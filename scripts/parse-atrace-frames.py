#!/usr/bin/env python3
"""Extract doFrame durations from a text systrace/atrace file.

Contract:
  * T0 is an explicit anchor: an in-trace marker (trace-on) or the fixture's
    monotonic clock (both trace-on and trace-off). There is no "first random
    event" fallback.
  * A frame that straddles T0 or the window end is kept with its full raw
    begin/end/duration and its overlap. The main acceptance max frame therefore
    includes the frame containing the user's tap; pre-entry time is reported
    separately instead of silently dropping the frame.
  * Slice stacks are per TID. Only the main thread (TID == PID) is accepted,
    so interleaved binder/render threads cannot corrupt nesting.
  * A run marker `AmberPerf.Run.<runId>` binds the trace to one run; a missing,
    duplicate or mismatched run marker fails closed, as does a missing /
    duplicate anchor or an unfinished main-thread doFrame (truncated trace).
"""
from __future__ import annotations

import argparse
import json
import hashlib
import math
import re
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from chat_perf_contract import PARSER_VERSION, PATHS

LINE = re.compile(
    r"^\s*(?P<comm>.+?)-(?P<tid>\d+)\s+"
    r"(?:\(\s*(?P<tgid>\d+|----+)\s*\)\s+)?"
    r"\[\d+\]\s+\S+\s+(?P<ts>\d+\.\d+):\s+"
    r"tracing_mark_write:\s+(?P<kind>[BE])"
    r"(?:\|(?P<pid>-?\d+))?"
    r"(?:\|(?P<name>.*?))?\s*$"
)
RUN_PREFIX = "AmberPerf.Run."


class TraceError(Exception):
    pass


def _events(path: Path, pid: int):
    events = []
    for number, line in enumerate(path.read_text(errors="ignore").splitlines(), 1):
        match = LINE.match(line)
        if not match:
            continue
        tid = int(match.group("tid"))
        raw_tgid = match.group("tgid")
        tgid = int(raw_tgid) if raw_tgid and raw_tgid.isdigit() else None
        event_pid = int(match.group("pid")) if match.group("pid") else None
        if tid != pid:
            continue
        if tgid is not None and tgid != pid:
            continue
        if event_pid is not None and event_pid != pid:
            continue
        events.append(
            {
                "line": number,
                "tid": tid,
                "kind": match.group("kind"),
                "name": (match.group("name") or "").strip(),
                "ts": float(match.group("ts")),
            }
        )
    return events


def _single_marker(events, marker: str, label: str) -> float:
    hits = [
        event["ts"]
        for event in events
        if event["kind"] == "B" and event["name"] == marker
    ]
    if not hits:
        raise TraceError(f"{label} not found: {marker}")
    if len(hits) != 1:
        raise TraceError(f"{label} appeared {len(hits)} times: {marker}")
    return hits[0]


