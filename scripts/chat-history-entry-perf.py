#!/usr/bin/env python3
"""Phase-2 history-entry fixture doctor/prepare/start/collect entry point.

Device-side actions are intentionally explicit and only ever target the
perf-fixture package (app.amber.agent.perffixture). The primary agent owns
adb/install/device settings; this script never changes global device state and
never clears the whole device logcat.

Ordered measured sequence (cold):
  prepare (separate process) -> force-stop -> launcher -> atrace start ->
  tap EntryRequested -> settle/optional S7/interaction -> wait atrace ->
  pull -> collect (APK hash before/after, per-run metrics directory).

Warm: no prepare/pm clear, bring the launcher to front in the SAME process,
verify the pid is unchanged, then atrace/tap as above.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import secrets
import shlex
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from chat_perf_contract import PATHS, PARSER_VERSION, HOST_VERSION, path_id, environment_snapshot

PACKAGE = "app.amber.agent.perffixture"
PREP_ACTIVITY = f"{PACKAGE}/app.amber.agent.perffixture.PerfFixturePrepActivity"
LAUNCH_ACTIVITY = f"{PACKAGE}/app.amber.agent.perffixture.PerfFixtureLauncherActivity"
RECEIVER = f"{PACKAGE}/app.amber.agent.perffixture.PerfFixtureInteractionReceiver"
ACTION_HOME_STATE = "app.amber.agent.perffixture.PERF_HOME_STATE"
ACTION_HOME_ARM = "app.amber.agent.perffixture.PERF_HOME_ARM"
ACTION_SET_RUN = "app.amber.agent.perffixture.PERF_SET_RUN"
ACTION_BLUR = "app.amber.agent.perffixture.PERF_BLUR"
ACTION_INTERACTION = "app.amber.agent.perffixture.PERF_INTERACTION"
ACTION_SEND = "app.amber.agent.perffixture.PERF_S7_SEND"
ACTION_STOP = "app.amber.agent.perffixture.PERF_STOP"
ACTION_SNAPSHOT = "app.amber.agent.perffixture.PERF_SNAPSHOT"
SCENARIOS = [f"S{i}" for i in range(9)]
TRACE_WINDOW_MS = 1500.0
ADB_COMMAND: list[str] = ["adb"]


def configure_adb(value: str) -> None:
    global ADB_COMMAND
    ADB_COMMAND = shlex.split(value)


def adb(serial: str | None, *args: str, check: bool = True) -> subprocess.CompletedProcess:
    command = list(ADB_COMMAND)
    if serial:
        command += ["-s", serial]
    if len(args) > 2 and args[0] == "shell":
        command += ["shell", shlex.join(args[1:])]
    else:
        command += list(args)
    return subprocess.run(command, text=True, capture_output=True, check=check)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sanitize_run_id(run_id: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]", "_", run_id)[:80]


def require_fixture_package(package: str) -> None:
    if package != PACKAGE or not package.endswith(".perffixture"):
        raise SystemExit(f"refusing non-fixture package: {package}")


def apk_info(path: Path, aapt2: str) -> dict:
    badging = subprocess.run(
        [aapt2, "dump", "badging", str(path)], text=True, capture_output=True, check=False
    )
    if badging.returncode != 0:
        raise SystemExit(f"aapt2 badging failed for {path}: {badging.stderr}")
    package_match = re.search(r"^package: name='([^']+)'", badging.stdout, re.M)
    if not package_match:
        raise SystemExit(f"cannot read package from {path}")
    tree = subprocess.run(
        [aapt2, "dump", "xmltree", str(path), "--file", "AndroidManifest.xml"],
        text=True,
        capture_output=True,
        check=False,
    )
    if tree.returncode != 0:
        raise SystemExit(f"aapt2 manifest failed for {path}: {tree.stderr}")
    names = re.findall(r'android:name\([^)]*\)="([^"]+)"', tree.stdout)
    instrumentation = re.search(
        r"E: instrumentation[\s\S]{0,500}?android:name\([^)]*\)=\"([^\"]+)\"",
        tree.stdout,
    )
    trace_match = re.search(
        r'app\.amber\.perf\.traceEnabled[\s\S]{0,400}?android:value\([^)]*\)=("?)(true|false)\1',
        tree.stdout,
    )
    abi = re.findall(r"native-code: '([^']+)'", badging.stdout)
    return {
        "package": package_match.group(1),
        "debuggable": "android:debuggable" in tree.stdout,
        "profileable": "E: profileable" in tree.stdout
        and re.search(r"E: profileable[\s\S]{0,200}android:enabled\([^)]*\)=true", tree.stdout) is not None,
        "application": next((n for n in names if "Application" in n), None),
        "instrumentation": instrumentation.group(1) if instrumentation else None,
        "manifest": tree.stdout,
        "traceEnabled": (trace_match.group(2) == "true") if trace_match else None,
        "abi": abi,
        "baselineProfile": any(
            name.startswith("assets/dexopt/baseline.prof") for name in zipfile.ZipFile(path).namelist()
        ),
        "badging": badging.stdout,
    }


def apk_signer_sha256(path: Path, apksigner: str) -> str:
    result = subprocess.run(
        [apksigner, "verify", "--print-certs", str(path)],
        text=True, capture_output=True, check=False,
    )
    if result.returncode != 0:
        raise SystemExit(f"apksigner verify failed for {path}: {result.stderr or result.stdout}")
    match = re.search(
        r"Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)", result.stdout
    )
    if not match:
        raise SystemExit(f"cannot read signer certificate from {path}")
    return match.group(1).lower()


def installed_apk_hash(serial: str, package: str, temp_dir: str) -> str:
    package_path = adb(serial, "shell", "pm", "path", package, check=False)
    installed_path = None
    for line in (package_path.stdout or "").splitlines():
        if line.startswith("package:"):
            installed_path = line.removeprefix("package:").strip()
            break
    if package_path.returncode != 0 or not installed_path:
        raise SystemExit(f"fixture package not installed: {package}")
    local = Path(temp_dir) / "installed.apk"
    pulled = adb(serial, "pull", installed_path, str(local), check=False)
    if pulled.returncode != 0 or not local.is_file():
        raise SystemExit(f"cannot pull installed APK: {pulled.stderr or pulled.stdout}")
    return sha256(local)


def device_identity(serial: str) -> dict:
    def prop(name: str) -> str:
        return adb(serial, "shell", "getprop", name).stdout.strip()

    return {
        "serial": serial,
        "model": prop("ro.product.model"),
        "sdk": prop("ro.build.version.sdk"),
        "abi": prop("ro.product.cpu.abi"),
        "fingerprint": prop("ro.build.fingerprint"),
    }


def doctor(args: argparse.Namespace) -> int:
    report: dict[str, object] = {"package": args.package, "serial": args.serial}
    require_fixture_package(args.package)
    if not args.serial:
        raise SystemExit("--serial is required")
    if args.dry_run:
        print(json.dumps({"dryRun": True, "command": "doctor", "package": args.package}, indent=2))
        return 0
    apk = Path(args.apk)
    test_apk = Path(args.test_apk)
    for path in (apk, test_apk):
        if not path.is_file():
            raise SystemExit(f"APK missing: {path}")
    aapt2 = args.aapt2
    if not Path(aapt2).is_file():
        raise SystemExit(f"aapt2 missing: {aapt2}")
    if not Path(args.apksigner).is_file():
        raise SystemExit(f"apksigner missing: {args.apksigner}")
    info = apk_info(apk, aapt2)
    test_info = apk_info(test_apk, aapt2)
    if info["package"] != args.package:
        raise SystemExit(f"APK package {info['package']} != {args.package}")
    if info["debuggable"] or not info["profileable"] or not info["baselineProfile"]:
        raise SystemExit("fixture APK must be non-debuggable, profileable and carry baseline.prof")
    if info["traceEnabled"] is None:
        raise SystemExit("fixture APK is missing app.amber.perf.traceEnabled meta-data")
    if test_info["package"] != f"{args.package}.test":
        raise SystemExit(f"test APK package {test_info['package']} != {args.package}.test")
    instrumentation = re.search(
        r'targetPackage\([^)]*\)="([^"]+)"', test_info["manifest"]
    )
    if not instrumentation or instrumentation.group(1) != args.package:
        raise SystemExit("test APK does not target the fixture package")
    if not test_info["instrumentation"]:
        raise SystemExit("test APK has no instrumentation runner")
    if args.expect_instrumentation and test_info["instrumentation"] != args.expect_instrumentation:
        raise SystemExit(
            f"test instrumentation {test_info['instrumentation']} != "
            f"{args.expect_instrumentation}"
        )

    signer = apk_signer_sha256(apk, args.apksigner)
    test_signer = apk_signer_sha256(test_apk, args.apksigner)
    report.update(
        {
            "apk": str(apk),
            "apkSha256": sha256(apk),
            "signerSha256": signer,
            "testSignerSha256": test_signer,
            "fixtureApplication": info["application"],
            "debuggable": info["debuggable"],
            "profileable": info["profileable"],
            "chatPerfTrace": info["traceEnabled"],
            "abi": info["abi"],
            "baselineProfile": info["baselineProfile"],
            "testApk": str(test_apk),
            "testApkSha256": sha256(test_apk),
            "testPackage": test_info["package"],
            "testInstrumentation": test_info["instrumentation"],
        }
    )
    try:
        if adb(args.serial, "get-state").stdout.strip() != "device":
            raise SystemExit("device not ready")
        with tempfile.TemporaryDirectory() as temp:
            installed_hash = installed_apk_hash(args.serial, args.package, temp)
            installed_signer = apk_signer_sha256(Path(temp) / "installed.apk", args.apksigner)
            installed_test_hash = installed_apk_hash(
                args.serial, f"{args.package}.test", temp
            )
            installed_test_signer = apk_signer_sha256(
                Path(temp) / "installed.apk", args.apksigner
            )
        if installed_test_hash != report["testApkSha256"]:
            raise SystemExit(
                f"installed test APK hash {installed_test_hash} != "
                f"supplied {report['testApkSha256']}"
            )
        if installed_test_signer != test_signer:
            raise SystemExit("installed test APK signer does not match supplied test APK")
        if installed_signer != signer:
            raise SystemExit(
                f"installed signer {installed_signer} != supplied signer {signer}"
            )
        if installed_hash != report["apkSha256"]:
            raise SystemExit(
                f"installed fixture APK hash {installed_hash} != supplied {report['apkSha256']}"
            )
        report.update(
            {
                "device": device_identity(args.serial),
                "installedApkSha256": installed_hash,
                "installedSignerSha256": installed_signer,
                "installedTestApkSha256": installed_test_hash,
                "installedTestSignerSha256": installed_test_signer,
            }
        )
    except (FileNotFoundError, subprocess.CalledProcessError) as error:
        raise SystemExit(f"device check failed: {error}")
    print(json.dumps(report, indent=2))
    return 0


def logcat_entries(serial: str) -> str:
    return adb(serial, "logcat", "-d", "-s", "AmberPerfFixture:I", "AmberPerf:I", check=False).stdout or ""


def wait_for_log(serial: str, pattern: str, timeout: float, *, reject_pattern: str | None = None) -> str:
    deadline = time.monotonic() + timeout
    regex = re.compile(pattern)
    rejected = re.compile(reject_pattern) if reject_pattern else None
    while time.monotonic() < deadline:
        entries = logcat_entries(serial)
        if rejected:
            match = rejected.search(entries)
            if match:
                raise SystemExit(f"fixture rejected operation: {match.group(0)}")
        if regex.search(entries):
            return entries
        time.sleep(0.25)
    raise SystemExit(f"timed out waiting for log pattern: {pattern}")


def _prepare_impl(args: argparse.Namespace, capture: dict) -> int:
    require_fixture_package(args.package)
    if args.scenario not in SCENARIOS:
        raise SystemExit(f"unsupported scenario: {args.scenario}")
    if not args.run_id:
        raise SystemExit("--run-id is required")
    if args.dry_run:
        print(json.dumps({
            "dryRun": True,
            "command": "prepare",
            "steps": ["pm clear", "start prep", "wait prepared runId", "force-stop"],
            "scenario": args.scenario,
            "runId": args.run_id,
        }, indent=2))
        return 0
    cleared = adb(args.serial, "shell", "pm", "clear", args.package, check=False)
    if cleared.returncode != 0 or "Success" not in (cleared.stdout or ""):
        raise SystemExit(f"pm clear failed: {cleared.stderr or cleared.stdout}")
    capture["stage"] = "seed"
    adb(
        args.serial, "shell", "am", "start", "-W", "-n", PREP_ACTIVITY,
        "--es", "scenario", args.scenario,
        "--es", "runId", args.run_id,
    )
    entries = wait_for_log(
        args.serial,
        rf"prepared runId={re.escape(args.run_id)} scenario={args.scenario} "
        rf"status=(?:ok persisted=true|failed persisted=false)",
        args.timeout,
    )
    if f"prepared runId={args.run_id} scenario={args.scenario} status=failed" in entries:
        raise SystemExit(f"fixture preparation failed for runId={args.run_id}; see prepared JSON")
    seed_pid = adb(args.serial, "shell", "pidof", args.package, check=False).stdout.strip()
    capture["seedPid"] = seed_pid
    adb(args.serial, "shell", "am", "force-stop", args.package)
    capture["stage"] = "restart-readback"
    adb(args.serial, "shell", "am", "start", "-W", "-n", PREP_ACTIVITY,
        "--es", "scenario", args.scenario, "--es", "runId", args.run_id,
        "--ez", "verifyOnly", "true")
    entries = wait_for_log(args.serial,
        rf"verified runId={re.escape(args.run_id)} scenario={args.scenario} status=(?:ok|failed) restarted=true",
        args.timeout)
    if f"verified runId={args.run_id} scenario={args.scenario} status=ok restarted=true" not in entries:
        raise SystemExit("fixture restart readback failed; see verified JSON")
    verify_pid = adb(args.serial, "shell", "pidof", args.package, check=False).stdout.strip()
    if not seed_pid or not verify_pid or seed_pid == verify_pid:
        raise SystemExit("fixture readback was not in a new process")
    capture["verifyPid"] = verify_pid
    adb(args.serial, "shell", "am", "force-stop", args.package)
    capture["status"] = "ok"
    return 0


def ui_xml(serial: str) -> str:
    adb(serial, "shell", "rm", "-f", "/sdcard/perf-ui.xml", check=False)
    dumped = adb(serial, "shell", "uiautomator", "dump", "/sdcard/perf-ui.xml", check=False)
    if dumped.returncode != 0:
        raise SystemExit(f"UIAutomator dump failed: {dumped.stderr or dumped.stdout}")
    return adb(serial, "shell", "cat", "/sdcard/perf-ui.xml", check=False).stdout or ""


def parse_ui_nodes(xml: str) -> list[ET.Element]:
    try:
        root = ET.fromstring(xml)
    except ET.ParseError as error:
        raise SystemExit(f"invalid UIAutomator XML: {error}")
    return list(root.iter("node"))


def _bounds_center(node: ET.Element) -> tuple[int, int]:
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    if not match:
        raise SystemExit("UIAutomator node has invalid bounds")
    x1, y1, x2, y2 = map(int, match.groups())
    if x2 <= x1 or y2 <= y1:
        raise SystemExit("UIAutomator node has empty bounds")
    return (x1 + x2) // 2, (y1 + y2) // 2


def parse_open_bounds(xml: str) -> tuple[int, int]:
    """Stable locator: content-desc, not the all-caps visual text."""
    nodes = [
        node for node in parse_ui_nodes(xml)
        if (node.get("content-desc") or "").strip() == "perf_open_history"
    ]
    if len(nodes) != 1:
        raise SystemExit(
            f"fixture Open history locator matched {len(nodes)} nodes; expected one"
        )
    node = nodes[0]
    if node.get("enabled") != "true":
        raise SystemExit("fixture Open history button is disabled (seed not persisted?)")
    if node.get("visible-to-user") == "false":
        raise SystemExit("fixture Open history button is not visible")
    return _bounds_center(node)


def fixture_open_bounds(serial: str) -> tuple[int, int]:
    return parse_open_bounds(ui_xml(serial))


def return_to_launcher(args: argparse.Namespace, expected_pid: str, capture: dict) -> tuple[int, int]:
    """Bounded visible-state transition: IME -> route/home -> fixture launcher."""
    deadline = time.monotonic() + getattr(args, "launcher_timeout", 10.0)
    transitions = capture.setdefault("warmTransitions", [])
    requested_launcher = False
    dismissed_ime = False
    while time.monotonic() < deadline:
        pid = adb(args.serial, "shell", "pidof", args.package, check=False).stdout.strip()
        if pid != expected_pid:
            raise SystemExit(f"warm return changed pid: {expected_pid} -> {pid}")
        xml = ui_xml(args.serial)
        nodes = parse_ui_nodes(xml)
        if any(node.get("content-desc") == "perf_open_history" for node in nodes):
            point = parse_open_bounds(xml)
            transitions.append("launcher-visible")
            return point
        ime = adb(args.serial, "shell", "dumpsys", "input_method", check=False).stdout or ""
        if not dismissed_ime and re.search(r"(?:mInputShown|isInputViewShown)\s*=\s*true", ime):
            transitions.append("dismiss-ime")
            adb(args.serial, "shell", "input", "keyevent", "KEYCODE_BACK")
            dismissed_ime = True
        elif not requested_launcher:
            # Explicitly reorder the existing launcher above either chat or Home.
            # No destructive task flags and no reliance on the route BACK stack.
            transitions.append("reorder-launcher")
            adb(args.serial, "shell", "am", "start", "-W", "--activity-reorder-to-front",
                "--activity-single-top", "-n", LAUNCH_ACTIVITY,
                "--es", "scenario", args.scenario, "--es", "runId", args.run_id)
            requested_launcher = True
        else:
            time.sleep(0.2)
    raise SystemExit("warm launcher did not become visibly enabled in the same process")


def ui_contains(serial: str, text: str) -> bool:
    return any(
        text in (node.get("text") or "") or text in (node.get("content-desc") or "")
        for node in parse_ui_nodes(ui_xml(serial))
    )


def parse_device_environment(policy: str, power: str) -> dict:
    screen_values = re.findall(
        r"mScreenOn(?:Early|Fully)?\s*=\s*(true|false)", policy
    )
    if screen_values:
        screen_on = all(value == "true" for value in screen_values)
    else:
        screen_on = "Display Power: state=ON" in power
    wakefulness = re.search(r"mWakefulness\s*=\s*(\w+)", power)
    awake = (
        wakefulness.group(1) == "Awake"
        if wakefulness
        else screen_on
    )
    keyguard = bool(
        re.search(
            r"(?:mShowingLockscreen|mKeyguardShowing|KeyguardShowing|mDreamingLockscreen)"
            r"\s*=\s*true",
            policy,
        )
    )
    keyguard = keyguard or bool(re.search(r"(?m)^\s*showing\s*=\s*true\s*$", policy))
    return {"screenOn": screen_on, "awake": awake, "keyguard": keyguard}


def preflight_device(serial: str) -> dict:
    policy = adb(serial, "shell", "dumpsys", "window", "policy", check=False).stdout or ""
    power = adb(serial, "shell", "dumpsys", "power", check=False).stdout or ""
    env = parse_device_environment(policy, power)
    if not env["awake"] or not env["screenOn"]:
        raise SystemExit(
            "device environment invalid: screen is off/asleep; wake and unlock "
            "manually before measurement"
        )
    if env["keyguard"]:
        raise SystemExit(
            "device environment invalid: keyguard/lock screen is showing; "
            "unlock manually before measurement"
        )
    return env


def interaction_mode(args: argparse.Namespace) -> str:
    if args.interaction_mode != "auto":
        return args.interaction_mode
    # S0 has no scroll range; use the same request->focus confirmation instead
    # of pretending a swipe response exists.
    return "input" if args.scenario == "S0" else "swipe"


def parse_edit_bounds(xml: str) -> tuple[int, int] | None:
    nodes = [
        node for node in parse_ui_nodes(xml)
        if (node.get("class") or "") == "android.widget.EditText"
        and node.get("enabled") == "true"
    ]
    if not nodes:
        return None
    return _bounds_center(nodes[0])


def ui_edit_bounds(serial: str) -> tuple[int, int] | None:
    return parse_edit_bounds(ui_xml(serial))


def snapshot_line(entries: str, nonce: str, conversation: str) -> str | None:
    prefix = f"snapshot nonce={nonce} "
    return next(
        (
            line for line in entries.splitlines()
            if prefix in line and f"conversation={conversation}" in line
        ),
        None,
    )


def s7_ui_state(xml: str, reply_fragment: str = "S7 scripted") -> dict:
    nodes = parse_ui_nodes(xml)
    edit_nodes = [
        node for node in nodes
        if (node.get("class") or "") == "android.widget.EditText"
    ]
    return {
        "textVisible": any(
            reply_fragment in (node.get("text") or "")
            or reply_fragment in (node.get("content-desc") or "")
            for node in nodes
        ),
        "editPresent": bool(edit_nodes),
        "editEnabled": any(node.get("enabled") == "true" for node in edit_nodes),
    }


def screen_size(serial: str) -> tuple[int, int]:
    output = adb(serial, "shell", "wm", "size").stdout or ""
    match = re.search(r"(\d+)x(\d+)", output)
    if not match:
        raise SystemExit("cannot read screen size")
    return int(match.group(1)), int(match.group(2))


def broadcast(serial: str, action: str, *extras: str) -> None:
    adb(serial, "shell", "am", "broadcast", "-a", action, "-n", RECEIVER, *extras)


def fixture_conversation(scenario: str) -> str:
    return "10000000-0000-4000-8000-0000000000" + scenario.removeprefix("S").zfill(2)


def home_state(args):
    nonce = secrets.token_hex(8)
    broadcast(args.serial, ACTION_HOME_STATE, "--es", "nonce", nonce,
              "--es", "conversationId", fixture_conversation(args.scenario))
    entries = wait_for_log(args.serial, rf"home-state nonce={nonce} stateJson=", args.handshake_timeout)
    line = next(line for line in entries.splitlines() if f"home-state nonce={nonce} stateJson=" in line)
    return json.loads(line.split("stateJson=", 1)[1])


def home_row_bounds(xml: str, scenario: str) -> tuple[int, int]:
    tag = "perf_home_row_" + fixture_conversation(scenario)
    matches = [node for node in parse_ui_nodes(xml) if node.get("content-desc") == tag]
    if len(matches) != 1 or matches[0].get("enabled") != "true" or matches[0].get("visible-to-user") == "false":
        raise SystemExit("Home target row must be uniquely visible and enabled")
    return _bounds_center(matches[0])


def prepare_home_entry(args, capture, pid_before):
    start = time.monotonic()
    capture["homeLaunchHostMonotonic"] = start
    if args.cold:
        adb(args.serial, "shell", "am", "force-stop", args.package)
        start = time.monotonic()
        capture["homeLaunchHostMonotonic"] = start
        capture["startupTimingSource"] = "host launch command to observed Home; upper bound includes observation/environment overhead, not TTID"
        adb(args.serial, "shell", "am", "start", "-W", "-n", f"{args.package}/app.amber.agent.RouteActivity",
            "--es", "perfPathId", PATHS[1])
    else:
        if not pid_before:
            raise SystemExit("warm Home-first requires a live process")
        # Genuine production Back stack, never reorder/start another Activity.
        ime = adb(args.serial, "shell", "dumpsys", "input_method", check=False).stdout or ""
        if re.search(r"(?:mInputShown|isInputViewShown)\s*=\s*true", ime):
            adb(args.serial, "shell", "input", "keyevent", "KEYCODE_BACK")
        adb(args.serial, "shell", "input", "keyevent", "KEYCODE_BACK")
    deadline = time.monotonic() + args.launcher_timeout
    while time.monotonic() < deadline:
        state = home_state(args)
        if state.get("home") and state.get("rowVisible") and state.get("focused"):
            if state.get("pathId") != PATHS[1]:
                raise SystemExit("Home owner path mismatch")
            pid = adb(args.serial, "shell", "pidof", args.package, check=False).stdout.strip()
            if pid != str(state.get("pid")) or (args.warm and pid != pid_before) or (args.cold and pid == pid_before):
                raise SystemExit("Home-first process identity mismatch")
            if args.cold and state.get("chatVisited"):
                raise SystemExit("cold process already visited Chat")
            if args.warm and (state.get("lastEntryActivity") != state.get("activityId") or
                              state.get("lastEntryConversation") != fixture_conversation(args.scenario)):
                raise SystemExit("warm Activity/conversation owner mismatch")
            point = home_row_bounds(ui_xml(args.serial), args.scenario)
            capture["homeBefore"] = state
            capture["appStartToHomeVisibleHostMs"] = (time.monotonic() - start) * 1000 if args.cold else None
            return *point, pid
        time.sleep(0.2)
    raise SystemExit("production Home/target row did not become visible")


def settle_home(args, capture):
    before = home_state(args)
    point = home_row_bounds(ui_xml(args.serial), args.scenario)
    # No polling, environment query, or tool command during this quiet interval.
    quiet_start = time.monotonic()
    time.sleep(0.500)
    quiet_observed_ms = (time.monotonic() - quiet_start) * 1000
    after = home_state(args)
    after_point = home_row_bounds(ui_xml(args.serial), args.scenario)
    if not all(after.get(k) for k in ("home", "rowVisible", "focused")) or before["activityId"] != after["activityId"] or point != after_point:
        raise SystemExit("Home-ready quiet interval was interrupted")
    capture["homeReady"] = {"quietMs": 500, "quietObservedMs": quiet_observed_ms, "source": "actual-row-layout-then-quiet", "activityId": after["activityId"]}
    if args.cold:
        capture["appStartToHomeReadyHostMs"] = (time.monotonic() - capture["homeLaunchHostMonotonic"]) * 1000
    broadcast(args.serial, ACTION_HOME_ARM, "--es", "runId", args.run_id, "--es", "scenario", args.scenario,
              "--es", "conversationId", fixture_conversation(args.scenario), "--es", "phase", "warm" if args.warm else "cold")
    wait_for_log(args.serial, rf"home-armed runId={re.escape(args.run_id)} accepted=true", args.handshake_timeout,
                 reject_pattern=rf"home-armed runId={re.escape(args.run_id)} accepted=false[^\n]*")
    return after_point


def build_start_plan(args: argparse.Namespace) -> list[dict]:
    """Pure, ordered plan used by start() and exercised by script tests."""
    steps: list[dict] = []
    if getattr(args, "path_id", PATHS[0]) == PATHS[1]:
        return [{"kind": k, "pathId": PATHS[1]} for k in (
            ("force-stop", "production-home-no-chat", "environment-pre", "row-layout-quiet-500ms", "arm-no-t0", "atrace-start", "real-home-row-tap-T0", "post-ack-full-window", "collect", "environment-post")
            if args.cold else ("same-pid-activity-back-home", "environment-pre", "row-layout-quiet-500ms", "arm-no-t0", "atrace-start", "real-home-row-tap-T0", "post-ack-full-window", "collect", "environment-post"))]
    if args.cold:
        steps.append({"kind": "force-stop", "cmd": ["shell", "am", "force-stop", args.package]})
        steps.append({
            "kind": "launch-launcher",
            "cmd": ["shell", "am", "start", "-W", "-n", LAUNCH_ACTIVITY,
                    "--es", "scenario", args.scenario, "--es", "runId", args.run_id],
        })
    else:
        steps.append({"kind": "pid-before", "cmd": ["shell", "pidof", args.package]})
        steps.append({"kind": "warm-return-launcher", "sameProcess": True})
        steps.append({"kind": "pid-after", "cmd": ["shell", "pidof", args.package]})
    steps.append({"kind": "coords", "locator": "content-desc=perf_open_history"})
    steps.append({"kind": "set-run"})
    if args.trace_seconds > 0:
        steps.append({"kind": "atrace-start", "windowMs": TRACE_WINDOW_MS})
    steps.append({"kind": "tap-open"})
    steps.append({"kind": "settle", "seconds": args.settle, "windowMs": TRACE_WINDOW_MS})
    if args.scenario == "S7":
        steps.append({"kind": "s7-send-stream-settled"})
    steps.append({"kind": "pid-measured"})
    if args.trace_seconds > 0:
        steps.append({"kind": "atrace-wait"})
        steps.append({"kind": "atrace-pull"})
    if args.external_readable_text:
        steps.append({"kind": "external-readable-poll", "text": args.external_readable_text})
    if args.interact:
        if interaction_mode(args) == "input":
            steps.append({"kind": "blur"})
            steps.append({"kind": "interaction-request-input"})
            steps.append({"kind": "tap-input"})
        else:
            steps.append({"kind": "interaction-request"})
            steps.append({"kind": "swipe"})
    if args.trace_mode == "on":
        steps.append({"kind": "metrics-export-wait"})
    return steps


def save_evidence(args: argparse.Namespace, capture: dict, target: Path, *, failed: bool) -> None:
    """Best-effort diagnostics never replace the original operation's exit code."""
    target.parent.mkdir(parents=True, exist_ok=True)
    errors = capture.setdefault("evidenceErrors", [])
    try:
        logs = logcat_entries(args.serial)
        target.with_suffix(".logcat.txt").write_text(logs)
        # Keep raw logs as raw; only this run's lines are attributed to this run.
        capture["runLogLines"] = [line for line in logs.splitlines()
                                  if f"runId={getattr(args, 'run_id', '')} " in line]
    except BaseException as error:
        errors.append(f"logcat: {error}")
    try:
        display = adb(args.serial, "shell", "dumpsys", "display", check=False).stdout or ""
        displays = target.with_suffix(".displays.txt")
        displays.write_text(display)
        capture["displayEvidence"] = {"path": str(displays), "sha256": sha256(displays),
                                      "deadlineStatus": "unknown"}
    except BaseException as error:
        errors.append(f"display: {error}")
    if failed:
        try:
            target.with_suffix(".ui.xml").write_text(ui_xml(args.serial))
        except BaseException as error:
            errors.append(f"UI: {error}")
    if getattr(args, "scenario", None):
        for name in ("prepared", "verified"):
            try:
                remote = f"/sdcard/Android/data/{args.package}/files/chat-perf/{name}-{args.scenario}.json"
                local = target.with_suffix(f".{name}.json")
                result = adb(args.serial, "pull", remote, str(local), check=False)
                if result.returncode == 0 and local.is_file():
                    payload = json.loads(local.read_text())
                    capture[name + "Evidence"] = {"path": str(local),
                        "sameRun": payload.get("runId") == getattr(args, "run_id", None)}
            except BaseException as error:
                errors.append(f"{name}: {error}")
    if failed and capture.get("traceRemote") and not capture.get("tracePath"):
        try:
            # An early failure can leave a partial capture. Preserve it, label
            # completeness unknown, and keep the run invalid.
            local = Path(args.trace_output) if args.trace_output else target.with_suffix(".atrace")
            local.parent.mkdir(parents=True, exist_ok=True)
            result = adb(args.serial, "pull", str(capture["traceRemote"]), str(local), check=False)
            if result.returncode == 0 and local.is_file():
                capture.update(tracePath=str(local), traceSha256=sha256(local),
                               traceBytes=local.stat().st_size, traceComplete=False)
            else:
                errors.append(f"trace unavailable: {result.stderr or result.stdout or result.returncode}")
        except BaseException as error:
            errors.append(f"trace: {error}")
    target.write_text(json.dumps(capture, indent=2))


