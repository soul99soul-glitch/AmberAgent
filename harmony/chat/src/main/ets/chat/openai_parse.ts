// OpenAI Chat Completions 流式解析 — 纯逻辑,零平台依赖
//
// Android 基准(ChatCompletionsAPI.kt / ErrorParser.kt)的忠实移植:
//   - parseMessage(:739-811)          → parseChatCompletionMessage
//   - parseAnnotations(:813-829)      → parseOpenAiAnnotations
//   - parseTokenUsage(:832-849)       → parseOpenAiTokenUsage
//   - ErrorParser.parseErrorDetail    → parseOpenAiErrorDetail
//   - streamText onEvent 组装(:199-256) → parseOpenAiStreamEventData
//
// 与 Android 的唯一增补:OpenAiStreamError 携带原始 error payload(诊断用),
// 抛出信号的 message 与 Android HttpException 完全一致。

import type { JsonObject, JsonValue } from './json.ts';
import {
  makeUIMessage,
} from './message.ts';
import type {
  MessageRole, UIMessage, UIMessageAnnotation, UIMessagePart, UIMessagePartTool,
  UIMessageChoice, MessageChunk,
} from './message.ts';
import { nowIso } from './ids.ts';
import type { TokenUsage } from './usage.ts';
import {
  STREAM_TOOL_INDEX_METADATA_KEY, REASONING_CONTENT_PRESENT_METADATA_KEY,
} from './tool_merge.ts';
import { normalizeOpenAIStreamDataLines } from './openai_normalize.ts';

// ===== JsonValue 窄化助手(kotlinx jsonPrimitiveOrNull/jsonObjectOrNull 对应) =====

const isObj = (v: JsonValue | undefined): v is JsonObject =>
  typeof v === 'object' && v !== null && !Array.isArray(v);

const strOrNull = (v: JsonValue | undefined): string | null =>
  typeof v === 'string' ? v : null;

const intOrNull = (v: JsonValue | undefined): number | null =>
  typeof v === 'number' && Number.isInteger(v) ? v : null;

// ===== parseChatCompletionMessage(ChatCompletionsAPI.kt:739-811) =====

const ROLE_MAP: Record<string, MessageRole> = {
  SYSTEM: 'system',
  USER: 'user',
  ASSISTANT: 'assistant',
  TOOL: 'tool',
};

