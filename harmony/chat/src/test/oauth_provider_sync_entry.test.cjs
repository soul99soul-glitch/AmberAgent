const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const harness = (kind, mode) => {
  const codex = kind === 'Codex';
  let providers = [{ id: 'provider', type: 'openai', authMode: mode, apiKey: 'author-key',
    enabled: true, name: 'Custom provider', baseUrl: 'https://private.test',
    models: [{ id: 'model' }], balanceOption: null, brand: 'openai',
    chatCompletionsPath: '/chat/completions', useResponseApi: false }];
  let writes = 0, tokenWrites = 0;
  const current = { accessToken: 'old', refreshToken: 'refresh', accountId: 'account', expiresAt: 1 };
  const response = { status: 200, body: JSON.stringify({ access_token: 'fresh', refresh_token: 'fresh-refresh' }) };
  const host = { exports: {}, Promise, Error, JSON, Date,
    CLIENT_ID: 'client', ISSUER: 'https://oauth.test', REDIRECT_URI: 'https://oauth.test/callback',
    DEVICE_LOGIN_TIMEOUT_MS: 100000, Math, setTimeout, getChatKvStore: () => ({}),
    loadProviders: async () => providers,
    saveProviders: async (_kv, next) => { providers = next; writes++; },
    loadCodexTokens: async () => current, loadGrokTokens: async () => current,
    saveCodexTokens: async () => { tokenWrites++; }, saveGrokTokens: async () => { tokenWrites++; },
    postJson: async () => response, postForm: async () => response,
    claimString: (object, key) => typeof object[key] === 'string' ? object[key] : '',
    buildTokens: (accessToken, refreshToken) => ({ ...current, accessToken, refreshToken }),
    parseTokens: body => ({ ...current, accessToken: JSON.parse(body).access_token }),
    exchangeAuthorizationCode: async () => ({ ...current, accessToken: 'fresh' }),
    formEncode: () => 'refresh-fields',
  };
  const filename = path.resolve(__dirname, `../../../entry/src/main/ets/platform_impl/${kind}OAuthClient.ets`);
  const source = fs.readFileSync(filename, 'utf8');
  const file = ts.createSourceFile(filename, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
  const names = ['syncAccessTokenToProvider', codex ? 'refreshCodexTokens' : 'refreshGrokTokens',
    codex ? 'pollCodexDeviceCode' : 'exchangeGrokCode'];
  const selected = file.statements.filter(statement => ts.isVariableStatement(statement)
    && statement.declarationList.declarations.some(declaration => names.includes(declaration.name.getText(file))))
    .map(statement => statement.getText(file)).join('\n');
  vm.runInNewContext(ts.transpileModule(`${selected}\nexports.sync = syncAccessTokenToProvider;
    exports.refresh = ${names[1]}; exports.login = ${names[2]};`, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, host);
  return { host, sync: host.exports.sync, refresh: host.exports.refresh, login: host.exports.login,
    providers: () => providers, writes: () => writes, tokenWrites: () => tokenWrites };
};

for (const kind of ['Codex', 'Grok']) {
  const matching = kind === 'Codex' ? 'codex_oauth' : 'grok_oauth';
  const other = kind === 'Codex' ? 'grok_oauth' : 'codex_oauth';
  for (const mode of ['api_key', other, 'mimo_coding_plan']) {
    test(`${kind}: late OAuth token preserves a provider changed to ${mode}`, async () => {
      const h = harness(kind, mode);
      const before = JSON.stringify(h.providers());
      await h.sync('provider', 'late-oauth');
      assert.equal(JSON.stringify(h.providers()), before);
      assert.equal(h.writes(), 0);
    });
  }
  test(`${kind}: matching OAuth updates only the key and avoids unchanged writes`, async () => {
    const h = harness(kind, matching);
    const expected = { ...h.providers()[0], apiKey: 'fresh' };
    await h.sync('provider', 'fresh');
    assert.deepEqual(JSON.parse(JSON.stringify(h.providers()[0])), expected);
    assert.equal(h.writes(), 1);
    await h.sync('provider', 'fresh');
    assert.equal(h.writes(), 1);
  });
  test(`${kind}: foreign provider identity and type remain unchanged`, async () => {
    const h = harness(kind, matching);
    await h.sync('another-provider', 'fresh');
    h.providers()[0].type = 'claude';
    await h.sync('provider', 'fresh');
    assert.equal(h.providers()[0].apiKey, 'author-key');
    assert.equal(h.writes(), 0);
  });
  test(`${kind}: actual refresh still saves tokens and synchronizes an active OAuth provider`, async () => {
    const h = harness(kind, matching);
    const tokens = await h.refresh('provider');
    assert.equal(tokens.accessToken, 'fresh');
    assert.equal(h.tokenWrites(), 1);
    assert.equal(h.providers()[0].apiKey, 'fresh');
    assert.equal(h.writes(), 1);
  });
  test(`${kind}: mode switch while actual refresh HTTP is pending preserves the author API key`, async () => {
    const h = harness(kind, matching);
    let finish;
    const pendingHttp = new Promise(resolve => { finish = resolve; });
    h.host.postJson = h.host.postForm = async () => pendingHttp;
    const refreshing = h.refresh('provider');
    // The persisted row changes before the refresh result reaches the synchronization helper.
    h.providers()[0].authMode = 'api_key';
    h.providers()[0].apiKey = 'replacement-author-key';
    finish({ status: 200, body: JSON.stringify({ access_token: 'late-oauth' }) });
    assert.equal((await refreshing).accessToken, 'late-oauth');
    assert.equal(h.tokenWrites(), 1);
    assert.equal(h.providers()[0].apiKey, 'replacement-author-key');
    assert.equal(h.writes(), 0);
  });
}

const deferred = () => {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { promise, resolve };
};
const authorization = { intervalSeconds: 1, deviceAuthId: 'device', userCode: 'user-code' };
const pollResponse = { status: 200, body: JSON.stringify({ authorization_code: 'code', code_verifier: 'verifier' }) };

for (const kind of ['Codex', 'Grok']) {
  test(`${kind}: cancellation while login HTTP is pending performs no token/provider write`, async () => {
    const h = harness(kind, kind === 'Codex' ? 'codex_oauth' : 'grok_oauth');
    const response = deferred();
    let cancelled = false;
    h.host.postJson = h.host.postForm = async () => response.promise;
    const pending = kind === 'Codex' ? h.login('provider', authorization, () => cancelled)
      : h.login('provider', 'code', 'verifier', () => cancelled);
    cancelled = true;
    response.resolve(kind === 'Codex' ? pollResponse : { status: 200, body: '{"access_token":"fresh"}' });
    await assert.rejects(pending, /已取消登录/);
    assert.equal(h.tokenWrites(), 0); assert.equal(h.writes(), 0);
    assert.equal(h.providers()[0].apiKey, 'author-key');
  });
  test(`${kind}: current login still persists and synchronizes credentials`, async () => {
    const h = harness(kind, kind === 'Codex' ? 'codex_oauth' : 'grok_oauth');
    h.host.postJson = async () => pollResponse;
    const tokens = kind === 'Codex' ? await h.login('provider', authorization, () => false)
      : await h.login('provider', 'code', 'verifier', () => false);
    assert.equal(tokens.accessToken, 'fresh');
    assert.equal(h.tokenWrites(), 1); assert.equal(h.writes(), 1);
    assert.equal(h.providers()[0].apiKey, 'fresh');
  });
}

test('Codex: cancellation during authorization-code exchange prevents persistence after exchange', async () => {
  const h = harness('Codex', 'codex_oauth');
  const response = deferred(), exchanging = deferred();
  let cancelled = false;
  h.host.postJson = async () => pollResponse;
  h.host.exchangeAuthorizationCode = async () => { exchanging.resolve(); return response.promise; };
  const pending = h.login('provider', authorization, () => cancelled);
  await exchanging.promise;
  cancelled = true; response.resolve({ accessToken: 'fresh', refreshToken: 'refresh' });
  await assert.rejects(pending, /已取消登录/);
  assert.equal(h.tokenWrites(), 0); assert.equal(h.writes(), 0);
});

test('Grok: legacy caller without a cancellation callback still commits a successful exchange', async () => {
  const h = harness('Grok', 'grok_oauth');
  assert.equal((await h.login('provider', 'code', 'verifier')).accessToken, 'fresh');
  assert.equal(h.tokenWrites(), 1); assert.equal(h.providers()[0].apiKey, 'fresh');
});
