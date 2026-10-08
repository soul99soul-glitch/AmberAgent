import type { Context } from '@kit.AbilityKit';
import type { AbortSignalLike } from '@amber/deepread-domain';
import type {
  Conversation, UIMessage, UIMessagePart, UIMessagePartText, UIMessagePartTool,
  PendingUserMessage, PendingUserMessageMode, ConversationCompact, KernelPhase,
  SandboxActivityUiState, ToolApprovalVerdict, ToolApprovalPartLocator, AgentTool,
  ToolLoopOptions, ChatTurnDeps, ChatKernelHost, DeepReadOpenEvent,
  SearchSettings, MiniAppSetting, Assistant, JevCompletionNotice, JevCompletionFacts,
} from '@amber/chat-domain';
import {
  newId, nowIso, currentMessages, nodeRole, makeUIMessage, makeUserMessage,
  patchConversation, applyStopGeneration, streamContentLength, shouldCheckpoint,
  checkpointConversationTail, checkpointRegenerateConversation,
  runChatTurnWithTools, runRegenerateAtWithTools, runToolLoopContinuation,
  runAppendUserMessage, editMessage, applyToolApprovalToConversation,
  conversationHasPendingTools, conversationHasPendingOrUnexecutedTools,
  resolveIdleToolBlocker, createChatKernelHost, ContextCompactionFailedError,
  makePendingUserMessage, enqueuePendingUserMessage, cancelPendingUserMessage,
  dequeueNextPendingUserMessage, preparePendingMessageForDispatch, convertSteerToFollowup,
  loadPendingQueue, persistPendingQueue, persistPendingQueueWithEvent, recordPendingQueueEvent,
  invalidateCompacts, AgentToolActivityStore, applyUserInputRegexes, jevUnverifiedChanges, jevCompletionBindingMatches,
} from '@amber/chat-domain';
import type { ChatRuntime, ChatProviderChoice, TaskPrompts } from '../di/AppContainer.ets';
import { getChatRepository, getChatKvStore, getCompactStore, getChatAssistants } from '../di/AppContainer.ets';
import type { AgentRuntimeSnapshot } from './AgentRuntimePrefs.ets';
import type { OcrSeed } from './OcrSupport.ets';
import { EntryAbortController } from './EntryAbortController.ets';
import { beginConversationRun, beginConversationCompaction, isConversationCompacting } from './ConversationRunGuard.ets';
import { reportGenerationProgress } from './BackgroundGenerationKeepAlive.ets';
import { GenerationProgressTracker } from './GenerationProgressTracker.ets';
import { prepareConversationSeed, buildConversationTurnDeps } from './ConversationRunDependencies.ets';
import { buildConversationToolLoop } from './ConversationRunTools.ets';
import { completeConversationAuxiliary } from './ConversationRunAuxiliary.ets';
import { releaseConversationBrowserRun } from './ConversationBrowserGuard.ets';
import { cancelNativePluginJsConversation } from './NativePluginJsTransport.ets';
import { evaluateJevCompletion } from './JevCompletionSupport.ets';
import { cancelJevApprovalTriage, scheduleJevApprovalTriage } from './JevApprovalTriage.ets';
import { compactConversationNow } from './ContextCompactionSupport.ets';
import type { ManualCompactionOutcome } from './ContextCompactionSupport.ets';
import { materializeDocumentParts, materializeConversationDocuments } from './DocumentAttachmentSupport.ets';

export interface ConversationRunSnapshot {
  conversationId: string; runId: string; phase: KernelPhase; sending: boolean;
  conversation: Conversation | null; visualConversation: Conversation | null; conversationDeleted: boolean;
  streamingTail: UIMessage[]; regenTail: UIMessage | null; regenNodeId: string;
  retryStatus: string; errorMsg: string; liveActivity: SandboxActivityUiState | null;
  pendingQueue: PendingUserMessage[]; contextCompacts: ConversationCompact[];
  contextWindowTokens: number | null; compactActiveCount: number;
  completionNotice: JevCompletionNotice | null;
  modelLabel: string; awaitingTools: boolean; jsSandboxNeeded: boolean; historyNodeCount: number;
}
export interface ConversationRunHandle {
  conversationId: string; runId: string; done: Promise<void>;
}
export interface ConversationSendCommand {
  kind: 'send'; parts: UIMessagePart[]; dispatchMessageId: string;
}
export interface ConversationRegenerateCommand { kind: 'regenerate'; nodeId: string; }
export interface ConversationResumeCommand { kind: 'resume'; manualToolCallId: string; }
export interface ConversationEditCommand { kind: 'edit'; nodeId: string; messageId: string; text: string; }
export interface ConversationVerdictCommand {
  kind: 'verdict'; toolCallId: string; verdict: ToolApprovalVerdict; locator?: ToolApprovalPartLocator;
}
export type ConversationRunCommand = ConversationSendCommand | ConversationRegenerateCommand
  | ConversationResumeCommand | ConversationEditCommand | ConversationVerdictCommand;
export interface PreparedConversationSeed {
  runtime: ChatRuntime; agentRuntime: AgentRuntimeSnapshot;
  compressChoice: ChatProviderChoice | null; taskPrompts: TaskPrompts;
  searchSettings: SearchSettings; ocrSeed: OcrSeed | null; miniAppSetting: MiniAppSetting;
  userNickname: string;
  webmountGlobalEnabled: boolean; webmountEvalEnabled: boolean; imageGenConfigured: boolean;
  subAgentEnabled: boolean; subAgentMode: string;
  agentTaskTools: AgentTool[]; agentCronTools: AgentTool[];
  enableGenerationNotification: boolean;
}

export class DetachedConversationContext {
  readonly conversationId: string;
  readonly runId: string;
  readonly seed: PreparedConversationSeed;
  readonly signal: AbortSignalLike;
  readonly activityStore: AgentToolActivityStore = new AgentToolActivityStore();
  conversation: Conversation;
  recalledMemoryIds: number[] = [];
  baseTools: AgentTool[] = [];
  subAgentTools: AgentTool[] = [];
  private readonly service: ConversationRunService;

