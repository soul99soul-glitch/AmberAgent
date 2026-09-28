// agent_task.test.ts — D-130 feature/task 三件套
// Android 基准: AgentTaskModels.kt + AgentTaskRecoveryManager.kt + AgentTaskStore.kt
import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { JsonObject } from '../main/ets/chat/json.ts';
import type {
  AgentTaskFilePort, AgentTaskSnapshot,
} from '../main/ets/chat/agent_task.ts';
import {
  AgentTaskRecoveryManager, AgentTaskStore,
  makeAgentTaskRetryPolicy, makeAgentTaskOutputRef, makeAgentTaskSnapshot,
  agentTaskSnapshotToJson, agentTaskSnapshotFromJson,
  agentTaskStatusRunning, agentTaskStatusToQueueState, agentTaskStatusToRecoveryState,
} from '../main/ets/chat/agent_task.ts';

// ===== 假文件端口 =====

class MemFiles implements AgentTaskFilePort {
  files: Map<string, string> = new Map();

  mkdirs(_dir: string): Promise<void> { return Promise.resolve(); }

  listJsonFileNames(dir: string): Promise<string[]> {
    const out: string[] = [];
    this.files.forEach((_v: string, path: string) => {
      if (path.startsWith(`${dir}/`) && path.endsWith('.json') &&
        path.indexOf('/', dir.length + 1) < 0) {
        out.push(path.substring(dir.length + 1));
      }
    });
    return Promise.resolve(out.sort());
  }

  readText(path: string): Promise<string | null> {
    const v: string | undefined = this.files.get(path);
    return Promise.resolve(v !== undefined ? v : null);
  }

  writeText(path: string, text: string): Promise<void> {
    this.files.set(path, text);
    return Promise.resolve();
  }

  delete(path: string): Promise<void> {
    this.files.delete(path);
    return Promise.resolve();
  }

  exists(path: string): Promise<boolean> {
    return Promise.resolve(this.files.has(path));
  }

  isPathInside(root: string, path: string): Promise<boolean> {
    return Promise.resolve(path.startsWith(`${root}/`));
  }
}

const makeStoreDeps = (files: MemFiles): { taskDir: string; appFilesDir: string; files: MemFiles } => ({
  taskDir: '/files/amberagent/tasks',
  appFilesDir: '/files',
  files,
});

const sampleSnapshot = (taskId: string, status: AgentTaskSnapshot['status'] = 'QUEUED'): AgentTaskSnapshot =>
  makeAgentTaskSnapshot({
    taskId, type: 'cron', title: `t-${taskId}`,
    status, createdAtMs: 1000, updatedAtMs: 1000,
  });

// ===== 枚举/模型(AgentTaskModels.kt) =====

test('agentTaskStatusRunning: QUEUED/RUNNING 为真(:31-32)', () => {
  assert.equal(agentTaskStatusRunning('QUEUED'), true);
  assert.equal(agentTaskStatusRunning('RUNNING'), true);
  assert.equal(agentTaskStatusRunning('COMPLETED'), false);
  assert.equal(agentTaskStatusRunning('FAILED'), false);
});

test('snapshot 默认值 + 线格式 snake_case 键序', () => {
  const s: AgentTaskSnapshot = makeAgentTaskSnapshot({
    taskId: 'a', type: 'cron', title: 'A', status: 'QUEUED', createdAtMs: 5,
  });
  assert.equal(s.schemaVersion, 2);
  assert.equal(s.queueState, 'ACTIVE');
  assert.equal(s.recoveryState, 'ACTIVE');
  assert.equal(s.updatedAtMs, 5);
  assert.equal(s.retryPolicy.requiresApproval, true);
  assert.equal(s.cancelCapability, false);

  const j: JsonObject = agentTaskSnapshotToJson(s);
  assert.deepEqual(Object.keys(j), [
    'schema_version', 'task_id', 'type', 'title', 'queue_state', 'recovery_state',
    'retry_policy', 'status', 'output_offset', 'created_at_ms', 'updated_at_ms',
    'notified', 'cancel_capability',
  ]);
  assert.equal(j['status'], 'queued');
  const retry: JsonObject = j['retry_policy'] as JsonObject;
  assert.deepEqual(Object.keys(retry), ['retryable', 'requires_approval', 'max_retries', 'retry_count']);
  // 往返
  const back: AgentTaskSnapshot = agentTaskSnapshotFromJson(j);
  assert.equal(back.taskId, 'a');
  assert.equal(back.status, 'QUEUED');
  assert.equal(back.schemaVersion, 2);
});

