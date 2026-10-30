import argparse
import contextlib
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).resolve().parents[1] / "chat-history-entry-perf.py"
spec = importlib.util.spec_from_file_location("chat_perf", MODULE_PATH)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def start_args(**overrides):
    values = dict(
        serial="mock-device",
        package=module.PACKAGE, path_id="native-launcher-stress",
        adb="adb",
        dry_run=True,
        scenario="S1",
        run_id="run-1",
        cold=True,
        warm=False,
        settle=1.2,
        trace_seconds=3.0,
        interact=True,
        interaction_mode="auto",
        handshake_timeout=8.0,
        final_timeout=15.0,
        trace_mode="on",
        apk=None,
        aapt2="/mock/android-build-tools/aapt2",
        external_readable_text=None,
    )
    values.update(overrides)
    return argparse.Namespace(**values)


class StartPlanTest(unittest.TestCase):
    def kinds(self, args):
        return [step["kind"] for step in module.build_start_plan(args)]

    def test_cold_plan_starts_atrace_before_the_entry_tap(self):
        kinds = self.kinds(start_args())
        self.assertEqual(kinds[0], "force-stop")
        self.assertLess(kinds.index("atrace-start"), kinds.index("tap-open"))
        self.assertLess(kinds.index("coords"), kinds.index("set-run"))
        self.assertLess(kinds.index("set-run"), kinds.index("tap-open"))
        self.assertLess(kinds.index("tap-open"), kinds.index("pid-measured"))
        self.assertLess(kinds.index("pid-measured"), kinds.index("atrace-wait"))
        self.assertLess(kinds.index("atrace-wait"), kinds.index("interaction-request"))
        self.assertLess(kinds.index("atrace-start"), kinds.index("atrace-wait"))
        self.assertEqual(kinds[-1], "metrics-export-wait")

    def test_warm_plan_never_clears_or_force_stops(self):
        args = start_args(cold=False, warm=True)
        kinds = self.kinds(args)
        self.assertNotIn("force-stop", kinds)
        self.assertEqual(kinds[0], "pid-before")
        self.assertLess(kinds.index("pid-before"), kinds.index("warm-return-launcher"))
        self.assertLess(kinds.index("warm-return-launcher"), kinds.index("pid-after"))
        self.assertLess(kinds.index("pid-after"), kinds.index("tap-open"))
        self.assertNotIn("prepare", kinds)

    def test_s0_uses_request_focus_instead_of_a_swipe(self):
        kinds = self.kinds(start_args(scenario="S0"))
        self.assertIn("blur", kinds)
        self.assertIn("interaction-request-input", kinds)
        self.assertIn("tap-input", kinds)
        self.assertNotIn("swipe", kinds)
        self.assertLess(kinds.index("blur"), kinds.index("interaction-request-input"))
        self.assertLess(kinds.index("interaction-request-input"), kinds.index("tap-input"))

    def test_s7_plan_contains_deterministic_send_stream_settle_step(self):
        kinds = self.kinds(start_args(scenario="S7"))
        self.assertIn("s7-send-stream-settled", kinds)
        self.assertLess(kinds.index("tap-open"), kinds.index("s7-send-stream-settled"))

    def test_run_id_is_sanitized_like_the_app(self):
        self.assertEqual("a_b_c_1", module.sanitize_run_id("a/b c:1"))
        self.assertLessEqual(len(module.sanitize_run_id("x" * 200)), 80)

    def test_dry_run_does_not_touch_the_device(self):
        args = start_args()
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(module.start(args), 0)
        payload = json.loads(output.getvalue())
        self.assertTrue(payload["dryRun"])
        self.assertIn("atrace-start", [step["kind"] for step in payload["plan"]])


