// KvResponseResumeStore tests

import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  createKvResponseResumeStore, responsesResumeKey,
} from '../main/ets/chat/openai_responses_resume_store.ts';
import { createMemoryKeyValueStore } from '../main/ets/chat/kv_store.ts';

test('resume store save/load/clear roundtrip', async () => {
  const kv = createMemoryKeyValueStore();
  const store = createKvResponseResumeStore(kv);
  assert.equal(await store.load('run-1'), null);
  await store.save('run-1', 'resp_abc', 7, 'prov-1');
  const cursor = await store.load('run-1');
  assert.deepEqual(cursor, { responseId: 'resp_abc', sequence: 7, providerId: 'prov-1' });
  await store.clear('run-1');
  assert.equal(await store.load('run-1'), null);
});

test('resume store: corrupt JSON → null (not throw)', async () => {
  const kv = createMemoryKeyValueStore();
  await kv.put(responsesResumeKey('bad'), 'not-json');
  const store = createKvResponseResumeStore(kv);
  assert.equal(await store.load('bad'), null);
});

test('resume store: incomplete object → null', async () => {
  const kv = createMemoryKeyValueStore();
  await kv.put(responsesResumeKey('bad2'), JSON.stringify({ responseId: 1 }));
  const store = createKvResponseResumeStore(kv);
  assert.equal(await store.load('bad2'), null);
});
