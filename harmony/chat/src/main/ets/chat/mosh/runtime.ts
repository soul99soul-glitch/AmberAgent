import type { AbortSignalLike } from '@amber/deepread-domain';
import type { AgentTaskStore } from '../agent_task.ts';
import { agentTaskStatusRunning, makeAgentTaskRetryPolicy, makeAgentTaskSnapshot } from '../agent_task.ts';
import { newId } from '../ids.ts';
import type { SSHCloseReason, SSHConnectionOptions, SSHHandle, SSHOutputChunk, SSHTargetSnapshot } from '../terminal/models.ts';
import type { SSHTransportPort } from '../terminal/ports.ts';
import type { SSHTargetResolver } from '../terminal/target_resolver.ts';
import { TerminalError } from '../terminal/profile_store.ts';
import { TerminalController, checkAbort, relayAbort, terminalAwait, terminalDelay } from '../terminal/control.ts';
import { TerminalUTF8Decoder, terminalUTF8Encode } from '../terminal/utf8.ts';
import type { MoshOptions, MoshSessionEvent, MoshSessionRequest, MoshSessionSnapshot } from './models.ts';
import type { MoshTransportPort } from './ports.ts';
import { moshServerCommand, parseMoshConnect } from './bootstrap.ts';
import { MoshStream } from './stream.ts';

