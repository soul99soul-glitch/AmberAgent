import { test } from 'node:test';
import assert from 'node:assert/strict';
import { deepReadLibraryStatus, queryDeepReadLibrary } from '../main/ets/domain/library.ts';
import { makeEmptyDeepReadOutput } from '../main/ets/domain/models.ts';
import type { DeepReadCacheEntry } from '../main/ets/platform/repository.ts';

const entry = (id: string): DeepReadCacheEntry => ({
  topicId: id, title: `文章 ${id}`, sourceUrl: null, output: makeEmptyDeepReadOutput(),
  phase: 'IDLE', attemptCount: 0, lastError: null, createdAt: 1, updatedAt: 1, expiresAt: 2,
});

test('library searches the entire saved history and deep chapters without changing order', () => {
  const rows = Array.from({ length: 105 }, (_, i) => entry(String(i)));
  rows[104].output.analysis.quotes = [{ text: 'Distinct Evidence', attribution: '证人' }];
  assert.deepEqual(queryDeepReadLibrary(rows, ' distinct evidence ', 'all').map(row => row.topicId), ['104']);
  assert.equal(queryDeepReadLibrary(rows, '文章 10', 'all').length, 6);
  assert.equal(rows.length, 105);
});

test('running status uses actual jobs; interrupted writing is incomplete and failed chapters remain failures', () => {
  const interrupted = entry('old');
  interrupted.phase = 'WRITING';
  assert.equal(deepReadLibraryStatus(interrupted), 'incomplete');
  assert.equal(deepReadLibraryStatus(interrupted, ['old']), 'running');
  interrupted.output.sectionStates.OVERVIEW = { status: 'FAILED', errorMessage: 'provider failed' };
  assert.equal(deepReadLibraryStatus(interrupted), 'failed');
  assert.equal(queryDeepReadLibrary([interrupted], '', 'running', ['old']).length, 1);
  assert.equal(queryDeepReadLibrary([interrupted], '', 'failed', ['old']).length, 0);
});

test('completion and failure filters combine with full text search', () => {
  const failed = entry('failed'); failed.lastError = '网络失败'; failed.output.summary = '植物';
  const partial = entry('partial'); partial.output.summary = '植物';
  assert.deepEqual(queryDeepReadLibrary([failed, partial], '植物', 'failed').map(row => row.topicId), ['failed']);
  assert.deepEqual(queryDeepReadLibrary([failed, partial], '植物', 'incomplete').map(row => row.topicId), ['partial']);
  assert.deepEqual(queryDeepReadLibrary([failed, partial], '不存在', 'all'), []);
});
