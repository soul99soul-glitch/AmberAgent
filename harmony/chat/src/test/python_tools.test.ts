import { test } from 'node:test';
import assert from 'node:assert/strict';
import { AgentTaskStore } from '../main/ets/chat/agent_task.ts';
import { AgentToolActivityStore } from '../main/ets/chat/tool_activity.ts';
import { PythonRuntime } from '../main/ets/chat/python/runtime.ts';
import { createPythonTool } from '../main/ets/chat/python/tools.ts';
import type { PythonExecuteOptions, PythonNativeResult } from '../main/ets/chat/python/models.ts';
import type { JsonValue } from '../main/ets/chat/json.ts';
import type { AbortSignalLike } from '@amber/deepread-domain';

const setup = async () => {
  const taskStore = await AgentTaskStore.create({ taskDir: '/files/tasks', appFilesDir: '/files', files: {
    async mkdirs() {}, async listJsonFileNames() { return []; }, async readText() { return null; }, async writeText() {},
    async delete() {}, async exists() { return false; }, async isPathInside() { return false; },
  } });
  const calls: PythonExecuteOptions[] = [];
  const transport = {
    version() { return '3.14.7'; },
    async execute(_id: string, options: PythonExecuteOptions, _signal?: AbortSignalLike): Promise<PythonNativeResult> {
      calls.push(options);
      return { status: 'completed', exitCode: 0, stdout: 'result\n', stderr: '', errorCode: null };
    },
  };
  const runtime = new PythonRuntime({ transport, taskStore });
  const activityStore = new AgentToolActivityStore();
  const tool = createPythonTool({ runtime, activityStore, conversationId: 'chat' });
  return { taskStore, calls, transport, runtime, activityStore, tool };
};
const resultJson = async (s: Awaited<ReturnType<typeof setup>>, input: JsonValue, signal?: AbortSignalLike) => {
  const parts = await s.tool.execute(input, signal);
  assert.equal(parts.length, 1);
  assert.equal(parts[0].type, 'text');
  return JSON.parse(parts[0].type === 'text' ? parts[0].text : '{}');
};

test('python_execute requires approval, rejects auto approval and describes accurate local capabilities', async () => {
  const s = await setup();
  assert.equal(s.tool.name, 'python_execute');
  assert.equal(s.tool.needsApproval, true);
  assert.equal(s.tool.allowsAutoApproval, false);
  assert.deepEqual(s.tool.parameters()?.required, ['code']);
  assert.ok(s.tool.description.includes('locally'));
  assert.ok(s.tool.description.includes('Workspace'));
  assert.ok(s.tool.description.includes('not a system security sandbox'));
  assert.ok(!s.tool.description.includes('SSH'));
});

test('tool sends code and default stdin/timeout and returns a complete native result envelope', async () => {
  const s = await setup();
  const result = await resultJson(s, { code: 'print(1)' });
  assert.equal(result.runtime, 'embedded_python');
  assert.equal(result.status, 'completed');
  assert.equal(result.running, false);
  assert.equal(result.stdout, 'result\n');
  assert.equal(Object.hasOwn(result, 'output'), false);
  assert.equal(result.exit_code, 0);
  assert.equal(result.error_code, null);
  assert.deepEqual(s.calls, [{ source: 'print(1)', stdin: '', timeoutMs: 15000 }]);
  assert.equal(s.taskStore.read(result.run_id)?.sourceConversationId, 'chat');
  assert.equal(s.activityStore.sandboxActivity?.runtime, 'embedded_python');
  assert.equal(s.activityStore.sandboxActivity?.status, 'succeeded');
  assert.equal(s.activityStore.sandboxActivity?.canCancel, false);
});

test('explicit stdin/timeout are preserved without remote host or workspace arguments', async () => {
  const s = await setup();
  await resultJson(s, { code: 'print(input())', stdin: '中😀\n', timeout_ms: 42 });
  assert.deepEqual(s.calls, [{ source: 'print(input())', stdin: '中😀\n', timeoutMs: 42 }]);
});

test('tool validates JSON argument types and byte limits without invoking native', async () => {
  const s = await setup();
  const invalid: JsonValue[] = [null, [], 'code', {}, { code: 1 }, { code: 'pass', stdin: null },
    { code: 'pass', timeout_ms: '1' }, { code: 'pass', timeout_ms: 1.5 }, { code: 'pass', timeout_ms: 60001 },
    { code: '' }, { code: 'pass', stdin: '中'.repeat(21846) }];
  for (const input of invalid) {
    const result = await resultJson(s, input);
    assert.equal(result.status, 'failed');
    assert.equal(result.error_code, 'invalid_arguments');
    assert.equal(result.runtime, 'embedded_python');
  }
  assert.equal(s.calls.length, 0);
  assert.equal(s.taskStore.list().length, 0);
  assert.equal(s.activityStore.sandboxActivity?.canCancel, false);
});

