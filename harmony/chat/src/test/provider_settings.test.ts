// ProviderSetting 持久化线格式规格测试
//
// Android 基准:
//   ai/provider/ProviderSetting.kt(sealed: openai/google/claude,@SerialName 鉴别名,
//     @Transient builtIn/description/shortDescription 不序列化)
//   ai/provider/Model.kt(全字段 + BuiltInTools sealed + providerOverwrite 递归)
//   ai/util/Json.kt(encodeDefaults/explicitNulls=false/ignoreUnknownKeys)
//   存储形态:Settings DataStore providers: List<ProviderSetting> JSON
//
// 决定(D-017):
//   - apiKey/privateKey 等 secret 是静息持久化字段,进线格式(不进日志/请求侧纯逻辑,
//     请求侧 provider_model.ts 保持 secret-free,adapter 做映射)
//   - 领域枚举小写 union ↔ 线格式大写/serial 名;sealed 鉴别 "type" 在最前
//   - ChatModel(请求侧子集)与 ProviderModel(持久化全量)并存,adapter 映射

import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  makeProviderSettingOpenAIVariant,
  makeProviderSettingGoogle,
  makeProviderSettingClaude,
  makeProviderModel,
  hasUsableAuth,
} from '../main/ets/chat/provider_settings.ts';
import {
  serializeProviderSetting,
  parseProviderSetting,
  serializeProviderSettingList,
  parseProviderSettingList,
} from '../main/ets/chat/provider_settings_serialize.ts';
import { createMemoryKeyValueStore, saveProviders, loadProviders } from '../main/ets/chat/kv_store.ts';

const FIXED_ID = '11111111-2222-3333-4444-555555555555';

test('golden: Model 全字段(枚举大写,BuiltInTools sealed,可空省略/出现)', () => {
  const m = makeProviderModel({
    modelId: 'gpt-4o', displayName: 'GPT-4o', id: FIXED_ID,
    type: 'chat',
    customHeaders: [{ name: 'X', value: '1' }],
    customBodies: [{ key: 'k', value: true }],
    inputModalities: ['text', 'image'],
    outputModalities: ['text'],
    abilities: ['tool', 'reasoning'],
    tools: ['search', 'url_context'],
    contextWindowTokens: 128000,
  });
  const s = makeProviderSettingOpenAIVariant({ id: FIXED_ID, models: [m] });
  const json = serializeProviderSetting(s);
  const obj = JSON.parse(json) as { models: Array<Record<string, unknown>> };
  const w = obj.models[0];
  assert.equal(w['type'], 'CHAT');
  assert.deepEqual(w['inputModalities'], ['TEXT', 'IMAGE']);
  assert.deepEqual(w['abilities'], ['TOOL', 'REASONING']);
  assert.deepEqual(w['tools'], [{ type: 'search' }, { type: 'url_context' }]);
  assert.equal(w['contextWindowTokens'], 128000);
  assert.deepEqual(w['customHeaders'], [{ name: 'X', value: '1' }]);
  assert.deepEqual(w['customBodies'], [{ key: 'k', value: true }]);
  assert.deepEqual(parseProviderSetting(json), s, 'round-trip');
  // 可空省略
  const noCtx = serializeProviderSetting(makeProviderSettingOpenAIVariant({
    id: FIXED_ID, models: [makeProviderModel({ id: FIXED_ID })],
  }));
  assert.ok(!noCtx.includes('contextWindowTokens'));
  assert.ok(!noCtx.includes('providerOverwrite'));
});

