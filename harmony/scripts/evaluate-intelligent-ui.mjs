#!/usr/bin/env node

// P0-only model generation probe. Run with the repository's existing tsx:
//   harmony/chat/node_modules/.bin/tsx harmony/scripts/evaluate-intelligent-ui.mjs
// It reads the existing local model config, but never prints or persists credentials.

import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const CONFIG_PATH = process.env.MIMOCODE_CONFIG ?? path.join(os.homedir(), '.config', 'mimocode', 'mimocode.jsonc');
const FIXTURE_PATH = path.join(ROOT, 'harmony', 'chat', 'src', 'test', 'fixtures', 'intelligent-ui', 'p0-model-cases.json');
const OUTPUT_PATH = process.env.EVAL_OUTPUT_PATH ?? path.join(ROOT, 'docs', 'reviews', '2026-10-10-intelligent-ui', 'p0', 'model-evaluation.json');
const MAX_TOKENS = 2500;
const REQUEST_TIMEOUT_MS = 90_000;
const SSE_IDLE_TIMEOUT_MS = 20_000;
const MAX_STREAM_CHARS = 1_048_576;
const MAX_OUTPUT_CHARS = 64_000;
const MODEL_IDS = { qwen: 'qwen3.8-max', glm: 'glm-5.3', mify: 'moonshot/kimi-k3' };
const REQUESTED_PROVIDERS = new Set((process.env.EVAL_PROVIDERS ?? 'qwen,glm').split(',').map((item) => item.trim()).filter(Boolean));

const SYSTEM_PROMPT = `你是 Amber 的智能 UI 生成器，正在参加协议兼容性评估。只输出用户任务需要的协议围栏和极少量解释文字，不要输出思考过程、HTML、JS、React、Three.js 源码、工具调用、密钥或请求头。

智能 UI 使用以下严格格式，围栏头必须原样是 \`\`\`amber-ui v=1 id=<ascii-id>\\n，正文每行一个完整 JSON 对象，围栏末尾是 \`\`\`：
- 记录 op 只有 state、node、end；end 记录只能是 {"op":"end"}。
- state 只允许 {"op":"state","id":"...","initial":<字符串/数字/布尔值>}。
- node 的 type 只能是 column、text、choice、input、button、asset；node 通用字段只有 op、id、parent、visibleWhen。
- column 除通用字段没有额外字段；text 只能增加 text；choice 只能增加 state 和 options；input 只能增加 state 和可选 placeholder；button 只能增加 label 和 action；asset 只能增加 ref。
- id 和 parent/state/ref 使用 ASCII ID，必须以字母开头，最多 48 个字符，只能含字母、数字、下划线、连字符；根 column 省略 parent 或使用 null。
- visibleWhen 只能是 {"state":"state_id","equals":<字符串/数字/布尔值>}，不支持表达式；options 只能是非空数组 [{"value":<字符串/数字/布尔值>,"label":"非空文字"}]。
- button.action 只能是 {"type":"draft","text":"...{{state_id}}..."}，占位符只能引用本块已声明的状态；asset.ref 只引用同一回答里的图形 id。
- 每块只能有一个根 column；父节点必须先出现；state 必须先声明；最后必须有且只有一个 end；不添加 CSS、HTML、JS、表达式或未知字段。
- 用户状态和动作由宿主持有。按钮只填入草稿，绝不自动发送、执行工具或产生外部副作用。

独立图形使用独立围栏，不要把图形 XML 或场景 JSON 塞进智能 UI 的 JSON 字符串：
- 二维图形使用 \`\`\`amber-svg v=1 id=<ascii-id>\\n，然后是一个完整闭合的 SVG 根元素。禁止 script、事件属性、foreignObject、外部资源、外部 URL 和宿主 bridge。
- 三维图形使用 \`\`\`amber-three v=1 id=<ascii-id>\\n，然后是一个 JSON 对象 {"objects":[...]}。对象只能是 box、sphere、cylinder、cone、torus、plane，必须有 id、color、position、rotation、scale、size；不要输出 Three.js 代码、CDN、纹理 URL 或外部模型。
- 三维场景最多 48 个对象；对象 id 最多 48 个字符且唯一；color 必须是 #RRGGBB；position 和 rotation 都是长度 3 的有限数字且每项绝对值不超过 100；scale 是长度 3 的正数且每项不超过 10；size 必须是正数且每项不超过 40。size 维度为 box=3、sphere=1、cylinder/cone/torus/plane=2。
- 需要图形时，智能 UI 用 asset.ref 指向图形 id；图形只在来源位置输出一次。

请依据用户请求自行选择最小的完整内容。保持中文内容简洁，避免为了填充而增加无关节点。`;

