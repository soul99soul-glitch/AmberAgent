# E11 frozen execution contract

Frozen by root after E10 source/checkpoint. Source authority is `results/E11-jev-council-design.md` plus the actual E8–E10 interfaces. Implementation starts only after E10 checkpoint; only necessary checks and separate device/provider gates.

## Scope and existing facts

- E11 closes neutral asynchronous approval triage and actual council seat `off/search/full` tool runs. Jev is its independent TYPESAFE/VERCEL evaluation API, not a Chat provider.
- E8 keeps council/DeepRead `ModelConfig` independent; current `domain/model_config.ts:9` still contains `{id,label,baseUrl,apiKey,model}`. E9 extends Google Chat authentication only. Council tool mode must not borrow or fall back to the current Chat model/provider, Google identity or a task-model UUID/pair.
- `tool_loop.ts:447–480` and `recipe_loop.ts:69/92` already save actual pending parts/checkpoints before emitting. No Jev HTTP await is permitted in capture, dispatcher.before, pending persistence or the synchronous card publication path.
- `ConversationRunService.ets:429` has current-canonical queued `mutateStoredConversation`; `:625–644` completes the saved awaiting-approval state. Root starts triage only after that real save barrier succeeds.
- `Council/manager.ts:493–538` currently fire-and-forgets JSONL append and suppresses errors. Transcript publication is not an approval durability barrier. `ChatRdbRepository.ets:300–319` actually rejects degraded writes despite an outdated AppContainer comment; successful source-store save can be reused, memory fallback cannot stand in for council durability.
- E11 Web delivers only a callable finite-decision seam. E12 implements and exercises its real **local visible WebMount `run_goal`** consumer. Current single WebView/owner/lease is not a remote service. No Moli/Playwright/Steel, external CLI seats or any other user ③ exclusion is reintroduced.

## A — Jev domain/runtime exports

Own new `chat/jev_approval.ts`, small `chat/jev_web.ts`, Entry `JevApprovalTriage.ets`, and narrow `jev_models.ts/jev_client.ts/JevSupport.ets` changes. No tool execution or approval policy changes here.

```ts
type JevNewPurpose = 'approval_triage' | 'web_automation';
type JevFact = 'yes' | 'no' | 'unknown';
interface JevNewPurposeSettings {
  mode: JevMode; allowToolMetadata: boolean;
  allowTaskText: boolean; allowPageContent: boolean;
}
interface JevApprovalLocator {
  conversationId: string; messageId: string; partIndex: number; toolCallId: string;
}
interface JevApprovalTriage {
  subjectHash: string; readonly: JevFact; reversible: JevFact; goalAligned: JevFact;
}
interface JevApprovalSubject {
  bindingJSON: string; action: UIMessagePartTool | null;
  packageHash: string | null; baseHash: string | null;
}
```

- `JevSettings.approvalTriage` and `.webAutomation` each use the named settings type; old JSON defaults both mode OFF and all allowances false. Existing four wired-purpose semantics remain global. Global OFF caps all purposes OFF; global SHADOW caps new ACTIVE purposes SHADOW.
- Public pure exports: `resolveJevPurposeMode(settings,purpose):JevMode`, `jevFactFromProbability(p:number):JevFact`, `buildJevApprovalSubject(locator,parent,userMessageId):JevApprovalSubject|null`, `readJevApprovalTriage(parent,expectedSubjectHash):JevApprovalTriage|null`. Wire metadata key is `jev_approval_v1`; probabilities ≥0.65=yes, ≤0.35=no, between=unknown. Nonfinite/out-of-range values remain a decode error, never a made-up answer.
- The subject is derived from the **saved** parent: ordinary exact input/target/protocol, Recipe's saved `pendingStep` plus descriptor hash/execution/nextIndex, Plugin's saved pending call/step plus descriptor package hash/execution (candidate_test includes candidate hash/expectedProvided), or import's pinned candidate+base hash. Bind parent locator, parent toolCallId and initiating user-message identity as well. Never reread a live package/default terminal target to build it. Missing/bad checkpoint/target ⇒ no guess; facts stay unknown.
- `bindingJSON` is strictly local input to existing synchronous Entry `sha256HexUtf8`; it is not request state and is never logged/saved as an outbound summary. UI only reads facts whose computed current subject hash matches; changing package child cannot leave the prior step's label looking current.
- Entry exports `scheduleJevApprovalTriage(conversationId:string,signal?:AbortSignalLike):void` and `cancelJevApprovalTriage(conversationId:string):void`; root schedules saved current pending subjects. The helper reloads settings on dispatch and completion. Deleted/answered/denied/approved/changed-subject/disabled cases drop late results.
- `jevEvaluate(state,questions,signal?:AbortSignalLike)` remains source-compatible and passes the actual signal to existing HTTP. Reuse current daily budget/cooldown and strict `decodeJevResponse`. Failure does not remove/change pending or execute anything.
- Outbound three facts concern: genuinely read/observe without modifying **or external disclosure**, easy reversal, and direct relevance to the initiating user's request. `mutates=false` alone cannot prove readonly for network search/HTTP. Only deterministic facts from the actual saved primitive/policy may be fixed locally; unknown facts request bounded Noul questions.
- Tool metadata/task/page allowances apply to these two new purposes only. Send only explicitly constructed bounded scalar summaries; raw commands/scripts/credentials/headers/query strings/package files are excluded. Without the material allowance, the affected fact stays unknown. Inputs/web text are data, never instructions.
- OFF: zero evaluation request. SHADOW: evaluation plus one narrow persisted observation `{purpose,subjectHash,model,facts,status}` (no raw materials), no model label or approval-state change. ACTIVE: merge matching facts into **latest canonical metadata only** via root's queued mutation, preserving all other metadata/output. All modes have zero Jev approvals/denials/answers; original human controls and permission resolver remain authoritative.

