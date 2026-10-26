import type { NovelPolishStartPreview } from './polish_context.ts';
import type { PolishChapterResult } from './polish.ts';
import { novelProjectOperationKind, readNovelProjectOperation } from './specialized_operations.ts';
import type {
  NovelProjectOperationKind, NovelProjectToolInput, NovelProjectToolResult,
} from './specialized_operations.ts';
import {
  withNovelChapterContract, withNovelUpcomingArc, makeNovelChapterContract, collectChapterContractProposal,
  parseChapterContractProposal,
} from './chapter_contract.ts';
import type {
  NovelChapterContractInput, NovelChapterContractStatus, NovelChapterContract,
} from './chapter_contract.ts';
import {
  defaultNovelModelDefaults, resolveNovelDefaultTarget, projectWithNovelStorySeed,
  novelQuickStartRequestText,
} from './standalone_defaults.ts';
import type { NovelModelDefaults, NovelModelRole, NovelStorySeed } from './standalone_defaults.ts';
import { novelId, makeNovelChapterVersion } from './models.ts';
import type { NovelRunKind } from './prompt_catalog.ts';
import type {
  NovelProject, NovelMessage, NovelChapter, NovelMaterial, NovelMaterialSuggestion, NovelChatMode,
  NovelGenerationGranularity, NovelMaterialKind, NovelCollectionTarget, NovelChapterVersion,
  NovelModelPolicy, NovelModelTarget, NovelBranchSettings, NovelMaterialFields,
} from './models.ts';
import {
  createProject, renameProject, setProjectModelPolicy, setProjectBranchSettings, saveChapter,
  saveChapterVersion, deleteChapter, upsertMaterial, deleteMaterial, collect, replacePendingSuggestions,
  resolveSuggestion, resolveSettingProposal as resolveSettingProposalMutation,
} from './mutations.ts';
import { invalidInput, notFound, projectBusy, isNovelError } from './error.ts';
import { buildNovelContext, novelComposerRunKind, MAX_USER_CHARS } from './context_builder.ts';
import { analyzeChapterSuggestions } from './suggestion_engine.ts';
import { runContinuityAudit } from './continuity_audit.ts';
import type { NovelAuditReport, NovelAuditController } from './continuity_audit.ts';
import { parseSettingProposals } from './setting_proposal_parser.ts';
import type { NovelSettingProposal } from './models.ts';
import type { NovelModelRunning, NovelModelRequest } from './model_running.ts';
import type { NovelToolContinuationVerdict } from './model_running.ts';
import type { NovelContextPreviewReceipt } from './model_running.ts';
import type { NovelInjectionOverrides } from './material_injection.ts';
import { NovelStateService } from './state_rebuild.ts';
import type { NovelOrdinaryRunView } from './ordinary_run.ts';
import type { NovelStateOperation } from './state_rebuild.ts';
import {
  emptyNovelStructuredState, pruneNovelStructuredState, projectNovelCharacterExperiences,
  applyNovelIdentityClarification,
} from './structured_state.ts';
import type { NovelIdentityAction, NovelCharacterExperience } from './structured_state.ts';
import { effectiveNovelMaterials, withBranchMaterialEdits } from './material_inheritance.ts';
import { upsertMaterial as upsertStateMaterial } from './mutations.ts';
import type { NovelAuditIssue } from './continuity_audit.ts';
import type {
  NovelProjectRepository, NovelWorkspaceMutationKind, NovelWorkspaceStatus, NovelWorkspaceCas,
  NovelWorkspaceSnapshot,
} from './repository.ts';
import type { StreamTransportState, UIMessage } from '../agent/message.ts';
import type {
  DurableWorkspaceProposal, WorkspaceProposalPatch, WorkspaceCommit, WorkspaceProposalReview,
} from './workspace_history.ts';
import { novelMessageUi } from './transcript.ts';
import { cloneCollectedMessage } from './collected_candidates.ts';
import { CHAPTER_CONTRACT_PROPOSAL_SYSTEM } from './prompt_catalog.ts';
import { decodeNovelWorkspaceUtf8 } from './workspace_exchange.ts';
import type {
  NovelProjectInventory, NovelProjectRecoveryPreview, NovelNativeRestorePreview, NovelNativeBackupSnapshot,
} from './workspace_storage.ts';
import type { NovelNativeBackupImport } from './native_backup.ts';
import type { NovelWorkspaceImportPlan } from './workspace_interop.ts';
import type { NovelWorkspaceArchiveFile } from './workspace_exchange.ts';
import type { DurableGhostwriteJob } from './ghostwrite.ts';
import type { DurablePolishJob, PolishContextOptions } from './polish.ts';
import { materialSuggestionChapterDigest, assertMaterialAdoptionBranch } from './material_adoption.ts';
import type { NovelMaterialAdoption } from './material_adoption.ts';
import { prepareNovelDiscussionArchive, confirmNovelDiscussionArchive } from './discussion_archive.ts';
import type { NovelDiscussionArchive, NovelDiscussionArchiveDraft } from './discussion_archive.ts';
import type { NovelGhostwriteStartPreview } from './ghostwrite_start_preview.ts';
import type { NovelGhostwriteReport } from './job_report.ts';
import { NovelOrdinaryRunService } from './ordinary_run_service.ts';
import { NovelDurableJobCoordinator } from './durable_job_coordinator.ts';
import { NovelContinuityRepairService } from './continuity_repair_service.ts';
import { NovelWorkspaceToolProposals } from './workspace_tool_proposals.ts';

// 公开查询/编辑与运行服务装配。普通生成、持久任务与连续性修复各有唯一 owner。
// ===== 运行事件 / 句柄 =====
export type NovelRunEvent =
  | { kind: 'started' }
  | { kind: 'status'; text: string }
  | {
    kind: 'snapshot'; messages: UIMessage[]; generationActive: boolean;
    textDeltasLive: boolean; transport: StreamTransportState;
  }
  | { kind: 'waiting_user' }
  | { kind: 'completed'; message: NovelMessage }
  | { kind: 'interrupted'; partial: string }
  | { kind: 'failed'; message: string; unsavedMessages?: UIMessage[] };

export interface NovelRun {
  id: string;
  subscribe(cb: (event: NovelRunEvent) => void): () => void;
}

export interface NovelActiveRun {
  runKind?: NovelRunKind | null;
  run: NovelRun;
  historyCount: number;
  userText: string;
  startedAt: number;
}

export interface NovelContextPreviewResult {
  projectId: string;
  branchId: string;
  receipt: NovelContextPreviewReceipt;
}

export interface NovelStructuredStateView {
  project: NovelProject;
  cas: NovelWorkspaceCas;
  operation: NovelStateOperation | null;
  active: boolean;
  experiences: NovelCharacterExperience[];
}

export interface NovelCollectionResult {
  chapter: NovelChapter;
  suggestionCount: number;
  suggestionWarning: string | null;
  // 仅本次调用存活，不写入项目文件。失败 warning 由分析路径保存到章节。
  analysisFinished: Promise<NovelSuggestionRefreshResult>;
}

export interface NovelSuggestionRefreshResult {
  suggestions: NovelMaterialSuggestion[];
  count: number;
  warning: string | null;
}

