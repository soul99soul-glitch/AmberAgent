const test = require('node:test');
const assert = require('node:assert/strict');
const { loadChatModelContextSession } = require('./chat_model_context_fixture.cjs');

function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
const settle = () => new Promise(resolve => setImmediate(resolve));
const choice = id => ({ provider: { id: 'provider', name: 'Provider' }, model: { id, modelId: id, contextWindowTokens: 100 } });
const conversation = id => ({ id, assistantId: 'assistant', messageNodes: [] });
function harness({ providers, resolveChoice, readCompacts, nativeCounter, getAssistants, compact } = {}) {
  let current = conversation('one');
  const errors = [], toasts = [], estimates = [], nativeCalls = [];
  const domain = {
    currentMessages: conv => conv.messageNodes.flatMap(node => node.messages.slice(node.selectIndex, node.selectIndex + 1)),
    estimateConversationInputTokens: (messages, compacts) => { estimates.push({ messages, compacts }); return messages.length * 10 + compacts.length; },
    nativeTokenizerForModel: id => id ? 'native' : null,
    formatContextTokens: String, formatNumberInt: String,
    buildTopModelMenuGroups: providers => providers || [],
    loadProviders: providers || (() => Promise.resolve([])),
    reasoningLevelsForModel: () => [{ value: 'high', label: 'High' }],
    defaultReasoningLevelForModel: () => 'high',
    reasoningLevelForModel: assistant => assistant.level,
    withReasoningLevelForModel: (assistant, _id, level) => ({ ...assistant, level }),
  };
  const Session = loadChatModelContextSession(domain, (...args) => {
    nativeCalls.push(args);
    return nativeCounter ? nativeCounter(...args) : Promise.resolve(17);
  }, toast => toasts.push(toast));
  const session = new Session();
  session.start({
    currentConversation: () => current,
    error: message => errors.push(message),
    kvStore: {}, compactStore: { getCompacts: readCompacts || (() => Promise.resolve([])) },
    getAssistants: getAssistants || (() => Promise.resolve([{ id: 'assistant', level: 'high' }])),
    resolveChoice: resolveChoice || (() => Promise.resolve(choice('model'))),
    saveChoice: () => Promise.resolve(),
    mutateAssistant: (_id, mutate) => Promise.resolve(mutate({ id: 'assistant' })),
    compact: compact || (() => Promise.resolve({ compacts: [], summary: '', message: 'done' })),
  });
  return { session, errors, toasts, estimates, nativeCalls, current: () => current, setCurrent: value => { current = value; } };
}

for (const stage of ['providers', 'choice']) {
  test(`closed or hidden menu discards delayed ${stage} result`, async () => {
    for (const close of ['closeModelMenu', 'hide', 'dispose']) {
      const request = deferred();
      const h = stage === 'providers' ? harness({ providers: () => request.promise })
        : harness({ resolveChoice: () => request.promise });
      h.session.toggleModelMenu(); await settle();
      h.session[close]();
      request.resolve(stage === 'providers' ? [{ id: 'late' }] : choice('late'));
      await settle();
      assert.equal(h.session.modelMenuOpen, false, close);
      assert.equal(h.session.modelMenuLoading, false, close);
    }
  });
}

test('menu read failure releases loading and allows a later opening', async () => {
  let attempts = 0;
  const h = harness({ providers: () => ++attempts === 1 ? Promise.reject(new Error('read failed')) : Promise.resolve([]) });
  h.session.toggleModelMenu(); await settle();
  assert.equal(h.session.modelMenuLoading, false);
  h.session.toggleModelMenu(); await settle();
  assert.equal(h.session.modelMenuOpen, true);
});

test('native token counts reuse node/compact identity and reject stale conversation and model results', async () => {
  const requests = [];
  const h = harness({ nativeCounter: () => { const request = deferred(); requests.push(request); return request.promise; } });
  h.session.setContextModel(choice('first'));
  assert.equal(requests.length, 1);
  h.session.refreshNativeContextEstimate();
  assert.equal(requests.length, 1, 'history-window projection must reuse the canonical node reference');
  h.setCurrent(conversation('two'));
  h.session.refreshNativeContextEstimate();
  assert.equal(requests.length, 2);
  h.session.setContextModel(choice('second'));
  assert.equal(requests.length, 3);
  requests[0].resolve(1000); requests[1].resolve(2000); await settle();
  assert.equal(h.session.nativeContextTokens, null);
  requests[2].resolve(31); await settle();
  assert.equal(h.session.nativeContextTokens, 31);
  assert.equal(h.session.contextUsedTokens, 31);
  assert.equal(h.session.estimatedContextTokens(), 31);
});

