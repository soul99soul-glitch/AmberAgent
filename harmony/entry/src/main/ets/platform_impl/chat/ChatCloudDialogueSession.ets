import type {
  Conversation, MessageNode, UIMessage, UIMessagePart, KeyValueStore,
} from '@amber/chat-domain';
import {
  makeUIMessage, makeMessageNode, toMessageNode,
  patchConversation, newId, nowIso,
} from '@amber/chat-domain';
import {
  MacGatewayClient, MacGatewayError, describeMacGatewayError,
  CLOUD_DIALOGUE_ENABLED_KEY, CLOUD_DIALOGUE_STATE_KEY,
  isValidCloudRequestId, validateCloudDialogueText,
  type CloudDialogueClientLike,
  type MacGatewayConnection, type CloudDialogueInputState,
  type CloudDialogueReceipt, type CloudDialogueEvent, type CloudDialogueEventsPage,
} from '../MacGatewayClient.ets';

export type { CloudDialogueClientLike } from '../MacGatewayClient.ets';

const CONNECTION_KEY: string = 'mac_gateway_connection_v1';
const POLL_INTERVAL_MS: number = 1000;
const MAX_SEEN_EVENT_IDS: number = 500;
const MAX_STORED_INPUTS: number = 60;
const MAX_STORED_TASKS: number = 30;
const MAX_SINGLE_LINE_CHARS: number = 140;

export interface CloudDialogueInputRecord {
  requestId: string;
  eventId: string;
  kind: 'message' | 'followup' | 'stop';
  text: string;
  topicId: string;
  workItemId: string;
  state: CloudDialogueInputState;
  messageId: string;
  updatedAt: number;
}

export interface CloudDialogueTaskRecord {
  workItemId: string;
  topicId: string;
  title: string;
  status: 'running' | 'completed' | 'blocked' | 'failed' | 'cancelled';
  progressText: string;
  stopRequested: boolean;
  resultText: string;
  updatedAt: number;
}

export interface CloudDialoguePendingRetry {
  kind: 'message' | 'followup' | 'stop';
  workItemId: string;
  text: string;
  requestId: string;
}

interface ExplicitFollowupTarget {
  workItemId: string;
  text: string;
}

interface PersistedCloudDialogueSlice {
  scopeKey: string;
  boundConversationId: string;
  cursor: number;
  seenEventIds: string[];
  inputs: CloudDialogueInputRecord[];
  tasks: CloudDialogueTaskRecord[];
}

export interface ChatCloudDialoguePorts {
  kvStore: KeyValueStore;
  currentConversation: () => Conversation | null;
  mutateConversation: (
    conversationId: string,
    update: (latest: Conversation) => Conversation | null,
  ) => Promise<Conversation | null>;
  error: (message: string) => void;
  autoScrollEnabled: () => boolean;
  jumpToBottom: () => void;
  clientFactory?: (connection: MacGatewayConnection) => CloudDialogueClientLike;
}

export const formatCloudInputStateBadge = (
  kind: 'message' | 'followup' | 'stop',
  state: CloudDialogueInputState,
  workItemId: string = '',
): string => {
  const prefix: string = kind === 'followup'
    ? (workItemId.length > 0 ? `补充说明(${shortWorkItemId(workItemId)})` : '补充说明')
    : (kind === 'stop' ? '停止请求' : '云端消息');
  if (state === 'received') return `${prefix} · 已接收待消费 [received]`;
  if (state === 'consumed') return `${prefix} · 已消费 [consumed]`;
  return `${prefix} · 已取消未执行 [cancelled]`;
};

export const sanitizeSingleLineProgress = (raw: string): string => {
  const collapsed: string = raw.replace(/[\r\n\t]+/g, ' ').replace(/\s+/g, ' ').trim();
  if (collapsed.length <= MAX_SINGLE_LINE_CHARS) return collapsed;
  return `${collapsed.substring(0, MAX_SINGLE_LINE_CHARS - 1)}…`;
};

export const shortWorkItemId = (workItemId: string): string => {
  const trimmed: string = workItemId.trim();
  if (trimmed.length <= 14) return trimmed;
  return trimmed.substring(0, 14);
};

const formatUserCloudMessageText = (
  kind: 'message' | 'followup',
  text: string,
  state: CloudDialogueInputState,
  workItemId: string,
): string => {
  if (kind === 'followup') {
    const targetLabel: string = workItemId.length > 0 ? ` · 任务 ${shortWorkItemId(workItemId)}` : '';
    return `[补充说明${targetLabel}]\n${text}\n\n— ${formatCloudInputStateBadge('followup', state, workItemId)}`;
  }
  return `${text}\n\n— ${formatCloudInputStateBadge('message', state, workItemId)}`;
};

