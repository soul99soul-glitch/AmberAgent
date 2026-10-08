// mcp_protocol — MCP JSON-RPC client 核(io.modelcontextprotocol:kotlin-sdk 0.8.4 子集移植)
//
// Android 基准(amber 使用面 + SDK 内部行为,gradle/libs.versions.toml:150 锁 0.8.4):
//   shared/Protocol.kt — connect(:237-258 回调安装+transport.start)/request
//     (:437-522:**withTimeout 仅裹 send**,result.await() 无限等 — SDK quirk 逐字;
//     超时 → cancel:CancelledNotification{requestId,reason=McpException.message}
//     + 抛 TimeoutCancellationException 'Timed out waiting for <ms> ms')/
//     onResponse(:368-399 未知 id → onError IllegalStateException 文案逐字;
//     error → McpException(code,message,data))/onRequest(:401-429 无 handler →
//     METHOD_NOT_FOUND 'Server does not support <method>')/doClose(:261-273
//     挂起 handler 全数 CONNECTION_CLOSED 拒绝 + onClose)
//   client/Client.kt — connect(:172-211 initialize 握手:LATEST_PROTOCOL_VERSION
//     + capabilities + clientInfo;SUPPORTED 校验失败 'Server's protocol version
//     is not supported: <v>';notifications/initialized;catch → close + 映射
//     IllegalStateException 'Error connecting to transport: <msg>')/
//     ClientOptions(enforceStrictCapabilities=**true** 默认;:70-73)/
//     assertCapabilityForMethod(:214-256 ToolsCall/ToolsList →
//     'Server does not support tools (required for <枚举名>)';Initialize/Ping 豁免)
//   types/jsonRpc.kt — JSONRPCRequest id 默认 Uuid.random().**toHexString()**
//     (32 hex 无连字符);ErrorCode 常量;types/McpException.kt — message =
//     'MCP error <code>: <message>';types/methods.kt — 枚举名 ToolsCall/ToolsList
//   types/tools.kt — CallToolRequestParams(name,arguments?,_meta?)/
//     CallToolResult(content,isError?,structuredContent?,_meta?)/
//     ListToolsResult(tools,nextCursor?,_meta?);McpManager.kt:511-513
//     ToolSchema.toSchema(properties ?: {}, required)
//   Protocol.kt:93 DEFAULT_REQUEST_TIMEOUT=60s(McpManager callTool 传 120s)
//   McpManager.kt:500-509 McpJson:explicitNulls=false(null 字段省略)
//
// 边界登记:SDK 为外部依赖,本子集按行为忠实移植(Ktor/OkHttp → rcp 同类适配);
//   网络帧键序 = kotlinx 声明序(jsonrpc 尾键)—— 线无语义,仅记录。
//   协议级 onError 默认仅 Kotlin Log → 注入 hook,默认 noop(日志适配同例)。

import type { JsonObject, JsonValue } from './json.ts';
import type { AbortSignalLike } from '@amber/deepread-domain';
import { newId } from './ids.ts';

// ===== 常量(jsonRpc.kt/methods.kt/Protocol.kt) =====

export const JSONRPC_VERSION: string = '2.0';
export const MCP_LATEST_PROTOCOL_VERSION: string = '2025-06-18';
export const MCP_SUPPORTED_PROTOCOL_VERSIONS: string[] = [
  '2024-11-05', '2025-03-26', '2025-06-18',
];
export const MCP_DEFAULT_REQUEST_TIMEOUT_MS: number = 60000;

export const MCP_ERROR_CONNECTION_CLOSED: number = -32000;
export const MCP_ERROR_REQUEST_TIMEOUT: number = -32001;
export const MCP_ERROR_METHOD_NOT_FOUND: number = -32601;

// ===== McpException(McpException.kt:message = 'MCP error <code>: <message>') =====

export class McpError extends Error {
  readonly code: number;
  readonly data: JsonValue | null;

  constructor(code: number, message: string, data: JsonValue | null = null) {
    super(`MCP error ${code}: ${message}`);
    this.name = 'McpError';
    this.code = code;
    this.data = data;
  }
}

// kotlinx TimeoutCancellationException('Timed out waiting for <ms> ms') 等价
export class McpTimeoutError extends Error {
  constructor(timeoutMs: number) {
    super(`Timed out waiting for ${timeoutMs} ms`);
    this.name = 'McpTimeoutError';
  }
}

// ===== Transport(shared/Transport.kt) =====

