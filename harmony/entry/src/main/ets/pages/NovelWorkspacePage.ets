import { getNovelPlanDraftStore } from '../novel/NovelPlanDraftStore.ets';
import { NovelPlanDraftStore, NovelControlPlanDraft, sameControlPlanDraft } from '../novel/NovelPlanDraftState';
// NovelWorkspacePage — 小说工作区:三页签(聊天写作 / 章节正文 / 活资料)
// 单文件承载三个 pane(@Builder)与对话框(Stack 叠层),状态集中于本页 @State。
import {
  ACCENT, ACCENT_INK, BG, ERROR, INK, INK2, INK3, INK4, LINE, MOTION_ENTER, MOTION_OVERLAY,
  MOTION_PRESS, OVERLAY, SUCCESS_FG, SURFACE, SURFACE2,
} from '../design/tokens.ets';
import { FONT_MONO, FONT_SANS_BOLD, FONT_SANS_MEDIUM } from '../design/fonts.ets';
import { Icon } from '../components/ui/Icon.ets';
import { Seg, CardGroup, CardRow } from '../components/PageTemplate.ets';
import { StudioTabs } from '../components/studio/StudioTabs.ets';
import { StudioInkStamp } from '../components/studio/StudioInkStamp.ets';
import { getProductKind } from '../platform_impl/ProductIdentity.ets';
import { ChatInput } from '../components/ChatInput.ets';
import {
  AgentMessageRow, NO_AGENT_MESSAGE_ROW_CAPABILITIES,
} from '../components/AgentMessageRow.ets';
import type { AgentMessageRowHostCapabilities } from '../components/AgentMessageRow.ets';
import { InteractiveMessageTimeline } from '../components/InteractiveMessageTimeline.ets';
import { NativeTimelineFollow } from '../components/NativeTimelineFollow.ets';
import { NovelDiscussionArchiveSheet } from '../components/NovelDiscussionArchiveSheet.ets';
import { NovelDiscussionArchiveCard } from '../components/NovelDiscussionArchiveCard.ets';
import { NovelRunRecoveryCard } from '../components/NovelRunRecoveryCard.ets';
import { discussionArchiveAtMessage, discussionMessageVisible } from '../components/NovelArchivePresentation.ets';
import { NovelMaterialAdoptionSheet, NovelMaterialAdoptionSheetTarget } from '../components/NovelMaterialAdoptionSheet.ets';
import { NovelMaterialEditorSheet, NovelMaterialEditorDraft, NovelMaterialEditScope, emptyMaterialEditorDraft, materialEditorDraftFor } from '../components/NovelMaterialEditorSheet.ets';
import { NovelMaterialCard, NovelHiddenSharedMaterials } from '../components/NovelMaterialCard.ets';
import { NovelSettingProposalCard } from '../components/NovelSettingProposalCard.ets';
import { orphanSettingProposals, settingProposalsForMessage } from '../platform_impl/NovelSettingProposalProjection.ets';
import {
  NovelPolishCreateSheet, NovelPolishCreateSheetChapter,
} from '../components/NovelPolishCreateSheet.ets';
import { NovelPolishJobCard } from '../components/NovelPolishJobCard.ets';
import { NovelBranchSheet } from '../components/NovelBranchSheet.ets';
import {
  NovelSettingsPane, NovelModelOptionView,
} from '../components/NovelSettingsPane.ets';
import { NovelAuditReportView } from '../components/NovelAuditReportView.ets';
import { NovelGhostwriteReportCard } from '../components/NovelGhostwriteReportCard.ets';
import { NovelRevisionBriefSheet } from '../components/NovelRevisionBriefSheet.ets';
import { NovelGhostwriteStartSheet } from '../components/NovelGhostwriteStartSheet.ets';
import { NovelGhostwriteCard } from '../components/NovelGhostwriteCard.ets';
import { KeyboardAvoidMode, router, promptAction } from '@kit.ArkUI';
import { fileIo, picker } from '@kit.CoreFileKit';
import { common } from '@kit.AbilityKit';
import { pasteboard } from '@kit.BasicServicesKit';
import {
  getNovelCreation, getNovelWorkspaceService, loadNovelModelOptions, describeChatModel,
} from '../di/AppContainer.ets';
import type { NovelModelOption } from '../di/AppContainer.ets';
import type { NovelBookExportBundle } from '../platform_impl/NovelWorkspaceService.ets';
import { shareNovelFile, NovelShareFormat } from '../platform_impl/NovelShareSupport.ets';
import { MATERIAL_KIND_LABELS } from '@amber/deepread-domain';
import {
  NovelProject, NovelMessage, makeNovelMessage, NovelChapter, NovelMaterial, NovelMaterialSuggestion, NovelBranch,
  NovelMaterialFields, NovelWorkspaceCas, NovelWorkspaceSnapshot, makeNovelMaterial, materialOrigin, NovelInjectionOverrides, novelComposerRunKind,
  NovelChatMode, NovelGenerationGranularity, NovelMaterialKind, NovelCollectionTarget,
  NovelRunKind, NovelRun, NovelActiveRun, NovelRunEvent, NovelOrdinaryRun, NovelOrdinaryRunView, latestAssistantText, ordinaryRunMessages, ordinaryRunHistoryCount,
  NovelSettingProposal, NovelForeshadow, NovelConfirmedDecision, NovelDiscussionArchive, NovelSuggestionRefreshResult, canCloneCollectedMessage,
  NovelWorkspaceStatus, StreamTransportState, UIMessage, makeUIMessage, toText,
  UIMessagePart, novelMessageUi, novelMessageText, NovelToolContinuationVerdict,
  NovelModelPolicy, NovelModelTarget, NovelModelDefaults, defaultNovelModelDefaults, resolveNovelDefaultTarget,
  NovelBranchSettings, NovelConsistencyReport,
  emptyNovelBranchSettings, eligibleNovelDiscussion, NovelDiscussionArchiveDraft,
  NovelMaterialAdoption, buildSuggestionAdoption, buildSettingProposalAdoption, materialAdoptionTargetDigest,
  NovelAuditIssue, NovelAuditReference, verifyAuditCanonicalReferences, NovelAuditReport, NovelAuditController, NovelAuditProgress, NovelAuditBlock, defaultGhostwriteDigest, DEFAULT_GHOSTWRITE_CHAPTER_COUNT,
  DurableGhostwriteJob, GhostwriteStage, DurablePolishJob, PolishContextOptions, PolishStage,
  NovelGhostwriteStartPreview, NovelGhostwriteReport, NovelChapterContract, confirmedChapterPlanText, chapterContractMarkdown, formatNovelQuickStartOverview, novelChapterOrdinal, nextNovelChapterOrdinal,
} from '@amber/deepread-domain';
import {
  shouldScrollOnStreamUpdate, transitionOnGenerateEnd, transitionOnJumpToBottom,
  transitionOnStreamUpdate, transitionOnUserScroll,
} from '@amber/chat-domain';
import type { FollowMode, ToolApprovalPartLocator } from '@amber/chat-domain';
import {
  selectedParagraphDraft, draftAfterSelection, canCollectSelection,
  defaultCollectionLocation, composerOwner, canRestoreFailedInput,
  NovelComposerDraftStore,
} from '../novel/NovelComposerState';
import type { NovelComposeMode, NovelComposerDraft, CollectionLocation } from '../novel/NovelComposerState';
import { getNovelComposerDraftStore } from '../novel/NovelComposerDraftStore.ets';
import { loadNovelModelDefaults } from '../platform_impl/NovelStandaloneDefaults.ets';


const NOVEL_AGENT_MESSAGE_CAPABILITIES: AgentMessageRowHostCapabilities = {
  copy: false,
  fork: false,
  branchSwitch: false,
  edit: false,
  regenerate: false,
  favorite: false,
  openActions: false,
  approveTool: true,
  denyTool: true,
  answerTool: true,
  openToolPreview: false,
  openSubAgentRun: false,
  runMiniApp: false,
  modifyMiniApp: false,
  openMiniAppList: false,
  openDocument: false,
};

type MaterialFilter = 'all' | NovelMaterialKind;

const KIND_FILTERS: MaterialFilter[] = ['all', 'world', 'character', 'outline', 'requirement', 'other'];
// ArkTS 不允许 Record<string,string> 对象字面量,用显式 interface
interface KindFilterLabels {
  all: string;
  world: string;
  character: string;
  outline: string;
  requirement: string;
  other: string;
}
interface ExportMenuResult {
  index: number;
}
type ControlPlanDraft = NovelControlPlanDraft;
interface WorkspaceReturnParams { novelContextOverrides?: string; }
interface NovelContextOverrideReturn {
  projectId: string;
  branchId: string;
  forceIncludeMaterialIds: string[];
  forceExcludeMaterialIds: string[];
  receiptToken: string;
}
const KIND_FILTER_LABELS: KindFilterLabels = {
  all: '全部', world: '世界', character: '人物', outline: '大纲', requirement: '写作要求', other: '其他',
};

const filterLabel = (f: MaterialFilter): string => {
  switch (f) {
    case 'all': return KIND_FILTER_LABELS.all;
    case 'world': return KIND_FILTER_LABELS.world;
    case 'character': return KIND_FILTER_LABELS.character;
    case 'outline': return KIND_FILTER_LABELS.outline;
    case 'requirement': return KIND_FILTER_LABELS.requirement;
    default: return KIND_FILTER_LABELS.other;
  }
};

@Entry
@Component
struct NovelWorkspacePage {
  @StorageProp('themeEpoch') @Watch('clearStudioFeedback') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @State project: NovelProject | null = null;
  @State workspaceStatus: NovelWorkspaceStatus | null = null;
  @State branchSheetOpen: boolean = false;
  // 项目控制 sheet(iOS writingContext):模式/写作模型/本章计划/写作偏好/分支/导出
  @State controlSheetOpen: boolean = false;
  @State controlTab: number = 0;
  @State planThisChapter: string = '';
  @State planFuture: string = '';
  @State planPreferences: string = '';
  @State controlNotice: string = '';
  @State controlPlanReady: boolean = false;
  @State controlPlanLoading: boolean = false;
  @State controlPlanError: string = '';
  private controlPlanSession: number = 0;
  private controlPlanWriteRevision: number = 0;
  private planDraftStore: NovelPlanDraftStore = getNovelPlanDraftStore();
  private controlPlanOwner: string = '';
  private controlPlanBaseline: ControlPlanDraft = { thisChapterPlan: '', futurePlan: '', preferences: '' };
  private controlPlanDrafts: Map<string, ControlPlanDraft> = new Map();
  @State composerModelSheetOpen: boolean = false;
  @State composerModelSession: number = 0;
  @State composerModelError: string = '';
  @State novelModelDefaults: NovelModelDefaults = defaultNovelModelDefaults();
  @State chatModelLabel: string = '';
  @State modelOptionsReady: boolean = false;
  @State modelOptionsLoading: boolean = false;
  @State modelOptionsError: string = '';
  private modelOptionsLoadToken: number = 0;
  @State contextOverrides: NovelInjectionOverrides | null = null;
  private lastContextReceiptToken: string = '';
  @StorageProp('appBackgrounded') @Watch('studioBackgroundChanged') private studioBackgrounded: boolean = false;
  @StorageProp('reduceMotion') private reduceMotion: boolean = false;
  @State private studioPageVisible: boolean = true;
  @State private collectionStamp: number = 0;
  @State private studioTabDirection: number = 1;
  private studioVisibleCycle: number = 0;
  @State tab: number = 0;
  // 设定 tab 分类(iOS Compendium segmented):0 角色 / 1 世界观 / 2 剧情 / 3 更多
  @State settingTab: number = 0;
  @State private projectModelsVisible: boolean = false;
  @State fsTitle: string = '';
  @State fsContent: string = '';
  @State decTitle: string = '';
  @State decContent: string = '';
  @State errorMsg: string = '';
  @State consistencyMessage: string = '';
  @State auditRunning: boolean = false;
  @State auditMessage: string = '';
  @State auditReport: NovelAuditReport | null = null;
  private auditController: NovelAuditController | null = null;
  private auditBranchId: string = '';
  @State modelOptions: NovelModelOptionView[] = [];
  @State busy: boolean = false;
  @State draft: string = '';  // 聊天
  @State composeMode: NovelComposeMode = 'whole_chapter';
  @State sendStarting: boolean = false;
  @State composerRestoring: boolean = false;
  @State failedInput: string = '';
  @State failedMessages: UIMessage[] = [];
  @State ordinaryRecovery: NovelOrdinaryRunView | null = null;
  @State activeMessages: UIMessage[] = [];
  @State activeTextDeltasLive: boolean = false;
  @State activeTransport: StreamTransportState = 'unavailable';
  @State activeFrameSeen: boolean = false;
  @State followMode: FollowMode = 'idle';
  @State atBottomApprox: boolean = true;
  @State jumpButtonVisible: boolean = false;
  @State generationStatus: string = '';
  @State activeRunId: string = '';
  @State activeRunCreatedAt: number = 0;
  @State activeHistoryCount: number = 0;
  @State archiveOpen: boolean = false;
  @State archiveSummary: string = '';
  @State archiveDecisions: string[] = [];
  @State archiveSourceCount: number = 0;
  @State archiveSourceRange: string = '';
  @State archiveDraftKey: string = '';
  @State archiveError: string = '';
  @State expandedArchiveIds: string[] = [];
  @State collectionFeedback: string = '';
  private collectionFeedbackToken: number = 0;
  @State adoptionOpen: boolean = false;
  @State adoptionKind: NovelMaterialKind = 'world';
  @State adoptionTitle: string = '';
  @State adoptionBody: string = '';
  @State adoptionFields: NovelMaterialFields = {};
  @State adoptionTargets: NovelMaterialAdoptionSheetTarget[] = [];
  @State adoptionDraftKey: string = '';
  @State adoptionError: string = '';
  private archiveDraft: NovelDiscussionArchiveDraft | null = null;
  private adoptionBaseline: NovelMaterialAdoption | null = null;
  private adoptionTargetDigests: Map<string, string> = new Map();
  private adoptionSourceId: string = '';
  private adoptionIsSetting: boolean = false;

  async archiveDiscussion(): Promise<void> {
    const project: NovelProject | null = this.project;
    if (project === null || this.busy || this.hasBlockingNovelRun()) return;
    const messages: NovelMessage[] = eligibleNovelDiscussion(project);
    if (messages.length === 0) {
      promptAction.showToast({ message: '没有可归档的新增讨论内容', duration: 1800 });
      return;
    }
    this.busy = true;
    this.errorMsg = '';
    const branchId: string = this.workspaceStatus?.activeBranchId ?? '';
    try {
      const draft: NovelDiscussionArchiveDraft = await getNovelCreation().prepareDiscussionArchive(
        this.projectId, messages.map((message: NovelMessage): string => message.id));
      if (!this.pageAlive || (this.workspaceStatus?.activeBranchId ?? '') !== branchId) return;
      this.archiveDraft = draft;
      this.archiveSummary = draft.summary;
      this.archiveDecisions = draft.decisions.slice();
      this.archiveSourceCount = draft.sourceMessageIds.length;
      const archivedMessages: NovelMessage[] = messages.filter((message: NovelMessage): boolean =>
        draft.sourceMessageIds.includes(message.id));
      this.archiveSourceRange = `从「${archivedMessages[0].content.substring(0, 24)}」至「${archivedMessages[archivedMessages.length - 1].content.substring(0, 24)}」`;
      this.archiveDraftKey = `${draft.sourceDigest}-${Date.now()}`;
      this.archiveError = '';
      this.archiveOpen = true;
    } catch (e) {
      if (this.pageAlive) this.errorMsg = e instanceof Error ? e.message : String(e);
    } finally {
      this.busy = false;
    }
  }

  private async confirmArchive(summary: string, selectedIndexes: number[]): Promise<void> {
    if (this.busy || this.archiveDraft === null) return;
    this.busy = true;
    this.archiveError = '';
    try {
      await getNovelCreation().confirmDiscussionArchive(this.projectId, this.archiveDraft, summary, selectedIndexes);
      this.archiveOpen = false;
      this.archiveDraft = null;
      await this.reload();
      if (this.pageAlive) promptAction.showToast({ message: '讨论已归档，原文保留在创作记录中', duration: 1800 });
    } catch (e) {
      if (this.pageAlive) this.archiveError = e instanceof Error ? e.message : String(e);
    } finally {
      this.busy = false;
    }
  }

  private cancelArchive(): void {
    if (this.busy) return;
    this.archiveOpen = false;
    this.archiveDraft = null;
  }

  // N2:章节版本历史 sheet
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @State pendingUserText: string = '';
  @State collectMessageId: string = '';
  @State collectTitle: string = '';
  // 四段式收录(iOS NovelCollectCandidateSheet):段落勾选 → 收录前编辑 → 位置
  @State collectParas: string[] = [];
  @State collectSelected: boolean[] = [];
  @State collectDraft: string = '';
  @State collectManuallyEdited: boolean = false;
  @State collectMode: number = 0;  // 0 新开 / 1 并入 / 2 替换
  @State collectChapterId: string = '';
  // 章节
  @State chapterEditorOpen: boolean = false;
  @State editingChapterId: string = '';
  @State chapterTitle: string = '';
  @State chapterContent: string = '';
  // 素材
  @State expandedMaterialId: string = '';
  @State materialEditorOpen: boolean = false;
  @State editingMaterialId: string = '';
  @State materialEditorDraft: NovelMaterialEditorDraft = emptyMaterialEditorDraft();
  @State materialEditorKey: string = '';
  @State materialCloseRequest: number = 0;
  private materialEditorCas: NovelWorkspaceCas | null = null;
  private materialOpenToken: number = 0;
  private materialCreatedAt: number = 0;
  @State polishCreateOpen: boolean = false;
  @State polishJob: DurablePolishJob | null = null;
  @State polishBusy: boolean = false;
  @State ghostwriteJob: DurableGhostwriteJob | null = null;
  @State ghostwriteBriefOpen: boolean = false;
  @State ghostwriteBriefKey: string = '';
  @State ghostwriteBriefCloseRequest: number = 0;
  @State ghostwriteBriefError: string = '';
  @State ghostwriteBriefDetail: string = '';
  private ghostwriteBriefOpenToken: number = 0;
  private ghostwriteBriefCas: NovelWorkspaceCas | null = null;
  private ghostwriteBriefJobId: string = '';
  private ghostwriteBriefCandidateDigest: string = '';
  @State auditRepairBusy: boolean = false;
  private auditRepairToken: number = 0;

  @State ghostwriteBusy: boolean = false;
  @State ghostwritePreview: NovelGhostwriteStartPreview | null = null;
  @State ghostwritePreviewError: string = '';
  @State ghostwriteReport: NovelGhostwriteReport | null = null;
  @State singlePolishChapterId: string = '';
  @State polishStartError: string = '';
  @State activeQuickStart: boolean = false;
  @State private activeRunKind: NovelRunKind | null = null;
  @State private completedPreview: UIMessage[] = [];
  @State failedQuickStart: boolean = false;
  @State ghostwriteModelAvailable: boolean = false;
  @State ghostwriteModelIssue: string = '正在检查模型配置…';

  private projectId: string = '';
  private currentUnsub: (() => void) | null = null;
  private chatScroller: Scroller = new Scroller();
  private pageAlive: boolean = false;
  private reloadToken: number = 0;
  private initialScrollArmed: boolean = false;
  private novelScrollToken: number = 0;
  private nativeFollow: NativeTimelineFollow | null = null;
  private novelViewportHeight: number = 0;
  private novelLastVisibleIndex: number = -1;
  private novelUserScrolling: boolean = false;
  private userTouchActive: boolean = false;
  private recoveryAttemptedToolCalls: Set<string> = new Set();
  private ghostwritePollToken: number = 0;
  private polishPollToken: number = 0;
  private requestedBranchId: string = '';
  private requestedPolishJobId: string = '';
  private composerDraftStore: NovelComposerDraftStore = getNovelComposerDraftStore();
  private composerDraftOwner: string = '';
  private composerRevision: number = 0;
  private composerSaveToken: number = 0;
  private failedInputMode: NovelComposeMode = 'whole_chapter';
  @State failedInputRunKind: NovelRunKind | null = null;
  private collectOriginalTitle: string = '';
  private collectOriginalDraft: string = '';
  private collectOriginalMode: number = 0;
  private collectOriginalChapterId: string = '';
  private collectDraftController: TextAreaController = new TextAreaController();
  private collectTitleController: TextInputController = new TextInputController();
  private chapterTitleController: TextInputController = new TextInputController();
  private chapterContentController: TextAreaController = new TextAreaController();
  // F38:编辑器脏检查基线(打开时快照;返回时对比,脏则确认放弃)
  private chapterOriginalTitle: string = '';
  private chapterOriginalContent: string = '';
  private previousKeyboardAvoidMode: KeyboardAvoidMode = KeyboardAvoidMode.OFFSET;
  private keyboardAvoidanceActive: boolean = false;

  aboutToAppear(): void {
    this.enableKeyboardAvoidance();
    this.pageAlive = true;
    this.studioPageVisible = true;
    const params = router.getParams() as Record<string, string>;
    this.projectId = params ? (params['projectId'] ?? '') : '';
    this.requestedBranchId = params ? (params['branchId'] ?? '') : '';
    this.requestedPolishJobId = params ? (params['jobId'] ?? '') : '';
    // 列表页「快速开始」创建的种子:项目首载且空消息时自动发起 quick_start
    this.pendingQuickStartSeed = params ? (params['quickStartSeed'] ?? '') : '';
    if (params && params['tab'] === 'chapters') this.tab = 1;
    this.reload();
    this.loadProjectModelOptions();
  }

  private pendingQuickStartSeed: string = '';

  private maybeFireQuickStart(project: NovelProject, activeRun: NovelActiveRun | null): void {
    if (this.pendingQuickStartSeed.length === 0) return;
    if (project.messages.length > 0 || activeRun !== null) {
      this.pendingQuickStartSeed = '';
      return;
    }
    const seed: string = this.pendingQuickStartSeed;
    this.pendingQuickStartSeed = '';
    this.setComposerDraft(seed);
    this.send('quick_start');
  }

  // 阅读器 push 返回时刷新(章节编辑/版本恢复/删除在子页完成)
  private pageHiddenOnce: boolean = false;

  onPageHide(): void {
    this.closeControlSheet();
    this.studioPageVisible = false;
    if (this.composerModelSheetOpen) this.closeComposerModelSheet(this.composerModelSession, true);
    this.modelOptionsLoadToken++;
    this.clearStudioFeedback();
    this.ghostwriteBriefOpenToken++;
    this.auditRepairToken++;
    if (this.auditRepairBusy) this.cancelAuditRepair();
    this.materialOpenToken++;
    this.flushComposerDraft();
    this.pageHiddenOnce = true;
    this.auditController?.cancel();
    this.restoreKeyboardAvoidance();
  }

  onPageShow(): void {
    this.studioPageVisible = true;
    this.enableKeyboardAvoidance();
    if (this.pageHiddenOnce) {
      this.pageHiddenOnce = false;
      this.loadProjectModelOptions();
      this.reload().then((): void => { if (this.pageAlive) this.consumeContextOverrides(); });
    }
  }

  aboutToDisappear(): void {
    this.closeControlSheet();
    this.studioPageVisible = false;
    if (this.composerModelSheetOpen) this.closeComposerModelSheet(this.composerModelSession, true);
    this.modelOptionsLoadToken++;
    this.clearStudioFeedback();
    this.ghostwriteBriefOpenToken++;
    this.auditRepairToken++;
    if (this.auditRepairBusy) this.cancelAuditRepair();
    this.materialOpenToken++;
    this.flushComposerDraft();
    this.restoreKeyboardAvoidance();
    this.pageAlive = false;
    this.auditController?.cancel();
    this.auditController = null;
    this.reloadToken++;
    this.novelScrollToken++;
    this.nativeFollow?.cancel();
    this.ghostwritePollToken++;
    this.polishPollToken++;
    if (this.currentUnsub !== null) {
      const unsub: (() => void) = this.currentUnsub;
      this.currentUnsub = null;
      unsub(); // 终结即退订,emitter 不再持有页面闭包
    }
  }

  // The impression belongs to the visible collection action, never to an AI run event.
  private clearStudioFeedback(): void {
    this.studioVisibleCycle++;
    this.collectionStamp = 0;
  }

  private studioBackgroundChanged(): void {
    if (this.studioBackgrounded) {
      this.retainControlPlanDraft();
      this.clearStudioFeedback();
      if (this.composerModelSheetOpen) this.closeComposerModelSheet(this.composerModelSession, true);
    }
  }

  private showCollectionStamp(projectId: string, branchId: string, visibleCycle: number): void {
    if (getProductKind() !== 'novel' || !this.pageAlive || !this.studioPageVisible || this.studioBackgrounded ||
      this.studioVisibleCycle !== visibleCycle || this.projectId !== projectId ||
      this.workspaceStatus?.activeBranchId !== branchId || this.tab !== 1) return;
    this.collectionStamp++;
  }

  private selectStudioTab(idx: number): void {
    if (idx === this.tab) return;
    this.studioTabDirection = idx > this.tab ? 1 : -1;
    if (idx !== 1) this.clearStudioFeedback();
    animateTo({ duration: this.studioPaneDuration(), curve: Curve.EaseInOut }, (): void => { this.tab = idx; });
  }

  private studioPaneDuration(): number {
    if (!this.studioPageVisible || this.studioBackgrounded) return 0;
    return this.reduceMotion ? 100 : 180;
  }

