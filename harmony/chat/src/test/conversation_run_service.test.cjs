// Actual Entry service/controller/restore guard and Chat engine; only host ports are controlled.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const root = path.resolve(__dirname, '../../..');
const entry = path.join(root, 'entry/src/main/ets/platform_impl');
const clone = value => JSON.parse(JSON.stringify(value));
const deferred = () => {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};
const turn = () => new Promise(resolve => setImmediate(resolve));

function loadEntry(filename, imports, storage, cache = new Map()) {
  const full = path.resolve(entry, filename);
  if (cache.has(full)) return cache.get(full);
  const exports = {}; cache.set(full, exports);
  const source = ts.transpileModule(fs.readFileSync(full, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText;
  const requireEntry = name => {
    if (Object.prototype.hasOwnProperty.call(imports, name)) return imports[name];
    if (name === './EntryAbortController.ets' || name === './ConversationRunGuard.ets') {
      return loadEntry(name, imports, storage, cache);
    }
    throw new Error('Unexpected Entry dependency: ' + name);
  };
  vm.runInNewContext(source, { exports, require: requireEntry, AppStorage: storage,
    Error, Promise, Map, Set, Math, Number, JSON, String, Date, console, setTimeout, clearTimeout }, { filename: full });
  return exports;
}

async function serviceFixture(initialIds = ['A', 'B']) {
  const domain = await import('../main/ets/index.ts');
  const memory = domain.createMemoryConversationRepository();
  const kv = domain.createMemoryKeyValueStore();
  for (const id of initialIds) await memory.save(domain.makeConversation(id));
  const values = new Map([['abilityContext', {}]]), generating = [], stoppedKeepAlive = [];
  const storage = { get: key => values.get(key), setOrCreate: (key, value) => {
    values.set(key, value); if (key === 'chatGenerating') generating.push(value);
  } };
  const textOf = conversation => domain.currentMessages(conversation).map(message => message.parts
    .filter(part => part.type === 'text').map(part => part.text).join('')).join('|');
  const userCount = (conversation, text) => domain.currentMessages(conversation).filter(message =>
    message.role === 'user' && message.parts.some(part => part.type === 'text' && part.text === text)).length;
  const writeEvents = [], writing = new Map(), maximum = new Map();
  let saveGate = null, saveFault = null;
  const repository = { ...memory, save: async conversation => {
    const value = clone(conversation), id = value.id;
    const active = (writing.get(id) ?? 0) + 1; writing.set(id, active);
    maximum.set(id, Math.max(maximum.get(id) ?? 0, active));
    writeEvents.push({ event: 'start', id, text: textOf(value) });
    try {
      if (saveGate?.matches(value)) { const gate = saveGate; saveGate = null; gate.entered.resolve(); await gate.release.promise; }
      if (saveFault?.matches(value)) { const fault = saveFault; saveFault = null; throw new Error(fault.message); }
      await memory.save(value); writeEvents.push({ event: 'committed', id, text: textOf(value) });
    } catch (error) { writeEvents.push({ event: 'rejected', id, text: textOf(value) }); throw error; }
    finally { writing.set(id, active - 1); }
  } };
  const providers = new Map(), contexts = new Map(), toolsByConversation = new Map();
  const providerFor = id => {
    if (providers.has(id)) return providers.get(id);
    const requests = [], waiters = new Map();
    const provider = {
      requests,
      next: index => { if (requests[index]) return Promise.resolve(requests[index]);
        const waiting = deferred(); waiters.set(index, waiting); return waiting.promise; },
      streamText: (messages, onChunk, opts) => {
        const completed = deferred(); const index = requests.length;
        const request = { messages: clone(messages), signal: opts.signal, done: completed.promise,
          emit: text => onChunk({ id: id + '-chunk-' + index, model: id + '-model', usage: null,
            choices: [{ index: 0, delta: domain.makeAssistantMessage(text), message: null, finishReason: null }] }),
          finish: () => { opts.onDataEnd?.(); completed.resolve(); },
          fail: error => completed.reject(error) };
        requests.push(request); waiters.get(index)?.resolve(request);
        const abort = () => completed.reject(new Error(id + ' request cancelled'));
        opts.signal.addEventListener('abort', abort);
        return completed.promise.finally(() => opts.signal.removeEventListener('abort', abort));
      },
    };
    providers.set(id, provider); return provider;
  };
  const imports = {
    '@amber/chat-domain': domain,
    '../di/AppContainer.ets': { getChatRepository: () => repository, getChatKvStore: () => kv,
      getCompactStore: () => ({ getCompacts: async () => [], deleteByConversation: async () => {} }) },
    './BackgroundGenerationKeepAlive.ets': { stopGenerationKeepAlive: async context => { stoppedKeepAlive.push(context); } },
    './ConversationBrowserGuard.ets': { releaseConversationBrowserRun: () => {} },
    './NativePluginJsTransport.ets': { cancelNativePluginJsConversation: () => {} },
    './ConversationRunDependencies.ets': {
      prepareConversationSeed: async conversation => ({ runtime: {
        assistant: domain.makeAssistant({ name: conversation.id + ' assistant', localTools: [] }),
        params: { model: { modelId: conversation.id + '-model' } }, modelContextWindowTokens: 16000 } }),
      buildConversationTurnDeps: context => {
        const previous = contexts.get(context.conversationId) ?? []; previous.push(context); contexts.set(context.conversationId, previous);
        return { assistant: context.seed.runtime.assistant, inputTransformers: [], outputTransformers: [],
          provider: providerFor(context.conversationId), abortSignal: context.signal,
          store: { save: conversation => context.save(conversation) }, flushIntervalMs: 0,
          retrySetting: domain.makeGenerationRetrySetting({ enabled: false }) };
      },
    },
    './ConversationRunTools.ets': { buildConversationToolLoop: async context => ({ tools: toolsByConversation.get(context.conversationId) ?? [],
      makeProviderForStep: () => providerFor(context.conversationId) }) },
    './ConversationRunAuxiliary.ets': { completeConversationAuxiliary: () => {} },
  };
  const entryCache = new Map();
  const { ConversationRunService } = loadEntry('ConversationRunService.ets', imports, storage, entryCache);
  const guard = loadEntry('ConversationRunGuard.ets', imports, storage, entryCache);
  const service = new ConversationRunService();
  return { domain, memory, service, guard, values, generating, stoppedKeepAlive, providerFor, contexts, textOf, userCount, maximum, writeEvents, toolsByConversation,
    setSaveGate: value => { saveGate = value; }, setSaveFault: value => { saveFault = value; } };
}

test('actual service isolates A/B runs and admission, awaits durable checkpoints, and never requeues committed input', { timeout: 5000 }, async () => {
  const { domain, memory, service, guard, values, generating, stoppedKeepAlive, providerFor, contexts, textOf, userCount, maximum, writeEvents, setSaveGate, setSaveFault } = await serviceFixture();
  const snapshotsA = [], snapshotsB = [];
  const offA = service.subscribe('A', value => snapshotsA.push(clone(value)));
  const offB = service.subscribe('B', value => snapshotsB.push(clone(value)));
  assert.equal(snapshotsA[0].conversationId, 'A'); assert.equal(snapshotsB[0].sending, false);
  const parts = text => domain.makeUserMessage(text).parts;
  const a = service.send('A', parts('input-A')), b = service.send('B', parts('input-B'));
  assert.equal(service.activeCount(), 2); assert.equal(values.get('chatGenerating'), true);
  assert.equal(guard.hasActiveConversationRuns(['A', 'B']), true);
  assert.throws(() => guard.beginConversationRestore(['A']), /仍在生成或恢复/);
  assert.throws(() => service.send('A', parts('duplicate-A')), /仍在运行/);
  const [requestA, requestB] = await Promise.all([providerFor('A').next(0), providerFor('B').next(0)]);
  assert.equal(service.snapshot('A').modelLabel, 'A-model'); assert.equal(service.snapshot('B').modelLabel, 'B-model');
  const gate = { matches: value => value.id === 'A' && textOf(value).includes('partial-A'), entered: deferred(), release: deferred() };
  setSaveGate(gate); requestA.emit('partial-A'); await gate.entered.promise;
  requestB.emit('partial-B'); await turn();
  assert.match(textOf(await memory.getById('B')), /partial-B/);
  let aFinished = false; a.done.then(() => { aFinished = true; });
  service.cancel('B', a.runId); assert.equal(requestB.signal.aborted, false);
  service.cancel('A', a.runId); await turn();
  assert.equal(aFinished, false); assert.equal(requestA.signal.aborted, true); assert.equal(requestB.signal.aborted, false);
  assert.equal(service.isBusy('A'), true); assert.equal(values.get('chatGenerating'), true);
  gate.release.resolve(); await a.done;
  assert.match(textOf(await memory.getById('A')), /partial-A/);
  assert.equal(service.snapshot('A').phase, 'cancelled'); assert.equal(service.activeCount(), 1);
  assert.equal(guard.hasActiveConversationRuns(['A']), false); assert.equal(guard.hasActiveConversationRuns(['B']), true);
  assert.equal(service.snapshot('B').sending, true); assert.equal(values.get('chatGenerating'), true); assert.equal(stoppedKeepAlive.length, 0);

  const oldContext = contexts.get('A')[0];
  const secondA = service.send('A', parts('save-fault-A'));
  const failedDone = assert.rejects(secondA.done, /actual repository save failure/);
  const secondRequest = await providerFor('A').next(1);
  oldContext.publish({ errorMsg: 'stale-A', conversationId: 'B' }); requestA.emit('late-A');
  assert.notEqual(service.snapshot('A').errorMsg, 'stale-A'); assert.notEqual(service.snapshot('B').errorMsg, 'stale-A');
  await assert.rejects(oldContext.save(await memory.getById('B')), /不匹配|已结束/);
  service.cancel('A', a.runId); assert.equal(secondRequest.signal.aborted, false);
  setSaveFault({ matches: value => value.id === 'A' && textOf(value).includes('fault-output'), message: 'actual repository save failure' });
  secondRequest.emit('fault-output'); await failedDone;
  assert.equal(service.snapshot('A').phase, 'failed'); assert.match(service.snapshot('A').errorMsg, /actual repository save failure/);
  assert.equal(service.activeCount(), 1); assert.equal(requestB.signal.aborted, false);
  assert.equal(maximum.get('A'), 1); assert.equal(maximum.get('B'), 1);
  for (const snapshot of snapshotsA) assert.equal(snapshot.conversationId, 'A');
  for (const snapshot of snapshotsB) {
    assert.equal(snapshot.conversationId, 'B');
    if (snapshot.conversation) assert.doesNotMatch(textOf(snapshot.conversation), /input-A|save-fault-A|late-A/);
  }
  await service.mutateStoredConversation('B', latest => domain.patchConversation(latest, { isPinned: true }));
  assert.equal((await memory.getById('B')).isPinned, true);
  requestB.finish(); await b.done;
  assert.match(textOf(await memory.getById('B')), /partial-B/);
  assert.equal((await memory.getById('B')).isPinned, true);
  assert.equal(service.activeCount(), 0); assert.equal(values.get('chatGenerating'), false); assert.equal(stoppedKeepAlive.length, 1);

  // The queued input is durably committed before a real provider rejection; it must not be returned to the queue.
  await service.enqueue('A', parts('queued-once'), true);
  const queuedRequest = await providerFor('A').next(2);
  assert.equal(userCount(await memory.getById('A'), 'queued-once'), 1);
  queuedRequest.fail(new Error('controlled provider failure after input commit')); await turn();
  assert.equal(service.isBusy('A'), false); assert.equal(service.pending('A').length, 0);
  await service.resumePending('A'); await turn();
  assert.equal(providerFor('A').requests.length, 3); assert.equal(userCount(await memory.getById('A'), 'queued-once'), 1);
  assert.ok(writeEvents.some(event => event.event === 'rejected' && event.id === 'A'));

  // A genuinely new page seed is consumed once; an existing retained snapshot cannot resurrect a deleted ID.
  const fresh = domain.makeConversation('fresh'); const offFresh = service.subscribe('fresh', () => {});
  service.seedNewConversation(fresh); assert.throws(() => service.seedNewConversation(fresh), /已登记|正在运行/);
  const first = service.send('fresh', parts('first-input'));
  const freshRequest = await providerFor('fresh').next(0);
  assert.equal(userCount(await memory.getById('fresh'), 'first-input'), 1);
  freshRequest.emit('first-result'); freshRequest.finish(); await first.done;
  await memory.delete('fresh');
  await assert.rejects(service.send('fresh', parts('must-not-resurrect')).done, /不存在或已删除/);
  assert.equal(await memory.getById('fresh'), null); assert.equal(providerFor('fresh').requests.length, 1);
  offA(); offB(); offFresh();
  assert.equal(service.snapshot('A'), null); assert.equal(service.snapshot('B'), null); assert.equal(service.snapshot('fresh'), null);
  assert.deepEqual(generating.slice(0, 2), [true, false]);
});

test('focused active projection shows regenerated user output once across real engine checkpoints', { timeout: 5000 }, async () => {
  const { domain, memory, service, providerFor, textOf } = await serviceFixture([]);
  const violations = [], evidence = [];
  const inspect = snapshot => {
    if (!snapshot.sending || !snapshot.conversation || !textOf(snapshot.conversation).includes('user-live')) return;
    // Fallback is the current pre-fix Page projection; the fixed Service supplies a separate stable base.
    const visual = snapshot.visualConversation ?? domain.patchConversation(snapshot.conversation, {
      messageNodes: snapshot.conversation.messageNodes.slice(0, snapshot.historyNodeCount + (snapshot.regenNodeId ? 0 : 1)) });
    const tail = snapshot.streamingTail.concat(snapshot.regenTail ? [snapshot.regenTail] : []);
    const markerCount = domain.currentMessages(visual).concat(tail).filter(message => message.parts.some(part => part.type === 'text' && part.text.includes('user-live'))).length;
    const facts = { visualIds: visual.messageNodes.map(node => node.id), canonicalIds: snapshot.conversation.messageNodes.map(node => node.id),
      markerCount, stableBase: snapshot.visualConversation !== undefined && snapshot.visualConversation !== null };
    evidence.push(facts);
    if (!facts.stableBase) violations.push('active visual baseline is missing');
    if (facts.visualIds.join(',') !== 'user-target') violations.push('active baseline changed after checkpoint');
    if (textOf(visual).includes('user-live')) violations.push('checkpoint content entered active baseline');
    if (markerCount !== 1) violations.push('generated content appears ' + markerCount + ' times');
  };
  const node = (role, text, id) => domain.makeMessageNode([domain.makeUIMessage(role, domain.makeUserMessage(text).parts)], 0, id);
  await memory.save(domain.makeConversation('projection-user', [node('user', 'target', 'user-target'), node('assistant', 'future', 'future-node')]));
  const offUser = service.subscribe('projection-user', inspect);
  const user = service.regenerate('projection-user', 'user-target'); const userRequest = await providerFor('projection-user').next(0);
  userRequest.emit('user-live'); await turn(); userRequest.emit('x'.repeat(512)); await turn();
  assert.ok(service.snapshot('projection-user').conversation.messageNodes.length > 1);
  userRequest.finish(); await user.done; offUser();
  assert.ok(evidence.length >= 2, 'Observe more than one actual checkpoint publication');
  assert.match(textOf(await memory.getById('projection-user')), /user-live/);
  assert.deepEqual([...new Set(violations)], [], JSON.stringify(evidence));
});
