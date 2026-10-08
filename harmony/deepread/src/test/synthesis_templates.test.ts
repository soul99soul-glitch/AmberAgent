import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { DEEPREAD_SYNTHESIS_TEMPLATES, parseSynthesisArticle, parseSynthesisPick, synthesisArticleMarkdown } from '../main/ets/domain/synthesis_templates.ts';
import { makeEmptyDeepReadOutput } from '../main/ets/domain/models.ts';
import type { DeepReadOutput, DeepReadTemplateSnapshot } from '../main/ets/domain/models.ts';
import type { RunManagerDeps } from '../main/ets/agent/run_manager.ts';
import { run, runSection } from '../main/ets/agent/run_manager.ts';
import { isComplete, sectionsReady, hasReadableArticle } from '../main/ets/domain/helpers.ts';
import { deepReadProgressSnapshot } from '../main/ets/platform/deep_read_progress.ts';
import { makeAssistantMessage, toText } from '../main/ets/agent/message.ts';
import type { DeepReadSource } from '../main/ets/research/source_prefetcher.ts';
import { markdownToPlainText } from '../../../chat/src/main/ets/chat/message_copy.ts';
import { deepReadToMarkdown, deepReadToText } from '../main/ets/domain/export.ts';
import { parseInline } from '../../../chat/src/main/ets/chat/markdown_blocks.ts';

const source: DeepReadSource = { sourceId: 'web:1', title: '真实报道', url: 'https://news.test/a', source: 'search', evidenceText: '真实新闻证据，来源记载了详细时间、事实和立场。'.repeat(15), credibility: 'medium', freshness: 'unknown', publishedAt: null, imageCandidates: [] };
const fixtures: Record<string, object> = {
  deepread_brief: { points: ['要点一', '要点二'], background: '背景', impact: '影响', uncertain: ['待核实'] },
  deepread_qa: { questions: [{ q: '为何变化？', a: '来源解释了原因。', sources: [1, 999, '1', 1.2] }] },
  deepread_debate: { dispute: '如何权衡？', camps: [{ stance: 'pro', label: '支持方', holders: ['机构'], argument: '支持的理由', quote: '公开原话', quote_by: '专家', sources: [1] }, { stance: 'unknown', label: '谨慎方', holders: [], argument: '反对的理由', sources: [999] }], takeaway: '权衡风险' },
  deepread_timeline: { events: [1, 2, 3].map(id => ({ date: `2026-10-0${id}`, event: `事件${id}`, turning: id === 2, sources: [1, 999] })), turns: [{ date: '2026-10-02', why: '关键转折' }] },
  deepread_review: { verdict: '值得关注', consensus: ['续航优秀'], splits: [{ topic: '性能', views: [{ source: 1, view: '良好' }, { source: 999, view: '无效' }] }], specs: [{ name: '容量', value: '128GB' }], scores: [{ source: 1, score: '4/5', note: '推荐' }, { source: 999, score: '99' }], conclusion: '按需求选择' },
};
const reply = (id: string): string => JSON.stringify({ title: '结构化新标题', lede: '来源提供的导语。', ...fixtures[id] });
const snapshot = (id: string): DeepReadTemplateSnapshot => ({ id, name: id, kind: 'synthesis', html: null, capturedAt: 1 });