class CollectGuardTest(unittest.TestCase):
    def collect_args(self, directory, **overrides):
        values = dict(
            serial="mock-device",
            package=module.PACKAGE, path_id="native-launcher-stress",
            adb="adb",
            dry_run=True,
            output=str(Path(directory) / "run"),
            run_id="run-1",
            scenario="S1",
            phase="cold",
            apk=str(Path(directory) / "fixture.apk"),
            source_manifest=str(Path(directory) / "source.json"),
            frames_json=str(Path(directory) / "frames.json"),
            capture_log=str(Path(directory) / "capture.json"),
            fixture_version="fixture-v1",
            build_variant="fullPerffixture",
            trace_on=True,
            trace_off=False,
            trace=None,
            external_readable_ms=None,
            external_source=None,
            aapt2="/nonexistent/aapt2",
            apksigner="/nonexistent/apksigner",
        )
        values.update(overrides)
        return argparse.Namespace(**values)

    def test_collect_requires_exactly_one_trace_mode(self):
        with tempfile.TemporaryDirectory() as directory:
            args = self.collect_args(directory, trace_on=False, trace_off=False)
            with self.assertRaises(SystemExit):
                module.collect(args)

    def test_trace_off_collect_requires_external_measurement(self):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory, "fixture.apk").write_bytes(b"apk")
            Path(directory, "source.json").write_text("{}")
            Path(directory, "frames.json").write_text(json.dumps({
                "source": "atrace", "deadlineMs": 16.0, "windowMs": 1500.0,
                "valuesMs": [12.0],
            }))
            Path(directory, "capture.json").write_text("{}")
            args = self.collect_args(
                directory, trace_on=False, trace_off=True,
                apk=str(Path(directory) / "fixture.apk"),
                source_manifest=str(Path(directory) / "source.json"),
                frames_json=str(Path(directory) / "frames.json"),
                capture_log=str(Path(directory) / "capture.json"),
            )
            # Dry-run is checked after the trace-off external contract.
            with self.assertRaises(SystemExit):
                module.collect(args)


if __name__ == "__main__":
    unittest.main()


