import type { AbortSignalLike } from '@amber/deepread-domain';
import type { AgentTaskStatus, AgentTaskStore } from '../agent_task.ts';
import { agentTaskStatusRunning, makeAgentTaskSnapshot } from '../agent_task.ts';
import { newId } from '../ids.ts';
import { terminalTail } from '../terminal/utf8.ts';
import { PythonController, PythonError, pythonUTF8Size, relayPythonAbort } from './control.ts';
import type { PythonExecuteRequest, PythonNativeResult, PythonSnapshot } from './models.ts';
import { PYTHON_MAX_SOURCE_BYTES, PYTHON_MAX_STDIN_BYTES, PYTHON_MAX_TIMEOUT_MS } from './models.ts';
import type { PythonTransportPort } from './ports.ts';

export interface PythonRuntimeDeps { transport: PythonTransportPort; taskStore: AgentTaskStore; }
interface PythonRun {
  snapshot: PythonSnapshot;
  controller: PythonController;
  interrupted: boolean;
  done: Promise<void>;
  finish: () => void;
}
const nativeStatus = (result: PythonNativeResult, interrupted: boolean): AgentTaskStatus => {
  if (result.status === 'completed') return 'COMPLETED';
  if (result.status === 'failed') return 'FAILED';
  if (result.status === 'timed_out') return 'TIMED_OUT';
  return interrupted ? 'INTERRUPTED' : 'CANCELLED';
};

export class PythonRuntime {
  private readonly active: Map<string, PythonRun> = new Map();
  constructor(private readonly deps: PythonRuntimeDeps) {}
  version(): string { return this.deps.transport.version(); }
  async execute(request: PythonExecuteRequest, signal?: AbortSignalLike): Promise<PythonSnapshot> {
    if (!request.source.trim() || request.source.indexOf('\0') >= 0 ||
      pythonUTF8Size(request.source) > PYTHON_MAX_SOURCE_BYTES || pythonUTF8Size(request.stdin) > PYTHON_MAX_STDIN_BYTES ||
      !Number.isInteger(request.timeoutMs) || request.timeoutMs < 1 || request.timeoutMs > PYTHON_MAX_TIMEOUT_MS) {
      throw new PythonError('invalid_arguments');
    }
    const captured: PythonExecuteRequest = { source: request.source, stdin: request.stdin, timeoutMs: request.timeoutMs,
      sourceToolName: request.sourceToolName, sourceConversationId: request.sourceConversationId };
    const runId: string = newId();
    let finish: () => void = (): void => undefined;
    const done: Promise<void> = new Promise((resolve): void => { finish = resolve; });
    const run: PythonRun = { snapshot: { runId, runtime: 'embedded_python', status: 'QUEUED',
      exitCode: null, stdout: '', stderr: '', errorCode: null }, controller: new PythonController(), interrupted: false, done, finish };
    this.active.set(runId, run);
    const detach: () => void = relayPythonAbort(signal, run.controller);
    try {
      await this.deps.taskStore.register(makeAgentTaskSnapshot({ taskId: runId, type: 'python', title: 'Python',
        runtime: 'embedded_python', status: 'QUEUED', createdAtMs: Date.now(), cancelCapability: true,
        spec: { timeoutMs: captured.timeoutMs }, sourceToolName: captured.sourceToolName,
        sourceConversationId: captured.sourceConversationId }), async (): Promise<boolean> => {
        await this.cancel(runId);
        return run.snapshot.status === 'CANCELLED';
      });
      let result: PythonNativeResult;
      try {
        if (run.controller.signal.aborted) throw new PythonError('cancelled');
        run.snapshot.status = 'RUNNING';
        await this.deps.taskStore.update(runId, { status: 'RUNNING', lastHeartbeatMs: Date.now() });
        if (run.controller.signal.aborted) throw new PythonError('cancelled');
        result = await this.deps.transport.execute(runId, { source: captured.source, stdin: captured.stdin,
          timeoutMs: captured.timeoutMs }, run.controller.signal);
      } catch (error) {
        const cancelled: boolean = run.controller.signal.aborted;
        result = { status: cancelled ? 'cancelled' : 'failed', exitCode: null, stdout: '', stderr: '',
          errorCode: cancelled ? 'cancelled' : 'python_transport_error' };
      }
      const status: AgentTaskStatus = nativeStatus(result, run.interrupted);
      const errorCode: string | null = status === 'INTERRUPTED' ? 'background_interrupted' : result.errorCode;
      run.snapshot = { runId, runtime: 'embedded_python', status, exitCode: result.exitCode,
        stdout: result.stdout, stderr: result.stderr, errorCode };
      await this.deps.taskStore.update(runId, { status, cancelCapability: false, lastHeartbeatMs: Date.now(),
        summary: JSON.stringify({ exitCode: result.exitCode, stdout: terminalTail(result.stdout, 1600),
          stderr: terminalTail(result.stderr, 1600) }), lastErrorCode: errorCode,
        error: errorCode === null ? null : 'Python: ' + errorCode });
      return run.snapshot;
    } finally {
      detach(); this.active.delete(runId); run.finish();
    }
  }
  async cancel(runId: string): Promise<void> {
    const run: PythonRun | undefined = this.active.get(runId);
    if (run === undefined) return;
    if (agentTaskStatusRunning(run.snapshot.status)) run.controller.abort();
    await run.done;
  }
  async interruptForBackground(): Promise<void> {
    const pending: PythonRun[] = [];
    this.active.forEach((run: PythonRun): void => {
      if (agentTaskStatusRunning(run.snapshot.status)) { run.interrupted = true; run.controller.abort(); }
      pending.push(run);
    });
    await Promise.all(pending.map((run: PythonRun): Promise<void> => run.done));
  }
}
