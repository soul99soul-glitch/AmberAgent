import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createMemoryKeyValueStore } from '../main/ets/chat/kv_store.ts';
import { SSHKeyStore } from '../main/ets/chat/terminal/key_store.ts';
import { SSHProfileStore } from '../main/ets/chat/terminal/profile_store.ts';
import { createSSHKeyTools } from '../main/ets/chat/terminal/key_tools.ts';
import { createSSHProfileTools, isSSHProfileMainSessionOnly } from '../main/ets/chat/terminal/profile_tools.ts';
import { resolveSSHRoute, validateProfileRouteCandidate } from '../main/ets/chat/terminal/route_resolver.ts';
import { SSHTargetResolver } from '../main/ets/chat/terminal/target_resolver.ts';
import { SSHOutputRedactor } from '../main/ets/chat/terminal/output_redactor.ts';
import { toolInvocationPolicy } from '../main/ets/chat/tool_policy.ts';
import { redactStoredToolInput } from '../main/ets/chat/tool_input_redaction.ts';
import { captureSecretToolInput, restoreSecretToolInput, clearAllSecretToolInputs, secretToolInputCount } from '../main/ets/chat/tool_input_vault.ts';
import type { SSHProfile, SSHCredential } from '../main/ets/chat/terminal/models.ts';
import type { JsonObject } from '../main/ets/chat/json.ts';

const profile = (id: string, jumpProfileId: string | null = null): SSHProfile => ({
  id, name: id, host: id + '.invalid', port: 22, username: 'test', authMethod: 'password', revision: 1,
  jumpProfileId, knownHostSHA256: 'SHA256:fixture', knownHostHost: id + '.invalid', knownHostPort: 22,
  knownHostViaJump: jumpProfileId, credentialRef: null,
});
const setup = async () => {
  const kv = createMemoryKeyValueStore();
  const saved = new Map<string, SSHCredential>();
  const credentials = { async save(id: string, _binding: unknown, value: SSHCredential) { saved.set(id, value); },
    async load(id: string) { return saved.get(id) ?? null; }, async exists(id: string) { return saved.has(id); },
    async delete(id: string) { saved.delete(id); } };
  // Unit port only. Real OpenSSL/key format validation is in jump_native.test.cjs.
  const crypto = { async generateKey() { return { algorithm: 'ed25519', publicKey: 'ssh-ed25519 unit-fixture',
    fingerprintSHA256: 'SHA256:unit-fixture', privateKey: 'private-key-unit-secret' }; },
    async inspectKey() { return { algorithm: 'ed25519', publicKey: 'ssh-ed25519 imported-unit-fixture',
      fingerprintSHA256: 'SHA256:imported-unit-fixture', hasPassphrase: true }; } };
  const keys = await SSHKeyStore.create({ kv, credentials, crypto });
  const profiles = await SSHProfileStore.create({ kv, credentials, keys });
  const tools = [...createSSHKeyTools({ keys, profiles }), ...createSSHProfileTools({ keys, profiles })];
  const call = async (name: string, args: JsonObject) => {
    const out = await tools.find(t => t.name === name)!.execute(args);
    assert.equal(out[0].type, 'text');
    return JSON.parse(out[0].type === 'text' ? out[0].text : '{}');
  };
  return { kv, saved, credentials, keys, profiles, tools, call };
};

test('route fixtures, full cycle path and reverse-dependent validation', () => {
  const fixtures = JSON.parse(readFileSync(new URL('../../../../docs/ssh/route-fixtures.json', import.meta.url), 'utf8'));
  for (const fixture of fixtures) {
    const all = Object.entries(fixture.links).map(([id, jump]) => profile(id, jump as string | null));
    if (fixture.error) assert.throws(() => resolveSSHRoute(fixture.target, all), { code: fixture.error }, fixture.name);
    else assert.deepEqual(resolveSSHRoute(fixture.target, all).map(p => p.id), fixture.route, fixture.name);
  }
  assert.throws(() => resolveSSHRoute('a', [profile('a', 'b'), profile('b', 'c'), profile('c', 'b')]),
    e => { assert.deepEqual((e as { details: { cycle_path: string[] } }).details.cycle_path, ['a', 'b', 'c', 'b']); return true; });
  const all = [profile('a', 'b'), profile('b', 'c'), profile('c', 'd'), profile('d'), profile('e')];
  assert.throws(() => validateProfileRouteCandidate(profile('d', 'e'), all), { code: 'jump_chain_too_long' });
  assert.equal(all[3].jumpProfileId, null);
});

test('key tool policy, reference binding and deletion guards (including concurrent profile save)', async () => {
  const s = await setup();
  for (const tool of s.tools.filter(t => t.name.startsWith('ssh_key_'))) {
    const read = tool.name === 'ssh_key_list';
    const policy = toolInvocationPolicy(tool, {});
    assert.equal(policy.risk, read ? 'normal' : 'high');
    assert.equal(policy.category, 'terminal');
    assert.equal(policy.mutates, !read);
    assert.equal(isSSHProfileMainSessionOnly(tool.name), !read);
  }
  const key = (await s.call('ssh_key_generate', { name: 'device' })).key;
  const created = await s.call('ssh_profile_create', { name: 'jump', host: 'jump.invalid', username: 'u', auth_method: 'key', key_id: key.id });
  assert.equal(created.profile.key_id, key.id);
  assert.equal((await s.call('ssh_key_delete', { key_id: key.id })).error_code, 'key_in_use');
  const target = await s.call('ssh_profile_create', { name: 'target', host: 'target.invalid', username: 'u',
    password: 'target-test-password', jump_profile_id: created.profile.id });
  assert.equal((await s.call('ssh_profile_delete', { id: created.profile.id })).error_code, 'in_use_as_jump');
  assert.equal(target.profile.route_ready, false);
  const serialized = JSON.stringify(await s.call('ssh_key_list', {}));
  assert.equal(serialized.includes('private-key-unit-secret'), false);
  assert.equal(serialized.includes('credentialRef'), false);
  const another = await s.keys.generateKey('race');
  const race = await Promise.allSettled([
    s.profiles.saveWithCredential({ ...profile('race'), authMethod: 'privateKey', keyId: another.id }, null, null),
    s.keys.deleteKey(another.id),
  ]);
  assert.equal(race.filter(r => r.status === 'rejected').length, 1);
  if (s.profiles.get('race') !== null) assert.ok(s.keys.get(another.id));
  else assert.equal(s.keys.get(another.id), null);
});

