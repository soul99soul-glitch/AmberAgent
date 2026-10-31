// H4.3 数据保留:会话删除后的清理语义。
//
// 覆盖的是「删了会话之后还留着什么」这一类隐私/空间问题:
//   1. composer 输入草稿(chat_input_draft/<id>)必须随会话消失,否则已删除会话的
//      用户文字会无限期留在 Preferences 里,重建同 id 会话时还会被读回输入框。
//   2. 生成图片目录 chat_images/<id>/ 必须整目录删除(Android FilesManager:481-486),
//      连同 managed_files 登记行;否则残留成孤儿文件与不存在的登记行。
//   3. 上传附件仍走原有的「被其他会话引用则保留」判定,不因新增清理而误删。
//
// 用真实 ManagedFileStore.ets 跑,只替换 fileIo/AbilityKit/chat-domain 端口。

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');

const storePath = path.resolve(__dirname, '../main/ets/platform_impl/ManagedFileStore.ets');

// 内存文件系统:只实现本文件用到的同步调用。目录需显式登记
// (accessSync/statSync 用它判断是否为目录),文件的多级父目录由 addAncestors 补齐。
function memoryFileSystem(initial = {}) {
  const files = new Map(Object.entries(initial)); // path -> content
  const dirs = new Set(['/sandbox']);
  const deleted = [];
  const addAncestors = p => {
    let parent = path.posix.dirname(p);
    while (parent !== '/' && parent !== '.') {
      dirs.add(parent);
      parent = path.posix.dirname(parent);
    }
  };
  for (const key of files.keys()) addAncestors(key);
  const fileIo = {
    accessSync: p => files.has(p) || dirs.has(p),
    statSync: p => ({ isDirectory: () => dirs.has(p) }),
    listFileSync: p => {
      const prefix = `${p}/`;
      const names = new Set();
      for (const key of [...files.keys(), ...dirs]) {
        if (!key.startsWith(prefix)) continue;
        names.add(key.slice(prefix.length).split('/')[0]);
      }
      return [...names];
    },
    unlinkSync: p => { deleted.push(p); files.delete(p); },
    rmdirSync: p => { deleted.push(`${p}/`); dirs.delete(p); },
  };
  return {
    fileIo, deleted,
    addDir: p => dirs.add(p),
    addFile: (p, content = '') => { files.set(p, content); addAncestors(p); },
    has: p => files.has(p),
    files,
  };
}

