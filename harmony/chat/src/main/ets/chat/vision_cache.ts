// vision_cache — 视觉识别缓存(D-071a)
// Android 基准:
//   common/cache/LruCache.kt(全文:capacity/LinkedHashMap accessOrder/deleteOnEvict/
//     preload/expireAfterWrite/get 过期回源)
//   OcrTransformer.kt:32-46 — capacity 64, SingleFileCacheStore(vision_cache.json),
//     deleteOnEvict=true, preloadFromStore=true, expireAfterWrite 3 天
// 偏差:CacheStore 按键接口 → 单文件整写 Port(read/write 全量 JSON;
//   saveEntry/remove/clear 均为改后整写,与 SingleFileCacheStore 同语义);
//   所有 store 异常吞咽(Android catch {} 逐字语义)

interface CacheEntry {
  value: string;
  expiresAt: number | null;
}

export interface VisionCacheFileStore {
  // 读全量 JSON;不存在 → null;解析失败按空(Android catch → 空)
  read(): string | null;
  // 写失败吞咽(平台缓存,非业务数据)
  write(content: string): void;
}

export interface VisionCache {
  get(key: string): string | null;
  put(key: string, value: string): void;
  remove(key: string): void;
  size(): number;
}

const CAPACITY: number = 64;
const EXPIRE_AFTER_WRITE_MS: number = 3 * 24 * 60 * 60 * 1000; // 3.days

export const createVisionCache = (
  store: VisionCacheFileStore, nowMs: () => number = (): number => Date.now(),
): VisionCache => {
  // LinkedHashMap(accessOrder=true) → JS Map 手动 delete+set 重排
  const map = new Map<string, CacheEntry>();
  // 单文件内容(键 → entry);首次访问惰性读
  let storeLoaded: boolean = false;
  const storeData = new Map<string, CacheEntry>();

  const isExpired = (e: CacheEntry): boolean =>
    e.expiresAt !== null && nowMs() > e.expiresAt;

  const loadStore = (): void => {
    if (storeLoaded) return;
    storeLoaded = true;
    try {
      const raw: string | null = store.read();
      if (raw === null) return;
      const parsed = JSON.parse(raw) as Record<string, { value: string; expiresAt: number | null }>;
      if (typeof parsed !== 'object' || parsed === null) return;
      for (const k of Object.keys(parsed)) {
        const e = parsed[k];
        if (typeof e === 'object' && e !== null && typeof e.value === 'string') {
          storeData.set(k, { value: e.value, expiresAt: typeof e.expiresAt === 'number' ? e.expiresAt : null });
        }
      }
    } catch {
      // 解析失败按空缓存
    }
  };

  const persistStore = (): void => {
    const out: Record<string, { value: string; expiresAt: number | null }> = {};
    for (const [k, e] of storeData) out[k] = { value: e.value, expiresAt: e.expiresAt };
    try {
      store.write(JSON.stringify(out));
    } catch {
      // Android catch {} 静默
    }
  };

  const storeRemove = (key: string): void => {
    try {
      if (storeData.delete(key)) persistStore();
    } catch {
      // 静默
    }
  };

  // removeEldestEntry:size > capacity → 逐最旧(deleteOnEvict → store.remove)
  const evictIfNeeded = (): void => {
    while (map.size > CAPACITY) {
      const eldest: string | undefined = map.keys().next().value as string | undefined;
      if (eldest === undefined) break;
      map.delete(eldest);
      storeRemove(eldest);
    }
  };

  // preloadFromStore:读全量,过期项从 store 移除,map 达 capacity 即停
  loadStore();
  {
    const now: number = nowMs();
    for (const [k, entry] of storeData) {
      if (!isExpired(entry)) {
        map.set(k, entry);
      } else {
        storeRemove(k);
      }
      if (map.size >= CAPACITY) break;
    }
  }

  return {
    get: (key: string): string | null => {
      loadStore();
      const memEntry: CacheEntry | undefined = map.get(key);
      if (memEntry !== undefined) {
        if (!isExpired(memEntry)) {
          // accessOrder:命中重排到最新
          map.delete(key);
          map.set(key, memEntry);
          return memEntry.value;
        }
        map.delete(key);
        // 过期后**回源 store**(LruCache.kt get:内存过期仍查 store)
      }
      const storeEntry: CacheEntry | undefined = storeData.get(key);
      if (storeEntry !== undefined) {
        if (!isExpired(storeEntry)) {
          map.set(key, storeEntry);
          evictIfNeeded();
          return storeEntry.value;
        }
        storeRemove(key);
        return null;
      }
      return null;
    },

    put: (key: string, value: string): void => {
      loadStore();
      const entry: CacheEntry = { value, expiresAt: nowMs() + EXPIRE_AFTER_WRITE_MS };
      // 重复 key 先删后插(保持插入序 = 最新)
      if (map.has(key)) map.delete(key);
      map.set(key, entry);
      evictIfNeeded();
      try {
        storeData.set(key, entry);
        persistStore();
      } catch {
        // 静默
      }
    },

    remove: (key: string): void => {
      map.delete(key);
      storeRemove(key);
    },

    size: (): number => map.size,
  };
};
