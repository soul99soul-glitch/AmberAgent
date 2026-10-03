import { test } from 'node:test';
import assert from 'node:assert/strict';
import { deepReadToMarkdown, deepReadToText } from '../main/ets/domain/export.ts';
import { makeEmptyDeepReadOutput } from '../main/ets/domain/models.ts';
import { IMAGE_CONFIDENCE } from '../main/ets/domain/enums.ts';
import type { DeepReadCacheEntry } from '../main/ets/platform/repository.ts';

const fullEntry = (): DeepReadCacheEntry => {
  const output = makeEmptyDeepReadOutput();
  output.topicType = 'product';
  output.summary = '**完整概览**\n\n|列|值|\n|---|---|\n|甲|乙|';
  output.keyEntities = ['实体[甲]', '实体乙'];
  output.timeline = [{ date: '时间甲', event: '时间轴正文甲', isHighlight: true,
    imageUrl: 'https://img.example/timeline.png', imageCaption: '时间轴图注甲' }];
  output.corePoints = [{ point: '要点正文甲', supporting: '支持材料甲',
    imageUrl: 'https://img.example/core.png', imageCaption: '核心图注甲' }];
  output.analysis = { coreDispute: '争议甲', perspectives: [{ holder: '观点持有者甲', viewpoint: '观点正文甲' }],
    implications: '影响正文甲', quotes: Array.from({ length: 6 }, (_, n) => ({ text: `引述${n}`, attribution: `出处${n}` })) };
  output.heroImageUrl = 'https://img.example/hero.png';
  output.heroImageConfidence = IMAGE_CONFIDENCE.HERO;
  output.heroCaption = '主图图注甲';
  output.imageAssets = [
    { url: output.heroImageUrl, caption: '资产主图图注甲', confidence: IMAGE_CONFIDENCE.HERO, score: 90, source: '主图来源甲',
      qualityHint: null, selectionReason: null, relatedEntities: [], relatedTimelineIndex: null },
    { url: 'https://img.example/extra.png', caption: '图库图注甲', confidence: IMAGE_CONFIDENCE.INLINE, score: 80, source: '图库来源甲',
      qualityHint: null, selectionReason: null, relatedEntities: [], relatedTimelineIndex: null },
    { url: 'https://img.example/rejected.png', caption: '拒绝图片不可导出', confidence: IMAGE_CONFIDENCE.REJECT, score: 1, source: null,
      qualityHint: null, selectionReason: null, relatedEntities: [], relatedTimelineIndex: null },
  ];
  output.diagram = { type: 'relationship', title: '关系图标题甲', reason: '关系图解释甲',
    nodes: [{ id: 'node-a', label: '关系节点甲', note: '节点说明甲', group: '关系分组甲' },
      { id: 'node-b', label: '关系节点乙', note: null, group: null }],
    edges: [{ from: 'node-a', to: 'node-b', label: '关系边甲' }], caption: '关系图图注甲' };
  output.references = [{ title: '参考标题甲', url: 'https://ref.example/a?q=(甲)', source: '参考来源甲', publishedAt: '2026-09-30' }];
  output.extendedReading = Array.from({ length: 12 }, (_, n) => ({ title: `延伸标题${n}`, url: `https://extra.example/${n}`,
    source: `延伸来源${n}`, publishedAt: `发布时间${n}` }));
  output.inputSourceUrls = ['https://input.example/seed-only'];
  return { topicId: 'topic', title: '标题[甲]', sourceUrl: 'https://source.example/topic', output, phase: 'COMPLETE',
    attemptCount: 0, lastError: null, createdAt: 1, updatedAt: 1_790_726_400_000, expiresAt: 2_000_000_000_000 };
};

test('both exports retain every visible chapter, all quotes, all reading links, sources and captions', () => {
  for (const serialize of [deepReadToMarkdown, deepReadToText]) {
    const result = serialize(fullEntry());
    for (const text of ['product', '完整概览', '|甲|乙|', '实体乙', '时间甲', '时间轴正文甲', '时间轴图注甲',
      '要点正文甲', '支持材料甲', '核心图注甲', '争议甲', '观点持有者甲', '观点正文甲', '影响正文甲',
      '引述5', '出处5', '主图图注甲', '资产主图图注甲', '主图来源甲', '图库图注甲', '图库来源甲',
      '关系图标题甲', '关系图解释甲', '关系节点甲', '节点说明甲', '关系分组甲', '关系节点乙', '关系边甲',
      '关系图图注甲', '参考标题甲', '参考来源甲', '2026-09-30', '延伸标题11', '延伸来源11', '发布时间11',
      '研究输入', 'https://input.example/seed-only']) assert.ok(result.includes(text), text);
    assert.ok(!result.includes('rejected.png'));
    assert.ok(!result.includes('拒绝图片不可导出'));
  }
});

