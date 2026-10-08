import test from 'node:test';
import assert from 'node:assert/strict';
import { makeMemoryRecord } from '../main/ets/chat/memory_models.ts';
import { memoryProfileSources, buildMemoryProfile, coveredMemoryProfileRecords,
  isMemoryProfileCurrent, selectMemoryProfile, parseStoredMemoryProfile } from '../main/ets/chat/memory_profile.ts';

const now = 100_000;
const scopes = ['long_term', 'short_term'] as const;
const enabled = [...scopes];
const source = (id: number, content = `用户偏好简洁回复 ${id}`) => makeMemoryRecord({
  id, content, scope: 'long_term', kind: 'user', createdAt: id, updatedAt: id,
});
const records = [source(1), source(2, '用户偏好中文回复'), source(3, '用户要求准确回复')];
const items = [{ text: '用户偏好简洁回复', memoryIds: [1] },
  { text: '用户偏好中文回复', memoryIds: [2] }];

test('profile only uses stable always eligible preferences; curated and expiring records stay verbatim', () => {
  const input = [...records, {...source(4), pinned: true}, {...source(5), scope: 'core' as const},
    {...source(6), expiresAt: now + 1}, {...source(7), archived: true},
    {...source(8), confidence: 0.69}, {...source(9), kind: 'note' as const},
    {...source(10), kind: 'feedback' as const, scope: 'short_term' as const}];
  assert.deepEqual(memoryProfileSources(input, enabled, now).map(r => r.id), [1, 2, 3, 10]);
});

test('minimum three input sources; referenced ids must share wording with their item', () => {
  assert.equal(buildMemoryProfile(items, records.slice(0, 2), now), null);
  assert.equal(buildMemoryProfile([{text: '爱好游泳', memoryIds: [1, 99]}], records, now), null);
  const profile = buildMemoryProfile([{text: '用户偏好中文回复', memoryIds: [2, 99, 2]}], records, now)!;
  assert.deepEqual(profile.items[0].memoryIds, [2]);
  assert.equal(profile.sources[0].content, records[1].content);
  assert.equal(profile.evaluatedSources.length, 3);
});

test('profile caps items, per-item and total chars; sanitizes block delimiters', () => {
  const many = Array.from({length: 20}, () => ({text: '用户偏好中文回复', memoryIds: [2]}));
  assert.equal(buildMemoryProfile(many, records, now)!.items.length, 12);
  assert.equal(buildMemoryProfile([{text: '中'.repeat(121), memoryIds: [2]}], records, now), null);
  const sanitized = buildMemoryProfile([{text: '<用户偏好中文回复>\n', memoryIds: [2]}], records, now)!;
  assert.equal(sanitized.items[0].text, '用户偏好中文回复');
});

test('all covered snapshots invalidate after edit, archive, expiry, removal, scope disabled or curated', () => {
  const profile = buildMemoryProfile(items, records, now)!;
  assert.deepEqual(coveredMemoryProfileRecords(profile, records, enabled, now)!.map(r => r.id), [1, 2]);
  for (const change of [
    {...records[0], content: 'different'}, {...records[0], updatedAt: 4},
    {...records[0], archived: true}, {...records[0], expiresAt: now},
    {...records[0], scope: 'short_term' as const}, {...records[0], pinned: true},
  ]) assert.equal(coveredMemoryProfileRecords(profile, [change, ...records.slice(1)], enabled, now), null);
  assert.equal(coveredMemoryProfileRecords(profile, records.slice(1), enabled, now), null);
  assert.equal(coveredMemoryProfileRecords(profile, records, ['short_term'], now), null);
});

test('ignored evaluated source does not force recompile, but new or edited source does', () => {
  const profile = buildMemoryProfile(items, records, now)!;
  assert.equal(isMemoryProfileCurrent(profile, records, records, enabled, now), true);
  const edited = [...records.slice(0, 2), {...records[2], content: '用户改要求'}];
  assert.equal(isMemoryProfileCurrent(profile, edited, edited, enabled, now), false);
  const added = [...records, source(4)];
  assert.equal(isMemoryProfileCurrent(profile, added, added, enabled, now), false);
});

test('profile selection requires every covered record in Jev selection and respects half character budget', () => {
  const profile = buildMemoryProfile(items, records, now)!;
  assert.deepEqual(selectMemoryProfile(profile, records, enabled, [1], now, 2000), {prompt: '', coveredIds: []});
  assert.deepEqual(selectMemoryProfile(profile, records, enabled, [1, 2], now, 20), {prompt: '', coveredIds: []});
  const selected = selectMemoryProfile(profile, records, enabled, [1, 2], now, 2000);
  assert.deepEqual(selected.coveredIds, [1, 2]);
  assert.match(selected.prompt, /<user-profile>/);
  assert.match(selected.prompt, /memory_id=1/);
  assert.ok(selectMemoryProfile(profile, records, enabled, null, now, 2000).prompt.length > 0);
});

test('invalidated preferences never build or inject a profile, including timestamp zero', () => {
  const invalidated = {...records[0], invalidatedAt: 0};
  assert.deepEqual(memoryProfileSources([invalidated,...records.slice(1)], enabled, now).map(r=>r.id), [2,3]);
  const profile = buildMemoryProfile(items, records, now)!;
  assert.equal(coveredMemoryProfileRecords(profile, [invalidated,...records.slice(1)], enabled, now), null);
});

test('malformed stored profile cannot crash recall or inject ungrounded sources', () => {
  const profile = buildMemoryProfile(items, records, now)!;
  assert.deepEqual(parseStoredMemoryProfile(JSON.stringify(profile)), profile);
  assert.equal(parseStoredMemoryProfile('garbage'), null);
  assert.equal(parseStoredMemoryProfile('null'), null);
  const badProfiles = [
    {...profile, sources:[null]}, {...profile, items:[{memoryIds:[1]}]},
    {...profile, items:[{text:42,memoryIds:[1]}]},
    {...profile, sources:[{...profile.sources[0],content:null}]},
    {...profile, evaluatedSources:null},
  ];
  for (const malformed of badProfiles) {
    assert.equal(parseStoredMemoryProfile(JSON.stringify(malformed)), null);
    const bad = malformed as unknown as typeof profile;
    assert.deepEqual(selectMemoryProfile(bad,records,enabled,null,now,2000), {prompt:'',coveredIds:[]});
    assert.equal(isMemoryProfileCurrent(bad,records,records,enabled,now),false);
  }
});