@Observed
export class ChatCloudDialogueSession {
  enabled: boolean = false;
  paired: boolean = false;
  gatewayName: string = '';
  submitting: boolean = false;
  cursor: number = 0;
  boundConversationId: string = '';
  followupTargetWorkItemId: string = '';
  activeProgressText: string = '';
  tasks: CloudDialogueTaskRecord[] = [];
  inputs: CloudDialogueInputRecord[] = [];
  uiTick: number = 0;

  private ports: ChatCloudDialoguePorts | null = null;
  private connection: MacGatewayConnection | null = null;
  private seenEventIds: string[] = [];
  private pendingRetry: CloudDialoguePendingRetry | null = null;
  private pollTimer: number = -1;
  private pollInFlight: boolean = false;
  private pageAlive: boolean = false;
  private pageVisible: boolean = false;

  start(ports: ChatCloudDialoguePorts): void {
    this.ports = ports;
    this.pageAlive = true;
    this.pageVisible = true;
    this.refreshConfigAndStart();
  }

  show(): void {
    this.pageVisible = true;
    this.refreshConfigAndStart();
  }

  hide(): void {
    this.pageVisible = false;
    this.stopPolling();
  }

  dispose(): void {
    this.pageVisible = false;
    this.pageAlive = false;
    this.stopPolling();
  }

  isActiveMode(): boolean {
    return this.enabled && this.paired && this.connection !== null;
  }

  runningTasks(): CloudDialogueTaskRecord[] {
    return this.tasks.filter((t: CloudDialogueTaskRecord): boolean => t.status === 'running');
  }

  recentInputs(): CloudDialogueInputRecord[] {
    return this.inputs.slice(Math.max(0, this.inputs.length - 4));
  }

  followupTargetTask(): CloudDialogueTaskRecord | null {
    if (this.followupTargetWorkItemId.length === 0) return null;
    const found: CloudDialogueTaskRecord | undefined = this.tasks.find(
      (t: CloudDialogueTaskRecord): boolean => t.workItemId === this.followupTargetWorkItemId && t.status === 'running',
    );
    return found ?? null;
  }

  toggleFollowupTarget(workItemId: string): void {
    if (this.followupTargetWorkItemId === workItemId) {
      this.followupTargetWorkItemId = '';
    } else {
      this.followupTargetWorkItemId = workItemId;
    }
    this.uiTick++;
  }

  clearFollowupTarget(): void {
    if (this.followupTargetWorkItemId.length > 0) {
      this.followupTargetWorkItemId = '';
      this.uiTick++;
    }
  }

  async refreshConfigAndStart(): Promise<void> {
    if (this.ports === null) return;
    try {
      const rawConn: string | null = await this.ports.kvStore.get(CONNECTION_KEY);
      const rawEnabled: string | null = await this.ports.kvStore.get(CLOUD_DIALOGUE_ENABLED_KEY);
      let parsedConn: MacGatewayConnection | null = null;
      if (rawConn !== null && rawConn.length > 0) {
        try {
          parsedConn = JSON.parse(rawConn) as MacGatewayConnection;
        } catch (_e) {
          parsedConn = null;
        }
      }
      const nextPaired: boolean = parsedConn !== null;
      const nextEnabled: boolean = nextPaired && rawEnabled === 'true';
      const prevScope: string = this.scopeKey(this.connection);
      const nextScope: string = this.scopeKey(parsedConn);
      this.connection = parsedConn;
      this.paired = nextPaired;
      this.gatewayName = parsedConn?.name ?? '';
      this.enabled = nextEnabled;
      if (!nextEnabled) {
        this.stopPolling();
        this.followupTargetWorkItemId = '';
        this.uiTick++;
        return;
      }
      if (prevScope !== nextScope || this.seenEventIds.length === 0) {
        await this.restorePersistedSlice(nextScope);
      }
      this.ensurePolling();
      await this.pollOnce();
    } catch (_e) {
      // 配置读取异常不阻塞页面
    }
  }

  private scopeKey(conn: MacGatewayConnection | null): string {
    if (conn === null) return '';
    return `${conn.gatewayId}:${conn.deviceId}`;
  }

