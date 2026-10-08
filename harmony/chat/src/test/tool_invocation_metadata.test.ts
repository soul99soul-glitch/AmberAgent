import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { Conversation } from '../main/ets/chat/conversation.ts';
import { makeConversation, currentMessages } from '../main/ets/chat/conversation.ts';
import type { ConversationStore, ChatStreamProvider, ChatTurnDeps } from '../main/ets/chat/chat_turn.ts';
import type { UIMessagePart, UIMessagePartTool, MessageChunk } from '../main/ets/chat/message.ts';
import { makeUIMessage } from '../main/ets/chat/message.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import type { JsonObject } from '../main/ets/chat/json.ts';
import type { AgentTool } from '../main/ets/chat/tool.ts';
import { makeAgentTool } from '../main/ets/chat/tool.ts';
import type { ToolLoopOptions } from '../main/ets/chat/tool_loop.ts';
import { runChatTurnWithTools, runToolLoopContinuation } from '../main/ets/chat/tool_loop.ts';
import { AgentToolDispatcher, defaultToolInvocationHooks } from '../main/ets/chat/tool_dispatcher.ts';
import { createToolRegistry } from '../main/ets/chat/tool_registry.ts';
import { createMemoryKeyValueStore } from '../main/ets/chat/kv_store.ts';
import { SSHProfileStore, targetDigest } from '../main/ets/chat/terminal/profile_store.ts';
import type { SSHCredentialStorePort, SSHTransportPort } from '../main/ets/chat/terminal/ports.ts';
import type { SSHCredential, SSHCredentialBinding, SSHTargetSnapshot,
  TerminalSessionSnapshot } from '../main/ets/chat/terminal/models.ts';
import { createTerminalApprovalGuard } from '../main/ets/chat/terminal_approval.ts';
import { TerminalRuntime } from '../main/ets/chat/terminal/runtime.ts';
import { createTerminalTools } from '../main/ets/chat/terminal/tools.ts';
import { AgentTaskStore } from '../main/ets/chat/agent_task.ts';
import { AgentToolActivityStore } from '../main/ets/chat/tool_activity.ts';
import { SSHTargetResolver } from '../main/ets/chat/terminal/target_resolver.ts';
import { MoshRuntime } from '../main/ets/chat/mosh/runtime.ts';
import { createMoshTools } from '../main/ets/chat/mosh/tools.ts';
import type { MoshTransportPort } from '../main/ets/chat/mosh/ports.ts';

class PersistedStore implements ConversationStore {
  saved: string[] = [];
  async save(conv: Conversation): Promise<void> { this.saved.push(JSON.stringify(conv)); }
  load(): Conversation { return JSON.parse(this.saved[this.saved.length - 1]) as Conversation; }
}
const toolPart = (id: string, name: string, input: string = '{}'): UIMessagePartTool => ({
  type: 'tool', toolCallId: id, toolName: name, input, output: [],
  approvalState: { type: 'auto' }, metadata: { batch_marker: id },
});
const providerFor = (parts: UIMessagePart[]): ChatStreamProvider => {
  let generated: boolean = false;
  return {
    streamText: async (_messages, onChunk): Promise<void> => {
      const delta = makeUIMessage('assistant',
        generated ? [{ type: 'text', text: 'done', metadata: null }] : parts);
      generated = true;
      const chunk: MessageChunk = { id: 'chunk', model: 'fixture', usage: null,
        choices: [{ index: 0, delta, message: null, finishReason: 'unknown' }] };
      onChunk(chunk);
    },
  };
};
const depsFor = (provider: ChatStreamProvider, store: PersistedStore): ChatTurnDeps => ({
  assistant: makeAssistant({}), inputTransformers: [], outputTransformers: [], provider, store,
});
const loopFor = (provider: ChatStreamProvider, tools: AgentTool[],
  capture: (part: UIMessagePartTool) => JsonObject | null): ToolLoopOptions => ({
  tools, makeProviderForStep: () => provider, captureInvocationMetadata: capture,
});
const partsOf = (conv: Conversation): UIMessagePartTool[] => currentMessages(conv)
  .flatMap((m) => m.parts).filter((p): p is UIMessagePartTool => p.type === 'tool');
