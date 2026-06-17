/** Source-only Agent loop contract types for future ArkTS implementation. */

export type MessageRole = 'SYSTEM' | 'USER' | 'ASSISTANT' | 'TOOL';

export type UIMessagePart =
  | { type: 'text'; text: string }
  | { type: 'reasoning'; reasoning: string; finishedAt?: string | null }
  | {
      type: 'tool';
      toolCallId: string;
      toolName: string;
      input: string;
      output: UIMessagePart[];
      approvalState: 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXECUTED' | string;
      metadata?: Record<string, unknown>;
    }
  | { type: 'image'; url: string; mimeType?: string }
  | { type: 'document'; url: string; mimeType?: string; name?: string };

export interface UIMessageContract {
  id?: string;
  role: MessageRole;
  parts: UIMessagePart[];
  annotations?: Array<Record<string, unknown>>;
  createdAt?: string;
  finishedAt?: string;
  modelId?: string;
}

export type TurnEvent =
  | { type: 'delta'; text: string }
  | { type: 'reasoning_delta'; text: string }
  | { type: 'tool_call'; id: string; name: string; args: string }
  | { type: 'tool_result'; id: string; output: string; isError: boolean; durationMs?: number }
  | { type: 'done' }
  | { type: 'error'; message: string; recoverable?: boolean }
  | { type: 'cancel_requested'; source: 'user' | 'system' }
  | { type: 'cancelled' };

export type AgentRunStatus = 'started' | 'streaming' | 'tool_pending' | 'tool_result' | 'final' | 'failed' | 'cancelled';

export interface AgentCheckpoint {
  status: AgentRunStatus;
  conversationId: string;
  messageNodeId?: string;
  messages: UIMessageContract[];
  updatedAt: string;
}