  private async restorePersistedSlice(scopeKey: string): Promise<void> {
    if (this.ports === null || scopeKey.length === 0) return;
    const raw: string | null = await this.ports.kvStore.get(CLOUD_DIALOGUE_STATE_KEY);
    if (raw === null || raw.length === 0) {
      this.resetMemoryState();
      return;
    }
    try {
      const slice: PersistedCloudDialogueSlice = JSON.parse(raw) as PersistedCloudDialogueSlice;
      if (slice.scopeKey !== scopeKey) {
        this.resetMemoryState();
        return;
      }
      this.boundConversationId = typeof slice.boundConversationId === 'string' ? slice.boundConversationId : '';
      this.cursor = typeof slice.cursor === 'number' && slice.cursor >= 0 ? Math.floor(slice.cursor) : 0;
      this.seenEventIds = Array.isArray(slice.seenEventIds) ? slice.seenEventIds.slice(-MAX_SEEN_EVENT_IDS) : [];
      this.inputs = Array.isArray(slice.inputs) ? slice.inputs.slice(-MAX_STORED_INPUTS) : [];
      this.tasks = Array.isArray(slice.tasks) ? slice.tasks.slice(-MAX_STORED_TASKS) : [];
      this.syncActiveProgressText();
      this.uiTick++;
    } catch (_e) {
      this.resetMemoryState();
    }
  }

  private resetMemoryState(): void {
    this.boundConversationId = '';
    this.cursor = 0;
    this.seenEventIds = [];
    this.inputs = [];
    this.tasks = [];
    this.activeProgressText = '';
    this.followupTargetWorkItemId = '';
    this.uiTick++;
  }

  private async persistSlice(): Promise<void> {
    if (this.ports === null || this.connection === null) return;
    const slice: PersistedCloudDialogueSlice = {
      scopeKey: this.scopeKey(this.connection),
      boundConversationId: this.boundConversationId,
      cursor: this.cursor,
      seenEventIds: this.seenEventIds.slice(-MAX_SEEN_EVENT_IDS),
      inputs: this.inputs.slice(-MAX_STORED_INPUTS),
      tasks: this.tasks.slice(-MAX_STORED_TASKS),
    };
    await this.ports.kvStore.put(CLOUD_DIALOGUE_STATE_KEY, JSON.stringify(slice));
  }

  private ensurePolling(): void {
    if (!this.pageAlive || !this.pageVisible || !this.isActiveMode() || this.pollTimer >= 0) return;
    this.pollTimer = setInterval((): void => {
      this.pollOnce().catch((): void => {});
    }, POLL_INTERVAL_MS);
  }

  private stopPolling(): void {
    if (this.pollTimer >= 0) {
      clearInterval(this.pollTimer);
      this.pollTimer = -1;
    }
  }

  private client(): CloudDialogueClientLike {
    const conn: MacGatewayConnection | null = this.connection;
    if (conn === null) {
      throw new MacGatewayError('unreachable', '未配对 Mac Gateway');
    }
    if (this.ports !== null && this.ports.clientFactory !== undefined) {
      return this.ports.clientFactory(conn);
    }
    return MacGatewayClient.of(conn);
  }

  private resolveRequestId(kind: 'message' | 'followup' | 'stop', workItemId: string, text: string, explicitId?: string): string {
    if (explicitId !== undefined && explicitId.length > 0) return explicitId;
    const retry: CloudDialoguePendingRetry | null = this.pendingRetry;
    if (retry !== null && retry.kind === kind && retry.workItemId === workItemId && retry.text === text) {
      return retry.requestId;
    }
    const prefix: string = kind === 'message' ? 'msg' : (kind === 'followup' ? 'inp' : 'stp');
    const generated: string = `${prefix}-${Date.now()}-${newId().substring(0, 12)}`;
    this.pendingRetry = { kind: kind, workItemId: workItemId, text: text, requestId: generated };
    return generated;
  }

  private clearPendingRetryIfMatched(requestId: string): void {
    if (this.pendingRetry !== null && this.pendingRetry.requestId === requestId) {
      this.pendingRetry = null;
    }
  }

  /**
   * 统一提交入口：
   * - 若已选中补充目标任务（followupTargetWorkItemId）或使用显式前缀 `@task:<id> ` / `/followup <id> `，发送 `inputs`
   * - 否则作为新消息发送 `messages`（即使当前已有运行中云任务，也可直接提独立新问题 B）
   */
  async submitComposerText(rawText: string): Promise<boolean> {
    if (!this.isActiveMode() || this.ports === null) return false;
    const trimmed: string = rawText.trim();
    const validationError: string | null = validateCloudDialogueText(trimmed);
    if (validationError !== null) {
      this.ports.error(validationError);
      return false;
    }
    const explicitFollowup = this.parseExplicitFollowupPrefix(trimmed);
    if (explicitFollowup !== null) {
      return await this.submitFollowupInternal(explicitFollowup.workItemId, explicitFollowup.text);
    }
    const targetTask: CloudDialogueTaskRecord | null = this.followupTargetTask();
    if (targetTask !== null) {
      return await this.submitFollowupInternal(targetTask.workItemId, trimmed);
    }
    return await this.submitMessageInternal(trimmed);
  }

