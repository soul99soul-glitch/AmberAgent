import type { Context } from '@kit.AbilityKit';
import type {
  Conversation, MessageNode, UIMessage, UIMessagePart,
  UIMessagePartReasoning, UIMessagePartTool, KeyValueStore,
  ChatTurnDeps, ToolLoopOptions, ChatKernelHost, SandboxActivityUiState,
} from '@amber/chat-domain';
import {
  newId, nowIso, currentMessages, makeUIMessage, makeMessageNode, toMessageNode,
  patchConversation, applyStopGeneration, createChatKernelHost, runAgenticToolLoop,
  conversationHasPendingTools, applyUserInputRegexes, finishReasoning, finishPendingTools,
  cancelToolByUser, toText,
} from '@amber/chat-domain';
import { EntryAbortController } from './EntryAbortController.ets';
import { beginConversationRun } from './ConversationRunGuard.ets';
import { reportGenerationProgress } from './BackgroundGenerationKeepAlive.ets';
import { GenerationProgressTracker } from './GenerationProgressTracker.ets';
import {
  ConversationRunService, DetachedConversationContext,
  type DetachedTaskHooks, type PreparedConversationSeed, type ConversationRunSnapshot,
} from './ConversationRunService.ets';
import {
  formatCloudInputStateBadge, sanitizeSingleLineProgress, shortWorkItemId,
} from './chat/ChatCloudDialogueSession.ets';

export const LOCAL_ASYNC_TASKS_KEY: string = 'local_async_tasks_state_v1';
export const DEFAULT_MAX_CONCURRENT_LOCAL_TASKS: number = 3;
const MAX_STORED_LOCAL_TASKS: number = 40;
const MAX_STORED_LOCAL_INPUTS: number = 80;
const STREAM_WRITE_THROTTLE_MS: number = 120;

export type LocalTaskStatus = 'queued' | 'running' | 'completed' | 'failed' | 'cancelled' | 'interrupted';
export type LocalTaskInputState = 'received' | 'consumed' | 'cancelled';

export interface LocalTaskRecord {
  taskId: string;
  conversationId: string;
  title: string;
  userNodeId: string;
  assistantNodeId: string;
  assistantMessageId: string;
  status: LocalTaskStatus;
  progressText: string;
  stopRequested: boolean;
  resultText: string;
  createdAt: number;
  updatedAt: number;
}

export interface LocalTaskInputRecord {
  requestId: string;
  taskId: string;
  conversationId: string;
  kind: 'message' | 'followup' | 'stop';
  text: string;
  state: LocalTaskInputState;
  messageId: string;
  updatedAt: number;
}

interface PersistedLocalAsyncState {
  tasks: LocalTaskRecord[];
  inputs: LocalTaskInputRecord[];
}

interface PendingFollowupPayload {
  requestId: string;
  parts: UIMessagePart[];
  text: string;
}

interface ActiveLocalTaskExecution {
  taskId: string;
  conversationId: string;
  controller: EntryAbortController;
  kernel: ChatKernelHost | null;
  tracker: GenerationProgressTracker;
  releaseGuard: () => void;
  unsubscribeActivity: (() => void) | null;
  done: Promise<void>;
  resolveDone: () => void;
}

const summarizeTaskTitle = (rawText: string, parts: UIMessagePart[]): string => {
  const cleaned: string = sanitizeSingleLineProgress(rawText);
  if (cleaned.length > 0) return cleaned.length <= 26 ? cleaned : `${cleaned.substring(0, 25)}…`;
  const hasImage: boolean = parts.some((p: UIMessagePart): boolean => p.type === 'image');
  const hasDoc: boolean = parts.some((p: UIMessagePart): boolean => p.type === 'document');
  if (hasImage && hasDoc) return '图文与文件提问';
  if (hasImage) return '图片提问';
  if (hasDoc) return '文件提问';
  return '新提问';
};

const formatLocalFollowupUserText = (text: string, state: LocalTaskInputState, taskId: string): string => {
  const targetLabel: string = taskId.length > 0 ? ` · 任务 ${shortWorkItemId(taskId)}` : '';
  return `[补充说明${targetLabel}]\n${text}\n\n— ${formatCloudInputStateBadge('followup', state, taskId)}`;
};

const replaceNodeMessageById = (
  conversation: Conversation, nodeId: string, replacement: UIMessage,
): Conversation => {
  const nextNodes: MessageNode[] = conversation.messageNodes.map((node: MessageNode): MessageNode => {
    if (node.id !== nodeId) return node;
    const msgs: UIMessage[] = node.messages.map((msg: UIMessage, idx: number): UIMessage =>
      idx === node.selectIndex ? replacement : msg);
    return makeMessageNode(msgs, node.selectIndex, node.id);
  });
  return patchConversation(conversation, { messageNodes: nextNodes, updateAt: nowIso() });
};