export interface NovelConsistencyIssue {
  kind: 'plot_stale' | 'unresolved' | 'pending_proposal';
  message: string;
}

export interface NovelConsistencyReport {
  ok: boolean;
  activeBranchId: string;
  issues: NovelConsistencyIssue[];
}

export interface NovelWorkspaceListResult {
  root: string;
  entries: string[];
}

export interface NovelWorkspaceReadResult {
  path: string;
  content: string;
}

export interface NovelWorkspaceGrepMatch {
  path: string;
  line: number;
  text: string;
}

export interface NovelWorkspaceGrepResult {
  query: string;
  matches: NovelWorkspaceGrepMatch[];
}

// ===== 依赖 =====
export interface NovelCreationDeps {
  repository: NovelProjectRepository;
  modelRunning: NovelModelRunning;
  loadModelDefaults?: () => Promise<NovelModelDefaults>;
  loadPolishPreference?: () => Promise<string>;
  polishEffects?: NovelPolishEffects;
  nowMs?: () => number;
  idleTimeoutMs?: number;
}

export interface NovelPolishEffects {
  schedule(job: DurablePolishJob): Promise<void>;
  cancel(job: DurablePolishJob): Promise<void>;
  notify(job: DurablePolishJob): Promise<void>;
}

export class NovelCreation {
  private repository: NovelProjectRepository;
  private modelRunning: NovelModelRunning;
  private loadModelDefaults: () => Promise<NovelModelDefaults>;
  private nowMs: () => number;
  private idleTimeoutMs: number;
  private stateService: NovelStateService;
  private workspaceTools: NovelWorkspaceToolProposals;
  private ordinary: NovelOrdinaryRunService;
  private jobs: NovelDurableJobCoordinator;
  private repairs: NovelContinuityRepairService;

  constructor(deps: NovelCreationDeps) {
    this.repository = deps.repository;
    this.modelRunning = deps.modelRunning;
    this.loadModelDefaults = deps.loadModelDefaults ?? (async (): Promise<NovelModelDefaults> => defaultNovelModelDefaults());
    this.nowMs = deps.nowMs ?? ((): number => Date.now());
    this.idleTimeoutMs = deps.idleTimeoutMs ?? 90_000;
    this.stateService = new NovelStateService(this.repository, this.modelRunning,
      async (project: NovelProject): Promise<NovelModelTarget> => resolveNovelDefaultTarget(
        project.modelPolicy, await this.loadModelDefaults(), 'stateSync'));
    this.workspaceTools = new NovelWorkspaceToolProposals(this.repository, this.nowMs);
    this.jobs = new NovelDurableJobCoordinator({ repository: this.repository, modelRunning: this.modelRunning,
      loadModelDefaults: this.loadModelDefaults, loadPolishPreference: deps.loadPolishPreference,
      polishEffects: deps.polishEffects, nowMs: this.nowMs });
    this.repairs = new NovelContinuityRepairService({ repository: this.repository, modelRunning: this.modelRunning,
      loadModelDefaults: this.loadModelDefaults, workspaceTools: this.workspaceTools,
      projectRunning: (projectId: string): boolean => this.hasActiveProjectDriver(projectId) });
    this.ordinary = new NovelOrdinaryRunService({ repository: this.repository, modelRunning: this.modelRunning,
      loadModelDefaults: this.loadModelDefaults, workspaceTools: this.workspaceTools, nowMs: this.nowMs,
      idleTimeoutMs: this.idleTimeoutMs,
      otherInteractiveRunning: (projectId: string): boolean => this.stateService.isRunning(projectId) || this.repairs.isRunning(projectId),
      otherDriverRunning: (projectId: string): boolean => this.stateService.isRunning(projectId) || this.repairs.isRunning(projectId) || this.jobs.isRunning(projectId) });
  }

  listProjects(): Promise<NovelProject[]> {
    return this.repository.listProjects();
  }

  listProjectInventory(): Promise<NovelProjectInventory> { return this.repository.listProjectInventory(); }

  inspectProjectRecovery(projectId: string): Promise<NovelProjectRecoveryPreview | null> {
    return this.repository.inspectProjectRecovery(projectId);
  }

  restoreProjectRecovery(preview: NovelProjectRecoveryPreview): Promise<NovelProject> {
    if (this.hasActiveProjectDriver(preview.projectId)) return Promise.reject(projectBusy(preview.projectId));
    return this.repository.restoreProjectRecovery(preview);
  }

  nativeBackupSnapshot(projectId: string): Promise<NovelNativeBackupSnapshot> {
    if (this.ordinary.isRunning(projectId)) return Promise.reject(projectBusy(projectId));
    return this.repository.nativeBackupSnapshot(projectId);
  }

  inspectNativeRestore(input: NovelNativeBackupImport): Promise<NovelNativeRestorePreview> {
    return this.repository.inspectNativeRestore(input);
  }

  installNativeBackup(input: NovelNativeBackupImport, preview: NovelNativeRestorePreview): Promise<NovelProject> {
    if (this.hasActiveProjectDriver(input.projectId)) return Promise.reject(projectBusy(input.projectId));
    return this.repository.installNativeBackup(input, preview);
  }

  installWorkspacePlan(plan: NovelWorkspaceImportPlan, preview?: NovelNativeRestorePreview): Promise<NovelProject> {
    const projectId: string = plan.manifest.projectId;
    if (this.hasActiveProjectDriver(projectId)) return Promise.reject(projectBusy(projectId));
    return this.repository.installWorkspacePlan(plan, preview);
  }

  private hasActiveProjectDriver(projectId: string): boolean {
    return this.ordinary.isRunning(projectId) || this.stateService.isRunning(projectId) ||
      this.repairs.isRunning(projectId) || this.jobs.isRunning(projectId);
  }

  publicExportPlan(projectId: string): Promise<NovelWorkspaceImportPlan> {
    return this.repository.publicExportPlan(projectId);
  }

  async open(projectId: string): Promise<NovelProject> {
    const project: NovelProject = await this.repository.loadProject(projectId);
    const repaired: boolean = await this.workspaceTools.reconcileDeniedWorkspaceTools(projectId, project);
    const opened: NovelProject = repaired ? await this.repository.loadProject(projectId) : project;
    this.jobs.recover(projectId);
    return opened;
  }

  readWorkspaceSnapshot(projectId: string): Promise<NovelWorkspaceSnapshot> {
    return this.repository.readWorkspaceSnapshot(projectId);
  }

  workspaceStatus(projectId: string): Promise<NovelWorkspaceStatus> {
    return this.repository.workspaceStatus(projectId);
  }

  async ghostwriteJob(projectId: string): Promise<DurableGhostwriteJob | null> {
    return this.jobs.ghostwriteJob(projectId);
  }

  async prepareGhostwriteStart(projectId: string, targetChapterCount: number): Promise<NovelGhostwriteStartPreview> {
    return this.jobs.prepareGhostwriteStart(projectId, targetChapterCount);
  }

  async latestGhostwriteReport(projectId: string): Promise<NovelGhostwriteReport | null> {
    return this.jobs.latestGhostwriteReport(projectId);
  }

