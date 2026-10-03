import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { makeNovelChapter, makeNovelMaterial } from '../main/ets/novel/models.ts';
import type { NovelChapter, NovelMaterial } from '../main/ets/novel/models.ts';
import { chapterPlotSourceDigest } from '../main/ets/novel/plot_projection.ts';
import {
  NOVEL_STATE_PROTOCOL_VERSION, appendNovelStateDelta, applyNovelIdentityClarification, emptyNovelStructuredState,
  invalidateNovelStructuredState, mergeNovelStateDelta, parseNovelStateDelta, parseNovelStructuredState,
  projectNovelCharacterExperiences, pruneNovelStructuredState,
} from '../main/ets/novel/structured_state.ts';

const materials = (): NovelMaterial[] => [
  makeNovelMaterial({ id: 'lin', kind: 'character', title: '林舟', content: '主人公', aliases: ['小舟'], now: 1 }),
  makeNovelMaterial({ id: 'shen', kind: 'character', title: '沈青', content: '同行者', now: 1 }),
  makeNovelMaterial({ id: 'world', kind: 'world', title: '旧城', content: '背景', now: 1 }),
];
const chapter = (id: string = 'c1'): NovelChapter => makeNovelChapter({
  id, title: '渡桥', content: '小舟救起了沈青。路人喊道：“阿远！”', now: 1,
});
const envelope = (source: NovelChapter = chapter()) => ({
  protocolVersion: NOVEL_STATE_PROTOCOL_VERSION,
  chapterId: source.id,
  sourceDigest: chapterPlotSourceDigest(source.content),
  events: [{ id: `${source.id}-rescue`, chapterId: source.id, sourceDigest: chapterPlotSourceDigest(source.content),
    quote: '小舟救起了沈青。', summary: '林舟救起沈青', entityRefs: ['lin', 'shen'] }],
  unresolvedIdentityNames: ['阿远'],
});
const parsed = (source: NovelChapter = chapter()) => parseNovelStateDelta(JSON.stringify(envelope(source)), source, materials());
const state = () => mergeNovelStateDelta(emptyNovelStructuredState(), parsed(), chapter(), materials());

test('strict versioned JSON accepts exact source evidence and records empty chapters as checked', () => {
  assert.deepEqual(parsed(), envelope());
  const empty = makeNovelChapter({ id: 'c2', title: '空', content: '', now: 1 });
  const delta = parseNovelStateDelta(JSON.stringify({ ...envelope(empty), events: [], unresolvedIdentityNames: [] }), empty, materials());
  const result = mergeNovelStateDelta(state(), delta, empty, materials());
  assert.deepEqual(result.chapterSources.map(item => item.chapterId), ['c1', 'c2']);
  assert.deepEqual(result.staleChapterIds, []);
});

test('whole-document parsing rejects fences, trailing junk, unknown fields, missing fields and wrong types', () => {
  const text = JSON.stringify(envelope());
  for (const invalid of ['```json\n' + text + '\n```', text + ' commentary', '[]', 'null', '{']) {
    assert.throws(() => parseNovelStateDelta(invalid, chapter(), materials()), /状态/);
  }
  const base = envelope();
  const { sourceDigest: removed, ...missing } = base;
  assert.ok(removed);
  for (const invalid of [missing, { ...base, extra: true }, { ...base, protocolVersion: 'amber.novel.state.v2' },
    { ...base, events: {} }, { ...base, unresolvedIdentityNames: null },
    { ...base, events: [{ ...base.events[0], extra: true }] },
    { ...base, events: [{ ...base.events[0], entityRefs: [1] }] },
    { ...base, events: [{ ...base.events[0], summary: ' ' }] }]) {
    assert.throws(() => parseNovelStateDelta(JSON.stringify(invalid), chapter(), materials()), /状态/);
  }
});

test('source chapter, digest and literal event quote must all match current body', () => {
  const base = envelope();
  for (const invalid of [{ ...base, chapterId: 'c2' }, { ...base, sourceDigest: 'outdated' },
    { ...base, events: [{ ...base.events[0], chapterId: 'c2' }] },
    { ...base, events: [{ ...base.events[0], sourceDigest: 'outdated' }] },
    { ...base, events: [{ ...base.events[0], quote: '林舟救起沈青。' }] },
    { ...base, events: [{ ...base.events[0], quote: ' ' }] },
    { ...base, unresolvedIdentityNames: ['从未出现'] }]) {
    assert.throws(() => parseNovelStateDelta(JSON.stringify(invalid), chapter(), materials()), /状态/);
  }
  assert.throws(() => parseNovelStateDelta(JSON.stringify(base), { ...chapter(), content: chapter().content + '改' }, materials()), /状态/);
  assert.throws(() => parseNovelStateDelta(JSON.stringify(base), { ...chapter(), discarded: true }, materials()), /状态/);
});

