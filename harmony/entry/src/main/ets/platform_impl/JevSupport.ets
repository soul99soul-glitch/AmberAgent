// JevSupport — Jev 运行时(entry 侧;Android JevDecisionCoordinator 对齐 v1)
//
// 职责:设置读取 → 预算门(日 200 次/错误冷却 5min)→ 协议调用(httpClient)→
//   SHADOW 只记录指标不影响返回 / ACTIVE 返回评估结果。
// 用途接线由 JEV_WIRED_PURPOSES 如实列出;Web 自动化等待有限候选宿主。

import type { JsonObject } from '@amber/chat-domain';
import {
  loadJevSettings, makeJevSettings,
  JEV_TYPESAFE_ENDPOINT, JEV_VERCEL_DEFAULT_BASE,
  buildJevRequest, decodeJevResponse, resolveJevPurposeMode, jevPurposeConsentUnchanged, jevPurposeSettings,
  buildJevNoulBatch, readJevNoulBatchProbabilities,
  type JevPurpose, type JevEvaluation, type JevMode, type JevQuestion, type JevSettings, type JevNoulBatchSpec, type JevEvaluateResult,
} from '@amber/chat-domain';
import type { AbortSignalLike, HttpResponse } from '@amber/deepread-domain';
import { EntryAbortController } from './EntryAbortController.ets';
import { getAppContainer, getChatKvStore } from '../di/AppContainer.ets';
import type { ToolSearchReranker, ScoredTool } from '@amber/chat-domain';
import type { AgentTool, UIMessagePart, JsonValue, InputSchemaObj } from '@amber/chat-domain';
import { makeAgentTool, makeInputSchemaObj } from '@amber/chat-domain';

export type { JevEvaluateResult } from '@amber/chat-domain';

const JEV_DAILY_BUDGET: number = 200;
const JEV_ERROR_COOLDOWN_MS: number = 5 * 60 * 1000;
const JEV_STATE_MAX_CHARS: number = 4000;

const budgetDayKey = (): string => `jev_budget/${new Date().toISOString().substring(0, 10)}`;
const JEV_COOLDOWN_KEY: string = 'jev_cooldown_until';
const JEV_LAST_ERROR_KEY: string = 'jev_last_error';

export interface JevRuntimeStatus {
  mode: JevMode;
  apiMode: string;
  todayCount: number;
  budget: number;
  cooldownUntil: number;
  lastError: string;
}

export const loadJevRuntimeStatus = async (): Promise<JevRuntimeStatus> => {
  const kv = getChatKvStore();
  const settings: JevSettings = await loadJevSettings(kv);
  const todayRaw: string | null = await kv.get(budgetDayKey());
  const cooldownRaw: string | null = await kv.get(JEV_COOLDOWN_KEY);
  const lastError: string | null = await kv.get(JEV_LAST_ERROR_KEY);
  return {
    mode: settings.mode,
    apiMode: settings.apiMode,
    todayCount: todayRaw !== null ? Number(todayRaw) || 0 : 0,
    budget: JEV_DAILY_BUDGET,
    cooldownUntil: cooldownRaw !== null ? Number(cooldownRaw) || 0 : 0,
    lastError: lastError ?? '',
  };
};

let budgetTail: Promise<void> = Promise.resolve();
const reserveBudget = (cancelled: () => boolean): Promise<string> => {
  const reservation: Promise<string> = budgetTail.then(async (): Promise<string> => {
    if (cancelled()) return 'cancelled';
    const status: JevRuntimeStatus = await loadJevRuntimeStatus();
    if (cancelled()) return 'cancelled';
    if (status.cooldownUntil > Date.now()) return 'cooldown';
    if (status.todayCount >= JEV_DAILY_BUDGET) return 'budget_exhausted';
    await getChatKvStore().put(budgetDayKey(), `${status.todayCount + 1}`);
    return '';
  });
  budgetTail = reservation.then((): void => {}, (): void => {});
  return reservation;
};

const noteError = async (message: string): Promise<void> => {
  const kv = getChatKvStore();
  await kv.put(JEV_LAST_ERROR_KEY, message.substring(0, 200));
  await kv.put(JEV_COOLDOWN_KEY, `${Date.now() + JEV_ERROR_COOLDOWN_MS}`);
};

const skipped = (reason: string): JevEvaluateResult => ({ ok: false, reason, evaluation: null, shadow: false });
const hasPurposeConsent = (settings: JevSettings, purpose: JevPurpose | null): boolean => {
  if (purpose === null) return true;
  const scopes = jevPurposeSettings(settings, purpose);
  if (purpose === 'tool_context_selection') return scopes !== null && scopes.allowToolOutput;
  if (purpose === 'context_retention') return scopes !== null && scopes.allowToolOutput;
  if (purpose === 'auto_approval') return scopes !== null && scopes.allowToolMetadata;
  if (purpose === 'completion_check') return scopes !== null && scopes.allowTaskText;
  return true;
};

