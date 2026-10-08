// Actual FavoriteStore and domain; only repository/KV ports are controlled.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const plain = value => JSON.parse(JSON.stringify(value));

function loadStore(domain, kv, repository) {
  const filename = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl/FavoriteStore.ets');
  const exports = {};
  const code = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText;
  vm.runInNewContext(code, { exports, require: name => {
    if (name === '@amber/chat-domain') return domain;
    if (name === '../di/AppContainer.ets') return { getChatKvStore: () => kv, getChatRepository: () => repository };
    throw new Error('Unexpected dependency: ' + name);
  }, Error, Promise, JSON, String, Date }, { filename });
  return exports;
}

async function fixture() {
  const domain = await import('../main/ets/index.ts');
  const kv = domain.createMemoryKeyValueStore();
  const repository = domain.createMemoryConversationRepository();
  const nodes = ['n1', 'n2'].map(id => domain.makeMessageNode([domain.makeAssistantMessage('回答 ' + id)], 0, id));
  await repository.save(domain.makeConversation('A', nodes, { title: '来源标题' }));
  await repository.save(domain.makeConversation('B', nodes, { title: '另一个会话' }));
  const legacy = { id: 'legacy', title: '旧标题', content: '旧文本', conversationId: 'B', createdAt: 10 };
  await kv.put('favorites_json', JSON.stringify([legacy]));
  return { domain, kv, repository, legacy, store: loadStore(domain, kv, repository) };
}

test('node identity deduplicates concurrent saves, persists through reload, cancels and preserves legacy favorites', async () => {
  const { domain, kv, repository, store, legacy } = await fixture();
  await Promise.all([store.setNodeFavorite('A', 'n1', true), store.setNodeFavorite('A', 'n1', true),
    store.setNodeFavorite('A', 'n2', true)]);
  const reloaded = loadStore(domain, kv, repository);
  const favorites = await reloaded.listFavorites();
  assert.equal(favorites.length, 3);
  const saved = favorites.find(item => item.nodeId === 'n1');
  assert.equal(saved.id, 'node:A:n1'); assert.equal(saved.title, '来源标题'); assert.equal(saved.content, '回答 n1');
  assert.deepEqual(plain(await reloaded.listFavoriteNodeIds('A')).sort(), ['n1', 'n2']);
  const old = favorites.find(item => item.id === 'legacy');
  assert.equal(old.content, legacy.content); assert.equal(old.createdAt, legacy.createdAt); assert.equal(old.nodeId, '');
  await reloaded.addFavorite({ ...saved, id: 'attempted-duplicate' });
  assert.equal((await reloaded.listFavorites()).length, 3);
  await reloaded.setNodeFavorite('A', 'n1', false);
  await reloaded.setNodeFavorite('A', 'n1', false);
  assert.deepEqual(plain(await reloaded.listFavoriteNodeIds('A')), ['n2']);
  await reloaded.removeFavorite('node:A:n2');
  assert.equal((await reloaded.listFavorites()).length, 1);
  await reloaded.setNodeFavorite('A', 'n1', true);
  await reloaded.removeFavoritesOfConversation('A');
  assert.equal((await reloaded.listFavorites())[0].id, 'legacy');
});

test('source lookup targets the node, legacy opens only its conversation, and missing sources fail visibly', async () => {
  const { domain, repository, store } = await fixture();
  await store.setNodeFavorite('A', 'n1', true);
  const favorites = await store.listFavorites();
  const node = favorites.find(item => item.nodeId === 'n1');
  assert.deepEqual(plain(await store.resolveFavoriteSource(node)), { conversationId: 'A', nodeId: 'n1' });
  assert.deepEqual(plain(await store.resolveFavoriteSource(favorites.find(item => item.id === 'legacy'))),
    { conversationId: 'B', nodeId: '' });
  const conversation = await repository.getById('A');
  await repository.save(domain.patchConversation(conversation, { messageNodes: conversation.messageNodes.slice(1) }));
  await assert.rejects(store.resolveFavoriteSource(node), /原消息已删除/);
  await store.setNodeFavorite('A', 'n1', false);
  await assert.rejects(store.setNodeFavorite('A', 'n1', true), /待收藏消息已不存在/);
  await store.setNodeFavorite('A', 'n2', true);
  await repository.delete('A');
  await assert.rejects(store.resolveFavoriteSource(node), /来源会话已不存在/);
  // An orphan reference can still be cancelled without reviving its source.
  await store.setNodeFavorite('A', 'n2', false);
  assert.equal((await store.listFavorites()).length, 1);
});

test('failed writes do not pretend to select a node, and corrupt legacy data is not overwritten', async () => {
  const { domain, kv, repository } = await fixture();
  let failure = true;
  const controlledKv = { ...kv, put: async (key, value) => {
    if (failure) throw new Error('controlled write failure');
    await kv.put(key, value);
  } };
  const store = loadStore(domain, controlledKv, repository);
  await assert.rejects(store.setNodeFavorite('A', 'n1', true), /controlled write failure/);
  assert.deepEqual(plain(await store.listFavoriteNodeIds('A')), []);
  failure = false;
  await store.setNodeFavorite('A', 'n1', true);
  assert.deepEqual(plain(await store.listFavoriteNodeIds('A')), ['n1']);
  const raw = '{ broken favorites';
  await kv.put('favorites_json', raw);
  await assert.rejects(store.setNodeFavorite('A', 'n1', false), /收藏数据损坏/);
  assert.equal(await kv.get('favorites_json'), raw);
});
