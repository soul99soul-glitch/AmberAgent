import { jevAutoApprovalReason } from '../main/ets/chat/jev_auto_approval.ts';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { makeAgentTool } from '../main/ets/chat/tool.ts';
import type { AgentTool } from '../main/ets/chat/tool.ts';
import type { UIMessagePartTool } from '../main/ets/chat/message.ts';
import type { JsonObject } from '../main/ets/chat/json.ts';
import { AgentToolDispatcher } from '../main/ets/chat/tool_dispatcher.ts';
import { PermissionDecisionResolver } from '../main/ets/chat/tool_permission.ts';
import { makeConversation, toMessageNode } from '../main/ets/chat/conversation.ts';
import { makeUIMessage } from '../main/ets/chat/message.ts';
import { TerminalController } from '../main/ets/chat/terminal/control.ts';
import type { AbortSignalLike } from '@amber/deepread-domain';
import type { PluginDescriptor, PluginHealth, PluginImplementation, PluginJsEvent, PluginPackage, PluginRunCheckpoint } from '../main/ets/chat/plugins/models.ts';
import { PluginHttpError } from '../main/ets/chat/plugins/models.ts';
import type { PluginExecutionPort, PluginJsPort, PluginStore } from '../main/ets/chat/plugins/ports.ts';
import { checkPluginHostCall, pluginNetworkURLAllowed } from '../main/ets/chat/plugins/broker.ts';
import { createPluginLoopAdapter, createPluginRun, recoverInterruptedPlugins } from '../main/ets/chat/plugins/runner.ts';
import { createPluginTools } from '../main/ets/chat/plugins/tools.ts';

const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T;
const health = (): PluginHealth => ({ pluginId: 'sample', packageHash: 'hash', consecutiveFailures: 0, quarantinedAt: null, quarantineReason: null, diagnostics: [] });
const descriptor = (implementation: PluginImplementation): PluginDescriptor => ({
  pluginId: 'sample', toolId: 'plugin__sample__run', name: 'run', description: 'Fixture', version: '1', packageHash: 'hash',
  capabilities: { workspaceReadPrefixes: ['data'], workspaceWritePrefixes: ['data'], networkDomains: ['example.com'], webMountActions: [], localRuntimes: ['embedded_python'] },
  backgroundAllowed: false, inputSchema: { type: 'object', properties: { value: { type: 'string' } }, required: ['value'], additionalProperties: false },
  outputSchema: null, output: 'json', timeoutMs: 1000, maxOutputChars: 10000, implementation,
  primitiveTools: implementation.kind === 'javascript' ? implementation.hostTools : [],
  envelope: { tools: [], mutates: true, needsApproval: true, risk: 'high' },
});
const parent = (name = 'plugin__sample__run', input = '{"value":"你好"}'): UIMessagePartTool => ({ type: 'tool', toolName: name,
  toolCallId: 'parent', input, output: [], approvalState: { type: 'auto' }, metadata: { plugin_manual: true } });
