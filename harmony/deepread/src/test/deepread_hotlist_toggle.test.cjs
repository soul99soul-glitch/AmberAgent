const { test } = require('node:test');
const assert = require('node:assert/strict');
const { actualPage } = require('./deepread_settings_fixture.cjs');

const loadPageMethod = () => {
  const toasts = [];
  const page = actualPage('pages/SettingDeepReadPage.ets', ['toggleHotlist'], {
    promptAction: { showToast: value => toasts.push(value.message) },
  });
  return { page, toasts };
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
