// RcpHttpClient — HttpClient 契约的真实实现(@kit.RemoteCommunicationKit)
// 对照设计 §8.1
//
// TODO(toolchain/ArkTS 验证): RCP API 细节(SESSION / request 方法名 / headers 类型)
// 需在 DevEco + API 24 下编译校正。当前基于 HarmonyOS RCP 文档常见用法编写。

import { rcp } from '@kit.RemoteCommunicationKit';
import type { HttpClient, HttpRequest, HttpResponse } from '@amber/deepread-domain';

interface InternalResponse {
  statusCode: number;
  body: ArrayBuffer;
  headers: Record<string, string>;
}

export const createRcpHttpClient = (): HttpClient => {
  // 单例 session(RCP 推荐复用)
  // TODO(arkts): createSession 配置项(timeout / CA / proxy)待按 §8.1 补
  let session: rcp.Session | null = null;
  const getSession = (): rcp.Session => {
    if (session === null) {
      session = rcp.createSession();
    }
    return session;
  };

  const headersToRcp = (h: Record<string, string>): Record<string, string> => h;

  const fetch = async (req: HttpRequest): Promise<HttpResponse> => {
    const s = getSession();
    const method = (req.method ?? 'GET').toUpperCase();
    // TODO(arkts): RCP 的 fetch/method 签名以 API 24 实际为准
    const resp = await s.fetch(req.url, {
      method: method as rcp.RequestMethod,
      headers: headersToRcp(req.headers),
      content: req.body !== null && req.body !== undefined ? req.body : undefined,
    } as rcp.RequestParams);
    const bodyText = typeof resp.body === 'string' ? resp.body : uint8ToText(resp.body);
    return {
      status: resp.statusCode,
      headers: resp.headers as Record<string, string>,
      body: bodyText,
    };
  };

  const fetchStream = async (_req: HttpRequest): Promise<ReadableStream> => {
    // TODO(phase6): SSE 流式 — RCP 的 stream 分块回调,接 sse_assembler。
    // 当前 AiClient 的 SSE 由 OpenAiCompatibleAiClient 处理(它直接用 fetch + ReadableStream)。
    throw new Error('fetchStream not implemented — SSE handled by AiClient');
  };

  return { fetch, fetchStream };
};

// ArrayBuffer → string(UTF-8)。TODO(arkts): 用 @ohos.util.TextDecoder
const uint8ToText = (buf: ArrayBuffer | Uint8Array): string => {
  const bytes = buf instanceof Uint8Array ? buf : new Uint8Array(buf);
  let s = '';
  for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
  // 简易 UTF-8 解码(生产用 TextDecoder)
  try {
    return decodeURIComponent(escape(s));
  } catch {
    return s;
  }
};
