// RcpHttpClient — HttpClient 契约的实现
//
// 历史教训:之前用 @kit.RemoteCommunicationKit (RCP),它用自己的 DNS 解析器,
// 在某些设备上默认 DNS 是 127.0.x.x(回环),导致 "Couldn't resolve host name"。
// 现改用 @ohos.net.http —— 它走系统 NetStack,用系统配置的 DNS,稳定可靠。
//
// 对照设计 §8.1。ArkTS 兼容:class implements。

import { http } from '@kit.NetworkKit';
import { util } from '@kit.ArkTS';
import { hilog } from '@kit.PerformanceAnalysisKit';
import type { HttpClient, HttpRequest, HttpResponse, AbortSignalLike } from '@amber/deepread-domain';
import { httpResponseStatusFromHeaders, isEventStreamResponse } from '@amber/deepread-domain';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.Http';
const STREAM_PREFIX_PROBE_BYTES: number = 1024;

const sanitizeUrlForLog = (url: string): string => {
  const hashIndex: number = url.indexOf('#');
  const withoutFragment: string = hashIndex >= 0 ? url.slice(0, hashIndex) : url;
  const queryIndex: number = withoutFragment.indexOf('?');
  const withoutQuery: string = queryIndex >= 0 ? withoutFragment.slice(0, queryIndex) : withoutFragment;
  const schemeEnd: number = withoutQuery.indexOf('//');
  if (schemeEnd < 0) return withoutQuery;
  const authorityStart: number = schemeEnd + 2;
  const pathStart: number = withoutQuery.indexOf('/', authorityStart);
  const authorityEnd: number = pathStart >= 0 ? pathStart : withoutQuery.length;
  const authority: string = withoutQuery.slice(authorityStart, authorityEnd);
  const atIndex: number = authority.lastIndexOf('@');
  if (atIndex < 0) return withoutQuery;
  return `${withoutQuery.slice(0, authorityStart)}${authority.slice(atIndex + 1)}${withoutQuery.slice(authorityEnd)}`;
};

interface FetchOpts {
  signal?: AbortSignalLike;
}

interface StreamOpts {
  onChunk: (chunk: ArrayBuffer, end: boolean) => void;
  onDataEnd?: () => void;
  signal?: AbortSignalLike;
  // R18:消费方解析到协议终态(done/error)后返回 true → 立即销毁底层请求并
  //   以当前状态 settle,不再等服务端 dataEnd(避免终止帧后持续心跳挂等)。
  shouldStop?: () => boolean;
}