  constructor(service: ConversationRunService, runId: string, conversation: Conversation,
    seed: PreparedConversationSeed, signal: AbortSignalLike) {
    this.service = service;
    this.runId = runId;
    this.conversationId = conversation.id;
    this.conversation = conversation;
    this.seed = seed;
    this.signal = signal;
  }
  save(conversation: Conversation): Promise<void> { return this.service.saveContext(this, conversation); }
  mutateConversation(update: (latest: Conversation) => Conversation | null): Promise<Conversation | null> {
    return this.service.mutateContext(this, update);
  }
  publish(patch: Partial<ConversationRunSnapshot>): void { this.service.publishContext(this, patch); }
  pending(): PendingUserMessage[] { return this.service.pending(this.conversationId); }
  consumeSteer(): Promise<UIMessage[]> { return this.service.consumeSteer(this); }
  cancelPending(messageId: string): Promise<void> { return this.service.cancelPending(this.conversationId, messageId); }
  clearPending(): Promise<void> { return this.service.clearPending(this.conversationId); }
  hasNavigationCollector(): boolean { return this.service.hasNavigationCollector(this.conversationId); }
  emitDeepRead(event: DeepReadOpenEvent): void { this.service.emitDeepRead(this.conversationId, event); }
}

interface ActiveRun {
  id: string;
  controller: EntryAbortController;
  kernel: ChatKernelHost | null;
  context: DetachedConversationContext | null;
  tracker: GenerationProgressTracker;
  raw: UIMessage[];
  checkpointAt: number;
  checkpointLen: number;
  historyCount: number;
  regenerateNodeId: string;
  visualNodeId: string;
  resumeMode: boolean;
  writeError: Error | null;
  inputCommitted: boolean;
  cancelQueue: Promise<void> | null;
  release: () => void;
  unsubscribeActivity: (() => void) | null;
}
interface ConversationSession {
  id: string;
  record: ActiveRun | null;
  completionController: EntryAbortController | null;
  completionSourceFingerprint: string;
  latest: Conversation | null;
  newConversation: Conversation | null;
  state: ConversationRunSnapshot;
  queue: PendingUserMessage[];
  queueLoaded: boolean;
  queueTail: Promise<void>;
  writeTail: Promise<void>;
  pendingWrites: number;
  pendingQueueWrites: number;
  drainActive: boolean;
  appendActive: boolean;
  listeners: Array<(snapshot: ConversationRunSnapshot) => void>;
  navigation: Array<(event: DeepReadOpenEvent) => void>;
}
const errorText = (error: Error): string => error instanceof ContextCompactionFailedError
  ? '上下文已满，压缩失败，请配置任务模型' : error.message;
const emptySnapshot = (id: string): ConversationRunSnapshot => ({
  conversationId: id, runId: '', phase: 'idle', sending: false, conversation: null,
  visualConversation: null, conversationDeleted: false,
  streamingTail: [], regenTail: null, regenNodeId: '', retryStatus: '', errorMsg: '', liveActivity: null,
  pendingQueue: [], contextCompacts: [], contextWindowTokens: null, compactActiveCount: 0,
  completionNotice: null, modelLabel: '', awaitingTools: false, jsSandboxNeeded: false, historyNodeCount: 0,
});
const copySnapshot = (s: ConversationRunSnapshot): ConversationRunSnapshot => ({
  conversationId: s.conversationId, runId: s.runId, phase: s.phase, sending: s.sending,
  conversation: s.conversation, visualConversation: s.visualConversation, conversationDeleted: s.conversationDeleted,
  streamingTail: s.streamingTail.slice(), regenTail: s.regenTail,
  regenNodeId: s.regenNodeId, retryStatus: s.retryStatus, errorMsg: s.errorMsg, liveActivity: s.liveActivity,
  pendingQueue: s.pendingQueue.slice(), contextCompacts: s.contextCompacts.slice(),
  contextWindowTokens: s.contextWindowTokens, compactActiveCount: s.compactActiveCount,
  modelLabel: s.modelLabel, awaitingTools: s.awaitingTools, jsSandboxNeeded: s.jsSandboxNeeded,
  historyNodeCount: s.historyNodeCount, completionNotice: s.completionNotice,
});

export class ConversationRunService {
  private readonly sessions: Map<string, ConversationSession> = new Map<string, ConversationSession>();
  private runningListeners: Array<(ids: string[]) => void> = [];
  private generating: boolean = false;

  private session(id: string): ConversationSession {
    const previous: ConversationSession | undefined = this.sessions.get(id);
    if (previous !== undefined) return previous;
    const created: ConversationSession = {
      id: id, record: null, completionController: null, completionSourceFingerprint: '', latest: null, newConversation: null, state: emptySnapshot(id), queue: [], queueLoaded: false,
      queueTail: Promise.resolve(), writeTail: Promise.resolve(), pendingWrites: 0, pendingQueueWrites: 0,
      drainActive: false, appendActive: false, listeners: [], navigation: [],
    };
    this.sessions.set(id, created);
    return created;
  }
  private emit(session: ConversationSession): void {
    session.state.pendingQueue = session.queue.slice();
    const value: ConversationRunSnapshot = copySnapshot(session.state);
    for (const listener of session.listeners.slice()) {
      try { listener(value); } catch (_error) { /* Observers cannot interrupt durable execution. */ }
    }
  }
  private releaseIdle(session: ConversationSession): void {
    if (session.record === null && session.newConversation === null && !session.drainActive && !session.appendActive && session.queue.length === 0
      && session.pendingWrites === 0 && session.pendingQueueWrites === 0
      && session.listeners.length === 0 && session.navigation.length === 0 && this.sessions.get(session.id) === session) {
      this.clearCompletion(session);
      this.sessions.delete(session.id);
    }
  }
  private aggregate(): void {
    const ids: string[] = this.runningConversationIds();
    for (const listener of this.runningListeners.slice()) {
      try { listener(ids.slice()); } catch (_error) { /* Same observer isolation as session snapshots. */ }
    }
    const next: boolean = this.activeCount() > 0;
    if (next === this.generating) return;
    this.generating = next;
    AppStorage.setOrCreate<boolean>('chatGenerating', next);
    // E13:后台任务的停止由活动集驱动(末个 run 报告 inactive 即触发),此处不再直停
  }

