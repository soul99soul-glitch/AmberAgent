#!/usr/bin/env bash
# Bring up lan-bridge + SSH reverse tunnel to vps-japan (wg-home 10.77.77.1:3773).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
TOKEN="${SYNARA_AUTH_TOKEN:-}"
if [[ -z "$TOKEN" ]]; then
  for pid in $(pgrep -f 'app.asar/apps/server/dist/index.mjs' || true); do
    TOKEN=$(ps eww -p "$pid" 2>/dev/null | tr ' ' '\n' | awk -F= '/^SYNARA_AUTH_TOKEN=/{print $2; exit}')
    [[ -n "$TOKEN" ]] && break
  done
fi
if [[ -z "$TOKEN" ]]; then
  echo "Cannot find SYNARA_AUTH_TOKEN (is Synara desktop running?)" >&2
  exit 1
fi
if ! curl -fsS --max-time 2 http://127.0.0.1:3773/health >/dev/null; then
  pkill -f 'synara-lan-bridge.py' 2>/dev/null || true
  nohup python3 "$ROOT/synara-lan-bridge.py" --auth-token "$TOKEN" >/tmp/synara-lan-bridge.log 2>&1 &
  sleep 1
fi
curl -fsS --max-time 2 http://127.0.0.1:3773/health >/dev/null
pkill -f 'ssh .*3773:127.0.0.1:3773' 2>/dev/null || true
sleep 0.3
nohup ssh -N \
  -o ExitOnForwardFailure=yes \
  -o ServerAliveInterval=30 \
  -o ServerAliveCountMax=3 \
  -o TCPKeepAlive=yes \
  -o BatchMode=yes \
  -R 0.0.0.0:3773:127.0.0.1:3773 \
  vps-japan >/tmp/synara-ssh-tunnel.log 2>&1 &
disown || true
sleep 1
echo "local bridge:  http://127.0.0.1:3773/health"
echo "via WG (phone): http://10.77.77.1:3773/  (phone must be on home WireGuard)"
echo "Amber host=10.77.77.1 port=3773 token=<SYNARA_AUTH_TOKEN>"
ssh -o BatchMode=yes vps-japan 'curl -fsS --max-time 5 http://10.77.77.1:3773/health; echo'
