const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const loadProjection = () => {
  const file = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl/NovelSettingProposalProjection.ets');
  const exports = {};
  vm.runInNewContext(ts.transpileModule(fs.readFileSync(file, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, { exports, require: () => ({}), Array }, { filename: file });
  return exports;
};
const { settingProposalsForMessage, orphanSettingProposals } = loadProjection();
const message = (id, uiId = id) => ({ id, uiMessage: { id: uiId } });
const proposals = [
  { id: 'pending', sourceMessageId: 'source', status: 'pending' },
  { id: 'accepted', sourceMessageId: 'source', status: 'accepted' },
  { id: 'rejected', sourceMessageId: 'source', status: 'rejected' },
  { id: 'later', sourceMessageId: 'later-source', status: 'pending' },
  { id: 'orphan', sourceMessageId: 'gone', status: 'accepted' },
];
const ids = (items) => Array.from(items, (item) => item.id);

test('all durable proposal states stay at their source in stored order', () => {
  assert.deepEqual(ids(settingProposalsForMessage(proposals, message('source'))), ['pending', 'accepted', 'rejected']);
  assert.deepEqual(ids(settingProposalsForMessage(proposals, message('other'))), []);
});

test('orphan fallback uses full history, so sliced active-history sources do not jump to the tail', () => {
  const fullHistory = [message('source'), message('later-source')];
  assert.deepEqual(ids(orphanSettingProposals(proposals, fullHistory)), ['orphan']);
});

test('source IDs match persisted UI messages as well as NovelMessage IDs', () => {
  assert.deepEqual(ids(settingProposalsForMessage(proposals, message('novel-source', 'source'))), ['pending', 'accepted', 'rejected']);
  assert.deepEqual(ids(orphanSettingProposals(proposals, [message('novel-source', 'source'), message('later-source')])), ['orphan']);
});
