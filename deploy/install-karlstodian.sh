#!/usr/bin/env bash
# Installs (or updates) Karlstodian inside a Debian LXC. Run as root inside the container,
# from the unpacked bundle (gradlew deployBundle -> build/karlstodian-deploy.tgz):
#   bash /root/karlstodian-deploy/install-karlstodian.sh
#
# Expects next to it: karlstodian.jar and karlstodian-update.sh. Optionally data/ from your PC
# (settings, API keys, database); it's copied into place only on first install, and the bundle
# is then removed. Safe to re-run: it keeps the server's data.
#
# Day-to-day updates don't need this: the karlstodian-update timer installs each new GitHub
# release (every merge to main) within about 5 minutes.
set -euo pipefail

SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$SRC/karlstodian.jar"
HOME_DIR="/opt/karlstodian"
SVC="karlstodian"
PORT=8000

ok()   { echo -e "\e[1;32m==>\e[0m $*"; }
warn() { echo -e "\e[1;33m !\e[0m $*"; }
die()  { echo -e "\e[1;31m x\e[0m $*" >&2; exit 1; }

[ "$(id -u)" = 0 ] || die "Run as root."
[ -f "$JAR" ] || die "Missing $JAR"
[ -f "$SRC/karlstodian-update.sh" ] || die "Missing $SRC/karlstodian-update.sh"
command -v apt-get >/dev/null || die "This expects a Debian/Ubuntu container."

find_java() { ls -d /usr/lib/jvm/*21*/bin/java 2>/dev/null | head -1; }

export DEBIAN_FRONTEND=noninteractive
if [ -z "$(find_java)" ]; then
  ok "Installing Java 21 (first run takes a minute or two)"
  apt-get update -qq
  apt-get install -y -qq ca-certificates curl >/dev/null
  if ! apt-get install -y -qq openjdk-21-jre-headless >/dev/null 2>&1; then
    # Debian 12 only ships Java 17; use Eclipse Adoptium's repository instead
    ok "Java 21 isn't in this Debian's repos; adding the Eclipse Adoptium (Temurin) repository"
    apt-get install -y -qq gpg >/dev/null
    install -d -m 755 /etc/apt/keyrings
    curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor --yes -o /etc/apt/keyrings/adoptium.gpg
    echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release; echo "$VERSION_CODENAME") main" \
      > /etc/apt/sources.list.d/adoptium.list
    apt-get update -qq
    apt-get install -y -qq temurin-21-jre >/dev/null
  fi
fi
JAVA="$(find_java)"
[ -n "$JAVA" ] || die "Java 21 didn't install."
ok "Using $("$JAVA" -version 2>&1 | head -1)"
if ! command -v curl >/dev/null || ! command -v jq >/dev/null; then
  apt-get update -qq
  apt-get install -y -qq curl jq >/dev/null
fi

ok "Installing Karlstodian into $HOME_DIR"
id "$SVC" >/dev/null 2>&1 || useradd --system --home-dir "$HOME_DIR" --shell /usr/sbin/nologin "$SVC"
mkdir -p "$HOME_DIR/data"
install -m 644 "$JAR" "$HOME_DIR/karlstodian.jar.new"
mv -f "$HOME_DIR/karlstodian.jar.new" "$HOME_DIR/karlstodian.jar"
echo local > "$HOME_DIR/VERSION"   # the updater swaps this for the newest release

if [ -d "$SRC/data" ]; then
  if [ -f "$HOME_DIR/data/pruner.db" ]; then
    warn "This server already has its own data; leaving it alone (ignoring the copy from the PC)."
  else
    ok "Bringing over your settings, API keys and flags from the PC"
    cp -a "$SRC/data/." "$HOME_DIR/data/"
  fi
fi
chown -R "$SVC:$SVC" "$HOME_DIR/data"
chmod 700 "$HOME_DIR/data"
[ -f "$HOME_DIR/data/settings.json" ] && chmod 600 "$HOME_DIR/data/settings.json"

ok "Setting up the $SVC service"
cat > "/etc/systemd/system/$SVC.service" <<UNIT
[Unit]
Description=Karlstodian (custodian of the media library)
After=network-online.target
Wants=network-online.target

[Service]
User=$SVC
WorkingDirectory=$HOME_DIR
Environment=PRUNER_HOST=0.0.0.0
Environment=PRUNER_PORT=$PORT
Environment=PRUNER_DB_PATH=$HOME_DIR/data/pruner.db
ExecStart=$JAVA -Xmx384m -XX:+UseSerialGC -jar $HOME_DIR/karlstodian.jar
Restart=on-failure
RestartSec=5
NoNewPrivileges=yes
ProtectSystem=full
ProtectHome=yes
PrivateTmp=yes
ReadWritePaths=$HOME_DIR/data

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable "$SVC" >/dev/null 2>&1
systemctl restart "$SVC"

ok "Setting up automatic updates from GitHub releases"
install -m 755 "$SRC/karlstodian-update.sh" /usr/local/sbin/karlstodian-update
cat > "/etc/systemd/system/$SVC-update.service" <<UNIT
[Unit]
Description=Install the newest Karlstodian release
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/karlstodian-update
UNIT
cat > "/etc/systemd/system/$SVC-update.timer" <<UNIT
[Unit]
Description=Check for a new Karlstodian release every 5 minutes

[Timer]
OnBootSec=2min
OnUnitActiveSec=5min
RandomizedDelaySec=30

[Install]
WantedBy=timers.target
UNIT
systemctl daemon-reload
systemctl enable --now "$SVC-update.timer" >/dev/null 2>&1

ok "Waiting for it to start"
for _ in $(seq 1 60); do
  curl -fs "http://127.0.0.1:$PORT/healthz" >/dev/null 2>&1 && break
  sleep 1
done
curl -fs "http://127.0.0.1:$PORT/healthz" >/dev/null 2>&1 || die "Karlstodian didn't start. See: journalctl -u $SVC -n 50"

rm -rf "$SRC" /root/karlstodian-deploy.tgz   # the bundle holds your API keys; don't leave a copy in /root

IP=$(hostname -I | awk '{print $1}')
echo
ok "Karlstodian is running: http://$IP:$PORT"
echo "    Timezone: $(cat /etc/timezone 2>/dev/null || date +%Z) (the nightly scan schedule uses it)"
echo "    Logs:     journalctl -u $SVC -f"
echo "    Restart:  systemctl restart $SVC"
echo "    Updates:  every 5 min from GitHub releases (journalctl -u $SVC-update; update now: karlstodian-update)"