const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);

const stripJsonc = (source) => {
  let out = '';
  let quote = false;
  let escaped = false;
  let lineComment = false;
  let blockComment = false;
  for (let i = 0; i < source.length; i += 1) {
    const c = source[i];
    const n = source[i + 1];
    if (lineComment) {
      if (c === '\n') { lineComment = false; out += c; }
      else out += ' ';
      continue;
    }
    if (blockComment) {
      if (c === '*' && n === '/') { blockComment = false; out += '  '; i += 1; }
      else out += c === '\n' ? '\n' : ' ';
      continue;
    }
    if (quote) {
      out += c;
      if (escaped) escaped = false;
      else if (c === '\\') escaped = true;
      else if (c === '"') quote = false;
      continue;
    }
    if (c === '"') { quote = true; out += c; continue; }
    if (c === '/' && n === '/') { lineComment = true; out += '  '; i += 1; continue; }
    if (c === '/' && n === '*') { blockComment = true; out += '  '; i += 1; continue; }
    out += c;
  }
  return out.replace(/,\s*([}\]])/g, '$1');
};

const readJsonc = async (filePath) => JSON.parse(stripJsonc(await fs.readFile(filePath, 'utf8')));

const resolveConfigValue = (value) => {
  if (typeof value !== 'string') return '';
  const exactEnv = value.match(/^\$\{([A-Za-z_][A-Za-z0-9_]*)\}$/);
  if (exactEnv) return process.env[exactEnv[1]] ?? '';
  return value;
};

const loadProvider = (config, providerId, modelId) => {
  const provider = config?.provider?.[providerId];
  const options = provider?.options;
  const baseUrl = resolveConfigValue(options?.baseURL).replace(/\/$/, '');
  const apiKey = resolveConfigValue(options?.apiKey);
  if (!baseUrl || !apiKey) return null;
  const configuredModel = provider?.models?.[modelId];
  if (!configuredModel) return null;
  return { providerId, modelId, baseUrl, apiKey };
};

const safeText = (value) => String(value ?? '')
  .replace(/Bearer\s+[A-Za-z0-9._~+/=-]+/gi, 'Bearer <redacted>')
  .replace(/(api[-_]?key|authorization|token|secret|password)\s*([:=])\s*["']?[^,}\n"']+/gi, '$1$2<redacted>')
  .replace(/\b(sk|key|token)-[A-Za-z0-9._-]{16,}\b/g, '<redacted>');

const boundedOutput = (value) => safeText(value).slice(0, 24_000);

const readResponseText = async (response) => {
  const reader = response.body?.getReader();
  if (!reader) throw new EvalError('transport', 'response body is not readable');
  const decoder = new TextDecoder();
  let text = '';
  let completed = false;
  try {
    while (true) {
      const readResult = await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new EvalError('transport', 'response body idle timeout')), SSE_IDLE_TIMEOUT_MS);
        reader.read().then((value) => { clearTimeout(timer); resolve(value); }, (error) => { clearTimeout(timer); reject(error); });
      });
      text += decoder.decode(readResult.value ?? new Uint8Array(), { stream: !readResult.done });
      if (text.length > MAX_STREAM_CHARS) throw new EvalError('output_limit', 'nonstream capture cap reached');
      if (readResult.done) { completed = true; break; }
    }
    return text;
  } finally {
    if (!completed) reader.cancel().catch(() => {});
  }
};

const classifyFailure = (error) => {
  if (error?.kind) return error.kind;
  const message = String(error?.message ?? error);
  if (/401|403|api.?key|unauthori[sz]|forbidden/i.test(message)) return 'provider_auth';
  if (/429|rate.?limit|quota|too many/i.test(message)) return 'provider_rate_limit';
  if (/timeout|fetch failed|network|socket|ECONN|ENOTFOUND|aborted/i.test(message)) return 'transport';
  return 'provider';
};

class EvalError extends Error {
  constructor(kind, message, details = {}) {
    super(message);
    this.kind = kind;
    this.details = details;
  }
}