test('golden: Google/Claude variant 键序与字段;旧 JSON 缺 brand/authMode → 默认', () => {
  const g = makeProviderSettingGoogle({
    id: FIXED_ID, apiKey: 'gk', vertexAI: true, useServiceAccount: true,
    privateKey: 'pk', serviceAccountEmail: 'sa@x', location: 'asia-east1', projectId: 'p1',
    authMode: 'gemini_code_assist_oauth',
  });
  const gj = serializeProviderSetting(g);
  assert.ok(gj.startsWith('{"type":"google",'));
  const gobj = JSON.parse(gj) as Record<string, unknown>;
  assert.equal(gobj['baseUrl'], 'https://generativelanguage.googleapis.com/v1beta');
  assert.equal(gobj['authMode'], 'gemini_code_assist_oauth');
  assert.deepEqual(parseProviderSetting(gj), g);

  const c = makeProviderSettingClaude({ id: FIXED_ID, apiKey: 'ck', promptCaching: true });
  const cj = serializeProviderSetting(c);
  assert.ok(cj.startsWith('{"type":"claude",'));
  assert.equal((JSON.parse(cj) as Record<string, unknown>)['promptCaching'], true);
  assert.deepEqual(parseProviderSetting(cj), c);

  // 旧 JSON:缺 brand/authMode(ProviderSetting.kt:183-190 注释口径)
  const old = '{"type":"openai","id":"x","enabled":true,"name":"n","models":[],'
    + '"balanceOption":{"enabled":false,"apiPath":"/credits","resultPath":"data.total_usage"},'
    + '"apiKey":"","baseUrl":"https://api.deepseek.com/v1","chatCompletionsPath":"/chat/completions",'
    + '"useResponseApi":false}';
  const parsed = parseProviderSetting(old);
  assert.equal(parsed.type, 'openai');
  if (parsed.type === 'openai') {
    assert.equal(parsed.brand, 'generic');
    assert.equal(parsed.authMode, 'api_key');
  }
});

test('providerOverwrite 递归(一层嵌套 round-trip)', () => {
  const inner = makeProviderSettingClaude({ id: 'inner', apiKey: 'k2' });
  const m = makeProviderModel({ id: FIXED_ID, modelId: 'claude-x', providerOverwrite: inner });
  const s = makeProviderSettingOpenAIVariant({ id: FIXED_ID, models: [m] });
  const json = serializeProviderSetting(s);
  const obj = JSON.parse(json) as { models: Array<Record<string, unknown>> };
  const ow = obj.models[0]['providerOverwrite'] as Record<string, unknown>;
  assert.equal(ow['type'], 'claude');
  assert.deepEqual(parseProviderSetting(json), s);
});

test('hasUsableAuth 矩阵(ProviderSetting.kt:389-408)', () => {
  assert.equal(hasUsableAuth(makeProviderSettingOpenAIVariant({ enabled: false, apiKey: 'k' })), false);
  assert.equal(hasUsableAuth(makeProviderSettingOpenAIVariant({ apiKey: '' })), false);
  assert.equal(hasUsableAuth(makeProviderSettingOpenAIVariant({ apiKey: 'k' })), true);
  assert.equal(hasUsableAuth(makeProviderSettingOpenAIVariant({
    apiKey: 'k', useResponseApi: true,
  })), true); // Responses API 已落地(spiral Phase 1)
  assert.equal(hasUsableAuth(makeProviderSettingOpenAIVariant({ authMode: 'codex_oauth' })), false);
  assert.equal(hasUsableAuth(makeProviderSettingGoogle({ apiKey: 'k' })), true);
  assert.equal(hasUsableAuth(makeProviderSettingGoogle({ authMode: 'gemini_code_assist_oauth' })), false);
  assert.equal(hasUsableAuth(makeProviderSettingGoogle({
    vertexAI: true, useServiceAccount: true, privateKey: 'pk',
  })), false);
  assert.equal(hasUsableAuth(makeProviderSettingGoogle({
    vertexAI: true, useServiceAccount: true, privateKey: '',
  })), false);
  assert.equal(hasUsableAuth(makeProviderSettingClaude({ apiKey: 'k' })), true);
  assert.equal(hasUsableAuth(makeProviderSettingClaude({ apiKey: '' })), false);
});

test('list + KV 存取;未知鉴别名抛错', async () => {
  const list = [
    makeProviderSettingOpenAIVariant({ id: 'p1' }),
    makeProviderSettingGoogle({ id: 'p2' }),
    makeProviderSettingClaude({ id: 'p3' }),
  ];
  const raw = serializeProviderSettingList(list);
  assert.deepEqual(parseProviderSettingList(raw), list);
  const store = createMemoryKeyValueStore();
  await saveProviders(store, list);
  assert.deepEqual(await loadProviders(store), list);
  assert.equal(await loadProviders(createMemoryKeyValueStore()), null);
  assert.throws(() => parseProviderSetting('{"type":"unknown_x","id":"y"}'), /unknown ProviderSetting type/);
});