const approve = (conv: Conversation): Conversation => ({
  ...conv, messageNodes: conv.messageNodes.map((node) => ({ ...node,
    messages: node.messages.map((message) => ({ ...message,
      parts: message.parts.map((part): UIMessagePart => part.type === 'tool'
        ? { ...part, approvalState: { type: 'approved' } } : part),
    })),
  })),
});
const gatedTool = (name: string, execute: () => Promise<UIMessagePart[]>): AgentTool => makeAgentTool({
  name, description: name, needsApproval: true, allowsAutoApproval: false, execute,
});

test('new batch captures invocation metadata into persisted pending calls without losing existing metadata', async () => {
  const store = new PersistedStore();
  const provider = providerFor([toolPart('first', 'conversation_search'), toolPart('second', 'memory_read')]);
  const tools = ['conversation_search', 'memory_read'].map((name) => gatedTool(name, async () => []));
  await runChatTurnWithTools(makeConversation('metadata-batch', []), 'run', depsFor(provider, store),
    loopFor(provider, tools, (part) => ({ captured: part.toolCallId })));
  const pending = partsOf(store.load());
  assert.equal(pending.length, 2);
  for (const part of pending) {
    assert.equal(part.approvalState.type, 'pending');
    assert.equal(part.metadata?.batch_marker, part.toolCallId);
    assert.equal(part.metadata?.captured, part.toolCallId);
    assert.ok(part.metadata?.permission_trace);
  }
});

test('approval continuation consumes persisted invocation metadata and never recaptures from rebuilt factory', async () => {
  const store = new PersistedStore();
  const provider = providerFor([toolPart('approved', 'conversation_search')]);
  let executions: number = 0;
  const tool = gatedTool('conversation_search', async () => {
    executions++;
    return [{ type: 'text', text: 'executed', metadata: null }];
  });
  await runChatTurnWithTools(makeConversation('metadata-resume', []), 'run', depsFor(provider, store),
    loopFor(provider, [tool], () => ({ captured: 'original' })));
  await store.save(approve(store.load()));
  let recaptures: number = 0;
  const out = await runToolLoopContinuation(store.load(), depsFor(provider, store),
    loopFor(provider, [tool], () => { recaptures++; return { captured: 'replacement' }; }));
  assert.equal(executions, 1);
  assert.equal(recaptures, 0);
  assert.equal(partsOf(out)[0].metadata?.captured, 'original');
});

test('new invocation metadata is persisted before execution when permission allows without pending', async () => {
  const store = new PersistedStore();
  const provider = providerFor([toolPart('auto', 'conversation_search')]);
  let observed: JsonObject | null = null;
  const tool = gatedTool('conversation_search', async () => {
    observed = partsOf(store.load())[0].metadata;
    return [{ type: 'text', text: 'executed', metadata: null }];
  });
  await runChatTurnWithTools(makeConversation('metadata-auto', []), 'run', depsFor(provider, store),
    { ...loopFor(provider, [tool], () => ({ captured: 'before-execute' })),
      autoApproveTools: true, autoApproveHighRiskTools: true });
  assert.equal((observed as JsonObject | null)?.['captured'], 'before-execute');
});

