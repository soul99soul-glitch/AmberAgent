import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { NovelChapter } from '../main/ets/novel/models.ts';
import type { NovelAuditIssue } from '../main/ets/novel/continuity_audit.ts';
import { makeNovelChapter } from '../main/ets/novel/models.ts';
import { defaultGhostwriteDigest } from '../main/ets/novel/ghostwrite.ts';
import type { NovelModelRunning, NovelModelRequest, NovelModelEvent } from '../main/ets/novel/model_running.ts';
import { makeAssistantMessage } from '../main/ets/agent/message.ts';
import {
  CONTINUITY_REPAIR_PROTOCOL_VERSION, CONTINUITY_REPAIR_SYSTEM_PROMPT, REPAIR_MAX_OUTPUT_TOKENS,
  prepareContinuityRepair, parseContinuityRepair, mergeContinuityRepairs, collectContinuityRepairText,
} from '../main/ets/novel/continuity_repair.ts';

const fixture = (): { chapter: NovelChapter; issue: NovelAuditIssue } => {
  const chapter = makeNovelChapter({ id: 'chapter-007', title: '重逢',
    content: '先文：她今年二十岁。\n🙂她今年三十岁。\n后文：她今年三十岁。', now: 1 });
  const quote = '她今年三十岁。';
  const start = chapter.content.indexOf(quote);
  return { chapter, issue: {
    chapterId: chapter.id, chapterRef: '第7章 重逢', sourceDigest: defaultGhostwriteDigest(chapter.content),
    quote, start, end: start + quote.length, severity: 'major', summary: '同一时间年龄不一致', suggestion: '统一为二十岁',
  } };
};
const response = (issue: NovelAuditIssue, changes: object = {}): string => JSON.stringify({
  protocolVersion: CONTINUITY_REPAIR_PROTOCOL_VERSION, chapterId: issue.chapterId,
  sourceDigest: issue.sourceDigest, start: issue.start, end: issue.end, replacement: '她今年二十岁。', ...changes,
});

const pairedFixture = () => {
  const { chapter, issue } = fixture();
  const earlier = makeNovelChapter({ id: 'chapter-002', title: '旧日', ordinal: 2,
    content: '那一年，她刚满二十岁。', now: 1 });
  const quote = '她刚满二十岁。';
  const start = earlier.content.indexOf(quote);
  issue.canonicalReferences = [{ chapterId: earlier.id, chapterTitle: '过期标题', chapterOrdinal: 999,
    sourceDigest: defaultGhostwriteDigest(earlier.content), quote, start, end: start + quote.length }];
  return { chapter, issue, earlier, chapters: [earlier, chapter] };
};

test('paired repair prompt includes validated earlier canonical evidence with current title and ordinal', () => {
  const { chapter, issue, earlier, chapters } = pairedFixture();
  const prepared = prepareContinuityRepair(chapter, issue, chapters);
  const input = JSON.parse(prepared.userPrompt);
  assert.deepEqual(input.canonicalReferences, [{ chapterId: earlier.id, chapterTitle: earlier.title,
    chapterOrdinal: 2, sourceDigest: defaultGhostwriteDigest(earlier.content), quote: '她刚满二十岁。', start: 4, end: 11 }]);
  assert.equal(input.chapterId, chapter.id);
  assert.equal(input.sourceContent, chapter.content);
  assert.equal(input.issue.quote, issue.quote);
  assert.ok(CONTINUITY_REPAIR_SYSTEM_PROMPT.includes('canonicalReferences'));
  assert.ok(CONTINUITY_REPAIR_SYSTEM_PROMPT.includes('先文不可修改'));
  const applied = parseContinuityRepair(response(issue), chapter, issue);
  assert.equal(applied.chapterId, chapter.id);
  assert.equal(earlier.content, '那一年，她刚满二十岁。');
});

test('paired repair cannot proceed without explicit frozen chapters or with an empty reference set', () => {
  const { chapter, issue, chapters } = pairedFixture();
  assert.throws(() => prepareContinuityRepair(chapter, issue));
  assert.throws(() => prepareContinuityRepair(chapter, { ...issue, canonicalReferences: [] }, chapters));
});

test('paired repair rejects missing canonical chapter, changed quote, stale digest and invalid range', () => {
  const { chapter, issue, chapters } = pairedFixture();
  const reference = issue.canonicalReferences![0];
  for (const changes of [{ chapterId: 'missing' }, { quote: '她刚满三十岁。' }, { sourceDigest: 'old' },
    { start: reference.start + 1 }, { end: reference.end - 1 }]) {
    assert.throws(() => prepareContinuityRepair(chapter,
      { ...issue, canonicalReferences: [{ ...reference, ...changes }] }, chapters));
  }
});