  private reportRunProgress(record: ActiveRun, active: boolean, conversationId: string = ''): void {
    const context: Context | undefined = AppStorage.get<Context>('abilityContext');
    if (context === undefined) return;
    const totals = record.tracker.totals();
    reportGenerationProgress(context, { source: 'chat', runId: record.id, generationActive: active,
      receivedChars: totals.chars, receivedBytes: totals.bytes,
      conversationId: record.context?.conversationId ?? conversationId,
      title: record.context?.conversation.title ?? '' });
  }
  private write<T>(session: ConversationSession, operation: () => Promise<T>): Promise<T> {
    session.pendingWrites++;
    const result: Promise<T> = session.writeTail.then(operation);
    // Keep serialization usable after a rejected write; callers retain its actual rejection.
    session.writeTail = result.then((): void => {}, (): void => {});
    return result.finally((): void => { session.pendingWrites--; this.releaseIdle(session); });
  }
  private async ensureQueue(session: ConversationSession): Promise<void> {
    if (session.queueLoaded) return;
    session.queue = await loadPendingQueue(getChatKvStore(), session.id, Date.now,
      (error: Error): void => { session.state.errorMsg = error.message; });
    session.queueLoaded = true;
    this.emit(session);
  }
  private async queueOperation<T>(session: ConversationSession, operation: () => Promise<T>): Promise<T> {
    const release: () => void = beginConversationRun(session.id);
    session.pendingQueueWrites++;
    const result: Promise<T> = session.queueTail.then(async (): Promise<T> => {
      await this.ensureQueue(session);
      return await operation();
    });
    session.queueTail = result.then((): void => {}, (): void => {});
    return result.finally((): void => { session.pendingQueueWrites--; release(); this.releaseIdle(session); });
  }
  private applyConversation(session: ConversationSession, conversation: Conversation): void {
    if (session.completionController !== null || session.state.completionNotice !== null) {
      const facts: JevCompletionFacts | null = jevUnverifiedChanges(currentMessages(conversation));
      if (facts === null || facts.sourceFingerprint !== session.completionSourceFingerprint) this.clearCompletion(session);
    }
    session.latest = conversation;
    session.state.conversation = conversation;
    session.state.awaitingTools = conversationHasPendingOrUnexecutedTools(conversation);
    if (session.record?.context !== null && session.record?.context !== undefined) {
      session.record.context.conversation = conversation;
    }
    this.emit(session);
  }

  snapshot(conversationId: string): ConversationRunSnapshot | null {
    const session: ConversationSession | undefined = this.sessions.get(conversationId);
    return session === undefined ? null : copySnapshot(session.state);
  }
  subscribe(conversationId: string, listener: (snapshot: ConversationRunSnapshot) => void): () => void {
    const session: ConversationSession = this.session(conversationId);
    session.listeners.push(listener);
    try { listener(copySnapshot(session.state)); } catch (_error) { /* Same isolation as emit. */ }
    return (): void => {
      session.listeners = session.listeners.filter((item): boolean => item !== listener);
      this.releaseIdle(session);
    };
  }
  subscribeNavigation(conversationId: string, listener: (event: DeepReadOpenEvent) => void): () => void {
    const session: ConversationSession = this.session(conversationId);
    session.navigation.push(listener);
    return (): void => {
      session.navigation = session.navigation.filter((item): boolean => item !== listener);
      this.releaseIdle(session);
    };
  }
  hasNavigationCollector(conversationId: string): boolean {
    return (this.sessions.get(conversationId)?.navigation.length ?? 0) > 0;
  }
  emitDeepRead(conversationId: string, event: DeepReadOpenEvent): void {
    const session: ConversationSession | undefined = this.sessions.get(conversationId);
    if (session === undefined) return;
    for (const listener of session.navigation.slice()) listener(event);
  }
  isBusy(conversationId: string): boolean {
    const session: ConversationSession | undefined = this.sessions.get(conversationId);
    return session !== undefined && (session.record !== null || session.appendActive || session.drainActive);
  }
  async compact(conversationId: string): Promise<ManualCompactionOutcome> {
    const session: ConversationSession = this.session(conversationId);
    if (this.isBusy(conversationId) || isConversationCompacting(conversationId)) {
      throw new Error('该会话仍在生成或压缩，请稍后重试。');
    }
    const release: () => void = beginConversationCompaction(session.id);
    // Reuse the existing non-generation admission lock; reserve/append/drain
    // all consult it synchronously before their first await.
    session.appendActive = true;
    session.state.compactActiveCount = 1;
    this.emit(session);
    try {
      return await this.write(session, async (): Promise<ManualCompactionOutcome> => {
        const conversation: Conversation | null = await this.loadCanonical(session);
        if (conversation === null) throw new Error('会话不存在或已删除');
        this.applyConversation(session, conversation);
        const result: ManualCompactionOutcome = await compactConversationNow(conversation);
        session.state.contextCompacts = result.compacts;
        if (result.contextWindowTokens !== null) session.state.contextWindowTokens = result.contextWindowTokens;
        this.emit(session);
        return result;
      });
    } finally {
      session.appendActive = false;
      session.state.compactActiveCount = 0;
      release();
      this.emit(session);
      this.drain(session);
      this.releaseIdle(session);
    }
  }
  activeCount(): number {
    let count: number = 0;
    for (const session of this.sessions.values()) if (session.record !== null && session.state.sending) count++;
    return count;
  }
  runningConversationIds(): string[] {
    const ids: string[] = [];
    for (const session of this.sessions.values()) {
      if (session.record !== null && session.state.sending) ids.push(session.id);
    }
    return ids;
  }
  subscribeRunning(listener: (ids: string[]) => void): () => void {
    this.runningListeners.push(listener);
    try { listener(this.runningConversationIds()); } catch (_error) { /* Immediate replay uses the same isolation. */ }
    return (): void => {
      this.runningListeners = this.runningListeners.filter((item): boolean => item !== listener);
    };
  }
  forgetDeletedConversation(conversationId: string): void {
    cancelJevApprovalTriage(conversationId);
    const session: ConversationSession | undefined = this.sessions.get(conversationId);
    if (session !== undefined) this.clearCompletion(session);
    if (session === undefined) { cancelNativePluginJsConversation(conversationId); return; }
    if (this.isBusy(conversationId) || session.pendingWrites > 0 || session.pendingQueueWrites > 0) {
      throw new Error('该会话仍在运行或保存，请稍后删除');
    }
    cancelNativePluginJsConversation(conversationId);
    session.queue = []; session.queueLoaded = true; session.newConversation = null; session.latest = null;
    session.state = emptySnapshot(conversationId); session.state.conversationDeleted = true;
    this.emit(session);
    this.sessions.delete(conversationId);
  }
  seedNewConversation(conversation: Conversation): void {
    const session: ConversationSession = this.session(conversation.id);
    if (session.record !== null || session.appendActive || session.latest !== null || session.newConversation !== null) {
      throw new Error('新会话已登记或正在运行');
    }
    session.newConversation = conversation;
  }
  discardNewConversation(conversationId: string): void {
    const session: ConversationSession | undefined = this.sessions.get(conversationId);
    if (session === undefined || this.isBusy(conversationId) || session.latest !== null
      || session.pendingWrites > 0 || session.pendingQueueWrites > 0 || session.queue.length > 0) return;
    session.newConversation = null;
    this.releaseIdle(session);
  }
  private async loadCanonical(session: ConversationSession): Promise<Conversation | null> {
    const saved: Conversation | null = await getChatRepository().getById(session.id);
    if (saved !== null) {
      session.newConversation = null;
      const materialized: Conversation = await materializeConversationDocuments(saved);
      if (materialized !== saved) {
        await getChatRepository().save(materialized);
        this.applyConversation(session, materialized);
      }
      return materialized;
    }
    const created: Conversation | null = session.newConversation;
    if (created === null) return null;
    const release: () => void = beginConversationRun(session.id);
    try {
      await getChatRepository().save(created);
      session.newConversation = null;
      return created;
    } finally { release(); }
  }
  start(conversationId: string, command: ConversationRunCommand): ConversationRunHandle {
    return this.reserve(this.session(conversationId), command, false);
  }
  send(conversationId: string, parts: UIMessagePart[]): ConversationRunHandle {
    const command: ConversationSendCommand = { kind: 'send', parts: parts, dispatchMessageId: '' };
    return this.start(conversationId, command);
  }
  regenerate(conversationId: string, nodeId: string): ConversationRunHandle {
    const command: ConversationRegenerateCommand = { kind: 'regenerate', nodeId: nodeId };
    return this.start(conversationId, command);
  }
  resume(conversationId: string, manualToolCallId: string = ''): ConversationRunHandle {
    const command: ConversationResumeCommand = { kind: 'resume', manualToolCallId: manualToolCallId };
    return this.start(conversationId, command);
  }
  edit(conversationId: string, nodeId: string, messageId: string, text: string): ConversationRunHandle {
    const command: ConversationEditCommand = { kind: 'edit', nodeId: nodeId, messageId: messageId, text: text };
    return this.start(conversationId, command);
  }
  verdict(conversationId: string, toolCallId: string, verdict: ToolApprovalVerdict,
    locator?: ToolApprovalPartLocator): ConversationRunHandle {
    const command: ConversationVerdictCommand = { kind: 'verdict', toolCallId: toolCallId, verdict: verdict, locator: locator };
    return this.start(conversationId, command);
  }
  private reserve(session: ConversationSession, command: ConversationRunCommand, fromDrain: boolean): ConversationRunHandle {
    if (session.record !== null || session.appendActive || (session.drainActive && !fromDrain)) {
      throw new Error('该会话仍在运行，请先停止或将消息排队');
    }
    cancelJevApprovalTriage(session.id);
    this.clearCompletion(session);
    const release: () => void = beginConversationRun(session.id);
    const id: string = newId();
    const record: ActiveRun = {
      id: id, controller: new EntryAbortController(), kernel: null, context: null,
      tracker: new GenerationProgressTracker(), raw: [],
      checkpointAt: 0, checkpointLen: 0, historyCount: 0, regenerateNodeId: '', visualNodeId: '',
      resumeMode: command.kind !== 'send', writeError: null, inputCommitted: false, cancelQueue: null,
      release: release, unsubscribeActivity: null,
    };
    // E13:preparing 即以零进度登记活动;真实增长在 captureRaw 测量,终态在 finally 注销
    this.reportRunProgress(record, true, session.id);
    session.record = record; // Synchronous admission precedes every repository/seed await.
    session.state.runId = id; session.state.phase = 'preparing'; session.state.sending = true;
    session.state.conversation = null; session.state.errorMsg = ''; session.state.retryStatus = '';
    session.state.visualConversation = null; session.state.conversationDeleted = false;
    session.state.streamingTail = []; session.state.regenTail = null; session.state.regenNodeId = '';
    session.state.liveActivity = null; session.state.compactActiveCount = 0;
    session.state.jsSandboxNeeded = false; session.state.historyNodeCount = 0;
    this.aggregate(); this.emit(session);
    const done: Promise<void> = this.perform(session, record, command);
    return { conversationId: session.id, runId: id, done: done };
  }