## Web seam — E12 is the real consumer

```ts
interface JevWebCandidate { id:string; toolName:string; input:JsonObject; description:string; }
interface JevWebObservation { sessionId:string; revision:string; pageSummary:string; }
interface JevWebDecision {
  kind:'action'|'handback'; candidateId:string|null; reason:string; shadow:boolean;
}
```

- A exports `buildJevWebChoice(observation,candidates):JevNoulBatchSpec` (the existing request bundle also carries Choice questions) and Entry `chooseJevWebCandidate(observation,candidates,signal?):Promise<JevWebDecision>`; only supplied finite candidate IDs or handback. No arbitrary tool name/input/JS/URL/text generation and no Jev DONE option.
- E12 owns observe → locally bounded candidates supplied by the main model/task → Choice → recheck same session/revision/site permission/bot lease → original primitive approval+dispatcher → new observe. Real host completion conditions decide DONE. Stale revision means observe again; unknown/error/cancel/capability loss means handback, no old action execution.
- OFF and SHADOW produce zero clicks/types/tool execution; ACTIVE still needs real permissions/human approvals. Only necessary step/deadline/no-progress bounds. `web_automation` is not added to `JEV_WIRED_PURPOSES` or reported working until the actual E12 `run_goal` consumer and its local-host gates are connected. No external remote-service acceptance claim.

## B — Council domain exports (DeepRead only)

Own `deepread/council/models.ts,runner.ts,manager.ts,validator.ts,prompts.ts` and a small approval-wait helper if needed; **never import Chat or Entry into DeepRead**.

```ts
type CouncilToolMode = 'off'|'search'|'full';
interface CouncilSeatRunKey { runId:string; round:number; seatId:string; }
interface CouncilSource { title:string; url:string; service:string; }
interface CouncilApprovalRequest {
  key:CouncilSeatRunKey; conversationId:string; messageId:string;
  partIndex:number; toolCallId:string; subjectHash:string; part:UIMessagePartTool;
}
interface CouncilToolVerdict { kind:'approved'|'denied'|'answered'; reason:string; answer:string|null; }
interface CouncilToolSnapshot {
  key:CouncilSeatRunKey; conversationId:string;
  messages:UIMessage[]; pending:CouncilApprovalRequest[]; sources:CouncilSource[];
}
```

