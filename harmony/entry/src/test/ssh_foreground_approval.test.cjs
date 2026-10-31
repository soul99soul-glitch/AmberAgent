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
    if (key === '@amber/chat-domain') return {
      collectToolApprovalCandidates, isSSHProfileMainSessionOnly,
      ...require('../../../chat/src/main/ets/chat/terminal/host_trust_approval.ts'),
    };
    throw Error(key);
  }, { get: () => state.background });
  const conversation = { messageNodes: [{ messages: [{ id: 'm', parts: [
    { type: 'tool', toolCallId: 'call', toolName: 'ssh_key_import', metadata: null },
  ] }] }] };
  return { state, conversation, check: verdict => exports.assertSSHForegroundApproval(conversation, 'call', verdict) };
}

test('HM-2: auto high-risk cannot pin; only an unlocked foreground verdict bound to call, route and fingerprint can commit', async () => {
  const { createMemoryKeyValueStore } = require('../../../chat/src/main/ets/chat/kv_store.ts');
  const { SSHProfileStore } = require('../../../chat/src/main/ets/chat/terminal/profile_store.ts');
  const { SSHTargetResolver } = require('../../../chat/src/main/ets/chat/terminal/target_resolver.ts');
  const { createSSHHostTrustTool } = require('../../../chat/src/main/ets/chat/terminal/host_trust_tool.ts');
  const { createSSHKeyTools } = require('../../../chat/src/main/ets/chat/terminal/key_tools.ts');
  const { createSSHProfileTools } = require('../../../chat/src/main/ets/chat/terminal/profile_tools.ts');
  const { createTerminalApprovalGuard } = require('../../../chat/src/main/ets/chat/terminal_approval.ts');
  const { PermissionDecisionResolver } = require('../../../chat/src/main/ets/chat/tool_permission.ts');
  const { AgentToolDispatcher } = require('../../../chat/src/main/ets/chat/tool_dispatcher.ts');
  const secrets = new Map();
  const credentials = { save: async (id, _binding, value) => { secrets.set(id, value); },
    load: async id => secrets.get(id) ?? null, exists: async id => secrets.has(id),
    delete: async id => { secrets.delete(id); } };
  const profiles = await SSHProfileStore.create({ kv: createMemoryKeyValueStore(), credentials });
  const jump = { id: 'hm2-jump', name: 'Synthetic jump', host: 'jump.invalid', port: 22,
    username: 'fixture', authMethod: 'password' };
  await profiles.commitVerified(jump, null, 'SHA256:synthetic-jump', { secret: 'synthetic-jump-password', passphrase: null });
  const draft = { id: 'hm2-target', name: 'Synthetic target', host: 'target.invalid', port: 22,
    username: 'fixture', authMethod: 'password', jumpProfileId: jump.id };
  await profiles.saveWithCredential(draft, null, { secret: 'synthetic-HM2-password', passphrase: null });
  const resolver = new SSHTargetResolver({ profiles, credentials });
  let presented = 'SHA256:synthetic-HM2';
  let duringProbe = async () => {};
  const runtime = { captureTarget: id => resolver.captureTarget(id), readSession: () => { throw Error('unused'); },
    probe: async () => {
      const revision = profiles.get(draft.id).revision;
      await duringProbe();
      return { profileRevision: revision, fingerprintSHA256: presented, hostKeyType: 'ssh-ed25519', hopIndex: 1, hopCount: 1 };
    } };
  const toolDef = createSSHHostTrustTool(runtime, profiles);
  const permissions = new PermissionDecisionResolver();
  const guard = () => createTerminalApprovalGuard(runtime, profiles);
  const part = (id, input = { profile_id: draft.id, fingerprint: presented }) => {
    const tool = { type: 'tool', toolName: toolDef.name, toolCallId: id, input: JSON.stringify(input),
      output: [], approvalState: { type: 'auto' }, metadata: null };
    tool.metadata = guard().captureInvocationMetadata(tool);
    return tool;
  };
  const run = async tool => new AgentToolDispatcher({ hooks: [guard().hook] }).execute(tool, toolDef, true, true);
  const payload = result => JSON.parse(result.output[0].text);
  const approve = async tool => {
    f.conversation.messageNodes[0].messages[0].parts = [tool];
    await f.check({ kind: 'approved' });
    return { ...tool, approvalState: { type: 'approved' } };
  };
  const commit = part('call');
  assert.equal(permissions.resolve(toolDef, commit, true, true).action, 'ask');
  assert.equal((await run(commit)).approvalState.type, 'pending');
  assert.equal(profiles.get(draft.id).knownHostSHA256, null);
  assert.equal(permissions.resolve(toolDef, part('probe', { profile_id: draft.id }), true, true).action, 'allow');
  for (const other of [...createSSHKeyTools({ keys: {}, profiles }), ...createSSHProfileTools({ profiles })]) {
    if (!['ssh_key_generate', 'ssh_key_import', 'ssh_key_delete', 'ssh_profile_create', 'ssh_profile_update', 'ssh_profile_delete'].includes(other.name)) continue;
    const invocation = { ...commit, toolName: other.name, input: '{}' };
    assert.equal(permissions.resolve(other, invocation, true, true).action, 'allow');
  }
  const f = fixture();
  // A persisted approval flag alone is not a foreground confirmation.
  assert.equal(payload(await run({ ...commit, approvalState: { type: 'approved' } })).status, 'failed');
  for (const field of ['locked', 'background', 'lockError']) {
    f.state[field] = true;
    await assert.rejects(approve(commit), /前台卡片批准/);
    f.state[field] = false;
    assert.equal(payload(await run({ ...commit, approvalState: { type: 'approved' } })).status, 'failed');
  }
  const changedInput = await approve(commit);
  presented = 'SHA256:changed-HM2';
  assert.equal(payload(await run({ ...changedInput, input: JSON.stringify({ profile_id: draft.id, fingerprint: presented }) })).status, 'failed');
  presented = 'SHA256:synthetic-HM2';
  const changedRoute = await approve(part('call'));
  await profiles.saveMetadata({ ...profiles.get(jump.id), name: 'changed-route' }, profiles.get(jump.id).revision);
  assert.equal(payload(await run(changedRoute)).status, 'failed');
  const fresh = await approve(part('call'));
  assert.equal(payload(await run({ ...fresh, toolCallId: 'other-call' })).status, 'failed');
  // A route edit while the probe awaits must also invalidate the commit proof.
  duringProbe = async () => profiles.saveMetadata({ ...profiles.get(jump.id), name: 'edited-during-probe' }, profiles.get(jump.id).revision);
  assert.equal(payload(await run(fresh)).status, 'failed');
  duringProbe = async () => {};
  const commitVerified = profiles.commitVerified.bind(profiles);
  profiles.commitVerified = (...args) => {
    const edit = profiles.saveMetadata({ ...profiles.get(jump.id), name: 'queued-before-commit' }, profiles.get(jump.id).revision);
    const committing = commitVerified(...args);
    return edit.then(() => committing);
  };
  assert.equal(payload(await run(await approve(part('call')))).error_code, 'profile_conflict');
  profiles.commitVerified = commitVerified;
  const approved = await approve(part('call'));
  const trusted = payload(await run(approved));
  assert.equal(trusted.status, 'trusted', JSON.stringify(trusted));
  assert.equal(profiles.get(draft.id).knownHostSHA256, presented);
});
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
