import type {
  ChatTimelineRow, UIMessage, UIMessagePartTool, ChatEpisodeLedger, ChatEpisodeRecord,
  MessageTurnProjection, AssistantTurnRole, TurnProgressSummary,
} from '@amber/chat-domain';
import { MessageRowViewState } from '../../components/MessageRowViewState.ets';

export type ChatTimelineItemKind = 'message' | 'compact' | 'episode';

@Observed
export class ChatTimelineItem {
  readonly key: string;
  readonly kind: ChatTimelineItemKind;
  row: ChatTimelineRow;
  message: UIMessage;
  streaming: boolean;
  compactCount: number;
  rowIndex: number;
  episodeId: string = '';
  episodeTitle: string = '';
  episodeSummary: string = '';
  episodeReason: string = 'manual_new_topic';
  isAssistantContinuation: boolean = false;
  turnRole: AssistantTurnRole = 'none';
  turnHeadline: string = '';
  turnStatusKind: string = 'completed';
  turnStepCount: number = 0;
  turnHasError: boolean = false;
  turnTools: UIMessagePartTool[] = [];
  turnReasoning: string[] = [];
  standaloneToolIds: string[] = [];
  isNewEntrance: boolean = false;
  readonly viewState: MessageRowViewState;

  constructor(key: string, kind: ChatTimelineItemKind, row: ChatTimelineRow,
    message: UIMessage, streaming: boolean, compactCount: number, rowIndex: number,
    viewState: MessageRowViewState, isNewEntrance: boolean = false) {
    this.key = key;
    this.kind = kind;
    this.row = row;
    this.message = message;
    this.streaming = streaming;
    this.compactCount = compactCount;
    this.rowIndex = rowIndex;
    this.isNewEntrance = isNewEntrance;
    this.viewState = viewState;
  }

  update(row: ChatTimelineRow, message: UIMessage, streaming: boolean,
    compactCount: number, rowIndex: number): void {
    const previous: ChatTimelineRow = this.row;
    if (previous.nodeId !== row.nodeId || previous.message !== row.message ||
      previous.branchCount !== row.branchCount || previous.selectIndex !== row.selectIndex ||
      previous.isAssistant !== row.isAssistant || previous.isLastAssistant !== row.isLastAssistant ||
      previous.displayRevision !== row.displayRevision) this.row = row;
    if (this.message !== message) this.message = message;
    if (this.streaming !== streaming) this.streaming = streaming;
    if (this.compactCount !== compactCount) this.compactCount = compactCount;
    if (this.rowIndex !== rowIndex) this.rowIndex = rowIndex;
  }

  updateEpisode(ep: ChatEpisodeRecord): void {
    if (this.episodeId !== ep.id) this.episodeId = ep.id;
    if (this.episodeTitle !== ep.title) this.episodeTitle = ep.title;
    if (this.episodeSummary !== ep.summary) this.episodeSummary = ep.summary;
    if (this.episodeReason !== ep.reason) this.episodeReason = ep.reason;
  }

  updateTurn(proj: MessageTurnProjection): void {
    if (this.isAssistantContinuation !== proj.isAssistantContinuation) {
      this.isAssistantContinuation = proj.isAssistantContinuation;
    }
    if (this.turnRole !== proj.turnRole) this.turnRole = proj.turnRole;
    const summary: TurnProgressSummary | null = proj.turnProgress;
    const nextHeadline: string = summary !== null && summary.showSingleLineStatus ? summary.headline : '';
    const nextKind: string = summary !== null ? summary.statusKind : 'completed';
    const nextSteps: number = summary !== null ? summary.stepCount : 0;
    const nextErr: boolean = summary !== null ? summary.hasError : false;
    const nextTools: UIMessagePartTool[] = summary !== null ? summary.allTools : [];
    const nextReasoning: string[] = summary !== null ? summary.reasoning : [];
    if (this.turnHeadline !== nextHeadline) this.turnHeadline = nextHeadline;
    if (this.turnStatusKind !== nextKind) this.turnStatusKind = nextKind;
    if (this.turnStepCount !== nextSteps) this.turnStepCount = nextSteps;
    if (this.turnHasError !== nextErr) this.turnHasError = nextErr;
    if (this.turnTools.length !== nextTools.length ||
      nextTools.some((t: UIMessagePartTool, i: number): boolean => t !== this.turnTools[i])) {
      this.turnTools = nextTools;
    }
    if (this.turnReasoning.length !== nextReasoning.length ||
      nextReasoning.some((item: string, i: number): boolean => item !== this.turnReasoning[i])) {
      this.turnReasoning = nextReasoning;
    }
    if (this.standaloneToolIds.length !== proj.standaloneToolIds.length ||
      proj.standaloneToolIds.some((id: string, i: number): boolean => id !== this.standaloneToolIds[i])) {
      this.standaloneToolIds = proj.standaloneToolIds;
    }
  }
}