- Add `ModelCouncilTaskSpec.toolMode`, `ModelCouncilRuntimeSetting.toolMode` with old defaults OFF; `CouncilTaskInput.toolMode?` overrides the actual run. Save/copy/validator/settings paths preserve the fixed mode. Existing independent `mode:'compare'|'debate'` is unchanged.
- `CouncilGenerateRequest` gains optional `key`, `toolMode`, `onTools(snapshot)` and `requestApproval(request,signal):Promise<CouncilToolVerdict>`; absent means OFF. Existing dynamic-seat planning and synthesis calls stay explicitly OFF. No new scheduler.
- `ModelCouncilTextResult` gains source-compatible optional `toolMessages`/`sources`; `ModelCouncilTurn` stores normalized `toolMessages:UIMessage[]`/`sources:CouncilSource[]` defaults []. Tool outputs/sources are preserved in archive and included as actual evidence in subsequent-round/裁判 prompts.
- Manager registers precise approval waiters for all saved pending requests; exposes `toolSnapshot(runId,round,seatId):CouncilToolSnapshot|null`, `subscribeTools(runId,listener):()=>void`, `submitToolVerdict(request,verdict):boolean`. Reject stale locator/run/round/seat/subjectHash/non-pending/duplicate verdict. Subject hash binds the exact saved parent+child step; a delayed button from the previous Recipe/Plugin child cannot approve the next child sharing its parent locator. Submission resolves that waiter only; Entry reloads/rechecks the same subject and performs the durable verdict write before continuation.
- Keep existing root→seat signal/semaphore/seat deadline/total deadline. Approval wait listens to the same seat signal, removes waiter/pending publication on cancel, and cannot extend the deadline. Late text/tools/sources/verdict callbacks cannot mutate terminal turns. Empty final text or unresolved tools is not completed; one seat failure does not cancel peers.

## C — Council Entry tool loop/store exports

Own new `CouncilToolRunner.ets`, small `CouncilSeatStore.ets` and narrow scope/source helpers; keep old `ModelCouncilRunner.ets` OFF route intact. Root injects real dependencies; no reverse AppContainer lookup in the new runner/store.

- `createCouncilToolRunner(deps:CouncilToolRunnerDeps):ModelCouncilTextRunner`; named Entry deps has `http:HttpClient`, `offRunner:ModelCouncilTextRunner`, and `prepareSeat(req:CouncilGenerateRequest):Promise<CouncilSeatExecution>`. Named `CouncilSeatExecution` fields are `conversation:Conversation`, `assistant:Assistant`, `store:CouncilSeatStore`, and `makeLoop(makeProviderForStep:(definitions:ChatToolDefinition[])=>ChatStreamProvider,toolPromptModel:ChatModel):Promise<ToolLoopOptions>`. Root prepares actual seat storage/tool ports only; C creates fixed-model `ChatTurnDeps` and awaits `makeLoop` with its provider factory/model. `deps.store` is that same seat store. No root-created/fake/default provider, fake ChatPage or discarded in-memory store.
- `createCouncilSeatStore(files:FileStore,key:CouncilSeatRunKey):CouncilSeatStore` exports `load():Promise<Conversation|null>` and `save(conversation:Conversation):Promise<void>`; it implements existing Chat `ConversationStore`. One source context per `(runId,round,seatId)`, using actual durable production FileStore and serial save before emit. Path is controlled/hash-derived (seatId can be user supplied); exact real IO errors and all-byte write completion must be surfaced. Existing transcript remains an archive, never a second approval truth.
- Model identity is always `req.model`: create `createOpenAIChatApi`/`asChatStreamProvider` using its fixed baseUrl/apiKey/model, actual definitions, seat temperature/reasoning and signal. `openai_request.ts:453` drops tools unless the request model has `'tool'` ability; the council adapter must explicitly construct that request capability for search/full and fail visibly on unsupported endpoint response. No lookup by Chat UUID/default and no silent text-only fallback.
- OFF delegates old text runner. SEARCH limits real executable registry to enabled `search_web/scrape_web/search_sources_status/search_strategy_explain`; respect the existing web-search consent/settings. FULL keeps the actual default Assistant's enabled/available tool scope and original `Assistant.toolProfile`, then exact same scoped discovery/Recipe/Plugin primitive resolvers; it does not overwrite the Assistant profile with `full`. FULL does not turn on either auto-approval setting and uses existing `invocationContext:'model_council'`.
- Reuse `runAgenticToolLoop` and `runToolLoopContinuation`, dispatcher, budget/duplicates, permission trace, fixed SSH/Mosh capture, package adapters and original settings. Root supplies an actual per-seat tool scope through the narrow assembly below. `buildConversationToolLoop(ctx)` requires the real service-bound `DetachedConversationContext`; it is not a council adapter, and C must not construct a fake context or call a parent Chat runtime.
- Loop returns pending only after save succeeded; publish matching snapshot then await human verdicts, apply exact parent locator with existing `applyToolApprovalToConversation`, save, and continuation. Recipe/Plugin child ask_user is displayed using `recipeAskUserPart/pluginAskUserPart`, but answer/approve/deny still locates and saves the parent. No batch approval or parent approval copied to future children.
- Dispatcher/native/package receives the same seat signal. Real successful search result items/scrape URLs populate sources; parsed failure cannot become a source/success. Outputs go back into the same seat conversation/provider context before final text/裁判.
- Save failure ⇒ no execute/no published recoverable approval. Cold reopening without a live restorable manager is honestly interrupted; never replay STARTED writes or restore a lost JSVM as resumable. Browser tools obey E6 ownership; absent visible seat browser capability is not exposed or reacquired from another chat.

