// council/manager — 模型议会编排器(移植自 Android ModelCouncilManager)
//
// 流程:第 1 轮各席并行独立作答;DEBATE 模式 2..N 轮喂前轮摘要,末轮(finalPosition);
// 最后裁判模型综合(synthesize)。同一模型并发用 Semaphore(PROVIDER_PARALLELISM) 限流。
// 每席实时文本经回调订阅(非轮询)。可选写 JSONL 转录。纯逻辑,Node 下 mock runner 可测。

import type { ModelConfig } from '../domain/model_config.ts';
import type { FileStore } from '../platform/files.ts';
import type { AbortSignalLike } from '../platform/runtime_api.ts';
import type { UIMessage } from '../agent/message.ts';
import { isToolAwaitingExecution } from '../agent/message.ts';
import type { ModelCouncilTextRunner } from './runner.ts';
import { Semaphore, withPermit } from './semaphore.ts';
import {
  PROVIDER_PARALLELISM, SYNTHESIZER_SEAT_KEY, makeEmptyResult, makeRuntimeSetting, normalizeCouncilToolMode,
} from './models.ts';
import type {
  ModelCouncilRun, ModelCouncilSeat, ModelCouncilTurn, ModelCouncilRunStatus,
  ModelCouncilRuntimeSetting, ModelCouncilResult, CouncilApprovalRequest,
  CouncilSeatRunKey, CouncilSource, CouncilToolSnapshot, CouncilToolVerdict,
} from './models.ts';
import {
  seatSystemPrompt, openingPrompt, responsePrompt, finalPositionPrompt,
  synthesisPrompt, SYNTHESIZER_SYSTEM_PROMPT, truncate,
} from './prompts.ts';
import { parseTask, resolveSynthesisModelId } from './validator.ts';
import type { CouncilTaskInput } from './validator.ts';
import { CouncilApprovalWaiters, copyCouncilToolSnapshot, councilSeatKey,
  validCouncilPending } from './approval_wait.ts';

// ===== 超时 / 取消 工具 =====
class TimeoutError extends Error {
  constructor(msg: string) {
    super(msg);
    this.name = 'TimeoutError';
  }
}

// 裁决保守解析:顶层 JSON object 的字符串数组字段填入结构化结果;
// 解析失败/无已知字段 → 原文保留在 finalRecommendation 并附 warning(不丢弃)
const applyVerdictText = (
  result: ModelCouncilResult, text: string, warnings: string[],
): void => {
  result.warnings = warnings;
  let parsed: unknown = null;
  try {
    parsed = JSON.parse(text);
  } catch (_e) {
    parsed = null;
  }
  const stringArrayField = (obj: Record<string, unknown>, keys: string[]): string[] | null => {
    for (const key of keys) {
      const v: unknown = obj[key];
      if (Array.isArray(v) && v.every((x): boolean => typeof x === 'string')) {
        return v as string[];
      }
    }
    return null;
  };
  if (parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)) {
    const obj: Record<string, unknown> = parsed as Record<string, unknown>;
    const consensus = stringArrayField(obj, ['consensus']);
    const conflicts = stringArrayField(obj, ['conflicts']);
    const evidence = stringArrayField(obj, ['strongestEvidence', 'strongest_evidence']);
    const risks = stringArrayField(obj, ['risks']);
    const finalRec: unknown = obj['finalRecommendation'] ?? obj['final_recommendation'];
    const structured: boolean = consensus !== null || conflicts !== null
      || evidence !== null || risks !== null || typeof finalRec === 'string';
    if (structured) {
      if (consensus !== null) result.consensus = consensus;
      if (conflicts !== null) result.conflicts = conflicts;
      if (evidence !== null) result.strongestEvidence = evidence;
      if (risks !== null) result.risks = risks;
      result.finalRecommendation = typeof finalRec === 'string' ? finalRec : text;
      return;
    }
  }
  result.finalRecommendation = text;
  result.warnings = [...warnings, 'synthesis response is not valid structured JSON'];
};

