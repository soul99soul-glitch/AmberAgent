#!/usr/bin/env bash
set -euo pipefail

ADB="${ADB:-/Users/arquiel/Library/Android/sdk/platform-tools/adb}"
SERIAL="${SERIAL:-c9a8a837}"
PACKAGE="${PACKAGE:-app.amber.agent.notion}"
REPEATS="${REPEATS:-3}"
SWIPES_PER_RUN="${SWIPES_PER_RUN:-12}"
SWIPE_MS="${SWIPE_MS:-90}"
SWIPE_PAUSE_SEC="${SWIPE_PAUSE_SEC:-0.06}"
ARTIFACT_ROOT="${ARTIFACT_ROOT:-/tmp/amberagent-auto-jank-$(date +%Y%m%d-%H%M%S)}"
EXPECTED_TEXT="${EXPECTED_TEXT:-}"
HOTSPOT_TEXT="${HOTSPOT_TEXT:-}"
HOTSPOT_SEEK_SWIPES="${HOTSPOT_SEEK_SWIPES:-28}"
BROAD_TOP_PCT="${BROAD_TOP_PCT:-12}"
BROAD_BOTTOM_PCT="${BROAD_BOTTOM_PCT:-92}"
HOTSPOT_TOP_PCT="${HOTSPOT_TOP_PCT:-12}"
HOTSPOT_BOTTOM_PCT="${HOTSPOT_BOTTOM_PCT:-92}"

TARGET_JANK_PCT="${TARGET_JANK_PCT:-2.5}"
TARGET_LEGACY_JANK_PCT="${TARGET_LEGACY_JANK_PCT:-4.0}"
TARGET_P95_MS="${TARGET_P95_MS:-16}"
TARGET_P99_MS="${TARGET_P99_MS:-96}"
TARGET_SLOW_UI_PCT="${TARGET_SLOW_UI_PCT:-2.0}"
TARGET_HIST_GE_200="${TARGET_HIST_GE_200:-0}"

mkdir -p "$ARTIFACT_ROOT"

run_adb() {
  "$ADB" -s "$SERIAL" "$@"
}

foreground_app() {
  run_adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  run_adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
  run_adb shell cmd statusbar collapse >/dev/null 2>&1 || true
  if ! is_app_focused; then
    run_adb shell am start -n "$PACKAGE/app.amber.agent.RouteActivity" >/dev/null
  fi
  wait_for_foreground
}

is_app_focused() {
  local focus
  focus="$(run_adb shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | tr -d '\r' || true)"
  grep -Fq "$PACKAGE" <<<"$focus" && ! grep -Fq "NotificationShade" <<<"$focus"
}

wait_for_foreground() {
  local i focus
  for ((i = 0; i < 20; i++)); do
    focus="$(run_adb shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | tr -d '\r' || true)"
    if grep -Fq "$PACKAGE" <<<"$focus" && ! grep -Fq "NotificationShade" <<<"$focus"; then
      return 0
    fi
    run_adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
    run_adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
    run_adb shell cmd statusbar collapse >/dev/null 2>&1 || true
    sleep 0.25
  done
  echo "Unable to focus $PACKAGE" >&2
  echo "$focus" >&2
  return 1
}

screen_size() {
  run_adb shell wm size | tr -d '\r' | sed -nE 's/.*Physical size: ([0-9]+)x([0-9]+).*/\1 \2/p' | tail -n 1
}

parse_gfxinfo() {
  local file="$1"
  python3 - "$file" \
    "$TARGET_JANK_PCT" "$TARGET_LEGACY_JANK_PCT" "$TARGET_P95_MS" "$TARGET_P99_MS" "$TARGET_SLOW_UI_PCT" "$TARGET_HIST_GE_200" <<'PY'
import re
import sys

path, target_jank, target_legacy, target_p95, target_p99, target_slow_ui, target_ge_200 = sys.argv[1:]
target_jank = float(target_jank)
target_legacy = float(target_legacy)
target_p95 = float(target_p95)
target_p99 = float(target_p99)
target_slow_ui = float(target_slow_ui)
target_ge_200 = int(target_ge_200)
text = open(path, "r", encoding="utf-8", errors="replace").read()

def grab(pattern, default=None, cast=float):
    m = re.search(pattern, text)
    if not m:
        return default
    return cast(m.group(1))

total = grab(r"Total frames rendered:\s+(\d+)", 0, int)
janky_pct = grab(r"Janky frames:\s+\d+\s+\(([\d.]+)%\)", 100.0, float)
legacy_pct = grab(r"Janky frames \(legacy\):\s+\d+\s+\(([\d.]+)%\)", 100.0, float)
p95 = grab(r"95th percentile:\s+(\d+)ms", 9999, int)
p99 = grab(r"99th percentile:\s+(\d+)ms", 9999, int)
slow_ui = grab(r"Number Slow UI thread:\s+(\d+)", 9999, int)
hist_line = re.search(r"HISTOGRAM:\s+(.+)", text)
ge_200 = 0
if hist_line:
    for bucket, count in re.findall(r"(\d+)ms=(\d+)", hist_line.group(1)):
        if int(bucket) >= 200:
            ge_200 += int(count)
slow_ui_pct = (slow_ui * 100.0 / total) if total else 100.0
passed = (
    total > 60 and
    janky_pct <= target_jank and
    legacy_pct <= target_legacy and
    p95 <= target_p95 and
    p99 <= target_p99 and
    slow_ui_pct <= target_slow_ui and
    ge_200 <= target_ge_200
)
print(
    "PASS={passed}\ttotal={total}\tjanky={janky:.2f}\tlegacy={legacy:.2f}\t"
    "p95={p95}\tp99={p99}\tslow_ui={slow_ui}\tslow_ui_pct={slow:.2f}\tge_200={ge_200}".format(
        passed=str(passed).lower(),
        total=total,
        janky=janky_pct,
        legacy=legacy_pct,
        p95=p95,
        p99=p99,
        slow_ui=slow_ui,
        slow=slow_ui_pct,
        ge_200=ge_200,
    )
)
sys.exit(0 if passed else 1)
PY
}

