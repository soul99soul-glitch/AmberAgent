import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { AbortSignalLike } from '@amber/deepread-domain';
import { AgentTaskStore } from '../main/ets/chat/agent_task.ts';
import type { AgentTaskFilePort } from '../main/ets/chat/agent_task.ts';
import { PythonRuntime } from '../main/ets/chat/python/runtime.ts';
import type { PythonExecuteOptions, PythonExecuteRequest, PythonNativeResult } from '../main/ets/chat/python/models.ts';
import type { PythonTransportPort } from '../main/ets/chat/python/ports.ts';

const success = (): PythonNativeResult => ({ status: 'completed', exitCode: 0, stdout: '中😀\n', stderr: '', errorCode: null });
class Transport implements PythonTransportPort {
  calls: Array<{ id: string; options: PythonExecuteOptions; signal?: AbortSignalLike }> = [];
  result = success();
  pending = false;
  finish: (result: PythonNativeResult) => void = () => {};
  rejection: Error | null = null;
  version(): string { return '3.14.7'; }
  async execute(id: string, options: PythonExecuteOptions, signal?: AbortSignalLike): Promise<PythonNativeResult> {
    this.calls.push({ id, options, signal });
    if (this.rejection !== null) throw this.rejection;
    if (!this.pending) return this.result;
    return new Promise(resolve => { this.finish = resolve; });
  }
}
const setup = async () => {
  const data = new Map<string, string>();
  const files: AgentTaskFilePort = {
    async mkdirs() {}, async listJsonFileNames() { return []; }, async readText(p) { return data.get(p) ?? null; },
    async writeText(p, value) { data.set(p, value); }, async delete(p) { data.delete(p); },
    async exists(p) { return data.has(p); }, async isPathInside(root, p) { return p.startsWith(root + '/'); },
  };
  const taskStore = await AgentTaskStore.create({ taskDir: '/files/tasks', appFilesDir: '/files', files });
  const transport = new Transport();
  const runtime = new PythonRuntime({ transport, taskStore });
  const request: PythonExecuteRequest = { source: 'print(input())', stdin: 'private input', timeoutMs: 15000,
    sourceToolName: 'python_execute', sourceConversationId: 'chat' };
  return { data, files, taskStore, transport, runtime, request };
};
const untilStarted = async (transport: Transport): Promise<void> => {
  while (transport.calls.length === 0) await new Promise(resolve => setTimeout(resolve, 1));
};

test('Python preserves native Unicode output and registers one shared task without source/stdin persistence', async () => {
  const s = await setup();
  const result = await s.runtime.execute(s.request);
  assert.equal(s.runtime.version(), '3.14.7');
  assert.equal(result.runtime, 'embedded_python');
  assert.equal(result.status, 'COMPLETED');
  assert.equal(result.stdout, '中😀\n');
  assert.equal(result.exitCode, 0);
  assert.equal(s.transport.calls[0].id, result.runId);
  assert.deepEqual(s.transport.calls[0].options, { source: s.request.source, stdin: s.request.stdin, timeoutMs: 15000 });
  const task = s.taskStore.read(result.runId)!;
  assert.equal(task.runtime, 'embedded_python');
  assert.equal(task.sourceToolName, 'python_execute');
  assert.equal(task.sourceConversationId, 'chat');
  assert.equal(task.cancelCapability, false);
  assert.equal(task.queueState, 'TERMINAL');
  assert.ok(!JSON.stringify(task).includes(s.request.source));
  assert.ok(!JSON.stringify(task).includes(s.request.stdin));
});