const withTimeout = <T>(p: Promise<T>, ms: number): Promise<T> => {
  return new Promise<T>((resolve, reject) => {
    const timer: ReturnType<typeof setTimeout> =
      setTimeout(() => { reject(new TimeoutError('timeout')); }, ms);
    p.then(v => { clearTimeout(timer); resolve(v); })
      .catch(e => { clearTimeout(timer); reject(e); });
  });
};

class CancelSignal implements AbortSignalLike {
  aborted: boolean = false;
  private cbs: Array<() => void> = [];
  addEventListener(_type: string, listener: () => void): void {
    this.cbs.push(listener);
  }
  removeEventListener(_type: string, listener: () => void): void {
    const i: number = this.cbs.indexOf(listener);
    if (i >= 0) this.cbs.splice(i, 1);
  }
  abort(): void {
    if (this.aborted) return;
    this.aborted = true;
    const callbacks: Array<() => void> = this.cbs.slice();
    this.cbs = [];
    for (let i = 0; i < callbacks.length; i++) callbacks[i]();
  }
}

// 让模型调用与取消信号赛跑:即使 runner 没监听 abort,取消也能立即生效。
const raceCancel = <T>(p: Promise<T>, signal: CancelSignal): Promise<T> => {
  return new Promise<T>((resolve, reject) => {
    const abort = (): void => { reject(new Error('aborted')); };
    signal.addEventListener('abort', abort);
    p.then(value => { signal.removeEventListener('abort', abort); resolve(value); },
      error => { signal.removeEventListener('abort', abort); reject(error); });
    if (signal.aborted) abort();
  });
};

// ===== 依赖 =====
export interface ModelCouncilDeps {
  runner: ModelCouncilTextRunner;
  fileStore: FileStore | null;   // 测试可传 null 跳过转录
  modelPool: ModelConfig[];
  setting: ModelCouncilRuntimeSetting;
  nowMs?: () => number;
}

// 转录事件(各自 typed,便于 JSON.stringify)
interface TranscriptStarted { event: string; runId: string; mode: string; run: ModelCouncilRun; }
interface TranscriptTurn { event: string; turn: ModelCouncilTurn; }
interface TranscriptFinished { event: string; status: string; run: ModelCouncilRun; }

let runSeq = 0;
const newRunId = (): string => {
  runSeq += 1;
  return `run_${Date.now().toString(36)}_${runSeq}`;
};

export class ModelCouncilManager {
  private runner: ModelCouncilTextRunner;
  private fileStore: FileStore | null;
  private modelPool: ModelConfig[];
  private setting: ModelCouncilRuntimeSetting;
  private nowMs: () => number;

  private runs: Map<string, ModelCouncilRun> = new Map();
  private liveText: Map<string, Map<string, string>> = new Map();
  private listeners: Map<string, Map<string, Set<(text: string) => void>>> = new Map();
  private signals: Map<string, CancelSignal> = new Map();
  private completions: Map<string, Promise<void>> = new Map();
  private toolSnapshots: Map<string, CouncilToolSnapshot> = new Map();
  private toolListeners: Map<string, Set<(snapshot: CouncilToolSnapshot) => void>> = new Map();
  private approvalWaiters: CouncilApprovalWaiters = new CouncilApprovalWaiters();
  private transcriptWrites: Map<string, Promise<void>> = new Map();
  private transcriptErrors: Map<string, string[]> = new Map();

  constructor(deps: ModelCouncilDeps) {
    this.runner = deps.runner;
    this.fileStore = deps.fileStore;
    this.modelPool = JSON.parse(JSON.stringify(deps.modelPool)) as ModelConfig[];
    this.setting = makeRuntimeSetting(JSON.parse(JSON.stringify(deps.setting)) as ModelCouncilRuntimeSetting);
    this.nowMs = deps.nowMs ?? ((): number => Date.now());
  }

  // ===== 公开 API =====