test('paired repair rejects target or later chapter as canonical and rejects drift outside canonical quote', () => {
  const { chapter, issue, earlier, chapters } = pairedFixture();
  assert.throws(() => prepareContinuityRepair(chapter, issue, [chapter, earlier]));
  assert.throws(() => prepareContinuityRepair(chapter, { ...issue, canonicalReferences: [{
    chapterId: chapter.id, chapterTitle: chapter.title, chapterOrdinal: 7,
    sourceDigest: issue.sourceDigest, quote: issue.quote, start: issue.start, end: issue.end,
  }] }, chapters));
  assert.throws(() => prepareContinuityRepair(chapter, issue,
    [{ ...earlier, content: earlier.content + '后加一句' }, chapter]));
});

test('ordinary single-evidence correction still works without frozen chapter list or canonical field', () => {
  const { chapter, issue } = fixture();
  const input = JSON.parse(prepareContinuityRepair(chapter, issue).userPrompt);
  assert.equal(Object.hasOwn(input, 'canonicalReferences'), false);
  assert.ok(parseContinuityRepair(response(issue), chapter, issue).content.includes('🙂她今年二十岁。'));
});

test('prepare freezes explicit source body and exact audited UTF16 occurrence in a versioned prompt', () => {
  const { chapter, issue } = fixture();
  const prepared = prepareContinuityRepair(chapter, issue);
  const input = JSON.parse(prepared.userPrompt);
  assert.equal(prepared.sourceContent, chapter.content);
  assert.equal(prepared.sourceDigest, issue.sourceDigest);
  assert.equal(input.protocolVersion, 'amber.novel.continuity-repair.v1');
  assert.equal(input.chapterId, chapter.id);
  assert.equal(input.sourceContent, chapter.content);
  assert.equal(input.issue.quote, issue.quote);
  assert.equal(input.issue.start, issue.start);
  assert.equal(input.issue.end, issue.end);
  assert.equal(input.issue.summary, issue.summary);
  assert.equal(input.issue.suggestion, issue.suggestion);
  assert.ok(CONTINUITY_REPAIR_SYSTEM_PROMPT.includes('JSON'));
  assert.equal(REPAIR_MAX_OUTPUT_TOKENS, 8192);
});

test('a true repair changes only the audited occurrence and preserves exact prefix/suffix', () => {
  const { chapter, issue } = fixture();
  const applied = parseContinuityRepair(response(issue), chapter, issue);
  assert.equal(applied.content, '先文：她今年二十岁。\n🙂她今年二十岁。\n后文：她今年三十岁。');
  assert.equal(applied.content.slice(0, issue.start), chapter.content.slice(0, issue.start));
  assert.equal(applied.content.slice(issue.start + applied.replacement.length), chapter.content.slice(issue.end));
  assert.equal(applied.quote, issue.quote);
  assert.equal(applied.sourceDigest, issue.sourceDigest);
  assert.equal(chapter.content.includes('🙂她今年三十岁。'), true);
});

test('a second occurrence is repaired at its frozen range instead of finding the first quote', () => {
  const { chapter, issue } = fixture();
  issue.start = chapter.content.lastIndexOf(issue.quote);
  issue.end = issue.start + issue.quote.length;
  assert.equal(parseContinuityRepair(response(issue), chapter, issue).content,
    '先文：她今年二十岁。\n🙂她今年三十岁。\n后文：她今年二十岁。');
});

test('strict JSON rejects invalid JSON, prose, markdown and non-object output', () => {
  const { chapter, issue } = fixture();
  for (const text of ['bad JSON', '{', `说明：${response(issue)}`, `\x60\x60\x60json\n${response(issue)}\n\x60\x60\x60`,
    'null', '[]', '42', JSON.stringify('output'), `${response(issue)}${response(issue)}`]) {
    assert.throws(() => parseContinuityRepair(text, chapter, issue));
  }
});

test('response rejects mismatched protocol, chapter, digest and range', () => {
  const { chapter, issue } = fixture();
  for (const changes of [{ protocolVersion: 'other' }, { chapterId: 'chapter-008' }, { sourceDigest: 'stale' },
    { start: issue.start + 1 }, { end: issue.end - 1 }, { start: String(issue.start) }, { start: null }]) {
    assert.throws(() => parseContinuityRepair(response(issue, changes), chapter, issue));
  }
});

test('response rejects extra full-chapter output, missing fields and non-text/no-op replacement', () => {
  const { chapter, issue } = fixture();
  for (const changes of [{ content: '整个重写的正文' }, { replacement: undefined }, { sourceDigest: undefined },
    { replacement: 123 }, { replacement: '' }, { replacement: ' \n' }, { replacement: issue.quote }]) {
    assert.throws(() => parseContinuityRepair(response(issue, changes), chapter, issue));
  }
});

