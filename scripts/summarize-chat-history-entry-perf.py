#!/usr/bin/env python3
"""Fail-closed Phase-2 history-entry summary.

Each run is a directory containing:
  run-manifest.json  (identity, capture conditions, frame window)
  metrics-*.json     (ChatPerfTrace output, required only when traceEnabled)

No fast/best sample selection. Invalid samples are retained in the output with
their reasons and make the command exit non-zero.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from chat_perf_contract import PATHS, PARSER_VERSION
from statistics import median

REQUIRED_PHASES = [
    "EntryRequested",
    "DataReady",
    "InitialPreparationReady",
    "FirstTimelineLayout",
    "FirstContentDraw",
    "FirstReadableDraw",
    "FirstInteractive",
]
SCENARIOS = [f"S{i}" for i in range(9)]
SHA_RE = re.compile(r"^[0-9a-f]{64}$")
WINDOW_MS = 1500.0


class RunError(Exception):
    pass


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def finite(value, name: str, *, allow_zero: bool = True) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise RunError(f"{name} is not numeric")
    number = float(value)
    if not math.isfinite(number):
        raise RunError(f"{name} is not finite")
    if number < 0 or (not allow_zero and number == 0):
        raise RunError(f"{name} is negative/zero")
    return number


def require_string(value, name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise RunError(f"missing {name}")
    return value.strip()


def require_sha(value, name: str) -> str:
    text = require_string(value, name)
    if not SHA_RE.fullmatch(text):
        raise RunError(f"invalid {name}")
    return text


def load_one(run_dir: Path) -> dict:
    manifest_path = run_dir / "run-manifest.json"
    if not manifest_path.is_file():
        raise RunError("missing run-manifest.json")
    try:
        manifest = json.loads(manifest_path.read_text())
    except Exception as error:
        raise RunError(f"unreadable run-manifest: {error}")
    if manifest.get("schema") != 1:
        raise RunError("unsupported run-manifest schema")

    run_id = require_string(manifest.get("runId"), "runId")
    scenario = require_string(manifest.get("scenario"), "scenario")
    if scenario not in SCENARIOS:
        raise RunError(f"unsupported scenario {scenario}")
    phase = require_string(manifest.get("phase"), "phase")
    if phase not in ("cold", "warm"):
        raise RunError(f"invalid phase {phase}")
    package = require_string(manifest.get("package"), "package")
    apk_sha = require_sha(manifest.get("apkSha256"), "apkSha256")
    source_manifest_sha = require_sha(manifest.get("sourceManifestSha256"), "sourceManifestSha256")
    source_diff_sha = require_sha(manifest.get("sourceDiffSha256"), "sourceDiffSha256")
    untracked_digest = require_sha(manifest.get("untrackedDigest"), "untrackedDigest")
    fixture_version = require_string(manifest.get("fixtureVersion"), "fixtureVersion")
    trace_enabled = manifest.get("traceEnabled")
    if not isinstance(trace_enabled, bool):
        raise RunError("missing boolean traceEnabled")

    path = manifest.get("pathId")
    if path not in PATHS:
        raise RunError("missing/unsupported pathId; legacy data is not relabelled")
    if manifest.get("parserVersion") != PARSER_VERSION:
        raise RunError("parserVersion mismatch; legacy frame semantics cannot be mixed")
    if (manifest.get("environmentPre") or {}).get("gate", {}).get("status") == "pause":
        raise RunError("pre-measurement environment gate paused this sample")
    device = manifest.get("device")
    if not isinstance(device, dict):
        raise RunError("missing device")
    for key in ("serial", "model", "sdk"):
        require_string(device.get(key), f"device.{key}")
    build = manifest.get("build")
    if not isinstance(build, dict):
        raise RunError("missing build")
    for key in ("variant", "debuggable", "profileable", "chatPerfTrace"):
        if key not in build:
            raise RunError(f"missing build.{key}")
    if not isinstance(build["debuggable"], bool) or not isinstance(build["profileable"], bool):
        raise RunError("build debuggable/profileable must be boolean")

    frames = manifest.get("frames")
    if not isinstance(frames, dict):
        raise RunError("missing frames")
    deadline_status = frames.get("deadlineStatus")
    deadline_evidence = frames.get("deadlineEvidence")
    if deadline_status == "unknown" and frames.get("deadlineMs") is None:
        deadline = None
    elif deadline_status == "evidenced" and isinstance(deadline_evidence, dict):
        require_string(deadline_evidence.get("sha256"), "frames.deadlineEvidence.sha256")
        require_string(deadline_evidence.get("displayId"), "frames.deadlineEvidence.displayId")
        deadline = finite(frames.get("deadlineMs"), "frames.deadlineMs", allow_zero=False)
    else:
        raise RunError("deadline must be explicitly unknown or evidenced for the target display")
    window = finite(frames.get("windowMs"), "frames.windowMs", allow_zero=False)
    if window != WINDOW_MS:
        raise RunError(f"frames.windowMs must be {WINDOW_MS:g}")
    source = require_string(frames.get("source"), "frames.source")
    values = frames.get("valuesMs")
    if not isinstance(values, list) or not values:
        raise RunError("frames.valuesMs must be non-empty")
    frames_ms = [finite(value, "frames.valuesMs[]") for value in values]
    pid = frames.get("pid")
    if pid is not None and (isinstance(pid, bool) or not isinstance(pid, int) or pid <= 0):
        raise RunError("frames.pid must be a positive integer when present")
    external_timing = manifest.get("externalTiming")
    if not trace_enabled and not isinstance(external_timing, dict):
        raise RunError("trace-off run requires externalTiming")
    if not trace_enabled:
        finite(external_timing.get("firstReadableMs"), "externalTiming.firstReadableMs")

    metrics_files = sorted(run_dir.glob("metrics-*.json"))
    if trace_enabled:
        if len(metrics_files) != 1:
            raise RunError(f"expected exactly one metrics file, found {len(metrics_files)}")
        try:
            metrics = json.loads(metrics_files[0].read_text())
        except Exception as error:
            raise RunError(f"unreadable metrics: {error}")
        for key, expected in (
            ("runId", run_id),
            ("scenario", scenario),
            ("package", package),
            ("traceEnabled", trace_enabled),
        ):
            if metrics.get(key) != expected:
                raise RunError(f"metrics {key} mismatch")
        phases = metrics.get("phasesMs")
        if not isinstance(phases, dict):
            raise RunError("missing phasesMs")
        values_by_phase = {}
        for name in REQUIRED_PHASES:
            if name not in phases:
                raise RunError(f"missing phase {name}")
            values_by_phase[name] = finite(phases[name], f"phase {name}")
        # Real partial order: data/prep/layout are concurrent for the first
        # readable draw; only layout->content->readable is strictly ordered.
        entry = values_by_phase["EntryRequested"]
        for name, value in values_by_phase.items():
            if value < entry:
                raise RunError(f"phase {name} precedes EntryRequested")
        readable = values_by_phase["FirstReadableDraw"]
        for name in ("DataReady", "InitialPreparationReady", "FirstTimelineLayout"):
            if values_by_phase[name] > readable:
                raise RunError(f"phase {name} follows FirstReadableDraw")
        if values_by_phase["FirstTimelineLayout"] > values_by_phase["FirstContentDraw"]:
            raise RunError("FirstContentDraw precedes FirstTimelineLayout")
        if values_by_phase["FirstContentDraw"] > readable:
            raise RunError("FirstReadableDraw precedes FirstContentDraw")

        if metrics.get("status") != "final":
            raise RunError(f"metrics are not final: {metrics.get('status')!r}")
        if not isinstance(metrics.get("revision"), int) or metrics["revision"] <= 0:
            raise RunError("metrics revision is missing/invalid")
        expected_target = "EMPTY_HERO" if scenario == "S0" else "MESSAGE"
        if metrics.get("targetKind") != expected_target:
            raise RunError(
                f"targetKind {metrics.get('targetKind')!r} != {expected_target}"
            )
        interaction = metrics.get("interaction")
        if not isinstance(interaction, dict):
            raise RunError("missing scripted interaction request/response")
        requested = finite(interaction.get("requestedAtMs"), "interaction.requestedAtMs")
        confirmed = finite(interaction.get("confirmedAtMs"), "interaction.confirmedAtMs")
        latency = finite(interaction.get("latencyMs"), "interaction.latencyMs")
        if confirmed < requested:
            raise RunError("interaction response precedes request")
        if abs(latency - (confirmed - requested)) > 0.01:
            raise RunError("interaction latency is inconsistent")
        if abs(confirmed - values_by_phase["FirstInteractive"]) > 0.5:
            raise RunError("FirstInteractive is not the confirmed response time")
        require_string(interaction.get("kind"), "interaction.kind")
        require_string(interaction.get("source"), "interaction.source")
        if metrics.get("pathId") != path:
            raise RunError("metrics pathId mismatch")
        metrics_payload = metrics
    else:
        if metrics_files:
            raise RunError("trace-off run must not contain marker metrics")
        values_by_phase = None
        metrics_payload = None

    return {
        "environmentPreGate": (manifest.get("environmentPre") or {}).get("gate", {"status": "unknown"}),
        "environmentPostGate": (manifest.get("environmentPost") or {}).get("gate", {"status": "unknown"}),
        "pathId": path,
        "parserVersion": PARSER_VERSION,
        "runId": run_id,
        "scenario": scenario,
        "phase": phase,
        "package": package,
        "apkSha256": apk_sha,
        "sourceManifestSha256": source_manifest_sha,
        "sourceDiffSha256": source_diff_sha,
        "untrackedDigest": untracked_digest,
        "fixtureVersion": fixture_version,
        "traceEnabled": trace_enabled,
        "device": {key: device[key] for key in ("serial", "model", "sdk")},
        "build": build,
        "framesSource": source,
        "framesMs": frames_ms,
        "deadlineMs": deadline,
        "deadlineStatus": deadline_status,
        "displayId": deadline_evidence.get("displayId") if deadline_evidence else None,
        "windowMs": window,
        "externalReadableMs": (
            finite(external_timing.get("firstReadableMs"), "externalTiming.firstReadableMs")
            if not trace_enabled else None
        ),
        "phasesMs": values_by_phase,
        "metrics": metrics_payload,
        "_dir": str(run_dir),
    }


def load_runs(
    root: Path,
    scenario_filter: str | None = None,
    phase_filter: str | None = None,
) -> tuple[list[dict], list[dict]]:
    valid: list[dict] = []
    invalid: list[dict] = []
    manifests = sorted(root.rglob("run-manifest.json"))
    manifest_dirs = {path.parent.resolve() for path in manifests}
    for path in manifests:
        run_dir = path.parent
        if scenario_filter or phase_filter:
            # Filter before validation so another scenario/phase in the same
            # tree is not reported as an invalid sample of this condition.
            try:
                peek = json.loads(path.read_text())
            except Exception:
                peek = {}
            if scenario_filter and peek.get("scenario") != scenario_filter:
                continue
            if phase_filter and peek.get("phase") != phase_filter:
                continue
        try:
            valid.append(load_one(run_dir))
        except RunError as error:
            invalid.append({"dir": str(run_dir), "reason": str(error)})
    for metrics in sorted(root.rglob("metrics-*.json")):
        if metrics.parent.resolve() not in manifest_dirs:
            invalid.append({"dir": str(metrics.parent), "reason": "orphan metrics without run-manifest"})
    if not manifests and not invalid:
        invalid.append({"dir": str(root), "reason": "no run-manifest.json found"})
    return valid, invalid


def percentile(values: list[float], p: float) -> float:
    ordered = sorted(values)
    if not ordered:
        raise RunError("empty percentile")
    index = min(len(ordered) - 1, max(0, int(round((len(ordered) - 1) * p))))
    return ordered[index]


def require_identity(runs: list[dict], expected_apk: str | None, expected_source: Path | None) -> None:
    if not runs:
        raise RunError("no valid runs")
    fixed_keys = (
        "pathId", "parserVersion", "scenario", "phase", "package", "apkSha256", "sourceManifestSha256",
        "sourceDiffSha256", "untrackedDigest", "fixtureVersion", "traceEnabled",
        "deadlineMs", "deadlineStatus", "displayId",
    )
    for key in fixed_keys:
        values = {run[key] for run in runs}
        if len(values) != 1:
            raise RunError(f"mixed {key}: {sorted(map(str, values))}")
    for key in ("serial", "model", "sdk"):
        values = {run["device"][key] for run in runs}
        if len(values) != 1:
            raise RunError(f"mixed device.{key}")
    for key in ("variant", "debuggable", "profileable", "chatPerfTrace"):
        values = {run["build"][key] for run in runs}
        if len(values) != 1:
            raise RunError(f"mixed build.{key}")
    ids = [run["runId"] for run in runs]
    if len(ids) != len(set(ids)):
        raise RunError("duplicate runId")
    if expected_apk:
        if not SHA_RE.fullmatch(expected_apk):
            raise RunError("invalid --apk-sha256")
        if any(run["apkSha256"] != expected_apk for run in runs):
            raise RunError("APK hash does not match every run manifest")
    if expected_source:
        if not expected_source.is_file():
            raise RunError(f"source manifest missing: {expected_source}")
        expected = sha256(expected_source)
        if any(run["sourceManifestSha256"] != expected for run in runs):
            raise RunError("source manifest hash mismatch")


def summarize(
    root: Path,
    min_samples: int,
    expected_apk: str | None,
    expected_source: Path | None,
    *,
    scenario_filter: str | None = None,
    phase_filter: str | None = None,
) -> dict:
    runs, invalid = load_runs(root, scenario_filter, phase_filter)
    result: dict = {
        "schema": 1,
        "status": "invalid" if invalid else "ok",
        "invalidRuns": invalid,
    }
    if invalid:
        result["reason"] = "invalid run(s): " + "; ".join(
            f"{item['dir']}: {item['reason']}" for item in invalid
        )
    try:
        require_identity(runs, expected_apk, expected_source)
        if len(runs) < min_samples:
            raise RunError(f"need {min_samples} valid runs, found {len(runs)}")
    except RunError as error:
        result["status"] = "invalid"
        if result["invalidRuns"]:
            result["reason"] += f"; identity/sample: {error}"
        else:
            result["reason"] = str(error)
        return result

    readable = [
        run["phasesMs"]["FirstReadableDraw"] if run["traceEnabled"] else run["externalReadableMs"]
        for run in runs
    ]
    all_frames = [frame for run in runs for frame in run["framesMs"]]
    deadline = runs[0]["deadlineMs"]
    result.update(
        {
            "environment": {"formalComparisonQualified": False, "perRun": [{"runId": r["runId"], "pre": r["environmentPreGate"], "post": r["environmentPostGate"]} for r in runs]},
            "pathId": runs[0]["pathId"],
            "parserVersion": PARSER_VERSION,
            "scenario": runs[0]["scenario"],
            "phase": runs[0]["phase"],
            "package": runs[0]["package"],
            "runs": len(runs),
            "runIds": [run["runId"] for run in runs],
            "apkSha256": runs[0]["apkSha256"],
            "sourceManifestSha256": runs[0]["sourceManifestSha256"],
            "sourceDiffSha256": runs[0]["sourceDiffSha256"],
            "fixtureVersion": runs[0]["fixtureVersion"],
            "traceEnabled": runs[0]["traceEnabled"],
            "firstReadableMs": {
                "source": "marker" if runs[0]["traceEnabled"] else "external",
                "raw": readable,
                "p50": percentile(readable, 0.50),
                "p90": percentile(readable, 0.90),
                "median": median(readable),
            },
            "frames": {
                "windowMs": runs[0]["windowMs"],
                "deadlineMs": deadline,
                "rawMs": all_frames,
                "p50": percentile(all_frames, 0.50),
                "p90": percentile(all_frames, 0.90),
                "p95": percentile(all_frames, 0.95),
                "percentileMethod": "nearest-rounded-index",
                "runMaxMs": [max(r["framesMs"]) for r in runs],
                "runP95Ms": [percentile(r["framesMs"], 0.95) for r in runs],
                "max": max(all_frames),
                "deadlineStatus": runs[0]["deadlineStatus"],
                **({"overBudget": sum(1 for frame in all_frames if frame > deadline)}
                   if deadline is not None else {}),
            },
            "rawRuns": [
                {
                    "runId": run["runId"],
                    "dir": run["_dir"],
                    "phasesMs": run["phasesMs"],
                    "framesMs": run["framesMs"],
                    "externalReadableMs": run["externalReadableMs"],
                }
                for run in runs
            ],
        }
    )
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--path-id", choices=PATHS, required=True)
    parser.add_argument("--runs", required=True, help="directory containing run subdirectories")
    parser.add_argument("--min-samples", type=int, default=3)
    parser.add_argument("--apk-sha256")
    parser.add_argument("--source-manifest")
    parser.add_argument("--scenario", choices=SCENARIOS)
    parser.add_argument("--phase", choices=["cold", "warm"])
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    if args.min_samples < 1:
        raise SystemExit("--min-samples must be positive")
    result = summarize(
        Path(args.runs),
        args.min_samples,
        args.apk_sha256,
        Path(args.source_manifest) if args.source_manifest else None,
        scenario_filter=args.scenario,
        phase_filter=args.phase,
    )
    if result.get("status") == "ok" and result.get("pathId") != args.path_id:
        result.update(status="invalid", reason="requested pathId mismatch")
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2, ensure_ascii=False))
    print(json.dumps(result, indent=2, ensure_ascii=False))
    return 0 if result["status"] == "ok" else 2


if __name__ == "__main__":
    sys.exit(main())
