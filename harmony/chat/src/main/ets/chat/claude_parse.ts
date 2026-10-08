// Claude Messages API 解析 — 纯逻辑,零平台依赖
//
// Android 基准: ClaudeProvider.kt
//   parseMessage(:526-595)         → parseClaudeMessage
//   parseTokenUsage(:602-620)      → parseClaudeTokenUsage
//   generateText 响应提取(:129-151) → parseClaudeResponseBody
//   streamText onEvent(:192-254)   → parseClaudeStreamEvent
//   ErrorParser.parseErrorDetail   → 复用 parseOpenAiErrorDetail(同算法,泛型)

import type { JsonObject, JsonValue } from './json.ts';
import { makeUIMessage, CLAUDE_REDACTED_THINKING_METADATA_KEY, CLAUDE_THINKING_BLOCK_INDEX_METADATA_KEY } from './message.ts';
import type { MessageChunk, UIMessage, UIMessageChoice, UIMessagePart } from './message.ts';
import { nowIso } from './ids.ts';
import type { TokenUsage } from './usage.ts';
import { withStreamToolIndex } from './tool_merge.ts';
import { parseOpenAiErrorDetail } from './openai_parse.ts';

const isObj = (v: JsonValue | undefined): v is JsonObject =>
  typeof v === 'object' && v !== null && !Array.isArray(v);

const strOrNull = (v: JsonValue | undefined): string | null =>
  typeof v === 'string' ? v : null;

const intOrNull = (v: JsonValue | undefined): number | null =>
  typeof v === 'number' && Number.isInteger(v) ? v : null;

// ===== parseMessage(:526-595) =====

export const parseClaudeMessage = (content: JsonValue[], streamBlockIndex: number | null = null): UIMessage => {
  const parts: UIMessagePart[] = [];

  for (let index = 0; index < content.length; index++) {
    const contentBlock = content[index];
    if (!isObj(contentBlock)) continue;
    const type: string | null = strOrNull(contentBlock['type']);

    if (type === 'text' || type === 'text_delta') {
      const text: string = strOrNull(contentBlock['text']) ?? '';
      if (text.length > 0) {
        parts.push({ type: 'text', text, metadata: null });
      }
    } else if (type === 'thinking' || type === 'thinking_delta' || type === 'signature_delta') {
      const thinking: string = strOrNull(contentBlock['thinking']) ?? '';
      const signature: string | null = strOrNull(contentBlock['signature']);
      if (thinking.length > 0 || signature !== null || type === 'thinking') {
        const metadata: JsonObject = { [CLAUDE_THINKING_BLOCK_INDEX_METADATA_KEY]: streamBlockIndex ?? index };
        if (signature !== null) metadata['signature'] = signature;
        parts.push({
          type: 'reasoning',
          reasoning: thinking,
          createdAt: nowIso(), // Android Clock.System.now() 等价
          finishedAt: null,
          metadata,
        });
      }
    } else if (type === 'redacted_thinking') {
      parts.push({ type: 'reasoning', reasoning: '', createdAt: nowIso(), finishedAt: null,
        metadata: { [CLAUDE_REDACTED_THINKING_METADATA_KEY]: contentBlock,
          [CLAUDE_THINKING_BLOCK_INDEX_METADATA_KEY]: streamBlockIndex ?? index } });
    } else if (type === 'tool_use') {
      const id: string = strOrNull(contentBlock['id']) ?? '';
      const name: string = strOrNull(contentBlock['name']) ?? '';
      const inputRaw: JsonValue | undefined = contentBlock['input'];
      const input: JsonObject = isObj(inputRaw) ? inputRaw : {};
      parts.push({
        type: 'tool',
        toolCallId: id,
        toolName: name,
        input: Object.keys(input).length === 0 ? '' : JSON.stringify(input),
        output: [],
        approvalState: { type: 'auto' },
        metadata: null,
      });
    } else if (type === 'input_json_delta') {
      const input: string = strOrNull(contentBlock['partial_json']) ?? '';
      parts.push({
        type: 'tool',
        toolCallId: '',
        toolName: '',
        input,
        output: [],
        approvalState: { type: 'auto' },
        metadata: null,
      });
    }
  }

  return makeUIMessage('assistant', parts);
};

// ===== parseTokenUsage(:602-620) =====
// usage 回退链:body.usage → body.message.usage;prompt = input + cache_read + cache_creation

