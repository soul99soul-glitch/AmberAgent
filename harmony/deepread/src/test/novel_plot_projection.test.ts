import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { makeNovelChapter } from '../main/ets/novel/models.ts';
import type { NovelChapter } from '../main/ets/novel/models.ts';
import {
  chapterPlotSourceDigest, firstStaleChapterOrdinal, rebuildChapterPlots, updateChapterPlots,
} from '../main/ets/novel/plot_projection.ts';

const chapters = (): NovelChapter[] => [1, 2, 3, 4].map(n => makeNovelChapter({
  id: `c${n}`, title: `第${n}章`, content: `正文${n}`, now: 1,
}));

test('body edit refreshes its own evidence and invalidates unchanged later chapters', () => {
  const before = chapters();
  const after = before.map(c => c.id === 'c2' ? { ...c, content: '正文2改' } : c);
  const pointers = updateChapterPlots(before, after, rebuildChapterPlots(before));
  assert.deepEqual(pointers.map(p => p.stale), [false, false, true, true]);
  assert.equal(pointers[1].sourceDigest, chapterPlotSourceDigest('正文2改'));
  assert.match(pointers[1].text, /正文2改/);
  assert.equal(firstStaleChapterOrdinal(after, pointers), 3);
  assert.deepEqual(rebuildChapterPlots(after).map(p => p.stale), [false, false, false, false]);
});

test('deletion and discard invalidate the first surviving later chapter; tail deletion does not', () => {
  const before = chapters();
  const removed = before.filter(c => c.id !== 'c2');
  assert.deepEqual(updateChapterPlots(before, removed, rebuildChapterPlots(before)).map(p => [p.chapterId, p.stale]),
    [['c1', false], ['c3', true], ['c4', true]]);
  const discarded = before.map(c => c.id === 'c2' ? { ...c, discarded: true } : c);
  assert.deepEqual(updateChapterPlots(before, discarded, rebuildChapterPlots(before)).map(p => [p.chapterId, p.stale]),
    [['c1', false], ['c3', true], ['c4', true]]);
  assert.deepEqual(updateChapterPlots(before, before.slice(0, -1), rebuildChapterPlots(before)).map(p => p.stale),
    [false, false, false]);
});

test('restore creates fresh evidence for the restored chapter and invalidates later chapters', () => {
  const after = chapters();
  const before = after.map(c => c.id === 'c2' ? { ...c, discarded: true } : c);
  assert.deepEqual(updateChapterPlots(before, after, rebuildChapterPlots(before)).map(p => p.stale),
    [false, false, true, true]);
});

test('append and later edits preserve earlier stale evidence; title and diagnostic metadata do not invalidate', () => {
  const before = chapters();
  const prior = rebuildChapterPlots(before).map(p => ({ ...p, stale: p.chapterId === 'c2' }));
  const appended = before.concat(makeNovelChapter({ id: 'c5', title: '新章', content: '五', now: 1 }));
  assert.deepEqual(updateChapterPlots(before, appended, prior).map(p => p.stale), [false, true, false, false, false]);
  const edited = before.map(c => c.id === 'c4' ? { ...c, content: '四改' } : c);
  assert.deepEqual(updateChapterPlots(before, edited, prior).map(p => p.stale), [false, true, false, false]);
  const titleOnly = before.map(c => c.id === 'c1' ? { ...c, title: '新标题', suggestionWarning: '分析失败' } : c);
  const pointers = updateChapterPlots(before, titleOnly, rebuildChapterPlots(before));
  assert.deepEqual(pointers.map(p => p.stale), [false, false, false, false]);
  assert.match(pointers[0].text, /^新标题/);
  assert.equal(pointers[0].sourceDigest, chapterPlotSourceDigest(before[0].content));
});

test('pointer evidence is bounded and skips blank lines', () => {
  const chapter = makeNovelChapter({ id: 'c', title: '标题', content: '\n 一\n\n 二\n 三\n四', now: 1 });
  assert.equal(rebuildChapterPlots([chapter])[0].text, '标题\n一\n二\n三');
  assert.equal(rebuildChapterPlots([{ ...chapter, content: '长'.repeat(500) }])[0].text.length, 160);
});

test('mismatched source evidence stays stale until an explicit rebuild', () => {
  const current = chapters();
  const prior = rebuildChapterPlots(current);
  prior[1] = { ...prior[1], sourceDigest: 'outdated' };
  const pointers = updateChapterPlots(current, current, prior);
  assert.deepEqual(pointers.map(pointer => pointer.stale), [false, true, true, true]);
  assert.deepEqual(rebuildChapterPlots(current).map(pointer => pointer.stale), [false, false, false, false]);
});