test('toQueueState/toRecoveryState(AgentTaskStore.kt:235-248)', () => {
  assert.equal(agentTaskStatusToQueueState('QUEUED', 'cron'), 'SCHEDULED');
  assert.equal(agentTaskStatusToQueueState('QUEUED', 'terminal'), 'QUEUED');
  assert.equal(agentTaskStatusToQueueState('RUNNING', 'cron'), 'ACTIVE');
  assert.equal(agentTaskStatusToQueueState('FAILED', 'cron'), 'TERMINAL');

  const notRetryable = makeAgentTaskRetryPolicy();
  assert.equal(agentTaskStatusToRecoveryState('QUEUED', 'cron', notRetryable), 'SCHEDULED');
  assert.equal(agentTaskStatusToRecoveryState('RUNNING', 'x', notRetryable), 'ACTIVE');
  assert.equal(agentTaskStatusToRecoveryState('COMPLETED', 'x', notRetryable), 'OUTPUT_ONLY');
  assert.equal(agentTaskStatusToRecoveryState('FAILED', 'x', notRetryable), 'CLEANUP_ONLY');
  const retryable = makeAgentTaskRetryPolicy({ retryable: true, maxRetries: 3 });
  assert.equal(agentTaskStatusToRecoveryState('FAILED', 'x', retryable), 'RETRYABLE');
  assert.equal(agentTaskStatusToRecoveryState('TIMED_OUT', 'x', retryable), 'RETRYABLE');
});

// ===== RecoveryManager(全文 68,五分支) =====

const noOutput = async (_s: AgentTaskSnapshot): Promise<boolean> => false;
const hasOutput = async (_s: AgentTaskSnapshot): Promise<boolean> => true;

test('recover: cron 型 → SCHEDULED 三态 + heartbeat', async () => {
  const rm = new AgentTaskRecoveryManager(noOutput);
  const out: AgentTaskSnapshot = await rm.recoverOnStartup(
    sampleSnapshot('c1', 'RUNNING'), 9999);
  assert.equal(out.status, 'QUEUED');
  assert.equal(out.queueState, 'SCHEDULED');
  assert.equal(out.recoveryState, 'SCHEDULED');
  assert.equal(out.cancelCapability, false);
  assert.equal(out.lastHeartbeatMs, 9999);
});

test('recover: running → INTERRUPTED + 文案/错误码逐字', async () => {
  const rm = new AgentTaskRecoveryManager(noOutput);
  // 非 cron 型才走 running 分支(cron 型优先,Android 同序 :22)
  const out: AgentTaskSnapshot = await rm.recoverOnStartup(
    makeAgentTaskSnapshot({ taskId: 'r1', type: 'terminal', title: 't', status: 'RUNNING', createdAtMs: 1 }), 9999);
  assert.equal(out.status, 'INTERRUPTED');
  assert.equal(out.queueState, 'TERMINAL');
  assert.equal(out.recoveryState, 'INTERRUPTED');
  assert.equal(out.error, 'Task was interrupted because AmberAgent restarted.');
  assert.equal(out.lastErrorCode, 'interrupted_by_restart');
  assert.equal(out.updatedAtMs, 9999);

  // 输出存在 → OUTPUT_ONLY
  const out2: AgentTaskSnapshot = await new AgentTaskRecoveryManager(hasOutput)
    .recoverOnStartup(makeAgentTaskSnapshot({
      taskId: 'r2', type: 'terminal', title: 't', status: 'RUNNING',
      createdAtMs: 1, outputPath: '/files/out.log',
    }), 9999);
  assert.equal(out2.recoveryState, 'OUTPUT_ONLY');
  assert.equal(out2.outputRef !== null && out2.outputRef.type, 'terminal_log');
  assert.equal(out2.outputRef !== null && out2.outputRef.exists, true);
});

