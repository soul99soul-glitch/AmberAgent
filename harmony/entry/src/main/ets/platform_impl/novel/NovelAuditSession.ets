import { router } from '@kit.ArkUI';
import type { NovelCreation } from '@amber/deepread-domain';
import { NovelProject, NovelChapter, NovelWorkspaceSnapshot, NovelWorkspaceStatus, NovelConsistencyReport, NovelAuditIssue, NovelAuditReference, verifyAuditCanonicalReferences, NovelAuditReport, NovelAuditController, NovelAuditProgress, NovelAuditBlock, defaultGhostwriteDigest } from '@amber/deepread-domain';

export interface NovelAuditSessionPorts {
  creation: NovelCreation;
  projectId: () => string;
  currentProject: () => NovelProject | null;
  currentStatus: () => NovelWorkspaceStatus | null;
  isBusy: () => boolean;
  setBusy: (value: boolean) => void;
  hasBlockingNovelRun: () => boolean;
  error: (message: string) => void;
  operationError: (action: string, error: Object) => void;
}

@Observed
export class NovelAuditSession {
  consistencyMessage: string = '';
  auditRunning: boolean = false;
  auditMessage: string = '';
  auditReport: NovelAuditReport | null = null;
  auditController: NovelAuditController | null = null;
  auditBranchId: string = '';
  auditRepairBusy: boolean = false;
  auditRepairToken: number = 0;
  private ports: NovelAuditSessionPorts | null = null;
  private pageAlive: boolean = false;
  private pageHiddenOnce: boolean = false;
  private get deps(): NovelAuditSessionPorts { return this.ports as NovelAuditSessionPorts; }
  private get projectId(): string { return this.deps.projectId(); }
  private get project(): NovelProject | null { return this.deps.currentProject(); }
  private get workspaceStatus(): NovelWorkspaceStatus | null { return this.deps.currentStatus(); }
  private get busy(): boolean { return this.deps.isBusy(); }
  private set busy(value: boolean) { this.deps.setBusy(value); }
  private set errorMsg(value: string) { this.deps.error(value); }
  start(ports: NovelAuditSessionPorts): void { this.ports = ports; this.pageAlive = true; this.pageHiddenOnce = false; }
  show(): void { this.pageHiddenOnce = false; }
  hide(): void {
    this.pageHiddenOnce = true;
    this.auditRepairToken++;
    if (this.auditRepairBusy) void this.cancelAuditRepair();
    this.auditController?.cancel();
  }
  dispose(): void { this.hide(); this.pageAlive = false; this.auditController = null; }

  async runConsistencyCheck(): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    try {
      const report: NovelConsistencyReport = await this.deps.creation.checkConsistency(this.projectId);
      this.consistencyMessage = report.ok
        ? '当前分支正文、剧情与提案状态一致。'
        : report.issues.map(issue => issue.message).join(' ');
      this.errorMsg = '';
    } catch (error) {
      this.deps.operationError('一致性检查失败', error);
    } finally {
      this.busy = false;
    }
  }

  async runLlmContinuityAudit(): Promise<void> {
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
      const report: NovelAuditReport = await this.deps.creation.runContinuityAudit(this.projectId, control);
      if (!this.pageAlive || this.auditController !== control) return;
      this.auditReport = report;
      this.auditMessage = '';
      this.errorMsg = '';
    } catch (error) {
      if (this.pageAlive) {
        this.auditMessage = '';
        this.deps.operationError('连续性审校失败', error);
      }
    } finally {
      if (this.auditController === control) {
        this.auditRunning = false;
        this.auditController = null;
      }
    }
  }

  auditReportStale(): boolean {
    const report: NovelAuditReport | null = this.auditReport;
    const project: NovelProject | null = this.project;
    if (report === null || project === null) return false;
    if (this.workspaceStatus?.activeBranchId !== this.auditBranchId) return true;
    return report.blocks.some((block: NovelAuditBlock): boolean => {
      const chapter: NovelChapter | undefined = project.chapters.find((item: NovelChapter): boolean => item.id === block.chapterId);
      return chapter === undefined || chapter.discarded || defaultGhostwriteDigest(chapter.content) !== block.sourceDigest;
    });
  }

  async openAuditEvidence(issue: NovelAuditIssue | NovelAuditReference, targetChapterId: string = ''): Promise<void> {
    const chapterId: string = issue.chapterId;
    if (chapterId.length === 0) return;
    const token: number = this.auditRepairToken;
    try {
      const snapshot: NovelWorkspaceSnapshot = await this.deps.creation.readWorkspaceSnapshot(this.projectId);
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
      if (this.pageAlive && !this.pageHiddenOnce && token === this.auditRepairToken) this.deps.operationError('无法查看审校原文', error);
    }
  }

  async repairAuditIssue(issue: NovelAuditIssue): Promise<void> {
    await this.repairAuditIssues([issue]);
  }

  async repairAuditIssues(issues: NovelAuditIssue[]): Promise<void> {
    if (issues.length === 0 || this.auditRunning || this.auditRepairBusy || this.busy || this.deps.hasBlockingNovelRun() || this.auditReportStale()) return;
    this.auditRepairBusy = true;
    const token: number = ++this.auditRepairToken;
    try {
      const proposal = await this.deps.creation.repairContinuityIssues(this.projectId, issues);
      if (!this.pageAlive || this.pageHiddenOnce || token !== this.auditRepairToken) return;
      await router.pushUrl({ url: 'pages/NovelOperationsPage', params: { projectId: this.projectId, proposalId: proposal.proposalId } });
    } catch (error) {
      if (this.pageAlive && !this.pageHiddenOnce && token === this.auditRepairToken) this.deps.operationError('无法生成审校修复建议', error);
    } finally { if (this.pageAlive && token === this.auditRepairToken) this.auditRepairBusy = false; }
  }

  async cancelAuditRepair(): Promise<void> {
    if (!this.auditRepairBusy) return;
    const token: number = ++this.auditRepairToken;
    try { await this.deps.creation.cancelContinuityRepair(this.projectId); }
    catch (error) {
      if (this.pageAlive && !this.pageHiddenOnce && token === this.auditRepairToken) this.deps.operationError('无法取消修复建议', error);
    } finally { if (this.pageAlive && token === this.auditRepairToken) this.auditRepairBusy = false; }
  }
}
