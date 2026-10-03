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
      load(path.resolve(__dirname, '../../../deepread/src/main/ets/domain/enums.ts')));
    if (name === '@amber/chat-domain') return load(path.resolve(__dirname, '../../../chat/src/main/ets/chat/markdown_blocks.ts'));
    const resolved = path.resolve(path.dirname(file), name);
    for (const candidate of [resolved, resolved + '.ets', resolved + '.ts']) if (fs.existsSync(candidate)) return load(candidate);
    throw Error('Unexpected dependency ' + name);
  };
  new Function('exports', 'require', code)(exports, dep);
  return exports;
}
const template = load(path.join(root, 'platform_impl/DeepReadTemplate.ets'));
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
  const source = fs.readFileSync(path.join(root, 'pages', file), 'utf8');
  const code = ts.transpileModule('class Page {\n' + names.map(name => method(source, name)).join('\n') + '\n}\nreturn new Page();',
    { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  const deps = { ...template, BG: '#ffffff', INK: '#222222', INK3: '#555555', SURFACE: '#eeeeee', LINE: '#aaaaaa',
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
    'aboutToDisappear', 'importHtmlFile', 'generate', 'templateRowKey'];
  const editor = page('DeepReadTemplateWorkbenchPage.ets', methods, {
    getChatKvStore: () => ({ get: async () => null }), ...extras,
  });
  Object.assign(editor, { storage, templates: [], loaded: true, alive: true, lifecycleId: 1, editorId: 0,
    saving: false, generating: false, generationController: null, requestedTemplateId: '',
    note: '', genError: '', editName: '', editHtml: '', previewOpen: false, previewId: 0,
    webAttached: true, webInitialized: true, fontScale: 1, fontSerif: false, accentColorStore: '#b8623a' });
  return editor;
}
const tick = () => new Promise(resolve => setImmediate(resolve));

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
  const source = fs.readFileSync(path.join(root, 'pages/DeepReadTemplateWorkbenchPage.ets'), 'utf8');
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
  const settings = page('DeepReadTemplateSettingsPage.ets', ['reload', 'select', 'edit'],
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
});
