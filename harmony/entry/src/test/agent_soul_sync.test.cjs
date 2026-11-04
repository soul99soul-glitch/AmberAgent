const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
require('../../../chat/node_modules/tsx/dist/cjs/index.cjs');

const domain = require('../../../chat/src/main/ets/index.ts');
const { isResidentTool } = require('../../../chat/src/main/ets/chat/builtin_introspection_tools.ts');
const { toolInvocationPolicy } = require('../../../chat/src/main/ets/chat/tool_policy.ts');
const { PermissionDecisionResolver } = require('../../../chat/src/main/ets/chat/tool_permission.ts');
const { scopedSubAgentTools } = require('../../../chat/src/main/ets/chat/subagent_tool_scope.ts');

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

function createMemoryKv(initial = {}) {
  const map = new Map(Object.entries(initial));
  return {
    async get(key) {
      return map.has(key) ? map.get(key) : null;
    },
    async put(key, value) {
      map.set(key, String(value));
    },
    async delete(key) {
      map.delete(key);
    },
    snapshot() {
      return Object.fromEntries(map.entries());
    },
  };
}

function createAppStorageMock() {
  const store = new Map();
  return {
    get(key) {
      return store.get(key);
    },
    setOrCreate(key, value) {
      store.set(key, value);
    },
    snapshot() {
      return Object.fromEntries(store.entries());
    },
  };
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

function loadAgentSoulSyncModule(gatewayMod, kvStore, appStorageMock) {
  const outputText = transpileEts('../main/ets/platform_impl/AgentSoulSync.ets');
  const exports = {};
  new Function('exports', 'require', 'AppStorage', outputText)(
    exports,
    (name) => {
      if (name === '@amber/chat-domain') return domain;
      if (name === '@kit.PerformanceAnalysisKit') return { hilog: { warn() {}, info() {}, debug() {} } };
      if (name.endsWith('/AppContainer.ets')) return { getChatKvStore: () => kvStore };
      if (name.endsWith('/MacGatewayClient.ets')) return gatewayMod;
      throw new Error(`Unexpected dependency in AgentSoulSync: ${name}`);
    },
    appStorageMock,
  );
  return exports;
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

const TEST_CONN = {
  addrs: ['100.64.0.12'],
  port: 8899,
  fingerprint: 'ABCD1234',
  token: 'bearer-secret-token',
  deviceId: 'dev-harmony-1',
  gatewayId: 'gw-mac-1',
  name: 'Arquiel-Mac',
};

test('AgentSoulStore: local update, 8-snapshot history cap, reversible restore, and reset to Amber', async () => {
  const kv = createMemoryKv();
  const store = new domain.AgentSoulStore(kv);

  const initial = await store.load();
  assert.equal(initial.displayName, 'Amber');
  assert.equal(initial.isDefault, true);
  assert.equal(initial.history.length, 0);

  // Create 10 distinct personas -> history should cap at 8 most recent previous personas
  for (let i = 1; i <= 10; i++) {
    const res = await store.updateLocal({
      displayName: `角色-${i}`,
      tagline: `签名-${i}`,
      soulMarkdown: `你是第 ${i} 号测试角色。`,
      source: 'harmony_ui',
      nowMs: 1000 + i * 10,
    });
    assert.equal(res.changed, true);
  }

  const afterTen = await store.load(true);
  assert.equal(afterTen.displayName, '角色-10');
  assert.equal(afterTen.isDefault, false);
  assert.equal(afterTen.history.length, 8);
  assert.equal(afterTen.history[0].displayName, '角色-9');
  assert.equal(afterTen.history[7].displayName, '角色-2');

  // Restore snapshot '角色-5' -> current '角色-10' archived at top of history
  const targetSnap = afterTen.history.find((h) => h.displayName === '角色-5');
  assert.ok(targetSnap);
  const restored = await store.restoreHistory(targetSnap.id, 'harmony_ui', 2000);
  assert.equal(restored.changed, true);
  assert.equal(restored.state.displayName, '角色-5');
  assert.equal(restored.state.history[0].displayName, '角色-10');
  assert.equal(restored.state.history.length, 8);

  // Reset to default Amber -> '角色-5' archived at top of history
  const reset = await store.resetToDefault('harmony_ui', 3000);
  assert.equal(reset.changed, true);
  assert.equal(reset.state.isDefault, true);
  assert.equal(reset.state.displayName, 'Amber');
  assert.equal(reset.state.tagline, '');
  assert.equal(reset.state.soulMarkdown, '');
  assert.equal(reset.state.history[0].displayName, '角色-5');
});

test('System prompt decouples <agents_md> honest execution rules from <agent_persona> and authorizes agent_soul_update', () => {
  const defaultPrompt = domain.buildAgentSoulPrompt(
    domain.DEFAULT_AGENT_SOUL_MARKDOWN,
    domain.defaultAgentSoulState(),
  );
  assert.ok(defaultPrompt.includes('<agents_md>'));
  assert.ok(defaultPrompt.includes('agent_soul_update'));
  assert.ok(defaultPrompt.includes('切勿拒绝或让用户手动修改 SOUL.md'));
  assert.ok(!defaultPrompt.includes('<agent_persona>'));

  const customState = {
    ...domain.defaultAgentSoulState(),
    displayName: '东坡居士',
    tagline: '竹杖芒鞋轻胜马',
    soulMarkdown: '说话带宋代文人旷达风趣之气，但回答技术问题依然精准利落。',
    isDefault: false,
    revision: 3,
  };
  const customPrompt = domain.buildAgentSoulPrompt(domain.DEFAULT_AGENT_SOUL_MARKDOWN, customState);
  assert.ok(customPrompt.includes('<agents_md>'));
  assert.ok(customPrompt.includes('<agent_persona>'));
  assert.ok(customPrompt.includes('角色称呼：东坡居士'));
  assert.ok(customPrompt.includes('一句话签名：竹杖芒鞋轻胜马'));
  assert.ok(customPrompt.includes('说话带宋代文人旷达风趣之气'));
  // Backward compatibility: single-arg call omits persona section cleanly
  const singleArg = domain.buildAgentSoulPrompt('Core rule only');
  assert.ok(singleArg.includes('<agents_md>\nCore rule only\n</agents_md>'));
  assert.ok(!singleArg.includes('形象人设与自塑授权'));
});

test('Always-Loaded tools: agent_soul_get & agent_soul_update stay resident, survive minimal profile, and execute without approval', async () => {
  const kv = createMemoryKv();
  const store = new domain.AgentSoulStore(kv);
  const tools = domain.createAgentSoulCoreTools({
    getState: () => store.load(),
    updateFromChat: (patch) => store.updateLocal(patch),
    resetFromChat: () => store.resetToDefault('harmony_chat'),
  });
  assert.equal(tools.length, 2);

  for (const tool of tools) {
    assert.equal(isResidentTool(tool.name, 'prompt_config'), true, `${tool.name} must be resident`);
    const policy = toolInvocationPolicy(tool, { display_name: '苏轼' });
    assert.equal(policy.needsApproval, false, `${tool.name} must not require approval`);
    assert.equal(policy.autoApprovable, true, `${tool.name} must be auto-approvable`);

    const resolver = new PermissionDecisionResolver();
    const toolPart = {
      type: 'tool',
      toolCallId: `call-${tool.name}`,
      toolName: tool.name,
      input: JSON.stringify({ display_name: '苏轼' }),
      output: [],
      approvalState: { type: 'auto' },
      metadata: null,
    };
    const decision = resolver.resolve(tool, toolPart, false, false, [], 'normal');
    assert.equal(decision.action, 'allow', `${tool.name} must execute directly without user approval prompt`);
  }

  // Survives minimal profile filtering
  const filtered = domain.filterToolProfile(tools, 'minimal');
  assert.equal(filtered.tools.length, 2);

  // Subagents cannot mutate user's main persona
  const subTools = scopedSubAgentTools(tools);
  assert.equal(subTools.some((t) => t.name === 'agent_soul_update'), false);
  assert.equal(subTools.some((t) => t.name === 'agent_soul_get'), true);

  // Execute agent_soul_update & agent_soul_get round-trip
  const updateTool = tools.find((t) => t.name === 'agent_soul_update');
  const getTool = tools.find((t) => t.name === 'agent_soul_get');
  const updateParts = await updateTool.execute({
    display_name: '林黛玉',
    tagline: '潇湘妃子',
    soul_markdown: '言辞机敏细腻。',
  });
  const updatedJson = JSON.parse(updateParts[0].text);
  assert.equal(updatedJson.status, 'ok');
  assert.equal(updatedJson.display_name, '林黛玉');
  assert.equal(updatedJson.is_default, false);
  assert.equal(updatedJson.source, 'harmony_chat');

  const getParts = await getTool.execute({});
  const gotJson = JSON.parse(getParts[0].text);
  assert.equal(gotJson.displayName, '林黛玉');
  assert.equal(gotJson.tagline, '潇湘妃子');

  const resetParts = await updateTool.execute({ reset_to_default: true });
  const resetJson = JSON.parse(resetParts[0].text);
  assert.equal(resetJson.is_default, true);
  assert.equal(resetJson.display_name, 'Amber');
});

test('C-SOUL-V1 bidirectional sync: MacGatewayClient /v2/soul + anti-loopback pull + revision==0 seed + 404 silent degrade', async () => {
  const kv = createMemoryKv();
  const appStorage = createAppStorageMock();
  let remoteSoul = {
    revision: 2,
    updatedAtMs: 1700000002000,
    displayName: '云端孔明',
    tagline: '鞠躬尽瘁',
    soulMarkdown: '运筹帷幄之中。',
    isDefault: false,
    source: 'macos_ui',
    history: [],
  };
  let return404 = false;

  const encodeJson = (obj) => new TextEncoder().encode(JSON.stringify(obj)).buffer;
  const { mod: gatewayMod, rcpCalls } = loadMacGatewayModule(async (req) => {
    if (return404) {
      return { statusCode: 404, body: encodeJson({ error: 'not_found' }) };
    }
    if (req.method === 'GET' && req.url.endsWith('/v2/soul')) {
      return { statusCode: 200, body: encodeJson(remoteSoul) };
    }
    if (req.method === 'PUT' && req.url.endsWith('/v2/soul')) {
      const body = JSON.parse(req.body);
      remoteSoul = {
        revision: remoteSoul.revision + 1,
        updatedAtMs: body.updatedAtMs,
        displayName: body.displayName,
        tagline: body.tagline,
        soulMarkdown: body.soulMarkdown,
        isDefault: body.isDefault,
        source: body.source,
        history: remoteSoul.history,
      };
      return { statusCode: 200, body: encodeJson(remoteSoul) };
    }
    throw new Error(`Unexpected request: ${req.method} ${req.url}`);
  }, kv);

  const syncMod = loadAgentSoulSyncModule(gatewayMod, kv, appStorage);
  const store = new domain.AgentSoulStore(kv);
  const sync = new syncMod.AgentSoulCloudSynchronizer({
    store,
    loadConnection: async () => TEST_CONN,
    clientFactory: (c) => gatewayMod.MacGatewayClient.of(c),
  });

  // 1) Remote soulRevision > localLastSyncedRevision -> pulls GET /v2/soul, updates AppStorage, ZERO PUT loopback
  await sync.observeRemoteSoulRevision(2, TEST_CONN);
  assert.equal(rcpCalls.length, 1);
  assert.equal(rcpCalls[0].method, 'GET');
  assert.equal(rcpCalls[0].headers.authorization, 'Bearer bearer-secret-token');
  assert.equal(rcpCalls[0].configuration.security.certificatePinning.publicKeyHash, 'ABCD1234');

  const pulled = await store.load();
  assert.equal(pulled.displayName, '云端孔明');
  assert.equal(pulled.localLastSyncedRevision, 2);
  assert.equal(appStorage.get('agentSoulDisplayName'), '云端孔明');
  assert.equal(appStorage.get('agentSoulIsDefault'), false);

  // Observing same soulRevision=2 again -> no extra GET or PUT
  await sync.observeRemoteSoulRevision(2, TEST_CONN);
  assert.equal(rcpCalls.length, 1);

  // 2) Local chat update -> sends PUT /v2/soul with source='harmony_chat'
  await sync.updateLocalAndSync({
    displayName: '鸿蒙李白',
    tagline: '诗仙',
    soulMarkdown: '天生我材必有用。',
    source: 'harmony_chat',
    nowMs: 1700000005000,
  }, 'harmony_chat');
  // Wait microtasks for fire-and-forget pushStateToCloud
  await new Promise((r) => setTimeout(r, 20));
  const putCalls = rcpCalls.filter((c) => c.method === 'PUT');
  assert.equal(putCalls.length, 1);
  assert.equal(JSON.parse(putCalls[0].body).source, 'harmony_chat');
  assert.equal((await store.load()).localLastSyncedRevision, 3);

  // 3) First connect when server revision == 0 and local has custom non-default persona -> seeds cloud once
  const kvSeed = createMemoryKv();
  const storeSeed = new domain.AgentSoulStore(kvSeed);
  await storeSeed.updateLocal({
    displayName: '本地既有角色',
    tagline: '离线创建',
    soulMarkdown: '先于配对存在的人设。',
    source: 'harmony_ui',
    nowMs: 1700000009000,
  });
  remoteSoul = {
    revision: 0,
    updatedAtMs: 0,
    displayName: 'Amber',
    tagline: '',
    soulMarkdown: '',
    isDefault: true,
    source: 'default',
    history: [],
  };
  rcpCalls.length = 0;
  const syncSeed = new syncMod.AgentSoulCloudSynchronizer({
    store: storeSeed,
    loadConnection: async () => TEST_CONN,
    clientFactory: (c) => gatewayMod.MacGatewayClient.of(c),
  });
  await syncSeed.syncFromCloudOnce(TEST_CONN);
  assert.equal(rcpCalls.length, 2);
  assert.equal(rcpCalls[0].method, 'GET');
  assert.equal(rcpCalls[1].method, 'PUT');
  assert.equal(JSON.parse(rcpCalls[1].body).displayName, '本地既有角色');
  assert.equal(JSON.parse(rcpCalls[1].body).source, 'harmony_ui');
  assert.equal((await storeSeed.load()).localLastSyncedRevision, 1);

  // 4) Remote 404 silently degrades without throwing
  return404 = true;
  await assert.doesNotReject(() => syncSeed.syncFromCloudOnce(TEST_CONN));
  await assert.doesNotReject(async () => {
    await syncSeed.updateLocalAndSync({
      displayName: '离线降级角色',
      source: 'harmony_ui',
    }, 'harmony_ui');
    await new Promise((r) => setTimeout(r, 20));
  });
  assert.equal((await storeSeed.load()).displayName, '离线降级角色');
});

test('ChatCloudDialogueSession forwards connected gateway and polled soulRevision to AgentSoul hooks', async () => {
  const kv = createMemoryKv({
    mac_gateway_connection_v1: JSON.stringify(TEST_CONN),
    mac_gateway_cloud_dialogue_enabled_v1: 'true',
  });
  const { mod: gatewayMod } = loadMacGatewayModule(async () => ({
    statusCode: 200,
    body: new TextEncoder().encode(JSON.stringify({ items: [], nextCursor: 0, soulRevision: 7 })).buffer,
  }), kv);
  const dialogueMod = loadCloudDialogueModule(gatewayMod);

  let connectedCount = 0;
  const observedRevisions = [];
  const session = new dialogueMod.ChatCloudDialogueSession();
  session.start({
    kvStore: kv,
    currentConversation: () => null,
    mutateConversation: async () => null,
    error: () => {},
    autoScrollEnabled: () => false,
    jumpToBottom: () => {},
    onDialogueConnected: (c) => {
      assert.equal(c.gatewayId, 'gw-mac-1');
      connectedCount++;
    },
    onSoulRevision: (rev) => {
      observedRevisions.push(rev);
    },
  });
  await new Promise((r) => setTimeout(r, 30));
  assert.equal(connectedCount, 1);
  assert.deepEqual(observedRevisions, [7]);
  session.dispose();
});