export interface McpTransport {
  start(): Promise<void>;
  // timeoutMs:本条消息底层 HTTP 请求的读超时(缺省由 transport/平台决定)
  send(message: JsonObject, timeoutMs?: number): Promise<void>;
  // Streamable 专用:initialize 协商出的协议版本回写(后续请求头携带)
  setProtocolVersion?(version: string): void;
  close(): Promise<void>;
  onClose(block: () => void): void;
  onError(block: (e: Error) => void): void;
  onMessage(block: (msg: JsonObject) => void): void;
  // Abort only the HTTP operation carrying this JSON-RPC request id.
  cancelPending?: (requestId: string) => void | Promise<void>;
}

// ===== 内容/工具类型(tools.kt/content.kt 使用面子集) =====

export type McpContentBlock =
  | { type: 'text'; text: string }
  | { type: 'image'; data: string; mimeType: string }
  | { type: 'other'; raw: JsonValue };

export interface McpCallToolResult {
  content: McpContentBlock[];
  isError: boolean | null;
  structuredContent: JsonObject | null;
}

export interface McpServerTool {
  name: string;
  description: string | null;
  // ToolSchema.toSchema(McpManager.kt:511-513):properties ?: {} / required 原样
  inputSchema: { properties: JsonObject; required: string[] | null; jsonSchema?: JsonObject };
  annotations?: JsonObject;
}

interface PendingHandler {
  resolve: (result: JsonValue) => void;
  reject: (e: Error) => void;
}

const isObj = (v: JsonValue | undefined): v is JsonObject =>
  typeof v === 'object' && v !== null && !Array.isArray(v);

const hexRequestId = (): string => newId().replace(/-/g, '');

// ===== Client(client/Client.kt + shared/Protocol.kt 使用面子集) =====

export interface McpClientInfo {
  name: string;
  version: string;
}

export class McpClient {
  private readonly clientInfo: McpClientInfo;
  private transport: McpTransport | null = null;
  private readonly pending: Map<string, PendingHandler> = new Map();
  private readonly cancelledIds: Map<string, number> = new Map();
  private closeHook: () => void = (): void => {};
  private errorHook: (e: Error) => void = (): void => {};
  private messageHook: (msg: JsonObject) => void = (): void => {};
  private protocolErrorHook: (e: Error) => void = (): void => {};

  // Client.kt:115-135(initialize 完成后可读)
  serverCapabilities: JsonObject | null = null;
  serverVersion: McpClientInfo | null = null;
  serverInstructions: string | null = null;

  constructor(clientInfo: McpClientInfo) {
    this.clientInfo = clientInfo;
  }

  // McpManager.kt:143/:187 — client.transport == null 判定
  get hasTransport(): boolean {
    return this.transport !== null;
  }

  onClose(block: () => void): void {
    this.closeHook = block;
  }

  onError(block: (e: Error) => void): void {
    this.errorHook = block;
  }

  // 协议级错误出口(Protocol.onError;SDK 默认仅日志 → noop 默认)
  onProtocolError(block: (e: Error) => void): void {
    this.protocolErrorHook = block;
  }

  // Protocol.kt:237-258 + Client.kt:172-211
  async connect(transport: McpTransport): Promise<void> {
    this.transport = transport;
    transport.onClose((): void => { this.doClose(); });
    transport.onError((e: Error): void => { this.errorHook(e); });
    transport.onMessage((msg: JsonObject): void => { this.dispatchMessage(msg); });
    await transport.start();

    try {
      // InitializeRequest(params: protocolVersion/capabilities/clientInfo)
      const result: JsonValue = await this.request('initialize', {
        protocolVersion: MCP_LATEST_PROTOCOL_VERSION,
        capabilities: {},
        clientInfo: { name: this.clientInfo.name, version: this.clientInfo.version },
      } as JsonObject);
      const init: JsonObject = isObj(result) ? result : {};
      const protocolVersion: string = typeof init['protocolVersion'] === 'string'
        ? init['protocolVersion'] as string : '';
      if (MCP_SUPPORTED_PROTOCOL_VERSIONS.indexOf(protocolVersion) < 0) {
        throw new Error(`Server's protocol version is not supported: ${protocolVersion}`);
      }
      this.serverCapabilities = isObj(init['capabilities']) ? init['capabilities'] : null;
      const serverInfo: JsonValue | undefined = init['serverInfo'];
      this.serverVersion = isObj(serverInfo)
        ? {
          name: typeof serverInfo['name'] === 'string' ? serverInfo['name'] as string : '',
          version: typeof serverInfo['version'] === 'string' ? serverInfo['version'] as string : '',
        }
        : null;
      this.serverInstructions = typeof init['instructions'] === 'string'
        ? init['instructions'] as string : null;
      if (this.transport !== null && this.transport.setProtocolVersion !== undefined) {
        this.transport.setProtocolVersion(protocolVersion);
      }
      await this.notification('notifications/initialized', null);
    } catch (error) {
      await this.close();
      // Client.kt:200-209 映射:Cancellation/McpException/StreamableHttpError/
      //   SerializationException → 原样;其余 → IllegalStateException 包装
      if (error instanceof McpError || error instanceof McpTimeoutError) {
        throw error;
      }
      const msg: string = error instanceof Error ? error.message : String(error);
      throw new Error(`Error connecting to transport: ${msg}`);
    }
  }

