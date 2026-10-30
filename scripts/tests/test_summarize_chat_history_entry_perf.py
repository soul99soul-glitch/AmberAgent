import importlib.util
import json
import math
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).resolve().parents[1] / "summarize-chat-history-entry-perf.py"
spec = importlib.util.spec_from_file_location("summarize", MODULE_PATH)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

PHASES = {
    "EntryRequested": 0.0,
    "DataReady": 2.0,
    "InitialPreparationReady": 5.0,
    "FirstTimelineLayout": 30.0,
    "FirstContentDraw": 31.0,
    "FirstReadableDraw": 40.0,
    "FirstInteractive": 50.0,
}


def manifest(run_id="r1", scenario="S1", phase="cold", apk="a" * 64, trace=True):
    return {
        "schema": 1,
        "pathId": "native-launcher-stress",
        "parserVersion": "outer-main-doframe-v2",
        "runId": run_id,
        "scenario": scenario,
        "phase": phase,
        "package": "app.amber.agent.perffixture",
        "apkSha256": apk,
        "sourceManifestSha256": "b" * 64,
        "sourceDiffSha256": "c" * 64,
        "untrackedDigest": "d" * 64,
        "fixtureVersion": "fixture-v1",
        "traceEnabled": trace,
        "device": {"serial": "mock-device", "model": "25128PNA1C", "sdk": "37"},
        "build": {
            "variant": "fullPerffixture",
            "debuggable": False,
            "profileable": True,
            "chatPerfTrace": trace,
        },
        "frames": {
            "source": "atrace-text:Choreographer#doFrame",
            "deadlineMs": 16.0,
            "deadlineStatus": "evidenced",
            "deadlineEvidence": {"sha256": "e" * 64, "displayId": "0"},
            "windowMs": 1500.0,
            "valuesMs": [10.0, 18.0, 12.0],
        },
        "externalTiming": ({"firstReadableMs": 45.0} if not trace else {}),
    }


def metrics(run_id="r1", scenario="S1", trace=True, phases=None):
    return {
        "schema": 1,
        "pathId": "native-launcher-stress",
        "parserVersion": "outer-main-doframe-v2",
        "runId": run_id,
        "scenario": scenario,
        "package": "app.amber.agent.perffixture",
        "traceEnabled": trace,
        "status": "final",
        "revision": 1,
        "targetKind": "EMPTY_HERO" if scenario == "S0" else "MESSAGE",
        "phasesMs": phases if phases is not None else dict(PHASES),
        "interaction": {
            "requestedAtMs": 42.0,
            "confirmedAtMs": 50.0,
            "latencyMs": 8.0,
            "kind": "swipe",
            "source": "lazy-scroll",
        },
    }


def write_run(root, name, run_manifest, metric=None):
    directory = root / name
    directory.mkdir()
    (directory / "run-manifest.json").write_text(json.dumps(run_manifest))
    if metric is not None:
        (directory / "metrics-a.json").write_text(json.dumps(metric))