class FixtureCredentials implements SSHCredentialStorePort {
  private entries = new Map<string, SSHCredential>();
  async save(ref: string, _binding: SSHCredentialBinding, credential: SSHCredential): Promise<void> {
    this.entries.set(ref, credential);
  }
  async load(ref: string): Promise<SSHCredential | null> { return this.entries.get(ref) ?? null; }
  async exists(ref: string): Promise<boolean> { return this.entries.has(ref); }
  async delete(ref: string): Promise<void> { this.entries.delete(ref); }
}
class ApprovalRuntime {
  constructor(private profiles: SSHProfileStore) {}
  captureTarget(id: string | null): SSHTargetSnapshot {
    const profile = id === null ? this.profiles.defaultProfile() : this.profiles.get(id);
    if (profile === null) throw new Error('profile_missing');
    return { profileId: profile.id, digest: targetDigest(profile), usesDefault: id === null };
  }
  readSession(id: string): TerminalSessionSnapshot {
    if (id !== 'session-p') throw new Error('session_missing');
    return { sessionId: id, profileId: 'p', status: 'RUNNING', exitCode: null,
      outputTail: '$ ', outputLogPath: '/private/session-p.log', columns: 80, rows: 24,
      errorCode: null, errorMessage: null };
  }
}
class MoshApprovalRuntime {
  sessionReads: number = 0;
  constructor(private profiles: SSHProfileStore) {}
  captureTarget(id: string | null): SSHTargetSnapshot {
    const profile = id === null ? this.profiles.defaultProfile() : this.profiles.get(id);
    if (profile === null) throw new Error('profile_missing');
    return { profileId: profile.id, digest: targetDigest(profile), usesDefault: id === null };
  }
  readSession(id: string) {
    this.sessionReads++;
    if (id !== 'mosh-p') throw new Error('mosh_session_missing');
    return { sessionId: id, profileId: 'p', runtime: 'remote_mosh' as const, status: 'RUNNING' as const,
      connectionState: 'running' as const, reconnectLastHeardMs: 0, outputTail: '$ ', columns: 80, rows: 24,
      errorCode: null, errorMessage: null };
  }
}
const approvalFixture = async (parts: UIMessagePartTool[], enableMosh: boolean = false,
  review?: import('../main/ets/chat/tool_dispatcher.ts').AutoApprovalReview) => {
  const credentials = new FixtureCredentials();
  const profiles = await SSHProfileStore.create({ kv: createMemoryKeyValueStore(), credentials });
  for (const id of ['p', 'q']) {
    await profiles.commitVerified({ id, name: id, host: `host-${id}`, port: 22,
      username: 'fixture', authMethod: 'password' }, null, `SHA256:${id}`, { secret: 'fixture-only', passphrase: null });
  }
  await profiles.setDefault('p');
  const runtime = new ApprovalRuntime(profiles);
  const moshRuntime = new MoshApprovalRuntime(profiles);
  const store = new PersistedStore();
  const provider = providerFor(parts);
  let executed: string[] = [];
  const build = (): ToolLoopOptions => {
    const guard = createTerminalApprovalGuard(runtime, profiles, enableMosh ? moshRuntime : undefined);
    const names = Array.from(new Set(parts.map((part) => part.toolName)));
    // Construct new registry/factory on every continuation, like ChatPage.
    const tools = createToolRegistry(names.map((name) => gatedTool(name, async () => {
      executed = [...executed, name];
      return [{ type: 'text', text: 'executed', metadata: null }];
    }))).tools();
    return { ...loopFor(provider, tools, guard.captureInvocationMetadata),
      dispatcher: new AgentToolDispatcher({ hooks: [...defaultToolInvocationHooks(), guard.hook], autoApprovalReview: review }),
      autoApproveTools: review !== undefined, autoApproveHighRiskTools: review !== undefined };
  };
  await runChatTurnWithTools(makeConversation('ssh-approval', []), 'run', depsFor(provider, store), build());
  const resume = async (): Promise<Conversation> => {
    await store.save(approve(store.load()));
    return runToolLoopContinuation(store.load(), depsFor(provider, store), build());
  };
  return { profiles, credentials, runtime, moshRuntime, store, resume, executed: () => executed };
};
const assertTargetFailure = (conv: Conversation, callId: string): void => {
  const part = partsOf(conv).find((p) => p.toolCallId === callId)!;
  assert.equal(part.output[0].type, 'text');
  const payload = JSON.parse((part.output[0] as { text: string }).text);
  assert.equal(payload.status, 'failed');
  assert.equal(payload.error_code, 'target_changed');
  assert.equal(payload.recoverable, false);
};

test('persisted default target rejects changed default after approval factory rebuild', async () => {
  const s = await approvalFixture([toolPart('default', 'terminal_execute')]);
  const original = partsOf(s.store.load())[0].metadata?.terminal_target;
  const originalLabel = partsOf(s.store.load())[0].metadata?.terminal_target_label;
  assert.deepEqual(original, s.runtime.captureTarget(null));
  assert.equal(originalLabel, 'fixture@host-p:22');
  await s.profiles.setDefault('q');
  const out = await s.resume();
  assertTargetFailure(out, 'default');
  assert.deepEqual(s.executed(), []);
  assert.deepEqual(partsOf(out)[0].metadata?.terminal_target, original);
  assert.equal(partsOf(out)[0].metadata?.terminal_target_label, originalLabel);
});

