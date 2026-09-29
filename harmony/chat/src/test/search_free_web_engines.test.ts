// search_free_web_engines.test.ts — 免费网页引擎(Brave/360/夸克)解析 + 验证码熔断
// 夹具按 2026-09 真实页面结构裁剪(360/夸克实抓;Brave 按 deedy5/ddgs 解析规则)
import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import type { HttpClient, HttpRequest, HttpResponse } from '@amber/deepread-domain';
import { initSearchSdk } from '../main/ets/search/search_service.ts';
import {
  braveWebSearch, so360Search, quarkSearch, parseBraveHtml, parseSo360Html, parseQuarkHtml,
  runFreeEngine, resetFreeEngineBreakers, freeEngineCoolingDown, looksTechnicalQuery,
} from '../main/ets/search/free_web_engines.ts';

const mockHttpSeq = (responses: HttpResponse[], captured: HttpRequest[]): HttpClient => ({
  fetch: (req: HttpRequest): Promise<HttpResponse> => {
    captured.push(req);
    const r: HttpResponse = responses.length > 0
      ? responses.shift() as HttpResponse
      : { status: 500, headers: {}, body: '' };
    return Promise.resolve(r);
  },
  fetchStream: (): Promise<HttpResponse> => Promise.reject(new Error('not used')),
});

beforeEach(() => resetFreeEngineBreakers());

const BRAVE_PAGE: string = `<html><body><div id="results">
<div class="snippet svelte-1" data-type="web" data-pos="1">
  <a href="https://zhuanlan.zhihu.com/p/1" class="heading-serpresult svelte-2">
    <div class="site-wrapper"><div class="sitename-container">知乎专栏</div></div>
    <div class="title search-snippet-title svelte-3">量子计算的现状</div>
  </a>
  <div class="snippet-content"><div class="content desktop-default-regular">量子计算正在从实验室走向实用。</div></div>
</div>
<div class="snippet" data-type="ad"><a href="https://ads.example.com"><div class="title">Ad</div></a></div>
<div class="snippet" data-type="web">
  <a href="https://example.org/q"><div class="title">Second</div></a>
</div>
</div></body></html>`;

test('brave:data-type=web 块 + a>div.title 链接 + 末个 title 块为标题 + snippet/content 摘要', () => {
  const items = parseBraveHtml(BRAVE_PAGE, 10);
  assert.equal(items.length, 2); // ad 块不计
  assert.equal(items[0].title, '量子计算的现状');
  assert.equal(items[0].url, 'https://zhuanlan.zhihu.com/p/1');
  assert.equal(items[0].text, '量子计算正在从实验室走向实用。');
  assert.equal(items[1].url, 'https://example.org/q');
  assert.equal(items[1].text, '');
});

test('brave:请求形态;429 → HTTP 错误', async () => {
  const calls: HttpRequest[] = [];
  initSearchSdk(mockHttpSeq([{ status: 200, headers: {}, body: BRAVE_PAGE }], calls),
    undefined, (): string => 'zh-CN,zh');
  await braveWebSearch('量子 计算', { resultSize: 5 });
  assert.equal(calls[0].url, 'https://search.brave.com/search?q=%E9%87%8F%E5%AD%90+%E8%AE%A1%E7%AE%97&source=web');
  assert.equal(calls[0].headers['Accept-Language'], 'zh-CN,zh');
  initSearchSdk(mockHttpSeq([{ status: 429, headers: {}, body: '' }], []));
  await assert.rejects(braveWebSearch('q', { resultSize: 5 }), /HTTP 429/);
});

const SO360_PAGE: string = `<html><body><ul class="result">
<li class="res-list" data-lazyload="1">
  <h3 class="res-title"><a href="https://www.so.com/link?m=abc" data-mdurl="https://www.ncsti.gov.cn/a.html"><em>量子计算</em>的发展</a></h3>
  <p class="res-desc">国际学界的<em>主流</em>观点</p>
</li>
<li class="res-list">
  <h3 class="res-title"><a href="https://wenku.so.com/d/1">文库条目</a></h3>
  <div class="res-rich">35页 摘要</div>
</li>
<li class="res-list">
  <h3 class="g-title"><a href="http://news.so.com/ns?q=x">最新相关消息</a></h3>
</li>
</ul></body></html>`;

