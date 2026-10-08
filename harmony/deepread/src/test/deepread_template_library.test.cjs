const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const main = path.resolve(__dirname, '../main/ets');
const platform = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl');
const cache = new Map();
function load(filename, ports = {}) {
  filename = path.resolve(filename);
  if (!Object.keys(ports).length && cache.has(filename)) return cache.get(filename);
  const exports = {};
  if (!Object.keys(ports).length) cache.set(filename, exports);
  const source = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  vm.runInNewContext(source, { exports, require: name => {
    if (ports[name]) return ports[name];
    if (name === '@amber/deepread-domain') return Object.assign({}, load(path.join(main, 'domain/models.ts')),
      load(path.join(main, 'domain/helpers.ts')), load(path.join(main, 'domain/enums.ts')),
      load(path.join(main, 'domain/synthesis_templates.ts')));
    if (name === '@amber/chat-domain') return load(path.resolve(__dirname, '../../../chat/src/main/ets/chat/markdown_blocks.ts'));
    if (name.startsWith('.')) return load(path.resolve(path.dirname(filename), name));
    throw new Error('Unexpected dependency ' + name);
  }, console, URL, TextEncoder, TextDecoder, Uint8Array, Error, JSON, Math, Number, String,
  Array, Object, Map, Set, Date, Promise, setTimeout, clearTimeout,
  setInterval: () => { throw new Error('Polling forbidden'); }, AppStorage: { get: () => ({}) } }, { filename });
  return exports;
}
const template = load(path.join(platform, 'DeepReadTemplate.ets'));
const domain = load(path.join(main, 'domain/models.ts'));
const library = load(path.join(main, 'domain/library.ts'));
const tick = () => new Promise(resolve => setImmediate(resolve));
class Predicates {
  constructor() { this.matches = []; this.limit = 0; }
  equalTo(key, value) { this.matches.push(row => row[key] === value); return this; }
  lessThan(key, value) { this.matches.push(row => row[key] < value); return this; }
  orderByDesc(key) { this.order = key; return this; }
  limitAs(value) { this.limit = value; return this; }
}
function resultSet(rows, columns) {
  let at = -1;
  return { goToNextRow: () => ++at < rows.length, getString: i => rows[at][columns[i]] ?? '',
    getLong: i => rows[at][columns[i]], close() {} };
}
function repositoryFixture() {
  const rows = new Map();
  const store = {
    insert: async (_, row) => { rows.set(row.topic_id, JSON.parse(JSON.stringify(row))); },
    query: async (predicate, columns) => {
      let matches = [...rows.values()].filter(row => predicate.matches.every(match => match(row)));
      if (predicate.order) matches.sort((a, b) => b[predicate.order] - a[predicate.order]);
      if (predicate.limit) matches = matches.slice(0, predicate.limit);
      return resultSet(matches, columns);
    },
    delete: async predicate => {
      let count = 0;
      for (const [id, row] of rows) if (predicate.matches.every(match => match(row))) { rows.delete(id); count++; }
      return count;
    },
  };
  const sdk = { RdbPredicates: Predicates, ConflictResolution: { ON_CONFLICT_REPLACE: 1 } };
  const repo = load(path.join(platform, 'RdbRepository.ets'), {
    '@kit.ArkData': { relationalStore: sdk }, '@kit.PerformanceAnalysisKit': { hilog: { info() {}, warn() {} } },
  }).createRdbRepository();
  repo.store = store;
  return { repo, rows };
}
const entry = (id, output = domain.makeEmptyDeepReadOutput(), time = 10) => ({ topicId: id, title: 'Article ' + id,
  sourceUrl: null, output, phase: output.generationPhase, attemptCount: 0, lastError: null,
  createdAt: time, updatedAt: time, expiresAt: Date.now() + 100000 });