  // Protocol.kt:437-522(strict capabilities 默认 true — ClientOptions:70-73)
  private request(method: string, params: JsonObject | null,
    timeoutMs: number = MCP_DEFAULT_REQUEST_TIMEOUT_MS,
    signal?: AbortSignalLike): Promise<JsonValue> {
    if (this.transport === null) throw new Error('Not connected');
    const transport: McpTransport = this.transport;
    // enforceStrictCapabilities:assertCapabilityForMethod(Client.kt:214-256)
    if (method === 'tools/call' || method === 'tools/list') {
      if (this.serverCapabilities === null || this.serverCapabilities['tools'] === undefined
        || this.serverCapabilities['tools'] === null) {
        const enumName: string = method === 'tools/call' ? 'ToolsCall' : 'ToolsList';
        throw new Error(`Server does not support tools (required for ${enumName})`);
      }
    }

    // 已 aborted:不再建 pending、不发送,直接取消语义返回
    if (signal !== undefined && signal.aborted) {
      const early: Error = new Error('MCP request aborted');
      early.name = 'AbortError';
      return Promise.reject(early);
    }
    const id: string = hexRequestId();
    const message: JsonObject = params !== null
      ? { id, method, params, jsonrpc: JSONRPC_VERSION }
      : { id, method, jsonrpc: JSONRPC_VERSION };

    // Stop 取消:删除 pending、取消底层 HTTP、通知服务端 cancelled、
    // 以 AbortError 结算 done(race 直含 done → 即使 transport.send 挂起也能立即结算)
    let settled: boolean = false;
    const onAbort = (): void => {
      if (settled) return;
      const entry = this.pending.get(id);
      if (entry === undefined) return;
      this.pending.delete(id);
      this.suppressLateResponse(id, timeoutMs);
      void transport.cancelPending?.(id);
      const note: JsonObject = {
        method: 'notifications/cancelled',
        params: { requestId: id, reason: 'Client aborted' },
        jsonrpc: JSONRPC_VERSION,
      };
      transport.send(note).catch((): void => {});
      const err: Error = new Error('MCP request aborted');
      err.name = 'AbortError';
      entry.reject(err);
    };
    const done: Promise<JsonValue> = new Promise((resolve, reject): void => {
      this.pending.set(id, { resolve, reject });
    });
    // JS 工件:send 失败后 handler 按 SDK 语义残留(doClose 才结算);此时 done
    //   无人 await → 预挂 noop 防 unhandledRejection 噪音(不改变可观察行为)
    done.catch((): void => {});
    if (signal !== undefined && signal.addEventListener !== undefined) {
      signal.addEventListener('abort', onAbort);
    }
    const detachAbortListener = (): void => {
      settled = true;
      if (signal !== undefined && signal.removeEventListener !== undefined) {
        signal.removeEventListener('abort', onAbort);
      }
    };

    const cancel = (reason: McpError): void => {
      this.pending.delete(id);
      this.suppressLateResponse(id, timeoutMs);
      void transport.cancelPending?.(id);
      const note: JsonObject = {
        method: 'notifications/cancelled',
        params: { requestId: id, reason: reason.message },
        jsonrpc: JSONRPC_VERSION,
      };
      // transport.send 失败沿 cancel 路径不掩盖原超时(cause 日志档,SDK 同)
      transport.send(note).catch((): void => {});
    };

    let timedOut: boolean = false;
    let timer: ReturnType<typeof setTimeout> | null = null;
    const timeout: Promise<JsonValue> = new Promise((_resolve, reject): void => {
      timer = setTimeout((): void => {
        timedOut = true;
        reject(new McpTimeoutError(timeoutMs));
      }, timeoutMs);
    });
    const clearTimer = (): void => {
      if (timer !== null) clearTimeout(timer);
    };
    // The timeout covers both transport acceptance and response completion.
    // race 直含 done:abort 触发的 done 拒绝无需等待 transport.send 完成
    const sendAndWait: Promise<JsonValue> = transport.send(message, timeoutMs).then(() => done);
    return Promise.race([sendAndWait, timeout, done])
      .then((value: JsonValue): JsonValue => {
        detachAbortListener();
        clearTimer();
        return value;
      })
      .catch((cause: Error): Promise<JsonValue> => {
        detachAbortListener();
        clearTimer();
        if (signal !== undefined && signal.aborted) {
          // abort 已触发:transport 晚到的错误统一按取消语义,不覆盖 AbortError
          const err: Error = new Error('MCP request aborted');
          err.name = 'AbortError';
          throw err;
        }
        if (!timedOut) {
          this.pending.delete(id);
          this.suppressLateResponse(id, timeoutMs);
          throw cause;
        }
        cancel(new McpError(MCP_ERROR_REQUEST_TIMEOUT, 'Request timed out', { timeout: timeoutMs }));
        throw cause;
      });
  }

