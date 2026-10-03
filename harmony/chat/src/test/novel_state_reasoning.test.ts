import { test } from 'node:test';
import assert from 'node:assert/strict';
import { applyNovelStateReasoning } from '../main/ets/chat/novel_state_reasoning.ts';
import { makeNovelTextGenerationParams } from '../main/ets/chat/novel_request_config.ts';
import { makeChatModel, makeTextGenerationParams, makeProviderSettingOpenAI } from '../main/ets/chat/provider_model.ts';
import { makeProviderSettingOpenAIVariant } from '../main/ets/chat/provider_settings.ts';
import { buildChatCompletionRequest } from '../main/ets/chat/openai_request.ts';
import { buildResponsesRequestBody } from '../main/ets/chat/openai_responses_request.ts';
import { makeUserMessage } from '../main/ets/chat/message.ts';
const off = { kind: 'stateRebuild' as const, reasoningEnabled: false };
const paramsFor = (modelId: string) => makeNovelTextGenerationParams(makeTextGenerationParams({
  model: makeChatModel({ modelId, abilities: ['reasoning'] }), reasoningLevel: 'high',
}), [], 1000, off);

test('state-only official supported models send explicit none through actual OpenAI builders', () => {
  for (const modelId of ['gpt-5.1', 'gpt-5.2', 'gpt-5.4', 'gpt-5.5', 'gpt-6-sol', 'gpt-6-luna', 'gpt-5.2-2025-12-11']) {
    for (const useResponseApi of [false, true]) {
      const params = applyNovelStateReasoning(paramsFor(modelId), makeProviderSettingOpenAIVariant({ useResponseApi }), off);
      const input = { messages: [makeUserMessage('state')], params, setting: makeProviderSettingOpenAI({}), stream: true };
      const body = useResponseApi ? buildResponsesRequestBody(input) : buildChatCompletionRequest(input);
      assert.deepEqual(useResponseApi ? body.reasoning : body.reasoning_effort, useResponseApi ? { effort: 'none' } : 'none');
    }
  }
});
test('ordinary, enabled, unsupported model and other endpoint params retain exact identity', () => {
  const params = paramsFor('gpt-5.5');
  const official = makeProviderSettingOpenAIVariant({});
  assert.equal(applyNovelStateReasoning(params, official), params);
  assert.equal(applyNovelStateReasoning(params, official, { ...off, reasoningEnabled: true }), params);
  for (const modelId of ['gpt-5', 'o3', 'gpt-6-astra', 'gpt-6.1-sol', 'gpt-5.5-pro', 'gpt-5.5-codex']) {
    const other = paramsFor(modelId);
    assert.equal(applyNovelStateReasoning(other, official, off), other);
  }
  assert.equal(applyNovelStateReasoning(params, makeProviderSettingOpenAIVariant({ baseUrl: 'https://api.openai.com.example/v1' }), off), params);
  const plain = { ...params, model: { ...params.model, abilities: [] } };
  assert.equal(applyNovelStateReasoning(plain, official, off), plain);
});
