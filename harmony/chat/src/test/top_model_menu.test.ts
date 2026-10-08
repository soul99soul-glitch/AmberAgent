// top_model_menu 纯逻辑测试
//
// 覆盖:
//   - hasUsableAuth 过滤(disabled / 空 apiKey / oauth 可用)
//   - ModelType 过滤(chat 组不含 image/embedding)
//   - 无目标类型模型的 provider 整组丢弃(对齐 ProviderGroup 早返回)
//   - selected 标记(providerId+modelId 双命中)
//   - 选中模型所在 provider 不在结果时无 selected
//   - 空 providers(null / [])→ []
//   - contextWindowTokens 透传(含 null)

import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  makeProviderSettingOpenAIVariant, makeProviderSettingGoogle, makeProviderSettingClaude,
  makeProviderModel,
} from '../main/ets/chat/provider_settings.ts';
import { buildTopModelMenuGroups } from '../main/ets/chat/top_model_menu.ts';

const PID_A: string = 'prov-a';
const PID_B: string = 'prov-b';
const PID_C: string = 'prov-c';

for (const mode of ['zhipu_coding_plan', 'kimi_coding_plan', 'mimo_coding_plan', 'minimax_token_plan'] as const) {
  test(`${mode}: enabled provider with a saved key and chat model remains selectable beside DeepSeek`, () => {
    const deepseek = makeProviderSettingOpenAIVariant({
      id: PID_A, name: 'DeepSeek', apiKey: 'test-key',
      models: [makeProviderModel({ modelId: 'deepseek-chat' })],
    });
    const plan = makeProviderSettingOpenAIVariant({
      id: PID_B, name: mode, authMode: mode, apiKey: 'test-plan-key',
      models: [makeProviderModel({ modelId: 'plan-model' })],
    });
    const groups = buildTopModelMenuGroups([deepseek, plan], 'chat', PID_B, 'plan-model');
    assert.deepEqual(groups.map(group => group.providerId), [PID_A, PID_B]);
    assert.equal(groups[1].models[0].selected, true);
    assert.deepEqual(buildTopModelMenuGroups([{ ...plan, enabled: false }], 'chat', '', ''), []);
    assert.deepEqual(buildTopModelMenuGroups([{ ...plan, apiKey: '  ' }], 'chat', '', ''), []);
  });
}

test('空 providers(null) → []', () => {
  assert.deepEqual(buildTopModelMenuGroups(null, 'chat', '', ''), []);
});

test('空 providers([]) → []', () => {
  assert.deepEqual(buildTopModelMenuGroups([], 'chat', '', ''), []);
});

test('过滤 !hasUsableAuth 的 provider(disabled)', () => {
  const disabled = makeProviderSettingOpenAIVariant({
    id: PID_A, enabled: false, apiKey: 'sk-x',
    models: [makeProviderModel({ modelId: 'gpt-4o', type: 'chat' })],
  });
  assert.deepEqual(buildTopModelMenuGroups([disabled], 'chat', '', ''), []);
});

test('过滤 !hasUsableAuth 的 provider(空 apiKey 且非 oauth)', () => {
  const noKey = makeProviderSettingOpenAIVariant({
    id: PID_A, apiKey: '  ',
    models: [makeProviderModel({ modelId: 'gpt-4o', type: 'chat' })],
  });
  assert.deepEqual(buildTopModelMenuGroups([noKey], 'chat', '', ''), []);
});

test('ModelType 过滤:chat 组不含 image/embedding 模型', () => {
  const p = makeProviderSettingOpenAIVariant({
    id: PID_A, apiKey: 'sk-x',
    models: [
      makeProviderModel({ modelId: 'gpt-4o', type: 'chat', displayName: 'GPT-4o' }),
      makeProviderModel({ modelId: 'dall-e-3', type: 'image', displayName: 'DALL·E 3' }),
      makeProviderModel({ modelId: 'text-embed', type: 'embedding', displayName: 'Embed' }),
    ],
  });
  const groups = buildTopModelMenuGroups([p], 'chat', '', '');
  assert.equal(groups.length, 1);
  assert.equal(groups[0].models.length, 1);
  assert.equal(groups[0].models[0].modelId, 'gpt-4o');
});

test('image type 过滤:image 组不含 chat 模型', () => {
  const p = makeProviderSettingOpenAIVariant({
    id: PID_A, apiKey: 'sk-x',
    models: [
      makeProviderModel({ modelId: 'gpt-4o', type: 'chat' }),
      makeProviderModel({ modelId: 'dall-e-3', type: 'image' }),
    ],
  });
  const groups = buildTopModelMenuGroups([p], 'image', '', '');
  assert.equal(groups.length, 1);
  assert.equal(groups[0].models[0].modelId, 'dall-e-3');
});

test('无目标类型模型的 provider 整组丢弃(对齐 ProviderGroup 早返回)', () => {
  const onlyImage = makeProviderSettingOpenAIVariant({
    id: PID_A, apiKey: 'sk-x',
    models: [makeProviderModel({ modelId: 'dall-e-3', type: 'image' })],
  });
  assert.deepEqual(buildTopModelMenuGroups([onlyImage], 'chat', '', ''), []);
});

test('多 provider 分组:每个 hasUsableAuth 且含 chat 模型的 provider 一组', () => {
  const a = makeProviderSettingOpenAIVariant({
    id: PID_A, apiKey: 'sk-a',
    models: [makeProviderModel({ modelId: 'gpt-4o', type: 'chat', displayName: 'GPT-4o' })],
  });
  const b = makeProviderSettingClaude({
    id: PID_B, apiKey: 'sk-b',
    models: [makeProviderModel({ modelId: 'claude-sonnet', type: 'chat', displayName: 'Sonnet' })],
  });
  const groups = buildTopModelMenuGroups([a, b], 'chat', '', '');
  assert.equal(groups.length, 2);
  assert.equal(groups[0].providerId, PID_A);
  assert.equal(groups[0].providerName, 'OpenAI');
  assert.equal(groups[1].providerId, PID_B);
  assert.equal(groups[1].providerName, 'Claude');
});