const checkpoint = (p: UIMessagePartTool): PluginRunCheckpoint => p.metadata!['plugin_v1'] as unknown as PluginRunCheckpoint;
const result = (p: UIMessagePartTool): JsonObject => JSON.parse((p.output[0] as { text: string }).text) as JsonObject;
const fixture = (d: PluginDescriptor) => {
  const trust = { tier: 'local_unsigned' as const, publisherTrusted: false, keyId: null, fingerprint: null, signature: null };
  const pkg = { manifest: { id: 'sample', name: 'Sample', version: '1', description: 'Fixture', tools: [], capabilities: d.capabilities,
    backgroundAllowed: false, schema: 'amber.plugin.v1' }, hash: 'hash', files: [], fileHashes: {}, tools: [d], envelope: d.envelope } as PluginPackage;
  const installed = [{ id: 'sample', currentHash: 'hash', package: pkg, configuredEnabled: true, enabled: true, trust, health: health(), errorCode: null, errorMessage: null }];
  let failures = 0, successes = 0, applies = 0;
  const store: PluginStore = { listInstalled: async () => installed, readPackage: async () => ({ candidate: pkg, trust }),
    prepareImport: async (source, _p, enable) => ({ source, candidate: pkg, trust, baseHash: null, permissionExpanded: false, permissionDiff: [], enable }),
    applyImport: async () => { applies++; return { id: 'sample', hash: 'hash', changed: true, enabled: true, permissionExpanded: false, trust }; },
    setEnabled: async () => {}, remove: async () => {}, rollback: async () => { throw new Error('unused'); }, restore: async () => {},
    exportArchive: async () => new Uint8Array(), recordSuccess: async () => { successes++; return health(); },
    recordFailure: async () => { failures++; return { ...health(), consecutiveFailures: failures, quarantinedAt: failures >= 3 ? 1 : null }; },
    listTrustedKeys: async () => [], addTrustedKey: async () => { throw new Error('unused'); }, removeTrustedKey: async () => {},
  };
  const writes: JsonObject[] = [], saved: UIMessagePartTool[] = [], defs: AgentTool[] = [makeAgentTool({ name: 'file_write', description: 'write', needsApproval: true, allowsAutoApproval: false,
    execute: async (input) => { assert.equal(checkpoint(saved.at(-1)!).phase, 'started'); writes.push(input as JsonObject);
      return [{ type: 'text', text: JSON.stringify({ path: (input as JsonObject)['path'] }), metadata: null }]; } })];
  const dispatcher = new AgentToolDispatcher({ resolver: new PermissionDecisionResolver(), hooks: [] });
  const port: PluginExecutionPort = { primitive: (name) => defs.find((t) => t.name === name) ?? null,
    capture: () => ({ frozen: 'original' }), decide: (p, def) => dispatcher.resolveDecision(def, p, false, false, []),
    dispatch: (p, def, signal) => dispatcher.execute(p, def, false, false, [], 'normal', undefined, signal),
    saveParent: async (p) => { saved.push(clone(p)); } };
  let starts = 0, replies = 0, alive = false, id = '';
  const js: PluginJsPort = { start: async (r) => { starts++; alive = true; id = r.executionId;
    return { type: 'host_call', sessionId: id, callId: '1', toolName: 'file_write', argsJson: '{"path":"data/one","content":"一"}' }; },
    reply: async () => { replies++; if (replies === 1) return { type: 'host_call', sessionId: id, callId: '2', toolName: 'file_write', argsJson: '{"path":"data/two","content":"二"}' };
      alive = false; return { type: 'finished', sessionId: id, resultJson: 'null', logs: [] }; },
    reject: async () => { alive = false; return { type: 'failed', sessionId: id, errorCode: 'host_call_rejected', message: 'Rejected', logs: [], abandoned: false }; },
    cancel: () => { alive = false; }, hasSession: () => alive };
  const deps = { store, installed, js, http: { execute: async (_request: import('../main/ets/chat/plugins/models.ts').PluginHttpRequest, _signal?: AbortSignalLike) => ({ status: 200, body: '{"value":"你好"}' }) },
    webMount: { withScope: async <T>(_domains: string[], operation: () => Promise<T>): Promise<T> => operation() }, resolveMcpPrimitive: () => null };
  return { deps, adapter: createPluginLoopAdapter(deps), port, defs, writes, saved, js, counts: () => ({ starts, replies, failures, successes, applies }) };
};