class SummaryTest(unittest.TestCase):
    def test_valid_multi_run_summary_has_frame_and_readable_percentiles(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_run(root, "cold-1", manifest("r1"), metrics("r1"))
            write_run(root, "cold-2", manifest("r2"), metrics("r2"))
            write_run(root, "cold-3", manifest("r3"), metrics("r3"))
            result = module.summarize(root, 3, None, None)
            self.assertEqual(result["status"], "ok")
            self.assertEqual(result["runs"], 3)
            self.assertEqual(result["firstReadableMs"]["median"], 40.0)
            self.assertEqual(result["frames"]["p50"], 12.0)
            self.assertEqual(result["frames"]["overBudget"], 3)
            self.assertIn("rawRuns", result)

    def test_reported_same_run_without_identity_and_negative_phases_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bad_manifest = {
                "schema": 1,
        "pathId": "native-launcher-stress",
        "parserVersion": "outer-main-doframe-v2",
                "runId": "same-run",
                "scenario": "S1",
                "phase": "cold",
                "package": "app.amber.agent.perffixture",
                "device": {},
                "build": {},
                "frames": {},
            }
            bad_metrics = {
                "runId": "same-run",
                "scenario": "S1",
                "package": "app.amber.agent.perffixture",
                "phasesMs": {name: -1 for name in PHASES},
            }
            for index in range(3):
                write_run(root, f"run-{index}", bad_manifest, bad_metrics)
            result = module.summarize(root, 3, None, None)
            self.assertEqual(result["status"], "invalid")
            self.assertEqual(len(result["invalidRuns"]), 3)

    def test_duplicate_run_id_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_run(root, "a", manifest("same-run"), metrics("same-run"))
            write_run(root, "b", manifest("same-run"), metrics("same-run"))
            write_run(root, "c", manifest("same-run"), metrics("same-run"))
            result = module.summarize(root, 1, None, None)
            self.assertEqual(result["status"], "invalid")
            self.assertIn("duplicate runId", result["reason"])

    def test_missing_identity_trace_and_invalid_phases_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            broken = manifest("r1")
            del broken["apkSha256"]
            del broken["sourceManifestSha256"]
            del broken["traceEnabled"]
            write_run(root, "broken", broken, metrics("r1"))
            result = module.summarize(root, 1, None, None)
            self.assertEqual(result["status"], "invalid")

        for bad_value in (-1.0, float("nan"), float("inf")):
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                phases = dict(PHASES)
                phases["FirstReadableDraw"] = bad_value
                write_run(root, "bad-phase", manifest(), metrics(phases=phases))
                result = module.summarize(root, 1, None, None)
                self.assertEqual(result["status"], "invalid", bad_value)

    def test_duplicate_old_metrics_pull_and_orphan_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            run = root / "run"
            run.mkdir()
            (run / "run-manifest.json").write_text(json.dumps(manifest()))
            (run / "metrics-a.json").write_text(json.dumps(metrics()))
            (run / "metrics-b.json").write_text(json.dumps(metrics()))
            result = module.summarize(root, 1, None, None)
            self.assertEqual(result["status"], "invalid")
            self.assertIn("exactly one metrics", result["reason"])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "orphan").mkdir()
            (root / "orphan" / "metrics-a.json").write_text(json.dumps(metrics()))
            result = module.summarize(root, 1, None, None)
            self.assertEqual(result["status"], "invalid")
            self.assertTrue(any("orphan" in item["reason"] for item in result["invalidRuns"]))

    def test_mixed_trace_and_wrong_apk_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_run(root, "on", manifest("r1", trace=True), metrics("r1", trace=True))
            write_run(root, "off", manifest("r2", trace=False), None)
            result = module.summarize(root, 1, None, None)
            self.assertEqual(result["status"], "invalid")
            self.assertIn("mixed traceEnabled", result["reason"])

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_run(root, "one", manifest("r1", apk="e" * 64), metrics("r1"))
            result = module.summarize(root, 1, "f" * 64, None)
            self.assertEqual(result["status"], "invalid")
            self.assertIn("APK hash", result["reason"])

    def test_missing_frames_are_not_silently_replaced_by_plan_build(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            broken = manifest()
            broken["frames"] = {}
            write_run(root, "no-frames", broken, metrics())
            result = module.summarize(root, 1, None, None)
            self.assertEqual(result["status"], "invalid")

    def test_snapshot_status_is_not_accepted_as_final(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            metric = metrics()
            metric["status"] = "snapshot"
            write_run(root, "snapshot", manifest(), metric)
            result = module.summarize(root, 1, None, None)
            self.assertEqual(result["status"], "invalid")
            self.assertIn("not final", result["reason"])

    def test_missing_interaction_request_and_response_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            metric = metrics()
            del metric["interaction"]
            write_run(root, "no-interaction", manifest(), metric)
            result = module.summarize(root, 1, None, None)
            self.assertEqual(result["status"], "invalid")
            self.assertIn("interaction", result["reason"])

    def test_partial_order_allows_concurrent_data_prep_and_layout(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            phases = dict(PHASES)
            # Data Ready and preparation may finish after the target layout;
            # only layout -> content -> readable is strictly ordered.
            phases["DataReady"] = 32.0
            phases["InitialPreparationReady"] = 33.0
            write_run(root, "concurrent", manifest(), metrics(phases=phases))
            result = module.summarize(root, 1, None, None)
            self.assertEqual(result["status"], "ok")

    def test_scenario_and_phase_filters_select_one_condition(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_run(root, "s1-cold", manifest("r1", "S1", "cold"), metrics("r1", "S1"))
            write_run(root, "s2-cold", manifest("r2", "S2", "cold"), metrics("r2", "S2"))
            write_run(root, "s1-warm", manifest("r3", "S1", "warm"), metrics("r3", "S1"))
            result = module.summarize(
                root, 1, None, None, scenario_filter="S1", phase_filter="cold"
            )
            self.assertEqual(result["status"], "ok")
            self.assertEqual(result["runIds"], ["r1"])

    def test_target_kind_must_match_scenario(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            metric = metrics(scenario="S1")
            metric["targetKind"] = "EMPTY_HERO"
            write_run(root, "wrong-target", manifest(scenario="S1"), metric)
            result = module.summarize(root, 1, None, None)
            self.assertEqual(result["status"], "invalid")
            self.assertIn("targetKind", result["reason"])

    def test_trace_off_requires_external_timing_and_has_no_marker_metrics(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_run(root, "off", manifest("r1", trace=False), None)
            result = module.summarize(root, 1, None, None)
            self.assertEqual(result["status"], "ok")
            self.assertEqual(result["firstReadableMs"]["source"], "external")
            self.assertEqual(result["firstReadableMs"]["median"], 45.0)


if __name__ == "__main__":
    unittest.main()
