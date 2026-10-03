const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const filename = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl/HotListState.ets');
const exportsObject = {};
vm.runInNewContext(ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText, { exports: exportsObject, Set, Map, JSON, Date, Math, RegExp }, { filename });
const state = exportsObject;
const plain = value => JSON.parse(JSON.stringify(value));
const item = (title, url = 'https://example.com/article') => ({ rank: 1, title, url, heat: '' });
const section = (id, items, fetchedAt, error = null) => ({
  sourceId: id, sourceName: id, items, fetchedAt, error,
});

test('聚合话题保留所有有效种子 URL，去重并拒绝非 HTTP 来源', () => {
  const topic = { sources: [
    { url: 'https://one.test/a' }, { url: 'https://two.test/b' },
    { url: 'https://one.test/a' }, { url: '' }, { url: 'javascript:bad()' },
  ] };
  assert.deepEqual(plain(state.hotTopicSeedUrls(topic)), ['https://one.test/a', 'https://two.test/b']);
});

test('中文分隔的关注词可切换仅关注与全部，原始缓存未被过滤破坏', () => {
  const raw = [section('news', [item('机器人进展'), item('Football scores')], 100)];
  const keywords = state.parseHotListFocusKeywords('机器人，AI、机器人; agent\nLLM');
  assert.deepEqual(plain(keywords), ['机器人', 'AI', 'agent', 'LLM']);
  const focused = state.projectHotListSections(raw, ['news'], keywords, 'focus_only', false);
  assert.equal(focused[0].items.length, 1);
  const all = state.projectHotListSections(raw, ['news'], keywords, 'all', false);
  assert.equal(all[0].items.length, 2);
  assert.equal(raw[0].items.length, 2);
});

test('关注可匹配译文/来源，短英文词不误匹配单词片段', () => {
  const raw = [section('tech', [
    { ...item('OpenAI advances'), displayTitle: '人工智能新进展' }, item('Daily travel'),
  ], 100)];
  assert.equal(state.projectHotListSections(raw, ['tech'], ['人工智能'], 'focus_only', true)[0].items.length, 1);
  assert.equal(state.projectHotListSections(raw, ['tech'], ['AI'], 'focus_only', false)[0].items.length, 0);
});

test('单源失败保留之前的条目和成功时间，关闭源数据仍在原始缓存', () => {
  const raw = [section('one', [item('Old title')], 100), section('off', [item('Disabled')], 90)];
  const next = [section('one', [], 999, 'HTTP 503')];
  const merged = state.mergeHotListSections(raw, next);
  assert.equal(merged[0].items[0].title, 'Old title');
  assert.equal(merged[0].fetchedAt, 100);
  assert.equal(merged[0].error, 'HTTP 503');
  assert.equal(merged[0].stale, true);
  assert.equal(merged[1].sourceId, 'off');
  assert.equal(state.latestHotListUpdate(merged, ['one']), 100);
});

test('全部首次失败不伪造更新时间', () => {
  assert.equal(state.latestHotListUpdate([section('one', [], 999, 'timeout')], ['one']), 0);
});

test('freshness逐启用源检查，手动/来源变更/失败可以重抓', () => {
  const now = 60 * 60 * 1000;
  const fresh = [section('one', [item('News')], now - 1000)];
  assert.equal(state.shouldRefreshHotList(fresh, ['one'], 60, now), false);
  assert.equal(state.shouldRefreshHotList(fresh, ['one', 'new'], 60, now), true);
  assert.equal(state.shouldRefreshHotList(fresh, [], 60, now), false);
  assert.equal(state.shouldRefreshHotList(fresh, ['one'], 15, now + 20 * 60 * 1000), true);
  assert.equal(state.shouldRefreshHotList([section('one', [], now, 'timeout')], ['one'], 60, now), true);
});

test('成功刷新复用同标题译文但不把旧译文套到新标题', () => {
  const raw = [section('one', [{ ...item('Old title'), displayTitle: '旧标题' }], 100)];
  const same = state.mergeHotListSections(raw, [section('one', [item('Old title')], 200)]);
  assert.equal(same[0].items[0].displayTitle, '旧标题');
  const changed = state.mergeHotListSections(raw, [section('one', [item('New title')], 200)]);
  assert.equal(changed[0].items[0].displayTitle, undefined);
});

test('批翻译只消费外文新标题；关闭偏好显示原文且原缓存/标识不变', () => {
  const raw = [section('one', [item('New research'), item('中文新闻')], 100)];
  assert.deepEqual(plain(state.untranslatedHotListTitles(raw, ['one'])), ['New research']);
  const translated = state.withHotListTitleTranslations(raw, { 'New research': '新研究' });
  assert.equal(translated[0].items[0].title, 'New research');
  assert.equal(translated[0].items[0].url, raw[0].items[0].url);
  assert.equal(translated[0].items[0].displayTitle, '新研究');
  assert.equal(raw[0].items[0].displayTitle, undefined);
  const projected = state.projectHotListSections(translated, ['one'], [], 'all', false);
  assert.equal(projected[0].items[0].displayTitle, undefined);
  assert.equal(projected[0].items[0].title, 'New research');
});

test('P6 discovery summaries survive raw cache merge projection and a failed refresh', () => {
  const raw = [section('huggingface_papers', [{ ...item('Paper'), summary: 'Actual paper abstract' }], 100)];
  const first = state.mergeHotListSections([], raw);
  const projected = state.projectHotListSections(first, ['huggingface_papers'], [], 'all', false);
  assert.equal(projected[0].items[0].summary, 'Actual paper abstract');
  const failed = state.mergeHotListSections(first, [section('huggingface_papers', [], 200, 'HTTP 503')]);
  assert.equal(failed[0].items[0].summary, 'Actual paper abstract');
  assert.equal(failed[0].fetchedAt, 100);
  assert.equal(failed[0].stale, true);
});
