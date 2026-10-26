const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
const { loadProviderSessions, providerPage } = require('./provider_session_fixture.cjs');
const tick = () => new Promise(resolve => setImmediate(resolve));

test('catalog model addition keeps existing UUID/parameters and the Google catalog display name', () => {
  const { ProviderEditSession } = loadProviderSessions({ makeProviderModel: value => ({ id: 'new-uuid', ...value }) });
  const edit = new ProviderEditSession();
  edit.start({ catalogModel: id => id === 'new-api' ? { modelId: id, displayName: 'Catalog name' } : undefined });
  const existing = { id: 'stable-uuid', modelId: 'existing-api', customBodies: [{ key: 'keep', value: true }] };
  edit.modelsText = ' existing-api\nnew-api, existing-api ';
  const models = edit.parseModels([existing]);
  assert.equal(models.length, 2); assert.equal(models[0], existing);
  assert.equal(models[0].id, 'stable-uuid');
  assert.deepEqual(models[0].customBodies, [{ key: 'keep', value: true }]);
  assert.equal(models[1].displayName, 'Catalog name'); assert.equal(models[1].id, 'new-uuid');
});

test('returning from Google child settings refreshes reference/auth changes while retaining unrelated drafts', async () => {
  const original = { id: 'google', type: 'google', models: [], apiKey: 'old', authMode: 'api_key',
    useServiceAccount: false, privateKeyRef: null, projectId: '', location: 'global', vertexAI: false };
  let latest = { ...original, models: [{ id: 'params-uuid', modelId: 'api', customBodies: ['latest'] }] };
  const { ProviderEditSession } = loadProviderSessions({ loadProviders: async () => [latest] });
  const edit = new ProviderEditSession(); edit.start({ kvStore: {} });
  Object.assign(edit, { provider: original, name: 'unsaved name', apiKey: 'unsaved key',
    baseUrl: 'https://draft.test', modelsText: 'unsaved-model', authMode: 'api_key' });
  edit.reloadReference(); await tick();
  assert.equal(edit.provider, latest); assert.equal(edit.apiKey, 'unsaved key');
  assert.equal(edit.name, 'unsaved name'); assert.equal(edit.modelsText, 'unsaved-model');
  latest = { ...latest, authMode: 'service_account', useServiceAccount: true,
    privateKeyRef: 'new-secret-ref', projectId: 'new-project', location: 'new-location', apiKey: 'child-key' };
  edit.reloadReference(); await tick();
  assert.equal(edit.provider, latest); assert.equal(edit.authMode, 'service_account');
  assert.equal(edit.projectId, 'new-project'); assert.equal(edit.apiKey, 'child-key');
  assert.equal(edit.name, 'unsaved name'); assert.equal(edit.modelsText, 'unsaved-model');
  assert.equal(edit.baseUrl, 'https://draft.test');
});

test('delete confirmation releases busy on cancel; committed latest-list deletion survives Google cleanup failure', async () => {
  const current = { id: 'google', type: 'google', privateKeyRef: 'latest-secret' };
  const other = { id: 'keep' };
  const events = [], dialogs = [];
  let stored = [current, other], failWrite = false, backs = 0;
  const { ProviderEditSession } = loadProviderSessions({
    loadProviders: async () => stored,
    saveProviders: async (_store, list) => { if (failWrite) throw Error('disk unavailable'); events.push('write'); stored = list; },
  });
  const edit = new ProviderEditSession();
  edit.start({ kvStore: {}, cleanupDeletedGoogleProvider: async provider => {
    assert.equal(provider, current); events.push('cleanup'); throw Error('credential cleanup failed');
  }, cleanupWarning: message => events.push(message) });
  edit.provider = { id: 'google', type: 'google', privateKeyRef: 'stale-secret' }; edit.name = 'Google';
  const page = providerPage(['removeSelf'], { AlertDialog: { show: dialog => dialogs.push(dialog) },
    getProductKind: () => 'deepread', ERROR: 'red', router: { back: () => backs++ } }, { edit });
  page.removeSelf(); assert.equal(edit.ioBusy, true);
  dialogs[0].primaryButton.action(); assert.equal(edit.ioBusy, false);
  page.removeSelf(); dialogs[1].secondaryButton.action(); await tick();
  assert.deepEqual(stored, [other]); assert.deepEqual(events, ['write', 'cleanup', 'credential cleanup failed']);
  assert.equal(backs, 1); assert.equal(edit.ioBusy, false);
  failWrite = true; assert.equal(await edit.deleteProvider('keep'), false);
  assert.match(edit.savedMsg, /disk unavailable/); assert.equal(edit.ioBusy, false);
  assert.equal(events.length, 3); assert.equal(backs, 1);
});

test('current raw provider export keeps format and non-secret Google metadata with picker fallback', async () => {
  const source = fs.readFileSync(path.join(__dirname, '../main/ets/platform_impl/provider/ProviderExport.ets'), 'utf8');
  const code = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText;
  for (const picked of ['file://destination', null, new Error('picker cancelled')]) {
    const writes = [], notices = [];
    const output = {};
    new Function('exports', 'require', code)(output, name => {
      if (name === '@kit.CoreFileKit') return {
        picker: { DocumentViewPicker: class { async save(options) {
          assert.deepEqual(options.newFileNames, ['provider-Current_Name.json']);
          if (picked instanceof Error) throw picked;
          return picked === null ? [] : [picked];
        } } }, fileIo: {
          OpenMode: { READ_WRITE: 1, CREATE: 2, TRUNC: 4 }, mkdirSync() {},
          openSync: target => ({ fd: target }), writeSync: (target, bytes) => writes.push({ target, json: JSON.parse(Buffer.from(bytes).toString()) }), closeSync() {},
        },
      };
      if (name === '@kit.ArkTS') return { util: { TextEncoder: { create: () => ({ encodeInto: text => Buffer.from(text) }) } } };
      if (name === '@kit.ArkUI') return { promptAction: { showToast: value => notices.push(value) } };
      throw Error('Unexpected export dependency: ' + name);
    });
    const raw = { type: 'google', name: 'Current/Name', baseUrl: 'https://current.test', apiKey: 'never-export',
      privateKey: 'never-private', privateKeyRef: 'never-ref', oauthClientSecretRef: 'never-oauth-secret',
      authMode: 'service_account', useServiceAccount: true, serviceAccountEmail: 'account', vertexAI: true,
      location: 'global', projectId: 'project', oauthClientId: 'public-client', oauthRedirectUri: 'https://callback',
      models: [{ id: 'internal', modelId: 'api-model', displayName: 'Model', type: 'chat', customHeaders: [{ value: 'never-header' }] }] };
    output.exportProvider(raw, { filesDir: '/app/files' }); await tick();
    assert.equal(writes.length, 1);
    assert.equal(writes[0].target, typeof picked === 'string' ? picked : '/app/files/exports/provider-Current_Name.json');
    const payload = writes[0].json;
    assert.equal(payload.format, 'amber-provider'); assert.equal(payload.version, 1);
    assert.equal(payload.name, 'Current/Name'); assert.equal(payload.projectId, 'project');
    assert.equal(payload.oauthClientId, 'public-client'); assert.equal(payload.serviceAccountEmail, 'account');
    assert.deepEqual(payload.models, [{ modelId: 'api-model', displayName: 'Model', type: 'chat' }]);
    assert.doesNotMatch(JSON.stringify(payload), /never-|privateKey|SecretRef|apiKey/);
    assert.equal(notices.length, 1);
  }
});
