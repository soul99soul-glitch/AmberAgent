import type { AbortSignalLike } from '@amber/deepread-domain';
import type { AgentTaskStore } from '../agent_task.ts';
import { agentTaskStatusRunning } from '../agent_task.ts';
import { newId } from '../ids.ts';
import type { SSHCredentialStorePort, SSHTransportPort, TerminalLogPort } from './ports.ts';
import type {
  SSHConnectionOptions, SSHCredential, SSHHandle, SSHProfile, SSHProfileDraft, SSHReadPacket,
  SSHTargetSnapshot, SSHTrustProbe, TerminalCommandRequest, TerminalJobSnapshot, TerminalSessionEvent,
  TerminalSessionRequest, TerminalSessionSnapshot,
} from './models.ts';
import { SSHProfileStore, TerminalError, draftDigest, normalizeDraft, sameBinding } from './profile_store.ts';
import { SSHTargetResolver } from './target_resolver.ts';
import { TerminalController, checkAbort, relayAbort, terminalDelay } from './control.ts';
import { TerminalJobRuntime } from './job_runtime.ts';
import { TerminalSessionRuntime } from './session_runtime.ts';

export interface TerminalRuntimeDeps {
  profiles: SSHProfileStore;
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
  private pending: PendingOperation[] = [];
  constructor(private readonly deps: TerminalRuntimeDeps) {
    this.jobs = new TerminalJobRuntime(deps.transport, deps.logs, deps.taskStore);
    this.sessions = new TerminalSessionRuntime(deps.transport, deps.logs);
    this.targetResolver = new SSHTargetResolver({ profiles: deps.profiles, credentials: deps.credentials });
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
    const d: SSHProfileDraft = normalizeDraft(draft);
    const revision: number | null = this.deps.profiles.get(d.id)?.revision ?? null;
    return this.withPending(signal, async (op: PendingOperation): Promise<SSHTrustProbe> => {
      checkAbort(op.controller.signal);
      const result = await this.deps.transport.probe(newId(), { host: d.host, port: d.port, timeoutMs: 10000 }, op.controller.signal);
      checkAbort(op.controller.signal);
      if ((this.deps.profiles.get(d.id)?.revision ?? null) !== revision) throw new TerminalError('profile_conflict');
      const current: SSHProfile | null = this.deps.profiles.get(d.id);
      const expected: string | null = current !== null && current.knownHostHost === d.host && current.knownHostPort === d.port ?
        current.knownHostSHA256 : null;
      return { draftDigest: draftDigest(d), profileRevision: revision, fingerprintSHA256: result.fingerprintSHA256,
        hostKeyType: result.hostKeyType, expectedFingerprintSHA256: expected,
        trustState: expected === null ? 'untrusted' : expected === result.fingerprintSHA256 ? 'trusted' : 'mismatch' };
    });
  }
  verifyAndSave(draft: SSHProfileDraft, probe: SSHTrustProbe, credential: SSHCredential | null,
    acceptFingerprint: boolean, signal?: AbortSignalLike): Promise<SSHProfile> {
    const d: SSHProfileDraft = normalizeDraft(draft);
    return this.withPending(signal, async (op: PendingOperation): Promise<SSHProfile> => {
      const validate: () => void = (): void => {
        checkAbort(op.controller.signal);
        if (draftDigest(d) !== probe.draftDigest ||
          (this.deps.profiles.get(d.id)?.revision ?? null) !== probe.profileRevision) throw new TerminalError('profile_conflict');
      };
      validate();
      if (!probe.fingerprintSHA256.startsWith('SHA256:')) throw new TerminalError('invalid_arguments');
      const old: SSHProfile | null = this.deps.profiles.get(d.id);
      const trusted: boolean = old !== null && old.knownHostHost === d.host && old.knownHostPort === d.port &&
        old.knownHostSHA256 === probe.fingerprintSHA256;
      if (!trusted && !acceptFingerprint) throw new TerminalError('host_trust_required');
      let auth: SSHCredential | null = credential;
      if (auth === null && old !== null && sameBinding(old, d) && old.credentialRef !== null) {
        auth = await this.deps.credentials.load(old.credentialRef, d);
      }
      if (auth === null || !auth.secret) throw new TerminalError('credential_missing');
      validate();
      let handle: SSHHandle | null = null;
      let terminal: boolean = false;
      try {
        handle = await this.deps.transport.startExec(newId(), this.connection(d, auth, probe.fingerprintSHA256),
          { command: "printf 'amber-terminal-auth-check\\n'", timeoutMs: 10000 }, op.controller.signal);
        const deadline: number = Date.now() + 10000;
        while (!terminal) {
          validate();
          if (Date.now() >= deadline) throw new TerminalError('connection_timeout');
          const packet: SSHReadPacket = await this.deps.transport.read(handle, 65536);
          terminal = packet.state !== 'running';
          if (terminal && (packet.state !== 'exited' || packet.exitCode !== 0)) {
            throw new TerminalError(packet.errorCode ?? 'authentication_check_failed');
          }
          if (!terminal) await terminalDelay(100, op.controller.signal);
        }
      } finally {
        if (handle !== null) await this.deps.transport.close(handle,
          terminal ? 'release' : op.interrupted ? 'disconnected' : 'cancelled');
      }
      validate();
      return this.deps.profiles.commitVerified(d, probe.profileRevision, probe.fingerprintSHA256, credential);
    });
  }
  private connection(binding: SSHProfileDraft, credential: SSHCredential, fingerprint: string): SSHConnectionOptions {
    return { host: binding.host, port: binding.port, username: binding.username, authMethod: binding.authMethod,
      secret: credential.secret, passphrase: credential.passphrase, expectedFingerprintSHA256: fingerprint, connectTimeoutMs: 10000 };
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
      return this.jobs.start(captured, connection, (): void => { this.targetProfile(captured.target); }, op.controller.signal);
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
      return this.sessions.start(captured, connection, (): void => { this.targetProfile(captured.target); }, op.controller.signal);
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