test('event IDs and references are explicit unique effective material IDs, never alias lookup', () => {
  const base = envelope();
  for (const invalid of [{ ...base, events: base.events.concat(base.events) },
    { ...base, events: [{ ...base.events[0], entityRefs: ['小舟'] }] },
    { ...base, events: [{ ...base.events[0], entityRefs: ['lin', 'lin'] }] },
    { ...base, events: [{ ...base.events[0], entityRefs: ['missing'] }] }]) {
    assert.throws(() => parseNovelStateDelta(JSON.stringify(invalid), chapter(), materials()), /状态/);
  }
  assert.throws(() => parseNovelStateDelta(JSON.stringify(base), chapter(), materials().filter(item => item.id !== 'lin')), /状态/);
});

test('known exact canonical names and aliases are filtered only from unknown identity candidates', () => {
  const delta = parseNovelStateDelta(JSON.stringify({ ...envelope(), unresolvedIdentityNames: ['小舟', '沈青', '阿远', '阿远'] }), chapter(), materials());
  assert.deepEqual(delta.unresolvedIdentityNames, ['阿远']);
  const unreferenced = { ...delta, events: delta.events.map(item => ({ ...item, entityRefs: ['shen'] })) };
  const result = mergeNovelStateDelta(emptyNovelStructuredState(), unreferenced, chapter(), materials());
  const experiences = projectNovelCharacterExperiences(result, [chapter()], materials());
  assert.equal(experiences.find(item => item.materialId === 'lin')?.events.length, 0);
  assert.deepEqual(experiences.find(item => item.materialId === 'lin')?.aliases, ['小舟']);
  assert.equal(experiences.find(item => item.materialId === 'shen')?.events.length, 1);
  assert.ok(!experiences.some(item => item.materialId === 'world'));
});

test('chapter merge replaces prior facts, keeps other chapters and rejects cross-chapter ID collision', () => {
  const before = state();
  const second = chapter('c2');
  const merged = mergeNovelStateDelta(before, parsed(second), second, materials());
  assert.equal(merged.events.length, 2);
  const noEvents = { ...parsed(), events: [] };
  assert.deepEqual(mergeNovelStateDelta(merged, noEvents, chapter(), materials()).events.map(item => item.chapterId), ['c2']);
  const collision = { ...parsed(second), events: [{ ...parsed(second).events[0], id: before.events[0].id }] };
  assert.throws(() => mergeNovelStateDelta(before, collision, second, materials()), /状态/);
  assert.equal(before.events.length, 1);
});

test('same chapter chunks accumulate idempotently and reject conflicting IDs or old body versions', () => {
  const before = state();
  const second = { ...parsed(), events: [{ ...parsed().events[0], id: 'c1-chunk2', quote: '路人喊道：“阿远！”',
    summary: '路人呼唤阿远', entityRefs: [] }] };
  const accumulated = appendNovelStateDelta(before, second, chapter(), materials());
  assert.deepEqual(accumulated.events.map(item => item.id), ['c1-rescue', 'c1-chunk2']);
  assert.deepEqual(appendNovelStateDelta(accumulated, second, chapter(), materials()), accumulated);
  assert.throws(() => appendNovelStateDelta(before, { ...second, events: [{ ...second.events[0], id: 'c1-rescue' }] }, chapter(), materials()), /状态/);
  const changed = { ...chapter(), content: chapter().content + '新版' };
  const delta = { ...second, sourceDigest: chapterPlotSourceDigest(changed.content),
    events: second.events.map(item => ({ ...item, sourceDigest: chapterPlotSourceDigest(changed.content) })) };
  assert.throws(() => appendNovelStateDelta(before, delta, changed, materials()), /状态/);
  assert.equal(before.events.length, 1);
});

test('pruning removes changed, discarded, missing and invalid-reference evidence from projection', () => {
  const before = state();
  for (const sources of [[], [{ ...chapter(), content: '正文改了' }], [{ ...chapter(), discarded: true }]]) {
    const result = pruneNovelStructuredState(before, sources, materials());
    assert.equal(result.events.length, 0);
    assert.equal(result.unresolvedIdentityNames.length, 0);
    assert.equal(projectNovelCharacterExperiences(before, sources, materials())[0].events.length, 0);
  }
  const withoutCharacter = materials().filter(item => item.id !== 'lin');
  assert.equal(pruneNovelStructuredState(before, [chapter()], withoutCharacter).events.length, 0);
  assert.equal(before.events.length, 1);
});