test('persisted explicit target rejects host edit after approval factory rebuild', async () => {
  const s = await approvalFixture([toolPart('host', 'terminal_job_start', '{"profile_id":"p"}')]);
  const profile = s.profiles.get('p')!;
  await s.profiles.saveMetadata({ ...profile, host: 'changed-host' }, profile.revision);
  const out = await s.resume();
  assertTargetFailure(out, 'host');
  assert.deepEqual(s.executed(), []);
});

test('unchanged persisted target executes after new guard and serialized approval', async () => {
  const s = await approvalFixture([toolPart('same', 'terminal_session_start')]);
  const out = await s.resume();
  assert.deepEqual(s.executed(), ['terminal_session_start']);
  assert.equal((partsOf(out)[0].output[0] as { text: string }).text, 'executed');
  assert.equal(partsOf(out)[0].metadata?.batch_marker, 'same');
});

test('old approved terminal call without persisted target context is rejected, never recaptured', async () => {
  const s = await approvalFixture([toolPart('legacy', 'terminal_execute')]);
  const legacy = s.store.load();
  const part = partsOf(legacy)[0];
  delete part.metadata!['terminal_target'];
  await s.store.save(legacy);
  const out = await s.resume();
  assertTargetFailure(out, 'legacy');
  assert.deepEqual(s.executed(), []);
  assert.equal(partsOf(out)[0].metadata?.terminal_target, undefined);
});

test('non-terminal and read/wait/stop calls retain metadata without target rebinding', async () => {
  const names = ['conversation_search', 'terminal_job_read', 'terminal_job_wait', 'terminal_job_stop',
    'terminal_session_read', 'terminal_session_stop'];
  const s = await approvalFixture(names.map((name) => toolPart(name, name)));
  for (const part of partsOf(s.store.load())) {
    assert.equal(part.metadata?.terminal_target, undefined);
    assert.equal(part.metadata?.batch_marker, part.toolCallId);
  }
  await s.profiles.setDefault('q');
  await s.resume();
  assert.deepEqual(s.executed().sort(), names.sort());
});

test('batch retains independent target and existing metadata through permission and execution', async () => {
  const s = await approvalFixture([toolPart('p-call', 'terminal_execute', '{"profile_id":"p"}'),
    toolPart('q-call', 'terminal_job_start', '{"profile_id":"q"}')]);
  const pending = partsOf(s.store.load());
  assert.deepEqual(pending.map((part) => part.metadata?.terminal_target),
    [s.runtime.captureTarget('p'), s.runtime.captureTarget('q')]);
  await s.profiles.setDefault('q');
  const out = await s.resume();
  assert.equal(s.executed().length, 2);
  const result = partsOf(out);
  for (let i = 0; i < 2; i++) {
    assert.deepEqual(result[i].metadata?.terminal_target, pending[i].metadata?.terminal_target);
    assert.equal(result[i].metadata?.batch_marker, pending[i].toolCallId);
    assert.ok(result[i].metadata?.permission_trace);
  }
});

test('session exec pins session profile explicitly and survives unrelated default changes', async () => {
  const s = await approvalFixture([toolPart('pty', 'terminal_session_exec', '{"session_id":"session-p"}')]);
  assert.deepEqual(partsOf(s.store.load())[0].metadata?.terminal_target, s.runtime.captureTarget('p'));
  await s.profiles.setDefault('q');
  await s.resume();
  assert.deepEqual(s.executed(), ['terminal_session_exec']);
});

test('session exec rejects changes to its own profile before resumed command send', async () => {
  const s = await approvalFixture([toolPart('pty-edit', 'terminal_session_exec', '{"session_id":"session-p"}')]);
  const profile = s.profiles.get('p')!;
  await s.profiles.saveMetadata({ ...profile, host: 'changed-pty-host' }, profile.revision);
  assertTargetFailure(await s.resume(), 'pty-edit');
  assert.deepEqual(s.executed(), []);
});