test('recover: completed+输出存在 → OUTPUT_ONLY;failed+retryable → RETRYABLE;否则 CLEANUP_ONLY', async () => {
  const completed: AgentTaskSnapshot = makeAgentTaskSnapshot({
    taskId: 'd1', type: 'terminal', title: 't', status: 'COMPLETED',
    createdAtMs: 1, outputPath: '/files/out.log',
  });
  const out1: AgentTaskSnapshot = await new AgentTaskRecoveryManager(hasOutput)
    .recoverOnStartup(completed, 100);
  assert.equal(out1.recoveryState, 'OUTPUT_ONLY');
  assert.equal(out1.lastHeartbeatMs, 100);

  const failedRetryable: AgentTaskSnapshot = makeAgentTaskSnapshot({
    taskId: 'd2', type: 'x', title: 't', status: 'FAILED', createdAtMs: 1,
    retryPolicy: makeAgentTaskRetryPolicy({ retryable: true, maxRetries: 2 }),
  });
  const out2: AgentTaskSnapshot = await new AgentTaskRecoveryManager(noOutput)
    .recoverOnStartup(failedRetryable, 100);
  assert.equal(out2.recoveryState, 'RETRYABLE');

  const failedPlain: AgentTaskSnapshot = makeAgentTaskSnapshot({
    taskId: 'd3', type: 'x', title: 't', status: 'FAILED', createdAtMs: 1,
  });
  const out3: AgentTaskSnapshot = await new AgentTaskRecoveryManager(noOutput)
    .recoverOnStartup(failedPlain, 100);
  assert.equal(out3.recoveryState, 'CLEANUP_ONLY');
});

// ===== AgentTaskStore(全文 248) =====

test('store: create 加载磁盘快照并经 recovery 恢复', async () => {
  const files = new MemFiles();
  const stale: AgentTaskSnapshot = sampleSnapshot('s1', 'RUNNING');
  files.files.set('/files/amberagent/tasks/s1.json', JSON.stringify(agentTaskSnapshotToJson(stale)));
  // 非法文件跳过
  files.files.set('/files/amberagent/tasks/bad.json', '{oops');

  const store: AgentTaskStore = await AgentTaskStore.create(makeStoreDeps(files));
  const restored: AgentTaskSnapshot | null = store.read('s1');
  assert.ok(restored !== null);
  // cron 型 → QUEUED/SCHEDULED
  assert.equal(restored.status, 'QUEUED');
  assert.equal(restored.queueState, 'SCHEDULED');
  // 恢复后有变化 → 回写
  const persisted: string | null = await files.readText('/files/amberagent/tasks/s1.json');
  assert.ok(persisted !== null && persisted.indexOf('"queued"') >= 0);
  assert.equal(store.read('bad'), null);
});

