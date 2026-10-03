import type { NovelChapter, NovelModelTarget, NovelProject } from './models.ts';
import { novelId } from './models.ts';
import type { NovelProjectRepository, NovelWorkspaceCas } from './repository.ts';
import type { NovelModelRunning, NovelModelRequest } from './model_running.ts';
import { latestAssistantText } from '../agent/message.ts';
import { chapterPlotSourceDigest } from './plot_projection.ts';
import { effectiveNovelMaterials } from './material_inheritance.ts';
import {
  NOVEL_STATE_PROTOCOL_VERSION, emptyNovelStructuredState, parseNovelStateDelta,
  appendNovelStateDelta, pruneNovelStructuredState,
} from './structured_state.ts';
import type { NovelStructuredState } from './structured_state.ts';
import { invalidInput, projectBusy } from './error.ts';

export const STATE_MAX_OUTPUT_TOKENS = 8192;
export const STATE_SYSTEM_PROMPT = '从本块真实小说正文抽取结构事件，不把计划、资料匹配或推理当作已发生事实。只输出严格 JSON：'
  + '{"protocolVersion":"amber.novel.state.v1","chapterId":"输入chapterId","sourceDigest":"输入sourceDigest",'
  + '"events":[{"id":"本块唯一ID","chapterId":"输入chapterId","sourceDigest":"输入sourceDigest",'
  + '"quote":"本块逐字原文","summary":"原文证明的事实","entityRefs":["有效人物materialId"]}],'
  + '"unresolvedIdentityNames":["正文中的待作者澄清人物名称"]}。entityRefs只使用输入人物ID；'
  + '名称或别名出现本身不证明人物参与某事件。无法取证的事件不写；没有事件返回空数组。';

export interface NovelStateSource { chapterId: string; sourceDigest: string; }
export interface NovelStateTarget extends NovelStateSource { start: number; end: number; }
export interface NovelStateOperation {
  id: string;
  protocolVersion: 'amber.novel.state.v1';
  kind: 'stateDelta' | 'stateRebuild';
  status: 'running' | 'failed' | 'completed' | 'cancelled';
  branchId: string;
  sourceCas: NovelWorkspaceCas;
  sources: NovelStateSource[];
  selectedChapterIds: string[];
  targets: NovelStateTarget[];
  cursor: number;
  error: string | null;
  draft: NovelStructuredState;
  modelTarget: NovelModelTarget;
  reasoningEnabled: boolean;
  identityContext: string;
  startedAt: number;
  updatedAt: number;
}
interface StateRun { runId: string; cancelled: boolean; cancelWait: (() => void) | null; done: Promise<void>; finish: () => void; }

const identities = (project: NovelProject): string => JSON.stringify({
  characters: effectiveNovelMaterials(project).filter(material => material.kind === 'character')
    .map(material => ({ id: material.id, name: material.title, aliases: material.aliases ?? [] })),
  clarifications: project.structuredState?.identityClarifications ?? [],
});
const inputFor = (operation: NovelStateOperation, target: NovelStateTarget, chapter: NovelChapter): string => JSON.stringify({
  protocolVersion: NOVEL_STATE_PROTOCOL_VERSION, chapterId: chapter.id, sourceDigest: target.sourceDigest,
  sourceRange: { start: target.start, end: target.end }, identities: operation.identityContext,
  content: chapter.content.slice(target.start, target.end),
});
export const assertNovelStateSources = (operation: NovelStateOperation, project: NovelProject, branchId: string): void => {
  if (operation.branchId !== branchId) throw invalidInput('状态任务所属分支已切换，请回到原分支');
  const chapters = project.chapters.filter(chapter => !chapter.discarded);
  if (operation.sources.length !== chapters.length || operation.sources.some((source, index) =>
    source.chapterId !== chapters[index].id || source.sourceDigest !== chapterPlotSourceDigest(chapters[index].content))) {
    throw invalidInput('任务来源正文已变化，请重新开始状态重建');
  }
  if (operation.identityContext !== identities(project)) throw invalidInput('任务人物资料或身份澄清已变化，请重新开始状态重建');
};

