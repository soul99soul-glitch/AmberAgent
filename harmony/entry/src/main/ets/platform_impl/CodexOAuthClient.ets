// CodexOAuthClient — ChatGPT/Codex 设备码登录(iOS IOSCodexOAuthClient 对齐)
//
// 流程:POST /api/accounts/deviceauth/usercode 拿 user_code → 用户浏览器完成登录
//   → 轮询 /api/accounts/deviceauth/token 拿 authorization_code+code_verifier
//   → POST /oauth/token(form) 换 access/refresh token → 落 KV。
// token 三处消费:① provider.apiKey 字段承载当前 access token(请求层同步读);
//   ② 本 KV 存 refresh/expiry/accountId;③ ChatGPT-Account-Id 头(AppContainer 注入)。
// 刷新点:登录/详情页打开/resolveChatRuntime 临期(2min skew)时;401 自动重试不做(v1 登记)。

import { util } from '@kit.ArkTS';
import type { KeyValueStore, ProviderSetting, ProviderSettingOpenAIVariant } from '@amber/chat-domain';
import { loadProviders, saveProviders } from '@amber/chat-domain';
import { getAppContainer, getChatKvStore } from '../di/AppContainer.ets';
import type { HttpRequest } from '@amber/deepread-domain';

interface HttpTextResult {
  status: number;
  body: string;
}

const CLIENT_ID: string = 'app_EMoamEEZ73f0CkXaXp7hrann';
const ISSUER: string = 'https://auth.openai.com';
const DEVICE_VERIFICATION_URL: string = `${ISSUER}/codex/device`;
const CODEX_BACKEND: string = 'https://chatgpt.com/backend-api/codex';
const CLIENT_VERSION: string = '0.155.0';
const ORIGINATOR: string = 'amberagent_android';
const REFRESH_SKEW_MS: number = 2 * 60 * 1000;
const DEVICE_LOGIN_TIMEOUT_MS: number = 15 * 60 * 1000;
const FALLBACK_TOKEN_LIFETIME_MS: number = 45 * 60 * 1000;

export interface CodexTokens {
  accessToken: string;
  refreshToken: string;
  expiresAt: number;
  accountId: string;
  email: string;
  planType: string;
}

export interface CodexDeviceAuthorization {
  verificationUrl: string;
  userCode: string;
  intervalSeconds: number;
  deviceAuthId: string;
}

export interface CodexModelItem {
  modelId: string;
  displayName: string;
}

const tokensKey = (providerId: string): string => `oauth_tokens/codex/${providerId}`;

const postJson = async (url: string, jsonBody: string, extraHeaders?: Record<string, string>):
  Promise<HttpTextResult> => {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (extraHeaders !== undefined) {
    Object.keys(extraHeaders).forEach((k: string): void => { headers[k] = extraHeaders[k]; });
  }
  const req: HttpRequest = { url, method: 'POST', headers, body: jsonBody };
  const resp = await getAppContainer().httpClient.fetch(req, { signal: undefined });
  return { status: resp.status, body: resp.body };
};

const formEncode = (fields: Record<string, string>): string => {
  const parts: string[] = [];
  Object.keys(fields).forEach((k: string): void => {
    parts.push(`${encodeURIComponent(k)}=${encodeURIComponent(fields[k])}`);
  });
  return parts.join('&');
};

// JWT payload(中段 base64url)→ claims;不合法返回 null
const jwtPayload = (jwt: string): Record<string, Object> | null => {
  const segments: string[] = jwt.split('.');
  if (segments.length < 2) return null;
  let base64: string = segments[1].replace(/-/g, '+').replace(/_/g, '/');
  const padding: number = (4 - base64.length % 4) % 4;
  for (let i = 0; i < padding; i++) base64 += '=';
  try {
    const bytes: Uint8Array = new util.Base64Helper().decodeSync(base64);
    const text: string = new util.TextDecoder('utf-8').decode(bytes);
    return JSON.parse(text) as Record<string, Object>;
  } catch (_e) {
    return null;
  }
};

const claimString = (claims: Record<string, Object> | null, key: string): string => {
  if (claims === null) return '';
  const v = claims[key];
  return typeof v === 'string' ? v as string : '';
};

