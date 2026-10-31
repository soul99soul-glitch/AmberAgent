import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  sealSecretFields, openSecretFields, blobHasInlineSecrets, sealScalarSecret, openScalarSecret,
  migrateSecretFields, stripSecretFields, isRedactedSecretPlaceholder,
  type SecretFieldStore, type SecretField,
} from '../main/ets/chat/secret_fields_blob.ts';
import { encodeSSHCredential, decodeSSHCredential } from '../main/ets/chat/terminal/credential_codec.ts';
import { partSummaryLine } from '../main/ets/chat/context_compact.ts';
import { makeRecapInput } from '../main/ets/chat/conversation_recap.ts';
import { buildJevAutoApprovalBatch } from '../main/ets/chat/jev_auto_approval.ts';
import { makeConversation, toMessageNode } from '../main/ets/chat/conversation.ts';
import { makeUserMessage, makeUIMessage, type UIMessagePartTool } from '../main/ets/chat/message.ts';
import { serializeProviderSettingList, parseProviderSettingList } from '../main/ets/chat/provider_settings_serialize.ts';
import {
  makeProviderSettingOpenAIVariant, makeProviderSettingGoogle, makeProviderModel,
} from '../main/ets/chat/provider_settings.ts';
import { makeSearchServiceOptions, searchServiceOptionsToJson } from '../main/ets/search/search_service.ts';
import type { SearchServiceOptions } from '../main/ets/search/search_service.ts';

const PROVIDERS = 'providers';
const MODELS = 'models';

const memoryStore = (): SecretFieldStore & { entries: Map<string, string> } => {
  const entries = new Map<string, string>();
  const keyOf = (ownerKey: string, field: SecretField): string => `${ownerKey}/${field}`;
  return {
    entries,
    read(ownerKey: string, field: SecretField): Promise<string | null> {
      const value = entries.get(keyOf(ownerKey, field));
      return Promise.resolve(value === undefined ? null : value);
    },
    write(ownerKey: string, field: SecretField, value: string): Promise<void> {
      entries.set(keyOf(ownerKey, field), value);
      return Promise.resolve();
    },
    remove(ownerKey: string, field: SecretField): Promise<void> {
      entries.delete(keyOf(ownerKey, field));
      return Promise.resolve();
    },
    listOwnerKeys(): Promise<string[]> {
      // ownerKey 可以有多段(providers/<provider>/<model>),去掉末尾的槽位名即可
      const keys = new Set<string>();
      for (const key of entries.keys()) keys.add(key.slice(0, key.lastIndexOf('/')));
      return Promise.resolve([...keys].sort());
    },
    discardAll(): Promise<void> {
      entries.clear();
      return Promise.resolve();
    },
  };
};

const openaiProvider = (id: string, apiKey: string) => makeProviderSettingOpenAIVariant({
  id, name: `Provider ${id}`, baseUrl: 'https://api.example.com/v1', apiKey,
});

const googleProvider = (id: string, apiKey: string, privateKey: string) => makeProviderSettingGoogle({
  id, name: `Provider ${id}`, baseUrl: 'https://generativelanguage.googleapis.com',
  apiKey, privateKey, useServiceAccount: privateKey.length > 0,
});

test('seal blanks every secret field into the store and open restores the exact settings', async () => {
  const store = memoryStore();
  const providers = [openaiProvider('p1', 'sk-openai'), googleProvider('p2', 'gk-google', 'PEM-PRIVATE')];
  const raw = serializeProviderSettingList(providers);

  const sealed = await sealSecretFields(raw, store, PROVIDERS);
  assert.equal(sealed.includes('sk-openai'), false, 'openai key must not stay in the blob');
  assert.equal(sealed.includes('gk-google'), false, 'google key must not stay in the blob');
  assert.equal(sealed.includes('PEM-PRIVATE'), false, 'service account key must not stay in the blob');
  assert.equal(store.entries.size, 3);

  const reopened = await openSecretFields(sealed, store, PROVIDERS);
  assert.deepEqual(parseProviderSettingList(reopened), parseProviderSettingList(raw));
});