const updateMessageTextById = (
  conversation: Conversation, messageId: string, nextText: string,
): Conversation => {
  if (messageId.length === 0) return conversation;
  let changed: boolean = false;
  const nextNodes: MessageNode[] = conversation.messageNodes.map((node: MessageNode): MessageNode => {
    let nodeChanged: boolean = false;
    const msgs: UIMessage[] = node.messages.map((msg: UIMessage): UIMessage => {
      if (msg.id !== messageId) return msg;
      nodeChanged = true; changed = true;
      return makeUIMessage(msg.role, [{ type: 'text', text: nextText, metadata: null }], {
        id: msg.id, createdAt: msg.createdAt, finishedAt: msg.finishedAt,
        modelId: msg.modelId, usage: msg.usage, annotations: msg.annotations,
      });
    });
    return nodeChanged ? makeMessageNode(msgs, node.selectIndex, node.id) : node;
  });
  return changed ? patchConversation(conversation, { messageNodes: nextNodes, updateAt: nowIso() }) : conversation;
};

const combineTaskGeneratedAssistantMessage = (
  assistantMessageId: string, generatedMessages: UIMessage[], fallbackText: string, finished: boolean,
): UIMessage => {
  const assistantMessages: UIMessage[] = generatedMessages.filter(
    (msg: UIMessage): boolean => msg.role === 'assistant');
  if (assistantMessages.length === 0) {
    return makeUIMessage('assistant', [{ type: 'text', text: fallbackText, metadata: null }], {
      id: assistantMessageId, finishedAt: finished ? nowIso() : null,
    });
  }
  const combinedParts: UIMessagePart[] = [];
  for (const msg of assistantMessages) {
    for (const part of msg.parts) combinedParts.push(part);
  }
  const lastMsg: UIMessage = assistantMessages[assistantMessages.length - 1];
  const base: UIMessage = makeUIMessage(
    'assistant',
    combinedParts.length > 0 ? combinedParts : [{ type: 'text', text: fallbackText, metadata: null }],
    {
      id: assistantMessageId, createdAt: assistantMessages[0].createdAt,
      finishedAt: finished ? (lastMsg.finishedAt ?? nowIso()) : null,
      modelId: lastMsg.modelId, usage: lastMsg.usage, annotations: lastMsg.annotations,
    },
  );
  return finished ? finishReasoning(base) : base;
};

const summarizeStreamingProgress = (generatedMessages: UIMessage[]): string => {
  const assistants: UIMessage[] = generatedMessages.filter((m: UIMessage): boolean => m.role === 'assistant');
  if (assistants.length === 0) return '正在思考…';
  const last: UIMessage = assistants[assistants.length - 1];
  for (let i: number = last.parts.length - 1; i >= 0; i--) {
    const part: UIMessagePart = last.parts[i];
    if (part.type === 'tool') {
      const toolPart: UIMessagePartTool = part as UIMessagePartTool;
      if (toolPart.output.length === 0) return sanitizeSingleLineProgress(`正在调用工具 ${toolPart.toolName}…`);
    }
  }
  const textContent: string = toText(last).trim();
  if (textContent.length > 0) return sanitizeSingleLineProgress(`正在生成（${textContent.length} 字）：${textContent}`);
  for (let i: number = last.parts.length - 1; i >= 0; i--) {
    const part: UIMessagePart = last.parts[i];
    if (part.type === 'reasoning') {
      const preview: string = (part as UIMessagePartReasoning).reasoning.trim();
      return preview.length > 0 ? sanitizeSingleLineProgress(`正在思考：${preview}`) : '正在深度思考…';
    }
  }
  return '正在生成回复…';
};

export class ConversationTaskRunner {
  private readonly runService: ConversationRunService;
  private readonly kvStore: KeyValueStore;
  private readonly maxConcurrentTasks: number;
  private tasks: LocalTaskRecord[] = [];
  private inputs: LocalTaskInputRecord[] = [];
  private readonly initialTaskParts: Map<string, UIMessagePart[]> = new Map<string, UIMessagePart[]>();
  private readonly pendingFollowups: Map<string, PendingFollowupPayload[]> = new Map<string, PendingFollowupPayload[]>();
  private readonly activeExecutions: Map<string, ActiveLocalTaskExecution> = new Map<string, ActiveLocalTaskExecution>();
  private readonly taskDonePromises: Map<string, Promise<void>> = new Map<string, Promise<void>>();
  private readonly taskDoneResolvers: Map<string, () => void> = new Map<string, () => void>();
  private readonly listeners: Array<() => void> = [];
  private hydratePromise: Promise<void> | null = null;
  private hydrated: boolean = false;
  private scheduling: boolean = false;

  constructor(
    runService: ConversationRunService,
    maxConcurrentTasks: number = DEFAULT_MAX_CONCURRENT_LOCAL_TASKS,
  ) {
    this.runService = runService;
    this.kvStore = runService.getStorage().kv;
    this.maxConcurrentTasks = Math.max(1, maxConcurrentTasks);
    this.runService.setExternalActiveConversations((): string[] => this.activeConversationIds());
  }