  // 解析任务并发起运行(同步返回初始快照;executeCouncil 后台跑)。校验失败抛 Error。
  start(input: CouncilTaskInput): ModelCouncilRun {
    const spec = parseTask(input, this.setting, this.modelPool);
    const runId: string = newRunId();
    const now: number = this.nowMs();
    const run: ModelCouncilRun = {
      runId: runId, status: 'running', mode: spec.mode, seats: spec.seats, task: spec,
      turns: [], result: null, transcriptPath: `model-council/runs/${runId}.jsonl`,
      startedAtMs: now, updatedAtMs: now,
    };
    this.runs.set(runId, run);
    this.signals.set(runId, new CancelSignal());
    const started: TranscriptStarted = { event: 'started', runId: runId, mode: spec.mode, run: this.copyRun(run) };
    this.writeTranscriptLine(runId, JSON.stringify(started));
    this.completions.set(runId, this.executeCouncil(run));
    return this.copyRun(run);
  }

  snapshot(runId: string): ModelCouncilRun | null {
    const run: ModelCouncilRun | undefined = this.runs.get(runId);
    return run !== undefined ? this.copyRun(run) : null;
  }

  // 首页「继续」卡:列出仍在运行的 run(内存态,进程存活期内有效)
  listActive(): ModelCouncilRun[] {
    const out: ModelCouncilRun[] = [];
    this.runs.forEach((run: ModelCouncilRun): void => {
      if (run.status === 'running') out.push(this.copyRun(run));
    });
    return out.sort((a, b): number => b.updatedAtMs - a.updatedAtMs);
  }

  // 议会落地页「最近讨论」:本会话全部 run(内存态,含已结束),最新在前
  listAll(): ModelCouncilRun[] {
    const out: ModelCouncilRun[] = [];
    this.runs.forEach((run: ModelCouncilRun): void => {
      out.push(this.copyRun(run));
    });
    return out.sort((a, b): number => b.updatedAtMs - a.updatedAtMs);
  }

  read(runId: string): ModelCouncilRun | null {
    return this.snapshot(runId);
  }

  async wait(runId: string, timeoutMs: number, signal?: AbortSignalLike): Promise<ModelCouncilRun | null> {
    const cancelled = (): Error => {
      const error = new Error('council wait aborted');
      error.name = 'AbortError';
      return error;
    };
    if (signal?.aborted) throw cancelled();
    const comp: Promise<void> | undefined = this.completions.get(runId);
    if (comp !== undefined) {
      let timer: ReturnType<typeof setTimeout> | undefined;
      let onAbort: (() => void) | null = null;
      try {
        await new Promise<void>((resolve, reject): void => {
          onAbort = (): void => { reject(cancelled()); };
          signal?.addEventListener?.('abort', onAbort);
          timer = setTimeout(resolve, timeoutMs);
          comp.then(resolve, reject);
          if (signal?.aborted) onAbort();
        });
      } finally {
        if (timer !== undefined) clearTimeout(timer);
        if (onAbort !== null) signal?.removeEventListener?.('abort', onAbort);
      }
    }
    return this.snapshot(runId);
  }

  cancel(runId: string): void {
    const signal: CancelSignal | undefined = this.signals.get(runId);
    if (signal !== undefined) signal.abort();
  }

  toolSnapshot(runId: string, round: number, seatId: string): CouncilToolSnapshot | null {
    const value = this.toolSnapshots.get(councilSeatKey({ runId, round, seatId }));
    return value === undefined ? null : copyCouncilToolSnapshot(value);
  }

  subscribeTools(runId: string, listener: (snapshot: CouncilToolSnapshot) => void): () => void {
    let listeners = this.toolListeners.get(runId);
    if (listeners === undefined) {
      listeners = new Set();
      this.toolListeners.set(runId, listeners);
    }
    const target = listeners;
    target.add(listener);
    this.toolSnapshots.forEach((snapshot: CouncilToolSnapshot): void => {
      if (snapshot.key.runId === runId) listener(copyCouncilToolSnapshot(snapshot));
    });
    return (): void => { target.delete(listener); };
  }

  submitToolVerdict(request: CouncilApprovalRequest, verdict: CouncilToolVerdict): boolean {
    const run = this.runs.get(request.key.runId);
    const snapshot = this.toolSnapshots.get(councilSeatKey(request.key));
    if (run?.status !== 'running' || snapshot === undefined
      || !snapshot.pending.some(item => JSON.stringify(item) === JSON.stringify(request))) return false;
    return this.approvalWaiters.submit(request, verdict);
  }

