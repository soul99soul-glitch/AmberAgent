import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildSyncSettingsBlob, applySyncRestoreSettings } from '../main/ets/chat/settings_backup.ts';
import {
  createMemoryKeyValueStore, loadAssistants, loadProviders, saveAssistants, saveProviders,
  ASSISTANTS_KEY, PROVIDERS_KEY, type KeyValueStore,
} from '../main/ets/chat/kv_store.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import {
  makeProviderModel, makeProviderSettingOpenAIVariant, makeProviderSettingGoogle,
} from '../main/ets/chat/provider_settings.ts';
import {
  buildSyncPayload, settingsFromSyncPayload, createSyncBackup, restoreSyncBackup, type SyncCryptoPort,
} from '../main/ets/chat/sync_snapshot.ts';

// 用 Node 真正 AES-GCM 验证归档组装及恢复；设备密码学实现不在本测试范围。
import { createCipheriv, createDecipheriv, createHash, randomBytes } from 'node:crypto';
const cryptoPort: SyncCryptoPort = {
  deriveKey: async (pass, salt) => createHash('sha256').update(pass).update(salt).digest(),
  encrypt: async (plain, key, iv) => {
    const cipher = createCipheriv('aes-256-gcm', key, Buffer.from(iv, 'base64'));
    return Buffer.concat([cipher.update(plain), cipher.final(), cipher.getAuthTag()]);
  },
  decrypt: async (encrypted, key, iv) => {
    try {
      const decipher = createDecipheriv('aes-256-gcm', key, Buffer.from(iv, 'base64'));
      decipher.setAuthTag(encrypted.slice(-16));
      return Buffer.concat([decipher.update(encrypted.slice(0, -16)), decipher.final()]);
    } catch { return null; }
  },
  sha256Hex: async (bytes) => createHash('sha256').update(bytes).digest('hex'),
  randomBytes: async (n) => randomBytes(n),
};

test('Backup entry settings assembler survives archive restore with assistant enums, model types and credentials', async () => {
  const kv = createMemoryKeyValueStore();
  const assistants = [makeAssistant({ name: 'Default', localTools: ['time_info', 'workspace_files'] })];
  const nested = makeProviderSettingGoogle({ apiKey: 'nested-secret' });
  const providers = [makeProviderSettingOpenAIVariant({
    apiKey: 'provider-secret',
    models: [makeProviderModel({ modelId: 'm', type: 'chat', providerOverwrite: nested })],
  })];
  await saveAssistants(kv, assistants);
  await saveProviders(kv, providers);
  const settings = await buildSyncSettingsBlob(kv, true, true);
  const bytes = await createSyncBackup(cryptoPort, {
    conversations: [], settings, appVersionName: 'harmony-preview',
    appVersionCode: 1, deviceId: 'harmony', passphrase: 'secret',
  }, 1000);
  const restored = await restoreSyncBackup(cryptoPort, bytes, 'secret');
  assert.deepEqual(settingsFromSyncPayload(restored.payload), { assistants, providers });
  assert.equal(settingsFromSyncPayload(restored.payload).providers?.[0].apiKey, 'provider-secret');
  assert.equal(settingsFromSyncPayload(restored.payload).providers?.[0].models[0].providerOverwrite?.apiKey,
    'nested-secret');
});

test('Backup categories skip unselected storage and missing keys; empty selected lists remain present', async () => {
  for (const includeAssistants of [false, true]) {
    for (const includeProviders of [false, true]) {
      const kv = createMemoryKeyValueStore();
      await saveAssistants(kv, []);
      await saveProviders(kv, []);
      const reads: string[] = [];
      const selectedKv: KeyValueStore = {
        get: async (key) => { reads.push(key); return kv.get(key); },
        put: (key, value) => kv.put(key, value), delete: (key) => kv.delete(key),
      };
      const settings = await buildSyncSettingsBlob(selectedKv, includeAssistants, includeProviders);
      assert.deepEqual(reads, [
        ...(includeAssistants ? [ASSISTANTS_KEY] : []), ...(includeProviders ? [PROVIDERS_KEY] : []),
      ]);
      const parsed = settingsFromSyncPayload(buildSyncPayload({
        conversations: [], settings, appVersionName: '1', appVersionCode: 1,
        deviceId: 'd', passphrase: null,
      }, 1));
      assert.deepEqual(parsed, {
        assistants: includeAssistants ? [] : null, providers: includeProviders ? [] : null,
      });
    }
  }
  assert.deepEqual(await buildSyncSettingsBlob(createMemoryKeyValueStore(), true, true), {});
});

test('Provider-only backup converts domain model types to the sync wire format', async () => {
  const kv = createMemoryKeyValueStore();
  const provider = makeProviderSettingOpenAIVariant({
    models: [makeProviderModel({ modelId: 'm', type: 'chat' })],
  });
  await saveProviders(kv, [provider]);
  const settings = await buildSyncSettingsBlob(kv, false, true);
  const payload = buildSyncPayload({
    conversations: [], settings, appVersionName: '1', appVersionCode: 1,
    deviceId: 'd', passphrase: null,
  }, 1);
  assert.deepEqual(settingsFromSyncPayload(payload).providers, [provider]);
});

test('Restore settings pre-read failure returns a settings-specific message and never writes or rolls back', async () => {
  for (const failedKey of [ASSISTANTS_KEY, PROVIDERS_KEY]) {
    const writes: string[] = [];
    const kv: KeyValueStore = {
      get: async (key) => {
        if (key === failedKey) throw new Error('storage unavailable');
        return '[]';
      },
      put: async (key) => { writes.push(key); }, delete: async (key) => { writes.push(key); },
    };
    const note = await applySyncRestoreSettings({ assistants: [makeAssistant()], providers: [] }, kv);
    assert.match(note ?? '', /settings.*失败.*storage unavailable/);
    assert.deepEqual(writes, []);
  }
});

test('Restore settings preserves unselected data and rolls both selected values back after a later write fails', async () => {
  const kv = createMemoryKeyValueStore();
  const assistants = [makeAssistant({ name: 'Old' })];
  const providers = [makeProviderSettingOpenAIVariant({ name: 'Old Provider' })];
  await saveAssistants(kv, assistants);
  await saveProviders(kv, providers);
  const changed = [makeAssistant({ name: 'New' })];
  const note = await applySyncRestoreSettings({ assistants: changed, providers: null }, kv);
  assert.equal(note, '（已覆盖 settings）');
  assert.deepEqual(await loadProviders(kv), providers);
  await saveAssistants(kv, assistants);
  let failed = false;
  const failOnceKv: KeyValueStore = {
    get: (key) => kv.get(key),
    put: async (key, value) => {
      if (key === PROVIDERS_KEY && !failed) { failed = true; throw new Error('write failed'); }
      await kv.put(key, value);
    },
    delete: (key) => kv.delete(key),
  };
  const rollbackNote = await applySyncRestoreSettings({ assistants: changed, providers: [] }, failOnceKv);
  assert.match(rollbackNote ?? '', /失败，已回滚/);
  assert.deepEqual(await loadAssistants(kv), assistants);
  assert.deepEqual(await loadProviders(kv), providers);
});
