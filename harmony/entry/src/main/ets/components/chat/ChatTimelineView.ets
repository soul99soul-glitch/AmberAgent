import type { UIMessage, UIMessagePartDocument, UIMessagePartTool, ChatTimelineRow, ToolApprovalPartLocator, ConversationCompact } from '@amber/chat-domain';
import { ChatTimelineSession } from '../../platform_impl/chat/ChatTimelineSession.ets';
import { ChatConversationPresentation } from '../../platform_impl/chat/ChatConversationPresentation.ets';
import { ChatSmartUiSession } from '../../platform_impl/chat/ChatSmartUiSession.ets';
import { ChatTimelineMessageItem } from './ChatTimelineMessageItem.ets';
import { ChatTimelineItem } from '../../platform_impl/chat/ChatTimelineDataSource.ets';
import { InteractiveMessageTimeline } from '../InteractiveMessageTimeline.ets';
import { Icon } from '../ui/Icon.ets';
import { ACCENT, INK, INK2, INK3, SURFACE2, LINE, MOTION_OVERLAY } from '../../design/tokens.ets';
type MsgRow = ChatTimelineRow;
const accentAlpha = (hex: string, alpha: number): string => {
  const value: string = hex.replace('#', '');
  if (value.length !== 6) return hex;
  const r: number = parseInt(value.substring(0, 2), 16);
  const g: number = parseInt(value.substring(2, 4), 16);
  const b: number = parseInt(value.substring(4, 6), 16);
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
};


// Actions can outlive a native row while a sheet is open. Resolve only when
// invoked; streaming frames update the observed item directly.
export function resolveChatTimelineRow(rows: MsgRow[], streamingTail: UIMessage[], seed: MsgRow): MsgRow {
  const current: MsgRow | undefined = rows.find((row: MsgRow): boolean =>
    seed.nodeId.length > 0 ? row.nodeId === seed.nodeId : row.message.id === seed.message.id);
  if (current !== undefined) return current;
  const message: UIMessage | undefined = streamingTail.find((item: UIMessage): boolean =>
    item.id === seed.message.id);
  if (message === undefined) return seed;
  return {
    nodeId: '', message: message, branchCount: 1, selectIndex: 0,
    isAssistant: message.role === 'assistant', isLastAssistant: true,
    displayRevision: 0, displaySignature: '',
  };
}

@Component
export struct ChatTimelineView {
  @ObjectLink timeline: ChatTimelineSession;
  @ObjectLink presentation: ChatConversationPresentation;
  @ObjectLink smartUi: ChatSmartUiSession;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @Prop activeArtifactInteraction: string = '';
  @Prop favoriteNodeIds: string[] = [];
  @Prop @Watch('syncTimelineItems') contextCompacts: ConversationCompact[] = [];
  onMessageCommand: (command: string, row: MsgRow, index: number) => void = (): void => {};
  onApprove: (id: string, locator?: ToolApprovalPartLocator) => void = (): void => {};
  onDeny: (id: string, locator?: ToolApprovalPartLocator) => void = (): void => {};
  onAnswer: (id: string, answer: string, locator?: ToolApprovalPartLocator) => void = (): void => {};
  onToolPreview: (part: UIMessagePartTool, locator?: ToolApprovalPartLocator) => void = (): void => {};
  subjectHash: (part: UIMessagePartTool, locator?: ToolApprovalPartLocator) => string = (): string => '';
  onRunDetails: (kind: string, id: string, tools: UIMessagePartTool[]) => void = (): void => {};
  onDocument: (part: UIMessagePartDocument) => void = (): void => {};
  onDraft: (text: string) => void = (): void => {};
  onModifyImage: (url: string) => void = (): void => {};
  onSmartUiDraft: (nodeId: string, key: string) => void = (): void => {};
  onShowCompact: (messageId: string) => void = (): void => {};
  onSendSuggestion: (text: string) => void = (): void => {};
  onNavigate: (kind: string, id: string) => void = (): void => {};

  private previewScrollChanged(scrolling: boolean): void {
    if (AppStorage.get<boolean>('threePreviewScrolling') !== scrolling) {
      AppStorage.setOrCreate<boolean>('threePreviewScrolling', scrolling);
    }
  }