const buildTokens = (
  accessToken: string, refreshToken: string, idToken: string, fallback: CodexTokens | null,
): CodexTokens => {
  const accessClaims = jwtPayload(accessToken);
  const idClaims: Record<string, Object> | null = idToken.length > 0 ? jwtPayload(idToken) : null;
  const authClaims: Record<string, Object> | null = idClaims !== null
    ? idClaims['https://api.openai.com/auth'] as Record<string, Object> : null;
  const profileClaims: Record<string, Object> | null = idClaims !== null
    ? idClaims['https://api.openai.com/profile'] as Record<string, Object> : null;
  const expRaw = accessClaims !== null ? accessClaims['exp'] : undefined;
  const exp: number = typeof expRaw === 'number' ? expRaw * 1000
    : Date.now() + FALLBACK_TOKEN_LIFETIME_MS;
  const accountId: string = claimString(authClaims, 'chatgpt_account_id').length > 0
    ? claimString(authClaims, 'chatgpt_account_id')
    : (fallback !== null ? fallback.accountId : '');
  let email: string = claimString(idClaims, 'email');
  if (email.length === 0) email = claimString(profileClaims, 'email');
  if (email.length === 0 && fallback !== null) email = fallback.email;
  const planType: string = claimString(authClaims, 'chatgpt_plan_type').length > 0
    ? claimString(authClaims, 'chatgpt_plan_type')
    : (fallback !== null ? fallback.planType : '');
  return {
    accessToken,
    refreshToken,
    expiresAt: Math.max(exp, Date.now() + REFRESH_SKEW_MS),
    accountId,
    email,
    planType,
  };
};

export const loadCodexTokens = async (providerId: string): Promise<CodexTokens | null> => {
  const raw: string | null = await getChatKvStore().get(tokensKey(providerId));
  if (raw === null) return null;
  try {
    return JSON.parse(raw) as CodexTokens;
  } catch (_e) {
    return null;
  }
};

const saveCodexTokens = async (providerId: string, tokens: CodexTokens): Promise<void> => {
  await getChatKvStore().put(tokensKey(providerId), JSON.stringify(tokens));
};

export const clearCodexTokens = async (providerId: string): Promise<void> => {
  await getChatKvStore().delete(tokensKey(providerId));
};

