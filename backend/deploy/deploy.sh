#!/bin/bash
# Deploy backend/server.py to the Hetzner 16GB box (root@5.161.56.33).
# Idempotent: first run installs everything; later runs just sync + restart.
set -euo pipefail
BOX=root@5.161.56.33
DIR="$(cd "$(dirname "$0")" && pwd)"

ssh "$BOX" 'id meetingsremote >/dev/null 2>&1 || useradd -r -s /usr/sbin/nologin -d /opt/meetingsremote meetingsremote
mkdir -p /opt/meetingsremote
command -v caddy >/dev/null || {
  apt-get update -qq && apt-get install -y -qq debian-keyring debian-archive-keyring apt-transport-https curl
  curl -1sLf https://dl.cloudsmith.io/public/caddy/stable/gpg.key | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt > /etc/apt/sources.list.d/caddy-stable.list
  apt-get update -qq && apt-get install -y -qq caddy
}
apt-get install -y -qq python3-venv libpq5 >/dev/null
[ -d /opt/meetingsremote/venv ] || python3 -m venv /opt/meetingsremote/venv
/opt/meetingsremote/venv/bin/pip install -q psycopg2-binary'

scp -q "$DIR/../server.py" "$BOX:/opt/meetingsremote/server.py"
scp -q "$DIR/meetingsremote.service" "$BOX:/etc/systemd/system/meetingsremote.service"
scp -q "$DIR/Caddyfile" "$BOX:/etc/caddy/Caddyfile"

ssh "$BOX" 'chown -R meetingsremote:meetingsremote /opt/meetingsremote
chmod 600 /opt/meetingsremote/.env 2>/dev/null || echo "NOTE: create /opt/meetingsremote/.env (see backend/server.py header)"
systemctl daemon-reload
systemctl enable --now meetingsremote
systemctl restart meetingsremote caddy
sleep 1; curl -sf http://127.0.0.1:8791/health && echo " backend OK"'
