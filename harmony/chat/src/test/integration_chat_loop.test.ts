// 集成测试:Chat 主回路端到端(source-only 系统测试)
//
// 链路: runChatTurn
//   → applyInputTransformers(Template)
//   → asChatStreamProvider → createOpenAIChatApi(buildChatCompletionRequest)
//   → 假 HttpClient 回放 SSE(reasoning_content + think tag + 流式 tool_calls 两段)
//   → SseAssembler → parseOpenAiStreamEventData → MessageStreamAccumulator
//   → 节流 onUpdate(streamingTail visual: thinkTag + regex)
//   → applyOnGenerationFinish(thinkTag 落库形态)
//   → ConversationStore 落会话 → serialize/parse round-trip → memory repository
//
// 价值:锁定各切片模块间的集成契约(接口形状/chunk 语义/transformer 次序),
// 设备端 adapter 接入前的最后一道 source-only 防线。

import { test } from 'node:test';
import assert from 'node:assert/strict';

import type { HttpClient, HttpRequest, HttpResponse } from '@amber/deepread-domain';

import { runChatTurn, createMemoryConversationStore } from '../main/ets/chat/chat_turn.ts';
import { createOpenAIChatApi, asChatStreamProvider } from '../main/ets/chat/openai_chat_api.ts';
import { makeAssistant, makeAssistantRegex } from '../main/ets/chat/assistant.ts';
import { createTemplateTransformer, thinkTagTransformer, regexOutputTransformer } from '../main/ets/chat/transformers.ts';
import { makeConversation, currentMessages } from '../main/ets/chat/conversation.ts';
import type { UIMessage, UIMessagePartReasoning, UIMessagePartTool } from '../main/ets/chat/message.ts';
import { makeChatModel, makeProviderSettingOpenAI, makeTextGenerationParams } from '../main/ets/chat/provider_model.ts';
import { serializeMessageList, parseMessageList } from '../main/ets/chat/serialize.ts';
import { createMemoryConversationRepository } from '../main/ets/chat/persistence.ts';
import type { JsonObject } from '../main/ets/chat/json.ts';

const enc = new TextEncoder();

const SSE = [
  'data: {"id":"c1","model":"gpt-4o","choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"推理"},"finish_reason":null}]}',
  '',
  'data: {"id":"c1","model":"gpt-4o","choices":[{"index":0,"delta":{"content":"<think>内省</think>坏消息"},"finish_reason":null}]}',
  '',
  'data: {"id":"c1","model":"gpt-4o","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"search","arguments":"{\\"q\\":"}}]},"finish_reason":null}]}',
  '',
  'data: {"id":"c1","model":"gpt-4o","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"x\\"}"}}]},"finish_reason":null}]}',
  '',
  'data: {"id":"c1","model":"gpt-4o","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}',
  '',
  'data: [DONE]',
  '',
].join('\n');

const fakeHttp = (capture: { req: HttpRequest | null }): HttpClient => ({
  fetch: (): Promise<HttpResponse> => Promise.resolve({ status: 200, headers: {}, body: '{}' }),
  fetchStream: (req: HttpRequest, opts: { onChunk: (c: ArrayBuffer, end: boolean) => void }): Promise<HttpResponse> => {
    capture.req = req;
    // 分两段投递,模拟真实网络分包
    const half = SSE.length >> 1;
    opts.onChunk(enc.encode(SSE.slice(0, half)).buffer as ArrayBuffer, false);
    opts.onChunk(enc.encode(SSE.slice(half)).buffer as ArrayBuffer, true);
    return Promise.resolve({ status: 200, headers: {}, body: '' });
  },
});

test('集成: 主回路端到端 — 模板入参/流式累积/visual 节流/终态落库/序列化往返/仓储', async () => {
  const capture: { req: HttpRequest | null } = { req: null };
  const api = createOpenAIChatApi({
    http: fakeHttp(capture),
    setting: makeProviderSettingOpenAI({}),
  });
  const params = makeTextGenerationParams({ model: makeChatModel({ modelId: 'gpt-4o' }) });
  const provider = asChatStreamProvider(api, () => params);

  const assistant = makeAssistant({
    messageTemplate: '总结:{{message}}',
    // Android quirk(RegexOutputTransformer.kt:47,53):visualTransform 内硬编码 visual=false,
    // 即 visualTransform 命中的是 visualOnly=false 的规则;visualOnly=true 由渲染层另走(D-012 忠实保留)
    regexes: [makeAssistantRegex({ findRegex: '坏', replaceString: '**', affectingScope: ['assistant'] })],
  });
  const store = createMemoryConversationStore();
  const updates: UIMessage[][] = [];

  const conv0 = makeConversation('conv-int', []);
  const final = await runChatTurn(conv0, '讲个故事', {
    assistant,
    inputTransformers: [createTemplateTransformer()],
    outputTransformers: [thinkTagTransformer, regexOutputTransformer],
    provider,
    store,
    flushIntervalMs: 0,
    onUpdate: (msgs: UIMessage[]): void => { updates.push(msgs); },
  });

  // 1. 请求侧:模板作用于发往 provider 的 user 消息
  const body = JSON.parse(capture.req?.body ?? '{}') as JsonObject;
  const sentMessages = body['messages'] as JsonObject[];
  assert.equal(sentMessages[sentMessages.length - 1]['content'], '总结:讲个故事');

  // 2. 会话:两次保存(user 入列 + 终态),user + 1 assistant 节点
  assert.equal(store.saved.length, 2);
  const finalMessages = currentMessages(final);
  assert.equal(finalMessages.length, 2);
  assert.equal(finalMessages[0].role, 'user');
  const ai = finalMessages[1];
  assert.equal(ai.role, 'assistant');

  // 3. 终态 parts:provider reasoning + thinkTag 落库(reasoning+strip 后文本) + tool
  const kinds = ai.parts.map((p): string => p.type);
  assert.deepEqual(kinds, ['reasoning', 'reasoning', 'text', 'tool']);
  assert.equal((ai.parts[0] as UIMessagePartReasoning).reasoning, '推理');
  assert.equal((ai.parts[1] as UIMessagePartReasoning).reasoning, '内省',
    'think tag 经 onGenerationFinish 转 reasoning part 落库');
  assert.equal(ai.parts[2].type === 'text' ? ai.parts[2].text : '', '坏消息',
    'RegexOutputTransformer 不实现 onGenerationFinish(缺省恒等),落库文本不变');
  const tool = ai.parts[3] as UIMessagePartTool;
  assert.equal(tool.toolName, 'search');
  assert.equal(tool.input, '{"q":"x"}', '两段流式 arguments 合并');

  // 4. streamingTail visual:末次 onUpdate 的 assistant 尾消息 — think 剥离 + regex(visual=false 规则)生效
  assert.ok(updates.length > 0);
  const tail = updates[updates.length - 1][1];
  const tailText = tail.parts.find((p): boolean => p.type === 'text');
  assert.ok(tailText !== undefined && tailText.type === 'text');
  assert.equal(tailText.text, '**消息', 'visual: think tag 剥离 + regex 替换(不落库)');

  // 5. 序列化 round-trip(kotlinx 线格式)
  const blob = serializeMessageList(finalMessages);
  assert.deepEqual(parseMessageList(blob), finalMessages);

  // 6. 仓储:保存后可按 id 取回,节点结构一致
  const repo = createMemoryConversationRepository();
  await repo.save(final);
  const loaded = await repo.getById('conv-int');
  assert.ok(loaded !== null);
  assert.deepEqual(currentMessages(loaded!), finalMessages);
});
