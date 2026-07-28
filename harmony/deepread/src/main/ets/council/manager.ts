// council/manager — 模型议会编排器(移植自 Android ModelCouncilManager)
//
// 流程:第 1 轮各席并行独立作答;DEBATE 模式 2..N 轮喂前轮摘要,末轮(finalPosition);
// 最后裁判模型综合(synthesize)。同一模型并发用 Semaphore(PROVIDER_PARALLELISM) 限流。
// 每席实时文本经回调订阅(非轮询)。可选写 JSONL 转录。纯逻辑,Node 下 mock runner 可测。

import type { ModelConfig } from '../domain/model_config.ts';
import type { FileStore } from '../platform/files.ts';
import type { AbortSignalLike } from '../platform/runtime_api.ts';
import type { ModelCouncilTextRunner } from './runner.ts';
import { Semaphore, withPermit } from './semaphore.ts';
import {
  PROVIDER_PARALLELISM, SYNTHESIZER_SEAT_KEY, makeEmptyResult,
} from './models.ts';
import type {
  ModelCouncilRun, ModelCouncilSeat, ModelCouncilTurn, ModelCouncilRunStatus,
  ModelCouncilRuntimeSetting,
} from './models.ts';
import {
  seatSystemPrompt, openingPrompt, responsePrompt, finalPositionPrompt,
  synthesisPrompt, SYNTHESIZER_SYSTEM_PROMPT, truncate,
} from './prompts.ts';
import { parseTask, resolveSynthesisModelId } from './validator.ts';
import type { CouncilTaskInput } from './validator.ts';

// ===== 超时 / 取消 工具 =====
class TimeoutError extends Error {
  constructor(msg: string) {
    super(msg);
    this.name = 'TimeoutError';
  }
}

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
  abort(): void {
    if (this.aborted) return;
    this.aborted = true;
    for (let i = 0; i < this.cbs.length; i++) this.cbs[i]();
  }
}