export const parseChatCompletionMessage = (obj: JsonObject): UIMessage => {
  // MessageRole.valueOf(uppercase):未知 role 抛错,缺失默认 ASSISTANT
  const rawRole: string = (strOrNull(obj['role']) ?? 'ASSISTANT').toUpperCase();
  const role: MessageRole | undefined = ROLE_MAP[rawRole];
  if (role === undefined) {
    throw new Error(`unknown message role: ${rawRole}`);
  }

  // content 仅取 primitive string;数组形态(Mistral)不进正文
  const content: string = strOrNull(obj['content']) ?? '';
  const hasReasoningContent: boolean = 'reasoning_content' in obj;
  let reasoning: string | null = strOrNull(obj['reasoning_content']) ?? strOrNull(obj['reasoning']);
  if (reasoning === null) {
    // Mistral 接口:content 为数组 [{type:"thinking",thinking:[{type:"text",text}]}]
    const contentArr: JsonValue | undefined = obj['content'];
    if (Array.isArray(contentArr)) {
      const first: JsonValue | undefined = contentArr[0];
      if (isObj(first)) {
        const thinking: JsonValue | undefined = first['thinking'];
        if (Array.isArray(thinking) && isObj(thinking[0])) {
          reasoning = strOrNull(thinking[0]['text']);
        }
      }
    }
  }

  const toolCalls: JsonValue[] = Array.isArray(obj['tool_calls']) ? obj['tool_calls'] : [];
  const images: JsonValue[] = Array.isArray(obj['images']) ? obj['images'] : [];

  const parts: UIMessagePart[] = [];

  if (hasReasoningContent || (reasoning !== null && reasoning.length > 0)) {
    parts.push({
      type: 'reasoning',
      reasoning: reasoning ?? '',
      createdAt: nowIso(),
      finishedAt: null,
      metadata: hasReasoningContent
        ? { [REASONING_CONTENT_PRESENT_METADATA_KEY]: true }
        : null,
    });
  }

  for (const tc of toolCalls) {
    if (!isObj(tc)) continue;
    const type: string | null = strOrNull(tc['type']);
    if (type !== null && type.length > 0 && type !== 'function') {
      throw new Error(`tool call type not supported: ${type}`);
    }
    const index: number | null = intOrNull(tc['index']);
    const fn: JsonValue | undefined = tc['function'];
    const fnObj: JsonObject | null = isObj(fn) ? fn : null;
    const tool: UIMessagePartTool = {
      type: 'tool',
      toolCallId: strOrNull(tc['id']) ?? '',
      toolName: fnObj !== null ? (strOrNull(fnObj['name']) ?? '') : '',
      input: fnObj !== null ? (strOrNull(fnObj['arguments']) ?? '') : '',
      output: [],
      approvalState: { type: 'auto' },
      metadata: index !== null ? { [STREAM_TOOL_INDEX_METADATA_KEY]: index } : null,
    };
    parts.push(tool);
  }

  if (content.length > 0) {
    parts.push({ type: 'text', text: content, metadata: null });
  }

  for (const image of images) {
    if (!isObj(image)) continue;
    const type: string | null = strOrNull(image['type']);
    if (type !== 'image_url') continue;
    const imageUrl: JsonValue | undefined = image['image_url'];
    const url: string | null = isObj(imageUrl) ? strOrNull(imageUrl['url']) : null;
    if (url === null) continue;
    if (!url.startsWith('data:image')) {
      throw new Error('Only data uri is supported');
    }
    // Kotlin substringAfter:前缀不存在时返回原串(对齐)
    const prefix = 'data:image/png;base64,';
    parts.push({
      type: 'image',
      url: url.startsWith(prefix) ? url.slice(prefix.length) : url,
      metadata: null,
    });
  }

  const annotationsRaw: JsonValue[] = Array.isArray(obj['annotations']) ? obj['annotations'] : [];
  return makeUIMessage(role, parts, {
    annotations: parseOpenAiAnnotations(annotationsRaw),
  });
};

// ===== parseOpenAiAnnotations(ChatCompletionsAPI.kt:813-829) =====

export const parseOpenAiAnnotations = (arr: JsonValue[]): UIMessageAnnotation[] =>
  arr.map((el: JsonValue): UIMessageAnnotation => {
    const obj: JsonObject = isObj(el) ? el : {};
    const type: string | null = strOrNull(obj['type']);
    if (type === null) throw new Error('type is null');
    if (type === 'url_citation') {
      const body: JsonValue | undefined = obj['url_citation'];
      const bodyObj: JsonObject = isObj(body) ? body : {};
      return {
        type: 'url_citation',
        title: strOrNull(bodyObj['title']) ?? '',
        url: strOrNull(bodyObj['url']) ?? '',
      };
    }
    throw new Error(`unknown annotation type: ${type}`);
  });

// ===== parseOpenAiTokenUsage(ChatCompletionsAPI.kt:832-849) =====

export const parseOpenAiTokenUsage = (obj: JsonObject | null): TokenUsage | null => {
  if (obj === null) return null;
  const hit: number | null = intOrNull(obj['prompt_cache_hit_tokens']);
  const miss: number | null = intOrNull(obj['prompt_cache_miss_tokens']);
  let promptTokens: number | null = intOrNull(obj['prompt_tokens']);
  if (promptTokens === null) {
    const parts: number[] = [];
    if (hit !== null) parts.push(hit);
    if (miss !== null) parts.push(miss);
    promptTokens = parts.length > 0 ? parts.reduce((a: number, b: number): number => a + b, 0) : 0;
  }
  const details: JsonValue | undefined = obj['prompt_tokens_details'];
  const cachedFromDetails: number | null = isObj(details) ? intOrNull(details['cached_tokens']) : null;
  return {
    promptTokens,
    completionTokens: intOrNull(obj['completion_tokens']) ?? 0,
    totalTokens: intOrNull(obj['total_tokens']) ?? 0,
    cachedTokens: cachedFromDetails ?? hit ?? 0,
  };
};

