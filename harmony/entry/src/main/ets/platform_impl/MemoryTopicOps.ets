// One foreground model pass for topics, profile and confirmed duplicates.
import { batteryInfo } from '@kit.BasicServicesKit';
import type { AbortSignalLike } from '@amber/deepread-domain';
import type { MemoryProfilePassOutput, MemoryRecord, MessageChunk, OpenAIChatApi } from '@amber/chat-domain';
import {
  buildMemoryProfilePassPrompt, buildMemoryTopicPrompt, decodeMemoryTopicSuggestions, isMemoryTopicSource,
  makeTextGenerationParams, makeUserMessage, memoryProfileSources, toChatModel, toText,
  nearDuplicateCandidates, parseMemoryProfilePassOutput,
} from '@amber/chat-domain';
import type { ChatProviderChoice } from '../di/AppContainer.ets';
import { prepareProviderApi, getChatKvStore } from '../di/AppContainer.ets';
import type { MemoryWorkerGateSnapshot } from './AgentRuntimePrefs.ets';
import { loadAgentRuntimeSnapshot } from './AgentRuntimePrefs.ets';
import { sharedKvMutex } from './AtomicJsonFile.ets';
import { EntryAbortController } from './EntryAbortController.ets';
import { resolveMemoryDaydreamChoice } from './MemoryDreamOps.ets';
import { getMemoryTopicStoreOps, MemoryDocumentRefreshError } from './MemoryStore.ets';
import type { MemoryOptimizationApplyResult } from './MemoryOptimizationStore.ets';

export type MemoryTopicRunStatus = 'idle' | 'running' | 'completed' | 'skipped'
  | 'missing_model' | 'cancelled' | 'timed_out' | 'failed';
export interface MemoryTopicRunResult {
  status: MemoryTopicRunStatus;
  error: string;
  sourceSaved: boolean;
  documentError: string;
  topicCount: number;
  staleCount: number;
  profileUpdated: boolean;
  mergedCount: number;
}
export interface MemoryTopicRunState extends MemoryTopicRunResult { running: boolean; }
interface TopicDailyBudget { day: number; count: number; }
const BUDGET_KEY: string = 'memory_topic_daily_runs_v1';
const result = (status: MemoryTopicRunStatus, error: string = ''): MemoryTopicRunResult => ({
  status, error, sourceSaved: false, documentError: '', topicCount: 0, staleCount: 0,
  profileUpdated: false, mergedCount: 0,
});
const runState = (value: MemoryTopicRunResult, running: boolean): MemoryTopicRunState => ({
  running, status: value.status, error: value.error, sourceSaved: value.sourceSaved,
  documentError: value.documentError, topicCount: value.topicCount, staleCount: value.staleCount,
  profileUpdated: value.profileUpdated, mergedCount: value.mergedCount,
});

class TopicGateError extends Error {
  readonly status: MemoryTopicRunStatus;
  constructor(status: MemoryTopicRunStatus, message: string) { super(message); this.status = status; }
}

const awaitAbortable = <T>(promise: Promise<T>, signal: AbortSignalLike): Promise<T> =>
  new Promise<T>((resolve, reject): void => {
    const abort = (): void => { reject(new TopicGateError('cancelled', '主题聚合已取消')); };
    signal.addEventListener?.('abort', abort);
    promise.then(resolve, reject).finally((): void => signal.removeEventListener?.('abort', abort));
  });

export class MemoryTopicCoordinator {
  private current: MemoryTopicRunState = runState(result('idle'), false);
  private listeners: Array<(state: MemoryTopicRunState) => void> = [];
  private controller: EntryAbortController | null = null;
  private inForeground: boolean = true;
  private timedOut: boolean = false;