  private parseExplicitFollowupPrefix(text: string): ExplicitFollowupTarget | null {
    const match: RegExpMatchArray | null = text.match(/^(?:\/followup|@task:)\s*([A-Za-z0-9._:-]+)\s+([\s\S]+)$/);
    if (match !== null) {
      const target: ExplicitFollowupTarget = { workItemId: match[1], text: match[2].trim() };
      return target;
    }
    if (text.startsWith('/followup ') || text.startsWith('/input ')) {
      const running: CloudDialogueTaskRecord[] = this.runningTasks();
      if (running.length > 0) {
        const body: string = text.replace(/^\/(?:followup|input)\s+/, '').trim();
        if (body.length > 0) {
          const target: ExplicitFollowupTarget = { workItemId: running[0].workItemId, text: body };
          return target;
        }
      }
    }
    return null;
  }

  async sendMessage(text: string, explicitRequestId?: string): Promise<CloudDialogueReceipt> {
    const trimmed: string = text.trim();
    const reqId: string = this.resolveRequestId('message', '', trimmed, explicitRequestId);
    const receipt: CloudDialogueReceipt = await this.client().messages(reqId, trimmed);
    this.clearPendingRetryIfMatched(reqId);
    await this.recordInputReceipt('message', reqId, trimmed, '', receipt);
    await this.pollOnce();
    return receipt;
  }

  async sendFollowup(workItemId: string, text: string, explicitRequestId?: string): Promise<CloudDialogueReceipt> {
    const trimmed: string = text.trim();
    const targetId: string = workItemId.trim();
    const reqId: string = this.resolveRequestId('followup', targetId, trimmed, explicitRequestId);
    const receipt: CloudDialogueReceipt = await this.client().inputs(targetId, reqId, trimmed);
    this.clearPendingRetryIfMatched(reqId);
    await this.recordInputReceipt('followup', reqId, trimmed, targetId, receipt);
    if (this.followupTargetWorkItemId === targetId) {
      this.followupTargetWorkItemId = '';
    }
    await this.pollOnce();
    return receipt;
  }

  async stopTask(workItemId: string, explicitRequestId?: string): Promise<CloudDialogueReceipt | null> {
    if (!this.isActiveMode() || this.ports === null) return null;
    const targetId: string = workItemId.trim();
    if (targetId.length === 0) return null;
    const reqId: string = this.resolveRequestId('stop', targetId, '', explicitRequestId);
    this.submitting = true;
    this.uiTick++;
    try {
      const receipt: CloudDialogueReceipt = await this.client().stop(targetId, reqId);
      this.clearPendingRetryIfMatched(reqId);
      this.markTaskStopRequested(targetId);
      await this.recordInputReceipt('stop', reqId, '停止任务', targetId, receipt);
      if (this.followupTargetWorkItemId === targetId) {
        this.followupTargetWorkItemId = '';
      }
      await this.pollOnce();
      return receipt;
    } catch (e) {
      await this.handleGatewayError(e as Error);
      return null;
    } finally {
      this.submitting = false;
      this.uiTick++;
    }
  }

  private async submitMessageInternal(text: string): Promise<boolean> {
    this.submitting = true;
    this.uiTick++;
    try {
      await this.sendMessage(text);
      return true;
    } catch (e) {
      await this.handleGatewayError(e as Error);
      return false;
    } finally {
      this.submitting = false;
      this.uiTick++;
    }
  }

  private async submitFollowupInternal(workItemId: string, text: string): Promise<boolean> {
    this.submitting = true;
    this.uiTick++;
    try {
      await this.sendFollowup(workItemId, text);
      return true;
    } catch (e) {
      await this.handleGatewayError(e as Error);
      return false;
    } finally {
      this.submitting = false;
      this.uiTick++;
    }
  }

