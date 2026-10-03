// OpenAI Responses API 请求构建(纯逻辑,无 IO)
//
// Android 基准: ResponseAPI.kt
//   buildRequestBody(:605-717) / buildMessages(:727-850)
//   addAssistantItems / addUserItems / addContentItem
//   openAIResponsesReasoningEffort(:1391) / isOnlyTextPart(:1400)
//   resolveResponseProviderCapabilities(:1414) / supportsResponsesResume(:1438)
//   withoutSamplingParamsIfNeeded / withForcedStream
//
// 与 Chat Completions 的差异: input 数组、instructions、reasoning{summary,effort}、
// function_call / function_call_output item、store 门控。

import type { JsonObject, JsonValue } from './json.ts';
import type {
  MessageRole, UIMessage, UIMessagePart, UIMessagePartImage, UIMessagePartReasoning,
  UIMessagePartTool, UIMessagePartText,
} from './message.ts';
import { isValidToUpload } from './message.ts';
import type {
  ChatModel, CustomBody, ProviderSettingOpenAI, ReasoningLevel, TextGenerationParams,
} from './provider_model.ts';
import { reasoningLevelIsEnabled } from './provider_model.ts';
import {
  groupPartsByToolBoundary, hostOf, isModelAllowTemperature, mergeCustomBody,
} from './openai_request.ts';
import type { ImageEncoder, PartGroup } from './openai_request.ts';

// ===== capabilities / resume 门控 =====

export interface ResponseProviderCapabilities {
  supportsReasoningSummary: boolean;
  supportEncryptedContent: boolean;
  supportsStoredResponses: boolean;
}

export const resolveResponseProviderCapabilities = (host: string): ResponseProviderCapabilities => {
  if (host === 'ark.cn-beijing.volces.com') {
    return { supportsReasoningSummary: false, supportEncryptedContent: false, supportsStoredResponses: false };
  }
  if (host === 'api.openai.com') {
    return { supportsReasoningSummary: true, supportEncryptedContent: true, supportsStoredResponses: true };
  }
  return { supportsReasoningSummary: true, supportEncryptedContent: true, supportsStoredResponses: false };
};

/** P6-01 严格适用:官方 api.openai.com + api_key + useResponseApi */
export const supportsResponsesResume = (setting: ProviderSettingOpenAI): boolean =>
  setting.useResponseApi === true &&
  setting.authMode === 'api_key' &&
  setting.baseUrl.startsWith('https://') &&
  resolveResponseProviderCapabilities(hostOf(setting.baseUrl)).supportsStoredResponses === true;

// openAIResponsesReasoningEffort(:1391) — AUTO→null; OFF→low; xhigh/max→high
export const openAIResponsesReasoningEffort = (level: ReasoningLevel): string | null => {
  switch (level) {
    case 'auto': return null;
    case 'off': return 'low';
    case 'low': return 'low';
    case 'medium': return 'medium';
    case 'high':
    case 'xhigh':
    case 'max': return 'high';
    default: return null;
  }
};

// ===== resume 类型 =====

export interface ResponseCursor {
  responseId: string;
  sequence: number;
  providerId: string;
  terminalStatus?: 'completed';
}

export interface ResponsesResumeRequest {
  runId: string;
  resumeFrom: ResponseCursor | null;
}

export interface ResponseResumeStore {
  save(runId: string, responseId: string, sequence: number, providerId: string): Promise<void>;
  load(runId: string): Promise<ResponseCursor | null>;
  clear(runId: string): Promise<void>;
}

export const responsesResumeUrl = (setting: ProviderSettingOpenAI, cursor: ResponseCursor): string => {
  if (!supportsResponsesResume(setting) || cursor.providerId !== setting.id) {
    throw new Error('Responses resume requires the original official OpenAI provider');
  }
  if (!/^resp_[A-Za-z0-9_-]+$/.test(cursor.responseId) || !Number.isSafeInteger(cursor.sequence) || cursor.sequence < 0) {
    throw new Error('Invalid Responses resume cursor');
  }
  return `${setting.baseUrl.replace(/\/$/, '')}/responses/${cursor.responseId}?stream=true&starting_after=${cursor.sequence}`;
};

