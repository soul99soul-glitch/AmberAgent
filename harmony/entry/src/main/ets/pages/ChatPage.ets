// ChatPage — 聊天页:消息流(text/reasoning/tool)+ 输入发送 + 流式更新
// 回路: runChatTurn(chat-domain) + OpenAIChatApi(RcpHttpClient)+ RDB 仓库(D-021)
// D-024:assistant 分支 — ◀ n/m ▶ 切换(selectMessageBranch) + 末条「重新生成」
//   (runRegenerateAt);D-041:regenerate 流式预览落地(目标节点行下渲染流式
//   快照,visual transforms 与 send 路径一致),MVP spinner 裁剪项清零
// D-038:图片输入 — PhotoViewPicker 选图 → data: URL → UIMessagePart[] 发送
//   (文本可空,图片不可空);渲染 Image(p.url)
// D-079b:物化即压缩(ImageCompress.compressImageAsDataUrl),对齐 FileEncoder 载荷
import { ACCENT, ACCENT_INK, BG, BG_FADE_END, BG_FADE_START, BG_GLASS, BODY, ERROR, HEADER, INK, INK2, INK3, INK4, LABEL, LINE, LINE2, META, MONO_SMALL, OVERLAY, SIGNAL, STATUS_AMBER, STATUS_BLUE, SURFACE, SURFACE2, TEXT_STYLES, USER_BG, USER_INK } from '../design/tokens.ets';
import type { AbortSignalLike } from '@amber/deepread-domain';
import { router, promptAction, window } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { inputMethod } from '@kit.IMEKit';
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import { pasteboard } from '@kit.BasicServicesKit';
import type {
  Conversation, MessageNode, UIMessage, UIMessagePart, UIMessagePartText, Assistant, TransformerContext,
  PendingUserMessage, PendingUserMessageMode, DequeueNextResult, UIMessagePartReasoning, ReasoningCardState,
  CompactPolicy, CompactResult, PreparedContext, DisplaySetting,
  UIMessagePartTool, ToolLoopOptions, IdleToolBlockerResult, ChatToolDefinition,
  TextGenerationParams, ChatStreamProvider, ToolApprovalVerdict, ChatTurnDeps,
  ToolRegistry, DeepReadOpenEvent, MessageTransformer, AgentTool, LocalToolOption,
  MessageChunk, OpenAIChatApi, AskUserQuestion, AskedAnswers, ConversationCompact,
  ImageGuardSettings, VisionModelHealth, VisionProbeDeps, ProviderModel, ProviderSetting,
  AgentToolActivityStore, SandboxActivityUiState, McpToolActivityPort, JsonValue,
} from '@amber/chat-domain';
import {
  currentMessages, makeConversation, newId, nowIso,
  createTemplateTransformer, thinkTagTransformer, regexOutputTransformer,
  asChatStreamProvider, seedConversationWithPresets,
  nodeCurrentMessage, nodeRole, selectMessageBranch, deleteMessage, editMessage,
  forkConversation, patchConversation,
  // Phase 2:停止生成收口 + 流式 checkpoint 纯逻辑(@amber/chat-domain)
  applyStopGeneration, streamContentLength, shouldCheckpoint, checkpointConversationTail,
  checkpointRegenerateConversation,
  runAutoTitle, runSuggestion, makeUserMessage, toText, makeTextGenerationParams,
  applyVisualTransformersStreamingTail,
  makePendingUserMessage, enqueuePendingUserMessage, dequeueNextPendingUserMessage,
  cancelPendingUserMessage, preparePendingMessageForDispatch, pendingPreviewText,
  convertSteerToFollowup, runAppendUserMessage,
  persistPendingQueue, persistPendingQueueWithEvent, loadPendingQueue, recordPendingQueueEvent,
  reasoningCardStateExpanded, onReasoningExpandedChange,
  makeCompactPolicy, prepareContext, invalidateCompacts, estimateContextWindow,
  copyValidCompactsToConversation, ContextCompactionFailedError,
  conversationWithMessagesAsNodes,
  runChatTurnWithTools, runToolLoopContinuation, runRegenerateAtWithTools,
  createTimeTool, makeUIMessage,
  createAskUserTool, createConversationContextTools, createConversationHistoryTools,
  createConversationQueueTools, createToolRegistry,
  createToolSearchTool, createRegistryIntrospectionTools, filterToolProfile,
  createRunPlanUpdateTool, createClipboardTool, createDeepReadOpenTool,
  parseDeepReadSlashCommand,
  applyToolApprovalToConversation, conversationHasPendingTools,
  conversationHasPendingOrUnexecutedTools, resolveIdleToolBlocker,
  parseAskUserQuestions, buildAskUserAnswerPayload,
  ASK_USER_TOOL_NAME,
  estimateConversationInputTokens, formatNumberInt, formatContextTokens,
  loadSearchPrefs, SearchSettings, createSearchTools,
  createTimeReminderTransformer, javaLocalDateTimeString, dayOfWeekFullName,
  createPlaceholderTransformer, createDocumentTransformer,
  createPromptInjectionTransformer, PromptInjection, Lorebook,
  RECENT_CHATS_PROMPT_LIMIT, buildRecentChatsPrompt, RecentChatItem, ConversationSummary,
  buildMemoryRecallPrompt, MemoryRecallRuntimeGate, createMemoryTools,
  runMemoryExtraction, MemoryWorkerModelResolution, memoryWorkerModelCandidates,
  buildAgentSoulPrompt,
  toChatModel, DEFAULT_COMPRESS_PROMPT,
  makeGenerationRetrySetting,
  firstImageBlockingIssueForSend, inspectImageAttachment, imageAttachmentBlocksSend,
  probeVisionModelHealth,
  createMcpManagementTools, createMcpServerTools, makeAgentTool, createSkillTools,
  createWorkspaceTools, createWorkspaceArtifactTools, createDeepReadPlaybookTools,
  loadAssistants, saveAssistants, patchAssistant,
  reasoningLevelForModel, withReasoningLevelForModel,
  defaultReasoningLevelForModel, reasoningLevelsForModel,
  deriveSandboxActivities, mergeSandboxTimeline, idleSandboxActivity,
  withStepProgress, isActiveOperation,
  groupMessageParts,
  buildTopModelMenuGroups,
  loadProviders, loadDisplaySetting, isCloseEnoughToBottom, FOLLOW_BOTTOM_TOLERANCE_VP,
  createMiniAppPromptTransformer, createMiniAppOutputTransformer,
  makeMiniAppSetting, loadMiniAppSetting,
} from '@amber/chat-domain';
import type {
  MiniAppSetting, MiniAppRecord, MiniAppRepository,
} from '@amber/chat-domain';
import type {
  ThinkingStep, MessagePartBlock,
  TopModelMenuGroup, ReasoningLevelOption, ReasoningLevel,
} from '@amber/chat-domain';
import {
  getChatRepository, getChatAssistants, resolveChatRuntime, describeChatModel, ChatRuntime,
  getChatKvStore, getCompactStore, getSessionGrantStore,
  getChatModelSelection, saveChatModelSelection,
  resolveChatProviderChoice, ChatProviderChoice, ChatModelSelection,
  buildProviderApi, currentChatModelUuid, loadTaskModelIds, resolveTaskChatModelChoice,
  resolveTaskModelChain, loadTaskPrompts, TaskPrompts,
  getMcpManager, initMcpManager, getToolActivityStore, getSkillManager,
  getWorkspaceManager,
  getSkillWorkspacePort, getDeepReadPlaybookRepository, getAgentPromptConfigTool,
  getPermissionsStatusTool, getAgentTaskTools, getAgentCronTools,
  hydrateSubAgentSetting, getSubAgentTools, SubAgentToolsContext,
  getSubAgentManager,
} from '../di/AppContainer.ets';
import type { SubAgentRun } from '@amber/chat-domain';
// Phase 1:消息流跟随底部(纯逻辑 helper,零 SDK 依赖;ChatPage 侧只做状态接线)
import {
  transitionOnUserScroll, transitionOnStreamUpdate, transitionOnGenerateEnd,
  transitionOnJumpToBottom, shouldScrollOnStreamUpdate,
  shouldScrollOnAppend,
} from '@amber/chat-domain';
import type { FollowMode } from '@amber/chat-domain';
// Phase 5:历史分页窗口加载(纯逻辑 helper;ChatPage 侧只做状态接线)
import {
  createInitialTimelineLoadState, applyTailWindow, beginLoadOlder,
  applyOlderPage, canLoadOlder, coversIndex, nextOlderPageOffset,
  mergeOlderNodes,
} from '@amber/chat-domain';
import type { ConversationTimelineLoadState } from '@amber/chat-domain';
import { getMcpSettingsStore } from '../platform_impl/McpSettingsStore.ets';
import { inflateRawEntry, createEntryXmlPull } from '../platform_impl/EntryDocumentParser.ets';
import {
  createArtifactHttpPort, createArtifactImagePort, createDeflateRawPort,
} from '../platform_impl/ArtifactPorts.ets';
import { compressImageAsDataUrl } from '../platform_impl/ImageCompress.ets';
import { EntryAbortController } from '../platform_impl/EntryAbortController.ets';
import { MarkdownText } from '../components/MarkdownText.ets';
import { Icon } from '../components/ui/Icon.ets';
import { DotText, DotGround } from '../components/ui/DotMatrix.ets';
import { DotLoading } from '../components/ui/DotLoading.ets';
import { speakText } from '../platform_impl/TtsEngine.ets';
import { addFavorite } from '../platform_impl/FavoriteStore.ets';
import { ChatMessageReasoning } from '../components/ChatMessageReasoning.ets';
import { ChatMessageTools } from '../components/ChatMessageTools.ets';
import { ChatMessageAskUserStep } from '../components/ChatMessageAskUserStep.ets';
import { ChatInput } from '../components/ChatInput.ets';
import type { QuickMessage } from '../components/SlashCommandPanel.ets';
import { ContextUsageIndicator } from '../components/ChatInputUsage.ets';
import { ChatMessageAvatar } from '../components/ChatMessageAvatar.ets';
import { ChatMessageSubAgentStep } from '../components/ChatMessageSubAgentStep.ets';
import { ChatMessageCouncilStep } from '../components/ChatMessageCouncilStep.ets';
import { ChatMessageActionFooter, resolveActionFooterMode } from '../components/ChatMessageActionFooter.ets';
import { SubAgentRunSheet } from '../components/SubAgentRunSheet.ets';
import { ChatMessageCopySheet } from '../components/ChatMessageCopySheet.ets';
import { ChatMessageToolPreviewSheet } from '../components/ChatMessageToolPreviewSheet.ets';
import { ChatMessageActionsSheet } from '../components/ChatMessageActionsSheet.ets';
import { ChatDrawer } from '../components/ChatDrawer.ets';
import { ChatListGrouped } from '../components/ChatListGrouped.ets';
import { TopModelMenu } from '../components/TopModelMenu.ets';
import { ContentAttachmentChip, urlBasename } from '../components/ContentAttachmentChip.ets';
import { createEntryClipboardPort, sha256HexUtf8 } from '../platform_impl/EntryLocalToolPorts.ets';
import { ensureSearchSdk } from '../platform_impl/SearchSdkInit.ets';
import { createEntryOcrTransformer, findModelByIdInProviders, findProviderOfModel, generateVisionText, loadOcrSeed, OcrSeed } from '../platform_impl/OcrSupport.ets';
import { encodeImageDetailed } from '../platform_impl/EntryImageEncoder.ets';
import {
  AgentRuntimeSnapshot, defaultAgentRuntimeSnapshot, loadAgentRuntimeSnapshot, systemLanguage,
} from '../platform_impl/AgentRuntimePrefs.ets';
import { createEntryPlaceholderValues } from '../platform_impl/PlaceholderSupport.ets';
import { createEntryDocumentReader } from '../platform_impl/DocumentSupport.ets';
import { loadLorebooks, loadModeInjections } from '../platform_impl/ExtensionPrefs.ets';
import { createEntryBase64ImageTransformer } from '../platform_impl/Base64ImageSupport.ets';
import {
  getMemoryExtractionStoreOps, getMemoryReadRepository, getMemoryToolDeps,
} from '../platform_impl/MemoryStore.ets';
import { getMiniAppPlatform, getMiniAppRepositorySync } from '../platform_impl/MiniAppPlatform.ets';
import type { MiniAppPlatform } from '../platform_impl/MiniAppPlatform.ets';
import { MiniAppChatCard } from '../components/MiniAppChatCard.ets';

// D-122:沙盒 sheet 代码块底色(设置面 ROW 惯例同值)
const ROW: string = SURFACE2;

// 1:1 原型空态 hero 轮换句(3.2s 淡入淡出)
const GREETINGS: string[] = [
  '今天想聊点什么？', '想精读哪一篇？', '描述一下，就搭给你看。', '要续写哪个世界？', '让四个模型辩一场？',
];

const HILOG_DOMAIN: number = 0x0001;
const HILOG_TAG: string = 'DeepRead.ChatPage';

// Phase 5:历史分页常量(计划 §6.7 — PAGE_SIZE 对齐 Android 会话分页规模)
const TIMELINE_PAGE_SIZE: number = 50;
// 首屏跨页定位最多连续追加历史页数/节点数,避免极端会话无限链。
const TIMELINE_MAX_PREFETCH_PAGES: number = 40;
const TIMELINE_MAX_PREFETCH_NODES: number = 2000;


// 节点视图模型:分支切换/重新生成需要节点级信息
interface MsgRow {
  nodeId: string;
  message: UIMessage;
  branchCount: number;
  selectIndex: number;
  isAssistant: boolean;
  isLastAssistant: boolean;
}

const buildRows = (conv: Conversation): MsgRow[] => {
  let lastAssistantIdx: number = -1;
  for (let i = conv.messageNodes.length - 1; i >= 0; i--) {
    if (nodeRole(conv.messageNodes[i]) === 'assistant') {
      lastAssistantIdx = i;
      break;
    }
  }
  return conv.messageNodes.map((n, i: number): MsgRow => ({
    nodeId: n.id,
    message: nodeCurrentMessage(n),
    branchCount: n.messages.length,
    selectIndex: n.selectIndex,
    isAssistant: nodeRole(n) === 'assistant',
    isLastAssistant: i === lastAssistantIdx,
  }));
};

// Phase 5:node 窗口 → rows(与 buildRows 同一映射;窗口用 messageNodes 切片,
//   保证首屏后置定位参数与分支切换走同一 MsgRow 形状)
const buildRowsFromNodes = (nodes: MessageNode[]): MsgRow[] => {
  const rows: MsgRow[] = [];
  const nodeCount: number = nodes.length;
  let lastAssistantRow: MsgRow | null = null;
  for (let i = 0; i < nodeCount; i++) {
    const row: MsgRow = {
      nodeId: nodes[i].id,
      message: nodeCurrentMessage(nodes[i]),
      branchCount: nodes[i].messages.length,
      selectIndex: nodes[i].selectIndex,
      isAssistant: nodeRole(nodes[i]) === 'assistant',
      isLastAssistant: false,
    };
    rows.push(row);
    if (row.isAssistant) lastAssistantRow = row;
  }
  if (lastAssistantRow !== null) lastAssistantRow.isLastAssistant = true;
  return rows;
};

@Component
struct MessageRow {
  @StorageProp('darkMode') darkMode: boolean = false;
  @Prop message: UIMessage;
  @Prop branchCount: number = 1;
  @Prop selectIndex: number = 0;
  @Prop isLastAssistant: boolean = false;
  // D-053:本行是否属于活动流式尾部(reasoning loading 判定;
  //   Android messageLoading 语义,流式尾/regen 预览传 true)
  @Prop @Watch('onStreamingChange') streaming: boolean = false;
  @Prop autoCloseThinking: boolean = true;
  // 同一 message id 可同时出现在已提交行与工具续跑预览中；预览使用独立键命名空间。
  @Prop streamKeyTag: string = '';
  onSwitch: (idx: number) => void = (_idx: number): void => {};
  onRegenerate: () => void = (): void => {};
  onDelete: () => void = (): void => {};
  onEdit: () => void = (): void => {};
  onOpenActions: () => void = (): void => {};
  onFork: () => void = (): void => {};
  // D-057:工具审批回调(toolCallId 定位,ChatService.handleToolApproval)
  onApproveTool: (toolCallId: string) => void = (_id: string): void => {};
  onDenyTool: (toolCallId: string) => void = (_id: string): void => {};
  // D-105:ask_user 应答回调(ChatMessageAskUserStep.kt onToolAnswer)
  onAnswerTool: (toolCallId: string, answer: string) => void = (_id: string, _a: string): void => {};
  onOpenSubAgentRun: (runId: string, tools: UIMessagePartTool[]) => void = (_id: string, _t: UIMessagePartTool[]): void => {};
  onCopy: () => void = (): void => {};
  onSpeak: (text: string) => void = (_t: string): void => {};
  onFavorite: (text: string) => void = (_t: string): void => {};
  onOpenToolPreview: (part: UIMessagePartTool) => void = (_p: UIMessagePartTool): void => {};
  // P3:可见文本增长回调(逐字 reveal / 终态 drain 推进时触发;冒泡到 ChatPage
  //   复用既有 followStreamingUpdate,不新建第二套滚动状态机)
  onTextGrow: () => void = (): void => {};
  // MiniApp:卡片回调冒泡(onRun 进 Runner;onModify 组装修订 prompt 回填输入框;
  //   onOpenList 进列表页 — 均在 ChatPage 层实现,MessageRow 仅透传)
  onRunMiniApp: (appId: string) => void = (_appId: string): void => {};
  onModifyMiniApp: (prompt: string) => void = (_prompt: string): void => {};
  onOpenMiniAppList: () => void = (): void => {};
  // D-053:reasoning 卡片态(part 下标 → 用户显式选择;未选走默认迁移)
  @State reasoningStates: Record<string, string> = {};
  @State thinkingExpandedStates: Record<string, boolean> = {};
  // D-105:ask_user 草稿态(题 id → 文本/单选值;:102;题 id → 多选集;:104)
  @State askAnswers: Record<string, string> = {};
  @State askMultiAnswers: Record<string, string[]> = {};
  // D-053:本会话内见过流式(Android sawStreamingMessage:123 —
  //   流式结束后保持 Preview 不自动折叠)
  @State sawStream: boolean = false;
  // 1:1 原型 fadeRise .26s ease-out:行入场 opacity 0→1 + translateY 4→0
  @State enterOpacity: number = 0;
  @State enterY: number = 4;

  aboutToAppear(): void {
    if (this.streaming) this.sawStream = true;
  }

  private onStreamingChange(_propName: string): void {
    if (this.streaming) this.sawStream = true;
  }

  // loading = messageLoading && finishedAt == null(ChatMessageReasoning.kt:107)
  private reasoningLoading(p: UIMessagePartReasoning): boolean {
    return this.streaming && p.finishedAt === null;
  }

  // 朗读文本:text part 拼接 + 去 **/`(TTS 友好)
  private plainText(): string {
    let s: string = '';
    for (const p of this.message.parts) {
      if (p.type === 'text') s += p.text;
    }
    return s.replace(/\*\*([^*]+)\*\*/g, '$1').replace(/`([^`]+)`/g, '$1');
  }

  // :952-960 已拒绝展示:'已拒绝' + 非空白 reason → ': reason'(红色;
  //   chat_message_tool_denied zh 逐字,冒号为 ASCII ': ')
  private deniedLabel(t: UIMessagePartTool): string {
    if (t.approvalState.type !== 'denied') return '';
    const reason: string = t.approvalState.reason;
    return reason.trim().length > 0 ? `已拒绝: ${reason}` : '已拒绝';
  }

  // ===== D-105:ask_user 问题卡片(ChatMessageAskUserStep.kt 子集) =====
  // :87-99 解析(渲染期重算,题量小;解析失败 → [])
  private askQuestionsOf(t: UIMessagePartTool): AskUserQuestion[] {
    return parseAskUserQuestions(t.input);
  }

  // :174 单选/文本选项 chip 点选
  private setAskAnswer(qid: string, value: string): void {
    const copy: Record<string, string> = {};
    const keys: string[] = Object.keys(this.askAnswers);
    for (const k of keys) {
      copy[k] = this.askAnswers[k];
    }
    copy[qid] = value;
    this.askAnswers = copy;
  }

  // :191-196 多选 chip 切换(Set 增删语义)
  private toggleAskMulti(qid: string, option: string): void {
    const copy: Record<string, string[]> = {};
    const keys: string[] = Object.keys(this.askMultiAnswers);
    for (const k of keys) {
      copy[k] = this.askMultiAnswers[k].slice();
    }
    const cur: string[] = copy[qid] !== undefined ? copy[qid] : [];
    const idx: number = cur.indexOf(option);
    if (idx >= 0) {
      cur.splice(idx, 1);
    } else {
      cur.push(option);
    }
    copy[qid] = cur;
    this.askMultiAnswers = copy;
  }

  // :307-309 清空
  private clearAskDrafts(): void {
    this.askAnswers = {};
    this.askMultiAnswers = {};
  }

  // :330-347 提交(载荷逐字;使能判定 :348-353 由按钮侧调 isAskUserSubmittable)
  private submitAskAnswers(t: UIMessagePartTool): void {
    const qs: AskUserQuestion[] = this.askQuestionsOf(t);
    this.onAnswerTool(t.toolCallId,
      buildAskUserAnswerPayload(qs, this.askAnswers, this.askMultiAnswers));
  }

  // 初始 Collapsed(:88);流式开始 → Preview(:125-126,showThinkingContent
  //   设置项鸿蒙未接,Android 默认 true);流式过 → 保持 Preview(:129-133)
  //   idx 为字符串 key(block-step 复合索引,避免数字碰撞)
  private reasoningStateAt(idx: string): ReasoningCardState {
    const s: string | undefined = this.reasoningStates[idx];
    if (s === 'collapsed' || s === 'preview' || s === 'expanded') {
      return s as ReasoningCardState;
    }
    return (this.streaming || this.sawStream) ? 'preview' : 'collapsed';
  }

  private toggleReasoning(idx: string, loading: boolean): void {
    const cur: ReasoningCardState = this.reasoningStateAt(idx);
    const next: ReasoningCardState =
      onReasoningExpandedChange(!reasoningCardStateExpanded(cur), loading);
    const copy: Record<string, string> = {};
    const keys: string[] = Object.keys(this.reasoningStates);
    for (const k of keys) {
      copy[k] = this.reasoningStates[k];
    }
    copy[idx] = next;
    this.reasoningStates = copy;
  }

  private autoCollapseReasoning(idx: string): void {
    if (this.streaming || this.reasoningStateAt(idx) !== 'expanded') return;
    const copy: Record<string, string> = {};
    const keys: string[] = Object.keys(this.reasoningStates);
    for (const k of keys) copy[k] = this.reasoningStates[k];
    copy[idx] = 'preview';
    this.reasoningStates = copy;
  }

  // groupMessageParts 分组结果(每次 build 重算,题量小)
  private getBlocks(): MessagePartBlock[] {
    return groupMessageParts(this.message.parts);
  }

  private isLastTextBlock(blocks: MessagePartBlock[], index: number): boolean {
    for (let i: number = blocks.length - 1; i >= 0; i--) {
      // ArkTS 不通过下标访问收窄判别联合 → 先取局部 const
      const block: MessagePartBlock = blocks[i];
      if (block.kind === 'content' && block.part.type === 'text') {
        return i === index;
      }
    }
    return false;
  }

  // ThinkingBlock 组级折叠(ChainOfThought 语义)
  private thinkingExpanded(bi: number): boolean {
    const v: boolean | undefined = this.thinkingExpandedStates[`${bi}`];
    return v !== undefined ? v : true; // 默认展开
  }

  private toggleThinkingExpanded(bi: number): void {
    // 含 pending 审批 tool 的 block 不允许折叠(用户需要看到审批按钮)
    if (this.hasPendingToolInBlock(bi)) return;
    const key: string = `${bi}`;
    const cur: boolean = this.thinkingExpanded(bi);
    const copy: Record<string, boolean> = {};
    const keys: string[] = Object.keys(this.thinkingExpandedStates);
    for (const k of keys) {
      copy[k] = this.thinkingExpandedStates[k];
    }
    copy[key] = !cur;
    this.thinkingExpandedStates = copy;
  }

  // 检查指定 block 是否含 pending 审批 tool(禁止折叠)
  private hasPendingToolInBlock(bi: number): boolean {
    const blocks: MessagePartBlock[] = this.getBlocks();
    if (bi >= blocks.length) return false;
    const block: MessagePartBlock = blocks[bi];
    if (block.kind !== 'thinking') return false;
    // ArkTS 联合 narrowing 后需显式访问
    const steps: ThinkingStep[] = (block as Record<string, Object>)['steps'] as ThinkingStep[];
    for (const step of steps) {
      if (step.kind === 'tool' && step.tool.approvalState.type === 'pending') return true;
    }
    return false;
  }

  private thinkingSummary(steps: ThinkingStep[]): string {
    let reasoningCount: number = 0;
    let toolCount: number = 0;
    for (const step of steps) {
      if (step.kind === 'reasoning') reasoningCount++;
      else if (step.kind === 'tool') toolCount++;
    }
    if (reasoningCount > 0 && toolCount > 0) return `思考 ${reasoningCount} 段 · 工具 ${toolCount} 次`;
    if (reasoningCount > 0) return `思考 ${reasoningCount} 段`;
    if (toolCount > 0) return `工具 ${toolCount} 次`;
    return '思考过程';
  }

  build() {
    Column() {
      // 1:1 原型抬头:assistant=amber 标签(+流式光标),user=无
      ChatMessageAvatar({ message: this.message, streaming: this.streaming })
      // 阶段 A 接入:用 groupMessageParts 分组渲染,而非直接遍历 parts。
      //   ThinkingBlock → 连续 reasoning+tool 折叠为一组;
      //   ContentBlock → text/image/video/audio/document/mini_app;
      //   SubAgentBlock/CouncilBlock → 占位卡片(完整 UI 后续梯队)。
      //   reasoning 状态索引仍用原始 part index(通过 ContentBlock.index 或
      //   ThinkingBlock 内 step 遍历时累加获得)。
      Column() {
      ForEach(this.getBlocks(), (block: MessagePartBlock, bi: number): void => {
        if (block.kind === 'thinking') {
          // 组级折叠头部(ChainOfThought 语义:摘要+步骤数)
          Row() {
            Text(this.thinkingExpanded(bi) ? '▾' : '▸').fontSize(11).fontColor(INK3)
            Text(this.thinkingSummary(block.steps))
              .fontSize(META).fontColor(INK3)
              .margin({ left: 6 })
              .layoutWeight(1)
          }
          .width('100%')
          .padding({ left: 8, right: 8, top: 4, bottom: 4 })
          .backgroundColor(SURFACE2)
          .borderRadius(6)
          .margin({ bottom: 2 })
          .onClick((): void => { this.toggleThinkingExpanded(bi); })

          if (this.thinkingExpanded(bi)) {
            ForEach(block.steps, (step: ThinkingStep, si: number): void => {
            if (step.kind === 'reasoning') {
              ChatMessageReasoning({
                part: step.reasoning,
                state: this.reasoningStateAt(`${bi}-${si}`),
                loading: this.reasoningLoading(step.reasoning),
                autoCloseThinking: this.autoCloseThinking,
                onToggle: (): void => {
                  this.toggleReasoning(`${bi}-${si}`, this.reasoningLoading(step.reasoning));
                },
                onAutoCollapse: (): void => {
                  this.autoCollapseReasoning(`${bi}-${si}`);
                },
              })
            } else if (step.kind === 'tool') {
              if (step.tool.toolName === ASK_USER_TOOL_NAME) {
                ChatMessageAskUserStep({
                  part: step.tool,
                  answers: this.askAnswers,
                  multiAnswers: this.askMultiAnswers,
                  onSubmit: (_a: Record<string, string>, _m: Record<string, string[]>): void => {
                    this.submitAskAnswers(step.tool);
                  },
                  onAnswer: (qid: string, value: string): void => {
                    this.setAskAnswer(qid, value);
                  },
                  onToggleMulti: (qid: string, opt: string): void => {
                    this.toggleAskMulti(qid, opt);
                  },
                  onClear: (): void => {
                    this.clearAskDrafts();
                  },
                })
              } else {
                ChatMessageTools({
                  part: step.tool,
                  onApprove: (toolCallId: string): void => {
                    this.onApproveTool(toolCallId);
                  },
                  onDeny: (toolCallId: string): void => {
                    this.onDenyTool(toolCallId);
                  },
                  onOpenPreview: (): void => {
                    this.onOpenToolPreview(step.tool);
                  },
                  deniedLabel: step.tool.approvalState.type === 'denied'
                    ? this.deniedLabel(step.tool) : '',
                })
              }
            }
          }, (step: ThinkingStep, si: number): string => `${bi}-${si}-${step.kind}`)
          }
        } else if (block.kind === 'content') {
          if (block.part.type === 'text') {
            if (this.message.role === 'user') {
              // 1:1 原型 UserBubble:INK 底/USER_INK 字/radius 16-16-5-16/padding 11-15
              // P3:user 内容恒 settled(streaming=false)→ 即时渲染,精确缓存兜底
              Column() {
                MarkdownText({
                  content: block.part.text,
                  fontSize: 15,
                  fontColor: USER_INK,
                  fillWidth: false,
                  streamKey: `${this.streamKeyTag}${this.message.id}#${block.index}`,
                })
              }
              .alignItems(HorizontalAlign.Start)
              .padding({ left: 15, right: 15, top: 11, bottom: 11 })
              .backgroundColor(USER_BG)
              .borderRadius({ topLeft: 16, topRight: 16, bottomRight: 5, bottomLeft: 16 })
              .constraintSize({ maxWidth: '82%' })
              .margin({ bottom: 4 })
            } else {
              // 1:1 原型 AgentTurn:无卡片,正文直接落在 BG 上(15/1.66/ink)
  // P3:活动流式尾消息(streaming=true)只对最后文本块启用逐字 reveal;
  //   streamKey(messageId+part index)恒传 → 跨 streamingTail→rows 迁移
  //   保留 pacing 状态完成终态 drain,且 settled 内容命中精确缓存;
  //   历史/非尾文本 streaming=false → 即时完整渲染,不逐字重放。
  Column() {
    MarkdownText({
      content: block.part.text,
      fontSize: 15,
      fontColor: INK,
      streaming: this.streaming && this.isLastTextBlock(this.getBlocks(), bi),
      streamKey: `${this.streamKeyTag}${this.message.id}#${block.index}`,
      onVisibleGrow: (): void => { this.onTextGrow(); },
    })
  }
              .margin({ bottom: 4 })
            }
          } else if (block.part.type === 'image') {
            Image(block.part.url)
              .width(160)
              .objectFit(ImageFit.Cover)
              .borderRadius(8)
              .margin({ bottom: 4 })
          } else if (block.part.type === 'video') {
            // M2.2:占位文本 → 真实附件 chip(图标 + 文件名/mime)
            ContentAttachmentChip({
              icon: '🎬',
              label: urlBasename(block.part.url).length > 0 ? urlBasename(block.part.url) : '视频',
              sublabel: block.part.mime,
            })
          } else if (block.part.type === 'audio') {
            ContentAttachmentChip({
              icon: '🎵',
              label: block.part.fileName.length > 0 ? block.part.fileName : (urlBasename(block.part.url).length > 0 ? urlBasename(block.part.url) : '音频'),
              sublabel: block.part.mime,
            })
          } else if (block.part.type === 'document') {
            ContentAttachmentChip({
              icon: '📄',
              label: block.part.fileName.length > 0 ? block.part.fileName : (urlBasename(block.part.url).length > 0 ? urlBasename(block.part.url) : '文档'),
              sublabel: block.part.mime,
            })
          } else if (block.part.type === 'mini_app') {
            // MiniApp:M2.2 占位卡 → 真卡片(运行/修改/菜单;回调冒泡到 ChatPage)
            MiniAppChatCard({
              part: block.part,
              onRun: (appId: string): void => { this.onRunMiniApp(appId); },
              onOpenList: (): void => { this.onOpenMiniAppList(); },
              onModify: (prompt: string): boolean => {
                this.onModifyMiniApp(prompt);
                return true;
              },
            })
          }
        } else if (block.kind === 'subagent') {
          ChatMessageSubAgentStep({
            tools: block.step.tools,
            runId: block.step.runId,
            onOpenRun: (rid: string): void => {
              this.onOpenSubAgentRun(rid, block.step.tools);
            },
          })
        } else if (block.kind === 'council') {
          ChatMessageCouncilStep({
            tools: block.step.tools,
            runId: block.step.runId,
            onOpenRun: (_rid: string): void => {
              // council run sheet:复用 tool preview sheet 展示 council 工具详情
              if (block.step.tools.length > 0) {
                this.onOpenToolPreview(block.step.tools[0]);
              }
            },
          })
        }
      }, (block: MessagePartBlock, bi: number): string => `block-${bi}-${block.kind}`)
      }
      .gesture(
        GestureGroup(
          GestureMode.Exclusive,
          LongPressGesture().onAction((): void => {
            if (this.message.role === 'user') this.onOpenActions();
          }),
          TapGesture().onAction((): void => {
            if (this.message.role === 'user') this.onEdit();
          }),
        )
      )
      // 第三梯队:ChatMessageActionFooter 替换内联操作行(annotations + branch + actions 统一尾部)
      // streaming 期间交给 resolveActionFooterMode → reserved(占位防跳动),不再强制 hidden
      ChatMessageActionFooter({
        annotations: this.message.annotations,
        mode: resolveActionFooterMode(
          this.message.role, this.isLastAssistant, this.streaming, this.message.parts.length > 0,
        ),
        isUser: this.message.role === 'user',
        isLastAssistant: this.isLastAssistant,
        branchCount: this.branchCount,
        selectIndex: this.selectIndex,
        onCopy: (): void => this.onCopy(),
        onFork: (): void => this.onFork(),
        onEdit: (): void => this.onEdit(),
        onRegenerate: (): void => this.onRegenerate(),
        onSpeak: (): void => { this.onSpeak(this.plainText()); },
        onFavorite: (): void => { this.onFavorite(this.plainText()); },
        onSwitch: (idx: number): void => { this.onSwitch(idx); },
      })
    }
    .width('100%')
    .alignItems(this.message.role === 'user' ? HorizontalAlign.End : HorizontalAlign.Start)
    .padding({ left: 20, right: 20, top: 8, bottom: 8 })
    .opacity(this.enterOpacity)
    .translate({ y: this.enterY })
    .onAppear((): void => {
      animateTo({ duration: 260, curve: Curve.EaseOut }, (): void => {
        this.enterOpacity = 1;
        this.enterY = 0;
      });
    })
  }
}

