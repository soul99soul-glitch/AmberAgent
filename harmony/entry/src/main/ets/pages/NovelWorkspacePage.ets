import { getNovelPlanDraftStore } from '../novel/NovelPlanDraftStore.ets';
// 页面持有工作区快照、路由和可见生命周期；具体编辑与运行会话由观察对象持有。
import { ACCENT, BG, ERROR, INK, INK2, INK3, INK4, LINE, MOTION_ENTER, SURFACE } from '../design/tokens.ets';
import { FONT_SANS_BOLD, FONT_SANS_MEDIUM } from '../design/fonts.ets';
import { Icon } from '../components/ui/Icon.ets';
import { Seg, CardRow } from '../components/PageTemplate.ets';
import { StudioTabs } from '../components/studio/StudioTabs.ets';
import { getProductKind } from '../platform_impl/ProductIdentity.ets';
import { NovelDiscussionArchiveSheet } from '../components/NovelDiscussionArchiveSheet.ets';
import { NovelPolishCreateSheet } from '../components/NovelPolishCreateSheet.ets';
import { NovelPolishJobCard } from '../components/NovelPolishJobCard.ets';
import { NovelBranchSheet } from '../components/NovelBranchSheet.ets';
import { NovelSettingsPane, NovelModelOptionView } from '../components/NovelSettingsPane.ets';
import { NovelAuditReportView } from '../components/NovelAuditReportView.ets';
import { NovelRevisionBriefSheet } from '../components/NovelRevisionBriefSheet.ets';
import { NovelGhostwriteStartSheet } from '../components/NovelGhostwriteStartSheet.ets';
import { NovelGhostwriteCard } from '../components/NovelGhostwriteCard.ets';
import { KeyboardAvoidMode, router, promptAction } from '@kit.ArkUI';
import { fileIo, picker } from '@kit.CoreFileKit';
import { common } from '@kit.AbilityKit';
import { getNovelCreation, getNovelWorkspaceService, loadNovelModelOptions, describeChatModel } from '../di/AppContainer.ets';
import type { NovelModelOption } from '../di/AppContainer.ets';
import type { NovelBookExportBundle } from '../platform_impl/NovelWorkspaceService.ets';
import { shareNovelFile, NovelShareFormat } from '../platform_impl/NovelShareSupport.ets';
import { NovelProject, NovelMessage, NovelBranch, NovelActiveRun, NovelOrdinaryRunView, NovelSettingProposal, NovelWorkspaceStatus, NovelModelPolicy, NovelModelDefaults, NovelBranchSettings, NovelAuditIssue, NovelAuditReference, DEFAULT_GHOSTWRITE_CHAPTER_COUNT, DurableGhostwriteJob, DurablePolishJob, PolishContextOptions, NovelGhostwriteStartPreview, NovelGhostwriteReport } from '@amber/deepread-domain';
import { composerOwner } from '../novel/NovelComposerState';
import { getNovelComposerDraftStore } from '../novel/NovelComposerDraftStore.ets';
import { loadNovelModelDefaults } from '../platform_impl/NovelStandaloneDefaults.ets';


import { NovelDiscussionSession } from '../platform_impl/novel/NovelDiscussionSession.ets';
import type { NovelDiscussionPorts } from '../platform_impl/novel/NovelDiscussionSession.ets';
import { NovelJobSession } from '../platform_impl/novel/NovelJobSession.ets';
import type { NovelJobSessionPorts } from '../platform_impl/novel/NovelJobSession.ets';
import { NovelAuditSession } from '../platform_impl/novel/NovelAuditSession.ets';
import type { NovelAuditSessionPorts } from '../platform_impl/novel/NovelAuditSession.ets';
import { NovelWritingControlSession } from '../platform_impl/novel/NovelWritingControlSession.ets';
import type { NovelWritingControlPorts } from '../platform_impl/novel/NovelWritingControlSession.ets';
import { NovelManuscriptSession } from '../platform_impl/novel/NovelManuscriptSession.ets';
import type { NovelManuscriptPorts } from '../platform_impl/novel/NovelManuscriptSession.ets';
import { NovelCompendiumSession } from '../platform_impl/novel/NovelCompendiumSession.ets';
import type { NovelCompendiumPorts } from '../platform_impl/novel/NovelCompendiumSession.ets';
import { NovelDiscussionView } from '../components/novel/NovelDiscussionView.ets';
import { NovelWritingControlView } from '../components/novel/NovelWritingControlView.ets';
import { NovelCompendiumView, NovelMaterialEditorOverlay, NovelMaterialAdoptionOverlay } from '../components/novel/NovelCompendiumView.ets';
import { NovelManuscriptView } from '../components/novel/NovelManuscriptView.ets';
import { NovelManuscriptSheets } from '../components/novel/NovelManuscriptSheets.ets';
interface ExportMenuResult { index: number; }