  publishContext(context: DetachedConversationContext, patch: Partial<ConversationRunSnapshot>): void {
    const session: ConversationSession | undefined = this.sessions.get(context.conversationId);
    if (session === undefined || session.state.runId !== context.runId) return;
    if (patch.phase !== undefined) session.state.phase = patch.phase;
    if (patch.retryStatus !== undefined) session.state.retryStatus = patch.retryStatus;
    if (patch.errorMsg !== undefined) session.state.errorMsg = patch.errorMsg;
    if (patch.liveActivity !== undefined) session.state.liveActivity = patch.liveActivity;
    if (patch.contextCompacts !== undefined) session.state.contextCompacts = patch.contextCompacts;
    if (patch.compactActiveCount !== undefined) session.state.compactActiveCount = patch.compactActiveCount;
    if (patch.contextWindowTokens !== undefined) session.state.contextWindowTokens = patch.contextWindowTokens;
    if (patch.modelLabel !== undefined) session.state.modelLabel = patch.modelLabel;
    if (patch.jsSandboxNeeded !== undefined) session.state.jsSandboxNeeded = patch.jsSandboxNeeded;
    this.emit(session);
  }
  saveContext(context: DetachedConversationContext, conversation: Conversation, checkpoint: boolean = false): Promise<void> {
    const session: ConversationSession | undefined = this.sessions.get(context.conversationId);
    if (session === undefined || session.record?.id !== context.runId || conversation.id !== context.conversationId) {
      return Promise.reject(new Error('会话运行已结束或保存目标不匹配'));
    }
    const record: ActiveRun = session.record;
    // A real engine save subsumes earlier raw content. Later raw callbacks remain available for cancellation.
    if (!checkpoint) record.raw = [];
    const saved: Promise<void> = this.write(session, async (): Promise<void> => {
      if (session.record !== record) throw new Error('会话运行已结束');
      // Message/state changes belong to the engine; field mutations share the same session tail.
      const latest: Conversation | null = session.latest;
      const effective: Conversation = latest === null ? conversation : patchConversation(conversation, {
        title: latest.title, chatSuggestions: latest.chatSuggestions, isPinned: latest.isPinned,
      });
      await getChatRepository().save(effective);
      if (!record.resumeMode) {
        const messages: UIMessage[] = currentMessages(effective);
        if (messages.length > record.historyCount && messages[record.historyCount].role === 'user') {
          if (!record.inputCommitted && record.regenerateNodeId.length === 0) {
            session.state.visualConversation = patchConversation(effective, {
              messageNodes: effective.messageNodes.slice(0, session.state.historyNodeCount + 1),
            });
          }
          record.inputCommitted = true;
        }
      }
      this.applyConversation(session, effective);
    });
    return saved.catch((error: Error): void => {
      record.writeError = error; record.controller.abort();
      if (session.record === record) { session.state.errorMsg = errorText(error); this.emit(session); }
      throw error;
    });
  }
  mutateContext(context: DetachedConversationContext,
    update: (latest: Conversation) => Conversation | null): Promise<Conversation | null> {
    // Resolve the current per-ID session at completion time. An old auxiliary await
    // must not retain a private tail after an idle session has been released.
    return this.mutateStoredConversation(context.conversationId, update);
  }
  mutateStoredConversation(conversationId: string,
    update: (latest: Conversation) => Conversation | null | Promise<Conversation | null>): Promise<Conversation | null> {
    const release: () => void = beginConversationRun(conversationId);
    const session: ConversationSession = this.session(conversationId);
    return this.write(session, async (): Promise<Conversation | null> => {
      const latest: Conversation | null = await this.loadCanonical(session);
      if (latest === null) return null;
      const updated: Conversation | null = await update(latest);
      if (updated === null) return null;
      if (updated.id !== conversationId) throw new Error('字段变更保存目标不匹配');
      await getChatRepository().save(updated);
      this.applyConversation(session, updated);
      return updated;
    }).finally(release);
  }
  private captureRaw(session: ConversationSession, record: ActiveRun, messages: UIMessage[]): void {
    if (session.record !== record || record.context === null) return;
    record.raw = messages.slice();
    // E13:真实接收量在 checkpoint 节流前测量;只有新增才上报
    const gained = record.tracker.observe(messages);
    if (gained.chars > 0 || gained.bytes > 0) this.reportRunProgress(record, true);
    if (messages.length === 0) return;
    const last: UIMessage = messages[messages.length - 1];
    if (last.role !== 'assistant' && last.role !== 'user') return;
    const now: number = Date.now(); const length: number = streamContentLength(last);
    if (!shouldCheckpoint(now, record.checkpointAt, record.checkpointLen, length)) return;
    record.checkpointAt = now; record.checkpointLen = length;
    const context: DetachedConversationContext = record.context;
    const projected: Conversation = record.regenerateNodeId.length > 0
      ? checkpointRegenerateConversation(context.conversation, record.regenerateNodeId, messages)
      : checkpointConversationTail(context.conversation, messages);
    this.saveContext(context, projected, true).catch((): void => { /* saveContext records failure; final done rejects it. */ });
  }
  private updateVisual(session: ConversationSession, record: ActiveRun, messages: UIMessage[]): void {
    if (session.record !== record) return;
    if (record.resumeMode) {
      const base: Conversation | null = session.state.visualConversation;
      const targetIndex: number = base === null ? -1 : base.messageNodes.findIndex((node): boolean => node.id === record.visualNodeId);
      session.state.regenNodeId = record.visualNodeId;
      session.state.regenTail = targetIndex < 0 ? null : (messages[targetIndex] ?? null);
      session.state.streamingTail = targetIndex < 0 ? [] : messages.slice(targetIndex + 1);
    } else session.state.streamingTail = messages.slice(record.historyCount + (record.regenerateNodeId.length === 0 ? 1 : 0));
    this.emit(session);
  }
  private async refreshCompacts(session: ConversationSession, record: ActiveRun): Promise<void> {
    const values: ConversationCompact[] = await getCompactStore().getCompacts(session.id);
    if (session.record === record || session.state.runId === record.id) {
      session.state.contextCompacts = values; this.emit(session);
    }
  }
  private manualId(conversation: Conversation, requested: string): string {
    if (requested.length > 0) return requested;
    const messages: UIMessage[] = currentMessages(conversation);
    if (messages.length === 0) return '';
    for (const part of messages[messages.length - 1].parts) {
      if (part.type === 'tool') {
        const tool: UIMessagePartTool = part as UIMessagePartTool;
        if (tool.metadata?.['recipe_manual'] === true || tool.metadata?.['plugin_manual'] === true) return tool.toolCallId;
      }
    }
    return '';
  }
  private async perform(session: ConversationSession, record: ActiveRun, command: ConversationRunCommand): Promise<void> {
    let failure: Error | null = null;
    let finalized: Conversation | null = null;
    let manualToolCallId: string = '';
    try {
      const loaded: Conversation | null = await this.write(session, async (): Promise<Conversation | null> => {
        const latest: Conversation | null = await this.loadCanonical(session);
        session.latest = latest;
        return latest;
      });
      if (loaded === null) throw new Error('会话不存在或已删除');
      await this.queueOperation(session, async (): Promise<void> => {});
      const seed: PreparedConversationSeed = await prepareConversationSeed(loaded);
      if (record.controller.signal.aborted) throw new Error('会话运行已取消');
      // Auxiliary field mutations may have completed while provider/seed hydration awaited.
      const hydrated: Conversation = session.latest ?? loaded;
      const context: DetachedConversationContext = new DetachedConversationContext(
        this, record.id, hydrated, seed, record.controller.signal);
      record.context = context; record.historyCount = currentMessages(loaded).length;
      // 启动历史基线:已有内容不计为本租期接收(含再生命运目标;新分支消息以新 ID 计入)
      record.tracker.seedBaseline(currentMessages(hydrated));
      session.state.historyNodeCount = loaded.messageNodes.length;
      session.state.visualConversation = hydrated;
      this.applyConversation(session, hydrated);
      session.state.contextWindowTokens = seed.runtime.modelContextWindowTokens;
      session.state.modelLabel = seed.runtime.params.model.modelId;
      session.state.jsSandboxNeeded = seed.runtime.assistant.localTools.includes('javascript_engine');
      this.emit(session);
      record.unsubscribeActivity = context.activityStore.subscribe((activity: SandboxActivityUiState | null): void => {
        context.publish({ liveActivity: activity });
      });
      let conversation: Conversation = context.conversation;
      const userParts: UIMessagePart[] = command.kind === 'send'
        ? applyUserInputRegexes(command.parts, seed.runtime.assistant.regexes) : [];
      if (command.kind === 'edit') {
        let original: UIMessage | null = null;
        for (const node of conversation.messageNodes) {
          const found: UIMessage | undefined = node.messages.find((message): boolean => message.id === command.messageId);
          if (found !== undefined) { original = found; break; }
        }
        if (original === null) throw new Error('待编辑消息已不存在');
        const editParts: UIMessagePart[] = makeUserMessage(command.text).parts.concat(
          original.parts.filter((part: UIMessagePart): boolean => part.type !== 'text'));
        const accepted: UIMessagePart[] = await materializeDocumentParts(
          applyUserInputRegexes(editParts, seed.runtime.assistant.regexes));
        conversation = editMessage(conversation, command.messageId, accepted);
        await invalidateCompacts(getCompactStore(), session.id, 'message_edited');
        await context.save(conversation);
        record.regenerateNodeId = command.nodeId; record.visualNodeId = command.nodeId;
      } else if (command.kind === 'regenerate') {
        record.regenerateNodeId = command.nodeId; record.visualNodeId = command.nodeId;
      } else if (command.kind === 'verdict') {
        const approved: Conversation = applyToolApprovalToConversation(conversation, command.toolCallId, command.verdict, command.locator);
        if (approved === conversation) throw new Error('工具调用已变化或无法唯一定位，请重新打开工具卡片');
        conversation = approved; await context.save(conversation);
        if (conversationHasPendingTools(conversation)) finalized = context.conversation;
      }
      if (record.regenerateNodeId.length > 0) {
        const targetIndex: number = conversation.messageNodes.findIndex((node): boolean => node.id === record.regenerateNodeId);
        if (targetIndex < 0) throw new Error(`runRegenerateAt: node not found: ${record.regenerateNodeId}`);
        if (nodeRole(conversation.messageNodes[targetIndex]) === 'user') {
          const prefix: Conversation = patchConversation(context.conversation, {
            messageNodes: context.conversation.messageNodes.slice(0, targetIndex + 1),
          });
          session.state.visualConversation = prefix;
          session.state.historyNodeCount = prefix.messageNodes.length;
          record.historyCount = currentMessages(prefix).length;
          record.resumeMode = false; record.visualNodeId = '';
        } else session.state.visualConversation = context.conversation;
        await invalidateCompacts(getCompactStore(), session.id, 'message_regenerated');
      }
      if (command.kind === 'send') {
        const pureText: boolean = userParts.every((part: UIMessagePart): boolean => part.type === 'text');
        const answer: string = userParts.filter((part: UIMessagePart): boolean => part.type === 'text')
          .map((part: UIMessagePart): string => (part as UIMessagePartText).text).join('\n');
        const blocker = resolveIdleToolBlocker(conversation, answer, pureText);
        if (blocker.changed) {
          await this.queueOperation(session, async (): Promise<void> => recordPendingQueueEvent(getChatKvStore(), session.id, {
            event: blocker.event ?? 'pending_tool_cancel', messageId: command.dispatchMessageId,
            detail: blocker.blockingToolNames.join(','),
          }));
          conversation = blocker.conversation; await context.save(conversation);
          record.resumeMode = blocker.shouldResume;
          if (blocker.shouldResume) record.inputCommitted = true;
        }
      }
      if (record.resumeMode && record.regenerateNodeId.length === 0) {
        record.visualNodeId = conversation.messageNodes.length > 0 ? conversation.messageNodes[conversation.messageNodes.length - 1].id : '';
        manualToolCallId = this.manualId(conversation, command.kind === 'resume' ? command.manualToolCallId : '');
      }
      if (record.regenerateNodeId.length === 0) session.state.visualConversation = context.conversation;
      session.state.regenNodeId = record.visualNodeId;
      this.emit(session);
      if (record.controller.signal.aborted) throw new Error('会话运行已取消');
      if (finalized === null) {
        const deps: ChatTurnDeps = buildConversationTurnDeps(context);
        deps.onUpdate = (messages: UIMessage[]): void => this.updateVisual(session, record, messages);
        deps.onRawFlushSnapshot = (messages: UIMessage[]): void => this.captureRaw(session, record, messages);
        const loop: ToolLoopOptions = await buildConversationToolLoop(context);
        if (manualToolCallId.length > 0) loop.manualToolCallId = manualToolCallId;
        if (record.controller.signal.aborted) throw new Error('会话运行已取消');
        const host: ChatKernelHost = createChatKernelHost({
          onPhase: (phase: KernelPhase): void => {
            if (session.record === record) { session.state.phase = phase; this.emit(session); }
          },
        });
        record.kernel = host;
        const outcome = await host.run(context.conversation, deps,
          async (runtimeDeps: ChatTurnDeps): Promise<Conversation> => {
            if (record.regenerateNodeId.length > 0) return await runRegenerateAtWithTools(
              context.conversation, record.regenerateNodeId, runtimeDeps, loop);
            if (record.resumeMode) return await runToolLoopContinuation(context.conversation, runtimeDeps, loop);
            if (command.kind !== 'send') throw new Error('会话命令没有生成入口');
            const accepted: UIMessagePart[] = await materializeDocumentParts(userParts);
            if (record.controller.signal.aborted) throw new Error('会话运行已取消');
            return await runChatTurnWithTools(context.conversation, accepted, runtimeDeps, loop);
          });
        finalized = outcome.conversation;
      }
      if (record.writeError !== null) throw record.writeError;
      if (record.controller.signal.aborted && record.raw.length > 0) {
        // Kernel cancellation tracks engine writes; the newest raw checkpoint belongs to this same session tail.
        await session.writeTail;
        finalized = record.regenerateNodeId.length > 0
          ? checkpointRegenerateConversation(context.conversation, record.regenerateNodeId, record.raw)
          : checkpointConversationTail(context.conversation, record.raw);
      }
      let stopTarget: string = '';
      if (record.regenerateNodeId.length > 0) {
        const target = finalized.messageNodes.find((node): boolean => node.id === record.regenerateNodeId);
        if (target !== undefined && nodeRole(target) === 'assistant') stopTarget = record.regenerateNodeId;
      }
      if (record.controller.signal.aborted) finalized = applyStopGeneration(finalized, nowIso(), stopTarget) ?? finalized;
      await context.save(finalized);
      finalized = context.conversation;
      if (record.cancelQueue !== null) await record.cancelQueue;
      // Optional UI-footprint reads retain the existing Page behavior; writes above still reject.
      await this.refreshCompacts(session, record).catch((): void => {});
    } catch (error) {
      failure = error instanceof Error ? error : new Error(String(error));
      await session.writeTail;
      // Failure must drain the same latest raw tail as cancellation. Periodic
      // checkpoints can legitimately lag by fewer than 512 characters.
      if (record.writeError === null && record.context !== null && record.raw.length > 0) {
        const context: DetachedConversationContext = record.context;
        const partial: Conversation = record.regenerateNodeId.length > 0
          ? checkpointRegenerateConversation(context.conversation, record.regenerateNodeId, record.raw)
          : checkpointConversationTail(context.conversation, record.raw);
        try { await this.saveContext(context, partial, true); }
        catch (persistError) { failure = persistError instanceof Error ? persistError : new Error(String(persistError)); }
      }
      if (record.writeError !== null) failure = record.writeError;
      try {
        const saved: Conversation | null = await getChatRepository().getById(session.id);
        if (saved !== null) this.applyConversation(session, saved);
      } catch (reloadError) { failure = reloadError instanceof Error ? reloadError : new Error(String(reloadError)); }
    } finally {
      await session.writeTail;
      if (record.cancelQueue !== null) {
        try { await record.cancelQueue; }
        catch (error) { failure = error instanceof Error ? error : new Error(String(error)); }
      }
      if (record.unsubscribeActivity !== null) record.unsubscribeActivity();
      if (session.record === record) {
        session.state.sending = false; session.state.streamingTail = []; session.state.regenTail = null;
        session.state.visualConversation = session.latest;
        session.state.regenNodeId = ''; session.state.retryStatus = ''; session.state.compactActiveCount = 0;
        session.state.awaitingTools = session.latest !== null && conversationHasPendingOrUnexecutedTools(session.latest);
        session.state.phase = failure !== null ? (record.controller.signal.aborted && record.writeError === null ? 'cancelled' : 'failed')
          : (record.controller.signal.aborted ? 'cancelled' : (session.state.awaitingTools ? 'awaiting_approval' : 'completed'));
        session.state.errorMsg = failure === null ? '' : errorText(failure);
        session.record = null;
        // E13:completed/failed/cancelled/awaiting_approval 均注销本 run 的后台活动
        this.reportRunProgress(record, false);
        releaseConversationBrowserRun(session.id, failure === null && !record.controller.signal.aborted && session.state.awaitingTools);
        this.aggregate(); this.emit(session);
      }
      record.release();
    }
    if (failure !== null) { this.drain(session); this.releaseIdle(session); throw failure; }
    if (!record.controller.signal.aborted && session.state.awaitingTools) {
      scheduleJevApprovalTriage(session.id, record.controller.signal);
    }
    if (finalized !== null && record.context !== null && !record.controller.signal.aborted && !session.state.awaitingTools) {
      completeConversationAuxiliary(record.context, finalized, manualToolCallId);
      if (manualToolCallId.length === 0 && session.state.phase === 'completed') {
        this.scheduleCompletion(session, record.id, finalized);
      }
    }
    if (manualToolCallId.length === 0) this.drain(session);
    this.releaseIdle(session);
  }

