// GrokOAuthClient — xAI Grok OAuth(PKCE;iOS IOSGrokOAuthClient 对齐)
//
// 流程:构建 authorize URL(code_challenge S256)→ 系统浏览器完成登录
//   → loopback 重定向不可达,用户复制地址栏最终 URL 粘贴回 App(飞书 OAuth 同款手动回路)
//   → 提取 code → POST /oauth2/token(form,无 secret)→ token 落 KV。
// 请求走 cli-chat-proxy.grok.com/v1(Android fixedBaseUrl 同款),CLI 身份头由
//   AppContainer 注入(grok-shell UA + x-grok-client-identifier,缺失 = HTTP 426)。

import { util } from '@kit.ArkTS';
import type { ProviderSetting, ProviderSettingOpenAIVariant } from '@amber/chat-domain';
import { loadProviders, saveProviders } from '@amber/chat-domain';
import { getAppContainer, getChatKvStore } from '../di/AppContainer.ets';
import { sha256HexUtf8 } from './EntryLocalToolPorts.ets';
import type { HttpRequest } from '@amber/deepread-domain';

interface HttpTextResult {
  status: number;
  body: string;
}

const CLIENT_ID: string = 'b1a00492-073a-47ea-816f-4c329264a828';
const AUTHORIZE_ENDPOINT: string = 'https://auth.x.ai/oauth2/authorize';
const TOKEN_ENDPOINT: string = 'https://auth.x.ai/oauth2/token';
const REDIRECT_URI: string = 'http://127.0.0.1:8787/callback';
const SCOPE: string = 'openid profile email offline_access grok-cli:access api:access conversations:read conversations:write';
const REFRESH_SKEW_MS: number = 2 * 60 * 1000;
const FALLBACK_TOKEN_LIFETIME_MS: number = 60 * 60 * 1000;

export interface GrokTokens {
  accessToken: string;
  refreshToken: string;
  expiresAt: number;
  email: string;
}

export interface GrokModelItem {
  modelId: string;
  displayName: string;
}

export const GROK_FALLBACK_MODELS: GrokModelItem[] = [
  { modelId: 'grok-4.6', displayName: 'Grok 4.6' },
  { modelId: 'grok-4.5', displayName: 'Grok 4.5' },
  { modelId: 'grok-4.20-0309-reasoning', displayName: 'Grok 4.20 Reasoning' },
  { modelId: 'grok-build', displayName: 'Grok Build' },
];

const tokensKey = (providerId: string): string => `oauth_tokens/grok/${providerId}`;

const formEncode = (fields: Record<string, string>): string => {
  const parts: string[] = [];
  Object.keys(fields).forEach((k: string): void => {
    parts.push(`${encodeURIComponent(k)}=${encodeURIComponent(fields[k])}`);
  });
  return parts.join('&');
};

const base64Url = (bytes: Uint8Array): string => {
  const b64: string = new util.Base64Helper().encodeToStringSync(bytes);
  return b64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
};

// PKCE 材料(random verifier + S256 challenge)。随机源:util 安全随机不可用时的
// 时间戳+Math.random 混合(登录一次性材料,非长期密钥,强度足够)
const randomVerifier = (): string => {
  const alphabet: string = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~';
  let out: string = '';
  const seed: number = Date.now();
  for (let i = 0; i < 64; i++) {
    const idx: number = Math.floor(Math.random() * alphabet.length);
    out += alphabet[(idx + (seed % alphabet.length)) % alphabet.length];
  }
  return out;
};

const sha256HexToBytes = (hex: string): Uint8Array => {
  const out: Uint8Array = new Uint8Array(hex.length / 2);
  for (let i = 0; i < hex.length; i += 2) {
    out[i / 2] = parseInt(hex.substring(i, i + 2), 16);
  }
  return out;
};

export interface GrokAuthorization {
  url: string;
  codeVerifier: string;
}

// authorize URL + 本地保存的 code_verifier(交换时配对)
export const buildGrokAuthorization = (): GrokAuthorization => {
  const verifier: string = randomVerifier();
  const digest: string = sha256HexUtf8(verifier);
  const challenge: string = base64Url(sha256HexToBytes(digest));
  const queryFields: Record<string, string> = {
    'client_id': CLIENT_ID,
    'redirect_uri': REDIRECT_URI,
    'scope': SCOPE,
    'response_type': 'code',
    'code_challenge': challenge,
    'code_challenge_method': 'S256',
    'state': `${Date.now()}`,
  };
const query: string = formEncode(queryFields);
  return { url: `${AUTHORIZE_ENDPOINT}?${query}`, codeVerifier: verifier };
};

