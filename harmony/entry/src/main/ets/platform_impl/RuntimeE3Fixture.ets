// Fixed Debug acceptance checks. No Want-provided Python source or shell command.
import { Want } from '@kit.AbilityKit';
import { util } from '@kit.ArkTS';
import { hilog } from '@kit.PerformanceAnalysisKit';
import BuildProfile from 'BuildProfile';
import {
  AgentTaskStore, PythonRuntime, MoshRuntime, SSHProfileStore, SSHTargetResolver,
  createMemoryKeyValueStore,
} from '@amber/chat-domain';
import type {
  AgentTaskFilePort, AgentTaskStatus, AgentTaskSnapshot, PythonExecuteRequest,
  PythonSnapshot, SSHProfileDraft, SSHProfile, SSHCredentialStorePort,
  MoshSessionEvent, MoshSessionSnapshot,
} from '@amber/chat-domain';
import { createNativePythonTransport } from './NativePythonTransport.ets';
import { createNativeMoshTransport } from './NativeMoshTransport.ets';
import { createNativeSSHTransport } from './NativeSSHTransport.ets';
import { createEntrySSHCredentialStore } from './SSHCredentialStore.ets';
import { EntryAbortController } from './EntryAbortController.ets';

const DOMAIN: number = 0x0001;
const TAG: string = 'E3.Fixture';
const MOSH_PIN: string = 'SHA256:2C/scFRFC6NoNJmL2ogZAhVd/tjTmVoZ2FnpaN7s2MY';

interface FixtureResult { runId: string; name: string; pass: boolean; }
interface FixtureSummary { runId: string; name: string; completed: number; expected: number; pass: boolean; }
interface PythonCase {
  name: string;
  source: string;
  stdin: string;
  status: AgentTaskStatus;
  exitCode: number;
  stdout: string;
  stderrContains: string | null;
  errorCode: string | null;
}

class FixtureResults {
  private readonly runId: string;
  private readonly summaryName: string;
  private readonly expected: number;
  private completed: number = 0;
  private pass: boolean = true;

  constructor(runId: string, summaryName: string, expected: number) {
    this.runId = runId; this.summaryName = summaryName; this.expected = expected;
  }

  record(name: string, pass: boolean): void {
    const result: FixtureResult = { runId: this.runId, name: name, pass: pass };
    hilog.info(DOMAIN, TAG, '%{public}s', JSON.stringify(result));
    this.completed++; this.pass = this.pass && pass;
  }

  complete(): void {
    const summary: FixtureSummary = { runId: this.runId, name: this.summaryName,
      completed: this.completed, expected: this.expected, pass: this.pass && this.completed === this.expected };
    hilog.info(DOMAIN, TAG, '%{public}s', JSON.stringify(summary));
  }
}

// Metadata only; neither interpreter nor UDP/SSH behavior is substituted.
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

const fixtureTaskStore = (): Promise<AgentTaskStore> => AgentTaskStore.create({
  taskDir: '/e3-fixture/tasks', appFilesDir: '/e3-fixture', files: new FixtureTaskFiles(),
});
const delay = (ms: number): Promise<void> => new Promise((resolve): void => { setTimeout(resolve, ms); });
const bytes = (text: string): Uint8Array => util.TextEncoder.create('utf-8').encodeInto(text);
const pythonRequest = (source: string, stdin: string = '', timeoutMs: number = 15000): PythonExecuteRequest => ({
  source: source, stdin: stdin, timeoutMs: timeoutMs,
  sourceToolName: 'python_fixture', sourceConversationId: null,
});

const BASIC_CASES: PythonCase[] = [
  { name: 'python-version-stdin-unicode-json-math', source: "import json, math\nprint(input())\nvalue = json.loads(stdin)\nprint(value['message'])\nprint(math.isqrt(value['n']))",
    stdin: '{"message":"中😀","n":81}', status: 'COMPLETED', exitCode: 0,
    stdout: '{"message":"中😀","n":81}\n中😀\n9\n', stderrContains: null, errorCode: null },
  { name: 'python-exception', source: "raise ValueError('fixed-fixture-error')", stdin: '',
    status: 'FAILED', exitCode: 1, stdout: '', stderrContains: 'ValueError: fixed-fixture-error', errorCode: 'python_exception' },
  { name: 'python-open-rejected', source: "open('fixture-do-not-create', 'w')", stdin: '',
    status: 'FAILED', exitCode: 1, stdout: '', stderrContains: 'unavailable in embedded Python: open', errorCode: 'python_exception' },
  { name: 'python-os-rejected', source: 'import os', stdin: '',
    status: 'FAILED', exitCode: 1, stdout: '', stderrContains: 'unavailable in embedded Python: os', errorCode: 'python_exception' },
  { name: 'python-network-rejected', source: 'import socket', stdin: '',
    status: 'FAILED', exitCode: 1, stdout: '', stderrContains: 'unavailable in embedded Python: socket', errorCode: 'python_exception' },
];