  async startGhostwrite(
    projectId: string, targetChapterCount: number, preview?: NovelGhostwriteStartPreview,
  ): Promise<DurableGhostwriteJob> {
    return this.jobs.startGhostwrite(projectId, targetChapterCount, preview);
  }

  async pauseGhostwrite(projectId: string, jobId: string): Promise<DurableGhostwriteJob> {
    return this.jobs.pauseGhostwrite(projectId, jobId);
  }

  async resumeGhostwrite(projectId: string, jobId: string): Promise<DurableGhostwriteJob> {
    return this.jobs.resumeGhostwrite(projectId, jobId);
  }

  async reviseGhostwriteWithBrief(
    projectId: string, jobId: string, brief: string, expectedCas?: NovelWorkspaceCas,
    expectedCandidateDigest?: string,
  ): Promise<DurableGhostwriteJob> {
    return this.jobs.reviseGhostwriteWithBrief(projectId, jobId, brief, expectedCas, expectedCandidateDigest);
  }

  async retryGhostwrite(projectId: string, jobId: string): Promise<DurableGhostwriteJob> {
    return this.jobs.retryGhostwrite(projectId, jobId);
  }

  async cancelGhostwrite(projectId: string, jobId: string): Promise<DurableGhostwriteJob> {
    return this.jobs.cancelGhostwrite(projectId, jobId);
  }

  async polishJob(projectId: string, requestedJobId: string = ''): Promise<DurablePolishJob | null> {
    return this.jobs.polishJob(projectId, requestedJobId);
  }

  async previewPolishSelected(
    projectId: string, chapterIds: string[], contextOptions: PolishContextOptions,
  ): Promise<NovelPolishStartPreview> {
    return this.jobs.previewPolishSelected(projectId, chapterIds, contextOptions);
  }

  async startPolishSelected(
    projectId: string, chapterIds: string[], contextOptions: PolishContextOptions, expectedCas?: NovelWorkspaceCas,
  ): Promise<DurablePolishJob> {
    return this.jobs.startPolishSelected(projectId, chapterIds, contextOptions, expectedCas);
  }

  async polishOutcomes(projectId: string, jobId: string): Promise<PolishChapterResult[]> {
    return this.jobs.polishOutcomes(projectId, jobId);
  }

  async startPolish(
    projectId: string, fromOrdinal: number, toOrdinal: number, contextOptions: PolishContextOptions,
  ): Promise<DurablePolishJob> {
    return this.jobs.startPolish(projectId, fromOrdinal, toOrdinal, contextOptions);
  }

  async startSelectedPolish(
    projectId: string, ordinals: number[], contextOptions: PolishContextOptions,
  ): Promise<DurablePolishJob> {
    return this.jobs.startSelectedPolish(projectId, ordinals, contextOptions);
  }

  async startChapterPolish(
    projectId: string, chapterId: string, contextOptions: PolishContextOptions,
  ): Promise<DurablePolishJob> {
    return this.jobs.startChapterPolish(projectId, chapterId, contextOptions);
  }

  async pausePolish(projectId: string, jobId: string): Promise<DurablePolishJob> {
    return this.jobs.pausePolish(projectId, jobId);
  }

  async resumePolish(projectId: string, jobId: string): Promise<DurablePolishJob> {
    return this.jobs.resumePolish(projectId, jobId);
  }

  async retryPolish(projectId: string, jobId: string, chapterIds?: string[]): Promise<DurablePolishJob> {
    return this.jobs.retryPolish(projectId, jobId, chapterIds);
  }

  async cancelPolish(projectId: string, jobId: string): Promise<DurablePolishJob> {
    return this.jobs.cancelPolish(projectId, jobId);
  }

  async drivePolishInBackground(
    projectId: string, branchId: string, jobId: string, maxChapters: number = 1,
  ): Promise<DurablePolishJob> {
    return this.jobs.drivePolishInBackground(projectId, branchId, jobId, maxChapters);
  }

  async yieldPolishForSystem(projectId: string, jobId: string): Promise<DurablePolishJob> {
    return this.jobs.yieldPolishForSystem(projectId, jobId);
  }

  async createBranch(projectId: string, name: string): Promise<NovelProject> {
    this.assertProjectIdle(projectId);
    const status: NovelWorkspaceStatus = await this.repository.workspaceStatus(projectId);
    return await this.repository.createBranch(projectId, name, status.cas, novelId());
  }

  async switchBranch(projectId: string, branchId: string): Promise<NovelProject> {
    this.assertProjectIdle(projectId);
    const status: NovelWorkspaceStatus = await this.repository.workspaceStatus(projectId);
    return await this.repository.switchBranch(projectId, branchId, status.cas);
  }

  async undoLast(projectId: string): Promise<NovelProject> {
    this.assertProjectIdle(projectId);
    const status: NovelWorkspaceStatus = await this.repository.workspaceStatus(projectId);
    return await this.repository.undo(projectId, status.cas, novelId());
  }

  workspaceHistory(projectId: string): Promise<WorkspaceCommit[]> {
    return this.repository.workspaceHistory(projectId);
  }

  async chapterVersionCheckpoint(
    projectId: string, versionId: string, expectedCas?: NovelWorkspaceCas,
  ): Promise<WorkspaceCommit | null> {
    const status: NovelWorkspaceStatus = await this.repository.workspaceStatus(projectId);
    return this.repository.chapterVersionCheckpoint(projectId, versionId, expectedCas ?? status.cas);
  }

  async forkFromHistory(
    projectId: string, head: string, name: string, expectedCas?: NovelWorkspaceCas,
  ): Promise<NovelProject> {
    this.assertProjectIdle(projectId);
    const status: NovelWorkspaceStatus = await this.repository.workspaceStatus(projectId);
    return this.repository.forkFromHistory(projectId, head, name, expectedCas ?? status.cas, novelId());
  }

  async undoToCheckpoint(projectId: string, head: string): Promise<NovelProject> {
    this.assertProjectIdle(projectId);
    const status: NovelWorkspaceStatus = await this.repository.workspaceStatus(projectId);
    return this.repository.undoToCheckpoint(projectId, head, status.cas, novelId());
  }

  async renameBranch(projectId: string, branchId: string, name: string): Promise<NovelProject> {
    this.assertProjectIdle(projectId);
    const status: NovelWorkspaceStatus = await this.repository.workspaceStatus(projectId);
    return this.repository.renameBranch(projectId, branchId, name, status.cas, novelId());
  }

  async setMainBranch(projectId: string, branchId: string): Promise<NovelProject> {
    this.assertProjectIdle(projectId);
    const status: NovelWorkspaceStatus = await this.repository.workspaceStatus(projectId);
    return this.repository.setMainBranch(projectId, branchId, status.cas, novelId());
  }

  async deleteBranch(projectId: string, branchId: string): Promise<NovelProject> {
    this.assertProjectIdle(projectId);
    const status: NovelWorkspaceStatus = await this.repository.workspaceStatus(projectId);
    return this.repository.deleteBranch(projectId, branchId, status.cas, novelId());
  }