export class NovelStateService {
  private readonly repository: NovelProjectRepository;
  private readonly model: NovelModelRunning;
  private readonly resolveTarget: (project: NovelProject) => Promise<NovelModelTarget>;
  private readonly runs: Map<string, StateRun> = new Map();
  private readonly observers: Map<string, Set<(operation: NovelStateOperation | null) => void>> = new Map();
  constructor(repository: NovelProjectRepository, model: NovelModelRunning,
    resolveTarget: (project: NovelProject) => Promise<NovelModelTarget>) {
    this.repository = repository; this.model = model; this.resolveTarget = resolveTarget;
  }
  isRunning(projectId: string): boolean { return this.runs.has(projectId); }
  observe(projectId: string, listener: (operation: NovelStateOperation | null) => void): () => void {
    let listeners = this.observers.get(projectId);
    if (listeners === undefined) { listeners = new Set(); this.observers.set(projectId, listeners); }
    listeners.add(listener);
    void this.repository.loadProject(projectId).then(project => {
      if (this.observers.get(projectId)?.has(listener)) listener(project.stateOperation ?? null);
    });
    return (): void => { const current = this.observers.get(projectId); current?.delete(listener);
      if (current?.size === 0) this.observers.delete(projectId); };
  }
  private publish(projectId: string, operation: NovelStateOperation): void {
    for (const listener of this.observers.get(projectId) ?? []) {
      try { listener(operation); } catch { /* The persisted operation remains authoritative. */ }
    }
  }
  async start(projectId: string, chapterId: string | null = null): Promise<NovelStateOperation> {
    if (this.runs.has(projectId)) throw projectBusy(projectId);
    const snapshot = await this.repository.readWorkspaceSnapshot(projectId);
    const chapters = snapshot.project.chapters.filter(chapter => !chapter.discarded);
    const selected = chapterId === null ? chapters : chapters.filter(chapter => chapter.id === chapterId);
    if (selected.length === 0) throw invalidInput('没有可抽取的正文');
    const modelTarget = await this.resolveTarget(snapshot.project);
    const operation: NovelStateOperation = {
      id: novelId(), protocolVersion: NOVEL_STATE_PROTOCOL_VERSION, kind: chapterId === null ? 'stateRebuild' : 'stateDelta',
      status: 'running', branchId: snapshot.status.activeBranchId, sourceCas: snapshot.status.cas,
      sources: chapters.map(chapter => ({ chapterId: chapter.id, sourceDigest: chapterPlotSourceDigest(chapter.content) })),
      selectedChapterIds: selected.map(chapter => chapter.id),
      targets: [], cursor: 0, error: null,
      draft: pruneNovelStructuredState(snapshot.project.structuredState ?? emptyNovelStructuredState(),
        snapshot.project.chapters, effectiveNovelMaterials(snapshot.project)),
      modelTarget, reasoningEnabled: snapshot.project.stateSyncReasoningEnabled === true,
      identityContext: identities(snapshot.project), startedAt: Date.now(), updatedAt: Date.now(),
    };
    const removed = new Set(selected.map(chapter => chapter.id));
    operation.draft = { ...operation.draft, events: operation.draft.events.filter(event => !removed.has(event.chapterId)),
      unresolvedIdentityNames: chapterId === null ? [] : operation.draft.unresolvedIdentityNames,
      chapterSources: operation.draft.chapterSources.filter(source => !removed.has(source.chapterId)) };
    return this.drive(projectId, operation, snapshot.status.cas, selected);
  }
  async retry(projectId: string): Promise<NovelStateOperation> {
    if (this.runs.has(projectId)) throw projectBusy(projectId);
    const snapshot = await this.repository.readWorkspaceSnapshot(projectId);
    const previous = snapshot.project.stateOperation;
    if (previous === null || previous === undefined || previous.status === 'completed') throw invalidInput('没有可重试的状态任务');
    assertNovelStateSources(previous, snapshot.project, snapshot.status.activeBranchId);
    return this.drive(projectId, { ...previous, status: 'running', error: null, updatedAt: Date.now() }, snapshot.status.cas);
  }
  cancel(projectId: string): void {
    const run = this.runs.get(projectId);
    if (run === undefined) return;
    run.cancelled = true;
    run.cancelWait?.();
    if (run.runId.length > 0) this.model.cancel(run.runId);
  }
  async cancelAndWait(projectId: string): Promise<void> {
    const run = this.runs.get(projectId);
    if (run === undefined) return;
    this.cancel(projectId);
    await run.done;
  }
  private async collect(request: NovelModelRequest, run: StateRun): Promise<string> {
    return new Promise<string>((resolve, reject) => {
      let text: string = ''; let done: boolean = false; let unsubscribe: () => void = (): void => {};
      const finish = (error: Error | null): void => {
        if (done) return; done = true; clearTimeout(timer); unsubscribe(); run.cancelWait = null;
        if (error === null) resolve(text); else reject(error);
      };
      const timer = setTimeout((): void => { finish(new Error('状态抽取超时，请显式重试')); this.model.cancel(request.runId); }, 120_000);
      run.cancelWait = (): void => finish(new Error('状态任务已取消'));
      if (run.cancelled) { run.cancelWait(); return; }
      try {
        unsubscribe = this.model.start(request).subscribe(event => {
          if (event.kind === 'snapshot') text = latestAssistantText(event.messages);
          else if (event.kind === 'completed') finish(null);
          else if (event.kind === 'failed') finish(new Error(event.message));
          else if (event.kind === 'waiting_user') { finish(new Error('状态任务不支持工具交互')); this.model.cancel(request.runId); }
        });
        if (done) unsubscribe();
      } catch (error) { finish(error instanceof Error ? error : new Error(String(error))); }
    });
  }
  private async drive(projectId: string, initial: NovelStateOperation, initialCas: NovelWorkspaceCas,
    selected?: NovelChapter[]): Promise<NovelStateOperation> {
    if (this.runs.has(projectId)) throw projectBusy(projectId);
    let finish: () => void = (): void => {};
    const done = new Promise<void>(resolve => { finish = resolve; });
    const handle: StateRun = { runId: '', cancelled: false, cancelWait: null, done, finish };
    this.runs.set(projectId, handle);
    let operation = initial;
    let expected = initialCas;
    const persist = async (next: NovelStateOperation, complete: boolean = false): Promise<void> => {
      const saved = complete ? await this.repository.completeStateOperation(projectId, expected, next)
        : await this.repository.commitProject(projectId, expected,
          `${operation.id}:state:${next.cursor}:${next.status}:${novelId()}`, 'state_operation', project => ({ ...project,
            stateOperation: next, updatedAt: Date.now() }));
      operation = saved.stateOperation!;
      const snapshot = await this.repository.readWorkspaceSnapshot(projectId);
      if (snapshot.project.revision !== saved.revision) throw invalidInput('状态保存后工作区已变化，下一块未启动');
      expected = snapshot.status.cas;
      this.publish(projectId, operation);
    };
    try {
      await persist(operation);
      if (handle.cancelled) throw new Error('状态任务已取消');
      if (selected !== undefined || operation.targets.length === 0) {
        const snapshot = await this.repository.readWorkspaceSnapshot(projectId);
        assertNovelStateSources(operation, snapshot.project, snapshot.status.activeBranchId);
        await this.model.validate(operation.modelTarget, projectId);
        const budget = this.model.inputBudgetTokens === undefined ? 16000
          : await this.model.inputBudgetTokens(operation.modelTarget, projectId, STATE_MAX_OUTPUT_TOKENS);
        const estimate = (input: string): number => this.model.estimateInputTokens === undefined
          ? STATE_SYSTEM_PROMPT.length + input.length + 256 : this.model.estimateInputTokens(STATE_SYSTEM_PROMPT, input);
        const chapters = selected ?? snapshot.project.chapters.filter(chapter => !chapter.discarded
          && operation.selectedChapterIds.includes(chapter.id));
        const targets: NovelStateTarget[] = [];
        for (const chapter of chapters) {
          let start: number = 0;
          do {
            const target: NovelStateTarget = { chapterId: chapter.id, sourceDigest: chapterPlotSourceDigest(chapter.content), start, end: start };
            if (estimate(inputFor(operation, target, chapter)) >= budget) throw invalidInput('人物身份资料已超过状态模型输入预算，请选择更大窗口模型');
            let low: number = start; let high: number = chapter.content.length;
            while (low < high) {
              const mid: number = Math.ceil((low + high) / 2);
              if (estimate(inputFor(operation, { ...target, end: mid }, chapter)) <= budget) low = mid; else high = mid - 1;
            }
            if (low < chapter.content.length && low > start && chapter.content.charCodeAt(low - 1) >= 0xD800
              && chapter.content.charCodeAt(low - 1) <= 0xDBFF) low--;
            if (low === start && chapter.content.length > start) throw invalidInput('状态模型窗口不足以容纳正文');
            targets.push({ ...target, end: low }); start = low;
          } while (start < chapter.content.length);
        }
        await persist({ ...operation, targets, updatedAt: Date.now() });
      }
      while (operation.cursor < operation.targets.length) {
        if (handle.cancelled) throw new Error('状态任务已取消');
        const snapshot = await this.repository.readWorkspaceSnapshot(projectId);
        assertNovelStateSources(operation, snapshot.project, snapshot.status.activeBranchId);
        const target = operation.targets[operation.cursor];
        const chapter = snapshot.project.chapters.find(item => item.id === target.chapterId)!;
        const frozen = { ...chapter };
        handle.runId = `${operation.id}:chunk:${operation.cursor}`;
        const text = await this.collect({ runId: handle.runId, projectId, systemPrompt: STATE_SYSTEM_PROMPT,
          maxOutputTokens: STATE_MAX_OUTPUT_TOKENS, modelTarget: operation.modelTarget, toolProfile: 'none', history: [],
          taskOptions: { kind: operation.kind, reasoningEnabled: operation.reasoningEnabled },
          operation: { kind: 'turn', userPrompt: inputFor(operation, target, frozen) }, checkpoint: async (): Promise<void> => {},
        }, handle);
        if (handle.cancelled) throw new Error('状态任务已取消');
        const delta = parseNovelStateDelta(text, frozen, effectiveNovelMaterials(snapshot.project));
        const block: string = frozen.content.slice(target.start, target.end);
        if (delta.events.some(event => !block.includes(event.quote)) || delta.unresolvedIdentityNames.some(name => !block.includes(name))) {
          throw invalidInput('状态抽取引用了本块以外的正文，结果未保存');
        }
        const scoped = { ...delta, events: delta.events.map(event => ({ ...event,
          id: `${frozen.id}:${target.start}:${event.id}` })) };
        const draft = appendNovelStateDelta(operation.draft, scoped, frozen, effectiveNovelMaterials(snapshot.project));
        await persist({ ...operation, cursor: operation.cursor + 1, draft, updatedAt: Date.now() });
      }
      await persist({ ...operation, status: 'completed', error: null, updatedAt: Date.now() }, true);
      return operation;
    } catch (error) {
      const snapshot = await this.repository.readWorkspaceSnapshot(projectId);
      if (snapshot.status.activeBranchId !== operation.branchId || snapshot.project.stateOperation?.id !== operation.id) throw error;
      expected = snapshot.status.cas;
      await persist({ ...operation, status: handle.cancelled ? 'cancelled' : 'failed',
        error: error instanceof Error ? error.message : String(error), updatedAt: Date.now() });
      return operation;
    } finally { this.runs.delete(projectId); handle.finish(); }
  }
}