const FRESH_CASES: PythonCase[] = [
  { name: 'python-module-mutation', source: "import json\njson.dumps = lambda value: 'changed'\ntransient = 9\nprint(json.dumps({}))", stdin: '',
    status: 'COMPLETED', exitCode: 0, stdout: 'changed\n', stderrContains: null, errorCode: null },
  { name: 'python-fresh-module-and-globals', source: "import json\ntry:\n    print(transient)\nexcept NameError:\n    print('fresh')\nprint(json.dumps({'value': 7}, sort_keys=True))", stdin: '',
    status: 'COMPLETED', exitCode: 0, stdout: 'fresh\n{"value": 7}\n', stderrContains: null, errorCode: null },
];

const checkPythonCase = async (runtime: PythonRuntime, fixture: PythonCase): Promise<boolean> => {
  const result: PythonSnapshot = await runtime.execute(pythonRequest(fixture.source, fixture.stdin));
  return result.status === fixture.status && result.exitCode === fixture.exitCode &&
    result.stdout === fixture.stdout && result.errorCode === fixture.errorCode &&
    (fixture.stderrContains === null ? result.stderr === '' : result.stderr.includes(fixture.stderrContains));
};

const checkPythonCancel = async (runtime: PythonRuntime, source: string, marker: string): Promise<boolean> => {
  const controller: EntryAbortController = new EntryAbortController();
  const startedAt: number = Date.now();
  const timer: number = setTimeout((): void => { controller.abort(); }, 1000);
  try {
    const result: PythonSnapshot = await runtime.execute(pythonRequest(source), controller.signal);
    return result.status === 'CANCELLED' && result.exitCode === null && result.errorCode === 'cancelled' &&
      result.stdout.includes(marker) && Date.now() - startedAt < 6000;
  } finally { clearTimeout(timer); }
};

const runPythonFixtures = async (runId: string): Promise<void> => {
  const results: FixtureResults = new FixtureResults(runId, 'python-complete', 11);
  let runtime: PythonRuntime | null = null;
  let taskStore: AgentTaskStore | null = null;
  let stage: string = 'python-setup';
  try {
    taskStore = await fixtureTaskStore();
    runtime = new PythonRuntime({ transport: await createNativePythonTransport(), taskStore: taskStore });
    for (const fixture of BASIC_CASES) {
      stage = fixture.name;
      const matched: boolean = await checkPythonCase(runtime, fixture);
      results.record(stage, matched && (fixture.name !== 'python-version-stdin-unicode-json-math' ||
        runtime.version().startsWith('3.14.7')));
    }
    stage = 'python-output-limit';
    const limit: PythonSnapshot = await runtime.execute(pythonRequest("print('x' * (128 * 1024 + 1))"));
    results.record(stage, limit.status === 'FAILED' && limit.exitCode === 1 && limit.errorCode === 'output_limit' &&
      bytes(limit.stdout).length === 128 * 1024 && limit.stderr === '');

    stage = 'python-loop-timeout';
    const timed: PythonSnapshot = await runtime.execute(pythonRequest("print('timeout-start')\nwhile True:\n    pass", '', 1500));
    results.record(stage, timed.status === 'TIMED_OUT' && timed.exitCode === null && timed.errorCode === 'timed_out' &&
      timed.stdout.includes('timeout-start\n'));

    stage = 'python-swallowed-base-exception-cancel';
    results.record(stage, await checkPythonCancel(runtime,
      "print('swallow-start')\nwhile True:\n    try:\n        while True:\n            pass\n    except BaseException:\n        pass", 'swallow-start\n'));
    for (const fixture of FRESH_CASES) {
      stage = fixture.name;
      results.record(stage, await checkPythonCase(runtime, fixture));
    }
  } catch (_error) {
    // No source, stdin, Python result, error message or arbitrary exception object in logs.
    results.record(stage, false);
  } finally {
    let clean: boolean = true;
    try { await runtime?.interruptForBackground(); } catch (_error) { clean = false; }
    if (taskStore !== null) {
      clean = clean && taskStore.list().every((task: AgentTaskSnapshot): boolean =>
        task.status !== 'QUEUED' && task.status !== 'RUNNING' && !task.cancelCapability);
    }
    results.record('python-cleanup', clean);
    results.complete();
  }
};

