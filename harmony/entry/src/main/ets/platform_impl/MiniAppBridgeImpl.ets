// MiniAppBridgeImpl — MiniAppBridge.kt 的 entry 实现
//
// postMessage 分发:校验 sessionToken → 逐方法 sandbox.require → 敏感方法确认
//   回调 → 执行 → runJavaScript 回传 window.AmberBridge._handleNativeResponse(json)。
//
// 方法实现策略:
//   真实现  storage.* / toast / host.getTheme / fetch / search / clipboard.copy /
//            host.updateBoardSummary / host.getConversationContext / sharedStore.* /
//            eventBus.* / launch
//   诚实错误 sensor.* / location.* / clipboard.read / ai.generate /
//            host.sendToConversation / host.createArtifact
//           → throw not_available_on_harmony → envelope ok:false(JS reject,
//             与 Android handler 抛错同信号;逐个评估见偏差报告;均先过
//             sandbox.require 保持权限语义)
//
// 偏差登记:
//   - 事件回调/subscription 状态为实例级(Android 全局 object → HAR 实例工厂)
//   - 重定向:RCP autoRedirect=false + 手动 MINI_APP_MAX_REDIRECTS(3) 跳逐跳
//     checkUrl(Android executeChecked 等价,重定向转 GET);响应体仍整体缓冲后
//     校验大小上限(RCP 无流式读,内存峰值为平台偏差;上限拒绝语义同 readCapped)
//   - host.getTheme 颜色由页面注入 tokens(回调接口,platform_impl 不碰 UI)

import type {
  MiniAppBridgeRequest, MiniAppBridgeResponse, JsonValue, JsonObject,
  MiniAppPermission, MiniAppRecord, MiniAppRequestPlan, MiniAppHostResolver,
  MiniAppUrlRecord,
} from '@amber/chat-domain';
import {
  MiniAppValidationException, MiniAppSecurityException, MiniAppUrlGuard,
  resolveRedirectUrl, isIpLiteralHost,
  buildMiniAppRequestPlan, resolveMiniAppResponseType,
  MINI_APP_MAX_RESPONSE_BYTES, MINI_APP_MAX_TEXT_CHARS, MINI_APP_MAX_IMAGE_BYTES,
  MINI_APP_MAX_REDIRECTS,
  MINI_APP_IMAGE_ACCEPT_HEADER, MINI_APP_USER_AGENT,
  utf8ByteLength,
} from '@amber/chat-domain';
import { rcp } from '@kit.RemoteCommunicationKit';
import { connection } from '@kit.NetworkKit';
import { util } from '@kit.ArkTS';
import { pasteboard } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import type { HttpClient } from '@amber/deepread-domain';
import type { SearchHit, SearchProvider, SearchProviderRegistry } from '@amber/deepread-domain';
import type { MiniAppPlatform } from './MiniAppPlatform.ets';
import { getAppContainer } from '../di/AppContainer.ets';

const HILOG_DOMAIN: number = 0x0001;
const HILOG_TAG: string = 'MiniApp.Bridge';

export interface MiniAppThemeJson {
  dark: boolean;
  background: string;
  foreground: string;
  primary: string;
}

// 宿主能力注入(页面侧提供;platform_impl 不直接碰 UI)
export interface MiniAppBridgeHost {
  toast: (message: string) => void;
  confirm: (title: string, message: string) => Promise<boolean>;
  theme: () => MiniAppThemeJson;
  launch: (appId: string) => void;
}

export interface MiniAppBridgeOpts {
  appId: string;
  sessionToken: string;
  host: MiniAppBridgeHost;
  // 当前小应用记录快照(Runner 加载后注入;标题/描述用于确认文案)
  app: () => MiniAppRecord | null;
  runJs: (script: string) => void;
}

// ===== 参数读取(JsonObject 最小子集) =====

const stringParam = (params: JsonObject, key: string): string => {
  const v: JsonValue | undefined = params[key];
  if (typeof v !== 'string') throw new MiniAppValidationException(`Missing parameter: ${key}`);
  return v;
};

