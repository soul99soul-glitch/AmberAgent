import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { setTimeout as delay } from 'node:timers/promises';
import { pollingObservable } from '../main/ets/platform/observable.ts';

// 所有测试用 AbortSignal 兜底清理,避免 setInterval 阻止 node 进程退出

const makeController = (): AbortController => new AbortController();

test('subscribe immediately calls back with current value if available', async () => {
  const controller = makeController();
  let calls = 0;
  const obs = pollingObservable(async () => ({ n: ++calls }), { intervalMs: 50, signal: controller.signal });
  await delay(10);
  let received = 0;
  obs.subscribe(v => { received = (v as { n: number }).n; });
  await delay(5);
  assert.ok(received > 0, 'should get current value immediately');
  controller.abort();
});

test('observable only notifies on value change', async () => {
  const controller = makeController();
  let fetchCount = 0;
  let receivedCount = 0;
  const obs = pollingObservable(async () => {
    fetchCount++;
    return 'same';
  }, { intervalMs: 20, signal: controller.signal });
  const unsub = obs.subscribe(() => { receivedCount++; });
  await delay(100);
  unsub();
  controller.abort();
  assert.ok(fetchCount >= 3, `fetcher called multiple times (${fetchCount})`);
  assert.equal(receivedCount, 1, 'only first value triggers callback (rest unchanged)');
});

test('subscribe returns unsubscribe function', async () => {
  const controller = makeController();
  let count = 0;
  const obs = pollingObservable(async () => ({ n: ++count }), { intervalMs: 20, signal: controller.signal });
  const unsub = obs.subscribe(() => {});
  assert.equal(typeof unsub, 'function');
  unsub();
  controller.abort();
  await delay(30);
});

test('AbortSignal stops polling', async () => {
  const controller = makeController();
  let fetchCount = 0;
  const obs = pollingObservable(async () => ++fetchCount, { intervalMs: 20, signal: controller.signal });
  obs.subscribe(() => {});
  await delay(50);
  const beforeAbort = fetchCount;
  controller.abort();
  await delay(80);
  assert.ok(fetchCount <= beforeAbort + 1, `fetch stopped after abort (before=${beforeAbort}, after=${fetchCount})`);
});

test('multiple subscribers all receive updates', async () => {
  const controller = makeController();
  let n = 0;
  const obs = pollingObservable(async () => ({ n: ++n }), { intervalMs: 20, signal: controller.signal });
  let r1 = 0, r2 = 0;
  const u1 = obs.subscribe(v => { r1 = (v as { n: number }).n; });
  await delay(30);
  const u2 = obs.subscribe(v => { r2 = (v as { n: number }).n; });
  await delay(60);
  u1();
  u2();
  controller.abort();
  assert.ok(r1 >= 2, `subscriber 1 got updates (${r1})`);
  assert.ok(r2 >= 1, `subscriber 2 got updates (${r2})`);
});