test('selected 标记:providerId + modelId 双命中', () => {
  const a = makeProviderSettingOpenAIVariant({
    id: PID_A, apiKey: 'sk-a',
    models: [
      makeProviderModel({ modelId: 'gpt-4o', type: 'chat' }),
      makeProviderModel({ modelId: 'gpt-4o-mini', type: 'chat' }),
    ],
  });
  const groups = buildTopModelMenuGroups([a], 'chat', PID_A, 'gpt-4o-mini');
  assert.equal(groups[0].models[0].selected, false);
  assert.equal(groups[0].models[1].selected, true);
});

test('selected 仅 providerId 命中但 modelId 不命中 → 不选中', () => {
  const a = makeProviderSettingOpenAIVariant({
    id: PID_A, apiKey: 'sk-a',
    models: [makeProviderModel({ modelId: 'gpt-4o', type: 'chat' })],
  });
  const groups = buildTopModelMenuGroups([a], 'chat', PID_A, 'nonexistent');
  assert.equal(groups[0].models[0].selected, false);
});

test('选中模型所在 provider 已删除 → 结果中无 selected', () => {
  const a = makeProviderSettingOpenAIVariant({
    id: PID_A, apiKey: 'sk-a',
    models: [makeProviderModel({ modelId: 'gpt-4o', type: 'chat' })],
  });
  // current 指向已不存在的 PID_C
  const groups = buildTopModelMenuGroups([a], 'chat', PID_C, 'whatever');
  assert.equal(groups[0].models[0].selected, false);
});

test('currentModelId 为空(无选中) → 全部 selected=false', () => {
  const a = makeProviderSettingOpenAIVariant({
    id: PID_A, apiKey: 'sk-a',
    models: [
      makeProviderModel({ modelId: 'gpt-4o', type: 'chat' }),
      makeProviderModel({ modelId: 'gpt-4o-mini', type: 'chat' }),
    ],
  });
  const groups = buildTopModelMenuGroups([a], 'chat', PID_A, '');
  assert.equal(groups[0].models[0].selected, false);
  assert.equal(groups[0].models[1].selected, false);
});

test('currentProviderId 空、currentModelId 非空 → 视为无选中(需双命中)', () => {
  const a = makeProviderSettingOpenAIVariant({
    id: PID_A, apiKey: 'sk-a',
    models: [makeProviderModel({ modelId: 'gpt-4o', type: 'chat' })],
  });
  const groups = buildTopModelMenuGroups([a], 'chat', '', 'gpt-4o');
  assert.equal(groups[0].models[0].selected, false);
});

test('contextWindowTokens 透传(含 null)', () => {
  const a = makeProviderSettingOpenAIVariant({
    id: PID_A, apiKey: 'sk-a',
    models: [
      makeProviderModel({ modelId: 'gpt-4o', type: 'chat', contextWindowTokens: 128000 }),
      makeProviderModel({ modelId: 'legacy', type: 'chat', contextWindowTokens: null }),
    ],
  });
  const groups = buildTopModelMenuGroups([a], 'chat', '', '');
  assert.equal(groups[0].models[0].contextWindowTokens, 128000);
  assert.equal(groups[0].models[1].contextWindowTokens, null);
});

test('Google Code Assist OAuth is not selectable without API-key auth', () => {
  const g = makeProviderSettingGoogle({
    id: PID_B, apiKey: '  ', authMode: 'gemini_code_assist_oauth',
    models: [makeProviderModel({ modelId: 'gemini-pro', type: 'chat' })],
  });
  const groups = buildTopModelMenuGroups([g], 'chat', '', '');
  assert.deepEqual(groups, []);
});

test('provider.models 为空 → 该组丢弃', () => {
  const a = makeProviderSettingOpenAIVariant({
    id: PID_A, apiKey: 'sk-a', models: [],
  });
  assert.deepEqual(buildTopModelMenuGroups([a], 'chat', '', ''), []);
});

test('混合场景:多 provider 混合可用性 + 类型 + selected', () => {
  const a = makeProviderSettingOpenAIVariant({
    id: PID_A, apiKey: 'sk-a', name: 'OpenAI',
    models: [
      makeProviderModel({ modelId: 'gpt-4o', type: 'chat', displayName: 'GPT-4o', contextWindowTokens: 128000 }),
      makeProviderModel({ modelId: 'dall-e-3', type: 'image' }),
    ],
  });
  const bDisabled = makeProviderSettingClaude({
    id: PID_B, apiKey: 'sk-b', enabled: false,
    models: [makeProviderModel({ modelId: 'claude', type: 'chat' })],
  });
  const c = makeProviderSettingGoogle({
    id: PID_C, apiKey: 'sk-c', name: 'Google',
    models: [makeProviderModel({ modelId: 'gemini-pro', type: 'chat', displayName: 'Gemini Pro' })],
  });
  const groups = buildTopModelMenuGroups([a, bDisabled, c], 'chat', PID_C, 'gemini-pro');
  assert.equal(groups.length, 2); // a + c(b disabled 丢弃)
  assert.equal(groups[0].providerId, PID_A);
  assert.equal(groups[0].models.length, 1); // 仅 chat,image 过滤掉
  assert.equal(groups[1].providerId, PID_C);
  assert.equal(groups[1].models[0].selected, true);
  assert.equal(groups[0].models[0].selected, false);
});
