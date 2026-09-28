import type { AbortSignalLike } from '@amber/deepread-domain';
import type { AgentTaskStore } from '../agent_task.ts';
import { agentTaskStatusRunning, makeAgentTaskOutputRef, makeAgentTaskRetryPolicy, makeAgentTaskSnapshot } from '../agent_task.ts';
import { newId } from '../ids.ts';
import type { SSHConnectionOptions, TerminalCommandRequest, TerminalJobSnapshot } from './models.ts';
import type { SSHTransportPort, TerminalLogPort } from './ports.ts';
import { TerminalController, checkAbort, relayAbort, terminalAwait, terminalDelay } from './control.ts';
import { TerminalError } from './profile_store.ts';
import { TerminalStream } from './stream.ts';
import { posixQuote } from './utf8.ts';

interface Job {
  id: string;
  request: TerminalCommandRequest;
  stream: TerminalStream;
  controller: TerminalController;
  starting: Promise<void>;
  interrupted: boolean;
}
export class TerminalJobRuntime {
  private jobs: Map<string, Job> = new Map();
  constructor(private readonly transport: SSHTransportPort, private readonly logs: TerminalLogPort,
    private readonly taskStore: AgentTaskStore) {}
  async start(request: TerminalCommandRequest, connection: SSHConnectionOptions,
    validate: () => void, signal?: AbortSignalLike): Promise<TerminalJobSnapshot> {
    checkAbort(signal);
    const id: string = newId();
    const controller: TerminalController = new TerminalController();
    let finishStarting: () => void = (): void => undefined;
    const starting: Promise<void> = new Promise((resolve): void => { finishStarting = resolve; });
    const stream: TerminalStream = new TerminalStream('', this.transport, this.logs,
      async (): Promise<void> => { await this.publish(job); });
    const job: Job = { id, request, stream, controller, starting, interrupted: false };
    this.jobs.set(id, job);
    const detach: () => void = relayAbort(signal, (): void => controller.abort());
    try {
      const path: string = await terminalAwait(this.logs.create(id), controller.signal);
      checkAbort(controller.signal);
      stream.logPath = path;
      await this.taskStore.register(makeAgentTaskSnapshot({ taskId: id, type: 'terminal', title: request.command,
        spec: { command: request.command, cwd: request.cwd, profileId: request.target.profileId }, runtime: 'remote_ssh',
        status: 'QUEUED', createdAtMs: Date.now(), cancelCapability: true,
        retryPolicy: makeAgentTaskRetryPolicy({ retryable: false }),
        sourceToolName: request.sourceToolName, sourceConversationId: request.sourceConversationId,
        outputPath: path, outputRef: makeAgentTaskOutputRef({ type: 'terminal_log', path, exists: true }) }),
      async (): Promise<boolean> => {
        const stopped: TerminalJobSnapshot = await this.stop(id);
        return stopped.status === 'CANCELLED';
      });
      await this.begin(job, connection, validate);
      stream.beginPolling();
      return this.read(id);
    } catch (error) {
      await stream.startFailed(error as Error, job.interrupted);
      return this.read(id);
    } finally { detach(); finishStarting(); }
  }
  private async begin(job: Job, connection: SSHConnectionOptions, validate: () => void): Promise<void> {
    try {
      checkAbort(job.controller.signal); validate();
      const command: string = job.request.cwd === null ? job.request.command :
        'cd -- ' + posixQuote(job.request.cwd) + ' && ' + job.request.command;
      const handle = await this.transport.startExec(newId(), connection,
        { command, timeoutMs: job.request.timeoutMs }, job.controller.signal);
      await job.stream.attach(handle);
      if (job.controller.signal.aborted) await job.stream.close(job.interrupted ? 'disconnected' : 'cancelled');
    } catch (error) { await job.stream.startFailed(error as Error, job.interrupted); }
  }
  private async publish(job: Job): Promise<void> {
    const snapshot: TerminalJobSnapshot = this.snapshot(job);
    await this.taskStore.update(job.id, { status: snapshot.status, cancelCapability: job.stream.active(),
      outputOffset: job.stream.logBytes, outputRef: makeAgentTaskOutputRef({ type: 'terminal_log',
        path: snapshot.outputLogPath, exists: true, tailOffset: job.stream.logBytes }),
      summary: JSON.stringify({ exitCode: snapshot.exitCode, stdoutTail: snapshot.stdoutTail,
        stderrTail: snapshot.stderrTail, outputTail: snapshot.outputTail }),
      lastHeartbeatMs: Date.now(), error: snapshot.errorMessage, lastErrorCode: snapshot.errorCode });
  }
  private snapshot(job: Job): TerminalJobSnapshot {
    const s: TerminalStream = job.stream;
    return { jobId: job.id, profileId: job.request.target.profileId, command: job.request.command, cwd: job.request.cwd,
      status: s.status, exitCode: s.exitCode, stdoutTail: s.stdoutTail, stderrTail: s.stderrTail, outputTail: s.outputTail,
      outputLogPath: s.logPath, errorCode: s.errorCode, errorMessage: s.errorMessage };
  }
  read(id: string): TerminalJobSnapshot {
    const job: Job | undefined = this.jobs.get(id);
    if (job !== undefined) return this.snapshot(job);
    const task = this.taskStore.read(id);
    if (task === null || task.type !== 'terminal' || task.runtime !== 'remote_ssh') throw new TerminalError('job_missing');
    interface StoredTail { exitCode: number | null; stdoutTail: string; stderrTail: string; outputTail: string; }
    let tails: StoredTail = { exitCode: null, stdoutTail: '', stderrTail: '', outputTail: '' };
    if (task.summary !== null) { try { tails = JSON.parse(task.summary) as StoredTail; } catch { /* legacy task summary */ } }
    return { jobId: id, profileId: String(task.spec?.['profileId'] ?? ''), command: String(task.spec?.['command'] ?? ''),
      cwd: task.spec?.['cwd'] === null || task.spec?.['cwd'] === undefined ? null : String(task.spec['cwd']),
      status: agentTaskStatusRunning(task.status) ? 'INTERRUPTED' : task.status,
      exitCode: task.status === 'COMPLETED' || task.status === 'FAILED' ? tails.exitCode ?? null : null,
      stdoutTail: tails.stdoutTail ?? '', stderrTail: tails.stderrTail ?? '', outputTail: tails.outputTail ?? '',
      outputLogPath: task.outputRef?.path ?? task.outputPath ?? '', errorCode: task.lastErrorCode, errorMessage: task.error };
  }
  async wait(id: string, timeoutMs: number, signal?: AbortSignalLike): Promise<TerminalJobSnapshot> {
    if (!Number.isFinite(timeoutMs) || timeoutMs < 0 || timeoutMs > 60000) throw new TerminalError('invalid_arguments');
    const deadline: number = Date.now() + timeoutMs;
    checkAbort(signal);
    let snapshot: TerminalJobSnapshot = this.read(id);
    while (agentTaskStatusRunning(snapshot.status) && Date.now() < deadline) {
      await terminalDelay(Math.min(100, deadline - Date.now()), signal);
      snapshot = this.read(id);
    }
    return snapshot;
  }
  async stop(id: string, interrupted: boolean = false): Promise<TerminalJobSnapshot> {
    const job: Job | undefined = this.jobs.get(id);
    if (job === undefined) return this.read(id);
    if (!job.stream.active()) return this.snapshot(job);
    job.interrupted = interrupted;
    job.controller.abort();
    const closing: Promise<void> = job.stream.close(interrupted ? 'disconnected' : 'cancelled');
    await job.starting;
    await closing;
    return this.snapshot(job);
  }
  async interrupt(): Promise<void> {
    const stops: Promise<TerminalJobSnapshot>[] = [];
    this.jobs.forEach((job: Job): void => { if (job.stream.active()) stops.push(this.stop(job.id, true)); });
    await Promise.all(stops);
  }
}