  private assertProjectIdle(projectId: string): void {
    if (this.ordinary.isRunning(projectId) || this.stateService.isRunning(projectId) || this.repairs.isRunning(projectId)) throw projectBusy(projectId);
  }

  async syncPlot(projectId: string): Promise<NovelProject> {
    const status: NovelWorkspaceStatus = await this.repository.workspaceStatus(projectId);
    return await this.repository.syncPlot(projectId, status.cas, novelId());
  }

  async resolveUnresolved(projectId: string): Promise<NovelProject> {
    const status: NovelWorkspaceStatus = await this.repository.workspaceStatus(projectId);
    return await this.repository.resolveUnresolved(projectId, status.cas, novelId());
  }

  workspaceProposals(projectId: string): Promise<DurableWorkspaceProposal[]> {
    return this.repository.workspaceProposals(projectId);
  }

  async createWorkspaceProposal(
    projectId: string, patches: WorkspaceProposalPatch[], proposalId: string = novelId(),
    review?: WorkspaceProposalReview, expectedCas?: NovelWorkspaceCas,
  ): Promise<DurableWorkspaceProposal> {
    return this.workspaceTools.createWorkspaceProposal(projectId, patches, proposalId, review, expectedCas);
  }

  async proposeProjectOperation(
    projectId: string, kind: NovelProjectOperationKind, args: NovelProjectToolInput, expectedCas?: NovelWorkspaceCas,
  ): Promise<DurableWorkspaceProposal> {
    const status: NovelWorkspaceStatus = await this.repository.workspaceStatus(projectId);
    return this.repository.proposeProjectOperation(projectId, kind, args, expectedCas ?? status.cas, novelId(), this.nowMs());
  }

  async executeProjectTool(projectId: string, name: string, args: NovelProjectToolInput): Promise<NovelProjectToolResult> {
    const kind: NovelProjectOperationKind = novelProjectOperationKind(name);
    const project: NovelProject = await this.repository.loadProject(projectId);
    const read: NovelProjectToolResult | null = readNovelProjectOperation(project, kind, args);
    if (read !== null) return read;
    const proposal: DurableWorkspaceProposal = await this.proposeProjectOperation(projectId, kind, args);
    return { status: proposal.status, proposal_id: proposal.proposalId,
      requires_author_approval: true, summary: proposal.review?.summary ?? kind };
  }

  resolveWorkspaceProposal(
    projectId: string, proposalId: string, accept: boolean, commandId: string = novelId(),
  ): Promise<DurableWorkspaceProposal> {
    return this.workspaceTools.resolveWorkspaceProposal(projectId, proposalId, accept, commandId);
  }

  async checkConsistency(projectId: string): Promise<NovelConsistencyReport> {
    const status: NovelWorkspaceStatus = await this.repository.workspaceStatus(projectId);
    const issues: NovelConsistencyIssue[] = [];
    if (status.plotStale) {
      issues.push({ kind: 'plot_stale', message: '剧情摘要落后于当前正文，请先同步剧情。' });
    }
    if (status.unresolvedFromOrdinal !== null) {
      issues.push({
        kind: 'unresolved',
        message: `第 ${status.unresolvedFromOrdinal} 章起的后续影响尚未确认。`,
      });
    }
    if (status.pendingProposalCount > 0) {
      issues.push({
        kind: 'pending_proposal',
        message: `仍有 ${status.pendingProposalCount} 个待确认提案。`,
      });
    }
    return { ok: issues.length === 0, activeBranchId: status.activeBranchId, issues };
  }

  async runContinuityAudit(projectId: string, control?: NovelAuditController): Promise<NovelAuditReport> {
    const project: NovelProject = await this.repository.loadProject(projectId);
    return runContinuityAudit(
      this.modelRunning, project, await this.resolvedModelTarget(project, 'stateSync'), this.idleTimeoutMs * 2, control);
  }

  async ingestSettingProposals(
    projectId: string, sourceMessageId: string, text: string,
  ): Promise<NovelSettingProposal[]> {
    const now: number = this.nowMs();
    const parsed: NovelSettingProposal[] =
      parseSettingProposals(text, sourceMessageId, now);
    if (parsed.length === 0) return [];
    let saved: NovelSettingProposal[] = [];
    await this.commitMutation(
      projectId, 'proposal_create',
      (project: NovelProject): NovelProject => {
        const kept: NovelSettingProposal[] = project.settingProposals.filter(
          (p: NovelSettingProposal): boolean =>
            !(p.status === 'pending' && p.sourceMessageId === sourceMessageId));
        saved = parsed;
        return {
          ...project,
          settingProposals: [...kept, ...parsed],
          updatedAt: now,
        };
      });
    return saved;
  }

  async workspaceList(projectId: string, path: string = ''): Promise<NovelWorkspaceListResult> {
    const status: NovelWorkspaceStatus = await this.repository.workspaceStatus(projectId);
    const root: string = this.workspaceTools.workspaceToolPath(path, status.activeBranchId, true);
    const files: NovelWorkspaceArchiveFile[] = await this.repository.workspaceFiles(projectId);
    const entries: string[] = files.map((file: NovelWorkspaceArchiveFile): string => file.path)
      .filter((candidate: string): boolean => candidate === root ||
        candidate.startsWith(root.endsWith('/') ? root : `${root}/`))
      .sort();
    return { root, entries };
  }

  async workspaceRead(projectId: string, path: string): Promise<NovelWorkspaceReadResult> {
    const status: NovelWorkspaceStatus = await this.repository.workspaceStatus(projectId);
    const checked: string = this.workspaceTools.workspaceToolPath(path, status.activeBranchId, false);
    const files: NovelWorkspaceArchiveFile[] = await this.repository.workspaceFiles(projectId);
    const file: NovelWorkspaceArchiveFile | undefined = files.find(
      (candidate: NovelWorkspaceArchiveFile): boolean => candidate.path === checked);
    if (file === undefined) throw notFound('workspace_file', checked);
    return { path: checked, content: decodeNovelWorkspaceUtf8(file.bytes) };
  }

  async workspaceGrep(
    projectId: string, query: string, path: string = '', maxResults: number = 50,
  ): Promise<NovelWorkspaceGrepResult> {
    const needle: string = query.trim();
    if (needle.length === 0) throw invalidInput('搜索内容不能为空');
    const status: NovelWorkspaceStatus = await this.repository.workspaceStatus(projectId);
    const root: string = this.workspaceTools.workspaceToolPath(path, status.activeBranchId, true);
    const limit: number = Math.max(1, Math.min(200, Math.floor(maxResults)));
    const files: NovelWorkspaceArchiveFile[] = await this.repository.workspaceFiles(projectId);
    const matches: NovelWorkspaceGrepMatch[] = [];
    for (let i: number = 0; i < files.length && matches.length < limit; i++) {
      const file: NovelWorkspaceArchiveFile = files[i];
      if (file.path !== root && !file.path.startsWith(root.endsWith('/') ? root : `${root}/`)) continue;
      const lines: string[] = decodeNovelWorkspaceUtf8(file.bytes).split('\n');
      for (let line: number = 0; line < lines.length && matches.length < limit; line++) {
        if (lines[line].indexOf(needle) >= 0) {
          matches.push({ path: file.path, line: line + 1, text: lines[line] });
        }
      }
    }
    return { query: needle, matches };
  }

