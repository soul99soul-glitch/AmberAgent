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
} from './models.ts';
import {
  createProject, renameProject, setProjectModel, appendMessage, saveChapter, deleteChapter,
  upsertMaterial, deleteMaterial, collect, replacePendingSuggestions, resolveSuggestion,
  addSettingProposals, resolveSettingProposal as resolveSettingProposalMutation, saveChapterVersion,
} from './mutations.ts';
import {
  invalidInput, notFound, projectBusy, providerError, outputTooLarge, isNovelError,
} from './error.ts';
import { systemPrompt, MAX_USER_CHARS, MAX_OUTPUT_CHARS } from './context_builder.ts';
import { analyzeChapterSuggestions } from './suggestion_engine.ts';
import type { NovelModelRunning, NovelModelRequest, NovelModelEvent , NovelModelStream } from './model_running.ts';
import type {
  NovelProjectRepository, NovelWorkspaceMutationKind, NovelWorkspaceStatus,
} from './repository.ts';
import { parseSettingProposals } from './discussion_structured.ts';
import type { NovelSettingProposalDraft, NovelSettingProposalsParsed } from './discussion_structured.ts';
import { latestAssistantText } from '../agent/message.ts';
import type { StreamTransportState, UIMessage } from '../agent/message.ts';
import type { DurableWorkspaceProposal, WorkspaceProposalPatch } from './workspace_history.ts';

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

// 事件发射器:仅缓冲终结事件(completed/interrupted/failed),供晚订阅补齐。
// delta/status/activity 不入 buffer(一次生成可达数十万 delta,全缓冲会 OOM)。
interface Emitter<T> {
  emit(v: T): void;
  subscribe(cb: (v: T) => void): () => void;
}