test('guard captures factory-time targets rather than current config at pending capture', async () => {
  const s = await approvalFixture([toolPart('freeze', 'terminal_execute')]);
  const guard = createTerminalApprovalGuard(s.runtime, s.profiles);
  const before = s.runtime.captureTarget(null);
  await s.profiles.setDefault('q');
  assert.deepEqual(guard.captureInvocationMetadata(toolPart('new-call', 'terminal_execute'))?.terminal_target, before);
});

test('explicit profile and session on that profile capture the same non-secret approval label', async () => {
  const s = await approvalFixture([toolPart('explicit-label', 'terminal_execute', '{"profile_id":"p"}'),
    toolPart('session-label', 'terminal_session_exec', '{"session_id":"session-p"}')]);
  const pending = partsOf(s.store.load());
  assert.deepEqual(pending.map((part) => part.metadata?.terminal_target_label),
    ['fixture@host-p:22', 'fixture@host-p:22']);
  assert.equal(pending.map((part) => part.metadata?.terminal_target_label).join('').includes('fixture-only'), false);
  await s.resume();
});

test('missing profile retains null target and blank label and is refused on approval continuation', async () => {
  const s = await approvalFixture([toolPart('missing-label', 'terminal_execute', '{"profile_id":"missing"}')]);
  assert.equal(partsOf(s.store.load())[0].metadata?.terminal_target, null);
  assert.equal(partsOf(s.store.load())[0].metadata?.terminal_target_label, '');
  assertTargetFailure(await s.resume(), 'missing-label');
  assert.deepEqual(s.executed(), []);
});

test('empty profile_id resumes against default through real terminal tools and runtime', async () => {
  const s = await approvalFixture([toolPart('setup', 'terminal_execute')]);
  const files = new Map<string, string>();
  const taskStore = await AgentTaskStore.create({ taskDir: '/files/tasks', appFilesDir: '/files', files: {
    async mkdirs() {}, async listJsonFileNames() { return []; },
    async readText(path: string) { return files.get(path) ?? null; },
    async writeText(path: string, value: string) { files.set(path, value); },
    async delete(path: string) { files.delete(path); }, async exists(path: string) { return files.has(path); },
    async isPathInside(root: string, path: string) { return path.startsWith(root + '/'); },
  } });
  let starts: number = 0;
  const exited = () => ({ chunks: [], state: 'exited' as const, exitCode: 0, errorCode: null, errorMessage: null });
  const transport: SSHTransportPort = {
    async probe() { return { fingerprintSHA256: 'SHA256:p', hostKeyType: 'ed25519' }; },
    async startExec(_id, connection) { starts++; assert.equal(connection.host, 'host-p'); return { id: 'exec', kind: 'exec' }; },
    async startPty() { return { id: 'pty', kind: 'pty' }; },
    async read() { return exited(); }, async close() { return exited(); }, async write() {}, async resize() {},
  };
  const runtime = new TerminalRuntime({ profiles: s.profiles, credentials: s.credentials, transport, taskStore,
    logs: { async create(id) { return '/files/logs/' + id; }, async append() {} } });
  const store = new PersistedStore();
  const provider = providerFor([toolPart('empty-profile', 'terminal_execute', '{"profile_id":"","command":"echo fixture"}')]);
  const build = (): ToolLoopOptions => {
    const guard = createTerminalApprovalGuard(runtime, s.profiles);
    const tools = createToolRegistry(createTerminalTools({ runtime, profiles: s.profiles,
      activityStore: new AgentToolActivityStore(), conversationId: 'empty-profile' })).tools();
    return { ...loopFor(provider, tools, guard.captureInvocationMetadata),
      dispatcher: new AgentToolDispatcher({ hooks: [...defaultToolInvocationHooks(), guard.hook] }) };
  };
  await runChatTurnWithTools(makeConversation('empty-profile', []), 'run', depsFor(provider, store), build());
  assert.equal(starts, 0);
  await store.save(approve(store.load()));
  const out = await runToolLoopContinuation(store.load(), depsFor(provider, store), build());
  assert.equal(starts, 1);
  assert.deepEqual(partsOf(out)[0].metadata?.terminal_target, runtime.captureTarget(null));
  const result = JSON.parse((partsOf(out)[0].output[0] as { text: string }).text);
  assert.equal(result.profile_id, 'p');
  assert.equal(result.status, 'completed');
});