export interface MoshRuntimeDeps {
  targetResolver: SSHTargetResolver;
  sshTransport: SSHTransportPort;
  transport: MoshTransportPort;
  taskStore?: AgentTaskStore;
}
interface Session {
  id: string;
  request: MoshSessionRequest;
  controller: TerminalController;
  stream: MoshStream;
  sshHandle: SSHHandle | null;
  sshClosing: Promise<void> | null;
  starting: Promise<void>;
  interrupted: boolean;
  listeners: Array<(event: MoshSessionEvent) => void>;
}
const validateSize = (columns: number, rows: number): void => {
  if (!Number.isInteger(columns) || !Number.isInteger(rows) || columns < 1 || rows < 1 || columns > 1000 || rows > 1000 || columns * rows > 30000) {
    throw new TerminalError('invalid_arguments');
  }
};
export class MoshRuntime {
  private sessions: Map<string, Session> = new Map();
  constructor(private readonly deps: MoshRuntimeDeps) {}
  captureTarget(profileId: string | null): SSHTargetSnapshot { return this.deps.targetResolver.captureTarget(profileId); }
  async startSession(request: MoshSessionRequest, signal?: AbortSignalLike): Promise<MoshSessionSnapshot> {
    checkAbort(signal); validateSize(request.columns, request.rows); moshServerCommand(request.udpPort, request.serverLocale);
    const captured: MoshSessionRequest = { target: { profileId: request.target.profileId, digest: request.target.digest,
      usesDefault: request.target.usesDefault }, columns: request.columns, rows: request.rows, udpPort: request.udpPort, serverLocale: request.serverLocale,
      sourceToolName: request.sourceToolName, sourceConversationId: request.sourceConversationId };
    const id: string = newId();
    let finish: () => void = (): void => undefined;
    const starting: Promise<void> = new Promise((resolve): void => { finish = resolve; });
    const stream: MoshStream = new MoshStream(this.deps.transport, async (chunks): Promise<void> => { await this.publish(session, chunks); });
    const session: Session = { id, request: captured, controller: new TerminalController(), stream,
      sshHandle: null, sshClosing: null, starting, interrupted: false, listeners: [] };
    this.sessions.set(id, session);
    const detach: () => void = relayAbort(signal, (): void => { void this.stopSession(id); });
    try {
      if (this.deps.taskStore !== undefined) {
        await this.deps.taskStore.register(makeAgentTaskSnapshot({ taskId: id, type: 'terminal', title: 'Remote Mosh session',
          runtime: 'remote_mosh', status: 'QUEUED', createdAtMs: Date.now(), cancelCapability: true,
          retryPolicy: makeAgentTaskRetryPolicy({ retryable: false }),
          spec: { profileId: captured.target.profileId, columns: captured.columns, rows: captured.rows, udpPort: captured.udpPort ?? null },
          sourceToolName: captured.sourceToolName, sourceConversationId: captured.sourceConversationId }),
        async (): Promise<boolean> => (await this.stopSession(id)).status === 'CANCELLED');
      }
      const connection: SSHConnectionOptions = await terminalAwait(
        this.deps.targetResolver.prepare(captured.target, session.controller.signal), session.controller.signal);
      const options: MoshOptions = await this.bootstrap(session, connection);
      checkAbort(session.controller.signal); this.deps.targetResolver.targetProfile(captured.target);
      const handle = await this.deps.transport.start(newId(), options, session.controller.signal);
      await stream.attach(handle);
      if (session.controller.signal.aborted) await stream.close(session.interrupted ? 'disconnected' : 'cancelled');
      stream.beginPolling();
    } catch (error) { await stream.startFailed(error as Error, session.interrupted); }
    finally { detach(); finish(); }
    return this.snapshot(session);
  }
  private async bootstrap(session: Session, connection: SSHConnectionOptions): Promise<MoshOptions> {
    const signal = session.controller.signal;
    checkAbort(signal); this.deps.targetResolver.targetProfile(session.request.target);
    const handle: SSHHandle = await this.deps.sshTransport.startExec(newId(), connection,
      { command: moshServerCommand(session.request.udpPort, session.request.serverLocale), timeoutMs: 10000 }, signal);
    session.sshHandle = handle;
    let terminal: boolean = false;
    let stdout: string = '';
    const decoder: TerminalUTF8Decoder = new TerminalUTF8Decoder();
    let size: number = 0;
    try {
      checkAbort(signal);
      if (!handle.peerAddress) throw new TerminalError('ssh_peer_address_missing');
      const deadline: number = Date.now() + 10000;
      while (!terminal) {
        checkAbort(signal); this.deps.targetResolver.targetProfile(session.request.target);
        if (Date.now() >= deadline) throw new TerminalError('connection_timeout');
        const packet = await this.deps.sshTransport.read(handle, 65536);
        for (const chunk of packet.chunks) {
          size += chunk.bytes.length;
          if (size > 65536) throw new TerminalError('mosh_bootstrap_too_large');
          if (!chunk.isStderr) stdout += decoder.decode(chunk.bytes);
        }
        terminal = packet.state !== 'running';
        if (terminal && (packet.state !== 'exited' || packet.exitCode !== 0)) {
          throw new TerminalError(packet.exitCode === 127 ? 'mosh_server_missing' : packet.errorCode ?? 'mosh_bootstrap_failed');
        }
        if (!terminal) await terminalDelay(100, signal);
      }
      stdout += decoder.decode(new Uint8Array(0), true);
      checkAbort(signal);
      const connected = parseMoshConnect(stdout, session.request.udpPort);
      return { peerAddress: handle.peerAddress, port: connected.port, sessionKey: connected.sessionKey,
        columns: session.request.columns, rows: session.request.rows, connectTimeoutMs: 10000 };
    } finally {
      stdout = '';
      await this.closeBootstrap(session, terminal ? 'release' : session.interrupted ? 'disconnected' : 'cancelled');
    }
  }
  private closeBootstrap(session: Session, reason: SSHCloseReason): Promise<void> {
    if (session.sshHandle === null) return session.sshClosing ?? Promise.resolve();
    const handle: SSHHandle = session.sshHandle; session.sshHandle = null;
    session.sshClosing = this.deps.sshTransport.close(handle, reason).then((): void => undefined);
    return session.sshClosing;
  }
  private get(id: string): Session {
    const session: Session | undefined = this.sessions.get(id);
    if (session === undefined) throw new TerminalError('session_missing');
    return session;
  }
  private snapshot(session: Session): MoshSessionSnapshot {
    const s: MoshStream = session.stream;
    return { runtime: 'remote_mosh', sessionId: session.id, profileId: session.request.target.profileId, status: s.status,
      connectionState: s.connectionState, reconnectLastHeardMs: s.reconnectLastHeardMs, outputTail: s.outputTail,
      columns: session.request.columns, rows: session.request.rows, errorCode: s.errorCode, errorMessage: s.errorMessage };
  }
  private async publish(session: Session, chunks: SSHOutputChunk[]): Promise<void> {
    const snapshot: MoshSessionSnapshot = this.snapshot(session);
    if (this.deps.taskStore !== undefined) {
      await this.deps.taskStore.update(session.id, { status: snapshot.status, cancelCapability: session.stream.active(),
        summary: JSON.stringify(snapshot), lastHeartbeatMs: Date.now(), error: snapshot.errorMessage, lastErrorCode: snapshot.errorCode });
    }
    for (const listener of session.listeners.slice()) {
      try { listener({ snapshot, chunks }); } catch { /* independent view observers */ }
    }
  }
  readSession(id: string): MoshSessionSnapshot {
    const session: Session | undefined = this.sessions.get(id);
    if (session !== undefined) return this.snapshot(session);
    const task = this.deps.taskStore?.read(id);
    if (task == null || task.type !== 'terminal' || task.runtime !== 'remote_mosh') throw new TerminalError('session_missing');
    let saved: MoshSessionSnapshot | null = null;
    if (task.summary !== null) { try { saved = JSON.parse(task.summary) as MoshSessionSnapshot; } catch { /* no snapshot before bootstrap */ } }
    const interrupted: boolean = agentTaskStatusRunning(task.status) || task.status === 'INTERRUPTED';
    return { runtime: 'remote_mosh', sessionId: id, profileId: String(task.spec?.['profileId'] ?? ''),
      status: interrupted ? 'INTERRUPTED' : task.status, connectionState: interrupted ? 'disconnected' : saved?.connectionState ?? 'closed',
      reconnectLastHeardMs: saved?.reconnectLastHeardMs ?? null, outputTail: saved?.outputTail ?? '',
      columns: Number(task.spec?.['columns'] ?? 80), rows: Number(task.spec?.['rows'] ?? 24),
      errorCode: interrupted ? 'recovery_interrupted' : task.lastErrorCode,
      errorMessage: interrupted ? 'Mosh session cannot resume after restart. Start a new session.' : task.error };
  }
  subscribeSession(id: string, listener: (event: MoshSessionEvent) => void): () => void {
    if (!this.sessions.has(id)) { listener({ snapshot: this.readSession(id), chunks: [] }); return (): void => undefined; }
    const session: Session = this.get(id); session.listeners.push(listener);
    listener({ snapshot: this.snapshot(session), chunks: [] });
    return (): void => { session.listeners = session.listeners.filter((v): boolean => v !== listener); };
  }
  sendSessionBytes(id: string, bytes: Uint8Array): Promise<void> {
    if (bytes.length > 65536) return Promise.reject(new TerminalError('invalid_arguments'));
    return this.get(id).stream.write(bytes.slice());
  }
  async execSession(id: string, command: string, target: SSHTargetSnapshot, signal?: AbortSignalLike): Promise<MoshSessionSnapshot> {
    const session: Session = this.get(id);
    const captured: SSHTargetSnapshot = { profileId: target.profileId, digest: target.digest, usesDefault: target.usesDefault };
    const encoded: Uint8Array = terminalUTF8Encode(command + '\n');
    if (!command.trim() || command.indexOf('\0') >= 0 || encoded.length > 65536) throw new TerminalError('invalid_arguments');
    await session.stream.write(encoded, (): void => {
      checkAbort(signal); this.deps.targetResolver.targetProfile(captured);
      if (captured.profileId !== session.request.target.profileId || captured.digest !== session.request.target.digest) throw new TerminalError('target_changed');
    });
    return this.snapshot(session);
  }
  async resizeSession(id: string, columns: number, rows: number): Promise<void> {
    validateSize(columns, rows);
    const session: Session = this.get(id);
    await session.stream.resize(columns, rows);
    session.request.columns = columns; session.request.rows = rows;
  }
  async stopSession(id: string, interrupted: boolean = false): Promise<MoshSessionSnapshot> {
    if (!this.sessions.has(id)) return this.readSession(id);
    const session: Session = this.get(id);
    if (!session.stream.active()) return this.snapshot(session);
    session.interrupted = interrupted; session.controller.abort();
    const sshClosing: Promise<void> = this.closeBootstrap(session, interrupted ? 'disconnected' : 'cancelled');
    const closing: Promise<void> = session.stream.close(interrupted ? 'disconnected' : 'cancelled');
    await Promise.all([sshClosing, closing, session.starting]);
    return this.snapshot(session);
  }
  async interruptForBackground(): Promise<void> {
    const stops: Promise<MoshSessionSnapshot>[] = [];
    this.sessions.forEach((s: Session): void => { if (s.stream.active()) stops.push(this.stopSession(s.id, true)); });
    await Promise.all(stops);
  }
}