test('Python exception preserves stderr and failed activity instead of swallowing interpreter result', async () => {
  const s = await setup();
  s.transport.execute = async (): Promise<PythonNativeResult> => ({ status: 'failed', exitCode: 1,
    stdout: 'before error', stderr: 'ValueError: bad', errorCode: 'python_exception' });
  const result = await resultJson(s, { code: 'raise ValueError()' });
  assert.equal(result.status, 'failed');
  assert.equal(result.exit_code, 1);
  assert.equal(result.stderr, 'ValueError: bad');
  assert.equal(result.stdout, 'before error');
  assert.equal(s.activityStore.sandboxActivity?.status, 'failed');
  assert.equal(s.activityStore.sandboxActivity?.outputTail, 'before error\nValueError: bad');
});

test('timeout with no output gives a meaningful failed activity and null native exit code', async () => {
  const s = await setup();
  s.transport.execute = async (): Promise<PythonNativeResult> => ({ status: 'timed_out', exitCode: null,
    stdout: '', stderr: '', errorCode: 'timed_out' });
  const result = await resultJson(s, { code: 'while True: pass' });
  assert.equal(result.status, 'timed_out');
  assert.equal(result.exit_code, null);
  assert.equal(s.activityStore.sandboxActivity?.status, 'failed');
  assert.equal(s.activityStore.sandboxActivity?.outputTail, 'Python: timed_out');
});

test('cancelled call settles both tool result and activity only after native acknowledges cancellation', async () => {
  const s = await setup();
  let finish: (result: PythonNativeResult) => void = () => {};
  let started = false;
  s.transport.execute = async (_id, _options, signal): Promise<PythonNativeResult> => {
    started = true;
    return new Promise(resolve => {
      finish = resolve;
      signal?.addEventListener?.('abort', () => {});
    });
  };
  const signal = new AbortController();
  const execution = resultJson(s, { code: 'while True: pass' }, signal.signal);
  while (!started) await new Promise(resolve => setTimeout(resolve, 1));
  signal.abort();
  assert.equal(s.activityStore.sandboxActivity?.status, 'running');
  assert.equal(s.activityStore.sandboxActivity?.canCancel, true);
  finish({ status: 'cancelled', exitCode: null, stdout: 'partial', stderr: '', errorCode: 'cancelled' });
  const result = await execution;
  assert.equal(result.status, 'cancelled');
  assert.equal(result.stdout, 'partial');
  assert.equal(s.activityStore.sandboxActivity?.status, 'cancelled');
  assert.equal(s.activityStore.sandboxActivity?.canCancel, false);
});

test('output and cancellation from an earlier execution cannot overwrite a newer activity slot', async () => {
  const s = await setup();
  let finish: (result: PythonNativeResult) => void = () => {};
  let started = false;
  s.transport.execute = async (): Promise<PythonNativeResult> => {
    started = true; return new Promise(resolve => { finish = resolve; });
  };
  const execution = resultJson(s, { code: 'print(1)' });
  while (!started) await new Promise(resolve => setTimeout(resolve, 1));
  const next = s.activityStore.startTool('file_read', 'read', '', 'workspace', '', false, 'next');
  finish({ status: 'completed', exitCode: 0, stdout: 'old result', stderr: '', errorCode: null });
  await execution;
  assert.equal(s.activityStore.sandboxActivity?.toolCallId, next);
  assert.equal(s.activityStore.sandboxActivity?.toolName, 'file_read');
  assert.equal(s.activityStore.sandboxActivity?.status, 'running');
  assert.equal(s.activityStore.sandboxActivity?.outputTail, '');
});

test('128KiB control-character output is serialized once within the configured tool budget', async () => {
  const s = await setup();
  s.transport.execute = async (): Promise<PythonNativeResult> => ({ status: 'completed', exitCode: 0,
    stdout: '\u0001'.repeat(128 * 1024), stderr: '', errorCode: null });
  const parts = await s.tool.execute({ code: 'print(1)' });
  assert.equal(parts[0].type, 'text');
  const text = parts[0].type === 'text' ? parts[0].text : '';
  assert.ok(text.length <= 128 * 1024 * 6 + 2048);
  const result = JSON.parse(text);
  assert.equal(result.stdout.length, 128 * 1024);
  assert.equal(Object.hasOwn(result, 'output'), false);
  assert.ok((s.activityStore.sandboxActivity?.outputTail.length ?? 0) <= 1600);
});

for (const status of ['completed', 'failed', 'cancelled'] as const) {
  test('Python ' + status + ' Activity tail preserves Unicode boundaries and full tool output', async () => {
    const s = await setup();
    const stdout = '😀' + 'x'.repeat(1599);
    s.transport.execute = async (): Promise<PythonNativeResult> => ({ status,
      exitCode: status === 'completed' ? 0 : status === 'failed' ? 1 : null,
      stdout, stderr: '', errorCode: status === 'completed' ? null : status });
    const result = await resultJson(s, { code: 'print(1)' });
    const tail = s.activityStore.sandboxActivity!.outputTail;
    assert.equal(tail, 'x'.repeat(1599));
    assert.equal(result.stdout, stdout);
    assert.equal(s.activityStore.sandboxActivity?.canCancel, false);
  });
}