test('seal round-trip keeps non-secret configuration untouched', async () => {
  const store = memoryStore();
  const raw = serializeProviderSettingList([openaiProvider('p1', 'sk-1'), googleProvider('p2', '', '')]);
  const sealed = await sealSecretFields(raw, store, PROVIDERS);
  const parsed = JSON.parse(sealed) as Array<Record<string, unknown>>;
  assert.equal(parsed.length, 2);
  assert.equal(parsed[0]['name'], 'Provider p1');
  assert.equal(parsed[0]['baseUrl'], 'https://api.example.com/v1');
  assert.equal(parsed[0]['apiKey'], '');
  assert.equal(parsed[1]['privateKey'], '');
  // 无凭据的 provider 不写入任何条目,也不因缺少条目而报错
  assert.equal(store.entries.has('providers/p2/apiKey'), false);
  assert.equal(store.entries.has('providers/p2/privateKey'), false);
});

test('clearing a key in the model removes the stored secret instead of resurrecting it', async () => {
  const store = memoryStore();
  const raw = serializeProviderSettingList([openaiProvider('p1', 'sk-1')]);
  const sealed = await sealSecretFields(raw, store, PROVIDERS);
  const loaded = parseProviderSettingList(await openSecretFields(sealed, store, PROVIDERS));
  const cleared = serializeProviderSettingList(loaded.map((provider) => ({ ...provider, apiKey: '' })));

  const resealed = await sealSecretFields(cleared, store, PROVIDERS);
  assert.equal(store.entries.has('providers/p1/apiKey'), false, 'cleared key must not stay in the store');
  const reopened = parseProviderSettingList(await openSecretFields(resealed, store, PROVIDERS));
  assert.equal(reopened[0].apiKey, '');
});

test('seal prunes secrets of records that no longer exist without touching other namespaces', async () => {
  const store = memoryStore();
  await sealSecretFields(serializeProviderSettingList([openaiProvider('p1', 'sk-1'), openaiProvider('p2', 'sk-2')]),
    store, PROVIDERS);
  await sealSecretFields(JSON.stringify([{ id: 'p1', apiKey: 'deepread-key' }]), store, MODELS);
  assert.deepEqual(await store.listOwnerKeys(), ['models/p1', 'providers/p1', 'providers/p2']);

  await sealSecretFields(serializeProviderSettingList([openaiProvider('p2', 'sk-2')]), store, PROVIDERS);
  assert.deepEqual(await store.listOwnerKeys(), ['models/p1', 'providers/p2'],
    'same-named record in another blob must survive');
});

test('malformed or unexpected blobs pass through unchanged and never lose credentials', async () => {
  const store = memoryStore();
  for (const raw of ['', 'not json', '{}', 'null', '[1,2,3]', '[{"name":"no id","apiKey":"sk-x"}]']) {
    assert.equal(await sealSecretFields(raw, store, PROVIDERS), raw);
    assert.equal(await openSecretFields(raw, store, PROVIDERS), raw);
  }
  assert.equal(store.entries.size, 0, 'unparseable input must not write or blank credentials');
});

test('a record without a usable id keeps its inline credential rather than dropping it', async () => {
  const store = memoryStore();
  const raw = JSON.stringify([
    { id: 'ok', apiKey: 'sk-ok' },
    { apiKey: 'sk-no-id' },
    { id: '', apiKey: 'sk-empty-id' },
  ]);
  const sealed = await sealSecretFields(raw, store, PROVIDERS);
  const parsed = JSON.parse(sealed) as Array<Record<string, unknown>>;
  assert.equal(parsed[0]['apiKey'], '', 'identified record is sealed');
  assert.equal(parsed[1]['apiKey'], 'sk-no-id', 'unidentified record is left untouched');
  assert.equal(parsed[2]['apiKey'], 'sk-empty-id', 'empty id is not a valid vault owner');
  assert.deepEqual(await store.listOwnerKeys(), ['providers/ok']);
});

test('the deepread model config shape is sealed by the same codec', async () => {
  const store = memoryStore();
  const raw = JSON.stringify([{ id: 'default', label: '默认模型', baseUrl: 'https://api.deepseek.com',
    apiKey: 'sk-deepread', model: 'deepseek-chat' }]);
  const sealed = await sealSecretFields(raw, store, MODELS);
  assert.equal(sealed.includes('sk-deepread'), false);
  const rows = JSON.parse(sealed) as Array<Record<string, unknown>>;
  assert.equal(rows[0]['label'], '默认模型');
  assert.equal(rows[0]['apiKey'], '');
  assert.deepEqual(JSON.parse(await openSecretFields(sealed, store, MODELS)), JSON.parse(raw));
});