  // 订阅某席(或 SYNTHESIZER_SEAT_KEY)的累积文本;立即回放当前值;返回取消订阅函数。
  subscribeLive(runId: string, seatKey: string, cb: (text: string) => void): () => void {
    let perRun: Map<string, Set<(text: string) => void>> | undefined = this.listeners.get(runId);
    if (perRun === undefined) {
      perRun = new Map();
      this.listeners.set(runId, perRun);
    }
    let set: Set<(text: string) => void> | undefined = perRun.get(seatKey);
    if (set === undefined) {
      set = new Set();
      perRun.set(seatKey, set);
    }
    const target: Set<(text: string) => void> = set;
    target.add(cb);
    const cur: string | undefined = this.liveText.get(runId)?.get(seatKey);
    if (cur !== undefined) cb(cur);
    return () => { target.delete(cb); };
  }

  reportMarkdown(runId: string, title: string): string {
    const run: ModelCouncilRun | undefined = this.runs.get(runId);
    if (run === undefined) return '';
    let md: string = `# ${title}\n\n`;
    md += `议题: ${run.task.objective}\n\n`;
    md += `模式: ${run.task.mode} · 轮数: ${run.task.rounds} · 状态: ${run.status}\n\n`;
    for (let i = 0; i < run.task.seats.length; i++) {
      const seat: ModelCouncilSeat = run.task.seats[i];
      md += `## ${seat.name}（${seat.role}）\n\n`;
      const turns: ModelCouncilTurn[] = run.turns.filter(t => t.seatId === seat.seatId);
      for (let j = 0; j < turns.length; j++) {
        md += `### 第 ${turns[j].round} 轮\n\n${turns[j].content}\n\n`;
        for (const source of turns[j].sources ?? []) md += `- [${source.title}](${source.url}) · ${source.service}\n`;
      }
    }
    if (run.result !== null && run.result.finalRecommendation.length > 0) {
      md += `## 综合裁决\n\n${run.result.finalRecommendation}\n`;
    }
    return md;
  }

  // ===== 内部:编排 =====

  private async executeCouncil(run: ModelCouncilRun): Promise<void> {
    const signal: CancelSignal = this.signals.get(run.runId) as CancelSignal;
    // 运行级 deadline(totalTimeoutMs 从不生效是缺陷):到期 abort 根 signal,
    // 所有席位/裁判请求级联取消,终态按 timed_out(不伪装 cancelled)
    let deadlineTimer: ReturnType<typeof setTimeout> | null = null;
    let deadlineHit: boolean = false;
    deadlineTimer = setTimeout((): void => {
      // 用户已先取消 → 保持 cancelled 语义,不被迟到的 deadline 改写
      if (!signal.aborted) {
        deadlineHit = true;
        signal.abort();
      }
    }, Math.max(this.setting.totalTimeoutMs, 1));
    try {
      await this.runRound(run, 1, signal);
      if (run.task.mode === 'debate') {
        for (let r = 2; r <= run.task.rounds; r++) {
          if (signal.aborted) break;
          if (!this.anyCompleted(run)) break;
          await this.runRound(run, r, signal);
        }
      }
      if (!signal.aborted && this.anyCompleted(run)) await this.synthesize(run, signal);

      const allCompleted: boolean = run.turns.length > 0 && run.turns.every(t => t.status === 'completed');
      const synthOk: boolean = run.result !== null && run.result.error.length === 0;
      const anyCompleted: boolean = this.anyCompleted(run);
      if (deadlineHit && signal.aborted) {
        run.status = 'timed_out';
      } else if (signal.aborted) {
        run.status = 'cancelled';
      } else if (!anyCompleted) {
        // 无任何成功席位(或 synthesize 因无输入而跳过):灾难性失败
        run.status = 'failed';
        if (run.result === null) {
          const r = makeEmptyResult();
          r.error = 'no completed turns';
          run.result = r;
        }
      } else {
        run.status = (allCompleted && synthOk) ? 'completed' : 'partial_failed';
      }
    } catch (e) {
      run.status = deadlineHit && signal.aborted ? 'timed_out' : (signal.aborted ? 'cancelled' : 'failed');
      if (run.result === null) {
        const r = makeEmptyResult();
        r.error = String(e);
        run.result = r;
      } else if (run.result.error.length === 0) {
        run.result.error = String(e);
      }
    } finally {
      if (deadlineTimer !== null) clearTimeout(deadlineTimer);
      run.updatedAtMs = this.nowMs();
      const finished: TranscriptFinished = { event: 'finished', status: run.status, run: this.copyRun(run) };
      this.writeTranscriptLine(run.runId, JSON.stringify(finished));
      await this.transcriptWrites.get(run.runId);
      const archiveErrors: string[] = this.transcriptErrors.get(run.runId) ?? [];
      if (archiveErrors.length > 0) {
        if (run.result === null) run.result = makeEmptyResult();
        run.result.warnings = [...run.result.warnings, ...archiveErrors];
      }
      this.transcriptWrites.delete(run.runId);
      this.transcriptErrors.delete(run.runId);
      // 清理本轮运行状态(runs 保留以便 UI 事后读 snapshot/reportMarkdown;
      // signals/completions/listeners 不再需要,释放引用避免泄漏)
      this.signals.delete(run.runId);
      this.completions.delete(run.runId);
      // live 文本与 listener 集合同步释放(否则每次运行泄漏整份流文本闭包)
      this.liveText.delete(run.runId);
      this.listeners.delete(run.runId);
      this.toolListeners.delete(run.runId);
    }
  }