  private async recordInputReceipt(
    kind: 'message' | 'followup' | 'stop',
    requestId: string,
    text: string,
    explicitWorkItemId: string,
    receipt: CloudDialogueReceipt,
  ): Promise<void> {
    const resolvedWorkItemId: string = explicitWorkItemId.length > 0
      ? explicitWorkItemId
      : (receipt.workItemIds.length > 0 ? receipt.workItemIds[0] : '');
    const topicId: string = receipt.topicId ?? '';
    const existingIdx: number = this.inputs.findIndex(
      (item: CloudDialogueInputRecord): boolean => item.requestId === requestId || item.eventId === receipt.eventId,
    );
    let messageId: string = existingIdx >= 0 ? this.inputs[existingIdx].messageId : '';
    if (kind !== 'stop' && messageId.length === 0) {
      messageId = `cloud-input-${receipt.eventId}`;
      const display: string = formatUserCloudMessageText(kind, text, receipt.state, resolvedWorkItemId);
      await this.appendOrUpdateConversationMessage(messageId, 'user', display);
    } else if (kind !== 'stop' && messageId.length > 0) {
      const display: string = formatUserCloudMessageText(kind, text, receipt.state, resolvedWorkItemId);
      await this.appendOrUpdateConversationMessage(messageId, 'user', display);
    }
    const record: CloudDialogueInputRecord = {
      requestId: requestId,
      eventId: receipt.eventId,
      kind: kind,
      text: text,
      topicId: topicId,
      workItemId: resolvedWorkItemId,
      state: receipt.state,
      messageId: messageId,
      updatedAt: Date.now(),
    };
    if (existingIdx >= 0) {
      const nextInputs: CloudDialogueInputRecord[] = this.inputs.slice();
      nextInputs[existingIdx] = record;
      this.inputs = nextInputs;
    } else {
      this.inputs = [...this.inputs, record].slice(-MAX_STORED_INPUTS);
    }
    for (const wid of receipt.workItemIds) {
      this.ensureTaskRecord(wid, topicId, kind === 'message' ? text : '');
    }
    if (explicitWorkItemId.length > 0) {
      this.ensureTaskRecord(explicitWorkItemId, topicId, '');
    }
    await this.persistSlice();
    this.uiTick++;
  }

  private ensureTaskRecord(workItemId: string, topicId: string, fallbackTitle: string): void {
    const trimmedId: string = workItemId.trim();
    if (trimmedId.length === 0) return;
    const idx: number = this.tasks.findIndex((t: CloudDialogueTaskRecord): boolean => t.workItemId === trimmedId);
    const cleanTitle: string = sanitizeSingleLineProgress(fallbackTitle);
    if (idx < 0) {
      const created: CloudDialogueTaskRecord = {
        workItemId: trimmedId,
        topicId: topicId,
        title: cleanTitle.length > 0 ? cleanTitle : `任务 ${shortWorkItemId(trimmedId)}`,
        status: 'running',
        progressText: '云端执行中…',
        stopRequested: false,
        resultText: '',
        updatedAt: Date.now(),
      };
      this.tasks = [...this.tasks, created].slice(-MAX_STORED_TASKS);
      this.syncActiveProgressText();
      return;
    }
    const current: CloudDialogueTaskRecord = this.tasks[idx];
    const updated: CloudDialogueTaskRecord = {
      workItemId: current.workItemId,
      topicId: topicId.length > 0 ? topicId : current.topicId,
      title: current.title.startsWith('任务 ') && cleanTitle.length > 0 ? cleanTitle : current.title,
      status: current.status,
      progressText: current.progressText,
      stopRequested: current.stopRequested,
      resultText: current.resultText,
      updatedAt: Date.now(),
    };
    const next: CloudDialogueTaskRecord[] = this.tasks.slice();
    next[idx] = updated;
    this.tasks = next;
    this.syncActiveProgressText();
  }

  private markTaskStopRequested(workItemId: string): void {
    const idx: number = this.tasks.findIndex((t: CloudDialogueTaskRecord): boolean => t.workItemId === workItemId);
    if (idx < 0) return;
    const current: CloudDialogueTaskRecord = this.tasks[idx];
    const nextTask: CloudDialogueTaskRecord = {
      workItemId: current.workItemId,
      topicId: current.topicId,
      title: current.title,
      status: current.status,
      progressText: current.status === 'running' ? '正在停止…' : current.progressText,
      stopRequested: true,
      resultText: current.resultText,
      updatedAt: Date.now(),
    };
    const next: CloudDialogueTaskRecord[] = this.tasks.slice();
    next[idx] = nextTask;
    this.tasks = next;
    this.syncActiveProgressText();
  }

  async pollOnce(): Promise<void> {
    if (!this.isActiveMode() || this.pollInFlight || this.ports === null) return;
    this.pollInFlight = true;
    try {
      await this.refreshPendingReceipts();
      const page: CloudDialogueEventsPage = await this.client().events(this.cursor, 100);
      let changed: boolean = false;
      if (Array.isArray(page.items) && page.items.length > 0) {
        for (const ev of page.items) {
          if (this.seenEventIds.indexOf(ev.id) >= 0) {
            if (ev.seq > this.cursor) this.cursor = ev.seq;
            continue;
          }
          this.seenEventIds = [...this.seenEventIds, ev.id].slice(-MAX_SEEN_EVENT_IDS);
          await this.applyEvent(ev);
          if (ev.seq > this.cursor) this.cursor = ev.seq;
          changed = true;
        }
        if (typeof page.nextCursor === 'number' && page.nextCursor > this.cursor) {
          this.cursor = page.nextCursor;
          changed = true;
        }
      }
      if (changed) {
        await this.persistSlice();
        this.uiTick++;
      }
    } catch (e) {
      await this.handleGatewayError(e as Error);
    } finally {
      this.pollInFlight = false;
    }
  }