test('a failing secret store surfaces the error instead of silently persisting a blanked blob', async () => {
  const raw = serializeProviderSettingList([openaiProvider('p1', 'sk-1')]);
  const failing: SecretFieldStore = {
    read: () => Promise.resolve(null),
    write: () => Promise.reject(new Error('asset_unavailable')),
    remove: () => Promise.resolve(),
    listOwnerKeys: () => Promise.resolve([]),
    discardAll: () => Promise.resolve(),
  };
  await assert.rejects(() => sealSecretFields(raw, failing, PROVIDERS), /asset_unavailable/);
  // 读侧失败同样上抛:否则内存里会出现空 key,下一次保存就会删掉真实凭据
  const corrupt: SecretFieldStore = {
    read: () => Promise.reject(new Error('provider_secret_corrupt')),
    write: () => Promise.resolve(),
    remove: () => Promise.resolve(),
    listOwnerKeys: () => Promise.resolve([]),
    discardAll: () => Promise.resolve(),
  };
  await assert.rejects(
    () => openSecretFields(serializeProviderSettingList([openaiProvider('p1', '')]), corrupt, PROVIDERS),
    /provider_secret_corrupt/,
  );
});

test('existing plaintext settings are migrated by one seal pass and stay readable', async () => {
  const store = memoryStore();
  const legacy = serializeProviderSettingList([openaiProvider('p1', 'sk-legacy')]);
  const migrated = await sealSecretFields(legacy, store, PROVIDERS);
  assert.notEqual(migrated, legacy);
  const loaded = parseProviderSettingList(await openSecretFields(migrated, store, PROVIDERS));
  assert.equal(loaded[0].apiKey, 'sk-legacy');
});

test('repeated save of unchanged settings is idempotent', async () => {
  const store = memoryStore();
  const raw = serializeProviderSettingList([openaiProvider('p1', 'sk-1'), googleProvider('p2', 'g', 'pem')]);
  const once = await sealSecretFields(raw, store, PROVIDERS);
  const twice = await sealSecretFields(await openSecretFields(once, store, PROVIDERS), store, PROVIDERS);
  assert.equal(twice, once);
  assert.deepEqual(await store.listOwnerKeys(), ['providers/p1', 'providers/p2']);
});

test('blobHasInlineSecrets skips the vault round-trip once a blob is already sealed', async () => {
  const store = memoryStore();
  const raw = serializeProviderSettingList([openaiProvider('p1', 'sk-1'), googleProvider('p2', '', '')]);
  assert.equal(blobHasInlineSecrets(raw), true, 'plaintext blob needs sealing');
  const sealed = await sealSecretFields(raw, store, PROVIDERS);
  assert.equal(blobHasInlineSecrets(sealed), false, 'sealed blob needs no further vault work');
  assert.equal(blobHasInlineSecrets('[{"name":"no id","apiKey":"sk-x"}]'), false,
    'unowned credentials cannot be sealed, so the read path must not rewrite them');
  assert.equal(blobHasInlineSecrets('not json'), false);
});

test('scalar secret keys are sealed as a whole string and migrate in one pass', async () => {
  const store = memoryStore();
  const ownerKey = 'scalar/ai_api_key';
  // 旧版明文标量:读取时就地加密,Preferences 只留空串
  assert.equal(await sealScalarSecret('sk-legacy-scalar', store, ownerKey, 'apiKey'), '');
  assert.equal(store.entries.get('scalar/ai_api_key/apiKey'), 'sk-legacy-scalar');
  assert.equal(await openScalarSecret('', store, ownerKey, 'apiKey'), 'sk-legacy-scalar');
  // 已迁移后再读:调用方不能拿空串回灌(那会删掉真值),只做回填
  assert.equal(await openScalarSecret('', store, ownerKey, 'apiKey'), 'sk-legacy-scalar');
  // 用户清空(写路径上内存值即权威):store 条目删除,读回空串而不是复活旧值
  await sealScalarSecret('', store, ownerKey, 'apiKey');
  assert.equal(store.entries.has('scalar/ai_api_key/apiKey'), false);
  assert.equal(await openScalarSecret('', store, ownerKey, 'apiKey'), '');
  // 迁移期间 Preferences 仍有值时以它为准
  assert.equal(await openScalarSecret('sk-still-plaintext', store, ownerKey, 'apiKey'), 'sk-still-plaintext');
});

