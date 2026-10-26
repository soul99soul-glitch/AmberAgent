import type { NovelCreation } from '@amber/deepread-domain';
import type { NovelPolishCreateSheetChapter } from '../../components/NovelPolishCreateSheet.ets';
import { getProductKind } from '../ProductIdentity.ets';
import { NovelProject, NovelChapter, NovelWorkspaceCas, NovelWorkspaceSnapshot, NovelWorkspaceStatus, NovelBranchSettings, DurableGhostwriteJob, GhostwriteStage, DurablePolishJob, PolishContextOptions, PolishStage, NovelGhostwriteStartPreview, NovelGhostwriteReport, NovelChapterContract, confirmedChapterPlanText, chapterContractMarkdown, novelChapterOrdinal } from '@amber/deepread-domain';

export interface NovelJobSessionPorts {
  creation: NovelCreation;
  projectId: () => string;
  currentProject: () => NovelProject | null;
  currentStatus: () => NovelWorkspaceStatus | null;
  requestedPolishJobId: () => string;
  clearRequestedPolishJob: () => void;
  isBusy: () => boolean;
  hasBlockingNovelRun: () => boolean;
  hasOrdinaryRun: () => boolean;
  publishWorkspace: (project: NovelProject, status: NovelWorkspaceStatus) => void;
  reload: () => Promise<void>;
  error: (message: string) => void;
  operationError: (action: string, error: Object) => void;
  closeControl: () => void;
  showJobSettings: () => void;
}

@Observed
export class NovelJobSession {
  polishCreateOpen: boolean = false;
  polishJob: DurablePolishJob | null = null;
  polishBusy: boolean = false;
  ghostwriteJob: DurableGhostwriteJob | null = null;
  ghostwriteBriefOpen: boolean = false;
  ghostwriteBriefKey: string = '';
  ghostwriteBriefCloseRequest: number = 0;
  ghostwriteBriefError: string = '';
  ghostwriteBriefDetail: string = '';
  ghostwriteBriefOpenToken: number = 0;
  ghostwriteBriefCas: NovelWorkspaceCas | null = null;
  ghostwriteBriefJobId: string = '';
  ghostwriteBriefCandidateDigest: string = '';
  ghostwriteBusy: boolean = false;
  ghostwritePreview: NovelGhostwriteStartPreview | null = null;
  ghostwritePreviewError: string = '';
  ghostwriteReport: NovelGhostwriteReport | null = null;
  singlePolishChapterId: string = '';
  polishStartError: string = '';
  ghostwriteModelAvailable: boolean = false;
  ghostwriteModelIssue: string = '正在检查模型配置…';
  ghostwritePollToken: number = 0;
  polishPollToken: number = 0;
  private ports: NovelJobSessionPorts | null = null;
  private pageAlive: boolean = false;
  private studioPageVisible: boolean = false;
  private get deps(): NovelJobSessionPorts { return this.ports as NovelJobSessionPorts; }
  private get projectId(): string { return this.deps.projectId(); }
  private get project(): NovelProject | null { return this.deps.currentProject(); }
  private get workspaceStatus(): NovelWorkspaceStatus | null { return this.deps.currentStatus(); }
  private get requestedPolishJobId(): string { return this.deps.requestedPolishJobId(); }
  private get busy(): boolean { return this.deps.isBusy(); }
  private set errorMsg(value: string) { this.deps.error(value); }
  start(ports: NovelJobSessionPorts): void { this.ports = ports; this.pageAlive = true; this.studioPageVisible = true; }
  show(): void { this.studioPageVisible = true; }
  hide(): void { this.studioPageVisible = false; this.ghostwritePollToken++; this.polishPollToken++; this.ghostwriteBriefOpenToken++; }
  dispose(): void { this.hide(); this.pageAlive = false; }

