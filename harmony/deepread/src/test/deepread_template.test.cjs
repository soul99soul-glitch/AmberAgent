const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

// Exercise the shipped renderer, including the shared Chat Markdown parser.
// Only the platform storage port is supplied by each test.
const etsRoot = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl');
const modelsFile = path.resolve(__dirname, '../main/ets/domain/models.ts');
const helpersFile = path.resolve(__dirname, '../main/ets/domain/helpers.ts');
const enumsFile = path.resolve(__dirname, '../main/ets/domain/enums.ts');
const markdownFile = path.resolve(__dirname, '../../../chat/src/main/ets/chat/markdown_blocks.ts');
const moduleCache = new Map();
const loadModule = (filename) => {
  filename = path.resolve(filename);
  if (moduleCache.has(filename)) return moduleCache.get(filename);
  const exports = {};
  moduleCache.set(filename, exports);
  const source = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const requireModule = (specifier) => {
    if (specifier === '@amber/deepread-domain') return Object.assign({},
      loadModule(modelsFile), loadModule(helpersFile), loadModule(enumsFile));
    if (specifier === '@amber/chat-domain') return loadModule(markdownFile);
    if (specifier.startsWith('.')) {
      const resolved = path.resolve(path.dirname(filename), specifier);
      for (const candidate of [resolved, resolved + '.ets', resolved + '.ts']) {
        if (fs.existsSync(candidate)) return loadModule(candidate);
      }
    }
    throw new Error(`Unexpected renderer dependency: ${specifier}`);
  };
  vm.runInNewContext(source, {
    exports, require: requireModule, console, URL, TextEncoder, TextDecoder,
    Uint8Array, Buffer, Error, JSON, Math, Number, String, Array, Object, Map, Set, Date,
  }, { filename });
  return exports;
};
const template = loadModule(path.join(etsRoot, 'DeepReadTemplate.ets'));
const makeOutput = loadModule(modelsFile).makeEmptyDeepReadOutput;
const imageConfidence = loadModule(enumsFile).IMAGE_CONFIDENCE;
const occurrences = (haystack, needle) => haystack.split(needle).length - 1;
const includesAll = (html, values) => {
  for (const value of values) assert.ok(html.includes(value), `Missing article field: ${value}`);
};

const fullOutput = () => {
  const o = makeOutput();
  o.generationComplete = true;
  o.generationPhase = 'COMPLETE';
  o.summary = 'SUMMARY_UNIQUE';
  o.keyEntities = Array.from({ length: 15 }, (_, i) => `ENTITY_UNIQUE_${i}`);
  o.heroImageUrl = 'https://images.example/hero.png';
  o.heroCaption = 'HERO_CAPTION_UNIQUE';
  o.timeline = Array.from({ length: 12 }, (_, i) => ({
    date: `DATE_UNIQUE_${i}`, event: `EVENT_UNIQUE_${i}`, isHighlight: i === 0,
    imageUrl: i === 0 ? 'https://images.example/timeline.png' : null,
    imageCaption: i === 0 ? 'TIMELINE_CAPTION_UNIQUE' : null,
  }));
  o.corePoints = Array.from({ length: 12 }, (_, i) => ({
    point: `POINT_UNIQUE_${i}`, supporting: `SUPPORT_UNIQUE_${i}`,
    imageUrl: i === 0 ? 'https://images.example/core.png' : null,
    imageCaption: i === 0 ? 'CORE_CAPTION_UNIQUE' : null,
  }));
  o.analysis.coreDispute = 'DISPUTE_UNIQUE';
  o.analysis.implications = 'IMPLICATION_UNIQUE';
  o.analysis.perspectives = Array.from({ length: 11 }, (_, i) => ({
    holder: `HOLDER_UNIQUE_${i}`, viewpoint: `VIEWPOINT_UNIQUE_${i}`,
  }));
  o.analysis.quotes = Array.from({ length: 10 }, (_, i) => ({
    text: `QUOTE_UNIQUE_${i}`, attribution: `ATTRIBUTION_UNIQUE_${i}`,
  }));
  o.extendedReading = Array.from({ length: 14 }, (_, i) => ({
    title: `READING_UNIQUE_${i}`, url: `https://reading.example/${i}`,
    source: `READING_SOURCE_UNIQUE_${i}`, publishedAt: `READING_DATE_UNIQUE_${i}`,
  }));
  o.references = [{
    title: 'REFERENCE_UNIQUE', url: 'https://reference.example/source',
    source: 'REFERENCE_SOURCE_UNIQUE', publishedAt: 'REFERENCE_DATE_UNIQUE',
  }];
  o.imageAssets = [o.heroImageUrl, o.timeline[0].imageUrl, o.corePoints[0].imageUrl,
    'https://images.example/gallery.png'].map((url, i) => ({
    url, caption: i === 3 ? 'GALLERY_CAPTION_UNIQUE' : null,
    confidence: 'inline', score: null, source: null, qualityHint: null,
    selectionReason: null, relatedEntities: [], relatedTimelineIndex: null,
  }));
  o.diagram = {
    type: 'causal_chain', title: 'DIAGRAM_TITLE_UNIQUE', reason: 'DIAGRAM_REASON_UNIQUE',
    caption: 'DIAGRAM_CAPTION_UNIQUE',
    nodes: [
      { id: 'a', label: 'NODE_A_UNIQUE', note: 'NODE_NOTE_UNIQUE', group: 'GROUP_UNIQUE' },
      { id: 'b', label: 'NODE_B_UNIQUE', note: null, group: null },
    ],
    edges: [{ from: 'a', to: 'b', label: 'EDGE_LABEL_UNIQUE' }],
  };
  return o;
};