test('JS retains stack across two individual approvals, exact child, started save and explicit null candidate comparison', async () => {
  const f = fixture(descriptor({ kind: 'javascript', source: 'tools.file_write(input);return null;', hostTools: ['file_write'] }));
  let p = await f.adapter.prepare(parent('plugin_test', '{"workspace_directory":"pkg","expected_candidate_hash":"hash","tool":"run","inputs":{"value":"你好"},"expected_result":null}'), f.defs);
  p = await f.adapter.advance(p, f.port);
  assert.equal(p.approvalState.type, 'pending'); assert.deepEqual(p.output, []); assert.equal(f.writes.length, 0);
  const first = checkpoint(p).pendingStep!;
  p = await f.adapter.advance({ ...p, approvalState: { type: 'approved' } }, f.port);
  assert.equal(p.approvalState.type, 'pending'); assert.deepEqual(p.output, []); assert.equal(f.writes.length, 1);
  assert.notEqual(checkpoint(p).pendingStep!.toolCallId, first.toolCallId);
  assert.equal(checkpoint(p).pendingStep!.metadata!['frozen'], 'original');
  p = await f.adapter.advance({ ...p, approvalState: { type: 'approved' } }, f.port);
  assert.equal(result(p)['status'], 'succeeded'); assert.equal(result(p)['result'], null); assert.equal(result(p)['expected_match'], true);
  assert.equal(result(p)['registered'], false); assert.deepEqual(f.counts(), { starts: 1, replies: 2, failures: 0, successes: 0, applies: 0 });
  await f.adapter.advance(p, f.port); assert.equal(f.writes.length, 2); assert.equal(f.counts().starts, 1);
  const ask = fixture(descriptor({ kind: 'javascript', source: 'return tools.ask_user(input);', hostTools: ['ask_user'] }));
  ask.defs.push(makeAgentTool({ name: 'ask_user', description: 'Answer', needsApproval: true,
    execute: async () => { throw new Error('Answer must be provided by dispatcher'); } }));
  ask.js.start = async (r) => ({ type: 'host_call', sessionId: r.executionId, callId: 'ask', toolName: 'ask_user', argsJson: '{"question":"Continue?"}' });
  ask.js.hasSession = () => true;
  ask.js.reply = async (id, _call, answer) => ({ type: 'finished', sessionId: id, resultJson: answer, logs: [] });
  let question = await ask.adapter.advance(await ask.adapter.prepare(parent(), ask.defs), ask.port);
  question = await ask.adapter.advance({ ...question, approvalState: { type: 'approved' } }, ask.port);
  assert.equal(question.approvalState.type, 'pending');
  question = await ask.adapter.advance({ ...question, approvalState: { type: 'answered', answer: 'Yes' } }, ask.port);
  assert.equal(result(question)['result'], 'Yes');
  const resumed = fixture(descriptor({ kind: 'javascript', source: 'tools.file_write(input);while(true){}', hostTools: ['file_write'] }));
  const pending = await resumed.adapter.advance(await resumed.adapter.prepare(parent(), resumed.defs), resumed.port);
  let entered: () => void = () => {}; const replyEntered = new Promise<void>((resolve) => { entered = resolve; });
  let complete: (event: PluginJsEvent) => void = () => {};
  resumed.js.reply = async () => new Promise<PluginJsEvent>((resolve) => { complete = resolve; entered(); });
  const originalCancel = resumed.js.cancel;
  resumed.js.cancel = (id) => { originalCancel(id); complete({ type: 'failed', sessionId: id, errorCode: 'cancelled', message: 'Cancelled', logs: [], abandoned: true }); };
  const current = new TerminalController();
  const continuation = resumed.adapter.advance({ ...pending, approvalState: { type: 'approved' } }, resumed.port, current.signal);
  await replyEntered; current.abort();
  let timer: ReturnType<typeof setTimeout> | undefined;
  const promptly = await Promise.race([continuation.then(() => true), new Promise<boolean>((resolve) => { timer = setTimeout(() => resolve(false), 50); })]);
  if (!promptly) resumed.js.cancel(checkpoint(pending).executionId);
  const stopped = await continuation; clearTimeout(timer);
  assert.equal(promptly, true); assert.equal(result(stopped)['status'], 'cancelled'); assert.equal(resumed.counts().starts, 1);
});

