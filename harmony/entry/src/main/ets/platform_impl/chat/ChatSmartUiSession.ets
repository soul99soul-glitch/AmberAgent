import { hilog } from '@kit.PerformanceAnalysisKit';
import type {
  Conversation, MessageNode, UIMessage, SmartUiDocument, SmartUiDraftSource, SmartUiScalar,
  SmartUiStateAccessor, SmartUiStateValue, SmartUiDraftStore, SmartUiDraftSaveResult,
  SmartUiWeatherProjection, SmartUiContinuationCandidate, SmartUiNode,
} from '@amber/chat-domain';
import {
  nodeCurrentMessage, projectWeatherReadTool, isSmartUiTaskState, smartUiDefinitionFingerprint,
  smartUiContinuationDocumentForSource, latestSmartUiContinuationSource, createSmartUiController,
  smartUiContinuationSnapshot, isExplicitSmartUiContinuation, appendSmartUiContinuation,
  SMART_UI_MAX_DRAFT_PROMPT_CHARS,
} from '@amber/chat-domain';

const HILOG_DOMAIN: number = 0x0001;
const HILOG_TAG: string = 'Chat.SmartUi';

export interface ChatSmartUiSourceContext {
  conversationId: string;
  messages: UIMessage[];
}

interface SmartUiValueEntry {
  sourceKey: string;
  values: SmartUiStateValue[];
}

interface SmartUiRuntimeEntry {
  sourceKey: string;
  document: SmartUiDocument;
  source: SmartUiDraftSource;
  fingerprint: string;
  ready: boolean;
  streaming: boolean;
  loadEpoch: number;
  userChanged: boolean;
  loaded: boolean;
  lastInteraction: number;
}

export interface SmartUiContinuationCapture {
  sourceKey: string;
  source: SmartUiDraftSource;
  fingerprint: string;
  snapshot: string;
}

const EMPTY_SMART_UI_VALUES: SmartUiStateValue[] = [];

// The page retains this owner across theme subtree remounts. UI dependencies
// stay on its first level; maps and source tokens are private runtime state.
@Observed
export class ChatSmartUiSession {
  smartUiValues: SmartUiValueEntry[] = [];
  smartUiStateRevision: number = 0;
  stateAccessor: SmartUiStateAccessor = {
    get: (_sourceKey: string, _stateId: string): SmartUiScalar | undefined => undefined,
    set: (_sourceKey: string, _stateId: string, _value: SmartUiScalar): void => {},
  };
  private smartUiRuntime: Map<string, SmartUiRuntimeEntry> = new Map<string, SmartUiRuntimeEntry>();
  private smartUiDraftStore: SmartUiDraftStore | null = null;
  private smartUiLoadEpoch: number = 0;
  private smartUiInteractionSequence: number = 0;
  private smartUiSaveError: string = '';
  private smartUiProjectionConversation: Conversation | null = null;
  private smartUiProjectionNodes: MessageNode[] | null = null;
  private smartUiWeatherProjections: SmartUiWeatherProjection[] = [];
  private alive: boolean = false;
  private visible: boolean = false;
  private accessorStarted: boolean = false;
  private onError: (message: string) => void = (_message: string): void => {};

  start(store: SmartUiDraftStore, onError: (message: string) => void): void {
    this.smartUiDraftStore = store;
    this.onError = onError;
    this.alive = true;
    this.visible = true;
    if (this.accessorStarted) return;
    // Called through the page's observed instance, so callbacks retain its V1
    // proxy rather than the raw constructor this.
    this.stateAccessor = {
      get: (sourceKey: string, stateId: string): SmartUiScalar | undefined => this.smartUiValue(sourceKey, stateId),
      set: (sourceKey: string, stateId: string, value: SmartUiScalar): void => {
        this.setSmartUiValue(sourceKey, stateId, value);
      },
    };
    this.accessorStarted = true;
  }

  show(): void {
    if (!this.alive) return;
    this.visible = true;
    this.smartUiRuntime.forEach((entry: SmartUiRuntimeEntry): void => this.loadSmartUiDraft(entry));
  }

  hide(): void {
    this.visible = false;
    this.flush();
  }

  dispose(): void {
    this.alive = false;
    this.visible = false;
    this.flush();
  }

  private flush(): void {
    this.smartUiDraftStore?.flush().catch((error: Error): void => {
      this.smartUiSaveError = `智能 UI 草稿保存失败：${error.message}`;
    });
  }

