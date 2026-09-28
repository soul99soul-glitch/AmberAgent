import { test } from 'node:test';
import type { TestContext } from 'node:test';
import assert from 'node:assert/strict';
import type { AbortSignalLike } from '@amber/deepread-domain';
import { createMemoryKeyValueStore } from '../main/ets/chat/kv_store.ts';
import { AgentTaskStore } from '../main/ets/chat/agent_task.ts';
import { SSHProfileStore } from '../main/ets/chat/terminal/profile_store.ts';
import { SSHTargetResolver } from '../main/ets/chat/terminal/target_resolver.ts';
import type { SSHTransportPort } from '../main/ets/chat/terminal/ports.ts';
import type { SSHConnectionOptions, SSHExecOptions, SSHHandle, SSHReadPacket, SSHCloseReason } from '../main/ets/chat/terminal/models.ts';
import { MoshRuntime } from '../main/ets/chat/mosh/runtime.ts';
import type { MoshTransportPort } from '../main/ets/chat/mosh/ports.ts';
import type { MoshHandle, MoshOptions, MoshPacket, MoshCloseReason } from '../main/ets/chat/mosh/models.ts';
import { createMoshTools } from '../main/ets/chat/mosh/tools.ts';
import { AgentToolActivityStore } from '../main/ets/chat/tool_activity.ts';

const key = 'abcdefghijklmnopqrstuw';
const bytes = (text: string): Uint8Array => new TextEncoder().encode(text);
const sshPacket = (text: string, state: SSHReadPacket['state'] = 'exited', exitCode: number | null = 0): SSHReadPacket =>
  ({ chunks: [{ bytes: bytes(text), isStderr: false }], state, exitCode, errorCode: null, errorMessage: null });
const moshPacket = (text: string = '', state: MoshPacket['state'] = 'running'): MoshPacket =>
  ({ bytes: bytes(text), state, errorCode: null, lastHeardMs: 10 });
class SSH implements SSHTransportPort {
  calls: Array<{ connection: SSHConnectionOptions; options: SSHExecOptions }> = [];
  closes: SSHCloseReason[] = [];
  peerAddress: string | undefined = '2001:db8::1';
  packets: SSHReadPacket[] = [sshPacket('MOSH CONNECT 60001 ' + key + '\n')];
  async probe() { return { fingerprintSHA256: 'SHA256:trusted', hostKeyType: 'ed25519' }; }
  async startExec(_id: string, connection: SSHConnectionOptions, options: SSHExecOptions, _signal?: AbortSignalLike): Promise<SSHHandle> {
    this.calls.push({ connection, options }); return { id: 'ssh', kind: 'exec', peerAddress: this.peerAddress };
  }
  async startPty(): Promise<SSHHandle> { throw new Error('Mosh bootstrap must not use PTY/job'); }
  async read(): Promise<SSHReadPacket> { return this.packets.shift() ?? sshPacket('', 'running', null); }
  async write() { throw new Error('Mosh input must use UDP'); }
  async resize() { throw new Error('Mosh resize must use UDP'); }
  async close(_handle: SSHHandle, reason: SSHCloseReason): Promise<SSHReadPacket> {
    this.closes.push(reason); return sshPacket('', reason === 'release' ? 'exited' : reason, null);
  }
}
class UDP implements MoshTransportPort {
  calls: MoshOptions[] = [];
  closes: MoshCloseReason[] = [];
  writes: Uint8Array[] = [];
  sizes: number[][] = [];
  packets: MoshPacket[] = [];
  reads = 0;
  async start(_id: string, options: MoshOptions, _signal?: AbortSignalLike): Promise<MoshHandle> {
    this.calls.push({ ...options }); return { id: 'udp', kind: 'mosh' };
  }
  async read(): Promise<MoshPacket> { this.reads++; return this.packets.shift() ?? moshPacket(); }
  async write(_handle: MoshHandle, value: Uint8Array) { this.writes.push(value); }
  async resize(_handle: MoshHandle, columns: number, rows: number) { this.sizes.push([columns, rows]); }
  async close(_handle: MoshHandle, reason: MoshCloseReason): Promise<MoshPacket> {
    this.closes.push(reason); return moshPacket('', reason === 'release' ? 'closed' : reason);
  }
}
const draft = { id: 'p', name: 'LAN', host: 'load-balanced.example', port: 22, username: 'user', authMethod: 'password' as const };
const setup = async (context?: TestContext) => {
  const secrets = new Map<string, { secret: string; passphrase: string | null }>();
  const credentials = { async save(id: string, _binding: unknown, value: { secret: string; passphrase: string | null }) { secrets.set(id, value); },
    async load(id: string) { return secrets.get(id) ?? null; }, async exists(id: string) { return secrets.has(id); }, async delete(id: string) { secrets.delete(id); } };
  const profiles = await SSHProfileStore.create({ kv: createMemoryKeyValueStore(), credentials });
  await profiles.commitVerified(draft, null, 'SHA256:trusted', { secret: 'password', passphrase: null });
  await profiles.setDefault('p');
  const saved = new Map<string, string>();
  const files = { async mkdirs() {}, async listJsonFileNames(dir: string) { return [...saved.keys()].filter(path => path.startsWith(dir + '/')).map(path => path.slice(dir.length + 1)); }, async readText(path: string) { return saved.get(path) ?? null; },
    async writeText(path: string, text: string) { saved.set(path, text); }, async delete(path: string) { saved.delete(path); },
    async exists(path: string) { return saved.has(path); }, async isPathInside(root: string, path: string) { return path.startsWith(root + '/'); } };
  const taskStore = await AgentTaskStore.create({ taskDir: '/private/tasks', appFilesDir: '/private', files });
  const resolver = new SSHTargetResolver({ profiles, credentials });
  const ssh = new SSH(); const udp = new UDP();
  const runtime = new MoshRuntime({ targetResolver: resolver, sshTransport: ssh, transport: udp, taskStore });
  context?.after(async () => { await runtime.interruptForBackground(); });
  const request = () => ({ target: runtime.captureTarget(null), columns: 80, rows: 24, sourceToolName: 'terminal_mosh_session_start', sourceConversationId: 'chat' });
  return { runtime, request, profiles, credentials, resolver, ssh, udp, taskStore, saved, files };
};

