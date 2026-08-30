// novel/creation — 小说创作编排器(移植自 Android DefaultNovelCreation)
// CQRS 风格:异步查询/变更方法 + generate(流式 NovelRun) + interrupt。
// 每项目单运行(busy 拒绝);累积 partial、超 MAX_OUTPUT_CHARS 取消、idle 超时失败;
// 中断保留 partial(interrupted=true);收录先存章节再分析素材建议。纯逻辑,mock 模型可测。

import {
  makeNovelMessage, novelId, makeNovelChapterVersion, makeNovelChapter,
} from './models.ts';
import type { NovelRunKind } from './prompt_catalog.ts';
import type {
  NovelProject, NovelMessage, NovelChapter, NovelMaterial, NovelMaterialSuggestion,
  NovelChatMode, NovelGenerationGranularity, NovelMaterialKind, NovelCollectionTarget, NovelChapterVersion,
  NovelModelPolicy, NovelBranchSettings,
} from './models.ts';
import {
  createProject, renameProject, setProjectModelPolicy, setProjectBranchSettings,
  appendMessage, saveChapter, deleteChapter,
  upsertMaterial, deleteMaterial, collect, replacePendingSuggestions, resolveSuggestion,
  addSettingProposals, resolveSettingProposal as resolveSettingProposalMutation, saveChapterVersion,
} from './mutations.ts';
import {
  invalidInput, notFound, projectBusy, providerError, outputTooLarge, isNovelError,
} from './error.ts';
import { systemPrompt, MAX_USER_CHARS, MAX_OUTPUT_CHARS } from './context_builder.ts';
import { analyzeChapterSuggestions } from './suggestion_engine.ts';
import type { NovelModelRunning, NovelModelRequest, NovelModelEvent , NovelModelStream } from './model_running.ts';
import type { NovelToolContinuationVerdict } from './model_running.ts';
import type {
  NovelProjectRepository, NovelWorkspaceMutationKind, NovelWorkspaceStatus,
} from './repository.ts';
import { parseSettingProposals } from './discussion_structured.ts';
import type { NovelSettingProposalDraft, NovelSettingProposalsParsed } from './discussion_structured.ts';
import { latestAssistantText } from '../agent/message.ts';
import type {
  StreamTransportState, UIMessage, UIMessagePart, UIMessagePartTool,
} from '../agent/message.ts';
import type { DurableWorkspaceProposal, WorkspaceProposalPatch } from './workspace_history.ts';
import { novelMessageText, novelMessageUi } from './transcript.ts';
import { decodeNovelWorkspaceUtf8 } from './workspace_exchange.ts';
import type { NovelWorkspaceArchiveFile } from './workspace_exchange.ts';
import type {
  DurableGhostwriteJob, GhostwriteClaimRef, GhostwriteStage,
} from './ghostwrite.ts';
import { createGhostwriteRunner } from './ghostwrite_runner.ts';
import type { GhostwriteRunner } from './ghostwrite_runner.ts';

// ===== 运行事件 / 句柄 =====
export type NovelRunEvent =
  | { kind: 'started' }
  | { kind: 'status'; text: string }
  | {
    kind: 'snapshot'; messages: UIMessage[]; generationActive: boolean;
    textDeltasLive: boolean; transport: StreamTransportState;
  }
  | { kind: 'delta'; text: string }
  | { kind: 'replace'; text: string }
  | { kind: 'waiting_user' }
  | { kind: 'completed'; message: NovelMessage }
  | { kind: 'interrupted'; partial: string }
  | { kind: 'failed'; message: string };

export interface NovelRun {
  id: string;
  subscribe(cb: (event: NovelRunEvent) => void): () => void;
}

export interface NovelCollectionResult {
  chapter: NovelChapter;
  suggestionCount: number;
  suggestionWarning: string | null;
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

export type NovelBatchPolishResultStatus = 'succeeded' | 'failed' | 'cancelled';

export interface NovelBatchPolishResult {
  chapterId: string;
  status: NovelBatchPolishResultStatus;
  messageId: string | null;
  error: string | null;
}

export type NovelBatchPolishEvent =
  | { kind: 'progress'; completed: number; total: number; chapterId: string }
  | { kind: 'result'; result: NovelBatchPolishResult }
  | { kind: 'completed'; results: NovelBatchPolishResult[] }
  | { kind: 'cancelled'; results: NovelBatchPolishResult[] };

export interface NovelBatchPolishRun {
  id: string;
  subscribe(cb: (event: NovelBatchPolishEvent) => void): () => void;
  cancel(): void;
}

// ===== 依赖 =====
export interface NovelCreationDeps {
  repository: NovelProjectRepository;
  modelRunning: NovelModelRunning;
  nowMs?: () => number;
  idleTimeoutMs?: number;
}

const DEFAULT_IDLE_TIMEOUT_MS: number = 90_000;
const GHOSTWRITE_LEASE_MS: number = 12 * 60 * 60 * 1000;

// 事件发射器:仅缓冲终结事件(completed/interrupted/failed),供晚订阅补齐。
// delta/status/activity 不入 buffer(一次生成可达数十万 delta,全缓冲会 OOM)。
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
    }));
  });
  return next;
};

