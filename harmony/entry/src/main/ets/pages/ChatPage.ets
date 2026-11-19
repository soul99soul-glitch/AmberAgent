// ChatPage — 聊天页:消息流(text/reasoning/tool)+ 输入发送 + 流式更新
// 回路: runChatTurn(chat-domain) + OpenAIChatApi(RcpHttpClient)+ RDB 仓库(D-021)
// D-024:assistant 分支 — ◀ n/m ▶ 切换(selectMessageBranch) + 末条「重新生成」
//   (runRegenerateAt);D-041:regenerate 流式预览落地(目标节点行下渲染流式
//   快照,visual transforms 与 send 路径一致),MVP spinner 裁剪项清零
// D-038:图片输入 — PhotoViewPicker 选图 → data: URL → UIMessagePart[] 发送
//   (文本可空,图片不可空);渲染 Image(p.url)
// D-079b:物化即压缩(ImageCompress.compressImageAsDataUrl),对齐 FileEncoder 载荷
import {
  ACCENT, ACCENT_INK, BG, BODY, BODY2, ERROR, HEADER, INK, INK2, INK3,
  INK4, LABEL, LINE, LINE2, META, MONO_SMALL, OVERLAY, RADIUS, SIGNAL, STATUS_AMBER, STATUS_BLUE, SURFACE,
  SURFACE2, TEXT_STYLES, USER_BG, USER_INK, MOTION_OVERLAY,
} from '../design/tokens.ets';

