// search_service.test.ts — D-065 搜索 SDK 核心
// Android 基准: search/.../SearchService.kt(全文 314 行)+ ai/util/KeyRoulette.kt(全文)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { JsonObject } from '../main/ets/chat/json.ts';
import type { SearchServiceOptions } from '../main/ets/search/search_service.ts';
import {
  SEARCH_SERVICE_TYPES,
  makeSearchServiceOptions,
  searchServiceOptionsToJson,
  searchServiceOptionsFromJson,
  createDefaultKeyRoulette,
  createLruKeyRoulette,
} from '../main/ets/search/search_service.ts';

test('fromJson 回读:未知键忽略 + 缺字段回默认 + type 路由', () => {
  const j: JsonObject = {
    type: 'tavily', id: 'fixed-id', apiKey: 'k1', unknownField: 42,
  };
  const o: SearchServiceOptions = searchServiceOptionsFromJson(j);
  assert.equal(o.type, 'tavily');
  assert.equal(o.id, 'fixed-id');
  if (o.type === 'tavily') {
    assert.equal(o.apiKey, 'k1');
    assert.equal(o.depth, 'advanced'); // 缺字段 → 默认
  }
});

test('fromJson:未知 type → 抛错(kotlinx 未知 SerialName 语义)', () => {
  assert.throws(() => searchServiceOptionsFromJson({ type: 'nope' }));
});

test('round-trip:全部 17 类 toJson→fromJson 逐字段相等', () => {
  for (const t of Object.keys(SEARCH_SERVICE_TYPES)) {
    const o = makeSearchServiceOptions(t as SearchServiceOptions['type']);
    const back: SearchServiceOptions =
      searchServiceOptionsFromJson(searchServiceOptionsToJson(o));
    assert.deepEqual(back, o, `round-trip failed for ${t}`);
  }
});

// ===== KeyRoulette(KeyRoulette.kt 全文) =====

test('default roulette:split [\\s,]+ → trim → 非空白 → distinct;空 → 原串', () => {
  const r = createDefaultKeyRoulette((): number => 0.99);
  // random()=0.99 → 末位;distinct 保首次出现序
  assert.equal(r.next('k1, k2\nk3 k2'), 'k3');
  const r0 = createDefaultKeyRoulette((): number => 0);
  assert.equal(r0.next('k1, k2'), 'k1');
  assert.equal(r0.next('   '), '   '); // 空列表 → 返回原 keys
});

test('lru roulette:未使用 key 优先,否则最久未使用', () => {
  let stored: string | null = null;
  let now = 1000;
  const r = createLruKeyRoulette({
    read: (): string | null => stored,
    write: (c: string): void => { stored = c; },
    nowMs: (): number => now,
  });
  assert.equal(r.next('k1 k2', 'p'), 'k1'); // 首个未使用
  assert.equal(r.next('k1 k2', 'p'), 'k2'); // k1 已用 → k2
  assert.equal(r.next('k1 k2', 'p'), 'k1'); // 均用过 → 最久未用(k1)
});

test('lru roulette:24h 过期条目视为未使用;不在当前列表的键被过滤', () => {
  const DAY: number = 24 * 60 * 60 * 1000;
  const stored: string = JSON.stringify({ p: { k1: 0, stale: 0 } });
  const r = createLruKeyRoulette({
    read: (): string | null => stored,
    write: (): void => {},
    nowMs: (): number => DAY + 1000,
  });
  // k1 已过期 → 视为未使用;stale 不在 keyList 被过滤
  assert.equal(r.next('k1 k2', 'p'), 'k1');
});

test('lru roulette:持久化结构 Map<providerId, Map<key, ts>>;他 provider 全过期清理', () => {
  const DAY: number = 24 * 60 * 60 * 1000;
  let written: string = '';
  const stored: string = JSON.stringify({
    p: { k1: 500 },
    old: { x: 0 }, // 全过期 → 清理
  });
  const r = createLruKeyRoulette({
    read: (): string | null => stored,
    write: (c: string): void => { written = c; },
    nowMs: (): number => DAY + 500,
  });
  r.next('k1', 'p');
  const parsed = JSON.parse(written) as JsonObject;
  assert.deepEqual(Object.keys(parsed), ['p']);
  assert.equal((parsed['p'] as JsonObject)['k1'], DAY + 500);
});

test('lru roulette:load 失败 → 空缓存;空 key 列表 → 原串且不写盘', () => {
  let writes = 0;
  const r = createLruKeyRoulette({
    read: (): string | null => '{bad json',
    write: (): void => { writes++; },
    nowMs: (): number => 0,
  });
  assert.equal(r.next('k1', 'p'), 'k1'); // 坏 JSON → 空缓存 → 首个未使用
  assert.equal(writes, 1);
  assert.equal(r.next('  ', 'p'), '  '); // 空 → 原串,无读写
  assert.equal(writes, 1);
});
