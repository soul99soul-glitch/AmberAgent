// webmount/oauth — 通用站点 OAuth 协议纯逻辑(E12 冻结合同 A exports)
//
// Authorization Code + PKCE S256(RFC 8252 外部 user-agent 路线)。复用既有
// WebMountCryptoPort/WebMountOAuthToken(webmount_oauth.ts),不克隆。
// 配置校验 fail closed:HTTPS endpoints、固定 127.0.0.1 loopback redirect、
// 显式 API origins(仅 https,例外仅 loopback http)。binding 由宿主导出哈希,
// UI/模型输入不能选择 bindingHash。

import type { WebMountOAuthToken } from '../webmount_oauth.ts';
import type { WebMountOAuthApplication } from './models.ts';
import { isWebMountAbsoluteHttpUrl, webMountOriginOf } from './url.ts';

const SITE_ID_PATTERN: RegExp = /^[a-z0-9][a-z0-9_-]{0,39}$/;
const HTTPS_ENDPOINT_PATTERN: RegExp = /^https:\/\/[a-z0-9.-]+(:\d{1,5})?(\/[^\s#]*)?$/i;
const LOOPBACK_REDIRECT_PATTERN: RegExp = /^http:\/\/127\.0\.0\.1:\d{2,5}\/[a-z0-9\-._~\/]*$/i;
const LOOPBACK_ORIGIN_PATTERN: RegExp = /^http:\/\/(127\.0\.0\.1|localhost)(:\d{1,5})?$/i;

const invalid = (field: string, reason: string): never => {
  throw new Error(`webmount oauth application invalid: ${field} ${reason}`);
};

const hasControlOrSpace = (value: string): boolean => {
  for (let i: number = 0; i < value.length; i++) {
    if (value.charCodeAt(i) <= 0x20) return true;
  }
  return false;
};

const validateEndpoint = (field: string, value: string): string => {
  const trimmed: string = value.trim();
  // host 字符集不含 '@',userinfo 形式天然不命中
  if (!HTTPS_ENDPOINT_PATTERN.test(trimmed)) invalid(field, 'must be an absolute https URL without userinfo or fragment');
  return trimmed;
};

const validateRedirect = (value: string): string => {
  const trimmed: string = value.trim();
  if (!LOOPBACK_REDIRECT_PATTERN.test(trimmed)) invalid('redirectUri', 'must be a fixed http://127.0.0.1:<port>/<path> loopback');
  return trimmed;
};

const validateApiOrigin = (value: string): string => {
  const trimmed: string = value.trim();
  if (webMountOriginOf(trimmed) === null || trimmed !== webMountOriginOf(trimmed)) {
    invalid('apiOrigins', 'must be exact scheme://host[:port] origins');
  }
  if (!trimmed.startsWith('https://') && !LOOPBACK_ORIGIN_PATTERN.test(trimmed)) {
    invalid('apiOrigins', 'must be https (loopback http only)');
  }
  return trimmed;
};

// 校验并规范化(各字段 trim、apiOrigins 去重);不通过的抛错,绝不部分接受
export const validateWebMountOAuthApplication = (
  application: WebMountOAuthApplication,
): WebMountOAuthApplication => {
  const siteId: string = application.siteId.trim();
  if (!SITE_ID_PATTERN.test(siteId)) invalid('siteId', 'must be [a-z0-9_-], 1-40 chars');
  const clientId: string = application.clientId.trim();
  if (clientId.length === 0 || clientId.length > 200 || hasControlOrSpace(clientId)) {
    invalid('clientId', 'must be non-empty printable, <= 200 chars');
  }
  if (application.scope.length > 500) invalid('scope', 'must be <= 500 chars');
  if (application.tokenEncoding !== 'form' && application.tokenEncoding !== 'json') {
    invalid('tokenEncoding', 'must be form or json');
  }
  if (!['none', 'body', 'basic'].includes(application.clientAuthentication)) {
    invalid('clientAuthentication', 'must be none, body or basic');
  }
  if (!Array.isArray(application.apiOrigins) || application.apiOrigins.length === 0) {
    invalid('apiOrigins', 'must list at least one exact API origin');
  }
  const apiOrigins: string[] = [];
  for (const origin of application.apiOrigins) {
    const validated: string = validateApiOrigin(origin);
    if (!apiOrigins.includes(validated)) apiOrigins.push(validated);
  }
  return {
    siteId: siteId,
    bindingHash: application.bindingHash,
    authorizationEndpoint: validateEndpoint('authorizationEndpoint', application.authorizationEndpoint),
    tokenEndpoint: validateEndpoint('tokenEndpoint', application.tokenEndpoint),
    clientId: clientId,
    redirectUri: validateRedirect(application.redirectUri),
    scope: application.scope.trim(),
    tokenEncoding: application.tokenEncoding,
    clientAuthentication: application.clientAuthentication,
    apiOrigins: apiOrigins,
    clientSecretRef: application.clientSecretRef,
  };
};

// 固定键序的绑定串:宿主哈希(sha256)后得到 bindingHash。配置或 secret 引用
// 任一变化都会换绑定,旧 pending/token 随之失效
export const canonicalWebMountOAuthBinding = (
  application: WebMountOAuthApplication, clientSecretRef: string | null,
): string => JSON.stringify({
  siteId: application.siteId,
  authorizationEndpoint: application.authorizationEndpoint,
  tokenEndpoint: application.tokenEndpoint,
  clientId: application.clientId,
  redirectUri: application.redirectUri,
  scope: application.scope,
  tokenEncoding: application.tokenEncoding,
  clientAuthentication: application.clientAuthentication,
  apiOrigins: application.apiOrigins,
  clientSecretRef: clientSecretRef,
});

export interface WebMountPkceChallenge { state: string; codeChallenge: string; }

export const buildWebMountAuthorizationUrl = (
  application: WebMountOAuthApplication, pkce: WebMountPkceChallenge,
): string => {
  const pairs: Array<[string, string]> = [
    ['response_type', 'code'],
    ['client_id', application.clientId],
    ['redirect_uri', application.redirectUri],
    ['state', pkce.state],
    ['code_challenge', pkce.codeChallenge],
    ['code_challenge_method', 'S256'],
  ];
  if (application.scope.length > 0) pairs.push(['scope', application.scope]);
  const query: string = pairs
    .map((pair: [string, string]): string => `${encodeURIComponent(pair[0])}=${encodeURIComponent(pair[1])}`)
    .join('&');
  const separator: string = application.authorizationEndpoint.includes('?') ? '&' : '?';
  return `${application.authorizationEndpoint}${separator}${query}`;
};

export interface WebMountTokenGrant {
  grantType: 'authorization_code' | 'refresh_token';
  code: string | null;
  codeVerifier: string | null;
  refreshToken: string | null;
}

export interface WebMountTokenRequestSpec {
  url: string;
  method: string;
  contentType: string;
  authorizationHeader: string | null;
  body: string;
}

// RFC 6749 §2.3.1:basic 的 client_id/secret 先 form-encode 再 ':' 连接后 base64
const BASE64_ALPHABET: string = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
const base64Ascii = (text: string): string => {
  const bytes: number[] = [];
  for (let i: number = 0; i < text.length; i++) {
    const code: number = text.charCodeAt(i);
    if (code > 0x7f) throw new Error('basic credentials must be ASCII after form encoding');
    bytes.push(code);
  }
  let out: string = '';
  for (let i: number = 0; i < bytes.length; i += 3) {
    const a: number = bytes[i];
    const b: number | undefined = i + 1 < bytes.length ? bytes[i + 1] : undefined;
    const c: number | undefined = i + 2 < bytes.length ? bytes[i + 2] : undefined;
    out += BASE64_ALPHABET[a >> 2];
    out += BASE64_ALPHABET[((a & 3) << 4) | ((b ?? 0) >> 4)];
    out += b === undefined ? '=' : BASE64_ALPHABET[((b & 15) << 2) | ((c ?? 0) >> 6)];
    out += c === undefined ? '=' : BASE64_ALPHABET[c & 63];
  }
  return out;
};

export const buildWebMountTokenRequest = (
  application: WebMountOAuthApplication, clientSecret: string | null, grant: WebMountTokenGrant,
): WebMountTokenRequestSpec => {
  const fields: Array<[string, string]> = [['grant_type', grant.grantType]];
  if (grant.grantType === 'authorization_code') {
    if (grant.code === null || grant.codeVerifier === null) throw new Error('authorization_code grant requires code and verifier');
    fields.push(['code', grant.code], ['redirect_uri', application.redirectUri], ['code_verifier', grant.codeVerifier]);
  } else {
    if (grant.refreshToken === null || grant.refreshToken.length === 0) throw new Error('refresh_token grant requires a refresh token');
    fields.push(['refresh_token', grant.refreshToken]);
  }
  let authorizationHeader: string | null = null;
  if (application.clientAuthentication === 'basic') {
    if (clientSecret === null) throw new Error('basic client authentication requires the resolved client secret');
    const credentials: string = `${encodeURIComponent(application.clientId)}:${encodeURIComponent(clientSecret)}`;
    authorizationHeader = `Basic ${base64Ascii(credentials)}`;
  } else {
    fields.push(['client_id', application.clientId]);
    if (application.clientAuthentication === 'body') {
      if (clientSecret === null) throw new Error('body client authentication requires the resolved client secret');
      fields.push(['client_secret', clientSecret]);
    }
  }
  let body: string;
  if (application.tokenEncoding === 'json') {
    const payload: Record<string, string> = {};
    for (const pair of fields) payload[pair[0]] = pair[1];
    body = JSON.stringify(payload);
  } else {
    body = fields.map((pair: [string, string]): string => `${encodeURIComponent(pair[0])}=${encodeURIComponent(pair[1])}`).join('&');
  }
  return {
    url: application.tokenEndpoint,
    method: 'POST',
    contentType: application.tokenEncoding === 'json' ? 'application/json' : 'application/x-www-form-urlencoded',
    authorizationHeader: authorizationHeader,
    body: body,
  };
};

// 通用 token 响应解析:access_token 必须非空串;错误载荷浮出真实 message
export const parseWebMountTokenResponse = (body: string, nowMs: number): WebMountOAuthToken => {
  const parsed: unknown = JSON.parse(body);
  if (typeof parsed !== 'object' || parsed === null) throw new Error('oauth token: invalid JSON');
  const obj: Record<string, unknown> = parsed as Record<string, unknown>;
  const access: unknown = obj['access_token'];
  if (typeof access !== 'string' || access.length === 0) {
    const err: unknown = obj['error_description'] ?? obj['error'] ?? obj['message'];
    throw new Error(`oauth token failed: ${typeof err === 'string' ? err : 'no access_token'}`);
  }
  const expiresIn: number = typeof obj['expires_in'] === 'number' ? obj['expires_in'] as number : 0;
  return {
    accessToken: access,
    refreshToken: typeof obj['refresh_token'] === 'string' ? obj['refresh_token'] as string : null,
    expiresAtMillis: nowMs + Math.max(0, expiresIn) * 1000,
    tokenType: typeof obj['token_type'] === 'string' ? obj['token_type'] as string : 'Bearer',
    scope: typeof obj['scope'] === 'string' ? obj['scope'] as string : null,
  };
};

// Bearer 只能注入与当前绑定完全一致的已配置 API origin
export const webMountOAuthAllowsApiOrigin = (
  application: WebMountOAuthApplication, requestUrl: string,
): boolean => {
  const origin: string | null = webMountOriginOf(requestUrl);
  return origin !== null && isWebMountAbsoluteHttpUrl(requestUrl) && application.apiOrigins.includes(origin);
};