class FakeAdb:
    def __init__(self, pid="111", ack_set_run=True, include_edit=True,
                 trace_enabled=True, open_enabled=True):
        self.pid = pid
        self.open_enabled = open_enabled
        self.ack_set_run = ack_set_run
        self.include_edit = include_edit
        self.trace_enabled = trace_enabled
        self.input_mode = False
        self.run_counter = 0
        self.launcher_run = "RID"
        self.launcher_scenario = "S1"
        self.logs = []
        self.calls = []
        self.t0 = 1_000_000_000

    def __call__(self, serial, *args, check=True):
        import subprocess
        self.calls.append(args)
        cmd = list(args)
        out = ""
        if cmd[:4] == ["shell", "dumpsys", "window", "policy"]:
            out = "mScreenOnFully=true mShowingLockscreen=false"
        elif cmd[:3] == ["shell", "dumpsys", "power"]:
            out = "mWakefulness=Awake Display Power: state=ON"
        elif cmd[:3] == ["shell", "pm", "clear"]:
            out = "Success"
        elif cmd[:2] == ["shell", "pidof"]:
            out = self.pid
        elif cmd[:4] == ["shell", "am", "force-stop", module.PACKAGE]:
            self.run_counter += 1
            self.pid = str(1000 + self.run_counter)
        elif cmd[:3] == ["shell", "am", "start"] and "verifyOnly" in cmd:
            self.logs.append(f"verified runId=PREP scenario=S1 status=ok restarted=true pid={self.pid}")
        elif cmd[:3] == ["shell", "am", "broadcast"]:
            action = cmd[cmd.index("-a") + 1]
            extras = {}
            rest = cmd[cmd.index("-n") + 2:]
            for index in range(0, len(rest) - 2, 3):
                if rest[index] == "--es":
                    extras[rest[index + 1]] = rest[index + 2]
            if action == module.ACTION_SET_RUN and self.ack_set_run:
                self.launcher_run = extras["runId"]
                self.launcher_scenario = extras["scenario"]
                self.logs.append(
                    f"launcher-run-id runId={self.launcher_run} "
                    f"scenario={self.launcher_scenario}"
                )
            elif action == module.ACTION_BLUR:
                self.logs.append(f"blur-ack runId={extras.get('runId','')} ok=true")
            elif action == module.ACTION_INTERACTION:
                self.input_mode = extras.get("kind") == "input-tap"
                self.logs.append(
                    f"interaction-requested runId={self.launcher_run} kind={extras.get('kind')}"
                )
        elif cmd[:2] == ["shell", "input"]:
            if cmd[2] == "tap":
                if cmd[3:5] == ["20", "20"]:
                    self.t0 += 1_000_000
                    self.logs.append(
                        f"launcher-entry runId={self.launcher_run} "
                        f"scenario={self.launcher_scenario} "
                        f"t0MonoNs={self.t0} "
                        f"current={self.launcher_run if self.trace_enabled else 'null'}"
                    )
                    if self.trace_enabled:
                        self.logs.append(
                            f"metrics-snapshot runId={self.launcher_run} "
                            f"revision=1 status=snapshot complete=false"
                        )
                elif self.input_mode:
                    self.logs.append(
                        f"interaction-confirmed runId={self.launcher_run} "
                        f"source=input-focus latencyMs=20.0"
                    )
                    if self.trace_enabled:
                        self.logs.append(
                            f"metrics-export runId={self.launcher_run} status=final "
                            f"revision=2 complete=true"
                        )
            elif cmd[2] == "swipe":
                self.logs.append(
                    f"interaction-confirmed runId={self.launcher_run} source=lazy-scroll latencyMs=9.0"
                )
                if self.trace_enabled:
                    self.logs.append(
                        f"metrics-export runId={self.launcher_run} status=final "
                        f"revision=2 complete=true"
                    )
        elif cmd[:2] == ["shell", "uiautomator"]:
            out = ""
        elif cmd[:2] == ["shell", "cat"]:
            edit = (
                '<node class="android.widget.EditText" enabled="true" '
                'bounds="[400,1900][680,2000]" />'
                if self.include_edit else ""
            )
            out = (
                '<hierarchy><node text="OPEN HISTORY" content-desc="perf_open_history" '
                'class="android.widget.Button" enabled="' +
                ('true' if self.open_enabled else 'false') + '" '
                'bounds="[10,10][30,30]" />' + edit + "</hierarchy>"
            )
        elif cmd[:2] == ["shell", "wm"]:
            out = "Physical size: 1080x2400"
        elif cmd[:2] == ["logcat", "-d"]:
            out = "\n".join(self.logs)
        return subprocess.CompletedProcess(args=cmd, returncode=0, stdout=out, stderr="")


class WarmHandshakeTest(unittest.TestCase):
    def args(self, run_id, **overrides):
        values = dict(
            serial="mock-device", package=module.PACKAGE, path_id="native-launcher-stress", adb="adb", dry_run=False,
            scenario="S1", run_id=run_id, cold=False, warm=True,
            settle=0.0, trace_seconds=0.0, trace_output=None, capture_log=None,
            interact=True, interaction_mode="auto", handshake_timeout=0.5,
            final_timeout=5.0, trace_mode="off", apk=None,
            aapt2="/mock/android-build-tools/aapt2",
            s7_timeout=5.0, external_readable_text=None, external_timeout=1.0,
        )
        values.update(overrides)
        return argparse.Namespace(**values)

    def test_back_to_old_launcher_updates_run_id_and_keeps_pid(self):
        fake = FakeAdb()
        original = module.adb
        module.adb = fake
        self.addCleanup(setattr, module, "adb", original)
        with tempfile.TemporaryDirectory() as directory:
            first = self.args("WID")
            first.capture_log = str(Path(directory) / "wid.json")
            self.assertEqual(module.start(first), 0)
            second = self.args("WID2")
            second.capture_log = str(Path(directory) / "wid2.json")
            self.assertEqual(module.start(second), 0)
            first_capture = json.loads((Path(directory) / "wid.json").read_text())
            second_capture = json.loads((Path(directory) / "wid2.json").read_text())
        self.assertEqual(first_capture["runId"], "WID")
        self.assertEqual(second_capture["runId"], "WID2")
        self.assertNotEqual(first_capture["entryMonoNs"], second_capture["entryMonoNs"])
        self.assertEqual(first_capture["pidMeasured"], second_capture["pidMeasured"])
        self.assertEqual(fake.launcher_run, "WID2")
        self.assertFalse(any("force-stop" in call for call in fake.calls))
        for call in fake.calls:
            if call[:2] == ["shell", "am"]:
                self.assertFalse(any("clear" in str(part) for part in call))

    def test_missing_launcher_run_ack_fails_closed(self):
        fake = FakeAdb(ack_set_run=False)
        original = module.adb
        module.adb = fake
        self.addCleanup(setattr, module, "adb", original)
        with self.assertRaises(SystemExit):
            module.start(self.args("WID"))


