const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const root = path.resolve(__dirname, '../../../entry/src/main/ets');
const compile = source => ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;

test('actual selected configured and free registry paths use saved result size and report failed angles', async () => {
  const calls = []; const failures = [];
  const service = { type: 'tavily', id: 'selected', apiKey: 'key' };
  const settings = { searchCommonOptions: { resultSize: 13 }, searchServices: [service], searchServiceSelected: 0,
    searchEnabledServiceIds: [], searchBuiltinDuckDuckGoEnabled: false, searchBuiltinBingEnabled: true,
    searchBuiltinWikipediaEnabled: false, searchBuiltinHackerNewsEnabled: false, searchGoogleWebViewFallbackEnabled: false };
  const chat = { FREE_ENGINE_NAMES: { bing: 'Bing' }, SEARCH_SERVICE_TYPES: { tavily: 'Tavily' },
    freeEngineCoolingDown: () => false, looksTechnicalQuery: () => false,
    enabledServices: value => value.searchServices.filter(option => value.searchEnabledServiceIds.includes(option.id)),
    runFreeEngine: async (_id, query, options) => { calls.push([query, options.resultSize]);
      if (query === '背景') throw new Error('free angle unavailable'); return { items: [] }; },
    getSearchService: () => ({ search: async (request, options) => { calls.push([request.query, options.resultSize]);
      if (request.query === '背景') throw new Error('configured angle unavailable'); return { items: [] }; } }) };
  const helperExports = {};
  vm.runInNewContext(compile(fs.readFileSync(path.resolve(__dirname, '../../../chat/src/main/ets/search/deepread_search_registry.ts'), 'utf8')), {
    exports: helperExports, require: name => {
      if (['./search_aggregator.ts', './service_registry.ts', './search_service.ts'].includes(name)) return chat;
      throw new Error('unexpected helper import ' + name);
    }, setTimeout, clearTimeout, Promise, Date, Error,
  });
  const exports = {};
  vm.runInNewContext(compile(fs.readFileSync(path.join(root, 'platform_impl/SearchRegistry.ets'), 'utf8')), {
    exports, require: name => {
      if (name === '@amber/chat-domain') return { ...chat, ...helperExports };
      if (name === '@amber/deepread-domain' || name === './NewsNowProvider.ets') return {};
      if (name === '@kit.PerformanceAnalysisKit') return { hilog: { warn() {} } };
      throw new Error('unexpected registry import ' + name);
    }, setTimeout, clearTimeout, Promise, Date, Error,
  });
  const registry = exports.createTavilyAndFallbackRegistry({}, {}, async () => settings, () => {});
  const free = await registry.snapshot();
  await free.providers[0].search(['主题', '背景'], undefined, (query, error) => failures.push([query, error]));
  settings.searchEnabledServiceIds = ['selected'];
  const configured = await registry.snapshot();
  await configured.providers[0].search(['主题', '背景'], undefined, (query, error) => failures.push([query, error]));
  assert.deepEqual(calls, [['主题', 13], ['背景', 13], ['主题', 13], ['背景', 13]]);
  assert.deepEqual(failures, [['背景', 'Bing: free angle unavailable'], ['背景', 'Tavily: Error: configured angle unavailable']]);
});

const panelFixture = (overrides = {}) => {
  const source = fs.readFileSync(path.join(root, 'components/DeepReadExportPanel.ets'), 'utf8');
  const start = source.indexOf('  private openPdfPreview():');
  const end = source.indexOf('\n  private loadPdfHtml()', start);
  const exports = {};
  const renders = [];
  vm.runInNewContext(compile(`class Panel { ${source.slice(start, end)} } exports.Panel = Panel;`), {
    exports, Error, String, BG: '#111111', INK: '#eeeeee', INK3: '#bbbbbb', SURFACE: '#222222', LINE: '#444444',
    getProductKind: () => 'agent',
    PDF_DOCUMENT_URL: 'https://export.test/article', PRINT_CSS: '<style>PRINT</style>',
    isDeepReadPdfSupported: () => true,
    renderCapturedDeepReadTemplateHtml: (...args) => { renders.push(args); return '<head></head><article>BODY</article>'; },
    resolveDeepReadArticleTemplate: output => ({ template: output.templateSnapshot, message: '' }),
  });
  return { panel: Object.assign(new exports.Panel(), {
    currentActive: () => true, ready: true, complete: true, snapshot: { title: '文章', output: { summary: '已保存正文',
      templateSnapshot: { id: 'saved-custom', name: '保存版式', kind: 'custom', html: '<article>SAVED{{content}}</article>', capturedAt: 10 } } },
    busy: false, fontScale: 1.8, fontSerif: true, darkMode: false, customTemplateHtml: '<article>{{content}}</article>',
    accentColorStore: '#ffaa00', pdfPreviewId: 0, pdfPreview: false, errorMessage: '', ...overrides,
  }), renders };
};

test('PDF passes the saved template snapshot with full reading scale, serif and current appearance', () => {
  const { panel, renders } = panelFixture();
  panel.openPdfPreview();
  assert.equal(panel.pdfPreview, true);
  assert.equal(renders[0][0], panel.snapshot.title);
  assert.equal(renders[0][1], panel.snapshot.output);
  assert.notEqual(renders[0][1].templateSnapshot.html, panel.customTemplateHtml);
  assert.deepEqual({ ...renders[0][2] }, {
    fontScale: 1.8, fontSerif: true, readerLayout: 'classic', readerStyle: 'classic', dark: false,
    background: '#111111', foreground: '#eeeeee', muted: '#bbbbbb',
    surface: '#222222', border: '#444444', accent: '#ffaa00',
  });
  assert.ok(panel.pdfHtml.includes('PRINT'));
});

test('default PDF layout remains available and partial drafts cannot claim a full PDF', () => {
  const normal = panelFixture({ snapshot: { title: '默认文章', output: { summary: '正文',
    templateSnapshot: { id: 'none', name: '默认排版', kind: 'native', html: null, capturedAt: 10 } } } }); normal.panel.openPdfPreview();
  assert.equal(normal.renders[0][1].templateSnapshot.kind, 'native');
  const partial = panelFixture({ complete: false }); partial.panel.openPdfPreview();
  assert.equal(partial.panel.pdfPreview, false);
  assert.equal(partial.renders.length, 0);
});
