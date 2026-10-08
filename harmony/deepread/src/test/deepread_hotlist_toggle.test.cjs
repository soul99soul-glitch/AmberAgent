const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

// ArkUI builders are not a Node runtime. Execute the actual persistence method
// from the page, keeping its state and the platform toast as controlled inputs.
const loadPageMethod = () => {
  const filename = path.resolve(__dirname, '../../../entry/src/main/ets/pages/SettingDeepReadPage.ets');
  const source = fs.readFileSync(filename, 'utf8');
  const start = source.indexOf('  async toggleHotlist(');
  const end = source.indexOf('\n  async saveSourceConfig(', start);
  assert.ok(start >= 0 && end > start);
  const method = source.slice(start, end);
  const exports = {};
  const toasts = [];
  const fixture = `class SettingsFixture { hotlistToggles = []; storage = null; saved = ''; ${method} }
    exports.SettingsFixture = SettingsFixture;`;
  vm.runInNewContext(ts.transpileModule(fixture, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, { exports, Promise, promptAction: { showToast: (value) => toasts.push(value.message) } }, { filename });
  return { page: new exports.SettingsFixture(), toasts };
};

test('hotlist toggle persists the source key and updates only its displayed switch', async () => {
  const { page, toasts } = loadPageMethod();
  const writes = [];
  page.hotlistToggles = [{ id: 'arxiv_ai', displayName: 'Arxiv AI', enabled: false },
    { id: 'zhihu', displayName: '知乎', enabled: true }];
  page.storage = { set: async (key, value) => writes.push([key, value]) };
  await page.toggleHotlist('arxiv_ai');
  assert.deepEqual(writes, [['source_arxiv_ai', 'true']]);
  assert.equal(page.hotlistToggles[0].enabled, true);
  assert.equal(page.hotlistToggles[1].enabled, true);
  assert.equal(toasts.length, 0);
});

test('storage failure restores the prior switch and reports failure instead of leaving fake success', async () => {
  const { page, toasts } = loadPageMethod();
  page.hotlistToggles = [{ id: 'arxiv_ai', displayName: 'Arxiv AI', enabled: false }];
  page.storage = { set: async () => { throw new Error('disk failure'); } };
  await page.toggleHotlist('arxiv_ai');
  assert.equal(page.hotlistToggles[0].enabled, false);
  assert.equal(page.saved, '热榜源设置保存失败，请重试');
  assert.deepEqual(toasts, ['热榜源设置保存失败，请重试']);
});
