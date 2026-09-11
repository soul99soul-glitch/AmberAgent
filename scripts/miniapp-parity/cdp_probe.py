#!/usr/bin/env python3
"""Phase 4 MiniApp parity device driver via Chrome DevTools Protocol.

Reads the fixture's #result block and clicks fixture buttons inside the real
Android WebView, so device verification does not depend on fragile screen
coordinates. Only talks to the controlled emulator's forwarded devtools port.
"""
import json
import sys
import time
import urllib.request
import websocket  # pip3 install websocket-client


def first_page(ws_port):
    with urllib.request.urlopen(f"http://127.0.0.1:{ws_port}/json") as response:
        pages = json.load(response)
    for page in pages:
        if page.get("type") == "page" and "MiniApp" in page.get("title", ""):
            return page
    raise SystemExit("MiniApp page not found among debuggable pages")


class Cdp:
    def __init__(self, ws_url):
        # Devtools rejects non-empty Origin headers; suppress the default one.
        self.ws = websocket.create_connection(ws_url, timeout=15, suppress_origin=True)
        self.next_id = 1

    def eval(self, expression, await_promise=False):
        call_id = self.next_id
        self.next_id += 1
        self.ws.send(json.dumps({
            "id": call_id,
            "method": "Runtime.evaluate",
            "params": {
                "expression": expression,
                "returnByValue": True,
                "awaitPromise": await_promise,
            },
        }))
        while True:
            message = json.loads(self.ws.recv())
            if message.get("id") == call_id:
                result = message.get("result", {}).get("result", {})
                if result.get("subtype") == "error":
                    raise RuntimeError(result.get("description"))
                return result.get("value")

    def close(self):
        self.ws.close()


def main():
    ws_port = int(sys.argv[1]) if len(sys.argv) > 1 else 9223
    action = sys.argv[2] if len(sys.argv) > 2 else "read"
    cdp = Cdp(first_page(ws_port)["webSocketDebuggerUrl"])
    try:
        if action == "read":
            print(cdp.eval("document.querySelector('#result') ? document.querySelector('#result').textContent : 'NO_RESULT_NODE'"))
        elif action == "click":
            button_id = sys.argv[3]
            print(cdp.eval(f"document.getElementById('{button_id}') ? (document.getElementById('{button_id}').click(), 'clicked') : 'NO_BUTTON'"))
        elif action == "eval":
            expression = sys.argv[3]
            value = cdp.eval(expression, await_promise=True)
            print(json.dumps(value, ensure_ascii=False))
    finally:
        cdp.close()


if __name__ == "__main__":
    main()