  private anyCompleted(run: ModelCouncilRun): boolean {
    return run.turns.some(t => t.status === 'completed');
  }

  private async runRound(run: ModelCouncilRun, round: number, signal: CancelSignal): Promise<void> {
    const seats: ModelCouncilSeat[] = run.task.seats;
    const previousTurns: ModelCouncilTurn[] = run.turns.filter(turn => turn.round < round);
    const semaphores: Map<string, Semaphore> = new Map();
    const promises: Array<Promise<void>> = [];
    for (let i = 0; i < seats.length; i++) {
      const seat: ModelCouncilSeat = seats[i];
      const key: string = seat.modelId; // 按模型限流
      let sem: Semaphore | undefined = semaphores.get(key);
      if (sem === undefined) {
        sem = new Semaphore(PROVIDER_PARALLELISM);
        semaphores.set(key, sem);
      }
      const s: Semaphore = sem;
      promises.push(withPermit(s, () => this.runSeat(run, round, seat, signal, previousTurns)));
    }
    await Promise.all(promises);
  }

  private async runSeat(
    run: ModelCouncilRun, round: number, seat: ModelCouncilSeat, signal: CancelSignal,
    previousTurns: ModelCouncilTurn[],
  ): Promise<void> {
    const runId: string = run.runId;
    const model: ModelConfig | null = this.getModel(seat.modelId);
    const label: string = model !== null ? this.modelLabel(model) : seat.modelId;
    const seatKey: CouncilSeatRunKey = { runId, round, seatId: seat.seatId };
    const prefix: string = this.livePrefixFor(run, seat.seatId, round);
    this.setLive(runId, seat.seatId, prefix);

    if (signal.aborted) {
      this.appendTurn(run, this.makeTurn(round, seat, label, 'cancelled', '', 'aborted', []));
      return;
    }

    if (model === null) {
      this.appendTurn(run, this.makeTurn(round, seat, label, 'failed', '', 'model not found', []));
      return;
    }

    const isFinalRound: boolean =
      run.task.mode === 'debate' && round >= 3 && round === run.task.rounds;
    let userPrompt: string;
    if (round === 1) userPrompt = openingPrompt(run.task, seat);
    else if (isFinalRound) userPrompt = finalPositionPrompt(run.task, seat, previousTurns);
    else userPrompt = responsePrompt(run.task, seat, previousTurns);

    let cumulative: string = prefix;
    // 席位级子 signal:超时只取消本席底层请求(不动根 signal,其余席位继续);
    // 创建时继承根信号已中止状态(cancel 可先于席位启动发生)
    const seatSignal: CancelSignal = new CancelSignal();
    if (signal.aborted) seatSignal.abort();
    const cascade = (): void => { seatSignal.abort(); };
    signal.addEventListener('abort', cascade);
    let seatTimer: ReturnType<typeof setTimeout> | null = null;
    let ended: boolean = false;
    try {
      seatTimer = setTimeout((): void => { seatSignal.abort(); }, Math.max(this.setting.seatTimeoutMs, 1));
      const res = await withTimeout(raceCancel(this.runner.generate({
        model: model,
        systemPrompt: seatSystemPrompt(seat, normalizeCouncilToolMode(run.task.toolMode)),
        userPrompt: userPrompt,
        outputBudgetChars: seat.outputBudgetChars,
        reasoningLevel: seat.reasoningLevel,
        temperature: seat.temperature,
        onChunk: (text: string): void => {
          if (ended || seatSignal.aborted || signal.aborted || run.status !== 'running') return;
          cumulative = prefix + text;
          this.setLive(runId, seat.seatId, cumulative);
        },
        signal: seatSignal,
        key: seatKey,
        toolMode: normalizeCouncilToolMode(run.task.toolMode),
        onTools: (snapshot: CouncilToolSnapshot): void => {
          if (ended || seatSignal.aborted || signal.aborted || run.status !== 'running') return;
          this.publishTools(snapshot, seatKey, seatSignal);
        },
        requestApproval: (request: CouncilApprovalRequest, approvalSignal: AbortSignalLike): Promise<CouncilToolVerdict> => {
          if (ended || approvalSignal !== seatSignal || seatSignal.aborted || signal.aborted)
            return Promise.reject(new Error('seat ended'));
          return this.approvalWaiters.wait(request, seatSignal);
        },
      }), seatSignal), this.setting.seatTimeoutMs);

      if (signal.aborted) {
        this.appendTurn(run, this.makeTurn(round, seat, label, 'cancelled',
          truncate(res.text, seat.outputBudgetChars), '', res.warnings));
        return;
      }
      const content: string = truncate(res.text, seat.outputBudgetChars);
      this.setLive(runId, seat.seatId, prefix + content);
      if (content.trim().length === 0) {
        // 空文本 = 无有效产出(空流/网关异常),不得计 completed 进入裁判
        this.appendTurn(run, this.makeTurn(round, seat, label, 'failed',
          '', 'empty response text', res.warnings));
        return;
      }
      const snapshot = this.toolSnapshots.get(councilSeatKey(seatKey));
      const messages: UIMessage[] = res.toolMessages ?? snapshot?.messages ?? [];
      const unresolved: boolean = (snapshot?.pending.length ?? 0) > 0
        || messages.some(message => message.parts.some(part => part.type === 'tool' && isToolAwaitingExecution(part)));
      this.appendTurn(run, this.makeTurn(round, seat, label, unresolved ? 'failed' : 'completed',
        content, unresolved ? 'unresolved tool calls' : '', res.warnings, messages, res.sources ?? snapshot?.sources ?? []));
    } catch (e) {
      let status: ModelCouncilRunStatus;
      if (signal.aborted) status = 'cancelled';
      else if (e instanceof TimeoutError || seatSignal.aborted) status = 'timed_out';
      else status = 'failed';
      const partial: string = cumulative.length > prefix.length ? cumulative.slice(prefix.length) : '';
      this.appendTurn(run, this.makeTurn(round, seat, label, status, partial, String(e), []));
    } finally {
      ended = true;
      // Release this same seat's paused native/package owners on success or any
      // save/hash/runner failure as well as timeout. Peers use separate signals.
      seatSignal.abort();
      if (seatTimer !== null) clearTimeout(seatTimer);
      signal.removeEventListener('abort', cascade);
      this.approvalWaiters.clearSeat(seatKey);
      const snapshot = this.toolSnapshots.get(councilSeatKey(seatKey));
      if (snapshot !== undefined && snapshot.pending.length > 0) {
        snapshot.pending = [];
        this.emitTools(snapshot);
      }
    }
  }