test('editorial template renders every article field without the old display caps', () => {
  const o = fullOutput();
  const html = template.renderEditorialSlantHtml('TITLE_UNIQUE', o);
  const expected = ['TITLE_UNIQUE', o.summary, ...o.keyEntities, o.heroCaption,
    ...o.timeline.flatMap(e => [e.date, e.event]), o.timeline[0].imageCaption,
    ...o.corePoints.flatMap(p => [p.point, p.supporting]), o.corePoints[0].imageCaption,
    o.analysis.coreDispute, o.analysis.implications,
    ...o.analysis.perspectives.flatMap(p => [p.holder, p.viewpoint]),
    ...o.analysis.quotes.flatMap(q => [q.text, q.attribution]),
    ...o.extendedReading.flatMap(l => [l.title, l.url, l.source, l.publishedAt]),
    ...o.references.flatMap(l => [l.title, l.url, l.source, l.publishedAt]),
    'GALLERY_CAPTION_UNIQUE', o.diagram.title, o.diagram.reason, o.diagram.caption,
    ...o.diagram.nodes.flatMap(n => [n.label, n.note, n.group].filter(Boolean)),
    o.diagram.edges[0].label];
  includesAll(html, expected);
  for (const image of o.imageAssets) {
    assert.equal(occurrences(html, `src="${image.url}"`), 1, `Image repeated: ${image.url}`);
  }
});

const imageAsset = (url, caption, confidence) => ({
  url, caption, confidence, score: null, source: null, qualityHint: null,
  selectionReason: null, relatedEntities: [], relatedTimelineIndex: null,
});
const renderBothTemplates = o => [
  template.renderEditorialSlantHtml('Image gate', o),
  template.renderCustomTemplateHtml('<h1>{{title}}</h1>{{content}}', 'Image gate', o),
];

test('a rejected hero asset is hidden in Web templates just as it is in native reading', () => {
  const o = makeOutput();
  o.heroImageUrl = 'https://images.example/rejected-hero.png';
  o.heroCaption = 'REJECTED_HERO_CAPTION';
  o.heroImageConfidence = imageConfidence.HERO;
  o.imageAssets = [imageAsset(o.heroImageUrl, o.heroCaption, imageConfidence.REJECT)];
  assert.equal(loadModule(helpersFile).displayHeroImageUrl(o), null);
  for (const html of renderBothTemplates(o)) {
    assert.equal(html.includes(o.heroImageUrl), false);
    assert.equal(html.includes(o.heroCaption), false);
  }
});

test('Web hero uses the approved fallback asset and its caption without repeating it in the gallery', () => {
  const o = makeOutput();
  o.heroImageUrl = 'https://images.example/rejected-hero.png';
  o.heroCaption = 'WRONG_REJECTED_CAPTION';
  o.heroImageConfidence = imageConfidence.HERO;
  const fallback = imageAsset('https://images.example/approved-fallback.png', 'APPROVED_FALLBACK_CAPTION', imageConfidence.INLINE);
  const gallery = imageAsset('https://images.example/approved-gallery.png', 'APPROVED_GALLERY_CAPTION', imageConfidence.INLINE);
  o.imageAssets = [imageAsset(o.heroImageUrl, o.heroCaption, imageConfidence.REJECT), fallback, gallery, gallery];
  assert.equal(loadModule(helpersFile).displayHeroImageUrl(o), fallback.url);
  assert.equal(loadModule(helpersFile).displayHeroCaption(o), fallback.caption);
  for (const html of renderBothTemplates(o)) {
    assert.equal(html.includes(o.heroImageUrl), false);
    assert.equal(html.includes(o.heroCaption), false);
    includesAll(html, [fallback.caption, gallery.caption]);
    assert.equal(occurrences(html, `src="${fallback.url}"`), 1);
    assert.equal(occurrences(html, `src="${gallery.url}"`), 1);
  }
});

