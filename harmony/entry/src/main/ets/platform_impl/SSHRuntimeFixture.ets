// Fixed local SSH/Asset acceptance checks; never available in a release build.
import { Want, Context } from '@kit.AbilityKit';
import { util } from '@kit.ArkTS';
import { fileIo } from '@kit.CoreFileKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import BuildProfile from 'BuildProfile';
import {
  AgentTaskStore, SSHProfileStore, TerminalRuntime, createMemoryKeyValueStore,
} from '@amber/chat-domain';
import type {
  AgentTaskFilePort, AgentTaskSnapshot, SSHCredential, SSHCredentialBinding,
  SSHCredentialStorePort, SSHConnectionOptions, SSHHandle, SSHProfileDraft,
  SSHProfile, SSHTrustProbe, SSHTransportPort, TerminalCommandRequest,
  TerminalJobSnapshot, TerminalSessionEvent, TerminalSessionSnapshot,
} from '@amber/chat-domain';
import { createEntrySSHCredentialStore } from './SSHCredentialStore.ets';
import { createNativeSSHTransport } from './NativeSSHTransport.ets';
import { createEntryTerminalLogPort } from './TerminalLogPort.ets';
import { EntryAbortController } from './EntryAbortController.ets';

const DOMAIN: number = 0x0001;
const TAG: string = 'SSH.Fixture';
const PIN: string = 'SHA256:cKNdbZG2rwmIGdsuNSEdLYA55g9Zy5JFQvXNXIdVZA4';
const EXPECTED: number = 17;

interface FixtureInputs {
  runId: string;
  password: string;
  privateKey: string;
  passphrase: string;
}
interface FixtureResult { runId: string; name: string; pass: boolean; }
interface FixtureSummary { runId: string; name: string; completed: number; expected: number; pass: boolean; }
interface CodedError extends Error { code: string; }
interface FixtureError extends Error { code?: string | number; }
interface FixtureFailure { runId: string; name: string; stage: string; errorCode: string; errorName: string; }

// Only task metadata is in memory. Credentials, network and log files use real adapters.
class FixtureTaskFiles implements AgentTaskFilePort {
  private data: Map<string, string> = new Map<string, string>();
  async mkdirs(_dir: string): Promise<void> {}
  async listJsonFileNames(_dir: string): Promise<string[]> { return []; }
  async readText(path: string): Promise<string | null> { return this.data.get(path) ?? null; }
  async writeText(path: string, text: string): Promise<void> { this.data.set(path, text); }
  async delete(path: string): Promise<void> { this.data.delete(path); }
  async exists(path: string): Promise<boolean> { return this.data.has(path); }
  async isPathInside(root: string, path: string): Promise<boolean> { return path.startsWith(root + '/'); }
}

const delay = (ms: number): Promise<void> => new Promise((resolve): void => { setTimeout(resolve, ms); });
const bytes = (text: string): Uint8Array => util.TextEncoder.create('utf-8').encodeInto(text);
const binding = (privateKey: boolean = false): SSHCredentialBinding => ({
  host: '127.0.0.1', port: 22224, username: 'amber-fixture', authMethod: privateKey ? 'privateKey' : 'password',
});
const draft = (id: string, privateKey: boolean = false): SSHProfileDraft => ({
  id: id, name: 'SSH fixed device fixture', host: '127.0.0.1', port: 22224,
  username: 'amber-fixture', authMethod: privateKey ? 'privateKey' : 'password',
});
const commandRequest = (runtime: TerminalRuntime, profileId: string, command: string,
  source: string = 'ssh_fixture'): TerminalCommandRequest => ({
  target: runtime.captureTarget(profileId), command: command, cwd: null, timeoutMs: 10000,
  sourceToolName: source, sourceConversationId: null,
});

class SSHFixtureRun {
  private readonly input: FixtureInputs;
  private completed: number = 0;
  private pass: boolean = true;
  private stage: string = 'initialization';
  private logPaths: string[] = [];
  private credentials: SSHCredentialStorePort = createEntrySSHCredentialStore();
  private transport: SSHTransportPort = createNativeSSHTransport();
  private largeReference: string = util.generateRandomUUID(false);
  private profiles: SSHProfileStore | null = null;
  private runtime: TerminalRuntime | null = null;
  private unsubscribe: (() => void) | null = null;

  constructor(input: FixtureInputs) { this.input = input; }

  private record(name: string, pass: boolean): void {
    const result: FixtureResult = { runId: this.input.runId, name: name, pass: pass };
    hilog.info(DOMAIN, TAG, '%{public}s', JSON.stringify(result));
    this.completed++;
    this.pass = this.pass && pass;
  }

