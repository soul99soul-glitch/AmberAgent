// miniapp_storage — 基于 KeyValueStore Port 的 KV 存储(键 "$appId:$key",三重限额)
//
// Android 基准: feature/miniapp/MiniAppStorage.kt(全文 63 行)
// 偏差:
//   - SharedPreferences(同步) → KeyValueStore Port(异步)→ get/set/remove 为 async
//   - prefs.all 迭代 → snapshot 注入(异步,设备端 Preferences getAll;
//     未注入则回退 store.allEntries,均无则跳过配额核算)
//   - key 正则逐字: [a-zA-Z0-9._:-]{1,64}(完整匹配)
//   - 字节计数用 utf8ByteLength(Java encodeToByteArray().size 语义)

import { MiniAppValidationException, utf8ByteLength } from './miniapp_models.ts';
import type { KeyValueStore } from '../kv_store.ts';

export interface MiniAppStorageSnapshot {
  entries: Array<{ key: string; value: string }>;
}

export interface MiniAppStorageOpts {
  // 当前 app 全部 KV 快照(用于配额核算;不传则回退 store.allEntries —
  //   内存实现必给;两者都无则跳过配额核算)
  snapshot?: () => Promise<MiniAppStorageSnapshot>;
}

const MAX_VALUE_BYTES: number = 32 * 1024;
const MAX_KEYS_PER_APP: number = 128;
const MAX_TOTAL_BYTES_PER_APP: number = 512 * 1024;
const KEY_PATTERN: RegExp = /^[a-zA-Z0-9._:-]{1,64}$/;

// appId 级互斥:配额检查是快照-校验-写入的 TOCTOU,同 app 多 Runner 并发
// set/remove 会双双基于同一旧快照通过检查并突破 128 key/512KB 上限。
// 模块级(非实例字段):跨 Storage 实例同样生效。
const appIdMutexTails: Map<string, Promise<void>> = new Map<string, Promise<void>>();

const withAppLock = <T>(appId: string, op: () => Promise<T>): Promise<T> => {
  const previous: Promise<void> = appIdMutexTails.get(appId) ?? Promise.resolve();
  const run: Promise<T> = previous.catch((): void => {}).then(op);
  const tail: Promise<void> = run.then((): void => {}, (): void => {});
  appIdMutexTails.set(appId, tail);
  return run.finally((): void => {
    if (appIdMutexTails.get(appId) === tail) appIdMutexTails.delete(appId);
  });
};

export class MiniAppStorage {
  constructor(
    private readonly store: KeyValueStore,
    private readonly opts: MiniAppStorageOpts = {},
  ) {}

  async get(appId: string, key: string): Promise<string | null> {
    this.validateKey(key);
    return this.store.get(this.storageKey(appId, key));
  }

  async set(appId: string, key: string, value: string): Promise<void> {
    this.validateKey(key);
    const valueBytes: number = utf8ByteLength(value);
    if (valueBytes > MAX_VALUE_BYTES) {
      throw new MiniAppValidationException('Storage value is too large');
    }
    return withAppLock(appId, async (): Promise<void> => {
      await this.enforceAppQuota(appId, key, valueBytes);
      await this.store.put(this.storageKey(appId, key), value);
    });
  }

  async remove(appId: string, key: string): Promise<void> {
    this.validateKey(key);
    return withAppLock(appId, async (): Promise<void> => {
      await this.store.delete(this.storageKey(appId, key));
    });
  }

  private validateKey(key: string): void {
    if (!KEY_PATTERN.test(key)) {
      throw new MiniAppValidationException('Invalid storage key');
    }
  }

  private storageKey(appId: string, key: string): string {
    return `${appId}:${key}`;
  }

  // MiniAppStorage.kt:36-55(快照异步:设备端 Preferences getAll;未配置时
  //   回退 store.allEntries,均无则跳过)
  private async enforceAppQuota(appId: string, key: string, newValueBytes: number): Promise<void> {
    const snapshot: MiniAppStorageSnapshot | null = await this.loadSnapshot();
    if (snapshot === null) return;
    const prefix: string = `${appId}:`;
    const targetKey: string = this.storageKey(appId, key);
    let count: number = 0;
    let totalBytes: number = 0;
    let oldValueBytes: number = 0;
    for (const entry of snapshot.entries) {
      if (!entry.key.startsWith(prefix)) continue;
      count++;
      const bytes: number = utf8ByteLength(entry.value);
      totalBytes += bytes;
      if (entry.key === targetKey) oldValueBytes = bytes;
    }
    if (oldValueBytes === 0 && count >= MAX_KEYS_PER_APP) {
      throw new MiniAppValidationException('Storage key limit exceeded');
    }
    if (totalBytes - oldValueBytes + newValueBytes > MAX_TOTAL_BYTES_PER_APP) {
      throw new MiniAppValidationException('Storage quota exceeded');
    }
  }

  private async loadSnapshot(): Promise<MiniAppStorageSnapshot | null> {
    if (this.opts.snapshot !== undefined) {
      return this.opts.snapshot();
    }
    if (this.store.allEntries !== undefined) {
      return { entries: await this.store.allEntries() };
    }
    return null;
  }
}