test('Mosh pending persists its protocol and readable target through rebuilt approval guard', async () => {
  const s = await approvalFixture([toolPart('mosh-start', 'terminal_mosh_session_start', '{"profile_id":""}')], true);
  const pending = partsOf(s.store.load())[0];
  assert.equal(pending.metadata?.terminal_protocol, 'remote_mosh');
  assert.equal(pending.metadata?.terminal_target_label, 'fixture@host-p:22');
  assert.deepEqual(pending.metadata?.terminal_target, s.moshRuntime.captureTarget(null));
  const out = await s.resume();
  assert.deepEqual(s.executed(), ['terminal_mosh_session_start']);
  assert.equal(partsOf(out)[0].metadata?.terminal_protocol, 'remote_mosh');
});

test('new SSH protocol is explicit; historical SSH pending without protocol still resumes only SSH', async () => {
  const s = await approvalFixture([toolPart('legacy-ssh-protocol', 'terminal_execute')], true);
  const legacy = s.store.load();
  assert.equal(partsOf(legacy)[0].metadata?.terminal_protocol, 'remote_ssh');
  delete partsOf(legacy)[0].metadata!['terminal_protocol'];
  await s.store.save(legacy);
  await s.resume();
  assert.deepEqual(s.executed(), ['terminal_execute']);
});

test('Mosh approval missing persisted protocol is refused despite an otherwise valid target', async () => {
  const s = await approvalFixture([toolPart('legacy-mosh-protocol', 'terminal_mosh_session_start')], true);
  const legacy = s.store.load();
  partsOf(legacy)[0].metadata!['terminal_target'] = { ...s.moshRuntime.captureTarget(null) };
  delete partsOf(legacy)[0].metadata!['terminal_protocol'];
  await s.store.save(legacy);
  assertTargetFailure(await s.resume(), 'legacy-mosh-protocol');
  assert.deepEqual(s.executed(), []);
});

for (const [name, protocol] of [
  ['terminal_mosh_session_start', 'remote_ssh'], ['terminal_session_start', 'remote_mosh'],
] as const) {
  test(`${name} refuses persisted approval for ${protocol}`, async () => {
    const s = await approvalFixture([toolPart('cross-protocol', name)], true);
    const changed = s.store.load();
    partsOf(changed)[0].metadata!['terminal_protocol'] = protocol;
    await s.store.save(changed);
    assertTargetFailure(await s.resume(), 'cross-protocol');
    assert.deepEqual(s.executed(), []);
  });
}

test('Mosh default changes reject execution and retain the approved protocol and target label', async () => {
  const s = await approvalFixture([toolPart('mosh-default', 'terminal_mosh_session_start')], true);
  await s.profiles.setDefault('q');
  const out = await s.resume();
  assertTargetFailure(out, 'mosh-default');
  assert.equal(partsOf(out)[0].metadata?.terminal_protocol, 'remote_mosh');
  assert.equal(partsOf(out)[0].metadata?.terminal_target_label, 'fixture@host-p:22');
  assert.deepEqual(s.executed(), []);
});

test('Mosh explicit host change rejects approval continuation', async () => {
  const s = await approvalFixture([toolPart('mosh-host', 'terminal_mosh_session_start', '{"profile_id":"p"}')], true);
  const profile = s.profiles.get('p')!;
  await s.profiles.saveMetadata({ ...profile, host: 'changed-mosh-host' }, profile.revision);
  assertTargetFailure(await s.resume(), 'mosh-host');
  assert.deepEqual(s.executed(), []);
});

test('mixed SSH and Mosh batch routes session identity to the Mosh runtime and preserves both protocols', async () => {
  const s = await approvalFixture([toolPart('ssh-batch', 'terminal_execute', '{"profile_id":"p"}'),
    toolPart('mosh-batch', 'terminal_mosh_session_exec', '{"session_id":"mosh-p"}')], true);
  const pending = partsOf(s.store.load());
  assert.deepEqual(pending.map((part) => part.metadata?.terminal_protocol), ['remote_ssh', 'remote_mosh']);
  assert.equal(s.moshRuntime.sessionReads, 1);
  await s.profiles.setDefault('q');
  const out = await s.resume();
  assert.equal(s.moshRuntime.sessionReads, 2);
  assert.deepEqual(s.executed(), ['terminal_execute', 'terminal_mosh_session_exec']);
  for (const part of partsOf(out)) {
    assert.equal(part.metadata?.batch_marker, part.toolCallId);
    assert.equal(part.metadata?.terminal_target_label, 'fixture@host-p:22');
    assert.ok(part.metadata?.permission_trace);
  }
});

