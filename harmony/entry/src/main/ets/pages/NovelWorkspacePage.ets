// NovelWorkspacePage — 小说工作区:三页签(聊天写作 / 章节正文 / 活资料)
// 单文件承载三个 pane(@Builder)与对话框(Stack 叠层),状态集中于本页 @State。
import {
  ACCENT, ACCENT_INK, BG, ERROR, INK, INK2, INK3, INK4, LINE, MOTION_ENTER, MOTION_OVERLAY,
  MOTION_PRESS, OVERLAY, SUCCESS_FG, SURFACE, SURFACE2,
} from '../design/tokens.ets';
import { FONT_MONO, FONT_SANS_BOLD, FONT_SANS_MEDIUM } from '../design/fonts.ets';
import { Icon } from '../components/ui/Icon.ets';
import { Seg, CardGroup, CardRow } from '../components/PageTemplate.ets';
import { ChatInput } from '../components/ChatInput.ets';
import {
  AgentMessageRow, NO_AGENT_MESSAGE_ROW_CAPABILITIES,
} from '../components/AgentMessageRow.ets';
import type { AgentMessageRowHostCapabilities } from '../components/AgentMessageRow.ets';
import { InteractiveMessageTimeline } from '../components/InteractiveMessageTimeline.ets';
import { NovelSettingProposalCard } from '../components/NovelSettingProposalCard.ets';
import {
  NovelPolishCreateSheet, NovelPolishCreateSheetChapter,
} from '../components/NovelPolishCreateSheet.ets';
import { NovelPolishJobCard } from '../components/NovelPolishJobCard.ets';
import { NovelBranchSheet } from '../components/NovelBranchSheet.ets';
import {
  NovelSettingsPane, NovelModelOptionView,
} from '../components/NovelSettingsPane.ets';
import { NovelGhostwriteCard } from '../components/NovelGhostwriteCard.ets';
import { KeyboardAvoidMode, router, promptAction } from '@kit.ArkUI';
import { fileIo, picker } from '@kit.CoreFileKit';
import { common } from '@kit.AbilityKit';
import {
  getNovelCreation, getNovelWorkspaceService, loadNovelModelOptions,
} from '../di/AppContainer.ets';
import type { NovelModelOption } from '../di/AppContainer.ets';
import type { NovelBookExportBundle } from '../platform_impl/NovelWorkspaceService.ets';
import { MATERIAL_KIND_LABELS } from '@amber/deepread-domain';
import {
  NovelProject, NovelMessage, NovelChapter, NovelMaterial, NovelMaterialSuggestion,
  NovelChatMode, NovelGenerationGranularity, NovelMaterialKind, NovelCollectionTarget,
  NovelRunKind, NovelRun, NovelActiveRun, NovelRunEvent,
  NovelSettingProposal, NovelForeshadow, NovelConfirmedDecision,
  NovelWorkspaceStatus, StreamTransportState, UIMessage, makeUIMessage,
  UIMessagePart, novelMessageUi, novelMessageText, NovelToolContinuationVerdict,
  NovelModelPolicy, NovelModelTarget, NovelBranchSettings, NovelConsistencyReport,
  emptyNovelBranchSettings,
  NovelAuditIssue, NovelAuditReport,
  DurableGhostwriteJob, GhostwriteStage, DurablePolishJob, PolishContextOptions, PolishStage,
} from '@amber/deepread-domain';
import {
  shouldScrollOnStreamUpdate, transitionOnGenerateEnd, transitionOnJumpToBottom,
  transitionOnStreamUpdate, transitionOnUserScroll,
} from '@amber/chat-domain';
import type { FollowMode, ToolApprovalPartLocator } from '@amber/chat-domain';


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