// holder 模式:跨 then 传递 runtime(ArkTS 闭包收窄限制 + arkts-no-obj-literals-as-types)
interface RuntimeHolder {
  rt: ChatRuntime | null;
}

// holder 模式:drain 的 append 失败时跨 then 回灌该 dispatch
interface PendingRequeueHolder {
  msg: PendingUserMessage | null;
}

// D-048:排队消息 chip 视图模型
interface PendingChipRow {
  id: string;
  preview: string;
}

const QUICK_MESSAGES_KEY: string = 'quick_messages';

function decodeQuickMessages(raw: string | null): QuickMessage[] {
  if (raw === null) return [];
  try {
    const parsed: QuickMessage[] = JSON.parse(raw) as QuickMessage[];
    if (!Array.isArray(parsed)) return [];
    const out: QuickMessage[] = [];
    for (const item of parsed) {
      if (typeof item.id !== 'string' || typeof item.title !== 'string' || typeof item.content !== 'string') {
        continue;
      }
      if (item.content.trim().length === 0) continue;
      out.push({ id: item.id, title: item.title, content: item.content });
    }
    return out;
  } catch (_e) {
    return [];
  }
}

@Entry
@Component
struct ChatPage {
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  // P4:底部导航条安全区高度(EntryAbility 全屏布局避让区 bottomRect,vp;0=手势导航全隐藏)
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  // 主题切换时触发整页重建(build 重读可变令牌)
  @StorageProp('darkMode') darkMode: boolean = false;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  // Return-to-page forces rebuild, reads latest theme tokens (background pages in stack don't receive StorageProp updates)
  @State themeTick: number = 0;
  // 1:1 原型 slideInR .28s ease-out:整页入场
  @State pageEnterOpacity: number = 0;
  @State pageEnterX: number = 20;
  // 头部悬浮层:scrollTop>4 玻璃化
  @State headerSolid: boolean = false;
  // 空态 hero 问候语轮换(3.2s 淡入淡出)
  @State greetIdx: number = 0;
  @State greetOpacity: number = 1;
  private greetTimer: number = -1;
  private cursorTimer: number = -1;
  private pageAlive: boolean = false;
  // 页面被子路由覆盖时仍存活；IME 和异步 UI 刷新必须遵守可见性。
  private pageVisible: boolean = false;
  private hasShownOnce: boolean = false;
  private generationToken: number = 0;
  private favSeq: number = 0;
  private searchSettingsSeq: number = 0;
  private quickMessagesSeq: number = 0;
  @State greetName: string = '';
  @State cursorOn: boolean = true;
  @State rows: MsgRow[] = [];
  // Phase 5:历史分页窗口 — this.conversation 恒全量(生成/checkpoint/上下文),
  //   rows 只承载尾部窗口 + 已上滑加载的历史页(展示层)
  @State timelineLoadState: ConversationTimelineLoadState = createInitialTimelineLoadState();
  @State prefetching: boolean = false;
  // 最近一次历史页前插计数(锚点补偿已在加载回调内就地执行,无需持久读)
  @State streamingTail: UIMessage[] = [];
  @State suggestions: string[] = [];
  @State inputText: string = '';
  @State quickMessages: QuickMessage[] = [];
  @State sending: boolean = false;
  // D-087:任务模型选择快照(compress/title/suggestion;aboutToAppear 异步加载,
  //   与 ocrSeed/searchSettings 同档;null = 未解析或无可用模型 → 回退 chat 运行态)
  private compressChoice: ChatProviderChoice | null = null;
  private taskChoicesLoaded: boolean = false;
  // D-094:任务提示词快照(compress 同步工具构建位使用;aboutToAppear 加载,
  //   与 compressChoice/ocrSeed 同档;title/suggestion 走调用时新取)
  private taskPromptsCache: TaskPrompts | null = null;
  // D-070:搜索偏好(aboutToAppear 异步加载;SearchTools 工具组输入)
  searchSettings: SearchSettings | null = null;
  // D-130:cron 四工具(aboutToAppear 异步水合,与 searchSettings 同档;
  //   水合完成前的极早轮次不带 cron 工具 — 异步加载时序等价先例)
  private agentCronToolsCache: AgentTool[] = [];
  // D-131:AgentTaskTools 六件,与 cron 共用 AgentTaskStore/调度器缓存。
  private agentTaskToolsCache: AgentTool[] = [];
  // D-132c:SubAgentTools 五件(每会话动态挂载)。parentToolsProvider 为惰性闭包,
  //   读 baseToolsCache(在 buildToolLoop 内 step-4 设置),保证只暴露已 profile
  //   的 parent base tools,不含 subagent_* 自身。subagentEnabled + 会话存在才挂载。
  private subAgentEnabled: boolean = false;
  private subAgentMode: string = 'roster';
  // M2.1:ReasoningPicker 段集 + 当前档位(模型感知;aboutToAppear 装配,选档位时刷新)
  @State reasoningLevels: ReasoningLevelOption[] = [];
  @State currentReasoningLevel: string = '';
  // 当前模型内部 id(ReasoningPicker 记忆 key;refreshReasoningPicker 设置)
  private currentModelId: string = '';
  private baseToolsCache: AgentTool[] = [];
  private subAgentToolsCache: AgentTool[] = [];
  @State errorMsg: string = '';
  // D-108:拒绝理由弹窗态(ToolDenyReasonDialog;空 = 关闭)
  @State denyDialogToolCallId: string = '';
  @State denyReasonText: string = '';
  // D-110:上下文用量指示(ContextUsageIndicator)— compacts/窗口快照 + 压缩活动计数
  @State contextCompacts: ConversationCompact[] = [];
  @State contextWindowTokens: number | null = null;
  @State compactActiveCount: number = 0;
  @State autoCloseThinking: boolean = true;
  private contextEstimateConversation: Conversation | null = null;
  private contextEstimateCompacts: ConversationCompact[] | null = null;
  private contextEstimateTokens: number = 0;
  @State title: string = '新对话';
  @State modelLabel: string = '';
  // TopModelMenu:顶部模型快速切换下拉(Android TopModelMenu.kt)
  @State modelMenuOpen: boolean = false;
  // 打开重入守卫:async 装配期间阻止重复触发(避免连点产生多条 loadProviders 链)
  @State modelMenuLoading: boolean = false;
  @State modelMenuGroups: TopModelMenuGroup[] = [];
  @State modelMenuCurrentProvider: string = '';
  @State modelMenuCurrentModel: string = '';
  @State editingNodeId: string = '';
  @State editingMessageId: string = '';
  @State editingTitle: boolean = false;
  @State titleInput: string = '';
  // D-038:待发图片(data: URL 物化,发送后清空)
  @State pendingImages: string[] = [];
  // D-041:regenerate 流式预览(目标节点行下渲染;完成后清空)
  @State regenTail: UIMessage | null = null;
  @State regenNodeId: string = '';
  // D-048:发送排队;D-052:KV 持久化(进程死亡恢复,PendingMessageStore 语义)
  @State pendingRows: PendingChipRow[] = [];
  // D-122:沙盒活动时间线(ChatPage.kt:500-536)— live 单槽订阅 + 步骤选择
  //   (-1 = 最新,:523-527 coerceIn 等价)+ sheet 开关
  @State liveActivity: SandboxActivityUiState | null = null;
  @State selectedSandboxIndex: number = -1;
  @State sandboxSheetOpen: boolean = false;
  // SubAgent run sheet
  @State subAgentSheetRunId: string = '';
  @State subAgentSheetOpen: boolean = false;
  @State subAgentLiveText: string = '';
  private subAgentSheetTools: UIMessagePartTool[] = [];
  private unsubSubAgentLive: (() => void) | null = null;
  // Copy sheet
  @State copySheetOpen: boolean = false;
  private copySheetMessage: UIMessage | null = null;
  // Phase 6:消息长按动作 sheet
  @State actionsSheetOpen: boolean = false;
  private actionsSheetRow: MsgRow | null = null;
  // Phase 6:会话抽屉
  @State drawerOpen: boolean = false;
  @State drawerConversations: ConversationSummary[] = [];
  // Tool preview sheet
  @State toolPreviewSheetOpen: boolean = false;
  private toolPreviewPart: UIMessagePartTool | null = null;
  private unsubActivity: () => void = (): void => {};
  private drawerSearchText: string = '';
  private pendingQueue: PendingUserMessage[] = [];
  private pendingDrainActive: boolean = false;
  private appendActive: boolean = false;
  private pickerToken: number = 0;
  @State materializingImages: boolean = false;
  // D-050:重试状态文案(strings.xml:386;null = 清除)
  @State retryStatus: string = '';
  private conversation: Conversation | null = null;
  private abortController: EntryAbortController | null = null;
  // Phase 2:停止生成收口 + 流式 checkpoint
  // 最后一次 raw 快照(onRawFlushSnapshot;checkpoint 投影基)
  private lastRawSnapshot: UIMessage[] = [];
  private checkpointRegenerateNodeId: string = '';
  private lastCheckpointAtMs: number = 0;
  private lastCheckpointLen: number = 0;
  // checkpoint 保存 promise 尾(串行化落库,不阻塞 provider chunk 回调)
  private checkpointTail: Promise<void> = Promise.resolve();
  private scroller: Scroller = new Scroller();
  // Phase 1:滚动跟随/暂停/跳底/首屏定位
  // @State 驱动跳底按钮显隐(跟随模式/离底状态变化需触发重绘)
  @State followMode: FollowMode = 'idle';
  @State jumpButtonVisible: boolean = false;
  private enableAutoScroll: boolean = true;
  private showMessageJumper: boolean = true;
  // 程序化贴底在流式追加后的同一帧:isAtEnd() 因内容高度尚未重排而滞后,
  //   故用「最近一次程序化贴底且用户未再离开」做 isAtEnd 近似(true = 贴底)。
  private atBottomApprox: boolean = true;
  private listViewportHeight: number = 0;
  private firstVisibleListIndex: number = -1;
  private lastVisibleListIndex: number = -1;
  private userTouchActive: boolean = false;
  // 首次构建 List 前 scroller 未绑定(先于 aboutToAppear 装配 rows),首帧滚底延迟到 List 挂载后
  private listReady: boolean = false;
  // 程序化滚动调用的下一帧标记:Idle 回调消费;无回调时也定时复位,不吞真实用户首帧。
  private programmaticScrollPending: boolean = false;
  private programmaticScrollToken: number = 0;
  // 首帧定位 armed(等 rows 装配后再于 List onAppear 执行);-1 = 底部
  private initialScrollArmed: boolean = false;
  private initialScrollTarget: number = -1;
  private initialScrollScheduleToken: number = 0;
  private initialJumpSuppressedUntilUserScroll: boolean = false;
  private generationEndScrollPending: boolean = false;
  // P4:IME 键盘高度跟随(对齐 Android ImeAutoScroller.kt —
  //   仅 delta>0 且应跟随时 scrollBy;收起不反向滚)。keyboardHeightChange
  //   回调单位为 px,列表滚动单位为 vp → 经 UIContext.px2vp 换算。
  private imeKeyboardHeightVp: number = 0;
  private imeListenerAttached: boolean = false;
  private imeWindow: window.Window | null = null;
  private imeProgrammaticScrollToken: number = 0;
  // P4:Context 用量详情 sheet 开关(5 段柱点击,紧凑详情)
  @State contextMeterOpen: boolean = false;
  // D-071b:OCR 种子(providers/ocr_model/ocr_prompt 快照,aboutToAppear 加载)
  private ocrSeed: OcrSeed | null = null;

  // D-072:agent_runtime 快照(transform 调用时读 = Android settingsFlow.value 语义)
  private agentRuntime: AgentRuntimeSnapshot = defaultAgentRuntimeSnapshot();
  // MiniApp:miniapp setting 快照(enabled 门;aboutToAppear 加载,
  //   transform 调用时读 = Android ctx.settings.agentRuntime.miniApp 语义)
  private miniAppSetting: MiniAppSetting = makeMiniAppSetting();
  // D-073:display_setting 用户信息快照(抽屉与 placeholder 共用)
  @State userNickname: string = '';
  @State userAvatar: string = '';
  // D-075:mode_injections/lorebooks 快照(transform 调用时读 = settingsFlow.value 语义)
  private modeInjections: PromptInjection[] = [];
  private lorebooks: Lorebook[] = [];

  // D-071b~D-075:input transformers 链 —
  //   [timeReminder, promptInjection, miniAppPrompt, template, placeholder,
  //   document, ocr]
  //   (Android ChatService.kt:169-177:TimeReminder 1/PromptInjection 2/
  //   MiniAppPrompt 3/Placeholder 4/Document 5/Ocr 6 — 相对序忠实;template 为
  //   D-016 既有位;MiniAppPrompt 依附 promptInjection 之后第 3 位);
  //   seed 未就绪当轮不带 OCR(同 D-070 搜索工具门模式)
  private buildInputTransformers(rt: ChatRuntime): MessageTransformer[] {
    const list: MessageTransformer[] = [
      createTimeReminderTransformer({
        enabled: (): boolean => this.agentRuntime.enableTimeReminder,
        formatDayOfWeek: (ms: number): string => dayOfWeekFullName(systemLanguage(), ms),
        formatLocalDateTime: javaLocalDateTimeString,
      }),
      createPromptInjectionTransformer({
        modeInjections: (): PromptInjection[] => this.modeInjections,
        lorebooks: (): Lorebook[] => this.lorebooks,
      }),
      createMiniAppPromptTransformer({
        enabled: (): boolean => this.miniAppSetting.enabled,
        loadApp: (id: string): Promise<MiniAppRecord | null> =>
          getMiniAppPlatform().then((platform: MiniAppPlatform): Promise<MiniAppRecord | null> =>
            platform.repository.getById(id)),
      }),
      createTemplateTransformer(),
      createPlaceholderTransformer(createEntryPlaceholderValues(
        rt.params.model.modelId, rt.params.model.displayName,
        (): string => this.userNickname)),
      createDocumentTransformer(createEntryDocumentReader()),
    ];
    if (this.ocrSeed !== null) {
      list.push(createEntryOcrTransformer(this.ocrSeed, rt.modelSupportsImageInput));
    }
    return list;
  }

  // MiniApp:repository 惰性单例(output transformer 依赖;Android Koin inject 等价)
  private miniAppRepositoryCache: MiniAppRepository | null = null;
  private miniAppRepository(): MiniAppRepository {
    if (this.miniAppRepositoryCache !== null) return this.miniAppRepositoryCache;
    this.miniAppRepositoryCache = getMiniAppRepositorySync();
    return this.miniAppRepositoryCache;
  }

  // D-077b:动态 system 块供给(recentChats 子集,GenerationHandler.kt:736);
  //   每次组装调用时重取(Android 每次 generateInternal 重算语义);
  //   enableRecentChatsReference 默认 true(PreferencesStore.kt:155)
  private loadExtraSystemBlocks(): Promise<string[]> {
    if (!this.agentRuntime.enableRecentChatsReference) return Promise.resolve([]);
    return getChatRepository().listRecent(RECENT_CHATS_PROMPT_LIMIT)
      .then((recent: ConversationSummary[]): string[] => {
        const items: RecentChatItem[] = recent.map(
          (s: ConversationSummary): RecentChatItem => ({
            title: s.title,
            lastChat: new Intl.DateTimeFormat(systemLanguage(), { dateStyle: 'medium' })
              .format(new Date(s.updateAt)),
          }));
        const prompt: string = buildRecentChatsPrompt(items);
        return prompt.length > 0 ? [prompt] : [];
      });
  }

  // D-085b:记忆召回块(GenerationHandler.kt:414 → :727,动态序最前);
  //   每次组装重取 — 召回对当前会话消息(含刚入列的 user)打分;
  //   空召回/三开关全关 → buildMemoryRecallPrompt 回 '' → 不进块(Android :727 isNotBlank 门)
  private loadMemorySystemBlocks(): Promise<string[]> {
    const conv: Conversation | null = this.conversation;
    if (conv === null) return Promise.resolve([]);
    const gate: MemoryRecallRuntimeGate = {
      enableCoreMemory: this.agentRuntime.enableCoreMemory,
      enableShortTermMemory: this.agentRuntime.enableShortTermMemory,
      enableLongTermMemory: this.agentRuntime.enableLongTermMemory,
      memoryRecall: this.agentRuntime.memoryRecall,
    };
    return buildMemoryRecallPrompt(gate, currentMessages(conv), getMemoryReadRepository())
      .then((prompt: string): string[] => prompt.length > 0 ? [prompt] : []);
  }