test('Mosh session profile edit rejects resumed command send', async () => {
  const s = await approvalFixture([toolPart('mosh-session-edit', 'terminal_mosh_session_exec', '{"session_id":"mosh-p"}')], true);
  const profile = s.profiles.get('p')!;
  await s.profiles.saveMetadata({ ...profile, host: 'changed-mosh-host' }, profile.revision);
  assertTargetFailure(await s.resume(), 'mosh-session-edit');
  assert.deepEqual(s.executed(), []);
});

test('Mosh start without the optional Mosh runtime never falls back to SSH execution', async () => {
  const s = await approvalFixture([toolPart('no-mosh-runtime', 'terminal_mosh_session_start')]);
  const pending = partsOf(s.store.load())[0];
  assert.equal(pending.metadata?.terminal_protocol, 'remote_mosh');
  assert.equal(pending.metadata?.terminal_target, null);
  assertTargetFailure(await s.resume(), 'no-mosh-runtime');
  assert.deepEqual(s.executed(), []);
});

test('Mosh read and stop keep ordinary metadata and do not rebind after default changes', async () => {
  const names = ['terminal_mosh_session_read', 'terminal_mosh_session_stop'];
  const s = await approvalFixture(names.map((name) => toolPart(name, name)), true);
  for (const part of partsOf(s.store.load())) {
    assert.equal(part.metadata?.terminal_target, undefined);
    assert.equal(part.metadata?.terminal_protocol, undefined);
  }
  await s.profiles.setDefault('q');
  await s.resume();
  assert.deepEqual(s.executed(), names);
});

