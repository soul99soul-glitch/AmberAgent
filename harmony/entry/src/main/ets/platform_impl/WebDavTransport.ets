// DAV needs arbitrary HTTP methods and a binary body; keep the LLM text port separate.
// API 12: RCP Request supports custom methods and ArrayBuffer request/response bodies.
import { rcp } from '@kit.RemoteCommunicationKit';
import type { WebDavRequest, WebDavResponse, WebDavTransport } from '@amber/chat-domain';

class EntryWebDavTransport implements WebDavTransport {
  async fetch(request: WebDavRequest): Promise<WebDavResponse> {
    const session: rcp.Session = rcp.createSession();
    try {
      let content: rcp.RequestContent | undefined;
      if (request.body instanceof Uint8Array) {
        // slice copies only this view, not unused prefix/suffix in its backing buffer.
        content = request.body.slice().buffer as ArrayBuffer;
      } else {
        content = request.body;
      }
      const nativeRequest: rcp.Request = new rcp.Request(
        request.url, request.method, request.headers, content);
      const response: rcp.Response = await session.fetch(nativeRequest);
      return { status: response.statusCode,
        body: new Uint8Array(response.body ?? new ArrayBuffer(0)) };
    } finally {
      session.close();
    }
  }
}

export const createWebDavTransport = (): WebDavTransport => new EntryWebDavTransport();
