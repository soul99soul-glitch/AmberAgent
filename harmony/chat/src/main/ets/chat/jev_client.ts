// jev_client — Jev 协议编解码(Android core/jev JevClient.kt 对齐)
//
// 双方言:TYPESAFE body {model, state, questions};VERCEL body {state, questions}
//   (模型走 ai-model-id 头,ai-gateway-auth-method: api-key)。
// 题编码:criteria 保存 noul 的 true/false 标准或 choice 的选项说明(VERCEL 记 boolean)。
// 答解码:逐题校验 — 缺题抛;noul 取 [0,1] 有限数;choice 必须是已知选项 id。

import type { JsonObject, JsonValue } from './json.ts';
import type {
  JevAnswer, JevApiMode, JevEvaluation, JevQuestion, JevUsage,
} from './jev_models.ts';

export interface JevRequestSpec {
  url: string;
  headers: Record<string, string>;
  body: string;
}

export interface JevNoulBatchSpec {
  state: JsonObject;
  questions: Record<string, JevQuestion>;
}

// 每道 cN 题明确引用原始候选键;响应仍按相同 keys 顺序回绑。
export const buildJevNoulBatch = (
  purpose: string, query: string, keys: string[], candidates: Record<string, string>,
): JevNoulBatchSpec => {
  const questions: Record<string, JevQuestion> = {};
  const stateCandidates: JsonObject = {};
  for (let idx = 0; idx < keys.length; idx++) {
    const key: string = keys[idx];
    stateCandidates[key] = candidates[key];
    questions[`c${idx}`] = {
      kind: 'noul',
      instructions: `判断 state.candidates[${JSON.stringify(key)}] 是否与用户意图相关(用途:${purpose})。`,
      trueCriteria: '与意图直接相关,或有助于完成意图',
      falseCriteria: '与意图无关,或会干扰完成意图',
    };
  }
  return { state: { query: query.substring(0, 500), candidates: stateCandidates }, questions };
};

export const readJevNoulBatchProbabilities = (
  keys: string[], evaluation: JevEvaluation,
): Record<string, number> => {
  const probabilities: Record<string, number> = {};
  for (let idx = 0; idx < keys.length; idx++) {
    const answer: JevAnswer | undefined = evaluation.answers[`c${idx}`];
    if (answer !== undefined && answer.kind === 'noul') probabilities[keys[idx]] = answer.probability;
  }
  return probabilities;
};

const questionToJson = (q: JevQuestion, apiMode: JevApiMode): JsonObject => {
  if (q.kind === 'noul') {
    const out: JsonObject = {
      type: apiMode === 'vercel' ? 'boolean' : 'noul',
      instructions: q.instructions,
    };
    const criteria: JsonObject = {};
    if (q.trueCriteria.length > 0) criteria['true'] = q.trueCriteria;
    if (q.falseCriteria.length > 0) criteria['false'] = q.falseCriteria;
    if (Object.keys(criteria).length > 0) out['criteria'] = criteria;
    return out;
  }
  const options: JsonObject = {};
  Object.keys(q.options).forEach((id: string): void => {
    options[id] = q.options[id];
  });
  return {
    type: 'choice',
    instructions: q.instructions,
    criteria: options,
  };
};

export const buildJevRequest = (
  apiMode: JevApiMode,
  endpoint: string,
  apiKey: string,
  model: string,
  state: JsonObject,
  questions: Record<string, JevQuestion>,
): JevRequestSpec => {
  const questionsJson: JsonObject = {};
  Object.keys(questions).forEach((id: string): void => {
    questionsJson[id] = questionToJson(questions[id], apiMode);
  });
  if (apiMode === 'vercel') {
    const body: JsonObject = { state, questions: questionsJson };
    return {
      url: `${endpoint.replace(/\/+$/, '')}/evaluation-model`,
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${apiKey}`,
        'ai-model-id': model,
        'ai-gateway-auth-method': 'api-key',
        'ai-evaluation-model-specification-version': '4',
      },
      body: JSON.stringify(body),
    };
  }
  const body: JsonObject = { model, state, questions: questionsJson };
  return {
    url: endpoint,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${apiKey}`,
    },
    body: JSON.stringify(body),
  };
};

class JevDecodeError extends Error {}

const asObject = (v: JsonValue | undefined, what: string): JsonObject => {
  if (v === undefined || typeof v !== 'object' || v === null || Array.isArray(v)) {
    throw new JevDecodeError(`invalid ${what}`);
  }
  return v as JsonObject;
};

const decodeUsage = (root: JsonObject, apiMode: JevApiMode): JevUsage | null => {
  const raw = root['usage'];
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) return null;
  const obj = raw as JsonObject;
  if (apiMode === 'vercel') {
    const input = obj['inputTokens'];
    const output = obj['outputTokens'];
    if (typeof input !== 'number' || typeof output !== 'number') return null;
    return { inputTokens: input, outputTokens: output };
  }
  const input = obj['input_tokens'];
  const output = obj['output_tokens'];
  if (typeof input !== 'number' || typeof output !== 'number') return null;
  return { inputTokens: input, outputTokens: output };
};

const decodeOneAnswer = (
  id: string, question: JevQuestion, raw: JsonValue | undefined, apiMode: JevApiMode,
): JevAnswer => {
  if (raw === undefined) throw new JevDecodeError(`missing answer for question ${id}`);
  const obj: JsonObject = asObject(raw, `answer for ${id}`);
  const expectedType: string = question.kind === 'noul' && apiMode === 'vercel' ? 'boolean' : question.kind;
  if (obj['type'] !== expectedType) throw new JevDecodeError(`invalid answer type for ${id}`);
  if (question.kind === 'noul') {
    const p: JsonValue | undefined = obj[apiMode === 'vercel' ? 'probability' : 'noul'];
    if (typeof p !== 'number' || !Number.isFinite(p) || p < 0 || p > 1) {
      throw new JevDecodeError(`invalid noul probability for ${id}`);
    }
    return { kind: 'noul', probability: p };
  }
  const selected: JsonValue | undefined = obj['choice'];
  if (typeof selected !== 'string' || Object.keys(question.options).indexOf(selected) < 0) {
    throw new JevDecodeError(`invalid choice selection for ${id}`);
  }
  return { kind: 'choice', selected };
};

export const decodeJevResponse = (
  bodyText: string,
  questions: Record<string, JevQuestion>,
  apiMode: JevApiMode,
): JevEvaluation => {
  let rootRaw: JsonValue;
  try {
    rootRaw = JSON.parse(bodyText) as JsonValue;
  } catch (_e) {
    throw new JevDecodeError('response is not valid JSON');
  }
  const root: JsonObject = asObject(rootRaw, 'response');
  const answersObject: JsonObject = asObject(root['answers'], 'answers');
  const answers: Record<string, JevAnswer> = {};
  Object.keys(questions).forEach((id: string): void => {
    answers[id] = decodeOneAnswer(id, questions[id], answersObject[id], apiMode);
  });
  const modelRaw = root['model'];
  return {
    answers,
    usage: decodeUsage(root, apiMode),
    model: typeof modelRaw === 'string' ? modelRaw : '',
  };
};
