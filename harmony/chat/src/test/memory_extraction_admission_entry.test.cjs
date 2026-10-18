// Actual Entry store and atomic file I/O; the platform file API and model are controlled.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const fsp = fs.promises;
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const deferred = () => {
  let resolve;
  const promise = new Promise(yes => { resolve = yes; });
  return { promise, resolve };
};

const entryStore = async (domain, t) => {
  const directory = await fsp.mkdtemp(path.join(os.tmpdir(), 'memory-admission-'));
  t.after(() => fsp.rm(directory, { recursive: true, force: true }));
  const handles = new Map();
  let nextHandle = 1;
  const fileIo = {
    OpenMode: { READ_WRITE: 1, CREATE: 2, TRUNC: 4 },
    access: async file => {
      try { await fsp.access(file); return true; }
      catch (error) { if (error.code === 'ENOENT') return false; throw error; }
    },
    readText: file => fsp.readFile(file, 'utf8'),
    mkdir: (file, recursive) => fsp.mkdir(file, { recursive }),
    open: async file => {
      const handle = await fsp.open(file, 'w');
      const fd = nextHandle++;
      handles.set(fd, handle);
      return { fd };
    },
    write: async (fd, buffer) => (await handles.get(fd).write(Buffer.from(buffer))).bytesWritten,
    close: async file => { await handles.get(file.fd).close(); handles.delete(file.fd); },
    renameSync: (from, to) => fs.renameSync(from, to),
    unlinkSync: file => fs.unlinkSync(file),
  };
  const imports = {
    '@amber/chat-domain': domain,
    '@kit.CoreFileKit': { fileIo },
    '@kit.PerformanceAnalysisKit': { hilog: { warn: () => {} } },
    '@kit.ArkTS': { util: { TextEncoder: { create: () => ({ encodeInto: text => new TextEncoder().encode(text) }) } } },
    './NotificationNotifier.ets': {},
    './MemoryDocumentStore.ets': {},
    './MemoryOptimizationStore.ets': {},
  };
  const restart = () => {
    const cache = new Map();
    const load = name => {
      if (imports[name]) return imports[name];
      if (cache.has(name)) return cache.get(name);
      const exports = {};
      cache.set(name, exports);
      const filename = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl', name);
      const code = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
        compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
      }).outputText;
      vm.runInNewContext(code, {
        exports, require: load, AppStorage: { get: () => ({ filesDir: directory }) },
        Date, Error, Promise, Map, Set, Number, String, Math, JSON, Uint8Array, ArrayBuffer,
      }, { filename });
      return exports;
    };
    return load('./MemoryStore.ets').getMemoryExtractionStoreOps();
  };
  return {
    restart,
    events: async () => JSON.parse(await fsp.readFile(path.join(directory, 'memory/memory_events.json'), 'utf8')),
  };
};

const extractionDeps = (ops, generateText, resolution = { kind: 'ok', modelId: 'worker' }, maxDailyRuns = 1) => ({
  ...ops,
  worker: { enabled: true, extractionEnabled: true, maxDailyRuns },
  locale: 'zh-CN',
  resolveWorkerModel: async () => resolution,
  generateText,
});
const conversation = (domain, id, contaminated = false) => {
  const messages = [domain.makeUserMessage('我喜欢简洁的中文回复')];
  if (contaminated) messages.push(domain.makeUIMessage('assistant', [{ type: 'tool', toolCallId: 'search',
    toolName: 'search_web', input: '{}', output: [], approvalState: { type: 'approved' }, metadata: null }]));
  return domain.makeConversation(id, messages.map(domain.toMessageNode));
};

test('actual extraction admission reserves only the last daily slot and survives Entry store reload', async t => {
  const domain = await import('../main/ets/index.ts');
  domain.resetMemoryExtractionDebounce();
  const store = await entryStore(domain, t);
  let calls = 0;
  const generate = async () => { calls++; return '{"candidates":[]}'; };
  const deps = extractionDeps(store.restart(), generate);
  await Promise.all(['daily-one', 'daily-two'].map(id =>
    domain.runMemoryExtraction(conversation(domain, id), deps)));
  assert.equal(calls, 1, 'two conversations must not both spend the last slot');
  const events = await store.events();
  assert.equal(events.filter(event => event.type === 'extraction_started').length, 1);
  assert.equal(events.filter(event => event.message === 'Daily memory worker limit reached.').length, 1);

  await domain.runMemoryExtraction(conversation(domain, 'daily-after-reload'), extractionDeps(store.restart(), generate));
  assert.equal(calls, 1, 'persisted admission must remain spent after reloading the store');
  assert.equal((await store.events()).filter(event => event.type === 'extraction_started').length, 1);
});

test('actual extraction admission releases the store queue before parallel provider work', { timeout: 5000 }, async t => {
  const domain = await import('../main/ets/index.ts');
  domain.resetMemoryExtractionDebounce();
  const store = await entryStore(domain, t);
  const bothEntered = deferred(), release = deferred();
  let calls = 0;
  const deps = extractionDeps(store.restart(), async () => {
    if (++calls === 2) bothEntered.resolve();
    await release.promise;
    return '{"candidates":[]}';
  }, undefined, 2);
  const running = Promise.all(['parallel-one', 'parallel-two'].map(id =>
    domain.runMemoryExtraction(conversation(domain, id), deps)));
  await bothEntered.promise;
  assert.equal((await store.events()).filter(event => event.type === 'extraction_started').length, 2);
  release.resolve();
  await running;
  assert.equal(calls, 2);
});

test('missing models/providers and contaminated conversations do not reserve extraction quota', async t => {
  const domain = await import('../main/ets/index.ts');
  domain.resetMemoryExtractionDebounce();
  const store = await entryStore(domain, t);
  const ops = store.restart();
  let calls = 0;
  const generate = async () => { calls++; return '{"candidates":[]}'; };
  await domain.runMemoryExtraction(conversation(domain, 'no-model'), extractionDeps(ops, generate, { kind: 'no_model' }));
  await domain.runMemoryExtraction(conversation(domain, 'no-provider'), extractionDeps(ops, generate, { kind: 'no_provider', modelId: 'missing' }));
  await domain.runMemoryExtraction(conversation(domain, 'contaminated', true), extractionDeps(ops, generate));
  assert.equal(calls, 0);
  assert.equal((await store.events()).filter(event => event.type === 'extraction_started').length, 0);
  await domain.runMemoryExtraction(conversation(domain, 'eligible'), extractionDeps(ops, generate));
  assert.equal(calls, 1);
  assert.equal((await store.events()).filter(event => event.type === 'extraction_started').length, 1);
});
