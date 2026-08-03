/**
 * Token usage information
 * @see ai/src/main/java/me/rerere/ai/core/Usage.kt
 */
export interface TokenUsage {
  promptTokens: number;
  completionTokens: number;
  cachedTokens: number;
  totalTokens: number;
}
