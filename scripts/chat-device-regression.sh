#!/usr/bin/env bash
# Runs production chat/CAS/Room in the test Application's separate storage namespace.
set -euo pipefail
adb_path="${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
serial="${SERIAL:-392288d7}"
package="${PACKAGE:-app.amber.agent.graphite}"
output_dir="${1:-artifacts/chat-performance/device-regression}"
adb_cmd=("$adb_path")
if [[ "$(uname -s)" == Darwin && "${ADB_ARCH:-x86_64}" == x86_64 ]]; then
  adb_cmd=(arch -x86_64 "$adb_path")
fi
run_adb() { "${adb_cmd[@]}" -s "$serial" "$@"; }
mkdir -p "$output_dir"
# Optional install; always record the SHA-256 of what is actually installed.
if [[ -n "${APP_APK:-}" ]]; then
  shasum -a 256 "$APP_APK" > "$output_dir/app-apk-local.sha256"
  run_adb install -r "$APP_APK" > "$output_dir/install-app.txt" 2>&1
fi
if [[ -n "${TEST_APK:-}" ]]; then
  shasum -a 256 "$TEST_APK" > "$output_dir/test-apk-local.sha256"
  run_adb install -r "$TEST_APK" > "$output_dir/install-test.txt" 2>&1
fi
for installed in "$package" "$package.test"; do
  installed_path="$(run_adb shell pm path "$installed" | head -1 | sed 's/^package://' | tr -d '\r')"
  printf '%s ' "$installed"
  run_adb shell sha256sum "$installed_path"
done > "$output_dir/installed-apks.sha256"
run_adb shell run-as "$package" sha256sum files/datastore/settings.preferences_pb > "$output_dir/user-model-settings-before.sha256"
# RouteActivity's Context-local launch preferences belong to the Activity context.
# Save/restore these UI-only preferences while all DB/DataStore work is sandboxed.
run_adb exec-out run-as "$package" cat shared_prefs/amber_agent.preferences.xml > "$output_dir/launch-preferences-backup.xml"
restore_launch_preferences() {
  run_adb shell am force-stop "$package" || true
  run_adb shell "run-as $package sh -c 'cat > shared_prefs/amber_agent.preferences.xml'" \
    < "$output_dir/launch-preferences-backup.xml"
}
trap restore_launch_preferences EXIT
# Per-run test evidence written by the instrumentation (never user data).
evidence_dir="/sdcard/Android/data/$package/files"
run_adb shell "rm -f $evidence_dir/production-*.json" || true
failed=0
ran=0
: > "$output_dir/summary.txt"
for method in ${CHAT_DEVICE_METHODS:-cancelRegenerateSwitchHistoryAndCopy reopenDurableReplyInFreshProcess productionPinnedCompletionShowsSettledRichMarkdown productionCompletionPreservesOffBottomReadingAnchor productionCancelPreservesOffBottomReadingAnchor productionVisibleReplySwapsUnderOlderReadingAnchor}; do
  # The panel's 60s idle timeout would stop the activity mid-method; a wake
  # key is ordinary input (no setting change) and restarts that timer.
  run_adb shell input keyevent KEYCODE_WAKEUP
  ( sleep 1
    run_adb shell am start -W --activity-single-top -n "$package/androidx.activity.ComponentActivity"
  ) > "$output_dir/$method-foreground-kick.txt" 2>&1 &
  kick_pid=$!
  run_adb shell am instrument -w -e amberDeviceRegression true \
    -e class "${CHAT_DEVICE_TEST_CLASS:-app.amber.agent.ChatInteractionDeviceRegressionTest}#$method" \
    "$package.test/app.amber.agent.ChatDeviceFixtureRunner" | tee "$output_dir/$method.txt"
  wait "$kick_pid" || true
  run_adb logcat -d -s AmberDeviceRegression:I > "$output_dir/$method-logcat.txt"
  ran=$((ran + 1))
  if rg -q 'OK \(1 test\)' "$output_dir/$method.txt"; then
    printf 'OK %s\n' "$method" | tee -a "$output_dir/summary.txt"
  else
    printf 'FAIL %s\n' "$method" | tee -a "$output_dir/summary.txt"
    failed=$((failed + 1))
  fi
done
printf 'ran=%s failed=%s\n' "$ran" "$failed" | tee -a "$output_dir/summary.txt"
mkdir -p "$output_dir/evidence"
for evidence in $(run_adb shell ls "$evidence_dir" | tr -d '\r' | grep '^production-.*[.]json$' || true); do
  run_adb pull "$evidence_dir/$evidence" "$output_dir/evidence/" >> "$output_dir/pull-evidence.txt" 2>&1 || true
done
restore_launch_preferences
trap - EXIT
run_adb shell run-as "$package" sha256sum files/datastore/settings.preferences_pb > "$output_dir/user-model-settings-after.sha256"
cmp "$output_dir/user-model-settings-before.sha256" "$output_dir/user-model-settings-after.sha256"
if (( failed > 0 )); then
  echo "$failed/$ran methods failed; see $output_dir/summary.txt" >&2
  exit 1
fi
