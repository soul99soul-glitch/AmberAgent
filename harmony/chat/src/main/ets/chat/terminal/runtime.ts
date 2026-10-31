import type { AbortSignalLike } from '@amber/deepread-domain';
import type { AgentTaskStore } from '../agent_task.ts';
import { agentTaskStatusRunning } from '../agent_task.ts';
import type { SSHCredentialStorePort, SSHTransportPort, TerminalLogPort } from './ports.ts';
import type {
  SSHConnectionOptions, SSHCredential, SSHProfile, SSHProfileDraft,
  SSHTargetSnapshot, SSHTrustProbe, TerminalCommandRequest, TerminalJobSnapshot, TerminalSessionEvent,
  TerminalSessionRequest, TerminalSessionSnapshot,
} from './models.ts';
import { SSHProfileStore, TerminalError } from './profile_store.ts';
import type { SSHKeyStore } from './key_store.ts';
import { SSHTrustFlow } from './trust_flow.ts';
import { buildRouteHopSummaries } from './route_resolver.ts';
import { SSHTargetResolver } from './target_resolver.ts';
import { TerminalController, checkAbort, relayAbort } from './control.ts';
import { TerminalJobRuntime } from './job_runtime.ts';
import { TerminalSessionRuntime } from './session_runtime.ts';

