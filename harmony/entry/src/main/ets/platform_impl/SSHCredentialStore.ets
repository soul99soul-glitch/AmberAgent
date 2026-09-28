import { asset } from '@kit.AssetStoreKit';
import { fileIo } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';
import type { Context } from '@kit.AbilityKit';
import type { BusinessError } from '@kit.BasicServicesKit';
import type {
  SSHCredentialStorePort, SSHCredentialBinding, SSHCredential, SyncCryptoPort,
} from '@amber/chat-domain';
import { encodeSSHCredential, decodeSSHCredential } from '@amber/chat-domain';
import { KeyedAsyncMutex, writeTextFileAtomic } from './AtomicJsonFile.ets';
import { createEntrySyncCryptoPort } from './SyncCryptoPort.ets';

interface CredentialEnvelope {
  version: number;
  reference: string;
  ivBase64: string;
  ciphertextBase64: string;
}

const validateReference = (reference: string): void => {
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(reference)) {
    throw new Error('credential_invalid_reference');
  }
};

const aliasQuery = (reference: string): asset.AssetMap => {
  const query: asset.AssetMap = new Map<asset.Tag, asset.Value>();
  query.set(asset.Tag.ALIAS, util.TextEncoder.create('utf-8').encodeInto(`amber-ssh-${reference}`));
  return query;
};

const queryAsset = async (reference: string, type: asset.ReturnType): Promise<asset.AssetMap | null> => {
  const query: asset.AssetMap = aliasQuery(reference);
  query.set(asset.Tag.RETURN_TYPE, type);
  try {
    const results: asset.AssetMap[] = await asset.query(query);
    if (results.length !== 1) throw new Error('credential_asset_invalid_result');
    return results[0];
  } catch (error) {
    if ((error as BusinessError).code === asset.ErrorCode.NOT_FOUND) return null;
    throw error as Error;
  }
};

const decodeEnvelope = (text: string, reference: string): CredentialEnvelope => {
  try {
    const envelope: CredentialEnvelope = JSON.parse(text) as CredentialEnvelope;
    if (envelope === null || envelope.version !== 1 || envelope.reference !== reference ||
      envelope.ivBase64.length !== 16 || !/^[A-Za-z0-9+/]{16}$/.test(envelope.ivBase64) ||
      !(envelope.ciphertextBase64.length >= 24) ||
      !/^[A-Za-z0-9+/]+={0,2}$/.test(envelope.ciphertextBase64)) throw new Error('invalid');
    return envelope;
  } catch (_error) {
    throw new Error('credential_corrupt');
  }
};

// Asset SECRET is limited to 1024 bytes. Store only the 32-byte AES key there;
// the full credential and its binding remain authenticated app-private ciphertext.
class EntrySSHCredentialStore implements SSHCredentialStorePort {
  private readonly crypto: SyncCryptoPort = createEntrySyncCryptoPort();
  private readonly mutex: KeyedAsyncMutex = new KeyedAsyncMutex();

  private path(reference: string): string {
    validateReference(reference);
    const context: Context | undefined = AppStorage.get<Context>('abilityContext');
    if (context === undefined) throw new Error('credential_context_unavailable');
    return `${context.filesDir}/amberagent/terminal/credentials/${reference}.json`;
  }

