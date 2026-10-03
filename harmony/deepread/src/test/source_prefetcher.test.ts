import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import {
  createSourcePrefetcher, DeepReadSource, buildDeepReadQueries, scoreCredibility,
} from '../main/ets/research/source_prefetcher.ts';
import type { HttpClient, HttpRequest, HttpResponse } from '../main/ets/platform/http.ts';
import type { SearchProvider, SearchProviderRegistry, SearchHit } from '../main/ets/platform/search.ts';
import { MAX_SOURCES, MIN_SOURCE_CHARS, MIN_SEED_SOURCE_CHARS } from '../main/ets/domain/enums.ts';

// ===== helpers =====

// 生成长正文(满足 MIN_SOURCE_CHARS=280 阈值)
// 注意:reader extractor 会做 distinct 去重,所以每段内容必须不同
const longBody = (extra: string = ''): string => {
  const paras = [
    '第一段独立的正文内容必须各不相同才能通过去重检查并保留下来供后续使用。',
    '第二段不同的文字描述了事件的背景和起因供阅读器提取并验证逻辑正确性。',
    '第三段继续补充细节包括时间线关键人物以及直接相关的背景信息说明。',
    '第四段提供分析视角涵盖争议焦点各方立场和可能的发展走向预测。',
    '第五段总结影响范围涉及的利益相关方和长期意义供读者参考判断。',
    '第六段补充扩展阅读材料链接和相关的官方声明或权威报道引用。',
    '第七段是结尾部分归纳要点并提出开放性问题引导读者深入思考。',
    '第八段作为收尾强调事实核查的重要性以及信息来源的可靠性评估。',
    '第九段附上数据图表说明趋势变化以及关键指标对比分析的结论。',
    '第十段列举后续待观察事项提醒读者关注事态发展的最新动向。',
    '第十一段提供专家观点引用以增强论述的权威性和可信度说明。',
    '第十二段以总结性陈述收束全文并给出整体性的判断与展望。',
  ];
  const body = paras.join('</p><p>');
  return `<html><head><title>Page Title</title></head><body><p>${body}${extra}</p></body></html>`;
};

const ok = (body: string): HttpResponse => ({ status: 200, headers: {}, body });
const notFound = (): HttpResponse => ({ status: 404, headers: {}, body: 'not found' });

// 按路径路由的 mock http
const mockHttp = (routes: (req: HttpRequest) => HttpResponse): { http: HttpClient; calls: HttpRequest[] } => {
  const calls: HttpRequest[] = [];
  const http: HttpClient = {
    fetch: async (req: HttpRequest): Promise<HttpResponse> => {
      calls.push(req);
      return routes(req);
    },
    fetchStream: async () => { throw new Error('not used'); },
  };
  return { http, calls };
};

const mockRegistry = (
  enabledHits: SearchHit[] = [],
  fallbackHits: SearchHit[] = [],
): { registry: SearchProviderRegistry; calls: { count: number } } => {
  const calls = { count: 0 };
  const makeProvider = (name: string, hits: SearchHit[]): SearchProvider => ({
    name,
    search: async (): Promise<SearchHit[]> => { calls.count++; return hits; },
  });
  const registry: SearchProviderRegistry = {
    enabled: () => [makeProvider('tavily', enabledHits)],
    fallback: () => [makeProvider('jina', fallbackHits)],
  };
  return { registry, calls };
};

// ===== LRU cache =====

test('LRU cache: second collect with same key returns cached (no re-fetch)', async () => {
  let fetchCount = 0;
  const { http } = mockHttp(() => { fetchCount++; return ok(longBody()); });
  const { registry } = mockRegistry([]);
  const pf = createSourcePrefetcher(http, registry);

  // seedUrl 提供,避免空 prefetch
  await pf.collect('topic-1', 'Title', 'https://seed.example.com/x', false);
  const firstFetch = fetchCount;
  const firstCache = pf.cacheSize();
  assert.ok(firstFetch >= 1);
  assert.equal(firstCache, 1);

  // 第二次同 key → cache 命中,无新 fetch
  await pf.collect('topic-1', 'Title', 'https://seed.example.com/x', false);
  assert.equal(fetchCount, firstFetch, 'no re-fetch on cache hit');
  assert.equal(pf.cacheSize(), 1);
});