const evaluateOperation = async (
  purpose: JevPurpose | null, state: JsonObject, questions: Record<string, JevQuestion>, signal?: AbortSignalLike, expectedSettings?: JevSettings,
): Promise<JevEvaluateResult> => {
  const cancelled = (): boolean => signal?.aborted === true;
  if (cancelled()) return skipped('cancelled');
  const settings: JevSettings = await loadJevSettings(getChatKvStore());
  if (expectedSettings !== undefined && !jevPurposeConsentUnchanged(expectedSettings, settings, purpose ?? 'tool_discovery')) return skipped('settings_changed');
  const effective: JevMode = purpose === null ? settings.mode : resolveJevPurposeMode(settings, purpose);
  if (cancelled()) return skipped('cancelled');
  if (effective === 'off') return skipped('off');
  if (!hasPurposeConsent(settings, purpose)) return skipped('consent');
  if (settings.apiKey.length === 0) return skipped('no_api_key');
  if (settings.model.length === 0) return skipped('no_model');
  if (JSON.stringify(state).length > JEV_STATE_MAX_CHARS) return skipped('state_too_large');
  const endpoint: string = settings.apiMode === 'vercel'
    ? (settings.baseUrl.length > 0 ? settings.baseUrl : JEV_VERCEL_DEFAULT_BASE) : JEV_TYPESAFE_ENDPOINT;
  try {
    const reason: string = await reserveBudget(cancelled);
    if (reason.length > 0) return skipped(reason);
    const current: JevSettings = await loadJevSettings(getChatKvStore());
    if (cancelled()) return skipped('cancelled');
    if (!jevPurposeConsentUnchanged(settings, current, purpose ?? 'tool_discovery')) return skipped('settings_changed');
    const spec = buildJevRequest(settings.apiMode, endpoint, settings.apiKey, settings.model, state, questions);
    const request: Promise<HttpResponse> = getAppContainer().httpClient.fetch({
      url: spec.url, method: 'POST', headers: spec.headers, body: spec.body,
    }, { signal });
    const resp: HttpResponse = await request;
    if (cancelled()) return skipped('cancelled');
    const latest: JevSettings = await loadJevSettings(getChatKvStore());
    if (cancelled()) return skipped('cancelled');
    if (!jevPurposeConsentUnchanged(settings, latest, purpose ?? 'tool_discovery')) return skipped('settings_changed');
    if (resp.status < 200 || resp.status >= 300) {
      await noteError(`HTTP ${resp.status}`);
      return skipped(`http_${resp.status}`);
    }
    const evaluation: JevEvaluation = decodeJevResponse(resp.body, questions, settings.apiMode);
    return { ok: true, reason: 'ok', evaluation, shadow: effective === 'shadow' };
  } catch (error) {
    if (cancelled()) return skipped('cancelled');
    await noteError(error instanceof Error ? error.message : String(error));
    return skipped('transport_or_decode');
  }
};

// The single deadline covers settings reads and queued reservations as well as transport.
const evaluate = async (
  purpose: JevPurpose | null, state: JsonObject, questions: Record<string, JevQuestion>, signal?: AbortSignalLike, expectedSettings?: JevSettings,
): Promise<JevEvaluateResult> => {
  if (signal?.aborted === true) return skipped('cancelled');
  const controller: EntryAbortController = new EntryAbortController();
  let timedOut: boolean = false;
  let stop: () => void = (): void => {};
  const stopped: Promise<JevEvaluateResult> = new Promise((resolve): void => {
    stop = (): void => { controller.abort(); resolve(skipped(timedOut ? 'deadline' : 'cancelled')); };
  });
  const relay = (): void => { stop(); };
  signal?.addEventListener?.('abort', relay);
  const timer: number = setTimeout((): void => { timedOut = true; stop(); }, 1200);
  try {
    return await Promise.race([evaluateOperation(purpose, state, questions, controller.signal, expectedSettings), stopped]);
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener?.('abort', relay);
  }
};

// Existing global-purpose callers keep their previous scope and mode behavior.
export const jevEvaluate = (state: JsonObject, questions: Record<string, JevQuestion>, signal?: AbortSignalLike): Promise<JevEvaluateResult> =>
  evaluate(null, state, questions, signal);
export const jevEvaluatePurpose = (purpose: JevPurpose, state: JsonObject, questions: Record<string, JevQuestion>, signal?: AbortSignalLike, expectedSettings?: JevSettings): Promise<JevEvaluateResult> =>
  evaluate(purpose, state, questions, signal, expectedSettings);

// ── TOOL_DISCOVERY:tool_search 语义重排(ACTIVE 改序,SHADOW 原序+记录) ──

export interface RerankCandidate {
  name: string;
  description: string;
}

