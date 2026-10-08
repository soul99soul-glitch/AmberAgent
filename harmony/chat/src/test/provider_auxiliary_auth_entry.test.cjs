// Run actual Entry factories with platform ports replaced; requests use the real protocol adapters.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const entry = path.resolve(__dirname, '../../../entry/src/main/ets');

const evaluate = (relative, names, host) => {
  const filename = path.join(entry, relative);
  const source = fs.readFileSync(filename, 'utf8');
  const file = ts.createSourceFile(filename, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
  const picked = file.statements.filter(statement => {
    if (ts.isVariableStatement(statement)) return statement.declarationList.declarations.some(
      declaration => names.includes(declaration.name.getText(file)));
    return ts.isClassDeclaration(statement) && names.includes(statement.name?.text);
  }).map(statement => statement.getText(file)).join('\n');
  const exported = names.map(name => `exports.${name} = ${name};`).join('\n');
  vm.runInNewContext(ts.transpileModule(`${picked}\n${exported}`, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, host);
  for (const name of names) host[name] = host.exports[name];
};

const harness = async mode => {
  const domain = await import('../main/ets/index.ts');
  const requests = [], refreshed = [];
  const text = JSON.stringify({ schema_version: 2, timeline_summary: '已确定目标和执行方案。'.repeat(8),
    handoff_markdown: '## Goal\n' + '保留目标和工作状态。'.repeat(20) });
  const response = request => request.url.endsWith('/responses')
    ? { id: 'r', model: 'fixture', status: 'completed', output: [
      { type: 'message', content: [{ type: 'output_text', text }] }] }
    : { id: 'c', model: 'fixture', choices: [{ index: 0, message: { role: 'assistant', content: text }, finish_reason: 'stop' }] };
  const http = {
    fetch: async request => { requests.push(request); return { status: 200, headers: {}, body: JSON.stringify(response(request)) }; },
    fetchStream: async (request, opts) => {
      requests.push(request);
      const payload = request.url.endsWith('/responses')
        ? `event: response.completed\ndata: ${JSON.stringify({ type: 'response.completed', response: response(request) })}\n\n`
        : `data: ${JSON.stringify({ id: 'c', model: 'fixture', choices: [{ index: 0, delta: { role: 'assistant', content: text }, finish_reason: 'stop' }] })}\n\ndata: [DONE]\n\n`;
      const bytes = new TextEncoder().encode(payload); opts.onChunk(bytes.buffer, true);
      return { status: 200, headers: {}, body: '' };
    },
  };
  const model = domain.makeProviderModel({ id: 'model', modelId: 'fixture', abilities: ['reasoning'], customHeaders: [
    { name: 'X-Model', value: 'task-header' }], customBodies: [{ key: 'task_body', value: 'task-body' }] });
  const provider = domain.makeProviderSettingOpenAIVariant({ id: 'provider', apiKey: 'stale', authMode: mode,
    baseUrl: 'https://fixture.test/v1', useResponseApi: mode === 'codex_oauth', models: [model] });
  const choice = { provider, model, providerContainerId: provider.id };
  const store = domain.createMemoryCompactStore();
  const worker = { enabled: true, dreamMaintenanceEnabled: true, dreamModelEnabled: true,
    topicWriteEnabled: true, daydreamReasoningLevel: 'high', timeoutMs: 10000 };
  const kv = domain.createMemoryKeyValueStore();
  const jevContext = new domain.JevContextRuntime({
    loadPolicy: async () => ({ selectionMode: 'off', retentionMode: 'off',
      selectionAllowed: false, retentionAllowed: false,
      selectionTaskText: false, selectionToolMetadata: false,
      retentionTaskText: false, retentionToolMetadata: false,
      selectionConsentKey: 'off', retentionConsentKey: 'off' }),
    createController: () => new AbortController(),
    evaluate: async () => assert.fail('Disabled Jev must not evaluate during auxiliary auth checks'),
  });
  const host = { ...domain, exports: {}, Error, Promise, Map, Set, JSON, String, Number, Date, Math,
    setTimeout, clearTimeout, DOMAIN: 1, TAG: 'test', hilog: { warn() {} },
    getAppContainer: () => ({ httpClient: http }), getChatKeyRoulette: () => ({ next: key => key }),
    encodeImageForChat: async url => url, encodeImageDetailed: async url => ({ url }),
    getChatKvStore: () => kv, getCompactStore: () => store,
    beginJevContextRun: (conversationId, runId, signal) => jevContext.beginRun(conversationId, runId, signal),
    getValidCodexToken: async id => { refreshed.push(['codex', id]); return { accessToken: 'fresh-codex', accountId: 'account' }; },
    getValidGrokToken: async id => { refreshed.push(['grok', id]); return { accessToken: 'fresh-grok' }; },
    resolveTaskModelReference: async () => choice, resolveMemoryDaydreamChoice: async () => choice,
    readTaskModelReference: async () => ({ kind: 'auto' }), TASK_COMPRESS_MODEL_KEY: 'compress',
    loadTaskPrompts: async () => ({ compressPrompt: '' }),
    loadAgentRuntimeSnapshot: async () => ({ contextCompaction: { keepRecentTurns: 1 }, memoryWorker: worker }),
    getMemoryDreamStoreOps: () => ({}), getMemoryDreamPlanStore: () => ({}),
    resolveChatProviderChoice: async () => choice,
    ChatStreamSubAgentGenerationPort: class { constructor(config) { this.config = config; } },
    AppStorage: { get: () => false }, EntryAbortController: AbortController,
    MemoryDocumentRefreshError: class extends Error {},
    getMemoryTopicStoreOps: () => ({ snapshotForTopics: async () => [{ id: 'a' }, { id: 'b' }],
      applyOptimization: async () => ({ topicIds: [], staleCount: 0, rejectedReasons: [], changed: false,
        profileUpdated: false, mergedIds: [] }) }),
    isMemoryTopicSource: () => true,
    decodeMemoryTopicSuggestions: () => [],
  };
  // The auth wrapper is introduced by the fix; baseline factories still run without it.
  const appSource = fs.readFileSync(path.join(entry, 'di/AppContainer.ets'), 'utf8');
  const authNames = ['buildProviderApi', 'buildSubAgentGenerationPort'];
  if (appSource.includes('export const prepareProviderApi')) authNames.push('prepareProviderApi');
  evaluate('di/AppContainer.ets', authNames, host);
  const runtime = { resolvedChoice: choice, modelContextWindowTokens: 128000 };
  host.resolveChatRuntime = async () => runtime;
  return { domain, requests, refreshed, choice, store, host, runtime };
};

const runTask = async (h, task) => {
  const { host, domain, choice } = h;
  if (task === 'auxiliary') {
    evaluate('platform_impl/ConversationRunAuxiliary.ets', ['chunkText', 'generateAuxiliaryText'], host);
    await host.generateAuxiliaryText(choice, 'auxiliary prompt', 'off');
  } else if (task === 'ocr') {
    evaluate('platform_impl/OcrSupport.ets', ['generateVisionText'], host);
    await host.generateVisionText(choice.provider, choice.model, [domain.makeUserMessage('ocr')]);
  } else if (task === 'automatic compaction') {
    evaluate('platform_impl/ConversationRunDependencies.ets', ['buildConversationCompressProvider'], host);
    await host.buildConversationCompressProvider({ runtime: h.runtime, compressChoice: choice })
      .streamText([domain.makeUserMessage('compress')], () => {});
  } else if (task === 'manual compaction') {
    evaluate('platform_impl/ContextCompactionSupport.ets', ['skippedMessage', 'compactConversationNow'], host);
    const nodes = Array.from({ length: 8 }, (_, i) => domain.makeMessageNode([
      domain.makeUIMessage(i % 2 ? 'assistant' : 'user', [{ type: 'text', text: `message ${i}`, metadata: null }]) ]));
    await host.compactConversationNow(domain.makeConversation('conversation', nodes));
  } else if (task === 'daydream') {
    evaluate('platform_impl/MemoryDreamOps.ets', ['loadMemoryDreamPlanOpDeps'], host);
    await (await host.loadMemoryDreamPlanOpDeps()).planner.generateText('daydream');
  } else if (task === 'topic') {
    evaluate('platform_impl/MemoryTopicOps.ets', ['result', 'runState', 'TopicGateError', 'awaitAbortable', 'MemoryTopicCoordinator'], host);
    const outcome = await new host.MemoryTopicCoordinator().run(true);
    assert.equal(outcome.status, 'completed', outcome.error);
  } else {
    const port = host.buildSubAgentGenerationPort(h.runtime);
    const model = await port.config.resolveModel(null);
    const params = domain.makeTextGenerationParams({ model: domain.toChatModel(model), temperature: 0.3,
      customBody: model.customBodies });
    await port.config.makeProvider(model, params, model.customHeaders).streamText([domain.makeUserMessage('subagent')], () => {});
  }
};

for (const mode of ['codex_oauth', 'grok_oauth', 'api_key']) {
  for (const task of ['auxiliary', 'ocr', 'automatic compaction', 'manual compaction', 'daydream', 'topic', 'subagent']) {
    test(`Entry ${task} ${mode}: fresh auth reaches real HTTP and task parameters remain intact`, async () => {
      const h = await harness(mode);
      await runTask(h, task);
      assert.ok(h.requests.length > 0, 'Task must reach the protocol adapter');
      const request = h.requests[0];
      assert.equal(request.headers.Authorization, `Bearer ${mode === 'api_key' ? 'stale' : `fresh-${mode === 'codex_oauth' ? 'codex' : 'grok'}`}`);
      assert.equal(request.headers['ChatGPT-Account-Id'], mode === 'codex_oauth' ? 'account' : undefined);
      assert.equal(h.choice.provider.apiKey, 'stale', 'Auth snapshots must not mutate the source choice');
      assert.deepEqual(h.refreshed, mode === 'api_key' ? [] : [[mode === 'codex_oauth' ? 'codex' : 'grok', 'provider']]);
      const body = JSON.parse(request.body);
      assert.equal(body.model, 'fixture');
      assert.equal(request.headers['X-Model'], task === 'ocr' ? undefined : 'task-header');
      assert.equal(body.task_body, task === 'ocr' ? undefined : 'task-body');
      if (task === 'subagent' && mode !== 'codex_oauth') assert.equal(body.temperature, 0.3);
      if (['daydream', 'topic'].includes(task)) assert.equal(mode === 'codex_oauth' ? body.reasoning.effort : body.reasoning_effort, 'high');
    });
  }
}

test('Entry auth factory snapshots provider and headers before awaiting OAuth; invalid refresh performs no HTTP', async () => {
  const h = await harness('codex_oauth');
  assert.equal(typeof h.host.prepareProviderApi, 'function');
  let finish;
  h.host.getValidCodexToken = () => new Promise(resolve => { finish = resolve; });
  const headers = { 'X-Task': 'original' };
  const pending = h.host.prepareProviderApi(h.choice.provider, headers);
  h.choice.provider.baseUrl = 'https://changed.test/v1'; headers['X-Task'] = 'changed';
  finish({ accessToken: 'fresh-snapshot', accountId: 'snapshot-account' });
  const api = await pending;
  await api.generateText([h.domain.makeUserMessage('snapshot')], h.domain.makeTextGenerationParams({
    model: h.domain.toChatModel(h.choice.model) }));
  assert.ok(h.requests[0].url.startsWith('https://fixture.test/'));
  assert.equal(h.requests[0].headers['X-Task'], 'original');
  assert.equal(h.requests[0].headers.Authorization, 'Bearer fresh-snapshot');
  assert.equal(h.requests[0].headers['ChatGPT-Account-Id'], 'snapshot-account');
  h.host.getValidCodexToken = async () => null;
  await assert.rejects(h.host.prepareProviderApi(h.choice.provider, {}), /登录已失效/);
  assert.equal(h.requests.length, 1);
});

test('Entry foreground context preparation forwards the owning run signal to the domain', async () => {
  const h = await harness('api_key');
  const signal = new AbortController().signal;
  let captured;
  h.host.prepareContext = async (_conversation, _policy, _window, messages, _size, deps) => {
    captured = deps; return { messages };
  };
  evaluate('platform_impl/ConversationRunDependencies.ets', ['buildConversationCompressProvider', 'prepareMessages'], h.host);
  const messages = [h.domain.makeUserMessage('hello')];
  const ctx = { conversationId: 'conversation', runId: 'foreground-run',
    signal, conversation: h.domain.makeConversation('conversation'), publish() {},
    seed: { runtime: h.runtime, compressChoice: h.choice, agentRuntime: { contextCompaction: {} } } };
  await h.host.prepareMessages(ctx)(messages);
  assert.equal(captured.abortSignal, signal);
  const prepared = await captured.prepareToolResults(messages, 4);
  assert.equal(prepared.messages, messages, 'Disabled Jev preserves the same prepared source');
  assert.equal(prepared.retainedToolCallIds.size, 0);
  assert.equal(h.requests.length, 0, 'No compaction request should be made until the domain admits it');
});