class OhosHttpClient implements HttpClient {
  fetch(req: HttpRequest, opts?: FetchOpts): Promise<HttpResponse> {
    const url: string = req.url;
    const safeUrl: string = sanitizeUrlForLog(url);
    const method: string = req.method.toUpperCase();
    const signal: AbortSignalLike | undefined = opts?.signal;
    hilog.info(DOMAIN, TAG, 'fetch start: %{public}s %{public}s', method, safeUrl);

    const requestMethod: http.RequestMethod = this.mapMethod(method);
    const httpRequest: http.HttpRequest = http.createHttp();
    const options: http.HttpRequestOptions = {
      method: requestMethod,
      header: req.headers,
      expectDataType: http.HttpDataType.STRING,
      connectTimeout: 15000,
      // LLM 非流式响应(文章规划等)首包常超 20s — 与流式读超时对齐 120s
      readTimeout: 120000,
      usingCache: false,
    };
    if (req.body !== undefined && req.body !== null &&
      (method === 'POST' || method === 'PUT' || method === 'PATCH')) {
      options.extraData = req.body;
    }

    return new Promise<HttpResponse>((resolve, reject): void => {
      let settled: boolean = false;
      let destroyed: boolean = false;

      const destroyOnce = (): void => {
        if (destroyed) return;
        destroyed = true;
        httpRequest.destroy();
      };
      const cleanup = (): void => {
        signal?.removeEventListener?.('abort', onAbort);
        destroyOnce();
      };
      const fail = (error: Error): void => {
        if (settled) return;
        settled = true;
        cleanup();
        reject(error);
      };
      const onAbort = (): void => {
        hilog.warn(DOMAIN, TAG, 'fetch aborted: %{public}s', safeUrl);
        const abortError: Error = new Error(`${safeUrl} fetch aborted`);
        abortError.name = 'AbortError'; // 下游按名称识别取消(区别于网络失败)
        fail(abortError);
      };

      if (signal?.aborted) {
        onAbort();
        return;
      }
      signal?.addEventListener?.('abort', onAbort);
      if (settled || signal?.aborted) {
        onAbort();
        return;
      }

      httpRequest.request(url, options)
        .then((resp: http.HttpResponse): void => {
          if (settled) return;
          const status: number = resp.responseCode;
          const resultVal: string | object | ArrayBuffer = resp.result;
          const bodyText: string = typeof resultVal === 'string'
            ? resultVal : this.bodyToString(resultVal);
          const headers: Record<string, string> = this.copyHeaders(resp.header);
          settled = true;
          cleanup();
          hilog.info(DOMAIN, TAG, 'fetch done: %{public}s status=%{public}d len=%{public}d',
            safeUrl, status, bodyText.length);
          resolve({ status, headers, body: bodyText });
        })
        .catch((e: Error): void => {
          if (settled) return;
          const errText: string = e !== null && e !== undefined && e.message !== undefined
            ? `${e.message}` : JSON.stringify(e);
          hilog.error(DOMAIN, TAG, 'fetch FAILED: %{public}s err=%{public}s', safeUrl, errText);
          fail(new Error(`${safeUrl} fetch failed: ${errText}`));
        });
    });
  }

