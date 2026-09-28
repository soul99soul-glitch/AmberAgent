import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createMemoryKeyValueStore } from '../main/ets/chat/kv_store.ts';
import { SSHProfileStore } from '../main/ets/chat/terminal/profile_store.ts';
import type { SSHCredential, SSHCredentialBinding, SSHProfileDraft } from '../main/ets/chat/terminal/models.ts';
import type { SSHCredentialStorePort } from '../main/ets/chat/terminal/ports.ts';

class Credentials implements SSHCredentialStorePort {
  entries = new Map<string, SSHCredential>();
  failSave = false;
  failDelete = false;
  async save(ref: string, _binding: SSHCredentialBinding, value: SSHCredential): Promise<void> {
    if (this.failSave) throw new Error('asset unavailable');
    assert.equal(this.entries.has(ref), false);
    this.entries.set(ref, value);
  }
  async load(ref: string): Promise<SSHCredential | null> { return this.entries.get(ref) ?? null; }
  async exists(ref: string): Promise<boolean> { return this.entries.has(ref); }
  async delete(ref: string): Promise<void> {
    if (this.failDelete) throw new Error('asset cleanup unavailable');
    this.entries.delete(ref);
  }
}

const draft: SSHProfileDraft = { id: 'p', name: 'LAN', host: ' server ', port: 22, username: 'user', authMethod: 'password' };
const secret: SSHCredential = { secret: ' secret-marker ', passphrase: null };
const setup = async () => {
  const kv = createMemoryKeyValueStore();
  const credentials = new Credentials();
  const store = await SSHProfileStore.create({ kv, credentials });
  const profile = await store.commitVerified(draft, null, 'SHA256:abc', secret);
  return { kv, credentials, store, profile };
};

test('profile KV failure preserves prior password and cleans staged replacement', async () => {
  const { kv, credentials, store, profile } = await setup();
  kv.put = async () => { throw new Error('disk full'); };
  await assert.rejects(store.commitVerified(draft, profile.revision, 'SHA256:new', { secret: 'new', passphrase: null }));
  assert.deepEqual(store.get('p'), profile);
  assert.equal(credentials.entries.size, 1);
  assert.equal((await credentials.load(profile.credentialRef!))?.secret, secret.secret);
});

test('credential save failure does not alter metadata, pin or existing ref', async () => {
  const { credentials, store, profile } = await setup();
  credentials.failSave = true;
  await assert.rejects(store.commitVerified(draft, profile.revision, 'SHA256:new', secret));
  assert.deepEqual(store.get('p'), profile);
});

test('revision CAS rejects stale verified commit without leaking staged credentials', async () => {
  const { credentials, store, profile } = await setup();
  const renamed = await store.saveMetadata({ ...draft, name: 'renamed' }, profile.revision);
  await assert.rejects(store.commitVerified(draft, profile.revision, 'SHA256:new', secret), /profile_conflict/);
  assert.equal(credentials.entries.size, 1);
  assert.deepEqual(store.get('p'), renamed);
});

test('binding changes invalidate precisely; snapshots cannot mutate persisted data', async () => {
  const { credentials, store, profile } = await setup();
  const renamed = await store.saveMetadata({ ...draft, name: 'new' }, profile.revision);
  assert.equal(renamed.credentialRef, profile.credentialRef);
  const changedUser = await store.saveMetadata({ ...draft, username: 'other' }, renamed.revision);
  assert.equal(changedUser.knownHostSHA256, profile.knownHostSHA256);
  assert.equal(changedUser.credentialRef, null);
  assert.equal(credentials.entries.size, 0);
  const changedHost = await store.saveMetadata({ ...draft, host: 'other' }, changedUser.revision);
  assert.equal(changedHost.knownHostSHA256, null);
  store.snapshot().profiles[0].host = 'tampered';
  assert.equal(store.get('p')?.host, 'other');
});

test('metadata contains no secret; committed replacement survives old ref cleanup failure', async () => {
  const { kv, credentials, store, profile } = await setup();
  credentials.failDelete = true;
  const replacement = await store.commitVerified(draft, profile.revision, 'SHA256:new', secret);
  assert.notEqual(replacement.credentialRef, profile.credentialRef);
  assert.equal(replacement.revision, profile.revision + 1);
  assert.equal(Array.from(kv.entries.values()).join('').includes('secret-marker'), false);
});

test('null credential cannot create profile or reuse missing credential', async () => {
  const { credentials, store, profile } = await setup();
  await credentials.delete(profile.credentialRef!);
  await assert.rejects(store.commitVerified(draft, profile.revision, 'SHA256:new', null), /credential_missing/);
  await assert.rejects(store.saveMetadata({ ...draft, port: 0 }, profile.revision), /invalid_arguments/);
  await assert.rejects(store.saveMetadata(draft, null), /profile_conflict/);
});
