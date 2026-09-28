import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createMemoryKeyValueStore } from '../main/ets/chat/kv_store.ts';
import { AgentTaskStore } from '../main/ets/chat/agent_task.ts';
import { SSHProfileStore } from '../main/ets/chat/terminal/profile_store.ts';
import { TerminalRuntime } from '../main/ets/chat/terminal/runtime.ts';
import type { SSHTransportPort } from '../main/ets/chat/terminal/ports.ts';
import type { SSHConnectionOptions, SSHHandle, SSHReadPacket, SSHCloseReason } from '../main/ets/chat/terminal/models.ts';
import type { AbortSignalLike } from '@amber/deepread-domain';
import { AgentToolActivityStore } from '../main/ets/chat/tool_activity.ts';
import { createTerminalTools } from '../main/ets/chat/terminal/tools.ts';
import { createToolRegistry } from '../main/ets/chat/tool_registry.ts';

const running = (): SSHReadPacket => ({ chunks: [], state: 'running', exitCode: null, errorCode: null, errorMessage: null });
class Transport implements SSHTransportPort {
  starts: SSHConnectionOptions[] = [];
  reads = 0;
  closes: SSHCloseReason[] = [];
  writes: Uint8Array[] = [];
  packets: SSHReadPacket[] = [];
  pending = false;
  authFail = false;
  async probe() { return { fingerprintSHA256: 'SHA256:key', hostKeyType: 'ed25519' }; }
  async startExec(_id: string, connection: SSHConnectionOptions, _options: unknown, signal?: AbortSignalLike): Promise<SSHHandle> {
    this.starts.push(connection);
    if (this.authFail) throw Object.assign(new Error('denied'), { code: 'authentication_failed' });
    if (this.pending) return new Promise((_resolve, reject) => {
      const abort = () => reject(Object.assign(new Error('cancelled'), { code: 'cancelled' }));
      signal?.addEventListener?.('abort', abort);
      if (signal?.aborted) abort();
    });
    return { id: String(this.starts.length), kind: 'exec' };
  }
  async startPty(id: string, connection: SSHConnectionOptions, options: unknown, signal?: AbortSignalLike): Promise<SSHHandle> {
    const handle = await this.startExec(id, connection, options, signal);
    return { id: handle.id, kind: 'pty' };
  }
  async read() { this.reads++; return this.packets.shift() ?? running(); }
  async write(_handle: SSHHandle, bytes: Uint8Array) { this.writes.push(bytes); }
  async resize() {}
  async close(_handle: SSHHandle, reason: SSHCloseReason) {
    this.closes.push(reason);
    return { ...running(), state: reason === 'disconnected' ? 'disconnected' : 'cancelled' } as SSHReadPacket;
  }
}
const draft = { id: 'p', name: 'LAN', host: 'host', port: 22, username: 'user', authMethod: 'password' as const };
const setup = async () => {
  const secrets = new Map<string, { secret: string; passphrase: string | null }>();
  const credentials = {
    async save(ref: string, _binding: unknown, c: { secret: string; passphrase: string | null }) { secrets.set(ref, c); },
    async load(ref: string) { return secrets.get(ref) ?? null; },
    async exists(ref: string) { return secrets.has(ref); },
    async delete(ref: string) { secrets.delete(ref); },
  };
  const profiles = await SSHProfileStore.create({ kv: createMemoryKeyValueStore(), credentials });
  await profiles.commitVerified(draft, null, 'SHA256:key', { secret: 'pw', passphrase: null });
  await profiles.setDefault('p');
  const data = new Map<string, string>();
  const files = {
    async mkdirs() {}, async listJsonFileNames() { return []; }, async readText(p: string) { return data.get(p) ?? null; },
    async writeText(p: string, t: string) { data.set(p, t); }, async delete(p: string) { data.delete(p); },
    async exists(p: string) { return data.has(p); }, async isPathInside(root: string, p: string) { return p.startsWith(root + '/'); },
  };
  const taskStore = await AgentTaskStore.create({ taskDir: '/files/tasks', appFilesDir: '/files', files });
  const transport = new Transport();
  const log = new Map<string, string>();
  const logs = { async create(id: string) { const p = '/files/logs/' + id; log.set(p, ''); return p; },
    async append(p: string, text: string) { log.set(p, log.get(p)! + text); } };
  const runtime = new TerminalRuntime({ profiles, credentials, transport, logs, taskStore });
  const request = () => ({ target: runtime.captureTarget(null), command: 'echo hello', cwd: null, timeoutMs: 10000,
    sourceToolName: 'terminal_job_start', sourceConversationId: 'chat' });
  return { profiles, credentials, transport, runtime, request, taskStore, logs, log };
};