  subscribe(listener: () => void): () => void {
    this.listeners.push(listener);
    return (): void => {
      const idx: number = this.listeners.indexOf(listener);
      if (idx >= 0) this.listeners.splice(idx, 1);
    };
  }

  private emit(): void {
    this.runService.notifyExternalStateChange();
    for (const listener of this.listeners.slice()) {
      try { listener(); } catch (_e) {}
    }
  }

  activeConversationIds(): string[] {
    const ids: string[] = [];
    for (const task of this.tasks) {
      if ((task.status === 'running' || task.status === 'queued') && ids.indexOf(task.conversationId) < 0) {
        ids.push(task.conversationId);
      }
    }
    return ids;
  }

  runningTaskCount(): number { return this.activeExecutions.size; }

  queuedTaskCount(): number {
    let count: number = 0;
    for (const task of this.tasks) if (task.status === 'queued') count++;
    return count;
  }

  tasksForConversation(conversationId: string): LocalTaskRecord[] {
    return this.tasks.filter((t: LocalTaskRecord): boolean => t.conversationId === conversationId);
  }

  activeAndQueuedTasksForConversation(conversationId: string): LocalTaskRecord[] {
    return this.tasks.filter((t: LocalTaskRecord): boolean =>
      t.conversationId === conversationId && (t.status === 'running' || t.status === 'queued'));
  }

  inputsForConversation(conversationId: string): LocalTaskInputRecord[] {
    return this.inputs.filter((i: LocalTaskInputRecord): boolean => i.conversationId === conversationId);
  }

  waitForTask(taskId: string): Promise<void> {
    return this.taskDonePromises.get(taskId) ?? Promise.resolve();
  }

  async ensureHydrated(): Promise<void> {
    if (this.hydrated) return;
    if (this.hydratePromise !== null) return await this.hydratePromise;
    this.hydratePromise = this.hydrateAndRecoverInternal();
    try { await this.hydratePromise; } finally { this.hydratePromise = null; }
  }

  private async hydrateAndRecoverInternal(): Promise<void> {
    try {
      const raw: string | null = await this.kvStore.get(LOCAL_ASYNC_TASKS_KEY);
      if (raw !== null && raw.trim().length > 0) {
        const parsed: PersistedLocalAsyncState = JSON.parse(raw) as PersistedLocalAsyncState;
        const loadedTasks: LocalTaskRecord[] = Array.isArray(parsed.tasks) ? parsed.tasks : [];
        const loadedInputs: LocalTaskInputRecord[] = Array.isArray(parsed.inputs) ? parsed.inputs : [];
        const interruptedTasks: LocalTaskRecord[] = [];
        const cancelledInputsByConv: Map<string, LocalTaskInputRecord[]> = new Map<string, LocalTaskInputRecord[]>();
        const now: number = Date.now();
        for (const task of loadedTasks) {
          if (task.status === 'running' || task.status === 'queued') {
            task.status = 'interrupted';
            task.stopRequested = false;
            task.progressText = '任务因应用退出已中断 [interrupted]';
            task.updatedAt = now;
            interruptedTasks.push(task);
          }
          this.tasks.push(task);
        }
        for (const input of loadedInputs) {
          if (input.state === 'received') {
            input.state = 'cancelled';
            input.updatedAt = now;
            const list: LocalTaskInputRecord[] = cancelledInputsByConv.get(input.conversationId) ?? [];
            list.push(input);
            cancelledInputsByConv.set(input.conversationId, list);
          }
          this.inputs.push(input);
        }
        for (const task of interruptedTasks) {
          await this.runService.mutateStoredConversation(task.conversationId, (latest: Conversation): Conversation => {
            let nextConv: Conversation = applyStopGeneration(latest, nowIso(), task.assistantNodeId) ?? latest;
            const node: MessageNode | undefined = nextConv.messageNodes.find(
              (n: MessageNode): boolean => n.id === task.assistantNodeId);
            if (node !== undefined) {
              const cur: UIMessage = node.messages[node.selectIndex];
              const existingText: string = toText(cur).trim();
              const interruptedNote: string = '[任务因应用退出已中断]';
              const nextMsg: UIMessage = existingText.length === 0 || existingText.startsWith('[正在')
                ? makeUIMessage('assistant', [{ type: 'text', text: interruptedNote, metadata: null }], {
                  id: cur.id, createdAt: cur.createdAt, finishedAt: nowIso(),
                })
                : finishPendingTools(
                  finishReasoning(makeUIMessage('assistant', cur.parts.concat([
                    { type: 'text', text: `\n\n${interruptedNote}`, metadata: null },
                  ]), {
                    id: cur.id, createdAt: cur.createdAt, finishedAt: nowIso(),
                    modelId: cur.modelId, usage: cur.usage, annotations: cur.annotations,
                  })),
                  cancelToolByUser,
                );
              nextConv = replaceNodeMessageById(nextConv, task.assistantNodeId, nextMsg);
            }
            const cancelledInputs: LocalTaskInputRecord[] = cancelledInputsByConv.get(task.conversationId) ?? [];
            for (const inp of cancelledInputs) {
              if (inp.kind === 'followup' && inp.messageId.length > 0) {
                nextConv = updateMessageTextById(
                  nextConv, inp.messageId, formatLocalFollowupUserText(inp.text, 'cancelled', inp.taskId));
              }
            }
            return nextConv;
          }).catch((): null => null);
        }
        if (interruptedTasks.length > 0 || cancelledInputsByConv.size > 0) await this.persistState();
      }
    } catch (_e) { /* Ignore corrupt persistence snapshot */ }
    this.hydrated = true;
    this.emit();
  }