  async cleanupConversation(conversationId: string): Promise<void> {
    const result = await this.smartUiDraftStore?.cleanupConversation(conversationId);
    if (result !== undefined && result.error !== null) {
      this.smartUiSaveError = `智能 UI 草稿清理失败：${result.error}`;
    }
  }

  private reportError(message: string): void {
    if (this.alive && this.visible) this.onError(message);
  }

  private smartUiEntry(sourceKey: string): SmartUiValueEntry | undefined {
    return this.smartUiValues.find((entry: SmartUiValueEntry): boolean => entry.sourceKey === sourceKey);
  }

  private publishSmartUiValues(values: SmartUiValueEntry[]): void {
    this.smartUiValues = values;
    this.smartUiStateRevision++;
  }

  currentSmartUiWeatherProjections(conversation: Conversation | null): SmartUiWeatherProjection[] {
    if (conversation === null) return [];
    if (this.smartUiProjectionConversation === conversation &&
      this.smartUiProjectionNodes === conversation.messageNodes) return this.smartUiWeatherProjections;
    const projections: SmartUiWeatherProjection[] = [];
    for (const node of conversation.messageNodes) {
      const message: UIMessage = nodeCurrentMessage(node);
      for (const part of message.parts) {
        if (part.type !== 'tool' || part.toolName !== 'weather_read') continue;
        const projection: SmartUiWeatherProjection | null = projectWeatherReadTool(part, {
          conversationId: conversation.id, nodeId: node.id, messageId: message.id, toolCallId: part.toolCallId,
        });
        if (projection !== null) projections.push(projection);
      }
    }
    this.smartUiProjectionConversation = conversation;
    this.smartUiProjectionNodes = conversation.messageNodes;
    this.smartUiWeatherProjections = projections;
    return projections;
  }

  smartUiValue(sourceKey: string, stateId: string): SmartUiScalar | undefined {
    const entry: SmartUiValueEntry | undefined = this.smartUiEntry(sourceKey);
    const item: SmartUiStateValue | undefined = entry?.values.find(
      (value: SmartUiStateValue): boolean => value.id === stateId);
    return item?.value;
  }

  smartUiValuesFor(sourceKey: string): SmartUiStateValue[] {
    return this.smartUiEntry(sourceKey)?.values ?? EMPTY_SMART_UI_VALUES;
  }

  setSmartUiValue(sourceKey: string, stateId: string, value: SmartUiScalar): void {
    if (this.smartUiValue(sourceKey, stateId) === value) return;
    const runtime: SmartUiRuntimeEntry | undefined = this.smartUiRuntime.get(sourceKey);
    if (runtime !== undefined) {
      runtime.userChanged = true;
      if (isSmartUiTaskState(runtime.document, stateId)) {
        runtime.lastInteraction = ++this.smartUiInteractionSequence;
      }
    }
    const entries: SmartUiValueEntry[] = this.smartUiValues.slice();
    const entry: SmartUiValueEntry | undefined = entries.find(
      (candidate: SmartUiValueEntry): boolean => candidate.sourceKey === sourceKey);
    if (entry === undefined) {
      this.publishSmartUiValues(entries.concat([{ sourceKey, values: [{ id: stateId, value }] }]));
      return;
    }
    const values: SmartUiStateValue[] = entry.values.slice();
    const position: number = values.findIndex((item: SmartUiStateValue): boolean => item.id === stateId);
    if (position >= 0) values[position] = { id: stateId, value };
    else values.push({ id: stateId, value });
    this.publishSmartUiValues(entries.map((candidate: SmartUiValueEntry): SmartUiValueEntry =>
      candidate.sourceKey === sourceKey ? { sourceKey, values } : candidate));
  }

  clearSmartUiValues(sourcePrefix: string): void {
    if (sourcePrefix.length === 0) return;
    const prefix: string = `${sourcePrefix}:`;
    this.publishSmartUiValues(this.smartUiValues.filter(
      (entry: SmartUiValueEntry): boolean => !entry.sourceKey.startsWith(prefix)));
    this.smartUiRuntime.forEach((runtime: SmartUiRuntimeEntry, key: string): void => {
      if (!key.startsWith(prefix)) return;
      this.smartUiRuntime.delete(key);
      if (runtime.source.nodeId.length > 0) {
        this.smartUiDraftStore?.remove(runtime.source).catch((error: Error): void => {
          this.smartUiSaveError = `智能 UI 草稿清理失败：${error.message}`;
        });
      }
    });
  }

