const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { actualPage, entryRoot, loadPureModule } = require('../../../deepread/src/test/deepread_ui_fixture.cjs');
const { tokenizeCode } = loadPureModule(path.resolve(__dirname, '../../../chat/src/main/ets/chat/code_highlight.ts'));

function readEntryFile(relPath) {
  return fs.readFileSync(path.join(entryRoot, relPath), 'utf8');
}

test('H5.1/H5.3: cards, approval views, and settings pages use theme & font tokens without raw hex/font regressions', () => {
  const providerSettings = readEntryFile('pages/ChatProviderSettingsPage.ets');
  assert.match(providerSettings, /\bERROR\b[\s\S]*?from '\.\.\/design\/tokens\.ets'/);
  assert.match(providerSettings, /TEXT_STYLES\.secondary\.size/);

  const chatTools = readEntryFile('components/ChatMessageTools.ets');
  assert.doesNotMatch(chatTools, /'JetBrainsMono'/);
  assert.match(chatTools, /FONT_MONO/);
  assert.match(chatTools, /\.padding\(PAD_ITEM\)/);
  assert.match(chatTools, /\.borderRadius\(RADIUS\)/);
  assert.match(chatTools, /sshApprovalCommand\(this\.part\)[\s\S]*?\.wordBreak\(WordBreak\.BREAK_ALL\)/);

  const previewSheet = readEntryFile('components/ChatMessageToolPreviewSheet.ets');
  assert.doesNotMatch(previewSheet, /'JetBrainsMono'/);
  assert.match(previewSheet, /FONT_MONO/);
  assert.match(previewSheet, /\.constraintSize\(\{\s*minHeight:\s*56\s*\}\)/);

  const providerCard = readEntryFile('components/ProviderCard.ets');
  assert.doesNotMatch(providerCard, /#FFF5F0|#C0392B/);
  assert.match(providerCard, /ACCENT_LIGHT/);
  assert.match(providerCard, /\.fontColor\(ERROR\)/);
  assert.match(providerCard, /\.constraintSize\(\{\s*minHeight:\s*44\s*\}\)/);

  const askUser = readEntryFile('components/ChatMessageAskUserStep.ets');
  assert.doesNotMatch(askUser, /'JetBrainsMono'|#FFFFFF/);
  assert.match(askUser, /ACCENT_INK/);
  assert.match(askUser, /FONT_MONO/);
  assert.match(askUser, /\.constraintSize\(\{\s*minHeight:\s*64\s*\}\)/);

  const errorCard = readEntryFile('components/ui/ErrorCard.ets');
  assert.match(errorCard, /\.constraintSize\(\{\s*minWidth:\s*44,\s*minHeight:\s*44\s*\}\)/);
  assert.match(errorCard, /\.accessibilityText\('关闭错误提示'\)/);
  assert.match(errorCard, /\.padding\(PAD_ITEM\)/);
});

test('H5.1: capsules and settings inputs use minHeight constraints and ellipsis truncation for large Font Scale', () => {
  const capsule = readEntryFile('components/AgentToolCapsule.ets');
  assert.doesNotMatch(capsule, /\.height\(30\)/);
  assert.match(capsule, /\.constraintSize\(\{\s*minHeight:\s*30/);
  assert.match(capsule, /\.constraintSize\(\{\s*minHeight:\s*44\s*\}\)/);

  const sshPage = readEntryFile('pages/SettingSSHProfilesPage.ets');
  assert.doesNotMatch(sshPage, /\.height\(52\)/);
  assert.match(sshPage, /Text\(profile\.name\)[\s\S]*?\.maxLines\(1\)\.textOverflow\(\{\s*overflow:\s*TextOverflow\.Ellipsis\s*\}\)/);
  assert.match(sshPage, /Text\(assistant\.name\.length > 0 \? assistant\.name : '未命名助手'\)[\s\S]*?\.maxLines\(1\)\.textOverflow\(\{\s*overflow:\s*TextOverflow\.Ellipsis\s*\}\)/);

  const backupPage = readEntryFile('pages/SettingBackupPage.ets');
  assert.match(backupPage, /RestorePreview[\s\S]*?\.maxLines\(2\)\.textOverflow\(\{\s*overflow:\s*TextOverflow\.Ellipsis\s*\}\)/);
  assert.match(backupPage, /TextInput\(\{\s*text:\s*this\.davUrl[\s\S]*?\.constraintSize\(\{\s*minHeight:\s*44\s*\}\)/);
});

test('H5.2: MarkdownBlockView caches collapsed code slices and per-line ForEach keys across streaming reads', () => {
  const names = [
    'codeRevision', 'getCodeLines', 'splitTokensByLine', 'codeSourceLines',
    'codeLineCount', 'codeKey', 'codeIsCollapsed', 'visibleCodeLineCap',
    'visibleHighlightLines', 'visiblePlainLines', 'codeLineKey',
  ];
  const page = actualPage('components/MarkdownBlockView.ets', names, {
    tokenizeCode,
    CODE_COLLAPSE_THRESHOLD: 10,
  });
  Object.assign(page, {
    cachedCode: null,
    viewState: null,
    streamKey: 'message#0',
    slot: { key: 'md-0' },
    codeExpanded: {},
    codeBlockAutoCollapse: true,
    showLineNumbers: true,
  });

  const block = {
    kind: 'code_block',
    lang: 'typescript',
    code: Array.from({ length: 24 }, (_, idx) => `const value${idx} = ${idx};`).join('\n'),
  };

  const firstHighlightSlice = page.visibleHighlightLines(block, 0);
  const secondHighlightSlice = page.visibleHighlightLines(block, 0);
  assert.equal(firstHighlightSlice.length, 10);
  assert.equal(firstHighlightSlice, secondHighlightSlice, 'collapsed highlight slice must be reused without re-slicing');

  const firstPlainSlice = page.visiblePlainLines(block, 0);
  const secondPlainSlice = page.visiblePlainLines(block, 0);
  assert.equal(firstPlainSlice.length, 10);
  assert.equal(firstPlainSlice, secondPlainSlice, 'collapsed plain slice must be reused without re-slicing');

  const key0First = page.codeLineKey(block, 0, firstHighlightSlice[0]);
  const key0Second = page.codeLineKey(block, 0, firstHighlightSlice[0]);
  assert.equal(key0First, key0Second);
  assert.match(key0First, /^codeline-0\|/);
});

test('H5.2: HomeListPane memoizes previewFilter so per-row hideRowLine and isLast do not re-filter O(N^2)', () => {
  const page = actualPage('components/HomeListPane.ets', ['previewFilter', 'hideRowLine', 'isActive'], {});
  Object.assign(page, {
    searchQuery: 'amber',
    twoPane: false,
    activeId: '',
    lastOpenedId: 'c2',
    cachedFilterInput: [],
    cachedFilterQuery: '',
    cachedFilterResult: [],
    collection: {
      items: [
        { id: 'c1', title: 'Amber Release Plan', updateAt: '2026-10-31T10:00:00Z', isPinned: false },
        { id: 'c2', title: 'Amber Harmony UI', updateAt: '2026-10-31T09:00:00Z', isPinned: false },
        { id: 'c3', title: 'Other Topic', updateAt: '2026-10-31T08:00:00Z', isPinned: false },
      ],
    },
  });

  const first = page.previewFilter(page.collection.items);
  const second = page.previewFilter(page.collection.items);
  assert.equal(first.length, 2);
  assert.equal(first, second, 'identical (items, query) must return memoized array instance');
  assert.equal(page.hideRowLine(0), true);
  assert.equal(page.hideRowLine(1), true);
});
