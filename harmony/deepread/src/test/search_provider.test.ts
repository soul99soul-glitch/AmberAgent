import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { createTavilyProvider, createFallbackProviders, TavilyConfig } from '../main/ets/research/search_provider.ts';
import type { HttpClient, HttpRequest, HttpResponse } from '../main/ets/platform/http.ts';

// mock HttpClient 记录请求 + 返回可配置响应
const mockHttp = (
  responder: (req: HttpRequest) => HttpResponse,
): { http: HttpClient; calls: HttpRequest[] } => {
  const calls: HttpRequest[] = [];
  const http: HttpClient = {
    fetch: async (req: HttpRequest): Promise<HttpResponse> => {
      calls.push(req);
      return responder(req);
    },
    fetchStream: async () => { throw new Error('not used'); },
  };
  return { http, calls };
};

const ok = (body: string): HttpResponse => ({ status: 200, headers: {}, body });

// ===== createTavilyProvider: success =====

test('tavily: success → parses results into SearchHit[]', async () => {
  const body = JSON.stringify({
    results: [
      { title: 'First Result', url: 'https://a.example.com/1', content: 'snippet one' },
      { title: 'Second Result', url: 'https://b.example.com/2' },  // no content
    ],
  });
  const { http, calls } = mockHttp(() => ok(body));
  const provider = createTavilyProvider(http, { apiKey: 'tvly-test' });
  const hits = await provider.search(['quantum computing']);
  assert.equal(hits.length, 2);
  assert.equal(hits[0].title, 'First Result');
  assert.equal(hits[0].url, 'https://a.example.com/1');
  assert.equal(hits[0].snippet, 'snippet one');
  assert.equal(hits[0].source, 'tavily');
  assert.equal(hits[1].snippet, null, 'missing content → null snippet');

  // 验证请求 shape
  assert.equal(calls.length, 1);
  const req = calls[0];
  assert.equal(req.url, 'https://api.tavily.com/search');
  assert.equal(req.method, 'POST');
  assert.equal(req.headers['Content-Type'], 'application/json');
  assert.equal(req.headers['Authorization'], 'Bearer tvly-test');
  const sentBody = JSON.parse(req.body ?? '{}');
  assert.equal(sentBody.query, 'quantum computing');
  assert.equal(sentBody.max_results, 10);
  assert.equal(sentBody.search_depth, 'advanced');
  assert.equal(sentBody.include_answer, false);
});

test('tavily: respects custom baseUrl', async () => {
  const { http, calls } = mockHttp(() => ok(JSON.stringify({ results: [] })));
  const provider = createTavilyProvider(http, { apiKey: 'k', baseUrl: 'https://custom.tavily.test' });
  await provider.search(['q']);
  assert.equal(calls[0].url, 'https://custom.tavily.test/search');
});

// ===== error cases =====

test('tavily: empty query array → returns [] (no http call)', async () => {
  const { http, calls } = mockHttp(() => ok('{}'));
  const provider = createTavilyProvider(http, { apiKey: 'k' });
  const hits = await provider.search([]);
  assert.deepEqual(hits, []);
  assert.equal(calls.length, 0);
});

test('tavily: empty-string query → returns [] (no http call)', async () => {
  const { http, calls } = mockHttp(() => ok('{}'));
  const provider = createTavilyProvider(http, { apiKey: 'k' });
  const hits = await provider.search(['']);
  assert.deepEqual(hits, []);
  assert.equal(calls.length, 0);
});

test('tavily: non-200 status → throws with status + body excerpt', async () => {
  const { http } = mockHttp(() => ({ status: 401, headers: {}, body: 'Unauthorized: bad api key' }));
  const provider = createTavilyProvider(http, { apiKey: 'bad' });
  await assert.rejects(() => provider.search(['q']), /Tavily search failed: 401/);
});

test('tavily: 500 status → throws', async () => {
  const { http } = mockHttp(() => ({ status: 500, headers: {}, body: 'server error detail here' }));
  const provider = createTavilyProvider(http, { apiKey: 'k' });
  await assert.rejects(() => provider.search(['q']), /Tavily search failed: 500/);
});

test('tavily: response missing results field → returns []', async () => {
  const { http } = mockHttp(() => ok(JSON.stringify({ answer: 'irrelevant' })));
  const provider = createTavilyProvider(http, { apiKey: 'k' });
  const hits = await provider.search(['q']);
  assert.deepEqual(hits, []);
});

test('tavily: response with empty results array → returns []', async () => {
  const { http } = mockHttp(() => ok(JSON.stringify({ results: [] })));
  const provider = createTavilyProvider(http, { apiKey: 'k' });
  const hits = await provider.search(['q']);
  assert.deepEqual(hits, []);
});

test('tavily: result with null content → snippet null', async () => {
  const { http } = mockHttp(() => ok(JSON.stringify({
    results: [{ title: 't', url: 'https://x.com', content: null }],
  })));
  const provider = createTavilyProvider(http, { apiKey: 'k' });
  const hits = await provider.search(['q']);
  assert.equal(hits.length, 1);
  assert.equal(hits[0].snippet, null);
});

test('tavily: uses only first query (multi-query handled by merger)', async () => {
  const { http, calls } = mockHttp(() => ok(JSON.stringify({ results: [] })));
  const provider = createTavilyProvider(http, { apiKey: 'k' });
  await provider.search(['first query', 'second query', 'third query']);
  const sent = JSON.parse(calls[0].body ?? '{}');
  assert.equal(sent.query, 'first query');
});

// ===== provider name =====

test('tavily provider name is "tavily"', () => {
  const { http } = mockHttp(() => ok('{}'));
  const provider = createTavilyProvider(http, { apiKey: 'k' });
  assert.equal(provider.name, 'tavily');
});

// ===== fallback providers =====

test('fallback providers: returns at least one provider', () => {
  const providers = createFallbackProviders();
  assert.ok(providers.length >= 1);
  for (const p of providers) {
    assert.ok(p.name.length > 0);
    assert.equal(typeof p.search, 'function');
  }
});

test('fallback providers: jina returns empty (conceptual, no key needed)', async () => {
  const providers = createFallbackProviders();
  const jina = providers.find(p => p.name === 'jina_fallback');
  assert.ok(jina, 'jina_fallback present');
  if (jina) {
    const hits = await jina.search(['anything']);
    assert.deepEqual(hits, []);
  }
});

test('fallback providers: search does not throw on empty query', async () => {
  const providers = createFallbackProviders();
  for (const p of providers) {
    const hits = await p.search([]);
    assert.ok(Array.isArray(hits));
  }
});

test('tavily: 多 query 逐个执行并按 URL 去重;部分失败只丢该 query', async () => {
  let call = 0;
  const bodies: string[] = [
    JSON.stringify({ results: [{ title: 'A', url: 'https://x.test/1' }, { title: 'A2', url: 'https://x.test/1' }] }),
    JSON.stringify({ results: [{ title: 'B', url: 'https://x.test/2' }] }),
    JSON.stringify({ results: [{ title: 'C', url: 'https://x.test/3' }] }),
  ];
  const { http } = mockHttp((): { status: number; headers: Record<string, string>; body: string } => {
    call++;
    if (call === 2) return { status: 500, headers: {}, body: 'boom' };
    return { status: 200, headers: {}, body: bodies[call - 1] ?? '{}' };
  });
  const provider = createTavilyProvider(http, { apiKey: 'k' });
  const hits = await provider.search(['q1', 'q2', 'q3']);
  assert.equal(hits.length, 2, 'q1 两去重为 1 + q3 补 1(q2 500 跳过)');
  assert.equal(call, 3);
});
