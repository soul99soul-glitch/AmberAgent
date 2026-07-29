// McpHttpPortImpl — McpHttpPort 的 entry 实现(@ohos.net.http + SseAssembler)
//
// D-118:domain 侧纯 transport(mcp_transports.ts)经 McpHttpPort 访问平台 HTTP/SSE,
// 本文件把端口接到 NetStack。与 RcpHttpClient 同源教训:用 @ohos.net.http 不用 RCP
// (DNS 回环问题)。
//
// 偏差登记:
// - statusDescription:Harmony http 不给 reason phrase,按常见码表映射(ktor
//   HttpStatusCode.description 等价子集);未知名 → ''
// - openSse/postStream 响应头经 headersReceive 事件获取;requestInStream 仅回 code
//   (HTTP 语义保证 headers 先于 body 到达,故 head 判定先于 dataReceive)
// - SseAssembler 对 MCP 事件流同构(data 多行 join '\n'/id/event 透传);[DONE] 哨兵
//   在 MCP 协议面不出现,done=true 按流结束处理

import { http } from '@kit.NetworkKit';
import { util } from '@kit.ArkTS';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { SseAssembler } from '@amber/deepread-domain';
import type { DecodeChunk } from '@amber/deepread-domain';
import { McpSseOpenError } from '@amber/chat-domain';
import type {
  McpHttpPort, McpHttpResponse, McpSseEvent, McpSseStream,
  McpPostStreamResponse, McpSseLineStream,
} from '@amber/chat-domain';

const DOMAIN: number = 0x0001;
const TAG: string = 'Mcp.HttpPort';

// ktor HttpStatusCode.description 子集(terminateSession 错误消息用)
const statusDescription = (code: number): string => {
  switch (code) {
    case 200: return 'OK';
    case 201: return 'Created';
    case 202: return 'Accepted';
    case 204: return 'No Content';
    case 301: return 'Moved Permanently';
    case 302: return 'Found';
    case 304: return 'Not Modified';
    case 400: return 'Bad Request';
    case 401: return 'Unauthorized';
    case 403: return 'Forbidden';
    case 404: return 'Not Found';
    case 405: return 'Method Not Allowed';
    case 408: return 'Request Timeout';
    case 409: return 'Conflict';
    case 429: return 'Too Many Requests';
    case 500: return 'Internal Server Error';
    case 501: return 'Not Implemented';
    case 502: return 'Bad Gateway';
    case 503: return 'Service Unavailable';
    case 504: return 'Gateway Timeout';
    default: return '';
  }
};

const mapMethod = (method: string): http.RequestMethod => {
  switch (method.toUpperCase()) {
    case 'GET': return http.RequestMethod.GET;
    case 'POST': return http.RequestMethod.POST;
    case 'PUT': return http.RequestMethod.PUT;
    case 'DELETE': return http.RequestMethod.DELETE;
    case 'HEAD': return http.RequestMethod.HEAD;
    case 'OPTIONS': return http.RequestMethod.OPTIONS;
    default: return http.RequestMethod.GET;
  }
};

const headerPairs = (raw: Object | undefined): Array<[string, string]> => {
  const out: Array<[string, string]> = [];
  if (raw === undefined || raw === null) return out;
  const h: Record<string, string> = raw as Record<string, string>;
  for (const k of Object.keys(h)) {
    const v: string = h[k];
    if (typeof v === 'string') out.push([k, v]);
  }
  return out;
};

const headerRecord = (headers: Array<[string, string]>): Record<string, string> => {
  const rec: Record<string, string> = {};
  for (const pair of headers) rec[pair[0]] = pair[1];
  return rec;
};

const headerFind = (headers: Array<[string, string]>, name: string): string | null => {
  const lower: string = name.toLowerCase();
  for (const pair of headers) {
    if (pair[0].toLowerCase() === lower) return pair[1];
  }
  return null;
};

const decodeChunk: DecodeChunk = (chunk: ArrayBuffer): string => {
  const td: util.TextDecoder = util.TextDecoder.create('utf-8');
  return td.decodeToString(new Uint8Array(chunk));
};

// 队列式 SSE 事件流(requestInStream 回调泵入,next() 消费)
class QueuedSseStream implements McpSseStream {
  private readonly assembler: SseAssembler = new SseAssembler(decodeChunk);
  private queue: Array<McpSseEvent | null> = [];
  private waiters: Array<(e: McpSseEvent | null) => void> = [];
  private ended: boolean = false;

