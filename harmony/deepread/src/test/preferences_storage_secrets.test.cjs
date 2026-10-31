// PreferencesStorage 静息凭据边界的真实适配器测试。
// 用 ts.transpileModule + vm 直接跑 entry 里的 PreferencesStorage.ets(与
// deepread_reader.test.cjs 同一套手法),凭据库用内存实现,Preferences 用 Map。
// 目的不是复述 codec 单测,而是验证「落盘那一刻」真的没有明文,以及迁移/清空/失败
// 三种路径在适配器层成立。

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const adapterPath = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl/PreferencesStorage.ets');
const codecPath = path.resolve(__dirname, '../../../chat/src/main/ets/chat/secret_fields_blob.ts');

const compile = text => ts.transpileModule(text, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;

const loadCodec = () => {
  const exports = {};
  vm.runInNewContext(compile(fs.readFileSync(codecPath, 'utf8')), { exports }, { filename: codecPath });
  return exports;
};

const memoryVault = (options = {}) => {
  const entries = new Map();
  const keyOf = (ownerKey, field) => `${ownerKey}/${field}`;
  const state = {
    discarded: 0,
    readError: options.readError ?? null,
    writeError: options.writeError ?? null,
  };
  return {
    entries, state,
    read: async (ownerKey, field) => {
      if (state.readError) throw new Error(state.readError);
      if (options.failReads) throw new Error('asset_unavailable');
      const value = entries.get(keyOf(ownerKey, field));
      return value === undefined ? null : value;
    },
    write: async (ownerKey, field, value) => {
      if (state.writeError) throw new Error(state.writeError);
      if (options.failWrites) throw new Error('asset_unavailable');
      entries.set(keyOf(ownerKey, field), value);
    },
    remove: async (ownerKey, field) => { entries.delete(keyOf(ownerKey, field)); },
    listOwnerKeys: async () => [...new Set([...entries.keys()].map(key => key.slice(0, key.lastIndexOf('/'))))],
    discardAll: async () => {
      state.discarded += 1;
      state.readError = null;
      state.writeError = null;
      entries.clear();
    },
  };
};

const loadAdapter = (vault) => {
  const prefs = new Map();
  const exports = {};
  const chatDomain = loadCodec();
  vm.runInNewContext(compile(fs.readFileSync(adapterPath, 'utf8')), {
    exports,
    AppStorage: { get: () => ({ filesDir: '/stub' }) },
    require: spec => {
      if (spec === '@kit.ArkData') {
        return { preferences: { getPreferences: async () => ({
          get: async (key, fallback) => (prefs.has(key) ? prefs.get(key) : fallback),
          put: async (key, value) => { prefs.set(key, value); },
          delete: async (key) => { prefs.delete(key); },
          flush: async () => {},
          getAll: async () => Object.fromEntries(prefs),
        }) } };
      }
      if (spec === '@amber/chat-domain') return chatDomain;
      // 只用到 KeyedAsyncMutex;用最小的串行实现替身,不复制生产代码
      if (spec === './AtomicJsonFile.ets') {
        return { KeyedAsyncMutex: class {
          constructor() { this.tails = new Map(); }
          withLock(key, op) {
            const previous = this.tails.get(key) ?? Promise.resolve();
            const run = previous.then(op, op);
            this.tails.set(key, run.then(() => {}, () => {}));
            return run;
          }
        } };
      }
      return {};
    },
  }, { filename: adapterPath });
  return { storage: exports.createPreferencesStorage(vault), prefs };
};

const providersBlob = (apiKey) => JSON.stringify([{ id: 'p1', name: 'Provider', type: 'openai',
  apiKey, models: [], baseUrl: 'https://api.example.com/v1' }]);

test('a legacy plaintext providers blob is encrypted on first read and survives repeated reads', async () => {
  const vault = memoryVault();
  const { storage, prefs } = loadAdapter(vault);
  prefs.set('providers', providersBlob('sk-legacy-provider'));

  const first = await storage.get('providers', null);
  assert.equal(JSON.parse(first)[0].apiKey, 'sk-legacy-provider', 'caller still sees the key');
  assert.equal(prefs.get('providers').includes('sk-legacy-provider'), false, 'Preferences must not keep the plaintext key');
  assert.equal(vault.entries.get('providers/p1/apiKey'), 'sk-legacy-provider');

  const second = await storage.get('providers', null);
  assert.equal(JSON.parse(second)[0].apiKey, 'sk-legacy-provider');
  assert.equal(prefs.get('providers').includes('sk-legacy-provider'), false, 'repeated reads stay ciphertext');
  assert.equal(vault.entries.get('providers/p1/apiKey'), 'sk-legacy-provider', 'repeated reads never drop the secret');
});

test('writing a provider stores no plaintext and clearing the key removes the secret', async () => {
  const vault = memoryVault();
  const { storage, prefs } = loadAdapter(vault);
  await storage.set('providers', providersBlob('sk-new'));
  assert.equal(prefs.get('providers').includes('sk-new'), false);
  assert.equal(vault.entries.get('providers/p1/apiKey'), 'sk-new');
  assert.equal(JSON.parse(await storage.get('providers', null))[0].apiKey, 'sk-new');

  await storage.set('providers', providersBlob(''));
  assert.equal(vault.entries.has('providers/p1/apiKey'), false, 'cleared key must not stay in the vault');
  assert.equal(JSON.parse(await storage.get('providers', null))[0].apiKey, '');
});

test('scalar *_api_key values are sealed and re-read without being deleted', async () => {
  const vault = memoryVault();
  const { storage, prefs } = loadAdapter(vault);

  await storage.set('ai_api_key', 'sk-scalar');
  assert.equal(prefs.get('ai_api_key'), '', 'Preferences keeps only an empty placeholder');
  assert.equal(vault.entries.get('scalar/ai_api_key/apiKey'), 'sk-scalar');
  // 回归护栏:第二次读曾经会以空串调用 seal 而删掉真值
  assert.equal(await storage.get('ai_api_key', ''), 'sk-scalar');
  assert.equal(await storage.get('ai_api_key', ''), 'sk-scalar');
  assert.equal(vault.entries.get('scalar/ai_api_key/apiKey'), 'sk-scalar');

  await storage.set('tavily_api_key', 'tvly-1');
  assert.equal(prefs.get('tavily_api_key'), '');
  assert.equal(await storage.get('tavily_api_key', ''), 'tvly-1');

  await storage.set('ai_api_key', '');
  assert.equal(vault.entries.has('scalar/ai_api_key/apiKey'), false);
  assert.equal(await storage.get('ai_api_key', ''), '');
});

test('a legacy plaintext scalar migrates on read and never returns to Preferences', async () => {
  const vault = memoryVault();
  const { storage, prefs } = loadAdapter(vault);
  prefs.set('ai_api_key', 'sk-old-plaintext');
  assert.equal(await storage.get('ai_api_key', ''), 'sk-old-plaintext');
  assert.equal(prefs.get('ai_api_key'), '');
  assert.equal(vault.entries.get('scalar/ai_api_key/apiKey'), 'sk-old-plaintext');
});

test('an unavailable vault rejects the read instead of returning an empty credential', async () => {
  const vault = memoryVault({ failReads: true });
  const { storage, prefs } = loadAdapter(vault);
  prefs.set('ai_api_key', '');
  await assert.rejects(() => storage.get('ai_api_key', ''), /asset_unavailable/);

  const { storage: writer, prefs: writerPrefs } = loadAdapter(memoryVault({ failWrites: true }));
  await assert.rejects(() => writer.set('ai_api_key', 'sk-x'), /asset_unavailable/);
  assert.equal(writerPrefs.has('ai_api_key'), false, 'a failed seal must not persist a blanked value');
});

test('non-secret keys are stored verbatim and deleting a sealed blob prunes only its namespace', async () => {
  const vault = memoryVault();
  const { storage, prefs } = loadAdapter(vault);
  await storage.set('assistants', '[{"id":"a"}]');
  assert.equal(prefs.get('assistants'), '[{"id":"a"}]');

  await storage.set('providers', providersBlob('sk-1'));
  await storage.set('model_configs', JSON.stringify([{ id: 'm1', apiKey: 'sk-model' }]));
  assert.equal(vault.entries.has('providers/p1/apiKey'), true);
  assert.equal(vault.entries.has('models/m1/apiKey'), true);

  await storage.delete('providers');
  assert.equal(vault.entries.has('providers/p1/apiKey'), false);
  assert.equal(vault.entries.has('models/m1/apiKey'), true, 'other namespaces are untouched');
});

test('chat search services keep their keys sealed and out of Preferences', async () => {
  const vault = memoryVault();
  const { storage, prefs } = loadAdapter(vault);
  await storage.set('search_services', JSON.stringify([
    { id: 'searxng', type: 'searxng', apiKey: 'sk-search', password: 'pw-1' },
    { id: 'tavily', type: 'tavily', apiKey: 'tvly-1' },
  ]));
  assert.equal(prefs.get('search_services').includes('tvly-1'), false);
  assert.equal(prefs.get('search_services').includes('pw-1'), false);
  const loaded = JSON.parse(await storage.get('search_services', '[]'));
  assert.equal(loaded[0].password, 'pw-1');
  assert.equal(loaded[1].apiKey, 'tvly-1');
});

test('deleting a scalar key removes its vault entry', async () => {
  const vault = memoryVault();
  const { storage, prefs } = loadAdapter(vault);
  await storage.set('ai_api_key', 'sk-scalar');
  assert.equal(vault.entries.has('scalar/ai_api_key/apiKey'), true);
  await storage.delete('ai_api_key');
  assert.equal(prefs.has('ai_api_key'), false);
  assert.equal(vault.entries.has('scalar/ai_api_key/apiKey'), false, 'deleting the key must not leave the secret on disk');
});

test('the legacy ai_api_key survives migration because the deepread card still reads it', async () => {
  const vault = memoryVault();
  const { storage, prefs } = loadAdapter(vault);
  prefs.set('ai_api_key', 'sk-legacy');
  // 迁移读取后:值仍在(加密保管),deepread 的 AI 配置卡与 AiClient 都不会读到空值
  assert.equal(await storage.get('ai_api_key', ''), 'sk-legacy');
  assert.equal(await storage.get('ai_api_key', ''), 'sk-legacy');
  assert.equal(prefs.get('ai_api_key'), '');
});

// ===== 3) 主密钥丢失后的恢复路径:清空密文字段、保留非密字段、留可见提示 =====

const providerWithModelSecrets = () => JSON.stringify([{
  id: 'p1', name: 'Provider p1', type: 'openai', apiKey: 'sk-provider',
  baseUrl: 'https://api.example.com/v1', models: [{
    id: 'p1-model', modelId: 'model-a', displayName: 'Model A',
    customHeaders: [{ name: 'X-Auth', value: 'header-secret' }],
    providerOverwrite: { id: 'ov', name: 'Override', type: 'google', apiKey: 'sk-override',
      baseUrl: 'https://generativelanguage.googleapis.com/v1beta' },
  }],
}]);

for (const code of ['provider_secret_key_missing', 'provider_secret_corrupt']) {
  test(`unreadable secrets (${code}) recover by clearing ciphertext fields and keep non-secret config`, async () => {
    const vault = memoryVault();
    const { storage, prefs } = loadAdapter(vault);
    await storage.set('providers', providerWithModelSecrets());
    vault.state.readError = code;
    assert.equal(prefs.get('provider_secret_recovery'), undefined, 'no notice before a failure');

    const loaded = JSON.parse(await storage.get('providers', '[]'));
    assert.equal(loaded[0].apiKey, '', 'the lost provider key is cleared, not thrown');
    assert.equal(loaded[0].name, 'Provider p1', 'non-secret provider fields survive');
    assert.equal(loaded[0].baseUrl, 'https://api.example.com/v1');
    assert.equal(loaded[0].models[0].customHeaders[0].name, 'X-Auth', 'header names survive');
    assert.equal(loaded[0].models[0].customHeaders[0].value, '');
    assert.equal(loaded[0].models[0].providerOverwrite.name, 'Override');
    assert.equal(loaded[0].models[0].providerOverwrite.apiKey, '');

    assert.equal(vault.state.discarded, 1, 'the unrecoverable vault is discarded exactly once');
    assert.equal(vault.entries.size, 0);
    const persisted = prefs.get('providers');
    assert.equal(persisted.includes('sk-provider'), false, 'the recovered blob is what gets stored');
    assert.equal(persisted.includes('header-secret'), false);
    assert.equal(prefs.get('provider_secret_recovery'), '1', 'the user is told to re-enter keys');

    // 恢复后可以继续正常读写,不会永久失败
    const again = JSON.parse(await storage.get('providers', '[]'));
    assert.equal(again[0].apiKey, '');
    assert.equal(again[0].name, 'Provider p1');
  });

  test(`legacy plaintext migration hitting ${code} discards the broken vault and re-migrates intact plaintext`, async () => {
    const vault = memoryVault({ readError: code, writeError: code === 'provider_secret_corrupt' ? code : null });
    const { storage, prefs } = loadAdapter(vault);
    prefs.set('providers', providerWithModelSecrets());

    const loaded = JSON.parse(await storage.get('providers', '[]'));
    assert.equal(loaded[0].apiKey, 'sk-provider', 'intact plaintext provider key must be preserved');
    assert.equal(loaded[0].models[0].customHeaders[0].value, 'header-secret', 'intact plaintext header value must be preserved');
    assert.equal(loaded[0].models[0].providerOverwrite.apiKey, 'sk-override', 'intact plaintext override key must be preserved');
    assert.equal(vault.state.discarded, 1, 'corrupt vault was discarded before re-migrating');
    assert.equal(vault.entries.get('providers/p1/apiKey'), 'sk-provider');
    assert.equal(prefs.get('providers').includes('sk-provider'), false, 'Preferences now holds sealed blob');
    assert.equal(prefs.get('provider_secret_recovery'), undefined, 'no recovery notice when plaintext survived');

    const scalarVault = memoryVault({ writeError: code });
    const { storage: scalarStorage, prefs: scalarPrefs } = loadAdapter(scalarVault);
    scalarPrefs.set('ai_api_key', 'sk-scalar-intact');
    assert.equal(await scalarStorage.get('ai_api_key', ''), 'sk-scalar-intact');
    assert.equal(scalarVault.state.discarded, 1);
    assert.equal(scalarVault.entries.get('scalar/ai_api_key/apiKey'), 'sk-scalar-intact');
    assert.equal(scalarPrefs.get('ai_api_key'), '');
    assert.equal(scalarPrefs.get('provider_secret_recovery'), undefined);
  });
}

test('provider_secret_invalid_size is rejected as a caller/entry error without wiping the vault', async () => {
  const vault = memoryVault();
  const { storage, prefs } = loadAdapter(vault);
  await storage.set('ai_api_key', 'sk-keep');
  vault.state.writeError = 'provider_secret_invalid_size';
  prefs.set('providers', providersBlob('sk-oversized'));

  await assert.rejects(() => storage.get('providers', '[]'), /provider_secret_invalid_size/);
  assert.equal(vault.state.discarded, 0, 'size validation error must not discard the vault');
  assert.equal(vault.entries.get('scalar/ai_api_key/apiKey'), 'sk-keep');
});

test('a transient secret-store error still rejects instead of wiping keys', async () => {
  const vault = memoryVault({ readError: 'asset_unavailable' });
  const { storage, prefs } = loadAdapter(vault);
  prefs.set('providers', providerWithModelSecrets());
  await assert.rejects(() => storage.get('providers', '[]'), /asset_unavailable/);
  assert.equal(vault.state.discarded, 0, 'an unknown failure must not destroy the vault');
  assert.equal(prefs.get('provider_secret_recovery'), undefined, 'no recovery notice for a transient failure');
  // 迁移写入发生在回填之前,所以密钥已经在库里;失败的只是回填。数据没有丢。
  assert.equal(vault.entries.get('providers/p1/apiKey'), 'sk-provider');
  assert.equal(prefs.get('providers').includes('sk-provider'), false,
    'the persisted blob is the migrated one, not a blanked one');
});

test('scalar keys recover the same way when their secret is unreadable', async () => {
  const vault = memoryVault({ readError: 'provider_secret_key_missing' });
  const { storage, prefs } = loadAdapter(vault);
  prefs.set('ai_api_key', '');
  assert.equal(await storage.get('ai_api_key', ''), '');
  assert.equal(vault.state.discarded, 1);
  assert.equal(prefs.get('provider_secret_recovery'), '1');
});

// ===== MCP 服务器 header 进入静息边界(D-136) =====
// 复现真实形状:McpCommonOptions.headers 是 kotlinx Pair 的 {first, second},
// value 常带 Authorization;此前它整段明文留在 Preferences。

const mcpServersBlob = (headers) => JSON.stringify([{
  type: 'streamable_http',
  id: 'mcp-1',
  commonOptions: { enable: true, name: 'Remote', headers, tools: [] },
  url: 'https://mcp.example.com/mcp',
}]);

test('mcp_servers headers are sealed before they reach Preferences', async () => {
  const vault = memoryVault();
  const { storage, prefs } = loadAdapter(vault);
  await storage.set('mcp_servers', mcpServersBlob([
    { first: 'Authorization', second: 'Bearer sk-mcp' },
  ]));

  assert.equal(prefs.get('mcp_servers').includes('Bearer sk-mcp'), false,
    'Preferences must not keep the MCP authorization header in plaintext');
  assert.equal(prefs.get('mcp_servers').includes('Authorization'), true, 'header names stay readable');
  assert.equal(vault.entries.get('mcp/mcp-1/headers'), '["Bearer sk-mcp"]');

  const loaded = JSON.parse(await storage.get('mcp_servers', '[]'));
  assert.equal(loaded[0].commonOptions.headers[0].second, 'Bearer sk-mcp', 'callers still see the header');
  assert.equal(loaded[0].url, 'https://mcp.example.com/mcp', 'non-secret server config is untouched');
});

test('a legacy plaintext mcp_servers blob migrates on first read and never returns to Preferences', async () => {
  const vault = memoryVault();
  const { storage, prefs } = loadAdapter(vault);
  prefs.set('mcp_servers', mcpServersBlob([{ first: 'Authorization', second: 'Bearer legacy-mcp' }]));

  assert.equal(JSON.parse(await storage.get('mcp_servers', '[]'))[0].commonOptions.headers[0].second,
    'Bearer legacy-mcp');
  assert.equal(prefs.get('mcp_servers').includes('Bearer legacy-mcp'), false);
  assert.equal(vault.entries.get('mcp/mcp-1/headers'), '["Bearer legacy-mcp"]');

  // 第二次读走已加密分支,不得再迁一次或丢掉库里的值
  assert.equal(JSON.parse(await storage.get('mcp_servers', '[]'))[0].commonOptions.headers[0].second,
    'Bearer legacy-mcp');
  assert.equal(vault.entries.get('mcp/mcp-1/headers'), '["Bearer legacy-mcp"]');
});

test('deleting mcp_servers prunes only the mcp namespace', async () => {
  const vault = memoryVault();
  const { storage, prefs } = loadAdapter(vault);
  await storage.set('mcp_servers', mcpServersBlob([{ first: 'Authorization', second: 'Bearer gone' }]));
  await storage.set('providers', providersBlob('sk-provider'));
  assert.equal(vault.entries.has('mcp/mcp-1/headers'), true);

  await storage.delete('mcp_servers');
  assert.equal(prefs.has('mcp_servers'), false);
  assert.equal(vault.entries.has('mcp/mcp-1/headers'), false, 'MCP headers must not outlive the server list');
  assert.equal(vault.entries.get('providers/p1/apiKey'), 'sk-provider', 'other namespaces are untouched');
});

test('assistants customHeaders values are sealed and migrated through PreferencesStorage', async () => {
  const vault = memoryVault();
  const { storage, prefs } = loadAdapter(vault);
  const assistantBlob = JSON.stringify([{
    id: 'asst-1',
    name: 'Assistant 1',
    customHeaders: [{ name: 'Authorization', value: 'Bearer asst-secret' }],
  }]);

  await storage.set('assistants', assistantBlob);
  assert.equal(prefs.get('assistants').includes('Bearer asst-secret'), false,
    'Preferences must not keep assistant custom header values in plaintext');
  assert.equal(prefs.get('assistants').includes('Authorization'), true);
  assert.equal(vault.entries.get('assistants/asst-1/customHeaders'), '["Bearer asst-secret"]');

  const loaded = JSON.parse(await storage.get('assistants', '[]'));
  assert.equal(loaded[0].customHeaders[0].value, 'Bearer asst-secret');

  // Legacy plaintext migration on read
  const legacyVault = memoryVault();
  const { storage: legacyStorage, prefs: legacyPrefs } = loadAdapter(legacyVault);
  legacyPrefs.set('assistants', assistantBlob);
  const migrated = JSON.parse(await legacyStorage.get('assistants', '[]'));
  assert.equal(migrated[0].customHeaders[0].value, 'Bearer asst-secret');
  assert.equal(legacyPrefs.get('assistants').includes('Bearer asst-secret'), false);
  assert.equal(legacyVault.entries.get('assistants/asst-1/customHeaders'), '["Bearer asst-secret"]');
});

test('oauth_tokens/* are encrypted as scalar secrets and removed on delete', async () => {
  const vault = memoryVault();
  const { storage, prefs } = loadAdapter(vault);
  const codexTokens = JSON.stringify({
    accessToken: 'at-codex-secret',
    refreshToken: 'rt-codex-secret',
    expiresAt: 1700000000000,
    accountId: 'acc-1',
    email: 'user@example.com',
    planType: 'plus',
  });
  const grokTokens = JSON.stringify({
    accessToken: 'at-grok-secret',
    refreshToken: 'rt-grok-secret',
    expiresAt: 1700000000000,
    email: 'grok@example.com',
  });

  await storage.set('oauth_tokens/codex/p1', codexTokens);
  await storage.set('oauth_tokens/grok/p2', grokTokens);
  assert.equal(prefs.get('oauth_tokens/codex/p1'), '', 'Preferences keeps only empty placeholder');
  assert.equal(prefs.get('oauth_tokens/grok/p2'), '', 'Preferences keeps only empty placeholder');
  assert.equal(vault.entries.get('scalar/oauth_tokens/codex/p1/apiKey'), codexTokens);
  assert.equal(vault.entries.get('scalar/oauth_tokens/grok/p2/apiKey'), grokTokens);

  assert.equal(await storage.get('oauth_tokens/codex/p1', null), codexTokens);
  assert.equal(await storage.get('oauth_tokens/grok/p2', null), grokTokens);
  assert.equal(await storage.get('oauth_tokens/codex/missing', null), null);

  // Legacy plaintext migration on read
  prefs.set('oauth_tokens/codex/legacy', codexTokens);
  assert.equal(await storage.get('oauth_tokens/codex/legacy', null), codexTokens);
  assert.equal(prefs.get('oauth_tokens/codex/legacy'), '');
  assert.equal(vault.entries.get('scalar/oauth_tokens/codex/legacy/apiKey'), codexTokens);

  // Deleting removes both Preferences and vault entry
  await storage.delete('oauth_tokens/codex/p1');
  assert.equal(prefs.has('oauth_tokens/codex/p1'), false);
  assert.equal(vault.entries.has('scalar/oauth_tokens/codex/p1/apiKey'), false);
});

test('ProviderKeyVault.write rejects oversized id (>256) or field (>64) before touching the vault file', async () => {
  const vaultPath = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl/ProviderKeyVault.ets');
  const files = new Map();
  let keyBytes = Buffer.alloc(32, 7);
  const exports = {};
  vm.runInNewContext(compile(fs.readFileSync(vaultPath, 'utf8')), {
    exports,
    Uint8Array,
    AppStorage: { get: () => ({ filesDir: '/stub' }) },
    require: spec => {
      if (spec === '@kit.CoreFileKit') {
        return { fileIo: {
          lstatSync: p => {
            if (!files.has(p)) { const err = new Error('ENOENT'); err.code = 13900002; throw err; }
            return { isSymbolicLink: () => false, isFile: () => true, size: files.get(p).length };
          },
          readText: async p => files.get(p),
          unlink: async p => { files.delete(p); },
        } };
      }
      if (spec === '@kit.AssetStoreKit') {
        return { asset: {
          Tag: { ALIAS: 1, RETURN_TYPE: 2, SECRET: 3, ACCESSIBILITY: 4, SYNC_TYPE: 5, CONFLICT_RESOLUTION: 6 },
          ReturnType: { ALL: 1, ATTRIBUTES: 2 },
          Accessibility: { DEVICE_UNLOCKED: 1 },
          SyncType: { NEVER: 1 },
          ConflictResolution: { THROW_ERROR: 1 },
          ErrorCode: { NOT_FOUND: 404 },
          query: async () => keyBytes ? [new Map([[3, new Uint8Array(keyBytes)]])] : [],
          add: async m => { keyBytes = Buffer.from(m.get(3)); },
          remove: async () => { keyBytes = null; },
        } };
      }
      if (spec === '@kit.ArkTS') {
        return { util: {
          TextEncoder: { create: () => ({ encodeInto: s => new Uint8Array(Buffer.from(s, 'utf8')) }) },
          TextDecoder: { create: () => ({ decodeToString: b => Buffer.from(b).toString('utf8') }) },
          Base64Helper: class {
            encodeToStringSync(b) { return Buffer.from(b).toString('base64'); }
            decodeSync(s) { return new Uint8Array(Buffer.from(s, 'base64')); }
          },
        } };
      }
      if (spec === './SyncCryptoPort.ets') {
        return { createEntrySyncCryptoPort: () => ({
          randomBytes: async n => new Uint8Array(Buffer.alloc(n, 9)),
          encrypt: async plain => new Uint8Array(Buffer.concat([Buffer.from(plain), Buffer.alloc(16, 1)])),
          decrypt: async cipher => new Uint8Array(Buffer.from(cipher).subarray(0, cipher.byteLength - 16)),
        }) };
      }
      if (spec === './AtomicJsonFile.ets') {
        return {
          writeTextFileAtomic: async (p, text) => { files.set(p, text); },
          KeyedAsyncMutex: class {
            constructor() { this.tails = new Map(); }
            withLock(key, op) {
              const prev = this.tails.get(key) ?? Promise.resolve();
              const run = prev.then(op, op);
              this.tails.set(key, run.then(() => {}, () => {}));
              return run;
            }
          },
        };
      }
      return {};
    },
  }, { filename: vaultPath });

  const store = exports.createEntrySecretFieldStore();
  await store.write('providers/p1', 'apiKey', 'sk-valid');
  assert.equal(await store.read('providers/p1', 'apiKey'), 'sk-valid');

  await assert.rejects(() => store.write('x'.repeat(257), 'apiKey', 'sk-bad'), /provider_secret_invalid_size/);
  await assert.rejects(() => store.write('providers/p1', 'f'.repeat(65), 'sk-bad'), /provider_secret_invalid_size/);
  // Existing vault entry remains readable and uncorrupted
  assert.equal(await store.read('providers/p1', 'apiKey'), 'sk-valid');
});