def extract(
    path: Path,
    pid: int,
    start_ms: float,
    window_ms: float,
    anchor_marker: str | None = None,
    anchor_monotonic_ns: int | None = None,
    run_id: str | None = None,
) -> dict:
    events = _events(path, pid)
    if not events:
        raise TraceError(f"no main-thread tracing_mark_write events for pid {pid}")

    if run_id:
        sanitized = re.sub(r"[^A-Za-z0-9_.-]", "_", run_id)[:80]
        _single_marker(events, RUN_PREFIX + sanitized, "run marker")

    marker_anchor = None
    if anchor_marker:
        marker_anchor = _single_marker(events, anchor_marker, "anchor marker")
    external_anchor = None
    if anchor_monotonic_ns is not None:
        if anchor_monotonic_ns <= 0:
            raise TraceError("anchor monotonic ns must be positive")
        external_anchor = anchor_monotonic_ns / 1_000_000_000.0
    if marker_anchor is not None and external_anchor is not None:
        if abs(marker_anchor - external_anchor) > 0.25:
            raise TraceError(
                "marker anchor and external monotonic anchor differ by more than 250ms"
            )
    anchor = external_anchor if external_anchor is not None else marker_anchor
    if anchor is None:
        raise TraceError("an explicit --anchor-marker or --anchor-monotonic-ns is required")

    window_start = anchor + start_ms / 1000.0
    window_end = window_start + window_ms / 1000.0
    stacks: dict[int, list[dict]] = {}
    frames = []
    for event in events:
        tid = event["tid"]
        stack = stacks.setdefault(tid, [])
        if event["kind"] == "B":
            stack.append(event)
            continue
        if not stack:
            # The trace may start in the middle of a slice; without a matching
            # B we cannot attribute it, so ignore this E.
            continue
        begin = stack.pop()
        if not begin["name"].startswith("Choreographer#doFrame"):
            continue
        frame_begin = begin["ts"]
        frame_end = event["ts"]
        if frame_end < frame_begin:
            raise TraceError("non-monotonic frame timestamps")
        if frame_end < window_start or frame_begin > window_end:
            continue
        overlap_ms = max(
            0.0,
            (min(frame_end, window_end) - max(frame_begin, window_start)) * 1000.0,
        )
        if overlap_ms <= 0.0:
            continue
        frames.append(
            {
                "beginMs": round((frame_begin - anchor) * 1000.0, 3),
                "endMs": round((frame_end - anchor) * 1000.0, 3),
                "durationMs": round((frame_end - frame_begin) * 1000.0, 3),
                "overlapMs": round(overlap_ms, 3),
                "preEntryMs": round(max(0.0, (window_start - frame_begin) * 1000.0), 3),
                "postWindowMs": round(max(0.0, (frame_end - window_end) * 1000.0), 3),
                "line": begin["line"],
                "section": begin["name"],
                "tid": tid,
            }
        )

    for tid, stack in stacks.items():
        for begin in stack:
            if (
                begin["name"].startswith("Choreographer#doFrame")
                and begin["ts"] <= window_end
            ):
                raise TraceError(
                    f"truncated trace: unfinished doFrame on TID {tid} at line {begin['line']}"
                )

    if not frames:
        raise TraceError("no Choreographer#doFrame frames overlap the requested window")

    frames.sort(key=lambda frame: (frame["beginMs"], -frame["endMs"], frame["line"]))
    raw_frames = [dict(frame) for frame in frames]
    outer = []
    nested = []
    for frame in frames:
        if outer and frame["beginMs"] < outer[-1]["endMs"]:
            if frame["endMs"] <= outer[-1]["endMs"]:
                nested.append({**frame, "outerLine": outer[-1]["line"]})
                continue
            raise TraceError("partially overlapping main-thread doFrame intervals")
        outer.append(frame)
    frames = outer
    return {
        "parserVersion": PARSER_VERSION,
        "rawFrames": raw_frames,
        "nestedFrames": nested,
        "schema": 1,
        "source": "atrace-text:Choreographer#doFrame",
        "runId": run_id,
        "pid": pid,
        "anchorSource": "external-monotonic" if external_anchor is not None else "marker",
        "anchorMarker": anchor_marker,
        "anchorMonotonicNs": anchor_monotonic_ns,
        "startMs": start_ms,
        "windowMs": window_ms,
        "frames": frames,
        "valuesMs": [frame["durationMs"] for frame in frames],
        "preEntryFrameCount": sum(1 for frame in frames if frame["preEntryMs"] > 0.0),
        "maxDurationMs": max(frame["durationMs"] for frame in frames),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--path-id", choices=PATHS, required=True)
    parser.add_argument("--trace", required=True)
    parser.add_argument("--pid", type=int, required=True)
    parser.add_argument("--start-ms", type=float, default=0.0)
    parser.add_argument("--window-ms", type=float, default=1500.0)
    deadlines = parser.add_mutually_exclusive_group(required=True)
    deadlines.add_argument("--refresh-hz", type=float)
    deadlines.add_argument("--deadline-ms", type=float)
    deadlines.add_argument("--deadline-unknown", action="store_true")
    parser.add_argument("--deadline-evidence", help="saved evidence for the TARGET app display/deadline")
    parser.add_argument("--display-id", help="target display ID, not an unrelated mirror display")
    binding = parser.add_mutually_exclusive_group()
    binding.add_argument("--run-id", help="requires matching in-trace run marker (trace-on)")
    binding.add_argument("--external-run-id", help="capture-bound ID, no in-trace marker (trace-off)")
    parser.add_argument("--anchor-marker")
    parser.add_argument("--anchor-monotonic-ns", type=int)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    if args.window_ms <= 0:
        raise SystemExit("window-ms must be positive")
    deadline = None
    evidence = None
    if not args.deadline_unknown:
        value = args.refresh_hz if args.refresh_hz is not None else args.deadline_ms
        if not math.isfinite(value) or value <= 0:
            raise SystemExit("refresh-hz/deadline-ms must be finite and positive")
        if not args.deadline_evidence or args.display_id is None:
            raise SystemExit("known deadline requires --deadline-evidence and --display-id for the target app")
        path = Path(args.deadline_evidence)
        if not path.is_file() or not path.stat().st_size:
            raise SystemExit("deadline evidence is missing/empty")
        evidence = {"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                    "displayId": args.display_id,
                    "basis": "explicit-refresh-hz" if args.refresh_hz is not None else "explicit-deadline-ms"}
        deadline = 1000.0 / args.refresh_hz if args.refresh_hz is not None else args.deadline_ms
    if args.external_run_id and args.anchor_monotonic_ns is None:
        raise SystemExit("external run binding requires --anchor-monotonic-ns from capture")
    try:
        payload = extract(
            Path(args.trace),
            args.pid,
            args.start_ms,
            args.window_ms,
            args.anchor_marker,
            args.anchor_monotonic_ns,
            args.run_id,
        )
    except TraceError as error:
        raise SystemExit(str(error))
    payload["parserSourceSha256"] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    payload["pathId"] = args.path_id
    payload["runId"] = args.run_id or args.external_run_id
    payload["runBinding"] = "marker" if args.run_id else "external" if args.external_run_id else "none"
    payload["deadlineMs"] = deadline
    payload["deadlineStatus"] = "unknown" if deadline is None else "evidenced"
    payload["deadlineEvidence"] = evidence
    Path(args.output).write_text(json.dumps(payload, indent=2))
    print(json.dumps({"frames": len(payload["frames"]), "output": args.output}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
