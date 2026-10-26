const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
const { loadChatSmartUiSession } = require('./chat_smart_ui_fixture.cjs');
const { loadChatComposerSession } = require('./chat_composer_fixture.cjs');

function method(source, name) {
  const start = source.search(new RegExp(`^  private ${name}\\(`, 'm'));
  assert.ok(start >= 0, name);
  let end = source.indexOf('{', start) + 1;
  let depth = 1;
  while (depth && end < source.length) {
    if (source[end] === '{') depth++;
    if (source[end] === '}') depth--;
    end++;
  }
  return source.slice(start, end);
}

function productionClass(relative, names, dependencies) {
  const source = fs.readFileSync(path.join(__dirname, '../main/ets', relative), 'utf8');
  const js = ts.transpileModule(`class Subject { ${names.map(name => method(source, name)).join('\n')} } return Subject;`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022 },
  }).outputText;
  return new Function(...Object.keys(dependencies), js)(...Object.values(dependencies));
}

async function fixture(actionText = '继续比较') {
  const domain = await import('../../../chat/src/main/ets/index.ts');
  const source = { conversationId: 'conversation', nodeId: 'answer-node', messageId: 'answer',
    blockId: 'budget', blockIndex: 0, sourceStart: 0 };
  const text = '```amber-ui v=1 id=budget\n' + [
    { op: 'state', id: 'amount', initial: 50 },
    { op: 'state', id: 'note', initial: '' },
    { op: 'node', id: 'root', type: 'column' },
    { op: 'node', id: 'amount-field', parent: 'root', type: 'component', state: 'amount', label: '预算',
      component: { kind: 'numberField', min: 0, max: 100, step: 10 } },
    { op: 'node', id: 'note-field', parent: 'root', type: 'input', state: 'note', label: '补充要求' },
    { op: 'node', id: 'continue', parent: 'root', type: 'button', label: '继续', action: { type: 'draft', text: actionText } },
    { op: 'end' },
  ].map(record => JSON.stringify(record)).join('\n') + '\n```';
  const message = domain.makeUIMessage('assistant', [{ type: 'text', text, metadata: null }], { id: source.messageId });
  const document = domain.parseResponseContent(text, false)[0].ui;
  assert.equal(document.status, 'ready');
  const notices = [];
  const Page = productionClass('pages/ChatPage.ets', ['onSmartUiDraft'], {
    ...domain, promptAction: { showToast: notice => notices.push(notice.message) },
  });
  const page = new Page();
  const Session = loadChatSmartUiSession(domain);
  page.smartUi = new Session();
  const Composer = loadChatComposerSession(domain);
  page.composer = new Composer();
  const conversation = { id: source.conversationId, messageNodes: [] };
  Object.assign(page, { errorMsg: '', currentSmartUiMessages: () => [message],
    smartUiSourceContext: () => ({ conversationId: conversation.id, messages: page.currentSmartUiMessages() }) });
  page.composer.start({
    kvStore: { put: async () => {}, get: async () => null }, runService: {},
    currentConversation: () => conversation, isSending: () => false, currentQueue: () => [],
    currentCompletionNotice: () => null, setCompletionNotice() {}, error: message => { page.errorMsg = message; },
    resolveChoice: async () => null, loadOcrSeed: async () => null, probeOcrHealth: async () => ({}),
    submit: () => false, dismissKeyboard() {}, autoScrollEnabled: () => false, jumpToBottom() {}, openDeepRead() {},
    captureContinuation: text => page.smartUi.captureSmartUiContinuation(text, page.smartUiSourceContext()),
    continuationAvailable: () => page.smartUi.latestSmartUiContinuationRuntime(page.smartUiSourceContext()) !== undefined,
    continuationCurrent: capture => page.smartUi.smartUiContinuationCurrent(capture, page.smartUiSourceContext()),
    loadMentionRoles: async () => [], abilityContext: () => ({}),
  });
  page.composer.setInputText('我的原草稿');
  page.composer.pendingImages = ['file://image'];
  page.composer.pendingDocs = [{ url: 'file://document' }];
  page.smartUi.start({ load: async () => ({ status: 'missing', values: [], diagnostics: [] }),
    saveDebounced: async () => ({ saved: true }), flush: async () => {} }, message => { page.errorMsg = message; });
  page.smartUi.onSmartUiDocument('source', document, false, source, true);
  const runtime = page.smartUi.smartUiRuntime.get('source');
  const controller = domain.createSmartUiController(page.smartUi.stateAccessor);
  const Card = productionClass('components/NativeSmartUiCard.ets', ['sameValues', 'sameStrings', 'applyReconciliation', 'updateState'], {
    SMART_UI_MAX_INPUT_VALUE_CHARS: 512, MOTION_PRESS: 100, Curve: { EaseOut: {} },
  });
  const card = new Card();
  Object.assign(card, { controller, document, sourceKey: 'source', values: controller.reconcile(document, 'source', [], []).values,
    invalidStateIds: [], stateDiagnostics: [], onContentGrow: () => {},
    onStateChange: (id, value) => page.smartUi.onSmartUiStateChange('source', id, value) });
  return { page, card, controller, document, runtime, message, notices, domain };
}

