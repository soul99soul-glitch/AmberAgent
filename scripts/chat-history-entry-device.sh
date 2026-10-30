#!/bin/bash
# Explicit device owner only. Never run alongside another device task.
set -euo pipefail
case "${1:-help}" in
  help|-h|--help)
    printf '%s\n' 'Explicit device owner only: install on|off; single S1 on; single-cold S1 on; single-warm S1 on; pilot on; overhead batch1|batch2' \
      'Required environment: SERIAL EVIDENCE_ROOT ON_APK OFF_APK TEST_APK SOURCE_MANIFEST; optional ADB AAPT2 APKSIGNER FIXTURE_VERSION'
    exit 0 ;;
esac
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"
ADB_STR="${ADB:-adb}"
SERIAL="${SERIAL:?Set SERIAL to the authorized connected fixture device}"
ROOT="${EVIDENCE_ROOT:?Set EVIDENCE_ROOT to a local evidence directory}"
ON="${ON_APK:?Set ON_APK to the current trace-on fixture APK}"
OFF="${OFF_APK:?Set OFF_APK to the matching trace-off fixture APK}"
TEST="${TEST_APK:?Set TEST_APK to the matching fixture test APK}"
SOURCE_MANIFEST="${SOURCE_MANIFEST:?Set SOURCE_MANIFEST to the current build source manifest}"
AAPT2="${AAPT2:-$(command -v aapt2 || true)}"
APKSIGNER="${APKSIGNER:-$(command -v apksigner || true)}"
FIXTURE_VERSION="${FIXTURE_VERSION:-chat-perf-home-a2-20261028}"
# Pass the adb command as argv, including quoted tool paths; never eval it.
adb_device() {
  python3 -c 'import shlex,subprocess,sys; sys.exit(subprocess.call([*shlex.split(sys.argv[1]), "-s", sys.argv[2], *sys.argv[3:]]))' "$ADB_STR" "$SERIAL" "$@"
}
validate_install_apk() {
  python3 - "$1" "$2" "$AAPT2" <<'PY_CHECK_APK'
import re, subprocess, sys
result = subprocess.run([sys.argv[3], "dump", "badging", sys.argv[1]], text=True, capture_output=True)
match = re.search(r"^package: name='([^']+)'", result.stdout, re.M)
if result.returncode or not match or match.group(1) != sys.argv[2]:
    raise SystemExit("refusing to install APK outside the expected fixture package: " + sys.argv[1])
PY_CHECK_APK
}
validate_mode() {
  case "$1" in on|off) ;; *) printf 'Invalid trace mode: %s\n' "$1" >&2; return 2 ;; esac
}
PREFIX="history-entry-$(date +%Y%m%d-%H%M%S)-$$"
OUT="$ROOT/runs/$PREFIX"
mkdir -p "$OUT"