test('probe never authenticates; explicit trust precedes verify and failed auth preserves old ref', async () => {
  const s = await setup();
  const old = s.profiles.get('p')!;
  const probe = await s.runtime.probe({ ...draft, host: 'new' });
  assert.equal(s.transport.starts.length, 0);
  await assert.rejects(s.runtime.verifyAndSave({ ...draft, host: 'new' }, probe, { secret: 'new', passphrase: null }, false), /host_trust_required/);
  assert.equal(s.transport.starts.length, 0);
  s.transport.authFail = true;
  await assert.rejects(s.runtime.verifyAndSave({ ...draft, host: 'new' }, probe, { secret: 'new', passphrase: null }, true));
  assert.deepEqual(s.profiles.get('p'), old);
  assert.equal(s.transport.starts[0].expectedFingerprintSHA256, probe.fingerprintSHA256);
});

test('approved default snapshot refuses later default change before native auth', async () => {
  const s = await setup();
  const req = s.request();
  await s.profiles.setDefault(null);
  await assert.rejects(s.runtime.startJob(req), /target_changed/);
  assert.equal(s.transport.starts.length, 0);
});

test('job has real shared task cancellation while handshake is pending', async () => {
  const s = await setup();
  s.transport.pending = true;
  const started = s.runtime.startJob(s.request());
  while (s.taskStore.list().length === 0) await new Promise(r => setTimeout(r, 1));
  const id = s.taskStore.list()[0].taskId;
  await s.taskStore.cancel(id);
  assert.equal((await started).status, 'CANCELLED');
  assert.equal(s.taskStore.read(id)?.cancelCapability, false);
});

test('empty read remains running; split UTF8, stderr and nonzero exit are drained then released', async () => {
  const s = await setup();
  const bytes = new TextEncoder().encode('中😀');
  s.transport.packets = [running(), { ...running(), chunks: [{ bytes: bytes.slice(0, 4), isStderr: false }] },
    { ...running(), chunks: [{ bytes: bytes.slice(4), isStderr: false }, { bytes: new TextEncoder().encode('err'), isStderr: true }], state: 'exited', exitCode: 7 }];
  const job = await s.runtime.startJob(s.request());
  assert.equal(job.exitCode, null);
  const done = await s.runtime.waitJob(job.jobId, 2000);
  assert.equal(done.status, 'FAILED');
  assert.equal(done.exitCode, 7);
  assert.equal(done.stdoutTail, '中😀');
  assert.equal(done.stderrTail, 'err');
  assert.ok(s.log.get(done.outputLogPath)?.includes('中😀'));
  assert.equal(s.transport.closes.filter(r => r === 'release').length, 1);
});

test('wait cancellation does not kill job; start signal detaches; background interrupts real owner', async () => {
  const s = await setup();
  const startSignal = new AbortController();
  const job = await s.runtime.startJob(s.request(), startSignal.signal);
  startSignal.abort();
  const waitSignal = new AbortController();
  const wait = s.runtime.waitJob(job.jobId, 2000, waitSignal.signal);
  waitSignal.abort();
  await assert.rejects(wait, /cancelled/);
  assert.equal(s.runtime.readJob(job.jobId).status, 'RUNNING');
  await s.runtime.interruptForBackground();
  assert.equal(s.runtime.readJob(job.jobId).status, 'INTERRUPTED');
  assert.equal(s.runtime.readJob(job.jobId).exitCode, null);
});

