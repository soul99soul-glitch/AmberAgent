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

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.Http';

// DEBUG: 模拟器代理(绕过 Clash fake-ip DNS;正式环境删除)
const debugProxy: http.HttpProxy = {
  host: '10.0.2.2',
  port: 7897,
  exclusionList: [],
};

// fetchStream 回调选项(对齐 HttpClient.fetchStream 的内联类型)
interface StreamOpts {
  onChunk: (chunk: ArrayBuffer, end: boolean) => void;
  signal?: AbortSignalLike;
}

// DEBUG: URL 重写结果(绕过 Clash fake-ip)
interface UrlRewriteResult {
  url: string;
  host: string | null;
}

class OhosHttpClient implements HttpClient {
  async fetch(req: HttpRequest): Promise<HttpResponse> {
    const url: string = req.url;
    const method: string = req.method.toUpperCase();
    hilog.info(DOMAIN, TAG, 'fetch start: %{public}s %{public}s', method, url);

    const httpRequest: http.HttpRequest = http.createHttp();

    // 请求选项
    const options: http.HttpRequestOptions = {
      method: this.mapMethod(method),
      header: req.headers,
      expectDataType: http.HttpDataType.STRING,
      connectTimeout: 15000,
      readTimeout: 20000,
      usingCache: false,
      // DEBUG: 模拟器代理(绕过 Clash fake-ip DNS;正式环境删除 usingProxy)
      usingProxy: debugProxy,
    };

    // body:POST/PUT 时带 body
    if (req.body !== undefined && req.body !== null && (method === 'POST' || method === 'PUT' || method === 'PATCH')) {
      options.extraData = req.body;
    }

    try {
      const resp: http.HttpResponse = await httpRequest.request(url, options);
      const status: number = resp.responseCode;
      const resultVal: string | object | ArrayBuffer = resp.result;
      const bodyText: string = typeof resultVal === 'string'
        ? resultVal
        : this.bodyToString(resultVal);
      hilog.info(DOMAIN, TAG, 'fetch done: %{public}s status=%{public}d len=%{public}d',
        url, status, bodyText.length);

      const headers: Record<string, string> = {};
      if (resp.header !== undefined && resp.header !== null) {
        const h: Record<string, string> = resp.header as Record<string, string>;
        const keys: string[] = Object.keys(h);
        for (let i = 0; i < keys.length; i++) {
          headers[keys[i]] = h[keys[i]];
        }
      }

      const result: HttpResponse = {
        status: status,
        headers: headers,
        body: bodyText,
      };
      return result;
    } catch (e) {
      const errMsg: string = String(e);
      hilog.error(DOMAIN, TAG, 'fetch FAILED: %{public}s err=%{public}s', url, errMsg);
      throw new Error(`${url} fetch failed: ${errMsg}`);
    } finally {
      httpRequest.destroy();
    }
  }

  // 流式请求:用 @ohos.net.http 的 requestInStream(API 12+),body 经 dataReceive 事件
  // 以 ArrayBuffer 分块吐出,经 opts.onChunk 传给上层(SseAssembler 再组装成 SSE)。
  // TODO(DevEco 验证):requestInStream / dataReceive / dataEnd 的确切签名随 SDK 版本可能微调。
  async fetchStream(req: HttpRequest, opts: StreamOpts): Promise<HttpResponse> {
    const url: string = req.url;
    const method: string = req.method.toUpperCase();
    hilog.info(DOMAIN, TAG, 'fetchStream start: %{public}s %{public}s', method, url);

    const httpRequest: http.HttpRequest = http.createHttp();

    const options: http.HttpRequestOptions = {
      method: this.mapMethod(method),
      header: req.headers,
      expectDataType: http.HttpDataType.ARRAY_BUFFER,
      connectTimeout: 15000,
      readTimeout: 120000, // 流式生成可能持续较久
      usingCache: false,
    };
    if (req.body !== undefined && req.body !== null && (method === 'POST' || method === 'PUT' || method === 'PATCH')) {
      options.extraData = req.body;
    }

    // 中止:已 aborted 直接拒绝;运行中 aborted 则 destroy(由 settled 防重复结算)
    let settled: boolean = false;
    const signal: AbortSignalLike | undefined = opts.signal;
    if (signal !== undefined && signal.aborted) {
      httpRequest.destroy();
      throw new Error(`${url} stream aborted before start`);
    }
    if (signal !== undefined && signal.addEventListener !== undefined) {
      signal.addEventListener('abort', () => {
        if (!settled) {
          settled = true;
          httpRequest.destroy();
        }
      });
    }

    return new Promise<HttpResponse>((resolve, reject) => {
      let status: number = 0;

      httpRequest.on('dataReceive', (data: ArrayBuffer) => {
        if (settled) return;
        opts.onChunk(data, false);
      });

      httpRequest.on('dataEnd', () => {
        if (settled) return;
        settled = true;
        opts.onChunk(new ArrayBuffer(0), true);
        hilog.info(DOMAIN, TAG, 'fetchStream done: %{public}s status=%{public}d', url, status);
        httpRequest.destroy();
        const result: HttpResponse = { status: status, headers: {}, body: '' };
        resolve(result);
      });

      httpRequest.requestInStream(url, options)
        .then((code: number) => {
          status = code;
          if (code >= 400) {
            // 4xx/5xx:服务端返回错误体(通常非流式),不依赖上层 status 检查,直接拒绝
            if (!settled) {
              settled = true;
              httpRequest.destroy();
              hilog.warn(DOMAIN, TAG, 'fetchStream rejected: %{public}s status=%{public}d', url, code);
              reject(new Error(`${url} stream HTTP ${code}`));
            }
          } else if (code !== 200) {
            hilog.warn(DOMAIN, TAG, 'fetchStream non-200: %{public}s status=%{public}d', url, code);
          }
        })
        .catch((e: Error) => {
          if (settled) return;
          settled = true;
          httpRequest.destroy();
          const errMsg: string = String(e);
          hilog.error(DOMAIN, TAG, 'fetchStream FAILED: %{public}s err=%{public}s', url, errMsg);
          reject(new Error(`${url} stream failed: ${errMsg}`));
        });
    });
  }

  private mapMethod(method: string): http.RequestMethod {
    switch (method) {
      case 'GET': return http.RequestMethod.GET;
      case 'POST': return http.RequestMethod.POST;
      case 'PUT': return http.RequestMethod.PUT;
      case 'DELETE': return http.RequestMethod.DELETE;
      case 'HEAD': return http.RequestMethod.HEAD;
      case 'OPTIONS': return http.RequestMethod.OPTIONS;
      default: return http.RequestMethod.GET;
    }
  }

  private bodyToString(result: string | object | ArrayBuffer): string {
    if (typeof result === 'string') {
      return result;
    }
    if (result instanceof Uint8Array) {
      const td: util.TextDecoder = util.TextDecoder.create('utf-8');
      return td.decodeToString(result);
    }
    // 对象/数组 → JSON
    try {
      return JSON.stringify(result);
    } catch (_) {
      return String(result);
    }
  }
}

export const createRcpHttpClient = (): HttpClient => {
  return new OhosHttpClient();
};