test('Markdown preserves article syntax while escaping literal labels and unsafe link destinations', () => {
  const entry = fullEntry();
  entry.output.references.push({ title: '危险[标题]', url: 'javascript:alert(1)', source: null, publishedAt: null });
  const result = deepReadToMarkdown(entry);
  assert.ok(result.startsWith('# 标题\\[甲\\]'));
  assert.ok(result.includes('**完整概览**'));
  assert.ok(result.includes('[参考标题甲](<https://ref.example/a?q=(甲)>)'));
  assert.ok(!result.includes('](<javascript:'));
  assert.ok(result.includes('危险\\[标题\\]'));
  assert.ok(result.includes('javascript:alert\\(1\\)'));
});

test('partial output exports retain isolated captions, relationship IDs and chapter content', () => {
  const entry = fullEntry();
  entry.output = makeEmptyDeepReadOutput();
  entry.output.corePoints = [{ point: '', supporting: '孤立支持内容', imageUrl: null, imageCaption: '无图但已有图注' }];
  entry.output.diagram = { type: 'flow', title: '', reason: null, nodes: [],
    edges: [{ from: '旧端点甲', to: '旧端点乙', label: '旧关系' }], caption: '孤立图注' };
  const result = deepReadToText(entry);
  assert.ok(result.includes('生成状态：部分稿'));
  assert.ok(deepReadToMarkdown(entry).includes('生成状态：部分稿'));
  for (const text of ['孤立支持内容', '无图但已有图注', '旧端点甲 → 旧端点乙：旧关系', '孤立图注']) {
    assert.ok(result.includes(text), text);
  }
});

test('structured image and diagram text stays literal in Markdown rather than adding links or images', () => {
  const entry = fullEntry();
  const literal = '[来源](javascript:alert(1)) ![额外图](https://unexpected.example/image.png)';
  entry.output.heroCaption = literal;
  entry.output.timeline![0].imageCaption = literal;
  entry.output.corePoints![0].imageCaption = literal;
  entry.output.imageAssets[0].caption = literal;
  entry.output.imageAssets[1].caption = literal;
  entry.output.diagram!.caption = literal;
  entry.output.diagram!.reason = literal;
  entry.output.diagram!.nodes[0].note = literal;
  const markdown = deepReadToMarkdown(entry);
  assert.ok(!markdown.includes('[来源](javascript:'));
  assert.ok(!markdown.includes('![额外图](https://unexpected'));
  assert.ok(markdown.includes('\\[来源\\]\\(javascript:alert\\(1\\)\\)'));
  assert.ok(deepReadToText(entry).includes(literal));
});


test('text prose formatter applies only to Markdown body fields and leaves literal metadata untouched', () => {
  const entry = fullEntry();
  entry.title = '**literal title**';
  entry.output.heroCaption = '**literal caption**';
  const seen: string[] = [];
  const format = (source: string): string => { seen.push(source); return 'PLAIN:' + source; };
  const text = deepReadToText(entry, format);
  for (const body of [entry.output.summary, entry.output.timeline![0].event,
    entry.output.corePoints![0].point, entry.output.corePoints![0].supporting!,
    entry.output.analysis.coreDispute!, entry.output.analysis.perspectives[0].viewpoint,
    entry.output.analysis.implications!, entry.output.analysis.quotes[5].text]) {
    assert.ok(seen.includes(body)); assert.ok(text.includes('PLAIN:' + body));
  }
  assert.equal(seen.includes(entry.title), false); assert.equal(seen.includes(entry.output.heroCaption), false);
  assert.ok(text.startsWith('**literal title**')); assert.ok(text.includes('**literal caption**'));
  assert.ok(text.includes('https://ref.example/a?q=(甲)'));
  assert.ok(deepReadToMarkdown(entry).includes('**完整概览**'));
  assert.equal(deepReadToMarkdown(entry).includes('PLAIN:'), false);
});
