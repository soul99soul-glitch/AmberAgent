import type { KeyValueStore } from '../kv_store.ts';
import { newId } from '../ids.ts';
import type { SSHCredential, SSHCredentialBinding, SSHKey, SSHKeyProtection,
  SSHGeneratedKeyMaterial, SSHInspectedKeyMaterial } from './models.ts';
import type { SSHCredentialStorePort, SSHKeyCryptoPort } from './ports.ts';
import { TerminalError } from './profile_store.ts';
import { isRedactedPlaceholder } from '../tool_input_redaction.ts';

export interface SSHKeyStoreDeps {
  kv: KeyValueStore;
  credentials: SSHCredentialStorePort;
  crypto: SSHKeyCryptoPort;
  defaultProtection?: SSHKeyProtection;
}

const KEY_STORE_KV_KEY: string = 'terminal_ssh_keys';
const KEY_BINDING_HOST: string = '__amber_ssh_key__';

const keyBindingFor = (keyId: string): SSHCredentialBinding => ({
  host: KEY_BINDING_HOST,
  port: 22,
  username: keyId,
  authMethod: 'privateKey',
});

const copyKey = (k: SSHKey): SSHKey => ({
  id: k.id,
  name: k.name,
  algorithm: k.algorithm,
  publicKey: k.publicKey,
  fingerprintSHA256: k.fingerprintSHA256,
  origin: k.origin,
  protection: k.protection,
  hasPassphrase: k.hasPassphrase,
  requireUserPresence: k.requireUserPresence,
  credentialRef: k.credentialRef,
  createdAtMs: k.createdAtMs,
});

export class SSHKeyStore {
  private keys: SSHKey[] = [];
  private pending: Promise<void> = Promise.resolve();
  private listeners: Array<(keys: SSHKey[]) => void> = [];
  private readonly crypto: SSHKeyCryptoPort;
  private readonly protection: SSHKeyProtection;
  private profileUsage: ((keyId: string) => string[]) | null = null;

  private constructor(private readonly deps: SSHKeyStoreDeps) {
    this.crypto = deps.crypto;
    this.protection = deps.defaultProtection ?? 'asset_encrypted';
  }

  static async create(deps: SSHKeyStoreDeps): Promise<SSHKeyStore> {
    const store: SSHKeyStore = new SSHKeyStore(deps);
    const raw: string | null = await deps.kv.get(KEY_STORE_KV_KEY);
    if (raw !== null) {
      const parsed: unknown = JSON.parse(raw);
      if (Array.isArray(parsed)) {
        store.keys = (parsed as SSHKey[]).map(copyKey);
      }
    }
    return store;
  }

  list(): SSHKey[] {
    return this.keys.map(copyKey);
  }

  get(id: string): SSHKey | null {
    const found: SSHKey | undefined = this.keys.find((k: SSHKey): boolean => k.id === id);
    return found === undefined ? null : copyKey(found);
  }

  subscribe(listener: (keys: SSHKey[]) => void): () => void {
    this.listeners.push(listener);
    listener(this.list());
    return (): void => {
      this.listeners = this.listeners.filter((v): boolean => v !== listener);
    };
  }

  setProfileUsageResolver(resolver: (keyId: string) => string[]): void { this.profileUsage = resolver; }

  withProfileWrite<T>(operation: () => Promise<T>): Promise<T> { return this.serial(operation); }

  private serial<T>(operation: () => Promise<T>): Promise<T> {
    const run: Promise<T> = this.pending.then(operation);
    this.pending = run.then((): void => undefined, (): void => undefined);
    return run;
  }

  private async persist(next: SSHKey[]): Promise<void> {
    await this.deps.kv.put(KEY_STORE_KV_KEY, JSON.stringify(next));
    this.keys = next;
    for (const listener of this.listeners.slice()) {
      try { listener(this.list()); } catch { /* observer error cannot roll back committed state */ }
    }
  }

  private assertNameUnique(name: string): string {
    const trimmed: string = name.trim();
    if (trimmed.length === 0 || trimmed.length > 64 || trimmed.indexOf('\0') >= 0) {
      throw new TerminalError('invalid_arguments', 'SSH key name must be 1 to 64 characters');
    }
    const lower: string = trimmed.toLowerCase();
    for (const existing of this.keys) {
      if (existing.name.trim().toLowerCase() === lower) {
        throw new TerminalError('key_name_taken', `An SSH key named '${trimmed}' already exists.`);
      }
    }
    return trimmed;
  }

  private wrapCryptoError(error: unknown): never {
    if (error instanceof TerminalError) throw error;
    const coded = error as { code?: string; message?: string };
    if (typeof coded?.code === 'string') {
      throw new TerminalError(coded.code, coded.message ?? coded.code);
    }
    throw new TerminalError('unsupported_key', error instanceof Error ? error.message : 'SSH key operation failed');
  }