const optionalStringParam = (params: JsonObject, key: string): string | null => {
  const v: JsonValue | undefined = params[key];
  if (v === undefined || v === null) return null;
  if (typeof v !== 'string') throw new MiniAppValidationException(`Invalid parameter: ${key}`);
  return v;
};

const optionalNumberParam = (params: JsonObject, key: string): number | null => {
  const v: JsonValue | undefined = params[key];
  if (v === undefined || v === null) return null;
  if (typeof v !== 'number') throw new MiniAppValidationException(`Invalid parameter: ${key}`);
  return v;
};

const clamp = (value: number, min: number, max: number): number =>
  value < min ? min : (value > max ? max : value);

// ===== URL guard(DNS 逐地址校验;fail-closed) =====
// Android 语义:guard 即最终裁决(getAllByName 失败 = 请求失败)。
//   公网字面 IP 直接放行(等价 InetAddress 字面短路);域名必须 resolveHost
//   成功且全部地址为公网;DNS 失败即拒绝,不回退放行 — http 层无法钉住实际
//   解析结果,回退放行即留下 DNS 层绕过口。
const dnsResolveHost: MiniAppHostResolver = async (host: string): Promise<string[]> => {
  try {
    const addresses = await connection.getAddressesByName(host);
    const out: string[] = [];
    for (const a of addresses) {
      if (a.address !== undefined && a.address !== null && a.address.trim().length > 0) {
        out.push(a.address.trim());
      }
    }
    if (out.length === 0) throw new Error('empty dns result');
    return out;
  } catch {
    throw new Error('dns failed');
  }
};

// ===== HTTP 执行(字节通道) =====

interface MiniAppFetchBytesResult {
  status: number;
  ok: boolean;
  contentType: string;
  bytes: Uint8Array;
  // 重定向后的最终请求 URL(fetch 契约对齐 Android:返回最终地址)
  finalUrl: string;
}

interface MiniAppImageResult {
  bytes: Uint8Array;
  contentType: string;
}

const headerText = (v: string | string[] | undefined): string => {
  if (v === undefined || v === null) return '';
  if (Array.isArray(v)) return v.length > 0 ? v[0] : '';
  return v;
};

class MiniAppHttpExecutor {
  // plan 构建期 guard:字面 IP 校验(私网字面 IP 即刻拒绝;域名放行交给 checkUrl)
  readonly planGuard: MiniAppUrlGuard;
  private dnsGuard: MiniAppUrlGuard;
  private literalGuard: MiniAppUrlGuard;
  private session: rcp.Session | null = null;

  constructor() {
    this.planGuard = new MiniAppUrlGuard(null);
    this.dnsGuard = new MiniAppUrlGuard(dnsResolveHost);
    this.literalGuard = new MiniAppUrlGuard(null);
  }

  // RCP 会话懒创建;bridge.close() → dispose() 释放
  private ensureSession(): rcp.Session {
    if (this.session === null) {
      this.session = rcp.createSession();
    }
    return this.session;
  }

  dispose(): void {
    if (this.session !== null) {
      this.session.close();
      this.session = null;
    }
  }

  // 完整 URL 校验(Android MiniAppUrlGuard.check 等价):
  //   非 https / 私网字面 IP 即刻拒绝;公网字面 IP 放行;域名 DNS 逐地址校验
  async checkUrl(raw: string): Promise<void> {
    const record: MiniAppUrlRecord = await this.literalGuard.check(raw);
    if (isIpLiteralHost(record.host)) return;
    await this.dnsGuard.resolveAllowed(record.host);
  }