test('session replay starts after subscription, read is cached and approved writes preserve bytes', async () => {
  const s = await setup();
  const bytes = new TextEncoder().encode('$ ');
  s.transport.packets = [{ ...running(), chunks: [{ bytes, isStderr: false }] }];
  const target = s.runtime.captureTarget('p');
  const session = await s.runtime.startSession({ target, cwd: '/a\'b', columns: 80, rows: 24,
    sourceToolName: null, sourceConversationId: null });
  const events: Uint8Array[] = [];
  const unsub = s.runtime.subscribeSession(session.sessionId, e => e.chunks.forEach(c => events.push(c.bytes)));
  assert.equal(s.transport.reads, 0);
  await new Promise(r => setTimeout(r, 160));
  assert.deepEqual(events, [bytes]);
  const readCount = s.transport.reads;
  assert.equal(s.runtime.readSession(session.sessionId).outputTail, '$ ');
  assert.equal(s.transport.reads, readCount);
  await s.runtime.sendSessionBytes(session.sessionId, new Uint8Array([3, 9, 27, 91, 65]));
  assert.deepEqual(s.transport.writes[1], new Uint8Array([3, 9, 27, 91, 65]));
  assert.equal(new TextDecoder().decode(s.transport.writes[0]), "cd -- '/a'\\''b'\n");
  await s.profiles.saveMetadata({ ...draft, username: 'other' }, s.profiles.get('p')!.revision);
  await assert.rejects(s.runtime.execSession(session.sessionId, 'whoami', target), /target_changed/);
  unsub();
  await s.runtime.stopSession(session.sessionId);
  await assert.rejects(s.runtime.sendSessionBytes(session.sessionId, bytes), /session_closed/);
});

test('verify succeeds only after pinned auth command exits; auth-time profile edits reject commit', async () => {
  const s = await setup();
  const probe = await s.runtime.probe(draft);
  const old = s.profiles.get('p')!;
  s.transport.packets = [{ ...running(), state: 'exited', exitCode: 0 }];
  const saved = await s.runtime.verifyAndSave(draft, probe, { secret: 'newpw', passphrase: null }, false);
  assert.equal(saved.revision, old.revision + 1);
  const nextProbe = await s.runtime.probe(draft);
  s.transport.read = async () => {
    await s.profiles.saveMetadata({ ...draft, name: 'concurrent rename' }, saved.revision);
    return { ...running(), state: 'exited', exitCode: 0 };
  };
  await assert.rejects(s.runtime.verifyAndSave(draft, nextProbe, { secret: 'thirdpw', passphrase: null }, false), /profile_conflict/);
  assert.equal(s.profiles.get('p')?.credentialRef, saved.credentialRef);
  assert.equal(s.transport.closes.filter(r => r === 'release').length, 2);
});

test('snapshot is immutable across awaited secret load and retargeting is refused after load', async () => {
  const s = await setup();
  const originalLoad = s.credentials.load;
  let release: () => void = () => {};
  const barrier = new Promise<void>(r => { release = r; });
  s.credentials.load = async ref => { await barrier; return originalLoad(ref); };
  const req = s.request();
  const start = s.runtime.startJob(req);
  req.command = 'unexpected'; req.target.profileId = 'other';
  release();
  const job = await start;
  assert.equal(job.command, 'echo hello');
  assert.equal(s.transport.starts.length, 1);
  await s.runtime.stopJob(job.jobId);

  const changed = await setup();
  let releaseChanged: () => void = () => {};
  const barrierChanged = new Promise<void>(r => { releaseChanged = r; });
  changed.credentials.load = async () => { await barrierChanged; return { secret: 'old', passphrase: null }; };
  const pending = changed.runtime.startJob(changed.request());
  await changed.profiles.saveMetadata({ ...draft, host: 'retargeted' }, changed.profiles.get('p')!.revision);
  releaseChanged();
  await assert.rejects(pending, /target_changed/);
  assert.equal(changed.transport.starts.length, 0);
});

test('start handshake failure creates FAILED task; timeout/disconnect/cancel have no fake exit code', async () => {
  const s = await setup(); s.transport.authFail = true;
  const failed = await s.runtime.startJob(s.request());
  assert.equal(failed.status, 'FAILED');
  assert.equal(s.taskStore.read(failed.jobId)?.cancelCapability, false);
  for (const state of ['timed_out', 'disconnected', 'cancelled'] as const) {
    const next = await setup();
    next.transport.packets = [{ ...running(), state }];
    const job = await next.runtime.startJob(next.request());
    const terminal = await next.runtime.waitJob(job.jobId, 1000);
    assert.equal(terminal.status, state === 'timed_out' ? 'TIMED_OUT' : state === 'disconnected' ? 'INTERRUPTED' : 'CANCELLED');
    assert.equal(terminal.exitCode, null);
    assert.equal(next.taskStore.read(job.jobId)?.cancelCapability, false);
  }
});

