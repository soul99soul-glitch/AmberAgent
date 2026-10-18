const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');

function deferred() {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { promise, resolve };
}

function fixture() {
  const values = new Map();
  const writes = [];
  let nextPause = null;
  const pause = (operation, key) => {
    const entered = deferred();
    const released = deferred();
    nextPause = { operation, key, entered, released };
    return { entered: entered.promise, release: released.resolve };
  };
  const before = async (operation, key) => {
    const pending = nextPause;
    if (pending === null || pending.operation !== operation || pending.key !== key) return;
    nextPause = null;
    pending.entered.resolve();
    await pending.released.promise;
  };
  const kv = {
    async get(key) { await before('get', key); return values.get(key) ?? null; },
    async put(key, value) { await before('put', key); values.set(key, value); writes.push([key, value]); },
    async delete(key) { await before('delete', key); values.delete(key); },
  };
  const source = fs.readFileSync(path.join(__dirname, '../main/ets/platform_impl/EntryCompactStore.ets'), 'utf8');
  const code = ts.transpileModule(source, { compilerOptions: {
    module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022,
  } }).outputText;
  const exports = {};
  new Function('require', 'exports', code)(name => { throw new Error(`Unexpected import: ${name}`); }, exports);
  return { store: exports.createEntryCompactStore(kv), kv, values, writes, pause,
    deleteData: id => exports.deleteConversationCompactData(kv, id),
    clearDeleted: id => exports.clearConversationDeletedMarker(kv, id) };
}

function compact(conversationId, id = 'summary') {
  return { id, conversationId, summary: 'Summary of the old conversation messages', level: 1,
    sourceStartIndex: 0, sourceEndIndex: 1, sourceMessageIds: ['user', 'assistant'], tokenEstimate: 20,
    createdAt: 1, updatedAt: 1, status: 'completed' };
}

test('EntryCompactStore rejects a delayed summary after conversation invalidation', async () => {
  const f = fixture();
  const revision = await f.store.getRevision('conversation');
  await f.store.deleteByConversation('conversation');
  assert.equal(await f.store.insertCompact(compact('conversation'), revision), false);
  assert.deepEqual(await f.store.getCompacts('conversation'), []);
  assert.equal(f.values.has('compacts/conversation'), false);
});

test('EntryCompactStore checks queued summaries after synchronous revision invalidation', async () => {
  const f = fixture();
  const revision = await f.store.getRevision('conversation');
  const blocker = f.pause('get', 'compacts/conversation.events');
  const event = f.store.recordEvent('conversation', 'started', null, 'Pending previous write');
  await blocker.entered;
  const queuedSummary = f.store.insertCompact(compact('conversation', 'stale'), revision);
  const invalidation = f.store.deleteByConversation('conversation');
  blocker.release();
  await event;
  assert.equal(await queuedSummary, false, 'a queued stale generation must reject before writing');
  await invalidation;
  assert.equal(f.writes.some(([key]) => key === 'compacts/conversation'), false);
  assert.deepEqual(await f.store.getCompacts('conversation'), []);
});

test('EntryCompactStore new revision waits for pending invalidation and accepts fresh summaries', async () => {
  const f = fixture();
  const previous = await f.store.getRevision('conversation');
  assert.equal(await f.store.insertCompact(compact('conversation', 'old'), previous), true);
  const blocker = f.pause('delete', 'compacts/conversation');
  const invalidation = f.store.deleteByConversation('conversation');
  await blocker.entered;
  let observed = false;
  const revisionRead = f.store.getRevision('conversation').then(value => { observed = true; return value; });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(observed, false, 'fresh generation starts after queued invalidation has completed');
  blocker.release();
  await invalidation;
  const revision = await revisionRead;
  assert.ok(revision > previous);
  assert.equal(await f.store.insertCompact(compact('conversation', 'fresh'), revision), true);
  assert.deepEqual((await f.store.getCompacts('conversation')).map(value => value.id), ['fresh']);
});

test('EntryCompactStore invalidation only changes the affected conversation revision', async () => {
  const f = fixture();
  const firstRevision = await f.store.getRevision('first');
  const secondRevision = await f.store.getRevision('second');
  await f.store.deleteByConversation('first');
  assert.ok(await f.store.getRevision('first') > firstRevision);
  assert.equal(await f.store.getRevision('second'), secondRevision);
  assert.equal(await f.store.insertCompact(compact('second'), secondRevision), true);
  assert.equal((await f.store.getCompacts('second')).length, 1);
});

test('EntryCompactStore reports rejection if invalidation lands during KV publication', async () => {
  const f = fixture();
  const revision = await f.store.getRevision('conversation');
  const blocker = f.pause('put', 'compacts/conversation');
  const pendingSummary = f.store.insertCompact(compact('conversation'), revision);
  await blocker.entered;
  const invalidation = f.store.deleteByConversation('conversation');
  blocker.release();
  assert.equal(await pendingSummary, false, 'the stale generation cannot be reported as committed');
  await invalidation;
  assert.equal(f.values.has('compacts/conversation'), false);
});