test('Web gallery excludes rejected and non-HTTP assets while retaining accepted images once', () => {
  const o = makeOutput();
  const hero = imageAsset('https://images.example/approved-hero.png', 'APPROVED_HERO', imageConfidence.HERO);
  const rejected = imageAsset('https://images.example/rejected-gallery.png', 'REJECTED_GALLERY', imageConfidence.REJECT);
  const data = imageAsset('data:image/png;base64,eHl6', 'NON_HTTP_GALLERY', imageConfidence.INLINE);
  const accepted = imageAsset('https://images.example/accepted-gallery.png', 'ACCEPTED_GALLERY', imageConfidence.INLINE);
  o.heroImageUrl = hero.url;
  o.heroImageConfidence = imageConfidence.HERO;
  o.imageAssets = [hero, rejected, data, accepted, accepted];
  for (const html of renderBothTemplates(o)) {
    assert.equal(html.includes(rejected.url), false);
    assert.equal(html.includes(rejected.caption), false);
    assert.equal(html.includes(data.url), false);
    assert.equal(html.includes(data.caption), false);
    assert.ok(html.includes(accepted.caption));
    assert.equal(occurrences(html, `src="${accepted.url}"`), 1);
    assert.equal(occurrences(html, `src="${hero.url}"`), 1);
  }
});

test('summary Markdown uses the shared AST for blocks and inline formatting', () => {
  const o = makeOutput();
  o.summary = '# AST_HEADING\n\n**AST_BOLD** and *AST_ITALIC* with `AST_CODE`.\n\n'
    + '> AST_QUOTE\n\n- AST_ITEM_A\n- AST_ITEM_B\n\n'
    + '7. AST_ORDERED_A\n8. AST_ORDERED_B\n\n'
    + '| AST_COL_A | AST_COL_B |\n| --- | --- |\n| AST_CELL_A | AST_CELL_B |\n\n'
    + '```text\n<AST_RAW_CODE>&\n```\n\n---\n\n'
    + '[AST_LINK](https://example.com/source)\n\n![AST_ALT](https://example.com/image.png)';
  const html = template.renderEditorialSlantHtml('Markdown', o);
  assert.match(html, /<h1\b[^>]*>AST_HEADING<\/h1>/);
  assert.match(html, /<strong>AST_BOLD<\/strong>/);
  assert.match(html, /<em>AST_ITALIC<\/em>/);
  assert.match(html, /<code>AST_CODE<\/code>/);
  assert.match(html, /<blockquote\b[^>]*>[\s\S]*AST_QUOTE[\s\S]*<\/blockquote>/);
  assert.match(html, /<ul\b[^>]*>[\s\S]*AST_ITEM_A[\s\S]*AST_ITEM_B[\s\S]*<\/ul>/);
  assert.match(html, /<ol\b[^>]*start="7"[^>]*>/);
  assert.match(html, /<table\b/);
  assert.match(html, /<th\b[^>]*>AST_COL_A<\/th>/);
  assert.match(html, /<td\b[^>]*>AST_CELL_B<\/td>/);
  assert.match(html, /<pre\b[^>]*>[\s\S]*&lt;AST_RAW_CODE&gt;&amp;/);
  assert.match(html, /<hr\b/);
  assert.match(html, /href="https:\/\/example\.com\/source"/);
  assert.match(html, /src="https:\/\/example\.com\/image\.png"/);
  assert.equal(html.includes('&amp;lt;AST_RAW_CODE'), false);
});

