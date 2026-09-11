#!/usr/bin/env python3
"""Seed only the disposable emulator fixture; production runner does all calls."""
import argparse
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument("--adb", required=True)
args = parser.parse_args()
adb = [args.adb, "-s", "emulator-5554"]
package = "app.amber.agent.graphite"
fixture_id = "parity-system-capabilities"


def run(*command, **kwargs):
    return subprocess.run(adb + list(command), check=True, timeout=20, **kwargs)


assert run("shell", "getprop", "ro.kernel.qemu", capture_output=True, text=True).stdout.strip() == "1"
html = Path(__file__).with_name("index.html").read_text()
now = int(time.time() * 1000)
digest = hashlib.sha256(html.encode()).hexdigest()
permissions = json.dumps(["haptics", "device", "screen", "speech", "share", "openURL"])


def literal(value):
    if value is None:
        return "NULL"
    if isinstance(value, int):
        return str(value)
    return "'" + value.replace("'", "''") + "'"


row = {
    "id": fixture_id, "title": "MiniApp 系统能力验收", "description": "仅合成数据与系统交互验证",
    "htmlContent": html, "sourceConversationId": None, "sourceMessageId": None,
    "iconEmoji": "🧪", "category": "验收", "permissionsJson": permissions,
    "pinned": 1, "runCount": 0, "boardSummary": None, "version": 1,
    "htmlHash": digest, "createdAt": now, "updatedAt": now, "lastRunAt": None,
}
sql = "BEGIN;\nINSERT OR REPLACE INTO mini_app (" + ",".join(row) + ") VALUES (" + ",".join(map(literal, row.values())) + ");\n"
sql += "INSERT OR REPLACE INTO mini_app_version(appId,versionNumber,htmlContent,htmlHash,changeNote,createdAt) VALUES (" + ",".join(map(literal, [fixture_id, 1, html, digest, "Synthetic parity fixture", now])) + ");\nCOMMIT;\n"
run("shell", "am", "force-stop", package)
command = shlex.join(["run-as", package, "sqlite3", "databases/amber_agent"])
run("shell", command, input=sql, text=True)
run("shell", "am", "start", "-n", package + "/app.amber.agent.RouteActivity")
print("Seeded synthetic fixture:", fixture_id)