export interface TerminalRuntimeDeps {
  profiles: SSHProfileStore;
  keys?: SSHKeyStore;
  credentials: SSHCredentialStorePort;
  transport: SSHTransportPort;
  logs: TerminalLogPort;
  taskStore: AgentTaskStore;
}
interface PendingOperation { controller: TerminalController; done: Promise<void>; interrupted: boolean; }
export class TerminalRuntime {
  private readonly jobs: TerminalJobRuntime;
  private readonly sessions: TerminalSessionRuntime;
  private readonly targetResolver: SSHTargetResolver;
  private readonly trust: SSHTrustFlow;
  private pending: PendingOperation[] = [];
  constructor(private readonly deps: TerminalRuntimeDeps) {
    this.jobs = new TerminalJobRuntime(deps.transport, deps.logs, deps.taskStore);
    this.sessions = new TerminalSessionRuntime(deps.transport, deps.logs);
    this.targetResolver = new SSHTargetResolver({ profiles: deps.profiles, credentials: deps.credentials, keys: deps.keys ?? deps.profiles.keyStore() });
    this.trust = new SSHTrustFlow(deps.profiles, this.targetResolver, deps.transport);
  }
  captureTarget(profileId: string | null): SSHTargetSnapshot {
    return this.targetResolver.captureTarget(profileId);
  }
  private targetProfile(target: SSHTargetSnapshot): SSHProfile {
    return this.targetResolver.targetProfile(target);
  }
  private withPending<T>(signal: AbortSignalLike | undefined,
    action: (operation: PendingOperation) => Promise<T>): Promise<T> {
    const op: PendingOperation = { controller: new TerminalController(), done: Promise.resolve(), interrupted: false };
    this.pending.push(op);
    const detach: () => void = relayAbort(signal, (): void => op.controller.abort());
    const run: Promise<T> = action(op);
    op.done = run.then((): void => undefined, (): void => undefined);
    return run.finally((): void => {
      detach(); this.pending = this.pending.filter((v: PendingOperation): boolean => v !== op);
    });
  }
  probe(draft: SSHProfileDraft, signal?: AbortSignalLike): Promise<SSHTrustProbe> {
    return this.withPending(signal, (op: PendingOperation): Promise<SSHTrustProbe> =>
      this.trust.probe(draft, op.controller.signal));
  }
  verifyAndSave(draft: SSHProfileDraft, probe: SSHTrustProbe, credential: SSHCredential | null,
    acceptFingerprint: boolean, signal?: AbortSignalLike): Promise<SSHProfile> {
    return this.withPending(signal, (op: PendingOperation): Promise<SSHProfile> =>
      this.trust.verifyAndSave(draft, probe, credential, acceptFingerprint, op.controller.signal));
  }
  private async prepare(target: SSHTargetSnapshot, signal: AbortSignalLike): Promise<SSHConnectionOptions> {
    return this.targetResolver.prepare(target, signal);
  }
  async startJob(request: TerminalCommandRequest, signal?: AbortSignalLike): Promise<TerminalJobSnapshot> {
    const captured: TerminalCommandRequest = { target: { profileId: request.target.profileId, digest: request.target.digest,
      usesDefault: request.target.usesDefault }, command: request.command, cwd: request.cwd, timeoutMs: request.timeoutMs,
      sourceToolName: request.sourceToolName, sourceConversationId: request.sourceConversationId };
    if (!captured.command.trim() || captured.command.indexOf('\0') >= 0 ||
      (captured.cwd !== null && captured.cwd.indexOf('\0') >= 0) || !Number.isFinite(captured.timeoutMs) || captured.timeoutMs < 1) {
      throw new TerminalError('invalid_arguments');
    }
    return this.withPending(signal, async (op: PendingOperation): Promise<TerminalJobSnapshot> => {
      const connection: SSHConnectionOptions = await this.prepare(captured.target, op.controller.signal);
      checkAbort(op.controller.signal); this.targetProfile(captured.target);
      return this.jobs.start(captured, connection, (): void => { this.targetProfile(captured.target); }, op.controller.signal, buildRouteHopSummaries(this.targetResolver.targetRoute(captured.target)));
    });
  }
  async execute(request: TerminalCommandRequest, signal?: AbortSignalLike): Promise<TerminalJobSnapshot> {
    const job: TerminalJobSnapshot = await this.startJob(request, signal);
    if (!agentTaskStatusRunning(job.status)) return job;
    const stop: () => void = (): void => { void this.jobs.stop(job.jobId); };
    const detach: () => void = relayAbort(signal, stop);
    try { return await this.jobs.wait(job.jobId, 5000, signal); }
    catch (error) { if (signal?.aborted) await this.jobs.stop(job.jobId); throw error; }
    finally { detach(); }
  }
  readJob(id: string): TerminalJobSnapshot { return this.jobs.read(id); }
  waitJob(id: string, timeoutMs: number, signal?: AbortSignalLike): Promise<TerminalJobSnapshot> { return this.jobs.wait(id, timeoutMs, signal); }
  stopJob(id: string): Promise<TerminalJobSnapshot> { return this.jobs.stop(id); }
  async startSession(request: TerminalSessionRequest, signal?: AbortSignalLike): Promise<TerminalSessionSnapshot> {
    if (!Number.isInteger(request.columns) || request.columns < 1 || !Number.isInteger(request.rows) || request.rows < 1 ||
      (request.cwd !== null && request.cwd.indexOf('\0') >= 0)) throw new TerminalError('invalid_arguments');
    const captured: TerminalSessionRequest = { target: { profileId: request.target.profileId, digest: request.target.digest,
      usesDefault: request.target.usesDefault }, cwd: request.cwd, columns: request.columns, rows: request.rows,
      sourceToolName: request.sourceToolName, sourceConversationId: request.sourceConversationId };
    return this.withPending(signal, async (op: PendingOperation): Promise<TerminalSessionSnapshot> => {
      const connection: SSHConnectionOptions = await this.prepare(captured.target, op.controller.signal);
      checkAbort(op.controller.signal); this.targetProfile(captured.target);
      return this.sessions.start(captured, connection, (): void => { this.targetProfile(captured.target); }, op.controller.signal, buildRouteHopSummaries(this.targetResolver.targetRoute(captured.target)));
    });
  }
  readSession(id: string): TerminalSessionSnapshot { return this.sessions.read(id); }
  subscribeSession(id: string, listener: (event: TerminalSessionEvent) => void): () => void { return this.sessions.subscribe(id, listener); }
  sendSessionBytes(id: string, bytes: Uint8Array): Promise<void> { return this.sessions.write(id, bytes); }
  execSession(id: string, command: string, target: SSHTargetSnapshot,
    signal?: AbortSignalLike): Promise<TerminalSessionSnapshot> {
    const captured: SSHTargetSnapshot = { profileId: target.profileId, digest: target.digest, usesDefault: target.usesDefault };
    return this.sessions.exec(id, command, captured, (): void => { this.targetProfile(captured); }, signal);
  }
  resizeSession(id: string, columns: number, rows: number): Promise<void> { return this.sessions.resize(id, columns, rows); }
  stopSession(id: string): Promise<TerminalSessionSnapshot> { return this.sessions.stop(id); }
  async interruptForBackground(): Promise<void> {
    const pending: PendingOperation[] = this.pending.slice();
    for (const op of pending) { op.interrupted = true; op.controller.abort(); }
    await Promise.all([this.jobs.interrupt(), this.sessions.interrupt(), ...pending.map((op: PendingOperation): Promise<void> => op.done)]);
  }
}
