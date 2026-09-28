import type { AbortSignalLike, HttpClient } from '@amber/deepread-domain';
import type { JsonObject, JsonValue } from './json.ts';
import type { KeyValueStore } from './kv_store.ts';
import type { WebMountOAuthToken } from './webmount_oauth.ts';
import { webMountTokenUsable, feishuTokenEndpoint, parseFeishuTokenResponse } from './webmount_oauth.ts';
import { assertWebMountRequestActive, fetchWebMountRequest } from './webmount_request.ts';
import type { WebMountEnabledCheck } from './webmount_request.ts';

export const FEISHU_APP_ID_KEY: string = 'webmount_feishu_app_id';
export const FEISHU_APP_SECRET_KEY: string = 'webmount_feishu_app_secret';
const DOCX_BASE: string = 'https://open.feishu.cn/open-apis/docx';

export interface FeishuDocsTokenStore {
  get(): Promise<WebMountOAuthToken | null>;
  set(token: WebMountOAuthToken | null): Promise<void>;
}

export interface FeishuDocsClientDependencies {
  http: HttpClient;
  kv: KeyValueStore;
  tokenStore: FeishuDocsTokenStore;
  checkEnabled: WebMountEnabledCheck;
}

export class FeishuDocsClient {
  private readonly deps: FeishuDocsClientDependencies;

  constructor(deps: FeishuDocsClientDependencies) {
    this.deps = deps;
  }

  // 有效 access_token(过期 → refresh;失败 → 诚实报错引导重新登录)
  async resolveAccessToken(signal?: AbortSignalLike): Promise<string> {
    await assertWebMountRequestActive(this.deps.checkEnabled, signal);
    const token = await this.deps.tokenStore.get();
    if (token === null) throw new Error('飞书未登录:请到 设置 → WebMount 完成飞书授权');
    if (webMountTokenUsable(token, Date.now())) return token.accessToken;
    if (token.refreshToken === null || token.refreshToken.length === 0) {
      throw new Error('飞书授权已过期,请重新登录');
    }
    const appId: string | null = await this.deps.kv.get(FEISHU_APP_ID_KEY);
    const appSecret: string | null = await this.deps.kv.get(FEISHU_APP_SECRET_KEY);
    if (appId === null || appId.length === 0) {
      throw new Error('飞书授权已过期且缺少应用凭据,请重新登录');
    }
    const body: JsonObject = {
      grant_type: 'refresh_token', app_id: appId, client_id: appId,
      refresh_token: token.refreshToken,
    };
    if (appSecret !== null && appSecret.length > 0) {
      body['app_secret'] = appSecret;
      body['client_secret'] = appSecret;
    }
    const resp = await fetchWebMountRequest(this.deps.http, {
      url: feishuTokenEndpoint, method: 'POST',
      headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    }, this.deps.checkEnabled, signal);
    const refreshed = parseFeishuTokenResponse(resp.body, Date.now());
    if (refreshed === null) throw new Error('飞书授权刷新失败,请重新登录');
    await this.deps.tokenStore.set(refreshed);
    return refreshed.accessToken;
  }

  async fetch(
    url: string, method: 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH',
    accessToken: string, body?: JsonObject, signal?: AbortSignalLike,
  ): Promise<JsonObject> {
    const resp = await fetchWebMountRequest(this.deps.http, {
      url: url, method: method,
      headers: { 'Authorization': `Bearer ${accessToken}`, 'Content-Type': 'application/json' },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    }, this.deps.checkEnabled, signal);
    const env: JsonObject = JSON.parse(resp.body) as JsonObject;
    const code: JsonValue | undefined = env['code'];
    if (typeof code === 'number' && code !== 0) {
      const msg: JsonValue | undefined = env['msg'];
      throw new Error(`feishu api error ${code}${typeof msg === 'string' ? `: ${msg}` : ''}`);
    }
    return env;
  }

  async append(
    id: string, text: string, parent: string, signal?: AbortSignalLike,
  ): Promise<JsonObject> {
    const token: string = await this.resolveAccessToken(signal);
    if (parent.length === 0) {
      // resolveRootBlockId(Android:312):根 block = blocks[0].block_id
      const env0: JsonObject = await this.fetch(
        `${DOCX_BASE}/v1/documents/${encodeURIComponent(id)}/blocks?page_size=1`,
        'GET', token, undefined, signal);
      const data: JsonValue | undefined = env0['data'];
      const items: JsonValue | undefined = data !== undefined && typeof data === 'object'
        && data !== null && !Array.isArray(data) ? (data as JsonObject)['items'] : undefined;
      if (Array.isArray(items) && items.length > 0) {
        const bid: JsonValue | undefined = (items[0] as JsonObject)['block_id'];
        if (typeof bid === 'string') parent = bid;
      }
      if (parent.length === 0) throw new Error('cannot resolve document root block');
    }
    const block: JsonObject = {
      block_type: 2,
      text: {
        elements: [{ text_run: { content: text } as JsonObject } as JsonObject], style: {},
      } as JsonObject,
    };
    return this.fetch(
      `${DOCX_BASE}/v1/documents/${encodeURIComponent(id)}/blocks/${encodeURIComponent(parent)}/children`,
      'POST', token, { index: -1, children: [block] } as JsonObject, signal);
  }
}
