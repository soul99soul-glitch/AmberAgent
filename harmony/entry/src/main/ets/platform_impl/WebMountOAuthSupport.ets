// WebMountOAuthSupport — PKCE crypto + 飞书 token 交换（entry）
//
// Crypto：CryptoArchitectureKit SHA-256 → base64url。
// 交换：HttpClient Port POST token endpoint。
// 存储：KV 'webmount_feishu_token'。

import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { util } from '@kit.ArkTS';
import { hilog } from '@kit.PerformanceAnalysisKit';
import type { KeyValueStore, JsonObject, JsonValue } from '@amber/chat-domain';
import type { HttpClient } from '@amber/deepread-domain';
import {
  createWebMountPkce, buildFeishuTokenBody, parseFeishuTokenResponse,
  webMountTokenUsable, feishuTokenEndpoint,
  type WebMountCryptoPort, type WebMountPkce, type WebMountOAuthToken,
} from '@amber/chat-domain';

const DOMAIN: number = 0x0001;
const TAG: string = 'WebMountOAuth';
const TOKEN_KEY: string = 'webmount_feishu_token';

const base64Url = (bytes: Uint8Array): string => {
  const helper = new util.Base64Helper();
  return helper.encodeToStringSync(bytes)
    .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
};

const sha256Base64Url = async (input: string): Promise<string> => {
  const md = cryptoFramework.createMd('SHA256');
  const raw: Uint8Array = util.TextEncoder.create('utf-8').encodeInto(input);
  md.updateSync({ data: raw });
  const digest = md.digestSync();
  return base64Url(new Uint8Array(digest.data));
};

const randomBase64Url = async (byteLength: number): Promise<string> => {
  const rnd = cryptoFramework.createRandom();
  const buf: cryptoFramework.DataBlob = rnd.generateRandomSync(byteLength);
  return base64Url(new Uint8Array(buf.data));
};

class WebMountCryptoPortImpl implements WebMountCryptoPort {
  sha256Base64Url(input: string): Promise<string> {
    return sha256Base64Url(input);
  }

  randomBase64Url(byteLength: number): Promise<string> {
    return randomBase64Url(byteLength);
  }
}

export const createWebMountCryptoPort = (): WebMountCryptoPort =>
  new WebMountCryptoPortImpl();

export interface FeishuExchangeInput {
  appId: string;
  appSecret: string;
  code: string;
  codeVerifier: string;
  redirectUri?: string;
}

export interface KvWebMountTokenStore {
  get(): Promise<WebMountOAuthToken | null>;
  set(token: WebMountOAuthToken | null): Promise<void>;
}

const parseTokenJson = (raw: string): WebMountOAuthToken | null => {
  const o: JsonObject = JSON.parse(raw) as JsonObject;
  const access: JsonValue | undefined = o['accessToken'];
  if (typeof access !== 'string') return null;
  const refresh = o['refreshToken'];
  const exp = o['expiresAtMillis'];
  const ttype = o['tokenType'];
  const scope = o['scope'];
  const token: WebMountOAuthToken = {
    accessToken: access,
    refreshToken: typeof refresh === 'string' ? refresh : null,
    expiresAtMillis: typeof exp === 'number' ? exp : 0,
    tokenType: typeof ttype === 'string' ? ttype : 'Bearer',
    scope: typeof scope === 'string' ? scope : null,
  };
  return token;
};

class KvWebMountTokenStoreImpl implements KvWebMountTokenStore {
  private readonly kv: KeyValueStore;

  constructor(kv: KeyValueStore) {
    this.kv = kv;
  }

  get(): Promise<WebMountOAuthToken | null> {
    return this.kv.get(TOKEN_KEY).then((raw: string | null): WebMountOAuthToken | null => {
      if (raw === null || raw.length === 0) return null;
      try {
        return parseTokenJson(raw);
      } catch (_err) {
        return null;
      }
    });
  }

  set(token: WebMountOAuthToken | null): Promise<void> {
    if (token === null) {
      return this.kv.delete(TOKEN_KEY);
    }
    return this.kv.put(TOKEN_KEY, JSON.stringify(token));
  }
}

export const createKvWebMountTokenStore = (kv: KeyValueStore): KvWebMountTokenStore =>
  new KvWebMountTokenStoreImpl(kv);

export const makeFeishuPkce = (): Promise<WebMountPkce> =>
  createWebMountPkce(createWebMountCryptoPort());

export const exchangeFeishuCode = async (
  http: HttpClient,
  input: FeishuExchangeInput,
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
