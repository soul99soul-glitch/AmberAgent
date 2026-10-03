import { selectedPolishTargets, frozenPolishContext } from './polish_context.ts';
import type { NovelPolishStartPreview } from './polish_context.ts';
import { projectPolishOutcomes } from './polish.ts';
import type { PolishChapterResult } from './polish.ts';
import { novelProjectOperationKind, readNovelProjectOperation } from './specialized_operations.ts';
import type { NovelProjectOperationKind, NovelProjectToolInput, NovelProjectToolResult } from './specialized_operations.ts';
import { withNovelChapterContract, withNovelUpcomingArc, makeNovelChapterContract, collectChapterContractProposal, parseChapterContractProposal } from './chapter_contract.ts';
import type { NovelChapterContractInput, NovelChapterContractStatus, NovelChapterContract } from './chapter_contract.ts';
import { defaultNovelModelDefaults, resolveNovelDefaultTarget, projectWithNovelStorySeed, novelQuickStartRequestText } from './standalone_defaults.ts';
import type { NovelModelDefaults, NovelModelRole, NovelStorySeed } from './standalone_defaults.ts';
// novel/creation — 小说创作编排器(移植自 Android DefaultNovelCreation)
// CQRS 风格:异步查询/变更方法 + generate(流式 NovelRun) + interrupt。
// 每项目单运行(busy 拒绝);累积 partial、超 MAX_OUTPUT_CHARS 取消、idle 超时失败;
// 中断保留 partial(interrupted=true);收录先存章节再分析素材建议。纯逻辑,mock 模型可测。

import {
  makeNovelMessage, novelId, makeNovelChapterVersion, novelChapterOrdinal,
} from './models.ts';
import type { NovelRunKind } from './prompt_catalog.ts';
import type {
  NovelProject, NovelMessage, NovelChapter, NovelMaterial, NovelMaterialSuggestion,
  NovelChatMode, NovelGenerationGranularity, NovelMaterialKind, NovelCollectionTarget, NovelChapterVersion,
  NovelModelPolicy, NovelModelTarget, NovelBranchSettings, NovelMaterialFields,
  NovelCandidateProvenance,
} from './models.ts';
import {
  createProject, renameProject, setProjectModelPolicy, setProjectBranchSettings,
  appendMessage, saveChapter, saveChapterVersion, deleteChapter,
  upsertMaterial, deleteMaterial, collect, replacePendingSuggestions, resolveSuggestion,
  resolveSettingProposal as resolveSettingProposalMutation,
} from './mutations.ts';
import {
  invalidInput, notFound, projectBusy, providerError, outputTooLarge, isNovelError,
} from './error.ts';
import { systemPrompt, buildNovelContext, novelComposerRunKind, MAX_USER_CHARS, MAX_OUTPUT_CHARS } from './context_builder.ts';
import { analyzeChapterSuggestions } from './suggestion_engine.ts';
import { runContinuityAudit } from './continuity_audit.ts';
import type { NovelAuditReport, NovelAuditController } from './continuity_audit.ts';
import { parseSettingProposals } from './setting_proposal_parser.ts';
import type { NovelSettingProposal } from './models.ts';
import type { NovelModelRunning, NovelModelRequest, NovelModelEvent, NovelModelStream } from './model_running.ts';
import type { NovelToolContinuationVerdict } from './model_running.ts';
import type { NovelContextPreviewReceipt } from './model_running.ts';
import type { NovelInjectionOverrides } from './material_injection.ts';
import { NovelStateService } from './state_rebuild.ts';
import { freezeNovelOrdinaryRequest, ordinaryRunMessages, ordinaryRunHistoryCount, ordinaryContinuationHistory, assertOrdinaryRunTranscript, ordinaryRunCursor, ordinaryRecoveryRequest } from './ordinary_run.ts';
import type { NovelOrdinaryRun, NovelOrdinaryRunView, NovelOrdinaryRunStatus } from './ordinary_run.ts';
import type { NovelStateOperation } from './state_rebuild.ts';
import {
  emptyNovelStructuredState, pruneNovelStructuredState, projectNovelCharacterExperiences,
  applyNovelIdentityClarification,
} from './structured_state.ts';
import type { NovelIdentityAction, NovelCharacterExperience } from './structured_state.ts';
import { effectiveNovelMaterials, withBranchMaterialEdits } from './material_inheritance.ts';
import { upsertMaterial as upsertStateMaterial } from './mutations.ts';
import { prepareContinuityRepair, parseContinuityRepair, mergeContinuityRepairs, collectContinuityRepairText,
  CONTINUITY_REPAIR_SYSTEM_PROMPT, REPAIR_MAX_OUTPUT_TOKENS } from './continuity_repair.ts';
import type { AppliedContinuityRepair } from './continuity_repair.ts';
import type { NovelAuditIssue } from './continuity_audit.ts';
import { chapterFileName } from './workspace_contract.ts';
import type {
  NovelProjectRepository, NovelWorkspaceMutationKind, NovelWorkspaceStatus, NovelWorkspaceCas, NovelWorkspaceSnapshot,
} from './repository.ts';
import { latestAssistantText } from '../agent/message.ts';
import type {
  StreamTransportState, UIMessage, UIMessagePart, UIMessagePartTool,
} from '../agent/message.ts';
import type { DurableWorkspaceProposal, WorkspaceProposalPatch, WorkspaceCommit, WorkspaceProposalReview } from './workspace_history.ts';
import { novelMessageText, novelMessageUi, withNovelMessageText } from './transcript.ts';
import { makeNovelCandidateProvenance } from './candidate_provenance.ts';
import { cloneCollectedMessage } from './collected_candidates.ts';
import { hasPolishSentinel, stripPolishSentinel, CHAPTER_CONTRACT_PROPOSAL_SYSTEM } from './prompt_catalog.ts';
import { decodeNovelWorkspaceUtf8 } from './workspace_exchange.ts';
import type { NovelProjectInventory, NovelProjectRecoveryPreview, NovelNativeRestorePreview, NovelNativeBackupSnapshot } from './workspace_storage.ts';
import type { NovelNativeBackupImport } from './native_backup.ts';
import type { NovelWorkspaceImportPlan } from './workspace_interop.ts';
import type { NovelWorkspaceArchiveFile } from './workspace_exchange.ts';
import type {
  DurableGhostwriteJob, GhostwriteClaimRef, GhostwriteStage,
} from './ghostwrite.ts';
import { createGhostwriteRunner } from './ghostwrite_runner.ts';
import type { GhostwriteRunner } from './ghostwrite_runner.ts';
import type { DurablePolishJob, PolishContextOptions, PolishStage } from './polish.ts';
import { createPolishRunner } from './polish_runner.ts';
import type { PolishRunner } from './polish_runner.ts';
import { materialSuggestionChapterDigest, assertMaterialAdoptionBranch } from './material_adoption.ts';
import type { NovelMaterialAdoption } from './material_adoption.ts';
import { prepareNovelDiscussionArchive, confirmNovelDiscussionArchive } from './discussion_archive.ts';
import type { NovelDiscussionArchive, NovelDiscussionArchiveDraft } from './discussion_archive.ts';

import { stageNovelQuickStart } from './quick_start_proposals.ts';
import { makeGhostwriteStartPreview, assertGhostwriteStartPreview } from './ghostwrite_start_preview.ts';
import type { NovelGhostwriteStartPreview } from './ghostwrite_start_preview.ts';
import { ghostwriteJobReport } from './job_report.ts';
import type { NovelGhostwriteReport } from './job_report.ts';

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

const DEFAULT_IDLE_TIMEOUT_MS: number = 90_000;
const GHOSTWRITE_LEASE_MS: number = 12 * 60 * 60 * 1000;
const POLISH_LEASE_MS: number = 12 * 60 * 60 * 1000;

// 事件发射器:保留最近一帧 snapshot 与无订阅者时的终结事件，供页面离开后重连。
// 只保留一帧，避免生成过程按事件数线性增长。
interface Emitter<T> {
  emit(v: T): void;
  subscribe(cb: (v: T) => void): () => void;
}

const isTerminal = <T>(v: T): boolean => {
  const k: string = (v as { kind?: string }).kind ?? '';
  return k === 'completed' || k === 'waiting_user' || k === 'interrupted' ||
    k === 'failed' || k === 'cancelled';
};

interface CollectedModelResult {
  terminal: 'completed' | 'waiting_user';
  messages: UIMessage[];
  text: string;
}

interface RawWorkspaceWritePatch {
  operation?: unknown;
  path?: unknown;
  content?: unknown;
}

interface RawWorkspaceWriteInput {
  proposal_id?: unknown;
  patches?: RawWorkspaceWritePatch[];
}

const uiMessageEpoch = (message: UIMessage, fallback: number): number => {
  const parsed: number = Date.parse(message.createdAt);
  return Number.isFinite(parsed) ? parsed : fallback;
};

const transcriptFromUi = (
  project: NovelProject, messages: UIMessage[], mode: NovelChatMode,
  granularity: NovelGenerationGranularity | null, now: number,
  candidate?: NovelCandidateProvenance, runKind?: NovelRunKind | null,
): NovelMessage[] => {
  const existing: Map<string, NovelMessage> = new Map();
  project.messages.forEach((message: NovelMessage): void => {
    existing.set(novelMessageUi(message).id, message);
  });
  const next: NovelMessage[] = [];
  messages.forEach((uiMessage: UIMessage): void => {
    if (uiMessage.role !== 'user' && uiMessage.role !== 'assistant') return;
    const prior: NovelMessage | undefined = existing.get(uiMessage.id);
    const interrupted: boolean = uiMessage.annotations.some(
      (annotation): boolean => annotation.type === 'generation_interrupted');
    next.push(makeNovelMessage({
      id: prior?.id ?? uiMessage.id,
      role: uiMessage.role,
      mode: prior?.mode ?? mode,
      uiMessage,
      createdAt: prior?.createdAt ?? uiMessageEpoch(uiMessage, now),
      granularity: prior?.granularity ?? (uiMessage.role === 'assistant' ? granularity : null),
      collectedChapterId: prior?.collectedChapterId ?? null,
      interrupted: prior?.interrupted ?? interrupted,
      candidate: prior !== undefined ? prior.candidate : (uiMessage.role === 'assistant' ? candidate : undefined),
      runKind: prior !== undefined ? prior.runKind : (uiMessage.role === 'assistant' ? runKind : undefined),
    }));
  });
  return next;
};

