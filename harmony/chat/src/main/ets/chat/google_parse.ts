// Google Gemini 解析 — 纯逻辑,零平台依赖
//
// Android 基准: GoogleProvider.kt
//   googleRoleToCommonRole(:631-638) / parseMessage(:640-658)
//   parseSearchGroundingMetadata(:660-674) / parseMessagePart(:676-727)
//   parseUsageMeta(:861-876) / generateText 响应提取(:246-280)
//   streamText onEvent(:357-414) + withStableToolCallIds(:336-349)

import type { JsonObject, JsonValue } from './json.ts';
import { makeUIMessage } from './message.ts';
import type {
  MessageChunk, MessageRole, UIMessage, UIMessageAnnotation, UIMessageChoice,
  UIMessagePart,
} from './message.ts';
import { newId, nowIso } from './ids.ts';
import type { TokenUsage } from './usage.ts';
import { withStreamToolIndex, withStreamArgsReplace } from './tool_merge.ts';
import { ProviderResponseError } from './provider_response_error.ts';

const isObj = (v: JsonValue | undefined): v is JsonObject =>
  typeof v === 'object' && v !== null && !Array.isArray(v);

const strOrNull = (v: JsonValue | undefined): string | null =>
  typeof v === 'string' ? v : null;

const intOrNull = (v: JsonValue | undefined): number | null =>
  typeof v === 'number' && Number.isInteger(v) ? v : null;

// ===== googleRoleToCommonRole(:631-638):未知 role 抛错 =====

export const googleRoleToCommonRole = (role: string): MessageRole => {
  if (role === 'user') return 'user';
  if (role === 'system') return 'system';
  if (role === 'model') return 'assistant';
  throw new Error(`Unknown role ${role}`);
};

// ===== parseMessagePart(:676-727) =====

export const parseGoogleMessagePart = (obj: JsonObject): UIMessagePart => {
  if ('text' in obj) {
    const thought: boolean = obj['thought'] === true;
    const text: string = strOrNull(obj['text']) ?? '';
    if (thought) {
      return {
        type: 'reasoning',
        reasoning: text,
        createdAt: nowIso(), // Android Clock.System.now() 等价
        finishedAt: null,
        metadata: null,
      };
    }
    return { type: 'text', text, metadata: null };
  }

  if ('functionCall' in obj) {
    const fc: JsonObject = isObj(obj['functionCall']) ? (obj['functionCall'] as JsonObject) : {};
    const name: string = strOrNull(fc['name']) ?? '';
    // A function with no parameters legitimately omits args.
    const input: string = 'args' in fc ? JSON.stringify(fc['args']) : '{}';
    const wireId: string | null = strOrNull(fc['id']);
    const metadata: JsonObject = { thoughtSignature: strOrNull(obj['thoughtSignature']) };
    if (wireId !== null && wireId.trim().length > 0) metadata['gemini_wire_call_id'] = wireId;
    return {
      type: 'tool',
      toolCallId: wireId !== null && wireId.trim().length > 0 ? wireId : newId(),
      toolName: name,
      input,
      output: [],
      approvalState: { type: 'auto' },
      metadata,
    };
  }

  if ('inlineData' in obj) {
    const inlineData: JsonObject = isObj(obj['inlineData']) ? (obj['inlineData'] as JsonObject) : {};
    const mime: string = strOrNull(inlineData['mimeType']) ?? 'image/png';
    const data: string = strOrNull(inlineData['data']) ?? '';
    const thought: boolean = obj['thought'] === true;
    if (!mime.startsWith('image/')) {
      throw new Error('Only image mime type is supported');
    }
    // 思考过程中的草稿图,直接忽略(转为 reasoning 占位,Android :709-715)
    if (thought) {
      return {
        type: 'reasoning',
        reasoning: '[Draft Image]\n',
        createdAt: nowIso(),
        finishedAt: null,
        metadata: null,
      };
    }
    return {
      type: 'image',
      url: data,
      metadata: { thoughtSignature: strOrNull(obj['thoughtSignature']) },
    };
  }

  throw new Error(`unknown message part type: ${JSON.stringify(obj)}`);
};

// ===== parseSearchGroundingMetadata(:660-674) =====

export const parseSearchGroundingMetadata = (
  obj: JsonObject | null,
): UIMessageAnnotation[] => {
  if (obj === null) return [];
  const chunksRaw: JsonValue | undefined = obj['groundingChunks'];
  if (!Array.isArray(chunksRaw)) return [];
  const out: UIMessageAnnotation[] = [];
  for (const chunk of chunksRaw) {
    if (!isObj(chunk)) continue;
    const web: JsonValue | undefined = chunk['web'];
    if (!isObj(web)) continue;
    const uri: string | null = strOrNull(web['uri']);
    const title: string | null = strOrNull(web['title']);
    if (uri === null || title === null) continue;
    out.push({ type: 'url_citation', title, url: uri });
  }
  return out;
};