const createEmitter = <T>(): Emitter<T> => {
  const subs = new Set<(v: T) => void>();
  const terminal: T[] = [];
  return {
    emit(v: T): void {
      if (subs.size > 0) {
        subs.forEach(cb => { cb(v); });
      } else if (isTerminal(v)) {
        terminal.push(v); // 无订阅者时缓存终结事件
      }
    },
    subscribe(cb: (v: T) => void): () => void {
      for (let i = 0; i < terminal.length; i++) cb(terminal[i]);
      subs.add(cb);
      return () => { subs.delete(cb); };
    },
  };
};

interface LiveRun {
  runId: string;
  projectId: string;
  partial: string;
  interrupted: boolean;
  doneResolve: () => void;
  donePromise: Promise<void>;
}

interface LiveBatchPolishRun {
  runId: string;
  projectId: string;
  cancelled: boolean;
  currentRunId: string | null;
}

export class NovelCreation {
  private repository: NovelProjectRepository;
  private modelRunning: NovelModelRunning;
  private ghostwriteRunner: GhostwriteRunner;
  private nowMs: () => number;
  private idleTimeoutMs: number;
  private projectRuns: Map<string, LiveRun> = new Map();
  private batchPolishRuns: Map<string, LiveBatchPolishRun> = new Map();
  private batchPolishProjectRuns: Map<string, NovelBatchPolishRun> = new Map();
  private polishMessageChapterIds: Map<string, string> = new Map();
  // 仅防止同一进程重复驱动；durable job/repository 始终是唯一事实源。
  private drivingGhostwriteJobs: Set<string> = new Set();

  constructor(deps: NovelCreationDeps) {
    this.repository = deps.repository;
    this.modelRunning = deps.modelRunning;
    this.ghostwriteRunner = createGhostwriteRunner({
      repository: deps.repository,
      modelRunning: deps.modelRunning,
      nowMs: deps.nowMs,
      makeId: (prefix: string): string => `${prefix}:${novelId()}`,
    });
    this.nowMs = deps.nowMs ?? ((): number => Date.now());
    this.idleTimeoutMs = deps.idleTimeoutMs ?? DEFAULT_IDLE_TIMEOUT_MS;
  }

  // ===== 查询 / 变更 =====
  listProjects(): Promise<NovelProject[]> {
    return this.repository.listProjects();
  }