## D — UI and root integration boundaries

UI owner: `ChatMessageTools/ChatMessageToolPreviewSheet` neutral three-fact wrapping labels; council pages/settings actual tool-mode selection, exact pending/ask_user/preview, real source links and cancel/failed/interrupted state; `SettingJevPage` two new purpose modes/allowances and save errors. Preserve new Jev fields in every existing `makeJevSettings` rebuild. Use current tokens/16vp gutters/≥44vp controls; long labels/URLs wrap. No green safe/recommended-approve control.

Root only: Chat/DeepRead index exports, AppContainer initialization/dependency injection, actual default Assistant run snapshot, ConversationRunService saved-pending scheduling/deletion+cancel hook, any shared catalog seam, existing routes/lifecycle. Current JevSupport already imports AppContainer while AppContainer imports JevSupport; do not spread that reverse edge into new modules. Cancellation disables writeback and releases waiters/native owners before any late continuation. UI publication and stored approval are separate claims.

### Root `prepareSeat` assembly — actual source constraints

- Hard boundary: `ConversationRunService.ets:70–101` defines a class whose `save/mutate/pending/steer/navigation` methods invoke its private service; `saveContext` is at :388. `ConversationRunDependencies.ets:47` first calls `resolveChatRuntime`, and :245 binds provider/model/compaction plus `store.save→ctx.save`. None of these factories is a valid way to prepare a seat, even when the returned Chat provider is subsequently discarded.
- The existing legal primitive entry is `AssistantLocalPrimitives.ets:21` → `createAssistantLocalPrimitives(AssistantLocalPrimitivesDeps)`; it already accepts actual Assistant, seat conversationId/activityStore, workspace and actual SSH/Mosh/Python runtimes without requiring ChatRuntime. Root obtains these through `getChatAssistants`, `getWorkspaceManager`, `getSSHProfileStore`, `getTerminalRuntime`, `getMoshRuntime`, `getPythonRuntime`, and `initMcpManager/getMcpManager` in `di/AppContainer.ets`. Current `prepareCouncilManager(setting)` has only the CouncilPage manual-start caller, no registered Chat council-start tool/parent Assistant locator/global currentAssistantID. Before run start, freeze `getChatAssistants()[0]` (the actual persisted default configuration and ID); UI explains FULL uses this default Assistant's enabled/available tools. No new Assistant picker or invented parent-Chat field.
- `prepareCouncilManager` creates the tool runner with a run-owned closure, shared by every seat: frozen actual Assistant ID/config, feature/settings scope, MCP server IDs and original approval settings. FULL uses the existing full profile within the frozen enabled/available primitive scope; SEARCH restricts that scope to actual search primitives. Original Chat profile semantics remain unchanged. Subsequent seats do not resolve a later global/default Assistant or settings afresh. Each continuation rechecks availability/revocation for that owned identity and intersects it with the frozen scope; it cannot add newly enabled features/servers or widen profile/approval rights. Package refresh still validates live name/hash and dependencies against that same owned primitive scope. OFF planning/synthesis may retain the existing cached text runner.
- Minimum shared extraction, owned by root: move only the package/catalog block `ConversationRunTools.ets:249–270` and activity wrapper :61–76 into `RunToolCatalog.ets`; no DetachedContext, ChatRuntime, provider, ConversationStore, service or AppContainer lookup. Keep ordinary Chat assembly/provider/steer unchanged. Suggested exact small exports:
  ```ts
  RunToolCatalogDeps { primitives:AgentTool[]; profile:MainAgentToolProfile; conversationId:string; recipeStore:RecipeStore; pluginStore:PluginStore; reranker?:ToolSearchReranker }
  RunToolCatalogSnapshot { baseTools:AgentTool[]; tools:AgentTool[]; recipeAdapter:RecipeLoopAdapter; pluginAdapter:PluginLoopAdapter }
  createRunToolCatalog(deps:RunToolCatalogDeps):Promise<RunToolCatalogSnapshot>
  scopeRunTools(tools:AgentTool[],conversationId:string,activityStore:AgentToolActivityStore):AgentTool[]
  ```
  `baseTools` is registry+introspection; `tools` additionally has tool_search. Chat alone appends its real subagent tools. Callers scope their final tools; cloning preserves all approval flags, recipeEnvelope/pluginEnvelope/mcpTarget. Each caller's `refreshTools` awaits the same catalog factory, then replaces its loop's adapters and scoped tools together; no second discovery catalog or approval truth.
