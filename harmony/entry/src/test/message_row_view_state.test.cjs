const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { actualPage, entryRoot, loadPureModule } = require('../../../deepread/src/test/deepread_ui_fixture.cjs');
const { MessageRowViewState } = loadPureModule(path.join(entryRoot, 'components/MessageRowViewState.ets'));
const plain = value => JSON.parse(JSON.stringify(value));

function row(viewState, streaming = false) {
  const file = 'components/AgentMessageRow.ets';
  const source = fs.readFileSync(path.join(entryRoot, file), 'utf8');
  const names = ['aboutToAppear', 'aboutToDisappear', 'restoreViewState', 'saveViewState',
    'setAskAnswer', 'toggleAskMulti', 'clearAskDrafts', 'toggleReasoning', 'reasoningStateAt',
    'autoCollapseReasoning', 'captureMiniAppStreamText'];
  const page = actualPage(file, names.filter(name => new RegExp(`^  (?:private )?${name}\\(`, 'm').test(source)), {
    MessageRowViewState, mightContainMiniApp: text => text.startsWith('mini-app:'),
  });
  Object.assign(page, { viewState, streaming, autoCloseThinking: true,
    message: { id: 'message', parts: [] }, reasoningStates: {}, askAnswers: {}, askMultiAnswers: {},
    sawStream: false, lastMiniAppStreamText: '', lastMiniAppStreamMessageId: '' });
  return page;
}

test('a lazy message remount restores explicit reasoning choices and tool drafts', () => {
  const state = new MessageRowViewState();
  const first = row(state, true);
  first.aboutToAppear();
  first.setAskAnswer('question', 'unfinished answer');
  first.toggleAskMulti('choices', 'one');
  first.toggleReasoning('0-0');
  const restored = row(state);
  restored.aboutToAppear();
  assert.deepEqual(plain(restored.askAnswers), { question: 'unfinished answer' });
  assert.deepEqual(plain(restored.askMultiAnswers), { choices: ['one'] });
  assert.equal(restored.reasoningStateAt('0-0'), 'collapsed');
  assert.equal(restored.sawStream, true);
  restored.clearAskDrafts();
  const afterClear = row(state);
  afterClear.aboutToAppear();
  assert.deepEqual(plain(afterClear.askAnswers), {});
  assert.deepEqual(plain(afterClear.askMultiAnswers), {});
});

test('stream preview survives row destruction and another message keeps its own state', () => {
  const state = new MessageRowViewState();
  const first = row(state, true);
  first.message.parts = [{ type: 'text', text: 'mini-app: partial source' }];
  first.aboutToAppear();
  first.captureMiniAppStreamText();
  if (first.aboutToDisappear) first.aboutToDisappear();
  const restored = row(state);
  restored.aboutToAppear();
  assert.equal(restored.lastMiniAppStreamText, 'mini-app: partial source');
  assert.equal(restored.lastMiniAppStreamMessageId, 'message');
  const other = row(new MessageRowViewState());
  other.aboutToAppear();
  assert.deepEqual(plain(other.reasoningStates), {});
  assert.equal(other.lastMiniAppStreamText, '');
});

function markdownBlock(viewState, streamKey = 'message#0') {
  const page = actualPage('components/MarkdownBlockView.ets', ['aboutToAppear', 'aboutToDisappear',
    'codeStateKey', 'saveCodeState', 'codeKey', 'codeRevision', 'codeSourceLines', 'codeLineCount',
    'codeIsCollapsed', 'toggleCodeExpanded'], { CODE_COLLAPSE_THRESHOLD: 10,
    MOTION_OVERLAY: 1, Curve: { EaseOut: 0 } });
  Object.assign(page, { viewState, streamKey, slot: { key: 'md-0' }, codeExpanded: {},
    cachedCode: null, codeBlockAutoCollapse: true });
  page.getUIContext = () => ({ animateTo: (_options, update) => update() });
  return page;
}

test('code expansion survives a Markdown block remount without leaking to another source', () => {
  const state = new MessageRowViewState();
  const block = { kind: 'code_block', lang: 'ts', code: 'const n = 1;\n'.repeat(12) };
  const first = markdownBlock(state);
  first.aboutToAppear();
  assert.equal(first.codeIsCollapsed(block, 0), true);
  first.toggleCodeExpanded(block, 0);
  first.aboutToDisappear();
  const restored = markdownBlock(state);
  restored.aboutToAppear();
  assert.equal(restored.codeIsCollapsed(block, 0), false);
  const other = markdownBlock(state, 'message#1');
  other.aboutToAppear();
  assert.equal(other.codeIsCollapsed(block, 0), true);
});