  private clearCompletion(session: ConversationSession): void {
    session.completionController?.abort();
    session.completionController = null;
    session.completionSourceFingerprint = '';
    session.state.completionNotice = null;
  }

  dismissCompletion(conversationId: string, runId: string): void {
    const session: ConversationSession | undefined = this.sessions.get(conversationId);
    if (session === undefined || session.state.runId !== runId) return;
    this.clearCompletion(session);
    this.emit(session);
  }

  private scheduleCompletion(session: ConversationSession, runId: string, conversation: Conversation): void {
    const facts: JevCompletionFacts | null = jevUnverifiedChanges(currentMessages(conversation));
    if (facts === null) return;
    const controller: EntryAbortController = new EntryAbortController();
    session.completionController = controller;
    session.completionSourceFingerprint = facts.sourceFingerprint;
    evaluateJevCompletion(conversation, controller.signal).then((candidate: JevCompletionFacts | null): void => {
      if (candidate === null || controller.signal.aborted || this.sessions.get(session.id) !== session
        || session.completionController !== controller || session.record !== null || session.state.runId !== runId
        || session.state.phase !== 'completed' || session.state.awaitingTools || session.latest === null
        || !jevCompletionBindingMatches(candidate, currentMessages(session.latest))) return;
      session.state.completionNotice = { runId: runId, finalMessageId: candidate.finalMessageId,
        finalContentFingerprint: candidate.finalContentFingerprint, sourceFingerprint: candidate.sourceFingerprint };
      this.emit(session);
    }).catch((): void => {}).finally((): void => {
      if (session.completionController === controller) session.completionController = null;
    });
  }