// 非流式与 SSE 的 candidate 来源采用同一映射,保留真实状态和建议 HTML。
const parseGoogleCandidateAnnotations = (candidate: JsonObject): UIMessageAnnotation[] => {
  const grounding: JsonValue | undefined = candidate['groundingMetadata'];
  const annotations: UIMessageAnnotation[] = parseSearchGroundingMetadata(isObj(grounding) ? grounding : null);
  const entry: JsonValue | undefined = isObj(grounding) ? grounding['searchEntryPoint'] : undefined;
  const html: string | null = isObj(entry) ? strOrNull(entry['renderedContent']) : null;
  if (html !== null && html.length > 0) annotations.push({ type: 'google_search_suggestions', html });

  const context: JsonValue | undefined = candidate['urlContextMetadata'];
  const urls: JsonValue | undefined = isObj(context) ? context['urlMetadata'] : undefined;
  if (Array.isArray(urls)) for (const item of urls) {
    if (!isObj(item)) continue;
    const url: string | null = strOrNull(item['retrievedUrl']);
    const status: string | null = strOrNull(item['urlRetrievalStatus']);
    if (url !== null && status !== null) annotations.push({ type: 'url_context', url, status });
  }
  return annotations;
};

// ===== parseMessage(:640-658) =====

export const parseGoogleMessage = (message: JsonObject): UIMessage => {
  const role: MessageRole = googleRoleToCommonRole(strOrNull(message['role']) ?? 'model');
  const contentRaw: JsonValue | undefined = message['content'];
  if (!isObj(contentRaw)) {
    throw new Error('No content');
  }
  const partsRaw: JsonValue | undefined = contentRaw['parts'];
  const parts: UIMessagePart[] = Array.isArray(partsRaw)
    ? partsRaw.map((p: JsonValue): UIMessagePart =>
        parseGoogleMessagePart(isObj(p) ? p : {}))
    : [];

  const annotations: UIMessageAnnotation[] = parseGoogleCandidateAnnotations(message);

  return makeUIMessage(role, parts, { annotations });
};

// ===== parseUsageMeta(:861-876):completion = candidates + thoughts =====

export const parseGoogleUsageMeta = (obj: JsonObject | null): TokenUsage | null => {
  if (obj === null) return null;
  const promptTokens: number = intOrNull(obj['promptTokenCount']) ?? 0;
  const thoughtTokens: number = intOrNull(obj['thoughtsTokenCount']) ?? 0;
  const cachedTokens: number = intOrNull(obj['cachedContentTokenCount']) ?? 0;
  const candidatesTokens: number = intOrNull(obj['candidatesTokenCount']) ?? 0;
  const totalTokens: number = intOrNull(obj['totalTokenCount']) ?? 0;
  return {
    promptTokens,
    completionTokens: candidatesTokens + thoughtTokens,
    cachedTokens,
    totalTokens,
  };
};

// ===== generateText 响应提取(:246-280) =====

export const unwrapGoogleResponse = (bodyJson: JsonObject): JsonObject =>
  isObj(bodyJson['response']) ? bodyJson['response'] : bodyJson;

const FAILED_GOOGLE_FINISH_REASONS: string[] = [
  'SAFETY', 'RECITATION', 'LANGUAGE', 'BLOCKLIST', 'PROHIBITED_CONTENT', 'SPII',
  'MALFORMED_FUNCTION_CALL', 'UNEXPECTED_TOOL_CALL', 'TOO_MANY_TOOL_CALLS',
  'IMAGE_SAFETY', 'IMAGE_PROHIBITED_CONTENT', 'IMAGE_RECITATION', 'IMAGE_OTHER', 'NO_IMAGE',
  'MISSING_THOUGHT_SIGNATURE', 'MALFORMED_RESPONSE', 'ESCALATION', 'PUP_LIMITED_DISABLED',
];

export const googleFailedFinishReason = (chunk: MessageChunk): string | null => {
  for (const choice of chunk.choices) {
    const reason: string | null = choice.finishReason;
    if (reason !== null && FAILED_GOOGLE_FINISH_REASONS.indexOf(reason.toUpperCase()) >= 0) return reason;
  }
  return null;
};

