// agent_task_tools.test.ts — D-131 AgentTaskTools 六件
// Android 基准: feature/tools/impl/.../AgentTaskTools.kt(全文 210 行)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { JsonObject, JsonValue } from '../main/ets/chat/json.ts';
import type {
  AgentTaskFilePort, AgentTaskSnapshot,
} from '../main/ets/chat/agent_task.ts';
import {
  AgentTaskStore, makeAgentTaskOutputRef, makeAgentTaskRetryPolicy, makeAgentTaskSnapshot,
} from '../main/ets/chat/agent_task.ts';
import { AgentTaskScheduler } from '../main/ets/chat/agent_task_scheduler.ts';
import { AgentTaskTools } from '../main/ets/chat/agent_task_tools.ts';

class MemFiles implements AgentTaskFilePort {
  files: Map<string, string> = new Map();

  mkdirs(_dir: string): Promise<void> { return Promise.resolve(); }

  listJsonFileNames(dir: string): Promise<string[]> {
    const names: string[] = [];
    this.files.forEach((_text: string, path: string): void => {
      if (path.startsWith(`${dir}/`) && path.endsWith('.json') && path.indexOf('/', dir.length + 1) < 0) {
        names.push(path.substring(dir.length + 1));
      }
    });
    return Promise.resolve(names.sort());
  }