  ghostwriteStageRuns(stage: GhostwriteStage): boolean {
    return stage !== 'paused' && stage !== 'waiting_user' && stage !== 'failed' &&
      stage !== 'cancelled' && stage !== 'completed';
  }

  scheduleGhostwritePoll(): void {
    const job: DurableGhostwriteJob | null = this.ghostwriteJob;
    if (job === null || !this.ghostwriteStageRuns(job.stage) || !this.pageAlive) return;
    const token: number = ++this.ghostwritePollToken;
    setTimeout((): void => {
      if (!this.pageAlive || token !== this.ghostwritePollToken) return;
      void this.pollGhostwrite(token);
    }, 1200);
  }

  async pollGhostwrite(token: number): Promise<void> {
    try {
      const previous: DurableGhostwriteJob | null = this.ghostwriteJob;
      const next: DurableGhostwriteJob | null = await this.deps.creation.ghostwriteJob(this.projectId);
      if (!this.pageAlive || token !== this.ghostwritePollToken) return;
      this.ghostwriteJob = next;
      if (next === null && previous !== null) {
        const report: NovelGhostwriteReport | null = await this.deps.creation.latestGhostwriteReport(this.projectId);
        if (!this.pageAlive || token !== this.ghostwritePollToken) return;
        this.ghostwriteReport = report;
      }
      if (next !== null && getProductKind() !== 'novel') this.deps.showJobSettings();
      const previousProgress: number = previous?.progress.length ?? 0;
      const nextProgress: number = next?.progress.length ?? 0;
      if (previousProgress !== nextProgress || (previous !== null && next === null)) {
        const project: NovelProject = await this.deps.creation.open(this.projectId);
        const status: NovelWorkspaceStatus = await this.deps.creation.workspaceStatus(this.projectId);
        if (!this.pageAlive || token !== this.ghostwritePollToken) return;
        this.deps.publishWorkspace(project, status);
      }
      this.scheduleGhostwritePoll();
    } catch (error) {
      if (this.pageAlive && token === this.ghostwritePollToken) {
        this.errorMsg = `代笔状态刷新失败：${String(error)}`;
      }
    }
  }

  polishStageRuns(stage: PolishStage): boolean {
    return stage !== 'waiting_system' && stage !== 'paused' && stage !== 'failed' &&
      stage !== 'cancelled' && stage !== 'completed';
  }

  polishRuns(): boolean {
    return this.polishJob !== null && this.polishStageRuns(this.polishJob.stage);
  }

  schedulePolishPoll(): void {
    const job: DurablePolishJob | null = this.polishJob;
    if (job === null || (!this.polishStageRuns(job.stage) && job.stage !== 'waiting_system') ||
      !this.pageAlive || !this.studioPageVisible) return;
    const token: number = ++this.polishPollToken;
    setTimeout((): void => {
      if (!this.pageAlive || !this.studioPageVisible || token !== this.polishPollToken) return;
      void this.pollPolish(token);
    }, 1200);
  }

  async pollPolish(token: number): Promise<void> {
    try {
      const previous: DurablePolishJob | null = this.polishJob;
      const next: DurablePolishJob | null = await this.deps.creation.polishJob(
        this.projectId, this.requestedPolishJobId);
      if (!this.pageAlive || !this.studioPageVisible || token !== this.polishPollToken) return;
      this.polishJob = next;
      const previousProgress: number = previous?.progress.length ?? 0;
      const nextProgress: number = next?.progress.length ?? 0;
      if (previousProgress !== nextProgress || previous?.stage !== next?.stage) {
        const project: NovelProject = await this.deps.creation.open(this.projectId);
        const status: NovelWorkspaceStatus = await this.deps.creation.workspaceStatus(this.projectId);
        if (!this.pageAlive || !this.studioPageVisible || token !== this.polishPollToken) return;
        this.deps.publishWorkspace(project, status);
      }
      this.schedulePolishPoll();
    } catch (error) {
      if (this.pageAlive && this.studioPageVisible && token === this.polishPollToken) {
        this.errorMsg = `润色状态刷新失败：${String(error)}`;
      }
    }
  }