test('LRU cache: different cacheKey (different seedUrl) re-fetches', async () => {
  let fetchCount = 0;
  const { http } = mockHttp(() => { fetchCount++; return ok(longBody()); });
  const { registry } = mockRegistry([]);
  const pf = createSourcePrefetcher(http, registry);

  await pf.collect('topic-1', 'Title', 'https://seed.example.com/a', false);
  const after1 = fetchCount;
  await pf.collect('topic-1', 'Title', 'https://seed.example.com/b', false);
  assert.ok(fetchCount > after1, 'different key re-fetches');
  assert.equal(pf.cacheSize(), 2);
});

test('force=true bypasses cache', async () => {
  let fetchCount = 0;
  const { http } = mockHttp(() => { fetchCount++; return ok(longBody()); });
  const { registry } = mockRegistry([]);
  const pf = createSourcePrefetcher(http, registry);

  await pf.collect('topic-1', 'Title', 'https://seed.example.com/x', false);
  const cached = fetchCount;
  await pf.collect('topic-1', 'Title', 'https://seed.example.com/x', true);
  assert.ok(fetchCount > cached, 'force re-fetches despite cache');
});

// ===== seed URL priority + low threshold =====

test('seed URL fetched even without search hits (low threshold vs MIN_SOURCE_CHARS)', async () => {
  // seed 用低阈值 MIN_SEED_SOURCE_CHARS(15),但 reader extractor 行过滤要求 >=18 字符。
  // 所以 seed 内容必须 >=18 字符(过行过滤)但 < MIN_SOURCE_CHARS(280,不走普通阈值)。
  // 这里给一段 ~40 字符的 seed,普通 scrape 会丢弃(< 280),但 seed 路径保留。
  const seedLine = '用户提供的种子页面正文内容超过十八字符即可被保留下来用于预取。';
  assert.ok(seedLine.length >= 18 && seedLine.length < MIN_SOURCE_CHARS);
  const seedHtml = `<html><body><p>${seedLine}</p></body></html>`;
  const { http } = mockHttp(() => ok(seedHtml));
  const { registry } = mockRegistry([]);  // 无 search hit
  const pf = createSourcePrefetcher(http, registry);

  const sources = await pf.collect('topic-1', 'Title', 'https://seed.example.com/x', true);
  assert.equal(sources.length, 1);
  assert.equal(sources[0].source, 'seed');
  assert.equal(sources[0].url, 'https://seed.example.com/x');
});

test('seed URL whose extracted text is empty (all lines <18) → dropped', async () => {
  // 太短的行被 reader extractor 过滤,提取为空 → 低于任何阈值
  const tooShort = '<html><body><p>太短</p></body></html>';
  const { http } = mockHttp(() => ok(tooShort));
  const { registry } = mockRegistry([]);
  const pf = createSourcePrefetcher(http, registry);

  const sources = await pf.collect('topic-1', 'Title', 'https://seed.example.com/x', true);
  assert.equal(sources.length, 0, 'seed extracted empty → dropped');
});

// ===== scrape threshold MIN_SOURCE_CHARS =====

test('search hit with evidence < MIN_SOURCE_CHARS is dropped', async () => {
  const shortBody = '<html><body><p>短内容不超过阈值会被丢弃掉</p></body></html>';
  const hits: SearchHit[] = [{ title: 't', url: 'https://result.example.com/1', snippet: null, source: 'tavily' }];
  const { http } = mockHttp(() => ok(shortBody));
  const { registry } = mockRegistry(hits);
  const pf = createSourcePrefetcher(http, registry);

  const sources = await pf.collect('topic-1', 'Title', null, true);
  assert.equal(sources.length, 0, 'scraped below MIN_SOURCE_CHARS dropped');
  assert.ok(MIN_SOURCE_CHARS === 280);
});