test('both prepare and apply reject source body drift even when the quote stays unchanged', () => {
  const { chapter, issue } = fixture();
  chapter.content += '\n新增正文';
  assert.throws(() => prepareContinuityRepair(chapter, issue), /正文.*变化/);
  assert.throws(() => parseContinuityRepair(response(issue), chapter, issue), /正文.*变化/);
});

test('both prepare and apply reject wrong chapter identity and changed quote at a valid range', () => {
  const { chapter, issue } = fixture();
  for (const invalid of [{ ...issue, chapterId: 'other' }, { ...issue, quote: '她今年四十岁。' }]) {
    assert.throws(() => prepareContinuityRepair(chapter, invalid));
    assert.throws(() => parseContinuityRepair(response(invalid), chapter, invalid));
  }
});

test('both prepare and apply reject invalid or shifted ranges rather than searching for another match', () => {
  const { chapter, issue } = fixture();
  for (const range of [{ start: -1 }, { start: NaN }, { start: Infinity }, { start: issue.start + 0.5 },
    { start: issue.end }, { end: issue.end - 1 }, { end: chapter.content.length + 1 },
    { start: issue.start + 1, end: issue.end + 1 }]) {
    const invalid = { ...issue, ...range };
    assert.throws(() => prepareContinuityRepair(chapter, invalid));
    assert.throws(() => parseContinuityRepair(response(invalid), chapter, invalid));
  }
});

test('empty evidence cannot produce an insertion outside audited text', () => {
  const { chapter, issue } = fixture();
  const invalid = { ...issue, quote: '', end: issue.start };
  assert.throws(() => prepareContinuityRepair(chapter, invalid));
  assert.throws(() => parseContinuityRepair(response(invalid), chapter, invalid));
});

test('batch repairs merge original ranges from the end without shifting the earlier patch', () => {
  const { chapter, issue } = fixture();
  const lastIssue = { ...issue, start: chapter.content.lastIndexOf(issue.quote) };
  lastIssue.end = lastIssue.start + lastIssue.quote.length;
  const first = parseContinuityRepair(response(issue, { replacement: '她二十岁。' }), chapter, issue);
  const last = parseContinuityRepair(response(lastIssue, { replacement: '她仍是二十岁。' }), chapter, lastIssue);
  for (const repairs of [[first, last], [last, first]]) {
    assert.equal(mergeContinuityRepairs(chapter, repairs), '先文：她今年二十岁。\n🙂她二十岁。\n后文：她仍是二十岁。');
  }
  assert.equal(mergeContinuityRepairs(chapter, []), chapter.content);
});

test('batch rejects duplicate and overlapping original ranges before applying anything', () => {
  const { chapter, issue } = fixture();
  const first = parseContinuityRepair(response(issue), chapter, issue);
  const overlap = { ...issue, start: issue.start + 1, quote: issue.quote.slice(1) };
  const second = parseContinuityRepair(response(overlap), chapter, overlap);
  assert.throws(() => mergeContinuityRepairs(chapter, [first, first]), /重复|重叠/);
  assert.throws(() => mergeContinuityRepairs(chapter, [second, first]), /重复|重叠/);
});

test('batch accepts adjacent ranges and ignores arbitrary full-content metadata', () => {
  const { chapter, issue } = fixture();
  const left = { ...issue, end: issue.start + 2, quote: issue.quote.slice(0, 2) };
  const right = { ...issue, start: issue.start + 2, quote: issue.quote.slice(2) };
  const patches = [parseContinuityRepair(response(left, { replacement: '主角' }), chapter, left),
    parseContinuityRepair(response(right, { replacement: '二十岁。' }), chapter, right)];
  patches[0].content = '随意改写整章';
  assert.equal(mergeContinuityRepairs(chapter, patches),
    chapter.content.slice(0, issue.start) + '主角二十岁。' + chapter.content.slice(issue.end));
});

test('batch revalidates protocol, identity, source digest, quote and range against current body', () => {
  const { chapter, issue } = fixture();
  const patch = parseContinuityRepair(response(issue), chapter, issue);
  for (const changes of [{ protocolVersion: 'other' }, { chapterId: 'other' }, { sourceDigest: 'old' },
    { quote: '她今年四十岁。' }, { start: patch.start + 1 }, { end: patch.end - 1 },
    { replacement: '' }, { replacement: patch.quote }]) {
    assert.throws(() => mergeContinuityRepairs(chapter, [{ ...patch, ...changes }]));
  }
  assert.throws(() => mergeContinuityRepairs({ ...chapter, content: chapter.content + '变化' }, [patch]));
});