test('broker and current scope deny without effect; cold started/VM-lost never re-evaluate', async () => {
  const nodeURL = globalThis.URL;
  try {
    globalThis.URL = undefined as unknown as typeof URL;
    assert.equal(pluginNetworkURLAllowed('https://Api.Example.com.:8443/中文?q=你好', ['example.com']), true);
    for (const url of ['https://user@example.com/path', 'https://example.com:0/', 'https://example.com:65536/',
      'https://example%2ecom/', 'https://example.com\\evil/', 'https://example.com\n/']) {
      assert.equal(pluginNetworkURLAllowed(url, ['example.com']), false);
    }
  } finally { globalThis.URL = nodeURL; }
  const d = descriptor({ kind: 'javascript', source: 'return input;', hostTools: ['file_write'] });
  assert.throws(() => checkPluginHostCall(d, 'file_write', { path: 'database/escape', content: 'x' }));
  assert.throws(() => checkPluginHostCall(d, 'terminal_execute', { command: 'echo bypass' }));
  assert.throws(() => checkPluginHostCall(d, 'wm_eval', { code: 'fetch("https://evil.test")' }));
  const f = fixture(d); let p = await f.adapter.advance(await f.adapter.prepare(parent(), f.defs), f.port);
  f.js.cancel(checkpoint(p).executionId);
  p = await f.adapter.advance({ ...p, approvalState: { type: 'approved' } }, f.port);
  assert.equal(result(p)['status'], 'outcome_unknown'); assert.equal(f.writes.length, 0); assert.equal(f.counts().starts, 1);
  const g = fixture(d); const run = createPluginRun(d, { value: 'x' }); run.phase = 'started';
  run.pendingStep = { ...parent('file_write', '{"path":"data/a","content":"x"}'), toolCallId: 'started-child', metadata: null };
  const unknown = await g.adapter.advance({ ...parent(), metadata: { plugin_v1: run as unknown as JsonObject } }, g.port);
  assert.equal(result(unknown)['status'], 'outcome_unknown'); assert.equal(g.counts().starts, 0);
  const conversation = { ...makeConversation('assistant'), messageNodes: [toMessageNode(makeUIMessage('assistant', [{ ...parent(), metadata: { plugin_v1: run as unknown as JsonObject } }]))] };
  const recovered = recoverInterruptedPlugins(conversation, () => false);
  assert.equal((recovered.messageNodes[0]!.messages[0]!.parts[0] as UIMessagePartTool).output.length, 1);
  assert.equal(recoverInterruptedPlugins(recovered, () => false), recovered);
  const ready = { ...run, phase: 'ready' as const, pendingStep: null };
  const untouched = { ...makeConversation('assistant'), messageNodes: [toMessageNode(makeUIMessage('assistant', [{ ...parent(), metadata: { plugin_v1: ready as unknown as JsonObject } }]))] };
  assert.equal(recoverInterruptedPlugins(untouched, () => false), untouched);
  const pending = fixture(d);
  pending.port.saveParent = async (part) => { if (checkpoint(part).phase === 'awaiting_approval') throw new Error('pending save failed'); pending.saved.push(clone(part)); };
  await assert.rejects(pending.adapter.advance(await pending.adapter.prepare(parent(), pending.defs), pending.port), /pending save failed/);
  assert.equal(pending.js.hasSession(''), false); assert.equal(pending.writes.length, 0);
  const started = fixture(d); const approved = await started.adapter.advance(await started.adapter.prepare(parent(), started.defs), started.port);
  started.port.saveParent = async (part) => { if (checkpoint(part).phase === 'started') throw new Error('started save failed'); };
  await assert.rejects(started.adapter.advance({ ...approved, approvalState: { type: 'approved' } }, started.port), /started save failed/);
  assert.equal(started.js.hasSession(''), false); assert.equal(started.writes.length, 0);
  const denied = fixture(d), waiting = await denied.adapter.advance(await denied.adapter.prepare(parent(), denied.defs), denied.port);
  assert.equal(result(await denied.adapter.advance({ ...waiting, approvalState: { type: 'denied', reason: 'No' } }, denied.port))['error_code'], 'denied');
  assert.equal(denied.writes.length, 0); assert.equal(denied.counts().failures, 0); assert.equal(denied.js.hasSession(''), false);
  const cancelled = fixture(d), controller = new TerminalController(); controller.abort();
  const stopped = await cancelled.adapter.advance(await cancelled.adapter.prepare(parent(), cancelled.defs), cancelled.port, controller.signal);
  assert.equal(result(stopped)['status'], 'cancelled'); assert.equal(cancelled.counts().starts, 0); assert.equal(cancelled.writes.length, 0);
});

