const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');
const ts = require('typescript');

const entryRoot = path.resolve(__dirname, '../../../entry/src/main/ets');
const domainRoot = path.resolve(__dirname, '../main/ets/domain');
const markdownFile = path.resolve(__dirname, '../../../chat/src/main/ets/chat/markdown_blocks.ts');
const cache = new Map();
const loadPureModule = filename => {
  filename = path.resolve(filename);
  if (cache.has(filename)) return cache.get(filename);
  const exports = {};
  cache.set(filename, exports);
  const requireModule = specifier => {
    if (specifier === '@amber/deepread-domain') return Object.assign({},
      ...['models.ts', 'helpers.ts', 'enums.ts', 'export.ts', 'synthesis_templates.ts'].map(name => loadPureModule(path.join(domainRoot, name))));
    if (specifier === '@amber/chat-domain') return loadPureModule(markdownFile);
    if (specifier.startsWith('.')) {
      const resolved = path.resolve(path.dirname(filename), specifier);
      for (const candidate of [resolved, resolved + '.ets', resolved + '.ts']) {
        if (fs.existsSync(candidate)) return loadPureModule(candidate);
      }
    }
    throw new Error(`Unexpected pure export dependency: ${specifier}`);
  };
  vm.runInNewContext(ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, { exports, require: requireModule, Error, String, Number, Date, Promise,
    Array, Object, Map, Set, JSON, Math, Uint8Array }, { filename });
  return exports;
};
const models = loadPureModule(path.join(domainRoot, 'models.ts'));
const helpers = loadPureModule(path.join(domainRoot, 'helpers.ts'));
const serializers = loadPureModule(path.join(domainRoot, 'export.ts'));
const textExports = {};
const textSource = fs.readFileSync(path.join(entryRoot, 'platform_impl/DeepReadExportFiles.ets'), 'utf8');
const textFormatter = textSource.slice(textSource.indexOf('const inlineText ='), textSource.indexOf('export const isDeepReadPdfSupported'));
vm.runInNewContext(ts.transpileModule(textFormatter, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText, { exports: textExports, ...loadPureModule(markdownFile) });
const renderer = loadPureModule(path.join(entryRoot, 'platform_impl/DeepReadTemplate.ets'));
const deferred = () => {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};
const savedEntry = (title = 'SAVED_TITLE', summary = 'SAVED_SUMMARY') => {
  const output = models.makeEmptyDeepReadOutput();
  output.summary = summary;
  output.generationComplete = true;
  output.generationPhase = 'COMPLETE';
  for (const stage of ['OVERVIEW', 'NARRATIVE', 'ANALYSIS', 'EXTENDED_READING']) {
    output.sectionStates[stage] = { status: 'READY', errorMessage: null };
  }
  output.references = [{ title: 'Saved reference', url: 'https://example.com/reference', source: 'SAVED_SOURCE', publishedAt: null }];
  return { topicId: 'saved-topic', title, sourceUrl: 'https://example.com/topic',
    output, updatedAt: 1700000000000, phase: 'COMPLETE', lastError: null };
};

// Execute the component's actual fields and non-Builder methods. Storage,
// document/share dialogs and Webview are controlled platform ports; serializers,
// completion checks and PDF HTML rendering are the real shipped modules.
const loadPanel = (entry = savedEntry()) => {
  const filename = path.join(entryRoot, 'components/DeepReadExportPanel.ets');
  const source = fs.readFileSync(filename, 'utf8');
  const constantsStart = source.indexOf('const PDF_DOCUMENT_URL:');
  const constantsEnd = source.indexOf('\n@Component', constantsStart);
  const fieldsStart = source.indexOf('  @StorageProp(');
  const methodsStart = source.indexOf('  aboutToAppear():');
  const methodsEnd = source.indexOf('\n  @Builder', methodsStart);
  assert.ok(constantsStart >= 0 && constantsEnd > constantsStart);
  assert.ok(fieldsStart >= 0 && methodsStart > fieldsStart && methodsEnd > methodsStart);
  const fields = source.slice(fieldsStart, methodsStart)
    .replace(/@(?:StorageProp\([^)]*\)|Prop|State)\s*/g, '');
  const fixture = source.slice(constantsStart, constantsEnd)
    + `\nclass ExportPanelFixture { ${fields}\n${source.slice(methodsStart, methodsEnd)} }`
    + '\nexports.ExportPanelFixture = ExportPanelFixture;';
  const state = {
    entry, getEntry: null, repositoryCalls: [], pdfSupported: true, productKind: 'agent',
    platformCalls: [], handlers: {}, loadCalls: [], pdfCalls: [],
    loadError: null, createPdf: null, currentUrl: '', getUrlError: null, getUrlCalls: 0,
    context: { cacheDir: '/cache' },
  };
  const platform = {};
  for (const name of ['shareDeepReadText', 'saveDeepReadTextFile', 'shareDeepReadTextFile', 'saveDeepReadMarkdown', 'shareDeepReadMarkdown',
    'saveDeepReadPdf', 'shareDeepReadPdf']) {
    platform[name] = async (...args) => {
      state.platformCalls.push({ name, args });
      if (state.handlers[name]) return state.handlers[name](...args);
      return name.startsWith('save') ? true : undefined;
    };
  }
  class WebviewController {
    loadData(...args) {
      state.loadCalls.push(args);
      if (state.loadError) throw state.loadError;
    }
    getUrl() {
      state.getUrlCalls++;
      if (state.getUrlError) throw state.getUrlError;
      return state.currentUrl;
    }
    async createPdf(configuration) {
      state.pdfCalls.push(configuration);
      if (state.createPdf) return state.createPdf(configuration);
      return { pdfArrayBuffer: () => new Uint8Array([37, 80, 68, 70]) };
    }
  }
  const exports = {};
  vm.runInNewContext(ts.transpileModule(fixture, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, { exports, ...platform, Error, String, Number, Math, Date, Promise, Uint8Array,
    ACCENT: '#B8623A', BG: '#FFFFFF', INK: '#1B1A17', INK3: '#57544C', SURFACE: '#FFFFFF', LINE: '#D6D1C4', webview: { WebviewController },
    getProductKind: () => state.productKind,
    D: { paper: '#FBF7F1', ink: '#2A2320', muted: '#6E6254', card: '#FFFDF9', rule: '#E4D9CB', accent: '#C8402F' },
    AppStorage: { get: () => state.context },
    getRepository: () => ({ get: async topicId => {
      state.repositoryCalls.push(topicId);
      return state.getEntry ? state.getEntry(topicId) : state.entry;
    } }),
    sha256HexUtf8: value => crypto.createHash('sha256').update(value, 'utf8').digest('hex'),
    isComplete: helpers.isComplete, hasDisplayableDeepReadOutput: helpers.hasDisplayableDeepReadOutput,
    deepReadToText: serializers.deepReadToText,
    deepReadMarkdownToText: textExports.deepReadMarkdownToText,
    deepReadToMarkdown: serializers.deepReadToMarkdown,
    renderCapturedDeepReadTemplateHtml: renderer.renderCapturedDeepReadTemplateHtml,
    resolveDeepReadArticleTemplate: renderer.resolveDeepReadArticleTemplate,
    isDeepReadPdfSupported: () => state.pdfSupported,
  }, { filename });
  const page = new exports.ExportPanelFixture();
  page.topicId = 'saved-topic';
  page.title = 'TRANSIENT_UI_TITLE';
  return { page, state, source };
};
const pdfCallbacks = (page, source, previewId, epoch) => {
  const builder = source.slice(source.indexOf('  PdfPreview('), source.indexOf('\n  build():'));
  const exports = {};
  const bodies = {
    attached: /\.onControllerAttached\(\(\): void => \{([\s\S]*?)\}\)/,
    end: /\.onPageEnd\(\(event: OnPageEndEvent\): void => \{([\s\S]*?)\}\)/,
    error: /\.onErrorReceive\(\(event: OnErrorReceiveEvent\): void => \{([\s\S]*?)\}\)/,
  };
  for (const [name, pattern] of Object.entries(bodies)) {
    const match = builder.match(pattern);
    assert.ok(match, `${name} callback`);
    const text = `exports.${name} = function(event) { ${match[1]} };`;
    vm.runInNewContext(ts.transpileModule(text, {
      compilerOptions: { target: ts.ScriptTarget.ES2022 },
    }).outputText, { exports, previewId, epoch });
  }
  return {
    attached: () => exports.attached.call(page),
    end: url => exports.end.call(page, { url }),
    error: url => exports.error.call(page, {
      request: { getRequestUrl: () => url, isMainFrame: () => true },
      error: { getErrorInfo: () => 'OLD_THEME' },
    }),
  };
};
const loadReady = async fixture => {
  fixture.page.alive = true;
  await fixture.page.loadSnapshot();
  assert.equal(fixture.page.ready, true);
  return fixture;
};
const makePdfReady = page => {
  page.openPdfPreview();
  page.onPdfAttached(page.pdfPreviewId, page.themeEpoch);
  page.onPdfPageEnd('about:blank', page.pdfPreviewId, page.themeEpoch);
  page.onPdfPageEnd(page.pdfDocumentUrl);
  assert.equal(page.pdfReady, true);
};

test('actual data page-end confirms the unique current document, while blank and old owner URLs cannot enable PDF', async () => {
  const { page, state, source } = await loadReady(loadPanel());
  page.openPdfPreview();
  const first = pdfCallbacks(page, source, page.pdfPreviewId, page.themeEpoch);
  first.attached();
  first.end('about:blank');
  state.currentUrl = page.pdfDocumentUrl;
  first.end('about:blank');
  assert.equal(page.pdfReady, false);
  assert.equal(state.getUrlCalls, 0, 'blank completion does not query or enable the document');
  first.end('data:text/html;base64,actual-engine-document');
  assert.equal(page.pdfReady, true, 'the real engine event is data: and getUrl is the unique virtual URL');
  assert.equal(state.getUrlCalls, 1);

  page.closePdfPreview();
  page.openPdfPreview();
  const second = pdfCallbacks(page, source, page.pdfPreviewId, page.themeEpoch);
  second.attached();
  second.end('about:blank');
  state.currentUrl = 'https://deepread-export.local/article?preview=1';
  second.end('data:text/html;base64,old-virtual-document');
  assert.equal(page.pdfReady, false);
  state.currentUrl = page.pdfDocumentUrl;
  const beforeOldPreview = state.getUrlCalls;
  first.end('data:text/html;base64,old-preview-event');
  assert.equal(page.pdfReady, false);
  assert.equal(state.getUrlCalls, beforeOldPreview, 'old preview is rejected before reading the controller');
  second.end('data:text/html;base64,current-document');
  assert.equal(page.pdfReady, true);

  page.themeEpoch++;
  const themed = pdfCallbacks(page, source, page.pdfPreviewId, page.themeEpoch);
  themed.attached();
  themed.end('about:blank');
  const beforeOldTheme = state.getUrlCalls;
  second.end('data:text/html;base64,old-theme-event');
  assert.equal(page.pdfReady, false);
  assert.equal(state.getUrlCalls, beforeOldTheme, 'old theme is rejected before reading the controller');
  themed.end('data:text/html;base64,current-theme-document');
  assert.equal(page.pdfReady, true);
  page.onPdfError(page.pdfDocumentUrl, true, 'DOCUMENT_FAILURE');
  themed.end('data:text/html;base64,late-completion-after-failure');
  assert.equal(page.pdfReady, false);
  page.aboutToDisappear();
  const readsBeforeLeaving = state.getUrlCalls;
  themed.end('data:text/html;base64,late-completion-after-leaving');
  assert.equal(state.getUrlCalls, readsBeforeLeaving);
});

test('getUrl failure during an actual data completion exposes a retryable preview error and cannot revive PDF', async () => {
  const { page, state, source } = await loadReady(loadPanel());
  page.openPdfPreview();
  const callbacks = pdfCallbacks(page, source, page.pdfPreviewId, page.themeEpoch);
  callbacks.attached();
  callbacks.end('about:blank');
  state.getUrlError = new Error('GET_URL_FAILURE');
  callbacks.end('data:text/html;base64,actual-engine-document');
  assert.equal(page.pdfReady, false);
  assert.equal(page.pdfLoadStarted, false);
  assert.ok(page.errorMessage.includes('PDF 预览加载失败'));
  assert.ok(page.errorMessage.includes('返回菜单'));
  state.getUrlError = null;
  state.currentUrl = page.pdfDocumentUrl;
  callbacks.end('data:text/html;base64,late-completion');
  assert.equal(page.pdfReady, false);
  page.closePdfPreview();
  assert.equal(page.errorMessage, '');
});

test('PDF first load waits for the initial blank page and ignores duplicate, stale preview and old-theme callbacks', async () => {
  const { page, state, source } = await loadReady(loadPanel());
  page.openPdfPreview();
  const firstId = page.pdfPreviewId, firstEpoch = page.themeEpoch;
  const first = pdfCallbacks(page, source, firstId, firstEpoch);
  first.attached();
  assert.equal(state.loadCalls.length, 0, 'attached must not race the initial src');
  first.end('about:blank');
  assert.equal(state.loadCalls.length, 1);
  assert.equal(page.pdfReady, false, 'blank completion only starts the real document');
  first.end('about:blank');
  assert.equal(state.loadCalls.length, 1, 'initial blank cannot reload the document twice');
  first.end(page.pdfDocumentUrl);
  assert.equal(page.pdfReady, true);

  page.closePdfPreview();
  page.openPdfPreview();
  const secondId = page.pdfPreviewId;
  const second = pdfCallbacks(page, source, secondId, firstEpoch);
  second.attached();
  first.end('about:blank');
  assert.equal(state.loadCalls.length, 1, 'previous preview cannot initialize the new view');
  second.end('about:blank');
  assert.equal(state.loadCalls.length, 2);

  page.themeEpoch++;
  const secondEpoch = page.themeEpoch;
  const themed = pdfCallbacks(page, source, secondId, secondEpoch);
  themed.attached();
  second.end('about:blank');
  second.error(page.pdfDocumentUrl);
  assert.equal(state.loadCalls.length, 2);
  assert.equal(page.errorMessage, '');
  themed.end('about:blank');
  assert.equal(state.loadCalls.length, 3, 'newly mounted theme gets one complete document');
  second.end(page.pdfDocumentUrl);
  assert.equal(page.pdfReady, false);
  themed.end(page.pdfDocumentUrl);
  assert.equal(page.pdfReady, true);
  page.aboutToDisappear();
  themed.end('about:blank');
  assert.equal(state.loadCalls.length, 3);
});

test('export actions use the one persisted complete snapshot, independent of later generation UI changes', async () => {
  const { page, state } = await loadReady(loadPanel());
  assert.equal(page.articleTitle, 'SAVED_TITLE');
  assert.equal(page.loading, false);
  state.entry = savedEntry('NEWER_TITLE', 'NEWER_SUMMARY');
  page.title = 'OTHER_TRANSIENT_UI_TITLE';
  for (const action of ['text', 'markdown-save', 'markdown-share']) await page.perform(action);
  assert.deepEqual(state.repositoryCalls, ['saved-topic']);
  assert.equal(state.platformCalls.length, 3);
  for (const call of state.platformCalls) {
    assert.ok(call.args[2].includes('SAVED_SUMMARY'));
    assert.ok(call.args[2].includes('Saved reference'));
    assert.equal(call.args[2].includes('NEWER_SUMMARY'), false);
    assert.equal(call.args[2].includes('TRANSIENT_UI'), false);
    assert.equal(call.args[3](), true);
  }
  assert.equal(state.platformCalls[0].args[1], 'SAVED_TITLE');
  assert.match(state.platformCalls[1].args[1], /^deepread-[0-9a-f]{64}\.md$/);
});

test('missing or empty saved articles cannot enter export despite a COMPLETE phase label', async () => {
  const empty = savedEntry();
  empty.output = models.makeEmptyDeepReadOutput();
  for (const entry of [null, empty]) {
    const { page, state } = loadPanel(entry);
    page.alive = true;
    await page.loadSnapshot();
    assert.equal(page.ready, false);
    assert.equal(page.snapshot, null);
    assert.ok(page.errorMessage.length > 0);
    await page.perform('text');
    page.openPdfPreview();
    assert.equal(state.platformCalls.length, 0);
    assert.equal(page.pdfPreview, false);
  }
});

test('partial saved article allows text and Markdown with an explicit draft status while PDF remains disabled', async () => {
  const partial = savedEntry();
  partial.output.sectionStates.ANALYSIS = { status: 'FAILED', errorMessage: 'not saved' };
  const { page, state } = await loadReady(loadPanel(partial));
  assert.equal(page.complete, false);
  await page.perform('text');
  await page.perform('markdown-share');
  assert.equal(state.platformCalls.length, 2);
  for (const call of state.platformCalls) assert.ok(call.args[2].includes('生成状态：部分稿'));
  page.openPdfPreview();
  assert.equal(page.pdfPreview, false);
});

test('late repository completion or failure after leaving the panel cannot publish state', async () => {
  for (const reject of [false, true]) {
    const { page, state } = loadPanel();
    const result = deferred();
    state.getEntry = () => result.promise;
    page.alive = true;
    const loading = page.loadSnapshot();
    page.aboutToDisappear();
    if (reject) result.reject(new Error('LATE_READ_FAILURE'));
    else result.resolve(savedEntry());
    await loading;
    assert.equal(page.ready, false);
    assert.equal(page.snapshot, null);
    assert.equal(page.errorMessage, '');
  }
});

test('busy prevents duplicate document actions and a cancelled picker creates no error or success claim', async () => {
  const { page, state } = await loadReady(loadPanel());
  const picker = deferred();
  state.handlers.saveDeepReadMarkdown = () => picker.promise;
  const first = page.perform('markdown-save');
  assert.equal(page.busy, true);
  await page.perform('markdown-save');
  await page.perform('text');
  assert.equal(state.platformCalls.length, 1);
  picker.resolve(false);
  await first;
  assert.equal(page.busy, false);
  assert.equal(page.note, '');
  assert.equal(page.errorMessage, '');
});

test('document actions receive a live guard and late picker completion cannot update a closed panel', async () => {
  const { page, state } = await loadReady(loadPanel());
  const picker = deferred();
  state.handlers.saveDeepReadMarkdown = () => picker.promise;
  const operation = page.perform('markdown-save');
  const active = state.platformCalls[0].args[3];
  assert.equal(active(), true);
  page.aboutToDisappear();
  assert.equal(active(), false);
  picker.resolve(true);
  await operation;
  assert.equal(page.note, '');
  assert.equal(page.errorMessage, '');
});

test('host closing before child disappearance prevents a pending picker write and late component updates', async () => {
  const { page, state } = await loadReady(loadPanel());
  let hostActive = true;
  page.isActive = () => hostActive;
  const picker = deferred();
  let writes = 0;
  state.handlers.saveDeepReadMarkdown = async (_context, _name, _content, active) => {
    await picker.promise;
    if (!active()) return false;
    writes++;
    return true;
  };
  const operation = page.perform('markdown-save');
  assert.equal(state.platformCalls[0].args[3](), true);
  hostActive = false;
  const before = { busy: page.busy, note: page.note, errorMessage: page.errorMessage };
  assert.equal(page.alive, true, 'This reproduces the host-close window before child disappearance');
  picker.resolve();
  await operation;
  assert.equal(writes, 0);
  assert.equal(state.platformCalls[0].args[3](), false);
  assert.deepEqual({ busy: page.busy, note: page.note, errorMessage: page.errorMessage }, before);
});

test('live document failures are visible, while late failures are suppressed and sharing only claims the panel opened', async () => {
  const { page, state } = await loadReady(loadPanel());
  state.handlers.shareDeepReadText = async () => { throw new Error('SHARE_FAILURE'); };
  await page.perform('text');
  assert.ok(page.errorMessage.includes('SHARE_FAILURE'));
  assert.equal(page.busy, false);
  delete state.handlers.shareDeepReadText;
  await page.perform('text');
  assert.equal(page.note, '已打开文本分享面板');
  await page.perform('markdown-share');
  assert.equal(page.note, '已打开 Markdown 分享面板');
  const late = deferred();
  state.handlers.shareDeepReadText = () => late.promise;
  const operation = page.perform('text');
  page.aboutToDisappear();
  late.reject(new Error('LATE_SHARE_FAILURE'));
  await operation;
  assert.equal(page.errorMessage, '');
  assert.equal(page.note, '');
});

test('the PDF capability gate blocks preview and conversion with an accurate API requirement', async () => {
  const { page, state } = await loadReady(loadPanel());
  state.pdfSupported = false;
  page.openPdfPreview();
  assert.equal(page.pdfPreview, false);
  assert.ok(page.errorMessage.includes('API 14'));
  assert.equal(state.loadCalls.length, 0);
  state.pdfSupported = true;
  makePdfReady(page);
  state.pdfSupported = false;
  await page.exportPdf(false);
  assert.equal(state.pdfCalls.length, 0);
  assert.ok(page.errorMessage.includes('API 14'));
});

test('PDF is ready only after the current loaded document ends; old preview callbacks cannot enable it', async () => {
  const { page, state } = await loadReady(loadPanel(savedEntry('中文PDF标题 #100%', '中文PDF正文进度100%，符号#保持原样。')));
  page.openPdfPreview();
  const firstUrl = page.pdfDocumentUrl;
  page.onPdfPageEnd(firstUrl);
  assert.equal(page.pdfReady, false);
  page.onPdfAttached(page.pdfPreviewId, page.themeEpoch);
  page.onPdfPageEnd('about:blank');
  assert.equal(page.pdfReady, false);
  page.onPdfPageEnd(firstUrl);
  assert.equal(page.pdfReady, true);
  const html = state.loadCalls[0][0];
  assert.equal(html, page.pdfHtml, 'A non-empty base URL requires raw rendered HTML');
  assert.ok(html.includes('中文PDF标题 #100%'));
  assert.ok(html.includes('中文PDF正文进度100%'));
  assert.ok(html.includes('#FFFFFF'));
  assert.ok(html.includes('Saved reference'));
  assert.ok(html.includes('@page{size:A4}'));
  assert.match(html, /\.reading > a,\.reading > div\{break-inside:avoid\}/,
    'the actual PDF HTML keeps each reading title and publisher together');
  assert.doesNotMatch(html, /(?:^|\})[^{}]*\bsection\b[^{}]*\{[^{}]*break-inside:avoid/,
    'reading sections retain natural page breaks');
  assert.deepEqual(state.loadCalls[0].slice(1), ['text/html', 'UTF-8', firstUrl, firstUrl]);
  page.closePdfPreview();
  page.openPdfPreview();
  page.onPdfAttached(page.pdfPreviewId, page.themeEpoch);
  page.onPdfPageEnd('about:blank');
  assert.notEqual(page.pdfDocumentUrl, firstUrl);
  page.onPdfPageEnd(firstUrl);
  page.onPdfPageEnd(firstUrl + '#old-fragment');
  assert.equal(page.pdfReady, false);
  page.onPdfPageEnd(page.pdfDocumentUrl + '#current-fragment');
  assert.equal(page.pdfReady, true);
  page.pdfReady = false;
  page.aboutToDisappear();
  page.onPdfPageEnd(page.pdfDocumentUrl);
  assert.equal(page.pdfReady, false);
});

test('PDF conversion waits for rendered content and uses A4 configuration and exact returned bytes', async () => {
  const { page, state } = await loadReady(loadPanel());
  page.openPdfPreview();
  await page.exportPdf(false);
  assert.equal(state.pdfCalls.length, 0);
  page.onPdfAttached(page.pdfPreviewId, page.themeEpoch);
  page.onPdfPageEnd('about:blank');
  await page.exportPdf(false);
  assert.equal(state.pdfCalls.length, 0);
  page.onPdfPageEnd(page.pdfDocumentUrl);
  const bytes = new Uint8Array([0, 0, 37, 80, 68, 70, 0]).subarray(2, 6);
  state.createPdf = async () => ({ pdfArrayBuffer: () => bytes });
  await page.exportPdf(false);
  assert.equal(state.pdfCalls.length, 1);
  const configuration = JSON.parse(JSON.stringify(state.pdfCalls[0]));
  assert.deepEqual(configuration, {
    width: 8.27, height: 11.69, marginTop: 0.5, marginBottom: 0.5,
    marginLeft: 0.5, marginRight: 0.5, scale: 1, shouldPrintBackground: true,
  });
  assert.equal(state.platformCalls.length, 1);
  assert.equal(state.platformCalls[0].name, 'saveDeepReadPdf');
  assert.match(state.platformCalls[0].args[1], /^deepread-[0-9a-f]{64}\.pdf$/);
  assert.equal(state.platformCalls[0].args[2], bytes);
  assert.equal(page.note, 'PDF 文件已保存');
  assert.equal(page.busy, false);
});

test('leaving during PDF conversion prevents a late result from opening a save or share dialog', async () => {
  for (const reject of [false, true]) {
    const { page, state } = await loadReady(loadPanel());
    makePdfReady(page);
    const conversion = deferred();
    state.createPdf = () => conversion.promise;
    const operation = page.exportPdf(true);
    await page.exportPdf(false);
    assert.equal(state.pdfCalls.length, 1);
    page.aboutToDisappear();
    if (reject) conversion.reject(new Error('LATE_PDF_FAILURE'));
    else conversion.resolve({ pdfArrayBuffer: () => new Uint8Array([37, 80, 68, 70]) });
    await operation;
    assert.equal(state.platformCalls.length, 0);
    assert.equal(page.note, '');
    assert.equal(page.errorMessage, '');
  }
});

test('host closing before child disappearance discards pending PDF conversion without opening file UI', async () => {
  const { page, state } = await loadReady(loadPanel());
  let hostActive = true;
  page.isActive = () => hostActive;
  makePdfReady(page);
  const conversion = deferred();
  state.createPdf = () => conversion.promise;
  const operation = page.exportPdf(true);
  assert.equal(state.pdfCalls.length, 1);
  hostActive = false;
  const before = { busy: page.busy, note: page.note, errorMessage: page.errorMessage };
  assert.equal(page.alive, true, 'The host has closed but aboutToDisappear has not run');
  conversion.resolve({ pdfArrayBuffer: () => new Uint8Array([37, 80, 68, 70]) });
  await operation;
  assert.equal(state.platformCalls.length, 0);
  assert.deepEqual({ busy: page.busy, note: page.note, errorMessage: page.errorMessage }, before);
});

test('empty PDF output cannot open a file dialog or claim that export succeeded', async () => {
  for (const share of [false, true]) {
    const { page, state } = await loadReady(loadPanel());
    makePdfReady(page);
    state.createPdf = async () => ({ pdfArrayBuffer: () => new Uint8Array(0) });
    await page.exportPdf(share);
    assert.equal(state.platformCalls.length, 0);
    assert.ok(page.errorMessage.includes('PDF 导出失败'));
    assert.equal(page.note, '');
    assert.equal(page.busy, false);
  }
});

test('PDF conversion failure exposes the cause, resets busy and retains the saved snapshot for retry', async () => {
  const { page, state } = await loadReady(loadPanel());
  makePdfReady(page);
  const snapshot = page.snapshot;
  state.createPdf = async () => { throw new Error('PDF_CONVERSION_FAILURE'); };
  await page.exportPdf(false);
  assert.equal(state.platformCalls.length, 0);
  assert.equal(page.snapshot, snapshot);
  assert.equal(page.busy, false);
  assert.ok(page.errorMessage.includes('PDF_CONVERSION_FAILURE'));
});

test('only an active current-document main-frame error disables PDF, and a late page end cannot revive it', async () => {
  const { page } = await loadReady(loadPanel());
  makePdfReady(page);
  page.onPdfError(page.pdfDocumentUrl, false, 'IMAGE_FAILURE');
  page.onPdfError('https://deepread-export.local/article?preview=0', true, 'OLD_DOCUMENT_FAILURE');
  assert.equal(page.pdfReady, true);
  assert.equal(page.pdfLoadStarted, true);
  assert.equal(page.errorMessage, '');
  page.onPdfError(page.pdfDocumentUrl, true, 'CURRENT_DOCUMENT_FAILURE');
  assert.equal(page.pdfReady, false);
  assert.equal(page.pdfLoadStarted, false);
  assert.ok(page.errorMessage.includes('CURRENT_DOCUMENT_FAILURE'));
  page.onPdfPageEnd(page.pdfDocumentUrl);
  assert.equal(page.pdfReady, false);
  page.closePdfPreview();
  page.onPdfError(page.pdfDocumentUrl, true, 'LATE_DOCUMENT_FAILURE');
  assert.equal(page.errorMessage, '');
});

test('PDF save cancellation and share status are accurate and the live guard follows the preview', async () => {
  const { page, state } = await loadReady(loadPanel());
  makePdfReady(page);
  state.handlers.saveDeepReadPdf = async () => false;
  await page.exportPdf(false);
  assert.equal(page.note, '');
  assert.equal(page.errorMessage, '');
  await page.exportPdf(true);
  assert.equal(page.note, '已打开 PDF 分享面板');
  const active = state.platformCalls.at(-1).args[3];
  assert.equal(active(), true);
  page.closePdfPreview();
  assert.equal(active(), false);
});

test('PDF preview load failures are visible and navigation cannot replace it with an external page', async () => {
  const { page, state } = await loadReady(loadPanel());
  page.openPdfPreview();
  state.loadError = new Error('WEB_LOAD_FAILURE');
  page.onPdfAttached(page.pdfPreviewId, page.themeEpoch);
  page.onPdfPageEnd('about:blank');
  assert.equal(page.pdfLoadStarted, false);
  assert.equal(page.pdfReady, false);
  assert.ok(page.errorMessage.includes('WEB_LOAD_FAILURE'));
  const navigation = (url, gesture = false) => page.handleNavigation({
    getRequestUrl: () => url, isRequestGesture: () => gesture,
  });
  assert.equal(navigation('about:blank'), false);
  assert.equal(navigation('data:text/html,preview'), false);
  assert.equal(navigation(page.pdfDocumentUrl), false);
  assert.equal(navigation(page.pdfDocumentUrl + '#section', true), false);
  assert.equal(navigation('https://example.com/external', true), true);
  assert.equal(navigation('file:///data/secret', true), true);
});


test('TXT file actions use one saved snapshot and preserve the explicit partial status', async () => {
  const partial = savedEntry('已保存标题', '**已保存正文中文😀**\n\n[原始来源](https://example.com/原始)');
  partial.output.sectionStates.ANALYSIS = { status: 'FAILED', errorMessage: 'not saved' };
  const { page, state } = await loadReady(loadPanel(partial));
  state.entry = savedEntry('新的标题', '新的正文');
  await page.perform('text-save');
  assert.equal(state.platformCalls[0].name, 'saveDeepReadTextFile');
  assert.match(state.platformCalls[0].args[1], /^deepread-[0-9a-f]{64}-partial\.txt$/);
  assert.ok(state.platformCalls[0].args[2].includes('已保存正文中文😀'));
  assert.ok(state.platformCalls[0].args[2].includes('生成状态：部分稿'));
  assert.equal(state.platformCalls[0].args[2].includes('**已保存'), false);
  assert.ok(state.platformCalls[0].args[2].includes('原始来源 (https://example.com/原始)'));
  assert.equal(state.platformCalls[0].args[2].includes('新的正文'), false);
  assert.equal(page.note, 'TXT 文件已保存');
  await page.perform('text-share');
  assert.equal(state.platformCalls[1].name, 'shareDeepReadTextFile');
  assert.equal(state.platformCalls[1].args[2], state.platformCalls[0].args[2]);
  assert.equal(page.note, '已打开 TXT 文件分享面板');
  state.handlers.saveDeepReadTextFile = async () => false;
  await page.perform('text-save'); assert.equal(page.note, '');
  state.handlers.shareDeepReadTextFile = async () => { throw new Error('TXT_SHARE_FAILURE'); };
  await page.perform('text-share'); assert.match(page.errorMessage, /TXT_SHARE_FAILURE/);
});

test('a reopened export instance invalidates the old picker owner and repository snapshot', async () => {
  const { page, state } = await loadReady(loadPanel());
  const picker = deferred(); let writes = 0;
  state.handlers.saveDeepReadTextFile = async (_context, _name, _content, active) => {
    await picker.promise; if (!active()) return false; writes++; return true;
  };
  const pending = page.perform('text-save');
  const oldRead = deferred(); state.getEntry = () => oldRead.promise;
  const reading = page.loadSnapshot();
  page.aboutToDisappear(); state.getEntry = null; state.entry = savedEntry('NEW_OWNER', 'NEW_BODY');
  page.aboutToAppear(); await Promise.resolve(); await Promise.resolve();
  picker.resolve(); oldRead.resolve(savedEntry('STALE_OWNER', 'STALE_BODY'));
  await pending; await reading;
  assert.equal(writes, 0); assert.equal(page.articleTitle, 'NEW_OWNER');
  assert.equal(page.snapshot.title, 'NEW_OWNER'); assert.equal(page.note, '');
});


test('PDF renders the saved custom template snapshot despite global template changes', async () => {
  const entry = savedEntry('保存版式文章', '保存版式正文');
  entry.output.templateSnapshot = { id: 'saved-custom', name: '保存版式', kind: 'custom',
    html: '<html><head></head><body><article>FROZEN_LAYOUT<h1>{{title}}</h1>{{content}}</article></body></html>', capturedAt: 100 };
  const { page } = await loadReady(loadPanel(entry));
  page.customTemplateHtml = '<article>GLOBAL_CHANGED{{content}}</article>';
  page.openPdfPreview();
  assert.equal(page.pdfPreview, true);
  assert.ok(page.pdfHtml.includes('FROZEN_LAYOUT'));
  assert.ok(page.pdfHtml.includes('保存版式正文'));
  assert.equal(page.pdfHtml.includes('GLOBAL_CHANGED'), false);
});

test('legacy missing custom snapshot uses the explicit same default fallback as reading', async () => {
  const entry = savedEntry(); entry.output.templateId = 'deleted-custom';
  const { page } = await loadReady(loadPanel(entry));
  page.customTemplateHtml = '<article>UNRELATED_GLOBAL{{content}}</article>';
  page.openPdfPreview();
  assert.equal(page.pdfPreview, true); assert.ok(page.pdfHtml.includes('SAVED_SUMMARY'));
  assert.equal(page.pdfHtml.includes('UNRELATED_GLOBAL'), false);
  assert.match(page.note, /快照缺失.*默认排版/);
});

test('standalone PDF uses iOS paper and hides figures for printing while the host keeps its existing image policy', async () => {
  const standalone = await loadReady(loadPanel(savedEntry()));
  standalone.state.productKind = 'deepread';
  standalone.page.openPdfPreview();
  assert.equal(standalone.page.pdfPreview, true);
  assert.match(standalone.page.pdfHtml, /--dr-bg:#FBF7F1/);
  assert.match(standalone.page.pdfHtml, /--dr-accent:#C8402F/);
  assert.match(standalone.page.pdfHtml, /img,figure\{display:none!important;\}/);
  const host = await loadReady(loadPanel(savedEntry()));
  host.page.openPdfPreview();
  assert.match(host.page.pdfHtml, /--dr-bg:#FFFFFF/);
  assert.equal(host.page.pdfHtml.includes('img,figure{display:none!important;}'), false);
});
