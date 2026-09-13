// probe-batch3-cron.cjs — batch 3 regression (R12 entry inFlight / R16 long-delay / R17 abort)
//
// Loads the REAL entry scheduler source with mocked platform edges only:
//   harmony/entry/src/main/ets/platform_impl/AgentCronStore.ets
//   harmony/entry/src/main/ets/platform_impl/EntryAbortController.ets  (real, zero runtime deps)
// Platform edges are mocked: @kit.* -> {}, @amber/* -> {} (only type imports),
// and setTimeout/clearTimeout -> shared fake clock. No device / network / real timers.
//
// Domain-level R12 claim + RMW mutex + R17 cancelRun have node:test coverage in
// harmony/chat/src/test/agent_cron_regression.test.ts. This probe covers the entry
// control flow that node:test cannot load (.ets).

const path = require('node:path');
const assert = require('node:assert/strict');
const { REPO_ROOT, createLoader } = require('../../2026-09-13-harmony-merged-evidence/probe-lib.cjs');

const PLATFORM = path.join(REPO_ROOT, 'harmony/entry/src/main/ets/platform_impl');

// ---------- shared fake clock + fake timers ----------
let clock = 1_700_000_000_000;          // controlled nowMs
let seq = 1;
let pending = [];                        // { id, cb, delay, at }

const fakeSetTimeout = (cb, delay) => {
  const id = seq++;
  pending.push({ id, cb, delay, at: clock + delay });
  return id;
};
const fakeClearTimeout = (id) => {
  const i = pending.findIndex((t) => t.id === id);
  if (i >= 0) pending.splice(i, 1);
};

// ---------- loader with mocked edges ----------
const depResolver = (name) => {
  // @kit.* and @amber/* (type-only) resolve to empty mock objects
  return {};
};
depResolver.sandboxGlobals = { console, setTimeout: fakeSetTimeout, clearTimeout: fakeClearTimeout };

const loader = createLoader(depResolver);
const store = loader.load(path.join(PLATFORM, 'AgentCronStore.ets'));
const abort = loader.load(path.join(PLATFORM, 'EntryAbortController.ets'));

const { createEntryCronSchedulerPort, AGENT_CRON_EXECUTION_AVAILABLE } = store;
const { createEntryAbortController } = abort;

const workNameOf = (id) => `amberagent_cron_${id}`;
const flush = async () => {
  for (let i = 0; i < 8; i++) await new Promise((r) => setImmediate(r));
};

// fire the pending timer whose workName matches; advances clock to its due
const firePendingByName = (workName) => {
  // scheduler tracks timers internally; find the latest pending by identity is not
  // exposed, so advance the clock to the max 'at' and run all due callbacks once.
  const due = pending.filter((t) => t.at <= clock);
  const chosen = due.length > 0 ? due[due.length - 1] : pending[0];
  pending = pending.filter((t) => t !== chosen);
  clock = Math.max(clock, chosen.at);
  chosen.cb();
  return chosen;
};

// ---------- fake EntryCronManagerView ----------
const makeFakeManager = () => {
  const state = {
    tasks: new Map(),        // id -> task record
    claimed: new Set(),
    calls: [],               // control-flow trace
    runCount: 0,             // executor invocations
  };
  let schedulerPort = null;
  const manager = {
    add(id, { enabled = true, nextRunAtMs = null, lastStatus = 'Idle' } = {}) {
      state.tasks.set(id, { id, enabled, nextRunAtMs, lastStatus, runCount: 0, lastError: null });
    },
    get state() { return state; },
    setSchedulerPort(p) { schedulerPort = p; },
    async prepareTriggeredRun(id, manual = false) {
      state.calls.push(`prepare:${manual}`);
      if (state.claimed.has(id)) return null;
      const t = state.tasks.get(id);
      if (t === undefined) return null;
      if (!manual && !t.enabled) return null;
      state.claimed.add(id);
      t.runCount += 1;
      t.lastStatus = 'Queued';
      return { id };
    },
    async getTask(id) {
      const t = state.tasks.get(id);
      return t === undefined ? null : { ...t };
    },
    async markRunStarted(id) {
      state.calls.push('started');
      const t = state.tasks.get(id);
      if (t !== undefined) t.lastStatus = 'Running';
    },
    async markRunCompleted(id) {
      state.calls.push('completed');
      const t = state.tasks.get(id);
      if (t !== undefined) t.lastStatus = 'Succeeded';
    },
    async markRunFailed(id, message) {
      state.calls.push('failed');
      const t = state.tasks.get(id);
      if (t !== undefined) { t.lastStatus = 'Failed'; t.lastError = message; }
    },
    async markRunCancelled(id) {
      state.calls.push('cancelled');
      const t = state.tasks.get(id);
      if (t !== undefined) {
        t.lastStatus = 'Cancelled';
        t.lastError = 'Run cancelled; next cycle remains scheduled.';
      }
    },
    async markRunSuspend(id) {
      state.calls.push('suspend');
      const t = state.tasks.get(id);
      if (t !== undefined) t.lastStatus = 'Suspend';
    },
    async scheduleNextRun(id) {
      const t = state.tasks.get(id);
      // missing / disabled / no due ⇒ real manager is a no-op (no scheduler.schedule)
      if (t === undefined || !t.enabled || t.nextRunAtMs === null || schedulerPort === null) return;
      state.calls.push('rearm');
      const delay = Math.max(t.nextRunAtMs - clock, 0);
      schedulerPort.schedule(workNameOf(id), id, delay);
    },
    releaseRunClaim(id) { state.claimed.delete(id); },
  };
  return manager;
};

