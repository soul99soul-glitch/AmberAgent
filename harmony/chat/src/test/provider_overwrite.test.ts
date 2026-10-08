// D-084 测试 — Model.findProvider(PreferencesStore.kt:500-518)+
//   copyProvider(models=…)(ProviderSetting.kt:156-165 data class copy 语义)
import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  copyProviderSettingWithModels,
  findProviderForModel,
  makeProviderModel,
  makeProviderSettingClaude,
  makeProviderSettingGoogle,
  makeProviderSettingOpenAIVariant,
} from '../main/ets/chat/provider_settings.ts';
import type {
  ProviderModel,
  ProviderSetting,
} from '../main/ets/chat/provider_settings.ts';

const container = (models: ProviderModel[]): ProviderSetting =>
  makeProviderSettingOpenAIVariant({
    id: 'p-container', name: 'Container', apiKey: 'container-key',
    baseUrl: 'https://container.example/v1', models,
  });

test('findProviderForModel: 容器未命中 → null(overwrite 不兜底,:501)', () => {
  const overwrite: ProviderSetting = makeProviderSettingClaude({
    id: 'p-over', apiKey: 'over-key',
  });
  const orphan: ProviderModel = makeProviderModel({ id: 'm-x', providerOverwrite: overwrite });
  assert.equal(findProviderForModel([container([])], orphan), null);
});

test('findProviderForModel: 命中 + overwrite → overwrite 副本,models=[],变体字段全保留', () => {
  const overwrite: ProviderSetting = makeProviderSettingGoogle({
    id: 'p-over', apiKey: 'over-key', baseUrl: 'https://over.example',
    vertexAI: true, location: 'asia-east1', projectId: 'proj-1',
  });
  const m: ProviderModel = makeProviderModel({ id: 'm-1', providerOverwrite: overwrite });
  const r: ProviderSetting | null = findProviderForModel([container([m])], m);
  assert.ok(r !== null);
  assert.equal(r.type, 'google');
  assert.equal(r.id, 'p-over');
  assert.equal(r.models.length, 0); // copyProvider(models = emptyList())
  if (r.type === 'google') {
    assert.equal(r.apiKey, 'over-key');
    assert.equal(r.baseUrl, 'https://over.example');
    assert.equal(r.vertexAI, true);
    assert.equal(r.location, 'asia-east1');
    assert.equal(r.projectId, 'proj-1');
  }
});

test('findProviderForModel: checkOverwrite=false → 忽略 overwrite 返回容器', () => {
  const overwrite: ProviderSetting = makeProviderSettingClaude({ id: 'p-over' });
  const m: ProviderModel = makeProviderModel({ id: 'm-1', providerOverwrite: overwrite });
  const c: ProviderSetting = container([m]);
  const r: ProviderSetting | null = findProviderForModel([c], m, false);
  assert.equal(r, c);
});

test('copyProviderSettingWithModels: 三变体显式字段保留 + models 替换', () => {
  const m2: ProviderModel = makeProviderModel({ id: 'm-2' });
  const o: ProviderSetting = makeProviderSettingOpenAIVariant({
    id: 'p-o', enabled: false, name: 'Custom', apiKey: 'k',
    baseUrl: 'https://o.example', chatCompletionsPath: '/c',
    useResponseApi: true, authMode: 'api_key', brand: 'deepseek',
  });
  const c: ProviderSetting = copyProviderSettingWithModels(o, [m2]);
  assert.equal(c.type, 'openai');
  assert.equal(c.id, 'p-o');
  assert.equal(c.enabled, false);
  assert.equal(c.name, 'Custom');
  assert.equal(c.models.length, 1);
  assert.equal(c.models[0].id, 'm-2');
  if (c.type === 'openai') {
    assert.equal(c.apiKey, 'k');
    assert.equal(c.chatCompletionsPath, '/c');
    assert.equal(c.useResponseApi, true);
    assert.equal(c.brand, 'deepseek');
  }
  const claude: ProviderSetting = makeProviderSettingClaude({
    id: 'p-c', promptCaching: true, apiKey: 'ck',
  });
  const cc: ProviderSetting = copyProviderSettingWithModels(claude, []);
  assert.equal(cc.type, 'claude');
  if (cc.type === 'claude') {
    assert.equal(cc.promptCaching, true);
    assert.equal(cc.apiKey, 'ck');
  }
});
