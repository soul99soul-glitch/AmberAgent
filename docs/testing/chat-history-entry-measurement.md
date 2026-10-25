# Opt-in history-entry measurement fixtures

This increment adds measurement and deterministic test infrastructure, not a new history-rendering optimization. The branch also contains the earlier cold-entry optimization commit from the still-open PR #36; a PR targeting main depends on/contains that prior work. Do not describe the entire branch diff as only this infrastructure increment. `chatPerfTrace` and `chatPerfFixture` default to false. The fixture build is a separate, non-debuggable/profileable `app.amber.agent.perffixture` package; it uses the production Home → ChatPage → ChatVM → Room path with synthetic data and a local provider. Fixture activities/receiver are exported only in that opt-in build. Do not ship it as a daily-use app.

The default product build retains its normal Application, package, runner, navigation, preparation budgets and animations. Diagnostics do not perform full-history scans, collect counters or export metrics when disabled. This is not a zero-overhead claim: some fixed clock reads and nullable probe lookups remain.

## Historical merged-runtime evidence identity

On 2026-10-28, the merged checkout `c176cab50e6c4fa8be99277323740ece0c50ec12` plus the recorded Stage A working-tree changes was rebuilt and checked on one Android 17 / arm64 device. Content hashes, including untracked inputs, identify the runtime; the commit alone is insufficient.

| Validation | Result |
|---|---|
| Python host suite at runtime acceptance | 88 passed |
| Trace-ON fixture JVM selection | 37 passed |
| Trace-OFF fixture JVM selection | 16 passed, including Store 9 and ProjectionAtomicity 2 |
| Compose readiness/timeline tests in the debug host | 6 passed |
| Default-OFF debug compilation and bridge/marker tests | Compilation succeeded; 4 passed; `CHAT_PERF_TRACE=false` |
| Compiled Home JUnit signature | Public, non-static `()V` |
| Device functionality | Home → cold Chat → production Back → Home → warm Chat, plus 5 isolation/protocol tests: 6 passed |
| ON smoke | S1/S2/S3, one cold and one warm each: 6 valid, no sample retries |
| Persisted fixture data | 4/120/1000 rows verified individually in a new readback process |
| OFF APK | Built and statically audited; not installed or sampled in this merged-runtime review |

All six smoke runs had seven phases, revision 2 final metrics, matching runId/PID/Activity/target identity, the Home-first path and a fixed 1500ms trace window. Cold/warm retained the same process and Activity within each scenario. S2/S3 intentionally share tail content. This does not mean every page of 1000 nodes was traversed.

| Merged acceptance APK | SHA256 |
|---|---|
| ON | `3ffae43e703232b34b62b8acb52f0db6e18248fa308f532c72ecb2d56375d8b6` |
| OFF | `9fb796cad6d57039a9f04ecf2d40ab945343f0bc19b753afd5b1125d98ccdbe5` |
| Test | `72bd0f62947f67d4c0639b2a2af48c871e7f29b08b13367585703257cda93031` |

Frozen source-manifest SHA256: `da9ed852246781d6292c7f7774cec5b9c9a09bc525fa51a00138df33ae8c15a3`. ON/OFF signatures match and both carry generated baseline profiles. Profile presence is not proof of ART runtime compilation state. The test APK matches the previously validated r4 test content; the runtime APKs are new builds.

The first test selections included six Compose tests in a fixture host without the debug ComponentActivity declaration, and three trace-dependent lifecycle tests under OFF. These failures were retained. The Compose tests then passed in their intended debug host; lifecycle tests passed under ON. No assertions were weakened and no product code was changed to accommodate the selections.

`FirstReadableDraw` means draw-ready, not screen present. `FirstInteractive` confirms a real safe interaction after the trace window. All summaries retain `formalComparisonQualified=false`; deadline/GC/ART evidence is incomplete. Do not derive jank rates, statistical gains, equivalence or zero instrumentation overhead from these smoke runs. Debug ~140ms observations are a different build condition. B/C algorithms, preparation budgets, animations and root layout remain unreleased.

The earlier temporary-directory evidence was lost. Later preserved A2 evidence belongs to `26446e1` plus its recorded diff, not automatically to this merged runtime; 61 recorded runtime inputs changed. Historical 18-pilot/16-overhead samples remain separate. Their written protocol followed the first group, so they cannot be described as fully preregistered. Never rewrite historical manifests or reconstruct missing traces as results.

