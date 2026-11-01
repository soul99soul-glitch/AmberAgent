const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
require('../../../chat/node_modules/tsx/dist/cjs/index.cjs');

const domain = require('../../../chat/src/main/ets/index.ts');
const { loadChatComposerSession } = require('./chat_composer_fixture.cjs');
const { loadChatSmartUiSession } = require('./chat_smart_ui_fixture.cjs');

const etsRoot = path.resolve(__dirname, '../main/ets');

function transpileEts(relPath) {
  const fullPath = path.join(etsRoot, relPath);
  const source = fs.readFileSync(fullPath, 'utf8');
  return ts.transpileModule(source, {
    compilerOptions: {
      target: ts.ScriptTarget.ES2022,
      module: ts.ModuleKind.CommonJS,
      experimentalDecorators: true,
    },
    fileName: fullPath,
  }).outputText;
}

function createRuntimeEnvironment() {
  const appStorageMap = new Map();
  globalThis.AppStorage = {
    get: (key) => appStorageMap.get(key),
    setOrCreate: (key, val) => { appStorageMap.set(key, val); },
  };
  appStorageMap.set('abilityContext', { __mockContext: true });

  const keepAliveEvents = [];
  const activeGuards = new Map();

  const guardMod = {
    beginConversationRun: (convId) => {
      activeGuards.set(convId, (activeGuards.get(convId) ?? 0) + 1);
      let released = false;
      return () => {
        if (released) return;
        released = true;
        const next = (activeGuards.get(convId) ?? 1) - 1;
        if (next <= 0) activeGuards.delete(convId);
        else activeGuards.set(convId, next);
      };
    },
    beginConversationCompaction: () => () => {},
    isConversationCompacting: () => false,
    conversationRestoreVersion: () => 0,
  };

  const keepAliveMod = {
    reportGenerationProgress: (_ctx, snapshot) => {
      keepAliveEvents.push({ ...snapshot });
    },
  };

  class MockAbortSignal {
    constructor() {
      this.aborted = false;
      this._listeners = [];
    }
    addEventListener(type, fn) {
      if (type === 'abort') this._listeners.push(fn);
    }
    removeEventListener(type, fn) {
      if (type === 'abort') this._listeners = this._listeners.filter((item) => item !== fn);
    }
    _fire() {
      if (this.aborted) return;
      this.aborted = true;
      for (const fn of this._listeners.slice()) fn();
    }
  }

  class MockEntryAbortController {
    constructor() {
      this.signal = new MockAbortSignal();
    }
    abort() {
      this.signal._fire();
    }
  }

  class MockGenerationProgressTracker {
    constructor() {
      this.chars = 0;
      this.bytes = 0;
    }
    seedBaseline() {}
    observe(messages) {
      let total = 0;
      for (const m of messages) {
        total += domain.toText(m).length;
      }
      const gained = Math.max(0, total - this.chars);
      this.chars = Math.max(this.chars, total);
      this.bytes = this.chars * 2;
      return { chars: gained, bytes: gained * 2 };
    }
    totals() {
      return { chars: this.chars, bytes: this.bytes };
    }
  }

  const macGatewayExports = {};
  new Function('exports', 'require', transpileEts('platform_impl/MacGatewayClient.ets'))(
    macGatewayExports,
    (id) => {
      if (id === '@amber/chat-domain') return domain;
      if (id.startsWith('@kit.')) return { rcp: {}, util: {}, pushService: {}, cert: {}, deviceInfo: {}, hilog: { warn() {}, info() {} } };
      if (id.endsWith('/AppContainer.ets')) return { getChatKvStore: () => ({ get: async () => null, put: async () => {} }) };
      throw new Error('Unexpected import in MacGatewayClient: ' + id);
    },
  );

  const runServiceExports = {};
  new Function('exports', 'require', transpileEts('platform_impl/ConversationRunService.ets'))(
    runServiceExports,
    (id) => {
      if (id === '@amber/chat-domain') return domain;
      if (id === 'BuildProfile') return { default: { DEBUG: false }, DEBUG: false };
      if (id.endsWith('SSHForegroundApproval.ets')) return { assertSSHForegroundApproval: async () => {} };
      if (id.endsWith('EntryAbortController.ets')) return { EntryAbortController: MockEntryAbortController };
      if (id.endsWith('ConversationRunGuard.ets')) return guardMod;
      if (id.endsWith('BackgroundGenerationKeepAlive.ets')) return keepAliveMod;
      if (id.endsWith('GenerationProgressTracker.ets')) return { GenerationProgressTracker: MockGenerationProgressTracker };
      if (id.endsWith('ConversationBrowserGuard.ets')) return { releaseConversationBrowserRun: () => {} };
      if (id.endsWith('NativePluginJsTransport.ets')) return { cancelNativePluginJsConversation: () => {} };
      throw new Error('Unexpected import in ConversationRunService: ' + id);
    },
  );

  let cloudSessionExports = null;
  const taskRunnerExports = {};
  cloudSessionExports = {};

  new Function('exports', 'require', 'Observed', transpileEts('platform_impl/chat/ChatCloudDialogueSession.ets'))(
    cloudSessionExports,
    (id) => {
      if (id === '@amber/chat-domain') return domain;
      if (id.endsWith('MacGatewayClient.ets')) return macGatewayExports;
      if (id.endsWith('ConversationTaskRunner.ets')) return taskRunnerExports;
      throw new Error('Unexpected import in ChatCloudDialogueSession: ' + id);
    },
    (cls) => cls,
  );

  new Function('exports', 'require', transpileEts('platform_impl/ConversationTaskRunner.ets'))(
    taskRunnerExports,
    (id) => {
      if (id === '@amber/chat-domain') return domain;
      if (id.endsWith('EntryAbortController.ets')) return { EntryAbortController: MockEntryAbortController };
      if (id.endsWith('ConversationRunGuard.ets')) return guardMod;
      if (id.endsWith('BackgroundGenerationKeepAlive.ets')) return keepAliveMod;
      if (id.endsWith('GenerationProgressTracker.ets')) return { GenerationProgressTracker: MockGenerationProgressTracker };
      if (id.endsWith('ConversationRunService.ets')) return runServiceExports;
      if (id.endsWith('ChatCloudDialogueSession.ets')) return cloudSessionExports;
      throw new Error('Unexpected import in ConversationTaskRunner: ' + id);
    },
  );

  return {
    appStorageMap,
    keepAliveEvents,
    activeGuards,
    ConversationRunService: runServiceExports.ConversationRunService,
    ConversationTaskRunner: taskRunnerExports.ConversationTaskRunner,
    LOCAL_ASYNC_TASKS_KEY: taskRunnerExports.LOCAL_ASYNC_TASKS_KEY,
    ChatCloudDialogueSession: cloudSessionExports.ChatCloudDialogueSession,
    formatCloudInputStateBadge: cloudSessionExports.formatCloudInputStateBadge,
  };
}