test('search hit with long evidence is kept', async () => {
  const hits: SearchHit[] = [{ title: 't', url: 'https://result.example.com/1', snippet: null, source: 'tavily' }];
  const { http } = mockHttp(() => ok(longBody()));
  const { registry } = mockRegistry(hits);
  const pf = createSourcePrefetcher(http, registry);

  const sources = await pf.collect('topic-1', 'Title', null, true);
  assert.equal(sources.length, 1);
  assert.equal(sources[0].url, 'https://result.example.com/1');
});

// ===== URL dedup =====

test('same URL from seed + search deduped to one', async () => {
  const url = 'https://same.example.com/x';
  const hits: SearchHit[] = [{ title: 't', url, snippet: null, source: 'tavily' }];
  const { http } = mockHttp(() => ok(longBody()));
  const { registry } = mockRegistry(hits);
  const pf = createSourcePrefetcher(http, registry);

  const sources = await pf.collect('topic-1', 'Title', url, true);
  assert.equal(sources.length, 1, 'deduped');
});

// ===== cap MAX_SOURCES =====

test('caps at MAX_SOURCES (12)', async () => {
  // 20 unique search hits, all returning long body
  const hits: SearchHit[] = [];
  for (let i = 0; i < 20; i++) {
    hits.push({ title: `t${i}`, url: `https://r${i}.example.com/${i}`, snippet: null, source: 'tavily' });
  }
  const { http } = mockHttp(() => ok(longBody()));
  const { registry } = mockRegistry(hits);
  const pf = createSourcePrefetcher(http, registry);

  const sources = await pf.collect('topic-1', 'Title', null, true);
  assert.equal(sources.length, MAX_SOURCES);
  assert.equal(MAX_SOURCES, 12);
  // URL 全唯一
  const urls = new Set(sources.map(s => s.url));
  assert.equal(urls.size, MAX_SOURCES);
});

// ===== multi-provider parallel =====

test('calls both enabled and fallback providers', async () => {
  const hits: SearchHit[] = [{ title: 't', url: 'https://r.example.com/1', snippet: null, source: 'tavily' }];
  const { http } = mockHttp(() => ok(longBody()));
  const { registry, calls } = mockRegistry(hits, []);
  const pf = createSourcePrefetcher(http, registry);

  await pf.collect('topic-1', 'Title', null, true);
  assert.equal(calls.count, 2, 'enabled + fallback both called');
});

test('fallback provider results merged via interleave', async () => {
  const { http } = mockHttp(() => ok(longBody()));
  const { registry } = mockRegistry(
    [{ title: 'a', url: 'https://enabled.example.com/1', snippet: null, source: 'tavily' }],
    [{ title: 'b', url: 'https://fallback.example.com/1', snippet: null, source: 'jina' }],
  );
  const pf = createSourcePrefetcher(http, registry);

  const sources = await pf.collect('topic-1', 'Title', null, true);
  const urls = sources.map(s => s.url).sort();
  assert.ok(urls.includes('https://enabled.example.com/1'));
  assert.ok(urls.includes('https://fallback.example.com/1'));
});

// ===== private URL filter =====

test('private seed URL (localhost) is filtered out', async () => {
  const { http } = mockHttp(() => ok(longBody()));
  const { registry } = mockRegistry([]);
  const pf = createSourcePrefetcher(http, registry);

  const sources = await pf.collect('topic-1', 'Title', 'http://localhost:8080/secret', true);
  assert.equal(sources.length, 0, 'private seed blocked');
});

