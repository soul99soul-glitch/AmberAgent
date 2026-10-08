// Claude Messages API 请求构建(纯逻辑,无 IO)
//
// Android 基准: ClaudeProvider.kt
//   buildMessageRequest(:289-378) / buildMessages(:380-393)
//   insertMessagesCacheControl(:395-434) / addAssistantMessage(:436-475)
//   addUserMessage(:477-484) / toContentBlock(:486-509)
//   toToolUseBlock(:511-516) / toToolResultBlock(:518-524)
//   SystemPromptMarkers.kt(:3-5)
//
// 裁剪(登记):
//   - keyRoulette 多 key 轮换 → 单 apiKey(adapter 层,P1)
//   - image 编码注入 ClaudeImageEncoder Port(mime + 无前缀 base64;
//     对齐 Android encodeBase64(withPrefix=false).getOrThrow() 失败即抛)

import type { JsonObject, JsonValue } from './json.ts';
import type {
  UIMessage, UIMessagePart, UIMessagePartImage, UIMessagePartReasoning, UIMessagePartTool,
} from './message.ts';
import { isValidToUpload, toolInputAsJson, CLAUDE_REDACTED_THINKING_METADATA_KEY } from './message.ts';
import type { TextGenerationParams } from './provider_model.ts';
import { reasoningLevelIsEnabled, reasoningLevelEffort } from './provider_model.ts';
import type { ProviderSettingClaude } from './provider_settings.ts';
import { mergeCustomBody, groupPartsByToolBoundary } from './openai_request.ts';
import type { PartGroup } from './openai_request.ts';
// cache 标记常量在 context_assembly 已定义(D-018),复用不重复定义
import {
  SYSTEM_PROMPT_CACHE_CONTROL_METADATA, SYSTEM_PROMPT_CACHE_DISABLED,
  SYSTEM_PROMPT_CACHE_EPHEMERAL,
} from './context_assembly.ts';
export {
  SYSTEM_PROMPT_CACHE_CONTROL_METADATA, SYSTEM_PROMPT_CACHE_DISABLED,
  SYSTEM_PROMPT_CACHE_EPHEMERAL,
};

// ===== ImageEncoder Port(Claude 形态:mime + 无前缀 base64) =====

export interface ClaudeEncodedImage {
  mimeType: string;
  base64: string;
}

export type ClaudeImageEncoder = (url: string) => ClaudeEncodedImage;

const missingEncoder: ClaudeImageEncoder = (url: string): ClaudeEncodedImage => {
  throw new Error(`image encoding requires injected ClaudeImageEncoder (url: ${url})`);
};

// ===== System prompt cache 标记(SystemPromptMarkers.kt:3-5) =====
// 常量定义在 context_assembly.ts(D-018 已落地),此处 import + re-export(见文件头)

const EPHEMERAL_CACHE_CONTROL: JsonObject = { type: 'ephemeral' };

// ===== toContentBlock(:486-509) =====

const metadataStr = (part: UIMessagePart, key: string): string | null => {
  if (part.metadata === null || typeof part.metadata !== 'object' || Array.isArray(part.metadata)) {
    return null;
  }
  const v: JsonValue | undefined = (part.metadata as JsonObject)[key];
  return typeof v === 'string' ? v : null;
};

export const toClaudeContentBlock = (
  part: UIMessagePart, encodeImage: ClaudeImageEncoder,
): JsonObject | null => {
  if (part.type === 'text') {
    return { type: 'text', text: part.text };
  }
  if (part.type === 'image') {
    const encoded: ClaudeEncodedImage = encodeImage((part as UIMessagePartImage).url);
    return {
      type: 'image',
      source: { type: 'base64', media_type: encoded.mimeType, data: encoded.base64 },
    };
  }
  if (part.type === 'reasoning') {
    const r: UIMessagePartReasoning = part as UIMessagePartReasoning;
    const redacted = r.metadata?.[CLAUDE_REDACTED_THINKING_METADATA_KEY];
    if (typeof redacted === 'object' && redacted !== null && !Array.isArray(redacted) &&
      redacted['type'] === 'redacted_thinking' && typeof redacted['data'] === 'string' &&
      redacted['data'].trim().length > 0) return redacted;
    const signature = metadataStr(r, 'signature');
    if (signature === null || signature.trim().length === 0) return null;
    return { type: 'thinking', thinking: r.reasoning, signature };
  }
  // 其他 part 类型按 Android else -> null 丢弃
  return null;
};