  async workspaceWriteResult(
    projectId: string, proposalId: string, patches: WorkspaceProposalPatch[],
  ): Promise<DurableWorkspaceProposal> {
    const proposal: DurableWorkspaceProposal = await this.workspaceTools.stageWorkspaceWriteProposal(projectId, proposalId, patches);
    return await this.resolveWorkspaceProposal(projectId, proposal.proposalId, true,
      `novel:${projectId}:proposal:${proposalId}:approve`);
  }

  async create(name: string, seed?: NovelStorySeed): Promise<NovelProject> {
    const now: number = this.nowMs();
    const empty: NovelProject = { ...createProject(name, now), creationMode: 'blank', quickStartSeed: null, polishPreference: '' };
    const project: NovelProject = seed === undefined ? empty : projectWithNovelStorySeed(empty, seed, now);
    return await this.repository.createProject(project);
  }

  async rename(projectId: string, name: string): Promise<NovelProject> {
    const ghostwriteJobs = await this.repository.listGhostwriteJobs(projectId);
    const polishJobs = await this.repository.listPolishJobs(projectId);
    if ([...ghostwriteJobs, ...polishJobs].some(job => job.stage !== 'completed' && job.stage !== 'cancelled')) {
      throw invalidInput('请先完成或取消当前代写/润色任务，再重命名项目');
    }
    return this.commitMutation(projectId, 'rename', p => renameProject(p, name, this.nowMs()));
  }

  setModelPolicy(projectId: string, modelPolicy: NovelModelPolicy): Promise<NovelProject> {
    return this.commitMutation(
      projectId, 'model_change', p => setProjectModelPolicy(p, modelPolicy, this.nowMs()));
  }

  setBranchSettings(projectId: string, branchSettings: NovelBranchSettings): Promise<NovelProject> {
    return this.commitMutation(
      projectId, 'branch_settings_change',
      p => setProjectBranchSettings(p, branchSettings, this.nowMs()));
  }

  setProjectPolishPreference(
    projectId: string, preference: string, expectedCas?: NovelWorkspaceCas,
  ): Promise<NovelProject> {
    this.assertProjectIdle(projectId);
    return this.commitMutation(projectId, 'project_setup_change',
      project => ({ ...project, polishPreference: preference.trim(), updatedAt: this.nowMs() }),
      novelId(), expectedCas);
  }

  async validateWritingModel(projectId: string): Promise<void> {
    const project: NovelProject = await this.repository.loadProject(projectId);
    await this.modelRunning.validate(await this.resolvedModelTarget(project, 'writing'), projectId);
  }

  async stopActiveRun(projectId: string): Promise<void> {
    return this.ordinary.stopActiveRun(projectId);
  }

  async delete(projectId: string): Promise<void> {
    await this.stateService.cancelAndWait(projectId);
    await this.cancelContinuityRepair(projectId);
    // Wait for the interrupted run's final checkpoint before deleting its project.
    await this.stopActiveRun(projectId);
    await this.jobs.cancelAndDrain(projectId);
    await this.repository.deleteProject(projectId);
  }

  async saveChapter(
    projectId: string, chapterId: string | null, title: string, content: string,
    expectedCas?: NovelWorkspaceCas,
  ): Promise<NovelChapter> {
    // holder 对象:闭包内赋值会使 TS 对裸 let 的控制流收窄失效(tsc TS2352)
    const box: { value: NovelChapter | null } = { value: null };
    await this.commitMutation(projectId, 'manual_edit', p => {
      const previous: NovelChapter | undefined = p.chapters.find(chapter => chapter.id === chapterId);
      const archived: NovelProject = previous !== undefined &&
        (previous.title !== title || previous.content !== content)
        ? saveChapterVersion(p, previous.id, 'manual', this.nowMs()).project : p;
      const m = saveChapter(archived, chapterId, title, content, this.nowMs());
      box.value = m.value;
      return m.project;
    }, novelId(), expectedCas);
    if (box.value === null) throw new Error('saveChapter: 更新未返回章节');
    return box.value;
  }

  deleteChapter(projectId: string, chapterId: string): Promise<NovelProject> {
    return this.commitMutation(projectId, 'chapter_delete',
      p => deleteChapter(p, chapterId, this.nowMs()));
  }

  async saveSharedMaterial(projectId: string, material: NovelMaterial, expectedCas?: NovelWorkspaceCas): Promise<NovelProject> {
    this.assertProjectIdle(projectId);
    const status: NovelWorkspaceStatus = await this.repository.workspaceStatus(projectId);
    return this.repository.saveSharedMaterial(projectId, material, expectedCas ?? status.cas, novelId());
  }

  async deleteSharedMaterial(projectId: string, materialId: string, expectedCas?: NovelWorkspaceCas): Promise<NovelProject> {
    this.assertProjectIdle(projectId);
    const status: NovelWorkspaceStatus = await this.repository.workspaceStatus(projectId);
    return this.repository.deleteSharedMaterial(projectId, materialId, expectedCas ?? status.cas, novelId());
  }

  async restoreMaterialInheritance(projectId: string, materialId: string, expectedCas?: NovelWorkspaceCas): Promise<NovelProject> {
    this.assertProjectIdle(projectId);
    const status: NovelWorkspaceStatus = await this.repository.workspaceStatus(projectId);
    return this.repository.restoreMaterialInheritance(projectId, materialId, expectedCas ?? status.cas, novelId());
  }

  async upsertMaterial(
    projectId: string, materialId: string | null, kind: NovelMaterialKind,
    title: string, content: string, enabled: boolean, fields?: NovelMaterialFields, expectedCas?: NovelWorkspaceCas,
  ): Promise<NovelMaterial> {
    const box: { value: NovelMaterial | null } = { value: null };
    await this.commitMutation(projectId, 'material_edit', p => {
      const m = upsertMaterial(p, materialId, kind, title, content, enabled, this.nowMs(), fields);
      box.value = m.value;
      return m.project;
    }, novelId(), expectedCas);
    if (box.value === null) throw new Error('upsertMaterial: 更新未返回素材');
    return box.value;
  }

  deleteMaterial(projectId: string, materialId: string, expectedCas?: NovelWorkspaceCas): Promise<NovelProject> {
    return this.commitMutation(projectId, 'material_delete',
      p => deleteMaterial(p, materialId, this.nowMs()), novelId(), expectedCas);
  }

  async cloneCollectedMessage(
    projectId: string, messageId: string, expectedCas?: NovelWorkspaceCas,
  ): Promise<NovelMessage> {
    this.assertProjectIdle(projectId);
    let cloned: NovelMessage | null = null;
    await this.commitMutation(projectId, 'transcript_checkpoint', (project, branchId) => {
      const result = cloneCollectedMessage(project, messageId, branchId, this.nowMs());
      cloned = result.message;
      return result.project;
    }, novelId(), expectedCas);
    if (cloned === null) throw new Error('cloneCollectedMessage: 更新未返回候选');
    return cloned;
  }