// A source item owns its identity independently of the native row's lifetime.
// Text frames update ObjectLink fields; only topology changes notify LazyForEach.
export class ChatTimelineDataSource implements IDataSource {
  private items: ChatTimelineItem[] = [];
  private byKey: Map<string, ChatTimelineItem> = new Map<string, ChatTimelineItem>();
  private viewStates: Map<string, MessageRowViewState> = new Map<string, MessageRowViewState>();
  private listeners: DataChangeListener[] = [];

  totalCount(): number { return this.items.length; }
  getData(index: number): ChatTimelineItem { return this.items[index]; }

  registerDataChangeListener(listener: DataChangeListener): void {
    if (this.listeners.indexOf(listener) < 0) this.listeners.push(listener);
  }

  unregisterDataChangeListener(listener: DataChangeListener): void {
    const index: number = this.listeners.indexOf(listener);
    if (index >= 0) this.listeners.splice(index, 1);
  }

  rowIndexAt(dataIndex: number): number {
    return dataIndex >= 0 && dataIndex < this.items.length ? this.items[dataIndex].rowIndex : -1;
  }

  dataIndexForRow(rowIndex: number): number {
    return this.items.findIndex((item: ChatTimelineItem): boolean =>
      item.kind === 'message' && item.rowIndex === rowIndex);
  }

  dataIndexForNode(nodeId: string): number {
    if (nodeId.length === 0) return -1;
    return this.items.findIndex((item: ChatTimelineItem): boolean =>
      item.kind === 'message' && item.row.nodeId === nodeId);
  }

  private stateFor(messageId: string): MessageRowViewState {
    let state: MessageRowViewState | undefined = this.viewStates.get(messageId);
    if (state === undefined) {
      state = new MessageRowViewState();
      this.viewStates.set(messageId, state);
    }
    return state;
  }

  private itemFor(key: string, kind: ChatTimelineItemKind, row: ChatTimelineRow,
    message: UIMessage, streaming: boolean, compactCount: number, rowIndex: number,
    animateNew: boolean = false): ChatTimelineItem {
    const previous: ChatTimelineItem | undefined = this.byKey.get(key);
    if (previous !== undefined) {
      previous.update(row, message, streaming, compactCount, rowIndex);
      return previous;
    }
    return new ChatTimelineItem(key, kind, row, message, streaming, compactCount,
      rowIndex, this.stateFor(message.id), animateNew);
  }

