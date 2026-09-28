import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { pollingObservable } from '../main/ets/platform/observable.ts';

// 可控轮询时钟,每次 tick 后等待 fetcher 的 Promise 完成。
// after 即使断言失败也中止轮询,避免 setInterval 阻止 node 进程退出。

const makeController = (): AbortController => new AbortController();

test('subscribe immediately calls back with current value if available', async (t) => {
  t.mock.timers.enable({ apis: ['setInterval'] });
  const controller = makeController();
  t.after(() => controller.abort());
  let calls = 0;
  const obs = pollingObservable(async () => ({ n: ++calls }), { intervalMs: 50, signal: controller.signal });
  const firstUnsub = obs.subscribe(() => {});
  await Promise.resolve();
  assert.equal(obs.getCurrent()?.n, 1);
  let received = 0;
  const unsub = obs.subscribe(v => { received = v.n; });
  assert.equal(received, 1, 'should get cached current value synchronously');
  firstUnsub();
  unsub();
});

test('observable only notifies on value change', async (t) => {
  t.mock.timers.enable({ apis: ['setInterval'] });
  const controller = makeController();
  t.after(() => controller.abort());
  let fetchCount = 0;
  let receivedCount = 0;
  const obs = pollingObservable(async () => {
    fetchCount++;
    return 'same';
  }, { intervalMs: 20, signal: controller.signal });
  const unsub = obs.subscribe(() => { receivedCount++; });
  await Promise.resolve();
  for (let i = 0; i < 3; i++) {
    t.mock.timers.tick(20);
    await Promise.resolve();
  }
  unsub();
  assert.equal(fetchCount, 4, 'initial fetch and three completed polls');
  assert.equal(receivedCount, 1, 'only first value triggers callback (rest unchanged)');
});

test('subscribe returns unsubscribe function', async (t) => {
  t.mock.timers.enable({ apis: ['setInterval'] });
  const controller = makeController();
  t.after(() => controller.abort());
  let count = 0;
  const obs = pollingObservable(async () => ({ n: ++count }), { intervalMs: 20, signal: controller.signal });
  const unsub = obs.subscribe(() => {});
  await Promise.resolve();
  assert.equal(typeof unsub, 'function');
  unsub();
  t.mock.timers.tick(60);
  await Promise.resolve();
  assert.equal(count, 1, 'last unsubscribe stops polling');
});

test('AbortSignal stops polling', async (t) => {
  t.mock.timers.enable({ apis: ['setInterval'] });
  const controller = makeController();
  t.after(() => controller.abort());
  let fetchCount = 0;
  const obs = pollingObservable(async () => ++fetchCount, { intervalMs: 20, signal: controller.signal });
  obs.subscribe(() => {});
  await Promise.resolve();
  t.mock.timers.tick(20);
  await Promise.resolve();
  assert.equal(fetchCount, 2);
  const beforeAbort = fetchCount;
  controller.abort();
  t.mock.timers.tick(80);
  await Promise.resolve();
  assert.equal(fetchCount, beforeAbort, 'fetch stopped after abort');
});

test('multiple subscribers all receive updates', async (t) => {
  t.mock.timers.enable({ apis: ['setInterval'] });
  const controller = makeController();
  t.after(() => controller.abort());
  let n = 0;
  const obs = pollingObservable(async () => ({ n: ++n }), { intervalMs: 20, signal: controller.signal });
  let r1 = 0, r2 = 0;
  const u1 = obs.subscribe(v => { r1 = (v as { n: number }).n; });
  await Promise.resolve();
  t.mock.timers.tick(20);
  await Promise.resolve();
  assert.equal(r1, 2);
  const u2 = obs.subscribe(v => { r2 = (v as { n: number }).n; });
  assert.equal(r2, 2, 'second subscriber immediately receives cached value');
  t.mock.timers.tick(20);
  await Promise.resolve();
  u1();
  u2();
  assert.equal(r1, 3);
  assert.equal(r2, 3);
});