  onSmartUiStateChange(sourceKey: string, stateId: string, value: SmartUiScalar): void {
    const runtime: SmartUiRuntimeEntry | undefined = this.smartUiRuntime.get(sourceKey);
    // Keep transient invalid edits in the page owner too, so text continuation cannot reuse an older value.
    if (runtime !== undefined && this.smartUiValue(sourceKey, stateId) !== value) {
      this.setSmartUiValue(sourceKey, stateId, value);
    }
    this.saveSmartUiEntry(runtime, 250);
  }

  private saveSmartUiEntry(runtime: SmartUiRuntimeEntry | undefined, delayMs: number): void {
    const store: SmartUiDraftStore | null = this.smartUiDraftStore;
    if (runtime === undefined || store === null || !runtime.ready || runtime.source.nodeId.length === 0) return;
    store.saveDebounced(runtime.document, runtime.source, this.smartUiValuesFor(runtime.sourceKey), Date.now(), delayMs)
      .then((result: SmartUiDraftSaveResult): void => { this.smartUiSaveResult(runtime.sourceKey, result); })
      .catch((error: Error): void => {
        this.smartUiSaveError = `智能 UI 草稿保存失败：${error.message}`;
        if (this.alive) this.reportError(this.smartUiSaveError);
      });
  }

  // P3 hook: the current definition and lifecycle are already source-keyed here;
  // persistence can attach without re-parsing text groups or changing native cards.
  private replaceSmartUiValues(sourceKey: string, values: SmartUiStateValue[]): void {
    const next: SmartUiValueEntry[] = this.smartUiValues.filter(
      (entry: SmartUiValueEntry): boolean => entry.sourceKey !== sourceKey);
    this.publishSmartUiValues(next.concat([{ sourceKey, values: values.slice() }]));
  }

  private smartUiBlockMatches(left: SmartUiDraftSource, right: SmartUiDraftSource): boolean {
    return left.conversationId === right.conversationId && left.messageId === right.messageId &&
      left.blockId === right.blockId && left.blockIndex === right.blockIndex && left.sourceStart === right.sourceStart;
  }

  private smartUiSourceMatches(left: SmartUiDraftSource, right: SmartUiDraftSource): boolean {
    return left.nodeId === right.nodeId && this.smartUiBlockMatches(left, right);
  }

