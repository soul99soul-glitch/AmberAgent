// Actual Entry auth modules and domain wire helpers. HTTP/native SDK/store are controlled
// host ports; native POSIX and device Crypto/store have separate, explicitly named gates.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');
const ts = require('typescript');
require('tsx/cjs');
const domain = require('../main/ets/chat/google_auth.ts');
const { makeProviderSettingGoogle } = require('../main/ets/chat/provider_settings.ts');
const entry = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl');
const clone = value => JSON.parse(JSON.stringify(value));
const turn = () => new Promise(resolve => setImmediate(resolve));
const deferred = () => { let resolve; const promise = new Promise(yes => { resolve = yes; }); return { promise, resolve }; };

function fixture() {
  let now = 1800000000000;
  class Clock extends Date { static now() { return now; } }
  class Url extends URL { get params() { return this.searchParams; } static parseURL(text, base) { return new Url(text, base); } }
  const events = [], records = new Map(), listeners = new Map(), ports = new Set(), signatures = [];
  let sequence = 0, denyOnboard = false, writeHook = null, refreshGate = null, refreshEntered = null;
  const keyOf = binding => JSON.stringify([binding.providerId, binding.mode]);
  const secure = {
    stageGoogleSecret: async () => 'fixture-ref',
    loadGoogleSecret: async () => 'fixture-private-key',
    deleteGoogleSecret: async () => {},
    readGoogleOAuthTokens: async binding => {
      const record = records.get(keyOf(binding));
      return record && JSON.stringify(record.binding) === JSON.stringify(binding) ? clone(record.tokens) : null;
    },
    writeGoogleOAuthTokens: async (binding, tokens) => {
      events.push(['write:start', binding.providerId, tokens.projectId]);
      if (writeHook) await writeHook(binding, tokens);
      records.set(keyOf(binding), { binding: clone(binding), tokens: clone(tokens) });
      events.push(['write:done', binding.providerId, tokens.projectId]);
    },
    removeGoogleOAuthTokens: async binding => {
      const record = records.get(keyOf(binding));
      if (record && JSON.stringify(record.binding) === JSON.stringify(binding)) records.delete(keyOf(binding));
      events.push(['remove', binding.providerId, binding.clientId]);
    },
    removeGoogleOAuthTokensForProvider: async id => {
      for (const [key, record] of records) if (record.binding.providerId === id) records.delete(key);
      events.push(['forget', id]);
    },
  };
  const native = {
    startGoogleLoopback: async (port, callback) => {
      assert.equal(ports.has(port), false, 'old listener must release before a new attempt binds');
      const handleId = `handle-${++sequence}`;
      ports.add(port); listeners.set(handleId, { port, callback }); events.push(['listen', handleId, port]);
      return { handleId, port };
    },
    replyGoogleLoopback: async (id, connectionId, status) => { events.push(['reply', id, connectionId, status]); },
    closeGoogleLoopback: async id => {
      const listener = listeners.get(id);
      if (listener) { ports.delete(listener.port); listeners.delete(id); events.push(['close', id]); }
    },
  };
  const http = { fetch: async (request, opts) => {
    events.push(['http', request.url, request.body ?? '']);
    if (request.url === domain.GOOGLE_OAUTH_TOKEN_ENDPOINT) {
      const fields = new URLSearchParams(request.body);
      if (fields.get('grant_type') === 'refresh_token' && refreshGate) {
        refreshEntered.resolve(opts.signal);
        await refreshGate.promise;
        assert.equal(opts.signal.aborted, false, 'another conversation still waits for this refresh');
      }
      if (fields.has('assertion')) {
        const claims = JSON.parse(Buffer.from(fields.get('assertion').split('.')[1], 'base64url'));
        assert.equal(claims.exp - claims.iat, 3600);
        assert.equal(claims.scope, domain.GOOGLE_CLOUD_PLATFORM_SCOPE);
        assert.equal(claims.aud, domain.GOOGLE_OAUTH_TOKEN_ENDPOINT);
      }
      const body = { access_token: `access-${events.length}`, token_type: 'Bearer', expires_in: 3600 };
      if (fields.get('grant_type') === 'authorization_code') body.refresh_token = 'saved-refresh';
      return { status: 200, headers: {}, body: JSON.stringify(body) };
    }
    if (request.url.endsWith(':loadCodeAssist')) return { status: 200, headers: {}, body: JSON.stringify(denyOnboard
      ? { ineligibleTiers: [{ reasonCode: 'VALIDATION_REQUIRED', validationUrl: 'https://accounts.google.com/validate' }] }
      : { allowedTiers: [{ id: 'managed-tier', isDefault: true, userDefinedCloudaicompanionProject: false }] }) };
    if (request.url.endsWith(':onboardUser')) return { status: 200, headers: {}, body: JSON.stringify({ done: false, name: 'operations/fixture' }) };
    if (request.url.endsWith('/operations/fixture')) return { status: 200, headers: {}, body: JSON.stringify({ done: true,
      response: { cloudaicompanionProject: { id: 'cloud-ready' } } }) };
    if (request.url.includes('/publishers/google/models')) return { status: 200, headers: {}, body: JSON.stringify({
      publisherModels: [{ name: 'publishers/google/models/gemini-fixture', displayName: 'Fixture model' }] }) };
    if (request.url.endsWith(':fetchAvailableModels')) return { status: 200, headers: {}, body: JSON.stringify({
      models: { 'gemini-fixture': { displayName: 'Fixture model' } } }) };
    throw new Error(`Unexpected HTTP route ${request.url}`);
  } };
  const imports = {
    '@amber/chat-domain': domain,
    '@kit.ArkTS': { url: { URL: Url }, util: {
      Base64Helper: class { encodeToStringSync(bytes) { return Buffer.from(bytes).toString('base64'); } },
      TextEncoder: { create: () => ({ encodeInto: text => new TextEncoder().encode(text) }) },
    } },
    '@kit.CryptoArchitectureKit': { cryptoFramework: {
      createRandom: () => ({ generateRandomSync: size => ({ data: crypto.randomBytes(size) }) }),
      createMd: () => { const hash = crypto.createHash('sha256'); return { updateSync: value => hash.update(value.data), digestSync: () => ({ data: hash.digest() }) }; },
    } },
    'libamber_native.so': { default: native },
    './GoogleSecureStore.ets': secure,
    './GoogleRS256Signer.ets': { signGoogleRS256: async (key, input) => {
      signatures.push({ key, input: Buffer.from(input).toString() }); return new Uint8Array([1, 2, 3]);
    } },
  };
  const cache = new Map();
  function load(file) {
    if (cache.has(file)) return cache.get(file);
    const exports = {}; cache.set(file, exports);
    const output = ts.transpileModule(fs.readFileSync(path.join(entry, file), 'utf8'), {
      compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
    }).outputText;
    vm.runInNewContext(output, { exports, require: name => {
      if (Object.hasOwn(imports, name)) return imports[name];
      if (name.startsWith('./') && name.endsWith('.ets')) return load(name.slice(2));
      throw new Error(`Unexpected import ${name}`);
    }, Error, Promise, Map, Set, Date: Clock, JSON, String, Number, Uint8Array, RegExp,
    setTimeout: (fn, ms) => setTimeout(fn, ms === 5000 ? 0 : ms), clearTimeout }, { filename: file });
    return exports;
  }
  const api = load('GoogleAuthSupport.ets');
  const runtime = new api.EntryGoogleAuthRuntime(http);
  const send = target => {
    const [handleId, listener] = [...listeners].at(-1);
    const connectionId = `connection-${++sequence}`;
    listener.callback({ connectionId, requestTarget: target, errorCode: null });
    return { handleId, connectionId };
  };
  const authorize = async setting => {
    const attempt = await runtime.begin(setting);
    const url = new URL(attempt.authorizationUrl);
    send(`/callback?state=${url.searchParams.get('state')}&code=one-use-code`);
    return { attempt, url, status: await attempt.completion };
  };
  return { runtime, api, load, http, records, events, ports, signatures, send, authorize,
    clock: ms => { now += ms; }, deny: value => { denyOnboard = value; },
    writeHook: value => { writeHook = value; }, refreshGate: (gate, entered) => { refreshGate = gate; refreshEntered = entered; } };
}