sample() {
  local scenario="$1" phase="$2" mode="$3" apk="$4"
  local runid="$PREFIX-$scenario-$phase-$mode"
  local raw="$OUT/$runid-raw"
  mkdir -p "$raw"
  # Same post-window semantic poll in BOTH modes; only OFF uses it as its external upper bound.
  local readable_text="$scenario"
  if [[ "$scenario" == S3 ]]; then readable_text=S2; fi # S2/S3 intentionally share the same tail.
  local extra=(--external-readable-text "$readable_text")
  python3 scripts/chat-history-entry-perf.py start --path-id home-first-first-history \
    --serial "$SERIAL" --scenario "$scenario" --run-id "$runid" "--$phase" "--trace-$mode" \
    --apk "$apk" --aapt2 "$AAPT2" --adb "$ADB_STR" --trace-output "$raw/trace.atrace" \
    --capture-log "$raw/capture.json" ${extra[@]+"${extra[@]}"}
  local pid anchor
  read -r pid anchor < <(python3 - "$raw/capture.json" <<'PY'
import json,sys
c=json.load(open(sys.argv[1])); print(c['pidMeasured'],c['entryMonoNs'])
PY
  )
  local bind=(--run-id "$runid")
  if [[ "$mode" == off ]]; then bind=(--external-run-id "$runid"); fi
  python3 scripts/parse-atrace-frames.py --path-id home-first-first-history --trace "$raw/trace.atrace" \
    --pid "$pid" --anchor-monotonic-ns "$anchor" "${bind[@]}" --deadline-unknown --window-ms 1500 --output "$raw/frames.json"
  local external=()
  if [[ "$mode" == off ]]; then
    local readable
    readable=$(python3 - "$raw/capture.json" <<'PY'
import json,sys
print(json.load(open(sys.argv[1]))['externalReadableUpperBoundMs'])
PY
    )
    external=(--external-readable-ms "$readable" --external-source uiautomator-semantic-visible-upper-bound)
  fi
  python3 scripts/chat-history-entry-perf.py collect --path-id home-first-first-history \
    --serial "$SERIAL" --scenario "$scenario" --run-id "$runid" --phase "$phase" "--trace-$mode" \
    --apk "$apk" --adb "$ADB_STR" --aapt2 "$AAPT2" --apksigner "$APKSIGNER" \
    --source-manifest "$SOURCE_MANIFEST" --fixture-version "$FIXTURE_VERSION" \
    --frames-json "$raw/frames.json" --capture-log "$raw/capture.json" --output "$OUT/$runid" ${external[@]+"${external[@]}"}
  python3 scripts/summarize-chat-history-entry-perf.py --path-id home-first-first-history --runs "$OUT/$runid" \
    --min-samples 1 --source-manifest "$SOURCE_MANIFEST" --scenario "$scenario" --phase "$phase" --output "$raw/single-summary.json"
}
prepare() {
  local scenario="$1" mode="$2"
  python3 scripts/chat-history-entry-perf.py prepare --path-id home-first-first-history --serial "$SERIAL" \
    --scenario "$scenario" --run-id "$PREFIX-$scenario-prep-$mode" --adb "$ADB_STR" --capture-log "$OUT/$PREFIX-$scenario-prep-$mode.json"
}
install_mode() {
  validate_mode "$1"
  local mode="$1" apk="$ON"
  if [[ "$mode" == off ]]; then apk="$OFF"; fi
  validate_install_apk "$apk" app.amber.agent.perffixture
  adb_device install -r "$apk"
  # Same install+wait rhythm for EVERY ABBA slot, including repeated same letter.
  sleep 3
}
doctor_mode() {
  validate_mode "$1"
  local mode="$1" apk="$ON"
  if [[ "$mode" == off ]]; then apk="$OFF"; fi
  python3 scripts/chat-history-entry-perf.py doctor --serial "$SERIAL" --apk "$apk" --test-apk "$TEST" \
    --aapt2 "$AAPT2" --apksigner "$APKSIGNER" --adb "$ADB_STR" > "$OUT/doctor-$PREFIX-$mode.json"
}
pair() {
  validate_mode "$2"
  local scenario="$1" mode="$2" apk="$ON"
  if [[ "$mode" == off ]]; then apk="$OFF"; fi
  prepare "$scenario" "$mode"
  sample "$scenario" cold "$mode" "$apk"
  sample "$scenario" warm "$mode" "$apk"
}
case "${1:-help}" in
  install) validate_mode "${2:?on|off}"; validate_install_apk "$TEST" app.amber.agent.perffixture.test; install_mode "${2:?on|off}"; adb_device install -r "$TEST" ;;
  single-cold) mode="${3:-on}"; doctor_mode "$mode"; prepare "${2:-S1}" "$mode"; apk="$ON"; if [[ "$mode" == off ]]; then apk="$OFF"; fi; sample "${2:-S1}" cold "$mode" "$apk" ;;
  single-warm) mode="${3:-on}"; doctor_mode "$mode"; apk="$ON"; if [[ "$mode" == off ]]; then apk="$OFF"; fi; sample "${2:-S1}" warm "$mode" "$apk" ;;
  single) mode="${3:-on}"; doctor_mode "$mode"; pair "${2:-S1}" "$mode" ;;
  pilot)
    mode="${2:-on}"; doctor_mode "$mode"
    base="$PREFIX"
    for scenario in S1 S2 S3; do
      for i in 1 2 3; do PREFIX="$base-i$i"; pair "$scenario" "$mode"; done
    done ;;
  overhead)
    batch="${2:?batch1|batch2}"; modes=(on off off on)
    if [[ "$batch" == batch2 ]]; then modes=(off on on off); elif [[ "$batch" != batch1 ]]; then exit 2; fi
    base="$PREFIX"; slot=0
    for mode in "${modes[@]}"; do
      slot=$((slot+1)); PREFIX="$base-$batch-slot$slot"; install_mode "$mode"; doctor_mode "$mode"; pair S1 "$mode"
    done ;;
  *) printf '%s\n' 'Primary only: install on|off; single S1 on; pilot on; overhead batch1; overhead batch2'; exit 2 ;;
esac
printf 'Evidence directory: %s\n' "$OUT"
