const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');
const ts = require('../../../chat/node_modules/typescript');
require('../../../chat/node_modules/tsx/dist/cjs/index.cjs');
const domain = require('../../../chat/src/main/ets/index.ts');
const { loadChatComposerSession } = require('./chat_composer_fixture.cjs');

function transpileEts(relPath) {
  const filename = path.resolve(__dirname, relPath);
  const result = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
    reportDiagnostics: true,
  });
  if (result.diagnostics.length > 0) {
    throw new Error(`Could not transpile ${relPath}: ${result.diagnostics.map(d => d.messageText).join('; ')}`);
  }
  return result.outputText;
}

function loadMacGatewayModule(rcpFetchImpl, kvStore) {
  const outputText = transpileEts('../main/ets/platform_impl/MacGatewayClient.ets');
  const exports = {};
  const rcpCalls = [];
  const rcpMock = {
    Request: class Request {
      constructor(url, method, headers, body, cookies, transfer, configuration) {
        this.url = url;
        this.method = method;
        this.headers = headers;
        this.body = body;
        this.configuration = configuration;
      }
    },
    createSession() {
      return {
        async fetch(req) {
          rcpCalls.push(req);
          return await rcpFetchImpl(req);
        },
        close() {},
      };
    },
  };
  const utilMock = {
    Base64Helper: class {
      decodeSync(b64) { return new Uint8Array(Buffer.from(b64, 'base64')); }
    },
    TextDecoder: {
      create() {
        return {
          decodeToString(bytes) { return Buffer.from(bytes).toString('utf8'); },
        };
      },
    },
  };
  new Function('exports', 'require', outputText)(exports, (name) => {
    if (name === '@kit.RemoteCommunicationKit') return { rcp: rcpMock };
    if (name === '@kit.ArkTS') return { util: utilMock };
    if (name === '@kit.PushKit') return { pushService: { async getToken() { return 'tok'; } } };
    if (name === '@kit.BasicServicesKit') return { deviceInfo: { marketName: 'HarmonyTest' } };
    if (name === '@kit.PerformanceAnalysisKit') return { hilog: { warn() {}, info() {} } };
    if (name.endsWith('/AppContainer.ets')) return { getChatKvStore: () => kvStore };
    throw new Error(`Unexpected dependency in MacGatewayClient: ${name}`);
  });
  return { mod: exports, rcpCalls };
}

function loadCloudDialogueModule(gatewayMod) {
  const outputText = transpileEts('../main/ets/platform_impl/chat/ChatCloudDialogueSession.ets');
  const exports = {};
  new Function('exports', 'require', 'Observed', 'setInterval', 'clearInterval', outputText)(
    exports,
    (name) => {
      if (name === '@amber/chat-domain') return domain;
      if (name.endsWith('/MacGatewayClient.ets')) return gatewayMod;
      throw new Error(`Unexpected dependency in ChatCloudDialogueSession: ${name}`);
    },
    (ctor) => ctor,
    () => 1,
    () => {},
  );
  return exports;
}

function createMemoryKv(initial = {}) {
  const map = new Map(Object.entries(initial));
  return {
    map,
    async get(key) { return map.has(key) ? map.get(key) : null; },
    async put(key, value) { map.set(key, String(value)); },
    async delete(key) { map.delete(key); },
  };
}

const SAMPLE_CONNECTION = {
  gatewayId: 'gw-1',
  name: 'Arquiel-Mac',
  addrs: ['127.0.0.1'],
  port: 8741,
  fingerprint: 'sha256-spki-pin-base64',
  deviceId: 'dev-harmony-1',
  token: 'bearer-secret-token',
};