  // Android MiniAppNetwork.executeChecked 等价:RCP autoRedirect 关闭,手动最多
  //   MINI_APP_MAX_REDIRECTS(3) 跳,逐跳 checkUrl,重定向一律转 GET 并丢弃 body
  async fetchBytes(plan: MiniAppRequestPlan): Promise<MiniAppFetchBytesResult> {
    let url: string = plan.url;
    let method: rcp.HttpMethod = plan.method === 'POST' ? 'POST' : 'GET';
    let content: rcp.RawDataContent | undefined =
      method === 'POST' && plan.body !== null ? plan.body : undefined;
    for (let hop: number = 0; ; hop++) {
      await this.checkUrl(url);
      const headers: rcp.RequestHeaders = {};
      for (const key of Object.keys(plan.headers)) {
        headers[key] = plan.headers[key];
      }
      const request: rcp.Request = new rcp.Request(
        url, method, headers, content, undefined, undefined,
        {
          transfer: {
            autoRedirect: false,
            timeout: { connectMs: 10000, transferMs: 15000 },
          },
        });
      const response: rcp.Response = await this.ensureSession().fetch(request);
      const status: number = response.statusCode;
      // 每跳统一预检(重定向响应体也应受限;3xx 一般无 body,此为防御)
      const hopLength: string | string[] | undefined = response.headers['content-length'];
      const hopLengthText: string | null = typeof hopLength === 'string'
        ? hopLength : (Array.isArray(hopLength) && hopLength.length > 0
          ? String(hopLength[0]) : null);
      if (hopLengthText !== null) {
        const hopLen: number = Number.parseInt(hopLengthText, 10);
        if (Number.isFinite(hopLen) && hopLen > MINI_APP_MAX_RESPONSE_BYTES) {
          throw new MiniAppValidationException('Response is too large');
        }
      }
      if (status >= 300 && status < 400) {
        const location: string | undefined = response.headers['location'];
        if (hop >= MINI_APP_MAX_REDIRECTS || location === undefined || location.trim().length === 0) {
          throw new MiniAppValidationException('Too many redirects');
        }
        const next: string | null = resolveRedirectUrl(url, location);
        if (next === null) throw new MiniAppValidationException('Invalid redirect');
        url = next;
        method = 'GET'; // Android newBuilder().get():重定向转 GET
        content = undefined;
        continue;
      }
      const contentType: string = headerText(response.headers['content-type'])
        .split(';')[0].toLowerCase();
      const body: ArrayBuffer | undefined = response.body;
      const bytes: Uint8Array = body === undefined ? new Uint8Array(0) : new Uint8Array(body);
      if (bytes.byteLength > MINI_APP_MAX_RESPONSE_BYTES) {
        throw new MiniAppValidationException('Response is too large');
      }
      return { status, ok: status >= 200 && status < 300, contentType, bytes, finalUrl: url };
    }
  }

  async fetchImageBytes(url: string): Promise<MiniAppImageResult> {
    const plan: MiniAppRequestPlan = {
      url,
      method: 'GET',
      headers: { accept: MINI_APP_IMAGE_ACCEPT_HEADER, 'user-agent': MINI_APP_USER_AGENT },
      body: null,
    };
    const result: MiniAppFetchBytesResult = await this.fetchBytes(plan);
    if (!result.ok) {
      throw new MiniAppValidationException(`Image fetch failed: ${result.status}`);
    }
    if (!result.contentType.startsWith('image/')) {
      throw new MiniAppValidationException('URL did not return an image');
    }
    if (result.bytes.byteLength > MINI_APP_MAX_IMAGE_BYTES) {
      throw new MiniAppValidationException('Image is too large');
    }
    const image: MiniAppImageResult = { bytes: result.bytes, contentType: result.contentType };
    return image;
  }
}

// ===== 桥接 =====

export class MiniAppBridgeImpl {
  private platform: MiniAppPlatform;
  private appId: string;
  private sessionToken: string;
  private host: MiniAppBridgeHost;
  private app: () => MiniAppRecord | null;
  private runJs: (script: string) => void;
  private executor: MiniAppHttpExecutor;
  private closed: boolean = false;
  // Android bridgeScope.limitedParallelism(1) 等价:分发串行
  private queue: Promise<void> = Promise.resolve();
  private eventPublishTimes: number[] = [];
  private subscriptionIds: string[] = [];

  constructor(platform: MiniAppPlatform, opts: MiniAppBridgeOpts) {
    this.platform = platform;
    this.appId = opts.appId;
    this.sessionToken = opts.sessionToken;
    this.host = opts.host;
    this.app = opts.app;
    this.runJs = opts.runJs;
    this.executor = new MiniAppHttpExecutor();
  }

