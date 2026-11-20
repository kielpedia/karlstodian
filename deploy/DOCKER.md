# Running Karlstodian in a container (Docker or Kubernetes)

An alternative to the LXC install in [DEPLOY.md](DEPLOY.md). Every merge to `main` publishes
`ghcr.io/kielpedia/karlstodian` with these tags:

- `:main`, the newest build from `main`
- `:<full commit sha>`, one tag per commit
- `:build-<n>`, the same number as the jar release

Each pushed image has a signed build provenance attestation.

## What the image does

| | |
|---|---|
| Data | `KARLSTODIAN_DATA=/data`: `pruner.db` and `settings.json` go there. Put a volume on it; nothing is stored in the image. |
| Settings | Any setting can be an env var `KARLSTODIAN_<FIELD>` (e.g. `KARLSTODIAN_RADARR_API_KEY`), so keys can come from a Kubernetes Secret. These win over `settings.json`, show as locked on the Settings page, and are never written to `settings.json`, so rotating the Secret takes effect on restart. Anything not set this way is edited on the Settings page as usual. `PRUNER_<FIELD>` still works too. |
| Media | Read-only and optional, for hardlink link counts. Mount the share at `/mnt` and the paths match what Radarr/Sonarr/qBittorrent report (`/mnt/media/...`, `/mnt/torrents/...`), the same as the LXC today. Mounted anywhere else, set Path mappings on the Settings page or in `KARLSTODIAN_PATH_MAPS`, e.g. `[["/mnt", "/media"]]`. |
| Network | Listens on `0.0.0.0:8000`. `GET /healthz` returns `{"ok":true,"version":...}`. There's no login, so keep it on the LAN. |
| Memory | `JAVA_OPTS` defaults to `-XX:MaxRAMPercentage=75 -XX:+UseSerialGC`, so the heap follows the container's memory limit. 512 Mi is plenty. |
| Shutdown | Java runs as PID 1. On SIGTERM it finishes in-flight requests, stops the timers and scans, closes SQLite and logs `Stopped cleanly`. |
| User | uid/gid **1000**, not root. The data volume must be writable by it. |
| Time | Set `TZ` (e.g. `America/Chicago`): the scan schedule uses local time. |

Settings field names are the keys of `settings.json`, e.g. `plex_url`, `plex_token`,
`tautulli_url`, `tautulli_api_key`, `radarr_url`, `radarr_api_key`, `sonarr_url`,
`sonarr_api_key`, `qbit_url`, `qbit_username`, `qbit_password`, `path_maps`, `scan_cron`,
`dry_run`, `grace_days`. Lists and maps are JSON.

## Docker Compose

```
mkdir karlstodian && cd karlstodian
curl -fsSLO https://raw.githubusercontent.com/kielpedia/karlstodian/main/deploy/docker-compose.yml
mkdir data && sudo chown 1000:1000 data
docker compose up -d
```

Updates: `docker compose pull && docker compose up -d`, or pin a `:<sha>` tag and change it.

## Kubernetes

The essentials, for a manifest in your infra repo:

- **One replica**, `strategy: Recreate`. SQLite allows one writer, and two pods would both run removals.
- **Volume:** a PVC mounted at `/data`, with `securityContext: {runAsUser: 1000, runAsGroup: 1000, fsGroup: 1000, runAsNonRoot: true}`.
- **Secrets:** `envFrom: [{secretRef: {name: karlstodian}}]`, with keys like `KARLSTODIAN_RADARR_API_KEY`.
- **Probes:** `livenessProbe` and `readinessProbe` on `httpGet: {path: /healthz, port: 8000}`. Startup takes a few seconds, so give it an `initialDelaySeconds` of about 10.
- **Shutdown:** `terminationGracePeriodSeconds: 30`.
- **Memory:** `resources.limits.memory: 512Mi`.
- **Media (optional):** mount it read-only at `/mnt`.
- **Image:** pin it by digest and verify it before deploying:

  ```
  gh attestation verify oci://ghcr.io/kielpedia/karlstodian@sha256:<digest> \
    --repo kielpedia/karlstodian --source-ref refs/heads/main \
    --signer-workflow kielpedia/karlstodian/.github/workflows/ci.yml --deny-self-hosted-runners
  ```

## Moving from the LXC

Copy `settings.json` and `pruner.db` from `/opt/karlstodian/data` to the new volume and give
them to uid 1000. **Stop the LXC first** (`systemctl disable --now karlstodian karlstodian-update.timer`):
two copies working from the same data would both run the removal timer.

Keys already in `settings.json` keep working. If you move them to a Secret, the Secret's values win.

## Build it yourself

```
docker build -t karlstodian --build-arg APP_VERSION=my-build .
docker run -d -p 8000:8000 -v "$PWD/data:/data" karlstodian
```

`data/` is never copied into the image: `.dockerignore` only lets the build inputs in.
