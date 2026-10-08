# Phase 2 independent review

Scope: compared the copied baseline `/tmp/amber-android-review-20261002-baseline` with the Phase 2 implementation of SS-01 through SS-06, plus production callers and focused tests. Read-only review; no production/test edits, Gradle run, other-product repository access, install, or real iCloud acceptance. Root owns execution evidence. Read `phase2.md` and `sync-security.md` for the approved boundaries, then checked actual code rather than treating their claims as proof.

## Verdict

All SS-01 through SS-06 are structurally closed with the scoped implementation after the local SS-04 follow-up below. No remaining blocking finding was identified in this phase review. Root-owned execution validation remains separate. No broader retry, fallback, storage migration, external SAF restore, or startup-recovery framework is warranted.

## Confirmed finding

### P2 — Probe cleanup failure replaces the original cancellation — FIXED and re-reviewed

- Location: `feature/icloud/src/main/kotlin/app/amber/feature/icloud/ICloudDriveManager.kt:132-147`.
- Concrete trigger: probe upload succeeds, `readText` throws `CancellationException`, and the cleanup `client.delete` throws a normal HTTP/network exception (for example connectivity loss during a cancelled read). Both are real operations against iCloud; delete resolves the node and moves it to trash and can fail independently.
- Actual behavior: Kotlin `finally` replaces the pending readback cancellation with the delete exception. Outer `getOrElse` checks only that replacement exception, so the explicit cancellation rethrow branch is bypassed and the method returns a READ_ONLY state. It also drops the original reason when readback failure and cleanup failure coincide.
- Second check / by design: the implementation and phase contract explicitly promise cancellation propagation after cleanup. `NonCancellable` permits the cleanup attempt but does not preserve the primary exception. The existing cancellation regression mocks read failure only and has successful delete, so it cannot expose this branch. This is a local exception-ownership defect, not a speculative demand for cloud rollback or retries.
- Minimal closure: preserve an in-flight primary exception; attach cleanup failure as suppressed when a primary failure exists, otherwise propagate cleanup failure so a successful read with failed cleanup does not mark the probe successful. Add a focused MockEngine case combining cancelled readback with failing trash request; assert cancellation survives and the cleanup attempt targeted only its own unique probe.

## SS-01 through SS-06 verification

| Finding | Production verification | Regression evidence reviewed | Result |
|---|---|---|---|
| SS-01 artifact bodies | Artifact locator collection is inside the exact transaction exporting artifact rows. Bodies read restored-owned first, configured SAF second, mirror fallback third. Only UUID md/json artifact locators enter the new dataset. Restore-owned root is separate from workspace mirror, participates in current file journal, and is read first by ArtifactRepository. Existing restored-body edits update it and deletion removes it. DataSourceModule injects the singleton WorkspaceManager. | Actual saveDeepRead mirror/SAF round trips, unrelated workspace exclusion, SAF current-body isolation, edit/delete, malformed artifact-row rollback, CONFIG_ONLY and absent-dataset legacy compatibility. | Closed in code. FULL chat-specific fixture is not added, but chat and DeepRead share the same locator/read/export path; no separate chat bypass was found. |
| SS-02 shared chat_images | Preserve both skips tree staging/replacement; replace both replaces tree; mixed combinations stage archive then merge current local files before journal replacement. Local bytes deliberately win collisions. Upload and standalone image roots retain their separate flags. | All four flag combinations, local/archive non-colliding files and identical-path collision. | Closed under the explicitly approved whole-root/local-wins policy. |
| SS-03 device key | getOrCreate uses local synchronized read/upsert. Production DI registers one DeviceBoundBackupKey singleton. SecretStore.update is upsert; current stays read-only and does not repair ciphertext during restore. | Unreadable descriptor recovery and eight concurrent calls returning the same durable key. | Closed; no cross-process/multiple-owner requirement found. |
| SS-04 probe | Unique UUID filename and overwrite=false avoid old user-file replacement; created flag restricts delete to the successful create path. NonCancellable cleanup handles normal cancellation when delete succeeds. | Legacy named file retained; mismatched readback cleanup; cancellation with successful cleanup. | Closed after local exception-preservation follow-up; no remaining blocking finding. |
| SS-05 chunked read | Tool now passes 4MB global cap to actual manager/client full-download path. Output slicing retains max_chars independently. Client enforces streamed byte count, not only advertised metadata. | Real tool execution with MockEngine downloads 300KB, returns first and subsequent 65536-char slices; over-4MB file still fails. | Closed with existing bounded full-read semantics. |
| SS-06 stage cleanup | Owning try/finally starts immediately after restore-stage creation and wraps extraction, missing-table compatibility validation, settings decode and apply; prior file-journal handling remains inside it. | Malformed settings and missing required artifact table assert stage directory set unchanged. | Closed. Extraction-limit failure follows the same finally mechanically; no additional sweep is needed. |

## Rejected / intentional observations

- Restored owned content shadows same-locator SAF content. This is the approved authority boundary and is tested. Normal artifact edit/delete continues the existing workspace operation in addition to owned-body synchronization; restore itself makes no SAF write.
- Old archives without artifact-content do not clear the owned root. This is the explicit compatibility policy and the new dataset marker controls replacement, including an empty dataset.
- Mixed shared-root merge retains some unused files and local bytes win a collision that an imported consumer also names. This is the approved policy; preserving two distinct bodies under one unchanged URI cannot be solved by an unrequested generic remap.
- DB and body export are not one global atomic filesystem snapshot. The existing documented backup contract exports one DB snapshot and external file resources separately; Phase 2 did not introduce a global writer lock. No new data race unique to this patch was proved.
- The nullable WorkspaceManager constructor dependency preserves existing test factories; production uses singleton injection and fallback constructs from current workspace preferences for export only. This is a small compatibility seam, not an unnecessary abstraction layer.
- No dead-code removal or general security machinery is requested by the evidence.

## Execution boundary

The regressions were reviewed for real production calls and assertions, but not executed by this reviewer. Do not equate this static review with root-owned GREEN tests, APK build, provider/device or UX acceptance. The local probe exception-preservation patch has now been re-reviewed; execution GREEN remains root-owned.

## Follow-up review — SS-04 closure

Read the final manager patch and `failedCleanupDoesNotReplaceProbeCancellation` regression without modifying code/tests or running Gradle. The manager now records the original probe failure in a narrow local variable. Cleanup still runs only for its own successfully created UUID probe, with NonCancellable I/O; cleanup failure is suppressed onto the original failure instead of replacing it. If no original failure exists, cleanup failure propagates to the existing READ_ONLY handling, so a successful read plus failed delete cannot mark the probe successful. Original cancellation reaches the explicit CancellationException rethrow.

The new MockEngine regression makes readback throw the named CancellationException and the actual trash request throw an ordinary failure, then asserts cancellation type/message, suppressed cleanup failure and unchanged legacy user file. It exercises the previously missed production branch. No retries, generic helper or rollback layer was added. Finding closed in the implementation and independent static review; test execution is currently owned by root's unified Phase 1–3 run.
