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
  MessageSearchHit, UIMessagePartTool, ToolApprovalVerdict, ToolApprovalPartLocator, DeepReadOpenEvent,
  AgentTool, MessageChunk, OpenAIChatApi, AskedAnswers, ConversationCompact, ImageGuardSettings,
  VisionModelHealth, VisionProbeDeps, ProviderModel, ProviderSetting, SandboxActivityUiState,
  InteractiveTurnRunResult, ChatTimelineRow, SmartUiDocument, SmartUiDraftSource, SmartUiScalar,
  SmartUiStateValue,
} from '@amber/chat-domain';
import {
  currentMessages, makeConversation, newId, seedConversationWithPresets, nodeCurrentMessage,
  selectMessageBranch, deleteMessage, editMessage, buildChatTimelineRows, messageSignature, forkConversation, patchConversation,
  runAutoTitle, makeUserMessage, toText, messageCopyText, makeTextGenerationParams, pendingPreviewText, convertSteerToFollowup,
  invalidateCompacts, copyValidCompactsToConversation, ContextCompactionFailedError, recoverInterruptedRecipes,
  recoverInterruptedPlugins, recoverInterruptedWebMountGoals, parseDeepReadSlashCommand, estimateConversationInputTokens, formatNumberInt,
  formatContextTokens, nativeTokenizerForModel, loadSearchPrefs, SearchSettings,
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
import { Icon } from '../components/ui/Icon.ets';
import { DotText, DotGround } from '../components/ui/DotMatrix.ets';
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
import { ChatDrawer } from '../components/ChatDrawer.ets';
import { HomeListPane } from '../components/HomeListPane.ets';
import {
  isTwoPaneWindow, twoPaneListWidth, WINDOW_HEIGHT_VP_KEY, WINDOW_WIDTH_VP_KEY,
} from '../design/adaptive.ets';
import { ChatListGrouped } from '../components/ChatListGrouped.ets';
import { TopModelMenu } from '../components/TopModelMenu.ets';
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

// D-122:沙盒 sheet 代码块底色(设置面 ROW 惯例同值)
interface ChatArchiveRouteParams { conversationId: string; nodeId: string; }

const ROW: string = SURFACE2;

// accent rgba 混色(建议 chips 描边等;与 tokens 令牌 #RRGGBB 配合)
// 1:1 原型空态 hero 轮换句(3.2s 淡入淡出)
const GREETINGS: string[] = [
  '今天想聊点什么？', '想精读哪一篇？', '描述一下，就搭给你看。', '要续写哪个世界？', '让四个模型辩一场？',
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
  @State chatHeaderHeight: number = 0;
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
      openDeepRead: (event: DeepReadOpenEvent): void => { this.openDeepRead(event); },
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
      openDeepRead: (event: DeepReadOpenEvent): void => { this.openDeepRead(event); },
      captureContinuation: (text: string): SmartUiContinuationCapture | null => this.smartUi.captureSmartUiContinuation(text, this.smartUiSourceContext()),
      continuationAvailable: (): boolean => this.smartUi.latestSmartUiContinuationRuntime(this.smartUiSourceContext()) !== undefined,
      continuationCurrent: (capture: SmartUiContinuationCapture | null): boolean => this.smartUi.smartUiContinuationCurrent(capture, this.smartUiSourceContext()),
      loadMentionRoles: (): Promise<MentionRoleItem[]> => hydrateSubAgentSetting().then((setting): Promise<MentionRoleItem[]> =>
        isModelCouncilEnabled().then((enabled: boolean): MentionRoleItem[] => buildMentionRoleItems(setting.enabled, enabled, setting.mode, setting.customDefinitions))),
      abilityContext: (): common.UIAbilityContext => getContext(this) as common.UIAbilityContext,
      cloudDialogue: (): ChatCloudDialogueSession => this.cloudDialogue,
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
        const url: string = target === 'board' ? 'pages/BoardPage' : target === 'apps' ? 'pages/MiniAppListPage' : target === 'settings' ? 'pages/SettingPage' : 'pages/ProfilePage';
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

  // 深读页路由(deep_read_open 工具 bus 与斜杠命令共用)
  private openDeepRead(event: DeepReadOpenEvent): void {
    const params: Record<string, string> = {};
    params['topicId'] = event.topicId;
    params['title'] = event.title;
    router.pushUrl({ url: 'pages/DeepReadArticlePage', params: params });
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
  chatSheetHosts(): void {
    // bindSheet 按宿主节点注册；每张 sheet 保持独立且稳定的宿主。
    ChatMessageActionsView({ session: this.messageActions })

    ChatArtifactPanels({
      session: this.artifacts,
      onDraft: (text: string): void => { this.composer.setInputText(text); },
      onRunMiniApp: (appId: string): void => { router.pushUrl({ url: 'pages/MiniAppRunnerPage', params: { appId: appId } }); },
    })

    ChatCompactSummaryHost({ session: this.modelContext })

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
    Stack() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
    this.chatSheetHosts()
    ChatRunDetailsView({ session: this.runDetails })
    Column() {
      // 顶栏参与布局，正文视口从顶栏细线开始。
      // 顶栏位于正文视口之前，细线就是正文上边界。
      Row() {
        // amber-zhenji:chat-head 左侧为返回(抽屉入口收归首页)。
        //   两栏时左边就是列表,去掉返回;占位保留,标题仍居中(系统返回照旧回首页)。
        if (this.twoPane()) {
          Row().width(80).height(48)
        } else {
          Row() {
            Row() {
              Icon({ name: 'back', iconSize: 24, color: INK })
            }
            .width(48).height(48)
            .padding({ right: 8 })
            .justifyContent(FlexAlign.Center)
            .alignItems(VerticalAlign.Center)
            .onClick((): void => { this.navigateBack(); })
            .accessibilityText('返回')
          }
          .width(80).justifyContent(FlexAlign.Start).alignItems(VerticalAlign.Center)
        }

        // 两行文字共用中轴，左右各保留 48vp 按钮区域。
        Column() {
          if (this.presentation.editingTitle) {
            TextInput({ text: this.presentation.titleInput, placeholder: '会话标题' })
              .fontSize(TEXT_STYLES.sessionTitle.size)
              .fontWeight(TEXT_STYLES.sessionTitle.weight)
              .lineHeight(TEXT_STYLES.sessionTitle.lineHeight)
              .fontFamily(TEXT_STYLES.sessionTitle.family)
              .width('100%').height(36)
              .backgroundColor(SURFACE)
              .onChange((v: string): void => { this.presentation.titleInput = v; })
          } else {
            Row({ space: 6 }) {
              if (this.presentation.sending || this.presentation.recapState.loading) {
                LoadingProgress().width(14).height(14).color(this.accentColorStore)
              } else {
                Icon({ name: 'history', iconSize: 14, color: this.accentColorStore })
              }
              Text(this.presentation.sending ? '正在回复…' : (this.presentation.recapState.loading ? '正在整理回顾…' : this.presentation.title))
                .fontSize(14).fontWeight(FontWeight.Medium).fontColor(INK)
                .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                .flexShrink(1)
            }
            .height(34).constraintSize({ maxWidth: '100%' }).justifyContent(FlexAlign.Center)
            // 右侧补齐图标 14vp + 间距 6vp，让标题文字居中。
            .padding({ left: 10, right: 30 })
            .backgroundColor(SURFACE2).borderRadius(17)
            .onClick((): void => { this.presentation.openRecap(); })
            .accessibilityText('对话回顾：' + this.presentation.title)
          }
          // 模型行(amber-zhenji):mono 11.5 ink3 居中 + ⌄12
          Row() {
            Text(this.modelContext.modelLabel.length > 0 ? this.modelContext.modelLabel : '未配置模型')
              .fontSize(11.5).fontFamily(FONT_MONO)
              .fontColor(this.modelContext.modelLabel.length > 0 ? INK3 : this.accentColorStore)
              .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis }).flexShrink(1)
            Icon({ name: 'chevron_down', iconSize: 12, color: INK3 })
              .rotate({ angle: this.modelContext.modelMenuOpen ? 180 : 0 })
              .margin({ left: 2 })
          }
          .height(30).width('100%').justifyContent(FlexAlign.Center)
          // 左侧补齐下拉图标 12vp + 间距 2vp，与标题文字对齐。
          .padding({ left: 24, right: 10 })
          .onClick((): void => {
            if (!this.presentation.editingTitle) { this.presentation.closeRecap(); this.modelContext.toggleModelMenu(); }
          })
          .accessibilityText('切换模型与服务商')
        }
        .layoutWeight(1)
        .alignItems(HorizontalAlign.Center)

        // 右侧保留产物架；编辑态沿用保存/取消。
        if (this.presentation.editingTitle) {
          Row() {
            Text('保存').fontSize(META).fontColor(this.accentColorStore)
              .padding({ left: 8, right: 4 })
              .onClick((): void => this.presentation.saveTitle())
            Text('取消').fontSize(META).fontColor(INK3)
              .padding({ left: 8, right: 0 })
              .onClick((): void => { this.presentation.editingTitle = false; })
          }
        } else {
          Row() {
            // 形象人设入口(40×44 热区;自定义人设时高亮 accent 色)
            Row() {
              Icon({
                name: 'ph_maskHappy',
                iconSize: 19,
                color: this.agentSoulIsDefault ? INK : this.accentColorStore,
              })
            }
            .width(40).height(44)
            .justifyContent(FlexAlign.Center)
            .alignItems(VerticalAlign.Center)
            .onClick((): void => { router.pushUrl({ url: 'pages/AgentSoulPage' }); })
            .accessibilityText('形象人设')

            // 产物架入口(图片/文件/小应用陈列;40×44 热区)
            Row() {
              Icon({ name: 'box', iconSize: 20, color: INK })
            }
            .width(40).height(44)
            .justifyContent(FlexAlign.Center)
            .alignItems(VerticalAlign.Center)
            .onClick((): void => { this.artifacts.openArtifactShelf(); })
            .accessibilityText('产物架')
          }
          .width(80).justifyContent(FlexAlign.End)
          .alignItems(VerticalAlign.Center)
        }
      }
      .width('100%')
      .constraintSize({ minHeight: 64 })
      .padding({ left: 20, right: 20, top: this.statusBarHeight + 2, bottom: 8 })
      .backgroundColor(BG)
      .border({ width: { bottom: 0.5 }, color: LINE })
      .onAreaChange((_old: Area, area: Area): void => {
        this.chatHeaderHeight = Number(area.height);
      })
      Stack({ alignContent: Alignment.Top }) {
      // 共用 Timeline 只承载 List/几何/事件透传；Chat 的窗口、首帧定位与三态跟底保持原状态机。
      ChatTimelineView({
        timeline: this.timeline, presentation: this.presentation, smartUi: this.smartUi,
        activeArtifactInteraction: this.activeArtifactInteraction,
        favoriteNodeIds: this.messageActions.favoriteNodeIds, contextCompacts: this.modelContext.contextCompacts,
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
      })

      // ContextUsagePanel(Android ContextRing.kt ContextUsagePopup):指示器下方右锚定
      ChatContextMeterOverlay({ session: this.modelContext })
      }
      .layoutWeight(1)
      .width('100%')

      ChatComposerPane({
        composer: this.composer, presentation: this.presentation, cloudDialogue: this.cloudDialogue,
        navBarHeight: this.navBarHeight, storageDegraded: this.storageDegraded,
        manualCompacting: this.modelContext.manualCompacting, retryStatus: this.runDetails.retryStatus,
        onCompact: (): void => { this.modelContext.compactCurrentConversation(); },
        onRetryStorage: (): void => { getChatRepository().list().catch((error: Error): void => { this.presentation.errorMsg = error.message; }); },
        sandboxContent: (): void => { this.sandboxContent(); },
        runtimeContent: (): void => { this.runtimeContent(); },
      })
    }
    .width('100%').height('100%').backgroundColor(BG)

    // 空状态 hero(1:1 原型): 居中 Amber▌ 词标 36px 陶土光标闪烁 +
    //   问候句 3.2s 淡入淡出轮换;光学中心上移(margin bottom 128)
    if (this.presentation.conversationArrivedTick >= 0 && this.presentation.conversationLoaded && this.presentation.conversation !== null
      && this.timeline.rows.length === 0 && !this.presentation.sending) {
      Column() {
        Row() {
          if (this.agentSoulIsDefault || this.agentSoulDisplayName === 'Amber') {
            DotText({ text: 'AMBER', dotScale: 3.6, color: INK, glow: true })
          } else {
            Text(this.agentSoulDisplayName)
              .fontSize(30).fontWeight(FontWeight.Bold).fontColor(INK)
              .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          }
          Text('')
            .width(12).height(30).backgroundColor(this.accentColorStore).borderRadius(2)
            .margin({ left: 4 })
            .opacity(this.cursorOn ? 1 : 0)
        }
        .constraintSize({ minHeight: 44 })
        .alignItems(VerticalAlign.Center)
        .accessibilityText('形象人设')
        .onClick((): void => { router.pushUrl({ url: 'pages/AgentSoulPage' }); })
        Text(this.agentSoulTagline.trim().length > 0 ? this.agentSoulTagline : GREETINGS[this.greetIdx])
          .fontSize(15).fontColor(INK3)
          .margin({ top: 14 })
          .opacity(this.agentSoulTagline.trim().length > 0 ? 1 : this.greetOpacity)
      }
      .alignItems(HorizontalAlign.Center)
      .width('100%')
      .margin({ bottom: 128 })
      // 模型下拉打开时隐藏 hero:面板下缘会横切问候句(只露半行)
      .visibility(this.modelContext.modelMenuOpen ? Visibility.Hidden : Visibility.Visible)
    }

    // TopModelMenu 覆盖层(Android TopModelMenu.kt:Box { scrim; AnimatedVisibility(panel) })
    //   Stack 子节点序:主 Column(底) → 本覆盖层 → deny-dialog(顶,确保拒绝弹窗压在菜单之上)。
    //   组件自含全屏 dimmed scrim(28% 黑,点击 = onClose)+ 贴顶面板。
    ChatModelMenuOverlay({ session: this.modelContext, topInset: this.chatHeaderHeight,
      onOpenFullPicker: (assistantId: string): void => { router.pushUrl({ url: 'pages/ChatModelPickerPage', params: { assistantId: assistantId } }); },
      onOpenProviderSettings: (): void => { router.pushUrl({ url: 'pages/ChatProviderSettingsPage' }); },
    })

    if (this.presentation.recapOpen) {
      ChatRecapPanel({
        recap: this.presentation.displayedRecap(),
        loading: this.presentation.recapSourceLoading || this.presentation.recapState.loading,
        error: this.presentation.recapState.error,
        stale: this.presentation.recapNeedsRefresh(),
        topInset: this.statusBarHeight + 80,
        onClose: (): void => { this.presentation.closeRecap(); },
        onRefresh: (): void => { this.presentation.openRecap(true); },
        onLocate: (node: ConversationRecapNode): void => { this.presentation.locateRecapNode(node); },
        onNextStep: (text: string): void => { this.presentation.closeRecap(); this.composer.setInputText(text); },
      })
    }

    ChatConversationDrawerView({ session: this.drawer,
      currentConversationId: this.presentation.conversation?.id ?? '', runningIds: this.presentation.runningConversationIds,
      userNickname: this.presentation.userNickname, userAvatar: this.presentation.userAvatar,
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