test('Mosh bootstraps only pinned SSH, uses its actual peer IP and never persists the CONNECT key', async (t) => {
  const s = await setup(t);
  s.ssh.packets = [sshPacket('banner\nMOSH CON', 'running', null), sshPacket('NECT 60001 ' + key + '\r\n')];
  const result = await s.runtime.startSession(s.request());
  assert.equal(result.runtime, 'remote_mosh'); assert.equal(result.status, 'RUNNING');
  assert.equal(s.ssh.calls[0].connection.expectedFingerprintSHA256, 'SHA256:trusted');
  assert.equal(s.ssh.calls[0].options.command, "mosh-server new -s -c 256 -l 'LANG=C.UTF-8' -l 'LC_ALL=C.UTF-8'");
  assert.deepEqual(s.ssh.closes, ['release']);
  assert.equal(s.udp.calls[0].peerAddress, '2001:db8::1');
  assert.equal(s.udp.calls[0].port, 60001); assert.equal(s.udp.calls[0].sessionKey, key);
  assert.equal(result.outputTail, ''); assert.equal('exitCode' in result, false);
  assert.equal(JSON.stringify(s.taskStore.list()).includes(key), false);
  assert.equal([...s.saved.values()].join('').includes(key), false);
  await s.runtime.stopSession(result.sessionId);
});

test('dedicated Mosh tools require start/exec/stop approval and report live session state without exit_code', async (t) => {
  const s = await setup(t);
  const activityStore = new AgentToolActivityStore();
  const tools = createMoshTools({ runtime: s.runtime, profiles: s.profiles, activityStore, conversationId: 'chat' });
  assert.deepEqual(tools.map(t => t.name), ['terminal_mosh_session_start', 'terminal_mosh_session_exec', 'terminal_mosh_session_read', 'terminal_mosh_session_stop']);
  for (const tool of tools) {
    assert.equal(tool.allowsAutoApproval, false);
    assert.equal(tool.needsApproval, tool.name !== 'terminal_mosh_session_read');
  }
  const parts = await tools[0].execute({});
  const result = JSON.parse((parts[0] as { text: string }).text);
  assert.equal(result.runtime, 'remote_mosh'); assert.equal(result.status, 'running');
  assert.equal(result.connection_state, 'running'); assert.equal('exit_code' in result, false);
  assert.equal(activityStore.sandboxActivity?.status, 'running');
  assert.equal(JSON.stringify(activityStore.sandboxActivity).includes(key), false);
  await tools[1].execute({ session_id: result.session_id, command: 'printf hello' });
  assert.equal(new TextDecoder().decode(s.udp.writes[0]), 'printf hello\n');
  const reads = s.udp.reads;
  await tools[2].execute({ session_id: result.session_id });
  assert.equal(s.udp.reads, reads);
  await tools[3].execute({ session_id: result.session_id });
  assert.equal(activityStore.sandboxActivity?.status, 'cancelled');
});