  private async refreshPendingReceipts(): Promise<void> {
    let changed: boolean = false;
    const now: number = Date.now();
    for (let i: number = 0; i < this.inputs.length; i++) {
      const item: CloudDialogueInputRecord = this.inputs[i];
      const needsWorkItem: boolean = item.kind === 'message'
        && item.workItemId.length === 0
        && (now - item.updatedAt) < 15000;
      const needsStateUpdate: boolean = item.state === 'received';
      if (!needsWorkItem && !needsStateUpdate) continue;
      if (!isValidCloudRequestId(item.requestId)) continue;
      try {
        const receipt: CloudDialogueReceipt = await this.client().receipts(item.requestId);
        const resolvedWid: string = item.workItemId.length > 0
          ? item.workItemId
          : (receipt.workItemIds.length > 0 ? receipt.workItemIds[0] : '');
        const resolvedTopic: string = receipt.topicId ?? item.topicId;
        for (const wid of receipt.workItemIds) {
          this.ensureTaskRecord(wid, resolvedTopic, item.kind === 'message' ? item.text : '');
        }
        if (receipt.state !== item.state || resolvedWid !== item.workItemId || resolvedTopic !== item.topicId) {
          const updated: CloudDialogueInputRecord = {
            requestId: item.requestId,
            eventId: item.eventId,
            kind: item.kind,
            text: item.text,
            topicId: resolvedTopic,
            workItemId: resolvedWid,
            state: receipt.state,
            messageId: item.messageId,
            updatedAt: Date.now(),
          };
          const nextInputs: CloudDialogueInputRecord[] = this.inputs.slice();
          nextInputs[i] = updated;
          this.inputs = nextInputs;
          if (updated.kind !== 'stop' && updated.messageId.length > 0) {
            const display: string = formatUserCloudMessageText(
              updated.kind, updated.text, updated.state, updated.workItemId,
            );
            await this.appendOrUpdateConversationMessage(updated.messageId, 'user', display);
          }
          changed = true;
        }
      } catch (_e) {
        // 单条 receipt 查询失败不中断整体轮询
      }
    }
    if (changed) {
      await this.persistSlice();
      this.uiTick++;
    }
  }