const isTerminal = <T>(v: T): boolean => {
  const k: string = (v as { kind?: string }).kind ?? '';
  return k === 'completed' || k === 'interrupted' || k === 'failed' || k === 'cancelled';
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
  private nowMs: () => number;
  private idleTimeoutMs: number;
  private projectRuns: Map<string, LiveRun> = new Map();
  private batchPolishRuns: Map<string, LiveBatchPolishRun> = new Map();
  private batchPolishProjectRuns: Map<string, NovelBatchPolishRun> = new Map();
  private polishMessageChapterIds: Map<string, string> = new Map();

  constructor(deps: NovelCreationDeps) {
    this.repository = deps.repository;
    this.modelRunning = deps.modelRunning;
    this.nowMs = deps.nowMs ?? ((): number => Date.now());
    this.idleTimeoutMs = deps.idleTimeoutMs ?? DEFAULT_IDLE_TIMEOUT_MS;
  }

  // ===== 查询 / 变更 =====
  listProjects(): Promise<NovelProject[]> {
    return this.repository.listProjects();
  }

  open(projectId: string): Promise<NovelProject> {
    return this.repository.loadProject(projectId);
  }

  workspaceStatus(projectId: string): Promise<NovelWorkspaceStatus> {
    return this.repository.workspaceStatus(projectId);
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
    projectId: string, proposalId: string, accept: boolean,
  ): Promise<DurableWorkspaceProposal> {
    return this.repository.resolveProposal(projectId, proposalId, accept, novelId(), this.nowMs());
  }

  async create(name: string): Promise<NovelProject> {
    const project: NovelProject = createProject(name, this.nowMs());
    return await this.repository.createProject(project);
  }

  rename(projectId: string, name: string): Promise<NovelProject> {
    return this.commitMutation(projectId, 'rename', p => renameProject(p, name, this.nowMs()));
  }

  setModel(projectId: string, modelId: string | null): Promise<NovelProject> {
    return this.commitMutation(projectId, 'model_change', p => setProjectModel(p, modelId, this.nowMs()));
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
    const now: number = this.nowMs();
    const suggestions: NovelMaterialSuggestion[] =
      await analyzeChapterSuggestions(this.modelRunning, project, chapter, project.modelId, now);
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
    const suggestions: NovelMaterialSuggestion[] = await analyzeChapterSuggestions(
      this.modelRunning, project, pseudo, project.modelId, this.nowMs());
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

  private async commitMutation(
    projectId: string, kind: NovelWorkspaceMutationKind,
    transform: (project: NovelProject) => NovelProject,
  ): Promise<NovelProject> {
    const status: NovelWorkspaceStatus = await this.repository.workspaceStatus(projectId);
    return await this.repository.commitProject(projectId, status.cas, novelId(), kind, transform);
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

      await this.modelRunning.validate(project.modelId);
      emitter.emit({ kind: 'started' });

      const request: NovelModelRequest = {
        runId: live.runId, systemPrompt: sysPrompt, userPrompt: trimmed,
        maxOutputTokens: null, modelId: project.modelId,
      };
      const fullText: string = await this.collectWithEvents(live, emitter, request);

      if (fullText.trim().length === 0) throw providerError('模型没有返回内容');

      const now: number = this.nowMs();
      const userMsg: NovelMessage = makeNovelMessage({ role: 'user', mode: mode, content: trimmed, createdAt: now });
      const assistantMsg: NovelMessage = makeNovelMessage({
        role: 'assistant', mode: mode, content: fullText, createdAt: now, granularity: gran,
      });
      const proposalBlock: NovelSettingProposalsParsed | null =
        mode === 'discuss' ? parseSettingProposals(fullText) : null;
      const proposalDrafts: NovelSettingProposalDraft[] = proposalBlock?.proposals ?? [];
      await this.commitMutation(projectId, 'generation_commit', p => {
        let np: NovelProject = appendMessage(p, userMsg, now);
        np = appendMessage(np, assistantMsg, now);
        np = addSettingProposals(np, assistantMsg.id, proposalDrafts, now);
        if (gran !== null) np = { ...np, lastGenerationGranularity: gran };
        return np;
      });
      emitter.emit({ kind: 'completed', message: assistantMsg });
    } catch (e) {
      if (live.interrupted) {
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

  private waitForNovelRun(run: NovelRun): Promise<NovelRunEvent> {
    return new Promise<NovelRunEvent>((resolve: (event: NovelRunEvent) => void): void => {
      let unsub: () => void = (): void => {};
      let settled: boolean = false;
      unsub = run.subscribe((event: NovelRunEvent): void => {
        if (event.kind !== 'completed' && event.kind !== 'interrupted' && event.kind !== 'failed') return;
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
  ): Promise<string> {
    return new Promise<string>((resolve, reject) => {
      let buffer: string = '';
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
          finish(() => { resolve(buffer); });
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

  // 中断时尽力保存 partial + 用户消息。即使 partial 为空,也保留 user 消息
  // (用户已发送的输入不应丢失);partial 为空时不写 interrupted 助手占位消息。
  private async persistInterrupted(
    live: LiveRun, userText: string, mode: NovelChatMode,
    granularity: NovelGenerationGranularity | null,
  ): Promise<void> {
    const partial: string = live.partial;
    const trimmed: string = userText.trim();
    if (partial.trim().length === 0 && trimmed.length === 0) return;
    try {
      const now: number = this.nowMs();
      await this.commitMutation(live.projectId, 'interrupt_commit', p => {
        const gran: NovelGenerationGranularity | null =
          mode === 'write' ? (granularity ?? p.lastGenerationGranularity) : null;
        let np: NovelProject = p;
        if (trimmed.length > 0) {
          np = appendMessage(np, makeNovelMessage({ role: 'user', mode: mode, content: trimmed, createdAt: now }), now);
        }
        // 有 partial 才写 interrupted 助手消息;否则只保留 user 输入
        if (partial.trim().length > 0) {
          np = appendMessage(np, makeNovelMessage({
            role: 'assistant', mode: mode, content: partial, createdAt: now, granularity: gran, interrupted: true,
          }), now);
        }
        return np;
      });
    } catch {
      // 尽力而为,失败忽略(项目可能已被删除)
    }
  }
}

export const createNovelCreation = (deps: NovelCreationDeps): NovelCreation => {
  return new NovelCreation(deps);
};