class UiStateTest(unittest.TestCase):
    def test_missing_or_disabled_edit_is_not_input_enabled(self):
        no_edit = '<hierarchy><node text="S7 scripted" /></hierarchy>'
        self.assertFalse(module.s7_ui_state(no_edit)["editPresent"])
        disabled = (
            '<hierarchy><node text="S7 scripted" />'
            '<node class="android.widget.EditText" enabled="false" /></hierarchy>'
        )
        self.assertTrue(module.s7_ui_state(disabled)["editPresent"])
        self.assertFalse(module.s7_ui_state(disabled)["editEnabled"])
        enabled = disabled.replace('enabled="false"', 'enabled="true"')
        self.assertTrue(module.s7_ui_state(enabled)["editEnabled"])


class S0InteractionTest(unittest.TestCase):
    def args(self, **overrides):
        values = dict(
            serial="mock-device", package=module.PACKAGE, path_id="native-launcher-stress", adb="adb", dry_run=False,
            scenario="S0", run_id="S0-WID", cold=False, warm=True,
            settle=0.0, trace_seconds=0.0, trace_output=None, capture_log=None,
            interact=True, interaction_mode="auto", handshake_timeout=0.5,
            final_timeout=5.0, trace_mode="off", apk=None,
            aapt2="/mock/android-build-tools/aapt2",
            s7_timeout=5.0, external_readable_text=None, external_timeout=1.0,
        )
        values.update(overrides)
        return argparse.Namespace(**values)

    def test_no_scroll_content_uses_blur_then_real_focus_response(self):
        fake = FakeAdb()
        original = module.adb
        module.adb = fake
        self.addCleanup(setattr, module, "adb", original)
        with tempfile.TemporaryDirectory() as directory:
            args = self.args(capture_log=str(Path(directory) / "s0.json"))
            self.assertEqual(module.start(args), 0)
            capture = json.loads((Path(directory) / "s0.json").read_text())
        self.assertEqual(capture["interaction"]["kind"], "input")
        self.assertTrue(any(call[:3] == ("shell", "input", "tap") and call[3:5] == ("540", "1950")
                            for call in fake.calls))
        self.assertIn("interaction-confirmed runId=S0-WID source=input-focus", "\n".join(fake.logs))

    def test_missing_edit_node_fails_instead_of_using_a_swipe(self):
        fake = FakeAdb(include_edit=False)
        original = module.adb
        module.adb = fake
        self.addCleanup(setattr, module, "adb", original)
        with self.assertRaises(SystemExit):
            module.start(self.args())


class SnapshotEvidenceTest(unittest.TestCase):
    def test_old_snapshot_cannot_satisfy_a_new_nonce(self):
        entries = (
            "snapshot nonce=old runId=none conversation=AAA nodes=1 loading=false\n"
            "snapshot nonce=new runId=none conversation=AAA nodes=2 loading=false\n"
        )
        self.assertIsNone(module.snapshot_line(entries, "missing", "AAA"))
        self.assertIn("nodes=2", module.snapshot_line(entries, "new", "AAA") or "")
        self.assertIsNone(module.snapshot_line(entries, "new", "BBB"))


