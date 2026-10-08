#!/usr/bin/env python3
"""Create/pair a Synara External MCP integration and emit Amber import JSON.

Requires:
  - Synara desktop running (loopback backend in ~/.synara/userdata/server-runtime.json)
  - websockets (pip install websockets)
  - SYNARA_AUTH_TOKEN (env, or discoverable from a running synara-lan-bridge cmdline)

This talks to loopback WS as owner (legacy ?token=), then pairs over HTTP.
Pairing credential prefix must be syn_mcp_v1_.

Usage:
  python3 scripts/synara-external-mcp-setup.py
  python3 scripts/synara-external-mcp-setup.py --lan-host 192.168.100.107 --lan-port 3773
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import re
import secrets
import subprocess
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

try:
    import websockets
except ImportError:
    print("Missing dependency: pip install websockets", file=sys.stderr)
    sys.exit(1)

CREDENTIAL_PREFIX = "syn_mcp_v1_"
PAIRING_PREFIX = "syn_pair_v1_"
RUNTIME_PATH = Path(
    os.environ.get("SYNARA_RUNTIME_JSON")
    or Path.home() / ".synara/userdata/server-runtime.json"
)


def read_runtime() -> dict:
    try:
        return json.loads(RUNTIME_PATH.read_text(encoding="utf-8"))
    except Exception:
        return {}


def discover_auth_token() -> str:
    env = os.environ.get("SYNARA_AUTH_TOKEN", "").strip()
    if env:
        return env
    try:
        out = subprocess.check_output(["ps", "-ax", "-o", "args="], text=True, errors="ignore")
    except Exception:
        out = ""
    for line in out.splitlines():
        if "synara-lan-bridge.py" not in line or "--auth-token" not in line:
            continue
        m = re.search(r"--auth-token\s+(\S+)", line)
        if m:
            return m.group(1).strip()
    return ""


def http_json(method: str, url: str, data=None, headers=None, timeout: float = 8.0):
    h = dict(headers or {})
    body = None
    if data is not None:
        body = json.dumps(data).encode("utf-8")
        h.setdefault("Content-Type", "application/json")
    req = urllib.request.Request(url, data=body, headers=h, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, dict(resp.headers), resp.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode("utf-8")


def negotiate(base: str) -> dict:
    url = (
        f"{base}/ws/negotiate"
        "?x-synara-client-build=amber-companion"
        "&x-synara-protocol-epoch=1"
        "&x-synara-protocol-min-revision=1"
        "&x-synara-protocol-max-revision=1"
    )
    st, _, body = http_json("GET", url)
    if st != 200:
        raise RuntimeError(f"negotiate failed HTTP {st}: {body[:200]}")
    return json.loads(body)


def ws_url(base_host_port: str, token: str, neg: dict) -> str:
    return (
        f"ws://{base_host_port}/ws"
        f"?token={token}"
        f"&x-synara-client-build=amber-companion"
        f"&x-synara-protocol-epoch={neg['protocolEpoch']}"
        f"&x-synara-protocol-revision={neg['negotiatedRevision']}"
        f"&x-synara-server-instance={neg['serverInstanceId']}"
    )


async def rpc(base_http: str, host_port: str, token: str, method: str, payload, rid: str):
    neg = negotiate(base_http)
    async with websockets.connect(
        ws_url(host_port, token, neg),
        open_timeout=5,
        close_timeout=2,
        max_size=8_000_000,
    ) as ws:
        await ws.send(
            json.dumps(
                {
                    "_tag": "Request",
                    "id": rid,
                    "tag": method,
                    "payload": payload,
                    "headers": [],
                }
            )
        )
        while True:
            msg = await asyncio.wait_for(ws.recv(), timeout=8)
            data = json.loads(msg)
            if data.get("_tag") == "Exit" and data.get("requestId") == rid:
                return data


async def ensure_pairing_code(
    base_http: str, host_port: str, token: str, name: str, force_new: bool
):
    listed = await rpc(
        base_http, host_port, token, "server.listExternalMcpIntegrations", {}, "1"
    )
    if listed["exit"]["_tag"] != "Success":
        raise RuntimeError(f"list failed: {listed}")
    ints = listed["exit"]["value"]
    candidates = [
        x
        for x in ints
        if x.get("name") == name
        and x.get("pairedAt") is None
        and x.get("revokedAt") is None
    ]
    if candidates:
        integration_id = candidates[0]["integrationId"]
        refreshed = await rpc(
            base_http,
            host_port,
            token,
            "server.refreshExternalMcpPairing",
            {"integrationId": integration_id},
            "2",
        )
        if refreshed["exit"]["_tag"] != "Success":
            raise RuntimeError(f"refresh failed: {refreshed}")
        val = refreshed["exit"]["value"]
        return integration_id, val["pairingCode"]

    already_paired = [
        x
        for x in ints
        if x.get("name") == name
        and x.get("pairedAt") is not None
        and x.get("revokedAt") is None
    ]
    if already_paired and not force_new:
        raise RuntimeError(
            f'Integration "{name}" is already paired ({already_paired[0]["integrationId"]}). '
            "Re-run with --force-new to create another, or reuse the saved credential in "
            "/tmp/synara_amber_external_mcp.json / --emit-from-meta."
        )

    created = await rpc(
        base_http,
        host_port,
        token,
        "server.createExternalMcpIntegration",
        {
            "name": name,
            "projectScope": "all",
            "capabilities": [
                "projects:read",
                "tasks:create",
                "tasks:wait",
                "tasks:read",
            ],
            "expiresInDays": 30,
            "clientKind": "other",
        },
        "3",
    )
    if created["exit"]["_tag"] != "Success":
        raise RuntimeError(f"create failed: {created}")
    val = created["exit"]["value"]
    return val["integration"]["integrationId"], val["pairingCode"]


def pair(base_http: str, pairing_code: str) -> dict:
    if not pairing_code.startswith(PAIRING_PREFIX):
        raise RuntimeError(f"unexpected pairing prefix: {pairing_code[:20]}")
    credential = CREDENTIAL_PREFIX + secrets.token_urlsafe(32)
    st, _, body = http_json(
        "POST",
        f"{base_http}/api/mcp/external/pair",
        {"pairingCode": pairing_code, "credential": credential},
    )
    if st != 200:
        raise RuntimeError(f"pair failed HTTP {st}: {body[:300]}")
    return json.loads(body)


def verify_mcp(url: str, credential: str) -> list[str]:
    auth = {
        "Authorization": f"Bearer {credential}",
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
    }
    st, hdrs, body = http_json(
        "POST",
        url,
        {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "protocolVersion": "2025-03-26",
                "capabilities": {},
                "clientInfo": {"name": "amber-agent", "version": "0"},
            },
        },
        auth,
    )
    if st != 200:
        raise RuntimeError(f"initialize failed HTTP {st}: {body[:300]}")
    sid = hdrs.get("Mcp-Session-Id") or hdrs.get("mcp-session-id")
    h = dict(auth)
    if sid:
        h["Mcp-Session-Id"] = sid
    http_json(
        "POST",
        url,
        {"jsonrpc": "2.0", "method": "notifications/initialized"},
        h,
    )
    st2, _, body2 = http_json(
        "POST",
        url,
        {"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}},
        h,
    )
    if st2 != 200:
        raise RuntimeError(f"tools/list failed HTTP {st2}: {body2[:300]}")
    tools = json.loads(body2).get("result", {}).get("tools", [])
    return [t.get("name", "") for t in tools]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--name", default="AmberAgent", help="Integration display name")
    ap.add_argument("--lan-host", default="192.168.100.107")
    ap.add_argument("--lan-port", type=int, default=3773)
    ap.add_argument(
        "--out",
        default="/tmp/synara_amber_mcp_import.json",
        help="Amber MCP import JSON path",
    )
    ap.add_argument(
        "--force-new",
        action="store_true",
        help="Create a new integration even if one with the same name is already paired",
    )
    ap.add_argument(
        "--emit-from-meta",
        action="store_true",
        help="Only rewrite Amber import JSON from /tmp/synara_amber_external_mcp.json",
    )
    args = ap.parse_args()

    if args.emit_from_meta:
        meta_path = Path("/tmp/synara_amber_external_mcp.json")
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        cred = meta.get("credential") or (
            (meta.get("authorization") or "").removeprefix("Bearer ").strip()
        )
        url = meta.get("url") or f"http://{args.lan_host}:{args.lan_port}/mcp/external"
        if not cred:
            print("No credential in meta file", file=sys.stderr)
            return 1
        amber_import = {
            "mcpServers": {
                "Synara": {
                    "type": "streamable_http",
                    "url": url,
                    "headers": {"Authorization": f"Bearer {cred}"},
                }
            }
        }
        out = Path(args.out)
        out.write_text(json.dumps(amber_import, indent=2) + "\n", encoding="utf-8")
        print(out.read_text(encoding="utf-8"))
        return 0

    runtime = read_runtime()
    port = int(runtime.get("port") or 0)
    if port <= 0:
        print(f"Cannot read Synara runtime port from {RUNTIME_PATH}", file=sys.stderr)
        return 1
    token = discover_auth_token()
    if not token:
        print("SYNARA_AUTH_TOKEN not found (env or lan-bridge cmdline)", file=sys.stderr)
        return 1

    base_http = f"http://127.0.0.1:{port}"
    host_port = f"127.0.0.1:{port}"
    print(f"Synara loopback: {base_http}")

    try:
        integration_id, pairing_code = asyncio.run(
            ensure_pairing_code(
                base_http, host_port, token, args.name, force_new=args.force_new
            )
        )
    except Exception as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 1
    print(f"integration: {integration_id}")
    paired = pair(base_http, pairing_code)
    credential = paired["credential"]
    print(f"paired OK · expires {paired.get('expiresAt')}")

    lan_url = f"http://{args.lan_host}:{args.lan_port}/mcp/external"
    loopback_url = f"{base_http}/mcp/external"
    for label, url in (("loopback", loopback_url), ("lan", lan_url)):
        try:
            names = verify_mcp(url, credential)
            print(f"MCP {label} OK · tools={names[:6]}{'...' if len(names) > 6 else ''}")
        except Exception as e:
            print(f"MCP {label} FAIL: {e}", file=sys.stderr)
            if label == "loopback":
                return 1

    amber_import = {
        "mcpServers": {
            "Synara": {
                "type": "streamable_http",
                "url": lan_url,
                "headers": {"Authorization": f"Bearer {credential}"},
            }
        }
    }
    out = Path(args.out)
    out.write_text(json.dumps(amber_import, indent=2) + "\n", encoding="utf-8")
    meta = {
        "integrationId": integration_id,
        "url": lan_url,
        "loopbackUrl": loopback_url,
        "credential": credential,
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "amberImportPath": str(out),
    }
    Path("/tmp/synara_amber_external_mcp.json").write_text(
        json.dumps(meta, indent=2) + "\n", encoding="utf-8"
    )

    print()
    print(f"Amber import JSON written to: {out}")
    print("In Amber: Settings → MCP → Import, paste that JSON.")
    print("Then enable the Synara server on the current Assistant.")
    print()
    print(out.read_text(encoding="utf-8"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