const parseSse = async (response) => {
  const reader = response.body?.getReader();
  if (!reader) throw new EvalError('transport', 'response body is not readable');
  const decoder = new TextDecoder();
  let buffer = '';
  let content = '';
  let reasoningChars = 0;
  let finishReason = null;
  let usage = null;
  let sawDone = false;
  let eventCount = 0;
  let receivedChars = 0;
  let completed = false;
  const consume = (line) => {
    if (!line.startsWith('data:')) return;
    receivedChars += line.length;
    if (receivedChars > MAX_STREAM_CHARS) throw new EvalError('output_limit', 'stream capture cap reached');
    const raw = line.slice(5).trim();
    if (!raw) return;
    if (raw === '[DONE]') { sawDone = true; return; }
    let payload;
    try { payload = JSON.parse(raw); }
    catch (error) { throw new EvalError('transport', `invalid SSE JSON: ${error.message}`); }
    eventCount += 1;
    if (!isObject(payload)) return;
    const choices = Array.isArray(payload.choices) ? payload.choices : [];
    for (const choice of choices) {
      if (!isObject(choice)) continue;
      if (typeof choice.finish_reason === 'string' && choice.finish_reason.length > 0) {
        finishReason = choice.finish_reason;
      }
      const delta = isObject(choice.delta) ? choice.delta : null;
      const message = isObject(choice.message) ? choice.message : null;
      const source = delta ?? message;
      if (source && typeof source.content === 'string') {
        content += source.content;
        if (content.length > MAX_OUTPUT_CHARS) throw new EvalError('output_limit', 'assistant content cap reached');
      }
      if (source && typeof source.reasoning_content === 'string') reasoningChars += source.reasoning_content.length;
      if (source && typeof source.reasoning === 'string') reasoningChars += source.reasoning.length;
    }
    if (isObject(payload.usage)) usage = payload.usage;
  };
  try {
    while (true) {
      const readResult = await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new EvalError('transport', 'SSE idle timeout')), SSE_IDLE_TIMEOUT_MS);
        reader.read().then((value) => { clearTimeout(timer); resolve(value); }, (error) => { clearTimeout(timer); reject(error); });
      });
      const { value, done } = readResult;
      buffer += decoder.decode(value ?? new Uint8Array(), { stream: !done });
      if (buffer.length > MAX_STREAM_CHARS) throw new EvalError('output_limit', 'stream buffer cap reached');
      let newline = buffer.indexOf('\n');
      while (newline >= 0) {
        const line = buffer.slice(0, newline).replace(/\r$/, '');
        buffer = buffer.slice(newline + 1);
        consume(line);
        newline = buffer.indexOf('\n');
      }
      if (done) { completed = true; break; }
    }
    if (buffer.trim()) consume(buffer.trim());
    return { content, reasoningChars, finishReason, usage, sawDone, eventCount };
  } finally {
    if (!completed) reader.cancel().catch(() => {});
  }
};

const callModel = async (provider, messages, maxTokens = MAX_TOKENS) => {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  const startedAt = Date.now();
  let response = null;
  try {
    const requestBody = { model: provider.modelId, messages, stream: true, max_tokens: maxTokens, stream_options: { include_usage: true } };
    if (provider.providerId === 'glm') {
      requestBody.thinking = { type: 'disabled' };
      requestBody.reasoning_effort = 'none';
    } else if (provider.providerId === 'mify') {
      requestBody.reasoning_effort = 'low';
    }
    response = await fetch(`${provider.baseUrl}/chat/completions`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${provider.apiKey}`, 'Content-Type': 'application/json' },
      body: JSON.stringify(requestBody),
      signal: controller.signal,
    });
    if (!response.ok) {
      const body = boundedOutput(await readResponseText(response));
      const kind = response.status === 401 || response.status === 403 ? 'provider_auth' :
        response.status === 429 ? 'provider_rate_limit' : 'provider';
      throw new EvalError(kind, `HTTP ${response.status}: ${body}`, { status: response.status });
    }
    const stream = await new Promise((resolve, reject) => {
      const overallTimer = setTimeout(() => reject(new EvalError('transport', 'overall response timeout')), REQUEST_TIMEOUT_MS);
      parseSse(response).then((value) => { clearTimeout(overallTimer); resolve(value); }, (error) => { clearTimeout(overallTimer); reject(error); });
    });
    return { ...stream, terminal: stream.sawDone && stream.finishReason !== null, mode: 'stream', durationMs: Date.now() - startedAt };
  } catch (error) {
    if (error instanceof EvalError) throw error;
    throw new EvalError(classifyFailure(error), safeText(error?.message ?? error));
  } finally {
    clearTimeout(timer);
    if (response?.body?.locked) response.body.cancel().catch(() => {});
  }
};

const callModelNonStream = async (provider, messages, maxTokens = MAX_TOKENS) => {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  const startedAt = Date.now();
  let response = null;
  try {
    const requestBody = { model: provider.modelId, messages, stream: false, max_tokens: maxTokens };
    if (provider.providerId === 'glm') {
      requestBody.thinking = { type: 'disabled' };
      requestBody.reasoning_effort = 'none';
    } else if (provider.providerId === 'mify') {
      requestBody.reasoning_effort = 'low';
    }
    response = await fetch(`${provider.baseUrl}/chat/completions`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${provider.apiKey}`, 'Content-Type': 'application/json' },
      body: JSON.stringify(requestBody),
      signal: controller.signal,
    });
    const raw = boundedOutput(await readResponseText(response));
    let payload;
    try { payload = JSON.parse(raw); } catch (error) { throw new EvalError('transport', `invalid response JSON: ${error.message}`); }
    if (!response.ok) {
      const kind = response.status === 401 || response.status === 403 ? 'provider_auth' :
        response.status === 429 ? 'provider_rate_limit' : 'provider';
      throw new EvalError(kind, `HTTP ${response.status}: ${raw}`, { status: response.status });
    }
    const choice = Array.isArray(payload.choices) && isObject(payload.choices[0]) ? payload.choices[0] : null;
    const message = choice && isObject(choice.message) ? choice.message : {};
    const content = typeof message.content === 'string' ? message.content : '';
    const reasoning = typeof message.reasoning_content === 'string' ? message.reasoning_content :
      typeof message.reasoning === 'string' ? message.reasoning : '';
    const finishReason = typeof choice?.finish_reason === 'string' ? choice.finish_reason : null;
    return {
      content,
      reasoningChars: reasoning.length,
      finishReason,
      usage: isObject(payload.usage) ? payload.usage : null,
      sawDone: false,
      terminal: finishReason !== null,
      mode: 'nonstream_fallback',
      durationMs: Date.now() - startedAt,
    };
  } catch (error) {
    if (error instanceof EvalError) throw error;
    throw new EvalError(classifyFailure(error), safeText(error?.message ?? error));
  } finally {
    clearTimeout(timer);
    if (response?.body?.locked) response.body.cancel().catch(() => {});
  }
};

