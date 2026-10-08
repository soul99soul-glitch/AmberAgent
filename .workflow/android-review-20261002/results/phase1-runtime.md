# Phase 1 Runtime

Ownership: app novel Runtime/Tools, NovelMarkdownWorkspaceViewModel and focused tests. No feature-storage edits. Other agents' WIP preserved.

## Red regressions prepared

- `NovelTurnOutputRecoveryTest.cancellingAfterTwoWritesToANewFileDeletesItAndRetainsAuthorOutput`: real Launcher/Agent/Runtime, two free writes to new draft, actual stop, assert no orphan and author output retained.
- `NovelTurnOutputRecoveryTest.providerFailureAfterTwoWritesToANewMaterialDeletesIt`: real Launcher/Agent/Runtime, new character file twice then provider error, assert original public tree.
- `NovelMarkdownWorkspaceViewModelTest.interactive discussion sends previous branch dialogue once and audits stay isolated`: real VM/Launcher/Agent/Runtime with recording fake kernel, two rounds and isolated whole-book audit, foreign branch secret exclusion and exact message counts/order.
- `NovelMarkdownWorkspaceViewModelTest.chapter save queued before restore cannot overwrite the imported chapter`: follows existing queued-branch/undo test mechanism; same project/path restore replaces manuscript before action executes; assert content, durable ledger, and callback unchanged.
- `NovelMarkdownWorkspaceViewModelTest.accepted send with a session write failure reports an error instead of escaping its coroutine`: accepted send through VM, deliberately invalid sessions parent forces actual IO failure, assert error UI, busy clears, no uncaught coroutine failure. Original fixture filesystem restored in finally.

Root red execution: `/tmp/amber-review-phase1-runtime-red.log`, 24 tests / 5 failed. Both new-file rollback tests, interactive history, queued chapter save, and session persistence UI-error regression failed on the original implementation; compile succeeded. NR03/04 are now accepted by root after reproduction.

## Minimal production fixes

- NR01: `rememberPrevious` now checks key presence, preserving the first null preimage.
- NR02: optional empty-default history in `TurnRequest`; only interactive `send` projects its existing branch dialogue through the small `NovelMarkdownDiscussionHistory.kt`. Current input excluded by stable ID; audit/interrupted/other branch records are excluded. Specialized requests retain empty history.
- History integration boundary: Runtime reads Text/Reasoning only from assistant messages following the current final USER, so initial kernel snapshots cannot emit or persist the preceding reply. Added zero-new-output stop regression and failed-second-round/third-followup order regression.
- NR03: chapter save reuses existing `runAuthorEdit`, freezing restore epoch and retaining full content/plot/unresolved/canUndo refresh plus success callback behavior. Removed 40-line duplicate save lifecycle.
- NR04: send catches ordinary exceptions locally into the existing error UI; cancellation still propagates, and its busy cleanup remains epoch-aware. No retry or IO fallback added.

`git diff --check` passes. No Gradle run by this agent. Production/test edits are complete and awaiting root-owned green execution and independent phase review.

## First combined green follow-up

Root `/tmp/amber-review-phase1-green-next-red.log` confirmed new NR01/NR02/NR04 regressions green. NR03 content/tree/ledger/no-callback assertions all passed; only busy=false failed because the synthetic fixture called the low-level RestoreBoundary without the production RestoreBridge reload lifecycle. The fixture now uses the actual SyncRestoreWriteGate/RestoreBridge and waits for its post-restore reload, retaining every original content/ledger/callback assertion. Production intentionally does not let an old author coroutine clear the new restore's busy state.

The existing send/stop test also rejected its initial send before any model execution. SettingsAggregator.update immediately publishes a requested value, while its cold raw-flow projection can subsequently replace that value; the fixture's old first() could mistake immediate publication for completed initialization. Fixture now writes a provider URL ending in `/` and waits for the canonical raw-flow URL normalization plus matching model/provider/id; no arbitrary delay or retry. Initial send assertion includes error/loading/busy to expose a different cause if still present. Awaiting root rerun.

MaterialActions creates UUID filenames and hashed decision filenames; discarded operations use the bound branch and original chapter path. Stronger hidden-segment path validation requires no app-level fallback or additional design change in these owners.

## Independent phase review follow-up: NS4 caller

Reviewer found the character-proposal filename caller passes `slug + .md` into reservedPath: dot-prefixed names now fall back to `character` without extension, and ordinary duplicate names already allocate `alice.md-2` without a visible extension. Prepared two actual VM/Runtime/tool-write regressions (dot-prefixed name and same normal name twice); they assert visible Markdown directory/catalog entries, preserved display titles, and unchanged first-card raw content.

Root red `/tmp/amber-review-phase1-final-boundaries-red.log`: 24 tests / 2 failed, only these two character regressions. Previous four Runtime/VM issues and fixture lifecycle/settings corrections passed. Root accepted original duplicate-card invisibility as NS9, with the NS4 dot-prefix caller compatibility regression as the same allocation fix. Authorized three-line production fix now allocates bare basename against existing basenames, then appends `.md`; display name and existing first-card content untouched. Awaiting root final phase green and independent review.