for (const template of DEEPREAD_SYNTHESIS_TEMPLATES.slice(1)) {
  test(`${template.id}: iOS shape parses, serializes and exports without invented magazine analysis`, () => {
    const article = parseSynthesisArticle(reply(template.id), template, '话题', [source])!;
    assert.equal(article.shape, 'template_synthesis');
    assert.equal(article.template, template.id);
    assert.equal(article.sources[0].site, 'news.test');
    assert.deepEqual(JSON.parse(JSON.stringify(article)), article);
    const output = { ...makeEmptyDeepReadOutput(), templateArticle: article, generationComplete: true, generationPhase: 'COMPLETE' as const };
    assert.equal(isComplete(output), true);
    assert.equal(sectionsReady(output), true);
    assert.equal(hasReadableArticle(output), true);
    assert.deepEqual(output.sectionStates, {});
    assert.deepEqual(output.analysis, makeEmptyDeepReadOutput().analysis);
    const entry = { topicId: 'topic', title: '旧标题', sourceUrl: source.url, output, phase: 'COMPLETE' as const, attemptCount: 0, lastError: null, createdAt: 1, updatedAt: 1, expiresAt: 2 };
    const markdown = deepReadToMarkdown(entry);
    assert.equal(markdown, synthesisArticleMarkdown(article));
    assert.ok(deepReadToText(entry).includes('结构化新标题'));
    assert.equal(deepReadProgressSnapshot(output, false).percent, 100);
    if (article.qa) assert.deepEqual(article.qa[0].sources, [1, 1]);
    if (article.debate) { assert.equal(article.debate.camps[0].quoteBy, '专家'); assert.equal(article.debate.camps[1].stance, 'neutral'); assert.deepEqual(article.debate.camps[1].sources, []); }
    if (article.review) { assert.equal(article.review.scores.length, 1); assert.equal(article.review.splits[0].views.length, 1); }
  });
  test(`${template.id}: empty and truncated structures cannot complete`, () => {
    assert.equal(parseSynthesisArticle('{}', template, '话题', [source]), null);
    assert.equal(parseSynthesisArticle(reply(template.id).slice(0, -5), template, '话题', [source]), null);
  });
}

type Fixture = { deps: RunManagerDeps; read: () => DeepReadOutput | null; write: (output: DeepReadOutput) => void; prompts: string[]; counts: { prefetch: number; plan: number } };
const harness = (id: string, responses: string[]): Fixture => {
  let disk = JSON.stringify({ ...makeEmptyDeepReadOutput(), templateSnapshot: snapshot(id), templateId: id });
  const prompts: string[] = []; const counts = { prefetch: 0, plan: 0 };
  const deps: RunManagerDeps = { writerMode: 'structured', model: 'model', playbookMarkdown: '', nowIso: () => '2026-10-06',
    repository: { get: () => JSON.parse(disk), save: async (_id, _title, output) => { disk = JSON.stringify(output); }, clear() { throw new Error('must not clear'); } },
    prefetcher: { collect: async () => { counts.prefetch++; return [source]; }, cacheSize: () => 0 },
    aiClient: { generateText: async () => { counts.plan++; return []; } },
    collectRun: async (messages, _label, signal, tools) => { assert.equal(tools?.length, 0); prompts.push(messages.map(toText).join('\n')); if (signal?.aborted) throw new Error('aborted'); return [...messages, makeAssistantMessage(responses.shift() ?? '{}')]; },
  };
  return { deps, prompts, counts, read: () => JSON.parse(disk), write: output => { disk = JSON.stringify(output); } };
};

test('explicit templates use shared collection and one synthesis call, complete cache avoids new work', async () => {
  for (const template of DEEPREAD_SYNTHESIS_TEMPLATES.slice(1)) {
    const h = harness(template.id, [reply(template.id)]);
    const first = await run(h.deps, 'topic', '标题');
    assert.equal(first.ok, true); assert.equal(isComplete(first.output), true);
    assert.equal(h.prompts.length, 1); assert.equal(h.counts.plan, 0); assert.equal(h.counts.prefetch, 1);
    assert.deepEqual(h.read()?.templateArticle, first.output.templateArticle);
    const cached = await run(h.deps, 'topic', '标题', { templateSnapshot: snapshot('deepread_qa') });
    assert.deepEqual(cached.output, JSON.parse(JSON.stringify(first.output))); assert.equal(h.prompts.length, 1); assert.equal(h.counts.prefetch, 1);
  }
});

test('auto selects once, persists concrete choice on failure, cold retry keeps choice despite changed preference', async () => {
  const h = harness('deepread_auto', ['{"template":"deepread_qa"}', '{}', reply('deepread_qa')]);
  const failed = await run(h.deps, 'topic', '标题');
  assert.equal(failed.ok, false); assert.equal(failed.output.templateSnapshot?.id, 'deepread_qa');
  assert.equal(failed.output.sectionStates['OVERVIEW'].status, 'FAILED');
  assert.equal(h.prompts.length, 2);
  const result = await runSection({ ...h.deps, templateSnapshot: snapshot('deepread_review') }, 'topic', '标题', 'OVERVIEW');
  assert.equal(result.ok, true); assert.equal(result.output.templateArticle?.template, 'deepread_qa');
  assert.equal(h.prompts.length, 3); assert.equal(h.counts.plan, 0);
});