const createEmitter = <T>(): Emitter<T> => {
  const subs = new Set<(v: T) => void>();
  const terminal: T[] = [];
  let latestLive: T | null = null;
  return {
    emit(v: T): void {
      const kind: string = (v as { kind?: string }).kind ?? '';
      if (!isTerminal(v) && (kind === 'snapshot' || latestLive === null)) latestLive = v;
      if (subs.size > 0) {
        subs.forEach(cb => { cb(v); });
      } else if (isTerminal(v)) {
        terminal.push(v); // 无订阅者时缓存终结事件
      }
    },
    subscribe(cb: (v: T) => void): () => void {
      if (latestLive !== null) cb(latestLive);
      for (let i = 0; i < terminal.length; i++) cb(terminal[i]);
      subs.add(cb);
      return () => { subs.delete(cb); };
    },
  };
};

interface LiveRun {
  ordinary?: NovelOrdinaryRun;
  runKind?: NovelRunKind | null;
  runId: string;
  projectId: string;
  handle: NovelRun;
  historyCount: number;
  userText: string;
  startedAt: number;
  partial: string;
  interrupted: boolean;
  candidate?: NovelCandidateProvenance;
  branchId: string | null;
  messages: UIMessage[];
  interruptedSaveError?: string;
  doneResolve: () => void;
  donePromise: Promise<void>;
}
interface LiveContinuityRepair {
  cancelled: boolean;
  cancelText: (() => void) | null;
  done: Promise<void>;
  finish: () => void;
}

export class NovelCreation {
  private repository: NovelProjectRepository;
  private modelRunning: NovelModelRunning;
  private loadModelDefaults: () => Promise<NovelModelDefaults>;
  private ghostwriteRunner: GhostwriteRunner;
  private polishRunner: PolishRunner;
  private polishEffects: NovelPolishEffects | null;
  private nowMs: () => number;
  private idleTimeoutMs: number;
  private projectRuns: Map<string, LiveRun> = new Map();
  // 仅防止同一进程重复驱动；durable job/repository 始终是唯一事实源。
  private drivingGhostwriteJobs: Map<string, Promise<void>> = new Map();
  private drivingPolishJobs: Map<string, Promise<void>> = new Map();
  private stateService: NovelStateService;
  private repairRuns: Map<string, LiveContinuityRepair> = new Map();

  constructor(deps: NovelCreationDeps) {
    this.repository = deps.repository;
    this.modelRunning = deps.modelRunning;
    this.loadModelDefaults = deps.loadModelDefaults ?? (async (): Promise<NovelModelDefaults> => defaultNovelModelDefaults());
    this.stateService = new NovelStateService(this.repository, this.modelRunning,
      async (project: NovelProject): Promise<NovelModelTarget> => resolveNovelDefaultTarget(
        project.modelPolicy, await this.loadModelDefaults(), 'stateSync'));
    this.ghostwriteRunner = createGhostwriteRunner({
      repository: deps.repository,
      modelRunning: deps.modelRunning,
      resolveModelTarget: async (policy, role) => resolveNovelDefaultTarget(policy, await this.loadModelDefaults(), role),
      nowMs: deps.nowMs,
      makeId: (prefix: string): string => `${prefix}:${novelId()}`,
    });
    this.polishRunner = createPolishRunner({
      loadPolishPreference: deps.loadPolishPreference,
      repository: deps.repository,
      modelRunning: deps.modelRunning,
      resolveModelTarget: async (policy, role) => resolveNovelDefaultTarget(policy, await this.loadModelDefaults(), role),
      nowMs: deps.nowMs,
      makeId: (prefix: string): string => `${prefix}:${novelId()}`,
    });
    this.polishEffects = deps.polishEffects ?? null;
    this.nowMs = deps.nowMs ?? ((): number => Date.now());
    this.idleTimeoutMs = deps.idleTimeoutMs ?? DEFAULT_IDLE_TIMEOUT_MS;
  }