  feed(chunk: ArrayBuffer): void {
    for (const e of this.assembler.feed(chunk)) {
      if (e.done) {
        this.end();
        return;
      }
      this.deliver({ event: e.event ?? null, data: e.data, id: e.id ?? null });
    }
  }

  finish(): void {
    for (const e of this.assembler.flush()) {
      if (!e.done) {
        this.deliver({ event: e.event ?? null, data: e.data, id: e.id ?? null });
      }
    }
    this.end();
  }

  private deliver(e: McpSseEvent): void {
    const w: ((e: McpSseEvent | null) => void) | undefined = this.waiters.shift();
    if (w !== undefined) w(e); else this.queue.push(e);
  }

  private end(): void {
    if (this.ended) return;
    this.ended = true;
    for (const w of this.waiters) w(null);
    this.waiters = [];
    this.queue.push(null);
  }

  next(): Promise<McpSseEvent | null> {
    const e: McpSseEvent | null | undefined = this.queue.shift();
    if (e !== undefined) return Promise.resolve(e);
    if (this.ended) return Promise.resolve(null);
    return new Promise((resolve): void => {
      this.waiters.push(resolve);
    });
  }

  cancel(): Promise<void> {
    this.end();
    return Promise.resolve();
  }
}

// 队列式行流(inline SSE:POST event-stream 响应的行源)
class QueuedLineStream implements McpSseLineStream {
  private buffer: string = '';
  private queue: Array<string | null> = [];
  private waiters: Array<(l: string | null) => void> = [];
  private ended: boolean = false;

  feed(chunk: ArrayBuffer): void {
    this.buffer += decodeChunk(chunk);
    for (;;) {
      const lf: number = this.buffer.indexOf('\n');
      if (lf < 0) break;
      let line: string = this.buffer.substring(0, lf);
      this.buffer = this.buffer.substring(lf + 1);
      if (line.endsWith('\r')) line = line.substring(0, line.length - 1);
      this.deliver(line);
    }
  }

  finish(): void {
    if (this.buffer.length > 0) {
      this.deliver(this.buffer);
      this.buffer = '';
    }
    this.end();
  }

  private deliver(line: string): void {
    const w: ((l: string | null) => void) | undefined = this.waiters.shift();
    if (w !== undefined) w(line); else this.queue.push(line);
  }

  private end(): void {
    if (this.ended) return;
    this.ended = true;
    for (const w of this.waiters) w(null);
    this.waiters = [];
    this.queue.push(null);
  }

  readLine(): Promise<string | null> {
    const l: string | null | undefined = this.queue.shift();
    if (l !== undefined) return Promise.resolve(l);
    if (this.ended) return Promise.resolve(null);
    return new Promise((resolve): void => {
      this.waiters.push(resolve);
    });
  }

  cancel(): Promise<void> {
    this.end();
    return Promise.resolve();
  }
}

class OhosMcpHttpPort implements McpHttpPort {
  // 非流式请求(SSE POST send / DELETE terminate)
  async request(method: string, url: string, headers: Array<[string, string]>,
    body: string | null): Promise<McpHttpResponse> {
    const httpRequest: http.HttpRequest = http.createHttp();
    const options: http.HttpRequestOptions = {
      method: mapMethod(method),
      header: headerRecord(headers),
      expectDataType: http.HttpDataType.STRING,
      connectTimeout: 15000,
      readTimeout: 20000,
      usingCache: false,
    };
    if (body !== null && (method === 'POST' || method === 'PUT' || method === 'PATCH')) {
      options.extraData = body;
    }
    try {
      const resp: http.HttpResponse = await httpRequest.request(url, options);
      const status: number = resp.responseCode;
      const pairs: Array<[string, string]> = headerPairs(resp.header);
      const resultVal: string | Object | ArrayBuffer = resp.result;
      const bodyText: string = typeof resultVal === 'string'
        ? resultVal : JSON.stringify(resultVal);
      return {
        status,
        statusDescription: statusDescription(status),
        headers: pairs,
        contentType: headerFind(pairs, 'content-type'),
        bodyText,
      };
    } catch (e) {
      hilog.error(DOMAIN, TAG, 'request FAILED: %{public}s err=%{public}s', url, String(e));
      // arkts-limited-throw:仅允许 Error 类型,非 Error 包装重抛
      if (e instanceof Error) throw e;
      throw new Error(String(e));
    } finally {
      httpRequest.destroy();
    }
  }