  private async synthesize(run: ModelCouncilRun, signal: CancelSignal): Promise<void> {
    const synthId: string = resolveSynthesisModelId(this.setting, this.modelPool);
    const model: ModelConfig | null = synthId.length > 0 ? this.getModel(synthId) : null;
    if (model === null) {
      const r = makeEmptyResult();
      r.error = 'no synthesis model';
      run.result = r;
      return;
    }
    this.setLive(run.runId, SYNTHESIZER_SEAT_KEY, '');
    try {
      // 裁判同席位:子 signal(超时取消底层请求;继承根中止;结束清理)
      const synthSignal: CancelSignal = new CancelSignal();
      if (signal.aborted) synthSignal.abort();
      const synthCascade = (): void => { synthSignal.abort(); };
      signal.addEventListener('abort', synthCascade);
      let synthTimer: ReturnType<typeof setTimeout> | null = null;
      let ended: boolean = false;
      try {
        synthTimer = setTimeout((): void => { synthSignal.abort(); }, Math.max(this.setting.seatTimeoutMs, 1));
        const res = await withTimeout(raceCancel(this.runner.generate({
          model: model,
          systemPrompt: SYNTHESIZER_SYSTEM_PROMPT,
          userPrompt: synthesisPrompt(run.task, run.turns),
          outputBudgetChars: this.setting.outputBudgetChars,
          reasoningLevel: 'off',
          temperature: null,
          onChunk: (text: string): void => {
            if (!ended && !synthSignal.aborted && !signal.aborted && run.status === 'running')
              this.setLive(run.runId, SYNTHESIZER_SEAT_KEY, text);
          },
          signal: synthSignal,
          toolMode: 'off',
        }), synthSignal), this.setting.seatTimeoutMs);
        if (res.text.trim().length === 0) throw new Error('empty response text');
        const result = makeEmptyResult();
        applyVerdictText(result, res.text, res.warnings);
        result.perSeatSummaries = this.perSeatSummaries(run);
        run.result = result;
      } finally {
        ended = true;
        synthSignal.abort();
        if (synthTimer !== null) clearTimeout(synthTimer);
        signal.removeEventListener('abort', synthCascade);
      }
    } catch (e) {
      const r = makeEmptyResult();
      r.error = String(e);
      run.result = r;
    }
  }

