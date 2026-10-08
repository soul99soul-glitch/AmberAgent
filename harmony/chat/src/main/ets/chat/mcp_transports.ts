// mcp_transports — MCP 两 transport 全文移植(amber 自有代码,非 SDK)
//
// Android 基准:
//   app/core/ai/mcp/transport/SseClientTransport.kt(201 行全文)— 旧版 SSE:
//     GET sseSession → 'endpoint' 事件给 POST 端点;start 二启 check 文案(:66-68);
//     send 三 check(:97-99)+ POST 非 2xx 'Error POSTing to endpoint (HTTP N): body'
//     (:113-115);事件分发 'error'→onError+抛 IllegalStateException('SSE error: data')
//     /'open'→noop/'endpoint'/else→handleMessage(:134-148);handleEndpoint
//     绝对路径=origin+data/相对=baseUrl+'/'+data,baseUrl 三分支(:54-63);
//     handleMessage 解析失败仅 onError(:182-184);closeResources CAS 幂等+onClose
//   app/core/ai/mcp/transport/StreamableHttpClientTransport.kt(369 行全文):
//     send:check 'Transport is not started'(:105);Accept 'application/json,
//     text/event-stream' + Content-Type json(:121-122);mcp-session-id 捕获(:127);
//     202 → initialized 通知时异步起 SSE(:129-143);非 2xx → StreamableHttpError
//     (status,body)(:145-149);contentType json→全文解码(失败 onError+抛)/
//     event-stream→inline 行解析/else 空体无 ct→return,否则
//     StreamableHttpError(-1,'Unexpected content type: $<ct>')(:151-176);
//     terminateSession:405 容忍,他错 'Failed to terminate session: <desc>'
//     (:201-223);startSseSession:GET accept(json)+(resumptionToken ?: lastEventId)
//     →Last-Event-ID;405/ct=json → 静默返回(:247-262);collectSse:id 沉淀+
//     onResumptionToken;null/'message' 解码(replay 改 id)/'error'→onError
//     (:280-314);handleInlineSse:id:/event:/data: trim **concat 无换行**(:362-366
//     quirk 逐字);空行 dispatch;解码失败 onError+抛(:344-348)
//
// 端口架构(XmlPullPort/InflateRawPort 先例):平台 HTTP/SSE 经 McpHttpPort 注入,
//   entry 以 rcp+SseAssembler 实现,测试以 fake 驱动;JSON 编解码 = McpJson 口径
//   (explicitNulls=false 由构造侧省略保证;键序 = 构造序)。
// 偏差登记:baseUrl 取请求 URL(Android 用 session 终态 URL,重定向后差异);
//   ktor sseSession 自动重连(reconnectionTime)未移植 — amber 恒 null(:74 默认),
//    reconnect 由 McpManager 层承担(D-119);resumptionToken 发送侧路径保留
//   但 McpManager/SDK 请求面从不构造(SDK RequestOptions 仅 timeout)→ 不可达。

import type { JsonObject, JsonValue } from './json.ts';
import type { McpTransport } from './mcp_protocol.ts';

// ===== 平台端口(entry 实现) =====

export interface McpHttpResponse {
  status: number;
  statusDescription: string;
  // 响应头(大小写不敏感查找由 transport 层小写化)
  headers: Array<[string, string]>;
  // Content-Type 响应头原值(可带参数);无 → null
  contentType: string | null;
  // 非流响应全文(流式响应为 '')
  bodyText: string;
}

export interface McpSseEvent {
  event: string | null;
  data: string | null;
  id: string | null;
}

export interface McpSseStream {
  // 下一事件;null → 流结束
  next(): Promise<McpSseEvent | null>;
  cancel(): Promise<void>;
}

export interface McpPostStreamResponse {
  status: number;
  statusDescription: string;
  headers: Array<[string, string]>;
  // Content-Type 响应头原值(可带参数);无 → null
  contentType: string | null;
  // contentType 非 text/event-stream:全文;event-stream:'' + lines
  bodyText: string;
  lines: McpSseLineStream | null;
}