- Root's small CouncilAssembly invokes the existing factories listed at `ConversationRunTools.ets:14–29,191–246`: actual enabled skill manager/mutation, assistant-scoped MCP management/server tools (retain real mcpTarget), memory feature gates, configured search, task/cron/status/health/soul/clipboard/playbooks and configured image tools. Use actual native ports and settings, not copied execute bodies or a synthetic Chat seed. Package validation/`createRecipeTools/createPluginTools` consume that same reduced primitive oracle; `EntryPluginRuntime.ets:10` resolves MCP by exact identity and associates native JS cancellation with the seat ID. Native plugin JS remains available without a WebView; it must not be confused with `javascript_execute`.
- Unavailable seat hosts must be removed **before** package registration/discovery: all `wm_*` and `javascript_execute` (`ConversationBrowserGuard.ets:37,53` requires the visible ChatPage ID); `conversation_queue_status/cancel` (`builtin_queue_tools.ts:23` needs actual ConversationRunService user queue); `deep_read_open` (requires a navigation collector); existing public `subagent_*` (`AppContainer.ets:893–913` generation port is built from parent ChatRuntime). Never borrow the visible Chat conversationId, expose a fake empty queue, or allow fallback/default Chat generation for these tools. FULL is still all enabled/available tools, with these specific missing host capabilities reported honestly.
- Do not over-exclude service-backed tools: `createFeishuDocsTools` (:59) uses HTTP/token storage; `syncEnabled` checks the actual global-or-Assistant enable gate and updates the session enabled flag, without requiring a mounted WebView. `createConversationHistoryTools` (`builtin_session_tools.ts:155`) may use the real read-only Chat repository/grant store and seat currentConversationProvider; this does not authorize writing the seat into ChatRdb. `createConversationContextTools` (`builtin_conversation_tools.ts:51`) may use the same latest saved seat plus compact store; compact execution must receive C's fixed seat provider, never `buildConversationCompressProvider(seed)` or an implicit Chat fallback. Omit only a capability whose real dependencies were not supplied.
- Root builds the same `AgentToolDispatcher` + `defaultToolInvocationHooks` + `createTerminalApprovalGuard(...).hook/captureInvocationMetadata` as :181–190,272–275; preserve owned runtime approval/budget settings and `invocationContext:'model_council'`. Root calls C's exported `createCouncilSeatStore` with real FileStore/key and builds the initial Conversation using the frozen Assistant ID; C owns that store implementation and every generation/approval/continuation write. `prepareSeat.makeLoop` closes over the frozen scope/actual ports, supplies C's `makeProviderForStep([])` to any real seat compact tool, awaits package catalog construction and assigns only C's supplied provider factory/model into ToolLoopOptions. Its currentConversationProvider reads the same seat store, never a captured initial conversation or `getChatRepository().getById(seatId)`. There is no Chat service queue/steer hook on this loop.
- Actual dependency direction remains AppContainer → root CouncilAssembly/shared catalog → C runner/store and existing primitive factories; the new C runner/store and shared catalog do not import AppContainer. `makeProviderForStep` and `toolPromptModel` must be assigned from C's fixed ModelConfig request, not copied from `buildConversationToolLoop:284–292`. Initial seed, every pending parent/child, human verdict and execution feedback are saved to the same CouncilSeatStore before corresponding publication/continuation.

## Required completion evidence after implementation

Only necessary focused groups: triage OFF/SHADOW/threshold/privacy and actual delayed subject writeback; council real stream tool→dispatcher→same-seat model feedback plus FULL approved/denied/answered/store-failure/cancel isolation. No blanket old-suite matrix. Root affected types/lint/build/sign once and independent logic/UI reviewers; real model/provider and bright/dark device evidence stay separate gates. E11 Web can be source-complete as a decision seam while E12 consumer acceptance remains explicitly pending.

New JevApprovalTriage helper receives narrow initialized host ports for canonical read/mutate, saved subject/policy and evaluation/settings access; it does not import AppContainer or ConversationRunService. Root schedules it only after durable pending state and supplies queued canonical mutation. Approval lookup in UI checks the current subject hash so prior package-step labels do not remain current.