  // ===== 内部:辅助 =====

  private perSeatSummaries(run: ModelCouncilRun): string[] {
    const out: string[] = [];
    for (let i = 0; i < run.task.seats.length; i++) {
      const seat: ModelCouncilSeat = run.task.seats[i];
      const turns: ModelCouncilTurn[] =
        run.turns.filter(t => t.seatId === seat.seatId && t.status === 'completed');
      if (turns.length === 0) continue;
      const last: ModelCouncilTurn = turns[turns.length - 1];
      out.push(`${seat.name}: ${truncate(last.content, 200)}`);
    }
    return out;
  }

  private livePrefixFor(run: ModelCouncilRun, seatId: string, round: number): string {
    if (round <= 1) return '';
    const prior: ModelCouncilTurn[] =
      run.turns.filter(t => t.seatId === seatId && t.round < round && t.content.length > 0);
    if (prior.length === 0) return '';
    let s: string = '';
    for (let i = 0; i < prior.length; i++) {
      s += `--- 第 ${prior[i].round} 轮 ---\n\n${prior[i].content}\n\n`;
    }
    return s;
  }

  private makeTurn(
    round: number, seat: ModelCouncilSeat, label: string, status: ModelCouncilRunStatus,
    content: string, error: string, warnings: string[],
    toolMessages: UIMessage[] = [], sources: CouncilSource[] = [],
  ): ModelCouncilTurn {
    const t: ModelCouncilTurn = {
      round: round, seatId: seat.seatId, seatName: seat.name, role: seat.role,
      modelId: seat.modelId, modelLabel: label, status: status,
      content: content, error: error, warnings: warnings,
      toolMessages: JSON.parse(JSON.stringify(toolMessages)) as UIMessage[],
      sources: sources.map(source => ({ title: source.title, url: source.url, service: source.service })),
    };
    return t;
  }

