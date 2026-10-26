import { resolveNovelDefaultTarget } from './standalone_defaults.ts';
import type { NovelModelDefaults, NovelModelRole } from './standalone_defaults.ts';
import { makeNovelMessage, novelId } from './models.ts';
import type { NovelRunKind } from './prompt_catalog.ts';
import type {
  NovelProject, NovelMessage, NovelChapter, NovelChatMode, NovelGenerationGranularity,
  NovelCandidateProvenance,
} from './models.ts';
import { invalidInput, notFound, projectBusy, providerError, outputTooLarge, isNovelError } from './error.ts';
import { systemPrompt, buildNovelContext, MAX_USER_CHARS, MAX_OUTPUT_CHARS } from './context_builder.ts';
import type { NovelModelRunning, NovelModelRequest, NovelModelEvent, NovelModelStream } from './model_running.ts';
import type { NovelToolContinuationVerdict } from './model_running.ts';
import type { NovelInjectionOverrides } from './material_injection.ts';
import {
  freezeNovelOrdinaryRequest, ordinaryRunMessages, ordinaryRunHistoryCount, ordinaryContinuationHistory,
  assertOrdinaryRunTranscript, ordinaryRunCursor, ordinaryRecoveryRequest,
} from './ordinary_run.ts';
import type { NovelOrdinaryRun, NovelOrdinaryRunView, NovelOrdinaryRunStatus } from './ordinary_run.ts';
import type {
  NovelProjectRepository, NovelWorkspaceMutationKind, NovelWorkspaceStatus, NovelWorkspaceCas,
} from './repository.ts';
import { latestAssistantText } from '../agent/message.ts';
import type { UIMessage, UIMessagePart, UIMessagePartTool } from '../agent/message.ts';
import { novelMessageText, novelMessageUi, withNovelMessageText } from './transcript.ts';
import { makeNovelCandidateProvenance } from './candidate_provenance.ts';
import { hasPolishSentinel, stripPolishSentinel } from './prompt_catalog.ts';
import { stageNovelQuickStart } from './quick_start_proposals.ts';
import type { NovelRun, NovelRunEvent, NovelActiveRun } from './creation.ts';
import type { NovelWorkspaceToolProposals } from './workspace_tool_proposals.ts';

// 普通生成、工具延续与恢复共用同一运行 owner；页面重连读取同一 canonical snapshot。
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

interface NovelOrdinaryRunDeps {
  repository: NovelProjectRepository;
  modelRunning: NovelModelRunning;
  loadModelDefaults: () => Promise<NovelModelDefaults>;
  workspaceTools: NovelWorkspaceToolProposals;
  otherInteractiveRunning: (projectId: string) => boolean;
  otherDriverRunning: (projectId: string) => boolean;
  nowMs: () => number;
  idleTimeoutMs: number;
}

export class NovelOrdinaryRunService {
  private repository: NovelProjectRepository;
  private modelRunning: NovelModelRunning;
  private loadModelDefaults: () => Promise<NovelModelDefaults>;
  private workspaceTools: NovelWorkspaceToolProposals;
  private otherInteractiveRunning: (projectId: string) => boolean;
  private otherDriverRunning: (projectId: string) => boolean;
  private nowMs: () => number;
  private idleTimeoutMs: number;
  private projectRuns: Map<string, LiveRun> = new Map();

  constructor(deps: NovelOrdinaryRunDeps) {
    this.repository = deps.repository;
    this.modelRunning = deps.modelRunning;
    this.loadModelDefaults = deps.loadModelDefaults;
    this.workspaceTools = deps.workspaceTools;
    this.otherInteractiveRunning = deps.otherInteractiveRunning;
    this.otherDriverRunning = deps.otherDriverRunning;
    this.nowMs = deps.nowMs;
    this.idleTimeoutMs = deps.idleTimeoutMs;
  }

  isRunning(projectId: string): boolean { return this.projectRuns.has(projectId); }

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

    if (this.projectRuns.has(projectId) || this.otherInteractiveRunning(projectId)) {
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
    const active: boolean = (this.isRunning(projectId) || this.otherDriverRunning(projectId));
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
    if ((this.isRunning(projectId) || this.otherDriverRunning(projectId))) {
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
    if (this.projectRuns.has(projectId) || this.otherInteractiveRunning(projectId)) {
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

  async stopActiveRun(projectId: string): Promise<void> {
    const live: LiveRun | undefined = this.projectRuns.get(projectId);
    if (live !== undefined) {
      this.interrupt(live.runId);
      await live.donePromise;
      if (live.interruptedSaveError !== undefined) throw providerError(live.interruptedSaveError);
    }
  }

  interrupt(runId: string): void {
    this.projectRuns.forEach(live => {
      if (live.runId === runId) live.interrupted = true;
    });
    this.modelRunning.cancel(runId);
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

  private assertExpectedSnapshot(actual: NovelWorkspaceCas, expected?: NovelWorkspaceCas): void {
    if (expected !== undefined && (actual.branchId !== expected.branchId || actual.head !== expected.head || actual.treeDigest !== expected.treeDigest))
      throw invalidInput('工作区已变化，请重新读取后操作');
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
        await this.workspaceTools.prepareWorkspaceWriteTool(projectId, tool, verdict);
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
        await this.workspaceTools.resolvePreparedWorkspaceWrite(projectId, tool, false);
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
