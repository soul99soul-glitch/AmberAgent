const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
const source = fs.readFileSync(path.resolve(__dirname, '../main/ets/components/NovelRunRecoveryCard.ets'), 'utf8');
function method(name) {
  const match = new RegExp('^  (?:private )?' + name + '\\(', 'm').exec(source);
  assert.ok(match, name);
  let depth = 1, end = source.indexOf('{', match.index) + 1;
  for (; depth && end < source.length; end++) {
    if (source[end] === '{') depth++;
    if (source[end] === '}') depth--;
  }
  return source.slice(match.index, end);
}
function fixture() {
  const dialogs = [], retried = [], resumed = [], copied = [];
  const code = ts.transpileModule('class Card {\n' + ['resetRecord', 'confirmRetry', 'confirmResume', 'confirmRecovery', 'copyPartial', 'aboutToDisappear'].map(method).join('\n')
    + '\n}\nreturn new Card();', { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  const card = new Function('AlertDialog', code)({ show: dialog => dialogs.push(dialog) });
  Object.assign(card, { alive: true, busy: false, retryAllowed: true, resumeAllowed: false, recordKey: 'run-a', actionToken: 0,
    detail: '分支主线 · 第三章替换候选', partialText: '已保存部分文本',
    onRetry: key => retried.push(key), onResume: key => resumed.push(key), onCopyPartial: (...args) => copied.push(args) });
  return { card, dialogs, retried, resumed, copied };
}
test('retry only dispatches the displayed durable run after explicit confirmation and previews its scope', () => {
  const { card, dialogs, retried } = fixture();
  card.confirmRetry(); assert.equal(retried.length, 0);
  assert.ok(dialogs[0].message.includes('第三章')); assert.ok(dialogs[0].message.includes('不会直接写入正文'));
  dialogs[0].secondaryButton.action(); assert.deepEqual(retried, ['run-a']);
});
test('record changes or component exit invalidate old confirmation, and busy/ineligible records cannot start', () => {
  const { card, dialogs, retried } = fixture();
  card.confirmRetry(); card.recordKey = 'run-b'; card.resetRecord(); dialogs[0].secondaryButton.action();
  assert.equal(retried.length, 0);
  card.confirmRetry(); card.aboutToDisappear(); dialogs[1].secondaryButton.action(); assert.equal(retried.length, 0);
  card.alive = true; card.busy = true; card.confirmRetry(); assert.equal(dialogs.length, 2);
  card.busy = false; card.retryAllowed = false; card.confirmRetry(); assert.equal(dialogs.length, 2);
});
test('eligibility is rechecked when accepting confirmation; partial copying preserves the full saved content and record identity', () => {
  const { card, dialogs, retried, copied } = fixture();
  card.confirmRetry(); card.retryAllowed = false; dialogs[0].secondaryButton.action(); assert.equal(retried.length, 0);
  card.copyPartial(); assert.deepEqual(copied, [['run-a', '已保存部分文本']]);
  card.partialText = ' '; card.copyPartial(); assert.equal(copied.length, 1);
  card.partialText = '新记录'; card.aboutToDisappear(); card.copyPartial(); assert.equal(copied.length, 1);
});


test('resume uses its dedicated callback after cursor continuation preview and never dispatches a fresh retry', () => {
  const { card, dialogs, retried, resumed } = fixture();
  card.resumeAllowed = true; card.retryAllowed = false;
  card.confirmResume(); assert.equal(resumed.length, 0);
  assert.ok(dialogs[0].message.includes('上次保存的响应继续生成')); assert.ok(dialogs[0].message.includes('不重新发送全新请求'));
  dialogs[0].secondaryButton.action(); assert.deepEqual(resumed, ['run-a']); assert.equal(retried.length, 0);
});
test('resume eligibility and record identity are rechecked after confirmation opens', () => {
  const { card, dialogs, resumed } = fixture();
  card.confirmResume(); assert.equal(dialogs.length, 0);
  card.resumeAllowed = true; card.confirmResume(); card.resumeAllowed = false;
  dialogs[0].secondaryButton.action(); assert.equal(resumed.length, 0);
  card.resumeAllowed = true; card.confirmResume(); card.recordKey = 'run-b'; card.resetRecord();
  dialogs[1].secondaryButton.action(); assert.equal(resumed.length, 0);
  card.confirmResume(); card.aboutToDisappear(); dialogs[2].secondaryButton.action(); assert.equal(resumed.length, 0);
});
