const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
require('../../../chat/node_modules/tsx/dist/cjs/index.cjs');
const { collectToolApprovalCandidates } = require('../../../chat/src/main/ets/chat/tool_approval.ts');
const { isSSHProfileMainSessionOnly } = require('../../../chat/src/main/ets/chat/terminal/profile_tools.ts');
const source = fs.readFileSync(path.join(__dirname, '../main/ets/platform_impl/SSHForegroundApproval.ets'), 'utf8');
const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText;
function fixture() {
  const state = { background: false, locked: false, lockError: false };
  const exports = {};
  new Function('exports', 'require', 'AppStorage', code)(exports, key => {
    if (key === '@ohos.screenLock') return { default: { isScreenLocked: async () => {
      if (state.lockError) throw Error('unavailable'); return state.locked;
    } } };
    if (key === '@amber/chat-domain') return { collectToolApprovalCandidates, isSSHProfileMainSessionOnly };
    throw Error(key);
  }, { get: () => state.background });
  const conversation = { messageNodes: [{ messages: [{ id: 'm', parts: [
    { type: 'tool', toolCallId: 'call', toolName: 'ssh_key_import', metadata: null },
  ] }] }] };
  return { state, conversation, check: verdict => exports.assertSSHForegroundApproval(conversation, 'call', verdict) };
}
test('SSH manual approval denies locked, background, unknown-lock and changed-key cards; denial remains available', async () => {
  const f = fixture();
  await f.check({ kind: 'approved' });
  for (const field of ['locked', 'background', 'lockError']) {
    f.state[field] = true;
    await assert.rejects(f.check({ kind: 'approved' }), /前台卡片批准/);
    await f.check({ kind: 'denied', reason: 'no' });
    f.state[field] = false;
  }
  f.conversation.messageNodes[0].messages[0].parts[0].metadata = { ssh_trust_settings_required: true };
  await assert.rejects(f.check({ kind: 'approved' }), /人工核对/);
  await f.check({ kind: 'denied', reason: 'no' });
});