  aboutToDisappear(): void { this.previewScrollChanged(false); }
  aboutToAppear(): void { this.timeline.syncDataSource(); }
  private syncTimelineItems(): void { this.timeline.syncDataSource(); }

  @Builder
  chatTimelineRows(): void {
    LazyForEach(this.timeline.dataSource, (item: ChatTimelineItem): void => {
      ListItem() {
        ChatTimelineMessageItem({
          item: item, timeline: this.timeline, presentation: this.presentation, smartUi: this.smartUi,
          favoriteNodeIds: this.favoriteNodeIds,
          onMessageCommand: this.onMessageCommand, onApprove: this.onApprove, onDeny: this.onDeny,
          onAnswer: this.onAnswer, onToolPreview: this.onToolPreview, subjectHash: this.subjectHash,
          onRunDetails: this.onRunDetails, onDocument: this.onDocument, onDraft: this.onDraft,
          onModifyImage: this.onModifyImage, onSmartUiDraft: this.onSmartUiDraft,
          onShowCompact: this.onShowCompact, onNavigate: this.onNavigate,
        })
      }
    }, (item: ChatTimelineItem): string => item.key)
  }

  @Builder
  chatTimelineActiveTail(): void {
    // 活动尾已在 chatTimelineRows 内；共用壳仍保留此 slot 给其他宿主。
  }

  @Builder
  chatTimelineBottomInset(): void {
    if (this.presentation.suggestions.length > 0) {
      ListItem() { Column().height(52) }
    }
  }

  @Builder
  floatingSuggestions(): void {
    Scroll() {
      Row() {
        ForEach(this.presentation.suggestions, (s: string): void => {
          Text(s)
            .fontSize(13).fontColor(this.accentColorStore)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .constraintSize({ maxWidth: '58%' })
            .padding({ left: 12, right: 12, top: 8, bottom: 8 })
            .backgroundColor(SURFACE2)
            .borderRadius(999)
            .border({ width: 1, color: accentAlpha(this.accentColorStore, 0.35) })
            .margin({ right: 8 })
            // Android ChatPage.kt:812-830:点击 = 填入输入框;
            //   长按 = 直接发送(D-042);生成中 → FOLLOWUP 入队(D-048);
            //   D-080:模型空值门(:823/:864/:880,建议文本非斜杠恒要模型)
            .onClick((): void => { this.onDraft(s); })
            .gesture(
              LongPressGesture()
                .onAction((): void => {
                  this.onSendSuggestion(s);
                })
            )
        }, (s: string, i: number): string => `sg-${i}-${s}`)
      }
      .padding({ left: 20, right: 20 })
    }
    .align(Alignment.TopStart)
    .scrollable(ScrollDirection.Horizontal)
    .scrollBar(BarState.Off)
    .width('100%')
    .height(40)
    .position({ left: 0, bottom: 8 })
  }

