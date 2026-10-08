import assert from 'node:assert/strict';
import test from 'node:test';
import fs from 'node:fs';
import ts from 'typescript';
import { parseMarkdown, parseInline } from '../main/ets/chat/markdown_blocks.ts';

interface Renderer {
  renderDeepReadSynthesisBody(article: unknown): string;
  DEEPREAD_SYNTHESIS_CSS: string;
}

const platformRoot = new URL('../../../entry/src/main/ets/platform_impl/', import.meta.url);
const loadEts = (name: string, imports: Record<string, unknown>): Record<string, unknown> => {
  const compiled = ts.transpileModule(fs.readFileSync(new URL(name, platformRoot), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const module = { exports: {} };
  new Function('require', 'exports', 'module', compiled)((id: string) => {
    assert.ok(id in imports, `unexpected import ${id}`);
    return imports[id];
  }, module.exports, module);
  return module.exports;
};

const markdown = loadEts('DeepReadMarkdownHtml.ets', { '@amber/chat-domain': { parseMarkdown, parseInline } });
const renderer = loadEts('DeepReadSynthesisHtml.ets', { './DeepReadMarkdownHtml.ets': markdown }) as unknown as Renderer;

const base = {
  shape: 'template_synthesis', template: 'deepread_brief', title: '标题', lede: '**导语**',
  sources: [
    { id: 2, title: '资料乙', url: 'https://example.com/b', site: '站点乙' },
    { id: 7, title: '资料七', url: null, site: '站点七' },
  ],
};

test('brief renders source-backed title, Markdown points and independent uncertainty', () => {
  const html = renderer.renderDeepReadSynthesisBody({ ...base, brief: {
    points: ['第一条 **关键点**', '第二条'], background: '背景第一段。\n\n背景第二段。',
    impact: '影响', uncertain: ['尚待核实'],
  } });
  assert.match(html, /速览简报/);
  assert.match(html, /<strong>导语<\/strong>/);
  assert.match(html, /<ol class="numbered"><li>第一条 <strong>关键点<\/strong><\/li>/);
  assert.match(html, /背景第一段。<\/p><p>背景第二段。/);
  assert.match(html, /待核实/);
  assert.match(html, /id="dr-source-7"/);
  assert.match(html, /href="https:\/\/example.com\/b"/);
});

test('Q&A citations use actual source IDs, deduplicate and remain in the final answer paragraph', () => {
  const html = renderer.renderDeepReadSynthesisBody({ ...base, template: 'deepread_qa', qa: [
    { question: '为什么？', answer: '先介绍。\n\n再解释。', sources: [7, 7, 2, 1, 99, -1] },
    { question: '还有呢？', answer: '- 要点甲\n- 要点乙', sources: [2] },
  ] });
  assert.match(html, /Q1/);
  assert.match(html, /Q2/);
  assert.match(html, /再解释。<sup class="cite"><a href="#dr-source-7"[^>]*>\[7\]<\/a><a href="#dr-source-2"[^>]*>\[2\]<\/a><\/sup><\/p>/);
  assert.doesNotMatch(html, /href="#dr-source-(1|99|-1)"/);
  assert.equal((html.match(/href="#dr-source-7"/g) ?? []).length, 1);
  assert.match(html, /<\/ul><p><sup class="cite">/);
});

test('debate and timeline preserve camps, attributed quotations and turning explanations', () => {
  const debate = renderer.renderDeepReadSynthesisBody({ ...base, template: 'deepread_debate', debate: {
    dispute: '是否采用？', camps: [
      { stance: 'pro', label: '支持者', holders: ['机构甲', '专家乙'], argument: '值得采用。', quote: '原话', quoteBy: '专家乙', sources: [2] },
      { stance: 'con', label: '反对者', holders: [], argument: '谨慎采用。', quote: '', quoteBy: '', sources: [7] },
    ], takeaway: '综合权衡。',
  } });
  assert.match(debate, /class="camp pro"/);
  assert.match(debate, /class="camp con"/);
  assert.match(debate, /机构甲、专家乙/);
  assert.match(debate, /“原话”<\/p><small>—— 专家乙<\/small>/);
  assert.match(debate, /你可以怎么看/);
  const timeline = renderer.renderDeepReadSynthesisBody({ ...base, template: 'deepread_timeline', timeline: {
    events: [{ date: '2026-10-06', event: '事件发生', turning: true, sources: [7] }],
    turns: [{ date: '2026-10-06', why: '**转折原因**' }],
  } });
  assert.match(timeline, /<li class="turn"><span class="date">2026-10-06/);
  assert.match(timeline, /事件发生<sup class="cite">/);
  assert.match(timeline, /转折点/);
  assert.match(timeline, /<strong>转折原因<\/strong>/);
});

test('review maps nonsequential source sites and escapes model text and hostile source URLs', () => {
  const html = renderer.renderDeepReadSynthesisBody({ ...base, template: 'deepread_review', title: '<script>alert(1)</script>',
    sources: [...base.sources,
      { id: 7, title: '重复来源', url: 'https://duplicate.example', site: '重复站点' },
      { id: 8, title: '<img src=x onerror=alert(1)>', url: 'javascript:alert(1)', site: '不安全地址' },
      { id: -2, title: '无效编号', url: null, site: '无效来源' }],
    review: {
      verdict: '一句结论', consensus: ['共同意见'],
      splits: [{ topic: '续航', views: [{ source: 7, view: '更持久' }, { source: 2, view: '表现一般' }] }],
      specs: [{ name: '<功率>', value: '100W & 200W' }],
      scores: [{ source: 7, score: '4/5', note: '表现良好' }], conclusion: '适合买。',
    },
  });
  assert.match(html, /分歧 · 续航/);
  assert.match(html, /<b>站点七<\/b>/);
  assert.match(html, /<b>站点乙<\/b>/);
  assert.match(html, /<th scope="row">&lt;功率&gt;<\/th><td>100W &amp; 200W<\/td>/);
  assert.match(html, /class="score">4\/5/);
  assert.match(html, /买不买/);
  assert.match(html, /&lt;script&gt;alert\(1\)&lt;\/script&gt;/);
  assert.doesNotMatch(html, /<script>|href="javascript:|<img src=x|重复来源|无效来源/);
  assert.equal((html.match(/id="dr-source-7"/g) ?? []).length, 1);
});
