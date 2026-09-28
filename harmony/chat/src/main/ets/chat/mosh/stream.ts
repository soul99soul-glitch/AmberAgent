import type { AgentTaskStatus } from '../agent_task.ts';
import { agentTaskStatusRunning } from '../agent_task.ts';
import type { SSHOutputChunk } from '../terminal/models.ts';
import { TerminalError } from '../terminal/profile_store.ts';
import { TerminalUTF8Decoder, terminalTail } from '../terminal/utf8.ts';
import type { MoshCloseReason, MoshConnectionState, MoshHandle, MoshPacket } from './models.ts';
import type { MoshTransportPort } from './ports.ts';
import { moshFailure } from './bootstrap.ts';

export class MoshStream {
  status: AgentTaskStatus = 'QUEUED';
  connectionState: MoshConnectionState = 'connecting';
  reconnectLastHeardMs: number | null = null;
  outputTail: string = '';
  errorCode: string | null = null;
  errorMessage: string | null = null;
  private handle: MoshHandle | null = null;
  private pending: Promise<void> = Promise.resolve();
  private closing: Promise<void> | null = null;
  private closeReason: MoshCloseReason | null = null;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private decoder: TerminalUTF8Decoder = new TerminalUTF8Decoder();
  constructor(private readonly transport: MoshTransportPort,
    private readonly changed: (chunks: SSHOutputChunk[]) => Promise<void>) {}
  active(): boolean { return agentTaskStatusRunning(this.status); }
  private serial<T>(action: () => Promise<T>): Promise<T> {
    const run: Promise<T> = this.pending.then(action);
    this.pending = run.then((): void => undefined, (): void => undefined);
    return run;
  }
  async attach(handle: MoshHandle): Promise<void> {
    if (this.closeReason !== null) { await this.transport.close(handle, this.closeReason); return; }
    this.handle = handle;
    this.status = 'RUNNING'; this.connectionState = 'running';
    await this.changed([]);
  }
  beginPolling(): void {
    if (!this.active() || this.handle === null || this.timer !== null) return;
    this.timer = setTimeout((): void => {
      this.timer = null;
      void this.serial(async (): Promise<void> => {
        if (!this.active() || this.handle === null) return;
        try { await this.consume(await this.transport.read(this.handle, 65536)); }
        catch (error) { if (this.closing === null) await this.startFailed(error as Error); }
        this.beginPolling();
      }).catch((): void => { /* failure is represented by the session snapshot */ });
    }, 100);
  }
  private async consume(packet: MoshPacket, released: boolean = false): Promise<void> {
    if (!this.active()) return;
    const changed: boolean = packet.bytes.length > 0 || packet.state !== this.connectionState || packet.lastHeardMs !== this.reconnectLastHeardMs;
    this.outputTail = terminalTail(this.outputTail + this.decoder.decode(packet.bytes), 32768);
    this.connectionState = packet.state; this.reconnectLastHeardMs = packet.lastHeardMs;
    if (packet.state !== 'running' && packet.state !== 'reconnecting') {
      this.outputTail = terminalTail(this.outputTail + this.decoder.decode(new Uint8Array(0), true), 32768);
      this.status = packet.state === 'closed' ? 'COMPLETED' : packet.state === 'cancelled' ? 'CANCELLED' :
        packet.state === 'disconnected' ? 'INTERRUPTED' : 'FAILED';
      if (packet.errorCode !== null) {
        const failure = moshFailure(new TerminalError(packet.errorCode));
        this.errorCode = failure.code; this.errorMessage = failure.message;
      }
      if (!released && this.handle !== null) {
        const handle: MoshHandle = this.handle; this.handle = null;
        await this.transport.close(handle, 'release');
      }
    }
    if (changed || !this.active()) await this.changed(packet.bytes.length === 0 ? [] : [{ bytes: packet.bytes, isStderr: false }]);
  }
  async startFailed(error: Error, interrupted: boolean = false): Promise<void> {
    if (this.closing !== null) { await this.closing; return; }
    if (!this.active()) return;
    if (this.timer !== null) { clearTimeout(this.timer); this.timer = null; }
    if (this.handle !== null) {
      const handle: MoshHandle = this.handle; this.handle = null;
      try { await this.transport.close(handle, interrupted ? 'disconnected' : 'cancelled'); } catch { /* retain original failure */ }
    }
    const failure = moshFailure(error);
    this.status = interrupted || failure.code === 'disconnected' ? 'INTERRUPTED' : failure.code === 'cancelled' ? 'CANCELLED' : 'FAILED';
    this.connectionState = this.status === 'INTERRUPTED' ? 'disconnected' : this.status === 'CANCELLED' ? 'cancelled' : 'failed';
    this.errorCode = interrupted ? 'background_interrupted' : failure.code;
    this.errorMessage = interrupted ? moshFailure(new TerminalError('background_interrupted')).message : failure.message;
    await this.changed([]);
  }
  close(reason: MoshCloseReason): Promise<void> {
    if (this.closing !== null) return this.closing;
    if (!this.active()) return Promise.resolve();
    if (this.timer !== null) { clearTimeout(this.timer); this.timer = null; }
    this.closeReason = reason;
    const handle: MoshHandle | null = this.handle; this.handle = null;
    // Close reaches native before the serial queue, so it can wake an active write.
    const result: Promise<MoshPacket | null> = handle === null ? Promise.resolve(null) : this.transport.close(handle, reason);
    const observed = result.then((packet): { packet: MoshPacket | null; error: Error | null } => ({ packet, error: null }),
      (error: Error): { packet: MoshPacket | null; error: Error | null } => ({ packet: null, error }));
    this.closing = this.serial(async (): Promise<void> => {
      const closed = await observed;
      const packet: MoshPacket = closed.packet ?? { state: 'closed', bytes: new Uint8Array(0), errorCode: null, lastHeardMs: this.reconnectLastHeardMs };
      await this.consume({ state: reason === 'disconnected' ? 'disconnected' : reason === 'cancelled' ? 'cancelled' : 'closed',
        bytes: packet.bytes, lastHeardMs: packet.lastHeardMs, errorCode: closed.error === null ? packet.errorCode : moshFailure(closed.error).code }, true);
      if (reason === 'disconnected') {
        this.errorCode = 'background_interrupted'; this.errorMessage = moshFailure(new TerminalError(this.errorCode)).message;
        await this.changed([]);
      }
    });
    return this.closing;
  }
  write(bytes: Uint8Array, validate?: () => void): Promise<void> {
    return this.serial(async (): Promise<void> => {
      if (this.handle === null || !this.active()) throw new TerminalError('session_closed');
      if (validate !== undefined) validate();
      try { await this.transport.write(this.handle, bytes); }
      catch (error) { if (this.closing === null) await this.startFailed(error as Error); throw error; }
    });
  }
  resize(columns: number, rows: number): Promise<void> {
    return this.serial(async (): Promise<void> => {
      if (this.handle === null || !this.active()) throw new TerminalError('session_closed');
      try { await this.transport.resize(this.handle, columns, rows); }
      catch (error) { if (this.closing === null) await this.startFailed(error as Error); throw error; }
    });
  }
}
