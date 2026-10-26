// Exercise the Entry factory and real domain tool loop; only platform ports and model output are fixtures.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const loadExecutor = host => {
  const filename = path.resolve(__dirname, '../../../entry/src/main/ets/di/CronRuntime.ets');
  const file = ts.createSourceFile(filename, fs.readFileSync(filename, 'utf8'),
    ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
  const declarations = [];
  const visit = statement => {
    if (ts.isVariableStatement(statement) && statement.declarationList.declarations.some(declaration =>
      declaration.name.getText(file) === 'createEntryCronRunExecutor')) declarations.push(statement);
    ts.forEachChild(statement, visit);
  };
  visit(file);
  host.ports = host;
  assert.equal(declarations.length, 1, 'Load the production Entry Cron executor');
  const source = `${declarations[0].getText(file)}\nexports.create = createEntryCronRunExecutor;`;
  vm.runInNewContext(ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, host);
  return host.exports.create();
};

const chunk = parts => ({
  id: 'response', model: 'fixture', usage: null,
  choices: [{ index: 0, finishReason: 'stop', message: null,
    delta: { id: 'delta', role: 'assistant', parts, annotations: [],
      createdAt: '2026-10-18T00:00:00Z', finishedAt: null, modelId: null,
      usage: null, translation: null } }],
});
const text = value => chunk([{ type: 'text', text: value, metadata: null }]);
const toolCall = name => chunk([{ type: 'tool', toolCallId: `cron-${name}`, toolName: name,
  input: '{}', output: [], approvalState: { type: 'auto' }, metadata: null }]);

const harness = async ({ script = [text('completed')], existing = false, onStream } = {}) => {
  const domain = await import('../main/ets/index.ts');
  const events = [], requests = [], saved = [], toolSignals = [];
  const task = { id: 'task', title: 'Cron fixture', prompt: '检查任务',
    conversationId: existing ? 'existing' : '' };
  const repository = domain.createMemoryConversationRepository();
  if (existing) await repository.save(domain.makeConversation('existing', [], {
    assistantId: 'assistant', autoApproveToolCalls: true,
  }));
  const store = { ...repository, save: async conversation => {
    saved.push(structuredClone(conversation));
    await repository.save(conversation);
  } };
  const writeTool = domain.makeAgentTool({
    name: 'file_write', description: 'Approval-required workspace mutation', needsApproval: true,
    execute: async (_input, signal) => {
      toolSignals.push(signal);
      return [{ type: 'text', text: 'written', metadata: null }];
    },
  });
  const readTool = domain.makeAgentTool({
    name: 'file_read', description: 'Read-only workspace operation',
    execute: async (_input, signal) => {
      toolSignals.push(signal);
      return [{ type: 'text', text: 'read result', metadata: null }];
    },
  });
  const assistant = domain.makeAssistant({ localTools: ['workspace_files'],
    mcpServers: [], streamOutput: true });
  const params = domain.makeTextGenerationParams({ model: domain.makeChatModel({ modelId: 'fixture' }),
    temperature: 0.4, topP: 0.8, maxTokens: 300, reasoningLevel: 'high',
    customBody: { fixture: 'preserved' } });
  const api = { streamText: async (messages, stepParams, onChunk, opts) => {
    requests.push({ messages: structuredClone(messages), params: stepParams, signal: opts?.signal });
    assert.ok(requests.length <= script.length, 'Unexpected additional model turn');
    const response = script[requests.length - 1];
    if (response instanceof Error) throw response;
    onChunk(response);
    if (onStream) await onStream(requests.length, opts?.signal);
  } };
  const runtime = { assistant, api, params, contextMessageSize: 100 };
  const host = { ...domain, exports: {}, Error, Date, String,
    getAgentCronManager: async () => ({ listTasksSnapshot: async () => [task] }),
    beginConversationRun: id => { events.push(['begin', id]); return () => events.push(['release', id]); },
    notifyCronRunning: (...args) => events.push(['running', ...args]),
    notifyCronCompleted: (...args) => events.push(['completed', ...args]),
    notifyCronFailed: (...args) => events.push(['failed', ...args]),
    getChatRepository: () => store,
    resolveChatRuntime: async id => { events.push(['runtime', id]); return runtime; },
    getChatKvStore: () => ({}), loadSearchPrefs: async () => ({ enableWebSearch: false }),
    loadAgentRuntimeSnapshot: async () => ({ maxToolLoopSteps: 128 }),
    ensureSearchSdk() {}, initMcpManager: async () => {},
    getWorkspaceManager: () => ({}), getToolActivityStore: () => ({}),
    createWorkspaceTools: () => [readTool, writeTool], getMcpManager: () => ({}),
    createMcpServerTools: () => [],
    createTimeTool: () => domain.createTimeTool(() => new Date('2026-10-18T00:00:00Z')),
  };
  return { domain, task, events, requests, saved, toolSignals, repository, params,
    execute: loadExecutor(host) };
};