test('store: upsert/update/remove/list 排序 + 持久化', async () => {
  const files = new MemFiles();
  const store: AgentTaskStore = await AgentTaskStore.create(makeStoreDeps(files));
  await store.register(sampleSnapshot('a', 'COMPLETED'));
  await store.register(sampleSnapshot('b', 'RUNNING'));

  // update:status 联动 queueState/recoveryState 重算(:79/:86)
  const updated: AgentTaskSnapshot | null = await store.update('a', { status: 'FAILED' });
  assert.ok(updated !== null);
  assert.equal(updated.status, 'FAILED');
  assert.equal(updated.queueState, 'TERMINAL');
  assert.equal(updated.recoveryState, 'CLEANUP_ONLY');
  assert.equal(await store.update('nope', { status: 'FAILED' }), null);

  // list:running 优先,其后 updatedAtMs 降序
  const listed: AgentTaskSnapshot[] = store.list();
  assert.deepEqual(listed.map((s: AgentTaskSnapshot): string => s.taskId), ['b', 'a']);
  assert.deepEqual(store.list('cron').length, 2);
  assert.deepEqual(store.list(null, 'RUNNING').map((s: AgentTaskSnapshot): string => s.taskId), ['b']);

  // 持久化文件存在
  assert.ok(await files.exists('/files/amberagent/tasks/a.json'));
  assert.equal(await store.remove('a'), true);
  assert.equal(await files.exists('/files/amberagent/tasks/a.json'), false);
  assert.equal(await store.remove('a'), false);
});

test('store: cancel leaves a real terminal state intact when completion wins the cancellation race', async () => {
  const store: AgentTaskStore = await AgentTaskStore.create(makeStoreDeps(new MemFiles()));
  const task: AgentTaskSnapshot = makeAgentTaskSnapshot({ taskId: 'cancel-race', type: 'terminal', title: 'SSH',
    status: 'RUNNING', createdAtMs: 1, cancelCapability: true });
  await store.register(task, async (): Promise<boolean> => {
    await store.update(task.taskId, { status: 'COMPLETED', cancelCapability: false, summary: 'Exit 0', error: null });
    return false;
  });
  const result: AgentTaskSnapshot = await store.cancel(task.taskId);
  assert.equal(result.status, 'COMPLETED');
  assert.equal(result.summary, 'Exit 0');
  assert.equal(result.error, null);
});

test('store: cancel — 无回调 → 错误文案;有回调 → CANCELLED', async () => {
  const files = new MemFiles();
  const store: AgentTaskStore = await AgentTaskStore.create(makeStoreDeps(files));
  await store.register(sampleSnapshot('c', 'RUNNING'));
  const denied: AgentTaskSnapshot = await store.cancel('c');
  assert.equal(denied.error, 'Task cannot be cancelled from AmberAgent.');
  assert.equal(denied.status, 'RUNNING');

  let calls: number = 0;
  const cancellable: AgentTaskSnapshot = makeAgentTaskSnapshot({
    taskId: 'c2', type: 'x', title: 't', status: 'RUNNING',
    createdAtMs: 1, cancelCapability: true,
  });
  await store.register(cancellable, (): Promise<boolean> => { calls += 1; return Promise.resolve(true); });
  const cancelled: AgentTaskSnapshot = await store.cancel('c2');
  assert.equal(calls, 1);
  assert.equal(cancelled.status, 'CANCELLED');
  assert.equal(cancelled.summary, 'Cancellation requested.');
  // 非 running → 原样返回
  const again: AgentTaskSnapshot = await store.cancel('c2');
  assert.equal(again.status, 'CANCELLED');
  assert.equal(calls, 1);
  await assert.rejects(() => store.cancel('unknown'), /Unknown agent task: unknown/);
});

