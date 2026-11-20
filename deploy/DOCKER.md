# Running Karlstodian in Docker

An alternative to the LXC install in [DEPLOY.md](DEPLOY.md). Every merge to `main` publishes an
image next to the jar release:

- `ghcr.io/kielpedia/karlstodian:latest`, the newest build
- `ghcr.io/kielpedia/karlstodian:build-<n>`, a fixed build (same number as the jar release)

It runs as uid 10001 (not root), listens on port 8000 and keeps everything in `/data`:
`settings.json` (API keys and tokens) and `pruner.db`. It has no login, so keep it on the LAN.

## Start it

```
mkdir karlstodian && cd karlstodian
curl -fsSLO https://raw.githubusercontent.com/kielpedia/karlstodian/main/deploy/docker-compose.yml
mkdir data && sudo chown 10001:10001 data
docker compose up -d
```

Open `http://<host>:8000/settings` and enter the service URLs and keys. `docker compose ps`
shows it as healthy once `/healthz` answers.

Set `TZ` in the compose file to your timezone: the scan schedule uses it.

### Bringing existing data along

Copy `settings.json` and `pruner.db` from the old install's `data/` into `./data`, then
`sudo chown -R 10001:10001 data`.

**Stop the old install first** (`systemctl disable --now karlstodian karlstodian-update.timer`
in the LXC). Two copies working from the same data would both run the removal timer.

### Hardlink counts (optional)

To see how many links each file has, the container needs the media share read-only. Mount it at
the same path Radarr, Sonarr and qBittorrent use (see the commented lines in the compose file),
or mount it anywhere and set Path mappings in Settings.

## Updates

Nothing updates itself in Docker. Pick one:

- By hand: `docker compose pull && docker compose up -d`
- Automatically: a tool like Watchtower, watching the `latest` tag
- Pinned: use a `build-<n>` tag and change it when you want to update

To roll back, set the image to the previous `build-<n>` and run `docker compose up -d`.

## Build it yourself

```
docker build -t karlstodian --build-arg APP_VERSION=my-build .
docker run -d -p 8000:8000 -v "$PWD/data:/data" karlstodian
```

`data/` is never copied into the image (`.dockerignore` only lets the build inputs in).

## Checking an image

Each pushed image has a signed build provenance attestation:

```
gh attestation verify oci://ghcr.io/kielpedia/karlstodian:latest --repo kielpedia/karlstodian
```