  private appendTurn(run: ModelCouncilRun, turn: ModelCouncilTurn): void {
    const snapshot = this.toolSnapshots.get(councilSeatKey({ runId: run.runId, round: turn.round, seatId: turn.seatId }));
    if (snapshot !== undefined) {
      if ((turn.toolMessages?.length ?? 0) === 0) turn.toolMessages = copyCouncilToolSnapshot(snapshot).messages;
      if ((turn.sources?.length ?? 0) === 0) turn.sources = copyCouncilToolSnapshot(snapshot).sources;
    }
    run.turns.push(turn);
    run.updatedAtMs = this.nowMs();
    const ev: TranscriptTurn = { event: 'turn', turn: turn };
    this.writeTranscriptLine(run.runId, JSON.stringify(ev));
  }

  private getModel(id: string): ModelConfig | null {
    const hit: ModelConfig | undefined = this.modelPool.find(m => m.id === id);
    return hit !== undefined ? hit : null;
  }

  private modelLabel(model: ModelConfig): string {
    return model.label.length > 0 ? model.label : model.model;
  }

  private setLive(runId: string, seatKey: string, text: string): void {
    let perRun: Map<string, string> | undefined = this.liveText.get(runId);
    if (perRun === undefined) {
      perRun = new Map();
      this.liveText.set(runId, perRun);
    }
    perRun.set(seatKey, text);
    const perRunL: Map<string, Set<(text: string) => void>> | undefined = this.listeners.get(runId);
    if (perRunL === undefined) return;
    const set: Set<(text: string) => void> | undefined = perRunL.get(seatKey);
    if (set === undefined) return;
    set.forEach(cb => { cb(text); });
  }

  private copyRun(run: ModelCouncilRun): ModelCouncilRun {
    const copy: ModelCouncilRun = JSON.parse(JSON.stringify(run)) as ModelCouncilRun;
    copy.task.toolMode = normalizeCouncilToolMode(copy.task.toolMode);
    for (const turn of copy.turns) {
      turn.toolMessages = turn.toolMessages ?? [];
      turn.sources = turn.sources ?? [];
    }
    return copy;
  }

  private writeTranscriptLine(runId: string, line: string): void {
    if (this.fileStore === null) return;
    const run: ModelCouncilRun | undefined = this.runs.get(runId);
    if (run === undefined) return;
    const files: FileStore = this.fileStore;
    // Ordered best-effort archive only. C's source-store save precedes every
    // approval publication; this JSONL append is never that durability barrier.
    const prior: Promise<void> = this.transcriptWrites.get(runId) ?? Promise.resolve();
    const write = prior.then(() => files.appendText(run.transcriptPath, line + '\n')).catch((error: Error): void => {
      const warnings = this.transcriptErrors.get(runId) ?? [];
      warnings.push(`council transcript write failed: ${error.message}`);
      this.transcriptErrors.set(runId, warnings);
    });
    this.transcriptWrites.set(runId, write);
  }

  private publishTools(snapshot: CouncilToolSnapshot, expected: CouncilSeatRunKey, signal: CancelSignal): void {
    if (councilSeatKey(snapshot.key) !== councilSeatKey(expected) || snapshot.conversationId.length === 0
      || !validCouncilPending(snapshot)) throw new Error('invalid council tool source');
    const copy: CouncilToolSnapshot = copyCouncilToolSnapshot(snapshot);
    this.approvalWaiters.register(copy, signal);
    this.toolSnapshots.set(councilSeatKey(expected), copy);
    this.emitTools(copy);
  }

  private emitTools(snapshot: CouncilToolSnapshot): void {
    this.toolListeners.get(snapshot.key.runId)?.forEach(listener => { listener(copyCouncilToolSnapshot(snapshot)); });
  }
}

export const createModelCouncilManager = (deps: ModelCouncilDeps): ModelCouncilManager => {
  return new ModelCouncilManager(deps);
};