test('a transient invalid numeric edit reaches the page owner and blocks ordinary text continuation', async () => {
  const { page, card, controller, document } = await fixture();
  card.updateState('amount', 60);
  assert.match(page.smartUi.captureSmartUiContinuation('按这个继续', page.smartUiSourceContext()).snapshot, /60/);
  card.updateState('amount', 57);
  assert.equal(page.smartUi.smartUiValue('source', 'amount'), 57);
  assert.equal(page.smartUi.captureSmartUiContinuation('按这个继续', page.smartUiSourceContext()), null);
  card.updateState('note', '另一个字段改变');
  const remounted = controller.reconcile(document, 'source', [], page.smartUi.smartUiValuesFor('source'));
  assert.equal(remounted.values.find(item => item.id === 'amount').value, 57);
  assert.ok(remounted.invalidStateIds.includes('amount'));
  assert.equal(page.smartUi.captureSmartUiContinuation('按这个继续', page.smartUiSourceContext()), null);
  card.updateState('amount', '');
  assert.equal(page.smartUi.smartUiValue('source', 'amount'), '');
  assert.equal(page.smartUi.captureSmartUiContinuation('按这个继续', page.smartUiSourceContext()), null);
});

test('button drafts freeze a visible selection without consuming original composer text or attachments', async () => {
  const { page, card, notices } = await fixture();
  card.updateState('amount', 60);
  card.updateState('note', '必须离线可用');
  const images = page.composer.pendingImages.slice();
  const docs = page.composer.pendingDocs.slice();
  page.onSmartUiDraft('continue', 'source');
  const filled = page.composer.inputText;
  assert.ok(filled.startsWith('我的原草稿\n\n继续比较'));
  assert.match(filled, /预算.*60/);
  assert.match(filled, /必须离线可用/);
  page.onSmartUiDraft('continue', 'source');
  assert.equal(page.composer.inputText, filled, 'identical button taps do not duplicate the snapshot');
  card.updateState('amount', 70);
  assert.equal(page.composer.inputText, filled, 'changing the card does not rewrite user-editable composer text');
  assert.deepEqual(page.composer.pendingImages, images);
  assert.deepEqual(page.composer.pendingDocs, docs);
  assert.equal(notices[0], '已填入输入框，确认后发送');
});

