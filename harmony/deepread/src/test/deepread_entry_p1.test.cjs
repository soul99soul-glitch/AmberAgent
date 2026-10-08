const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const ENTRY = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl');
const load = (name, imports = {}) => {
  imports = {
    '@amber/deepread-domain': { isComplete: (value) => value.generationComplete === true
      && ['OVERVIEW', 'NARRATIVE', 'ANALYSIS', 'EXTENDED_READING'].every(stage => value.sectionStates[stage]?.status === 'READY') },
    '@kit.PerformanceAnalysisKit': { hilog: { warn() {} } },
    ...imports,
  };
  const filename = path.join(ENTRY, name);
  const source = fs.readFileSync(filename, 'utf8');
  const exports = {};
  vm.runInNewContext(ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, {
    exports, require: (spec) => {
      if (imports[spec]) return imports[spec];
      throw new Error(`unexpected import ${spec}`);
    },
    Error, Promise, Map, Set, JSON, String, Number, Date, Math,
  }, { filename });
  return exports;
};
const output = (text) => ({ overview: text, sectionStates: {}, generationPhase: 'COMPLETE' });
const cached = (value = output('已存稿')) => ({
  topicId: 'topic', title: 'title', sourceUrl: 'https://source.example/article', output: value,
  phase: 'COMPLETE', attemptCount: 1, lastError: null,
  createdAt: 1, updatedAt: 2, expiresAt: 3,
});

test('expired stored articles stay readable for retry and replacement failure recovery', async () => {
  const { DeepReadRunRepositoryAdapter } = load('DeepReadRunRepository.ets');
  const row = cached();
  const repository = { listHistory: async () => [row] };
  const adapter = new DeepReadRunRepositoryAdapter(repository);
  await adapter.hydrate();
  assert.equal(adapter.get('topic', 'title'), row.output);
});

test('a run loads its stored article even when it is outside the recent history cache', async () => {
  const { DeepReadRunRepositoryAdapter } = load('DeepReadRunRepository.ets');
  const row = cached();
  const requested = [];
  const repository = {
    listHistory: async () => [],
    get: async (topicId) => { requested.push(topicId); return row; },
  };
  const adapter = new DeepReadRunRepositoryAdapter(repository);
  await adapter.hydrate();
  assert.equal(adapter.get('topic', 'title'), null);
  await adapter.load('topic');
  assert.deepEqual(requested, ['topic']);
  assert.equal(adapter.get('topic', 'title'), row.output);
});

test('save waits for persistence and exposes the new cache only after storage succeeds', async () => {
  const { DeepReadRunRepositoryAdapter } = load('DeepReadRunRepository.ets');
  const row = cached();
  let resolveSave;
  let written;
  const repository = {
    listHistory: async () => [row],
    upsert: (entry) => { written = entry; return new Promise((resolve) => { resolveSave = resolve; }); },
  };
  const adapter = new DeepReadRunRepositoryAdapter(repository);
  await adapter.hydrate();
  const replacement = output('新稿');
  let settled = false;
  const pending = Promise.resolve(adapter.save('topic', 'title', replacement)).then(() => { settled = true; });
  await Promise.resolve();
  assert.equal(settled, false);
  assert.equal(adapter.get('topic', 'title'), row.output);
  assert.equal(written.sourceUrl, row.sourceUrl);
  assert.equal(written.createdAt, row.createdAt);
  resolveSave();
  await pending;
  assert.equal(adapter.get('topic', 'title'), replacement);
});

test('failed storage write rejects the run save and keeps the old cache', async () => {
  const { DeepReadRunRepositoryAdapter } = load('DeepReadRunRepository.ets');
  const row = cached();
  const failure = new Error('database full');
  const repository = { listHistory: async () => [row], upsert: async () => { throw failure; } };
  const adapter = new DeepReadRunRepositoryAdapter(repository);
  await adapter.hydrate();
  await assert.rejects(async () => adapter.save('topic', 'title', output('新稿')), failure);
  assert.equal(adapter.get('topic', 'title'), row.output);
});

test('a captured AI configuration stays stable while settings and the caller object change', async () => {
  const requests = [];
  const imports = {
    '@amber/deepread-domain': {
      makeUIMessage: (role, parts) => ({ role, parts }),
      SseAssembler: class {}, parseOpenAiStreamEvent: () => ({}),
    },
    '@kit.PerformanceAnalysisKit': { hilog: { info() {}, warn() {}, error() {} } },
    '@kit.ArkTS': { util: {} },
  };
  const { createOpenAiCompatibleAiClient } = load('OpenAiCompatibleAiClient.ets', imports);
  const values = { ai_base_url: 'https://later.example', ai_api_key: 'later-key' };
  const storage = { get: async (key, fallback) => values[key] ?? fallback };
  const http = { fetch: async (request) => {
    requests.push(request);
    return { status: 200, body: '{"choices":[{"message":{"content":"done"}}]}' };
  } };
  const captured = { baseUrl: 'https://first.example', apiKey: 'first-key' };
  const ai = createOpenAiCompatibleAiClient(http, storage, captured);
  captured.baseUrl = 'https://mutated.example'; captured.apiKey = 'mutated-key';
  await ai.generateText({ model: 'first-model', messages: [], maxSteps: 1 });
  values.ai_base_url = 'https://third.example'; values.ai_api_key = 'third-key';
  await ai.generateText({ model: 'first-model', messages: [], maxSteps: 1 });
  assert.deepEqual(requests.map((request) => request.url), [
    'https://first.example/v1/chat/completions', 'https://first.example/v1/chat/completions',
  ]);
  assert.deepEqual(requests.map((request) => request.headers.Authorization), ['Bearer first-key', 'Bearer first-key']);
});

test('new runs reread the selected model, credentials, and playbook without changing earlier snapshots', async () => {
  const { captureDeepReadRunConfig } = load('DeepReadRunConfig.ets');
  const values = {
    deepread_model_id: 'board-model', ai_model: 'chat-model',
    ai_base_url: 'https://first.example', ai_api_key: 'first-key',
  };
  let playbook = 'first playbook';
  const storage = { get: async (key, fallback) => values[key] ?? fallback };
  const first = await captureDeepReadRunConfig(storage, async () => playbook);
  values.deepread_model_id = '';
  values.ai_model = 'new-chat-model';
  values.ai_base_url = 'https://second.example'; values.ai_api_key = 'second-key';
  playbook = 'second playbook';
  const second = await captureDeepReadRunConfig(storage, async () => playbook);
  assert.deepEqual(JSON.parse(JSON.stringify(first)), {
    model: 'board-model', baseUrl: 'https://first.example', apiKey: 'first-key', playbookMarkdown: 'first playbook',
  });
  assert.deepEqual(JSON.parse(JSON.stringify(second)), {
    model: 'new-chat-model', baseUrl: 'https://second.example', apiKey: 'second-key', playbookMarkdown: 'second playbook',
  });
});
