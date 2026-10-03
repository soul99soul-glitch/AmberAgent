// OpenAI 兼容请求构建(纯逻辑,无 IO)
//
// Android 基准:
//   ChatCompletionsAPI.kt buildChatCompletionRequest(:294-475) / buildMessages(:498-517)
//     addAssistantMessages(:559-621) / buildAssistantMessageJson(:623-699)
//     addNonAssistantMessage(:702-737) / isMiMoProvider(:519-529)
//     shouldForceReasoningContentForToolCalls(:531-543) / isModelAllowTemperature(:490-496)
//   ProviderMessageUtils.kt groupPartsByToolBoundary(:26-58)
//   util/Request.kt mergeCustomBody(:48-68)
//
// 裁剪(D-014):
//   - image base64 编码注入 ImageEncoder Port(文件 IO 在 adapter);
//     未注入时编码即抛错,对齐 Android "image encoding failure should fail" 语义。
//   - ModelRegistry token matcher 简化:o 系列 = /^o\d+(-|$)/,gpt-5 由 startsWith 覆盖。
//   - apiKey/customHeaders 不在此层。

import type { JsonObject, JsonValue } from './json.ts';
import type {
  UIMessage, UIMessagePart, UIMessagePartImage, UIMessagePartReasoning, UIMessagePartTool,
} from './message.ts';
import { isValidToUpload, isToolExecuted } from './message.ts';
import type {
  ChatModel, CustomBody, ProviderSettingOpenAI, ReasoningLevel, TextGenerationParams,
} from './provider_model.ts';
import {
  reasoningLevelIsEnabled, reasoningLevelEffort, reasoningLevelBudgetTokens,
} from './provider_model.ts';

// ===== ImageEncoder Port =====

// 注入的编码器:url → data-uri;失败必须抛错(对齐 encodeBase64().getOrThrow())
export type ImageEncoder = (url: string) => string;

const missingEncoder: ImageEncoder = (url: string): string => {
  throw new Error(`image encoding requires injected ImageEncoder (url: ${url})`);
};

// ===== host / 判定函数 =====

// toHttpUrl().host 等价:去 scheme、path、port
export const hostOf = (baseUrl: string): string => {
  let s = baseUrl.trim();
  const schemeIdx = s.indexOf('://');
  if (schemeIdx >= 0) s = s.slice(schemeIdx + 3);
  const slashIdx = s.indexOf('/');
  if (slashIdx >= 0) s = s.slice(0, slashIdx);
  const colonIdx = s.lastIndexOf(':');
  if (colonIdx >= 0) s = s.slice(0, colonIdx);
  return s.toLowerCase();
};

// isMiMoProvider(ChatCompletionsAPI.kt:519)
export const isMiMoProvider = (
  setting: ProviderSettingOpenAI,
  host: string,
  modelId: string,
): boolean => {
  const lowerModelId = modelId.toLowerCase();
  return setting.brand === 'mimo' ||
    setting.authMode === 'mimo_coding_plan' ||
    host.endsWith('xiaomimimo.com') ||
    lowerModelId.indexOf('mimo') >= 0;
};

// shouldForceReasoningContentForToolCalls(ChatCompletionsAPI.kt:531)
export const shouldForceReasoningContentForToolCalls = (
  setting: ProviderSettingOpenAI,
  host: string,
  model: ChatModel,
  reasoningLevel: ReasoningLevel,
): boolean => {
  if (!reasoningLevelIsEnabled(reasoningLevel)) return false;
  if (model.abilities.indexOf('reasoning') < 0) return false;
  const lowerModelId = model.modelId.toLowerCase();
  return setting.brand === 'deepseek' ||
    host === 'api.deepseek.com' ||
    lowerModelId.indexOf('deepseek') >= 0;
};

