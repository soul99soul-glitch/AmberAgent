// TokenUsage — HarmonyOS port of ai/src/main/java/app/amber/ai/core/Usage.kt
// 线格式:字段名 1:1(promptTokens/completionTokens/cachedTokens/totalTokens)

export interface TokenUsage {
  promptTokens: number;
  completionTokens: number;
  cachedTokens: number;
  totalTokens: number;
}

// 合并规则(Android Usage.kt:13):
//   prompt/completion/cached 各自: other > 0 取 other,否则取 this(无 this 取 0)
//   totalTokens 重新计算 = prompt + completion(不采纳 other.totalTokens)
export const mergeUsage = (current: TokenUsage | null, other: TokenUsage): TokenUsage => {
  const promptTokens: number = other.promptTokens > 0
    ? other.promptTokens
    : (current !== null ? current.promptTokens : 0);
  const completionTokens: number = other.completionTokens > 0
    ? other.completionTokens
    : (current !== null ? current.completionTokens : 0);
  const cachedTokens: number = other.cachedTokens > 0
    ? other.cachedTokens
    : (current !== null ? current.cachedTokens : 0);
  return {
    promptTokens,
    completionTokens,
    cachedTokens,
    totalTokens: promptTokens + completionTokens,
  };
};