  private async persistState(): Promise<void> {
    const payload: PersistedLocalAsyncState = {
      tasks: this.tasks.slice(-MAX_STORED_LOCAL_TASKS),
      inputs: this.inputs.slice(-MAX_STORED_LOCAL_INPUTS),
    };
    await this.kvStore.put(LOCAL_ASYNC_TASKS_KEY, JSON.stringify(payload)).catch((): void => {});
  }

  private registerTaskPromise(taskId: string): void {
    let resolver: () => void = (): void => {};
    const promise: Promise<void> = new Promise<void>((resolve): void => { resolver = resolve; });
    this.taskDonePromises.set(taskId, promise);
    this.taskDoneResolvers.set(taskId, resolver);
  }

  private resolveTaskPromise(taskId: string): void {
    const resolver: (() => void) | undefined = this.taskDoneResolvers.get(taskId);
    if (resolver !== undefined) {
      this.taskDoneResolvers.delete(taskId);
      resolver();
    }
  }

  async submitQuestion(
    conversationId: string,
    parts: UIMessagePart[],
    rawText: string,
    fallbackConversation: Conversation | null = null,
  ): Promise<LocalTaskRecord> {
    await this.ensureHydrated();
    const repo = this.runService.getStorage().repository;
    if (fallbackConversation !== null && fallbackConversation.id === conversationId) {
      const existing: Conversation | null = await repo.getById(conversationId);
      if (existing === null) await repo.save(fallbackConversation);
    }
    const taskId: string = newId();
    const requestId: string = newId();
    const now: number = Date.now();
    const title: string = summarizeTaskTitle(rawText, parts);
    const willQueue: boolean = this.runningTaskCount() >= this.maxConcurrentTasks;
    const initialStatus: LocalTaskStatus = willQueue ? 'queued' : 'running';
    const initialProgress: string = willQueue ? '排队等待并发槽位 [queued]' : '正在准备上下文…';
    const placeholderText: string = willQueue ? '[正在排队等待执行…]' : '[正在思考…]';

    const userMsg: UIMessage = makeUIMessage('user', parts);
    const userNode: MessageNode = toMessageNode(userMsg);
    const assistantMsg: UIMessage = makeUIMessage('assistant', [
      { type: 'text', text: placeholderText, metadata: null },
    ]);
    const assistantNode: MessageNode = toMessageNode(assistantMsg);

    this.registerTaskPromise(taskId);
    this.initialTaskParts.set(taskId, parts.slice());

    const task: LocalTaskRecord = {
      taskId, conversationId, title,
      userNodeId: userNode.id, assistantNodeId: assistantNode.id, assistantMessageId: assistantMsg.id,
      status: initialStatus, progressText: initialProgress, stopRequested: false,
      resultText: '', createdAt: now, updatedAt: now,
    };
    const inputRecord: LocalTaskInputRecord = {
      requestId, taskId, conversationId,
      kind: 'message',
      text: rawText.trim().length > 0 ? rawText.trim() : title,
      state: willQueue ? 'received' : 'consumed',
      messageId: userMsg.id,
      updatedAt: now,
    };

    await this.runService.mutateStoredConversation(conversationId, (latest: Conversation): Conversation =>
      patchConversation(latest, {
        messageNodes: latest.messageNodes.concat([userNode, assistantNode]),
        updateAt: nowIso(),
      }));

    this.tasks.push(task);
    this.inputs.push(inputRecord);
    if (this.tasks.length > MAX_STORED_LOCAL_TASKS) {
      this.tasks = this.tasks.slice(this.tasks.length - MAX_STORED_LOCAL_TASKS);
    }
    if (this.inputs.length > MAX_STORED_LOCAL_INPUTS) {
      this.inputs = this.inputs.slice(this.inputs.length - MAX_STORED_LOCAL_INPUTS);
    }
    await this.persistState();
    this.emit();
    this.scheduleNext();
    return task;
  }