test('task cancellation waits for real native settlement and unregisters the cancellation callback', async () => {
  const s = await setup(); s.transport.pending = true;
  const execution = s.runtime.execute(s.request);
  await untilStarted(s.transport);
  const id = s.transport.calls[0].id;
  let stopped = false;
  const cancellation = s.taskStore.cancel(id).then(() => { stopped = true; });
  await new Promise(resolve => setTimeout(resolve, 5));
  assert.equal(s.transport.calls[0].signal?.aborted, true);
  assert.equal(s.taskStore.read(id)?.status, 'RUNNING');
  assert.equal(s.taskStore.read(id)?.cancelCapability, true);
  assert.equal(stopped, false);
  s.transport.finish({ status: 'cancelled', exitCode: null, stdout: 'partial', stderr: '', errorCode: 'cancelled' });
  assert.equal((await execution).status, 'CANCELLED');
  await cancellation;
  assert.equal(s.taskStore.read(id)?.status, 'CANCELLED');
  assert.equal(s.taskStore.read(id)?.cancelCapability, false);
  assert.equal((await s.taskStore.cancel(id)).status, 'CANCELLED');
});

test('caller abort waits for native result and cancellation preserves partial output', async () => {
  const s = await setup(); s.transport.pending = true;
  const signal = new AbortController();
  let settled = false;
  const execution = s.runtime.execute(s.request, signal.signal).then(result => { settled = true; return result; });
  await untilStarted(s.transport); signal.abort();
  await new Promise(resolve => setTimeout(resolve, 5));
  assert.equal(settled, false);
  s.transport.finish({ status: 'cancelled', exitCode: null, stdout: 'partial', stderr: 'err', errorCode: 'cancelled' });
  const result = await execution;
  assert.equal(result.status, 'CANCELLED');
  assert.equal(result.stdout, 'partial');
  assert.equal(result.stderr, 'err');
  assert.equal(result.exitCode, null);
});

test('background interruption waits for actual termination; later execution starts fresh', async () => {
  const s = await setup(); s.transport.pending = true;
  const execution = s.runtime.execute(s.request);
  await untilStarted(s.transport);
  let stopped = false;
  const interruption = s.runtime.interruptForBackground().then(() => { stopped = true; });
  await new Promise(resolve => setTimeout(resolve, 5));
  assert.equal(stopped, false);
  assert.equal(s.transport.calls[0].signal?.aborted, true);
  s.transport.finish({ status: 'cancelled', exitCode: null, stdout: '', stderr: '', errorCode: 'cancelled' });
  const interrupted = await execution;
  await interruption;
  assert.equal(interrupted.status, 'INTERRUPTED');
  assert.equal(interrupted.errorCode, 'background_interrupted');
  assert.equal(s.taskStore.read(interrupted.runId)?.cancelCapability, false);
  s.transport.pending = false;
  const next = await s.runtime.execute(s.request);
  assert.equal(next.status, 'COMPLETED');
  assert.notEqual(next.runId, interrupted.runId);
});

test('cancellation cannot overwrite native completion that wins the race', async () => {
  const s = await setup(); s.transport.pending = true;
  const execution = s.runtime.execute(s.request);
  await untilStarted(s.transport);
  const id = s.transport.calls[0].id;
  const cancellation = s.taskStore.cancel(id);
  s.transport.finish(success());
  assert.equal((await execution).status, 'COMPLETED');
  assert.equal((await cancellation).status, 'COMPLETED');
});

test('timeout and Python exception remain distinct real terminal states', async () => {
  const s = await setup();
  s.transport.result = { status: 'timed_out', exitCode: null, stdout: 'tick', stderr: '', errorCode: 'timed_out' };
  const timeout = await s.runtime.execute(s.request);
  assert.equal(timeout.status, 'TIMED_OUT');
  assert.equal(timeout.exitCode, null);
  s.transport.result = { status: 'failed', exitCode: 1, stdout: '', stderr: 'ValueError: bad', errorCode: 'python_exception' };
  const failed = await s.runtime.execute(s.request);
  assert.equal(failed.status, 'FAILED');
  assert.equal(failed.stderr, 'ValueError: bad');
  assert.equal(failed.exitCode, 1);
});

