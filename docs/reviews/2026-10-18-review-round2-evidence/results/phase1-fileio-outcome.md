# Phase 1 FileStore IO integrity

Confirmed genuine defects in the actual `OhosFileStore` implementation: `writeText`, `appendText`, and `writeBytes` treated a successful partial write as complete; `readBytes` ignored the actual count and returned zero-padded content after a partial read or early EOF. This affects the shared persistence port used by council JSONL transcripts and novel/workspace/recipe files.

The local official `@ohos.file.fs` reference (`phase1-fileio-official-docs.txt`, read at lines 1681-1701 and write at 2066-2086) and the SDK declarations (`@ohos.file.fs.d.ts`, read at line 2664 and write at line 3557) confirm that both operations return actual byte counts. The `offset` option refers to file position, not a buffer position; omitted options continue from the descriptor position. Existing repository `AtomicJsonFile.ets` already follows the same write contract.

Changed only `FileStore.ets`, new `file_store_io.test.cjs`, and one fixture import in existing `file_store_lock.test.cjs`:

- A single private full-write helper is shared by all three write methods. Text is encoded once as UTF-8; byte slices preserve multibyte content and Uint8Array view boundaries.
- Reads continue until the stat-reported size is filled. No progress before completion or an impossible byte count becomes an observable error rather than a fabricated complete file.
- Existing create/truncate/append behavior and descriptor cleanup remain in their owners. Existing WIP locking methods were preserved.

Validation:

- `phase1-fileio-red.log`: tests run against the pre-repair actual source; 8 failures reproduce truncation, zero padding, and missing failure propagation. Empty/missing behavior already passed.
- `phase1-fileio-green.log`: focused IO and pre-existing locking tests pass. Checks include UTF-8 split inside multibyte characters, append preservation, Uint8Array subviews, multiple short reads, stalled writes, early EOF, descriptor closure, empty files, and missing files.
- `phase1-fileio-novel-regression.log`: existing novel repository/storage/restore regressions pass, including workspace swap recovery, opaque bytes, receipt/CAS behavior, and shared locking.
- `git diff --check` on the owned files passed.

Per assignment, ArkTS lint, Harmony build, signing, installation, and emulator/device acceptance are deferred to the parent integration phase. No storage data, permissions, commits, or remotes were changed.

## Independent-review correction: empty text encoding

The phase reviewer found the first fixture did not model the official `util.TextEncoder.encodeInto('')` contract: the SDK declaration `@ohos.util.d.ts` lines 485-496 documents that an empty input returns `undefined`. The initial implementation therefore introduced a real `writeText('')` / `appendText('')` TypeError after opening the file. The fixture now returns `undefined` for empty input; `phase1-fileio-empty-red.log` captures the actual-source failure.

The two text call sites now skip encoding/writing when content is empty, after opening the file, so creating an empty file, truncating existing text, preserving existing append content, and closing the descriptor retain the prior behavior. No generic fallback or encoder abstraction was added. `phase1-fileio-empty-green.log` records all IO and lock tests passing with the corrected mock. The empty-file test now also checks truncating nonempty existing text and appending empty text to an existing file. `git diff --check` passed again.
