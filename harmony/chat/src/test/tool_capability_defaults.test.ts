import { test } from 'node:test';
import assert from 'node:assert/strict';
import { makeProviderModel, makeProviderSettingOpenAIVariant } from '../main/ets/chat/provider_settings.ts';
import { parseProviderSetting, serializeProviderSetting } from '../main/ets/chat/provider_settings_serialize.ts';
import { toChatModel, buildToolSystemPrompt } from '../main/ets/chat/context_assembly.ts';
import { makeProviderSettingOpenAI, makeTextGenerationParams } from '../main/ets/chat/provider_model.ts';
import { buildChatCompletionRequest } from '../main/ets/chat/openai_request.ts';
import { createToolSearchTool } from '../main/ets/chat/builtin_introspection_tools.ts';
import { createToolRegistry } from '../main/ets/chat/tool_registry.ts';
import { toChatToolDefinition } from '../main/ets/chat/tool.ts';

test('new chat models default to function tools; image and embedding models do not', () => {
  for (const modelId of ['mimo-v2.6-flash', 'mimo-v2.6-pro', 'deepseek-chat', 'custom-chat']) {
    assert.ok(makeProviderModel({ modelId }).abilities.includes('tool'), modelId);
  }
  for (const type of ['image', 'embedding'] as const) {
    assert.deepEqual(makeProviderModel({ modelId: 'other', type }).abilities, []);
  }
});

test('legacy saved chat models with empty abilities gain tools and send their discovery schemas', () => {
  const provider = parseProviderSetting(JSON.stringify({ type: 'openai', models: [
    { id: 'flash', modelId: 'mimo-v2.6-flash', type: 'CHAT', abilities: [] },
    { id: 'pro', modelId: 'mimo-v2.6-pro', type: 'CHAT', abilities: ['REASONING'] },
    { id: 'image', modelId: 'image', type: 'IMAGE', abilities: [] },
  ] }));
  assert.deepEqual(provider.models[0].abilities, ['tool']);
  assert.deepEqual(provider.models[1].abilities, ['reasoning', 'tool']);
  assert.deepEqual(provider.models[2].abilities, []);
  const model = toChatModel(provider.models[0]);
  const search = createToolSearchTool(createToolRegistry([]));
  const request = buildChatCompletionRequest({ messages: [], stream: true,
    setting: makeProviderSettingOpenAI({ authMode: 'mimo_coding_plan', baseUrl: 'https://token-plan-cn.xiaomimimo.com/v1' }),
    params: makeTextGenerationParams({ model, tools: [toChatToolDefinition(search)] }),
  });
  assert.ok(Array.isArray(request['tools']));
  assert.ok(buildToolSystemPrompt([search], model, []).includes('Tool discovery:'));
});

test('explicitly disabling tools survives repeated saves and reloads', () => {
  let provider = makeProviderSettingOpenAIVariant({ models: [
    makeProviderModel({ modelId: 'mimo-v2.6-flash', abilities: [] }),
    makeProviderModel({ modelId: 'mimo-v2.6-pro', abilities: ['reasoning'] }),
  ] });
  for (let i = 0; i < 3; i++) {
    provider = parseProviderSetting(serializeProviderSetting(provider)) as typeof provider;
    assert.deepEqual(provider.models[0].abilities, []);
    assert.deepEqual(provider.models[1].abilities, ['reasoning']);
  }
});