test('invalid UDP ports and oversized terminal cell counts fail before any SSH bootstrap', async (t) => {
  const s = await setup(t);
  for (const port of [0, -1, 65536, 60000.5]) {
    await assert.rejects(s.runtime.startSession({ ...s.request(), udpPort: port }), /invalid_arguments/);
  }
  await assert.rejects(s.runtime.startSession({ ...s.request(), columns: 1000, rows: 31 }), /invalid_arguments/);
  assert.equal(s.ssh.calls.length, 0);
});

test('native UDP timeout gives actionable guidance and never exposes raw error text or key', async (t) => {
  const s = await setup(t);
  s.udp.start = async () => { throw Object.assign(new Error('transport dump ' + key), { code: 'connect_timeout' }); };
  const result = await s.runtime.startSession(s.request());
  assert.equal(result.status, 'FAILED'); assert.equal(result.errorCode, 'connect_timeout');
  assert.match(result.errorMessage!, /UDP.*port.*firewall/);
  assert.equal(JSON.stringify(result).includes(key), false);
  assert.equal([...s.saved.values()].join('').includes(key), false);
});

test('the approved server UTF8 locale is quoted exactly once; unknown locale never bootstraps', async (t) => {
  const s = await setup(t);
  const result = await s.runtime.startSession({ ...s.request(), serverLocale: 'en_US.UTF-8' });
  assert.equal(result.status, 'RUNNING');
  assert.equal(s.ssh.calls[0].options.command, "mosh-server new -s -c 256 -l 'LANG=en_US.UTF-8' -l 'LC_ALL=en_US.UTF-8'");
  const tools = createMoshTools({ runtime: s.runtime, profiles: s.profiles, activityStore: new AgentToolActivityStore(), conversationId: 'chat' });
  const parts = await tools[0].execute({ server_locale: 'en_US.UTF-8; echo injected' });
  assert.equal(JSON.parse((parts[0] as { text: string }).text).error_code, 'invalid_arguments');
  assert.equal(s.ssh.calls.length, 1);
});

test('cold runtime reads the shared TaskStore as interrupted without storing a key or re-running SSH', async (t) => {
  const s = await setup(t);
  const started = await s.runtime.startSession(s.request());
  const restoredStore = await AgentTaskStore.create({ taskDir: '/private/tasks', appFilesDir: '/private', files: s.files });
  const restored = new MoshRuntime({ targetResolver: s.resolver, sshTransport: s.ssh, transport: s.udp, taskStore: restoredStore });
  const snapshot = restored.readSession(started.sessionId);
  assert.equal(snapshot.status, 'INTERRUPTED'); assert.equal(snapshot.connectionState, 'disconnected');
  assert.equal(snapshot.profileId, 'p'); assert.equal(s.ssh.calls.length, 1); assert.equal(s.udp.calls.length, 1);
  let events = 0;
  restored.subscribeSession(started.sessionId, event => { events++; assert.equal(event.snapshot.status, 'INTERRUPTED'); });
  assert.equal(events, 1); assert.equal((await restored.stopSession(started.sessionId)).status, 'INTERRUPTED');
});