test('article strings cannot escape HTML or create executable link/image URLs', () => {
  const o = makeOutput();
  o.summary = '<img src=x onerror="boom"> & plain [bad](javascript:boom)';
  o.keyEntities = ['<ENTITY & "quoted">'];
  o.heroImageUrl = 'javascript:boom';
  o.extendedReading = [{ title: 'BAD_LINK', url: 'javascript:boom', source: null, publishedAt: null }];
  o.references = [{ title: 'GOOD_REFERENCE', url: 'https://example.com/?a=1&b=2', source: null, publishedAt: null }];
  const html = template.renderEditorialSlantHtml('<TITLE & "quoted">', o);
  assert.ok(html.includes('&lt;TITLE &amp; &quot;quoted&quot;&gt;'));
  assert.ok(html.includes('&lt;ENTITY &amp; &quot;quoted&quot;&gt;'));
  assert.equal(/<img\b[^>]*onerror\s*=/.test(html), false);
  assert.equal(/(?:src|href)="javascript:/i.test(html), false);
  assert.match(html, /href="https:\/\/example\.com\/\?a=1&amp;b=2"/);
});

test('old six-slot custom templates append chapters that had no original slot', () => {
  const o = fullOutput();
  const raw = '<article><h1>{{title}}</h1><aside>{{kicker}}</aside>{{summary}}{{timeline}}{{analysis}}{{reading}}</article>';
  const html = template.renderCustomTemplateHtml(raw, 'CUSTOM_TITLE', o);
  includesAll(html, ['CUSTOM_TITLE', o.corePoints[11].point, 'DIAGRAM_TITLE_UNIQUE',
    'REFERENCE_UNIQUE', 'HERO_CAPTION_UNIQUE', 'GALLERY_CAPTION_UNIQUE']);
  for (const marker of ['SUMMARY_UNIQUE', 'EVENT_UNIQUE_11', 'QUOTE_UNIQUE_9',
    'READING_UNIQUE_13', 'POINT_UNIQUE_11', 'REFERENCE_UNIQUE']) {
    assert.equal(occurrences(html, marker), 1, `Custom chapter repeated: ${marker}`);
  }
});

test('the content slot renders the whole article once and title text is not substituted again', () => {
  const html = template.renderCustomTemplateHtml(
    '<article><h1>{{title}}</h1>{{content}}</article>', 'TITLE {{analysis}}', fullOutput());
  for (const marker of ['SUMMARY_UNIQUE', 'EVENT_UNIQUE_11', 'POINT_UNIQUE_11',
    'DISPUTE_UNIQUE', 'REFERENCE_UNIQUE', 'DIAGRAM_TITLE_UNIQUE']) {
    assert.equal(occurrences(html, marker), 1, `Content slot repeated: ${marker}`);
  }
  assert.ok(html.includes('TITLE {{analysis}}'));
});

test('an unclosed full HTML shell still receives every unslotted article chapter', () => {
  const raw = '<html><head><style>h1{font-weight:600}</style></head><body><h1>{{title}}</h1>{{summary}}';
  assert.equal(template.validateTemplateHtml(raw), '');
  const html = template.renderCustomTemplateHtml(raw, 'Unclosed shell', fullOutput());
  includesAll(html, ['POINT_UNIQUE_11', 'REFERENCE_UNIQUE', 'DIAGRAM_TITLE_UNIQUE']);
});

test('placeholders in HTML comment examples do not consume visible article chapters', () => {
  for (const commentedSlot of ['content', 'summary']) {
    const raw = `<!-- available slot {{${commentedSlot}}} --><h1>{{title}}</h1>`;
    assert.equal(template.validateTemplateHtml(raw), '');
    const html = template.renderCustomTemplateHtml(raw, 'Comment example', fullOutput());
    const visible = html.replace(/<!--[\s\S]*?-->/g, '');
    includesAll(visible, ['SUMMARY_UNIQUE', 'POINT_UNIQUE_11', 'REFERENCE_UNIQUE', 'DIAGRAM_TITLE_UNIQUE']);
    assert.equal(occurrences(visible, 'SUMMARY_UNIQUE'), 1);
  }
});

test('partial articles show reference-only, diagram-only and core-point-only content', () => {
  for (const field of ['references', 'diagram', 'corePoints']) {
    const source = fullOutput();
    const partial = makeOutput();
    partial[field] = source[field];
    const marker = field === 'references' ? 'REFERENCE_UNIQUE'
      : field === 'diagram' ? 'DIAGRAM_TITLE_UNIQUE' : 'POINT_UNIQUE_11';
    for (const render of [
      () => template.renderEditorialSlantHtml('Partial', partial),
      () => template.renderCustomTemplateHtml('<h1>{{title}}</h1>{{summary}}', 'Partial', partial),
    ]) assert.ok(render().includes(marker), `Lost partial field: ${field}`);
  }
});

test('editorial and custom renders use current theme, font scale and serif preference', () => {
  const options = {
    fontScale: 1.2, fontSerif: true, background: '#111111', foreground: '#eeeeee',
    muted: '#999999', surface: '#222222', border: '#444444', accent: '#ff7700',
  };
  for (const html of [
    template.renderEditorialSlantHtml('Theme', fullOutput(), options),
    template.renderCustomTemplateHtml('<h1>{{title}}</h1>{{content}}', 'Theme', fullOutput(), options),
  ]) {
    includesAll(html, [options.background, options.foreground, options.muted,
      options.surface, options.border, options.accent]);
    assert.match(html, /--ds\s*:\s*1\.200/);
    assert.match(html, /font-family\s*:\s*var\(--dr-font\)/);
    assert.match(html, /--dr-font\s*:\s*Georgia,'Noto Serif SC',serif/);
  }
});

test('display options cannot inject CSS/HTML and non-finite scale falls back to a safe number', () => {
  const options = { fontScale: NaN, background: '#fff;}INJECTED_THEME<style>',
    foreground: 'url(https://injected.example/a)' };
  for (const html of [
    template.renderEditorialSlantHtml('Safe theme', makeOutput(), options),
    template.renderCustomTemplateHtml('{{content}}', 'Safe theme', makeOutput(), options),
  ]) {
    assert.equal(html.includes('INJECTED_THEME'), false);
    assert.equal(html.includes('injected.example'), false);
    assert.match(html, /--ds\s*:\s*1\.000/);
  }
});

const templateAtBytes = (bytes) => {
  const prefix = '<article>{{content}}';
  const suffix = '</article>';
  const remaining = bytes - Buffer.byteLength(prefix + suffix, 'utf8');
  return prefix + '中'.repeat(Math.floor(remaining / 3)) + 'x'.repeat(remaining % 3) + suffix;
};

test('template limit counts UTF-8 bytes, accepts the limit and rejects one byte more', () => {
  const max = 96 * 1024;
  const exact = templateAtBytes(max);
  const over = templateAtBytes(max + 1);
  assert.equal(Buffer.byteLength(exact, 'utf8'), max);
  assert.ok(exact.length < max);
  assert.equal(template.validateTemplateHtml(exact), '');
  assert.notEqual(template.validateTemplateHtml(over), '');
});

test('manual, generated and imported template contents share strict active-content validation', () => {
  for (const unsafe of [
    '<article>{{content}}<script>boom()</script></article>',
    '<article onclick="boom()">{{content}}</article>',
    '<article><img src="https://example.com/a" onerror=boom()>{{content}}</article>',
    '<style>article{background:url(https://evil.example/a)}</style>{{content}}',
    '<style>@import "https://evil.example/a";</style>{{content}}',
    '<form action="https://evil.example">{{content}}</form>',
    '<a href="javascript:boom">{{content}}</a>',
  ]) assert.notEqual(template.validateTemplateHtml(unsafe), '', `Unsafe template accepted: ${unsafe}`);
  assert.equal(template.validateTemplateHtml(
    '<style>article{color:#222;padding:12px}</style><article>{{content}}'
    + '<img src="https://images.example/allowed.png" alt="cover"></article>'), '');
});

test('template documents disable scripts and external active fetches while allowing article images', () => {
  for (const html of [
    template.renderEditorialSlantHtml('CSP', fullOutput()),
    template.renderCustomTemplateHtml('<article>{{content}}</article>', 'CSP', fullOutput()),
  ]) {
    assert.match(html, /http-equiv="Content-Security-Policy"/i);
    assert.match(html, /script-src[^;]*(?:'none'|&#39;none&#39;)/);
    assert.match(html, /connect-src[^;]*(?:'none'|&#39;none&#39;)/);
    assert.match(html, /img-src[^;]*https:/);
    assert.ok(html.includes('src="https://images.example/hero.png"'));
  }
});

test('corrupt template storage and real storage failures propagate instead of an empty list', async () => {
  await assert.rejects(template.loadCustomTemplates({ get: async () => '[' }));
  await assert.rejects(template.loadCustomTemplates({ get: async () => { throw new Error('READ_FAILURE'); } }), /READ_FAILURE/);
  await assert.rejects(template.saveCustomTemplates({ set: async () => { throw new Error('WRITE_FAILURE'); } }, [{
    id: 'custom_1', name: 'Valid', html: '<article>{{content}}</article>', createdAt: 123,
  }]), /WRITE_FAILURE/);
});

test('saving rejects unsafe template content before storage mutation and preserves valid creation time', async () => {
  const writes = [];
  const storage = { set: async (key, value) => { writes.push({ key, value }); } };
  await assert.rejects(template.saveCustomTemplates(storage, [{
    id: 'custom_bad', name: 'Unsafe', html: '{{content}}<script>boom()</script>', createdAt: 11,
  }]));
  assert.equal(writes.length, 0);
  await template.saveCustomTemplates(storage, [{
    id: 'custom_ok', name: 'Valid', html: '<article>{{content}}</article>', createdAt: 123,
  }]);
  const loaded = await template.loadCustomTemplates({ get: async () => writes[0].value });
  assert.equal(loaded.length, 1);
  assert.equal(loaded[0].createdAt, 123);
  assert.equal(loaded[0].html, '<article>{{content}}</article>');
});

// ArkUI builders do not run in Node. Execute the actual page's persistence
// methods with controlled page state and the real template validation/storage.
const loadWorkbenchMethods = () => {
  const filename = path.resolve(__dirname, '../../../entry/src/main/ets/pages/DeepReadTemplateWorkbenchPage.ets');
  const source = fs.readFileSync(filename, 'utf8');
  const start = source.indexOf('  private startNew():');
  const end = source.indexOf('\n  private async importHtmlFile(', start);
  assert.ok(start >= 0 && end > start, 'Workbench persistence methods were not found');
  const exports = {};
  const fixture = `class WorkbenchFixture {
    templates = []; editingId = ''; editName = ''; editHtml = ''; note = '';
    saving = false; storage = null; loaded = true; alive = true;
    lifecycleId = 0; editorId = 0; requestedTemplateId = '';
    ${source.slice(start, end)}
  } exports.WorkbenchFixture = WorkbenchFixture;`;
  vm.runInNewContext(ts.transpileModule(fixture, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, { exports, Error, String, Date, Promise,
    validateTemplateHtml: template.validateTemplateHtml,
    saveCustomTemplates: template.saveCustomTemplates,
    starterTemplateHtml: template.starterTemplateHtml,
  }, { filename });
  const page = new exports.WorkbenchFixture();
  page.templates = [
    { id: 'custom_existing', name: 'Existing', html: '<article>{{content}}</article>', createdAt: 123 },
    { id: 'custom_other', name: 'Other', html: '<article>{{summary}}</article>', createdAt: 456 },
  ];
  page.editingId = 'custom_existing';
  page.editName = ' Updated ';
  page.editHtml = '<article>{{content}}</article>';
  return page;
};

test('workbench reload uses the same percent scale and font mode as the article page', async () => {
  const filename = path.resolve(__dirname, '../../../entry/src/main/ets/pages/DeepReadTemplateWorkbenchPage.ets');
  const source = fs.readFileSync(filename, 'utf8');
  const start = source.indexOf('  private reload():');
  const end = source.indexOf('\n  private startEdit(', start);
  assert.ok(start >= 0 && end > start, 'Workbench reload method was not found');
  for (const preference of [
    { scale: '100', mode: 'default', expectedScale: 1, expectedSerif: false },
    { scale: '120', mode: 'serif', expectedScale: 1.2, expectedSerif: true },
    { scale: '70', mode: 'serif', expectedScale: 0.7, expectedSerif: true },
    { scale: '180', mode: 'default', expectedScale: 1.8, expectedSerif: false },
  ]) {
    const exports = {};
    const fixture = `class WorkbenchFixture {
      templates = []; storage = null; note = ''; fontScale = 1; fontSerif = true;
      alive = true; lifecycleId = 0; editorId = 0; requestedTemplateId = '';
      ${source.slice(start, end)}
    } exports.WorkbenchFixture = WorkbenchFixture;`;
    vm.runInNewContext(ts.transpileModule(fixture, {
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
    }).outputText, { exports, Error, String, Number, Promise, parseFloat,
      loadCustomTemplates: template.loadCustomTemplates,
      getChatKvStore: () => ({ get: async key => key === 'deepread_font_scale' ? preference.scale : preference.mode }),
    }, { filename });
    const page = new exports.WorkbenchFixture();
    page.storage = { get: async () => '[]' };
    page.reload();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(page.fontScale, preference.expectedScale);
    assert.equal(page.fontSerif, preference.expectedSerif);
    const html = template.renderCustomTemplateHtml('{{content}}', 'Preview', fullOutput(), {
      fontScale: page.fontScale, fontSerif: page.fontSerif,
    });
    assert.ok(html.includes(`--ds:${preference.expectedScale.toFixed(3)}`));
    assert.ok(html.includes(preference.expectedSerif
      ? "--dr-font:Georgia,'Noto Serif SC',serif"
      : "--dr-font:'HarmonyOS Sans SC',sans-serif"));
  }
});

test('Workbench preview sends raw rendered HTML with Chinese, CSS colors and percent text to loadData', () => {
  const filename = path.resolve(__dirname, '../../../entry/src/main/ets/pages/DeepReadTemplateWorkbenchPage.ets');
  const source = fs.readFileSync(filename, 'utf8');
  const start = source.indexOf('  private loadPreview():');
  const end = source.indexOf('\n  private openPreview(', start);
  assert.ok(start >= 0 && end > start, 'Workbench preview method was not found');
  const title = '中文预览标题 #100%';
  const output = makeOutput();
  output.summary = '中文正文进度100%，符号#保持原样。';
  const raw = '<style>#preview{color:#123abc;width:100%}</style><h1 id="preview">{{title}}</h1>{{content}}';
  const options = {
    fontScale: 1, fontSerif: false, background: '#FFFFFF', foreground: '#1B1A17',
    muted: '#57544C', surface: '#FFFFFF', border: '#D6D1C4', accent: '#B8623A',
  };
  const expectedHtml = template.renderCustomTemplateHtml(raw, title, output, options);
  const calls = [];
  const exports = {};
  const fixture = `class WorkbenchPreviewFixture {
    ${source.slice(start, end)}
  } exports.WorkbenchPreviewFixture = WorkbenchPreviewFixture;`;
  vm.runInNewContext(ts.transpileModule(fixture, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, { exports, Error, String,
    SAMPLE_TITLE: title, BG: options.background, INK: options.foreground,
    INK3: options.muted, SURFACE: options.surface, LINE: options.border,
    buildSampleTemplateOutput: () => output,
    renderCustomTemplateHtml: template.renderCustomTemplateHtml,
  }, { filename });
  const page = new exports.WorkbenchPreviewFixture();
  Object.assign(page, { alive: true, webAttached: true, webInitialized: true,
    previewId: 3, themeEpoch: 2, previewOpen: true, editHtml: raw, note: '',
    fontScale: options.fontScale, fontSerif: options.fontSerif, accentColorStore: options.accent,
    webController: { loadData: (...args) => calls.push(args) },
  });
  page.loadPreview();
  assert.equal(calls.length, 1);
  assert.equal(calls[0][0], expectedHtml);
  assert.ok(calls[0][0].includes(title));
  assert.ok(calls[0][0].includes('#123abc'));
  assert.ok(calls[0][0].includes('进度100%'));
  assert.deepEqual(calls[0].slice(1), ['text/html', 'UTF-8', ' ', ' ']);
  assert.equal(page.note, '');
});

test('Workbench current main-document errors close the preview with an error, while obsolete and subresource errors do nothing', () => {
  const filename = path.resolve(__dirname, '../../../entry/src/main/ets/pages/DeepReadTemplateWorkbenchPage.ets');
  const source = fs.readFileSync(filename, 'utf8');
  const start = source.search(/^  private onPreviewError\(/m);
  assert.ok(start >= 0, 'Workbench onPreviewError method was not found');
  const open = source.indexOf('{', start);
  let depth = 1;
  let end = open + 1;
  for (; depth && end < source.length; end++) {
    if (source[end] === '{') depth++;
    if (source[end] === '}') depth--;
  }
  const exports = {};
  vm.runInNewContext(ts.transpileModule(`class PreviewErrorFixture {
    ${source.slice(start, end)}
  } exports.PreviewErrorFixture = PreviewErrorFixture;`, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, { exports, Error, String }, { filename });
  const fresh = () => Object.assign(new exports.PreviewErrorFixture(), {
    alive: true, webAttached: true, webInitialized: true, previewId: 3, themeEpoch: 2,
    previewOpen: true, note: 'KEEP_NOTICE',
  });
  const current = fresh();
  current.onPreviewError(true);
  assert.equal(current.previewOpen, false);
  assert.equal(current.note, '预览失败:正文加载错误，请重试。');
  for (const state of [
    { alive: false }, { webAttached: false }, { previewOpen: false }, { mainFrame: false },
  ]) {
    const page = Object.assign(fresh(), state);
    const before = page.previewOpen;
    page.onPreviewError(state.mainFrame !== false);
    assert.equal(page.previewOpen, before);
    assert.equal(page.note, 'KEEP_NOTICE');
  }
});

test('Workbench Web error callbacks invoke the preview error consumer', () => {
  const filename = path.resolve(__dirname, '../../../entry/src/main/ets/pages/DeepReadTemplateWorkbenchPage.ets');
  const source = fs.readFileSync(filename, 'utf8');
  assert.match(source, /\.onErrorReceive\([\s\S]*?this\.onPreviewError\s*\(/);
});

test('Workbench waits for each current initial blank document once, and old preview/theme callbacks cannot close a new preview', () => {
  const filename = path.resolve(__dirname, '../../../entry/src/main/ets/pages/DeepReadTemplateWorkbenchPage.ets');
  const source = fs.readFileSync(filename, 'utf8');
  const method = name => {
    const start = source.search(new RegExp('^  private (?:async )?' + name + '\\(', 'm'));
    assert.ok(start >= 0, `Workbench ${name} method was not found`);
    const open = source.indexOf('{', start);
    let depth = 1;
    let end = open + 1;
    for (; depth && end < source.length; end++) {
      if (source[end] === '{') depth++;
      if (source[end] === '}') depth--;
    }
    return source.slice(start, end);
  };
  const names = ['attachPreviewWeb', 'onPreviewPageEnd', 'onPreviewError', 'openPreview', 'loadPreview'];
  const exports = {};
  const calls = [];
  const output = makeOutput();
  output.summary = '中文正文100% #原样';
  vm.runInNewContext(ts.transpileModule(`class PreviewCallbacks {
    ${names.map(method).join('\n')}
  } exports.PreviewCallbacks = PreviewCallbacks;`, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, { exports, Error, String, SAMPLE_TITLE: '中文标题 #100%',
    BG: '#FFFFFF', INK: '#1B1A17', INK3: '#57544C', SURFACE: '#FFFFFF', LINE: '#D6D1C4',
    validateTemplateHtml: template.validateTemplateHtml,
    buildSampleTemplateOutput: () => output, renderCustomTemplateHtml: template.renderCustomTemplateHtml,
  }, { filename });
  const page = new exports.PreviewCallbacks();
  Object.assign(page, { alive: true, webAttached: false, webInitialized: false,
    previewOpen: false, previewId: 0, themeEpoch: 2, note: '',
    editHtml: '<style>#preview{color:#123abc;width:100%}</style><h1>{{title}}</h1>{{content}}',
    fontScale: 1, fontSerif: false, accentColorStore: '#B8623A',
    webController: { loadData: (...args) => calls.push(args) },
  });
  page.openPreview();
  const firstId = page.previewId;
  const firstEpoch = page.themeEpoch;
  assert.equal(firstId, 1);
  assert.equal(page.previewOpen, true);
  assert.equal(calls.length, 0);
  page.attachPreviewWeb(firstId, firstEpoch);
  assert.equal(page.webAttached, true);
  assert.equal(page.webInitialized, false);
  page.loadPreview();
  page.onPreviewPageEnd('https://example.com/other', firstId, firstEpoch);
  assert.equal(calls.length, 0);
  page.onPreviewPageEnd('about:blank', firstId, firstEpoch);
  assert.equal(page.webInitialized, true);
  assert.equal(calls.length, 1);
  assert.ok(calls[0][0].startsWith('<!DOCTYPE html>'));
  assert.ok(calls[0][0].includes('中文正文100%'));
  assert.ok(calls[0][0].includes('#123abc'));
  page.onPreviewPageEnd('about:blank', firstId, firstEpoch);
  page.onPreviewPageEnd('https://example.com/article', firstId, firstEpoch);
  assert.equal(calls.length, 1);
  page.fontScale = 1.2;
  page.loadPreview();
  assert.equal(calls.length, 2);
  assert.ok(calls[1][0].includes('--ds:1.200'));

  page.themeEpoch++;
  page.attachPreviewWeb(firstId, page.themeEpoch);
  assert.equal(page.webInitialized, false);
  page.onPreviewPageEnd('about:blank', firstId, firstEpoch);
  page.onPreviewError(true, firstId, firstEpoch);
  assert.equal(page.previewOpen, true);
  assert.equal(page.note, '');
  assert.equal(calls.length, 2);
  page.onPreviewPageEnd('about:blank', firstId, page.themeEpoch);
  assert.equal(calls.length, 3);

  page.previewOpen = false;
  page.webAttached = false;
  page.webInitialized = false;
  page.openPreview();
  const secondId = page.previewId;
  assert.equal(secondId, firstId + 1);
  page.attachPreviewWeb(secondId, page.themeEpoch);
  page.onPreviewPageEnd('about:blank', firstId, page.themeEpoch);
  page.onPreviewError(true, firstId, page.themeEpoch);
  assert.equal(page.previewOpen, true);
  assert.equal(page.webInitialized, false);
  assert.equal(page.note, '');
  assert.equal(calls.length, 3);
  page.onPreviewPageEnd('about:blank', secondId, page.themeEpoch);
  assert.equal(calls.length, 4);
  page.onPreviewPageEnd('about:blank', secondId, page.themeEpoch);
  assert.equal(calls.length, 4);
  page.alive = false;
  page.webAttached = false;
  page.attachPreviewWeb(secondId, page.themeEpoch);
  page.onPreviewPageEnd('about:blank', secondId, page.themeEpoch);
  assert.equal(page.webAttached, false);
  assert.equal(calls.length, 4);
});

test('workbench saving an edited template preserves its creation time and other templates', async () => {
  const page = loadWorkbenchMethods();
  const writes = [];
  page.storage = { set: async (key, value) => { writes.push({ key, value }); } };
  await page.save();
  assert.equal(writes.length, 2);
  assert.equal(writes[0].key, 'deepread_custom_templates');
  assert.equal(writes[1].key, 'deepread_template_id');
  assert.equal(writes[1].value, 'custom_existing');
  const persisted = JSON.parse(writes[0].value);
  const edited = persisted.find(t => t.id === 'custom_existing');
  assert.equal(edited.createdAt, 123);
  assert.equal(edited.name, 'Updated');
  assert.equal(persisted.find(t => t.id === 'custom_other').createdAt, 456);
  assert.equal(page.templates.find(t => t.id === 'custom_existing').createdAt, 123);
  assert.equal(page.editingId, 'custom_existing');
  assert.ok(page.note.includes('已保存'));
  assert.equal(page.saving, false);
});

test('workbench save failure keeps the list and editor identity and exposes the storage error', async () => {
  const page = loadWorkbenchMethods();
  const before = page.templates;
  page.storage = { set: async () => { throw new Error('WORKBENCH_WRITE_FAILURE'); } };
  await page.save();
  assert.equal(page.templates, before);
  assert.equal(page.editingId, 'custom_existing');
  assert.equal(page.editHtml, '<article>{{content}}</article>');
  assert.ok(page.note.includes('保存失败'));
  assert.ok(page.note.includes('WORKBENCH_WRITE_FAILURE'));
  assert.equal(page.saving, false);
});

test('workbench delete failure retains the list, editor and selected template', async () => {
  const page = loadWorkbenchMethods();
  const before = page.templates;
  let selected = 'custom_existing';
  const calls = [];
  page.storage = {
    get: async key => { calls.push(['get', key]); return selected; },
    set: async (key, value) => {
      calls.push(['set', key]);
      if (key === 'deepread_custom_templates') throw new Error('WORKBENCH_DELETE_FAILURE');
      selected = value;
    },
  };
  await page.remove('custom_existing');
  assert.equal(page.templates, before);
  assert.equal(page.editingId, 'custom_existing');
  assert.equal(selected, 'custom_existing');
  assert.deepEqual(calls, [['set', 'deepread_custom_templates']]);
  assert.ok(page.note.includes('删除失败'));
  assert.ok(page.note.includes('WORKBENCH_DELETE_FAILURE'));
  assert.equal(page.saving, false);
});

test('workbench unsafe save leaves editor content visible and does not attempt a write', async () => {
  const page = loadWorkbenchMethods();
  const before = page.templates;
  let writeCount = 0;
  page.storage = { set: async () => { writeCount++; } };
  page.editHtml = '<article>{{content}}<script>boom()</script></article>';
  await page.save();
  assert.equal(writeCount, 0);
  assert.equal(page.templates, before);
  assert.equal(page.editingId, 'custom_existing');
  assert.ok(page.editHtml.includes('<script>'));
  assert.ok(page.note.includes('被禁元素'));
  assert.equal(page.saving, false);
});