const fenceBlocks = (output) => {
  const blocks = [];
  const header = /```(amber-ui|amber-svg|amber-three)\s+v=1\s+id=([A-Za-z][A-Za-z0-9_-]{0,47})[ \t]*\r?\n/g;
  let match;
  while ((match = header.exec(output)) !== null) {
    const close = output.indexOf('```', header.lastIndex);
    const closed = close >= 0;
    const end = closed ? close : output.length;
    blocks.push({ kind: match[1], id: match[2], body: output.slice(header.lastIndex, end), closed });
    header.lastIndex = closed ? close + 3 : output.length;
  }
  return blocks;
};

const safeSvg = (body) => {
  const value = body.trim();
  if (!/^<svg(?:\s|>)/i.test(value) || !/<\/svg>\s*$/i.test(value)) return { ok: false, reason: 'svg_root_or_closure' };
  if (/<script\b|<foreignObject\b|\son[a-z]+\s*=|(?:href|src)\s*=\s*["']https?:|url\s*\(\s*["']?https?:/i.test(value)) {
    return { ok: false, reason: 'svg_unsafe_content' };
  }
  return { ok: true, root: 'svg' };
};

const evaluateBlocks = async (output, caseItem, protocol, three) => {
  const blocks = fenceBlocks(output);
  const ui = blocks.find((item) => item.kind === 'amber-ui');
  const visual = blocks.filter((item) => item.kind === 'amber-svg' || item.kind === 'amber-three');
  const expected = caseItem.expected;
  const requiresUi = caseItem.category !== 'asset';
  const requiresVisual = caseItem.category === 'asset' || caseItem.category === 'combination';
  const result = {
    uiStatus: requiresUi ? 'missing' : 'not_required', uiPass: !requiresUi,
    visualStatus: requiresVisual ? 'missing' : 'not_required', visualPass: !requiresVisual,
    pass: false, failureClass: null, diagnostics: [],
    blockKinds: blocks.map((item) => `${item.kind}:${item.id}:${item.closed ? 'closed' : 'open'}`),
  };
  if (ui === undefined && requiresUi) {
    result.failureClass = output.length >= 23_000 ? 'output_limit' : 'format';
    return result;
  }
  if (ui !== undefined && !ui.closed && requiresUi) {
    result.failureClass = 'output_limit';
    result.uiStatus = 'partial';
    return result;
  }
  let document = null;
  if (ui !== undefined && ui.closed) {
    document = protocol.parseSmartUi(ui.body, ui.id, true);
    result.uiStatus = document.status;
    result.diagnostics = document.diagnostics.slice(0, 12).map((item) => ({ code: item.code, line: item.line, severity: item.severity }));
  }
  if (requiresUi && document !== null) {
    const nodeTypes = document.nodes.map((node) => node.type);
    const hasTypes = expected.nodeTypes.every((type) => nodeTypes.includes(type));
    // State IDs are model-local. Only the required number and valid references are contractual.
    const hasStates = document.states.length >= expected.stateIds.length;
    const stateIds = new Set(document.states.map((state) => state.id));
    const choiceNodes = document.nodes.filter((node) => node.type === 'choice');
    const inputNodes = document.nodes.filter((node) => node.type === 'input');
    const validDraft = document.nodes.some((node) => {
      if (node.type !== 'button' || node.action?.type !== 'draft') return false;
      const refs = [...node.action.text.matchAll(/\{\{([A-Za-z][A-Za-z0-9_-]{0,47})\}\}/g)].map((item) => item[1]);
      return refs.every((id) => stateIds.has(id));
    });
    const validVisibility = document.nodes.some((node) => {
      if (node.visibleWhen === undefined || !stateIds.has(node.visibleWhen.state)) return false;
      const state = document.states.find((item) => item.id === node.visibleWhen.state);
      const choice = choiceNodes.find((item) => item.state === node.visibleWhen.state);
      const values = choice?.options?.map((item) => item.value) ?? [];
      return state !== undefined && (state.initial === node.visibleWhen.equals || values.includes(node.visibleWhen.equals));
    });
    const assertionChecks = {
      choice: !expected.assertions.includes('choice') || choiceNodes.some((node) => stateIds.has(node.state) && (node.options?.length ?? 0) > 0),
      input: !expected.assertions.includes('input') || inputNodes.some((node) => stateIds.has(node.state)),
      draft: !expected.assertions.includes('draft') || validDraft,
      visibleWhen: !expected.assertions.includes('visibleWhen') || validVisibility,
    };
    result.uiPass = document.status === 'ready' && document.rootId !== null && hasTypes && hasStates && Object.values(assertionChecks).every(Boolean);
    if (!result.uiPass) {
      result.failureClass = 'format';
      if (document.status === 'ready' && (!hasTypes || !hasStates || Object.values(assertionChecks).some((value) => !value))) result.failureClass = 'content_interaction';
    }
  }
  if (requiresVisual) {
    const refs = document === null ? [] : document.nodes.filter((node) => node.type === 'asset').map((node) => node.ref).filter(Boolean);
    const expectedVisualKind = caseItem.category === 'asset' && caseItem.id === 'asset-04' ? 'amber-three' : caseItem.category === 'asset' ? 'amber-svg' : null;
    const validVisualKinds = expectedVisualKind === null || visual.some((item) => item.kind === expectedVisualKind);
    if (visual.length === 0 || (caseItem.category === 'combination' && (refs.length === 0 || !refs.every((ref) => visual.some((item) => item.id === ref)))) || !validVisualKinds) {
      result.visualStatus = 'missing';
      result.visualPass = false;
    } else {
      const visualResults = [];
      for (const item of visual) {
        if (!item.closed) { visualResults.push({ id: item.id, kind: item.kind, ok: false, reason: 'unclosed' }); continue; }
        if (item.kind === 'amber-svg') visualResults.push({ id: item.id, kind: item.kind, ...safeSvg(item.body) });
        else {
          try {
            const scene = JSON.parse(item.body.trim());
            const normalized = three.normalizeThreeScene(scene);
            visualResults.push({ id: item.id, kind: item.kind, ok: normalized !== null, reason: normalized === null ? 'three_bounds_or_shape' : undefined });
          } catch { visualResults.push({ id: item.id, kind: item.kind, ok: false, reason: 'three_json' }); }
        }
      }
      result.visualStatus = visualResults.every((item) => item.ok) ? 'ready' : 'error';
      result.visualPass = visualResults.some((item) => item.ok);
    }
    if (!result.visualPass && result.failureClass === null) result.failureClass = 'content_interaction';
  }
  result.pass = result.uiPass && result.visualPass;
  return result;
};

const caseMessages = (prompt) => [{ role: 'system', content: SYSTEM_PROMPT }, { role: 'user', content: prompt }];

const submittedPrompt = (item) => {
  if (item.category === 'choice') return `${item.prompt}。请用原生 choice 选择器切换相关说明，并提供一个只填入草稿的继续按钮。`;
  if (item.category === 'input') return `${item.prompt}。请提供 input 控件和一个只填入草稿的按钮，使用输入值生成下一步说明。`;
  if (item.category === 'combination') return `${item.prompt}。请提供原生 choice/input 操作区，引用一份独立图示，并用按钮只填入草稿。`;
  return `${item.prompt}。请只生成独立的 SVG 或 Three 场景协议，不要生成智能 UI；二维图示用 amber-svg，三维空间对象用 amber-three。`;
};

const runModel = async (provider, fixtures, protocol, three) => {
  const modelResult = { provider: provider.providerId, model: provider.modelId, transportMode: 'stream', pilot: null, cases: [], skipped: false };
  try {
    let pilot;
    const pilotPrompt = '请生成一个最小的证书链智能 UI，包含一个可选择根证书或服务器证书的选择器，以及一个继续解释按钮。';
    try {
      // GLM 的 coding endpoint 已实测 streaming 会持续输出直到捕获上限；保留该事实，后续用一次非流式请求完成内容验收。
      if (process.env.EVAL_STREAM_ONLY === '1') {
        pilot = await callModel(provider, caseMessages(pilotPrompt), 512);
      } else if (provider.providerId === 'glm' || provider.providerId === 'mify') {
        modelResult.transportMode = 'nonstream_fallback';
        if (provider.providerId === 'glm') {
          modelResult.streamFailure = { failureClass: 'output_limit', error: 'GLM streaming pilot previously exceeded the 16 KiB harness capture cap; this is not provider output proof' };
        } else {
          modelResult.probeNote = 'Existing Mify provider evaluated via nonstream pilot to keep the second-provider probe bounded.';
        }
        pilot = await callModelNonStream(provider, caseMessages(pilotPrompt), provider.providerId === 'mify' ? 6000 : 256);
      } else {
        pilot = await callModel(provider, caseMessages(pilotPrompt), 512);
      }
    } catch (streamError) {
      // One explicit alternate protocol probe records a stream incompatibility without blind retries.
      if (provider.providerId !== 'glm' || !['output_limit', 'transport'].includes(classifyFailure(streamError))) throw streamError;
      modelResult.streamFailure = { failureClass: classifyFailure(streamError), error: boundedOutput(streamError.message) };
      pilot = await callModelNonStream(provider, caseMessages(pilotPrompt), provider.providerId === 'mify' ? 6000 : 256);
      modelResult.transportMode = 'nonstream_fallback';
    }
    modelResult.pilot = { ok: pilot.terminal && pilot.finishReason === 'stop', mode: pilot.mode, finishReason: pilot.finishReason, sawDone: pilot.sawDone, terminal: pilot.terminal, durationMs: pilot.durationMs, output: boundedOutput(pilot.content), reasoningChars: pilot.reasoningChars };
    if (!modelResult.pilot.ok) {
      modelResult.skipped = true;
      modelResult.skipReason = pilot.sawDone ? 'pilot_no_finish_reason' : 'pilot_transport';
      return modelResult;
    }
    if (process.env.EVAL_PILOT_ONLY === '1') {
      modelResult.summary = { total: 0, pass: 0, uiPass: 0, visualPass: 0, evaluated: 0, availableRate: 0, failures: {} };
      return modelResult;
    }
  } catch (error) {
    modelResult.pilot = { ok: false, failureClass: classifyFailure(error), error: boundedOutput(error.message) };
    modelResult.skipped = true;
    modelResult.skipReason = 'pilot_failed';
    return modelResult;
  }
  for (const item of fixtures) {
    const userText = submittedPrompt(item);
    const row = { id: item.id, category: item.category, prompt: item.prompt, userText, finishReason: null, sawDone: false, durationMs: 0, output: '', evaluation: null };
    try {
      const response = modelResult.transportMode === 'nonstream_fallback'
        ? await callModelNonStream(provider, caseMessages(userText))
        : await callModel(provider, caseMessages(userText));
      row.finishReason = response.finishReason;
      row.sawDone = response.sawDone;
      row.terminal = response.terminal;
      row.mode = response.mode;
      row.durationMs = response.durationMs;
      row.output = boundedOutput(response.content);
      row.reasoningChars = response.reasoningChars;
      row.evaluation = await evaluateBlocks(response.content, item, protocol, three);
      if (!response.terminal && row.evaluation.failureClass === null) row.evaluation.failureClass = 'transport';
      if (response.finishReason === 'length' && !row.evaluation.pass) row.evaluation.failureClass = 'output_limit';
    } catch (error) {
      row.failureClass = classifyFailure(error);
      row.error = boundedOutput(error.message);
    }
    modelResult.cases.push(row);
  }
  const evaluated = modelResult.cases.filter((item) => item.evaluation !== null);
  modelResult.summary = {
    total: fixtures.length,
    pass: modelResult.cases.filter((item) => item.evaluation?.pass === true).length,
    uiPass: modelResult.cases.filter((item) => item.evaluation?.uiPass === true).length,
    visualPass: modelResult.cases.filter((item) => item.evaluation?.visualPass === true).length,
    evaluated: evaluated.length,
    availableRate: modelResult.cases.length === 0 ? 0 : modelResult.cases.filter((item) => item.evaluation?.pass === true).length / modelResult.cases.length,
    failures: modelResult.cases.reduce((counts, item) => {
      const kind = item.failureClass ?? item.evaluation?.failureClass;
      if (kind) counts[kind] = (counts[kind] ?? 0) + 1;
      return counts;
    }, {}),
  };
  return modelResult;
};

const sha256 = (value) => createHash('sha256').update(String(value), 'utf8').digest('hex');

const requestParametersFor = (model) => ({
  provider: model.provider,
  model: model.model,
  caseTransport: model.transportMode,
  caseMaxTokens: MAX_TOKENS,
  pilotMaxTokens: model.provider === 'mify' ? 6000 : 256,
  reasoning: model.provider === 'glm' ? { thinking: 'disabled', reasoning_effort: 'none' } :
    model.provider === 'mify' ? { reasoning_effort: 'low' } : { unspecified: true },
  systemPromptVersion: 'p0-v1',
  auth: 'existing local provider config; credential value omitted',
});

const summarizeModel = (model) => {
  const evaluated = model.cases.filter((item) => item.evaluation !== null);
  model.summary = {
    total: model.cases.length,
    pass: model.cases.filter((item) => item.evaluation?.pass === true).length,
    uiPass: model.cases.filter((item) => item.evaluation?.uiPass === true).length,
    visualPass: model.cases.filter((item) => item.evaluation?.visualPass === true).length,
    evaluated: evaluated.length,
    availableRate: model.cases.length === 0 ? 0 : model.cases.filter((item) => item.evaluation?.pass === true).length / model.cases.length,
    failures: model.cases.reduce((counts, item) => {
      const kind = item.failureClass ?? item.evaluation?.failureClass;
      if (kind) counts[kind] = (counts[kind] ?? 0) + 1;
      return counts;
    }, {}),
  };
};

const addStableEvidence = (report, fixtures) => {
  report.evaluationVersion = 'p0-replay-v2';
  report.requestParameters = report.models.map(requestParametersFor);
  report.fixtureManifest = fixtures.map((item, index) => ({
    index,
    id: item.id,
    category: item.category,
    promptHash: sha256(item.prompt),
    submittedUserTextHash: sha256(submittedPrompt(item)),
  }));
  for (const model of report.models) {
    model.requestParameters = requestParametersFor(model);
    for (const item of model.cases) {
      item.promptHash = sha256(item.prompt);
      item.submittedUserTextHash = sha256(item.userText);
      item.rawOutput = item.output;
      item.outputField = 'rawOutput';
    }
  }
  return report;
};

const replayReport = async (inputPath, outputPath) => {
  const report = JSON.parse(await fs.readFile(inputPath, 'utf8'));
  const fixtures = JSON.parse(await fs.readFile(FIXTURE_PATH, 'utf8'));
  const fixtureById = new Map(fixtures.map((item) => [item.id, item]));
  const protocol = await import(pathToFileURL(path.join(ROOT, 'harmony', 'chat', 'src', 'main', 'ets', 'chat', 'smart_ui', 'protocol.ts')).href);
  const three = await import(pathToFileURL(path.join(ROOT, 'harmony', 'chat', 'src', 'main', 'ets', 'chat', 'three_scene.ts')).href);
  for (const model of report.models ?? []) {
    for (const item of model.cases ?? []) {
      const fixture = fixtureById.get(item.id);
      if (fixture === undefined) continue;
      item.evaluation = await evaluateBlocks(item.output ?? item.rawOutput ?? '', fixture, protocol, three);
      item.evaluationVersion = 'p0-replay-v2';
    }
    summarizeModel(model);
  }
  addStableEvidence(report, fixtures);
  await fs.writeFile(outputPath, `${JSON.stringify(report, null, 2)}\n`, 'utf8');
  console.log(JSON.stringify({ output: path.relative(ROOT, outputPath), models: report.models.map((item) => ({ provider: item.provider, model: item.model, summary: item.summary })) }, null, 2));
};

const selfCheck = async () => {
  const protocol = await import(pathToFileURL(path.join(ROOT, 'harmony', 'chat', 'src', 'main', 'ets', 'chat', 'smart_ui', 'protocol.ts')).href);
  const three = await import(pathToFileURL(path.join(ROOT, 'harmony', 'chat', 'src', 'main', 'ets', 'chat', 'three_scene.ts')).href);
  const choiceCase = { id: 'self-choice', category: 'choice', expected: { nodeTypes: ['column', 'choice', 'text', 'button'], stateIds: ['any'], assertions: ['choice', 'draft', 'visibleWhen'] } };
  const validUi = '```amber-ui v=1 id=self_choice\n' +
    '{"op":"state","id":"selected","initial":"a"}\n' +
    '{"op":"node","id":"root","type":"column"}\n' +
    '{"op":"node","id":"choice","parent":"root","type":"choice","state":"selected","options":[{"value":"a","label":"A"},{"value":"b","label":"B"}]}\n' +
    '{"op":"node","id":"detail","parent":"root","type":"text","text":"A","visibleWhen":{"state":"selected","equals":"a"}}\n' +
    '{"op":"node","id":"draft","parent":"root","type":"button","label":"继续","action":{"type":"draft","text":"解释 {{selected}}"}}\n' +
    '{"op":"end"}\n```';
  const valid = await evaluateBlocks(validUi, choiceCase, protocol, three);
  assert.equal(valid.pass, true, 'valid choice contract must pass');
  const invalid = await evaluateBlocks(validUi.replace('{"op":"node","id":"choice"', '{"id":"choice"'), choiceCase, protocol, three);
  assert.equal(invalid.pass, false, 'missing node op must fail');
  const assetCase = { id: 'self-svg', category: 'asset', expected: { nodeTypes: [], stateIds: [], assertions: ['asset'] } };
  const validSvg = await evaluateBlocks('```amber-svg v=1 id=self_svg\n<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><rect width="10" height="10" fill="#ffffff"/></svg>\n```', assetCase, protocol, three);
  assert.equal(validSvg.pass, true, 'safe closed SVG must pass');
  console.log(JSON.stringify({ selfCheck: 'ok', checks: 3 }));
};

const main = async () => {
  const config = await readJsonc(CONFIG_PATH);
  const fixtures = JSON.parse(await fs.readFile(FIXTURE_PATH, 'utf8'));
  const protocol = await import(pathToFileURL(path.join(ROOT, 'harmony', 'chat', 'src', 'main', 'ets', 'chat', 'smart_ui', 'protocol.ts')).href);
  const three = await import(pathToFileURL(path.join(ROOT, 'harmony', 'chat', 'src', 'main', 'ets', 'chat', 'three_scene.ts')).href);
  const providers = Object.entries(MODEL_IDS)
    .filter(([id]) => REQUESTED_PROVIDERS.has(id))
    .map(([id, modelId]) => loadProvider(config, id, modelId)).filter(Boolean);
  const results = await Promise.all(providers.map((provider) => runModel(provider, fixtures, protocol, three)));
  const report = {
    generatedAt: new Date().toISOString(),
    configSource: path.basename(CONFIG_PATH),
    fixtureSource: path.relative(ROOT, FIXTURE_PATH),
    systemPromptVersion: 'p0-v1',
    maxTokens: MAX_TOKENS,
    concurrency: 2,
    fixtureCount: fixtures.length,
    models: results,
  };
  addStableEvidence(report, fixtures);
  await fs.mkdir(path.dirname(OUTPUT_PATH), { recursive: true });
  await fs.writeFile(OUTPUT_PATH, `${JSON.stringify(report, null, 2)}\n`, 'utf8');
  console.log(JSON.stringify({ output: path.relative(ROOT, OUTPUT_PATH), models: results.map((item) => ({ provider: item.provider, model: item.model, pilot: item.pilot, skipped: item.skipped, summary: item.summary })) }, null, 2));
};

const dispatch = async () => {
  if (process.env.EVAL_SELF_CHECK === '1') return selfCheck();
  if (process.env.EVAL_REPLAY_INPUT) {
    const output = process.env.EVAL_REPLAY_OUTPUT ?? process.env.EVAL_REPLAY_INPUT;
    return replayReport(process.env.EVAL_REPLAY_INPUT, output);
  }
  return main();
};

dispatch().catch((error) => {
  console.error(JSON.stringify({ error: classifyFailure(error), message: boundedOutput(error?.message ?? error) }));
  process.exitCode = 1;
});