test('saving an empty list prunes that namespace instead of leaving ciphertext behind', async () => {
  const store = memoryStore();
  await sealSecretFields(serializeProviderSettingList([openaiProvider('p1', 'sk-1')]), store, PROVIDERS);
  await sealSecretFields(serializeProviderSettingList([openaiProvider('p2', 'sk-2')]), store, MODELS);
  assert.deepEqual(await store.listOwnerKeys(), ['models/p2', 'providers/p1']);

  const emptied = await sealSecretFields('[]', store, PROVIDERS);
  assert.equal(emptied, '[]', 'the empty list itself is stored verbatim');
  assert.deepEqual(await store.listOwnerKeys(), ['models/p2'], 'only the emptied namespace is pruned');
  assert.equal(await openSecretFields('[]', store, PROVIDERS), '[]');
});

test('searxng passwords travel through the same sealed fields as api keys', async () => {
  const store = memoryStore();
  const raw = JSON.stringify([
    { id: 'searxng', type: 'searxng', apiKey: 'sk-search', password: 'hunter2' },
    { id: 'tavily', type: 'tavily', apiKey: 'tvly-key' },
  ]);
  const sealed = await sealSecretFields(raw, store, 'search');
  assert.equal(sealed.includes('hunter2'), false);
  assert.equal(sealed.includes('tvly-key'), false);
  assert.equal(store.entries.get('search/searxng/password'), 'hunter2');
  assert.deepEqual(JSON.parse(await openSecretFields(sealed, store, 'search')), JSON.parse(raw));
});

test('the real search_services serializer round-trips through the sealed boundary', async () => {
  const store = memoryStore();
  // 用生产序列化器生成 blob,避免手写字段名掩盖「字段改名后静默不加密」
  const searxng = makeSearchServiceOptions('searxng') as Extract<SearchServiceOptions, { type: 'searxng' }>;
  searxng.id = 'searxng';
  searxng.password = 'pw-2';
  const tavily = makeSearchServiceOptions('tavily') as Extract<SearchServiceOptions, { type: 'tavily' }>;
  tavily.id = 'tavily';
  tavily.apiKey = 'tvly-2';
  const raw = JSON.stringify([searxng, tavily].map((service) => searchServiceOptionsToJson(service)));
  assert.ok(raw.includes('"apiKey"') && raw.includes('"password"'), 'sanity: serializer emits both secret fields');

  const sealed = await sealSecretFields(raw, store, 'search');
  assert.equal(sealed.includes('tvly-2'), false, 'serialized apiKey field must be sealed');
  assert.equal(sealed.includes('pw-2'), false, 'serialized password field must be sealed');
  assert.equal(store.entries.get('search/tavily/apiKey'), 'tvly-2');
  assert.equal(store.entries.get('search/searxng/password'), 'pw-2');
  const back = JSON.parse(await openSecretFields(sealed, store, 'search')) as Array<Record<string, unknown>>;
  assert.equal(back[0]['password'], 'pw-2');
  assert.equal(back[1]['apiKey'], 'tvly-2');
});

test('a blob whose rows cannot be attributed never wipes the namespace', async () => {
  const store = memoryStore();
  await sealSecretFields(serializeProviderSettingList([openaiProvider('p1', 'sk-1')]), store, PROVIDERS);
  assert.deepEqual(await store.listOwnerKeys(), ['providers/p1']);

  // 结构变了 / 记录全缺 id:既不能写也不能清
  const unattributable = JSON.stringify([{ name: 'x', apiKey: 'sk-x' }]);
  assert.equal(await sealSecretFields(unattributable, store, PROVIDERS), unattributable);
  assert.deepEqual(await store.listOwnerKeys(), ['providers/p1'], 'existing secrets must survive');
});

// ===== 嵌套凭据:models[].customHeaders[].value 与 models[].providerOverwrite.apiKey =====

