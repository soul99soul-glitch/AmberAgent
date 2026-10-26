import { window, KeyboardAvoidMode, LengthMetrics } from '@kit.ArkUI';
import { inputMethod } from '@kit.IMEKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import type { Conversation, ConversationRepository, MessageNode, UIMessage, ChatTimelineRow, ConversationTimelineLoadState, FollowMode } from '@amber/chat-domain';
import { buildChatTimelineRows, createInitialTimelineLoadState, applyTailWindow, beginLoadOlder, applyOlderPage, canLoadOlder, coversIndex, nextOlderPageOffset, mergeOlderNodes, isCloseEnoughToBottom, FOLLOW_BOTTOM_TOLERANCE_VP, transitionOnUserScroll, transitionOnStreamUpdate, transitionOnGenerateEnd, transitionOnJumpToBottom, shouldScrollOnStreamUpdate, shouldScrollOnAppend } from '@amber/chat-domain';
import { NativeTimelineFollow } from '../../components/NativeTimelineFollow.ets';
import { ChatTimelineDataSource } from './ChatTimelineDataSource.ets';
const HILOG_DOMAIN: number = 0x0001;
const HILOG_TAG: string = 'DeepRead.ChatPage';
export const TIMELINE_PAGE_SIZE: number = 50;
const TIMELINE_MAX_PREFETCH_PAGES: number = 40;
const TIMELINE_MAX_PREFETCH_NODES: number = 2000;
type MsgRow = ChatTimelineRow;
export const buildRows = (conv: Conversation, previousRows: MsgRow[] = []): MsgRow[] => buildChatTimelineRows(conv.messageNodes, previousRows);
export const buildRowsFromNodes = (nodes: MessageNode[], previousRows: MsgRow[] = []): MsgRow[] => buildChatTimelineRows(nodes, previousRows);

export interface ChatTimelineCallbacks {
  currentConversation: () => Conversation | null;
  currentStreamingTail: () => UIMessage[];
  currentRegenTail: () => UIMessage | null;
  currentRegenNodeId: () => string;
  isSending: () => boolean;
  currentSuggestions: () => string[];
  headerHeight: () => number;
  statusBarHeight: () => number;
  activeArtifactInteraction: () => string;
  compactBoundaryCount: (messageId: string) => number;
}

interface TimelinePrependAnchor {
  nodeId: string;
  y: number;
}

interface PendingOlderTimelinePage {
  conversationId: string;
  nodes: MessageNode[];
  oldestLoadedIndex: number;
}

interface ScrollNavigationTimer {
  handle: number;
}

@Observed
export class ChatTimelineSession {
  rows: MsgRow[] = [];
  private lazyDataSource: ChatTimelineDataSource | null = null;
  private dataSourceSyncPending: boolean = false;
  private dataSourceConversationId: string = '';
  private pendingOlderPage: PendingOlderTimelinePage | null = null;
  private prependLayoutCallback: (() => void) | null = null;
  private userScrollInProgress: boolean = false;

  get dataSource(): ChatTimelineDataSource {
    if (this.lazyDataSource === null) this.lazyDataSource = new ChatTimelineDataSource();
    return this.lazyDataSource;
  }

  setRows(rows: MsgRow[]): void {
    this.rows = rows;
    this.scheduleDataSourceSync();
  }

  private scheduleDataSourceSync(): void {
    if (this.dataSourceSyncPending) return;
    this.dataSourceSyncPending = true;
    // A final snapshot can replace the live tail and history in the same turn.
    // Reconcile after both assignments so its stable message slot never leaves.
    Promise.resolve().then((): void => {
      this.dataSourceSyncPending = false;
      if (this.pageAlive) this.syncDataSource();
    });
  }

  syncDataSource(): void {
    const conversationId: string = this.conversation?.id ?? '';
    if (conversationId.length > 0) {
      if (this.dataSourceConversationId.length > 0 && this.dataSourceConversationId !== conversationId) {
        this.cancelPrependRestore();
        if (this.pendingOlderPage !== null) this.prefetching = false;
        this.pendingOlderPage = null;
        this.dataSource.clear();
      }
      this.dataSourceConversationId = conversationId;
    }
    this.dataSource.sync(this.rows, this.streamingTail, this.regenTail,
      this.callbacks?.currentRegenNodeId?.() ?? '',
      (messageId: string): number => this.compactBoundaryCount(messageId));
  }