test('custom capture survives repository section updates and template deletion', async () => {
  let saved = [{ id: 'custom_one', name: 'Original design', html: '<article class="original"><h1>{{title}}</h1>{{content}}</article>', createdAt: 1, updatedAt: 2 }];
  const storage = { get: async () => JSON.stringify(saved) };
  const frozen = await template.captureDeepReadTemplate(storage, 'custom_one', 50);
  const output = domain.makeEmptyDeepReadOutput();
  output.templateId = frozen.id; output.templateSnapshot = frozen; output.summary = 'Captured article';
  const { repo } = repositoryFixture();
  await repo.upsert(entry('one', output));
  await repo.updateSectionState('one', 'OVERVIEW', { status: 'READY', errorMessage: null });
  await repo.updatePhase('one', 'WRITING');
  saved = [];
  const read = await repo.get('one');
  assert.equal(read.templateId, frozen.id);
  assert.equal(read.output.templateSnapshot.html, frozen.html);
  assert.equal(template.resolveDeepReadArticleTemplate(read.output).fallback, null);
  const reading = template.renderCapturedDeepReadTemplateHtml(read.title, read.output);
  assert.match(reading, /class="original"/); assert.match(reading, /Captured article/);
  await assert.rejects(template.captureDeepReadTemplate(storage, 'custom_one'), /已不存在/);
});

test('legacy and missing template fallback is explicit and never reads the latest global template', () => {
  const output = domain.makeEmptyDeepReadOutput();
  assert.equal(template.resolveDeepReadArticleTemplate(output).fallback, 'legacy');
  output.templateId = 'custom_removed';
  assert.equal(template.resolveDeepReadArticleTemplate(output).fallback, 'missing');
  output.templateId = 'editorial_slant';
  assert.equal(template.resolveDeepReadArticleTemplate(output).template.kind, 'editorial');
  output.templateSnapshot = { id: 'custom_bad', name: 'Bad', kind: 'custom', html: '<script>bad()</script>{{content}}', capturedAt: 1 };
  assert.equal(template.resolveDeepReadArticleTemplate(output).fallback, 'missing');
  assert.match(template.renderCapturedDeepReadTemplateHtml('Old', output), /<h1 class="title">Old/);
});

test('legal starter saves in the actual template store with original and updated timestamps', async () => {
  const html = template.starterTemplateHtml();
  assert.equal(template.validateTemplateHtml(html), '');
  const writes = new Map();
  const storage = { get: async (key, fallback) => writes.get(key) ?? fallback, set: async (key, value) => writes.set(key, value) };
  await template.saveCustomTemplates(storage, [{ id: 'custom_new', name: 'New', html, createdAt: 1, updatedAt: 2 }]);
  const list = await template.loadCustomTemplates(storage);
  assert.equal(list[0].createdAt, 1); assert.equal(list[0].updatedAt, 2);
  const captured = await template.captureDeepReadTemplate(storage, 'custom_new', 3);
  assert.equal(captured.html, html);
});

test('repository history emits creates, phase updates, deletes and fresh subscriptions with no polling', async () => {
  const { repo } = repositoryFixture();
  const values = [];
  const history = repo.observeHistory(0);
  const stop = history.subscribe(entries => values.push(entries.map(row => row.topicId + ':' + row.phase).join(',')));
  await tick(); assert.equal(values.at(-1), '');
  await repo.upsert(entry('one')); assert.equal(values.at(-1), 'one:IDLE');
  await repo.upsert(entry('two', domain.makeEmptyDeepReadOutput(), 20)); assert.equal(values.at(-1), 'two:IDLE,one:IDLE');
  await repo.updatePhase('one', 'WRITING'); assert.match(values.at(-1), /one:WRITING/);
  await repo.delete('two'); assert.equal(values.at(-1), 'one:WRITING');
  stop(); assert.equal(repo.historyObservers.size, 0);
  const count = values.length; await repo.upsert(entry('three')); assert.equal(values.length, count);
  const stopAgain = history.subscribe(entries => values.push(entries.map(row => row.topicId).join(',')));
  await tick(); assert.match(values.at(-1), /three/); stopAgain(); assert.equal(repo.historyObservers.size, 0);
});