// isModelAllowTemperature(ChatCompletionsAPI.kt:490)
// D-014 简化:OPENAI_O_MODELS(^o$+^\d+$ token 序列)→ /^o\d+(-|$)/;
// GPT_5 matcher 被 startsWith('gpt-5') 覆盖。
export const isModelAllowTemperature = (model: ChatModel): boolean => {
  const modelId = model.modelId.toLowerCase();
  return !/^o\d+(-|$)/.test(modelId) &&
    !modelId.startsWith('gpt-5') &&
    modelId.indexOf('codex') < 0;
};

// ===== groupPartsByToolBoundary(ProviderMessageUtils.kt:26) =====

export type PartGroup =
  | { kind: 'content'; parts: UIMessagePart[] }
  | { kind: 'tools'; tools: UIMessagePartTool[] };

export const groupPartsByToolBoundary = (parts: UIMessagePart[]): PartGroup[] => {
  const groups: PartGroup[] = [];
  const state: { content: UIMessagePart[]; tools: UIMessagePartTool[] } = { content: [], tools: [] };
  const flushContent = (): void => {
    if (state.content.length > 0) {
      groups.push({ kind: 'content', parts: state.content.slice() });
      state.content = [];
    }
  };
  const flushTools = (): void => {
    if (state.tools.length > 0) {
      groups.push({ kind: 'tools', tools: state.tools.slice() });
      state.tools = [];
    }
  };
  for (const part of parts) {
    if (part.type === 'tool' && isToolExecuted(part)) {
      flushContent();
      state.tools.push(part);
    } else {
      flushTools();
      state.content.push(part);
    }
  }
  flushContent();
  flushTools();
  return groups;
};

// ===== reasoning_content 显式标记(parse 侧写入的 metadata) =====

const hasExplicitReasoningContentField = (part: UIMessagePartReasoning): boolean =>
  part.metadata !== null &&
  typeof part.metadata === 'object' &&
  !Array.isArray(part.metadata) &&
  (part.metadata as JsonObject)['reasoning_content_present'] === true;

// ===== addNonAssistantMessage(:702-737) =====

const buildNonAssistantMessageJson = (message: UIMessage, encodeImage: ImageEncoder): JsonObject => {
  const obj: JsonObject = { role: message.role };
  const textParts = message.parts.filter(
    (p: UIMessagePart): p is UIMessagePart & { type: 'text' } => p.type === 'text');
  const isOnlyTextPart = message.parts.length === 1 && message.parts[0].type === 'text';

  if (message.role === 'system' && textParts.length > 0) {
    obj['content'] = textParts.map((p): string => p.text).join('\n\n');
  } else if (isOnlyTextPart) {
    obj['content'] = textParts[0].text;
  } else {
    const content: JsonValue[] = [];
    for (const part of message.parts) {
      if (part.type === 'text') {
        content.push({ type: 'text', text: part.text });
      } else if (part.type === 'image') {
        content.push({
          type: 'image_url',
          image_url: { url: encodeImage((part as UIMessagePartImage).url) },
        });
      }
      // 其他 part 类型按 Android else -> {} 丢弃
    }
    obj['content'] = content;
  }
  return obj;
};

// ===== buildAssistantMessageJson(:623-699) =====