  private loaderItemCount(): number { return this.timelineLoadState.fullyLoaded ? 0 : 1; }

  listIndexForRow(rowIndex: number): number {
    const dataIndex: number = this.dataSource.dataIndexForRow(rowIndex);
    return dataIndex < 0 ? -1 : dataIndex + this.loaderItemCount();
  }

  timelineLoadState: ConversationTimelineLoadState = createInitialTimelineLoadState();
  prefetching: boolean = false;
  scroller: Scroller = new Scroller();
  smartUiAssetAreas: Map<string, number> = new Map<string, number>();
  nativeFollow: NativeTimelineFollow | null = null;
  // Timer bookkeeping changes on every scroll frame; only visibility belongs in observed UI state.
  private scrollNavigationTimer: ScrollNavigationTimer = { handle: -1 };
  enableAutoScroll: boolean = true;
  showMessageJumper: boolean = true;
  followMode: FollowMode = 'idle';
  jumpButtonVisible: boolean = false;
  atBottomApprox: boolean = true;
  listViewportHeight: number = 0;
  firstVisibleListIndex: number = -1;
  lastVisibleListIndex: number = -1;
  userTouchActive: boolean = false;
  scrollNavigationVisible: boolean = false;
  listReady: boolean = false;
  programmaticScrollPending: boolean = false;
  programmaticScrollToken: number = 0;
  initialScrollArmed: boolean = false;
  initialScrollTarget: number = -1;
  initialScrollScheduleToken: number = 0;
  initialJumpSuppressedUntilUserScroll: boolean = false;
  generationEndScrollPending: boolean = false;
  imeKeyboardHeightVp: number = 0;
  imeListenerAttached: boolean = false;
  imeWindow: window.Window | null = null;
  imeProgrammaticScrollToken: number = 0;
  previousKeyboardAvoidMode: KeyboardAvoidMode = KeyboardAvoidMode.OFFSET;
  keyboardAvoidanceActive: boolean = false;
  private pageAlive: boolean = false;
  private pageVisible: boolean = false;
  private uiContext: UIContext | null = null;
  private repository: ConversationRepository | null = null;
  private callbacks: ChatTimelineCallbacks | null = null;
  start(context: UIContext, repository: ConversationRepository, callbacks: ChatTimelineCallbacks): void {
    this.uiContext = context;
    this.repository = repository;
    this.callbacks = callbacks;
    this.pageAlive = true;
    this.pageVisible = true;
  }
  show(): void { this.pageVisible = true; }
  hide(): void { this.pageVisible = false; this.userScrollInProgress = false; this.applyPendingOlderPage(); this.cancelPrependRestore(); this.hideScrollNavigation(); this.nativeFollow?.cancel(); this.detachKeyboardListener(); this.restoreKeyboardAvoidance(); this.imeKeyboardHeightVp = 0; }
  dispose(): void { this.hide(); this.pageAlive = false; this.initialScrollScheduleToken++; this.imeProgrammaticScrollToken++; this.lazyDataSource?.clear(); }
  getUIContext(): UIContext { return this.uiContext as UIContext; }
  get conversation(): Conversation | null { return this.callbacks?.currentConversation() ?? null; }
  get streamingTail(): UIMessage[] { return this.callbacks?.currentStreamingTail() ?? []; }
  get regenTail(): UIMessage | null { return this.callbacks?.currentRegenTail() ?? null; }
  get sending(): boolean { return this.callbacks?.isSending() ?? false; }
  get suggestions(): string[] { return this.callbacks?.currentSuggestions() ?? []; }
  get chatHeaderHeight(): number { return this.callbacks?.headerHeight() ?? 0; }
  get statusBarHeight(): number { return this.callbacks?.statusBarHeight() ?? 0; }
  get activeArtifactInteraction(): string { return this.callbacks?.activeArtifactInteraction() ?? ''; }
  compactBoundaryCount(messageId: string): number { return this.callbacks?.compactBoundaryCount(messageId) ?? 0; }