  sync(rows: ChatTimelineRow[], streamingTail: UIMessage[], regenTail: UIMessage | null,
    regenNodeId: string, compactCount: (messageId: string) => number,
    episodeLedger?: ChatEpisodeLedger | null,
    projectTurns?: (messages: UIMessage[], streamingIds: ReadonlySet<string>, boundaryIds: ReadonlySet<string>) => MessageTurnProjection[],
    activeStreamingMessageIds?: ReadonlySet<string>): void {
    const hadItems: boolean = this.items.length > 0;
    const episodesByAfterId: Map<string, ChatEpisodeRecord[]> = new Map<string, ChatEpisodeRecord[]>();
    const boundaryIds: Set<string> = new Set<string>();
    if (episodeLedger !== undefined && episodeLedger !== null) {
      for (const ep of episodeLedger.episodes) {
        boundaryIds.add(ep.afterMessageId);
        const list: ChatEpisodeRecord[] = episodesByAfterId.get(ep.afterMessageId) ?? [];
        list.push(ep);
        episodesByAfterId.set(ep.afterMessageId, list);
      }
    }

    const next: ChatTimelineItem[] = [];
    const positions: Map<string, number> = new Map<string, number>();
    const streamingIds: Set<string> = new Set<string>();
    for (let index: number = 0; index < rows.length; index++) {
      const row: ChatTimelineRow = rows[index];
      const regenerating: boolean = regenTail !== null && row.nodeId === regenNodeId;
      const message: UIMessage = regenerating ? regenTail as UIMessage : row.message;
      const isUnfinished: boolean = message.finishedAt === null || message.finishedAt === undefined || message.finishedAt.length === 0;
      const rowStreaming: boolean = regenerating ||
        (activeStreamingMessageIds !== undefined && activeStreamingMessageIds.has(message.id) && isUnfinished);
      if (rowStreaming) streamingIds.add(message.id);
      const count: number = compactCount(row.message.id);
      positions.set(message.id, next.length);
      const msgKey: string = `message:${message.id}`;
      const isTailNew: boolean = hadItems && index === rows.length - 1 && !this.byKey.has(msgKey);
      next.push(this.itemFor(msgKey, 'message', row, message, rowStreaming, count, index, isTailNew));
      if (count > 0) {
        next.push(this.itemFor(`compact:${row.message.id}`, 'compact', row,
          row.message, false, count, index));
      }
      const sealedHere: ChatEpisodeRecord[] | undefined = episodesByAfterId.get(row.message.id);
      if (sealedHere !== undefined) {
        for (const ep of sealedHere) {
          const epItem: ChatTimelineItem = this.itemFor(`episode:${ep.id}`, 'episode', row, row.message, false, 0, index);
          epItem.updateEpisode(ep);
          next.push(epItem);
        }
      }
    }
    if (regenTail !== null) streamingIds.add(regenTail.id);
    for (let index: number = 0; index < streamingTail.length; index++) {
      const message: UIMessage = streamingTail[index];
      streamingIds.add(message.id);
      const existingIndex: number | undefined = positions.get(message.id);
      if (existingIndex !== undefined) {
        const existing: ChatTimelineItem = next[existingIndex];
        existing.update(existing.row, message, true, existing.compactCount, existing.rowIndex);
        continue;
      }
      const row: ChatTimelineRow = {
        nodeId: '', message, branchCount: 1, selectIndex: 0,
        isAssistant: message.role === 'assistant', isLastAssistant: true,
        displayRevision: 0, displaySignature: '',
      };
      positions.set(message.id, next.length);
      const streamKey: string = `message:${message.id}`;
      const isNewStream: boolean = hadItems && !this.byKey.has(streamKey);
      next.push(this.itemFor(streamKey, 'message', row, message, true, 0, rows.length + index, isNewStream));
    }

    if (projectTurns !== undefined) {
      const messageItems: ChatTimelineItem[] = [];
      const orderedMessages: UIMessage[] = [];
      for (const item of next) {
        if (item.kind === 'message') {
          messageItems.push(item);
          orderedMessages.push(item.message);
        }
      }
      const projections: MessageTurnProjection[] = projectTurns(orderedMessages, streamingIds, boundaryIds);
      for (let i: number = 0; i < messageItems.length && i < projections.length; i++) {
        messageItems[i].updateTurn(projections[i]);
      }
    }
    const nextKeys: Set<string> = new Set<string>();
    const nextByKey: Map<string, ChatTimelineItem> = new Map<string, ChatTimelineItem>();
    for (const item of next) {
      nextKeys.add(item.key);
      nextByKey.set(item.key, item);
    }
    for (let index: number = this.items.length - 1; index >= 0; index--) {
      if (!nextKeys.has(this.items[index].key)) {
        this.items.splice(index, 1);
        this.notifyDelete(index);
      }
    }
    const retainedKeys: Set<string> = new Set<string>();
    for (const item of this.items) retainedKeys.add(item.key);
    for (let index: number = 0; index < next.length; index++) {
      const item: ChatTimelineItem = next[index];
      if (this.items[index] === item) continue;
      // New prefixes append without searching the old list. Existing items only
      // move for actual reorder; they retain their state even through removal.
      const oldIndex: number = retainedKeys.has(item.key) ? this.items.indexOf(item, index + 1) : -1;
      if (oldIndex >= 0) {
        this.items.splice(oldIndex, 1);
        this.notifyDelete(oldIndex);
      }
      this.items.splice(index, 0, item);
      this.notifyAdd(index);
    }
    this.byKey = nextByKey;
  }

  clear(): void {
    for (let index: number = this.items.length - 1; index >= 0; index--) {
      this.items.splice(index, 1);
      this.notifyDelete(index);
    }
    this.byKey.clear();
    this.viewStates.clear();
  }

  private notifyAdd(index: number): void {
    for (const listener of this.listeners.slice()) listener.onDataAdd(index);
  }

  private notifyDelete(index: number): void {
    for (const listener of this.listeners.slice()) listener.onDataDelete(index);
  }
}