export interface McpSseLineStream {
  readLine(): Promise<string | null>; // null → EOF
  cancel(): Promise<void>;
}

// openSse 失败(需 status/contentType 供 405/json-mode 判定 — :247-262)
export class McpSseOpenError extends Error {
  readonly status: number | null;
  readonly contentType: string | null;

  constructor(message: string, status: number | null, contentType: string | null) {
    super(message);
    this.name = 'McpSseOpenError';
    this.status = status;
    this.contentType = contentType;
  }
}

// 用户自定义头不得覆盖协议必需头;名称大小写不敏感合并
const mergeMcpHeaders = (
  user: Array<[string, string]>, protocol: Array<[string, string]>,
  protocolFirst: boolean,
): Array<[string, string]> => {
  const out: Array<[string, string]> = [];
  for (const pair of user) {
    const clash: boolean = protocol.some(
      (proto: [string, string]): boolean =>
        proto[0].toLowerCase() === pair[0].toLowerCase());
    if (!clash) out.push(pair);
  }
  // 顺序按各传输层金样;用户自定义头不可覆盖协议必需头
  return protocolFirst ? [...protocol, ...out] : [...out, ...protocol];
};

export interface McpHttpPort {
  request(method: string, url: string, headers: Array<[string, string]>,
    body: string | null, requestId?: string, timeoutMs?: number): Promise<McpHttpResponse>;
  openSse(url: string, headers: Array<[string, string]>): Promise<McpSseStream>;
  postStream(url: string, headers: Array<[string, string]>,
    body: string, requestId?: string, timeoutMs?: number): Promise<McpPostStreamResponse>;
  cancelPending?: (requestId: string) => void | Promise<void>;
}

// StreamableHttpError(:54-55):message = 'Streamable HTTP error: <message>'
export class StreamableHttpError extends Error {
  readonly code: number | null;

  constructor(code: number | null, message: string | null) {
    super(`Streamable HTTP error: ${message}`);
    this.name = 'StreamableHttpError';
    this.code = code;
  }
}

// ===== 共用 =====

const headerGet = (headers: Array<[string, string]>, name: string): string | null => {
  const lower: string = name.toLowerCase();
  for (const [k, v] of headers) {
    if (k.toLowerCase() === lower) return v;
  }
  return null;
};

const decodeMessage = (data: string): JsonObject => {
  const parsed: JsonValue = JSON.parse(data) as JsonValue;
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    throw new Error('Serializer for JSONRPCMessage: not a JSON object');
  }
  return parsed;
};

// ContentType.withoutParameters() 等价(:151 比较侧;错误消息保留原值 :171)
const withoutParams = (ct: string | null): string | null =>
  ct === null ? null : ct.split(';')[0].trim();

// URL 分解(origin = protocolWithAuthority;path 含前导 '/')
interface UrlParts {
  origin: string;
  path: string;
}

