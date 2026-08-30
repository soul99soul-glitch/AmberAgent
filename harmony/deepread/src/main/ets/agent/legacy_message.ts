// Upgrade-only adapter for DeepRead's pre-C1 message wire shape.
// Production code must construct the canonical types from message.ts directly.

import type {
  MessageRole, ToolApprovalState, UIMessage, UIMessagePart,
  UIMessagePartReasoning, UIMessagePartTool,
} from './message.ts';
import type { JsonObject } from './json.ts';

export type LegacyToolApprovalState = 'auto' | 'pending' | 'approved' | 'denied' | 'answered';

export interface LegacyUIMessagePartText {
  kind: 'text';
  text: string;
}

export interface LegacyUIMessagePartImage {
  kind: 'image';
  url: string;
}

export interface LegacyUIMessagePartReasoning {
  kind: 'reasoning';
  reasoning: string;
  createdAt: number;
  finishedAt: number | null;
}

export interface LegacyUIMessagePartTool {
  kind: 'tool';
  toolCallId: string;
  toolName: string;
  input: string;
  output: LegacyUIMessagePart[];
  approvalState: LegacyToolApprovalState;
  streamIndex?: number;
}

export type LegacyUIMessagePart =
  | LegacyUIMessagePartText
  | LegacyUIMessagePartImage
  | LegacyUIMessagePartReasoning
  | LegacyUIMessagePartTool;

export interface LegacyUIMessage {
  id: string;
  role: MessageRole;
  parts: LegacyUIMessagePart[];
  createdAt: number;
  finishedAt: number | null;
  modelId: string | null;
}

const migrateLegacyApprovalState = (state: LegacyToolApprovalState): ToolApprovalState => {
  switch (state) {
    case 'auto': return { type: 'auto' };
    case 'pending': return { type: 'pending' };
    case 'approved': return { type: 'approved' };
    case 'denied': return { type: 'denied', reason: '' };
    case 'answered': return { type: 'answered', answer: '' };
  }
};

const legacyEpochToIso = (epochMillis: number): string => new Date(epochMillis).toISOString();

const migrateLegacyPart = (part: LegacyUIMessagePart): UIMessagePart => {
  switch (part.kind) {
    case 'text':
      return { type: 'text', text: part.text, metadata: null };
    case 'image':
      return { type: 'image', url: part.url, metadata: null };
    case 'reasoning': {
      const reasoning: UIMessagePartReasoning = {
        type: 'reasoning',
        reasoning: part.reasoning,
        createdAt: legacyEpochToIso(part.createdAt),
        finishedAt: part.finishedAt === null ? null : legacyEpochToIso(part.finishedAt),
        metadata: null,
      };
      return reasoning;
    }
    case 'tool': {
      const metadata: JsonObject | null = part.streamIndex === undefined
        ? null
        : { stream_tool_index: part.streamIndex };
      const tool: UIMessagePartTool = {
        type: 'tool',
        toolCallId: part.toolCallId,
        toolName: part.toolName,
        input: part.input,
        output: part.output.map(migrateLegacyPart),
        approvalState: migrateLegacyApprovalState(part.approvalState),
        metadata,
      };
      return tool;
    }
  }
};

export const migrateLegacyUIMessage = (message: LegacyUIMessage): UIMessage => ({
  id: message.id,
  role: message.role,
  parts: message.parts.map(migrateLegacyPart),
  annotations: [],
  createdAt: legacyEpochToIso(message.createdAt),
  finishedAt: message.finishedAt === null ? null : legacyEpochToIso(message.finishedAt),
  modelId: message.modelId,
  usage: null,
  translation: null,
});