  generateKey(
    name: string,
    type: 'ed25519' | 'ecdsa-p256' = 'ed25519',
    requireUserPresence: boolean = false,
  ): Promise<SSHKey> {
    return this.serial(async (): Promise<SSHKey> => {
      const cleanName: string = this.assertNameUnique(name);
      if (requireUserPresence) throw new TerminalError('invalid_arguments', 'User-presence keys are not supported on this platform.');
      if (type !== 'ed25519' && type !== 'ecdsa-p256') {
        throw new TerminalError('invalid_arguments', 'SSH key type must be ed25519 or ecdsa-p256');
      }
      let generated: SSHGeneratedKeyMaterial;
      try {
        generated = await this.crypto.generateKey(cleanName, type);
      } catch (error) {
        this.wrapCryptoError(error);
      }
      const keyId: string = `key_${newId()}`;
      const credentialRef: string = newId();
      try {
        await this.deps.credentials.save(credentialRef, keyBindingFor(keyId), {
          secret: generated.privateKey, passphrase: null,
        });
      } finally { generated.privateKey = ''; }
      const record: SSHKey = {
        id: keyId,
        name: cleanName,
        algorithm: generated.algorithm,
        publicKey: generated.publicKey,
        fingerprintSHA256: generated.fingerprintSHA256,
        origin: 'generated',
        protection: this.protection,
        hasPassphrase: false,
        requireUserPresence,
        credentialRef,
        createdAtMs: Date.now(),
      };
      try {
        await this.persist([...this.keys, record]);
      } catch (error) {
        await this.deps.credentials.delete(credentialRef).catch((): void => undefined);
        throw error;
      }
      return copyKey(record);
    });
  }

  importKey(
    name: string,
    privateKey: string,
    passphrase: string | null = null,
    requireUserPresence: boolean = false,
  ): Promise<SSHKey> {
    return this.serial(async (): Promise<SSHKey> => {
      const cleanName: string = this.assertNameUnique(name);
      if (requireUserPresence) throw new TerminalError('invalid_arguments', 'User-presence keys are not supported on this platform.');
      if (!privateKey || privateKey.trim().length === 0 || privateKey.length > 65536 || privateKey.indexOf('\0') >= 0) {
        throw new TerminalError('unsupported_key', 'SSH private key is empty or invalid');
      }
      if (isRedactedPlaceholder(privateKey) || isRedactedPlaceholder(passphrase)) {
        throw new TerminalError('credential_unavailable');
      }
      let inspected: SSHInspectedKeyMaterial;
      try {
        inspected = await this.crypto.inspectKey(cleanName, privateKey, passphrase);
      } catch (error) {
        this.wrapCryptoError(error);
      }
      const keyId: string = `key_${newId()}`;
      const credentialRef: string = newId();
      await this.deps.credentials.save(credentialRef, keyBindingFor(keyId), {
        secret: privateKey,
        passphrase: inspected.hasPassphrase ? passphrase : null,
      });
      const record: SSHKey = {
        id: keyId,
        name: cleanName,
        algorithm: inspected.algorithm,
        publicKey: inspected.publicKey,
        fingerprintSHA256: inspected.fingerprintSHA256,
        origin: 'imported',
        protection: this.protection,
        hasPassphrase: inspected.hasPassphrase,
        requireUserPresence,
        credentialRef,
        createdAtMs: Date.now(),
      };
      try {
        await this.persist([...this.keys, record]);
      } catch (error) {
        await this.deps.credentials.delete(credentialRef).catch((): void => undefined);
        throw error;
      }
      return copyKey(record);
    });
  }

  async loadCredential(keyId: string): Promise<SSHCredential | null> {
    const record: SSHKey | null = this.get(keyId);
    if (record === null) return null;
    return this.deps.credentials.load(record.credentialRef, keyBindingFor(record.id));
  }

  credentialExists(keyId: string): Promise<boolean> {
    const record: SSHKey | null = this.get(keyId);
    if (record === null) return Promise.resolve(false);
    return this.deps.credentials.exists(record.credentialRef).catch((): boolean => false);
  }

  deleteKey(keyId: string): Promise<SSHKey> {
    return this.serial(async (): Promise<SSHKey> => {
      const record: SSHKey | null = this.get(keyId);
      if (record === null) {
        throw new TerminalError('key_missing', `No saved SSH key with id '${keyId}'.`);
      }
      if (this.profileUsage === null) throw new TerminalError('profile_store_unavailable');
      const usedByProfileIds: string[] = this.profileUsage(keyId);
      if (usedByProfileIds.length > 0) {
        throw new TerminalError(
          'key_in_use',
          `SSH key '${record.name}' (${keyId}) is referenced by profiles and cannot be deleted.`,
          { used_by_profile_ids: usedByProfileIds },
        );
      }
      const next: SSHKey[] = this.keys.filter((k: SSHKey): boolean => k.id !== keyId);
      await this.persist(next);
      await this.deps.credentials.delete(record.credentialRef);
      return record;
    });
  }
}