On 2026-10-29, host-only preparation removed personal tool/output paths and old APK defaults, added an install package guard and reduced raw device-log regression inputs to redacted environment-field excerpts. These host changes do not alter Android runtime inputs. Their Python checks (90 passed: the original 88 plus 2 host portability/install-guard tests) are separate from the 88-test/runtime record above; they have not been rerun on hardware. Original APKs, raw logs and traces remain local evidence, outside the PR.

## Reproduce locally

Run from this Android repository root. Use JDK 17 (`JAVA_HOME`), an installed Android SDK, Python 3 and Bash. Put build-tools `aapt2`/`apksigner` on PATH or export `AAPT2`/`APKSIGNER`; `ADB` accepts an argv string, including a required host architecture wrapper. Keep APKs and raw evidence in an external `ARTIFACTS` directory, never in the commit.

```bash
python3 -m unittest discover -s scripts/tests -v
mkdir -p "$ARTIFACTS"
./gradlew --offline -PchatPerfFixture=true -PchatPerfTrace=true -PprofileableDebug=true \
  :app:testFullPerffixtureUnitTest --tests 'app.amber.agent.perffixture.*' \
  --tests 'app.amber.feature.ui.utils.ChatPerf*' \
  --tests 'app.amber.feature.ui.pages.chat.ChatTimelinePrewarmSelectionTest' \
  --tests 'app.amber.feature.ui.pages.chat.ChatTimelinePlanIncrementalTest' \
  :app:assembleFullPerffixture :app:assembleFullPerffixtureAndroidTest
cp app/build/outputs/apk/full/perffixture/app-full-arm64-v8a-perffixture.apk "$ARTIFACTS/on.apk"
cp app/build/outputs/apk/androidTest/full/perffixture/app-full-perffixture-androidTest.apk "$ARTIFACTS/test.apk"
./gradlew --offline -PchatPerfFixture=true -PchatPerfTrace=false -PprofileableDebug=true \
  :app:testFullPerffixtureUnitTest --tests 'app.amber.agent.perffixture.HomeFirstEntryTest' \
  --tests 'app.amber.agent.perffixture.PerfFixtureStorageShapeTest' \
  --tests 'app.amber.core.service.ConversationStoreTest' \
  --tests 'app.amber.core.service.ConversationProjectionAtomicityTest' :app:assembleFullPerffixture
cp app/build/outputs/apk/full/perffixture/app-full-arm64-v8a-perffixture.apk "$ARTIFACTS/off.apk"
./gradlew --offline -PchatPerfTrace=true :app:testFullDebugUnitTest \
  --tests 'app.amber.feature.ui.components.richtext.MarkdownReadinessProbeTest' \
  --tests 'app.amber.feature.ui.pages.chat.ChatTimelineEffectsTest'
./gradlew --offline :app:compileFullDebugKotlin :app:testFullDebugUnitTest \
  --tests 'app.amber.feature.ui.utils.ChatPerfCommandBridgeTest' \
  --tests 'app.amber.feature.ui.utils.ChatPerfMarkerStateTest'
```

Freeze a **new** source manifest for each build pair. Record HEAD, tracked binary diff SHA256, untracked-file hashes, complete runtime/test/build inputs, JDK/SDK/Gradle, source/generated profiles and APK signatures/hashes. Collector-required fields include `head`, `trackedDiffSha256`, `untrackedFiles`, `hostFiles` (the current `chat-history-entry-perf.py`, `parse-atrace-frames.py`, `chat_perf_contract.py` SHA256 values), and `build.apks` entries containing each APK's `sha256`. Verify source inputs did not change between ON/OFF builds. The collector checks these identities and pulls the installed APK before/after collection. Old manifests fail when host scripts or APKs change; do not edit an old manifest to reuse its samples.

Only the authorized device owner runs the commands below, without another device task. Inspect `--help` and keep the screen awake/unlocked manually before starting; scripts do not bypass a lock screen, change global settings or clear product data. All mutable fixture paths remain under the independent package. Exported fixture controls are for synthetic data only. Never point seeding at `.graphite`.