def evidence_target(args: argparse.Namespace, operation: str) -> Path:
    if getattr(args, "capture_log", None):
        return Path(args.capture_log)
    if getattr(args, "trace_output", None):
        return Path(args.trace_output).with_suffix(".capture.json")
    root = Path(os.environ.get("CHAT_PERF_EVIDENCE_ROOT", "build/chat-history-entry-perf")) / "runs"
    root.mkdir(parents=True, exist_ok=True)
    return Path(tempfile.mkdtemp(prefix=f"{operation}-", dir=root)) / "capture.json"


def run_with_evidence(args: argparse.Namespace, operation: str, implementation) -> int:
    require_fixture_package(args.package)
    capture = {"pathId": getattr(args, "path_id", PATHS[0]), "hostVersion": HOST_VERSION, "parserVersion": PARSER_VERSION, "schema": 1, "runId": args.run_id, "scenario": args.scenario,
               "phase": "warm" if getattr(args, "warm", False) else "cold",
               "package": args.package, "serial": args.serial,
               "traceMode": getattr(args, "trace_mode", None), "stage": "validation"}
    if args.dry_run:
        return implementation(args, capture)
    target = evidence_target(args, operation)
    if target.exists():
        raise SystemExit(f"refusing to overwrite evidence: {target}")
    try:
        result = implementation(args, capture)
    except BaseException as error:
        capture.update(status="invalid", errorClass=type(error).__name__, reason=str(error),
                       exitCode=error.code if isinstance(error, SystemExit) else 1)
        try:
            save_evidence(args, capture, target, failed=True)
        except BaseException as diagnostic_error:
            print(f"could not save failure evidence: {diagnostic_error}", file=sys.stderr)
        print(f"invalid {operation} evidence: {target}", file=sys.stderr)
        raise
    save_evidence(args, capture, target, failed=False)
    print(json.dumps(capture, indent=2))
    return result