  async collectMessage(
    projectId: string, messageId: string, target: NovelCollectionTarget,
    editedContent: string | null = null,
  ): Promise<NovelCollectionResult> {
    const box: { value: NovelChapter | null } = { value: null };
    await this.commitMutation(projectId, 'collect', (p, branchId) => {
      const m = collect(p, messageId, target, this.nowMs(), editedContent, branchId);
      box.value = m.value;
      return m.project;
    });
    if (box.value === null) throw new Error('collectMessage: 更新未返回章节');
    const ch: NovelChapter = box.value;
    // 分析失败会保存到章节 suggestionWarning，正文收录仍已完成。
    const analysisFinished: Promise<NovelSuggestionRefreshResult> = this.refreshMaterialSuggestions(projectId, ch.id);
    // 即使调用方不观察后台结果，也不会产生未处理的 Promise rejection。
    void analysisFinished.catch((): void => {});
    return { chapter: ch, suggestionCount: 0, suggestionWarning: null, analysisFinished };
  }

  async refreshMaterialSuggestions(
    projectId: string, chapterId: string,
  ): Promise<NovelSuggestionRefreshResult> {
    const project: NovelProject = await this.repository.loadProject(projectId);
    const status: NovelWorkspaceStatus = await this.repository.workspaceStatus(projectId);
    const chapter: NovelChapter | undefined = project.chapters.find(c => c.id === chapterId);
    if (chapter === undefined) throw notFound('chapter', chapterId);
    const now: number = this.nowMs();
    const sourceDigest: string = materialSuggestionChapterDigest(chapter);
    try {
      const suggestions = await analyzeChapterSuggestions(this.modelRunning, project, chapter,
        await this.resolvedModelTarget(project, 'stateSync'), now);
      await this.commitMutation(projectId, 'suggestion_refresh', (current, branchId) => {
        const source = current.chapters.find(item => item.id === chapterId);
        if (branchId !== status.activeBranchId || source === undefined ||
          materialSuggestionChapterDigest(source) !== sourceDigest) {
          throw invalidInput('分析期间来源章节或分支已变更，旧建议未写入，请重新分析');
        }
        const next = replacePendingSuggestions(current, chapterId, suggestions, now);
        return { ...next, chapters: next.chapters.map(item => item.id === chapterId
          ? { ...item, suggestionWarning: null } : item) };
      });
      return { suggestions, count: suggestions.length,
        warning: suggestions.length === 0 ? '本章暂无新的资料建议' : null };
    } catch (error) {
      const warning: string = isNovelError(error) ? error.message : String(error);
      await this.commitMutation(projectId, 'suggestion_refresh', (current, branchId) => {
        if (branchId !== status.activeBranchId) return current;
        return { ...current, chapters: current.chapters.map(item => item.id === chapterId &&
          materialSuggestionChapterDigest(item) === sourceDigest ? { ...item, suggestionWarning: warning } : item) };
      });
      throw error;
    }
  }

  async resolveMaterialSuggestion(
    projectId: string, suggestionId: string, accept: boolean,
    edit?: NovelMaterialAdoption,
  ): Promise<NovelMaterial | null> {
    let material: NovelMaterial | null = null;
    await this.commitMutation(projectId, 'proposal_resolve', (p, branchId) => {
      if (accept && edit !== undefined) assertMaterialAdoptionBranch(edit, branchId);
      const m = resolveSuggestion(p, suggestionId, accept, this.nowMs(), edit);
      material = m.value;
      return m.project;
    });
    return material;
  }

  async resolveSettingProposal(
    projectId: string, proposalId: string, accept: boolean,
    edit?: NovelMaterialAdoption,
  ): Promise<NovelMaterial | null> {
    let material: NovelMaterial | null = null;
    await this.commitMutation(projectId, 'proposal_resolve', (project: NovelProject, branchId: string): NovelProject => {
      if (accept && edit !== undefined) assertMaterialAdoptionBranch(edit, branchId);
      const mutation = resolveSettingProposalMutation(project, proposalId, accept, this.nowMs(), edit);
      material = mutation.value;
      return mutation.project;
    });
    return material;
  }

  async readStructuredState(projectId: string): Promise<NovelStructuredStateView> {
    const snapshot = await this.repository.readWorkspaceSnapshot(projectId);
    const project = snapshot.project;
    const materials = effectiveNovelMaterials(project);
    const state = pruneNovelStructuredState(project.structuredState ?? emptyNovelStructuredState(), project.chapters, materials);
    return { project: { ...project, structuredState: state }, cas: snapshot.status.cas,
      operation: project.stateOperation ?? null, active: this.stateService.isRunning(projectId),
      experiences: projectNovelCharacterExperiences(state, project.chapters, materials) };
  }

  observeStateOperation(projectId: string, listener: (operation: NovelStateOperation | null) => void): () => void {
    return this.stateService.observe(projectId, listener);
  }

  startStateRebuild(projectId: string): Promise<NovelStateOperation> {
    if (this.hasActiveProjectDriver(projectId)) return Promise.reject(projectBusy(projectId));
    return this.stateService.start(projectId);
  }

  syncStructuredChapter(projectId: string, chapterId: string): Promise<NovelStateOperation> {
    if (this.hasActiveProjectDriver(projectId)) return Promise.reject(projectBusy(projectId));
    return this.stateService.start(projectId, chapterId);
  }

  retryStateRebuild(projectId: string): Promise<NovelStateOperation> {
    if (this.hasActiveProjectDriver(projectId)) return Promise.reject(projectBusy(projectId));
    return this.stateService.retry(projectId);
  }

  async cancelStateRebuild(projectId: string): Promise<void> { await this.stateService.cancelAndWait(projectId); }

  setStateReasoningEnabled(projectId: string, enabled: boolean, expectedCas?: NovelWorkspaceCas): Promise<NovelProject> {
    return this.commitMutation(projectId, 'state_operation', project => ({ ...project,
      stateSyncReasoningEnabled: enabled, updatedAt: this.nowMs() }), novelId(), expectedCas);
  }

  resolveIdentityClarification(
    projectId: string, mention: string, action: NovelIdentityAction, materialId: string | null,
    expectedCas: NovelWorkspaceCas,
  ): Promise<NovelProject> {
    if (this.hasActiveProjectDriver(projectId)) return Promise.reject(projectBusy(projectId));
    return this.commitMutation(projectId, 'identity_clarification', project => {
      let next: NovelProject = project;
      let targetId: string | null = materialId;
      if (action === 'create') {
        if (materialId !== null) throw invalidInput('新人物不能指定已有资料ID');
        const mutation = upsertStateMaterial(project, null, 'character', mention,
          `作者已确认人物身份：${mention}`, true, this.nowMs(), { injectionMode: 'smart' });
        next = mutation.project; targetId = mutation.value.id;
      } else if (action === 'merge') {
        const material = effectiveNovelMaterials(project).find(item => item.id === materialId && item.kind === 'character');
        if (material === undefined) throw invalidInput('请选择当前分支已有的人物');
        next = upsertStateMaterial(project, material.id, material.kind, material.title, material.content, material.enabled,
          this.nowMs(), { ...material, aliases: [...new Set([...(material.aliases ?? []), mention])] }).project;
      }
      next = withBranchMaterialEdits(next, next.materials);
      const state = applyNovelIdentityClarification(project.structuredState ?? emptyNovelStructuredState(),
        mention, action, targetId, effectiveNovelMaterials(next));
      return { ...next, structuredState: state, updatedAt: this.nowMs() };
    }, novelId(), expectedCas);
  }