test('Entry Cron runs the real tool loop, forwards its signal and persists the completed conversation', async () => {
  const h = await harness({ script: [toolCall('get_time_info'), toolCall('file_read'), text('time checked')] });
  const signal = new AbortController().signal;
  await h.execute('task', signal);
  assert.equal(h.requests.length, 3, 'Both tool results must reach a following model turn');
  assert.deepEqual(h.toolSignals, [signal], 'The executing workspace tool receives the owning run signal');
  for (const request of h.requests) {
    assert.equal(request.signal, signal);
    assert.ok(request.params.tools.some(tool => tool.name === 'get_time_info'));
    for (const key of ['model', 'temperature', 'topP', 'maxTokens', 'reasoningLevel', 'customBody']) {
      assert.deepEqual(request.params[key], h.params[key], `Step provider retains ${key}`);
    }
  }
  const resultTool = h.requests[1].messages.flatMap(message => message.parts)
    .find(part => part.type === 'tool');
  assert.equal(JSON.parse(resultTool.output[0].text).timestamp_ms, Date.parse('2026-10-18T00:00:00Z'));
  const readResult = h.requests[2].messages.flatMap(message => message.parts)
    .find(part => part.type === 'tool' && part.toolName === 'file_read');
  assert.equal(readResult.output[0].text, 'read result');
  const persisted = await h.repository.getById('cron-task');
  assert.equal(h.domain.toText(h.domain.currentMessages(persisted).at(-1)).trim(), 'time checked');
  assert.equal(h.domain.conversationHasPendingOrUnexecutedTools(persisted), false);
  assert.deepEqual(h.events.map(event => event[0]), ['begin', 'running', 'runtime', 'completed', 'release']);
  assert.equal(h.events[0][1], 'cron-task');
});

test('Entry Cron saves pending approval, rejects without tool side effects and reports failure', async () => {
  const h = await harness({ script: [toolCall('file_write')], existing: true });
  await assert.rejects(h.execute('task'), /需要人工审批或未完成工具执行/);
  assert.equal(h.toolSignals.length, 0, 'Even a conversation with auto approval cannot mutate headlessly');
  assert.equal(h.requests.length, 1);
  const persisted = await h.repository.getById('existing');
  const pending = h.domain.currentMessages(persisted).flatMap(message => message.parts)
    .find(part => part.type === 'tool');
  assert.equal(pending.approvalState.type, 'pending');
  assert.equal(h.domain.conversationHasPendingOrUnexecutedTools(persisted), true);
  assert.equal(h.saved.at(-1).id, 'existing');
  assert.deepEqual(h.events.map(event => event[0]), ['begin', 'running', 'runtime', 'failed', 'release']);
  assert.equal(h.events[2][1], 'assistant');
  assert.match(h.events[3][3], /需要人工审批或未完成工具执行/);
});

test('Entry Cron propagates provider failure, sends a failure notification and releases the run', async () => {
  const h = await harness({ script: [new Error('provider unavailable')] });
  await assert.rejects(h.execute('task'), /provider unavailable/);
  assert.deepEqual(h.events.map(event => event[0]), ['begin', 'running', 'runtime', 'failed', 'release']);
  assert.equal(h.events[3][3], 'provider unavailable');
});

test('Entry Cron rejects pre-cancelled work before acquiring a run or creating a conversation', async () => {
  const h = await harness();
  const controller = new AbortController(); controller.abort();
  await assert.rejects(h.execute('task', controller.signal), /Cron run cancelled/);
  assert.deepEqual(h.events, []);
  assert.equal(h.saved.length, 0);
  assert.equal(h.requests.length, 0);
});

test('Entry Cron cancellation after a model tool call prevents execution and completion/failure notifications', async () => {
  const controller = new AbortController();
  const h = await harness({ script: [toolCall('file_read')], onStream: () => controller.abort() });
  await assert.rejects(h.execute('task', controller.signal), /Cron run cancelled/);
  assert.equal(h.requests[0].signal, controller.signal);
  assert.equal(h.toolSignals.length, 0);
  assert.equal(h.requests.length, 1);
  assert.deepEqual(h.events.map(event => event[0]), ['begin', 'running', 'runtime', 'release']);
});
