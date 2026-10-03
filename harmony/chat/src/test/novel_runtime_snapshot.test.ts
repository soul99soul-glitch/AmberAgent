import { test } from 'node:test';
import assert from 'node:assert/strict';
import { freezeNovelRuntimeConfiguration, readNovelRuntimeConfiguration } from '../main/ets/chat/novel_runtime_snapshot.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import { makeProviderModel, makeProviderSettingOpenAIVariant } from '../main/ets/chat/provider_settings.ts';
import { makeChatModel, makeTextGenerationParams, makeProviderSettingOpenAI } from '../main/ets/chat/provider_model.ts';
import { makeUserMessage } from '../main/ets/chat/message.ts';
import { buildChatCompletionRequest } from '../main/ets/chat/openai_request.ts';

test('cold runtime snapshot retains actual generation body after settings change and excludes credentials', () => {
  const provider = makeProviderSettingOpenAIVariant({ apiKey: 'provider-secret' });
  const assistant = makeAssistant({ temperature: 0.4, customHeaders: [{ name: 'X-Secret', value: 'assistant-secret' }] });
  const model = makeProviderModel({ modelId: 'original-model', customHeaders: [{ name: 'X-Model', value: 'model-secret' }] });
  const params = makeTextGenerationParams({ model: makeChatModel({ modelId: model.modelId }), temperature: 0.4,
    topP: 0.8, maxTokens: 1234, customBody: [{ key: 'seed', value: 42 }] });
  const input = { messages: [makeUserMessage('original request')], params, setting: makeProviderSettingOpenAI({}), stream: true };
  const originalBody = buildChatCompletionRequest(input);
  const raw = freezeNovelRuntimeConfiguration(provider, model, assistant, params, 16384);
  for (const secret of ['provider-secret', 'assistant-secret', 'model-secret']) assert.equal(raw.includes(secret), false);
  params.temperature = 0.9; params.model.modelId = 'changed-model'; params.customBody[0].value = 99;
  assistant.temperature = 0.9;
  const restored = readNovelRuntimeConfiguration(raw, { ...provider, apiKey: 'rotated-live-key' }, model, assistant);
  assert.deepEqual(buildChatCompletionRequest({ ...input, params: restored.params }), originalBody);
  assert.equal(restored.contextWindowTokens, 16384);
  assert.deepEqual(restored.assistant.customHeaders, []);
  assert.equal(restored.assistant.temperature, 0.4);
  assert.doesNotThrow(() => readNovelRuntimeConfiguration(raw, provider, model,
    { ...assistant, customHeaders: [{ name: ' X-Secret ', value: ' assistant-secret ' }] }));
});

test('exact retry refuses changed endpoint or configured headers while allowing credential rotation', () => {
  const provider = makeProviderSettingOpenAIVariant({});
  const model = makeProviderModel({ modelId: 'original' });
  const assistant = makeAssistant({});
  const raw = freezeNovelRuntimeConfiguration(provider, model, assistant, makeTextGenerationParams({}), null);
  assert.doesNotThrow(() => readNovelRuntimeConfiguration(raw, { ...provider, apiKey: 'new-key' }, model, assistant));
  assert.throws(() => readNovelRuntimeConfiguration(raw, { ...provider, baseUrl: 'https://different.invalid/v1' }, model, assistant), /接口设置已改变/);
  assert.throws(() => readNovelRuntimeConfiguration(raw, { ...provider, useResponseApi: true }, model, assistant), /接口设置已改变/);
  assert.throws(() => readNovelRuntimeConfiguration(raw, { ...provider, id: 'different-effective-provider' }, model, assistant), /接口设置已改变/);
  assert.throws(() => readNovelRuntimeConfiguration(raw, provider, model,
    { ...assistant, customHeaders: [{ name: 'New-Header', value: 'new-value' }] }), /请求头已改变/);
  assert.throws(() => readNovelRuntimeConfiguration('{', provider, model, assistant), /无法读取/);
  assert.throws(() => readNovelRuntimeConfiguration('{}', provider, model, assistant), /不完整/);
  assert.throws(() => readNovelRuntimeConfiguration(JSON.stringify({ version: 1, assistant: null,
    params: { model: { modelId: 7 } }, contextWindowTokens: null }), provider, model, assistant), /不完整/);
});
