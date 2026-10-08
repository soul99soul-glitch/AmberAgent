const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const { loadPureModule, entryRoot } = require('./deepread_ui_fixture.cjs');
const { resolveDeepReadArticleTemplate } = loadPureModule(path.join(entryRoot, 'platform_impl/DeepReadTemplate.ets'));
const source = fs.readFileSync(path.resolve(__dirname, '../../../entry/src/main/ets/pages/DeepReadArticlePage.ets'), 'utf8');
const method = name => {
  const start = source.search(new RegExp('^  (?:private\\s+)?(?:async\\s+)?' + name + '\\(', 'm'));
  assert.ok(start >= 0, name);
  const open = source.indexOf('{', start);
  let depth = 1, end = open + 1;
  for (; depth && end < source.length; end++) {
    if (source[end] === '{') depth++;
    if (source[end] === '}') depth--;
  }
  return source.slice(start, end);
};
const readyStates = () => Object.fromEntries(['OVERVIEW', 'NARRATIVE', 'ANALYSIS', 'EXTENDED_READING'].map(stage => [stage, { status: 'READY' }]));
const compile = text => ts.transpileModule(text, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;
function harness(overrides = {}) {
  const actions = [], writes = [], opened = [];
  const context = { loadDeepReadAppearance: async () => {},
    console, Promise, String, Number, Math, JSON, Error,
    makeEmptyDeepReadOutput: () => ({ summary: '' }),
    FONT_SCALE_MIN: Number(source.match(/FONT_SCALE_MIN: number = ([0-9.]+)/)[1]),
    FONT_SCALE_MAX: Number(source.match(/FONT_SCALE_MAX: number = ([0-9.]+)/)[1]), FONT_SCALE_STEP: 0.05,
    GENERATION_STEP_COUNT: 6, DOMAIN: 0x0001, TAG: 'DeepRead.Article',
    STAGE_ORDER: ['OVERVIEW', 'NARRATIVE', 'ANALYSIS', 'EXTENDED_READING'],
    firstFailureMessage: o => o.sectionStates?.ANALYSIS?.status === 'FAILED' ? o.sectionStates.ANALYSIS.errorMessage : null,
    isCacheEntryExpired: () => false,
    loadCustomTemplates: async () => [],
    resolveDeepReadArticleTemplate,
    hilog: { warn() {}, info() {} },
    firstFailedStage: o => o.sectionStates?.ANALYSIS?.status === 'FAILED' ? 'ANALYSIS' : null,
    isComplete: o => o.generationComplete === true && ['OVERVIEW', 'NARRATIVE', 'ANALYSIS', 'EXTENDED_READING'].every(stage => o.sectionStates?.[stage]?.status === 'READY'),
    hasDisplayableDeepReadOutput: o => !!(o.summary?.trim() || o.analysis.quotes.some(q => q.text.trim())),
    deepReadProgressSnapshot: () => ({ percent: 50, label: '撰写中' }),
    getChatKvStore: () => ({ get: async key => key === 'deepread_first_use_confirmed' ? 'true' : null }),
    getAppContainer: () => ({ storage: overrides.storage ?? {
      get: async (key, fallback) => fallback, set: async (...args) => writes.push(args),
    } }),
    promptAction: { showToast() {} },
    getRepository: () => overrides.repository ?? ({}),
    getDeepReadScheduler: overrides.getScheduler,
    getProductKind: () => 'deepread',
    module: { exports: {} },
  };
  const names = ['hasReadableOutput', 'retryGeneration', 'applyOutput', 'zoomIn', 'zoomOut',
    'persistFontScale', 'handleWebNavigation', 'cancelGeneration', 'retryFirstFailure',
    'loadDisplaySettings', 'loadArticle', 'phaseLabel', 'progressLabel', 'stageLabel', 'partialError',
    'loadTemplateHtml', 'switchToNativeReading', 'isWebTemplate', 'continuationNotice',
    'openExport', 'openSources', 'openArticlePanel', 'isArticlePanelActive', 'isExportActive', 'closeArticlePanel', 'onBackPress', 'clearEditorialMotion', 'celebratePublished', 'editorialActive', 'showNewsroom'];
  vm.runInNewContext(compile(`module.exports = class ReaderHarness { ${names.map(method).join('\n')} };`), context);
  const page = new context.module.exports();
  Object.assign(page, {
    pageAlive: true, pageToken: 4, topicId: 'topic', title: 'title', sourceUrl: '', seedUrls: [], discoveryInputs: [],
    motionCycle: 0, completionStamp: 0, pageVisible: true, appBackgrounded: false,
    output: { summary: '旧稿', analysis: { quotes: [] }, sectionStates: readyStates(), generationComplete: true,
      templateSnapshot: { id: 'editorial_slant', name: '杂志 · 立场', kind: 'editorial', html: null, capturedAt: 1 } },
    running: false, errorMsg: '', cancelled: false, awaitingContinue: false, runStartsUnreadable: false,
    articlePanel: '', articlePanelSession: 0, showRegenerateDialog: false,
    fontScale: 1, scheduler: { abort: id => actions.push(['abort', id]) },
    generateArticle: () => actions.push('regenerate'), continueGeneration: () => actions.push('continue'),
    refreshTemplateWeb: () => actions.push('refresh'),
    webRenderError: '', webAttached: false, webInitialized: true, webSession: 7, themeEpoch: 2,
    templateId: 'editorial_slant', nativeReadingOverride: false,
    openLink: url => opened.push(url), subscribeToUpdates: () => {},
    refreshWorkspaceStatus: async () => {},
  });
  return { page, actions, writes, opened };
}

test('partial quote remains visible while paused, running, and failed', () => {
  const { page } = harness();
  page.output = { summary: '', analysis: { quotes: [{ text: '已保存引述' }] } };
  for (const state of [{ running: true }, { running: false, awaitingContinue: true },
    { awaitingContinue: false, errorMsg: '后续失败' }]) {
    Object.assign(page, state);
    assert.equal(page.hasReadableOutput(), true);
  }
});

test('retry selects the failed section, incomplete continuation, or full regeneration', () => {
  const { page, actions } = harness();
  page.retryFirstFailure = () => actions.push('section');
  page.output.sectionStates.ANALYSIS = { status: 'FAILED' };
  page.retryGeneration();
  page.output.sectionStates = {}; page.output.generationComplete = false;
  page.retryGeneration();
  page.output.generationComplete = true; page.output.sectionStates = readyStates();
  page.retryGeneration();
  assert.deepEqual(actions, ['section', 'continue', 'regenerate']);
});

test('output updates refresh the attached Web consumer; obsolete page callbacks do not', () => {
  const { page, actions } = harness();
  const next = { summary: '新正文', generationComplete: false, generationPhase: 'WRITING', sectionStates: {} };
  page.applyOutput(next, 4);
  assert.equal(page.output, next);
  assert.deepEqual(actions, ['refresh']);
  page.applyOutput({ summary: '迟到内容' }, 3);
  assert.equal(page.output, next);
  page.pageAlive = false;
  page.applyOutput({ summary: '离页内容' }, 4);
  assert.equal(actions.length, 1);
});

test('font buttons clamp, refresh both template modes, and persist the display preference', async () => {
  const { page, writes, actions } = harness();
  page.fontScale = 1.79; page.zoomIn();
  assert.equal(page.fontScale, 1.8);
  page.fontScale = 0.71; page.zoomOut();
  assert.equal(page.fontScale, 0.7);
  await Promise.resolve();
  assert.deepEqual(writes, [['deepread_font_scale', 180], ['deepread_font_scale', 70]]);
  assert.deepEqual(actions, ['refresh', 'refresh']);
});

test('Web external navigation requires a live, main-frame user gesture', () => {
  const { page, opened } = harness();
  const request = (url, main = true, gesture = true) => ({
    getRequestUrl: () => url, isMainFrame: () => main, isRequestGesture: () => gesture,
  });
  assert.equal(page.handleWebNavigation(request('about:blank')), false);
  assert.equal(page.handleWebNavigation(request('#section')), false);
  assert.equal(page.handleWebNavigation(request('https://amber-deepread.invalid/article#dr-source-1')), false);
  assert.equal(page.handleWebNavigation(request('https://amber-deepread.invalid/article', true, false)), false);
  // Diagnostic baseline: engine evidence decides whether local data navigation
  // needs a different rule. This assertion records the current interception.
  assert.equal(page.handleWebNavigation(request('data:text/html,local-preview', true, false)), true);
  page.handleWebNavigation(request('https://safe.test/article'));
  page.handleWebNavigation(request('https://iframe.test', false));
  page.handleWebNavigation(request('https://redirect.test', true, false));
  page.handleWebNavigation(request('file:///private/document'));
  page.pageAlive = false;
  page.handleWebNavigation(request('https://late.test'));
  assert.deepEqual(opened, ['https://safe.test/article']);
});

test('cancelling a readable article leaves its content while aborting its real job', () => {
  const { page, actions } = harness();
  const old = page.output;
  page.running = true; page.cancelGeneration();
  assert.equal(page.output, old);
  assert.equal(page.running, false);
  assert.equal(page.cancelled, true);
  assert.deepEqual(actions, [['abort', 'topic']]);
});

test('leaving during scheduler resolution prevents a section retry from starting afterward', async () => {
  let resolve, starts = 0;
  const scheduler = new Promise(done => { resolve = done; });
  const { page } = harness({ getScheduler: () => scheduler });
  page.output.sectionStates.ANALYSIS = { status: 'FAILED' };
  page.retryFirstFailure();
  page.pageAlive = false;
  resolve({ runSection: async () => { starts++; return { ok: true, output: null }; } });
  await scheduler; await Promise.resolve(); await Promise.resolve();
  assert.equal(starts, 0);
});

test('native gallery excludes hero, chapter images, duplicates, and rejected assets', () => {
  const body = fs.readFileSync(path.resolve(__dirname,
    '../../../entry/src/main/ets/components/DeepReadNativeArticleBody.ets'), 'utf8');
  const extract = name => {
    const start = body.indexOf('  private ' + name + '(');
    const open = body.indexOf('{', start);
    let depth = 1, end = open + 1;
    for (; depth; end++) { if (body[end] === '{') depth++; if (body[end] === '}') depth--; }
    return body.slice(start, end);
  };
  const context = { loadDeepReadAppearance: async () => {}, module: { exports: {} }, IMAGE_CONFIDENCE: { REJECT: 'reject' } };
  vm.runInNewContext(compile(`module.exports = class NativeBody {
    ${extract('imageUrl')} ${extract('galleryAssets')}
  };`), context);
  const component = new context.module.exports();
  component.heroUrl = () => 'https://image.test/hero.jpg';
  component.output = {
    timeline: [{ imageUrl: 'https://image.test/timeline.jpg' }],
    corePoints: [{ imageUrl: 'https://image.test/core.jpg' }],
    imageAssets: ['hero', 'timeline', 'core', 'other', 'other', 'rejected'].map(id => ({
      url: 'https://image.test/' + id + '.jpg', confidence: id === 'rejected' ? 'reject' : 'inline',
    })),
  };
  assert.deepEqual(Array.from(component.galleryAssets(), asset => asset.url), ['https://image.test/other.jpg']);
});

test('COMPLETE phase alone preserves partial failure and loads as resumable history', async () => {
  const incomplete = { summary: '保留内容', analysis: { quotes: [] }, generationPhase: 'COMPLETE',
    generationComplete: false, sectionStates: { ...readyStates(), ANALYSIS: { status: 'FAILED', errorMessage: '分析中断' } } };
  const { page } = harness({ repository: { get: async () => ({ output: incomplete }) },
    getScheduler: async () => ({ isRunning: () => false }) });
  page.errorMsg = '已有错误';
  page.applyOutput(incomplete, 4);
  assert.equal(page.errorMsg, '已有错误');
  assert.equal(page.phaseLabel(incomplete), '生成失败');
  assert.equal(page.partialError(), '分析中断');
  page.loadDisplaySettings = async () => {};
  await page.loadArticle(4, false);
  assert.equal(page.awaitingContinue, true);
  assert.equal(page.running, false);
  assert.equal(page.partialError(), '分析中断');
});

test('loading an active job snapshots the existing readable cache and does not launch or replace its pipeline', async () => {
  for (const [summary, complete, newsroom] of [['', false, true], ['旧的部分稿', false, false], ['旧的完整稿', true, false]]) {
    const cached = { summary, analysis: { quotes: [] }, generationPhase: 'WRITING',
      generationComplete: complete, sectionStates: readyStates() };
    const { page } = harness({ repository: { get: async () => ({ output: cached }) },
      getScheduler: async () => ({ isRunning: () => true }) });
    page.output = null;
    let launched = 0;
    page.runNow = async () => { launched++; };
    await page.loadArticle(4, false);
    assert.equal(page.running, true);
    assert.equal(page.runStartsUnreadable, newsroom);
    assert.equal(page.showNewsroom(), newsroom);
    assert.equal(page.output, cached);
    assert.equal(launched, 0, 'the admitted job is only observed');
  }
});

test('font preference round trip uses the actual PreferencesStorage numeric adapter at both bounds', async () => {
  const prefs = new Map();
  const adapterSource = fs.readFileSync(path.resolve(__dirname,
    '../../../entry/src/main/ets/platform_impl/PreferencesStorage.ets'), 'utf8');
  const exports = {};
  vm.runInNewContext(compile(adapterSource), {
    exports, AppStorage: { get: () => ({}) },
    require: spec => spec === '@kit.ArkData' ? { preferences: { getPreferences: async () => ({
      get: async (key, fallback) => prefs.has(key) ? prefs.get(key) : fallback,
      put: async (key, value) => prefs.set(key, value), flush: async () => {},
    }) } } : {},
  });
  const storage = exports.createPreferencesStorage();
  const { page } = harness({ storage });
  for (const bound of [70, 180]) {
    page.fontScale = bound / 100;
    page.persistFontScale();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(typeof await storage.get('deepread_font_scale', 100), 'number');
    assert.equal(await storage.get('deepread_font_scale', 100), bound);
    page.fontScale = 1;
    await page.loadDisplaySettings();
    assert.equal(page.fontScale, bound / 100);
  }
});

test('Web load failure exposes a manual native path that preserves content and selected preference', () => {
  const { page } = harness();
  const old = page.output;
  page.webAttached = true;
  page.editorialHtml = () => '<html>article</html>';
  page.webController = { loadData: () => { throw new Error('controller destroyed'); } };
  page.loadTemplateHtml();
  assert.equal(page.templateId, 'editorial_slant');
  assert.ok(page.webRenderError.length > 0);
  page.switchToNativeReading();
  assert.equal(page.templateId, 'editorial_slant');
  assert.equal(page.nativeReadingOverride, true);
  assert.equal(page.output, old);
  assert.equal(page.webRenderError, '');
  assert.equal(page.webAttached, false);
});

test('Article passes raw HTML with Chinese, CSS colors and percent text to loadData when a base URL is supplied', () => {
  const { page } = harness();
  const calls = [];
  const html = '<!DOCTYPE html><html><head><style>#article{color:#123abc;width:100%}</style></head>'
    + '<body><h1 id="article">中文标题 #100%</h1><p>中文正文进度100%，符号#保持原样。</p></body></html>';
  page.webAttached = true;
  page.editorialHtml = () => html;
  page.webController = { loadData: (...args) => calls.push(args) };
  for (const id of ['editorial_slant', 'custom_raw_contract']) {
    page.templateId = id;
    page.loadTemplateHtml();
  }
  assert.equal(calls.length, 2);
  for (const call of calls) {
    assert.equal(call[0], html);
    assert.deepEqual(call.slice(1), ['text/html', 'UTF-8', 'https://amber-deepread.invalid/article', 'https://amber-deepread.invalid/article']);
  }
  assert.equal(page.webRenderError, '');
});

test('Article main-document errors expose manual native recovery while preserving article and selected template', () => {
  const context = { loadDeepReadAppearance: async () => {}, module: { exports: {} }, DOMAIN: 0x0001, TAG: 'DeepRead.Article',
    hilog: { info() {}, warn() {} }, String, Error };
  vm.runInNewContext(compile(`module.exports = class ErrorHook { ${method('onTemplateError')} };`), context);
  const onTemplateError = context.module.exports.prototype.onTemplateError;
  for (const id of ['editorial_slant', 'custom_error_callback']) {
    const { page, writes } = harness();
    const old = page.output;
    page.templateId = id;
    page.output.templateSnapshot = { id, name: id, kind: id === 'editorial_slant' ? 'editorial' : 'custom',
      html: id === 'editorial_slant' ? null : '<article>{{content}}</article>', capturedAt: 1 };
    page.webAttached = true;
    onTemplateError.call(page, true);
    assert.equal(page.webRenderError, '模板显示失败，文章内容仍已保留');
    assert.equal(page.output, old);
    assert.equal(page.templateId, id);
    assert.equal(writes.length, 0);
    page.switchToNativeReading();
    assert.equal(page.templateId, id);
    assert.equal(page.nativeReadingOverride, true);
    assert.equal(page.output, old);
    assert.equal(page.webRenderError, '');
  }
  for (const state of [
    { pageAlive: false }, { webAttached: false }, { nativeReadingOverride: true }, { mainFrame: false },
  ]) {
    const { page } = harness();
    page.webAttached = true;
    page.webRenderError = 'KEEP_NOTICE';
    Object.assign(page, state);
    const old = page.output;
    onTemplateError.call(page, state.mainFrame !== false);
    assert.equal(page.webRenderError, 'KEEP_NOTICE');
    assert.equal(page.output, old);
  }
});

test('Article waits for the current initial blank document once, while stale session/theme callbacks cannot affect a remount', () => {
  const names = ['attachTemplateWeb', 'onTemplatePageEnd', 'detachTemplateWeb', 'refreshTemplateWeb', 'onTemplateError'];
  const context = { loadDeepReadAppearance: async () => {}, module: { exports: {} }, DOMAIN: 0x0001, TAG: 'DeepRead.Article',
    hilog: { info() {}, warn() {} }, String, Error };
  vm.runInNewContext(compile(`module.exports = class WebCallbacks { ${names.map(method).join('\n')} };`), context);
  const { page } = harness();
  for (const name of names) page[name] = context.module.exports.prototype[name];
  const calls = [];
  page.webAttached = false;
  page.webInitialized = false;
  page.editorialHtml = () => `<html><head><style>#article{color:#123abc}</style></head><body>中文100% ${page.output.summary}</body></html>`;
  page.webController = { loadData: (...args) => calls.push(args) };
  const session = page.webSession;
  const epoch = page.themeEpoch;
  page.attachTemplateWeb(session, epoch);
  assert.equal(page.webAttached, true);
  assert.equal(page.webInitialized, false);
  assert.equal(calls.length, 0);
  page.loadTemplateHtml();
  page.onTemplatePageEnd('https://example.com/other', session, epoch);
  assert.equal(calls.length, 0);
  page.onTemplatePageEnd('about:blank', session, epoch);
  assert.equal(page.webInitialized, true);
  assert.equal(calls.length, 1);
  assert.equal(calls[0][0], page.editorialHtml());
  page.onTemplatePageEnd('about:blank', session, epoch);
  page.onTemplatePageEnd('https://example.com/article', session, epoch);
  assert.equal(calls.length, 1);
  page.zoomIn();
  assert.equal(calls.length, 2);
  page.applyOutput({ summary: '更新正文 #100%', generationComplete: false,
    generationPhase: 'WRITING', sectionStates: {}, templateSnapshot: page.output.templateSnapshot }, page.pageToken);
  assert.equal(calls.length, 3);
  assert.ok(calls[2][0].includes('更新正文 #100%'));

  page.themeEpoch++;
  page.attachTemplateWeb(session, page.themeEpoch);
  assert.equal(page.webAttached, true);
  assert.equal(page.webInitialized, false);
  page.onTemplatePageEnd('about:blank', session, epoch);
  page.detachTemplateWeb(session, epoch);
  page.onTemplateError(true, session, epoch);
  assert.equal(page.webAttached, true);
  assert.equal(page.webRenderError, '');
  assert.equal(calls.length, 3);
  page.onTemplatePageEnd('about:blank', session, page.themeEpoch);
  assert.equal(calls.length, 4);
  page.onTemplatePageEnd('about:blank', session, page.themeEpoch);
  assert.equal(calls.length, 4);

  page.webSession++;
  page.attachTemplateWeb(page.webSession, page.themeEpoch);
  page.onTemplatePageEnd('about:blank', session, page.themeEpoch);
  page.detachTemplateWeb(session, page.themeEpoch);
  page.onTemplateError(true, session, page.themeEpoch);
  assert.equal(page.webAttached, true);
  assert.equal(page.webInitialized, false);
  assert.equal(page.webRenderError, '');
  assert.equal(calls.length, 4);
  page.onTemplatePageEnd('about:blank', page.webSession, page.themeEpoch);
  assert.equal(calls.length, 5);
  page.detachTemplateWeb(page.webSession, page.themeEpoch);
  assert.equal(page.webAttached, false);
  assert.equal(page.webInitialized, false);
  page.onTemplatePageEnd('about:blank', page.webSession, page.themeEpoch);
  assert.equal(calls.length, 5);
  page.pageAlive = false;
  page.attachTemplateWeb(page.webSession, page.themeEpoch);
  assert.equal(page.webAttached, false);
  assert.equal(calls.length, 5);
});

test('cancelled empty metadata makes no saved-content promise', () => {
  const { page } = harness();
  page.cancelled = true;
  page.output = { summary: '', analysis: { quotes: [] } };
  assert.equal(page.continuationNotice(), '已取消，可重新开始');
  page.output.summary = '已保存部分稿';
  assert.equal(page.continuationNotice(), '生成已取消，已保存的进度仍可用');
});

test('a failed section without a provider error string still exposes a retry notice', () => {
  const { page } = harness();
  page.output.generationComplete = false;
  page.output.sectionStates.ANALYSIS = { status: 'FAILED', errorMessage: null };
  assert.equal(page.partialError(), '分析立场与影响失败');
});

test('export opens only for readable idle content and Back closes it before leaving', () => {
  const { page } = harness();
  page.running = true; page.openExport();
  assert.equal(page.articlePanel, '');
  page.running = false; page.output.summary = ''; page.openExport();
  assert.equal(page.articlePanel, '');
  page.output.summary = '已保存正文'; page.pageAlive = false; page.openExport();
  assert.equal(page.articlePanel, '');
  page.pageAlive = true; page.output.generationComplete = false; page.openExport();
  assert.equal(page.articlePanel, 'export', 'saved partial content can export text and Markdown');
  page.articlePanel = '';
  page.output.generationComplete = true;
  page.pageAlive = true; page.openExport();
  assert.equal(page.articlePanel, 'export');
  assert.equal(page.onBackPress(), true);
  assert.equal(page.articlePanel, '');
  assert.equal(page.onBackPress(), false);
});


test('closing and reopening export invalidates the departing panel before its exit animation finishes', () => {
  const { page } = harness();
  page.openExport();
  const first = page.articlePanelSession;
  assert.equal(page.isExportActive(first), true);
  page.openExport();
  assert.equal(page.articlePanelSession, first, 'an already open panel keeps its snapshot');
  page.onBackPress();
  assert.equal(page.isExportActive(first), false);
  page.openExport();
  const second = page.articlePanelSession;
  assert.ok(second > first);
  assert.equal(page.isExportActive(first), false, 'late file/share work from the exiting panel stays cancelled');
  page.closeArticlePanel(first);
  assert.equal(page.articlePanel, 'export', 'an old close callback cannot dismiss the reopened panel');
  page.pageVisible = false;
  assert.equal(page.isExportActive(second), false);
  page.pageVisible = true;
  assert.equal(page.isExportActive(second), true);
  page.closeArticlePanel(second);
  assert.equal(page.isExportActive(second), false);
});

test('sources and export share one modal owner, and an old close cannot dismiss a different reopened panel', () => {
  const { page } = harness();
  page.openSources();
  const sourcesSession = page.articlePanelSession;
  assert.equal(page.articlePanel, 'sources');
  assert.equal(page.isArticlePanelActive('sources', sourcesSession), true);
  page.openExport(); assert.equal(page.articlePanel, 'sources', 'the open modal blocks a second presentation');
  assert.equal(page.onBackPress(), true); assert.equal(page.articlePanel, '');
  assert.equal(page.isArticlePanelActive('sources', sourcesSession), false);
  page.openExport(); assert.equal(page.articlePanel, 'export');
  page.closeArticlePanel(sourcesSession); assert.equal(page.articlePanel, 'export');
  assert.equal(page.onBackPress(), true); assert.equal(page.articlePanel, '');
  assert.equal(page.onBackPress(), false, 'only another Back can leave the article');
});