test('a delayed initial history query cannot overwrite a committed event or call an unsubscribed page', async () => {
  const { repo } = repositoryFixture();
  const readHistory = repo.listHistory.bind(repo);
  let initial;
  let calls = 0;
  repo.listHistory = limit => ++calls === 1 ? new Promise(resolve => { initial = resolve; }) : readHistory(limit);
  const values = [];
  const stop = repo.observeHistory(0).subscribe(rows => values.push(rows.map(row => row.topicId).join(',')));
  await repo.upsert(entry('committed'));
  assert.equal(values.at(-1), 'committed');
  initial([]); await tick();
  assert.equal(values.at(-1), 'committed');
  stop();
  const before = values.length;
  let delayed;
  repo.listHistory = () => new Promise(resolve => { delayed = resolve; });
  const stopLate = repo.observeHistory(0).subscribe(() => values.push('late'));
  stopLate(); delayed([]); await tick();
  assert.equal(values.length, before);
  assert.equal(repo.historyObservers.size, 0);
});

test('library observes real scheduler admission and completion even before a draft exists and releases both subscriptions', async () => {
  const { repo } = repositoryFixture();
  const { createScheduler } = load(path.join(main, 'agent/scheduler.ts'));
  let release; const block = new Promise(resolve => { release = resolve; });
  const scheduler = createScheduler({ runManager: {}, createRunManager: async () => { await block; throw new Error('No provider'); },
    observeOutput: () => ({ getCurrent: () => undefined }), isInterruptedPhase: () => false,
    createAbortController: () => new AbortController() });
  const snapshots = [];
  const stop = library.observeDeepReadLibrary(repo, scheduler).subscribe(snapshot => snapshots.push(snapshot));
  await tick();
  const running = scheduler.run('collect', 'Collecting sources');
  await tick();
  const rows = library.queryDeepReadLibraryRows(snapshots.at(-1), '', 'running');
  assert.equal(rows.length, 1); assert.equal(rows[0].entry, null); assert.equal(rows[0].title, 'Collecting sources');
  await repo.upsert(entry('collect')); assert.equal(library.queryDeepReadLibraryRows(snapshots.at(-1), '', 'running').length, 1);
  release(); await assert.rejects(running, /No provider/);
  assert.equal(snapshots.at(-1).activeRuns.length, 0);
  stop(); const count = snapshots.length; await repo.upsert(entry('after')); assert.equal(snapshots.length, count);
  assert.equal(repo.historyObservers.size, 0);
});

const source = { sourceId: 'actual', url: 'https://source.example/report', title: '已读取的来源报道', source: '原始资料',
  evidenceText: '产品公布后收集用户反馈，功能与实际使用场景仍需继续核对。'.repeat(30),
  credibility: 'medium', freshness: 'unknown', publishedAt: null, imageCandidates: [] };