// ===== tool_use / tool_result(:511-524) =====

export const toClaudeToolUseBlock = (tool: UIMessagePartTool): JsonObject => ({
  type: 'tool_use',
  id: tool.toolCallId,
  name: tool.toolName,
  input: toolInputAsJson(tool),
});

export const toClaudeToolResultBlock = (
  tool: UIMessagePartTool, encodeImage: ClaudeImageEncoder,
): JsonObject => {
  const content: JsonValue[] = [];
  for (const p of tool.output) {
    const block: JsonObject | null = toClaudeContentBlock(p, encodeImage);
    if (block !== null) content.push(block);
  }
  return { type: 'tool_result', tool_use_id: tool.toolCallId, content };
};

// ===== addAssistantMessage(:436-475) =====

const appendClaudeAssistantMessage = (
  out: JsonObject[], message: UIMessage, encodeImage: ClaudeImageEncoder,
): void => {
  const groups: PartGroup[] = groupPartsByToolBoundary(message.parts);
  let contentBuffer: JsonObject[] = [];

  for (const group of groups) {
    if (group.kind === 'content') {
      for (const part of group.parts) {
        const block: JsonObject | null = toClaudeContentBlock(part, encodeImage);
        if (block !== null) contentBuffer.push(block);
      }
    } else {
      for (const tool of group.tools) {
        contentBuffer.push(toClaudeToolUseBlock(tool));
      }
      // 输出 assistant 消息
      out.push({ role: 'assistant', content: contentBuffer as unknown as JsonValue });
      contentBuffer = [];
      // 紧跟 tool_result(user 角色)
      const results: JsonValue[] = [];
      for (const tool of group.tools) {
        results.push(toClaudeToolResultBlock(tool, encodeImage));
      }
      out.push({ role: 'user', content: results as unknown as JsonValue });
    }
  }

  // 输出剩余内容
  if (contentBuffer.length > 0) {
    out.push({ role: 'assistant', content: contentBuffer as unknown as JsonValue });
  }
};

// ===== insertMessagesCacheControl(:395-434) =====
// 倒数第二条非 tool_result 的 user message 的最后一个 content block 加 cache_control

const isToolResultUserMessage = (msg: JsonObject): boolean => {
  if (msg['role'] !== 'user') return false;
  const content: JsonValue | undefined = msg['content'];
  if (!Array.isArray(content)) return false;
  return content.some((b: JsonValue): boolean =>
    typeof b === 'object' && b !== null && !Array.isArray(b) &&
    (b as JsonObject)['type'] === 'tool_result');
};

export const insertMessagesCacheControl = (messages: JsonObject[]): JsonObject[] => {
  const realUserIndices: number[] = [];
  messages.forEach((msg: JsonObject, i: number): void => {
    if (msg['role'] === 'user' && !isToolResultUserMessage(msg)) {
      realUserIndices.push(i);
    }
  });
  if (realUserIndices.length < 2) return messages;
  const targetIndex: number = realUserIndices[realUserIndices.length - 2];

  return messages.map((msg: JsonObject, i: number): JsonObject => {
    if (i !== targetIndex) return msg;
    const content: JsonValue | undefined = msg['content'];
    if (!Array.isArray(content)) return msg;
    const newContent: JsonValue[] = content.map((block: JsonValue, bi: number): JsonValue => {
      if (bi !== content.length - 1) return block;
      if (typeof block !== 'object' || block === null || Array.isArray(block)) return block;
      const obj: JsonObject = { ...(block as JsonObject) };
      obj['cache_control'] = EPHEMERAL_CACHE_CONTROL;
      return obj;
    });
    return { ...msg, content: newContent as unknown as JsonValue };
  });
};

// ===== buildMessages(:380-393) =====

export const buildClaudeMessages = (
  messages: UIMessage[],
  promptCaching: boolean,
  encodeImage: ClaudeImageEncoder = missingEncoder,
): JsonObject[] => {
  const out: JsonObject[] = [];
  for (const message of messages) {
    if (!isValidToUpload(message) || message.role === 'system') continue;
    if (message.role === 'assistant') {
      appendClaudeAssistantMessage(out, message, encodeImage);
    } else {
      const content: JsonValue[] = [];
      for (const part of message.parts) {
        const block: JsonObject | null = toClaudeContentBlock(part, encodeImage);
        if (block !== null) content.push(block);
      }
      out.push({ role: message.role, content: content as unknown as JsonValue });
    }
  }
  if (!promptCaching) return out;
  return insertMessagesCacheControl(out);
};

