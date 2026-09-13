// Control-flow transcription of NotificationNotifierImpl (entry/platform_impl/NotificationNotifier.ets)
// with a deferred mock platform. @kit.NotificationKit cannot run in node, so the *platform calls*
// are mocked, but the async/await structure is copied 1:1 from the real source:
//   notifyCompleted :155  -> await buildWant only; publishBasicNotification(...) NOT awaited/returned
//   cancelRunning   :167  -> cancelNotification(id) void, NOT awaited
//   publishBasicNotification :112 returns notificationManager.publish(...).catch(...)
//   cancelNotification :118 void: notificationManager.cancel(id).catch(...)
// Goal: scheduler's `await notifyCompleted` then `await cancelRunning` does NOT pin the platform
// publish/cancel order -> same-id race.

type Deferred = { promise: Promise<void>; resolve: () => void };
const deferred = (): Deferred => { let resolve!: () => void; const promise = new Promise<void>(r => { resolve = r; }); return { promise, resolve }; };
type Pending = { kind: 'want' | 'publish' | 'cancel'; id: number; title?: string; d: Deferred };

class MockPlatform {
  state = new Map<number, string>();
  queue: Pending[] = [];
  buildWant(id: number): Promise<void> { const d = deferred(); this.queue.push({ kind: 'want', id, d }); return d.promise; }
  publish(id: number, title: string): Promise<void> { const d = deferred(); this.queue.push({ kind: 'publish', id, title, d }); return d.promise.catch((): void => {}); }
  cancel(id: number): void { const d = deferred(); this.queue.push({ kind: 'cancel', id, d }); void d.promise.catch((): void => {}); }
  settleFirst(kind: 'want' | 'publish' | 'cancel'): boolean {
    const i = this.queue.findIndex(p => p.kind === kind);
    if (i < 0) return false;
    const p = this.queue.splice(i, 1)[0];
    if (p.kind === 'publish') this.state.set(p.id, p.title ?? '');
    if (p.kind === 'cancel') this.state.delete(p.id);
    p.d.resolve();
    return true;
  }
  // settle every queued op of a kind (platform stack drains; last publish wins for same id)
  settleAll(kind: 'want' | 'publish' | 'cancel'): void { while (this.settleFirst(kind)) { /* drain */ } }
}

// 1:1 transcription of the notifier's three relevant methods
const notifier = {
  async notifyRunning(id: number, title: string, plt: MockPlatform): Promise<void> {
    const _wa = await plt.buildWant(id);
    plt.publish(id, 'running:' + title); // dropped promise
  },
  async notifyCompleted(id: number, complete: boolean, plt: MockPlatform): Promise<void> {
    const _wa = await plt.buildWant(id);
    plt.publish(id, complete ? 'completed' : 'partial'); // dropped promise
  },
  async cancelRunning(id: number, plt: MockPlatform): Promise<void> {
    plt.cancel(id); // void
  },
};

const flush = (): Promise<void> => new Promise<void>(r => setImmediate(r));
const NID = 7;

// Drive one notifier call: start it, resolve its buildWant, advance microtasks.
const drive = async (p: Promise<void>, plt: MockPlatform): Promise<void> => {
  plt.settleFirst('want');
  await flush();
  await p;
};

// ===== Scenario A: normal successful run (scheduler.ts:141/150/159) =====
const runNormal = async (settleOrder: Array<'publish' | 'cancel'>): Promise<void> => {
  const plt = new MockPlatform();
  await drive(notifier.notifyRunning(NID, 'T', plt), plt);
  await drive(notifier.notifyCompleted(NID, true, plt), plt); // publish queued
  await notifier.cancelRunning(NID, plt);                     // cancel queued (same id)
  for (const step of settleOrder) { plt.settleAll(step); await flush(); }
  console.log(`  normal, platform settles [${settleOrder.join(' -> ')}] => visible = ${plt.state.get(NID) ?? '(none)'}`);
};

console.log('A) normal success: `await notifyCompleted` returns before publish lands, then cancel is issued');
await runNormal(['publish', 'cancel']); // publish then cancel -> completion retracted
await runNormal(['cancel', 'publish']); // cancel no-ops, then publish -> stale completion visible

// ===== Scenario B: REPLACE — old run A finally cancel vs new run B notifyRunning =====
const runReplace = async (settleOrder: Array<'publish' | 'cancel'>): Promise<void> => {
  const plt = new MockPlatform();
  const b = notifier.notifyRunning(NID, 'B', plt); // B running publish queued
  await notifier.cancelRunning(NID, plt);          // A finally cancel queued (scheduler.ts:159)
  await drive(b, plt);
  for (const step of settleOrder) { plt.settleAll(step); await flush(); }
  console.log(`  replace, platform settles [${settleOrder.join(' -> ')}] => B running visible = ${plt.state.get(NID) ?? '(none)'}`);
};
console.log('B) REPLACE: old A finally cancel vs B notifyRunning publish (same id)');
await runReplace(['publish', 'cancel']); // B publish then A cancel -> B ongoing retracted
await runReplace(['cancel', 'publish']); // A cancel no-ops -> B visible

console.log('NOTE: call order is deterministic (publish issued before cancel); platform settle order is NOT.');
