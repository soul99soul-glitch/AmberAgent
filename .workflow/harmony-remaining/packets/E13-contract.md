# E13 draft contract — novel cards and actual background progress

Draft for root decision, 2026-09-28. This preparation changes only this document. No production edit/test/build/device/account request is authorized by the draft. Authority: `results/E13-novel-background-design.md`, current source and the local official API12 documents below. Keep Harmony API12/V1 and two production owners with root-only shared integration.

## A: consume existing novel decisions and proposals

Own small new `NovelChapterVerdictCard.ets`, `NovelChapterPlanProposalCard.ets`, one local proposal projection helper if needed, and only their call sites in `NovelGhostwriteCard.ets`/`ChatMessageTools.ets`. No domain/repository/engine or new approval protocol.

Verdict card consumes existing `GhostwriteReview`, `GhostwriteCandidate` and `FrozenPlan` from the durable job; no new persisted verdict DTO. The actual model has `blocking`/`rewriteRequired`, not a `decision` field: show blocked first when blocking=true, rewrite when non-blocking+rewriteRequired, otherwise review passed. “Review passed” does not assert a chapter was committed; only existing job stage/receipt can establish inclusion. Preserve candidateId/digest and planId/digest provenance, chapterOrdinal, attempt+1, findings kind/code/message/location, rewriteInstructions/nextPlan and stateDelta as present. Keep existing pause/resume/retry/cancel actions and original shared ask_user answer card; no force-pass/include button or model request.

The sole new UI projection DTO is local Entry display data:

```ts
interface NovelChapterPlanPatchView {
  operation: 'write' | 'delete';
  path: string;
  content: string | null;
}
interface NovelChapterPlanProposalView {
  proposalId: string;
  patches: NovelChapterPlanPatchView[];
}
parseNovelChapterPlanProposal(part: UIMessagePartTool): NovelChapterPlanProposalView | null
```

Project only toolName `novel_workspace_write` with structurally valid whole input: nonempty proposal_id, nonempty patches, every patch a write with string content or delete with null content, and all patches target exactly the same `plan/this-chapter.md` or canonical `branches/<single-branch>/plan/this-chapter.md`. Preserve order and **every** patch, including repeated writes/deletes; do not hide approved side effects. Empty write string is a real write; delete means “清空本章计划”. Mixed paths/other files, unknown operations and bad JSON retain the original generic tool UI and raw input. UI projection does not normalize paths, choose an active branch or authorize a proposal; existing `workspaceToolPath`, fixed patch/CAS and receipt logic remain authoritative.

The original `UIMessagePartTool` remains the action target. Dedicated display calls only ChatMessageTools' existing onApprove/onDeny(toolCallId), subject to existing pending/canApprove/canDeny. AgentMessageRow supplies its existing `ToolApprovalPartLocator {messageId,partIndex}` to the original parent callback; NovelWorkspacePage.resolveNovelToolId → continueNovelTool → NovelCreation.continueTool remains the approval/denial/answer/save route. Do not reconstruct targets from proposal_id, displayed text or array position; do not bypass blank/duplicate tool ID rejection. History and active tail share the same route. `NovelSettingProposalCard` is a different material proposal and must not be used to write these workspace patches.

Use existing V1 theme/card conventions, 44vp actions, wrapping titles/paths/body and small expand/collapse state. Expanded review/plan content is fully readable, without maxLines truncation falsely labelled full text. Do not add navigation or a second transcript/polling source.

## B: one API12 task for aggregate Chat + Novel activity

Own `BackgroundGenerationKeepAlive.ets`, a small local measurement helper if needed and the Entry `NovelChatRunAdapter.ets` wrapper. The narrow raw observer addition in existing domain `novel_interactive_adapter.ts` is also B-owned if root approves it; it forwards existing ChatTurnDeps.onRawFlushSnapshot and does not alter generation/persistence. Root owns module.json5, EntryAbility and ConversationRunService integration. No provider/native engine refactor.

Freeze one shared platform reporting DTO, with explicit cumulative per-run values:

```ts
interface GenerationProgressSnapshot {
  source: 'chat' | 'novel';
  runId: string;
  generationActive: boolean;
  receivedChars: number;
  receivedBytes: number;
}
reportGenerationProgress(context: Context, snapshot: GenerationProgressSnapshot): void
setGenerationKeepAliveBackground(context: Context, backgrounded: boolean): Promise<void>
disposeGenerationKeepAlive(context: Context): Promise<void>
```

No DTO field is a percentage, guessed total or wall-clock increment. Key the small activity map by source+runId; snapshots contain monotonic received content totals for that run. `receivedChars` is the existing JS string-length unit (UTF16 code units), `receivedBytes` is UTF8 content bytes, **not HTTP wire-byte accounting**. Notification text says received content bytes with no user/title/prompt/secret/body. Aggregate only increments observed after the actual task was acquired; seed existing totals as the lease baseline and never publish foreground/history totals as newly received. End/waiting_user/cancel removes its own active entry; A finishing must not release still-active B, including mixed Chat/Novel. Preparation/connecting may establish an intended active generation with zero progress; paused, approval waiting, saved sessions and local-only VM/tool work never invent transfer progress.

**Required source correction to the pre-design:** NovelModelEvent.snapshot currently comes through InteractiveTurnRuntime.onUpdate; ChatTurn applies visual transformers before onUpdate, and the generic interactive content-length helper also includes tool outputs. Those snapshots cannot prove received content. Use a single optional observer on `NovelInteractiveAdapterDeps`, e.g. `onRawSnapshot(request: NovelModelRequest, messages: UIMessage[]): void`, forwarded from the existing `deps.onRawFlushSnapshot` before visual transformation. Entry consumes the request's real runId/projectId/history and model events for activity/terminal state. Synchronous wrapper subscription is retained so no early terminal is lost; waiting_user/completed/failed and cancel clear the exact run even when the page detached. This covers normal creation/ghostwrite/polish through the same existing model adapter.

Chat reports from current ConversationRunService.captureRaw before checkpoint throttling, not visualConversation or AppStorage.chatGenerating. `streamContentLength(last)` alone is insufficient: captureRaw presently accepts USER, and only measuring the last assistant would lose earlier tool-loop steps. Seed per-assistant-message baselines from the actual starting history; count only new model-provided assistant text/reasoning/tool name+input, excluding tool output, local wrappers, USER/STEER, approval/receipt/status changes and unchanged historical content. New message IDs/steps add their actual growth once; regeneration seeds its actual target as a fresh output, continuation retains existing content baseline, retry/reset does not make the lease count decrease or count old content twice. Measurement may observe existing provider raw/step hooks; it must not replace provider, accumulator, retry or transformer semantics.

Maintain a desired-background flag plus one serialized system operation tail/pending request. Root foreground sets desired=false **before** awaiting; a late start checks the latest desired flag and aggregate and immediately stops its acquired task if no longer needed. Store only the notification returned by that successful request. Serialize start/stop/publish so a stale completion cannot acquire a newer lease, publish to its ID or stop it. Stop success clears the running fact; failure retains an observable unknown/still-held fact and reports the real error rather than claiming stopped. Destroy invalidates pending progress work and requests final cleanup. Do not introduce a scheduler or fallback state-machine hierarchy.

API12 request must be `startBackgroundRunning(context, ['dataTransfer'], wantAgent): Promise<ContinuousTaskNotification>`; enum overload returns void and is unsuitable for this notification update. Root adds EntryAbility `backgroundModes:['dataTransfer']` alongside existing KEEP_BACKGROUND_RUNNING permission; declaration is required but not proof of successful grant/runtime verification. At API12 one UIAbility has one continuous task, so Chat and Novel share it.

Update the **actual returned notificationId**, returned slotType/contentType (matching LIVE_VIEW/SYSTEM_LIVE_VIEW), systemLiveView.typeCode=8 and `progress: {currentValue: receivedContentBytes, isPercentage:false}` with unknown maxValue omitted. Third-party apps update a system-created live view with the same ID; they do not create its channel independently or reuse ordinary generation-done notification ID 990001. A small at-most-once-per-second coalescer sends only actual changed progress; silence produces no increasing values. Observe request/publish/stop failures honestly; no background permission prompt loop.

