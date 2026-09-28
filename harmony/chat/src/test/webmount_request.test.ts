import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { HttpClient, HttpRequest, HttpResponse, AbortSignalLike } from '@amber/deepread-domain';
import type { WebMountOAuthToken } from '../main/ets/chat/webmount_oauth.ts';
import { FeishuDocsClient, FEISHU_APP_ID_KEY } from '../main/ets/chat/feishu_docs_client.ts';
import type { FeishuDocsTokenStore } from '../main/ets/chat/feishu_docs_client.ts';
import { fetchWebMountRequest } from '../main/ets/chat/webmount_request.ts';

const response = (body: object): HttpResponse => ({ status: 200, headers: {}, body: JSON.stringify(body) });
const activeToken = (): WebMountOAuthToken => ({
  accessToken: 'access', refreshToken: 'refresh', expiresAtMillis: Date.now() + 3600000,
  tokenType: 'Bearer', scope: null,
});
const deferred = <T>(): { promise: Promise<T>; resolve: (value: T) => void } => {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => { resolve = r; });
  return { promise, resolve };
};

class ControlledHttp implements HttpClient {
  calls: Array<{ request: HttpRequest; signal: AbortSignalLike | undefined }> = [];
  next: (request: HttpRequest) => Promise<HttpResponse> = async () => response({ code: 0, data: {} });
  async fetch(request: HttpRequest, opts?: { signal?: AbortSignalLike }): Promise<HttpResponse> {
    this.calls.push({ request, signal: opts?.signal });
    return this.next(request);
  }
  async fetchStream(): Promise<HttpResponse> { throw new Error('unused'); }
}

const client = (
  http: ControlledHttp, checkEnabled: () => Promise<boolean> = async () => true,
  tokenStore: FeishuDocsTokenStore = { get: async () => activeToken(), set: async () => {} },
): FeishuDocsClient => new FeishuDocsClient({
  http, checkEnabled, tokenStore,
  kv: {
    get: async (key) => key === FEISHU_APP_ID_KEY ? 'app-id' : 'secret',
    put: async () => {}, delete: async () => {},
  },
});

const request: HttpRequest = { url: 'https://example.com/write', method: 'POST', headers: {} };
const abortError = (error: unknown): boolean => error instanceof Error && error.name === 'AbortError';

test('WebMount request: disabled tools reject before dispatch', async () => {
  const http = new ControlledHttp();
  await assert.rejects(fetchWebMountRequest(http, request, async () => false), /disabled/);
  assert.equal(http.calls.length, 0);
});

test('WebMount request: pre-cancelled signal rejects before checking access', async () => {
  const http = new ControlledHttp();
  const controller = new AbortController();
  controller.abort();
  let checks = 0;
  await assert.rejects(fetchWebMountRequest(http, request, async () => { checks++; return true; },
    controller.signal), abortError);
  assert.equal(checks, 0);
  assert.equal(http.calls.length, 0);
});

test('WebMount request: cancellation while permission lookup awaits prevents dispatch', async () => {
  const http = new ControlledHttp();
  const controller = new AbortController();
  const enabled = deferred<boolean>();
  const pending = fetchWebMountRequest(http, request, () => enabled.promise, controller.signal);
  controller.abort();
  enabled.resolve(true);
  await assert.rejects(pending, abortError);
  assert.equal(http.calls.length, 0);
});

test('WebMount request: caller signal reaches HttpClient and cancelled late response cannot succeed', async () => {
  const http = new ControlledHttp();
  const controller = new AbortController();
  const reply = deferred<HttpResponse>();
  const dispatched = deferred<void>();
  http.next = async () => { dispatched.resolve(); return reply.promise; };
  const pending = fetchWebMountRequest(http, request, async () => true, controller.signal);
  await dispatched.promise;
  assert.equal(http.calls[0].signal, controller.signal);
  controller.abort();
  reply.resolve(response({ ok: true }));
  await assert.rejects(pending, abortError);
});

test('Feishu: global and assistant access revoked rejects before reading token or refreshing', async () => {
  const http = new ControlledHttp();
  let tokenReads = 0;
  const docs = client(http, async () => false, {
    get: async () => { tokenReads++; return activeToken(); }, set: async () => {},
  });
  await assert.rejects(docs.resolveAccessToken(), /disabled/);
  assert.equal(tokenReads, 0);
  assert.equal(http.calls.length, 0);
});