function createInMemoryStorage(initialConversations = []) {
  const kvMap = new Map();
  const convMap = new Map();
  for (const c of initialConversations) convMap.set(c.id, c);

  const kv = {
    get: async (key) => (kvMap.has(key) ? kvMap.get(key) : null),
    put: async (key, val) => { kvMap.set(key, val); },
    delete: async (key) => { kvMap.delete(key); },
  };
  const repository = {
    getById: async (id) => (convMap.has(id) ? convMap.get(id) : null),
    save: async (conv) => { convMap.set(conv.id, conv); },
  };
  const compactStore = {
    getCompacts: async () => [],
    saveCompacts: async () => {},
  };
  const assistant = domain.makeAssistant({
    id: 'default',
    name: 'Default Assistant',
    systemPrompt: '',
    temperature: 0.7,
    topP: 1,
    contextMessageSize: 32,
    streamOutput: true,
    localTools: [],
    regexes: [],
  });
  return {
    kvMap,
    convMap,
    storage: {
      kv,
      repository,
      compactStore,
      loadAssistants: async () => [assistant],
    },
    assistant,
  };
}

function makeFakeExecution({ streamHandler, tools = [], completeAuxiliaryCalls = [] }) {
  const model = {
    modelId: 'mock-model',
    displayName: 'Mock Model',
    type: 'CHAT',
    inputModalities: ['TEXT'],
    outputModalities: ['TEXT'],
    abilities: ['TOOL'],
    tools: [],
    customHeaders: [],
    customBodies: [],
  };
  const assistant = domain.makeAssistant({
    id: 'default',
    name: 'Default Assistant',
    systemPrompt: '',
    temperature: 0.7,
    topP: 1,
    contextMessageSize: 32,
    streamOutput: true,
    localTools: [],
    regexes: [],
    autoApproveToolCalls: true,
  });
  return {
    prepareSeed: async () => ({
      runtime: {
        api: {},
        resolvedChoice: { provider: {}, model },
        assistant,
        params: { model: domain.toChatModel(model), temperature: 0.7, topP: 1, tools: [] },
        contextMessageSize: 32,
        modelContextWindowTokens: 64000,
        modelSupportsImageInput: true,
        modelAbilities: ['TOOL'],
        allSkills: [],
        terminalRuntime: null,
        pythonRuntime: null,
        moshRuntime: null,
        sshProfiles: null,
      },
      agentRuntime: {
        autoApproveAllToolCalls: true,
        autoApproveHighRiskToolCalls: true,
        maxToolLoopSteps: 8,
        speculativeToolExecution: { enabled: false, maxConcurrentTools: 4 },
      },
      compressChoice: null,
      taskPrompts: { titlePrompt: '', suggestionPrompt: '', compressPrompt: '' },
      searchSettings: {},
      ocrSeed: null,
      miniAppSetting: { enabled: false },
      userNickname: 'User',
      generativeWidgetsEnabled: false,
      webmountGlobalEnabled: false,
      webmountEvalEnabled: false,
      imageGenConfigured: false,
      subAgentEnabled: false,
      subAgentMode: 'roster',
      agentTaskTools: [],
      agentCronTools: [],
      enableGenerationNotification: false,
    }),
    buildTurnDeps: (ctx) => ({
      assistant,
      inputTransformers: [],
      outputTransformers: [],
      provider: {
        streamText: (messages, onChunk, opts) => streamHandler(ctx, messages, onChunk, opts),
      },
      store: { save: (conv) => ctx.save(conv) },
      abortSignal: ctx.signal,
    }),
    buildToolLoop: async (ctx) => ({
      tools,
      makeProviderForStep: () => ({
        streamText: (messages, onChunk, opts) => streamHandler(ctx, messages, onChunk, opts),
      }),
      consumeSteerMessages: () => ctx.consumeSteer(),
      autoApproveTools: true,
      autoApproveHighRiskTools: true,
      maxSteps: 8,
    }),
    completeAuxiliary: (ctx, finalized) => {
      completeAuxiliaryCalls.push({ runId: ctx.runId, conversationId: finalized.id });
    },
    compactConversation: async (conv) => ({ compacts: [], contextWindowTokens: 64000, conversation: conv }),
    evaluateCompletion: async () => null,
    scheduleApprovalTriage: () => {},
    cancelApprovalTriage: () => {},
    materializeDocumentParts: async (parts) => parts,
    materializeConversationDocuments: async (conv) => conv,
  };
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

test('local_agent_async: same conversation长 A → 问 B → 补充 A → 停止 A (B finishes first, A consumes follow-up at reasoning boundary, stop A only stops A)', async () => {
  const env = createRuntimeEnvironment();
  const initialConv = domain.patchConversation(domain.makeConversation('conv-local-1'), { title: '本地异步测试会话' });
  const { storage, convMap } = createInMemoryStorage([initialConv]);

  const taskAStep1Started = deferred();
  const releaseTaskAStep1 = deferred();
  const taskAStep2Started = deferred();
  const releaseTaskAStep2 = deferred();
  const taskAPrompts = [];

  const execution = makeFakeExecution({
    streamHandler: async (ctx, messages, onChunk, opts) => {
      const userTexts = messages
        .filter((m) => m.role === 'user')
        .map((m) => domain.toText(m));
      const allUserText = userTexts.join('\n');

      // Short Question B: answers immediately without waiting for A
      if (allUserText.includes('短问题 B')) {
        const msg = domain.makeUIMessage('assistant', [
          { type: 'text', text: '短问题 B 的回答：1+1=2', metadata: null },
        ]);
        onChunk({ choices: [{ delta: msg, message: null, finishReason: 'stop' }], usage: null });
        return;
      }

      // Long Task A
      taskAPrompts.push(userTexts);
      if (taskAPrompts.length === 1) {
        // Step 1 of Task A: emit initial streaming chunk, then wait for releaseTaskAStep1
        const partialStep1 = domain.makeUIMessage('assistant', [
          { type: 'text', text: '长任务 A 第一阶段分析中…', metadata: null },
        ]);
        onChunk({ choices: [{ delta: partialStep1, message: null, finishReason: null }], usage: null });
        taskAStep1Started.resolve();
        await releaseTaskAStep1.promise;
        const doneStep1 = domain.makeUIMessage('assistant', [
          { type: 'text', text: '（第一阶段完成）', metadata: null },
        ]);
        onChunk({ choices: [{ delta: doneStep1, message: null, finishReason: 'stop' }], usage: null });
        return;
      }

      // Step 2 of Task A (after consuming follow-up 1 at reasoning boundary)
      const partialStep2 = domain.makeUIMessage('assistant', [
        { type: 'text', text: '\n长任务 A 第二阶段（已结合补充说明分析性能）…', metadata: null },
      ]);
      onChunk({ choices: [{ delta: partialStep2, message: null, finishReason: null }], usage: null });
      taskAStep2Started.resolve();
      await Promise.race([
        releaseTaskAStep2.promise,
        new Promise((resolve) => {
          if (opts?.signal?.aborted) resolve();
          else opts?.signal?.addEventListener?.('abort', resolve);
        }),
      ]);
      if (opts?.signal?.aborted) {
        throw new Error('Generation aborted by user');
      }
    },
  });

  const runService = new env.ConversationRunService(storage, execution);
  const taskRunner = new env.ConversationTaskRunner(runService, 3);
  const cloudSession = new env.ChatCloudDialogueSession();
  cloudSession.start({
    kvStore: storage.kv,
    currentConversation: () => convMap.get('conv-local-1') ?? null,
    mutateConversation: (id, update) => runService.mutateStoredConversation(id, update),
    error: (msg) => { throw new Error('Unexpected error: ' + msg); },
    autoScrollEnabled: () => false,
    jumpToBottom: () => {},
    taskRunner: () => taskRunner,
  });

  const ChatComposerSession = loadChatComposerSession({
    ...domain,
    firstImageBlockingIssueForSend: async () => null,
  });
  const ChatSmartUiSession = loadChatSmartUiSession(domain);
  const smartUi = new ChatSmartUiSession();
  smartUi.start({}, () => {});
  const composer = new ChatComposerSession();
  composer.start({
    kvStore: storage.kv,
    runService,
    currentConversation: () => convMap.get('conv-local-1') ?? null,
    isSending: () => runService.snapshot('conv-local-1')?.sending ?? false,
    currentQueue: () => [],
    currentCompletionNotice: () => null,
    setCompletionNotice: () => {},
    error: (msg) => { throw new Error('Composer error: ' + msg); },
    resolveChoice: async () => ({ provider: {}, model: { customHeaders: [], customBodies: [] } }),
    loadOcrSeed: async () => null,
    probeOcrHealth: async () => ({}),
    submit: (cmd) => { runService.start('conv-local-1', cmd); return true; },
    dismissKeyboard: () => {},
    autoScrollEnabled: () => false,
    jumpToBottom: () => {},
    openDeepRead: () => {},
    captureContinuation: () => null,
    continuationAvailable: () => false,
    continuationCurrent: () => true,
    loadMentionRoles: async () => [],
    abilityContext: () => ({}),
    cloudDialogue: () => cloudSession,
  });

  // 1. Send Long Task A via composer
  composer.setInputText('长任务 A：请分析系统架构');
  await composer.send();
  assert.equal(composer.inputText, '', 'Composer should clear immediately after submitting Task A');
  await taskAStep1Started.promise;

  // Verify composer is NOT blocked while Task A is running
  assert.equal(runService.snapshot('conv-local-1')?.sending ?? false, false);
  const activeAfterA = cloudSession.runningTasks();
  assert.equal(activeAfterA.length, 1);
  const taskAId = activeAfterA[0].workItemId;
  assert.equal(activeAfterA[0].status, 'running');
  assert.ok(!activeAfterA[0].progressText.includes('\n'), 'Progress text must be single-line');

  // 2. While Task A is still running Step 1, send Question B (normal message -> independent new question)
  composer.setInputText('短问题 B：1+1等于几？');
  await composer.send();
  assert.equal(composer.inputText, '');

  const tasksInConv = taskRunner.tasksForConversation('conv-local-1');
  assert.equal(tasksInConv.length, 2);
  const taskARec = tasksInConv[0];
  const taskBRec = tasksInConv[1];
  await taskRunner.waitForTask(taskBRec.taskId);

  // Verify Question B finished BEFORE Task A finished!
  assert.equal(taskBRec.status, 'completed');
  assert.equal(taskARec.status, 'running');
  assert.ok(taskBRec.resultText.includes('短问题 B 的回答：1+1=2'));

  // 3. Explicitly select Task A for follow-up (`补充`) and send Follow-up 1 while Task A is still in Step 1
  cloudSession.toggleFollowupTarget(taskAId);
  assert.equal(cloudSession.followupTargetWorkItemId, taskAId);
  composer.setInputText('请重点补充性能部分');
  await composer.send();
  assert.equal(cloudSession.followupTargetWorkItemId, '', 'Follow-up target should reset after sending');

  // Before Step 1 finishes, Follow-up 1 must be in `received` state
  let inputsForConv = taskRunner.inputsForConversation('conv-local-1');
  const followup1 = inputsForConv.find((i) => i.kind === 'followup' && i.text.includes('请重点补充性能部分'));
  assert.ok(followup1);
  assert.equal(followup1.state, 'received');

  // 4. Release Task A Step 1 so Task A reaches its next reasoning boundary and consumes Follow-up 1
  releaseTaskAStep1.resolve();
  await taskAStep2Started.promise;

  assert.equal(followup1.state, 'consumed', 'Follow-up 1 must transition to consumed at reasoning boundary');
  assert.equal(taskAPrompts.length, 2);
  assert.ok(
    taskAPrompts[1].some((txt) => txt.includes('请重点补充性能部分')),
    'Task A Step 2 reasoning must include consumed Follow-up 1',
  );

  // 5. Send Follow-up 2 during Step 2, then immediately stop Task A before Step 2 finishes
  cloudSession.toggleFollowupTarget(taskAId);
  composer.setInputText('未消费的补充说明 2');
  await composer.send();
  inputsForConv = taskRunner.inputsForConversation('conv-local-1');
  const followup2 = inputsForConv.find((i) => i.kind === 'followup' && i.text.includes('未消费的补充说明 2'));
  assert.ok(followup2);
  assert.equal(followup2.state, 'received');

  await cloudSession.stopTask(taskAId);
  await taskRunner.waitForTask(taskAId);

  assert.equal(taskARec.status, 'cancelled');
  assert.equal(followup1.state, 'consumed');
  assert.equal(followup2.state, 'cancelled', 'Unconsumed follow-up must become cancelled when Task A is stopped');

  // Verify anchored node positions in the conversation:
  // [userNodeA, assistantNodeA, userNodeB, assistantNodeB, followupNode1, followupNode2]
  const finalConv = convMap.get('conv-local-1');
  const nodeIds = finalConv.messageNodes.map((n) => n.id);
  const idxUserA = nodeIds.indexOf(taskARec.userNodeId);
  const idxAssistantA = nodeIds.indexOf(taskARec.assistantNodeId);
  const idxUserB = nodeIds.indexOf(taskBRec.userNodeId);
  const idxAssistantB = nodeIds.indexOf(taskBRec.assistantNodeId);
  assert.equal(idxAssistantA, idxUserA + 1, 'Task A result must stay anchored right after User A');
  assert.equal(idxAssistantB, idxUserB + 1, 'Task B result must stay anchored right after User B');

  const assistantAMsg = domain.nodeCurrentMessage(finalConv.messageNodes[idxAssistantA]);
  const assistantBMsg = domain.nodeCurrentMessage(finalConv.messageNodes[idxAssistantB]);
  assert.ok(domain.toText(assistantAMsg).includes('长任务 A 第一阶段'));
  assert.ok(domain.toText(assistantAMsg).includes('长任务 A 第二阶段'));
  assert.equal(domain.toText(assistantBMsg), '短问题 B 的回答：1+1=2');
});

test('local_agent_async: concurrency cap queues excess tasks with visible queued state and auto-starts on slot release, across multiple conversations', async () => {
  const env = createRuntimeEnvironment();
  const conv1 = domain.patchConversation(domain.makeConversation('conv-1'), { title: '会话 1' });
  const conv2 = domain.patchConversation(domain.makeConversation('conv-2'), { title: '会话 2' });
  const { storage, convMap } = createInMemoryStorage([conv1, conv2]);

  const gates = {
    t1: deferred(),
    t2: deferred(),
    t3: deferred(),
  };
  const started = {
    t1: deferred(),
    t2: deferred(),
    t3: deferred(),
  };

  const execution = makeFakeExecution({
    streamHandler: async (_ctx, messages, onChunk) => {
      const userMsgs = messages.filter((m) => m.role === 'user');
      const lastUserText = userMsgs.length > 0 ? domain.toText(userMsgs[userMsgs.length - 1]) : '';
      const key = lastUserText.includes('任务 3') ? 't3' : (lastUserText.includes('任务 2') ? 't2' : 't1');
      started[key].resolve();
      await gates[key].promise;
      const reply = domain.makeUIMessage('assistant', [
        { type: 'text', text: `${key} 完成`, metadata: null },
      ]);
      onChunk({ choices: [{ delta: reply, message: null, finishReason: 'stop' }], usage: null });
    },
  });

  const runService = new env.ConversationRunService(storage, execution);
  // Set concurrency cap = 2
  const taskRunner = new env.ConversationTaskRunner(runService, 2);

  const t1 = await taskRunner.submitQuestion('conv-1', [{ type: 'text', text: '任务 1', metadata: null }], '任务 1');
  const t2 = await taskRunner.submitQuestion('conv-2', [{ type: 'text', text: '任务 2', metadata: null }], '任务 2');
  const t3 = await taskRunner.submitQuestion('conv-1', [{ type: 'text', text: '任务 3', metadata: null }], '任务 3');

  await Promise.all([started.t1.promise, started.t2.promise]);

  // Task 1 (conv-1) and Task 2 (conv-2) are running concurrently; Task 3 exceeds cap=2 and is queued!
  assert.equal(t1.status, 'running');
  assert.equal(t2.status, 'running');
  assert.equal(t3.status, 'queued');
  assert.ok(t3.progressText.includes('[queued]'));
  assert.equal(taskRunner.runningTaskCount(), 2);
  assert.equal(taskRunner.queuedTaskCount(), 1);

  const t3Input = taskRunner.inputsForConversation('conv-1').find((i) => i.taskId === t3.taskId);
  assert.equal(t3Input.state, 'received', 'Queued task initial input is received until scheduled');

  // Complete Task 1 -> Task 3 should automatically start!
  gates.t1.resolve();
  await taskRunner.waitForTask(t1.taskId);
  await started.t3.promise;

  assert.equal(t1.status, 'completed');
  assert.equal(t3.status, 'running');
  assert.equal(t3Input.state, 'consumed', 'Task 3 input transitions to consumed when scheduled');

  gates.t2.resolve();
  gates.t3.resolve();
  await Promise.all([taskRunner.waitForTask(t2.taskId), taskRunner.waitForTask(t3.taskId)]);

  assert.equal(t2.status, 'completed');
  assert.equal(t3.status, 'completed');
  assert.equal(domain.currentMessages(convMap.get('conv-2')).length, 2);
  assert.equal(domain.currentMessages(convMap.get('conv-1')).length, 4);
});

test('local_agent_async: background keep-alive reporting and honest interrupted recovery on restart after OS kill', async () => {
  const env = createRuntimeEnvironment();
  const conv = domain.patchConversation(domain.makeConversation('conv-recover'), { title: '重启恢复测试' });
  const { storage, convMap } = createInMemoryStorage([conv]);

  const inFlightStarted = deferred();
  const neverResolves = deferred();

  const execution = makeFakeExecution({
    streamHandler: async (_ctx, _messages, onChunk) => {
      const chunk = domain.makeUIMessage('assistant', [
        { type: 'text', text: '输出了一半的内容', metadata: null },
      ]);
      onChunk({ choices: [{ delta: chunk, message: null, finishReason: null }], usage: null });
      inFlightStarted.resolve();
      await neverResolves.promise;
    },
  });

  const runService1 = new env.ConversationRunService(storage, execution);
  const runner1 = new env.ConversationTaskRunner(runService1, 1);

  const runningTask = await runner1.submitQuestion(
    'conv-recover',
    [{ type: 'text', text: '将被系统杀掉的任务', metadata: null }],
    '将被系统杀掉的任务',
  );
  await inFlightStarted.promise;

  const followupInput = await runner1.submitFollowup(
    'conv-recover',
    runningTask.taskId,
    [{ type: 'text', text: '未消费的补充', metadata: null }],
    '未消费的补充',
  );
  assert.equal(followupInput.state, 'received');

  // Verify background keep-alive was reported for runningTask.taskId and AppStorage('chatGenerating') is true
  assert.equal(env.appStorageMap.get('chatGenerating'), true);
  assert.ok(
    env.keepAliveEvents.some((e) => e.runId === runningTask.taskId && e.generationActive === true),
    'BackgroundGenerationKeepAlive must receive generationActive=true for local task runId',
  );

  // Simulate OS kill + app restart: instantiate a brand-new RunService + TaskRunner against the persisted storage
  const runService2 = new env.ConversationRunService(storage, execution);
  const runner2 = new env.ConversationTaskRunner(runService2, 2);
  await runner2.ensureHydrated();

  const recoveredTasks = runner2.tasksForConversation('conv-recover');
  assert.equal(recoveredTasks.length, 1);
  assert.equal(recoveredTasks[0].status, 'interrupted');
  assert.ok(recoveredTasks[0].progressText.includes('[interrupted]'));

  const recoveredInputs = runner2.inputsForConversation('conv-recover');
  const recoveredFollowup = recoveredInputs.find((i) => i.requestId === followupInput.requestId);
  assert.ok(recoveredFollowup);
  assert.equal(recoveredFollowup.state, 'cancelled');

  const recoveredConv = convMap.get('conv-recover');
  const assistantNode = recoveredConv.messageNodes.find((n) => n.id === runningTask.assistantNodeId);
  assert.ok(assistantNode);
  const assistantText = domain.toText(domain.nodeCurrentMessage(assistantNode));
  assert.ok(
    assistantText.includes('[任务因应用退出已中断]'),
    `Expected interrupted marker in assistant message, got: ${assistantText}`,
  );
  neverResolves.resolve();
});
