// generation_retry.ts — 生成重试策略(纯逻辑层)
//
// Android 基准: core/ai/api/src/main/kotlin/app/amber/core/ai/GenerationRetry.kt(全文 166 行)
// 集成语义: GenerationHandler.kt runProviderCallWithRetry(:774-820)
//   - attempt 从 1 起;decide 在每次失败后调用;attempt > maxRetries 停止
//   - 状态文案 strings.xml:386 "Connection interrupted. Retrying in %1$d s (%2$d/%3$d): %4$s"
//     (秒 = (delayMs/1000) coerceAtLeast 1, Kotlin 整除)
//
// JS 映射说明:
//   - CancellationException → Error.name === 'AbortError'(ArkTS/浏览器 Abort 约定)
//   - Throwable.cause 链 → Error.cause(ES2022),seen 集合防循环
//   - 类名 → err.constructor.name(SocketTimeoutException 等具体类由 adapter 抛)
//   - Kotlin Long 位移 shl → JS <<(指数合理范围内同语义)

// strings.xml:386 逐字
export const RETRY_STATUS_TEMPLATE: string =
  'Connection interrupted. Retrying in %1$d s (%2$d/%3$d): %4$s';

// GenerationRetry.kt:14-20
export interface GenerationRetrySetting {
  enabled: boolean;
  maxRetries: number;
  initialDelayMs: number;
  maxDelayMs: number;
  jitterRatio: number;
}

export const makeGenerationRetrySetting = (
  opts: Partial<GenerationRetrySetting>,
): GenerationRetrySetting => ({
  enabled: opts.enabled ?? true,
  maxRetries: opts.maxRetries ?? 5,
  initialDelayMs: opts.initialDelayMs ?? 1000,
  maxDelayMs: opts.maxDelayMs ?? 16000,
  jitterRatio: opts.jitterRatio ?? 0.15,
});

// GenerationRetry.kt:22-35
export type GenerationFailureCategory =
  | 'NETWORK' | 'TIMEOUT' | 'RATE_LIMIT' | 'SERVER' | 'AUTH' | 'BAD_REQUEST'
  | 'QUOTA' | 'SAFETY' | 'CONTEXT' | 'CANCELLED' | 'TEMPORARY' | 'UNKNOWN';

// GenerationRetry.kt:37-41
export interface GenerationFailureClassification {
  category: GenerationFailureCategory;
  retryable: boolean;
  reason: string;
}

// GenerationRetry.kt:43-48
export interface GenerationRetryDecision {
  retryable: boolean;
  category: GenerationFailureCategory;
  reason: string;
  delayMs: number;
}

const containsAny = (text: string, needles: string[]): boolean =>
  needles.some((n: string): boolean => text.includes(n));

// ArkTS SDK lib 未声明 ES2022 Error.cause(运行时存在) — 交叉类型补齐
type CausedError = Error & { cause?: unknown };

// errorText(:139-146):cause 链拼接「类名:消息\n」lowercase;seen 防循环
const errorText = (error: Error): string => {
  let out: string = '';
  const seen = new Set<Error>();
  let current: Error | undefined = error;
  while (current !== undefined && !seen.has(current)) {
    seen.add(current);
    out += `${current.constructor.name}:${current.message}\n`;
    const cause: unknown = (current as CausedError).cause;
    current = cause instanceof Error ? cause : undefined;
  }
  return out.toLowerCase();
};

// hasCause<T>(:148-156):cause 链上存在指定类名
const hasCauseNamed = (error: Error, names: string[]): boolean => {
  const seen = new Set<Error>();
  let current: Error | undefined = error;
  while (current !== undefined && !seen.has(current)) {
    if (names.includes(current.constructor.name)) return true;
    seen.add(current);
    const cause: unknown = (current as CausedError).cause;
    current = cause instanceof Error ? cause : undefined;
  }
  return false;
};

const NETWORK_CAUSE_NAMES: string[] = [
  'UnknownHostException', 'ConnectException', 'SocketException', 'SSLException',
];

