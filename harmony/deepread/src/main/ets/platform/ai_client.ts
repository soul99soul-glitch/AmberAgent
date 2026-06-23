import type { AbortSignalLike } from './runtime_api.ts';
// AiClient — OpenAI-compatible chat completion 客户端
// 注意:UIMessage 类型留 Plan 2 展开,这里先简化为不透明类型

// Plan 2 会展开 UIMessage(对应 Android ai/ui/Message.kt),这里先占位
export type UIMessage = unknown;

export interface Tool {
  name: string;
  description: string;
  schema: object;  // JSON schema
}

export interface AiClient {
  generateText(params: {
    model: string;
    messages: UIMessage[];
    tools?: Tool[];
    maxSteps?: number;
    autoApproveTools?: boolean;
    autoApproveHighRiskTools?: boolean;
    autoApprovedToolNames?: string[];
    stream?: boolean;
    signal?: AbortSignalLike;
  }): Promise<UIMessage[]>;
}