test('legacy private key imports to key_id; auth switch clears reference; pin changes invalidate approval', async () => {
  const s = await setup();
  const created = await s.call('ssh_profile_create', { name: 'legacy', host: 'legacy.invalid', username: 'u',
    auth_method: 'private_key', private_key: 'unit-import-secret', passphrase: 'unit-passphrase' });
  assert.ok(created.profile.key_id);
  const id = created.profile.id;
  assert.equal((await s.call('ssh_key_list', {})).keys[0].has_passphrase, true);
  const current = s.profiles.get(id)!;
  await s.profiles.commitVerified(current, current.revision, 'SHA256:fixture', null);
  const resolver = new SSHTargetResolver({ profiles: s.profiles, credentials: s.credentials, keys: s.keys });
  const captured = resolver.captureTarget(id);
  await s.profiles.saveMetadata({ ...s.profiles.get(id)!, name: 'renamed' }, s.profiles.get(id)!.revision);
  assert.throws(() => resolver.targetProfile(captured), { code: 'target_changed' });
  const switched = await s.call('ssh_profile_update', { id, auth_method: 'password', password: 'unit-password' });
  assert.equal(switched.profile.key_id, null);
  const pin = s.profiles.get(id)!.knownHostSHA256;
  await s.call('ssh_profile_update', { id, name: 'rename2' });
  assert.equal(s.profiles.get(id)!.knownHostSHA256, pin);
});

test('key import is redacted before persistence and fails closed without its vault entry', async () => {
  clearAllSecretToolInputs();
  const input = JSON.stringify({ name: 'imported', private_key: 'private-key-marker', passphrase: 'passphrase-marker' });
  const captured = captureSecretToolInput('ssh_key_import', 'import-call', input);
  assert.equal(captured.captured, true);
  assert.equal(captured.input.includes('marker'), false);
  assert.equal(redactStoredToolInput('ssh_key_import', input).includes('marker'), false);
  const restored = restoreSecretToolInput('ssh_key_import', 'import-call', JSON.parse(captured.input));
  assert.equal(restored.ok, true);
  assert.equal(secretToolInputCount(), 0);
  assert.equal(restoreSecretToolInput('ssh_key_import', 'import-call', JSON.parse(captured.input)).ok, false);
  const s = await setup();
  for (const placeholder of ['***', '<redacted>', '[REDACTED]', '••••', '(empty)']) {
    assert.equal((await s.call('ssh_key_import', { name: 'bad', private_key: placeholder })).error_code, 'credential_unavailable');
    assert.equal((await s.call('ssh_key_import', { name: 'bad', private_key: 'unit-key', passphrase: placeholder })).error_code, 'credential_unavailable');
  }
});

test('streaming output redacts every split of jump and target credentials', () => {
  const secrets = ['jump-only-password', 'target-only-passphrase', 'PRIVATEKEYBODYUNIT'];
  for (const secret of secrets) {
    for (let split = 1; split < secret.length; split++) {
      const redactor = new SSHOutputRedactor(secrets);
      assert.equal(redactor.push('safe ' + secret.slice(0, split)) + redactor.push(secret.slice(split) + ' end', true), 'safe *** end');
    }
  }
});

test('first-use trust probes and confirms a fingerprint but cannot replace an existing pin', async () => {
  const { createSSHHostTrustTool } = await import('../main/ets/chat/terminal/host_trust_tool.ts');
  const { draftDigest } = await import('../main/ets/chat/terminal/profile_store.ts');
  const s = await setup();
  const p = await s.profiles.saveWithCredential(profile('untrusted'), null, { secret: 'fixture-pw', passphrase: null });
  const runtime = { async probe() { return { draftDigest: draftDigest(p), profileRevision: p.revision,
    fingerprintSHA256: 'SHA256:first-key', hostKeyType: 'ssh-ed25519', expectedFingerprintSHA256: null,
    trustState: 'untrusted' as const, hopIndex: 1, hopCount: 1 }; } };
  const tool = createSSHHostTrustTool(runtime as unknown as import('../main/ets/chat/terminal/runtime.ts').TerminalRuntime, s.profiles);
  const call = async (args: JsonObject) => {
    const parts = await tool.execute(args);
    return JSON.parse(parts[0].type === 'text' ? parts[0].text : '{}');
  };
  assert.equal(toolInvocationPolicy(tool, {}).risk, 'high');
  assert.equal((await call({ profile_id: p.id })).status, 'awaiting_host_trust');
  assert.equal((await call({ profile_id: p.id, fingerprint: 'SHA256:wrong' })).status, 'host_key_changed');
  assert.equal(s.profiles.get(p.id)!.knownHostSHA256, null);
  assert.equal((await call({ profile_id: p.id, fingerprint: 'SHA256:first-key' })).status, 'trusted');
  assert.equal((await call({ profile_id: p.id, fingerprint: 'SHA256:replacement' })).status, 'settings_required');
  assert.equal(s.profiles.get(p.id)!.knownHostSHA256, 'SHA256:first-key');
});
