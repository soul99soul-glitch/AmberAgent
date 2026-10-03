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

test('failed generation commits the latest tail below checkpoint growth threshold', { timeout: 5000 }, async () => {
  const { domain, memory, service, providerFor } = await serviceFixture(['A']);
  const off = service.subscribe('A', () => {});
  const run = service.send('A', domain.makeUserMessage('failure question').parts);
  const rejected = assert.rejects(run.done, /provider terminal failure/);
  const request = await providerFor('A').next(0);
  request.emit('first'); await turn();
  assert.match(domain.toText(domain.currentMessages(await memory.getById('A')).at(-1)), /first/);
  request.emit(' latest'); request.fail(new Error('provider terminal failure'));
  await rejected;
  assert.equal(domain.toText(domain.currentMessages(await memory.getById('A')).at(-1)), 'first latest');
  assert.equal(service.snapshot('A').phase, 'failed');
  assert.match(service.snapshot('A').errorMsg, /provider terminal failure/);
  off();
});

function loadEntry(filename, imports, storage, cache = new Map()) {
  const full = path.resolve(entry, filename);
  if (cache.has(full)) return cache.get(full);
  const exports = {}; cache.set(full, exports);
  const source = ts.transpileModule(fs.readFileSync(full, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText;
  const requireEntry = name => {
    if (Object.prototype.hasOwnProperty.call(imports, name)) return imports[name];
    if (name === './EntryAbortController.ets' || name === './ConversationRunGuard.ets' || name === './GenerationProgressTracker.ets') {
      return loadEntry(name, imports, storage, cache);
    }
    throw new Error('Unexpected Entry dependency: ' + name);
  };
  vm.runInNewContext(source, { exports, require: requireEntry, AppStorage: storage,
    Error, Promise, Map, Set, Math, Number, JSON, String, Date, console, setTimeout, clearTimeout }, { filename: full });
  return exports;
}

async function serviceFixture(initialIds = ['A', 'B'], options = {}) {
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
          emitParts: parts => onChunk({ id: id + '-chunk-' + index, model: id + '-model', usage: null,
            choices: [{ index: 0, delta: domain.makeUIMessage('assistant', parts), message: null, finishReason: null }] }),
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
  const liveRuns = new Set();
  const assistantFor = id => domain.makeAssistant({ id, name: id + ' assistant', localTools: [], regexes: options.regexes ?? [] });
  const compactPort = { compactConversationNow: options.compact ?? (async () => ({
    status: 'completed', message: '已压缩', summary: '摘要', compacts: [], contextWindowTokens: 16000,
  })) };
  const documentPort = { materializeDocumentParts: options.documents ?? (async parts => parts),
    materializeConversationDocuments: async conversation => conversation };
  const imports = {
    '@amber/chat-domain': domain,
    '@kit.ArkTS': { util: { TextEncoder: { create: () => ({ encodeInto: text => new TextEncoder().encode(text) }) } } },
    '../di/AppContainer.ets': { getChatRepository: () => repository, getChatKvStore: () => kv,
      getChatAssistants: async () => [assistantFor('default')],
      getCompactStore: () => ({ getCompacts: async () => [], deleteByConversation: async () => {} }) },
    './BackgroundGenerationKeepAlive.ets': { reportGenerationProgress: (context, progress) => {
      if (progress.generationActive) liveRuns.add(progress.runId);
      else { liveRuns.delete(progress.runId); if (liveRuns.size === 0) stoppedKeepAlive.push(context); }
    } },
    './JevCompletionSupport.ets': { evaluateJevCompletion: options.completion ?? (async () => null) },
    './JevApprovalTriage.ets': { cancelJevApprovalTriage: () => {}, scheduleJevApprovalTriage: () => {} },
    './ContextCompactionSupport.ets': compactPort,
    './DocumentAttachmentSupport.ets': documentPort,
    './ConversationBrowserGuard.ets': { releaseConversationBrowserRun: () => {} },
    './NativePluginJsTransport.ets': { cancelNativePluginJsConversation: () => {} },
    './ConversationRunDependencies.ets': {
      prepareConversationSeed: async conversation => ({ runtime: {
        assistant: assistantFor(conversation.id),
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
      dispatcher: options.autoReviewFactory ? new domain.AgentToolDispatcher({ autoApprovalReview: options.autoReviewFactory(context, domain, kv) }) : undefined,
      autoApproveTools: options.autoReviewFactory !== undefined, autoApproveHighRiskTools: options.autoReviewFactory !== undefined,
      makeProviderForStep: () => providerFor(context.conversationId) }) },
    './ConversationRunAuxiliary.ets': { completeConversationAuxiliary: () => {} },
  };
  const entryCache = new Map();
  const { ConversationRunService } = loadEntry('ConversationRunService.ets', imports, storage, entryCache);
  const guard = loadEntry('ConversationRunGuard.ets', imports, storage, entryCache);
  const service = new ConversationRunService();
  return { domain, memory, kv, service, guard, values, generating, stoppedKeepAlive, providerFor, contexts, textOf, userCount, maximum, writeEvents, toolsByConversation,
    setSaveGate: value => { saveGate = value; }, setSaveFault: value => { saveFault = value; } };
}

test('manual compact excludes runs/restores and pending restore atomically takes only an undispatched item', { timeout: 5000 }, async () => {
  const gate = deferred();
  const { service, guard, domain, providerFor } = await serviceFixture(['A'], { compact: () => gate.promise });
  const off = service.subscribe('A', () => {});
  const compact = service.compact('A');
  assert.equal(service.isBusy('A'), true);
  assert.equal(guard.isConversationCompacting('A'), true);
  assert.throws(() => service.send('A', domain.makeUserMessage('blocked').parts), /仍在运行/);
  assert.throws(() => guard.beginConversationRestore(['A']), /仍在生成或恢复/);
  await assert.rejects(service.compact('A'), /生成或压缩/);
  await service.enqueue('A', domain.makeUserMessage('restore me').parts, true);
  const queued = service.pending('A')[0];
  const taken = await service.takePending('A', queued.id);
  assert.equal(taken.id, queued.id);
  assert.equal(await service.takePending('A', queued.id), null);
  assert.equal(service.pending('A').length, 0);
  gate.resolve({ status: 'completed', message: '已压缩', summary: 'visible summary', compacts: [], contextWindowTokens: 16000 });
  assert.equal((await compact).summary, 'visible summary');
  assert.equal(service.isBusy('A'), false);
  assert.equal(guard.isConversationCompacting('A'), false);
  assert.equal(service.snapshot('A').compactActiveCount, 0);
  assert.equal(providerFor('A').requests.length, 0);
  const releaseAuto = guard.beginConversationCompaction('A');
  await assert.rejects(service.compact('A'), /生成或压缩/);
  releaseAuto();
  off();
});

test('history admission processes user regex once and materializes documents for send, edit, append and STEER', { timeout: 5000 }, async () => {
  const domain = await import('../main/ets/index.ts');
  let imports = 0;
  const fixture = await serviceFixture(['A'], {
    regexes: [domain.makeAssistantRegex({ findRegex: 'foo', replaceString: 'foo!', affectingScope: ['user'] })],
    documents: async parts => parts.map(part => part.type === 'document' && part.url.startsWith('data:')
      ? { ...part, url: `file:///upload/${++imports}.txt` } : part),
  });
  const { service, memory, providerFor, contexts } = fixture;
  const off = service.subscribe('A', () => {});
  const raw = [...domain.makeUserMessage('foo').parts,
    { type: 'document', url: 'data:text/plain;base64,YQ==', fileName: 'a.txt', mime: 'text/plain', metadata: null }];
  const send = service.send('A', raw);
  const first = await providerFor('A').next(0);
  let saved = await memory.getById('A');
  assert.equal(domain.toText(domain.currentMessages(saved)[0]).trim(), 'foo!');
  assert.equal(domain.currentMessages(saved)[0].parts[1].url, 'file:///upload/1.txt');
  assert.equal(raw[0].text, 'foo'); assert.match(raw[1].url, /^data:/);
  first.emit('answer'); first.finish(); await send.done;
  const userNode = saved.messageNodes[0];
  const edited = service.edit('A', userNode.id, userNode.messages[0].id, 'foo');
  const second = await providerFor('A').next(1);
  saved = await memory.getById('A');
  const variant = saved.messageNodes[0].messages.at(-1);
  assert.equal(domain.toText(variant).trim(), 'foo!');
  assert.equal(variant.parts[1].url, 'file:///upload/1.txt');
  assert.equal(imports, 1);
  second.emit('edited answer'); second.finish(); await edited.done;
  await service.append('A', raw);
  const appended = domain.currentMessages(await memory.getById('A')).at(-1);
  assert.equal(domain.toText(appended).trim(), 'foo!'); assert.equal(appended.parts[1].url, 'file:///upload/2.txt');
  const active = service.send('A', domain.makeUserMessage('continue').parts);
  const third = await providerFor('A').next(2);
  await service.enqueue('A', raw, true, 'STEER');
  assert.equal(service.pending('A')[0].parts[0].text, 'foo');
  const steering = await contexts.get('A').at(-1).consumeSteer();
  assert.equal(domain.toText(steering[0]).trim(), 'foo!'); assert.equal(steering[0].parts[1].url, 'file:///upload/3.txt');
  assert.equal(service.pending('A').length, 0);
  await service.enqueue('A', raw, true);
  const followupId = service.pending('A')[0].id;
  third.emit('continued'); third.finish(); await active.done;
  const fourth = await providerFor('A').next(3);
  assert.equal(await service.takePending('A', followupId), null);
  const followed = domain.currentMessages(await memory.getById('A')).at(-1);
  assert.equal(domain.toText(followed).trim(), 'foo!'); assert.equal(followed.parts[1].url, 'file:///upload/4.txt');
  fourth.emit('final'); fourth.finish(); await turn();
  off();
});

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

const completedWrite = () => ({ type: 'tool', toolCallId: 'completed-write', toolName: 'file_write',
  input: '{"path":"code/app.ts","content":"changed"}', output: [{ type: 'text', text: '{"path":"code/app.ts"}', metadata: null }],
  approvalState: { type: 'approved' }, metadata: null });

test('completion notice is volatile, bound to the completed run/tail, and late old checks cannot overwrite a new run', { timeout: 5000 }, async () => {
  const pending = [];
  const f = await serviceFixture(['A'], { completion: async (conversation, signal) => {
    const gate = deferred(); pending.push({ conversation, signal, gate }); return gate.promise;
  } });
  const off = f.service.subscribe('A', () => {});
  const run = f.service.send('A', f.domain.makeUserMessage('modify').parts);
  const request = await f.providerFor('A').next(0);
  request.emitParts([completedWrite(), { type: 'text', text: '已完成', metadata: null }]); request.finish(); await run.done;
  assert.equal(pending.length, 1);
  const facts = f.domain.jevUnverifiedChanges(f.domain.currentMessages(pending[0].conversation));
  const savesBefore = f.writeEvents.length;
  pending[0].gate.resolve(facts); await turn();
  assert.equal(f.service.snapshot('A').completionNotice.runId, run.runId);
  assert.equal(f.writeEvents.length, savesBefore, 'notice must not save a captured conversation after run release');
  const next = f.service.send('A', f.domain.makeUserMessage('next').parts);
  assert.equal(f.service.snapshot('A').completionNotice, null);
  const nextRequest = await f.providerFor('A').next(1); nextRequest.emit('next final'); nextRequest.finish(); await next.done;
  const old = pending[1];
  if (old) { old.gate.resolve(facts); await turn(); }
  assert.equal(f.service.snapshot('A').completionNotice, null);
  off();
});

test('cancelled/failed provider run never schedules completion evaluation', { timeout: 5000 }, async () => {
  const calls = [];
  const f = await serviceFixture(['A'], { completion: async (...args) => { calls.push(args); return null; } });
  const off = f.service.subscribe('A', () => {});
  const run = f.service.send('A', f.domain.makeUserMessage('modify').parts);
  const rejected = assert.rejects(run.done, /failure/);
  const request = await f.providerFor('A').next(0); request.emitParts([completedWrite()]); request.fail(new Error('failure')); await rejected;
  assert.equal(calls.length, 0); assert.equal(f.service.snapshot('A').completionNotice, null);
  off();
});

for (const scenario of ['send', 'assistant regenerate']) {
  test(`actual service ${scenario} review sees saved current user/prefix through Entry provider, excludes future user`, { timeout: 5000 }, async () => {
    const states = [], initialSnapshots = []; let effects = 0;
    const f = await serviceFixture(['A'], { autoReviewFactory: (context, domain, kv) => {
      const support = loadEntry('JevAutoApprovalSupport.ets', { '@amber/chat-domain': domain,
        '../di/AppContainer.ets': { getChatKvStore: () => kv }, './JevSupport.ets': { jevEvaluatePurpose: async (_purpose, state) => {
          states.push(state);
          return { ok: true, shadow: false, reason: '', evaluation: { answers: {
            destructive: { kind: 'noul', probability: .9 }, exfiltration: { kind: 'noul', probability: 0 },
            offTask: { kind: 'noul', probability: 0 }, authorized: { kind: 'noul', probability: .9 } }, usage: null, model: 'judge' } };
        } } }, {});
      initialSnapshots.push(domain.currentMessages(context.conversation));
      return support.createEntryJevAutoApprovalReview(domain.currentMessages(context.conversation), () => domain.currentMessages(context.conversation));
    } });
    const d = f.domain;
    await d.saveJevSettings(f.kv, d.makeJevSettings({ mode: 'active', autoApproval: { mode: 'active', allowTaskText: true, allowToolMetadata: true } }));
    f.toolsByConversation.set('A', [d.makeAgentTool({ name: 'file_write', description: 'write', needsApproval: true,
      execute: async () => { effects++; return [{ type: 'text', text: 'done', metadata: null }]; } })]);
    const originalUser = d.toMessageNode(d.makeUserMessage('CURRENT_AUTHORIZED_REQUEST'));
    const regenerated = d.toMessageNode(d.makeUIMessage('assistant', [{ type: 'text', text: 'original answer', metadata: null }]));
    if (scenario !== 'send') await f.memory.save(d.makeConversation('A', [originalUser, regenerated,
      d.toMessageNode(d.makeUserMessage('FUTURE_UNRELATED_REQUEST')), d.toMessageNode(d.makeUIMessage('assistant', []))]));
    const off = f.service.subscribe('A', () => {});
    const run = scenario === 'send' ? f.service.send('A', d.makeUserMessage('CURRENT_AUTHORIZED_REQUEST').parts)
      : f.service.regenerate('A', regenerated.id);
    const request = await f.providerFor('A').next(0);
    request.emitParts([{ type: 'tool', toolCallId: 'write', toolName: 'file_write', input: '{"path":"allowed"}',
      output: [], metadata: null, approvalState: { type: 'auto' } }]); request.finish();
    const final = await f.providerFor('A').next(1); final.emit('complete'); final.finish(); await run.done;
    assert.equal(effects, 1); assert.equal(states.length, 1);
    assert.match(JSON.stringify(states[0].user_requests), /CURRENT_AUTHORIZED_REQUEST/);
    assert.doesNotMatch(JSON.stringify(states[0].user_requests), /FUTURE_UNRELATED_REQUEST/);
    if (scenario === 'send') assert.doesNotMatch(JSON.stringify(initialSnapshots[0]), /CURRENT_AUTHORIZED_REQUEST/);
    else assert.match(JSON.stringify(initialSnapshots[0]), /FUTURE_UNRELATED_REQUEST/);
    off();
  });
}

test('changing the selected user branch invalidates both pending and visible completion notices with the same final tail', { timeout: 5000 }, async () => {
  for (const resolveBeforeBranch of [false, true]) {
    const pending = [];
    const f = await serviceFixture(['A'], { completion: async (conversation, signal) => {
      const gate = deferred(); pending.push({ conversation, signal, gate }); return gate.promise;
    } });
    const off = f.service.subscribe('A', () => {});
    const run = f.service.send('A', f.domain.makeUserMessage('first task').parts);
    const request = await f.providerFor('A').next(0);
    request.emitParts([completedWrite(), { type: 'text', text: 'done', metadata: null }]); request.finish(); await run.done;
    const candidate = f.domain.jevUnverifiedChanges(f.domain.currentMessages(pending[0].conversation));
    if (resolveBeforeBranch) { pending[0].gate.resolve(candidate); await turn(); assert.ok(f.service.snapshot('A').completionNotice); }
    await f.service.mutateStoredConversation('A', latest => ({ ...latest, messageNodes: latest.messageNodes.map((node, index) => index === 0
      ? { ...node, messages: [...node.messages, f.domain.makeUserMessage('different task')] } : node) }));
    const stored = await f.memory.getById('A');
    await f.service.mutateStoredConversation('A', latest => f.domain.selectMessageBranch(latest, stored.messageNodes[0].id, 1));
    if (!resolveBeforeBranch) {
      assert.equal(pending[0].signal.aborted, true, 'branch change aborts the pending assessment');
      pending[0].gate.resolve(candidate); await turn();
    }
    assert.equal(f.service.snapshot('A').completionNotice, null);
    assert.equal(f.domain.currentMessages(await f.memory.getById('A')).at(-1).id, candidate.finalMessageId);
    off();
  }
});

test('a truly pending completion result from the previous run is aborted and cannot apply during the new run', { timeout: 5000 }, async () => {
  const pending = [];
  const f = await serviceFixture(['A'], { completion: async (conversation, signal) => {
    const gate = deferred(); pending.push({ conversation, signal, gate }); return gate.promise;
  } });
  const off = f.service.subscribe('A', () => {});
  const first = f.service.send('A', f.domain.makeUserMessage('first').parts);
  const request = await f.providerFor('A').next(0);
  request.emitParts([completedWrite(), { type: 'text', text: 'done', metadata: null }]); request.finish(); await first.done;
  const old = pending[0]; const candidate = f.domain.jevUnverifiedChanges(f.domain.currentMessages(old.conversation));
  const second = f.service.send('A', f.domain.makeUserMessage('second').parts);
  assert.equal(old.signal.aborted, true);
  old.gate.resolve(candidate); await turn();
  assert.equal(f.service.snapshot('A').runId, second.runId); assert.equal(f.service.snapshot('A').completionNotice, null);
  const next = await f.providerFor('A').next(1); next.emit('second response'); next.finish(); await second.done;
  assert.equal(f.service.snapshot('A').completionNotice, null);
  off();
});