test('transport failures close task capability without printing arbitrary native error dumps', async () => {
  const s = await setup(); s.transport.rejection = new Error('private source in native dump');
  const result = await s.runtime.execute(s.request);
  assert.equal(result.status, 'FAILED');
  assert.equal(result.errorCode, 'python_transport_error');
  assert.equal(result.stderr, '');
  assert.ok(!JSON.stringify(s.taskStore.list()).includes('private source in native dump'));
  assert.equal(s.taskStore.read(result.runId)?.cancelCapability, false);
});

test('already aborted invocation settles cancelled without calling native', async () => {
  const s = await setup(); const signal = new AbortController(); signal.abort();
  const result = await s.runtime.execute(s.request, signal.signal);
  assert.equal(result.status, 'CANCELLED');
  assert.equal(s.transport.calls.length, 0);
  assert.equal(s.taskStore.read(result.runId)?.cancelCapability, false);
});

test('background during task registration prevents native startup and records INTERRUPTED', async () => {
  const s = await setup();
  const originalWrite = s.files.writeText;
  let release: () => void = () => {};
  let registering = false;
  s.files.writeText = async (path, value) => {
    if (!registering) {
      registering = true;
      await new Promise<void>(resolve => { release = resolve; });
    }
    await originalWrite(path, value);
  };
  const execution = s.runtime.execute(s.request);
  while (!registering) await new Promise(resolve => setTimeout(resolve, 1));
  const interruption = s.runtime.interruptForBackground();
  release();
  const result = await execution;
  await interruption;
  assert.equal(result.status, 'INTERRUPTED');
  assert.equal(s.transport.calls.length, 0);
  assert.equal(s.taskStore.read(result.runId)?.cancelCapability, false);
});

test('validation enforces UTF8 source/stdin limits and bounded integer timeout before registering', async () => {
  const s = await setup();
  for (const patch of [{ source: '' }, { source: ' \n' }, { source: '\0' },
    { source: '中'.repeat(87382) }, { stdin: '😀'.repeat(16385) },
    { timeoutMs: 0 }, { timeoutMs: 60001 }, { timeoutMs: 1.5 }, { timeoutMs: NaN }]) {
    await assert.rejects(s.runtime.execute({ ...s.request, ...patch }), /invalid_arguments/);
  }
  assert.equal(s.taskStore.list().length, 0);
  assert.equal(s.transport.calls.length, 0);
  await s.runtime.execute({ ...s.request, source: 'x'.repeat(262144), stdin: '😀'.repeat(16384), timeoutMs: 60000 });
  assert.equal(s.transport.calls.length, 1);
});

test('request is copied before async registration so approved code cannot be swapped by mutation', async () => {
  const s = await setup(); s.transport.pending = true;
  const execution = s.runtime.execute(s.request);
  s.request.source = 'changed'; s.request.stdin = 'changed'; s.request.timeoutMs = 1;
  await untilStarted(s.transport);
  assert.equal(s.transport.calls[0].options.source, 'print(input())');
  assert.equal(s.transport.calls[0].options.stdin, 'private input');
  assert.equal(s.transport.calls[0].options.timeoutMs, 15000);
  s.transport.finish(success()); await execution;
});

test('background interrupts every live invocation rather than only the most recent controller', async () => {
  const s = await setup();
  const finishes: Array<(result: PythonNativeResult) => void> = [];
  s.transport.execute = async (id, options, signal) => {
    s.transport.calls.push({ id, options, signal });
    return new Promise(resolve => finishes.push(resolve));
  };
  const first = s.runtime.execute(s.request), second = s.runtime.execute(s.request);
  while (finishes.length < 2) await new Promise(resolve => setTimeout(resolve, 1));
  const interruption = s.runtime.interruptForBackground();
  assert.ok(s.transport.calls.every(call => call.signal?.aborted));
  for (const finish of finishes) finish({ status: 'cancelled', exitCode: null, stdout: '', stderr: '', errorCode: 'cancelled' });
  assert.equal((await first).status, 'INTERRUPTED');
  assert.equal((await second).status, 'INTERRUPTED');
  await interruption;
});
