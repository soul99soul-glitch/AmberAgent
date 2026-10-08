# Phase 2 — sync / backup / iCloud

Status: production implementation complete, awaiting root-owned Gradle validation and independent phase review. Do not claim test pass yet.

Root RED evidence: `/tmp/amber-review-phase1-green-next-red.log` reproduced all SS-01 through SS-06 defects before these fixes.

## Final implementation

- SS-01: `ArtifactBackupContentStore` owns only restored artifact bodies under `filesDir/amberagent/artifact-content`. Archive export reads artifact locators from the same SQLite snapshot as exported rows, then reads each actual authoritative body (owned restored file, otherwise existing SAF-primary/mirror fallback). The explicit dataset is staged and replaced through the existing internal restore journal. ArtifactRepository prefers only an existing restored owned file, keeps ordinary SAF-primary behavior otherwise, and synchronizes edits/deletes of restored bodies. Restore never writes external SAF or includes unrelated workspace files. Missing dataset in old archives leaves the existing owned root alone; CONFIG_ONLY does not replace it.
- SS-02: both consumers preserved => keep local chat_images; both replaced => full replacement. Mixed combinations import archive files and merge surviving local files into the staged tree before journal replacement. Local bytes win path collisions, per the approved policy.
- SS-03: synchronized device-key generation upserts a fresh key when the stored ciphertext is unreadable. `current()` remains read-only so unrelated-device archives still reject.
- SS-04: unique probe filename with overwrite=false; NonCancellable cleanup only after the probe's own create succeeds. Existing legacy-named probe file remains untouched. Probe cancellation propagates after cleanup.
- SS-05: icloud_read uses the existing 4MB whole-file download hard cap. Output slicing alone follows start_char/max_chars, so first and subsequent chunks of a 300KB file work.
- SS-06: extraction, compatibility validation and settings decode all execute under one staging-directory try/finally. The prior inner cleanup moved to this owning boundary.

## Verification prepared

12 new focused tests across `SyncArchiveManagerIntegrationTest` (5), `DeviceBoundBackupKeyRecoveryTest` (2), `ICloudDriveBackupRegressionTest` (5), with a small SAF provider fixture. Includes body round trip/missing unrelated files/current SAF isolation/owned body edit-delete/journal rollback/old archive + CONFIG_ONLY compatibility, four preservation combinations/local collision policy, unreadable + concurrent device keys, successful/failed/cancelled probe cleanup, 300KB chunks + 4MB cap, and failed validation stage cleanup.

`git diff --check` was run on changed sync/iCloud paths. This agent did not run Gradle, install or device/network acceptance.

## Integration notes

`DataSourceModule` injects the existing singleton WorkspaceManager into SyncArchiveManager. The optional constructor argument keeps old test factories source-compatible; fallback creates a WorkspaceManager only for export so it sees current persisted SAF selection. No new global abstraction, general retry/rollback framework, external SAF restore writes, or preservation toggle.

The mixed shared-root collision policy intentionally protects current local content at the same path. An archived and surviving consumer that name the exact same conversation/file path with different bytes cannot both retain distinct bytes under the unchanged URI. This phase does not invent URI remapping; non-colliding local and imported files are retained and collision behavior is explicit in tests.

## Independent review follow-up

The phase reviewer confirmed a cancellation precedence defect: a cancelled probe read-back followed by failed remote cleanup replaced the original CancellationException and was reported as ordinary READ_ONLY failure. Added deterministic MockEngine `failedCleanupDoesNotReplaceProbeCancellation` (read-back cancellation plus trash failure). The local probe preserves its primary exception and attaches cleanup failure as suppressed; with no primary error, cleanup failure still propagates through the existing failure path. This adds one regression test (13 total prepared in this phase), without a general cleanup framework. Root owns the follow-up Gradle validation; this agent did not run Gradle.