test('store: retry — 四分支文案逐字', async () => {
  const files = new MemFiles();
  const store: AgentTaskStore = await AgentTaskStore.create(makeStoreDeps(files));
  // 不可重试
  await store.register(sampleSnapshot('r1', 'FAILED'));
  const notRetryable: AgentTaskSnapshot = await store.retry('r1');
  assert.equal(notRetryable.error, 'Task is not retryable.');
  assert.equal(notRetryable.lastErrorCode, 'retry_not_allowed');

  // 达到上限
  await store.register(makeAgentTaskSnapshot({
    taskId: 'r2', type: 'x', title: 't', status: 'FAILED', createdAtMs: 1,
    retryPolicy: makeAgentTaskRetryPolicy({ retryable: true, maxRetries: 1, retryCount: 1 }),
  }));
  const limited: AgentTaskSnapshot = await store.retry('r2');
  assert.equal(limited.error, 'Task retry limit reached.');
  assert.equal(limited.lastErrorCode, 'retry_limit_reached');

  // 无适配器
  await store.register(makeAgentTaskSnapshot({
    taskId: 'r3', type: 'x', title: 't', status: 'FAILED', createdAtMs: 1,
    retryPolicy: makeAgentTaskRetryPolicy({ retryable: true, maxRetries: 3 }),
  }));
  const noAdapter: AgentTaskSnapshot = await store.retry('r3');
  assert.equal(noAdapter.error, 'Task retry is available in metadata, but no live retry adapter is registered.');
  assert.equal(noAdapter.lastErrorCode, 'retry_adapter_missing');
  assert.equal(noAdapter.recoveryState, 'RETRYABLE');

  // 成功重试 → QUEUED + retryCount+1
  await store.register(makeAgentTaskSnapshot({
    taskId: 'r4', type: 'x', title: 't', status: 'FAILED', createdAtMs: 1,
    retryPolicy: makeAgentTaskRetryPolicy({ retryable: true, maxRetries: 3 }),
  }), null, (): Promise<boolean> => Promise.resolve(true));
  const retried: AgentTaskSnapshot = await store.retry('r4');
  assert.equal(retried.status, 'QUEUED');
  assert.equal(retried.queueState, 'QUEUED');
  assert.equal(retried.retryPolicy.retryCount, 1);
  assert.equal(retried.summary, 'Retry requested.');
});

test('store: cleanup — 私有输出门(isPathInside)', async () => {
  const files = new MemFiles();
  files.files.set('/files/private/out.txt', 'x');
  files.files.set('/etc/outside.txt', 'y');
  const store: AgentTaskStore = await AgentTaskStore.create(makeStoreDeps(files));
  await store.register(makeAgentTaskSnapshot({
    taskId: 'k', type: 'x', title: 't', status: 'COMPLETED', createdAtMs: 1,
    outputRef: makeAgentTaskOutputRef({ path: '/files/private/out.txt', exists: true }),
  }));
  assert.equal(await store.cleanup('k', true), true);
  assert.equal(await files.exists('/files/private/out.txt'), false);

  await store.register(makeAgentTaskSnapshot({
    taskId: 'k2', type: 'x', title: 't', status: 'COMPLETED', createdAtMs: 1,
    outputPath: '/etc/outside.txt',
  }));
  assert.equal(await store.cleanup('k2', true), true);
  assert.equal(await files.exists('/etc/outside.txt'), true);   // 沙箱外不删
  assert.equal(await store.cleanup('nope'), false);
});

test('R25 cleanup — 非终态(QUEUED/RUNNING)拒绝,终态可清', async () => {
  const files = new MemFiles();
  const store: AgentTaskStore = await AgentTaskStore.create(makeStoreDeps(files));
  await store.register(makeAgentTaskSnapshot({
    taskId: 'run', type: 'x', title: 't', status: 'RUNNING', createdAtMs: 1,
    cancelCapability: true,
  }));
  await store.register(makeAgentTaskSnapshot({
    taskId: 'queued', type: 'x', title: 't', status: 'QUEUED', createdAtMs: 1,
    cancelCapability: true,
  }));
  // 非终态:cleanup 拒绝,看板记录与取消回调都保留
  assert.equal(await store.cleanup('run'), false);
  assert.equal(await store.cleanup('queued'), false);
  assert.notEqual(store.read('run'), null);
  const stillRunning: AgentTaskSnapshot | null = store.read('run');
  assert.equal(stillRunning?.cancelCapability, true);
  // 终态(四类)才可清
  for (const status of ['COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT'] as const) {
    await store.register(sampleSnapshot(`term-${status}`, status));
    assert.equal(await store.cleanup(`term-${status}`), true);
    assert.equal(store.read(`term-${status}`), null);
  }
});