  ghostwriteModelsReady(): boolean {
    return this.ghostwriteModelAvailable;
  }

  ghostwriteModelReason(): string {
    return this.ghostwriteModelIssue.length > 0
      ? this.ghostwriteModelIssue : '写作或审稿模型当前不可用。';
  }

  ghostwritePlanReady(): boolean {
    return (this.project?.branchSettings.thisChapterPlan.trim().length ?? 0) > 0;
  }

  polishLocksWorkspace(): boolean {
    return this.polishJob !== null && this.polishJob.stage !== 'completed' &&
      this.polishJob.stage !== 'cancelled';
  }

  novelJobLocksWorkspace(): boolean {
    return this.ghostwriteJob !== null || this.polishLocksWorkspace();
  }

  ghostwriteRuns(): boolean {
    return this.ghostwriteJob !== null && this.ghostwriteStageRuns(this.ghostwriteJob.stage);
  }

  async startGhostwrite(targetChapterCount: number): Promise<void> {
    if (this.ghostwriteBusy || this.busy || this.deps.hasBlockingNovelRun()) return;
    this.ghostwriteBusy = true;
    this.ghostwritePreviewError = '';
    try {
      const preview: NovelGhostwriteStartPreview = await this.deps.creation.prepareGhostwriteStart(this.projectId, targetChapterCount);
      if (this.pageAlive) {
        this.deps.closeControl();
        this.ghostwritePreview = preview;
      }
    } catch (error) { this.deps.operationError('准备代笔计划失败', error); }
    finally { this.ghostwriteBusy = false; }
  }

  ghostwriteAuthorPlanPreview(preview: NovelGhostwriteStartPreview | null = this.ghostwritePreview): string {
    const content: string = preview?.planContent ?? '';
    const settings: NovelBranchSettings | undefined = this.project?.branchSettings;
    const contract: NovelChapterContract | undefined = settings?.chapterContract;
    if (settings !== undefined && contract !== undefined && contract.status === 'confirmed' && content === confirmedChapterPlanText(settings)) {
      return `本章计划已确认\n\n${chapterContractMarkdown(contract)}`;
    }
    return content;
  }

  async confirmGhostwriteStart(): Promise<void> {
    const preview: NovelGhostwriteStartPreview | null = this.ghostwritePreview;
    if (preview === null || this.ghostwriteBusy) return;
    const targetChapterCount: number = preview.targetChapterCount;
    if (this.busy || this.deps.hasBlockingNovelRun()) return;
    this.ghostwriteBusy = true;
    try {
      this.ghostwriteJob = await this.deps.creation.startGhostwrite(this.projectId, targetChapterCount, preview);
      this.ghostwritePreview = null;
      if (getProductKind() !== 'novel') this.deps.showJobSettings();
      this.errorMsg = '';
      this.scheduleGhostwritePoll();
    } catch (error) {
      this.ghostwritePreviewError = String(error);
    } finally {
      this.ghostwriteBusy = false;
    }
  }

  async openGhostwriteBrief(): Promise<void> {
    const job: DurableGhostwriteJob | null = this.ghostwriteJob;
    if (job === null || this.ghostwriteBusy || (job.stage !== 'paused' && job.stage !== 'failed')) return;
    const token: number = ++this.ghostwriteBriefOpenToken;
    try {
      const snapshot: NovelWorkspaceSnapshot = await this.deps.creation.readWorkspaceSnapshot(this.projectId);
      if (!this.pageAlive || token !== this.ghostwriteBriefOpenToken || this.ghostwriteJob?.jobId !== job.jobId) return;
      if (snapshot.status.activeBranchId !== job.branchId) throw new Error('分支已变化，请重新打开代笔任务');
      this.ghostwriteBriefDetail = `分支“${snapshot.status.activeBranchName}” · 第 ${job.currentChapterOrdinal} 章。保留当前候选作为来源，按本次要求生成下一稿；确认收录前正文保持原样。`;
      this.ghostwriteBriefCas = snapshot.status.cas;
      this.ghostwriteBriefJobId = job.jobId;
      this.ghostwriteBriefCandidateDigest = job.candidate?.digest ?? '';
      this.ghostwriteBriefKey = `${job.jobId}:${Date.now()}`;
      this.ghostwriteBriefError = '';
      this.deps.closeControl();
      this.ghostwriteBriefOpen = true;
    } catch (error) { this.deps.operationError('无法打开修改要求', error); }
  }

