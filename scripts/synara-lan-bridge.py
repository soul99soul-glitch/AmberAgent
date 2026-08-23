#!/usr/bin/env python3
"""Expose the running Synara desktop backend (loopback) on the LAN.

Desktop Synara binds 127.0.0.1 only and rejects browser WebSocket upgrades that
carry a non-loopback Origin (403). This proxy:

  1) Forwards HTTP + WebSocket to the desktop backend
  2) On WS upgrade to /ws: injects ?token= if missing
  3) Rewrites Host + Origin to the loopback backend so 403 Origin checks pass
  4) Tracks server-runtime.json per connection, so it survives desktop restarts
     (the desktop picks a new random port/token each launch)

Usage:
  python3 scripts/synara-lan-bridge.py
"""
from __future__ import annotations

import argparse
import json
import os
import re
import socket
import subprocess
import sys
import threading
import urllib.request
from urllib.parse import parse_qsl, quote, urlencode, urlsplit, urlunsplit


def read_runtime() -> dict:
    # SYNARA_RUNTIME_JSON override exists for tests only.
    path = os.environ.get("SYNARA_RUNTIME_JSON") or os.path.expanduser(
        "~/.synara/userdata/server-runtime.json"
    )
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def discover_token() -> str | None:
    try:
        out = subprocess.check_output(
            ["pgrep", "-f", "app.asar/apps/server/dist/index.mjs"],
            text=True,
            stderr=subprocess.DEVNULL,
        ).strip().splitlines()
        for pid in out:
            env = subprocess.check_output(
                ["ps", "eww", "-p", pid.strip()],
                text=True,
                errors="ignore",
            )
            for part in env.split():
                if part.startswith("SYNARA_AUTH_TOKEN="):
                    return part.split("=", 1)[1]
    except Exception:
        pass
    return None


class BackendResolver:
    """Re-resolves backend target/token when the desktop restarts.

    The desktop picks a fresh random port (and token) on every launch; a bridge
    that caches them at startup silently proxies to a dead port after the next
    restart. server-runtime.json is re-read per connection (cheap); token
    rediscovery (ps eww) only runs when the desktop pid changes. Pinned CLI
    values always win and are never refreshed.
    """

    def __init__(self, pinned_target: tuple[str, int] | None, pinned_token: str | None):
        self._pinned_target = pinned_target
        self._pinned_token = pinned_token
        self._lock = threading.Lock()
        self._pid: int | None = None
        self._target: tuple[str, int] | None = pinned_target
        self._token: str | None = pinned_token

    def resolve(self) -> tuple[tuple[str, int], str | None]:
        runtime = read_runtime()
        pid = runtime.get("pid")
        with self._lock:
            if pid and pid != self._pid:
                self._pid = pid
                if self._pinned_target is None:
                    host = str(runtime.get("host") or "127.0.0.1")
                    port = int(runtime.get("port") or 0)
                    if port:
                        self._target = (host, port)
                if self._pinned_token is None:
                    self._token = discover_token() or self._token
            return self._target or ("127.0.0.1", 52944), self._token


def parse_host_port(value: str, default_host: str, default_port: int) -> tuple[str, int]:
    if ":" in value:
        host, port_s = value.rsplit(":", 1)
        return host or default_host, int(port_s)
    return default_host, int(value)


def pipe(src: socket.socket, dst: socket.socket) -> None:
    try:
        while True:
            data = src.recv(65536)
            if not data:
                break
            dst.sendall(data)
    except Exception:
        pass
    finally:
        try:
            dst.shutdown(socket.SHUT_WR)
        except Exception:
            pass


_FIRST_LINE_RE = re.compile(rb"^([A-Z]+) (\S+) (HTTP/\d\.\d)\r\n")


def rewrite_client_request(
    header_bytes: bytes,
    *,
    token: str | None,
    backend_host: str,
    backend_port: int,
) -> bytes:
    """Rewrite first HTTP request: Host/Origin + optional WS token inject."""
    if b"\r\n\r\n" not in header_bytes:
        return header_bytes

    head, body = header_bytes.split(b"\r\n\r\n", 1)
    lines = head.split(b"\r\n")
    if not lines:
        return header_bytes

    first = lines[0]
    m = _FIRST_LINE_RE.match(first + b"\r\n")
    method = target = version = None
    if m:
        method = m.group(1).decode()
        target = m.group(2).decode("latin1", "ignore")
        version = m.group(3).decode()

    is_ws = b"upgrade: websocket" in head.lower()
    # Inject token into WS upgrades (endpoint renamed across desktop versions:
    # current builds use /ws/bootstrap, older builds used /ws).
    if is_ws and token and target:
        parts = urlsplit(target)
        path = parts.path or "/"
        norm = path.rstrip("/")
        if norm in ("/ws", "/ws/bootstrap"):
            q = dict(parse_qsl(parts.query, keep_blank_values=True))
            if not q.get("token"):
                q["token"] = token
            target = urlunsplit(("", "", path, urlencode(q), parts.fragment))
            first = f"{method} {target} {version}".encode()

    loop_origin = f"http://{backend_host}:{backend_port}".encode()
    loop_host = f"{backend_host}:{backend_port}".encode()
    out_lines = [first]
    saw_host = saw_origin = False
    for line in lines[1:]:
        low = line.lower()
        if low.startswith(b"host:"):
            out_lines.append(b"Host: " + loop_host)
            saw_host = True
        elif low.startswith(b"origin:"):
            # Desktop-managed backend rejects non-loopback Origin with 403.
            out_lines.append(b"Origin: " + loop_origin)
            saw_origin = True
        else:
            out_lines.append(line)
    if not saw_host:
        out_lines.append(b"Host: " + loop_host)
    # For browser WS, Origin is usually present; if missing, add loopback Origin
    # so server path is consistent.
    if is_ws and not saw_origin:
        out_lines.append(b"Origin: " + loop_origin)

    return b"\r\n".join(out_lines) + b"\r\n\r\n" + body


