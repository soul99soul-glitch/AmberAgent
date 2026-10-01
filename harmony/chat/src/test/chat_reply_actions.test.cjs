const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../../../entry/src/main/ets');
const read = file => fs.readFileSync(path.join(root, file), 'utf8');

test('provider panel uses the measured header boundary including status bar and padding', () => {
  const page = read('pages/ChatPage.ets');
  const menu = read('components/TopModelMenu.ets');
  assert.match(page, /onAreaChange\([^]*?this\.chatHeaderHeight = Number\(area\.height\)/);
  assert.match(page, /topInset: this\.chatHeaderHeight/);
  assert.match(menu, /\.height\(this\.topInset\)/);
});

test('assistant content opens actions on long press and retains branch controls inline', () => {
  const row = read('components/AgentMessageRow.ets');
  assert.match(row, /this\.message\.role === 'assistant' && this\.capabilities\.openActions[^]*?priorityGesture\(LongPressGesture\(\)\.onAction\(\(\): void => \{ this\.onOpenActions\(\); \}\)\)/);
  const page = read('pages/ChatPage.ets');
  for (const name of ['copy', 'regenerate', 'favorite']) {
    assert.match(page, new RegExp(`${name}: false`));
  }
  assert.match(page, /branchSwitch: true/);
  assert.match(page, /fork: true/);
});

test('assistant sheet separates text selection from direct clipboard copy', () => {
  const sheet = read('components/AgentMessageActionsSheet.ets');
  assert.deepEqual([...sheet.matchAll(/this\.actionRow\('[^']+', '([^']+)'/g)].map(m => m[1]),
    ['选择文字', '复制', '重新生成']);
  const page = read('pages/ChatPage.ets');
  assert.match(page, /onSelectText: \(\): void => \{ this\.copyActionsSheetMessage\(\); \}/);
  assert.match(page, /messageCopyText\(this\.actionsSheetRow\.message, false\)/);
  assert.match(page, /onCopy: \(\): void => \{ this\.copyAssistantMessage\(\); \}/);
});