type ComposeMode = 'discuss' | 'continue' | 'whole_chapter';
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
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
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
  @State tab: number = 0;
  // 设定 tab 分类(iOS Compendium segmented):0 角色 / 1 世界观 / 2 剧情 / 3 更多
  @State settingTab: number = 0;
  @State fsTitle: string = '';
  @State fsContent: string = '';
  @State decTitle: string = '';
  @State decContent: string = '';
  @State errorMsg: string = '';
  @State consistencyMessage: string = '';
  @State auditRunning: boolean = false;
  @State auditMessage: string = '';
  @State auditIssues: NovelAuditIssue[] = [];
  @State modelOptions: NovelModelOptionView[] = [];
  @State busy: boolean = false;
  @State draft: string = '';  // 聊天
  @State composeMode: ComposeMode = 'whole_chapter';
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
  // N3:讨论归档蒸馏(取当前讨论模式中未收录的 assistant 消息)
  async archiveDiscussion(): Promise<void> {
    if (this.project === null || this.activeRunId.length > 0) return;
    const discussMsgs: NovelMessage[] = this.project.messages.filter(
      (m: NovelMessage): boolean => m.mode === 'discuss' && m.collectedChapterId === null);
    if (discussMsgs.length === 0) {
      promptAction.showToast({ message: '没有可归档的讨论内容', duration: 1400 });
      return;
    }
    this.busy = true;
    try {
      const ids: string[] = discussMsgs.map((m: NovelMessage): string => m.id);
      const r = await getNovelCreation().archiveDiscussion(this.projectId, ids);
      promptAction.showToast({ message: `归档完成,产出 ${r.count} 条资料建议`, duration: 1800 });
      await this.reload();
    } catch (e) {
      this.errorMsg = String(e);
    } finally {
      this.busy = false;
    }
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
  @State materialKind: NovelMaterialKind = 'world';
  @State materialTitle: string = '';
  @State materialContent: string = '';
  @State materialEnabled: boolean = true;
  @State polishCreateOpen: boolean = false;
  @State polishJob: DurablePolishJob | null = null;
  @State polishBusy: boolean = false;
  @State ghostwriteJob: DurableGhostwriteJob | null = null;
  @State ghostwriteBusy: boolean = false;
  @State ghostwriteModelAvailable: boolean = false;
  @State ghostwriteModelIssue: string = '正在检查模型配置…';

  private projectId: string = '';
  private currentUnsub: (() => void) | null = null;
  private chatScroller: Scroller = new Scroller();
  private pageAlive: boolean = false;
  private reloadToken: number = 0;
  private initialScrollArmed: boolean = false;
  private novelScrollToken: number = 0;
  private userTouchActive: boolean = false;
  private recoveryAttemptedToolCalls: Set<string> = new Set();
  private ghostwritePollToken: number = 0;
  private polishPollToken: number = 0;
  private requestedBranchId: string = '';
  private requestedPolishJobId: string = '';
  // F38:编辑器脏检查基线(打开时快照;返回时对比,脏则确认放弃)
  private chapterOriginalTitle: string = '';
  private chapterOriginalContent: string = '';
  private materialOriginalKind: NovelMaterialKind = 'world';
  private materialOriginalTitle: string = '';
  private materialOriginalContent: string = '';
  private materialOriginalEnabled: boolean = true;
  private previousKeyboardAvoidMode: KeyboardAvoidMode = KeyboardAvoidMode.OFFSET;
  private keyboardAvoidanceActive: boolean = false;

  aboutToAppear(): void {
    this.enableKeyboardAvoidance();
    this.pageAlive = true;
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
    this.draft = seed;
    this.send('quick_start');
  }

  // 阅读器 push 返回时刷新(章节编辑/版本恢复/删除在子页完成)
  private pageHiddenOnce: boolean = false;

  onPageHide(): void {
    this.pageHiddenOnce = true;
    this.restoreKeyboardAvoidance();
  }

  onPageShow(): void {
    this.enableKeyboardAvoidance();
    if (this.pageHiddenOnce) {
      this.pageHiddenOnce = false;
      this.reload();
    }
  }

  aboutToDisappear(): void {
    this.restoreKeyboardAvoidance();
    this.pageAlive = false;
    this.reloadToken++;
    this.novelScrollToken++;
    this.ghostwritePollToken++;
    this.polishPollToken++;
    if (this.currentUnsub !== null) {
      const unsub: (() => void) = this.currentUnsub;
      this.currentUnsub = null;
      unsub(); // 终结即退订,emitter 不再持有页面闭包
    }
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
    if (this.controlSheetOpen) {
      this.controlSheetOpen = false;
      return true;
    }
    if (this.polishCreateOpen) {
      if (!this.polishBusy) this.polishCreateOpen = false;
      return true;
    }
    if (this.branchSheetOpen) {
      this.branchSheetOpen = false;
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
      this.collectMessageId = '';
      return true;
    }
    return false;
  }

  private chapterDirty(): boolean {
    return this.chapterTitle !== this.chapterOriginalTitle
      || this.chapterContent !== this.chapterOriginalContent;
  }

  private materialDirty(): boolean {
    return this.materialKind !== this.materialOriginalKind
      || this.materialTitle !== this.materialOriginalTitle
      || this.materialContent !== this.materialOriginalContent
      || this.materialEnabled !== this.materialOriginalEnabled;
  }

  private confirmDiscardChapter(): void {
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
        action: (): void => { this.chapterEditorOpen = false; },
      },
    });
  }

  private confirmDiscardMaterial(): void {
    if (!this.materialDirty()) {
      this.materialEditorOpen = false;
      return;
    }
    AlertDialog.show({
      title: '放弃编辑？',
      message: '未保存的资料内容将丢失。',
      primaryButton: { value: '继续编辑', action: (): void => {} },
      secondaryButton: {
        value: '放弃',
        fontColor: ERROR,
        action: (): void => { this.materialEditorOpen = false; },
      },
    });
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
      const ghostwrite: DurableGhostwriteJob | null = await creation.ghostwriteJob(projectId);
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
        this.project = project;
        this.workspaceStatus = status;
        this.ghostwriteJob = ghostwrite;
        this.polishJob = polish;
        if (ghostwrite !== null) this.settingTab = 3;
        this.ghostwriteModelAvailable = modelAvailable;
        this.ghostwriteModelIssue = modelIssue;
        // 等 List 收到新 rows 的可见范围回调再落底；Promise 返回后的
        // setTimeout 仍可能早于 ArkUI 完成 ForEach 布局。
        this.initialScrollArmed = project.messages.length > 0 || activeRun !== null;
        if (activeRun !== null &&
          (this.activeRunId !== activeRun.run.id || this.currentUnsub === null)) {
          this.attachNovelRun(
            activeRun.run, activeRun.historyCount, activeRun.userText,
            activeRun.startedAt, '正在继续离页前的生成…');
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
      if (next !== null) this.settingTab = 3;
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
    try {
      const options: NovelModelOption[] = await loadNovelModelOptions();
      if (!this.pageAlive) return;
      this.modelOptions = options.map((option: NovelModelOption): NovelModelOptionView => ({
        providerId: option.providerId,
        modelId: option.modelId,
        label: option.label,
        providerLabel: option.providerLabel,
        available: option.available,
      }));
    } catch (error) {
      if (this.pageAlive) this.errorMsg = `模型列表加载失败：${String(error)}`;
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
    if (this.ghostwriteBusy || this.novelJobLocksWorkspace()) return;
    this.ghostwriteBusy = true;
    try {
      this.ghostwriteJob = await getNovelCreation().startGhostwrite(this.projectId, targetChapterCount);
      this.settingTab = 3;
      this.errorMsg = '';
      this.scheduleGhostwritePoll();
    } catch (error) {
      this.showWorkspaceOperationError('启动代笔失败', error);
    } finally {
      this.ghostwriteBusy = false;
    }
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
      this.settingTab = 3;
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
      this.settingTab = 3;
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

  // LLM 全文连续性审计(iOS NovelContinuityAudit);需已配置 stateSync 职责模型
  private async runLlmContinuityAudit(): Promise<void> {
    if (this.auditRunning || this.busy) return;
    const p: NovelProject | null = this.project;
    if (p !== null && p.modelPolicy.stateSync === null) {
      this.auditMessage = '请先在工作区设定中配置「内部职责」模型';
      this.auditIssues = [];
      return;
    }
    this.auditRunning = true;
    this.auditMessage = '审计中…（检查情节、人名与时间线）';
    this.auditIssues = [];
    try {
      const report: NovelAuditReport = await getNovelCreation().runContinuityAudit(this.projectId);
      this.auditIssues = report.issues;
      this.auditMessage = report.ok
        ? '未发现连续性问题。'
        : `发现 ${report.issues.length} 条问题（不自动改稿）。`;
      this.errorMsg = '';
    } catch (error) {
      this.auditMessage = '';
      this.showWorkspaceOperationError('连续性审计失败', error);
    } finally {
      this.auditRunning = false;
    }
  }

  private auditSeverityColor(severity: string): string {
    if (severity === 'blocker') return ERROR;
    if (severity === 'major') return ACCENT;
    return INK3;
  }

  private async createWorkspaceBranch(name: string): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    try {
      await getNovelCreation().createBranch(this.projectId, name);
      this.branchSheetOpen = false;
      await this.reload();
    } catch (e) {
      this.showWorkspaceOperationError('创建分支失败', e);
    } finally {
      this.busy = false;
    }
  }

  private async selectWorkspaceBranch(branchId: string): Promise<void> {
    if (this.busy || branchId === this.workspaceStatus?.activeBranchId) return;
    this.busy = true;
    try {
      await getNovelCreation().switchBranch(this.projectId, branchId);
      this.branchSheetOpen = false;
      await this.reload();
    } catch (e) {
      this.showWorkspaceOperationError('切换分支失败', e);
    } finally {
      this.busy = false;
    }
  }

  private async undoWorkspace(): Promise<void> {
    if (this.busy || this.novelJobLocksWorkspace()) return;
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
    if (this.busy) return;
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
    if (this.busy) return;
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
    promptAction.showToast({ message: `已导出 ${fileName}`, duration: 1600 });
  }

  private async exportArtifact(index: number): Promise<void> {
    if (this.project === null || this.busy) return;
    this.busy = true;
    try {
      const safeName: string = this.project.name.replace(/[\\/:*?"<>|]/g, '-');
      if (index === 0) {
        const zip: Uint8Array = await getNovelWorkspaceService().exportWorkspaceZip(this.projectId);
        await this.saveExportFile(`${safeName}.amber-novel.zip`, zip);
      } else {
        const book: NovelBookExportBundle = await getNovelWorkspaceService().exportBook(this.projectId);
        if (index === 1) await this.saveExportFile(`${safeName}.txt`, this.encodeExportText(book.text));
        else if (index === 2) {
          await this.saveExportFile(`${safeName}.md`, this.encodeExportText(book.markdown));
        } else if (index === 3) await this.saveExportFile(`${safeName}.epub`, book.epub);
      }
      this.errorMsg = '';
    } catch (e) {
      this.errorMsg = `导出失败：${String(e)}`;
      promptAction.showToast({ message: this.errorMsg, duration: 2400 });
    } finally {
      this.busy = false;
    }
  }

  private showExportMenu(): void {
    if (this.project === null || this.busy) return;
    promptAction.showActionMenu({
      buttons: [
        { text: '工作区 ZIP', color: INK },
        { text: '正文 TXT', color: INK },
        { text: '正文 Markdown', color: INK },
        { text: '正文 EPUB', color: INK },
      ],
    }).then((result: ExportMenuResult): void => {
      this.exportArtifact(result.index);
    }).catch((): void => {});
  }

  // 章节目录次要操作(一致性/深度审计/撤销/批量润色);既有守卫逻辑不变
  private showChapterToolsMenu(): void {
    promptAction.showActionMenu({
      buttons: [
        { text: '一致性检查', color: INK },
        { text: this.workspaceStatus?.canUndo && !this.novelJobLocksWorkspace() ? '撤销上次工作区操作' : '撤销（当前不可用）',
          color: this.workspaceStatus?.canUndo && !this.novelJobLocksWorkspace() ? INK : INK4 },
        { text: this.polishConflictReason().length > 0 ? `批量润色（${this.polishConflictReason()}）` : '批量润色',
          color: this.polishConflictReason().length > 0 ? INK4 : INK },
      ],
    }).then((result: ExportMenuResult): void => {
      if (result.index === 0) this.runConsistencyCheck();
      else if (result.index === 1) {
        if (this.workspaceStatus?.canUndo && !this.novelJobLocksWorkspace()) this.undoWorkspace();
      } else if (result.index === 2) {
        if (this.polishConflictReason().length === 0) {
          this.requestedPolishJobId = '';
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
    return novelMessageUi(message);
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
      this.chatScroller.scrollEdge(Edge.Bottom);
    }, 0);
  }

  private beginNovelGenerationFollow(): void {
    this.followMode = transitionOnJumpToBottom(true);
    this.atBottomApprox = true;
    this.jumpButtonVisible = false;
    this.scheduleNovelScrollToBottom();
  }

  private followNovelStreamingUpdate(): void {
    this.followMode = transitionOnStreamUpdate(this.followMode, this.atBottomApprox);
    this.jumpButtonVisible = this.followMode === 'paused';
    if (shouldScrollOnStreamUpdate(this.followMode, this.atBottomApprox)) {
      this.scheduleNovelScrollToBottom();
    }
  }

  private finishNovelGenerationFollow(): void {
    const wasPaused: boolean = this.followMode === 'paused';
    this.followMode = transitionOnGenerateEnd(this.followMode);
    this.jumpButtonVisible = wasPaused;
    if (!wasPaused) this.scheduleNovelScrollToBottom();
  }

  private reconcileNovelUserScroll(): void {
    this.novelScrollToken++;
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
    this.followMode = transitionOnJumpToBottom(this.activeRunId.length > 0);
    this.scheduleNovelScrollToBottom();
  }

  async send(runKindOverride: NovelRunKind | null = null): Promise<void> {
    const text: string = this.draft.trim();
    if (text.length === 0 || this.hasBlockingNovelRun() || this.project === null) return;
    try {
      await getNovelCreation().validateWritingModel(this.projectId);
    } catch (error) {
      this.errorMsg = `模型不可用：${String(error)}`;
      return;
    }
    if (this.hasBlockingNovelRun() || this.project === null || this.draft.trim() !== text) return;
    const mode: NovelChatMode = this.composeMode === 'discuss' ? 'discuss' : 'write';
    let gran: NovelGenerationGranularity | null = null;
    if (this.composeMode === 'continue') gran = 'continuation';
    else if (this.composeMode === 'whole_chapter') gran = 'whole_chapter';
    let runKind: NovelRunKind = 'discussion';
    if (this.composeMode === 'continue') runKind = 'prose_continuation';
    else if (this.composeMode === 'whole_chapter') runKind = 'prose_whole_chapter';
    if (runKindOverride !== null) runKind = runKindOverride;

    this.draft = '';
    const run: NovelRun = runKind === 'quick_start'
      ? await getNovelCreation().quickStart(this.projectId, text)
      : getNovelCreation().generate(this.projectId, text, mode, gran, runKind);
    this.attachNovelRun(
      run, this.project.messages.length, text, Date.now(), '正在连接模型并组织正文…');
  }

  interrupt(): void {
    if (this.activeRunId.length > 0) getNovelCreation().interrupt(this.activeRunId);
  }

  private hasBlockingNovelRun(): boolean {
    return this.activeRunId.length > 0 || this.novelJobLocksWorkspace();
  }

  // 打开收录表单(iOS 四段式):拆段落、全选、初稿=全选拼接
  openCollect(messageId: string): void {
    this.collectMessageId = messageId;
    const p: NovelProject | null = this.project;
    this.collectTitle = p !== null ? `第 ${p.chapters.length + 1} 章` : '第 1 章';
    const msg: NovelMessage | null = p === null ? null
      : p.messages.find((m: NovelMessage): boolean => m.id === messageId) ?? null;
    const text: string = msg === null ? '' : novelMessageText(msg);
    this.collectParas = text.split(/\n\s*\n/).map((s: string): string => s.trim())
      .filter((s: string): boolean => s.length > 0);
    this.collectSelected = this.collectParas.map((): boolean => true);
    this.collectDraft = this.collectParas.join('\n\n');
    this.collectMode = 0;
    this.collectChapterId = p !== null && p.chapters.length > 0
      ? p.chapters[p.chapters.length - 1].id : '';
  }

  private togglePara(idx: number): void {
    const next: boolean[] = this.collectSelected.slice();
    next[idx] = !next[idx];
    this.collectSelected = next;
  }

  private collectSelectedCount(): number {
    return this.collectSelected.filter((v: boolean): boolean => v).length;
  }

  // 「按当前选择重置」:编辑稿回到勾选段落拼接(iOS 同名)
  private resetDraftFromSelection(): void {
    const parts: string[] = [];
    for (let i: number = 0; i < this.collectParas.length; i++) {
      if (this.collectSelected[i]) parts.push(this.collectParas[i]);
    }
    this.collectDraft = parts.join('\n\n');
  }

  private collectDisabled(): boolean {
    if (this.busy || this.collectDraft.trim().length === 0) return true;
    return this.collectMode !== 0 && this.collectChapterId.length === 0;
  }

  private async submitCollect(): Promise<void> {
    if (this.collectMessageId.length === 0 || this.collectDisabled()) return;
    let target: NovelCollectionTarget;
    if (this.collectMode === 0) target = { kind: 'new_chapter', title: this.collectTitle };
    else if (this.collectMode === 1) target = { kind: 'append', chapterId: this.collectChapterId };
    else target = { kind: 'replace', chapterId: this.collectChapterId };
    this.busy = true;
    try {
      await getNovelCreation().collectMessage(this.projectId, this.collectMessageId, target,
        this.collectDraft.trim());
      const mode: number = this.collectMode;
      this.collectMessageId = '';
      this.errorMsg = '';
      await this.reload();
      this.tab = 1;
      // iOS:整章收录(新开/替换)完成后自动 offer 讨论归档;并入片段不弹
      if (mode !== 1) {
        AlertDialog.show({
          title: '正文已收进章节',
          message: '要把这次讨论整理成长期记忆吗？Amber 会把讨论蒸馏成资料建议，确认后才写入设定。',
          primaryButton: { value: '不了', action: (): void => {} },
          secondaryButton: { value: '整理讨论', action: (): void => { this.archiveDiscussion(); } },
        });
      }
    } catch (e) {
      this.errorMsg = String(e);
    } finally {
      this.busy = false;
    }
  }

  private continueNovelTool(toolCallId: string, verdict: NovelToolContinuationVerdict,
    locator?: ToolApprovalPartLocator): void {
    if (this.hasBlockingNovelRun()) return;
    // R08:NovelService.continueTool 目前按 id 定位(findTool 取首个匹配),
    //   空 id 会错误命中同 blank 的其它工具、重复 id 也无法区分。UI 侧先用 locator
    //   精确解析出真实 id;无法唯一确定时安全拒绝,绝不按 blank 等价批准/续跑。
    const resolvedId: string = this.resolveNovelToolId(toolCallId, locator);
    if (resolvedId.length === 0) {
      this.errorMsg = '无法唯一定位这次工具调用（缺少或重复的工具 ID），已拒绝本次操作。';
      return;
    }
    const run: NovelRun = getNovelCreation().continueTool(this.projectId, resolvedId, verdict);
    this.attachNovelRun(
      run, this.project?.messages.length ?? 0, '', Date.now(), '正在继续同一轮任务…');
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
    startedAt: number, statusText: string,
  ): void {
    if (this.currentUnsub !== null) {
      const previous: () => void = this.currentUnsub;
      this.currentUnsub = null;
      previous();
    }
    this.activeRunId = run.id;
    this.activeRunCreatedAt = startedAt;
    this.pendingUserText = pendingUserText;
    this.activeMessages = [];
    this.activeHistoryCount = historyCount;
    this.activeTextDeltasLive = false;
    this.activeTransport = 'unavailable';
    this.activeFrameSeen = false;
    this.generationStatus = statusText;
    this.errorMsg = '';
    this.beginNovelGenerationFollow();
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
        this.finishNovelGenerationFollow();
        this.clearNovelRunUi();
        if (event.kind === 'failed') this.errorMsg = event.message;
        this.currentUnsub = null;
        this.reload();
      }
    });
    if (this.activeRunId === run.id) this.currentUnsub = unsub;
    else unsub();
  }

  private async resolveSettingProposal(proposal: NovelSettingProposal, accept: boolean): Promise<void> {
    this.busy = true;
    try {
      await getNovelCreation().resolveSettingProposal(this.projectId, proposal.id, accept);
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
    if (this.novelJobLocksWorkspace()) return;
    this.errorMsg = '';
    this.editingChapterId = '';
    const p: NovelProject | null = this.project;
    this.chapterTitle = p !== null ? `第 ${p.chapters.length + 1} 章` : '第 1 章';
    this.chapterContent = '';
    this.chapterOriginalTitle = this.chapterTitle;
    this.chapterOriginalContent = this.chapterContent;
    this.chapterEditorOpen = true;
  }

  private attachChapterRun(run: NovelRun, statusText: string): void {
    this.attachNovelRun(
      run, this.project?.messages.length ?? 0, '', Date.now(), statusText);
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

  // N2:单章润色(polish 哨兵协议)
  async polishChapter(chapterId: string): Promise<void> {
    if (this.hasBlockingNovelRun()) return;
    try {
      const creation = getNovelCreation();
      const run: NovelRun = await creation.polishChapter(this.projectId, chapterId);
      this.attachChapterRun(run, '润色中…');
    } catch (e) {
      this.errorMsg = String(e);
    }
  }

  private polishSheetChapters(): NovelPolishCreateSheetChapter[] {
    const p: NovelProject | null = this.project;
    if (p === null) return [];
    return p.chapters.map((chapter: NovelChapter, index: number): NovelPolishCreateSheetChapter => ({
      id: chapter.id,
      ordinal: index + 1,
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
    startOrdinal: number, endOrdinal: number, contextOptions: PolishContextOptions,
  ): Promise<void> {
    if (this.polishBusy || this.polishConflictReason().length > 0) return;
    this.polishBusy = true;
    try {
      this.requestedPolishJobId = '';
      this.polishJob = await getNovelCreation().startPolish(
        this.projectId, startOrdinal, endOrdinal, contextOptions);
      this.polishCreateOpen = false;
      this.errorMsg = '';
      this.schedulePolishPoll();
    } catch (error) {
      this.showWorkspaceOperationError('启动批量润色失败', error);
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
    if (this.novelJobLocksWorkspace()) return;
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

  private branchSettingsWith(foreshadows: NovelForeshadow[], decisions: NovelConfirmedDecision[]): NovelBranchSettings {
    const p: NovelProject | null = this.project;
    if (p === null) return emptyNovelBranchSettings();
    return {
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

  openNewMaterial(kind: NovelMaterialKind = 'world'): void {
    this.errorMsg = '';
    this.editingMaterialId = '';
    this.materialKind = kind;
    this.materialTitle = '';
    this.materialContent = '';
    this.materialEnabled = true;
    this.materialOriginalKind = this.materialKind;
    this.materialOriginalTitle = this.materialTitle;
    this.materialOriginalContent = this.materialContent;
    this.materialOriginalEnabled = this.materialEnabled;
    this.materialEditorOpen = true;
  }

  openEditMaterial(m: NovelMaterial): void {
    this.errorMsg = '';
    this.editingMaterialId = m.id;
    this.materialKind = m.kind;
    this.materialTitle = m.title;
    this.materialContent = m.content;
    this.materialEnabled = m.enabled;
    this.materialOriginalKind = m.kind;
    this.materialOriginalTitle = m.title;
    this.materialOriginalContent = m.content;
    this.materialOriginalEnabled = m.enabled;
    this.materialEditorOpen = true;
  }

  async saveMaterial(): Promise<void> {
    this.busy = true;
    try {
      const saved: NovelMaterial = await getNovelCreation().upsertMaterial(
        this.projectId, this.editingMaterialId.length > 0 ? this.editingMaterialId : null,
        this.materialKind, this.materialTitle, this.materialContent, this.materialEnabled);
      this.materialEditorOpen = false;
      this.expandedMaterialId = saved.id;
      this.errorMsg = '';
      await this.reload();
    } catch (e) {
      this.errorMsg = e instanceof Error ? e.message : String(e);
    } finally {
      this.busy = false;
    }
  }

  async deleteMaterial(id: string): Promise<void> {
    this.busy = true;
    try {
      await getNovelCreation().deleteMaterial(this.projectId, id);
      await this.reload();
    } catch (e) {
      this.errorMsg = String(e);
    } finally {
      this.busy = false;
    }
  }

  async resolveSuggestion(id: string, accept: boolean): Promise<void> {
    this.busy = true;
    try {
      await getNovelCreation().resolveMaterialSuggestion(this.projectId, id, accept);
      await this.reload();
    } catch (e) {
      this.errorMsg = String(e);
    } finally {
      this.busy = false;
    }
  }

  async reanalyze(): Promise<void> {
    const p: NovelProject | null = this.project;
    if (p === null || p.chapters.length === 0) return;
    this.busy = true;
    try {
      const last: NovelChapter = p.chapters[p.chapters.length - 1];
      await getNovelCreation().refreshMaterialSuggestions(this.projectId, last.id);
      await this.reload();
    } catch (e) {
      this.errorMsg = String(e);
    } finally {
      this.busy = false;
    }
  }

  // ===== 页签内容 =====
  @Builder
  novelMessageHeader(message: NovelMessage): void {
    if (message.role === 'assistant') {
      Text(this.assistantLabel(message))
        .fontSize(12)
        .fontColor(INK3)
        .margin({ bottom: 4 })
    }
  }

  @Builder
  novelMessageFooter(message: NovelMessage): void {
    if (message.mode === 'write' && message.collectedChapterId === null &&
      novelMessageText(message).trim().length > 0) {
      Row() {
        Icon({ name: 'ph_bookOpen', iconSize: 14, color: ACCENT_INK })
        Text(message.interrupted ? '收进章节（部分）' : '收进章节')
          .fontSize(12).fontWeight(FontWeight.Medium).fontFamily(FONT_SANS_MEDIUM)
          .fontColor(ACCENT_INK).margin({ left: 6 })
      }
      .constraintSize({ minHeight: 44 })
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
    }
  }

  @Builder
  activeRunHeader(show: boolean = true): void {
    if (show) {
      Text(this.activeMessages.length > 0 ? '正在写' : '正在思考')
        .fontSize(12)
        .fontColor(ACCENT)
        .margin({ bottom: 4 })
      if (this.activeFrameSeen) {
        Text(this.activeTransport === 'live' ? '实时' :
          (this.activeTransport === 'buffered' ? '缓冲' : '非流式'))
          .fontSize(10)
          .fontColor(INK3)
          .margin({ left: 8, bottom: 4 })
      }
    }
  }

  @Builder
  emptyAgentRowSlot(): void {}

  @Builder
  novelTimelineRows(): void {
    if (this.project !== null) {
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
      ForEach(this.activeRunId.length > 0
        ? this.project.messages.slice(0, this.activeHistoryCount)
        : this.project.messages, (message: NovelMessage): void => {
        ListItem() {
          AgentMessageRow({
            message: this.projectMessageUi(message),
            capabilities: NOVEL_AGENT_MESSAGE_CAPABILITIES,
            hostHeader: (): void => { this.novelMessageHeader(message); },
            hostFooter: (): void => { this.novelMessageFooter(message); },
            streaming: false,
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
      }, (message: NovelMessage): string => message.id)
      ForEach(this.project.settingProposals.filter(
        (proposal: NovelSettingProposal): boolean => proposal.status === 'pending'),
      (proposal: NovelSettingProposal): void => {
        ListItem() {
          NovelSettingProposalCard({
            title: proposal.title,
            content: proposal.content,
            kindLabel: MATERIAL_KIND_LABELS[proposal.kind],
            onResolve: (accept: boolean): void => { this.resolveSettingProposal(proposal, accept); },
          })
        }
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
  novelTimelineActiveTail(): void {
    if (this.activeRunId.length > 0) {
      if (this.activeMessages.length > 0) {
        ForEach(this.activeMessages, (message: UIMessage, index: number): void => {
          ListItem() {
            AgentMessageRow({
              message,
              capabilities: NOVEL_AGENT_MESSAGE_CAPABILITIES,
              hostHeader: (): void => { this.activeRunHeader(index === 0); },
              hostFooter: (): void => { this.emptyAgentRowSlot(); },
              streaming: true,
              textDeltasLive: this.activeTextDeltasLive,
              isLastAssistant: index === this.activeMessages.length - 1,
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
      } else {
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

  // ===== 项目控制 sheet(iOS writingContext:模式与偏好) =====

  private composeModeLabel(): string {
    if (this.composeMode === 'discuss') return '讨论';
    if (this.composeMode === 'continue') return '写一段';
    return '写整章';
  }

  private writingModelLabel(): string {
    const p: NovelProject | null = this.project;
    if (p === null) return '跟随全局';
    const writing: NovelModelTarget = p.modelPolicy.writing;
    if (writing.kind === 'global') return '跟随全局';
    const option: NovelModelOptionView | undefined = this.modelOptions.find(
      (o: NovelModelOptionView): boolean => o.providerId === writing.providerId && o.modelId === writing.modelId);
    return option !== undefined ? option.label : writing.modelId;
  }

  private workspaceSubtitle(): string {
    const mode: string = this.ghostwriteJob !== null ? '代笔' : '共创';
    const branch: string = this.workspaceStatus !== null ? this.workspaceStatus.activeBranchName : '';
    return `${mode} · ${branch} · ${this.writingModelLabel()}`;
  }

  // 创作方式 menu(讨论/写一段/写整章 + 归档当前讨论;iOS 意图 menu 同款)
  private showComposeModeMenu(): void {
    if (this.hasBlockingNovelRun()) return;
    promptAction.showActionMenu({
      title: '创作方式',
      buttons: [
        { text: '讨论', color: INK },
        { text: '写一段', color: INK },
        { text: '写整章', color: INK },
        { text: '归档当前讨论', color: INK },
      ],
    }).then((result: ExportMenuResult): void => {
      if (result.index === 0) this.composeMode = 'discuss';
      else if (result.index === 1) this.composeMode = 'continue';
      else if (result.index === 2) this.composeMode = 'whole_chapter';
      else if (result.index === 3) this.archiveDiscussion();
    }).catch((): void => {});
  }

  private openControlSheet(): void {
    const p: NovelProject | null = this.project;
    if (p === null) return;
    this.planThisChapter = p.branchSettings.thisChapterPlan;
    this.planFuture = p.branchSettings.futurePlan;
    this.planPreferences = p.branchSettings.preferences;
    this.controlTab = this.ghostwriteJob !== null ? 1 : 0;
    this.controlNotice = '';
    this.controlSheetOpen = true;
  }

  private selectWritingTarget(target: NovelModelTarget): void {
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
    if (p === null || this.busy) return;
    const s: NovelBranchSettings = p.branchSettings;
    await this.saveNovelBranchSettings({
      thisChapterPlan: this.planThisChapter,
      futurePlan: this.planFuture,
      preferences: this.planPreferences,
      foreshadows: s.foreshadows,
      confirmedDecisions: s.confirmedDecisions,
    });
    if (this.errorMsg.length === 0) this.controlNotice = '已保存到当前分支';
  }

  // 写作模型选项行(跟随全局 + 固定模型;iOS ComposerModelSheet 的「跟随小说默认」)
  @Builder
  private ModelRow(label: string, detail: string, selected: boolean, available: boolean, isLast: boolean,
    target: NovelModelTarget): void {
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
      .enabled(available && !this.busy)
      .onClick((): void => { this.selectWritingTarget(target); })
      if (!isLast) {
        Row() {
          Row().width('100%').height(1).backgroundColor(LINE)
        }.width('100%').padding({ left: 14, right: 14 })
      }
    }.width('100%')
  }

  @Builder
  private ModelRows(): void {
    this.ModelRow('跟随聊天全局模型', '写作使用应用聊天当前模型', this.writingTargetSelected(null), true,
      this.modelOptions.length === 0, { kind: 'global' })
    ForEach(this.modelOptions, (option: NovelModelOptionView, idx: number): void => {
      this.ModelRow(option.label,
        option.available ? option.providerLabel : `${option.providerLabel} · 当前不可用`,
        this.writingTargetSelected(option), option.available, idx === this.modelOptions.length - 1,
        { kind: 'fixed', providerId: option.providerId, modelId: option.modelId })
    }, (option: NovelModelOptionView): string => `${option.providerId}-${option.modelId}`)
  }

  @Builder
  private ControlOps(): void {
    CardRow({ label: '分支管理', desc: '创建、切换与撤销分支',
      onRowClick: (): void => {
        if (this.busy || this.novelJobLocksWorkspace()) return;
        this.controlSheetOpen = false;
        this.branchSheetOpen = true;
      } })
    CardRow({ label: '导出作品', desc: '工作区 ZIP / 正文 TXT / Markdown / EPUB', isLast: true,
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
      Blank().onClick((): void => { this.controlSheetOpen = false; })
      Column() {
        Row() {
          Column({ space: 2 }) {
            Text('项目控制').fontSize(17).fontWeight(FontWeight.Bold).fontFamily(FONT_SANS_MEDIUM).fontColor(INK)
            Text(this.project !== null ? this.project.name : '').fontSize(12).fontColor(INK3)
              .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          }.alignItems(HorizontalAlign.Start).layoutWeight(1)
          Text('完成').fontSize(15).fontFamily(FONT_SANS_MEDIUM).fontColor(ACCENT)
            .constraintSize({ minWidth: 44, minHeight: 44 }).textAlign(TextAlign.End)
            .onClick((): void => { this.controlSheetOpen = false; })
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
            TextArea({ text: this.planThisChapter, placeholder: '本章的位置、目标冲突、必须发生与不能发生…' })
              .width('100%').height(100).fontSize(13).fontColor(INK).placeholderColor(INK4)
              .backgroundColor(SURFACE2).borderRadius(12).padding(12)
              .enabled(!this.busy)
              .onChange((v: string): void => { this.planThisChapter = v; })
            Column() {}.height(12)
            this.SheetLabel('往后计划')
            TextArea({ text: this.planFuture, placeholder: '接下来几章的方向…' })
              .width('100%').height(80).fontSize(13).fontColor(INK).placeholderColor(INK4)
              .backgroundColor(SURFACE2).borderRadius(12).padding(12)
              .enabled(!this.busy)
              .onChange((v: string): void => { this.planFuture = v; })
            Column() {}.height(12)
            this.SheetLabel('写作偏好')
            TextArea({ text: this.planPreferences, placeholder: '文风、视角、节奏偏好…' })
              .width('100%').height(80).fontSize(13).fontColor(INK).placeholderColor(INK4)
              .backgroundColor(SURFACE2).borderRadius(12).padding(12)
              .enabled(!this.busy)
              .onChange((v: string): void => { this.planPreferences = v; })
            Row() {
              Text(this.controlNotice).fontSize(12).fontColor(INK3).layoutWeight(1)
              Text(this.busy ? '保存中…' : '保存').fontSize(14).fontWeight(FontWeight.Medium)
                .fontFamily(FONT_SANS_MEDIUM).fontColor(this.busy ? INK4 : ACCENT)
                .constraintSize({ minHeight: 44 })
                .enabled(!this.busy)
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
      .transition(TransitionEffect.translate({ y: 160 })
        .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
    }
    .width('100%').height('100%').backgroundColor(OVERLAY)
    .justifyContent(FlexAlign.End)
    .transition(TransitionEffect.OPACITY.animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
  }

  @Builder
  emptyTimelineSlot(): void {}

  @Builder
  chatPane(): void {
    Column() {
    Column() {
      // 代笔任务进行中时卡片常显于聊天顶(控制入口在顶栏「项目控制」)
      if (this.project !== null && this.ghostwriteJob !== null) {
        NovelGhostwriteCard({
          job: this.ghostwriteJob,
          planReady: this.ghostwritePlanReady(),
          modelReady: this.ghostwriteModelsReady(),
          planDisabledReason: '请先在设定中保存本章计划。',
          modelDisabledReason: this.ghostwriteModelReason(),
          busy: this.ghostwriteBusy,
          onStart: (targetChapterCount: number): void => { this.startGhostwrite(targetChapterCount); },
          onPause: (): void => { this.pauseGhostwrite(); },
          onResume: (): void => { this.resumeGhostwrite(); },
          onRetry: (): void => { this.retryGhostwrite(); },
          onCancel: (): void => { this.cancelGhostwrite(); },
        })
        .margin({ left: 16, right: 16, bottom: 8 })
        .borderRadius(14)
        .clip(true)
        .transition(TransitionEffect.OPACITY
          .combine(TransitionEffect.translate({ y: -8 }))
          .animation({ duration: MOTION_ENTER, curve: Curve.EaseOut }))
      }
    }.width('100%').clip(true)
    Column() {
      // Chat 与 Novel 共享同一 Timeline/AgentMessageRow；Timeline 独占 16vp 水平 gutter。
      InteractiveMessageTimeline({
        scroller: this.chatScroller,
        horizontalGutter: 16,
        topPadding: 8,
        bottomPadding: 0,
        cachedItems: 5,
        showOlderLoader: false,
        showDivider: false,
        rows: (): void => { this.novelTimelineRows(); },
        activeTail: (): void => { this.novelTimelineActiveTail(); },
        hostTail: (): void => { this.emptyTimelineSlot(); },
        onTimelineAppear: (): void => {
          if (this.initialScrollArmed) {
            this.initialScrollArmed = false;
            this.scheduleNovelScrollToBottom();
          }
        },
        onVisibleRangeChange: (_start: number, _end: number): void => {
          if (this.initialScrollArmed) {
            this.initialScrollArmed = false;
            this.scheduleNovelScrollToBottom();
          }
        },
        onTimelineTouch: (event: TouchEvent): void => {
          if (event.type === TouchType.Down) {
            this.userTouchActive = true;
            this.novelScrollToken++;
          }
          if (event.type === TouchType.Up || event.type === TouchType.Cancel) {
            this.userTouchActive = false;
            this.reconcileNovelUserScroll();
          }
        },
        onTimelineScroll: (_offset: number, state: ScrollState): void => {
          if (this.userTouchActive &&
            (state === ScrollState.Scroll || state === ScrollState.Fling)) {
            this.reconcileNovelUserScroll();
          }
        },
        onTimelineScrollStop: (): void => { this.reconcileNovelUserScroll(); },
      })
      .overlay(this.novelJumpButton(), { align: Alignment.BottomEnd })
      .layoutWeight(1)
      .clip(true)

      // 输入器(复用聊天页 ChatInput;meta 行 = 模型 chip + 创作方式 menu,iOS 同款)
      Column() {
        Row() {
          Text(this.writingModelLabel())
            .fontSize(11).fontFamily(FONT_MONO).fontColor(INK3)
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
            .constraintSize({ minHeight: 44, maxWidth: '50%' })
            .padding({ left: 10, right: 10 })
            .backgroundColor(SURFACE2).borderRadius(12)
            .enabled(this.project !== null)
            .onClick((): void => { this.openControlSheet(); })
            .accessibilityText('写作模型，打开项目控制')
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

        ChatInput({
          text: this.draft,
          placeholder: this.composerPlaceholder(),
          showAttachments: false,
          showCoreSlashCommands: false,
          sending: this.activeRunId.length > 0 || this.polishRuns() || this.ghostwriteRuns(),
          disabled: this.novelJobLocksWorkspace() && !this.ghostwriteRuns() && !this.polishRuns(),
          onTextChange: (v: string): void => { this.draft = v; },
          onSend: (): void => { this.send(); },
          onStop: (): void => {
            if (this.polishRuns()) this.pausePolish();
            else if (this.ghostwriteRuns()) this.pauseGhostwrite();
            else this.interrupt();
          },
          onClearInput: (): void => { this.draft = ''; },
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
      // 目录(iOS NovelChapterManagementView):标题 + 共 N 章;次要操作收「更多」
      Row() {
        Text('目录').fontSize(15).fontWeight(FontWeight.Bold).fontColor(INK2)
        if (this.project !== null) {
          Text(`共 ${this.project.chapters.length} 章`).fontSize(12).fontColor(INK3)
            .margin({ left: 8 })
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
                  Text((idx + 1) < 10 ? '0' + (idx + 1) : '' + (idx + 1))
                    .fontSize(13).fontColor(INK4).width(30)
                  Column() {
                    Text((c.discarded ? '[已废弃] ' : '') + (c.title.length > 0 ? c.title : '未命名章节'))
                      .fontSize(15).fontColor(c.discarded ? INK4 : INK).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                    Text(`${this.charCount(c.content)} 字`).fontSize(11).fontColor(INK3).margin({ top: 4 })
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

  // 资料卡(各分区共用):展开读全文,展开态露编辑/删除
  @Builder
  private MaterialCard(m: NovelMaterial): void {
    Column() {
      Row() {
        Text(MATERIAL_KIND_LABELS[m.kind]).fontSize(11).fontColor(ACCENT)
          .padding({ left: 8, right: 8, top: 3, bottom: 3 }).borderRadius(8).backgroundColor(SURFACE2)
        Text(m.title).fontSize(14).fontFamily(FONT_SANS_MEDIUM).fontColor(INK)
          .margin({ left: 8 }).layoutWeight(1).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        if (!m.enabled) {
          Text('· 不参与写作').fontSize(11).fontColor(ERROR)
        }
        Text(this.expandedMaterialId === m.id ? '收起' : '展开').fontSize(12).fontColor(INK3)
          .margin({ left: 8 })
      }
      .width('100%').constraintSize({ minHeight: 44 })
      .onClick(() => {
        animateTo({ duration: MOTION_ENTER, curve: Curve.EaseOut }, (): void => {
          this.expandedMaterialId = this.expandedMaterialId === m.id ? '' : m.id;
        });
      })
      if (this.expandedMaterialId === m.id) {
        Column() {
          Text(m.content).fontSize(13).fontColor(INK2).lineHeight(20).width('100%')
          Row() {
            Blank()
            Text('删除').fontSize(12).fontColor(ERROR)
              .constraintSize({ minWidth: 44, minHeight: 44 }).textAlign(TextAlign.Center).margin({ right: 8 })
              .onClick(() => { this.deleteMaterial(m.id); })
            Text('编辑').fontSize(12).fontColor(ACCENT)
              .constraintSize({ minWidth: 44, minHeight: 44 }).textAlign(TextAlign.Center)
              .onClick(() => { this.openEditMaterial(m); })
          }.width('100%').margin({ top: 10 })
        }
        .width('100%')
        .transition(TransitionEffect.OPACITY
          .combine(TransitionEffect.translate({ y: -8 }))
          .animation({ duration: MOTION_ENTER, curve: Curve.EaseOut }))
      }
    }
    .width('100%').padding(14).backgroundColor(SURFACE).borderRadius(12)
    .border({ width: 1, color: LINE }).margin({ bottom: 10 })
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
        .width('100%').padding({ left: 20, right: 20, top: 4, bottom: 10 })

        if (this.pendingSuggestionsIn(kinds).length > 0) {
          Text('待确认').fontSize(13).fontFamily(FONT_SANS_MEDIUM).fontColor(INK2).margin({ bottom: 8 })
          Text('Amber 从正文中发现了资料变化，采用后才会写入资料库。')
            .fontSize(11).fontColor(INK3).margin({ bottom: 10 })
          ForEach(this.pendingSuggestionsIn(kinds), (s: NovelMaterialSuggestion): void => {
            NovelSettingProposalCard({
              title: s.title,
              content: s.content,
              kindLabel: MATERIAL_KIND_LABELS[s.kind],
              onResolve: (accept: boolean): void => { this.resolveSuggestion(s.id, accept); },
            })
          }, (s: NovelMaterialSuggestion): string => s.id)
        }

        if (this.materialsIn(kinds).length === 0) {
          Text('暂无资料。').fontSize(13).fontColor(INK3)
            .width('100%').padding({ top: 12 })
        }
        ForEach(this.materialsIn(kinds), (m: NovelMaterial): void => {
          this.MaterialCard(m)
        }, (m: NovelMaterial): string => m.id)
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
        Text('通读全部章节，检查情节、人名与时间线矛盾；需要已配置「内部职责」模型。')
          .fontSize(11).fontColor(INK3).margin({ bottom: 8 })
        Row() {
          Blank()
          Text(this.auditRunning ? '审计中…' : '开始连续性审计')
            .fontSize(13).fontFamily(FONT_SANS_MEDIUM)
            .fontColor(this.auditRunning || this.busy ? INK4 : ACCENT)
            .constraintSize({ minHeight: 44 })
            .enabled(!this.auditRunning && !this.busy)
            .onClick((): void => { this.runLlmContinuityAudit(); })
        }.width('100%')
        if (this.auditMessage.length > 0) {
          Text(this.auditMessage).fontSize(12).fontColor(INK3).lineHeight(18).width('100%')
        }
        ForEach(this.auditIssues, (issue: NovelAuditIssue): void => {
          Column() {
            Row() {
              Text(issue.severity).fontSize(11).fontColor(this.auditSeverityColor(issue.severity))
                .padding({ left: 6, right: 6, top: 2, bottom: 2 }).borderRadius(6)
                .backgroundColor(SURFACE2)
              Text(issue.chapterRef).fontSize(12).fontColor(INK3).margin({ left: 6 })
            }
            Text(issue.summary).fontSize(13).fontColor(INK).lineHeight(18).margin({ top: 4 })
            if (issue.suggestion.length > 0) {
              Text(`建议：${issue.suggestion}`).fontSize(12).fontColor(INK3).lineHeight(17).margin({ top: 2 })
            }
          }
          .width('100%').alignItems(HorizontalAlign.Start)
          .padding({ left: 12, right: 12, top: 10, bottom: 10 })
          .backgroundColor(SURFACE).borderRadius(10)
          .margin({ top: 8 })
        }, (issue: NovelAuditIssue): string =>
          `${issue.severity}|${issue.chapterRef}|${issue.summary}`)
      }
      .width('100%').padding({ left: 20, right: 20, bottom: 24 })
      .alignItems(HorizontalAlign.Start)
    }
    .align(Alignment.TopStart)
    .layoutWeight(1)
  }

  // 更多分区:审稿/内部职责模型(NovelSettingsPane 的计划/伏笔/决定段已各归其位)
  @Builder
  private MorePane(): void {
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
  settingsPane(): void {
    Column() {
      // 设定分类(iOS Compendium segmented:角色/世界观/剧情/更多)
      Seg({ options: ['角色', '世界观', '剧情', '更多'], selected: this.settingTab,
        onSelect: (idx: number): void => {
          if (idx === this.settingTab) return;
          animateTo({ duration: MOTION_ENTER, curve: Curve.EaseOut }, (): void => { this.settingTab = idx; });
        } })
        .margin({ left: 16, right: 16, top: 2, bottom: 8 })
      if (this.project === null) {
        Column() {}.layoutWeight(1)
      } else if (this.settingTab === 0) {
        Column() {
          this.MaterialKindPane(['character'], '新增角色', 'character', false)
        }
        .width('100%').layoutWeight(1)
        .transition(TransitionEffect.OPACITY
          .combine(TransitionEffect.translate({ y: 10 }))
          .animation({ duration: MOTION_ENTER, curve: Curve.EaseOut }))
      } else if (this.settingTab === 1) {
        Column() {
          this.MaterialKindPane(['world', 'requirement', 'other'], '新增资料', 'world', true)
        }
        .width('100%').layoutWeight(1)
        .transition(TransitionEffect.OPACITY
          .combine(TransitionEffect.translate({ y: 10 }))
          .animation({ duration: MOTION_ENTER, curve: Curve.EaseOut }))
      } else if (this.settingTab === 2) {
        Column() {
          this.StoryPane()
        }
        .width('100%').layoutWeight(1)
        .transition(TransitionEffect.OPACITY
          .combine(TransitionEffect.translate({ y: 10 }))
          .animation({ duration: MOTION_ENTER, curve: Curve.EaseOut }))
      } else {
        Column() {
          this.MorePane()
        }
        .width('100%').layoutWeight(1)
        .transition(TransitionEffect.OPACITY
          .combine(TransitionEffect.translate({ y: 10 }))
          .animation({ duration: MOTION_ENTER, curve: Curve.EaseOut }))
      }
    }
    .width('100%').height('100%')
  }

  build(): void {
    Stack() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
        Column() {
          // 顶栏(iOS NovelProjectWorkspaceView):返回玻璃圆 + 居中 principal
          // (项目名 + 模式·分支·模型,点开「项目控制」)+ 右侧导出玻璃圆。
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
                Icon({ name: 'share', iconSize: 17, color: INK2 })
              }
              .width(44).height(44).borderRadius(22).backgroundColor(SURFACE)
              .border({ width: 0.5, color: LINE })
              .justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
              .enabled(!this.busy && this.project !== null)
              .onClick((): void => { this.showExportMenu(); })
              .accessibilityText('导出')
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
            .enabled(this.project !== null)
            .onClick((): void => { this.openControlSheet(); })
            .accessibilityText('项目控制')
          }
          .width('100%').padding({ left: 16, right: 16, top: this.statusBarHeight + 10, bottom: 10 })
          .backgroundColor(BG)

          // 页签(iOS segmented:灰轨 + 白 pill)
          Seg({ options: ['创作', '正文', '设定'], selected: this.tab,
            onSelect: (idx: number): void => {
              if (idx === this.tab) return;
              animateTo({ duration: MOTION_ENTER, curve: Curve.EaseOut }, (): void => { this.tab = idx; });
            } })
            .margin({ left: 16, right: 16, top: 2, bottom: 8 })

          if (this.errorMsg.length > 0 && !this.chapterEditorOpen && !this.materialEditorOpen) {
            Text(this.errorMsg).fontSize(12).fontColor(ERROR).lineHeight(18)
              .width('100%').padding({ left: 20, right: 20, bottom: 8 })
          }

          if (this.project === null) {
            Column() {
              Text('项目无法打开').fontSize(15).fontColor(INK3)
            }
            .width('100%').layoutWeight(1).justifyContent(FlexAlign.Center)
          } else if (this.tab === 0) {
            Column() {
              this.chatPane()
            }
            .width('100%').layoutWeight(1)
            .transition(TransitionEffect.OPACITY
              .combine(TransitionEffect.translate({ y: 10 }))
              .animation({ duration: MOTION_ENTER, curve: Curve.EaseOut }))
          } else if (this.tab === 1) {
            Column() {
              this.chapterPane()
            }
            .width('100%').layoutWeight(1)
            .transition(TransitionEffect.OPACITY
              .combine(TransitionEffect.translate({ y: 10 }))
              .animation({ duration: MOTION_ENTER, curve: Curve.EaseOut }))
          } else {
            Column() {
              this.settingsPane()
            }
            .width('100%').layoutWeight(1)
            .transition(TransitionEffect.OPACITY
              .combine(TransitionEffect.translate({ y: 10 }))
              .animation({ duration: MOTION_ENTER, curve: Curve.EaseOut }))
          }
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
          this.materialEditor()
        }
        if (this.branchSheetOpen && this.workspaceStatus !== null) {
          NovelBranchSheet({
            visible: this.branchSheetOpen,
            activeBranchId: this.workspaceStatus.activeBranchId,
            branches: this.workspaceStatus.branches,
            busy: this.busy || this.novelJobLocksWorkspace(),
            canUndo: this.workspaceStatus.canUndo,
            unresolvedFromOrdinal: this.workspaceStatus.unresolvedFromOrdinal,
            plotStale: this.workspaceStatus.plotStale,
            onClose: (): void => { this.branchSheetOpen = false; },
            onCreate: (name: string): void => { this.createWorkspaceBranch(name); },
            onSelect: (branchId: string): void => { this.selectWorkspaceBranch(branchId); },
            onUndo: (): void => { this.undoWorkspace(); },
            onSyncPlot: (): void => { this.syncWorkspacePlot(); },
            onResolveUnresolved: (): void => { this.resolveWorkspaceUnresolved(); },
          })
        }
        if (this.polishCreateOpen) {
          NovelPolishCreateSheet({
            visible: this.polishCreateOpen,
            chapters: this.polishSheetChapters(),
            modelReady: this.ghostwriteModelsReady(),
            modelDisabledReason: this.ghostwriteModelReason(),
            conflictReason: this.polishConflictReason(),
            busy: this.polishBusy,
            bottomPadding: Math.max(this.navBarHeight, 12),
            onStart: (
              startOrdinal: number, endOrdinal: number, contextOptions: PolishContextOptions,
            ): void => { this.startPolish(startOrdinal, endOrdinal, contextOptions); },
            onClose: (): void => { if (!this.polishBusy) this.polishCreateOpen = false; },
          })
        }
        // 项目控制 sheet(顶栏 principal 触发,渲染最上层)
        if (this.controlSheetOpen) {
          this.ControlSheet()
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
      Blank().onClick((): void => { this.collectMessageId = ''; })
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
              this.SheetLabel('选择段落')
              Blank()
              Text('全选').fontSize(12).fontFamily(FONT_SANS_MEDIUM).fontColor(ACCENT)
                .constraintSize({ minHeight: 36 }).padding({ left: 10 })
                .onClick((): void => {
                  this.collectSelected = this.collectParas.map((): boolean => true);
                  this.resetDraftFromSelection();
                })
              Text('清空').fontSize(12).fontFamily(FONT_SANS_MEDIUM).fontColor(INK3)
                .constraintSize({ minHeight: 36 }).padding({ left: 10 })
                .onClick((): void => {
                  this.collectSelected = this.collectParas.map((): boolean => false);
                  this.collectDraft = '';
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
              this.SheetLabel('收录前编辑')
              Blank()
              Text('按当前选择重置').fontSize(12).fontFamily(FONT_SANS_MEDIUM).fontColor(ACCENT)
                .constraintSize({ minHeight: 36 })
                .onClick((): void => { this.resetDraftFromSelection(); })
            }.width('100%').alignItems(VerticalAlign.Center)
            TextArea({ text: this.collectDraft, placeholder: '收录进章节的正文…' })
              .width('100%').height(150).fontSize(13).fontColor(INK).placeholderColor(INK4)
              .backgroundColor(SURFACE2).borderRadius(12).padding(12)
              .onChange((v: string): void => { this.collectDraft = v; })

            Column() {}.height(14)
            this.SheetLabel('章节位置')
            Seg({ options: ['新开章节', '并入', '替换'], selected: this.collectMode,
              onSelect: (idx: number): void => { this.collectMode = idx; } })
            if (this.collectMode === 0) {
              TextInput({ text: this.collectTitle, placeholder: '章节标题' })
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
            .onClick((): void => { this.collectMessageId = ''; })
          Text(this.busy ? '收录中…' : '收录').fontSize(14).fontWeight(FontWeight.Medium)
            .fontFamily(FONT_SANS_MEDIUM).fontColor(this.collectDisabled() ? INK4 : ACCENT)
            .layoutWeight(1).textAlign(TextAlign.Center).constraintSize({ minHeight: 44 })
            .enabled(!this.collectDisabled())
            .onClick((): void => { this.submitCollect(); })
        }.width('100%').margin({ top: 8 })
      }
      .width('100%').height('85%')
      .padding({ left: 18, right: 18, top: 16, bottom: 14 + this.navBarHeight })
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
              Text(`第 ${idx + 1} 章${c.title.length > 0 ? ' · ' + c.title : ''}`)
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
        TextInput({ text: this.chapterTitle, placeholder: '章节标题' })
          .height(44).fontSize(14).fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(10).padding({ left: 14, right: 14 })
          .onChange((v: string) => { this.chapterTitle = v; })
        TextArea({ text: this.chapterContent, placeholder: '章节正文' })
          .height(180).fontSize(14).fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(10).padding(12).margin({ top: 12 })
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
            .height(44)
            .onClick(() => { this.confirmDiscardChapter(); })
          Text('保存').fontSize(14).fontColor(ACCENT).layoutWeight(1).textAlign(TextAlign.Center)
            .height(44)
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

  @Builder
  materialEditor(): void {
    Column() {
      Column() {
        Text(this.editingMaterialId.length > 0 ? '编辑资料' : '新建资料')
          .fontSize(17).fontWeight(FontWeight.Bold).fontColor(INK).margin({ bottom: 16 })
        // kind chips
        Row() {
          ForEach(KIND_FILTERS.filter(f => f !== 'all'), (f: MaterialFilter) => {
            Text(filterLabel(f))
              .fontSize(12).height(44).padding({ left: 10, right: 10 }).borderRadius(22)
              .textAlign(TextAlign.Center)
              .fontColor(this.materialKind === f ? ACCENT_INK : INK2)
              .backgroundColor(this.materialKind === f ? ACCENT : SURFACE2)
              .margin({ right: 6 })
              .onClick(() => { this.materialKind = f as NovelMaterialKind; })
          }, (f: MaterialFilter) => f)
        }
        .width('100%').margin({ bottom: 12 })

        TextInput({ text: this.materialTitle, placeholder: this.materialKind === 'character' ? '人物姓名' : '名称' })
          .height(44).fontSize(14).fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(10).padding({ left: 14, right: 14 })
          .onChange((v: string) => { this.materialTitle = v; })
        TextArea({ text: this.materialContent, placeholder: '资料内容' })
          .height(140).fontSize(14).fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(10).padding(12).margin({ top: 12 })
          .onChange((v: string) => { this.materialContent = v; })
        Row() {
          Text('参与写作上下文').fontSize(13).fontColor(INK2).layoutWeight(1)
          Toggle({ type: ToggleType.Switch, isOn: this.materialEnabled })
            .selectedColor(ACCENT)
            .onChange((v: boolean) => { this.materialEnabled = v; })
        }
        .width('100%').margin({ top: 12 })
        if (this.errorMsg.length > 0) {
          Scroll() {
            Text(this.errorMsg).fontSize(12).fontColor(ERROR).width('100%').wordBreak(WordBreak.BREAK_ALL)
          }
          .width('100%').constraintSize({ maxHeight: 54 }).align(Alignment.TopStart).margin({ top: 8 })
        }
        Row() {
          Text('取消').fontSize(14).fontColor(INK3).layoutWeight(1).textAlign(TextAlign.Center)
            .height(44)
            .onClick(() => { this.confirmDiscardMaterial(); })
          Text('保存').fontSize(14).fontColor(ACCENT).layoutWeight(1).textAlign(TextAlign.Center)
            .height(44)
            .onClick(() => { this.saveMaterial(); })
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
