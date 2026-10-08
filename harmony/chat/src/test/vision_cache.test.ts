// vision_cache 规格测试(D-071a)
// Android 基准: common/cache/LruCache.kt(全文)+ OcrTransformer.kt:32-46
//   capacity 64 / deleteOnEvict / preloadFromStore / expireAfterWrite 3 天
import { test } from 'node:test';
import assert from 'node:assert/strict';

import { createVisionCache } from '../main/ets/chat/vision_cache.ts';
import type { VisionCacheFileStore } from '../main/ets/chat/vision_cache.ts';

const DAY_MS: number = 24 * 60 * 60 * 1000;

interface MemStore extends VisionCacheFileStore { dump(): string | null; }

const memStore = (initial: string | null = null): MemStore => {
  let content: string | null = initial;
  return {
    read: (): string | null => content,
    write: (c: string): void => { content = c; },
    dump: (): string | null => content,
  };
};

test('capacity 64:第 65 项逐出最旧(deleteOnEvict → store 同步移除)', () => {
  const store = memStore();
  const cache = createVisionCache(store);
  for (let i = 0; i < 65; i++) cache.put(`k${i}`, `v${i}`);
  assert.equal(cache.size(), 64);
  assert.equal(cache.get('k0'), null, '最旧被逐出');
  assert.equal(cache.get('k64'), 'v64');
  const persisted = JSON.parse(store.dump() ?? '{}') as Record<string, unknown>;
  assert.equal('k0' in persisted, false, 'store 同步移除');
  assert.equal('k64' in persisted, true);
});

test('accessOrder:get 命中重排到最新,逐出让位给其他键', () => {
  const cache = createVisionCache(memStore());
  for (let i = 0; i < 64; i++) cache.put(`k${i}`, `v${i}`);
  assert.equal(cache.get('k0'), 'v0', '访问 k0 → 变最新');
  cache.put('k64', 'v64'); // 逐出当前最旧 = k1
  assert.equal(cache.get('k0'), 'v0', 'k0 因重排存活');
  assert.equal(cache.get('k1'), null, 'k1 被逐出');
});

test('expireAfterWrite 3 天:过期 get → null 且 store 移除', () => {
  let now = 1000;
  const store = memStore();
  const cache = createVisionCache(store, (): number => now);
  cache.put('k', 'v');
  now += 3 * DAY_MS + 1;
  assert.equal(cache.get('k'), null);
  const persisted = JSON.parse(store.dump() ?? '{}') as Record<string, unknown>;
  assert.equal('k' in persisted, false);
});

test('preloadFromStore:建缓存即加载;过期项移除;读失败按空', () => {
  const good = { value: 'v', expiresAt: Date.now() + DAY_MS };
  const stale = { value: 'old', expiresAt: 1 };
  const store = memStore(JSON.stringify({ good, stale }));
  const cache = createVisionCache(store);
  assert.equal(cache.get('good'), 'v', 'preload 命中');
  assert.equal(cache.get('stale'), null, '过期项不回');
  const persisted = JSON.parse(store.dump() ?? '{}') as Record<string, unknown>;
  assert.equal('stale' in persisted, false, 'preload 过期项从 store 移除');

  // 读失败(解析错误)→ 空缓存,不抛
  const broken = createVisionCache(memStore('{not json'));
  assert.equal(broken.get('x'), null);
  broken.put('x', 'y');
  assert.equal(broken.get('x'), 'y');
});

test('preload 达 capacity 即停(LruCache.kt preload break)', () => {
  const seed: Record<string, { value: string; expiresAt: number }> = {};
  for (let i = 0; i < 65; i++) seed[`k${i}`] = { value: `v${i}`, expiresAt: Date.now() + DAY_MS };
  const cache = createVisionCache(memStore(JSON.stringify(seed)));
  assert.equal(cache.size(), 64);
});

test('remove:内存与 store 同步删除;store 写失败吞咽(平台缓存非业务数据)', () => {
  const store = memStore();
  const cache = createVisionCache(store);
  cache.put('k', 'v');
  cache.remove('k');
  assert.equal(cache.get('k'), null);
  assert.equal('k' in (JSON.parse(store.dump() ?? '{}') as Record<string, unknown>), false);

  const failingStore: VisionCacheFileStore = {
    read: (): string | null => { throw new Error('io'); },
    write: (_c: string): void => { throw new Error('io'); },
  };
  const cache2 = createVisionCache(failingStore);
  cache2.put('a', 'b'); // 不抛
  assert.equal(cache2.get('a'), 'b', 'store 故障时内存缓存仍可用');
});