const buildAssistantMessageJson = (
  contentParts: UIMessagePart[],
  tools: UIMessagePartTool[],
  reasoningPart: UIMessagePartReasoning | null,
  forceReasoningContentForToolCalls: boolean,
  encodeImage: ImageEncoder,
): JsonObject | null => {
  const hasUsableContent = contentParts.some((part: UIMessagePart): boolean => {
    if (part.type === 'text') return part.text.trim().length > 0;
    if (part.type === 'image') return part.url.trim().length > 0;
    return false;
  });
  const hasReasoning = reasoningPart !== null && reasoningPart.reasoning.trim().length > 0;
  const shouldEmitReasoningContent = hasReasoning ||
    (reasoningPart !== null && hasExplicitReasoningContentField(reasoningPart)) ||
    (forceReasoningContentForToolCalls && tools.length > 0);
  if (!hasUsableContent && !shouldEmitReasoningContent && tools.length === 0) {
    return null;
  }

  const obj: JsonObject = { role: 'assistant' };

  if (shouldEmitReasoningContent) {
    obj['reasoning_content'] = reasoningPart === null ? '' : reasoningPart.reasoning;
  }

  if (contentParts.length === 0) {
    obj['content'] = '';
  } else if (contentParts.length === 1 && contentParts[0].type === 'text') {
    obj['content'] = (contentParts[0] as { text: string }).text;
  } else {
    const content: JsonValue[] = [];
    for (const part of contentParts) {
      if (part.type === 'text') {
        content.push({ type: 'text', text: part.text });
      } else if (part.type === 'image') {
        content.push({
          type: 'image_url',
          image_url: { url: encodeImage((part as UIMessagePartImage).url) },
        });
      }
    }
    obj['content'] = content;
  }

  if (tools.length > 0) {
    const toolCalls: JsonValue[] = [];
    for (const tool of tools) {
      toolCalls.push({
        id: tool.toolCallId,
        type: 'function',
        function: { name: tool.toolName, arguments: tool.input },
      });
    }
    obj['tool_calls'] = toolCalls;
  }
  return obj;
};

// ===== buildMessages(:498-517 + addAssistantMessages :559-621) =====

export interface BuildMessagesOpts {
  preserveHistoricalReasoningContent?: boolean;
  forceReasoningContentForToolCalls?: boolean;
}

export const buildMessages = (
  messages: UIMessage[],
  opts: BuildMessagesOpts = {},
  encodeImage: ImageEncoder = missingEncoder,
): JsonObject[] => {
  const preserve = opts.preserveHistoricalReasoningContent ?? false;
  const force = opts.forceReasoningContentForToolCalls ?? false;

  const filteredMessages = messages.filter(isValidToUpload);
  let lastUserIndex = -1;
  filteredMessages.forEach((m: UIMessage, i: number): void => {
    if (m.role === 'user') lastUserIndex = i;
  });

  const out: JsonObject[] = [];

  filteredMessages.forEach((message: UIMessage, index: number): void => {
    if (message.role !== 'assistant') {
      out.push(buildNonAssistantMessageJson(message, encodeImage));
      return;
    }
    const includeReasoning = preserve || index > lastUserIndex;

    // addAssistantMessages
    const groups = groupPartsByToolBoundary(message.parts);
    const buf: { content: UIMessagePart[]; reasoning: UIMessagePartReasoning | null } =
      { content: [], reasoning: null };

    for (const group of groups) {
      if (group.kind === 'content') {
        if (includeReasoning) {
          const r = group.parts.filter(
            (p: UIMessagePart): p is UIMessagePartReasoning => p.type === 'reasoning')[0];
          if (r !== undefined) buf.reasoning = r;
        }
        group.parts
          .filter((p: UIMessagePart): boolean => p.type === 'text' || p.type === 'image')
          .forEach((p: UIMessagePart): void => { buf.content.push(p); });
      } else {
        const assistantMessage = buildAssistantMessageJson(
          buf.content, group.tools, buf.reasoning, force, encodeImage);
        if (assistantMessage !== null) out.push(assistantMessage);
        buf.content = [];
        buf.reasoning = null;

        for (const tool of group.tools) {
          out.push({
            role: 'tool',
            name: tool.toolName,
            tool_call_id: tool.toolCallId,
            content: tool.output
              .filter((p): p is UIMessagePart & { type: 'text' } => p.type === 'text')
              .map((p): string => p.text)
              .join('\n'),
          });
        }
      }
    }

    if (buf.content.length > 0 || buf.reasoning !== null) {
      const assistantMessage = buildAssistantMessageJson(buf.content, [], buf.reasoning, force, encodeImage);
      if (assistantMessage !== null) out.push(assistantMessage);
    }
  });

  return out;
};