class TraceModeCommandTest(unittest.TestCase):
    ON_APK = "/mock/apks/fixture-trace-on.apk"
    OFF_APK = "/mock/apks/fixture-trace-off.apk"
    AAPT2 = "/mock/android-build-tools/aapt2"

    def run_main(self, argv, fake):
        import contextlib
        import io
        import sys
        from unittest.mock import patch
        ctx = patch.object(module, 'apk_info', return_value={'traceEnabled': fake.trace_enabled}); ctx.start(); self.addCleanup(ctx.stop)
        ctx = patch.object(module.Path, 'is_file', return_value=True); ctx.start(); self.addCleanup(ctx.stop)
        ctx = patch.object(module, 'sha256', return_value='a' * 64); ctx.start(); self.addCleanup(ctx.stop)
        original = module.adb
        module.adb = fake
        self.addCleanup(setattr, module, "adb", original)
        old_argv = sys.argv
        sys.argv = ["chat-history-entry-perf.py"] + argv[:1] + (["--path-id", "native-launcher-stress"] if argv[0] in ("start", "collect") else []) + argv[1:]
        self.addCleanup(setattr, sys, "argv", old_argv)
        with contextlib.redirect_stdout(io.StringIO()):
            return module.main()

    def base(self, run_id, phase, mode, capture, **extra):
        argv = [
            "start", "--serial", "mock-device", "--scenario", "S1",
            "--run-id", run_id, f"--{phase}", "--trace-seconds", "0",
            "--capture-log", capture, "--apk",
            self.ON_APK if mode == "on" else self.OFF_APK,
            "--aapt2", self.AAPT2,
        ]
        if mode == "off":
            argv += ["--trace-off", "--no-interact"]
        for key, value in extra.items():
            argv += [f"--{key.replace('_', '-')}", str(value)]
        return argv

    def test_main_parses_and_mocks_on_off_cold_warm(self):
        with tempfile.TemporaryDirectory() as directory:
            fake_on = FakeAdb(trace_enabled=True)
            captures = {}
            runs = [
                ("ON-COLD", "cold", "on", fake_on),
                ("ON-WARM", "warm", "on", fake_on),
            ]
            for run_id, phase, mode, fake in runs:
                path = str(Path(directory) / f"{run_id}.json")
                self.assertEqual(self.run_main(self.base(run_id, phase, mode, path), fake), 0)
                captures[run_id] = json.loads(Path(path).read_text())
            fake_off = FakeAdb(trace_enabled=False)
            for run_id, phase, mode, fake in [
                ("OFF-COLD", "cold", "off", fake_off),
                ("OFF-WARM", "warm", "off", fake_off),
            ]:
                path = str(Path(directory) / f"{run_id}.json")
                self.assertEqual(self.run_main(self.base(run_id, phase, mode, path), fake), 0)
                captures[run_id] = json.loads(Path(path).read_text())
        self.assertEqual(len({c["runId"] for c in captures.values()}), 4)
        self.assertEqual(captures["ON-WARM"]["pidMeasured"], captures["ON-COLD"]["pidMeasured"])
        self.assertEqual(captures["OFF-WARM"]["pidMeasured"], captures["OFF-COLD"]["pidMeasured"])
        self.assertEqual(captures["ON-COLD"]["traceMode"], "on")
        self.assertEqual(captures["OFF-COLD"]["traceMode"], "off")
        self.assertFalse(any("metrics-export" in line for line in fake_off.logs))
        self.assertTrue(any("metrics-export" in line for line in fake_on.logs))
        self.assertFalse(any("interaction-confirmed" in line for line in fake_off.logs))
        self.assertTrue(any("interaction-confirmed" in line for line in fake_on.logs))

    def test_exact_report_dry_run_commands_parse(self):
        import contextlib
        import io
        commands = [
            ["start", "--serial", "mock-device", "--scenario", "S1",
             "--run-id", "review-off-warm", "--warm", "--no-interact",
             "--trace-off", "--dry-run"],
            ["start", "--serial", "mock-device", "--scenario", "S1",
             "--run-id", "review-off-cold", "--cold", "--trace-off",
             "--no-interact", "--dry-run"],
            ["start", "--serial", "mock-device", "--scenario", "S1",
             "--run-id", "review-on-warm", "--warm", "--dry-run"],
            ["start", "--serial", "mock-device", "--scenario", "S1",
             "--run-id", "review-on-cold", "--cold", "--dry-run"],
        ]
        original = module.adb
        module.adb = FakeAdb()
        self.addCleanup(setattr, module, "adb", original)
        import sys
        old_argv = sys.argv
        self.addCleanup(setattr, sys, "argv", old_argv)
        for command in commands:
            sys.argv = ["chat-history-entry-perf.py", command[0], "--path-id", "native-launcher-stress"] + command[1:]
            with contextlib.redirect_stdout(io.StringIO()) as output:
                self.assertEqual(module.main(), 0)
            self.assertTrue(json.loads(output.getvalue())["dryRun"])
            plan = [step["kind"] for step in json.loads(output.getvalue())["plan"]]
            if "--trace-off" in command:
                self.assertEqual(plan[-1], "atrace-pull")
                self.assertNotIn("metrics-export-wait", plan)
            else:
                self.assertEqual(plan[-1], "metrics-export-wait")

    def test_wrong_trace_mode_and_s7_off_fail_closed(self):
        with self.assertRaises(SystemExit):
            self.run_main(
                ["start", "--serial", "mock-device", "--scenario", "S1",
                 "--run-id", "bad", "--cold", "--trace-off", "--dry-run",
                 "--apk", self.ON_APK, "--aapt2", self.AAPT2],
                FakeAdb(),
            )
        with self.assertRaises(SystemExit):
            self.run_main(
                ["start", "--serial", "mock-device", "--scenario", "S7",
                 "--run-id", "bad-s7", "--cold", "--trace-off", "--dry-run"],
                FakeAdb(),
            )