const repairRequest = (): NovelModelRequest => ({ runId: 'exact-repair-run', projectId: 'book',
  systemPrompt: CONTINUITY_REPAIR_SYSTEM_PROMPT, maxOutputTokens: REPAIR_MAX_OUTPUT_TOKENS,
  modelTarget: { kind: 'global' }, toolProfile: 'none', history: [],
  operation: { kind: 'turn', userPrompt: '{}' }, checkpoint: async () => {},
});
const snapshot = (text: string): NovelModelEvent => ({ kind: 'snapshot', messages: [makeAssistantMessage(text)],
  generationActive: true, textDeltasLive: false, transport: 'live' });
const collectorFixture = (synchronous: NovelModelEvent[] = []) => {
  let callback: (event: NovelModelEvent) => void = () => {};
  let releases = 0;
  const requests: NovelModelRequest[] = [];
  const cancelled: string[] = [];
  const model: NovelModelRunning = { validate: async () => {}, cancel: id => { cancelled.push(id); },
    start: request => {
      requests.push(request);
      return { subscribe: cb => {
        callback = cb;
        synchronous.forEach(event => cb(event));
        return () => { releases += 1; };
      } };
    },
  };
  return { model, requests, cancelled, emit: (event: NovelModelEvent) => callback(event), releases: () => releases };
};

test('collector uses newest complete assistant snapshot, releases once and ignores late events', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const f = collectorFixture();
  const request = repairRequest();
  const handle = collectContinuityRepairText(f.model, request);
  assert.equal(f.requests[0], request);
  f.emit(snapshot('旧快照')); f.emit(snapshot('完整新快照')); f.emit({ kind: 'completed' });
  f.emit(snapshot('晚到快照')); f.emit({ kind: 'failed', message: '晚到失败' }); handle.cancel();
  t.mock.timers.tick(120_000);
  assert.equal(await handle.result, '完整新快照');
  assert.equal(f.releases(), 1); assert.deepEqual(f.cancelled, []);
});

test('collector safely releases subscriptions for synchronous completion and failure', async () => {
  const success = collectorFixture([snapshot('同步正文'), { kind: 'completed' }]);
  assert.equal(await collectContinuityRepairText(success.model, repairRequest()).result, '同步正文');
  assert.equal(success.releases(), 1);
  const failed = collectorFixture([{ kind: 'failed', message: '同步失败' }]);
  await assert.rejects(collectContinuityRepairText(failed.model, repairRequest()).result, /同步失败/);
  assert.equal(failed.releases(), 1);
});

test('collector cancellation rejects once, cancels exact run, releases and refuses partial/late output', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const f = collectorFixture();
  const handle = collectContinuityRepairText(f.model, repairRequest());
  f.emit(snapshot('未完成补丁'));
  handle.cancel(); handle.cancel(); f.emit(snapshot('晚到完成补丁')); f.emit({ kind: 'completed' });
  await assert.rejects(handle.result, /取消/);
  t.mock.timers.tick(120_000);
  assert.deepEqual(f.cancelled, ['exact-repair-run']); assert.equal(f.releases(), 1);
});

test('collector times out at exactly 120 seconds, cancels exact run and releases', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const f = collectorFixture();
  const handle = collectContinuityRepairText(f.model, repairRequest());
  t.mock.timers.tick(119_999);
  assert.equal(f.releases(), 0); assert.deepEqual(f.cancelled, []);
  t.mock.timers.tick(1);
  await assert.rejects(handle.result, /超时/);
  f.emit({ kind: 'completed' }); handle.cancel();
  assert.deepEqual(f.cancelled, ['exact-repair-run']); assert.equal(f.releases(), 1);
});

test('collector rejects failed events and unsupported user interaction, with timer cleanup', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  for (const event of [{ kind: 'failed', message: 'provider失败' }, { kind: 'waiting_user' }] as NovelModelEvent[]) {
    const f = collectorFixture();
    const handle = collectContinuityRepairText(f.model, repairRequest());
    f.emit(event);
    await assert.rejects(handle.result, event.kind === 'failed' ? /provider失败/ : /交互/);
    assert.equal(f.releases(), 1);
    assert.deepEqual(f.cancelled, event.kind === 'waiting_user' ? ['exact-repair-run'] : []);
    t.mock.timers.tick(120_000);
    assert.equal(f.releases(), 1);
    assert.deepEqual(f.cancelled, event.kind === 'waiting_user' ? ['exact-repair-run'] : []);
  }
});

test('collector rejects synchronous start/subscribe exceptions and clears pending timeout', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  for (const duringSubscribe of [false, true]) {
    const f = collectorFixture();
    f.model.start = () => {
      if (!duringSubscribe) throw new Error('start失败');
      return { subscribe: () => { throw new Error('subscribe失败'); } };
    };
    const handle = collectContinuityRepairText(f.model, repairRequest());
    await assert.rejects(handle.result, duringSubscribe ? /subscribe失败/ : /start失败/);
    const cancelCount = f.cancelled.length;
    t.mock.timers.tick(120_000);
    assert.equal(f.cancelled.length, cancelCount);
  }
});