test('MacGatewayClient reuses SPKI public-key pinning and calls /v2/dialogue/* + /v2/work-items/*', async () => {
  const kv = createMemoryKv();
  const responses = [];
  const { mod, rcpCalls } = loadMacGatewayModule(async (req) => {
    const next = responses.shift() || { statusCode: 200, body: '{}' };
    return {
      statusCode: next.statusCode,
      body: new TextEncoder().encode(typeof next.body === 'string' ? next.body : JSON.stringify(next.body)).buffer,
    };
  }, kv);

  const client = mod.MacGatewayClient.of(SAMPLE_CONNECTION);

  responses.push({
    statusCode: 202,
    body: {
      eventId: 'ev-msg-1',
      requestId: 'req-msg-1',
      topicId: 'topic-1',
      workItemIds: ['work-1'],
      state: 'received',
      replayed: false,
    },
  });
  const msgReceipt = await client.messages('req-msg-1', '请执行长任务 A');
  assert.equal(msgReceipt.eventId, 'ev-msg-1');
  assert.deepEqual(msgReceipt.workItemIds, ['work-1']);
  assert.equal(rcpCalls[0].url, 'https://127.0.0.1:8741/v2/dialogue/messages');
  assert.equal(rcpCalls[0].method, 'POST');
  assert.equal(rcpCalls[0].headers.authorization, 'Bearer bearer-secret-token');
  assert.deepEqual(rcpCalls[0].configuration.security.certificatePinning, {
    kind: 'public-key',
    publicKeyHash: 'sha256-spki-pin-base64',
    hashAlgorithm: 'SHA-256',
  });

  responses.push({
    statusCode: 202,
    body: {
      eventId: 'ev-inp-1',
      requestId: 'req-inp-1',
      topicId: 'topic-1',
      workItemIds: ['work-1'],
      state: 'received',
      replayed: false,
    },
  });
  const inpReceipt = await client.inputs('work-1', 'req-inp-1', '补充条件：只处理只读模块');
  assert.equal(inpReceipt.eventId, 'ev-inp-1');
  assert.equal(rcpCalls[1].url, 'https://127.0.0.1:8741/v2/work-items/work-1/inputs');

  responses.push({
    statusCode: 200,
    body: {
      eventId: 'ev-stop-1',
      requestId: 'req-stop-1',
      topicId: 'topic-1',
      workItemIds: ['work-1'],
      state: 'consumed',
      replayed: false,
    },
  });
  const stopReceipt = await client.stop('work-1', 'req-stop-1');
  assert.equal(stopReceipt.state, 'consumed');
  assert.equal(rcpCalls[2].url, 'https://127.0.0.1:8741/v2/work-items/work-1/stop');

  responses.push({
    statusCode: 200,
    body: {
      eventId: 'ev-inp-1',
      requestId: 'req-inp-1',
      topicId: 'topic-1',
      workItemIds: ['work-1'],
      state: 'consumed',
      replayed: true,
    },
  });
  const queriedReceipt = await client.receipts('req-inp-1');
  assert.equal(queriedReceipt.state, 'consumed');
  assert.equal(rcpCalls[3].url, 'https://127.0.0.1:8741/v2/dialogue/receipts/req-inp-1');

  responses.push({
    statusCode: 200,
    body: {
      items: [{
        seq: 1,
        id: 'ev-1',
        kind: 'assistant_reply',
        role: 'assistant',
        text: '收到',
        topicId: 'topic-1',
        workItemId: null,
        outcome: null,
      }],
      nextCursor: 1,
    },
  });
  const page = await client.events(0, 100);
  assert.equal(page.nextCursor, 1);
  assert.equal(page.items.length, 1);
  assert.equal(rcpCalls[4].url, 'https://127.0.0.1:8741/v2/dialogue/events?after=0&limit=100');

  // Validation & 404 old-daemon error description
  await assert.rejects(() => client.messages('bad id with spaces', 'hi'), /invalid_request/);
  await assert.rejects(() => client.messages('req-empty', '   '), /消息内容不能为空/);

  responses.push({ statusCode: 404, body: 'Not Found' });
  await assert.rejects(
    async () => {
      try {
        await client.events(0, 10);
      } catch (e) {
        assert.match(mod.describeMacGatewayError(e), /不支持云主对话/);
        throw e;
      }
    },
    /HTTP 404/,
  );
});

