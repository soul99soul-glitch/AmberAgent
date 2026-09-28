import type { AgentTaskStatus } from '../agent_task.ts';
import { agentTaskStatusRunning } from '../agent_task.ts';
import type { SSHCloseReason, SSHHandle, SSHOutputChunk, SSHReadPacket } from './models.ts';
import type { SSHTransportPort, TerminalLogPort } from './ports.ts';
import { TerminalError } from './profile_store.ts';
import { terminalFailure } from './control.ts';
import { TerminalUTF8Decoder, terminalTail, terminalUTF8Encode } from './utf8.ts';

export const packetStatus = (packet: SSHReadPacket): AgentTaskStatus => {
  if (packet.state === 'running') return 'RUNNING';
  if (packet.state === 'exited') return packet.exitCode === 0 ? 'COMPLETED' : 'FAILED';
  if (packet.state === 'cancelled') return 'CANCELLED';
  if (packet.state === 'timed_out') return 'TIMED_OUT';
  if (packet.state === 'disconnected') return 'INTERRUPTED';
  return 'FAILED';
};

// One owner per handle: reads, writes and output/status settlement share this serial queue.
// Close wakes native immediately, so a backpressured write cannot prevent cancellation.
// This is transport ownership only. Scheduling/recovery/cancellation UI stays in AgentTaskStore.
export class TerminalStream {
  status: AgentTaskStatus = 'QUEUED';
  exitCode: number | null = null;
  stdoutTail: string = '';
  stderrTail: string = '';
  outputTail: string = '';
  errorCode: string | null = null;
  errorMessage: string | null = null;
  logBytes: number = 0;
  private handle: SSHHandle | null = null;
  private pending: Promise<void> = Promise.resolve();
  private closeCompletion: Promise<void> | null = null;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private stdout: TerminalUTF8Decoder = new TerminalUTF8Decoder();
  private stderr: TerminalUTF8Decoder = new TerminalUTF8Decoder();
  constructor(public logPath: string, private readonly transport: SSHTransportPort,
    private readonly logs: TerminalLogPort,
    private readonly changed: (chunks: SSHOutputChunk[]) => Promise<void>) {}
  active(): boolean { return agentTaskStatusRunning(this.status); }
  serial<T>(operation: () => Promise<T>): Promise<T> {
    const run: Promise<T> = this.pending.then(operation);
    this.pending = run.then((): void => undefined, (): void => undefined);
    return run;
  }
  async attach(handle: SSHHandle): Promise<void> {
    this.handle = handle;
    this.status = 'RUNNING';
    await this.changed([]);
  }
  beginPolling(): void { this.schedule(); }
  private schedule(): void {
    if (!this.active() || this.handle === null) return;
    this.timer = setTimeout((): void => {
      this.timer = null;
      void this.serial(async (): Promise<void> => {
        if (!this.active() || this.handle === null) return;
        try { await this.consume(await this.transport.read(this.handle, 65536)); }
        catch (error) { await this.fail(error as Error); }
        this.schedule();
      }).catch((): void => { /* failure is already represented in stream state */ });
    }, 100);
  }
  private async append(text: string, isStderr: boolean): Promise<void> {
    if (!text) return;
    const logText: string = isStderr ? '[stderr] ' + text : text;
    await this.logs.append(this.logPath, logText);
    this.logBytes += terminalUTF8Encode(logText).length;
    if (isStderr) this.stderrTail = terminalTail(this.stderrTail + text, 16384);
    else this.stdoutTail = terminalTail(this.stdoutTail + text, 16384);
    this.outputTail = terminalTail(this.outputTail + text, 32768);
  }
  private async consume(packet: SSHReadPacket, released: boolean = false): Promise<void> {
    if (!this.active()) return;
    for (const chunk of packet.chunks) {
      await this.append((chunk.isStderr ? this.stderr : this.stdout).decode(chunk.bytes), chunk.isStderr);
    }
    if (packet.state !== 'running') {
      await this.append(this.stdout.decode(new Uint8Array(0), true), false);
      await this.append(this.stderr.decode(new Uint8Array(0), true), true);
      this.status = packetStatus(packet);
      this.exitCode = packet.state === 'exited' ? packet.exitCode : null;
      this.errorCode = packet.errorCode;
      this.errorMessage = packet.errorMessage;
      if (!released && this.handle !== null) {
        const handle: SSHHandle = this.handle;
        this.handle = null;
        await this.transport.close(handle, 'release');
      }
    }
    await this.changed(packet.chunks);
  }
  async startFailed(error: Error, interrupted: boolean = false): Promise<void> {
    if (this.closeCompletion !== null) { await this.closeCompletion; return; }
    if (this.timer !== null) { clearTimeout(this.timer); this.timer = null; }
    if (this.handle !== null) {
      const handle: SSHHandle = this.handle;
      this.handle = null;
      try { await this.transport.close(handle, interrupted ? 'disconnected' : 'cancelled'); }
      catch { /* retain original startup error */ }
    }
    const failure = terminalFailure(error);
    this.status = interrupted || failure.code === 'disconnected' ? 'INTERRUPTED' : failure.code === 'cancelled' ? 'CANCELLED' : 'FAILED';
    this.errorCode = interrupted ? 'background_interrupted' : failure.code;
    this.errorMessage = interrupted ? 'Remote SSH interrupted in background.' : failure.message;
    await this.changed([]);
  }
  private async fail(error: Error): Promise<void> {
    if (this.timer !== null) { clearTimeout(this.timer); this.timer = null; }
    if (this.handle !== null) {
      const handle: SSHHandle = this.handle;
      this.handle = null;
      try { await this.transport.close(handle, 'cancelled'); } catch { /* report original transport/log failure */ }
    }
    const failure = terminalFailure(error);
    this.status = 'FAILED'; this.exitCode = null;
    this.errorCode = failure.code; this.errorMessage = failure.message;
    await this.changed([]);
  }
  close(reason: SSHCloseReason): Promise<void> {
    if (this.timer !== null) { clearTimeout(this.timer); this.timer = null; }
    if (this.closeCompletion !== null) return this.closeCompletion;
    if (!this.active() || this.handle === null) return Promise.resolve();
    const handle: SSHHandle = this.handle;
    this.handle = null;
    interface CloseResult { packet: SSHReadPacket | null; error: Error | null; }
    // Observe both settlements now; the native Promise can settle before the serial
    // output queue reaches it, without creating an unhandled rejection.
    let result: Promise<CloseResult>;
    try {
      result = this.transport.close(handle, reason).then(
        (packet: SSHReadPacket): CloseResult => ({ packet, error: null }),
        (error: Error): CloseResult => ({ packet: null, error }));
    } catch (error) { result = Promise.resolve({ packet: null, error: error as Error }); }
    this.closeCompletion = this.serial(async (): Promise<void> => {
      try {
        const closed: CloseResult = await result;
        if (closed.error !== null) throw closed.error;
        await this.consume(closed.packet!, true);
      }
      catch (error) { await this.fail(error as Error); }
    });
    return this.closeCompletion;
  }
  write(bytes: Uint8Array, validate?: () => void): Promise<void> {
    return this.serial(async (): Promise<void> => {
      if (this.handle === null || !this.active()) throw new TerminalError('session_closed');
      if (validate !== undefined) validate();
      try { await this.transport.write(this.handle, bytes); }
      catch (error) { if (this.closeCompletion === null) await this.fail(error as Error); throw error; }
    });
  }
  resize(columns: number, rows: number): Promise<void> {
    return this.serial(async (): Promise<void> => {
      if (this.handle === null || !this.active()) throw new TerminalError('session_closed');
      try { await this.transport.resize(this.handle, columns, rows); }
      catch (error) { if (this.closeCompletion === null) await this.fail(error as Error); throw error; }
    });
  }
}
