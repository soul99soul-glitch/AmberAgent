const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
const source = fs.readFileSync(path.join(__dirname, '../main/ets/pages/DeepReadArticlePage.ets'), 'utf8');
const method = source.match(/^  private openAppearanceSettings\([\s\S]*?^  }/m)[0];
const pages = JSON.parse(fs.readFileSync(path.join(__dirname, '../main/resources/base/profile/main_pages.json'), 'utf8')).src;

test('reader status retires after publication with failed sources but retains actionable recovery errors', () => {
  const statusSource = fs.readFileSync(path.join(__dirname, '../main/ets/components/deepread/DeepReadArticleStatusPanel.ets'), 'utf8');
  const visible = statusSource.match(/^  private visible\([\s\S]*?^  }/m)[0];
  const code = ts.transpileModule('class Status {' + visible + '} return new Status();',
    { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  const status = new Function(code)();
  Object.assign(status, { running: true, complete: false, cancelled: false, awaitingContinue: false,
    error: '', workspaceError: '', webError: '', notice: '', templateMessage: '', sourceFailureCount: 5 });
  assert.equal(status.visible(), true, 'collection exposes cancellation while running');
  status.running = false;
  status.awaitingContinue = true;
  status.notice = '文章尚未完成，已保存的进度仍可用';
  assert.equal(status.visible(), true, 'an incomplete draft keeps continuation available');
  status.complete = true;
  assert.equal(status.visible(), false, 'publication releases the reader area even with failed sources and stale continuation flags');
  status.cancelled = true;
  assert.equal(status.visible(), false, 'cancelling regeneration does not obscure the retained complete article');
  status.running = true;
  assert.equal(status.visible(), true, 'regeneration over retained content still exposes cancellation');
  status.running = false;
  for (const [field, message] of [['error', '重新生成失败'], ['workspaceError', '工作区保存未完成'],
    ['webError', '模板显示失败'], ['templateMessage', '模板不可用，已使用默认排版']]) {
    status[field] = message;
    assert.equal(status.visible(), true, `${field} remains visible on a retained complete article`);
    status[field] = '';
    assert.equal(status.visible(), false, 'resolved recovery errors release the reader area');
  }
});

test('reader appearance menu opens the registered appearance screen and exposes navigation rejection only to its live page', async () => {
  const routes = [], notices = [];
  const code = ts.transpileModule('class Reader {' + method + '} return new Reader();',
    { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  const page = new Function('router', 'promptAction', code)(
    { pushUrl: options => { routes.push(options.url); return Promise.reject(new Error('route denied')); } },
    { showToast: message => notices.push(message.message) });
  page.pageAlive = true;
  page.openAppearanceSettings();
  await Promise.resolve();
  assert.equal(routes[0], 'pages/DeepReadAppearancePage');
  assert.ok(pages.includes(routes[0]));
  assert.deepEqual(notices, ['无法打开版式与样式：route denied']);
  page.pageAlive = false;
  page.openAppearanceSettings();
  await Promise.resolve();
  assert.equal(notices.length, 1, 'an obsolete page cannot publish the later navigation error');
});

test('reader does not render or own the root Dock', () => {
  assert.doesNotMatch(source, /DeepReadTabBar/);
});
