const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('../../../chat/node_modules/typescript');

test('page writes and deletion share the same pending draft queue', async () => {
  const { createMemoryKeyValueStore } = await import('../../../chat/src/main/ets/chat/kv_store.ts');
  const { createSmartUiDraftStore } = await import('../../../chat/src/main/ets/chat/smart_ui/store.ts');
  const { parseSmartUi } = await import('../../../chat/src/main/ets/chat/smart_ui/protocol.ts');
  const source = fs.readFileSync(path.resolve(__dirname,
    '../main/ets/platform_impl/SmartUiDraftSupport.ets'), 'utf8');
  const exports = {};
  vm.runInNewContext(ts.transpileModule(source, { compilerOptions: {
    module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022,
  } }).outputText, { exports, require: () => ({ createSmartUiDraftStore }) });
  const kv = createMemoryKeyValueStore();
  const pageStore = exports.getChatSmartUiDraftStore(kv);
  const deleteStore = exports.getChatSmartUiDraftStore(kv);
  assert.equal(pageStore, deleteStore);
  const doc = parseSmartUi('{"op":"state","id":"selected","initial":"a"}\n' +
    '{"op":"node","id":"root","type":"column"}\n{"op":"end"}\n', 'panel', true);
  const origin = { conversationId: 'conv', nodeId: 'node', messageId: 'message',
    blockId: 'panel', blockIndex: 0, sourceStart: 0 };
  const pending = pageStore.saveDebounced(doc, origin, [{ id: 'selected', value: 'b' }], 1, 1000);
  await deleteStore.cleanupConversation('conv');
  assert.equal((await pending).reason, 'cancelled');
  await pageStore.flush();
  assert.equal(kv.entries.size, 0);
});