test('full log preserves output beyond bounded tails with correct UTF8 task offset', async () => {
  const s = await setup();
  const text = '中😀'.repeat(8000);
  const encoded = new TextEncoder().encode(text);
  s.transport.packets = [{ ...running(), chunks: [{ bytes: encoded.slice(0, 40000), isStderr: false }] },
    { ...running(), chunks: [{ bytes: encoded.slice(40000), isStderr: false }], state: 'exited', exitCode: 0 }];
  const job = await s.runtime.startJob(s.request());
  const done = await s.runtime.waitJob(job.jobId, 1500);
  assert.equal(done.status, 'COMPLETED');
  assert.equal(s.log.get(done.outputLogPath), text);
  assert.ok(done.stdoutTail.length <= 16384 && done.outputTail.length <= 32768);
  assert.equal(s.taskStore.read(job.jobId)?.outputOffset, encoded.length);
  assert.equal(s.taskStore.read(job.jobId)?.retryPolicy.retryable, false);
  const restored = new TerminalRuntime({ profiles: s.profiles, credentials: s.credentials,
    transport: s.transport, logs: s.logs, taskStore: s.taskStore });
  assert.deepEqual(restored.readJob(job.jobId), done);
});

test('log append failure closes owner and preserves real FAILED state', async () => {
  const s = await setup();
  s.logs.append = async () => { throw new Error('disk full'); };
  s.transport.packets = [{ ...running(), chunks: [{ bytes: new Uint8Array([65]), isStderr: false }] }];
  const job = await s.runtime.startJob(s.request());
  const done = await s.runtime.waitJob(job.jobId, 1000);
  assert.equal(done.status, 'FAILED');
  assert.equal(done.exitCode, null);
  assert.deepEqual(s.transport.closes, ['cancelled']);
});

test('background cancels pending job, PTY and verification handshakes without resending', async () => {
  for (const kind of ['job', 'session', 'verify'] as const) {
    const s = await setup(); s.transport.pending = true;
    const started = kind === 'job' ? s.runtime.startJob(s.request()) : kind === 'session' ?
      s.runtime.startSession({ target: s.runtime.captureTarget('p'), cwd: null, columns: 80, rows: 24,
        sourceToolName: null, sourceConversationId: null }) :
      s.runtime.verifyAndSave(draft, await s.runtime.probe(draft), { secret: 'pw', passphrase: null }, false);
    const observed = started.then(v => v, error => error);
    while (s.transport.starts.length === 0) await new Promise(r => setTimeout(r, 1));
    await s.runtime.interruptForBackground();
    const result = await observed;
    if (kind !== 'verify') assert.equal(result.status, 'INTERRUPTED');
    assert.equal(s.transport.starts.length, 1);
  }
});

test('execute call abort cancels real job; recovered live task reports INTERRUPTED without native restart', async () => {
  const s = await setup();
  const controller = new AbortController();
  const execute = s.runtime.execute(s.request(), controller.signal);
  while (s.taskStore.list().length === 0) await new Promise(r => setTimeout(r, 1));
  controller.abort();
  await assert.rejects(execute, /cancelled/);
  assert.equal(s.taskStore.list()[0].status, 'CANCELLED');
  const next = await setup();
  const live = await next.runtime.startJob(next.request());
  const restored = new TerminalRuntime({ profiles: next.profiles, credentials: next.credentials,
    transport: next.transport, logs: next.logs, taskStore: next.taskStore });
  assert.equal(restored.readJob(live.jobId).status, 'INTERRUPTED');
  assert.equal(next.transport.starts.length, 1);
  await next.runtime.stopJob(live.jobId);
});

