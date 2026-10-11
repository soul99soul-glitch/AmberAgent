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
import { fileIo, picker } from '@kit.CoreFileKit';
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
  SmartUiStateAccessor, SmartUiStateValue, SmartUiDraftStore, SmartUiDraftSaveResult,
  SmartUiWeatherProjection,
} from '@amber/chat-domain';
import {
  currentMessages, makeConversation, newId, seedConversationWithPresets, nodeCurrentMessage,
  selectMessageBranch, deleteMessage, editMessage, buildChatTimelineRows, forkConversation, patchConversation,
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
  appendSmartUiDraft,
  smartUiDefinitionFingerprint,
  projectWeatherReadTool,
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
import { imageGenToolAvailable, EditSourceImage } from '../platform_impl/ImageGenTool.ets';

import { JsSandboxHost } from '../platform_impl/JsSandboxTool.ets';

import { createNativePluginJsTransport } from '../platform_impl/NativePluginJsTransport.ets';
import type { ConversationRunSnapshot, ConversationRunHandle, ConversationRunCommand,
  ConversationSendCommand, ConversationRegenerateCommand, ConversationResumeCommand,
  ConversationEditCommand, ConversationVerdictCommand } from '../platform_impl/ConversationRunService.ets';
import { bindConversationBrowserPage } from '../platform_impl/ConversationBrowserGuard.ets';
import { SubAgentActivityStrip } from '../components/SubAgentActivityStrip.ets';
import { cleanupDeletedConversation } from '../platform_impl/ManagedFileStore.ets';
import { getChatSmartUiDraftStore } from '../platform_impl/SmartUiDraftSupport.ets';

// D-122:沙盒 sheet 代码块底色(设置面 ROW 惯例同值)
interface ChatArchiveRouteParams { conversationId: string; nodeId: string; }

const ROW: string = SURFACE2;

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

// accent rgba 混色(建议 chips 描边等;与 tokens 令牌 #RRGGBB 配合)
const accentAlpha = (hex: string, alpha: number): string => {
  const value: string = hex.replace('#', '');
  if (value.length !== 6) return hex;
  const r: number = parseInt(value.substring(0, 2), 16);
  const g: number = parseInt(value.substring(2, 4), 16);
  const b: number = parseInt(value.substring(4, 6), 16);
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
};

// 待发文件附件(选取时一次性读入并转 data: URL)
interface PendingDoc {
  url: string;
  fileName: string;
  mime: string;
}

const MIME_BY_EXT: Record<string, string> = {};
MIME_BY_EXT.pdf = 'application/pdf';
MIME_BY_EXT.txt = 'text/plain';
MIME_BY_EXT.md = 'text/markdown';
MIME_BY_EXT.csv = 'text/csv';
MIME_BY_EXT.json = 'application/json';
MIME_BY_EXT.doc = 'application/msword';
MIME_BY_EXT.png = 'image/png';
MIME_BY_EXT.jpg = 'image/jpeg';
MIME_BY_EXT.jpeg = 'image/jpeg';
MIME_BY_EXT.gif = 'image/gif';
MIME_BY_EXT.webp = 'image/webp';
MIME_BY_EXT.zip = 'application/zip';
MIME_BY_EXT.docx = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
MIME_BY_EXT.xlsx = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
MIME_BY_EXT.pptx = 'application/vnd.openxmlformats-officedocument.presentationml.presentation';

const mimeByName = (name: string): string => {
  const dot: number = name.lastIndexOf('.');
  if (dot < 0) return 'application/octet-stream';
  const ext: string = name.substring(dot + 1).toLowerCase();
  const mime: string | undefined = MIME_BY_EXT[ext];
  return mime !== undefined ? mime : 'application/octet-stream';
};

// generate_image edit 源格式白名单(gpt-image 编辑上传口径)
const isAllowedEditMime = (mime: string): boolean =>
  mime === 'image/png' || mime === 'image/jpeg' || mime === 'image/webp';

const editMimeExt = (mime: string): string =>
  mime === 'image/jpeg' ? 'jpg' : (mime === 'image/webp' ? 'webp' : 'png');

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
type MsgRow = ChatTimelineRow;

const buildRows = (conv: Conversation, previousRows: MsgRow[] = []): MsgRow[] =>
  buildChatTimelineRows(conv.messageNodes, previousRows);

// Phase 5:node 窗口 → rows(与 buildRows 同一映射;窗口用 messageNodes 切片,
//   保证首屏后置定位参数与分支切换走同一 MsgRow 形状)
const buildRowsFromNodes = (nodes: MessageNode[], previousRows: MsgRow[] = []): MsgRow[] =>
  buildChatTimelineRows(nodes, previousRows);

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
}