  async repairContinuityIssue(projectId: string, issue: NovelAuditIssue): Promise<DurableWorkspaceProposal> {
    return this.repairContinuityIssues(projectId, [issue]);
  }

  async cancelContinuityRepair(projectId: string): Promise<void> {
    return this.repairs.cancelContinuityRepair(projectId);
  }

  async repairContinuityIssues(projectId: string, issues: NovelAuditIssue[]): Promise<DurableWorkspaceProposal> {
    return this.repairs.repairContinuityIssues(projectId, issues);
  }

  async previewContext(
    projectId: string, userText: string, mode: NovelChatMode,
    granularity: NovelGenerationGranularity | null = null,
    overrides?: NovelInjectionOverrides,
  ): Promise<NovelContextPreviewResult> {
    const trimmed: string = userText.trim();
    if (trimmed.length === 0) throw invalidInput('请输入内容');
    if (trimmed.length > MAX_USER_CHARS) throw invalidInput(`输入超过 ${MAX_USER_CHARS} 字`);
    const snapshot = await this.repository.readWorkspaceSnapshot(projectId);
    if (mode === 'write') this.assertWritingContextReady(snapshot.status);
    if (overrides?.branchId !== undefined && overrides.branchId !== snapshot.status.activeBranchId) {
      throw invalidInput('本次资料选择所属分支已切换，请重新预览');
    }
    if (this.modelRunning.previewContext === undefined) throw invalidInput('当前模型适配器未提供实际发送预览');
    const project: NovelProject = snapshot.project;
    const gran: NovelGenerationGranularity | null = mode === 'write'
      ? granularity ?? project.lastGenerationGranularity : null;
    const runKind: NovelRunKind = novelComposerRunKind(mode, gran);
    const modelTarget = await this.modelTargetFor(project, runKind);
    const request: NovelModelRequest = {
      runId: `preview:${novelId()}`, projectId, modelTarget, maxOutputTokens: null,
      systemPrompt: '', context: buildNovelContext(project, mode, gran, runKind, trimmed, overrides),
      history: project.messages.map(novelMessageUi), toolProfile: mode === 'write' ? 'none' : 'all',
      operation: { kind: 'turn', userPrompt: trimmed }, checkpoint: async (): Promise<void> => {},
    };
    return { projectId, branchId: snapshot.status.activeBranchId, receipt: await this.modelRunning.previewContext(request) };
  }

  generate(
    projectId: string, userText: string, mode: NovelChatMode,
    granularity: NovelGenerationGranularity | null = null,
    runKind: NovelRunKind | null = null,
    sourceChapterId: string | null = null,
    overrides?: NovelInjectionOverrides, expectedCas?: NovelWorkspaceCas,
  ): NovelRun {
    return this.ordinary.generate(projectId, userText, mode, granularity, runKind, sourceChapterId, overrides, expectedCas);
  }

  activeRun(projectId: string): NovelActiveRun | null {
    return this.ordinary.activeRun(projectId);
  }

  async readOrdinaryRun(projectId: string): Promise<NovelOrdinaryRunView> {
    return this.ordinary.readOrdinaryRun(projectId);
  }

  retryOrdinaryRun(projectId: string, recordId: string): NovelRun {
    return this.ordinary.retryOrdinaryRun(projectId, recordId);
  }

  resumeOrdinaryRun(projectId: string, recordId: string): NovelRun {
    return this.ordinary.resumeOrdinaryRun(projectId, recordId);
  }

  continueTool(
    projectId: string, toolCallId: string, verdict: NovelToolContinuationVerdict,
  ): NovelRun {
    return this.ordinary.continueTool(projectId, toolCallId, verdict);
  }

  setChapterContract(projectId: string, input: NovelChapterContractInput, status: NovelChapterContractStatus,
    expectedCas?: NovelWorkspaceCas): Promise<NovelProject> {
    this.assertProjectIdle(projectId);
    return this.commitMutation(projectId, 'branch_settings_change', (project, branchId) =>
      withNovelChapterContract(project, input, status, branchId, this.nowMs()), novelId(), expectedCas);
  }

  clearChapterContract(projectId: string, expectedCas?: NovelWorkspaceCas): Promise<NovelProject> {
    this.assertProjectIdle(projectId);
    return this.commitMutation(projectId, 'branch_settings_change', project => ({ ...project,
      branchSettings: { ...project.branchSettings, chapterContract: undefined, suggestedChapterCount: undefined, thisChapterPlan: '' },
      updatedAt: Math.max(project.updatedAt, this.nowMs()), revision: project.revision + 1 }), novelId(), expectedCas);
  }

  setUpcomingArc(projectId: string, beats: string[], expectedCas?: NovelWorkspaceCas): Promise<NovelProject> {
    this.assertProjectIdle(projectId);
    return this.commitMutation(projectId, 'branch_settings_change', project =>
      withNovelUpcomingArc(project, beats, this.nowMs()), novelId(), expectedCas);
  }

  async proposeChapterContract(projectId: string, guidance: string = '', expectedCas?: NovelWorkspaceCas,
    control?: NovelAuditController): Promise<{ contract: NovelChapterContract; expectedCas: NovelWorkspaceCas }> {
    this.assertProjectIdle(projectId);
    const snapshot = await this.repository.readWorkspaceSnapshot(projectId);
    this.assertExpectedSnapshot(snapshot.status.cas, expectedCas);
    const target = await this.modelTargetFor(snapshot.project, null);
    await this.modelRunning.validate(target, projectId);
    const context = buildNovelContext(snapshot.project, 'discuss', null, 'discussion', guidance);
    context.sections = [{ key: 'instruction', text: CHAPTER_CONTRACT_PROPOSAL_SYSTEM, required: true },
      ...context.sections.filter(section => section.key !== 'instruction')];
    const text = await collectChapterContractProposal(this.modelRunning, {
      runId: novelId(), projectId, modelTarget: target, toolProfile: 'none', maxOutputTokens: 4096,
      systemPrompt: CHAPTER_CONTRACT_PROPOSAL_SYSTEM, context, history: [],
      operation: { kind: 'turn', userPrompt: guidance.trim() || '请规划下一章目标、冲突、必须发生与禁止发生的内容。' },
      checkpoint: async (): Promise<void> => {},
    }, control);
    return { contract: makeNovelChapterContract(parseChapterContractProposal(text), 'draft', snapshot.status.activeBranchId, this.nowMs()),
      expectedCas: snapshot.status.cas };
  }

  private assertExpectedSnapshot(actual: NovelWorkspaceCas, expected?: NovelWorkspaceCas): void {
    if (expected !== undefined && (actual.branchId !== expected.branchId || actual.head !== expected.head || actual.treeDigest !== expected.treeDigest))
      throw invalidInput('工作区已变化，请重新读取后操作');
  }

