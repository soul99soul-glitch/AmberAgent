import importlib.util
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).resolve().parents[1] / "parse-atrace-frames.py"
spec = importlib.util.spec_from_file_location("parse_atrace", MODULE_PATH)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def line(comm, tid, tgid, ts, payload, flags="...."):
    tgid_part = f"({tgid:>5}) " if tgid is not None else ""
    return (
        f" {comm}-{tid} {tgid_part}[001] {flags} {ts:.6f}: "
        f"tracing_mark_write: {payload}"
    )


BASE = [
    line("app", 100, 100, 1000.000, "B|100|Choreographer#doFrame"),
    # Another thread in the same process interleaves; it must not touch the
    # main-thread stack.
    line("binder", 101, 100, 1000.004, "B|100|Binder:transaction"),
    line("binder", 101, 100, 1000.008, "E|100"),
    line("app", 100, 100, 1000.010, "B|100|AmberPerf.EntryRequested"),
    line("app", 100, 100, 1000.011, "E|100"),
    line("app", 100, 100, 1000.012, "B|100|AmberPerf.Run.r1"),
    line("app", 100, 100, 1000.013, "E|100"),
    line("app", 100, 100, 1000.140, "E|100"),
    line("app", 100, 100, 1000.150, "B|100|Choreographer#doFrame"),
    line("app", 100, 100, 1000.155, "E|100"),
    # Frame crossing the 1500ms window end.
    line("app", 100, 100, 1001.499, "B|100|Choreographer#doFrame"),
    line("app", 100, 100, 1001.510, "E"),
    # Different process, same-looking PID payload is not this process.
    line("other", 200, 200, 1000.120, "B|100|Choreographer#doFrame"),
    line("other", 200, 200, 1000.130, "E|100"),
]


class AtraceFrameTest(unittest.TestCase):
    def write(self, lines):
        import tempfile
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / "trace.atrace"
        path.write_text("\n".join(lines) + "\n")
        return path

    def test_straddling_frame_is_kept_with_raw_bounds_and_overlap(self):
        path = self.write(BASE)
        payload = module.extract(
            path, pid=100, start_ms=0.0, window_ms=1500.0,
            anchor_marker="AmberPerf.EntryRequested", run_id="r1",
        )
        self.assertEqual([140.0, 5.0, 11.0], payload["valuesMs"])
        first = payload["frames"][0]
        self.assertEqual(-10.0, first["beginMs"])
        self.assertEqual(130.0, first["overlapMs"])
        self.assertEqual(10.0, first["preEntryMs"])
        self.assertEqual(1, payload["preEntryFrameCount"])
        self.assertEqual(140.0, payload["maxDurationMs"])

    def test_external_monotonic_anchor_uses_same_window_without_a_marker(self):
        path = self.write(BASE)
        payload = module.extract(
            path, pid=100, start_ms=0.0, window_ms=1500.0,
            anchor_monotonic_ns=1000_010_000_000, run_id="r1",
        )
        self.assertEqual("external-monotonic", payload["anchorSource"])
        self.assertEqual([140.0, 5.0, 11.0], payload["valuesMs"])

    def test_tgid_absent_bare_e_and_interleaved_tids_are_supported(self):
        lines = [
            line("app", 100, None, 1000.000, "B|100|AmberPerf.EntryRequested"),
            line("app", 100, None, 1000.001, "E"),
            line("app", 100, None, 1000.010, "B|100|Choreographer#doFrame"),
            line("binder", 101, 100, 1000.011, "B|100|nested"),
            line("binder", 101, 100, 1000.012, "E|100"),
            line("app", 100, None, 1000.020, "E|100"),
        ]
        payload = module.extract(
            self.write(lines), 100, 0.0, 1500.0,
            anchor_marker="AmberPerf.EntryRequested",
        )
        self.assertEqual([10.0], payload["valuesMs"])

    def test_missing_or_duplicate_anchor_fails_closed(self):
        missing = self.write([line("app", 100, 100, 1000.0, "B|100|Choreographer#doFrame"),
                              line("app", 100, 100, 1000.01, "E|100")])
        with self.assertRaises(module.TraceError):
            module.extract(missing, 100, 0.0, 1500.0)
        duplicate = self.write(BASE + [
            line("app", 100, 100, 1000.020, "B|100|AmberPerf.EntryRequested"),
            line("app", 100, 100, 1000.021, "E|100"),
        ])
        with self.assertRaises(module.TraceError):
            module.extract(duplicate, 100, 0.0, 1500.0,
                           anchor_marker="AmberPerf.EntryRequested")

    def test_wrong_run_marker_fails_closed(self):
        path = self.write(BASE)
        with self.assertRaises(module.TraceError):
            module.extract(path, 100, 0.0, 1500.0,
                           anchor_marker="AmberPerf.EntryRequested", run_id="other")
        duplicate = self.write(BASE + [
            line("app", 100, 100, 1000.030, "B|100|AmberPerf.Run.r1"),
            line("app", 100, 100, 1000.031, "E|100"),
        ])
        with self.assertRaises(module.TraceError):
            module.extract(duplicate, 100, 0.0, 1500.0,
                           anchor_marker="AmberPerf.EntryRequested", run_id="r1")

    def test_unfinished_main_thread_frame_is_a_truncated_trace(self):
        lines = [
            line("app", 100, 100, 1000.0, "B|100|AmberPerf.EntryRequested"),
            line("app", 100, 100, 1000.001, "E|100"),
            line("app", 100, 100, 1000.010, "B|100|Choreographer#doFrame"),
        ]
        with self.assertRaises(module.TraceError):
            module.extract(self.write(lines), 100, 0.0, 1500.0,
                           anchor_marker="AmberPerf.EntryRequested")

    def test_wrong_pid_or_no_window_overlap_fails_closed(self):
        path = self.write(BASE)
        with self.assertRaises(module.TraceError):
            module.extract(path, 999, 0.0, 1500.0,
                           anchor_marker="AmberPerf.EntryRequested")
        with self.assertRaises(module.TraceError):
            module.extract(path, 100, 2000.0, 1500.0,
                           anchor_marker="AmberPerf.EntryRequested")


if __name__ == "__main__":
    unittest.main()