  async submitFollowup(
    conversationId: string,
    targetTaskId: string,
    parts: UIMessagePart[],
    text: string,
  ): Promise<LocalTaskInputRecord> {
    await this.ensureHydrated();
    const task: LocalTaskRecord | undefined = this.tasks.find(
      (t: LocalTaskRecord): boolean => t.taskId === targetTaskId && t.conversationId === conversationId);
    if (task === undefined || (task.status !== 'running' && task.status !== 'queued') || task.stopRequested) {
      throw new Error('目标任务已结束或正在停止，无法追加补充说明');
    }
    const requestId: string = newId();
    const now: number = Date.now();
    const cleanText: string = text.trim();
    const userDisplayMsg: UIMessage = makeUIMessage('user', [
      { type: 'text', text: formatLocalFollowupUserText(cleanText, 'received', targetTaskId), metadata: null },
    ]);
    const userNode: MessageNode = toMessageNode(userDisplayMsg);

    await this.runService.mutateStoredConversation(conversationId, (latest: Conversation): Conversation =>
      patchConversation(latest, {
        messageNodes: latest.messageNodes.concat([userNode]),
        updateAt: nowIso(),
      }));

    const inputRecord: LocalTaskInputRecord = {
      requestId, taskId: targetTaskId, conversationId,
      kind: 'followup', text: cleanText, state: 'received',
      messageId: userDisplayMsg.id, updatedAt: now,
    };
    const queue: PendingFollowupPayload[] = this.pendingFollowups.get(targetTaskId) ?? [];
    queue.push({ requestId, parts: parts.slice(), text: cleanText });
    this.pendingFollowups.set(targetTaskId, queue);

    task.progressText = sanitizeSingleLineProgress('已收到补充说明，将在下一推理边界消费…');
    task.updatedAt = now;
    this.inputs.push(inputRecord);
    await this.persistState();
    this.emit();
    return inputRecord;
  }

  async stopTask(conversationId: string, taskId: string): Promise<boolean> {
    await this.ensureHydrated();
    const task: LocalTaskRecord | undefined = this.tasks.find(
      (t: LocalTaskRecord): boolean => t.taskId === taskId && t.conversationId === conversationId);
    if (task === undefined || (task.status !== 'running' && task.status !== 'queued')) return false;
    const now: number = Date.now();
    task.stopRequested = true;
    task.updatedAt = now;

    const cancelledFollowups: LocalTaskInputRecord[] = [];
    for (const inp of this.inputs) {
      if (inp.taskId === taskId && inp.state === 'received') {
        inp.state = 'cancelled';
        inp.updatedAt = now;
        if (inp.kind === 'followup') cancelledFollowups.push(inp);
      }
    }
    this.pendingFollowups.delete(taskId);
    this.inputs.push({
      requestId: newId(), taskId, conversationId, kind: 'stop',
      text: '[停止任务]', state: 'consumed', messageId: '', updatedAt: now,
    });

    if (task.status === 'queued') {
      task.status = 'cancelled';
      task.progressText = '已取消排队';
      this.initialTaskParts.delete(taskId);
      await this.runService.mutateStoredConversation(conversationId, (latest: Conversation): Conversation => {
        let nextConv: Conversation = replaceNodeMessageById(
          latest, task.assistantNodeId,
          makeUIMessage('assistant', [{ type: 'text', text: '[任务已取消]', metadata: null }], {
            id: task.assistantMessageId, finishedAt: nowIso(),
          }),
        );
        for (const f of cancelledFollowups) {
          nextConv = updateMessageTextById(
            nextConv, f.messageId, formatLocalFollowupUserText(f.text, 'cancelled', taskId));
        }
        return nextConv;
      }).catch((): null => null);
      await this.persistState();
      this.emit();
      this.resolveTaskPromise(taskId);
      this.scheduleNext();
      return true;
    }

    task.progressText = '正在停止该任务…';
    if (cancelledFollowups.length > 0) {
      await this.runService.mutateStoredConversation(conversationId, (latest: Conversation): Conversation => {
        let nextConv: Conversation = latest;
        for (const f of cancelledFollowups) {
          nextConv = updateMessageTextById(
            nextConv, f.messageId, formatLocalFollowupUserText(f.text, 'cancelled', taskId));
        }
        return nextConv;
      }).catch((): null => null);
    }
    const active: ActiveLocalTaskExecution | undefined = this.activeExecutions.get(taskId);
    if (active !== undefined) {
      active.kernel?.cancel('user');
      active.controller.abort();
    }
    await this.persistState();
    this.emit();
    return true;
  }

  private scheduleNext(): void {
    if (this.scheduling) return;
    this.scheduling = true;
    try {
      while (this.activeExecutions.size < this.maxConcurrentTasks) {
        const nextTask: LocalTaskRecord | undefined = this.tasks.find((t: LocalTaskRecord): boolean =>
          (t.status === 'queued' || (t.status === 'running' && !this.activeExecutions.has(t.taskId)))
          && !t.stopRequested);
        if (nextTask === undefined) break;
        const now: number = Date.now();
        if (nextTask.status === 'queued') {
          nextTask.status = 'running';
          nextTask.progressText = '正在准备上下文…';
          nextTask.updatedAt = now;
          for (const inp of this.inputs) {
            if (inp.taskId === nextTask.taskId && inp.kind === 'message' && inp.state === 'received') {
              inp.state = 'consumed';
              inp.updatedAt = now;
            }
          }
        }
        this.launchTaskExecution(nextTask);
      }
    } finally {
      this.scheduling = false;
    }
  }

