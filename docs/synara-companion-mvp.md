# Synara Companion MVP

Remote-control the **Mac Synara workbench** from Amber over LAN.

## Phase 0 — Mac side (required)

Synara desktop binds **loopback only** (`127.0.0.1:<random-port>`). Phone cannot reach it directly.

### Option A — LAN bridge (while desktop is open)

```bash
# Keep Synara.app running, then:
python3 scripts/synara-lan-bridge.py
```

The script prints:

- LAN URL: `http://<mac-ip>:3773/`
- Token URL: `http://<mac-ip>:3773/?token=<SYNARA_AUTH_TOKEN>`
- WS path: `ws://<mac-ip>:3773/ws?token=...`

Verified on this machine (2026-07-14):

| Check | Result |
|---|---|
| Desktop listen | `127.0.0.1:52944` only |
| `/health` | `{"status":"ok",...}` |
| WS `/ws?token=...` | `101 Switching Protocols` |
| Bridge `0.0.0.0:3773` | HTTP + WS OK via `192.168.100.107` |

### Option B — Official remote CLI

See upstream [REMOTE.md](https://github.com/Emanuele-web04/synara/blob/main/REMOTE.md):

```bash
bun run --cwd apps/server start -- \
  --host 0.0.0.0 --port 3773 \
  --auth-token "$TOKEN" --no-browser
```

Use the same `SYNARA_HOME` as desktop if you need the same chat history.

## Phase 1 — Amber side

Entry points:

- Chat drawer QuickRow (icon row under primary nav) → rightmost **Synara**
  (replaces the previous Live 伴随 icon; Live entry is hidden for now)
- Settings → Experimental → **Synara**

Screens:

1. `SynaraCompanion` — host / port / token, health check, open workbench  
2. `SynaraWorkspace` — full-screen WebView of remote UI  

Defaults: port `3773`, HTTP to private ranges only (10/172.16-31/192.168/100.64-127 + localhost).

## Phone test checklist

1. Mac: Synara desktop open + `python3 scripts/synara-lan-bridge.py`  
2. Same Wi-Fi as phone  
3. Amber → drawer **Synara**  
4. Fill IP / port / token from bridge output  
5. **测试连接** → should say status=ok  
6. **进入工作台** → Synara UI loads and WS connects  

## Security notes

- Treat `SYNARA_AUTH_TOKEN` like a password; it rotates when desktop restarts.  
- Do not expose `0.0.0.0` without a token.  
- Bridge is for trusted LAN only.