test('Cloud mode switch disabled keeps local composer path untouched; enabled routes to cloud without local fallback', async () => {
  const kv = createMemoryKv({
    mac_gateway_connection_v1: JSON.stringify(SAMPLE_CONNECTION),
    mac_gateway_cloud_dialogue_enabled_v1: 'false',
  });
  const { mod: gatewayMod } = loadMacGatewayModule(async () => ({ statusCode: 200, body: '{}' }), kv);
  const cloudMod = loadCloudDialogueModule(gatewayMod);
  const ChatComposerSession = loadChatComposerSession(domain);

  let conversation = domain.makeConversation('conv-local-vs-cloud');
  const errors = [];
  let localSubmits = 0;
  let cloudMessagesCalls = 0;
  let failCloudMessage = false;

  const cloudSession = new cloudMod.ChatCloudDialogueSession();
  cloudSession.start({
    kvStore: kv,
    currentConversation: () => conversation,
    mutateConversation: async (_id, update) => {
      const next = update(conversation);
      if (next !== null) conversation = next;
      return conversation;
    },
    error: (msg) => errors.push(msg),
    autoScrollEnabled: () => true,
    jumpToBottom: () => {},
    clientFactory: () => ({
      async messages(requestId, text) {
        cloudMessagesCalls++;
        if (failCloudMessage) {
          throw new gatewayMod.MacGatewayError('server', 'HTTP 404', 404, '');
        }
        return {
          eventId: `ev-${cloudMessagesCalls}`,
          requestId,
          topicId: 'topic-cloud',
          workItemIds: [],
          state: 'received',
          replayed: false,
        };
      },
      async inputs() { throw new Error('unused'); },
      async stop() { throw new Error('unused'); },
      async receipts(requestId) {
        return {
          eventId: 'ev-1',
          requestId,
          topicId: 'topic-cloud',
          workItemIds: [],
          state: 'received',
          replayed: true,
        };
      },
      async events(after = 0) {
        return { items: [], nextCursor: after };
      },
    }),
  });
  await cloudSession.refreshConfigAndStart();
  assert.equal(cloudSession.isActiveMode(), false);

  const composer = new ChatComposerSession();
  composer.start({
    kvStore: kv,
    runService: {
      isBusy: () => false,
      append: async () => {},
      enqueue: async () => {},
      takePending: async () => null,
      cancelPending: async () => {},
      resumePending: async () => {},
      clearPending: async () => {},
      dismissCompletion: () => {},
      snapshot: () => null,
    },
    currentConversation: () => conversation,
    isSending: () => false,
    currentQueue: () => [],
    currentCompletionNotice: () => null,
    setCompletionNotice: () => {},
    error: (msg) => errors.push(msg),
    resolveChoice: async () => ({
      provider: { id: 'p1' },
      model: { modelId: 'm1', modalities: { input: ['text'], output: ['text'] }, abilities: [] },
    }),
    loadOcrSeed: async () => ({ ocrModelId: '', providers: [] }),
    probeOcrHealth: async () => ({ status: 'healthy' }),
    submit: () => {
      localSubmits++;
      return true;
    },
    dismissKeyboard: () => {},
    autoScrollEnabled: () => true,
    jumpToBottom: () => {},
    openDeepRead: () => {},
    captureContinuation: () => null,
    continuationAvailable: () => false,
    continuationCurrent: () => true,
    loadMentionRoles: async () => [],
    abilityContext: () => ({}),
    cloudDialogue: () => cloudSession,
  });

  // 1. Default (disabled): sends via local submit, zero cloud calls
  composer.setInputText('本地模型问题');
  await composer.send();
  assert.equal(localSubmits, 1);
  assert.equal(cloudMessagesCalls, 0);
  assert.equal(composer.inputText, '');

  // 2. Enable cloud mode explicitly: sends via cloud, zero local submits
  await gatewayMod.setCloudDialogueEnabled(true);
  await cloudSession.refreshConfigAndStart();
  assert.equal(cloudSession.isActiveMode(), true);

  composer.setInputText('云端问题 1');
  await composer.send();
  assert.equal(localSubmits, 1);
  assert.equal(cloudMessagesCalls, 1);
  assert.equal(composer.inputText, '');

  // 3. When cloud fails (e.g. 404), reports error, keeps draft for retry, NEVER falls back to local
  failCloudMessage = true;
  composer.setInputText('云端问题失败不回退');
  await composer.send();
  assert.equal(localSubmits, 1);
  assert.equal(cloudMessagesCalls, 2);
  assert.equal(composer.inputText, '云端问题失败不回退');
  assert.match(errors[errors.length - 1], /不支持云主对话/);
});