  refreshRowsAfterConversationChange(
    previous: Conversation | null,
    next: Conversation,
  ): void {
    const state: ConversationTimelineLoadState = this.timelineLoadState;
    if (!state.initialized || state.fullyLoaded) {
      this.setRows(buildRows(next, this.rows));
      this.timelineLoadState = applyTailWindow(
        state, 0, next.messageNodes.length, next.messageNodes.length);
      return;
    }

    const previousLength: number = previous !== null ? previous.messageNodes.length : 0;
    const previousLastId: string = previousLength > 0
      ? (previous as Conversation).messageNodes[previousLength - 1].id : '';
    const displayedLastId: string = this.rows.length > 0
      ? this.rows[this.rows.length - 1].nodeId : '';
    const wasAtTail: boolean = previousLastId.length > 0 && displayedLastId === previousLastId;
    const previousTailStart: number = Math.max(0, previousLength - TIMELINE_PAGE_SIZE);
    const wasTailOnly: boolean = wasAtTail && state.oldestLoadedIndex >= previousTailStart;
    let start: number = wasTailOnly
      ? Math.max(0, next.messageNodes.length - TIMELINE_PAGE_SIZE)
      : state.oldestLoadedIndex;
    if (!wasAtTail && this.rows.length > 0) {
      const firstId: string = this.rows[0].nodeId;
      const firstIndex: number = next.messageNodes.findIndex(
        (node: MessageNode): boolean => node.id === firstId);
      if (firstIndex >= 0) start = firstIndex;
    }
    start = Math.max(0, Math.min(start, next.messageNodes.length));
    if (next.messageNodes.length > 0 && start >= next.messageNodes.length) {
      const keepCount: number = Math.max(1, Math.min(this.rows.length, TIMELINE_PAGE_SIZE));
      start = Math.max(0, next.messageNodes.length - keepCount);
    }
    const available: number = next.messageNodes.length - start;
    const count: number = wasAtTail
      ? available
      : Math.min(this.rows.length, available);
    this.setRows(buildRowsFromNodes(next.messageNodes.slice(start, start + count), this.rows));
    this.timelineLoadState = applyTailWindow(
      state, start, this.rows.length, next.messageNodes.length);
  }