  postMessage(raw: string): void {
    if (this.closed) return;
    this.queue = this.queue.then((): Promise<void> => this.dispatch(raw)).catch((): void => {});
  }

  close(): void {
    if (this.closed) return;
    this.closed = true;
    for (const id of this.subscriptionIds) {
      this.platform.eventBus.unsubscribe(id);
    }
    this.subscriptionIds = [];
    this.executor.dispose();
  }

  private async dispatch(raw: string): Promise<void> {
    if (this.closed) return;
    let request: MiniAppBridgeRequest;
    try {
      request = JSON.parse(raw) as MiniAppBridgeRequest;
    } catch {
      this.sendResponse(-1, false, null, 'Invalid bridge request');
      return;
    }
    if (typeof request.id !== 'number' || typeof request.token !== 'string' ||
      typeof request.method !== 'string' || request.params === null ||
      typeof request.params !== 'object') {
      this.sendResponse(-1, false, null, 'Invalid bridge request');
      return;
    }
    if (request.token !== this.sessionToken) {
      this.sendResponse(request.id, false, null, 'Invalid MiniApp session token');
      return;
    }
    try {
      const data: JsonValue = await this.handle(request.method, request.params as JsonObject);
      this.sendResponse(request.id, true, data, null);
    } catch (e) {
      const message: string = e instanceof Error ? e.message : String(e);
      this.sendResponse(request.id, false, null, message);
    }
  }

  private sendResponse(id: number, ok: boolean, data: JsonValue | null, error: string | null): void {
    if (this.closed) return;
    const response: MiniAppBridgeResponse = { id, ok, data, error };
    this.runJs(`window.AmberBridge && window.AmberBridge._handleNativeResponse(${JSON.stringify(response)})`);
  }

  private emitEvent(type: string, subscriptionId: string | null, payload: JsonValue): void {
    if (this.closed) return;
    const event: JsonObject = { type: type as JsonValue };
    if (subscriptionId !== null) {
      event['subscriptionId'] = subscriptionId as JsonValue;
    }
    event['payload'] = payload;
    this.runJs(`window.AmberBridge && window.AmberBridge._emitNativeEvent(${JSON.stringify(event)})`);
  }

  // ===== 方法分发 =====

