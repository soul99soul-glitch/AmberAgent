// UIMessage / UIMessagePart — HarmonyOS port of ai/src/main/java/app/amber/ai/ui/Message.kt
//
// 线格式(DATA_SCHEMA_MATRIX J03,迁移硬约束):
//   - sealed part 鉴别器字段名 "type"(kotlinx 默认 classDiscriminator,Json.kt 未覆盖)
//   - SerialName 小写: text/image/video/audio/document/mini_app/reasoning/tool
//   - MessageRole 小写: system/user/assistant/tool
//   - ToolApprovalState sealed: {type:"auto"|"pending"|"approved"} / denied+reason / answered+answer
//   - UIMessageAnnotation: url_citation(title,url) / generation_interrupted(reason)
//   - metadata 对应 kotlinx JsonObject?,此处为 JsonObject | null
//
// 设计:ArkTS 友好 — interface + discriminated union + 工厂函数,无 class(同 deepread 模式)
// 流式 merge(appendChunk/Tool.merge/findToolMergeTarget)在切片 2 的 accumulator 中实现。

import type { JsonObject } from './json.ts';
import { newId, nowIso } from './ids.ts';
import type { TokenUsage } from './usage.ts';

// ===== 角色(对应 ai/core/MessageRole.kt,SerialName 小写) =====

export type MessageRole = 'system' | 'user' | 'assistant' | 'tool';

// ===== ToolApprovalState(对应 Message.kt:332-353 sealed class) =====

export type ToolApprovalState =
  | { type: 'auto' }
  | { type: 'pending' }
  | { type: 'approved' }
  | { type: 'denied'; reason: string }
  | { type: 'answered'; answer: string };

// 对应 ToolApprovalState.canResumeToolExecution()
export const canResumeToolExecution = (s: ToolApprovalState): boolean =>
  s.type === 'approved' || s.type === 'denied' || s.type === 'answered';

// ===== UIMessagePart(discriminated union,type 即 SerialName) =====

export interface UIMessagePartText {
  type: 'text';
  text: string;
  metadata: JsonObject | null;
}

export interface UIMessagePartImage {
  type: 'image';
  url: string;
  metadata: JsonObject | null;
}

export interface UIMessagePartVideo {
  type: 'video';
  url: string;
  mime: string; // default 'video/mp4'
  metadata: JsonObject | null;
}

export interface UIMessagePartAudio {
  type: 'audio';
  url: string;
  fileName: string; // default ''
  mime: string;     // default 'audio/mpeg'
  metadata: JsonObject | null;
}

export interface UIMessagePartDocument {
  type: 'document';
  url: string;
  fileName: string;
  mime: string;     // default 'text/*'
  metadata: JsonObject | null;
}

export interface UIMessagePartMiniApp {
  type: 'mini_app';
  appId: string;
  title: string;
  description: string;
  iconEmoji: string | null;
  category: string | null;
  permissions: string[];
  htmlHash: string | null;
  version: number;  // default 1
  metadata: JsonObject | null;
}

export interface UIMessagePartReasoning {
  type: 'reasoning';
  reasoning: string;
  createdAt: string;        // ISO 时间(Android 为 Instant)
  finishedAt: string | null;
  metadata: JsonObject | null;
}

export interface UIMessagePartTool {
  type: 'tool';
  toolCallId: string;
  toolName: string;
  input: string;                        // JSON 参数字符串
  output: UIMessagePart[];              // 工具执行结果(嵌套 parts)
  approvalState: ToolApprovalState;
  metadata: JsonObject | null;
}

export type UIMessagePart =
  | UIMessagePartText
  | UIMessagePartImage
  | UIMessagePartVideo
  | UIMessagePartAudio
  | UIMessagePartDocument
  | UIMessagePartMiniApp
  | UIMessagePartReasoning
  | UIMessagePartTool;

// ===== UIMessageAnnotation(Message.kt:592-613) =====

export type UIMessageAnnotation =
  | { type: 'url_citation'; title: string; url: string }
  | { type: 'generation_interrupted'; reason: string };

// ===== UIMessage =====

export interface UIMessage {
  id: string;
  role: MessageRole;
  parts: UIMessagePart[];
  annotations: UIMessageAnnotation[];
  createdAt: string;
  finishedAt: string | null;
  modelId: string | null;
  usage: TokenUsage | null;
  translation: string | null;
}

export type UIMessageOpts = Partial<Omit<UIMessage, 'role' | 'parts'>>;

export const makeUIMessage = (
  role: MessageRole,
  parts: UIMessagePart[] = [],
  opts: UIMessageOpts = {},
): UIMessage => ({
  id: opts.id ?? newId(),
  role,
  parts,
  annotations: opts.annotations ?? [],
  createdAt: opts.createdAt ?? nowIso(),
  finishedAt: opts.finishedAt ?? null,
  modelId: opts.modelId ?? null,
  usage: opts.usage ?? null,
  translation: opts.translation ?? null,
});