test('snapshot compacts explicitly invalidate tokenizer and expose marker summary without owning timeline', async () => {
  const h = harness();
  h.session.setContextModel(choice('model')); await settle();
  const compacts = [{ status: 'completed', sourceMessageIds: ['a', 'b'], summary: 'summary' }];
  const snapshot = { conversation: h.current(), contextCompacts: compacts, compactActiveCount: 0,
    contextWindowTokens: 120, modelLabel: 'provider/model' };
  h.session.applyRunSnapshot(snapshot); await settle();
  assert.equal(h.nativeCalls.length, 2, 'class has no @Watch; assignment must explicitly refresh');
  assert.equal(h.nativeCalls[1][1], compacts, 'tokenizer receives the raw service array');
  h.session.applyRunSnapshot(snapshot); await settle();
  assert.equal(h.nativeCalls.length, 2, 'same raw compact array reuses cached count');
  assert.equal(h.session.compactBoundaryCount('a'), 0);
  assert.equal(h.session.compactBoundaryCount('b'), 2);
  h.session.showCompactSummary('b');
  assert.equal(h.session.compactSummary, 'summary'); assert.equal(h.session.compactSummaryOpen, true);
  const replacement = [{ ...compacts[0], summary: 'updated summary' }];
  h.session.applyRunSnapshot({ ...snapshot, contextCompacts: replacement }); await settle();
  assert.equal(h.nativeCalls.length, 3, 'a replaced compact must invalidate the count');
  h.session.showCompactSummary('b');
  assert.equal(h.session.compactSummary, 'updated summary');
  h.session.hide(); assert.equal(h.session.compactSummaryOpen, false);
});

test('text frames with copied compact arrays keep the same estimate and in-flight native count', async () => {
  for (const compacts of [[], [{ status: 'completed', sourceMessageIds: ['a'], summary: 'summary' }]]) {
    const requests = [];
    const h = harness({ nativeCounter: () => {
      const request = deferred(); requests.push(request); return request.promise;
    } });
    h.session.setContextModel(choice('model'));
    const snapshot = { conversation: h.current(), contextCompacts: compacts, compactActiveCount: 0,
      contextWindowTokens: 120, modelLabel: 'provider/model' };
    h.session.applyRunSnapshot(snapshot);
    const calls = h.nativeCalls.length;
    const estimates = h.estimates.length;
    for (let frame = 0; frame < 20; frame++) {
      // ConversationRunService.copySnapshot isolates arrays on every published frame.
      h.session.applyRunSnapshot({ ...snapshot, contextCompacts: compacts.slice() });
    }
    assert.equal(h.nativeCalls.length, calls, 'unchanged compacts must not restart native tokenization');
    assert.equal(h.estimates.length, estimates, 'text-only frames must not re-estimate the full conversation');
    requests.at(-1).resolve(31); await settle();
    assert.equal(h.session.nativeContextTokens, 31, 'the retained in-flight result must still apply');
    assert.equal(h.session.contextUsedTokens, 31);
    const updated = { ...h.current(), messageNodes: [{ selectIndex: 0, messages: [{ role: 'user', usage: null }] }] };
    h.setCurrent(updated);
    h.session.applyRunSnapshot({ ...snapshot, conversation: updated, contextCompacts: compacts.slice() });
    assert.equal(h.nativeCalls.length, calls + 1, 'a real history change still invalidates the count');
    assert.equal(h.estimates.length, estimates + 1);
  }
});

test('effective model and reasoning results cannot overwrite a later canonical/model choice', async () => {
  const choices = [], assistants = [];
  const h = harness({ resolveChoice: () => { const request = deferred(); choices.push(request); return request.promise; },
    getAssistants: () => { const request = deferred(); assistants.push(request); return request.promise; } });
  h.session.refreshEffectiveModelState();
  h.setCurrent(conversation('two'));
  h.session.refreshEffectiveModelState();
  choices[1].resolve(choice('new')); await settle();
  choices[0].resolve(choice('old')); await settle();
  assert.equal(h.session.modelLabel, 'Provider / new');
  h.session.refreshReasoningPicker(null);
  assistants[0].resolve([{ id: 'assistant', level: 'late' }]); await settle();
  assert.deepEqual(Array.from(h.session.reasoningLevels), []);
  assert.equal(h.session.currentReasoningLevel, '');
});

test('last-turn metrics follow the latest canonical selected branch and publish first-level UI fields', () => {
  const h = harness();
  const message = { role: 'assistant', usage: { totalTokens: 1500, promptTokens: 1000, cachedTokens: 500, completionTokens: 500 },
    createdAt: '2026-10-18T00:00:00Z', finishedAt: '2026-10-18T00:00:10Z' };
  h.current().messageNodes = [{ selectIndex: 1, messages: [{ role: 'user', usage: null }, message] }];
  h.session.refreshNativeContextEstimate();
  assert.equal(h.session.turnTotalText, '1,500 tok');
  assert.equal(h.session.turnCacheText, '50%');
  assert.equal(h.session.turnSpeedText, '50 tok/s');
  h.setCurrent(conversation('two')); h.session.refreshNativeContextEstimate();
  assert.equal(h.session.turnTotalText, '—'); assert.equal(h.session.contextUsedTokens, 0);
});

test('manual compaction starts synchronously and hidden completion updates context without opening sheet', async () => {
  const request = deferred(); let calls = 0;
  const h = harness({ compact: id => { assert.equal(id, 'one'); calls++; return request.promise; } });
  h.session.compactCurrentConversation(); h.session.compactCurrentConversation();
  assert.equal(calls, 1); assert.equal(h.session.manualCompacting, true);
  h.session.hide();
  request.resolve({ compacts: [], summary: 'hidden summary', message: 'done' }); await settle();
  assert.equal(h.session.compactSummaryOpen, false); assert.equal(h.toasts.length, 0);
  assert.equal(h.session.manualCompacting, false);
});

test('dispose rejects delayed native count while menu/context commands retain one lifecycle owner', async () => {
  const request = deferred(); const h = harness({ nativeCounter: () => request.promise });
  h.session.setContextModel(choice('model'));
  h.session.dispose(); request.resolve(999); await settle();
  assert.equal(h.session.nativeContextTokens, null);
});
