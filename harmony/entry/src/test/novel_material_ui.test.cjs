const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
require('../../../chat/node_modules/tsx/dist/cjs/index.cjs');
const models = require('../../../deepread/src/main/ets/novel/models.ts');
const fieldsApi = require('../../../deepread/src/main/ets/novel/material_fields.ts');
const { createNovelCreation } = require('../../../deepread/src/main/ets/novel/creation.ts');
const { createFileNovelRepository } = require('../../../deepread/src/main/ets/novel/repository.ts');
const { createMemoryFileStore } = require('../../../deepread/src/main/ets/platform/files.ts');
const adoptionApi = require('../../../deepread/src/main/ets/novel/material_adoption.ts');
const sourceRoot = path.resolve(__dirname, '../main/ets');

function method(source, name) {
  const match = new RegExp('^  (?:private )?(?:async )?' + name + '\\(', 'm').exec(source);
  assert.ok(match, 'missing production method ' + name);
  let depth = 1, end = source.indexOf('{', match.index) + 1;
  for (; depth && end < source.length; end++) {
    if (source[end] === '{') depth++;
    if (source[end] === '}') depth--;
  }
  return source.slice(match.index, end);
}

function instance(file, names, env) {
  const source = fs.readFileSync(path.join(sourceRoot, file), 'utf8');
  const code = ts.transpileModule('class UI {\n' + names.map(name => method(source, name)).join('\n') + '\n}\nreturn new UI();',
    { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  return new Function(...Object.keys(env), code)(...Object.values(env));
}

const helpersSource = fs.readFileSync(path.join(sourceRoot, 'components/NovelMaterialEditorSheet.ets'), 'utf8');
const helperAst = ts.createSourceFile('helpers.ts', helpersSource, ts.ScriptTarget.ES2022, true);
const wanted = new Set(['emptyMaterialEditorDraft', 'materialEditorDraftFor']);
const helperSnippets = helperAst.statements.filter(node => ts.isVariableStatement(node)
  && node.declarationList.declarations.some(declaration => wanted.has(declaration.name.getText(helperAst))))
  .map(node => node.getText(helperAst).replace(/^export /, ''));
const helperCode = ts.transpileModule(helperSnippets.join('\n') + '\nreturn {emptyMaterialEditorDraft,materialEditorDraftFor};',
  { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
const helpers = new Function('normalizeNovelMaterialFields', helperCode)(fieldsApi.normalizeNovelMaterialFields);

async function fixture() {
  const repository = createFileNovelRepository(createMemoryFileStore());
  const base = models.makeNovelMaterial({ id: 'character', kind: 'character', title: '林舟', content: '共享稿', now: 1,
    aliases: ['阿舟'], tags: ['主角'], injectionMode: 'smart' });
  await repository.createProject({ ...models.makeNovelProject({ id: 'material-ui', name: '资料测试', now: 1 }), materials: [base] });
  const creation = createNovelCreation({ repository, modelRunning: { validate: async () => {}, start() { throw new Error('unused'); }, cancel() {} } });
  const page = instance('pages/NovelWorkspacePage.ets', ['openNewMaterial', 'openEditMaterial', 'saveMaterial', 'deleteMaterial',
    'hiddenSharedMaterialsIn', 'restoreMaterialInheritance', 'openMaterialAdoption', 'confirmMaterialAdoption'], {
    getNovelCreation: () => creation, ...helpers, makeNovelMaterial: models.makeNovelMaterial,
    buildSettingProposalAdoption: adoptionApi.buildSettingProposalAdoption,
    buildSuggestionAdoption: adoptionApi.buildSuggestionAdoption,
    materialAdoptionTargetDigest: adoptionApi.materialAdoptionTargetDigest,
  });
  Object.assign(page, { projectId: 'material-ui', pageAlive: true, busy: false, materialOpenToken: 0,
    materialEditorOpen: false, hasBlockingNovelRun: () => false,
    showWorkspaceOperationError: (_title, error) => { page.errorMsg = error.message; } });
  page.reload = async () => { const snapshot = await creation.readWorkspaceSnapshot('material-ui'); page.project = snapshot.project; page.workspaceStatus = snapshot.status; };
  await page.reload();
  return { page, creation, repository, base };
}

test('shared editor loads the actual base while branch editor loads the override, each with its snapshot CAS', async () => {
  const { page, creation, base } = await fixture();
  await creation.upsertMaterial(page.projectId, base.id, base.kind, base.title, '分支稿', true, { aliases: ['分支别名'], injectionMode: 'always' });
  await page.reload();
  const effective = page.project.materials[0];
  await page.openEditMaterial(effective, 'shared');
  assert.equal(page.materialEditorDraft.content, '共享稿');
  assert.deepEqual(page.materialEditorDraft.aliases, ['阿舟']);
  assert.deepEqual(page.materialEditorCas, page.workspaceStatus.cas);
  await page.openEditMaterial(effective, 'branch');
  assert.equal(page.materialEditorDraft.content, '分支稿');
  assert.deepEqual(page.materialEditorDraft.aliases, ['分支别名']);
});

test('shared save writes edited metadata but preserves an existing branch override until restore inheritance', async () => {
  const { page, creation, base } = await fixture();
  await creation.upsertMaterial(page.projectId, base.id, base.kind, base.title, '分支稿', true);
  await page.reload();
  await page.openEditMaterial(page.project.materials[0], 'shared');
  const draft = { ...page.materialEditorDraft, content: '新共享稿', aliases: ['新别名'], tags: ['新标签'], injectionMode: 'off', enabled: false };
  await page.saveMaterial(draft);
  assert.equal(page.materialEditorOpen, false);
  assert.equal(page.project.materials[0].content, '分支稿');
  assert.equal(page.project.baseMaterials[0].content, '新共享稿');
  await page.restoreMaterialInheritance(base.id, page.workspaceStatus.cas);
  assert.equal(page.project.materials[0].content, '新共享稿');
  assert.deepEqual(page.project.materials[0].aliases, ['新别名']);
  assert.equal(page.project.materials[0].enabled, false);
});

test('branch changes after opening an editor cannot rebind its old material id or draft to another branch', async () => {
  const { page, creation, base } = await fixture();
  await page.openEditMaterial(base, 'branch');
  const draft = { ...page.materialEditorDraft, content: '只给原分支的草稿' };
  await creation.createBranch(page.projectId, '其他分支');
  await page.saveMaterial(draft);
  assert.equal(page.materialEditorOpen, true);
  assert.ok(page.errorMsg.length > 0);
  assert.equal((await creation.open(page.projectId)).materials[0].content, '共享稿');
});

test('branch hiding a shared material leaves it discoverable for restore inheritance', async () => {
  const { page, base } = await fixture();
  await page.deleteMaterial(base.id, page.workspaceStatus.cas);
  assert.equal(page.project.materials.length, 0);
  assert.deepEqual(page.hiddenSharedMaterialsIn(['character']).map(material => material.id), [base.id]);
  await page.restoreMaterialInheritance(base.id, page.workspaceStatus.cas);
  assert.equal(page.project.materials[0].id, base.id);
  assert.equal(page.hiddenSharedMaterialsIn(['character']).length, 0);
});

test('adoption target selection retains omitted aliases, tags and disabled mode through actual UI confirmation and repository', async () => {
  const { page, creation, repository, base } = await fixture();
  await creation.upsertMaterial(page.projectId, base.id, base.kind, base.title, base.content, false, { injectionMode: 'off' });
  const chapter = models.makeNovelChapter({ id: 'chapter', title: '第一章', content: '林舟走入城门', now: 2 });
  const suggestion = models.makeNovelSuggestion({ id: 'suggestion', sourceChapterId: chapter.id, kind: 'character', title: '林舟', content: '候选稿', now: 2 });
  await repository.commitProject(page.projectId, (await creation.workspaceStatus(page.projectId)).cas, 'source', 'manual_edit', project => ({
    ...project, chapters: [chapter], materialSuggestions: [suggestion],
  }));
  await page.reload();
  page.openMaterialAdoption(suggestion.id, false);
  assert.equal(page.adoptionFields.injectionMode, undefined, 'source omission must remain an omission in the UI');
  const sheet = instance('components/NovelMaterialAdoptionSheet.ets', ['resetDraft', 'blocked', 'selectTarget', 'selectedTarget', 'canConfirm', 'confirm'],
    { novelMaterialAdoptionFields: fieldsApi.novelMaterialAdoptionFields, setTimeout });
  Object.assign(sheet, { alive: true, visible: true, busy: false, committingInput: false, inputToken: 0,
    initialKind: page.adoptionKind, initialTitle: page.adoptionTitle, initialBody: page.adoptionBody, initialFields: page.adoptionFields,
    draftKey: 'adoption', existingTargets: page.adoptionTargets, titleController: { stopEditing() {} }, bodyController: { stopEditing() {} }, commitFields() {} });
  sheet.resetDraft(); sheet.selectTarget(base.id);
  assert.equal(sheet.fields.injectionMode, 'off');
  assert.deepEqual(sheet.fields.aliases, ['阿舟']);
  sheet.title = '确认的人物'; sheet.body = '编辑后的完整资料';
  const committed = new Promise(resolve => { sheet.onConfirm = async (...args) => { await page.confirmMaterialAdoption(...args); resolve(); }; });
  sheet.confirm(); await committed;
  assert.equal(page.adoptionOpen, false);
  const material = page.project.materials[0];
  assert.equal(material.content, '编辑后的完整资料');
  assert.equal(material.enabled, false);
  assert.deepEqual(material.aliases, ['阿舟']);
  assert.deepEqual(material.tags, ['主角']);
});

test('editor IME fields commit before saving and a replaced draft invalidates its pending submit callback', async () => {
  const callbacks = [], saved = [];
  const sheet = instance('components/NovelMaterialEditorSheet.ets', ['resetDraft', 'copyDraft', 'blocked', 'confirm', 'updateFields'], {
    normalizeNovelMaterialFields: fieldsApi.normalizeNovelMaterialFields, setTimeout: callback => callbacks.push(callback),
  });
  Object.assign(sheet, { alive: true, busy: false, committing: false, inputToken: 0,
    initialDraft: { ...helpers.emptyMaterialEditorDraft(), title: '人物甲' },
    titleController: { stopEditing() {} }, bodyController: { stopEditing() {} }, onConfirm: draft => saved.push(draft) });
  sheet.resetDraft();
  sheet.commitFields = () => sheet.updateFields({ aliases: ['输入法刚提交的别名'], tags: [], customKind: '', injectionMode: 'smart' });
  sheet.confirm(); callbacks.shift()();
  assert.deepEqual(saved[0].aliases, ['输入法刚提交的别名']);
  assert.equal(saved[0].injectionMode, 'smart');
  sheet.confirm(); sheet.resetDraft(); callbacks.shift()();
  assert.equal(saved.length, 1);
});