export const makeSystemMessage = (prompt: string): UIMessage =>
  makeUIMessage('system', [{ type: 'text', text: prompt, metadata: null }]);

export const makeUserMessage = (prompt: string): UIMessage =>
  makeUIMessage('user', [{ type: 'text', text: prompt, metadata: null }]);

export const makeAssistantMessage = (prompt: string): UIMessage =>
  makeUIMessage('assistant', [{ type: 'text', text: prompt, metadata: null }]);

// ===== MessageChunk / UIMessageChoice(Message.kt:615-629) =====

export interface UIMessageChoice {
  index: number;
  delta: UIMessage | null;
  message: UIMessage | null;
  finishReason: string | null;
}

export interface MessageChunk {
  id: string;
  model: string;
  choices: UIMessageChoice[];
  usage: TokenUsage | null;
}

// ===== Tool part 帮助函数(Message.kt:444-455) =====

export const isToolExecuted = (t: UIMessagePartTool): boolean => t.output.length > 0;

export const isToolPending = (t: UIMessagePartTool): boolean => t.approvalState.type === 'pending';

export const canToolResumeExecution = (t: UIMessagePartTool): boolean =>
  !isToolExecuted(t) && canResumeToolExecution(t.approvalState);

// inputAsJson:解析失败回退空对象(Android runCatching → JsonObject(emptyMap))
export const toolInputAsJson = (t: UIMessagePartTool): JsonObject => {
  try {
    const parsed: unknown = JSON.parse(t.input.trim().length === 0 ? '{}' : t.input);
    if (parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return parsed as JsonObject;
    }
    return {};
  } catch (_e) {
    return {};
  }
};

// ===== UIMessage 帮助函数 =====

// toText(Message.kt:164):只拼接 text parts,\n 分隔
export const toText = (msg: UIMessage): string =>
  msg.parts.map((p: UIMessagePart): string => (p.type === 'text' ? p.text : '')).join('\n');

// summaryAsText(Message.kt:155):[ROLE]: 前缀(大写枚举名)
export const summaryAsText = (msg: UIMessage): string =>
  `[${msg.role.toUpperCase()}]: ${toText(msg)}`;

export const getTools = (msg: UIMessage): UIMessagePartTool[] =>
  msg.parts.filter((p: UIMessagePart): p is UIMessagePartTool => p.type === 'tool');

// isValidToUpload(Message.kt:173)
export const isValidToUpload = (msg: UIMessage): boolean =>
  msg.parts.some((p: UIMessagePart): boolean => {
    switch (p.type) {
      case 'text': return p.text.trim().length > 0;
      case 'image': return p.url.trim().length > 0;
      case 'video': return p.url.trim().length > 0;
      case 'audio': return p.url.trim().length > 0;
      case 'document': return p.url.trim().length > 0;
      case 'reasoning': return p.reasoning.trim().length > 0;
      default: return true;
    }
  });

// hasBase64Part(Message.kt:191)
export const hasBase64Part = (msg: UIMessage): boolean =>
  msg.parts.some((p: UIMessagePart): boolean => p.type === 'image' && p.url.startsWith('data:'));

// isEmptyInputMessage(Message.kt:255):用户可输入内容判空(text/image/document/video/audio)
// 其余 part(reasoning/tool/mini_app)按空处理(Android else → true)
export const isEmptyInputMessage = (parts: UIMessagePart[]): boolean => {
  if (parts.length === 0) return true;
  return parts.every((p: UIMessagePart): boolean => {
    switch (p.type) {
      case 'text': return p.text.trim().length === 0;
      case 'image': return p.url.trim().length === 0;
      case 'document': return p.url.trim().length === 0;
      case 'video': return p.url.trim().length === 0;
      case 'audio': return p.url.trim().length === 0;
      default: return true;
    }
  });
};

// isEmptyUIMessage(Message.kt:272):UI 是否显示任何内容;reasoning 按内容判空
export const isEmptyUIMessage = (parts: UIMessagePart[]): boolean => {
  if (parts.length === 0) return true;
  return parts.every((p: UIMessagePart): boolean => {
    switch (p.type) {
      case 'text': return p.text.trim().length === 0;
      case 'image': return p.url.trim().length === 0;
      case 'document': return p.url.trim().length === 0;
      case 'reasoning': return p.reasoning.trim().length === 0;
      case 'video': return p.url.trim().length === 0;
      case 'audio': return p.url.trim().length === 0;
      default: return true;
    }
  });
};
