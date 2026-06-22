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
  fetch(req: HttpRequest, opts?: { signal?: AbortSignal }): Promise<HttpResponse>;
  fetchStream(
    req: HttpRequest,
    opts: {
      onChunk: (chunk: ArrayBuffer, end: boolean) => void;
      signal?: AbortSignal;
    },
  ): Promise<HttpResponse>;
}