// ===== mergeCustomBody(Request.kt:48-68) =====

const isJsonObjectValue = (v: JsonValue | undefined): v is JsonObject =>
  v !== undefined && v !== null && typeof v === 'object' && !Array.isArray(v);

const mergeJsonObjects = (a: JsonObject, b: JsonObject): JsonObject => {
  const out: JsonObject = {};
  for (const k of Object.keys(a)) out[k] = a[k];
  for (const k of Object.keys(b)) {
    const prev = out[k];
    const next = b[k];
    out[k] = isJsonObjectValue(prev) && isJsonObjectValue(next)
      ? mergeJsonObjects(prev, next)
      : next;
  }
  return out;
};

export const mergeCustomBody = (base: JsonObject, customBody: CustomBody[]): JsonObject => {
  let out = base;
  for (const item of customBody) {
    if (item.key.trim().length === 0) continue; // blank key 跳过
    const prev = out[item.key];
    if (isJsonObjectValue(prev) && isJsonObjectValue(item.value)) {
      out = { ...out, [item.key]: mergeJsonObjects(prev, item.value) };
    } else {
      out = { ...out, [item.key]: item.value };
    }
  }
  return out;
};

// ===== SiliconFlow thinking 模型名单(ChatCompletionsAPI.kt:390-416,逐字) =====

const SILICONFLOW_THINKING_MODELS: ReadonlyArray<string> = [
  'Pro/moonshotai/Kimi-K2.5',
  'Pro/zai-org/GLM-5',
  'Pro/zai-org/GLM-5.1',
  'Pro/zai-org/GLM-4.7',
  'deepseek-ai/DeepSeek-V3.2',
  'Pro/deepseek-ai/DeepSeek-V3.2',
  'Qwen/Qwen3.5-397B-A17B',
  'Qwen/Qwen3.5-122B-A10B',
  'Qwen/Qwen3.5-35B-A3B',
  'Qwen/Qwen3.5-27B',
  'Qwen/Qwen3.5-9B',
  'Qwen/Qwen3.5-4B',
  'zai-org/GLM-4.6',
  'Qwen/Qwen3-8B',
  'Qwen/Qwen3-14B',
  'Qwen/Qwen3-32B',
  'Qwen/Qwen3-30B-A3B',
  'tencent/Hunyuan-A13B-Instruct',
  'zai-org/GLM-4.5V',
  'deepseek-ai/DeepSeek-V3.1-Terminus',
  'Pro/deepseek-ai/DeepSeek-V3.1-Terminus',
  'deepseek-ai/DeepSeek-V4-Flash',
  'Pro/deepseek-ai/DeepSeek-V4-Flash',
  'deepseek-ai/DeepSeek-V4-Pro',
  'Pro/deepseek-ai/DeepSeek-V4-Pro',
];

// ===== buildChatCompletionRequest(:294-475) =====

export interface BuildChatCompletionRequestInput {
  messages: UIMessage[];
  params: TextGenerationParams;
  setting: ProviderSettingOpenAI;
  stream: boolean;
  encodeImage?: ImageEncoder;
}