test('private Recipe reuses declared step and Python keeps fixed source/data apart; real failures quarantine, tests do not', async () => {
  const recipe = descriptor({ kind: 'recipe', manifest: { schema: 'amber.recipe.v1', name: 'private_copy', version: '1', description: 'Private', inputs: { value: 'string' },
    steps: [{ id: 'save', tool: 'file_write', arguments: { path: 'data/result', content: '${input.value}' } }], outputs: { path: '${step.save.output.path}' } } });
  recipe.primitiveTools = ['file_write']; const f = fixture(recipe);
  let p = await f.adapter.advance(await f.adapter.prepare(parent(), f.defs), f.port);
  p = await f.adapter.advance({ ...p, approvalState: { type: 'approved' } }, f.port);
  assert.deepEqual(result(p)['result'], { path: 'data/result' }); assert.equal(f.writes.length, 1);
  const command = descriptor({ kind: 'command', runtime: 'embedded_python', entry: 'scripts/run.py', source: 'FIXED_SOURCE', stdinInput: null });
  const g = fixture(command); let pythonCalls = 0;
  g.defs.push(makeAgentTool({ name: 'python_execute', description: 'Python', needsApproval: true, allowsAutoApproval: false,
    execute: async (input) => { pythonCalls++; const args = input as JsonObject; assert.equal(args['code'], 'FIXED_SOURCE'); assert.equal(args['stdin'], '{"value":"你好"}');
      return [{ type: 'text', text: JSON.stringify({ status: 'completed', exit_code: 0, stdout: JSON.stringify({ value: '你好' }) }), metadata: null }]; } }));
  p = await g.adapter.advance(await g.adapter.prepare(parent(), g.defs), g.port);
  const changedSource = clone(p);
  checkpoint(changedSource).pendingStep!.input = '{"code":"CHANGED_SOURCE","stdin":"x","timeout_ms":1000}';
  const rejectedSource = await g.adapter.advance({ ...changedSource, approvalState: { type: 'approved' } }, g.port);
  assert.equal(result(rejectedSource)['error_code'], 'invalid_checkpoint'); assert.equal(pythonCalls, 0);
  p = await g.adapter.advance({ ...p, approvalState: { type: 'approved' } }, g.port);
  assert.deepEqual(result(p)['result'], { value: '你好' });
  const h = fixture(command); h.defs.push(makeAgentTool({ name: 'python_execute', description: 'Python', execute: async () =>
    [{ type: 'text', text: '{"status":"failed","exit_code":1,"stdout":""}', metadata: null }] }));
  for (let i = 0; i < 3; i++) {
    let failure = await h.adapter.advance(await h.adapter.prepare(parent(), h.defs), h.port);
    if (failure.approvalState.type === 'pending') failure = await h.adapter.advance({ ...failure, approvalState: { type: 'approved' } }, h.port);
    assert.equal(result(failure)['status'], 'failed');
  }
  assert.equal(h.counts().failures, 3);
});

test('fixed MCP resolves only current target, OpenAPI uses current http_request definition and narrow port', async () => {
  const mcp = descriptor({ kind: 'remote', remote: { kind: 'mcp', server: 'allowed-server', tool: 'normalize' } });
  const f = fixture(mcp);
  const unavailable = await f.adapter.advance(await f.adapter.prepare(parent(), f.defs), f.port);
  assert.equal(result(unavailable)['error_code'], 'missing_tool'); assert.equal(f.counts().failures, 0);
  const remote = descriptor({ kind: 'remote', remote: { kind: 'openapi', url: 'https://example.com/normalize', method: 'POST' } });
  remote.timeoutMs = 25;
  remote.primitiveTools = ['http_request']; const g = fixture(remote);
  g.defs.push(makeAgentTool({ name: 'http_request', description: 'current scoped HTTP', needsApproval: true, allowsAutoApproval: false,
    execute: async () => { throw new Error('must use narrow PluginHttpPort'); } }));
  let requests = 0;
  g.deps.http.execute = async (request) => { requests++; assert.equal(request.url, 'https://example.com/normalize'); assert.equal(request.method, 'POST');
    assert.deepEqual(request.input, { value: '你好' }); return { status: 200, body: '{"value":"你好"}' }; };
  let p = await g.adapter.advance(await g.adapter.prepare(parent(), g.defs), g.port);
  assert.equal(p.approvalState.type, 'pending');
  const changedMethod = clone(p), changedChild = checkpoint(changedMethod).pendingStep!;
  changedChild.input = JSON.stringify({ ...(JSON.parse(changedChild.input) as JsonObject), method: 'GET' });
  const rejectedMethod = await g.adapter.advance({ ...changedMethod, approvalState: { type: 'approved' } }, g.port);
  assert.equal(result(rejectedMethod)['error_code'], 'invalid_checkpoint'); assert.equal(requests, 0);
  p = await g.adapter.advance({ ...p, approvalState: { type: 'approved' } }, g.port);
  assert.deepEqual(result(p)['result'], { value: '你好' });
  const abort = new TerminalController();
  g.deps.http.execute = async () => { abort.abort(); throw new PluginHttpError('cancelled', 'POST completion unknown.', true); };
  p = await g.adapter.advance(await g.adapter.prepare(parent(), g.defs), g.port);
  const cancelled = await g.adapter.advance({ ...p, approvalState: { type: 'approved' } }, g.port, abort.signal);
  assert.equal(result(cancelled)['status'], 'outcome_unknown'); assert.equal(g.counts().failures, 0);
  g.deps.http.execute = async (_request, signal) => new Promise((_resolve, reject) => {
    signal!.addEventListener!('abort', () => reject(new PluginHttpError('timed_out', 'POST completion unknown.', true)));
  });
  p = await g.adapter.advance(await g.adapter.prepare(parent(), g.defs), g.port);
  const timedOut = await g.adapter.advance({ ...p, approvalState: { type: 'approved' } }, g.port);
  assert.equal(result(timedOut)['status'], 'outcome_unknown'); assert.equal(g.counts().failures, 0);
});