class OffInteractionTest(unittest.TestCase):
    def test_trace_off_fixture_interaction_still_completes_without_export(self):
        fake = FakeAdb(trace_enabled=False)
        original = module.adb
        module.adb = fake
        self.addCleanup(setattr, module, "adb", original)
        import contextlib
        import io
        import sys
        old_argv = sys.argv
        self.addCleanup(setattr, sys, "argv", old_argv)
        with tempfile.TemporaryDirectory() as directory:
            capture = str(Path(directory) / "off.json")
            sys.argv = [
                "chat-history-entry-perf.py", "start", "--path-id", "native-launcher-stress", "--serial", "mock-device",
                "--scenario", "S1", "--run-id", "OFF-INTERACT", "--warm",
                "--trace-off", "--trace-seconds", "0", "--capture-log", capture,
                "--aapt2", "/mock/android-build-tools/aapt2",
            ]
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(module.main(), 0)
            payload = json.loads(Path(capture).read_text())
        self.assertEqual(payload["traceMode"], "off")
        self.assertEqual(payload["interaction"]["kind"], "swipe")
        self.assertTrue(any("interaction-confirmed runId=OFF-INTERACT" in line for line in fake.logs))
        self.assertFalse(any("metrics-export" in line for line in fake.logs))


class UiXmlLocatorTest(unittest.TestCase):
    def test_uppercase_real_xml_old_button_is_not_clickable(self):
        xml = (Path(__file__).parent / "fixtures" / "normal-launch-r3.xml").read_text()
        with self.assertRaises(SystemExit):
            module.parse_open_bounds(xml)
        # Same real hierarchy with the new stable content-desc, still disabled.
        stable_disabled = xml.replace(
            'content-desc="Open history S1"', 'content-desc="perf_open_history"'
        )
        with self.assertRaises(SystemExit):
            module.parse_open_bounds(stable_disabled)

    def test_stable_content_desc_ignores_uppercase_text_and_requires_enabled(self):
        xml = (
            '<hierarchy><node text="OPEN HISTORY" content-desc="perf_open_history" '
            'class="android.widget.Button" enabled="true" bounds="[100,200][300,400]" />'
            '</hierarchy>'
        )
        self.assertEqual((200, 300), module.parse_open_bounds(xml))
        disabled = xml.replace('enabled="true"', 'enabled="false"')
        with self.assertRaises(SystemExit):
            module.parse_open_bounds(disabled)
        missing = '<hierarchy><node text="OPEN HISTORY" enabled="true" /></hierarchy>'
        with self.assertRaises(SystemExit):
            module.parse_open_bounds(missing)
        duplicate = xml.replace(
            "</hierarchy>",
            '<node content-desc="perf_open_history" enabled="true" bounds="[1,1][2,2]" /></hierarchy>',
        )
        with self.assertRaises(SystemExit):
            module.parse_open_bounds(duplicate)

    def test_escaped_text_is_parsed_and_edit_requires_enabled_true(self):
        xml = (
            '<hierarchy><node text="S1 A &amp; B" class="android.widget.EditText" '
            'enabled="true" bounds="[0,0][20,20]" /></hierarchy>'
        )
        self.assertEqual((10, 10), module.parse_edit_bounds(xml))
        self.assertTrue(module.s7_ui_state(xml, "A & B")["textVisible"])
        disabled = xml.replace('enabled="true"', 'enabled="false"')
        self.assertIsNone(module.parse_edit_bounds(disabled))
        self.assertFalse(module.s7_ui_state(disabled)["editEnabled"])


