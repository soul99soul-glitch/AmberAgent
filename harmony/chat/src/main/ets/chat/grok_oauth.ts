// Grok OAuth 状态机 + CLI proxy 请求(纯逻辑)
//
// Android 基准: grok/GrokOAuth.kt:33-73 常量与 GrokAuthStatus
// 裁剪: 系统浏览器登录 / loopback server / Keystore 在 entry Port;
//      本切片: 常量、tokens→status、refresh skew、CLI proxy 请求构造。

export const GROK_OAUTH_AUTHORIZATION_ENDPOINT = 'https://auth.x.ai/oauth2/authorize';
export const GROK_OAUTH_TOKEN_ENDPOINT = 'https://auth.x.ai/oauth2/token';
export const GROK_CLI_PROXY_BASE_URL = 'https://cli-chat-proxy.grok.com/v1';
export const GROK_CLI_PROXY_HOST = 'cli-chat-proxy.grok.com';
export const GROK_OAUTH_REDIRECT_URI = 'http://127.0.0.1:8787/callback';
export const GROK_OAUTH_SCOPE =
  'openid profile email offline_access grok-cli:access api:access conversations:read conversations:write';
export const GROK_REFRESH_SKEW_MS = 2 * 60 * 1000;
export const GROK_FALLBACK_TOKEN_LIFETIME_MS = 60 * 60 * 1000;

export interface GrokOAuthTokens {
  accessToken: string;
  refreshToken: string | null;
  expiresAtMillis: number;
  idToken: string | null;
  email: string | null;
}

export type GrokAuthStatusCode =
  | 'not_signed_in'
  | 'token_missing'
  | 'token_expired'
  | 'ready';

export interface GrokAuthStatus {
  code: GrokAuthStatusCode;
  usable: boolean;
}

export const grokAuthStatusFrom = (
  tokens: GrokOAuthTokens | null | undefined,
  nowMillis: number,
): GrokAuthStatus => {
  if (tokens === null || tokens === undefined) {
    return { code: 'not_signed_in', usable: false };
  }
  if (tokens.accessToken.trim().length === 0) {
    return { code: 'token_missing', usable: false };
  }
  if (tokens.expiresAtMillis <= nowMillis) {
    const canRefresh = tokens.refreshToken !== null && tokens.refreshToken.length > 0;
    return { code: 'token_expired', usable: canRefresh };
  }
  return { code: 'ready', usable: true };
};

/** refresh 提前量判定(Android REFRESH_SKEW_MS) */
export const grokNeedsRefresh = (
  tokens: GrokOAuthTokens | null | undefined,
  nowMillis: number,
): boolean => {
  if (tokens === null || tokens === undefined) return false;
  return tokens.expiresAtMillis - nowMillis <= GROK_REFRESH_SKEW_MS;
};

export interface GrokTokenStore {
  get(providerId: string): Promise<GrokOAuthTokens | null>;
  set(providerId: string, tokens: GrokOAuthTokens | null): Promise<void>;
}

export interface GrokChatRequestDeps {
  setting: { baseUrl?: string };
  nowMillis: () => number;
  store: GrokTokenStore;
  providerId: string;
}

export class GrokAuthError extends Error {
  readonly statusCode: GrokAuthStatusCode | 'refresh_failed';
  constructor(statusCode: GrokAuthStatusCode | 'refresh_failed', message: string) {
    super(message);
    this.name = 'GrokAuthError';
    this.statusCode = statusCode;
  }
}

/**
 * 解析可用 bearer:必要时 refresh;无 token / 过期且不可 refresh → 抛稳定错误。
 * refresh 通过注入的 refresher 完成(entry 接 HTTP)。
 */
export const resolveGrokBearer = async (
  deps: GrokChatRequestDeps,
  refresher?: (refreshToken: string) => Promise<Omit<GrokOAuthTokens, 'refreshToken'> & { refreshToken?: string | null }>,
): Promise<string> => {
  const tokens = await deps.store.get(deps.providerId);
  const now = deps.nowMillis();
  const status = grokAuthStatusFrom(tokens, now);
  if (status.code === 'not_signed_in') {
    throw new GrokAuthError('not_signed_in', 'Grok not signed in');
  }
  if (tokens === null) {
    throw new GrokAuthError('token_missing', 'Grok token missing');
  }
  if (grokNeedsRefresh(tokens, now)) {
    if (tokens.refreshToken === null || tokens.refreshToken.length === 0 || refresher === undefined) {
      if (!status.usable) {
        throw new GrokAuthError('token_expired', 'Grok token expired');
      }
      return tokens.accessToken;
    }
    try {
      const refreshed = await refresher(tokens.refreshToken);
      const next: GrokOAuthTokens = {
        accessToken: refreshed.accessToken,
        refreshToken: refreshed.refreshToken ?? tokens.refreshToken,
        expiresAtMillis: refreshed.expiresAtMillis,
        idToken: refreshed.idToken ?? null,
        email: refreshed.email ?? null,
      };
      await deps.store.set(deps.providerId, next);
      return next.accessToken;
    } catch (e) {
      throw new GrokAuthError('refresh_failed', e instanceof Error ? e.message : 'Grok refresh failed');
    }
  }
  return tokens.accessToken;
};

export interface GrokChatRequestBodyOpts {
  model: string;
  messages: Array<{ role: string; content: string }>;
  stream: boolean;
}

export const buildGrokCliChatRequest = (
  bearer: string,
  opts: GrokChatRequestBodyOpts,
): { url: string; method: string; headers: Record<string, string>; body: string } => ({
  url: `${GROK_CLI_PROXY_BASE_URL}/chat/completions`,
  method: 'POST',
  headers: {
    Authorization: `Bearer ${bearer}`,
    'Content-Type': 'application/json',
    Host: GROK_CLI_PROXY_HOST,
  },
  body: JSON.stringify({
    model: opts.model,
    messages: opts.messages,
    stream: opts.stream,
  }),
});
