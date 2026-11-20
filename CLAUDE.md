# Karlstodian

"The custodian of the karlpedia media library." A small web app that scores Plex content
(movies, and TV shows as a whole) as removal candidates, for a Radarr/Sonarr + qBittorrent +
Plex setup that uses hardlinks.

Kotlin rewrite (Nov 2026) of an earlier Python/FastAPI version (not in this repo). Same API,
same JSON, same `data/pruner.db` schema and `data/settings.json`, so the old data carried over
unchanged. Verified against the real services: identical items, scores and order.

**This repo is public.** Never commit `data/`, `.env`, tokens, keys, or the owner's network
details (IPs, hostnames, container numbers). Those live in the owner's private `homelab-infra`
repo and on the server.

## Run it (dev machine is Windows)

- JDK 21 (Temurin). If `java` isn't on PATH, set JAVA_HOME to it. Gradle comes via `gradlew`.
- `start-karlstodian.bat` -> http://localhost:8000 (Library) and /settings. Reads `data/` in this folder.
- Tests: `gradlew test` (12 tests; inode/link-count/file-mode asserts only run on Linux).
- CLI: `gradlew run --args=check` (test connections), `gradlew run --args=scan` (one scan).
- Deployable: `gradlew buildFatJar` -> `build/libs/karlstodian.jar`, run with `java -jar`.
- Env: `PRUNER_HOST` (default 127.0.0.1; 0.0.0.0 on the server), `PRUNER_PORT` (8000),
  `PRUNER_DB_PATH`, and any setting as `PRUNER_<FIELD>` (lists/maps as JSON).

## Layout (src/main/kotlin/karlstodian)

- `Config.kt`: settings. Defaults from env/.env, overridden by `data/settings.json` (mode 600).
  `merge()` validates partial updates; blank secret = keep saved; changing a service URL requires
  re-entering its key (so a changed URL can't exfiltrate the saved key). Secrets never go to the browser.
  Field names stay snake_case: they are the settings.json keys, env var names and API fields.
- `Clients.kt`: Ktor clients for Plex, Tautulli, Radarr, Sonarr (API v3), qBittorrent. `Http.engineOverride`
  lets tests use a MockEngine.
- `Scan.kt`: read-only scan, joins everything per movie / per series into SQLite. A failed service
  keeps its cached data and marks the run `partial`. Inode/nlink via `unix:` file attributes (Linux only).
- `Scoring.kt`: score 0-100 (higher = remove first), weighted average of size/age/watch/quality.
  Uses Python-style rounding (half-even on exact value) to match the old app.
- `Db.kt`: plain JDBC + a small `Table` mapper over the @Serializable rows in `Models.kt`.
  Datetimes stored as naive UTC text, booleans 0/1 (SQLAlchemy's format). New columns go in `MIGRATIONS`.
- `Removal.kt`: the removal workflow. `mark` puts titles in Leaving Soon (grace period, Plex
  collection per library section via `syncLeavingSoon`); `processDue` (hourly + after each scan)
  cancels watched/protected ones, removes due ones (Radarr/Sonarr unmonitor + delete files, then
  torrents with data once seeded `seed_min_days`/`seed_rules`), and finishes seeding torrents.
  Dry run (default on) only writes "would ..." audit lines. Tables `removal`, `removaltorrent`, `audit`.
  Tracker URLs carry passkeys: only the host is ever stored (`trackerHost`).
- `Main.kt`: Ktor routes, cron scheduler (cron-utils, UNIX syntax, server local time), hourly removal timer, `main`.
- `src/main/resources/static/*.html`: Library, Removals, Settings. Plain HTML+JS, no build step.

Keep the `PRUNER_*` env prefix and `data/pruner.db` - renaming would orphan existing data.

## Delivery (CI/CD)

- `.github/workflows/ci.yml`: every PR and push runs the tests. A push to `main` also builds
  the jar, attests its build provenance and publishes it as release `build-<run number>`
  (`karlstodian.jar` + `karlstodian.jar.sha256`). GitHub-hosted runners only.
- The server pulls: `deploy/karlstodian-update.sh` runs from a systemd timer every 5 minutes,
  installs the newest release (checksum checked), restarts, health-checks `/healthz` and rolls
  back to the previous jar if it doesn't come up. It skips a round while a scan is running.
  So **merging to `main` deploys**. Rollback = `git revert` on `main`, which ships a new release.
  A release that fails its health check is recorded in `/opt/karlstodian/FAILED_VERSION` and not retried.
- Container: `Dockerfile` (multi-stage, runs as uid 10001, data in `/data`), `deploy/docker-compose.yml`,
  `deploy/DOCKER.md`. CI's `image` job builds and smoke-tests it on every PR and pushes
  `ghcr.io/kielpedia/karlstodian:build-<n>` + `:latest` (with attestation) on `main`. The owner's
  server still runs the LXC install; the image is an alternative, not what's deployed.
- `deploy/install-karlstodian.sh` is the one-time (re)install: Java 21, `/opt/karlstodian`, the
  `karlstodian` service and the updater timer. See `deploy/DEPLOY.md`.

## Owner's requirements and decisions

- TV shows are judged as a whole series, never per file.
- Factors: size, age on Plex, watch activity, quality.
- Plays count across **all** Plex users (Tautulli). Per-user drill-down in the item panel.
- Shows: "watched" = **share of episodes** watched (distinct episodes any user finished, by
  season/episode number, vs Plex `leafCount`; max with Plex `viewedLeafCount`).
- Low quality = 720p or lower, exemptible per title (app flag or `prune-quality-ok` tag).
  `prune-keep` tag / Protect flag = never scored.
- Not scored: protected, not matched in Plex, added in the last 60 days.
- Seed-time guard is a stretch goal; ratio/seeding_time from qBittorrent are stored.
- Settings are entered in the web UI, not files.
- Deletes, when built: dry-run by default, "Leaving Soon" Plex collection with a grace period,
  then delete via Radarr/Sonarr (deleteFiles + unmonitor) and remove the torrent+data in qBittorrent.
- Runs in **its own Debian LXC** (only that LXC needs a JVM), LAN-only behind the owner's Caddy,
  no login (owner's choice). The owner runs all server commands; give exact commands.

- Removal: the owner picks titles (no auto-selection); 14-day grace in a Leaving Soon Plex
  collection; automatic removal when it ends, plus "Remove now"; a finished play during the grace
  period keeps it; torrents are deleted only after their seed time.

## Next

1. Removal workflow shipped in PRs #2-#4 (Nov 2026) with dry run on. Owner checks the log on the
   server, then turns dry run off and removes one title by hand first.
2. Reclaimable space (milestone 2) was measured and dropped: deleting title + torrent frees ~ the
   shown size. Inode data is collected (media share mounted read-only at /mnt in the server's LXC).
3. Stretch: per-user views; "not in Plex" fix-up; "free X TB" helper for picking titles.
