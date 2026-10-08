#!/usr/bin/env bash
# Reverse-tunnel Mac lan-bridge (:3773) to a Japan VPS so phones outside
# the home LAN can reach Synara the same way as on Wi‑Fi.
#
# Topology:
#   phone → VPS:REMOTE_PORT → (ssh -R) → Mac 127.0.0.1:3773 (lan-bridge) → Synara
#
# Prerequisites on VPS (/etc/ssh/sshd_config):
#   GatewayPorts clientspecified
#   AllowTcpForwarding yes
# then: sudo systemctl reload sshd
#
# Usage:
#   export SYNARA_VPS=user@vps.example.com   # or root@x.x.x.x
#   # optional: SYNARA_VPS_PORT=22 SYNARA_REMOTE_PORT=3773 SYNARA_LOCAL_PORT=3773
#   ./scripts/synara-ssh-tunnel.sh
#
# Safer (recommended): bind only on VPS loopback, then reach it via WireGuard:
#   SYNARA_BIND=127.0.0.1 ./scripts/synara-ssh-tunnel.sh
#   phone WireGuard → VPS → http://127.0.0.1:3773  (from VPS) / http://VPS_WG_IP:3773
set -euo pipefail

# Default Host alias from ~/.ssh/config (Port/IdentityFile come from that file).
VPS="${SYNARA_VPS:-vps-japan}"
VPS_PORT="${SYNARA_VPS_PORT:-}"
REMOTE_PORT="${SYNARA_REMOTE_PORT:-3773}"
LOCAL_PORT="${SYNARA_LOCAL_PORT:-3773}"
# 0.0.0.0 + UFW on wg-home only (this house: 10.77.77.1). Prefer not opening eth0.
BIND="${SYNARA_BIND:-0.0.0.0}"
BRIDGE_DIR="$(cd "$(dirname "$0")" && pwd)"
KEEPALIVE="${SYNARA_SSH_KEEPALIVE:-30}"

if [[ -z "$VPS" ]]; then
  echo "Set SYNARA_VPS=vps-japan (or user@host)" >&2
  exit 1
fi

if ! curl -fsS --max-time 2 "http://127.0.0.1:${LOCAL_PORT}/health" >/dev/null 2>&1; then
  echo "Local lan-bridge not healthy on 127.0.0.1:${LOCAL_PORT}" >&2
  echo "Start Synara desktop, then in another terminal:" >&2
  echo "  python3 ${BRIDGE_DIR}/synara-lan-bridge.py" >&2
  exit 1
fi

echo "Tunnel: ${BIND}:${REMOTE_PORT} on ${VPS}  →  127.0.0.1:${LOCAL_PORT} (lan-bridge)"
echo "Keep this terminal open. Ctrl+C stops the tunnel (not the bridge)."
echo

# ExitOnForwardFailure: fail fast if remote port busy / GatewayPorts off
# ServerAlive*: survive home NAT / CGNAT idle drops
SSH_PORT_ARGS=()
if [[ -n "${VPS_PORT}" ]]; then
  SSH_PORT_ARGS=(-p "${VPS_PORT}")
fi

echo "Amber (phone on home WG): http://10.77.77.1:${REMOTE_PORT}/"
echo "Keep Synara desktop + lan-bridge running."
echo

exec ssh -N \
  -o ExitOnForwardFailure=yes \
  -o ServerAliveInterval="${KEEPALIVE}" \
  -o ServerAliveCountMax=3 \
  -o TCPKeepAlive=yes \
  "${SSH_PORT_ARGS[@]}" \
  -R "${BIND}:${REMOTE_PORT}:127.0.0.1:${LOCAL_PORT}" \
  "${VPS}"
