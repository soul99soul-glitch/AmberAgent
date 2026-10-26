const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('../../../chat/node_modules/typescript');
const source = fs.readFileSync(path.resolve(__dirname,
  '../main/ets/platform_impl/chat/ChatRunDetailsSession.ets'), 'utf8');
const compiled = ts.transpileModule(source.replace(/^import[\s\S]*?;\n/gm, '')
  .replace('@Observed\n', '').replace(/export /g, '') + '\nglobalThis.Session = ChatRunDetailsSession;', {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None }, reportDiagnostics: true,
});
assert.equal(compiled.diagnostics.length, 0);
function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
const flush = () => new Promise(resolve => setImmediate(resolve));
function fixture(overrides = {}) {
  let hook = null, unbound = 0, snapshot = null, sending = false;
  const commands = [], hashReads = [], activityReads = [];
  const conversation = { id: 'chat', messages: [] };
  const context = vm.createContext({
    hilog: { warn() {} },
    getWebMountSession: () => ({ bindHooks(next) {
      hook = next;
      return { unbind() { unbound++; } };
    } }),
    currentMessages: conv => conv.messages,
    currentJevApprovalSubjectHash: (conv, id, locator) => {
      hashReads.push([conv, id, locator]); return conv.hash;
    },
    deriveSandboxActivities: (conv, busy, retry) => { activityReads.push([conv, busy, retry]); return conv.activities || []; },
    mergeSandboxTimeline: (activities, live) => live ? [...activities, live] : activities,
    withStepProgress: live => live,
    idleSandboxActivity: conv => ({ toolName: 'agent_idle', conversationId: conv.id }),
    isActiveOperation: activity => activity.status === 'running',
  });
  vm.runInContext(compiled.outputText, context);
  const session = new context.Session();
  const bindings = {
    currentConversation: () => conversation,
    currentStreamingTail: () => [], currentRegenTail: () => null,
    sending: () => sending, error() {}, stop() { commands.push('stop'); },
    submitVerdict: command => { commands.push(command); return true; },
    loadSubAgentManager: () => Promise.reject(new Error('unexpected manager call')),
    readSetting: () => Promise.resolve(null),
    runSnapshot: () => snapshot,
    ...overrides,
  };
  session.start(bindings);
  return { session, conversation, commands, hashReads, activityReads,
    setSnapshot: value => { snapshot = value; }, setSending: value => { sending = value; },
    hook: () => hook, unbound: () => unbound,
  };
}
for (const action of ['closeSubAgentSheet', 'hide', 'dispose']) {
  test(`SubAgent late manager cannot reopen after ${action}`, async () => {
    const pending = deferred(); let subscriptions = 0;
    const f = fixture({ loadSubAgentManager: () => pending.promise });
    const opening = f.session.openSubAgentSheet('run', []);
    f.session[action]();
    pending.resolve({ subscribeLiveText() { subscriptions++; return () => {}; }, snapshot: () => null });
    await opening;
    assert.equal(f.session.subAgentSheetOpen, false);
    assert.equal(subscriptions, 0);
  });
}
test('reopening SubAgent releases previous subscription and rejects its late delivery', async () => {
  const listeners = new Map(), released = [];
  const manager = { subscribeLiveText(id, listener) {
    listeners.set(id, listener); return () => released.push(id);
  }, snapshot: id => ({ displayText: id }) };
  const f = fixture({ loadSubAgentManager: () => Promise.resolve(manager) });
  await f.session.openSubAgentSheet('old', []);
  await f.session.openSubAgentSheet('new', []);
  listeners.get('old')('stale');
  assert.equal(f.session.subAgentLiveText, 'new');
  assert.deepEqual(released, ['old']);
  f.session.hide(); listeners.get('new')('hidden');
  assert.deepEqual(released, ['old', 'new']);
  assert.equal(f.session.subAgentLiveText, 'new');
});
test('WebMount hook lifetime belongs to session and sheet keeps offscreen host detached', () => {
  const f = fixture(); const first = f.hook();
  first.onMountNeeded(); assert.equal(f.session.webmountHostVisible, true);
  f.session.openWebmountSheet(); first.onMountNeeded();
  assert.equal(f.session.webmountHostVisible, false);
  assert.equal(f.session.webmountSheetOpen, true);
  f.session.webmountSheetDidDisappear();
  assert.equal(f.session.webmountHostVisible, true);
  f.session.hide(); const count = f.unbound();
  f.session.webmountHostVisible = false; first.onMountNeeded();
  assert.equal(f.session.webmountHostVisible, false);
  f.session.show(); assert.equal(f.unbound(), count);
  f.hook().onMountNeeded(); assert.equal(f.session.webmountHostVisible, true);
  f.session.dispose(); assert.equal(f.unbound(), count + 1);
});
test('tool preview and JEV hash re-resolve canonical locator on every read', () => {
  const f = fixture();
  const old = { type: 'tool', toolCallId: 'tool', approvalState: 'pending' };
  const locator = { messageId: 'message', partIndex: 0 };
  f.session.openToolPreview(old, locator);
  const changed = { ...old, approvalState: 'approved' };
  const canonical = { id: 'chat', hash: 'first', messages: [{ id: 'message', parts: [changed] }] };
  f.setSnapshot({ conversation: canonical });
  assert.equal(f.session.currentToolPreviewPart(), changed);
  assert.equal(f.session.jevSubjectHash(old, locator), 'first');
  const newest = { ...changed, approvalState: 'denied' };
  canonical.messages[0].parts = [newest]; canonical.hash = 'second';
  assert.equal(f.session.currentToolPreviewPart(), newest);
  assert.equal(f.session.jevSubjectHash(old, locator), 'second');
  assert.equal(f.hashReads.length, 2);
  f.setSnapshot({ conversationDeleted: true, conversation: null });
  assert.equal(f.session.jevSubjectHash(old, locator), '');
});
test('approval, deny reason and answer use narrow guarded dispatch and preserve locator', () => {
  const f = fixture(); const locator = { messageId: 'message', partIndex: 2 };
  f.session.handleToolApproval('tool', true, '', locator);
  f.session.openDenyDialog('tool', locator); f.session.denyReasonText = 'reason';
  f.session.confirmDenyDialog();
  f.session.handleToolAnswer('tool', 'answer', locator);
  assert.deepEqual(JSON.parse(JSON.stringify(f.commands)), [
    { kind: 'verdict', toolCallId: 'tool', verdict: { kind: 'approved' }, locator },
    { kind: 'verdict', toolCallId: 'tool', verdict: { kind: 'denied', reason: 'reason' }, locator },
    { kind: 'verdict', toolCallId: 'tool', verdict: { kind: 'answered', answer: 'answer' }, locator },
  ]);
  assert.equal(f.session.denyDialogOpen, false);
  assert.equal(f.session.denyReasonText, 'reason');
  f.setSending(true); f.session.handleToolApproval('tool', true);
  f.session.dispose(); f.setSending(false); f.session.handleToolAnswer('tool', 'late');
  assert.equal(f.commands.length, 3);
});
test('snapshot revision updates canonical sandbox data without owning another conversation', () => {
  const f = fixture(); const active = { status: 'running', toolCallId: 'tool', conversationId: 'chat' };
  const canonical = { id: 'chat', activities: [active] };
  f.setSnapshot({ conversation: canonical });
  f.session.applyRunSnapshot({ liveActivity: null, jsSandboxNeeded: true, retryStatus: 'retry' });
  f.session.setOperationPreviewMode('auto');
  const revision = f.session.snapshotVersion;
  assert.equal(f.session.sandboxTimeline()[0], active);
  assert.equal(f.activityReads[0][0], canonical);
  assert.equal(f.activityReads[0][2], 'retry');
  f.session.applyRunSnapshot({ liveActivity: active, jsSandboxNeeded: false, retryStatus: '' });
  assert.equal(f.session.snapshotVersion, revision + 1);
  assert.equal(f.session.jsSandboxNeeded, false);
  f.session.setOperationPreviewMode('hidden'); assert.equal(f.session.sandboxTimeline().length, 0);
  f.session.setOperationPreviewMode('always');
  canonical.activities = []; f.session.liveActivity = null;
  assert.equal(f.session.sandboxTimeline()[0].toolName, 'agent_idle');
});
