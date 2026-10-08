const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
process.env.TSX_TSCONFIG_PATH ??= path.resolve(__dirname, '../../tsconfig.json');
require('tsx/cjs');
const domain = require('../main/ets/index.ts');
const source = fs.readFileSync(path.resolve(__dirname, '../../../entry/src/main/ets/pages/ChatPage.ets'), 'utf8');
const methods = ['dismissCompletionNotice', 'requestCompletionVerification'].map(name =>
  source.match(new RegExp(`  private ${name}\\([^]*?\\n  \\}`))[0]).join('\n');
function fixture(draft = '', attachments = false, editing = false) {
  const conversation = domain.makeConversation('A', [domain.toMessageNode(domain.makeUIMessage('user', [{ type: 'text', text: 'change', metadata: null }])),
    domain.toMessageNode(domain.makeUIMessage('assistant', [{ type: 'tool', toolCallId: 't', toolName: 'file_write', input: '{}',
      output: [{ type: 'text', text: '{"path":"app.ts"}', metadata: null }], approvalState: { type: 'approved' }, metadata: null },
      { type: 'text', text: 'done', metadata: null }], { id: 'final' }))]);
  const facts = domain.jevUnverifiedChanges(domain.currentMessages(conversation));
  const notice = { runId: 'run', finalMessageId: facts.finalMessageId, finalContentFingerprint: facts.finalContentFingerprint, sourceFingerprint: facts.sourceFingerprint };
  const snapshot = { completionNotice: notice, sending: false };
  let dismissed = 0, sends = 0;
  const exports = {};
  vm.runInNewContext(ts.transpileModule(`class Probe {${methods}};exports.Probe=Probe;`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText,
  { exports, currentMessages: domain.currentMessages, jevUnverifiedChanges: domain.jevUnverifiedChanges,
    jevVerificationComposerAction: domain.jevVerificationComposerAction,
    getConversationRunService: () => ({ snapshot: () => snapshot, dismissCompletion: () => { dismissed++; } }) });
  const page = new exports.Probe(); Object.assign(page, { conversation, completionNotice: notice, sending: false,
    sendPrechecking: false, materializingImages: false, inputText: draft, pendingImages: attachments ? ['image'] : [], pendingDocs: [],
    editingNodeId: editing ? 'edit-node' : '', editingMessageId: editing ? 'edit-message' : '', send: () => { sends++; } });
  return { page, snapshot, dismissed: () => dismissed, sends: () => sends };
}
test('actual page verification callback uses original send only for an empty composer', () => {
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
