// Actual Entry runner/store + OpenAI SSE + tool loop/dispatcher; controlled HTTP only.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const syncFs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const vm = require('node:vm');
const crypto = require('node:crypto');
const ts = require('typescript');

test('Council seats persist each approval/effect and feed actual search results to their own fixed model', async () => {
  const chat = await import('../main/ets/index.ts');
  const deepread = await import('../../../deepread/src/main/ets/index.ts');
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'e11-council-consumer-'));
  const entry = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl');
  const loaded = new Map();
  const reviewCalls = []; const jevKv = chat.createMemoryKeyValueStore();
  await chat.saveJevSettings(jevKv, chat.makeJevSettings({ mode: 'active', autoApproval: { mode: 'active', allowTaskText: true, allowToolMetadata: true } }));
  const unavailable = () => { throw new Error('Unavailable native host in controlled fixture'); };
  const imports = { '@amber/chat-domain': chat, '@amber/deepread-domain': deepread,
    './EntryLocalToolPorts.ets': { sha256HexUtf8: s => crypto.createHash('sha256').update(s).digest('hex') },
    '@kit.ArkTS': { util: {} },
    '../di/AppContainer.ets': { getChatKvStore: () => jevKv },
    './JevSupport.ets': { jevEvaluatePurpose: async (_purpose, state) => {
      reviewCalls.push(state); return { ok: true, shadow: false, reason: '', evaluation: { model: 'controlled-judge', usage: null,
        answers: { destructive: { kind: 'noul', probability: .9 }, exfiltration: { kind: 'noul', probability: 0 },
          offTask: { kind: 'noul', probability: 0 }, authorized: { kind: 'noul', probability: 0 } } } }; } },
    '@kit.LocalizationKit': { i18n: {} },
    './AtomicJsonFile.ets': { sharedKvMutex: {} },
    './JsSandboxTool.ets': { createJavascriptExecuteTool: () => chat.makeAgentTool({ name: 'javascript_execute', description: '', execute: unavailable }) },
    './ArtifactPorts.ets': { createArtifactHttpPort: () => ({}), createArtifactImagePort: () => ({}), createDeflateRawPort: () => unavailable },
    './EntryDocumentParser.ets': { inflateRawEntry: unavailable, createEntryXmlPull: unavailable },
    './NativePluginJsTransport.ets': { createNativePluginJsTransport: () => ({ start: unavailable, reply: unavailable, reject: unavailable, cancel: () => {}, hasSession: () => false }) },
    './PluginHttpPort.ets': { createEntryPluginHttpPort: () => ({ execute: unavailable }) },
    './PluginWebMountGate.ets': { createEntryPluginWebMountPort: () => ({ withScope: unavailable }) },
    './WebMountSession.ets': { getWebMountSession: () => null } };
  const load = name => {
    if (imports[name]) return imports[name];
    if (loaded.has(name)) return loaded.get(name);
    const exports = {}; loaded.set(name, exports);
    const filename = path.join(entry, name);
    const code = ts.transpileModule(syncFs.readFileSync(filename, 'utf8'), { compilerOptions: {
      module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022,
    } }).outputText;
    vm.runInNewContext(code, { exports, require: load, Error, Promise, Map, JSON, String, Date, Array }, { filename });
    return exports;
  };
  const disk = {
    writes: [], fail: null, partial: false,
    async readText(p) { try { return await fs.readFile(path.join(directory, p), 'utf8'); }
      catch (e) { if (e.code === 'ENOENT') return null; throw e; } },
    async writeText(p, text) {
      if (this.fail?.(text)) throw new Error('controlled disk failure');
      this.writes.push({ p, text }); await fs.mkdir(path.dirname(path.join(directory, p)), { recursive: true });
      await fs.writeFile(path.join(directory, p), this.partial ? text.slice(0, 12) : text);
    },
  };
  try {
    const stores = load('./CouncilSeatStore.ets'), runners = load('./CouncilToolRunner.ets');
    const { createEntryAbortController } = load('./EntryAbortController.ets');
    const effects = [], wire = [], executions = new Map(), snapshots = [];
    const scripts = new Map();
    const call = (id, name, args) => ({ id, type: 'function', function: { name,
      arguments: JSON.stringify(name === 'file_write' ? { content: '真实workspace写入', ...args } : args) } });
    const http = {
      async fetch(request) {
        assert.equal(request.url, 'https://api.tavily.com/search');
        return { status: 200, headers: {}, body: JSON.stringify({ results: [
          { title: '真实搜索输出', url: 'https://docs.example.test/中文-page', content: '受控HTTP来源内容' },
        ] }) };
      },
      async fetchStream(request, options) {
        const body = JSON.parse(request.body); wire.push({ request, body, signal: options.signal });
        const script = scripts.get(body.model); assert.ok(script, body.model);
        const next = script.shift(); assert.ok(next, `unexpected model replay: ${body.model}`);
        const delta = next.calls ? { role: 'assistant', tool_calls: next.calls.map((c, index) => ({ index, ...c })) }
          : { role: 'assistant', content: next.text };
        const event = `data: ${JSON.stringify({ id: 'controlled', choices: [{ index: 0, delta,
          finish_reason: next.calls ? 'tool_calls' : 'stop' }] })}\n\ndata: [DONE]\n\n`;
        options.onChunk(new TextEncoder().encode(event).buffer, true);
        return { status: 200, headers: {}, body: '' };
      },
    };
    chat.initSearchSdk(http);
    const settings = { enableWebSearch: true, searchCommonOptions: { resultSize: 1 },
      searchServices: [chat.searchServiceOptionsFromJson({ type: 'tavily', id: 'tavily', apiKey: 'controlled' })],
      searchEnabledServiceIds: ['tavily'], searchServiceSelected: 0,
      searchBuiltinJinaEnabled: false, searchBuiltinDuckDuckGoEnabled: false, searchBuiltinBingEnabled: false,
      searchBuiltinWikipediaEnabled: false, searchBuiltinHackerNewsEnabled: false, searchGoogleWebViewFallbackEnabled: false };
    const workspaceRoot = path.join(directory, 'workspace'); await fs.mkdir(workspaceRoot);
    const workspace = new chat.PosixWorkspaceManager({ rootAbs: workspaceRoot, port: {
      exists: syncFs.existsSync, isDirectory: p => syncFs.existsSync(p) && syncFs.statSync(p).isDirectory(),
      isFile: p => syncFs.existsSync(p) && syncFs.statSync(p).isFile(),
      listNames: p => syncFs.readdirSync(p).map(name => ({ name, directory: syncFs.statSync(path.join(p, name)).isDirectory() })),
      readText: p => syncFs.readFileSync(p, 'utf8'), readBytes: p => syncFs.readFileSync(p),
      writeText: (p, content, append) => {
        const entry = disk.writes.findLast(w => w.text.includes(path.basename(p)));
        assert.match(syncFs.readFileSync(path.join(directory, entry.p), 'utf8'), /council_started_v1/);
        // PosixWorkspaceManager.createFile touches a missing file with an empty write
        // before the payload write (one tool execution, two port calls); record only
        // real payload writes so effects stay 1:1 with actual tool executions.
        if (content.length > 0 || syncFs.existsSync(p)) effects.push(path.basename(p));
        syncFs.writeFileSync(p, content, { flag: append ? 'a' : 'w' });
      }, writeBytes: unavailable, fileSize: p => syncFs.statSync(p).size,
      mkdirs: p => syncFs.mkdirSync(p, { recursive: true }), rename: syncFs.renameSync, copyFile: syncFs.copyFileSync,
      deleteRecursively: p => { syncFs.rmSync(p, { recursive: true, force: true }); return true; },
    } });
    const assembly = load('./CouncilAssembly.ets');
    const recipeManifest = { schema: 'amber.recipe.v1', name: 'lookup', version: '1', description: 'Search without exporting item fields',
      inputs: {}, steps: [{ id: 'lookup', tool: 'search_web', arguments: { query: '中文', depth: 'quick' } }], outputs: {} };
    const descriptor = { manifest: recipeManifest, hash: 'pinned-lookup', canonicalJSON: chat.canonicalRecipeJSON(recipeManifest) };
    const recipeStore = { listInstalled: async () => [{ descriptor, enabled: true }] };
    const pluginStore = { listInstalled: async () => [] };
    const missing = name => chat.makeAgentTool({ name, description: 'unmounted host', execute: unavailable });
    const runner = runners.createCouncilToolRunner({ http, offRunner: { generate: async () => ({ text: 'OFF', warnings: [] }) },
      prepareSeat: async req => {
        const store = stores.createCouncilSeatStore(disk, req.key);
        const conversation = chat.makeConversation(`seat-${req.key.seatId}`, [], { assistantId: 'frozen-assistant' });
        const assistant = chat.makeAssistant({ id: 'frozen-assistant', toolProfile: 'minimal', localTools: ['workspace_files', 'ask_user'] });
        const runtime = load('./AgentRuntimePrefs.ets').defaultAgentRuntimeSnapshot();
        runtime.autoApproveAllToolCalls = req.key.seatId === 'jev'; runtime.autoApproveHighRiskToolCalls = req.key.seatId === 'jev';
        runtime.speculativeToolExecution.enabled = false;
        let liveAssistant = assistant;
        const deps = { assistant, runtime, activityStore: new chat.AgentToolActivityStore(),
          local: { workspace, terminalRuntime: null, moshRuntime: null, sshProfiles: null, pythonRuntime: null },
          skills: { enabledSkills: [], allSkills: [], skillManager: {}, setSkillEnabled: unavailable, workspace: {}, inflateRaw: unavailable },
          mcpManagement: { settings: { getMcpServers: () => [], getCurrentAssistantMcpServerIds: () => [],
            updateMcpServers: unavailable, subscribeMcpServers: () => () => {} }, manager: {}, skills: {} },
          mcpTools: [], memory: null, searchSettings: settings, feishuEnabled: false, imageGenConfigured: false,
          context: null, history: null, recipeStore, pluginStore,
          remainingToolsFactory: async () => ['wm_open', 'javascript_execute', 'conversation_queue_status', 'subagent_run', 'deep_read_open'].map(missing),
          readLiveScope: async () => ({ assistant: liveAssistant, runtime, searchEnabled: true,
            feishuEnabled: false, imageGenConfigured: false, mcpTargets: [] }) };
        const execution = { store, conversation, assistant, makeLoop: async (makeProviderForStep, toolPromptModel, beforeEffect) => {
          const loop = await assembly.createCouncilSeatLoop(deps, conversation, store,
            makeProviderForStep, toolPromptModel, req.toolMode, beforeEffect);
          execution.loop = loop; return loop;
        } };
        execution.setLiveAssistant = value => { liveAssistant = value; };
        executions.set(req.key.seatId, execution); return execution;
      } });
    const req = (seat, mode, controller = createEntryAbortController()) => ({ key: { runId: 'run', round: 1, seatId: seat },
      toolMode: mode, model: { id: seat, label: seat, baseUrl: `https://${seat}.model.test`, apiKey: 'controlled-seat', model: seat },
      systemPrompt: '席位固定system', userPrompt: '搜索并汇总', temperature: 0.3, reasoningLevel: 'low',
      outputBudgetChars: 500, signal: controller.signal, onChunk: () => {},
      onTools: snapshot => snapshots.push(snapshot), requestApproval: async () => ({ kind: 'approved', reason: '', answer: null }) });

    scripts.set('search', [{ calls: [call('search-call', 'search_web', { query: '中文', depth: 'quick' })] }, { text: '已读取来源' }]);
    const search = await runner.generate(req('search', 'search'));
    assert.equal(search.text, '已读取来源'); assert.equal(search.sources[0].title, '真实搜索输出');
    assert.equal(search.sources[0].service, 'Tavily');
    const feedback = wire.filter(w => w.body.model === 'search'); assert.equal(feedback.length, 2);
    assert.equal(feedback[0].request.url, 'https://search.model.test/v1/chat/completions');
    assert.equal(feedback[0].body.temperature, 0.3); assert.equal(feedback[0].body.reasoning_effort, 'low');
    assert.equal(feedback[0].body.tools.length, 4);
    assert.match(JSON.stringify(feedback[1].body.messages), /受控HTTP来源内容/);

    scripts.set('full', [{ calls: [call('yes', 'file_write', { path: 'approved' }), call('no', 'file_write', { path: 'denied' }),
      call('question', 'ask_user', { questions: [{ id: 'choice', question: '选择?', options: ['甲', '乙'] }] })] }, { text: '确认与拒绝均已收口' }]);
    const fullReq = req('full', 'full'), asked = [];
    fullReq.requestApproval = async request => {
      const saved = await executions.get('full').store.load();
      const part = chat.currentMessages(saved).find(m => m.id === request.messageId).parts[request.partIndex];
      assert.equal(part.approvalState.type, 'pending'); assert.equal(part.output.length, 0);
      assert.ok(request.subjectHash); asked.push(request.toolCallId);
      assert.equal(effects.length, 0); // No sibling effect before all individual saved decisions.
      return request.toolCallId === 'no' ? { kind: 'denied', reason: '拒绝', answer: null }
        : request.toolCallId === 'question' ? { kind: 'answered', reason: '', answer: '甲' }
        : { kind: 'approved', reason: '', answer: null };
    };
    const full = await runner.generate(fullReq); assert.deepEqual(asked, ['yes', 'no', 'question']);
    assert.deepEqual(effects, ['approved']); assert.match(JSON.stringify(full.toolMessages), /甲/);
    const start = disk.writes.find(w => w.text.includes('council_started_v1') && w.text.includes('"yes"'));
    assert.ok(start, 'STARTED was durably written');

    scripts.set('bad-disk', [{ calls: [call('blocked', 'file_write', { path: 'must-not-write' })] }]);
    disk.fail = text => text.includes('council_started_v1') && text.includes('must-not-write');
    await assert.rejects(runner.generate(req('bad-disk', 'full')), /controlled disk failure/);
    disk.fail = null; assert.deepEqual(effects, ['approved']);

    const fullExecution = executions.get('full');
    const frozenTools = fullExecution.loop.tools.map(t => t.name);
    assert.ok(frozenTools.includes('file_write')); assert.equal(fullExecution.assistant.toolProfile, 'minimal');
    for (const name of ['wm_open', 'javascript_execute', 'conversation_queue_status', 'subagent_run', 'deep_read_open']) assert.ok(!frozenTools.includes(name));
    fullExecution.setLiveAssistant(chat.patchAssistant(fullExecution.assistant, { localTools: ['workspace_files', 'ask_user', 'python'] }));
    assert.ok(!(await fullExecution.loop.refreshTools()).some(t => t.name === 'python_execute'));
    fullExecution.setLiveAssistant(chat.patchAssistant(fullExecution.assistant, { localTools: ['ask_user'] }));
    assert.ok(!(await fullExecution.loop.refreshTools()).some(t => t.name === 'file_write'));

    scripts.set('recipe', [{ calls: [call('wrapper', 'recipe__lookup', {})] }, { text: '使用Recipe真实来源' }]);
    const recipe = await runner.generate(req('recipe', 'full'));
    assert.equal(recipe.sources[0].title, '真实搜索输出');
    assert.equal(recipe.sources[0].service, 'Tavily');
    assert.doesNotMatch(JSON.stringify(recipe.toolMessages[0].parts.find(p => p.type === 'tool').output), /source_service/);

    scripts.set('changed', [{ calls: [call('changed-call', 'file_write', { path: 'old-subject' })] }]);
    const changedReq = req('changed', 'full');
    changedReq.requestApproval = async request => {
      const store = executions.get('changed').store, latest = await store.load();
      const message = chat.currentMessages(latest).find(m => m.id === request.messageId);
      message.parts[request.partIndex].input = '{"path":"new-subject"}';
      await store.save(latest);
      return { kind: 'approved', reason: '', answer: null };
    };
    await assert.rejects(runner.generate(changedReq), /subject changed/);
    assert.deepEqual(effects, ['approved']);

    const a = createEntryAbortController(), aReq = req('a', 'full', a);
    scripts.set('a', [{ calls: [call('a-call', 'file_write', { path: 'a-must-not-write' })] }]);
    let answerA, readyA; const pendingA = new Promise(resolve => { readyA = resolve; });
    aReq.requestApproval = () => { readyA(); return new Promise(resolve => { answerA = resolve; }); };
    const aResult = runner.generate(aReq).then(() => null, e => e);
    await pendingA; a.abort();
    scripts.set('b', [{ calls: [call('b-call', 'file_write', { path: 'b-write' })] }, { text: '独立B完成' }]);
    const b = await runner.generate(req('b', 'full')); assert.equal(b.text, '独立B完成');
    answerA({ kind: 'approved', reason: '', answer: null }); assert.equal((await aResult).name, 'AbortError');
    assert.deepEqual(effects, ['approved', 'b-write']);
    const aSaved = await executions.get('a').store.load(); assert.match(JSON.stringify(aSaved), /a-must-not-write/);
    assert.doesNotMatch(JSON.stringify(aSaved), /b-write/);
    await assert.rejects(runner.generate(req('a', 'full')), /interrupted/i);


    // Actual Assembly -> Entry gate -> saved seat pending -> original requestApproval -> effect once.
    scripts.set('jev', [{ calls: [call('jev-call', 'file_write', { path: 'jev-write' })] }, { text: '复核后完成' }]);
    const reviewReq = req('jev', 'full'); let reviewApprovals = 0;
    reviewReq.requestApproval = async request => {
      const saved = await executions.get('jev').store.load();
      const part = chat.currentMessages(saved).find(m => m.id === request.messageId).parts[request.partIndex];
      assert.equal(part.approvalState.type, 'pending'); assert.equal(part.output.length, 0);
      assert.match(JSON.stringify(part.metadata.permission_trace), /jev_auto_approval.*破坏性/);
      assert.deepEqual(effects, ['approved', 'b-write']); reviewApprovals++;
      return { kind: 'approved', reason: '', answer: null };
    };
    const reviewedSeat = await runner.generate(reviewReq);
    assert.equal(reviewedSeat.text, '复核后完成'); assert.equal(reviewApprovals, 1); assert.equal(reviewCalls.length, 1); assert.match(JSON.stringify(reviewCalls[0].user_requests), /搜索并汇总/);
    assert.deepEqual(effects, ['approved', 'b-write', 'jev-write']);

    const partial = stores.createCouncilSeatStore(disk, { runId: '../unsafe', round: 1, seatId: '../../escape' });
    disk.partial = true;
    await assert.rejects(partial.save(chat.makeConversation('partial')), /incomplete/i);
    assert.ok(disk.writes.every(w => /^model-council\/seats\/[a-f0-9]{64}\.json$/.test(w.p)));
  } finally { await fs.rm(directory, { recursive: true, force: true }); }
});