  private reportTaskKeepAlive(active: ActiveLocalTaskExecution, title: string, generationActive: boolean): void {
    const context: Context | undefined = AppStorage.get<Context>('abilityContext');
    if (context === undefined) return;
    const totals = active.tracker.totals();
    reportGenerationProgress(context, {
      source: 'chat', runId: active.taskId, generationActive,
      receivedChars: totals.chars, receivedBytes: totals.bytes,
      conversationId: active.conversationId, title,
    });
  }

  private launchTaskExecution(task: LocalTaskRecord): void {
    const releaseGuard: () => void = beginConversationRun(task.conversationId);
    const controller: EntryAbortController = new EntryAbortController();
    let resolveDone: () => void = (): void => {};
    const done: Promise<void> = new Promise<void>((resolve): void => { resolveDone = resolve; });
    const active: ActiveLocalTaskExecution = {
      taskId: task.taskId, conversationId: task.conversationId, controller, kernel: null,
      tracker: new GenerationProgressTracker(), releaseGuard, unsubscribeActivity: null, done, resolveDone,
    };
    this.activeExecutions.set(task.taskId, active);
    this.reportTaskKeepAlive(active, task.title, true);
    this.emit();

    this.executeTask(task, active).finally((): void => {
      if (active.unsubscribeActivity !== null) {
        active.unsubscribeActivity();
        active.unsubscribeActivity = null;
      }
      this.reportTaskKeepAlive(active, task.title, false);
      this.activeExecutions.delete(task.taskId);
      this.initialTaskParts.delete(task.taskId);
      this.pendingFollowups.delete(task.taskId);
      active.releaseGuard();
      active.resolveDone();
      this.resolveTaskPromise(task.taskId);
      this.emit();
      this.scheduleNext();
    });
  }

  private buildIsolatedWorkingConversation(
    canonical: Conversation, task: LocalTaskRecord, acceptedUserParts: UIMessagePart[],
  ): Conversation {
    const otherIncompleteAssistantNodeIds: Set<string> = new Set<string>();
    for (const candidate of this.tasks) {
      if (candidate.taskId !== task.taskId && candidate.conversationId === task.conversationId &&
        (candidate.status === 'running' || candidate.status === 'queued')) {
        otherIncompleteAssistantNodeIds.add(candidate.assistantNodeId);
      }
    }
    const prefixNodes: MessageNode[] = [];
    for (const node of canonical.messageNodes) {
      if (node.id === task.userNodeId) {
        const cur: UIMessage = node.messages[node.selectIndex];
        const updatedUser: UIMessage = makeUIMessage('user', acceptedUserParts, {
          id: cur.id, createdAt: cur.createdAt,
        });
        prefixNodes.push(makeMessageNode([updatedUser], 0, node.id));
        break;
      }
      if (node.id === task.assistantNodeId || otherIncompleteAssistantNodeIds.has(node.id)) continue;
      prefixNodes.push(node);
    }
    return patchConversation(canonical, { messageNodes: prefixNodes });
  }

  private async consumeFollowupsForTask(
    task: LocalTaskRecord, context: DetachedConversationContext,
  ): Promise<UIMessage[]> {
    if (task.stopRequested || context.signal.aborted) return [];
    const payloads: PendingFollowupPayload[] = this.pendingFollowups.get(task.taskId) ?? [];
    if (payloads.length === 0) return [];
    this.pendingFollowups.delete(task.taskId);

    const now: number = Date.now();
    const consumedRecords: LocalTaskInputRecord[] = [];
    for (const payload of payloads) {
      const rec: LocalTaskInputRecord | undefined = this.inputs.find(
        (i: LocalTaskInputRecord): boolean => i.requestId === payload.requestId);
      if (rec !== undefined && rec.state === 'received') {
        rec.state = 'consumed';
        rec.updatedAt = now;
        consumedRecords.push(rec);
      }
    }
    task.progressText = sanitizeSingleLineProgress('已消费补充说明，继续推理…');
    task.updatedAt = now;

    if (consumedRecords.length > 0) {
      await this.runService.mutateStoredConversation(task.conversationId, (latest: Conversation): Conversation => {
        let nextConv: Conversation = latest;
        for (const rec of consumedRecords) {
          nextConv = updateMessageTextById(
            nextConv, rec.messageId, formatLocalFollowupUserText(rec.text, 'consumed', task.taskId));
        }
        return nextConv;
      }).catch((): null => null);
    }

    const acceptedMessages: UIMessage[] = [];
    const execution = this.runService.getExecution();
    for (const payload of payloads) {
      const transformed: UIMessagePart[] = await execution.materializeDocumentParts(
        applyUserInputRegexes(payload.parts, context.seed.runtime.assistant.regexes));
      acceptedMessages.push(makeUIMessage('user', transformed));
    }
    await this.persistState();
    this.emit();
    return acceptedMessages;
  }