  cancel(conversationId: string, runId: string): void {
    cancelJevApprovalTriage(conversationId);
    const session: ConversationSession | undefined = this.sessions.get(conversationId);
    const record: ActiveRun | null = session?.record ?? null;
    if (session !== undefined) this.clearCompletion(session);
    if (session === undefined || record === null || record.id !== runId || record.controller.signal.aborted) return;
    record.kernel?.cancel('user'); record.controller.abort();
    record.cancelQueue = this.queueOperation(session, async (): Promise<void> => {
      const converted = convertSteerToFollowup(session.queue);
      if (!converted.changed) return;
      const previous: PendingUserMessage[] = session.queue; session.queue = converted.messages;
      try { await persistPendingQueue(getChatKvStore(), session.id, session.queue); }
      catch (error) { session.queue = previous; throw error instanceof Error ? error : new Error(String(error)); }
      this.emit(session);
    });
    record.cancelQueue.catch((error: Error): void => { session.state.errorMsg = error.message; this.emit(session); });
  }
  pending(conversationId: string): PendingUserMessage[] { return this.sessions.get(conversationId)?.queue.slice() ?? []; }
  resumePending(conversationId: string): Promise<void> {
    const session: ConversationSession = this.session(conversationId);
    return this.queueOperation(session, async (): Promise<void> => {}).then((): void => this.drain(session));
  }
  consumeSteer(context: DetachedConversationContext): Promise<UIMessage[]> {
    const session: ConversationSession | undefined = this.sessions.get(context.conversationId);
    if (session === undefined || session.record?.id !== context.runId) return Promise.resolve([]);
    return this.queueOperation(session, async (): Promise<UIMessage[]> => {
      const previous: PendingUserMessage[] = session.queue;
      const steers: PendingUserMessage[] = previous.filter((message): boolean => message.mode === 'STEER');
      if (steers.length === 0) return [];
      const accepted: UIMessage[] = [];
      for (const message of steers) {
        const parts: UIMessagePart[] = await materializeDocumentParts(
          applyUserInputRegexes(message.parts, context.seed.runtime.assistant.regexes));
        accepted.push(makeUIMessage('user', parts));
      }
      session.queue = previous.filter((message): boolean => message.mode !== 'STEER');
      try {
        await persistPendingQueue(getChatKvStore(), session.id, session.queue);
        for (const message of steers) await recordPendingQueueEvent(getChatKvStore(), session.id, {
          event: 'dequeue', messageId: message.id, detail: 'steer',
        });
      } catch (error) { session.queue = previous; this.emit(session); throw error instanceof Error ? error : new Error(String(error)); }
      this.emit(session);
      return accepted;
    });
  }
  enqueue(conversationId: string, parts: UIMessagePart[], answer: boolean,
    mode: PendingUserMessageMode = 'FOLLOWUP'): Promise<void> {
    const session: ConversationSession = this.session(conversationId);
    return this.queueOperation(session, async (): Promise<void> => {
      const message: PendingUserMessage = makePendingUserMessage({
        parts: parts, answer: answer, mode: session.record === null ? 'FOLLOWUP' : mode,
      });
      const result = enqueuePendingUserMessage(session.queue, message);
      if (!result.accepted) throw new Error('消息队列已满，请先等待或取消一些排队消息。');
      const previous: PendingUserMessage[] = session.queue; session.queue = result.messages;
      try {
        await persistPendingQueueWithEvent(getChatKvStore(), session.id, session.queue, {
          event: 'enqueue', messageId: message.id, detail: message.mode.toLowerCase(),
        });
      } catch (error) { session.queue = previous; throw error instanceof Error ? error : new Error(String(error)); }
      this.emit(session);
    }).then((): void => this.drain(session));
  }
  cancelPending(conversationId: string, messageId: string): Promise<void> {
    const session: ConversationSession = this.session(conversationId);
    return this.queueOperation(session, async (): Promise<void> => {
      const result = cancelPendingUserMessage(session.queue, messageId);
      if (!result.changed) return;
      const previous: PendingUserMessage[] = session.queue; session.queue = result.messages;
      try { await persistPendingQueueWithEvent(getChatKvStore(), session.id, session.queue, { event: 'cancel', messageId: messageId }); }
      catch (error) { session.queue = previous; throw error instanceof Error ? error : new Error(String(error)); }
      this.emit(session);
    });
  }
  takePending(conversationId: string, messageId: string): Promise<PendingUserMessage | null> {
    const session: ConversationSession = this.session(conversationId);
    return this.queueOperation(session, async (): Promise<PendingUserMessage | null> => {
      const message: PendingUserMessage | undefined = session.queue.find((item): boolean => item.id === messageId);
      if (message === undefined) return null;
      const previous: PendingUserMessage[] = session.queue;
      session.queue = previous.filter((item): boolean => item.id !== messageId);
      try {
        await persistPendingQueueWithEvent(getChatKvStore(), session.id, session.queue, {
          event: 'cancel', messageId: messageId, detail: 'restore_to_input',
        });
      } catch (error) { session.queue = previous; throw error instanceof Error ? error : new Error(String(error)); }
      this.emit(session);
      return message;
    });
  }
  clearPending(conversationId: string): Promise<void> {
    const session: ConversationSession = this.session(conversationId);
    return this.queueOperation(session, async (): Promise<void> => {
      const previous: PendingUserMessage[] = session.queue;
      if (previous.length === 0) return;
      session.queue = [];
      try { await persistPendingQueueWithEvent(getChatKvStore(), session.id, [], { event: 'clear', count: previous.length }); }
      catch (error) { session.queue = previous; throw error instanceof Error ? error : new Error(String(error)); }
      this.emit(session);
    });
  }
  append(conversationId: string, parts: UIMessagePart[]): Promise<void> { return this.appendParts(this.session(conversationId), parts, false); }
  private async appendParts(session: ConversationSession, parts: UIMessagePart[], fromDrain: boolean): Promise<void> {
    if (session.record !== null || session.appendActive || (session.drainActive && !fromDrain)) throw new Error('该会话仍在运行，请将消息排队');
    const release: () => void = beginConversationRun(session.id);
    session.appendActive = true;
    try {
      await this.write(session, async (): Promise<void> => {
        const latest: Conversation | null = await this.loadCanonical(session);
        if (latest === null) throw new Error('会话不存在或已删除');
        const assistants: Assistant[] = await getChatAssistants();
        const assistant: Assistant = assistants.find((item): boolean => item.id === latest.assistantId) ?? assistants[0];
        const accepted: UIMessagePart[] = await materializeDocumentParts(applyUserInputRegexes(parts, assistant.regexes));
        const out: Conversation = await runAppendUserMessage(latest, accepted, getChatRepository());
        this.applyConversation(session, out);
      });
    } finally { session.appendActive = false; release(); this.releaseIdle(session); }
    if (!fromDrain) this.drain(session);
  }
  private drain(session: ConversationSession): void {
    if (session.drainActive || session.record !== null || session.appendActive || session.queue.length === 0
      || (session.latest !== null && conversationHasPendingOrUnexecutedTools(session.latest))) return;
    session.drainActive = true;
    this.drainLoop(session).catch((error: Error): void => {
      session.state.errorMsg = error.message; this.emit(session);
    }).finally((): void => { session.drainActive = false; this.releaseIdle(session); });
  }
  private async drainLoop(session: ConversationSession): Promise<void> {
    if (session.latest === null) {
      const latest: Conversation | null = await this.write(session, (): Promise<Conversation | null> => this.loadCanonical(session));
      if (latest === null) throw new Error('会话不存在或已删除');
      this.applyConversation(session, latest);
    }
    while (session.record === null && !session.appendActive && session.queue.length > 0) {
      if (session.latest !== null && conversationHasPendingOrUnexecutedTools(session.latest)) return;
      const message: PendingUserMessage | null = await this.queueOperation(session, async (): Promise<PendingUserMessage | null> => {
        const next = dequeueNextPendingUserMessage(session.queue);
        if (next.next === null) return null;
        const prepared = preparePendingMessageForDispatch(next.rest, next.next);
        const previous: PendingUserMessage[] = session.queue;
        session.queue = prepared.rest;
        try { await persistPendingQueueWithEvent(getChatKvStore(), session.id, session.queue, {
          event: 'dequeue', messageId: prepared.dispatch.id, detail: prepared.dispatch.mode.toLowerCase(),
        }); }
        catch (error) { session.queue = previous; this.emit(session); throw error instanceof Error ? error : new Error(String(error)); }
        this.emit(session);
        return prepared.dispatch;
      });
      if (message === null) return;
      let dispatchedRun: ActiveRun | null = null;
      try {
        if (message.answer) {
          const command: ConversationSendCommand = { kind: 'send', parts: message.parts, dispatchMessageId: message.id };
          const handle: ConversationRunHandle = this.reserve(session, command, true);
          dispatchedRun = this.sessions.get(session.id)?.record ?? null;
          await handle.done;
        }
        else await this.appendParts(session, message.parts, true);
      } catch (error) {
        // A provider failure after user/answer persistence must not append the same input again.
        if (dispatchedRun !== null && dispatchedRun.inputCommitted) continue;
        await this.queueOperation(session, async (): Promise<void> => {
          // Put the dispatched item in front of the current queue, preserving new arrivals.
          session.queue = [message].concat(session.queue);
          this.emit(session);
          await persistPendingQueueWithEvent(getChatKvStore(), session.id, session.queue, {
            event: 'enqueue', messageId: message.id, detail: message.mode.toLowerCase(),
          });
        });
        throw error instanceof Error ? error : new Error(String(error));
      }
    }
  }
}
