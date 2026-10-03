import type { AbortSignalLike } from './runtime_api.ts';
import type { UIMessage, UIMessagePartText } from '../agent/message.ts';
// AiClient — OpenAI-compatible chat completion 客户端
// UIMessage 从 agent/message.ts 导入(真正的 typed interface)

export type { UIMessage };

export interface Tool {
  name: string;
  description: string;
  schema: object;  // JSON schema
  allowsAutoApproval?: boolean;
  isHighRisk?: boolean;
  // DeepRead writer tools provide the local execution bridge. Platform clients
  // may omit this field for ordinary model tools.
  execute?: (input: string) => Promise<UIMessagePartText[]>;
}

// generateText 参数(ArkTS 显式 interface,不用内联对象字面量类型)
export interface GenerateTextParams {
  model: string;
  messages: UIMessage[];
  tools?: Tool[];
  maxSteps?: number;
  autoApproveTools?: boolean;
  autoApproveHighRiskTools?: boolean;
  autoApprovedToolNames?: string[];
  stream?: boolean;
  signal?: AbortSignalLike;
}

export interface AiClient {
  generateText(params: GenerateTextParams): Promise<UIMessage[]>;
}
