# Phase 1 — Database fixes

## Implemented

- ST-01: ChatRdbRepository retains the SQL LIKE coarse query, checks actual message plaintext before choosing the highest node_index per conversation, and carries only that chosen plaintext into the snippet stage. Recency ordering and result limit remain in the existing conversation-query path. Empty/whitespace query and nonpositive limit now match the domain helper.
- ST-02: Both RDB adapters clear pending initialization with symmetric `then(success, failure)` cleanup instead of an abandoned rejecting `finally` Promise. The actual initialization Promise is still returned, so DeepRead callers receive the original failure and later calls retry. Chat's deliberate degraded-memory read policy and write rejection remain.

No source changed outside the two owned adapters and the focused new `rdb_repository.test.cjs`. Existing worktree `RdbRepository.inputSourceUrls` change was preserved.

## Verification

Before source fix, focused 5 tests: 1 pass / 4 fail. The failures reproduce discriminator/metadata overshadow, trimmed-query mismatch, and process-level unhandled initialization rejection. Chat initialization retry baseline passed.

After fix, focused tests: 5/5 pass. With existing domain persistence tests: **16/16 pass**.

```sh
TSX_TSCONFIG_PATH=harmony/chat/tsconfig.json node --import ./harmony/chat/node_modules/tsx/dist/loader.mjs --test harmony/chat/src/test/rdb_repository.test.cjs harmony/chat/src/test/persistence.test.ts
```

Evidence: `phase1-storage-tests.log`. Tests transpile actual adapter source and mock only SDK query/init ports; real message plaintext and persistence domain helpers are used. Initialization error is probed in an isolated child process, and explicitly verifies no unhandled rejection after the caller catches, original failure retained, second getRdbStore attempt made, and Chat degraded flag cleared after recovery.

Root coordinates independent phase review, ArkTS lint, HAP build, and any device validation. These host tests do not claim real-device relationalStore acceptance. ST-03/ST-04 and degraded backup candidate were sent to root for separately owned backup repair.