test('360:so.com/link 用 data-mdurl 真实地址 + 360 自家聚合页过滤 + 汉字间空白收拢', () => {
  const items = parseSo360Html(SO360_PAGE, 10);
  assert.equal(items.length, 2);
  assert.equal(items[0].url, 'https://www.ncsti.gov.cn/a.html');
  assert.equal(items[0].title, '量子计算的发展');
  assert.equal(items[0].text, '国际学界的主流观点');
  assert.equal(items[1].url, 'https://wenku.so.com/d/1');
  assert.equal(items[1].text, '35页摘要');
});

test('360:无结果 + 验证页 → verification', async () => {
  initSearchSdk(mockHttpSeq([{ status: 200, headers: {}, body: '<html>qcaptcha 安全验证</html>' }], []));
  await assert.rejects(so360Search('q', { resultSize: 5 }), /verification/);
});

const quarkBlob = (id: string, initialData: object): string =>
  `<script type="application/json" id="s-data-${id}" data-used-by="hydrate">${
    JSON.stringify({ data: { initialData } })}</script>`;

const QUARK_PAGE: string = '<html><body>' +
  quarkBlob('ss_text_1_4', {
    titleProps: { content: '全球<em>量子计算</em>研究' },
    summaryProps: { content: '三步走<em>路线</em>' },
    sourceProps: { dest_url: 'https://www.ncsti.gov.cn/b.html' },
  }) +
  quarkBlob('ss_kv_1_6', { title: 'CSDN 文章', text: '文章浏览阅读401次', url: 'https://blog.csdn.net/x/1' }) +
  quarkBlob('nature_result_1_2', { title: '视频', desc: 'd', url: 'https://page.sm.cn/blm/video' }) +
  quarkBlob('text_recommend', { title: '相关搜索', displayList: [] }) +
  '<script type="application/json" id="s-data-bad">{not json</script>' +
  '</body></html>';

test('夸克:hydrate JSON 两类卡片 + 站内视频页过滤 + 坏 JSON 跳过', async () => {
  const items = parseQuarkHtml(QUARK_PAGE, 10);
  assert.equal(items.length, 2);
  assert.equal(items[0].title, '全球量子计算研究');
  assert.equal(items[0].url, 'https://www.ncsti.gov.cn/b.html');
  assert.equal(items[0].text, '三步走路线');
  assert.equal(items[1].url, 'https://blog.csdn.net/x/1');
  assert.equal(items[1].text, '文章浏览阅读401次');
  const calls: HttpRequest[] = [];
  initSearchSdk(mockHttpSeq([{ status: 200, headers: {}, body: QUARK_PAGE }], calls));
  const r = await quarkSearch('q', { resultSize: 1 });
  assert.equal(r.items.length, 1);
  assert.ok(calls[0].url.startsWith('https://quark.sm.cn/s?q=q'));
});

test('熔断:验证页/限流后该引擎 10 分钟内直接跳过,不再发请求;普通错误不熔断', async () => {
  const calls: HttpRequest[] = [];
  initSearchSdk(mockHttpSeq([
    { status: 429, headers: {}, body: '' },
    { status: 500, headers: {}, body: '' },
  ], calls));
  await assert.rejects(runFreeEngine('brave', 'q', { resultSize: 5 }), /HTTP 429/);
  assert.ok(freeEngineCoolingDown('brave'));
  await assert.rejects(runFreeEngine('brave', 'q', { resultSize: 5 }), /cooling down/);
  assert.equal(calls.length, 1); // 熔断期间不发请求
  // 500 不是验证页 → 不熔断
  await assert.rejects(runFreeEngine('so360', 'q', { resultSize: 5 }), /HTTP 500/);
  assert.ok(!freeEngineCoolingDown('so360'));
});

test('looksTechnicalQuery:技术词命中', () => {
  assert.ok(looksTechnicalQuery('Rust 异步运行时'));
  assert.ok(looksTechnicalQuery('GitHub 开源项目'));
  assert.ok(!looksTechnicalQuery('日本央行加息'));
});