  private smartUiSaveResult(sourceKey: string, result: SmartUiDraftSaveResult): void {
    if (result.saved || result.reason === 'cancelled' || result.reason === 'document-not-ready') return;
    const message: string = result.error ?? `智能 UI 草稿未保存：${result.reason}`;
    this.smartUiSaveError = message;
    if (this.alive) this.reportError(message);
    else hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'smart UI draft save failed %{public}s: %{public}s', sourceKey, message);
  }

  private loadSmartUiDraft(entry: SmartUiRuntimeEntry): void {
    const store: SmartUiDraftStore | null = this.smartUiDraftStore;
    if (store === null || !entry.ready || entry.source.nodeId.length === 0 || entry.userChanged || entry.loaded) return;
    const epoch: number = ++this.smartUiLoadEpoch;
    entry.loadEpoch = epoch;
    const sourceKey: string = entry.sourceKey;
    const fingerprint: string = entry.fingerprint;
    const source: SmartUiDraftSource = entry.source;
    store.load(entry.document, source).then((restore): void => {
      const current: SmartUiRuntimeEntry | undefined = this.smartUiRuntime.get(sourceKey);
      if (!this.alive || !this.visible || current === undefined || current.loadEpoch !== epoch || current.fingerprint !== fingerprint ||
        !this.smartUiSourceMatches(current.source, source) || current.userChanged) return;
      current.loaded = true;
      if (restore.status === 'restored') this.replaceSmartUiValues(sourceKey, restore.values);
      if (restore.status === 'rejected' && restore.diagnostics.length > 0) {
        this.smartUiSaveError = restore.diagnostics.join('；');
      }
    }).catch((error: Error): void => {
      const current: SmartUiRuntimeEntry | undefined = this.smartUiRuntime.get(sourceKey);
      if (!this.alive || !this.visible || current === undefined || current.loadEpoch !== epoch) return;
      current.loaded = true;
      this.smartUiSaveError = `智能 UI 草稿读取失败：${error.message}`;
      this.reportError(this.smartUiSaveError);
    });
  }

  onSmartUiDocument(sourceKey: string, document: SmartUiDocument, streaming: boolean,
    source: SmartUiDraftSource, complete: boolean): void {
    if (source.conversationId.length === 0 || source.messageId.length === 0) return;
    const ready: boolean = complete && document.status === 'ready' && document.ended;
    const fingerprint: string = `${smartUiDefinitionFingerprint(document)}:${complete ? 'complete' : 'partial'}`;
    const previous: SmartUiRuntimeEntry | undefined = this.smartUiRuntime.get(sourceKey);
    if (previous !== undefined && previous.source.nodeId.length > 0 && source.nodeId.length > 0 &&
      previous.source.nodeId !== source.nodeId) {
      this.replaceSmartUiValues(sourceKey, []);
    }
    const branchChanged: boolean = previous !== undefined && previous.source.nodeId.length > 0 &&
      source.nodeId.length > 0 && previous.source.nodeId !== source.nodeId;
    const entry: SmartUiRuntimeEntry = {
      sourceKey, document, source, fingerprint, ready, streaming,
      loadEpoch: previous?.loadEpoch ?? 0,
      userChanged: branchChanged ? false : (previous?.userChanged ?? false),
      loaded: branchChanged ? false : (previous?.loaded ?? false),
      // Stream growth and completion retain the same card's edits; source replacement clears its owner separately.
      lastInteraction: previous !== undefined && !branchChanged &&
        this.smartUiBlockMatches(previous.source, source) ? previous.lastInteraction : 0,
    };
    if (previous === undefined || previous.fingerprint !== fingerprint ||
      !this.smartUiSourceMatches(previous.source, source)) {
      entry.loaded = false;
      entry.loadEpoch = 0;
    }
    this.smartUiRuntime.set(sourceKey, entry);
    this.loadSmartUiDraft(entry);
    if (ready && source.nodeId.length > 0 && entry.userChanged) this.saveSmartUiEntry(entry, 0);
  }

  reconcileSmartUiSources(conversation: Conversation): void {
    this.smartUiRuntime.forEach((runtime: SmartUiRuntimeEntry, key: string): void => {
      if (runtime.source.nodeId.length === 0 || runtime.source.conversationId !== conversation.id) return;
      const node: MessageNode | undefined = conversation.messageNodes.find(
        (candidate: MessageNode): boolean => candidate.id === runtime.source.nodeId);
      const valid: boolean = node !== undefined && node.messages.some(
        (message: UIMessage): boolean => message.id === runtime.source.messageId);
      if (valid) return;
      this.smartUiRuntime.delete(key);
      this.publishSmartUiValues(this.smartUiValues.filter((entry: SmartUiValueEntry): boolean => entry.sourceKey !== key));
      this.smartUiDraftStore?.remove(runtime.source).catch((error: Error): void => {
        this.smartUiSaveError = `智能 UI 草稿清理失败：${error.message}`;
      });
    });
  }

  cleanupSmartUiMessage(conversationId: string, messageId: string): void {
    this.smartUiRuntime.forEach((runtime: SmartUiRuntimeEntry, key: string): void => {
      if (runtime.source.conversationId !== conversationId || runtime.source.messageId !== messageId) return;
      this.smartUiRuntime.delete(key);
      this.publishSmartUiValues(this.smartUiValues.filter((entry: SmartUiValueEntry): boolean => entry.sourceKey !== key));
      this.smartUiDraftStore?.remove(runtime.source).catch((error: Error): void => {
        this.smartUiSaveError = `智能 UI 草稿清理失败：${error.message}`;
      });
    });
  }

  clearSmartUiMemory(): void {
    this.smartUiRuntime.clear();
    this.publishSmartUiValues([]);
    this.smartUiProjectionConversation = null;
    this.smartUiProjectionNodes = null;
    this.smartUiWeatherProjections = [];
  }

  private smartUiSourceIsCurrent(runtime: SmartUiRuntimeEntry, context: ChatSmartUiSourceContext): boolean {
    if (runtime.source.conversationId !== context.conversationId || !runtime.ready) return false;
    const message: UIMessage | undefined = context.messages.find(
      (candidate: UIMessage): boolean => candidate.id === runtime.source.messageId);
    if (message === undefined) return false;
    const document: SmartUiDocument | null = smartUiContinuationDocumentForSource(message, runtime.source);
    return document !== null && `${smartUiDefinitionFingerprint(document)}:complete` === runtime.fingerprint;
  }

  latestSmartUiContinuationRuntime(context: ChatSmartUiSourceContext): SmartUiRuntimeEntry | undefined {
    if (context.conversationId.length === 0) return undefined;
    const candidates: SmartUiContinuationCandidate[] = [];
    this.smartUiRuntime.forEach((runtime: SmartUiRuntimeEntry): void => {
      candidates.push({ sourceKey: runtime.sourceKey, conversationId: runtime.source.conversationId,
        messageId: runtime.source.messageId, ready: runtime.ready, streaming: runtime.streaming,
        lastInteraction: runtime.lastInteraction });
    });
    const key: string | null = latestSmartUiContinuationSource(candidates, context.conversationId,
      context.messages.map((message: UIMessage): string => message.id));
    return key === null ? undefined : this.smartUiRuntime.get(key);
  }

  private smartUiSnapshot(runtime: SmartUiRuntimeEntry): string | null {
    const controller = createSmartUiController(this.stateAccessor);
    return smartUiContinuationSnapshot(runtime.document, this.smartUiValuesFor(runtime.sourceKey),
      (stateId: string, value: SmartUiScalar): boolean => controller.isValueValid(runtime.document, stateId, value));
  }

  captureSmartUiContinuation(text: string, context: ChatSmartUiSourceContext): SmartUiContinuationCapture | null {
    if (!isExplicitSmartUiContinuation(text)) return null;
    const runtime: SmartUiRuntimeEntry | undefined = this.latestSmartUiContinuationRuntime(context);
    if (runtime === undefined || !this.smartUiSourceIsCurrent(runtime, context)) return null;
    const snapshot: string | null = this.smartUiSnapshot(runtime);
    if (snapshot === null) return null;
    return { sourceKey: runtime.sourceKey, source: runtime.source, fingerprint: runtime.fingerprint, snapshot };
  }

  smartUiContinuationCurrent(capture: SmartUiContinuationCapture | null, context: ChatSmartUiSourceContext): boolean {
    if (capture === null) return true;
    const runtime: SmartUiRuntimeEntry | undefined = this.smartUiRuntime.get(capture.sourceKey);
    const latest: SmartUiRuntimeEntry | undefined = this.latestSmartUiContinuationRuntime(context);
    const current: boolean = runtime !== undefined && latest?.sourceKey === capture.sourceKey &&
      this.smartUiSourceIsCurrent(runtime, context) &&
      (this.smartUiSourceMatches(runtime.source, capture.source) ||
        (capture.source.nodeId.length === 0 && this.smartUiBlockMatches(runtime.source, capture.source))) &&
      runtime.fingerprint === capture.fingerprint && this.smartUiSnapshot(runtime) === capture.snapshot;
    if (!current) this.reportError('智能 UI 的选择或来源已变化，请确认后重新发送');
    return current;
  }

  draft(nodeId: string, sourceKey: string, context: ChatSmartUiSourceContext): string | null {
    const runtime: SmartUiRuntimeEntry | undefined = this.smartUiRuntime.get(sourceKey);
    if (runtime === undefined || !this.smartUiSourceIsCurrent(runtime, context)) {
      this.reportError('这张智能 UI 已变化，请在当前消息中重新选择');
      return null;
    }
    const node: SmartUiNode | undefined = runtime.document.nodes.find(
      (candidate: SmartUiNode): boolean => candidate.id === nodeId);
    const controller = createSmartUiController(this.stateAccessor);
    const values: SmartUiStateValue[] = controller.reconcile(runtime.document, sourceKey,
      this.smartUiValuesFor(sourceKey), []).values;
    const prompt: string | null = controller.draft(runtime.document, node?.action, values);
    const snapshot: string | null = smartUiContinuationSnapshot(runtime.document, values,
      (stateId: string, value: SmartUiScalar): boolean => controller.isValueValid(runtime.document, stateId, value));
    if (prompt === null || snapshot === null) {
      this.reportError('当前选择不完整或内容过长，请检查后再继续');
      return null;
    }
    const draft: string = appendSmartUiContinuation(prompt, snapshot);
    if (draft.length > SMART_UI_MAX_DRAFT_PROMPT_CHARS) {
      this.reportError('智能 UI 草稿内容过长，请缩短输入后再继续');
      return null;
    }
    runtime.lastInteraction = ++this.smartUiInteractionSequence;
    return draft;
  }

}
