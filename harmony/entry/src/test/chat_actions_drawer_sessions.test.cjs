// Execute the production owner methods; UI/SDK integration remains covered by the integration gate.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');

function loadOwner(filename, dependencies) {
  const source = fs.readFileSync(path.resolve(__dirname, '../main/ets/platform_impl/chat', filename), 'utf8');
  const js = ts.transpileModule(source.replace(/^@Observed\s*$/gm, ''), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText;
  const exports = {};
  new Function('exports', 'require', 'AlertDialog', js)(exports, name => {
    if (name in dependencies) return dependencies[name];
    throw new Error(`Unexpected owner dependency ${name}`);
  }, dependencies.AlertDialog);
  return exports;
}
function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
const settle = () => new Promise(resolve => setImmediate(resolve));
function actionsFixture() {
  const events = [], errors = [], confirmations = [];
  const conv = { id: 'c1', messageNodes: [] };
  let current = conv, busy = false, canonicalRow = { nodeId: 'n1', message: { id: 'm1', role: 'user', parts: [] } };
  const exports = loadOwner('ChatMessageActionsSession.ets', {
    '@kit.ArkUI': { promptAction: { showToast: value => events.push(['toast', value.message]) } },
    '@kit.BasicServicesKit': { pasteboard: { getSystemPasteboard: () => ({ setDataSync: value => events.push(['copy', value]) }), createData: (_mime, text) => text } },
    '@kit.ShareKit': { systemShare: {} }, '@kit.ArkData': { uniformTypeDescriptor: {} },
    '@kit.PerformanceAnalysisKit': { hilog: { warn() {} } }, '../../design/tokens.ets': { ERROR: 'red' },
    '@amber/chat-domain': {
      messageCopyText: message => message.id, toText: message => message.id,
      nodeCurrentMessage: node => node.message,
      deleteMessage: (latest, id) => { events.push(['delete', latest.id, id]); return latest; },
      forkConversation: (latest, id) => { events.push(['fork', latest.id, id]); return { id: 'fork1' }; },
      copyValidCompactsToConversation: async () => { events.push(['copyCompacts']); }, newId: () => 'new',
    },
    './ChatCompactionSupport.ets': { invalidateChatConversationCompacts: async (_store, id, reason) => { events.push(['invalidate', id, reason]); } },
    AlertDialog: { show: value => confirmations.push(value) },
  });
  const deps = {
    repository: { getById: async () => conv, save: async () => events.push(['save']) }, compactStore: {},
    runService: { isBusy: () => busy, mutateStoredConversation: async (_id, transform) => transform(current) },
    currentConversation: () => current, resolveRow: () => canonicalRow, isSending: () => busy,
    ensureFullConversation: async () => events.push(['ensureFull']),
    listFavoriteNodeIds: async () => [], setNodeFavorite: async (...args) => events.push(['favorite', ...args]),
    onEdit: row => events.push(['edit', row.message.id]), onRegenerate: id => events.push(['regen', id]),
    onForkSaved: id => events.push(['forkSaved', id]), onLocateArchive: (...args) => events.push(['locate', ...args]),
    onMessageCleanup: (...args) => events.push(['cleanup', ...args]), onError: value => errors.push(value), getShareContext: () => ({}),
  };
  const session = new exports.ChatMessageActionsSession();
  session.start(deps);
  return { session, deps, events, errors, confirmations, row: canonicalRow,
    setRow: value => { canonicalRow = value; }, setBusy: value => { busy = value; }, setCurrent: value => { current = value; } };
}
function drawerFixture() {
  const events = [], errors = [], confirmations = [];
  let busy = false;
  const exports = loadOwner('ChatConversationDrawerSession.ets', {
    '@amber/chat-domain': { patchConversation: (conv, patch) => Object.assign({}, conv, patch) },
    '../ConversationRunGuard.ets': { beginConversationRestore: ids => { events.push(['lock', ids]); return () => events.push(['release']); } },
    '../../design/tokens.ets': { ERROR: 'red' }, AlertDialog: { show: value => confirmations.push(value) },
  });
  const deps = {
    repository: { list: async () => [], search: async () => [], searchMessages: async () => [], getById: async id => ({ id }), delete: async id => events.push(['delete', id]) },
    runService: { isBusy: () => busy, forgetDeletedConversation: id => events.push(['forget', id]) }, kv: {},
    currentConversationId: () => 'c1', closeModelMenu: () => events.push(['closeModel']),
    suspendIME: () => events.push(['suspendIME']), resumeIME: () => events.push(['resumeIME']),
    onNavigate: (...args) => events.push(['navigate', ...args]), maybeAutoTitle: async () => {},
    cleanupConversation: async id => events.push(['smartCleanup', id]),
    cleanupDeletedConversation: async full => events.push(['managedCleanup', full.id]), onError: value => errors.push(value),
  };
  const session = new exports.ChatConversationDrawerSession(); session.start(deps);
  return { session, deps, events, errors, confirmations, setBusy: value => { busy = value; } };
}

test('actions resolve selected row at invocation and close retains selection until sheet disappearance', () => {
  const f = actionsFixture();
  f.session.openActionsSheet(f.row);
  f.session.closeActionsSheet();
  f.setRow({ nodeId: 'n2', message: { id: 'm2', role: 'assistant' }, isLastAssistant: true });
  f.session.editActionsSheetMessage(); f.session.regenerateActionsSheetMessage(); f.session.copyAssistantMessage();
  assert.deepEqual(f.events.slice(0, 3), [['edit', 'm2'], ['regen', 'n2'], ['copy', 'm2']]);
  assert.equal(f.session.actionsSheetIsLastAssistant(), true);
  f.session.clearActionsSheet(); assert.equal(f.session.currentActionsRow(), null);
});

test('message deletion rechecks busy in mutation and cleans source before compact invalidation', async () => {
  const f = actionsFixture(); f.session.confirmDeleteMessage(f.row);
  f.setBusy(true); f.confirmations[0].secondaryButton.action(); await settle();
  assert.match(f.errors[0], /生成中/); assert.deepEqual(f.events, []);
  f.setBusy(false); f.session.confirmDeleteMessage(f.row); f.confirmations[1].secondaryButton.action(); await settle();
  assert.deepEqual(f.events, [['delete', 'c1', 'm1'], ['cleanup', 'c1', 'm1'], ['invalidate', 'c1', 'message_deleted']]);
});

test('favorite writes coalesce per node and late responses cannot replace another conversation favorites', async () => {
  const f = actionsFixture(), pending = deferred(); let writes = 0;
  f.deps.setNodeFavorite = () => { writes++; return pending.promise; };
  f.session.toggleFavorite(f.row); f.session.toggleFavorite(f.row); assert.equal(writes, 1);
  f.setCurrent({ id: 'other' }); f.session.favoriteNodeIds = ['other-node'];
  pending.resolve(); await settle();
  assert.deepEqual(f.session.favoriteNodeIds, ['other-node']);
  assert.equal(f.events.length, 0);
});

test('fork loads full canonical before copying compacts and saving', async () => {
  const f = actionsFixture(); f.session.forkAt(f.row); await settle();
  assert.deepEqual(f.events, [['ensureFull'], ['fork', 'c1', 'm1'], ['copyCompacts'], ['save'], ['forkSaved', 'fork1']]);
});

test('archive source rejects wrong branch and hidden callback, then emits full source for valid locate', async () => {
  const f = actionsFixture();
  f.deps.repository.getById = async () => ({ id: 'c1', messageNodes: [{ id: 'n1', message: { id: 'different' } }] });
  await f.session.openArchiveSource({ conversationId: 'c1', nodeId: 'n1', messageId: 'm1' });
  assert.match(f.errors[0], /当前分支/);
  f.session.hide(); await f.session.openArchiveSource({ conversationId: 'c1', nodeId: 'missing', messageId: 'm1' });
  assert.equal(f.errors.length, 1);
  f.session.show(); f.deps.repository.getById = async () => ({ id: 'c1', messageNodes: [{ id: 'n1', message: { id: 'm1' } }] });
  await f.session.openArchiveSource({ conversationId: 'c1', nodeId: 'n1', messageId: 'm1' });
  assert.equal(f.events[0][0], 'locate'); assert.equal(f.events[0][2], 'n1'); assert.equal(f.events[0][3], 0);
});

test('drawer closes model before suspending IME and restores listener only while visible', () => {
  const f = drawerFixture(); f.session.openDrawer();
  assert.deepEqual(f.events, [['closeModel'], ['suspendIME']]);
  f.session.closeDrawer(); f.session.hide(); f.session.closeDrawer();
  assert.deepEqual(f.events, [['closeModel'], ['suspendIME'], ['resumeIME']]);
});

test('drawer ignores obsolete searches and responses after close/dispose', async () => {
  const f = drawerFixture(), old = deferred(), fresh = deferred(), oldHits = deferred(), freshHits = deferred();
  f.deps.repository.search = text => text === 'old' ? old.promise : fresh.promise;
  f.deps.repository.searchMessages = text => text === 'old' ? oldHits.promise : freshHits.promise;
  f.session.refreshDrawerConversations('old'); f.session.refreshDrawerConversations('fresh');
  fresh.resolve([{ id: 'new' }]); freshHits.resolve([{ conversationId: 'new' }]); await settle();
  old.resolve([{ id: 'old' }]); oldHits.resolve([{ conversationId: 'old' }]); await settle();
  assert.deepEqual(f.session.drawerConversations, [{ id: 'new' }]);
  assert.deepEqual(f.session.drawerMessageHits, [{ conversationId: 'new' }]);
  const late = deferred(); f.deps.repository.search = () => late.promise;
  f.session.refreshDrawerConversations('later'); f.session.closeDrawer(); late.resolve([{ id: 'late' }]); await settle();
  assert.deepEqual(f.session.drawerConversations, [{ id: 'new' }]);
  f.session.dispose();
});

test('drawer deletion guards before and after confirmation; restore lock encloses forget and cleanup order', async () => {
  const f = drawerFixture(), item = { id: 'c1', title: 'title' };
  f.setBusy(true); f.session.deleteDrawerConversation(item); assert.equal(f.confirmations.length, 0);
  f.setBusy(false); f.session.deleteDrawerConversation(item); f.setBusy(true);
  f.confirmations[0].secondaryButton.action(); await settle(); assert.deepEqual(f.events, []);
  f.setBusy(false); f.session.deleteDrawerConversation(item); f.confirmations[1].secondaryButton.action(); await settle();
  assert.deepEqual(f.events, [['lock', ['c1']], ['delete', 'c1'], ['forget', 'c1'], ['smartCleanup', 'c1'], ['managedCleanup', 'c1'], ['release'], ['navigate', 'deletedCurrent', '']]);
});

test('drawer releases restore lock on delete failure without running later cleanup', async () => {
  const f = drawerFixture(); f.deps.repository.delete = async () => { throw new Error('disk'); };
  f.session.deleteDrawerConversation({ id: 'other', title: '' }); f.confirmations[0].secondaryButton.action(); await settle();
  assert.deepEqual(f.events, [['lock', ['other']], ['release']]); assert.deepEqual(f.errors, ['disk']);
});