  private notification(method: string, params: JsonObject | null): Promise<void> {
    if (this.transport === null) throw new Error('Not connected');
    const message: JsonObject = params !== null
      ? { method, params, jsonrpc: JSONRPC_VERSION }
      : { method, jsonrpc: JSONRPC_VERSION };
    return this.transport.send(message);
  }

  // Client.kt:484-490 listTools() → tools/list(params=null)
  async listTools(signal?: AbortSignalLike): Promise<McpServerTool[]> {
    const tools: McpServerTool[] = [];
    const cursors: Set<string> = new Set();
    let cursor: string | null = null;
    do {
      const result: JsonValue = await this.request('tools/list', cursor === null ? null : { cursor },
        MCP_DEFAULT_REQUEST_TIMEOUT_MS, signal);
      const obj: JsonObject = isObj(result) ? result : {};
      if (!Array.isArray(obj['tools'])) throw new Error('MCP tools/list returned no tools array');
      const raw: JsonValue[] = obj['tools'];
      tools.push(...raw.map((t: JsonValue): McpServerTool => {
        const o: JsonObject = isObj(t) ? t : {};
        const schema: JsonObject = isObj(o['inputSchema']) ? o['inputSchema'] : {};
        const reqRaw = Array.isArray(schema['required']) ? schema['required'] as JsonValue[] : null;
        const tool: McpServerTool = {
          name: typeof o['name'] === 'string' ? o['name'] as string : '',
          description: typeof o['description'] === 'string' ? o['description'] as string : null,
          inputSchema: {
            properties: isObj(schema['properties']) ? schema['properties'] : {},
            required: reqRaw === null ? null
              : reqRaw.filter((x: JsonValue): boolean => typeof x === 'string')
                .map((x: JsonValue): string => x as string),
          },
        };
        if (Object.keys(schema).some((key: string): boolean =>
          key !== 'type' && key !== 'properties' && key !== 'required')) tool.inputSchema.jsonSchema = schema;
        if (isObj(o['annotations'])) tool.annotations = o['annotations'];
        return tool;
      }));
      const next: JsonValue | undefined = obj['nextCursor'];
      cursor = typeof next === 'string' ? next : null;
      if (cursor !== null) {
        if (cursors.has(cursor)) throw new Error('MCP tools/list repeated a pagination cursor');
        cursors.add(cursor);
      }
    } while (cursor !== null);
    return tools;
  }

  // Client.kt:473-474 callTool(request, options);McpManager 传 timeout=120s
  async callTool(name: string, args: JsonObject,
    timeoutMs: number = MCP_DEFAULT_REQUEST_TIMEOUT_MS,
    signal?: AbortSignalLike): Promise<McpCallToolResult> {
    const result: JsonValue = await this.request('tools/call', {
      name, arguments: args,
    } as JsonObject, timeoutMs, signal);
    const obj: JsonObject = isObj(result) ? result : {};
    const raw = Array.isArray(obj['content']) ? obj['content'] as JsonValue[] : [];
    const content: McpContentBlock[] = raw.map((c: JsonValue): McpContentBlock => {
      const o: JsonObject = isObj(c) ? c : {};
      const type: string = typeof o['type'] === 'string' ? o['type'] as string : '';
      if (type === 'text') {
        return { type: 'text', text: typeof o['text'] === 'string' ? o['text'] as string : '' };
      }
      if (type === 'image') {
        return {
          type: 'image',
          data: typeof o['data'] === 'string' ? o['data'] as string : '',
          mimeType: typeof o['mimeType'] === 'string' ? o['mimeType'] as string : '',
        };
      }
      return { type: 'other', raw: c };
    });
    return {
      content,
      isError: typeof obj['isError'] === 'boolean' ? obj['isError'] as boolean : null,
      structuredContent: isObj(obj['structuredContent']) ? obj['structuredContent'] : null,
    };
  }