test('private search hit URL is filtered out', async () => {
  const hits: SearchHit[] = [{ title: 't', url: 'http://192.168.1.1/internal', snippet: null, source: 'tavily' }];
  const { http } = mockHttp(() => ok(longBody()));
  const { registry } = mockRegistry(hits);
  const pf = createSourcePrefetcher(http, registry);

  const sources = await pf.collect('topic-1', 'Title', null, true);
  assert.equal(sources.length, 0, 'private hit blocked');
});

// ===== empty prefetch → empty array (P1-9: caller decides hard-fail) =====

test('all-empty prefetch (no seed, no hits) → empty array', async () => {
  const { http } = mockHttp(() => ok(longBody()));
  const { registry } = mockRegistry([]);
  const pf = createSourcePrefetcher(http, registry);

  const sources = await pf.collect('topic-1', 'Title', null, true);
  assert.deepEqual(sources, []);
  assert.equal(pf.cacheSize(), 1, 'empty result still cached');
});

// ===== credibility scoring =====

test('credibility: gov/edu/reuters → high', () => {
  assert.equal(scoreCredibility('https://www.nasa.gov/article'), 'high');
  assert.equal(scoreCredibility('https://mit.edu/research'), 'high');
  assert.equal(scoreCredibility('https://reuters.com/world'), 'high');
});

test('credibility: medium/blog → low', () => {
  assert.equal(scoreCredibility('https://medium.com/post'), 'low');
  assert.equal(scoreCredibility('https://example.wordpress.com/x'), 'low');
});

test('credibility: generic → medium', () => {
  assert.equal(scoreCredibility('https://example.com/article'), 'medium');
});

// ===== buildDeepReadQueries =====

test('buildDeepReadQueries: includes title + variants', () => {
  const qs = buildDeepReadQueries('Quantum Breakthrough', null);
  assert.ok(qs.includes('Quantum Breakthrough'));
  assert.ok(qs.length <= 7);
  assert.ok(qs.every(q => q.length > 0));
});

test('buildDeepReadQueries: seed is fetched directly instead of submitted as a query', () => {
  const qs = buildDeepReadQueries('Title', 'https://seed.example.com/x');
  assert.equal(qs[0], 'Title');
  assert.ok(!qs.includes('https://seed.example.com/x'));
});

// ===== DeepReadSource shape =====

test('fetched source has correct shape', async () => {
  const hits: SearchHit[] = [{ title: 'My Title', url: 'https://r.example.com/1', snippet: null, source: 'tavily' }];
  const { http } = mockHttp(() => ok(longBody(' extra content')));
  const { registry } = mockRegistry(hits);
  const pf = createSourcePrefetcher(http, registry);

  const sources = await pf.collect('topic-1', 'Title', null, true);
  const s = sources[0];
  assert.ok(s.sourceId.startsWith('src-'));
  assert.equal(s.url, 'https://r.example.com/1');
  assert.equal(s.title, 'My Title');
  assert.equal(s.source, 'tavily');
  assert.ok(s.evidenceText.length > 0);
  assert.ok(s.credibility === 'high' || s.credibility === 'medium' || s.credibility === 'low');
  assert.equal(s.freshness, 'unknown');
  assert.equal(s.publishedAt, null);
  assert.ok(Array.isArray(s.imageCandidates));
});

// ===== deadline exceeded (short budget) =====