test('nine tools keep approval policy, captured target and real RUNNING activity', async () => {
  const s = await setup();
  const activityStore = new AgentToolActivityStore();
  const tools = createTerminalTools({ runtime: s.runtime, profiles: s.profiles, activityStore, conversationId: 'chat' });
  assert.equal(tools.length, 9);
  const registry = createToolRegistry(tools);
  for (const name of ['terminal_job_read', 'terminal_job_wait', 'terminal_session_read']) {
    assert.equal(registry.metadataFor(name)?.needsApproval, false);
  }
  for (const tool of tools) assert.equal(tool.allowsAutoApproval, false);
  const startTool = tools.find(t => t.name === 'terminal_job_start')!;
  assert.ok(startTool.description.includes('user@host:22'));
  const result = await startTool.execute({ command: 'sleep 20' });
  const json = JSON.parse(result[0].type === 'text' ? result[0].text : '{}');
  assert.equal(json.running, true); assert.equal(json.exit_code, null);
  assert.equal(activityStore.sandboxActivity?.status, 'running');
  assert.equal(activityStore.sandboxActivity?.conversationId, 'chat');
  await s.runtime.stopJob(json.job_id);
  await new Promise(r => setTimeout(r, 120));
  assert.equal(activityStore.sandboxActivity?.status, 'cancelled');
  await s.profiles.setDefault(null);
  const rejected = await startTool.execute({ command: 'unexpected' });
  const failure = JSON.parse(rejected[0].type === 'text' ? rejected[0].text : '{}');
  assert.equal(failure.error_code, 'target_changed');
  assert.equal(s.transport.starts.length, 1);
});

test('execute returns real RUNNING after bounded five-second wait and stop is idempotent', async () => {
  const s = await setup();
  const before = Date.now();
  const job = await s.runtime.execute(s.request());
  assert.ok(Date.now() - before >= 4500);
  assert.equal(job.status, 'RUNNING'); assert.equal(job.exitCode, null);
  const first = await s.runtime.stopJob(job.jobId);
  const second = await s.runtime.stopJob(job.jobId);
  assert.deepEqual(second, first);
  assert.deepEqual(s.transport.closes, ['cancelled']);
});

test('completed job cannot be overwritten by late TaskStore cancellation or late packets', async () => {
  const s = await setup();
  s.transport.packets = [{ ...running(), state: 'exited', exitCode: 0 },
    { ...running(), chunks: [{ bytes: new TextEncoder().encode('late'), isStderr: false }] }];
  const job = await s.runtime.startJob(s.request());
  const terminal = await s.runtime.waitJob(job.jobId, 1000);
  await s.taskStore.cancel(job.jobId);
  await s.runtime.stopJob(job.jobId);
  await new Promise(r => setTimeout(r, 150));
  assert.equal(s.taskStore.read(job.jobId)?.status, 'COMPLETED');
  assert.deepEqual(s.runtime.readJob(job.jobId), terminal);
  assert.equal(s.transport.reads, 1);
});

test('one session stop preserves other sessions; PTY resize uses real dimensions and inputs after close reject', async () => {
  const s = await setup();
  const request = { target: s.runtime.captureTarget('p'), cwd: null, columns: 80, rows: 24,
    sourceToolName: null, sourceConversationId: null };
  const a = await s.runtime.startSession(request);
  const b = await s.runtime.startSession(request);
  await s.runtime.resizeSession(b.sessionId, 100, 40);
  assert.equal(s.runtime.readSession(b.sessionId).columns, 100);
  await s.runtime.stopSession(a.sessionId);
  assert.equal(s.runtime.readSession(b.sessionId).status, 'RUNNING');
  await assert.rejects(s.runtime.resizeSession(a.sessionId, 1, 1), /session_closed/);
  await assert.rejects(s.runtime.resizeSession(b.sessionId, 0, 1), /invalid_arguments/);
  await s.runtime.stopSession(b.sessionId);
});

test('background aborts probe before credentials or auth are touched', async () => {
  const s = await setup();
  let entered = false;
  s.transport.probe = async (_id?: string, _options?: unknown, signal?: AbortSignalLike) => {
    entered = true;
    return new Promise((_resolve, reject) => {
      signal?.addEventListener?.('abort', () => reject(new Error('cancelled')));
    });
  };
  const probe = s.runtime.probe(draft);
  const observed = probe.catch(error => error);
  assert.equal(entered, true);
  await s.runtime.interruptForBackground();
  assert.match((await observed).message, /cancelled/);
  assert.equal(s.transport.starts.length, 0);
});

