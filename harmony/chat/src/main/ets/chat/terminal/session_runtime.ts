import type { AbortSignalLike } from '@amber/deepread-domain';
import { newId } from '../ids.ts';
import type { SSHConnectionOptions, SSHTargetSnapshot, TerminalSessionEvent,
  TerminalSessionRequest, TerminalSessionSnapshot } from './models.ts';
import type { SSHTransportPort, TerminalLogPort } from './ports.ts';
import { TerminalController, checkAbort, relayAbort, terminalAwait } from './control.ts';
import { TerminalError } from './profile_store.ts';
import { TerminalStream } from './stream.ts';
import { posixQuote, terminalUTF8Encode } from './utf8.ts';

interface Session {
  id: string;
  request: TerminalSessionRequest;
  target: SSHTargetSnapshot;
  stream: TerminalStream;
  controller: TerminalController;
  starting: Promise<void>;
  interrupted: boolean;
  listeners: Array<(event: TerminalSessionEvent) => void>;
}
export class TerminalSessionRuntime {
  private sessions: Map<string, Session> = new Map();
  constructor(private readonly transport: SSHTransportPort, private readonly logs: TerminalLogPort) {}
  async start(request: TerminalSessionRequest, connection: SSHConnectionOptions,
    validate: () => void, signal?: AbortSignalLike): Promise<TerminalSessionSnapshot> {
    checkAbort(signal);
    const id: string = newId();
    const controller: TerminalController = new TerminalController();
    let finishStarting: () => void = (): void => undefined;
    const starting: Promise<void> = new Promise((resolve): void => { finishStarting = resolve; });
    const stream: TerminalStream = new TerminalStream('', this.transport, this.logs,
      async (chunks): Promise<void> => {
        const event: TerminalSessionEvent = { snapshot: this.snapshot(session), chunks };
        for (const listener of session.listeners.slice()) {
          try { listener(event); } catch { /* independent terminal view observers */ }
        }
      });
    const session: Session = { id, request, target: { profileId: request.target.profileId,
      digest: request.target.digest, usesDefault: request.target.usesDefault }, stream, controller,
      starting, interrupted: false, listeners: [] };
    this.sessions.set(id, session);
    const detach: () => void = relayAbort(signal, (): void => controller.abort());
    try {
      const path: string = await terminalAwait(this.logs.create(id), controller.signal);
      checkAbort(controller.signal);
      stream.logPath = path;
      await this.begin(session, connection, validate);
      stream.beginPolling();
      return this.snapshot(session);
    } catch (error) {
      await stream.startFailed(error as Error, session.interrupted);
      return this.snapshot(session);
    } finally { detach(); finishStarting(); }
  }
  private async begin(session: Session, connection: SSHConnectionOptions, validate: () => void): Promise<void> {
    try {
      checkAbort(session.controller.signal); validate();
      const handle = await this.transport.startPty(newId(), connection,
        { term: 'xterm-256color', columns: session.request.columns, rows: session.request.rows }, session.controller.signal);
      await session.stream.attach(handle);
      if (!session.controller.signal.aborted && session.request.cwd !== null) {
        await session.stream.write(terminalUTF8Encode('cd -- ' + posixQuote(session.request.cwd) + '\n'));
      }
      if (session.controller.signal.aborted) await session.stream.close(session.interrupted ? 'disconnected' : 'cancelled');
    } catch (error) { await session.stream.startFailed(error as Error, session.interrupted); }
  }
  private get(id: string): Session {
    const session: Session | undefined = this.sessions.get(id);
    if (session === undefined) throw new TerminalError('session_missing');
    return session;
  }
  private snapshot(session: Session): TerminalSessionSnapshot {
    const s: TerminalStream = session.stream;
    return { sessionId: session.id, profileId: session.target.profileId, status: s.status, exitCode: s.exitCode,
      outputTail: s.outputTail, outputLogPath: s.logPath, columns: session.request.columns, rows: session.request.rows,
      errorCode: s.errorCode, errorMessage: s.errorMessage };
  }
  read(id: string): TerminalSessionSnapshot { return this.snapshot(this.get(id)); }
  subscribe(id: string, listener: (event: TerminalSessionEvent) => void): () => void {
    const session: Session = this.get(id);
    session.listeners.push(listener);
    listener({ snapshot: this.snapshot(session), chunks: [] });
    return (): void => { session.listeners = session.listeners.filter((v): boolean => v !== listener); };
  }
  write(id: string, bytes: Uint8Array): Promise<void> { return this.get(id).stream.write(bytes.slice()); }
  async exec(id: string, command: string, target: SSHTargetSnapshot,
    validate: () => void, signal?: AbortSignalLike): Promise<TerminalSessionSnapshot> {
    const session: Session = this.get(id);
    await session.stream.write(terminalUTF8Encode(command + '\n'), (): void => {
      checkAbort(signal);
      validate();
      if (target.profileId !== session.target.profileId || target.digest !== session.target.digest) {
        throw new TerminalError('target_changed');
      }
      if (!command.trim() || command.indexOf('\0') >= 0) throw new TerminalError('invalid_arguments');
    });
    return this.snapshot(session);
  }
  async resize(id: string, columns: number, rows: number): Promise<void> {
    if (!Number.isInteger(columns) || !Number.isInteger(rows) || columns < 1 || rows < 1) {
      throw new TerminalError('invalid_arguments');
    }
    const session: Session = this.get(id);
    await session.stream.resize(columns, rows);
    session.request.columns = columns; session.request.rows = rows;
  }
  async stop(id: string, interrupted: boolean = false): Promise<TerminalSessionSnapshot> {
    const session: Session = this.get(id);
    if (!session.stream.active()) return this.snapshot(session);
    session.interrupted = interrupted;
    session.controller.abort();
    const closing: Promise<void> = session.stream.close(interrupted ? 'disconnected' : 'cancelled');
    await session.starting;
    await closing;
    return this.snapshot(session);
  }
  async interrupt(): Promise<void> {
    const stops: Promise<TerminalSessionSnapshot>[] = [];
    this.sessions.forEach((s: Session): void => { if (s.stream.active()) stops.push(this.stop(s.id, true)); });
    await Promise.all(stops);
  }
}