for (const changeDefault of [true, false]) {
  test(`real Mosh tools persist approval and ${changeDefault ? 'reject changed default before SSH bootstrap' : 'register the exact source tool after unchanged approval'}`, async (t) => {
    const credentials = new FixtureCredentials();
    const profiles = await SSHProfileStore.create({ kv: createMemoryKeyValueStore(), credentials });
    for (const id of ['p', 'q']) {
      await profiles.commitVerified({ id, name: id, host: `host-${id}`, port: 22,
        username: 'fixture', authMethod: 'password' }, null, `SHA256:${id}`, { secret: 'fixture-only', passphrase: null });
    }
    await profiles.setDefault('p');
    const files = new Map<string, string>();
    const taskStore = await AgentTaskStore.create({ taskDir: '/files/tasks', appFilesDir: '/files', files: {
      async mkdirs() {}, async listJsonFileNames() { return []; },
      async readText(path: string) { return files.get(path) ?? null; },
      async writeText(path: string, value: string) { files.set(path, value); },
      async delete(path: string) { files.delete(path); }, async exists(path: string) { return files.has(path); },
      async isPathInside(root: string, path: string) { return path.startsWith(root + '/'); },
    } });
    let bootstraps: number = 0;
    let udpStarts: number = 0;
    const exited = () => ({ chunks: [], state: 'exited' as const, exitCode: 0, errorCode: null, errorMessage: null });
    const sshTransport: SSHTransportPort = {
      async probe() { return { fingerprintSHA256: 'SHA256:p', hostKeyType: 'ed25519' }; },
      async startExec(_id, connection) {
        bootstraps++;
        assert.equal(connection.host, 'host-p');
        return { id: 'bootstrap', kind: 'exec', peerAddress: '192.0.2.1' };
      },
      async startPty() { throw new Error('Mosh approval must not dispatch an SSH PTY'); },
      async read() { return { ...exited(), chunks: [{ isStderr: false,
        bytes: new TextEncoder().encode('MOSH CONNECT 60001 abcdefghijklmnopqrstuw\n') }] }; },
      async close() { return exited(); }, async write() {}, async resize() {},
    };
    const transport: MoshTransportPort = {
      async start() { udpStarts++; return { id: 'mosh-udp', kind: 'mosh' }; },
      async read() { return { bytes: new Uint8Array(0), state: 'running', errorCode: null, lastHeardMs: 0 }; },
      async write() {}, async resize() {},
      async close(_handle, reason) { return { bytes: new Uint8Array(0),
        state: reason === 'release' ? 'closed' : reason, errorCode: null, lastHeardMs: 0 }; },
    };
    const moshRuntime = new MoshRuntime({ targetResolver: new SSHTargetResolver({ profiles, credentials }),
      sshTransport, transport, taskStore });
    t.after(async () => { await moshRuntime.interruptForBackground(); });
    const store = new PersistedStore();
    const provider = providerFor([toolPart('real-mosh-start', 'terminal_mosh_session_start', '{"profile_id":""}')]);
    const activityStore = new AgentToolActivityStore();
    const build = (): ToolLoopOptions => {
      const guard = createTerminalApprovalGuard(new ApprovalRuntime(profiles), profiles, moshRuntime);
      const tools = createToolRegistry(createMoshTools({ runtime: moshRuntime, profiles,
        activityStore, conversationId: 'real-mosh-approval' })).tools();
      return { ...loopFor(provider, tools, guard.captureInvocationMetadata),
        dispatcher: new AgentToolDispatcher({ hooks: [...defaultToolInvocationHooks(), guard.hook] }) };
    };
    await runChatTurnWithTools(makeConversation('real-mosh-approval', []), 'run', depsFor(provider, store), build());
    const pending = partsOf(store.load())[0];
    assert.equal(pending.approvalState.type, 'pending');
    assert.equal(bootstraps, 0);
    assert.equal(udpStarts, 0);
    assert.deepEqual(taskStore.list(), []);
    if (changeDefault) await profiles.setDefault('q');
    await store.save(approve(store.load()));
    const out = await runToolLoopContinuation(store.load(), depsFor(provider, store), build());
    const part = partsOf(out)[0];
    assert.deepEqual(part.metadata?.terminal_target, pending.metadata?.terminal_target);
    assert.equal(part.metadata?.terminal_target_label, 'fixture@host-p:22');
    assert.equal(part.metadata?.terminal_protocol, 'remote_mosh');
    if (changeDefault) {
      assertTargetFailure(out, 'real-mosh-start');
      assert.equal(bootstraps, 0);
      assert.equal(udpStarts, 0);
      assert.deepEqual(taskStore.list(), []);
      assert.equal(activityStore.sandboxActivity, null);
    } else {
      assert.equal(bootstraps, 1);
      assert.equal(udpStarts, 1);
      const result = JSON.parse((part.output[0] as { text: string }).text);
      assert.equal(result.status, 'running');
      assert.equal(result.runtime, 'remote_mosh');
      assert.equal(result.profile_id, 'p');
      assert.equal(taskStore.list()[0].sourceToolName, 'terminal_mosh_session_start');
      assert.equal(taskStore.list()[0].sourceConversationId, 'real-mosh-approval');
      assert.equal(taskStore.list()[0].runtime, 'remote_mosh');
      await moshRuntime.stopSession(result.session_id);
    }
  });
}

test('auto review persists real terminal target and existing approval guard still rejects changed host on resume', async () => {
  let reviews = 0;
  const s = await approvalFixture([toolPart('reviewed-terminal', 'terminal_job_start', '{"profile_id":"p"}')], false,
    async () => { reviews++; return ['外发数据']; });
  const pending = partsOf(s.store.load())[0];
  assert.equal(pending.approvalState.type, 'pending'); assert.equal(reviews, 1); assert.deepEqual(s.executed(), []);
  const original = pending.metadata?.terminal_target;
  assert.match(JSON.stringify(pending.metadata?.permission_trace), /jev_auto_approval.*外发数据/);
  const profile = s.profiles.get('p')!;
  await s.profiles.saveMetadata({ ...profile, host: 'changed-host' }, profile.revision);
  const out = await s.resume();
  assertTargetFailure(out, 'reviewed-terminal'); assert.deepEqual(s.executed(), []);
  assert.deepEqual(partsOf(out)[0].metadata?.terminal_target, original); assert.equal(reviews, 1);
});