  state(): MemoryTopicRunState { return runState(this.current, this.current.running); }
  subscribe(listener: (state: MemoryTopicRunState) => void): () => void {
    this.listeners.push(listener); listener(this.state());
    return (): void => { this.listeners = this.listeners.filter((item): boolean => item !== listener); };
  }
  private publish(value: MemoryTopicRunResult, running: boolean): void {
    this.current = runState(value, running);
    for (const listener of this.listeners.slice()) {
      try { listener(this.state()); } catch (_error) { /* A view observer cannot change the write gate. */ }
    }
  }
  private checkActive(signal: AbortSignalLike): void {
    if (signal.aborted) throw new TopicGateError(this.timedOut ? 'timed_out' : 'cancelled',
      this.timedOut ? '主题模型请求超时，结果不会写入' : '主题聚合已取消');
    if (!this.inForeground || AppStorage.get<boolean>('appBackgrounded') === true) {
      throw new TopicGateError('cancelled', '应用已进入后台，主题结果未写入');
    }
  }
  private async checkWriteGate(signal: AbortSignalLike): Promise<MemoryWorkerGateSnapshot> {
    this.checkActive(signal);
    const worker: MemoryWorkerGateSnapshot = (await loadAgentRuntimeSnapshot(getChatKvStore())).memoryWorker;
    this.checkActive(signal);
    if (!worker.dreamModelEnabled) throw new TopicGateError('skipped', '主题模型聚合尚未开启');
    if (!worker.topicWriteEnabled) throw new TopicGateError('skipped', '主题派生写入尚未允许');
    return worker;
  }
  private charging(): boolean {
    try {
      const status: batteryInfo.BatteryChargeState = batteryInfo.chargingStatus;
      return status === batteryInfo.BatteryChargeState.ENABLE || status === batteryInfo.BatteryChargeState.FULL;
    } catch { return false; }
  }
  private async reserveDailyBudget(maximum: number): Promise<void> {
    await sharedKvMutex.withLock(BUDGET_KEY, async (): Promise<void> => {
      const now: Date = new Date(); now.setHours(0, 0, 0, 0);
      const day: number = now.getTime();
      const text: string | null = await getChatKvStore().get(BUDGET_KEY);
      const old: TopicDailyBudget | null = text === null ? null : JSON.parse(text) as TopicDailyBudget;
      if (old !== null && (!Number.isFinite(old.day) || !Number.isInteger(old.count) || old.count < 0)) {
        throw new Error('主题每日预算存储无效');
      }
      const count: number = old !== null && old.day === day ? old.count : 0;
      if (!Number.isFinite(maximum) || count >= Math.max(0, Math.floor(maximum))) {
        throw new TopicGateError('skipped', '今日自动主题聚合预算已用完');
      }
      const next: TopicDailyBudget = { day, count: count + 1 };
      await getChatKvStore().put(BUDGET_KEY, JSON.stringify(next));
    });
  }
  private async perform(manual: boolean, signal: AbortSignalLike): Promise<MemoryTopicRunResult> {
    const worker: MemoryWorkerGateSnapshot = await this.checkWriteGate(signal);
    if (!manual && worker.runOnlyOnIdle && AppStorage.get<boolean>('chatGenerating') === true) {
      return result('skipped', '聊天仍在运行，暂不自动聚合主题');
    }
    if (!manual && worker.runOnlyOnCharging && !this.charging()) return result('skipped', '设备未在充电，暂不自动聚合主题');
    const baseline: MemoryRecord[] = await getMemoryTopicStoreOps().snapshotForTopics();
    this.checkActive(signal);
    if (baseline.filter((record: MemoryRecord): boolean => isMemoryTopicSource(record, Date.now())).length < 2) {
      return result('skipped', '至少需要两条有效的非核心源记忆');
    }
    const choice: ChatProviderChoice | null = await resolveMemoryDaydreamChoice(worker);
    this.checkActive(signal);
    if (choice === null) return result('missing_model', '未找到可用的 Daydream、压缩或聊天模型');
    if (!manual) await this.reserveDailyBudget(worker.dreamMaxDailyRuns);
    await this.checkWriteGate(signal);
    const headers: Record<string, string> = {};
    for (const header of choice.model.customHeaders) {
      const name: string = header.name.trim();
      if (name.length > 0) headers[name] = header.value.trim();
    }
    const api: OpenAIChatApi = await prepareProviderApi(choice.provider, headers);
    const timer: number = setTimeout((): void => { this.timedOut = true; this.controller?.abort(); }, worker.timeoutMs);
    let chunk: MessageChunk;
    try {
      const promptNow: number = Date.now();
      const sources: MemoryRecord[] = memoryProfileSources(baseline, ['short_term', 'long_term'], promptNow);
      const prompt: string = buildMemoryTopicPrompt(baseline, promptNow) + '\n\n'
        + buildMemoryProfilePassPrompt(sources, nearDuplicateCandidates(baseline, promptNow))
        + '\n以上任务必须在同一次响应内完成，只返回一个 JSON 对象，包含三个数组：'
        + '{"topics":[],"profile":[],"duplicates":[]}。无建议的数组返回 []；不要输出多段 JSON。';
      chunk = await awaitAbortable(api.generateText([makeUserMessage(prompt)],
        makeTextGenerationParams({ model: toChatModel(choice.model), reasoningLevel: worker.daydreamReasoningLevel,
          customBody: choice.model.customBodies }),
        { signal }), signal);
    } finally { clearTimeout(timer); }
    await this.checkWriteGate(signal);
    const message = chunk.choices.length > 0 ? chunk.choices[0].message : null;
    const raw: string = message === null ? '' : toText(message);
    const suggestions = decodeMemoryTopicSuggestions(raw);
    const profilePass: MemoryProfilePassOutput | null = parseMemoryProfilePassOutput(raw);
    if (profilePass === null) throw new Error('整理结果的画像或同义去重格式无效，本次结果未写入');
    const applied: MemoryOptimizationApplyResult = await getMemoryTopicStoreOps().applyOptimization(
      baseline, suggestions, profilePass, Date.now(),
      async (): Promise<void> => { await this.checkWriteGate(signal); });
    const completed: MemoryTopicRunResult = result('completed', applied.rejectedReasons.join('；'));
    completed.sourceSaved = applied.changed; completed.topicCount = applied.topicIds.length;
    completed.staleCount = applied.staleCount;
    completed.profileUpdated = applied.profileUpdated; completed.mergedCount = applied.mergedIds.length;
    return completed;
  }
  async run(manual: boolean): Promise<MemoryTopicRunResult> {
    if (this.current.running) return result('skipped', '主题聚合已在运行');
    const controller: EntryAbortController = new EntryAbortController();
    this.controller = controller; this.timedOut = false;
    this.publish(result('running'), true);
    let outcome: MemoryTopicRunResult;
    try { outcome = await this.perform(manual, controller.signal); }
    catch (error) {
      if (error instanceof MemoryDocumentRefreshError) {
        outcome = result('failed', error.message); outcome.sourceSaved = error.sourceSaved; outcome.documentError = error.documentError;
        outcome.topicCount = error.topicResult?.topicIds.length ?? 0; outcome.staleCount = error.topicResult?.staleCount ?? 0;
      } else if (controller.signal.aborted) outcome = result(this.timedOut ? 'timed_out' : 'cancelled',
        this.timedOut ? '主题模型请求超时，结果不会写入' : '主题聚合已取消');
      else if (error instanceof TopicGateError) outcome = result(error.status, error.message);
      else outcome = result('failed', error instanceof Error ? error.message : String(error));
    }
    this.controller = null; this.publish(outcome, false);
    return outcome;
  }
  cancel(): void { this.controller?.abort(); }
  foreground(): void {
    this.inForeground = true;
    this.run(false).catch((error: Error): void => { this.publish(result('failed', error.message), false); });
  }
  background(): void { this.inForeground = false; this.cancel(); }
}

let topicCoordinator: MemoryTopicCoordinator | null = null;
export const getMemoryTopicCoordinator = (): MemoryTopicCoordinator => {
  if (topicCoordinator === null) topicCoordinator = new MemoryTopicCoordinator();
  return topicCoordinator;
};