test('Feishu: cancel during token load does not start token refresh', async () => {
  const http = new ControlledHttp();
  const controller = new AbortController();
  const token = deferred<WebMountOAuthToken | null>();
  const loading = deferred<void>();
  const docs = client(http, async () => true, {
    get: async () => { loading.resolve(); return token.promise; }, set: async () => {},
  });
  const pending = docs.resolveAccessToken(controller.signal);
  await loading.promise;
  controller.abort();
  token.resolve({ ...activeToken(), expiresAtMillis: 0 });
  await assert.rejects(pending, abortError);
  assert.equal(http.calls.length, 0);
});

test('Feishu: refresh request receives the same signal and persists fresh token', async () => {
  const http = new ControlledHttp();
  const controller = new AbortController();
  http.next = async () => response({ access_token: 'new-access', refresh_token: 'new-refresh', expires_in: 3600 });
  let saved: WebMountOAuthToken | null = null;
  const docs = client(http, async () => true, {
    get: async () => ({ ...activeToken(), expiresAtMillis: 0 }), set: async (token) => { saved = token; },
  });
  assert.equal(await docs.resolveAccessToken(controller.signal), 'new-access');
  assert.equal(http.calls[0].signal, controller.signal);
  assert.equal((saved as WebMountOAuthToken | null)?.accessToken, 'new-access');
});

test('Feishu append: cancelled refresh cannot persist token or dispatch document requests', async () => {
  const http = new ControlledHttp();
  const controller = new AbortController();
  const refresh = deferred<HttpResponse>();
  const dispatched = deferred<void>();
  let tokenWrites = 0;
  http.next = async () => { dispatched.resolve(); return refresh.promise; };
  const docs = client(http, async () => true, {
    get: async () => ({ ...activeToken(), expiresAtMillis: 0 }),
    set: async () => { tokenWrites++; },
  });
  const pending = docs.append('doc-id', 'new text', '', controller.signal);
  await dispatched.promise;
  controller.abort();
  refresh.resolve(response({ access_token: 'new-access', expires_in: 3600 }));
  await assert.rejects(pending, abortError);
  assert.equal(tokenWrites, 0);
  assert.equal(http.calls.length, 1);
  assert.equal(http.calls[0].request.url, 'https://open.feishu.cn/open-apis/authen/v2/oauth/token');
});

test('Feishu append: cancellation during root GET prevents the subsequent POST', async () => {
  const http = new ControlledHttp();
  const controller = new AbortController();
  const root = deferred<HttpResponse>();
  const dispatched = deferred<void>();
  http.next = async () => { dispatched.resolve(); return root.promise; };
  const pending = client(http).append('doc-id', 'new text', '', controller.signal);
  await dispatched.promise;
  controller.abort();
  root.resolve(response({ code: 0, data: { items: [{ block_id: 'root' }] } }));
  await assert.rejects(pending, abortError);
  assert.deepEqual(http.calls.map((call) => call.request.method), ['GET']);
});

test('Feishu append: revoked permission after root GET prevents the subsequent POST', async () => {
  const http = new ControlledHttp();
  let enabled = true;
  http.next = async () => {
    enabled = false;
    return response({ code: 0, data: { items: [{ block_id: 'root' }] } });
  };
  await assert.rejects(client(http, async () => enabled).append('doc-id', 'new text', ''), /disabled/);
  assert.deepEqual(http.calls.map((call) => call.request.method), ['GET']);
});

test('Feishu append: active request resolves root and writes the unchanged block payload', async () => {
  const http = new ControlledHttp();
  const controller = new AbortController();
  http.next = async (req) => req.method === 'GET'
    ? response({ code: 0, data: { items: [{ block_id: 'root' }] } })
    : response({ code: 0, data: { children: [{ block_id: 'new-block' }] } });
  const result = await client(http).append('doc-id', 'new text', '', controller.signal);
  assert.deepEqual(result['data'], { children: [{ block_id: 'new-block' }] });
  assert.deepEqual(http.calls.map((call) => call.request.method), ['GET', 'POST']);
  assert.ok(http.calls.every((call) => call.signal === controller.signal));
  assert.deepEqual(JSON.parse(http.calls[1].request.body ?? ''), {
    index: -1, children: [{ block_type: 2, text: { elements: [{ text_run: { content: 'new text' } }], style: {} } }],
  });
});