  private persistConversationCheckpoint(projected: Conversation): Promise<void> {
    const tail: Promise<void> = this.checkpointTail
      .then((): Promise<void> => getChatRepository().save(projected))
      .catch((e: Error): void => {
        hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'checkpoint persist failed: %{public}s', e.message);
      });
    this.checkpointTail = tail;
    return tail;
  }

  // checkpoint 投影在入队前同步完成，锁定当轮 conversation 与 regenerate 目标；
  // 后续页面切换或下一轮生成不会改变已排队快照的结构。
  private persistCheckpoint(snapshot: UIMessage[]): Promise<void> {
    if (this.conversation === null) return Promise.resolve();
    const conv: Conversation = this.conversation;
    const targetNodeId: string = this.checkpointRegenerateNodeId;
    const projected: Conversation = targetNodeId.length > 0
      ? checkpointRegenerateConversation(conv, targetNodeId, snapshot)
      : checkpointConversationTail(conv, snapshot);
    return this.persistConversationCheckpoint(projected);
  }

  // Phase 2:周期 checkpoint — 10s 或 512 字符合并信号(Android
  //   ChatStreamCheckpointRecorder 语义),从 raw 快照捕获、投影保存;
  //   不重复 append 节点(checkpointConversationTail 同 id 原位替换)。
  private captureStreamCheckpoint(snapshot: UIMessage[]): void {
    if (snapshot.length === 0) return;
    const last: UIMessage = snapshot[snapshot.length - 1];
    if (last.role !== 'assistant' && last.role !== 'user') return;
    const now: number = Date.now();
    const len: number = streamContentLength(last);
    if (shouldCheckpoint(now, this.lastCheckpointAtMs, this.lastCheckpointLen, len)) {
      this.lastCheckpointAtMs = now;
      this.lastCheckpointLen = len;
      this.persistCheckpoint(snapshot);
    }
  }

  // D-122:页面消失退订(store 为全局单例,防泄漏/跨页回调)
  // Phase 2:离页安全停止 — 生成中 abort + 以最后一次 raw 快照做 checkpoint 落库,
  //   保留部分回答(不会「切页即丢」);非生成时照旧清理订阅。
  //   push picker/settings/DeepRead 等离页场景同样先落 checkpoint 再释放,
  //   不虚假后台继续,也不在 checkpoint 完成前清 tail(计划 §3.6)。
  aboutToDisappear(): void {
    this.pageAlive = false;
    this.pageVisible = false;
    this.generationToken++;
    // P4:离页注销 IME 键盘高度监听(防跨页回调泄漏)
    this.detachKeyboardListener();
    this.imeKeyboardHeightVp = 0;
    this.pageVisible = false;
    const wasSending: boolean = this.sending;
    if (wasSending) {
      this.abortController?.abort();
    }
    if (wasSending && this.lastRawSnapshot.length > 0 && this.conversation !== null) {
      const projected: Conversation = this.checkpointRegenerateNodeId.length > 0
        ? checkpointRegenerateConversation(
          this.conversation, this.checkpointRegenerateNodeId, this.lastRawSnapshot)
        : checkpointConversationTail(this.conversation, this.lastRawSnapshot);
      const stopped: Conversation = applyStopGeneration(
        projected, nowIso(), this.stopTargetNodeId(projected)) ?? projected;
      // checkpoint 由仓储侧落库即可;页面已销毁,不再写组件 @State
      this.persistConversationCheckpoint(stopped).catch((e: Error): void => {
        hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'teardown checkpoint failed: %{public}s', e.message);
      });
    } else {
      this.sending = false;
      this.streamingTail = [];
      this.regenTail = null;
    }
    this.abortController = null;
    this.pendingDrainActive = false;
    this.regenNodeId = '';
    if (this.greetTimer !== -1) {
      clearInterval(this.greetTimer);
      this.greetTimer = -1;
    }
    if (this.cursorTimer !== -1) {
      clearInterval(this.cursorTimer);
      this.cursorTimer = -1;
    }
    this.invalidatePicker(true);
    this.unsubActivity();
    if (this.unsubSubAgentLive !== null) {
      this.unsubSubAgentLive();
      this.unsubSubAgentLive = null;
    }
  }

  private refreshQuickMessages(): void {
    const seq: number = ++this.quickMessagesSeq;
    getChatKvStore().get(QUICK_MESSAGES_KEY).then((raw: string | null): void => {
      if (!this.pageAlive || seq !== this.quickMessagesSeq) return;
      this.quickMessages = decodeQuickMessages(raw);
    }).catch((): void => {
      if (this.pageAlive && seq === this.quickMessagesSeq) this.quickMessages = [];
    });
  }

  // 搜索偏好加载(序号守卫:旧的迟到 load 不得覆盖后发起的 load)
  private refreshSearchSettings(): void {
    const seq: number = ++this.searchSettingsSeq;
    loadSearchPrefs(getChatKvStore()).then((s: SearchSettings): void => {
      if (!this.pageAlive || seq !== this.searchSettingsSeq) return;
      this.searchSettings = s;
    }).catch((): void => {});
  }

  onPageShow(): void {
    this.pageVisible = true;
    this.themeTick = Date.now();
    this.attachKeyboardListener();
    // D-070 补:从搜索设置页返回后重载偏好,否则继续用旧工具组/旧开关
    this.refreshSearchSettings();
    if (!this.hasShownOnce) {
      this.hasShownOnce = true;
      return;
    }
    // P4:页面回到前台/从子页返回时刷新模型/上下文/显示设置快照
    //   (onPageShow 每次入页触发,与 aboutToAppear 同异步 helper;pageAlive 守卫防离页写 UI)
    describeChatModel().then((label: string): void => {
      if (this.pageAlive) this.modelLabel = label;
    });
    resolveChatProviderChoice().then((choice: ChatProviderChoice | null): void => {
      if (!this.pageAlive) return;
      // 模型切换后刷新当前会话 context 窗口(计划 §5.6)+ ReasoningPicker 段集/档位
      this.contextWindowTokens = choice !== null ? choice.model.contextWindowTokens : null;
      this.refreshReasoningPicker(choice);
    });
    loadDisplaySetting(getChatKvStore()).then((setting: DisplaySetting): void => {
      if (!this.pageAlive) return;
      this.userNickname = setting.userNickname;
      this.userAvatar = setting.userAvatar;
      this.enableAutoScroll = setting.enableAutoScroll;
      this.showMessageJumper = setting.showMessageJumper;
      this.autoCloseThinking = setting.autoCloseThinking;
    });
    // MiniApp:设置页改 enabled 后返回,刷新快照(transform enabled 门;
    //   aboutToAppear 仅首载,Android settingsFlow collect 持续语义的页面级近似)
    loadMiniAppSetting(getChatKvStore()).then((setting: MiniAppSetting): void => {
      if (this.pageAlive) this.miniAppSetting = setting;
    });
    // D-110:压缩/会话可能已变更,刷新足迹(生成中仅更新展示,不打断)
    this.refreshContextCompacts();
  }

  onPageHide(): void {
    this.pageVisible = false;
    this.detachKeyboardListener();
    this.imeKeyboardHeightVp = 0;
  }

  onBackPress(): boolean {
    if (!this.drawerOpen) return false;
    this.closeDrawer();
    return true;
  }

  // ===== P4:IME 键盘跟随(ImeAutoScroller 语义) =====

  private attachKeyboardListener(): void {
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

  private detachKeyboardListener(): void {
    if (!this.imeListenerAttached || this.imeWindow === null) return;
    try {
      this.imeWindow.off('keyboardHeightChange');
    } catch (e) {
      hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'keyboard listener detach failed: %{public}s', String(e));
    }
    this.imeWindow = null;
    this.imeListenerAttached = false;
  }

  // Android ImeAutoScroller.kt:键盘高度增大且应跟随时 scrollBy(delta),
  //   收起(delta<=0)不反向滚;imeKeyboardHeightVp 复位为 0。
  private handleKeyboardHeightChange(heightPx: number): void {
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

  private shouldFollowIme(): boolean {
    if (!this.listReady || !this.enableAutoScroll) return false;
    // PausedForUser 时不接管滚动(Android:followMode != PausedForUser)
    if (this.followMode === 'paused') return false;
    return this.followMode === 'following' || this.isNearBottom();
  }

  // Android ChatInput.hideKeyboardAfterSend(:227-240,延迟 96ms 等布局提交后再关)。
  //   IMEKit stopInputSession(API 9)可关输入会话;无活动会话抛错 → 吞掉。
  //   仅在用户主动发送路径调用(send/sendWithoutAnswer 守卫通过后),不打断排队派发。
  private dismissKeyboardAfterSend(): void {
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

  private isGenerationOperationActive(token: number): boolean {
    return this.pageAlive && token === this.generationToken;
  }

  aboutToAppear(): void {
    this.pageAlive = true;
    this.pageVisible = true;
    this.checkpointRegenerateNodeId = '';
    this.initialJumpSuppressedUntilUserScroll = false;
    this.lastRawSnapshot = [];
    // 空状态问候名(读 KV user_nickname, 缺省空)
    getChatKvStore().get('user_nickname').then((n: string | null): void => {
      if (this.pageAlive) this.greetName = n === null ? '' : n;
    });
    // amber 光标闪烁(525ms)
    if (this.cursorTimer !== -1) clearInterval(this.cursorTimer);
    this.cursorTimer = setInterval((): void => {
      if (this.pageAlive) this.cursorOn = !this.cursorOn;
    }, 525);
    // 1:1 原型空态 hero:问候句 3.2s 淡入淡出轮换
    this.greetTimer = setInterval((): void => {
      animateTo({ duration: 260, curve: Curve.EaseOut, onFinish: (): void => {
        this.greetIdx = (this.greetIdx + 1) % GREETINGS.length;
        animateTo({ duration: 260, curve: Curve.EaseOut }, (): void => { this.greetOpacity = 1; });
      } }, (): void => { this.greetOpacity = 0; });
    }, 3200);
    // 每次进入(含从选择器返回)刷新模型标签
    this.refreshQuickMessages();
    describeChatModel().then((label: string): void => {
      if (this.pageAlive && this.pageVisible) this.modelLabel = label;
    });    // D-110:上下文窗口快照(ModelRegistry.MODEL_CONTEXT_WINDOW 注册表查询
    //   = P1 登记;取持久化 model.contextWindowTokens = Android 未知模型
    //   回退路径 :110-111)
    resolveChatProviderChoice().then((choice: ChatProviderChoice | null): void => {
      if (!this.pageAlive || !this.pageVisible) return;
      this.contextWindowTokens = choice !== null ? choice.model.contextWindowTokens : null;
      // M2.1:同步装配 ReasoningPicker 段集 + 当前档位
      this.refreshReasoningPicker(choice);
    });
    // D-070:搜索 SDK 运行时装配(幂等)+ 搜索偏好加载(SearchTools 工具组接线)
    ensureSearchSdk();
    this.refreshSearchSettings();
    // D-131:AgentTaskTools 水合(异步单例就绪后入目录;ChatService.kt:2299 无条件)
    getAgentTaskTools().then((tools: AgentTool[]): void => {
      if (!this.pageAlive) return;
      this.agentTaskToolsCache = tools;
    });
    // D-130:cron 四工具水合(异步单例就绪后入目录;LocalTools.kt:181 位)
    getAgentCronTools().then((tools: AgentTool[]): void => {
      if (!this.pageAlive) return;
      this.agentCronToolsCache = tools;
    });
    // D-071b:OCR 种子加载(providers/ocr_model/ocr_prompt 快照)
    loadOcrSeed(getChatKvStore()).then((seed: OcrSeed): void => {
      if (!this.pageAlive) return;
      this.ocrSeed = seed;
    });
    // D-087:compress 任务模型选择快照(resolveTaskChatModel(compressModelId),
    //   未设 = AUTO → findById 必失 → 回退当前 chat 模型,语义逐字)
    loadTaskModelIds().then((ids): void => {
      if (!this.pageAlive) return;
      resolveTaskChatModelChoice(ids.compressModelId).then((choice): void => {
        if (!this.pageAlive) return;
        this.compressChoice = choice;
        this.taskChoicesLoaded = true;
      });
    });
    // D-094:任务提示词快照(compress 同步位;缺失键 → DEFAULT_*)
    loadTaskPrompts().then((prompts: TaskPrompts): void => {
      if (!this.pageAlive) return;
      this.taskPromptsCache = prompts;
    });
    // D-072:agent_runtime 快照加载(enableTimeReminder 等;缺失/损坏 → 默认 false)
    loadAgentRuntimeSnapshot(getChatKvStore()).then((snap: AgentRuntimeSnapshot): void => {
      if (!this.pageAlive) return;
      this.agentRuntime = snap;
    });
    // MiniApp:miniapp setting 快照(enabled 门;transform 调用时读,
    //   Android ctx.settings.agentRuntime.miniApp 语义)
    loadMiniAppSetting(getChatKvStore()).then((setting: MiniAppSetting): void => {
      if (!this.pageAlive) return;
      this.miniAppSetting = setting;
    });
    // D-120:MCP manager 初始化(settings hydrate → 订阅回放真值,
    //   Android settingsFlow collect 时序等价);assistant mcpServers 白名单
    //   provider 于 runtime 解析后接线(wireMcpAssistant — 会话绑定 assistant,
    //   Android settings.getCurrentAssistant() 的页面级等价,登记偏差)
    initMcpManager().catch((e: Error): void => {
      hilog.error(0x0001, 'Chat.Page', 'initMcpManager failed: %{public}s', e.message);
    });
    // D-122:activityStore.sandboxActivity collect(:501)— StateFlow 首发射语义
    this.unsubActivity = getToolActivityStore().subscribe(
      (a: SandboxActivityUiState | null): void => {
        this.liveActivity = a;
      });
    // D-073:display_setting 快照(placeholder nickname/user + Phase 1 滚动偏好)
    loadDisplaySetting(getChatKvStore()).then((setting: DisplaySetting): void => {
      if (!this.pageAlive || !this.pageVisible) return;
      this.userNickname = setting.userNickname;
      this.userAvatar = setting.userAvatar;
      this.enableAutoScroll = setting.enableAutoScroll;
      this.showMessageJumper = setting.showMessageJumper;
      this.autoCloseThinking = setting.autoCloseThinking;
      if (!this.enableAutoScroll) this.followMode = 'idle';
    });
    // D-075:mode_injections/lorebooks 快照(缺失/损坏 → [])
    loadModeInjections(getChatKvStore()).then((list: PromptInjection[]): void => {
      if (!this.pageAlive) return;
      this.modeInjections = list;
    });
    loadLorebooks(getChatKvStore()).then((list: Lorebook[]): void => {
      if (!this.pageAlive) return;
      this.lorebooks = list;
    });
    const params = router.getParams() as Record<string, string>;
    const convId: string = params ? (params['conversationId'] ?? '') : '';
    // Phase 1:首屏定位参数(最小兼容:initialMessageIndex 数字 index;nodeId 定位行)
    const initialIndexParam: string = params ? (params['initialMessageIndex'] ?? '') : '';
    const initialNodeIdParam: string = params ? (params['nodeId'] ?? '') : '';
    if (convId.length > 0) {
      // Phase 5:默认只加载尾部窗口(长会话首帧只渲染最近 TIMELINE_PAGE_SIZE 节点);
      //   this.conversation 仍经 getById 全量装载 — 生成/checkpoint/上下文引擎
      //   照旧读完整会话,rows 仅承载窗口(全量 invariant 见 ensureFullConversationLoaded)
      getChatRepository().getConversationTailWindow(convId, TIMELINE_PAGE_SIZE)
        .then((window): void => {
          if (!this.pageAlive) return;
          this.timelineLoadState = applyTailWindow(
            this.timelineLoadState,
            window.oldestLoadedIndex, window.nodes.length, window.totalNodeCount);
          this.rows = buildRowsFromNodes(window.nodes);
        })
        .catch((e: Error): void => {
          hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'load tail window failed: %{public}s', e.message);
        })
        .then((): Promise<Conversation | null> => this.ensureFullConversationLoaded(convId))
        .then((full: Conversation | null): void => {
          if (!this.pageAlive) return;
          if (full === null) {
            if (this.rows.length === 0) this.errorMsg = '会话不存在';
            return;
          }
          this.conversation = full;
          if (this.rows.length === 0 && full.messageNodes.length > 0) {
            this.rows = buildRows(full);
            this.timelineLoadState = applyTailWindow(
              this.timelineLoadState, 0, full.messageNodes.length, full.messageNodes.length);
          }
          this.title = full.title.length > 0 ? full.title : '对话';
          this.suggestions = full.chatSuggestions;
          this.refreshContextCompacts();
          // Phase 5:首屏定位使用全量会话解析绝对节点索引，再让窗口层按需前插。
          // 这样 nodeId/initialMessageIndex 指向尾窗之外的旧消息时不会误滚到底部。
          let targetNodeId: string = '';
          let targetNodeIndex: number = -1;
          if (initialNodeIdParam.length > 0) {
            targetNodeIndex = full.messageNodes.findIndex(
              (n: MessageNode): boolean => n.id === initialNodeIdParam);
            if (targetNodeIndex >= 0) targetNodeId = initialNodeIdParam;
          } else if (initialIndexParam.length > 0) {
            const parsedIndex: number = Number(initialIndexParam);
            if (Number.isInteger(parsedIndex) && parsedIndex >= 0 &&
              parsedIndex < full.messageNodes.length) {
              targetNodeIndex = parsedIndex;
              targetNodeId = full.messageNodes[parsedIndex].id;
            }
          }
          if (targetNodeIndex < 0) {
            this.applyInitialScroll(-1);
          } else {
            this.positionToTargetNode(targetNodeId, targetNodeIndex);
          }
          // D-052:恢复持久化队列(session 创建时 load,ChatService.kt:334);
          //   解析失败 → hilog + 空队列(Android Log.w + getOrDefault 语义)
          loadPendingQueue(getChatKvStore(), full.id, undefined, (e: Error): void => {
            hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'load pending queue failed: %{public}s', e.message);
          }).then((msgs: PendingUserMessage[]): void => {
            if (msgs.length === 0) return;
            if (!this.pageAlive) return;
            this.pendingQueue = msgs;
            this.syncPendingRows();
            // launchPendingMessageLoopIfNeeded(ChatService.kt:588-595):
            //   未生成且队列非空 → 恢复派发循环(进程死亡恢复)
            if (!this.sending) this.drainPendingQueue();
          });
        });
    } else {
      // 新对话:支持 router 参数 assistantId 指定助手(D-028)
      const wantAssistantId: string = params ? (params['assistantId'] ?? '') : '';
      getChatAssistants().then((assistants): void => {
        // 加载完成时页面可能已销毁:不再写状态、更不得触发 send()(死页开生成)
        if (!this.pageAlive) return;
        let picked: Assistant = assistants[0];
        for (const a of assistants) {
          if (a.id === wantAssistantId) {
            picked = a;
            break;
          }
        }
        const fresh = makeConversation(newId(), [], { assistantId: picked.id });
        this.conversation = seedConversationWithPresets(fresh, picked);
        // Phase 5:新会话空窗口 = fullyLoaded(rows 直接全量展示)
        this.rows = buildRows(this.conversation);
        this.timelineLoadState = applyTailWindow(
          this.timelineLoadState, 0, this.rows.length, this.rows.length);
        this.suggestions = this.conversation.chatSuggestions;
        // Phase 1:新会话首帧滚底(无目标)
        this.applyInitialScroll(-1);
        // 消费首页传入的 initialText(首页输入框发送);无则显示空状态 hero(不自动发送)
        const initialText: string = params ? (params['initialText'] ?? '') : '';
        if (initialText.length > 0) {
          this.inputText = initialText;
          this.send();
        }
      });
    }
    // P4:IME 键盘高度监听(aboutToDisappear 注销;attach 异步取窗,pageAlive 守卫)
    this.attachKeyboardListener();
  }

  // ===== Phase 2:停止生成收口(Android ChatService.stopGeneration :2408-2455) =====

  // 统一停止入口(输入区停止键 / 沙盒 peek / 沙盒 sheet / 离页):
  //   abort 后不抢先保存 — 由生成完成 .then 对 out 做单次收口
  //   (避免「页面立即保存 + 完成保存」双写重复节点);convertSteerToFollowup
  //   既有逻辑保留。
  private requestStop(): void {
    if (!this.sending) {
      // 非生成中仍清 steer 队列(既有语义)
      this.convertSteerQueue();
      return;
    }
    this.abortController?.abort();
    this.convertSteerQueue();
  }

  private convertSteerQueue(): void {
    const conv2 = convertSteerToFollowup(this.pendingQueue);
    if (conv2.changed) {
      this.pendingQueue = conv2.messages;
      this.syncPendingRows();
      this.persistQueue().catch((e: Error): void => { this.errorMsg = e.message; });
    }
  }

  // assistant regenerate 的目标可能位于会话中间；普通 send/resume 与 user
  // regenerate 的活动结果都落在末节点，保持默认末节点收口语义。
  private stopTargetNodeId(
    conv: Conversation,
    requestedNodeId: string = this.checkpointRegenerateNodeId,
  ): string {
    if (requestedNodeId.length === 0) return '';
    const target = conv.messageNodes.find(
      (node): boolean => node.id === requestedNodeId);
    return target !== undefined && nodeRole(target) === 'assistant'
      ? requestedNodeId
      : '';
  }

  // stop 对活动消息做收口；applyStopGeneration 自身负责无变化早退。
  private finalizeGenerationResult(
    convOut: Conversation,
    stopped: boolean,
    requestedNodeId: string = this.checkpointRegenerateNodeId,
  ): Conversation {
    if (!stopped) return convOut;
    return applyStopGeneration(
      convOut, nowIso(), this.stopTargetNodeId(convOut, requestedNodeId)) ?? convOut;
  }

  // 最终会话始终排在已捕获的 checkpoint 之后保存。生成引擎内部虽已保存 out，
  // 这里的尾写负责保证较旧 checkpoint 永远不能覆盖终态/停止收口态。
  private queueFinalConversationSave(finalized: Conversation): void {
    const tail: Promise<void> = this.checkpointTail
      .then((): Promise<void> => getChatRepository().save(finalized))
      .catch((e: Error): void => {
        hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'generation final save failed: %{public}s', e.message);
      });
    this.checkpointTail = tail;
  }

  private finishGenerationCommon(
    convOut: Conversation,
    stopped: boolean,
    requestedNodeId: string = '',
  ): void {
    this.sending = false;
    this.abortController = null;
    this.streamingTail = [];
    this.regenTail = null;
    this.regenNodeId = '';
    this.lastRawSnapshot = [];
    this.lastCheckpointAtMs = 0;
    this.lastCheckpointLen = 0;
    const finalized: Conversation = this.finalizeGenerationResult(
      convOut, stopped, requestedNodeId);
    this.checkpointRegenerateNodeId = '';
    this.queueFinalConversationSave(finalized);
    const previousConversation: Conversation | null = this.conversation;
    this.conversation = finalized;
    // 数据保持全量，展示层按当前窗口同步，避免长会话生成结束时全量展开。
    this.refreshRowsAfterConversationChange(previousConversation, finalized);
    this.suggestions = finalized.chatSuggestions;
    this.handleGenerateEnd(this.atBottomApprox);
    if (!this.userTouchActive) this.scrollToBottom();
  }

  // 页面已经离开时不再写 UI，但生成 promise 仍负责把引擎最终结果排到
  // checkpoint 后面，避免 tool-loop 自身的部分保存覆盖离页收口快照。
  private finishInactiveGeneration(
    convOut: Conversation,
    stopped: boolean,
    requestedNodeId: string = '',
  ): void {
    const finalized: Conversation = this.finalizeGenerationResult(
      convOut, stopped, requestedNodeId);
    this.queueFinalConversationSave(finalized);
  }

  // ===== Phase 5:历史分页窗口加载(纯逻辑在 chat-domain/timeline_load) =====

  // 会话变化后的显示层同步：生成/追加仍使用全量 Conversation，但未加载完历史时
  // rows 继续保持窗口，避免第一次发送把长会话瞬间展开成数千个 ArkUI 子树。
  private refreshRowsAfterConversationChange(
    previous: Conversation | null,
    next: Conversation,
  ): void {
    const state: ConversationTimelineLoadState = this.timelineLoadState;
    if (!state.initialized || state.fullyLoaded) {
      this.rows = buildRows(next);
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
    this.rows = buildRowsFromNodes(next.messageNodes.slice(start, start + count));
    this.timelineLoadState = applyTailWindow(
      state, start, this.rows.length, next.messageNodes.length);
  }

  // 全量 invariant:生成/checkpoint/上下文引擎必须拿到完整会话。
  //   若当前窗口非全量(未初始化或未到最旧),经 getById 重新装载全量会话,
  //   返回全量 Conversation(供调用方写入 this.conversation);窗口全量时不重载。
  //   目标会话已删除 → null(窗口为空时上浮「会话不存在」)。
  private ensureFullConversationLoaded(convId: string): Promise<Conversation | null> {
    const st: ConversationTimelineLoadState = this.timelineLoadState;
    if (st.initialized && st.fullyLoaded && this.conversation !== null
      && this.conversation.id === convId) {
      return Promise.resolve(this.conversation);
    }
    return getChatRepository().getById(convId).then((full: Conversation | null): Conversation | null => {
      if (!this.pageAlive) return null;
      this.conversation = full;
      return full;
    });
  }

  // 待生成/checkpoint/上下文前,确认窗口已全量(生成只能发生在窗口覆盖全部节点后 —
  //   首屏默认尾窗 50,加载更早历史期间进入生成 → 先补全再开跑)
  private ensureFullConversationForMutation(): Promise<void> {
    const st: ConversationTimelineLoadState = this.timelineLoadState;
    if (st.initialized && st.fullyLoaded) return Promise.resolve();
    if (this.conversation === null) return Promise.resolve();
    const convId: string = this.conversation.id;
    const full: Promise<Conversation | null> = this.ensureFullConversationLoaded(convId);
    return full.then((loaded: Conversation | null): void => {
      if (loaded === null) return;
      // 生成与持久化需要全量数据，但展示窗口保持当前范围；历史仍由 onReachStart 展开。
    });
  }

  // 向上滚动触发更早历史加载(onReachStart)。防重入门在纯逻辑
  //   (canLoadOlder/beginLoadOlder),页面侧只负责把历史页前插 rows。
  private loadOlderTimeline(): void {
    const st: ConversationTimelineLoadState = this.timelineLoadState;
    if (!canLoadOlder(st) || this.prefetching) return;
    const state: ConversationTimelineLoadState = beginLoadOlder(st);
    this.timelineLoadState = state;
    if (this.conversation === null) return;
    const convId: string = this.conversation.id;
    const offset: number = nextOlderPageOffset(state, TIMELINE_PAGE_SIZE);
    const oldPlaceholderCount: number = st.fullyLoaded ? 0 : 1;
    const firstRowIndex: number = Math.max(
      0, Math.min(this.rows.length - 1, this.firstVisibleListIndex - oldPlaceholderCount));
    const anchorNodeId: string = this.rows.length > 0 ? this.rows[firstRowIndex].nodeId : '';
    let anchorY: number = 0;
    let anchorMeasured: boolean = false;
    if (this.listReady && anchorNodeId.length > 0) {
      try {
        anchorY = this.scroller.getItemRect(firstRowIndex + oldPlaceholderCount).y;
        anchorMeasured = true;
      } catch (_e) {
        anchorMeasured = false;
      }
    }
    this.prefetching = true;
    getChatRepository().getConversationNodePage(convId, offset, TIMELINE_PAGE_SIZE)
      .then((page): void => {
        if (!this.pageAlive) return;
        const older: MsgRow[] = buildRowsFromNodes(page.nodes);
        const merged: MsgRow[] = mergeOlderNodes(this.rows, older);
        const added: number = merged.length - this.rows.length;
        this.rows = merged;
        this.timelineLoadState = applyOlderPage(
          this.timelineLoadState, page.oldestLoadedIndex, added);
        if (added > 0 && anchorMeasured) {
          setTimeout((): void => {
            if (!this.pageAlive || !this.listReady) return;
            const rowIndex: number = this.rows.findIndex(
              (row: MsgRow): boolean => row.nodeId === anchorNodeId);
            if (rowIndex < 0) return;
            const placeholderCount: number = this.timelineLoadState.fullyLoaded ? 0 : 1;
            try {
              const newY: number = this.scroller.getItemRect(rowIndex + placeholderCount).y;
              const deltaVp: number = newY - anchorY;
              if (Math.abs(deltaVp) > 0.5) {
                this.beginProgrammaticScroll();
                this.scroller.scrollBy(0, deltaVp);
              }
            } catch (_e) {
            }
          }, 0);
        }
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
        if (this.pageAlive) this.prefetching = false;
      });
  }

  // 首屏跨界定位:目标节点早于当前窗口起点 → 追加历史页直至覆盖,再按 nodeId
  //   现算行索引定位(前插不偏移);超过 TIMELINE_MAX_PREFETCH_PAGES 页后降级滚底。
  private positionToTargetNode(targetNodeId: string, targetNodeIndex: number): void {
    const st: ConversationTimelineLoadState = this.timelineLoadState;
    if (st.fullyLoaded || coversIndex(st, targetNodeIndex)) {
      const row: number = this.rows.findIndex((r: MsgRow): boolean => r.nodeId === targetNodeId);
      this.applyInitialScroll(row >= 0 ? row : -1);
      return;
    }
    this.prefetching = true;
    this.prefetchHistoryUntilCovered(targetNodeId, targetNodeIndex, 0);
  }

  private prefetchHistoryUntilCovered(
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
    getChatRepository().getConversationNodePage(convId, offset, pageSize)
      .then((page): void => {
        if (!this.pageAlive) return;
        const older: MsgRow[] = buildRowsFromNodes(page.nodes);
        const merged: MsgRow[] = mergeOlderNodes(this.rows, older);
        const added: number = merged.length - this.rows.length;
        this.rows = merged;
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

  // ===== Phase 1:滚动跟随 helper(纯逻辑在 chat-domain/timeline_follow) =====

  private activeListItemCount(): number {
    // Phase 5:历史加载占位 item 计入(未全量时 List 顶部多一项,影响末端几何)
    const placeholderCount: number = this.timelineLoadState.fullyLoaded ? 0 : 1;
    return this.rows.length + this.streamingTail.length + (this.regenTail !== null ? 1 : 0) +
      (this.sending ? 1 : 0) + placeholderCount;
  }

  private isGenerationActive(): boolean {
    return this.sending || this.streamingTail.length > 0 || this.regenTail !== null;
  }

  // List 总高在 API 12 没有公开稳定接口。最后一项可见时，getItemRect 返回其
  //   相对 List 的几何位置；用 contentBottom 与 viewportHeight 的差恢复 Android
  //   isAtTimelineBottom(24vp) 语义。几何不可取时只退回精确 isAtEnd。
  private isNearBottom(): boolean {
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

  private beginProgrammaticScroll(): void {
    const token: number = ++this.programmaticScrollToken;
    this.programmaticScrollPending = true;
    // Idle/onScrollStop closes this attribution once the multi-frame scroll settles;
    // the timer only covers SDKs that omit those callbacks.
    setTimeout((): void => {
      if (this.programmaticScrollToken === token) this.programmaticScrollPending = false;
    }, 250);
  }

  private followStreamingUpdate(): void {
    this.handleStreamUpdate(this.atBottomApprox);
    if (!this.enableAutoScroll ||
      !shouldScrollOnStreamUpdate(this.followMode, this.atBottomApprox)) return;
    this.atBottomApprox = true;
    this.jumpButtonVisible = false;
    this.beginProgrammaticScroll();
    this.scroller.scrollEdge(Edge.Bottom);
  }

  // 用户主动滚动驱动:离开底部 → 暂停;滚回 24vp 范围 → 恢复。
  private handleUserScroll(generating: boolean): void {
    const atEnd: boolean = this.isNearBottom();
    this.initialJumpSuppressedUntilUserScroll = false;
    this.followMode = transitionOnUserScroll(this.followMode, atEnd, generating);
    this.atBottomApprox = atEnd;
    this.jumpButtonVisible = !atEnd && this.followMode !== 'following';
  }

  private handleStreamUpdate(atEnd: boolean): void {
    if (!this.enableAutoScroll) return;
    this.followMode = transitionOnStreamUpdate(this.followMode, atEnd);
    this.jumpButtonVisible = this.followMode === 'paused' &&
      !this.initialJumpSuppressedUntilUserScroll;
  }

  private handleGenerateEnd(atEnd: boolean): void {
    const shouldSettleAfterTouch: boolean = this.userTouchActive && atEnd &&
      this.followMode !== 'paused';
    this.followMode = transitionOnGenerateEnd(this.followMode);
    this.atBottomApprox = atEnd;
    this.generationEndScrollPending = shouldSettleAfterTouch;
    this.jumpButtonVisible = !atEnd &&
      !this.initialJumpSuppressedUntilUserScroll;
  }

  private reconcileAtEnd(): void {
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

  // 首次打开必须完成定位；后续 append/stream/end 受自动滚动设置与 paused 状态控制。
  private scrollToBottom(initial: boolean = false): void {
    if (!this.listReady) return;
    if (!initial && (!this.enableAutoScroll ||
      !shouldScrollOnAppend(this.followMode, this.atBottomApprox))) return;
    this.atBottomApprox = true;
    this.jumpButtonVisible = false;
    this.beginProgrammaticScroll();
    this.scroller.scrollEdge(Edge.Bottom);
  }

  private jumpToBottom(): void {
    this.followMode = transitionOnJumpToBottom(this.isGenerationActive());
    this.atBottomApprox = true;
    this.jumpButtonVisible = false;
    this.beginProgrammaticScroll();
    this.scroller.scrollEdge(Edge.Bottom);
  }

  // Phase 1:rows 的 nodeId 序列(首屏 nodeId 定位 + 列表滚动范围)
  private rowNodeIds(): string[] {
    const ids: string[] = [];
    for (let i = 0; i < this.rows.length; i++) {
      ids.push(this.rows[i].nodeId);
    }
    return ids;
  }

  // 首帧定位 — rows 赋值与 ArkUI List 重建在同一事件循环内时，延后一帧执行。
  //   token 使连续路由/重载只执行最近一次定位。
  private applyInitialScroll(targetRow: number): void {
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

  private performInitialScroll(): void {
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
    this.beginProgrammaticScroll();
    try {
      // Phase 5:历史加载占位 item 在 List 头部,scrollToIndex 索引需前移占位数
      this.scroller.scrollToIndex(target + (this.timelineLoadState.fullyLoaded ? 0 : 1),
        false, ScrollAlign.START);
    } catch (_e) {
      this.initialJumpSuppressedUntilUserScroll = false;
      this.atBottomApprox = true;
      this.beginProgrammaticScroll();
      this.scroller.scrollEdge(Edge.Bottom);
    }
  }

  // 「+」新对话: 重置为一个全新会话(替换当前页)
  private newChat(): void {
    router.replaceUrl({ url: 'pages/ChatPage' });
  }

  // ===== D-110:ContextUsageIndicator(ChatInputUsage.kt:77-162) =====

  private compactLoadSeq: number = 0;
  private reasoningPickerSeq: number = 0;

  private refreshContextCompacts(): void {
    const conv: Conversation | null = this.conversation;
    if (conv === null) {
      this.contextCompacts = [];
      return;
    }
    // 序号 + 会话身份双守卫:快速切换会话时旧摘要不得写入新会话的足迹
    const seq: number = ++this.compactLoadSeq;
    const convId: string = conv.id;
    getCompactStore().getCompacts(convId)
      .then((cs: ConversationCompact[]): void => {
        if (!this.pageAlive || seq !== this.compactLoadSeq) return;
        if (this.conversation === null || this.conversation.id !== convId) return;
        this.contextCompacts = cs;
      })
      .catch((): void => {});
  }

  private estimatedContextTokens(): number {
    if (this.conversation === null) return 0;
    if (this.contextEstimateConversation !== this.conversation
      || this.contextEstimateCompacts !== this.contextCompacts) {
      this.contextEstimateConversation = this.conversation;
      this.contextEstimateCompacts = this.contextCompacts;
      this.contextEstimateTokens = estimateConversationInputTokens(
        currentMessages(this.conversation), this.contextCompacts);
    }
    return this.contextEstimateTokens;
  }

  // :103-117 — estimate(消费活动 compacts,post-substitution 足迹)+ ratio clamp 0..1
  private contextUsageRatio(): number {
    const w: number | null = this.contextWindowTokens;
    if (w === null || w <= 0 || this.conversation === null) return 0;
    const used: number = this.estimatedContextTokens();
    const r: number = used / w;
    return r < 0 ? 0 : (r > 1 ? 1 : r);
  }

  // Amber Redesign ContextMeter:5 段柱填充数(ceil(ratio*5),0..5)
  private contextFilledBars(): number {
    return Math.ceil(this.contextUsageRatio() * 5);
  }

  // :150-160 — 文案:压缩中 → '正在压缩上下文'(zh :225 逐字);
  //   否则 'Context <used> / <window|-->'
  private contextUsageText(): string {
    if (this.compactActiveCount > 0) return '正在压缩上下文';
    if (this.conversation === null) return 'Context 0 / --';
    const used: number = this.estimatedContextTokens();
    const w: number | null = this.contextWindowTokens;
    return `Context ${formatContextTokens(used)} / ${w !== null ? formatNumberInt(w) : '--'}`;
  }

  // :120-125 — 环色:压缩中 blue;≥0.9 red;≥0.7 amber;否则 muted
  private contextRingColor(): string {
    if (this.compactActiveCount > 0) return STATUS_BLUE;
    const r: number = this.contextUsageRatio();
    if (r >= 0.9) return ERROR;
    if (r >= 0.7) return STATUS_AMBER;
    return INK3;
  }

  private switchBranch(nodeId: string, idx: number): void {
    if (this.conversation === null || this.sending) return;
    // Phase 5 全量 invariant:分支切换读取全量会话(窗口未全量先补全)
    this.ensureFullConversationForMutation().then((): void => {
      if (this.conversation === null || this.sending) return;
      const previous: Conversation = this.conversation;
      const updated: Conversation = selectMessageBranch(previous, nodeId, idx);
      this.conversation = updated;
      this.refreshRowsAfterConversationChange(previous, updated);
      getChatRepository().save(updated)
        .then((): Promise<void> => this.invalidateConvCompacts(updated.id, 'message_branch_changed'))
        .catch((e: Error): void => { this.errorMsg = e.message; });
    });
  }

  // Phase 5 全量 invariant:生成必须基于完整会话(窗口未全量时先补全再开跑;
  //   防首帧尾窗尚未补全就触发重新生成)
  private regenerate(nodeId: string): void {
    if (this.conversation === null || this.sending) return;
    this.ensureFullConversationForMutation().then((): void => {
      if (this.conversation === null || this.sending) return;
      this.runRegenerate(nodeId);
    });
  }

  private runRegenerate(nodeId: string): void {
    if (this.conversation === null || this.sending) return;
    this.sending = true;
    this.errorMsg = '';
    const operationAbort: EntryAbortController = new EntryAbortController();
    this.abortController = operationAbort;
    this.checkpointRegenerateNodeId = nodeId;
    const operationToken: number = ++this.generationToken;
    const operationStopTargetNodeId: string = nodeId;
    const conv: Conversation = this.conversation;
    const holder: RuntimeHolder = { rt: null };
    resolveChatRuntime(conv.assistantId)
      .then(async (rt: ChatRuntime | null): Promise<Conversation> => {
        holder.rt = rt;
        if (rt === null) {
          return Promise.reject(new Error('未配置可用的 Provider/模型'));
        }
        this.wireMcpAssistant(rt);
        this.warnIfToolAbilityMissing(rt);
        // D-132c:水合 subagent 设置 + 工具(buildToolLoop 前;parentToolsProvider 惰性)
        await this.prepareSubAgentTools(conv);
        // D-055:重生成使 compact 失效(ChatService.kt:1097 'message_regenerated'),
        //   先于生成完成,避免 prepareContext 读到过期摘要
        return this.invalidateConvCompacts(conv.id, 'message_regenerated')
          .then((): Promise<Conversation> => runRegenerateAtWithTools(conv, nodeId, {
          assistant: rt.assistant,
          finalTokenBudget: this.finalTokenBudgetOf(rt),
          // D-090:agentSoul 静态块首槽位(GenerationHandler.kt:721-724)
          agentSoul: buildAgentSoulPrompt(this.agentRuntime.agentSoulMarkdown),
          inputTransformers: this.buildInputTransformers(rt),
          // D-071b:视觉兜底门(模型 IMAGE 模态 + 有图才兜底,GenerationHandler.kt:431)
          modelSupportsImageInput: rt.modelSupportsImageInput,
          outputTransformers: [thinkTagTransformer, createEntryBase64ImageTransformer(), createMiniAppOutputTransformer({ enabled: (): boolean => this.miniAppSetting.enabled, repository: this.miniAppRepository() }), regexOutputTransformer], // ChatService.kt:179-184 序(MiniAppOutput 3 位) 依赖 this.miniAppRepository()
          provider: asChatStreamProvider(rt.api, () => rt.params),
          store: getChatRepository(),
          contextMessageSize: rt.contextMessageSize,
          // D-055:压缩引擎接管上下文(同 send 路径)
          prepareContextMessages: this.buildPrepareContext(conv, rt),
          // D-077b:recentChats 动态块(每次组装重取)
          // D-085b:记忆召回块(动态序最前,GenerationHandler.kt:727)
          memorySystemBlocks: (): Promise<string[]> => this.loadMemorySystemBlocks(),
          extraSystemBlocks: (): Promise<string[]> => this.loadExtraSystemBlocks(),
          abortSignal: operationAbort.signal,
          // D-115:GenerationHandler.kt:357/475/621 — retrySetting =
          //   settings.agentRuntime.generationRetry(PreferencesStore.kt:181 持久化)
          retrySetting: makeGenerationRetrySetting({
            enabled: this.agentRuntime.generationRetry.enabled,
            maxRetries: this.agentRuntime.generationRetry.maxRetries,
          }),
          // D-050:重试状态(send 路径同语义)
          onRetryStatus: (status: string | null): void => {
            if (this.isGenerationOperationActive(operationToken)) {
              this.retryStatus = status ?? '';
            }
          },
          // D-041:流式预览 — 快照末条即活动流式消息(assistant 路径占位
          //   合并/user 路径尾部追加,均为末条);visual transforms 与 send 一致
          onUpdate: (msgs: UIMessage[]): void => {
            if (!this.isGenerationOperationActive(operationToken)
              || msgs.length === 0 || holder.rt === null) return;
            const ctx: TransformerContext = { assistant: holder.rt.assistant };
            const transformed: UIMessage[] = applyVisualTransformersStreamingTail(
              msgs, [thinkTagTransformer, regexOutputTransformer], ctx); // MiniApp:仅 ThinkTag/Regex 实现 visualTransformTail(Android 同);MiniAppOutput 无 visual 变换,流式期恒等 → 不加
            this.regenNodeId = nodeId;
            this.regenTail = transformed[transformed.length - 1];
            this.followStreamingUpdate();
          },
          // Phase 2:raw 快照捕获(与 send 路径同接线)
          onRawFlushSnapshot: (msgs: UIMessage[]): void => {
            if (!this.isGenerationOperationActive(operationToken)) return;
            this.lastRawSnapshot = msgs;
            this.captureStreamCheckpoint(msgs);
          },
        }, this.buildToolLoop(rt, conv)));
      })
      .then((out: Conversation): void => {
        const stopped: boolean = operationAbort.signal.aborted;
        if (!this.isGenerationOperationActive(operationToken)) {
          this.finishInactiveGeneration(out, stopped, operationStopTargetNodeId);
          return;
        }
        this.finishGenerationCommon(out, stopped, operationStopTargetNodeId);
        this.refreshContextCompacts(); // D-110:压缩可能已写新摘要,刷新足迹
        if (holder.rt !== null) {
          this.maybeAutoTitle(this.conversation as Conversation);
          this.maybeSuggestions(this.conversation as Conversation, holder.rt);
          this.maybeMemoryExtraction(this.conversation as Conversation, holder.rt);
        }
        this.drainPendingQueue();
      })
      .catch((e: Error): void => {
        if (!this.isGenerationOperationActive(operationToken)) return;
        this.errorMsg = this.compactErrorText(e);
        this.sending = false;
        this.refreshContextCompacts(); // D-110:压缩可能已写新摘要,刷新足迹
        this.abortController = null;
        this.regenTail = null;
        this.regenNodeId = '';
        this.lastRawSnapshot = [];
        this.checkpointRegenerateNodeId = '';
        getChatRepository().getById(conv.id).then((saved: Conversation | null): void => {
          if (saved !== null) {
            const previous: Conversation | null = this.conversation;
            this.conversation = saved;
            this.refreshRowsAfterConversationChange(previous, saved);
            this.suggestions = saved.chatSuggestions;
          }
          this.drainPendingQueue();
        }).catch((reloadError: Error): void => {
          hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'regenerate reload failed: %{public}s', reloadError.message);
          this.drainPendingQueue();
        });
      });
  }

  // D-085d:记忆抽取(ChatService.kt:1494-1499 — 生成完成且无 pending/未执行
  //   工具时 appScope.launch 非阻塞抽取);门/去抖/配额/事件在 HAR 编排逐字;
  //   偏差:worker 模型 = chat 模型(任务模型设置未移植,D-025 同类登记);
  //   locale = systemLanguage()(Locale.getDefault().displayName 无直接等价)
  private maybeMemoryExtraction(conv: Conversation, rt: ChatRuntime): void {
    if (conversationHasPendingOrUnexecutedTools(conv)) return;
    const ops = getMemoryExtractionStoreOps();
    // D-087:链解析缓存(resolveWorkerModel 与 generateText 同一次编排共用,
    //   对齐 Android 单次 resolveMemoryModel 后模型+provider 配套使用)
    let extractionChoicePromise: Promise<ChatProviderChoice | null> | null = null;
    const resolveExtractionChoice = (): Promise<ChatProviderChoice | null> => {
      if (extractionChoicePromise === null) {
        extractionChoicePromise = (async (): Promise<ChatProviderChoice | null> => {
          const ids = await loadTaskModelIds();
          const chatUuid: string = await currentChatModelUuid();
          return resolveTaskModelChain(memoryWorkerModelCandidates(
            this.agentRuntime.memoryWorker, ids.compressModelId, chatUuid));
        })();
      }
      return extractionChoicePromise;
    };
    runMemoryExtraction(conv, {
      worker: this.agentRuntime.memoryWorker,
      locale: systemLanguage(),
      // D-087:worker 任务模型链(MemoryExtractor.kt:185-195)— modelId≠AUTO →
      //   followCompress → chat;外层 ?: chat;解析结果在闭包内缓存一次
      resolveWorkerModel: async (): Promise<MemoryWorkerModelResolution> => {
        const choice: ChatProviderChoice | null = await resolveExtractionChoice();
        return choice === null
          ? { kind: 'no_model' }
          : { kind: 'ok', modelId: choice.model.modelId };
      },
      generateText: async (prompt: string): Promise<string> => {
        const choice: ChatProviderChoice | null = await resolveExtractionChoice();
        if (choice === null) throw new Error('no model');
        const api: OpenAIChatApi = buildProviderApi(choice.provider, {});
        // TextGenerationParams(model = model) 默认参(MemoryExtractor.kt:121)
        const chunk: MessageChunk = await api.generateText(
          [makeUserMessage(prompt)],
          makeTextGenerationParams({ model: toChatModel(choice.model) }),
        );
        const msg = chunk.choices.length > 0 ? chunk.choices[0].message : null;
        return msg !== null ? toText(msg) : '';
      },
      getAllActiveRecords: ops.getAllActiveRecords,
      addCandidates: ops.addCandidates,
      addCandidate: ops.addCandidate,
      addMemory: ops.addMemory,
      addEvent: ops.addEvent,
      countEventsSince: ops.countEventsSince,
    }).catch((e: Error): void => {
      // 编排体内错误已吞为 EXTRACTION_FAILED 事件;此处仅存储层故障 — 记录不打扰
      hilog.error(0x0000, 'AmberChat', 'memory extraction failed: %{public}s', e.message);
    });
  }

  private sameConversationTail(a: Conversation, b: Conversation): boolean {
    return JSON.stringify(currentMessages(a)) === JSON.stringify(currentMessages(b));
  }

  // 自动命名(D-025):title 空白时非阻塞调用(对齐 Android 独立 launch);
  // 生成使用快照；落库前重载最新会话，只合并 title，避免覆盖新消息。
  private async maybeAutoTitle(conv: Conversation, force: boolean = false): Promise<void> {
    if (!force && conv.title.trim().length > 0) return;
    // D-094:settings.titlePrompt(ChatPrefs.kt:81)→ runAutoTitle deps.template
    //   (auto_title.ts:60;调用时新取 = settingsFlow.value 语义)
    const prompts: TaskPrompts = await loadTaskPrompts();
    const snapshot: Conversation = conv;
    return runAutoTitle(snapshot, {
      // D-087:title 任务模型(AiAuxiliaryGenerator.kt:41)—
      //   resolveTaskChatModel(titleModelId) ?: return(无模型静默跳过);
      //   params = model + reasoning OFF + model.customHeaders/customBodies(:55-59,
      //   不带 chat 温度/topP/maxTokens)
      generateText: async (prompt: string): Promise<string> => {
        const ids = await loadTaskModelIds();
        const choice: ChatProviderChoice | null =
          await resolveTaskChatModelChoice(ids.titleModelId);
        if (choice === null) throw new Error('no title model');
        const headers: Record<string, string> = {};
        for (const h of choice.model.customHeaders) {
          const name: string = h.name.trim();
          if (name.length === 0) continue;
          headers[name] = h.value.trim();
        }
        const api: OpenAIChatApi = buildProviderApi(choice.provider, headers);
        const chunk: MessageChunk = await api.generateText(
          [makeUserMessage(prompt)],
          makeTextGenerationParams({
            model: toChatModel(choice.model),
            reasoningLevel: 'off',
            customBody: choice.model.customBodies,
          }),
        );
        const msg = chunk.choices.length > 0 ? chunk.choices[0].message : null;
        return msg !== null ? toText(msg) : '';
      },
      // Auxiliary generation must not persist its stale snapshot directly.
      store: { save: (_ignored: Conversation): Promise<void> => Promise.resolve() },
      locale: '简体中文',
      template: prompts.titlePrompt,
    }, force)
      .then(async (titled: Conversation): Promise<void> => {
        const latest: Conversation | null = await getChatRepository().getById(titled.id);
        if (latest === null || latest.title !== snapshot.title
          || !this.sameConversationTail(snapshot, latest)) return;
        const merged: Conversation = patchConversation(latest, { title: titled.title });
        await getChatRepository().save(merged);
        if (this.pageAlive && this.conversation !== null && this.conversation.id === merged.id) {
          this.conversation = merged;
          this.title = merged.title.length > 0 ? merged.title : this.title;
        }
      })
      .catch((e: Error): void => {
        if (this.pageAlive) this.errorMsg = `自动命名失败:${e.message}`;
      });
  }

  // 会话建议(D-026):每轮生成完成后非阻塞刷新;失败 hilog 记录(非静默,
  // 不上浮 UI — Android 对建议失败也不打扰用户,仅 printStackTrace)
  private async maybeSuggestions(conv: Conversation, rt: ChatRuntime): Promise<void> {
    const snapshot: Conversation = conv;
    // D-094:settings.suggestionPrompt(ChatPrefs.kt:82)→ runSuggestion deps.template
    //   (suggestion.ts:57;调用时新取)
    const prompts: TaskPrompts = await loadTaskPrompts();
    runSuggestion(conv, {
      // D-087:suggestion 任务模型(AiAuxiliaryGenerator.kt:87)— 同 title 链
      generateText: async (prompt: string): Promise<string> => {
        const ids = await loadTaskModelIds();
        const choice: ChatProviderChoice | null =
          await resolveTaskChatModelChoice(ids.suggestionModelId);
        if (choice === null) throw new Error('no suggestion model');
        const headers: Record<string, string> = {};
        for (const h of choice.model.customHeaders) {
          const name: string = h.name.trim();
          if (name.length === 0) continue;
          headers[name] = h.value.trim();
        }
        const api: OpenAIChatApi = buildProviderApi(choice.provider, headers);
        const chunk: MessageChunk = await api.generateText(
          [makeUserMessage(prompt)],
          makeTextGenerationParams({
            model: toChatModel(choice.model),
            reasoningLevel: 'off',
            customBody: choice.model.customBodies,
          }),
        );
        const msg = chunk.choices.length > 0 ? chunk.choices[0].message : null;
        return msg !== null ? toText(msg) : '';
      },
      store: { save: (_ignored: Conversation): Promise<void> => Promise.resolve() },
      locale: '简体中文',
      template: prompts.suggestionPrompt,
    })
      .then(async (suggested: Conversation): Promise<void> => {
        const latest: Conversation | null = await getChatRepository().getById(suggested.id);
        if (latest === null || !this.sameConversationTail(snapshot, latest)) return;
        const merged: Conversation = patchConversation(latest, {
          chatSuggestions: suggested.chatSuggestions,
        });
        await getChatRepository().save(merged);
        if (this.pageAlive && this.conversation !== null && this.conversation.id === merged.id) {
          this.conversation = merged;
          this.suggestions = merged.chatSuggestions;
        }
      })
      .catch((e: Error): void => {
        hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'suggestion failed: %{public}s', e.message);
      });
  }

  // 手动改名(D-034):Android ChatVM.updateTitle 语义(copy title + save)
  private startEditTitle(): void {
    if (this.conversation === null) return;
    this.titleInput = this.conversation.title;
    this.editingTitle = true;
  }

  private saveTitle(): void {
    if (this.conversation === null) return;
    const updated: Conversation = patchConversation(this.conversation, { title: this.titleInput.trim() });
    this.conversation = updated;
    this.title = updated.title.length > 0 ? updated.title : '对话';
    this.editingTitle = false;
    getChatRepository().save(updated)
      .catch((e: Error): void => { this.errorMsg = e.message; });
  }

  // 会话分叉(D-033):复制 0..目标节点为新会话并跳转
  private forkAt(row: MsgRow): void {
    if (this.conversation === null || this.sending) return;
    const sourceConvId: string = this.conversation.id;
    const fork: Conversation = forkConversation(this.conversation, row.message.id);
    // D-055:分叉复制有效 compact(ChatService.kt:2120 copyValidCompactsToConversation);
    //   失败 hilog 非静默,不阻断分叉
    copyValidCompactsToConversation(
      getCompactStore(), sourceConvId, fork, (): string => newId(), (): number => Date.now())
      .catch((e: Error): void => {
        hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'fork compact copy failed: %{public}s', e.message);
      });
    getChatRepository().save(fork).then((): void => {
      const params: Record<string, string> = {};
      params['conversationId'] = fork.id;
      router.pushUrl({ url: 'pages/ChatPage', params: params });
    }).catch((e: Error): void => { this.errorMsg = e.message; });
  }

  // 编辑消息(D-031):填入输入框进入编辑态;发送时走「编辑并重发」
  private startEditMessage(row: MsgRow): void {
    if (this.conversation === null || this.sending) return;
    this.editingNodeId = row.nodeId;
    this.editingMessageId = row.message.id;
    this.inputText = toText(row.message);
  }

  private cancelEdit(): void {
    this.editingNodeId = '';
    this.editingMessageId = '';
    this.inputText = '';
  }

  // 图片选择(D-038):PhotoViewPicker → data: URL → 待发预览;
  //   D-079b:物化时压缩(gif 直通,其余 JPEG q85 长边 ≤2048 + EXIF 归一),
  //   对齐 Android FileEncoder.compressAndEncode 载荷;
  //   读取失败逐张告警(非静默),Android 同语义(FileEncoder 失败即抛)
  private pickImages(): void {
    if (this.sending || this.materializingImages) return;
    const token: number = ++this.pickerToken;
    const options: photoAccessHelper.PhotoSelectOptions = new photoAccessHelper.PhotoSelectOptions();
    options.MIMEType = photoAccessHelper.PhotoViewMIMETypes.IMAGE_TYPE;
    options.maxSelectNumber = 3;
    const picker: photoAccessHelper.PhotoViewPicker = new photoAccessHelper.PhotoViewPicker();
    picker.select(options).then(async (result: photoAccessHelper.PhotoSelectResult): Promise<void> => {
      if (token !== this.pickerToken) return;
      const uris: string[] = result.photoUris;
      this.materializingImages = true;
      try {
        const dataUrls: string[] = await Promise.all(uris.map(
          (uri: string): Promise<string> => compressImageAsDataUrl(uri)));
        if (token === this.pickerToken) {
          this.pendingImages = [...this.pendingImages, ...dataUrls];
        }
      } catch (e) {
        if (token === this.pickerToken) {
          const err: Error = e as Error;
          hilog.warn(0x0001, 'ChatPage', `图片读取失败: ${err.message}`);
          this.errorMsg = `图片读取失败: ${err.message}`;
        }
      } finally {
        if (token === this.pickerToken) this.materializingImages = false;
      }
    }).catch((e: Error): void => {
      if (token === this.pickerToken) {
        hilog.info(0x0001, 'ChatPage', `图片选择结束: ${e.message}`);
        this.materializingImages = false;
      }
    });
  }

  private invalidatePicker(clearImages: boolean = false): void {
    this.pickerToken++;
    this.materializingImages = false;
    if (clearImages) this.pendingImages = [];
  }

  private removePendingImage(index: number): void {
    const next: string[] = [...this.pendingImages];
    next.splice(index, 1);
    this.pendingImages = next;
  }

  // 编辑并重发:editMessage(新分支)→ 持久化 → runRegenerateAt(user 节点截断续跑)
  private sendEdit(text: string): void {
    if (this.conversation === null || this.sending) return;
    // Phase 5 全量 invariant:编辑前确保完整会话(窗口未全量先补全)
    this.ensureFullConversationForMutation().then((): void => {
      if (this.conversation === null || this.sending) return;
      this.runSendEdit(text);
    }).catch((e: Error): void => {
      this.errorMsg = e.message;
    });
  }

  private runSendEdit(text: string): void {
    if (this.conversation === null || this.sending) return;
    const conv: Conversation = this.conversation;
    const nodeId: string = this.editingNodeId;
    const updated: Conversation = editMessage(conv, this.editingMessageId, makeUserMessage(text).parts);
    this.conversation = updated;
    this.refreshRowsAfterConversationChange(conv, updated);
    this.editingNodeId = '';
    this.editingMessageId = '';
    this.sending = true;
    this.errorMsg = '';
    this.invalidateConvCompacts(updated.id, 'message_edited')
      .then((): Promise<void> => getChatRepository().save(updated))
      .then((): void => {
        this.sending = false;
        this.regenerate(nodeId);
      })
      .catch((e: Error): void => {
        this.sending = false;
        this.errorMsg = this.compactErrorText(e);
      });
  }

  // 删除消息(D-029):长按 → 确认弹窗;生成中不提供入口(Android 同语义:
  // ChatVM.showDeleteBlockedWhileGeneratingError)
  private confirmDeleteMessage(row: MsgRow): void {
    if (this.conversation === null || this.sending) return;
    AlertDialog.show({
      title: '删除消息',
      message: '该消息将被删除,且不可恢复。',
      primaryButton: {
        value: '取消',
        action: (): void => {},
      },
      secondaryButton: {
        value: '删除',
        fontColor: ERROR,
        action: (): void => {
          if (this.conversation === null) return;
          // Phase 5 全量 invariant:删除读取全量会话(窗口未全量先补全)
          this.ensureFullConversationForMutation().then((): void => {
            if (this.conversation === null) return;
            const previous: Conversation = this.conversation;
            const updated: Conversation = deleteMessage(previous, row.message.id, false);
            this.conversation = updated;
            this.refreshRowsAfterConversationChange(previous, updated);
            getChatRepository().save(updated)
              .then((): Promise<void> => this.invalidateConvCompacts(updated.id, 'message_deleted'))
              .catch((e: Error): void => { this.errorMsg = e.message; });
          });
        },
      },
    });
  }

  // ===== D-048 发送排队(Android ChatService.sendMessage:687-732 / launchPendingMessageLoop:801-867) =====

  private syncPendingRows(): void {
    this.pendingRows = this.pendingQueue.map(
      (m: PendingUserMessage): PendingChipRow => ({ id: m.id, preview: pendingPreviewText(m) }));
  }

  // D-052:每次队列变更 → KV 持久化(Android ConversationSession 通知点
  //   :128-231 + durable persist :712/:1046);失败 hilog 非静默
  private persistQueue(): Promise<void> {
    if (this.conversation === null) return Promise.resolve();
    const convId: string = this.conversation.id;
    return persistPendingQueue(getChatKvStore(), convId, this.pendingQueue).catch((e: Error): void => {
      hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'persist pending queue failed: %{public}s', e.message);
      throw e;
    });
  }

  private persistQueueWithEvent(event: string, messageId: string,
    detail?: string): Promise<void> {
    if (this.conversation === null) return Promise.resolve();
    return persistPendingQueueWithEvent(getChatKvStore(), this.conversation.id,
      this.pendingQueue, { event, messageId, detail }).catch((e: Error): void => {
      hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'pending queue mutation failed: %{public}s', e.message);
      throw e;
    });
  }

  // D-052:审计事件(recordEvent:62-87;entry 现有交互子集 enqueue/dequeue/cancel)
  private auditQueueEvent(event: string, messageId: string, detail?: string): Promise<void> {
    if (this.conversation === null) return Promise.resolve();
    const convId: string = this.conversation.id;
    return recordPendingQueueEvent(getChatKvStore(), convId, { event, messageId, detail })
      .catch((e: Error): void => {
        hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'pending queue audit failed: %{public}s', e.message);
        throw e;
      });
  }

  // ===== D-055 上下文压缩引擎接线(ConversationContextEngine.kt:112-286) =====

  // 失效钩子(ChatService.kt:1097/:2082/:2152/:2171;失败 hilog 非静默,不阻断交互)
  private invalidateConvCompacts(convId: string, reason: string): Promise<void> {
    return invalidateCompacts(getCompactStore(), convId, reason)
      .catch((e: Error): void => {
        hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'compact invalidate failed: %{public}s', e.message);
      });
  }

  // prepareContextMessages 闭包:chat_turn 在 append user 后以基线消息回调;
  //   重建 messageNodes 供压缩规划(message id 保持);policy 默认值
  //   keepRecentTurns=8(PreferencesStore.kt:241 ContextCompactionSetting 默认,
  //   设置面未做前硬编码);precompact 异步 launch(engine:173-186)
  // D-087:compact 生成 provider(ConversationContextEngine.kt:408-410/:689)—
  //   压缩任务模型(resolveTaskChatModel(compressModelId),未设=AUTO→chat 模型)
  //   + TextGenerationParams(model) 最小参;快照未加载/无模型 → chat 运行态兜底
  // 发送边界最终预算(D-055 补充):forceRatio × 上下文窗 — 组装完 agentSoul/
  // memory/工具 prompt 等后置 system 块后,chat 域在发送边界再做一次兜底 fit,
  // 否则 prepare 阶段的 fit 不含这些块,超长 prompt 仍会顶出窗口
  private finalTokenBudgetOf(rt: ChatRuntime): number {
    const cc = this.agentRuntime.contextCompaction;
    const window: number = estimateContextWindow(rt.modelContextWindowTokens);
    return Math.max(Math.trunc(window * cc.forceRatio), 4000);
  }

  private compressProvider(rt: ChatRuntime): ChatStreamProvider {
    const choice: ChatProviderChoice | null = this.compressChoice;
    if (choice === null) return asChatStreamProvider(rt.api, () => rt.params);
    const api: OpenAIChatApi = buildProviderApi(choice.provider, {});
    return asChatStreamProvider(
      api, () => makeTextGenerationParams({ model: toChatModel(choice.model) }));
  }

  private buildPrepareContext(conv: Conversation, rt: ChatRuntime):
    (messages: UIMessage[]) => Promise<UIMessage[]> {
    // D-091:settings.agentRuntime.contextCompaction.toCompactPolicy()
    //   (PreferencesStore.kt:247-255 字段直传;快照加载前 = Android 默认值)
    const cc = this.agentRuntime.contextCompaction;
    const policy: CompactPolicy = makeCompactPolicy({
      enabled: cc.enabled,
      notifyOnly: cc.notifyOnly,
      precompactRatio: cc.precompactRatio,
      forceRatio: cc.forceRatio,
      keepRecentTurns: cc.keepRecentTurns,
      maxSummaryTokens: cc.maxSummaryTokens,
    });
    return async (messages: UIMessage[]): Promise<UIMessage[]> => {
      const effConv: Conversation = conversationWithMessagesAsNodes(conv, messages);
      // D-094:settings.compressPrompt(ChatPrefs.kt:84)→ CompactEngineDeps.compressPrompt
      //   (context_engine.ts:485;调用时新取 = settingsFlow.value 语义)
      const prompts: TaskPrompts = await loadTaskPrompts();
      const prepared: PreparedContext = await prepareContext(effConv, policy,
        rt.modelContextWindowTokens, messages, rt.contextMessageSize, {
          provider: this.compressProvider(rt),
          store: getCompactStore(),
          compressPrompt: prompts.compressPrompt,
          launchPrecompact: (run: () => Promise<CompactResult>): void => {
            // D-110:precompact 活动跟踪(engine:182-191 appScope.launch;
            //   PLANNING/COMPACTING → 完成/失败复位)
            this.compactActiveCount = this.compactActiveCount + 1;
            run().catch((e: Error): void => {
              hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'precompact failed: %{public}s', e.message);
            }).then((): void => {
              this.compactActiveCount = Math.max(0, this.compactActiveCount - 1);
            });
          },
          // D-110:force 压缩活动钩子(context_engine onCompactActiveChange)
          onCompactActiveChange: (active: boolean): void => {
            this.compactActiveCount = Math.max(0,
              this.compactActiveCount + (active ? 1 : -1));
          },
        });
      return prepared.messages;
    };
  }

  // 压缩失败统一文案(Android ChatVM 错误映射)
  private compactErrorText(e: Error): string {
    return e instanceof ContextCompactionFailedError
      ? '上下文已满，压缩失败，请配置任务模型'
      : e.message;
  }

  // ===== D-057 工具循环接线(GenerationHandler:161-391 调用面) =====

  // D-132c:Android ChatService.kt:2307 — conversation 存在且 subAgent.enabled 时挂
  //   五件 SubAgentTools。设置水合(复用 PromptConfigStore 唯一 RMW)+ 工具构建为异步,
  //   在 buildToolLoop 前完成;parentToolsProvider 惰性读 baseToolsCache(buildToolLoop
  //   内 step-4 设置)。禁用/无会话 → 空缓存,buildToolLoop 不挂载。
  private async prepareSubAgentTools(conv: Conversation): Promise<void> {
    try {
      const setting = await hydrateSubAgentSetting();
      this.subAgentEnabled = setting.enabled;
      this.subAgentMode = setting.mode;
      if (!setting.enabled) {
        this.subAgentToolsCache = [];
        return;
      }
      const ctx: SubAgentToolsContext = {
        parentConversationId: conv.id,
        parentToolsProvider: (): AgentTool[] => this.baseToolsCache,
      };
      this.subAgentToolsCache = await getSubAgentTools(ctx);
    } catch (e) {
      // 水合失败不阻塞主对话;subagent 工具本轮不挂载(fail-open 主链路)
      const msg: string = e instanceof Error ? e.message : String(e);
      hilog.warn(0x0001, 'Chat.Page', 'prepareSubAgentTools failed: %{public}s', msg);
      this.subAgentEnabled = false;
      this.subAgentToolsCache = [];
    }
  }

  // SubAgent run sheet:打开时订阅 liveText,关闭时取消订阅
  private async openSubAgentSheet(runId: string, tools: UIMessagePartTool[]): Promise<void> {
    this.subAgentSheetRunId = runId;
    this.subAgentSheetTools = tools;
    this.subAgentLiveText = '';
    try {
      const manager = await getSubAgentManager();
      // 订阅实时文本
      this.unsubSubAgentLive = manager.subscribeLiveText(runId, (text: string): void => {
        this.subAgentLiveText = text;
      });
      // 若已结束,从 snapshot 取最终文本
      const snapshot: SubAgentRun | null = manager.snapshot(runId);
      if (snapshot !== null && snapshot.displayText.length > 0) {
        this.subAgentLiveText = snapshot.displayText;
      }
    } catch (e) {
      const msg: string = e instanceof Error ? e.message : String(e);
      hilog.warn(0x0001, 'Chat.Page', 'openSubAgentSheet failed: %{public}s', msg);
    }
    this.subAgentSheetOpen = true;
  }

  private closeSubAgentSheet(): void {
    if (this.unsubSubAgentLive !== null) {
      this.unsubSubAgentLive();
      this.unsubSubAgentLive = null;
    }
    this.subAgentSheetOpen = false;
    this.subAgentSheetRunId = '';
    this.subAgentLiveText = '';
  }

  // Copy sheet
  // 1:1 原型消息区底部 56px mask 渐隐(不拦截触摸)
  @Builder
  BottomFade(h: number): void {
    Column()
      .width('100%')
      .height(h)
      .linearGradient({
        direction: GradientDirection.Bottom,
        colors: [[BG_FADE_START, 0.0], [BG_FADE_END, 1.0]],
      })
      .hitTestBehavior(HitTestMode.None)
  }

  // 收藏消息文本 → FavoriteStore 真实落盘(KV)
  private favoriteText(text: string): void {
    const conv = this.conversation;
    const body: string = text.trim();
    if (conv === null || body.length === 0) return;
    const first: string = body.split('\n')[0];
    this.favSeq++;
    addFavorite({
      // Date.now 同毫秒可碰撞 → 序号后缀保 ForEach key 唯一
      id: `${Date.now()}-${this.favSeq}`,
      title: first.substring(0, 40),
      content: body,
      conversationId: conv.id,
      createdAt: Date.now(),
    }).then((): void => {
      promptAction.showToast({ message: '已收藏', duration: 1200 });
    }).catch((e: Error): void => {
      promptAction.showToast({ message: `收藏失败:${e.message}`, duration: 1800 });
    });
  }

  private openCopySheet(msg: UIMessage): void {
    this.copySheetMessage = msg;
    this.copySheetOpen = true;
  }

  private closeCopySheet(): void {
    this.copySheetOpen = false;
  }

  private openActionsSheet(row: MsgRow): void {
    if (this.sending) return;
    this.actionsSheetRow = row;
    this.actionsSheetOpen = true;
  }

  private closeActionsSheet(): void {
    this.actionsSheetOpen = false;
  }

  private clearActionsSheet(): void {
    this.actionsSheetOpen = false;
    this.actionsSheetRow = null;
  }

  private actionsSheetMessage(): UIMessage {
    return (this.actionsSheetRow as MsgRow).message;
  }

  private actionsSheetIsUser(): boolean {
    return this.actionsSheetRow !== null && this.actionsSheetRow.message.role === 'user';
  }

  private actionsSheetIsLastAssistant(): boolean {
    return this.actionsSheetRow !== null && this.actionsSheetRow.isLastAssistant;
  }

  private copyActionsSheetMessage(): void {
    if (this.actionsSheetRow !== null) this.openCopySheet(this.actionsSheetRow.message);
  }

  private editActionsSheetMessage(): void {
    if (this.actionsSheetRow !== null) this.startEditMessage(this.actionsSheetRow);
  }

  private regenerateActionsSheetMessage(): void {
    if (this.actionsSheetRow !== null) this.regenerate(this.actionsSheetRow.nodeId);
  }

  private forkActionsSheetMessage(): void {
    if (this.actionsSheetRow !== null) this.forkAt(this.actionsSheetRow);
  }

  private speakActionsSheetMessage(): void {
    if (this.actionsSheetRow !== null) speakText(toText(this.actionsSheetRow.message));
  }

  private shareActionsSheetMessage(): void {
    if (this.actionsSheetRow !== null) this.shareMessage(this.actionsSheetRow.message);
  }

  private deleteActionsSheetMessage(): void {
    if (this.actionsSheetRow !== null) this.confirmDeleteMessage(this.actionsSheetRow);
  }

  private shareMessage(message: UIMessage): void {
    const text: string = toText(message).trim();
    if (text.length === 0) {
      promptAction.showToast({ message: '没有可分享的文本', duration: 1200 });
      return;
    }
    try {
      const board: pasteboard.SystemPasteboard = pasteboard.getSystemPasteboard();
      board.setDataSync(pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, text));
      promptAction.showToast({ message: '已复制，可粘贴分享', duration: 1400 });
    } catch (e) {
      this.errorMsg = `分享准备失败: ${String(e)}`;
    }
  }

  private refreshDrawerConversations(text: string = this.drawerSearchText): void {
    this.drawerSearchText = text;
    const query: string = text.trim();
    const request: Promise<ConversationSummary[]> = query.length > 0
      ? getChatRepository().search(query) : getChatRepository().list();
    request.then((items: ConversationSummary[]): void => {
      if (this.pageAlive) this.drawerConversations = items;
    }).catch((e: Error): void => {
      this.errorMsg = e.message;
    });
  }

  private openDrawer(): void {
    this.closeModelMenu();
    this.detachKeyboardListener();
    this.imeKeyboardHeightVp = 0;
    try {
      inputMethod.getController().stopInputSession().catch((_e: Error): void => {});
    } catch (_e) {
    }
    this.refreshDrawerConversations('');
    this.drawerOpen = true;
  }

  private closeDrawer(): void {
    this.drawerOpen = false;
    if (this.pageVisible) this.attachKeyboardListener();
  }

  private openDrawerConversation(id: string): void {
    this.drawerOpen = false;
    if (this.conversation !== null && this.conversation.id === id) return;
    router.replaceUrl({ url: 'pages/ChatPage', params: { conversationId: id } });
  }

  private deleteDrawerConversation(item: ConversationSummary): void {
    const deletingCurrent: boolean = this.conversation !== null && this.conversation.id === item.id;
    if (deletingCurrent && this.isGenerationActive()) {
      this.errorMsg = '生成中无法删除当前会话';
      return;
    }
    AlertDialog.show({
      title: '删除会话',
      message: `确定删除“${item.title.length > 0 ? item.title : '未命名对话'}”吗？此操作不可恢复。`,
      primaryButton: { value: '取消', action: (): void => {} },
      secondaryButton: {
        value: '删除',
        fontColor: ERROR,
        action: (): void => {
          getChatRepository().delete(item.id).then((): void => {
            if (deletingCurrent) {
              this.drawerOpen = false;
              router.replaceUrl({ url: 'pages/ChatPage' });
            } else {
              this.refreshDrawerConversations();
            }
          }).catch((e: Error): void => { this.errorMsg = e.message; });
        },
      },
    });
  }

  private regenerateDrawerTitle(item: ConversationSummary): void {
    getChatRepository().getById(item.id).then((conv: Conversation | null): Promise<void> => {
      if (conv === null) return Promise.resolve();
      return this.maybeAutoTitle(conv, true);
    }).then((): void => {
      this.refreshDrawerConversations();
    }).catch((e: Error): void => { this.errorMsg = e.message; });
  }

  private openDrawerBoard(): void {
    this.drawerOpen = false;
    router.pushUrl({ url: 'pages/BoardPage' });
  }

  private openDrawerApps(): void {
    this.drawerOpen = false;
    router.pushUrl({ url: 'pages/MiniAppListPage' });
  }

  private openDrawerSettings(): void {
    this.drawerOpen = false;
    router.pushUrl({ url: 'pages/SettingPage' });
  }

  private editDrawerNickname(_name: string): void {
    this.drawerOpen = false;
    router.pushUrl({ url: 'pages/ProfilePage' });
  }

  // Tool preview sheet
  private openToolPreview(part: UIMessagePartTool): void {
    this.toolPreviewPart = part;
    this.toolPreviewSheetOpen = true;
  }

  private closeToolPreview(): void {
    this.toolPreviewSheetOpen = false;
  }

  // TopModelMenu:打开/关闭下拉。打开 = 装配 groups(过滤 hasUsableAuth + chat 类型),
  //   复用 buildTopModelMenuGroups 域纯逻辑;关闭 = 仅翻 bool。装配完成后再置 open=true,
  //   避免空 groups 闪烁(KV/Preferences 本地读取,用户感知不到延迟)。
  //   Android ChatPage.kt:985-996 装配 + 992 挂载 的鸿蒙对应。
  //   嵌套 .then(对齐 ChatModelPickerPage 既有装配范式,避开 Promise.all 元组推断)
  // 1:1 原型 menuIn .16s ease-out:开/关都经 animateTo 驱动 transition 进出场
  private closeModelMenu(): void {
    animateTo({ duration: 220, curve: Curve.EaseOut }, (): void => {
      this.modelMenuOpen = false;
    });
  }

  private toggleModelMenu(): void {
    if (this.modelMenuOpen) {
      this.closeModelMenu();
      return;
    }
    // 重入守卫:装配期间连点忽略(KV/Preferences 本地读取快,但避免重复链)
    if (this.modelMenuLoading) return;
    this.modelMenuLoading = true;
    // 读取失败也必须复位 loading — 否则菜单从此点不开
    loadProviders(getChatKvStore()).then((providers: ProviderSetting[] | null): void => {
      getChatModelSelection().then((sel: ChatModelSelection | null): void => {
        const currentProvider: string = sel !== null ? sel.providerId : '';
        const currentModel: string = sel !== null ? sel.modelId : '';
        this.modelMenuGroups = buildTopModelMenuGroups(
          providers, 'chat', currentProvider, currentModel,
        );
        this.modelMenuCurrentProvider = currentProvider;
        this.modelMenuCurrentModel = currentModel;
        this.modelMenuLoading = false;
        animateTo({ duration: 220, curve: Curve.EaseOut }, (): void => {
          this.modelMenuOpen = true;
        });
      }).catch((e: Error): void => {
        this.modelMenuLoading = false;
        hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'model selection read failed: %{public}s', e.message);
      });
    }).catch((e: Error): void => {
      this.modelMenuLoading = false;
      hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'providers read failed: %{public}s', e.message);
    });
  }

  // 下拉内选中模型:持久化 + 刷新标签 + 关闭。
  //   对齐 Android onSelect → vm.setChatModel;Harmony 选择层 = Preferences 键
  //   (chat_selected_provider_id/model_id),resolveChatRuntime 消费。
  private selectModelFromMenu(providerId: string, modelId: string): void {
    saveChatModelSelection({ providerId, modelId }).then((): void => {
      describeChatModel().then((label: string): void => { this.modelLabel = label; });
      // contextWindowTokens + ReasoningPicker 段集也随之刷新(模型变了,档位段集 + 当前档位变)
      resolveChatProviderChoice().then((choice: ChatProviderChoice | null): void => {
        this.contextWindowTokens = choice !== null ? choice.model.contextWindowTokens : null;
        this.refreshReasoningPicker(choice);
      });
      this.closeModelMenu();
    });
  }

  // M2.1:ReasoningPicker 装配 — 按当前 model 的段集 + assistant 记忆的当前档位。
  //   choice=null(无模型配置)→ 清空段集,ReasoningPicker 不渲染。
  //   Android ChatInputComposers.kt:293-314 thinking footer 装配对应。
  private refreshReasoningPicker(choice: ChatProviderChoice | null): void {
    // 入口统一失效在途请求(null 分支也要递增,防旧模型迟到回调复活档位)
    const seq: number = ++this.reasoningPickerSeq;
    if (choice === null) {
      this.reasoningLevels = [];
      this.currentReasoningLevel = '';
      this.currentModelId = '';
      return;
    }
    this.reasoningLevels = reasoningLevelsForModel(choice.model);
    this.currentModelId = choice.model.id;
    // 当前档位 = assistant 按模型记忆(无记忆 → 模型默认档位)
    const modelId: string = choice.model.id;
    // 模型序号守卫:快速切换模型时,旧模型的迟到结果不得覆盖新模型档位
    getChatAssistants().then((assistants: Assistant[]): void => {
      if (!this.pageAlive || seq !== this.reasoningPickerSeq) return;
      if (this.currentModelId !== modelId) return;
      const assistantId: string = this.conversation !== null ? this.conversation.assistantId : '';
      let picked: Assistant | null = null;
      for (const a of assistants) {
        if (a.id === assistantId) { picked = a; break; }
      }
      if (picked === null) return;
      const a: Assistant = picked;
      this.currentReasoningLevel = reasoningLevelForModel(
        a, modelId, defaultReasoningLevelForModel(choice.model),
      );
    });
  }

  // M2.1:用户在 ReasoningPicker 选档位 → 按模型记忆持久化到 assistant
  //   Android onSelect → assistant.withReasoningLevelForModel(model.id, level)
  private handleReasoningLevelChange(level: string): void {
    if (this.conversation === null) return;
    const assistantId: string = this.conversation.assistantId;
    const modelId: string = this.currentModelId;
    const rl: ReasoningLevel = level as ReasoningLevel;
    getChatAssistants().then((assistants: Assistant[]): void => {
      let changed: Assistant | null = null;
      const next: Assistant[] = [];
      for (const a of assistants) {
        if (a.id === assistantId) {
          changed = withReasoningLevelForModel(a, modelId, rl);
          next.push(changed);
        } else {
          next.push(a);
        }
      }
      if (changed === null) return;
      saveAssistants(getChatKvStore(), next).then((): void => {
        this.currentReasoningLevel = level;
      });
    });
  }

  // 斜杠命令 /compact:手动触发上下文压缩(失效 compacts,下次发送时重新压缩)
  private compactCurrentConversation(): void {
    if (this.conversation === null) return;
    const conv: Conversation = this.conversation;
    this.invalidateConvCompacts(conv.id, 'manual_compact')
      .then((): void => {
        this.refreshContextCompacts();
      })
      .catch((e: Error): void => {
        hilog.warn(0x0001, 'Chat.Page', 'compactCurrentConversation failed: %{public}s', e.message);
      });
  }

  // 工具循环选项:D-058 首批内置工具 = get_time_info + ask_user +
  //   conversation_*(4) + session_*(4),D-059 追加 conversation_queue_*(2),
  //   经 ToolRegistry 包装层(ToolRegistry.kt
  //   :31-161 — 派生 needsApproval/display_title 注入/output budget);
  //   autoApprove/speculative 经 agentRuntime 快照接线(D-104);每步 provider 工厂按步内
  //   工具定义重建 params(arkts-no-spread 逐字段,不用 rt.params 展开)
  private buildToolLoop(rt: ChatRuntime, conv: Conversation): ToolLoopOptions {
    // Android conversationProvider = getConversationFlow().value(最新会话态)
    const conversationProvider: () => Promise<Conversation> = (): Promise<Conversation> =>
      Promise.resolve(this.conversation ?? conv);
    // D-082:ToolProfileFilter(ChatService.kt:2302)— 先按 assistant.toolProfile
    //   过滤原始工具集,再建 registry;tool_search/自省对在过滤后追加(:2326-2329)
    // D-083:LocalToolOption 门控(LocalTools.kt:105-124 + ChatService.kt:2237)—
    //   time_info/ask_user/clipboard 按 assistant.localTools 勾选加入;
    //   run_plan_update/deep_read_open 无门恒在(:182/:188);
    //   workspace_files 六件 D-124 + artifact 十一件 D-125 落地(见下);同组
    //   externalFileTools 及其余 9 variant(javascript_engine/tts/terminal/
    //   screen_automation/system_access/webview/icloud_drive/webmount/
    //   webmount_eval)未移植 → 门为空转(登记)
    const localOpts: LocalToolOption[] = rt.assistant.localTools;
    const profiledTools: AgentTool[] =
      filterToolProfile([
        ...(localOpts.indexOf('time_info') >= 0 ? [createTimeTool()] : []),
        ...(localOpts.indexOf('ask_user') >= 0 ? [createAskUserTool()] : []),
        // D-124:workspace 六件(LocalTools.kt:122-126 — workspace_files 门内
        //   workspaceTools.getTools(),位置 = ask_user 之后;同组
        //   externalFileTools 未移植 → P1 登记);
        //   activityStore = 全局单例(D-122)
        ...(localOpts.indexOf('workspace_files') >= 0
          ? createWorkspaceTools(getWorkspaceManager(), getToolActivityStore())
          : []),
        // D-125:workspace artifact 十一件(LocalTools.kt:123 — 同门第二组
        //   workspaceArtifactTools.getTools();pdf_read/pdf_render_page 平台
        //   阻断显式抛错,登记)
        ...(localOpts.indexOf('workspace_files') >= 0
          ? createWorkspaceArtifactTools({
            workspaceManager: getWorkspaceManager(),
            activityStore: getToolActivityStore(),
            http: createArtifactHttpPort(),
            image: createArtifactImagePort(),
            inflateRaw: inflateRawEntry,
            deflateRaw: createDeflateRawPort(),
            newXmlParser: createEntryXmlPull,
          })
          : []),
        // D-123:skills 工具组(ChatService.kt:2239-2246 — createSkillTools 五/六件,
        //   位置 = localTools 之后/MCP 管理之前);enabledSkills/allSkills =
        //   createRunTools 时快照(rt.assistant/rt.allSkills 同为本次发送快照);
        //   updateEnabledSkill(:339-350)→ setSkillEnabled 闭包 RMW 当前 assistant
        ...createSkillTools({
          enabledSkills: rt.assistant.enabledSkills,
          allSkills: rt.allSkills,
          skillManager: getSkillManager(),
          setSkillEnabled: (name: string, enable: boolean): Promise<void> =>
            this.setSkillEnabled(rt, name, enable),
          workspace: getSkillWorkspacePort(),
          inflateRaw: inflateRawEntry,
        }),
        // D-120:MCP 工具桥接(ChatService.kt:2247-2282)— 管理四件
        //   (mcp_list/mcp_call_tool/mcp_test + mcp_import_from_skill:D-123
        //   skills 子系统落地并入)+ mcp__ 组(getAllAvailableTools 逐个)
        ...createMcpManagementTools({
          settings: getMcpSettingsStore(),
          manager: getMcpManager(),
          skills: getSkillManager(),
          assistantIds: [...rt.assistant.mcpServers],
        }),
        // D-122:activityStore 事件面接线(ChatService.kt:2262-2274 —
        //   startTool/complete/fail 三段;scope 由终表统一包覆,见 tools 组装)
        ...createMcpServerTools({
          manager: getMcpManager(),
          activity: this.mcpActivityPort(),
          // 按轮快照:全局 assistantIdsProvider 会被栈内其它 ChatPage 改写
          assistantIds: [...rt.assistant.mcpServers],
        }),
        // D-085c:memory_* 四件(ChatService.kt:2280 addAll(createMemoryTools),
        //   位置 = MCP 之后/ConversationContextTools 之前);
        //   三开关全关 → 空组(:2346-2351);todayText = toLocalString(true) 等价
        //   (本地化 MEDIUM 日期,建工具时快照)
        ...((this.agentRuntime.enableCoreMemory || this.agentRuntime.enableShortTermMemory
          || this.agentRuntime.enableLongTermMemory)
          ? createMemoryTools(getMemoryToolDeps(
            new Intl.DateTimeFormat(systemLanguage(), { dateStyle: 'medium' }).format(new Date())))
          : []),
        ...createConversationContextTools({
          conversationProvider,
          compactStore: getCompactStore(),
          // 与 buildPrepareContext 同口径(settings.agentRuntime.contextCompaction,D-091)
          policy: makeCompactPolicy({
            enabled: this.agentRuntime.contextCompaction.enabled,
            notifyOnly: this.agentRuntime.contextCompaction.notifyOnly,
            precompactRatio: this.agentRuntime.contextCompaction.precompactRatio,
            forceRatio: this.agentRuntime.contextCompaction.forceRatio,
            keepRecentTurns: this.agentRuntime.contextCompaction.keepRecentTurns,
            maxSummaryTokens: this.agentRuntime.contextCompaction.maxSummaryTokens,
          }),
          modelContextWindowTokens: rt.modelContextWindowTokens,
          compactEngineDeps: {
            provider: this.compressProvider(rt),
            store: getCompactStore(),
            // D-094:settings.compressPrompt(同步构建位 → aboutToAppear 快照,
            //   未就绪 → DEFAULT_COMPRESS_PROMPT,与 deps.compressPrompt ?? DEFAULT 同义)
            compressPrompt: this.taskPromptsCache !== null
              ? this.taskPromptsCache.compressPrompt : DEFAULT_COMPRESS_PROMPT,
          },
        }),
        ...createConversationHistoryTools({
          repository: getChatRepository(),
          currentConversationProvider: conversationProvider,
          grantStore: getSessionGrantStore(),
        }),
        // D-059:conversation_queue_* 工具对(ChatService.kt:2457-2544);
        //   变更经既有页面路径(cancelPendingById/clear)— persist+审计与
        //   ChatService:498-511 同语义(changed/count>0 才记录)
        ...createConversationQueueTools({
          pendingProvider: (): Promise<PendingUserMessage[]> => Promise.resolve(this.pendingQueue),
          cancelOne: (messageId: string): Promise<void> => this.cancelPendingById(messageId),
          clearAll: (): Promise<void> => {
            const previous: PendingUserMessage[] = this.pendingQueue;
            const count: number = previous.length;
            if (count > 0) {
              this.pendingQueue = [];
              this.syncPendingRows();
              return this.persistQueue().then((): Promise<void> =>
                this.auditQueueEvent('clear', '', `count=${count}`))
                .catch((e: Error): void => {
                  this.pendingQueue = previous;
                  this.syncPendingRows();
                  this.errorMsg = e.message;
                  throw e;
                });
            }
            return Promise.resolve();
          },
        }),
        // D-131:agent_task_* 六件(ChatService.kt:2299 无条件;
        //   createConversationQueueTools(:2297)之后)
        ...this.agentTaskToolsCache,
        // D-129:permissions_status(LocalTools.kt:180 无条件,位置 =
        //   agentCron(:181)/runPlanUpdate(:182)之前)
        getPermissionsStatusTool(),
        // D-130:agent_cron 四件(LocalTools.kt:181 无条件;aboutToAppear
        //   水合,完成前为空数组 = searchSettings 时序先例)
        ...this.agentCronToolsCache,
        // D-061:本地工具三件(LocalTools runPlanUpdateTool/clipboardTool/
        //   deepReadOpenTool);deep_read bus 收集器 = router 打开深读页
        //   (Index.startDeepRead:120-127 同路由;页面存活即有收集者)
        createRunPlanUpdateTool(),
        ...(localOpts.indexOf('clipboard') >= 0
          ? [createClipboardTool(createEntryClipboardPort())] : []),
        // D-128:agent_prompt_config(LocalTools.kt:183 无条件,
        //   位置 = runPlanUpdate(:182)之后/deepReadPlaybook(:187)之前)
        getAgentPromptConfigTool(),
        // D-127:DeepReadPlaybookTools 四件(LocalTools.kt:187 无条件,
        //   位置 = deepReadOpenTool(:188)之前);仓库 = deepread HAR
        //   playbook 单例,结构直配 DeepReadPlaybookPort
        ...createDeepReadPlaybookTools(getDeepReadPlaybookRepository()),
        createDeepReadOpenTool({
          bus: {
            hasCollectors: (): boolean => true,
            emit: (event: DeepReadOpenEvent): void => {
              this.openDeepRead(event);
            },
          },
          sha256Hex: sha256HexUtf8,
        }),
        // D-070:搜索工具组(ChatService.kt:2232-2234 — enableWebSearch 门内
        //   addAll(createSearchTools(settings)));searchSettings 未加载完成时
        //   本轮不带搜索工具(偏好加载为异步,与 Android StateFlow 就绪时序等价)
        ...(this.searchSettings !== null && this.searchSettings.enableWebSearch
          ? createSearchTools(this.searchSettings)
          : []),
      ], rt.assistant.toolProfile).tools;
    const registry: ToolRegistry = createToolRegistry(profiledTools);
    // D-132c:Android ChatService.kt:2304-2306 — baseTools = baseRegistry.tools() +
    //   introspection(baseRegistry);parentToolsProvider 只捕获此 base 集,
    //   不含 subagent_*(后者由 step-5 条件挂载)。缓存供 subagent_start 委派读取。
    this.baseToolsCache = [
      ...registry.tools(),
      ...createRegistryIntrospectionTools(registry),
    ];
    // D-132c:Android ChatService.kt:2307-2315 — conversation 存在且 subAgent.enabled
    //   时挂五件 SubAgentTools(parentToolsProvider = baseTools);否则空。
    //   public subagent_* 不进入 child scope(manager.start 过滤);不加入 LocalTools。
    const supervisorTools: AgentTool[] = this.subAgentEnabled && this.conversation !== null
      ? this.subAgentToolsCache
      : [];
    return {
      // createRunTools(ChatService.kt:2326-2329):registry.tools() +
      //   tool_search(profile 透传,D-082)+ 自省对 + supervisor tools;
      //   D-122:scopedToConversation(:2329 → :2332-2343)终表统一包覆
      tools: this.scopedToConversation([
        ...this.baseToolsCache,
        createToolSearchTool(registry, rt.assistant.toolProfile),
        ...supervisorTools,
      ], conv),
      makeProviderForStep: (defs: ChatToolDefinition[]): ChatStreamProvider =>
        asChatStreamProvider(rt.api, (): TextGenerationParams => makeTextGenerationParams({
          model: rt.params.model,
          temperature: rt.params.temperature,
          topP: rt.params.topP,
          maxTokens: rt.params.maxTokens,
          tools: defs,
          reasoningLevel: rt.params.reasoningLevel,
          customBody: rt.params.customBody,
        })),
      consumeSteerMessages: (): Promise<UIMessage[]> => this.drainSteerMessages(),
      // D-077a:tool system prompt 块所需模型(buildSystemPromptParts:739-741)
      toolPromptModel: rt.params.model,
      // D-104:ChatService.kt:1345-1347 逐字 —
      //   autoApproveTools = settings.agentRuntime.autoApproveAllToolCalls
      //     || conversation.autoApproveToolCalls
      autoApproveTools: this.agentRuntime.autoApproveAllToolCalls || conv.autoApproveToolCalls,
      autoApproveHighRiskTools: this.agentRuntime.autoApproveHighRiskToolCalls,
      // D-104:speculativeToolExecution(PreferencesStore.kt:226-229)门接线
      speculativeEnabled: this.agentRuntime.speculativeToolExecution.enabled,
      speculativeMaxConcurrentTools: this.agentRuntime.speculativeToolExecution.maxConcurrentTools,
      // D-115:ChatService.kt:1349-1352 —
      //   maxSteps = settings.agentRuntime.maxToolLoopSteps.coerceIn(16,512)
      //   (coerceIn 引擎侧 tool_loop.ts:147-148 已忠实,此处原样透传)
      maxSteps: this.agentRuntime.maxToolLoopSteps,
    };
  }

  // D-120:assistant.mcpServers 白名单 provider 接线(runtime 解析后;
  //   Android settings.getCurrentAssistant().mcpServers 的会话级等价)
  private wireMcpAssistant(rt: ChatRuntime): void {
    getMcpSettingsStore().setAssistantIdsProvider((): string[] => rt.assistant.mcpServers);
  }

  // D-121:tools_warning 门(ChatService.kt:1278-1286)— 模型无 TOOL 能力且
  //   (settings.enableWebSearch || mcpManager.getAllAvailableTools() 非空) →
  //   addError(tools_warning, title=error_title_tool_unavailable),**不中断生成**。
  //   须在 wireMcpAssistant 之后调用(getAllAvailableTools 依赖 assistant provider)。
  //   偏差登记:addError 带 title('工具不可用')— harmony 错误条无 title 槽位,
  //   仅正文 values-zh:1277 逐字上浮。
  private warnIfToolAbilityMissing(rt: ChatRuntime): void {
    if (rt.modelAbilities.indexOf('tool') >= 0) return;
    const searchOn: boolean = this.searchSettings !== null && this.searchSettings.enableWebSearch;
    if (!searchOn && getMcpManager().getAllAvailableTools().length === 0) return;
    this.errorMsg = '当前模型未开启工具调用，请在设置内启用模型的工具能力，再使用搜索/MCP等功能';
  }

  // ===== D-122:沙盒活动时间线(ChatPage.kt:500-536 + ChatInputSandbox.kt) =====

  // :503-522 — derive + scoped live + merge + operationPreviewMode 门;
  //   偏差:processingStatus ← retryStatus(harmony 无独立 live-status 流,
  //   enableLiveStatusNotification 子系统未移植,P1 延续)
  private sandboxTimeline(): SandboxActivityUiState[] {
    const conv: Conversation | null = this.conversation;
    if (conv === null) return [];
    const messageActivities: SandboxActivityUiState[] = deriveSandboxActivities(
      conv, this.sending, this.retryStatus.length > 0 ? this.retryStatus : null);
    // scopedLiveSandboxActivity(:508-511):conversationId 匹配,或 null 且
    //   消息时间线含该 toolCallId
    let scoped: SandboxActivityUiState | null = null;
    const live: SandboxActivityUiState | null = this.liveActivity;
    if (live !== null) {
      const inMessages: boolean = messageActivities.some(
        (a: SandboxActivityUiState): boolean => a.toolCallId === live.toolCallId);
      if (live.conversationId === conv.id || (live.conversationId === null && inMessages)) {
        scoped = withStepProgress(live, conv);
      }
    }
    const raw: SandboxActivityUiState[] = mergeSandboxTimeline(messageActivities, scoped);
    // :516-522 — ALWAYS 空 → idle 兜底;AUTO 过滤活动态;HIDDEN 空
    const mode: string = this.agentRuntime.operationPreviewMode;
    if (mode === 'always') {
      return raw.length > 0 ? raw : [idleSandboxActivity(conv)];
    }
    if (mode === 'auto') return raw.filter(isActiveOperation);
    return [];
  }

  // :523-529 — selectedSandboxIndex clamp(-1 = 最新)+ canCancel
  private currentSandboxActivity(timeline: SandboxActivityUiState[]): SandboxActivityUiState | null {
    const latest: number = timeline.length - 1;
    if (latest < 0) return null;
    if (this.selectedSandboxIndex >= 0 && this.selectedSandboxIndex <= latest) {
      return timeline[this.selectedSandboxIndex];
    }
    return timeline[latest];
  }

  // sandboxStatusLabel(:280-286 文案逐字)
  private sandboxStatusZh(status: string): string {
    if (status === 'running') return '执行中';
    if (status === 'waiting_for_permission') return '待授权';
    if (status === 'succeeded') return '成功';
    if (status === 'failed') return '失败';
    return '已取消';
  }

  // stepProgressText(:362-370):agent_idle → '待命';x/y;否则状态文案
  private sandboxStepText(activity: SandboxActivityUiState): string {
    if (activity.toolName === 'agent_idle') return '待命';
    if (activity.stepIndex !== null && activity.stepTotal !== null) {
      return `${activity.stepIndex}/${activity.stepTotal}`;
    }
    return this.sandboxStatusZh(activity.status);
  }

  // ChatService.kt:2254-2282 activityStore 事件面 — store 适配 McpToolActivityPort
  private mcpActivityPort(): McpToolActivityPort {
    const store: AgentToolActivityStore = getToolActivityStore();
    return {
      startTool: (toolName: string, title: string, inputPreview: string, runtime: string): string =>
        store.startTool(toolName, title, inputPreview, runtime),
      complete: (toolCallId: string, outputPreview: string): void => {
        store.complete(toolCallId, outputPreview);
      },
      fail: (toolCallId: string, error: Error): void => {
        store.fail(toolCallId, error);
      },
    };
  }

  // ChatService.kt:2332-2343 — 终表每工具 execute 包 withConversation(scopeId)
  private scopedToConversation(tools: AgentTool[], conv: Conversation): AgentTool[] {
    const scopeId: string = conv.id;
    const store: AgentToolActivityStore = getToolActivityStore();
    return tools.map((tool: AgentTool): AgentTool => makeAgentTool({
      name: tool.name,
      description: tool.description,
      parameters: tool.parameters,
      systemPrompt: tool.systemPrompt,
      needsApproval: tool.needsApproval,
      allowsAutoApproval: tool.allowsAutoApproval,
      mandatoryApproval: tool.mandatoryApproval,
      execute: (input: JsonValue, signal?: AbortSignalLike): Promise<UIMessagePart[]> =>
        store.withConversation(scopeId,
          (): Promise<UIMessagePart[]> => tool.execute(input, signal)),
    }));
  }

  // D-123:updateEnabledSkill(SkillsTools.kt:339-350)— settingsStore.update 对
  //   当前 assistant enabledSkills 做 +name/-name RMW;entry = assistants KV
  //   读改写(rt.assistant.id = 本次发送解析的当前助手 = getCurrentAssistant 同义)
  private async setSkillEnabled(rt: ChatRuntime, name: string, enable: boolean): Promise<void> {
    const kv = getChatKvStore();
    const list: Assistant[] = await loadAssistants(kv) ?? [];
    const next: Assistant[] = list.map((a: Assistant): Assistant => {
      if (a.id !== rt.assistant.id) return a;
      const skills: string[] = enable
        ? (a.enabledSkills.indexOf(name) >= 0 ? a.enabledSkills : a.enabledSkills.concat([name]))
        : a.enabledSkills.filter((n: string): boolean => n !== name);
      return patchAssistant(a, { enabledSkills: skills });
    });
    await saveAssistants(kv, next);
  }

  // steer 消息步间消费(Android session.dequeueSteerPendingUserMessages,
  //   GenerationHandler:386-390):生成中到达的 STEER 出队 → user 消息注入会话;
  //   出队后 durable persist + 审计(D-052 同规)
  private drainSteerMessages(): Promise<UIMessage[]> {
    const previous: PendingUserMessage[] = this.pendingQueue;
    const steers: PendingUserMessage[] = previous.filter(
      (m: PendingUserMessage): boolean => m.mode === 'STEER');
    if (steers.length === 0) return Promise.resolve([]);
    this.pendingQueue = this.pendingQueue.filter(
      (m: PendingUserMessage): boolean => m.mode !== 'STEER');
    this.syncPendingRows();
    let durable: Promise<void> = this.persistQueue();
    for (const s of steers) {
      durable = durable.then((): Promise<void> => this.auditQueueEvent('dequeue', s.id, 'steer'));
    }
    return durable.then((): UIMessage[] => steers.map(
      (s: PendingUserMessage): UIMessage => makeUIMessage('user', s.parts)))
      .catch((e: Error): UIMessage[] => {
        this.pendingQueue = previous;
        this.syncPendingRows();
        this.errorMsg = e.message;
        throw e;
      });
  }

  // 工具审批(ChatService.kt:1122-1188):改写 approvalState → 持久化 →
  //   无 pending 残留 → 续跑生成(handleMessageComplete)
  private handleToolApproval(toolCallId: string, approved: boolean, reason: string = ''): void {
    const verdict: ToolApprovalVerdict = approved
      ? { kind: 'approved' }
      : { kind: 'denied', reason };
    this.applyToolVerdict(toolCallId, verdict);
  }

  // ===== D-108:拒绝理由弹窗(ToolDenyReasonDialog :428-461 + 调用点 :966-974) =====
  //   拒绝按钮 → showDenyDialog;confirm(reason) → onToolApproval(id,false,reason)
  private openDenyDialog(toolCallId: string): void {
    this.denyReasonText = '';
    this.denyDialogToolCallId = toolCallId;
  }

  private dismissDenyDialog(): void {
    this.denyDialogToolCallId = '';
    this.denyReasonText = '';
  }

  private confirmDenyDialog(): void {
    const id: string = this.denyDialogToolCallId;
    const reason: string = this.denyReasonText;
    this.denyDialogToolCallId = '';
    this.denyReasonText = '';
    if (id.length > 0) {
      this.handleToolApproval(id, false, reason);
    }
  }

  // D-105:ask_user 应答(ChatService.handleToolApproval answer 分支,:1136-1140
  //   answer 优先 → answered 态;落库 + 无 pending 则续跑,同审批路径)
  private handleToolAnswer(toolCallId: string, answer: string): void {
    this.applyToolVerdict(toolCallId, { kind: 'answered', answer });
  }

  private applyToolVerdict(toolCallId: string, verdict: ToolApprovalVerdict): void {
    if (this.conversation === null || this.sending) return;
    const previous: Conversation = this.conversation;
    const out: Conversation = applyToolApprovalToConversation(
      previous, toolCallId, verdict);
    this.conversation = out;
    this.refreshRowsAfterConversationChange(previous, out);
    getChatRepository().save(out)
      .then((): void => {
        if (!conversationHasPendingTools(out)) {
          this.resumeToolLoop(out);
        }
      })
      .catch((e: Error): void => {
        this.errorMsg = e.message;
      });
  }

  // 审批后续跑:不追加 user 消息,直接进工具循环;流式预览复用 D-041
  //   regenTail 机制(末条 assistant 行下渲染 — 写回的就是末条消息)
  private resumeToolLoop(conv: Conversation): void {
    if (this.sending) return;
    this.sending = true;
    this.errorMsg = '';
    const operationAbort: EntryAbortController = new EntryAbortController();
    this.abortController = operationAbort;
    this.checkpointRegenerateNodeId = '';
    const operationToken: number = ++this.generationToken;
    const lastNodeId: string = conv.messageNodes.length > 0
      ? conv.messageNodes[conv.messageNodes.length - 1].id
      : '';
    const holder: RuntimeHolder = { rt: null };
    resolveChatRuntime(conv.assistantId)
      .then(async (rt: ChatRuntime | null): Promise<Conversation> => {
        holder.rt = rt;
        if (rt === null) {
          return Promise.reject(new Error('未配置可用的 Provider/模型'));
        }
        this.wireMcpAssistant(rt);
        this.warnIfToolAbilityMissing(rt);
        // D-132c:水合 subagent 设置 + 工具(buildToolLoop 前;parentToolsProvider 惰性)
        await this.prepareSubAgentTools(conv);
        return runToolLoopContinuation(conv, {
          assistant: rt.assistant,
          // D-090:agentSoul 静态块首槽位(GenerationHandler.kt:721-724)
          agentSoul: buildAgentSoulPrompt(this.agentRuntime.agentSoulMarkdown),
          inputTransformers: this.buildInputTransformers(rt),
          // D-071b:视觉兜底门(模型 IMAGE 模态 + 有图才兜底,GenerationHandler.kt:431)
          modelSupportsImageInput: rt.modelSupportsImageInput,
          outputTransformers: [thinkTagTransformer, createEntryBase64ImageTransformer(), createMiniAppOutputTransformer({ enabled: (): boolean => this.miniAppSetting.enabled, repository: this.miniAppRepository() }), regexOutputTransformer], // ChatService.kt:179-184 序(MiniAppOutput 3 位) 依赖 this.miniAppRepository()
          provider: asChatStreamProvider(rt.api, () => rt.params),
          store: getChatRepository(),
          contextMessageSize: rt.contextMessageSize,
          prepareContextMessages: this.buildPrepareContext(conv, rt),
          // D-077b:recentChats 动态块(每次组装重取)
          // D-085b:记忆召回块(动态序最前,GenerationHandler.kt:727)
          memorySystemBlocks: (): Promise<string[]> => this.loadMemorySystemBlocks(),
          extraSystemBlocks: (): Promise<string[]> => this.loadExtraSystemBlocks(),
          abortSignal: operationAbort.signal,
          // D-115:GenerationHandler.kt:357/475/621 — retrySetting =
          //   settings.agentRuntime.generationRetry(续跑路径同接线)
          retrySetting: makeGenerationRetrySetting({
            enabled: this.agentRuntime.generationRetry.enabled,
            maxRetries: this.agentRuntime.generationRetry.maxRetries,
          }),
          onRetryStatus: (status: string | null): void => {
            if (this.isGenerationOperationActive(operationToken)) {
              this.retryStatus = status ?? '';
            }
          },
          onUpdate: (msgs: UIMessage[]): void => {
            if (!this.isGenerationOperationActive(operationToken)
              || msgs.length === 0 || holder.rt === null) return;
            const ctx: TransformerContext = { assistant: holder.rt.assistant };
            const transformed: UIMessage[] = applyVisualTransformersStreamingTail(
              msgs, [thinkTagTransformer, regexOutputTransformer], ctx); // MiniApp:仅 ThinkTag/Regex 实现 visualTransformTail(Android 同);MiniAppOutput 无 visual 变换,流式期恒等 → 不加
            this.regenNodeId = lastNodeId;
            this.regenTail = transformed[transformed.length - 1];
            this.followStreamingUpdate();
          },
          // Phase 2:raw 快照捕获(与 send 路径同接线)
          onRawFlushSnapshot: (msgs: UIMessage[]): void => {
            if (!this.isGenerationOperationActive(operationToken)) return;
            this.lastRawSnapshot = msgs;
            this.captureStreamCheckpoint(msgs);
          },
        }, this.buildToolLoop(rt, conv));
      })
      .then((out: Conversation): void => {
        const stopped: boolean = operationAbort.signal.aborted;
        if (!this.isGenerationOperationActive(operationToken)) {
          this.finishInactiveGeneration(out, stopped);
          return;
        }
        this.finishGenerationCommon(out, stopped);
        this.refreshContextCompacts(); // D-110:压缩可能已写新摘要,刷新足迹
        if (holder.rt !== null) {
          this.maybeAutoTitle(this.conversation as Conversation);
          this.maybeSuggestions(this.conversation as Conversation, holder.rt);
          this.maybeMemoryExtraction(this.conversation as Conversation, holder.rt);
        }
        this.drainPendingQueue();
      })
      .catch((e: Error): void => {
        if (!this.isGenerationOperationActive(operationToken)) return;
        this.errorMsg = this.compactErrorText(e);
        this.sending = false;
        this.refreshContextCompacts(); // D-110:压缩可能已写新摘要,刷新足迹
        this.abortController = null;
        this.regenTail = null;
        this.regenNodeId = '';
        this.lastRawSnapshot = [];
        // 审批态/部分写回已持久化,刷新显示
        getChatRepository().getById(conv.id)
          .then((saved: Conversation | null): void => {
            if (saved !== null) {
              const previous: Conversation | null = this.conversation;
              this.conversation = saved;
              this.refreshRowsAfterConversationChange(previous, saved);
            }
          })
          .catch((reloadError: Error): void => {
            hilog.warn(HILOG_DOMAIN, HILOG_TAG,
              'regenerate reload failed: %{public}s', reloadError.message);
          })
          .then((): void => { this.drainPendingQueue(); });
      });
  }

  // 生成中发送 → 入队;满 20 拒绝(Android 文案逐字)。
  // 模式(D-051,Android ChatInput.kt:259/272 + ChatService.kt:699):
  //   answer=true(点击发送)→ 恒 FOLLOWUP;answer=false(长按)生成中 → STEER
  //   (插 STEER 前缀位,派发时 asFollowup 转换),否则 FOLLOWUP
  // ChatService.kt:695-700:mode 显式传入(默认 FOLLOWUP);非生成期 Android 强制
  //   FOLLOWUP — 鸿蒙入队仅在 sending 分支发生,天然等价
  private enqueuePendingParts(
    parts: UIMessagePart[], answer: boolean, mode: PendingUserMessageMode = 'FOLLOWUP'): void {
    const previous: PendingUserMessage[] = this.pendingQueue;
    const msg: PendingUserMessage = makePendingUserMessage({ parts, answer, mode });
    const r = enqueuePendingUserMessage(previous, msg);
    if (!r.accepted) {
      this.errorMsg = '消息队列已满，请先等待或取消一些排队消息。';
      return;
    }
    this.pendingQueue = r.messages;
    this.syncPendingRows();
    this.persistQueueWithEvent('enqueue', msg.id, msg.mode.toLowerCase())
      .catch((e: Error): void => {
        this.pendingQueue = previous;
        this.syncPendingRows();
        this.errorMsg = e.message;
      });
  }

  private cancelPendingById(id: string): Promise<void> {
    const previous: PendingUserMessage[] = this.pendingQueue;
    const r = cancelPendingUserMessage(previous, id);
    if (!r.changed) return Promise.resolve();
    this.pendingQueue = r.messages;
    this.syncPendingRows();
    return this.persistQueueWithEvent('cancel', id)
      .catch((e: Error): void => {
        this.pendingQueue = previous;
        this.syncPendingRows();
        this.errorMsg = e.message;
        throw e;
      });
  }

  // 出队后派发失败回灌:头插回**当前**队列并持久化(不能回写旧快照 —
  // 期间可能有并发入队,且 KV 已是 dequeue 后状态)
  private requeueDispatchAtHead(dispatch: PendingUserMessage): void {
    const current: PendingUserMessage[] = this.pendingQueue;
    this.pendingQueue = [dispatch, ...current];
    this.syncPendingRows();
    this.persistQueueWithEvent('enqueue', dispatch.id, dispatch.mode.toLowerCase())
      .catch((e: Error): void => { this.errorMsg = e.message; });
  }

  // 生成完成(成功/失败/中断)后出队下一条继续派发(Android 循环逐条 catch 后继续)
  private drainPendingQueue(): void {
    if (this.pendingDrainActive || this.sending || this.appendActive || this.conversation === null) return;
    if (conversationHasPendingOrUnexecutedTools(this.conversation)) return;
    const dq: DequeueNextResult = dequeueNextPendingUserMessage(this.pendingQueue);
    if (dq.next === null) return;
    const prep = preparePendingMessageForDispatch(dq.rest, dq.next);
    this.pendingQueue = prep.rest;
    this.syncPendingRows();
    this.pendingDrainActive = true;
    this.persistQueueWithEvent('dequeue', prep.dispatch.id, prep.dispatch.mode.toLowerCase())
      .then((): void => {
        this.pendingDrainActive = false;
        if (!prep.dispatch.answer) {
          this.pendingDrainActive = true;
          this.appendActive = true;
          // holder:append 失败时回灌该 dispatch(闭包内重赋值,ArkTS 收窄限制)
          const requeue: PendingRequeueHolder = { msg: null };
          // Phase 5 全量 invariant:append 落库前确保完整会话(窗口未全量先补全)
          this.ensureFullConversationForMutation().then((): void => {
            if (this.conversation === null) {
              this.appendActive = false;
              this.pendingDrainActive = false;
              this.requeueDispatchAtHead(prep.dispatch);
              return;
            }
            runAppendUserMessage(this.conversation as Conversation, prep.dispatch.parts,
              getChatRepository())
              .then((out: Conversation): void => {
                const previous: Conversation | null = this.conversation;
                this.conversation = out;
                this.refreshRowsAfterConversationChange(previous, out);
                this.scrollToBottom();
              })
              .catch((e: Error): void => {
                this.errorMsg = e.message;
                requeue.msg = prep.dispatch;
              })
              .then((): void => {
                this.appendActive = false;
                this.pendingDrainActive = false;
                if (requeue.msg !== null) {
                  // 已出队未落库 → 回灌保消息;本轮停轮避免 失败→重试 死循环,
                  // 下次用户操作/生成结束再触发 drain
                  this.requeueDispatchAtHead(requeue.msg);
                  return;
                }
                this.drainPendingQueue();
              });
          }).catch((e: Error): void => {
            // 补全失败:回灌 + 复位双标志,否则 pending queue 永久停滞
            this.appendActive = false;
            this.pendingDrainActive = false;
            this.requeueDispatchAtHead(prep.dispatch);
            this.errorMsg = e.message;
          });
          return;
        }
        this.sendParts(prep.dispatch.parts, prep.dispatch.id,
          (): void => this.requeueDispatchAtHead(prep.dispatch));
      })
      .catch((e: Error): void => {
        // dequeue 持久化失败:内存已是 rest — 头演回 dispatch 保消息(KV 仍是旧态,
        // 回灌的 enqueue 持久化失败则内存已保住,尽力而为)
        this.pendingDrainActive = false;
        this.requeueDispatchAtHead(prep.dispatch);
        this.errorMsg = e.message;
      });
  }

  // ===== D-064 /deepread 斜杠命令(SendMessageOrchestrator.kt:26-41) =====

  // 深读页路由(deep_read_open 工具 bus 与斜杠命令共用)
  private openDeepRead(event: DeepReadOpenEvent): void {
    const params: Record<string, string> = {};
    params['topicId'] = event.topicId;
    params['title'] = event.title;
    router.pushUrl({ url: 'pages/DeepReadArticlePage', params: params });
  }

  // 命中 → 路由深读页 + 清空输入,不入队/不发给模型(orchestrator:34-38
  //   bus.tryEmit 恒真 = 页面存活即有收集者,与 deep_read_open 同口径)
  private tryRouteDeepReadSlash(inputParts: UIMessagePart[]): boolean {
    const event: DeepReadOpenEvent | null = parseDeepReadSlashCommand(inputParts, sha256HexUtf8);
    if (event === null) return false;
    this.inputText = '';
    this.invalidatePicker(true);
    this.openDeepRead(event);
    return true;
  }

  // D-051/D-081:长按发送(ChatInput.kt:265-289 + ChatPage.kt:706-710):
  //   生成中 → STEER 入队(插在生成后首位)+ answer=TRUE(:708
  //   answer = loadingJob != null && queueMode == STEER — 插话被进行中的
  //   生成消费,属「已应答」;未被消费则降级 FOLLOWUP 派发时仍要生成);
  //   非生成中 → answer=false 直接 appendUserMessage(只记录不生成)
  //   登记偏差:编辑态不支持(Android 无此交互),编辑态长按按普通发送语义入队
  private sendWithoutAnswer(): void {
    if (this.materializingImages) return;
    const text: string = this.inputText.trim();
    const hasImages: boolean = this.pendingImages.length > 0;
    if ((text.length === 0 && !hasImages) || this.conversation === null) return;
    const inputParts: UIMessagePart[] = [];
    if (text.length > 0) {
      inputParts.push({ type: 'text', text, metadata: null });
    }
    for (const url of this.pendingImages) {
      inputParts.push({ type: 'image', url, metadata: null });
    }
    // D-064:斜杠命令拦截(编辑态除外 — Android 编辑走 handleMessageEdit 不经 orchestrator)
    if (this.editingNodeId.length === 0 && this.tryRouteDeepReadSlash(inputParts)) return;
    // D-080:模型门(Android 长按发送 :695-698);门过才清输入(中止时输入保留)
    // D-111:门后图片守卫(ChatInput.kt:279 sendMessageWithoutAnswer);守卫失败同保留
    this.gateModelConfigured((choice: ChatProviderChoice): void => {
      this.guardImagesForSend(inputParts, choice, (): void => {
        // P4:发送被接受 → 延迟关键盘(Android ChatInput.hideKeyboardAfterSend 语义)
        this.dismissKeyboardAfterSend();
        this.inputText = '';
        this.invalidatePicker(true);
        if (this.editingNodeId.length > 0) this.cancelEdit();
        if (this.sending) {
          // D-081:STEER + answer=true(ChatPage.kt:708) — 插话注入进行中生成
          this.enqueuePendingParts(inputParts, true, 'STEER');
          return;
        }
        if (this.appendActive) {
          // 上一个「只追加不生成」还在跑:同样入队(answer=false,drain 时走 append 路径),
          // 不能清空输入后静默丢弃消息
          this.enqueuePendingParts(inputParts, false);
          return;
        }
        this.appendActive = true;
        // Phase 5 全量 invariant:append 落库前确保完整会话(窗口未全量先补全)
        this.ensureFullConversationForMutation().then((): void => {
          if (this.conversation === null) {
            this.appendActive = false;
            return;
          }
          runAppendUserMessage(this.conversation as Conversation, inputParts, getChatRepository())
            .then((out: Conversation): void => {
              const previous: Conversation | null = this.conversation;
              this.conversation = out;
              this.refreshRowsAfterConversationChange(previous, out);
              this.scrollToBottom();
            })
            .catch((e: Error): void => { this.errorMsg = e.message; })
            .then((): void => {
              this.appendActive = false;
              this.drainPendingQueue();
            });
        }).catch((e: Error): void => {
          // 补全失败必须复位,否则 appendActive 永久 true、后续长按发送全部被吞
          this.appendActive = false;
          this.errorMsg = e.message;
        });
      });
    });
  }

  // ===== D-080 模型空值门 =====
  // Android ChatPage.kt:532-533 canSendWithoutChatModel + :676-679/:695-698/
  //   :823-825/:864-869/:880-885 发送门 — currentChatModel == null 且非
  //   /deepread 斜杠(编辑态恒要模型)→ toast '请先选择模型'(逐字)中止:
  //   输入保留,不入队/不生成/不编辑。斜杠路由已在各路径前置拦截,无需在此判断。
  // D-111:choice 透传(图片守卫需要 chat 模型 inputModalities)
  private gateModelConfigured(onConfigured: (choice: ChatProviderChoice) => void): void {
    resolveChatProviderChoice().then((choice: ChatProviderChoice | null): void => {
      if (choice === null) {
        this.errorMsg = '请先选择模型';
        return;
      }
      onConfigured(choice);
    }).catch((e: Error): void => {
      this.errorMsg = e.message;
    });
  }

  // ===== D-111 图片发送守卫(ImageAttachmentValidator.kt:79-100;消费点 ChatInput.kt:250/:279) =====
  // Android:sendMessage/sendMessageWithoutAnswer 协程内 firstBlockingIssueForSend —
  //   blockingIssue != null → toast 错误中止(输入/待发附件保留);null → 放行。
  // 鸿蒙接线:模型门已通过(chat 模型空值分支不可达,Android 同文案等价);
  //   守卫失败 → errorMsg(toast 等价面);回退场景实时探测视觉健康
  //   (generateVisionText 与 OcrTransformer 同口径,OcrSupport D-071b)。
  private guardImagesForSend(
    inputParts: UIMessagePart[], choice: ChatProviderChoice, onPass: () => void,
  ): void {
    const seed: OcrSeed | null = this.ocrSeed;
    const settings: ImageGuardSettings = {
      chatModel: choice.model,
      ocrModelId: seed !== null ? seed.ocrModelId : '',
      providers: seed !== null ? seed.providers : [],
    };
    const encodeCheck = (url: string): void => { encodeImageDetailed(url); };
    const probeHealth = (): Promise<VisionModelHealth> => probeVisionModelHealth({
      findOcrModel: (): ProviderModel | null =>
        findModelByIdInProviders(settings.providers, settings.ocrModelId),
      modelSupportsImage: (m: ProviderModel): boolean => m.inputModalities.includes('image'),
      findProvider: (m: ProviderModel): ProviderSetting | null =>
        findProviderOfModel(settings.providers, m),
      generateText: (p: ProviderSetting, m: ProviderModel, msgs: UIMessage[]): Promise<string | null> =>
        generateVisionText(p, m, msgs),
    });
    firstImageBlockingIssueForSend(inputParts, settings, encodeCheck, probeHealth)
      .then((issue): void => {
        if (issue !== null) {
          this.errorMsg = issue.message;
          return;
        }
        onPass();
      })
      .catch((e: Error): void => { this.errorMsg = e.message; });
  }

  // 附件点按(ChatInputAttachments.kt:onStatusClick)— blocked 才 toast 错误,其余静默
  private inspectPendingImage(url: string): void {
    resolveChatProviderChoice().then((choice: ChatProviderChoice | null): void => {
      const seed: OcrSeed | null = this.ocrSeed;
      const settings: ImageGuardSettings = {
        chatModel: choice !== null ? choice.model : null,
        ocrModelId: seed !== null ? seed.ocrModelId : '',
        providers: seed !== null ? seed.providers : [],
      };
      const status = inspectImageAttachment(url, settings,
        (u: string): void => { encodeImageDetailed(u); });
      if (imageAttachmentBlocksSend(status)) {
        this.errorMsg = status.message;
      }
    }).catch((e: Error): void => { this.errorMsg = e.message; });
  }

  private send(): void {
    if (this.materializingImages) return;
    const text: string = this.inputText.trim();
    const hasImages: boolean = this.pendingImages.length > 0;
    // D-038:文本可空但图片不可空;两者皆空才拦截(isEmptyInput 对齐 Android)
    if ((text.length === 0 && !hasImages) || this.conversation === null) return;
    // 组装输入 parts:文本 + 图片(data: URL)
    const inputParts: UIMessagePart[] = [];
    if (text.length > 0) {
      inputParts.push({ type: 'text', text, metadata: null });
    }
    for (const url of this.pendingImages) {
      inputParts.push({ type: 'image', url, metadata: null });
    }
    // D-064:斜杠命令拦截(orchestrator 在排队/发送前解析;编辑态除外)
    if (this.editingNodeId.length === 0 && this.tryRouteDeepReadSlash(inputParts)) return;
    // D-080:模型门(编辑态同要模型 — Android canRouteWithoutChatModel 编辑恒 false)
    // D-111:门后图片守卫(ChatInput.kt:250 sendMessage);编辑态无图片 part → 守卫直放行
    this.gateModelConfigured((choice: ChatProviderChoice): void => {
      this.guardImagesForSend(inputParts, choice, (): void => {
        // P4:发送被接受 → 延迟关键盘(Android ChatInput.hideKeyboardAfterSend 语义)
        this.dismissKeyboardAfterSend();
        // D-048:生成中 → FOLLOWUP 入队(编辑态同 — 编辑重发需等当前生成结束;
        //   入队即退出编辑态,排队消息按新消息派发,登记偏差:Android 无编辑态排队交互)
        if (this.sending) {
          this.enqueuePendingParts(inputParts, true);
          this.inputText = '';
          this.invalidatePicker(true);
          if (this.editingNodeId.length > 0) this.cancelEdit();
          return;
        }
        // 编辑态:走「编辑并重发」(编辑态不携带待发图片,对齐 Android 编辑仅文本)
        if (this.editingNodeId.length > 0) {
          this.inputText = '';
          this.invalidatePicker(true);
          this.sendEdit(text);
          return;
        }
        this.inputText = '';
        this.invalidatePicker(true);
        this.sendParts(inputParts);
      });
    });
  }

  private sendParts(
    inputParts: UIMessagePart[], dispatchMessageId: string = '',
    onPrepareFailed?: () => void,
  ): void {
    if (this.conversation === null) return;
    // 同步占位:ensureFull 是异步的,两次发送会在回调里才检查 sending,
    // 双双基于同一旧快照开跑并互相覆盖保存(此处占位后 runSendParts 里的赋值幂等)
    if (this.sending) return;
    this.sending = true;
    // Phase 5 全量 invariant:生成必须基于完整会话(窗口未全量时先补全再开跑)
    this.ensureFullConversationForMutation().then((): void => {
      if (this.conversation === null) {
        this.sending = false;
        if (onPrepareFailed !== undefined) onPrepareFailed();
        return;
      }
      this.runSendParts(inputParts, dispatchMessageId);
    }).catch((e: Error): void => {
      this.sending = false;
      this.errorMsg = e.message;
      if (onPrepareFailed !== undefined) onPrepareFailed();
    });
  }

  private runSendParts(inputParts: UIMessagePart[], dispatchMessageId: string = ''): void {
    if (this.conversation === null) return;
    // 单文本 part → 退化为 string(与既有 send 路径行为一致)
    let userInput: string | UIMessagePart[] = inputParts;
    if (inputParts.length === 1 && inputParts[0].type === 'text') {
      userInput = (inputParts[0] as UIMessagePartText).text;
    }
    this.sending = true;
    this.errorMsg = '';
    const operationAbort: EntryAbortController = new EntryAbortController();
    this.abortController = operationAbort;
    this.checkpointRegenerateNodeId = '';
    const operationToken: number = ++this.generationToken;
    const conv: Conversation = this.conversation;
    // 流式尾部起点 = 现有消息 + 即将入列的 user 消息
    const tailStart: number = currentMessages(conv).length + 1;
    // D-057:续跑模式(idle blocker「继续」/应答)— 不追加 user 消息,
    //   流式预览切换为末条消息(写回末条 assistant,regenTail 机制)
    let resumeMode: boolean = false;
    let resumeNodeId: string = '';
    // holder 模式:跨 then 传递 runtime(ArkTS 闭包收窄限制)
    const holder: RuntimeHolder = { rt: null };
    resolveChatRuntime(conv.assistantId)
      .then(async (rt: ChatRuntime | null): Promise<Conversation> => {
        holder.rt = rt;
        if (rt === null) {
          return Promise.reject(new Error('未配置可用的 Provider/模型(providers 未持久化或无可用 auth)'));
        }
        this.wireMcpAssistant(rt);
        this.warnIfToolAbilityMissing(rt);
        // D-132c:水合 subagent 设置 + 工具(buildToolLoop 前;parentToolsProvider 惰性)
        await this.prepareSubAgentTools(conv);
        const deps: ChatTurnDeps = {
          assistant: rt.assistant,
          finalTokenBudget: this.finalTokenBudgetOf(rt),
          // D-090:agentSoul 静态块首槽位(GenerationHandler.kt:721-724;
          //   buildAgentSoulPrompt 空白 → '' 自然滤除)
          agentSoul: buildAgentSoulPrompt(this.agentRuntime.agentSoulMarkdown),
          inputTransformers: this.buildInputTransformers(rt),
          // D-071b:视觉兜底门(模型 IMAGE 模态 + 有图才兜底,GenerationHandler.kt:431)
          modelSupportsImageInput: rt.modelSupportsImageInput,
          outputTransformers: [thinkTagTransformer, createEntryBase64ImageTransformer(), createMiniAppOutputTransformer({ enabled: (): boolean => this.miniAppSetting.enabled, repository: this.miniAppRepository() }), regexOutputTransformer], // ChatService.kt:179-184 序(MiniAppOutput 3 位) 依赖 this.miniAppRepository()
          provider: asChatStreamProvider(rt.api, () => rt.params),
          store: getChatRepository(),
          contextMessageSize: rt.contextMessageSize,
          // D-055:压缩引擎接管上下文(engine 内部编辑/规划/截断;
          //   chat_turn 侧 contextMessageSize 强制 0,避免双重截断)
          prepareContextMessages: this.buildPrepareContext(conv, rt),
          // D-077b:recentChats 动态块(每次组装重取)
          // D-085b:记忆召回块(动态序最前,GenerationHandler.kt:727)
          memorySystemBlocks: (): Promise<string[]> => this.loadMemorySystemBlocks(),
          extraSystemBlocks: (): Promise<string[]> => this.loadExtraSystemBlocks(),
          abortSignal: operationAbort.signal,
          // D-115:GenerationHandler.kt:357/475/621 — retrySetting =
          //   settings.agentRuntime.generationRetry(PreferencesStore.kt:181 持久化)
          retrySetting: makeGenerationRetrySetting({
            enabled: this.agentRuntime.generationRetry.enabled,
            maxRetries: this.agentRuntime.generationRetry.maxRetries,
          }),
          // D-050:重试状态(strings.xml:386 文案;null 清除)
          onRetryStatus: (status: string | null): void => {
            if (this.isGenerationOperationActive(operationToken)) {
              this.retryStatus = status ?? '';
            }
          },
          onUpdate: (msgs: UIMessage[]): void => {
            if (!this.isGenerationOperationActive(operationToken)) return;
            if (resumeMode) {
              if (msgs.length > 0) {
                this.regenNodeId = resumeNodeId;
                this.regenTail = msgs[msgs.length - 1];
                this.followStreamingUpdate();
              }
              return;
            }
            this.streamingTail = msgs.slice(tailStart);
            this.followStreamingUpdate();
          },
          // Phase 2:raw 快照捕获(onRawFlushSnapshot 节流观察点,未经 visual
          //   transforms;与 onUpdate 独立)— checkpoint 投影基,不扰动 UI
          onRawFlushSnapshot: (msgs: UIMessage[]): void => {
            if (!this.isGenerationOperationActive(operationToken)) return;
            this.lastRawSnapshot = msgs;
            this.captureStreamCheckpoint(msgs);
          },
        };
        // D-057:idle 工具阻塞(ChatService.kt:757/:838 — 派发前先解阻塞;
        //   「继续」批准/ask_user 应答 → 消息不入列直接续跑;否则取消陈旧工具)
        const isPureText: boolean = inputParts.every(
          (p: UIMessagePart): boolean => p.type === 'text');
        const answerText: string = inputParts
          .filter((p: UIMessagePart): boolean => p.type === 'text')
          .map((p: UIMessagePart): string => (p as UIMessagePartText).text)
          .join('\n');
        const blocker: IdleToolBlockerResult = resolveIdleToolBlocker(conv, answerText, isPureText);
        if (blocker.changed) {
          await this.auditQueueEvent(blocker.event ?? 'pending_tool_cancel', dispatchMessageId,
            blocker.blockingToolNames.join(','));
          if (blocker.shouldResume) {
            resumeMode = true;
            resumeNodeId = blocker.conversation.messageNodes.length > 0
              ? blocker.conversation.messageNodes[blocker.conversation.messageNodes.length - 1].id
              : '';
            const previous: Conversation | null = this.conversation;
            this.conversation = blocker.conversation;
            this.refreshRowsAfterConversationChange(previous, blocker.conversation);
            return getChatRepository().save(blocker.conversation)
              .then((): Promise<Conversation> => runToolLoopContinuation(
                blocker.conversation, deps, this.buildToolLoop(rt, blocker.conversation)));
          }
          return getChatRepository().save(blocker.conversation)
            .then((): Promise<Conversation> => runChatTurnWithTools(
              blocker.conversation, userInput, deps, this.buildToolLoop(rt, blocker.conversation)));
        }
        return runChatTurnWithTools(conv, userInput, deps, this.buildToolLoop(rt, conv));
      })
      .then((out: Conversation): void => {
        const stopped: boolean = operationAbort.signal.aborted;
        if (!this.isGenerationOperationActive(operationToken)) {
          this.finishInactiveGeneration(out, stopped);
          return;
        }
        this.finishGenerationCommon(out, stopped);
        this.refreshContextCompacts(); // D-110:压缩可能已写新摘要,刷新足迹
        // 自动命名 + 会话建议(非阻塞,对齐 Android 独立 launch)
        if (holder.rt !== null) {
          this.maybeAutoTitle(this.conversation as Conversation);
          this.maybeSuggestions(this.conversation as Conversation, holder.rt);
          this.maybeMemoryExtraction(this.conversation as Conversation, holder.rt);
        }
        // D-048:出队下一条(Android 循环:成功 → dequeueNext)
        this.drainPendingQueue();
      })
      .catch((e: Error): void => {
        if (!this.isGenerationOperationActive(operationToken)) return;
        this.errorMsg = this.compactErrorText(e);
        this.sending = false;
        this.refreshContextCompacts(); // D-110:压缩可能已写新摘要,刷新足迹
        this.abortController = null;
        this.streamingTail = [];
        this.regenTail = null;
        this.regenNodeId = '';
        this.lastRawSnapshot = [];
        // 图片已随 user 快照持久化,清空避免重发时重复
        this.invalidatePicker(true);
        // user 消息快照已持久化(chat_turn 语义),刷新显示
        getChatRepository().getById(conv.id)
          .then((saved: Conversation | null): void => {
            if (saved !== null) {
              const previous: Conversation | null = this.conversation;
              this.conversation = saved;
              this.refreshRowsAfterConversationChange(previous, saved);
              this.suggestions = saved.chatSuggestions;
            }
          })
          .catch((reloadError: Error): void => {
            hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'send reload failed: %{public}s', reloadError.message);
          })
          .then((): void => {
            // D-048:失败也继续出队(Android 循环逐条 catch → addError 后继续)
            this.drainPendingQueue();
          });
      });
  }

  // 当前索引(:523-527 — selected coerceIn(0,latest) ?: latest;-1 = 未选)
  private currentSandboxIndexOf(timeline: SandboxActivityUiState[]): number {
    const latest: number = timeline.length - 1;
    if (latest < 0) return -1;
    if (this.selectedSandboxIndex >= 0 && this.selectedSandboxIndex <= latest) {
      return this.selectedSandboxIndex;
    }
    return latest;
  }

  // SandboxStepStatusIcon(:763-793)— 16dp 圆:成功 accent 勾/失败红 ✕/
  //   取消 muted ✕/进行中 accent 勾(颜色为 theme 近似,登记)
  @Builder
  sandboxStatusIcon(status: string) {
    if (status === 'failed') {
      Text('✕').fontSize(9).fontColor(Color.White).textAlign(TextAlign.Center)
        .width(16).height(16).backgroundColor(ERROR).borderRadius(8)
    } else if (status === 'cancelled') {
      Text('✕').fontSize(9).fontColor(Color.White).textAlign(TextAlign.Center)
        .width(16).height(16).backgroundColor(INK3).borderRadius(8)
    } else {
      Text('✓').fontSize(9).fontColor(Color.White).textAlign(TextAlign.Center)
        .width(16).height(16).backgroundColor(this.accentColorStore).borderRadius(8)
    }
  }

  // SandboxStepArrow(:738-760)— 左/右 chevron,不可用淡化
  @Builder
  sandboxArrow(left: boolean, enabled: boolean, onTap: () => void) {
    Text(left ? '‹' : '›')
      .fontSize(13)
      .fontColor(enabled ? INK : INK4)
      .padding({ left: 4, right: 4 })
      .onClick((): void => {
        if (enabled) onTap();
      })
  }

  // SandboxStepPeek(:657-736)— 999 圆角 pill:图标+标题+中断+步骤箭头;
  //   点击开 sheet;canCancel = activity.canCancel && loading(:529)
  @Builder
  sandboxStrip(timeline: SandboxActivityUiState[]) {
    Row() {
      this.sandboxStatusIcon(this.currentSandboxActivity(timeline)?.status ?? 'running')
      Text(this.currentSandboxActivity(timeline)?.title ?? '')
        .fontSize(10.5).fontWeight(FontWeight.Medium).fontColor(INK)
        .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        .layoutWeight(1).margin({ left: 8 })
      if ((this.currentSandboxActivity(timeline)?.canCancel ?? false) && this.sending) {
        Text('✕')
          .fontSize(10).fontColor(this.accentColorStore)
          .padding({ left: 4, right: 4 })
          .onClick((): void => {
            // onCancelClick → vm.stopGeneration(:640-642;同输入区停止按钮)。
            // Phase 2:统一走 requestStop(收口 + 不抢先保存)
            this.requestStop();
          })
      }
      this.sandboxArrow(true, this.currentSandboxIndexOf(timeline) > 0, (): void => {
        this.selectedSandboxIndex = this.currentSandboxIndexOf(timeline) - 1;
      })
      Text(this.sandboxStepText(this.currentSandboxActivity(timeline) ??
        timeline[timeline.length - 1]))
        .fontSize(9.5).fontColor(INK3).maxLines(1)
      this.sandboxArrow(false,
        this.currentSandboxIndexOf(timeline) >= 0 &&
        this.currentSandboxIndexOf(timeline) < timeline.length - 1,
        (): void => {
          this.selectedSandboxIndex = this.currentSandboxIndexOf(timeline) + 1;
        })
    }
    .width('100%')
    .padding({ left: 6, right: 10, top: 3, bottom: 3 })
    .backgroundColor(SURFACE)
    .borderRadius(999)
    .border({ width: 1, color: LINE })
    .margin({ left: 20, right: 20, bottom: 4 })
    .onClick((): void => { this.sandboxSheetOpen = true; })
  }

  // SubAgent run sheet builder
  @Builder
  subAgentSheetBuilder() {
    SubAgentRunSheet({
      runId: this.subAgentSheetRunId,
      tools: this.subAgentSheetTools,
      liveText: this.subAgentLiveText,
      onDismiss: (): void => { this.closeSubAgentSheet(); },
    })
  }

  // Copy sheet builder
  @Builder
  copySheetBuilder() {
    if (this.copySheetMessage !== null) {
      ChatMessageCopySheet({
        message: this.copySheetMessage as UIMessage,
        onDismiss: (): void => { this.closeCopySheet(); },
      })
    }
  }

  // Phase 6:消息动作 sheet builder
  @Builder
  messageActionsSheetBuilder() {
    if (this.actionsSheetRow !== null) {
      ChatMessageActionsSheet({
        message: this.actionsSheetMessage(),
        isUser: this.actionsSheetIsUser(),
        isLastAssistant: this.actionsSheetIsLastAssistant(),
        onCopy: (): void => { this.copyActionsSheetMessage(); },
        onEdit: (): void => { this.editActionsSheetMessage(); },
        onRegenerate: (): void => { this.regenerateActionsSheetMessage(); },
        onFork: (): void => { this.forkActionsSheetMessage(); },
        onSpeak: (): void => { this.speakActionsSheetMessage(); },
        onShare: (): void => { this.shareActionsSheetMessage(); },
        onDelete: (): void => { this.deleteActionsSheetMessage(); },
        onDismiss: (): void => { this.closeActionsSheet(); },
      })
    }
  }

  // Phase 6:抽屉会话列表注入
  @Builder
  drawerConversationList(): void {
    ChatListGrouped({
      conversations: this.drawerConversations,
      selectedId: this.conversation !== null ? this.conversation.id : '',
      runningIds: this.isGenerationActive() && this.conversation !== null
        ? [this.conversation.id] : [],
      onOpenConversation: (id: string): void => { this.openDrawerConversation(id); },
      onDelete: (item: ConversationSummary): void => {
        this.deleteDrawerConversation(item);
      },
      onPin: (item: ConversationSummary): void => {
        getChatRepository().getById(item.id).then((conv: Conversation | null): Promise<void> => {
          if (conv === null) return Promise.resolve();
          return getChatRepository().save(patchConversation(conv, { isPinned: !conv.isPinned }));
        }).then((): void => { this.refreshDrawerConversations(); })
          .catch((e: Error): void => { this.errorMsg = e.message; });
      },
      onRegenerateTitle: (item: ConversationSummary): void => {
        this.regenerateDrawerTitle(item);
      },
    })
  }

  // Tool preview sheet builder
  @Builder
  toolPreviewSheetBuilder() {
    if (this.toolPreviewPart !== null) {
      ChatMessageToolPreviewSheet({
        part: this.toolPreviewPart as UIMessagePartTool,
        onDismiss: (): void => { this.closeToolPreview(); },
      })
    }
  }

  // P4:Context 用量紧凑详情(ChatInputUsage.kt 的 title/metrics/percent 子集;
  //   不发明大卡片 — 复用既有 contextUsageRatio/contextUsageText/contextRingColor)
  @Builder
  contextMeterSheet() {
    Column() {
      Row() {
        Text('Context').fontSize(14).fontColor(INK).fontWeight(FontWeight.Medium)
        Blank()
        Text(this.contextUsageText()).fontSize(META).fontColor(INK3)
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 16, bottom: 8 })

      // 5 段柱(放大版)
      Row() {
        ForEach([0, 1, 2, 3, 4], (i: number): void => {
          Text('')
            .layoutWeight(1).height(10).borderRadius(3)
            .backgroundColor(i < this.contextFilledBars() ? this.contextRingColor() : LINE)
            .margin({ right: 4 })
        }, (i: number): string => `sheetbar-${i}`)
      }
      .width('100%')
      .padding({ left: 16, right: 16 })

      Row() {
        Text(`${Math.round(this.contextUsageRatio() * 100)}%`)
          .fontSize(14).fontColor(this.contextRingColor()).fontWeight(FontWeight.Medium)
        Text('已用').fontSize(META).fontColor(INK3).margin({ left: 6 })
        Blank()
        if (this.compactActiveCount > 0) {
          Text(`压缩中 ${this.compactActiveCount} 项`).fontSize(META).fontColor(STATUS_BLUE)
        }
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 8, bottom: 16 })
    }
    .width('100%')
    .backgroundColor(SURFACE)
  }

  // SandboxActivitySheet(:1113+;web 预览路径 = webview 子系统未移植 P1)
  //   头部 :796-870 + 工具内容 :873-899 + 代码块 :1070-1110
  @Builder
  sandboxSheet(timeline: SandboxActivityUiState[]) {
    Column() {
      // 头部
      Row() {
        this.sandboxStatusIcon(this.currentSandboxActivity(timeline)?.status ?? 'running')
        Column() {
          Text(this.currentSandboxActivity(timeline)?.title ?? '')
            .fontSize(14).fontWeight(FontWeight.Medium).fontColor(INK)
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          Text(((): string => {
            const a: SandboxActivityUiState | null = this.currentSandboxActivity(timeline);
            if (a === null) return '';
            return a.runtime.trim().length > 0 ? a.runtime : a.toolName;
          })())
            .fontSize(11).fontColor(INK3).margin({ top: 2 })
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        .alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 10 })
        if ((this.currentSandboxActivity(timeline)?.canCancel ?? false) && this.sending) {
          Text('中断')
            .fontSize(11).fontColor(this.accentColorStore)
            .padding({ left: 10, right: 10, top: 6, bottom: 6 })
            .backgroundColor(ROW)
            .borderRadius(999)
            .onClick((): void => { this.requestStop(); })
        }
        this.sandboxArrow(true, this.currentSandboxIndexOf(timeline) > 0, (): void => {
          this.selectedSandboxIndex = this.currentSandboxIndexOf(timeline) - 1;
        })
        Text(this.sandboxStepText(this.currentSandboxActivity(timeline) ??
          timeline[timeline.length - 1]))
          .fontSize(13).fontColor(INK3).maxLines(1)
        this.sandboxArrow(false,
          this.currentSandboxIndexOf(timeline) >= 0 &&
          this.currentSandboxIndexOf(timeline) < timeline.length - 1,
          (): void => {
            this.selectedSandboxIndex = this.currentSandboxIndexOf(timeline) + 1;
          })
      }
      .width('100%').padding({ left: 12, right: 12, top: 9, bottom: 9 })
      .backgroundColor(SURFACE).borderRadius(22)
      .border({ width: 1, color: LINE })

      // 内容(:873-899)— 调用内容/调用结果两块
      Scroll() {
        Column() {
          Text('调用内容')
            .fontSize(11).fontColor(INK3).width('100%')
          Text(((): string => {
            const a: SandboxActivityUiState | null = this.currentSandboxActivity(timeline);
            if (a === null) return '';
            return a.inputPreview.trim().length > 0 ? a.inputPreview : a.title;
          })())
            .fontSize(12).fontColor(INK).fontFamily('JetBrainsMono')
            .width('100%').margin({ top: 4 })
            .padding(10).backgroundColor(ROW).borderRadius(10)
          Text('调用结果')
            .fontSize(11).fontColor(INK3).width('100%').margin({ top: 12 })
          Text(((): string => {
            const a: SandboxActivityUiState | null = this.currentSandboxActivity(timeline);
            if (a === null) return '';
            if (a.outputTail.trim().length > 0) return a.outputTail;
            if (a.status === 'running' || a.status === 'waiting_for_permission') {
              return '等待工具返回输出...';
            }
            return '无输出';
          })())
            .fontSize(12).fontColor(INK).fontFamily('JetBrainsMono')
            .width('100%').margin({ top: 4 })
            .padding(10).backgroundColor(ROW).borderRadius(10)
        }
        .width('100%').alignItems(HorizontalAlign.Start)
      }
        .align(Alignment.TopStart)
      .width('100%').layoutWeight(1).margin({ top: 12 })
    }
    .width('100%').height('100%').padding(16)
  }

  build() {
    Stack() {
    Column() {
      // 1:1 原型:头部悬浮层(默认全透明,scrollTop>4 玻璃化+浮出发丝线),
      //   消息区 paddingTop 补偿头部高度
      Stack({ alignContent: Alignment.Top }) {
      // cachedCount 预渲染可见区外 5 项,改善滚动流畅度
      // LazyForEach 替换 ForEach 需 IDataSource 重构(涉及 18+ 处 rows 赋值),
      //   登记为设备验证项(需真机验证 IDataSource 响应式 + 性能提升)
      List({ scroller: this.scroller }) {
        // Phase 5:历史加载占位 item(仅在有更早历史可加载时显示;计划 §6.8 —
        //   高 48vp,居中 META 文案 + DotLoading)
        if (!this.timelineLoadState.fullyLoaded) {
          ListItem() {
            Row() {
              DotLoading({ dotSize: 6, color: INK3 })
              Text('加载更早消息…')
                .fontSize(META).fontColor(INK3)
                .margin({ left: 8 })
            }
            .width('100%').height(48)
            .justifyContent(FlexAlign.Center)
          }
        }
        ForEach(this.rows, (row: MsgRow): void => {
          ListItem() {
              MessageRow({
                message: row.message,
                autoCloseThinking: this.autoCloseThinking,
                branchCount: row.branchCount,
              selectIndex: row.selectIndex,
              isLastAssistant: row.isLastAssistant,
              onSwitch: (idx: number): void => this.switchBranch(row.nodeId, idx),
              onRegenerate: (): void => this.regenerate(row.nodeId),
              onDelete: (): void => this.confirmDeleteMessage(row),
              onEdit: (): void => this.startEditMessage(row),
              onOpenActions: (): void => this.openActionsSheet(row),
              onFork: (): void => this.forkAt(row),
              onApproveTool: (toolCallId: string): void => this.handleToolApproval(toolCallId, true),
              onDenyTool: (toolCallId: string): void => this.openDenyDialog(toolCallId),
              onAnswerTool: (toolCallId: string, answer: string): void =>
                this.handleToolAnswer(toolCallId, answer),
              onOpenSubAgentRun: (rid: string, tools: UIMessagePartTool[]): void => {
                this.openSubAgentSheet(rid, tools);
              },
              onCopy: (): void => { this.openCopySheet(row.message); },
              onSpeak: (t: string): void => { speakText(t); },
              onFavorite: (t: string): void => { this.favoriteText(t); },
              onOpenToolPreview: (part: UIMessagePartTool): void => { this.openToolPreview(part); },
              onTextGrow: (): void => { this.followStreamingUpdate(); },
              onRunMiniApp: (appId: string): void => {
                router.pushUrl({ url: 'pages/MiniAppRunnerPage', params: { appId: appId } });
              },
              onModifyMiniApp: (prompt: string): void => {
                this.inputText = prompt;
              },
              onOpenMiniAppList: (): void => {
                router.pushUrl({ url: 'pages/MiniAppListPage' });
              },
            })
          }
          // D-041:regenerate 流式预览渲染在目标节点行下
          if (row.nodeId === this.regenNodeId && this.regenTail !== null) {
            ListItem() {
              MessageRow({
                message: this.regenTail as UIMessage,
                autoCloseThinking: this.autoCloseThinking,
                streaming: true,
                streamKeyTag: 'regen:',
                // 流式尾 = 正在生成的最后一条 assistant(footer 走 reserved 占位)
                isLastAssistant: true,
                onTextGrow: (): void => { this.followStreamingUpdate(); },
              })
            }
          }
        }, (row: MsgRow): string => row.nodeId + (this.darkMode ? "-d" : "-l"))
        ForEach(this.streamingTail, (m: UIMessage): void => {
          ListItem() {
            MessageRow({ message: m, autoCloseThinking: this.autoCloseThinking, streaming: true,
              isLastAssistant: true,
              onTextGrow: (): void => { this.followStreamingUpdate(); } })
          }
        }, (m: UIMessage): string => `tail-${m.id}`)
        if (this.sending) {
          ListItem() {
            Text('生成中…').fontSize(12).fontColor(INK3)
              .padding({ left: 20, right: 20, top: 4, bottom: 4 })
          }
        }
      }
      .width('100%').layoutWeight(1)
      .cachedCount(5)
      // P4:消息区底部安全区 padding = 导航条高 + TimelineBottomSafetyPadding(28vp,
      //   对齐 Android ChatList.kt:105);全屏布局下避免末条消息被导航条遮挡
      .padding({ top: this.statusBarHeight + 58, bottom: this.navBarHeight + 28 })
      .overlay(this.BottomFade(56), { align: Alignment.Bottom })
      // Phase 1:首帧定位在 List 挂载后执行(此时 scroller 已绑定)
      .onAppear((): void => {
        this.listReady = true;
        if (this.initialScrollArmed) this.performInitialScroll();
      })
      .onAreaChange((_oldValue: Area, newValue: Area): void => {
        const height: number = Number(newValue.height);
        if (Number.isFinite(height)) this.listViewportHeight = height;
      })
      // Phase 5:上滑到顶部 → 加载更早历史(计划 §6.6 step 4;防重入门在
      //   canLoadOlder/beginLoadOlder)
      .onReachStart((): void => {
        this.loadOlderTimeline();
      })
      .onScrollIndex((start: number, end: number, _center: number): void => {
        this.firstVisibleListIndex = start;
        this.lastVisibleListIndex = end;
      })
      .onTouch((event: TouchEvent): void => {
        if (event.type === TouchType.Down) this.userTouchActive = true;
        if (event.type === TouchType.Up || event.type === TouchType.Cancel) {
          this.userTouchActive = false;
          if (this.generationEndScrollPending) this.reconcileAtEnd();
        }
      })
      .onDidScroll((_scrollOffset: number, scrollState: ScrollState): void => {
        const y: number = this.scroller.currentOffset().yOffset;
        const solid: boolean = y > 4;
        if (solid !== this.headerSolid) {
          animateTo({ duration: 220, curve: Curve.EaseOut }, (): void => {
            this.headerSolid = solid;
          });
        }
        if (this.programmaticScrollPending && scrollState === ScrollState.Idle) {
          this.programmaticScrollPending = false;
        } else if ((scrollState === ScrollState.Scroll || scrollState === ScrollState.Fling) &&
          (this.userTouchActive || !this.programmaticScrollPending)) {
          this.handleUserScroll(this.isGenerationActive());
        }
      })
      .onScrollStop((): void => {
        this.programmaticScrollPending = false;
        this.reconcileAtEnd();
      })
      .divider({ strokeWidth: 1, color: LINE })

      // 悬浮头部(1:1 原型 padding 2px 20px 11px;透明 → 毛玻璃 blur14)
      Row() {
        // Android ChatHeader:菜单三线触发器；保留返回按钮供显式返回。
        Row() {
          Stack() {
            Column() {
              Text('').width(22).height(1.5).backgroundColor(INK).borderRadius(1)
              Text('').width(14).height(1.5).backgroundColor(INK).borderRadius(1)
                .margin({ top: 5 })
              Text('').width(19).height(1.5).backgroundColor(INK).borderRadius(1)
                .margin({ top: 5 })
            }
            .alignItems(HorizontalAlign.Start)
          }
          .width(36).height(36)
          .alignContent(Alignment.Center)
          .onClick((): void => { this.openDrawer(); })
          .accessibilityText('打开会话抽屉')

        }

        // Center:标题 + 模型行(flex 1, margin-left 2)
        Column() {
          if (this.editingTitle) {
            TextInput({ text: this.titleInput, placeholder: '会话标题' })
              .fontSize(TEXT_STYLES.sessionTitle.size)
              .fontWeight(TEXT_STYLES.sessionTitle.weight)
              .lineHeight(TEXT_STYLES.sessionTitle.lineHeight)
              .layoutWeight(1).height(36)
              .backgroundColor(SURFACE)
              .onChange((v: string): void => { this.titleInput = v; })
          } else {
            Text(this.title)
              .fontSize(TEXT_STYLES.sessionTitle.size)
              .fontWeight(TEXT_STYLES.sessionTitle.weight)
              .lineHeight(TEXT_STYLES.sessionTitle.lineHeight)
              .fontColor(INK)
              .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
              .width('100%')
          }
          // 模型行(1:1):厂商前缀模型名 mono + ⌄ 紧跟其后,整体左对齐
          Row() {
            Text(this.modelLabel.length > 0 ? this.modelLabel : '未配置模型')
              .fontSize(META).fontFamily('JetBrainsMono')
              .fontColor(this.modelLabel.length > 0 ? INK3 : this.accentColorStore)
              .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
            Icon({ name: 'chevron_down', iconSize: 11, color: INK4 })
              .rotate({ angle: this.modelMenuOpen ? 180 : 0 })
              .margin({ left: 6 })
          }
          .margin({ top: 1 })
        }
        .layoutWeight(1)
        .alignItems(HorizontalAlign.Start)
        .margin({ left: 10 })
        .onClick((): void => {
          if (!this.editingTitle) {
            this.toggleModelMenu();
          }
        })

        // Right:✎(或编辑态 保存/取消)
        if (this.editingTitle) {
          Row() {
            Text('保存').fontSize(META).fontColor(this.accentColorStore)
              .padding({ left: 8, right: 4 })
              .onClick((): void => this.saveTitle())
            Text('取消').fontSize(META).fontColor(INK3)
              .padding({ left: 8, right: 0 })
              .onClick((): void => { this.editingTitle = false; })
          }
        } else {
          Row() {
            // P4:Context 用量 meter(5 段柱 + %,复用既有 contextUsageRatio/
            //   contextFilledBars/contextRingColor 计算;点击弹紧凑详情 sheet)—
            //   置 header 右侧,+ 图标左侧,不遮挡模型行
            Row() {
              ContextUsageIndicator({
                usedTokens: this.estimatedContextTokens(),
                contextWindow: this.contextWindowTokens ?? 0,
                compactActive: this.compactActiveCount > 0,
                lineColor: LINE,
              })
            }
            .alignItems(VerticalAlign.Center)
            .padding({ left: 6, right: 6, top: 6, bottom: 6 })
            .borderRadius(8)
            .margin({ right: 10 })
            .onClick((): void => { this.contextMeterOpen = true; })
            .accessibilityText(this.contextUsageText())
            // Right:「+」新对话(对齐参考图;40 热区)
            Row() {
              Icon({ name: 'plus', iconSize: 22, color: INK })
            }
            .width(40).height(40)
            .justifyContent(FlexAlign.Center)
            .alignItems(VerticalAlign.Center)
            .onClick((): void => { this.newChat(); })
            .accessibilityText('新建对话')
          }
          .alignItems(VerticalAlign.Center)
        }
      }
      .width('100%')
      .padding({ left: 20, right: 20, top: this.statusBarHeight + 2, bottom: 11 })
      .border({ width: { bottom: 1 }, color: { bottom: this.headerSolid ? LINE : Color.Transparent }, style: { bottom: BorderStyle.Dotted } })
      .backgroundColor(this.headerSolid ? BG_GLASS : Color.Transparent)
      .backdropBlur(this.headerSolid ? 14 : 0)
      if (this.jumpButtonVisible && this.showMessageJumper) {
        Stack() {
          Icon({ name: 'arrow_down_to_line', iconSize: 20, color: INK })
        }
        .width(40).height(40)
        .position({ right: 20, bottom: 16 })
        .backgroundColor(SURFACE2)
        .borderRadius(20)
        .border({ width: 1, color: LINE })
        .shadow({ radius: 8, color: '#33000000', offsetY: 2 })
        .alignContent(Alignment.Center)
        .onClick((): void => { this.jumpToBottom(); })
        .accessibilityText('回到消息底部')
      }
      }
      .layoutWeight(1)
      .width('100%')

      // P4:错误提示可关闭条(Android ErrorCard 语义 — 紧凑行 + 右侧 ×,
      //   关闭后 errorMsg='',不改变既有 error 语义/设置点)
      if (this.errorMsg.length > 0) {
        Row() {
          Text(this.errorMsg)
            .fontSize(12).fontColor(ERROR)
            .layoutWeight(1)
            .maxLines(2)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
          Icon({ name: 'close', iconSize: 14, color: INK3 })
            .padding({ left: 8 })
            .onClick((): void => { this.errorMsg = ''; })
            .accessibilityText('关闭错误提示')
        }
        .width('100%')
        .padding({ left: 20, right: 20, top: 4, bottom: 4 })
      }

      // D-050:重试状态(strings.xml:386 文案,重试等待期间显示)
      if (this.retryStatus.length > 0) {
        Text(this.retryStatus)
          .fontSize(12).fontColor(this.accentColorStore)
          .padding({ left: 20, right: 20, top: 4, bottom: 4 })
      }

      // 会话建议 chips(D-026):生成完成后刷新;点击填入输入框
      if (this.suggestions.length > 0 && !this.sending) {
        Scroll() {
          Row() {
            ForEach(this.suggestions, (s: string): void => {
              Text(s)
                .fontSize(12).fontColor(INK2)
                .maxLines(1)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
                .padding({ left: 10, right: 10, top: 6, bottom: 6 })
                .backgroundColor(SURFACE)
                .borderRadius(12)
                .margin({ right: 8 })
                // Android ChatPage.kt:812-830:点击 = 填入输入框;
                //   长按 = 直接发送(D-042);生成中 → FOLLOWUP 入队(D-048);
                //   D-080:模型空值门(:823/:864/:880,建议文本非斜杠恒要模型)
                .onClick((): void => { this.inputText = s; })
                .gesture(
                  LongPressGesture()
                    .onAction((): void => {
                      if (this.sending) {
                        this.gateModelConfigured((_choice: ChatProviderChoice): void => {
                          this.enqueuePendingParts([{ type: 'text', text: s, metadata: null }], true);
                        });
                        return;
                      }
                      this.inputText = s;
                      this.send();
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
        .margin({ bottom: 6 })
      }

      // D-048:排队消息 chips(Android ChatListIndicators pending 区,可逐条取消)
      if (this.pendingRows.length > 0) {
        Scroll() {
          Row() {
            Text('排队中')
              .fontSize(11).fontColor(INK3)
              .margin({ right: 8 })
            ForEach(this.pendingRows, (row: PendingChipRow): void => {
              Row() {
                Text(row.preview)
                  .fontSize(12).fontColor(INK2)
                  .maxLines(1)
                  .textOverflow({ overflow: TextOverflow.Ellipsis })
                  .constraintSize({ maxWidth: 180 })
                Text('✕')
                  .fontSize(11).fontColor(INK3)
                  .padding({ left: 6 })
                  .onClick((): void => {
                    this.cancelPendingById(row.id).catch((e: Error): void => {
                      this.errorMsg = e.message;
                    });
                  })
              }
              .padding({ left: 10, right: 8, top: 5, bottom: 5 })
              .backgroundColor(SURFACE2)
              .borderRadius(12)
              .margin({ right: 8 })
            }, (row: PendingChipRow): string => row.id)
          }
          .padding({ left: 20, right: 20 })
        }
        .align(Alignment.TopStart)
        .scrollable(ScrollDirection.Horizontal)
        .scrollBar(BarState.Off)
        .width('100%')
        .margin({ bottom: 6 })
      }

      // D-038:待发图片预览(可移除);D-111:点按 = inspectImage 状态检查,
      //   blocked → errorMsg(ChatInputAttachments.kt:onStatusClick,toast 等价面)
      if (this.pendingImages.length > 0) {
        Scroll() {
          Row() {
            ForEach(this.pendingImages, (url: string, i: number): void => {
              Stack() {
                Image(url)
                  .width(64).height(64)
                  .objectFit(ImageFit.Cover)
                  .borderRadius(8)
                  .onClick((): void => { this.inspectPendingImage(url); })
                  .accessibilityText(`查看第 ${i + 1} 张待发图片`)
                Text('✕')
                  .fontSize(10).fontColor('#FFFFFF')
                  .width(28).height(28)
                  .textAlign(TextAlign.Center)
                  .margin({ top: 4, right: 4 })
                  .backgroundColor(OVERLAY)
                  .borderRadius(14)
                  .onClick((): void => { this.removePendingImage(i); })
                  .accessibilityText(`删除第 ${i + 1} 张待发图片`)
              }
              .alignContent(Alignment.TopEnd)
              .margin({ right: 8 })
            }, (url: string, i: number): string => `img-${i}-${url}`)
          }
          .padding({ left: 20, right: 20 })
        }
        .align(Alignment.TopStart)
        .scrollable(ScrollDirection.Horizontal)
        .scrollBar(BarState.Off)
        .width('100%')
        .margin({ bottom: 6 })
      }

      // D-122:沙盒活动 peek(ChatInputSandbox.kt SandboxStepPeek)—
      //   时间线非空才显示(:516-522 模式门已在 sandboxTimeline 内)
      if (this.sandboxTimeline().length > 0) {
        this.sandboxStrip(this.sandboxTimeline())
      }

      // D-110:ContextUsageIndicator 已上移至 ChatHeader(ContextMeter 5 段柱 + %)

      // P4:输入区底部安全区(沉浸式全屏下不被导航条遮挡);
      //   Android ChatInput 沉浸式基准 = 安全区底部 + 10vp 呼吸
      Column() {
        // 第二梯队 E:ChatInput 组件替换内联输入行
        ChatInput({
          text: this.inputText,
          sending: this.sending,
          editing: this.editingNodeId.length > 0,
          hasImages: this.pendingImages.length > 0,
          subAgentEnabled: this.subAgentEnabled,
          subAgentMode: this.subAgentMode,
          quickMessages: this.quickMessages,
          // 1:1 原型 send 键 color/bg .18s ease:输入变化经 animateTo 驱动
          onTextChange: (v: string): void => {
            animateTo({ duration: 180, curve: Curve.EaseOut }, (): void => {
              this.inputText = v;
            });
          },
          onSend: (): void => { this.send(); },
          onStop: (): void => {
            // Phase 2:统一停止入口(abort + 收口标记 + steer→followup;
            //   不抢先保存 — 生成完成 .then 单次收口)
            this.requestStop();
          },
          onSendWithoutAnswer: (): void => { this.sendWithoutAnswer(); },
          onPickImage: (): void => { this.pickImages(); },
          onCancelEdit: (): void => { this.cancelEdit(); },
          onClearInput: (): void => { this.inputText = ''; },
          onCompactContext: (): void => { this.compactCurrentConversation(); },
          onInsertText: (t: string): void => { this.inputText = t; },
        })
      }
      .width('100%')
      .padding({ bottom: this.navBarHeight + 10 })
    }
    .width('100%').height('100%').backgroundColor(BG)

    // 空状态 hero(1:1 原型): 居中 Amber▌ 词标 36px 陶土光标闪烁 +
    //   问候句 3.2s 淡入淡出轮换;光学中心上移(margin bottom 128)
    if (this.rows.length === 0 && !this.sending) {
      Column() {
        Row() {
          DotText({ text: 'AMBER', dotScale: 3.6, color: INK, glow: true })
          Text('')
            .width(12).height(30).backgroundColor(this.accentColorStore).borderRadius(2)
            .margin({ left: 4 })
            .opacity(this.cursorOn ? 1 : 0)
        }
        Text(GREETINGS[this.greetIdx])
          .fontSize(15).fontColor(INK3)
          .margin({ top: 14 })
          .opacity(this.greetOpacity)
      }
      .alignItems(HorizontalAlign.Center)
      .width('100%')
      .margin({ bottom: 128 })
    }

    // TopModelMenu 覆盖层(Android TopModelMenu.kt:Box { scrim; AnimatedVisibility(panel) })
    //   Stack 子节点序:主 Column(底) → 本覆盖层 → deny-dialog(顶,确保拒绝弹窗压在菜单之上)。
    //   组件自含全屏 dimmed scrim(28% 黑,点击 = onClose)+ 贴顶面板。
    if (this.modelMenuOpen) {
      TopModelMenu({
        groups: this.modelMenuGroups,
        currentProviderId: this.modelMenuCurrentProvider,
        currentModelId: this.modelMenuCurrentModel,
        reasoningLevels: this.reasoningLevels,
        currentReasoningLevel: this.currentReasoningLevel,
        onReasoningLevelChange: (level: string): void => { this.handleReasoningLevelChange(level); },
        onSelect: (providerId: string, modelId: string): void => {
          this.selectModelFromMenu(providerId, modelId);
        },
        onClose: (): void => { this.closeModelMenu(); },
        onOpenFullPicker: (): void => {
          this.closeModelMenu();
          router.pushUrl({ url: 'pages/ChatModelPickerPage' });
        },
        onOpenProviderSettings: (): void => {
          this.closeModelMenu();
          router.pushUrl({ url: 'pages/ChatProviderSettingsPage' });
        },
      })
    }

    if (this.drawerOpen) {
      ChatDrawer({
        show: true,
        userNickname: this.userNickname,
        userAvatar: this.userAvatar,
        onNewChat: (): void => { this.newChat(); },
        onOpenBoard: (): void => { this.openDrawerBoard(); },
        onOpenApps: (): void => { this.openDrawerApps(); },
        onOpenSettings: (): void => { this.openDrawerSettings(); },
        onSearchText: (text: string): void => { this.refreshDrawerConversations(text); },
        onDismiss: (): void => { this.closeDrawer(); },
        onEditNickname: (name: string): void => { this.editDrawerNickname(name); },
        conversationList: (): void => { this.drawerConversationList(); },
      })
    }

    // ===== D-108:拒绝理由弹窗(ToolDenyReasonDialog :428-461 逐字) =====
    //   title '拒绝工具执行'/hint '原因（可选）'/confirm '拒绝'/dismiss '取消'
    //   (strings.xml:204/:203/:202);AlertDialog → Stack overlay(同 D-101 先例)
    if (this.denyDialogToolCallId.length > 0) {
      Column() {
        Column() {
          Text('拒绝工具执行')
            .fontSize(15).fontWeight(FontWeight.Medium).fontColor(INK)
            .width('100%').margin({ bottom: 10 })
          TextArea({ text: this.denyReasonText, placeholder: '原因（可选）' })
            .width('100%').fontSize(13).fontColor(INK)
            .constraintSize({ minHeight: 88, maxHeight: 160 })
            .backgroundColor(SURFACE2).borderRadius(10)
            .onChange((v: string): void => { this.denyReasonText = v; })
          Row() {
            Blank()
            Text('取消')
              .fontSize(14).fontColor(INK3)
              .padding({ left: 20, right: 20, top: 8, bottom: 8 })
              .onClick((): void => { this.dismissDenyDialog(); })
            Text('拒绝')
              .fontSize(14).fontColor(ERROR)
              .padding({ left: 20, right: 8, top: 8, bottom: 8 })
              .onClick((): void => { this.confirmDenyDialog(); })
          }
          .width('100%').margin({ top: 12 })
        }
        .width('86%')
        .padding(18)
        .backgroundColor(SURFACE)
        .borderRadius(16)
        .border({ width: 1, color: LINE })
        // 消费点击,防穿透到遮罩 onClick(遮罩点击 = onDismissRequest)
        .onClick((): void => {})
      }
      .width('100%').height('100%')
      .justifyContent(FlexAlign.Center)
      .backgroundColor(OVERLAY)
      .onClick((): void => { this.dismissDenyDialog(); })
    }
    }
    .width('100%').height('100%')
    // 1:1 原型 slideInR .28s ease-out:整页入场 translateX 20→0 + opacity 0→1
    //   乘 themeTick 读取建依赖:主题变更 onPageShow 后 build 重读令牌
    .opacity(this.pageEnterOpacity * (this.themeTick >= 0 ? 1 : 1))
    .translate({ x: this.pageEnterX })
    .onAppear((): void => {
      animateTo({ duration: 280, curve: Curve.EaseOut }, (): void => {
        this.pageEnterOpacity = 1;
        this.pageEnterX = 0;
      });
    })
    // D-122:沙盒活动 sheet(sandboxOverlayOpen → sandboxSheetOpen)
    .bindSheet(this.sandboxSheetOpen, this.sandboxSheet(this.sandboxTimeline()), {
      height: SheetSize.LARGE,
      onDisappear: (): void => { this.sandboxSheetOpen = false; },
    })
    // SubAgent run sheet
    .bindSheet(this.subAgentSheetOpen, this.subAgentSheetBuilder(), {
      height: SheetSize.LARGE,
      onDisappear: (): void => { this.closeSubAgentSheet(); },
    })
    // Copy sheet
    .bindSheet(this.copySheetOpen, this.copySheetBuilder(), {
      height: SheetSize.MEDIUM,
      onDisappear: (): void => { this.closeCopySheet(); },
    })
    // Message actions sheet
    .bindSheet(this.actionsSheetOpen, this.messageActionsSheetBuilder(), {
      height: SheetSize.LARGE,
      onDisappear: (): void => { this.clearActionsSheet(); },
    })
    // Tool preview sheet
    .bindSheet(this.toolPreviewSheetOpen, this.toolPreviewSheetBuilder(), {
      height: SheetSize.LARGE,
      onDisappear: (): void => { this.closeToolPreview(); },
    })
    // P4:Context 用量详情 sheet(5 段柱点击打开)
    .bindSheet(this.contextMeterOpen, this.contextMeterSheet(), {
      height: SheetSize.MEDIUM,
      onDisappear: (): void => { this.contextMeterOpen = false; },
    })
  }
}