// ===== buildMessageRequest(:289-378) =====

export interface BuildClaudeMessageRequestInput {
  messages: UIMessage[];
  params: TextGenerationParams;
  setting: ProviderSettingClaude;
  stream: boolean;
  encodeImage?: ClaudeImageEncoder;
}

export const buildClaudeMessageRequest = (input: BuildClaudeMessageRequestInput): JsonObject => {
  const { messages, params, setting, stream } = input;
  const encodeImage: ClaudeImageEncoder = input.encodeImage ?? missingEncoder;

  const req: JsonObject = {
    model: params.model.modelId,
    messages: buildClaudeMessages(messages, setting.promptCaching, encodeImage) as unknown as JsonValue,
    max_tokens: params.maxTokens !== null ? params.maxTokens : 64000,
  };

  // temperature 仅在未开 reasoning 时携带(:302-305)
  if (params.temperature !== null && !reasoningLevelIsEnabled(params.reasoningLevel)) {
    req['temperature'] = params.temperature;
  }
  if (params.topP !== null) req['top_p'] = params.topP;

  req['stream'] = stream;

  // system prompt(:310-332):取第一条 system 消息的 text parts;
  // cache_control 仅打在显式 EPHEMERAL 标记的 part 上(无标记则不打);
  // 任一 part 标记 DISABLED → 整个 system 不打 cache
  const systemMessage: UIMessage | undefined = messages.filter(
    (m: UIMessage): boolean => m.role === 'system')[0];
  const systemTextParts: UIMessagePart[] = systemMessage !== undefined
    ? systemMessage.parts.filter((p: UIMessagePart): boolean => p.type === 'text')
    : [];
  if (systemTextParts.length > 0) {
    const cacheDisabled: boolean = systemTextParts.some(
      (p: UIMessagePart): boolean =>
        metadataStr(p, SYSTEM_PROMPT_CACHE_CONTROL_METADATA) === SYSTEM_PROMPT_CACHE_DISABLED);
    let explicitCacheIndex: number = -1;
    systemTextParts.forEach((p: UIMessagePart, i: number): void => {
      if (metadataStr(p, SYSTEM_PROMPT_CACHE_CONTROL_METADATA) === SYSTEM_PROMPT_CACHE_EPHEMERAL) {
        explicitCacheIndex = i; // indexOfLast:遍历时覆盖即取最后
      }
    });
    const systemBlocks: JsonValue[] = systemTextParts.map((p: UIMessagePart, i: number): JsonObject => {
      const block: JsonObject = { type: 'text', text: (p as { text: string }).text };
      if (setting.promptCaching && !cacheDisabled && i === explicitCacheIndex) {
        block['cache_control'] = EPHEMERAL_CACHE_CONTROL;
      }
      return block;
    });
    req['system'] = systemBlocks as unknown as JsonValue;
  }

  // thinking(:334-360):adaptive + summarized;OFF → disabled;
  //   显式档位 → output_config.effort(旧 budget_tokens 形态已不支持,Android 注释同)
  if (params.model.abilities.indexOf('reasoning') >= 0) {
    if (params.reasoningLevel === 'off') {
      req['thinking'] = { type: 'disabled' };
    } else if (params.reasoningLevel === 'auto') {
      req['thinking'] = { type: 'adaptive', display: 'summarized' };
    } else {
      req['thinking'] = { type: 'adaptive', display: 'summarized' };
      req['output_config'] = { effort: reasoningLevelEffort(params.reasoningLevel) };
    }
  }

  // tools(:362-376);promptCaching → 最后一个 tool 打 cache_control
  if (params.model.abilities.indexOf('tool') >= 0 && params.tools.length > 0) {
    const tools: JsonValue[] = params.tools.map((tool, i: number): JsonObject => {
      const obj: JsonObject = {
        name: tool.name,
        description: tool.description,
        input_schema: tool.parameters,
      };
      if (setting.promptCaching && i === params.tools.length - 1) {
        obj['cache_control'] = EPHEMERAL_CACHE_CONTROL;
      }
      return obj;
    });
    req['tools'] = tools as unknown as JsonValue;
  }

  // R06/C04:stream 为调用模式强约束 —— customBody 不得覆盖(与 R06 口径一致;
  //   非流式请求回普通 JSON 被流式解析器静默吞掉是用户可见空回复的根因)
  return { ...mergeCustomBody(req, params.customBody), stream };
};