## Greater-than-ten-minute boundary

Official DATA_TRANSFER guidance requires live-view progress and says first progress remaining unupdated beyond ten minutes cancels the task; API reference describes progress left unupdated for more than ten minutes. This is a consistency/transfer-progress rule, **not** a ten-minute renewable lease API. `updateBackgroundRunning` only changes task types; no API12 renew/extend operation was found in official docs/SDK. Do not run periodic stop/start to claim renewal, inject heartbeat percentages, infer eligibility from a publish Promise or use API15 cancel listeners/API20 task queries/API21 multi-task methods without guarded, justified compatibility work.

The documented NotificationProgress absolute-value fields are API11 and valid at API12, but the official DATA_TRANSFER example uses downloadTemplate percentage progress. Whether content-byte `systemLiveView.progress` updates satisfy this Harmony device's DATA_TRANSFER detector is an unresolved platform acceptance gate. Unknown LLM total forbids faking that template's percentage. Source may implement the supported absolute progress shape and record actual calls; it must not claim guaranteed indefinite keepalive or >10-minute success without evidence. A silent long request, user notification removal, system load or policy can still terminate/suspend activity; no synthetic recovery loop is added. SSH/Mosh/Python/JSVM background interrupt policies remain independent.

After authorized integration, root owns **one** real controlled HTTP/model content stream spanning >10 minutes, same actual notification ID and active system task, to establish the absolute-progress policy. If unavailable or not accepted, retain a clear pending/unsupported long-duration boundary and do not invent a fallback. This draft performs no such run.

## Evidence and necessary verification only

Current source anchors: GhostwriteReview/applyGhostwriteReview (`deepread/.../novel/ghostwrite.ts:60/470`); existing review UI (`NovelGhostwriteCard.ets:168`); parent locator/action (`AgentMessageRow.ets:391`, `NovelWorkspacePage.ets:988`); fixed proposal parsing (`creation.ts:1133`); current enum request/race (`BackgroundGenerationKeepAlive.ets:21/39`); Chat raw/aggregate (`ConversationRunService.ets:444/188`); Novel visual snapshot (`novel_interactive_adapter.ts:100/188`) and actual raw-before-visual callback (`chat_turn.ts:334`). Paths under `harmony/`.

This turn used docs search then full local docs read, and actual SDK declarations:

- `开发指南/Background_Tasks_Kit_后台任务开发服务/长时任务_ArkTS/continuous-task`; cache `/tmp/e13-c-continuous-official.txt:31/35/82`: DATA_TRANSFER progress and one UIAbility task limit.
- `API参考/Background_Tasks_Kit_后台任务开发服务/ArkTS_API/ohos_resourceschedule_backgroundTaskManager_后台任务管理_/js-apis-resourceschedule-backgroundtaskmanager`; cache `/tmp/e13-c-background-official.txt:849/1465`: type update and ten-minute boundary. [Official background API](https://developer.huawei.com/consumer/cn/doc/harmonyos-references/js-apis-resourceschedule-backgroundtaskmanager).
- `API参考/Notification_Kit_用户通知服务/ArkTS_API/notification/NotificationContent/js-apis-inner-notification-notificationcontent`; cache `/tmp/e13-c-content-official.txt:99/170`: same-ID live view and absolute NotificationProgress. SDK `@ohos.resourceschedule.backgroundTaskManager.d.ts:634/676`, `notification/notificationContent.d.ts:266/422` confirm API12/API11 signatures. [Official long-task guide](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/continuous-task).

Implementation verification is limited to one UI projection group (valid full-plan write/delete versus mixed/bad input), one aggregate lifecycle/progress group (A/B finish, pending start→foreground→new background, real increase versus silence/history/USER/STEER), owned lint/types and root uniform compile. Reuse original approved/denied/answer/save and receipt evidence; do not rerun the whole novel/chat suite for display work. Root later captures one bright/dark card set and the one actual long-duration stream above. Test shims and advanced fake clocks prove neither system-live-view rendering nor background policy acceptance.