const buildPort = (manager, runExecutor, nowMs) => {
  const port = createEntryCronSchedulerPort({
    manager: async () => manager,
    runExecutor,
    createAbortController: createEntryAbortController,
    nowMs,
  });
  manager.setSchedulerPort(port);
  return port;
};

const R = [];
const record = (name, fn) => {
  try {
    fn();
    R.push(['PASS', name]);
  } catch (e) {
    R.push(['FAIL', name, e]);
  }
};

async function main() {
  assert.equal(AGENT_CRON_EXECUTION_AVAILABLE, true, 'execution available (real wiring)');
  assert.equal(typeof createEntryCronSchedulerPort, 'function');

  // reset helper
  const reset = () => { pending = []; seq = 1; };

  // ===== CT1 R12: entry inFlight prevents double fire (timer + manual) =====
  {
    reset();
    const manager = makeFakeManager();
    let executorCalls = 0;
    let gates = [];
    const runExecutor = (taskId, signal) => {
      executorCalls += 1;
      return new Promise((res) => gates.push({ res, signal }));
    };
    const port = buildPort(manager, runExecutor, () => clock);
    manager.add('t1', { enabled: true, nextRunAtMs: clock + 60_000 });

    // two manual runNow in the same tick (double-click / timer+manual race)
    port.runNow(workNameOf('t1'), 't1');
    port.runNow(workNameOf('t1'), 't1');
    await flush();
    record('CT1a inFlight collapses second manual run (executor once)', () => {
      assert.equal(executorCalls, 1);
    });
    record('CT1b prepare allowed exactly once', () => {
      assert.equal(manager.state.calls.filter((c) => c.startsWith('prepare')).length, 1);
    });
    gates.forEach((g) => g.res());
    await flush();
    record('CT1c completed + rearmed after release', () => {
      assert.equal(manager.state.tasks.get('t1').lastStatus, 'Succeeded');
      assert.ok(manager.state.calls.indexOf('rearm') >= 0);
    });
  }

  // ===== CT2 R16: >2^31-1ms delay is not executed early (clamp + original-due check) =====
  {
    reset();
    const manager = makeFakeManager();
    let executorCalls = 0;
    const runExecutor = () => { executorCalls += 1; return Promise.resolve(); };
    const port = buildPort(manager, runExecutor, () => clock);
    const dueAt = clock + 364 * 24 * 60 * 60 * 1000;   // ~1 year out
    manager.add('t2', { enabled: true, nextRunAtMs: dueAt });

    // manager.schedule would compute delay=dueAt-now then port clamps
    port.schedule(workNameOf('t2'), 't2', dueAt - clock);
    record('CT2a long delay clamped to 2^31-1', () => {
      assert.equal(pending.length, 1);
      assert.equal(pending[0].delay, 2147483647);
    });

    // advance a live process to the clamped expiry (24.855 days) and fire
    firePendingByName(workNameOf('t2'));
    await flush();
    record('CT2b early timer does NOT execute (now << due)', () => {
      assert.equal(executorCalls, 0);
      assert.equal(manager.state.tasks.get('t2').runCount, 0);
      assert.equal(manager.state.tasks.get('t2').lastStatus, 'Idle');
    });
    record('CT2c early timer only re-arms (no prepare/started)', () => {
      assert.ok(manager.state.calls.indexOf('rearm') >= 0);
      assert.equal(manager.state.calls.filter((c) => c.startsWith('prepare')).length, 0);
    });
    record('CT2d re-armed timer is again clamped (chain), due unchanged', () => {
      assert.equal(pending.length, 1);
      assert.equal(pending[0].delay, 2147483647);
      assert.equal(manager.state.tasks.get('t2').nextRunAtMs, dueAt);
    });

    // near-task control: a task genuinely due executes (fresh clock window)
    pending = [];
    const nearExec = { n: 0 };
    const manager2 = makeFakeManager();
    const port2 = buildPort(manager2, () => { nearExec.n += 1; return Promise.resolve(); }, () => clock);
    const nearDue = clock + 60_000;
    manager2.add('t2b', { enabled: true, nextRunAtMs: nearDue });
    port2.schedule(workNameOf('t2b'), 't2b', nearDue - clock);
    record('CT2e near task delay not clamped', () => {
      assert.equal(pending.length, 1);
      assert.equal(pending[0].delay, 60_000);
    });
    firePendingByName(workNameOf('t2b'));
    await flush();
    record('CT2f near task executes on due', () => {
      assert.equal(nearExec.n, 1);
      assert.equal(manager2.state.tasks.get('t2b').lastStatus, 'Succeeded');
    });
  }

  // ===== CT3 R17: cancel aborts in-flight run; Cancelled (not Succeeded) + explicit next cycle =====
  {
    reset();
    const manager = makeFakeManager();
    let gates = [];
    let receivedSignal = null;
    const runExecutor = (taskId, signal) => {
      receivedSignal = signal;
      return new Promise((res) => gates.push({ res, signal }));
    };
    const port = buildPort(manager, runExecutor, () => clock);
    const dueAt = clock + 60_000;
    manager.add('t3', { enabled: true, nextRunAtMs: dueAt });
    port.schedule(workNameOf('t3'), 't3', dueAt - clock);

    firePendingByName(workNameOf('t3'));
    await flush();
    record('CT3a executor started with a signal', () => {
      assert.ok(receivedSignal !== null && typeof receivedSignal.aborted === 'boolean');
      assert.equal(receivedSignal.aborted, false);
      assert.equal(manager.state.tasks.get('t3').lastStatus, 'Running');
    });

    // user cancels the currently running generation
    port.cancel(workNameOf('t3'));
    record('CT3b cancel aborts the in-flight signal', () => {
      assert.equal(receivedSignal.aborted, true);
    });

    gates.forEach((g) => g.res());
    await flush();
    record('CT3c cancelled run is NOT recorded as Succeeded', () => {
      const t = manager.state.tasks.get('t3');
      assert.equal(t.lastStatus, 'Cancelled');
      assert.ok(manager.state.calls.indexOf('completed') < 0);
    });
    record('CT3d next cycle is explicitly kept (rearm after cancel)', () => {
      const t = manager.state.tasks.get('t3');
      assert.ok((t.lastError || '').indexOf('next cycle') >= 0);
      assert.ok(manager.state.calls.indexOf('rearm') >= 0);
      assert.equal(pending.length, 1);
    });
  }

  // ===== CT4 R17: deleting a running task stops it; no resurrect / no markCompleted =====
  {
    reset();
    const manager = makeFakeManager();
    let gates = [];
    let signal = null;
    const runExecutor = (_taskId, s) => { signal = s; return new Promise((res) => gates.push(res)); };
    const port = buildPort(manager, runExecutor, () => clock);
    const dueAt = clock + 60_000;
    manager.add('t4', { enabled: true, nextRunAtMs: dueAt });
    port.schedule(workNameOf('t4'), 't4', dueAt - clock);
    firePendingByName(workNameOf('t4'));
    await flush();

    // deleteTask: remove task snapshot, then scheduler.cancel(workName)
    manager.state.tasks.delete('t4');
    port.cancel(workNameOf('t4'));
    record('CT4a delete aborts the in-flight generation', () => {
      assert.equal(signal.aborted, true);
    });

    gates.forEach((res) => res());
    await flush();
    record('CT4b deleted task is not completed/resurrected', () => {
      assert.equal(manager.state.tasks.has('t4'), false);
      assert.ok(manager.state.calls.indexOf('completed') < 0);
      assert.ok(manager.state.calls.indexOf('rearm') < 0);
      assert.equal(pending.length, 0);
    });
  }

  // ===== CT5 R17: disable-while-running stops the run, no rearm / no Succeeded =====
  {
    reset();
    const manager = makeFakeManager();
    let gates = [];
    let signal = null;
    const runExecutor = (_taskId, s) => { signal = s; return new Promise((res) => gates.push(res)); };
    const port = buildPort(manager, runExecutor, () => clock);
    const dueAt = clock + 60_000;
    manager.add('t5', { enabled: true, nextRunAtMs: dueAt });
    port.schedule(workNameOf('t5'), 't5', dueAt - clock);
    firePendingByName(workNameOf('t5'));
    await flush();

    // user disables the task mid-run → updateTask cancels the pending timer
    manager.state.tasks.get('t5').enabled = false;
    port.cancel(workNameOf('t5'));
    gates.forEach((res) => res());
    await flush();
    record('CT5a disabled mid-run stops run as Suspend (not Succeeded)', () => {
      const t = manager.state.tasks.get('t5');
      assert.equal(t.lastStatus, 'Suspend');
      assert.ok(manager.state.calls.indexOf('completed') < 0);
    });
    record('CT5b disabled mid-run does not re-arm', () => {
      assert.equal(pending.length, 0);
      assert.ok(manager.state.calls.indexOf('rearm') < 0);
    });
  }

  // ===== report =====
  let failed = 0;
  for (const row of R) {
    if (row[0] === 'PASS') console.log(`PASS ${row[1]}`);
    else { failed += 1; console.log(`FAIL ${row[1]}`); console.log(row[2]); }
  }
  console.log(`\n${R.length - failed}/${R.length} assertions PASS`);
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(2); });