  async run(): Promise<void> {
    try {
      await this.checkAsset();
      const profiles: SSHProfileStore = await SSHProfileStore.create({
        kv: createMemoryKeyValueStore(), credentials: this.credentials,
      });
      this.profiles = profiles;
      const taskStore: AgentTaskStore = await AgentTaskStore.create({
        taskDir: '/ssh-fixture/tasks', appFilesDir: '/ssh-fixture', files: new FixtureTaskFiles(),
      });
      const runtime: TerminalRuntime = new TerminalRuntime({
        profiles: profiles, credentials: this.credentials, transport: this.transport,
        logs: createEntryTerminalLogPort(), taskStore: taskStore,
      });
      this.runtime = runtime;
      const profileDraft: SSHProfileDraft = draft(util.generateRandomUUID(false));
      this.stage = 'probe-pinned-host';
      const probe: SSHTrustProbe = await runtime.probe(profileDraft);
      this.record(this.stage, probe.fingerprintSHA256 === PIN && probe.trustState === 'untrusted');
      // Never authenticate against an unexpected fixture key, even in a debug check.
      if (probe.fingerprintSHA256 !== PIN) throw new Error('fixture_host_changed');
      await this.checkWrongPin();
      this.stage = 'password-verify-and-save';
      const profile: SSHProfile = await runtime.verifyAndSave(profileDraft, probe,
        { secret: this.input.password, passphrase: null }, true);
      this.record(this.stage, profile.knownHostSHA256 === PIN && profile.credentialRef !== null &&
        await this.credentials.exists(profile.credentialRef));
      await this.checkCommand(runtime, profile.id);
      await this.checkAbort(runtime, taskStore, profile.id);
      await this.checkPrivateKey(runtime, profiles);
      await this.checkPty(runtime, profile.id);
    } catch (error) {
      // Deliberately omit exception messages and all input values from the device log.
      const coded: FixtureError = error as FixtureError;
      const code: string = String(coded.code ?? 'unknown');
      const name: string = String(coded.name ?? 'unknown');
      const failure: FixtureFailure = { runId: this.input.runId, name: 'failure', stage: this.stage,
        errorCode: /^[A-Za-z0-9_.-]{1,64}$/.test(code) ? code : 'unknown',
        errorName: ['Error', 'BusinessError', 'TerminalError', 'SSHTransportError', 'TypeError', 'RangeError']
          .includes(name) ? name : 'unknown' };
      hilog.error(DOMAIN, TAG, '%{public}s', JSON.stringify(failure));
      this.record(this.stage, false);
    } finally {
      await this.cleanup();
      const summary: FixtureSummary = { runId: this.input.runId, name: 'complete',
        completed: this.completed, expected: EXPECTED, pass: this.pass && this.completed === EXPECTED };
      hilog.info(DOMAIN, TAG, '%{public}s', JSON.stringify(summary));
    }
  }

  private async checkAsset(): Promise<void> {
    this.stage = 'asset-large-roundtrip';
    const largeKey: string = this.input.privateKey + '\n'.repeat(Math.max(0, 4096 - this.input.privateKey.length));
    const credential: SSHCredential = { secret: largeKey, passphrase: this.input.passphrase };
    await this.credentials.save(this.largeReference, binding(true), credential);
    const loaded: SSHCredential | null = await this.credentials.load(this.largeReference, binding(true));
    this.record(this.stage, largeKey.length >= 4096 && await this.credentials.exists(this.largeReference) &&
      loaded !== null && loaded.secret === largeKey && loaded.passphrase === this.input.passphrase);

    this.stage = 'asset-binding-rejected';
    let rejected: boolean = false;
    const wrong: SSHCredentialBinding = { host: '127.0.0.1', port: 22224,
      username: 'different-fixture-user', authMethod: 'privateKey' };
    try { await this.credentials.load(this.largeReference, wrong); }
    catch (error) { rejected = (error as CodedError).code === 'credential_invalid'; }
    this.record(this.stage, rejected);

    this.stage = 'asset-file-has-no-plaintext';
    const context: Context | undefined = AppStorage.get<Context>('abilityContext');
    if (context === undefined) throw new Error('fixture_context_missing');
    const encrypted: string = await fileIo.readText(
      `${context.filesDir}/amberagent/terminal/credentials/${this.largeReference}.json`);
    this.record(this.stage, !encrypted.includes('-----BEGIN') && !encrypted.includes(this.input.passphrase) &&
      !encrypted.includes(largeKey));

    this.stage = 'asset-delete';
    await this.credentials.delete(this.largeReference);
    this.record(this.stage, !await this.credentials.exists(this.largeReference) &&
      await this.credentials.load(this.largeReference, binding(true)) === null);
  }