// Assertion decoding only; production xterm receives untouched frame bytes.
const frameText = (text: string): string => text
  .replace(/\x1b\[[0-?]*[ -/]*[@-~]/g, '')
  .replace(/\x1b\][^\x07]*(?:\x07|\x1b\\)/g, '')
  .replace(/\x1b[()#][0-9A-Za-z]/g, '');

const moshContains = async (runtime: MoshRuntime, id: string, pattern: RegExp): Promise<boolean> => {
  const deadline: number = Date.now() + 10000;
  while (Date.now() < deadline) {
    const session: MoshSessionSnapshot = runtime.readSession(id);
    if (pattern.test(frameText(session.outputTail))) return true;
    if (session.status !== 'RUNNING' && session.status !== 'QUEUED') return false;
    await delay(100);
  }
  return false;
};

const runMoshFixtures = async (runId: string, target: string, password: string): Promise<void> => {
  const results: FixtureResults = new FixtureResults(runId, 'mosh-complete', 4);
  const credentials: SSHCredentialStorePort = createEntrySSHCredentialStore();
  let profiles: SSHProfileStore | null = null;
  let runtime: MoshRuntime | null = null;
  let taskStore: AgentTaskStore | null = null;
  let unsubscribe: (() => void) | null = null;
  let stage: string = 'mosh-setup';
  try {
    if ((target !== 'emulator' && target !== 'phone') || password.length < 1 || password.length > 256) {
      results.record('mosh-invalid-fixture-input', false);
      return;
    }
    profiles = await SSHProfileStore.create({ kv: createMemoryKeyValueStore(), credentials: credentials });
    const profileDraft: SSHProfileDraft = { id: util.generateRandomUUID(false), name: 'Mosh fixed device fixture',
      host: target === 'emulator' ? '10.0.2.2' : '192.168.100.107', port: 22225,
      username: 'amber-fixture', authMethod: 'password' };
    // The known fixture pin is explicitly approved here; native bootstrap must still check it before auth.
    const profile: SSHProfile = await profiles.commitVerified(profileDraft, null, MOSH_PIN,
      { secret: password, passphrase: null });
    const resolver: SSHTargetResolver = new SSHTargetResolver({ profiles: profiles, credentials: credentials });
    taskStore = await fixtureTaskStore();
    runtime = new MoshRuntime({ targetResolver: resolver, sshTransport: createNativeSSHTransport(),
      transport: createNativeMoshTransport(), taskStore: taskStore });
    stage = 'mosh-bootstrap-unicode-bytes';
    const session: MoshSessionSnapshot = await runtime.startSession({ target: resolver.captureTarget(profile.id),
      columns: 80, rows: 24, serverLocale: 'en_US.UTF-8', sourceToolName: 'mosh_fixture', sourceConversationId: null });
    if (session.status !== 'RUNNING') { results.record(stage, false); return; }
    let byteCount: number = 0;
    unsubscribe = runtime.subscribeSession(session.sessionId, (event: MoshSessionEvent): void => {
      for (const chunk of event.chunks) byteCount += chunk.bytes.length;
    });
    await runtime.sendSessionBytes(session.sessionId, bytes("printf 'MOSH-%s-%s\\n' '中😀' 'ok'\n"));
    results.record(stage, await moshContains(runtime, session.sessionId, /MOSH-中😀-ok/) &&
      byteCount > 0 && runtime.readSession(session.sessionId).connectionState === 'running');

    stage = 'mosh-resize-stty-size';
    await runtime.resizeSession(session.sessionId, 93, 31);
    await runtime.sendSessionBytes(session.sessionId, bytes("printf 'MSIZE-RESIZED[%s]\\n' \"$(stty size)\"\n"));
    const resized: boolean = await moshContains(runtime, session.sessionId, /MSIZE-RESIZED\[31 93\]/);
    const current: MoshSessionSnapshot = runtime.readSession(session.sessionId);
    results.record(stage, resized && current.columns === 93 && current.rows === 31);

    stage = 'mosh-stop';
    const stopped: MoshSessionSnapshot = await runtime.stopSession(session.sessionId);
    results.record(stage, stopped.status === 'CANCELLED' && stopped.connectionState === 'cancelled' &&
      taskStore.read(session.sessionId)?.cancelCapability === false);
  } catch (_error) { results.record(stage, false); }
  finally {
    let clean: boolean = true;
    unsubscribe?.();
    try { await runtime?.interruptForBackground(); } catch (_error) { clean = false; }
    if (profiles !== null) {
      for (const profile of profiles.snapshot().profiles) {
        try {
          await profiles.deleteProfile(profile.id, profile.revision);
          if (profile.credentialRef !== null) clean = !await credentials.exists(profile.credentialRef) && clean;
        } catch (_error) { clean = false; }
      }
    }
    if (taskStore !== null) {
      clean = clean && taskStore.list().every((task: AgentTaskSnapshot): boolean =>
        task.status !== 'QUEUED' && task.status !== 'RUNNING' && !task.cancelCapability &&
        !JSON.stringify(task).includes('MOSH CONNECT'));
    }
    results.record('mosh-cleanup', clean);
    results.complete();
  }
};

export const runRuntimeE3Fixture = async (want: Want): Promise<void> => {
  if (!BuildProfile.DEBUG || want.parameters?.['amberE3Fixture'] !== 'runtime-v1') return;
  const runId: string = String(want.parameters?.['amberE3RunId'] ?? '').trim();
  if (!/^[A-Za-z0-9._-]{1,64}$/.test(runId)) return;
  await runPythonFixtures(runId);
  const target: string = String(want.parameters?.['amberE3Target'] ?? '');
  if (target !== '') await runMoshFixtures(runId, target, String(want.parameters?.['amberE3Password'] ?? ''));
};
