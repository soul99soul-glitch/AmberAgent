// miniapp_runtime — EventBus ≤20 订阅 / LaunchLimiter 30s≤3 次 / AiBudget 每日 50(MiniAppV3Runtime.kt)
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  createMiniAppEventBus, createMiniAppLaunchLimiter, createMiniAppAiBudget,
  MINI_APP_EVENT_BUS_MAX_SUBSCRIPTIONS_PER_APP,
  MINI_APP_AI_DAILY_BUDGET,
} from '../main/ets/chat/miniapp/miniapp_runtime.ts';
import type { MiniAppEventBus } from '../main/ets/chat/miniapp/miniapp_runtime.ts';
import { createMemoryKeyValueStore } from '../main/ets/chat/kv_store.ts';

test('eventBus: 每 app ≤ 20 订阅;不同 app 独立;发布按 namespace+topic;退订生效', () => {
  let n: number = 0;
  const bus: MiniAppEventBus = createMiniAppEventBus((): string => `sub-${++n}`);
  for (let i: number = 0; i < MINI_APP_EVENT_BUS_MAX_SUBSCRIPTIONS_PER_APP; i++) {
    bus.subscribe('app-a', 'ns', 'topic', (): void => {});
  }
  // app-a 第 21 个拒绝
  assert.throws((): string => bus.subscribe('app-a', 'ns', 'topic', (): void => {}));
  // 其它 app 不受影响
  const other: string = bus.subscribe('app-b', 'ns', 'topic', (): void => {});
  // 发布到 namespace+topic 命中回调;退订后不再收到
  let received: number = 0;
  const id: string = bus.subscribe('app-c', 'nsx', 'topic', (): void => {
    received++;
  });
  bus.subscribe('app-c', 'nsx', 'other-topic', (): void => {
    received++;
  });
  bus.publish('nsx', 'topic', { v: 1 });
  assert.equal(received, 1);
  bus.unsubscribe(id);
  bus.publish('nsx', 'topic', { v: 2 });
  assert.equal(received, 1);
  void other;
});

test('launchLimiter: 30 秒窗口内最多 3 次,超限拒绝,窗口过期释放', () => {
  let now: number = 1000;
  const limiter = createMiniAppLaunchLimiter((): number => now);
  limiter.check();
  limiter.check();
  limiter.check();
  assert.throws((): void => limiter.check()); // 窗内 3 条 → 拒绝
  now += 30_001; // 窗口过期,旧条目被淘汰
  limiter.check(); // 放行
  limiter.check();
  limiter.check();
  assert.throws((): void => limiter.check()); // 新一轮 3 条 → 拒绝
});

test('aiBudget: 每日 50 次预算,KV 持久化可选', async () => {
  const store = createMemoryKeyValueStore();
  const budget = createMiniAppAiBudget({
    appId: 'app-1',
    dayKey: (): string => '2026-08-21',
    store,
  });
  for (let i: number = 0; i < MINI_APP_AI_DAILY_BUDGET; i++) {
    await budget.consume();
  }
  await assert.rejects((): Promise<void> => budget.consume());
  const key: string = 'app-1_2026-08-21';
  assert.equal(store.entries.get(key), String(MINI_APP_AI_DAILY_BUDGET));
  // 次日清零
  const tomorrow = createMiniAppAiBudget({
    appId: 'app-1',
    dayKey: (): string => '2026-08-22',
    store,
  });
  await tomorrow.consume();
});

test('aiBudget: 无 store → 内存计数', async () => {
  const budget = createMiniAppAiBudget({
    appId: 'app-1',
    dayKey: (): string => '2026-08-21',
  });
  for (let i: number = 0; i < MINI_APP_AI_DAILY_BUDGET; i++) {
    await budget.consume();
  }
  await assert.rejects((): Promise<void> => budget.consume());
});

// Phase 5 回归:并发 consume 不得绕过每日限额(串行化)
test('MiniAppAiBudget: concurrent consume is serialized (no limit bypass)', async () => {
  const budget = createMiniAppAiBudget({ appId: 'app-cc' });
  // 10 个并发 consume:串行化后计数必须精确为 10
  const results = await Promise.allSettled(Array.from({ length: 10 }, (): Promise<void> => budget.consume()));
  const fulfilled = results.filter((r): boolean => r.status === 'fulfilled').length;
  assert.equal(fulfilled, 10);
  // 消费到上限:预算 50,先并发 45(总 55 次)→ 恰好 50 成功 5 拒绝
  const budget2 = createMiniAppAiBudget({ appId: 'app-cc2' });
  const results2 = await Promise.allSettled(Array.from({ length: 55 }, (): Promise<void> => budget2.consume()));
  const ok2 = results2.filter((r): boolean => r.status === 'fulfilled').length;
  const rejected2 = results2.filter((r): boolean => r.status === 'rejected').length;
  assert.equal(ok2, 50, 'exactly the daily budget may succeed');
  assert.equal(rejected2, 5);
});