def start(args: argparse.Namespace) -> int:
    return run_with_evidence(args, "start", _start_impl)


def prepare(args: argparse.Namespace) -> int:
    return run_with_evidence(args, "prepare", _prepare_impl)


def _start_impl(args: argparse.Namespace, capture: dict) -> int:
    require_fixture_package(args.package)
    if args.scenario not in SCENARIOS:
        raise SystemExit(f"unsupported scenario: {args.scenario}")
    if not args.run_id:
        raise SystemExit("--run-id is required")
    if args.cold == args.warm:
        raise SystemExit("exactly one of --cold/--warm is required")
    if args.trace_mode == "off" and args.scenario == "S7":
        raise SystemExit("trace-off S7 is not validated; off comparison is limited to non-S7 scenarios")
    if args.apk:
        apk = Path(args.apk)
        if not apk.is_file():
            raise SystemExit(f"APK missing: {apk}")
        if not Path(args.aapt2).is_file():
            raise SystemExit(f"aapt2 missing: {args.aapt2}")
        capture["apkSha256"] = sha256(apk)
        trace_meta = apk_info(apk, args.aapt2)["traceEnabled"]
        expected_trace = args.trace_mode == "on"
        if trace_meta != expected_trace:
            raise SystemExit(
                f"APK trace meta-data {trace_meta} != requested trace mode {args.trace_mode}"
            )
    if getattr(args, "path_id", PATHS[0]) == PATHS[1] and args.scenario not in ("S1", "S2", "S3"):
        raise SystemExit("Home-first pilot is limited to S1/S2/S3")
    plan = build_start_plan(args)
    if args.dry_run:
        print(json.dumps({"dryRun": True, "plan": plan}, indent=2))
        return 0
    capture["stage"] = "preflight"
    capture["environment"] = preflight_device(args.serial)
    run_id = args.run_id
    safe_run = sanitize_run_id(run_id)
    trace_remote = f"/data/local/tmp/perf-{safe_run}.atrace"
    trace_local = Path(args.trace_output) if args.trace_output else None
    capture["stage"] = "launcher"
    started_host_ms = time.time() * 1000.0
    pid_before = adb(args.serial, "shell", "pidof", args.package, check=False).stdout.strip()
    home_path = getattr(args, "path_id", PATHS[0]) == PATHS[1]
    if home_path:
        x, y, pid_launcher = prepare_home_entry(args, capture, pid_before)
    else:
        if args.cold:
            adb(args.serial, "shell", "am", "force-stop", args.package)
            adb(
                args.serial, "shell", "am", "start", "-W", "-n", LAUNCH_ACTIVITY,
                "--es", "scenario", args.scenario, "--es", "runId", run_id,
            )
            pid_launcher = adb(args.serial, "shell", "pidof", args.package, check=False).stdout.strip()
            if not pid_launcher:
                raise SystemExit("cold launcher process did not start")
            if pid_before and pid_before == pid_launcher:
                raise SystemExit(f"cold start reused pid {pid_launcher}")
        else:
            if not pid_before:
                raise SystemExit("warm start requires the fixture process to already exist")
            x, y = return_to_launcher(args, pid_before, capture)
            pid_launcher = pid_before
        capture.update({"pidBefore": pid_before or None, "pidLauncher": pid_launcher})
        if args.cold:
            x, y = fixture_open_bounds(args.serial)
        # The launcher can be an old instance resumed by BACK. Explicitly hand it
        # the current run/scenario and wait for its acknowledgement before tapping.
        broadcast(args.serial, ACTION_SET_RUN, "--es", "runId", run_id,
                  "--es", "scenario", args.scenario)
        wait_for_log(
            args.serial,
            rf"launcher-run-id runId={re.escape(run_id)} scenario={re.escape(args.scenario)}",
            args.handshake_timeout,
        )
        capture["openTap"] = [x, y, time.time() * 1000.0 - started_host_ms]

    capture["pidBefore"] = pid_before or None
    capture["pidLauncher"] = pid_launcher
    capture["stage"] = "environment-pre"
    pre_state = home_state(args) if home_path else None
    capture["environmentPre"] = environment_snapshot(adb, args.serial, args.package, app_state=pre_state)
    if capture["environmentPre"]["gate"]["status"] == "pause":
        raise SystemExit("environment paused before measurement: " + "; ".join(capture["environmentPre"]["gate"]["reasons"]))
    # Environmental queries finish BEFORE the frozen Home-ready quiet interval.
    if home_path:
        x, y = settle_home(args, capture)
    capture["openTap"] = [x, y, time.time() * 1000 - started_host_ms]

    capture["stage"] = "trace-start"
    capture["traceRemote"] = trace_remote
    if args.trace_seconds > 0:
        adb(args.serial, "shell", "rm", "-f", trace_remote, check=False)
        atrace_pid = adb(
            args.serial, "shell",
            f"nohup atrace -t {int(args.trace_seconds)} -b 65536 gfx view sched freq "
            f"-a {args.package} -o {trace_remote} >/dev/null 2>&1 & echo $!",
        ).stdout.strip()
        if not atrace_pid.isdigit():
            raise SystemExit("could not start atrace")
        # atrace must be running before the EntryRequested tap.
        time.sleep(0.35)
        capture["atracePid"] = atrace_pid

    capture["stage"] = "entry"
    tap_host_ms = time.time() * 1000.0
    adb(args.serial, "shell", "input", "tap", str(x), str(y))
    entry_prefix = "home-entry" if home_path else "launcher-entry"
    entry_ack = wait_for_log(
        args.serial,
        rf"{entry_prefix} runId={re.escape(run_id)} .*current=(?:{re.escape(run_id)}|null)",
        args.handshake_timeout,
    )
    # Receipt is causally AFTER the real device click/T0. Host input invocation
    # is before T0 and cannot bound the end of a device measurement window.
    entry_ack_received = time.monotonic()
    ack_line = next(
        (
            line for line in entry_ack.splitlines()
            if f"{entry_prefix} runId={run_id} " in line
            and (f"current={run_id}" in line or "current=null" in line)
        ),
        None,
    )
    t0_match = re.search(r"t0MonoNs=(\d+)", ack_line or "")
    if not t0_match:
        raise SystemExit("launcher-entry ack is missing t0MonoNs")
    if home_path:
        if f"pathId={PATHS[1]} " not in ack_line or f"activityId={capture['homeBefore']['activityId']} " not in ack_line:
            raise SystemExit("Home entry path/Activity ACK mismatch")
        if args.cold and "priorChatVisited=false" not in ack_line:
            raise SystemExit("cold Home entry had visited Chat before T0")
        capture["homeEntryAck"] = ack_line
    capture["entryMonoNs"] = int(t0_match.group(1))
    capture["entryRequestedHostMs"] = tap_host_ms - started_host_ms
    capture["stage"] = "sample-window"
    # Use the same conservative post-ACK guard for EVERY scenario. No host-wall
    # versus device-monotonic subtraction; the frame anchor remains device T0.
    window_guard_end = entry_ack_received + TRACE_WINDOW_MS / 1000
    while time.monotonic() < window_guard_end:
        time.sleep(max(0.0, window_guard_end - time.monotonic()))
    capture["windowGuard"] = {
        "source": "host-monotonic-after-entry-ack",
        "requiredWaitMs": TRACE_WINDOW_MS,
        "waitedMs": (time.monotonic() - entry_ack_received) * 1000,
        "deviceAnchorUnchanged": True,
    }

    if args.scenario == "S7":
        capture["stage"] = "s7-stream-validation"
        assert_s7_start(True)
        broadcast(args.serial, ACTION_SEND, "--es", "runId", run_id,
                  "--es", "text", "S7_scripted_probe")
        escaped_run = re.escape(run_id)
        rejected_send = (
            rf"(?:s7-send-phase-rejected|send-rejected) runId={escaped_run}(?=\s|$)[^\n]*|"
            rf"s7-send-accepted runId={escaped_run} accepted=false\b[^\n]*|"
            rf"metrics-export runId={escaped_run}(?=\s|$)[^\n]*status=invalid\b[^\n]*"
        )
        wait_for_log(args.serial, rf"s7-send-accepted runId={escaped_run} accepted=true\b",
                     args.handshake_timeout, reject_pattern=rejected_send)
        entries = wait_for_log(args.serial, rf"s7-settled runId={escaped_run}(?=\s|$)",
                               args.s7_timeout, reject_pattern=rejected_send)
        order = {}
        for marker in ("s7-send-accepted", "s7-first-chunk", "s7-settled"):
            index = entries.find(f"{marker} runId={run_id}")
            if index < 0:
                raise SystemExit(f"S7 missing {marker}")
            order[marker] = index
        if not order["s7-send-accepted"] < order["s7-first-chunk"] < order["s7-settled"]:
            raise SystemExit("S7 order is not send->stream->settled")
        if "s7-send-accepted runId=" + run_id + " accepted=true" not in entries:
            raise SystemExit("S7 send was not accepted by the production composer")
        ui_deadline = time.time() + 3.0
        while time.time() < ui_deadline and not ui_contains(args.serial, "S7 scripted"):
            time.sleep(0.2)
        if not ui_contains(args.serial, "S7 scripted"):
            raise SystemExit("S7 streamed text is not visible in the real chat UI")
        ui_state = s7_ui_state(ui_xml(args.serial))
        if not ui_state["editPresent"]:
            raise SystemExit("S7 UI has no EditText node; refusing fail-open inputEnabled")
        if not ui_state["editEnabled"]:
            raise SystemExit("S7 input remained disabled after settle")
        capture["s7"] = {
            "order": ["send-accepted", "first-chunk", "settled"],
            "visibleText": ui_state["textVisible"],
            "inputPresent": ui_state["editPresent"],
            "inputEnabled": ui_state["editEnabled"],
            "ui": ui_state,
        }



    pid_after = adb(args.serial, "shell", "pidof", args.package, check=False).stdout.strip()
    if not pid_after:
        raise SystemExit("fixture process is not running after entry")
    if args.warm and pid_after != pid_before:
        raise SystemExit(f"warm PID changed: {pid_before} -> {pid_after}")
    capture["pidMeasured"] = pid_after

    capture["stage"] = "trace-finish"
    if args.trace_seconds > 0:
        deadline = time.time() + args.trace_seconds + 10
        previous_size = -1
        stable_checks = 0
        while time.time() < deadline:
            running = adb(args.serial, "shell", "pidof", "atrace", check=False).stdout.strip()
            stat = adb(args.serial, "shell", "stat", "-c", "%s", trace_remote, check=False)
            size = int(stat.stdout.strip()) if stat.returncode == 0 and stat.stdout.strip().isdigit() else 0
            if not running and size > 0 and size == previous_size:
                stable_checks += 1
            else:
                stable_checks = 0
            if stable_checks >= 2:
                break
            previous_size = size
            time.sleep(0.5)
        else:
            raise SystemExit("atrace did not finish with a stable, non-empty file")
        if previous_size <= 0:
            raise SystemExit("atrace file is missing/empty")
        if trace_local is None:
            raise SystemExit("--trace-output is required when tracing")
        trace_local.parent.mkdir(parents=True, exist_ok=True)
        pulled = adb(args.serial, "pull", trace_remote, str(trace_local), check=False)
        if pulled.returncode != 0 or not trace_local.is_file():
            raise SystemExit(f"atrace pull failed: {pulled.stderr or pulled.stdout}")
        capture["tracePath"] = str(trace_local)
        capture["traceSha256"] = sha256(trace_local)
        capture["traceBytes"] = trace_local.stat().st_size

    capture["stage"] = "auxiliary"
    if args.external_readable_text:
        # Auxiliary only: run after the fixed frame window so UIAutomator does
        # not load the app during measurement. Never subtract from on-Treadable.
        deadline = time.time() + args.external_timeout
        while time.time() < deadline:
            if ui_contains(args.serial, args.external_readable_text):
                break
            time.sleep(0.2)
        else:
            raise SystemExit("external readable text did not appear")
        capture["externalReadableUpperBoundMs"] = time.time() * 1000.0 - tap_host_ms
        capture["externalSource"] = "uiautomator-semantic-visible-upper-bound"
        capture["externalReadableMeasuredAfterWindow"] = True

    # The assisted interaction runs after the fixed window for both packages;
    # trace-on still records the request/response latency, trace-off uses the
    # fixture ack only. This keeps in-window load identical.
    interaction_tap = None
    mode = interaction_mode(args)
    if args.interact:
        if mode == "input":
            broadcast(args.serial, ACTION_BLUR, "--es", "runId", run_id)
            wait_for_log(
                args.serial,
                rf"blur-ack runId={re.escape(run_id)} ok=true",
                args.handshake_timeout,
            )
            time.sleep(0.2)
            broadcast(args.serial, ACTION_INTERACTION, "--es", "runId", run_id,
                      "--es", "kind", "input-tap")
            time.sleep(0.1)
            edit = ui_edit_bounds(args.serial)
            if edit is None:
                raise SystemExit("S0 input interaction: no EditText node found")
            adb(args.serial, "shell", "input", "tap", str(edit[0]), str(edit[1]))
            interaction_tap = [edit[0], edit[1]]
        else:
            broadcast(args.serial, ACTION_INTERACTION, "--es", "runId", run_id,
                      "--es", "kind", "swipe")
            width, height = screen_size(args.serial)
            x1, y1 = width // 2, int(height * 0.55)
            x2, y2 = width // 2, int(height * 0.35)
            time.sleep(0.1)
            adb(args.serial, "shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), "120")
            interaction_tap = [x1, y1, x2, y2]
        wait_for_log(
            args.serial,
            rf"interaction-confirmed runId={re.escape(run_id)}",
            args.handshake_timeout,
        )
        capture["interaction"] = {"kind": mode, "coords": interaction_tap}

    capture["stage"] = "finalization"
    if args.trace_mode == "on":
        # Wait for the independent FINAL ack; early snapshots must not satisfy
        # the collector. An invalid final means a required marker/interaction
        # is missing, so fail before returning.
        entries = wait_for_log(
            args.serial,
            rf"metrics-export runId={re.escape(run_id)} .*status=(?:final|invalid) ",
            args.final_timeout,
        )
        final_line = next(
            (
                line for line in entries.splitlines()
                if f"metrics-export runId={run_id} " in line
                and "status=final" in line
            ),
            None,
        )
        if final_line is None:
            invalid_line = next(
                (
                    line for line in entries.splitlines()
                    if f"metrics-export runId={run_id} " in line
                    and "status=invalid" in line
                ),
                None,
            )
            raise SystemExit(f"metrics finalization failed: {invalid_line or 'no final ack'}")

    capture["stage"] = "environment-post"
    post_state = home_state(args) if home_path else None
    if home_path:
        # Persist the actual reply before any later environment/validation failure.
        capture["homeAfter"] = post_state
        capture["homePostMismatches"] = home_post_mismatches(capture["homeBefore"], post_state, pid_after, fixture_conversation(args.scenario))
    capture["environmentPost"] = environment_snapshot(adb, args.serial, args.package, post=True, app_state=post_state)
    if home_path and capture["homePostMismatches"]:
        details = "; ".join(f"{m['field']} expected={m['expected']!r} actual={m['actual']!r}" for m in capture["homePostMismatches"])
        raise SystemExit("Home-first post-state mismatch: " + details)
    capture["status"] = "ok"
    return 0


def home_post_mismatches(before: dict, after: dict, measured_pid: str, conversation: str) -> list[dict]:
    expected = {"pid": measured_pid, "activityId": before.get("activityId"), "pathId": PATHS[1],
                "home": False, "topRoute": "Chat", "topConversation": conversation,
                "targetVisited": True, "lastEntryActivity": before.get("activityId"),
                "lastEntryConversation": conversation}
    mismatches = []
    for field, value in expected.items():
        actual = after.get(field)
        matches = str(actual) == value if field == "pid" else (actual is value if isinstance(value, bool) else actual == value)
        if not matches:
            mismatches.append({"field": field, "expected": value, "actual": actual})
    return mismatches


def assert_s7_start(enabled: bool) -> None:
    if not enabled:
        raise SystemExit("S7 requires the fixture provider")


def validate_metrics_payload(
    metrics: dict,
    run_id: str,
    scenario: str,
    package: str,
) -> dict:
    for key, expected in (
        ("runId", run_id), ("scenario", scenario),
        ("package", package), ("traceEnabled", True),
    ):
        if metrics.get(key) != expected:
            raise SystemExit(f"metrics {key} mismatch")
    if metrics.get("status") != "final":
        raise SystemExit(f"metrics are not final: status={metrics.get('status')!r}")
    if not isinstance(metrics.get("revision"), int) or metrics["revision"] <= 0:
        raise SystemExit("metrics revision is missing/invalid")
    interaction = metrics.get("interaction")
    if not isinstance(interaction, dict):
        raise SystemExit("metrics missing scripted interaction request/response")
    for key in ("requestedAtMs", "confirmedAtMs", "latencyMs"):
        if not isinstance(interaction.get(key), (int, float)):
            raise SystemExit(f"metrics interaction.{key} is invalid")
    if abs(
        (interaction["confirmedAtMs"] - interaction["requestedAtMs"])
        - interaction["latencyMs"]
    ) > 0.01:
        raise SystemExit("metrics interaction latency is inconsistent")
    expected_kind = "EMPTY_HERO" if scenario == "S0" else "MESSAGE"
    if metrics.get("targetKind") != expected_kind:
        raise SystemExit(
            f"metrics targetKind {metrics.get('targetKind')} != {expected_kind}"
        )
    return interaction


def collect(args: argparse.Namespace) -> int:
    require_fixture_package(args.package)
    destination = Path(args.output)
    if destination.exists() and any(destination.iterdir()):
        raise SystemExit(f"refusing to overwrite existing run directory: {destination}")
    if not args.run_id:
        raise SystemExit("--run-id is required")
    for value, label in (
        (args.apk, "--apk"), (args.frames_json, "--frames-json"),
        (args.source_manifest, "--source-manifest"), (args.capture_log, "--capture-log"),
        (args.fixture_version, "--fixture-version"),
    ):
        if not value:
            raise SystemExit(f"{label} is required")
    if args.trace_on == args.trace_off:
        raise SystemExit("exactly one of --trace-on/--trace-off is required")
    if args.trace_off:
        if args.external_readable_ms is None:
            raise SystemExit("trace-off collect requires --external-readable-ms")
        if not args.external_source:
            raise SystemExit("trace-off collect requires --external-source")
    if args.dry_run:
        print(json.dumps({"dryRun": True, "command": "collect", "runId": args.run_id}, indent=2))
        return 0
    apk = Path(args.apk)
    frames_path = Path(args.frames_json)
    source_path = Path(args.source_manifest)
    capture_path = Path(args.capture_log)
    for path in (apk, frames_path, source_path, capture_path):
        if not path.is_file():
            raise SystemExit(f"missing input: {path}")
    aapt2 = args.aapt2
    if not Path(aapt2).is_file():
        raise SystemExit(f"aapt2 missing: {aapt2}")
    info = apk_info(apk, aapt2)
    if info["package"] != args.package:
        raise SystemExit(f"APK package {info['package']} != {args.package}")
    if info["debuggable"] or not info["profileable"]:
        raise SystemExit("collect requires a non-debuggable profileable fixture APK")
    if info["traceEnabled"] != args.trace_on:
        raise SystemExit(
            f"APK trace meta-data {info['traceEnabled']} != requested {args.trace_on}"
        )
    apk_sha = sha256(apk)
    capture = json.loads(capture_path.read_text())
    requested_path = path_id(getattr(args, "path_id", None))
    if capture.get("pathId") != requested_path:
        raise SystemExit("capture pathId mismatch")
    if capture.get("status") != "ok":
        raise SystemExit("capture is not successful; keep invalid evidence out of summaries")
    for key, expected in (
        ("runId", args.run_id), ("scenario", args.scenario), ("package", args.package),
    ):
        if capture.get(key) != expected:
            raise SystemExit(f"capture {key} mismatch: {capture.get(key)!r} != {expected!r}")
    if capture.get("apkSha256") not in (None, apk_sha):
        raise SystemExit("capture APK identity mismatch")
    if capture.get("phase") != args.phase:
        raise SystemExit("capture phase mismatch")
    trace_path = Path(capture["tracePath"]) if capture.get("tracePath") else None
    if args.trace_on and trace_path is None:
        raise SystemExit("trace-on collect requires the capture log's trace path")
    if trace_path is not None:
        if not trace_path.is_file():
            raise SystemExit(f"trace missing: {trace_path}")
        if sha256(trace_path) != capture.get("traceSha256"):
            raise SystemExit("trace hash does not match the capture log")
    if args.trace and trace_path is not None and sha256(Path(args.trace)) != capture.get("traceSha256"):
        raise SystemExit("--trace does not match the capture log")
    frames = json.loads(frames_path.read_text())
    if frames.get("pathId") != requested_path or frames.get("parserVersion") != PARSER_VERSION:
        raise SystemExit("frames pathId/parserVersion mismatch")
    if not isinstance(frames.get("valuesMs"), list) or not frames["valuesMs"]:
        raise SystemExit("frames-json needs non-empty valuesMs")
    if frames.get("deadlineStatus") == "unknown":
        if frames.get("deadlineMs") is not None:
            raise SystemExit("unknown deadline cannot supply a numeric budget")
    elif frames.get("deadlineStatus") == "evidenced":
        if not isinstance(frames.get("deadlineMs"), (int, float)) or frames["deadlineMs"] <= 0:
            raise SystemExit("frames-json needs a positive deadlineMs")
        evidence = frames.get("deadlineEvidence") or {}
        path = Path(evidence.get("path", ""))
        if not path.is_file() or sha256(path) != evidence.get("sha256") or evidence.get("displayId") is None:
            raise SystemExit("deadline evidence missing, changed or not bound to target display")
    else:
        raise SystemExit("frames-json deadline needs explicit evidenced/unknown status")
    if float(frames.get("windowMs", 0)) != TRACE_WINDOW_MS:
        raise SystemExit("frames-json windowMs must be 1500")
    if frames.get("runId") != args.run_id:
        raise SystemExit("frames-json belongs to another run")
    if frames.get("pid") is not None and capture.get("pidMeasured") not in (None, str(frames["pid"])):
        raise SystemExit("frames-json pid does not match the measured process")
    if frames.get("parserSourceSha256") != sha256(Path(__file__).with_name("parse-atrace-frames.py")):
        raise SystemExit("frames parser source fingerprint mismatch")
    source = json.loads(source_path.read_text())
    untracked = source.get("untrackedFiles")
    if not isinstance(untracked, dict):
        raise SystemExit("source manifest needs untrackedFiles")
    for key in ("head", "trackedDiffSha256"):
        if not source.get(key):
            raise SystemExit(f"source manifest missing {key}")

    for name in ("chat-history-entry-perf.py", "parse-atrace-frames.py", "chat_perf_contract.py"):
        if source.get("hostFiles", {}).get(name) != sha256(Path(__file__).with_name(name)):
            raise SystemExit("host source manifest mismatch: " + name)
    advertised = source.get("build", {}).get("apks", {})
    if not any(artifact.get("sha256") == apk_sha for artifact in advertised.values()):
        raise SystemExit("source manifest does not bind the supplied APK hash")
    destination.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as temp:
        installed_before = installed_apk_hash(args.serial, args.package, temp)
        if installed_before != apk_sha:
            raise SystemExit(
                f"installed APK hash {installed_before} != supplied {apk_sha}"
            )
        metrics_payload = None
        if args.trace_on:
            remote = (
                f"/sdcard/Android/data/{args.package}/files/chat-perf/runs/"
                f"{sanitize_run_id(args.run_id)}"
            )
            pulled_dir = Path(temp) / "run"
            pulled = adb(args.serial, "pull", remote, str(pulled_dir), check=False)
            if pulled.returncode != 0:
                raise SystemExit(f"metrics pull failed: {pulled.stderr or pulled.stdout}")
            metrics_files = sorted(pulled_dir.rglob("metrics-*.json"))
            if len(metrics_files) != 1:
                raise SystemExit(f"expected one metrics file, found {len(metrics_files)}")
            metrics_payload = json.loads(metrics_files[0].read_text())
            if metrics_payload.get("pathId") != requested_path:
                raise SystemExit("metrics pathId mismatch")
            validate_metrics_payload(
                metrics_payload, args.run_id, args.scenario, args.package
            )
            shutil.copy2(metrics_files[0], destination / f"metrics-{sanitize_run_id(args.run_id)}.json")
        installed_after = installed_apk_hash(args.serial, args.package, temp)
        if installed_after != installed_before:
            raise SystemExit("installed APK changed during collection")

    if not isinstance(capture.get("environmentPre"), dict) or not isinstance(capture.get("environmentPost"), dict):
        raise SystemExit("missing before/after environment evidence")
    shutil.copy2(source_path, destination / "source-manifest.json")
    shutil.copy2(capture_path, destination / "capture.json")
    if frames.get("deadlineStatus") == "evidenced":
        shutil.copy2(Path(frames["deadlineEvidence"]["path"]), destination / "deadline-evidence.txt")
        frames["deadlineEvidence"] = {**frames["deadlineEvidence"], "path": "deadline-evidence.txt"}
    if trace_path is not None:
        shutil.copy2(trace_path, destination / trace_path.name)
    device = device_identity(args.serial)
    manifest = {
        "pathId": requested_path,
        "hostVersion": capture.get("hostVersion"),
        "parserVersion": PARSER_VERSION,
        "parserSourceSha256": frames["parserSourceSha256"],
        "environmentPre": capture.get("environmentPre"),
        "environmentPost": capture.get("environmentPost"),
        "homeBefore": capture.get("homeBefore"),
        "schema": 1,
        "runId": args.run_id,
        "scenario": args.scenario,
        "phase": args.phase,
        "package": args.package,
        "apkSha256": apk_sha,
        "sourceManifestSha256": sha256(source_path),
        "sourceDiffSha256": source["trackedDiffSha256"],
        "untrackedDigest": hashlib.sha256(
            json.dumps(untracked, sort_keys=True, separators=(",", ":")).encode()
        ).hexdigest(),
        "fixtureVersion": args.fixture_version,
        "traceEnabled": args.trace_on,
        "device": device,
        "build": {
            "variant": args.build_variant,
            "debuggable": info["debuggable"],
            "profileable": info["profileable"],
            "chatPerfTrace": info["traceEnabled"],
            "application": info["application"],
            "abi": info["abi"],
            "baselineProfile": info["baselineProfile"],
            "signerSha256": apk_signer_sha256(apk, args.apksigner),
        },
        "frames": {
            "parserVersion": PARSER_VERSION,
            "source": frames.get("source", "unknown"),
            "deadlineMs": frames["deadlineMs"],
            "deadlineStatus": frames["deadlineStatus"],
            "deadlineEvidence": frames.get("deadlineEvidence"),
            "windowMs": TRACE_WINDOW_MS,
            "valuesMs": [float(value) for value in frames["valuesMs"]],
            "pid": frames.get("pid"),
            "entries": frames.get("frames", []),
            "nestedEntries": frames.get("nestedFrames", []),
            "rawEntries": frames.get("rawFrames", []),
            "anchorSource": frames.get("anchorSource"),
            "anchorMonotonicNs": frames.get("anchorMonotonicNs"),
        },
    }
    if not args.trace_on:
        manifest["externalTiming"] = {
            "firstReadableMs": float(args.external_readable_ms),
            "source": args.external_source,
        }
    (destination / "run-manifest.json").write_text(json.dumps(manifest, indent=2))
    print(json.dumps({"collected": str(destination), "runId": args.run_id}, indent=2))
    return 0


def s8_sequence(args: argparse.Namespace) -> int:
    require_fixture_package(args.package)
    if args.dry_run:
        return _s8_sequence_impl(args)
    output = Path(args.output)
    if output.exists():
        raise SystemExit(f"refusing to overwrite S8 evidence: {output}")
    try:
        return _s8_sequence_impl(args)
    except BaseException as error:
        evidence = json.loads(output.read_text()) if output.is_file() else {"steps": []}
        evidence.update(status="invalid", errorClass=type(error).__name__, reason=str(error),
                        stage=evidence["steps"][-1]["step"] if evidence["steps"] else "initial-entry")
        try:
            save_evidence(args, evidence, output, failed=True)
        except BaseException as diagnostic_error:
            print(f"could not save S8 failure evidence: {diagnostic_error}", file=sys.stderr)
        raise


def restored_route_line(entries: str, pid: str, before_restore: str) -> str | None:
    """Actual RouteActivity probe, new PID, and append-only current-step boundary."""
    if not entries.startswith(before_restore):
        raise SystemExit("restore log boundary lost (logcat rotated/replaced); refusing stale evidence")
    pattern = re.compile(
        rf"^\d{{2}}-\d{{2}}\s+\d{{2}}:\d{{2}}:\d{{2}}\.\d+\s+{re.escape(pid)}\s+\d+\s+"
        rf"I\s+AmberPerfFixture:\s+route-created savedState=true\s*$"
    )
    return next((line for line in entries[len(before_restore):].splitlines() if pattern.fullmatch(line)), None)


def _s8_sequence_impl(args: argparse.Namespace) -> int:
    """Real task-restore + fast switch/cancel sequence (no force-stop)."""
    require_fixture_package(args.package)
    if args.dry_run:
        print(json.dumps({
            "dryRun": True,
            "steps": [
                "launch S8 deep link", "assert S8 content", "HOME", "am kill",
                "am start RouteActivity --activity-reorder-to-front",
                "assert savedState=true log + S8 content", "snapshot",
                "fast switch S2->S8", "assert S8 only", "snapshot",
                "cancel S2->S8", "assert S8 only", "snapshot",
            ],
        }, indent=2))
        return 0
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    s8_id = "10000000-0000-4000-8000-000000000008"
    s2_id = "10000000-0000-4000-8000-000000000002"
    evidence: dict[str, object] = {"schema": 1, "steps": [], "status": "running"}
    output.write_text(json.dumps(evidence, indent=2))

    def step(name: str, **fields) -> None:
        evidence["steps"].append({"step": name, **fields})
        output.write_text(json.dumps(evidence, indent=2))

    def launch(conversation: str, wait: bool = True) -> None:
        cmd = ["shell", "am", "start"]
        if wait:
            cmd.append("-W")
        cmd += ["-n", f"{args.package}/app.amber.agent.RouteActivity",
                "--es", "conversationId", conversation]
        adb(args.serial, *cmd)

    def snapshot(name: str, expected_conversation: str) -> None:
        nonce = secrets.token_hex(8)
        step(name + "-request", nonce=nonce, expectedConversation=expected_conversation)
        broadcast(
            args.serial, ACTION_SNAPSHOT,
            "--es", "nonce", nonce,
        )
        deadline = time.time() + 5
        line = None
        while time.time() < deadline:
            line = snapshot_line(
                logcat_entries(args.serial), nonce, expected_conversation
            )
            if line:
                break
            time.sleep(0.2)
        step(
            name, snapshot=line, nonce=nonce,
            expectedConversation=expected_conversation, ok=line is not None,
        )
        if line is None:
            raise SystemExit(
                f"S8 owner snapshot nonce={nonce} did not show "
                f"conversation={expected_conversation}"
            )

    launch(s8_id)
    deadline = time.time() + 15
    while time.time() < deadline and not ui_contains(args.serial, "S8 tail"):
        time.sleep(0.25)
    if not ui_contains(args.serial, "S8 tail"):
        raise SystemExit("S8 initial entry did not show S8 content")
    step("initial-entry", visible="S8 tail")
    snapshot("initial-owner", s8_id)

    adb(args.serial, "shell", "input", "keyevent", "KEYCODE_HOME")
    time.sleep(1.0)
    pid_before = adb(args.serial, "shell", "pidof", args.package, check=False).stdout.strip()
    if not pid_before.isdigit():
        raise SystemExit("S8 restore requires one live owner PID before process kill")
    adb(args.serial, "shell", "am", "kill", args.package, check=False)
    time.sleep(1.0)
    pid_killed = adb(args.serial, "shell", "pidof", args.package, check=False).stdout.strip()
    if pid_killed:
        raise SystemExit("am kill did not remove the background process")
    # Bring the existing task back to the front; this is task restore, not a
    # force-stop re-entry. Evidence must show savedState=true and S8 content.
    before_restore = logcat_entries(args.serial)
    step("restore-request", pidBefore=pid_before, pidKilled=None,
         logBoundarySha256=hashlib.sha256(before_restore.encode()).hexdigest(),
         logBoundaryChars=len(before_restore))
    adb(args.serial, "shell", "am", "start", "-W",
        "--activity-reorder-to-front",
        "-n", f"{args.package}/app.amber.agent.RouteActivity", check=False)
    deadline = time.time() + 20
    restored = False
    restored_line = None
    pid_restored = ""
    while time.time() < deadline:
        pid_restored = adb(args.serial, "shell", "pidof", args.package, check=False).stdout.strip()
        if pid_restored.isdigit() and pid_restored != pid_before:
            restored_line = restored_route_line(logcat_entries(args.serial), pid_restored, before_restore)
            if restored_line and ui_contains(args.serial, "S8 tail"):
                # The process proving the probe must still own the UI response.
                confirmed_pid = adb(args.serial, "shell", "pidof", args.package, check=False).stdout.strip()
                if confirmed_pid == pid_restored:
                    restored = True
                    break
        time.sleep(0.3)
    step("process-death-task-restore", restored=restored,
         pidBefore=pid_before, pidKilled=pid_killed or None,
         pidRestored=pid_restored or None, routeProbe=restored_line)
    if not restored:
        output.write_text(json.dumps(evidence, indent=2))
        raise SystemExit("real task restore was not observed (need new PID + current-step route-created savedState=true + S8 content)")
    snapshot("restored-owner", s8_id)

    launch(s2_id, wait=False)
    launch(s8_id, wait=False)
    deadline = time.time() + 15
    while time.time() < deadline and not ui_contains(args.serial, "S8 tail"):
        time.sleep(0.2)
    fast_ok = ui_contains(args.serial, "S8 tail") and not ui_contains(args.serial, "S2 fixture")
    step("fast-switch-S2-to-S8", ok=fast_ok)
    snapshot("fast-switch-owner", s8_id)

    launch(s2_id, wait=False)
    time.sleep(0.05)
    launch(s8_id, wait=False)
    deadline = time.time() + 15
    while time.time() < deadline and not ui_contains(args.serial, "S8 tail"):
        time.sleep(0.2)
    cancel_ok = ui_contains(args.serial, "S8 tail") and not ui_contains(args.serial, "S2 fixture")
    step("cancel-S2-to-S8", ok=cancel_ok)
    snapshot("cancel-owner", s8_id)

    if not (fast_ok and cancel_ok):
        raise SystemExit("S8 fast-switch/cancel stale content detected")
    evidence["status"] = "ok"
    output.write_text(json.dumps(evidence, indent=2))
    print(json.dumps(evidence, indent=2))
    return 0


def add_common(parser: argparse.ArgumentParser, *, serial: bool = True) -> None:
    if serial:
        parser.add_argument("--serial", required=True)
    parser.add_argument("--package", default=PACKAGE)
    parser.add_argument("--adb", default="adb", help="adb command; supports 'arch -x86_64 /path/adb'")
    parser.add_argument("--dry-run", action="store_true")


def main() -> int:
    parser = argparse.ArgumentParser()
    add_common(parser, serial=False)
    sub = parser.add_subparsers(dest="command", required=True)

    p_doctor = sub.add_parser("doctor")
    add_common(p_doctor)
    p_doctor.add_argument("--apk", required=True)
    p_doctor.add_argument("--test-apk", required=True)
    p_doctor.add_argument("--aapt2", default=os.environ.get(
        "AAPT2", shutil.which("aapt2") or "aapt2"))
    p_doctor.add_argument("--apksigner", default=os.environ.get(
        "APKSIGNER", shutil.which("apksigner") or "apksigner"))
    p_doctor.add_argument("--expect-instrumentation")
    p_doctor.set_defaults(func=doctor)

    p_prepare = sub.add_parser("prepare")
    add_common(p_prepare)
    p_prepare.add_argument("--path-id", choices=PATHS, default=PATHS[0])
    p_prepare.add_argument("--scenario", required=True, choices=SCENARIOS)
    p_prepare.add_argument("--run-id", required=True)
    p_prepare.add_argument("--timeout", type=float, default=45.0)
    p_prepare.add_argument("--capture-log")
    p_prepare.set_defaults(func=prepare)

    p_start = sub.add_parser("start")
    add_common(p_start)
    p_start.add_argument("--path-id", choices=PATHS, required=True)
    p_start.add_argument("--scenario", required=True, choices=SCENARIOS)
    p_start.add_argument("--run-id", required=True)
    p_start.add_argument("--cold", action="store_true")
    p_start.add_argument("--warm", action="store_true")
    p_start.add_argument("--settle", type=float, default=1.2)
    p_start.add_argument("--launcher-timeout", type=float, default=10.0)
    p_start.add_argument("--handshake-timeout", type=float, default=8.0)
    p_start.add_argument("--final-timeout", type=float, default=15.0)
    p_start.add_argument("--trace-seconds", type=float, default=3.0)
    p_start.add_argument("--trace-output")
    p_start.add_argument("--capture-log")
    p_start.add_argument("--interact", action=argparse.BooleanOptionalAction, default=True)
    trace_group = p_start.add_mutually_exclusive_group()
    trace_group.add_argument("--trace-on", dest="trace_mode", action="store_const", const="on")
    trace_group.add_argument("--trace-off", dest="trace_mode", action="store_const", const="off")
    p_start.set_defaults(trace_mode="on")
    p_start.add_argument("--apk")
    p_start.add_argument("--aapt2", default=os.environ.get(
        "AAPT2", shutil.which("aapt2") or "aapt2"))
    p_start.add_argument(
        "--interaction-mode", choices=["auto", "swipe", "input"], default="auto",
    )
    p_start.add_argument("--s7-timeout", type=float, default=20.0)
    p_start.add_argument("--external-readable-text")
    p_start.add_argument("--external-timeout", type=float, default=8.0)
    p_start.set_defaults(func=start)

    p_collect = sub.add_parser("collect")
    add_common(p_collect)
    p_collect.add_argument("--path-id", choices=PATHS, required=True)
    p_collect.add_argument("--output", required=True)
    p_collect.add_argument("--run-id", required=True)
    p_collect.add_argument("--scenario", required=True, choices=SCENARIOS)
    p_collect.add_argument("--phase", required=True, choices=["cold", "warm"])
    p_collect.add_argument("--apk", required=True)
    p_collect.add_argument("--source-manifest", required=True)
    p_collect.add_argument("--frames-json", required=True)
    p_collect.add_argument("--capture-log", required=True)
    p_collect.add_argument("--fixture-version", required=True)
    p_collect.add_argument("--build-variant", default="fullPerffixture")
    p_collect.add_argument("--trace-on", action="store_true")
    p_collect.add_argument("--trace-off", action="store_true")
    p_collect.add_argument("--trace")
    p_collect.add_argument("--external-readable-ms", type=float)
    p_collect.add_argument("--external-source")
    p_collect.add_argument("--aapt2", default=os.environ.get(
        "AAPT2", shutil.which("aapt2") or "aapt2"))
    p_collect.add_argument("--apksigner", default=os.environ.get(
        "APKSIGNER", shutil.which("apksigner") or "apksigner"))
    p_collect.set_defaults(func=collect)

    p_s8 = sub.add_parser("s8-sequence")
    add_common(p_s8)
    p_s8.add_argument("--output", required=True)
    p_s8.set_defaults(func=s8_sequence)

    args = parser.parse_args()
    configure_adb(args.adb)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