export interface BuildResponsesRequestOpts {
  messages: UIMessage[];
  params: TextGenerationParams;
  setting: ProviderSettingOpenAI;
  stream: boolean;
  resume?: ResponsesResumeRequest | null;
  encodeImage?: ImageEncoder;
}

// ===== helpers =====

const isOnlyTextParts = (parts: UIMessagePart[]): boolean => {
  const gonnaSend = parts.filter((p: UIMessagePart): boolean => p.type === 'text' || p.type === 'image');
  const texts = parts.filter((p: UIMessagePart): boolean => p.type === 'text');
  return gonnaSend.length === texts.length && texts.length === 1;
};

const metaStr = (part: UIMessagePart, key: string): string | null => {
  const meta = (part as { metadata?: JsonObject }).metadata;
  if (meta === undefined || meta === null) return null;
  const v = meta[key];
  return typeof v === 'string' ? v : null;
};

// ===== input items =====

const addContentItem = (
  out: JsonValue[], role: MessageRole, parts: UIMessagePart[], encodeImage?: ImageEncoder,
): void => {
  if (parts.length === 0) return;
  if (isOnlyTextParts(parts)) {
    const textPart = parts[0] as UIMessagePartText;
    out.push({ role, content: textPart.text });
    return;
  }
  const content: JsonObject[] = [];
  for (const part of parts) {
    if (part.type === 'text') {
      content.push({
        type: role === 'user' ? 'input_text' : 'output_text',
        text: part.text,
      });
    } else if (part.type === 'image') {
      const img = part as UIMessagePartImage;
      const encode: ImageEncoder | undefined = encodeImage;
      if (encode === undefined) {
        throw new Error(`image encoding requires injected ImageEncoder (url: ${img.url})`);
      }
      const encoded = encode(img.url);
      content.push({
        type: role === 'user' ? 'input_image' : 'output_image',
        image_url: encoded,
      });
    }
  }
  out.push({ role, content });
};

const addUserItems = (out: JsonValue[], message: UIMessage, encodeImage?: ImageEncoder): void => {
  const contentParts = message.parts.filter(
    (p: UIMessagePart): boolean => p.type === 'text' || p.type === 'image',
  );
  if (contentParts.length > 0) {
    addContentItem(out, 'user', contentParts, encodeImage);
  }
};

const addAssistantItems = (out: JsonValue[], message: UIMessage, encodeImage?: ImageEncoder): void => {
  const groups = groupPartsByToolBoundary(message.parts);
  const contentBuffer: UIMessagePart[] = [];

  const flushContent = (): void => {
    if (contentBuffer.length > 0) {
      addContentItem(out, 'assistant', [...contentBuffer], encodeImage);
      contentBuffer.length = 0;
    }
  };

  for (const group of groups as PartGroup[]) {
    if (group.kind === 'content') {
      for (const part of group.parts) {
        if (part.type === 'reasoning') {
          const reasoningId = metaStr(part, 'reasoning_id');
          if (reasoningId === null || reasoningId.trim().length === 0) continue;
          flushContent();
          const r = part as UIMessagePartReasoning;
          const item: JsonObject = {
            type: 'reasoning',
            summary: [{ type: 'summary_text', text: r.reasoning }],
          };
          item['id'] = reasoningId;
          const encrypted = metaStr(part, 'encrypted_content');
          if (encrypted !== null) item['encrypted_content'] = encrypted;
          out.push(item);
        } else if (part.type === 'image') {
          const callId = metaStr(part, 'openai_image_call_id');
          if (callId !== null) {
            flushContent();
            out.push({ type: 'image_generation_call', id: callId });
          } else {
            contentBuffer.push(part);
          }
        } else if (part.type === 'text') {
          contentBuffer.push(part);
        }
      }
    } else {
      flushContent();
      for (const tool of group.tools) {
        const callId: string = metaStr(tool, 'openai_call_id') ?? tool.toolCallId;
        out.push({
          type: 'function_call',
          call_id: callId,
          name: tool.toolName,
          arguments: tool.input,
        });
        const outputText = tool.output
          .filter((p: UIMessagePart): boolean => p.type === 'text')
          .map((p: UIMessagePart): string => (p as UIMessagePartText).text)
          .join('\n');
        out.push({
          type: 'function_call_output',
          call_id: callId,
          output: outputText,
        });
      }
    }
  }
  flushContent();
};