@Entry
@Component
struct NovelWorkspacePage {
  @StorageProp('themeEpoch') @Watch('clearStudioFeedback') themeEpoch: number = 0;
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @StorageProp('appBackgrounded') @Watch('studioBackgroundChanged') private studioBackgrounded: boolean = false;
  @StorageProp('reduceMotion') private reduceMotion: boolean = false;
  @State project: NovelProject | null = null;
  @State workspaceStatus: NovelWorkspaceStatus | null = null;
  @State busy: boolean = false;
  @State errorMsg: string = '';
  @State branchSheetOpen: boolean = false;
  @State tab: number = 0;
  @State private studioPageVisible: boolean = true;
  @State private collectionStamp: number = 0;
  @State private studioTabDirection: number = 1;
  @State discussion: NovelDiscussionSession = new NovelDiscussionSession();
  @State jobs: NovelJobSession = new NovelJobSession();
  @State audit: NovelAuditSession = new NovelAuditSession();
  @State writing: NovelWritingControlSession = new NovelWritingControlSession();
  @State manuscript: NovelManuscriptSession = new NovelManuscriptSession();
  @State compendium: NovelCompendiumSession = new NovelCompendiumSession();

  private projectId: string = '';
  private requestedBranchId: string = '';
  private requestedPolishJobId: string = '';
  private pageAlive: boolean = false;
  private pageHiddenOnce: boolean = false;
  private reloadToken: number = 0;
  private studioVisibleCycle: number = 0;
  private keyboardAvoidanceActive: boolean = false;
  private previousKeyboardAvoidMode: KeyboardAvoidMode = KeyboardAvoidMode.OFFSET;

  private startSessions(): void {
    const creation = getNovelCreation();
    const discussionPorts: NovelDiscussionPorts = {
      creation, composerDraftStore: getNovelComposerDraftStore(),
      projectId: (): string => this.projectId, currentProject: (): NovelProject | null => this.project,
      currentStatus: (): NovelWorkspaceStatus | null => this.workspaceStatus,
      isBusy: (): boolean => this.busy, setBusy: (value: boolean): void => { this.busy = value; },
      error: (value: string): void => { this.errorMsg = value; },
      operationError: (action: string, error: Object): void => { this.showWorkspaceOperationError(action, error); },
      hasBlockingNovelRun: (): boolean => this.hasBlockingNovelRun(),
      jobLocksWorkspace: (): boolean => this.jobs.novelJobLocksWorkspace(),
      currentGhostwriteJob: (): DurableGhostwriteJob | null => this.jobs.ghostwriteJob,
      currentGhostwriteReport: (): NovelGhostwriteReport | null => this.jobs.ghostwriteReport,
      reload: (): Promise<void> => this.reload(), reloadToken: (): number => this.reloadToken,
      selectedTab: (): number => this.tab, backgrounded: (): boolean => this.studioBackgrounded,
      closeControl: (): void => { this.writing.closeControlSheet(); }, uiContext: () => this.getUIContext(),
    };
    this.discussion.start(discussionPorts);
    const jobPorts: NovelJobSessionPorts = {
      creation, projectId: (): string => this.projectId, currentProject: (): NovelProject | null => this.project,
      currentStatus: (): NovelWorkspaceStatus | null => this.workspaceStatus,
      requestedPolishJobId: (): string => this.requestedPolishJobId,
      clearRequestedPolishJob: (): void => { this.requestedPolishJobId = ''; },
      isBusy: (): boolean => this.busy, hasBlockingNovelRun: (): boolean => this.hasBlockingNovelRun(),
      hasOrdinaryRun: (): boolean => this.discussion.activeRunId.length > 0,
      publishWorkspace: (project: NovelProject, status: NovelWorkspaceStatus): void => { this.project = project; this.workspaceStatus = status; },
      reload: (): Promise<void> => this.reload(), error: (value: string): void => { this.errorMsg = value; },
      operationError: (action: string, error: Object): void => { this.showWorkspaceOperationError(action, error); },
      closeControl: (): void => { this.writing.closeControlSheet(); },
      showJobSettings: (): void => { this.compendium.settingTab = 3; },
    };
    this.jobs.start(jobPorts);
    const auditPorts: NovelAuditSessionPorts = {
      creation, projectId: (): string => this.projectId, currentProject: (): NovelProject | null => this.project,
      currentStatus: (): NovelWorkspaceStatus | null => this.workspaceStatus,
      isBusy: (): boolean => this.busy, setBusy: (value: boolean): void => { this.busy = value; },
      hasBlockingNovelRun: (): boolean => this.hasBlockingNovelRun(),
      error: (value: string): void => { this.errorMsg = value; },
      operationError: (action: string, error: Object): void => { this.showWorkspaceOperationError(action, error); },
    };
    this.audit.start(auditPorts);
    const writingPorts: NovelWritingControlPorts = {
      creation, planDraftStore: getNovelPlanDraftStore(), isNovelProduct: getProductKind() === 'novel',
      loadModelOptions: async (): Promise<NovelModelOptionView[]> => {
        const options: NovelModelOption[] = await loadNovelModelOptions();
        return options.map((option: NovelModelOption): NovelModelOptionView => ({ providerId: option.providerId,
          modelId: option.modelId, label: option.label, providerLabel: option.providerLabel, available: option.available }));
      },
      loadModelDefaults: (): Promise<NovelModelDefaults> => loadNovelModelDefaults(),
      describeChatModel: (): Promise<string> => describeChatModel(),
      currentProject: (): NovelProject | null => this.project, workspaceStatus: (): NovelWorkspaceStatus | null => this.workspaceStatus,
      projectId: (): string => this.projectId, hasGhostwriteJob: (): boolean => this.jobs.ghostwriteJob !== null,
      isBusy: (): boolean => this.busy, setBusy: (value: boolean): void => { this.busy = value; },
      isAlive: (): boolean => this.pageAlive, isVisible: (): boolean => this.studioPageVisible,
      isBackgrounded: (): boolean => this.studioBackgrounded, getError: (): string => this.errorMsg,
      setError: (value: string): void => { this.errorMsg = value; },
      showOperationError: (action: string, error: Object): void => { this.showWorkspaceOperationError(action, error); },
      reload: (): Promise<void> => this.reload(),
    };
    this.writing.start(writingPorts);
    const manuscriptPorts: NovelManuscriptPorts = {
      creation, currentProject: (): NovelProject | null => this.project,
      workspaceStatus: (): NovelWorkspaceStatus | null => this.workspaceStatus, projectId: (): string => this.projectId,
      busy: (): boolean => this.busy, setBusy: (value: boolean): void => { this.busy = value; },
      error: (): string => this.errorMsg, setError: (value: string): void => { this.errorMsg = value; },
      reload: (): Promise<void> => this.reload(), pageAlive: (): boolean => this.pageAlive,
      pageHidden: (): boolean => this.pageHiddenOnce, hasBlockingNovelRun: (): boolean => this.hasBlockingNovelRun(),
      novelJobLocksWorkspace: (): boolean => this.jobs.novelJobLocksWorkspace(), visibleCycle: (): number => this.studioVisibleCycle,
      onCollected: (projectId: string, branchId: string, visibleCycle: number): void => { this.onManuscriptCollected(projectId, branchId, visibleCycle); },
    };
    this.manuscript.start(manuscriptPorts);
    const compendiumPorts: NovelCompendiumPorts = {
      creation, projectId: (): string => this.projectId, currentProject: (): NovelProject | null => this.project,
      currentStatus: (): NovelWorkspaceStatus | null => this.workspaceStatus, pageAlive: (): boolean => this.pageAlive,
      isBusy: (): boolean => this.busy, setBusy: (value: boolean): void => { this.busy = value; },
      readError: (): string => this.errorMsg, setError: (value: string): void => { this.errorMsg = value; },
      reload: (): Promise<void> => this.reload(), hasBlockingNovelRun: (): boolean => this.hasBlockingNovelRun(),
      showOperationError: (action: string, error: Error | string): void => { this.showWorkspaceOperationError(action, error); },
    };
    this.compendium.start(compendiumPorts);
  }

