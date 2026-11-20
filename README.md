# Karlstodian

A small self-hosted web app that scores Plex movies and TV shows as removal candidates, for a
Radarr/Sonarr + qBittorrent + Plex setup that uses hardlinks. Read-only for now: it scans and
scores, it doesn't delete anything.

- **Score 0-100** (higher = remove first): weighted average of size (percentile in the library),
  time on Plex, watch activity (across all users, via Tautulli; shows by share of episodes watched)
  and quality (share at 720p or lower). Weights are live sliders in the UI, with a "why this
  score" breakdown per title.
- **Not scored:** titles you protect (flag in the app, or a `prune-keep` tag in Radarr/Sonarr),
  titles not in Plex, and recent additions. Low quality can be exempted per title (`prune-quality-ok`).
- Settings (URLs, API keys) are entered in the web UI and never sent back to the browser.

Kotlin, Ktor, SQLite. No login: run it on your LAN only.

## Run

Needs Java 21.

```
./gradlew run                 # http://localhost:8000
./gradlew test
./gradlew buildFatJar         # build/libs/karlstodian.jar -> java -jar karlstodian.jar
```

Server install (Debian LXC, systemd, automatic updates from releases): see `deploy/DEPLOY.md`.

Docker: `ghcr.io/kielpedia/karlstodian:latest`, with a compose file; see `deploy/DOCKER.md`.