const splitUrl = (url: string): UrlParts => {
  const schemeEnd: number = url.indexOf('://');
  if (schemeEnd < 0) return { origin: url, path: '' };
  const rest: string = url.substring(schemeEnd + 3);
  const pathStart: number = rest.indexOf('/');
  if (pathStart < 0) return { origin: url, path: '' };
  let path: string = rest.substring(pathStart);
  const q: number = path.search(/[?#]/);
  if (q >= 0) path = path.substring(0, q);
  return { origin: url.substring(0, schemeEnd + 3 + pathStart), path };
};

// baseUrl 三分支(SseClientTransport.kt:54-63 逐字)
const sseBaseUrl = (requestUrl: string): string => {
  const { origin, path } = splitUrl(requestUrl);
  if (path.length === 0) return origin;
  if (path.endsWith('/')) return origin + path.substring(0, path.length - 1);
  return origin + path.substring(0, path.lastIndexOf('/'));
};

// ===== SseClientTransport(201 行全文) =====

// SDK AbstractTransport 回调为组合语义(onClose/onError:old→block 链;
// McpManager 先于 client.connect 注册的 hook 不被覆盖 — 0.8.4 源证实)
const compose0 = (old: () => void, block: () => void): () => void =>
  (): void => {
    old();
    block();
  };
const compose1 = (old: (e: Error) => void, block: (e: Error) => void): (e: Error) => void =>
  (e: Error): void => {
    old(e);
    block(e);
  };
const composeMsg = (old: (m: JsonObject) => void,
  block: (m: JsonObject) => void): (m: JsonObject) => void =>
  (m: JsonObject): void => {
    old(m);
    block(m);
  };

export class McpSseClientTransport implements McpTransport {
  private readonly port: McpHttpPort;
  private readonly urlString: string;
  private readonly configHeaders: Array<[string, string]>;
  private initialized: boolean = false;
  private session: McpSseStream | null = null;
  private jobActive: boolean = false;
  private endpointUrl: string | null = null;
  private endpointResolve: (url: string) => void = (): void => {};
  private endpointReject: (e: Error) => void = (): void => {};
  private endpointPromise: Promise<string> = Promise.resolve('');
  private closeBlock: () => void = (): void => {};
  private errorBlock: (e: Error) => void = (): void => {};
  private messageBlock: (msg: JsonObject) => void = (): void => {};

  constructor(port: McpHttpPort, urlString: string, configHeaders: Array<[string, string]>) {
    this.port = port;
    this.urlString = urlString;
    this.configHeaders = configHeaders;
  }

  onClose(block: () => void): void {
    this.closeBlock = compose0(this.closeBlock, block);
  }

  onError(block: (e: Error) => void): void {
    this.errorBlock = compose1(this.errorBlock, block);
  }

  onMessage(block: (msg: JsonObject) => void): void {
    this.messageBlock = composeMsg(this.messageBlock, block);
  }

  cancelPending(requestId: string): void | Promise<void> {
    return this.port.cancelPending?.(requestId);
  }

  // :65-93
  async start(): Promise<void> {
    if (this.initialized) {
      throw new Error('SSEClientTransport already started! If using Client class, ' +
        'note that connect() calls start() automatically.');
    }
    this.initialized = true;
    this.endpointPromise = new Promise((resolve, reject): void => {
      this.endpointResolve = resolve;
      this.endpointReject = reject;
    });
    // openSse 失败路径不 await endpointPromise → closeResources 的 reject
    // 会成为 unhandled;预挂 noop(不影响 start 处 await 的正常感知)
    this.endpointPromise.catch((): void => {});
    try {
      // Legacy SSE 必须声明接受事件流(Android Ktor SSE 插件自动补,鸿蒙需显式)
      this.session = await this.port.openSse(this.urlString,
        mergeMcpHeaders(this.configHeaders, [['Accept', 'text/event-stream']], false));
      this.jobActive = true;
      this.pump(); // collectMessages 协程(浮空,finally closeResources)
      await this.endpointPromise;
    } catch (e) {
      await this.closeResources();
      this.initialized = false;
      throw e;
    }
  }

  // :96-122
  async send(message: JsonObject, timeoutMs?: number): Promise<void> {
    if (!this.initialized) throw new Error('SseClientTransport is not initialized!');
    if (!this.jobActive) throw new Error('SseClientTransport is closed!');
    if (this.endpointUrl === null) throw new Error('Not connected!');
    try {
      const headers: Array<[string, string]> = mergeMcpHeaders(this.configHeaders, [
        ['Content-Type', 'application/json'],
      ], false);
      const requestId: string | undefined = typeof message['id'] === 'string'
        ? message['id'] as string : undefined;
      const response: McpHttpResponse = await this.port.request(
        'POST', this.endpointUrl, headers, JSON.stringify(message), requestId, timeoutMs);
      // bodyAsText 消费(:108-111 注释语义:端口层已全文读取)
      if (response.status < 200 || response.status >= 300) {
        throw new Error(
          `Error POSTing to endpoint (HTTP ${response.status}): ${response.bodyText}`);
      }
    } catch (e) {
      const err: Error = e instanceof Error ? e : new Error(String(e));
      this.errorBlock(err);
      throw err;
    }
  }

  // :124-127
  async close(): Promise<void> {
    if (!this.initialized) throw new Error('SseClientTransport is not initialized!');
    await this.closeResources();
  }

  // :129-158 collectMessages
  private pump(): void {
    const session: McpSseStream = this.session as McpSseStream;
    const loop = async (): Promise<void> => {
      try {
        for (;;) {
          const event: McpSseEvent | null = await session.next();
          if (event === null) break;
          const name: string | null = event.event;
          if (name === 'error') {
            const error: Error = new Error(`SSE error: ${event.data ?? ''}`);
            this.errorBlock(error);
            throw error;
          } else if (name === 'open') {
            // 连接已开,等待 endpoint(:141-143)
          } else if (name === 'endpoint') {
            this.handleEndpoint(event.data ?? '');
          } else {
            this.handleMessage(event.data ?? '');
          }
        }
      } catch (e) {
        const err: Error = e instanceof Error ? e : new Error(String(e));
        this.errorBlock(err);
        throw err;
      } finally {
        await this.closeResources();
      }
    };
    loop().catch((): void => {});
  }

  // :160-176
  private handleEndpoint(eventData: string): void {
    try {
      const endpointUrl: string = eventData.startsWith('/')
        ? splitUrl(this.urlString).origin + eventData
        : `${sseBaseUrl(this.urlString)}/${eventData}`;
      this.endpointUrl = endpointUrl;
      this.endpointResolve(endpointUrl);
    } catch (e) {
      const err: Error = e instanceof Error ? e : new Error(String(e));
      this.errorBlock(err);
      this.endpointReject(err);
      throw err;
    }
  }

  // :178-185(解析失败仅 onError)
  private handleMessage(data: string): void {
    try {
      this.messageBlock(decodeMessage(data));
    } catch (e) {
      this.errorBlock(e instanceof Error ? e : new Error(String(e)));
    }
  }

  // :187-200(CAS 幂等)
  private async closeResources(): Promise<void> {
    if (!this.initialized) return;
    this.initialized = false;
    this.jobActive = false;
    // endpoint 事件前流终止/主动 close:必须解除 start() 的 await,
    // 否则 connect() 永久挂起(settle-once,已 resolve 后为 no-op)
    this.endpointReject(new Error('SSE connection closed before endpoint event'));
    try {
      if (this.session !== null) await this.session.cancel();
    } catch (e) {
      this.errorBlock(e instanceof Error ? e : new Error(String(e)));
    }
    this.closeBlock();
  }
}

// ===== StreamableHttpClientTransport(369 行全文) =====

const MCP_SESSION_ID_HEADER: string = 'mcp-session-id';
const MCP_PROTOCOL_VERSION_HEADER: string = 'mcp-protocol-version';
const MCP_RESUMPTION_TOKEN_HEADER: string = 'Last-Event-ID';

export class McpStreamableHttpTransport implements McpTransport {
  private readonly port: McpHttpPort;
  private readonly url: string;
  private readonly configHeaders: Array<[string, string]>;
  // :69-71(amber 无 protocolVersion 写入面 — 字段保留)
  sessionId: string | null = null;
  protocolVersion: string | null = null;
  private initialized: boolean = false;
  private sseSession: McpSseStream | null = null;
  private lastEventId: string | null = null;
  private closeBlock: () => void = (): void => {};
  private errorBlock: (e: Error) => void = (): void => {};
  private messageBlock: (msg: JsonObject) => void = (): void => {};

  constructor(port: McpHttpPort, url: string, configHeaders: Array<[string, string]>) {
    this.port = port;
    this.url = url;
    this.configHeaders = configHeaders;
  }

  onClose(block: () => void): void {
    this.closeBlock = compose0(this.closeBlock, block);
  }

  onError(block: (e: Error) => void): void {
    this.errorBlock = compose1(this.errorBlock, block);
  }

  onMessage(block: (msg: JsonObject) => void): void {
    this.messageBlock = composeMsg(this.messageBlock, block);
  }

  cancelPending(requestId: string): void | Promise<void> {
    return this.port.cancelPending?.(requestId);
  }

  // :82-87
  start(): Promise<void> {
    if (this.initialized) {
      return Promise.reject(new Error('StreamableHttpClientTransport already started!'));
    }
    this.initialized = true;
    return Promise.resolve();
  }

  private applyCommonHeaders(headers: Array<[string, string]>): Array<[string, string]> {
    const out: Array<[string, string]> = [...headers];
    if (this.sessionId !== null) out.push([MCP_SESSION_ID_HEADER, this.sessionId]);
    if (this.protocolVersion !== null) out.push([MCP_PROTOCOL_VERSION_HEADER, this.protocolVersion]);
    return out;
  }

  // initialize 协商出的协议版本回写(mcp_protocol connect 调用)
  setProtocolVersion(version: string): void {
    this.protocolVersion = version;
  }

  // :92-177(resumptionToken 发送侧 :109-116 — 我方 client 不构造,保留分支)
  async send(message: JsonObject, timeoutMs?: number): Promise<void> {
    if (!this.initialized) throw new Error('Transport is not started');

    const jsonBody: string = JSON.stringify(message);
    const headers: Array<[string, string]> = mergeMcpHeaders(this.configHeaders, [
      ...this.applyCommonHeaders([]),
      ['Accept', 'application/json, text/event-stream'],
      ['Content-Type', 'application/json'],
    ], true);
    const requestId: string | undefined = typeof message['id'] === 'string'
      ? message['id'] as string : undefined;
    const response: McpPostStreamResponse = await this.port.postStream(
      this.url, headers, jsonBody, requestId, timeoutMs);

    const sessionHeader: string | null = headerGet(response.headers, MCP_SESSION_ID_HEADER);
    if (sessionHeader !== null) this.sessionId = sessionHeader;

    // :129-143
    if (response.status === 202) {
      if (message['method'] === 'notifications/initialized' && message['id'] === undefined) {
        // 异步起 SSE,不阻塞 send(:131-141 注释语义)
        this.startSseSession(null, null).catch((e: Error): void => {
          this.errorBlock(e);
        });
      }
      return;
    }

    // :145-149
    if (response.status < 200 || response.status >= 300) {
      const error: StreamableHttpError = new StreamableHttpError(
        response.status, response.bodyText);
      this.errorBlock(error);
      throw error;
    }

    // :151-176 content-type 三分支(比较侧 withoutParameters)
    const ctNoParams: string | null = withoutParams(response.contentType);
    if (ctNoParams === 'application/json') {
      if (response.bodyText.length > 0) {
        try {
          this.messageBlock(decodeMessage(response.bodyText));
        } catch (e) {
          const err: Error = e instanceof Error ? e : new Error(String(e));
          this.errorBlock(err);
          throw err;
        }
      }
      return;
    }
    if (ctNoParams === 'text/event-stream') {
      await this.handleInlineSse(response.lines);
      return;
    }
    if (response.contentType === null && response.bodyText.trim().length === 0) return;
    const ct: string = response.contentType ?? '<none>';
    // :172 — Kotlin "$$ct" = 字面 '$' + 模板 ct(逐字)
    const error: StreamableHttpError = new StreamableHttpError(-1, `Unexpected content type: $${ct}`);
    this.errorBlock(error);
    throw error;
  }

  // :179-196
  async close(): Promise<void> {
    if (!this.initialized) return;
    try {
      await this.terminateSession();
      if (this.sseSession !== null) await this.sseSession.cancel();
    } catch {
      // 清理期错误忽略(:190-192)
    } finally {
      this.initialized = false;
      this.closeBlock();
    }
  }

  // :201-223
  async terminateSession(): Promise<void> {
    if (this.sessionId === null) return;
    const response: McpHttpResponse = await this.port.request(
      'DELETE', this.url, mergeMcpHeaders(this.configHeaders, this.applyCommonHeaders([]), true), null);
    const success: boolean = response.status >= 200 && response.status < 300;
    if (!success && response.status !== 405) {
      const error: StreamableHttpError = new StreamableHttpError(
        response.status, `Failed to terminate session: ${response.statusDescription}`);
      this.errorBlock(error);
      throw error;
    }
    this.sessionId = null;
    this.lastEventId = null;
  }

  // :225-271
  private async startSseSession(
    resumptionToken: string | null, replayMessageId: JsonValue | null,
  ): Promise<void> {
    if (this.sseSession !== null) await this.sseSession.cancel();
    const protocol: Array<[string, string]> = [...this.applyCommonHeaders([])];
    protocol.push(['Accept', 'application/json']);
    const token: string | null = resumptionToken ?? this.lastEventId;
    if (token !== null) protocol.push([MCP_RESUMPTION_TOKEN_HEADER, token]);
    const headers: Array<[string, string]> =
      mergeMcpHeaders(this.configHeaders, protocol, true);

    let stream: McpSseStream;
    try {
      stream = await this.port.openSse(this.url, headers);
    } catch (e) {
      // :247-262 — 405(不支持 GET/SSE)/ct=json(JSON-only 模式)→ 静默返回
      if (e instanceof McpSseOpenError) {
        if (e.status === 405) return;
        if (e.contentType !== null
          && e.contentType.trim().split(';')[0].trim().toLowerCase() === 'application/json') {
          return;
        }
      }
      const err: Error = e instanceof Error ? e : new Error(String(e));
      this.errorBlock(err);
      throw err;
    }
    this.sseSession = stream;
    this.collectSse(stream, replayMessageId).catch((): void => {});
  }

  // :280-314
  private async collectSse(session: McpSseStream,
    replayMessageId: JsonValue | null): Promise<void> {
    try {
      for (;;) {
        const event: McpSseEvent | null = await session.next();
        if (event === null) break;
        if (event.id !== null) {
          this.lastEventId = event.id;
        }
        const name: string | null = event.event;
        if (name === null || name === 'message') {
          const data: string | null = event.data;
          if (data !== null && data.length > 0) {
            try {
              const msg: JsonObject = decodeMessage(data);
              this.messageBlock(this.rewriteReplay(msg, replayMessageId));
            } catch (e) {
              this.errorBlock(e instanceof Error ? e : new Error(String(e)));
            }
          }
        } else if (name === 'error') {
          this.errorBlock(new StreamableHttpError(null, event.data));
        }
      }
    } catch (e) {
      this.errorBlock(e instanceof Error ? e : new Error(String(e)));
    }
  }

  // Only an explicit resumption may rewrite an ID. Normal POST streams retain
  // every JSON-RPC ID so the client's pending table matches the actual response.
  private rewriteReplay(msg: JsonObject, replayMessageId: JsonValue | null): JsonObject {
    if (replayMessageId === null || msg['result'] === undefined) return msg;
    const out: JsonObject = { ...msg };
    out['id'] = replayMessageId;
    return out;
  }

  private async handleInlineSse(lines: McpSseLineStream | null): Promise<void> {
    if (lines === null) return;
    let sb: string = '';
    let id: string | null = null;
    let eventName: string | null = null;

    const dispatch = (): void => {
      if (id !== null) {
        this.lastEventId = id;
      }
      if (sb.trim().length === 0) return;
      if (eventName === null || eventName === 'message') {
        try {
          const msg: JsonObject = decodeMessage(sb);
          this.messageBlock(msg);
        } catch (e) {
          // :344-348 — onError + 抛
          const err: Error = e instanceof Error ? e : new Error(String(e));
          this.errorBlock(err);
          throw err;
        }
      }
    };

    for (;;) {
      const rawLine: string | null = await lines.readLine();
      if (rawLine === null) break;
      const line: string = rawLine.endsWith('\r') ? rawLine.slice(0, -1) : rawLine;
      if (line.length === 0) {
        dispatch();
        id = null;
        eventName = null;
        sb = '';
        continue;
      }
      if (line.startsWith('id:')) {
        id = line.substring(line.indexOf('id:') + 3).trim();
      } else if (line.startsWith('event:')) {
        eventName = line.substring(line.indexOf('event:') + 6).trim();
      } else if (line.startsWith('data:')) {
        const data: string = line.substring(5);
        sb += (sb.length === 0 ? '' : '\n') + (data.startsWith(' ') ? data.substring(1) : data);
      }
    }
    dispatch();
  }
}