def recv_http_headers(sock: socket.socket, max_bytes: int = 65536) -> bytes:
    buf = b""
    while b"\r\n\r\n" not in buf and len(buf) < max_bytes:
        chunk = sock.recv(4096)
        if not chunk:
            break
        buf += chunk
    return buf


def handle(
    client: socket.socket,
    resolver: BackendResolver,
) -> None:
    remote: socket.socket | None = None
    try:
        first = recv_http_headers(client)
        if not first:
            client.close()
            return
        target, token = resolver.resolve()
        first = rewrite_client_request(
            first,
            token=token,
            backend_host=target[0],
            backend_port=target[1],
        )
        remote = socket.create_connection(target, timeout=10)
        remote.sendall(first)
        t1 = threading.Thread(target=pipe, args=(client, remote), daemon=True)
        t2 = threading.Thread(target=pipe, args=(remote, client), daemon=True)
        t1.start()
        t2.start()
        t1.join()
        t2.join()
    except Exception as e:
        print(f"connection error: {e}", file=sys.stderr, flush=True)
    finally:
        for s in (client, remote):
            if s is None:
                continue
            try:
                s.close()
            except Exception:
                pass


def print_pairing_qr(url: str) -> None:
    """Render the pairing URL as a terminal QR the phone can scan in Amber.

    Requires the optional `qrcode` package (pip3 install qrcode); without it the
    plain URL above is still shown for manual entry. invert=False keeps dark
    modules as solid glyphs (correct for light-terminal backgrounds); border=2
    gives the scanner a quieter zone than 1 while staying under 80 columns.
    """
    try:
        import qrcode
    except ImportError:
        print("(hint: pip3 install qrcode — prints a scannable pairing QR here)", flush=True)
        return
    qr = qrcode.QRCode(border=2)
    qr.add_data(url)
    qr.print_ascii(invert=False, tty=False)


def main() -> int:
    ap = argparse.ArgumentParser(description="LAN bridge for Synara desktop backend")
    ap.add_argument("--listen", default="0.0.0.0:3773")
    ap.add_argument(
        "--target",
        default=None,
        help="pin backend host:port (default: track server-runtime.json across desktop restarts)",
    )
    ap.add_argument(
        "--auth-token",
        default=os.environ.get("SYNARA_AUTH_TOKEN") or None,
        help="pin WS token (default: rediscover from the desktop process)",
    )
    args = ap.parse_args()

    listen_host, listen_port = parse_host_port(args.listen, "0.0.0.0", 3773)
    pinned_target = parse_host_port(args.target, "127.0.0.1", 52944) if args.target else None
    pinned_token = (args.auth_token or "").strip() or None
    resolver = BackendResolver(pinned_target, pinned_token)
    (target_host, target_port), token = resolver.resolve()

    try:
        with urllib.request.urlopen(f"http://{target_host}:{target_port}/health", timeout=3) as resp:
            print(f"target health: {resp.read().decode()}", flush=True)
    except Exception as e:
        # Not fatal: the target is re-resolved per connection, so the bridge
        # recovers on its own once the desktop is (re)started.
        print(f"WARNING: cannot reach target {(target_host, target_port)}: {e}", file=sys.stderr, flush=True)
        print("Is Synara desktop running? Bridge will keep retrying per connection.", file=sys.stderr, flush=True)

    lan_ip = None
    # Wi-Fi may sit on en0 or en1 (e.g. USB-Ethernet adapters take en0);
    # take the first interface with a DHCP address.
    for iface in ("en0", "en1"):
        try:
            lan_ip = subprocess.check_output(
                ["ipconfig", "getifaddr", iface], text=True
            ).strip()
            if lan_ip:
                break
        except Exception:
            continue

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        srv.bind((listen_host, listen_port))
    except OSError as e:
        print(f"ERROR: cannot bind {listen_host}:{listen_port}: {e}", file=sys.stderr)
        print(f"  lsof -nP -iTCP:{listen_port} -sTCP:LISTEN", file=sys.stderr)
        return 1
    srv.listen(128)

    print(f"PROXY_READY {listen_host}:{listen_port} -> {target_host}:{target_port}", flush=True)
    print(f"target mode: {'pinned' if pinned_target else 'tracking server-runtime.json'}", flush=True)
    print(f"WS rewrite: Host/Origin -> http://{target_host}:{target_port}", flush=True)
    print(f"WS token inject: {'yes' if token else 'NO — pass --auth-token'}", flush=True)
    if lan_ip:
        print(f"LAN open:  http://{lan_ip}:{listen_port}/", flush=True)
        if token:
            # Encode like the app's workspaceUrl() so tokens with & + # survive
            # both copy-paste and QR scanning.
            pairing_url = f"http://{lan_ip}:{listen_port}/?token={quote(token, safe='')}"
            print(f"With token: {pairing_url}", flush=True)
            print("Scan in Amber (Synara → 扫码一键配对):", flush=True)
            print_pairing_qr(pairing_url)
    print("Keep this terminal open. Ctrl+C to stop.", flush=True)

    while True:
        client, _ = srv.accept()
        threading.Thread(target=handle, args=(client, resolver), daemon=True).start()


if __name__ == "__main__":
    raise SystemExit(main())