  // ===== 入站分发(Protocol.kt:246-256 when 序:response→request→notification→error→empty) =====

  private dispatchMessage(msg: JsonObject): void {
    const keys: string[] = Object.keys(msg);
    if (keys.length === 0) return; // JSONRPCEmptyMessage → Unit
    if (msg['result'] !== undefined) {
      this.onResponse(msg, null);
      return;
    }
    if (msg['method'] !== undefined && msg['id'] !== undefined) {
      this.onRequest(msg);
      return;
    }
    if (msg['method'] !== undefined) {
      this.onNotification(msg);
      return;
    }
    if (msg['error'] !== undefined) {
      this.onResponse(null, msg);
    }
  }

  private suppressLateResponse(id: string, ttlMs: number): void {
    const now: number = Date.now();
    for (const entry of this.cancelledIds.entries()) {
      if (entry[1] <= now) this.cancelledIds.delete(entry[0]);
    }
    this.cancelledIds.set(id, now + Math.max(60000, ttlMs));
    while (this.cancelledIds.size > 256) {
      let oldest: string | null = null;
      for (const key of this.cancelledIds.keys()) {
        oldest = key;
        break;
      }
      if (oldest === null) break;
      this.cancelledIds.delete(oldest);
    }
  }

  // Protocol.kt:368-399
  private onResponse(response: JsonObject | null, error: JsonObject | null): void {
    const src: JsonObject = (response ?? error) as JsonObject;
    const id: string = String(src['id']);
    const handler: PendingHandler | undefined = this.pending.get(id);
    if (handler === undefined) {
      const cancelledUntil: number | undefined = this.cancelledIds.get(id);
      if (cancelledUntil !== undefined) {
        this.cancelledIds.delete(id);
        if (cancelledUntil > Date.now()) return;
      }
      this.protocolErrorHook(new Error(
        `Received a response for an unknown message ID: ${JSON.stringify(src)}`));
      return;
    }
    this.pending.delete(id);
    if (response !== null) {
      handler.resolve(response['result'] as JsonValue);
    } else {
      const errRaw: JsonValue | undefined = (error as JsonObject)['error'];
      const errObj: JsonObject = isObj(errRaw) ? errRaw : {};
      handler.reject(new McpError(
        typeof errObj['code'] === 'number' ? errObj['code'] as number : 0,
        typeof errObj['message'] === 'string' ? errObj['message'] as string : '',
        errObj['data'] ?? null,
      ));
    }
  }

  // Protocol.kt:401-418 — 无 handler → METHOD_NOT_FOUND 回复
  private onRequest(request: JsonObject): void {
    const method: string = typeof request['method'] === 'string'
      ? request['method'] as string : '';
    const reply: JsonObject = {
      id: request['id'] as JsonValue,
      error: { code: MCP_ERROR_METHOD_NOT_FOUND, message: `Server does not support ${method}` },
      jsonrpc: JSONRPC_VERSION,
    };
    this.transport?.send(reply).catch((e: Error): void => { this.protocolErrorHook(e); });
  }

  private onNotification(notification: JsonObject): void {
    // Protocol.kt:346-365 — progress 无注册 handler → onError 文案逐字;
    //   其余通知无 handler → trace 日志档(noop)
    if (notification['method'] === 'notifications/progress') {
      this.protocolErrorHook(new Error(
        `Received a progress notification for an unknown token: ${JSON.stringify(notification)}`));
    }
  }

  // Protocol.kt:261-273 doClose(挂起全数 CONNECTION_CLOSED 拒绝 → onClose)
  private doClose(): void {
    const handlers: PendingHandler[] = Array.from(this.pending.values());
    this.pending.clear();
    this.cancelledIds.clear();
    this.transport = null;
    for (const h of handlers) {
      h.reject(new McpError(MCP_ERROR_CONNECTION_CLOSED, 'Connection closed'));
    }
    this.closeHook();
  }

  // Protocol.kt:403-404 — close() = transport?.close();transport 契约:
  //   close() 亦触发 onClose 回调(→ doClose)
  close(): Promise<void> {
    return this.transport?.close() ?? Promise.resolve();
  }
}
