import type { AbortSignalLike } from './runtime_api.ts';
// HttpClient — 鸿蒙 RCP 封装的接口契约
// 实现留后续(RcpHttpClient 用 @kit.NetworkKit 的 rcp.createSession)

export interface HttpRequest {
  url: string;
  method: 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH';
  headers: Record<string, string>;
  body?: string;
}

export interface HttpResponse {
  status: number;
  headers: Record<string, string>;
  body: string;
}

export interface HttpClient {
  fetch(req: HttpRequest, opts?: { signal?: AbortSignalLike }): Promise<HttpResponse>;
  fetchStream(
    req: HttpRequest,
    opts: {
      onChunk: (chunk: ArrayBuffer, end: boolean) => void;
      onDataEnd?: () => void;
      signal?: AbortSignalLike;
      /**
       * 协议收口(R18):消费方在解析到 done/error 等协议终态后置位,客户端在
       * 每次 onChunk 之后检视该回调,返回 true 即立即销毁底层请求并 settle
       * (不再等待服务端关流,也不泄漏连接)。未提供时行为不变(兼容既有实现)。
       */
      shouldStop?: () => boolean;
    },
  ): Promise<HttpResponse>;
}
