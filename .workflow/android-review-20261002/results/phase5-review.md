# Phase 5 independent integration review

Reviewer: phase1_review, final scoped integration pass. Read-only review of the comprehensive report, original WIP snapshot and change manifest, shared integration files, Settings publication/fixture closures and OAuth build configuration boundary. No Gradle execution, production/test edits, other-product repository access, commit, push or install.

## Conclusion

**No real integration blocker found in the assigned scope.** Full-suite execution and ordinary-config APK generation are still root-owned gates; this report does not convert an in-progress Gradle log into GREEN or device/provider acceptance.

## Issue accounting and evidence language

The table in `docs/reviews/2026-10-02-comprehensive-review.md` contains exactly **48 unique production groups**: NS 10, NR 4, SS 6, JEV 8, RCI 4, MC 8, T 7 and SET 1. No duplicated ID or fixture-only RCI05 inflation was found. Supplementary cancellation/roundtrip/protocol edge fixes remain grouped with their actual defect. NS9 is justified independently by the existing repeated-character extension bug, beyond the NS4 dot-title follow-up.

The report separately records by-design behavior, fixture readiness/expectation defects and synthetic OAuth configuration. It states the source/JVM boundary and explicitly excludes cloud/provider, Keystore, SAF-device, OEM notification, screen automation and visual acceptance. The recorded intermediate test counts match the first verification JSON artifacts, including the app slice failures. Its final validation section and phase statuses must be refreshed only after root completes the pending all-module tests/build. The phrase `8字节fallback` would be more precise as `8字符fallback`: the actual allocator uses Kotlin take(8); this is wording only, not a new product issue.

## Settings and shared predicate closure

SET01 production logic is unchanged since the independent Settings review: all six domains derive from one latest durable Preferences object, decoded off Main and published while the existing writeMutex is held. A queued old notification cannot overwrite an acknowledged later update. Ordinary update, updateLaunchCount and restoreSecretRefs all publish the same canonical readback before returning. Existing normalization/backfill/model-slot rules and secret refs rehydration remain shared.

The important affected modes have been checked together:

- Provider seed waits for serialized complete provider configuration and every canonical slot, not identity alone or AUTO for valid selected models.
- Soul prechecks use the canonical flow; durable CAS writes still read persisted state. No Soul retry was added to conceal a false Stale result.
- MiniApp runner gate checks the live canonical high-risk setting through the actual factory; cold-start readiness is a fixture wait rather than product fallback.
- WebMount test matches the actual combined collapsed label and production 44 dp minimum; no layout patch was introduced for a stale assertion.
- Settings normal scope-cancellation/fatal limitation remains explicitly identified as inherited policy, rather than a falsely claimed new regression.

`settings-review.md` contains the detailed source/fixture assessment. Earlier app slice evidence establishes these tests passed after the focused repair; the final all-module pass remains separate.

## Initial WIP preservation evidence

Independently recomputed the manifest against `/tmp/amber-android-review-20261002-baseline`, excluding only snapshot metadata `head.txt` and `git-status.txt`:

- 2,218 original files are byte-identical.
- 70 original files differ through this review's authorized edits.
- No original source/test/document file is missing.
- Both the unchanged count and exact modified path set match `results/change-manifest.json`.

HEAD still equals the report's `6a66f7a3c91f9b4f8f0335c8b0f58fa88dca77d9`. The original archive includes the pre-existing dirty/untracked contents and status. Shared-file changes were compared with that archive, not git HEAD, so baseline WIP is not incorrectly attributed to this repair. This proves no original path deletion and a retained reviewable original snapshot. It does not justify saying every intentionally modified file was left byte-identical or mechanically prove every semantic WIP detail independently.

## Shared file integration boundaries

- **ChatVM**: new HostDraftComposer owns only host draft projection/accepted-send consumption. onChatVisible still initializes the actual conversation before the existing approval-resume call; host draft refresh now runs even when auto-approval is disabled. The auto-approval setting remains read live and the original ChatService resume gate remains the approval owner. Composer draft consumption captures identity synchronously and uses durable draft-ID CAS; it does not mutate tool approval or make a stale whole draft authoritative.
- **ArtifactRepository**: the baseline-relative change only adds restored-owned body read/open/update/delete integration. Its existing saveDeepRead and normal workspace pathways remain. MC05 effect dedup is in the singleton MiniAppWorkspaceWriter, not a competing ArtifactRepository rewrite. Restore-owned shadow authority is documented/accepted and covered in Phase2; no new backup or MiniApp ownership conflict was found.
- **DefaultRunKernel**: the baseline-relative diff extracts the already-existing Jev escalation logic and invokes it at both fresh Auto calls and transient Auto rechecks of persisted Pending tools. Metadata survives escalation; original owner/effect/persisted snapshots and manual Approved behavior remain. ChatVM no longer introduces a parallel path for these repaired calls. The unreferenced legacy helper is documented as a candidate without a current reachable trigger, and was not silently deleted.

No cross-phase revert, overwritten duplicate implementation, extra global writer framework or unintended coupling was found in these diffs.

## Synthetic OAuth versus ordinary APK

`ai/build.gradle.kts` is byte-identical to the archived baseline, as are the root/app build scripts checked earlier. The existing value priority is command-line Gradle property, environment variable, then local.properties. Current project gradle.properties and local.properties contain zero Antigravity OAuth override keys; no synthetic marker was persisted there. The focused OAuth tests use synthetic nonsecret -P values to cross the explicit configured-client gate. This is fixture configuration, not a production OAuth bypass.

The root's subsequent assembleDebug must run without those -P overrides and must regenerate ai BuildConfig with the ordinary value source. Before final delivery, inspect the generated BuildConfig fields as empty/ordinary versus synthetic (without logging secrets), and record the new APK/build result. Gradle's tracked BuildConfig inputs should invalidate the test configuration output, but a successful earlier test artifact alone is not evidence of ordinary final APK configuration. No synthetic values were added to source scripts by this repair.

