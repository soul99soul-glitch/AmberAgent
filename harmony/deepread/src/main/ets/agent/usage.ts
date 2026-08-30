// Token usage carried by the canonical UIMessage schema.

export interface TokenUsage {
  promptTokens: number;
  completionTokens: number;
  cachedTokens: number;
  totalTokens: number;
}
