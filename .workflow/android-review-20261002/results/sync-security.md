# Sync / backup / workspace / iCloud read-only review

Scope: production HEAD 6a66f7a plus current WIP. Inspected Android sync manager/providers/local import, Android backup XML, device-bound secrets, workspace mirror ownership, artifact content storage, iCloud manager/client/tool/UI paths. No code changes or Gradle runs. Findings below were checked against production callers and intentional policy comments.

## Confirmed findings

### SS-01 [P1] Full backup restores artifact rows without their content files

- Location: `app/src/main/java/app/amber/core/sync/core/SyncArchiveManager.kt:1256` (`artifact` / `artifact_reference` export) and `:1300` (file-root allowlist); `app/src/main/java/app/amber/agent/data/workspace/ArtifactRepository.kt:403-423`.
- Trigger: save chat/DeepRead as an artifact with no SAF workspace configured; create FULL backup through local export / Google / WebDAV / local-folder; restore into another installation.
- Evidence: artifact content locator points inside `WorkspaceManager.mirrorDir` (`filesDir/amberagent/workspace-mirror`). Export writes the DB rows but only UPLOAD/SKILLS/IMAGES/CHAT_IMAGES/novel-workspace files. Artifact read checks configured SAF first and this omitted mirror second. On a clean restore neither has the body, so artifact is listed but `readContent` returns null. SAF-configured artifacts are also omitted because successful SAF write does not populate mirror.
- Recheck: external user workspace itself can reasonably stay out of application backup; the problem is exporting application-owned artifact metadata while dropping every persisted body. No other artifact backup/export bridge exists in the examined production path.
- Approved minimal closure (Phase2): add a small ArtifactBackupContentStore with owned durable root `filesDir/amberagent/artifact-content`. Export only snapshotted artifact rows' restricted `artifacts/<UUID>.md/.json` locators, reading prior owned restored body first, then the actual SAF-primary/mirror fallback storage. Explicit artifact-content dataset is staged/replaced under the existing internal journal. ArtifactRepository readContent/contentFile prefers an existing owned restored body, otherwise retains normal SAF-primary behavior; editing/deleting such a restored artifact updates/deletes its owned body as well. This never writes external SAF during restore, and unrelated old SAF content cannot shadow imported content. It also survives terminal mirror reset. CONFIG_ONLY leaves bodies alone; old archives lacking the dataset do not replace the owned root. No new preserve switch; EVERYTHING artifact rows and bodies follow the same replacement rule independently of conversation/gallery switches.
- Regression: create chat and DeepRead artifacts in mirror-only mode; FULL round trip into an empty workspace; assert metadata and readContent bytes. Add SAF-created artifact case, and prove unrelated workspace files stay out.

### SS-02 [P1] Independent preserve toggles break shared chat_images ownership

- Location: `app/src/main/java/app/amber/core/sync/core/SyncArchiveManager.kt:460-462,516-523,603-606`.
- Production ownership: `FilesManager.kt:451,482` creates conversation-scoped output under `chat_images/<conversationId>`; `ImageGenerationRepository.kt:47-58` returns these files for chat; SyncArchiveManager's `:1304-1307` explicitly documents persisted message links to them. Gallery also references generated paths.
- Trigger A: EVERYTHING with preserveConversations=true, preserveGenMedia=false (UI toggles independent). Existing chat rows remain, but chat_images is replaced by archive files; existing chat images disappear.
- Trigger B: preserveConversations=false, preserveGenMedia=true. New chat rows are imported, but their image files are skipped; imported chat links break.
- Recheck: UPLOAD is correctly tied to conversation preservation; CHAT_IMAGES cannot exclusively belong to gallery because actual chat production paths store persistent references to it. Both default=true works for current chats; the two mixed combinations are defective.
- Minimal repair: implement shared-root ownership policy for mixed combinations: retain files required by preserved consumers and import files required by replaced consumers. Test all four toggle combinations, rather than merely moving CHAT_IMAGES to the other single flag. Whole-root merge in mixed cases is a small viable policy if it explicitly preserves local files on collisions.
- Regression: different local/archive conversation IDs and generated images; verify surviving/imported message image URIs resolve in both mixed combinations, plus full replace and full preserve.

