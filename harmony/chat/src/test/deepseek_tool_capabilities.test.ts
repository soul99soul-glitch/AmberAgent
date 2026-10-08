import { test } from 'node:test';
import assert from 'node:assert/strict';
import { makeProviderModel } from '../main/ets/chat/provider_settings.ts';
import { makeProviderSettingOpenAI, makeTextGenerationParams } from '../main/ets/chat/provider_model.ts';
import { buildChatCompletionRequest } from '../main/ets/chat/openai_request.ts';
import { buildToolSystemPrompt, toChatModel } from '../main/ets/chat/context_assembly.ts';
import { createToolSearchTool } from '../main/ets/chat/builtin_introspection_tools.ts';
import { createToolRegistry } from '../main/ets/chat/tool_registry.ts';
import { toChatToolDefinition } from '../main/ets/chat/tool.ts';
import { makeUIMessage, makeUserMessage } from '../main/ets/chat/message.ts';
import type { JsonObject } from '../main/ets/chat/json.ts';

const search = createToolSearchTool(createToolRegistry([]));
const setting = makeProviderSettingOpenAI({ baseUrl: 'https://api.deepseek.com', brand: 'deepseek' });
const history = [makeUserMessage('上一轮问题'), makeUIMessage('assistant', [
  { type: 'reasoning', reasoning: '上一轮完整思考', createdAt: '2026-09-30T00:00:00.000Z', finishedAt: null, metadata: null },
  { type: 'text', text: '上一轮回答', metadata: null },
]), makeUserMessage('这次需要搜索')];

test('new official DeepSeek models send native tool schemas matching their discovery prompt', () => {
  for (const modelId of ['deepseek-flash', 'deepseek-v4-pro']) {
    const model = toChatModel(makeProviderModel({ modelId }));
    const request = buildChatCompletionRequest({
      messages: history, setting, stream: true,
      params: makeTextGenerationParams({ model, tools: [toChatToolDefinition(search)] }),
    });
    assert.ok(Array.isArray(request['tools']), `${modelId} must send the tool schema`);
    assert.ok(buildToolSystemPrompt([search], model, []).includes('Tool discovery:'));
    assert.deepEqual(request['thinking'], { type: 'enabled' });
    const assistant = (request['messages'] as JsonObject[]).find((m) => m['role'] === 'assistant');
    assert.equal(assistant?.['reasoning_content'], '上一轮完整思考');
    for (const variant of [
      { setting: makeProviderSettingOpenAI({ baseUrl: 'https://deepseek-proxy.example/v1' }), tools: [toChatToolDefinition(search)], reasoningLevel: 'auto' as const },
      { setting, tools: [], reasoningLevel: 'auto' as const },
      { setting, tools: [toChatToolDefinition(search)], reasoningLevel: 'off' as const },
    ]) {
      const bounded = buildChatCompletionRequest({
        messages: history, setting: variant.setting, stream: true,
        params: makeTextGenerationParams({ model, tools: variant.tools, reasoningLevel: variant.reasoningLevel }),
      });
      const historical = (bounded['messages'] as JsonObject[]).find((m) => m['role'] === 'assistant');
      assert.equal(historical?.['reasoning_content'], undefined, 'preservation stays scoped to official thinking tool requests');
    }
  }
});

test('an explicit empty capability setting neither sends tool schemas nor advertises discovery', () => {
  const model = toChatModel(makeProviderModel({ modelId: 'deepseek-flash', abilities: [] }));
  const request = buildChatCompletionRequest({
    messages: [], setting, stream: true,
    params: makeTextGenerationParams({ model, tools: [toChatToolDefinition(search)] }),
  });
  assert.equal(request['tools'], undefined);
  assert.equal(buildToolSystemPrompt([search], model, []), '');
  assert.deepEqual(makeProviderModel({ modelId: 'unregistered-deepseek-proxy' }).abilities, ['tool']);
});
