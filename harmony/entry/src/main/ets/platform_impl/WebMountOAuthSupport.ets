// WebMountOAuthSupport — PKCE crypto + 飞书 token 交换（entry）
//
// Crypto：CryptoArchitectureKit SHA-256 → base64url。
// 交换：HttpClient Port POST token endpoint。
// 存储：KV 'webmount_feishu_token'；状态 UI 读写。

import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { util } from '@kit.ArkTS';
import { hilog } from '@kit.PerformanceAnalysisKit';
import type { HttpClient, KeyValueStore } from '@amber/chat-domain';
import {
  createWebMountPkce, buildFeishuTokenBody, parseFeishuTokenResponse,
  webMountTokenUsable, feishuTokenEndpoint,
  type WebMountCryptoPort, type WebMountPkce, type WebMountOAuthToken,
} from '@amber/chat-domain';

const DOMAIN: number = 0x0001;
const TAG: string = 'WebMountOAuth';
const TOKEN_KEY: string = 'webmount_feishu_token';

const toBlob = (bytes: Uint8Array): cryptoFramework.DataBlob => {
  const buf = new ArrayBuffer(bytes.length);
  new Uint8Array(buf).set(bytes);
  return { data: buf };
};

const base64Url = (bytes: Uint8Array): string => {
  const helper = new util.Base64Helper();
  return helper.encodeToStringSync(bytes)
    .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
};

export const createWebMountCryptoPort = (): WebMountCryptoPort => ({
  sha256Base64Url: async (input: string): Promise<string> => {
    const md = cryptoFramework.createMd('SHA256');
    const raw: Uint8Array = util.TextEncoder.create('utf-8').encodeInto(input);
    md.updateSync(toBlob(raw));
    const digest = md.digestSync();
    return base64Url(new Uint8Array(digest.data));
  },
  randomBase64Url: async (byteLength: number): Promise<string> => {
    const out = new Uint8Array(byteLength);
    let seed = (Date.now() ^ Math.imul(byteLength, 0x9e3779b9)) >>> 0;
    for (let i = 0; i < byteLength; i++) {
      seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
      out[i] = (seed >>> 16) & 0xff;
    }
    return base64Url(out);
  },
});

export const createKvWebMountTokenStore = (kv: KeyValueStore) => ({
  get: async (): Promise<WebMountOAuthToken | null> => {
    const raw = await kv.get(TOKEN_KEY);
    if (raw === null || raw.length === 0) return null;
    try {
      const parsed: unknown = JSON.parse(raw);
      if (typeof parsed !== 'object' || parsed === null) return null;
      const o = parsed as Record<string, unknown>;
      if (typeof o['accessToken'] !== 'string') return null;
      return {
        accessToken: o['accessToken'],
        refreshToken: typeof o['refreshToken'] === 'string' ? o['refreshToken'] : null,
        expiresAtMillis: typeof o['expiresAtMillis'] === 'number' ? o['expiresAtMillis'] : 0,
        tokenType: typeof o['tokenType'] === 'string' ? o['tokenType'] : 'Bearer',
        scope: typeof o['scope'] === 'string' ? o['scope'] : null,
      };
    } catch {
      return null;
    }
  },
  set: async (token: WebMountOAuthToken | null): Promise<void> => {
    if (token === null) {
      await kv.delete(TOKEN_KEY);
      return;
    }
    await kv.put(TOKEN_KEY, JSON.stringify(token));
  },
});

export const makeFeishuPkce = (): Promise<WebMountPkce> =>
  createWebMountPkce(createWebMountCryptoPort());

export const exchangeFeishuCode = async (
  http: HttpClient,
  input: { appId: string; appSecret: string; code: string; codeVerifier: string; redirectUri?: string },
): Promise<WebMountOAuthToken> => {
  const body = buildFeishuTokenBody({
    appId: input.appId,
    appSecret: input.appSecret,
    code: input.code,
    codeVerifier: input.codeVerifier,
    redirectUri: input.redirectUri,
  });
  const resp = await http.fetch({
    url: feishuTokenEndpoint,
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body,
  });
  if (resp.status < 200 || resp.status >= 300) {
    hilog.warn(DOMAIN, TAG, 'token exchange HTTP %{public}d', resp.status);
    throw new Error(`Feishu token HTTP ${resp.status}`);
  }
  return parseFeishuTokenResponse(resp.body, Date.now());
};

export const feishuTokenStatusText = (
  token: WebMountOAuthToken | null, nowMs: number = Date.now(),
): string => {
  if (token === null) return '未登录';
  if (!webMountTokenUsable(token, nowMs)) return '已过期，请重新授权';
  return `已登录 · 剩余 ${Math.max(0, Math.round((token.expiresAtMillis - nowMs) / 60000))} 分钟`;
};