for (const kind of ['job', 'session'] as const) {
  test('background owns deferred log creation before ' + kind + ' start, including later foreground', async () => {
    const s = await setup();
    let reached: () => void = () => {};
    let release: () => void = () => {};
    const entered = new Promise<void>(r => { reached = r; });
    const barrier = new Promise<void>(r => { release = r; });
    const originalCreate = s.logs.create;
    s.logs.create = async id => { reached(); await barrier; return originalCreate(id); };
    const pending = kind === 'job' ? s.runtime.startJob(s.request()) : s.runtime.startSession({
      target: s.runtime.captureTarget(null), cwd: null, columns: 80, rows: 24,
      sourceToolName: null, sourceConversationId: null });
    await entered;
    await s.runtime.interruptForBackground();
    // The platform can be foreground again before the late file operation completes.
    release();
    const done = await pending;
    if ('jobId' in done) await s.runtime.stopJob(done.jobId);
    else await s.runtime.stopSession(done.sessionId);
    assert.equal(s.transport.starts.length, 0, 'pre-background work must not authenticate later');
    assert.equal(done.status, 'INTERRUPTED');
  });
}

test('PTY tool activity replaces cumulative output and preserves real repeated prompts', async () => {
  const s = await setup();
  const activityStore = new AgentToolActivityStore();
  const tools = createTerminalTools({ runtime: s.runtime, profiles: s.profiles, activityStore, conversationId: 'chat' });
  s.transport.packets = [{ ...running(), chunks: [{ bytes: new TextEncoder().encode('$ '), isStderr: false }] }];
  const result = await tools.find(t => t.name === 'terminal_session_start')!.execute({});
  const output = JSON.parse(result[0].type === 'text' ? result[0].text : '{}');
  await new Promise(r => setTimeout(r, 460));
  const idleTail = activityStore.sandboxActivity?.outputTail;
  s.transport.packets.push({ ...running(), chunks: [{ bytes: new TextEncoder().encode('$ '), isStderr: false }] });
  await new Promise(r => setTimeout(r, 160));
  const repeatedTail = activityStore.sandboxActivity?.outputTail;
  await s.runtime.stopSession(output.session_id);
  assert.equal(idleTail, '$');
  assert.equal(repeatedTail, '$ $');
});

for (const action of ['stop', 'background'] as const) {
  test('session ' + action + ' wakes a transport write blocked until close', async () => {
    const s = await setup();
    const session = await s.runtime.startSession({ target: s.runtime.captureTarget(null), cwd: null,
      columns: 80, rows: 24, sourceToolName: null, sourceConversationId: null });
    let reached: () => void = () => {};
    let release: () => void = () => {};
    const entered = new Promise<void>(r => { reached = r; });
    const blocked = new Promise<void>(r => { release = r; });
    s.transport.write = async () => { reached(); await blocked; };
    const originalClose = s.transport.close.bind(s.transport);
    s.transport.close = async (handle, reason) => { release(); return originalClose(handle, reason); };
    const writing = s.runtime.sendSessionBytes(session.sessionId, new Uint8Array([97]));
    await entered;
    const stopping = action === 'stop' ? s.runtime.stopSession(session.sessionId) : s.runtime.interruptForBackground();
    const responsive = await Promise.race([stopping.then(() => true), new Promise<boolean>(r => setTimeout(() => r(false), 150))]);
    const closes = s.transport.closes.length;
    release(); await writing; await stopping;
    assert.equal(responsive, true, 'close must reach native before the blocked write completes');
    assert.equal(closes, 1);
    assert.equal(s.runtime.readSession(session.sessionId).status, action === 'stop' ? 'CANCELLED' : 'INTERRUPTED');
  });
}