const providerWithModelSecrets = (id: string) => makeProviderSettingOpenAIVariant({
  id,
  name: `Provider ${id}`,
  baseUrl: 'https://api.example.com/v1',
  apiKey: 'sk-provider',
  models: [makeProviderModel({
    id: `${id}-model`,
    modelId: 'model-a',
    displayName: 'Model A',
    customHeaders: [
      { name: 'X-Auth', value: 'header-secret' },
      { name: 'X-Trace', value: '' },
    ],
    providerOverwrite: makeProviderSettingGoogle({
      id: `${id}-override`,
      name: 'Override',
      apiKey: 'sk-override',
      baseUrl: 'https://generativelanguage.googleapis.com/v1beta',
    }),
  })],
});

test('model custom header values are sealed while their names stay readable', async () => {
  const store = memoryStore();
  const raw = serializeProviderSettingList([providerWithModelSecrets('p1')]);
  const sealed = await sealSecretFields(raw, store, PROVIDERS);

  assert.equal(sealed.includes('header-secret'), false, 'header value must not stay in the blob');
  assert.equal(sealed.includes('X-Auth'), true, 'header name is not a secret and stays');
  const rows = JSON.parse(sealed) as Array<Record<string, unknown>>;
  const models = rows[0]['models'] as Array<Record<string, unknown>>;
  const headers = models[0]['customHeaders'] as Array<Record<string, unknown>>;
  assert.equal(headers[0]['value'], '');
  assert.equal(headers[1]['value'], '', 'empty header value stays empty');
  assert.equal(store.entries.get('providers/p1/p1-model/customHeaders'), '["header-secret",""]');

  const reopened = parseProviderSettingList(await openSecretFields(sealed, store, PROVIDERS));
  assert.deepEqual(reopened[0].models[0].customHeaders, [
    { name: 'X-Auth', value: 'header-secret' },
    { name: 'X-Trace', value: '' },
  ]);
});

test('model providerOverwrite keeps its non-secret fields and seals only the api key', async () => {
  const store = memoryStore();
  const raw = serializeProviderSettingList([providerWithModelSecrets('p1')]);
  const sealed = await sealSecretFields(raw, store, PROVIDERS);

  assert.equal(sealed.includes('sk-override'), false, 'override api key must not stay in the blob');
  assert.equal(sealed.includes('https://generativelanguage.googleapis.com/v1beta'), true,
    'override endpoint is not a secret and stays');
  const rows = JSON.parse(sealed) as Array<Record<string, unknown>>;
  const override = ((rows[0]['models'] as Array<Record<string, unknown>>)[0]['providerOverwrite']) as Record<string, unknown>;
  assert.equal(override['apiKey'], '');
  assert.equal(override['name'], 'Override');
  assert.equal(store.entries.get('providers/p1/p1-model/apiKey'), 'sk-override');

  const reopened = parseProviderSettingList(await openSecretFields(sealed, store, PROVIDERS));
  const restored = reopened[0].models[0].providerOverwrite;
  assert.equal(restored === null ? '' : restored.apiKey, 'sk-override');
  assert.equal(restored === null ? '' : restored.name, 'Override');
});

test('blobHasInlineSecrets also sees nested plaintext model credentials', async () => {
  const store = memoryStore();
  const raw = serializeProviderSettingList([providerWithModelSecrets('p1')]);
  assert.equal(blobHasInlineSecrets(raw), true);
  const sealed = await sealSecretFields(raw, store, PROVIDERS);
  assert.equal(blobHasInlineSecrets(sealed), false, 'a fully sealed blob needs no migration pass');
});

test('migrating a partly sealed blob never deletes already stored secrets', async () => {
  const store = memoryStore();
  const sealed = await sealSecretFields(serializeProviderSettingList([providerWithModelSecrets('p1')]), store, PROVIDERS);
  // 模拟「同一 blob 里又出现一条新增明文」:provider 级密钥回到明文,模型级仍是加密态
  const rows = JSON.parse(sealed) as Array<Record<string, unknown>>;
  rows[0]['apiKey'] = 'sk-added-later';
  const partial = JSON.stringify(rows);

  const migrated = await migrateSecretFields(partial, store, PROVIDERS);
  const after = JSON.parse(migrated) as Array<Record<string, unknown>>;
  assert.equal(after[0]['apiKey'], '', 'the newly found plaintext is sealed');
  assert.equal(store.entries.get('providers/p1/apiKey'), 'sk-added-later');
  assert.equal(store.entries.get('providers/p1/p1-model/customHeaders'), '["header-secret",""]',
    'already sealed model headers must survive migration');
  assert.equal(store.entries.get('providers/p1/p1-model/apiKey'), 'sk-override',
    'already sealed override key must survive migration');
});