  // status 未知时以 SSE MIME 或正文开头的协议字段确认流；JSON 等正文等待真实状态码。
  fetchStream(req: HttpRequest, opts: StreamOpts): Promise<HttpResponse> {
    const url: string = req.url;
    const safeUrl: string = sanitizeUrlForLog(url);
    const method: string = req.method.toUpperCase();
    hilog.info(DOMAIN, TAG, 'fetchStream start: %{public}s %{public}s', method, safeUrl);

    const requestMethod: http.RequestMethod = this.mapMethod(method);
    const httpRequest: http.HttpRequest = http.createHttp();
    const signal: AbortSignalLike | undefined = opts.signal;
    const options: http.HttpRequestOptions = {
      method: requestMethod,
      header: req.headers,
      expectDataType: http.HttpDataType.ARRAY_BUFFER,
      connectTimeout: 15000,
      readTimeout: 120000,
      usingCache: false,
    };
    if (req.body !== undefined && req.body !== null &&
      (method === 'POST' || method === 'PUT' || method === 'PATCH')) {
      options.extraData = req.body;
    }

    return new Promise<HttpResponse>((resolve, reject): void => {
      let settled: boolean = false;
      let destroyed: boolean = false;
      let status: number | null = null;
      let eventStream: boolean = false;
      let dataEnded: boolean = false;
      let headers: Record<string, string> = {};
      let pendingChunks: ArrayBuffer[] = [];
      let chunkCount: number = 0;
      let totalBytes: number = 0;
      let probedPrefixBytes: number = 0;
      let dataEndNotified: boolean = false;

      const destroyOnce = (): void => {
        if (destroyed) return;
        destroyed = true;
        httpRequest.destroy();
      };
      const cleanup = (): void => {
        signal?.removeEventListener?.('abort', onAbort);
        destroyOnce();
      };
      const fail = (error: Error, logMessage: string): void => {
        if (settled) return;
        settled = true;
        cleanup();
        hilog.error(DOMAIN, TAG, logMessage, safeUrl);
        reject(error);
      };
      const notifyDataEnd = (): boolean => {
        if (dataEndNotified) return true;
        dataEndNotified = true;
        if (opts.onDataEnd === undefined) return true;
        try {
          opts.onDataEnd();
          return true;
        } catch (e) {
          fail(new Error(`${safeUrl} dataEnd callback failed: ${String(e)}`),
            'fetchStream dataEnd callback failed: %{public}s');
          return false;
        }
      };
      // R18:协议 done/error 已达成 → 关闭底层并立即 settle(携带真实 status;
      //   status 尚未回传时为 0,消费方已按 state.done 提前返回,不再判 status)。
      const stopConsumption = (): boolean => {
        if (settled) return false;
        if (!notifyDataEnd()) return false;
        if (settled) return false;
        settled = true;
        cleanup();
        hilog.info(DOMAIN, TAG, 'fetchStream stopped by consumer: %{public}s', safeUrl);
        resolve({ status: status !== null ? status : 0, headers, body: '' });
        return false;
      };
      const emit = (chunk: ArrayBuffer, end: boolean): boolean => {
        try {
          opts.onChunk(chunk, end);
        } catch (e) {
          fail(new Error(`${safeUrl} stream callback failed: ${String(e)}`),
            'fetchStream callback failed: %{public}s');
          return false;
        }
        const shouldStop: (() => boolean) | undefined = opts.shouldStop;
        if (shouldStop !== undefined && shouldStop()) {
          return stopConsumption();
        }
        return true;
      };
      const flushPendingStreamChunks = (): boolean => {
        const chunks: ArrayBuffer[] = pendingChunks;
        pendingChunks = [];
        for (const chunk of chunks) {
          if (!emit(chunk, false)) return false;
        }
        return true;
      };
      const canConsumeStream = (): boolean => {
        if (status === null) return eventStream;
        return status >= 200 && status < 400;
      };
      const settleWhenReady = (): void => {
        if (settled) return;
        if (status !== null && status <= 0) {
          fail(new Error(`${safeUrl} stream returned invalid HTTP status ${status}`),
            'fetchStream invalid status: %{public}s');
          return;
        }
        if (canConsumeStream() && !flushPendingStreamChunks()) return;
        if (status === null || !dataEnded) return;

        if (status >= 400) {
          const body: string = this.decodeChunks(pendingChunks);
          pendingChunks = [];
          settled = true;
          cleanup();
          resolve({ status, headers, body });
          return;
        }
        if (!emit(new ArrayBuffer(0), true)) return;
        if (settled) return;
        settled = true;
        cleanup();
        hilog.info(DOMAIN, TAG,
          'fetchStream done: %{public}s status=%{public}d chunks=%{public}d bytes=%{public}d',
          safeUrl, status, chunkCount, totalBytes);
        resolve({ status, headers, body: '' });
      };
      const onAbort = (): void => {
        hilog.warn(DOMAIN, TAG, 'fetchStream aborted: %{public}s', safeUrl);
        const abortError: Error = new Error(`${safeUrl} stream aborted`);
        abortError.name = 'AbortError'; // 同 fetch:取消与网络失败可区分
        fail(abortError, 'fetchStream aborted: %{public}s');
      };

      if (signal?.aborted) {
        onAbort();
        return;
      }
      signal?.addEventListener?.('abort', onAbort);
      if (settled || signal?.aborted) {
        onAbort();
        return;
      }

      httpRequest.on('headersReceive', (raw: Object): void => {
        if (settled) return;
        headers = this.copyHeaders(raw);
        eventStream = eventStream || isEventStreamResponse(headers);
        // requestInStream 的 Promise 在 dataEnd 后才返回状态码。
        // 提供状态行的运行时可从响应头提前识别最终响应。
        const headerStatus: number | null = httpResponseStatusFromHeaders(headers);
        if (headerStatus !== null &&
          ((headerStatus >= 200 && headerStatus < 300) || headerStatus >= 400)) {
          status = headerStatus;
        }
        // 当前 Harmony 运行时不含状态行。SSE MIME/正文前缀只决定提前消费，
        // status 仍未知；最终 HTTP code 或既有协议终态负责收口，不伪造 200。
        settleWhenReady();
      });
      httpRequest.on('dataReceive', (data: ArrayBuffer): void => {
        if (settled) return;
        chunkCount += 1;
        totalBytes += data.byteLength;
        if (canConsumeStream()) {
          emit(data, false);
        } else {
          pendingChunks.push(data);
          // Some compatible gateways send SSE as text/plain or omit MIME. Waiting
          // for requestInStream's status buffers the whole answer until dataEnd.
          // Inspect only the response prefix; known HTTP errors stay buffered.
          if (status === null && probedPrefixBytes < STREAM_PREFIX_PROBE_BYTES) {
            probedPrefixBytes = Math.min(totalBytes, STREAM_PREFIX_PROBE_BYTES);
            if (this.hasEventStreamPrefix(pendingChunks)) {
              eventStream = true;
              flushPendingStreamChunks();
            }
          }
        }
      });
      httpRequest.on('dataEnd', (): void => {
        if (settled) return;
        if (!notifyDataEnd()) return;
        dataEnded = true;
        settleWhenReady();
      });

      httpRequest.requestInStream(url, options)
        .then((code: number): void => {
          if (settled) return;
          status = code;
          settleWhenReady();
        })
        .catch((e: Error): void => {
          if (settled) return;
          fail(new Error(`${safeUrl} stream failed: ${String(e)}`),
            'fetchStream FAILED: %{public}s');
        });
    });
  }