class PrepareAndLocatorFlowTest(unittest.TestCase):
    def args(self, **overrides):
        values = dict(
            serial="mock-device", package=module.PACKAGE, path_id="native-launcher-stress", adb="adb", dry_run=False,
            scenario="S1", run_id="PREP", timeout=0.3,
        )
        values.update(overrides)
        return argparse.Namespace(**values)

    def test_prepare_requires_persisted_ack(self):
        fake = FakeAdb()
        fake.logs = ["prepared runId=PREP scenario=S1 status=ok persisted=true"]
        original = module.adb
        module.adb = fake
        self.addCleanup(setattr, module, "adb", original)
        self.assertEqual(module.prepare(self.args()), 0)
        fake.logs = ["prepared runId=PREP scenario=S1 status=ok persisted=false"]
        with self.assertRaises(SystemExit):
            module.prepare(self.args())

    def test_disabled_open_button_fails_before_atrace(self):
        fake = FakeAdb(open_enabled=False)
        original = module.adb
        module.adb = fake
        self.addCleanup(setattr, module, "adb", original)
        with self.assertRaises(SystemExit):
            module.start(argparse.Namespace(
                serial="mock-device", package=module.PACKAGE, path_id="native-launcher-stress", adb="adb", dry_run=False,
                scenario="S1", run_id="DISABLED", cold=False, warm=True,
                settle=0.0, trace_seconds=3.0, trace_output="/tmp/none.atrace",
                capture_log=None, interact=True, interaction_mode="auto",
                handshake_timeout=0.2, final_timeout=2.0, trace_mode="on", apk=None,
                aapt2="/mock/android-build-tools/aapt2",
                s7_timeout=1.0, external_readable_text=None, external_timeout=0.2,
            ))
        self.assertFalse(any(call[:3] == ("shell", "atrace") for call in fake.calls))