test('preflight rejects a changed choice or selected branch and preserves the composer', async () => {
  const { page, card } = await fixture();
  card.updateState('amount', 60);
  const capture = page.smartUi.captureSmartUiContinuation('按这个继续', page.smartUiSourceContext());
  assert.equal(page.smartUi.smartUiContinuationCurrent(capture, page.smartUiSourceContext()), true);
  card.updateState('amount', 70);
  assert.equal(page.smartUi.smartUiContinuationCurrent(capture, page.smartUiSourceContext()), false);
  assert.equal(page.composer.inputText, '我的原草稿');
  const nextCapture = page.smartUi.captureSmartUiContinuation('按这个继续', page.smartUiSourceContext());
  page.currentSmartUiMessages = () => [];
  assert.equal(page.smartUi.smartUiContinuationCurrent(nextCapture, page.smartUiSourceContext()), false);
  assert.match(page.errorMsg, /来源已变化/);
  assert.equal(page.composer.inputText, '我的原草稿');
});

test('publishing the same live card during preflight keeps its capture while a durable branch change rejects it', async () => {
  const { page, card, runtime } = await fixture();
  runtime.source = { ...runtime.source, nodeId: '' };
  runtime.streaming = true;
  card.updateState('amount', 60);
  const liveCapture = page.smartUi.captureSmartUiContinuation('按这个继续', page.smartUiSourceContext());
  assert.ok(liveCapture);
  runtime.source = { ...runtime.source, nodeId: 'published-node' };
  runtime.streaming = false;
  assert.equal(page.smartUi.smartUiContinuationCurrent(liveCapture, page.smartUiSourceContext()), true);
  const durableCapture = page.smartUi.captureSmartUiContinuation('按这个继续', page.smartUiSourceContext());
  runtime.source = { ...runtime.source, nodeId: 'different-branch' };
  assert.equal(page.smartUi.smartUiContinuationCurrent(durableCapture, page.smartUiSourceContext()), false);
});

test('a repeated native value notification preserves the owner array and actual interaction order', async () => {
  const { page, runtime } = await fixture();
  page.smartUi.setSmartUiValue('source', 'note', 'offline');
  const values = page.smartUi.smartUiValues;
  const interaction = runtime.lastInteraction;
  page.smartUi.setSmartUiValue('source', 'note', 'offline');
  assert.equal(page.smartUi.smartUiValues, values);
  assert.equal(runtime.lastInteraction, interaction);
  page.smartUi.setSmartUiValue('source', 'note', 'updated');
  assert.notEqual(page.smartUi.smartUiValues, values);
  assert.ok(runtime.lastInteraction > interaction);
});

test('streamed card growth and completion keep the most recent actual field interaction', async () => {
  const { page, runtime, message, domain } = await fixture();
  const header = '```amber-ui v=1 id=budget\n';
  const body = message.parts[0].text.slice(header.length).split('{"op":"end"}')[0];
  const liveSource = { ...runtime.source, nodeId: '' };
  message.parts[0].text = header + body;
  page.smartUi.onSmartUiDocument('source', domain.parseSmartUi(body, 'budget', false), true, liveSource, false);
  page.smartUi.setSmartUiValue('source', 'note', '只用离线方案');
  const interaction = page.smartUi.smartUiRuntime.get('source').lastInteraction;
  assert.ok(interaction > 0);
  const grownBody = body + JSON.stringify({ op: 'node', id: 'explanation', parent: 'root',
    type: 'text', text: '新增说明不改变已填的要求' }) + '\n';
  message.parts[0].text = header + grownBody;
  page.smartUi.onSmartUiDocument('source', domain.parseSmartUi(grownBody, 'budget', false), true, liveSource, false);
  assert.equal(page.smartUi.smartUiRuntime.get('source').lastInteraction, interaction);
  const completedBody = grownBody + '{"op":"end"}\n';
  message.parts[0].text = header + completedBody + '```';
  page.smartUi.onSmartUiDocument('source', domain.parseSmartUi(completedBody, 'budget', true), false, runtime.source, true);
  assert.equal(page.smartUi.smartUiRuntime.get('source').lastInteraction, interaction);
  assert.match(page.smartUi.captureSmartUiContinuation('按这个继续', page.smartUiSourceContext()).snapshot, /只用离线方案/);
});