  // SSE 长连(legacy GET / streamable GET);非 2xx → McpSseOpenError(供 405/json 判定)
  openSse(url: string, headers: Array<[string, string]>): Promise<McpSseStream> {
    const httpRequest: http.HttpRequest = http.createHttp();
    const stream: QueuedSseStream = new QueuedSseStream();
    let respHeaders: Array<[string, string]> = [];

    httpRequest.on('headersReceive', (header: Object): void => {
      respHeaders = headerPairs(header);
    });
    httpRequest.on('dataReceive', (data: ArrayBuffer): void => {
      stream.feed(data);
    });
    httpRequest.on('dataEnd', (): void => {
      stream.finish();
      httpRequest.destroy();
    });

    const options: http.HttpRequestOptions = {
      method: http.RequestMethod.GET,
      header: headerRecord(headers),
      expectDataType: http.HttpDataType.ARRAY_BUFFER,
      connectTimeout: 15000,
      readTimeout: 120000,
      usingCache: false,
    };

    return httpRequest.requestInStream(url, options)
      .then((code: number): McpSseStream => {
        if (code < 200 || code >= 300) {
          // ktor sseSession 非 2xx → SSEClientException(status+contentType)
          httpRequest.destroy();
          throw new McpSseOpenError(
            `SSE open failed (HTTP ${code})`, code, headerFind(respHeaders, 'content-type'));
        }
        return stream;
      })
      .catch((e: Error): never => {
        if (e instanceof McpSseOpenError) throw e;
        httpRequest.destroy();
        hilog.error(DOMAIN, TAG, 'openSse FAILED: %{public}s err=%{public}s', url, String(e));
        throw new McpSseOpenError(String(e), null, null);
      });
  }

  // Streamable POST:event-stream 响应 → 行流即时返回;否则缓冲全文到 dataEnd
  postStream(url: string, headers: Array<[string, string]>,
    body: string): Promise<McpPostStreamResponse> {
    const httpRequest: http.HttpRequest = http.createHttp();
    const options: http.HttpRequestOptions = {
      method: http.RequestMethod.POST,
      header: headerRecord(headers),
      expectDataType: http.HttpDataType.ARRAY_BUFFER,
      connectTimeout: 15000,
      readTimeout: 120000,
      usingCache: false,
      extraData: body,
    };

    return new Promise<McpPostStreamResponse>((resolve, reject): void => {
      let respHeaders: Array<[string, string]> = [];
      let code: number = 0;
      let codeReady: boolean = false;
      let headReady: boolean = false;
      let decided: boolean = false;
      let lineStream: QueuedLineStream | null = null;
      let buffered: string = '';

      const makeHead = (lines: QueuedLineStream | null,
        bodyText: string): McpPostStreamResponse => ({
        status: code,
        statusDescription: statusDescription(code),
        headers: respHeaders,
        contentType: headerFind(respHeaders, 'content-type'),
        bodyText,
        lines,
      });

      // 头部判定:2xx + text/event-stream → 行流;其余 → 缓冲(HTTP 序保证先于 body)
      const tryDecide = (): void => {
        if (decided || !headReady || !codeReady) return;
        decided = true;
        const ct: string | null = headerFind(respHeaders, 'content-type');
        const ctBase: string | null = ct === null ? null : ct.split(';')[0].trim();
        if (code >= 200 && code < 300 && ctBase === 'text/event-stream') {
          lineStream = new QueuedLineStream();
          resolve(makeHead(lineStream, ''));
        }
        // 非流:等 dataEnd 时 resolve(下方)
      };

      httpRequest.on('headersReceive', (header: Object): void => {
        respHeaders = headerPairs(header);
        headReady = true;
        tryDecide();
      });
      httpRequest.on('dataReceive', (data: ArrayBuffer): void => {
        if (lineStream !== null) {
          lineStream.feed(data);
        } else {
          buffered += decodeChunk(data);
        }
      });
      httpRequest.on('dataEnd', (): void => {
        httpRequest.destroy();
        if (lineStream !== null) {
          lineStream.finish();
          return;
        }
        if (!decided) decided = true;
        resolve(makeHead(null, buffered));
      });

      httpRequest.requestInStream(url, options)
        .then((c: number): void => {
          code = c;
          codeReady = true;
          tryDecide();
        })
        .catch((e: Error): void => {
          httpRequest.destroy();
          if (!decided) {
            decided = true;
            hilog.error(DOMAIN, TAG, 'postStream FAILED: %{public}s err=%{public}s',
              url, String(e));
            reject(e);
          } else if (lineStream !== null) {
            lineStream.finish();
          }
        });
    });
  }
}

export const createMcpHttpPort = (): McpHttpPort => {
  return new OhosMcpHttpPort();
};