  private workspacePaneTransition(): TransitionEffect {
    if (getProductKind() !== 'novel') {
      return TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: 10 }))
        .animation({ duration: MOTION_ENTER, curve: Curve.EaseOut });
    }
    return TransitionEffect.OPACITY.animation({ duration: this.studioPaneDuration(), curve: Curve.EaseInOut });
  }

  private enableKeyboardAvoidance(): void {
    if (this.keyboardAvoidanceActive) return;
    this.previousKeyboardAvoidMode = this.getUIContext().getKeyboardAvoidMode();
    this.getUIContext().setKeyboardAvoidMode(KeyboardAvoidMode.RESIZE);
    this.keyboardAvoidanceActive = true;
  }

  private restoreKeyboardAvoidance(): void {
    if (!this.keyboardAvoidanceActive) return;
    this.getUIContext().setKeyboardAvoidMode(this.previousKeyboardAvoidMode);
    this.keyboardAvoidanceActive = false;
  }

  // F38:系统返回优先消费自绘 overlay,避免直接退出页面(编辑脏数据先确认)。
  //   系统 sheet(AlertDialog/actionMenu)由平台自行消费返回,本页不重复拦截。
  onBackPress(): boolean {
    return this.closeTopOverlay();
  }

  // 顶栏返回与系统返回同路径,保证层级一致
  private handleBack(): void {
    if (!this.closeTopOverlay()) router.back();
  }

  // 关闭最上层自绘 overlay;有关闭动作返回 true(已消费返回),无 overlay 返回 false。
  private closeTopOverlay(): boolean {
    // 渲染顺序:后面的 child 更靠上,故按逆序检查
    if (this.adoptionOpen) {
      this.cancelAdoption();
      return true;
    }
    if (this.archiveOpen) {
      this.cancelArchive();
      return true;
    }
    if (this.composerModelSheetOpen) {
      this.closeComposerModelSheet(this.composerModelSession);
      return true;
    }
    if (this.ghostwriteBriefOpen) {
      this.ghostwriteBriefCloseRequest++;
      return true;
    }
    if (this.controlSheetOpen) {
      this.closeControlSheet();
      return true;
    }
    if (this.ghostwritePreview !== null) {
      if (!this.ghostwriteBusy) this.ghostwritePreview = null;
      return true;
    }
    if (this.polishCreateOpen) {
      if (!this.polishBusy) this.polishCreateOpen = false;
      return true;
    }
    if (this.branchSheetOpen) {
      if (!this.busy) this.branchSheetOpen = false;
      return true;
    }
    if (this.materialEditorOpen) {
      this.confirmDiscardMaterial();
      return true;
    }
    if (this.chapterEditorOpen) {
      this.confirmDiscardChapter();
      return true;
    }
    if (this.collectMessageId.length > 0) {
      this.confirmDiscardCollect();
      return true;
    }
    return false;
  }

  private chapterDirty(): boolean {
    return this.chapterTitle !== this.chapterOriginalTitle
      || this.chapterContent !== this.chapterOriginalContent;
  }

  private confirmDiscardChapter(): void {
    if (this.busy) return;
    this.chapterTitleController.stopEditing();
    this.chapterContentController.stopEditing();
    setTimeout((): void => { this.confirmDiscardCommittedChapter(); }, 0);
  }

  private confirmDiscardCommittedChapter(): void {
    if (!this.pageAlive || !this.chapterEditorOpen || this.busy) return;
    if (!this.chapterDirty()) {
      this.chapterEditorOpen = false;
      return;
    }
    AlertDialog.show({
      title: '放弃编辑？',
      message: '未保存的章节内容将丢失。',
      primaryButton: { value: '继续编辑', action: (): void => {} },
      secondaryButton: {
        value: '放弃',
        fontColor: ERROR,
        action: (): void => { if (!this.busy) this.chapterEditorOpen = false; },
      },
    });
  }

  private confirmDiscardMaterial(): void {
    if (!this.busy) this.materialCloseRequest++;
  }

  async reload(): Promise<void> {
    if (this.projectId.length === 0) return;
    const token: number = ++this.reloadToken;
    const projectId: string = this.projectId;
    try {
      const creation = getNovelCreation();
      let project: NovelProject = await creation.open(projectId);
      let activeRun: NovelActiveRun | null = creation.activeRun(projectId);
      let status: NovelWorkspaceStatus = await creation.workspaceStatus(projectId);
      if (this.requestedBranchId.length > 0 && status.activeBranchId !== this.requestedBranchId) {
        if (activeRun !== null) throw new Error('生成进行中，暂不能切换分支。');
        await creation.switchBranch(projectId, this.requestedBranchId);
        project = await creation.open(projectId);
        activeRun = creation.activeRun(projectId);
        status = await creation.workspaceStatus(projectId);
      }
      const recovery: NovelOrdinaryRunView = await creation.readOrdinaryRun(projectId);
      const ghostwrite: DurableGhostwriteJob | null = await creation.ghostwriteJob(projectId);
      const ghostwriteReport: NovelGhostwriteReport | null = await creation.latestGhostwriteReport(projectId);
      let polish: DurablePolishJob | null = await creation.polishJob(
        projectId, this.requestedPolishJobId);
      let polishNotice: string = '';
      if (this.requestedPolishJobId.length > 0) {
        const current: DurablePolishJob | null = await creation.polishJob(projectId);
        if (current !== null && current.jobId !== polish?.jobId &&
          current.stage !== 'completed' && current.stage !== 'cancelled') {
          polish = current;
          this.requestedPolishJobId = '';
          polishNotice = '通知对应任务已结束，当前分支有新的润色任务，已显示当前任务。';
        }
      }
      if (polish !== null && polish.branchId !== status.activeBranchId) {
        throw new Error('润色任务不属于当前分支。');
      }
      let modelAvailable: boolean = true;
      let modelIssue: string = '';
      try {
        await creation.validateGhostwriteModels(projectId, project);
      } catch (error) {
        modelAvailable = false;
        modelIssue = error instanceof Error ? error.message : String(error);
      }
      if (this.pageAlive && token === this.reloadToken) {
        await this.restoreComposerDraft(composerOwner(projectId, status.activeBranchId));
        if (!this.pageAlive || token !== this.reloadToken) return;
        if (this.workspaceStatus?.activeBranchId !== status.activeBranchId) {
          this.expandedArchiveIds = [];
          this.collectionFeedback = '';
          this.collectionFeedbackToken++;
          this.clearStudioFeedback();
        }
        const workspaceChanged: boolean = this.project === null ||
          this.workspaceStatus?.activeBranchId !== status.activeBranchId;
        this.project = project;
        this.completedPreview = [];
        this.workspaceStatus = status;
        this.ordinaryRecovery = recovery.record?.branchId === status.activeBranchId ? recovery : null;
        if (this.contextOverrides !== null && this.contextOverrides.branchId !== status.activeBranchId) this.contextOverrides = null;
        this.requestedBranchId = '';
        this.ghostwriteJob = ghostwrite;
        this.ghostwriteReport = ghostwriteReport;
        this.polishJob = polish;
        if (ghostwrite !== null && getProductKind() !== 'novel') this.settingTab = 3;
        this.ghostwriteModelAvailable = modelAvailable;
        this.ghostwriteModelIssue = modelIssue;
        // 等 List 收到新 rows 的可见范围回调再落底；Promise 返回后的
        // setTimeout 仍可能早于 ArkUI 完成 ForEach 布局。
        if (workspaceChanged) this.initialScrollArmed = project.messages.length > 0 || activeRun !== null;
        if (activeRun !== null &&
          (this.activeRunId !== activeRun.run.id || this.currentUnsub === null)) {
          this.attachNovelRun(
            activeRun.run, activeRun.historyCount, activeRun.userText,
            activeRun.startedAt, '正在继续离页前的生成…', activeRun.runKind === 'quick_start', activeRun.runKind ?? null);
        } else if (activeRun === null && this.currentUnsub === null && this.activeRunId.length > 0) {
          this.clearNovelRunUi();
        }
        this.scheduleRecoverableTool(project);
        this.scheduleGhostwritePoll();
        this.schedulePolishPoll();
        this.maybeFireQuickStart(project, activeRun);
        if (polishNotice.length > 0) {
          promptAction.showToast({ message: polishNotice, duration: 2400 });
        }
      }
    } catch (e) {
      if (this.pageAlive && token === this.reloadToken) {
        this.errorMsg = `项目无法打开：${String(e)}`;
      }
    }
  }

  private ghostwriteStageRuns(stage: GhostwriteStage): boolean {
    return stage !== 'paused' && stage !== 'waiting_user' && stage !== 'failed' &&
      stage !== 'cancelled' && stage !== 'completed';
  }

  private scheduleGhostwritePoll(): void {
    const job: DurableGhostwriteJob | null = this.ghostwriteJob;
    if (job === null || !this.ghostwriteStageRuns(job.stage) || !this.pageAlive) return;
    const token: number = ++this.ghostwritePollToken;
    setTimeout((): void => {
      if (!this.pageAlive || token !== this.ghostwritePollToken) return;
      void this.pollGhostwrite(token);
    }, 1200);
  }

  private async pollGhostwrite(token: number): Promise<void> {
    try {
      const previous: DurableGhostwriteJob | null = this.ghostwriteJob;
      const next: DurableGhostwriteJob | null = await getNovelCreation().ghostwriteJob(this.projectId);
      if (!this.pageAlive || token !== this.ghostwritePollToken) return;
      this.ghostwriteJob = next;
      if (next === null && previous !== null) {
        this.ghostwriteReport = await getNovelCreation().latestGhostwriteReport(this.projectId);
      }
      if (next !== null && getProductKind() !== 'novel') this.settingTab = 3;
      const previousProgress: number = previous?.progress.length ?? 0;
      const nextProgress: number = next?.progress.length ?? 0;
      if (previousProgress !== nextProgress || (previous !== null && next === null)) {
        const project: NovelProject = await getNovelCreation().open(this.projectId);
        const status: NovelWorkspaceStatus = await getNovelCreation().workspaceStatus(this.projectId);
        if (!this.pageAlive || token !== this.ghostwritePollToken) return;
        this.project = project;
        this.workspaceStatus = status;
      }
      this.scheduleGhostwritePoll();
    } catch (error) {
      if (this.pageAlive && token === this.ghostwritePollToken) {
        this.errorMsg = `代笔状态刷新失败：${String(error)}`;
      }
    }
  }

  private polishStageRuns(stage: PolishStage): boolean {
    return stage !== 'waiting_system' && stage !== 'paused' && stage !== 'failed' &&
      stage !== 'cancelled' && stage !== 'completed';
  }

  private polishRuns(): boolean {
    return this.polishJob !== null && this.polishStageRuns(this.polishJob.stage);
  }

  private schedulePolishPoll(): void {
    const job: DurablePolishJob | null = this.polishJob;
    if (job === null || !this.polishStageRuns(job.stage) || !this.pageAlive) return;
    const token: number = ++this.polishPollToken;
    setTimeout((): void => {
      if (!this.pageAlive || token !== this.polishPollToken) return;
      void this.pollPolish(token);
    }, 1200);
  }

  private async pollPolish(token: number): Promise<void> {
    try {
      const previous: DurablePolishJob | null = this.polishJob;
      const next: DurablePolishJob | null = await getNovelCreation().polishJob(
        this.projectId, this.requestedPolishJobId);
      if (!this.pageAlive || token !== this.polishPollToken) return;
      this.polishJob = next;
      const previousProgress: number = previous?.progress.length ?? 0;
      const nextProgress: number = next?.progress.length ?? 0;
      if (previousProgress !== nextProgress || previous?.stage !== next?.stage) {
        this.project = await getNovelCreation().open(this.projectId);
        this.workspaceStatus = await getNovelCreation().workspaceStatus(this.projectId);
        if (!this.pageAlive || token !== this.polishPollToken) return;
      }
      this.schedulePolishPoll();
    } catch (error) {
      if (this.pageAlive && token === this.polishPollToken) {
        this.errorMsg = `润色状态刷新失败：${String(error)}`;
      }
    }
  }

  private scheduleRecoverableTool(project: NovelProject): void {
    for (let i: number = project.messages.length - 1; i >= 0; i--) {
      const parts: UIMessagePart[] = novelMessageUi(project.messages[i]).parts;
      for (let p: number = parts.length - 1; p >= 0; p--) {
        const part: UIMessagePart = parts[p];
        if (part.type !== 'tool' || part.output.length > 0 ||
          this.recoveryAttemptedToolCalls.has(part.toolCallId)) continue;
        let verdict: NovelToolContinuationVerdict | null = null;
        if (part.approvalState.type === 'approved') verdict = { kind: 'approved' };
        else if (part.approvalState.type === 'answered') {
          verdict = { kind: 'answered', answer: part.approvalState.answer };
        } else if (part.approvalState.type === 'denied') {
          verdict = { kind: 'denied', reason: part.approvalState.reason };
        }
        if (verdict === null) continue;
        this.recoveryAttemptedToolCalls.add(part.toolCallId);
        const toolCallId: string = part.toolCallId;
        setTimeout((): void => {
          if (this.pageAlive && !this.hasBlockingNovelRun()) {
            this.continueNovelTool(toolCallId, verdict as NovelToolContinuationVerdict);
          }
        }, 0);
        return;
      }
    }
  }

  private async loadProjectModelOptions(): Promise<void> {
    const token: number = ++this.modelOptionsLoadToken;
    this.modelOptionsLoading = true;
    this.modelOptionsReady = false;
    this.modelOptionsError = '';
    try {
      const options: NovelModelOption[] = await loadNovelModelOptions();
      const defaults: NovelModelDefaults = getProductKind() === 'novel'
        ? await loadNovelModelDefaults() : defaultNovelModelDefaults();
      const chatLabel: string = getProductKind() === 'novel' ? await describeChatModel() : '';
      if (!this.pageAlive || token !== this.modelOptionsLoadToken) return;
      this.modelOptions = options.map((option: NovelModelOption): NovelModelOptionView => ({
        providerId: option.providerId,
        modelId: option.modelId,
        label: option.label,
        providerLabel: option.providerLabel,
        available: option.available,
      }));
      this.novelModelDefaults = defaults;
      this.chatModelLabel = chatLabel;
      this.modelOptionsReady = true;
    } catch (error) {
      if (this.pageAlive && token === this.modelOptionsLoadToken) {
        this.modelOptionsError = `模型列表加载失败：${String(error)}`;
        this.errorMsg = this.modelOptionsError;
      }
    } finally {
      if (this.pageAlive && token === this.modelOptionsLoadToken) this.modelOptionsLoading = false;
    }
  }

  private ghostwriteModelsReady(): boolean {
    return this.ghostwriteModelAvailable;
  }

  private ghostwriteModelReason(): string {
    return this.ghostwriteModelIssue.length > 0
      ? this.ghostwriteModelIssue : '写作或审稿模型当前不可用。';
  }

  private ghostwritePlanReady(): boolean {
    return (this.project?.branchSettings.thisChapterPlan.trim().length ?? 0) > 0;
  }

  private polishLocksWorkspace(): boolean {
    return this.polishJob !== null && this.polishJob.stage !== 'completed' &&
      this.polishJob.stage !== 'cancelled';
  }

  private novelJobLocksWorkspace(): boolean {
    return this.ghostwriteJob !== null || this.polishLocksWorkspace();
  }

  private ghostwriteRuns(): boolean {
    return this.ghostwriteJob !== null && this.ghostwriteStageRuns(this.ghostwriteJob.stage);
  }

  private async startGhostwrite(targetChapterCount: number): Promise<void> {
    if (this.ghostwriteBusy || this.busy || this.hasBlockingNovelRun()) return;
    this.ghostwriteBusy = true;
    this.ghostwritePreviewError = '';
    try {
      const preview: NovelGhostwriteStartPreview = await getNovelCreation().prepareGhostwriteStart(this.projectId, targetChapterCount);
      if (this.pageAlive) {
        this.controlSheetOpen = false;
        this.ghostwritePreview = preview;
      }
    } catch (error) { this.showWorkspaceOperationError('准备代笔计划失败', error); }
    finally { this.ghostwriteBusy = false; }
  }

  private ghostwriteAuthorPlanPreview(preview: NovelGhostwriteStartPreview | null = this.ghostwritePreview): string {
    const content: string = preview?.planContent ?? '';
    const settings: NovelBranchSettings | undefined = this.project?.branchSettings;
    const contract: NovelChapterContract | undefined = settings?.chapterContract;
    if (settings !== undefined && contract !== undefined && contract.status === 'confirmed' && content === confirmedChapterPlanText(settings)) {
      return `本章计划已确认\n\n${chapterContractMarkdown(contract)}`;
    }
    return content;
  }

  private async confirmGhostwriteStart(): Promise<void> {
    const preview: NovelGhostwriteStartPreview | null = this.ghostwritePreview;
    if (preview === null || this.ghostwriteBusy) return;
    const targetChapterCount: number = preview.targetChapterCount;
    if (this.busy || this.hasBlockingNovelRun()) return;
    this.ghostwriteBusy = true;
    try {
      this.ghostwriteJob = await getNovelCreation().startGhostwrite(this.projectId, targetChapterCount, preview);
      this.ghostwritePreview = null;
      if (getProductKind() !== 'novel') this.settingTab = 3;
      this.errorMsg = '';
      this.scheduleGhostwritePoll();
    } catch (error) {
      this.ghostwritePreviewError = String(error);
    } finally {
      this.ghostwriteBusy = false;
    }
  }

  private async openGhostwriteBrief(): Promise<void> {
    const job: DurableGhostwriteJob | null = this.ghostwriteJob;
    if (job === null || this.ghostwriteBusy || (job.stage !== 'paused' && job.stage !== 'failed')) return;
    const token: number = ++this.ghostwriteBriefOpenToken;
    try {
      const snapshot: NovelWorkspaceSnapshot = await getNovelCreation().readWorkspaceSnapshot(this.projectId);
      if (!this.pageAlive || token !== this.ghostwriteBriefOpenToken || this.ghostwriteJob?.jobId !== job.jobId) return;
      if (snapshot.status.activeBranchId !== job.branchId) throw new Error('分支已变化，请重新打开代笔任务');
      this.ghostwriteBriefDetail = `分支“${snapshot.status.activeBranchName}” · 第 ${job.currentChapterOrdinal} 章。保留当前候选作为来源，按本次要求生成下一稿；确认收录前正文保持原样。`;
      this.ghostwriteBriefCas = snapshot.status.cas;
      this.ghostwriteBriefJobId = job.jobId;
      this.ghostwriteBriefCandidateDigest = job.candidate?.digest ?? '';
      this.ghostwriteBriefKey = `${job.jobId}:${Date.now()}`;
      this.ghostwriteBriefError = '';
      this.controlSheetOpen = false;
      this.ghostwriteBriefOpen = true;
    } catch (error) { this.showWorkspaceOperationError('无法打开修改要求', error); }
  }

  private async submitGhostwriteBrief(brief: string): Promise<void> {
    if (this.ghostwriteBusy || this.ghostwriteBriefCas === null) return;
    this.ghostwriteBusy = true; this.ghostwriteBriefError = '';
    try {
      this.ghostwriteJob = await getNovelCreation().reviseGhostwriteWithBrief(this.projectId,
        this.ghostwriteBriefJobId, brief, this.ghostwriteBriefCas, this.ghostwriteBriefCandidateDigest);
      if (!this.pageAlive) return;
      this.ghostwriteBriefOpen = false;
      this.ghostwritePollToken++;
      await this.reload();
      this.scheduleGhostwritePoll();
    } catch (error) { if (this.pageAlive) this.ghostwriteBriefError = String(error); }
    finally { if (this.pageAlive) this.ghostwriteBusy = false; }
  }

  private async pauseGhostwrite(): Promise<void> {
    const job: DurableGhostwriteJob | null = this.ghostwriteJob;
    if (this.ghostwriteBusy || job === null || !this.ghostwriteStageRuns(job.stage)) return;
    this.ghostwriteBusy = true;
    try {
      this.ghostwritePollToken++;
      this.ghostwriteJob = await getNovelCreation().pauseGhostwrite(this.projectId, job.jobId);
      this.errorMsg = '';
    } catch (error) {
      this.showWorkspaceOperationError('暂停代笔失败', error);
    } finally {
      this.ghostwriteBusy = false;
    }
  }

  private async resumeGhostwrite(): Promise<void> {
    const job: DurableGhostwriteJob | null = this.ghostwriteJob;
    if (this.ghostwriteBusy || job === null || job.stage !== 'paused') return;
    this.ghostwriteBusy = true;
    try {
      this.ghostwriteJob = await getNovelCreation().resumeGhostwrite(this.projectId, job.jobId);
      if (getProductKind() !== 'novel') this.settingTab = 3;
      this.errorMsg = '';
      this.scheduleGhostwritePoll();
    } catch (error) {
      this.showWorkspaceOperationError('继续代笔失败', error);
    } finally {
      this.ghostwriteBusy = false;
    }
  }

  private async retryGhostwrite(): Promise<void> {
    const job: DurableGhostwriteJob | null = this.ghostwriteJob;
    if (this.ghostwriteBusy || job === null || job.stage !== 'failed') return;
    this.ghostwriteBusy = true;
    try {
      this.ghostwriteJob = await getNovelCreation().retryGhostwrite(this.projectId, job.jobId);
      if (getProductKind() !== 'novel') this.settingTab = 3;
      this.errorMsg = '';
      this.scheduleGhostwritePoll();
    } catch (error) {
      this.showWorkspaceOperationError('重试代笔失败', error);
    } finally {
      this.ghostwriteBusy = false;
    }
  }

  private async cancelGhostwrite(): Promise<void> {
    const job: DurableGhostwriteJob | null = this.ghostwriteJob;
    if (this.ghostwriteBusy || job === null) return;
    this.ghostwriteBusy = true;
    try {
      this.ghostwritePollToken++;
      await getNovelCreation().cancelGhostwrite(this.projectId, job.jobId);
      this.ghostwriteJob = null;
      this.ghostwriteReport = await getNovelCreation().latestGhostwriteReport(this.projectId);
      this.errorMsg = '';
      await this.reload();
    } catch (error) {
      this.showWorkspaceOperationError('取消代笔失败', error);
    } finally {
      this.ghostwriteBusy = false;
    }
  }

  private async saveNovelModelPolicy(policy: NovelModelPolicy): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    try {
      await getNovelCreation().setModelPolicy(this.projectId, policy);
      this.errorMsg = '';
      await this.reload();
    } catch (error) {
      this.showWorkspaceOperationError('保存模型职责失败', error);
    } finally {
      this.busy = false;
    }
  }

  private async saveNovelBranchSettings(settings: NovelBranchSettings): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    try {
      await getNovelCreation().setBranchSettings(this.projectId, settings);
      this.errorMsg = '';
      await this.reload();
    } catch (error) {
      this.showWorkspaceOperationError('保存分支设定失败', error);
    } finally {
      this.busy = false;
    }
  }

  private async runConsistencyCheck(): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    try {
      const report: NovelConsistencyReport = await getNovelCreation().checkConsistency(this.projectId);
      this.consistencyMessage = report.ok
        ? '当前分支正文、剧情与提案状态一致。'
        : report.issues.map(issue => issue.message).join(' ');
      this.errorMsg = '';
    } catch (error) {
      this.showWorkspaceOperationError('一致性检查失败', error);
    } finally {
      this.busy = false;
    }
  }

  private async runLlmContinuityAudit(): Promise<void> {
    if (this.auditRunning || this.busy) return;
    this.auditBranchId = this.workspaceStatus?.activeBranchId ?? '';
    this.auditRunning = true;
    this.auditMessage = '正在准备全文审校…';
    this.auditReport = null;
    const control: NovelAuditController = new NovelAuditController((progress: NovelAuditProgress): void => {
      if (this.pageAlive && this.auditController === control) {
        this.auditMessage = `已检查 ${progress.completedBlocks}/${progress.totalBlocks} 块 · ${progress.checkedChars}/${progress.totalChars} 字符`;
      }
    });
    this.auditController = control;
    try {
      const report: NovelAuditReport = await getNovelCreation().runContinuityAudit(this.projectId, control);
      if (!this.pageAlive || this.auditController !== control) return;
      this.auditReport = report;
      this.auditMessage = '';
      this.errorMsg = '';
    } catch (error) {
      if (this.pageAlive) {
        this.auditMessage = '';
        this.showWorkspaceOperationError('连续性审校失败', error);
      }
    } finally {
      if (this.auditController === control) {
        this.auditRunning = false;
        this.auditController = null;
      }
    }
  }

  private auditReportStale(): boolean {
    const report: NovelAuditReport | null = this.auditReport;
    const project: NovelProject | null = this.project;
    if (report === null || project === null) return false;
    if (this.workspaceStatus?.activeBranchId !== this.auditBranchId) return true;
    return report.blocks.some((block: NovelAuditBlock): boolean => {
      const chapter: NovelChapter | undefined = project.chapters.find((item: NovelChapter): boolean => item.id === block.chapterId);
      return chapter === undefined || chapter.discarded || defaultGhostwriteDigest(chapter.content) !== block.sourceDigest;
    });
  }

  private async openAuditEvidence(issue: NovelAuditIssue | NovelAuditReference, targetChapterId: string = ''): Promise<void> {
    const chapterId: string = issue.chapterId;
    if (chapterId.length === 0) return;
    const token: number = this.auditRepairToken;
    try {
      const snapshot: NovelWorkspaceSnapshot = await getNovelCreation().readWorkspaceSnapshot(this.projectId);
      if (!this.pageAlive || this.pageHiddenOnce || token !== this.auditRepairToken) return;
      const status: NovelWorkspaceStatus = snapshot.status;
      const project: NovelProject = snapshot.project;
      if (targetChapterId.length > 0) verifyAuditCanonicalReferences(project.chapters, targetChapterId, [issue]);
      const chapter: NovelChapter | undefined = project.chapters.find((item: NovelChapter): boolean => item.id === chapterId);
      if (status.activeBranchId !== this.auditBranchId || status.cas.branchId !== this.auditBranchId || chapter === undefined || chapter.discarded ||
        defaultGhostwriteDigest(chapter.content) !== issue.sourceDigest || !Number.isInteger(issue.start) || !Number.isInteger(issue.end) ||
        issue.start < 0 || issue.end <= issue.start || issue.end > chapter.content.length || issue.quote.length === 0 ||
        chapter.content.slice(issue.start, issue.end) !== issue.quote) {
        this.errorMsg = '分支或正文已变化，这条证据来自旧稿。请重新审校后查看原文。';
        return;
      }
      await router.pushUrl({ url: 'pages/NovelChapterReaderPage', params: { projectId: this.projectId, chapterId,
        evidenceQuote: issue.quote, evidenceStart: issue.start, evidenceEnd: issue.end, evidenceDigest: issue.sourceDigest,
        evidenceBranchId: this.auditBranchId } });
    } catch (error) {
      if (this.pageAlive && !this.pageHiddenOnce && token === this.auditRepairToken) this.showWorkspaceOperationError('无法查看审校原文', error);
    }
  }

  private async repairAuditIssue(issue: NovelAuditIssue): Promise<void> {
    await this.repairAuditIssues([issue]);
  }

  private async repairAuditIssues(issues: NovelAuditIssue[]): Promise<void> {
    if (issues.length === 0 || this.auditRunning || this.auditRepairBusy || this.busy || this.hasBlockingNovelRun() || this.auditReportStale()) return;
    this.auditRepairBusy = true;
    const token: number = ++this.auditRepairToken;
    try {
      const proposal = await getNovelCreation().repairContinuityIssues(this.projectId, issues);
      if (!this.pageAlive || this.pageHiddenOnce || token !== this.auditRepairToken) return;
      await router.pushUrl({ url: 'pages/NovelOperationsPage', params: { projectId: this.projectId, proposalId: proposal.proposalId } });
    } catch (error) {
      if (this.pageAlive && !this.pageHiddenOnce && token === this.auditRepairToken) this.showWorkspaceOperationError('无法生成审校修复建议', error);
    } finally { if (this.pageAlive && token === this.auditRepairToken) this.auditRepairBusy = false; }
  }

  private async cancelAuditRepair(): Promise<void> {
    if (!this.auditRepairBusy) return;
    const token: number = ++this.auditRepairToken;
    try { await getNovelCreation().cancelContinuityRepair(this.projectId); }
    catch (error) {
      if (this.pageAlive && !this.pageHiddenOnce && token === this.auditRepairToken) this.showWorkspaceOperationError('无法取消修复建议', error);
    } finally { if (this.pageAlive && token === this.auditRepairToken) this.auditRepairBusy = false; }
  }

  private openProjectPage(url: string): void {
    if (this.busy || this.hasBlockingNovelRun()) return;
    this.controlSheetOpen = false;
    router.pushUrl({ url, params: { projectId: this.projectId } })
      .catch((error: Error): void => { if (this.pageAlive) this.errorMsg = String(error); });
  }

  private openPolishPage(): void {
    if (this.busy) return;
    this.controlSheetOpen = false;
    router.pushUrl({ url: 'pages/NovelPolishPage', params: { projectId: this.projectId } })
      .catch((error: Error): void => { if (this.pageAlive) this.errorMsg = String(error); });
  }

  private toggleDiscussionArchive(archiveId: string): void {
    if (!this.project?.discussionArchives.some((archive: NovelDiscussionArchive): boolean => archive.id === archiveId)) return;
    this.expandedArchiveIds = this.expandedArchiveIds.includes(archiveId)
      ? this.expandedArchiveIds.filter((id: string): boolean => id !== archiveId)
      : this.expandedArchiveIds.concat(archiveId);
  }

  private ordinaryRecoveryRecord(): NovelOrdinaryRun | null {
    const record: NovelOrdinaryRun | null = this.ordinaryRecovery?.record ?? null;
    return record !== null && record.status !== 'completed' && record.status !== 'waiting_user' &&
      record.branchId === this.workspaceStatus?.activeBranchId && !this.ordinaryRecovery?.active ? record : null;
  }

  private ordinaryPartialText(record: NovelOrdinaryRun): string {
    if (record.originalRequest.operation.kind === 'tool_continuation') {
      const current: UIMessage | undefined = ordinaryRunMessages(record).slice(ordinaryRunHistoryCount(record))
        .reverse().find((message: UIMessage): boolean => message.role === 'assistant');
      if (current === undefined) return '';
      const before: UIMessage | undefined = record.originalRequest.history.find((message: UIMessage): boolean => message.id === current.id);
      const text: string = latestAssistantText([current]);
      const oldText: string = before === undefined ? '' : latestAssistantText([before]);
      return text.startsWith(oldText) ? text.slice(oldText.length) : text;
    }
    const originalIds: string[] = record.originalRequest.history.map((message: UIMessage): string => message.id);
    return latestAssistantText(record.checkpointMessages.filter((message: UIMessage): boolean => !originalIds.includes(message.id)));
  }

  private ordinaryRecoveryDetail(record: NovelOrdinaryRun): string {
    if (record.originalRequest.operation.kind === 'tool_continuation') {
      return `${this.workspaceStatus?.activeBranchName ?? '当前分支'} · 工具续接\n继续已保存的回答与工具结果，不重新发起原讨论。重试会替换本次工具续接的部分内容；需要保留可先复制。`;
    }
    const mode: string = record.runKind === 'regenerate' ? '章节重生成'
      : record.runKind === 'polish' ? '章节润色' : record.runKind === 'quick_start' ? '快速开始'
        : record.mode === 'discuss' ? '讨论' : record.granularity === 'whole_chapter' ? '整章创作' : '片段创作';
    const source: NovelChapter | undefined = this.project?.chapters.find((chapter: NovelChapter): boolean => chapter.id === record.candidate?.sourceChapterId);
    return `${this.workspaceStatus?.activeBranchName ?? '当前分支'} · ${mode}${source === undefined ? '' : ` · ${source.title}`}\n使用保存的原请求、模型与上下文。重试不会将此前失败的部分内容作为新的写作输入。`;
  }

  private recoverOrdinaryRun(recordId: string, kind: 'retry' | 'resume'): void {
    const record: NovelOrdinaryRun | null = this.ordinaryRecoveryRecord();
    if (record === null || record.id !== recordId || this.busy || this.hasBlockingNovelRun()) return;
    if (kind === 'retry' ? !this.ordinaryRecovery?.retryAllowed : !this.ordinaryRecovery?.resumeAllowed) return;
    const run: NovelRun = kind === 'retry'
      ? getNovelCreation().retryOrdinaryRun(this.projectId, recordId)
      : getNovelCreation().resumeOrdinaryRun(this.projectId, recordId);
    const historyCount: number = kind === 'resume' || record.originalRequest.operation.kind === 'tool_continuation'
      ? ordinaryRunHistoryCount(record) : this.project?.messages.length ?? 0;
    this.attachNovelRun(run, historyCount, '', Date.now(),
      kind === 'retry' ? '正在按原请求重试…' : '正在继续原任务…', record.runKind === 'quick_start',
      record.runKind ?? (record.mode === 'discuss' ? 'discussion' : 'prose_whole_chapter'));
  }

  private copyOrdinaryPartial(recordId: string, text: string): void {
    if (this.ordinaryRecoveryRecord()?.id !== recordId || text.length === 0) return;
    try {
      pasteboard.getSystemPasteboard().setDataSync(pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, text));
      promptAction.showToast({ message: '已复制保存的部分内容', duration: 1400 });
    } catch (error) { this.showWorkspaceOperationError('复制失败', error); }
  }

  private confirmCloneCollected(message: NovelMessage): void {
    const project: NovelProject | null = this.project;
    const cas: NovelWorkspaceCas | undefined = this.workspaceStatus?.cas;
    if (project === null || cas === undefined || this.busy || this.hasBlockingNovelRun() ||
      !canCloneCollectedMessage(project, message, cas.branchId)) return;
    const projectId: string = this.projectId;
    AlertDialog.show({ title: '再次收录这份回复？',
      message: '根据当前书稿再次收录这份原回复。将创建一份带来源标记的新候选，再由你选择段落与收录位置；原收录记录保留。',
      primaryButton: { value: '取消', action: (): void => {} },
      secondaryButton: { value: '创建收录候选', action: (): void => {
        if (this.pageAlive && !this.pageHiddenOnce && this.projectId === projectId && this.workspaceStatus?.activeBranchId === cas.branchId) {
          this.cloneCollectedForCollection(message.id, cas);
        }
      } } });
  }

  private async cloneCollectedForCollection(messageId: string, cas: NovelWorkspaceCas): Promise<void> {
    if (this.busy || this.hasBlockingNovelRun() || this.workspaceStatus?.activeBranchId !== cas.branchId) return;
    const projectId: string = this.projectId;
    this.busy = true;
    this.errorMsg = '';
    try {
      const cloned: NovelMessage = await getNovelCreation().cloneCollectedMessage(projectId, messageId, cas);
      if (!this.pageAlive || this.pageHiddenOnce || this.projectId !== projectId) return;
      await this.reload();
      if (this.pageAlive && !this.pageHiddenOnce && this.projectId === projectId && this.workspaceStatus?.activeBranchId === cas.branchId) {
        if (this.project?.messages.some((message: NovelMessage): boolean => message.id === cloned.id)) this.openCollect(cloned.id);
        else if (this.errorMsg.length === 0) this.errorMsg = '收录候选已保存，但页面尚未读取到。请重新打开项目后再收录。';
      }
    } catch (error) {
      if (this.pageAlive && this.projectId === projectId) this.errorMsg = String(error);
    } finally { if (this.pageAlive && this.projectId === projectId) this.busy = false; }
  }

  private async createWorkspaceBranch(name: string): Promise<void> {
    if (this.busy || this.hasBlockingNovelRun()) return;
    this.busy = true;
    try {
      await getNovelCreation().createBranch(this.projectId, name);
      this.requestedBranchId = '';
      this.requestedPolishJobId = '';
      this.branchSheetOpen = false;
      await this.reload();
    } catch (e) {
      this.showWorkspaceOperationError('创建分支失败', e);
    } finally {
      this.busy = false;
    }
  }

  private async selectWorkspaceBranch(branchId: string): Promise<void> {
    if (this.busy || this.ghostwriteBusy || this.polishBusy || this.sendStarting || this.composerRestoring ||
      branchId === this.workspaceStatus?.activeBranchId) return;
    if (this.hasBlockingNovelRun()) {
      const branch: NovelBranch | undefined = this.workspaceStatus?.branches.find((item: NovelBranch): boolean => item.id === branchId);
      if (branch === undefined) return;
      AlertDialog.show({
        title: '停止任务并切换分支？',
        message: `停止当前生成、代笔或润色任务，再切换到“${branch.name}”。已保存的内容保留，尚未完成的任务将取消。`,
        primaryButton: { value: '继续当前任务', action: (): void => {} },
        secondaryButton: { value: '停止并切换', action: (): void => { this.stopAndSwitchWorkspaceBranch(branchId); } },
      });
      return;
    }
    await this.stopAndSwitchWorkspaceBranch(branchId);
  }

  private async stopAndSwitchWorkspaceBranch(branchId: string): Promise<void> {
    if (this.busy || this.ghostwriteBusy || this.polishBusy || this.sendStarting || this.composerRestoring) return;
    this.busy = true;
    try {
      const creation = getNovelCreation();
      await creation.stopActiveRun(this.projectId);
      const ghostwrite: DurableGhostwriteJob | null = this.ghostwriteJob;
      if (ghostwrite !== null) {
        await creation.cancelGhostwrite(this.projectId, ghostwrite.jobId);
        this.ghostwritePollToken++;
        this.ghostwriteJob = null;
      }
      const polish: DurablePolishJob | null = this.polishJob;
      if (polish !== null && this.polishLocksWorkspace()) {
        this.polishJob = await creation.cancelPolish(this.projectId, polish.jobId);
        this.polishPollToken++;
      }
      // switchBranch 重新读取停止任务之后的工作区 CAS，再进入既有事务。
      await creation.switchBranch(this.projectId, branchId);
      this.requestedBranchId = '';
      this.requestedPolishJobId = '';
      this.branchSheetOpen = false;
      await this.reload();
    } catch (e) {
      this.showWorkspaceOperationError('切换分支失败', e);
    } finally {
      this.busy = false;
    }
  }

  private async undoWorkspace(): Promise<void> {
    if (this.busy || this.hasBlockingNovelRun()) return;
    this.busy = true;
    try {
      await getNovelCreation().undoLast(this.projectId);
      await this.reload();
    } catch (e) {
      this.showWorkspaceOperationError('撤销失败', e);
    } finally {
      this.busy = false;
    }
  }

  private async syncWorkspacePlot(): Promise<void> {
    if (this.busy || this.hasBlockingNovelRun()) return;
    this.busy = true;
    try {
      await getNovelCreation().syncPlot(this.projectId);
      await this.reload();
    } catch (e) {
      this.showWorkspaceOperationError('同步剧情失败', e);
    } finally {
      this.busy = false;
    }
  }

  private async resolveWorkspaceUnresolved(): Promise<void> {
    if (this.busy || this.hasBlockingNovelRun()) return;
    this.busy = true;
    try {
      await getNovelCreation().resolveUnresolved(this.projectId);
      await this.reload();
    } catch (e) {
      this.showWorkspaceOperationError('标记后续影响失败', e);
    } finally {
      this.busy = false;
    }
  }

  private showWorkspaceOperationError(action: string, error: Object): void {
    this.errorMsg = `${action}：${String(error)}`;
    promptAction.showToast({ message: this.errorMsg, duration: 2400 });
  }

  private writeExportBytes(uri: string, bytes: Uint8Array): void {
    const file: fileIo.File = fileIo.openSync(
      uri, fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
    try {
      const exact: ArrayBuffer = (bytes.buffer as ArrayBuffer)
        .slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
      fileIo.writeSync(file.fd, exact);
    } finally {
      fileIo.closeSync(file);
    }
  }

  private encodeExportText(text: string): Uint8Array {
    const values: number[] = [];
    for (let i: number = 0; i < text.length; i++) {
      let cp: number = text.charCodeAt(i);
      if (cp >= 0xd800 && cp <= 0xdbff && i + 1 < text.length) {
        const low: number = text.charCodeAt(i + 1);
        if (low >= 0xdc00 && low <= 0xdfff) {
          cp = 0x10000 + ((cp - 0xd800) << 10) + (low - 0xdc00);
          i += 1;
        }
      }
      if (cp < 0x80) values.push(cp);
      else if (cp < 0x800) values.push(0xc0 | (cp >> 6), 0x80 | (cp & 0x3f));
      else if (cp < 0x10000) {
        values.push(0xe0 | (cp >> 12), 0x80 | ((cp >> 6) & 0x3f), 0x80 | (cp & 0x3f));
      } else {
        values.push(0xf0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3f),
          0x80 | ((cp >> 6) & 0x3f), 0x80 | (cp & 0x3f));
      }
    }
    return new Uint8Array(values);
  }

  private async saveExportFile(fileName: string, bytes: Uint8Array): Promise<void> {
    const options: picker.DocumentSaveOptions = new picker.DocumentSaveOptions();
    options.newFileNames = [fileName];
    const context: common.UIAbilityContext =
      this.getUIContext().getHostContext() as common.UIAbilityContext;
    const documentPicker: picker.DocumentViewPicker = new picker.DocumentViewPicker(context);
    const uris: string[] = await documentPicker.save(options);
    if (uris.length === 0) return;
    this.writeExportBytes(uris[0], bytes);
    promptAction.showToast({ message: '文件已导出', duration: 1600 });
  }

  private async deliverExportFile(
    fileName: string, bytes: Uint8Array, share: boolean, format: NovelShareFormat,
  ): Promise<void> {
    if (!share) { await this.saveExportFile(fileName, bytes); return; }
    const context: common.UIAbilityContext =
      this.getUIContext().getHostContext() as common.UIAbilityContext;
    await shareNovelFile(context, fileName, bytes, format,
      (): boolean => this.pageAlive && !this.pageHiddenOnce);
  }

  private async exportArtifact(index: number, share: boolean = false): Promise<void> {
    if (this.project === null || this.busy) return;
    this.busy = true;
    try {
      const safeName: string = this.project.name.replace(/[\\/:*?"<>|]/g, '-');
      if (index === 0) {
        const zip: Uint8Array = await getNovelWorkspaceService().exportWorkspaceZip(this.projectId);
        await this.deliverExportFile(`${safeName}.amber-novel.zip`, zip, share, 'zip');
      } else if (index === 1) {
        const zip: Uint8Array = await getNovelWorkspaceService().exportNativeBackupZip(this.projectId);
        await this.deliverExportFile(`${safeName}.amber-native-backup.zip`, zip, share, 'zip');
      } else {
        const book: NovelBookExportBundle = await getNovelWorkspaceService().exportBook(this.projectId);
        if (index === 2) await this.deliverExportFile(`${safeName}.txt`, this.encodeExportText(book.text), share, 'txt');
        else if (index === 3) {
          await this.deliverExportFile(`${safeName}.md`, this.encodeExportText(book.markdown), share, 'markdown');
        } else if (index === 4) await this.deliverExportFile(`${safeName}.epub`, book.epub, share, 'epub');
      }
      this.errorMsg = '';
    } catch (e) {
      this.errorMsg = `${share ? '分享' : '导出'}失败：${String(e)}`;
      promptAction.showToast({ message: this.errorMsg, duration: 2400 });
    } finally {
      this.busy = false;
    }
  }

  private showExportMenu(): void {
    if (this.project === null || this.busy) return;
    promptAction.showActionMenu({
      buttons: [
        { text: '公开工作区 ZIP（跨端交流）', color: $r('sys.color.ohos_id_color_text_primary') },
        { text: '完整备份 ZIP（含创作记录）', color: $r('sys.color.ohos_id_color_text_primary') },
        { text: '正文 TXT', color: $r('sys.color.ohos_id_color_text_primary') },
        { text: '正文 Markdown', color: $r('sys.color.ohos_id_color_text_primary') },
        { text: '正文 EPUB', color: $r('sys.color.ohos_id_color_text_primary') },
        { text: '分享作品…', color: $r('sys.color.ohos_id_color_text_primary') },
      ],
    }).then((result: ExportMenuResult): void => {
      if (!this.pageAlive || this.pageHiddenOnce || this.busy) return;
      if (result.index === 5) this.showShareMenu();
      else this.exportArtifact(result.index);
    }).catch((): void => {});
  }

  private showShareMenu(): void {
    if (this.project === null || this.busy) return;
    promptAction.showActionMenu({
      buttons: [
        { text: '分享公开工作区 ZIP', color: $r('sys.color.ohos_id_color_text_primary') },
        { text: '分享完整备份 ZIP', color: $r('sys.color.ohos_id_color_text_primary') },
        { text: '分享正文 TXT', color: $r('sys.color.ohos_id_color_text_primary') },
        { text: '分享正文 Markdown', color: $r('sys.color.ohos_id_color_text_primary') },
        { text: '分享正文 EPUB', color: $r('sys.color.ohos_id_color_text_primary') },
      ],
    }).then((result: ExportMenuResult): void => {
      if (!this.pageAlive || this.pageHiddenOnce || this.busy) return;
      this.exportArtifact(result.index, true);
    }).catch((): void => {});
  }

  // 章节目录次要操作(一致性/深度审计/撤销/批量润色);既有守卫逻辑不变
  private showChapterToolsMenu(): void {
    if (this.busy || this.project === null) return;
    promptAction.showActionMenu({
      buttons: [
        { text: '一致性检查', color: $r('sys.color.ohos_id_color_text_primary') },
        { text: this.workspaceStatus?.canUndo && !this.novelJobLocksWorkspace() ? '撤销上次工作区操作' : '撤销（当前不可用）',
          color: this.workspaceStatus?.canUndo && !this.novelJobLocksWorkspace() ? $r('sys.color.ohos_id_color_text_primary') : $r('sys.color.ohos_id_color_text_tertiary') },
        { text: this.polishConflictReason().length > 0 ? `批量润色（${this.polishConflictReason()}）` : '批量润色',
          color: this.polishConflictReason().length > 0 ? $r('sys.color.ohos_id_color_text_tertiary') : $r('sys.color.ohos_id_color_text_primary') },
      ],
    }).then((result: ExportMenuResult): void => {
      if (!this.pageAlive || this.busy) return;
      if (result.index === 0) this.runConsistencyCheck();
      else if (result.index === 1) {
        if (this.workspaceStatus?.canUndo && !this.novelJobLocksWorkspace()) this.undoWorkspace();
      } else if (result.index === 2) {
        if (this.polishConflictReason().length === 0) {
          this.requestedPolishJobId = '';
          this.singlePolishChapterId = '';
          this.polishStartError = '';
          this.polishCreateOpen = true;
        }
      }
    }).catch((): void => {});
  }

  // ===== 聊天 =====
  assistantLabel(m: NovelMessage): string {
    if (m.interrupted) return '已停止（内容已保留）';
    if (m.mode === 'discuss') return '讨论';
    return m.granularity === 'continuation' ? '续写' : '整章';
  }

  private messageTimestamp(epochMillis: number): string {
    return new Date(epochMillis).toISOString();
  }

  private projectMessageUi(message: NovelMessage): UIMessage {
    const overview: string | null = this.project === null ? null : formatNovelQuickStartOverview(this.project, message);
    if (overview === null) return novelMessageUi(message);
    const original: UIMessage = novelMessageUi(message);
    return makeUIMessage(original.role, [{ type: 'text', text: overview, metadata: null }], {
      id: original.id, createdAt: original.createdAt, finishedAt: original.finishedAt, modelId: original.modelId,
    });
  }

  private unsavedDisplayMessage(message: UIMessage): UIMessage {
    if (!this.failedQuickStart || message.role !== 'assistant') return message;
    return makeUIMessage('assistant', [{ type: 'text', text: '快速开始未能保存。可复制原始内容后重试。', metadata: null }], {
      id: message.id, createdAt: message.createdAt, finishedAt: message.finishedAt, modelId: message.modelId,
    });
  }

  private pendingUserUi(): UIMessage {
    const createdAt: string = this.messageTimestamp(
      this.activeRunCreatedAt > 0 ? this.activeRunCreatedAt : Date.now());
    return makeUIMessage('user', [{ type: 'text', text: this.pendingUserText, metadata: null }], {
      id: `novel-user:${this.activeRunId}`,
      createdAt,
      finishedAt: createdAt,
      modelId: this.project?.modelId ?? null,
    });
  }

  private activeStatusUi(): UIMessage {
    const createdAt: string = this.messageTimestamp(
      this.activeRunCreatedAt > 0 ? this.activeRunCreatedAt : Date.now());
    return makeUIMessage('assistant', [{ type: 'text', text: this.generationStatus, metadata: null }], {
      id: `novel-run:${this.activeRunId}`,
      createdAt,
      finishedAt: null,
      modelId: this.project?.modelId ?? null,
    });
  }

  private scheduleNovelScrollToBottom(): void {
    const token: number = ++this.novelScrollToken;
    setTimeout((): void => {
      if (!this.pageAlive || token !== this.novelScrollToken || this.userTouchActive) return;
      this.atBottomApprox = true;
      this.jumpButtonVisible = false;
      this.nativeFollow?.cancel();
      this.chatScroller.scrollEdge(Edge.Bottom);
    }, 0);
  }

  private novelTimelineItemCount(): number {
    let count: number = 0;
    const project: NovelProject | null = this.project;
    if (project !== null) {
      if (this.ordinaryRecoveryRecord() !== null && this.activeRunId.length === 0) count++;
      if (this.ghostwriteJob !== null) count++;
      if (project.messages.length === 0 && this.activeRunId.length === 0) count++;
      if (this.ghostwriteJob === null && this.ghostwriteReport !== null) count++;
      const messages: NovelMessage[] = this.novelTimelineMessages();
      const expandedArchives: string[] = this.expandedArchiveIds;
      for (const message of messages) {
        if (discussionArchiveAtMessage(project.discussionArchives, message) !== null) count++;
        if (discussionMessageVisible(project.discussionArchives, expandedArchives, message)) count++;
        count += this.settingProposalsAt(message).length;
      }
      count += orphanSettingProposals(project.settingProposals, project.messages).length;
    }
    if (this.pendingUserText.length > 0 && this.activeMessages.length === 0) count++;
    if (this.activeRunId.length === 0) count += this.failedMessages.length + this.completedPreview.length;
    else {
      const activeCount: number = this.activeRunTimelineMessages().length;
      if (this.activeQuickStart) count += 1 + activeCount;
      else if (activeCount === 0) count++;
    }
    return count;
  }

  private measuredNovelBottomTarget(): number | null {
    const current: number = this.chatScroller.currentOffset().yOffset;
    if (this.chatScroller.isAtEnd()) return current;
    const lastIndex: number = this.novelTimelineItemCount() - 1;
    if (lastIndex < 0) return current;
    if (this.novelLastVisibleIndex < lastIndex || this.novelViewportHeight <= 0) return null;
    const rect: RectResult = this.chatScroller.getItemRect(lastIndex);
    return current + Math.max(0, rect.y + rect.height - this.novelViewportHeight);
  }

  private requestNovelNativeFollow(): void {
    if (this.nativeFollow === null) {
      this.nativeFollow = new NativeTimelineFollow(this.getUIContext(), this.chatScroller,
        (): boolean => this.pageAlive && this.studioPageVisible && !this.studioBackgrounded && this.tab === 0 &&
          !this.userTouchActive && shouldScrollOnStreamUpdate(this.followMode, this.atBottomApprox),
        (): number | null => this.measuredNovelBottomTarget(), (): void => {});
    }
    this.nativeFollow.request();
  }

  private beginNovelGenerationFollow(): void {
    this.novelUserScrolling = false;
    this.followMode = transitionOnJumpToBottom(true);
    this.atBottomApprox = true;
    this.jumpButtonVisible = false;
    this.scheduleNovelScrollToBottom();
  }

  private followNovelStreamingUpdate(): void {
    this.followMode = transitionOnStreamUpdate(this.followMode, this.atBottomApprox);
    this.jumpButtonVisible = this.followMode === 'paused';
    if (shouldScrollOnStreamUpdate(this.followMode, this.atBottomApprox)) {
      this.requestNovelNativeFollow();
    }
  }

  private finishNovelGenerationFollow(): void {
    const wasPaused: boolean = this.followMode === 'paused';
    this.followMode = transitionOnGenerateEnd(this.followMode);
    this.jumpButtonVisible = wasPaused;
    if (!wasPaused) this.requestNovelNativeFollow();
  }

  private reconcileNovelUserScroll(): void {
    this.novelScrollToken++;
    this.nativeFollow?.cancel();
    let atEnd: boolean = false;
    try {
      atEnd = this.chatScroller.isAtEnd();
    } catch (_error) {
      atEnd = this.atBottomApprox;
    }
    this.atBottomApprox = atEnd;
    this.followMode = transitionOnUserScroll(
      this.followMode, atEnd, this.activeRunId.length > 0);
    this.jumpButtonVisible = !atEnd;
  }

  private jumpNovelToBottom(): void {
    this.novelUserScrolling = false;
    this.followMode = transitionOnJumpToBottom(this.activeRunId.length > 0);
    this.scheduleNovelScrollToBottom();
  }

  private setComposerDraft(text: string): void {
    if (text === this.draft) return;
    this.draft = text;
    this.composerRevision++;
    this.scheduleComposerSave();
  }

  private scheduleComposerSave(): void {
    const token: number = ++this.composerSaveToken;
    setTimeout((): void => {
      if (token === this.composerSaveToken) this.flushComposerDraft();
    }, 400);
  }

  private flushComposerDraft(): void {
    this.composerSaveToken++;
    if (this.composerDraftOwner.length === 0 || this.composerRestoring) return;
    const owner: string = this.composerDraftOwner;
    const snapshot: NovelComposerDraft = { text: this.draft, mode: this.composeMode };
    this.composerDraftStore.save(owner, snapshot).catch((error: Object): void => {
      const message: string = `创作草稿保存失败：${String(error)}`;
      if (this.pageAlive && this.composerDraftOwner === owner) this.errorMsg = message;
      promptAction.showToast({ message, duration: 2400 });
    });
  }

  private async restoreComposerDraft(owner: string): Promise<void> {
    if (this.composerDraftOwner === owner) return;
    this.flushComposerDraft();
    this.composerDraftOwner = owner;
    this.composerRevision++;
    const revision: number = this.composerRevision;
    this.composerRestoring = true;
    this.draft = '';
    this.failedInput = '';
    this.failedInputRunKind = null;
    this.failedMessages = [];
    try {
      const snapshot: NovelComposerDraft = await this.composerDraftStore.load(owner);
      if (this.pageAlive && this.composerDraftOwner === owner && this.composerRevision === revision) {
        this.draft = snapshot.text;
        this.composeMode = snapshot.mode;
      }
    } catch (error) {
      if (this.pageAlive && this.composerDraftOwner === owner) {
        this.errorMsg = `创作草稿恢复失败：${String(error)}`;
      }
    } finally {
      if (this.composerDraftOwner === owner) this.composerRestoring = false;
    }
  }

  private restoreFailedInput(): void {
    if (this.failedInputRunKind === 'quick_start') {
      this.retryQuickStartSeed(this.failedInput);
      return;
    }
    if (this.failedInput.length === 0 || this.hasBlockingNovelRun()) return;
    if (this.draft.length > 0) {
      promptAction.showToast({ message: '请先保留或清空当前输入，再恢复失败的输入。', duration: 1800 });
      return;
    }
    this.composeMode = this.failedInputMode;
    this.setComposerDraft(this.failedInput);
    this.failedInput = '';
    this.flushComposerDraft();
  }

  private quickStartSeedFor(message: NovelMessage): string {
    const messages: NovelMessage[] = this.project?.messages ?? [];
    const index: number = messages.findIndex((item: NovelMessage): boolean => item.id === message.id);
    for (let cursor: number = index - 1; cursor >= 0; cursor--) {
      if (messages[cursor].role === 'user') return novelMessageText(messages[cursor]);
    }
    return '';
  }

  private canRetryQuickStart(message: NovelMessage): boolean {
    return message.role === 'assistant' && message.runKind === 'quick_start' &&
      !this.project?.settingProposals.some((proposal: NovelSettingProposal): boolean => proposal.sourceMessageId === message.id) &&
      this.quickStartSeedFor(message).trim().length > 0;
  }

  private async retryQuickStartSeed(seed: string): Promise<void> {
    if (seed.trim().length === 0 || this.busy || this.hasBlockingNovelRun()) return;
    if (this.draft.length > 0 && this.draft !== seed) {
      promptAction.showToast({ message: '请先保留或清空当前输入，再重试快速开始。', duration: 1800 });
      return;
    }
    this.composeMode = 'discuss';
    this.setComposerDraft(seed);
    this.flushComposerDraft();
    await this.send('quick_start');
  }

  async send(runKindOverride: NovelRunKind | null = null): Promise<void> {
    const originalInput: string = this.draft;
    const text: string = originalInput.trim();
    if (text.length === 0 || this.busy || this.hasBlockingNovelRun() || this.project === null) return;
    const owner: string = this.composerDraftOwner;
    const composeMode: NovelComposeMode = this.composeMode;
    this.sendStarting = true;
    let clearedRevision: number = -1;
    try {
      await getNovelCreation().validateWritingModel(this.projectId);
      if (!this.pageAlive || this.busy || this.activeRunId.length > 0 || this.novelJobLocksWorkspace() ||
        this.project === null || this.composerDraftOwner !== owner || this.draft !== originalInput) return;
      const mode: NovelChatMode = composeMode === 'discuss' ? 'discuss' : 'write';
      let gran: NovelGenerationGranularity | null = null;
      if (composeMode === 'continue') gran = 'continuation';
      else if (composeMode === 'whole_chapter') gran = 'whole_chapter';
      let runKind: NovelRunKind = novelComposerRunKind(mode, gran);
      if (runKindOverride !== null) runKind = runKindOverride;
      this.setComposerDraft('');
      clearedRevision = this.composerRevision;
      this.flushComposerDraft();
      const run: NovelRun = runKind === 'quick_start'
        ? await getNovelCreation().quickStart(this.projectId, text)
        : getNovelCreation().generate(this.projectId, text, mode, gran, runKind, null, this.contextOverrides ?? undefined);
      if (runKind !== 'quick_start') this.contextOverrides = null;
      if (!this.pageAlive || this.composerDraftOwner !== owner) return;
      this.failedInput = '';
      this.attachNovelRun(
        run, this.project.messages.length, text, Date.now(), '正在连接模型并组织正文…', runKind === 'quick_start', runKind);
    } catch (error) {
      if (this.pageAlive && this.composerDraftOwner === owner) {
        if (clearedRevision >= 0 && canRestoreFailedInput(
          owner, this.composerDraftOwner, clearedRevision, this.composerRevision, this.draft)) {
          this.setComposerDraft(originalInput);
          this.flushComposerDraft();
        }
        if (runKindOverride === 'quick_start') {
          this.failedInput = text;
          this.failedInputRunKind = 'quick_start';
        }
        this.errorMsg = `生成未能启动：${String(error)}`;
      }
    } finally {
      this.sendStarting = false;
    }
  }

  interrupt(): void {
    if (this.activeRunId.length > 0) getNovelCreation().interrupt(this.activeRunId);
  }

  private hasBlockingNovelRun(): boolean {
    return this.sendStarting || this.composerRestoring ||
      this.activeRunId.length > 0 || this.novelJobLocksWorkspace();
  }

  // 打开收录表单(iOS 四段式):拆段落、全选、初稿=全选拼接
  openCollect(messageId: string): void {
    const p: NovelProject | null = this.project;
    const msg: NovelMessage | null = p === null ? null
      : p.messages.find((m: NovelMessage): boolean => m.id === messageId) ?? null;
    const location: CollectionLocation = defaultCollectionLocation(
      msg?.candidate?.kind ?? 'write', msg?.candidate?.sourceChapterId ?? null,
      msg?.granularity ?? null, p?.chapters ?? []);
    try {
      this.collectTitle = p !== null ? `第 ${nextNovelChapterOrdinal(p.chapters)} 章` : '第 1 章';
    } catch (error) {
      this.collectTitle = '';
      this.errorMsg = error instanceof Error ? error.message : String(error);
    }
    this.collectMessageId = messageId;
    const text: string = msg === null ? '' : novelMessageText(msg);
    this.collectParas = text.split(/\n\s*\n/).map((s: string): string => s.trim())
      .filter((s: string): boolean => s.length > 0);
    this.collectSelected = this.collectParas.map((): boolean => true);
    this.collectDraft = this.collectParas.join('\n\n');
    this.collectManuallyEdited = false;
    this.collectMode = location.mode;
    this.collectChapterId = location.chapterId;
    this.collectOriginalTitle = this.collectTitle;
    this.collectOriginalDraft = this.collectDraft;
    this.collectOriginalMode = this.collectMode;
    this.collectOriginalChapterId = this.collectChapterId;
  }

  private togglePara(idx: number): void {
    const next: boolean[] = this.collectSelected.slice();
    next[idx] = !next[idx];
    this.collectSelected = next;
    this.refreshCollectDraft();
  }

  private selectCollectParagraphs(selected: boolean): void {
    this.collectSelected = this.collectParas.map((): boolean => selected);
    this.refreshCollectDraft();
  }

  private refreshCollectDraft(): void {
    this.collectDraft = draftAfterSelection(
      this.collectParas, this.collectSelected, this.collectDraft, this.collectManuallyEdited);
  }

  private collectSelectedCount(): number {
    return this.collectSelected.filter((v: boolean): boolean => v).length;
  }

  // 「按当前选择重置」:编辑稿回到勾选段落拼接(iOS 同名)
  private resetDraftFromSelection(): void {
    this.collectManuallyEdited = false;
    this.collectDraft = selectedParagraphDraft(this.collectParas, this.collectSelected);
  }

  private confirmDiscardCollect(): void {
    if (this.busy) return;
    const messageId: string = this.collectMessageId;
    this.collectDraftController.stopEditing();
    this.collectTitleController.stopEditing();
    // 退出编辑先接收输入法最终 onChange，再判断脏稿。
    setTimeout((): void => {
      if (!this.pageAlive || this.collectMessageId !== messageId || this.busy) return;
      const dirty: boolean = this.collectDraft !== this.collectOriginalDraft ||
        this.collectTitle !== this.collectOriginalTitle || this.collectMode !== this.collectOriginalMode ||
        this.collectChapterId !== this.collectOriginalChapterId ||
        this.collectSelected.some((selected: boolean): boolean => !selected);
      if (!dirty) {
        this.collectMessageId = '';
        return;
      }
      AlertDialog.show({
        title: '放弃收录编辑？',
        message: '尚未收录的正文修改和段落选择将丢失。',
        primaryButton: { value: '继续编辑', action: (): void => {} },
        secondaryButton: { value: '放弃', fontColor: ERROR,
          action: (): void => { this.collectMessageId = ''; } },
      });
    }, 0);
  }

  private collectDisabled(): boolean {
    if (this.busy || !canCollectSelection(this.collectSelected, this.collectDraft)) return true;
    return this.collectMode !== 0 && this.collectChapterId.length === 0;
  }

  private async submitCollect(): Promise<void> {
    if (this.collectMessageId.length === 0 || this.collectDisabled()) return;
    const messageId: string = this.collectMessageId;
    const visibleCycle: number = this.studioVisibleCycle;
    this.collectDraftController.stopEditing();
    this.collectTitleController.stopEditing();
    await new Promise<void>((resolve: () => void): void => { setTimeout(resolve, 0); });
    if (this.collectMessageId !== messageId || this.collectDisabled()) return;
    let target: NovelCollectionTarget;
    if (this.collectMode === 0) target = { kind: 'new_chapter', title: this.collectTitle };
    else if (this.collectMode === 1) target = { kind: 'append', chapterId: this.collectChapterId };
    else target = { kind: 'replace', chapterId: this.collectChapterId };
    this.busy = true;
    try {
      const projectId: string = this.projectId;
      const branchId: string = this.workspaceStatus?.activeBranchId ?? '';
      const collected = await getNovelCreation().collectMessage(projectId, this.collectMessageId, target,
        this.collectDraft.trim());
      if (!this.pageAlive || this.pageHiddenOnce || this.projectId !== projectId ||
        this.workspaceStatus?.activeBranchId !== branchId) return;
      const feedbackToken: number = ++this.collectionFeedbackToken;
      this.collectionFeedback = '正文已收录，剧情同步点已保存。正在分析本章资料建议…';
      void collected.analysisFinished.then(
        (result: NovelSuggestionRefreshResult): Promise<void> => this.refreshCollectedAnalysis(projectId, branchId, feedbackToken, result),
        (): Promise<void> => this.refreshCollectedAnalysis(projectId, branchId, feedbackToken, null),
      ).catch((): void => {});
      this.collectMessageId = '';
      this.errorMsg = '';
      await this.reload();
      this.studioTabDirection = this.tab < 1 ? 1 : -1;
      this.tab = 1;
      this.showCollectionStamp(projectId, branchId, visibleCycle);
    } catch (e) {
      this.errorMsg = String(e);
    } finally {
      this.busy = false;
    }
  }

  private materialAnalysisFailureText(error: string): string {
    if (error.includes('资料建议解析失败')) {
      return '模型返回的资料建议格式不完整，请重新分析此章。';
    }
    return error.replace(/^(NovelError|Error):\s*/, '');
  }

  private async refreshCollectedAnalysis(projectId: string, branchId: string, token: number,
    result: NovelSuggestionRefreshResult | null): Promise<void> {
    if (!this.pageAlive || this.projectId !== projectId ||
      this.workspaceStatus?.activeBranchId !== branchId || token !== this.collectionFeedbackToken) return;
    this.collectionFeedback = result !== null
      ? `正文已收录，剧情同步点已保存。${result.warning ?? `资料分析完成，新增 ${result.count} 条建议。`}`
      : '正文已收录，剧情同步点已保存。资料分析未完成，可在「设定」中重新分析此章。';
    await this.reload();
  }

  private continueNovelTool(toolCallId: string, verdict: NovelToolContinuationVerdict,
    locator?: ToolApprovalPartLocator): void {
    if (this.busy || this.hasBlockingNovelRun()) return;
    // R08:NovelService.continueTool 目前按 id 定位(findTool 取首个匹配),
    //   空 id 会错误命中同 blank 的其它工具、重复 id 也无法区分。UI 侧先用 locator
    //   精确解析出真实 id;无法唯一确定时安全拒绝,绝不按 blank 等价批准/续跑。
    const resolvedId: string = this.resolveNovelToolId(toolCallId, locator);
    if (resolvedId.length === 0) {
      this.errorMsg = '无法唯一定位这次工具调用（缺少或重复的工具 ID），已拒绝本次操作。';
      return;
    }
    const run: NovelRun = getNovelCreation().continueTool(this.projectId, resolvedId, verdict);
    const historyCount: number = (this.project?.messages ?? []).findIndex((message: NovelMessage): boolean =>
      novelMessageUi(message).parts.some((part: UIMessagePart): boolean => part.type === 'tool' && part.toolCallId === resolvedId));
    const sourceMessage: NovelMessage | undefined = this.project?.messages[historyCount];
    this.attachNovelRun(
      run, historyCount, '', Date.now(), '正在继续同一轮任务…', false,
      sourceMessage?.runKind ?? (sourceMessage?.mode === 'discuss' ? 'discussion' : 'prose_whole_chapter'));
  }

  // R08:locator(messageId+partIndex)优先精确定位到目标工具 part 的 toolCallId;
  //   无 locator 时要求 id 非空且在整个项目内唯一命中,否则返回 ''(调用方拒绝)。
  private resolveNovelToolId(toolCallId: string, locator?: ToolApprovalPartLocator): string {
    const project: NovelProject | null = this.project;
    if (project === null) return '';
    if (locator !== undefined) {
      for (let i: number = 0; i < project.messages.length; i++) {
        const ui: UIMessage = novelMessageUi(project.messages[i]);
        if (ui.id !== locator.messageId) continue;
        const parts: UIMessagePart[] = ui.parts;
        if (locator.partIndex < 0 || locator.partIndex >= parts.length) return '';
        const target: UIMessagePart = parts[locator.partIndex];
        if (target.type !== 'tool') return '';
        return target.toolCallId.length > 0 ? target.toolCallId : '';
      }
      return '';
    }
    if (toolCallId.length === 0) return '';
    let found: string = '';
    let count: number = 0;
    for (let i: number = 0; i < project.messages.length; i++) {
      const parts: UIMessagePart[] = novelMessageUi(project.messages[i]).parts;
      for (let p: number = 0; p < parts.length; p++) {
        const part: UIMessagePart = parts[p];
        if (part.type !== 'tool' || part.toolCallId !== toolCallId) continue;
        found = part.toolCallId;
        count++;
      }
    }
    return count === 1 ? found : '';
  }

  private clearNovelRunUi(): void {
    this.activeRunId = '';
    this.activeQuickStart = false;
    this.activeRunKind = null;
    this.activeRunCreatedAt = 0;
    this.pendingUserText = '';
    this.activeMessages = [];
    this.activeHistoryCount = 0;
    this.activeTextDeltasLive = false;
    this.activeTransport = 'unavailable';
    this.activeFrameSeen = false;
    this.generationStatus = '';
  }

  private attachNovelRun(
    run: NovelRun, historyCount: number, pendingUserText: string,
    startedAt: number, statusText: string, quickStart: boolean = false, runKind: NovelRunKind | null = null,
  ): void {
    if (this.currentUnsub !== null) {
      const previous: () => void = this.currentUnsub;
      this.currentUnsub = null;
      previous();
    }
    this.activeRunId = run.id;
    this.activeQuickStart = quickStart;
    this.activeRunCreatedAt = startedAt;
    this.pendingUserText = pendingUserText;
    this.activeMessages = [];
    this.activeHistoryCount = historyCount;
    this.activeTextDeltasLive = false;
    this.activeTransport = 'unavailable';
    this.activeFrameSeen = false;
    this.generationStatus = statusText;
    this.errorMsg = '';
    this.failedMessages = [];
    this.failedQuickStart = false;
    this.completedPreview = [];
    const owner: string = this.composerDraftOwner;
    const inputMode: NovelComposeMode = this.composeMode;
    this.activeRunKind = runKind ?? (quickStart ? 'quick_start' :
      (inputMode === 'discuss' ? 'discussion' : 'prose_whole_chapter'));
    this.beginNovelGenerationFollow();
    let terminalSeen: boolean = false;
    const unsub: () => void = run.subscribe((event: NovelRunEvent): void => {
      if (this.activeRunId !== run.id) return;
      if (event.kind === 'status') {
        if (event.text.length > 0) this.generationStatus = event.text;
      } else if (event.kind === 'snapshot') {
        this.activeMessages = event.messages.slice(this.activeHistoryCount);
        this.activeTextDeltasLive = event.textDeltasLive;
        this.activeTransport = event.transport;
        this.activeFrameSeen = true;
        this.followNovelStreamingUpdate();
      } else if (event.kind === 'completed' || event.kind === 'waiting_user' ||
        event.kind === 'interrupted' || event.kind === 'failed') {
        terminalSeen = true;
        if (event.kind === 'failed' && event.unsavedMessages !== undefined) {
          this.failedMessages = event.unsavedMessages.slice();
          this.failedQuickStart = this.activeQuickStart;
        }
        if (event.kind === 'failed' && this.composerDraftOwner === owner && this.pendingUserText.length > 0) {
          this.failedInput = this.pendingUserText;
          this.failedInputMode = inputMode;
          this.failedInputRunKind = this.activeQuickStart ? 'quick_start' : null;
        }
        if (event.kind === 'failed') this.errorMsg = event.message;
        this.currentUnsub = null;
        void this.settleNovelRunUi(run.id);
      }
    });
    if (!terminalSeen && this.activeRunId === run.id) this.currentUnsub = unsub;
    else unsub();
  }

  private async settleNovelRunUi(runId: string): Promise<void> {
    const reloadToken: number = this.reloadToken + 1;
    // Keep the live body until reload atomically publishes the saved transcript.
    await this.reload();
    if (!this.pageAlive || reloadToken !== this.reloadToken ||
      (this.activeRunId.length > 0 && this.activeRunId !== runId)) return;
    if (this.activeRunId === runId) {
      // A read failure must neither hide the last body nor keep an ended run locked.
      if (this.failedMessages.length === 0) {
        this.completedPreview = this.activeRunTimelineMessages().map((message: UIMessage): UIMessage =>
          this.activeRunMessage(message));
      }
      this.clearNovelRunUi();
    }
    this.finishNovelGenerationFollow();
  }

  private async resolveSettingProposal(proposal: NovelSettingProposal, accept: boolean): Promise<void> {
    if (this.busy || this.hasBlockingNovelRun()) return;
    if (accept) {
      this.openMaterialAdoption(proposal.id, true);
      return;
    }
    this.busy = true;
    try {
      await getNovelCreation().resolveSettingProposal(this.projectId, proposal.id, false);
      this.errorMsg = '';
      await this.reload();
    } catch (e) {
      this.errorMsg = String(e);
    } finally {
      this.busy = false;
    }
  }

  // ===== 章节 =====
  openNewChapter(): void {
    if (this.busy || this.novelJobLocksWorkspace()) return;
    this.errorMsg = '';
    this.editingChapterId = '';
    const p: NovelProject | null = this.project;
    try {
      this.chapterTitle = p !== null ? `第 ${nextNovelChapterOrdinal(p.chapters)} 章` : '第 1 章';
    } catch (error) {
      this.errorMsg = error instanceof Error ? error.message : String(error);
      return;
    }
    this.chapterContent = '';
    this.chapterOriginalTitle = this.chapterTitle;
    this.chapterOriginalContent = this.chapterContent;
    this.chapterEditorOpen = true;
  }

  private attachChapterRun(run: NovelRun, statusText: string): void {
    this.attachNovelRun(
      run, this.project?.messages.length ?? 0, '', Date.now(), statusText, false, 'regenerate');
  }

  async regenerateChapter(chapterId: string): Promise<void> {
    if (this.hasBlockingNovelRun()) return;
    try {
      const creation = getNovelCreation();
      const run: NovelRun = await creation.regenerateChapter(this.projectId, chapterId);
      this.attachChapterRun(run, '重新生成中…');
    } catch (e) {
      this.errorMsg = String(e);
    }
  }

  // 单章与批量共用持久化润色及上下文确认面板。
  async polishChapter(chapterId: string): Promise<void> {
    if (this.busy || this.hasBlockingNovelRun()) return;
    this.singlePolishChapterId = chapterId;
    this.polishStartError = '';
    this.polishCreateOpen = true;
  }

  private polishSheetChapters(): NovelPolishCreateSheetChapter[] {
    const p: NovelProject | null = this.project;
    if (p === null) return [];
    return p.chapters.filter((chapter: NovelChapter): boolean =>
      this.singlePolishChapterId.length === 0 || chapter.id === this.singlePolishChapterId).map((chapter: NovelChapter): NovelPolishCreateSheetChapter => ({
      id: chapter.id,
      ordinal: novelChapterOrdinal(chapter, p.chapters.indexOf(chapter) + 1),
      title: chapter.title,
      discarded: chapter.discarded,
    }));
  }

  private polishConflictReason(): string {
    if (this.ghostwriteJob !== null) return '当前分支已有代笔任务，请先完成或取消。';
    if (this.polishLocksWorkspace()) return '当前分支已有批量润色任务。';
    if (this.activeRunId.length > 0) return '当前对话仍在生成，请先停止。';
    return '';
  }

  private async startPolish(
    startOrdinal: number, endOrdinal: number, contextOptions: PolishContextOptions, ordinals: number[],
  ): Promise<void> {
    if (this.busy || this.polishBusy || this.polishConflictReason().length > 0) return;
    this.polishBusy = true;
    this.polishStartError = '';
    try {
      this.requestedPolishJobId = '';
      this.polishJob = this.singlePolishChapterId.length > 0
        ? await getNovelCreation().startChapterPolish(this.projectId, this.singlePolishChapterId, contextOptions)
        : await getNovelCreation().startSelectedPolish(this.projectId, ordinals, contextOptions);
      this.polishCreateOpen = false;
      this.errorMsg = '';
      this.schedulePolishPoll();
    } catch (error) {
      this.polishStartError = String(error);
    } finally {
      this.polishBusy = false;
    }
  }

  private async pausePolish(): Promise<void> {
    const job: DurablePolishJob | null = this.polishJob;
    if (this.polishBusy || job === null || !this.polishStageRuns(job.stage)) return;
    this.polishBusy = true;
    try {
      this.polishPollToken++;
      this.polishJob = await getNovelCreation().pausePolish(this.projectId, job.jobId);
      this.errorMsg = '';
    } catch (error) {
      this.showWorkspaceOperationError('暂停批量润色失败', error);
    } finally {
      this.polishBusy = false;
    }
  }

  private async resumePolish(): Promise<void> {
    const job: DurablePolishJob | null = this.polishJob;
    if (this.polishBusy || job === null ||
      (job.stage !== 'paused' && job.stage !== 'waiting_system')) return;
    this.polishBusy = true;
    try {
      this.polishJob = await getNovelCreation().resumePolish(this.projectId, job.jobId);
      this.errorMsg = '';
      this.schedulePolishPoll();
    } catch (error) {
      this.showWorkspaceOperationError('继续批量润色失败', error);
    } finally {
      this.polishBusy = false;
    }
  }

  private async retryPolish(): Promise<void> {
    const job: DurablePolishJob | null = this.polishJob;
    if (this.polishBusy || job === null || job.stage !== 'failed') return;
    this.polishBusy = true;
    try {
      this.polishJob = await getNovelCreation().retryPolish(this.projectId, job.jobId);
      this.errorMsg = '';
      this.schedulePolishPoll();
    } catch (error) {
      this.showWorkspaceOperationError('重试批量润色失败', error);
    } finally {
      this.polishBusy = false;
    }
  }

  private async cancelPolish(): Promise<void> {
    const job: DurablePolishJob | null = this.polishJob;
    if (this.polishBusy || job === null) return;
    this.polishBusy = true;
    try {
      this.polishPollToken++;
      this.polishJob = await getNovelCreation().cancelPolish(this.projectId, job.jobId);
      this.errorMsg = '';
      await this.reload();
    } catch (error) {
      this.showWorkspaceOperationError('取消批量润色失败', error);
    } finally {
      this.polishBusy = false;
    }
  }

  // N2:章节版本历史
  async saveChapter(): Promise<void> {
    if (this.busy || !this.chapterEditorOpen || this.novelJobLocksWorkspace()) return;
    this.chapterTitleController.stopEditing();
    this.chapterContentController.stopEditing();
    await new Promise<void>((resolve: () => void): void => { setTimeout(resolve, 0); });
    if (!this.pageAlive || !this.chapterEditorOpen || this.busy || this.novelJobLocksWorkspace()) return;
    this.busy = true;
    try {
      const saved: NovelChapter = await getNovelCreation().saveChapter(
        this.projectId, this.editingChapterId.length > 0 ? this.editingChapterId : null,
        this.chapterTitle, this.chapterContent);
      this.chapterEditorOpen = false;
      this.errorMsg = '';
      await this.reload();
    } catch (e) {
      this.errorMsg = e instanceof Error ? e.message : String(e);
    } finally {
      this.busy = false;
    }
  }

  async deleteChapter(id: string): Promise<void> {
    if (this.novelJobLocksWorkspace()) return;
    this.busy = true;
    try {
      await getNovelCreation().deleteChapter(this.projectId, id);
      await this.reload();
    } catch (e) {
      this.errorMsg = String(e);
    } finally {
      this.busy = false;
    }
  }

  // ===== 素材 =====
  // ===== 剧情区读写(伏笔/已确认决定随当前分支 branchSettings 保存) =====
  private materialsIn(kinds: NovelMaterialKind[]): NovelMaterial[] {
    const p: NovelProject | null = this.project;
    if (p === null) return [];
    return p.materials.filter((m: NovelMaterial): boolean => kinds.indexOf(m.kind) >= 0);
  }

  private pendingSuggestionsIn(kinds: NovelMaterialKind[]): NovelMaterialSuggestion[] {
    const p: NovelProject | null = this.project;
    if (p === null) return [];
    return p.materialSuggestions.filter(
      (s: NovelMaterialSuggestion): boolean => s.status === 'pending' && kinds.indexOf(s.kind) >= 0);
  }

  private pendingSettingProposalsIn(kinds: NovelMaterialKind[]): NovelSettingProposal[] {
    const p: NovelProject | null = this.project;
    if (p === null) return [];
    return p.settingProposals.filter(
      (proposal: NovelSettingProposal): boolean => proposal.status === 'pending' && kinds.indexOf(proposal.kind) >= 0);
  }

  private branchSettingsWith(foreshadows: NovelForeshadow[], decisions: NovelConfirmedDecision[]): NovelBranchSettings {
    const p: NovelProject | null = this.project;
    if (p === null) return emptyNovelBranchSettings();
    return {
      chapterContract: p.branchSettings.chapterContract,
      upcomingArc: p.branchSettings.upcomingArc,
      suggestedChapterCount: p.branchSettings.suggestedChapterCount,
      thisChapterPlan: p.branchSettings.thisChapterPlan,
      futurePlan: p.branchSettings.futurePlan,
      preferences: p.branchSettings.preferences,
      foreshadows: foreshadows,
      confirmedDecisions: decisions,
    };
  }

  private async addStoryForeshadow(): Promise<void> {
    const p: NovelProject | null = this.project;
    if (p === null || this.busy) return;
    const title: string = this.fsTitle.trim();
    const content: string = this.fsContent.trim();
    if (title.length === 0 || content.length === 0) {
      this.errorMsg = '请填写伏笔标题和内容后再新增。';
      return;
    }
    const now: number = Date.now();
    const added: NovelForeshadow = {
      id: `foreshadow-${now}-${p.branchSettings.foreshadows.length + 1}`,
      title: title, content: content, status: 'open', createdAt: now, resolvedAt: null,
    };
    await this.saveNovelBranchSettings(this.branchSettingsWith(
      p.branchSettings.foreshadows.concat([added]), p.branchSettings.confirmedDecisions));
    if (this.errorMsg.length === 0) {
      this.fsTitle = '';
      this.fsContent = '';
    }
  }

  private async toggleStoryForeshadow(id: string): Promise<void> {
    const p: NovelProject | null = this.project;
    if (p === null || this.busy) return;
    const now: number = Date.now();
    const updated: NovelForeshadow[] = p.branchSettings.foreshadows.map((item: NovelForeshadow): NovelForeshadow => {
      if (item.id !== id) return item;
      if (item.status === 'open') {
        return { id: item.id, title: item.title, content: item.content, status: 'resolved',
          createdAt: item.createdAt, resolvedAt: now };
      }
      return { id: item.id, title: item.title, content: item.content, status: 'open',
        createdAt: item.createdAt, resolvedAt: null };
    });
    await this.saveNovelBranchSettings(this.branchSettingsWith(updated, p.branchSettings.confirmedDecisions));
  }

  private async addStoryDecision(): Promise<void> {
    const p: NovelProject | null = this.project;
    if (p === null || this.busy) return;
    const title: string = this.decTitle.trim();
    const content: string = this.decContent.trim();
    if (title.length === 0 || content.length === 0) {
      this.errorMsg = '请填写决定标题和内容后再确认。';
      return;
    }
    const now: number = Date.now();
    const added: NovelConfirmedDecision = {
      id: `decision-${now}-${p.branchSettings.confirmedDecisions.length + 1}`,
      title: title, content: content, confirmedAt: now,
    };
    await this.saveNovelBranchSettings(this.branchSettingsWith(
      p.branchSettings.foreshadows, p.branchSettings.confirmedDecisions.concat([added])));
    if (this.errorMsg.length === 0) {
      this.decTitle = '';
      this.decContent = '';
    }
  }

  private openForeshadows(): NovelForeshadow[] {
    const p: NovelProject | null = this.project;
    if (p === null) return [];
    return p.branchSettings.foreshadows.filter((f: NovelForeshadow): boolean => f.status === 'open');
  }

  private resolvedForeshadows(): NovelForeshadow[] {
    const p: NovelProject | null = this.project;
    if (p === null) return [];
    return p.branchSettings.foreshadows.filter((f: NovelForeshadow): boolean => f.status === 'resolved');
  }

  async openNewMaterial(kind: NovelMaterialKind = 'world'): Promise<void> {
    if (this.busy || this.hasBlockingNovelRun()) return;
    const token: number = ++this.materialOpenToken;
    try {
      const snapshot: NovelWorkspaceSnapshot = await getNovelCreation().readWorkspaceSnapshot(this.projectId);
      if (!this.pageAlive || token !== this.materialOpenToken || this.busy || this.hasBlockingNovelRun()) return;
      const draft: NovelMaterialEditorDraft = emptyMaterialEditorDraft();
      draft.kind = kind;
      this.materialEditorDraft = draft;
      this.materialEditorCas = snapshot.status.cas;
      this.materialCreatedAt = Date.now();
      this.editingMaterialId = '';
      this.materialEditorKey = `new-${token}`;
      this.errorMsg = '';
      this.materialEditorOpen = true;
    } catch (error) { if (this.pageAlive && token === this.materialOpenToken) this.showWorkspaceOperationError('无法新建资料', error); }
  }

  async openEditMaterial(material: NovelMaterial, scope: NovelMaterialEditScope = 'branch'): Promise<void> {
    if (this.busy || this.hasBlockingNovelRun()) return;
    const token: number = ++this.materialOpenToken;
    try {
      const snapshot: NovelWorkspaceSnapshot = await getNovelCreation().readWorkspaceSnapshot(this.projectId);
      if (!this.pageAlive || token !== this.materialOpenToken || this.busy || this.hasBlockingNovelRun()) return;
      const effective: NovelMaterial | undefined = snapshot.project.materials.find((item: NovelMaterial): boolean => item.id === material.id);
      if (effective === undefined) throw new Error('此资料已不在当前分支，请刷新后再编辑');
      const base: NovelMaterial | undefined = snapshot.project.baseMaterials?.find((item: NovelMaterial): boolean => item.id === material.id);
      const source: NovelMaterial = scope === 'shared' && base !== undefined ? base : effective;
      this.materialEditorDraft = materialEditorDraftFor(source, scope);
      this.materialEditorCas = snapshot.status.cas;
      this.materialCreatedAt = source.createdAt;
      this.editingMaterialId = source.id;
      this.materialEditorKey = `${source.id}-${scope}-${token}`;
      this.errorMsg = '';
      this.materialEditorOpen = true;
    } catch (error) { if (this.pageAlive && token === this.materialOpenToken) this.showWorkspaceOperationError('无法编辑资料', error); }
  }

  async saveMaterial(draft: NovelMaterialEditorDraft): Promise<void> {
    if (this.busy || !this.materialEditorOpen || !this.pageAlive || this.hasBlockingNovelRun()) return;
    const cas: NovelWorkspaceCas | null = this.materialEditorCas;
    if (cas === null) { this.errorMsg = '资料编辑缺少工作区快照，请重新打开'; return; }
    this.busy = true;
    try {
      let savedId: string = this.editingMaterialId;
      const fields: NovelMaterialFields = { aliases: draft.aliases, tags: draft.tags,
        customKind: draft.customKind, injectionMode: draft.injectionMode };
      if (draft.scope === 'shared') {
        const material: NovelMaterial = makeNovelMaterial({ id: this.editingMaterialId,
          kind: draft.kind, title: draft.title, content: draft.content, enabled: draft.enabled,
          aliases: fields.aliases, tags: fields.tags, customKind: fields.customKind, injectionMode: fields.injectionMode, now: Date.now() });
        material.createdAt = this.materialCreatedAt;
        await getNovelCreation().saveSharedMaterial(this.projectId, material, cas);
        savedId = material.id;
      } else {
        const saved: NovelMaterial = await getNovelCreation().upsertMaterial(
          this.projectId, this.editingMaterialId.length > 0 ? this.editingMaterialId : null,
          draft.kind, draft.title, draft.content, draft.enabled, fields, cas);
        savedId = saved.id;
      }
      if (!this.pageAlive) return;
      this.materialEditorOpen = false;
      this.expandedMaterialId = savedId;
      this.errorMsg = '';
      await this.reload();
    } catch (error) { if (this.pageAlive) this.errorMsg = error instanceof Error ? error.message : String(error); }
    finally { if (this.pageAlive) this.busy = false; }
  }

  private confirmRestoreMaterialInheritance(material: NovelMaterial): void {
    if (this.busy || this.hasBlockingNovelRun()) return;
    const cas: NovelWorkspaceCas | null = this.workspaceStatus?.cas ?? null;
    if (cas === null) return;
    AlertDialog.show({ title: '恢复资料继承？',
      message: `当前分支重新使用“${material.title}”的最新共享底稿；此分支的覆盖或隐藏设置会移除，其他分支保持原样。`,
      primaryButton: { value: '取消', action: (): void => {} },
      secondaryButton: { value: '恢复继承', action: (): void => { this.restoreMaterialInheritance(material.id, cas); } } });
  }

  private async restoreMaterialInheritance(materialId: string, cas: NovelWorkspaceCas): Promise<void> {
    if (this.busy || this.hasBlockingNovelRun()) return;
    this.busy = true;
    try {
      await getNovelCreation().restoreMaterialInheritance(this.projectId, materialId, cas);
      await this.reload();
    } catch (error) { if (this.pageAlive) this.showWorkspaceOperationError('恢复资料继承失败', error); }
    finally { if (this.pageAlive) this.busy = false; }
  }

  private confirmDeleteMaterial(material: NovelMaterial): void {
    if (this.busy || this.hasBlockingNovelRun()) return;
    const cas: NovelWorkspaceCas | null = this.workspaceStatus?.cas ?? null;
    if (cas === null) return;
    const shared: boolean = this.project?.baseMaterials?.some((base: NovelMaterial): boolean => base.id === material.id) ?? false;
    AlertDialog.show({ title: shared ? '在当前分支移除此资料？' : '删除当前分支资料？',
      message: shared ? `当前分支将隐藏“${material.title}”。共享底稿和其他分支保留，可通过恢复继承重新显示。`
        : `删除当前分支的“${material.title}”，其他分支保持原样。`,
      primaryButton: { value: '取消', action: (): void => {} },
      secondaryButton: { value: '确认移除', fontColor: ERROR, action: (): void => { this.deleteMaterial(material.id, cas); } } });
  }

  async deleteMaterial(id: string, cas: NovelWorkspaceCas): Promise<void> {
    if (this.busy || this.hasBlockingNovelRun()) return;
    this.busy = true;
    try {
      await getNovelCreation().deleteMaterial(this.projectId, id, cas);
      await this.reload();
    } catch (e) {
      this.errorMsg = String(e);
    } finally {
      this.busy = false;
    }
  }

  async resolveSuggestion(id: string, accept: boolean): Promise<void> {
    if (this.busy || this.hasBlockingNovelRun()) return;
    if (accept) {
      this.openMaterialAdoption(id, false);
      return;
    }
    this.busy = true;
    try {
      await getNovelCreation().resolveMaterialSuggestion(this.projectId, id, false);
      await this.reload();
    } catch (e) {
      this.errorMsg = String(e);
    } finally {
      this.busy = false;
    }
  }

  async reanalyze(chapterId: string = ''): Promise<void> {
    const p: NovelProject | null = this.project;
    if (this.busy || this.hasBlockingNovelRun() || p === null || p.chapters.length === 0) return;
    this.busy = true;
    this.errorMsg = '';
    this.collectionFeedback = '';
    this.collectionFeedbackToken++;
    try {
      const chapter: NovelChapter | undefined = chapterId.length > 0
        ? p.chapters.find((item: NovelChapter): boolean => item.id === chapterId) : p.chapters[p.chapters.length - 1];
      if (chapter === undefined) return;
      await getNovelCreation().refreshMaterialSuggestions(this.projectId, chapter.id);
      await this.reload();
    } catch (e) {
      // 失败信息已由 domain 持久化到章上，重读后保留本次错误。
      await this.reload();
      if (this.pageAlive) this.errorMsg = this.materialAnalysisFailureText(String(e));
    } finally {
      this.busy = false;
    }
  }

  private openMaterialAdoption(sourceId: string, isSetting: boolean): void {
    const project: NovelProject | null = this.project;
    if (project === null || this.busy || this.hasBlockingNovelRun()) return;
    try {
      let baseline: NovelMaterialAdoption;
      let sourceFields: NovelMaterialFields;
      if (isSetting) {
        const source: NovelSettingProposal | undefined = project.settingProposals.find(
          (proposal: NovelSettingProposal): boolean => proposal.id === sourceId);
        if (source === undefined) return;
        baseline = buildSettingProposalAdoption(project, source, null, this.workspaceStatus?.activeBranchId ?? null);
        sourceFields = { aliases: source.aliases, tags: source.tags, customKind: source.customKind, injectionMode: source.injectionMode };
      } else {
        const source: NovelMaterialSuggestion | undefined = project.materialSuggestions.find(
          (suggestion: NovelMaterialSuggestion): boolean => suggestion.id === sourceId);
        if (source === undefined) return;
        baseline = buildSuggestionAdoption(project, source, null, this.workspaceStatus?.activeBranchId ?? null);
        sourceFields = { aliases: source.aliases, tags: source.tags, customKind: source.customKind, injectionMode: source.injectionMode };
      }
      this.adoptionBaseline = baseline;
      this.adoptionSourceId = sourceId;
      this.adoptionIsSetting = isSetting;
      this.adoptionKind = baseline.kind;
      this.adoptionTitle = baseline.title;
      this.adoptionBody = baseline.content;
      this.adoptionFields = sourceFields;
      this.adoptionTargets = project.materials.map((material: NovelMaterial): NovelMaterialAdoptionSheetTarget => {
        return { id: material.id, kind: material.kind, title: material.title, body: material.content, enabled: material.enabled,
          aliases: material.aliases, tags: material.tags, customKind: material.customKind, injectionMode: material.injectionMode };
      });
      this.adoptionTargetDigests = new Map();
      project.materials.forEach((material: NovelMaterial): void => {
        this.adoptionTargetDigests.set(material.id, materialAdoptionTargetDigest(material));
      });
      this.adoptionDraftKey = `${sourceId}-${Date.now()}`;
      this.adoptionError = '';
      this.adoptionOpen = true;
    } catch (e) {
      this.errorMsg = e instanceof Error ? e.message : String(e);
    }
  }

  private async confirmMaterialAdoption(
    kind: NovelMaterialKind, title: string, body: string, targetId: string | null, fields: NovelMaterialFields,
  ): Promise<void> {
    if (this.busy || this.adoptionBaseline === null) return;
    const digest: string | null = targetId === null ? null : this.adoptionTargetDigests.get(targetId) ?? null;
    if (targetId !== null && digest === null) {
      this.adoptionError = '目标不在本次预览中，请重新打开后选择';
      return;
    }
    const edit: NovelMaterialAdoption = {
      kind, title, content: body, targetMaterialId: targetId, targetDigest: digest,
      sourceDigest: this.adoptionBaseline.sourceDigest, branchId: this.adoptionBaseline.branchId,
      aliases: fields.aliases, tags: fields.tags, customKind: fields.customKind, injectionMode: fields.injectionMode,
    };
    this.busy = true;
    this.adoptionError = '';
    try {
      if (this.adoptionIsSetting) {
        await getNovelCreation().resolveSettingProposal(this.projectId, this.adoptionSourceId, true, edit);
      } else {
        await getNovelCreation().resolveMaterialSuggestion(this.projectId, this.adoptionSourceId, true, edit);
      }
      this.adoptionOpen = false;
      this.adoptionBaseline = null;
      this.errorMsg = '';
      await this.reload();
    } catch (e) {
      if (this.pageAlive) this.adoptionError = e instanceof Error ? e.message : String(e);
    } finally {
      this.busy = false;
    }
  }

  private cancelAdoption(): void {
    if (this.busy) return;
    this.adoptionOpen = false;
    this.adoptionBaseline = null;
  }

  // ===== 页签内容 =====
  @Builder
  novelMessageHeader(message: NovelMessage): void {
    if (message.role === 'assistant') {
      Text(this.assistantLabel(message))
        .fontSize(12)
        .fontColor(INK3)
        .height(18)
        .margin({ bottom: 4 })
    }
    if (message.clonedFromMessageId !== undefined) {
      Text('再次收录候选 · 来自已收录回复').fontSize(12).fontColor(INK3).lineHeight(18).width('100%')
    }
  }

  @Builder
  novelMessageFooter(message: NovelMessage): void {
    if (this.canRetryQuickStart(message)) {
      Text('重试快速开始').fontSize(13).fontColor(ACCENT).constraintSize({ minHeight: 44 })
        .enabled(!this.busy && !this.hasBlockingNovelRun())
        .onClick((): void => { this.retryQuickStartSeed(this.quickStartSeedFor(message)); })
    }
    if (message.role === 'assistant' && message.mode === 'write' && message.collectedChapterId === null &&
      (message.candidate === undefined || message.candidate.kind !== 'polish' || message.candidate.complete) &&
      novelMessageText(message).trim().length > 0) {
      Row() {
        Icon({ name: 'ph_bookOpen', iconSize: 14, color: ACCENT_INK })
        Text(message.interrupted ? '收进章节（部分）' : '收进章节')
          .fontSize(12).fontWeight(FontWeight.Medium).fontFamily(FONT_SANS_MEDIUM)
          .fontColor(ACCENT_INK).margin({ left: 6 })
      }
      .height(44)
      .padding({ left: 16, right: 16 })
      .borderRadius(22)
      .backgroundColor(ACCENT)
      .margin({ top: 6 })
      .alignItems(VerticalAlign.Center)
      .onClick((): void => { this.openCollect(message.id); })
      .accessibilityText(message.interrupted ? '收进章节，部分内容' : '收进章节')
    } else if (message.collectedChapterId !== null) {
      Row() {
        Icon({ name: 'check', iconSize: 13, color: SUCCESS_FG })
        Text('已收进章节').fontSize(12).fontFamily(FONT_SANS_MEDIUM).fontColor(SUCCESS_FG)
          .margin({ left: 5 })
      }
      .alignItems(VerticalAlign.Center).margin({ top: 10 })
      if (this.project !== null && canCloneCollectedMessage(this.project, message, this.workspaceStatus?.activeBranchId ?? '')) {
        Text('再次收录').fontSize(13).fontColor(ACCENT).constraintSize({ minHeight: 44 })
          .enabled(!this.busy && !this.hasBlockingNovelRun())
          .onClick((): void => { this.confirmCloneCollected(message); })
      }
    }
  }

  @Builder
  activeRunHeader(show: boolean = true): void {
    if (show) {
      Row() {
        Text(this.activeMessages.length > 0 ? '正在写' : '正在思考')
          .fontSize(12)
          .fontColor(ACCENT)
        if (this.activeFrameSeen) {
          Text(this.activeTransport === 'live' ? '实时' :
            (this.activeTransport === 'buffered' ? '缓冲' : '非流式'))
            .fontSize(10)
            .fontColor(INK3)
            .margin({ left: 8 })
        }
      }.height(18).alignItems(VerticalAlign.Center).margin({ bottom: 4 })
    }
  }

  @Builder
  private activeRunFooter(message: UIMessage): void {
    if (message.role === 'assistant' && this.activeRunKind !== 'discussion' && !this.activeQuickStart &&
      toText(message).trim().length > 0) {
      // The saved collection button is44vp plus6vp margin; reserve it while writing.
      Column() {}.height(50)
    }
  }

  @Builder
  emptyAgentRowSlot(): void {}

  // ForEach retains its first item; read the current immutable snapshot as Amber does.
  private activeRunMessage(seed: UIMessage): UIMessage {
    const current: UIMessage = this.activeMessages.find((message: UIMessage): boolean => message.id === seed.id) ?? seed;
    if (!this.activeQuickStart) return current;
    // Quick start generates structured JSON. Show its live thinking, then the saved overview.
    return makeUIMessage(current.role, current.parts.filter((part: UIMessagePart): boolean => part.type === 'reasoning'), {
      id: current.id, createdAt: current.createdAt, finishedAt: current.finishedAt, modelId: current.modelId,
    });
  }

  private activeRunTimelineMessages(): UIMessage[] {
    if (!this.activeQuickStart) return this.activeMessages;
    return this.activeMessages.filter((message: UIMessage): boolean => message.role === 'assistant' &&
      message.parts.some((part: UIMessagePart): boolean => part.type === 'reasoning'));
  }

  private novelTimelineMessages(): NovelMessage[] {
    if (this.project === null) return [];
    if (this.activeRunId.length === 0) return this.project.messages;
    const messages: NovelMessage[] = this.project.messages.slice(0, this.activeHistoryCount);
    if (!this.activeQuickStart) {
      for (const message of this.activeRunTimelineMessages()) {
        if (message.role !== 'user' && message.role !== 'assistant') continue;
        messages.push(makeNovelMessage({ id: message.id, role: message.role,
          mode: this.activeRunKind === 'discussion' ? 'discuss' : 'write', uiMessage: message,
          createdAt: this.activeRunCreatedAt, runKind: this.activeRunKind }));
      }
    }
    return messages;
  }

  private novelTimelineMessageLive(message: NovelMessage): boolean {
    return this.activeRunId.length > 0 && !this.activeQuickStart &&
      this.activeMessages.some((current: UIMessage): boolean => current.id === message.uiMessage.id);
  }

  private savedTimelineMessage(seed: NovelMessage): NovelMessage {
    return this.project?.messages.find((message: NovelMessage): boolean => message.id === seed.id) ?? seed;
  }

  private novelTimelineMessageUi(message: NovelMessage): UIMessage {
    return this.novelTimelineMessageLive(message) ? this.activeRunMessage(message.uiMessage) :
      this.projectMessageUi(this.savedTimelineMessage(message));
  }

  @Builder
  private novelTimelineMessageHeader(message: NovelMessage): void {
    if (this.novelTimelineMessageLive(message)) this.activeRunHeader(message.role === 'assistant');
    else this.novelMessageHeader(this.savedTimelineMessage(message));
  }

  @Builder
  private novelTimelineMessageFooter(message: NovelMessage): void {
    if (this.novelTimelineMessageLive(message)) this.activeRunFooter(this.novelTimelineMessageUi(message));
    else this.novelMessageFooter(this.savedTimelineMessage(message));
  }

  @Builder
  novelTimelineRows(): void {
    if (this.project !== null) {
      if (this.ordinaryRecoveryRecord() !== null && this.activeRunId.length === 0) {
        ListItem() {
          NovelRunRecoveryCard({
            recordKey: this.ordinaryRecoveryRecord()!.id,
            heading: this.ordinaryRecoveryRecord()!.status === 'failed' ? '上次生成失败' : '上次生成已中断',
            detail: this.ordinaryRecoveryDetail(this.ordinaryRecoveryRecord()!),
            userText: this.ordinaryRecoveryRecord()!.userText,
            partialText: this.ordinaryPartialText(this.ordinaryRecoveryRecord()!),
            busy: this.busy || this.hasBlockingNovelRun(),
            retryAllowed: this.ordinaryRecovery?.retryAllowed ?? false,
            resumeAllowed: this.ordinaryRecovery?.resumeAllowed ?? false,
            blockedReason: this.ordinaryRecovery?.blockedReason ?? '',
            errorMessage: this.ordinaryRecoveryRecord()!.error ?? '',
            retryLabel: this.ordinaryRecoveryRecord()!.originalRequest.operation.kind === 'tool_continuation' ? '重试工具续接' : '重试原请求',
            onRetry: (recordId: string): void => { this.recoverOrdinaryRun(recordId, 'retry'); },
            onResume: (recordId: string): void => { this.recoverOrdinaryRun(recordId, 'resume'); },
            onCopyPartial: (recordId: string, text: string): void => { this.copyOrdinaryPartial(recordId, text); },
          }).margin({ bottom: 10 })
        }
      }
      if (this.ghostwriteJob !== null) {
        ListItem() {
          NovelGhostwriteCard({
            job: this.ghostwriteJob,
            suggestedChapterCount: this.project?.branchSettings.suggestedChapterCount ?? DEFAULT_GHOSTWRITE_CHAPTER_COUNT,
            planReady: this.ghostwritePlanReady(),
            modelReady: this.ghostwriteModelsReady(),
            planDisabledReason: '请先在项目控制中保存本章计划。',
            modelDisabledReason: this.ghostwriteModelReason(),
            busy: this.ghostwriteBusy,
            onStart: (targetChapterCount: number): void => { this.startGhostwrite(targetChapterCount); },
            onPause: (): void => { this.pauseGhostwrite(); },
            onResume: (): void => { this.resumeGhostwrite(); },
            onRetry: (): void => { this.retryGhostwrite(); },
            onCancel: (): void => { this.cancelGhostwrite(); },
          })
          .margin({ bottom: 8 }).borderRadius(14).clip(true)
        }
      }
      if (this.project.messages.length === 0 && this.activeRunId.length === 0) {
        // 空态(iOS 纯文字):快速开始已前置到列表页创建 sheet
        ListItem() {
          Column() {
            Text('新的创作对话').fontSize(15).fontColor(INK2).width('100%')
            Text('在下方写下灵感，先梳理设定与开篇方向。')
              .fontSize(12).fontColor(INK3).margin({ top: 6 }).width('100%')
          }
          .alignItems(HorizontalAlign.Start)
          .width('100%').padding({ left: 16, right: 16, top: 12, bottom: 12 })
        }
      }
      if (this.ghostwriteJob === null && this.ghostwriteReport !== null) {
        ListItem() { NovelGhostwriteReportCard({ report: this.ghostwriteReport }) }
      }
      ForEach(this.novelTimelineMessages(), (message: NovelMessage): void => {
        if (discussionArchiveAtMessage(this.project?.discussionArchives ?? [], message) !== null) {
          ListItem() {
            NovelDiscussionArchiveCard({
              archive: discussionArchiveAtMessage(this.project?.discussionArchives ?? [], message)!,
              expanded: this.expandedArchiveIds.includes(discussionArchiveAtMessage(this.project?.discussionArchives ?? [], message)!.id),
              onToggle: (archiveId: string): void => { this.toggleDiscussionArchive(archiveId); },
            }).margin({ bottom: 8 })
          }
        }
        if (discussionMessageVisible(this.project?.discussionArchives ?? [], this.expandedArchiveIds, message)) {
          ListItem() {
            AgentMessageRow({
              message: this.novelTimelineMessageUi(message),
              capabilities: NOVEL_AGENT_MESSAGE_CAPABILITIES,
              hostHeader: (): void => { this.novelTimelineMessageHeader(message); },
              hostFooter: (): void => { this.novelTimelineMessageFooter(message); },
              streaming: this.novelTimelineMessageLive(message),
              textDeltasLive: this.activeTextDeltasLive,
              isLastAssistant: this.novelTimelineMessageLive(message) &&
                message.uiMessage.id === this.activeRunTimelineMessages()[this.activeRunTimelineMessages().length - 1]?.id,
              animateEntrance: false,
              onTextGrow: (): void => {
                if (this.novelTimelineMessageLive(message)) this.followNovelStreamingUpdate();
                else this.requestNovelNativeFollow();
              },
              onApproveTool: (toolCallId: string, locator?: ToolApprovalPartLocator): void => {
                this.continueNovelTool(toolCallId, { kind: 'approved' }, locator);
              },
              onDenyTool: (toolCallId: string, locator?: ToolApprovalPartLocator): void => {
                this.continueNovelTool(toolCallId, { kind: 'denied', reason: '用户拒绝了这次操作' }, locator);
              },
              onAnswerTool: (toolCallId: string, answer: string,
                locator?: ToolApprovalPartLocator): void => {
                this.continueNovelTool(toolCallId, { kind: 'answered', answer }, locator);
              },
            })
          }
        }
        ForEach(this.settingProposalsAt(message),
          (proposal: NovelSettingProposal): void => {
            this.novelSettingProposalRow(proposal)
          }, (proposal: NovelSettingProposal): string => proposal.id)
      }, (message: NovelMessage): string => `${message.id}:${message.collectedChapterId ?? ''}:${discussionMessageVisible(this.project?.discussionArchives ?? [], this.expandedArchiveIds, message)}`)
      ForEach(orphanSettingProposals(this.project.settingProposals, this.project.messages),
        (proposal: NovelSettingProposal): void => {
          this.novelSettingProposalRow(proposal)
        }, (proposal: NovelSettingProposal): string => proposal.id)
    }
    if (this.pendingUserText.length > 0 && this.activeMessages.length === 0) {
      ListItem() {
        AgentMessageRow({
          message: this.pendingUserUi(),
          capabilities: NO_AGENT_MESSAGE_ROW_CAPABILITIES,
          hostHeader: (): void => { this.emptyAgentRowSlot(); },
          hostFooter: (): void => { this.emptyAgentRowSlot(); },
          streaming: false,
        })
      }
    }
  }

  @Builder
  private novelSettingProposalRow(proposal: NovelSettingProposal): void {
    ListItem() {
      NovelSettingProposalCard({
        title: proposal.title,
        content: proposal.content,
        kindLabel: MATERIAL_KIND_LABELS[proposal.kind],
        status: proposal.status,
        onResolve: (accept: boolean): void => { this.resolveSettingProposal(proposal, accept); },
      })
    }
  }

  private settingProposalsAt(message: NovelMessage): NovelSettingProposal[] {
    return this.project === null ? [] : settingProposalsForMessage(this.project.settingProposals, message);
  }

  @Builder
  novelTimelineActiveTail(): void {
    ForEach(this.completedPreview, (message: UIMessage): void => {
      ListItem() {
        AgentMessageRow({ message,
          capabilities: NO_AGENT_MESSAGE_ROW_CAPABILITIES,
          hostHeader: (): void => { this.completedPreviewHeader(); },
          hostFooter: (): void => { this.completedPreviewFooter(); },
          streaming: false, animateEntrance: false,
        })
      }
    }, (message: UIMessage): string => `completed-preview-${message.id}`)
    ForEach(this.activeRunId.length > 0 ? [] : this.failedMessages, (message: UIMessage): void => {
      ListItem() {
        AgentMessageRow({
          message: this.unsavedDisplayMessage(message),
          capabilities: NO_AGENT_MESSAGE_ROW_CAPABILITIES,
          hostHeader: (): void => { this.unsavedMessageHeader(); },
          hostFooter: (): void => { this.unsavedMessageFooter(message); },
          streaming: false,
        })
      }
    }, (message: UIMessage): string => `unsaved-${message.id}`)
    if (this.activeRunId.length > 0) {
      if (this.activeQuickStart) {
        ListItem() {
          Text('正在整理角色、设定与开篇方向；完成后可逐项确认。')
            .fontSize(14).fontColor(INK3).lineHeight(21).width('100%').padding(16)
        }
      }
      if (this.activeRunTimelineMessages().length > 0) {
        ForEach(this.activeQuickStart ? this.activeRunTimelineMessages() : [], (message: UIMessage, index: number): void => {
          ListItem() {
            AgentMessageRow({
              message: this.activeRunMessage(message),
              capabilities: NOVEL_AGENT_MESSAGE_CAPABILITIES,
              hostHeader: (): void => { this.activeRunHeader(message.role === 'assistant'); },
              hostFooter: (): void => { this.activeRunFooter(this.activeRunMessage(message)); },
              streaming: true,
              textDeltasLive: this.activeTextDeltasLive,
              isLastAssistant: index === this.activeRunTimelineMessages().length - 1,
              onTextGrow: (): void => { this.followNovelStreamingUpdate(); },
              onApproveTool: (toolCallId: string, locator?: ToolApprovalPartLocator): void => {
                this.continueNovelTool(toolCallId, { kind: 'approved' }, locator);
              },
              onDenyTool: (toolCallId: string, locator?: ToolApprovalPartLocator): void => {
                this.continueNovelTool(toolCallId, { kind: 'denied', reason: '用户拒绝了这次操作' }, locator);
              },
              onAnswerTool: (toolCallId: string, answer: string,
                locator?: ToolApprovalPartLocator): void => {
                this.continueNovelTool(toolCallId, { kind: 'answered', answer }, locator);
              },
            })
          }
        }, (message: UIMessage): string => message.id)
      } else if (!this.activeQuickStart) {
        ListItem() {
          AgentMessageRow({
            message: this.activeStatusUi(),
            capabilities: NO_AGENT_MESSAGE_ROW_CAPABILITIES,
            hostHeader: (): void => { this.activeRunHeader(); },
            hostFooter: (): void => { this.emptyAgentRowSlot(); },
            streaming: true,
            textDeltasLive: false,
            isLastAssistant: true,
            onTextGrow: (): void => { this.followNovelStreamingUpdate(); },
          })
        }
      }
    }
  }

  @Builder
  private completedPreviewHeader(): void {
    Text('生成内容 · 项目尚未刷新').fontSize(12).fontColor(INK3).margin({ bottom: 4 })
  }

  @Builder
  private completedPreviewFooter(): void {
    Text('重新加载项目').fontSize(12).fontColor(ACCENT).constraintSize({ minHeight: 44 })
      .enabled(!this.busy && !this.hasBlockingNovelRun())
      .onClick((): void => { this.reload(); })
  }

  @Builder
  private unsavedMessageHeader(): void {
    Text('未保存的生成内容').fontSize(12).fontColor(ERROR).margin({ bottom: 4 })
  }

  @Builder
  private unsavedMessageFooter(message: UIMessage): void {
    if (toText(message).trim().length > 0) {
      Text('复制未保存内容').fontSize(12).fontColor(ACCENT).fontFamily(FONT_SANS_MEDIUM)
        .constraintSize({ minHeight: 44 }).padding({ left: 8, right: 8 })
        .onClick((): void => { this.copyUnsavedMessage(message); })
    }
  }

  private copyUnsavedMessage(message: UIMessage): void {
    try {
      const board: pasteboard.SystemPasteboard = pasteboard.getSystemPasteboard();
      board.setDataSync(pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, toText(message)));
      promptAction.showToast({ message: '已复制未保存内容', duration: 1400 });
    } catch (error) {
      this.showWorkspaceOperationError('复制失败', error);
    }
  }

  // ===== 项目控制 sheet(iOS writingContext:模式与偏好) =====

  private composeModeLabel(): string {
    if (this.composeMode === 'discuss') return '讨论';
    if (this.composeMode === 'continue') return '写一段';
    return '写整章';
  }

  private writingModelLabel(): string {
    const p: NovelProject | null = this.project;
    if (p === null) return getProductKind() === 'novel' ? '选择模型' : '跟随全局';
    const writing: NovelModelTarget = p.modelPolicy.writing;
    if (getProductKind() === 'novel') {
      if (!this.modelOptionsReady) return this.modelOptionsLoading ? '读取模型…' : '模型配置读取失败';
      const effective: NovelModelTarget = resolveNovelDefaultTarget(p.modelPolicy, this.novelModelDefaults, 'writing');
      const label: string = this.writingTargetLabel(effective);
      return writing.kind === 'global' ? `小说默认 · ${label}` : label;
    }
    if (writing.kind === 'global') return '跟随全局';
    const option: NovelModelOptionView | undefined = this.modelOptions.find(
      (o: NovelModelOptionView): boolean => o.providerId === writing.providerId && o.modelId === writing.modelId);
    return option !== undefined ? option.label : writing.modelId;
  }

  private writingTargetLabel(target: NovelModelTarget): string {
    if (target.kind === 'global') return this.chatModelLabel.length > 0 ? this.chatModelLabel : '未配置可用模型';
    const option: NovelModelOptionView | undefined = this.modelOptions.find(
      (item: NovelModelOptionView): boolean => item.providerId === target.providerId && item.modelId === target.modelId);
    if (option === undefined) return `模型已移除 · ${target.modelId}`;
    return `${option.label}${option.available ? '' : '（当前不可用）'}`;
  }

  private defaultWritingModelLabel(): string {
    return this.modelOptionsReady ? this.writingTargetLabel(this.novelModelDefaults.writing) : '读取模型配置后选择';
  }

  private openComposerModelSheet(): void {
    if (getProductKind() !== 'novel') { this.openControlSheet(); return; }
    if (this.project === null || this.busy || !this.pageAlive || !this.studioPageVisible || this.studioBackgrounded) return;
    this.composerModelSession++;
    this.composerModelError = '';
    this.composerModelSheetOpen = true;
    this.loadProjectModelOptions();
  }

  private composerModelSessionActive(session: number): boolean {
    return this.pageAlive && this.studioPageVisible && !this.studioBackgrounded &&
      this.composerModelSheetOpen && this.composerModelSession === session;
  }

  private closeComposerModelSheet(session: number, force: boolean = false): void {
    if (session !== this.composerModelSession || (this.busy && !force)) return;
    this.composerModelSheetOpen = false;
    this.composerModelSession++;
    this.composerModelError = '';
  }

  private async chooseComposerModel(target: NovelModelTarget, session: number): Promise<void> {
    const p: NovelProject | null = this.project;
    if (p === null || this.busy || !this.modelOptionsReady || this.modelOptionsLoading ||
      !this.composerModelSessionActive(session)) return;
    if (target.kind === 'fixed' && !this.modelOptions.some((option: NovelModelOptionView): boolean =>
      option.available && option.providerId === target.providerId && option.modelId === target.modelId)) return;
    this.composerModelError = '';
    await this.saveNovelModelPolicy({ writing: target, review: p.modelPolicy.review, stateSync: p.modelPolicy.stateSync });
    if (!this.composerModelSessionActive(session)) return;
    if (this.errorMsg.length > 0) this.composerModelError = this.errorMsg;
    else this.closeComposerModelSheet(session);
  }

  private composerModelTransition(card: boolean): TransitionEffect {
    if (this.studioBackgrounded || !this.studioPageVisible) return TransitionEffect.IDENTITY;
    if (!card || this.reduceMotion) return TransitionEffect.OPACITY.animation({ duration: 160 });
    return TransitionEffect.asymmetric(
      TransitionEffect.move(TransitionEdge.BOTTOM).combine(TransitionEffect.OPACITY)
        .animation({ duration: 320, curve: Curve.EaseOut }),
      TransitionEffect.move(TransitionEdge.BOTTOM).combine(TransitionEffect.OPACITY)
        .animation({ duration: 250, curve: Curve.EaseIn }));
  }

  private workspaceSubtitle(): string {
    const mode: string = this.ghostwriteJob !== null ? '代笔' : '共创';
    const branch: string = this.workspaceStatus !== null ? this.workspaceStatus.activeBranchName : '';
    return `${mode} · ${branch} · ${this.writingModelLabel()}`;
  }

  // 创作方式 menu(讨论/写一段/写整章 + 归档当前讨论;iOS 意图 menu 同款)
  private showComposeModeMenu(): void {
    if (this.busy || this.hasBlockingNovelRun()) return;
    promptAction.showActionMenu({
      title: '创作方式',
      buttons: [
        { text: '讨论', color: $r('sys.color.ohos_id_color_text_primary') },
        { text: '写一段', color: $r('sys.color.ohos_id_color_text_primary') },
        { text: '写整章', color: $r('sys.color.ohos_id_color_text_primary') },
        { text: '归档当前讨论', color: $r('sys.color.ohos_id_color_text_primary') },
      ],
    }).then((result: ExportMenuResult): void => {
      if (result.index === 0) this.composeMode = 'discuss';
      else if (result.index === 1) this.composeMode = 'continue';
      else if (result.index === 2) this.composeMode = 'whole_chapter';
      else if (result.index === 3) this.archiveDiscussion();
      if (result.index >= 0 && result.index <= 2) this.flushComposerDraft();
    }).catch((): void => {});
  }

  private async openControlSheet(): Promise<void> {
    const p: NovelProject | null = this.project;
    if (p === null || this.busy) return;
    this.retainControlPlanDraft();
    const session: number = ++this.controlPlanSession;
    const owner: string = composerOwner(this.projectId, this.workspaceStatus?.activeBranchId ?? '');
    this.controlPlanOwner = owner;
    this.controlPlanBaseline = {
      thisChapterPlan: p.branchSettings.thisChapterPlan, futurePlan: p.branchSettings.futurePlan,
      preferences: p.branchSettings.preferences,
    };
    this.controlTab = this.ghostwriteJob !== null ? 1 : 0;
    this.controlNotice = '';
    this.controlPlanError = '';
    this.controlPlanReady = false;
    this.controlSheetOpen = true;
    const retained: ControlPlanDraft | undefined = this.controlPlanDrafts.get(owner);
    if (retained !== undefined) {
      this.showControlPlanDraft(retained, !sameControlPlanDraft(retained, this.controlPlanBaseline));
      // 缓存可能来自失败的自动保存；重开时再落盘，不能用内存恢复提示掩盖失败。
      this.persistControlPlanDraft(owner, sameControlPlanDraft(retained, this.controlPlanBaseline) ? null : retained);
      return;
    }
    this.showControlPlanDraft(this.controlPlanBaseline, false);
    this.controlPlanReady = false;
    this.controlPlanLoading = true;
    try {
      const saved: ControlPlanDraft | null = await this.planDraftStore.load(owner);
      if (!this.pageAlive || !this.controlSheetOpen || session !== this.controlPlanSession) return;
      const restored: boolean = saved !== null && !sameControlPlanDraft(saved, this.controlPlanBaseline);
      if (restored && saved !== null) this.controlPlanDrafts.set(owner, saved);
      this.showControlPlanDraft(restored && saved !== null ? saved : this.controlPlanBaseline, restored);
    } catch (error) {
      if (this.pageAlive && session === this.controlPlanSession) {
        this.controlPlanError = '读取计划草稿失败：' + String(error);
      }
    } finally {
      if (this.pageAlive && session === this.controlPlanSession) this.controlPlanLoading = false;
    }
  }

  private showControlPlanDraft(plan: ControlPlanDraft, restored: boolean): void {
    this.planThisChapter = plan.thisChapterPlan;
    this.planFuture = plan.futurePlan;
    this.planPreferences = plan.preferences;
    this.controlPlanReady = true;
    this.controlPlanLoading = false;
    this.controlNotice = restored ? '已恢复未保存的计划草稿，请核对后保存。' : '';
  }

  private closeControlSheet(): void {
    if (!this.controlSheetOpen) return;
    this.retainControlPlanDraft();
    this.controlPlanSession++;
    this.controlSheetOpen = false;
    this.controlPlanLoading = false;
  }

  private changeControlPlan(field: string, value: string): void {
    if (!this.controlPlanReady || this.busy) return;
    if (field === 'chapter') this.planThisChapter = value;
    else if (field === 'future') this.planFuture = value;
    else this.planPreferences = value;
    this.retainControlPlanDraft();
  }

  private retainControlPlanDraft(): void {
    if (this.controlPlanOwner.length === 0 || !this.controlPlanReady) return;
    const owner: string = this.controlPlanOwner;
    const plan: ControlPlanDraft = {
      thisChapterPlan: this.planThisChapter, futurePlan: this.planFuture, preferences: this.planPreferences,
    };
    const previous: ControlPlanDraft | undefined = this.controlPlanDrafts.get(owner);
    const clean: boolean = sameControlPlanDraft(plan, this.controlPlanBaseline);
    if (clean && previous === undefined) return;
    if (!clean && previous !== undefined && sameControlPlanDraft(plan, previous)) return;
    // 清理落盘成功前保留撤回后的基线，失败重开时不能复活旧磁盘草稿。
    this.controlPlanDrafts.set(owner, plan);
    this.persistControlPlanDraft(owner, clean ? null : plan);
  }

  private persistControlPlanDraft(owner: string, plan: ControlPlanDraft | null): void {
    const revision: number = ++this.controlPlanWriteRevision;
    const clearing: ControlPlanDraft | undefined = this.controlPlanDrafts.get(owner);
    this.planDraftStore.save(owner, plan).then((): void => {
      if (plan === null && this.controlPlanDrafts.get(owner) === clearing) this.controlPlanDrafts.delete(owner);
      if (this.pageAlive && this.controlPlanOwner === owner && revision === this.controlPlanWriteRevision) {
        this.controlPlanError = '';
        this.controlNotice = plan === null ? '' : '草稿已保存，尚未应用到当前分支。';
      }
    }).catch((error: Object): void => {
      if (this.pageAlive && this.controlPlanOwner === owner && revision === this.controlPlanWriteRevision) {
        this.controlPlanError = '计划草稿保存失败，当前输入仍保留：' + String(error);
      }
    });
  }

  private retryControlPlanDraft(): void {
    if (!this.controlPlanReady || this.busy) return;
    const plan: ControlPlanDraft = {
      thisChapterPlan: this.planThisChapter, futurePlan: this.planFuture, preferences: this.planPreferences,
    };
    this.persistControlPlanDraft(this.controlPlanOwner, sameControlPlanDraft(plan, this.controlPlanBaseline) ? null : plan);
  }

  private selectWritingTarget(target: NovelModelTarget): void {
    if (this.composerModelSheetOpen) {
      this.chooseComposerModel(target, this.composerModelSession);
      return;
    }
    const p: NovelProject | null = this.project;
    if (p === null || this.busy) return;
    this.saveNovelModelPolicy({
      writing: target, review: p.modelPolicy.review, stateSync: p.modelPolicy.stateSync,
    });
  }

  private writingTargetSelected(option: NovelModelOptionView | null): boolean {
    const p: NovelProject | null = this.project;
    if (p === null) return false;
    const writing: NovelModelTarget = p.modelPolicy.writing;
    if (option === null) return writing.kind === 'global';
    if (writing.kind !== 'fixed') return false;
    return writing.providerId === option.providerId && writing.modelId === option.modelId;
  }

  private async savePlanPrefs(): Promise<void> {
    const p: NovelProject | null = this.project;
    if (p === null || this.busy || !this.controlPlanReady) return;
    const owner: string = this.controlPlanOwner;
    const s: NovelBranchSettings = p.branchSettings;
    const savedPlan: ControlPlanDraft = {
      thisChapterPlan: this.planThisChapter, futurePlan: this.planFuture, preferences: this.planPreferences,
    };
    const settings: NovelBranchSettings = this.branchSettingsWith(s.foreshadows, s.confirmedDecisions);
    settings.thisChapterPlan = savedPlan.thisChapterPlan;
    settings.futurePlan = savedPlan.futurePlan;
    settings.preferences = savedPlan.preferences;
    await this.saveNovelBranchSettings(settings);
    if (this.errorMsg.length === 0) {
      this.controlPlanDrafts.set(owner, savedPlan);
      if (this.controlPlanOwner === owner) {
        this.controlPlanBaseline = savedPlan;
        this.controlNotice = '已保存到当前分支';
      }
      this.busy = true;
      try {
        await this.planDraftStore.save(owner, null);
        if (this.controlPlanDrafts.get(owner) === savedPlan) this.controlPlanDrafts.delete(owner);
        if (this.pageAlive && this.controlPlanOwner === owner) this.controlPlanError = '';
      } catch (error) {
        if (this.pageAlive && this.controlPlanOwner === owner) {
          this.controlPlanError = '正式计划已保存，但清理草稿失败：' + String(error);
        }
      } finally {
        this.busy = false;
      }
    }
  }

  // 写作模型选项行(跟随全局 + 固定模型;iOS ComposerModelSheet 的「跟随小说默认」)
  @Builder
  private ModelRow(label: string, detail: string, selected: boolean, available: boolean, isLast: boolean,
    target: NovelModelTarget, session: number = 0): void {
    Column() {
      Row() {
        Column({ space: 2 }) {
          Text(label).fontSize(15).fontFamily(FONT_SANS_MEDIUM).fontColor(available ? INK : INK4)
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          if (detail.length > 0) {
            Text(detail).fontSize(12).fontColor(INK3).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          }
        }.alignItems(HorizontalAlign.Start).layoutWeight(1)
        if (selected) {
          Icon({ name: 'check', iconSize: 16, color: ACCENT })
        }
      }
      .width('100%').alignItems(VerticalAlign.Center).constraintSize({ minHeight: 52 })
      .padding({ left: 14, right: 14, top: 10, bottom: 10 })
      .opacity(available ? 1 : 0.55)
      .enabled(available && !this.busy && (!this.composerModelSheetOpen ||
        (this.modelOptionsReady && !this.modelOptionsLoading)))
      .onClick((): void => {
        if (session > 0) this.chooseComposerModel(target, session);
        else this.selectWritingTarget(target);
      })
      if (!isLast) {
        Row() {
          Row().width('100%').height(1).backgroundColor(LINE)
        }.width('100%').padding({ left: 14, right: 14 })
      }
    }.width('100%')
  }

  @Builder
  private ModelRows(session: number = 0): void {
    this.ModelRow(getProductKind() === 'novel' ? '跟随小说默认' : '跟随聊天全局模型',
      getProductKind() === 'novel' ? this.defaultWritingModelLabel() : '写作使用应用聊天当前模型',
      this.writingTargetSelected(null), true,
      this.modelOptions.length === 0, { kind: 'global' }, session)
    ForEach(this.modelOptions, (option: NovelModelOptionView, idx: number): void => {
      this.ModelRow(option.label,
        option.available ? option.providerLabel : `${option.providerLabel} · 当前不可用`,
        this.writingTargetSelected(option), option.available, idx === this.modelOptions.length - 1,
        { kind: 'fixed', providerId: option.providerId, modelId: option.modelId }, session)
    }, (option: NovelModelOptionView): string => `${option.providerId}-${option.modelId}`)
  }

  @Builder
  private ComposerModelSheet(session: number): void {
    Column() {
      Blank().onClick((): void => { this.closeComposerModelSheet(session); })
      Column() {
        Row() {
          Text('创作模型').fontSize(18).fontWeight(FontWeight.Medium).fontColor(INK).layoutWeight(1)
          Text('完成').fontSize(15).fontColor(ACCENT).constraintSize({ minWidth: 48, minHeight: 44 })
            .textAlign(TextAlign.End).enabled(!this.busy)
            .onClick((): void => { this.closeComposerModelSheet(session); })
        }.width('100%').padding({ left: 20, right: 20, top: 10, bottom: 4 })
        Scroll() {
          Column() {
            if (this.modelOptionsLoading) {
              Text('正在读取模型…').fontSize(13).fontColor(INK3).padding(20)
            } else if (this.modelOptionsError.length > 0) {
              Text(this.modelOptionsError).fontSize(13).fontColor(ERROR).width('100%').padding(20)
              Text('重试').fontSize(15).fontColor(ACCENT).constraintSize({ minHeight: 44 })
                .onClick((): void => { if (this.composerModelSessionActive(session)) this.loadProjectModelOptions(); })
            } else {
              this.ModelRows(session)
              if (this.modelOptions.length === 0) {
                Text('暂无模型，请先添加模型服务。').fontSize(13).fontColor(INK3).width('100%').padding(20)
              }
            }
            if (this.composerModelError.length > 0) {
              Text(this.composerModelError).fontSize(13).fontColor(ERROR).width('100%').padding(20)
            }
            if (this.busy) { Text('保存中…').fontSize(13).fontColor(INK3).padding(12) }
          }.width('100%').alignItems(HorizontalAlign.Start)
        }.constraintSize({ maxHeight: 420 }).scrollBar(BarState.Auto)
      }.width('100%').backgroundColor(SURFACE).borderRadius({ topLeft: 20, topRight: 20 })
        .padding({ bottom: Math.max(16, this.navBarHeight) }).transition(this.composerModelTransition(true))
    }.width('100%').height('100%').backgroundColor(OVERLAY)
      .justifyContent(FlexAlign.End).transition(this.composerModelTransition(false))
  }

  private consumeContextOverrides(): void {
    const params: WorkspaceReturnParams = router.getParams() as WorkspaceReturnParams;
    const raw: string | undefined = params?.novelContextOverrides;
    if (raw === undefined) return;
    try {
      const result: NovelContextOverrideReturn = JSON.parse(raw) as NovelContextOverrideReturn;
      if (result.receiptToken === this.lastContextReceiptToken) return;
      this.lastContextReceiptToken = result.receiptToken;
      if (result.projectId !== this.projectId || result.branchId !== this.workspaceStatus?.activeBranchId) {
        this.contextOverrides = null;
        this.errorMsg = '上下文选择来自其他项目或分支，请重新预览';
        return;
      }
      this.contextOverrides = { branchId: result.branchId, forceIncludeMaterialIds: result.forceIncludeMaterialIds,
        forceExcludeMaterialIds: result.forceExcludeMaterialIds };
    } catch (error) { this.errorMsg = `无法读取本次上下文选择：${String(error)}`; }
  }

  private openContextPreview(): void {
    if (this.busy || this.hasBlockingNovelRun() || this.project === null) return;
    this.controlSheetOpen = false;
    router.pushUrl({ url: 'pages/NovelContextPreviewPage', params: {
      projectId: this.projectId, userText: this.draft, mode: this.composeMode === 'discuss' ? 'discuss' : 'write',
      granularity: this.composeMode === 'continue' ? 'continuation' : this.composeMode === 'whole_chapter' ? 'whole_chapter' : '',
      forceIncludeMaterialIds: JSON.stringify(this.contextOverrides?.forceIncludeMaterialIds ?? []),
      forceExcludeMaterialIds: JSON.stringify(this.contextOverrides?.forceExcludeMaterialIds ?? []),
    } }).catch((error: Error): void => { if (this.pageAlive) this.errorMsg = String(error); });
  }

  @Builder
  private GhostwriteStartPreviewSheet(preview: NovelGhostwriteStartPreview): void {
    NovelGhostwriteStartSheet({
      planContent: this.ghostwriteAuthorPlanPreview(preview), branchName: preview.branchName,
      chapterCount: preview.targetChapterCount, busy: this.ghostwriteBusy,
      errorMessage: this.ghostwritePreviewError, bottomPadding: Math.max(this.navBarHeight, 12),
      onConfirm: (): void => { this.confirmGhostwriteStart(); },
      onClose: (): void => { if (!this.ghostwriteBusy) this.ghostwritePreview = null; },
    })
  }

  @Builder
  private ControlOps(): void {
    CardRow({ label: '预览本次上下文', desc: '查看资料选择与预算，只调整下一次普通创作',
      onRowClick: (): void => { this.openContextPreview(); } })
      .enabled(!this.busy && !this.hasBlockingNovelRun())
      .opacity(this.busy || this.hasBlockingNovelRun() ? 0.5 : 1)
    CardRow({ label: '正文操作与修改建议', desc: '按段改写、回退最近章节、删除多章与确认预览',
      onRowClick: (): void => { this.openProjectPage('pages/NovelOperationsPage'); } })
      .enabled(!this.busy && !this.hasBlockingNovelRun()).opacity(this.busy || this.hasBlockingNovelRun() ? 0.5 : 1)
    CardRow({ label: '润色与结果', desc: '任意选章、项目润色偏好、逐章结果与失败重试',
      onRowClick: (): void => { this.openPolishPage(); } }).enabled(!this.busy)
    CardRow({ label: '本章计划与走向', desc: '本章合同、确认计划与后续最多八个走向',
      onRowClick: (): void => { this.openProjectPage('pages/NovelChapterContractPage'); } })
      .enabled(!this.busy && !this.hasBlockingNovelRun()).opacity(this.busy || this.hasBlockingNovelRun() ? 0.5 : 1)
    CardRow({ label: '剧情状态与人物经历', desc: '原文证据、身份确认、救援进度与重试',
      onRowClick: (): void => { this.openProjectPage('pages/NovelStatePage'); } })
      .enabled(!this.busy && !this.hasBlockingNovelRun()).opacity(this.busy || this.hasBlockingNovelRun() ? 0.5 : 1)
    if (this.ghostwriteJob?.stage === 'paused' || this.ghostwriteJob?.stage === 'failed') {
      CardRow({ label: '按要求返修代笔', desc: '保留当前候选作为来源，只对下一稿生效',
        onRowClick: (): void => { this.openGhostwriteBrief(); } }).enabled(!this.ghostwriteBusy)
    }
    CardRow({ label: '项目创建设置', desc: '创作方式、原始种子与项目润色偏好',
      onRowClick: (): void => {
        if (this.busy || this.hasBlockingNovelRun()) return;
        this.controlSheetOpen = false;
        router.pushUrl({ url: 'pages/NovelProjectSetupPage', params: { projectId: this.projectId } })
          .catch((error: Error): void => { if (this.pageAlive) this.errorMsg = String(error); });
      } })
      .enabled(!this.busy && !this.hasBlockingNovelRun())
      .opacity(this.busy || this.hasBlockingNovelRun() ? 0.5 : 1)
    CardRow({ label: '分支管理', desc: '创建、切换与撤销分支',
      onRowClick: (): void => {
        if (this.busy) return;
        this.controlSheetOpen = false;
        this.branchSheetOpen = true;
      } })
      .enabled(!this.busy)
      .opacity(this.busy ? 0.5 : 1)
    CardRow({ label: '导出作品', desc: '公开工作区 / 完整备份 / TXT / Markdown / EPUB', isLast: true,
      onRowClick: (): void => {
        this.controlSheetOpen = false;
        this.showExportMenu();
      } })
  }

  @Builder
  private SheetLabel(label: string): void {
    Text(label).fontSize(12).fontFamily(FONT_SANS_MEDIUM).fontColor(INK3)
      .width('100%').margin({ bottom: 8 })
  }

  // 项目控制 sheet(顶栏 principal 触发;bottom sheet 85%,内容可滚)
  @Builder
  private ControlSheet(): void {
    Column() {
      Blank().onClick((): void => { this.closeControlSheet(); })
      Column() {
        Row() {
          Column({ space: 2 }) {
            Text('项目控制').fontSize(17).fontWeight(FontWeight.Bold).fontFamily(FONT_SANS_MEDIUM).fontColor(INK)
            Text(this.project !== null ? this.project.name : '').fontSize(12).fontColor(INK3)
              .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          }.alignItems(HorizontalAlign.Start).layoutWeight(1)
          Text('完成').fontSize(15).fontFamily(FONT_SANS_MEDIUM).fontColor(ACCENT)
            .constraintSize({ minWidth: 44, minHeight: 44 }).textAlign(TextAlign.End)
            .onClick((): void => { this.closeControlSheet(); })
        }.width('100%').alignItems(VerticalAlign.Center).padding({ bottom: 10 })
        Scroll() {
          Column() {
            this.SheetLabel('创作模式')
            Seg({ options: ['共创', '代笔'], selected: this.controlTab,
              onSelect: (idx: number): void => {
                animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
                  this.controlTab = idx;
                });
              } })
            Column() {
              if (this.controlTab === 1) {
                Column() {
                  NovelGhostwriteCard({
                    job: this.ghostwriteJob,
            suggestedChapterCount: this.project?.branchSettings.suggestedChapterCount ?? DEFAULT_GHOSTWRITE_CHAPTER_COUNT,
                    planReady: this.ghostwritePlanReady(),
                    modelReady: this.ghostwriteModelsReady(),
                    planDisabledReason: '请先保存本章计划。',
                    modelDisabledReason: this.ghostwriteModelReason(),
                    busy: this.ghostwriteBusy,
                    onStart: (targetChapterCount: number): void => { this.startGhostwrite(targetChapterCount); },
                    onPause: (): void => { this.pauseGhostwrite(); },
                    onResume: (): void => { this.resumeGhostwrite(); },
                    onRetry: (): void => { this.retryGhostwrite(); },
                    onCancel: (): void => { this.cancelGhostwrite(); },
                  })
                }
                .width('100%').margin({ top: 10 }).borderRadius(14).clip(true)
                .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: -8 }))
                  .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
              } else {
                Text('与 Amber 逐段讨论、逐章收录正文。').fontSize(12).fontColor(INK3)
                  .width('100%').margin({ top: 8 })
                  .transition(TransitionEffect.OPACITY.animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
              }
            }.width('100%').clip(true)

            Column() {}.height(16)
            this.SheetLabel('写作模型')
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.ModelRows(); } })

            Column() {}.height(16)
            this.SheetLabel('本章计划')
            Text('编辑内容自动保存为草稿，点击保存才应用到当前分支。').fontSize(12).fontColor(INK3)
              .width('100%').margin({ bottom: 8 })
            if (this.controlPlanLoading) { Text('正在读取计划草稿…').fontSize(12).fontColor(INK3).padding(8) }
            if (this.controlNotice.length > 0) { Text(this.controlNotice).fontSize(12).fontColor(INK3).width('100%').margin({ bottom: 8 }) }
            if (this.controlPlanError.length > 0) {
              Text(this.controlPlanError).fontSize(12).fontColor(ERROR).width('100%').margin({ bottom: 8 })
              Text(this.controlPlanReady ? '重试保存草稿' : '重试读取草稿').fontSize(13).fontColor(ACCENT).height(44)
                .onClick((): void => {
                  if (this.controlPlanReady) this.retryControlPlanDraft();
                  else this.openControlSheet();
                })
            }
            TextArea({ text: this.planThisChapter, placeholder: '本章的位置、目标冲突、必须发生与不能发生…' })
              .width('100%').height(100).fontSize(13).fontColor(INK).placeholderColor(INK4)
              .backgroundColor(SURFACE2).borderRadius(12).padding(12)
              .enabled(!this.busy && this.controlPlanReady)
              .onChange((v: string): void => { this.changeControlPlan('chapter', v); })
            Column() {}.height(12)
            this.SheetLabel('往后计划')
            TextArea({ text: this.planFuture, placeholder: '接下来几章的方向…' })
              .width('100%').height(80).fontSize(13).fontColor(INK).placeholderColor(INK4)
              .backgroundColor(SURFACE2).borderRadius(12).padding(12)
              .enabled(!this.busy && this.controlPlanReady)
              .onChange((v: string): void => { this.changeControlPlan('future', v); })
            Column() {}.height(12)
            this.SheetLabel('写作偏好')
            TextArea({ text: this.planPreferences, placeholder: '文风、视角、节奏偏好…' })
              .width('100%').height(80).fontSize(13).fontColor(INK).placeholderColor(INK4)
              .backgroundColor(SURFACE2).borderRadius(12).padding(12)
              .enabled(!this.busy && this.controlPlanReady)
              .onChange((v: string): void => { this.changeControlPlan('preferences', v); })
            Row() {
              Blank()
              Text(this.busy ? '保存中…' : '保存').fontSize(14).fontWeight(FontWeight.Medium)
                .fontFamily(FONT_SANS_MEDIUM).fontColor(this.busy ? INK4 : ACCENT)
                .constraintSize({ minWidth: 44, minHeight: 44 }).textAlign(TextAlign.End)
                .enabled(!this.busy && this.controlPlanReady)
                .onClick((): void => { this.savePlanPrefs(); })
            }.width('100%').margin({ top: 6 })

            Column() {}.height(16)
            this.SheetLabel('操作')
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.ControlOps(); } })
            Column() {}.height(24)
          }
          .width('100%').alignItems(HorizontalAlign.Start)
        }
        .width('100%').layoutWeight(1).align(Alignment.TopStart).scrollBar(BarState.Off)
      }
      .width('100%').height('85%')
      .padding({ left: 18, right: 18, top: 16, bottom: 14 + this.navBarHeight })
      .backgroundColor(SURFACE).borderRadius({ topLeft: 20, topRight: 20 })
      .onClick((): void => {})
      .transition(getProductKind() === 'novel' ? this.composerModelTransition(true) :
        TransitionEffect.translate({ y: 160 }).animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
    }
    .width('100%').height('100%').backgroundColor(OVERLAY)
    .justifyContent(FlexAlign.End)
    .transition(getProductKind() === 'novel' ? this.composerModelTransition(false) :
      TransitionEffect.OPACITY.animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
  }

  @Builder
  emptyTimelineSlot(): void {}

  @Builder
  chatPane(): void {
    Column() {
    Column() {
      Stack({ alignContent: Alignment.BottomEnd }) {
      // Chat 与 Novel 共享同一 Timeline/AgentMessageRow；Timeline 独占 16vp 水平 gutter。
      InteractiveMessageTimeline({
        scroller: this.chatScroller,
        horizontalGutter: 16,
        topPadding: 8,
        bottomPadding: 0,
        cachedItems: 5,
        showOlderLoader: false,
        showDivider: false,
        showScrollBar: false,
        rows: (): void => { this.novelTimelineRows(); },
        activeTail: (): void => { this.novelTimelineActiveTail(); },
        hostTail: (): void => { this.emptyTimelineSlot(); },
        onTimelineAppear: (): void => {
          if (this.initialScrollArmed) {
            this.initialScrollArmed = false;
            this.scheduleNovelScrollToBottom();
          }
        },
        onViewportHeightChange: (height: number): void => {
          this.novelViewportHeight = height;
          if (!this.initialScrollArmed) this.requestNovelNativeFollow();
        },
        onVisibleRangeChange: (_start: number, _end: number): void => {
          this.novelLastVisibleIndex = _end;
          if (this.initialScrollArmed) {
            this.initialScrollArmed = false;
            this.scheduleNovelScrollToBottom();
          }
        },
        onTimelineTouch: (event: TouchEvent): void => {
          if (event.type === TouchType.Down) {
            this.userTouchActive = true;
            this.novelScrollToken++;
            this.nativeFollow?.cancel();
          }
          if (event.type === TouchType.Up || event.type === TouchType.Cancel) {
            this.userTouchActive = false;
            if (this.novelUserScrolling) this.reconcileNovelUserScroll();
            else this.requestNovelNativeFollow();
          }
        },
        onTimelineWillScroll: (offset: number, _state: ScrollState, source: ScrollSource): void => {
          if (offset !== 0 && (source === ScrollSource.DRAG || source === ScrollSource.FLING)) {
            this.novelUserScrolling = true;
            this.nativeFollow?.cancel();
          }
        },
        onTimelineScroll: (_offset: number, state: ScrollState): void => {
          if (this.novelUserScrolling &&
            (state === ScrollState.Scroll || state === ScrollState.Fling)) {
            this.reconcileNovelUserScroll();
          }
        },
        onTimelineScrollStop: (): void => {
          if (this.novelUserScrolling) {
            this.novelUserScrolling = false;
            this.reconcileNovelUserScroll();
          }
        },
      })
      .width('100%')
      .height('100%')
      .clip(true)
      this.novelJumpButton()
      }
      .layoutWeight(1)
      .width('100%')

      // 输入器(复用聊天页 ChatInput;meta 行 = 模型 chip + 创作方式 menu,iOS 同款)
      Column() {
        Row() {
          Text(this.writingModelLabel())
            .fontSize(11).fontFamily(FONT_MONO).fontColor(INK3)
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
            .constraintSize({ minHeight: 44, maxWidth: '50%' })
            .padding({ left: 10, right: 10 })
            .backgroundColor(SURFACE2).borderRadius(12)
            .enabled(this.project !== null && !this.busy)
            .onClick((): void => { this.openComposerModelSheet(); })
            .accessibilityText(getProductKind() === 'novel' ? '创作模型，选择模型' : '写作模型，打开项目控制')
          Blank()
          Text(`${this.composeModeLabel()} ▾`)
            .fontSize(12).fontFamily(FONT_SANS_MEDIUM).fontColor(INK2)
            .maxLines(1)
            .constraintSize({ minHeight: 44 })
            .padding({ left: 12, right: 12 })
            .backgroundColor(SURFACE2).borderRadius(12)
            .opacity(this.hasBlockingNovelRun() ? 0.5 : 1)
            .onClick((): void => { this.showComposeModeMenu(); })
            .accessibilityText('创作方式')
        }
        .width('100%').margin({ bottom: 6 }).alignItems(VerticalAlign.Center)

        if (this.failedInput.length > 0) {
          Row({ space: 8 }) {
            Text('可恢复本次输入，重新发送时会读取当前书稿。')
              .fontSize(11).fontColor(INK3).layoutWeight(1).lineHeight(16)
            Text(this.failedInputRunKind === 'quick_start' ? '重试快速开始' : '恢复输入').fontSize(12).fontColor(ACCENT).fontFamily(FONT_SANS_MEDIUM)
              .constraintSize({ minHeight: 44 }).padding({ left: 8, right: 8 })
              .enabled(!this.hasBlockingNovelRun())
              .onClick((): void => { this.restoreFailedInput(); })
          }.width('100%').alignItems(VerticalAlign.Center)
        }
        ChatInput({
          text: this.draft,
          placeholder: this.composerPlaceholder(),
          showAttachments: false,
          showCoreSlashCommands: false,
          sending: this.activeRunId.length > 0 || this.polishRuns() || this.ghostwriteRuns(),
          disabled: this.busy || this.sendStarting || this.composerRestoring ||
            (this.novelJobLocksWorkspace() && !this.ghostwriteRuns() && !this.polishRuns()),
          onTextChange: (v: string): void => { this.setComposerDraft(v); },
          onSend: (): void => { this.send(); },
          onStop: (): void => {
            if (this.polishRuns()) this.pausePolish();
            else if (this.ghostwriteRuns()) this.pauseGhostwrite();
            else this.interrupt();
          },
          onClearInput: (): void => { this.setComposerDraft(''); },
        })
      }
      // 全屏布局下手势条悬浮(bottomRect=0 → navBarHeight=0),须自留 ≥24vp 安全区
      .width('100%').padding({ left: 16, right: 16, top: 8,
        bottom: 12 + Math.max(this.navBarHeight, 24) })
      .border({ width: { top: 0.5 }, color: { top: LINE } })
    }
    .layoutWeight(1)
    .width('100%')
    }
    .layoutWeight(1)
    .width('100%')
  }

  @Builder
  private novelJumpButton(): void {
    if (this.jumpButtonVisible) {
      Stack() {
        Icon({ name: 'arrow_down_to_line', iconSize: 20, color: INK })
      }
      .width(44)
      .height(44)
      .margin({ right: 16, bottom: 12 })
      .backgroundColor(SURFACE2)
      .borderRadius(22)
      .border({ width: 1, color: LINE })
      .alignContent(Alignment.Center)
      .onClick((): void => { this.jumpNovelToBottom(); })
      .accessibilityText('回到消息底部')
      .transition(TransitionEffect.OPACITY
        .combine(TransitionEffect.translate({ y: 8 }))
        .animation({ duration: MOTION_PRESS, curve: Curve.EaseOut }))
    }
  }

  composerPlaceholder(): string {
    if (this.composeMode === 'discuss') return '聊人物、情节或世界观…';
    if (this.composeMode === 'continue') return '描述接下来发生的场景…';
    return '描述本章要点，生成完整章节…';
  }

  @Builder
  chapterPane(): void {
    Column() {
      if (this.collectionFeedback.length > 0) {
        Text(this.collectionFeedback).fontSize(12).fontColor(INK3).lineHeight(18).width('100%')
          .padding({ left: 16, right: 16, top: 10, bottom: 10 })
      }
      // 目录(iOS NovelChapterManagementView):标题 + 共 N 章;次要操作收「更多」
      Row() {
        Text('目录').fontSize(15).fontWeight(FontWeight.Bold).fontColor(INK2)
        if (this.project !== null) {
          Text(`共 ${this.project.chapters.length} 章`).fontSize(12).fontColor(INK3)
            .margin({ left: 8 })
        }
        if (getProductKind() === 'novel') {
          StudioInkStamp({ word: '收稿', trigger: this.collectionStamp, stampSize: 32,
            active: this.studioPageVisible && !this.studioBackgrounded && this.tab === 1 })
            .margin({ left: 12, right: 8 })
        }
        Blank()
        Text('更多 ▾').fontSize(12).fontColor(INK3)
          .constraintSize({ minWidth: 44 }).height(44)
          .padding({ left: 8, right: 8 }).textAlign(TextAlign.Center)
          .onClick((): void => { this.showChapterToolsMenu(); })
        Text('新建').fontSize(13).fontColor(ACCENT_INK)
          .constraintSize({ minWidth: 64 }).height(36)
          .padding({ left: 14, right: 14 }).textAlign(TextAlign.Center)
          .backgroundColor(ACCENT).borderRadius(18)
          .onClick(() => { this.openNewChapter(); })
      }
      .width('100%').padding({ left: 20, right: 20, top: 12, bottom: 10 })
      .alignItems(VerticalAlign.Center)

      if (this.consistencyMessage.length > 0) {
        Text(this.consistencyMessage).fontSize(12).fontColor(INK3).lineHeight(18)
          .width('100%').padding({ left: 20, right: 20, bottom: 10 })
      }

      if (this.polishJob !== null) {
        NovelPolishJobCard({
          job: this.polishJob,
          busy: this.polishBusy,
          onPause: (): void => { this.pausePolish(); },
          onResume: (): void => { this.resumePolish(); },
          onRetry: (): void => { this.retryPolish(); },
          onCancel: (): void => { this.cancelPolish(); },
        })
        .margin({ left: 16, right: 16, bottom: 10 })
      }

      if (this.project !== null && this.project.chapters.length === 0) {
        Text('在创作对话中收录的正文会按章节出现在这里。')
          .fontSize(13).fontColor(INK3).padding({ left: 20, right: 20, top: 20 })
      } else {
        List({ space: 0 }) {
          if (this.project !== null) {
            ForEach(this.project.chapters, (c: NovelChapter, idx: number) => {
              ListItem() {
                Row() {
                  Text(novelChapterOrdinal(c, idx + 1).toString().padStart(2, '0'))
                    .fontSize(13).fontColor(INK4).width(30)
                  Column() {
                    Text((c.discarded ? '[已废弃] ' : '') + (c.title.length > 0 ? c.title : '未命名章节'))
                      .fontSize(15).fontColor(c.discarded ? INK4 : INK).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                    Text(`${this.charCount(c.content)} 字`).fontSize(11).fontColor(INK3).margin({ top: 4 })
                    if (c.suggestionWarning !== undefined && c.suggestionWarning !== null && c.suggestionWarning.length > 0) {
                      Text('正文已保存 · 资料分析未完成').fontSize(11).fontColor(ERROR).lineHeight(16).margin({ top: 4 })
                    }
                  }
                  .alignItems(HorizontalAlign.Start).layoutWeight(1)
                  Icon({ name: 'chevron_right', iconSize: 16, color: INK4 })
                }
                .width('100%').padding({ left: 20, right: 20, top: 14, bottom: 14 })
                .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
                .onClick(() => { this.openChapterReader(c.id); })
              }
            }, (c: NovelChapter) => c.id)
          }
        }
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%')
  }

  // 章节阅读器为 push 独立页(iOS NovelChapterReaderView;操作集中在其 ⋯菜单)
  private openChapterReader(chapterId: string): void {
    router.pushUrl({
      url: 'pages/NovelChapterReaderPage',
      params: { 'projectId': this.projectId, 'chapterId': chapterId } as Record<string, string>,
    });
  }

  charCount(s: string): number {
    return s.replace(/\s/g, '').length;
  }

  private hiddenSharedMaterialsIn(kinds: NovelMaterialKind[]): NovelMaterial[] {
    const project: NovelProject | null = this.project;
    if (project === null) return [];
    return (project.baseMaterials ?? []).filter((material: NovelMaterial): boolean =>
      kinds.includes(material.kind) && (project.hiddenMaterialIds ?? []).includes(material.id));
  }

  @Builder
  private MaterialCard(material: NovelMaterial): void {
    NovelMaterialCard({ material, origin: this.project === null ? 'branch' : materialOrigin(this.project, material.id),
      sharedExists: this.project?.baseMaterials?.some((base: NovelMaterial): boolean => base.id === material.id) ?? false,
      expanded: this.expandedMaterialId === material.id, busy: this.busy || this.hasBlockingNovelRun(),
      onExpand: (): void => { this.expandedMaterialId = this.expandedMaterialId === material.id ? '' : material.id; },
      onEditBranch: (): void => { this.openEditMaterial(material, 'branch'); },
      onEditShared: (): void => { this.openEditMaterial(material, 'shared'); },
      onDelete: (): void => { this.confirmDeleteMaterial(material); },
      onRestore: (): void => { this.confirmRestoreMaterialInheritance(material); },
    })
  }

  @Builder
  private HiddenMaterials(kinds: NovelMaterialKind[]): void {
    NovelHiddenSharedMaterials({ materials: this.hiddenSharedMaterialsIn(kinds), busy: this.busy || this.hasBlockingNovelRun(),
      onRestore: (material: NovelMaterial): void => { this.confirmRestoreMaterialInheritance(material); } })
  }

  @Builder
  private PendingMaterialSuggestions(kinds: NovelMaterialKind[]): void {
    if (this.pendingSuggestionsIn(kinds).length > 0 || this.pendingSettingProposalsIn(kinds).length > 0) {
      Text('待确认').fontSize(13).fontFamily(FONT_SANS_MEDIUM).fontColor(INK2).margin({ bottom: 8 })
      Text('建议采用后才会写入资料库。')
        .fontSize(11).fontColor(INK3).margin({ bottom: 10 })
      ForEach(this.pendingSettingProposalsIn(kinds), (proposal: NovelSettingProposal): void => {
        NovelSettingProposalCard({
          title: proposal.title, content: proposal.content, kindLabel: MATERIAL_KIND_LABELS[proposal.kind],
          onResolve: (accept: boolean): void => { this.resolveSettingProposal(proposal, accept); },
        })
      }, (proposal: NovelSettingProposal): string => `setting:${proposal.id}`)
      ForEach(this.pendingSuggestionsIn(kinds), (s: NovelMaterialSuggestion): void => {
        NovelSettingProposalCard({
          title: s.title, content: s.content, kindLabel: MATERIAL_KIND_LABELS[s.kind],
          onResolve: (accept: boolean): void => { this.resolveSuggestion(s.id, accept); },
        })
      }, (s: NovelMaterialSuggestion): string => s.id)
    }
  }

  // 资料分区页(iOS Compendium 角色/世界观):待确认建议 + 资料列表 + 新增
  @Builder
  private MaterialKindPane(kinds: NovelMaterialKind[], addLabel: string, addKind: NovelMaterialKind,
    showReanalyze: boolean): void {
    Scroll() {
      Column() {
        Row() {
          if (showReanalyze) {
            Text('重新分析').fontSize(12).fontColor(INK3)
              .constraintSize({ minHeight: 44 }).padding({ left: 8, right: 8 }).textAlign(TextAlign.Center)
              .onClick(() => { this.reanalyze(); })
          }
          Blank()
          Text(addLabel).fontSize(12).fontFamily(FONT_SANS_MEDIUM).fontColor(ACCENT)
            .constraintSize({ minHeight: 44 }).padding({ left: 8, right: 8 }).textAlign(TextAlign.Center)
            .onClick(() => { this.openNewMaterial(addKind); })
        }
        .width('100%').padding({ top: 4, bottom: 10 })

        if (this.project !== null) {
          ForEach(this.project.chapters.filter((chapter: NovelChapter): boolean =>
            chapter.suggestionWarning !== undefined && chapter.suggestionWarning !== null && chapter.suggestionWarning.length > 0),
          (chapter: NovelChapter): void => {
            Column({ space: 6 }) {
              Text(`「${chapter.title}」正文已保存，资料分析未完成`).fontSize(12).fontColor(ERROR).lineHeight(18)
              Text(this.materialAnalysisFailureText(chapter.suggestionWarning ?? '')).fontSize(11).fontColor(INK3).lineHeight(16)
              Text('重新分析此章').fontSize(12).fontColor(ACCENT).height(44).textAlign(TextAlign.Center)
                .enabled(!this.busy && !this.hasBlockingNovelRun())
                .onClick((): void => { this.reanalyze(chapter.id); })
            }
            .width('100%').alignItems(HorizontalAlign.Start).padding(12)
            .backgroundColor(SURFACE2).borderRadius(10).margin({ bottom: 10 })
          }, (chapter: NovelChapter): string => chapter.id)
        }
        this.PendingMaterialSuggestions(kinds)

        if (this.materialsIn(kinds).length === 0) {
          Text('暂无资料。').fontSize(13).fontColor(INK3)
            .width('100%').padding({ top: 12 })
        }
        ForEach(this.materialsIn(kinds), (m: NovelMaterial): void => {
          this.MaterialCard(m)
        }, (m: NovelMaterial): string => m.id)
        this.HiddenMaterials(kinds)
      }
      .width('100%').padding({ left: 20, right: 20, bottom: 24 })
      .alignItems(HorizontalAlign.Start)
    }
    .align(Alignment.TopStart)
    .layoutWeight(1)
  }

  // 伏笔卡:标为已解/重新打开
  @Builder
  private ForeshadowCard(item: NovelForeshadow): void {
    Column() {
      Row() {
        Text(item.title).fontSize(13).fontWeight(FontWeight.Medium).fontFamily(FONT_SANS_MEDIUM)
          .fontColor(INK).layoutWeight(1)
        Text(item.status === 'open' ? '标为已解' : '重新打开').fontSize(12).fontColor(ACCENT)
          .constraintSize({ minHeight: 44 }).padding({ left: 10 })
          .enabled(!this.busy)
          .onClick((): void => { this.toggleStoryForeshadow(item.id); })
      }
      .width('100%')
      Text(item.content).fontSize(12).fontColor(INK2).lineHeight(18).maxLines(3)
        .width('100%').margin({ top: 4 })
    }
    .width('100%').padding(14).backgroundColor(SURFACE).borderRadius(12)
    .border({ width: 1, color: LINE }).margin({ bottom: 10 })
  }

  // 剧情分区(iOS 设定→剧情):总纲 + 伏笔 + 已确认决定 + 连续性审计
  @Builder
  private StoryPane(): void {
    Scroll() {
      Column() {
        this.PendingMaterialSuggestions(['outline'])
        Row() {
          Text('总纲').fontSize(12).fontFamily(FONT_SANS_MEDIUM).fontColor(INK3)
          Blank()
          Text('新增总纲').fontSize(12).fontFamily(FONT_SANS_MEDIUM).fontColor(ACCENT)
            .constraintSize({ minHeight: 44 }).padding({ left: 8, right: 8 }).textAlign(TextAlign.Center)
            .onClick(() => { this.openNewMaterial('outline'); })
        }
        .width('100%').alignItems(VerticalAlign.Center)
        ForEach(this.materialsIn(['outline']), (m: NovelMaterial): void => {
          this.MaterialCard(m)
        }, (m: NovelMaterial): string => m.id)
        this.HiddenMaterials(['outline'])
        if (this.materialsIn(['outline']).length === 0) {
          Text('暂无总纲资料。').fontSize(12).fontColor(INK3).width('100%').margin({ bottom: 8 })
        }

        Column() {}.height(12)
        this.SheetLabel('伏笔')
        Text('未解决与已解决状态都随当前分支保存。').fontSize(11).fontColor(INK3).margin({ bottom: 8 })
        TextInput({ text: this.fsTitle, placeholder: '伏笔标题' })
          .height(44).fontSize(13).fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(10).padding({ left: 14, right: 14 })
          .enabled(!this.busy)
          .onChange((v: string): void => { this.fsTitle = v; })
        TextArea({ text: this.fsContent, placeholder: '伏笔内容与预计回收方式' })
          .width('100%').height(80).fontSize(13).fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(10).padding(12).margin({ top: 8 })
          .enabled(!this.busy)
          .onChange((v: string): void => { this.fsContent = v; })
        Row() {
          Blank()
          Text('新增伏笔').fontSize(13).fontFamily(FONT_SANS_MEDIUM).fontColor(this.busy ? INK4 : ACCENT)
            .constraintSize({ minHeight: 44 })
            .enabled(!this.busy)
            .onClick((): void => { this.addStoryForeshadow(); })
        }.width('100%')
        Text(`未解决 · ${this.openForeshadows().length}`).fontSize(12).fontFamily(FONT_SANS_MEDIUM)
          .fontColor(INK2).width('100%').margin({ top: 8, bottom: 6 })
        if (this.openForeshadows().length === 0) {
          Text('暂无未解决伏笔。').fontSize(12).fontColor(INK3).width('100%').margin({ bottom: 8 })
        }
        ForEach(this.openForeshadows(), (item: NovelForeshadow): void => {
          this.ForeshadowCard(item)
        }, (item: NovelForeshadow): string => item.id)
        if (this.resolvedForeshadows().length > 0) {
          Text(`已解决 · ${this.resolvedForeshadows().length}`).fontSize(12).fontFamily(FONT_SANS_MEDIUM)
            .fontColor(INK2).width('100%').margin({ top: 8, bottom: 6 })
          ForEach(this.resolvedForeshadows(), (item: NovelForeshadow): void => {
            this.ForeshadowCard(item)
          }, (item: NovelForeshadow): string => item.id)
        }

        Column() {}.height(12)
        this.SheetLabel('已确认决定')
        Text('确认后的决定会进入当前分支的活资料。').fontSize(11).fontColor(INK3).margin({ bottom: 8 })
        TextInput({ text: this.decTitle, placeholder: '决定标题' })
          .height(44).fontSize(13).fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(10).padding({ left: 14, right: 14 })
          .enabled(!this.busy)
          .onChange((v: string): void => { this.decTitle = v; })
        TextArea({ text: this.decContent, placeholder: '已确认的事实、约束或创作决定' })
          .width('100%').height(80).fontSize(13).fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(10).padding(12).margin({ top: 8 })
          .enabled(!this.busy)
          .onChange((v: string): void => { this.decContent = v; })
        Row() {
          Blank()
          Text('新增决定').fontSize(13).fontFamily(FONT_SANS_MEDIUM).fontColor(this.busy ? INK4 : ACCENT)
            .constraintSize({ minHeight: 44 })
            .enabled(!this.busy)
            .onClick((): void => { this.addStoryDecision(); })
        }.width('100%')
        if (this.project !== null && this.project.branchSettings.confirmedDecisions.length === 0) {
          Text('尚未记录已确认决定。').fontSize(12).fontColor(INK3).width('100%').margin({ top: 8 })
        }
        if (this.project !== null) {
          ForEach(this.project.branchSettings.confirmedDecisions, (item: NovelConfirmedDecision): void => {
            Column() {
              Text(item.title).fontSize(13).fontWeight(FontWeight.Medium).fontFamily(FONT_SANS_MEDIUM).fontColor(INK)
              Text(item.content).fontSize(12).fontColor(INK2).lineHeight(18).margin({ top: 3 })
            }
            .width('100%').alignItems(HorizontalAlign.Start)
            .padding(14).backgroundColor(SURFACE).borderRadius(12)
            .border({ width: 1, color: LINE }).margin({ bottom: 10 })
          }, (item: NovelConfirmedDecision): string => item.id)
        }

        Column() {}.height(12)
        // 连续性审计(自正文 tab「更多」迁入;iOS 剧情 Section)
        this.SheetLabel('连续性审计')
        Text('逐块覆盖全部有效章节，核查有原文依据的连续性问题。内部职责模型未单独配置时使用写作模型。')
          .fontSize(11).fontColor(INK3).lineHeight(17).margin({ bottom: 8 })
        Row() {
          Text(this.auditRunning ? '正在审校' : '开始全文审校').fontSize(13).fontColor(this.auditRunning || this.busy ? INK4 : ACCENT)
            .constraintSize({ minHeight: 44 }).enabled(!this.auditRunning && !this.busy)
            .onClick((): void => { this.runLlmContinuityAudit(); })
          Blank()
          if (this.auditRunning) {
            Text('取消').fontSize(13).fontColor(ACCENT).constraintSize({ minWidth: 44, minHeight: 44 })
              .onClick((): void => { this.auditController?.cancel(); })
          }
        }.width('100%')
        if (this.auditMessage.length > 0) {
          Text(this.auditMessage).fontSize(12).fontColor(INK3).lineHeight(18).width('100%')
        }
        if ((this.auditReport?.issues.length ?? 0) > 0) {
          Flex({ wrap: FlexWrap.Wrap }) {
            Text(this.auditRepairBusy ? '正在生成修复建议…' : '全部生成修复建议').fontSize(13).fontColor(ACCENT)
              .constraintSize({ minHeight: 44 }).padding({ right: 20 })
              .enabled(!this.auditRepairBusy && !this.auditRunning && !this.busy && !this.hasBlockingNovelRun() && !this.auditReportStale())
              .opacity(this.auditRepairBusy || this.auditReportStale() ? 0.45 : 1)
              .onClick((): void => { this.repairAuditIssues(this.auditReport?.issues ?? []); })
            if (this.auditRepairBusy) {
              Text('取消修复').fontSize(13).fontColor(ACCENT).constraintSize({ minHeight: 44 })
                .onClick((): void => { this.cancelAuditRepair(); })
            }
          }.width('100%')
        }
        NovelAuditReportView({ report: this.auditReport, chapters: this.project?.chapters ?? [], stale: this.auditReportStale(),
          repairBusy: this.auditRepairBusy || this.auditRunning || this.busy || this.hasBlockingNovelRun(),
          onRepair: (issue: NovelAuditIssue): void => { this.repairAuditIssue(issue); },
          onCanonicalEvidence: (issue: NovelAuditIssue, reference: NovelAuditReference): void => { this.openAuditEvidence(reference, issue.chapterId); },
          onEvidence: (issue: NovelAuditIssue): void => { this.openAuditEvidence(issue); } })
      }
      .width('100%').padding({ left: 20, right: 20, bottom: 24 })
      .alignItems(HorizontalAlign.Start)
    }
    .align(Alignment.TopStart)
    .layoutWeight(1)
  }

  // 更多分区:审稿/内部职责模型(NovelSettingsPane 的计划/伏笔/决定段已各归其位)
  @Builder
  private ProjectModelRolesPane(): void {
    if (this.project !== null) {
      NovelSettingsPane({
        project: this.project,
        modelOptions: this.modelOptions,
        busy: this.busy || this.novelJobLocksWorkspace(),
        showPlanSections: false,
        showStorySections: false,
        showMaterialsEntry: false,
        onSaveModelPolicy: (policy: NovelModelPolicy): void => {
          this.saveNovelModelPolicy(policy);
        },
        onSaveBranchSettings: (settings: NovelBranchSettings): void => {
          this.saveNovelBranchSettings(settings);
        },
        onNewMaterial: (): void => {
          this.openNewMaterial('other');
        },
      })
    }
  }

  @Builder
  private MorePane(): void {
    if (getProductKind() !== 'novel') {
      this.ProjectModelRolesPane()
    } else {
      Column() {
        Row() {
          Text(this.projectModelsVisible ? '项目模型职责' : '其他资料').fontSize(13).fontColor(INK3)
          Blank()
          Text(this.projectModelsVisible ? '返回其他资料' : '项目模型职责').fontSize(13).fontColor(ACCENT)
            .constraintSize({ minHeight: 44 }).padding({ left: 8, right: 8 })
            .onClick((): void => {
              animateTo({ duration: this.studioPaneDuration(), curve: Curve.EaseInOut }, (): void => {
                this.projectModelsVisible = !this.projectModelsVisible;
              });
            })
        }.width('100%').padding({ left: 20, right: 12 })
        Stack({ alignContent: Alignment.TopStart }) {
          if (this.projectModelsVisible) {
            Column() { this.ProjectModelRolesPane() }.width('100%').height('100%').transition(this.workspacePaneTransition())
          } else {
            Column() { this.MaterialKindPane(['requirement', 'other'], '新增资料', 'other', false) }
              .width('100%').height('100%').transition(this.workspacePaneTransition())
          }
        }.width('100%').layoutWeight(1)
      }.width('100%').height('100%')
    }
  }

  @Builder
  settingsPane(): void {
    Column() {
      // 设定分类(iOS Compendium segmented:角色/世界观/剧情/更多)
      if (getProductKind() === 'novel') {
        StudioTabs({ options: ['角色', '世界观', '剧情', '更多'], selected: this.settingTab,
          active: this.studioPageVisible && !this.studioBackgrounded,
          onSelect: (idx: number): void => {
            if (idx === this.settingTab) return;
            animateTo({ duration: this.studioPaneDuration(), curve: Curve.EaseInOut }, (): void => { this.settingTab = idx; });
          } })
          .margin({ left: 16, right: 16, top: 2, bottom: 8 })
      } else {
        Seg({ options: ['角色', '世界观', '剧情', '更多'], selected: this.settingTab,
          onSelect: (idx: number): void => {
            if (idx === this.settingTab) return;
            animateTo({ duration: MOTION_ENTER, curve: Curve.EaseOut }, (): void => { this.settingTab = idx; });
          } })
          .margin({ left: 16, right: 16, top: 2, bottom: 8 })
      }
      // Keep outgoing and incoming panes in the same bounds during the fade.
      Stack({ alignContent: Alignment.TopStart }) {
        if (this.project === null) {
          Column() {}.height('100%')
        } else if (this.settingTab === 0) {
          Column() {
            this.MaterialKindPane(['character', 'relationship'], '新增角色', 'character', false)
          }
          .width('100%').height('100%')
          .transition(this.workspacePaneTransition())
        } else if (this.settingTab === 1) {
          Column() {
            this.MaterialKindPane(getProductKind() === 'novel' ? ['world'] : ['world', 'requirement', 'other'],
              '新增资料', 'world', true)
          }
          .width('100%').height('100%')
          .transition(this.workspacePaneTransition())
        } else if (this.settingTab === 2) {
          Column() {
            this.StoryPane()
          }
          .width('100%').height('100%')
          .transition(this.workspacePaneTransition())
        } else {
          Column() {
            this.MorePane()
          }
          .width('100%').height('100%')
          .transition(this.workspacePaneTransition())
        }
      }.width('100%').layoutWeight(1)
    }
    .width('100%').height('100%')
  }

  build(): void {
    Stack() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
        Column() {
          // 顶栏(iOS NovelProjectWorkspaceView):返回玻璃圆 + 居中 principal
          // (项目名 + 模式·分支·模型,点开「项目控制」)+ 右侧设置玻璃圆（宿主保留导出入口）。
          // Stack 点击拦截坑:整宽按钮行先声明(下层),可点胶囊后声明(上层)且内容宽
          Stack({ alignContent: Alignment.Center }) {
            Row() {
              Row() {
                Icon({ name: 'back', iconSize: 18, color: INK })
              }
              .width(44).height(44).borderRadius(22).backgroundColor(SURFACE)
              .border({ width: 0.5, color: LINE })
              .justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
              .onClick((): void => { this.handleBack(); })
              .accessibilityText('返回')
              Blank()
              Row() {
                Icon({ name: getProductKind() === 'novel' ? 'gear' : 'share', iconSize: 17, color: INK2 })
              }
              .width(44).height(44).borderRadius(22).backgroundColor(SURFACE)
              .border({ width: 0.5, color: LINE })
              .justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
              .enabled(!this.busy && this.project !== null)
              .onClick((): void => {
                if (getProductKind() === 'novel') router.pushUrl({ url: 'pages/NovelStandaloneSettingsPage' });
                else this.showExportMenu();
              })
              .accessibilityText(getProductKind() === 'novel' ? '设置' : '导出')
            }
            .width('100%')
            Column() {
              Text(this.project !== null ? this.project.name : '小说创作')
                .fontSize(17).fontWeight(FontWeight.Bold).fontColor(INK).fontFamily(FONT_SANS_BOLD)
                .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
              Text(this.workspaceSubtitle())
                .fontSize(11).fontColor(INK3).fontFamily(FONT_SANS_MEDIUM)
                .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                .margin({ top: 2 })
            }
            .alignItems(HorizontalAlign.Center)
            .constraintSize({ minHeight: 44, maxWidth: '62%' })
            .padding({ left: 12, right: 12 })
            .enabled(this.project !== null && !this.busy)
            .onClick((): void => { this.openControlSheet(); })
            .accessibilityText('项目控制')
          }
          .width('100%').padding({ left: 16, right: 16, top: this.statusBarHeight + 10, bottom: 10 })
          .backgroundColor(BG)

          // 页签(iOS segmented:灰轨 + 白 pill)
          if (getProductKind() === 'novel') {
            StudioTabs({ options: ['创作', '正文', '设定'], selected: this.tab,
              active: this.studioPageVisible && !this.studioBackgrounded,
              onSelect: (idx: number): void => { this.selectStudioTab(idx); } })
              .margin({ left: 16, right: 16, top: 2, bottom: 8 })
          } else {
            Seg({ options: ['创作', '正文', '设定'], selected: this.tab,
              onSelect: (idx: number): void => {
                if (idx === this.tab) return;
                animateTo({ duration: MOTION_ENTER, curve: Curve.EaseOut }, (): void => { this.tab = idx; });
              } })
              .margin({ left: 16, right: 16, top: 2, bottom: 8 })
          }

          if (this.errorMsg.length > 0 && !this.chapterEditorOpen && !this.materialEditorOpen) {
            Text(this.errorMsg).fontSize(12).fontColor(ERROR).lineHeight(18)
              .width('100%').padding({ left: 20, right: 20, bottom: 8 })
          }

          if (this.contextOverrides !== null) {
            Row() {
              Text(`本次上下文：包含 ${this.contextOverrides.forceIncludeMaterialIds.length} 项，排除 ${this.contextOverrides.forceExcludeMaterialIds.length} 项。仅下一次普通创作有效。`)
                .fontSize(12).fontColor(INK3).lineHeight(18).layoutWeight(1)
              Text('清除').fontSize(13).fontColor(ACCENT).height(44).padding({ left: 12, right: 12 })
                .onClick((): void => { this.contextOverrides = null; })
            }.width('100%').padding({ left: 20, right: 20, bottom: 8 })
          }

          Stack({ alignContent: Alignment.TopStart }) {
            if (this.project === null) {
              Column() {
                Text('项目无法打开').fontSize(15).fontColor(INK3)
              }
              .width('100%').height('100%').justifyContent(FlexAlign.Center)
            } else if (this.tab === 0) {
              Column() {
                this.chatPane()
              }
              .width('100%').height('100%')
              .transition(this.workspacePaneTransition())
            } else if (this.tab === 1) {
              Column() {
                this.chapterPane()
              }
              .width('100%').height('100%')
              .transition(this.workspacePaneTransition())
            } else {
              Column() {
                this.settingsPane()
              }
              .width('100%').height('100%')
              .transition(this.workspacePaneTransition())
            }
          }.width('100%').layoutWeight(1)
        }
        .width('100%').height('100%').backgroundColor(BG)

        // 收录 sheet(四段式)
        if (this.collectMessageId.length > 0) {
          this.collectSheet()
        }
        // 章节编辑器
        if (this.chapterEditorOpen) {
          this.chapterEditor()
        }
        // 素材编辑器
        if (this.materialEditorOpen) {
          NovelMaterialEditorSheet({ draftKey: this.materialEditorKey, closeRequest: this.materialCloseRequest,
            initialDraft: this.materialEditorDraft, newMaterial: this.editingMaterialId.length === 0,
            busy: this.busy, errorMessage: this.errorMsg, bottomPadding: Math.max(this.navBarHeight, 12),
            onConfirm: (draft: NovelMaterialEditorDraft): void => { this.saveMaterial(draft); },
            onCancel: (): void => { if (!this.busy) { this.materialEditorOpen = false; this.materialOpenToken++; } },
          })
        }
        if (this.branchSheetOpen && this.workspaceStatus !== null) {
          NovelBranchSheet({
            visible: this.branchSheetOpen,
            activeBranchId: this.workspaceStatus.activeBranchId,
            branches: this.workspaceStatus.branches,
            busy: this.busy || this.hasBlockingNovelRun(),
            switchBusy: this.busy || this.ghostwriteBusy || this.polishBusy || this.sendStarting || this.composerRestoring,
            switchNeedsStop: this.hasBlockingNovelRun(),
            canUndo: this.workspaceStatus.canUndo,
            unresolvedFromOrdinal: this.workspaceStatus.unresolvedFromOrdinal,
            plotStale: this.workspaceStatus.plotStale,
            onClose: (): void => { if (!this.busy) this.branchSheetOpen = false; },
            onCreate: (name: string): void => { this.createWorkspaceBranch(name); },
            onSelect: (branchId: string): void => { this.selectWorkspaceBranch(branchId); },
            onUndo: (): void => { this.undoWorkspace(); },
            onSyncPlot: (): void => { this.syncWorkspacePlot(); },
            onResolveUnresolved: (): void => { this.resolveWorkspaceUnresolved(); },
            onOpenHistory: (): void => {
              if (this.busy || this.hasBlockingNovelRun()) return;
              this.branchSheetOpen = false;
              router.pushUrl({ url: 'pages/NovelBranchHistoryPage', params: { projectId: this.projectId } })
                .catch((error: Error): void => { if (this.pageAlive) this.errorMsg = String(error); });
            },
          })
        }
        if (this.polishCreateOpen) {
          NovelPolishCreateSheet({
            visible: this.polishCreateOpen,
            singleChapter: this.singlePolishChapterId.length > 0,
            chapters: this.polishSheetChapters(),
            modelReady: this.ghostwriteModelsReady(),
            modelDisabledReason: this.ghostwriteModelReason(),
            conflictReason: this.polishConflictReason(),
            errorMessage: this.polishStartError,
            busy: this.polishBusy,
            bottomPadding: Math.max(this.navBarHeight, 12),
            onStart: (
              startOrdinal: number, endOrdinal: number, contextOptions: PolishContextOptions, ordinals: number[],
            ): void => { this.startPolish(startOrdinal, endOrdinal, contextOptions, ordinals); },
            onClose: (): void => { if (!this.polishBusy) this.polishCreateOpen = false; },
          })
        }
        if (this.ghostwritePreview !== null) {
          this.GhostwriteStartPreviewSheet(this.ghostwritePreview)
        }
        if (this.ghostwriteBriefOpen) {
          NovelRevisionBriefSheet({ draftKey: this.ghostwriteBriefKey, closeRequest: this.ghostwriteBriefCloseRequest,
            sheetTitle: '按要求返修代笔', detail: this.ghostwriteBriefDetail,
            placeholder: '输入本次返修要求（最多 2400 字）', confirmLabel: '开始定向返修', busy: this.ghostwriteBusy,
            errorMessage: this.ghostwriteBriefError, bottomPadding: Math.max(this.navBarHeight, 12),
            onConfirm: (brief: string): void => { this.submitGhostwriteBrief(brief); },
            onCancel: (): void => { if (!this.ghostwriteBusy) this.ghostwriteBriefOpen = false; } })
        }
        // 项目控制 sheet(顶栏 principal 触发,渲染最上层)
        if (this.controlSheetOpen) {
          this.ControlSheet()
        }
        if (this.composerModelSheetOpen) {
          this.ComposerModelSheet(this.composerModelSession)
        }
        if (this.archiveOpen) {
          NovelDiscussionArchiveSheet({
            visible: this.archiveOpen, draftKey: this.archiveDraftKey,
            sourceMessageCount: this.archiveSourceCount, sourceRangeLabel: this.archiveSourceRange,
            initialSummary: this.archiveSummary, decisions: this.archiveDecisions,
            busy: this.busy, errorMessage: this.archiveError, bottomPadding: Math.max(this.navBarHeight, 12),
            onConfirm: (summary: string, indexes: number[]): void => { this.confirmArchive(summary, indexes); },
            onCancel: (): void => { this.cancelArchive(); },
          })
        }
        if (this.adoptionOpen) {
          NovelMaterialAdoptionSheet({
            visible: this.adoptionOpen, draftKey: this.adoptionDraftKey,
            initialKind: this.adoptionKind, initialTitle: this.adoptionTitle, initialBody: this.adoptionBody, initialFields: this.adoptionFields,
            existingTargets: this.adoptionTargets, busy: this.busy, errorMessage: this.adoptionError,
            bottomPadding: Math.max(this.navBarHeight, 12),
            onConfirm: (kind: NovelMaterialKind, title: string, body: string, targetId: string | null, fields: NovelMaterialFields): void => {
              this.confirmMaterialAdoption(kind, title, body, targetId, fields);
            },
            onCancel: (): void => { this.cancelAdoption(); },
          })
        }
      }, (e: number): string => `theme-${e}`)
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%')
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }

  @Builder
  private collectSheet(): void {
    Column() {
      Blank().onClick((): void => { this.confirmDiscardCollect(); })
      Column() {
        Row() {
          Text('收录正文').fontSize(17).fontWeight(FontWeight.Bold).fontFamily(FONT_SANS_MEDIUM).fontColor(INK)
            .layoutWeight(1)
          Text(`${this.collectSelectedCount()}/${this.collectParas.length} 段`)
            .fontSize(12).fontColor(INK3)
        }.width('100%').alignItems(VerticalAlign.Center).margin({ bottom: 10 })
        Scroll() {
          Column() {
            Row() {
              Text('选择段落').fontSize(12).fontFamily(FONT_SANS_MEDIUM).fontColor(INK3)
                .layoutWeight(1)
              Text('全选').fontSize(12).fontFamily(FONT_SANS_MEDIUM).fontColor(ACCENT)
                .constraintSize({ minHeight: 36 }).padding({ left: 10 })
                .onClick((): void => {
                  this.selectCollectParagraphs(true);
                })
              Text('清空').fontSize(12).fontFamily(FONT_SANS_MEDIUM).fontColor(INK3)
                .constraintSize({ minHeight: 36 }).padding({ left: 10 })
                .onClick((): void => {
                  this.selectCollectParagraphs(false);
                })
            }.width('100%').alignItems(VerticalAlign.Center)
            ForEach(this.collectParas, (para: string, idx: number): void => {
              Row({ space: 10 }) {
                Row() {
                  if (this.collectSelected[idx]) {
                    Icon({ name: 'check', iconSize: 12, color: ACCENT_INK })
                  }
                }
                .width(20).height(20).borderRadius(10)
                .justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
                .backgroundColor(this.collectSelected[idx] ? ACCENT : Color.Transparent)
                .border({ width: 1, color: this.collectSelected[idx] ? ACCENT : LINE })
                Text(para).fontSize(13).fontColor(INK2).lineHeight(20).layoutWeight(1)
                  .maxLines(3).textOverflow({ overflow: TextOverflow.Ellipsis })
              }
              .width('100%').alignItems(VerticalAlign.Top)
              .padding({ top: 8, bottom: 8 })
              .onClick((): void => { this.togglePara(idx); })
            }, (para: string, idx: number): string => `para-${idx}`)

            Column() {}.height(14)
            Row() {
              Text('收录前编辑').fontSize(12).fontFamily(FONT_SANS_MEDIUM).fontColor(INK3)
                .layoutWeight(1)
              Text('按当前选择重置').fontSize(12).fontFamily(FONT_SANS_MEDIUM).fontColor(ACCENT)
                .constraintSize({ minHeight: 36 })
                .onClick((): void => { this.resetDraftFromSelection(); })
            }.width('100%').alignItems(VerticalAlign.Center)
            TextArea({ text: this.collectDraft, placeholder: '收录进章节的正文…',
              controller: this.collectDraftController })
              .width('100%').height(150).fontSize(13).fontColor(INK).placeholderColor(INK4)
              .backgroundColor(SURFACE2).borderRadius(12).padding(12)
              .onChange((v: string): void => {
                if (v !== this.collectDraft) {
                  this.collectManuallyEdited = true;
                  this.collectDraft = v;
                }
              })
            if (this.collectManuallyEdited) {
              Text('已保留你的编辑；段落调整后可用“按当前选择重置”重建正文。')
                .fontSize(11).fontColor(INK3).lineHeight(16).margin({ top: 6 }).width('100%')
            }

            Column() {}.height(14)
            this.SheetLabel('章节位置')
            Seg({ options: ['新开章节', '并入', '替换'], selected: this.collectMode,
              onSelect: (idx: number): void => { this.collectMode = idx; } })
            if (this.collectMode === 0) {
              TextInput({ text: this.collectTitle, placeholder: '章节标题',
                controller: this.collectTitleController })
                .height(44).fontSize(14).fontColor(INK).placeholderColor(INK4)
                .backgroundColor(SURFACE2).borderRadius(10).padding({ left: 14, right: 14 })
                .margin({ top: 10 })
                .onChange((v: string): void => { this.collectTitle = v; })
            } else if (this.project !== null) {
              CardGroup({ radius: 18, stroke: true, content: (): void => { this.CollectChapterRows(); } })
                .margin({ top: 10 })
            }
            if (this.errorMsg.length > 0) {
              Text(this.errorMsg).fontSize(13).fontColor(ERROR).width('100%').margin({ top: 8 })
            }
            Column() {}.height(8)
          }
          .width('100%').alignItems(HorizontalAlign.Start)
        }
        .width('100%').layoutWeight(1).align(Alignment.TopStart).scrollBar(BarState.Off)
        Row() {
          Text('取消').fontSize(14).fontFamily(FONT_SANS_MEDIUM).fontColor(INK3)
            .layoutWeight(1).textAlign(TextAlign.Center).constraintSize({ minHeight: 44 })
            .enabled(!this.busy)
            .onClick((): void => { this.confirmDiscardCollect(); })
          Text(this.busy ? '收录中…' : '收录').fontSize(14).fontWeight(FontWeight.Medium)
            .fontFamily(FONT_SANS_MEDIUM).fontColor(this.collectDisabled() ? INK4 : ACCENT)
            .layoutWeight(1).textAlign(TextAlign.Center).constraintSize({ minHeight: 44 })
            .enabled(!this.collectDisabled())
            .onClick((): void => { this.submitCollect(); })
        }.width('100%').margin({ top: 8 })
      }
      .width('100%').height('85%')
      .padding({ left: 18, right: 18, top: 16, bottom: 14 + Math.max(this.navBarHeight, 24) })
      .backgroundColor(SURFACE).borderRadius({ topLeft: 20, topRight: 20 })
      .onClick((): void => {})
      .transition(TransitionEffect.translate({ y: 160 })
        .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
    }
    .width('100%').height('100%').backgroundColor(OVERLAY)
    .justifyContent(FlexAlign.End)
    .transition(TransitionEffect.OPACITY.animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
  }

  // 并入/替换的章节单选行
  @Builder
  private CollectChapterRows(): void {
    Column() {
      ForEach(this.project !== null ? this.project.chapters : [] as NovelChapter[],
        (c: NovelChapter, idx: number): void => {
          Row() {
            Column({ space: 2 }) {
              Text(`第 ${novelChapterOrdinal(c, idx + 1)} 章${c.title.length > 0 ? ' · ' + c.title : ''}`)
                .fontSize(14).fontFamily(FONT_SANS_MEDIUM).fontColor(INK)
                .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
              Text(`${this.charCount(c.content)} 字`).fontSize(11).fontColor(INK3)
            }.alignItems(HorizontalAlign.Start).layoutWeight(1)
            if (this.collectChapterId === c.id) {
              Icon({ name: 'check', iconSize: 16, color: ACCENT })
            }
          }
          .width('100%').alignItems(VerticalAlign.Center).constraintSize({ minHeight: 48 })
          .padding({ left: 14, right: 14 })
          .onClick((): void => { this.collectChapterId = c.id; })
          if (this.project !== null && idx < this.project.chapters.length - 1) {
            Row() {
              Row().width('100%').height(1).backgroundColor(LINE)
            }.width('100%').padding({ left: 14, right: 14 })
          }
        }, (c: NovelChapter): string => c.id)
    }.width('100%')
  }

  @Builder
  chapterEditor(): void {
    Column() {
      Column() {
        Text(this.editingChapterId.length > 0 ? '编辑本章' : '新建章节')
          .fontSize(17).fontWeight(FontWeight.Bold).fontColor(INK).margin({ bottom: 16 })
        TextInput({ text: this.chapterTitle, placeholder: '章节标题', controller: this.chapterTitleController })
          .height(44).fontSize(14).fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(10).padding({ left: 14, right: 14 })
          .enabled(!this.busy)
          .onChange((v: string) => { this.chapterTitle = v; })
        TextArea({ text: this.chapterContent, placeholder: '章节正文', controller: this.chapterContentController })
          .height(180).fontSize(14).fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(10).padding(12).margin({ top: 12 })
          .enabled(!this.busy)
          .onChange((v: string) => { this.chapterContent = v; })
        Text(`${this.charCount(this.chapterContent)} 字`).fontSize(11).fontColor(INK3).margin({ top: 6 })
        if (this.errorMsg.length > 0) {
          Scroll() {
            Text(this.errorMsg).fontSize(12).fontColor(ERROR).width('100%').wordBreak(WordBreak.BREAK_ALL)
          }
          .width('100%').constraintSize({ maxHeight: 54 }).align(Alignment.TopStart).margin({ top: 8 })
        }
        Row() {
          Text('取消').fontSize(14).fontColor(INK3).layoutWeight(1).textAlign(TextAlign.Center)
            .height(44).enabled(!this.busy)
            .onClick(() => { this.confirmDiscardChapter(); })
          Text(this.busy ? '保存中…' : '保存').fontSize(14).fontColor(ACCENT).layoutWeight(1).textAlign(TextAlign.Center)
            .height(44).enabled(!this.busy)
            .onClick(() => { this.saveChapter(); })
        }
        .width('100%').margin({ top: 16 })
      }
      .width('88%').padding(22).backgroundColor(SURFACE).borderRadius(16)
      .transition(TransitionEffect.translate({ y: 24 })
        .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
    }
    .width('100%').height('100%').backgroundColor(OVERLAY)
    .justifyContent(FlexAlign.Center)
    .transition(TransitionEffect.OPACITY.animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
  }

}
