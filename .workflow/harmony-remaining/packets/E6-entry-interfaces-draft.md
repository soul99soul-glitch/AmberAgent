# E6 Entry 宿主接口草案

2026-09-28；仅只读核实与实施分工，依据 `results/E6-parallel-design.md`；没有修改产品/测试、构建或操作设备。
源码位置已按 E5 当前内容核实：ChatPage `runRegenerate:2019`、`runSendEdit:3079`、`buildPrepareContext:3216`、`buildToolLoop:3718`、`resumeToolLoop:4238`、`drainPendingQueue:4432`、`runSendParts:4737`；AppContainer `ChatRuntime:1262`、`resolveChatRuntime:1612`。以下是新增接口的精确形态，不是已实现声明。

```ts
// 类型沿用 @amber/chat-domain；ChatRuntime/ChatProviderChoice/TaskPrompts 沿用 DI。
interface ConversationRunSnapshot {
  conversationId: string; runId: string; phase: KernelPhase; sending: boolean;
  conversation: Conversation | null; // preparing 时 null；之后始终 full canonical
  streamingTail: UIMessage[]; regenTail: UIMessage | null; regenNodeId: string;
  retryStatus: string; errorMsg: string; liveActivity: SandboxActivityUiState | null;
  pendingQueue: PendingUserMessage[]; contextCompacts: ConversationCompact[];
  contextWindowTokens: number | null; compactActiveCount: number;
  modelLabel: string; awaitingTools: boolean; // pending/unexecuted 判定沿现有 helper
}
interface ConversationRunHandle {
  conversationId: string; runId: string; done: Promise<void>;
}
type ConversationRunCommand =
  | { kind: 'send'; parts: UIMessagePart[]; dispatchMessageId: string }
  | { kind: 'regenerate'; nodeId: string }
  | { kind: 'resume'; manualToolCallId: string }
  | { kind: 'edit'; nodeId: string; messageId: string; text: string }
  | { kind: 'verdict'; toolCallId: string; verdict: ToolApprovalVerdict; locator?: ToolApprovalPartLocator };
class ConversationRunService {
  start(conversationId: string, command: ConversationRunCommand): ConversationRunHandle;
  send(conversationId: string, parts: UIMessagePart[]): ConversationRunHandle;
  regenerate(conversationId: string, nodeId: string): ConversationRunHandle;
  resume(conversationId: string, manualToolCallId: string = ''): ConversationRunHandle;
  edit(conversationId: string, nodeId: string, messageId: string, text: string): ConversationRunHandle;
  verdict(conversationId: string, toolCallId: string, verdict: ToolApprovalVerdict,
    locator?: ToolApprovalPartLocator): ConversationRunHandle;
  append(conversationId: string, parts: UIMessagePart[]): Promise<void>;
  enqueue(conversationId: string, parts: UIMessagePart[], answer: boolean,
    mode: PendingUserMessageMode = 'FOLLOWUP'): Promise<void>;
  cancelPending(conversationId: string, messageId: string): Promise<void>;
  clearPending(conversationId: string): Promise<void>;
  snapshot(conversationId: string): ConversationRunSnapshot | null;
  subscribe(conversationId: string, listener: (s: ConversationRunSnapshot) => void): () => void;
  cancel(conversationId: string, runId: string): void;
  isBusy(conversationId: string): boolean;
  activeCount(): number;
}
interface PreparedConversationSeed {
  runtime: ChatRuntime; agentRuntime: AgentRuntimeSnapshot;
  compressChoice: ChatProviderChoice | null; taskPrompts: TaskPrompts;
  searchSettings: SearchSettings; ocrSeed: OcrSeed; miniAppSetting: MiniAppSetting;
  userNickname: string; modeInjections: PromptInjection[]; lorebooks: Lorebook[];
  webmountGlobalEnabled: boolean; webmountEvalEnabled: boolean; imageGenConfigured: boolean;
  subAgentEnabled: boolean; subAgentMode: string;
  agentTaskTools: AgentTool[]; agentCronTools: AgentTool[];
  enableGenerationNotification: boolean;
}
class DetachedConversationContext {
  readonly conversationId: string; readonly runId: string; readonly seed: PreparedConversationSeed;
  conversation: Conversation; readonly signal: AbortSignalLike;
  readonly activityStore: AgentToolActivityStore; // 本会话实例，非全局 across-await scope
  baseTools: AgentTool[]; subAgentTools: AgentTool[]; // 本轮 catalog / parentToolsProvider
  save(conversation: Conversation): Promise<void>; // 原 repo.save，同一 session writeTail
  publish(patch: Partial<ConversationRunSnapshot>): void;
  pending(): PendingUserMessage[];
  consumeSteer(): Promise<UIMessage[]>;
  cancelPending(messageId: string): Promise<void>; clearPending(): Promise<void>;
  hasNavigationCollector(): boolean; emitDeepRead(event: DeepReadOpenEvent): void;
}
prepareConversationSeed(conversation: Conversation): Promise<PreparedConversationSeed>;
buildConversationTurnDeps(ctx: DetachedConversationContext): ChatTurnDeps;
buildConversationToolLoop(ctx: DetachedConversationContext): Promise<ToolLoopOptions>;
completeConversationAuxiliary(ctx: DetachedConversationContext, finalized: Conversation,
  manualToolCallId: string): void;
```