test('source mismatch discovered on read invalidates later chapters and keeps earlier evidence', () => {
  const c1 = chapter();
  const c2 = chapter('c2');
  const c3 = chapter('c3');
  const before = mergeNovelStateDelta(mergeNovelStateDelta(state(), parsed(c2), c2, materials()), parsed(c3), c3, materials());
  const current = [c1, { ...c2, content: c2.content + '修订' }, c3];
  const result = pruneNovelStructuredState(before, current, materials());
  assert.deepEqual(result.staleChapterIds, ['c2', 'c3']);
  assert.deepEqual(result.events.map(item => item.chapterId), ['c1']);
});

test('body edits, deletion and reordering invalidate later evidence; metadata alone and append preserve earlier evidence', () => {
  const c1 = chapter();
  const c2 = chapter('c2');
  const c3 = chapter('c3');
  let before = state();
  before = mergeNovelStateDelta(before, parsed(c2), c2, materials());
  before = mergeNovelStateDelta(before, parsed(c3), c3, materials());
  const original = [c1, c2, c3];
  const edited = [c1, { ...c2, content: c2.content + '新' }, c3];
  const invalid = invalidateNovelStructuredState(original, edited, before);
  assert.deepEqual(invalid.staleChapterIds, ['c2', 'c3']);
  assert.deepEqual(projectNovelCharacterExperiences(invalid, edited, materials())[0].events.map(item => item.chapterId), ['c1']);
  const refreshed = mergeNovelStateDelta(invalid, parsed(c3), c3, materials());
  assert.deepEqual(refreshed.staleChapterIds, ['c2']);
  assert.deepEqual(invalidateNovelStructuredState(original, [c1, c3], before).staleChapterIds, ['c3']);
  assert.deepEqual(invalidateNovelStructuredState(original, [c2, c1, c3], before).staleChapterIds, ['c2', 'c1', 'c3']);
  assert.deepEqual(invalidateNovelStructuredState(original, [c1, c2], before).staleChapterIds, []);
  assert.deepEqual(invalidateNovelStructuredState(original, [{ ...c1, title: '新名', updatedAt: 9 }, c2, c3], before).staleChapterIds, []);
  assert.deepEqual(invalidateNovelStructuredState(original, [...original, chapter('c4')], before).staleChapterIds, ['c4']);
});

test('explicit ignore/create/merge resolves candidate without inventing events or changing materials', () => {
  const before = state();
  const ignored = applyNovelIdentityClarification(before, '阿远', 'ignore', null, materials());
  assert.deepEqual(ignored.unresolvedIdentityNames, []);
  assert.deepEqual(ignored.identityClarifications, [{ mention: '阿远', action: 'ignore', materialId: null }]);
  const merged = applyNovelIdentityClarification(before, '阿远', 'merge', 'lin', materials());
  assert.deepEqual(merged.events, before.events);
  assert.deepEqual(merged.identityClarifications, [{ mention: '阿远', action: 'merge', materialId: 'lin' }]);
  const created = makeNovelMaterial({ id: 'yuan', kind: 'character', title: '阿远', content: '', now: 1 });
  assert.equal(applyNovelIdentityClarification(before, '阿远', 'create', 'yuan', materials().concat(created)).identityClarifications[0].materialId, 'yuan');
  for (const args of [
    ['缺失', 'ignore', null], ['阿远', 'ignore', 'lin'], ['阿远', 'merge', null], ['阿远', 'merge', 'world'], ['阿远', 'create', 'missing'],
  ] as const) {
    assert.throws(() => applyNovelIdentityClarification(before, args[0], args[1], args[2], materials()), /身份/);
  }
  assert.deepEqual(before.unresolvedIdentityNames, ['阿远']);
  assert.deepEqual(materials()[0].aliases, ['小舟']);
});

test('authored clarifications suppress later unresolved candidates and losing a target reopens the mention', () => {
  const clarified = applyNovelIdentityClarification(state(), '阿远', 'merge', 'lin', materials());
  assert.deepEqual(mergeNovelStateDelta(clarified, parsed(), chapter(), materials()).unresolvedIdentityNames, []);
  const result = pruneNovelStructuredState(clarified, [chapter()], materials().filter(item => item.id !== 'lin'));
  assert.deepEqual(result.identityClarifications, []);
  assert.deepEqual(result.unresolvedIdentityNames, ['阿远']);
});