  loadOlderTimeline(): void {
    const st: ConversationTimelineLoadState = this.timelineLoadState;
    if (!canLoadOlder(st) || this.prefetching) return;
    const state: ConversationTimelineLoadState = beginLoadOlder(st);
    this.timelineLoadState = state;
    if (this.conversation === null) return;
    const convId: string = this.conversation.id;
    const offset: number = nextOlderPageOffset(state, TIMELINE_PAGE_SIZE);
    this.prefetching = true;
    (this.repository as ConversationRepository).getConversationNodePage(convId, offset, TIMELINE_PAGE_SIZE)
      .then((page): void => {
        if (!this.pageAlive) return;
        if (this.conversation?.id !== convId) return;
        this.pendingOlderPage = {
          conversationId: convId, nodes: page.nodes, oldestLoadedIndex: page.oldestLoadedIndex,
        };
        this.applyPendingOlderPage();
      })
      .catch((e: Error): void => {
        hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'load older page failed: %{public}s', e.message);
        const s: ConversationTimelineLoadState = this.timelineLoadState;
        this.timelineLoadState = {
          initialized: s.initialized,
          loadingOlder: false,
          fullyLoaded: s.fullyLoaded,
          oldestLoadedIndex: s.oldestLoadedIndex,
          loadedNodeCount: s.loadedNodeCount,
        };
      })
      .then((): void => {
        if (this.pageAlive && this.pendingOlderPage === null && this.prependLayoutCallback === null) {
          this.prefetching = false;
        }
      });
  }

  private visiblePrependAnchor(): TimelinePrependAnchor | null {
    if (!this.pageVisible || !this.listReady) return null;
    const loaderCount: number = this.loaderItemCount();
    const start: number = Math.max(0, this.firstVisibleListIndex - loaderCount);
    const end: number = Math.min(this.dataSource.totalCount() - 1, this.lastVisibleListIndex - loaderCount);
    for (let index: number = start; index <= end; index++) {
      const item = this.dataSource.getData(index);
      if (item.kind !== 'message' || item.row.nodeId.length === 0) continue;
      try {
        const rect: RectResult = this.scroller.getItemRect(index + loaderCount);
        if (rect.height > 0) return { nodeId: item.row.nodeId, y: rect.y };
      } catch (_error) {
        // onScrollIndex can lag one layout; only an actually measured visible row is an anchor.
      }
    }
    return null;
  }

  private applyPendingOlderPage(): void {
    const page: PendingOlderTimelinePage | null = this.pendingOlderPage;
    if (page === null || (this.pageVisible && (this.userTouchActive || this.userScrollInProgress))) return;
    this.pendingOlderPage = null;
    if (this.conversation?.id !== page.conversationId) { this.prefetching = false; return; }
    const anchor: TimelinePrependAnchor | null = this.visiblePrependAnchor();
    const older: MsgRow[] = buildRowsFromNodes(page.nodes, this.rows);
    const merged: MsgRow[] = mergeOlderNodes(this.rows, older);
    const added: number = merged.length - this.rows.length;
    if (added > 0 && anchor !== null) {
      const restore = (): void => {
        this.removePrependLayoutCallback();
        if (!this.pageAlive || !this.pageVisible || this.conversation?.id !== page.conversationId) {
          this.prefetching = false;
          return;
        }
        const dataIndex: number = this.dataSource.dataIndexForNode(anchor.nodeId);
        if (dataIndex < 0) { this.prefetching = false; return; }
        this.beginProgrammaticScroll();
        // Keep ReachStart blocked until the seek itself has completed layout.
        this.prependLayoutCallback = (): void => { this.cancelPrependRestore(); };
        this.getUIContext().getUIObserver().on('didLayout', this.prependLayoutCallback);
        this.scroller.scrollToIndex(dataIndex + this.loaderItemCount(), false, ScrollAlign.START,
          { extraOffset: LengthMetrics.vp(-anchor.y) });
      };
      // Subscribe before rows and the conditional loader change. The new indices
      // are usable only after the native List has consumed that data update.
      this.prependLayoutCallback = restore;
      this.getUIContext().getUIObserver().on('didLayout', restore);
    }
    this.setRows(merged);
    this.timelineLoadState = applyOlderPage(this.timelineLoadState, page.oldestLoadedIndex, added);
    this.syncDataSource();
    if (this.prependLayoutCallback === null) this.prefetching = false;
  }

  private removePrependLayoutCallback(): void {
    const callback: (() => void) | null = this.prependLayoutCallback;
    if (callback !== null) this.uiContext?.getUIObserver().off('didLayout', callback);
    this.prependLayoutCallback = null;
  }

  cancelPrependRestore(): void {
    if (this.prependLayoutCallback === null) return;
    this.removePrependLayoutCallback();
    this.prefetching = false;
  }

  beginUserTimelineScroll(): void {
    this.userScrollInProgress = true;
    this.cancelPrependRestore();
  }

  finishUserTimelineScroll(): void {
    this.userScrollInProgress = false;
    this.applyPendingOlderPage();
  }

  finishTimelineTouch(): void { this.applyPendingOlderPage(); }

  positionToTargetNode(targetNodeId: string, targetNodeIndex: number): void {
    const full: Conversation | null = this.conversation;
    if (full !== null && full.messageNodes[targetNodeIndex]?.id === targetNodeId
      && targetNodeIndex < this.timelineLoadState.oldestLoadedIndex) {
      const start: number = Math.max(0, targetNodeIndex - targetNodeIndex % TIMELINE_PAGE_SIZE);
      const nodes: MessageNode[] = full.messageNodes.slice(start);
      this.setRows(buildRowsFromNodes(nodes, this.rows));
      this.timelineLoadState = applyTailWindow(this.timelineLoadState, start, nodes.length, full.messageNodes.length);
    }
    const st: ConversationTimelineLoadState = this.timelineLoadState;
    if (st.fullyLoaded || coversIndex(st, targetNodeIndex)) {
      const row: number = this.rows.findIndex((r: MsgRow): boolean => r.nodeId === targetNodeId);
      this.applyInitialScroll(row >= 0 ? row : -1);
      return;
    }
    this.prefetching = true;
    this.prefetchHistoryUntilCovered(targetNodeId, targetNodeIndex, 0);
  }

  prefetchHistoryUntilCovered(
    targetNodeId: string, targetNodeIndex: number, depth: number,
  ): void {
    if (!this.pageAlive) return;
    const st: ConversationTimelineLoadState = this.timelineLoadState;
    if (st.fullyLoaded || coversIndex(st, targetNodeIndex)) {
      this.prefetching = false;
      const row: number = this.rows.findIndex((r: MsgRow): boolean => r.nodeId === targetNodeId);
      if (row < 0) {
        hilog.warn(HILOG_DOMAIN, HILOG_TAG,
          'prefetch covered target index but node was not found: %{public}s', targetNodeId);
      }
      this.applyInitialScroll(row >= 0 ? row : -1);
      return;
    }
    if (depth >= TIMELINE_MAX_PREFETCH_PAGES ||
      st.loadedNodeCount >= TIMELINE_MAX_PREFETCH_NODES) {
      hilog.warn(HILOG_DOMAIN, HILOG_TAG,
        'prefetch history stopped before target: %{public}s', targetNodeId);
      this.prefetching = false;
      this.applyInitialScroll(-1);
      return;
    }
    if (this.conversation === null) {
      this.prefetching = false;
      return;
    }
    const convId: string = this.conversation.id;
    const pageSize: number = Math.min(
      TIMELINE_PAGE_SIZE, TIMELINE_MAX_PREFETCH_NODES - st.loadedNodeCount);
    const offset: number = nextOlderPageOffset(st, pageSize);
    (this.repository as ConversationRepository).getConversationNodePage(convId, offset, pageSize)
      .then((page): void => {
        if (!this.pageAlive) return;
        const older: MsgRow[] = buildRowsFromNodes(page.nodes, this.rows);
        const merged: MsgRow[] = mergeOlderNodes(this.rows, older);
        const added: number = merged.length - this.rows.length;
        this.setRows(merged);
        this.timelineLoadState = applyOlderPage(
          this.timelineLoadState, page.oldestLoadedIndex, added);
        if (older.length === 0 || added === 0) {
          hilog.warn(HILOG_DOMAIN, HILOG_TAG,
            'prefetch history made no progress for target: %{public}s', targetNodeId);
          this.prefetching = false;
          this.applyInitialScroll(-1);
          return;
        }
        this.prefetchHistoryUntilCovered(targetNodeId, targetNodeIndex, depth + 1);
      })
      .catch((e: Error): void => {
        hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'prefetch history failed: %{public}s', e.message);
        this.prefetching = false;
        this.applyInitialScroll(-1);
      });
  }

  activeListItemCount(): number {
    return this.dataSource.totalCount() + this.loaderItemCount() +
      (this.suggestions.length > 0 ? 1 : 0);
  }

  isGenerationActive(): boolean {
    return this.sending || this.streamingTail.length > 0 || this.regenTail !== null;
  }

  isNearBottom(): boolean {
    if (!this.listReady) return this.atBottomApprox;
    if (this.scroller.isAtEnd()) return true;
    const itemCount: number = this.activeListItemCount();
    if (itemCount === 0) return true;
    const lastIndex: number = itemCount - 1;
    if (this.lastVisibleListIndex < lastIndex || this.listViewportHeight <= 0) return false;
    try {
      const rect: RectResult = this.scroller.getItemRect(lastIndex);
      const contentBottom: number = rect.y + rect.height;
      // getItemRect() is relative to the List viewport. The remaining scroll
      // distance is the part of the last item below the viewport bottom.
      const remaining: number = Math.max(0, contentBottom - this.listViewportHeight);
      return remaining <= FOLLOW_BOTTOM_TOLERANCE_VP;
    } catch (_e) {
      return false;
    }
  }

  hideScrollNavigation(): void {
    if (this.scrollNavigationTimer.handle !== -1) clearTimeout(this.scrollNavigationTimer.handle);
    this.scrollNavigationTimer.handle = -1;
    this.scrollNavigationVisible = false;
  }

  showScrollNavigation(): void {
    if (this.scrollNavigationTimer.handle !== -1) clearTimeout(this.scrollNavigationTimer.handle);
    this.scrollNavigationVisible = true;
    this.scrollNavigationTimer.handle = setTimeout((): void => {
      this.scrollNavigationTimer.handle = -1;
      this.scrollNavigationVisible = false;
    }, 1200);
  }

  floatingControlsBottom(): number {
    return this.suggestions.length > 0 ? 60 : 12;
  }

  beginProgrammaticScroll(): void {
    const token: number = ++this.programmaticScrollToken;
    this.programmaticScrollPending = true;
    // Idle/onScrollStop closes this attribution once the multi-frame scroll settles;
    // the timer only covers SDKs that omit those callbacks.
    setTimeout((): void => {
      if (this.programmaticScrollToken === token) this.programmaticScrollPending = false;
    }, 250);
  }

  measuredBottomTarget(): number | null {
    const current: number = this.scroller.currentOffset().yOffset;
    if (this.scroller.isAtEnd()) return current;
    const lastIndex: number = this.activeListItemCount() - 1;
    if (lastIndex < 0) return current;
    // Text growth can push the existing 52vp suggestion spacer out of view.
    // Its preceding item still measures the same bottom, including that fixed spacer.
    const hiddenSuggestionInset: number = this.suggestions.length > 0 && lastIndex > 0 &&
      this.lastVisibleListIndex === lastIndex - 1 ? 52 : 0;
    const measuredIndex: number = hiddenSuggestionInset > 0 ? lastIndex - 1 : lastIndex;
    if (this.lastVisibleListIndex < measuredIndex || this.listViewportHeight <= 0) return null;
    const rect: RectResult = this.scroller.getItemRect(measuredIndex);
    // The composer owns the navigation inset outside this List. Only the actual
    // suggestion ListItem belongs in this target; visible spacers are already measured.
    return current + Math.max(0, rect.y + rect.height + hiddenSuggestionInset - this.listViewportHeight);
  }

  requestNativeFollow(): void {
    if (this.nativeFollow === null) {
      this.nativeFollow = new NativeTimelineFollow(this.getUIContext(), this.scroller,
        (): boolean => this.pageAlive && this.pageVisible && this.listReady &&
          this.enableAutoScroll && this.activeArtifactInteraction.length === 0 &&
          !this.userTouchActive && !this.initialJumpSuppressedUntilUserScroll &&
          shouldScrollOnStreamUpdate(this.followMode, this.atBottomApprox),
        (): number | null => this.measuredBottomTarget(),
        (): void => { this.beginProgrammaticScroll(); });
    }
    this.nativeFollow.request();
  }

  followStreamingUpdate(): void {
    this.handleStreamUpdate(this.atBottomApprox);
    if (!this.enableAutoScroll ||
      !shouldScrollOnStreamUpdate(this.followMode, this.atBottomApprox)) return;
    this.atBottomApprox = true;
    this.jumpButtonVisible = false;
    this.requestNativeFollow();
  }

  handleUserScroll(generating: boolean): void {
    this.nativeFollow?.cancel();
    const atEnd: boolean = this.isNearBottom();
    this.initialJumpSuppressedUntilUserScroll = false;
    this.followMode = transitionOnUserScroll(this.followMode, atEnd, generating);
    this.atBottomApprox = atEnd;
    this.jumpButtonVisible = !atEnd && this.followMode !== 'following';
  }

  handleStreamUpdate(atEnd: boolean): void {
    if (!this.enableAutoScroll) return;
    this.followMode = transitionOnStreamUpdate(this.followMode, atEnd);
    this.jumpButtonVisible = this.followMode === 'paused' &&
      !this.initialJumpSuppressedUntilUserScroll;
  }

  handleGenerateEnd(atEnd: boolean): void {
    const shouldSettleAfterTouch: boolean = this.userTouchActive && atEnd &&
      this.followMode !== 'paused';
    this.followMode = transitionOnGenerateEnd(this.followMode);
    this.atBottomApprox = atEnd;
    this.generationEndScrollPending = shouldSettleAfterTouch;
    this.jumpButtonVisible = !atEnd &&
      !this.initialJumpSuppressedUntilUserScroll;
  }

  reconcileAtEnd(): void {
    const atEnd: boolean = this.isNearBottom();
    this.atBottomApprox = atEnd;
    if (this.initialJumpSuppressedUntilUserScroll) {
      this.jumpButtonVisible = false;
    } else {
      this.jumpButtonVisible = !atEnd;
    }
    if (atEnd) {
      this.followMode = transitionOnUserScroll(
        this.followMode, true, this.isGenerationActive());
      if (this.generationEndScrollPending && !this.userTouchActive) {
        this.generationEndScrollPending = false;
        this.scrollToBottom();
      }
    } else {
      this.generationEndScrollPending = false;
    }
  }

  scrollToBottom(initial: boolean = false): void {
    if (!this.listReady) return;
    if (!initial && (!this.enableAutoScroll ||
      !shouldScrollOnAppend(this.followMode, this.atBottomApprox))) return;
    this.atBottomApprox = true;
    this.jumpButtonVisible = false;
    if (initial) {
      this.nativeFollow?.cancel();
      this.beginProgrammaticScroll();
      this.scroller.scrollEdge(Edge.Bottom);
    } else {
      this.requestNativeFollow();
    }
  }

  jumpToBottom(): void {
    this.cancelPrependRestore();
    this.nativeFollow?.cancel();
    this.followMode = transitionOnJumpToBottom(this.isGenerationActive());
    this.atBottomApprox = true;
    this.jumpButtonVisible = false;
    this.beginProgrammaticScroll();
    this.scroller.scrollEdge(Edge.Bottom);
  }

  rowNodeIds(): string[] {
    const ids: string[] = [];
    for (let i = 0; i < this.rows.length; i++) {
      ids.push(this.rows[i].nodeId);
    }
    return ids;
  }

  applyInitialScroll(targetRow: number): void {
    this.cancelPrependRestore();
    this.initialScrollTarget = targetRow;
    this.initialScrollArmed = true;
    const token: number = ++this.initialScrollScheduleToken;
    setTimeout((): void => {
      if (this.initialScrollScheduleToken !== token || !this.initialScrollArmed) return;
      if (this.listReady) {
        this.performInitialScroll();
      }
    }, 0);
  }

  performInitialScroll(): void {
    this.initialScrollArmed = false;
    if (this.initialScrollTarget < 0) {
      this.scrollToBottom(true);
      return;
    }
    const target: number = this.initialScrollTarget;
    this.initialScrollTarget = -1;
    this.atBottomApprox = false;
    this.jumpButtonVisible = false;
    this.initialJumpSuppressedUntilUserScroll = true;
    this.nativeFollow?.cancel();
    this.beginProgrammaticScroll();
    try {
      this.syncDataSource();
      // Loader and compact markers share one mapping with prepend anchors.
      this.scroller.scrollToIndex(this.listIndexForRow(target),
        false, ScrollAlign.START);
    } catch (_e) {
      this.initialJumpSuppressedUntilUserScroll = false;
      this.atBottomApprox = true;
      this.beginProgrammaticScroll();
      this.scroller.scrollEdge(Edge.Bottom);
    }
  }

  enableKeyboardAvoidance(): void {
    if (this.keyboardAvoidanceActive) return;
    this.previousKeyboardAvoidMode = this.getUIContext().getKeyboardAvoidMode();
    // 让 layoutWeight(1) 的消息区压缩，避免默认 OFFSET 把顶栏抬出屏幕。
    this.getUIContext().setKeyboardAvoidMode(KeyboardAvoidMode.RESIZE);
    this.keyboardAvoidanceActive = true;
  }

  restoreKeyboardAvoidance(): void {
    if (!this.keyboardAvoidanceActive) return;
    this.getUIContext().setKeyboardAvoidMode(this.previousKeyboardAvoidMode);
    this.keyboardAvoidanceActive = false;
  }

  attachKeyboardListener(): void {
    if (this.imeListenerAttached) return;
    const ctx: Context | undefined = this.getUIContext().getHostContext();
    if (ctx === undefined) return;
    window.getLastWindow(ctx).then((win: window.Window): void => {
      if (!this.pageAlive || !this.pageVisible) return;
      this.imeWindow = win;
      win.on('keyboardHeightChange', (h: number): void => {
        this.handleKeyboardHeightChange(h);
      });
      this.imeListenerAttached = true;
    }).catch((e: Error): void => {
      hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'keyboard listener attach failed: %{public}s', e.message);
    });
  }

  detachKeyboardListener(): void {
    if (!this.imeListenerAttached || this.imeWindow === null) return;
    try {
      this.imeWindow.off('keyboardHeightChange');
    } catch (e) {
      hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'keyboard listener detach failed: %{public}s', String(e));
    }
    this.imeWindow = null;
    this.imeListenerAttached = false;
  }

  handleKeyboardHeightChange(heightPx: number): void {
    if (!this.pageAlive || !this.pageVisible) return;
    const currentVp: number = heightPx > 0 ? this.getUIContext().px2vp(heightPx) : 0;
    const deltaVp: number = currentVp - this.imeKeyboardHeightVp;
    if (currentVp <= 0) {
      this.imeKeyboardHeightVp = 0;
      return;
    }
    // 对齐 ChatListNormalSection.kt:636-641 shouldScroll:自动滚动开 +
    //   跟随中(或已在底部附近)+ 未暂停
    if (deltaVp > 0 && this.shouldFollowIme()) {
      this.nativeFollow?.cancel();
      const token: number = ++this.imeProgrammaticScrollToken;
      this.beginProgrammaticScroll();
      try {
        this.scroller.scrollBy(0, deltaVp);
      } catch (e) {
        hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'ime scrollBy failed: %{public}s', String(e));
      }
      setTimeout((): void => {
        if (this.imeProgrammaticScrollToken === token) {
          this.reconcileAtEnd();
        }
      }, 120);
    }
    this.imeKeyboardHeightVp = currentVp;
  }

  shouldFollowIme(): boolean {
    if (!this.listReady || !this.enableAutoScroll) return false;
    // PausedForUser 时不接管滚动(Android:followMode != PausedForUser)
    if (this.followMode === 'paused') return false;
    return this.followMode === 'following' || this.isNearBottom();
  }

  dismissKeyboardAfterSend(): void {
    setTimeout((): void => {
      if (!this.pageAlive) return;
      try {
        inputMethod.getController().stopInputSession().catch((e: Error): void => {
          hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'stopInputSession failed: %{public}s', e.message);
        });
      } catch (e) {
        hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'stopInputSession throw: %{public}s', String(e));
      }
    }, 96);
  }

  smartUiAssetAreaKey(messageId: string, partIndex: number, ref: string, sourceStart: number): string {
    // Declared asset IDs are unique within a message, including across text groups.
    return JSON.stringify([messageId, ref]);
  }

  onSmartUiAssetArea(messageId: string, partIndex: number, ref: string,
    sourceStart: number, area: Area): void {
    this.smartUiAssetAreas.set(this.smartUiAssetAreaKey(messageId, partIndex, ref, sourceStart),
      Number(area.globalPosition.y));
  }

  onSmartUiAssetView(nodeId: string, messageId: string, partIndex: number,
    ref: string, sourceStart: number): void {
    const y: number | undefined = messageId.length > 0
      ? this.smartUiAssetAreas.get(this.smartUiAssetAreaKey(messageId, partIndex, ref, sourceStart)) : undefined;
    if (y !== undefined && Number.isFinite(y)) {
      this.followMode = 'paused';
      this.nativeFollow?.cancel();
      this.beginProgrammaticScroll();
      const targetY: number = this.statusBarHeight + this.chatHeaderHeight + 24;
      try {
        this.scroller.scrollBy(0, y - targetY);
      } catch (_e) {
        this.applyInitialScroll(this.rows.findIndex((item: MsgRow): boolean => item.nodeId === nodeId));
      }
      return;
    }
    const row: number = this.rows.findIndex((item: MsgRow): boolean => item.nodeId === nodeId);
    if (row >= 0) {
      this.followMode = 'paused';
      this.applyInitialScroll(row);
    }
    hilog.info(HILOG_DOMAIN, HILOG_TAG,
      'smart ui asset view: %{public}s %{public}d %{public}s %{public}d', nodeId, partIndex, ref, sourceStart);
  }
}