test('cancelled queued session_exec sends no command and keeps its PTY alive', async () => {
  const s = await setup();
  const session = await s.runtime.startSession({ target: s.runtime.captureTarget(null), cwd: null,
    columns: 80, rows: 24, sourceToolName: null, sourceConversationId: null });
  const tool = createTerminalTools({ runtime: s.runtime, profiles: s.profiles,
    activityStore: new AgentToolActivityStore(), conversationId: 'chat' }).find(t => t.name === 'terminal_session_exec')!;
  let reached: () => void = () => {};
  let release: () => void = () => {};
  const entered = new Promise<void>(r => { reached = r; });
  const blocked = new Promise<void>(r => { release = r; });
  const originalWrite = s.transport.write.bind(s.transport);
  let calls = 0;
  s.transport.write = async (handle, bytes) => { if (++calls === 1) { reached(); await blocked; } await originalWrite(handle, bytes); };
  const manual = s.runtime.sendSessionBytes(session.sessionId, new Uint8Array([97]));
  await entered;
  const controller = new AbortController();
  const execution = tool.execute({ session_id: session.sessionId, command: 'echo UNSENT' }, controller.signal);
  controller.abort(); release(); await manual;
  const result = await execution;
  const output = JSON.parse(result[0].type === 'text' ? result[0].text : '{}');
  const sent = s.transport.writes.map(bytes => new TextDecoder().decode(bytes));
  const status = s.runtime.readSession(session.sessionId).status;
  await s.runtime.stopSession(session.sessionId);
  assert.equal(sent.includes('echo UNSENT\n'), false);
  assert.equal(output.error_code, 'cancelled');
  assert.equal(status, 'RUNNING');
});

test('close-triggered write rejection preserves INTERRUPTED and final raw bytes with one close', async () => {
  const s = await setup();
  const session = await s.runtime.startSession({ target: s.runtime.captureTarget(null), cwd: null,
    columns: 80, rows: 24, sourceToolName: null, sourceConversationId: null });
  let reached: () => void = () => {};
  let rejectWrite: (error: Error) => void = () => {};
  const entered = new Promise<void>(r => { reached = r; });
  s.transport.write = async () => { reached(); await new Promise<void>((_resolve, reject) => { rejectWrite = reject; }); };
  const bytes = new TextEncoder().encode('末尾😀');
  const observed: Uint8Array[] = [];
  s.runtime.subscribeSession(session.sessionId, e => e.chunks.forEach(c => observed.push(c.bytes)));
  const originalClose = s.transport.close.bind(s.transport);
  s.transport.close = async (handle, reason) => {
    rejectWrite(Object.assign(new Error('channel closed'), { code: 'channel_error' }));
    return { ...await originalClose(handle, reason), chunks: [{ bytes, isStderr: false }] };
  };
  const writing = s.runtime.sendSessionBytes(session.sessionId, new Uint8Array([97])).then(() => null, error => error);
  await entered;
  const background = s.runtime.interruptForBackground();
  const concurrentStop = s.runtime.stopSession(session.sessionId);
  await Promise.all([background, concurrentStop]);
  assert.match((await writing).message, /channel closed/);
  const done = s.runtime.readSession(session.sessionId);
  assert.equal(done.status, 'INTERRUPTED'); assert.equal(done.exitCode, null);
  assert.equal(done.outputTail, '末尾😀');
  assert.equal(s.log.get(done.outputLogPath), '末尾😀');
  assert.deepEqual(observed, [bytes]);
  assert.deepEqual(s.transport.closes, ['disconnected']);
});

test('background close wakes blocked initial cwd write before startSession has returned', async () => {
  const s = await setup();
  let reached: () => void = () => {};
  let release: () => void = () => {};
  const entered = new Promise<void>(r => { reached = r; });
  const blocked = new Promise<void>(r => { release = r; });
  s.transport.write = async () => { reached(); await blocked; };
  const originalClose = s.transport.close.bind(s.transport);
  s.transport.close = async (handle, reason) => { release(); return originalClose(handle, reason); };
  const starting = s.runtime.startSession({ target: s.runtime.captureTarget(null), cwd: '/work',
    columns: 80, rows: 24, sourceToolName: null, sourceConversationId: null });
  await entered;
  const background = s.runtime.interruptForBackground();
  const responsive = await Promise.race([background.then(() => true), new Promise<boolean>(r => setTimeout(() => r(false), 150))]);
  release(); await background;
  const session = await starting;
  assert.equal(responsive, true);
  assert.equal(session.status, 'INTERRUPTED');
  assert.deepEqual(s.transport.closes, ['disconnected']);
});
