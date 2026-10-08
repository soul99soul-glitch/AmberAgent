const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const filename = path.resolve(__dirname, '../../../entry/src/main/ets/pages/DeepReadDiscoverySettingsPage.ets');
const source = fs.readFileSync(filename, 'utf8');
const compile = text => ts.transpileModule(text, { compilerOptions: {
  target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS,
} }).outputText;
const catalog = {};
vm.runInNewContext(compile(fs.readFileSync(path.resolve(__dirname, '../main/ets/domain/discovery.ts'), 'utf8')), { exports: catalog });
const method = name => {
  const start = source.search(new RegExp('^  (?:private\\s+)?(?:async\\s+)?' + name + '\\(', 'm'));
  assert.ok(start >= 0, name + ' is present');
  const open = source.indexOf('{', start);
  let depth = 1;
  let end = open + 1;
  while (depth > 0 && end < source.length) { if (source[end] === '{') depth++; if (source[end] === '}') depth--; end++; }
  return source.slice(start, end);
};
const plain = value => JSON.parse(JSON.stringify(value));
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; };
const harness = (values = {}, hooks = {}) => {
  const writes = [];
  const storage = {
    async get(key, fallback) { if (hooks.get) { const result = hooks.get(key); if (result !== undefined) return result; }
      return Object.hasOwn(values, key) ? values[key] : fallback; },
    async set(key, value) { if (hooks.set) await hooks.set(key, value); values[key] = value; writes.push([key, value]); },
  };
  const context = { module: { exports: {} }, ...catalog, getAppContainer: () => ({ storage }),
    getProductKind: () => 'agent' };
  vm.runInNewContext(compile(`module.exports = class SettingsFixture {
    ${['loadSettings', 'saveSettings', 'changeSource', 'persistPreference', 'sourceGroup', 'aboutToDisappear'].map(method).join('\n')}
  }`), context);
  const page = new context.module.exports();
  Object.assign(page, { sources: [], focusKeywords: '', focusModeIndex: 0, refreshMinutes: 60,
    wifiOnly: false, translateZh: false, alive: true, loadToken: 1, loading: true, settingsLoaded: false,
    saving: false, notice: '', error: '' });
  return { page, storage, values, writes };
};
test('真实发现设置按catalog五组读取持久开关，编辑保存后同key重开还原关注/刷新偏好', async () => {
  const h = harness({ source_hacker_news: 'false', source_sspai: 'true', deepread_focus_keywords: 'AI', deepread_focus_mode: 'focus_only' });
  await h.page.loadSettings(1);
  assert.equal(h.page.settingsLoaded, true);
  assert.equal(h.page.sources.length, catalog.DEEPREAD_DISCOVERY_SOURCES.length);
  assert.equal(h.page.sources.find(item => item.id === 'hacker_news').enabled, false);
  assert.equal(h.page.sources.find(item => item.id === 'sspai').enabled, true);
  assert.equal(h.page.sources.find(item => item.id === 'hupu-zhugandaoretie').enabled, false);
  const grouped = catalog.DEEPREAD_DISCOVERY_CATEGORIES.flatMap(category => plain(h.page.sourceGroup(category)));
  assert.deepEqual(plain(grouped).map(item => item.id).sort(), plain(h.page.sources).map(item => item.id).sort());
  h.page.changeSource('hacker_news');
  h.page.focusKeywords = ' AI,机器人 ';
  h.page.focusModeIndex = 1;
  h.page.refreshMinutes = 30;
  h.page.wifiOnly = true;
  h.page.translateZh = true;
  await h.page.saveSettings();
  assert.equal(h.values.source_hacker_news, 'true');
  assert.equal(h.values.source_sspai, 'true');
  assert.equal(h.values.deepread_focus_keywords, 'AI,机器人');
  assert.equal(h.values.deepread_focus_mode, 'focus_first');
  assert.equal(h.values.deepread_hotlist_refresh_minutes, 30);
  assert.equal(h.values.deepread_hotlist_wifi_only, 'true');
  assert.equal(h.values.deepread_hotlist_translate_zh, 'true');
  assert.match(h.page.notice, /已保存/);
  const reopened = harness(h.values);
  await reopened.page.loadSettings(1);
  assert.equal(reopened.page.focusModeIndex, 1);
  assert.equal(reopened.page.refreshMinutes, 30);
  assert.equal(reopened.page.wifiOnly, true);
  assert.equal(reopened.page.translateZh, true);
});
test('真实发现设置离页时迟到读取不写页面；读取失败不会保存默认值覆盖旧偏好', async () => {
  const hold = deferred();
  const started = deferred();
  const late = harness({}, { get(key) { if (key === 'source_hacker_news') { started.resolve(); return hold.promise; } } });
  const load = late.page.loadSettings(1);
  await started.promise;
  late.page.aboutToDisappear();
  hold.resolve('false');
  await load;
  assert.equal(late.page.sources.length, 0);
  assert.equal(late.page.settingsLoaded, false);
  const failed = harness({ deepread_focus_keywords: '旧内容' }, { get() { throw new Error('read failed'); } });
  await failed.page.loadSettings(1);
  await failed.page.saveSettings();
  assert.match(failed.page.error, /读取发现设置失败/);
  assert.equal(failed.writes.length, 0);
  assert.equal(failed.values.deepread_focus_keywords, '旧内容');
});
test('发现设置写入失败显示失败且允许作者重试，不显示保存成功', async () => {
  let fail = true;
  const h = harness({}, { set() { if (fail) throw new Error('disk unavailable'); } });
  await h.page.loadSettings(1);
  await h.page.saveSettings();
  assert.equal(h.page.notice, '');
  assert.match(h.page.error, /保存发现设置失败/);
  assert.equal(h.page.saving, false);
  fail = false;
  await h.page.saveSettings();
  assert.equal(h.page.error, '');
  assert.match(h.page.notice, /已保存/);
});
