// JevSupport — Jev 运行时(entry 侧;Android JevDecisionCoordinator 对齐 v1)
//
// 职责:设置读取 → 预算门(日 200 次/错误冷却 5min)→ 协议调用(httpClient)→
//   SHADOW 只记录指标不影响返回 / ACTIVE 返回评估结果。
// 本批接线用途:TOOL_DISCOVERY(tool_search 语义重排);其余用途(记忆召回/上下文
//   选择/模型路由/自动化)登记未接 — 设置面不展示假开关。

import type { JsonObject } from '@amber/chat-domain';
import {
  loadJevSettings, makeJevSettings,
  JEV_TYPESAFE_ENDPOINT, JEV_VERCEL_DEFAULT_BASE,
  buildJevRequest, decodeJevResponse,
  buildJevNoulBatch, readJevNoulBatchProbabilities,
  type JevEvaluation, type JevMode, type JevQuestion, type JevSettings, type JevNoulBatchSpec,
} from '@amber/chat-domain';
import { getAppContainer, getChatKvStore } from '../di/AppContainer.ets';
import type { ToolSearchReranker, ScoredTool } from '@amber/chat-domain';
import type { AgentTool, UIMessagePart, JsonValue, InputSchemaObj } from '@amber/chat-domain';
import { makeAgentTool, makeInputSchemaObj } from '@amber/chat-domain';

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

const bumpBudget = async (): Promise<void> => {
  const kv = getChatKvStore();
  const raw: string | null = await kv.get(budgetDayKey());
  const count: number = raw !== null ? Number(raw) || 0 : 0;
  await kv.put(budgetDayKey(), `${count + 1}`);
};

const noteError = async (message: string): Promise<void> => {
  const kv = getChatKvStore();
  await kv.put(JEV_LAST_ERROR_KEY, message.substring(0, 200));
  await kv.put(JEV_COOLDOWN_KEY, `${Date.now() + JEV_ERROR_COOLDOWN_MS}`);
};

export interface JevEvaluateResult {
  ok: boolean;
  reason: string;
  evaluation: JevEvaluation | null;
  shadow: boolean;
}

// 统一评估入口(off/预算/冷却 → skipped 语义;失败回退调用方本地路径)
export const jevEvaluate = async (
  state: JsonObject,
  questions: Record<string, JevQuestion>,
): Promise<JevEvaluateResult> => {
  const kv = getChatKvStore();
  const settings: JevSettings = await loadJevSettings(kv);
  if (settings.mode === 'off') return { ok: false, reason: 'off', evaluation: null, shadow: false };
  if (settings.apiKey.length === 0) return { ok: false, reason: 'no_api_key', evaluation: null, shadow: false };
  if (settings.model.length === 0) return { ok: false, reason: 'no_model', evaluation: null, shadow: false };
  const status: JevRuntimeStatus = await loadJevRuntimeStatus();
  if (status.todayCount >= JEV_DAILY_BUDGET) {
    return { ok: false, reason: 'budget_exhausted', evaluation: null, shadow: false };
  }
  if (status.cooldownUntil > Date.now()) {
    return { ok: false, reason: 'cooldown', evaluation: null, shadow: false };
  }
  const stateJson: string = JSON.stringify(state);
  if (stateJson.length > JEV_STATE_MAX_CHARS) {
    return { ok: false, reason: 'state_too_large', evaluation: null, shadow: false };
  }
  const endpoint: string = settings.apiMode === 'vercel'
    ? (settings.baseUrl.length > 0 ? settings.baseUrl : JEV_VERCEL_DEFAULT_BASE)
    : JEV_TYPESAFE_ENDPOINT;
  try {
    const spec = buildJevRequest(
      settings.apiMode, endpoint, settings.apiKey, settings.model, state, questions);
    const resp = await getAppContainer().httpClient.fetch({
      url: spec.url,
      method: 'POST',
      headers: spec.headers,
      body: spec.body,
    }, { signal: undefined });
    if (resp.status < 200 || resp.status >= 300) {
      await noteError(`HTTP ${resp.status}`);
      return { ok: false, reason: `http_${resp.status}`, evaluation: null, shadow: false };
    }
    const evaluation: JevEvaluation = decodeJevResponse(resp.body, questions, settings.apiMode);
    await bumpBudget();
    return {
      ok: true,
      reason: 'ok',
      evaluation,
      shadow: settings.mode === 'shadow',
    };
  } catch (e) {
    await noteError(e instanceof Error ? e.message : String(e));
    return { ok: false, reason: 'transport_or_decode', evaluation: null, shadow: false };
  }
};

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
  'tool_discovery', 'memory_recall', 'context_selection', 'model_routing',
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
