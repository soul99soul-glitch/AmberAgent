// webmount_oauth — WebMount OAuth PKCE（Android PkceUtils + FeishuOAuthProvider 对齐）
//
// 纯逻辑：PKCE verifier/challenge/state、飞书 authorize URL、token exchange 请求体。
// SHA-256 / 随机数经 Port 注入（entry = CryptoArchitectureKit）。

export interface WebMountCryptoPort {
  /** UTF-8 字符串 → base64url(无填充) 的 SHA-256 */
  sha256Base64Url(input: string): Promise<string>;
  randomBase64Url(byteLength: number): Promise<string>;
}

export const defaultFeishuRedirectUri = 'http://127.0.0.1:53682/callback';

export const feishuAuthorizationEndpoint = 'https://accounts.feishu.cn/open-apis/authen/v1/authorize';
export const feishuTokenEndpoint = 'https://open.feishu.cn/open-apis/authen/v2/oauth/token';

export interface WebMountPkce {
  codeVerifier: string;
  codeChallenge: string;
  state: string;
}

export const createWebMountPkce = async (
  crypto: WebMountCryptoPort, byteLength: number = 32,
): Promise<WebMountPkce> => {
  const codeVerifier = await crypto.randomBase64Url(byteLength);
  const codeChallenge = await crypto.sha256Base64Url(codeVerifier);
  const state = await crypto.randomBase64Url(16);
  return { codeVerifier, codeChallenge, state };
};

export const buildFeishuAuthorizationUrl = (input: {
  appId: string;
  redirectUri?: string;
  state: string;
  codeChallenge: string;
  scope?: string;
}): string => {
  const redirect = input.redirectUri !== undefined && input.redirectUri.length > 0
    ? input.redirectUri : defaultFeishuRedirectUri;
  const pairs: Array<[string, string]> = [
    ['app_id', input.appId],
    ['client_id', input.appId],
    ['redirect_uri', redirect],
    ['response_type', 'code'],
    ['state', input.state],
    ['code_challenge', input.codeChallenge],
    ['code_challenge_method', 'S256'],
  ];
  if (input.scope !== undefined && input.scope.length > 0) {
    pairs.push(['scope', input.scope]);
  }
  const qs = pairs
    .map(([k, v]): string => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`)
    .join('&');
  return `${feishuAuthorizationEndpoint}?${qs}`;
};

export interface FeishuTokenRequest {
  appId: string;
  appSecret: string;
  code: string;
  redirectUri?: string;
  codeVerifier: string;
}

export const buildFeishuTokenBody = (req: FeishuTokenRequest): string => {
  const redirect = req.redirectUri !== undefined && req.redirectUri.length > 0
    ? req.redirectUri : defaultFeishuRedirectUri;
  return JSON.stringify({
    grant_type: 'authorization_code',
    client_id: req.appId,
    client_secret: req.appSecret,
    code: req.code,
    redirect_uri: redirect,
    code_verifier: req.codeVerifier,
  });
};

export interface WebMountOAuthToken {
  accessToken: string;
  refreshToken: string | null;
  expiresAtMillis: number;
  tokenType: string;
  scope: string | null;
}

export const parseFeishuTokenResponse = (
  body: string, nowMs: number,
): WebMountOAuthToken => {
  const parsed: unknown = JSON.parse(body);
  if (typeof parsed !== 'object' || parsed === null) {
    throw new Error('feishu token: invalid JSON');
  }
  const obj = parsed as Record<string, unknown>;
  const access = obj['access_token'];
  if (typeof access !== 'string' || access.length === 0) {
    const err = obj['error_description'] ?? obj['error'] ?? obj['msg'];
    throw new Error(`feishu token failed: ${typeof err === 'string' ? err : 'no access_token'}`);
  }
  const expiresIn = typeof obj['expires_in'] === 'number' ? obj['expires_in'] : 0;
  return {
    accessToken: access,
    refreshToken: typeof obj['refresh_token'] === 'string' ? obj['refresh_token'] : null,
    expiresAtMillis: nowMs + Math.max(0, expiresIn) * 1000,
    tokenType: typeof obj['token_type'] === 'string' ? obj['token_type'] : 'Bearer',
    scope: typeof obj['scope'] === 'string' ? obj['scope'] : null,
  };
};

export interface WebMountOAuthTokenStore {
  get(providerId: string): Promise<WebMountOAuthToken | null>;
  set(providerId: string, token: WebMountOAuthToken | null): Promise<void>;
}

export const webMountTokenUsable = (
  token: WebMountOAuthToken | null, nowMs: number, skewMs: number = 60_000,
): boolean => token !== null && token.expiresAtMillis - nowMs > skewMs;