test('Contract replay: 长 A → 问 B → 补充 A → 停止 A with seq resume, dedup, single-line process_event, and 3 input states', async () => {
  const kv = createMemoryKv({
    mac_gateway_connection_v1: JSON.stringify(SAMPLE_CONNECTION),
    mac_gateway_cloud_dialogue_enabled_v1: 'true',
  });
  const { mod: gatewayMod } = loadMacGatewayModule(async () => ({ statusCode: 200, body: '{}' }), kv);
  const cloudMod = loadCloudDialogueModule(gatewayMod);

  let conversation = domain.makeConversation('conv-async-flow');
  const errors = [];
  const apiLog = [];
  const receiptsByReq = new Map();
  let eventLog = [];

  const mockClient = {
    async messages(requestId, text) {
      apiLog.push({ op: 'messages', requestId, text });
      const isTaskA = text.includes('长任务 A');
      const receipt = {
        eventId: isTaskA ? 'ev-user-a' : 'ev-user-b',
        requestId,
        topicId: isTaskA ? 'topic-a' : 'topic-b',
        workItemIds: isTaskA ? ['work-A'] : [],
        state: 'received',
        replayed: false,
      };
      receiptsByReq.set(requestId, receipt);
      return receipt;
    },
    async inputs(workItemId, requestId, text) {
      apiLog.push({ op: 'inputs', workItemId, requestId, text });
      const idx = apiLog.filter(x => x.op === 'inputs').length;
      const receipt = {
        eventId: `ev-followup-${idx}`,
        requestId,
        topicId: 'topic-a',
        workItemIds: [workItemId],
        state: 'received',
        replayed: false,
      };
      receiptsByReq.set(requestId, receipt);
      return receipt;
    },
    async stop(workItemId, requestId) {
      apiLog.push({ op: 'stop', workItemId, requestId });
      const receipt = {
        eventId: 'ev-stop-a',
        requestId,
        topicId: 'topic-a',
        workItemIds: [workItemId],
        state: 'consumed',
        replayed: false,
      };
      receiptsByReq.set(requestId, receipt);
      return receipt;
    },
    async receipts(requestId) {
      return receiptsByReq.get(requestId);
    },
    async events(after = 0, limit = 100) {
      const items = eventLog.filter(e => e.seq > after).slice(0, limit);
      const nextCursor = items.length === 0 ? after : items[items.length - 1].seq;
      return { items, nextCursor };
    },
  };

  const createSession = () => {
    const s = new cloudMod.ChatCloudDialogueSession();
    s.start({
      kvStore: kv,
      currentConversation: () => conversation,
      mutateConversation: async (_id, update) => {
        const next = update(conversation);
        if (next !== null) conversation = next;
        return conversation;
      },
      error: (msg) => errors.push(msg),
      autoScrollEnabled: () => true,
      jumpToBottom: () => {},
      clientFactory: () => mockClient,
    });
    return s;
  };

  const session = createSession();
  await session.refreshConfigAndStart();

  // Empty events page does not advance cursor
  await session.pollOnce();
  assert.equal(session.cursor, 0);

  // Step 1: Send long Task A
  assert.equal(await session.submitComposerText('请执行长任务 A：扫描全部模块'), true);
  assert.equal(session.runningTasks().length, 1);
  assert.equal(session.runningTasks()[0].workItemId, 'work-A');
  assert.equal(session.inputs[0].state, 'received');

  // Task A emits input_consumed + multiple process_events (with newlines)
  eventLog.push(
    {
      seq: 1,
      id: 'consumed:ev-user-a',
      kind: 'input_consumed',
      role: 'system',
      text: 'consumed',
      topicId: 'topic-a',
      workItemId: 'work-A',
      outcome: null,
    },
    {
      seq: 2,
      id: 'proc-1',
      kind: 'process_event',
      role: 'system',
      text: '正在分析模块 1/10\n读取目录结构',
      topicId: 'topic-a',
      workItemId: 'work-A',
      outcome: null,
    },
    {
      seq: 3,
      id: 'proc-2',
      kind: 'process_event',
      role: 'system',
      text: '正在分析模块 4/10\n检查依赖关系',
      topicId: 'topic-a',
      workItemId: 'work-A',
      outcome: null,
    },
  );
  await session.pollOnce();
  assert.equal(session.cursor, 3);
  assert.equal(session.inputs[0].state, 'consumed');
  // process_event updates single-line progress in place and NEVER adds messages/tool cards to conversation
  assert.equal(session.runningTasks()[0].progressText, '正在分析模块 4/10 检查依赖关系');
  assert.equal(conversation.messageNodes.length, 1); // only User A message node exists

  // Step 2: While Task A is still running, ask independent Question B (normal composer submit -> messages)
  assert.equal(await session.submitComposerText('顺便问个独立问题 B：2+2 等于几？'), true);
  assert.equal(apiLog[1].op, 'messages');
  eventLog.push({
    seq: 4,
    id: 'reply-b',
    kind: 'assistant_reply',
    role: 'assistant',
    text: '4',
    topicId: 'topic-b',
    workItemId: null,
    outcome: null,
  });
  await session.pollOnce();
  assert.equal(session.cursor, 4);
  const messagesAfterB = domain.currentMessages(conversation);
  assert.equal(messagesAfterB.length, 3); // User A, User B, Assistant B ("4")
  assert.equal(domain.toText(messagesAfterB[2]), '4');
  assert.equal(session.runningTasks().length, 1); // Task A is still running

  // Step 3: Send two follow-up inputs to Task A:
  //   followup #1 will be consumed by Task A
  //   followup #2 will stay received until Task A is stopped, becoming cancelled
  session.toggleFollowupTarget('work-A');
  assert.equal(session.followupTargetWorkItemId, 'work-A');
  assert.equal(await session.submitComposerText('补充说明 1：优先检查 entry 模块'), true);
  assert.equal(apiLog[2].op, 'inputs');
  assert.equal(apiLog[2].workItemId, 'work-A');
  assert.equal(session.followupTargetWorkItemId, ''); // auto-cleared after sending followup

  // Second followup via explicit @task:work-A prefix
  assert.equal(await session.submitComposerText('@task:work-A 补充说明 2：生成详细统计'), true);
  assert.equal(apiLog[3].op, 'inputs');
  assert.equal(apiLog[3].workItemId, 'work-A');

  const followup1Record = session.inputs.find(i => i.eventId === 'ev-followup-1');
  const followup2Record = session.inputs.find(i => i.eventId === 'ev-followup-2');
  assert.equal(followup1Record.state, 'received');
  assert.equal(followup2Record.state, 'received');

  // Worker consumes followup #1
  eventLog.push({
    seq: 5,
    id: 'consumed:ev-followup-1',
    kind: 'input_consumed',
    role: 'system',
    text: 'consumed followup 1',
    topicId: 'topic-a',
    workItemId: 'work-A',
    outcome: null,
  });
  await session.pollOnce();
  assert.equal(session.inputs.find(i => i.eventId === 'ev-followup-1').state, 'consumed');
  assert.equal(session.inputs.find(i => i.eventId === 'ev-followup-2').state, 'received');

  // Verify all 3 states (received, consumed) are visible before stop, and cancelled after stop
  // Step 4: Stop Task A -> cancels unconsumed followup #2 and emits task_result(cancelled)
  const stopReceipt = await session.stopTask('work-A');
  assert.equal(stopReceipt.state, 'consumed');
  assert.equal(apiLog[4].op, 'stop');
  assert.equal(apiLog[4].workItemId, 'work-A');

  eventLog.push(
    {
      seq: 6,
      id: 'cancelled:ev-followup-2',
      kind: 'input_cancelled',
      role: 'system',
      text: 'cancelled followup 2',
      topicId: 'topic-a',
      workItemId: 'work-A',
      outcome: null,
    },
    {
      seq: 7,
      id: 'result:work-A',
      kind: 'task_result',
      role: 'assistant',
      text: '任务已按用户请求停止，已完成 entry 模块初步检查。',
      topicId: 'topic-a',
      workItemId: 'work-A',
      outcome: 'cancelled',
    },
  );
  await session.pollOnce();
  assert.equal(session.cursor, 7);
  assert.equal(session.runningTasks().length, 0);
  assert.equal(session.activeProgressText, '');
  assert.equal(session.inputs.find(i => i.eventId === 'ev-followup-1').state, 'consumed');
  assert.equal(session.inputs.find(i => i.eventId === 'ev-followup-2').state, 'cancelled');

  const finalMsgs = domain.currentMessages(conversation).map(m => domain.toText(m));
  assert.ok(finalMsgs.some(t => t.includes('[consumed]') && t.includes('补充说明 1')));
  assert.ok(finalMsgs.some(t => t.includes('[cancelled]') && t.includes('补充说明 2')));
  assert.ok(finalMsgs.some(t => t.includes('已停止') && t.includes('任务已按用户请求停止')));

  // Step 5: Verify seq cursor persistence & event deduplication across session reload
  const msgCountBeforeReplay = domain.currentMessages(conversation).length;
  const reloadedSession = createSession();
  await reloadedSession.refreshConfigAndStart();
  assert.equal(reloadedSession.cursor, 7);
  // Even if server returns overlapping events, seenEventIds deduplicates them
  await reloadedSession.pollOnce();
  assert.equal(domain.currentMessages(conversation).length, msgCountBeforeReplay);
  assert.equal(errors.length, 0);
});