  private async checkWrongPin(): Promise<void> {
    this.stage = 'wrong-pin-before-auth';
    const connection: SSHConnectionOptions = { host: '127.0.0.1', port: 22224, username: 'amber-fixture',
      authMethod: 'password', secret: this.input.password, passphrase: null, connectTimeoutMs: 10000,
      expectedFingerprintSHA256: 'SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA' };
    let handle: SSHHandle | null = null;
    let rejected: boolean = false;
    try {
      handle = await this.transport.startExec(util.generateRandomUUID(false), connection,
        { command: "printf 'fixture-wrong-pin-must-not-run\\n'", timeoutMs: 10000 });
    } catch (error) { rejected = (error as CodedError).code === 'host_key_mismatch'; }
    finally { if (handle !== null) await this.transport.close(handle, 'cancelled'); }
    this.record(this.stage, rejected);
  }

  private async checkCommand(runtime: TerminalRuntime, profileId: string): Promise<void> {
    this.stage = 'exec-unicode-stderr-exit7';
    let job: TerminalJobSnapshot = await runtime.execute(commandRequest(runtime, profileId,
      "printf '中😀\\n'; printf '错误🚀\\n' >&2; exit 7"));
    this.logPaths.push(job.outputLogPath);
    if (job.status === 'RUNNING' || job.status === 'QUEUED') job = await runtime.waitJob(job.jobId, 10000);
    this.record(this.stage, job.status === 'FAILED' && job.exitCode === 7 &&
      job.stdoutTail === '中😀\n' && job.stderrTail === '错误🚀\n');
    this.stage = 'exec-private-complete-log';
    const log: string = await fileIo.readText(job.outputLogPath);
    this.record(this.stage, log.includes('中😀\n') && log.includes('[stderr] 错误🚀\n'));
  }

  private async checkAbort(runtime: TerminalRuntime, taskStore: AgentTaskStore, profileId: string): Promise<void> {
    this.stage = 'active-exec-abort';
    const controller: EntryAbortController = new EntryAbortController();
    let rejected: boolean = false;
    // Attach the rejection handler immediately; cancellation may happen during start.
    const executing: Promise<void> = runtime.execute(commandRequest(runtime, profileId,
      "printf 'fixture-running\\n'; sleep 30", 'ssh_fixture_abort'), controller.signal)
      .then((): void => {}, (): void => { rejected = true; });
    let task: AgentTaskSnapshot | undefined = undefined;
    const deadline: number = Date.now() + 3000;
    while (Date.now() < deadline) {
      task = taskStore.list().find((item: AgentTaskSnapshot): boolean => item.sourceToolName === 'ssh_fixture_abort');
      if (task !== undefined && task.status === 'RUNNING') break;
      await delay(100);
    }
    const wasRunning: boolean = task !== undefined && task.status === 'RUNNING' &&
      runtime.readJob(task.taskId).exitCode === null;
    controller.abort();
    await executing;
    if (task === undefined) { this.record(this.stage, false); return; }
    const job: TerminalJobSnapshot = runtime.readJob(task.taskId);
    this.logPaths.push(job.outputLogPath);
    this.record(this.stage, wasRunning && rejected && job.status === 'CANCELLED' && job.exitCode === null &&
      taskStore.read(task.taskId)?.cancelCapability === false);
  }

  private async checkPrivateKey(runtime: TerminalRuntime, profiles: SSHProfileStore): Promise<void> {
    this.stage = 'encrypted-rsa-auth';
    const keyDraft: SSHProfileDraft = draft(util.generateRandomUUID(false), true);
    const probe: SSHTrustProbe = await runtime.probe(keyDraft);
    if (probe.fingerprintSHA256 !== PIN) throw new Error('fixture_host_changed');
    const profile: SSHProfile = await runtime.verifyAndSave(keyDraft, probe,
      { secret: this.input.privateKey, passphrase: this.input.passphrase }, true);
    const job: TerminalJobSnapshot = await runtime.execute(commandRequest(runtime, profile.id,
      "printf 'fixture-rsa-ok\\n'"));
    this.logPaths.push(job.outputLogPath);
    this.record(this.stage, this.input.privateKey.length > 1024 && profiles.get(profile.id)?.credentialRef !== null &&
      job.status === 'COMPLETED' && job.exitCode === 0 && job.stdoutTail === 'fixture-rsa-ok\n');
  }

  private async sessionContains(runtime: TerminalRuntime, id: string, pattern: RegExp): Promise<boolean> {
    const deadline: number = Date.now() + 6000;
    while (Date.now() < deadline) {
      const session: TerminalSessionSnapshot = runtime.readSession(id);
      if (pattern.test(session.outputTail)) return true;
      if (session.status !== 'RUNNING' && session.status !== 'QUEUED') return false;
      await delay(100);
    }
    return false;
  }