import { router, promptAction, window, KeyboardAvoidMode } from '@kit.ArkUI';
import { clearArtifactInteractionOutside } from '../platform_impl/ArtifactInteraction.ets';
import { picker } from '@kit.CoreFileKit';
import { camera, cameraPicker } from '@kit.CameraKit';
import { util } from '@kit.ArkTS';
import type { common } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { FONT_MONO, FONT_SANS } from '../design/fonts.ets';
import { inputMethod } from '@kit.IMEKit';
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import { pasteboard } from '@kit.BasicServicesKit';
import { systemShare } from '@kit.ShareKit';
import { uniformTypeDescriptor as utd } from '@kit.ArkData';
import type {
  Conversation, MessageNode, UIMessage, UIMessagePart, UIMessagePartDocument, UIMessagePartImage,
  UIMessagePartMiniApp, Assistant, PendingUserMessage, PendingUserMessageMode, DisplaySetting,
  MessageSearchHit, UIMessagePartTool, ToolApprovalVerdict, ToolApprovalPartLocator,
  AgentTool, MessageChunk, OpenAIChatApi, AskedAnswers, ConversationCompact, ImageGuardSettings,
  VisionModelHealth, VisionProbeDeps, ProviderModel, ProviderSetting, SandboxActivityUiState,
  InteractiveTurnRunResult, ChatTimelineRow, SmartUiDocument, SmartUiDraftSource, SmartUiScalar,
  SmartUiStateValue, ChatEpisodeLedger, ChatEpisodeRecord,
} from '@amber/chat-domain';
import {
  currentMessages, makeConversation, newId, seedConversationWithPresets, nodeCurrentMessage,
  selectMessageBranch, deleteMessage, editMessage, buildChatTimelineRows, messageSignature, forkConversation, patchConversation,
  runAutoTitle, makeUserMessage, toText, messageCopyText, makeTextGenerationParams, pendingPreviewText, convertSteerToFollowup,
  invalidateCompacts, copyValidCompactsToConversation, ContextCompactionFailedError, recoverInterruptedRecipes,
  recoverInterruptedPlugins, recoverInterruptedWebMountGoals, estimateConversationInputTokens, formatNumberInt,
  formatContextTokens, nativeTokenizerForModel, loadSearchPrefs, SearchSettings, toolInputDisplayTitle, toolStaticLabel, toolInputAsJson,
  buildMentionRoleItems, MentionRoleItem, ConversationSummary, toChatModel,
  firstImageBlockingIssueForSend, inspectImageAttachment, imageAttachmentBlocksSend,
  reasoningLevelForModel, withReasoningLevelForModel, defaultReasoningLevelForModel, reasoningLevelsForModel,
  deriveSandboxActivities, mergeSandboxTimeline, idleSandboxActivity, withStepProgress, isActiveOperation,
  buildTopModelMenuGroups, loadProviders, loadDisplaySetting, makeDisplaySetting, isCloseEnoughToBottom,
  FOLLOW_BOTTOM_TOLERANCE_VP, makeMiniAppSetting, loadMiniAppSetting, runInteractiveTurn, type KernelPhase,
  appendSmartUiDraft, appendSmartUiContinuation, isExplicitSmartUiContinuation,
} from '@amber/chat-domain';
import type { MiniAppSetting } from '@amber/chat-domain';
import type { ConversationRecap, ConversationRecapNode, RecapState } from '@amber/chat-domain';
import { projectRecap, recapEligible, recapIsStale } from '@amber/chat-domain';
import { getConversationRecapService } from '../platform_impl/ConversationRecapSupport.ets';
import { ChatRecapPanel } from '../components/ChatRecapPanel.ets';
import { MarkdownText } from '../components/MarkdownText.ets';
import type { TopModelMenuGroup, ReasoningLevelOption, ReasoningLevel } from '@amber/chat-domain';
import {
  getChatRepository, getChatAssistants, resolveChatRuntime, ChatRuntime, getConversationRunService,
  getConversationTaskRunner,
  getChatKvStore, getCompactStore, ChatProviderChoice, prepareProviderApi, readTaskModelReference, TASK_TITLE_MODEL_KEY, TASK_COMPRESS_MODEL_KEY,
  resolveTaskModelReference, loadTaskPrompts, TaskPrompts, initMcpManager, getAgentTaskTools,
  getAgentCronTools, hydrateSubAgentSetting, getSubAgentManager, isModelCouncilEnabled,
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

import { compressImageAsDataUrl } from '../platform_impl/ImageCompress.ets';
import type { TokenUsage } from '@amber/chat-domain';
import { EntryAbortController } from '../platform_impl/EntryAbortController.ets';
import { JevCompletionNoticeView } from '../components/JevCompletionNotice.ets';
import { jevUnverifiedChanges, jevVerificationComposerAction } from '@amber/chat-domain';
import type { JevCompletionNotice, JevCompletionFacts, JevVerificationComposerAction } from '@amber/chat-domain';
import { currentJevApprovalSubjectHash } from '../platform_impl/JevApprovalTriage.ets';
import { beginConversationRun, beginConversationRestore, hasActiveConversationRuns,
  conversationRestoreVersion } from '../platform_impl/ConversationRunGuard.ets';
import { resolveEffectiveChatChoice, saveEffectiveChatChoice } from '../platform_impl/ChatModelSelectionSupport.ets';
import { countNativeContextTokens } from '../platform_impl/NativeTokenCounter.ets';
import { mutateAssistant } from '../platform_impl/AssistantMutation.ets';
import { DotText } from '../components/ui/DotMatrix.ets';
import { markWantRoutingReady } from '../entryability/EntryAbility.ets';
import { ChatProgressiveEdgeBlur } from '../components/chat/ChatProgressiveEdgeBlur.ets';
import { ChatTopBarOverlay, type TopBarNoticeItem } from '../components/chat/ChatTopBarOverlay.ets';
import { AmberPersonaMedallion } from '../components/chat/AmberPersonaMedallion.ets';
import type { BottomDockTab } from '../components/chat/AmberBottomModeBar.ets';
import {
  loadEpisodeLedger, subscribeEpisodeLedger, startNewEpisode, sealIfIdleTimeoutExceeded,
  getCachedEpisodeLedger, activeEpisodeHeaderTitle, refineEpisodeSummaryInBackground,
  type RefinedEpisodeSummary,
} from '../platform_impl/chat/ChatEpisodeStore.ets';
import { listFavoriteNodeIds, setNodeFavorite } from '../platform_impl/FavoriteStore.ets';
import { ChatArtifactShelf } from '../components/ChatArtifactShelf.ets';
import type { ConversationArtifactIndex, ConversationArtifactSource, ConversationDocumentArtifact } from '@amber/chat-domain';
import { collectConversationArtifacts, artifactSourceNodeIndex } from '@amber/chat-domain';

import { vibrator } from '@kit.SensorServiceKit';
import { WebMountHost, getWebMountSession, WebMountSurface } from '../platform_impl/WebMountSession.ets';
import type { WebMountHookBinding } from '../platform_impl/WebMountSession.ets';

import { ChatInput } from '../components/ChatInput.ets';
import { ChatPendingQueueSheet } from '../components/ChatPendingQueueSheet.ets';
import type { ManualCompactionOutcome } from '../platform_impl/ContextCompactionSupport.ets';
import type { QuickMessage } from '../components/SlashCommandPanel.ets';
import { AgentMessageRow } from '../components/AgentMessageRow.ets';
import type { AgentMessageRowHostCapabilities } from '../components/AgentMessageRow.ets';
import { StreamingFrameRate } from '../components/StreamingFrameRate.ets';
import { NativeTimelineFollow } from '../components/NativeTimelineFollow.ets';
import { InteractiveMessageTimeline } from '../components/InteractiveMessageTimeline.ets';
import { SubAgentRunSheet } from '../components/SubAgentRunSheet.ets';
import { ChatMessageCopySheet } from '../components/ChatMessageCopySheet.ets';
import { ChatExportSheet } from '../components/ChatExportSheet.ets';
import { ChatCouncilRunSheet } from '../components/ChatCouncilRunSheet.ets';
import { ChatWorkspacePanel } from '../components/ChatWorkspacePanel.ets';
import type { ChatMessageArchiveSource } from '@amber/chat-domain';
import { ChatMessageToolPreviewSheet } from '../components/ChatMessageToolPreviewSheet.ets';
import { ChatMessageActionsSheet } from '../components/ChatMessageActionsSheet.ets';
import { AgentMessageActionsSheet } from '../components/AgentMessageActionsSheet.ets';
import { HomeListPane } from '../components/HomeListPane.ets';
import {
  isTwoPaneWindow, twoPaneListWidth, WINDOW_HEIGHT_VP_KEY, WINDOW_WIDTH_VP_KEY,
} from '../design/adaptive.ets';
import { sha256HexUtf8 } from '../platform_impl/EntryLocalToolPorts.ets';
import { ensureSearchSdk } from '../platform_impl/SearchSdkInit.ets';
import {
  probeEntryOcrHealth, loadOcrSeed, OcrSeed,
} from '../platform_impl/OcrSupport.ets';
import { encodeImageDetailed } from '../platform_impl/EntryImageEncoder.ets';
import {
  AgentRuntimeSnapshot, defaultAgentRuntimeSnapshot, loadAgentRuntimeSnapshot,
} from '../platform_impl/AgentRuntimePrefs.ets';

import { readDocumentPreview } from '../platform_impl/DocumentSupport.ets';

import type { MiniAppPlatform } from '../platform_impl/MiniAppPlatform.ets';
import { imageGenToolAvailable } from '../platform_impl/ImageGenTool.ets';

import { JsSandboxHost } from '../platform_impl/JsSandboxTool.ets';

import { createNativePluginJsTransport } from '../platform_impl/NativePluginJsTransport.ets';
import type { ConversationRunSnapshot, ConversationRunHandle, ConversationRunCommand,
  ConversationSendCommand, ConversationRegenerateCommand, ConversationResumeCommand,
  ConversationEditCommand, ConversationVerdictCommand } from '../platform_impl/ConversationRunService.ets';
import { bindConversationBrowserPage } from '../platform_impl/ConversationBrowserGuard.ets';
import { SubAgentActivityStrip } from '../components/SubAgentActivityStrip.ets';
import { cleanupDeletedConversation } from '../platform_impl/ManagedFileStore.ets';
import { ChatSmartUiSession } from '../platform_impl/chat/ChatSmartUiSession.ets';
import type { ChatSmartUiSourceContext, SmartUiContinuationCapture } from '../platform_impl/chat/ChatSmartUiSession.ets';
import { ChatComposerPane } from '../components/chat/ChatComposerPane.ets';
import { ChatTimelineView, resolveChatTimelineRow } from '../components/chat/ChatTimelineView.ets';
import type { ChatPresentationPorts } from '../platform_impl/chat/ChatConversationPresentation.ets';
import type { ChatComposerPorts } from '../platform_impl/chat/ChatComposerSession.ets';
import type { ChatTimelineCallbacks } from '../platform_impl/chat/ChatTimelineSession.ets';
import { chatCompactionErrorText } from '../platform_impl/chat/ChatCompactionSupport.ets';
import { ChatConversationPresentation } from '../platform_impl/chat/ChatConversationPresentation.ets';
import { ChatComposerSession } from '../platform_impl/chat/ChatComposerSession.ets';
import type { PendingDoc, PendingChipRow } from '../platform_impl/chat/ChatComposerSession.ets';
import { ChatCloudDialogueSession } from '../platform_impl/chat/ChatCloudDialogueSession.ets';
import { ChatModelContextSession } from '../platform_impl/chat/ChatModelContextSession.ets';
import { ChatModelMenuOverlay, ChatContextMeterOverlay, ChatCompactSummaryHost } from '../components/chat/ChatModelContextView.ets';
import { ChatRunDetailsSession } from '../platform_impl/chat/ChatRunDetailsSession.ets';
import { ChatRunDetailsView, ChatSandboxStrip, ChatRunActivityStrip, ChatToolDenyOverlay } from '../components/chat/ChatRunDetailsView.ets';
import { ChatMessageActionsSession } from '../platform_impl/chat/ChatMessageActionsSession.ets';
import { ChatMessageActionsView } from '../components/chat/ChatMessageActionsView.ets';
import { ChatConversationDrawerSession } from '../platform_impl/chat/ChatConversationDrawerSession.ets';
import { ChatConversationDrawerView } from '../components/chat/ChatConversationDrawerView.ets';
import { ChatArtifactSession } from '../platform_impl/chat/ChatArtifactSession.ets';
import { ChatArtifactPanels } from '../components/chat/ChatArtifactPanels.ets';
import { ChatTimelineSession, buildRows, buildRowsFromNodes, TIMELINE_PAGE_SIZE } from '../platform_impl/chat/ChatTimelineSession.ets';
import { getChatSmartUiDraftStore } from '../platform_impl/SmartUiDraftSupport.ets';
import { mimeByName, readUriBytes, loadAttachDataUrl } from '../platform_impl/chat/ChatAttachmentIO.ets';
import { observeGatewaySoulRevision, syncAgentSoulFromCloud } from '../platform_impl/AgentSoulSync.ets';
import { AgentEmbodimentSheet } from '../components/soul/AgentEmbodimentSheet.ets';
import { consecutiveTurnToolFailures, projectEpisodeLedger } from '@amber/chat-domain';
import { toggleConversationInHub } from '../platform_impl/chat/AmberHubStore.ets';

// D-122:沙盒 sheet 代码块底色(设置面 ROW 惯例同值)
interface ChatArchiveRouteParams { conversationId: string; nodeId: string; }

const ROW: string = SURFACE2;

// accent rgba 混色(建议 chips 描边等;与 tokens 令牌 #RRGGBB 配合)
// 1:1 原型空态 hero 轮换句(3.2s 淡入淡出)
const GREETINGS: string[] = [
  '今天想一起推进什么？', '随时告诉我，我来拆解处理。', '描述一下，就搭给你看。', '有需要我记住的事吗？', '让四个模型辩一场？',
];

const HILOG_DOMAIN: number = 0x0001;
const HILOG_TAG: string = 'DeepRead.ChatPage';

type MsgRow = ChatTimelineRow;

// holder 模式:跨 then 传递 runtime(ArkTS 闭包收窄限制 + arkts-no-obj-literals-as-types)
interface RuntimeHolder {
  rt: ChatRuntime | null;
}

// holder 模式:drain 的 append 失败时跨 then 回灌该 dispatch
interface PendingRequeueHolder {
  msg: PendingUserMessage | null;
}

interface KernelRunOutcome {
  conversation: Conversation;
  result: InteractiveTurnRunResult | null;
}

@Entry
@Component
struct ChatPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  // P4:底部导航条安全区高度(EntryAbility 全屏布局避让区 bottomRect,vp;0=手势导航全隐藏)
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  // 主题切换时触发整页重建(build 重读可变令牌)
  @StorageProp('darkMode') darkMode: boolean = false;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @StorageProp('agentSoulDisplayName') agentSoulDisplayName: string = 'Amber';
  @StorageProp('agentSoulTagline') agentSoulTagline: string = '';
  @StorageProp('agentSoulIsDefault') agentSoulIsDefault: boolean = true;
  @StorageProp('activeArtifactInteraction') activeArtifactInteraction: string = '';
  @StorageProp('chatStorageDegraded') storageDegraded: boolean = false;
  // 两栏(平板 / 折叠屏展开横屏):左侧首页窄栏 + 右侧本页聊天,见 design/adaptive.ets
  @StorageProp(WINDOW_WIDTH_VP_KEY) windowWidthVp: number = 0;
  @StorageProp(WINDOW_HEIGHT_VP_KEY) windowHeightVp: number = 0;
  @State homePaneVisible: boolean = false;
  // 两栏左栏高亮的会话(conversation 非响应式,在 attachConversationRun 统一入口同步)
  // conversation 非响应式:尾窗先置 conversationLoaded,全量会话晚一拍才到;
  //   到达时递增,让空态 hero 的判断重算(否则两步间若有重绘,hero 永远不出现)
  // 产物架(iOS artifact shelf 对齐:消息 part 派生的图片/文档/小应用陈列)
  // D0-12:同名文档版本对比(soul diff 同款诚实行级摘要)
  // Return-to-page forces rebuild, reads latest theme tokens (background pages in stack don't receive StorageProp updates)
  @State themeTick: number = 0;
  // 1:1 原型 slideInR .28s ease-out:整页入场
  @State pageEnterOpacity: number = 0;
  @State pageEnterX: number = 20;
  // 头部悬浮层:scrollTop>4 玻璃化
  // 空态 hero 问候语轮换(3.2s 淡入淡出)
  @State greetIdx: number = 0;
  @State greetOpacity: number = 1;
  private greetTimer: number = -1;
  private cursorTimer: number = -1;
  private pageAlive: boolean = false;
  // 页面被子路由覆盖时仍存活；IME 和异步 UI 刷新必须遵守可见性。
  private pageVisible: boolean = false;
  private hasShownOnce: boolean = false;
  @State greetName: string = '';
  @State cursorOn: boolean = true;
  // Phase 5:历史分页窗口 — this.presentation.conversation 恒全量(生成/checkpoint/上下文),
  //   rows 只承载尾部窗口 + 已上滑加载的历史页(展示层)
  // 最近一次历史页前插计数(锚点补偿已在加载回调内就地执行,无需持久读)
  // D0-10:@Watch 驱动草稿持久化(任何赋值点 — 发送清空/手动输入 — 都落 KV)
  // P2: native Smart UI state stays page-owned and keyed by message source identity.
  @State presentation: ChatConversationPresentation = new ChatConversationPresentation();
  @State composer: ChatComposerSession = new ChatComposerSession();
  @State cloudDialogue: ChatCloudDialogueSession = new ChatCloudDialogueSession();
  @State modelContext: ChatModelContextSession = new ChatModelContextSession();
  @State runDetails: ChatRunDetailsSession = new ChatRunDetailsSession();
  @State messageActions: ChatMessageActionsSession = new ChatMessageActionsSession();
  @State drawer: ChatConversationDrawerSession = new ChatConversationDrawerSession();
  @State artifacts: ChatArtifactSession = new ChatArtifactSession();
  @State timeline: ChatTimelineSession = new ChatTimelineSession();
  @State smartUi: ChatSmartUiSession = new ChatSmartUiSession();
  // D-087:任务模型选择快照(compress/title/suggestion;aboutToAppear 异步加载,
  //   与 ocrSeed/searchSettings 同档;null = 未解析或无可用模型 → 回退 chat 运行态)
  private compressChoice: ChatProviderChoice | null = null;
  private taskChoicesLoaded: boolean = false;
  // D-094:任务提示词快照(compress 同步工具构建位使用;aboutToAppear 加载,
  //   与 compressChoice/ocrSeed 同档;title/suggestion 走调用时新取)
  private taskPromptsCache: TaskPrompts | null = null;
  // D-070:搜索偏好(aboutToAppear 异步加载;SearchTools 工具组输入)
  // D0-9:@State 化(输入区搜索开关图标态需随 toggle 重绘)
  // D-130:cron 四工具(aboutToAppear 异步水合,与 searchSettings 同档;
  //   水合完成前的极早轮次不带 cron 工具 — 异步加载时序等价先例)
  private agentCronToolsCache: AgentTool[] = [];
  // D-131:AgentTaskTools 六件,与 cron 共用 AgentTaskStore/调度器缓存。
  private agentTaskToolsCache: AgentTool[] = [];
  // D-132c:SubAgentTools 五件(每会话动态挂载)。parentToolsProvider 为惰性闭包,
  //   读 baseToolsCache(在 buildToolLoop 内 step-4 设置),保证只暴露已 profile
  //   的 parent base tools,不含 subagent_* 自身。subagentEnabled + 会话存在才挂载。
  // D0-8:@提及候选角色(aboutToAppear/onPageShow 装配;空数组 = 输入区不启用面板)
  // M2.1:ReasoningPicker 段集 + 当前档位(模型感知;aboutToAppear 装配,选档位时刷新)
  // 当前模型内部 id(ReasoningPicker 记忆 key;refreshReasoningPicker 设置)
  // D-108:拒绝理由弹窗态(ToolDenyReasonDialog;空 = 关闭)
  // D-110:上下文用量指示(ContextUsageIndicator)— compacts/窗口快照 + 压缩活动计数
  // TopModelMenu:顶部模型快速切换下拉(Android TopModelMenu.kt)
  @State chatHeaderHeight: number = 108;
  @State composerHeight: number = 132;
  @State bottomDockTab: BottomDockTab = 'chat';
  @State dismissedNoticeIds: string[] = [];
  @State episodeHeaderTitle: string = '';
  @State embodimentSheetOpen: boolean = false;
  @State turnStepsSheetOpen: boolean = false;
  @State turnStepsTitle: string = '';
  @State turnStepsTools: UIMessagePartTool[] = [];
  @State turnStepsReasoning: string[] = [];
  private openingHubConversation: boolean = false;
  private unsubEpisodeLedger: (() => void) | null = null;
  private boundEpisodeConvId: string = '';
  // 装配请求由关闭/离页失效，迟到回调不能重开菜单或覆盖下一次请求。
  // D-038:待发图片(data: URL 物化,发送后清空)
  // D-041:regenerate 流式预览(目标节点行下渲染;完成后清空)
  // D-048:发送排队;D-052:KV 持久化(进程死亡恢复,PendingMessageStore 语义)
  // D-122:沙盒活动时间线(ChatPage.kt:500-536)— live 单槽订阅 + 步骤选择
  //   (-1 = 最新,:523-527 coerceIn 等价)+ sheet 开关
  // SubAgent run sheet
  // Copy sheet
  // Phase 6:消息长按动作 sheet
  // Phase 6:会话抽屉
  // Tool preview sheet
  // D-050:重试状态文案(strings.xml:386;null = 清除)
  // Retain raw service references for comparison, rather than ArkUI-observed part proxies.
  // Phase 2:停止生成收口 + 流式 checkpoint
  // 最后一次 raw 快照(onRawFlushSnapshot;checkpoint 投影基)
  // checkpoint 保存 promise 尾(串行化落库,不阻塞 provider chunk 回调)
  @StorageProp('appBackgrounded') @Watch('syncStreamingFrameRate') private appBackgrounded: boolean = false;

  private syncStreamingFrameRate(): void {
    this.presentation.setAppBackgrounded(this.appBackgrounded);
  }
  // D-072:agent_runtime 快照(transform 调用时读 = Android settingsFlow.value 语义)
  private agentRuntime: AgentRuntimeSnapshot = defaultAgentRuntimeSnapshot();
  // generate_image 装配门(execute 仍实时解析;aboutToAppear/onPageShow 探测刷新)
  // MiniApp:miniapp setting 快照(enabled 门;aboutToAppear 加载,
  //   transform 调用时读 = Android ctx.settings.agentRuntime.miniApp 语义)
  private miniAppSetting: MiniAppSetting = makeMiniAppSetting();
  // 页面退订可见状态；运行服务继续持有会话，离页不取消正在生成的内容。
  aboutToDisappear(): void {
    AppStorage.setOrCreate<string>('activeArtifactInteraction', '');
    this.pageAlive = false;
    this.pageVisible = false;
    this.presentation.dispose();
    this.composer.dispose();
    this.cloudDialogue.dispose();
    this.modelContext.dispose();
    this.runDetails.dispose();
    this.messageActions.dispose();
    this.drawer.dispose();
    this.artifacts.dispose();
    this.timeline.dispose();
    this.smartUi.dispose();
    if (this.unsubEpisodeLedger !== null) {
      this.unsubEpisodeLedger();
      this.unsubEpisodeLedger = null;
    }
    if (this.greetTimer !== -1) { clearInterval(this.greetTimer); this.greetTimer = -1; }
    if (this.cursorTimer !== -1) { clearInterval(this.cursorTimer); this.cursorTimer = -1; }
  }

  private stopGreetTimer(): void {
    if (this.greetTimer !== -1) clearInterval(this.greetTimer);
    this.greetTimer = -1;
    this.greetOpacity = 1;
  }

  private startGreetTimer(): void {
    if (!this.pageAlive || !this.pageVisible || this.greetTimer !== -1) return;
    this.greetTimer = setInterval((): void => {
      if (!this.pageAlive || !this.pageVisible) return;
      animateTo({ duration: 260, curve: Curve.EaseOut, onFinish: (): void => {
        if (!this.pageAlive || !this.pageVisible) return;
        this.greetIdx = (this.greetIdx + 1) % GREETINGS.length;
        animateTo({ duration: 260, curve: Curve.EaseOut }, (): void => { this.greetOpacity = 1; });
      } }, (): void => { this.greetOpacity = 0; });
    }, 3200);
  }

  onPageShow(): void {
    this.presentation.show();
    this.composer.show();
    this.cloudDialogue.show();
    this.modelContext.show();
    this.runDetails.show();
    this.messageActions.show();
    this.drawer.show();
    this.artifacts.show();
    this.timeline.show();
    this.smartUi.show();
    this.timeline.enableKeyboardAvoidance();
    this.pageVisible = true;
    this.homePaneVisible = true;
    markWantRoutingReady(this.getUIContext().getRouter());
    this.syncStreamingFrameRate();
    if (this.presentation.conversation !== null) this.presentation.attachConversationRun(this.presentation.conversation.id, true);
    this.startGreetTimer();
    this.presentation.refreshRestoredConversation().catch((e: Error): void => {
      if (this.pageAlive) this.presentation.errorMsg = e.message;
    });
    this.themeTick = Date.now();
    if (this.cursorTimer === -1) {
      this.cursorTimer = setInterval((): void => {
        if (this.pageAlive && this.pageVisible) this.cursorOn = !this.cursorOn;
      }, 525);
    }
    this.timeline.attachKeyboardListener();
    // D-070 补:从搜索设置页返回后重载偏好,否则继续用旧工具组/旧开关
    this.composer.refreshSearchSettings();
    // 从任务模型页返回后重探生图槽(工具装配门)
    imageGenToolAvailable().then((ok: boolean): void => {
      if (this.pageAlive) this.composer.imageGenConfigured = ok;
    }).catch((): void => {});
    if (!this.hasShownOnce) {
      this.hasShownOnce = true;
      return;
    }
    // P4:页面回到前台/从子页返回时刷新模型/上下文/显示设置快照
    //   (onPageShow 每次入页触发,与 aboutToAppear 同异步 helper;pageAlive 守卫防离页写 UI)
    this.modelContext.refreshEffectiveModelState();
    loadDisplaySetting(getChatKvStore()).then((setting: DisplaySetting): void => {
      if (!this.pageAlive) return;
      this.presentation.displaySettings = setting;
      this.presentation.userNickname = setting.userNickname;
      this.presentation.userAvatar = setting.userAvatar;
      this.timeline.enableAutoScroll = setting.enableAutoScroll;
      this.timeline.showMessageJumper = setting.showMessageJumper;
      this.presentation.enableGenerationNotification = setting.enableNotificationOnMessageGeneration;
      this.runDetails.loadWebMountGlobalToggles();
      this.presentation.autoCloseThinking = setting.autoCloseThinking;
    });
    // MiniApp:设置页改 enabled 后返回,刷新快照(transform enabled 门;
    //   aboutToAppear 仅首载,Android settingsFlow collect 持续语义的页面级近似)
    loadMiniAppSetting(getChatKvStore()).then((setting: MiniAppSetting): void => {
      if (this.pageAlive) this.miniAppSetting = setting;
    });
    // D-110:压缩/会话可能已变更,刷新足迹(生成中仅更新展示,不打断)
    this.modelContext.refreshContextCompacts();
    this.composer.refreshMentionRoles();
  }

  onPageHide(): void {
    AppStorage.setOrCreate<string>('activeArtifactInteraction', '');
    this.pageVisible = false;
    this.homePaneVisible = false;
    this.presentation.hide();
    this.composer.hide();
    this.cloudDialogue.hide();
    this.modelContext.hide();
    this.runDetails.hide();
    this.messageActions.hide();
    this.drawer.hide();
    this.artifacts.hide();
    this.timeline.hide();
    this.smartUi.hide();
    this.stopGreetTimer();
    if (this.cursorTimer !== -1) { clearInterval(this.cursorTimer); this.cursorTimer = -1; }
  }

  onBackPress(): boolean {
    if (this.messageActions.workspacePanelOpen) { this.messageActions.workspacePanelOpen = false; return true; }
    if (this.runDetails.councilSheetOpen) { this.runDetails.councilSheetOpen = false; return true; }
    if (this.presentation.recapOpen) { this.presentation.closeRecap(); return true; }
    if (this.messageActions.exportSheetOpen) { this.messageActions.exportSheetOpen = false; return true; }
    if (this.artifacts.documentPreviewOpen) { this.artifacts.documentPreviewOpen = false; return true; }
    if (this.runDetails.denyDialogOpen) { this.runDetails.dismissDenyDialog(); return true; }
    if (this.modelContext.modelMenuOpen || this.modelContext.modelMenuLoading) { this.modelContext.closeModelMenu(); return true; }
    if (this.modelContext.contextMeterOpen) { this.modelContext.contextMeterOpen = false; return true; }
    if (this.bottomDockTab !== 'chat') { this.bottomDockTab = 'chat'; return true; }
    if (this.drawer.drawerOpen) { this.drawer.closeDrawer(); return true; }
    return false;
  }

  private navigateBack(): void {
    if (!this.onBackPress()) router.back();
  }

  private twoPane(): boolean {
    return isTwoPaneWindow(this.windowWidthVp, this.windowHeightVp);
  }

  pageTransition(): void {
    // 两栏:左栏在前后两页里是同一份列表,换会话/返回首页都不滑动整页
    if (this.twoPane()) {
      PageTransitionEnter({ type: RouteType.None, duration: 0 })
      PageTransitionExit({ type: RouteType.None, duration: 0 })
    }
  }

  aboutToAppear(): void {
    const presentationPorts: ChatPresentationPorts = {
      repository: getChatRepository(), runService: getConversationRunService(), recapService: getConversationRecapService(), compactStore: getCompactStore(),
      bindBrowserPage: bindConversationBrowserPage,
      bindBrowserHooks: (): void => { this.runDetails.bindWebmountHooks(); },
      refreshFavorites: (id: string): void => { this.messageActions.refreshFavorites(id); },
      refreshModel: (): void => { this.modelContext.refreshEffectiveModelState(); },
      refreshOcr: (): void => { this.composer.reloadOcrSeed(); },
      refreshPendingRows: (): void => { this.composer.syncPendingRows(); },
      publishRunDetails: (snapshot: ConversationRunSnapshot): void => { this.runDetails.applyRunSnapshot(snapshot); },
      publishModelContext: (snapshot: ConversationRunSnapshot): void => { this.modelContext.applyRunSnapshot(snapshot); },
      reconcileSmartUi: (conversation: Conversation): void => { this.smartUi.reconcileSmartUiSources(conversation); },
      clearSmartUi: (): void => { this.smartUi.clearSmartUiMemory(); },
      timelineState: (): ConversationTimelineLoadState => this.timeline.timelineLoadState,
      clearTimeline: (): void => { this.timeline.setRows([]); },
      syncTimeline: (): void => { this.timeline.syncDataSource(); },
      projectConversation: (previous: Conversation | null, next: Conversation): void => {
        if (!this.timeline.timelineLoadState.initialized) {
          const start: number = Math.max(0, next.messageNodes.length - TIMELINE_PAGE_SIZE);
          this.timeline.setRows(buildRowsFromNodes(next.messageNodes.slice(start), this.timeline.rows));
          this.timeline.timelineLoadState = applyTailWindow(this.timeline.timelineLoadState, start, next.messageNodes.length - start, next.messageNodes.length);
        } else this.timeline.refreshRowsAfterConversationChange(previous, next);
      },
      resetTimeline: (conversation: Conversation): void => {
        this.timeline.setRows(buildRows(conversation, this.timeline.rows));
        this.timeline.timelineLoadState = applyTailWindow(this.timeline.timelineLoadState, 0, conversation.messageNodes.length, conversation.messageNodes.length);
      },
      followStreamingUpdate: (): void => { this.timeline.followStreamingUpdate(); },
      handleGenerateEnd: (): void => { this.timeline.handleGenerateEnd(this.timeline.atBottomApprox); },
      settleScroll: (): void => { if (!this.timeline.userTouchActive && this.timeline.followMode !== 'paused') this.timeline.scrollToBottom(); },
      isGenerationActive: (): boolean => this.timeline.isGenerationActive(),
      closeModelMenu: (): void => { this.modelContext.closeModelMenu(); },
      locateRecap: (full: Conversation, nodeId: string, index: number): void => {
        this.timeline.followMode = 'paused';
        if (index < this.timeline.timelineLoadState.oldestLoadedIndex) {
          this.timeline.setRows(buildRowsFromNodes(full.messageNodes.slice(index), this.timeline.rows));
          this.timeline.timelineLoadState = applyTailWindow(this.timeline.timelineLoadState, index, full.messageNodes.length - index, full.messageNodes.length);
        }
        this.timeline.positionToTargetNode(nodeId, index);
      },
      loadTaskPrompts: loadTaskPrompts,
      resolveTitleChoice: (): Promise<ChatProviderChoice | null> => readTaskModelReference(TASK_TITLE_MODEL_KEY).then((reference): Promise<ChatProviderChoice | null> => resolveTaskModelReference(reference)),
      prepareProviderApi: prepareProviderApi,
    };
    this.presentation.start(presentationPorts);
    const composerPorts: ChatComposerPorts = {
      kvStore: getChatKvStore(), runService: getConversationRunService(),
      currentConversation: (): Conversation | null => this.presentation.conversation,
      isSending: (): boolean => this.presentation.sending,
      currentQueue: (): PendingUserMessage[] => this.presentation.pendingQueue,
      currentCompletionNotice: (): JevCompletionNotice | null => this.presentation.completionNotice,
      setCompletionNotice: (notice: JevCompletionNotice | null): void => { this.presentation.completionNotice = notice; },
      error: (message: string): void => { this.presentation.errorMsg = message; },
      resolveChoice: resolveEffectiveChatChoice,
      loadOcrSeed: (): Promise<OcrSeed> => loadOcrSeed(getChatKvStore()), probeOcrHealth: probeEntryOcrHealth,
      submit: (command: ConversationRunCommand): boolean => this.presentation.submitConversationRun(command),
      dismissKeyboard: (): void => { this.timeline.dismissKeyboardAfterSend(); },
      autoScrollEnabled: (): boolean => this.timeline.enableAutoScroll,
      jumpToBottom: (): void => { this.timeline.jumpToBottom(); },
      captureContinuation: (text: string): SmartUiContinuationCapture | null => this.smartUi.captureSmartUiContinuation(text, this.smartUiSourceContext()),
      continuationAvailable: (): boolean => this.smartUi.latestSmartUiContinuationRuntime(this.smartUiSourceContext()) !== undefined,
      continuationCurrent: (capture: SmartUiContinuationCapture | null): boolean => this.smartUi.smartUiContinuationCurrent(capture, this.smartUiSourceContext()),
      loadMentionRoles: (): Promise<MentionRoleItem[]> => hydrateSubAgentSetting().then((setting): Promise<MentionRoleItem[]> =>
        isModelCouncilEnabled().then((enabled: boolean): MentionRoleItem[] => buildMentionRoleItems(setting.enabled, enabled, setting.mode, setting.customDefinitions))),
      abilityContext: (): common.UIAbilityContext => getContext(this) as common.UIAbilityContext,
      cloudDialogue: (): ChatCloudDialogueSession => this.cloudDialogue,
      sealIdleEpisode: async (conv: Conversation): Promise<void> => {
        if (getConversationTaskRunner().activeAndQueuedTasksForConversation(conv.id).length > 0) return;
        const messages: UIMessage[] = currentMessages(conv);
        const res = await sealIfIdleTimeoutExceeded(conv.id, messages, Date.now(), conv.messageNodes);
        if (res.sealed !== null) this.refineSealedEpisodeAsync(conv, res.sealed, messages);
      },
    };
    this.composer.start(composerPorts);
    this.cloudDialogue.start({
      kvStore: getChatKvStore(),
      currentConversation: (): Conversation | null => this.presentation.conversation,
      mutateConversation: (id: string, update: (latest: Conversation) => Conversation | null): Promise<Conversation | null> =>
        getConversationRunService().mutateStoredConversation(id, update),
      error: (message: string): void => { this.presentation.errorMsg = message; },
      autoScrollEnabled: (): boolean => this.timeline.enableAutoScroll,
      jumpToBottom: (): void => { this.timeline.jumpToBottom(); },
      taskRunner: getConversationTaskRunner,
      onDialogueConnected: (c): void => { syncAgentSoulFromCloud(c); },
      onSoulRevision: (rev, c): void => { observeGatewaySoulRevision(rev, c); },
    });
    this.modelContext.start({
      currentConversation: (): Conversation | null => this.presentation.conversation,
      error: (message: string): void => { this.presentation.errorMsg = message; },
      kvStore: getChatKvStore(), compactStore: getCompactStore(), getAssistants: getChatAssistants,
      resolveChoice: resolveEffectiveChatChoice, saveChoice: saveEffectiveChatChoice, mutateAssistant: mutateAssistant,
      compact: (id: string): Promise<ManualCompactionOutcome> => getConversationRunService().compact(id),
    });
    this.runDetails.start({
      loadSubAgentManager: getSubAgentManager,
      readSetting: (key: string): Promise<string | null> => getChatKvStore().get(key),
      runSnapshot: (id: string): ConversationRunSnapshot | null => getConversationRunService().snapshot(id),
      currentConversation: (): Conversation | null => this.presentation.conversation,
      currentStreamingTail: (): UIMessage[] => this.presentation.streamingTail,
      currentRegenTail: (): UIMessage | null => this.presentation.regenTail,
      sending: (): boolean => this.presentation.sending,
      error: (message: string): void => { this.presentation.errorMsg = message; },
      stop: (): void => { this.presentation.requestStop(); },
      submitVerdict: (command: ConversationVerdictCommand): boolean => this.presentation.submitConversationRun(command),
    });
    this.messageActions.start({
      repository: getChatRepository(), runService: getConversationRunService(), compactStore: getCompactStore(),
      currentConversation: (): Conversation | null => this.presentation.conversation,
      resolveRow: (row: MsgRow): MsgRow => resolveChatTimelineRow(this.timeline.rows, this.presentation.streamingTail, row),
      isSending: (): boolean => this.presentation.sending,
      ensureFullConversation: (): Promise<void> => this.presentation.ensureFullConversationForMutation(),
      listFavoriteNodeIds: listFavoriteNodeIds, setNodeFavorite: setNodeFavorite,
      onEdit: (row: MsgRow): void => { this.composer.startEditMessage(row); },
      onRegenerate: (nodeId: string): void => { this.presentation.regenerate(nodeId); },
      onForkSaved: (id: string): void => { router.pushUrl({ url: 'pages/ChatPage', params: { conversationId: id } }); },
      onLocateArchive: (full: Conversation, nodeId: string, index: number): void => {
        if (this.presentation.conversation?.id !== full.id) {
          router.pushUrl({ url: 'pages/ChatPage', params: { conversationId: full.id, nodeId: nodeId } });
          return;
        }
        this.presentation.conversation = full;
        this.smartUi.reconcileSmartUiSources(full);
        this.timeline.setRows(buildRows(full, this.timeline.rows));
        this.timeline.timelineLoadState = applyTailWindow(this.timeline.timelineLoadState, 0, full.messageNodes.length, full.messageNodes.length);
        this.timeline.followMode = 'paused';
        this.timeline.positionToTargetNode(nodeId, index);
      },
      onMessageCleanup: (id: string, messageId: string): void => { this.smartUi.cleanupSmartUiMessage(id, messageId); },
      onError: (message: string): void => { this.presentation.errorMsg = message; },
      getShareContext: (): common.UIAbilityContext => getContext(this) as common.UIAbilityContext,
    });
    this.drawer.start({
      repository: getChatRepository(), runService: getConversationRunService(),
      currentConversationId: (): string => this.presentation.conversation?.id ?? '',
      closeModelMenu: (): void => { this.modelContext.closeModelMenu(); },
      suspendIME: (): void => {
        this.timeline.detachKeyboardListener(); this.timeline.imeKeyboardHeightVp = 0;
        try { inputMethod.getController().stopInputSession().catch((_e: Error): void => {}); } catch (_e) {}
      },
      resumeIME: (): void => { if (this.pageVisible) this.timeline.attachKeyboardListener(); },
      onNavigate: (target: string, id: string): void => {
        if (target === 'newChat') { this.newChat(); return; }
        if (target === 'conversation') { router.replaceUrl({ url: 'pages/ChatPage', params: { conversationId: id } }); return; }
        if (target === 'deletedCurrent') { router.replaceUrl({ url: 'pages/ChatPage' }); return; }
        if (target === 'tasks') { this.bottomDockTab = 'tasks'; return; }
        if (target === 'persona') { this.embodimentSheetOpen = true; return; }
        const url: string = target === 'history' ? 'pages/HistoryPage'
          : target === 'council' ? 'pages/CouncilPage'
          : target === 'webmount' ? 'pages/SettingExperimentalWebMountPage'
          : target === 'apps' ? 'pages/MiniAppListPage'
          : target === 'settings' ? 'pages/SettingPage'
          : 'pages/ProfilePage';
        router.pushUrl({ url: url });
      },
      maybeAutoTitle: (conv: Conversation, force: boolean): Promise<void> => this.presentation.maybeAutoTitle(conv, force),
      cleanupConversation: (id: string): Promise<void> => this.smartUi.cleanupConversation(id),
      cleanupDeletedConversation: (full: Conversation): Promise<void> => cleanupDeletedConversation(full, getChatRepository(), getChatKvStore()),
      onError: (message: string): void => { this.presentation.errorMsg = message; },
    });
    this.artifacts.start(getChatRepository(), getConversationRunService(), {
      currentConversation: (): Conversation | null => this.presentation.conversation,
      fullyLoaded: (): boolean => this.timeline.timelineLoadState.fullyLoaded,
      enableGenerativeWidgets: (): boolean => this.presentation.displaySettings.enableGenerativeWidgets,
      error: (message: string): void => { this.presentation.errorMsg = message; },
      locateSource: (full: Conversation, nodeId: string, index: number): void => {
        this.presentation.conversation = full;
        this.smartUi.reconcileSmartUiSources(full);
        this.timeline.setRows(buildRows(full, this.timeline.rows));
        this.timeline.timelineLoadState = applyTailWindow(this.timeline.timelineLoadState, 0, full.messageNodes.length, full.messageNodes.length);
        this.timeline.followMode = 'paused';
        this.timeline.positionToTargetNode(nodeId, index);
      },
    }, readDocumentPreview);
    const timelinePorts: ChatTimelineCallbacks = {
      currentConversation: (): Conversation | null => this.presentation.conversation,
      currentStreamingTail: (): UIMessage[] => this.presentation.streamingTail,
      currentRegenTail: (): UIMessage | null => this.presentation.regenTail,
      currentRegenNodeId: (): string => this.presentation.regenNodeId,
      isSending: (): boolean => this.presentation.sending,
      currentSuggestions: (): string[] => this.presentation.suggestions,
      headerHeight: (): number => this.chatHeaderHeight,
      statusBarHeight: (): number => this.statusBarHeight,
      activeArtifactInteraction: (): string => this.activeArtifactInteraction,
      compactBoundaryCount: (id: string): number => this.modelContext.compactBoundaryCount(id),
    };
    this.timeline.start(this.getUIContext(), getChatRepository(), timelinePorts);
    this.timeline.setEpisodeLedgerProvider((): ChatEpisodeLedger | null => {
      const conv: Conversation | null = this.presentation.conversation;
      const convId: string = conv?.id ?? '';
      if (convId.length > 0 && convId !== this.boundEpisodeConvId) {
        this.bindEpisodeLedger(convId);
      }
      return conv !== null ? projectEpisodeLedger(getCachedEpisodeLedger(conv.id), conv.messageNodes) : null;
    });
    this.timeline.enableKeyboardAvoidance();
    this.pageAlive = true;
    this.smartUi.start(getChatSmartUiDraftStore(getChatKvStore()), (message: string): void => {
      if (this.pageAlive) this.presentation.errorMsg = message;
    });
    this.modelContext.refreshNativeContextEstimate();
    this.pageVisible = true;
    this.syncStreamingFrameRate();
    this.timeline.initialJumpSuppressedUntilUserScroll = false;
    // WebMount 会话宿主:首次 open 时按需挂载(避免常驻隐藏 Web)
    this.runDetails.bindWebmountHooks();
    this.runDetails.loadWebMountGlobalToggles();
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
    this.startGreetTimer();
    // 每次进入(含从选择器返回)刷新模型标签
    this.composer.refreshQuickMessages();
    hydrateSubAgentSetting().then((setting): void => {
      if (!this.pageAlive) return;
      this.composer.subAgentEnabled = setting.enabled; this.composer.subAgentMode = setting.mode;
    });
    // D-110:上下文窗口快照(ModelRegistry.MODEL_CONTEXT_WINDOW 注册表查询
    //   = P1 登记;取持久化 model.contextWindowTokens = Android 未知模型
    //   回退路径 :110-111)
    this.modelContext.refreshEffectiveModelState();
    // D-070:搜索 SDK 运行时装配(幂等)+ 搜索偏好加载(SearchTools 工具组接线)
    ensureSearchSdk();
    this.composer.refreshSearchSettings();
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
    this.composer.reloadOcrSeed();
    // D-087:compress 任务模型选择快照(resolveTaskChatModel(compressModelId),
    //   未设 = AUTO → findById 必失 → 回退当前 chat 模型,语义逐字)
    readTaskModelReference(TASK_COMPRESS_MODEL_KEY).then((reference): void => {
      if (!this.pageAlive) return;
      resolveTaskModelReference(reference).then((choice): void => {
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
      this.runDetails.setOperationPreviewMode(snap.operationPreviewMode);
    });
    // generate_image 装配门:生图槽解析到 OpenAI 兼容 image 模型才注册工具
    imageGenToolAvailable().then((ok: boolean): void => {
      if (this.pageAlive) this.composer.imageGenConfigured = ok;
    }).catch((): void => {});
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
    // D-073:display_setting 快照(placeholder nickname/user + Phase 1 滚动偏好)
    loadDisplaySetting(getChatKvStore()).then((setting: DisplaySetting): void => {
      if (!this.pageAlive || !this.pageVisible) return;
      this.presentation.displaySettings = setting;
      this.presentation.userNickname = setting.userNickname;
      this.presentation.userAvatar = setting.userAvatar;
      this.timeline.enableAutoScroll = setting.enableAutoScroll;
      this.timeline.showMessageJumper = setting.showMessageJumper;
      this.presentation.enableGenerationNotification = setting.enableNotificationOnMessageGeneration;
      this.runDetails.loadWebMountGlobalToggles();
      this.presentation.autoCloseThinking = setting.autoCloseThinking;
      if (!this.timeline.enableAutoScroll) this.timeline.followMode = 'idle';
    });
    this.composer.refreshMentionRoles();
    const params = router.getParams() as Record<string, string>;
    const convId: string = params ? (params['conversationId'] ?? '') : '';
    const recipeManualId: string = params ? (params['recipeManualToolCallId'] ?? '') : '';
    const pluginManualId: string = params ? (params['pluginManualToolCallId'] ?? '') : '';
    const manualId: string = recipeManualId.length > 0 ? recipeManualId : pluginManualId;
    this.presentation.loadedRestoreVersion = conversationRestoreVersion(convId);
    // Phase 1:首屏定位参数(最小兼容:initialMessageIndex 数字 index;nodeId 定位行)
    const initialIndexParam: string = params ? (params['initialMessageIndex'] ?? '') : '';
    const initialNodeIdParam: string = params ? (params['nodeId'] ?? '') : '';
    if (convId.length > 0) {
      this.composer.useExistingConversationDraft();
      const existingRun: ConversationRunSnapshot | null = getConversationRunService().snapshot(convId);
      this.presentation.attachConversationRun(convId);
      if (existingRun !== null && (existingRun.sending || existingRun.conversation !== null)) {
        this.composer.restoreInputDraft(`chat_input_draft/${convId}`);
        if (existingRun.conversation !== null && initialNodeIdParam.length > 0) {
          const index: number = existingRun.conversation.messageNodes.findIndex((node: MessageNode): boolean => node.id === initialNodeIdParam);
          if (index >= 0) this.timeline.positionToTargetNode(initialNodeIdParam, index); else this.timeline.applyInitialScroll(-1);
        } else if (existingRun.conversation !== null && initialIndexParam.length > 0) {
          const index: number = Number(initialIndexParam);
          const node: MessageNode | undefined = existingRun.conversation.messageNodes[index];
          if (Number.isInteger(index) && index >= 0 && node !== undefined) this.timeline.positionToTargetNode(node.id, index);
          else this.timeline.applyInitialScroll(-1);
        } else this.timeline.applyInitialScroll(-1);
        return;
      }
      // Phase 5:默认只加载尾部窗口(长会话首帧只渲染最近 TIMELINE_PAGE_SIZE 节点);
      //   this.presentation.conversation 仍经 getById 全量装载 — 生成/checkpoint/上下文引擎
      //   照旧读完整会话,rows 仅承载窗口(全量 invariant 见 ensureFullConversationLoaded)
      getChatRepository().getConversationTailWindow(convId, TIMELINE_PAGE_SIZE)
        .then((window): void => {
          if (!this.pageAlive || this.presentation.serviceOwnsConversation(convId)) return;
          this.timeline.timelineLoadState = applyTailWindow(
            this.timeline.timelineLoadState,
            window.oldestLoadedIndex, window.nodes.length, window.totalNodeCount);
          this.timeline.setRows(buildRowsFromNodes(window.nodes, this.timeline.rows));
          this.presentation.conversationLoaded = true;
        })
        .catch((e: Error): void => {
          hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'load tail window failed: %{public}s', e.message);
        })
        .then((): Promise<Conversation | null> => this.presentation.ensureFullConversationLoaded(convId))
        .then(async (loaded: Conversation | null): Promise<void> => {
          let full: Conversation | null = loaded;
          if (!this.pageAlive || this.presentation.serviceOwnsConversation(convId)) return;
          this.presentation.conversationLoaded = true;
          if (full === null) {
            if (this.timeline.rows.length === 0) this.presentation.errorMsg = '会话不存在';
            return;
          }
          if (!hasActiveConversationRuns([full.id])) {
            try {
              const recovered: Conversation = recoverInterruptedPlugins(
                recoverInterruptedWebMountGoals(recoverInterruptedRecipes(full)),
                createNativePluginJsTransport().hasSession);
              if (recovered !== full) {
                const release: () => void = beginConversationRestore([full.id]);
                try { await getChatRepository().save(recovered); } finally { release(); }
                if (!this.pageAlive) return;
                full = recovered;
                this.timeline.setRows(buildRowsFromNodes(full.messageNodes.slice(this.timeline.timelineLoadState.oldestLoadedIndex), this.timeline.rows));
              }
            } catch (error) {
              if (this.pageAlive) this.presentation.errorMsg = error instanceof Error ? chatCompactionErrorText(error) : String(error);
              return;
            }
          }
          this.presentation.conversation = full;
          this.smartUi.reconcileSmartUiSources(full);
          this.presentation.conversationArrivedTick++;
          this.composer.restoreInputDraft(`chat_input_draft/${full.id}`);
          this.modelContext.refreshEffectiveModelState();
          if (this.timeline.rows.length === 0 && full.messageNodes.length > 0) {
            this.timeline.setRows(buildRows(full, this.timeline.rows));
            this.timeline.timelineLoadState = applyTailWindow(
              this.timeline.timelineLoadState, 0, full.messageNodes.length, full.messageNodes.length);
          }
          this.presentation.title = full.title.length > 0 ? full.title : '对话';
          this.presentation.suggestions = full.chatSuggestions;
          this.modelContext.refreshContextCompacts();
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
            this.timeline.applyInitialScroll(-1);
          } else {
            this.timeline.positionToTargetNode(targetNodeId, targetNodeIndex);
          }
          if (manualId.length > 0) {
            const messages: UIMessage[] = currentMessages(full);
            const manual: UIMessagePartTool | undefined = messages.length > 0
              ? messages[messages.length - 1].parts.find((part: UIMessagePart): boolean =>
                part.type === 'tool' && (part as UIMessagePartTool).toolCallId === manualId) as UIMessagePartTool | undefined
              : undefined;
            if (manual !== undefined && (manual.metadata?.['recipe_manual'] === true
              || manual.metadata?.['plugin_manual'] === true)
              && manual.output.length === 0 && manual.approvalState.type === 'auto') this.presentation.resumeToolLoop(full);
          }
          this.composer.drainPendingQueue();
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
        this.presentation.conversation = seedConversationWithPresets(fresh, picked);
        this.presentation.unsavedConversationSeed = this.presentation.conversation;
        getConversationRunService().seedNewConversation(this.presentation.conversation);
        this.presentation.attachConversationRun(fresh.id);
        this.presentation.conversationLoaded = true;
        // 未发送新会话:恢复 __unsaved__ 稳定键草稿(fresh.id 每次进页都变,不可作键)
        this.composer.restoreInputDraft('chat_input_draft/__unsaved__');
        this.modelContext.refreshEffectiveModelState();
        // Phase 5:新会话空窗口 = fullyLoaded(rows 直接全量展示)
        this.timeline.setRows(buildRows(this.presentation.conversation, this.timeline.rows));
        this.timeline.timelineLoadState = applyTailWindow(
          this.timeline.timelineLoadState, 0, this.timeline.rows.length, this.timeline.rows.length);
        this.presentation.suggestions = this.presentation.conversation.chatSuggestions;
        // Phase 1:新会话首帧滚底(无目标)
        this.timeline.applyInitialScroll(-1);
        // 消费首页传入的 initialText(首页输入框发送);无则显示空状态 hero(不自动发送)
        const initialText: string = params ? (params['initialText'] ?? '') : '';
        const sendMode: string = params ? (params['sendMode'] ?? 'send') : 'send';
        const openImagePicker: boolean = params ? params['openImagePicker'] === 'true' : false;
        if (initialText.length > 0) {
          this.composer.setInputText(initialText);
          if (sendMode === 'append') {
            this.composer.sendWithoutAnswer();
          } else {
            this.composer.send();
          }
        }
        if (openImagePicker && !this.composer.initialImagePickerConsumed && this.pageAlive) {
          this.composer.initialImagePickerConsumed = true;
          setTimeout((): void => {
            if (this.pageAlive && this.pageVisible) this.composer.pickImages();
          }, 0);
        }
      });
    }
    // P4:IME 键盘高度监听(aboutToDisappear 注销;attach 异步取窗,pageAlive 守卫)
    this.timeline.attachKeyboardListener();
  }

  // 「+」新对话: 重置为一个全新会话(替换当前页)
  private newChat(): void {
    router.replaceUrl({ url: 'pages/ChatPage' });
  }

  private async startHubConversation(hubId: string, instruction: string, title: string): Promise<void> {
    if (this.openingHubConversation) return;
    this.openingHubConversation = true;
    const draft: string = instruction.trim().length > 0
      ? `[专题·${title}] ${instruction.trim()}\n\n` : `[专题·${title}] `;
    const assistantId: string | undefined = this.presentation.conversation?.assistantId;
    try {
      const assistants: Assistant[] = await getChatAssistants();
      const picked: Assistant = assistants.find((item: Assistant): boolean => item.id === assistantId) ?? assistants[0];
      if (!this.pageAlive || !this.pageVisible) return;
      this.composer.flushInputDraft();
      const conversation: Conversation = seedConversationWithPresets(
        makeConversation(newId(), [], { assistantId: picked.id, title: title }), picked);
      await getChatRepository().save(conversation);
      await getChatKvStore().put(`chat_input_draft/${conversation.id}`, draft);
      await toggleConversationInHub(getChatKvStore(), hubId, conversation.id);
      if (this.pageAlive && this.pageVisible) {
        await router.pushUrl({ url: 'pages/ChatPage', params: { conversationId: conversation.id } });
      }
    } catch (error) {
      if (this.pageAlive) this.presentation.errorMsg = (error as Error).message;
    } finally {
      this.openingHubConversation = false;
    }
  }

  private countTimelineArtifacts(): number {
    let count: number = 0;
    for (const row of this.timeline.rows) {
      for (const part of row.message.parts) {
        if (part.type === 'image' || part.type === 'document' || part.type === 'mini_app') count++;
      }
    }
    return count;
  }

  private runningTaskBadgeCount(): number {
    const cloudOrLocal: number = this.cloudDialogue.runningTasks().length;
    const otherRunning: number = this.presentation.runningConversationIds.filter(
      (id: string): boolean => id !== (this.presentation.conversation?.id ?? ''),
    ).length;
    return cloudOrLocal + otherRunning;
  }

  private pendingApprovalCount(): number {
    let count: number = 0;
    for (const row of this.timeline.rows) {
      for (const part of row.message.parts) {
        if (part.type === 'tool' && (part as UIMessagePartTool).approvalState.type === 'pending') count++;
      }
    }
    return count;
  }

  private consecutiveErrorCount(): number {
    return consecutiveTurnToolFailures(this.timeline.rows.map((r: ChatTimelineRow): UIMessage => r.message));
  }

  private attentionTaskBadgeCount(): number {
    let count: number = 0;
    for (const task of this.cloudDialogue.tasks) {
      if (task.status === 'failed' && this.dismissedNoticeIds.indexOf(`cloud:${task.workItemId}`) < 0) count++;
    }
    count += this.pendingApprovalCount();
    if (this.consecutiveErrorCount() >= 4) count++;
    return count;
  }

  private buildTopBarNotices(): TopBarNoticeItem[] {
    const notices: TopBarNoticeItem[] = [];
    const currentId: string = this.presentation.conversation?.id ?? '';
    for (const task of this.cloudDialogue.tasks) {
      const nid: string = `cloud:${task.workItemId}`;
      if (this.dismissedNoticeIds.indexOf(nid) >= 0) continue;
      const ownerId: string = this.cloudDialogue.conversationIdForTask(task.workItemId, task.topicId);
      if (task.status === 'failed') {
        notices.push({
          id: nid, kind: 'failed',
          title: task.title.length > 0 ? task.title : '云端任务失败',
          subtitle: task.progressText.length > 0 ? task.progressText : '点击查看详情',
          conversationId: ownerId,
        });
      } else if (task.status === 'completed' && ownerId.length > 0 && ownerId !== currentId) {
        notices.push({
          id: nid, kind: 'completed',
          title: task.title.length > 0 ? task.title : '云端任务已完成',
          subtitle: '来自其他对话',
          conversationId: ownerId,
        });
      }
    }
    for (const id of this.presentation.runningConversationIds) {
      if (id === currentId) continue;
      const nid: string = `run:${id}`;
      if (this.dismissedNoticeIds.indexOf(nid) >= 0) continue;
      notices.push({
        id: nid, kind: 'running',
        title: '后台对话正在生成',
        subtitle: '轻点切过去查看',
        conversationId: id,
      });
    }
    return notices;
  }

  private openTopBarNotice(notice: TopBarNoticeItem): void {
    const currentId: string = this.presentation.conversation?.id ?? '';
    if (notice.conversationId.length > 0 && notice.conversationId !== currentId) {
      router.replaceUrl({ url: 'pages/ChatPage', params: { conversationId: notice.conversationId } });
      return;
    }
    this.bottomDockTab = 'tasks';
  }

  private clearTopBarNotices(): void {
    const next: string[] = this.dismissedNoticeIds.slice();
    for (const n of this.buildTopBarNotices()) {
      if (next.indexOf(n.id) < 0) next.push(n.id);
    }
    this.dismissedNoticeIds = next;
  }

  private bindEpisodeLedger(convId: string): void {
    if (convId.length === 0 || convId === this.boundEpisodeConvId) return;
    this.boundEpisodeConvId = convId;
    if (this.unsubEpisodeLedger !== null) {
      this.unsubEpisodeLedger();
      this.unsubEpisodeLedger = null;
    }
    this.unsubEpisodeLedger = subscribeEpisodeLedger(convId, (): void => {
      if (this.pageAlive) this.refreshEpisodeUi();
    });
    loadEpisodeLedger(convId).then((): void => {
      if (this.pageAlive && (this.presentation.conversation?.id ?? '') === convId) {
        this.refreshEpisodeUi();
      }
    }).catch((): void => {});
  }

  private refreshEpisodeUi(): void {
    const conv: Conversation | null = this.presentation.conversation;
    if (conv === null) {
      this.episodeHeaderTitle = '';
      return;
    }
    const ledger: ChatEpisodeLedger = projectEpisodeLedger(getCachedEpisodeLedger(conv.id), conv.messageNodes);
    this.episodeHeaderTitle = activeEpisodeHeaderTitle(ledger, currentMessages(conv), this.presentation.title);
    this.timeline.syncDataSource();
  }

  private startNewTopicAction(): void {
    const conv: Conversation | null = this.presentation.conversation;
    if (conv === null) {
      this.newChat();
      return;
    }
    if (this.composer.sendPrechecking || this.presentation.sending ||
      getConversationTaskRunner().activeAndQueuedTasksForConversation(conv.id).length > 0) {
      promptAction.showToast({ message: '请等当前任务结束后再开启新话题', duration: 1600 });
      return;
    }
    const msgs: UIMessage[] = currentMessages(conv);
    startNewEpisode(conv.id, msgs, Date.now(), conv.messageNodes).then((res): void => {
      if (!this.pageAlive || this.presentation.conversation?.id !== conv.id) return;
      if (res.sealed === null) {
        promptAction.showToast({ message: '当前已是新话题起点', duration: 1500 });
        return;
      }
      this.refreshEpisodeUi();
      promptAction.showToast({ message: '✦ 已开启新话题分段', duration: 1600 });
      const sealedEp: ChatEpisodeRecord = res.sealed;
      if (sealedEp.turnCount >= 2) {
        this.refineSealedEpisodeAsync(conv, sealedEp, msgs);
      }
    }).catch((err: Error): void => {
      if (this.pageAlive) this.presentation.errorMsg = err.message;
    });
  }

  private refineSealedEpisodeAsync(conv: Conversation, ep: ChatEpisodeRecord, msgs: UIMessage[]): void {
    refineEpisodeSummaryInBackground(conv.id, ep, msgs, async (segment: UIMessage[]): Promise<RefinedEpisodeSummary | null> => {
      const choice = await resolveEffectiveChatChoice(conv.assistantId);
      if (choice === null) return null;
      const api: OpenAIChatApi = await prepareProviderApi(choice.provider, {});
      const preview: string = segment.slice(0, 8).map((m: UIMessage): string =>
        `${m.role === 'user' ? '用户' : '助手'}: ${toText(m).substring(0, 120)}`).join('\n');
      const promptMsg: UIMessage = makeUserMessage(
        `请为以下对话分段提炼极简标题（不超过16字）和单行摘要（不超过50字），严格输出 JSON {"title":"...","summary":"..."}：\n\n${preview}`,
      );
      const params = makeTextGenerationParams({ model: toChatModel(choice.model) });
      const chunk: MessageChunk = await api.generateText([promptMsg], params);
      let collected: string = '';
      for (const c of chunk.choices) {
        const piece: UIMessage | null = c.delta ?? c.message;
        if (piece !== null) collected += toText(piece);
      }
      const match = /\{[\s\S]*\}/.exec(collected);
      if (match === null) return null;
      const parsed = JSON.parse(match[0]) as Record<string, string>;
      if (typeof parsed['title'] === 'string' && typeof parsed['summary'] === 'string') {
        const res: RefinedEpisodeSummary = { title: parsed['title'], summary: parsed['summary'] };
        return res;
      }
      return null;
    });
  }

  private toolStepDisplayTitle(part: UIMessagePartTool): string {
    try {
      const inputObj = toolInputAsJson(part);
      const dt: string | null = toolInputDisplayTitle(inputObj);
      if (dt !== null && dt.length > 0) return dt;
      const st: string | null = toolStaticLabel(part.toolName, inputObj);
      if (st !== null && st.length > 0) return st;
    } catch (_e) {}
    return toolStaticLabel(part.toolName) ?? part.toolName;
  }

  @Builder
  TurnStepsSheet(): void {
    Column({ space: 10 }) {
      Row() {
        Text(this.turnStepsTitle.length > 0 ? this.turnStepsTitle : '本轮步骤明细')
          .fontSize(16)
          .fontWeight(FontWeight.Medium)
          .fontColor(INK)
          .layoutWeight(1)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
        Text('关闭')
          .fontSize(14)
          .fontColor(this.accentColorStore)
          .onClick((): void => { this.turnStepsSheetOpen = false; })
      }
      .width('100%')
      .padding({ left: 20, right: 20, top: 18, bottom: 6 })

      if (this.turnStepsTools.length === 0 && this.turnStepsReasoning.length === 0) {
        Text('暂无可查看的步骤明细。')
          .fontSize(13)
          .fontColor(INK3)
          .padding({ left: 20, right: 20, top: 8 })
      } else {
        Scroll() {
          Column({ space: 8 }) {
            ForEach(this.turnStepsReasoning, (reasoning: string): void => {
              Text(reasoning).fontSize(13).fontColor(INK2).width('100%')
            }, (_text: string, index: number): string => `reasoning-${index}`)
            ForEach(this.turnStepsTools, (tool: UIMessagePartTool): void => {
              Row({ space: 10 }) {
                Column()
                  .width(7)
                  .height(7)
                  .borderRadius(999)
                  .backgroundColor(tool.approvalState.type === 'denied' ? ERROR : (tool.output.length > 0 ? SIGNAL : this.accentColorStore))
                Column({ space: 2 }) {
                  Text(this.toolStepDisplayTitle(tool))
                    .fontSize(13.5)
                    .fontColor(INK)
                    .maxLines(1)
                    .textOverflow({ overflow: TextOverflow.Ellipsis })
                  Text(tool.toolName)
                    .fontSize(11)
                    .fontFamily(FONT_MONO)
                    .fontColor(INK3)
                    .maxLines(1)
                }
                .layoutWeight(1)
                .alignItems(HorizontalAlign.Start)
                Text('查看 ›')
                  .fontSize(12)
                  .fontColor(this.accentColorStore)
              }
              .width('100%')
              .padding({ left: 14, right: 14, top: 10, bottom: 10 })
              .borderRadius(14)
              .backgroundColor(SURFACE2)
              .onClick((): void => {
                this.turnStepsSheetOpen = false;
                this.runDetails.openToolPreview(tool);
              })
            }, (t: UIMessagePartTool): string => t.toolCallId)
          }
          .padding({ left: 20, right: 20, bottom: 24 })
        }
        .layoutWeight(1)
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(BG)
  }

  // SandboxActivitySheet(:1113+;web 预览路径 = webview 子系统未移植 P1)
  //   头部 :796-870 + 工具内容 :873-899 + 代码块 :1070-1110
  private currentSmartUiMessages(): UIMessage[] {
    // Timeline resolves selected branches, regeneration and the active streaming tail.
    const messages: UIMessage[] = this.timeline.rows.map((row: MsgRow): UIMessage =>
      row.nodeId === this.presentation.regenNodeId && this.presentation.regenTail !== null ? this.presentation.regenTail : row.message);
    return [...messages, ...this.presentation.streamingTail];
  }

  private smartUiSourceContext(): ChatSmartUiSourceContext {
    return { conversationId: this.presentation.conversation?.id ?? '', messages: this.currentSmartUiMessages() };
  }

  private onSmartUiDraft(nodeId: string, sourceKey: string): void {
    const draft: string | null = this.smartUi.draft(nodeId, sourceKey, this.smartUiSourceContext());
    if (draft === null) return;
    this.composer.setInputText(appendSmartUiDraft(this.composer.inputText, draft));
    promptAction.showToast({ message: '已填入输入框，确认后发送', duration: 1800 });
  }

  @Builder
  EmbodimentSheet(): void {
    AgentEmbodimentSheet({
      onClose: (): void => { this.embodimentSheetOpen = false; },
      onPickStarter: (prompt: string): void => {
        this.bottomDockTab = 'chat';
        this.composer.setInputText(prompt);
      },
      onOpenMemoryRecords: (): void => { router.pushUrl({ url: 'pages/MemoryRecordsPage' }); },
      onOpenMemoryTopics: (): void => { router.pushUrl({ url: 'pages/MemoryTopicsPage' }); },
    })
  }

  @Builder
  chatSheetHosts(): void {
    // bindSheet 按宿主节点注册；每张 sheet 保持独立且稳定的宿主。
    ChatMessageActionsView({ session: this.messageActions })

    ChatArtifactPanels({
      session: this.artifacts,
      onDraft: (text: string): void => { this.composer.setInputText(text); },
      onRunMiniApp: (appId: string): void => { router.pushUrl({ url: 'pages/MiniAppRunnerPage', params: { appId: appId } }); },
    })

    ChatCompactSummaryHost({ session: this.modelContext })

    Column() {}
      .width(0).height(0)
      .hitTestBehavior(HitTestMode.None)
      .bindSheet(this.turnStepsSheetOpen, this.TurnStepsSheet(), {
        height: SheetSize.MEDIUM,
        onDisappear: (): void => { this.turnStepsSheetOpen = false; },
      })

    Column() {}
      .width(0).height(0)
      .hitTestBehavior(HitTestMode.None)
      .bindSheet(this.embodimentSheetOpen, this.EmbodimentSheet(), {
        height: SheetSize.LARGE,
        onDisappear: (): void => { this.embodimentSheetOpen = false; },
      })

  }

  @Builder
  sandboxContent(): void { ChatSandboxStrip({ session: this.runDetails }) }
  @Builder
  runtimeContent(): void { ChatRunActivityStrip({ session: this.runDetails }) }

  build() {
    Row() {
    if (this.twoPane()) {
      HomeListPane({
        hostVisible: this.homePaneVisible,
        twoPane: true,
        inChat: true,
        activeId: this.presentation.homePaneActiveId,
      })
        .width(twoPaneListWidth(this.windowWidthVp))
        .height('100%')
      // 整 1 物理像素:1vp 落在小数像素上,边缘发虚、显粗
      ForEach([this.themeEpoch], (_e: number): void => {
        Column().width(this.getUIContext().px2vp(1)).height('100%').backgroundColor(LINE2)
      }, (e: number): string => `pane-line-${e}`)
    }
    Stack({ alignContent: Alignment.Top }) {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
    this.chatSheetHosts()
    ChatRunDetailsView({ session: this.runDetails })

    // ── 主舞台 Stack：底层全屏 Timeline ⇄ 顶部 54vp 窄模糊带 + 悬垂形象岛 ⇄ 底部悬浮胶囊输入与双态底栏 ──
    Stack({ alignContent: Alignment.Top }) {
      ChatTimelineView({
        timeline: this.timeline, presentation: this.presentation, smartUi: this.smartUi,
        activeArtifactInteraction: this.activeArtifactInteraction,
        favoriteNodeIds: this.messageActions.favoriteNodeIds, contextCompacts: this.modelContext.contextCompacts,
        topSpacerHeight: this.statusBarHeight + 114,
        bottomSpacerHeight: this.composerHeight,
        onMessageCommand: (command: string, row: MsgRow, index: number): void => {
          if (command === 'switch') this.presentation.switchBranch(row.nodeId, index);
          else if (command === 'regenerate') this.presentation.regenerate(row.nodeId);
          else if (command === 'edit') this.composer.startEditMessage(row);
          else if (command === 'actions') this.messageActions.openActionsSheet(row);
          else if (command === 'fork') this.messageActions.forkAt(row);
          else if (command === 'copy') this.messageActions.openCopySheet(row.nodeId === this.presentation.regenNodeId && this.presentation.regenTail !== null ? this.presentation.regenTail : row.message);
          else if (command === 'favorite') this.messageActions.toggleFavorite(row);
        },
        onApprove: (id: string, locator?: ToolApprovalPartLocator): void => { this.runDetails.handleToolApproval(id, true, '', locator); },
        onDeny: (id: string, locator?: ToolApprovalPartLocator): void => { this.runDetails.openDenyDialog(id, locator); },
        onAnswer: (id: string, answer: string, locator?: ToolApprovalPartLocator): void => { this.runDetails.handleToolAnswer(id, answer, locator); },
        onToolPreview: (part: UIMessagePartTool, locator?: ToolApprovalPartLocator): void => { this.runDetails.openToolPreview(part, locator); },
        subjectHash: (part: UIMessagePartTool, locator?: ToolApprovalPartLocator): string => this.runDetails.jevSubjectHash(part, locator),
        onRunDetails: (kind: string, id: string, tools: UIMessagePartTool[]): void => {
          if (kind === 'council') this.runDetails.openCouncilSheet(id, tools); else this.runDetails.openSubAgentSheet(id, tools);
        },
        onDocument: (part: UIMessagePartDocument): void => { this.artifacts.openDocumentPreview(part); },
        onDraft: (text: string): void => { this.composer.setInputText(text); },
        onModifyImage: (url: string): void => { this.composer.modifyGeneratedImage(url); },
        onSmartUiDraft: (nodeId: string, key: string): void => { this.onSmartUiDraft(nodeId, key); },
        onShowCompact: (messageId: string): void => { this.modelContext.showCompactSummary(messageId); },
        onSendSuggestion: (text: string): void => { this.composer.sendSuggestion(text); },
        onNavigate: (kind: string, id: string): void => { router.pushUrl({ url: kind === 'miniApp' ? 'pages/MiniAppRunnerPage' : 'pages/MiniAppListPage', params: { appId: id } }); },
        onOpenTurnDetails: (headline: string, tools: UIMessagePartTool[], reasoning: string[]): void => {
          this.turnStepsTitle = headline;
          this.turnStepsTools = tools;
          this.turnStepsReasoning = reasoning;
          this.turnStepsSheetOpen = true;
        },
      })

      // 空状态 hero：居中于消息舞台上，避开顶栏悬垂岛与底栏
      if (this.presentation.conversationArrivedTick >= 0 && this.presentation.conversationLoaded && this.presentation.conversation !== null
        && this.timeline.rows.length === 0 && !this.presentation.sending) {
        Column() {
          Column() {
            AmberPersonaMedallion({
              medallionSize: 72,
              displayName: this.agentSoulDisplayName,
              isDefault: this.agentSoulIsDefault,
              sending: false,
            })
          }
          .margin({ bottom: 14 })
          .onClick((): void => { this.embodimentSheetOpen = true; })

          Row() {
            if (this.agentSoulIsDefault || this.agentSoulDisplayName === 'Amber') {
              DotText({ text: 'AMBER', dotScale: 3.4, color: INK, glow: true })
            } else {
              Text(this.agentSoulDisplayName)
                .fontSize(28).fontWeight(FontWeight.Bold).fontColor(INK)
                .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
            }
            Text('')
              .width(11).height(28).backgroundColor(this.accentColorStore).borderRadius(2)
              .margin({ left: 4 })
              .opacity(this.cursorOn ? 1 : 0)
          }
          .constraintSize({ minHeight: 44 })
          .alignItems(VerticalAlign.Center)
          .accessibilityText('形象人设')
          .onClick((): void => { this.embodimentSheetOpen = true; })

          Text(this.agentSoulTagline.trim().length > 0 ? this.agentSoulTagline : GREETINGS[this.greetIdx])
            .fontSize(15).fontColor(INK3)
            .margin({ top: 10 })
            .opacity(this.agentSoulTagline.trim().length > 0 ? 1 : this.greetOpacity)
        }
        .alignItems(HorizontalAlign.Center)
        .width('100%')
        .position({ top: '34%' })
        .visibility(this.modelContext.modelMenuOpen ? Visibility.Hidden : Visibility.Visible)
      }

      // 底层窄渐进模糊带（statusBarHeight + 54vp，不覆盖悬垂胶囊下方正文）
      ChatProgressiveEdgeBlur({
        top: true,
        bandHeight: this.statusBarHeight + 54,
        active: true,
      })

      // 上层 overlay 居中悬垂岛（76vp 形象 + 32vp 胶囊 + 左右玻璃圆钮，正文触控透明穿透）
      ChatTopBarOverlay({
        twoPane: this.twoPane(),
        title: this.presentation.title,
        episodeTitle: this.episodeHeaderTitle,
        editingTitle: this.presentation.editingTitle,
        titleInput: this.presentation.titleInput,
        sending: this.presentation.sending,
        recapLoading: this.presentation.recapState.loading,
        activityLabel: this.cloudDialogue.isActiveMode() ? this.cloudDialogue.activeProgressText : '',
        agentSoulDisplayName: this.agentSoulDisplayName,
        agentSoulIsDefault: this.agentSoulIsDefault,
        artifactCount: this.countTimelineArtifacts(),
        notices: this.buildTopBarNotices(),
        onOpenDrawer: (): void => { this.drawer.openDrawer(); },
        onOpenPersona: (): void => { this.embodimentSheetOpen = true; },
        onOpenRecap: (): void => {
          if (!this.presentation.editingTitle) this.presentation.openRecap();
        },
        onStopGeneration: (): void => { this.presentation.requestStop(); },
        onTitleInputChange: (v: string): void => { this.presentation.titleInput = v; },
        onSaveTitle: (): void => { this.presentation.saveTitle(); },
        onCancelEditTitle: (): void => { this.presentation.editingTitle = false; },
        onOpenArtifactShelf: (): void => { this.artifacts.openArtifactShelf(); },
        onNewTopic: (): void => { this.startNewTopicAction(); },
        onNewChat: (): void => { this.newChat(); },
        onExportChat: (): void => { this.messageActions.exportSheetOpen = true; },
        onOpenNotice: (notice: TopBarNoticeItem): void => { this.openTopBarNotice(notice); },
        onClearNotices: (): void => { this.clearTopBarNotices(); },
        onControlsHeightChange: (h: number): void => { this.chatHeaderHeight = h; },
      })

      // ContextUsagePanel：从底栏上下文环或模型面板触发
      ChatContextMeterOverlay({ session: this.modelContext })

      // 底部悬浮胶囊输入栏 + 原位双态底栏
      Column() {
        ChatComposerPane({
          composer: this.composer, presentation: this.presentation, cloudDialogue: this.cloudDialogue,
          modelContext: this.modelContext,
          navBarHeight: this.navBarHeight, storageDegraded: this.storageDegraded,
          manualCompacting: this.modelContext.manualCompacting, retryStatus: this.runDetails.retryStatus,
          activeTab: this.bottomDockTab,
          attentionTaskCount: this.attentionTaskBadgeCount(),
          runningTaskCount: this.runningTaskBadgeCount(),
          pendingApprovalCount: this.pendingApprovalCount(),
          consecutiveErrorCount: this.consecutiveErrorCount(),
          hasUnrecoveredError: this.consecutiveErrorCount() >= 4,
          onCompact: (): void => { this.modelContext.compactCurrentConversation(); },
          onRetryStorage: (): void => { getChatRepository().list().catch((error: Error): void => { this.presentation.errorMsg = error.message; }); },
          onSelectTab: (tab: BottomDockTab): void => { this.bottomDockTab = tab; },
          onNewTopic: (): void => { this.startNewTopicAction(); },
          onOpenGatewaySettings: (): void => { router.pushUrl({ url: 'pages/SettingMacGatewayPage' }); },
          onOpenTasksSettings: (): void => { router.pushUrl({ url: 'pages/SettingCronTasksPage' }); },
          onOpenHubsPlaceholder: (): void => { router.pushUrl({ url: 'pages/MiniAppListPage' }); },
          onOpenConversation: (id: string): void => {
            if (id.length > 0 && id !== (this.presentation.conversation?.id ?? '')) {
              router.replaceUrl({ url: 'pages/ChatPage', params: { conversationId: id } });
            }
          },
          onFocusApproval: (): void => { this.timeline.jumpToBottom(); },
          onStartWithHubInstruction: (hubId: string, instruction: string, title: string): void => {
            this.startHubConversation(hubId, instruction, title);
          },
          sandboxContent: (): void => { this.sandboxContent(); },
          runtimeContent: (): void => { this.runtimeContent(); },
        })
      }
      .width('100%')
      .position({ left: 0, bottom: 0 })
      .onAreaChange((_old: Area, area: Area): void => {
        const h: number = Number(area.height);
        if (Number.isFinite(h) && h > 0) this.composerHeight = h;
      })
    }
    .width('100%').height('100%').backgroundColor(BG)

    // TopModelMenu 覆盖层
    ChatModelMenuOverlay({ session: this.modelContext, topInset: this.statusBarHeight + 112,
      onOpenFullPicker: (assistantId: string): void => { router.pushUrl({ url: 'pages/ChatModelPickerPage', params: { assistantId: assistantId } }); },
      onOpenProviderSettings: (): void => { router.pushUrl({ url: 'pages/ChatProviderSettingsPage' }); },
    })

    if (this.presentation.recapOpen) {
      ChatRecapPanel({
        recap: this.presentation.displayedRecap(),
        loading: this.presentation.recapSourceLoading || this.presentation.recapState.loading,
        error: this.presentation.recapState.error,
        stale: this.presentation.recapNeedsRefresh(),
        topInset: this.statusBarHeight + 114,
        onClose: (): void => { this.presentation.closeRecap(); },
        onRefresh: (): void => { this.presentation.openRecap(true); },
        onLocate: (node: ConversationRecapNode): void => { this.presentation.locateRecapNode(node); },
        onNextStep: (text: string): void => { this.presentation.closeRecap(); this.composer.setInputText(text); },
      })
    }

    ChatConversationDrawerView({ session: this.drawer,
      currentConversationId: this.presentation.conversation?.id ?? '', runningIds: this.presentation.runningConversationIds,
      userNickname: this.presentation.userNickname, userAvatar: this.presentation.userAvatar,
      onNewTopic: (): void => { this.startNewTopicAction(); },
      onOpenAppearance: (): void => { router.pushUrl({ url: 'pages/SettingAppearancePage' }); },
    })

    if (this.messageActions.workspacePanelOpen && this.presentation.conversation !== null) {
      ChatWorkspacePanel({ conversation: this.presentation.conversation, initialMessageId: this.messageActions.archiveInitialMessageId,
        onDismiss: (): void => { this.messageActions.workspacePanelOpen = false; },
        onOpenSource: (source: ChatMessageArchiveSource): void => {
          this.messageActions.openArchiveSource(source).catch((error: Error): void => { this.presentation.errorMsg = error.message; });
        },
      })
    }
    if (this.messageActions.exportSheetOpen && this.presentation.conversation !== null) {
      ChatExportSheet({
        show: true,
        conversation: this.presentation.conversation,
        messages: currentMessages(this.presentation.conversation),
        initialSelectedMessageIds: this.messageActions.exportInitialMessageIds,
        onDismiss: (): void => { this.messageActions.exportSheetOpen = false; },
      })
    }

    // ===== D-108:拒绝理由弹窗(ToolDenyReasonDialog :428-461 逐字) =====
    //   title '拒绝工具执行'/hint '原因（可选）'/confirm '拒绝'/dismiss '取消'
    //   (strings.xml:204/:203/:202);AlertDialog → Stack overlay(同 D-101 先例)
    ChatToolDenyOverlay({ session: this.runDetails })
    
      }, (e: number): string => `theme-${e}`)
}
      .enabled(this.themeEpoch >= 0)
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
    .layoutWeight(1)
    }
    .width('100%').height('100%')
    .onTouch((event: TouchEvent): void => { clearArtifactInteractionOutside(event); })
  }
}
