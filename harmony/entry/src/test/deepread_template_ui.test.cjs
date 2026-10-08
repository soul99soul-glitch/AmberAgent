const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
const root = path.resolve(__dirname, '../main/ets');
const modules = new Map();
function load(file) {
  if (modules.has(file)) return modules.get(file);
  const exports = {}; modules.set(file, exports);
  const code = ts.transpileModule(fs.readFileSync(file, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const dep = name => {
    if (name === '@amber/deepread-domain') return Object.assign({},
      load(path.resolve(__dirname, '../../../deepread/src/main/ets/domain/models.ts')),
      load(path.resolve(__dirname, '../../../deepread/src/main/ets/domain/helpers.ts')),
      load(path.resolve(__dirname, '../../../deepread/src/main/ets/domain/enums.ts')),
      load(path.resolve(__dirname, '../../../deepread/src/main/ets/domain/synthesis_templates.ts')));
    if (name === '@amber/chat-domain') return load(path.resolve(__dirname, '../../../chat/src/main/ets/chat/markdown_blocks.ts'));
    const resolved = path.resolve(path.dirname(file), name);
    for (const candidate of [resolved, resolved + '.ets', resolved + '.ts']) if (fs.existsSync(candidate)) return load(candidate);
    throw Error('Unexpected dependency ' + name);
  };
  new Function('exports', 'require', code)(exports, dep);
  return exports;
}
const template = load(path.join(root, 'platform_impl/DeepReadTemplate.ets'));
const editorSource = fs.readFileSync(path.join(root, 'components/deepread/DeepReadTemplateWorkbench.ets'), 'utf8');
const editorPortCode = ts.transpileModule(editorSource.slice(editorSource.indexOf('export class DeepReadTemplateEditorPort'),
  editorSource.indexOf('\n@Component')).replace('export class', 'class') + '\nreturn DeepReadTemplateEditorPort;',
  { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
const EditorPort = new Function(editorPortCode)();
function method(source, name) {
  const found = new RegExp('^  (?:private )?(?:async )?' + name + '\\(', 'm').exec(source);
  assert.ok(found, 'Missing actual method ' + name);
  let depth = 1, end = source.indexOf('{', found.index) + 1;
  for (; depth && end < source.length; end++) {
    if (source[end] === '{') depth++;
    if (source[end] === '}') depth--;
  }
  return source.slice(found.index, end);
}
function page(file, names, env = {}) {
  const source = fs.readFileSync(path.join(root, file.startsWith('components/') ? '' : 'pages', file), 'utf8');
  const code = ts.transpileModule('class Page {\n' + names.map(name => method(source, name)).join('\n') + '\n}\nreturn new Page();',
    { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  const deps = { ...template, BG: '#ffffff', INK: '#222222', INK3: '#555555', SURFACE: '#eeeeee', LINE: '#aaaaaa',
    getProductKind: () => 'agent',
    Curve: { EaseOut: 'ease-out' }, curves: { springCurve: () => 'spring' }, DeepReadHaptics: { selection: () => {} },
    DeepReadTemplateEditorPort: EditorPort,
    D: { paper: '#FBF7F1', ink: '#2A2320', muted: '#6E6254', card: '#FFFDF9', rule: '#E4D9CB', accent: '#C8402F' },
    SAMPLE_TITLE: '模板预览', ...env };
  return new Function(...Object.keys(deps), code)(...Object.values(deps));
}
function store() {
  const values = new Map([['deepread_custom_templates', '[]']]);
  const writes = [];
  return { values, writes, get: async (key, fallback) => values.get(key) ?? fallback,
    set: async (key, value) => { writes.push(key); values.set(key, value); } };
}
function workbench(storage = store(), extras = {}) {
  const methods = ['startNew', 'startEdit', 'save', 'confirmRemove', 'remove', 'reload', 'openPreview', 'loadPreview',
    'aboutToDisappear', 'importHtmlFile', 'generate', 'templateRowKey', 'validateDraft', 'changeName', 'changeHtml',
    'onBackPress', 'hasUnsavedChanges', 'requestLeave', 'confirmLeave', 'dismissEditor', 'aboutToAppear', 'changeDescription'];
  const editor = page('components/deepread/DeepReadTemplateWorkbench.ets', methods, {
    getChatKvStore: () => ({ get: async () => null }), getAppContainer: () => ({ storage }), ...extras,
  });
  Object.assign(editor, { storage, templates: [], loaded: true, alive: true, lifecycleId: 1, editorId: 0,
    saving: false, generating: false, generationController: null, requestedTemplateId: '',
    note: '', genError: '', editName: '', editDescription: '', editHtml: '', previewOpen: false, previewId: 0,
    webAttached: true, webInitialized: true, fontScale: 1, fontSerif: false, accentColorStore: '#b8623a',
    embedded: false, presentGeneratedDraft: false, editorPort: new EditorPort(), templateId: '', initialMode: 'editor', initialPreview: false,
    draftName: '', draftHtml: '', previewFontMode: '', previewFontScale: 0 });
  return editor;
}
const tick = () => new Promise(resolve => setImmediate(resolve));
function templateSheet(storage = store(), extras = {}) {
  const settings = page('DeepReadTemplateSettingsPage.ets', ['edit', 'preview', 'generateTemplate', 'openWorkbench',
    'closeSheet', 'sheetSaved', 'sheetDisappeared', 'workbenchSheetOptions', 'onBackPress',
    'aboutToDisappear', 'clearSelectionMotion', 'reload'], { getProductKind: () => 'deepread', ...extras });
  Object.assign(settings, { storage, alive: true, loadId: 0, templates: [], selectedId: 'none', error: '',
    sheetSession: 0, sheetOpen: false,
    sheetCloseApproved: false, sheetPort: new EditorPort(), previewFontMode: '', previewFontScale: 0,
    lifecycleId: 1, motionId: 0 });
  return settings;
}
function generatorRoute(extras = {}) {
  const route = page('DeepReadTemplateWorkbenchPage.ets', ['aboutToAppear', 'aboutToDisappear', 'generatedDraft',
    'closeSheet', 'sheetDisappeared', 'editorSheetOptions', 'onBackPress'], {
    router: { getParams: () => ({ mode: 'generate' }) }, ...extras,
  });
  Object.assign(route, { lifecycleId: 0, sheetSession: 0, sheetOpen: false, draftName: '', draftHtml: '',
    sheetCloseApproved: false, editorPort: new EditorPort(), sheetPort: new EditorPort() });
  route.aboutToAppear(); return route;
}

test('actual new → rendered preview → save → durable reload → select uses one existing template store', async () => {
  const storage = store(); const editor = workbench(storage);
  editor.startNew();
  assert.equal(editor.editName, '自定义模板');
  assert.equal(template.validateTemplateHtml(editor.editHtml), '');
  editor.openPreview();
  assert.equal(editor.previewOpen, true);
  const previews = [];
  editor.webAttached = true; editor.webInitialized = true;
  editor.webController = { loadData: (...args) => previews.push(args) };
  editor.loadPreview();
  assert.equal(previews.length, 1);
  assert.match(previews[0][0], /模板预览/);
  assert.ok(!previews[0][0].includes('{{content}}'));
  await editor.save();
  const saved = await template.loadCustomTemplates(storage);
  assert.equal(saved.length, 1);
  assert.equal(saved[0].html, editor.editHtml);
  assert.equal(storage.values.get('deepread_template_id'), saved[0].id);
  assert.ok(saved[0].createdAt > 0);
  assert.equal(saved[0].updatedAt, saved[0].createdAt);
  assert.match(editor.note, /已保存并用于新文章/);
  assert.deepEqual(storage.writes, ['deepread_custom_templates', 'deepread_template_id']);
});

test('editing retains creation time and updates edit time in persisted storage', async () => {
  const storage = store(); const editor = workbench(storage);
  const original = { id: 'custom_old', name: '原模板', html: '<article>{{content}}</article>', createdAt: 12, updatedAt: 13 };
  editor.templates = [original]; editor.startEdit(original); editor.editName = '改名';
  await editor.save();
  const [saved] = await template.loadCustomTemplates(storage);
  assert.equal(saved.id, original.id); assert.equal(saved.createdAt, 12);
  assert.ok(saved.updatedAt > 13); assert.equal(saved.name, '改名');
});

test('same-id save remounts the workbench row and editing that current item preserves the updated HTML', async () => {
  const storage = store(); const editor = workbench(storage);
  const original = { id: 'custom_old', name: '原模板', html: '<article>{{summary}}</article>', createdAt: 1, updatedAt: 2 };
  editor.templates = [original]; editor.startEdit(original);
  const oldKey = editor.templateRowKey(original);
  editor.editName = '更新后'; editor.editHtml = '<article>{{analysis}}</article>';
  await editor.save();
  const updated = editor.templates[0];
  assert.equal(updated.id, original.id);
  assert.notEqual(editor.templateRowKey(updated), oldKey);
  editor.startNew(); editor.startEdit(updated);
  assert.equal(editor.editName, '更新后');
  assert.equal(editor.editHtml, '<article>{{analysis}}</article>');
  await editor.save();
  assert.equal((await template.loadCustomTemplates(storage))[0].html, '<article>{{analysis}}</article>');
  const source = fs.readFileSync(path.join(root, 'components/deepread/DeepReadTemplateWorkbench.ets'), 'utf8');
  assert.match(source, /\}, \(t: CustomTemplateDef\): string => this\.templateRowKey\(t\)\)/);
});

test('delete confirmation captures the displayed template and clears selection only after successful removal', async () => {
  const dialogs = []; const storage = store();
  const editor = workbench(storage, { AlertDialog: { show: dialog => dialogs.push(dialog) } });
  editor.startNew(); await editor.save(); const savedId = editor.editingId;
  editor.confirmRemove(savedId);
  assert.equal(dialogs.length, 1); assert.equal((await template.loadCustomTemplates(storage)).length, 1);
  let removal; const remove = editor.remove.bind(editor);
  editor.remove = id => { removal = remove(id); return removal; };
  dialogs[0].primaryButton.action(); await removal;
  assert.equal((await template.loadCustomTemplates(storage)).length, 0);
  assert.equal(storage.values.get('deepread_template_id'), 'none');
  assert.equal(editor.editHtml, template.starterTemplateHtml());
});

test('load failure and invalid HTML cannot overwrite existing template storage', async () => {
  const storage = store(); const editor = workbench(storage);
  editor.startNew(); editor.loaded = false;
  await editor.save(); assert.equal(storage.writes.length, 0);
  editor.loaded = true; editor.editHtml = '<script>alert(1)</script>{{content}}';
  await editor.save(); assert.equal(storage.writes.length, 0);
  assert.match(editor.note, /禁/);
});

test('selection failure reports that HTML is saved rather than pretending the entire save failed', async () => {
  const storage = store(); const set = storage.set;
  storage.set = async (key, value) => { if (key === 'deepread_template_id') throw Error('选用落盘失败'); await set(key, value); };
  const editor = workbench(storage); editor.startNew(); await editor.save();
  assert.equal((await template.loadCustomTemplates(storage)).length, 1);
  assert.match(editor.note, /模板已保存，但选用失败/);
  assert.equal(editor.saving, false);
});

test('leaving during persistence does not update a destroyed page, while the requested save still completes', async () => {
  let finish; const storage = store(); const set = storage.set;
  const gate = new Promise(resolve => { finish = resolve; });
  storage.set = async (key, value) => { await gate; await set(key, value); };
  const editor = workbench(storage); editor.startNew(); editor.note = '离页前';
  const saving = editor.save(); editor.aboutToDisappear(); finish(); await saving;
  assert.equal(editor.note, '离页前'); assert.equal(editor.editingId, '');
  assert.equal((await template.loadCustomTemplates(storage)).length, 1);
});

test('late file picker results cannot replace a newly selected editor draft', async () => {
  let choose; let reads = 0;
  const editor = workbench(store(), {
    AppStorage: { get: () => ({}) }, picker: { DocumentSelectOptions: class {}, DocumentViewPicker: class {
      select() { return new Promise(resolve => { choose = resolve; }); }
    } }, fileIo: { statSync: () => ({ size: 1 }), readTextSync: () => { reads++; return '<article>{{content}}</article>'; } },
  });
  editor.startNew(); const pending = editor.importHtmlFile();
  const next = { id: 'other', name: '另一模板', html: '<article>{{summary}}</article>', createdAt: 2 };
  editor.startEdit(next); choose(['file.html']); await pending;
  assert.equal(editor.editHtml, next.html); assert.equal(reads, 0);
});

test('actual template list selection persists and returning from editor reloads the saved item', async () => {
  const storage = store(); const editor = workbench(storage); editor.startNew(); await editor.save();
  const routes = [];
  const settings = page('DeepReadTemplateSettingsPage.ets', ['reload', 'select', 'applySelection', 'edit'],
    { router: { pushUrl: arg => routes.push(arg) } });
  Object.assign(settings, { storage, alive: true, loadId: 0, saving: false, error: '', templates: [], selectedId: 'none' });
  await settings.reload(); assert.equal(settings.templates.length, 1);
  settings.edit(settings.templates[0].id);
  assert.equal(routes[0].params.templateId, settings.templates[0].id);
  await settings.select('editorial_slant');
  assert.equal(storage.values.get('deepread_template_id'), 'editorial_slant');
  assert.equal(settings.selectedId, 'editorial_slant');
});

test('late reload after leaving cannot install templates or reading font state', async () => {
  let finish; const gate = new Promise(resolve => { finish = resolve; });
  const storage = { get: async () => { await gate; return '[]'; } };
  const editor = workbench(storage, { getChatKvStore: () => ({ get: async () => { await gate; return '180'; } }) });
  editor.reload(); editor.aboutToDisappear(); finish(); await tick();
  assert.equal(editor.fontScale, 1); assert.equal(editor.templates.length, 0);
});

test('returning from same-id template edits gives retained V1 rows a new rendering key', async () => {
  const storage = store();
  const original = { id: 'custom_1', name: '原名称', html: '<article>{{content}}</article>', createdAt: 1, updatedAt: 2 };
  await template.saveCustomTemplates(storage, [original]);
  const settings = page('DeepReadTemplateSettingsPage.ets', ['reload', 'customRowKey']);
  Object.assign(settings, { storage, alive: true, loadId: 0, saving: false, error: '', templates: [], selectedId: 'none' });
  await settings.reload();
  const oldKey = settings.customRowKey(settings.templates[0]);
  await template.saveCustomTemplates(storage, [{ ...original, name: '改名后', updatedAt: 3 }]);
  await settings.reload();
  assert.equal(settings.templates[0].id, original.id);
  assert.equal(settings.templates[0].name, '改名后');
  assert.equal(settings.selectedId, 'none');
  assert.notEqual(settings.customRowKey(settings.templates[0]), oldKey);
  const updatedKey = settings.customRowKey(settings.templates[0]); settings.selectedId = original.id;
  assert.equal(settings.customRowKey(settings.templates[0]), updatedKey, 'selection keeps the row so its check can transition');
});

test('completion of an aborted AI editor request cannot clear the newer request or replace its draft', async () => {
  const completions = [];
  const editor = workbench(store(), {
    AI_TEMPLATE_PROMPT: '生成模板:', makeUserMessage: text => ({ role: 'user', text }),
    createEntryAbortController: () => { const signal = { aborted: false }; return { signal, abort: () => { signal.aborted = true; } }; },
    getDeepReadTextRuntime: async () => ({ model: {}, aiClient: {
      generateText: () => new Promise(resolve => completions.push(resolve)),
    } }),
  });
  editor.startNew(); editor.aiPrompt = '旧请求';
  const older = editor.generate(); await tick();
  editor.startNew(); editor.aiPrompt = '新请求';
  const newer = editor.generate(); await tick();
  const controller = editor.generationController;
  completions[0]([{ role: 'assistant', parts: [{ type: 'text', text: '<article>{{summary}}</article>' }] }]);
  await older;
  assert.equal(editor.generating, true); assert.equal(editor.generationController, controller);
  assert.equal(editor.editHtml, template.starterTemplateHtml());
  completions[1]([{ role: 'assistant', parts: [{ type: 'text', text: '<article>{{analysis}}</article>' }] }]);
  await newer;
  assert.equal(editor.generating, false); assert.equal(editor.generationController, null);
  assert.equal(editor.editHtml, '<article>{{analysis}}</article>');
  assert.equal(editor.hasUnsavedChanges(), true);
});

test('previewing from the template library loads the durable item before opening its preview', async () => {
  const storage = store();
  const saved = { id: 'custom_preview', name: '已存模板', html: '<article>{{summary}}</article>', createdAt: 1 };
  await template.saveCustomTemplates(storage, [saved]);
  const routes = [];
  const settings = page('DeepReadTemplateSettingsPage.ets', ['preview', 'generateTemplate'], {
    router: { pushUrl: route => routes.push(route) },
  });
  settings.preview(saved.id); settings.generateTemplate();
  assert.equal(routes[0].params.templateId, saved.id);
  assert.equal(routes[0].params.preview, true);
  assert.equal(routes[1].params.mode, 'generate');
  const editor = workbench(storage); editor.startNew();
  editor.requestedTemplateId = saved.id; editor.requestedPreview = true;
  editor.reload(); await tick();
  assert.equal(editor.editHtml, saved.html);
  assert.equal(editor.editName, saved.name);
  assert.equal(editor.previewOpen, true);
});

test('slow preferences refresh an already attached automatic preview with the current article font', async () => {
  const storage = store(); let scale; let mode; const previews = [];
  const saved = { id: 'custom_preview', name: '慢偏好模板', html: '<article>{{summary}}</article>', createdAt: 1 };
  await template.saveCustomTemplates(storage, [saved]);
  const editor = workbench(storage, { getProductKind: () => 'deepread',
    getChatKvStore: () => ({ get: key => new Promise(resolve => {
      if (key === 'deepread_font_scale') scale = resolve;
      else mode = resolve;
    }) }) });
  editor.startNew(); editor.requestedTemplateId = saved.id; editor.requestedPreview = true;
  editor.reload(); await tick();
  assert.equal(editor.previewOpen, true); assert.equal(editor.editHtml, saved.html);
  editor.webAttached = true; editor.webInitialized = true;
  editor.webController = { loadData: html => previews.push(html) }; editor.loadPreview();
  assert.match(previews.at(-1), /--ds:1\.000/);
  scale('140'); await tick(); assert.match(previews.at(-1), /--ds:1\.400/);
  mode(null); await tick(); assert.match(previews.at(-1), /--dr-font:Georgia,'Noto Serif SC',serif/);
  const current = previews.length; editor.aboutToDisappear(); editor.loadPreview();
  assert.equal(previews.length, current);
});

test('explicit valid preview font drafts win over late preferences without changing durable settings', async () => {
  for (const fontMode of ['default', 'serif']) {
    const storage = store(); const previews = []; let scale; let mode;
    const editor = workbench(storage, { getProductKind: () => 'deepread',
      getChatKvStore: () => ({ get: key => new Promise(resolve => {
        if (key === 'deepread_font_scale') scale = resolve; else mode = resolve;
      }) }) });
    editor.startNew(); Object.assign(editor, { previewFontScale: 150, previewFontMode: fontMode });
    editor.reload(); await tick(); editor.openPreview(); editor.webAttached = true; editor.webInitialized = true;
    editor.webController = { loadData: html => previews.push(html) }; editor.loadPreview();
    scale('70'); mode(fontMode === 'serif' ? 'default' : 'serif'); await tick();
    editor.loadPreview();
    assert.equal(editor.fontScale, 1.5); assert.equal(editor.fontSerif, fontMode === 'serif');
    assert.match(previews.at(-1), /--ds:1\.500/);
    assert.ok(previews.at(-1).includes(fontMode === 'serif'
      ? "--dr-font:Georgia,'Noto Serif SC',serif" : "--dr-font:'HarmonyOS Sans SC',sans-serif"));
    assert.equal(storage.writes.length, 0);
  }
});

test('invalid or absent preview params use persisted preferences and the typed route forwards only valid drafts', async () => {
  const storage = store();
  const editor = workbench(storage, {
    getChatKvStore: () => ({ get: async key => key === 'deepread_font_scale' ? '120' : 'serif' }) });
  editor.startNew(); editor.previewFontScale = NaN; editor.previewFontMode = 'invalid';
  editor.reload(); await tick(); assert.equal(editor.fontScale, 1.2); assert.equal(editor.fontSerif, true);
  const routes = [];
  const settings = page('DeepReadTemplateSettingsPage.ets', ['aboutToAppear', 'clearSelectionMotion', 'generateTemplate'], {
    getProductKind: () => 'deepread', getAppContainer: () => ({ storage }),
    router: { getParams: () => ({ readerFontMode: 'default', readerFontScale: 150 }), pushUrl: route => routes.push(route) } });
  Object.assign(settings, { lifecycleId: 0, motionId: 0 }); settings.aboutToAppear(); settings.generateTemplate();
  assert.equal(settings.previewFontMode, 'default'); assert.equal(settings.previewFontScale, 150);
  assert.deepEqual(routes[0].params, { mode: 'generate', readerFontMode: 'default', readerFontScale: 150 });
  const route = generatorRoute({ router: { getParams: () => routes[0].params } });
  assert.equal(route.previewFontMode, 'default'); assert.equal(route.previewFontScale, 150);
  const invalid = generatorRoute({ router: { getParams: () => ({ mode: 'generate', readerFontMode: 'invalid', readerFontScale: Infinity }) } });
  assert.equal(invalid.previewFontMode, ''); assert.equal(invalid.previewFontScale, 0);
});

function animatedSelection(storage = store(), kind = 'deepread') {
  const animations = []; let haptics = 0;
  const settings = page('DeepReadTemplateSettingsPage.ets', ['select', 'applySelection', 'clearSelectionMotion',
    'onPageHide', 'aboutToDisappear'], { getProductKind: () => kind, DeepReadHaptics: { selection: () => { haptics++; } } });
  Object.assign(settings, { storage, alive: true, lifecycleId: 1, motionId: 0, sheetSession: 0, loadId: 0,
    selectedId: 'none', pageVisible: true, appBackgrounded: false, reduceMotion: false, saving: false,
    bounceId: '', iconScale: 1, iconOffset: 0, error: '' });
  settings.getUIContext = () => ({ keyframeAnimateTo: (options, frames) => animations.push({ options, frames }) });
  return { settings, animations, haptics: () => haptics };
}

test('successful changed selection emits one bounded icon bounce and feedback after storage; repeats and failures do not', async () => {
  const f = animatedSelection(); await f.settings.select('editorial_slant');
  assert.equal(f.settings.storage.values.get('deepread_template_id'), 'editorial_slant');
  assert.equal(f.animations.length, 1); assert.equal(f.animations[0].options.iterations, 1); assert.equal(f.haptics(), 1);
  f.animations[0].frames[0].event(); assert.equal(f.settings.iconScale, 1.15); assert.equal(f.settings.iconOffset, -3);
  f.animations[0].frames[1].event(); assert.equal(f.settings.iconScale, 1); assert.equal(f.settings.iconOffset, 0);
  f.animations[0].options.onFinish(); assert.equal(f.settings.bounceId, '');
  await f.settings.select('editorial_slant'); assert.equal(f.animations.length, 1); assert.equal(f.haptics(), 1);
  f.settings.storage.set = async () => { throw Error('write failed'); };
  await f.settings.select('none'); assert.equal(f.settings.selectedId, 'editorial_slant');
  assert.equal(f.animations.length, 1); assert.equal(f.haptics(), 1); assert.match(f.settings.error, /write failed/);
});

test('reduced motion, host and hidden pages reach selection directly while old frames cannot alter a reopened cycle', async () => {
  for (const gate of ['reduced', 'background', 'hidden', 'host']) {
    const f = animatedSelection(store(), gate === 'host' ? 'agent' : 'deepread');
    f.settings.reduceMotion = gate === 'reduced'; f.settings.appBackgrounded = gate === 'background';
    f.settings.pageVisible = gate !== 'hidden'; await f.settings.select('editorial_slant');
    assert.equal(f.settings.selectedId, 'editorial_slant'); assert.equal(f.animations.length, 0);
    assert.equal(f.settings.iconScale, 1); assert.equal(f.settings.iconOffset, 0);
    assert.equal(f.haptics(), gate === 'reduced' ? 1 : 0, 'reduce motion does not disable sensory selection feedback');
  }
  const f = animatedSelection(); await f.settings.select('editorial_slant'); const old = f.animations[0];
  f.settings.onPageHide(); f.settings.pageVisible = true; await f.settings.select('none'); const current = f.animations[1];
  current.frames[0].event(); old.frames[1].event(); old.options.onFinish();
  assert.equal(f.settings.bounceId, 'none'); assert.equal(f.settings.iconScale, 1.15);
  f.settings.aboutToDisappear(); current.frames[0].event(); current.options.onFinish();
  assert.equal(f.settings.bounceId, ''); assert.equal(f.settings.iconScale, 1);
});

test('a selection committed after route disposal cannot animate or replace the new page state', async () => {
  let finish; const storage = store(); storage.set = () => new Promise(resolve => { finish = resolve; });
  const f = animatedSelection(storage); const pending = f.settings.select('editorial_slant');
  f.settings.aboutToDisappear(); f.settings.alive = true; f.settings.pageVisible = true;
  f.settings.selectedId = 'custom_current'; finish(); await pending;
  assert.equal(f.settings.selectedId, 'custom_current'); assert.equal(f.animations.length, 0); assert.equal(f.haptics(), 0);
});

test('visible validation is cleared when editing the draft and invalid edits cannot replace a saved template', async () => {
  const storage = store(); const editor = workbench(storage);
  editor.startNew(); await editor.save();
  const original = (await template.loadCustomTemplates(storage))[0];
  editor.validateDraft(); assert.match(editor.note, /校验通过/);
  editor.changeName('修改名称'); assert.equal(editor.note, '');
  editor.validateDraft(); editor.changeHtml('<script>alert(1)</script>{{content}}');
  assert.equal(editor.note, ''); editor.validateDraft(); assert.match(editor.note, /禁/);
  await editor.save();
  assert.equal((await template.loadCustomTemplates(storage))[0].html, original.html);
});

test('standalone editor closes only after both template persistence and default selection succeed', async () => {
  let closed = 0;
  const storage = store(); const editor = workbench(storage, {
    getProductKind: () => 'deepread', router: { back: () => { closed++; } },
  });
  editor.startNew(); await editor.save();
  assert.equal(closed, 1);
  assert.equal(storage.values.get('deepread_template_id'), editor.editingId);
  const set = storage.set;
  storage.set = async (key, value) => { if (key === 'deepread_template_id') throw Error('落盘失败'); await set(key, value); };
  editor.changeName('第二个名称'); await editor.save();
  assert.equal(closed, 1);
  assert.match(editor.note, /模板已保存，但选用失败/);
});

test('standalone system back and title cancel both retain edited HTML until the user chooses to discard', () => {
  const dialogs = []; let closed = 0;
  const editor = workbench(store(), {
    getProductKind: () => 'deepread', router: { back: () => { closed++; } },
    AlertDialog: { show: value => dialogs.push(value) },
  });
  editor.startNew(); assert.equal(editor.onBackPress(), false);
  editor.changeHtml('<article>{{summary}}</article>');
  assert.equal(editor.onBackPress(), true); assert.equal(dialogs.length, 1); assert.equal(closed, 0);
  editor.requestLeave(); assert.equal(dialogs.length, 1);
  dialogs[0].primaryButton.action();
  assert.equal(editor.editHtml, '<article>{{summary}}</article>'); assert.equal(closed, 0);
  editor.requestLeave(); assert.equal(dialogs.length, 2);
  let dismissed = 0;
  dialogs[1].onWillDismiss({ dismiss: () => { dismissed++; } });
  assert.equal(dismissed, 1);
  assert.equal(editor.editHtml, '<article>{{summary}}</article>'); assert.equal(closed, 0);
  editor.requestLeave(); assert.equal(dialogs.length, 3);
  dialogs[2].secondaryButton.action(); assert.equal(closed, 1);
});

test('exit confirmation does not affect the host and old discard callbacks cannot pop a reopened page', () => {
  const dialogs = []; let closed = 0; let kind = 'agent';
  const editor = workbench(store(), {
    getProductKind: () => kind, router: { back: () => { closed++; } },
    AlertDialog: { show: value => dialogs.push(value) },
  });
  editor.startNew(); editor.changeName('未保存的名称');
  assert.equal(editor.onBackPress(), false); assert.equal(dialogs.length, 0);
  kind = 'deepread'; editor.requestLeave(); assert.equal(dialogs.length, 1);
  editor.aboutToDisappear(); editor.alive = true;
  dialogs[0].secondaryButton.action(); assert.equal(closed, 0);
});

test('back from a live template preview closes the preview and leaves the unsaved editor intact', () => {
  const dialogs = []; let closed = 0;
  const editor = workbench(store(), {
    getProductKind: () => 'deepread', router: { back: () => { closed++; } },
    AlertDialog: { show: value => dialogs.push(value) },
  });
  editor.startNew(); editor.changeHtml('<article>{{summary}}</article>');
  editor.openPreview(); editor.webAttached = true; editor.webInitialized = true;
  assert.equal(editor.onBackPress(), true);
  assert.equal(editor.previewOpen, false); assert.equal(editor.webAttached, false);
  assert.equal(editor.editHtml, '<article>{{summary}}</article>');
  assert.equal(closed, 0); assert.equal(dialogs.length, 0);
  editor.requestLeave(); assert.equal(dialogs.length, 1);
});

test('AI push preserves its form, mounts an unsaved editor sheet and returns to the same form after one save', async () => {
  const storage = store(); const routes = []; let backs = 0;
  const settings = templateSheet(storage, { router: { pushUrl: route => routes.push(route) } });
  settings.generateTemplate(); assert.equal(settings.sheetOpen, false);
  assert.deepEqual(routes, [{ url: 'pages/DeepReadTemplateWorkbenchPage', params: { mode: 'generate' } }]);
  const route = generatorRoute(); const lifecycle = route.lifecycleId;
  const generator = workbench(storage, {
    getProductKind: () => 'deepread', router: { back: () => { backs++; } },
    AI_TEMPLATE_PROMPT: '生成模板:', makeUserMessage: text => ({ role: 'user', text }),
    createEntryAbortController: () => ({ signal: { aborted: false }, abort() { this.signal.aborted = true; } }),
    getDeepReadTextRuntime: async () => ({ model: {}, aiClient: {
      generateText: async () => [{ role: 'assistant', parts: [{ type: 'text', text: '<article>{{analysis}}</article>' }] }],
    } }),
  });
  Object.assign(generator, { initialMode: 'generate', presentGeneratedDraft: true, editorPort: route.editorPort,
    onGenerated: (name, html) => route.generatedDraft(lifecycle, name, html) });
  generator.aboutToAppear(); await tick(); generator.aiPrompt = '温暖杂志风格';
  await generator.generate();
  assert.equal(route.sheetOpen, true); assert.equal(generator.editorMode, 'generate');
  assert.equal(generator.aiPrompt, '温暖杂志风格'); assert.equal(generator.editName, '我的阅读模板');
  assert.equal(route.draftHtml, '<article>{{analysis}}</article>');
  assert.equal(generator.alive, true); assert.equal(generator.hasUnsavedChanges(), false);
  assert.equal(storage.writes.length, 0);
  const editorSession = route.sheetSession;
  const editor = workbench(storage, { getProductKind: () => 'deepread' });
  Object.assign(editor, { embedded: true, draftName: route.draftName, draftHtml: route.draftHtml,
    editorPort: route.sheetPort, onSaved: () => route.closeSheet(editorSession),
    onDismiss: () => route.closeSheet(editorSession) });
  editor.aboutToAppear(); await tick();
  assert.equal(editor.editHtml, '<article>{{analysis}}</article>');
  assert.equal(editor.hasUnsavedChanges(), true); assert.equal(storage.writes.length, 0);
  await editor.save(); assert.equal(route.sheetOpen, false);
  editor.aboutToDisappear(); route.sheetDisappeared(editorSession); await tick();
  assert.deepEqual(storage.writes, ['deepread_custom_templates', 'deepread_template_id']);
  assert.equal(backs, 0); assert.equal(generator.editorMode, 'generate');
  assert.equal(generator.aiPrompt, '温暖杂志风格'); assert.equal(generator.alive, true);
  await settings.reload();
  assert.equal(settings.templates.length, 1); assert.equal(settings.templates[0].html, '<article>{{analysis}}</article>');
  assert.equal(settings.selectedId, settings.templates[0].id);
});

test('sheet swipe reuses the real editor discard guard and preview dismissal keeps the editor sheet open', async () => {
  const dialogs = []; let dismissed = 0;
  const settings = templateSheet(); settings.edit(); const session = settings.sheetSession;
  const editor = workbench(settings.storage, { getProductKind: () => 'deepread', AlertDialog: { show: dialog => dialogs.push(dialog) } });
  Object.assign(editor, { embedded: true, editorPort: settings.sheetPort,
    onDismiss: () => settings.closeSheet(session) });
  editor.aboutToAppear(); await tick(); editor.changeHtml('<article>{{summary}}</article>');
  const options = settings.workbenchSheetOptions(session, settings.sheetPort);
  options.onWillDismiss({ dismiss: () => { dismissed++; } });
  assert.equal(dialogs.length, 1); assert.equal(dismissed, 0); assert.equal(settings.sheetOpen, true);
  dialogs[0].primaryButton.action(); assert.equal(editor.editHtml, '<article>{{summary}}</article>');
  editor.openPreview();
  options.onWillDismiss({ dismiss: () => { dismissed++; } });
  assert.equal(editor.previewOpen, false); assert.equal(dismissed, 0); assert.equal(settings.sheetOpen, true);
  options.onWillDismiss({ dismiss: () => { dismissed++; } });
  dialogs[1].secondaryButton.action(); assert.equal(dismissed, 1);
});

test('old save and native sheet callbacks cannot close a newer template-list editor or a departed page', () => {
  const settings = templateSheet(); settings.edit(); const old = settings.sheetSession;
  const oldOptions = settings.workbenchSheetOptions(old, settings.sheetPort);
  settings.closeSheet(old); settings.sheetDisappeared(old); settings.edit();
  const current = settings.sheetSession; let oldDismissed = 0;
  settings.sheetSaved(old); oldOptions.onDisappear();
  oldOptions.onWillDismiss({ dismiss: () => { oldDismissed++; } });
  assert.equal(oldDismissed, 1); assert.equal(settings.sheetSession, current);
  assert.equal(settings.sheetOpen, true);
  settings.aboutToDisappear();
  settings.sheetSaved(current); assert.equal(settings.sheetOpen, false);
});

test('a disposed AI route cannot deliver a late draft into a reopened generator or its newer editor sheet', async () => {
  let release; const storage = store(); const route = generatorRoute();
  const oldLifecycle = route.lifecycleId; let callbacks = 0;
  const generator = workbench(storage, {
    AI_TEMPLATE_PROMPT: '生成模板:', makeUserMessage: text => ({ role: 'user', text }),
    createEntryAbortController: () => ({ signal: { aborted: false }, abort() { this.signal.aborted = true; } }),
    getDeepReadTextRuntime: async () => ({ model: {}, aiClient: {
      generateText: () => new Promise(resolve => { release = resolve; }),
    } }),
  });
  Object.assign(generator, { initialMode: 'generate', presentGeneratedDraft: true, editorPort: route.editorPort,
    onGenerated: (name, html) => { callbacks++; route.generatedDraft(oldLifecycle, name, html); } });
  generator.aboutToAppear(); await tick(); generator.aiPrompt = '旧要求';
  const pending = generator.generate(); await tick();
  generator.aboutToDisappear(); route.aboutToDisappear(); route.aboutToAppear();
  route.generatedDraft(route.lifecycleId, '新生成', '<article>{{summary}}</article>');
  const current = route.sheetSession;
  release([{ role: 'assistant', parts: [{ type: 'text', text: '<article>{{analysis}}</article>' }] }]);
  await pending;
  assert.equal(callbacks, 0); assert.equal(route.sheetSession, current); assert.equal(route.sheetOpen, true);
  route.generatedDraft(oldLifecycle, '旧生成', '<article>{{content}}</article>');
  assert.equal(route.draftHtml, '<article>{{summary}}</article>'); assert.equal(storage.writes.length, 0);
});

test('generated editor cancellation closes only its sheet and stale native callbacks preserve the next draft', async () => {
  const route = generatorRoute(); const storage = store(); const dialogs = []; let backs = 0;
  route.generatedDraft(route.lifecycleId, '草稿', '<article>{{analysis}}</article>');
  const oldSession = route.sheetSession; const oldOptions = route.editorSheetOptions(oldSession, route.sheetPort);
  const editor = workbench(storage, { getProductKind: () => 'deepread', router: { back: () => { backs++; } },
    AlertDialog: { show: dialog => dialogs.push(dialog) } });
  Object.assign(editor, { embedded: true, draftName: route.draftName, draftHtml: route.draftHtml,
    editorPort: route.sheetPort, onDismiss: () => route.closeSheet(oldSession) });
  editor.aboutToAppear(); await tick(); editor.requestLeave();
  assert.equal(dialogs.length, 1); assert.equal(route.sheetOpen, true);
  dialogs[0].secondaryButton.action(); assert.equal(route.sheetOpen, false);
  editor.aboutToDisappear(); oldOptions.onDisappear();
  assert.equal(route.editorMode, 'generate'); assert.equal(route.alive, true);
  assert.equal(route.draftHtml, ''); assert.equal(backs, 0); assert.equal(storage.writes.length, 0);
  route.generatedDraft(route.lifecycleId, '新草稿', '<article>{{summary}}</article>');
  const current = route.sheetSession; oldOptions.onDisappear(); route.closeSheet(oldSession);
  let dismissed = 0; oldOptions.onWillDismiss({ dismiss: () => { dismissed++; } });
  assert.equal(dismissed, 1); assert.equal(route.sheetOpen, true); assert.equal(route.sheetSession, current);
  assert.equal(route.draftHtml, '<article>{{summary}}</article>');
});

test('template description edits participate in discard protection and survive the actual save/reload path', async () => {
  const storage = store(); const editor = workbench(storage); editor.startNew();
  editor.changeDescription('  适合专题长文的温暖杂志排版  ');
  assert.equal(editor.hasUnsavedChanges(), true);
  await editor.save();
  const saved = (await template.loadCustomTemplates(storage))[0];
  assert.equal(saved.description, '适合专题长文的温暖杂志排版');
  editor.startEdit(saved); assert.equal(editor.editDescription, saved.description);
  assert.equal(editor.hasUnsavedChanges(), false);
  editor.changeDescription('更新后的描述'); assert.equal(editor.hasUnsavedChanges(), true);
  assert.equal((await template.loadCustomTemplates(storage))[0].description, saved.description);
});

test('importing an HTML file preserves the editable description and both persist through the same template store', async () => {
  const storage = store(); const editor = workbench(storage, {
    AppStorage: { get: () => ({}) }, picker: { DocumentSelectOptions: class {}, DocumentViewPicker: class {
      async select() { return ['file.html']; }
    } }, fileIo: { statSync: () => ({ size: 30 }), readTextSync: () => '<article>{{analysis}}</article>' },
  });
  editor.startNew(); editor.changeDescription('导入 HTML 前填写的描述');
  await editor.importHtmlFile();
  assert.equal(editor.editDescription, '导入 HTML 前填写的描述');
  assert.equal(editor.editHtml, '<article>{{analysis}}</article>');
  await editor.save(); const [saved] = await template.loadCustomTemplates(storage);
  assert.equal(saved.description, editor.editDescription); assert.equal(saved.html, editor.editHtml);
});
