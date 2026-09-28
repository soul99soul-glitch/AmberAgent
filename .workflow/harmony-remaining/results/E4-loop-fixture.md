# E4 Recipe loop integration fixture

Status: **PASS (3 focused tests)**.

Owned changes: `harmony/chat/src/test/recipe_loop.test.ts` only. Product source, DI, ChatPage, devices, and builds remain with root and the other phase owners.

## Evidence

Command, run from `harmony/chat`:

```sh
./node_modules/.bin/tsx --test src/test/recipe_loop.test.ts
```

Initial scoped run: **2 passed, 1 failed**; `/tmp/e4-recipe-loop-fixture-initial.log`. The first two tests passed their final assertions. After root fixed the third test's directory-observation failure, only that affected test was rerun:

```sh
./node_modules/.bin/tsx --test --test-name-pattern='same-turn import' src/test/recipe_loop.test.ts
```

Final affected output: **1 passed, 0 failed**; `/tmp/e4-recipe-loop-fresh-directory-green.log`. All three distinct test cases now have passing evidence; the two unaffected cases were not rerun after this focused source correction.

The fixture uses the real `runChatTurnWithTools`/`runToolLoopContinuation`, Recipe adapter, ordered Recipe batch, permission resolver/dispatcher, tool exposure, registry/search, wrapper generation, validation, and checkpoint runner. Store ports are deterministic fakes that JSON-clone persisted conversation snapshots; primitives and model streams are controlled fixtures. This verifies the loop integration without claiming filesystem, platform, or UI acceptance.

1. Manual parent starts at AUTO with a persisted checkpoint, executes the read step, pauses at write approval, and resumes successfully after approval. Model `streamText` calls remain **0** across both continuations. A missing manual parent fails before streaming, with no new primitive calls. The final saved parent equals the returned parent.
2. A mixed model batch pauses at the Recipe write approval before the ordinary write executes. After catalog replacement with a newer Recipe version, approval resumes the pinned old descriptor/hash/execution ID without replaying its read step. Ordinary `file_write` then executes under the existing run trust. Its writeback preserves the completed Recipe checkpoint in the saved conversation.
3. An approved import executes in the same turn, then catalog refresh makes `tool_search` expose the new wrapper's actual required numeric schema. The wrapper executes a primitive still hidden from the lazy model schema, returning `42`. Disabling and deleting the Recipe remove its schema and executable mapping in the next respective rounds; later calls fail. Repeating the original identical search arguments after deletion observes the fresh directory and exposes nothing. A final current-scope assertion keeps the same valid installed descriptor in the adapter while omitting its wrapper from the tool directory: the model call fails and primitive execution count does not increase.

## Focused correction

The initial third-case failure used identical `tool_search` arguments after the catalog changed. The loop's duplicate-call guard prevented that valid current-directory observation and stopped before the deletion assertions. Root added the pure observation tools to the existing repeatable-tools set. The final fixture keeps the original identical search arguments, and the affected test passes without bypassing the guard with different parameters. An intermediate parameter experiment was discarded. The current-scope assertion verifies root's separate scope fix directly without temporarily reverting it or claiming a pre-fix red run for that fix.

No extra test matrix, full suite, platform build, or device repetition was introduced.