  private async applyEvent(ev: CloudDialogueEvent): Promise<void> {
    const wid: string = ev.workItemId ?? '';
    const topicId: string = ev.topicId ?? '';
    if (ev.kind === 'user_message' || ev.kind === 'followup') {
      if (wid.length > 0) {
        this.ensureTaskRecord(wid, topicId, ev.kind === 'user_message' ? ev.text : '');
      }
      return;
    }
    if (ev.kind === 'stop_requested') {
      if (wid.length > 0) this.markTaskStopRequested(wid);
      return;
    }
    if (ev.kind === 'input_consumed' || ev.kind === 'input_cancelled') {
      const nextState: CloudDialogueInputState = ev.kind === 'input_consumed' ? 'consumed' : 'cancelled';
      const prefix: string = ev.kind === 'input_consumed' ? 'consumed:' : 'cancelled:';
      const targetEventId: string = ev.id.startsWith(prefix) ? ev.id.substring(prefix.length) : '';
      await this.updateInputStateByEvent(targetEventId, wid, topicId, nextState);
      return;
    }
    if (ev.kind === 'process_event') {
      // process_event 只允许原地刷新同一处单行进度文案，严禁生成每工具卡片或进入会话消息流
      const progressLine: string = sanitizeSingleLineProgress(ev.text);
      if (wid.length > 0) {
        this.ensureTaskRecord(wid, topicId, '');
        const idx: number = this.tasks.findIndex((t: CloudDialogueTaskRecord): boolean => t.workItemId === wid);
        if (idx >= 0 && this.tasks[idx].status === 'running') {
          const cur: CloudDialogueTaskRecord = this.tasks[idx];
          const nextTask: CloudDialogueTaskRecord = {
            workItemId: cur.workItemId,
            topicId: topicId.length > 0 ? topicId : cur.topicId,
            title: cur.title,
            status: cur.status,
            progressText: progressLine.length > 0 ? progressLine : cur.progressText,
            stopRequested: cur.stopRequested,
            resultText: cur.resultText,
            updatedAt: Date.now(),
          };
          const next: CloudDialogueTaskRecord[] = this.tasks.slice();
          next[idx] = nextTask;
          this.tasks = next;
        }
      }
      this.activeProgressText = progressLine;
      return;
    }
    if (ev.kind === 'assistant_reply') {
      await this.markTopicInputsConsumed(topicId, wid);
      const msgId: string = `cloud-reply-${ev.id}`;
      await this.appendOrUpdateConversationMessage(msgId, 'assistant', ev.text);
      return;
    }
    if (ev.kind === 'task_result') {
      const outcomeRaw: string = ev.outcome ?? 'completed';
      let finalStatus: 'completed' | 'blocked' | 'failed' | 'cancelled' = 'completed';
      if (outcomeRaw === 'blocked') finalStatus = 'blocked';
      else if (outcomeRaw === 'failed') finalStatus = 'failed';
      else if (outcomeRaw === 'cancelled') finalStatus = 'cancelled';
      let taskTitle: string = wid.length > 0 ? `任务 ${shortWorkItemId(wid)}` : '云任务';
      if (wid.length > 0) {
        this.ensureTaskRecord(wid, topicId, '');
        const idx: number = this.tasks.findIndex((t: CloudDialogueTaskRecord): boolean => t.workItemId === wid);
        if (idx >= 0) {
          const cur: CloudDialogueTaskRecord = this.tasks[idx];
          taskTitle = cur.title;
          const nextTask: CloudDialogueTaskRecord = {
            workItemId: cur.workItemId,
            topicId: topicId.length > 0 ? topicId : cur.topicId,
            title: cur.title,
            status: finalStatus,
            progressText: '',
            stopRequested: cur.stopRequested,
            resultText: ev.text,
            updatedAt: Date.now(),
          };
          const next: CloudDialogueTaskRecord[] = this.tasks.slice();
          next[idx] = nextTask;
          this.tasks = next;
        }
        if (this.followupTargetWorkItemId === wid) {
          this.followupTargetWorkItemId = '';
        }
      }
      this.syncActiveProgressText();
      const statusLabel: string = finalStatus === 'completed'
        ? '已完成'
        : (finalStatus === 'cancelled' ? '已停止' : (finalStatus === 'blocked' ? '需确认' : '失败'));
      const header: string = `[${taskTitle} · ${statusLabel}]`;
      const body: string = ev.text.trim().length > 0 ? `${header}\n${ev.text}` : header;
      const msgId: string = `cloud-result-${ev.id}`;
      await this.appendOrUpdateConversationMessage(msgId, 'assistant', body);
    }
  }

  private async markTopicInputsConsumed(topicId: string, workItemId: string): Promise<void> {
    for (let i: number = 0; i < this.inputs.length; i++) {
      const rec: CloudDialogueInputRecord = this.inputs[i];
      if (rec.state !== 'received' || rec.kind !== 'message') continue;
      const topicMatch: boolean = topicId.length > 0 && rec.topicId === topicId;
      const widMatch: boolean = workItemId.length > 0 && rec.workItemId === workItemId;
      if (topicMatch || widMatch) {
        await this.updateInputStateAtIndex(i, 'consumed');
      }
    }
  }

  private async updateInputStateByEvent(
    targetEventId: string,
    workItemId: string,
    topicId: string,
    nextState: CloudDialogueInputState,
  ): Promise<void> {
    let idx: number = -1;
    if (targetEventId.length > 0) {
      idx = this.inputs.findIndex((item: CloudDialogueInputRecord): boolean => item.eventId === targetEventId);
    }
    if (idx < 0 && workItemId.length > 0) {
      idx = this.inputs.findIndex(
        (item: CloudDialogueInputRecord): boolean => item.workItemId === workItemId && item.state === 'received',
      );
    }
    if (idx >= 0) {
      const matched: CloudDialogueInputRecord = this.inputs[idx];
      if (workItemId.length > 0) {
        this.ensureTaskRecord(workItemId, topicId, matched.kind === 'message' ? matched.text : '');
      }
      await this.updateInputStateAtIndex(idx, nextState, workItemId, topicId);
    } else if (workItemId.length > 0 && nextState === 'consumed') {
      this.ensureTaskRecord(workItemId, topicId, '');
    }
  }