test('EntryCompactStore deletion marker blocks summary and audit resurrection until explicit restoration', async () => {
  const f = fixture();
  const revision = await f.store.getRevision('conversation');
  await f.deleteData('conversation');
  assert.equal(await f.store.insertCompact(compact('conversation'), revision), false);
  await f.store.recordEvent('conversation', 'completed', 'summary', 'Late summary');
  assert.equal(f.values.has('compacts/conversation'), false);
  assert.equal(f.values.has('compacts/conversation.events'), false);
  assert.deepEqual(await f.store.getCompacts('conversation'), []);
  await f.clearDeleted('conversation');
  assert.equal(await f.store.insertCompact(compact('conversation', 'restored')), true);
  assert.deepEqual((await f.store.getCompacts('conversation')).map(value => value.id), ['restored']);
});

test('EntryCompactStore removes a summary whose KV publication races with permanent deletion', async () => {
  const f = fixture();
  const revision = await f.store.getRevision('conversation');
  const blocker = f.pause('put', 'compacts/conversation');
  const pendingSummary = f.store.insertCompact(compact('conversation'), revision);
  await blocker.entered;
  await f.deleteData('conversation');
  blocker.release();
  assert.equal(await pendingSummary, false);
  assert.equal(f.values.has('compacts/conversation'), false);
  assert.deepEqual(await f.store.getCompacts('conversation'), []);
});

test('EntryCompactStore follows invalidation queued while compaction is waiting for an older write', async () => {
  const { compactConversation } = await import('../../../chat/src/main/ets/chat/context_engine.ts');
  const { makeCompactPolicy, selectCompactsForInjection } = await import('../../../chat/src/main/ets/chat/context_compact.ts');
  const { makeConversation, toMessageNode, currentMessages } = await import('../../../chat/src/main/ets/chat/conversation.ts');
  const { makeUIMessage, toText } = await import('../../../chat/src/main/ets/chat/message.ts');
  const { editMessage } = await import('../../../chat/src/main/ets/chat/edit_message.ts');
  const f = fixture();
  const conversationOf = count => makeConversation('revision-race', Array.from({ length: count }, (_, index) =>
    toMessageNode({ ...makeUIMessage(index % 2 === 0 ? 'user' : 'assistant', [
      { type: 'text', text: index === 0 ? 'original branch fact' : 'neutral history', metadata: null },
    ]), id: `m${index}` })));
  const output = JSON.stringify({ schema_version: 2,
    timeline_summary: '目标已经确定。历史已经分析。方案已经选择。结果需要验证。',
    handoff_markdown: '## Goal\nOLD CARRIED FACT\n' + '继续执行已经确定的任务。'.repeat(20) });
  const emit = onChunk => onChunk({ id: 'chunk', model: 'model', usage: null, choices: [
    { index: 0, finishReason: null, message: null,
      delta: makeUIMessage('assistant', [{ type: 'text', text: output, metadata: null }]) },
  ] });
  const policy = makeCompactPolicy({ keepRecentTurns: 1, precompactRatio: 0, forceRatio: 100 });
  assert.equal((await compactConversation(conversationOf(8), policy, null, 'test', '', false, {
    store: f.store, newId: () => 'prior', provider: { streamText: async (_messages, onChunk) => emit(onChunk) },
  })).status, 'completed');

  const eventPause = f.pause('get', 'compacts/revision-race.events');
  const event = f.store.recordEvent('revision-race', 'started', null, 'Pending write A');
  await eventPause.entered;
  let revisionReadStarted = false;
  const originalGetRevision = f.store.getRevision.bind(f.store);
  f.store.getRevision = id => { revisionReadStarted = true; return originalGetRevision(id); };
  let providerStarted = false;
  let prompt = '';
  const providerEntered = deferred();
  const providerRelease = deferred();
  const pending = compactConversation(conversationOf(12), policy, null, 'test', '', false, {
    store: f.store, newId: () => 'late', provider: { streamText: async (messages, onChunk) => {
      prompt = toText(messages[0]);
      providerStarted = true;
      providerEntered.resolve();
      await providerRelease.promise;
      emit(onChunk);
    } },
  });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(revisionReadStarted, true, 'compaction is already waiting for write A');
  const deletePause = f.pause('delete', 'compacts/revision-race');
  const invalidation = f.store.deleteByConversation('revision-race');
  eventPause.release();
  await deletePause.entered;
  await new Promise(resolve => setImmediate(resolve));
  const providerStartedBeforeDelete = providerStarted;
  deletePause.release();
  await invalidation;
  await event;
  await providerEntered.promise;
  providerRelease.resolve();
  await pending;

  const edited = editMessage(conversationOf(12), 'm0', [
    { type: 'text', text: 'revised branch fact', metadata: null },
  ]);
  const saved = await f.store.getCompacts('revision-race');
  const injected = selectCompactsForInjection(saved, new Set(currentMessages(edited).map(message => message.id)));
  assert.equal(injected.some(value => value.summary.includes('OLD CARRIED FACT')), false,
    'the old carried fact must not survive invalidation as a new-revision incremental summary');
  assert.equal(providerStartedBeforeDelete, false, 'revision must wait for the new deletion tail B');
  assert.equal(prompt.includes('OLD CARRIED FACT'), false, 'planning reads the committed post-invalidation store');
});