function fixture(options = {}) {
  const fsMock = memoryFileSystem(options.files || {});
  const kv = {
    values: new Map(options.kv || []),
    puts: [],
    deletes: [],
    async get(key) { return this.values.has(key) ? this.values.get(key) : null; },
    async put(key, value) { this.values.set(key, value); this.puts.push([key, value]); },
    async delete(key) { this.deletes.push(key); this.values.delete(key); },
    async allEntries() { return [...this.values].map(([key, value]) => ({ key, value })); },
  };
  const registryRows = (options.rows || []).map(row => ({ ...row }));
  const registryDeletes = [];
  const filesRepository = {
    insert: async file => file,
    update: async () => {},
    getById: async () => null,
    getByPath: async () => null,
    listByFolder: async folder => registryRows.filter(row => row.folder === folder),
    deleteById: async () => 0,
    deleteByPath: async relativePath => {
      registryDeletes.push(relativePath);
      const index = registryRows.findIndex(row => row.relativePath === relativePath);
      if (index < 0) return 0;
      registryRows.splice(index, 1);
      return 1;
    },
    deleteByFolder: async () => 0,
  };

  const output = ts.transpileModule(fs.readFileSync(storePath, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const exports = {};
  const warnings = [];
  const newRequire = name => {
    if (name === '@kit.AbilityKit') return { Context: class {} };
    if (name === '@kit.CoreFileKit') return { fileIo: fsMock.fileIo };
    if (name === '@kit.PerformanceAnalysisKit') {
      return { hilog: { warn: (_d, _t, message, ...args) => warnings.push([message, ...args]) } };
    }
    if (name === '@amber/chat-domain') {
      return {
        createFilesRepository: () => filesRepository,
        deleteChatFiles: async (deps, uris) => {
          const removed = [];
          for (const uri of uris) {
            const relativePath = deps.toRelativePath(uri);
            if (relativePath === null) continue;
            if (await deps.fileExists(relativePath)) await deps.deleteFile(relativePath);
            removed.push(relativePath);
          }
          for (const relativePath of removed) await deps.deleteByPath(relativePath);
          return removed;
        },
        conversationFileUris: conversation => {
          const uris = [];
          for (const node of conversation.messageNodes || []) {
            for (const message of node.messages || []) {
              for (const part of message.parts || []) {
                if (['image', 'document', 'video', 'audio'].includes(part.type)
                  && typeof part.url === 'string' && part.url.startsWith('file://')) uris.push(part.url);
              }
            }
          }
          return uris;
        },
        clearPendingQueueStorage: async (store, id) => { await store.delete(`pending-queue/${id}`); },
        FILE_FOLDERS_UPLOAD: 'upload',
        FILE_FOLDERS_SKILLS: 'skills',
        FILE_FOLDERS_CHAT_IMAGES: 'chat_images',
        FILE_FOLDERS_IMAGES: 'images',
      };
    }
    if (name.endsWith('FavoriteStore.ets')) {
      return { removeFavoritesOfConversation: async id => { favorites.push(id); } };
    }
    if (name.endsWith('EntryCompactStore.ets')) {
      return { deleteConversationCompactData: async (store, id) => {
        compactCleared.push(id);
        await store.put(`compacts/${id}.deleted`, '{"deletedAt":1}');
        await store.delete(`compacts/${id}`);
        await store.delete(`compacts/${id}.events`);
      } };
    }
    if (name.endsWith('ConversationRecapSupport.ets')) {
      return { getConversationRecapService: () => ({ invalidate: async id => { recapsInvalidated.push(id); } }) };
    }
    if (name.endsWith('SmartUiDraftSupport.ets')) {
      return { getChatSmartUiDraftStore: () => ({
        cleanupConversation: async id => { smartUiCleared.push(id); return { available: true, error: null }; },
      }) };
    }
    if (name.endsWith('AtomicJsonFile.ets')) {
      return {
        writeTextFileAtomic: async () => {},
        readGuardedJson: async () => ({ error: '', shape: { nextId: 1, rows: [] } }),
      };
    }
    throw new Error(`Unexpected import: ${name}`);
  };
  const favorites = [];
  const compactCleared = [];
  const recapsInvalidated = [];
  const smartUiCleared = [];
  new Function('require', 'exports', 'AppStorage', 'module', output)(
    newRequire, exports, { get: () => ({ filesDir: '/sandbox' }) }, { exports });

  return {
    exports, kv, fsMock, registryRows, registryDeletes, warnings,
    favorites, compactCleared, recapsInvalidated, smartUiCleared,
    filesRepository,
  };
}

const conversationOf = (id, uris = []) => ({
  id, assistantId: 'a', title: 'T', createAt: '2026-01-01T00:00:00Z', updateAt: '2026-01-01T00:00:00Z',
  chatSuggestions: [], isPinned: false, autoApproveToolCalls: false,
  messageNodes: uris.map((url, i) => ({
    id: `n${i}`, selectIndex: 0,
    messages: [{ id: `m${i}`, role: 'user', createdAt: '2026-01-01T00:00:00Z',
      parts: [{ type: 'image', url }] }],
  })),
});

const repositoryOf = conversations => ({
  list: async () => conversations.map(c => ({ id: c.id, title: c.title, updateAt: c.updateAt })),
  getById: async id => conversations.find(c => c.id === id) || null,
});

test('deleting a conversation also removes its composer input draft, suggestions and compact_done keys', async () => {
  const f = fixture({
    kv: [
      ['chat_input_draft/conv-1', '用户没发出去的草稿'],
      ['suggestions/conv-1', '["建议 1"]'],
      ['compact_done/conv-1', '1'],
    ],
  });
  await f.exports.cleanupDeletedConversation(conversationOf('conv-1'), repositoryOf([]), f.kv);

  assert.equal(f.kv.values.has('chat_input_draft/conv-1'), false,
    'a deleted conversation must not leave the typed draft behind');
  assert.equal(f.kv.values.has('suggestions/conv-1'), false,
    'a deleted conversation must not leave suggestions behind');
  assert.equal(f.kv.values.has('compact_done/conv-1'), false,
    'a deleted conversation must not leave compact_done behind');
  assert.ok(f.kv.deletes.includes('chat_input_draft/conv-1'));
  assert.ok(f.kv.deletes.includes('suggestions/conv-1'));
  assert.ok(f.kv.deletes.includes('compact_done/conv-1'));
});

test('deleting a conversation removes its generated image directory and registry rows', async () => {
  const f = fixture({
    files: {
      '/sandbox/chat_images/conv-1/1.png': 'png-1',
      '/sandbox/chat_images/conv-1/2.png': 'png-2',
      '/sandbox/chat_images/conv-2/keep.png': 'keep',
    },
    rows: [
      { id: 1, folder: 'chat_images', relativePath: 'chat_images/conv-1/1.png', displayName: '1.png',
        mimeType: 'image/png', sizeBytes: 5, createdAt: 1, updatedAt: 1 },
      { id: 2, folder: 'chat_images', relativePath: 'chat_images/conv-1/2.png', displayName: '2.png',
        mimeType: 'image/png', sizeBytes: 5, createdAt: 2, updatedAt: 2 },
      { id: 3, folder: 'chat_images', relativePath: 'chat_images/conv-2/keep.png', displayName: 'keep.png',
        mimeType: 'image/png', sizeBytes: 4, createdAt: 3, updatedAt: 3 },
    ],
  });
  await f.exports.cleanupDeletedConversation(conversationOf('conv-1'), repositoryOf([]), f.kv);

  assert.equal(f.fsMock.has('/sandbox/chat_images/conv-1/1.png'), false);
  assert.equal(f.fsMock.has('/sandbox/chat_images/conv-1/2.png'), false);
  assert.equal(f.fsMock.has('/sandbox/chat_images/conv-2/keep.png'), true,
    'another conversation\'s images must survive');
  assert.deepEqual(f.registryDeletes.sort(), ['chat_images/conv-1/1.png', 'chat_images/conv-1/2.png']);
  assert.deepEqual(f.registryRows.map(row => row.relativePath), ['chat_images/conv-2/keep.png']);
});

test('a missing chat_images directory is not an error', async () => {
  const f = fixture();
  await f.exports.cleanupDeletedConversation(conversationOf('conv-3'), repositoryOf([]), f.kv);
  assert.deepEqual(f.registryDeletes, []);
  assert.equal(f.kv.values.has('chat_input_draft/conv-3'), false);
});

test('an upload still referenced by another conversation is kept', async () => {
  const uri = 'file:///sandbox/upload/shared.pdf';
  const f = fixture({ files: { '/sandbox/upload/shared.pdf': 'pdf' } });
  const other = conversationOf('conv-other', [uri]);
  await f.exports.cleanupDeletedConversation(conversationOf('conv-1', [uri]), repositoryOf([other]), f.kv);
  assert.equal(f.fsMock.has('/sandbox/upload/shared.pdf'), true,
    'a file still referenced elsewhere must not be deleted');
});

test('deletion still clears compacts, recaps, smart UI drafts and favorites', async () => {
  const f = fixture();
  await f.exports.cleanupDeletedConversation(conversationOf('conv-9'), repositoryOf([]), f.kv);
  assert.deepEqual(f.compactCleared, ['conv-9']);
  assert.deepEqual(f.recapsInvalidated, ['conv-9']);
  assert.deepEqual(f.smartUiCleared, ['conv-9']);
  assert.deepEqual(f.favorites, ['conv-9']);
  assert.equal(f.kv.values.has('compacts/conv-9.deleted'), true,
    'the deleted marker keeps late summaries from resurrecting the conversation');
});

test('SettingFilesPage.confirmConversationCleanup skips busy conversations, locks restore, forgets deleted sessions and invalidates home cache', async () => {
  const { actualPage } = require('../../../deepread/src/test/deepread_ui_fixture.cjs');
  const events = [];
  let cacheInvalidated = 0;
  let reloaded = 0;
  const toasts = [];
  const repo = {
    getById: async id => conversationOf(id),
    delete: async id => { events.push(`delete:${id}`); },
  };
  const runService = {
    isBusy: id => id === 'conv-busy',
    forgetDeletedConversation: id => { events.push(`forget:${id}`); },
  };
  const page = actualPage('pages/SettingFilesPage.ets', ['confirmConversationCleanup'], {
    getChatRepository: () => repo,
    getChatKvStore: () => ({}),
    getConversationRunService: () => runService,
    beginConversationRestore: ids => {
      events.push(`lock:${ids.join(',')}`);
      return () => { events.push(`unlock:${ids.join(',')}`); };
    },
    cleanupDeletedConversation: async conv => { events.push(`cleanup:${conv.id}`); },
    invalidateHomeConversationCache: () => { cacheInvalidated++; events.push('invalidateCache'); },
    promptAction: { showToast: t => toasts.push(t.message) },
    Promise,
  });
  page.reload = () => { reloaded++; };
  page.convCleaning = false;

  page.confirmConversationCleanup([
    { id: 'conv-busy', title: 'Busy', updateAt: '2025-01-01T00:00:00Z', isPinned: false },
    { id: 'conv-idle', title: 'Idle', updateAt: '2025-01-01T00:00:00Z', isPinned: false },
  ]);
  await new Promise(resolve => setTimeout(resolve, 30));

  assert.equal(page.convCleaning, false);
  assert.equal(cacheInvalidated, 1);
  assert.equal(reloaded, 1);
  assert.deepEqual(events, [
    'lock:conv-idle',
    'delete:conv-idle',
    'forget:conv-idle',
    'cleanup:conv-idle',
    'unlock:conv-idle',
    'invalidateCache',
  ]);
  assert.match(toasts[0] ?? '', /已清理 1\/2 个对话/);
});