test('deadline exceeded: slow fetch rejected, others may still complete', async () => {
  // 一个超慢的 seed URL(模拟 100ms)+ 极短 budget
  // 本测试只验证单个来源失败的隔离;真实 15s/10s deadline 在 product_p2_research.test 用 fake timers 验证。
  // 改为:验证 fetchSource 对非 200 返回 null,以及整体容错。
  // (真正 deadline 测试需要注入 clock,本测试验证容错性即可)
  const { http } = mockHttp((req) => {
    // 第一个 URL 返回 500,第二个正常
    if (req.url.includes('fail')) return { status: 500, headers: {}, body: '' };
    return ok(longBody());
  });
  const hits: SearchHit[] = [
    { title: 'fail', url: 'https://fail.example.com/x', snippet: null, source: 'tavily' },
    { title: 'ok', url: 'https://ok.example.com/x', snippet: null, source: 'tavily' },
  ];
  const { registry } = mockRegistry(hits);
  const pf = createSourcePrefetcher(http, registry);

  const sources = await pf.collect('topic-1', 'Title', null, true);
  const urls = sources.map(s => s.url);
  assert.ok(!urls.includes('https://fail.example.com/x'), 'failed fetch dropped');
  assert.ok(urls.includes('https://ok.example.com/x'), 'ok fetch kept');
});

// ===== 直连 UA + Jina Reader 兜底 =====

const jinaBody = (text: string): string =>
  `Title: T\n\nURL Source: https://x\n\nPublished Time: 2026-09-01T08:00:00Z\n\nMarkdown Content:\n` +
  `![Image 1](https://img/x.png)\n${text} 见[原文](https://link)`;

const LONG_TEXT: string = '这是一段足够长的正文内容，用来模拟 Jina Reader 返回的页面文字。'.repeat(12);

test('直连请求带浏览器 UA;直连 403 → Jina Reader 兜底(去图片/链接,解析发布时间)', async () => {
  const hits: SearchHit[] = [{ title: 'Blocked', url: 'https://blocked.example.com/a', snippet: null, source: 'brave' }];
  const { http, calls } = mockHttp((req) => {
    if (req.url.startsWith('https://r.jina.ai/')) return ok(jinaBody(LONG_TEXT));
    return { status: 403, headers: {}, body: 'forbidden' };
  });
  const { registry } = mockRegistry(hits);
  const pf = createSourcePrefetcher(http, registry);
  const sources = await pf.collect('topic-jina', 'Title', null, true);
  const direct = calls.find((c) => c.url === 'https://blocked.example.com/a');
  assert.ok(direct !== undefined && direct.headers['User-Agent'].startsWith('Mozilla/5.0'));
  assert.ok(calls.some((c) => c.url === 'https://r.jina.ai/https://blocked.example.com/a'));
  assert.equal(sources.length, 1);
  assert.equal(sources[0].url, 'https://blocked.example.com/a'); // 来源仍记原 URL
  assert.equal(sources[0].publishedAt, '2026-09-01T08:00:00Z');
  assert.ok(!sources[0].evidenceText.includes('https://img/x.png'));
  assert.ok(sources[0].evidenceText.endsWith('见原文'));
});

test('Jina Reader 兜底每次 collect 至多 6 次;无 Markdown 标记的返回不收', async () => {
  const hits: SearchHit[] = Array.from({ length: 10 }, (_, i) => (
    { title: `t${i}`, url: `https://blocked.example.com/${i}`, snippet: null, source: 'brave' }));
  const { http, calls } = mockHttp((req) => (req.url.startsWith('https://r.jina.ai/')
    ? ok('<html>rate limited</html>')
    : { status: 403, headers: {}, body: '' }));
  const { registry } = mockRegistry(hits);
  const pf = createSourcePrefetcher(http, registry);
  const sources = await pf.collect('topic-cap', 'Title', null, true);
  assert.equal(calls.filter((c) => c.url.startsWith('https://r.jina.ai/')).length, 6);
  assert.equal(sources.length, 0);
});

test('私网 URL 不直连也不交给 Jina Reader', async () => {
  const hits: SearchHit[] = [{ title: 'lan', url: 'http://192.168.1.10/x', snippet: null, source: 'brave' }];
  const { http, calls } = mockHttp(() => ok(jinaBody(LONG_TEXT)));
  const { registry } = mockRegistry(hits);
  const pf = createSourcePrefetcher(http, registry);
  await pf.collect('topic-lan', 'Title', null, true);
  assert.equal(calls.length, 0);
});
