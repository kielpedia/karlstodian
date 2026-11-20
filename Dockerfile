# syntax=docker/dockerfile:1
# Karlstodian as a container (Docker or Kubernetes). Build: docker build -t karlstodian .
# Data (database + settings.json) lives in $KARLSTODIAN_DATA (/data): put a volume there.
# Settings and keys can come from KARLSTODIAN_<FIELD> environment variables. See deploy/DOCKER.md.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# Gradle and the dependencies first, so code changes don't download them again
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon --console=plain dependencies > /dev/null
COPY src src
ARG APP_VERSION=dev
RUN ./gradlew --no-daemon --console=plain buildFatJar -PappVersion="$APP_VERSION"

FROM eclipse-temurin:21-jre
# Fixed uid/gid 1000 so volume permissions stay the same across restarts and rebuilds
# (the base image's own uid-1000 user "ubuntu" is removed first).
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl tzdata \
 && rm -rf /var/lib/apt/lists/* \
 && (userdel --remove ubuntu 2>/dev/null || true) \
 && groupadd --gid 1000 karlstodian \
 && useradd --uid 1000 --gid 1000 --home-dir /app --no-create-home --shell /usr/sbin/nologin karlstodian \
 && mkdir -p /data && chown 1000:1000 /data
COPY --from=build /src/build/libs/karlstodian.jar /app/karlstodian.jar

# MaxRAMPercentage: size the heap from the container's memory limit, not the node's RAM
ENV KARLSTODIAN_DATA=/data \
    KARLSTODIAN_HOST=0.0.0.0 \
    KARLSTODIAN_PORT=8000 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"
USER 1000:1000
WORKDIR /app
VOLUME /data
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8000/healthz || exit 1
# exec: java is PID 1 and gets SIGTERM directly; its shutdown hook stops the server and closes SQLite
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/karlstodian.jar"]