test('a different durable source clears the prior values and continuation intent', async () => {
  const { page, runtime, document } = await fixture();
  page.smartUi.setSmartUiValue('source', 'note', '原分支要求');
  page.smartUi.onSmartUiDocument('source', document, false, { ...runtime.source, nodeId: 'different-node' }, true);
  assert.equal(page.smartUi.smartUiRuntime.get('source').lastInteraction, 0);
  assert.equal(page.smartUi.smartUiValue('source', 'note'), undefined);
  assert.equal(page.smartUi.captureSmartUiContinuation('按这个继续', page.smartUiSourceContext()), null);
});

test('an expanded button prompt cannot silently truncate its selection snapshot', async () => {
  const { page, card, controller, document, runtime, notices, domain } = await fixture('{{note}}'.repeat(7));
  card.updateState('note', 'x'.repeat(512));
  const prompt = controller.draft(document, document.nodes.find(node => node.id === 'continue').action,
    page.smartUi.smartUiValuesFor('source'));
  const snapshot = page.smartUi.smartUiSnapshot(runtime);
  assert.ok(prompt.length < domain.SMART_UI_MAX_DRAFT_PROMPT_CHARS);
  assert.ok(domain.appendSmartUiContinuation(prompt, snapshot).length > domain.SMART_UI_MAX_DRAFT_PROMPT_CHARS);
  const original = page.composer.inputText;
  const images = page.composer.pendingImages.slice();
  const docs = page.composer.pendingDocs.slice();
  const interaction = runtime.lastInteraction;
  page.onSmartUiDraft('continue', 'source');
  assert.equal(page.composer.inputText, original);
  assert.deepEqual(page.composer.pendingImages, images);
  assert.deepEqual(page.composer.pendingDocs, docs);
  assert.equal(runtime.lastInteraction, interaction);
  assert.match(page.errorMsg, /内容过长/);
  assert.deepEqual(notices, []);
});

test('an expanded button prompt within the total budget keeps its complete snapshot', async () => {
  const { page, card, runtime } = await fixture('{{note}}'.repeat(6));
  card.updateState('note', 'x'.repeat(512));
  const snapshot = page.smartUi.smartUiSnapshot(runtime);
  page.onSmartUiDraft('continue', 'source');
  const filled = page.composer.inputText;
  assert.ok(filled.startsWith('我的原草稿\n\n'));
  assert.ok(filled.endsWith(snapshot));
  assert.ok(filled.endsWith('【选择结束】'));
  page.onSmartUiDraft('continue', 'source');
  assert.equal(page.composer.inputText, filled);
});


test('a button expands its current action and snapshot atomically at the page owner after the final keystroke', async () => {
  const { page, card, controller, document } = await fixture('按照{{note}}继续比较');
  card.updateState('note', '最后一个字');
  const staleValues = controller.reconcile(document, 'source', [], []).values.map(item =>
    item.id === 'note' ? { id: item.id, value: '最后一个' } : item);
  const Node = productionClass('components/NativeSmartUiNode.ets', ['currentNode', 'draftText', 'clickDraft'], {
    EMPTY_NODE: { id: 'empty', type: 'column', parent: null },
  });
  const node = new Node();
  const emitted = [];
  Object.assign(node, { nodeId: 'continue', document, controller, values: staleValues, ready: true,
    onDraft: nodeId => { emitted.push(nodeId); page.onSmartUiDraft(nodeId, 'source'); },
    onReset: () => assert.fail('a draft button must not reset') });
  assert.equal(node.draftText(), '按照最后一个继续比较', 'the retained renderer prop reproduces the old incomplete template');
  node.clickDraft();
  assert.deepEqual(emitted, ['continue'], 'the callback carries identity rather than its stale expanded text');
  assert.ok(page.composer.inputText.startsWith('我的原草稿\n\n按照最后一个字继续比较'));
  assert.match(page.composer.inputText, /补充要求.*最后一个字/);
  assert.ok(!page.composer.inputText.includes('按照最后一个继续比较'));
  const frozen = page.composer.inputText;
  card.updateState('note', '后续新编辑');
  assert.equal(page.composer.inputText, frozen, 'the already prepared draft remains editable and frozen');
});

