# Round 2 persistence / media audit

Read-only audit packet B against the shared dirty tree. The audit created only result artifacts, without changing production or regression-test sources. Five owner-level findings are ready for root secondary review; the existing 26 repaired findings were checked as prior context, not counted again.

## P2: FileStore publishes successful partial I/O

- Owner: `harmony/entry/src/main/ets/platform_impl/FileStore.ets:68`, `:82`, `:97`, `:109`.
- Reachable non-novel callers: `harmony/deepread/src/main/ets/council/manager.ts:667` appends JSONL transcript records; `harmony/deepread/src/main/ets/agent/playbook.ts:135`, `:178`, `:218` persists playbooks. Byte operations additionally serve novel file snapshots, which this packet did not audit at the repository level.
- Contract: FileStore operations promise complete supplied content, or failure. These four operations use asynchronous `fileIo.read/write`, whose API returns actual bytes read/written as `Promise<number>`. No finding about synchronous `readSync/writeSync` is inferred from this probe.
- Actual-source reproduction: an SDK-contract port returning positive short counts causes `writeText` and `appendText` to resolve with only `{"m` written from a complete JSONL record; `writeBytes` resolves after 3/9 bytes; `readBytes` returns 9 bytes with a fabricated six-zero suffix after reading only 3. These are corrupted successful results, not merely dropped log warnings.
- Primary evidence: local SDK `/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/api/@ohos.file.fs.d.ts:5543`, `:7759`, `:11351`, `:11451`; official CLI FAQ `FAQ/本地数据和文件/本地文件管理/fs_write返回的长度和本身content长度不一致/faqs-local-file-manager-39` says the write return is actual bytes and differs from UTF-16 `String.length`. Current [OpenHarmony WriteCore](https://raw.githubusercontent.com/openharmony/filemanagement_file_api/master/interfaces/kits/js/src/mod_fs/properties/write_core.cpp) and [ReadCore](https://raw.githubusercontent.com/openharmony/filemanagement_file_api/master/interfaces/kits/js/src/mod_fs/properties/read_core.cpp) return the native libuv result without equating it to requested size; this supports, but does not replace, the API 12 SDK contract.
- Minimal fix: inside this adapter only, encode text as UTF-8, complete writes by byte count, and complete reads up to the stat size or reject early EOF. Omitted offset follows the existing descriptor position. Slice buffers for unwritten/unread bytes; `offset` addresses the file, not a source-array offset. Do not add atomic replacement, retries after failure, or a cross-repository framework.
- Suggested regression: extend a FileStore source-port test with byte-sliced Unicode, 3-byte partial progress, zero progress, short read/early EOF, and cleanup on error. Existing `file_store_lock.test.cjs` gives the source-loading pattern.
- Limit: corruption is reproduced against the real adapter and a legal short-count port; physical-device low-disk/read interruption was not exercised.

## P2: generated-image save/share still hits the default 5 MiB HTTP limit

- Owner: `harmony/entry/src/main/ets/platform_impl/ImageSourceSupport.ets:56-59`.
- Production callers: `GeneratedImageCarousel.ets:35` and `:93` pass the original generated image URL into `shareImage` / `saveImageToAlbum`; both call `prepareImageSource`. The fullscreen preview may already have a media-cache URL, but carousel actions do not need to open that preview first.
- Trigger: a valid 6 MiB remote PNG. The source request does not set `maxLimit`, so API 12 fails before source preparation, with HTTP error 2300023 under the SDK 5 MiB default. Both save and share fail even though the image lies within the product's existing 25 MiB media-image allowance. This is distinct from repaired MediaCache R09 because it is a separate production downloader.
- Primary evidence: SDK `@ohos.net.http.d.ts:498-512` documents default 5 MiB, option since API 11; `MediaCache.ets` and `media_cache.test.cjs` now explicitly use 25 MiB.
- Minimal fix: give this image download the same explicit 25 MiB limit, with a clear failure beyond the bound. No new download abstraction or retry policy is needed.
- Suggested regression: actual-source ImageSourceSupport port test that respects the platform default, successfully prepares a 6 MiB PNG once explicit limit is present, destroys HTTP requests, and preserves failure cleanup. File/data source behavior remains covered by existing behavior.
- Limit: actual source plus SDK-contract HTTP port, not a provider or physical-device test.

## P2: document selection silently swallows genuine import failures

- Owner: `harmony/entry/src/main/ets/pages/ChatPage.ets:2373-2393`.
- Production path: attachment file button -> DocumentViewPicker -> readUriBytes. The picker allows ordinary local document selection; readUriBytes intentionally rejects files above 10 MiB at `:2691`.
- Reproduction: selected `large.txt`, stat size 11 MiB -> `readUriBytes` throws the intended size error -> `pickFiles` catches it under the comment `用户取消选择`, leaving `pendingDocs=[]` and `errorMsg=''`. An unreadable document has the same result. The limit itself is valid; suppressing its error is the defect. In a multi-select batch, one failure also aborts all later selected files without feedback.
- Minimal fix: distinguish picker completion/cancellation from per-file import errors, report file/size failures using the existing error surface, and allow later selected files to import. Preserve pickerToken stale-callback guards. No background work or new state machine.
- Suggested regression: extracted real page methods with three selected files (readable / oversized or unreadable / readable), verify both readable attachments and visible failure; cancellation with empty URIs should remain silent; stale token must not mutate the current composer.

## P2: DAV directory text is interpreted as URL query/fragment

- Owner: `harmony/chat/src/main/ets/chat/sync_webdav.ts:59-60`.
- Production input: SettingBackupPage's user-entered `davPath` is passed as config.path. Filename segments already use encodeURIComponent, while this directory string does not.
- Actual-client/local-HTTP reproduction: `backups #1` produces MKCOL `.../dav/backups #1` and PUT `.../dav/backups #1/backup.abin`; both arrive at the server as `/dav/backups%20`, because the fragment hides the suffix and filename. `backups?2026` turns the filename into query data.
- Minimal fix: split directory on `/`, encode each segment, and rejoin. Preserve existing base URL and exactly-once filename handling.
- Suggested regression: extend `sync_webdav.test.ts` with `#`, `?`, `%`, Unicode and nested paths through the actual client, assert URLs and received paths as appropriate.

## P2: malformed backup manifest is published as renderable preview

- Owner: `harmony/chat/src/main/ets/chat/sync_snapshot.ts:551-559` (`inspectSyncArchive`); actual consumer `harmony/entry/src/main/ets/pages/SettingBackupPage.ets:545`.
- Production input: `SettingBackupPage.doPickRestore` at `:281-296` accepts `.abin`/`.json` selected by the user, calls inspectSyncArchive, and assigns its returned preview to UI state. WebDAV restore shares the same inspector.
- Actual reproduction: a 95-byte archive with manifest.json containing only `{"archiveVersion":2}` and an existing empty payload.bin passes inspection. The exact RestorePreview expression `preview.manifest.deviceLabel.length` then throws TypeError after the importer try/catch has finished. This is a damaged-file import boundary that currently publishes invalid state, not a hypothetical stricter format preference.
- Minimal fix: validate the manifest shape and typed fields used by preview/restore before returning a preview, rejecting unsupported/malformed values with a readable import error. Use a small explicit validator and preserve legitimate legacy archive versions; no generic schema system or crypto-format migration.
- Suggested regression: malformed manifest entries (missing/null/wrong-typed deviceLabel and restore-critical fields) must reject inspection; valid generated v1/v2 archives still preview and restore. No need to simulate ArkUI to test the inspector boundary.
- Secondary verification: nested backup auditor and this packet owner both replayed the domain function plus exact page expression. Device process termination was not tested and is not claimed.

## Reproduction commands and scope

```sh
node .workflow/comprehensive-review-round2-20261018/results/persistence-media-probe.cjs
harmony/chat/node_modules/.bin/tsx --tsconfig harmony/chat/tsconfig.json .workflow/comprehensive-review-round2-20261018/results/persistence-backup-probe.ts
```

The first script transpiles and executes ImageSourceSupport and FileStore, and extracts actual ChatPage picker/read methods without reimplementing their bodies. The second executes actual backup/WebDAV functions and records requests from a localhost HTTP endpoint. Backup findings were independently audited by a nested agent and replayed by this packet owner.

Covered: ChatRdbRepository serialization/transaction/delete/sweep, ConversationBlobStore write/read/remove, FileStore adapter, AtomicJsonFile, ManagedFileStore cleanup references and orphan safety, document materialization/preview/read bridges, image source/album/share/export bridges, video/media cache, local backup/restore and domain WebDAV URL handling. Excluded: novel repository/scheduler semantics, provider runtime, memory/search, Smart UI, actual filesystem capacity/device/provider/DAV acceptance.

Rejected/by-design: (1) MediaCache.utimes is synchronous void and accepts milliseconds per SDK, so missing await is not a bug. (2) Zero/negative conversation window sizes intentionally yield an empty window in both domain memory and RDB implementations; the contradictory private comment alone is not a defect. (3) deleteChatFiles rejects upload traversal segments in its domain predicate; a platform prefix check alone does not create a reachable arbitrary-delete path. (4) Cache eviction/expiry is designed as bounded best-effort caching; unproven active-video pinning is not a correctness finding. (5) Blob source integrity cannot reconstruct bytes lost before the prior fix; no speculative migration or checksum framework proposed. (6) Backup document attachment file-tree coverage, selected provider credentials and deferred cross-platform KDF are known disclosed scope/behavior, not new defects. The earlier sub-audit note that excluded malformed manifest from a four-item batch is superseded by the root's final request to retain it as the fifth candidate for secondary review.