test('full state decoder validates the exact state shape and current evidence', () => {
  const before = state();
  assert.deepEqual(parseNovelStructuredState(JSON.stringify(before), [chapter()], materials()), before);
  const { chapterSources: removed, ...missing } = before;
  assert.ok(removed);
  const malformed: unknown[] = [missing, { ...before, extra: true }, { ...before, protocolVersion: 'wrong' },
    { ...before, events: null }, { ...before, chapterSources: {} }, { ...before, identityClarifications: {} },
    { ...before, unresolvedIdentityNames: [1] }, { ...before, staleChapterIds: ['c1', 'c1'] },
    { ...before, chapterSources: before.chapterSources.concat(before.chapterSources) },
    { ...before, chapterSources: [{ ...before.chapterSources[0], extra: true }] },
    { ...before, chapterSources: [{ chapterId: 'c1', sourceDigest: null }] },
    { ...before, events: before.events.concat(before.events) },
    { ...before, events: [{ ...before.events[0], entityRefs: [1] }] },
    { ...before, events: [{ ...before.events[0], quote: '编造的证据' }] },
    { ...before, events: [{ ...before.events[0], summary: '' }] },
    { ...before, events: [{ ...before.events[0], extra: true }] },
    { ...before, events: [{ ...before.events[0], sourceDigest: '不同的旧版本' }] },
    { ...before, chapterSources: [] },
    { ...before, identityClarifications: [{ mention: '阿远', action: 'guess', materialId: 'lin' }] },
    { ...before, identityClarifications: [{ mention: '阿远', action: 'ignore', materialId: 'lin' }] },
    { ...before, identityClarifications: [{ mention: '阿远', action: 'merge', materialId: null }] },
    { ...before, identityClarifications: [{ mention: '', action: 'ignore', materialId: null }] },
    { ...before, identityClarifications: [{ mention: '阿远', action: 'create', materialId: 2 }] },
    { ...before, identityClarifications: [{ mention: '阿远', action: 'ignore', materialId: null, extra: true }] },
    { ...before, identityClarifications: [{ mention: '阿远', action: 'ignore', materialId: null },
      { mention: '阿远', action: 'merge', materialId: 'lin' }] },
  ];
  for (const invalid of malformed) {
    assert.throws(() => parseNovelStructuredState(JSON.stringify(invalid), [chapter()], materials()), /状态/);
  }
  for (const invalid of ['[]', 'null', '```json\n' + JSON.stringify(before) + '\n```', JSON.stringify(before) + '尾注']) {
    assert.throws(() => parseNovelStructuredState(invalid, [chapter()], materials()), /状态/);
  }
});

test('full state decoder accepts stale historical evidence and prunes invalid references safely', () => {
  const before = state();
  const changed = { ...chapter(), content: '新版正文，没有任何旧证据。' };
  const stale = parseNovelStructuredState(JSON.stringify(before), [changed], materials());
  assert.deepEqual(stale.events, []);
  assert.deepEqual(stale.staleChapterIds, ['c1']);
  assert.deepEqual(projectNovelCharacterExperiences(stale, [changed], materials())[0].events, []);
  assert.deepEqual(parseNovelStructuredState(JSON.stringify(before), [], materials()).events, []);
  assert.deepEqual(parseNovelStructuredState(JSON.stringify(before), [chapter()], materials().filter(item => item.id !== 'lin')).events, []);
  const explicitStale = { ...before, staleChapterIds: ['c1'] };
  assert.deepEqual(parseNovelStructuredState(JSON.stringify(explicitStale), [chapter()], materials()).events, []);
  // Declaring stale cannot excuse a fabricated quote for the same exact source version.
  assert.throws(() => parseNovelStructuredState(JSON.stringify({ ...explicitStale,
    events: [{ ...before.events[0], quote: '伪造原文' }] }), [chapter()], materials()), /状态/);
});

test('all authored identity actions suppress repeated model candidates during merge, append and cold decode', () => {
  for (const action of ['ignore', 'create', 'merge'] as const) {
    const clarified = applyNovelIdentityClarification(state(), '阿远', action, action === 'ignore' ? null : 'lin', materials());
    for (const merged of [mergeNovelStateDelta(clarified, parsed(), chapter(), materials()),
      appendNovelStateDelta(clarified, parsed(), chapter(), materials())]) {
      assert.deepEqual(merged.unresolvedIdentityNames, []);
      const cold = parseNovelStructuredState(JSON.stringify({ ...merged, unresolvedIdentityNames: ['阿远'] }), [chapter()], materials());
      assert.deepEqual(cold.unresolvedIdentityNames, []);
      assert.deepEqual(cold.identityClarifications, clarified.identityClarifications);
    }
  }
});
