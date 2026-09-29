// Execute the production page lifecycle/editor methods with a shared keyboard host
// and the real novel mutations. ArkUI rendering and keyboard geometry remain device checks.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../chat/node_modules/typescript');
require('../chat/node_modules/tsx/dist/cjs/index.cjs');
const models = require('../deepread/src/main/ets/novel/models.ts');
const mutations = require('../deepread/src/main/ets/novel/mutations.ts');

const modes = { OFFSET: 'OFFSET', RESIZE: 'RESIZE', NONE: 'NONE' };

function keyboardHost() {
  let mode = modes.NONE;
  const writes = [];
  return {
    writes,
    getKeyboardAvoidMode: () => mode,
    setKeyboardAvoidMode: value => { mode = value; writes.push(value); },
  };
}

function fixture(name, host = keyboardHost()) {
  let project = models.makeNovelProject({ id: 'novel-editor-fixture', name: '本地测试', now: 1 });
  const added = mutations.saveChapter(project, null, '已有章节', '原始正文', 2);
  project = added.project;
  const service = {
    saveChapter: async (_projectId, chapterId, title, content) => {
      const result = mutations.saveChapter(project, chapterId, title, content, 3);
      project = result.project;
      return result.value;
    },
    upsertMaterial: async (_projectId, materialId, kind, title, content, enabled) => {
      const result = mutations.upsertMaterial(project, materialId, kind, title, content, enabled, 3);
      project = result.project;
      return result.value;
    },
  };
  const source = fs.readFileSync(path.join(__dirname, '../entry/src/main/ets/pages', `${name}.ets`), 'utf8');
  const start = source.indexOf(`struct ${name} {`);
  const body = source.slice(start, source.indexOf('\n  @Builder', start))
    .replace(`struct ${name}`, `class ${name}`)
    .replace(/^([ \t]*)(?:@\w+(?:\([^)]*\))?\s*)+/gm, '$1');
  const compiled = ts.transpileModule(`${body}\n}\nreturn new ${name}();`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText;
  const dependencies = {
    KeyboardAvoidMode: modes,
    Scroller: class {},
    router: { getParams: () => ({ projectId: project.id, chapterId: added.value.id }) },
    getNovelCreation: () => service,
  };
  const page = new Function(...Object.keys(dependencies), compiled)(...Object.values(dependencies));
  page.getUIContext = () => host;
  page.reload = async () => { page.project = project; };
  page.loadProjectModelOptions = async () => {};
  page.aboutToAppear();
  page.onPageShow?.();
  return { page, host, project: () => project };
}

for (const name of ['NovelWorkspacePage', 'NovelChapterReaderPage']) {
  test(`${name}: keyboard ownership follows each visible lifetime, with idempotent cleanup`, () => {
    const { page, host } = fixture(name);
    assert.equal(host.getKeyboardAvoidMode(), modes.RESIZE);
    page.onPageShow?.();
    page.onPageHide?.();
    assert.equal(host.getKeyboardAvoidMode(), modes.NONE, 'hide restores the actual previous mode');
    assert.deepEqual(host.writes, [modes.RESIZE, modes.NONE]);
    host.setKeyboardAvoidMode(modes.OFFSET);
    page.onPageShow?.();
    assert.equal(host.getKeyboardAvoidMode(), modes.RESIZE, 'returning to the retained page reacquires RESIZE');
    page.onPageHide?.();
    assert.equal(host.getKeyboardAvoidMode(), modes.OFFSET);
    host.setKeyboardAvoidMode(modes.NONE);
    page.aboutToDisappear();
    assert.equal(host.getKeyboardAvoidMode(), modes.NONE, 'hidden-page destruction cannot overwrite another page');

    const direct = fixture(name);
    direct.page.aboutToDisappear();
    assert.equal(direct.host.getKeyboardAvoidMode(), modes.NONE, 'direct destruction restores the prior mode');
  });
}

test('workspace -> reader -> retained workspace keeps RESIZE after reader cleanup', () => {
  const { page: workspace, host } = fixture('NovelWorkspacePage');
  workspace.onPageHide();
  const { page: reader } = fixture('NovelChapterReaderPage', host);
  reader.onPageHide?.();
  workspace.onPageShow();
  reader.aboutToDisappear();
  assert.equal(host.getKeyboardAvoidMode(), modes.RESIZE);
  workspace.onPageHide();
  workspace.aboutToDisappear();
  assert.equal(host.getKeyboardAvoidMode(), modes.NONE, 'the final page restores the original host setting');
});

test('new chapter validation retains the draft, then a fresh editor clears the old error', async () => {
  const { page, project } = fixture('NovelWorkspacePage');
  page.openNewChapter();
  page.chapterTitle = '';
  page.chapterContent = '未保存正文';
  await page.saveChapter();
  assert.equal(page.chapterEditorOpen, true);
  assert.equal(page.chapterContent, '未保存正文');
  assert.equal(page.errorMsg, '标题不能为空');
  assert.equal(project().chapters.length, 1, 'invalid input did not persist');
  page.chapterEditorOpen = false;
  page.openNewChapter();
  assert.equal(page.errorMsg, '', 'a new editor must not inherit the previous failure');
  page.chapterTitle = '修正后的章节';
  await page.saveChapter();
  assert.equal(page.chapterEditorOpen, false);
  assert.equal(project().chapters.length, 2);
});

test('material validation retains the draft and both new/edit openings clear stale errors', async () => {
  const { page, project } = fixture('NovelWorkspacePage');
  page.openNewMaterial('character');
  page.materialContent = '未保存人物';
  await page.saveMaterial();
  assert.equal(page.materialEditorOpen, true);
  assert.equal(page.materialContent, '未保存人物');
  assert.equal(page.errorMsg, '标题不能为空');
  assert.equal(project().materials.length, 0);
  page.materialEditorOpen = false;
  page.openNewMaterial('character');
  assert.equal(page.errorMsg, '');
  page.materialTitle = '人物甲';
  await page.saveMaterial();
  assert.equal(page.materialEditorOpen, false);
  page.errorMsg = '旧错误';
  page.openEditMaterial(project().materials[0]);
  assert.equal(page.errorMsg, '');
  assert.equal(page.materialTitle, '人物甲');
});

test('reader validation retains edited content and reopening clears the stale error', async () => {
  const { page, project } = fixture('NovelChapterReaderPage');
  page.openEdit();
  page.editTitle = '';
  page.editContent = '待保存的阅读器正文';
  await page.saveEdit();
  assert.equal(page.editOpen, true);
  assert.equal(page.editContent, '待保存的阅读器正文');
  assert.equal(page.errorMsg, '标题不能为空');
  assert.equal(project().chapters[0].content, '原始正文');
  page.editOpen = false;
  page.openEdit();
  assert.equal(page.errorMsg, '');
  page.editTitle = '修正章节';
  await page.saveEdit();
  assert.equal(page.editOpen, false);
  assert.equal(project().chapters[0].title, '修正章节');
});