export const parseGoogleResponseBody = (rawJson: JsonObject, modelId: string): MessageChunk => {
  const bodyJson: JsonObject = unwrapGoogleResponse(rawJson);
  const feedback: JsonValue | undefined = bodyJson['promptFeedback'];
  const blockReason: string | null = isObj(feedback) ? strOrNull(feedback['blockReason']) : null;
  if (blockReason !== null) {
    throw new Error(`Google blocked the prompt: ${blockReason}`);
  }
  const candidatesRaw: JsonValue | undefined = bodyJson['candidates'];
  if (!Array.isArray(candidatesRaw) || candidatesRaw.length === 0) {
    throw new Error('Google returned no response candidates');
  }
  for (const candidate of candidatesRaw) {
    if (!isObj(candidate)) continue;
    const finishReason: string | null = strOrNull(candidate['finishReason']);
    if (candidate['content'] === undefined && finishReason !== null) {
      throw new Error(`Google returned no content for candidate with finishReason=${finishReason}`);
    }
  }

  const choices: UIMessageChoice[] = candidatesRaw.map((candidate: JsonValue, index: number): UIMessageChoice => ({
    index,
    delta: null,
    message: parseGoogleMessage(isObj(candidate) ? candidate : {}),
    finishReason: isObj(candidate) ? strOrNull(candidate['finishReason']) : null,
  }));
  const chunk: MessageChunk = {
    id: newId(), // Android Uuid.random
    model: modelId,
    choices,
    usage: parseGoogleUsageMeta(isObj(bodyJson['usageMetadata']) ? bodyJson['usageMetadata'] : null),
  };
  const failure: string | null = googleFailedFinishReason(chunk);
  if (failure !== null) {
    const partial: MessageChunk = { ...chunk, choices: chunk.choices.map((choice: UIMessageChoice): UIMessageChoice => ({
      ...choice,
      message: choice.message === null ? null : { ...choice.message,
        parts: choice.message.parts.filter((part: UIMessagePart): boolean => part.type !== 'tool') },
    })) };
    throw new ProviderResponseError(`Google generation failed: ${failure}`, partial);
  }
  return chunk;
};

// ===== 流式 tool id 稳定分配(:331-349) =====
// Preserve opaque wire IDs; old endpoints without IDs get distinct local ordinals.

export type GoogleToolIdAllocator = (message: UIMessage) => UIMessage;

export const createGoogleStreamToolIdAllocator = (): GoogleToolIdAllocator => {
  const prefix: string = `google-fc-${newId().slice(0, 8)}`;
  const state: { next: number } = { next: 0 };
  const wireIndexes: Map<string, number> = new Map();
  return (message: UIMessage): UIMessage => {
    if (!message.parts.some((p: UIMessagePart): boolean => p.type === 'tool')) {
      return message;
    }
    return {
      ...message,
      parts: message.parts.map((part: UIMessagePart): UIMessagePart => {
        if (part.type !== 'tool') return part;
        const wireId: JsonValue | undefined = part.metadata?.['gemini_wire_call_id'];
        if (typeof wireId === 'string' && wireId.trim().length > 0) {
          const existing: number | undefined = wireIndexes.get(wireId);
          const ordinal: number = existing ?? state.next++;
          wireIndexes.set(wireId, ordinal);
          // Gemini sends complete argument objects, rather than JSON string deltas.
          return withStreamArgsReplace(withStreamToolIndex(part, ordinal));
        }
        const ordinal: number = state.next++;
        return withStreamToolIndex({ ...part, toolCallId: `${prefix}-${ordinal}` }, ordinal);
      }),
    };
  };
};

// ===== streamText onEvent(:357-414) =====

// cloudcode-pa 包装 {"response": {...standard...}};公共 API 顶层即标准载荷,
// 以 response 键存在性区分(Android 注释 :362-365)
export const parseGoogleStreamEventData = (
  data: string, modelId: string, allocator: GoogleToolIdAllocator,
): MessageChunk | null => {
  const rawJson: JsonObject = JSON.parse(data) as JsonObject;
  const jsonData: JsonObject = unwrapGoogleResponse(rawJson);

  const feedback: JsonValue | undefined = jsonData['promptFeedback'];
  const reason: string | null = isObj(feedback) ? strOrNull(feedback['blockReason']) : null;
  if (reason !== null) {
    throw new Error(`Prompt feedback: ${reason}`);
  }

  const candidatesRaw: JsonValue | undefined = jsonData['candidates'];
  if (!Array.isArray(candidatesRaw) || candidatesRaw.length === 0) {
    return null; // Android return(跳过该事件)
  }
  const usage: TokenUsage | null = parseGoogleUsageMeta(
    isObj(jsonData['usageMetadata']) ? jsonData['usageMetadata'] : null);

  const choices: UIMessageChoice[] = candidatesRaw.map(
    (candidate: JsonValue, index: number): UIMessageChoice => {
      const candidateObj: JsonObject = isObj(candidate) ? candidate : {};
      const content: JsonValue | undefined = candidateObj['content'];
      const grounding: JsonValue | undefined = candidateObj['groundingMetadata'];
      const finishReason: string | null = strOrNull(candidateObj['finishReason']);

      let message: UIMessage | null = null;
      if (isObj(content)) {
        const wrapped: JsonObject = { role: 'model', content };
        if (isObj(grounding)) wrapped['groundingMetadata'] = grounding;
        const context: JsonValue | undefined = candidateObj['urlContextMetadata'];
        if (isObj(context)) wrapped['urlContextMetadata'] = context;
        message = allocator(parseGoogleMessage(wrapped));
      } else {
        const annotations: UIMessageAnnotation[] = parseGoogleCandidateAnnotations(candidateObj);
        if (annotations.length > 0) message = makeUIMessage('assistant', [], { annotations });
      }
      return { index, delta: message, message: null, finishReason };
    });

  return { id: newId(), model: modelId, choices, usage };
};