function stageReply(request) {
  const prompt = request.messages.map(message => message.parts.map(part => part.type === 'text' ? part.text : '').join('')).join('\n');
  return JSON.stringify(prompt.includes('目标段落：概览')
    ? { topic_type: 'product', summary: '来源报道了产品公布后收集反馈的完整经过；功能与长期使用效果仍需更多独立验证。', key_entities: ['产品'] }
    : prompt.includes('目标段落：时间轴叙事')
      ? { timeline: [{ date: '今天', event: '产品正式公布之后开始收集真实用户的使用反馈。' }], core_points: [{ point: '功能是否适合长期场景仍需核对。' }] }
      : prompt.includes('目标段落：深度分析')
        ? { analysis: { core_dispute: '不同用户对于这些功能是否满足长期使用场景存在核心分歧，需要核对独立反馈。', perspectives: [], implications: '应持续核对不同来源的实际反馈。', quotes: [] } }
        : prompt.includes('目标段落：扩展阅读')
          ? { extended_reading: [{ title: '实际提供的报道', url: source.url, source: source.source }], references: [] }
          : { overview_angle: '产品公布与用户反馈' });
}
async function pipelineFixture(snapshot, reply = stageReply, sources = [source]) {
  const { repo } = repositoryFixture();
  const Adapter = load(path.join(platform, 'DeepReadRunRepository.ets'), {
    '@kit.PerformanceAnalysisKit': { hilog: { warn() {} } },
  }).DeepReadRunRepositoryAdapter;
  const adapter = new Adapter(repo);
  const requests = [];
  const { makeAssistantMessage } = load(path.join(main, 'agent/message.ts'));
  const aiClient = { generateText: async request => {
    requests.push(request); return request.messages.concat(makeAssistantMessage(reply(request)));
  } };
  const deps = { model: 'ordinary-text', writerMode: 'structured', templateSnapshot: snapshot,
    playbookMarkdown: '', nowIso: () => '2026-10-03', aiClient,
    prefetcher: { collect: async () => sources, cacheSize: () => 0 },
    collectRun: (messages, _label, signal) => aiClient.generateText({ model: 'ordinary-text', messages, signal }),
    repository: { get: (id, title) => adapter.get(id, title), save: (id, title, output) => adapter.save(id, title, output), clear: id => adapter.clear(id) } };
  const { createScheduler } = load(path.join(main, 'agent/scheduler.ts'));
  let admittedOptions;
  const scheduler = createScheduler({ runManager: deps,
    createRunManager: async (_id, _title, _token, options) => { admittedOptions = options; return deps; },
    observeOutput: id => ({ getCurrent: () => adapter.get(id, ''), subscribe: callback => repo.observe(id).subscribe(row => callback(row?.output)) }),
    isInterruptedPhase: () => false, createAbortController: () => new AbortController() });
  return { repo, adapter, deps, requests, scheduler, options: () => admittedOptions,
    set: async output => { await repo.upsert(entry('pipeline', output)); await adapter.load('pipeline'); } };
}
const originalCapture = async () => template.captureDeepReadTemplate({ get: async () => JSON.stringify([
  { id: 'custom_captured', name: 'Captured design', html: '<article class="captured">{{title}}{{content}}</article>', createdAt: 1 },
]) }, 'custom_captured', 123);

test('captured selection crosses actual scheduler options, ordinary writer stages and RDB commits unchanged', async () => {
  const snapshot = await originalCapture();
  const defaults = await template.captureDeepReadTemplate({}, 'editorial_slant', 999);
  const f = await pipelineFixture(defaults);
  const committed = [];
  const stop = f.repo.observe('pipeline').subscribe(row => { if (row) committed.push(row); });
  const result = await f.scheduler.run('pipeline', '产品报道', { templateSnapshot: snapshot });
  stop();
  assert.equal(result.ok, true); assert.equal(result.output.generationComplete, true);
  assert.equal(f.options().templateSnapshot.id, snapshot.id);
  assert.equal(f.requests.length, 4); assert.ok(committed.length >= 4);
  for (const row of committed) assert.equal(row.output.templateSnapshot.html, snapshot.html);
  const saved = await f.repo.get('pipeline');
  assert.equal(saved.templateId, snapshot.id); assert.equal(saved.output.templateSnapshot.capturedAt, 123);
  assert.match(template.renderCapturedDeepReadTemplateHtml(saved.title, saved.output), /class="captured"/);
});

test('failed forced replacement keeps the complete previous article and its captured template', async () => {
  const snapshot = await originalCapture(); const f = await pipelineFixture(snapshot);
  await f.scheduler.run('pipeline', '产品报道');
  const old = await f.repo.get('pipeline');
  const next = await template.captureDeepReadTemplate({}, 'editorial_slant', 999);
  f.deps.collectRun = async messages => messages.concat({ id: 'bad', role: 'assistant', parts: [{ type: 'text', text: '错误 JSON' }], createdAt: '' });
  const result = await f.scheduler.run('pipeline', '产品报道', { force: true, templateSnapshot: next });
  assert.equal(result.ok, false);
  assert.equal(JSON.stringify(result.output), JSON.stringify(old.output));
  assert.equal(JSON.stringify((await f.repo.get('pipeline')).output), JSON.stringify(old.output));
});