export const buildChatCompletionRequest = (input: BuildChatCompletionRequestInput): JsonObject => {
  const { messages, params, setting, stream } = input;
  const encodeImage = input.encodeImage ?? missingEncoder;

  const host = hostOf(setting.baseUrl);
  const isMiMo = isMiMoProvider(setting, host, params.model.modelId);
  const forceReasoningContentForToolCalls = shouldForceReasoningContentForToolCalls(
    setting, host, params.model, params.reasoningLevel);
  // 官方 DeepSeek thinking + tools 请求要求回传所有历史轮的完整 reasoning_content。
  // 第三方兼容端点继续沿用原来的历史裁剪策略。
  const preserveDeepSeekReasoning = host === 'api.deepseek.com'
    && forceReasoningContentForToolCalls
    && params.model.abilities.indexOf('tool') >= 0
    && params.tools.length > 0;

  const req: JsonObject = {
    model: params.model.modelId,
    messages: buildMessages(messages, {
      preserveHistoricalReasoningContent: isMiMo || preserveDeepSeekReasoning,
      forceReasoningContentForToolCalls,
    }, encodeImage) as unknown as JsonValue,
  };

  if (isModelAllowTemperature(params.model)) {
    if (params.temperature !== null) req['temperature'] = params.temperature;
    if (params.topP !== null) req['top_p'] = params.topP;
  }
  if (params.maxTokens !== null) {
    req[isMiMo ? 'max_completion_tokens' : 'max_tokens'] = params.maxTokens;
  }

  req['stream'] = stream;
  if (stream) {
    if (host !== 'api.mistral.ai') { // mistral 不支持 stream_options
      req['stream_options'] = { include_usage: true };
    }
  }

  // openrouter 适配
  if (host === 'openrouter.ai') {
    if (params.model.outputModalities.indexOf('image') >= 0) {
      req['modalities'] = ['image', 'text'];
    }
  }

  if (params.model.abilities.indexOf('reasoning') >= 0) {
    const level = params.reasoningLevel;
    const enabled = reasoningLevelIsEnabled(level);
    const effort = reasoningLevelEffort(level);

    if (isMiMo) {
      req['thinking'] = { type: enabled ? 'enabled' : 'disabled' };
    } else if (host === 'openrouter.ai') {
      if (level === 'off') {
        req['reasoning'] = { effort: 'none' };
      } else if (level === 'auto') {
        req['reasoning'] = { enabled: true };
      } else {
        req['reasoning'] = { effort };
      }
    } else if (host === 'dashscope.aliyuncs.com') {
      req['enable_thinking'] = enabled;
      if (level !== 'auto') req['thinking_budget'] = reasoningLevelBudgetTokens(level);
    } else if (host === 'ark.cn-beijing.volces.com') {
      req['thinking'] = { type: enabled ? 'enabled' : 'disabled' };
    } else if (host === 'api.mistral.ai') {
      // Mistral 不支持
    } else if (host === 'chat.intern-ai.org.cn') {
      req['thinking_mode'] = enabled;
    } else if (host === 'api.siliconflow.cn') {
      if (SILICONFLOW_THINKING_MODELS.indexOf(params.model.modelId) >= 0) {
        req['enable_thinking'] = enabled;
      }
    } else if (host === 'open.bigmodel.cn' || host === 'api.moonshot.cn') {
      req['thinking'] = { type: enabled ? 'enabled' : 'disabled' };
    } else if (host === 'api.deepseek.com') {
      req['thinking'] = { type: enabled ? 'enabled' : 'disabled' };
      if (enabled && level !== 'auto') {
        req['reasoning_effort'] = effort;
      }
    } else {
      // OpenAI 官方:completions API 只支持 low/medium/high
      if (level !== 'auto') {
        req['reasoning_effort'] = effort === 'none' ? 'low' : effort;
      }
    }
  }

  if (params.model.abilities.indexOf('tool') >= 0 && params.tools.length > 0) {
    const tools: JsonValue[] = [];
    for (const tool of params.tools) {
      tools.push({
        type: 'function',
        function: { name: tool.name, description: tool.description, parameters: tool.parameters },
      });
    }
    req['tools'] = tools;
  }

  // mergeCustomBody + withoutSamplingParamsIfNeeded(:477-483)
  let out = mergeCustomBody(req, params.customBody);
  if (!isModelAllowTemperature(params.model)) {
    out = { ...out };
    delete out['temperature'];
    delete out['top_p'];
  }
  // R06/C04:stream 为调用模式强约束(对齐 ChatCompletionsAPI.kt:473-474
  //   withForcedStream)—— customBody 不允许把流式请求改成非流式,否则服务端回
  //   普通 JSON 会被流式解析器静默吞成空回复
  return { ...out, stream };
};
