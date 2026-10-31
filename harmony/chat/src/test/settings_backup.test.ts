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

test('Backup entry settings assembler survives archive restore with assistant enums and model types', async () => {
  const kv = createMemoryKeyValueStore();
  const assistants = [makeAssistant({ name: 'Default', localTools: ['time_info', 'workspace_files'] })];
  const nested = makeProviderSettingGoogle({ apiKey: 'nested-secret' });
  const providers = [makeProviderSettingOpenAIVariant({
    name: 'Provider',
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
  const restoredSettings = settingsFromSyncPayload(restored.payload);
  // 结构与非密字段必须完整回环:H4.3 的脱敏不能顺手削掉模型类型/助手枚举
  assert.deepEqual(restoredSettings.assistants, assistants);
  assert.equal(restoredSettings.providers?.length, 1);
  assert.equal(restoredSettings.providers?.[0].type, 'openai');
  assert.equal(restoredSettings.providers?.[0].name, 'Provider');
  assert.equal(restoredSettings.providers?.[0].models[0].modelId, 'm');
  assert.equal(restoredSettings.providers?.[0].models[0].providerOverwrite?.type, 'google');
  // H4.3:凭据不进导出载荷,恢复后需重新填写
  assert.equal(restoredSettings.providers?.[0].apiKey, '');
  assert.equal(restoredSettings.providers?.[0].models[0].providerOverwrite?.apiKey, '');
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

// ===== H4.3 导出边界:备份载荷不含明文凭据 =====
// Android SyncPayloadExporter:52-70 走 redactForExport,iOS IOSSyncBackup.export:52-56
// 走 IOSCredentialRedactor,两端都明确「加密不能代替脱敏」。加密开关是可选的,关掉就是
// 明文文件,因此脱敏必须在组装载荷时就完成,而不是靠用户勾选密码。

const providerWithEverySecret = () => makeProviderSettingOpenAIVariant({
  id: 'p1', name: 'Provider p1', baseUrl: 'https://api.example.com/v1',
  apiKey: 'sk-provider-plaintext',
  models: [makeProviderModel({
    modelId: 'm', type: 'chat',
    customHeaders: [{ name: 'X-Auth', value: 'header-plaintext' }],
    providerOverwrite: makeProviderSettingGoogle({
      id: 'ov', name: 'Override', apiKey: 'sk-override-plaintext',
      privateKey: 'PEM-private-plaintext',
    }),
  })],
});

test('the exported settings blob carries no plaintext provider credential', async () => {
  const kv = createMemoryKeyValueStore();
  await saveAssistants(kv, [makeAssistant({
    id: 'a1',
    name: 'Assistant 1',
    customHeaders: [{ name: 'Authorization', value: 'Bearer asst-header-secret' }],
  })]);
  await saveProviders(kv, [providerWithEverySecret()]);
  const settings = await buildSyncSettingsBlob(kv, true, true);
  const json = JSON.stringify(settings);

  assert.equal(json.includes('Bearer asst-header-secret'), false, 'assistant custom header value must not be exported');
  assert.equal(json.includes('Authorization'), true, 'assistant custom header name stays');
  assert.equal(json.includes('sk-provider-plaintext'), false, 'provider api key must not be exported');
  assert.equal(json.includes('header-plaintext'), false, 'model custom header value must not be exported');
  assert.equal(json.includes('sk-override-plaintext'), false, 'providerOverwrite api key must not be exported');
  assert.equal(json.includes('PEM-private-plaintext'), false, 'service account private key must not be exported');
  // 非密配置仍然要完整,否则恢复出来的服务商不可用
  assert.equal(json.includes('https://api.example.com/v1'), true);
  assert.equal(json.includes('X-Auth'), true, 'header names are not secrets');
});

test('an unencrypted backup archive contains no plaintext credential either', async () => {
  const kv = createMemoryKeyValueStore();
  await saveProviders(kv, [providerWithEverySecret()]);
  const settings = await buildSyncSettingsBlob(kv, true, true);
  const bytes = await createSyncBackup(cryptoPort, {
    conversations: [], settings, appVersionName: '1', appVersionCode: 1,
    deviceId: 'd', passphrase: null,
  }, 1);

  // 无密码备份的载荷是密文,但密钥派生自固定的空串——这里直接把归档解码后
  // 用同一 cryptoPort 解到明文,模拟拿到文件的人。
  const restored = await restoreSyncBackup(cryptoPort, bytes, null);
  const payloadJson = JSON.stringify(restored.payload);
  assert.equal(payloadJson.includes('sk-provider-plaintext'), false);
  assert.equal(payloadJson.includes('header-plaintext'), false);
  assert.equal(payloadJson.includes('sk-override-plaintext'), false);
  assert.equal(payloadJson.includes('PEM-private-plaintext'), false);
});

test('restoring a redacted providers section keeps the provider usable but asks for the key again', async () => {
  const kv = createMemoryKeyValueStore();
  const provider = providerWithEverySecret();
  await saveProviders(kv, [provider]);
  const settings = await buildSyncSettingsBlob(kv, false, true);

  const restoredProviders = settingsFromSyncPayload(buildSyncPayload({
    conversations: [], settings, appVersionName: '1', appVersionCode: 1,
    deviceId: 'd', passphrase: 'pw',
  }, 1)).providers;
  assert.equal(restoredProviders?.length, 1);
  const restored = restoredProviders![0];
  assert.equal(restored.id, 'p1');
  assert.equal(restored.name, 'Provider p1');
  assert.equal(restored.type, 'openai');
  assert.equal(restored.models[0].modelId, 'm');
  assert.equal(restored.models[0].customHeaders[0].name, 'X-Auth');
  assert.equal(restored.models[0].customHeaders[0].value, '', 'header value is redacted');
  assert.equal(restored.apiKey, '');
});