  async submitGhostwriteBrief(brief: string): Promise<void> {
    if (this.ghostwriteBusy || this.ghostwriteBriefCas === null) return;
    this.ghostwriteBusy = true; this.ghostwriteBriefError = '';
    try {
      this.ghostwriteJob = await this.deps.creation.reviseGhostwriteWithBrief(this.projectId,
        this.ghostwriteBriefJobId, brief, this.ghostwriteBriefCas, this.ghostwriteBriefCandidateDigest);
      if (!this.pageAlive) return;
      this.ghostwriteBriefOpen = false;
      this.ghostwritePollToken++;
      await this.deps.reload();
      this.scheduleGhostwritePoll();
    } catch (error) { if (this.pageAlive) this.ghostwriteBriefError = String(error); }
    finally { if (this.pageAlive) this.ghostwriteBusy = false; }
  }

  async pauseGhostwrite(): Promise<void> {
    const job: DurableGhostwriteJob | null = this.ghostwriteJob;
    if (this.ghostwriteBusy || job === null || !this.ghostwriteStageRuns(job.stage)) return;
    this.ghostwriteBusy = true;
    try {
      this.ghostwritePollToken++;
      this.ghostwriteJob = await this.deps.creation.pauseGhostwrite(this.projectId, job.jobId);
      this.errorMsg = '';
    } catch (error) {
      this.deps.operationError('暂停代笔失败', error);
    } finally {
      this.ghostwriteBusy = false;
    }
  }

  async resumeGhostwrite(): Promise<void> {
    const job: DurableGhostwriteJob | null = this.ghostwriteJob;
    if (this.ghostwriteBusy || job === null || job.stage !== 'paused') return;
    this.ghostwriteBusy = true;
    try {
      this.ghostwriteJob = await this.deps.creation.resumeGhostwrite(this.projectId, job.jobId);
      if (getProductKind() !== 'novel') this.deps.showJobSettings();
      this.errorMsg = '';
      this.scheduleGhostwritePoll();
    } catch (error) {
      this.deps.operationError('继续代笔失败', error);
    } finally {
      this.ghostwriteBusy = false;
    }
  }

  async retryGhostwrite(): Promise<void> {
    const job: DurableGhostwriteJob | null = this.ghostwriteJob;
    if (this.ghostwriteBusy || job === null || job.stage !== 'failed') return;
    this.ghostwriteBusy = true;
    try {
      this.ghostwriteJob = await this.deps.creation.retryGhostwrite(this.projectId, job.jobId);
      if (getProductKind() !== 'novel') this.deps.showJobSettings();
      this.errorMsg = '';
      this.scheduleGhostwritePoll();
    } catch (error) {
      this.deps.operationError('重试代笔失败', error);
    } finally {
      this.ghostwriteBusy = false;
    }
  }

  async cancelGhostwrite(): Promise<void> {
    const job: DurableGhostwriteJob | null = this.ghostwriteJob;
    if (this.ghostwriteBusy || job === null) return;
    this.ghostwriteBusy = true;
    try {
      this.ghostwritePollToken++;
      await this.deps.creation.cancelGhostwrite(this.projectId, job.jobId);
      this.ghostwriteJob = null;
      this.ghostwriteReport = await this.deps.creation.latestGhostwriteReport(this.projectId);
      this.errorMsg = '';
      await this.deps.reload();
    } catch (error) {
      this.deps.operationError('取消代笔失败', error);
    } finally {
      this.ghostwriteBusy = false;
    }
  }