### SS-03 [P2] Restored/unreadable device-key ciphertext blocks all new DEVICE_BOUND backups

- Location: `app/src/main/java/app/amber/core/sync/core/DeviceBoundBackupKey.kt:24-25`; `core/settings/src/main/kotlin/app/amber/core/settings/secret/SecretStore.kt:101-104`.
- Trigger: Android cloud backup/device transfer includes every SharedPreferences file (`backup_rules.xml:5-7`, `data_extraction_rules.xml:6-8,27-29`), including `amberagent_backup_device_key`. Android Keystore key is not exported by these rules. On reinstall/new device, ciphertext descriptor exists but decrypt returns null.
- Evidence: getOrCreate calls store.create when read is null; create rejects an existing stable key with `Secret already exists`. UI defaults to DEVICE_BOUND and every production exporter calls createArchiveFile, which calls this method. User cannot recreate this private preference through settings.
- Recheck: old device-bound archive rejection is correct; creating a new backup on the new device must work. Also current unsynchronized read/create can race if multiple exports begin together.
- Minimal repair: synchronize creation in DeviceBoundBackupKey and upsert a newly generated secret if unreadable, only on new-export getOrCreate. Keep current() read-only so old device archives still reject. Excluding this device-only preference from automatic backup is reasonable, but should not be the sole recovery for installations already carrying it.
- Regression: cipher that decrypts stored descriptor to null; getOrCreate succeeds, current reads the replacement, second invocation returns same key. Concurrent callers receive same key. Old archive still fails after replacement.

### SS-04 [P2] Write probe overwrites and trashes a pre-existing user file

- Location: `feature/icloud/src/main/kotlin/app/amber/feature/icloud/ICloudDriveManager.kt:119-126,333`.
- Trigger: configured vault already contains `.amberagent_probe.md` (user-created content or left by a prior interrupted probe); settings write-probe action runs.
- Evidence: fixed PROBE_FILE; client.writeText(overwrite=true) trashes original node before uploading probe text; successful read-back then deletes new node. User's file no longer exists at its original path. This UI probe is a capability check, not the approved icloud_write replacement action.
- Recheck: iCloud tool explicitly describes trash-before-replace semantics and asks approval for overwrite; this probe bypasses that context. A capability probe does not need to mutate any existing file.
- Minimal repair: unique randomized probe filename, overwrite=false; delete only after own create succeeds; finally cleanup on read failure (preserving cancellation semantics). No retries or general rollback framework required.
- Regression: MockEngine/fake drive with pre-existing legacy probe file; run new probe and assert original bytes and ID untouched, distinct newly created file read/deleted, and failed read cleans up only the new file.

### SS-05 [P2] icloud_read advertised chunking fails on ordinary large UTF-8 files

- Location: `app/src/main/java/app/amber/feature/tools/ICloudDriveTools.kt:131-138`; `feature/icloud/src/main/kotlin/app/amber/feature/icloud/ICloudDriveClient.kt:399-420`.
- Trigger: icloud_read(path, start_char=0, max_chars=65536 default) on a 300KB ASCII markdown file, below the 4MB global read limit.
- Evidence: tool passes maxBytes=(startChar+maxChars+1)*4 = 262148; client downloads the entire file and hard-errors when total bytes exceed maxBytes. Tool never obtains content, so no truncated/next_start_char output can be produced. Smaller custom max_chars makes failure easier. No server range or prefix-read exists.
- Recheck: protecting a 4MB hard file cap is reasonable; binding it to requested output slice contradicts chunked-read API. This is reproducible without real iCloud credentials using HTTP mocks.
- Minimal repair: pass MAX_ICLOUD_READ_BYTES as the file download limit and keep max_chars solely as output slicing. This reuses the current bounded full-read implementation and avoids adding range/stream abstractions.
- Regression: 300KB ASCII file yields 65536-character first slice and next_start_char; following slice works. File >4MB still rejects. Unicode slicing behavior preserved.