test('Entry Google auth closes callback, persists before READY, protects concurrent refresh and account generations', async () => {
  const f = fixture();
  const setting = makeProviderSettingGoogle({ id: 'oauth-main', authMode: 'gemini_code_assist_oauth',
    oauthClientId: 'user-owned-client', oauthRedirectUri: 'http://127.0.0.1:51891/callback' });
  assert.equal((await f.runtime.status(makeProviderSettingGoogle({ apiKey: 'fixture-key' }))).phase, 'configured');
  await assert.rejects(f.runtime.begin(makeProviderSettingGoogle({ authMode: 'gemini_code_assist_oauth' })), /客户端/);
  await assert.rejects(f.runtime.begin({ ...setting, oauthRedirectUri: 'https://example.invalid/callback' }), /回调/);
  assert.equal(f.events.length, 0);

  const attempt = await f.runtime.begin(setting);
  const url = new URL(attempt.authorizationUrl), state = url.searchParams.get('state');
  assert.equal(url.searchParams.get('client_id'), setting.oauthClientId);
  assert.equal(url.searchParams.get('redirect_uri'), setting.oauthRedirectUri);
  assert.equal(url.searchParams.get('code_challenge_method'), 'S256');
  for (const target of ['/favicon.ico', '/callback?state=wrong&code=bad', `/callback?state=${state}&state=${state}&code=bad`]) {
    f.send(target); await turn();
  }
  assert.deepEqual(f.events.filter(row => row[0] === 'reply').map(row => row[3]), [404, 400, 400]);
  assert.equal(f.events.some(row => row[0] === 'http'), false);
  f.send(`/callback?state=${state}&code=one-use-code`);
  const status = await attempt.completion;
  assert.equal(status.phase, 'ready'); assert.equal(status.projectId, 'cloud-ready');
  assert.equal(status.tierId, 'managed-tier'); assert.equal(f.ports.size, 0);
  const exchange = f.events.find(row => row[0] === 'http' && row[1] === domain.GOOGLE_OAUTH_TOKEN_ENDPOINT);
  const fields = new URLSearchParams(exchange[2]);
  assert.equal(crypto.createHash('sha256').update(fields.get('code_verifier')).digest('base64url'), url.searchParams.get('code_challenge'));
  assert.equal(f.events.filter(row => row[0] === 'http' && row[1] === domain.GOOGLE_OAUTH_TOKEN_ENDPOINT).length, 1);
  const persisted = f.events.findIndex(row => row[0] === 'write:done' && row[2] === null);
  assert.ok(persisted < f.events.findIndex(row => row[0] === 'http' && row[1].endsWith(':loadCodeAssist')));
  assert.equal((await f.runtime.resolve(setting)).kind, 'code_assist');
  assert.equal((await f.runtime.catalog(setting)).supported, false);

  f.clock(3590000);
  const gate = deferred(), entered = deferred(); f.refreshGate(gate, entered);
  const { EntryAbortController } = f.load('EntryAbortController.ets');
  const a = new EntryAbortController(), b = new EntryAbortController();
  const cancelled = f.runtime.resolve(setting, a.signal); cancelled.catch(() => {});
  const surviving = f.runtime.resolve(setting, b.signal);
  const sharedSignal = await entered.promise;
  a.abort(); await assert.rejects(cancelled, /取消/); assert.equal(sharedSignal.aborted, false);
  gate.resolve(); assert.equal((await surviving).projectId, 'cloud-ready');
  assert.equal([...f.records.values()][0].tokens.refreshToken, 'saved-refresh');
  assert.equal(f.events.filter(row => row[0] === 'http' && new URLSearchParams(row[2]).get('grant_type') === 'refresh_token').length, 1);
  f.refreshGate(null, null);

  const retrySetting = { ...setting, id: 'onboard-retry' };
  f.deny(true);
  assert.equal((await f.authorize(retrySetting)).status.phase, 'onboarding_required');
  const retryStatus = await f.runtime.status(retrySetting);
  assert.equal(retryStatus.validationUrl, 'https://accounts.google.com/validate');
  await assert.rejects(f.runtime.resolve(retrySetting), /项目尚未就绪/);
  f.deny(false);
  assert.equal((await f.runtime.retry(retrySetting)).phase, 'ready');
  assert.equal((await f.runtime.resolve(retrySetting)).projectId, 'cloud-ready');

  const failedSave = { ...setting, id: 'save-fails' };
  f.writeHook(async binding => { if (binding.providerId === failedSave.id) throw new Error('controlled secure-store failure'); });
  assert.equal((await f.authorize(failedSave)).status.phase, 'error');
  assert.equal([...f.records.values()].some(record => record.binding.providerId === failedSave.id), false);
  f.writeHook(null);

  const late = { ...setting, id: 'late-write' }, writeEntered = deferred(), releaseWrite = deferred();
  f.writeHook(async binding => { if (binding.providerId === late.id) { writeEntered.resolve(); await releaseWrite.promise; } });
  const lateAttempt = await f.runtime.begin(late), lateUrl = new URL(lateAttempt.authorizationUrl);
  f.send(`/callback?state=${lateUrl.searchParams.get('state')}&code=late-code`);
  await writeEntered.promise;
  const logout = f.runtime.logout(late);
  releaseWrite.resolve();
  await logout; await assert.rejects(lateAttempt.completion, /取消/);
  assert.equal([...f.records.values()].some(record => record.binding.providerId === late.id), false);
  assert.equal(f.ports.size, 0); f.writeHook(null);

  // A saved new client can start a request before cleanup of the superseded setting.
  const changed = { ...setting, oauthClientId: 'new-owned-client' };
  await f.runtime.configure(changed);
  const newAttempt = await f.runtime.begin(changed), newUrl = new URL(newAttempt.authorizationUrl);
  await assert.rejects(f.runtime.resolve(setting), /配置已改变/);
  assert.equal((await f.runtime.status(changed)).phase, 'authorizing', 'frozen old caller cannot cancel the new login');
  f.send(`/callback?state=${newUrl.searchParams.get('state')}&code=new-client-code`);
  assert.equal((await newAttempt.completion).phase, 'ready');
  const beforeCleanup = (await f.runtime.resolve(changed)).accessToken;
  await f.runtime.logout(setting);
  assert.equal((await f.runtime.resolve(changed)).accessToken, beforeCleanup);
  assert.equal(f.records.get(JSON.stringify([setting.id, setting.authMode])).binding.clientId, changed.oauthClientId);
  const antigravity = { ...changed, authMode: 'antigravity_oauth' };
  assert.equal((await f.authorize(antigravity)).status.phase, 'ready');
  assert.equal((await f.runtime.resolve(antigravity)).kind, 'antigravity');
  assert.equal((await f.runtime.catalog(antigravity)).models[0].modelId, 'gemini-fixture');
  await f.runtime.forget(changed);
  assert.equal([...f.records.values()].filter(record => record.binding.providerId === changed.id).length, 0);
  const cancelledAttempt = await f.runtime.begin({ ...setting, id: 'explicit-cancel' });
  cancelledAttempt.cancel();
  await assert.rejects(cancelledAttempt.completion, /取消/);
  assert.equal(f.ports.size, 0, 'explicit cancellation releases the pending listener');

  const sa = makeProviderSettingGoogle({ id: 'service-account', vertexAI: true, useServiceAccount: true,
    privateKeyRef: 'owned-key-ref', serviceAccountEmail: 'fixture@example.invalid', projectId: 'project-fixture', location: 'us-central1' });
  assert.equal((await f.runtime.status(sa)).phase, 'configured');
  assert.equal((await f.runtime.resolve(sa)).kind, 'service_account');
  assert.equal((await f.runtime.status(sa)).phase, 'ready');
  assert.equal((await f.runtime.catalog(sa)).models[0].modelId, 'gemini-fixture');
  assert.equal(f.signatures.length, 1, 'same credential revision reuses its valid token');
  assert.equal(JSON.parse(Buffer.from(f.signatures[0].input.split('.')[0], 'base64url')).alg, 'RS256');
  assert.equal(f.ports.size, 0);
});