  private async executeTask(task: LocalTaskRecord, active: ActiveLocalTaskExecution): Promise<void> {
    const execution = this.runService.getExecution();
    const storage = this.runService.getStorage();
    let workingConversation: Conversation | null = null;
    let baseMessageCount: number = 0;
    let latestGeneratedMessages: UIMessage[] = [];
    let lastStreamWriteAt: number = 0;
    let context: DetachedConversationContext | null = null;

    const projectToStoredConversation = async (finished: boolean, fallbackText: string): Promise<Conversation | null> => {
      const combined: UIMessage = combineTaskGeneratedAssistantMessage(
        task.assistantMessageId, latestGeneratedMessages, fallbackText, finished);
      return await this.runService.mutateStoredConversation(
        task.conversationId,
        (latest: Conversation): Conversation => replaceNodeMessageById(latest, task.assistantNodeId, combined),
      );
    };

    try {
      const loaded: Conversation | null = await storage.repository.getById(task.conversationId);
      if (loaded === null) throw new Error('会话不存在或已删除');
      if (active.controller.signal.aborted || task.stopRequested) throw new Error('任务已取消');

      const seed: PreparedConversationSeed = await execution.prepareSeed(loaded);
      if (active.controller.signal.aborted || task.stopRequested) throw new Error('任务已取消');

      const rawUserParts: UIMessagePart[] = this.initialTaskParts.get(task.taskId) ?? [
        { type: 'text', text: task.title, metadata: null },
      ];
      const acceptedUserParts: UIMessagePart[] = await execution.materializeDocumentParts(
        applyUserInputRegexes(rawUserParts, seed.runtime.assistant.regexes));
      if (active.controller.signal.aborted || task.stopRequested) throw new Error('任务已取消');

      const refreshed: Conversation = (await storage.repository.getById(task.conversationId)) ?? loaded;
      workingConversation = this.buildIsolatedWorkingConversation(refreshed, task, acceptedUserParts);
      baseMessageCount = currentMessages(workingConversation).length;
      active.tracker.seedBaseline(currentMessages(workingConversation));

      const hooks: DetachedTaskHooks = {
        save: async (snapshot: Conversation): Promise<void> => {
          workingConversation = snapshot;
          if (context !== null) context.conversation = snapshot;
          latestGeneratedMessages = currentMessages(snapshot).slice(baseMessageCount);
          if (!task.stopRequested) {
            task.progressText = summarizeStreamingProgress(latestGeneratedMessages);
            task.updatedAt = Date.now();
            this.emit();
          }
          await projectToStoredConversation(false, '[正在思考…]');
        },
        activeConversation: (): Conversation => workingConversation as Conversation,
        mutateConversation: (update: (latest: Conversation) => Conversation | null): Promise<Conversation | null> =>
          this.runService.mutateStoredConversation(task.conversationId, update),
        publish: (patch: Partial<ConversationRunSnapshot>): void => {
          if (task.stopRequested) return;
          if (patch.retryStatus !== undefined && patch.retryStatus.trim().length > 0) {
            task.progressText = sanitizeSingleLineProgress(patch.retryStatus);
            task.updatedAt = Date.now();
            this.emit();
          }
        },
        consumeSteer: (): Promise<UIMessage[]> =>
          context === null ? Promise.resolve([]) : this.consumeFollowupsForTask(task, context),
      };

      context = new DetachedConversationContext(
        this.runService, task.taskId, workingConversation, seed, active.controller.signal, hooks);

      active.unsubscribeActivity = context.activityStore.subscribe((activity: SandboxActivityUiState | null): void => {
        if (activity === null || task.stopRequested) return;
        const label: string = activity.title.trim().length > 0 ? activity.title.trim() : activity.toolName.trim();
        const detail: string = activity.outputTail.trim().length > 0
          ? activity.outputTail.trim() : activity.inputPreview.trim();
        task.progressText = sanitizeSingleLineProgress(
          detail.length > 0 ? `正在执行 ${label}：${detail}` : `正在执行 ${label}…`);
        task.updatedAt = Date.now();
        this.emit();
      });

      const deps: ChatTurnDeps = execution.buildTurnDeps(context);
      deps.onUpdate = (messages: UIMessage[]): void => {
        if (active.controller.signal.aborted || task.stopRequested) return;
        latestGeneratedMessages = messages.slice(baseMessageCount);
        task.progressText = summarizeStreamingProgress(latestGeneratedMessages);
        task.updatedAt = Date.now();
        this.emit();
        const now: number = Date.now();
        if (now - lastStreamWriteAt >= STREAM_WRITE_THROTTLE_MS) {
          lastStreamWriteAt = now;
          projectToStoredConversation(false, '[正在思考…]').catch((): null => null);
        }
      };
      deps.onRawFlushSnapshot = (messages: UIMessage[]): void => {
        latestGeneratedMessages = messages.slice(baseMessageCount);
        const gained = active.tracker.observe(messages);
        if (gained.chars > 0 || gained.bytes > 0) this.reportTaskKeepAlive(active, task.title, true);
      };

      const loop: ToolLoopOptions = await execution.buildToolLoop(context);
      if (active.controller.signal.aborted || task.stopRequested) throw new Error('任务已取消');

      const host: ChatKernelHost = createChatKernelHost({ onPhase: (): void => {} });
      active.kernel = host;

      const outcome = await host.run(
        workingConversation, deps,
        async (runtimeDeps: ChatTurnDeps): Promise<Conversation> => {
          let currentWorking: Conversation = workingConversation as Conversation;
          currentWorking = await runAgenticToolLoop(currentWorking, runtimeDeps, loop);
          workingConversation = currentWorking;
          if (context !== null) context.conversation = currentWorking;

          while (!active.controller.signal.aborted && !task.stopRequested &&
            !conversationHasPendingTools(currentWorking)) {
            const followups: UIMessage[] = await this.consumeFollowupsForTask(
              task, context as DetachedConversationContext);
            if (followups.length === 0) break;
            const nextNodes: MessageNode[] = currentWorking.messageNodes.slice();
            for (const fMsg of followups) nextNodes.push(toMessageNode(fMsg));
            currentWorking = patchConversation(currentWorking, { messageNodes: nextNodes, updateAt: nowIso() });
            workingConversation = currentWorking;
            if (context !== null) context.conversation = currentWorking;
            currentWorking = await runAgenticToolLoop(currentWorking, runtimeDeps, loop);
            workingConversation = currentWorking;
            if (context !== null) context.conversation = currentWorking;
          }
          return currentWorking;
        },
      );

      workingConversation = outcome.conversation;
      latestGeneratedMessages = currentMessages(workingConversation).slice(baseMessageCount);

      if (active.controller.signal.aborted || task.stopRequested) {
        await this.finalizeStoppedTask(task, latestGeneratedMessages);
        return;
      }

      const finalStored: Conversation | null = await projectToStoredConversation(true, '[已完成]');
      const finalAssistant: UIMessage = combineTaskGeneratedAssistantMessage(
        task.assistantMessageId, latestGeneratedMessages, '[已完成]', true);
      task.status = 'completed';
      task.resultText = toText(finalAssistant).trim();
      task.progressText = sanitizeSingleLineProgress(
        task.resultText.length > 0 ? `已完成：${task.resultText}` : '已完成');
      task.updatedAt = Date.now();
      await this.persistState();
      this.emit();

      if (finalStored !== null && context !== null &&
        this.activeAndQueuedTasksForConversation(task.conversationId).length === 0) {
        execution.completeAuxiliary(context, finalStored, '');
      }
    } catch (error) {
      if (active.controller.signal.aborted || task.stopRequested) {
        await this.finalizeStoppedTask(task, latestGeneratedMessages);
        return;
      }
      const errMsg: string = error instanceof Error ? error.message : String(error);
      task.status = 'failed';
      task.progressText = sanitizeSingleLineProgress(`执行失败：${errMsg}`);
      task.updatedAt = Date.now();
      await projectToStoredConversation(true, `[执行失败：${errMsg}]`).catch((): null => null);
      await this.persistState();
      this.emit();
    }
  }

