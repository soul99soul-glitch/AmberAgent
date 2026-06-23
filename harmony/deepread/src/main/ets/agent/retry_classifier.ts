// RetryClassifier — 照搬 Android GenerationRetry.kt
// 决定 LLM 调用失败时是否重试,以及重试延迟

export type ErrorCategory =
  | 'cancelled'
  | 'context'
  | 'quota'
  | 'safety'
  | 'auth'
  | 'bad_request'
  | 'rate_limit'
  | 'timeout'
  | 'server'
  | 'network'
  | 'unknown';

export interface RetryDecision {
  retryable: boolean;
  category: ErrorCategory;
  delayMs: number;     // 0 if not retryable
  attempt: number;     // 本次是第几次重试(1-based)
  reason: string;
}

export interface RetrySetting {
  enabled: boolean;
  maxRetries: number;
  initialDelayMs: number;
  maxDelayMs: number;
  jitterRatio: number;  // 0..1
}

export const DEFAULT_RETRY_SETTING: RetrySetting = {
  enabled: true,
  maxRetries: 5,
  initialDelayMs: 1_000,
  maxDelayMs: 16_000,
  jitterRatio: 0.15,
};

// 把任意 error 归类(根据 message / HTTP code / 类型)
export const classifyError = (error: unknown): ErrorCategory => {
  if (error === null || typeof error !== 'object') {
    return errorToCategory(String(error));
  }
  // AbortError / Cancelled
  if (error instanceof Error && (error.name === 'AbortError' || error.name === 'CancellationError')) {
    return 'cancelled';
  }
  const msg = extractMessage(error);
  const status = extractStatus(error);
  // 按优先级判断(照搬 Android GenerationFailureClassifier)
  if (msg && /context_length|context too large|maximum context|too many tokens/i.test(msg)) return 'context';
  if (msg && /insufficient_quota|quota|balance|余额|credit/i.test(msg)) return 'quota';
  if (msg && /content_policy|safety|moderation|blocked by policy|安全/i.test(msg)) return 'safety';
  if (status === 401 || status === 403) return 'auth';
  if (status === 400 || (msg && /model not found|model_not_found|invalid parameter/i.test(msg))) return 'bad_request';
  if (status === 429 || (msg && /too many requests/i.test(msg))) return 'rate_limit';
  if (status === 408 || (msg && /timeout|timed out/i.test(msg))) return 'timeout';
  if (status === 500 || status === 502 || status === 503 || status === 504 ||
      (msg && /overloaded|temporarily unavailable/i.test(msg))) return 'server';
  if (msg && /connection reset|socket closed|stream was reset|unexpected end|eof|network|failed to connect|ECONN|ENOTFOUND|ECONNRESET|EPIPE/i.test(msg)) return 'network';
  return 'unknown';
};

const errorToCategory = (msg: string): ErrorCategory => {
  if (/timeout|timed out/i.test(msg)) return 'timeout';
  if (/network|connection|socket/i.test(msg)) return 'network';
  return 'unknown';
};

// 哪些类别可重试(照搬 Android)
export const isRetryableCategory = (cat: ErrorCategory): boolean =>
  cat === 'rate_limit' || cat === 'timeout' || cat === 'server' || cat === 'network';

// 决定重试(返回 decision)
export const decideRetry = (
  error: unknown,
  attempt: number,             // 当前已重试次数(0=首次失败)
  setting: RetrySetting = DEFAULT_RETRY_SETTING,
): RetryDecision => {
  const category = classifyError(error);
  const retryable = setting.enabled && isRetryableCategory(category) && attempt < setting.maxRetries;
  const delayMs = retryable ? delayForAttempt(attempt, setting) : 0;
  return {
    retryable,
    category,
    delayMs,
    attempt: attempt + 1,
    reason: retryable
      ? `${category} error, will retry (attempt ${attempt + 1}/${setting.maxRetries})`
      : `${category} error, not retryable`,
  };
};

// 指数退避 + 抖动
export const delayForAttempt = (attempt: number, setting: RetrySetting): number => {
  const base = Math.min(
    setting.maxDelayMs,
    setting.initialDelayMs * Math.pow(2, attempt),
  );
  // 抖动:base ± jitterRatio * base
  const jitter = base * setting.jitterRatio * (Math.random() * 2 - 1);
  return Math.max(0, Math.round(base + jitter));
};

const extractMessage = (error: unknown): string => {
  if (error instanceof Error) return error.message;
  if (typeof error === 'object' && error !== null) {
    const e = error as Record<string, unknown>;
    if (typeof e.message === 'string') return e.message;
  }
  return '';
};

const extractStatus = (error: unknown): number | null => {
  if (typeof error === 'object' && error !== null) {
    const e = error as Record<string, unknown>;
    if (typeof e.status === 'number') return e.status;
    if (typeof e.statusCode === 'number') return e.statusCode;
    if (typeof e.code === 'number') return e.code;
  }
  return null;
};