  private async handle(method: string, params: JsonObject): Promise<JsonValue> {
    const repository = this.platform.repository;
    const storage = this.platform.storage;
    const appRecord: MiniAppRecord | null = this.app();
    const declared: string[] = appRecord !== null ? appRecord.permissions : [];
    const appTitle: string = appRecord !== null ? appRecord.title : this.appId;
    const sandbox = this.platform.sandboxFor(this.appId, declared);

    switch (method) {
      case 'storage.get': {
        sandbox.require('storage');
        const key: string = stringParam(params, 'key');
        const stored: string | null = await storage.get(this.appId, key);
        if (stored === null) return null;
        try {
          return JSON.parse(stored) as JsonValue;
        } catch {
          return stored;
        }
      }
      case 'storage.set': {
        sandbox.require('storage');
        const key: string = stringParam(params, 'key');
        const value: JsonValue = params['value'] === undefined ? null : params['value'];
        await storage.set(this.appId, key, JSON.stringify(value));
        return true;
      }
      case 'storage.remove': {
        sandbox.require('storage');
        const key: string = stringParam(params, 'key');
        await storage.remove(this.appId, key);
        return true;
      }
      case 'toast': {
        sandbox.require('toast');
        const message: string = stringParam(params, 'message').slice(0, 120);
        this.host.toast(message);
        return true;
      }
      case 'host.getTheme': {
        sandbox.require('theme');
        const theme: MiniAppThemeJson = this.host.theme();
        const themeJson: JsonObject = {};
        themeJson['dark'] = theme.dark as JsonValue;
        themeJson['background'] = theme.background as JsonValue;
        themeJson['foreground'] = theme.foreground as JsonValue;
        themeJson['primary'] = theme.primary as JsonValue;
        return themeJson;
      }
      case 'fetch': {
        sandbox.require('network');
        await this.audit(method, 'network', 'MiniApp network request', params);
        const plan: MiniAppRequestPlan =
          await buildMiniAppRequestPlan(this.executor.planGuard, params);
        const responseType: string = resolveMiniAppResponseType(params);
        const result: MiniAppFetchBytesResult = await this.executor.fetchBytes(plan);
        const body: JsonValue = this.decodeBody(result, responseType);
        const payload: JsonObject = {};
        payload['status'] = result.status as JsonValue;
        payload['ok'] = result.ok as JsonValue;
        payload['url'] = result.finalUrl as JsonValue;
        payload['contentType'] = result.contentType as JsonValue;
        payload['body'] = body;
        return payload;
      }
      case 'search': {
        sandbox.require('search');
        await this.audit(method, 'search', 'MiniApp search request', params);
        const query: string = stringParam(params, 'query').slice(0, 160);
        const limitRaw: number = optionalNumberParam(params, 'limit') ?? 6;
        const limit: number = clamp(limitRaw, 1, 8);
        return await this.searchItems(query, limit);
      }
      case 'clipboard.copy': {
        sandbox.require('clipboard.copy');
        const text: string = stringParam(params, 'text').slice(0, 20000);
        const board = pasteboard.getSystemPasteboard();
        board.setDataSync(pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, text));
        return true;
      }
      case 'host.updateBoardSummary': {
        sandbox.require('host.updateBoardSummary');
        const summary: string = stringParam(params, 'summary').slice(0, 500);
        await this.audit(method, 'host.updateBoardSummary', 'Update board summary', summary as JsonValue);
        await repository.updateBoardSummary(this.appId, summary);
        return true;
      }
      case 'host.getConversationContext': {
        sandbox.require('host.context');
        const maxCharsRaw: number = optionalNumberParam(params, 'maxChars') ?? 6000;
        const maxChars: number = clamp(maxCharsRaw, 200, 8000);
        return await this.confirm('允许读取上下文？', `「${appTitle}」想读取最小化会话上下文。`, async (): Promise<JsonValue> => {
          await this.audit(method, 'host.context', 'host.context', params);
          const record: MiniAppRecord | null = await repository.getById(this.appId);
          if (record === null) throw new MiniAppValidationException('MiniApp does not exist');
          return this.hostContextJson(record, maxChars);
        });
      }
      case 'sharedStore.get': {
        sandbox.require('sharedStore');
        const namespace: string = optionalStringParam(params, 'namespace') ?? this.appId;
        const key: string = stringParam(params, 'key');
        await this.audit(method, 'sharedStore', 'sharedStore.get', params);
        return await repository.sharedGet(this.appId, namespace, key);
      }
      case 'sharedStore.set': {
        sandbox.require('sharedStore');
        const namespace: string = optionalStringParam(params, 'namespace') ?? this.appId;
        const key: string = stringParam(params, 'key');
        const value: JsonValue = params['value'] === undefined ? null : params['value'];
        await this.audit(method, 'sharedStore', 'sharedStore.set', 'ok' as JsonValue);
        await repository.sharedSet(this.appId, namespace, key, value);
        return true;
      }
      case 'sharedStore.remove': {
        sandbox.require('sharedStore');
        const namespace: string = optionalStringParam(params, 'namespace') ?? this.appId;
        const key: string = stringParam(params, 'key');
        await this.audit(method, 'sharedStore', 'sharedStore.remove', params);
        await repository.sharedRemove(this.appId, namespace, key);
        return true;
      }
      case 'eventBus.subscribe': {
        sandbox.require('eventBus');
        const namespace: string = this.ownNamespace(optionalStringParam(params, 'namespace') ?? this.appId);
        const topic: string = this.safeTopic(stringParam(params, 'topic'));
        await this.audit(method, 'eventBus', 'eventBus.subscribe', params);
        const subscriptionId: string = this.platform.eventBus.subscribe(
          this.appId, namespace, topic, (payload: JsonValue): void => {
            this.emitEvent('eventBus', subscriptionId, payload);
          });
        this.subscriptionIds.push(subscriptionId);
        const result: JsonObject = {};
        result['subscriptionId'] = subscriptionId as JsonValue;
        return result;
      }
      case 'eventBus.unsubscribe': {
        sandbox.require('eventBus');
        const subscriptionId: string = stringParam(params, 'subscriptionId');
        const idx: number = this.subscriptionIds.indexOf(subscriptionId);
        if (idx >= 0) this.subscriptionIds.splice(idx, 1);
        this.platform.eventBus.unsubscribe(subscriptionId);
        return true;
      }
      case 'eventBus.publish': {
        sandbox.require('eventBus');
        const namespace: string = this.ownNamespace(optionalStringParam(params, 'namespace') ?? this.appId);
        const topic: string = this.safeTopic(stringParam(params, 'topic'));
        const payload: JsonValue = params['payload'] === undefined ? null : params['payload'];
        this.requirePayloadSize(payload, 16 * 1024);
        this.rateLimitEventPublish();
        await this.audit(method, 'eventBus', 'eventBus.publish', 'ok' as JsonValue);
        this.platform.eventBus.publish(namespace, topic, payload);
        return true;
      }
      case 'launch': {
        sandbox.require('launch');
        const targetAppId: string = stringParam(params, 'appId');
        this.platform.launchLimiter.check();
        return await this.confirm('打开另一个小应用？', `「${appTitle}」想打开小应用 ${targetAppId}。`, async (): Promise<JsonValue> => {
          const target: MiniAppRecord | null = await repository.getById(targetAppId);
          if (target === null) throw new MiniAppValidationException('Target MiniApp does not exist');
          await this.audit(method, 'launch', 'launch', targetAppId as JsonValue);
          this.host.launch(targetAppId);
          return true;
        });
      }

      // ===== 诚实错误(not_available_on_harmony → envelope ok:false,JS reject)=====
      case 'sensor.subscribe':
      case 'sensor.unsubscribe': {
        sandbox.require('sensor');
        throw new MiniAppValidationException('not_available_on_harmony');
      }
      case 'location.getCurrent': {
        sandbox.require('location');
        throw new MiniAppValidationException('not_available_on_harmony');
      }
      case 'clipboard.read': {
        sandbox.require('clipboard.read');
        throw new MiniAppValidationException('not_available_on_harmony');
      }
      case 'ai.generate': {
        sandbox.require('ai.generate');
        throw new MiniAppValidationException('not_available_on_harmony');
      }
      case 'host.sendToConversation': {
        sandbox.require('host.sendToConversation');
        throw new MiniAppValidationException('not_available_on_harmony');
      }
      case 'host.createArtifact': {
        sandbox.require('host.createArtifact');
        throw new MiniAppValidationException('not_available_on_harmony');
      }

      default:
        throw new MiniAppValidationException(`Unknown MiniApp bridge method: ${method}`);
    }
  }

  // miniAppMinimalHostContext → JsonObject(minimalHostContext 为 JsonObject 语义)
  private hostContextJson(record: MiniAppRecord, maxChars: number): JsonValue {
    const context: JsonObject = {};
    context['untrustedContext'] = true as JsonValue;
    context['appId'] = record.id as JsonValue;
    context['title'] = (record.title.slice(0, 80)) as JsonValue;
    context['description'] = (record.description.slice(0, 200)) as JsonValue;
    context['boardSummary'] = ((record.boardSummary ?? '').slice(0, maxChars)) as JsonValue;
    context['sourceConversationId'] = (record.sourceConversationId ?? '') as JsonValue;
    context['sourceMessageId'] = (record.sourceMessageId ?? '') as JsonValue;
    context['note'] = 'MiniApp host context is minimized. Full chat history, system prompts, provider settings, credentials, and hidden tool outputs are not exposed.' as JsonValue;
    return context;
  }

  private async audit(method: string, permission: MiniAppPermission, summary: string, payload: JsonValue): Promise<void> {
    await this.platform.repository.appendAudit(
      this.appId, method, permission, summary, JSON.stringify(payload));
  }

  private async confirm(title: string, message: string, block: () => Promise<JsonValue>): Promise<JsonValue> {
    if (this.closed) throw new MiniAppSecurityException('MiniApp runner is closed');
    const accepted: boolean = await this.host.confirm(title, message.slice(0, 420));
    if (this.closed) throw new MiniAppSecurityException('MiniApp runner is closed');
    if (!accepted) throw new MiniAppSecurityException('User denied MiniApp request');
    return block();
  }

  private ownNamespace(namespace: string): string {
    if (namespace !== this.appId) {
      throw new MiniAppValidationException('Cross-app namespace is not granted');
    }
    return namespace;
  }

  private safeTopic(topic: string): string {
    const normalized: string = topic.trim();
    if (normalized.length < 1 || normalized.length > 64 ||
      !/^[a-zA-Z0-9._:-]+$/.test(normalized)) {
      throw new MiniAppValidationException('Invalid topic');
    }
    return normalized;
  }

  private requirePayloadSize(payload: JsonValue, maxBytes: number): void {
    const bytes: number = utf8ByteLength(JSON.stringify(payload));
    if (bytes > maxBytes) throw new MiniAppValidationException('Payload is too large');
  }

  private rateLimitEventPublish(): void {
    const now: number = Date.now();
    while (this.eventPublishTimes.length > 0 && now - this.eventPublishTimes[0] > 10000) {
      this.eventPublishTimes.shift();
    }
    if (this.eventPublishTimes.length >= 30) {
      throw new MiniAppValidationException('EventBus publish rate limit exceeded');
    }
    this.eventPublishTimes.push(now);
  }

  private decodeBody(result: MiniAppFetchBytesResult, responseType: string): JsonValue {
    const text: string = util.TextDecoder.create('utf-8').decodeToString(result.bytes);
    if (responseType === 'json') {
      try {
        return JSON.parse(text) as JsonValue;
      } catch {
        return null;
      }
    }
    if (responseType === 'dataurl') {
      const base64: string = new util.Base64Helper().encodeToStringSync(result.bytes);
      const mime: string = result.contentType.length > 0 ? result.contentType : 'application/octet-stream';
      return `data:${mime};base64,${base64}`;
    }
    return text.slice(0, MINI_APP_MAX_TEXT_CHARS);
  }

  private async searchItems(query: string, limit: number): Promise<JsonValue> {
    const registry: SearchProviderRegistry = getAppContainer().searchRegistry;
    const providers: SearchProvider[] = [];
    const enabled: SearchProvider[] = registry.enabled();
    for (const p of enabled) providers.push(p);
    const fallback: SearchProvider[] = registry.fallback();
    for (const p of fallback) providers.push(p);
    const hits: SearchHit[] = [];
    for (const provider of providers) {
      try {
        const results: SearchHit[] = await provider.search([query]);
        for (const r of results) hits.push(r);
      } catch (e) {
        hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'MiniApp search provider failed: %{public}s',
          e instanceof Error ? e.message : String(e));
      }
    }
    // 按 url 去重(SearchAggregator dedupe 语义)
    const seen: string[] = [];
    const items: JsonValue[] = [];
    for (const hit of hits) {
      if (hit.url.length === 0 || seen.indexOf(hit.url) >= 0) continue;
      seen.push(hit.url);
      const item: JsonObject = {};
      item['title'] = (hit.title.slice(0, 120)) as JsonValue;
      item['url'] = (hit.url.slice(0, 500)) as JsonValue;
      item['snippet'] = ((hit.snippet ?? '').slice(0, 300)) as JsonValue;
      item['source'] = (hit.source.slice(0, 60)) as JsonValue;
      // Android MiniAppSearchBridge:published_at 存在才输出(publishedAt)
      if (hit.publishedAt !== undefined && hit.publishedAt !== null && hit.publishedAt.length > 0) {
        item['publishedAt'] = (hit.publishedAt.slice(0, 40)) as JsonValue;
      }
      items.push(item);
      if (items.length >= limit) break;
    }
    const payload: JsonObject = {};
    payload['items'] = items;
    return payload;
  }
}