  private mapMethod(method: string): http.RequestMethod {
    switch (method) {
      case 'GET': return http.RequestMethod.GET;
      case 'POST': return http.RequestMethod.POST;
      case 'PUT': return http.RequestMethod.PUT;
      case 'DELETE': return http.RequestMethod.DELETE;
      case 'PATCH':
        // RequestMethod.PATCH is API 26+, while this product targets API 12.
        throw new Error('PATCH is not supported by this Harmony HTTP runtime');
      default: throw new Error(`Unsupported HTTP method: ${method}`);
    }
  }

  private copyHeaders(raw: Object | undefined): Record<string, string> {
    const headers: Record<string, string> = {};
    if (raw === undefined || raw === null) return headers;
    const source: Record<string, string> = raw as Record<string, string>;
    for (const key of Object.keys(source)) headers[key] = source[key];
    return headers;
  }

  private hasEventStreamPrefix(chunks: ArrayBuffer[]): boolean {
    const prefix: Uint8Array = new Uint8Array(STREAM_PREFIX_PROBE_BYTES);
    let length: number = 0;
    for (const chunk of chunks) {
      const bytes: Uint8Array = new Uint8Array(chunk);
      const copied: number = Math.min(bytes.length, STREAM_PREFIX_PROBE_BYTES - length);
      prefix.set(bytes.subarray(0, copied), length);
      length += copied;
      if (length === STREAM_PREFIX_PROBE_BYTES) break;
    }
    const decoder: util.TextDecoder = util.TextDecoder.create('utf-8');
    const text: string = decoder.decodeToString(prefix.subarray(0, length));
    // SSE fields/comments start at column zero; allow BOM and initial empty lines.
    // A data: occurrence inside JSON or an ordinary error message is insufficient.
    return /^(?:\uFEFF)?(?:\r?\n)*(?:data|event|id|retry|):/.test(text);
  }

  private decodeChunks(chunks: ArrayBuffer[]): string {
    let total: number = 0;
    for (const chunk of chunks) total += chunk.byteLength;
    const bytes: Uint8Array = new Uint8Array(total);
    let offset: number = 0;
    for (const chunk of chunks) {
      const part: Uint8Array = new Uint8Array(chunk);
      bytes.set(part, offset);
      offset += part.byteLength;
    }
    if (bytes.length === 0) return '';
    const td: util.TextDecoder = util.TextDecoder.create('utf-8');
    return td.decodeToString(bytes);
  }

  private bodyToString(result: string | object | ArrayBuffer): string {
    if (typeof result === 'string') return result;
    if (result instanceof ArrayBuffer) {
      const td: util.TextDecoder = util.TextDecoder.create('utf-8');
      return td.decodeToString(new Uint8Array(result));
    }
    if (result instanceof Uint8Array) {
      const td: util.TextDecoder = util.TextDecoder.create('utf-8');
      return td.decodeToString(result);
    }
    try {
      return JSON.stringify(result);
    } catch (_) {
      return String(result);
    }
  }
}

export const createRcpHttpClient = (): HttpClient => new OhosHttpClient();