  async polishChapter(chapterId: string): Promise<void> {
    if (this.busy || this.deps.hasBlockingNovelRun()) return;
    this.singlePolishChapterId = chapterId;
    this.polishStartError = '';
    this.polishCreateOpen = true;
  }

  polishSheetChapters(): NovelPolishCreateSheetChapter[] {
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

  polishConflictReason(): string {
    if (this.ghostwriteJob !== null) return '当前分支已有代笔任务，请先完成或取消。';
    if (this.polishLocksWorkspace()) return '当前分支已有批量润色任务。';
    if (this.deps.hasOrdinaryRun()) return '当前对话仍在生成，请先停止。';
    return '';
  }

  async startPolish(
    startOrdinal: number, endOrdinal: number, contextOptions: PolishContextOptions, ordinals: number[],
  ): Promise<void> {
    if (this.busy || this.polishBusy || this.polishConflictReason().length > 0) return;
    this.polishBusy = true;
    this.polishStartError = '';
    try {
      this.deps.clearRequestedPolishJob();
      this.polishJob = this.singlePolishChapterId.length > 0
        ? await this.deps.creation.startChapterPolish(this.projectId, this.singlePolishChapterId, contextOptions)
        : await this.deps.creation.startSelectedPolish(this.projectId, ordinals, contextOptions);
      this.polishCreateOpen = false;
      this.errorMsg = '';
      this.schedulePolishPoll();
    } catch (error) {
      this.polishStartError = String(error);
    } finally {
      this.polishBusy = false;
    }
  }

  async pausePolish(): Promise<void> {
    const job: DurablePolishJob | null = this.polishJob;
    if (this.polishBusy || job === null || !this.polishStageRuns(job.stage)) return;
    this.polishBusy = true;
    try {
      this.polishPollToken++;
      this.polishJob = await this.deps.creation.pausePolish(this.projectId, job.jobId);
      this.errorMsg = '';
    } catch (error) {
      this.deps.operationError('暂停批量润色失败', error);
    } finally {
      this.polishBusy = false;
    }
  }

  async resumePolish(): Promise<void> {
    const job: DurablePolishJob | null = this.polishJob;
    if (this.polishBusy || job === null ||
      (job.stage !== 'paused' && job.stage !== 'waiting_system')) return;
    this.polishBusy = true;
    try {
      this.polishJob = await this.deps.creation.resumePolish(this.projectId, job.jobId);
      this.errorMsg = '';
      this.schedulePolishPoll();
    } catch (error) {
      this.deps.operationError('继续批量润色失败', error);
    } finally {
      this.polishBusy = false;
    }
  }

  async retryPolish(): Promise<void> {
    const job: DurablePolishJob | null = this.polishJob;
    if (this.polishBusy || job === null || job.stage !== 'failed') return;
    this.polishBusy = true;
    try {
      this.polishJob = await this.deps.creation.retryPolish(this.projectId, job.jobId);
      this.errorMsg = '';
      this.schedulePolishPoll();
    } catch (error) {
      this.deps.operationError('重试批量润色失败', error);
    } finally {
      this.polishBusy = false;
    }
  }

  async cancelPolish(): Promise<void> {
    const job: DurablePolishJob | null = this.polishJob;
    if (this.polishBusy || job === null) return;
    this.polishBusy = true;
    try {
      this.polishPollToken++;
      this.polishJob = await this.deps.creation.cancelPolish(this.projectId, job.jobId);
      this.errorMsg = '';
      await this.deps.reload();
    } catch (error) {
      this.deps.operationError('取消批量润色失败', error);
    } finally {
      this.polishBusy = false;
    }
  }
}