test('store: reconcileOnStartup — running 中断恢复 + 排序', async () => {
  const files = new MemFiles();
  const store: AgentTaskStore = await AgentTaskStore.create(makeStoreDeps(files));
  await store.register(makeAgentTaskSnapshot({
    taskId: 'q1', type: 'terminal', title: 't', status: 'RUNNING', createdAtMs: 1,
  }));
  await store.register(sampleSnapshot('q2', 'COMPLETED'));
  const recovered: AgentTaskSnapshot[] = await store.reconcileOnStartup();
  const q1: AgentTaskSnapshot | undefined =
    recovered.find((s: AgentTaskSnapshot): boolean => s.taskId === 'q1');
  assert.ok(q1 !== undefined);
  assert.equal(q1.status, 'INTERRUPTED');
  assert.equal(q1.lastErrorCode, 'interrupted_by_restart');
});

test('store: subscribe — publish 推送 list 快照', async () => {
  const files = new MemFiles();
  const store: AgentTaskStore = await AgentTaskStore.create(makeStoreDeps(files));
  const seen: string[][] = [];
  store.subscribe((snapshots: AgentTaskSnapshot[]): void => {
    seen.push(snapshots.map((s: AgentTaskSnapshot): string => s.taskId));
  });
  await store.register(sampleSnapshot('z'));
  assert.deepEqual(seen, [['z']]);
});

test('store: update 的 error/lastErrorCode — undefined 保留、null 显式清除(retry 成功语义)', async () => {
  const files = new MemFiles();
  const store: AgentTaskStore = await AgentTaskStore.create(makeStoreDeps(files));
  await store.register(sampleSnapshot('e1', 'FAILED'));
  await store.update('e1', { error: 'boom', lastErrorCode: 'x1' });
  // 未提供字段 → 保留
  const kept: AgentTaskSnapshot | null = store.read('e1');
  assert.ok(kept !== null);
  assert.equal(kept.error, 'boom');
  assert.equal(kept.lastErrorCode, 'x1');
  await store.update('e1', { status: 'RUNNING' });
  const kept2: AgentTaskSnapshot | null = store.read('e1');
  assert.ok(kept2 !== null);
  assert.equal(kept2.error, 'boom');
  // null → 清除(retry 成功 / subagent 正常收口)
  await store.update('e1', { status: 'COMPLETED', error: null, lastErrorCode: null });
  const cleared: AgentTaskSnapshot | null = store.read('e1');
  assert.ok(cleared !== null);
  assert.equal(cleared.error, null);
  assert.equal(cleared.lastErrorCode, null);
});

test('recovery: 禁用的 cron 任务重启后不复活为 SCHEDULED(spec.enabled=false)', async () => {
  const recovery: AgentTaskRecoveryManager = new AgentTaskRecoveryManager(
    async (): Promise<boolean> => false,
  );
  const disabled: AgentTaskSnapshot = makeAgentTaskSnapshot({
    taskId: 'cron-off', type: 'cron', title: 'off',
    status: 'RUNNING', createdAtMs: 1000,
    spec: { enabled: false },
  });
  const out: AgentTaskSnapshot = await recovery.recoverOnStartup(disabled, 2000);
  assert.equal(out.status, 'CANCELLED');
  assert.equal(out.queueState, 'TERMINAL');
  assert.equal(out.recoveryState, 'CLEANUP_ONLY');
  // spec.enabled 缺失(旧数据)→ 维持原启用恢复语义
  const legacy: AgentTaskSnapshot = makeAgentTaskSnapshot({
    taskId: 'cron-old', type: 'cron', title: 'old',
    status: 'RUNNING', createdAtMs: 1000,
  });
  const out2: AgentTaskSnapshot = await recovery.recoverOnStartup(legacy, 2000);
  assert.equal(out2.status, 'QUEUED');
  assert.equal(out2.queueState, 'SCHEDULED');
});
