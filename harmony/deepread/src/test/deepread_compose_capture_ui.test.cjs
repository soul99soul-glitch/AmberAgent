const { test } = require('node:test');
const assert = require('node:assert/strict');
const { entryRoot, domainRoot, loadPureModule, actualPage } = require('./deepread_ui_fixture.cjs');
const path = require('node:path');
const fs = require('node:fs');
const ts = require('typescript');
const template = loadPureModule(path.join(entryRoot, 'platform_impl/DeepReadTemplate.ets'));
const models = loadPureModule(path.join(domainRoot, 'models.ts'));
const inputs = loadPureModule(path.join(domainRoot, 'input_sources.ts'));
const helpers = loadPureModule(path.join(domainRoot, 'helpers.ts'));
const progress = loadPureModule(path.join(domainRoot, '../platform/deep_read_progress.ts'));
const oldTemplate = { id: 'custom_author', name: '作者版式', html: '<article><h1>{{title}}</h1><p>FROZEN_LAYOUT</p>{{content}}</article>', createdAt: 1 };
function fixture() {
  let templates = [oldTemplate], defaultId = 'editorial_slant';
  const saved = [], routes = [];
  const storage = { get: async (_key, fallback) => JSON.stringify(templates), set: async () => {} };
  const repository = { upsert: async entry => saved.push(entry) };
  const env = { loadDeepReadAppearance: async () => {}, ...template, ...models, ...inputs, ...helpers, ...progress,
    DeepReadHaptics: { success() {} }, newId: () => 'created', getRepository: () => repository, getAppContainer: () => ({ storage }), getProductKind: () => 'agent',
    getChatKvStore: () => ({ get: async key => key === 'deepread_first_use_confirmed' ? 'true' : defaultId }), router: { replaceUrl: async value => routes.push(value) },
  };
  const compose = actualPage('components/deepread/DeepReadComposerSheet.ets', ['loadTemplates', 'createArticle'], env);
  Object.assign(compose, { alive: true, busy: false, title: '作者主题', text: '真实补充资料', urls: '', files: [],
    templateId: 'none', templateChoices: [], pickerToken: 0, templateLoadToken: 0, templatesLoading: false, error: '',
    pendingTopicId: '', pendingTitle: '', onCreated: async (topicId, title) => routes.push({ params: { topicId, title } }) });
  return { compose, saved, routes, storage, repository, env,
    templates(value) { templates = value; }, default(value) { defaultId = value; } };
}
test('compose choice captures its own custom layout and original inputs before navigation', async () => {
  const f = fixture(); await f.compose.loadTemplates();
  assert.equal(f.compose.templateId, 'editorial_slant');
  assert.ok(f.compose.templateChoices.some(item => item.id === oldTemplate.id));
  f.compose.templateId = oldTemplate.id; await f.compose.createArticle();
  assert.equal(f.saved.length, 1); assert.equal(f.routes.length, 1);
  assert.equal(f.saved[0].templateId, oldTemplate.id);
  assert.equal(f.saved[0].output.templateSnapshot.html, oldTemplate.html);
  assert.equal(f.saved[0].output.inputText, '真实补充资料');
  assert.equal(f.saved[0].output.inputSources[0].content, '真实补充资料');
  assert.equal(f.routes[0].params.topicId, f.saved[0].topicId);
});
test('deleted selected template visibly rejects begin without persisting or navigating', async () => {
  const f = fixture(); f.compose.templateId = oldTemplate.id; f.templates([]);
  await f.compose.createArticle();
  assert.match(f.compose.error, /已不存在/); assert.equal(f.saved.length, 0); assert.equal(f.routes.length, 0);
  assert.equal(f.compose.busy, false);
});
test('leaving while template capture is pending never creates a late article', async () => {
  const f = fixture(); let finish;
  f.storage.get = () => new Promise(resolve => { finish = resolve; });
  f.compose.templateId = oldTemplate.id;
  const pending = f.compose.createArticle(); f.compose.alive = false; f.compose.pickerToken++;
  finish(JSON.stringify([oldTemplate])); await pending;
  assert.equal(f.saved.length, 0); assert.equal(f.routes.length, 0);
});
test('file picker operations do not invalidate the separate template-load owner', async () => {
  const f = fixture(); let finish;
  f.storage.get = () => new Promise(resolve => { finish = resolve; });
  const pending = f.compose.loadTemplates(); await Promise.resolve();
  f.compose.pickerToken++;
  finish(JSON.stringify([oldTemplate])); await pending;
  assert.equal(f.compose.templateChoices.length, template.BUILTIN_TEMPLATES.length + 1); assert.equal(f.compose.templatesLoading, false);
});
test('route failure keeps the committed reading visible and retry opens the same draft without another write', async () => {
  const f = fixture(); let attempts = 0;
  f.compose.onCreated = async (topicId, title) => {
    attempts++;
    if (attempts === 1) throw Error('页面打开失败');
    f.routes.push({ params: { topicId, title } });
  };
  await f.compose.createArticle();
  assert.equal(f.saved.length, 1); assert.equal(f.routes.length, 0); assert.match(f.compose.error, /页面打开失败/);
  assert.equal(f.compose.pendingTopicId, f.saved[0].topicId);
  const source = fs.readFileSync(path.join(entryRoot, 'components/deepread/DeepReadComposerSheet.ets'), 'utf8');
  const success = /DeepReadSettingsButton\(\{ icon: 'spark', success: ([^,]+),/.exec(source);
  assert.ok(success, 'the standalone primary button binds the real committed-draft success');
  assert.equal(new Function('return ' + success[1]).call(f.compose), true);
  const host = source.slice(source.indexOf('  HostCompose(): void'), source.indexOf('  TopicCard(): void'));
  const button = host.slice(host.indexOf("Text(this.busy ? '处理中…'"));
  const enabled = /\.enabled\(([^\n]+)\)\.onClick/.exec(button);
  assert.ok(enabled, 'test executes the production Host start-button gate');
  assert.equal(new Function('return ' + enabled[1]).call(f.compose), true, 'a committed draft must remain openable after route failure');
  await f.compose.createArticle();
  assert.equal(f.saved.length, 1); assert.equal(f.routes.length, 1); assert.equal(attempts, 2);
  assert.equal(f.routes[0].params.topicId, f.saved[0].topicId); assert.equal(f.routes[0].params.title, f.saved[0].title);
});
test('Reading reuses the captured layout after settings change or custom template deletion', async () => {
  const f = fixture(); f.compose.templateId = oldTemplate.id; await f.compose.createArticle();
  f.saved[0].output.summary = '已保存的正文'; f.templates([]); f.default('none');
  const reading = actualPage('pages/DeepReadArticlePage.ets', ['applyOutput', 'isWebTemplate', 'editorialHtml', 'renderOptions', 'switchToNativeReading'],
    { ...f.env, BG: '#111111', INK: '#eeeeee', INK3: '#bbbbbb', SURFACE: '#222222', LINE: '#444444', ACCENT: '#ff8800' });
  Object.assign(reading, { pageAlive: true, pageToken: 4, title: '作者主题', running: false,
    nativeReadingOverride: false, fontScale: 1, fontSerif: false, phaseLabel: () => '', refreshTemplateWeb() {} });
  reading.applyOutput(f.saved[0].output, 4);
  assert.equal(reading.templateName, oldTemplate.name); assert.equal(reading.isWebTemplate(), true);
  const html = reading.editorialHtml(); assert.ok(html.includes('FROZEN_LAYOUT')); assert.ok(html.includes('已保存的正文'));
  assert.equal(html, template.renderCapturedDeepReadTemplateHtml(reading.title, reading.output, reading.renderOptions()));
  reading.switchToNativeReading();
  assert.equal(reading.isWebTemplate(), false); assert.equal(reading.templateId, oldTemplate.id);
  assert.equal(reading.output.templateSnapshot.html, oldTemplate.html);
});
test('legacy or missing captured layout shows precise default attribution without consulting global settings', () => {
  const f = fixture(); const reading = actualPage('pages/DeepReadArticlePage.ets', ['applyOutput', 'isWebTemplate'], f.env);
  Object.assign(reading, { pageAlive: true, pageToken: 4, running: false, nativeReadingOverride: false,
    phaseLabel: () => '', refreshTemplateWeb() {} });
  const output = models.makeEmptyDeepReadOutput(); output.summary = '旧正文'; output.templateId = 'custom_deleted';
  reading.applyOutput(output, 4);
  assert.equal(reading.templateId, 'none'); assert.match(reading.templateMessage, /缺失.*默认/);
  assert.equal(reading.isWebTemplate(), false); assert.equal(reading.output.summary, '旧正文');
});
test('no-URL discovery input is saved as readable source text before the real scheduler sees it', async () => {
  const f = fixture(); const reading = actualPage('pages/DeepReadArticlePage.ets', ['saveDiscoveryInputs'], f.env);
  const source = inputs.makeInputSource('text', '热点来源与排名', '真实标题\n来源：News\n排名：1');
  Object.assign(reading, { pageAlive: true, pageToken: 4, topicId: 'hotspot', title: '真实标题', sourceUrl: '', seedUrls: [], discoveryInputs: [source] });
  const entry = await reading.saveDiscoveryInputs(4);
  assert.equal(f.saved[0], entry); assert.equal(entry.sourceUrl, null);
  assert.equal(entry.output.inputSources[0].url, null);
  assert.match(entry.output.inputText, /News/); assert.equal(entry.output.inputSourceUrls.length, 0);
  assert.equal(entry.output.templateSnapshot.id, 'editorial_slant');
});
for (const [force, active] of [[true, false], [false, false], [true, true]]) {
  test(`cached hotspot ${force ? 'explicit regenerate' : 'reopen'} ${active ? 'with live job' : 'without live job'} handles current attribution precisely`, async () => {
    const f = fixture(); f.compose.templateId = oldTemplate.id; await f.compose.createArticle();
    const existing = f.saved.pop(); existing.output.summary = '原已保存正文';
    existing.output.generationComplete = true;
    for (const stage of ['OVERVIEW', 'NARRATIVE', 'ANALYSIS', 'EXTENDED_READING']) existing.output.sectionStates[stage] = { status: 'READY' };
    existing.output.inputText = 'OLD_PROVIDER\n排名：1';
    f.repository.get = async () => existing;
    let seen = null;
    const scheduler = { isRunning: () => active, run: async (_id, _title, options) => { seen = f.saved.at(-1)?.output;
      assert.equal(options.templateSnapshot.html, oldTemplate.html);
      return { ok: true, output: null }; } };
    const page = actualPage('pages/DeepReadArticlePage.ets', ['loadArticle', 'saveDiscoveryInputs', 'loadDisplaySettings', 'applyOutput', 'runNow',
      'clearEditorialMotion', 'celebratePublished', 'editorialActive', 'hasReadableOutput'],
      { ...f.env, loadDeepReadAppearance: async () => {}, getDeepReadScheduler: async () => scheduler, getProductKind: () => 'deepread',
        FONT_SCALE_MIN: 0.7, FONT_SCALE_MAX: 1.8 });
    Object.assign(page, { pageAlive: true, pageToken: 4, topicId: existing.topicId, title: existing.title,
      sourceUrl: '', seedUrls: [], discoveryInputs: [inputs.makeInputSource('text', '新热点来源', 'NEW_PROVIDER\n排名：2')],
      running: false, nativeReadingOverride: false, phaseLabel: () => '', progressLabel: () => '',
      motionCycle: 0, completionStamp: 0, pageVisible: true, appBackgrounded: false,
      refreshTemplateWeb() {}, refreshWorkspaceStatus: async () => {}, subscribeToUpdates() {} });
    await page.loadArticle(4, force);
    assert.equal(existing.output.inputText, 'OLD_PROVIDER\n排名：1');
    if (force && !active) {
      assert.equal(f.saved.length, 1); assert.match(seen.inputText, /NEW_PROVIDER/);
      assert.match(seen.inputText, /排名：2/); assert.equal(seen.summary, '原已保存正文');
      assert.equal(seen.templateSnapshot.html, oldTemplate.html); assert.equal(f.saved[0].createdAt, existing.createdAt);
    } else {
      assert.equal(f.saved.length, 0); assert.equal(seen, null);
      if (active) assert.match(page.errorMsg, /先取消/);
    }
  });
}
test('Deep Read reads files above chat 10 MiB and rejects only its own 20 MiB boundary', () => {
  const source = fs.readFileSync(path.join(entryRoot, 'platform_impl/DeepReadSourceImporter.ets'), 'utf8');
  const start = source.indexOf('export const MAX_DEEPREAD_SOURCE_BYTES');
  const end = source.indexOf('\nconst readDocx', start);
  const code = ts.transpileModule(source.slice(start, end) + '\nexports.readSelectedBytes=readSelectedBytes;',
    { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText;
  let size = 12 * 1024 * 1024, reads = 0, closes = 0;
  const fileIo = { OpenMode: { READ_ONLY: 0 }, openSync: () => ({ fd: 1 }), statSync: () => ({ size }),
    readSync: (_fd, buffer) => { reads++; return buffer.byteLength; }, closeSync: () => { closes++; } };
  const exports = {}; new Function('exports', 'fileIo', code)(exports, fileIo);
  assert.equal(exports.readSelectedBytes('document').length, size); assert.equal(reads, 1);
  size = 20 * 1024 * 1024 + 1;
  assert.throws(() => exports.readSelectedBytes('large'), /20 MiB/); assert.equal(reads, 1); assert.equal(closes, 2);
  const chat = fs.readFileSync(path.join(entryRoot, 'platform_impl/DocumentAttachmentSupport.ets'), 'utf8');
  const match = /MAX_DOCUMENT_ATTACHMENT_BYTES: number = ([^;]+);/.exec(chat);
  assert.equal(new Function('return ' + match[1])(), 10 * 1024 * 1024);
});
test('late display-settings results cannot change a departed or newer Reader instance', async () => {
  const f = fixture(); let finish;
  f.storage.get = () => new Promise(resolve => { finish = resolve; });
  const page = actualPage('pages/DeepReadArticlePage.ets', ['loadDisplaySettings'],
    { ...f.env, getChatKvStore: () => ({ get: async () => 'serif' }), FONT_SCALE_MIN: 0.7, FONT_SCALE_MAX: 1.8 });
  Object.assign(page, { pageAlive: true, pageToken: 4, fontScale: 1, fontSerif: false });
  const pending = page.loadDisplaySettings(4); page.pageAlive = false; page.pageToken++;
  finish(180); await pending;
  assert.equal(page.fontScale, 1); assert.equal(page.fontSerif, false);
});
test('late first-use confirmation reads do not update the departed Reader or initialize its scheduler', async () => {
  const f = fixture(); let finish, schedulerReads = 0;
  f.repository.get = async () => null;
  const page = actualPage('pages/DeepReadArticlePage.ets', ['loadArticle', 'loadDisplaySettings'], {
    ...f.env, getChatKvStore: () => ({ get: async key => key === 'deepread_first_use_confirmed'
      ? new Promise(resolve => { finish = resolve; }) : 'sans' }),
    getDeepReadScheduler: async () => { schedulerReads++; return {}; }, FONT_SCALE_MIN: 0.7, FONT_SCALE_MAX: 1.8,
  });
  Object.assign(page, { pageAlive: true, pageToken: 4, topicId: 'topic', discoveryInputs: [], confirmed: true, running: true });
  const pending = page.loadArticle(4, false);
  for (let i = 0; i < 6 && !finish; i++) await Promise.resolve();
  assert.equal(typeof finish, 'function'); page.pageAlive = false; page.pageToken++;
  finish('false'); await pending;
  assert.equal(page.confirmed, true); assert.equal(page.running, true); assert.equal(schedulerReads, 0);
});