test('stripSecretFields blanks every secret and keeps the rest, without touching the store', async () => {
  const store = memoryStore();
  const raw = serializeProviderSettingList([providerWithModelSecrets('p1')]);
  const stripped = stripSecretFields(raw);
  assert.equal(store.entries.size, 0, 'stripping must not write anything');

  const rows = JSON.parse(stripped) as Array<Record<string, unknown>>;
  assert.equal(rows[0]['apiKey'], '');
  assert.equal(rows[0]['name'], 'Provider p1');
  const models = rows[0]['models'] as Array<Record<string, unknown>>;
  const headers = models[0]['customHeaders'] as Array<Record<string, unknown>>;
  assert.equal(headers[0]['value'], '');
  assert.equal(headers[0]['name'], 'X-Auth');
  const override = models[0]['providerOverwrite'] as Record<string, unknown>;
  assert.equal(override['apiKey'], '');
  assert.equal(override['baseUrl'], 'https://generativelanguage.googleapis.com/v1beta');
});

test('removing a model prunes its sealed credentials', async () => {
  const store = memoryStore();
  await sealSecretFields(serializeProviderSettingList([providerWithModelSecrets('p1')]), store, PROVIDERS);
  assert.equal(store.entries.has('providers/p1/p1-model/customHeaders'), true);

  const withoutModel = makeProviderSettingOpenAIVariant({
    id: 'p1', name: 'Provider p1', baseUrl: 'https://api.example.com/v1', apiKey: 'sk-provider', models: [],
  });
  await sealSecretFields(serializeProviderSettingList([withoutModel]), store, PROVIDERS);
  assert.equal(store.entries.has('providers/p1/p1-model/customHeaders'), false);
  assert.equal(store.entries.has('providers/p1/p1-model/apiKey'), false);
  assert.equal(store.entries.get('providers/p1/apiKey'), 'sk-provider', 'the provider secret survives');
});

// ===== MCP 服务器 header 的静息边界 =====
// McpCommonOptions.headers 是 [name, value] 对(kotlinx Pair 线格式 {first, second}),
// 与 provider 的 customHeaders 形状不同,必须单独覆盖:value 常带 Authorization。

const mcpServerJson = (headers: Array<[string, string]>) => JSON.stringify([{
  type: 'streamable_http',
  id: 'mcp-1',
  commonOptions: {
    enable: true,
    name: 'Remote',
    headers: headers.map(([first, second]) => ({ first, second })),
    tools: [],
  },
  url: 'https://mcp.example.com/mcp',
}]);

test('MCP header values are sealed while their names stay readable', async () => {
  const store = memoryStore();
  const raw = mcpServerJson([['Authorization', 'Bearer sk-mcp-secret'], ['X-Trace', '']]);
  const sealed = await sealSecretFields(raw, store, 'mcp');

  assert.equal(sealed.includes('sk-mcp-secret'), false, 'header value must not stay in the blob');
  assert.equal(sealed.includes('Authorization'), true, 'header name is not a secret and stays');
  assert.equal(sealed.includes('https://mcp.example.com/mcp'), true, 'server url is not a secret');
  const rows = JSON.parse(sealed) as Array<Record<string, unknown>>;
  const common = rows[0]['commonOptions'] as Record<string, unknown>;
  const headers = common['headers'] as Array<Record<string, unknown>>;
  assert.equal(headers[0]['second'], '');
  assert.equal(headers[1]['second'], '');
  assert.equal(store.entries.get('mcp/mcp-1/headers'), '["Bearer sk-mcp-secret",""]');

  const reopened = JSON.parse(await openSecretFields(sealed, store, 'mcp')) as Array<Record<string, unknown>>;
  const reopenedHeaders = ((reopened[0]['commonOptions'] as Record<string, unknown>)['headers']) as Array<Record<string, unknown>>;
  assert.deepEqual(reopenedHeaders, [
    { first: 'Authorization', second: 'Bearer sk-mcp-secret' },
    { first: 'X-Trace', second: '' },
  ]);
});