const nextTurn = async () => { for (let turn = 0; turn < 6; turn++) await Promise.resolve(); };
const deferred = () => {
  let resolve;
  const promise = new Promise(complete => { resolve = complete; });
  return { promise, resolve };
};

async function restoringSession() {
  const f = await fixture();
  const Session = loadChatSmartUiSession(f.domain);
  const session = new Session();
  const loads = [];
  let flushes = 0;
  const errors = [];
  session.start({
    load: () => { const load = deferred(); loads.push(load); return load.promise; },
    saveDebounced: async () => ({ saved: true }),
    flush: async () => { flushes++; },
  }, message => errors.push(message));
  session.onSmartUiDocument('restored', f.document, false, f.runtime.source, true);
  return { ...f, session, loads, errors, flushes: () => flushes };
}

test('async draft restore publishes one new first-level array and revision through the retained accessor', async () => {
  const f = await restoringSession();
  const values = f.session.smartUiValues;
  const accessor = f.session.stateAccessor;
  const revision = f.session.smartUiStateRevision;
  f.loads[0].resolve({ status: 'restored', values: [{ id: 'amount', value: 80 }], diagnostics: [] });
  await nextTurn();
  assert.notEqual(f.session.smartUiValues, values);
  assert.equal(f.session.smartUiStateRevision, revision + 1);
  assert.equal(f.session.stateAccessor, accessor);
  assert.equal(accessor.get('restored', 'amount'), 80);
  accessor.set('restored', 'amount', 90);
  assert.equal(f.session.smartUiStateRevision, revision + 2);
  assert.equal(f.session.smartUiValue('restored', 'amount'), 90);
});

test('a late restored draft cannot overwrite a current user edit', async () => {
  const f = await restoringSession();
  f.session.stateAccessor.set('restored', 'note', '保留最新编辑');
  const values = f.session.smartUiValues;
  const revision = f.session.smartUiStateRevision;
  f.loads[0].resolve({ status: 'restored', values: [{ id: 'note', value: '旧草稿' }], diagnostics: [] });
  await nextTurn();
  assert.equal(f.session.smartUiValue('restored', 'note'), '保留最新编辑');
  assert.equal(f.session.smartUiValues, values);
  assert.equal(f.session.smartUiStateRevision, revision);
});

test('hide flushes without applying a late load; showing retries while disposal keeps later callbacks inert', async () => {
  const f = await restoringSession();
  const values = f.session.smartUiValues;
  const accessor = f.session.stateAccessor;
  f.session.hide();
  assert.equal(f.flushes(), 1);
  f.loads[0].resolve({ status: 'restored', values: [{ id: 'amount', value: 80 }], diagnostics: [] });
  await nextTurn();
  assert.equal(f.session.smartUiValues, values);
  assert.equal(f.session.smartUiValue('restored', 'amount'), undefined);
  f.session.show();
  assert.equal(f.loads.length, 2);
  f.loads[1].resolve({ status: 'restored', values: [{ id: 'amount', value: 90 }], diagnostics: [] });
  await nextTurn();
  assert.equal(f.session.stateAccessor, accessor);
  assert.equal(f.session.smartUiValue('restored', 'amount'), 90);
  f.session.onSmartUiDocument('another', f.document, false, f.runtime.source, true);
  assert.equal(f.loads.length, 3);
  f.session.dispose();
  assert.equal(f.flushes(), 2);
  const disposedValues = f.session.smartUiValues;
  f.loads[2].resolve({ status: 'restored', values: [{ id: 'amount', value: 60 }], diagnostics: [] });
  await nextTurn();
  assert.equal(f.session.smartUiValues, disposedValues);
  assert.equal(f.session.smartUiValue('another', 'amount'), undefined);
  assert.deepEqual(f.errors, []);
});