  async save(reference: string, binding: SSHCredentialBinding, credential: SSHCredential): Promise<void> {
    const path: string = this.path(reference);
    const payload: string = encodeSSHCredential(binding, credential);
    await this.mutex.withLock<void>(reference, async (): Promise<void> => {
      if (await fileIo.access(path) || await queryAsset(reference, asset.ReturnType.ATTRIBUTES) !== null) {
        throw new Error('credential_reference_exists');
      }
      const key: Uint8Array = await this.crypto.randomBytes(32);
      const plaintext: Uint8Array = util.TextEncoder.create('utf-8').encodeInto(payload);
      let keyAdded: boolean = false;
      try {
        const iv: Uint8Array = await this.crypto.randomBytes(12);
        const ivBase64: string = new util.Base64Helper().encodeToStringSync(iv);
        const ciphertext: Uint8Array = await this.crypto.encrypt(plaintext, key, ivBase64);
        const attributes: asset.AssetMap = aliasQuery(reference);
        attributes.set(asset.Tag.SECRET, key);
        attributes.set(asset.Tag.ACCESSIBILITY, asset.Accessibility.DEVICE_UNLOCKED);
        attributes.set(asset.Tag.SYNC_TYPE, asset.SyncType.NEVER);
        attributes.set(asset.Tag.CONFLICT_RESOLUTION, asset.ConflictResolution.THROW_ERROR);
        await asset.add(attributes);
        keyAdded = true;
        const envelope: CredentialEnvelope = {
          version: 1, reference: reference, ivBase64: ivBase64,
          ciphertextBase64: new util.Base64Helper().encodeToStringSync(ciphertext),
        };
        await writeTextFileAtomic(path, JSON.stringify(envelope));
      } catch (error) {
        // No old reference is touched. If staging failed after Asset.add, reclaim
        // this new reference; report a real cleanup failure instead of hiding it.
        if (keyAdded) {
          try { await this.deleteUnlocked(reference, path); }
          catch (_cleanupError) { throw new Error('credential_save_cleanup_failed'); }
        }
        throw error as Error;
      } finally {
        plaintext.fill(0);
        key.fill(0);
      }
    });
  }

  async load(reference: string, binding: SSHCredentialBinding): Promise<SSHCredential | null> {
    const path: string = this.path(reference);
    return this.mutex.withLock<SSHCredential | null>(reference, async (): Promise<SSHCredential | null> => {
      if (!await fileIo.access(path)) return null;
      const envelope: CredentialEnvelope = decodeEnvelope(await fileIo.readText(path), reference);
      const record: asset.AssetMap | null = await queryAsset(reference, asset.ReturnType.ALL);
      if (record === null) throw new Error('credential_key_missing');
      const secret: asset.Value | undefined = record.get(asset.Tag.SECRET);
      if (!(secret instanceof Uint8Array) || secret.length !== 32) throw new Error('credential_key_invalid');
      let plaintext: Uint8Array | null = null;
      try {
        const ciphertext: Uint8Array = new util.Base64Helper().decodeSync(envelope.ciphertextBase64);
        plaintext = await this.crypto.decrypt(ciphertext, secret, envelope.ivBase64);
        if (plaintext === null) throw new Error('credential_corrupt');
        const text: string = util.TextDecoder.create('utf-8', { fatal: true }).decodeToString(plaintext);
        return decodeSSHCredential(text, binding);
      } finally {
        plaintext?.fill(0);
        secret.fill(0);
      }
    });
  }

  async exists(reference: string): Promise<boolean> {
    const path: string = this.path(reference);
    return this.mutex.withLock<boolean>(reference, async (): Promise<boolean> => {
      if (!await fileIo.access(path)) return false;
      return await queryAsset(reference, asset.ReturnType.ATTRIBUTES) !== null;
    });
  }

  async delete(reference: string): Promise<void> {
    const path: string = this.path(reference);
    await this.mutex.withLock<void>(reference, (): Promise<void> => this.deleteUnlocked(reference, path));
  }

  private async deleteUnlocked(reference: string, path: string): Promise<void> {
    let fileError: Error | null = null;
    try {
      if (await fileIo.access(path)) await fileIo.unlink(path);
    } catch (error) {
      fileError = error instanceof Error ? error : new Error('credential_delete_file_failed');
    }
    try {
      await asset.remove(aliasQuery(reference));
    } catch (error) {
      if ((error as BusinessError).code !== asset.ErrorCode.NOT_FOUND) throw error as Error;
    }
    if (fileError !== null) throw fileError;
  }
}

export const createEntrySSHCredentialStore = (): SSHCredentialStorePort => new EntrySSHCredentialStore();
