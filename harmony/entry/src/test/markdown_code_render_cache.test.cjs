const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { actualPage, loadPureModule } = require('../../../deepread/src/test/deepread_ui_fixture.cjs');
const { tokenizeCode } = loadPureModule(path.resolve(__dirname, '../../../chat/src/main/ets/chat/code_highlight.ts'));

function fixture() {
  const file = 'components/MarkdownBlockView.ets';
  const counts = { tokenize: 0, split: 0 };
  const names = ['codeRevision', 'getCodeLines', 'splitTokensByLine', 'codeSourceLines',
    'codeLineCount', 'codeKey', 'codeIsCollapsed', 'toggleCodeExpanded', 'saveCodeState',
    'codeStateKey', 'visibleCodeLineCap', 'visibleHighlightLines', 'visiblePlainLines'];
  const page = actualPage(file, names, {
    tokenizeCode: (code, lang) => { counts.tokenize++; return tokenizeCode(code, lang); },
    CODE_COLLAPSE_THRESHOLD: 10, MOTION_OVERLAY: 1, Curve: { EaseOut: 0 },
  });
  Object.assign(page, { cachedCode: null, viewState: null,
    streamKey: 'message#0', slot: { key: 'md-0' }, codeExpanded: {},
    codeBlockAutoCollapse: true, showLineNumbers: false });
  page.getUIContext = () => ({ animateTo: (_options, update) => update() });
  const split = page.splitTokensByLine.bind(page);
  page.splitTokensByLine = tokens => { counts.split++; return split(tokens); };
  return { page, counts };
}

function block(code, lang = 'typescript') { return { kind: 'code_block', code, lang }; }
function highlightedText(lines) { return lines.map(line => line.map(token => token.text).join('')).join('\n'); }

// Exercise the header, body and footer reads performed by RenderCodeBlock rather than
// only checking a helper in isolation. Display-setting changes must reuse this revision.
test('unchanged code builder reads prepare highlighted and plain lines once', t => {
  const { page, counts } = fixture();
  const code = block(Array.from({ length: 80 }, (_, index) => `const line${index} = ${index};`).join('\n'));
  const plain = page.codeSourceLines(code);
  const highlighted = page.getCodeLines(code);
  let reusedHighlighted = true;
  let reusedPlain = true;
  for (let read = 0; read < 20; read++) {
    page.showLineNumbers = read % 2 === 0;
    page.codeLineCount(code);
    page.codeIsCollapsed(code, 0);
    page.visibleHighlightLines(code, 0);
    page.visiblePlainLines(code, 0);
    reusedHighlighted = page.getCodeLines(code) === highlighted && reusedHighlighted;
    reusedPlain = page.codeSourceLines(code) === plain && reusedPlain;
  }
  t.diagnostic(JSON.stringify({ identicalBuilderReads: 20, ...counts, sourceLines: plain.length }));
  assert.deepEqual(counts, { tokenize: 1, split: 1 });
  assert.ok(reusedHighlighted, 'unchanged highlighted arrays should be reused');
  assert.ok(reusedPlain, 'unchanged plain arrays should be reused');
  assert.equal(highlightedText(highlighted), code.code);
  assert.equal(plain.join('\n'), code.code);
});

test('code and language revisions invalidate cached lines without losing multiline text or empty lines', () => {
  const { page, counts } = fixture();
  const code = block('/* first\n\nlast */\nconst x = `one\ntwo`;\n\n');
  const first = page.getCodeLines(code);
  assert.equal(highlightedText(first), code.code);
  assert.equal(page.codeLineCount(code), code.code.split('\n').length);
  assert.equal(page.visibleHighlightLines(code, 0).length, page.codeLineCount(code));
  code.code = code.code.replace('first', 'other'); // Same length is still a new revision.
  const replaced = page.getCodeLines(code);
  assert.notEqual(replaced, first);
  assert.equal(highlightedText(replaced), code.code);
  code.lang = 'unknown-language';
  const changedLanguage = page.getCodeLines(code);
  assert.notEqual(changedLanguage, replaced);
  assert.equal(highlightedText(changedLanguage), code.code);
  assert.deepEqual(counts, { tokenize: 3, split: 3 });
  const plain = block('one\n\n', '');
  assert.deepEqual(page.codeSourceLines(plain), ['one', '', '']);
  assert.deepEqual(page.getCodeLines(plain), []);
  assert.deepEqual(counts, { tokenize: 3, split: 3 });
});

test('same code block keeps explicit expansion and collapse while its source streams', () => {
  const { page } = fixture();
  const code = block('const n = 1;\n'.repeat(12));
  assert.equal(page.codeIsCollapsed(code, 0), true);
  assert.equal(page.visibleHighlightLines(code, 0).length, 10);
  page.toggleCodeExpanded(code, 0);
  assert.equal(page.codeIsCollapsed(code, 0), false);
  code.code += 'const next = 2;\n';
  assert.equal(page.codeIsCollapsed(code, 0), false);
  assert.equal(page.visibleHighlightLines(code, 0).length, page.codeLineCount(code));
  page.toggleCodeExpanded(code, 0);
  code.code += 'const last = 3;\n';
  assert.equal(page.codeIsCollapsed(code, 0), true);
  assert.equal(page.visibleHighlightLines(code, 0).length, 10);
});

test('a mounted block retains only its current prepared revision', () => {
  const { page, counts } = fixture();
  const first = block('const first = 1;\n');
  page.getCodeLines(first);
  page.getCodeLines(block('const second = 2;\n'));
  // Returning to an old source must prepare it again: old streaming revisions are released.
  page.getCodeLines(first);
  assert.deepEqual(counts, { tokenize: 3, split: 3 });
});
