import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import {
  makeModelConfig, newModelId, createStorageModelRegistry, MODEL_CONFIGS_STORAGE_KEY,
} from '../main/ets/domain/model_config.ts';
import type { Storage } from '../main/ets/platform/storage.ts';

// fake Storage:原值存取(对齐 PreferencesStorage 语义,不内部 JSON 编码)
const createFakeStorage = (): Storage & { dump: () => Map<string, unknown> } => {
  const map = new Map<string, unknown>();
  return {
    dump: () => map,
    async get<T>(key: string, defaultValue: T): Promise<T> {
      return map.has(key) ? (map.get(key) as T) : defaultValue;
    },
    async set<T>(key: string, value: T): Promise<void> {
      map.set(key, value);
    },
    async delete(key: string): Promise<void> {
      map.delete(key);
    },
    async getSecret(key: string): Promise<string | null> {
      const v = map.get(key);
      return typeof v === 'string' && v.length > 0 ? v : null;
    },
    async setSecret(key: string, value: string): Promise<void> {
      map.set(key, value);
    },
    async deleteSecret(key: string): Promise<void> {
      map.delete(key);
    },
    async getAllStringEntries(): Promise<Array<{ key: string; value: string }>> {
      const out: Array<{ key: string; value: string }> = [];
      for (const [key, value] of map) {
        if (typeof value === 'string') out.push({ key, value });
      }
      return out;
    },
  };
};

test('newModelId is unique-ish and prefixed', () => {
  const a = newModelId();
  const b = newModelId();
  assert.ok(a.startsWith('m_'));
  assert.notEqual(a, b);
});

test('makeModelConfig fills defaults and generates id', () => {
  const cfg = makeModelConfig({ label: 'X' });
  assert.ok(cfg.id.length > 0);
  assert.equal(cfg.label, 'X');
  assert.equal(cfg.baseUrl, '');
  assert.equal(cfg.apiKey, '');
  assert.equal(cfg.model, '');
});

test('makeModelConfig keeps provided id', () => {
  const cfg = makeModelConfig({ id: 'keep', label: 'Y' });
  assert.equal(cfg.id, 'keep');
});

test('registry: save then list roundtrip', async () => {
  const storage = createFakeStorage();
  const reg = createStorageModelRegistry(storage);
  await reg.save([
    makeModelConfig({ id: 'a', label: 'A', baseUrl: 'http://a', apiKey: 'ka', model: 'ma' }),
    makeModelConfig({ id: 'b', label: 'B', baseUrl: 'http://b', apiKey: 'kb', model: 'mb' }),
  ]);
  const all = await reg.list();
  assert.equal(all.length, 2);
  assert.deepEqual(all.map(m => m.id), ['a', 'b']);
});

test('registry: get by id, missing returns null', async () => {
  const storage = createFakeStorage();
  const reg = createStorageModelRegistry(storage);
  await reg.save([makeModelConfig({ id: 'a', label: 'A' })]);
  const hit = await reg.get('a');
  assert.ok(hit !== null);
  assert.equal(hit.id, 'a');
  assert.equal(await reg.get('zzz'), null);
});

test('registry: sanitize assigns ids to id-less entries on save', async () => {
  const storage = createFakeStorage();
  const reg = createStorageModelRegistry(storage);
  await reg.save([
    { id: '', label: 'NoId', baseUrl: '', apiKey: 'k', model: 'm' },
  ]);
  const all = await reg.list();
  assert.equal(all.length, 1);
  assert.ok(all[0].id.length > 0, 'id assigned');
});

test('registry: empty + no legacy → empty list', async () => {
  const storage = createFakeStorage();
  const reg = createStorageModelRegistry(storage);
  assert.deepEqual(await reg.list(), []);
});

test('registry: migrates legacy single-model config', async () => {
  const storage = createFakeStorage();
  await storage.set<string>('ai_api_key', 'legacy-key');
  await storage.set<string>('ai_base_url', 'https://legacy.example');
  await storage.set<string>('ai_model', 'legacy-model');
  const reg = createStorageModelRegistry(storage);
  const all = await reg.list();
  assert.equal(all.length, 1);
  assert.equal(all[0].id, 'default');
  assert.equal(all[0].apiKey, 'legacy-key');
  assert.equal(all[0].baseUrl, 'https://legacy.example');
  assert.equal(all[0].model, 'legacy-model');
  // 迁移结果已持久化
  const persisted = storage.dump().get(MODEL_CONFIGS_STORAGE_KEY);
  assert.ok(typeof persisted === 'string' && persisted.length > 0);
});

test('registry: legacy migration uses defaults when base_url/model absent', async () => {
  const storage = createFakeStorage();
  await storage.set<string>('ai_api_key', 'k');
  const reg = createStorageModelRegistry(storage);
  const all = await reg.list();
  assert.equal(all[0].baseUrl, 'https://api.deepseek.com');
  assert.equal(all[0].model, 'deepseek-chat');
});

test('registry: corrupt JSON → empty list, no throw', async () => {
  const storage = createFakeStorage();
  await storage.set<string>(MODEL_CONFIGS_STORAGE_KEY, '{not valid json');
  const reg = createStorageModelRegistry(storage);
  assert.deepEqual(await reg.list(), []);
});

test('registry: non-array JSON → empty list', async () => {
  const storage = createFakeStorage();
  await storage.set<string>(MODEL_CONFIGS_STORAGE_KEY, '{"foo":1}');
  const reg = createStorageModelRegistry(storage);
  assert.deepEqual(await reg.list(), []);
});
