#!/usr/bin/env bash
# Installs the newest Karlstodian release from GitHub if it isn't the one running.
# Run by karlstodian-update.timer (every 5 minutes) as root; logs go to the journal:
#   journalctl -u karlstodian-update
# Run it by hand to update now: karlstodian-update
set -euo pipefail

REPO="kielpedia/karlstodian"
DIR="/opt/karlstodian"
SVC="karlstodian"
PORT=8000
JAR="$DIR/karlstodian.jar"

healthy() {
  for _ in $(seq 1 90); do
    curl -fs "http://127.0.0.1:$PORT/healthz" >/dev/null 2>&1 && return 0
    sleep 1
  done
  return 1
}

release=$(curl -fsSL -H "Accept: application/vnd.github+json" "https://api.github.com/repos/$REPO/releases/latest")
tag=$(jq -r '.tag_name // empty' <<<"$release")
[ -n "$tag" ] || { echo "No release found"; exit 0; }

current=$(cat "$DIR/VERSION" 2>/dev/null || echo none)
[ "$tag" = "$current" ] && exit 0
if [ "$tag" = "$(cat "$DIR/FAILED_VERSION" 2>/dev/null || true)" ]; then
  exit 0 # already tried this one and rolled back; wait for a newer release
fi

# Don't cut a scan short: try again next round
if systemctl is-active -q "$SVC"; then
  running=$(curl -fs "http://127.0.0.1:$PORT/api/scans" 2>/dev/null \
    | jq -r 'if length > 0 and .[0].finished_at == null then "yes" else "no" end' 2>/dev/null || echo no)
  [ "$running" = "yes" ] && { echo "Scan running; will update to $tag later"; exit 0; }
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
base="https://github.com/$REPO/releases/download/$tag"
curl -fsSL -o "$tmp/karlstodian.jar" "$base/karlstodian.jar"
curl -fsSL -o "$tmp/karlstodian.jar.sha256" "$base/karlstodian.jar.sha256"
(cd "$tmp" && sha256sum -c --quiet karlstodian.jar.sha256) || { echo "Checksum mismatch for $tag; not installing"; exit 1; }

echo "Updating $current -> $tag"
[ -f "$JAR" ] && cp -p "$JAR" "$JAR.prev"
install -m 644 "$tmp/karlstodian.jar" "$JAR.new"
mv -f "$JAR.new" "$JAR"
systemctl restart "$SVC"

if healthy; then
  echo "$tag" > "$DIR/VERSION"
  rm -f "$DIR/FAILED_VERSION"
  echo "Now running $tag"
else
  echo "$tag didn't come up; rolling back to $current"
  echo "$tag" > "$DIR/FAILED_VERSION"
  if [ -f "$JAR.prev" ]; then
    mv -f "$JAR.prev" "$JAR"
    systemctl restart "$SVC"
    healthy && echo "Rolled back to $current" || echo "Rollback didn't come up either: journalctl -u $SVC -n 50"
  fi
  exit 1
fi