// 返回候选名的新顺序(第一个 = Jev 选中);不可用时返回 null(调用方回退词面序)
export const jevRerankToolCandidates = async (
  query: string, candidates: RerankCandidate[],
): Promise<string[] | null> => {
  if (candidates.length === 0) return null;
  const options: Record<string, string> = {};
  for (const c of candidates) {
    options[c.name] = c.description.substring(0, 160);
  }
  const questions: Record<string, JevQuestion> = {
    'best': {
      kind: 'choice',
      instructions: '给定用户意图与候选工具列表,选出最能完成该意图的那一个工具。',
      options,
    },
  };
  const state: JsonObject = { query: query.substring(0, 500) };
  const result: JevEvaluateResult = await jevEvaluate(state, questions);
  if (!result.ok || result.evaluation === null) return null;
  if (result.shadow) return null; // 影子模式不改序(只花预算记录)
  const answer = result.evaluation.answers['best'];
  if (answer === undefined || answer.kind !== 'choice') return null;
  const selected: string = answer.selected;
  const rest: string[] = candidates
    .map((c: RerankCandidate): string => c.name)
    .filter((n: string): boolean => n !== selected);
  return [selected, ...rest];
};

// tool_search 语义重排器(ChatPage 注入 createToolSearchTool;off/shadow/失败 → 原序)
export const jevToolSearchReranker = (): ToolSearchReranker =>
  async (query: string, matches: ScoredTool[]): Promise<ScoredTool[]> => {
    const candidates: RerankCandidate[] = matches.map(
      (s: ScoredTool): RerankCandidate => ({
        name: s.metadata.name,
        description: s.tool.description,
      }));
    const order: string[] | null = await jevRerankToolCandidates(query, candidates);
    if (order === null) return matches;
    const byName: Map<string, ScoredTool> = new Map<string, ScoredTool>();
    for (const m of matches) byName.set(m.metadata.name, m);
    const reordered: ScoredTool[] = [];
    for (const name of order) {
      const hit: ScoredTool | undefined = byName.get(name);
      if (hit !== undefined) reordered.push(hit);
    }
    return reordered.length > 0 ? reordered : matches;
  };

// ── D5-1:通用 noul 批量评估 / choice 单选(记忆召回/上下文选择/模型路由共用) ──

export interface JevNoulBatchResult {
  // 候选 id → 相关概率(仅含 Jev 实际回答的项)
  probabilities: Record<string, number>;
  shadow: boolean;
}

// 一次调用,每候选一道 noul 题(keys 显式保序;off/shadow/失败 → null 或 shadow 标记)
export const jevNoulBatch = async (
  purpose: string, query: string, keys: string[], candidates: Record<string, string>,
): Promise<JevNoulBatchResult | null> => {
  if (keys.length === 0) return null;
  const batch: JevNoulBatchSpec = buildJevNoulBatch(purpose, query, keys, candidates);
  const result: JevEvaluateResult = await jevEvaluate(batch.state, batch.questions);
  if (!result.ok || result.evaluation === null) return null;
  return { probabilities: readJevNoulBatchProbabilities(keys, result.evaluation), shadow: result.shadow };
};

export interface JevChoiceResult {
  selected: string;
  shadow: boolean;
}

// 单选(选项 id → 说明);off/shadow/失败 → null 或 shadow 标记
export const jevChoiceSelect = async (
  purpose: string, query: string, options: Record<string, string>,
): Promise<JevChoiceResult | null> => {
  const questions: Record<string, JevQuestion> = {};
  questions['best'] = {
    kind: 'choice',
    instructions: `选出最能完成该意图的一项(用途:${purpose})。`,
    options,
  };
  const state: JsonObject = { query: query.substring(0, 500) };
  const result: JevEvaluateResult = await jevEvaluate(state, questions);
  if (!result.ok || result.evaluation === null) return null;
  const answer = result.evaluation.answers['best'];
  if (answer === undefined || answer.kind !== 'choice') return null;
  return { selected: answer.selected, shadow: result.shadow };
};

// 用途标识(设置页/jev_status 如实展示)
export const JEV_WIRED_PURPOSES: string[] = [
  'tool_discovery', 'memory_recall', 'context_selection', 'model_routing', 'approval_triage',
  'tool_context_selection', 'context_retention', 'auto_approval', 'completion_check',
];

// jev_status 工具(只读;Android JevStatusTool 对齐)
export const createJevStatusTool = (): AgentTool =>
  makeAgentTool({
    name: 'jev_status',
    description: 'Report Jev evaluation service status: mode, api dialect, daily budget usage, '
      + 'cooldown and last error. Read-only.',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({} as JsonObject, null),
    execute: async (_input: JsonValue): Promise<UIMessagePart[]> => {
      const st: JevRuntimeStatus = await loadJevRuntimeStatus();
      const payload: JsonObject = {
        mode: st.mode,
        api_mode: st.apiMode,
        today_count: st.todayCount,
        budget: st.budget,
        cooldown_active: st.cooldownUntil > Date.now(),
        last_error: st.lastError,
        wired_purposes: JEV_WIRED_PURPOSES.join(','),
      };
      return [{ type: 'text', text: JSON.stringify(payload), metadata: null }];
    },
  });