test('auto pick cancellation is propagated and a late article never commits', async () => {
  for (const pick of [true, false]) {
    const h = harness(pick ? 'deepread_auto' : 'deepread_brief', []); const controller = new AbortController();
    h.deps.collectRun = async (messages, _label, signal) => {
      assert.equal(signal, controller.signal); controller.abort();
      return [...messages, makeAssistantMessage(pick ? '{"template":"deepread_brief"}' : reply('deepread_brief'))];
    };
    const result = await run(h.deps, 'topic', '标题', { signal: controller.signal });
    assert.equal(result.error, 'aborted'); assert.equal(result.ok, false);
    assert.equal(h.read()?.templateArticle, undefined); assert.equal(isComplete(h.read()!), false);
  }
});

test('failed forced template replacement preserves the complete prior article', async () => {
  const h = harness('deepread_brief', [reply('deepread_brief'), '{}']);
  const first = await run(h.deps, 'topic', '标题');
  const old = JSON.stringify(h.read());
  const result = await run(h.deps, 'topic', '标题', { force: true, templateSnapshot: snapshot('deepread_qa') });
  assert.equal(result.ok, false); assert.ok(result.error?.includes('问答解读'));
  assert.equal(JSON.stringify(h.read()), old); assert.deepEqual(result.output, JSON.parse(JSON.stringify(first.output)));
});

test('synthesis persistence rejection propagates instead of reporting model failure or completion', async () => {
  const h = harness('deepread_brief', [reply('deepread_brief')]);
  h.deps.repository!.save = async (_id, _title, output) => { if (output.templateArticle) throw new Error('disk full'); h.write(output); };
  await assert.rejects(run(h.deps, 'topic', '标题'), /disk full/);
  assert.equal(h.read()?.templateArticle, undefined); assert.equal(isComplete(h.read()!), false);
});

test('classic choice and unreadable auto choice fall back once to the magazine pipeline', async () => {
  for (const pick of ['{"template":"magazine"}', 'not valid JSON']) {
    const h = harness('deepread_auto', [pick,
      JSON.stringify({ summary: '这是充分有效的中文导语，介绍背景、事件和主要事实，超过最小长度。' }),
      JSON.stringify({ timeline: [{ date: '今天', event: '来源记录的重要事件与后续发展，详细说明行动与结果。' }] }),
      JSON.stringify({ analysis: { core_dispute: '争议在于各方如何评价这次行动对于相关行业竞争格局的影响。', perspectives: [] } })]);
    const result = await run(h.deps, 'topic', '标题');
    assert.equal(result.ok, true); assert.equal(isComplete(result.output), true);
    assert.equal(result.output.templateArticle, undefined); assert.equal(result.output.templateSnapshot?.id, 'none');
    assert.equal(h.counts.plan, 1); assert.equal(h.counts.prefetch, 1); assert.equal(h.prompts.length, 4);
  }
});

test('an existing partial magazine never switches to an auto or newly selected synthesis template', async () => {
  const h = harness('deepread_auto', [JSON.stringify({ timeline: [{ date: '今天', event: '重要事件与后续发展详解，这是超过二十字的真实叙事。' }] }), JSON.stringify({ analysis: { core_dispute: '各方如何评价本次行动，主要争议在于竞争格局以及未来发展的重要影响。' } })]);
  h.write({ ...h.read()!, summary: '已有导语', sectionStates: { OVERVIEW: { status: 'READY', errorMessage: null } } });
  const result = await run(h.deps, 'topic', '标题', { templateSnapshot: snapshot('deepread_qa') });
  assert.equal(result.output.templateArticle, undefined); assert.equal(result.output.summary, '已有导语');
  assert.equal(h.prompts.some(prompt => prompt.includes('不要写文章')), false); assert.equal(h.counts.plan, 1);
});