const EMPTY_SMART_UI_VALUES: SmartUiStateValue[] = [];

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
  @StorageProp('activeArtifactInteraction') activeArtifactInteraction: string = '';
  @StorageProp('chatStorageDegraded') storageDegraded: boolean = false;
  // 两栏(平板 / 折叠屏展开横屏):左侧首页窄栏 + 右侧本页聊天,见 design/adaptive.ets
  @StorageProp(WINDOW_WIDTH_VP_KEY) windowWidthVp: number = 0;
  @StorageProp(WINDOW_HEIGHT_VP_KEY) windowHeightVp: number = 0;
  @State homePaneVisible: boolean = false;
  // 两栏左栏高亮的会话(conversation 非响应式,在 attachConversationRun 统一入口同步)
  @State homePaneActiveId: string = '';
  // conversation 非响应式:尾窗先置 conversationLoaded,全量会话晚一拍才到;
  //   到达时递增,让空态 hero 的判断重算(否则两步间若有重绘,hero 永远不出现)
  @State conversationArrivedTick: number = 0;
  @State conversationLoaded: boolean = false;
  @State restoreReloadFailed: boolean = false;
  private loadedRestoreVersion: number = 0;
  @State documentPreviewOpen: boolean = false;
  @State documentPreviewTitle: string = '';
  @State documentPreviewText: string = '';
  private documentPreviewSeq: number = 0;
  // 产物架(iOS artifact shelf 对齐:消息 part 派生的图片/文档/小应用陈列)
  @State artifactShelfOpen: boolean = false;
  // D0-12:同名文档版本对比(soul diff 同款诚实行级摘要)
  @State artifactDiffOpen: boolean = false;
  @State artifactDiffTitle: string = '';
  @State artifactDiffText: string = '';
  @State shelfArtifacts: ConversationArtifactIndex = {
    images: [], docs: [], miniApps: [], widgets: [], svg: [], scenes: [], uiSnapshots: [], duplicateDocumentNames: [],
  };
  @State displaySettings: DisplaySetting = makeDisplaySetting();
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
  private searchSettingsSeq: number = 0;
  private quickMessagesSeq: number = 0;
  @State greetName: string = '';
  @State cursorOn: boolean = true;
  @State @Watch('refreshNativeContextEstimate') rows: MsgRow[] = [];
  // Phase 5:历史分页窗口 — this.conversation 恒全量(生成/checkpoint/上下文),
  //   rows 只承载尾部窗口 + 已上滑加载的历史页(展示层)
  @State timelineLoadState: ConversationTimelineLoadState = createInitialTimelineLoadState();
  @State prefetching: boolean = false;
  // 最近一次历史页前插计数(锚点补偿已在加载回调内就地执行,无需持久读)
  @State streamingTail: UIMessage[] = [];
  @State suggestions: string[] = [];
  // D0-10:@Watch 驱动草稿持久化(任何赋值点 — 发送清空/手动输入 — 都落 KV)
  @State @Watch('onInputTextDraftChange') inputText: string = '';
  // P2: native Smart UI state stays page-owned and keyed by message source identity.
  @State @Watch('onSmartUiValuesChanged') smartUiValues: SmartUiValueEntry[] = [];
  @State smartUiStateRevision: number = 0;
  private smartUiRuntime: Map<string, SmartUiRuntimeEntry> = new Map<string, SmartUiRuntimeEntry>();
  private smartUiDraftStore: SmartUiDraftStore | null = null;
  private smartUiLoadEpoch: number = 0;
  private smartUiSaveError: string = '';
  private smartUiProjectionConversation: Conversation | null = null;
  private smartUiProjectionNodes: MessageNode[] | null = null;
  private smartUiWeatherProjections: SmartUiWeatherProjection[] = [];
  private draftSaveTimer: number = -1;
  @State quickMessages: QuickMessage[] = [];
  @State sending: boolean = false;
  @State recapOpen: boolean = false;
  @State recapSourceLoading: boolean = false;
  @State recapSource: Conversation | null = null;
  @State recapState: RecapState = { recap: null, loading: false, error: '' };
  private recapOpenSeq: number = 0;
  private unsubscribeRecap: (() => void) | null = null;
  // D-087:任务模型选择快照(compress/title/suggestion;aboutToAppear 异步加载,
  //   与 ocrSeed/searchSettings 同档;null = 未解析或无可用模型 → 回退 chat 运行态)
  private compressChoice: ChatProviderChoice | null = null;
  private taskChoicesLoaded: boolean = false;
  // D-094:任务提示词快照(compress 同步工具构建位使用;aboutToAppear 加载,
  //   与 compressChoice/ocrSeed 同档;title/suggestion 走调用时新取)
  private taskPromptsCache: TaskPrompts | null = null;
  // D-070:搜索偏好(aboutToAppear 异步加载;SearchTools 工具组输入)
  // D0-9:@State 化(输入区搜索开关图标态需随 toggle 重绘)
  @State searchSettings: SearchSettings | null = null;
  // D-130:cron 四工具(aboutToAppear 异步水合,与 searchSettings 同档;
  //   水合完成前的极早轮次不带 cron 工具 — 异步加载时序等价先例)
  private agentCronToolsCache: AgentTool[] = [];
  // D-131:AgentTaskTools 六件,与 cron 共用 AgentTaskStore/调度器缓存。
  private agentTaskToolsCache: AgentTool[] = [];
  // D-132c:SubAgentTools 五件(每会话动态挂载)。parentToolsProvider 为惰性闭包,
  //   读 baseToolsCache(在 buildToolLoop 内 step-4 设置),保证只暴露已 profile
  //   的 parent base tools,不含 subagent_* 自身。subagentEnabled + 会话存在才挂载。
  private subAgentEnabled: boolean = false;
  // D0-8:@提及候选角色(aboutToAppear/onPageShow 装配;空数组 = 输入区不启用面板)
  @State mentionRoles: MentionRoleItem[] = [];
  private subAgentMode: string = 'roster';
  // M2.1:ReasoningPicker 段集 + 当前档位(模型感知;aboutToAppear 装配,选档位时刷新)
  @State reasoningLevels: ReasoningLevelOption[] = [];
  @State currentReasoningLevel: string = '';
  // 当前模型内部 id(ReasoningPicker 记忆 key;refreshReasoningPicker 设置)
  private currentModelId: string = '';
  @State errorMsg: string = '';
  // D-108:拒绝理由弹窗态(ToolDenyReasonDialog;空 = 关闭)
  @State denyDialogToolCallId: string = '';
  @State denyDialogOpen: boolean = false;
  @State councilSheetOpen: boolean = false;
  @State councilSheetRunId: string = '';
  @State councilSheetTools: UIMessagePartTool[] = [];
  @State workspacePanelOpen: boolean = false;
  @State archiveInitialMessageId: string = '';
  @State exportSheetOpen: boolean = false;
  @State exportInitialMessageIds: string[] = [];
  @State favoriteNodeIds: string[] = [];
  private favoriteWrites: Set<string> = new Set<string>();
  private denyDialogLocator: ToolApprovalPartLocator | undefined = undefined;
  @State denyReasonText: string = '';
  // D-110:上下文用量指示(ContextUsageIndicator)— compacts/窗口快照 + 压缩活动计数
  @State @Watch('refreshNativeContextEstimate') contextCompacts: ConversationCompact[] = [];
  @State contextWindowTokens: number | null = null;
  @State compactActiveCount: number = 0;
  @State autoCloseThinking: boolean = true;
  private contextEstimateConversation: Conversation | null = null;
  private contextEstimateCompacts: ConversationCompact[] | null = null;
  private contextEstimateTokens: number = 0;
  @State nativeContextTokens: number | null = null;
  private contextTokenModelId: string = '';
  private contextTokenizerId: string | null = null;
  private contextChoiceSeq: number = 0;
  private nativeContextSeq: number = 0;
  private nativeContextNodes: MessageNode[] | null = null;
  private nativeContextCompacts: ConversationCompact[] | null = null;
  private nativeContextModelId: string = '';
  private nativeContextConversationId: string = '';
  @State title: string = '新对话';
  @State modelLabel: string = '';
  // TopModelMenu:顶部模型快速切换下拉(Android TopModelMenu.kt)
  @State modelMenuOpen: boolean = false;
  @State chatHeaderHeight: number = 0;
  // 装配请求由关闭/离页失效，迟到回调不能重开菜单或覆盖下一次请求。
  @State modelMenuLoading: boolean = false;
  private modelMenuSeq: number = 0;
  @State modelMenuGroups: TopModelMenuGroup[] = [];
  @State modelMenuCurrentProvider: string = '';
  @State modelMenuCurrentModel: string = '';
  @State editingNodeId: string = '';
  @State editingMessageId: string = '';
  @State completionNotice: JevCompletionNotice | null = null;
  @State editingTitle: boolean = false;
  @State titleInput: string = '';
  // D-038:待发图片(data: URL 物化,发送后清空)
  @State pendingImages: string[] = [];
  @State pendingDocs: PendingDoc[] = [];
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
  private subAgentSheetSeq: number = 0;
  // Copy sheet
  @State copySheetOpen: boolean = false;
  private copySheetMessage: UIMessage | null = null;
  // Phase 6:消息长按动作 sheet
  @State actionsSheetOpen: boolean = false;
  private actionsSheetRow: MsgRow | null = null;
  // Phase 6:会话抽屉
  @State drawerOpen: boolean = false;
  @State drawerConversations: ConversationSummary[] = [];
  @State drawerMessageHits: MessageSearchHit[] = [];
  // Tool preview sheet
  @State toolPreviewSheetOpen: boolean = false;
  private toolPreviewPart: UIMessagePartTool | null = null;
  private toolPreviewLocator: ToolApprovalPartLocator | null = null;
  private drawerSearchText: string = '';
  private drawerSearchSeq: number = 0;
  @State pendingQueue: PendingUserMessage[] = [];
  @State queueSheetOpen: boolean = false;
  @State compactSummaryOpen: boolean = false;
  @State compactSummary: string = "";
  @State manualCompacting: boolean = false;
  private initialImagePickerConsumed: boolean = false;
  private pickerToken: number = 0;
  @State materializingImages: boolean = false;
  private sendPrechecking: boolean = false;
  private sendPreflightSeq: number = 0;
  // D-050:重试状态文案(strings.xml:386;null = 清除)
  @State retryStatus: string = '';
  private conversation: Conversation | null = null;
  private smartUiStateAccessor: SmartUiStateAccessor = {
    get: (sourceKey: string, stateId: string): SmartUiScalar | undefined => this.smartUiValue(sourceKey, stateId),
    set: (sourceKey: string, stateId: string, value: SmartUiScalar): void => {
      this.setSmartUiValue(sourceKey, stateId, value);
    },
  };
  private runConversationId: string = '';
  private currentRunId: string = '';
  private unsubscribeRun: (() => void) | null = null;
  private unsubscribeRunNavigation: (() => void) | null = null;
  private unbindConversationBrowser: (() => void) | null = null;
  private displayedRunConversation: Conversation | null = null;
  // Retain raw service references for comparison, rather than ArkUI-observed part proxies.
  private lastSnapshotTail: UIMessage[] = [];
  private lastSnapshotRegenTail: UIMessage | null = null;
  private unsavedConversationSeed: Conversation | null = null;
  @State runningConversationIds: string[] = [];
  private unsubscribeRunning: (() => void) | null = null;
  // Phase 2:停止生成收口 + 流式 checkpoint
  // 最后一次 raw 快照(onRawFlushSnapshot;checkpoint 投影基)
  // checkpoint 保存 promise 尾(串行化落库,不阻塞 provider chunk 回调)
  private scroller: Scroller = new Scroller();
  private smartUiAssetAreas: Map<string, number> = new Map<string, number>();
  private nativeFollow: NativeTimelineFollow | null = null;
  private streamingFrameRate: StreamingFrameRate = new StreamingFrameRate();
  @StorageProp('appBackgrounded') @Watch('syncStreamingFrameRate') private appBackgrounded: boolean = false;

  private syncStreamingFrameRate(): void {
    this.streamingFrameRate.update(this.pageVisible && !this.appBackgrounded && this.sending);
  }
  // Phase 1:滚动跟随/暂停/跳底/首屏定位
  // @State 驱动跳底按钮显隐(跟随模式/离底状态变化需触发重绘)
  @State followMode: FollowMode = 'idle';
  @State jumpButtonVisible: boolean = false;
  @State scrollNavigationVisible: boolean = false;
  private scrollNavigationHideTimer: number = -1;
  private enableAutoScroll: boolean = true;
  private showMessageJumper: boolean = true;
  // 程序化贴底在流式追加后的同一帧:isAtEnd() 因内容高度尚未重排而滞后,
  //   故用「最近一次程序化贴底且用户未再离开」做 isAtEnd 近似(true = 贴底)。
  private atBottomApprox: boolean = true;
  private listViewportHeight: number = 0;
  // D0-5:@State 化(回到顶部按钮显隐需随滚动重绘)
  @State firstVisibleListIndex: number = -1;
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
  private previousKeyboardAvoidMode: KeyboardAvoidMode = KeyboardAvoidMode.OFFSET;
  private keyboardAvoidanceActive: boolean = false;
  // 保留旧 Context 详情路径；当前用量入口已移至模型面板。
  @State contextMeterOpen: boolean = false;
  // D-071b:OCR 种子(providers/ocr_model/ocr_prompt 快照,aboutToAppear 加载)
  private ocrSeed: OcrSeed | null = null;

  // D-072:agent_runtime 快照(transform 调用时读 = Android settingsFlow.value 语义)
  private agentRuntime: AgentRuntimeSnapshot = defaultAgentRuntimeSnapshot();
  // generate_image 装配门(execute 仍实时解析;aboutToAppear/onPageShow 探测刷新)
  @State imageGenConfigured: boolean = false;
  // MiniApp:miniapp setting 快照(enabled 门;aboutToAppear 加载,
  //   transform 调用时读 = Android ctx.settings.agentRuntime.miniApp 语义)
  private miniAppSetting: MiniAppSetting = makeMiniAppSetting();
  // D-073:display_setting 用户信息快照(抽屉与 placeholder 共用)
  @State userNickname: string = '';
  @State userAvatar: string = '';

  // MiniApp:repository 惰性单例(output transformer 依赖;Android Koin inject 等价)

  // 页面退订可见状态；运行服务继续持有会话，离页不取消正在生成的内容。
  aboutToDisappear(): void {
    AppStorage.setOrCreate<string>('activeArtifactInteraction', '');
    this.sendPreflightSeq++;
    this.sendPrechecking = false;
    this.hideScrollNavigation();
    this.restoreKeyboardAvoidance();
    this.pageAlive = false;
    this.pageVisible = false;
    this.syncStreamingFrameRate();
    this.nativeFollow?.cancel();
    this.closeRecap();
    this.queueSheetOpen = false;
    this.compactSummaryOpen = false;
    this.councilSheetOpen = false;
    this.workspacePanelOpen = false;
    this.modelMenuSeq++;
    this.modelMenuLoading = false;
    this.detachConversationRun();
    if (this.runConversationId.length > 0) getConversationRunService().discardNewConversation(this.runConversationId);
    this.contextChoiceSeq++;
    this.nativeContextSeq++;
    this.nativeContextNodes = null;
    this.nativeContextCompacts = null;
    this.flushInputDraft();
    this.webmountHookBinding?.unbind();
    this.webmountHookBinding = null;
    this.detachKeyboardListener();
    this.imeKeyboardHeightVp = 0;
    if (this.greetTimer !== -1) { clearInterval(this.greetTimer); this.greetTimer = -1; }
    if (this.cursorTimer !== -1) { clearInterval(this.cursorTimer); this.cursorTimer = -1; }
    this.invalidatePicker(true);
    this.closeSubAgentSheet();
    this.smartUiDraftStore?.flush().catch((error: Error): void => {
      this.smartUiSaveError = `智能 UI 草稿保存失败：${error.message}`;
    });
  }

  private serviceOwnsConversation(id: string): boolean {
    const snapshot: ConversationRunSnapshot | null = getConversationRunService().snapshot(id);
    return snapshot !== null && (snapshot.sending || snapshot.conversation !== null);
  }

  private detachConversationRun(): void {
    this.unsubscribeRecap?.(); this.unsubscribeRecap = null;
    this.unsubscribeRun?.(); this.unsubscribeRun = null;
    this.unsubscribeRunning?.(); this.unsubscribeRunning = null;
    this.unsubscribeRunNavigation?.(); this.unsubscribeRunNavigation = null;
    this.unbindConversationBrowser?.(); this.unbindConversationBrowser = null;
  }

  private attachConversationRun(id: string, reloadIdle: boolean = false): void {
    this.refreshFavorites(id);
    this.detachConversationRun();
    this.runConversationId = id;
    this.homePaneActiveId = id;
    this.unsubscribeRecap = getConversationRecapService().subscribe(id, (state: RecapState): void => {
      if (this.pageAlive && this.pageVisible && this.runConversationId === id) this.recapState = state;
    });
    this.unsubscribeRunning = getConversationRunService().subscribeRunning((ids: string[]): void => {
      if (this.pageAlive && this.pageVisible) this.runningConversationIds = ids;
    });
    let first: boolean = true;
    this.unsubscribeRun = getConversationRunService().subscribe(id, (snapshot: ConversationRunSnapshot): void => {
      if (!this.pageAlive || !this.pageVisible || this.runConversationId !== id) return;
      this.applyConversationRunSnapshot(snapshot, !first);
      first = false;
    });
    if (!this.pageVisible) return;
    this.unsubscribeRunNavigation = getConversationRunService().subscribeNavigation(id, (event: DeepReadOpenEvent): void => {
      if (this.pageAlive && this.pageVisible && this.runConversationId === id) this.openDeepRead(event);
    });
    this.unbindConversationBrowser = bindConversationBrowserPage(id);
    this.webmountHookBinding?.unbind();
    this.webmountHookBinding = getWebMountSession().bindHooks({
      onMountNeeded: (): void => { if (this.pageAlive && this.pageVisible) this.webmountHostVisible = true; },
    });
    if (reloadIdle && !this.serviceOwnsConversation(id)) {
      getChatRepository().getById(id).then((saved: Conversation | null): void => {
        if (!this.pageAlive || !this.pageVisible || this.runConversationId !== id || this.serviceOwnsConversation(id)) return;
        if (saved === null) {
          if (this.unsavedConversationSeed === null) {
            this.errorMsg = '会话不存在或已删除'; this.conversationLoaded = false;
            this.conversation = null; this.rows = []; this.suggestions = [];
          }
          return;
        }
        const previous: Conversation | null = this.displayedRunConversation ?? this.conversation;
        this.unsavedConversationSeed = null;
        this.conversation = saved; this.displayedRunConversation = saved; this.conversationLoaded = true;
        this.refreshRowsAfterConversationChange(previous, saved);
        this.title = saved.title || '对话'; this.suggestions = saved.chatSuggestions;
      }).catch((error: Error): void => {
        if (this.pageAlive && this.pageVisible && this.runConversationId === id) this.errorMsg = error.message;
      });
    }
  }

  private streamStructureChanged(previous: UIMessage | null, next: UIMessage | null): boolean {
    if (previous === next) return false;
    if (previous === null || next === null || previous.id !== next.id ||
      previous.parts.length !== next.parts.length) return true;
    for (let index: number = 0; index < next.parts.length; index++) {
      const oldPart: UIMessagePart = previous.parts[index];
      const nextPart: UIMessagePart = next.parts[index];
      if (oldPart.type !== nextPart.type || (nextPart.type !== 'text' && oldPart !== nextPart)) return true;
    }
    return false;
  }

  private applyConversationRunSnapshot(snapshot: ConversationRunSnapshot, settle: boolean): void {
    const wasSending: boolean = this.sending;
    const oldRunId: string = this.currentRunId;
    const tailStructureChanged: boolean = this.lastSnapshotTail.length !== snapshot.streamingTail.length ||
      this.lastSnapshotTail.some((message: UIMessage, index: number): boolean =>
        this.streamStructureChanged(message, snapshot.streamingTail[index])) ||
      this.streamStructureChanged(this.lastSnapshotRegenTail, snapshot.regenTail);
    this.lastSnapshotTail = snapshot.streamingTail;
    this.lastSnapshotRegenTail = snapshot.regenTail;
    let timelineChanged: boolean = false;
    this.currentRunId = snapshot.runId;
    this.completionNotice = snapshot.completionNotice;
    if (this.sending && !snapshot.sending) {
      // 一轮结束:agent 可能已用 settings_set_model_slot 改了对话/视觉模型,刷新页头与图片守卫快照
      this.refreshEffectiveModelState();
      this.reloadOcrSeed();
    }
    this.sending = snapshot.sending;
    this.syncStreamingFrameRate();
    this.streamingTail = snapshot.streamingTail;
    this.regenTail = snapshot.regenTail;
    this.regenNodeId = snapshot.regenNodeId;
    this.retryStatus = snapshot.retryStatus;
    this.errorMsg = snapshot.errorMsg;
    this.liveActivity = snapshot.liveActivity;
    this.pendingQueue = snapshot.pendingQueue;
    this.syncPendingRows();
    if (snapshot.conversation !== null) this.contextCompacts = snapshot.contextCompacts;
    this.compactActiveCount = snapshot.compactActiveCount;
    if (snapshot.contextWindowTokens !== null) this.contextWindowTokens = snapshot.contextWindowTokens;
    if (snapshot.modelLabel.length > 0) this.modelLabel = snapshot.modelLabel;
    this.jsSandboxNeeded = snapshot.jsSandboxNeeded;
    this.kernelPhase = snapshot.phase;
    if (snapshot.conversationDeleted) {
      this.conversation = null; this.displayedRunConversation = null; this.unsavedConversationSeed = null;
      this.rows = []; this.suggestions = []; this.conversationLoaded = false;
      this.errorMsg = '会话已删除';
      return;
    }
    const full: Conversation | null = snapshot.conversation;
    if (full !== null) {
      this.unsavedConversationSeed = null;
      if (this.conversation !== full) this.conversation = full;
      this.reconcileSmartUiSources(full);
      this.conversationLoaded = true;
      const nextTitle: string = full.title || '对话';
      if (this.title !== nextTitle) this.title = nextTitle;
      const nextSuggestions: string[] = full.chatSuggestions;
      const suggestionsChanged: boolean = this.suggestions.length !== nextSuggestions.length ||
        this.suggestions.some((value: string, index: number): boolean => value !== nextSuggestions[index]);
      if (suggestionsChanged) this.suggestions = nextSuggestions;
      const visible: Conversation = snapshot.sending ? (snapshot.visualConversation ?? full) : full;
      if (!this.timelineLoadState.initialized) {
        timelineChanged = true;
        const start: number = Math.max(0, visible.messageNodes.length - TIMELINE_PAGE_SIZE);
        this.rows = buildRowsFromNodes(visible.messageNodes.slice(start), this.rows);
        this.timelineLoadState = applyTailWindow(this.timelineLoadState, start,
          visible.messageNodes.length - start, visible.messageNodes.length);
      } else if (this.displayedRunConversation !== visible) {
        // Streaming deltas live in the tail; immutable history changes get a new visible reference.
        timelineChanged = true;
        this.refreshRowsAfterConversationChange(this.displayedRunConversation, visible);
      }
      this.displayedRunConversation = visible;
    }
    // Text growth has onTextGrow; reasoning/tool updates have no such callback and follow here.
    if (snapshot.sending && (timelineChanged || tailStructureChanged)) this.followStreamingUpdate();
    if (settle && wasSending && !snapshot.sending && oldRunId === snapshot.runId) {
      this.handleGenerateEnd(this.atBottomApprox);
      if (snapshot.phase === 'completed') this.fireGenerationHaptic();
      if (!this.userTouchActive && this.followMode !== 'paused') this.scrollToBottom();
    }
  }

  private submitConversationRun(command: ConversationRunCommand): boolean {
    if (!this.pageAlive || this.conversation === null) return false;
    const id: string = this.conversation.id;
    if (conversationRestoreVersion(id) !== this.loadedRestoreVersion) {
      this.clearSmartUiMemory();
      this.refreshRestoredConversation().catch((error: Error): void => { if (this.pageAlive) this.errorMsg = error.message; });
      this.errorMsg = '会话已恢复，正在刷新，请稍后重试';
      return false;
    }
    if (this.unsubscribeRun === null || this.runConversationId !== id) this.attachConversationRun(id);
    try {
      const handle: ConversationRunHandle = getConversationRunService().start(id, command);
      handle.done.catch((error: Error): void => {
        if (this.pageAlive && this.pageVisible && this.runConversationId === id && this.currentRunId === handle.runId)
          this.errorMsg = this.compactErrorText(error);
      });
      return true;
    } catch (error) {
      this.errorMsg = error instanceof Error ? error.message : String(error);
      return false;
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

  // D0-10:输入草稿跨会话保留 — 已持久化会话键 chat_input_draft/{conversationId};
  //   未发送过消息的新会话用稳定键 __unsaved__(fresh.id 每次进页都变,不可作键)。
  //   600ms 尾随去抖;空串即清除(读侧按空串判无草稿);清空时同步清 __unsaved__。
  private inputDraftKey(): string {
    const conv: Conversation | null = this.conversation;
    if (conv === null || conv.messageNodes.length === 0) return 'chat_input_draft/__unsaved__';
    return `chat_input_draft/${conv.id}`;
  }

  private onInputTextDraftChange(): void {
    if (this.draftSaveTimer !== -1) clearTimeout(this.draftSaveTimer);
    this.draftSaveTimer = setTimeout((): void => {
      this.draftSaveTimer = -1;
      this.persistInputDraft();
    }, 600);
  }

  private persistInputDraft(): void {
    if (this.conversation === null) return;
    getChatKvStore().put(this.inputDraftKey(), this.inputText).catch((): void => {});
    if (this.inputText.length === 0) {
      getChatKvStore().put('chat_input_draft/__unsaved__', '').catch((): void => {});
    }
  }

  // 离页兜底:去抖窗口内返回会丢草稿(pageAlive 守卫),onPageHide 同步 flush
  private flushInputDraft(): void {
    if (this.draftSaveTimer === -1) return;
    clearTimeout(this.draftSaveTimer);
    this.draftSaveTimer = -1;
    this.persistInputDraft();
  }

  private restoreInputDraft(key: string): void {
    getChatKvStore().get(key)
      .then((raw: string | null): void => {
        if (!this.pageAlive || raw === null || raw.length === 0) return;
        // 页面已有更新的输入(如 initialText 直达发送)时不覆盖
        if (this.inputText.length > 0) return;
        this.inputText = raw;
      })
      .catch((): void => {});
  }

  // D0-8:@提及候选装配(subagent 内置/自定义角色 + 议会入口;
  //   嵌套 then 规避 ArkTS Promise.all 异构元组推断)
  private refreshMentionRoles(): void {
    hydrateSubAgentSetting()
      .then((s): Promise<void> =>
        isModelCouncilEnabled().then((councilOn: boolean): void => {
          if (!this.pageAlive) return;
          this.mentionRoles = buildMentionRoleItems(s.enabled, councilOn, s.mode, s.customDefinitions);
        }))
      .catch((): void => {});
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
    this.enableKeyboardAvoidance();
    this.pageVisible = true;
    this.homePaneVisible = true;
    this.syncStreamingFrameRate();
    if (this.conversation !== null) this.attachConversationRun(this.conversation.id, true);
    this.startGreetTimer();
    this.refreshRestoredConversation().catch((e: Error): void => {
      if (this.pageAlive) this.errorMsg = e.message;
    });
    this.themeTick = Date.now();
    if (this.cursorTimer === -1) {
      this.cursorTimer = setInterval((): void => {
        if (this.pageAlive && this.pageVisible) this.cursorOn = !this.cursorOn;
      }, 525);
    }
    this.attachKeyboardListener();
    // D-070 补:从搜索设置页返回后重载偏好,否则继续用旧工具组/旧开关
    this.refreshSearchSettings();
    // 从任务模型页返回后重探生图槽(工具装配门)
    imageGenToolAvailable().then((ok: boolean): void => {
      if (this.pageAlive) this.imageGenConfigured = ok;
    }).catch((): void => {});
    if (!this.hasShownOnce) {
      this.hasShownOnce = true;
      return;
    }
    // P4:页面回到前台/从子页返回时刷新模型/上下文/显示设置快照
    //   (onPageShow 每次入页触发,与 aboutToAppear 同异步 helper;pageAlive 守卫防离页写 UI)
    this.refreshEffectiveModelState();
    loadDisplaySetting(getChatKvStore()).then((setting: DisplaySetting): void => {
      if (!this.pageAlive) return;
      this.displaySettings = setting;
      this.userNickname = setting.userNickname;
      this.userAvatar = setting.userAvatar;
      this.enableAutoScroll = setting.enableAutoScroll;
      this.showMessageJumper = setting.showMessageJumper;
      this.enableGenerationNotification = setting.enableNotificationOnMessageGeneration;
      this.loadWebMountGlobalToggles();
      this.autoCloseThinking = setting.autoCloseThinking;
    });
    // MiniApp:设置页改 enabled 后返回,刷新快照(transform enabled 门;
    //   aboutToAppear 仅首载,Android settingsFlow collect 持续语义的页面级近似)
    loadMiniAppSetting(getChatKvStore()).then((setting: MiniAppSetting): void => {
      if (this.pageAlive) this.miniAppSetting = setting;
    });
    // D-110:压缩/会话可能已变更,刷新足迹(生成中仅更新展示,不打断)
    this.refreshContextCompacts();
    this.refreshMentionRoles();
  }

  onPageHide(): void {
    AppStorage.setOrCreate<string>('activeArtifactInteraction', '');
    this.sendPreflightSeq++;
    this.sendPrechecking = false;
    this.hideScrollNavigation();
    this.restoreKeyboardAvoidance();
    this.pageVisible = false;
    this.homePaneVisible = false;
    this.syncStreamingFrameRate();
    this.nativeFollow?.cancel();
    this.closeRecap();
    this.closeModelMenu();
    this.closeSubAgentSheet();
    this.councilSheetOpen = false;
    this.workspacePanelOpen = false;
    this.queueSheetOpen = false;
    this.compactSummaryOpen = false;
    this.detachConversationRun();
    this.webmountHookBinding?.unbind();
    this.webmountHookBinding = null;
    this.stopGreetTimer();
    if (this.cursorTimer !== -1) {
      clearInterval(this.cursorTimer);
      this.cursorTimer = -1;
    }
    this.detachKeyboardListener();
    this.imeKeyboardHeightVp = 0;
  }

  onBackPress(): boolean {
    if (this.workspacePanelOpen) { this.workspacePanelOpen = false; return true; }
    if (this.councilSheetOpen) { this.councilSheetOpen = false; return true; }
    if (this.recapOpen) { this.closeRecap(); return true; }
    if (this.exportSheetOpen) { this.exportSheetOpen = false; return true; }
    if (this.documentPreviewOpen) { this.documentPreviewOpen = false; return true; }
    if (this.denyDialogOpen) { this.dismissDenyDialog(); return true; }
    if (this.modelMenuOpen || this.modelMenuLoading) { this.closeModelMenu(); return true; }
    if (this.contextMeterOpen) { this.contextMeterOpen = false; return true; }
    if (this.drawerOpen) { this.closeDrawer(); return true; }
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

  private closeRecap(): void {
    this.recapOpen = false;
    this.recapSourceLoading = false;
    this.recapOpenSeq++;
  }

  private async openRecap(force: boolean = false): Promise<void> {
    const id: string = this.conversation?.id ?? '';
    if (id.length === 0) return;
    if (this.isGenerationActive()) { promptAction.showToast({ message: '回复完成后可查看回顾' }); return; }
    this.closeModelMenu();
    const seq: number = ++this.recapOpenSeq;
    // The window's keyboard avoidance can otherwise move the anchored panel off screen.
    try { await inputMethod.getController().stopInputSession(); } catch (_error) {}
    if (!this.pageAlive || !this.pageVisible || this.recapOpenSeq !== seq || this.conversation?.id !== id) return;
    this.recapOpen = true;
    this.recapSourceLoading = true;
    try {
      // Read the full branch: the timeline may currently display only its tail window.
      const full: Conversation | null = await getChatRepository().getById(id);
      if (!this.pageAlive || !this.pageVisible || this.recapOpenSeq !== seq || this.conversation?.id !== id) return;
      if (full === null || !recapEligible(full)) {
        this.closeRecap(); promptAction.showToast({ message: '再聊几轮就能回顾（至少 3 条用户消息）' }); return;
      }
      this.recapSource = full;
      this.recapSourceLoading = false;
      await getConversationRecapService().request(id, force);
    } catch (error) {
      if (this.pageAlive && this.pageVisible && this.recapOpenSeq === seq) {
        this.recapSourceLoading = false;
        const state: RecapState = this.recapState;
        this.recapState = { recap: state.recap, loading: false, error: (error as Error).message };
      }
    }
  }

  private displayedRecap(): ConversationRecap | null {
    const recap: ConversationRecap | null = this.recapState.recap;
    const source: Conversation | null = this.recapSource;
    return recap === null || source === null ? null : projectRecap(recap, source);
  }

  private recapNeedsRefresh(): boolean {
    return this.recapState.recap !== null && this.recapSource !== null
      && recapIsStale(this.recapState.recap, this.recapSource);
  }

  private locateRecapNode(node: ConversationRecapNode): void {
    const full: Conversation | null = this.recapSource;
    if (full === null || node.messageId === null) return;
    const index: number = full.messageNodes.findIndex((item: MessageNode): boolean =>
      nodeCurrentMessage(item).id === node.messageId);
    if (index < 0) return;
    this.closeRecap();
    this.followMode = 'paused';
    // The recap already holds the full branch. Reuse it to reach older sources
    // directly rather than hitting the general search's pagination limit.
    if (index < this.timelineLoadState.oldestLoadedIndex) {
      this.rows = buildRowsFromNodes(full.messageNodes.slice(index), this.rows);
      this.timelineLoadState = applyTailWindow(this.timelineLoadState, index,
        full.messageNodes.length - index, full.messageNodes.length);
    }
    this.positionToTargetNode(full.messageNodes[index].id, index);
  }

  // ===== P4:IME 键盘跟随(ImeAutoScroller 语义) =====

  private enableKeyboardAvoidance(): void {
    if (this.keyboardAvoidanceActive) return;
    this.previousKeyboardAvoidMode = this.getUIContext().getKeyboardAvoidMode();
    // 让 layoutWeight(1) 的消息区压缩，避免默认 OFFSET 把顶栏抬出屏幕。
    this.getUIContext().setKeyboardAvoidMode(KeyboardAvoidMode.RESIZE);
    this.keyboardAvoidanceActive = true;
  }

  private restoreKeyboardAvoidance(): void {
    if (!this.keyboardAvoidanceActive) return;
    this.getUIContext().setKeyboardAvoidMode(this.previousKeyboardAvoidMode);
    this.keyboardAvoidanceActive = false;
  }

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

  aboutToAppear(): void {
    this.enableKeyboardAvoidance();
    this.pageAlive = true;
    this.smartUiDraftStore = getChatSmartUiDraftStore(getChatKvStore());
    this.refreshNativeContextEstimate();
    this.pageVisible = true;
    this.syncStreamingFrameRate();
    this.initialJumpSuppressedUntilUserScroll = false;
    // WebMount 会话宿主:首次 open 时按需挂载(避免常驻隐藏 Web)
    this.webmountHookBinding?.unbind();
    this.webmountHookBinding = getWebMountSession().bindHooks({
      onMountNeeded: (): void => { if (this.pageAlive) this.webmountHostVisible = true; },
    });
    this.loadWebMountGlobalToggles();
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
    this.refreshQuickMessages();
    hydrateSubAgentSetting().then((setting): void => {
      if (!this.pageAlive) return;
      this.subAgentEnabled = setting.enabled; this.subAgentMode = setting.mode;
    });
    // D-110:上下文窗口快照(ModelRegistry.MODEL_CONTEXT_WINDOW 注册表查询
    //   = P1 登记;取持久化 model.contextWindowTokens = Android 未知模型
    //   回退路径 :110-111)
    this.refreshEffectiveModelState();
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
    this.reloadOcrSeed();
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
    });
    // generate_image 装配门:生图槽解析到 OpenAI 兼容 image 模型才注册工具
    imageGenToolAvailable().then((ok: boolean): void => {
      if (this.pageAlive) this.imageGenConfigured = ok;
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
      this.displaySettings = setting;
      this.userNickname = setting.userNickname;
      this.userAvatar = setting.userAvatar;
      this.enableAutoScroll = setting.enableAutoScroll;
      this.showMessageJumper = setting.showMessageJumper;
      this.enableGenerationNotification = setting.enableNotificationOnMessageGeneration;
      this.loadWebMountGlobalToggles();
      this.autoCloseThinking = setting.autoCloseThinking;
      if (!this.enableAutoScroll) this.followMode = 'idle';
    });
    this.refreshMentionRoles();
    const params = router.getParams() as Record<string, string>;
    const convId: string = params ? (params['conversationId'] ?? '') : '';
    const recipeManualId: string = params ? (params['recipeManualToolCallId'] ?? '') : '';
    const pluginManualId: string = params ? (params['pluginManualToolCallId'] ?? '') : '';
    const manualId: string = recipeManualId.length > 0 ? recipeManualId : pluginManualId;
    this.loadedRestoreVersion = conversationRestoreVersion(convId);
    // Phase 1:首屏定位参数(最小兼容:initialMessageIndex 数字 index;nodeId 定位行)
    const initialIndexParam: string = params ? (params['initialMessageIndex'] ?? '') : '';
    const initialNodeIdParam: string = params ? (params['nodeId'] ?? '') : '';
    if (convId.length > 0) {
      const existingRun: ConversationRunSnapshot | null = getConversationRunService().snapshot(convId);
      this.attachConversationRun(convId);
      if (existingRun !== null && (existingRun.sending || existingRun.conversation !== null)) {
        this.restoreInputDraft(`chat_input_draft/${convId}`);
        if (existingRun.conversation !== null && initialNodeIdParam.length > 0) {
          const index: number = existingRun.conversation.messageNodes.findIndex((node: MessageNode): boolean => node.id === initialNodeIdParam);
          if (index >= 0) this.positionToTargetNode(initialNodeIdParam, index); else this.applyInitialScroll(-1);
        } else if (existingRun.conversation !== null && initialIndexParam.length > 0) {
          const index: number = Number(initialIndexParam);
          const node: MessageNode | undefined = existingRun.conversation.messageNodes[index];
          if (Number.isInteger(index) && index >= 0 && node !== undefined) this.positionToTargetNode(node.id, index);
          else this.applyInitialScroll(-1);
        } else this.applyInitialScroll(-1);
        return;
      }
      // Phase 5:默认只加载尾部窗口(长会话首帧只渲染最近 TIMELINE_PAGE_SIZE 节点);
      //   this.conversation 仍经 getById 全量装载 — 生成/checkpoint/上下文引擎
      //   照旧读完整会话,rows 仅承载窗口(全量 invariant 见 ensureFullConversationLoaded)
      getChatRepository().getConversationTailWindow(convId, TIMELINE_PAGE_SIZE)
        .then((window): void => {
          if (!this.pageAlive || this.serviceOwnsConversation(convId)) return;
          this.timelineLoadState = applyTailWindow(
            this.timelineLoadState,
            window.oldestLoadedIndex, window.nodes.length, window.totalNodeCount);
          this.rows = buildRowsFromNodes(window.nodes, this.rows);
          this.conversationLoaded = true;
        })
        .catch((e: Error): void => {
          hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'load tail window failed: %{public}s', e.message);
        })
        .then((): Promise<Conversation | null> => this.ensureFullConversationLoaded(convId))
        .then(async (loaded: Conversation | null): Promise<void> => {
          let full: Conversation | null = loaded;
          if (!this.pageAlive || this.serviceOwnsConversation(convId)) return;
          this.conversationLoaded = true;
          if (full === null) {
            if (this.rows.length === 0) this.errorMsg = '会话不存在';
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
                this.rows = buildRowsFromNodes(full.messageNodes.slice(this.timelineLoadState.oldestLoadedIndex), this.rows);
              }
            } catch (error) {
              if (this.pageAlive) this.errorMsg = error instanceof Error ? this.compactErrorText(error) : String(error);
              return;
            }
          }
          this.conversation = full;
          this.reconcileSmartUiSources(full);
          this.conversationArrivedTick++;
          this.restoreInputDraft(`chat_input_draft/${full.id}`);
          this.refreshEffectiveModelState();
          if (this.rows.length === 0 && full.messageNodes.length > 0) {
            this.rows = buildRows(full, this.rows);
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
          if (manualId.length > 0) {
            const messages: UIMessage[] = currentMessages(full);
            const manual: UIMessagePartTool | undefined = messages.length > 0
              ? messages[messages.length - 1].parts.find((part: UIMessagePart): boolean =>
                part.type === 'tool' && (part as UIMessagePartTool).toolCallId === manualId) as UIMessagePartTool | undefined
              : undefined;
            if (manual !== undefined && (manual.metadata?.['recipe_manual'] === true
              || manual.metadata?.['plugin_manual'] === true)
              && manual.output.length === 0 && manual.approvalState.type === 'auto') this.resumeToolLoop(full);
          }
          this.drainPendingQueue();
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
        this.unsavedConversationSeed = this.conversation;
        getConversationRunService().seedNewConversation(this.conversation);
        this.attachConversationRun(fresh.id);
        this.conversationLoaded = true;
        // 未发送新会话:恢复 __unsaved__ 稳定键草稿(fresh.id 每次进页都变,不可作键)
        this.restoreInputDraft('chat_input_draft/__unsaved__');
        this.refreshEffectiveModelState();
        // Phase 5:新会话空窗口 = fullyLoaded(rows 直接全量展示)
        this.rows = buildRows(this.conversation, this.rows);
        this.timelineLoadState = applyTailWindow(
          this.timelineLoadState, 0, this.rows.length, this.rows.length);
        this.suggestions = this.conversation.chatSuggestions;
        // Phase 1:新会话首帧滚底(无目标)
        this.applyInitialScroll(-1);
        // 消费首页传入的 initialText(首页输入框发送);无则显示空状态 hero(不自动发送)
        const initialText: string = params ? (params['initialText'] ?? '') : '';
        const sendMode: string = params ? (params['sendMode'] ?? 'send') : 'send';
        const openImagePicker: boolean = params ? params['openImagePicker'] === 'true' : false;
        if (initialText.length > 0) {
          this.inputText = initialText;
          if (sendMode === 'append') {
            this.sendWithoutAnswer();
          } else {
            this.send();
          }
        }
        if (openImagePicker && !this.initialImagePickerConsumed && this.pageAlive) {
          this.initialImagePickerConsumed = true;
          setTimeout((): void => {
            if (this.pageAlive && this.pageVisible) this.pickImages();
          }, 0);
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
    if (this.runConversationId.length > 0 && this.currentRunId.length > 0) {
      getConversationRunService().cancel(this.runConversationId, this.currentRunId);
    }
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
      this.rows = buildRows(next, this.rows);
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
    this.rows = buildRowsFromNodes(next.messageNodes.slice(start, start + count), this.rows);
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
      const active: ConversationRunSnapshot | null = getConversationRunService().snapshot(convId);
      if (active !== null && (active.sending || active.conversation !== null)) return active.conversation;
      this.conversation = full;
      if (full !== null) this.reconcileSmartUiSources(full);
      return full;
    });
  }

  // 待生成/checkpoint/上下文前,确认窗口已全量(生成只能发生在窗口覆盖全部节点后 —
  //   首屏默认尾窗 50,加载更早历史期间进入生成 → 先补全再开跑)
  private async refreshRestoredConversation(): Promise<void> {
    const current: Conversation | null = this.conversation;
    if (current === null) return;
    const version: number = conversationRestoreVersion(current.id);
    if (version === this.loadedRestoreVersion) return;
    this.clearSmartUiMemory();
    this.conversationLoaded = false;
    this.restoreReloadFailed = false;
    let restored: Conversation | null;
    try {
      restored = await getChatRepository().getById(current.id);
    } catch (e) {
      this.restoreReloadFailed = true;
      throw e instanceof Error ? e : new Error(String(e));
    }
    if (!this.pageAlive || this.conversation?.id !== current.id) return;
    if (restored === null) {
      this.restoreReloadFailed = true;
      throw new Error('恢复后的会话无法读取');
    }
    this.conversation = restored;
    this.rows = buildRows(restored, this.rows);
    this.timelineLoadState = applyTailWindow(
      this.timelineLoadState, 0, restored.messageNodes.length, restored.messageNodes.length);
    this.suggestions = restored.chatSuggestions;
    this.title = restored.title || '对话';
    this.loadedRestoreVersion = version;
    this.conversationLoaded = true;
    this.refreshEffectiveModelState();
  }

  private async ensureFullConversationForMutation(): Promise<void> {
    await this.refreshRestoredConversation();
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
        const older: MsgRow[] = buildRowsFromNodes(page.nodes, this.rows);
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
    const full: Conversation | null = this.conversation;
    if (full !== null && full.messageNodes[targetNodeIndex]?.id === targetNodeId
      && targetNodeIndex < this.timelineLoadState.oldestLoadedIndex) {
      const start: number = Math.max(0, targetNodeIndex - targetNodeIndex % TIMELINE_PAGE_SIZE);
      const nodes: MessageNode[] = full.messageNodes.slice(start);
      this.rows = buildRowsFromNodes(nodes, this.rows);
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
        const older: MsgRow[] = buildRowsFromNodes(page.nodes, this.rows);
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
    const placeholderCount: number = this.timelineLoadState.fullyLoaded ? 0 : 1;
    const compactMarkerCount: number = this.rows.reduce((count: number, row: MsgRow): number =>
      count + (this.compactBoundaryCount(row.message.id) > 0 ? 1 : 0), 0);
    // 再生成替换原行，不增加 item；压缩标记和建议末尾留白各按实际 item 计数。
    return this.rows.length + compactMarkerCount + this.streamingTail.length + placeholderCount +
      (this.suggestions.length > 0 ? 1 : 0);
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

  private hideScrollNavigation(): void {
    if (this.scrollNavigationHideTimer !== -1) clearTimeout(this.scrollNavigationHideTimer);
    this.scrollNavigationHideTimer = -1;
    this.scrollNavigationVisible = false;
  }

  private showScrollNavigation(): void {
    if (this.scrollNavigationHideTimer !== -1) clearTimeout(this.scrollNavigationHideTimer);
    this.scrollNavigationVisible = true;
    this.scrollNavigationHideTimer = setTimeout((): void => {
      this.scrollNavigationHideTimer = -1;
      this.scrollNavigationVisible = false;
    }, 1200);
  }

  private floatingControlsBottom(): number {
    return this.suggestions.length > 0 ? 60 : 12;
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

  private measuredBottomTarget(): number | null {
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

  private requestNativeFollow(): void {
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

  private followStreamingUpdate(): void {
    this.handleStreamUpdate(this.atBottomApprox);
    if (!this.enableAutoScroll ||
      !shouldScrollOnStreamUpdate(this.followMode, this.atBottomApprox)) return;
    this.atBottomApprox = true;
    this.jumpButtonVisible = false;
    this.requestNativeFollow();
  }

  // 用户主动滚动驱动:离开底部 → 暂停;滚回 24vp 范围 → 恢复。
  private handleUserScroll(generating: boolean): void {
    this.nativeFollow?.cancel();
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
    if (initial) {
      this.nativeFollow?.cancel();
      this.beginProgrammaticScroll();
      this.scroller.scrollEdge(Edge.Bottom);
    } else {
      this.requestNativeFollow();
    }
  }

  private jumpToBottom(): void {
    this.nativeFollow?.cancel();
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
    this.nativeFollow?.cancel();
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

  private setContextModel(choice: ChatProviderChoice | null): void {
    this.contextWindowTokens = choice !== null ? choice.model.contextWindowTokens : null;
    this.contextTokenModelId = choice !== null ? choice.model.modelId : '';
    this.contextTokenizerId = nativeTokenizerForModel(this.contextTokenModelId);
    this.refreshNativeContextEstimate();
  }

  // rows changes at committed conversation boundaries, not for streaming chunks.
  // A history-page prepend retains messageNodes and therefore reuses this result.
  private refreshNativeContextEstimate(): void {
    if (!this.pageAlive) return;
    const conv: Conversation | null = this.conversation;
    const nodes: MessageNode[] | null = conv !== null ? conv.messageNodes : null;
    const convId: string = conv !== null ? conv.id : '';
    if (this.nativeContextNodes === nodes && this.nativeContextCompacts === this.contextCompacts
      && this.nativeContextModelId === this.contextTokenModelId
      && this.nativeContextConversationId === convId) return;
    this.nativeContextNodes = nodes;
    this.nativeContextCompacts = this.contextCompacts;
    this.nativeContextModelId = this.contextTokenModelId;
    this.nativeContextConversationId = convId;
    const seq: number = ++this.nativeContextSeq;
    this.nativeContextTokens = null;
    const tokenizerId: string | null = this.contextTokenizerId;
    if (conv === null || tokenizerId === null) return;
    const modelId: string = this.contextTokenModelId;
    countNativeContextTokens(currentMessages(conv), this.contextCompacts, tokenizerId)
      .then((tokens: number): void => {
        if (!this.pageAlive || seq !== this.nativeContextSeq || this.conversation === null
          || this.conversation.id !== convId || this.conversation.messageNodes !== nodes
          || this.contextTokenModelId !== modelId) return;
        this.nativeContextTokens = tokens;
      }).catch((): void => {});
  }

  private estimatedContextTokens(): number {
    if (this.conversation === null) return 0;
    if (this.nativeContextTokens !== null && this.nativeContextNodes === this.conversation.messageNodes
      && this.nativeContextCompacts === this.contextCompacts
      && this.nativeContextModelId === this.contextTokenModelId
      && this.nativeContextConversationId === this.conversation.id) return this.nativeContextTokens;
    if (this.contextEstimateConversation !== this.conversation
      || this.contextEstimateCompacts !== this.contextCompacts) {
      this.contextEstimateConversation = this.conversation;
      this.contextEstimateCompacts = this.contextCompacts;
      this.contextEstimateTokens = estimateConversationInputTokens(
        currentMessages(this.conversation), this.contextCompacts);
    }
    return this.contextEstimateTokens;
  }

  // 压缩边界标记(Android ContextCompactMarker 语义):
  //   完成态压缩范围的末条消息之后在时间线插入「已压缩 N 条」
  private compactBoundaryCount(messageId: string): number {
    for (const c of this.contextCompacts) {
      if (c.status !== 'completed' || c.sourceMessageIds.length === 0) continue;
      if (c.sourceMessageIds[c.sourceMessageIds.length - 1] === messageId) {
        return c.sourceMessageIds.length;
      }
    }
    return 0;
  }

  @Builder
  CompactMarkerRow(row: MsgRow): void {
    if (this.compactBoundaryCount(row.message.id) > 0) {
      Row() {
        Text(`已压缩 ${this.compactBoundaryCount(row.message.id)} 条上下文`)
          .fontSize(META).fontColor(INK3).fontFamily(FONT_SANS)
          .margin({ left: 10, right: 10 })
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
      .padding({ top: 10, bottom: 6 })
      .onClick((): void => {
        const compact: ConversationCompact | undefined = this.contextCompacts.find((c: ConversationCompact): boolean =>
          c.status === 'completed' && c.sourceMessageIds[c.sourceMessageIds.length - 1] === row.message.id);
        if (compact !== undefined) { this.compactSummary = compact.summary; this.compactSummaryOpen = true; }
      })
      .accessibilityText('查看上下文压缩摘要')
    }
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
    const id: string = this.conversation.id;
    getConversationRunService().mutateStoredConversation(id, (latest: Conversation): Conversation | null => {
      if (getConversationRunService().isBusy(id)) throw new Error('该会话仍在运行，请稍后重试');
      return selectMessageBranch(latest, nodeId, idx);
    }).then((updated: Conversation | null): Promise<void> => updated === null ? Promise.resolve()
      : this.invalidateConvCompacts(id, 'message_branch_changed')).catch((error: Error): void => {
        if (this.pageAlive && this.conversation?.id === id) this.errorMsg = error.message;
      });
  }

  // Phase 5 全量 invariant:生成必须基于完整会话(窗口未全量时先补全再开跑;
  //   防首帧尾窗尚未补全就触发重新生成)
  private regenerate(nodeId: string): void {
    if (!this.pageAlive || this.conversation === null || this.sending) return;
    const command: ConversationRegenerateCommand = { kind: 'regenerate', nodeId: nodeId };
    this.submitConversationRun(command);
  }

  // 生成完成通知(Android ChatService.kt:1396-1401 三条件:成功收尾 + 应用后台 + 开关开;
  //   成功条件由调用位置保证 — 仅正常完成路径到达,catch/中断路径提前 return)
  private enableGenerationNotification: boolean = false;
  // WebMount 全局开关(LocalTools.kt:161 语义:globalEnabled || 助手勾选;
  //   KV 键与 SettingExperimentalWebMountPage 约定)
  private webmountGlobalEnabled: boolean = false;
  private webmountEvalEnabled: boolean = false;
  @State webmountHostVisible: boolean = false;
  // WebMount 接管条/面板(bot/hand 租约;C4-2)
  @StorageProp('webmountUrl') webmountUrl: string = '';
  @StorageProp('webmountReady') webmountReady: boolean = false;
  @StorageProp('webmountLease') webmountLease: string = 'bot';
  @State webmountSheetOpen: boolean = false;
  // JS 沙箱宿主挂载门(assistant 勾选 javascript_engine 后置真;C5-3)
  @State jsSandboxNeeded: boolean = false;
  private webmountHookBinding: WebMountHookBinding | null = null;

  // D0-6:生成完成触觉反馈(DisplaySetting.enableMessageGenerationHapticEffect;
  //   canIUse 包裹马达能力 + isSupportEffect 预检,不支持预置降级时长振动;
  //   失败静默 — 触觉是增强不是阻断)
  private fireGenerationHaptic(): void {
    if (!this.displaySettings.enableMessageGenerationHapticEffect) return;
    if (!canIUse('SystemCapability.Sensors.MiscDevice')) return;
    vibrator.isSupportEffect('haptic.notice.success')
      .then((supported: boolean): void => {
        if (supported) {
          vibrator.startVibration(
            { type: 'preset', effectId: 'haptic.notice.success', count: 1 },
            { usage: 'notification' },
          ).catch((): void => {});
        } else {
          vibrator.startVibration(
            { type: 'time', duration: 60 },
            { usage: 'notification' },
          ).catch((): void => {});
        }
      })
      .catch((): void => {});
  }

  // WebMount 全局开关快照(设置页 KV;发送前同步读会打断注册表构造,故预载)
  private loadWebMountGlobalToggles(): void {
    getChatKvStore().get('webmount_global_enabled').then((raw: string | null): void => {
      if (this.pageAlive) this.webmountGlobalEnabled = raw === 'true';
    }).catch((): void => {});
    getChatKvStore().get('webmount_eval_enabled').then((raw: string | null): void => {
      if (this.pageAlive) this.webmountEvalEnabled = raw === 'true';
    }).catch((): void => {});
  }

  private sameConversationTail(a: Conversation, b: Conversation): boolean {
    return JSON.stringify(currentMessages(a)) === JSON.stringify(currentMessages(b));
  }

  // 自动命名(D-025):title 空白时非阻塞调用(对齐 Android 独立 launch);
  // 生成使用快照；落库前重载最新会话，只合并 title，避免覆盖新消息。
  private async maybeAutoTitle(conv: Conversation, force: boolean = false): Promise<void> {
    if (!force && conv.title.trim().length > 0) return;
    let releaseRun: () => void;
    try { releaseRun = beginConversationRun(conv.id); } catch { return; }
    try {
    // D-094:settings.titlePrompt(ChatPrefs.kt:81)→ runAutoTitle deps.template
    //   (auto_title.ts:60;调用时新取 = settingsFlow.value 语义)
    const prompts: TaskPrompts = await loadTaskPrompts();
    const snapshot: Conversation = conv;
    await runAutoTitle(snapshot, {
      // D-087:title 任务模型(AiAuxiliaryGenerator.kt:41)—
      //   resolveTaskChatModel(titleModelId) ?: return(无模型静默跳过);
      //   params = model + reasoning OFF + model.customHeaders/customBodies(:55-59,
      //   不带 chat 温度/topP/maxTokens)
      generateText: async (prompt: string): Promise<string> => {
        const reference = await readTaskModelReference(TASK_TITLE_MODEL_KEY);
        const choice: ChatProviderChoice | null =
          await resolveTaskModelReference(reference);
        if (choice === null) throw new Error('no title model');
        const headers: Record<string, string> = {};
        for (const h of choice.model.customHeaders) {
          const name: string = h.name.trim();
          if (name.length === 0) continue;
          headers[name] = h.value.trim();
        }
        const api: OpenAIChatApi = await prepareProviderApi(choice.provider, headers);
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
        const merged: Conversation | null = await getConversationRunService().mutateStoredConversation(titled.id,
          (latest: Conversation): Conversation | null => latest.title === snapshot.title
            && this.sameConversationTail(snapshot, latest) ? patchConversation(latest, { title: titled.title }) : null);
        if (merged === null) return;
        if (this.pageAlive && this.conversation !== null && this.conversation.id === merged.id) {
          this.conversation = merged;
          this.title = merged.title.length > 0 ? merged.title : this.title;
        }
      })
      .catch((e: Error): void => {
        if (this.pageAlive) this.errorMsg = `自动命名失败:${e.message}`;
      });
    } finally {
      releaseRun();
    }
  }

  // 手动改名(D-034):Android ChatVM.updateTitle 语义(copy title + save)
  private startEditTitle(): void {
    if (this.conversation === null) return;
    this.titleInput = this.conversation.title;
    this.editingTitle = true;
  }

  private saveTitle(): void {
    if (!this.pageAlive || this.conversation === null) return;
    const id: string = this.conversation.id;
    const title: string = this.titleInput.trim();
    this.editingTitle = false;
    getConversationRunService().mutateStoredConversation(id, (latest: Conversation): Conversation =>
      patchConversation(latest, { title: title })).catch((error: Error): void => {
        if (this.pageAlive && this.conversation?.id === id) this.errorMsg = error.message;
      });
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
    if (this.materializingImages) return;
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
        const dataUrls: string[] = [];
        let failed: number = 0;
        for (const uri of uris) {
          if (token !== this.pickerToken) return;
          try { dataUrls.push(await compressImageAsDataUrl(uri)); }
          catch (_e) { failed++; }
        }
        if (token === this.pickerToken) {
          this.pendingImages = [...this.pendingImages, ...dataUrls];
          if (failed > 0) this.errorMsg = `${failed} 张图片读取失败，其他图片已添加`;
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
    if (clearImages) {
      this.pendingImages = [];
      this.pendingDocs = [];
    }
  }

  private removePendingImage(index: number): void {
    const next: string[] = [...this.pendingImages];
    next.splice(index, 1);
    this.pendingImages = next;
  }

  private removePendingDoc(index: number): void {
    const next: PendingDoc[] = [...this.pendingDocs];
    next.splice(index, 1);
    this.pendingDocs = next;
  }

  private async takePhoto(): Promise<void> {
    if (this.materializingImages) return;
    const token: number = ++this.pickerToken;
    try {
      const ctx: common.UIAbilityContext = getContext(this) as common.UIAbilityContext;
      const profile: cameraPicker.PickerProfile = {
        cameraPosition: camera.CameraPosition.CAMERA_POSITION_BACK,
      };
      const result: cameraPicker.PickerResult = await cameraPicker.pick(
        ctx, [cameraPicker.PickerMediaType.PHOTO], profile);
      if (token !== this.pickerToken) return;
      if (result.resultCode === 0 && result.resultUri.length > 0) {
        this.materializingImages = true;
        const dataUrl: string = await compressImageAsDataUrl(result.resultUri);
        if (token === this.pickerToken) this.pendingImages = [...this.pendingImages, dataUrl];
      }
    } catch (error) {
      if (token === this.pickerToken) this.errorMsg = `拍照未完成：${error instanceof Error ? error.message : String(error)}`;
    } finally {
      if (token === this.pickerToken) this.materializingImages = false;
    }
  }

  private async pickFiles(): Promise<void> {
    const token: number = ++this.pickerToken;
    try {
      const docPicker = new picker.DocumentViewPicker();
      const options: picker.DocumentSelectOptions =
        new picker.DocumentSelectOptions();
      options.maxSelectNumber = 3;
      const uris: string[] = await docPicker.select(options);
      if (token !== this.pickerToken) return;
      for (const uri of uris) {
        const raw: string = uri.substring(uri.lastIndexOf('/') + 1);
        const fileName: string = decodeURIComponent(raw.split('?')[0]) || 'file';
        const bytes: Uint8Array = this.readUriBytes(uri);
        const b64: string = new util.Base64Helper().encodeToStringSync(bytes);
        const mime: string = mimeByName(fileName);
        this.pendingDocs = [...this.pendingDocs,
          { url: `data:${mime};base64,${b64}`, fileName: fileName, mime: mime } as PendingDoc];
      }
    } catch (_e) {
      // 用户取消选择
    }
  }

  // 长文本粘贴为文件(Android pasteLongTextAsFile 对齐):转 text/plain 附件,不进输入框
  private attachPastedText(text: string): void {
    const bytes: Uint8Array = util.TextEncoder.create('utf-8').encodeInto(text);
    const b64: string = new util.Base64Helper().encodeToStringSync(bytes);
    const fileName: string = `paste-${Date.now()}.txt`;
    this.pendingDocs = [...this.pendingDocs,
      { url: `data:text/plain;base64,${b64}`, fileName: fileName, mime: 'text/plain' } as PendingDoc];
    promptAction.showToast({ message: `已作为文件附件(${text.length} 字符)`, duration: 1800 });
  }

  private async loadEditSourceBytes(url: string): Promise<EditSourceImage | null> {
    try {
      if (url.startsWith('data:')) {
        const commaIdx: number = url.indexOf(',');
        if (commaIdx < 0) return null;
        const mime: string = url.substring(5, commaIdx).split(';')[0];
        if (!isAllowedEditMime(mime)) return null;
        const bytes: Uint8Array = new util.Base64Helper().decodeSync(url.substring(commaIdx + 1));
        return { bytes, mime, name: `source.${editMimeExt(mime)}` };
      }
      if (url.startsWith('file://')) {
        const mime: string = mimeByName(url);
        if (!isAllowedEditMime(mime)) return null;
        const bytes: Uint8Array = this.readUriBytes(url);
        const name: string = url.substring(url.lastIndexOf('/') + 1);
        return { bytes, mime, name: name.length > 0 ? name : `source.${editMimeExt(mime)}` };
      }
      return null;
    } catch (_e) {
      return null;
    }
  }

  // 生成图「修改」(iOS/Android modify 流程):图入待发附件 + 预填修改指令;
  //   之后模型调 generate_image(mode=edit) 时由 resolveEditSourceImage 取该图
  private modifyGeneratedImage(url: string): void {
    this.loadAttachDataUrl(url).then((dataUrl: string | null): void => {
      if (!this.pageAlive) return;
      if (dataUrl === null) {
        promptAction.showToast({ message: '读取图片失败,无法修改', duration: 1800 });
        return;
      }
      this.pendingImages = [...this.pendingImages, dataUrl];
      this.inputText = '修改这张图片：';
    }).catch((): void => {});
  }

  // ── WebMount 接管面板(bot/hand;C4-2) ──

  private webmountHostOf(url: string): string {
    const m: RegExpMatchArray | null = url.match(/^[a-zA-Z][a-zA-Z0-9+.-]*:\/\/([^/?#]+)/);
    return m !== null ? m[1] : url;
  }

  // 打开面板:offscreen host 先卸载(同一 controller 换挂到面板 Web)
  private openWebmountSheet(): void {
    this.webmountHostVisible = false;
    this.webmountSheetOpen = true;
  }

  private closeWebmountSheet(): void {
    this.webmountSheetOpen = false;
    this.webmountHostVisible = true;
  }

  private toggleWebmountLease(): void {
    getWebMountSession().setLease(this.webmountLease === 'hand' ? 'bot' : 'hand');
  }

  @Builder
  private webmountSheet() {
    Column() {
      Row() {
        Text(this.webmountHostOf(this.webmountUrl))
          .fontSize(16).fontColor(INK).layoutWeight(1)
          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        Text(this.webmountLease === 'hand' ? '交还控制' : '接管')
          .fontSize(14).fontColor(ACCENT)
          .padding({ left: 12, top: 8, bottom: 8 })
          .onClick((): void => { this.toggleWebmountLease(); })
        Text('关闭')
          .fontSize(14).fontColor(INK3)
          .padding({ left: 12, top: 8, bottom: 8 })
          .onClick((): void => { this.closeWebmountSheet(); })
      }
      .width('100%')
      if (this.webmountLease === 'hand') {
        Text('你正在手动控制 — Agent 的点击/输入/脚本已暂停,交还后恢复')
          .fontSize(META).fontColor(INK3)
          .width('100%')
          .padding({ top: 6, bottom: 2 })
      }
      WebMountSurface()
        .layoutWeight(1)
        .width('100%')
        .margin({ top: 8 })
    }
    .width('100%').height('100%').padding(16).backgroundColor(BG)
  }

  private async loadAttachDataUrl(url: string): Promise<string | null> {
    try {
      if (url.startsWith('data:')) return url;
      if (url.startsWith('file://')) {
        const mime: string = mimeByName(url);
        const bytes: Uint8Array = this.readUriBytes(url);
        const b64: string = new util.Base64Helper().encodeToStringSync(bytes);
        return `data:${mime};base64,${b64}`;
      }
      return null;
    } catch (_e) {
      return null;
    }
  }

  // 产物架使用完整已提交历史，不受聊天首屏分页窗口限制。
  private async openArtifactShelf(): Promise<void> {
    const current: Conversation | null = this.conversation;
    if (current === null) return;
    try {
      const stored: Conversation | null = this.timelineLoadState.fullyLoaded
        ? current : await getChatRepository().getById(current.id);
      if (!this.pageAlive || !this.pageVisible || this.conversation?.id !== current.id) return;
      const active: ConversationRunSnapshot | null = getConversationRunService().snapshot(current.id);
      const full: Conversation | null = active?.conversation ?? stored;
      if (full === null) { this.errorMsg = '会话已不存在'; return; }
      this.shelfArtifacts = collectConversationArtifacts(full, this.displaySettings.enableGenerativeWidgets);
      this.artifactShelfOpen = true;
    } catch (error) {
      if (this.pageAlive && this.conversation?.id === current.id) this.errorMsg = `无法打开产物架：${(error as Error).message}`;
    }
  }

  private async openArtifactSource(source: ConversationArtifactSource): Promise<void> {
    if (this.conversation?.id !== source.conversationId) { this.errorMsg = '来源会话已切换'; return; }
    try {
      const stored: Conversation | null = await getChatRepository().getById(source.conversationId);
      if (!this.pageAlive || !this.pageVisible || this.conversation?.id !== source.conversationId) return;
      const active: ConversationRunSnapshot | null = getConversationRunService().snapshot(source.conversationId);
      const full: Conversation | null = active?.conversation ?? stored;
      const index: number = full === null ? -1 : artifactSourceNodeIndex(full, source);
      if (full === null || index < 0) { this.errorMsg = '来源消息已不存在或不在当前分支'; return; }
      this.artifactShelfOpen = false;
      this.conversation = full;
      this.reconcileSmartUiSources(full);
      this.rows = buildRows(full, this.rows);
      this.timelineLoadState = applyTailWindow(this.timelineLoadState, 0, full.messageNodes.length, full.messageNodes.length);
      this.followMode = 'paused';
      this.positionToTargetNode(source.nodeId, index);
    } catch (error) {
      if (this.pageAlive && this.conversation?.id === source.conversationId) this.errorMsg = `无法定位来源：${(error as Error).message}`;
    }
  }

  // D0-12:同名文档两最新版本对比(readDocumentPreview 取文本;失败诚实展示)
  private async openArtifactDiff(fileName: string): Promise<void> {
    const matches: UIMessagePartDocument[] = this.shelfArtifacts.docs.map(
      (artifact: ConversationDocumentArtifact): UIMessagePartDocument => artifact.part).filter(
      (d: UIMessagePartDocument): boolean =>
        (d.fileName.length > 0 ? d.fileName : '(未命名)') === fileName);
    if (matches.length < 2) return;
    this.artifactDiffTitle = `版本对比:${fileName}`;
    this.artifactDiffText = '正在读取两个版本…';
    this.artifactDiffOpen = true;
    try {
      const older: string = await readDocumentPreview(matches[matches.length - 2]);
      const newer: string = await readDocumentPreview(matches[matches.length - 1]);
      this.artifactDiffText = this.diffTextSummary(older, newer);
    } catch (e) {
      const msg: string = e instanceof Error ? e.message : String(e);
      this.artifactDiffText = `无法对比:${msg}`;
    }
  }

  // soul diff 同款诚实行级摘要:行多重集增减统计 + 首个位置差异上下文(非 LCS)
  private diffTextSummary(older: string, newer: string): string {
    const la: string[] = older.split('\n');
    const lb: string[] = newer.split('\n');
    const counts: Map<string, number> = new Map<string, number>();
    for (const l of la) counts.set(l, (counts.get(l) ?? 0) + 1);
    let added: number = 0;
    for (const l of lb) {
      const c: number = counts.get(l) ?? 0;
      if (c > 0) counts.set(l, c - 1); else added++;
    }
    let removed: number = 0;
    counts.forEach((c: number): void => { removed += c; });
    let firstDiff: number = -1;
    const minLen: number = Math.min(la.length, lb.length);
    for (let i = 0; i < minLen; i++) {
      if (la[i] !== lb[i]) { firstDiff = i; break; }
    }
    if (firstDiff < 0 && la.length !== lb.length) firstDiff = minLen;
    let out: string = `旧版本 ${la.length} 行 · 新版本 ${lb.length} 行\n` +
      `+${added} 行新增 · −${removed} 行移除(按行统计,非逐字 diff)\n`;
    if (added === 0 && removed === 0) {
      out += '\n两个版本内容一致。';
      return out;
    }
    if (firstDiff >= 0) {
      const lo: number = Math.max(0, firstDiff - 2);
      const hi: number = Math.min(Math.max(la.length, lb.length), firstDiff + 3);
      out += `\n首处差异(第 ${firstDiff + 1} 行附近):\n`;
      for (let i = lo; i < hi; i++) {
        const a: string = i < la.length ? la[i] : '';
        const b: string = i < lb.length ? lb[i] : '';
        if (a === b) {
          out += `  ${i + 1}  ${a}\n`;
        } else {
          if (a.length > 0) out += `− ${i + 1}  ${a}\n`;
          if (b.length > 0) out += `+ ${i + 1}  ${b}\n`;
        }
      }
    }
    return out;
  }

  @Builder
  private artifactShelfSheet() {
    ChatArtifactShelf({ artifacts: this.shelfArtifacts,
      onDismiss: (): void => { this.artifactShelfOpen = false; },
      onOpenDocument: (part: UIMessagePartDocument): void => {
        this.artifactShelfOpen = false;
        this.openDocumentPreview(part);
      },
      onOpenDiff: (name: string): void => {
        this.artifactShelfOpen = false;
        this.openArtifactDiff(name);
      },
      onRunMiniApp: (appId: string): void => {
        this.artifactShelfOpen = false;
        router.pushUrl({ url: 'pages/MiniAppRunnerPage', params: { appId: appId } });
      },
      onSource: (source: ConversationArtifactSource): void => {
        this.artifactShelfOpen = false;
        this.openArtifactSource(source);
      },
      onAction: (prompt: string): void => {
        this.inputText = prompt;
        this.artifactShelfOpen = false;
      },
    })
  }

  private openDocumentPreview(part: UIMessagePartDocument): void {
    const seq: number = ++this.documentPreviewSeq;
    this.documentPreviewTitle = part.fileName;
    this.documentPreviewText = '正在读取附件…';
    this.documentPreviewOpen = true;
    readDocumentPreview(part).then((text: string): void => {
      if (this.pageAlive && this.documentPreviewOpen && seq === this.documentPreviewSeq) {
        this.documentPreviewText = text;
      }
    }).catch((e: Error): void => {
      if (this.pageAlive && this.documentPreviewOpen && seq === this.documentPreviewSeq) {
        this.documentPreviewText = `无法预览：${e.message}`;
      }
    });
  }

  @Builder
  private documentPreviewSheet() {
    Column() {
      Row() {
        Text(this.documentPreviewTitle).fontSize(18).fontColor(INK).layoutWeight(1).maxLines(2)
        Text('关闭').fontSize(14).fontColor(ACCENT).padding(12)
          .onClick((): void => { this.documentPreviewOpen = false; })
      }.width('100%')
      Scroll() {
        Text(this.documentPreviewText).fontSize(15).fontColor(INK)
          .copyOption(CopyOptions.InApp).width('100%')
      }.layoutWeight(1).width('100%').align(Alignment.TopStart)
    }.width('100%').height('100%').padding(20).backgroundColor(BG)
  }

  @Builder
  private artifactDiffSheet() {
    Column() {
      Row() {
        Text(this.artifactDiffTitle).fontSize(18).fontColor(INK).layoutWeight(1).maxLines(2)
        Text('关闭').fontSize(14).fontColor(ACCENT).padding(12)
          .onClick((): void => { this.artifactDiffOpen = false; })
      }.width('100%')
      Scroll() {
        Text(this.artifactDiffText).fontSize(14).fontColor(INK)
          .copyOption(CopyOptions.InApp).width('100%')
      }.layoutWeight(1).width('100%').align(Alignment.TopStart)
    }.width('100%').height('100%').padding(20).backgroundColor(BG)
  }

  private readUriBytes(uri: string): Uint8Array {
    const MAX: number = 10 * 1024 * 1024;
    const file: fileIo.File = fileIo.openSync(uri, fileIo.OpenMode.READ_ONLY);
    try {
      const st: fileIo.Stat = fileIo.statSync(file.fd);
      if (st.size > MAX) {
        throw new Error(`文件超过 10MB: ${st.size} bytes`);
      }
      const buf: ArrayBuffer = new ArrayBuffer(st.size);
      fileIo.readSync(file.fd, buf, { offset: 0, length: st.size });
      return new Uint8Array(buf);
    } finally {
      fileIo.closeSync(file);
    }
  }

  // 末条 assistant 消息的真实 usage(Android ContextRing V3 数据源)
  private lastTurnUsage(): TokenUsage | null {
    const conv: Conversation | null = this.conversation;
    if (conv === null) return null;
    const nodes = conv.messageNodes;
    for (let i: number = nodes.length - 1; i >= 0; i--) {
      const msgs = nodes[i].messages;
      if (msgs.length === 0) continue;
      const idx: number = nodes[i].selectIndex >= 0 && nodes[i].selectIndex < msgs.length
        ? nodes[i].selectIndex : 0;
      const msg: UIMessage = msgs[idx];
      if (msg.role !== 'assistant' || msg.usage === null) continue;
      return msg.usage;
    }
    return null;
  }

  private lastTurnElapsedMs(): number | null {
    const conv: Conversation | null = this.conversation;
    if (conv === null) return null;
    const nodes = conv.messageNodes;
    for (let i: number = nodes.length - 1; i >= 0; i--) {
      const msgs = nodes[i].messages;
      if (msgs.length === 0) continue;
      const idx: number = nodes[i].selectIndex >= 0 && nodes[i].selectIndex < msgs.length
        ? nodes[i].selectIndex : 0;
      const msg: UIMessage = msgs[idx];
      if (msg.role !== 'assistant') continue;
      if (msg.finishedAt === null) return null;
      const a: number = new Date(msg.createdAt).getTime();
      const b: number = new Date(msg.finishedAt).getTime();
      if (isNaN(a) || isNaN(b)) return null;
      return b - a > 0 ? b - a : null;
    }
    return null;
  }

  private formatGrouped(n: number): string {
    return Math.round(n).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  }

  private lastTurnTotalText(): string {
    const u: TokenUsage | null = this.lastTurnUsage();
    return u !== null ? `${this.formatGrouped(u.totalTokens)} tok` : '—';
  }

  private lastTurnCacheText(): string {
    const u: TokenUsage | null = this.lastTurnUsage();
    if (u === null || u.promptTokens <= 0 || u.cachedTokens <= 0) return '—';
    const pct: number = Math.min(100, u.cachedTokens * 100.0 / u.promptTokens);
    return `${Math.floor(pct)}%`;
  }

  private lastTurnSpeedText(): string {
    const u: TokenUsage | null = this.lastTurnUsage();
    const ms: number | null = this.lastTurnElapsedMs();
    if (u === null || ms === null || u.completionTokens <= 0) return '—';
    return `${Math.floor(u.completionTokens / (ms / 1000.0))} tok/s`;
  }

  @Builder
  metaStat(label: string, value: string) {
    Column() {
      Text(label).fontSize(10.5).fontColor(INK2).letterSpacing(0.3)
      Text(value).fontSize(12).fontColor(INK).fontWeight(FontWeight.Medium)
        .letterSpacing(0.2).margin({ top: 2 })
    }
    .alignItems(HorizontalAlign.Start)
  }

  // 用量与上下文卡(Android ContextUsagePanel:260dp,ctx 指示器下方右锚定)
  @Builder
  contextUsagePanel() {
    Column() {
      Text('用量与上下文')
        .fontSize(13).fontColor(INK2).letterSpacing(0.3)
        .width('100%')
        .margin({ bottom: 10 })

      Row() {
        Text('Context')
          .fontSize(13).fontColor(INK).fontWeight(FontWeight.Medium).letterSpacing(0.2)
        Blank()
        Text(`${this.estimatedContextTokens() > 0
          ? formatContextTokens(this.estimatedContextTokens()) : '0'} / ${this.contextWindowTokens !== null
          ? formatContextTokens(this.contextWindowTokens) : '--'}`)
          .fontSize(11.5).fontColor(INK2).letterSpacing(0.3)
      }
      .width('100%')

      Stack({ alignContent: Alignment.Start }) {
        Column()
          .width('100%').height(5).borderRadius(999)
          .backgroundColor('#141B1A17')
        Column()
          .height(5).borderRadius(999)
          .backgroundColor(this.contextRingColor())
          .width(`${Math.max(0, Math.min(1, this.contextUsageRatio())) * 100}%`)
      }
      .width('100%')
      .margin({ top: 6 })

      Column()
        .width('100%').height(1)
        .backgroundColor(LINE)
        .margin({ top: 14 })

      Row() {
        this.metaStat('本次', this.lastTurnTotalText())
        Blank()
        this.metaStat('缓存命中', this.lastTurnCacheText())
        Blank()
        this.metaStat('速度', this.lastTurnSpeedText())
      }
      .width('100%')
      .margin({ top: 12 })
      .alignItems(VerticalAlign.Top)
    }
    .width(260)
    .padding({ left: 16, right: 16, top: 14, bottom: 14 })
    .backgroundColor(SURFACE)
    .borderRadius(18)
    .border({ width: 1, color: LINE })
    .shadow({ radius: 36, color: '#33404238', offsetY: 6 })
    .alignItems(HorizontalAlign.Start)
  }

  // 编辑并重发:editMessage(新分支)→ 持久化 → runRegenerateAt(user 节点截断续跑)
  private sendEdit(text: string): boolean {
    if (!this.pageAlive || this.conversation === null || this.sending) return false;
    const nodeId: string = this.editingNodeId;
    const messageId: string = this.editingMessageId;
    const command: ConversationEditCommand = { kind: 'edit', nodeId: nodeId, messageId: messageId, text: text };
    if (this.submitConversationRun(command)) {
      this.editingNodeId = ''; this.editingMessageId = '';
      return true;
    }
    return false;
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
          const id: string = this.conversation.id;
          getConversationRunService().mutateStoredConversation(id, (latest: Conversation): Conversation | null => {
            if (getConversationRunService().isBusy(id)) throw new Error('生成中无法删除消息');
            return deleteMessage(latest, row.message.id, false);
          }).then((updated: Conversation | null): Promise<void> => {
            if (updated === null) return Promise.resolve();
            this.cleanupSmartUiMessage(id, row.message.id);
            return this.invalidateConvCompacts(id, 'message_deleted');
          }).catch((error: Error): void => {
              if (this.pageAlive && this.conversation?.id === id) this.errorMsg = error.message;
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

  // ===== D-055 上下文压缩引擎接线(ConversationContextEngine.kt:112-286) =====

  // 失效钩子(ChatService.kt:1097/:2082/:2152/:2171;失败 hilog 非静默,不阻断交互)
  private invalidateConvCompacts(convId: string, reason: string): Promise<void> {
    return invalidateCompacts(getCompactStore(), convId, reason)
      .catch((e: Error): void => {
        hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'compact invalidate failed: %{public}s', e.message);
      });
  }

  // 压缩失败统一文案(Android ChatVM 错误映射)
  private compactErrorText(e: Error): string {
    return e instanceof ContextCompactionFailedError
      ? '上下文已满，压缩失败，请配置任务模型'
      : e.message;
  }

  // SubAgent run sheet:打开时订阅 liveText,关闭时取消订阅
  private async openSubAgentSheet(runId: string, tools: UIMessagePartTool[]): Promise<void> {
    const seq: number = ++this.subAgentSheetSeq;
    if (this.unsubSubAgentLive !== null) {
      this.unsubSubAgentLive();
      this.unsubSubAgentLive = null;
    }
    this.subAgentSheetRunId = runId;
    this.subAgentSheetTools = tools;
    this.subAgentLiveText = '';
    try {
      const manager = await getSubAgentManager();
      if (!this.pageAlive || !this.pageVisible || seq !== this.subAgentSheetSeq) return;
      // 订阅实时文本
      this.unsubSubAgentLive = manager.subscribeLiveText(runId, (text: string): void => {
        if (!this.pageAlive || !this.pageVisible || seq !== this.subAgentSheetSeq) return;
        this.subAgentLiveText = text;
      });
      // 若已结束,从 snapshot 取最终文本
      const snapshot: SubAgentRun | null = manager.snapshot(runId);
      if (snapshot !== null && snapshot.displayText.length > 0) {
        this.subAgentLiveText = snapshot.displayText;
      }
    } catch (e) {
      if (!this.pageAlive || !this.pageVisible || seq !== this.subAgentSheetSeq) return;
      const msg: string = e instanceof Error ? e.message : String(e);
      hilog.warn(0x0001, 'Chat.Page', 'openSubAgentSheet failed: %{public}s', msg);
    }
    this.subAgentSheetOpen = true;
  }

  private closeSubAgentSheet(): void {
    this.subAgentSheetSeq++;
    if (this.unsubSubAgentLive !== null) {
      this.unsubSubAgentLive();
      this.unsubSubAgentLive = null;
    }
    this.subAgentSheetOpen = false;
  }

  private refreshFavorites(conversationId: string): Promise<void> {
    return listFavoriteNodeIds(conversationId).then((ids: string[]): void => {
      if (this.pageAlive && this.conversation?.id === conversationId) this.favoriteNodeIds = ids;
    }).catch((error: Error): void => {
      if (this.pageAlive && this.pageVisible) this.errorMsg = error.message;
    });
  }

  private toggleFavorite(row: MsgRow): void {
    const conversation: Conversation | null = this.conversation;
    if (conversation === null) return;
    const key: string = conversation.id + '/' + row.nodeId;
    if (this.favoriteWrites.has(key)) return;
    this.favoriteWrites.add(key);
    const selected: boolean = this.favoriteNodeIds.indexOf(row.nodeId) < 0;
    setNodeFavorite(conversation.id, row.nodeId, selected).then(async (): Promise<void> => {
      await this.refreshFavorites(conversation.id);
      if (this.pageAlive && this.pageVisible && this.conversation?.id === conversation.id) {
        promptAction.showToast({ message: selected ? '已收藏' : '已取消收藏', duration: 1200 });
      }
    }).catch((error: Error): void => {
      if (this.pageAlive && this.pageVisible) this.errorMsg = '收藏失败：' + error.message;
    }).finally((): void => { this.favoriteWrites.delete(key); });
  }

  private openChatWorkspace(messageId: string = ''): void {
    this.archiveInitialMessageId = messageId;
    this.workspacePanelOpen = true;
  }

  private async openArchiveSource(source: ChatMessageArchiveSource): Promise<void> {
    const full: Conversation | null = await getChatRepository().getById(source.conversationId);
    if (!this.pageAlive || !this.pageVisible) return;
    const index: number = full === null ? -1 : full.messageNodes.findIndex((node: MessageNode): boolean => node.id === source.nodeId);
    if (full === null || index < 0) { this.errorMsg = '来源消息已不存在'; return; }
    if (nodeCurrentMessage(full.messageNodes[index]).id !== source.messageId) {
      this.errorMsg = '来源消息不在当前分支，请先切回对应分支'; return;
    }
    this.workspacePanelOpen = false;
    if (this.conversation?.id === full.id) {
      this.rows = buildRows(full, this.rows);
      this.timelineLoadState = applyTailWindow(this.timelineLoadState, 0,
        full.messageNodes.length, full.messageNodes.length);
      this.positionToTargetNode(source.nodeId, index);
    }
    else {
      const params: ChatArchiveRouteParams = { conversationId: full.id, nodeId: source.nodeId };
      router.pushUrl({ url: 'pages/ChatPage', params: params });
    }
  }

  @Builder
  private CouncilSheet(): void {
    ChatCouncilRunSheet({ runId: this.councilSheetRunId, tools: this.councilSheetTools,
      onDismiss: (): void => { this.councilSheetOpen = false; } })
  }

  private openMessageExport(messageId: string = ''): void {
    this.exportInitialMessageIds = messageId.length > 0 ? [messageId] : [];
    this.exportSheetOpen = true;
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

  private copyAssistantMessage(): void {
    if (this.actionsSheetRow === null) return;
    const text: string = messageCopyText(this.actionsSheetRow.message, false);
    const board: pasteboard.SystemPasteboard = pasteboard.getSystemPasteboard();
    board.setDataSync(pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, text));
    promptAction.showToast({ message: '已复制', duration: 1200 });
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

  private shareActionsSheetMessage(): void {
    if (this.actionsSheetRow !== null) this.shareMessage(this.actionsSheetRow.message);
  }

  private deleteActionsSheetMessage(): void {
    if (this.actionsSheetRow !== null) this.confirmDeleteMessage(this.actionsSheetRow);
  }

  // 分享:系统分享面板(Share Kit)为主,面板不可用时降级复制到剪贴板
  private shareMessage(message: UIMessage): void {
    const text: string = toText(message).trim();
    if (text.length === 0) {
      promptAction.showToast({ message: '没有可分享的文本', duration: 1200 });
      return;
    }
    try {
      const data: systemShare.SharedData = new systemShare.SharedData({
        utd: utd.UniformDataType.PLAIN_TEXT,
        content: text
      });
      const controller: systemShare.ShareController = new systemShare.ShareController(data);
      const ctx: common.UIAbilityContext =
        this.getUIContext().getHostContext() as common.UIAbilityContext;
      controller.show(ctx, {
        anchor: { windowOffset: { x: 660, y: 2200 } }
      }).catch((): void => { this.fallbackCopyShare(text); });
    } catch (e) {
      this.fallbackCopyShare(text);
    }
  }

  private fallbackCopyShare(text: string): void {
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
    const sequence: number = ++this.drawerSearchSeq;
    const query: string = text.trim();
    const request: Promise<ConversationSummary[]> = query.length > 0
      ? getChatRepository().search(query) : getChatRepository().list();
    request.then((items: ConversationSummary[]): void => {
      if (this.pageAlive && sequence === this.drawerSearchSeq) this.drawerConversations = items;
    }).catch((e: Error): void => {
      if (this.pageAlive && sequence === this.drawerSearchSeq) this.errorMsg = e.message;
    });
    // 消息全文搜索(仅在有词时;与标题搜索并列,命中展示在会话列表下)
    if (query.length > 0) {
      getChatRepository().searchMessages(query, 8).then((hits: MessageSearchHit[]): void => {
        if (this.pageAlive && sequence === this.drawerSearchSeq) {
          this.drawerMessageHits = hits;
        }
      }).catch((_e: Error): void => {
        if (this.pageAlive && sequence === this.drawerSearchSeq) this.drawerMessageHits = [];
      });
    } else {
      this.drawerMessageHits = [];
    }
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
    this.closeDrawer();
    if (this.conversation !== null && this.conversation.id === id) return;
    router.replaceUrl({ url: 'pages/ChatPage', params: { conversationId: id } });
  }

  private deleteDrawerConversation(item: ConversationSummary): void {
    const deletingCurrent: boolean = this.conversation !== null && this.conversation.id === item.id;
    if (getConversationRunService().isBusy(item.id)) {
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
          if (getConversationRunService().isBusy(item.id)) { this.errorMsg = '生成中无法删除该会话'; return; }
          const commit = async (): Promise<void> => {
            const release: () => void = beginConversationRestore([item.id]);
            try {
              const full: Conversation | null = await getChatRepository().getById(item.id);
              if (full === null) return;
              await getChatRepository().delete(item.id);
              getConversationRunService().forgetDeletedConversation(item.id);
              const smartUiCleanup = await this.smartUiDraftStore?.cleanupConversation(item.id);
              if (smartUiCleanup !== undefined && smartUiCleanup.error !== null) {
                this.smartUiSaveError = `智能 UI 草稿清理失败：${smartUiCleanup.error}`;
              }
              await cleanupDeletedConversation(full, getChatRepository(), getChatKvStore());
            } finally { release(); }
          };
          commit().then((): void => {
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
  private jevSubjectHash(part: UIMessagePartTool, locator?: ToolApprovalPartLocator): string {
    const viewed: Conversation | null = this.conversation;
    if (viewed === null || locator === undefined) return '';
    const canonical: Conversation | null = getConversationRunService().snapshot(viewed.id)?.conversation ?? viewed;
    return canonical === null ? '' : currentJevApprovalSubjectHash(canonical, part.toolCallId, locator);
  }
  private currentToolPreviewPart(): UIMessagePartTool {
    const part: UIMessagePartTool = this.toolPreviewPart as UIMessagePartTool;
    const locator: ToolApprovalPartLocator | null = this.toolPreviewLocator;
    const viewed: Conversation | null = this.conversation;
    if (locator === null || viewed === null) return part;
    const snapshot: ConversationRunSnapshot | null = getConversationRunService().snapshot(viewed.id);
    if (snapshot?.conversationDeleted === true) return part;
    const canonical: Conversation = snapshot?.conversation ?? viewed;
    const message: UIMessage | undefined = currentMessages(canonical).find((item: UIMessage): boolean => item.id === locator.messageId);
    const candidate: UIMessagePart | undefined = message?.parts[locator.partIndex];
    return candidate?.type === 'tool' && (candidate as UIMessagePartTool).toolCallId === part.toolCallId
      ? candidate as UIMessagePartTool : part;
  }
  private openToolPreview(part: UIMessagePartTool, locator?: ToolApprovalPartLocator): void {
    this.toolPreviewLocator = locator ?? null;
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
    this.modelMenuSeq++;
    this.modelMenuLoading = false;
    animateTo({ duration: 220, curve: Curve.EaseOut }, (): void => {
      this.modelMenuOpen = false;
    });
  }

  private refreshEffectiveModelState(): void {
    const seq: number = ++this.contextChoiceSeq;
    const conversationId: string = this.conversation !== null ? this.conversation.id : '';
    const assistantId: string | undefined = this.conversation?.assistantId;
    resolveEffectiveChatChoice(assistantId).then((choice: ChatProviderChoice | null): void => {
      if (!this.pageAlive || seq !== this.contextChoiceSeq
        || (this.conversation !== null ? this.conversation.id : '') !== conversationId
        || this.conversation?.assistantId !== assistantId) return;
      this.modelLabel = choice !== null ? `${choice.provider.name} / ${choice.model.modelId}` : '';
      this.setContextModel(choice);
      this.refreshReasoningPicker(choice);
    }).catch((e: Error): void => {
      if (this.pageAlive && seq === this.contextChoiceSeq
        && (this.conversation !== null ? this.conversation.id : '') === conversationId
        && this.conversation?.assistantId === assistantId) this.errorMsg = e.message;
    });
  }

  // 未使用：模型标签现与 Context/Reasoning 一并由 refreshEffectiveModelState 更新。
  private async describeEffectiveModel(): Promise<string> {
    const choice: ChatProviderChoice | null =
      await resolveEffectiveChatChoice(this.conversation?.assistantId);
    return choice !== null ? `${choice.provider.name} / ${choice.model.modelId}` : '';
  }

  private toggleModelMenu(): void {
    if (this.modelMenuOpen || this.modelMenuLoading) {
      this.closeModelMenu();
      return;
    }
    const seq: number = ++this.modelMenuSeq;
    this.modelMenuLoading = true;
    // 读取失败也必须复位 loading — 否则菜单从此点不开
    loadProviders(getChatKvStore()).then((providers: ProviderSetting[] | null): void => {
      if (!this.pageAlive || !this.pageVisible || seq !== this.modelMenuSeq) return;
      resolveEffectiveChatChoice(this.conversation?.assistantId).then((choice: ChatProviderChoice | null): void => {
        if (!this.pageAlive || !this.pageVisible || seq !== this.modelMenuSeq) return;
        const currentProvider: string = choice !== null ? choice.provider.id : '';
        const currentModel: string = choice !== null ? choice.model.modelId : '';
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
        if (!this.pageAlive || seq !== this.modelMenuSeq) return;
        this.modelMenuLoading = false;
        hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'model selection read failed: %{public}s', e.message);
      });
    }).catch((e: Error): void => {
      if (!this.pageAlive || seq !== this.modelMenuSeq) return;
      this.modelMenuLoading = false;
      hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'providers read failed: %{public}s', e.message);
    });
  }

  // 下拉内选中模型:持久化 + 刷新标签 + 关闭。
  //   对齐 Android onSelect → vm.setChatModel;Harmony 选择层 = Preferences 键
  //   (chat_selected_provider_id/model_id),resolveChatRuntime 消费。
  private selectModelFromMenu(providerId: string, modelId: string): void {
    saveEffectiveChatChoice({ providerId, modelId }, this.conversation?.assistantId).then((): void => {
      if (!this.pageAlive) return;
      // contextWindowTokens + ReasoningPicker 段集也随之刷新(模型变了,档位段集 + 当前档位变)
      this.refreshEffectiveModelState();
      this.closeModelMenu();
    }).catch((e: Error): void => { if (this.pageAlive) this.errorMsg = e.message; });
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
    mutateAssistant(assistantId, (a: Assistant): Assistant =>
      withReasoningLevelForModel(a, modelId, rl)).then((changed: Assistant | null): void => {
      if (changed !== null && this.pageAlive && this.currentModelId === modelId) {
        this.currentReasoningLevel = level;
      }
    }).catch((e: Error): void => { if (this.pageAlive) this.errorMsg = e.message; });
  }

  // 手动压缩复用运行服务的同会话写入边界，不向生成队列另加任务。
  private compactCurrentConversation(): void {
    if (this.conversation === null || this.manualCompacting) return;
    const id: string = this.conversation.id;
    this.manualCompacting = true;
    getConversationRunService().compact(id).then((outcome: ManualCompactionOutcome): void => {
      if (!this.pageAlive || this.conversation?.id !== id) return;
      this.contextCompacts = outcome.compacts;
      this.refreshContextCompacts();
      if (this.pageVisible) promptAction.showToast({ message: outcome.message, duration: 2000 });
      if (this.pageVisible && outcome.summary.length > 0) {
        this.compactSummary = outcome.summary;
        this.compactSummaryOpen = true;
      }
    }).catch((error: Error): void => {
      if (this.pageAlive && this.conversation?.id === id) this.errorMsg = error.message;
    }).finally((): void => {
      if (this.conversation?.id === id) this.manualCompacting = false;
    });
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

  // Kernel façade:统一 phase 生命周期(对齐 iOS ChatKernelRunHost 可观察语义)
  private kernelPhase: KernelPhase = 'idle';

  // 工具审批(ChatService.kt:1122-1188):改写 approvalState → 持久化 →
  //   无 pending 残留 → 续跑生成(handleMessageComplete)
  private handleToolApproval(toolCallId: string, approved: boolean, reason: string = '',
    locator?: ToolApprovalPartLocator): void {
    const verdict: ToolApprovalVerdict = approved
      ? { kind: 'approved' }
      : { kind: 'denied', reason };
    this.applyToolVerdict(toolCallId, verdict, locator);
  }

  // ===== D-108:拒绝理由弹窗(ToolDenyReasonDialog :428-461 + 调用点 :966-974) =====
  //   拒绝按钮 → showDenyDialog;confirm(reason) → onToolApproval(id,false,reason)
  private openDenyDialog(toolCallId: string, locator?: ToolApprovalPartLocator): void {
    this.denyReasonText = '';
    this.denyDialogToolCallId = toolCallId;
    this.denyDialogLocator = locator;
    this.denyDialogOpen = true;
  }

  private dismissDenyDialog(): void {
    this.denyDialogOpen = false;
    this.denyDialogToolCallId = '';
    this.denyDialogLocator = undefined;
    // 保留退出帧的输入内容；下次 openDenyDialog 再清空。
  }

  private confirmDenyDialog(): void {
    const id: string = this.denyDialogToolCallId;
    const reason: string = this.denyReasonText;
    const locator: ToolApprovalPartLocator | undefined = this.denyDialogLocator;
    this.dismissDenyDialog();
    if (id.length > 0 || locator !== undefined) {
      this.handleToolApproval(id, false, reason, locator);
    }
  }

  // D-105:ask_user 应答(ChatService.handleToolApproval answer 分支,:1136-1140
  //   answer 优先 → answered 态;落库 + 无 pending 则续跑,同审批路径)
  private handleToolAnswer(toolCallId: string, answer: string, locator?: ToolApprovalPartLocator): void {
    this.applyToolVerdict(toolCallId, { kind: 'answered', answer }, locator);
  }

  private applyToolVerdict(toolCallId: string, verdict: ToolApprovalVerdict,
    locator?: ToolApprovalPartLocator): void {
    if (!this.pageAlive || this.conversation === null || this.sending) return;
    const command: ConversationVerdictCommand = { kind: 'verdict', toolCallId: toolCallId, verdict: verdict, locator: locator };
    this.submitConversationRun(command);
  }

  // 审批后续跑:不追加 user 消息,直接进工具循环;流式预览复用 D-041
  //   regenTail 机制(末条 assistant 行下渲染 — 写回的就是末条消息)
  private resumeToolLoop(conv: Conversation): void {
    if (!this.pageAlive || this.sending) return;
    const command: ConversationResumeCommand = { kind: 'resume', manualToolCallId: '' };
    this.submitConversationRun(command);
  }

  // 生成中发送 → 入队;满 20 拒绝(Android 文案逐字)。
  // 模式(D-051,Android ChatInput.kt:259/272 + ChatService.kt:699):
  //   answer=true(点击发送)→ 恒 FOLLOWUP;answer=false(长按)生成中 → STEER
  //   (插 STEER 前缀位,派发时 asFollowup 转换),否则 FOLLOWUP
  // ChatService.kt:695-700:mode 显式传入(默认 FOLLOWUP);非生成期 Android 强制
  //   FOLLOWUP — 鸿蒙入队仅在 sending 分支发生,天然等价
  private enqueuePendingParts(
    parts: UIMessagePart[], answer: boolean, mode: PendingUserMessageMode = 'FOLLOWUP'): Promise<void> {
    if (this.conversation === null) return Promise.reject(new Error('会话不存在'));
    return getConversationRunService().enqueue(this.conversation.id, parts, answer, mode);
  }

  private restorePendingMessage(id: string): void {
    const owner: string = this.conversation?.id ?? '';
    if (owner.length === 0) return;
    getConversationRunService().takePending(owner, id).then((pending: PendingUserMessage | null): void => {
      if (pending === null || !this.pageAlive || this.conversation?.id !== owner) return;
      const texts: string[] = [];
      for (const part of pending.parts) {
        if (part.type === 'text') texts.push(part.text);
        else if (part.type === 'image') this.pendingImages = [...this.pendingImages, part.url];
        else if (part.type === 'document') this.pendingDocs = [...this.pendingDocs,
          { url: part.url, fileName: part.fileName, mime: part.mime } as PendingDoc];
      }
      this.inputText = [this.inputText, texts.join('\n')].filter((text: string): boolean => text.length > 0).join('\n');
      this.queueSheetOpen = false;
    }).catch((error: Error): void => { if (this.pageAlive && this.conversation?.id === owner) this.errorMsg = error.message; });
  }

  @Builder
  PendingQueueSheet(): void {
    ChatPendingQueueSheet({
      messages: this.pendingQueue,
      onDismiss: (): void => { this.queueSheetOpen = false; },
      onRestore: (id: string): void => { this.restorePendingMessage(id); },
      onCancel: (id: string): void => {
        this.cancelPendingById(id).catch((error: Error): void => { this.errorMsg = error.message; });
      },
      onClear: (): void => {
        if (this.conversation === null) return;
        getConversationRunService().clearPending(this.conversation.id)
          .catch((error: Error): void => { this.errorMsg = error.message; });
      },
    })
  }

  @Builder
  CompactSummarySheet(): void {
    Column() {
      Text('上下文摘要').fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
        .width('100%').padding({ top: 12, bottom: 16 })
      Scroll() {
        MarkdownText({ content: this.compactSummary, fontSize: BODY, fontColor: INK2 })
          .width('100%').padding({ bottom: 20 })
      }.width('100%').layoutWeight(1).align(Alignment.TopStart).scrollBar(BarState.Off)
    }.width('100%').height('100%').padding({ left: 20, right: 20 }).backgroundColor(SURFACE)
  }


  private cancelPendingById(id: string): Promise<void> {
    if (this.conversation === null) return Promise.resolve();
    return getConversationRunService().cancelPending(this.conversation.id, id);
  }

  // 生成完成(成功/失败/中断)后出队下一条继续派发(Android 循环逐条 catch 后继续)
  private drainPendingQueue(): void {
    if (this.conversation === null) return;
    const id: string = this.conversation.id;
    getConversationRunService().resumePending(id).catch((error: Error): void => {
      if (this.pageAlive && this.conversation?.id === id) this.errorMsg = error.message;
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
  private async sendWithoutAnswer(): Promise<void> {
    if (this.materializingImages || this.sendPrechecking) return;
    const text: string = this.inputText.trim();
    if ((text.length === 0 && this.pendingImages.length === 0 && this.pendingDocs.length === 0) ||
      this.conversation === null) return;
    const acceptedText: string = this.inputText;
    const acceptedImages: string[] = this.pendingImages.slice();
    const acceptedDocs: PendingDoc[] = this.pendingDocs.slice();
    const owner: string = this.conversation.id;
    const editingNodeId: string = this.editingNodeId;
    const editingMessageId: string = this.editingMessageId;
    const inputParts: UIMessagePart[] = [];
    if (text.length > 0) inputParts.push({ type: 'text', text, metadata: null });
    for (const url of acceptedImages) inputParts.push({ type: 'image', url, metadata: null });
    for (const doc of acceptedDocs) inputParts.push({ type: 'document', url: doc.url,
      fileName: doc.fileName, mime: doc.mime, metadata: null });
    if (editingNodeId.length === 0 && this.tryRouteDeepReadSlash(inputParts)) return;
    this.sendPrechecking = true;
    const sequence: number = ++this.sendPreflightSeq;
    try {
      if (!await this.prepareComposerSend(inputParts, owner, sequence) || this.editingNodeId !== editingNodeId ||
        this.editingMessageId !== editingMessageId) return;
      this.dismissKeyboardAfterSend();
      if (this.sending) await this.enqueuePendingParts(inputParts, true, 'STEER');
      else if (getConversationRunService().isBusy(owner)) await this.enqueuePendingParts(inputParts, false);
      else await getConversationRunService().append(owner, inputParts);
      if (this.pageAlive && this.conversation?.id === owner) {
        this.clearAcceptedComposer(acceptedText, acceptedImages, acceptedDocs);
        if (editingNodeId.length > 0 && this.editingNodeId === editingNodeId && this.editingMessageId === editingMessageId) {
          this.editingNodeId = '';
          this.editingMessageId = '';
        }
      }
    } catch (error) {
      if (this.pageAlive && this.conversation?.id === owner) this.errorMsg = (error as Error).message;
    } finally {
      this.finishComposerPreflight(sequence);
    }
  }

  private composerSendCurrent(owner: string, sequence: number): boolean {
    return this.pageAlive && this.pageVisible && this.conversation?.id === owner && sequence === this.sendPreflightSeq;
  }

  private finishComposerPreflight(sequence: number): void {
    if (sequence === this.sendPreflightSeq) this.sendPrechecking = false;
  }

  private async prepareComposerSend(inputParts: UIMessagePart[], owner: string, sequence: number): Promise<boolean> {
    try {
      const choice: ChatProviderChoice | null = await resolveEffectiveChatChoice(this.conversation?.assistantId);
      if (!this.composerSendCurrent(owner, sequence)) return false;
      if (choice === null) {
        this.errorMsg = '请先选择模型';
        return false;
      }
      return await this.guardImagesForSend(inputParts, choice, owner, sequence);
    } catch (error) {
      if (this.composerSendCurrent(owner, sequence)) this.errorMsg = (error as Error).message;
      return false;
    }
  }

  // ===== D-080 模型空值门 =====
  // Android ChatPage.kt:532-533 canSendWithoutChatModel + :676-679/:695-698/
  //   :823-825/:864-869/:880-885 发送门 — currentChatModel == null 且非
  //   /deepread 斜杠(编辑态恒要模型)→ toast '请先选择模型'(逐字)中止:
  //   输入保留,不入队/不生成/不编辑。斜杠路由已在各路径前置拦截,无需在此判断。
  // D-111:choice 透传(图片守卫需要 chat 模型 inputModalities)
  private gateModelConfigured(onConfigured: (choice: ChatProviderChoice) => void): void {
    resolveEffectiveChatChoice(this.conversation?.assistantId).then((choice: ChatProviderChoice | null): void => {
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
  // OCR 种子(providers/ocr_model 快照)可被设置页或 agent 的 settings_set_model_slot 改写;
  //   发送/检查前重读,读失败沿用旧快照
  private async reloadOcrSeed(): Promise<OcrSeed | null> {
    try {
      const seed: OcrSeed = await loadOcrSeed(getChatKvStore());
      if (this.pageAlive) this.ocrSeed = seed;
      return seed;
    } catch (e) {
      return this.ocrSeed;
    }
  }

  private async guardImagesForSend(
    inputParts: UIMessagePart[], choice: ChatProviderChoice, owner: string, sequence: number,
  ): Promise<boolean> {
    const seed: OcrSeed | null = await this.reloadOcrSeed();
    if (!this.composerSendCurrent(owner, sequence)) return false;
    const settings: ImageGuardSettings = {
      chatModel: choice.model,
      ocrModelId: seed !== null ? seed.ocrModelId : '',
      providers: seed !== null ? seed.providers : [],
    };
    const encodeCheck = (url: string): void => { encodeImageDetailed(url); };
    const probeHealth = (): Promise<VisionModelHealth> => {
      if (seed === null) return Promise.reject(new Error('视觉识别配置尚未加载'));
      return probeEntryOcrHealth(seed);
    };
    const issue = await firstImageBlockingIssueForSend(inputParts, settings, encodeCheck, probeHealth);
    if (!this.composerSendCurrent(owner, sequence)) return false;
    if (issue !== null) {
      this.errorMsg = issue.message;
      return false;
    }
    return true;
  }

  // 附件点按(ChatInputAttachments.kt:onStatusClick)— blocked 才 toast 错误,其余静默
  private inspectPendingImage(url: string): void {
    resolveEffectiveChatChoice(this.conversation?.assistantId).then(async (choice: ChatProviderChoice | null): Promise<void> => {
      const seed: OcrSeed | null = await this.reloadOcrSeed();
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

  private dismissCompletionNotice(): void {
    if (this.conversation !== null && this.completionNotice !== null) {
      getConversationRunService().dismissCompletion(this.conversation.id, this.completionNotice.runId);
    }
    this.completionNotice = null;
  }

  private requestCompletionVerification(): void {
    const notice: JevCompletionNotice | null = this.completionNotice;
    const conversation: Conversation | null = this.conversation;
    if (notice === null || conversation === null || this.sending || this.sendPrechecking || this.materializingImages) return;
    const snapshot: ConversationRunSnapshot | null = getConversationRunService().snapshot(conversation.id);
    const facts: JevCompletionFacts | null = jevUnverifiedChanges(currentMessages(conversation));
    if (snapshot?.completionNotice?.runId !== notice.runId || snapshot.sending || facts === null
      || facts.finalMessageId !== notice.finalMessageId || facts.finalContentFingerprint !== notice.finalContentFingerprint
      || facts.sourceFingerprint !== notice.sourceFingerprint) {
      this.dismissCompletionNotice();
      return;
    }
    const action: JevVerificationComposerAction = jevVerificationComposerAction(this.inputText,
      this.pendingImages.length > 0 || this.pendingDocs.length > 0, this.editingNodeId.length > 0);
    this.inputText = action.text;
    this.dismissCompletionNotice();
    if (action.send) this.send();
  }

  private async send(queueMode: PendingUserMessageMode = 'FOLLOWUP'): Promise<void> {
    if (this.materializingImages || this.sendPrechecking) return;
    const text: string = this.inputText.trim();
    if ((text.length === 0 && this.pendingImages.length === 0 && this.pendingDocs.length === 0) ||
      this.conversation === null) return;
    const acceptedText: string = this.inputText;
    const acceptedImages: string[] = this.pendingImages.slice();
    const acceptedDocs: PendingDoc[] = this.pendingDocs.slice();
    const owner: string = this.conversation.id;
    const editingNodeId: string = this.editingNodeId;
    const editingMessageId: string = this.editingMessageId;
    const inputParts: UIMessagePart[] = [];
    if (text.length > 0) inputParts.push({ type: 'text', text, metadata: null });
    for (const url of acceptedImages) inputParts.push({ type: 'image', url, metadata: null });
    for (const doc of acceptedDocs) inputParts.push({ type: 'document', url: doc.url,
      fileName: doc.fileName, mime: doc.mime, metadata: null });
    if (editingNodeId.length === 0 && this.tryRouteDeepReadSlash(inputParts)) return;
    this.sendPrechecking = true;
    const sequence: number = ++this.sendPreflightSeq;
    try {
      if (!await this.prepareComposerSend(inputParts, owner, sequence) || this.editingNodeId !== editingNodeId ||
        this.editingMessageId !== editingMessageId) return;
      this.dismissKeyboardAfterSend();
      if (this.sending) await this.enqueuePendingParts(inputParts, true, queueMode);
      else if (editingNodeId.length > 0) {
        if (!this.sendEdit(text)) return;
      } else if (!this.sendParts(inputParts)) return;
      if (this.pageAlive && this.conversation?.id === owner) {
        this.clearAcceptedComposer(acceptedText, acceptedImages, acceptedDocs);
      }
    } catch (error) {
      if (this.pageAlive && this.conversation?.id === owner) this.errorMsg = (error as Error).message;
    } finally {
      this.finishComposerPreflight(sequence);
    }
  }

  private clearAcceptedComposer(text: string, images: string[], docs: PendingDoc[]): void {
    if (this.inputText === text) this.inputText = '';
    const remainingImages: string[] = this.pendingImages.slice();
    for (const url of images) {
      const index: number = remainingImages.indexOf(url);
      if (index >= 0) remainingImages.splice(index, 1);
    }
    this.pendingImages = remainingImages;
    const remainingDocs: PendingDoc[] = this.pendingDocs.slice();
    for (const doc of docs) {
      const index: number = remainingDocs.findIndex((candidate: PendingDoc): boolean =>
        candidate.url === doc.url && candidate.fileName === doc.fileName);
      if (index >= 0) remainingDocs.splice(index, 1);
    }
    this.pendingDocs = remainingDocs;
  }

  private sendParts(
    inputParts: UIMessagePart[], dispatchMessageId: string = '',
  ): boolean {
    const command: ConversationSendCommand = { kind: 'send', parts: inputParts, dispatchMessageId: dispatchMessageId };
    if (!this.submitConversationRun(command)) return false;
    if (this.enableAutoScroll) this.jumpToBottom();
    return true;
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
      if (this.actionsSheetMessage().role === 'assistant') {
        AgentMessageActionsSheet({
          onSelectText: (): void => { this.copyActionsSheetMessage(); },
          onCopy: (): void => { this.copyAssistantMessage(); },
          onRegenerate: (): void => { this.regenerateActionsSheetMessage(); },
          onDismiss: (): void => { this.closeActionsSheet(); },
        })
      } else {
        ChatMessageActionsSheet({
          message: this.actionsSheetMessage(),
          isUser: this.actionsSheetIsUser(),
          isLastAssistant: this.actionsSheetIsLastAssistant(),
          onCopy: (): void => { this.copyActionsSheetMessage(); },
          onEdit: (): void => { this.editActionsSheetMessage(); },
          onRegenerate: (): void => { this.regenerateActionsSheetMessage(); },
          onFork: (): void => { this.forkActionsSheetMessage(); },
          onShare: (): void => { this.shareActionsSheetMessage(); },
          canArchive: true,
          onArchive: (): void => { this.openChatWorkspace(this.actionsSheetMessage().id); },
          onOpenWorkspace: (): void => { this.openChatWorkspace(); },
          canFavorite: true,
          isFavorite: this.favoriteNodeIds.indexOf((this.actionsSheetRow as MsgRow).nodeId) >= 0,
          onFavorite: (): void => { if (this.actionsSheetRow !== null) this.toggleFavorite(this.actionsSheetRow); },
          onExport: (): void => { this.openMessageExport(this.actionsSheetMessage().id); },
          onDelete: (): void => { this.deleteActionsSheetMessage(); },
          onDismiss: (): void => { this.closeActionsSheet(); },
        })
      }
    }
  }

  // Phase 6:抽屉会话列表注入
  @Builder
  drawerConversationList(): void {
    ChatListGrouped({
      conversations: this.drawerConversations,
      selectedId: this.conversation !== null ? this.conversation.id : '',
      runningIds: this.runningConversationIds,
      onOpenConversation: (id: string): void => { this.openDrawerConversation(id); },
      onDelete: (item: ConversationSummary): void => {
        this.deleteDrawerConversation(item);
      },
      onPin: (item: ConversationSummary): void => {
        getConversationRunService().mutateStoredConversation(item.id,
          (latest: Conversation): Conversation => patchConversation(latest, { isPinned: !latest.isPinned }))
          .then((): void => { this.refreshDrawerConversations(); })
          .catch((e: Error): void => { this.errorMsg = e.message; });
      },
      onRegenerateTitle: (item: ConversationSummary): void => {
        this.regenerateDrawerTitle(item);
      },
    })
    // 消息全文命中(有搜索词时展示;点击进入对应会话)
    if (this.drawerMessageHits.length > 0) {
      Column() {
        Text(`消息命中 · ${this.drawerMessageHits.length}`)
          .fontSize(LABEL).fontColor(INK3)
          .width('100%').margin({ top: 14, bottom: 6 })
        ForEach(this.drawerMessageHits, (hit: MessageSearchHit): void => {
          Column() {
            Text(hit.conversationTitle.length > 0 ? hit.conversationTitle : '未命名会话')
              .fontSize(BODY2).fontWeight(FontWeight.Medium).fontColor(INK)
              .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
            Text(hit.snippet)
              .fontSize(META).fontColor(INK3).margin({ top: 3 })
              .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
          }
          .alignItems(HorizontalAlign.Start).width('100%')
          .padding({ left: 12, right: 12, top: 10, bottom: 10 })
          .borderRadius(RADIUS)
          .backgroundColor(SURFACE)
          .margin({ bottom: 6 })
          .onClick((): void => { this.openDrawerConversation(hit.conversationId); })
        }, (hit: MessageSearchHit): string => hit.conversationId)
      }
      .width('100%')
    }
  }

  // Tool preview sheet builder
  @Builder
  toolPreviewSheetBuilder() {
    if (this.toolPreviewPart !== null) {
      ChatMessageToolPreviewSheet({
        part: this.currentToolPreviewPart(),
        jevSubjectHash: this.jevSubjectHash(this.currentToolPreviewPart(), this.toolPreviewLocator ?? undefined),
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
            .fontSize(12).fontColor(INK).fontFamily(FONT_MONO)
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
            .fontSize(12).fontColor(INK).fontFamily(FONT_MONO)
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

  private smartUiEntry(sourceKey: string): SmartUiValueEntry | undefined {
    return this.smartUiValues.find((entry: SmartUiValueEntry): boolean => entry.sourceKey === sourceKey);
  }

  private onSmartUiValuesChanged(): void {
    this.smartUiStateRevision++;
  }

  private currentSmartUiWeatherProjections(): SmartUiWeatherProjection[] {
    const conversation: Conversation | null = this.conversation;
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

  private smartUiValue(sourceKey: string, stateId: string): SmartUiScalar | undefined {
    const entry: SmartUiValueEntry | undefined = this.smartUiEntry(sourceKey);
    const item: SmartUiStateValue | undefined = entry?.values.find(
      (value: SmartUiStateValue): boolean => value.id === stateId);
    return item?.value;
  }

  private smartUiValuesFor(sourceKey: string): SmartUiStateValue[] {
    return this.smartUiEntry(sourceKey)?.values ?? EMPTY_SMART_UI_VALUES;
  }

  private setSmartUiValue(sourceKey: string, stateId: string, value: SmartUiScalar): void {
    const runtime: SmartUiRuntimeEntry | undefined = this.smartUiRuntime.get(sourceKey);
    if (runtime !== undefined) runtime.userChanged = true;
    const entries: SmartUiValueEntry[] = this.smartUiValues.slice();
    const entry: SmartUiValueEntry | undefined = entries.find(
      (candidate: SmartUiValueEntry): boolean => candidate.sourceKey === sourceKey);
    if (entry === undefined) {
      this.smartUiValues = entries.concat([{ sourceKey, values: [{ id: stateId, value }] }]);
      return;
    }
    const values: SmartUiStateValue[] = entry.values.slice();
    const position: number = values.findIndex((item: SmartUiStateValue): boolean => item.id === stateId);
    if (position >= 0) values[position] = { id: stateId, value };
    else values.push({ id: stateId, value });
    this.smartUiValues = entries.map((candidate: SmartUiValueEntry): SmartUiValueEntry =>
      candidate.sourceKey === sourceKey ? { sourceKey, values } : candidate);
  }

  private clearSmartUiValues(sourcePrefix: string): void {
    if (sourcePrefix.length === 0) return;
    const prefix: string = `${sourcePrefix}:`;
    this.smartUiValues = this.smartUiValues.filter(
      (entry: SmartUiValueEntry): boolean => !entry.sourceKey.startsWith(prefix));
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

  private onSmartUiStateChange(sourceKey: string, _stateId: string, _value: SmartUiScalar): void {
    const runtime: SmartUiRuntimeEntry | undefined = this.smartUiRuntime.get(sourceKey);
    this.saveSmartUiEntry(runtime, 250);
  }

  private saveSmartUiEntry(runtime: SmartUiRuntimeEntry | undefined, delayMs: number): void {
    const store: SmartUiDraftStore | null = this.smartUiDraftStore;
    if (runtime === undefined || store === null || !runtime.ready || runtime.source.nodeId.length === 0) return;
    store.saveDebounced(runtime.document, runtime.source, this.smartUiValuesFor(runtime.sourceKey), Date.now(), delayMs)
      .then((result: SmartUiDraftSaveResult): void => { this.smartUiSaveResult(runtime.sourceKey, result); })
      .catch((error: Error): void => {
        this.smartUiSaveError = `智能 UI 草稿保存失败：${error.message}`;
        if (this.pageAlive) this.errorMsg = this.smartUiSaveError;
      });
  }

  // P3 hook: the current definition and lifecycle are already source-keyed here;
  // persistence can attach without re-parsing text groups or changing native cards.
  private replaceSmartUiValues(sourceKey: string, values: SmartUiStateValue[]): void {
    const next: SmartUiValueEntry[] = this.smartUiValues.filter(
      (entry: SmartUiValueEntry): boolean => entry.sourceKey !== sourceKey);
    this.smartUiValues = next.concat([{ sourceKey, values: values.slice() }]);
  }

  private smartUiSourceMatches(left: SmartUiDraftSource, right: SmartUiDraftSource): boolean {
    return left.conversationId === right.conversationId && left.nodeId === right.nodeId &&
      left.messageId === right.messageId && left.blockId === right.blockId &&
      left.blockIndex === right.blockIndex && left.sourceStart === right.sourceStart;
  }

  private smartUiSaveResult(sourceKey: string, result: SmartUiDraftSaveResult): void {
    if (result.saved || result.reason === 'cancelled' || result.reason === 'document-not-ready') return;
    const message: string = result.error ?? `智能 UI 草稿未保存：${result.reason}`;
    this.smartUiSaveError = message;
    if (this.pageAlive) this.errorMsg = message;
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
      if (!this.pageAlive || current === undefined || current.loadEpoch !== epoch || current.fingerprint !== fingerprint ||
        !this.smartUiSourceMatches(current.source, source) || current.userChanged) return;
      current.loaded = true;
      if (restore.status === 'restored') this.replaceSmartUiValues(sourceKey, restore.values);
      if (restore.status === 'rejected' && restore.diagnostics.length > 0) {
        this.smartUiSaveError = restore.diagnostics.join('；');
      }
    }).catch((error: Error): void => {
      const current: SmartUiRuntimeEntry | undefined = this.smartUiRuntime.get(sourceKey);
      if (!this.pageAlive || current === undefined || current.loadEpoch !== epoch) return;
      current.loaded = true;
      this.smartUiSaveError = `智能 UI 草稿读取失败：${error.message}`;
      this.errorMsg = this.smartUiSaveError;
    });
  }

  private onSmartUiDocument(sourceKey: string, document: SmartUiDocument, streaming: boolean,
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

  private reconcileSmartUiSources(conversation: Conversation): void {
    this.smartUiRuntime.forEach((runtime: SmartUiRuntimeEntry, key: string): void => {
      if (runtime.source.nodeId.length === 0 || runtime.source.conversationId !== conversation.id) return;
      const node: MessageNode | undefined = conversation.messageNodes.find(
        (candidate: MessageNode): boolean => candidate.id === runtime.source.nodeId);
      const valid: boolean = node !== undefined && node.messages.some(
        (message: UIMessage): boolean => message.id === runtime.source.messageId);
      if (valid) return;
      this.smartUiRuntime.delete(key);
      this.smartUiValues = this.smartUiValues.filter((entry: SmartUiValueEntry): boolean => entry.sourceKey !== key);
      this.smartUiDraftStore?.remove(runtime.source).catch((error: Error): void => {
        this.smartUiSaveError = `智能 UI 草稿清理失败：${error.message}`;
      });
    });
  }

  private cleanupSmartUiMessage(conversationId: string, messageId: string): void {
    this.smartUiRuntime.forEach((runtime: SmartUiRuntimeEntry, key: string): void => {
      if (runtime.source.conversationId !== conversationId || runtime.source.messageId !== messageId) return;
      this.smartUiRuntime.delete(key);
      this.smartUiValues = this.smartUiValues.filter((entry: SmartUiValueEntry): boolean => entry.sourceKey !== key);
      this.smartUiDraftStore?.remove(runtime.source).catch((error: Error): void => {
        this.smartUiSaveError = `智能 UI 草稿清理失败：${error.message}`;
      });
    });
  }

  private clearSmartUiMemory(): void {
    this.smartUiRuntime.clear();
    this.smartUiValues = [];
    this.smartUiProjectionConversation = null;
    this.smartUiProjectionNodes = null;
    this.smartUiWeatherProjections = [];
  }

  private onSmartUiDraft(prompt: string): void {
    this.inputText = appendSmartUiDraft(this.inputText, prompt);
  }

  private smartUiAssetAreaKey(messageId: string, partIndex: number, ref: string, sourceStart: number): string {
    // Declared asset IDs are unique within a message, including across text groups.
    return JSON.stringify([messageId, ref]);
  }

  private onSmartUiAssetArea(messageId: string, partIndex: number, ref: string,
    sourceStart: number, area: Area): void {
    this.smartUiAssetAreas.set(this.smartUiAssetAreaKey(messageId, partIndex, ref, sourceStart),
      Number(area.globalPosition.y));
  }

  private onSmartUiAssetView(nodeId: string, messageId: string, partIndex: number,
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

  // ForEach 保留初次创建的 row 闭包；参数与操作必须再读取当前状态。
  // 活动尾提交后有了 nodeId，但 message.id 不变，因此仍更新原来的 ListItem。
  private currentTimelineRow(seed: MsgRow): MsgRow {
    const current: MsgRow | undefined = this.rows.find((row: MsgRow): boolean =>
      seed.nodeId.length > 0 ? row.nodeId === seed.nodeId : row.message.id === seed.message.id);
    if (current !== undefined) return current;
    const message: UIMessage | undefined = this.streamingTail.find((item: UIMessage): boolean =>
      item.id === seed.message.id);
    if (message === undefined) return seed;
    return {
      nodeId: '', message: message, branchCount: 1, selectIndex: 0,
      isAssistant: message.role === 'assistant', isLastAssistant: true, displayRevision: 0,
    };
  }

  private displayedRowMessage(seed: MsgRow): UIMessage {
    const row: MsgRow = this.currentTimelineRow(seed);
    return row.nodeId === this.regenNodeId && this.regenTail !== null ? this.regenTail : row.message;
  }

  private chatTimelineItems(): MsgRow[] {
    // 只复制历史行引用；48ms 文本发布不会复制历史正文或创建历史行对象。
    const items: MsgRow[] = this.rows.slice();
    for (const message of this.streamingTail) {
      items.push({
        nodeId: '', message: message, branchCount: 1, selectIndex: 0,
        isAssistant: message.role === 'assistant', isLastAssistant: true, displayRevision: 0,
      });
    }
    return items;
  }

  private timelineRowStreaming(seed: MsgRow): boolean {
    const row: MsgRow = this.currentTimelineRow(seed);
    if (row.nodeId.length > 0) return row.nodeId === this.regenNodeId && this.regenTail !== null;
    return this.streamingTail.some((message: UIMessage): boolean => message.id === seed.message.id);
  }

  private timelineRowKey(row: MsgRow): string {
    // 完成、编辑、审批只更新参数；切换到另一消息分支才改变身份。
    return row.nodeId === this.regenNodeId && this.regenTail !== null ? this.regenTail.id : row.message.id;
  }

  @Builder
  chatTimelineRows(): void {
    ForEach(this.chatTimelineItems(), (row: MsgRow): void => {
      ListItem() {
        AgentMessageRow({
          animateEntrance: false,
          message: this.displayedRowMessage(row),
          streaming: this.timelineRowStreaming(row),
          capabilities: CHAT_MESSAGE_ROW_CAPABILITIES,
          showAssistantBubble: this.displaySettings.showAssistantBubble,
          showModelName: this.displaySettings.showModelName,
          showModelIcon: this.displaySettings.showModelIcon,
          showUserAvatar: this.displaySettings.showUserAvatar,
          userAvatar: this.userAvatar,
          userNickname: this.userNickname,
          fontSizeRatio: this.displaySettings.fontSizeRatio,
          codeBlockAutoCollapse: this.displaySettings.codeBlockAutoCollapse,
          showLineNumbers: this.displaySettings.showLineNumbers,
          chatFontFamily: this.displaySettings.chatFontFamily,
          enableLatexRendering: this.displaySettings.enableLatexRendering,
          enableGenerativeWidgets: this.displaySettings.enableGenerativeWidgets,
          smartUiConversationId: this.conversation?.id ?? '',
          smartUiNodeId: this.currentTimelineRow(row).nodeId,
          smartUiStateAccessor: this.smartUiStateAccessor,
          smartUiStateRevision: this.smartUiStateRevision,
          smartUiProjections: this.currentSmartUiWeatherProjections(),
          smartUiValuesFor: (sourceKey: string): SmartUiStateValue[] => this.smartUiValuesFor(sourceKey),
          onOpenDocument: (part: UIMessagePartDocument): void => { this.openDocumentPreview(part); },
          hostHeader: (): void => {},
          hostFooter: (): void => {},
          autoCloseThinking: this.autoCloseThinking,
          showThinkingContent: this.displaySettings.showThinkingContent,
          branchCount: this.currentTimelineRow(row).branchCount,
          selectIndex: this.currentTimelineRow(row).selectIndex,
          isLastAssistant: this.currentTimelineRow(row).isLastAssistant,
          onSwitch: (idx: number): void => this.switchBranch(this.currentTimelineRow(row).nodeId, idx),
          onRegenerate: (): void => this.regenerate(this.currentTimelineRow(row).nodeId),
          onEdit: (): void => this.startEditMessage(this.currentTimelineRow(row)),
          onOpenActions: (): void => this.openActionsSheet(this.currentTimelineRow(row)),
          onFork: (): void => this.forkAt(this.currentTimelineRow(row)),
          onApproveTool: (toolCallId: string, locator?: ToolApprovalPartLocator): void =>
            this.handleToolApproval(toolCallId, true, '', locator),
          onDenyTool: (toolCallId: string, locator?: ToolApprovalPartLocator): void =>
            this.openDenyDialog(toolCallId, locator),
          onAnswerTool: (toolCallId: string, answer: string, locator?: ToolApprovalPartLocator): void =>
            this.handleToolAnswer(toolCallId, answer, locator),
          onOpenCouncilRun: (rid: string, tools: UIMessagePartTool[]): void => {
            this.councilSheetRunId = rid; this.councilSheetTools = tools; this.councilSheetOpen = true;
          },
          onSubAgentResultToggle: (): void => {
            this.nativeFollow?.cancel();
            this.followMode = 'paused';
          },
          onOpenSubAgentRun: (rid: string, tools: UIMessagePartTool[]): void => {
            this.openSubAgentSheet(rid, tools);
          },
          onCopy: (): void => { this.openCopySheet(this.displayedRowMessage(row)); },
          isFavorite: this.favoriteNodeIds.indexOf(this.currentTimelineRow(row).nodeId) >= 0,
          onFavorite: (_text: string): void => { this.toggleFavorite(this.currentTimelineRow(row)); },
          jevSubjectHashForPart: (part: UIMessagePartTool, locator?: ToolApprovalPartLocator): string => this.jevSubjectHash(part, locator),
              onOpenToolPreview: (part: UIMessagePartTool, locator?: ToolApprovalPartLocator): void => { this.openToolPreview(part, locator); },
          onModifyGeneratedImage: (url: string): void => { this.modifyGeneratedImage(url); },
          onWidgetAction: (prompt: string): void => { this.inputText = prompt; },
          onSmartUiStateChange: (sourceKey: string, stateId: string, value: SmartUiScalar): void => {
            this.onSmartUiStateChange(sourceKey, stateId, value);
          },
          onSmartUiDraft: (prompt: string): void => { this.onSmartUiDraft(prompt); },
          onSmartUiAssetView: (messageId: string, partIndex: number, ref: string, sourceStart: number): void => {
            this.onSmartUiAssetView(this.currentTimelineRow(row).nodeId, messageId, partIndex, ref, sourceStart);
          },
          onSmartUiSourceReplaced: (sourcePrefix: string): void => {
            this.clearSmartUiValues(sourcePrefix);
          },
          onSmartUiDocument: (sourceKey: string, document: SmartUiDocument, streaming: boolean,
            source: SmartUiDraftSource, complete: boolean): void => {
            this.onSmartUiDocument(sourceKey, document, streaming, source, complete);
          },
          onSmartUiAssetArea: (partIndex: number, ref: string, sourceStart: number, area: Area): void => {
            this.onSmartUiAssetArea(this.currentTimelineRow(row).message.id, partIndex, ref, sourceStart, area);
          },
          onTextGrow: (): void => { this.followStreamingUpdate(); },
          onRunMiniApp: (appId: string): void => {
            router.pushUrl({ url: 'pages/MiniAppRunnerPage', params: { appId: appId } });
          },
          onModifyMiniApp: (prompt: string): void => { this.inputText = prompt; },
          onOpenMiniAppList: (): void => { router.pushUrl({ url: 'pages/MiniAppListPage' }); },
        })
      }
      if (this.compactBoundaryCount(this.currentTimelineRow(row).message.id) > 0) {
        ListItem() {
          this.CompactMarkerRow(this.currentTimelineRow(row))
        }
      }
    }, (row: MsgRow): string => this.timelineRowKey(row))
  }

  @Builder
  chatTimelineActiveTail(): void {
    // 活动尾已在 chatTimelineRows 内；共用壳仍保留此 slot 给其他宿主。
  }

  @Builder
  chatSheetHosts(): void {
    // bindSheet 按宿主节点注册；每张 sheet 保持独立且稳定的宿主。
    Column() {}
      .width(0).height(0)
      .hitTestBehavior(HitTestMode.None)
      .bindSheet($$this.documentPreviewOpen, this.documentPreviewSheet(), {
        height: '85%', showClose: false,
      })
    Column() {}
      .width(0).height(0)
      .hitTestBehavior(HitTestMode.None)
      .bindSheet($$this.artifactShelfOpen, this.artifactShelfSheet(), {
        height: '85%', showClose: false,
      })
    Column() {}
      .width(0).height(0)
      .hitTestBehavior(HitTestMode.None)
      .bindSheet($$this.artifactDiffOpen, this.artifactDiffSheet(), {
        height: '85%', showClose: false,
      })
    Column() {}
      .width(0).height(0)
      .hitTestBehavior(HitTestMode.None)
      .bindSheet($$this.webmountSheetOpen, this.webmountSheet(), {
        height: '92%', showClose: false,
        onDisappear: (): void => {
          // sheet 关闭(offscreen/手势):恢复 offscreen host 挂载
          if (!this.webmountHostVisible) this.webmountHostVisible = true;
        },
      })
    Column() {}
      .width(0).height(0)
      .hitTestBehavior(HitTestMode.None)
      .bindSheet(this.sandboxSheetOpen, this.sandboxSheet(this.sandboxTimeline()), {
        height: SheetSize.LARGE,
        onDisappear: (): void => { this.sandboxSheetOpen = false; },
      })
    Column() {}
      .width(0).height(0)
      .hitTestBehavior(HitTestMode.None)
      .bindSheet(this.subAgentSheetOpen, this.subAgentSheetBuilder(), {
        height: SheetSize.LARGE,
        showClose: false,
        backgroundColor: SURFACE,
        onDisappear: (): void => {
          this.closeSubAgentSheet();
          this.subAgentSheetRunId = '';
          this.subAgentLiveText = '';
        },
      })
    Column() {}
      .width(0).height(0)
      .hitTestBehavior(HitTestMode.None)
      .bindSheet(this.councilSheetOpen, this.CouncilSheet(), {
        height: SheetSize.LARGE,
        showClose: false,
        backgroundColor: SURFACE,
        onDisappear: (): void => { this.councilSheetOpen = false; },
      })
    Column() {}
      .width(0).height(0)
      .hitTestBehavior(HitTestMode.None)
      .bindSheet(this.queueSheetOpen, this.PendingQueueSheet(), {
        height: SheetSize.LARGE,
        onDisappear: (): void => { this.queueSheetOpen = false; },
      })
    Column() {}
      .width(0).height(0)
      .hitTestBehavior(HitTestMode.None)
      .bindSheet(this.compactSummaryOpen, this.CompactSummarySheet(), {
        height: SheetSize.LARGE,
        onDisappear: (): void => { this.compactSummaryOpen = false; },
      })
    Column() {}
      .width(0).height(0)
      .hitTestBehavior(HitTestMode.None)
      .bindSheet(this.copySheetOpen, this.copySheetBuilder(), {
        height: SheetSize.MEDIUM,
        showClose: false,
        onDisappear: (): void => { this.closeCopySheet(); },
      })
    Column() {}
      .width(0).height(0)
      .hitTestBehavior(HitTestMode.None)
      .bindSheet(this.actionsSheetOpen, this.messageActionsSheetBuilder(), {
        height: this.actionsSheetRow !== null && this.actionsSheetMessage().role === 'assistant'
          ? SheetSize.FIT_CONTENT : SheetSize.LARGE,
        showClose: this.actionsSheetIsUser(),
        onDisappear: (): void => { this.clearActionsSheet(); },
      })
    Column() {}
      .width(0).height(0)
      .hitTestBehavior(HitTestMode.None)
      .bindSheet(this.toolPreviewSheetOpen, this.toolPreviewSheetBuilder(), {
        height: SheetSize.LARGE, showClose: false, backgroundColor: SURFACE,
        onDisappear: (): void => {
          this.closeToolPreview();
          this.toolPreviewLocator = null;
        },
      })
  }

  // 建议浮在正文上；末尾留白是可滚动 item，不缩短整段正文的视口。
  @Builder
  private floatingSuggestions(): void {
    Scroll() {
      Row() {
        ForEach(this.suggestions, (s: string): void => {
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
            .onClick((): void => { this.inputText = s; })
            .gesture(
              LongPressGesture()
                .onAction((): void => {
                  if (this.sending) {
                    this.gateModelConfigured((_choice: ChatProviderChoice): void => {
                      this.enqueuePendingParts([{ type: 'text', text: s, metadata: null }], true)
                        .catch((error: Error): void => { this.errorMsg = error.message; });
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
    .height(40)
    .position({ left: 0, bottom: 8 })
  }

  @Builder
  private chatTimelineBottomInset(): void {
    if (this.suggestions.length > 0) {
      ListItem() { Column().height(52) }
    }
  }

  build() {
    Row() {
    if (this.twoPane()) {
      HomeListPane({
        hostVisible: this.homePaneVisible,
        twoPane: true,
        inChat: true,
        activeId: this.homePaneActiveId,
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
    // WebMount 会话宿主(移出可视区的真实尺寸 Web;按需挂载)
    WebMountHost({ visible: this.webmountHostVisible })
    // JS 沙箱宿主(隐藏 1x1;勾选 javascript_engine 时挂载)
    if (this.jsSandboxNeeded) {
      JsSandboxHost()
    }
    Column() {
      // 顶栏参与布局，正文视口从顶栏细线开始。
      // 顶栏位于正文视口之前，细线就是正文上边界。
      Row() {
        // amber-zhenji:chat-head 左侧为返回(抽屉入口收归首页)。
        //   两栏时左边就是列表,去掉返回;占位保留,标题仍居中(系统返回照旧回首页)。
        if (this.twoPane()) {
          Row().width(48).height(48)
        } else {
          Row() {
            Icon({ name: 'back', iconSize: 24, color: INK })
          }
          .width(48).height(48)
          // 窄 chevron 墨迹比右侧产物架图标内缩 4vp:右 padding 8 把墨迹左移,左右墨迹边距对称
          .padding({ right: 8 })
          .justifyContent(FlexAlign.Center)
          .alignItems(VerticalAlign.Center)
          .onClick((): void => { this.navigateBack(); })
          .accessibilityText('返回')
        }

        // 两行文字共用中轴，左右各保留 48vp 按钮区域。
        Column() {
          if (this.editingTitle) {
            TextInput({ text: this.titleInput, placeholder: '会话标题' })
              .fontSize(TEXT_STYLES.sessionTitle.size)
              .fontWeight(TEXT_STYLES.sessionTitle.weight)
              .lineHeight(TEXT_STYLES.sessionTitle.lineHeight)
              .fontFamily(TEXT_STYLES.sessionTitle.family)
              .layoutWeight(1).height(36)
              .backgroundColor(SURFACE)
              .onChange((v: string): void => { this.titleInput = v; })
          } else {
            Row({ space: 6 }) {
              if (this.sending || this.recapState.loading) {
                LoadingProgress().width(14).height(14).color(this.accentColorStore)
              } else {
                Icon({ name: 'history', iconSize: 14, color: this.accentColorStore })
              }
              Text(this.sending ? '正在回复…' : (this.recapState.loading ? '正在整理回顾…' : this.title))
                .fontSize(14).fontWeight(FontWeight.Medium).fontColor(INK)
                .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                .flexShrink(1)
            }
            .height(34).constraintSize({ maxWidth: '100%' }).justifyContent(FlexAlign.Center)
            // 右侧补齐图标 14vp + 间距 6vp，让标题文字居中。
            .padding({ left: 10, right: 30 })
            .backgroundColor(SURFACE2).borderRadius(17)
            .onClick((): void => { this.openRecap(); })
            .accessibilityText('对话回顾：' + this.title)
          }
          // 模型行(amber-zhenji):mono 11.5 ink3 居中 + ⌄12
          Row() {
            Text(this.modelLabel.length > 0 ? this.modelLabel : '未配置模型')
              .fontSize(11.5).fontFamily(FONT_MONO)
              .fontColor(this.modelLabel.length > 0 ? INK3 : this.accentColorStore)
              .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis }).flexShrink(1)
            Icon({ name: 'chevron_down', iconSize: 12, color: INK3 })
              .rotate({ angle: this.modelMenuOpen ? 180 : 0 })
              .margin({ left: 2 })
          }
          .height(30).width('100%').justifyContent(FlexAlign.Center)
          // 左侧补齐下拉图标 12vp + 间距 2vp，与标题文字对齐。
          .padding({ left: 24, right: 10 })
          .onClick((): void => {
            if (!this.editingTitle) { this.closeRecap(); this.toggleModelMenu(); }
          })
          .accessibilityText('切换模型与服务商')
        }
        .layoutWeight(1)
        .alignItems(HorizontalAlign.Center)

        // 右侧保留产物架；编辑态沿用保存/取消。
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
            // 产物架入口(图片/文件/小应用陈列;40 热区)
            Row() {
              Icon({ name: 'box', iconSize: 20, color: INK })
            }
            .width(40).height(40)
            .justifyContent(FlexAlign.Center)
            .alignItems(VerticalAlign.Center)
            .onClick((): void => { this.openArtifactShelf(); })
            .accessibilityText('产物架')
          }
          .width(48).justifyContent(FlexAlign.Center)
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
      InteractiveMessageTimeline({
        scroller: this.scroller,
        scrollInteractionEnabled: this.activeArtifactInteraction.length === 0,
        horizontalGutter: 20,
        topPadding: 0,
        bottomPadding: 0,
        cachedItems: 5,
        showOlderLoader: !this.timelineLoadState.fullyLoaded,
        showDivider: false,
        showScrollBar: false,
        rows: (): void => { this.chatTimelineRows(); },
        activeTail: (): void => { this.chatTimelineActiveTail(); },
        hostTail: (): void => { this.chatTimelineBottomInset(); },
        onTimelineAppear: (): void => {
          this.listReady = true;
          if (this.initialScrollArmed) this.performInitialScroll();
        },
        onViewportHeightChange: (height: number): void => { this.listViewportHeight = height; },
        onReachStart: (): void => { this.loadOlderTimeline(); },
        onVisibleRangeChange: (start: number, end: number): void => {
          this.firstVisibleListIndex = start;
          this.lastVisibleListIndex = end;
        },
        onTimelineTouch: (event: TouchEvent): void => {
          if (event.type === TouchType.Down) {
            this.userTouchActive = true;
            this.nativeFollow?.cancel();
          }
          if (event.type === TouchType.Up || event.type === TouchType.Cancel) {
            this.userTouchActive = false;
            if (this.generationEndScrollPending) this.reconcileAtEnd();
            if (this.followMode !== 'paused') this.requestNativeFollow();
          }
        },
        onTimelineWillScroll: (offset: number, _state: ScrollState, source: ScrollSource): void => {
          if (offset !== 0 && (source === ScrollSource.DRAG || source === ScrollSource.FLING)) {
            this.nativeFollow?.cancel();
            this.programmaticScrollPending = false;
            this.showScrollNavigation();
          }
        },
        onTimelineScroll: (_scrollOffset: number, scrollState: ScrollState): void => {
          if (this.programmaticScrollPending && scrollState === ScrollState.Idle) {
            this.programmaticScrollPending = false;
          } else if ((scrollState === ScrollState.Scroll || scrollState === ScrollState.Fling) &&
            (this.userTouchActive ||
              (!this.programmaticScrollPending && !this.nativeFollow?.isRunning()))) {
            this.handleUserScroll(this.isGenerationActive());
          }
        },
        onTimelineScrollStop: (): void => {
          this.programmaticScrollPending = false;
          this.reconcileAtEnd();
        },
      })

      if (this.scrollNavigationVisible && this.jumpButtonVisible && this.showMessageJumper) {
        Stack() {
          Icon({ name: 'arrow_down_to_line', iconSize: 20, color: INK })
        }
        .width(40).height(40)
        .position(this.displaySettings.messageJumperOnLeft ? { left: 20, bottom: this.floatingControlsBottom() }
          : { right: 20, bottom: this.floatingControlsBottom() })
        .backgroundColor(SURFACE2)
        .borderRadius(20)
        .border({ width: 1, color: LINE })
        .shadow({ radius: 8, color: '#33000000', offsetY: 2 })
        .alignContent(Alignment.Center)
        .onClick((): void => { this.jumpToBottom(); })
        .accessibilityText('回到消息底部')
        .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: 8 }))
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      }

      // 回到顶部：只在用户滚动时短暂显示，避开建议与跳底按钮。
      if (this.scrollNavigationVisible && this.showMessageJumper && this.firstVisibleListIndex > 1) {
        Stack() {
          Icon({ name: 'arrow_up', iconSize: 20, color: INK })
        }
        .width(40).height(40)
        .position(this.displaySettings.messageJumperOnLeft
          ? { left: 20, bottom: this.floatingControlsBottom() + (this.jumpButtonVisible ? 48 : 0) }
          : { right: 20, bottom: this.floatingControlsBottom() + (this.jumpButtonVisible ? 48 : 0) })
        .backgroundColor(SURFACE2)
        .borderRadius(20)
        .border({ width: 1, color: LINE })
        .shadow({ radius: 8, color: '#33000000', offsetY: 2 })
        .alignContent(Alignment.Center)
        .onClick((): void => {
          this.followMode = 'paused';
          this.atBottomApprox = false;
          this.nativeFollow?.cancel();
          this.beginProgrammaticScroll();
          this.scroller.scrollEdge(Edge.Top);
        })
        .accessibilityText('回到消息顶部')
        .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: 8 }))
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      }

      if (this.suggestions.length > 0) {
        this.floatingSuggestions()
      }

      // ContextUsagePanel(Android ContextRing.kt ContextUsagePopup):指示器下方右锚定
      if (this.contextMeterOpen) {
        Column()
          .width('100%').height('100%')
          .hitTestBehavior(HitTestMode.Block)
          .onClick((): void => { this.contextMeterOpen = false; })
        Column() {
          this.contextUsagePanel()
        }
        .position({ top: 0, right: 8 })
        .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: -8 }))
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      }
      }
      .layoutWeight(1)
      .width('100%')

      if (this.completionNotice !== null && !this.sending) {
        JevCompletionNoticeView({
          onVerify: (): void => { this.requestCompletionVerification(); },
          onDismiss: (): void => { this.dismissCompletionNotice(); },
        }).margin({ left: 20, right: 20, top: 4, bottom: 4 })
      }

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

      // D-048:排队消息 chips(Android ChatListIndicators pending 区,可逐条取消)
      if (this.pendingRows.length > 0) {
        Scroll() {
          Row() {
            Text(`队列 ${this.pendingQueue.length}`)
              .fontSize(11).fontColor(INK3)
              .constraintSize({ minHeight: 44 })
              .margin({ right: 8 })
              .onClick((): void => { this.queueSheetOpen = true; })
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

      // D-110:上下文用量现位于服务商/模型面板顶部。

      // P4:输入区底部安全区(沉浸式全屏下不被导航条遮挡);
      //   Android ChatInput 沉浸式基准 = 安全区底部 + 10vp 呼吸
      Column() {
        // 第二梯队 E:ChatInput 组件替换内联输入行
        if (this.restoreReloadFailed) {
          Text('恢复后的会话读取失败，点击重试')
            .fontSize(13).fontColor(ERROR).width('100%').padding(12)
            .onClick((): void => {
              this.refreshRestoredConversation().catch((e: Error): void => { this.errorMsg = e.message; });
            })
        }
        if (this.storageDegraded) {
          Text('本地数据库暂不可用，无法保存新消息。点击重试。')
            .fontSize(13).fontColor(ERROR).width('100%').padding(12)
            .onClick((): void => {
              getChatRepository().list().catch((e: Error): void => { this.errorMsg = e.message; });
            })
        }
        // 子代理跨会话活动条(C5-4;有运行中子代理时显示)
        SubAgentActivityStrip()
        // WebMount 接管条(C4-2):会话有页面时显示;点开接管面板
        if (this.webmountUrl.length > 0 && this.webmountReady && !this.webmountSheetOpen) {
          Row() {
            Icon({ name: 'globe', iconSize: 14, color: this.accentColorStore })
            Text(`WebMount · ${this.webmountHostOf(this.webmountUrl)}`)
              .fontSize(META).fontColor(INK2)
              .margin({ left: 6 })
              .layoutWeight(1)
              .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
            Text(this.webmountLease === 'hand' ? '手动控制中' : '查看 / 接管')
              .fontSize(META).fontColor(this.accentColorStore)
          }
          .width('100%')
          .padding({ left: 14, right: 14, top: 8, bottom: 8 })
          .margin({ left: 20, right: 20, bottom: 2 })
          .backgroundColor(SURFACE)
          .borderRadius(10)
          .border({ width: 1, color: LINE })
          .onClick((): void => { this.openWebmountSheet(); })
          .accessibilityText('打开 WebMount 浏览器面板')
        }
        if (this.manualCompacting) {
          Text('正在压缩上下文…').fontSize(META).fontColor(INK3)
            .width('100%').padding({ left: 20, right: 20, top: 6, bottom: 4 })
        }
        ChatInput({
          disabled: ((this.storageDegraded || !this.conversationLoaded) && !this.sending) || this.manualCompacting,
          text: this.inputText,
          sending: this.sending,
          queueCount: this.pendingQueue.length,
          onOpenQueue: (): void => { this.queueSheetOpen = true; },
          onQueueSend: (mode: string): void => { this.send(mode as PendingUserMessageMode); },
          editing: this.editingNodeId.length > 0,
          hasImages: this.pendingImages.length > 0 || this.pendingDocs.length > 0,
          subAgentEnabled: this.subAgentEnabled,
          subAgentMode: this.subAgentMode,
          quickMessages: this.quickMessages,
          mentionRoles: this.mentionRoles,
          sendOnEnter: this.displaySettings.sendOnEnter,
          pasteLongTextAsFile: this.displaySettings.pasteLongTextAsFile,
          pasteLongTextThreshold: this.displaySettings.pasteLongTextThreshold,
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
          onTakePhoto: (): void => { this.takePhoto(); },
          onPickFile: (): void => { this.pickFiles(); },
          onPasteAsFile: (t: string): void => { this.attachPastedText(t); },
          onCancelEdit: (): void => { this.cancelEdit(); },
          onClearInput: (): void => { this.inputText = ''; },
          onCompactContext: (): void => { this.compactCurrentConversation(); },
          onInsertText: (t: string): void => { this.inputText = t; },
        })
      }
      .width('100%')
      .padding({ top: 8, bottom: this.navBarHeight + 10 })
      .backgroundColor(BG)
      .border({ width: { top: 0.5 }, color: LINE })
    }
    .width('100%').height('100%').backgroundColor(BG)

    // 空状态 hero(1:1 原型): 居中 Amber▌ 词标 36px 陶土光标闪烁 +
    //   问候句 3.2s 淡入淡出轮换;光学中心上移(margin bottom 128)
    if (this.conversationArrivedTick >= 0 && this.conversationLoaded && this.conversation !== null
      && this.rows.length === 0 && !this.sending) {
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
      // 模型下拉打开时隐藏 hero:面板下缘会横切问候句(只露半行)
      .visibility(this.modelMenuOpen ? Visibility.Hidden : Visibility.Visible)
    }

    // TopModelMenu 覆盖层(Android TopModelMenu.kt:Box { scrim; AnimatedVisibility(panel) })
    //   Stack 子节点序:主 Column(底) → 本覆盖层 → deny-dialog(顶,确保拒绝弹窗压在菜单之上)。
    //   组件自含全屏 dimmed scrim(28% 黑,点击 = onClose)+ 贴顶面板。
    if (this.modelMenuOpen) {
      TopModelMenu({
        topInset: this.chatHeaderHeight,
        contextUsedTokens: this.estimatedContextTokens(),
        contextWindowTokens: this.contextWindowTokens,
        contextColor: this.contextRingColor(),
        contextCompactActive: this.compactActiveCount > 0,
        turnTotal: this.lastTurnTotalText(),
        turnCache: this.lastTurnCacheText(),
        turnSpeed: this.lastTurnSpeedText(),
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
          router.pushUrl({ url: 'pages/ChatModelPickerPage',
            params: { assistantId: this.conversation?.assistantId ?? '' } });
        },
        onOpenProviderSettings: (): void => {
          this.closeModelMenu();
          router.pushUrl({ url: 'pages/ChatProviderSettingsPage' });
        },
      })
    }

    if (this.recapOpen) {
      ChatRecapPanel({
        recap: this.displayedRecap(),
        loading: this.recapSourceLoading || this.recapState.loading,
        error: this.recapState.error,
        stale: this.recapNeedsRefresh(),
        topInset: this.statusBarHeight + 80,
        onClose: (): void => { this.closeRecap(); },
        onRefresh: (): void => { this.openRecap(true); },
        onLocate: (node: ConversationRecapNode): void => { this.locateRecapNode(node); },
        onNextStep: (text: string): void => { this.closeRecap(); this.inputText = text; },
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

    if (this.workspacePanelOpen && this.conversation !== null) {
      ChatWorkspacePanel({ conversation: this.conversation, initialMessageId: this.archiveInitialMessageId,
        onDismiss: (): void => { this.workspacePanelOpen = false; },
        onOpenSource: (source: ChatMessageArchiveSource): void => {
          this.openArchiveSource(source).catch((error: Error): void => { this.errorMsg = error.message; });
        },
      })
    }
    if (this.exportSheetOpen && this.conversation !== null) {
      ChatExportSheet({
        show: true,
        conversation: this.conversation,
        messages: currentMessages(this.conversation),
        initialSelectedMessageIds: this.exportInitialMessageIds,
        onDismiss: (): void => { this.exportSheetOpen = false; },
      })
    }

    // ===== D-108:拒绝理由弹窗(ToolDenyReasonDialog :428-461 逐字) =====
    //   title '拒绝工具执行'/hint '原因（可选）'/confirm '拒绝'/dismiss '取消'
    //   (strings.xml:204/:203/:202);AlertDialog → Stack overlay(同 D-101 先例)
    if (this.denyDialogOpen) {
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
        .transition(TransitionEffect.scale({ x: 0.96, y: 0.96 })
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      }
      .width('100%').height('100%')
      .justifyContent(FlexAlign.Center)
      .backgroundColor(OVERLAY)
      .onClick((): void => { this.dismissDenyDialog(); })
      .transition(TransitionEffect.OPACITY.animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
    }
    
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
