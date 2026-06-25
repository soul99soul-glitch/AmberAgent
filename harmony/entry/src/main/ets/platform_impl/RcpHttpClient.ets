// RcpHttpClient — HttpClient 契约的真实实现(@kit.RemoteCommunicationKit)
// 对照设计 §8.1。ArkTS 兼容:class implements。
// RCP API:rcp.createSession() → session.fetch(new rcp.Request(url, method, headers, content))

import { rcp } from '@kit.RemoteCommunicationKit';
import { util } from '@kit.ArkTS';
import { hilog } from '@kit.PerformanceAnalysisKit';
import type { HttpClient, HttpRequest, HttpResponse } from '@amber/deepread-domain';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.RCP';

class RcpHttpClient implements HttpClient {
  private session: rcp.Session | null = null;

  private getSession(): rcp.Session {
    if (this.session === null) {
      hilog.info(DOMAIN, TAG, 'createSession()');
      this.session = rcp.createSession();
    }
    return this.session;
  }

  async fetch(req: HttpRequest): Promise<HttpResponse> {
    const s: rcp.Session = this.getSession();
    const url: string = req.url;
    const method: rcp.HttpMethod = req.method as rcp.HttpMethod;
    const headers: Record<string, string> = req.headers;
    hilog.info(DOMAIN, TAG, 'fetch start: %{public}s %{public}s', method, url);
    const rcpReq: rcp.Request = req.body !== undefined && req.body !== null
      ? new rcp.Request(url, method, headers, req.body)
      : new rcp.Request(url, method, headers);
    try {
      const resp: rcp.Response = await s.fetch(rcpReq);
      hilog.info(DOMAIN, TAG, 'fetch done: %{public}s status=%{public}d', url, resp.statusCode);
      const bodyText: string = this.bodyToString(resp);
      const result: HttpResponse = {
        status: resp.statusCode,
        headers: {},
        body: bodyText,
      };
      return result;
    } catch (e) {
      const errMsg: string = String(e);
      hilog.error(DOMAIN, TAG, 'fetch FAILED: %{public}s err=%{public}s', url, errMsg);
      throw new Error(`${url} fetch failed: ${errMsg}`);
    }
  }

  async fetchStream(_req: HttpRequest): Promise<HttpResponse> {
    throw new Error('fetchStream not implemented');
  }

  private bodyToString(resp: rcp.Response): string {
    const td: util.TextDecoder = util.TextDecoder.create('utf-8');
    if (resp.body === undefined || resp.body === null) {
      return '';
    }
    return td.decodeToString(new Uint8Array(resp.body));
  }
}

export const createRcpHttpClient = (): HttpClient => {
  return new RcpHttpClient();
};