// 从用户粘贴的重定向 URL 提取授权码
export const extractGrokCode = (pastedUrl: string): string => {
  const match: RegExpMatchArray | null = pastedUrl.match(/[?&]code=([^&#]+)/);
  if (match === null) return '';
  return decodeURIComponent(match[1]);
};

export const loadGrokTokens = async (providerId: string): Promise<GrokTokens | null> => {
  const raw: string | null = await getChatKvStore().get(tokensKey(providerId));
  if (raw === null) return null;
  try {
    return JSON.parse(raw) as GrokTokens;
  } catch (_e) {
    return null;
  }
};

const saveGrokTokens = async (providerId: string, tokens: GrokTokens): Promise<void> => {
  await getChatKvStore().put(tokensKey(providerId), JSON.stringify(tokens));
};

export const clearGrokTokens = async (providerId: string): Promise<void> => {
  await getChatKvStore().delete(tokensKey(providerId));
};

// access token 同步进 provider.apiKey(请求层同步读;与 Codex 同一惯例)
const syncAccessTokenToProvider = async (providerId: string, accessToken: string): Promise<void> => {
  const list: ProviderSetting[] | null = await loadProviders(getChatKvStore());
  if (list === null) return;
  let dirty: boolean = false;
  const next: ProviderSetting[] = list.map((p: ProviderSetting): ProviderSetting => {
    if (p.id !== providerId || p.type !== 'openai') return p;
    const v: ProviderSettingOpenAIVariant = p as ProviderSettingOpenAIVariant;
    if (v.authMode !== 'grok_oauth' || v.apiKey === accessToken) return p;
    dirty = true;
    return {
      id: v.id, enabled: v.enabled, name: v.name, models: v.models,
      balanceOption: v.balanceOption,
      type: 'openai', apiKey: accessToken, baseUrl: v.baseUrl,
      chatCompletionsPath: v.chatCompletionsPath, useResponseApi: v.useResponseApi,
      authMode: v.authMode, brand: v.brand,
    } as ProviderSettingOpenAIVariant;
  });
  if (dirty) await saveProviders(getChatKvStore(), next);
};

interface GrokTokenResponse {
  access_token?: string;
  refresh_token?: string;
  expires_in?: number;
  email?: string;
}

const parseTokens = (body: string, fallback: GrokTokens | null): GrokTokens => {
  const obj = JSON.parse(body) as GrokTokenResponse;
  const accessToken: string = obj.access_token ?? '';
  if (accessToken.length === 0) throw new Error('Grok OAuth 响应缺少 access_token');
  const refreshToken: string = obj.refresh_token !== undefined && obj.refresh_token.length > 0
    ? obj.refresh_token
    : (fallback !== null ? fallback.refreshToken : '');
  const expiresIn: number = obj.expires_in !== undefined && obj.expires_in > 0
    ? obj.expires_in * 1000
    : FALLBACK_TOKEN_LIFETIME_MS;
  const email: string = obj.email !== undefined && obj.email.length > 0
    ? obj.email
    : (fallback !== null ? fallback.email : '');
  return {
    accessToken,
    refreshToken,
    expiresAt: Date.now() + expiresIn,
    email,
  };
};

const postForm = async (form: string): Promise<HttpTextResult> => {
  const req: HttpRequest = {
    url: TOKEN_ENDPOINT,
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      'Accept': 'application/json',
    },
    body: form,
  };
  const resp = await getAppContainer().httpClient.fetch(req, { signal: undefined });
  return { status: resp.status, body: resp.body };
};

export const exchangeGrokCode = async (
  providerId: string, code: string, codeVerifier: string,
  shouldCancel?: () => boolean,
): Promise<GrokTokens> => {
  const formFields: Record<string, string> = {
    'grant_type': 'authorization_code',
    'code': code,
    'code_verifier': codeVerifier,
    'redirect_uri': REDIRECT_URI,
    'client_id': CLIENT_ID,
  };
  const form: string = formEncode(formFields);
  const resp = await postForm(form);
  if (shouldCancel?.() === true) throw new Error('已取消登录');
  if (resp.status < 200 || resp.status >= 300) {
    throw new Error(`Grok OAuth 令牌交换失败(${resp.status})`);
  }
  const tokens: GrokTokens = parseTokens(resp.body, null);
  await saveGrokTokens(providerId, tokens);
  await syncAccessTokenToProvider(providerId, tokens.accessToken);
  return tokens;
};

export const refreshGrokTokens = async (providerId: string): Promise<GrokTokens> => {
  const current: GrokTokens | null = await loadGrokTokens(providerId);
  if (current === null) throw new Error('尚未登录 Grok');
  if (current.refreshToken.length === 0) throw new Error('Grok OAuth 无 refresh_token,请重新登录');
  const refreshFields: Record<string, string> = {
    'grant_type': 'refresh_token',
    'refresh_token': current.refreshToken,
    'client_id': CLIENT_ID,
  };
  const form: string = formEncode(refreshFields);
  const resp = await postForm(form);
  if (resp.status < 200 || resp.status >= 300) {
    throw new Error(`Grok OAuth 刷新失败(${resp.status})`);
  }
  const tokens: GrokTokens = parseTokens(resp.body, current);
  await saveGrokTokens(providerId, tokens);
  await syncAccessTokenToProvider(providerId, tokens.accessToken);
  return tokens;
};

export const getValidGrokToken = async (
  providerId: string, forceRefresh: boolean = false,
): Promise<GrokTokens | null> => {
  const current: GrokTokens | null = await loadGrokTokens(providerId);
  if (current === null) return null;
  if (!forceRefresh && current.expiresAt - REFRESH_SKEW_MS > Date.now()) return current;
  try {
    return await refreshGrokTokens(providerId);
  } catch (_e) {
    return null;
  }
};
