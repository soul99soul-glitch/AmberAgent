import { test } from 'node:test';
import assert from 'node:assert/strict';
import { NovelPlanDraftStore, sameControlPlanDraft } from '../main/ets/novel/NovelPlanDraftState';
import { composerOwner } from '../main/ets/novel/NovelComposerState';

const draft = { thisChapterPlan: '未应用本章计划', futurePlan: '后续走向', preferences: '第一人称' };
const deferred = () => {
  let resolve!: () => void;
  const promise = new Promise<void>(done => { resolve = done; });
  return { promise, resolve };
};

test('a fresh store restores all three fields by project and branch without changing formal settings', async () => {
  const values = new Map<string, string>();
  const storage = { get: async <T>(key: string, fallback: T): Promise<T> => (values.get(key) ?? fallback) as T,
    set: async <T>(key: string, value: T): Promise<void> => { values.set(key, value as string); } };
  const owner = composerOwner('project', 'branch');
  const store = new NovelPlanDraftStore(storage);
  await store.save(owner, draft);
  assert.deepEqual(await new NovelPlanDraftStore(storage).load(owner), draft);
  assert.equal(await store.load(composerOwner('other-project', 'branch')), null);
  assert.equal(await store.load(composerOwner('project', 'other-branch')), null);
  assert.equal([...values.keys()].every(key => key.startsWith('novel.plan-draft.')), true);
  await store.save(owner, null);
  assert.equal(await new NovelPlanDraftStore(storage).load(owner), null);
});

test('reopening waits for the old page write and save-then-clear cannot resurrect an applied draft', async () => {
  let value = ''; const firstWrite = deferred(); let writes = 0;
  const store = new NovelPlanDraftStore({
    get: async <T>(_key: string, fallback: T): Promise<T> => (value || fallback) as T,
    set: async <T>(_key: string, next: T): Promise<void> => {
      if (++writes === 1) await firstWrite.promise;
      value = next as string;
    },
  });
  const saving = store.save('owner', draft);
  let loaded = false;
  const reading = store.load('owner').then(result => { loaded = true; return result; });
  await Promise.resolve(); assert.equal(loaded, false);
  firstWrite.resolve(); await saving;
  assert.deepEqual(await reading, draft);
  const newer = store.save('owner', { ...draft, thisChapterPlan: '新版' });
  const clearing = store.save('owner', null);
  await newer; await clearing;
  assert.equal(await store.load('owner'), null);
});

test('storage failure is observable and a retry persists the author input', async () => {
  let fail = true; let value = '';
  const store = new NovelPlanDraftStore({ get: async <T>(_key: string, fallback: T): Promise<T> => (value || fallback) as T,
    set: async <T>(_key: string, next: T): Promise<void> => { if (fail) throw new Error('disk full'); value = next as string; } });
  await assert.rejects(store.save('owner', draft), /disk full/);
  fail = false; await store.save('owner', draft);
  assert.deepEqual(await store.load('owner'), draft);
});

test('malformed saved drafts report an error instead of becoming editable empty data', async () => {
  for (const value of ['{', 'null', '{}', '{"thisChapterPlan":42,"futurePlan":"","preferences":""}']) {
    const store = new NovelPlanDraftStore({ get: async <T>(): Promise<T> => value as T, set: async () => {} });
    await assert.rejects(store.load('owner'));
  }
  assert.equal(sameControlPlanDraft(draft, { ...draft }), true);
  assert.equal(sameControlPlanDraft(draft, { ...draft, preferences: '第三人称' }), false);
});