test('template TXT uses the injected Markdown parser and debate export preserves stance', () => {
  const template = DEEPREAD_SYNTHESIS_TEMPLATES.find(item => item.id === 'deepread_qa')!;
  const article = parseSynthesisArticle(JSON.stringify({ title: '问答', lede: '导语', questions: [{ q: '内容是什么？', a: '这是*斜体*和 `代码`。\n\n|项目|值|\n|---|---|\n|容量|128GB|', sources: [1] }] }), template, '话题', [source])!;
  const output = { ...makeEmptyDeepReadOutput(), templateArticle: article, generationComplete: true, generationPhase: 'COMPLETE' as const };
  const entry = { topicId: 'topic', title: '旧标题', sourceUrl: source.url, output, phase: 'COMPLETE' as const, attemptCount: 0, lastError: null, createdAt: 1, updatedAt: 1, expiresAt: 2 };
  const markdown = deepReadToMarkdown(entry);
  const seen: string[] = [];
  const plain = deepReadToText(entry, text => { seen.push(text); return markdownToPlainText(text); });
  assert.deepEqual(seen, [synthesisArticleMarkdown(article, false)]);
  assert.ok(markdown.includes('*斜体*')); assert.ok(markdown.includes('`代码`')); assert.ok(markdown.includes('|容量|128GB|'));
  assert.ok(plain.includes('这是斜体和 代码。')); assert.ok(plain.includes('容量\t128GB'));
  assert.equal(plain.includes('*斜体*'), false); assert.equal(plain.includes('`代码`'), false); assert.equal(plain.includes('|---|'), false);
  const debate = parseSynthesisArticle(reply('deepread_debate'), DEEPREAD_SYNTHESIS_TEMPLATES.find(item => item.id === 'deepread_debate')!, '话题', [source])!;
  debate.debate!.camps[0].label = '业内专家'; debate.debate!.camps[0].stance = 'con';
  const debateMarkdown = synthesisArticleMarkdown(debate);
  assert.ok(debateMarkdown.includes('### 反对 · 业内专家'));
  assert.ok(debateMarkdown.includes('### 中立 · 谨慎方'));
});

test('source metadata remains literal in Markdown links and TXT preserves complete URLs', () => {
  const template = DEEPREAD_SYNTHESIS_TEMPLATES.find(item => item.id === 'deepread_qa')!;
  const article = parseSynthesisArticle(reply('deepread_qa'), template, '话题', [source])!;
  const title = '标题](https://other.example) [报告 `代码` $价格$';
  const site = '新闻 *网站* [栏目] $价格$';
  const url = 'https://news.test/story_(edition)?ref=(source)';
  article.sources[0] = { id: 1, title, site, url };
  const markdown = synthesisArticleMarkdown(article);
  const sourceLine = markdown.split('\n').find(line => line.startsWith('- [1] '))!;
  const tokens = parseInline(sourceLine);
  const links = tokens.filter(token => token.type === 'link');
  assert.equal(links.length, 1);
  assert.equal(links[0].type === 'link' ? links[0].text : '', title);
  assert.equal(decodeURI(links[0].type === 'link' ? links[0].url : ''), url);
  const output = { ...makeEmptyDeepReadOutput(), templateArticle: article };
  const entry = { topicId: 'topic', title: '旧标题', sourceUrl: url, output, phase: 'COMPLETE' as const, attemptCount: 0, lastError: null, createdAt: 1, updatedAt: 1, expiresAt: 2 };
  const plain = deepReadToText(entry, markdownToPlainText);
  assert.ok(plain.includes(title));
  assert.ok(plain.includes(site));
  assert.ok(plain.includes(url));
  assert.ok(plain.includes('来源解释了原因。'));
});

test('ordinary source links preserve parenthesized URLs and TXT does not discard their target', () => {
  const article = parseSynthesisArticle(reply('deepread_qa'), DEEPREAD_SYNTHESIS_TEMPLATES.find(item => item.id === 'deepread_qa')!, '话题', [source])!;
  const url = 'https://news.test/story_(edition)?ref=(source)';
  article.sources[0].url = url;
  const sourceLine = synthesisArticleMarkdown(article).split('\n').find(line => line.startsWith('- [1] '))!;
  const links = parseInline(sourceLine).filter(token => token.type === 'link');
  assert.equal(links.length, 1);
  assert.equal(decodeURI(links[0].type === 'link' ? links[0].url : ''), url);
  const output = { ...makeEmptyDeepReadOutput(), templateArticle: article };
  const entry = { topicId: 'topic', title: '旧标题', sourceUrl: url, output, phase: 'COMPLETE' as const, attemptCount: 0, lastError: null, createdAt: 1, updatedAt: 1, expiresAt: 2 };
  assert.ok(deepReadToText(entry, markdownToPlainText).includes(url));
});