test('UDP terminal bytes, roaming state and input share one session; queued abort and background close stop actual writes', async (t) => {
  const s = await setup(t);
  const started = await s.runtime.startSession(s.request());
  const encoded = bytes('中😀$ ');
  s.udp.packets = [{ ...moshPacket(), bytes: encoded.slice(0, 4) }, { ...moshPacket('', 'reconnecting'), bytes: encoded.slice(4), lastHeardMs: 5001 },
    { ...moshPacket(), lastHeardMs: 1 }];
  const events: Uint8Array[] = [];
  const states: string[] = [];
  let received: () => void = () => undefined;
  const ready = new Promise<void>(resolve => { received = resolve; });
  const unsubscribe = s.runtime.subscribeSession(started.sessionId, event => {
    events.push(...event.chunks.map(chunk => chunk.bytes)); states.push(event.snapshot.connectionState);
    if (event.snapshot.connectionState === 'running' && event.snapshot.reconnectLastHeardMs === 1) received();
  });
  t.after(unsubscribe);
  await ready;
  assert.deepEqual(events, [encoded.slice(0, 4), encoded.slice(4)]);
  assert.ok(states.includes('reconnecting')); assert.equal(s.runtime.readSession(started.sessionId).outputTail, '中😀$ ');
  const reads = s.udp.reads;
  s.runtime.readSession(started.sessionId); assert.equal(s.udp.reads, reads);
  await s.runtime.sendSessionBytes(started.sessionId, new Uint8Array([3, 9, 27, 91, 65]));
  assert.deepEqual(s.udp.writes[0], new Uint8Array([3, 9, 27, 91, 65]));
  await s.runtime.resizeSession(started.sessionId, 100, 30); assert.deepEqual(s.udp.sizes, [[100, 30]]);
  let releaseWrite: () => void = () => undefined;
  let writeEntered: () => void = () => undefined;
  const entered = new Promise<void>(resolve => { writeEntered = resolve; });
  s.udp.write = async (_handle, value) => { s.udp.writes.push(value); writeEntered(); await new Promise<void>(resolve => { releaseWrite = resolve; }); };
  const manual = s.runtime.sendSessionBytes(started.sessionId, bytes('manual'));
  await entered;
  const signal = new AbortController();
  const queued = s.runtime.execSession(started.sessionId, 'echo UNSENT', s.runtime.captureTarget('p'), signal.signal);
  const rejected = assert.rejects(queued, /cancelled/);
  signal.abort(); releaseWrite(); await manual; await rejected;
  assert.equal(s.udp.writes.some(value => new TextDecoder().decode(value).includes('UNSENT')), false);
  assert.equal(s.runtime.readSession(started.sessionId).status, 'RUNNING');
  let rejectWrite: () => void = () => undefined;
  let blockedEntered: () => void = () => undefined;
  const blockedReady = new Promise<void>(resolve => { blockedEntered = resolve; });
  s.udp.write = async () => { blockedEntered(); await new Promise<void>((_resolve, reject) => { rejectWrite = () => reject(Object.assign(new Error('cancelled'), { code: 'cancelled' })); }); };
  const close = s.udp.close.bind(s.udp);
  s.udp.close = async (handle, reason) => { rejectWrite(); return close(handle, reason); };
  const blocked = s.runtime.sendSessionBytes(started.sessionId, bytes('blocked'));
  const blockedRejection = assert.rejects(blocked, /cancelled/);
  await blockedReady;
  await s.runtime.interruptForBackground(); await blockedRejection;
  const interrupted = s.runtime.readSession(started.sessionId);
  assert.equal(interrupted.status, 'INTERRUPTED'); assert.equal(interrupted.outputTail, '中😀$ ');
  assert.deepEqual(s.udp.closes, ['disconnected']);
  assert.equal(s.ssh.calls.length, 1); assert.equal(s.udp.calls.length, 1);
  assert.equal(s.taskStore.read(started.sessionId)?.cancelCapability, false);
});

test('background interrupts the pending SSH bootstrap and never starts UDP when the handshake settles late', async (t) => {
  const s = await setup(t);
  let entered: () => void = () => undefined;
  const waiting = new Promise<void>(resolve => { entered = resolve; });
  let sawCancel = false;
  s.ssh.startExec = async (_id, connection, options, signal) => {
    s.ssh.calls.push({ connection, options }); entered();
    return new Promise((_resolve, reject) => {
      const abort = () => { sawCancel = true; reject(Object.assign(new Error('cancelled'), { code: 'cancelled' })); };
      signal?.addEventListener?.('abort', abort); if (signal?.aborted) abort();
    });
  };
  const starting = s.runtime.startSession(s.request());
  await waiting;
  await s.runtime.interruptForBackground();
  const stopped = await starting;
  assert.equal(sawCancel, true); assert.equal(stopped.status, 'INTERRUPTED');
  assert.equal(s.udp.calls.length, 0); assert.equal(s.taskStore.read(stopped.sessionId)?.cancelCapability, false);
  assert.equal(JSON.stringify(s.taskStore.list()).includes(key), false);
});