// ===== parseOpenAiErrorDetail(ErrorParser.kt:14-51) =====

const ERROR_FIELDS: string[] = ['error', 'detail', 'message', 'description'];

export const parseOpenAiErrorDetail = (el: JsonValue): Error => {
  if (isObj(el)) {
    // Kotlin this[it] != null 对 JsonNull 也为 true → 按 key 存在性判断
    const found: string | undefined = ERROR_FIELDS.find((f: string): boolean => f in el);
    if (found !== undefined) {
      return parseOpenAiErrorDetail(el[found]);
    }
    return new Error(JSON.stringify(el));
  }
  if (Array.isArray(el)) {
    if (el.length === 0) return new Error('Unknown error: Empty JSON array');
    return parseOpenAiErrorDetail(el[0]);
  }
  // JsonPrimitive:kotlin content;null → "null"
  if (el === null) return new Error('null');
  if (typeof el === 'string') return new Error(el);
  return new Error(String(el));
};

// ===== OpenAiStreamError:流式 error payload 信号 =====
// message 与 Android HttpException 一致;payload 为增补的原始 error 对象(诊断用)。

export class OpenAiStreamError extends Error {
  readonly payload: JsonValue;

  constructor(message: string, payload: JsonValue) {
    super(message);
    this.name = 'OpenAiStreamError';
    this.payload = payload;
  }
}

// ===== parseOpenAiStreamEventData(streamText onEvent,ChatCompletionsAPI.kt:199-256) =====

export interface OpenAiStreamEventResult {
  chunks: MessageChunk[];
  done: boolean;   // 数据为纯 [DONE](正常经 SseAssembler 时 [DONE] 由 done 事件承担,不走这里)
}

export const parseOpenAiStreamEventData = (data: string): OpenAiStreamEventResult => {
  const payloads: string[] = normalizeOpenAIStreamDataLines(data);
  if (payloads.length === 0 && data.includes('[DONE]')) {
    return { chunks: [], done: true };
  }
  const chunks: MessageChunk[] = [];
  for (const payload of payloads) {
    const parsed: unknown = JSON.parse(payload);
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      throw new Error(`stream payload is not a JSON object: ${payload.slice(0, 80)}`);
    }
    const it: JsonObject = parsed as JsonObject;

    const errorValue: JsonValue | undefined = it['error'];
    if (errorValue !== undefined) {
      const base: Error = parseOpenAiErrorDetail(errorValue);
      throw new OpenAiStreamError(base.message, errorValue);
    }

    const id: string = strOrNull(it['id']) ?? '';
    const model: string = strOrNull(it['model']) ?? '';

    const choicesRaw: JsonValue[] = Array.isArray(it['choices']) ? it['choices'] : [];
    const choiceList: UIMessageChoice[] = [];
    if (choicesRaw.length > 0) {
      const first: JsonValue = choicesRaw[0];
      const choice: JsonObject = isObj(first) ? first : {};
      const deltaRaw: JsonValue | undefined = choice['delta'];
      const messageRaw: JsonValue | undefined = choice['message'];
      const message: JsonObject | null = isObj(deltaRaw) ? deltaRaw : (isObj(messageRaw) ? messageRaw : null);
      // 部分网关的 usage-only/keep-alive 尾块发 delta/message 双 null:
      // 跳过该 choice 但保留 chunk(usage 仍随行),不得让整条已收完内容的流报废
      if (message !== null) {
        // finish_reason null/缺失一律归一 "unknown"(ChatCompletionsAPI.kt:224-226)
        const finishReason: string = strOrNull(choice['finish_reason']) ?? 'unknown';
        choiceList.push({
          index: 0,
          delta: parseChatCompletionMessage(message),
          message: null,
          finishReason,
        });
      }
    }

    const usageRaw: JsonValue | undefined = it['usage'];
    chunks.push({
      id,
      model,
      choices: choiceList,
      usage: parseOpenAiTokenUsage(isObj(usageRaw) ? usageRaw : null),
    });
  }
  return { chunks, done: false };
};
