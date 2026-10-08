import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { makeEmptyDeepReadOutput } from '../main/ets/domain/models.ts';
import { hasDisplayableDeepReadOutput, hasReadableArticle, isComplete } from '../main/ets/domain/helpers.ts';

test('presentation never equates an empty failed metadata row with an article', () => {
  const output = makeEmptyDeepReadOutput();
  output.sectionStates.OVERVIEW = { status: 'FAILED', errorMessage: '没有资料' };
  assert.equal(hasDisplayableDeepReadOutput(output), false);
});

test('all useful partial fields remain visible without claiming completion or quality', () => {
  const fixtures = [
    { summary: '部分摘要' },
    { keyEntities: ['人物'] },
    { timeline: [{ date: '2026', event: '事件', isHighlight: false, imageUrl: null, imageCaption: null }] },
    { corePoints: [{ point: '', supporting: '仅支撑信息', imageUrl: null, imageCaption: null }] },
    { analysis: { coreDispute: null, implications: '仅影响内容', perspectives: [], quotes: [] } },
    { analysis: { coreDispute: null, implications: null, perspectives: [], quotes: [{ text: '仅引述', attribution: '来源' }] } },
    { analysis: { coreDispute: null, implications: null, perspectives: [{ holder: '立场', viewpoint: '仅观点' }], quotes: [] } },
    { references: [{ title: '唯一来源', url: 'https://source.test', source: null, publishedAt: null }] },
    { extendedReading: [{ title: '延伸阅读', url: 'https://reading.test', source: null, publishedAt: null }] },
    { diagram: { type: 'relationship', title: '仅图解', reason: null, nodes: [], edges: [], caption: null } },
    { imageAssets: [{ url: 'https://image.test/photo.jpg', caption: '仅图片', confidence: 'inline', score: 60,
      source: null, qualityHint: null, selectionReason: null, relatedEntities: [], relatedTimelineIndex: null }] },
  ];
  for (const field of fixtures) {
    const output = { ...makeEmptyDeepReadOutput(), ...field };
    assert.equal(hasDisplayableDeepReadOutput(output), true, JSON.stringify(field));
    assert.equal(hasReadableArticle(output), false);
    assert.equal(isComplete(output), false);
  }
});

test('rejected or unsafe image candidates are not visible partial articles', () => {
  for (const [url, confidence] of [['https://image.test/rejected.jpg', 'reject'], ['file:///private/photo.jpg', 'inline']]) {
    const output = makeEmptyDeepReadOutput();
    output.imageAssets = [{ url, confidence, caption: null, score: 0, source: null, qualityHint: null,
      selectionReason: null, relatedEntities: [], relatedTimelineIndex: null }];
    assert.equal(hasDisplayableDeepReadOutput(output), false);
  }
});