  aboutToAppear(): void {
    this.pageAlive = true;
    this.studioPageVisible = true;
    this.enableKeyboardAvoidance();
    const params = router.getParams() as Record<string, string>;
    this.projectId = params ? (params['projectId'] ?? '') : '';
    this.requestedBranchId = params ? (params['branchId'] ?? '') : '';
    this.requestedPolishJobId = params ? (params['jobId'] ?? '') : '';
    this.discussion.pendingQuickStartSeed = params ? (params['quickStartSeed'] ?? '') : '';
    if (params && params['tab'] === 'chapters') this.tab = 1;
    this.startSessions();
    this.reload();
    this.writing.loadProjectModelOptions();
  }

  onPageHide(): void {
    this.writing.hide();
    this.studioPageVisible = false;
    this.jobs.hide();
    this.audit.hide();
    this.compendium.invalidateMaterialOpen();
    this.discussion.hide();
    this.clearStudioFeedback();
    this.pageHiddenOnce = true;
    this.restoreKeyboardAvoidance();
  }

  onPageShow(): void {
    this.studioPageVisible = true;
    this.discussion.show(); this.jobs.show(); this.audit.show();
    this.enableKeyboardAvoidance();
    if (this.pageHiddenOnce) {
      this.pageHiddenOnce = false;
      this.writing.loadProjectModelOptions();
      this.reload().then((): void => { if (this.pageAlive) this.discussion.consumeContextOverrides(); });
    }
  }

  aboutToDisappear(): void {
    this.writing.hide();
    this.studioPageVisible = false;
    this.jobs.dispose(); this.audit.dispose(); this.discussion.dispose();
    this.compendium.invalidateMaterialOpen();
    this.clearStudioFeedback();
    this.restoreKeyboardAvoidance();
    this.pageAlive = false;
    this.reloadToken++;
  }

  private hasBlockingNovelRun(): boolean {
    return this.discussion.sendStarting || this.discussion.composerRestoring ||
      this.discussion.activeRunId.length > 0 || this.jobs.novelJobLocksWorkspace();
  }

  private onManuscriptCollected(projectId: string, branchId: string, visibleCycle: number): void {
    this.studioTabDirection = this.tab < 1 ? 1 : -1;
    this.tab = 1;
    this.showCollectionStamp(projectId, branchId, visibleCycle);
  }