```bash
export SERIAL="your-authorized-device-serial"
adb_fixture() {
  python3 -c 'import shlex,subprocess,sys; sys.exit(subprocess.call([*shlex.split(sys.argv[1]), "-s", sys.argv[2], *sys.argv[3:]]))' "${ADB:-adb}" "$SERIAL" "$@"
}
export ON_APK="$ARTIFACTS/on.apk" OFF_APK="$ARTIFACTS/off.apk" TEST_APK="$ARTIFACTS/test.apk"
export SOURCE_MANIFEST="$ARTIFACTS/source-manifest.json" EVIDENCE_ROOT="$ARTIFACTS/evidence"
bash scripts/chat-history-entry-device.sh install on
python3 scripts/run-home-device-test.py --serial "$SERIAL" --output-dir "$EVIDENCE_ROOT/home-test"
adb_fixture shell am instrument -w -e class \
  app.amber.agent.perffixture.PerfFixtureIsolationTest,app.amber.agent.perffixture.PerfFixtureProtocolDeviceTest \
  app.amber.agent.perffixture.test/app.amber.agent.perffixture.PerfFixtureAndroidTestRunner
bash scripts/chat-history-entry-device.sh single S1 on
# Inspect both results before expanding; at most one same-condition retry.
bash scripts/chat-history-entry-device.sh single S2 on
bash scripts/chat-history-entry-device.sh single S3 on
adb_fixture shell am force-stop app.amber.agent.perffixture
```

The wrapper runs doctor → separate-process prepare/readback → cold → production Back → warm → parse/collect/summary. The primitive CLIs allow explicit `--adb`, `--capture-log`, `--trace-output`, `--source-manifest` and `--output`. The fixed window, r4 lifecycle/window/display/touch checks, final revision and missing-marker failures must remain intact. Preserve invalid samples and unknown fields. Pilot/overhead commands are explicit larger experiments, not part of this six-run smoke or an automatic next step.

## UTF-16 metric naming correction

The current diagnostic metrics JSON uses schema 2 and exports only `preparation.toolOutputCharsPrepared`: the sum of `String.length` for Text tool outputs, measured in UTF-16 code units. CJK characters count as one unit and an emoji surrogate pair as two. This is not encoded bytes, serialized JSON volume or a byte budget. The existing Int accumulator limits remain; this change does not increase budgets or encode outputs. The VM and regression use the same diagnostic counting function. A Unicode export regression checks 4 UTF-16 units for “中文😀”, compared with 10 UTF-8 bytes computed only inside the test, and checks the exact exported preparation keys.

The run-manifest schema remains 1. The collector/summarizer consume phases and treat preparation fields as opaque; they can read the new metrics schema without translating historical fields. Historical metrics schema 1 and the APK/source hashes above are not rewritten or relabeled. This naming correction creates a new runtime identity; its new build and bounded device validation are recorded separately below. No previous six-run smoke is automatically credited to this APK.

Current unit-fix validation (2026-10-29): ON/OFF/test assembled and statically audited, 25 ON JVM tests passed (including the Unicode export regression), 3 OFF Home JVM tests passed, default-OFF compilation and 4 JVM tests passed, and 90 Python tests passed. ON/test were installed on the same isolated device. One S1 cold/warm pair was valid with schema 2 final metrics and real collect/summarize; one separate S5 native-path measurement check recorded 80 tools and exactly 2,699,264 UTF-16 code units. There were no sample retries. OFF remains build/static-audit only, with no device installation/sampling in this review.

**Unresolved heavy-tool risk:** the S5 native-path run observed a 154.239ms maximum outer main-thread frame. The raw trace and slow frame were retained. This check passed metric naming/identity/export validation, not performance acceptance. There is no causal attribution and no evidence that the naming correction introduced the long frame; no optimization or further sampling was attempted. It is not comparable to the S1 Home-first path or a formal baseline/candidate result.

| Unit-fix APK | SHA256 |
|---|---|
| fixture-chars-on.apk | `c111c2c3da2170d9d293384a141f777bf075d958f522fd58fa935fa5160ca046` |
| fixture-chars-test.apk | `72bd0f62947f67d4c0639b2a2af48c871e7f29b08b13367585703257cda93031` |
| fixture-chars-off.apk | `5d246b72964c588f0011ea4c04114518ee656213e636075b131fd5b025103b5c` |

New frozen source-manifest SHA256: `427a3f66b80e785e503b30575bf9cec2c84d0e22ef122defa59d79fb6292c4b6`. The current APKs and three bounded checks are separate from the historical six-smoke/six-device record above. The test APK content remains r4-identical. The fixture was force-stopped afterward, data kept, with no global setting change, unlock bypass or graphite data access. Full S0–S8, complete pagination and formal statistics were not rerun.

Not covered by this handoff: the full S0–S8 matrix, all fontScale/bubble/RTL combinations, full pagination/deep-link/restore coverage, daily Application background recovery/load, OFF device behavior in this review, multiple devices and formal algorithm A/B. If a future candidate is justified, the original two-batch and at least 10 cold + 10 warm per side standard still applies. This handoff is not a claim of a PR being merged or a complete Gate A/performance gain approval.