// 让模型调用与取消信号赛跑:即使 runner 没监听 abort,取消也能立即生效。
const raceCancel = <T>(p: Promise<T>, signal: CancelSignal): Promise<T> => {
  if (signal.aborted) return Promise.reject(new Error('aborted'));
  return new Promise<T>((resolve, reject) => {
    signal.addEventListener('abort', () => { reject(new Error('aborted')); });
    p.then(resolve, reject);
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
interface TranscriptStarted { event: string; runId: string; mode: string; }
interface TranscriptTurn { event: string; turn: ModelCouncilTurn; }
interface TranscriptFinished { event: string; status: string; }

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

  constructor(deps: ModelCouncilDeps) {
    this.runner = deps.runner;
    this.fileStore = deps.fileStore;
    this.modelPool = deps.modelPool;
    this.setting = deps.setting;
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
    const started: TranscriptStarted = { event: 'started', runId: runId, mode: spec.mode };
    this.writeTranscriptLine(runId, JSON.stringify(started));
    this.completions.set(runId, this.executeCouncil(run));
    return this.copyRun(run);
  }

  snapshot(runId: string): ModelCouncilRun | null {
    const run: ModelCouncilRun | undefined = this.runs.get(runId);
    return run !== undefined ? this.copyRun(run) : null;
  }

  read(runId: string): ModelCouncilRun | null {
    return this.snapshot(runId);
  }

  async wait(runId: string, timeoutMs: number): Promise<ModelCouncilRun | null> {
    const comp: Promise<void> | undefined = this.completions.get(runId);
    if (comp !== undefined) {
      let timer: ReturnType<typeof setTimeout> | undefined;
      await Promise.race([
        comp,
        new Promise<void>(res => { timer = setTimeout(res, timeoutMs); }),
      ]);
      if (timer !== undefined) clearTimeout(timer);
    }
    return this.snapshot(runId);
  }

  cancel(runId: string): void {
    const signal: CancelSignal | undefined = this.signals.get(runId);
    if (signal !== undefined) signal.abort();
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
      if (signal.aborted) {
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
      run.status = signal.aborted ? 'cancelled' : 'failed';
      if (run.result === null) {
        const r = makeEmptyResult();
        r.error = String(e);
        run.result = r;
      } else if (run.result.error.length === 0) {
        run.result.error = String(e);
      }
    } finally {
      run.updatedAtMs = this.nowMs();
      const finished: TranscriptFinished = { event: 'finished', status: run.status };
      this.writeTranscriptLine(run.runId, JSON.stringify(finished));
      // 清理本轮运行状态(runs 保留以便 UI 事后读 snapshot/reportMarkdown;
      // signals/completions/listeners 不再需要,释放引用避免泄漏)
      this.signals.delete(run.runId);
      this.completions.delete(run.runId);
    }
  }

  private anyCompleted(run: ModelCouncilRun): boolean {
    return run.turns.some(t => t.status === 'completed');
  }

  private async runRound(run: ModelCouncilRun, round: number, signal: CancelSignal): Promise<void> {
    const seats: ModelCouncilSeat[] = run.task.seats;
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
      promises.push(withPermit(s, () => this.runSeat(run, round, seat, signal)));
    }
    await Promise.all(promises);
  }

  private async runSeat(
    run: ModelCouncilRun, round: number, seat: ModelCouncilSeat, signal: CancelSignal,
  ): Promise<void> {
    const runId: string = run.runId;
    const model: ModelConfig | null = this.getModel(seat.modelId);
    const label: string = model !== null ? this.modelLabel(model) : seat.modelId;
    const prefix: string = this.livePrefixFor(run, seat.seatId, round);
    this.setLive(runId, seat.seatId, prefix);

    if (model === null) {
      this.appendTurn(run, this.makeTurn(round, seat, label, 'failed', '', 'model not found', []));
      return;
    }

    const isFinalRound: boolean =
      run.task.mode === 'debate' && round >= 3 && round === run.task.rounds;
    let userPrompt: string;
    if (round === 1) userPrompt = openingPrompt(run.task, seat);
    else if (isFinalRound) userPrompt = finalPositionPrompt(run.task, seat, run.turns);
    else userPrompt = responsePrompt(run.task, seat, run.turns);

    let cumulative: string = prefix;
    try {
      const res = await withTimeout(raceCancel(this.runner.generate({
        model: model,
        systemPrompt: seatSystemPrompt(seat),
        userPrompt: userPrompt,
        outputBudgetChars: seat.outputBudgetChars,
        reasoningLevel: seat.reasoningLevel,
        temperature: seat.temperature,
        onChunk: (text: string): void => {
          cumulative = prefix + text;
          this.setLive(runId, seat.seatId, cumulative);
        },
        signal: signal,
      }), signal), this.setting.seatTimeoutMs);

      if (signal.aborted) {
        this.appendTurn(run, this.makeTurn(round, seat, label, 'cancelled',
          truncate(res.text, seat.outputBudgetChars), '', res.warnings));
        return;
      }
      const content: string = truncate(res.text, seat.outputBudgetChars);
      this.setLive(runId, seat.seatId, prefix + content);
      this.appendTurn(run, this.makeTurn(round, seat, label, 'completed', content, '', res.warnings));
    } catch (e) {
      let status: ModelCouncilRunStatus;
      if (signal.aborted) status = 'cancelled';
      else if (e instanceof TimeoutError) status = 'timed_out';
      else status = 'failed';
      const partial: string = cumulative.length > prefix.length ? cumulative.slice(prefix.length) : '';
      this.appendTurn(run, this.makeTurn(round, seat, label, status, partial, String(e), []));
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
      const res = await withTimeout(raceCancel(this.runner.generate({
        model: model,
        systemPrompt: SYNTHESIZER_SYSTEM_PROMPT,
        userPrompt: synthesisPrompt(run.task, run.turns),
        outputBudgetChars: this.setting.outputBudgetChars,
        reasoningLevel: 'off',
        temperature: null,
        onChunk: (text: string): void => { this.setLive(run.runId, SYNTHESIZER_SEAT_KEY, text); },
        signal: signal,
      }), signal), this.setting.seatTimeoutMs);
      const result = makeEmptyResult();
      result.finalRecommendation = res.text;
      result.warnings = res.warnings;
      result.perSeatSummaries = this.perSeatSummaries(run);
      run.result = result;
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
  ): ModelCouncilTurn {
    const t: ModelCouncilTurn = {
      round: round, seatId: seat.seatId, seatName: seat.name, role: seat.role,
      modelId: seat.modelId, modelLabel: label, status: status,
      content: content, error: error, warnings: warnings,
    };
    return t;
  }

  private appendTurn(run: ModelCouncilRun, turn: ModelCouncilTurn): void {
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
    const copy: ModelCouncilRun = {
      runId: run.runId, status: run.status, mode: run.mode,
      seats: run.seats.slice(), task: run.task, turns: run.turns.slice(),
      result: run.result, transcriptPath: run.transcriptPath,
      startedAtMs: run.startedAtMs, updatedAtMs: run.updatedAtMs,
    };
    return copy;
  }

  private writeTranscriptLine(runId: string, line: string): void {
    if (this.fileStore === null) return;
    const run: ModelCouncilRun | undefined = this.runs.get(runId);
    if (run === undefined) return;
    // 转录失败不应阻塞编排,但要吞掉异常避免 unhandled rejection
    this.fileStore.appendText(run.transcriptPath, line + '\n').catch((): void => { /* 磁盘满/权限,忽略 */ });
  }
}

export const createModelCouncilManager = (deps: ModelCouncilDeps): ModelCouncilManager => {
  return new ModelCouncilManager(deps);
};