// classify(:51-102) — 分支顺序忠实(优先级即语义)
export const classifyGenerationFailure = (error: Error): GenerationFailureClassification => {
  // CancellationException(:52)→ JS AbortError 约定
  if (error.name === 'AbortError') {
    return { category: 'CANCELLED', retryable: false, reason: 'generation was cancelled' };
  }

  const text: string = errorText(error);

  if (containsAny(text, ['context_length', 'context too large', 'maximum context', 'too many tokens'])) {
    return { category: 'CONTEXT', retryable: false, reason: 'context is too large' };
  }
  if (containsAny(text, ['insufficient_quota', 'quota', 'balance', '余额', 'credit'])) {
    return { category: 'QUOTA', retryable: false, reason: 'quota or balance is insufficient' };
  }
  if (containsAny(text, ['content_policy', 'safety', 'moderation', 'blocked by policy', '安全'])) {
    return { category: 'SAFETY', retryable: false, reason: 'content was blocked by safety policy' };
  }
  if (containsAny(text, ['401', 'unauthorized', 'invalid api key', 'api key', 'authentication'])) {
    return { category: 'AUTH', retryable: false, reason: 'authentication failed' };
  }
  if (containsAny(text, ['403', 'forbidden', 'permission denied'])) {
    return { category: 'AUTH', retryable: false, reason: 'permission was denied' };
  }
  if (containsAny(text, ['400', 'bad request', 'invalid request', 'invalid parameter', 'model not found', 'model_not_found'])) {
    return { category: 'BAD_REQUEST', retryable: false, reason: 'request is invalid' };
  }
  if (containsAny(text, ['429', 'rate limit', 'too many requests'])) {
    return { category: 'RATE_LIMIT', retryable: true, reason: 'provider rate limited the request' };
  }
  if (containsAny(text, ['408', 'timeout', 'timed out']) || hasCauseNamed(error, ['SocketTimeoutException'])) {
    return { category: 'TIMEOUT', retryable: true, reason: 'request timed out' };
  }
  if (containsAny(text, ['500', '502', '503', '504', 'temporarily unavailable', 'overloaded', 'server error'])) {
    return { category: 'SERVER', retryable: true, reason: 'provider is temporarily unavailable' };
  }
  if (hasCauseNamed(error, NETWORK_CAUSE_NAMES) || containsAny(text, [
    'connection reset', 'socket closed', 'stream was reset', 'unexpected end',
    'eof', 'network', 'failed to connect',
  ])) {
    return { category: 'NETWORK', retryable: true, reason: 'network stream was interrupted' };
  }
  return { category: 'UNKNOWN', retryable: false, reason: 'failure is not known to be retryable' };
};

// decide(:104-124)
export const decideGenerationRetry = (
  error: Error,
  attempt: number,
  setting: GenerationRetrySetting,
  random: () => number = Math.random,
): GenerationRetryDecision => {
  const classification: GenerationFailureClassification = classifyGenerationFailure(error);
  if (!setting.enabled || !classification.retryable || attempt > setting.maxRetries) {
    return {
      retryable: false,
      category: classification.category,
      reason: classification.reason,
      delayMs: 0,
    };
  }
  return {
    retryable: true,
    category: classification.category,
    reason: classification.reason,
    delayMs: delayForAttempt(attempt, setting, random),
  };
};

// delayForAttempt(:126-136):
//   base = min(maxDelayMs, initialDelayMs shl (attempt-1));jitterRange = trunc(base*ratio);
//   result = base + randInt[-jitter, jitter] 闭区间,coerceAtLeast(0)
export const delayForAttempt = (
  attempt: number,
  setting: GenerationRetrySetting,
  random: () => number = Math.random,
): number => {
  const exponent: number = Math.max(0, attempt - 1);
  // 算术指数:<< 位移 32 位截断,attempt≥32 时 initialDelayMs<<31 变负数,
  // min(maxDelayMs, 负数) 为负 → 重试变立即重试
  const base: number = Math.min(setting.maxDelayMs, setting.initialDelayMs * (2 ** exponent));
  const jitterRange: number = Math.max(0, Math.trunc(base * setting.jitterRatio));
  if (jitterRange === 0) return base;
  const jitter: number = Math.floor(random() * (2 * jitterRange + 1)) - jitterRange;
  return Math.max(0, base + jitter);
};