// 登录成功/刷新后:把当前 access token 写回 provider.apiKey(请求层同步读它)
const syncAccessTokenToProvider = async (providerId: string, accessToken: string): Promise<void> => {
  const list: ProviderSetting[] | null = await loadProviders(getChatKvStore());
  if (list === null) return;
  let dirty: boolean = false;
  const next: ProviderSetting[] = list.map((p: ProviderSetting): ProviderSetting => {
    if (p.id !== providerId || p.type !== 'openai') return p;
    const v: ProviderSettingOpenAIVariant = p as ProviderSettingOpenAIVariant;
    if (v.authMode !== 'codex_oauth' || v.apiKey === accessToken) return p;
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

// ===== 设备码流程 =====

export const requestCodexDeviceCode = async (): Promise<CodexDeviceAuthorization> => {
  const deviceReqBody: Record<string, string> = { 'client_id': CLIENT_ID };
  const resp = await postJson(`${ISSUER}/api/accounts/deviceauth/usercode`,
    JSON.stringify(deviceReqBody));
  if (resp.status < 200 || resp.status >= 300) {
    throw new Error(`设备登录请求失败(${resp.status})`);
  }
  const obj = JSON.parse(resp.body) as Record<string, Object>;
  const deviceAuthId: string = claimString(obj, 'device_auth_id');
  if (deviceAuthId.length === 0) throw new Error('设备登录响应缺少 device_auth_id');
  let userCode: string = claimString(obj, 'user_code');
  if (userCode.length === 0) userCode = claimString(obj, 'usercode');
  const intervalRaw = obj['interval'];
  const interval: number = typeof intervalRaw === 'number' && intervalRaw > 0 ? intervalRaw : 5;
  return {
    verificationUrl: DEVICE_VERIFICATION_URL,
    userCode,
    intervalSeconds: interval,
    deviceAuthId,
  };
};

const exchangeAuthorizationCode = async (
  authorizationCode: string, codeVerifier: string,
): Promise<CodexTokens> => {
  const exchangeFields: Record<string, string> = {
    'grant_type': 'authorization_code',
    'code': authorizationCode,
    'redirect_uri': `${ISSUER}/deviceauth/callback`,
    'client_id': CLIENT_ID,
    'code_verifier': codeVerifier,
  };
  const form: string = formEncode(exchangeFields);
  const req: HttpRequest = {
    url: `${ISSUER}/oauth/token`,
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      'Accept': 'application/json',
      'originator': ORIGINATOR,
    },
    body: form,
  };
  const resp = await getAppContainer().httpClient.fetch(req, { signal: undefined });
  if (resp.status < 200 || resp.status >= 300) {
    throw new Error(`Codex 令牌交换失败(${resp.status})`);
  }
  const obj = JSON.parse(resp.body) as Record<string, Object>;
  const accessToken: string = claimString(obj, 'access_token');
  const refreshToken: string = claimString(obj, 'refresh_token');
  if (accessToken.length === 0 || refreshToken.length === 0) {
    throw new Error('Codex 令牌交换响应缺少 access/refresh token');
  }
  const idToken: string = claimString(obj, 'id_token');
  return buildTokens(accessToken, refreshToken, idToken, null);
};

// 轮询直到用户完成登录;shouldCancel 返回 true 时中止(页面关闭/用户取消)
export const pollCodexDeviceCode = async (
  providerId: string,
  authorization: CodexDeviceAuthorization,
  shouldCancel: () => boolean,
): Promise<CodexTokens> => {
  const startedAt: number = Date.now();
  const intervalMs: number = Math.max(authorization.intervalSeconds, 1) * 1000;
  while (Date.now() - startedAt < DEVICE_LOGIN_TIMEOUT_MS) {
    if (shouldCancel()) throw new Error('已取消登录');
    try {
      const pollBody: Record<string, string> = {
        'device_auth_id': authorization.deviceAuthId,
        'user_code': authorization.userCode,
      };
      const resp = await postJson(`${ISSUER}/api/accounts/deviceauth/token`,
        JSON.stringify(pollBody));
      if (shouldCancel()) throw new Error('已取消登录');
      if (resp.status >= 200 && resp.status < 300) {
        const obj = JSON.parse(resp.body) as Record<string, Object>;
        const authorizationCode: string = claimString(obj, 'authorization_code');
        const codeVerifier: string = claimString(obj, 'code_verifier');
        if (authorizationCode.length === 0 || codeVerifier.length === 0) {
          throw new Error('设备登录响应缺少授权码');
        }
        const tokens: CodexTokens = await exchangeAuthorizationCode(authorizationCode, codeVerifier);
        if (shouldCancel()) throw new Error('已取消登录');
        await saveCodexTokens(providerId, tokens);
        await syncAccessTokenToProvider(providerId, tokens.accessToken);
        return tokens;
      }
      if (resp.status === 403 || resp.status === 404 || resp.status >= 500) {
        await new Promise<void>((res): void => { setTimeout(res, intervalMs); });
        continue;
      }
      throw new Error(`设备登录轮询失败(${resp.status})`);
    } catch (e) {
      const msg: string = e instanceof Error ? e.message : String(e);
      if (msg.startsWith('已取消') || msg.indexOf('授权码') >= 0 || msg.indexOf('令牌交换') >= 0) {
        throw e instanceof Error ? e : new Error(String(e));
      }
      // 传输层错误:退避后继续(轮询语义)
      await new Promise<void>((res): void => { setTimeout(res, intervalMs); });
    }
  }
  throw new Error('设备登录超时(15 分钟未完成),请重试');
};

// ===== 刷新 =====

export const refreshCodexTokens = async (providerId: string): Promise<CodexTokens> => {
  const current: CodexTokens | null = await loadCodexTokens(providerId);
  if (current === null) throw new Error('尚未登录 Codex');
  const refreshBody: Record<string, string> = {
    'client_id': CLIENT_ID,
    'grant_type': 'refresh_token',
    'refresh_token': current.refreshToken,
  };
  const resp = await postJson(`${ISSUER}/oauth/token`, JSON.stringify(refreshBody));
  if (resp.status < 200 || resp.status >= 300) {
    throw new Error(`Codex 令牌刷新失败(${resp.status})`);
  }
  const obj = JSON.parse(resp.body) as Record<string, Object>;
  const accessToken: string = claimString(obj, 'access_token').length > 0
    ? claimString(obj, 'access_token') : current.accessToken;
  const refreshToken: string = claimString(obj, 'refresh_token').length > 0
    ? claimString(obj, 'refresh_token') : current.refreshToken;
  const merged: CodexTokens = buildTokens(accessToken, refreshToken,
    claimString(obj, 'id_token'), current);
  await saveCodexTokens(providerId, merged);
  await syncAccessTokenToProvider(providerId, merged.accessToken);
  return merged;
};

// 临期才刷新(2min skew);未登录/刷新失败返回 null(调用方按未配置处理)
export const getValidCodexToken = async (
  providerId: string, forceRefresh: boolean = false,
): Promise<CodexTokens | null> => {
  const current: CodexTokens | null = await loadCodexTokens(providerId);
  if (current === null) return null;
  if (!forceRefresh && current.expiresAt - REFRESH_SKEW_MS > Date.now()) return current;
  try {
    return await refreshCodexTokens(providerId);
  } catch (_e) {
    return null;
  }
};

// ===== 模型目录(失败回落内置清单) =====

const FALLBACK_MODELS: CodexModelItem[] = [
  { modelId: 'gpt-5.1-codex', displayName: 'gpt-5.1-codex' },
  { modelId: 'gpt-5.1-codex-max', displayName: 'gpt-5.1-codex-max' },
  { modelId: 'gpt-5.1', displayName: 'gpt-5.1' },
];

export const fetchCodexModels = async (providerId: string): Promise<CodexModelItem[]> => {
  try {
    const tokens: CodexTokens | null = await getValidCodexToken(providerId);
    if (tokens === null) return FALLBACK_MODELS;
    const headers: Record<string, string> = {
      'Authorization': `Bearer ${tokens.accessToken}`,
      'Accept': 'application/json',
      'originator': ORIGINATOR,
    };
    if (tokens.accountId.length > 0) headers['ChatGPT-Account-Id'] = tokens.accountId;
    const modelsReq: HttpRequest = {
      url: `${CODEX_BACKEND}/models?client_version=${CLIENT_VERSION}`,
      method: 'GET',
      headers,
    };
    const resp = await getAppContainer().httpClient.fetch(modelsReq, { signal: undefined });
    if (resp.status === 401) {
      const retry: CodexTokens | null = await getValidCodexToken(providerId, true);
      if (retry === null) return FALLBACK_MODELS;
      headers['Authorization'] = `Bearer ${retry.accessToken}`;
      const retryReq: HttpRequest = {
        url: `${CODEX_BACKEND}/models?client_version=${CLIENT_VERSION}`,
        method: 'GET',
        headers,
      };
      const resp2 = await getAppContainer().httpClient.fetch(retryReq, { signal: undefined });
      return parseCodexModels(resp2.body);
    }
    return parseCodexModels(resp.body);
  } catch (_e) {
    return FALLBACK_MODELS;
  }
};

const parseCodexModels = (body: string): CodexModelItem[] => {
  try {
    const parsed = JSON.parse(body) as Record<string, Object>;
    const data = parsed['data'] as Object[];
    if (!Array.isArray(data)) return FALLBACK_MODELS;
    const out: CodexModelItem[] = [];
    for (const item of data) {
      const obj = item as Record<string, Object>;
      const id: string = claimString(obj, 'id');
      if (id.length > 0 && id.toLowerCase().indexOf('review') < 0) {
        out.push({ modelId: id, displayName: id });
      }
    }
    return out.length > 0 ? out : FALLBACK_MODELS;
  } catch (_e) {
    return FALLBACK_MODELS;
  }
};