test('import is pinned and always explicitly approved; wrapper execute cannot bypass', async () => {
  const f = fixture(descriptor({ kind: 'javascript', source: 'return input;', hostTools: [] }));
  const tools = createPluginTools({ store: f.deps.store, installed: f.deps.installed, primitives: f.defs });
  f.defs.push(...tools);
  const dispatcher = new AgentToolDispatcher({ resolver: new PermissionDecisionResolver(), hooks: [] });
  f.port.decide = (p, tool) => dispatcher.resolveDecision(tool, p, true, true, []);
  await assert.rejects(f.adapter.prepare(parent('plugin_import', '{"workspace_directory":"pkg","expected_candidate_hash":"hash","expected_base_hash":"old"}'), f.defs),
    (error: { code: string }) => error.code === 'stale_base');
  assert.equal(f.counts().applies, 0);
  let p = await f.adapter.prepare(parent('plugin_import', '{"workspace_directory":"pkg","expected_candidate_hash":"hash","expected_base_hash":null,"enable":true}'), f.defs);
  p = await f.adapter.advance(p, f.port); assert.equal(p.approvalState.type, 'pending'); assert.equal(f.counts().applies, 0);
  p = await f.adapter.advance({ ...p, approvalState: { type: 'approved' } }, f.port);
  assert.equal(result(p)['status'], 'succeeded'); assert.equal(f.counts().applies, 1);
  const guarded = await tools.find((t) => t.name === 'plugin__sample__run')!.execute({ value: 'x' });
  assert.equal(JSON.parse((guarded[0] as { text: string }).text).error_code, 'plugin_adapter_required');
});

test('auto approval review pauses the actual JS host primitive, preserves checkpoint target and manual continuation', async () => {
  const f = fixture(descriptor({ kind: 'javascript', source: 'return tools.file_write(input);', hostTools: ['file_write'] }));
  let evaluations = 0;
  const dispatcher = new AgentToolDispatcher({ autoApprovalReview: async () => { evaluations++; return ['外发数据']; } });
  f.port.capture = () => ({ terminal_target: { profileId: 'pinned' } });
  f.port.decide = (p, def, signal) => dispatcher.resolveReviewedDecision(def, p, true, true, [], 'normal', signal);
  f.port.dispatch = (p, def, signal) => dispatcher.execute(p, def, true, true, [], 'normal', undefined, signal);
  f.js.reply = async (id) => ({ type: 'finished', sessionId: id, resultJson: 'null', logs: [] });
  let p = await f.adapter.advance(await f.adapter.prepare(parent(), f.defs), f.port);
  assert.equal(p.approvalState.type, 'pending'); assert.equal(checkpoint(p).phase, 'awaiting_approval');
  assert.equal(jevAutoApprovalReason(p), '自动批准复核：外发数据');
  assert.equal(f.writes.length, 0); assert.equal(evaluations, 1);
  const step = checkpoint(p).pendingStep!;
  assert.deepEqual(step.metadata!['terminal_target'], { profileId: 'pinned' });
  assert.match(JSON.stringify(step.metadata!['permission_trace']), /jev_auto_approval.*外发数据/);
  assert.equal(checkpoint(f.saved.at(-1)!).phase, 'awaiting_approval');
  p = await f.adapter.advance({ ...p, approvalState: { type: 'approved' } }, f.port);
  assert.equal(result(p)['status'], 'succeeded'); assert.equal(f.writes.length, 1); assert.equal(evaluations, 1);
});