export const buildResponsesInput = (
  messages: UIMessage[], encodeImage?: ImageEncoder,
): JsonValue[] => {
  const out: JsonValue[] = [];
  for (const message of messages) {
    if (!isValidToUpload(message) || message.role === 'system') continue;
    if (message.role === 'assistant') {
      addAssistantItems(out, message, encodeImage);
    } else {
      addUserItems(out, message, encodeImage);
    }
  }
  return out;
};

// ===== buildRequestBody =====

export const buildResponsesRequestBody = (opts: BuildResponsesRequestOpts): JsonObject => {
  const { messages, params, setting, stream } = opts;
  const host = hostOf(setting.baseUrl);
  const capabilities = resolveResponseProviderCapabilities(host);
  const resume = opts.resume ?? null;
  const model: ChatModel = params.model;

  const body: JsonObject = {
    model: model.modelId,
    stream,
  };

  // store 门控:resume → true;image_generation 无 resume 时省略(服务端拒绝
  // store=false);其余 → false(与 Android ResponseAPI.buildRequestBody 一致)
  const hasImageGeneration = model.tools.indexOf('image_generation') >= 0;
  if (resume !== null) {
    body['store'] = true;
  } else if (!hasImageGeneration) {
    body['store'] = false;
  }

  if (isModelAllowTemperature(model)) {
    if (params.temperature !== null) body['temperature'] = params.temperature;
    if (params.topP !== null) body['top_p'] = params.topP;
  }
  if (params.maxTokens !== null) body['max_output_tokens'] = params.maxTokens;

  const systemMessages = messages.filter((m: UIMessage): boolean => m.role === 'system');
  if (systemMessages.length > 0) {
    const texts = systemMessages[0].parts
      .filter((p: UIMessagePart): boolean => p.type === 'text')
      .map((p: UIMessagePart): string => (p as UIMessagePartText).text);
    body['instructions'] = texts.join('\n\n');
  }

  body['input'] = buildResponsesInput(messages, opts.encodeImage);

  if (model.abilities.indexOf('reasoning') >= 0 && reasoningLevelIsEnabled(params.reasoningLevel)) {
    const reasoning: JsonObject = {};
    if (capabilities.supportsReasoningSummary) {
      reasoning['summary'] = 'auto';
    }
    const effort = openAIResponsesReasoningEffort(params.reasoningLevel);
    if (effort !== null) reasoning['effort'] = effort;
    body['reasoning'] = reasoning;
    if (capabilities.supportEncryptedContent) {
      body['include'] = ['reasoning.encrypted_content'];
    }
  }

  const toolDefinitions: JsonObject[] = [];
  if (model.abilities.indexOf('tool') >= 0) {
    for (const tool of params.tools) {
      toolDefinitions.push({
        type: 'function',
        name: tool.name,
        description: tool.description,
        parameters: tool.parameters,
      });
    }
  }
  for (const builtIn of model.tools) {
    if (builtIn === 'search') {
      toolDefinitions.push({ type: 'web_search' });
    } else if (builtIn === 'image_generation') {
      toolDefinitions.push({ type: 'image_generation', model: 'gpt-image-2' });
    }
  }
  if (toolDefinitions.length > 0) {
    body['tools'] = toolDefinitions;
  }

  let out = mergeCustomBody(body, params.customBody);
  if (!isModelAllowTemperature(model)) {
    const clone: JsonObject = { ...out };
    delete clone['temperature'];
    delete clone['top_p'];
    out = clone;
  }
  out = { ...out, stream };
  // An explicit resumable stream must keep running when its local connection drops.
  if (resume !== null && stream) {
    out['background'] = true;
    out['store'] = true;
  }
  return out;
};
