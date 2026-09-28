# E11-B Council domain — source complete

- Ownership: only `harmony/deepread/src/main/ets/council/{models,runner,manager,validator,prompts,approval_wait}.ts`, one necessary `src/test/council_tools_e11.test.ts`, and this report. No index/Entry/UI/device/build changes; concurrent owners preserved.
- `models.ts` now exposes frozen `CouncilToolMode`, `CouncilSeatRunKey`, `CouncilSource`, `CouncilApprovalRequest`, `CouncilToolVerdict`, `CouncilToolSnapshot`, and `normalizeCouncilToolMode`. All messages/parts import the existing canonical `deepread/agent/message.ts`; there is no reverse Chat or Entry dependency.
- Runtime factory defaults old settings to OFF; input may explicitly override to search/full. Existing compare/debate is independent. Task/turn new fields remain optional in source types for old JSON/literals; parse/start/snapshot normalize actual tool mode, toolMessages and sources. Settings/model pool and input seats are copied; snapshot callers cannot mutate the live run or saved approval subjects.
- `runner.ts` optional `key/toolMode/onTools/requestApproval(request,signal)` and result `toolMessages/sources` match the frozen contract. Normal seats receive the real run/round/seat key; synthesis is explicitly OFF. The existing dynamic-seat planning caller is UI ownership and was notified to pass OFF.

## Actual approval lifecycle

- `manager.ts` exports `toolSnapshot(runId,round,seatId)`, `subscribeTools(runId,listener)` (immediate saved snapshot replay), and `submitToolVerdict(request,verdict)`.
- `onTools` only accepts the expected seat key and exact saved parent locator/part in pending state; it copies the snapshot and registers **every** pending waiter before publishing. This handles immediate UI decisions and multi-parent batches while C awaits sequentially.
- Locator tuple includes run/round/seat/conversation/message/partIndex/toolCallId plus C's exact saved subject hash. Verdict must match the current saved part; duplicate, stale child/hash, wrong locator, ended/cancelled run and non-pending subjects are rejected. Submit resolves that waiter only; it does not write approval state or execute a tool.
- C retains responsibility for actual durable seat save → emit → wait → reload/recompute subject → precise parent approval write → save → real continuation. Manager JSONL is an ordered archive, never an approval durability barrier. Its actual IO failures surface as result warnings rather than being silently treated as saved approvals.
- The exact same seat signal is required for the approval await. Root cancellation, seat deadline and total deadline retain the existing semaphore/parallel flow; one seat timeout/failure leaves peers running. Aborted pending waiters are removed; terminal snapshot messages/sources remain available while pending becomes empty.
- Text/tool callbacks check seat termination, seat/root signal and running run status. Late callbacks/decisions cannot reopen or mutate a terminal turn. Empty response or unresolved tool calls cannot count as completed.

## Evidence feedback

- Turns retain copied real tool messages and sources, including partial/failed/cancelled snapshots. JSONL turn records and report links include these fields.
- Response/final-position/synthesis prompts include bounded actual tool output and source URL/title/service, marked as data. Raw tool inputs are not inserted into these evidence summaries. Tool-capable seats no longer get the contradictory “you have no tools” system instruction; OFF behavior keeps it.

## Necessary verification

- Only one test group: `council_tools_e11.test.ts`; `/tmp/e11-council-manager-group.log`: 1/1 PASS, ~113 ms process duration. No old full suite or UI tests run.
- Covers a controlled source-save barrier before publication, all pending waiters registered before UI decisions, approved/denied/answered, forged locator/duplicate/stale child rejection, actual evidence/sources → court and JSONL, defensive snapshot copy, source-save failure without publication, peer survival, two seats × two pending cancellation, late callback immutability, seat timeout isolation, empty/unresolved failure and archive IO warning.
- This manager fixture models C's source barrier explicitly; it does **not** claim actual production FileStore/provider/dispatcher execution. C independently owns that real loop/store fixture.
- First actual group run was RED: immediate cancel from `onTools` left an already-rejected runner Promise unhandled (`Error: seat ended`). `raceCancel` now always attaches producer settlement before testing already-aborted state. Same group then GREEN. The cancellation callback array is copied before invocation so callbacks removing themselves cannot skip another waiter/cascade.
- DeepRead `tsc --noEmit --pretty false`: exit 0, `/tmp/e11-council-domain-types.log` empty.
- Exact six source files lint: exit 0, **0 errors / 597 warnings**, `/tmp/e11-council-domain-lint.log`. First attempt hit SDK-owned codelinter log EPERM; same command completed with required SDK log write authority.
- Independent read-only review requested from `/root/e2_review_domain_ui`; final review remains root's separate gate. Production model/provider, assembled full tool scope, signed app and device/UI acceptance are not claims of this domain report.

## C native-owner cleanup follow-up

- C/root confirmed that a save/hash/runner failure can leave a paused native package owner on the actual seat signal. Seat finally now sets `ended=true` and aborts **that same seat controller** on all terminal exits, including successful completion. Synthesis closes its own signal too; neither operation touches root/peer controllers or changes an already-appended result.
- Only the existing necessary group gained completed/failed terminal-signal assertions. Actual before RED: `terminal signal releases actual native owners` was false (`/tmp/e11-council-native-cleanup-red.log`). The narrow finally fix made the same group GREEN: 1/1 PASS, ~140 ms (`/tmp/e11-council-native-cleanup-green.log`), preserving the prior peer-isolation/cancel/source cases.
- Affected DeepRead types exit 0 (`/tmp/e11-council-native-cleanup-types.log`). Only changed `manager.ts` was relinted: exit 0, **0 errors / 380 warnings**, `/tmp/e11-council-native-cleanup-lint.log`. The other five unchanged source checks are reused, not rerun.
- C and the independent domain reviewer received the exact same-signal lifecycle change and new red/green evidence before integration.