class DevicePreflightTest(unittest.TestCase):
    def test_real_unlocked_policy_and_power_capture_is_valid(self):
        root = Path(__file__).parent / "fixtures"
        policy = (root / "unlocked-policy.txt").read_text()
        power = (root / "unlocked-power.txt").read_text()
        env = module.parse_device_environment(policy, power)
        self.assertTrue(env["screenOn"])
        self.assertTrue(env["awake"])
        self.assertFalse(env["keyguard"])

    def test_real_policy_with_spaced_showing_true_is_locked(self):
        root = Path(__file__).parent / "fixtures"
        policy = (root / "unlocked-policy.txt").read_text()
        locked = policy.replace("showing=false", "showing=true")
        env = module.parse_device_environment(locked, "mWakefulness = Awake")
        self.assertTrue(env["screenOn"])
        self.assertTrue(env["keyguard"])
        spaced_off = module.parse_device_environment(
            "mScreenOn = false\nshowing = true", "mWakefulness = Asleep"
        )
        self.assertFalse(spaced_off["screenOn"])
        self.assertFalse(spaced_off["awake"])
        self.assertTrue(spaced_off["keyguard"])

    def test_hangup_is_not_treated_as_awake(self):
        env = module.parse_device_environment(
            "mScreenOn = true\nshowing = false", "mWakefulness = Hangup"
        )
        self.assertTrue(env["screenOn"])
        self.assertFalse(env["awake"])
        self.assertFalse(env["keyguard"])

    def test_locked_or_screen_off_is_environment_invalid(self):
        locked = module.parse_device_environment(
            "mScreenOn=false mShowingLockscreen=true", "mWakefulness=Asleep"
        )
        self.assertFalse(locked["screenOn"])
        self.assertFalse(locked["awake"])
        self.assertTrue(locked["keyguard"])
        awake_locked = module.parse_device_environment(
            "mScreenOnFully=true mShowingLockscreen=true", "mWakefulness=Awake"
        )
        self.assertTrue(awake_locked["screenOn"])
        self.assertTrue(awake_locked["keyguard"])
        unlocked = module.parse_device_environment(
            "mScreenOnFully=true mShowingLockscreen=false", "mWakefulness=Awake"
        )
        self.assertTrue(unlocked["screenOn"])
        self.assertFalse(unlocked["keyguard"])

    def test_start_fails_before_force_stop_when_locked(self):
        fake = FakeAdb()
        # Replace the dumpsys handlers with a locked-screen response.
        original_call = fake.__class__.__call__

        def locked_call(self, serial, *args, check=True):
            if args[:4] == ("shell", "dumpsys", "window", "policy"):
                import subprocess
                return subprocess.CompletedProcess(args=list(args), returncode=0,
                                                   stdout="mScreenOn=false mShowingLockscreen=true",
                                                   stderr="")
            if args[:3] == ("shell", "dumpsys", "power"):
                import subprocess
                return subprocess.CompletedProcess(args=list(args), returncode=0,
                                                   stdout="mWakefulness=Asleep", stderr="")
            return original_call(self, serial, *args, check=check)

        fake.__class__.__call__ = locked_call
        self.addCleanup(setattr, fake.__class__, "__call__", original_call)
        original = module.adb
        module.adb = fake
        self.addCleanup(setattr, module, "adb", original)
        with self.assertRaises(SystemExit):
            module.start(start_args(cold=True, dry_run=False, trace_seconds=0.0))
        self.assertFalse(any(call[:3] == ("shell", "am", "force-stop") for call in fake.calls))


class FinalMetricsValidationTest(unittest.TestCase):
    def payload(self):
        return {
            "runId": "R1", "scenario": "S1", "package": module.PACKAGE, "pathId": "native-launcher-stress",
            "traceEnabled": True, "status": "final", "revision": 3,
            "targetKind": "MESSAGE",
            "interaction": {
                "requestedAtMs": 5000.0, "confirmedAtMs": 5012.0,
                "latencyMs": 12.0, "kind": "swipe", "source": "lazy-scroll",
            },
        }

    def test_final_payload_is_accepted(self):
        interaction = module.validate_metrics_payload(
            self.payload(), "R1", "S1", module.PACKAGE
        )
        self.assertEqual(12.0, interaction["latencyMs"])

    def test_snapshot_and_missing_interaction_are_rejected(self):
        snapshot = self.payload()
        snapshot["status"] = "snapshot"
        with self.assertRaises(SystemExit):
            module.validate_metrics_payload(snapshot, "R1", "S1", module.PACKAGE)
        missing = self.payload()
        del missing["interaction"]
        with self.assertRaises(SystemExit):
            module.validate_metrics_payload(missing, "R1", "S1", module.PACKAGE)
        bad_revision = self.payload()
        bad_revision["revision"] = 0
        with self.assertRaises(SystemExit):
            module.validate_metrics_payload(bad_revision, "R1", "S1", module.PACKAGE)