  // ===== 查询 / 变更 =====
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
    if (this.projectRuns.has(projectId)) return Promise.reject(projectBusy(projectId));
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
    const prefix: string = `${projectId}:`;
    return this.projectRuns.has(projectId) || this.stateService.isRunning(projectId) || this.repairRuns.has(projectId) ||
      Array.from(this.drivingGhostwriteJobs.keys()).some((key: string): boolean => key.startsWith(prefix)) ||
      Array.from(this.drivingPolishJobs.keys()).some((key: string): boolean => key.startsWith(prefix));
  }
  publicExportPlan(projectId: string): Promise<NovelWorkspaceImportPlan> {
    return this.repository.publicExportPlan(projectId);
  }

  async open(projectId: string): Promise<NovelProject> {
    const project: NovelProject = await this.repository.loadProject(projectId);
    const repaired: boolean = await this.reconcileDeniedWorkspaceTools(projectId, project);
    const opened: NovelProject = repaired ? await this.repository.loadProject(projectId) : project;
    void this.recoverGhostwriteDriver(projectId).catch((): void => {
      // Durable job remains visible as paused/failed; open 本身不因后台接管失败而隐藏项目。
    });
    void this.recoverPolishDriver(projectId).catch((): void => {
      // Durable polish remains visible; foreground recovery failure must not hide the project.
    });
    return opened;
  }

  readWorkspaceSnapshot(projectId: string): Promise<NovelWorkspaceSnapshot> {
    return this.repository.readWorkspaceSnapshot(projectId);
  }

  workspaceStatus(projectId: string): Promise<NovelWorkspaceStatus> {
    return this.repository.workspaceStatus(projectId);
  }

  async ghostwriteJob(projectId: string): Promise<DurableGhostwriteJob | null> {
    const jobs: DurableGhostwriteJob[] = await this.repository.listGhostwriteJobs(projectId);
    jobs.sort((left: DurableGhostwriteJob, right: DurableGhostwriteJob): number =>
      right.updatedAt - left.updatedAt);
    const active: DurableGhostwriteJob | undefined = jobs.find(
      (job: DurableGhostwriteJob): boolean => job.stage !== 'completed' && job.stage !== 'cancelled');
    if (active === undefined) return null;
    const progress = await this.repository.ghostwriteProgress(projectId, active.jobId);
    return { ...active, progress };
  }

  async prepareGhostwriteStart(projectId: string, targetChapterCount: number): Promise<NovelGhostwriteStartPreview> {
    const snapshot = await this.repository.readWorkspaceSnapshot(projectId);
    this.assertWritingContextReady(snapshot.status);
    return makeGhostwriteStartPreview(snapshot, targetChapterCount);
  }

  async latestGhostwriteReport(projectId: string): Promise<NovelGhostwriteReport | null> {
    const jobs: DurableGhostwriteJob[] = (await this.repository.listGhostwriteJobs(projectId)).filter(
      job => job.stage === 'completed' || job.stage === 'cancelled');
    jobs.sort((left, right): number => right.updatedAt - left.updatedAt);
    if (jobs.length === 0) return null;
    const progress = await this.repository.ghostwriteProgress(projectId, jobs[0].jobId);
    return ghostwriteJobReport({ ...jobs[0], progress });
  }

  async startGhostwrite(
    projectId: string, targetChapterCount: number, preview?: NovelGhostwriteStartPreview,
  ): Promise<DurableGhostwriteJob> {
    const snapshot = await this.repository.readWorkspaceSnapshot(projectId);
    this.assertWritingContextReady(snapshot.status);
    const frozen: NovelGhostwriteStartPreview = preview ?? makeGhostwriteStartPreview(snapshot, targetChapterCount);
    assertGhostwriteStartPreview(snapshot, targetChapterCount, frozen);
    await this.validateGhostwriteModels(projectId, snapshot.project);
    const jobId: string = novelId();
    const job: DurableGhostwriteJob = await this.repository.startGhostwriteJob(
      projectId, frozen.expectedCas, jobId, `${jobId}:plan:${snapshot.project.chapters.length + 1}`,
      targetChapterCount, this.nowMs());
    this.launchGhostwriteDriver(projectId, job.jobId);
    return job;
  }

  async pauseGhostwrite(projectId: string, jobId: string): Promise<DurableGhostwriteJob> {
    const job: DurableGhostwriteJob = await this.repository.loadGhostwriteJob(projectId, jobId);
    const claim: GhostwriteClaimRef = await this.ensureGhostwriteClaim(projectId, job);
    const paused: DurableGhostwriteJob =
      await this.repository.pauseGhostwriteJob(projectId, jobId, claim, this.nowMs());
    this.ghostwriteRunner.cancel(projectId, jobId);
    return paused;
  }

  async resumeGhostwrite(projectId: string, jobId: string): Promise<DurableGhostwriteJob> {
    await this.drivingGhostwriteJobs.get(`${projectId}:${jobId}`);
    const resumed: DurableGhostwriteJob = await this.repository.resumeGhostwriteJob(
      projectId, jobId, this.nowMs());
    this.launchGhostwriteDriver(projectId, jobId);
    return resumed;
  }

  async reviseGhostwriteWithBrief(
    projectId: string, jobId: string, brief: string, expectedCas?: NovelWorkspaceCas,
    expectedCandidateDigest?: string,
  ): Promise<DurableGhostwriteJob> {
    await this.drivingGhostwriteJobs.get(`${projectId}:${jobId}`);
    const snapshot = await this.repository.readWorkspaceSnapshot(projectId);
    await this.validateGhostwriteModels(projectId, snapshot.project);
    const revised = await this.repository.reviseGhostwriteWithBrief(
      projectId, jobId, brief, expectedCas ?? snapshot.status.cas, this.nowMs(), expectedCandidateDigest);
    return revised.stage === 'paused'
      ? this.resumeGhostwrite(projectId, jobId) : this.retryGhostwrite(projectId, jobId);
  }

  async retryGhostwrite(projectId: string, jobId: string): Promise<DurableGhostwriteJob> {
    await this.drivingGhostwriteJobs.get(`${projectId}:${jobId}`);
    const retried: DurableGhostwriteJob = await this.repository.retryGhostwriteJob(
      projectId, jobId, this.nowMs());
    this.launchGhostwriteDriver(projectId, jobId);
    return retried;
  }

  async cancelGhostwrite(projectId: string, jobId: string): Promise<DurableGhostwriteJob> {
    const job: DurableGhostwriteJob = await this.repository.loadGhostwriteJob(projectId, jobId);
    const now: number = this.nowMs();
    const claim: GhostwriteClaimRef | null = job.claim !== null && now < job.claim.leaseUntil
      ? { token: job.claim.token, epoch: job.claim.epoch } : null;
    const cancelled: DurableGhostwriteJob =
      await this.repository.cancelGhostwriteJob(projectId, jobId, claim, this.nowMs());
    this.ghostwriteRunner.cancel(projectId, jobId);
    return cancelled;
  }

  async polishJob(projectId: string, requestedJobId: string = ''): Promise<DurablePolishJob | null> {
    if (requestedJobId.length > 0) {
      const requested: DurablePolishJob = await this.repository.loadPolishJob(projectId, requestedJobId);
      const progress = await this.repository.polishProgress(projectId, requested.jobId);
      return { ...requested, progress };
    }
    const jobs: DurablePolishJob[] = await this.repository.listPolishJobs(projectId);
    jobs.sort((left: DurablePolishJob, right: DurablePolishJob): number =>
      right.updatedAt - left.updatedAt);
    const active: DurablePolishJob | undefined = jobs.find(
      (job: DurablePolishJob): boolean => job.stage !== 'completed' && job.stage !== 'cancelled');
    const selected: DurablePolishJob | undefined = active ?? jobs[0];
    if (selected === undefined) return null;
    const progress = await this.repository.polishProgress(projectId, selected.jobId);
    return { ...selected, progress };
  }

  async previewPolishSelected(
    projectId: string, chapterIds: string[], contextOptions: PolishContextOptions,
  ): Promise<NovelPolishStartPreview> {
    const snapshot = await this.repository.readWorkspaceSnapshot(projectId);
    this.assertWritingContextReady(snapshot.status);
    const targets = selectedPolishTargets(snapshot.project, chapterIds);
    const preference = (snapshot.project.polishPreference ?? '').trim();
    return { cas: snapshot.status.cas, chapterIds: targets.map(target => target.id), targets,
      polishPreference: preference, preferenceSource: preference ? 'project' : 'none', contextOptions,
      contextSnapshot: frozenPolishContext(snapshot.project, snapshot.status.activeBranchId, contextOptions,
        targets, snapshot.project.authorPlot ?? null) };
  }

  async startPolishSelected(
    projectId: string, chapterIds: string[], contextOptions: PolishContextOptions, expectedCas?: NovelWorkspaceCas,
  ): Promise<DurablePolishJob> {
    const snapshot = await this.repository.readWorkspaceSnapshot(projectId);
    const targets = selectedPolishTargets(snapshot.project, chapterIds);
    await this.validateGhostwriteModels(projectId, snapshot.project);
    this.assertWritingContextReady(snapshot.status);
    const job = await this.repository.startPolishJob(projectId, expectedCas ?? snapshot.status.cas, novelId(),
      targets[0].ordinal, targets[targets.length - 1].ordinal, contextOptions, this.nowMs(), targets.map(target => target.ordinal));
    await this.syncPolishEffects(job, true);
    this.launchPolishDriver(projectId, job.jobId, null);
    return job;
  }

  async polishOutcomes(projectId: string, jobId: string): Promise<PolishChapterResult[]> {
    return projectPolishOutcomes(await this.repository.loadPolishJob(projectId, jobId));
  }

  async startPolish(
    projectId: string, fromOrdinal: number, toOrdinal: number, contextOptions: PolishContextOptions,
  ): Promise<DurablePolishJob> {
    const project: NovelProject = await this.repository.loadProject(projectId);
    await this.validateGhostwriteModels(projectId, project);
    const status: NovelWorkspaceStatus = await this.repository.workspaceStatus(projectId);
    this.assertWritingContextReady(status);
    const job: DurablePolishJob = await this.repository.startPolishJob(
      projectId, status.cas, novelId(), fromOrdinal, toOrdinal, contextOptions, this.nowMs());
    await this.syncPolishEffects(job, true);
    this.launchPolishDriver(projectId, job.jobId, null);
    return job;
  }

  async startSelectedPolish(
    projectId: string, ordinals: number[], contextOptions: PolishContextOptions,
  ): Promise<DurablePolishJob> {
    const snapshot = await this.repository.readWorkspaceSnapshot(projectId);
    await this.validateGhostwriteModels(projectId, snapshot.project);
    this.assertWritingContextReady(snapshot.status);
    const selected: number[] = ordinals.slice().sort((left: number, right: number): number => left - right);
    if (selected.length === 0) throw invalidInput('至少选择一个润色章节');
    const job = await this.repository.startPolishJob(projectId, snapshot.status.cas, novelId(),
      selected[0], selected[selected.length - 1], contextOptions, this.nowMs(), selected);
    await this.syncPolishEffects(job, true);
    this.launchPolishDriver(projectId, job.jobId, null);
    return job;
  }

  async startChapterPolish(
    projectId: string, chapterId: string, contextOptions: PolishContextOptions,
  ): Promise<DurablePolishJob> {
    const snapshot = await this.repository.readWorkspaceSnapshot(projectId);
    const index: number = snapshot.project.chapters.findIndex(chapter => chapter.id === chapterId);
    if (index < 0 || snapshot.project.chapters[index].discarded) throw invalidInput('润色章节不存在或已废弃');
    const ordinal: number = novelChapterOrdinal(snapshot.project.chapters[index], index + 1);
    await this.validateGhostwriteModels(projectId, snapshot.project);
    this.assertWritingContextReady(snapshot.status);
    const job = await this.repository.startPolishJob(projectId, snapshot.status.cas, novelId(),
      ordinal, ordinal, contextOptions, this.nowMs());
    await this.syncPolishEffects(job, true);
    this.launchPolishDriver(projectId, job.jobId, null);
    return job;
  }

  async pausePolish(projectId: string, jobId: string): Promise<DurablePolishJob> {
    const job: DurablePolishJob = await this.repository.loadPolishJob(projectId, jobId);
    const claim: GhostwriteClaimRef = await this.ensurePolishClaim(projectId, job);
    const paused: DurablePolishJob = await this.repository.pausePolishJob(
      projectId, jobId, claim, this.nowMs());
    this.polishRunner.cancel(projectId, jobId);
    await this.syncPolishEffects(paused, false);
    return paused;
  }

  async resumePolish(projectId: string, jobId: string): Promise<DurablePolishJob> {
    await this.drivingPolishJobs.get(`${projectId}:${jobId}`);
    const resumed: DurablePolishJob = await this.repository.resumePolishJob(
      projectId, jobId, this.nowMs());
    await this.syncPolishEffects(resumed, true);
    this.launchPolishDriver(projectId, jobId, null);
    return resumed;
  }

  async retryPolish(projectId: string, jobId: string, chapterIds?: string[]): Promise<DurablePolishJob> {
    await this.drivingPolishJobs.get(`${projectId}:${jobId}`);
    const retried: DurablePolishJob = await this.repository.retryPolishJob(
      projectId, jobId, this.nowMs(), chapterIds);
    await this.syncPolishEffects(retried, true);
    this.launchPolishDriver(projectId, jobId, null);
    return retried;
  }

  async cancelPolish(projectId: string, jobId: string): Promise<DurablePolishJob> {
    const cancelled: DurablePolishJob = await this.repository.cancelPolishJob(
      projectId, jobId, this.nowMs());
    this.polishRunner.cancel(projectId, jobId);
    await this.syncPolishEffects(cancelled, false);
    return cancelled;
  }

  async drivePolishInBackground(
    projectId: string, branchId: string, jobId: string, maxChapters: number = 1,
  ): Promise<DurablePolishJob> {
    let job: DurablePolishJob = await this.repository.loadPolishJob(projectId, jobId);
    if (job.branchId !== branchId) throw invalidInput('polish 后台任务分支不匹配');
    if (job.stage === 'waiting_system') {
      job = await this.repository.resumePolishJob(projectId, jobId, this.nowMs());
    }
    if (!this.canDrivePolishStage(job.stage)) {
      await this.syncPolishEffects(job, false);
      return job;
    }
    if (job.claim !== null) {
      const staleClaim: GhostwriteClaimRef = { token: job.claim.token, epoch: job.claim.epoch };
      job = await this.repository.pausePolishJob(projectId, jobId, staleClaim, this.nowMs());
      this.polishRunner.cancel(projectId, jobId);
      job = await this.repository.resumePolishJob(projectId, jobId, this.nowMs());
    }
    const result: DurablePolishJob = await this.polishRunner.run(
      projectId, jobId, novelId(), POLISH_LEASE_MS, maxChapters);
    await this.syncPolishEffects(result, result.stage === 'waiting_system');
    return result;
  }

  async yieldPolishForSystem(projectId: string, jobId: string): Promise<DurablePolishJob> {
    const job: DurablePolishJob = await this.repository.loadPolishJob(projectId, jobId);
    if (!this.canDrivePolishStage(job.stage)) return job;
    const claim: GhostwriteClaimRef = await this.ensurePolishClaim(projectId, job);
    const waiting: DurablePolishJob = await this.repository.yieldPolishJob(
      projectId, jobId, claim, this.nowMs());
    this.polishRunner.cancel(projectId, jobId);
    await this.syncPolishEffects(waiting, true);
    return waiting;
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
    if (this.projectRuns.has(projectId) || this.stateService.isRunning(projectId) || this.repairRuns.has(projectId)) throw projectBusy(projectId);
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
    const status: NovelWorkspaceStatus = await this.repository.workspaceStatus(projectId);
    return await this.repository.createProposal(
      projectId, expectedCas ?? status.cas, proposalId, patches, this.nowMs(), review);
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
    return this.repository.resolveProposal(projectId, proposalId, accept, commandId, this.nowMs());
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

  /** LLM 全文连续性审计(iOS NovelContinuityAudit);结构检查仍用 checkConsistency */
  async runContinuityAudit(projectId: string, control?: NovelAuditController): Promise<NovelAuditReport> {
    const project: NovelProject = await this.repository.loadProject(projectId);
    return runContinuityAudit(
      this.modelRunning, project, await this.resolvedModelTarget(project, 'stateSync'), this.idleTimeoutMs * 2, control);
  }

  /** 从讨论/写作文本解析设定提案并写入项目(替换同 source 的 pending) */
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
    const root: string = this.workspaceToolPath(path, status.activeBranchId, true);
    const files: NovelWorkspaceArchiveFile[] = await this.repository.workspaceFiles(projectId);
    const entries: string[] = files.map((file: NovelWorkspaceArchiveFile): string => file.path)
      .filter((candidate: string): boolean => candidate === root ||
        candidate.startsWith(root.endsWith('/') ? root : `${root}/`))
      .sort();
    return { root, entries };
  }

  async workspaceRead(projectId: string, path: string): Promise<NovelWorkspaceReadResult> {
    const status: NovelWorkspaceStatus = await this.repository.workspaceStatus(projectId);
    const checked: string = this.workspaceToolPath(path, status.activeBranchId, false);
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
    const root: string = this.workspaceToolPath(path, status.activeBranchId, true);
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
    const proposal: DurableWorkspaceProposal = await this.stageWorkspaceWriteProposal(projectId, proposalId, patches);
    return await this.resolveWorkspaceProposal(projectId, proposal.proposalId, true,
      `novel:${projectId}:proposal:${proposalId}:approve`);
  }

  private async stageWorkspaceWriteProposal(
    projectId: string, proposalId: string, rawPatches: RawWorkspaceWritePatch[],
  ): Promise<DurableWorkspaceProposal> {
    const patches: WorkspaceProposalPatch[] = await this.normalizeWorkspaceProposalPatches(projectId, rawPatches);
    const snapshot: NovelWorkspaceSnapshot = await this.repository.readWorkspaceSnapshot(projectId);
    const existing: DurableWorkspaceProposal | undefined = (await this.repository.workspaceProposals(projectId))
      .find(item => item.proposalId === proposalId);
    const branch: string = snapshot.status.activeBranchId;
    const patch: WorkspaceProposalPatch = patches[0];
    const material: NovelMaterial | undefined = patches.length === 1 && patch.operation === 'write'
      ? snapshot.project.materials.find(item => patch.path === `branches/${branch}/setting/${item.kind}/${item.id}.md`) : undefined;
    if (material !== undefined) {
      const args: NovelProjectToolInput = { material_id: material.id, kind: material.kind,
        title: material.title, content: patch.content ?? '', scope: 'branch' };
      if (existing !== undefined) {
        const prior: NovelProjectToolInput | undefined = existing.operation?.args;
        if (existing.operation?.kind !== 'revise_material' || prior === undefined || prior.material_id !== args.material_id
          || prior.kind !== args.kind || prior.content !== args.content || prior.scope !== 'branch') {
          throw invalidInput(`proposal id 已用于其他资料内容: ${proposalId}`);
        }
        return existing;
      }
      return this.repository.proposeProjectOperation(projectId, 'revise_material', args,
        snapshot.status.cas, proposalId, this.nowMs());
    }
    if (existing !== undefined) {
      if (existing.operation !== undefined || JSON.stringify(existing.patches) !== JSON.stringify(patches)) {
        throw invalidInput(`proposal id 已用于其他 patches: ${proposalId}`);
      }
      return existing;
    }
    return this.createWorkspaceProposal(projectId, patches, proposalId, undefined, snapshot.status.cas);
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
    const live: LiveRun | undefined = this.projectRuns.get(projectId);
    if (live !== undefined) {
      this.interrupt(live.runId);
      await live.donePromise;
      if (live.interruptedSaveError !== undefined) throw providerError(live.interruptedSaveError);
    }
  }

  async delete(projectId: string): Promise<void> {
    await this.stateService.cancelAndWait(projectId);
    await this.cancelContinuityRepair(projectId);
    // Wait for the interrupted run's final checkpoint before deleting its project.
    await this.stopActiveRun(projectId);
    const ghostwriteJobs: DurableGhostwriteJob[] = await this.repository.listGhostwriteJobs(projectId);
    for (const job of ghostwriteJobs) {
      if (job.stage !== 'completed' && job.stage !== 'cancelled') await this.cancelGhostwrite(projectId, job.jobId);
    }
    const polishJobs: DurablePolishJob[] = await this.repository.listPolishJobs(projectId);
    for (const job of polishJobs) {
      if (job.stage !== 'completed' && job.stage !== 'cancelled') await this.cancelPolish(projectId, job.jobId);
    }
    const prefix: string = `${projectId}:`;
    const drivers: Promise<void>[] = [];
    this.drivingGhostwriteJobs.forEach((driver: Promise<void>, key: string): void => {
      if (key.startsWith(prefix)) drivers.push(driver);
    });
    this.drivingPolishJobs.forEach((driver: Promise<void>, key: string): void => {
      if (key.startsWith(prefix)) drivers.push(driver);
    });
    await Promise.all(drivers);
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

  // 收录助手回复进章节,并在后台分析素材建议(不阻塞收录返回)。
  // 建议分析可能耗时数十秒,UI 收录后应立即响应;建议稍后异步刷新。
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

  // ===== 生成 =====
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
    const run = this.repairRuns.get(projectId);
    if (run === undefined) return;
    run.cancelled = true; run.cancelText?.();
    await run.done;
  }
  async repairContinuityIssues(projectId: string, issues: NovelAuditIssue[]): Promise<DurableWorkspaceProposal> {
    if (this.hasActiveProjectDriver(projectId)) throw projectBusy(projectId);
    if (issues.length === 0) throw invalidInput('没有可修复的审校证据');
    let finish: () => void = (): void => {};
    const run: LiveContinuityRepair = { cancelled: false, cancelText: null,
      done: new Promise<void>(resolve => { finish = resolve; }), finish: (): void => finish() };
    this.repairRuns.set(projectId, run);
    try {
      const snapshot = await this.repository.readWorkspaceSnapshot(projectId);
      const prepared = issues.map(issue => {
        const chapter = snapshot.project.chapters.find(item => item.id === issue.chapterId && !item.discarded);
        if (chapter === undefined) throw notFound('chapter', issue.chapterId);
        const frozen = { ...chapter }; const evidence = { ...issue };
        return { frozen, evidence, plan: prepareContinuityRepair(frozen, evidence, snapshot.project.chapters) };
      });
      const sorted = prepared.slice().sort((a, b) => a.frozen.id.localeCompare(b.frozen.id) || a.evidence.start - b.evidence.start);
      for (let index: number = 1; index < sorted.length; index++) {
        if (sorted[index - 1].frozen.id === sorted[index].frozen.id && sorted[index - 1].evidence.end > sorted[index].evidence.start) {
          throw invalidInput('审校修复范围重叠，请逐条选择或重新审校');
        }
      }
      const target = resolveNovelDefaultTarget(snapshot.project.modelPolicy, await this.loadModelDefaults(), 'review');
      await this.modelRunning.validate(target, projectId);
      const repairs: AppliedContinuityRepair[] = [];
      const previews: WorkspaceProposalReview['previews'] = [];
      for (const item of prepared) {
        if (run.cancelled) throw invalidInput('连续性修复已取消');
        const collecting = collectContinuityRepairText(this.modelRunning, {
          runId: `repair:${novelId()}`, projectId, systemPrompt: CONTINUITY_REPAIR_SYSTEM_PROMPT,
          maxOutputTokens: REPAIR_MAX_OUTPUT_TOKENS, modelTarget: target, history: [], toolProfile: 'none',
          operation: { kind: 'turn', userPrompt: item.plan.userPrompt }, checkpoint: async (): Promise<void> => {},
        });
        run.cancelText = collecting.cancel;
        const text = await collecting.result; run.cancelText = null;
        if (run.cancelled) throw invalidInput('连续性修复已取消');
        const repair = parseContinuityRepair(text, item.frozen, item.evidence);
        repairs.push(repair);
        const ordinal = novelChapterOrdinal(item.frozen, snapshot.project.chapters.findIndex(chapter => chapter.id === item.frozen.id) + 1);
        previews.push({ label: `第 ${ordinal} 章 ${item.frozen.title}`, chapterId: item.frozen.id,
          oldText: item.evidence.quote, newText: repair.replacement, sourceDigest: materialSuggestionChapterDigest(item.frozen),
          start: item.evidence.start, end: item.evidence.end });
      }
      const patches: WorkspaceProposalPatch[] = [];
      for (const chapter of snapshot.project.chapters.filter(item => repairs.some(repair => repair.chapterId === item.id))) {
        const ordinal = novelChapterOrdinal(chapter, snapshot.project.chapters.indexOf(chapter) + 1);
        patches.push({ operation: 'write',
          path: `branches/${snapshot.status.activeBranchId}/chapters/${chapterFileName(ordinal, chapter.title)}`,
          content: mergeContinuityRepairs(chapter, repairs.filter(repair => repair.chapterId === chapter.id)) });
      }
      if (run.cancelled) throw invalidInput('连续性修复已取消');
      return await this.createWorkspaceProposal(projectId, patches, novelId(), {
        summary: `连续性修复：${issues.length} 条已取证问题`,
        sourceDigest: prepared.map(item => `${item.frozen.id}:${item.evidence.sourceDigest}`).join('|'), previews,
      }, snapshot.status.cas);
    } finally { this.repairRuns.delete(projectId); run.finish(); }
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
    const runId: string = novelId();
    const emitter: Emitter<NovelRunEvent> = createEmitter<NovelRunEvent>();
    const handle: NovelRun = {
      id: runId,
      subscribe: (cb): () => void => emitter.subscribe(cb),
    };

    if (this.projectRuns.has(projectId) || this.stateService.isRunning(projectId) || this.repairRuns.has(projectId)) {
      const busyMsg: string = projectBusy(projectId).message;
      // 延迟发射,确保调用方先订阅
      setTimeout(() => { emitter.emit({ kind: 'failed', message: busyMsg }); }, 0);
      return handle;
    }

    let doneResolve: () => void = (): void => {};
    const donePromise: Promise<void> = new Promise<void>(res => { doneResolve = res; });
    const live: LiveRun = {
      runKind, runId: runId, projectId: projectId, handle, historyCount: 0,
      userText, startedAt: this.nowMs(), partial: '', interrupted: false,
      branchId: null, messages: [],
      doneResolve: doneResolve, donePromise: donePromise,
    };
    this.projectRuns.set(projectId, live);
    void this.runGeneration(live, emitter, userText, mode, granularity, runKind, sourceChapterId, overrides, expectedCas);
    return handle;
  }

  activeRun(projectId: string): NovelActiveRun | null {
    const live: LiveRun | undefined = this.projectRuns.get(projectId);
    if (live === undefined) return null;
    return {
      runKind: live.runKind,
      run: live.handle,
      historyCount: live.historyCount,
      userText: live.userText,
      startedAt: live.startedAt,
    };
  }

  async readOrdinaryRun(projectId: string): Promise<NovelOrdinaryRunView> {
    const snapshot = await this.repository.readWorkspaceSnapshot(projectId);
    const record = snapshot.project.ordinaryRun ?? null;
    const active: boolean = this.hasActiveProjectDriver(projectId);
    let blockedReason: string = '';
    if (active) blockedReason = '当前项目正在运行';
    else if (record !== null) {
      if (record.branchId !== snapshot.status.activeBranchId) blockedReason = '请切回原生成分支';
      else if (record.status === 'completed' || record.status === 'waiting_user') blockedReason = '本次生成已完成或正在等待交互';
      else if (record.originalRequest.runtimeSnapshot === undefined) blockedReason = '原请求模型配置未能冻结，请重新生成';
      else { try { assertOrdinaryRunTranscript(record, snapshot.project.messages.map(novelMessageUi)); }
        catch (error) { blockedReason = String(error); } }
    }
    return { record, active, blockedReason, retryAllowed: record !== null && blockedReason.length === 0,
      resumeAllowed: record !== null && blockedReason.length === 0 && record.originalRequest.responsesResumeEnabled === true && record.cursor !== null };
  }
  retryOrdinaryRun(projectId: string, recordId: string): NovelRun { return this.startOrdinaryRecovery(projectId, recordId, 'retry'); }
  resumeOrdinaryRun(projectId: string, recordId: string): NovelRun { return this.startOrdinaryRecovery(projectId, recordId, 'resume'); }
  private startOrdinaryRecovery(projectId: string, recordId: string, kind: 'retry' | 'resume'): NovelRun {
    const emitter = createEmitter<NovelRunEvent>();
    const handle: NovelRun = { id: novelId(), subscribe: callback => emitter.subscribe(callback) };
    if (this.hasActiveProjectDriver(projectId)) {
      setTimeout(() => emitter.emit({ kind: 'failed', message: projectBusy(projectId).message }), 0); return handle;
    }
    let doneResolve: () => void = (): void => {};
    const live: LiveRun = { runId: handle.id, projectId, handle, historyCount: 0, userText: '',
      startedAt: this.nowMs(), partial: '', interrupted: false, branchId: null, messages: [],
      donePromise: new Promise<void>(resolve => { doneResolve = resolve; }), doneResolve: (): void => doneResolve() };
    this.projectRuns.set(projectId, live);
    void this.runGeneration(live, emitter, '', 'discuss', null, null, null, undefined, undefined, { id: recordId, kind });
    return handle;
  }

  private async finishOrdinaryRun(live: LiveRun, status: NovelOrdinaryRunStatus, error: string | null): Promise<void> {
    if (live.ordinary === undefined) return;
    await this.commitMutation(live.projectId, 'transcript_checkpoint', (current, branchId) => {
      const owner = current.ordinaryRun;
      if (owner === null || owner === undefined || branchId !== live.branchId || owner.id !== live.ordinary?.id) throw invalidInput('生成任务所属分支或持久记录已变化');
      const currentUi = current.messages.map(novelMessageUi);
      const prefixIds = new Set(owner.transcriptPrefix.map(message => message.id));
      const checkpointMessages = [...owner.originalRequest.history.map(message => currentUi.find(updated => updated.id === message.id) ?? message),
        ...currentUi.filter(message => !prefixIds.has(message.id))];
      return { ...current, ordinaryRun: { ...owner, status, error, checkpointMessages, updatedAt: this.nowMs() } };
    }, `novel:${live.runId}:ordinary:${status}`);
  }

  continueTool(
    projectId: string, toolCallId: string, verdict: NovelToolContinuationVerdict,
  ): NovelRun {
    const runId: string = novelId();
    const emitter: Emitter<NovelRunEvent> = createEmitter<NovelRunEvent>();
    const handle: NovelRun = {
      id: runId,
      subscribe: (cb): () => void => emitter.subscribe(cb),
    };
    if (this.projectRuns.has(projectId) || this.stateService.isRunning(projectId) || this.repairRuns.has(projectId)) {
      const busyMsg: string = projectBusy(projectId).message;
      setTimeout((): void => { emitter.emit({ kind: 'failed', message: busyMsg }); }, 0);
      return handle;
    }
    let doneResolve: () => void = (): void => {};
    const donePromise: Promise<void> = new Promise<void>(res => { doneResolve = res; });
    const live: LiveRun = {
      runId, projectId, handle, historyCount: 0, userText: '', startedAt: this.nowMs(),
      partial: '', interrupted: false, doneResolve, donePromise,
      branchId: null, messages: [],
    };
    this.projectRuns.set(projectId, live);
    void this.runToolContinuation(live, emitter, toolCallId, verdict);
    return handle;
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

  // N2:单章润色(polish 模板 + 哨兵协议)
  async polishChapter(projectId: string, chapterId: string): Promise<NovelRun> {
    const project: NovelProject = await this.findProjectAsync(projectId);
    const chapter: NovelChapter | null = project.chapters.find(c => c.id === chapterId) ?? null;
    if (chapter === null) throw notFound('chapter', chapterId);
    const userText: string = `请润色章节「${chapter.title}」,输出润色后的完整章节全文。`;
    return this.generate(projectId, userText, 'write', 'whole_chapter', 'polish', chapterId);
  }

  // N2:快速开始(空项目kick)
  async quickStart(projectId: string, idea: string): Promise<NovelRun> {
    return this.generate(projectId, idea, 'discuss', null, 'quick_start');
  }

  async quickStartFromSeed(projectId: string, guidance: string = '', coreIdeaOverride: string = ''): Promise<NovelRun> {
    const project: NovelProject = await this.repository.loadProject(projectId);
    return this.quickStart(projectId, novelQuickStartRequestText(project, guidance, coreIdeaOverride));
  }

  // N2:章节废弃标记
  async setChapterDiscarded(projectId: string, chapterId: string, discarded: boolean): Promise<NovelProject> {
    return this.commitMutation(projectId, 'chapter_discard', (p: NovelProject): NovelProject => {
      const idx: number = p.chapters.findIndex(c => c.id === chapterId);
      if (idx < 0) return p;
      const chapters: NovelChapter[] = p.chapters.slice();
      chapters[idx] = { ...chapters[idx], discarded: discarded, updatedAt: Date.now() };
      return { ...p, chapters: chapters, updatedAt: Date.now() };
    });
  }

  // N2:恢复章节版本(restoreChapterVersion mutation:备份当前→回写)
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

  // 归档生成只产出草稿；作者审阅摘要和决定后才持久推进范围。
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

  // N3:恢复最近删除的项目;无删除记录返回 null
  async restorePreviousProject(): Promise<NovelProject | null> {
    return this.repository.restorePrevious();
  }

  interrupt(runId: string): void {
    this.projectRuns.forEach(live => {
      if (live.runId === runId) live.interrupted = true;
    });
    this.modelRunning.cancel(runId);
  }

  private async findProjectAsync(projectId: string): Promise<NovelProject> {
    return this.open(projectId);
  }

  private workspaceToolPath(path: string, activeBranchId: string, directory: boolean): string {
    const clean: string = path.trim().replace(/\\/g, '/').replace(/^\.\//, '');
    if (clean.startsWith('/') || clean.split('/').some((part: string): boolean => part === '..')) {
      throw invalidInput('工作区路径无效');
    }
    const branchRoot: string = `branches/${activeBranchId}/`;
    if (clean.length === 0) return branchRoot;
    const checked: string = clean.startsWith('branches/') ? clean : branchRoot + clean;
    if (!checked.startsWith(branchRoot) || checked.indexOf('/.amber/') >= 0) {
      throw invalidInput('工具只能访问当前分支可见文件');
    }
    if (!directory && checked.endsWith('/')) throw invalidInput('读取路径必须指向文件');
    return checked;
  }

  private findTool(project: NovelProject, toolCallId: string): UIMessagePartTool | null {
    for (let i: number = 0; i < project.messages.length; i++) {
      const parts = novelMessageUi(project.messages[i]).parts;
      for (let p: number = 0; p < parts.length; p++) {
        const part: UIMessagePart = parts[p];
        if (part.type === 'tool' && part.toolCallId === toolCallId) return part;
      }
    }
    return null;
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

  // ===== 内部 =====
  private async runGeneration(
    live: LiveRun, emitter: Emitter<NovelRunEvent>, userText: string,
    mode: NovelChatMode, granularity: NovelGenerationGranularity | null,
    runKind: NovelRunKind | null = null,
    sourceChapterId: string | null = null,
    overrides?: NovelInjectionOverrides, expectedCas?: NovelWorkspaceCas,
    recovery?: { id: string; kind: 'retry' | 'resume' },
  ): Promise<void> {
    const projectId: string = live.projectId;
    try {
      let trimmed: string = userText.trim();
      if (recovery === undefined && trimmed.length === 0) throw invalidInput('请输入内容');
      if (recovery === undefined && trimmed.length > MAX_USER_CHARS) throw invalidInput(`输入超过 ${MAX_USER_CHARS} 字`);

      const snapshot = await this.repository.readWorkspaceSnapshot(projectId);
      const project: NovelProject = snapshot.project;
      const status: NovelWorkspaceStatus = snapshot.status;
      const previous = recovery === undefined ? null : project.ordinaryRun;
      if (recovery !== undefined) {
        if (previous === null || previous === undefined || previous.id !== recovery.id || previous.branchId !== status.activeBranchId) {
          throw invalidInput('原生成任务或所属分支已变化，请重新查看恢复记录');
        }
        assertOrdinaryRunTranscript(previous, project.messages.map(novelMessageUi));
        trimmed = previous.userText; userText = trimmed; mode = previous.mode;
        granularity = previous.granularity; runKind = previous.runKind;
        live.runKind = previous.runKind; live.userText = trimmed; live.candidate = previous.candidate;
      }
      this.assertExpectedSnapshot(status.cas, expectedCas);
      if (overrides?.branchId !== undefined && overrides.branchId !== status.activeBranchId) {
        throw invalidInput('本次资料选择所属分支已切换，请重新预览');
      }
      if (mode === 'write' && recovery === undefined) this.assertWritingContextReady(status);
      live.branchId = status.activeBranchId;
      live.historyCount = project.messages.length;
      let generationPrompt: string = trimmed;
      if (sourceChapterId !== null && (runKind === 'regenerate' || runKind === 'polish')) {
        const source: NovelChapter | undefined = project.chapters.find(chapter => chapter.id === sourceChapterId);
        if (source === undefined) throw notFound('chapter', sourceChapterId);
        // 内部原章取同一 persisted snapshot，不把章节全文当作者输入的 8k 限额。
        generationPrompt += `\n\n# 原章节「${source.title}」\n${source.content}`;
      }
      const gran: NovelGenerationGranularity | null =
        mode === 'write' ? (granularity ?? project.lastGenerationGranularity) : null;
      if (mode === 'write' && recovery === undefined) {
        const kind: NovelCandidateProvenance['kind'] = runKind === 'regenerate' || runKind === 'polish'
          ? runKind : 'write';
        const source: string | null = sourceChapterId ?? (gran === 'continuation'
          ? project.chapters.filter(chapter => !chapter.discarded).slice(-1)[0]?.id ?? null : null);
        live.candidate = makeNovelCandidateProvenance(project, status.activeBranchId, kind, source);
      }
      const sysPrompt: string = previous ? previous.originalRequest.systemPrompt
        : systemPrompt(project, mode, gran, runKind, generationPrompt, overrides);
      const modelTarget = previous ? previous.originalRequest.modelTarget : await this.modelTargetFor(project, runKind);
      let checkpointIndex: number = 0;
      let request: NovelModelRequest = {
        runId: live.runId,
        projectId,
        systemPrompt: sysPrompt,
        context: previous ? previous.originalRequest.context : buildNovelContext(project, mode, gran, runKind, generationPrompt, overrides),
        maxOutputTokens: null,
        modelTarget,
        toolProfile: mode === 'write' || runKind === 'quick_start' ? 'none' : 'all',
        history: project.messages.map(novelMessageUi),
        operation: { kind: 'turn', userPrompt: generationPrompt },
        checkpoint: async (messages: UIMessage[], cursor): Promise<void> => {
          checkpointIndex += 1;
          const now: number = this.nowMs();
          await this.commitMutation(projectId, 'transcript_checkpoint', (current, branchId): NovelProject => {
            if (live.branchId !== branchId) {
              throw invalidInput('生成期间当前分支已切换，结果未写入另一分支');
            }
            const owner = current.ordinaryRun;
            if (live.ordinary === undefined || owner?.id !== live.ordinary.id || owner.status !== 'running') {
              throw invalidInput('生成任务已终止或已被替换，迟到内容未保存');
            }
            const progress: NovelOrdinaryRun = { ...owner, checkpointMessages: messages,
              cursor: ordinaryRunCursor(owner, cursor), updatedAt: now };
            live.ordinary = progress;
            return {
              ...current,
              ordinaryRun: progress,
              messages: transcriptFromUi(current, ordinaryRunMessages(progress), mode, gran, now, live.candidate, live.runKind),
              lastGenerationGranularity: gran ?? current.lastGenerationGranularity,
              updatedAt: now,
            };
          }, `novel:${live.runId}:transcript:${checkpointIndex}`);
        },
      };
      if (previous !== null && previous !== undefined && recovery !== undefined) {
        request = { ...ordinaryRecoveryRequest(previous, recovery.kind), runId: live.runId, projectId, checkpoint: request.checkpoint };
      }
      let originalRequest = previous !== null && previous !== undefined ? previous.originalRequest : freezeNovelOrdinaryRequest(request);
      const now: number = this.nowMs();
      const ordinary: NovelOrdinaryRun = { version: 1, id: live.runId, branchId: status.activeBranchId,
        mode, granularity: gran, runKind, userText: trimmed, candidate: live.candidate, originalRequest,
        transcriptPrefix: recovery?.kind === 'resume' && previous ? previous.transcriptPrefix : project.messages.map(novelMessageUi),
        checkpointMessages: recovery?.kind === 'resume' && previous ? previous.checkpointMessages : originalRequest.history,
        cursor: recovery?.kind === 'resume' && previous ? previous.cursor : null,
        status: 'running', error: null, startedAt: now, updatedAt: now };
      await this.commitMutation(projectId, 'transcript_checkpoint', current => ({ ...current, ordinaryRun: ordinary }),
        `novel:${live.runId}:request`, status.cas);
      live.ordinary = ordinary;
      live.historyCount = ordinaryRunHistoryCount(ordinary);
      if (previous === null && this.modelRunning.prepareOrdinaryRequest !== undefined) {
        const prepared = await this.modelRunning.prepareOrdinaryRequest(request);
        request = { ...request, runtimeSnapshot: prepared.runtimeSnapshot,
          modelTarget: { kind: 'fixed', providerId: prepared.runtimeSnapshot.providerId, modelId: prepared.runtimeSnapshot.modelId },
          responsesResumeEnabled: prepared.responsesResumeSupported && mode === 'write' && runKind !== 'quick_start' };
        originalRequest = freezeNovelOrdinaryRequest(request);
        const preparedRun: NovelOrdinaryRun = { ...ordinary, originalRequest };
        await this.commitMutation(projectId, 'transcript_checkpoint', (current, branchId) => {
          if (branchId !== live.branchId || current.ordinaryRun?.id !== live.runId) throw invalidInput('模型配置准备期间生成来源已变化');
          return { ...current, ordinaryRun: preparedRun };
        }, `novel:${live.runId}:prepared`);
        live.ordinary = preparedRun;
      }
      await this.modelRunning.validate(request.modelTarget, projectId);
      emitter.emit({ kind: 'started' });
      const result: CollectedModelResult = await this.collectWithEvents(live, emitter, request);
      if (live.interrupted) throw providerError('用户停止生成');
      if (result.terminal === 'waiting_user') {
        await this.finishOrdinaryRun(live, 'waiting_user', null);
        emitter.emit({ kind: 'waiting_user' });
        return;
      }
      const historyIds: Set<string> = new Set(originalRequest.history.map(message => message.id));
      if (request.operation.kind === 'tool_continuation') {
        const toolCallId = request.operation.toolCallId;
        originalRequest.history.filter(message => message.parts.some(part => part.type === 'tool' && part.toolCallId === toolCallId))
          .forEach(message => historyIds.delete(message.id));
      }
      const newMessages: UIMessage[] = result.messages.filter(message => !historyIds.has(message.id));
      if (latestAssistantText(newMessages).trim().length === 0) {
        throw providerError('模型没有返回内容');
      }
      if (runKind === 'polish' && (!hasPolishSentinel(result.text) || stripPolishSentinel(result.text).length === 0)) {
        throw providerError('润色输出未完整结束，请重新润色，原章未修改');
      }
      let saved: NovelProject = await this.repository.loadProject(projectId);
      if (live.candidate !== undefined) {
        const candidate: NovelCandidateProvenance = { ...live.candidate, complete: true };
        saved = await this.commitMutation(projectId, 'transcript_checkpoint', (current, branchId) => {
          if (branchId !== candidate.branchId) throw invalidInput('候选来自另一分支');
          return {
            ...current,
            messages: current.messages.map(message => {
              if (message.role !== 'assistant' || !newMessages.some(ui => ui.id === message.uiMessage.id)) return message;
              const completed = makeNovelMessage({ ...message, candidate, interrupted: false });
              return runKind === 'polish'
                ? withNovelMessageText(completed, stripPolishSentinel(novelMessageText(message))) : completed;
            }),
          };
        }, `novel:${live.runId}:complete`);
      }
      const last: NovelMessage | undefined = saved.messages.slice().reverse().find(
        (message: NovelMessage): boolean => message.role === 'assistant' &&
          newMessages.some((ui: UIMessage): boolean => ui.id === novelMessageUi(message).id));
      if (last === undefined) throw providerError('模型结果未完成持久化');
      if (runKind === 'quick_start') {
        const sourceText: string = novelMessageText(last);
        await this.commitMutation(projectId, 'proposal_create', (current, branchId): NovelProject => {
          if (branchId !== live.branchId) throw invalidInput('快速开始期间分支已切换，提案未写入另一分支');
          return stageNovelQuickStart(current, last.id, sourceText, this.nowMs());
        }, `novel:${live.runId}:quick-start`);
      }
      await this.finishOrdinaryRun(live, 'completed', null);
      emitter.emit({ kind: 'completed', message: last });
    } catch (e) {
      if (live.interrupted) {
        // Adapter 的 checkpoint 已保存 canonical partial；这里只标记当前助手消息为 interrupted。
        try {
          await this.persistInterrupted(live, live.ordinary?.originalRequest.operation.kind === 'tool_continuation' ? '' : userText, mode, granularity);
          await this.finishOrdinaryRun(live, 'interrupted', '用户停止生成');
          emitter.emit({ kind: 'interrupted', partial: live.partial });
        } catch (saveError) {
          live.interruptedSaveError = `停止后内容未保存：${String(saveError)}。请复制可见内容后重试。`;
          emitter.emit({
            kind: 'failed', message: live.interruptedSaveError,
            unsavedMessages: live.messages.slice(),
          });
        }
      } else {
        const msg: string = isNovelError(e) ? e.message : String(e);
        try { await this.finishOrdinaryRun(live, 'failed', msg); }
        catch (saveError) { emitter.emit({ kind: 'failed', message: `${msg}；恢复记录未保存：${String(saveError)}`, unsavedMessages: live.messages.slice() }); return; }
        emitter.emit({ kind: 'failed', message: msg });
      }
    } finally {
      this.projectRuns.delete(projectId);
      live.doneResolve();
    }
  }

  private async runToolContinuation(
    live: LiveRun, emitter: Emitter<NovelRunEvent>, toolCallId: string,
    verdict: NovelToolContinuationVerdict,
  ): Promise<void> {
    const projectId: string = live.projectId;
    try {
      const snapshot = await this.repository.readWorkspaceSnapshot(projectId);
      const project: NovelProject = snapshot.project;
      const status: NovelWorkspaceStatus = snapshot.status;
      live.branchId = status.activeBranchId;
      live.historyCount = project.messages.length;
      const tool: UIMessagePartTool | null = this.findTool(project, toolCallId);
      if (tool === null) throw notFound('tool_call', toolCallId);
      const source: NovelMessage | undefined = project.messages.find(
        (message: NovelMessage): boolean => novelMessageUi(message).parts.some(
          (part): boolean => part.type === 'tool' && part.toolCallId === toolCallId));
      if (source === undefined) throw notFound('tool_message', toolCallId);
      const waiting = project.ordinaryRun?.status === 'waiting_user'
        && project.ordinaryRun.branchId === status.activeBranchId
        && project.ordinaryRun.checkpointMessages.some(message => message.parts.some(part => part.type === 'tool' && part.toolCallId === toolCallId))
        ? project.ordinaryRun : null;
      const original = waiting?.originalRequest;
      const modelTarget = original?.modelTarget ?? await this.resolvedModelTarget(project, 'writing');
      await this.modelRunning.validate(modelTarget, projectId);
      if (tool.toolName === 'novel_workspace_write') {
        await this.prepareWorkspaceWriteTool(projectId, tool, verdict);
      }
      emitter.emit({ kind: 'started' });
      let checkpointIndex: number = 0;
      const granularity: NovelGenerationGranularity | null = source.granularity;
      const request: NovelModelRequest = {
        runId: live.runId,
        projectId,
        systemPrompt: original?.systemPrompt ?? systemPrompt(project, source.mode, granularity, null),
        context: original?.context ?? buildNovelContext(project, source.mode, granularity, null),
        maxOutputTokens: original?.maxOutputTokens ?? null,
        modelTarget,
        runtimeSnapshot: original?.runtimeSnapshot,
        toolProfile: original?.toolProfile,
        history: waiting?.checkpointMessages ?? project.messages.map(novelMessageUi),
        operation: { kind: 'tool_continuation', toolCallId, verdict },
        checkpoint: async (messages: UIMessage[]): Promise<void> => {
          checkpointIndex += 1;
          const now: number = this.nowMs();
          try {
            await this.commitMutation(projectId, 'transcript_checkpoint', (current, branchId): NovelProject => {
              if (live.branchId !== branchId) throw invalidInput('工具续接期间当前分支已切换');
              if (waiting !== null && current.ordinaryRun?.id !== live.ordinary?.id) throw invalidInput('原工具生成任务已变化');
              const owner = waiting === null ? null : current.ordinaryRun;
              if (owner !== null && owner !== undefined && owner.status !== 'running') throw invalidInput('工具生成任务已终止，迟到内容未保存');
              const progress = owner === null || owner === undefined ? null : {
                ...owner, checkpointMessages: messages, updatedAt: now,
                originalRequest: { ...owner.originalRequest, history: ordinaryContinuationHistory(owner.originalRequest.history, messages) },
              };
              if (progress !== null) live.ordinary = progress;
              return {
                ...current,
                ordinaryRun: progress ?? current.ordinaryRun,
                messages: transcriptFromUi(current, progress === null ? messages : ordinaryRunMessages(progress, messages), source.mode, granularity, now),
                updatedAt: now,
              };
            }, `novel:${live.runId}:transcript:${checkpointIndex}`);
          } catch (saveError) {
            if (live.interrupted) {
              live.interruptedSaveError = `停止后内容未保存：${String(saveError)}。请复制可见内容后重试。`;
            }
            throw saveError;
          }
        },
      };
      if (waiting !== null) {
        const now = this.nowMs();
        const continuation: NovelOrdinaryRun = { ...waiting, id: live.runId,
          originalRequest: freezeNovelOrdinaryRequest(request), transcriptPrefix: project.messages.map(novelMessageUi),
          checkpointMessages: request.history, cursor: null, status: 'running', error: null, startedAt: now, updatedAt: now };
        await this.commitMutation(projectId, 'transcript_checkpoint', (current, branchId) => {
          if (branchId !== live.branchId || current.ordinaryRun?.id !== waiting.id) throw invalidInput('原工具生成任务已变化');
          return { ...current, ordinaryRun: continuation };
        }, `novel:${live.runId}:tool-request`);
        live.ordinary = continuation;
        live.historyCount = ordinaryRunHistoryCount(continuation);
      }
      const result: CollectedModelResult = await this.collectWithEvents(live, emitter, request);
      if (tool.toolName === 'novel_workspace_write' && verdict.kind === 'denied') {
        await this.resolvePreparedWorkspaceWrite(projectId, tool, false);
      }
      if (result.terminal === 'waiting_user') {
        await this.finishOrdinaryRun(live, 'waiting_user', null);
        emitter.emit({ kind: 'waiting_user' });
        return;
      }
      const saved: NovelProject = await this.repository.loadProject(projectId);
      const last: NovelMessage | undefined = saved.messages.slice().reverse().find(
        (message: NovelMessage): boolean => message.role === 'assistant' &&
          novelMessageText(message).trim().length > 0);
      if (last === undefined) throw providerError('模型没有返回内容');
      await this.finishOrdinaryRun(live, 'completed', null);
      emitter.emit({ kind: 'completed', message: last });
    } catch (error) {
      const message: string = isNovelError(error) ? error.message : String(error);
      try { await this.finishOrdinaryRun(live, live.interrupted ? 'interrupted' : 'failed', message); }
      catch (saveError) { live.interruptedSaveError = `停止后内容未保存：${String(saveError)}。请复制可见内容后重试。`; }
      if (live.interruptedSaveError !== undefined) {
        emitter.emit({ kind: 'failed', message: live.interruptedSaveError, unsavedMessages: live.messages.slice() });
      } else emitter.emit({ kind: 'failed', message });
    } finally {
      this.projectRuns.delete(projectId);
      live.doneResolve();
    }
  }

  private async prepareWorkspaceWriteTool(
    projectId: string, tool: UIMessagePartTool,
    verdict: NovelToolContinuationVerdict,
  ): Promise<void> {
    if (verdict.kind === 'answered') throw invalidInput('write 工具不接受回答');
    let raw: RawWorkspaceWriteInput;
    try {
      raw = JSON.parse(tool.input) as typeof raw;
    } catch {
      throw invalidInput('write 工具参数不是有效 JSON');
    }
    const proposalId: string = typeof raw.proposal_id === 'string' ? raw.proposal_id.trim() : '';
    if (proposalId.length === 0 || !Array.isArray(raw.patches) || raw.patches.length === 0) {
      throw invalidInput('write 工具缺少 proposal_id 或 patches');
    }
    const existing: DurableWorkspaceProposal = await this.stageWorkspaceWriteProposal(projectId, proposalId, raw.patches);
    if ((existing.status === 'accepted' && verdict.kind === 'denied') ||
      (existing.status === 'rejected' && verdict.kind === 'approved')) {
      throw invalidInput(`proposal 已按相反决定结案: ${proposalId}`);
    }
  }

  private async normalizeWorkspaceProposalPatches(
    projectId: string, rawPatches: RawWorkspaceWritePatch[],
  ): Promise<WorkspaceProposalPatch[]> {
    if (!Array.isArray(rawPatches) || rawPatches.length === 0) {
      throw invalidInput('write 工具缺少 patches');
    }
    const status: NovelWorkspaceStatus = await this.repository.workspaceStatus(projectId);
    return rawPatches.map((patch: RawWorkspaceWritePatch): WorkspaceProposalPatch => {
      if (patch.operation !== 'write' && patch.operation !== 'delete') {
        throw invalidInput('write patch operation 必须是 write 或 delete');
      }
      if (typeof patch.path !== 'string' || patch.path.trim().length === 0) {
        throw invalidInput('write patch path 必须是非空字符串');
      }
      if (patch.operation === 'write' && typeof patch.content !== 'string') {
        throw invalidInput('write patch content 必须是字符串');
      }
      if (patch.operation === 'delete' && patch.content !== null) {
        throw invalidInput('delete patch content 必须是 null');
      }
      return {
        operation: patch.operation,
        path: this.workspaceToolPath(patch.path, status.activeBranchId, false),
        content: patch.operation === 'delete' ? null : patch.content as string,
      };
    });
  }

  private proposalIdFromTool(tool: UIMessagePartTool): string | null {
    try {
      const raw = JSON.parse(tool.input) as { proposal_id?: unknown };
      return typeof raw.proposal_id === 'string' && raw.proposal_id.trim().length > 0
        ? raw.proposal_id.trim() : null;
    } catch {
      return null;
    }
  }

  private async resolvePreparedWorkspaceWrite(
    projectId: string, tool: UIMessagePartTool, accept: boolean,
  ): Promise<void> {
    let raw: RawWorkspaceWriteInput;
    try {
      raw = JSON.parse(tool.input) as RawWorkspaceWriteInput;
    } catch {
      throw invalidInput('write 工具参数不是有效 JSON');
    }
    const proposalId: string = typeof raw.proposal_id === 'string' ? raw.proposal_id.trim() : '';
    if (proposalId.length === 0 || !Array.isArray(raw.patches)) {
      throw invalidInput('write 工具缺少 proposal_id 或 patches');
    }
    await this.stageWorkspaceWriteProposal(projectId, proposalId, raw.patches);
    await this.resolveWorkspaceProposal(
      projectId, proposalId, accept,
      `novel:${projectId}:proposal:${proposalId}:${accept ? 'approve' : 'deny'}`);
  }

  private async reconcileDeniedWorkspaceTools(
    projectId: string, project: NovelProject,
  ): Promise<boolean> {
    const proposals: DurableWorkspaceProposal[] = await this.repository.workspaceProposals(projectId);
    const existing: Map<string, DurableWorkspaceProposal> = new Map(proposals.map(
      (proposal: DurableWorkspaceProposal): [string, DurableWorkspaceProposal] =>
        [proposal.proposalId, proposal]));
    let changed: boolean = false;
    for (let i: number = 0; i < project.messages.length; i++) {
      const parts: UIMessagePart[] = novelMessageUi(project.messages[i]).parts;
      for (let p: number = 0; p < parts.length; p++) {
        const part: UIMessagePart = parts[p];
        if (part.type !== 'tool' || part.toolName !== 'novel_workspace_write' ||
          part.approvalState.type !== 'denied') continue;
        const proposalId: string | null = this.proposalIdFromTool(part);
        if (proposalId === null) continue;
        const proposal: DurableWorkspaceProposal | undefined = existing.get(proposalId);
        if (proposal?.status === 'rejected') continue;
        if (proposal?.status === 'accepted') {
          throw invalidInput(`proposal 已按相反决定结案: ${proposalId}`);
        }
        await this.resolvePreparedWorkspaceWrite(projectId, part, false);
        changed = true;
      }
    }
    return changed;
  }

  async validateGhostwriteModels(projectId: string, loadedProject: NovelProject | null = null): Promise<void> {
    const project: NovelProject = loadedProject ?? await this.repository.loadProject(projectId);
    const defaults: NovelModelDefaults = await this.loadModelDefaults();
    const writingTarget = resolveNovelDefaultTarget(project.modelPolicy, defaults, 'writing');
    const reviewTarget = resolveNovelDefaultTarget(project.modelPolicy, defaults, 'review');
    const stateTarget = resolveNovelDefaultTarget(project.modelPolicy, defaults, 'stateSync');
    await this.modelRunning.validate(writingTarget, projectId);
    if (JSON.stringify(reviewTarget) !== JSON.stringify(writingTarget)) {
      await this.modelRunning.validate(reviewTarget, projectId);
    }
    if (JSON.stringify(stateTarget) !== JSON.stringify(reviewTarget) &&
      JSON.stringify(stateTarget) !== JSON.stringify(writingTarget)) {
      await this.modelRunning.validate(stateTarget, projectId);
    }
  }

  private async ensureGhostwriteClaim(
    projectId: string, job: DurableGhostwriteJob,
  ): Promise<GhostwriteClaimRef> {
    const now: number = this.nowMs();
    if (job.claim !== null && now < job.claim.leaseUntil) {
      return { token: job.claim.token, epoch: job.claim.epoch };
    }
    const claimed: DurableGhostwriteJob = await this.repository.claimGhostwriteJob(
      projectId, job.jobId, novelId(), now, GHOSTWRITE_LEASE_MS);
    if (claimed.claim === null) throw invalidInput('ghostwrite claim 未建立');
    return { token: claimed.claim.token, epoch: claimed.claim.epoch };
  }

  private canDriveGhostwriteStage(stage: GhostwriteStage): boolean {
    return stage !== 'paused' && stage !== 'waiting_user' && stage !== 'failed' &&
      stage !== 'cancelled' && stage !== 'completed';
  }

  private launchGhostwriteDriver(projectId: string, jobId: string): void {
    const key: string = `${projectId}:${jobId}`;
    if (this.drivingGhostwriteJobs.has(key)) return;
    const ownerToken: string = novelId();
    const driving: Promise<void> = this.ghostwriteRunner.run(projectId, jobId, ownerToken, GHOSTWRITE_LEASE_MS)
      .catch((): void => {
        // runner 已将可归属错误写回 durable failed；owner 被暂停/取消抢占时保留新 durable 状态。
      })
      .then((): void => {})
      .finally((): void => { this.drivingGhostwriteJobs.delete(key); });
    this.drivingGhostwriteJobs.set(key, driving);
  }

  private async recoverGhostwriteDriver(projectId: string): Promise<void> {
    const job: DurableGhostwriteJob | null = await this.ghostwriteJob(projectId);
    if (job === null || !this.canDriveGhostwriteStage(job.stage)) return;
    const key: string = `${projectId}:${job.jobId}`;
    if (this.drivingGhostwriteJobs.has(key)) return;
    const now: number = this.nowMs();
    if (job.claim !== null && now < job.claim.leaseUntil) {
      const staleClaim: GhostwriteClaimRef = { token: job.claim.token, epoch: job.claim.epoch };
      await this.repository.pauseGhostwriteJob(projectId, job.jobId, staleClaim, now);
      await this.repository.resumeGhostwriteJob(projectId, job.jobId, now);
    }
    this.launchGhostwriteDriver(projectId, job.jobId);
  }

  private async ensurePolishClaim(
    projectId: string, job: DurablePolishJob,
  ): Promise<GhostwriteClaimRef> {
    const now: number = this.nowMs();
    if (job.claim !== null && now < job.claim.leaseUntil) {
      return { token: job.claim.token, epoch: job.claim.epoch };
    }
    const claimed: DurablePolishJob = await this.repository.claimPolishJob(
      projectId, job.jobId, novelId(), now, POLISH_LEASE_MS);
    if (claimed.claim === null) throw invalidInput('polish claim 未建立');
    return { token: claimed.claim.token, epoch: claimed.claim.epoch };
  }

  private canDrivePolishStage(stage: PolishStage): boolean {
    return stage !== 'waiting_system' && stage !== 'paused' && stage !== 'failed' &&
      stage !== 'cancelled' && stage !== 'completed';
  }

  private launchPolishDriver(
    projectId: string, jobId: string, maxChapters: number | null,
  ): void {
    const key: string = `${projectId}:${jobId}`;
    if (this.drivingPolishJobs.has(key)) return;
    const driving: Promise<void> = this.polishRunner.run(projectId, jobId, novelId(), POLISH_LEASE_MS, maxChapters)
      .then((job: DurablePolishJob): Promise<void> =>
        this.syncPolishEffects(job, job.stage === 'waiting_system'))
      .catch((): void => {
        // runner owns durable failure; a stale foreground owner must not overwrite a newer state.
      })
      .finally((): void => { this.drivingPolishJobs.delete(key); });
    this.drivingPolishJobs.set(key, driving);
  }

  private async recoverPolishDriver(projectId: string): Promise<void> {
    const job: DurablePolishJob | null = await this.polishJob(projectId);
    if (job === null) return;
    if (job.stage === 'waiting_system') {
      await this.syncPolishEffects(job, true);
      return;
    }
    if (!this.canDrivePolishStage(job.stage)) {
      await this.syncPolishEffects(job, false);
      return;
    }
    const key: string = `${projectId}:${job.jobId}`;
    if (this.drivingPolishJobs.has(key)) return;
    const now: number = this.nowMs();
    if (job.claim !== null && now < job.claim.leaseUntil) {
      const staleClaim: GhostwriteClaimRef = { token: job.claim.token, epoch: job.claim.epoch };
      await this.repository.pausePolishJob(projectId, job.jobId, staleClaim, now);
      await this.repository.resumePolishJob(projectId, job.jobId, now);
    }
    this.launchPolishDriver(projectId, job.jobId, null);
  }

  private async syncPolishEffects(job: DurablePolishJob, schedule: boolean): Promise<void> {
    if (this.polishEffects === null) return;
    await this.polishEffects.notify(job);
    if (schedule) await this.polishEffects.schedule(job);
    else await this.polishEffects.cancel(job);
  }

  // 订阅模型事件,镜像到 emitter 并累积 partial;completed 返回全文,failed/超时/超限 拒绝。
  private collectWithEvents(
    live: LiveRun, emitter: Emitter<NovelRunEvent>, request: NovelModelRequest,
  ): Promise<CollectedModelResult> {
    return new Promise<CollectedModelResult>((resolve, reject) => {
      let buffer: string = '';
      let messages: UIMessage[] = request.history.slice();
      const historyIds: Set<string> = new Set((request.operation.kind === 'resume_response'
        ? live.ordinary?.originalRequest.history ?? request.history : request.history).map(message => message.id));
      if (request.operation.kind === 'tool_continuation') {
        const toolCallId = request.operation.toolCallId;
        request.history.filter(message => message.parts.some(part => part.type === 'tool' && part.toolCallId === toolCallId))
          .forEach(message => historyIds.delete(message.id));
      }
      let settled: boolean = false;
      let idleTimer: ReturnType<typeof setTimeout>;

      // 先置 noop 再 start:适配器同步抛错时 idle timer 触发 finish 不踩 TDZ
      let unsub: () => void = (): void => {};
      const finish = (fn: () => void): void => {
        if (settled) return;
        settled = true;
        clearTimeout(idleTimer);
        unsub();
        fn();
      };

      const resetIdle = (): void => {
        clearTimeout(idleTimer);
        idleTimer = setTimeout(() => {
          finish(() => {
            this.modelRunning.cancel(request.runId);
            reject(providerError('模型长时间没有响应'));
          });
        }, this.idleTimeoutMs);
      };

      resetIdle();
      if (live.interrupted) {
        finish(() => { reject(providerError('用户停止生成')); });
        return;
      }
      // start/subscribe 同步抛错(适配器配置/资源分配失败):走 failed 收口并清 timer
      let stream: NovelModelStream;
      try {
        stream = this.modelRunning.start(request);
      } catch (e) {
        finish(() => { reject(providerError(`模型启动失败:${String(e)}`)); });
        return;
      }
      try {
        unsub = stream.subscribe((evt: NovelModelEvent): void => {
        if (settled) return;
        resetIdle();
        if (evt.kind === 'status') {
          emitter.emit({ kind: 'status', text: evt.text });
        } else if (evt.kind === 'snapshot') {
          const runMessages: UIMessage[] = evt.messages.filter(message => !historyIds.has(message.id));
          const snapshotText: string = latestAssistantText(runMessages);
          if (snapshotText.length > MAX_OUTPUT_CHARS) {
            finish(() => {
              this.modelRunning.cancel(request.runId);
              reject(outputTooLarge(MAX_OUTPUT_CHARS));
            });
            return;
          }
          buffer = snapshotText;
          live.partial = snapshotText;
          live.messages = runMessages;
          messages = evt.messages.slice();
          emitter.emit({
            kind: 'snapshot',
            messages: live.ordinary === undefined ? evt.messages.slice() : ordinaryRunMessages(live.ordinary, evt.messages),
            generationActive: evt.generationActive,
            textDeltasLive: evt.textDeltasLive,
            transport: evt.transport,
          });
        } else if (evt.kind === 'completed') {
          finish(() => { resolve({ terminal: 'completed', messages, text: buffer }); });
        } else if (evt.kind === 'waiting_user') {
          finish(() => { resolve({ terminal: 'waiting_user', messages, text: buffer }); });
        } else if (evt.kind === 'failed') {
          finish(() => { reject(providerError(evt.message)); });
        }
        });
        if (settled) {
          // subscribe 同步回放终结事件(合法行为):赋值真实退订后立即调用,
          // 否则 emitter 持有不再触发的订阅闭包
          unsub();
        }
      } catch (e) {
        finish(() => { reject(providerError(`模型订阅失败:${String(e)}`)); });
        return;
      }
    });
  }

  // 中断时 checkpoint 通常已保存 user/partial；这里只给当前 partial 加终止标记。
  // 若 provider 在首个 checkpoint 前就失败，才补最小 user/partial，避免重复消息。
  private async persistInterrupted(
    live: LiveRun, userText: string, mode: NovelChatMode,
    granularity: NovelGenerationGranularity | null,
  ): Promise<void> {
    const partial: string = live.partial;
    const trimmed: string = userText.trim();
    if (partial.trim().length === 0 && trimmed.length === 0) return;
    const now: number = this.nowMs();
    await this.commitMutation(live.projectId, 'transcript_checkpoint', (p, branchId) => {
      if (live.branchId !== null && live.branchId !== branchId) {
        throw invalidInput('生成分支已切换，中断内容未写入另一分支');
      }
      const gran: NovelGenerationGranularity | null =
        mode === 'write' ? (granularity ?? p.lastGenerationGranularity) : null;
      let messages: NovelMessage[] = p.messages.slice();
      const hasUser: boolean = messages.slice(live.historyCount).some(
        (message: NovelMessage): boolean => message.role === 'user');
      if (!hasUser && trimmed.length > 0) {
        messages = messages.concat([
          makeNovelMessage({ role: 'user', mode, content: trimmed, createdAt: now }),
        ]);
      }
      if (partial.trim().length > 0) {
        let marked: boolean = false;
        for (let i: number = messages.length - 1; i >= live.historyCount; i--) {
          const message: NovelMessage = messages[i];
          if (message.role !== 'assistant' || novelMessageText(message) !== partial) continue;
          const ui: UIMessage = novelMessageUi(message);
          const annotated: UIMessage = {
            ...ui,
            annotations: ui.annotations.concat([
              { type: 'generation_interrupted', reason: '用户停止生成' },
            ]),
            finishedAt: new Date(now).toISOString(),
          };
          messages[i] = makeNovelMessage({
            id: message.id, role: message.role, mode: message.mode, uiMessage: annotated,
            createdAt: message.createdAt, granularity: message.granularity,
            collectedChapterId: message.collectedChapterId, interrupted: true,
            candidate: message.candidate ?? live.candidate,
            runKind: message.runKind ?? live.runKind,
          });
          marked = true;
          break;
        }
        if (!marked) {
          messages = messages.concat([makeNovelMessage({
            role: 'assistant', mode, content: partial, createdAt: now,
            granularity: gran, interrupted: true,
            candidate: live.candidate,
            runKind: live.runKind,
          })]);
        }
      }
      return { ...p, messages, updatedAt: now };
    }, `novel:${live.runId}:interrupt`);
  }
}

export const createNovelCreation = (deps: NovelCreationDeps): NovelCreation => {
  return new NovelCreation(deps);
};
