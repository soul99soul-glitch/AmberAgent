# Storage / native audit

Review base: HEAD 5ea51f7a plus current dirty and untracked worktree. Read harmony-arkts before reviewing. Source inspection covers Chat/DeepRead RDB, backup, managed files, atomic JSON, document/image import/export, Workspace/Preferences stores, EntryAbility/DI, SSH/Python/Mosh and loopback native lifecycle. No production sources modified during audit.

## Confirmed problems

| ID | Priority | Trigger and impact | Evidence / suggested test | Design verification |
| --- | --- | --- | --- | --- |
| ST-01 | P2 | `ChatRdbRepository.ets:492-510 (audit baseline)`: latest JSON LIKE candidate is selected before plaintext filtering. Searching `text` matches every text part's JSON discriminator; a recent message without the word hides an earlier real body match. | Execute actual adapter with two query rows: old body `text needle present`, new body `new answer`; actual result `[]`, real domain `searchMessageHitsIn` returns the conversation. Preserve SQL coarse filter, choose latest after plaintext match. | Latest *real* hit is documented contract. The documented quote/escape coarse-filter limitation does not explain this loss for ordinary `text`. |
| ST-02 | P2 | `RdbRepository.ets:177-186 (audit baseline)`: DB initialization rejects; `attempt.finally` returns another rejected Promise that is discarded. A caller catching `get()` still leaves an unhandled rejection. | Actual adapter with failing `getRdbStore`: caller catches `rdb-init-failure`, process `unhandledRejection` receives the same error. Verify rejection is handled and next operation genuinely retries. | Retrying DB initialization is intentional; additional unhandled rejection is accidental. Chat adapter has same cleanup pattern, but its initializeStore catches failures, so this exact failure currently affects DeepRead adapter. |
| ST-03 | P2 | `SettingBackupPage.ets:136-141, 234-237`: single `writeSync` ignores actual byte count. Short write publishes truncated backup and reports successful export/download. | Actual doExport extracted unchanged into host class: 128-byte archive, writeSync returns 3; status still `已导出`. Official local `js-apis-file-fs` says writeSync returns actual written bytes. | Success for incomplete backup is not intentional. Existing AtomicJsonFile and native-resource deployment already handle short writes. |
| ST-04 | P2 | `SettingBackupPage.ets:136-139, 234-237, 262-266, 295-299`: descriptor closes only on successful read/write. Read/stat/write error leaks descriptor; repeated failures consume available fds. | Actual doExport with writeSync throwing disk_full produces error status and zero close calls. Add exception fixtures for each read/write entrypoint or one shared bounded I/O helper used by them. | Reporting failure is intentional; failing to release an opened resource is not. |

## Additional confirmed storage mismatch

`ChatRdbRepository.searchMessages`: zero/negative limit currently appends one result before testing the limit, while the real domain helper returns `[]`; empty/whitespace search is also not normalized consistently. Focused database fix should match helper empty/limit semantics, with tests, without expanding query to every conversation.

## Candidate requiring main-agent ownership decision

`SettingBackupPage.ets:93-102` packs conversation reads that silently return empty fallback during ChatRdb init failure, without checking `chatStorageDegraded`. A local export/upload can therefore succeed with zero conversations despite unread existing DB data. The actual page fixture confirms export accepts empty read results; actual ChatRdb code confirms init failure returns empty memory and sets degraded. This needs a real-degraded adapter + page fixture before being counted as a separate accepted issue. Managed orphan cleanup already refuses to use degraded reads as destructive evidence.

## Rechecked / excluded candidates

- Python expensive/blocking C calls cannot be forcibly cancelled; README explicitly documents cooperative bytecode cancellation, no hard memory/OS sandbox claims. No fix proposed.
- Backup excludes attachment tree/memory/managed-files/compact/favorites and cross-platform KDF interoperability is pending; documented product scope, not a newly discovered corruption bug.
- Tail window limit<=0 returns empty (despite an older comment implying full): real memory implementation matches it, so no behavior change proposed.
- Backup restore rejects duplicate node IDs globally (`requireUniqueNodeIds`) and has one transaction; candidate duplicate-ID overwrite was ruled out.
- Generated-image registry fire-and-forget is explicitly documented Android-equivalent behavior. No change proposed.
- Shared atomically written JSON temp names are safe at audited production callers that already serialize writes; no blanket locking layer proposed.
- SSH request-id reuse cleanup race is possible at native raw API, but app generates unique request IDs and no reachable product trigger established; excluded from accepted list.
- Workspace manager reset already invalidates cached RecipeStore. Candidate stale recipe root was ruled out.

## Reproduction

Read-only disposable probe: `/tmp/amber-storage-review.cjs`.

```sh
TSX_TSCONFIG_PATH=harmony/chat/tsconfig.json node --import ./harmony/chat/node_modules/tsx/dist/loader.mjs /tmp/amber-storage-review.cjs
```

Official local SDK doc was read with `harmony/scripts/devecocli.sh docs read API参考/Core_File_Kit_文件基础服务/ArkTS_API/ohos_file_fs_文件管理_/js-apis-file-fs`; fileIo.writeSync returns actual written bytes, API 9+.