test('partial continuation retains the article capture despite a changed default and explicit new template', async () => {
  const snapshot = await originalCapture(); const f = await pipelineFixture(snapshot);
  await f.scheduler.run('pipeline', '产品报道');
  const old = (await f.repo.get('pipeline')).output;
  const partial = { ...old, generationComplete: false, analysis: domain.makeEmptyDeepReadOutput().analysis,
    sectionStates: { ...old.sectionStates, ANALYSIS: { status: 'FAILED', errorMessage: '中断' } } };
  await f.set(partial); f.requests.length = 0;
  const next = await template.captureDeepReadTemplate({}, 'editorial_slant', 999);
  f.deps.templateSnapshot = next;
  const result = await f.scheduler.run('pipeline', '产品报道', { templateSnapshot: next });
  assert.equal(result.output.generationComplete, true); assert.equal(f.requests.length, 2);
  assert.equal(result.output.templateSnapshot.id, snapshot.id);
  assert.equal((await f.repo.get('pipeline')).output.templateSnapshot.html, snapshot.html);
  assert.equal(result.output.summary, partial.summary);
});

test('a no-source failed continuation retains its original captured template instead of the changed default', async () => {
  const snapshot = await originalCapture();
  const next = await template.captureDeepReadTemplate({}, 'editorial_slant', 999);
  const f = await pipelineFixture(next, stageReply, []);
  await f.set({ ...domain.makeEmptyDeepReadOutput(), templateId: snapshot.id, templateSnapshot: snapshot,
    sectionStates: { OVERVIEW: { status: 'FAILED', errorMessage: '来源失败' } } });
  const result = await f.scheduler.run('pipeline', '产品报道');
  assert.equal(result.ok, false); assert.equal(f.requests.length, 0);
  assert.equal(result.output.templateSnapshot.id, snapshot.id);
  assert.equal((await f.repo.get('pipeline')).output.templateSnapshot.html, snapshot.html);
});


test('synthesis capture, repository updates and rendered HTML preserve the article shape and citations', async () => {
  const frozen = await template.captureDeepReadTemplate({ get: async () => { throw Error('No custom lookup for built-ins'); } }, 'deepread_qa', 50);
  const output = domain.makeEmptyDeepReadOutput();
  output.templateId = frozen.id; output.templateSnapshot = frozen;
  output.templateArticle = { shape: 'template_synthesis', template: frozen.id, title: 'Saved questions', lede: 'Saved lede',
    sources: [{ id: 1, title: 'Saved source', url: 'https://example.com/source', site: 'Example' }],
    qa: [{ question: 'QUESTION_UNIQUE', answer: 'ANSWER_UNIQUE', sources: [1] }] };
  output.generationComplete = true; output.generationPhase = 'COMPLETE';
  const { repo } = repositoryFixture();
  await repo.upsert(entry('synthesis', output));
  await repo.updatePhase('synthesis', 'COMPLETE');
  const read = await repo.get('synthesis');
  assert.deepEqual(JSON.parse(JSON.stringify(read.output.templateArticle)), JSON.parse(JSON.stringify(output.templateArticle)));
  assert.equal(template.resolveDeepReadArticleTemplate(read.output).fallback, null);
  const html = template.renderCapturedDeepReadTemplateHtml(read.title, read.output, { readerStyle: 'minimal', dark: true, readerLayout: 'debate' });
  assert.match(html, /QUESTION_UNIQUE/); assert.match(html, /ANSWER_UNIQUE/);
  assert.match(html, /href="#dr-source-1"/); assert.match(html, /id="dr-source-1"/);
  assert.match(html, /--dr-bg:#000000/);
  assert.ok(html.indexOf('QUESTION_UNIQUE') < html.indexOf('Saved source'), 'template order stays independent of magazine layout');
  const exported = load(path.join(main, 'domain/export.ts')).deepReadToMarkdown(read);
  assert.match(exported, /QUESTION_UNIQUE/); assert.match(exported, /ANSWER_UNIQUE/); assert.match(exported, /https:\/\/example.com\/source/);
});