  private async finalizeStoppedTask(task: LocalTaskRecord, generatedMessages: UIMessage[]): Promise<void> {
    const now: number = Date.now();
    task.status = 'cancelled';
    task.stopRequested = true;
    task.progressText = '已停止';
    task.updatedAt = now;

    const cancelledFollowups: LocalTaskInputRecord[] = [];
    for (const inp of this.inputs) {
      if (inp.taskId === task.taskId && inp.state === 'received') {
        inp.state = 'cancelled';
        inp.updatedAt = now;
        if (inp.kind === 'followup') cancelledFollowups.push(inp);
      }
    }

    const partialMsg: UIMessage = combineTaskGeneratedAssistantMessage(
      task.assistantMessageId, generatedMessages, '[任务已停止]', true);
    task.resultText = toText(partialMsg).trim();

    await this.runService.mutateStoredConversation(task.conversationId, (latest: Conversation): Conversation => {
      let nextConv: Conversation = replaceNodeMessageById(latest, task.assistantNodeId, partialMsg);
      nextConv = applyStopGeneration(nextConv, nowIso(), task.assistantNodeId) ?? nextConv;
      for (const f of cancelledFollowups) {
        nextConv = updateMessageTextById(
          nextConv, f.messageId, formatLocalFollowupUserText(f.text, 'cancelled', task.taskId));
      }
      return nextConv;
    }).catch((): null => null);

    await this.persistState();
    this.emit();
  }
}