test('Live local amberd daemon E2E: 长 A → 问 B → 补充 A → 停止 A over real HTTP', async (t) => {
  const http = require('node:http');
  const { execFileSync } = require('node:child_process');
  const javaBin = '/opt/homebrew/opt/openjdk@21/bin/java';
  const jarPath = '/Users/arquiel/.synara/worktrees/8eddb6c10d7d/cloud-daemon/build/libs/amberd-all.jar';
  if (!fs.existsSync(javaBin) || !fs.existsSync(jarPath)) {
    t.skip('Local Java 21 or amberd-all.jar not present');
    return;
  }

  const pythonBin = fs.existsSync('/Library/Developer/CommandLineTools/usr/bin/python3')
    ? '/Library/Developer/CommandLineTools/usr/bin/python3'
    : '/usr/bin/python3';
  const fakeProvider = http.createServer((req, res) => {
    let raw = '';
    req.on('data', chunk => { raw += chunk; });
    req.on('end', () => {
      const body = JSON.parse(raw || '{}');
      const messages = body.messages || [];
      const text = JSON.stringify(messages);
      const tools = Object.fromEntries((body.tools || []).map(x => [x.function.name, x.function]));
      const historyCalls = messages.flatMap(m => (m.tool_calls || []).map(c => c.function));
      const used = (name, sub) => historyCalls.some(c => c && c.name === name && String(c.arguments || '').includes(sub));
      const calls = [];
      let output = '正在处理。';
      const tool = (name, values) => {
        calls.push({
          id: `call-${Date.now()}-${calls.length}`,
          type: 'function',
          function: { name, arguments: JSON.stringify(values) },
        });
      };
      if (text.includes('受管 Worker')) {
        if (!used('process_run', 'time.sleep(120)')) {
          tool('process_run', {
            argv: [pythonBin, '-c', 'import time; time.sleep(120)'],
            background: true,
            timeoutSeconds: 120,
          });
        } else if (text.includes('ADD_HARMONY_SCOPE') && !used('workspace_file_write', 'note.txt')) {
          tool('workspace_file_write', { path: 'note.txt', content: 'ADD_HARMONY_SCOPE consumed' });
        } else {
          output = '后台脚本仍在工作，等待补充或完成事件。';
        }
      } else if (text.includes('你是对话标签提取器')) {
        output = '{"tags":[]}';
      } else if ('dispatch_work' in tools) {
        const last = String((messages[messages.length - 1] || {}).content || '');
        if (last.includes('QUESTION_B')) {
          output = 'ANSWER_B: 2+2=4，与后台任务A独立。';
        } else if (last.includes('LONG_TASK_A')) {
          output = '我会在后台执行任务A，你可以继续提问。';
          tool('dispatch_work', {
            tasks: [{ task_key: 'task-a', title: '长任务A', brief: 'LONG_TASK_A：后台长任务' }],
          });
        } else {
          output = '已处理。';
        }
      } else {
        output = 'main';
      }
      const message = { role: 'assistant', content: output };
      if (calls.length > 0) message.tool_calls = calls;
      const payload = JSON.stringify({
        id: 'harmony-e2e-fixture',
        object: 'chat.completion',
        choices: [{ index: 0, finish_reason: calls.length > 0 ? 'tool_calls' : 'stop', message }],
      });
      res.writeHead(200, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(payload) });
      res.end(payload);
    });
  });

  await new Promise(resolve => fakeProvider.listen(0, '127.0.0.1', resolve));
  const providerPort = fakeProvider.address().port;

  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'harmony-cloud-async-'));
  const dataDir = path.join(tmpDir, 'data');
  const port = 19400 + Math.floor(Math.random() * 400);
  const env = {
    ...process.env,
    HOME: os.homedir(),
    PATH: `${path.dirname(javaBin)}:/usr/bin:/bin`,
    AMBER_MASTER_KEY: 'fake-async-master',
    AMBER_MODEL_PROVIDER: 'openai',
    AMBER_MODEL_ID: 'async-fixture',
    AMBER_MODEL_API_KEY: 'fake-async-provider-key',
    AMBER_MODEL_BASE_URL: `http://127.0.0.1:${providerPort}/v1`,
  };

  const issuedStdout = execFileSync(
    javaBin,
    ['-cp', jarPath, 'app.amber.cloud.CliMainKt', 'pair', '--data-dir', dataDir, '--addr', '127.0.0.1', '--port', String(port)],
    { env, encoding: 'utf8' },
  );
  const pairLink = issuedStdout.split(/\r?\n/).find(line => line.startsWith('amber://'));
  assert.ok(pairLink, 'CliMainKt pair should emit an amber:// pairing link');

  const proc = spawn(
    javaBin,
    ['-Xmx256m', '-jar', jarPath, '--host', '127.0.0.1', '--port', String(port), '--data-dir', dataDir],
    { env, stdio: 'ignore' },
  );

  t.after(() => {
    try { proc.kill('SIGKILL'); } catch (_e) {}
    try { fakeProvider.close(); } catch (_e) {}
    try { fs.rmSync(tmpDir, { recursive: true, force: true }); } catch (_e) {}
  });

  const baseUrl = `http://127.0.0.1:${port}`;
  let ready = false;
  for (let i = 0; i < 60; i++) {
    try {
      const res = await fetch(`${baseUrl}/v2/agents`);
      if (res.status === 401) {
        ready = true;
        break;
      }
    } catch (_e) {}
    await new Promise(r => setTimeout(r, 150));
  }
  assert.equal(ready, true, 'Local amberd daemon should become ready');

  const kv = createMemoryKv();
  const { mod: gatewayMod } = loadMacGatewayModule(async (req) => {
    const httpUrl = req.url.replace(/^https:/, 'http:');
    const res = await fetch(httpUrl, {
      method: req.method,
      headers: req.headers,
      body: req.body,
    });
    const ab = await res.arrayBuffer();
    return { statusCode: res.status, body: ab };
  }, kv);
  const cloudMod = loadCloudDialogueModule(gatewayMod);

  // Pair using HarmonyOS parsePairingLink + pairMacGateway
  const payload = gatewayMod.parsePairingLink(pairLink);
  assert.ok(payload !== null, 'parsePairingLink should parse CliMainKt pair link');
  const pairedConn = await gatewayMod.pairMacGateway(payload);
  assert.ok(pairedConn.token.length > 0, 'pairMacGateway should store bearer token');
  await gatewayMod.setCloudDialogueEnabled(true);

  let conversation = domain.makeConversation('conv-live-amberd');
  const errors = [];
  const session = new cloudMod.ChatCloudDialogueSession();
  session.start({
    kvStore: kv,
    currentConversation: () => conversation,
    mutateConversation: async (_id, update) => {
      const next = update(conversation);
      if (next !== null) conversation = next;
      return conversation;
    },
    error: (msg) => errors.push(msg),
    autoScrollEnabled: () => true,
    jumpToBottom: () => {},
  });
  await session.refreshConfigAndStart();
  assert.equal(session.isActiveMode(), true);

  // 1. Send long Task A
  assert.equal(await session.submitComposerText('LONG_TASK_A：请后台执行长任务'), true);

  let workItemA = '';
  for (let i = 0; i < 40; i++) {
    await session.pollOnce();
    if (session.runningTasks().length > 0) {
      workItemA = session.runningTasks()[0].workItemId;
      break;
    }
    await new Promise(r => setTimeout(r, 100));
  }
  assert.ok(workItemA.length > 0, 'Task A should be dispatched with a workItemId');

  // 2. Ask independent Question B while Task A is running
  assert.equal(await session.submitComposerText('QUESTION_B：2+2等于多少？'), true);
  let sawAnswerB = false;
  for (let i = 0; i < 40; i++) {
    await session.pollOnce();
    const texts = domain.currentMessages(conversation).map(m => domain.toText(m));
    if (texts.some(txt => txt.includes('ANSWER_B'))) {
      sawAnswerB = true;
      break;
    }
    await new Promise(r => setTimeout(r, 100));
  }
  assert.equal(sawAnswerB, true, 'Question B should be answered independently while Task A is still running');
  assert.equal(session.runningTasks().length, 1, 'Task A should still be running when Question B is answered');

  // 3. Send follow-up input to Task A
  session.toggleFollowupTarget(workItemA);
  assert.equal(await session.submitComposerText('ADD_HARMONY_SCOPE：重点检查 harmony 目录'), true);
  for (let i = 0; i < 40; i++) {
    await session.pollOnce();
    if (session.inputs.some(inp => inp.kind === 'followup' && inp.state === 'consumed')) break;
    await new Promise(r => setTimeout(r, 100));
  }
  assert.equal(
    session.inputs.some(inp => inp.kind === 'followup' && inp.state === 'consumed'),
    true,
    'Follow-up input to Task A should transition from received to consumed',
  );

  // 4. Stop Task A
  const stopRec = await session.stopTask(workItemA);
  assert.ok(stopRec !== null);
  assert.equal(stopRec.state, 'consumed');

  for (let i = 0; i < 40; i++) {
    await session.pollOnce();
    if (session.runningTasks().length === 0) break;
    await new Promise(r => setTimeout(r, 100));
  }
  assert.equal(session.runningTasks().length, 0, 'Task A should reach terminal state after stop');
  assert.ok(session.cursor > 0, 'Event cursor should have advanced');
  const finalTexts = domain.currentMessages(conversation).map(m => domain.toText(m));
  assert.ok(finalTexts.some(txt => txt.includes('ANSWER_B')), 'Conversation should contain Answer B');
  assert.ok(finalTexts.some(txt => txt.includes('已停止')), 'Conversation should contain stopped Task A result');
  assert.equal(errors.length, 0, `Unexpected errors: ${errors.join('; ')}`);
});