### SS-06 [P2] Failed payload extraction/validation leaves large decrypted restore-stage directory

- Location: `app/src/main/java/app/amber/core/sync/core/SyncArchiveManager.kt:446-467,554-587,655-656`.
- Trigger: an authenticated legacy/corrupt backup contains accepted payload preview, large table/file entries, then missing required settings/table or malformed settings JSON; alternatively an entry-limit or disk-write error during extraction.
- Evidence: temporary stage directory is created at 446, while cleanup try/finally begins only at 587 after extraction, missing-table validation and settings decode. Failures above 587 bypass stage delete. Verification.cleanup only deletes encryptedPayloadFile/payloadFile, not this stage. Retry repeats the leak (up to staged table/file limits).
- Recheck: normal successful and application-stage failures clean correctly. This finding concerns earlier parsing/validation failures and needs only moving the existing try/finally boundary; no startup sweeper/fallback is justified.
- Minimal repair: wrap all operations after stage-root creation in existing try/finally, including extraction and validation.
- Regression: existing SyncArchiveManagerIntegrationTest malformed-table/missing-entry archives; snapshot cache restore-stage directories before/after failed apply, assert no added stage directory or plaintext files remain. Include extraction limit exception.

## Rejected / by-design observations

- iCloud overwrite=true moves the old node to trash before new upload. A failed upload loses original-path availability, but `ICloudDriveTools.kt` explicitly documents this replacement contract and requires approval. Do not invent new undocumented iCloud rename/rollback API during this task. The fixed write probe is independently defective (SS-04).
- iCloud node_ref does not bypass configured-vault path resolution: resolveNodeWithHint resolves the path first and compares IDs only to describe match level. Forged IDs alone cannot read outside vault.
- Workspace sync intentionally does not propagate deletions and refresh intentionally preserves mirror-only files; the returned sync policy states this. These are not sync bugs.
- CONFIG_ONLY intentionally restores provider settings only, leaves OAuth snapshots/tables/files alone; not a missing general restore feature.
- Secret refs are merged by SettingsAggregator.restoreSecretRefs, not replaced wholesale; suspected CONFIG_ONLY unrelated credential wipe rejected.
- Legacy S3Sync/WebDavSync restore helpers are not wired to any examined production UI or DI call; their old atomicity/raw-DB-copy concerns are not counted as active production bugs.
- Full settings backup intentionally exports only secret references/masks for provider credentials; old device-bound archives intentionally cannot be restored on another device.
- Interrupted restore journal recovery is currently invoked only during the next restore, not startup. This is a remaining lifecycle limitation worth considering separately, but the requested review should not casually add broad app-start recovery without ownership/startup-order verification.

## Suggested repair phases within this track

1. Local focused fixes: SS-03 + SS-06. Device key unit tests and restore failure cleanup integration tests, then independent subagent review.
2. Restore dataset correctness: SS-02 + SS-01. Focused row/file round-trip and toggle combinations; inspect old archive compatibility and stable artifact ownership; independent review.
3. Experimental iCloud safety/read correctness: SS-04 + SS-05. MockEngine tests; preserve explicit tool approval/replacement behavior; independent review.

No new retry/fallback framework, arbitrary whole-workspace backup, or dead-code removal is proposed.

## Phase 2 implementation status

All six root-confirmed RED findings now have focused production fixes. Final implementation/compatibility/collision policy and the 12 prepared regression tests are documented in `results/phase2.md`. Root owns Gradle GREEN validation and the independent phase review; this report does not claim those checks passed yet. Artifact restore follows the approved stable internal body store and does not project into external SAF.
