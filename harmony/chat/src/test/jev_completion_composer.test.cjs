const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { loadChatComposerSession } = require('../../../entry/src/test/chat_composer_fixture.cjs');
process.env.TSX_TSCONFIG_PATH ??= path.resolve(__dirname, '../../tsconfig.json');
require('tsx/cjs');
const domain = require('../main/ets/index.ts');
const Composer = loadChatComposerSession(domain);
function fixture(draft = '', attachments = false, editing = false) {
  const conversation = domain.makeConversation('A', [domain.toMessageNode(domain.makeUIMessage('user', [{ type: 'text', text: 'change', metadata: null }])),
    domain.toMessageNode(domain.makeUIMessage('assistant', [{ type: 'tool', toolCallId: 't', toolName: 'file_write', input: '{}',
      output: [{ type: 'text', text: '{"path":"app.ts"}', metadata: null }], approvalState: { type: 'approved' }, metadata: null },
      { type: 'text', text: 'done', metadata: null }], { id: 'final' }))]);
  const facts = domain.jevUnverifiedChanges(domain.currentMessages(conversation));
  const notice = { runId: 'run', finalMessageId: facts.finalMessageId, finalContentFingerprint: facts.finalContentFingerprint, sourceFingerprint: facts.sourceFingerprint };
  const snapshot = { completionNotice: notice, sending: false };
  let dismissed = 0, sends = 0;
  const state = { completionNotice: notice };
  const page = new Composer();
  page.start({
    kvStore: { put: async () => {} },
    runService: { snapshot: () => snapshot, dismissCompletion: () => { dismissed++; } },
    currentConversation: () => conversation, isSending: () => false,
    currentCompletionNotice: () => state.completionNotice,
    setCompletionNotice: value => { state.completionNotice = value; },
  });
  page.setInputText(draft);
  page.pendingImages = attachments ? ['image'] : [];
  page.editingNodeId = editing ? 'edit-node' : '';
  page.editingMessageId = editing ? 'edit-message' : '';
  // Retain the existing send spy: these callback tests assert the decision to
  // dispatch, while the send/preflight suite runs the real async send lifecycle.
  page.send = () => { sends++; };
  return { page, snapshot, dismissed: () => dismissed, sends: () => sends };
}
test('actual composer verification callback requests send only for an empty composer', () => {
  const f = fixture(); f.page.requestCompletionVerification();
  assert.equal(f.page.inputText, domain.JEV_VERIFICATION_PROMPT); assert.equal(f.sends(), 1);
  assert.equal(f.dismissed(), 1); assert.equal(f.page.completionNotice, null);
});
test('actual callback preserves draft, attachments and edit identity and waits for a manual send', () => {
  for (const args of [['draft', false, false], ['', true, false], ['', false, true], [' ', false, false]]) {
    const f = fixture(...args); const images = f.page.pendingImages; const edit = f.page.editingMessageId;
    f.page.requestCompletionVerification(); assert.equal(f.sends(), 0);
    assert.equal(f.page.pendingImages, images); assert.equal(f.page.editingMessageId, edit);
    assert.equal(f.page.inputText, args[0].length > 0 ? `${args[0]}\n\n${domain.JEV_VERIFICATION_PROMPT}` : domain.JEV_VERIFICATION_PROMPT);
  }
});
test('obsolete run/tail notice cannot send or alter the existing composer', () => {
  const f = fixture('draft'); f.snapshot.completionNotice = null; f.page.requestCompletionVerification();
  assert.equal(f.sends(), 0); assert.equal(f.page.inputText, 'draft'); assert.equal(f.page.completionNotice, null);
});
