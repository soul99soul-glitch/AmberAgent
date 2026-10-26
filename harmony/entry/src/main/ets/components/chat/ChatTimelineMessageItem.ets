import type { UIMessagePartDocument, UIMessagePartTool, ChatTimelineRow, SmartUiStateValue, SmartUiScalar, SmartUiDocument, SmartUiDraftSource, ToolApprovalPartLocator } from '@amber/chat-domain';
import { ChatTimelineSession } from '../../platform_impl/chat/ChatTimelineSession.ets';
import { ChatTimelineItem } from '../../platform_impl/chat/ChatTimelineDataSource.ets';
import { ChatConversationPresentation } from '../../platform_impl/chat/ChatConversationPresentation.ets';
import { ChatSmartUiSession } from '../../platform_impl/chat/ChatSmartUiSession.ets';
import { AgentMessageRow } from '../AgentMessageRow.ets';
import type { AgentMessageRowHostCapabilities } from '../AgentMessageRow.ets';
import { FONT_SANS } from '../../design/fonts.ets';
import { ACCENT, INK3, META } from '../../design/tokens.ets';
type MsgRow = ChatTimelineRow;

const CHAT_MESSAGE_ROW_CAPABILITIES: AgentMessageRowHostCapabilities = {
  copy: false,
  fork: false,
  branchSwitch: true,
  edit: true,
  regenerate: false,
  favorite: false,
  openActions: true,
  approveTool: true,
  denyTool: true,
  answerTool: true,
  openToolPreview: true,
  openSubAgentRun: true,
  openCouncilRun: true,
  runMiniApp: true,
  modifyMiniApp: true,
  openMiniAppList: true,
  openDocument: true,
};

@Component
export struct ChatTimelineMessageItem {
  @ObjectLink item: ChatTimelineItem;
  @ObjectLink timeline: ChatTimelineSession;
  @ObjectLink presentation: ChatConversationPresentation;
  @ObjectLink smartUi: ChatSmartUiSession;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @Prop favoriteNodeIds: string[] = [];
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
  onNavigate: (kind: string, id: string) => void = (): void => {};

  @Builder
  private CompactMarker(): void {
    Row() {
      Text(`已压缩 ${this.item.compactCount} 条上下文`)
        .fontSize(META).fontColor(INK3).fontFamily(FONT_SANS)
        .margin({ left: 10, right: 10 })
    }
    .width('100%')
    .alignItems(VerticalAlign.Center)
    .padding({ top: 10, bottom: 6 })
    .onClick((): void => { this.onShowCompact(this.item.row.message.id); })
    .accessibilityText('查看上下文压缩摘要')
  }