test('a plaintext MCP blob is detected, migrated once and then needs no migration', async () => {
  const store = memoryStore();
  const raw = mcpServerJson([['Authorization', 'Bearer migrated']]);
  assert.equal(blobHasInlineSecrets(raw), true);

  const migrated = await migrateSecretFields(raw, store, 'mcp');
  assert.equal(migrated.includes('Bearer migrated'), false);
  assert.equal(store.entries.get('mcp/mcp-1/headers'), '["Bearer migrated"]');
  assert.equal(blobHasInlineSecrets(migrated), false, 'a sealed MCP blob needs no further migration');
});

test('clearing every MCP header value removes the stored slot instead of resurrecting it', async () => {
  const store = memoryStore();
  await sealSecretFields(mcpServerJson([['Authorization', 'Bearer gone']]), store, 'mcp');
  assert.equal(store.entries.has('mcp/mcp-1/headers'), true);

  const cleared = await sealSecretFields(mcpServerJson([['Authorization', '']]), store, 'mcp');
  assert.equal(store.entries.has('mcp/mcp-1/headers'), false);
  assert.equal(JSON.parse(await openSecretFields(cleared, store, 'mcp'))[0]['commonOptions']['headers'][0]['second'],
    '');
});

test('deleting the last MCP server prunes its sealed headers', async () => {
  const store = memoryStore();
  await sealSecretFields(mcpServerJson([['Authorization', 'Bearer pruned']]), store, 'mcp');
  await sealSecretFields('[]', store, 'mcp');
  assert.equal(store.entries.has('mcp/mcp-1/headers'), false);
});

test('stripSecretFields blanks MCP header values and keeps the rest', async () => {
  const store = memoryStore();
  const stripped = stripSecretFields(mcpServerJson([['Authorization', 'Bearer stripped']]));
  assert.equal(store.entries.size, 0, 'stripping must not write anything');
  assert.equal(stripped.includes('Bearer stripped'), false);
  assert.equal(stripped.includes('Authorization'), true);
  assert.equal(stripped.includes('https://mcp.example.com/mcp'), true);
});

test('assistant top-level customHeaders values are sealed, migrated, opened and stripped', async () => {
  const store = memoryStore();
  const raw = JSON.stringify([{
    id: 'assistant-1',
    name: 'Assistant 1',
    customHeaders: [
      { name: 'Authorization', value: 'Bearer asst-secret' },
      { name: 'X-Trace', value: '' },
    ],
  }]);
  assert.equal(blobHasInlineSecrets(raw), true);

  const sealed = await sealSecretFields(raw, store, 'assistants');
  assert.equal(sealed.includes('Bearer asst-secret'), false);
  assert.equal(sealed.includes('Authorization'), true);
  assert.equal(blobHasInlineSecrets(sealed), false);
  assert.equal(store.entries.get('assistants/assistant-1/customHeaders'), '["Bearer asst-secret",""]');

  const reopened = JSON.parse(await openSecretFields(sealed, store, 'assistants')) as Array<Record<string, unknown>>;
  assert.deepEqual(reopened[0]['customHeaders'], [
    { name: 'Authorization', value: 'Bearer asst-secret' },
    { name: 'X-Trace', value: '' },
  ]);

  const stripped = stripSecretFields(raw);
  assert.equal(stripped.includes('Bearer asst-secret'), false);
  assert.equal(stripped.includes('Authorization'), true);

  const cleared = await sealSecretFields(JSON.stringify([{
    id: 'assistant-1',
    name: 'Assistant 1',
    customHeaders: [],
  }]), store, 'assistants');
  assert.equal(store.entries.has('assistants/assistant-1/customHeaders'), false);
  assert.equal(JSON.parse(await openSecretFields(cleared, store, 'assistants'))[0]['customHeaders'].length, 0);
});

