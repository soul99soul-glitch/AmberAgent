// miniapp_storage — KV 限额(单值 32KB / 每 app 128 键 / 总 512KB,键 "$appId:$key")
import test from 'node:test';
import assert from 'node:assert/strict';
import { createMemoryKeyValueStore } from '../main/ets/chat/kv_store.ts';
import type { MemoryKeyValueStore } from '../main/ets/chat/kv_store.ts';
import { MiniAppStorage } from '../main/ets/chat/miniapp/miniapp_storage.ts';
import type { MiniAppStorageOpts } from '../main/ets/chat/miniapp/miniapp_storage.ts';

const makeStore = (): MemoryKeyValueStore => createMemoryKeyValueStore();

const makeStorage = (store: MemoryKeyValueStore): MiniAppStorage => {
  const opts: MiniAppStorageOpts = {
    snapshot: async (): Promise<{ entries: Array<{ key: string; value: string }> }> => ({
      entries: [...store.entries].map(([key, value]: [string, string]) => ({ key, value })),
    }),
  };
  return new MiniAppStorage(store, opts);
};

const fails = async (fn: () => Promise<unknown>): Promise<boolean> => {
  try {
    await fn();
    return false;
  } catch (_e) {
    return true;
  }
};

test('key 模式校验:非法 key 拒绝;合法 key 放行', async () => {
  const storage: MiniAppStorage = makeStorage(makeStore());
  assert.equal(await fails((): Promise<unknown> => storage.get('a', 'bad key!')), true);
  assert.equal(await fails((): Promise<unknown> => storage.set('a', '', 'v')), true);
  assert.equal(await fails((): Promise<unknown> => storage.set('a', 'x'.repeat(65), 'v')), true);
  await storage.set('a', 'valid.key:ok', 'v');
  assert.equal(await storage.get('a', 'valid.key:ok'), 'v');
});

test('单值 32KB 限制(按 UTF-8 字节;严格大于)', async () => {
  const storage: MiniAppStorage = makeStorage(makeStore());
  // 恰好 32KB 允许(Android > MAX_VALUE_BYTES 语义)
  await storage.set('a', 'edge', 'x'.repeat(32 * 1024));
  assert.equal(await fails((): Promise<unknown> => storage.set('a', 'big', 'x'.repeat(32 * 1024 + 1))), true);
  // 中文多字节计数
  assert.equal(await fails((): Promise<unknown> => storage.set('a', 'cn', '汉'.repeat(11 * 1024))), true);
});

test('每 app 128 键限制;不同 app 独立配额', async () => {
  const storage: MiniAppStorage = makeStorage(makeStore());
  for (let i: number = 0; i < 128; i++) {
    await storage.set('app1', `k${i}`, 'v');
  }
  // 第 129 键拒绝
  assert.equal(await fails((): Promise<unknown> => storage.set('app1', 'k128', 'v')), true);
  // 覆盖已有键不计数
  await storage.set('app1', 'k0', 'v2');
  // 其它 app 不受影响
  await storage.set('app2', 'k0', 'v');
  // remove 释放键位
  await storage.remove('app1', 'k127');
  await storage.set('app1', 'k128', 'v');
});

test('每 app 总 512KB 限制', async () => {
  const storage: MiniAppStorage = makeStorage(makeStore());
  const chunk: string = 'x'.repeat(30 * 1024); // 30KB,低于单值 32KB 上限
  // 17 × 30KB = 510KB < 512KB → 成功
  for (let i: number = 0; i < 17; i++) {
    await storage.set('a', `k${i}`, chunk);
  }
  // 第 18 条 → 540KB > 512KB → 拒绝
  assert.equal(await fails((): Promise<unknown> => storage.set('a', 'k17', chunk)), true);
  // 覆盖释放旧字节后可继续
  await storage.set('a', 'k0', 'short');
  await storage.set('a', 'k17', chunk);
});

test('get/remove 语义 + 键前缀隔离', async () => {
  const storage: MiniAppStorage = makeStorage(makeStore());
  await storage.set('appA', 'k', 'va');
  await storage.set('appB', 'k', 'vb');
  assert.equal(await storage.get('appA', 'k'), 'va');
  assert.equal(await storage.get('appB', 'k'), 'vb');
  assert.equal(await storage.get('appA', 'missing'), null);
  await storage.remove('appA', 'k');
  assert.equal(await storage.get('appA', 'k'), null);
  assert.equal(await storage.get('appB', 'k'), 'vb');
});

// 未注入 snapshot 时回退 store.allEntries(设备端 Preferences getAll 语义)
test('配额核算回退 store.allEntries(无 snapshot 注入)', async () => {
  const store: MemoryKeyValueStore = makeStore();
  const storage: MiniAppStorage = new MiniAppStorage(store);
  for (let i: number = 0; i < 128; i++) {
    await storage.set('a', `k${i}`, 'v');
  }
  // 已 128 键 → 新键拒绝;覆盖已有键放行
  assert.equal(await fails((): Promise<void> => storage.set('a', 'kNew', 'v')), true);
  await storage.set('a', 'k0', 'overwrite');
  // 其他 app 的键不计入本 app 配额
  await storage.set('b', 'kOther', 'v');
});