run_one() {
  local run_id="$1"
  local dir="$ARTIFACT_ROOT/run-$run_id"
  mkdir -p "$dir"

  foreground_app
  run_adb shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp|mTopResumedActivity|mResumedActivity' > "$dir/window-before.txt" || true
  dump_ui "$dir/window-before.xml"
  assert_expected_text "$dir/window-before.xml" || return 2
  if [[ -n "$HOTSPOT_TEXT" ]]; then
    seek_hotspot_below_viewport "$dir" || return 2
  fi
  run_adb shell pidof "$PACKAGE" > "$dir/pid.txt" || true
  run_adb shell dumpsys package "$PACKAGE" | grep -E 'versionCode|versionName|DEBUGGABLE|profileable|isProfileable' > "$dir/package.txt" || true
  run_adb shell setprop log.tag.AmberChatPerf DEBUG >/dev/null 2>&1 || true
  run_adb logcat -c || true
  run_adb shell dumpsys gfxinfo "$PACKAGE" reset >/dev/null || true

  read -r width height <<<"$(screen_size)"
  local x=$((width / 2))
  local y_top=$((height * BROAD_TOP_PCT / 100))
  local y_bottom=$((height * BROAD_BOTTOM_PCT / 100))

  if [[ -n "$HOTSPOT_TEXT" ]]; then
    run_hotspot_swipes "$x" "$height"
  else
    run_broad_swipes "$x" "$y_top" "$y_bottom"
  fi
  sleep 0.6

  run_adb shell dumpsys gfxinfo "$PACKAGE" > "$dir/gfxinfo.txt"
  run_adb shell dumpsys gfxinfo "$PACKAGE" framestats > "$dir/gfxinfo-framestats.txt"
  run_adb logcat -d -v time > "$dir/logcat.txt" || true
  grep -F "AmberChatPerf" "$dir/logcat.txt" > "$dir/amber-chat-perf.log" || true
  run_adb shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp|mTopResumedActivity|mResumedActivity' > "$dir/window-after.txt" || true
  dump_ui "$dir/window-after.xml"
  run_adb shell setprop log.tag.AmberChatPerf INFO >/dev/null 2>&1 || true

  if parse_gfxinfo "$dir/gfxinfo.txt" > "$dir/summary.txt"; then
    echo "run-$run_id $(cat "$dir/summary.txt")"
    return 0
  else
    echo "run-$run_id $(cat "$dir/summary.txt")"
    return 1
  fi
}

run_broad_swipes() {
  local x="$1"
  local y_top="$2"
  local y_bottom="$3"
  local i

  for ((i = 1; i <= SWIPES_PER_RUN; i++)); do
    if (( i % 2 == 1 )); then
      run_adb shell input swipe "$x" "$y_bottom" "$x" "$y_top" "$SWIPE_MS"
    else
      run_adb shell input swipe "$x" "$y_top" "$x" "$y_bottom" "$SWIPE_MS"
    fi
    sleep "$SWIPE_PAUSE_SEC"
  done
}

run_hotspot_swipes() {
  local x="$1"
  local height="$2"
  local y_top=$((height * HOTSPOT_TOP_PCT / 100))
  local y_bottom=$((height * HOTSPOT_BOTTOM_PCT / 100))
  local i

  for ((i = 1; i <= SWIPES_PER_RUN; i++)); do
    run_adb shell input swipe "$x" "$y_bottom" "$x" "$y_top" "$SWIPE_MS"
    sleep "$SWIPE_PAUSE_SEC"
    run_adb shell input swipe "$x" "$y_top" "$x" "$y_bottom" "$SWIPE_MS"
    sleep "$SWIPE_PAUSE_SEC"
  done
}

