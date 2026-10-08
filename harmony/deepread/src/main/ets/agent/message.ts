// Canonical UIMessage schema shared by DeepRead and Chat.
// Wire discriminants and timestamps match Android ai/ui/Message.kt:
// `type`, object-valued approval state, and ISO timestamps.

import type { JsonObject } from './json.ts';
import { newId, nowIso } from './ids.ts';
import type { TokenUsage } from './usage.ts';
import { hasProtocolReasoningContent } from './reasoning_metadata.ts';

export type MessageRole = 'system' | 'user' | 'assistant' | 'tool';

// 传输事实与 UI reveal 分离：live 只表示请求完成前已收到解析后的增量；
// buffered 表示走流式端口但首个可见帧直到收流边界；unavailable 表示非流式或无增量。
export type StreamTransportState = 'live' | 'buffered' | 'unavailable';

export type ToolApprovalState =
  | { type: 'auto' }
  | { type: 'pending' }
  | { type: 'approved' }
  | { type: 'denied'; reason: string }
  | { type: 'answered'; answer: string };

export const canResumeToolExecution = (state: ToolApprovalState): boolean =>
  state.type === 'approved' || state.type === 'denied' || state.type === 'answered';

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
  mime: string;
  metadata: JsonObject | null;
}

export interface UIMessagePartAudio {
  type: 'audio';
  url: string;
  fileName: string;
  mime: string;
  metadata: JsonObject | null;
}

export interface UIMessagePartDocument {
  type: 'document';
  url: string;
  fileName: string;
  mime: string;
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
  version: number;
  metadata: JsonObject | null;
}

export interface UIMessagePartReasoning {
  type: 'reasoning';
  reasoning: string;
  createdAt: string;
  finishedAt: string | null;
  metadata: JsonObject | null;
}

export interface UIMessagePartTool {
  type: 'tool';
  toolCallId: string;
  toolName: string;
  input: string;
  output: UIMessagePart[];
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

export interface UIMessageUrlContextAnnotation {
  type: 'url_context';
  url: string;
  status: string;
}

export interface UIMessageGoogleSearchSuggestionsAnnotation {
  type: 'google_search_suggestions';
  html: string;
}

export type UIMessageAnnotation =
  | { type: 'url_citation'; title: string; url: string }
  | { type: 'generation_interrupted'; reason: string }
  | UIMessageUrlContextAnnotation
  | UIMessageGoogleSearchSuggestionsAnnotation;

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

const textPart = (text: string): UIMessagePartText => ({ type: 'text', text, metadata: null });

export const makeSystemMessage = (prompt: string): UIMessage =>
  makeUIMessage('system', [textPart(prompt)]);

export const makeUserMessage = (prompt: string): UIMessage =>
  makeUIMessage('user', [textPart(prompt)]);

export const makeAssistantMessage = (prompt: string): UIMessage =>
  makeUIMessage('assistant', [textPart(prompt)]);

export const finishAssistantMessage = (
  message: UIMessage, finishedAt: string = nowIso(),
): UIMessage => message.role === 'assistant' && message.finishedAt === null
  ? { ...message, finishedAt }
  : message;

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

export const isToolExecuted = (tool: UIMessagePartTool): boolean => tool.output.length > 0;

export const isToolPending = (tool: UIMessagePartTool): boolean => tool.approvalState.type === 'pending';

export const canToolResumeExecution = (tool: UIMessagePartTool): boolean =>
  !isToolExecuted(tool) && canResumeToolExecution(tool.approvalState);

export const isToolAwaitingExecution = (tool: UIMessagePartTool): boolean =>
  !isToolExecuted(tool) && tool.approvalState.type !== 'pending';

export const toolInputAsJson = (tool: UIMessagePartTool): JsonObject => {
  try {
    const parsed: unknown = JSON.parse(tool.input.trim().length === 0 ? '{}' : tool.input);
    if (parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return parsed as JsonObject;
    }
    return {};
  } catch (_error) {
    return {};
  }
};

export const toText = (message: UIMessage): string =>
  message.parts.map((part: UIMessagePart): string => part.type === 'text' ? part.text : '').join('\n');

export const reasoningPartText = (message: UIMessage): string =>
  message.parts.map((part: UIMessagePart): string => part.type === 'reasoning' ? part.reasoning : '').join('');

export const summaryAsText = (message: UIMessage): string =>
  `[${message.role.toUpperCase()}]: ${toText(message)}`;

export const getTools = (message: UIMessage): UIMessagePartTool[] =>
  message.parts.filter((part: UIMessagePart): part is UIMessagePartTool => part.type === 'tool');

export const latestAssistantText = (messages: UIMessage[]): string => {
  for (let i = messages.length - 1; i >= 0; i--) {
    const message: UIMessage = messages[i];
    if (message.role === 'assistant') {
      return message.parts
        .filter((part: UIMessagePart): part is UIMessagePartText => part.type === 'text')
        .map((part: UIMessagePartText): string => part.text)
        .join('');
    }
  }
  return '';
};

export const isValidToUpload = (message: UIMessage): boolean =>
  message.parts.some((part: UIMessagePart): boolean => {
    switch (part.type) {
      case 'text': return part.text.trim().length > 0;
      case 'image': return part.url.trim().length > 0;
      case 'video': return part.url.trim().length > 0;
      case 'audio': return part.url.trim().length > 0;
      case 'document': return part.url.trim().length > 0;
      case 'reasoning': return part.reasoning.trim().length > 0 || hasProtocolReasoningContent(part.metadata);
      default: return true;
    }
  });

export const hasBase64Part = (message: UIMessage): boolean =>
  message.parts.some((part: UIMessagePart): boolean => part.type === 'image' && part.url.startsWith('data:'));

export const isEmptyInputMessage = (parts: UIMessagePart[]): boolean => {
  if (parts.length === 0) return true;
  return parts.every((part: UIMessagePart): boolean => {
    switch (part.type) {
      case 'text': return part.text.trim().length === 0;
      case 'image': return part.url.trim().length === 0;
      case 'document': return part.url.trim().length === 0;
      case 'video': return part.url.trim().length === 0;
      case 'audio': return part.url.trim().length === 0;
      default: return true;
    }
  });
};

export const isEmptyUIMessage = (parts: UIMessagePart[]): boolean => {
  if (parts.length === 0) return true;
  return parts.every((part: UIMessagePart): boolean => {
    switch (part.type) {
      case 'text': return part.text.trim().length === 0;
      case 'image': return part.url.trim().length === 0;
      case 'document': return part.url.trim().length === 0;
      case 'reasoning': return part.reasoning.trim().length === 0;
      case 'video': return part.url.trim().length === 0;
      case 'audio': return part.url.trim().length === 0;
      default: return true;
    }
  });
};