  build(): void {
    if (this.item.kind === 'compact') {
      this.CompactMarker()
    } else {
      AgentMessageRow({
        animateEntrance: false,
        viewState: this.item.viewState,
        message: this.item.message,
        streaming: this.item.streaming,
        capabilities: CHAT_MESSAGE_ROW_CAPABILITIES,
        showAssistantBubble: this.presentation.displaySettings.showAssistantBubble,
        showModelName: this.presentation.displaySettings.showModelName,
        showModelIcon: this.presentation.displaySettings.showModelIcon,
        showUserAvatar: this.presentation.displaySettings.showUserAvatar,
        userAvatar: this.presentation.userAvatar,
        userNickname: this.presentation.userNickname,
        fontSizeRatio: this.presentation.displaySettings.fontSizeRatio,
        codeBlockAutoCollapse: this.presentation.displaySettings.codeBlockAutoCollapse,
        showLineNumbers: this.presentation.displaySettings.showLineNumbers,
        chatFontFamily: this.presentation.displaySettings.chatFontFamily,
        enableLatexRendering: this.presentation.displaySettings.enableLatexRendering,
        enableGenerativeWidgets: this.presentation.displaySettings.enableGenerativeWidgets,
        smartUiConversationId: this.presentation.conversation?.id ?? '',
        smartUiNodeId: this.item.row.nodeId,
        smartUiStateAccessor: this.smartUi.stateAccessor,
        smartUiStateRevision: this.smartUi.smartUiStateRevision,
        smartUiProjections: this.smartUi.currentSmartUiWeatherProjections(this.presentation.conversation),
        smartUiValuesFor: (sourceKey: string): SmartUiStateValue[] => this.smartUi.smartUiValuesFor(sourceKey),
        onOpenDocument: (part: UIMessagePartDocument): void => { this.onDocument(part); },
        hostHeader: (): void => {},
        hostFooter: (): void => {},
        autoCloseThinking: this.presentation.autoCloseThinking,
        showThinkingContent: this.presentation.displaySettings.showThinkingContent,
        branchCount: this.item.row.branchCount,
        selectIndex: this.item.row.selectIndex,
        isLastAssistant: this.item.row.isLastAssistant,
        onSwitch: (idx: number): void => this.onMessageCommand('switch', this.item.row, idx),
        onRegenerate: (): void => this.onMessageCommand('regenerate', this.item.row, 0),
        onEdit: (): void => this.onMessageCommand('edit', this.item.row, 0),
        onOpenActions: (): void => this.onMessageCommand('actions', this.item.row, 0),
        onFork: (): void => this.onMessageCommand('fork', this.item.row, 0),
        onApproveTool: (toolCallId: string, locator?: ToolApprovalPartLocator): void =>
          this.onApprove(toolCallId, locator),
        onDenyTool: (toolCallId: string, locator?: ToolApprovalPartLocator): void =>
          this.onDeny(toolCallId, locator),
        onAnswerTool: (toolCallId: string, answer: string, locator?: ToolApprovalPartLocator): void =>
          this.onAnswer(toolCallId, answer, locator),
        onOpenCouncilRun: (rid: string, tools: UIMessagePartTool[]): void => {
          this.onRunDetails('council', rid, tools);
        },
        onSubAgentResultToggle: (): void => {
          this.timeline.nativeFollow?.cancel();
          this.timeline.followMode = 'paused';
        },
        onOpenSubAgentRun: (rid: string, tools: UIMessagePartTool[]): void => {
          this.onRunDetails('subagent', rid, tools);
        },
        onCopy: (): void => { this.onMessageCommand('copy', this.item.row, 0); },
        isFavorite: this.favoriteNodeIds.indexOf(this.item.row.nodeId) >= 0,
        onFavorite: (_text: string): void => { this.onMessageCommand('favorite', this.item.row, 0); },
        jevSubjectHashForPart: (part: UIMessagePartTool, locator?: ToolApprovalPartLocator): string => this.subjectHash(part, locator),
        onOpenToolPreview: (part: UIMessagePartTool, locator?: ToolApprovalPartLocator): void => { this.onToolPreview(part, locator); },
        onModifyGeneratedImage: (url: string): void => { this.onModifyImage(url); },
        onWidgetAction: (prompt: string): void => { this.onDraft(prompt); },
        onSmartUiStateChange: (sourceKey: string, stateId: string, value: SmartUiScalar): void => {
          this.smartUi.onSmartUiStateChange(sourceKey, stateId, value);
        },
        onSmartUiDraft: (nodeId: string, sourceKey: string): void => { this.onSmartUiDraft(nodeId, sourceKey); },
        onSmartUiAssetView: (messageId: string, partIndex: number, ref: string, sourceStart: number): void => {
          this.timeline.onSmartUiAssetView(this.item.row.nodeId, messageId, partIndex, ref, sourceStart);
        },
        onSmartUiSourceReplaced: (sourcePrefix: string): void => {
          this.smartUi.clearSmartUiValues(sourcePrefix);
        },
        onSmartUiDocument: (sourceKey: string, document: SmartUiDocument, streaming: boolean,
          source: SmartUiDraftSource, complete: boolean): void => {
          this.smartUi.onSmartUiDocument(sourceKey, document, streaming, source, complete);
        },
        onSmartUiAssetArea: (partIndex: number, ref: string, sourceStart: number, area: Area): void => {
          this.timeline.onSmartUiAssetArea(this.item.row.message.id, partIndex, ref, sourceStart, area);
        },
        onTextGrow: (): void => { this.timeline.followStreamingUpdate(); },
        onRunMiniApp: (appId: string): void => {
          this.onNavigate('miniApp', appId);
        },
        onModifyMiniApp: (prompt: string): void => { this.onDraft(prompt); },
        onOpenMiniAppList: (): void => { this.onNavigate('miniAppList', ''); },
      })
    }
  }
}