  async open(projectId: string): Promise<NovelProject> {
    const project: NovelProject = await this.repository.loadProject(projectId);
    const repaired: boolean = await this.reconcileDeniedWorkspaceTools(projectId, project);
    const opened: NovelProject = repaired ? await this.repository.loadProject(projectId) : project;
    void this.recoverGhostwriteDriver(projectId).catch((): void => {
      // Durable job remains visible as paused/failed; open 本身不因后台接管失败而隐藏项目。
    });
    return opened;
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

  async startGhostwrite(projectId: string, targetChapterCount: number): Promise<DurableGhostwriteJob> {
    const project: NovelProject = await this.repository.loadProject(projectId);
    await this.validateGhostwriteModels(projectId, project);
    const status: NovelWorkspaceStatus = await this.repository.workspaceStatus(projectId);
    const jobId: string = novelId();
    const job: DurableGhostwriteJob = await this.repository.startGhostwriteJob(
      projectId, status.cas, jobId, `${jobId}:plan:${project.chapters.length + 1}`,
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
    const resumed: DurableGhostwriteJob = await this.repository.resumeGhostwriteJob(
      projectId, jobId, this.nowMs());
    this.launchGhostwriteDriver(projectId, jobId);
    return resumed;
  }

  async retryGhostwrite(projectId: string, jobId: string): Promise<DurableGhostwriteJob> {
    const retried: DurableGhostwriteJob = await this.repository.retryGhostwriteJob(
      projectId, jobId, this.nowMs());
    this.launchGhostwriteDriver(projectId, jobId);
    return retried;
  }

  async cancelGhostwrite(projectId: string, jobId: string): Promise<DurableGhostwriteJob> {
    let job: DurableGhostwriteJob = await this.repository.loadGhostwriteJob(projectId, jobId);
    if (job.claim === null) {
      if (job.stage === 'paused') {
        job = await this.repository.resumeGhostwriteJob(projectId, jobId, this.nowMs());
      } else if (job.stage === 'failed') {
        job = await this.repository.retryGhostwriteJob(projectId, jobId, this.nowMs());
      }
    }
    const claim: GhostwriteClaimRef = await this.ensureGhostwriteClaim(projectId, job);
    const cancelled: DurableGhostwriteJob =
      await this.repository.cancelGhostwriteJob(projectId, jobId, claim, this.nowMs());
    this.ghostwriteRunner.cancel(projectId, jobId);
    return cancelled;
  }

  async createBranch(projectId: string, name: string): Promise<NovelProject> {
    const status: NovelWorkspaceStatus = await this.repository.workspaceStatus(projectId);
    return await this.repository.createBranch(projectId, name, status.cas, novelId());
  }

  async switchBranch(projectId: string, branchId: string): Promise<NovelProject> {
    const status: NovelWorkspaceStatus = await this.repository.workspaceStatus(projectId);
    return await this.repository.switchBranch(projectId, branchId, status.cas);
  }

  async undoLast(projectId: string): Promise<NovelProject> {
    const status: NovelWorkspaceStatus = await this.repository.workspaceStatus(projectId);
    return await this.repository.undo(projectId, status.cas, novelId());
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
  ): Promise<DurableWorkspaceProposal> {
    const status: NovelWorkspaceStatus = await this.repository.workspaceStatus(projectId);
    return await this.repository.createProposal(
      projectId, status.cas, proposalId, patches, this.nowMs());
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
    const normalized: WorkspaceProposalPatch[] = await this.normalizeWorkspaceProposalPatches(
      projectId, patches as RawWorkspaceWritePatch[]);
    let proposals: DurableWorkspaceProposal[] = await this.repository.workspaceProposals(projectId);
    let existing: DurableWorkspaceProposal | undefined = proposals.find(
      (proposal: DurableWorkspaceProposal): boolean => proposal.proposalId === proposalId);
    if (existing === undefined) {
      await this.createWorkspaceProposal(projectId, normalized, proposalId);
      proposals = await this.repository.workspaceProposals(projectId);
      existing = proposals.find(
        (proposal: DurableWorkspaceProposal): boolean => proposal.proposalId === proposalId);
    }
    if (existing === undefined || JSON.stringify(existing.patches) !== JSON.stringify(normalized)) {
      throw invalidInput('write proposal patches 已变化');
    }
    return await this.resolveWorkspaceProposal(
      projectId, proposalId, true, `novel:${projectId}:proposal:${proposalId}:approve`);
  }

  async create(name: string): Promise<NovelProject> {
    const project: NovelProject = createProject(name, this.nowMs());
    return await this.repository.createProject(project);
  }

  rename(projectId: string, name: string): Promise<NovelProject> {
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

  async validateWritingModel(projectId: string): Promise<void> {
    const project: NovelProject = await this.repository.loadProject(projectId);
    await this.modelRunning.validate(project.modelPolicy.writing, projectId);
  }

  async delete(projectId: string): Promise<void> {
    // 若有活跃生成,先中断并等待其终止(避免 delete 与 updateProject 并发写竞争)
    const live: LiveRun | undefined = this.projectRuns.get(projectId);
    if (live !== undefined) {
      this.interrupt(live.runId);
      await live.donePromise;
    }
    await this.repository.deleteProject(projectId);
  }

  async saveChapter(
    projectId: string, chapterId: string | null, title: string, content: string,
  ): Promise<NovelChapter> {
    // holder 对象:闭包内赋值会使 TS 对裸 let 的控制流收窄失效(tsc TS2352)
    const box: { value: NovelChapter | null } = { value: null };
    await this.commitMutation(projectId, 'manual_edit', p => {
      const m = saveChapter(p, chapterId, title, content, this.nowMs());
      box.value = m.value;
      return m.project;
    });
    if (box.value === null) throw new Error('saveChapter: 更新未返回章节');
    return box.value;
  }

  deleteChapter(projectId: string, chapterId: string): Promise<NovelProject> {
    return this.commitMutation(projectId, 'chapter_delete',
      p => deleteChapter(p, chapterId, this.nowMs()));
  }

  async upsertMaterial(
    projectId: string, materialId: string | null, kind: NovelMaterialKind,
    title: string, content: string, enabled: boolean,
  ): Promise<NovelMaterial> {
    const box: { value: NovelMaterial | null } = { value: null };
    await this.commitMutation(projectId, 'material_edit', p => {
      const m = upsertMaterial(p, materialId, kind, title, content, enabled, this.nowMs());
      box.value = m.value;
      return m.project;
    });
    if (box.value === null) throw new Error('upsertMaterial: 更新未返回素材');
    return box.value;
  }

  deleteMaterial(projectId: string, materialId: string): Promise<NovelProject> {
    return this.commitMutation(projectId, 'material_delete',
      p => deleteMaterial(p, materialId, this.nowMs()));
  }

  // 收录助手回复进章节,并在后台分析素材建议(不阻塞收录返回)。
  // 建议分析可能耗时数十秒,UI 收录后应立即响应;建议稍后异步刷新。
  async collectMessage(
    projectId: string, messageId: string, target: NovelCollectionTarget,
  ): Promise<NovelCollectionResult> {
    const box: { value: NovelChapter | null } = { value: null };
    await this.commitMutation(projectId, 'collect', p => {
      const m = collect(p, messageId, target, this.nowMs());
      box.value = m.value;
      return m.project;
    });
    if (box.value === null) throw new Error('collectMessage: 更新未返回章节');
    const ch: NovelChapter = box.value;
    const refreshed: NovelProject = await this.repository.loadProject(projectId);
    if (refreshed.modelPolicy.stateSync === null) {
      return {
        chapter: ch,
        suggestionCount: 0,
        suggestionWarning: '未配置内部职责模型，已跳过资料同步',
      };
    }
    // 后台分析建议,失败不影响收录(UI 可稍后 reanalyze)
    void this.refreshMaterialSuggestions(projectId, ch.id).catch((): void => { /* 静默 */ });
    return { chapter: ch, suggestionCount: 0, suggestionWarning: null };
  }

  async refreshMaterialSuggestions(
    projectId: string, chapterId: string,
  ): Promise<NovelSuggestionRefreshResult> {
    const project: NovelProject = await this.repository.loadProject(projectId);
    const chapter: NovelChapter | undefined = project.chapters.find(c => c.id === chapterId);
    if (chapter === undefined) throw notFound('chapter', chapterId);
    if (project.modelPolicy.stateSync === null) throw invalidInput('请先配置内部职责模型');
    const now: number = this.nowMs();
    const suggestions: NovelMaterialSuggestion[] =
      await analyzeChapterSuggestions(
        this.modelRunning, project, chapter, project.modelPolicy.stateSync, now);
    // analyzeChapterSuggestions 内部已容错(失败返回空数组),这里再判一次并落库
    await this.commitMutation(
      projectId, 'suggestion_refresh', p => replacePendingSuggestions(p, chapterId, suggestions, now));
    // 建议 0 条 + analyze 内部失败 → 提示用户可重试(analyze 把失败也归为空数组,无法区分;
    // 这里仅在确无新建议时给中性提示)
    const warning: string | null =
      suggestions.length === 0 ? '本章暂无新的资料建议，可在章节变更后重新分析' : null;
    return { suggestions: suggestions, count: suggestions.length, warning: warning };
  }

  async resolveMaterialSuggestion(
    projectId: string, suggestionId: string, accept: boolean,
  ): Promise<NovelMaterial | null> {
    let material: NovelMaterial | null = null;
    await this.commitMutation(projectId, 'proposal_resolve', p => {
      const m = resolveSuggestion(p, suggestionId, accept, this.nowMs());
      material = m.value;
      return m.project;
    });
    return material;
  }

  async resolveSettingProposal(
    projectId: string, proposalId: string, accept: boolean,
  ): Promise<NovelMaterial | null> {
    let material: NovelMaterial | null = null;
    await this.commitMutation(projectId, 'proposal_resolve', (project: NovelProject): NovelProject => {
      const mutation = resolveSettingProposalMutation(project, proposalId, accept, this.nowMs());
      material = mutation.value;
      return mutation.project;
    });
    return material;
  }

  // ===== 生成 =====
  generate(
    projectId: string, userText: string, mode: NovelChatMode,
    granularity: NovelGenerationGranularity | null = null,
    runKind: NovelRunKind | null = null,
  ): NovelRun {
    const runId: string = novelId();
    const emitter: Emitter<NovelRunEvent> = createEmitter<NovelRunEvent>();

    if (this.projectRuns.has(projectId)) {
      const busyMsg: string = projectBusy(projectId).message;
      // 延迟发射,确保调用方先订阅
      setTimeout(() => { emitter.emit({ kind: 'failed', message: busyMsg }); }, 0);
      return { id: runId, subscribe: (cb): () => void => emitter.subscribe(cb) };
    }

    let doneResolve: () => void = (): void => {};
    const donePromise: Promise<void> = new Promise<void>(res => { doneResolve = res; });
    const live: LiveRun = {
      runId: runId, projectId: projectId, partial: '', interrupted: false,
      doneResolve: doneResolve, donePromise: donePromise,
    };
    this.projectRuns.set(projectId, live);
    void this.runGeneration(live, emitter, userText, mode, granularity, runKind);
    return { id: runId, subscribe: (cb): () => void => emitter.subscribe(cb) };
  }

  continueTool(
    projectId: string, toolCallId: string, verdict: NovelToolContinuationVerdict,
  ): NovelRun {
    const runId: string = novelId();
    const emitter: Emitter<NovelRunEvent> = createEmitter<NovelRunEvent>();
    if (this.projectRuns.has(projectId)) {
      const busyMsg: string = projectBusy(projectId).message;
      setTimeout((): void => { emitter.emit({ kind: 'failed', message: busyMsg }); }, 0);
      return { id: runId, subscribe: (cb): () => void => emitter.subscribe(cb) };
    }
    let doneResolve: () => void = (): void => {};
    const donePromise: Promise<void> = new Promise<void>(res => { doneResolve = res; });
    const live: LiveRun = {
      runId, projectId, partial: '', interrupted: false, doneResolve, donePromise,
    };
    this.projectRuns.set(projectId, live);
    void this.runToolContinuation(live, emitter, toolCallId, verdict);
    return { id: runId, subscribe: (cb): () => void => emitter.subscribe(cb) };
  }

  // N2:单章重新生成(whole_chapter regeneration 模板;校验章节存在)
  async regenerateChapter(projectId: string, chapterId: string): Promise<NovelRun> {
    const project: NovelProject = await this.findProjectAsync(projectId);
    const chapter: NovelChapter | null = project.chapters.find(c => c.id === chapterId) ?? null;
    if (chapter === null) throw notFound('chapter', chapterId);
    const userText: string = `请重新生成章节「${chapter.title}」。保留该章的目标情节,叙事方式重新设计。`;
    return this.generate(projectId, userText, 'write', 'whole_chapter', 'regenerate');
  }

  // N2:单章润色(polish 模板 + 哨兵协议)
  async polishChapter(projectId: string, chapterId: string): Promise<NovelRun> {
    const project: NovelProject = await this.findProjectAsync(projectId);
    const chapter: NovelChapter | null = project.chapters.find(c => c.id === chapterId) ?? null;
    if (chapter === null) throw notFound('chapter', chapterId);
    const userText: string = `请润色章节「${chapter.title}」,输出润色后的完整章节全文。\n\n${chapter.content}`;
    return this.generate(projectId, userText, 'write', 'whole_chapter', 'polish');
  }

  startBatchPolish(projectId: string, chapterIds: string[]): NovelBatchPolishRun {
    const existing: NovelBatchPolishRun | undefined = this.batchPolishProjectRuns.get(projectId);
    if (existing !== undefined) return existing;
    const runId: string = novelId();
    const emitter: Emitter<NovelBatchPolishEvent> = createEmitter<NovelBatchPolishEvent>();
    const live: LiveBatchPolishRun = {
      runId: runId,
      projectId: projectId,
      cancelled: false,
      currentRunId: null,
    };
    const publicRun: NovelBatchPolishRun = {
      id: runId,
      subscribe: (cb: (event: NovelBatchPolishEvent) => void): (() => void) => emitter.subscribe(cb),
      cancel: (): void => { this.cancelBatchPolish(runId); },
    };
    this.batchPolishRuns.set(runId, live);
    this.batchPolishProjectRuns.set(projectId, publicRun);
    void this.runBatchPolish(live, chapterIds.slice(), emitter);
    return publicRun;
  }

  cancelBatchPolish(runId: string): void {
    const live: LiveBatchPolishRun | undefined = this.batchPolishRuns.get(runId);
    if (live === undefined || live.cancelled) return;
    live.cancelled = true;
    if (live.currentRunId !== null) this.interrupt(live.currentRunId);
  }

  async adoptPolishMessage(
    projectId: string, chapterId: string, messageId: string,
  ): Promise<NovelChapter> {
    const sourceChapterId: string | undefined = this.polishMessageChapterIds.get(messageId);
    if (sourceChapterId === undefined || sourceChapterId !== chapterId) {
      throw invalidInput('润色结果与目标章节不匹配');
    }
    const box: { value: NovelChapter | null } = { value: null };
    await this.commitMutation(projectId, 'polish_adopt', (project: NovelProject): NovelProject => {
      const now: number = this.nowMs();
      const versioned: NovelProject = saveChapterVersion(project, chapterId, 'polish', now).project;
      const adopted = collect(versioned, messageId, { kind: 'replace', chapterId: chapterId }, now);
      box.value = adopted.value;
      return adopted.project;
    });
    if (box.value === null) throw new Error('adoptPolishMessage: 更新未返回章节');
    this.polishMessageChapterIds.delete(messageId);
    return box.value;
  }

  // N2:快速开始(空项目kick)
  async quickStart(projectId: string, idea: string): Promise<NovelRun> {
    return this.generate(projectId, idea, 'discuss', null, 'quick_start');
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

  // N3:讨论归档蒸馏 — 把指定讨论消息(按 id 列表)交给模型蒸馏成活资料建议
  async archiveDiscussion(projectId: string, messageIds: string[]): Promise<NovelSuggestionRefreshResult> {
    const project: NovelProject = await this.repository.loadProject(projectId);
    const msgs: NovelMessage[] = project.messages.filter(
      (m: NovelMessage): boolean => messageIds.includes(m.id) && m.content.trim().length > 0);
    if (msgs.length === 0) throw invalidInput('没有可归档的讨论内容');
    // 蒸馏 = 拼接讨论文本伪造成一章,复用章节素材建议分析(LLM 严格 JSON 输出)
    const mergedTitle: string = '讨论归档';
    const mergedContent: string = msgs.map((m: NovelMessage): string => m.content).join('\n\n');
    const pseudo: NovelChapter =
      makeNovelChapter({ title: mergedTitle, content: mergedContent, now: this.nowMs() });
    if (project.modelPolicy.stateSync === null) throw invalidInput('请先配置内部职责模型');
    const suggestions: NovelMaterialSuggestion[] = await analyzeChapterSuggestions(
      this.modelRunning, project, pseudo, project.modelPolicy.stateSync, this.nowMs());
    const updated: NovelProject = await this.commitMutation(
      projectId, 'discussion_archive', (p: NovelProject): NovelProject => {
      const next: NovelMaterialSuggestion[] = [...p.materialSuggestions];
      for (const sg of suggestions) {
        if (!next.some((x: NovelMaterialSuggestion): boolean => x.title === sg.title)) next.push(sg);
      }
      return { ...p, materialSuggestions: next, updatedAt: Date.now() };
      });
    return { suggestions: suggestions, count: suggestions.length, warning: null };
  }

  // N3:恢复最近删除的项目;无删除记录返回 null
  async restorePreviousProject(): Promise<NovelProject | null> {
    return this.repository.restorePrevious();
  }

  // N3:收录资格(页面禁用/提示):已收录 / 空文案 / 非本次运行头
  collectBlockReasonFor(project: NovelProject, messageId: string): string {
    const m: NovelMessage | undefined = project.messages.find(x => x.id === messageId);
    if (m === undefined) return '消息不存在';
    if (m.collectedChapterId !== null) return '该消息已收录';
    if (m.content.trim().length === 0) return '没有可收录的正文';
    if (m.interrupted && m.content.trim().length < 50) return '内容过短(中断且不足 50 字)';
    return '';
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

  private modelTargetFor(
    project: NovelProject, runKind: NovelRunKind | null,
  ): NovelProject['modelPolicy']['writing'] {
    if (runKind === 'polish' && project.modelPolicy.review !== null) {
      return project.modelPolicy.review;
    }
    return project.modelPolicy.writing;
  }

  private async commitMutation(
    projectId: string, kind: NovelWorkspaceMutationKind,
    transform: (project: NovelProject) => NovelProject,
    commandId: string = novelId(),
  ): Promise<NovelProject> {
    const status: NovelWorkspaceStatus = await this.repository.workspaceStatus(projectId);
    return await this.repository.commitProject(projectId, status.cas, commandId, kind, transform);
  }

  // ===== 内部 =====
  private async runGeneration(
    live: LiveRun, emitter: Emitter<NovelRunEvent>, userText: string,
    mode: NovelChatMode, granularity: NovelGenerationGranularity | null,
    runKind: NovelRunKind | null = null,
  ): Promise<void> {
    const projectId: string = live.projectId;
    try {
      const trimmed: string = userText.trim();
      if (trimmed.length === 0) throw invalidInput('请输入内容');
      if (trimmed.length > MAX_USER_CHARS) throw invalidInput(`输入超过 ${MAX_USER_CHARS} 字`);

      const project: NovelProject = await this.repository.loadProject(projectId);
      const gran: NovelGenerationGranularity | null =
        mode === 'write' ? (granularity ?? project.lastGenerationGranularity) : null;
      const sysPrompt: string = systemPrompt(project, mode, gran, runKind);
      const modelTarget = this.modelTargetFor(project, runKind);
      await this.modelRunning.validate(modelTarget, projectId);
      emitter.emit({ kind: 'started' });
      let checkpointIndex: number = 0;
      const request: NovelModelRequest = {
        runId: live.runId,
        projectId,
        systemPrompt: sysPrompt,
        maxOutputTokens: null,
        modelTarget,
        history: project.messages.map(novelMessageUi),
        operation: { kind: 'turn', userPrompt: trimmed },
        checkpoint: async (messages: UIMessage[]): Promise<void> => {
          checkpointIndex += 1;
          const now: number = this.nowMs();
          await this.commitMutation(projectId, 'transcript_checkpoint', (current: NovelProject): NovelProject => ({
            ...current,
            messages: transcriptFromUi(current, messages, mode, gran, now),
            lastGenerationGranularity: gran ?? current.lastGenerationGranularity,
            updatedAt: now,
          }), `novel:${live.runId}:transcript:${checkpointIndex}`);
        },
      };
      const result: CollectedModelResult = await this.collectWithEvents(live, emitter, request);
      if (result.terminal === 'waiting_user') {
        emitter.emit({ kind: 'waiting_user' });
        return;
      }
      const newMessages: UIMessage[] = result.messages.slice(project.messages.length);
      if (latestAssistantText(newMessages).trim().length === 0) {
        throw providerError('模型没有返回内容');
      }
      const saved: NovelProject = await this.repository.loadProject(projectId);
      const last: NovelMessage | undefined = saved.messages.slice().reverse().find(
        (message: NovelMessage): boolean => message.role === 'assistant' &&
          newMessages.some((ui: UIMessage): boolean => ui.id === novelMessageUi(message).id));
      if (last === undefined) throw providerError('模型结果未完成持久化');
      emitter.emit({ kind: 'completed', message: last });
    } catch (e) {
      if (live.interrupted) {
        // Adapter 的 checkpoint 已保存 canonical partial；这里只标记当前助手消息为 interrupted。
        await this.persistInterrupted(live, userText, mode, granularity);
        emitter.emit({ kind: 'interrupted', partial: live.partial });
      } else {
        const msg: string = isNovelError(e) ? e.message : String(e);
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
      const project: NovelProject = await this.repository.loadProject(projectId);
      const tool: UIMessagePartTool | null = this.findTool(project, toolCallId);
      if (tool === null) throw notFound('tool_call', toolCallId);
      const source: NovelMessage | undefined = project.messages.find(
        (message: NovelMessage): boolean => novelMessageUi(message).parts.some(
          (part): boolean => part.type === 'tool' && part.toolCallId === toolCallId));
      if (source === undefined) throw notFound('tool_message', toolCallId);
      const modelTarget = project.modelPolicy.writing;
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
        systemPrompt: systemPrompt(project, source.mode, granularity, null),
        maxOutputTokens: null,
        modelTarget,
        history: project.messages.map(novelMessageUi),
        operation: { kind: 'tool_continuation', toolCallId, verdict },
        checkpoint: async (messages: UIMessage[]): Promise<void> => {
          checkpointIndex += 1;
          const now: number = this.nowMs();
          await this.commitMutation(projectId, 'transcript_checkpoint', (current: NovelProject): NovelProject => ({
            ...current,
            messages: transcriptFromUi(current, messages, source.mode, granularity, now),
            updatedAt: now,
          }), `novel:${live.runId}:transcript:${checkpointIndex}`);
        },
      };
      const result: CollectedModelResult = await this.collectWithEvents(live, emitter, request);
      if (tool.toolName === 'novel_workspace_write' && verdict.kind === 'denied') {
        await this.resolvePreparedWorkspaceWrite(projectId, tool, false);
      }
      if (result.terminal === 'waiting_user') {
        emitter.emit({ kind: 'waiting_user' });
        return;
      }
      const saved: NovelProject = await this.repository.loadProject(projectId);
      const last: NovelMessage | undefined = saved.messages.slice().reverse().find(
        (message: NovelMessage): boolean => message.role === 'assistant' &&
          novelMessageText(message).trim().length > 0);
      if (last === undefined) throw providerError('模型没有返回内容');
      emitter.emit({ kind: 'completed', message: last });
    } catch (error) {
      const message: string = isNovelError(error) ? error.message : String(error);
      emitter.emit({ kind: 'failed', message });
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
    const patches: WorkspaceProposalPatch[] = await this.normalizeWorkspaceProposalPatches(
      projectId, raw.patches);
    const proposals: DurableWorkspaceProposal[] = await this.repository.workspaceProposals(projectId);
    const existing: DurableWorkspaceProposal | undefined = proposals.find(
      (proposal: DurableWorkspaceProposal): boolean => proposal.proposalId === proposalId);
    if (existing !== undefined) {
      const expected: string = JSON.stringify(patches);
      if (JSON.stringify(existing.patches) !== expected) {
        throw invalidInput(`proposal id 已用于其他 patches: ${proposalId}`);
      }
      if ((existing.status === 'accepted' && verdict.kind === 'denied') ||
        (existing.status === 'rejected' && verdict.kind === 'approved')) {
        throw invalidInput(`proposal 已按相反决定结案: ${proposalId}`);
      }
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
    const patches: WorkspaceProposalPatch[] = await this.normalizeWorkspaceProposalPatches(
      projectId, raw.patches);
    const proposals: DurableWorkspaceProposal[] = await this.repository.workspaceProposals(projectId);
    if (!proposals.some(
      (proposal: DurableWorkspaceProposal): boolean => proposal.proposalId === proposalId)) {
      await this.createWorkspaceProposal(projectId, patches, proposalId);
    }
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
    await this.modelRunning.validate(project.modelPolicy.writing, projectId);
    const reviewTarget = project.modelPolicy.review ?? project.modelPolicy.writing;
    if (JSON.stringify(reviewTarget) !== JSON.stringify(project.modelPolicy.writing)) {
      await this.modelRunning.validate(reviewTarget, projectId);
    }
    if (project.modelPolicy.stateSync !== null &&
      JSON.stringify(project.modelPolicy.stateSync) !== JSON.stringify(reviewTarget)) {
      await this.modelRunning.validate(project.modelPolicy.stateSync, projectId);
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
    this.drivingGhostwriteJobs.add(key);
    const ownerToken: string = novelId();
    void this.ghostwriteRunner.run(projectId, jobId, ownerToken, GHOSTWRITE_LEASE_MS)
      .catch((): void => {
        // runner 已将可归属错误写回 durable failed；owner 被暂停/取消抢占时保留新 durable 状态。
      })
      .finally((): void => { this.drivingGhostwriteJobs.delete(key); });
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

  private waitForNovelRun(run: NovelRun): Promise<NovelRunEvent> {
    return new Promise<NovelRunEvent>((resolve: (event: NovelRunEvent) => void): void => {
      let unsub: () => void = (): void => {};
      let settled: boolean = false;
      unsub = run.subscribe((event: NovelRunEvent): void => {
        if (event.kind !== 'completed' && event.kind !== 'waiting_user' &&
          event.kind !== 'interrupted' && event.kind !== 'failed') return;
        settled = true;
        unsub();
        resolve(event);
      });
      if (settled) unsub();
    });
  }

  private async runBatchPolish(
    live: LiveBatchPolishRun, chapterIds: string[], emitter: Emitter<NovelBatchPolishEvent>,
  ): Promise<void> {
    const results: NovelBatchPolishResult[] = [];
    try {
      for (let i = 0; i < chapterIds.length; i++) {
        if (live.cancelled) break;
        const chapterId: string = chapterIds[i];
        emitter.emit({ kind: 'progress', completed: results.length, total: chapterIds.length, chapterId: chapterId });
        let result: NovelBatchPolishResult;
        try {
          const run: NovelRun = await this.polishChapter(live.projectId, chapterId);
          live.currentRunId = run.id;
          if (live.cancelled) this.interrupt(run.id);
          const terminal: NovelRunEvent = await this.waitForNovelRun(run);
          if (terminal.kind === 'completed') {
            this.polishMessageChapterIds.set(terminal.message.id, chapterId);
            result = {
              chapterId: chapterId,
              status: 'succeeded',
              messageId: terminal.message.id,
              error: null,
            };
          } else if (live.cancelled || terminal.kind === 'interrupted') {
            result = {
              chapterId: chapterId,
              status: live.cancelled ? 'cancelled' : 'failed',
              messageId: null,
              error: live.cancelled ? '批量润色已取消' : '润色已中断',
            };
          } else if (terminal.kind === 'failed') {
            result = {
              chapterId: chapterId,
              status: 'failed',
              messageId: null,
              error: terminal.message,
            };
          } else {
            result = {
              chapterId: chapterId,
              status: 'failed',
              messageId: null,
              error: '润色运行异常结束',
            };
          }
        } catch (error) {
          result = {
            chapterId: chapterId,
            status: live.cancelled ? 'cancelled' : 'failed',
            messageId: null,
            error: live.cancelled ? '批量润色已取消' : String(error),
          };
        } finally {
          live.currentRunId = null;
        }
        results.push(result);
        emitter.emit({ kind: 'result', result: result });
        if (live.cancelled) break;
      }
      if (live.cancelled) {
        emitter.emit({ kind: 'cancelled', results: results.slice() });
      } else {
        emitter.emit({ kind: 'completed', results: results.slice() });
      }
    } finally {
      this.batchPolishRuns.delete(live.runId);
      const publicRun: NovelBatchPolishRun | undefined = this.batchPolishProjectRuns.get(live.projectId);
      if (publicRun?.id === live.runId) this.batchPolishProjectRuns.delete(live.projectId);
    }
  }

  // 订阅模型事件,镜像到 emitter 并累积 partial;completed 返回全文,failed/超时/超限 拒绝。
  private collectWithEvents(
    live: LiveRun, emitter: Emitter<NovelRunEvent>, request: NovelModelRequest,
  ): Promise<CollectedModelResult> {
    return new Promise<CollectedModelResult>((resolve, reject) => {
      let buffer: string = '';
      let messages: UIMessage[] = request.history.slice();
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
          const snapshotText: string = latestAssistantText(evt.messages);
          if (snapshotText.length > MAX_OUTPUT_CHARS) {
            finish(() => {
              this.modelRunning.cancel(request.runId);
              reject(outputTooLarge(MAX_OUTPUT_CHARS));
            });
            return;
          }
          buffer = snapshotText;
          live.partial = snapshotText;
          messages = evt.messages.slice();
          emitter.emit({
            kind: 'snapshot',
            messages: evt.messages.slice(),
            generationActive: evt.generationActive,
            textDeltasLive: evt.textDeltasLive,
            transport: evt.transport,
          });
        } else if (evt.kind === 'delta') {
          buffer += evt.text;
          live.partial = buffer;
          if (buffer.length > MAX_OUTPUT_CHARS) {
            finish(() => {
              this.modelRunning.cancel(request.runId);
              reject(outputTooLarge(MAX_OUTPUT_CHARS));
            });
            return;
          }
          emitter.emit({ kind: 'delta', text: evt.text });
        } else if (evt.kind === 'replace') {
          // replace 语义 = 全文替换,同样受输出上限约束(超限取消并拒绝)
          if (evt.text.length > MAX_OUTPUT_CHARS) {
            finish(() => {
              this.modelRunning.cancel(request.runId);
              reject(outputTooLarge(MAX_OUTPUT_CHARS));
            });
            return;
          }
          buffer = evt.text;
          live.partial = buffer;
          emitter.emit({ kind: 'replace', text: evt.text });
        } else if (evt.kind === 'completed') {
          finish(() => { resolve({ terminal: 'completed', messages, text: buffer }); });
        } else if (evt.kind === 'waiting_user') {
          finish(() => { resolve({ terminal: 'waiting_user', messages, text: buffer }); });
        } else if (evt.kind === 'failed') {
          finish(() => { reject(providerError(evt.message)); });
        }
        // activity:忽略
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
    try {
      const now: number = this.nowMs();
      await this.commitMutation(live.projectId, 'transcript_checkpoint', p => {
        const gran: NovelGenerationGranularity | null =
          mode === 'write' ? (granularity ?? p.lastGenerationGranularity) : null;
        let messages: NovelMessage[] = p.messages.slice();
        const hasUser: boolean = messages.some(
          (message: NovelMessage): boolean => message.role === 'user' &&
            novelMessageText(message).trim() === trimmed);
        if (!hasUser && trimmed.length > 0) {
          messages = messages.concat([
            makeNovelMessage({ role: 'user', mode, content: trimmed, createdAt: now }),
          ]);
        }
        if (partial.trim().length > 0) {
          let marked: boolean = false;
          for (let i: number = messages.length - 1; i >= 0; i--) {
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
            });
            marked = true;
            break;
          }
          if (!marked) {
            messages = messages.concat([makeNovelMessage({
              role: 'assistant', mode, content: partial, createdAt: now,
              granularity: gran, interrupted: true,
            })]);
          }
        }
        return { ...p, messages, updatedAt: now };
      }, `novel:${live.runId}:interrupt`);
    } catch {
      // 尽力而为,失败忽略(项目可能已被删除)
    }
  }
}

export const createNovelCreation = (deps: NovelCreationDeps): NovelCreation => {
  return new NovelCreation(deps);
};
