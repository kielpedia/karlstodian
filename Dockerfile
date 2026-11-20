# syntax=docker/dockerfile:1
# Karlstodian as a container. Build: docker build -t karlstodian .
# Settings and the database live in /data (mount a volume there). See deploy/DOCKER.md.

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
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl tzdata \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --user-group --home-dir /app --shell /usr/sbin/nologin karlstodian \
 && mkdir -p /data && chown karlstodian:karlstodian /data
COPY --from=build /src/build/libs/karlstodian.jar /app/karlstodian.jar

ENV PRUNER_HOST=0.0.0.0 \
    PRUNER_PORT=8000 \
    PRUNER_DB_PATH=/data/pruner.db \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"
USER karlstodian
WORKDIR /app
VOLUME /data
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8000/healthz || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/karlstodian.jar"]