  private async updateInputStateAtIndex(
    index: number,
    nextState: CloudDialogueInputState,
    workItemId: string = '',
    topicId: string = '',
  ): Promise<void> {
    if (index < 0 || index >= this.inputs.length) return;
    const cur: CloudDialogueInputRecord = this.inputs[index];
    const nextWid: string = cur.workItemId.length > 0 ? cur.workItemId : workItemId;
    const nextTopic: string = cur.topicId.length > 0 ? cur.topicId : topicId;
    if (cur.state === nextState && cur.workItemId === nextWid && cur.topicId === nextTopic) return;
    const updated: CloudDialogueInputRecord = {
      requestId: cur.requestId,
      eventId: cur.eventId,
      kind: cur.kind,
      text: cur.text,
      topicId: nextTopic,
      workItemId: nextWid,
      state: nextState,
      messageId: cur.messageId,
      updatedAt: Date.now(),
    };
    const nextInputs: CloudDialogueInputRecord[] = this.inputs.slice();
    nextInputs[index] = updated;
    this.inputs = nextInputs;
    if (updated.kind !== 'stop' && updated.messageId.length > 0) {
      const display: string = formatUserCloudMessageText(
        updated.kind, updated.text, updated.state, updated.workItemId,
      );
      await this.appendOrUpdateConversationMessage(updated.messageId, 'user', display);
    }
  }

  private syncActiveProgressText(): void {
    const running: CloudDialogueTaskRecord[] = this.runningTasks();
    if (running.length === 0) {
      this.activeProgressText = '';
      return;
    }
    const latest: CloudDialogueTaskRecord = running[running.length - 1];
    this.activeProgressText = latest.progressText;
  }

  private targetConversationId(): string {
    const current: Conversation | null = this.ports !== null ? this.ports.currentConversation() : null;
    if (current !== null && current.id.length > 0) {
      if (this.boundConversationId.length === 0) {
        this.boundConversationId = current.id;
      }
      return current.id;
    }
    return this.boundConversationId;
  }

  private async appendOrUpdateConversationMessage(
    messageId: string,
    role: 'user' | 'assistant',
    text: string,
  ): Promise<void> {
    if (this.ports === null) return;
    const convId: string = this.targetConversationId();
    if (convId.length === 0) return;
    const ports: ChatCloudDialoguePorts = this.ports;
    let appendedNew: boolean = false;
    await ports.mutateConversation(convId, (latest: Conversation): Conversation | null => {
      const nodes: MessageNode[] = latest.messageNodes.slice();
      for (let i: number = 0; i < nodes.length; i++) {
        const node: MessageNode = nodes[i];
        const msgIdx: number = node.messages.findIndex((m: UIMessage): boolean => m.id === messageId);
        if (msgIdx >= 0) {
          const oldMsg: UIMessage = node.messages[msgIdx];
          const updatedParts: UIMessagePart[] = [{ type: 'text', text: text, metadata: null }];
          const updatedMsg: UIMessage = makeUIMessage(oldMsg.role, updatedParts, {
            id: oldMsg.id,
            createdAt: oldMsg.createdAt,
            finishedAt: oldMsg.finishedAt,
            modelId: oldMsg.modelId,
            usage: oldMsg.usage,
            annotations: oldMsg.annotations,
            translation: oldMsg.translation,
          });
          const nextMsgs: UIMessage[] = node.messages.slice();
          nextMsgs[msgIdx] = updatedMsg;
          nodes[i] = makeMessageNode(nextMsgs, node.selectIndex, node.id);
          return patchConversation(latest, { messageNodes: nodes });
        }
      }
      appendedNew = true;
      const newParts: UIMessagePart[] = [{ type: 'text', text: text, metadata: null }];
      const createdMsg: UIMessage = makeUIMessage(role, newParts, {
        id: messageId,
        createdAt: nowIso(),
        finishedAt: role === 'assistant' ? nowIso() : null,
        modelId: role === 'assistant' ? 'cloud-gateway' : null,
      });
      const nextNodes: MessageNode[] = [...nodes, toMessageNode(createdMsg)];
      const nextTitle: string = latest.title.trim().length === 0 && role === 'user'
        ? sanitizeSingleLineProgress(text).substring(0, 24)
        : latest.title;
      return patchConversation(latest, { messageNodes: nextNodes, title: nextTitle });
    });
    if (appendedNew && ports.autoScrollEnabled()) {
      ports.jumpToBottom();
    }
  }

  private async handleGatewayError(err: Error): Promise<void> {
    if (this.ports === null) return;
    if (err instanceof MacGatewayError && err.kind === 'unauthorized') {
      this.stopPolling();
      this.enabled = false;
      this.paired = false;
      this.connection = null;
      this.followupTargetWorkItemId = '';
      await this.ports.kvStore.delete(CONNECTION_KEY);
      await this.ports.kvStore.delete(CLOUD_DIALOGUE_ENABLED_KEY);
      await this.ports.kvStore.delete(CLOUD_DIALOGUE_STATE_KEY);
      this.ports.error(describeMacGatewayError(err));
      this.uiTick++;
      return;
    }
    this.ports.error(describeMacGatewayError(err));
  }
}