  private studioBackgroundChanged(): void {
    if (this.studioBackgrounded) {
      this.writing.background();
      this.clearStudioFeedback();
    }
  }

  private clearStudioFeedback(): void {
    this.studioVisibleCycle++;
    this.collectionStamp = 0;
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

  onBackPress(): boolean {
    return this.closeTopOverlay();
  }

  private handleBack(): void {
    if (!this.closeTopOverlay()) router.back();
  }

  private closeTopOverlay(): boolean {
    // 渲染顺序:后面的 child 更靠上,故按逆序检查
    if (this.compendium.adoptionOpen) {
      this.compendium.cancelAdoption();
      return true;
    }
    if (this.discussion.archiveOpen) {
      this.discussion.cancelArchive();
      return true;
    }
    if (this.writing.composerModelSheetOpen) {
      this.writing.closeComposerModelSheet(this.writing.composerModelSession);
      return true;
    }
    if (this.jobs.ghostwriteBriefOpen) {
      this.jobs.ghostwriteBriefCloseRequest++;
      return true;
    }
    if (this.writing.controlSheetOpen) {
      this.writing.closeControlSheet();
      return true;
    }
    if (this.jobs.ghostwritePreview !== null) {
      if (!this.jobs.ghostwriteBusy) this.jobs.ghostwritePreview = null;
      return true;
    }
    if (this.jobs.polishCreateOpen) {
      if (!this.jobs.polishBusy) this.jobs.polishCreateOpen = false;
      return true;
    }
    if (this.branchSheetOpen) {
      if (!this.busy) this.branchSheetOpen = false;
      return true;
    }
    if (this.compendium.materialEditorOpen) {
      this.compendium.confirmDiscardMaterial();
      return true;
    }
    if (this.manuscript.chapterEditorOpen) {
      this.manuscript.confirmDiscardChapter();
      return true;
    }
    if (this.manuscript.collectMessageId.length > 0) {
      this.manuscript.confirmDiscardCollect();
      return true;
    }
    return false;
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
        await this.discussion.restoreComposerDraft(composerOwner(projectId, status.activeBranchId));
        if (!this.pageAlive || token !== this.reloadToken) return;
        if (this.workspaceStatus?.activeBranchId !== status.activeBranchId) {
          this.discussion.expandedArchiveIds = [];
          this.manuscript.clearCollectionFeedback();
          this.clearStudioFeedback();
        }
        const workspaceChanged: boolean = this.project === null ||
          this.workspaceStatus?.activeBranchId !== status.activeBranchId;
        this.project = project;
        this.discussion.completedPreview = [];
        this.workspaceStatus = status;
        this.discussion.ordinaryRecovery = recovery.record?.branchId === status.activeBranchId ? recovery : null;
        if (this.discussion.contextOverrides !== null && this.discussion.contextOverrides.branchId !== status.activeBranchId) this.discussion.contextOverrides = null;
        this.requestedBranchId = '';
        this.jobs.ghostwriteJob = ghostwrite;
        this.jobs.ghostwriteReport = ghostwriteReport;
        this.jobs.polishJob = polish;
        if (ghostwrite !== null && getProductKind() !== 'novel') this.compendium.settingTab = 3;
        this.jobs.ghostwriteModelAvailable = modelAvailable;
        this.jobs.ghostwriteModelIssue = modelIssue;
        // 等 List 收到新 rows 的可见范围回调再落底；Promise 返回后的
        // setTimeout 仍可能早于 ArkUI 完成 ForEach 布局。
        if (workspaceChanged) this.discussion.initialScrollArmed = project.messages.length > 0 || activeRun !== null;
        if (activeRun !== null &&
          (this.discussion.activeRunId !== activeRun.run.id || this.discussion.currentUnsub === null)) {
          this.discussion.attachNovelRun(
            activeRun.run, activeRun.historyCount, activeRun.userText,
            activeRun.startedAt, '正在继续离页前的生成…', activeRun.runKind === 'quick_start', activeRun.runKind ?? null);
        } else if (activeRun === null && this.discussion.currentUnsub === null && this.discussion.activeRunId.length > 0) {
          this.discussion.clearNovelRunUi();
        }
        this.discussion.scheduleRecoverableTool(project);
        this.jobs.scheduleGhostwritePoll();
        this.jobs.schedulePolishPoll();
        this.discussion.maybeFireQuickStart(project, activeRun);
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

  private openProjectPage(url: string): void {
    if (this.busy || this.hasBlockingNovelRun()) return;
    this.writing.controlSheetOpen = false;
    router.pushUrl({ url, params: { projectId: this.projectId } })
      .catch((error: Error): void => { if (this.pageAlive) this.errorMsg = String(error); });
  }

  private openPolishPage(): void {
    if (this.busy) return;
    this.writing.controlSheetOpen = false;
    router.pushUrl({ url: 'pages/NovelPolishPage', params: { projectId: this.projectId } })
      .catch((error: Error): void => { if (this.pageAlive) this.errorMsg = String(error); });
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
    if (this.busy || this.jobs.ghostwriteBusy || this.jobs.polishBusy || this.discussion.sendStarting || this.discussion.composerRestoring ||
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
    if (this.busy || this.jobs.ghostwriteBusy || this.jobs.polishBusy || this.discussion.sendStarting || this.discussion.composerRestoring) return;
    this.busy = true;
    try {
      const creation = getNovelCreation();
      await creation.stopActiveRun(this.projectId);
      const ghostwrite: DurableGhostwriteJob | null = this.jobs.ghostwriteJob;
      if (ghostwrite !== null) {
        await creation.cancelGhostwrite(this.projectId, ghostwrite.jobId);
        this.jobs.ghostwritePollToken++;
        this.jobs.ghostwriteJob = null;
      }
      const polish: DurablePolishJob | null = this.jobs.polishJob;
      if (polish !== null && this.jobs.polishLocksWorkspace()) {
        this.jobs.polishJob = await creation.cancelPolish(this.projectId, polish.jobId);
        this.jobs.polishPollToken++;
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

  private showChapterToolsMenu(): void {
    if (this.busy || this.project === null) return;
    promptAction.showActionMenu({
      buttons: [
        { text: '一致性检查', color: $r('sys.color.ohos_id_color_text_primary') },
        { text: this.workspaceStatus?.canUndo && !this.jobs.novelJobLocksWorkspace() ? '撤销上次工作区操作' : '撤销（当前不可用）',
          color: this.workspaceStatus?.canUndo && !this.jobs.novelJobLocksWorkspace() ? $r('sys.color.ohos_id_color_text_primary') : $r('sys.color.ohos_id_color_text_tertiary') },
        { text: this.jobs.polishConflictReason().length > 0 ? `批量润色（${this.jobs.polishConflictReason()}）` : '批量润色',
          color: this.jobs.polishConflictReason().length > 0 ? $r('sys.color.ohos_id_color_text_tertiary') : $r('sys.color.ohos_id_color_text_primary') },
      ],
    }).then((result: ExportMenuResult): void => {
      if (!this.pageAlive || this.busy) return;
      if (result.index === 0) this.audit.runConsistencyCheck();
      else if (result.index === 1) {
        if (this.workspaceStatus?.canUndo && !this.jobs.novelJobLocksWorkspace()) this.undoWorkspace();
      } else if (result.index === 2) {
        if (this.jobs.polishConflictReason().length === 0) {
          this.requestedPolishJobId = '';
          this.jobs.singlePolishChapterId = '';
          this.jobs.polishStartError = '';
          this.jobs.polishCreateOpen = true;
        }
      }
    }).catch((): void => {});
  }

  private workspaceSubtitle(): string {
    const mode: string = this.jobs.ghostwriteJob !== null ? '代笔' : '共创';
    const branch: string = this.workspaceStatus !== null ? this.workspaceStatus.activeBranchName : '';
    return `${mode} · ${branch} · ${this.writing.writingModelLabel(this.project)}`;
  }

  @Builder
  private GhostwriteStartPreviewSheet(preview: NovelGhostwriteStartPreview): void {
    NovelGhostwriteStartSheet({
      planContent: this.jobs.ghostwriteAuthorPlanPreview(preview), branchName: preview.branchName,
      chapterCount: preview.targetChapterCount, busy: this.jobs.ghostwriteBusy,
      errorMessage: this.jobs.ghostwritePreviewError, bottomPadding: Math.max(this.navBarHeight, 12),
      onConfirm: (): void => { this.jobs.confirmGhostwriteStart(); },
      onClose: (): void => { if (!this.jobs.ghostwriteBusy) this.jobs.ghostwritePreview = null; },
    })
  }

  @Builder
  private ControlOps(): void {
    CardRow({ label: '预览本次上下文', desc: '查看资料选择与预算，只调整下一次普通创作',
      onRowClick: (): void => { this.discussion.openContextPreview(); } })
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
    if (this.jobs.ghostwriteJob?.stage === 'paused' || this.jobs.ghostwriteJob?.stage === 'failed') {
      CardRow({ label: '按要求返修代笔', desc: '保留当前候选作为来源，只对下一稿生效',
        onRowClick: (): void => { this.jobs.openGhostwriteBrief(); } }).enabled(!this.jobs.ghostwriteBusy)
    }
    CardRow({ label: '项目创建设置', desc: '创作方式、原始种子与项目润色偏好',
      onRowClick: (): void => {
        if (this.busy || this.hasBlockingNovelRun()) return;
        this.writing.controlSheetOpen = false;
        router.pushUrl({ url: 'pages/NovelProjectSetupPage', params: { projectId: this.projectId } })
          .catch((error: Error): void => { if (this.pageAlive) this.errorMsg = String(error); });
      } })
      .enabled(!this.busy && !this.hasBlockingNovelRun())
      .opacity(this.busy || this.hasBlockingNovelRun() ? 0.5 : 1)
    CardRow({ label: '分支管理', desc: '创建、切换与撤销分支',
      onRowClick: (): void => {
        if (this.busy) return;
        this.writing.controlSheetOpen = false;
        this.branchSheetOpen = true;
      } })
      .enabled(!this.busy)
      .opacity(this.busy ? 0.5 : 1)
    CardRow({ label: '导出作品', desc: '公开工作区 / 完整备份 / TXT / Markdown / EPUB', isLast: true,
      onRowClick: (): void => {
        this.writing.controlSheetOpen = false;
        this.showExportMenu();
      } })
  }

  @Builder
  private SheetLabel(label: string): void {
    Text(label).fontSize(12).fontFamily(FONT_SANS_MEDIUM).fontColor(INK3)
      .width('100%').margin({ bottom: 8 })
  }

  @Builder
  private ProjectModelRolesPane(): void {
    if (this.project !== null) {
      NovelSettingsPane({
        project: this.project,
        modelOptions: this.writing.modelOptions,
        busy: this.busy || this.jobs.novelJobLocksWorkspace(),
        showPlanSections: false,
        showStorySections: false,
        showMaterialsEntry: false,
        onSaveModelPolicy: (policy: NovelModelPolicy): void => {
          this.writing.saveNovelModelPolicy(policy);
        },
        onSaveBranchSettings: (settings: NovelBranchSettings): void => {
          this.compendium.saveNovelBranchSettings(settings);
        },
        onNewMaterial: (): void => {
          this.compendium.openNewMaterial('other');
        },
      })
    }
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
            .onClick((): void => { this.writing.openControlSheet(); })
            .accessibilityText('项目控制')
          }
          .width('100%').padding({ left: 16, right: 16, top: this.statusBarHeight + 10, bottom: 10 })
          .backgroundColor(BG)

          // 页签(iOS segmented:灰轨 + 白 pill)
          Row() {
            if (getProductKind() === 'novel') {
              StudioTabs({ options: ['创作', '正文', '设定'], selected: this.tab,
                active: this.studioPageVisible && !this.studioBackgrounded,
                onSelect: (idx: number): void => { this.selectStudioTab(idx); } })
            } else {
              Seg({ options: ['创作', '正文', '设定'], selected: this.tab,
                onSelect: (idx: number): void => {
                  if (idx === this.tab) return;
                  animateTo({ duration: MOTION_ENTER, curve: Curve.EaseOut }, (): void => { this.tab = idx; });
                } })
            }
          }
          .width('100%')
          .padding({ left: 16, right: 16, top: 2, bottom: 8 })

          if (this.errorMsg.length > 0 && !this.manuscript.chapterEditorOpen && !this.compendium.materialEditorOpen) {
            Text(this.errorMsg).fontSize(12).fontColor(ERROR).lineHeight(18)
              .width('100%').padding({ left: 20, right: 20, bottom: 8 })
          }

          if (this.discussion.contextOverrides !== null) {
            Row() {
              Text(`本次上下文：包含 ${this.discussion.contextOverrides.forceIncludeMaterialIds.length} 项，排除 ${this.discussion.contextOverrides.forceExcludeMaterialIds.length} 项。仅下一次普通创作有效。`)
                .fontSize(12).fontColor(INK3).lineHeight(18).layoutWeight(1)
              Text('清除').fontSize(13).fontColor(ACCENT).height(44).padding({ left: 12, right: 12 })
                .onClick((): void => { this.discussion.contextOverrides = null; })
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
                NovelDiscussionView({ session: this.discussion, jobs: this.jobs, project: this.project,
                  workspaceStatus: this.workspaceStatus, busy: this.busy, blocking: this.hasBlockingNovelRun(),
                  navBarHeight: this.navBarHeight, writingLabel: this.writing.writingModelLabel(this.project),
                  onOpenModel: (): void => { this.writing.openComposerModelSheet(); },
                  onCollect: (id: string): void => { this.manuscript.openCollect(id); },
                  onCloneCollected: (message: NovelMessage): void => { this.manuscript.confirmCloneCollected(message); },
                  onResolveSettingProposal: (proposal: NovelSettingProposal, accept: boolean): void => { this.compendium.resolveSettingProposal(proposal, accept); },
                  onReload: (): void => { this.reload(); } })
              }
              .width('100%').height('100%')
              .transition(this.workspacePaneTransition())
            } else if (this.tab === 1) {
              Column() {
                NovelManuscriptView({ session: this.manuscript, project: this.project,
                  collectionStamp: this.collectionStamp, showStamp: getProductKind() === 'novel',
                  stampActive: this.studioPageVisible && !this.studioBackgrounded && this.tab === 1,
                  consistencyMessage: this.audit.consistencyMessage,
                  onTools: (): void => { this.showChapterToolsMenu(); },
                  onOpenChapter: (id: string): void => { router.pushUrl({ url: 'pages/NovelChapterReaderPage', params: { projectId: this.projectId, chapterId: id } }); },
                  polishContent: (): void => { this.PolishJobSection(); } })
              }
              .width('100%').height('100%')
              .transition(this.workspacePaneTransition())
            } else {
              Column() {
                NovelCompendiumView({ session: this.compendium, project: this.project, busy: this.busy,
                  blocking: this.hasBlockingNovelRun(), active: this.studioPageVisible && !this.studioBackgrounded,
                  novelProduct: getProductKind() === 'novel', paneDuration: this.studioPaneDuration(),
                  onReanalyze: (chapterId?: string): void => { this.manuscript.reanalyze(chapterId ?? ''); },
                  analysisFailureText: (error: string): string => this.manuscript.materialAnalysisFailureText(error),
                  auditContent: (): void => { this.AuditSection(); }, modelRolesContent: (): void => { this.ProjectModelRolesPane(); } })
              }
              .width('100%').height('100%')
              .transition(this.workspacePaneTransition())
            }
          }.width('100%').layoutWeight(1)
        }
        .width('100%').height('100%').backgroundColor(BG)

        if (this.manuscript.collectMessageId.length > 0 || this.manuscript.chapterEditorOpen) {
          NovelManuscriptSheets({ session: this.manuscript, project: this.project, busy: this.busy,
            errorMsg: this.errorMsg, navBarHeight: this.navBarHeight })
        }
        if (this.compendium.materialEditorOpen) {
          NovelMaterialEditorOverlay({ session: this.compendium, busy: this.busy,
            errorMessage: this.errorMsg, bottomPadding: Math.max(this.navBarHeight, 12) })
        }
        if (this.branchSheetOpen && this.workspaceStatus !== null) {
          NovelBranchSheet({
            visible: this.branchSheetOpen,
            activeBranchId: this.workspaceStatus.activeBranchId,
            branches: this.workspaceStatus.branches,
            busy: this.busy || this.hasBlockingNovelRun(),
            switchBusy: this.busy || this.jobs.ghostwriteBusy || this.jobs.polishBusy || this.discussion.sendStarting || this.discussion.composerRestoring,
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
        if (this.jobs.polishCreateOpen) {
          NovelPolishCreateSheet({
            visible: this.jobs.polishCreateOpen,
            singleChapter: this.jobs.singlePolishChapterId.length > 0,
            chapters: this.jobs.polishSheetChapters(),
            modelReady: this.jobs.ghostwriteModelsReady(),
            modelDisabledReason: this.jobs.ghostwriteModelReason(),
            conflictReason: this.jobs.polishConflictReason(),
            errorMessage: this.jobs.polishStartError,
            busy: this.jobs.polishBusy,
            bottomPadding: Math.max(this.navBarHeight, 12),
            onStart: (
              startOrdinal: number, endOrdinal: number, contextOptions: PolishContextOptions, ordinals: number[],
            ): void => { this.jobs.startPolish(startOrdinal, endOrdinal, contextOptions, ordinals); },
            onClose: (): void => { if (!this.jobs.polishBusy) this.jobs.polishCreateOpen = false; },
          })
        }
        if (this.jobs.ghostwritePreview !== null) {
          this.GhostwriteStartPreviewSheet(this.jobs.ghostwritePreview)
        }
        if (this.jobs.ghostwriteBriefOpen) {
          NovelRevisionBriefSheet({ draftKey: this.jobs.ghostwriteBriefKey, closeRequest: this.jobs.ghostwriteBriefCloseRequest,
            sheetTitle: '按要求返修代笔', detail: this.jobs.ghostwriteBriefDetail,
            placeholder: '输入本次返修要求（最多 2400 字）', confirmLabel: '开始定向返修', busy: this.jobs.ghostwriteBusy,
            errorMessage: this.jobs.ghostwriteBriefError, bottomPadding: Math.max(this.navBarHeight, 12),
            onConfirm: (brief: string): void => { this.jobs.submitGhostwriteBrief(brief); },
            onCancel: (): void => { if (!this.jobs.ghostwriteBusy) this.jobs.ghostwriteBriefOpen = false; } })
        }
        // 项目控制 sheet(顶栏 principal 触发,渲染最上层)
        if (this.writing.controlSheetOpen) {
          NovelWritingControlView({ session: this.writing, project: this.project, sheet: 'control', busy: this.busy,
            projectName: this.project?.name ?? '', isNovelProduct: getProductKind() === 'novel',
            navBarHeight: this.navBarHeight, studioPageVisible: this.studioPageVisible,
            studioBackgrounded: this.studioBackgrounded, reduceMotion: this.reduceMotion,
            jobSection: (): void => { this.GhostwriteJobSection(); }, actionSection: (): void => { this.ControlOps(); } })
        }
        if (this.writing.composerModelSheetOpen) {
          NovelWritingControlView({ session: this.writing, project: this.project, sheet: 'model', busy: this.busy,
            isNovelProduct: getProductKind() === 'novel', navBarHeight: this.navBarHeight,
            studioPageVisible: this.studioPageVisible, studioBackgrounded: this.studioBackgrounded, reduceMotion: this.reduceMotion })
        }
        if (this.discussion.archiveOpen) {
          NovelDiscussionArchiveSheet({
            visible: this.discussion.archiveOpen, draftKey: this.discussion.archiveDraftKey,
            sourceMessageCount: this.discussion.archiveSourceCount, sourceRangeLabel: this.discussion.archiveSourceRange,
            initialSummary: this.discussion.archiveSummary, decisions: this.discussion.archiveDecisions,
            busy: this.busy, errorMessage: this.discussion.archiveError, bottomPadding: Math.max(this.navBarHeight, 12),
            onConfirm: (summary: string, indexes: number[]): void => { this.discussion.confirmArchive(summary, indexes); },
            onCancel: (): void => { this.discussion.cancelArchive(); },
          })
        }
        if (this.compendium.adoptionOpen) {
          NovelMaterialAdoptionOverlay({ session: this.compendium, busy: this.busy,
            bottomPadding: Math.max(this.navBarHeight, 12) })
        }
      }, (e: number): string => `theme-${e}`)
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%')
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }
  @Builder
  private GhostwriteJobSection(): void {
    NovelGhostwriteCard({ job: this.jobs.ghostwriteJob,
      suggestedChapterCount: this.project?.branchSettings.suggestedChapterCount ?? DEFAULT_GHOSTWRITE_CHAPTER_COUNT,
      planReady: this.jobs.ghostwritePlanReady(), modelReady: this.jobs.ghostwriteModelsReady(),
      planDisabledReason: '请先保存本章计划。', modelDisabledReason: this.jobs.ghostwriteModelReason(), busy: this.jobs.ghostwriteBusy,
      onStart: (count: number): void => { this.jobs.startGhostwrite(count); }, onPause: (): void => { this.jobs.pauseGhostwrite(); },
      onResume: (): void => { this.jobs.resumeGhostwrite(); }, onRetry: (): void => { this.jobs.retryGhostwrite(); },
      onCancel: (): void => { this.jobs.cancelGhostwrite(); } })
  }
  @Builder
  private PolishJobSection(): void {
    if (this.jobs.polishJob !== null) {
      NovelPolishJobCard({ job: this.jobs.polishJob, busy: this.jobs.polishBusy,
        onPause: (): void => { this.jobs.pausePolish(); }, onResume: (): void => { this.jobs.resumePolish(); },
        onRetry: (): void => { this.jobs.retryPolish(); }, onCancel: (): void => { this.jobs.cancelPolish(); } })
        .margin({ left: 16, right: 16, bottom: 10 })
    }
  }
  @Builder
  private AuditSection(): void {
    Column() {
        this.SheetLabel('连续性审计')
        Text('逐块覆盖全部有效章节，核查有原文依据的连续性问题。内部职责模型未单独配置时使用写作模型。')
          .fontSize(11).fontColor(INK3).lineHeight(17).margin({ bottom: 8 })
        Row() {
          Text(this.audit.auditRunning ? '正在审校' : '开始全文审校').fontSize(13).fontColor(this.audit.auditRunning || this.busy ? INK4 : ACCENT)
            .constraintSize({ minHeight: 44 }).enabled(!this.audit.auditRunning && !this.busy)
            .onClick((): void => { this.audit.runLlmContinuityAudit(); })
          Blank()
          if (this.audit.auditRunning) {
            Text('取消').fontSize(13).fontColor(ACCENT).constraintSize({ minWidth: 44, minHeight: 44 })
              .onClick((): void => { this.audit.auditController?.cancel(); })
          }
        }.width('100%')
        if (this.audit.auditMessage.length > 0) {
          Text(this.audit.auditMessage).fontSize(12).fontColor(INK3).lineHeight(18).width('100%')
        }
        if ((this.audit.auditReport?.issues.length ?? 0) > 0) {
          Flex({ wrap: FlexWrap.Wrap }) {
            Text(this.audit.auditRepairBusy ? '正在生成修复建议…' : '全部生成修复建议').fontSize(13).fontColor(ACCENT)
              .constraintSize({ minHeight: 44 }).padding({ right: 20 })
              .enabled(!this.audit.auditRepairBusy && !this.audit.auditRunning && !this.busy && !this.hasBlockingNovelRun() && !this.audit.auditReportStale())
              .opacity(this.audit.auditRepairBusy || this.audit.auditReportStale() ? 0.45 : 1)
              .onClick((): void => { this.audit.repairAuditIssues(this.audit.auditReport?.issues ?? []); })
            if (this.audit.auditRepairBusy) {
              Text('取消修复').fontSize(13).fontColor(ACCENT).constraintSize({ minHeight: 44 })
                .onClick((): void => { this.audit.cancelAuditRepair(); })
            }
          }.width('100%')
        }
        NovelAuditReportView({ report: this.audit.auditReport, chapters: this.project?.chapters ?? [], stale: this.audit.auditReportStale(),
          repairBusy: this.audit.auditRepairBusy || this.audit.auditRunning || this.busy || this.hasBlockingNovelRun(),
          onRepair: (issue: NovelAuditIssue): void => { this.audit.repairAuditIssue(issue); },
          onCanonicalEvidence: (issue: NovelAuditIssue, reference: NovelAuditReference): void => { this.audit.openAuditEvidence(reference, issue.chapterId); },
          onEvidence: (issue: NovelAuditIssue): void => { this.audit.openAuditEvidence(issue); } })
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
}