  readText(path: string): Promise<string | null> {
    return Promise.resolve(this.files.get(path) ?? null);
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

const TASK_DIR: string = '/files/amberagent/tasks';
const APP_FILES_DIR: string = '/files';

const makeStore = async (): Promise<{
  files: MemFiles; store: AgentTaskStore; scheduler: AgentTaskScheduler;
}> => {
  const files: MemFiles = new MemFiles();
  const store: AgentTaskStore = await AgentTaskStore.create({
    taskDir: TASK_DIR,
    appFilesDir: APP_FILES_DIR,
    files,
  });
  return { files, store, scheduler: new AgentTaskScheduler(store) };
};

const textJson = async (parts: Promise<ReadonlyArray<{ type: string; text?: string }>>): Promise<JsonObject> => {
  const result = await parts;
  return JSON.parse(result[0].text ?? '{}') as JsonObject;
};

const snapshot = (
  taskId: string,
  status: AgentTaskSnapshot['status'],
  overrides: Partial<AgentTaskSnapshot> = {},
): AgentTaskSnapshot => makeAgentTaskSnapshot({
  taskId,
  type: overrides.type ?? 'subagent',
  title: overrides.title ?? `Task ${taskId}`,
  status,
  createdAtMs: 1000,
  updatedAtMs: 1000,
  spec: overrides.spec ?? null,
  runtime: overrides.runtime ?? null,
  queueState: overrides.queueState,
  recoveryState: overrides.recoveryState,
  retryPolicy: overrides.retryPolicy,
  outputRef: overrides.outputRef ?? null,
  sourceToolName: overrides.sourceToolName ?? null,
  sourceConversationId: overrides.sourceConversationId ?? null,
  outputPath: overrides.outputPath ?? null,
  outputOffset: overrides.outputOffset,
  lastHeartbeatMs: overrides.lastHeartbeatMs ?? null,
  notified: overrides.notified,
  cancelCapability: overrides.cancelCapability,
  permissionTraceId: overrides.permissionTraceId ?? null,
  summary: overrides.summary ?? null,
  lastErrorCode: overrides.lastErrorCode ?? null,
  error: overrides.error ?? null,
});

test('tools: 六件定义顺序/审批旗标/required', async () => {
  const { scheduler } = await makeStore();
  const tools = new AgentTaskTools(scheduler, { outputExists: (_path: string): Promise<boolean> => Promise.resolve(false) }).getTools();
  assert.deepEqual(tools.map((tool): string => tool.name), [
    'agent_task_list', 'agent_task_read', 'agent_task_cancel',
    'agent_task_retry', 'agent_task_cleanup', 'agent_runtime_status',
  ]);
  assert.equal(tools[0].needsApproval, false);
  assert.equal(tools[1].needsApproval, false);
  assert.equal(tools[2].needsApproval, true);
  assert.equal(tools[3].needsApproval, true);
  assert.equal(tools[4].needsApproval, true);
  assert.equal(tools[5].needsApproval, false);
  assert.deepEqual(tools[1].parameters()?.required, ['task_id']);
  assert.deepEqual(tools[2].parameters()?.required, ['task_id']);
  assert.deepEqual(tools[3].parameters()?.required, ['task_id']);
  assert.deepEqual(tools[4].parameters()?.required, ['task_id']);
});

test('list/read: status/type 过滤 + not_found 逐字', async () => {
  const { scheduler } = await makeStore();
  await scheduler.enqueue(snapshot('q', 'QUEUED', { type: 'cron' }));
  await scheduler.start(snapshot('r', 'RUNNING', { type: 'subagent' }));
  const tools = new AgentTaskTools(scheduler, { outputExists: (_path: string): Promise<boolean> => Promise.resolve(false) }).getTools();

  const listed: JsonObject = await textJson(tools[0].execute({ type: 'cron', status: 'queued' }));
  assert.equal(listed['status'], 'ok');
  assert.deepEqual((listed['tasks'] as JsonValue[]).map((item: JsonValue): string =>
    (item as JsonObject)['task_id'] as string), ['q']);

  const missing: JsonObject = await textJson(tools[1].execute({ task_id: 'missing' }));
  assert.equal(missing['status'], 'not_found');
  assert.equal(missing['task_id'], 'missing');
  assert.equal(missing['task'], undefined);
});

test('toJson: 168-204 键序/截断/异步 output_exists 探测', async () => {
  const { scheduler } = await makeStore();
  let probedPath: string | null = null;
  const longSummary: string = 's'.repeat(5000);
  await scheduler.enqueue(snapshot('json', 'QUEUED', {
    type: 'terminal',
    title: 'Terminal task',
    spec: { command: 'safe' },
    runtime: 'runtime-1',
    sourceToolName: 'terminal_run',
    sourceConversationId: 'conversation-1',
    retryPolicy: makeAgentTaskRetryPolicy({ retryable: true, requiresApproval: false, maxRetries: 3, retryCount: 1, reason: 'again' }),
    outputPath: '/files/log.txt',
    outputOffset: 8,
    lastHeartbeatMs: 11,
    notified: true,
    cancelCapability: true,
    permissionTraceId: 'trace-1',
    summary: longSummary,
    lastErrorCode: 'last-code',
    error: 'e'.repeat(2000),
  }));
  const tools = new AgentTaskTools(scheduler, {
    outputExists: (path: string): Promise<boolean> => {
      probedPath = path;
      return Promise.resolve(true);
    },
  }).getTools();
  const listed: JsonObject = await textJson(tools[0].execute({}));
  const task: JsonObject = (listed['tasks'] as JsonValue[])[0] as JsonObject;
  assert.deepEqual(Object.keys(task), [
    'task_id', 'type', 'title', 'queue_state', 'recovery_state', 'retryable',
    'retry_requires_approval', 'retry_count', 'retry_max', 'retry_reason', 'output_exists',
    'last_heartbeat_ms', 'spec', 'runtime', 'source_tool_name', 'source_conversation_id',
    'status', 'output_path', 'output_offset', 'created_at_ms', 'updated_at_ms', 'notified',
    'cancel_capability', 'permission_trace_id', 'summary', 'last_error_code', 'error',
  ]);
  assert.equal(task['output_exists'], true);
  assert.equal(probedPath, '/files/log.txt');
  assert.equal((task['summary'] as string).length, 4000);
  assert.equal((task['error'] as string).length, 1000);
});

test('cancel/retry: 委托 scheduler 并返回任务状态', async () => {
  const { scheduler } = await makeStore();
  let cancelled = false;
  let retried = false;
  await scheduler.start(
    snapshot('cancel', 'RUNNING', { cancelCapability: true }),
    async (): Promise<boolean> => { cancelled = true; return true; },
  );
  await scheduler.enqueue(
    snapshot('retry', 'FAILED', {
      retryPolicy: makeAgentTaskRetryPolicy({ retryable: true, maxRetries: 2 }),
    }),
    null,
    async (): Promise<boolean> => { retried = true; return true; },
  );
  const tools = new AgentTaskTools(scheduler, { outputExists: (_path: string): Promise<boolean> => Promise.resolve(false) }).getTools();
  const cancelledPayload: JsonObject = await textJson(tools[2].execute({ task_id: 'cancel' }));
  const retryPayload: JsonObject = await textJson(tools[3].execute({ task_id: 'retry' }));
  assert.equal(cancelled, true);
  assert.equal(retried, true);
  assert.equal(cancelledPayload['status'], 'cancelled');
  assert.equal(retryPayload['status'], 'queued');
  assert.equal((retryPayload['task'] as JsonObject)['retry_count'], 1);
});

test('cleanup: delete_private_output 传递并只删除私有输出', async () => {
  const { files, store, scheduler } = await makeStore();
  files.files.set('/files/private.log', 'private');
  files.files.set('/workspace/public.md', 'public');
  // R25:cleanup 仅终态可清 — 直接 upsert 保留 COMPLETED(enqueue 会强制回 QUEUED)
  await store.upsert(snapshot('cleanup', 'COMPLETED', {
    outputPath: '/files/private.log',
  }));
  const tools = new AgentTaskTools(scheduler, { outputExists: files.exists.bind(files) }).getTools();
  const result: JsonObject = await textJson(tools[4].execute({ task_id: 'cleanup', delete_private_output: true }));
  assert.equal(result['status'], 'ok');
  assert.equal(result['task_id'], 'cleanup');
  assert.equal(result['delete_private_output'], true);
  assert.equal(files.files.has('/files/private.log'), false);
  assert.equal(files.files.has('/workspace/public.md'), true);
});

test('runtime status: 状态计数 + by_type 键序', async () => {
  const { store, scheduler } = await makeStore();
  await store.upsert(snapshot('q', 'QUEUED', { type: 'cron' }));
  await store.upsert(snapshot('r', 'RUNNING', { type: 'subagent' }));
  await store.upsert(snapshot('done', 'COMPLETED', { type: 'report' }));
  await store.upsert(snapshot('failed', 'FAILED', { type: 'report' }));
  await store.upsert(snapshot('cancelled', 'CANCELLED', { type: 'cron' }));
  await store.upsert(snapshot('timed', 'TIMED_OUT', { type: 'terminal' }));
  await store.upsert(snapshot('interrupted', 'INTERRUPTED', { type: 'subagent' }));
  const tools = new AgentTaskTools(scheduler, { outputExists: (_path: string): Promise<boolean> => Promise.resolve(false) }).getTools();
  const payload: JsonObject = await textJson(tools[5].execute({}));
  assert.deepEqual(payload, {
    status: 'ok',
    total_tasks: 7,
    queued: 1,
    running: 1,
    completed: 1,
    failed: 1,
    cancelled: 1,
    timed_out: 1,
    interrupted: 1,
    by_type: { cron: 2, subagent: 2, report: 2, terminal: 1 },
  });
});