  private async checkPty(runtime: TerminalRuntime, profileId: string): Promise<void> {
    this.stage = 'pty-first-prompt-bytes';
    const session: TerminalSessionSnapshot = await runtime.startSession({ target: runtime.captureTarget(profileId),
      cwd: null, columns: 80, rows: 24, sourceToolName: 'ssh_fixture', sourceConversationId: null });
    this.logPaths.push(session.outputLogPath);
    let byteCount: number = 0;
    this.unsubscribe = runtime.subscribeSession(session.sessionId, (event: TerminalSessionEvent): void => {
      for (const chunk of event.chunks) byteCount += chunk.bytes.length;
    });
    const prompt: boolean = await this.sessionContains(runtime, session.sessionId, /e2-fixture\$ /);
    this.record(this.stage, prompt && byteCount > 0 && session.status === 'RUNNING' && session.exitCode === null);

    this.stage = 'pty-initial-stty-size';
    await runtime.sendSessionBytes(session.sessionId, bytes('stty size\n'));
    this.record(this.stage, await this.sessionContains(runtime, session.sessionId, /(?:^|[\r\n])24 80[\r\n]/));

    this.stage = 'pty-resize-stty-size';
    await runtime.resizeSession(session.sessionId, 93, 31);
    await runtime.sendSessionBytes(session.sessionId, bytes('stty size\n'));
    const resized: boolean = await this.sessionContains(runtime, session.sessionId, /(?:^|[\r\n])31 93[\r\n]/);
    const current: TerminalSessionSnapshot = runtime.readSession(session.sessionId);
    this.record(this.stage, resized && current.columns === 93 && current.rows === 31);

    this.stage = 'pty-ctrl-c-shell-survives';
    await runtime.sendSessionBytes(session.sessionId, bytes('sleep 30\n'));
    await delay(300);
    await runtime.sendSessionBytes(session.sessionId, new Uint8Array([3]));
    await runtime.sendSessionBytes(session.sessionId, bytes("printf 'fixture-ctrl-c-ok\\n'\n"));
    this.record(this.stage, await this.sessionContains(runtime, session.sessionId,
      /(?:^|[\r\n])fixture-ctrl-c-ok[\r\n]/) && runtime.readSession(session.sessionId).status === 'RUNNING');

    this.stage = 'pty-stop-no-fake-exit';
    const stopped: TerminalSessionSnapshot = await runtime.stopSession(session.sessionId);
    this.record(this.stage, stopped.status === 'CANCELLED' && stopped.exitCode === null);
  }

  private async cleanup(): Promise<void> {
    let clean: boolean = true;
    this.unsubscribe?.();
    try { await this.runtime?.interruptForBackground(); } catch (_error) { clean = false; }
    if (this.profiles !== null) {
      for (const profile of this.profiles.snapshot().profiles) {
        try {
          await this.profiles.deleteProfile(profile.id, profile.revision);
          if (profile.credentialRef !== null) clean = !await this.credentials.exists(profile.credentialRef) && clean;
        }
        catch (_error) { clean = false; }
      }
    }
    try {
      await this.credentials.delete(this.largeReference);
      clean = !await this.credentials.exists(this.largeReference) && clean;
    } catch (_error) { clean = false; }
    for (const path of this.logPaths) {
      try { if (await fileIo.access(path)) await fileIo.unlink(path); }
      catch (_error) { clean = false; }
    }
    this.record('cleanup', clean);
  }
}

export const runSSHRuntimeFixture = async (want: Want): Promise<void> => {
  if (!BuildProfile.DEBUG || want.parameters?.['amberSSHFixture'] !== 'ssh-v1') return;
  const runId: string = String(want.parameters?.['amberSSHRunId'] ?? '').trim();
  if (!/^[A-Za-z0-9._-]{1,64}$/.test(runId)) return;
  const input: FixtureInputs = { runId: runId,
    password: String(want.parameters?.['amberSSHPassword'] ?? ''),
    privateKey: String(want.parameters?.['amberSSHPrivateKey'] ?? ''),
    passphrase: String(want.parameters?.['amberSSHPassphrase'] ?? '') };
  if (input.password.length < 1 || input.password.length > 256 || input.privateKey.length < 1025 ||
    input.privateKey.length > 16384 || !input.privateKey.includes('-----BEGIN') ||
    input.passphrase.length < 8 || input.passphrase.length > 256) {
    const summary: FixtureSummary = { runId: runId, name: 'invalid-fixture-input',
      completed: 0, expected: EXPECTED, pass: false };
    hilog.info(DOMAIN, TAG, '%{public}s', JSON.stringify(summary));
    return;
  }
  await new SSHFixtureRun(input).run();
};