  build(): void {
    Stack({ alignContent: Alignment.Top }) {
      InteractiveMessageTimeline({
        scroller: this.timeline.scroller,
        scrollInteractionEnabled: this.activeArtifactInteraction.length === 0,
        horizontalGutter: 20,
        topPadding: 0,
        bottomPadding: 0,
        cachedItems: 5,
        preserveVisibleContent: false,
        showOlderLoader: !this.timeline.timelineLoadState.fullyLoaded,
        showDivider: false,
        showScrollBar: false,
        rows: (): void => { this.chatTimelineRows(); },
        activeTail: (): void => { this.chatTimelineActiveTail(); },
        hostTail: (): void => { this.chatTimelineBottomInset(); },
        onTimelineAppear: (): void => {
          this.previewScrollChanged(false);
          this.timeline.syncDataSource();
          this.timeline.listReady = true;
          if (this.timeline.initialScrollArmed) this.timeline.performInitialScroll();
        },
        onViewportHeightChange: (height: number): void => { this.timeline.listViewportHeight = height; },
        onReachStart: (): void => { this.timeline.loadOlderTimeline(); },
        onVisibleRangeChange: (start: number, end: number): void => {
          this.timeline.firstVisibleListIndex = start;
          this.timeline.lastVisibleListIndex = end;
        },
        onTimelineTouch: (event: TouchEvent): void => {
          if (event.type === TouchType.Down) {
            this.timeline.cancelPrependRestore();
            this.timeline.userTouchActive = true;
            this.timeline.nativeFollow?.cancel();
          }
          if (event.type === TouchType.Up || event.type === TouchType.Cancel) {
            this.timeline.userTouchActive = false;
            this.timeline.finishTimelineTouch();
            if (this.timeline.generationEndScrollPending) this.timeline.reconcileAtEnd();
            if (this.timeline.followMode !== 'paused') this.timeline.requestNativeFollow();
          }
        },
        onTimelineWillScroll: (offset: number, _state: ScrollState, source: ScrollSource): void => {
          if (offset !== 0) this.previewScrollChanged(true);
          if (offset !== 0 && (source === ScrollSource.DRAG || source === ScrollSource.FLING)) {
            this.timeline.beginUserTimelineScroll();
            this.timeline.nativeFollow?.cancel();
            this.timeline.programmaticScrollPending = false;
            this.timeline.showScrollNavigation();
          }
        },
        onTimelineScroll: (_scrollOffset: number, scrollState: ScrollState): void => {
          if (this.timeline.programmaticScrollPending && scrollState === ScrollState.Idle) {
            this.timeline.programmaticScrollPending = false;
          } else if ((scrollState === ScrollState.Scroll || scrollState === ScrollState.Fling) &&
            (this.timeline.userTouchActive ||
              (!this.timeline.programmaticScrollPending && !this.timeline.nativeFollow?.isRunning()))) {
            this.timeline.handleUserScroll(this.timeline.isGenerationActive());
          }
        },
        onTimelineScrollStop: (): void => {
          this.previewScrollChanged(false);
          this.timeline.programmaticScrollPending = false;
          this.timeline.finishUserTimelineScroll();
          this.timeline.reconcileAtEnd();
        },
      })

      if (this.timeline.scrollNavigationVisible && this.timeline.jumpButtonVisible && this.timeline.showMessageJumper) {
        Stack() {
          Icon({ name: 'arrow_down_to_line', iconSize: 20, color: INK })
        }
        .width(40).height(40)
        .position(this.presentation.displaySettings.messageJumperOnLeft ? { left: 20, bottom: this.timeline.floatingControlsBottom() }
          : { right: 20, bottom: this.timeline.floatingControlsBottom() })
        .backgroundColor(SURFACE2)
        .borderRadius(20)
        .border({ width: 1, color: LINE })
        .shadow({ radius: 8, color: '#33000000', offsetY: 2 })
        .alignContent(Alignment.Center)
        .onClick((): void => { this.timeline.jumpToBottom(); })
        .accessibilityText('回到消息底部')
        .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: 8 }))
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      }

      // 回到顶部：只在用户滚动时短暂显示，避开建议与跳底按钮。
      if (this.timeline.scrollNavigationVisible && this.timeline.showMessageJumper && this.timeline.firstVisibleListIndex > 1) {
        Stack() {
          Icon({ name: 'arrow_up', iconSize: 20, color: INK })
        }
        .width(40).height(40)
        .position(this.presentation.displaySettings.messageJumperOnLeft
          ? { left: 20, bottom: this.timeline.floatingControlsBottom() + (this.timeline.jumpButtonVisible ? 48 : 0) }
          : { right: 20, bottom: this.timeline.floatingControlsBottom() + (this.timeline.jumpButtonVisible ? 48 : 0) })
        .backgroundColor(SURFACE2)
        .borderRadius(20)
        .border({ width: 1, color: LINE })
        .shadow({ radius: 8, color: '#33000000', offsetY: 2 })
        .alignContent(Alignment.Center)
        .onClick((): void => {
          this.timeline.followMode = 'paused';
          this.timeline.atBottomApprox = false;
          this.timeline.cancelPrependRestore();
          this.timeline.nativeFollow?.cancel();
          this.timeline.beginProgrammaticScroll();
          this.timeline.scroller.scrollEdge(Edge.Top);
        })
        .accessibilityText('回到消息顶部')
        .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: 8 }))
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      }

      if (this.presentation.suggestions.length > 0) {
        this.floatingSuggestions()
      }

    }.width('100%').height('100%')
  }
}