test('redacted placeholders (<redacted>, ***, ••••) are rejected and never overwrite real secrets in vault or SSH codec', async () => {
  for (const p of ['<redacted>', '[REDACTED]', '***', '********', '••••', '●●●●', 'sk-...***']) {
    assert.equal(isRedactedSecretPlaceholder(p), true, `expected placeholder: ${p}`);
  }
  assert.equal(isRedactedSecretPlaceholder('sk-real-secret-key-123'), false);

  const store = memoryStore();
  await sealSecretFields(serializeProviderSettingList([providerWithModelSecrets('p1')]), store, PROVIDERS);
  assert.equal(store.entries.get('providers/p1/apiKey'), 'sk-provider');
  assert.equal(store.entries.get('providers/p1/p1-model/customHeaders'), '["header-secret",""]');

  // Attempt to save redacted placeholders over existing provider and model headers
  const withPlaceholders = makeProviderSettingOpenAIVariant({
    id: 'p1',
    name: 'Provider p1',
    baseUrl: 'https://api.example.com/v1',
    apiKey: '***',
    models: [makeProviderModel({
      id: 'p1-model',
      modelId: 'model-a',
      displayName: 'Model A',
      customHeaders: [
        { name: 'X-Auth', value: '<redacted>' },
        { name: 'X-Trace', value: 'trace-ok' },
      ],
    })],
  });
  const sealedPlaceholder = await sealSecretFields(serializeProviderSettingList([withPlaceholders]), store, PROVIDERS);
  assert.equal(sealedPlaceholder.includes('***'), false);
  assert.equal(sealedPlaceholder.includes('<redacted>'), false);
  assert.equal(store.entries.get('providers/p1/apiKey'), 'sk-provider', 'real apiKey must not be overwritten by ***');
  assert.equal(store.entries.get('providers/p1/p1-model/customHeaders'), '["header-secret","trace-ok"]',
    'real header secret must be preserved when placeholder is passed');

  // Scalar secret placeholder rejection
  await sealScalarSecret('sk-scalar-real', store, 'scalar/ai_api_key', 'apiKey');
  await sealScalarSecret('••••', store, 'scalar/ai_api_key', 'apiKey');
  assert.equal(await openScalarSecret('<redacted>', store, 'scalar/ai_api_key', 'apiKey'), 'sk-scalar-real');

  // SSH credential codec placeholder rejection
  const binding = { host: '10.0.0.1', port: 22, username: 'root', authMethod: 'password' as const };
  assert.throws(() => encodeSSHCredential(binding, { secret: '<redacted>', passphrase: null }), /credential_invalid/);
  assert.throws(() => encodeSSHCredential(binding, { secret: '***', passphrase: null }), /credential_invalid/);
  assert.throws(() => decodeSSHCredential(JSON.stringify({
    host: '10.0.0.1', port: 22, username: 'root', authMethod: 'password', secret: '••••', passphrase: null,
  }), binding), /credential_invalid/);
});

test('tool credentials are masked before compaction summary input, recap input, and Jev auto-approval batch', () => {
  const toolPart: UIMessagePartTool = {
    type: 'tool',
    toolCallId: 'call-1',
    toolName: 'http_request',
    input: JSON.stringify({ url: 'https://api.example.com', headers: { Authorization: 'Bearer sk-live-secret-999' } }),
    output: [{
      type: 'text',
      text: JSON.stringify({ api_key: 'sk-output-secret-888', ok: true }),
      metadata: null,
    }],
    approvalState: { type: 'approved' },
    metadata: null,
  };

  const summaryLine = partSummaryLine(toolPart);
  assert.equal(summaryLine.includes('sk-live-secret-999'), false);
  assert.equal(summaryLine.includes('sk-output-secret-888'), false);
  assert.equal(summaryLine.includes('***'), true);

  const conv = makeConversation('c-recap', [
    toMessageNode(makeUserMessage('u1')),
    toMessageNode(makeUIMessage('assistant', [toolPart])),
    toMessageNode(makeUserMessage('u2')),
    toMessageNode(makeUserMessage('u3')),
  ]);
  const recapInput = makeRecapInput(conv, null);
  assert.ok(recapInput !== null);
  assert.equal(recapInput.prompt.includes('sk-live-secret-999'), false);
  assert.equal(recapInput.prompt.includes('sk-output-secret-888'), false);

  const jevBatch = buildJevAutoApprovalBatch(toolPart, {
    action: 'allow',
    reason: 'test',
    source: 'test',
    trace: {
      traceId: 'trace-1',
      toolName: 'http_request',
      invocationContext: 'normal',
      policy: null,
      autoApproveTools: true,
      autoApproveHighRiskTools: false,
      autoApprovedByRun: false,
      approvalState: 'pending',
      action: 'allow',
      source: 'test',
      reason: 'test',
    },
  }, ['请调用接口']);
  assert.equal(String(jevBatch.state['parameters']).includes('sk-live-secret-999'), false);
  assert.equal(String(jevBatch.state['parameters']).includes('***'), true);
});
