# Phase 1 independent review

Status: **PASS after one precise FileStore correction and focused re-review**. All four accepted findings pass this independent review; no blocker remains in the reviewed diff. The first review interception and its resolution are retained below. No production or checked-in test file was edited by this reviewer. Review compared task-owned changes in the existing dirty `FileStore.ets` against the task-start baseline copy; previously clean novel sources/tests were compared against HEAD. The Harmony ArkTS skill was loaded before review. A second independent read-only reviewer checked the FileStore API contracts and fixture behavior.

## First-review blocker: empty text writes throw after the file is opened (resolved)

Owner: `harmony/entry/src/main/ets/platform_impl/FileStore.ets:83` and `:131`, reaching `writeFully` at `:65`. Priority: P2; breaks supported empty-text persistence.

Trigger: `store.writeText(path, '')` or `store.appendText(path, '')` with the real Harmony TextEncoder contract. Active single-argument `util.TextEncoder.encodeInto9+(input?: string)` returns `undefined` for an empty string. The new helper immediately reads `content.byteLength` and throws `TypeError`. `writeText` has already opened with `CREATE | TRUNC`; append has likewise opened/created its target. Empty file creation/truncation should succeed. This is reachable through shared file persistence: repository snapshot assembly retains raw chapter content (`repository.ts:788`) and writes snapshot strings through the port without a nonempty invariant (`repository.ts:1310-1311`, `:1530-1531`).

Official local reference command:

```sh
harmony/scripts/devecocli.sh docs read API参考/ArkTS_方舟编程语言/ArkTS_API/ohos_util_util工具函数_/js-apis-util
```

The output's lines 1430-1450 document `TextEncoder.create12+`; lines 1458-1478 document the active single-argument `encodeInto9+`. Both the input table at line 1472 and return table at line 1478 explicitly state that an empty-string input returns `undefined`. This is distinct from `encodeIntoUint8Array9+` beginning at line 1489 and the deprecated two-argument `encodeInto` beginning at line 1527.

At the first review, the new test masked the regression: `harmony/entry/src/test/file_store_io.test.cjs:68` always returned a `Uint8Array`, using `Buffer.from('')` for empty input. A read-only reproduction replaced only this mock in memory with `text === '' ? undefined : new Uint8Array(Buffer.from(text, encoding))` and executed the existing test file through `Module._compile`. Result: 8 passed, empty/missing behavior failed at the production helper's `byteLength` access. The checked-in files were untouched by the reviewer.

Requested correction: preserve empty `CREATE`/`TRUNC`/`APPEND` behavior and avoid calling the encoder/helper for empty strings, or explicitly provide an empty byte array. Make the fixture honor the real empty-string encoder result; rerun the focused I/O and lock checks. This requires no fallback framework or extra state.

### Focused re-review result

The implementer added only `content.length > 0` guards inside the existing post-open `try` blocks (`FileStore.ets:83-85`, `:133-135`). Empty text still opens/creates/truncates as appropriate and reaches the unchanged `finally` close. The fixture now models the documented empty encoder result (`file_store_io.test.cjs:68-70`). Its empty-file test additionally checks truncating an existing nonempty file, creating an empty append target, appending empty text without replacing existing content, and descriptor closure.

Read `phase1-fileio-empty-red.log`, which records the corrected fixture failing against the earlier source, and `phase1-fileio-empty-green.log`. Independently reran `node --test harmony/entry/src/test/file_store_io.test.cjs harmony/entry/src/test/file_store_lock.test.cjs`: **11 passed, 0 failed**. Rechecked the final baseline diff and `git diff --check`: passed. No further source change, fallback, or broad test repetition was requested.

## Accepted findings verified

- **Short asynchronous I/O:** remaining-byte writes preserve UTF-8 and typed-array view boundaries; reads accumulate actual counts and expose early EOF/stalls; existing `finally` owners close descriptors. Official file.fs contracts in `phase1-fileio-official-docs.txt:1679-1701` and `:2064-2086` confirm descriptor-position default and actual byte counts. Apart from empty text above, no reachable new defect was found. Lock fixture change only accommodates the added import.
- **Ghostwrite stop/claim boundaries:** the original claim reaches every writer/reviewer/planner call; a fresh `safePoint` after asynchronous model resolution and validation precedes paid model start (`ghostwrite_runner.ts:366-396`). Paused/cancelled durable state returns without failure mutation; expired/replaced claims prevent start and cannot overwrite the current owner. Existing role routing, read-only tool profile, waiting-user handling, rewrite and commit semantics remain intact. The tests gate real asynchronous resolve/validate boundaries and assert zero starts, commits and failure writes rather than asserting source strings. Existing creation facade tests also continue to pass. Rerunning the audit's original real-repository pause probe produced `requestsAfterStop: 0`; its obsolete expectation of the old defect then failed as expected.
- **Per-branch public roundtrip:** exporter writes `stateSyncReasoningEnabled` to each `branch.md` (`workspace_interop.ts:458-462`); importer uses a present branch value and falls back to legacy `project.md` only when absent (`:178-181`). Both present fields retain strict boolean validation. The changed fixture actually differs between main=false and side=true, and public file parse/install/switchBranch checks compare restored author fields to each original branch. Native backup retention and legacy project-level fallback pass.
- **Visible waiting-system observation:** only polling eligibility includes `waiting_system` (`NovelPolishPage.ets:141-158`, `NovelWorkspacePage.ets:769-799`); the execution/control predicates still exclude it. Hide invalidates the workspace poll token, callbacks check visibility, and awaited project/status results are assigned only after the final lifecycle guard. The new tests execute extracted production methods with controlled job sources for waiting -> writing -> completed, paused/terminal stop behavior, scheduled callbacks after hide, and in-flight completions spanning hide/show. Background creation tests still preserve durable waiting and scheduler ownership.

## Independent verification and limits

Reran affected ghostwrite runner and creation, public exchange/interop, native backup, polish creation/background ownership, and actual-page polling suites together: **94 passed, 0 failed**. Ran `git diff --check` on reviewed changed tracked files: passed. Inspected the implementers' red logs showing the original short-I/O, stop-boundary, branch roundtrip and observation failures, then the green logs.

This review does not claim Harmony SDK build, signing, install, emulator screenshot, API 12 device I/O, real model/provider acceptance, or actual WorkScheduler execution. Those remain integration verification layers owned by the parent. No retry/compatibility machinery, unrelated dirty changes, or style-only changes are requested. The empty-text correction and its affected focused checks have completed re-review; other passed evidence was reused.