`start` 同步检查本 ID busy、创建 runId/abort/record、取得既有 restore guard，然后异步读完整仓库与队列、调用 prepare；无页面传来的 executor/seed/Conversation 覆盖活动记录。prepare 用现有 loaders await 水合上述事实，不保留页面的“尚未加载默认空数组”竞态。`taskChoicesLoaded`、`pageAlive`、`generationToken` 不进 seed；title/suggestion 的既有最新 task slot/prompt 重读仍保留。start 的薄包装保持四条真实 engine 入口；edit 保存后直接同 record regenerate，verdict 保存后同 record continuation，避免释放 admission 的缝隙。append 复用 runAppendUserMessage 并同 ID 排他；队列变更与本会话 drain/persist/audit 串行，失败回灌当前队列、停止该次 drain，保留现有语义。

服务私有 session 保留 queue/drainActive/appendActive/writeTail/listeners；活动 record 保留 controller/kernel/raw/checkpoint/historyCount/stopTarget。原 onUpdate/onRaw/onRetry/onCompact 只写 ctx；send 的 `historyCount+1` 与 resume/regenerate 目标在开跑固定，checkpoint 增长不改变 visual tail 起点。store.save 必须 await 真正写入；raw、before-effect 和最终停止投影共用 writeTail。`done` 在最终写入结算后 resolve/reject；waiting tools 已持久化、sending=false，保留 canonical 给裁定入口。cancel 匹配两个 ID，再 kernel.cancel + 该 controller.abort；不调用全局 native interrupt。聚合 activeCount 维护 chatGenerating/保活，终态且无队列/订阅释放内存。

Page 先 subscribe 即时回放后才考虑冷读；已有 snapshot 不运行冷恢复或另一份 drain。UI 从 snapshot 的 conversation 更新 title/suggestions/rows 与既有 timeline revision，并将 pendingQueue 投影 pendingRows；scroll/follow、附件物化、草稿、editing 状态、model picker、导航/可见完成 haptic 留 Page。离页只退订及清 UI，不停止/保存运行。导航 collector 只在相同 ID 可见时有效，detach 后工具沿 existing bus 明确无收集者；不是延迟强行顶开别的会话。JS/WebMount 实际 host loss 与 owner busy 沿原研究处理，不能以 detached ctx 假称 WebView 跨页存活。

最少分工四个新文件（`entry/src/main/ets/platform_impl/`）：① `ConversationRunService.ets`：以上公共 DTO/record/context、命令/队列/保存/取消；② `ConversationRunDependencies.ets`：prepare、原 transformers/context/memory/recent blocks；③ `ConversationRunTools.ets`：原 buildToolLoop/catalog/adapters/guard/image source 与 ctx 注入；④ `ConversationRunAuxiliary.ets`：原 title/suggestions/memory/通知，字段合并经 ctx.save 对同 ID 最新态。②③④仅 type 引用 service DTO，不在运行时创建 service；DI getter 懒建 singleton。root 独占 ChatPage/ChatListPage/DI/EntryAbility 接线，避免交叉编辑。subagent DI 的 parent 解析必须绑定 seed.runtime 的 assistant/model，保留 role override/Jev，禁止改全局当前助手。MCP 工厂继续显式 assistantIds；tools warning 同一快照查询，不依赖 wireMcpAssistant 改全局。

源 gate：新模块无 Page/Scroller/UIContext/@State/捕获页面 this；provider/工具循环/Recipe/Plugin 引擎不复制。若展示 transport/text-live，只给现有 ChatKernelHostDeps 增可选 InteractiveTurnHooks.onSnapshot 转发，复用真实 InteractiveTurnSnapshot，不新增解析器。必要验证沿原 E6 研究的一组 A/B 交错与一次 UI 链；本 packet 未执行或声称任何 E6 PASS。