  async reviseChapter(projectId: string, chapterId: string, authorBrief: string, expectedCas?: NovelWorkspaceCas): Promise<NovelRun> {
    if (authorBrief.trim().length === 0) throw invalidInput('请输入本章改稿要求');
    return this.regenerateChapter(projectId, chapterId, authorBrief, expectedCas);
  }

  async regenerateChapter(projectId: string, chapterId: string, authorBrief: string = '', expectedCas?: NovelWorkspaceCas): Promise<NovelRun> {
    this.assertProjectIdle(projectId);
    const snapshot = await this.repository.readWorkspaceSnapshot(projectId);
    this.assertExpectedSnapshot(snapshot.status.cas, expectedCas);
    this.assertWritingContextReady(snapshot.status);
    const chapter = snapshot.project.chapters.find(c => c.id === chapterId);
    if (chapter === undefined) throw notFound('chapter', chapterId);
    const userText = `请重新生成章节「${chapter.title}」，输出完整章节。允许按作者要求改变情节和事实以修复矛盾。`
      + (authorBrief.trim() ? `\n作者改稿要求：${authorBrief.trim()}` : '请重新设计本章情节与叙事。');
    if (userText.length > MAX_USER_CHARS) throw invalidInput('本章改稿要求过长');
    await this.modelRunning.validate(await this.modelTargetFor(snapshot.project, 'regenerate'), projectId);
    return this.generate(projectId, userText, 'write', 'whole_chapter', 'regenerate', chapterId, undefined, expectedCas ?? snapshot.status.cas);
  }

  async polishChapter(projectId: string, chapterId: string): Promise<NovelRun> {
    const project: NovelProject = await this.findProjectAsync(projectId);
    const chapter: NovelChapter | null = project.chapters.find(c => c.id === chapterId) ?? null;
    if (chapter === null) throw notFound('chapter', chapterId);
    const userText: string = `请润色章节「${chapter.title}」,输出润色后的完整章节全文。`;
    return this.generate(projectId, userText, 'write', 'whole_chapter', 'polish', chapterId);
  }

  async quickStart(projectId: string, idea: string): Promise<NovelRun> {
    return this.generate(projectId, idea, 'discuss', null, 'quick_start');
  }

  async quickStartFromSeed(projectId: string, guidance: string = '', coreIdeaOverride: string = ''): Promise<NovelRun> {
    const project: NovelProject = await this.repository.loadProject(projectId);
    return this.quickStart(projectId, novelQuickStartRequestText(project, guidance, coreIdeaOverride));
  }

  async setChapterDiscarded(projectId: string, chapterId: string, discarded: boolean): Promise<NovelProject> {
    return this.commitMutation(projectId, 'chapter_discard', (p: NovelProject): NovelProject => {
      const idx: number = p.chapters.findIndex(c => c.id === chapterId);
      if (idx < 0) return p;
      const chapters: NovelChapter[] = p.chapters.slice();
      chapters[idx] = { ...chapters[idx], discarded: discarded, updatedAt: Date.now() };
      return { ...p, chapters: chapters, updatedAt: Date.now() };
    });
  }

  async restoreChapterVersion(projectId: string, versionId: string): Promise<NovelProject> {
    const now: number = Date.now();
    return this.commitMutation(projectId, 'version_restore', (p: NovelProject): NovelProject => {
      const v: NovelChapterVersion | undefined = p.chapterVersions.find(x => x.id === versionId);
      if (v === undefined) return p;
      const idx: number = p.chapters.findIndex(c => c.id === v.chapterId);
      if (idx < 0) return p;
      const current: NovelChapter = p.chapters[idx];
      const backup: NovelChapterVersion =
        makeNovelChapterVersion(v.chapterId, 'manual', current.title, current.content, now);
      const restored: NovelChapter = { ...current, title: v.title, content: v.content, updatedAt: now };
      const chapters: NovelChapter[] = p.chapters.slice();
      chapters[idx] = restored;
      return { ...p, chapters: chapters, chapterVersions: [...p.chapterVersions, backup], updatedAt: now };
    });
  }

  async prepareDiscussionArchive(
    projectId: string, messageIds: string[],
  ): Promise<NovelDiscussionArchiveDraft> {
    const project: NovelProject = await this.repository.loadProject(projectId);
    const status: NovelWorkspaceStatus = await this.repository.workspaceStatus(projectId);
    return prepareNovelDiscussionArchive(this.modelRunning,
      await this.resolvedModelTarget(project, 'stateSync'),
      project, messageIds, status.activeBranchId, this.idleTimeoutMs);
  }

  async confirmDiscussionArchive(
    projectId: string, draft: NovelDiscussionArchiveDraft, editedSummary: string,
    selectedDecisionIndexes: number[],
  ): Promise<NovelDiscussionArchive> {
    const box: { value: NovelDiscussionArchive | null } = { value: null };
    await this.commitMutation(projectId, 'discussion_archive', (project, branchId) => {
      const confirmed = confirmNovelDiscussionArchive(project, draft, editedSummary,
        selectedDecisionIndexes, branchId, this.nowMs());
      box.value = confirmed.archive;
      return confirmed.project;
    });
    if (box.value === null) throw new Error('confirmDiscussionArchive: 更新未返回归档');
    return box.value;
  }

  async restorePreviousProject(): Promise<NovelProject | null> {
    return this.repository.restorePrevious();
  }

  interrupt(runId: string): void {
    return this.ordinary.interrupt(runId);
  }

  private async findProjectAsync(projectId: string): Promise<NovelProject> {
    return this.open(projectId);
  }

  private async resolvedModelTarget(project: NovelProject, role: NovelModelRole): Promise<NovelProject['modelPolicy']['writing']> {
    return resolveNovelDefaultTarget(project.modelPolicy, await this.loadModelDefaults(), role);
  }

  private async modelTargetFor(project: NovelProject, runKind: NovelRunKind | null): Promise<NovelProject['modelPolicy']['writing']> {
    return this.resolvedModelTarget(project, runKind === 'polish' ? 'review' : 'writing');
  }

  private assertWritingContextReady(status: NovelWorkspaceStatus): void {
    if (status.plotStale || status.unresolvedFromOrdinal !== null) {
      throw invalidInput('正文已变更，请先同步剧情并确认后续章节影响，再开始写作');
    }
  }

  private async commitMutation(
    projectId: string, kind: NovelWorkspaceMutationKind,
    transform: (project: NovelProject, branchId: string) => NovelProject,
    commandId: string = novelId(), expectedCas?: NovelWorkspaceCas,
  ): Promise<NovelProject> {
    const status: NovelWorkspaceStatus = await this.repository.workspaceStatus(projectId);
    return await this.repository.commitProject(projectId, expectedCas ?? status.cas, commandId, kind,
      (project: NovelProject): NovelProject => transform(project, status.activeBranchId));
  }

  async validateGhostwriteModels(projectId: string, loadedProject: NovelProject | null = null): Promise<void> {
    return this.jobs.validateGhostwriteModels(projectId, loadedProject);
  }
}

export const createNovelCreation = (deps: NovelCreationDeps): NovelCreation => {
  return new NovelCreation(deps);
};
