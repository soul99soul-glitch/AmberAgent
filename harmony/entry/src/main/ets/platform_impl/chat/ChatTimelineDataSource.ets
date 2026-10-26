import type { ChatTimelineRow, UIMessage } from '@amber/chat-domain';
import { MessageRowViewState } from '../../components/MessageRowViewState.ets';

export type ChatTimelineItemKind = 'message' | 'compact';

@Observed
export class ChatTimelineItem {
  readonly key: string;
  readonly kind: ChatTimelineItemKind;
  row: ChatTimelineRow;
  message: UIMessage;
  streaming: boolean;
  compactCount: number;
  rowIndex: number;
  readonly viewState: MessageRowViewState;

  constructor(key: string, kind: ChatTimelineItemKind, row: ChatTimelineRow,
    message: UIMessage, streaming: boolean, compactCount: number, rowIndex: number,
    viewState: MessageRowViewState) {
    this.key = key;
    this.kind = kind;
    this.row = row;
    this.message = message;
    this.streaming = streaming;
    this.compactCount = compactCount;
    this.rowIndex = rowIndex;
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
    message: UIMessage, streaming: boolean, compactCount: number, rowIndex: number): ChatTimelineItem {
    const previous: ChatTimelineItem | undefined = this.byKey.get(key);
    if (previous !== undefined) {
      previous.update(row, message, streaming, compactCount, rowIndex);
      return previous;
    }
    return new ChatTimelineItem(key, kind, row, message, streaming, compactCount,
      rowIndex, this.stateFor(message.id));
  }

  sync(rows: ChatTimelineRow[], streamingTail: UIMessage[], regenTail: UIMessage | null,
    regenNodeId: string, compactCount: (messageId: string) => number): void {
    const next: ChatTimelineItem[] = [];
    const positions: Map<string, number> = new Map<string, number>();
    for (let index: number = 0; index < rows.length; index++) {
      const row: ChatTimelineRow = rows[index];
      const regenerating: boolean = regenTail !== null && row.nodeId === regenNodeId;
      const message: UIMessage = regenerating ? regenTail as UIMessage : row.message;
      const count: number = compactCount(row.message.id);
      positions.set(message.id, next.length);
      next.push(this.itemFor(`message:${message.id}`, 'message', row,
        message, regenerating, count, index));
      if (count > 0) {
        next.push(this.itemFor(`compact:${row.message.id}`, 'compact', row,
          row.message, false, count, index));
      }
    }
    for (let index: number = 0; index < streamingTail.length; index++) {
      const message: UIMessage = streamingTail[index];
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
      next.push(this.itemFor(`message:${message.id}`, 'message', row,
        message, true, 0, rows.length + index));
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