seek_hotspot_below_viewport() {
  local dir="$1"
  local i
  read -r width height <<<"$(screen_size)"
  local x=$((width / 2))
  local y_seek_top=$((height * 58 / 100))
  local y_seek_bottom=$((height * 76 / 100))
  local y_bottom=$((height * 84 / 100))

  dump_ui "$dir/hotspot-seek-start.xml"
  if ! grep -Fq "$HOTSPOT_TEXT" "$dir/hotspot-seek-start.xml"; then
    for ((i = 1; i <= HOTSPOT_SEEK_SWIPES; i++)); do
      run_adb shell input swipe "$x" "$y_seek_top" "$x" "$y_seek_bottom" "$SWIPE_MS"
      sleep 0.15
      dump_ui "$dir/hotspot-seek-backward-$i.xml"
      if grep -Fq "$HOTSPOT_TEXT" "$dir/hotspot-seek-backward-$i.xml"; then
        break
      fi
    done
  fi

  dump_ui "$dir/hotspot-seek-visible.xml"
  if ! grep -Fq "$HOTSPOT_TEXT" "$dir/hotspot-seek-visible.xml"; then
    for ((i = 1; i <= HOTSPOT_SEEK_SWIPES * 2; i++)); do
      run_adb shell input swipe "$x" "$y_seek_bottom" "$x" "$y_seek_top" "$SWIPE_MS"
      sleep 0.15
      dump_ui "$dir/hotspot-seek-forward-$i.xml"
      if grep -Fq "$HOTSPOT_TEXT" "$dir/hotspot-seek-forward-$i.xml"; then
        cp "$dir/hotspot-seek-forward-$i.xml" "$dir/hotspot-seek-visible.xml"
        break
      fi
    done
    if ! grep -Fq "$HOTSPOT_TEXT" "$dir/hotspot-seek-visible.xml"; then
      echo "Current screen never reached HOTSPOT_TEXT=$HOTSPOT_TEXT" >&2
      echo "Saved UI dump: $dir/hotspot-seek-visible.xml" >&2
      return 2
    fi
  fi

  for ((i = 1; i <= HOTSPOT_SEEK_SWIPES; i++)); do
    run_adb shell input swipe "$x" "$y_seek_top" "$x" "$y_seek_bottom" "$SWIPE_MS"
    sleep 0.15
    dump_ui "$dir/hotspot-before-enter-$i.xml"
    if ! grep -Fq "$HOTSPOT_TEXT" "$dir/hotspot-before-enter-$i.xml"; then
      cp "$dir/hotspot-before-enter-$i.xml" "$dir/hotspot-before-enter.xml"
      return 0
    fi
  done

  echo "HOTSPOT_TEXT=$HOTSPOT_TEXT is still visible before enter test" >&2
  echo "Saved UI dump: $dir/hotspot-before-enter-${HOTSPOT_SEEK_SWIPES}.xml" >&2
  return 2
}

dump_ui() {
  local output="$1"
  run_adb shell uiautomator dump /sdcard/amber-window.xml >/dev/null 2>&1 || true
  run_adb pull /sdcard/amber-window.xml "$output" >/dev/null 2>&1 || true
}

assert_expected_text() {
  local xml="$1"
  if [[ -z "$EXPECTED_TEXT" ]]; then
    return 0
  fi
  if ! grep -Fq "$EXPECTED_TEXT" "$xml"; then
    echo "Current screen does not contain EXPECTED_TEXT=$EXPECTED_TEXT" >&2
    echo "Saved UI dump: $xml" >&2
    return 2
  fi
}

echo "artifact_root=$ARTIFACT_ROOT"
echo "target janky<=${TARGET_JANK_PCT}% legacy<=${TARGET_LEGACY_JANK_PCT}% p95<=${TARGET_P95_MS}ms p99<=${TARGET_P99_MS}ms slow_ui<=${TARGET_SLOW_UI_PCT}% ge_200<=${TARGET_HIST_GE_200}"

passes=0
for ((run = 1; run <= REPEATS; run++)); do
  if run_one "$run"; then
    passes=$((passes + 1))
  else
    status=$?
    if [[ "$status" -eq 2 ]]; then
      echo "invalid-sample run-$run" | tee "$ARTIFACT_ROOT/final.txt"
      exit 2
    fi
  fi
done

echo "passes=$passes/$REPEATS" | tee "$ARTIFACT_ROOT/final.txt"
if [[ "$passes" -eq "$REPEATS" ]]; then
  exit 0
fi
exit 1