export const parseClaudeTokenUsage = (bodyJson: JsonObject | null): TokenUsage | null => {
  if (bodyJson === null) return null;
  let usageJson: JsonObject | null = isObj(bodyJson['usage']) ? (bodyJson['usage'] as JsonObject) : null;
  if (usageJson === null) {
    const msg: JsonValue | undefined = bodyJson['message'];
    if (isObj(msg) && isObj(msg['usage'])) {
      usageJson = msg['usage'] as JsonObject;
    }
  }
  if (usageJson === null) return null;

  const inputTokens: number = intOrNull(usageJson['input_tokens']) ?? 0;
  const cachedInputTokens: number = intOrNull(usageJson['cache_read_input_tokens']) ?? 0;
  const cachedCreationTokens: number = intOrNull(usageJson['cache_creation_input_tokens']) ?? 0;
  const completionTokens: number = intOrNull(usageJson['output_tokens']) ?? 0;
  const promptTokens: number = inputTokens + cachedInputTokens + cachedCreationTokens;
  return {
    promptTokens,
    completionTokens,
    cachedTokens: cachedInputTokens,
    totalTokens: promptTokens + completionTokens,
  };
};

// ===== generateText 响应提取(:129-151) =====

export const parseClaudeResponseBody = (bodyJson: JsonObject): MessageChunk => {
  const id: string = strOrNull(bodyJson['id']) ?? '';
  const model: string = strOrNull(bodyJson['model']) ?? '';
  const contentRaw: JsonValue | undefined = bodyJson['content'];
  const content: JsonValue[] = Array.isArray(contentRaw) ? contentRaw : [];
  const stopReason: string = strOrNull(bodyJson['stop_reason']) ?? 'unknown';
  const usage: TokenUsage | null = parseClaudeTokenUsage(bodyJson);

  const choice: UIMessageChoice = {
    index: 0,
    delta: null,
    message: parseClaudeMessage(content),
    finishReason: stopReason,
  };
  return { id, model, choices: [choice], usage };
};

// ===== streamText onEvent(:192-254) =====

export interface ClaudeStreamEventResult {
  done: boolean;          // message_stop → 流正常结束
  chunk: MessageChunk | null; // null = 该事件无可发内容(空 parts 且无 usage / [DONE])
}

// type = SSE event: 字段(可空);id = SSE id: 字段;data = data: 原文
// error 事件 → 抛 parseErrorDetail(Android close(error) 语义,由 API 层统一抛出)
export const parseClaudeStreamEvent = (
  type: string | undefined, id: string | undefined, data: string,
): ClaudeStreamEventResult => {
  if (data === '[DONE]') return { done: false, chunk: null };

  const dataJson: JsonObject = JSON.parse(data) as JsonObject;

  if (type === 'message_stop') {
    return { done: true, chunk: null };
  }
  if (type === 'error') {
    const errRaw: JsonValue | undefined = dataJson['error'];
    throw parseOpenAiErrorDetail(errRaw !== undefined ? errRaw : dataJson);
  }

  // content_block_start/delta:content_block 与 delta 合并为临时 content 数组复用 parseMessage
  const contentArray: JsonValue[] = [];
  const contentBlock: JsonValue | undefined = dataJson['content_block'];
  const deltaObj: JsonValue | undefined = dataJson['delta'];
  if (isObj(contentBlock)) contentArray.push(contentBlock);
  if (isObj(deltaObj)) contentArray.push(deltaObj);

  const blockIndex: number | null = intOrNull(dataJson['index']);
  let deltaMessage: UIMessage = parseClaudeMessage(contentArray, blockIndex);

  // content_block_start/delta 顶层 index 是并行 tool use 的唯一关联键:
  // input_json_delta 不带 tool id,不注入 index 时 merge 层只能回退到
  // "最后一个 Tool",并行 tool 的参数会串线(Android 注释 :225-227 逐字语义)
  if (blockIndex !== null && deltaMessage.parts.some((p: UIMessagePart): boolean => p.type === 'tool')) {
    deltaMessage = {
      ...deltaMessage,
      parts: deltaMessage.parts.map((p: UIMessagePart): UIMessagePart =>
        p.type === 'tool' ? withStreamToolIndex(p, blockIndex) : p),
    };
  }

  const tokenUsage: TokenUsage | null = parseClaudeTokenUsage(dataJson);
  if (deltaMessage.parts.length === 0 && tokenUsage === null) {
    return { done: false, chunk: null };
  }

  const choice: UIMessageChoice = {
    index: 0,
    delta: deltaMessage,
    message: null,
    finishReason: null,
  };
  return {
    done: false,
    chunk: { id: id ?? '', model: '', choices: [choice], usage: tokenUsage },
  };
};