## Remaining root-owned evidence

At report time `/tmp/amber-review-final-all-tests.log` is still running through all-module tasks, so no final test or APK success claim is made here. Root should append final counts/skips/failures, log/build result and ordinary-config BuildConfig verification to the comprehensive report and workflow state. External acceptance boundaries must remain explicit. This reviewer ran no tests/builds and made only this review artifact.

## Final follow-up — core settings fixture scheduling

Independently compared `SecretPrefsChainRoundTripTest` and `core/settings/build.gradle.kts` with the original snapshot. **The fix is test-only and appropriate; no new production blocker.** All business roundtrip assertions remain unchanged. The class now installs UnconfinedTestDispatcher Main in setup and restores Main in teardown. Its old Looper-idle/delay polling helper is replaced by finite withTimeout + StateFlow.first. Only the existing coroutines-test catalog dependency is added as testImplementation. No production scheduler, retry, timeout, lock bypass or readiness API was changed.

The supplied thread dump records SDK Main waiting in the runBlocking test at line 315. With Android's normal nonblocked Main loop, the aggregator's return-to-Main continuation releases writeMutex. In the old Robolectric fixture, init=false could be observed on Default while that Main continuation remained queued; a subsequent runBlocking write then stranded it. The test dispatcher fixes that specific test scheduling mismatch and retains the actual DataStore/secret read-write chain.

Root execution evidence now inspected: `/tmp/amber-review-core-settings-green.log` ends BUILD SUCCESSFUL in 6s. Core settings XML totals are **95 tests, 0 failures, 0 errors, 0 skipped**, including all **9 SecretPrefsChainRoundTripTest cases**. This closes the fixture hang without counting it as a production issue. The reviewer did not execute Gradle.

The two late test-only edits add two changed existing files to the original manifest. Root will refresh the final manifest from 70 to 72 modified original files (and the unchanged count correspondingly); the earlier 2,218/70 assessment above records the initial integration check, not a frozen claim for the final tree. The unrelated final app Koin/bridge fixture follow-ups remain owned by their reviewers and root final green.

### Final app fixture boundary check

Read `all-tests-first.json`: app has 2,696 tests / 2 failures / 14 skipped; the named remaining failures are KoinGraphVerifyTest and conversationImageIsSnapshottedThroughGuardedClientBeforeDraftOrSend. No claim of full app GREEN is made from this artifact.

The image fixture's successful fake HTTP response now explicitly sets `Content-Type: image/png`. Supplying a ResponseBody media type alone does not add that response header; the repaired fixture matches the existing guarded-client contract. This is a response-assembly correction, not a provider/network production change. The image body/content, guarded fetch, rejection and approval business assertions remain the relevant gate. No renewed review of unrelated product network logic was undertaken.

For Koin, `NovelModule.kt:26-28` constructs NovelWorkspaceRestoreBridge with a Context-derived workspaceRoot File; only the gate is resolved through get(). A per-definition File parameter declaration therefore matches the real factory and is not a global File exemption or a new production binding. Final read-only follow-up confirms the declaration is now present at KoinGraphVerifyTest:88-92 and is limited to NovelWorkspaceRestoreBridge + java.io.File. Its SyncRestoreWriteGate remains graph-resolved and no global File exemption or product binding was added. This fixture source gap is closed; root owns the full-suite execution result.

### Koin fixture final closure

The delivered three-line per-definition declaration exactly matches the Context-derived File argument in NovelModule:26-28. Compared with the archived baseline, NovelModule itself received no review-time change; the bridge binding was original WIP. The only supplemental change is the test's precision about hand-built factory arguments. Source review closed with no blocker and no broader DI-policy relaxation. No Gradle wait or execution by this reviewer.

## Final evidence acceptance — complete

**Final integration verification accepted. No remaining source/test/build blocker in the authorized review scope.** Root delivered `final-verification.json`; independently read both completion logs, parsed every recorded module's current XML and compared its counts/suite count, inspected generated ai BuildConfig without exposing values, recomputed both APK hashes/sizes and reran read-only apksigner verification. No Gradle/build/test was run by this reviewer.

- All-module JVM/Robolectric run: BUILD SUCCESSFUL in 45s. All 18 modules match the evidence JSON: 3,390 tests, 0 failures, 0 errors, 24 skipped; 3,366 passed. App matches 2,696 tests, 0 failures/errors, 14 skipped. The final Koin and image fixture closures therefore have executed GREEN.
- Ordinary `./gradlew --offline :app:assembleDebug`: BUILD SUCCESSFUL in 21s. Its evidence command has no synthetic OAuth -P overrides. Current ai generated debug BuildConfig has the two expected OAuth fields and neither contains either synthetic test marker. Actual configuration values were not printed.
- Both `app-universal-debug.apk` and `app-arm64-v8a-debug.apk` are 114,234,923 bytes and independently match SHA-256 `1fa289a599c265248bc97487a099a2659b4bebbfb475aede642faab854bf855a`. Read-only apksigner verify returns exit 0 and v2 true for each. This proves generated debug artifact/signature verification, not installation or production signing.
- Final original-WIP comparison independently recomputed: 2,216 original files unchanged, 72 original files modified, no missing original files. The final manifest accounts for the late test-only changes. Original source snapshot remains available for review.

All earlier pending-GREEN language above describes intermediate checkpoints; this final acceptance supersedes those execution gates. Device installation/launch, real cloud/provider/OEM/SAF/Keystore and visual acceptance remain outside this task's demonstrated evidence. No commit, push or device installation is implied by acceptance.
