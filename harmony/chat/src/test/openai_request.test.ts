// OpenAI 请求侧纯逻辑规格测试
//
// Android 基准:
//   ChatCompletionsAPI.kt buildChatCompletionRequest(:294-475) / buildMessages(:498-517)
//     addAssistantMessages(:559-621) / buildAssistantMessageJson(:623-699)
//     addNonAssistantMessage(:702-737) / isMiMoProvider(:519-529)
//     shouldForceReasoningContentForToolCalls(:531-543) / isModelAllowTemperature(:490-496)
//   ProviderMessageUtils.kt groupPartsByToolBoundary(:26-58)
//   util/Request.kt mergeCustomBody(:48-68)
//   ChatCompletionsAPIMessageTest.kt(场景口径)
//
// 裁剪(D-014):
//   - apiKey/customHeaders 不进纯逻辑层(auth/header 是 adapter 关注点)
//   - image base64 编码注入 ImageEncoder Port(文件 IO 在 adapter);未注入/失败即抛错,
//     对齐 Android "image encoding failure should fail" 语义
//   - ModelRegistry token matcher 简化(D-014):o 系列 ^o\d+(-|$),gpt-5 startsWith 覆盖

import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  makeChatModel, makeProviderSettingOpenAI, makeTextGenerationParams,
} from '../main/ets/chat/provider_model.ts';
import type { ChatToolDefinition } from '../main/ets/chat/provider_model.ts';
import {
  hostOf, isMiMoProvider, shouldForceReasoningContentForToolCalls, isModelAllowTemperature,
  groupPartsByToolBoundary, buildMessages, buildChatCompletionRequest, mergeCustomBody,
} from '../main/ets/chat/openai_request.ts';
import { makeUserMessage, makeSystemMessage, makeUIMessage, makeAssistantMessage } from '../main/ets/chat/message.ts';
import type { UIMessage, UIMessagePartTool } from '../main/ets/chat/message.ts';
import type { JsonObject, JsonValue } from '../main/ets/chat/json.ts';

const encoder = (url: string): string => `data:image/png;base64,ENC(${url})`;

const executedTool = (callId: string, name: string, input: string, output: string): UIMessagePartTool => ({
  type: 'tool', toolCallId: callId, toolName: name, input,
  output: [{ type: 'text', text: output, metadata: null }],
  approvalState: { type: 'auto' }, metadata: null,
});

const reasoning = (text: string): import('../main/ets/chat/message.ts').UIMessagePartReasoning => ({
  type: 'reasoning', reasoning: text,
  createdAt: '2026-07-28T10:00:00.000Z', finishedAt: '2026-07-28T10:00:01.000Z', metadata: null,
});

const at = (arr: JsonValue[], i: number): JsonObject => arr[i] as JsonObject;
const str = (o: JsonObject, k: string): string => o[k] as string;

// ===== host / 判定函数 =====

test('hostOf: 去 scheme/path/port', () => {
  assert.equal(hostOf('https://api.openai.com/v1'), 'api.openai.com');
  assert.equal(hostOf('http://localhost:8080/v1'), 'localhost');
  assert.equal(hostOf('https://open.bigmodel.cn/api/paas/v4/'), 'open.bigmodel.cn');
});

test('isModelAllowTemperature: o 系列/gpt-5/codex 禁止,常规允许', () => {
  const m = (id: string): boolean => isModelAllowTemperature(makeChatModel({ modelId: id }));
  assert.equal(m('o1'), false);
  assert.equal(m('o3-mini'), false);
  assert.equal(m('gpt-5'), false);
  assert.equal(m('gpt-5.1'), false);
  assert.equal(m('codex-mini-latest'), false);
  assert.equal(m('gpt-4o'), true);
  assert.equal(m('deepseek-chat'), true);
  assert.equal(m('o1abc'), true, 'token 边界:^o\\d+ 后必须 - 或结尾(D-014 简化口径)');
});

test('isMiMoProvider / shouldForceReasoningContentForToolCalls 判定矩阵', () => {
  const mimo = makeProviderSettingOpenAI({ brand: 'mimo' });
  assert.equal(isMiMoProvider(mimo, 'api.xiaomimimo.com', 'mimo-vl'), true);
  assert.equal(isMiMoProvider(makeProviderSettingOpenAI({}), 'api.openai.com', 'gpt-4o'), false);
  assert.equal(isMiMoProvider(makeProviderSettingOpenAI({}), 'api.xiaomimimo.com', 'x'), true);
  assert.equal(isMiMoProvider(makeProviderSettingOpenAI({}), 'h', 'MiMo-7B'), true);

  const ds = makeProviderSettingOpenAI({ brand: 'deepseek' });
  const reasoningModel = makeChatModel({ abilities: ['tool', 'reasoning'] });
  const chatOnlyModel = makeChatModel({ abilities: ['tool'] });
  assert.equal(shouldForceReasoningContentForToolCalls(ds, 'api.deepseek.com', reasoningModel, 'auto'), true);
  assert.equal(shouldForceReasoningContentForToolCalls(ds, 'api.deepseek.com', reasoningModel, 'off'), false);
  assert.equal(shouldForceReasoningContentForToolCalls(ds, 'api.deepseek.com', chatOnlyModel, 'auto'), false);
  assert.equal(shouldForceReasoningContentForToolCalls(makeProviderSettingOpenAI({}), 'api.openai.com', reasoningModel, 'auto'), false);
});

// ===== groupPartsByToolBoundary =====

test('groupParts: 连续已执行 tool 成组,未执行 tool 留在 Content', () => {
  const parts = [
    { type: 'text' as const, text: 'T1', metadata: null },
    executedTool('c1', 'a', '{}', 'r1'),
    executedTool('c2', 'b', '{}', 'r2'),
    { type: 'text' as const, text: 'T2', metadata: null },
    { type: 'tool' as const, toolCallId: 'c3', toolName: 'c', input: '{}',
      output: [], approvalState: { type: 'auto' as const }, metadata: null },
  ];
  const groups = groupPartsByToolBoundary(parts);
  // 忠实对齐 Android:未执行 tool 走 else 分支进 currentContent,与前面的 T2 合并为同一组
  assert.deepEqual(groups.map((g): string => g.kind), ['content', 'tools', 'content']);
  assert.equal(groups[1].kind === 'tools' ? groups[1].tools.length : 0, 2);
  const last = groups[2];
  assert.equal(last.kind === 'content' ? last.parts.length : 0, 2, '未执行 tool 与前驱 Content 合并');
});

// ===== buildMessages =====

test('buildMessages: system 多 text 用 \\n\\n 连接;单 text 消息 content 为字符串', () => {
  const sys = makeUIMessage('system', [
    { type: 'text', text: 'p1', metadata: null },
    { type: 'text', text: 'p2', metadata: null },
  ]);
  const out = buildMessages([sys, makeUserMessage('hi')], {}, encoder);
  assert.equal(str(at(out, 0), 'content'), 'p1\n\np2');
  assert.equal(str(at(out, 1), 'content'), 'hi');
});

test('buildMessages: 多模态 user(text+image) → content 数组,image 经注入 encoder', () => {
  const msg = makeUIMessage('user', [
    { type: 'text', text: '看图', metadata: null },
    { type: 'image', url: 'file://x.png', metadata: null },
  ]);
  const out = buildMessages([msg], {}, encoder);
  const content = at(out, 0)['content'] as JsonValue[];
  assert.equal(str(at(content, 0), 'type'), 'text');
  const img = at(content, 1);
  assert.equal(str(img, 'type'), 'image_url');
  assert.equal(str(img['image_url'] as JsonObject, 'url'), 'data:image/png;base64,ENC(file://x.png)');
});

test('buildMessages: image 编码失败 → 抛错(不放空文本)', () => {
  const msg = makeUIMessage('user', [{ type: 'image', url: 'content://missing', metadata: null }]);
  const failing = (): string => { throw new Error('ImageEncodingException: io fail'); };
  assert.throws(() => buildMessages([msg], {}, failing), /ImageEncodingException/);
  assert.throws(() => buildMessages([msg], {}), /requires injected ImageEncoder/,
    '未注入 encoder 时也必须失败(对齐 image encoding failure should fail)');
});

test('buildMessages: 多轮 reasoning+tool 顺序(Android 6 消息场景)', () => {
  const assistant = makeUIMessage('assistant', [
    reasoning('Let me think'),
    { type: 'text', text: 'I will search', metadata: null },
    executedTool('call_1', 'search', '{"q":"t"}', 'Result 1'),
    reasoning('Now calculate'),
    { type: 'text', text: 'Let me calculate', metadata: null },
    executedTool('call_2', 'calculate', '{"e":"1+1"}', '2'),
    { type: 'text', text: 'The final answer is 2', metadata: null },
  ]);
  const out = buildMessages([makeUserMessage('1+1?'), assistant], {}, encoder);
  assert.ok(out.length >= 6);
  assert.equal(str(at(out, 0), 'role'), 'user');
  const a1 = at(out, 1);
  assert.equal(str(a1, 'role'), 'assistant');
  const tc1 = a1['tool_calls'] as JsonValue[];
  assert.equal(tc1.length, 1);
  assert.equal(str(at(tc1, 0)['function'] as JsonObject, 'name'), 'search');
  assert.equal(str(a1, 'reasoning_content'), 'Let me think', '最后 user 之后 → 带 reasoning');
  const t1 = at(out, 2);
  assert.equal(str(t1, 'role'), 'tool');
  assert.equal(str(t1, 'tool_call_id'), 'call_1');
  assert.equal(str(t1, 'content'), 'Result 1');
  const a2 = at(out, 3);
  assert.equal(str(at(a2['tool_calls'] as JsonValue[], 0)['function'] as JsonObject, 'name'), 'calculate');
  assert.equal(str(a2, 'reasoning_content'), 'Now calculate');
  assert.equal(str(at(out, 4), 'tool_call_id'), 'call_2');
  assert.equal(str(at(out, 5), 'content'), 'The final answer is 2');
});

test('buildMessages: 并行 tool 同组(3 tool_calls 一条 assistant + 3 条 tool 结果)', () => {
  const assistant = makeUIMessage('assistant', [
    { type: 'text', text: 'search all', metadata: null },
    executedTool('call_1', 'search_web', '{}', 'R1'),
    executedTool('call_2', 'search_docs', '{}', 'R2'),
    executedTool('call_3', 'search_wiki', '{}', 'R3'),
    { type: 'text', text: 'combined', metadata: null },
  ]);
  const out = buildMessages([makeUserMessage('go'), assistant], {}, encoder);
  const withTools = out.filter((m): boolean => (m as JsonObject)['role'] === 'assistant'
    && Array.isArray((m as JsonObject)['tool_calls']));
  assert.equal(withTools.length, 1);
  assert.equal((withTools[0] as JsonObject)['tool_calls'] !== undefined
    ? ((withTools[0] as JsonObject)['tool_calls'] as JsonValue[]).length : 0, 3);
  const toolResults = out.filter((m): boolean => (m as JsonObject)['role'] === 'tool');
  assert.equal(toolResults.length, 3);
});

test('buildMessages: reasoning 只带最后一条 user 之后的 assistant(非 MiMo)', () => {
  const a1 = makeUIMessage('assistant', [reasoning('old think'), { type: 'text', text: 'old', metadata: null }]);
  const a2 = makeUIMessage('assistant', [reasoning('new think'), { type: 'text', text: 'new', metadata: null }]);
  const out = buildMessages(
    [makeUserMessage('q1'), a1, makeUserMessage('q2'), a2], {}, encoder);
  const assistants = out.filter((m): boolean => (m as JsonObject)['role'] === 'assistant');
  assert.equal(assistants.length, 2);
  const firstHas = (assistants[0] as JsonObject)['reasoning_content'];
  assert.ok(firstHas === undefined || firstHas === '', '历史 assistant 不带 reasoning');
  assert.equal(str(assistants[1] as JsonObject, 'reasoning_content'), 'new think');
});

test('buildMessages: 历史 assistant(reasoning+空 text)被过滤;最新一条保留 reasoning_content', () => {
  const mk = (): UIMessage => makeUIMessage('assistant', [
    reasoning('thinking'), { type: 'text', text: '', metadata: null },
  ]);
  const history = buildMessages([makeUserMessage('q1'), mk(), makeUserMessage('q2')], {}, encoder);
  assert.equal(history.length, 2, '中间空 assistant 被丢弃');
  const latest = buildMessages([makeUserMessage('q1'), mk()], {}, encoder);
  assert.equal(latest.length, 2);
  assert.equal(str(at(latest, 1), 'reasoning_content'), 'thinking');
  assert.equal(str(at(latest, 1), 'content'), '');
});

test('buildMessages: forceReasoningContentForToolCalls(deepseek)给 tool 历史补空 reasoning_content', () => {
  const assistant = makeUIMessage('assistant', [
    { type: 'text', text: 'I will check', metadata: null },
    executedTool('call_1', 'skills_list', '{}', '[]'),
  ]);
  const out = buildMessages([makeUserMessage('use tool'), assistant], {
    forceReasoningContentForToolCalls: true,
  }, encoder);
  const a1 = at(out, 1);
  assert.equal(str(a1, 'reasoning_content'), '');
  assert.ok(Array.isArray(a1['tool_calls']));
});

test('buildMessages: 显式空 reasoning(reasoning_content 标记) round trip 不带占位文本', () => {
  const msg = makeUIMessage('assistant', [
    {
      type: 'reasoning', reasoning: '',
      createdAt: '2026-07-28T10:00:00.000Z', finishedAt: '2026-07-28T10:00:01.000Z',
      metadata: { reasoning_content_present: true },
    },
    { type: 'text', text: 'ok', metadata: null },
  ]);
  const out = buildMessages([makeUserMessage('hello'), msg], {}, encoder);
  const a = at(out, 1);
  assert.ok('reasoning_content' in a);
  assert.equal(str(a, 'reasoning_content'), '');
  assert.equal(str(a, 'content'), 'ok');
});

test('buildMessages: 无效消息过滤;未执行 tool 不产出 tool_calls/tool 结果', () => {
  const emptyUser = makeUserMessage('   ');
  const pending = makeUIMessage('assistant', [
    { type: 'text', text: '调用中', metadata: null },
    {
      type: 'tool', toolCallId: 'c9', toolName: 'pending_tool', input: '{}',
      output: [], approvalState: { type: 'pending' }, metadata: null,
    },
  ]);
  const out = buildMessages([emptyUser, makeUserMessage('real'), pending], {}, encoder);
  assert.equal(out.length, 2, '空白 user 被 isValidToUpload 过滤');
  const a = at(out, 1);
  assert.equal(a['tool_calls'], undefined);
  assert.ok(!out.some((m): boolean => (m as JsonObject)['role'] === 'tool'));
});

// ===== buildChatCompletionRequest =====

test('request: 基础字段 + stream_options(mistral 除外)', () => {
  const req = buildChatCompletionRequest({
    messages: [makeUserMessage('hi')],
    params: makeTextGenerationParams({}),
    setting: makeProviderSettingOpenAI({}),
    stream: true,
    encodeImage: encoder,
  });
  assert.equal(str(req, 'model'), 'test-model');
  assert.ok(Array.isArray(req['messages']));
  assert.equal(req['stream'], true);
  assert.equal(str(req['stream_options'] as JsonObject, 'include_usage') as unknown, true);

  const mistral = buildChatCompletionRequest({
    messages: [makeUserMessage('hi')],
    params: makeTextGenerationParams({}),
    setting: makeProviderSettingOpenAI({ baseUrl: 'https://api.mistral.ai/v1' }),
    stream: true,
    encodeImage: encoder,
  });
  assert.equal(mistral['stream_options'], undefined, 'mistral 不支持 stream_options');
});

test('request: temperature/top_p 常规带上,o1/gpt-5/codex 被剥离', () => {
  const mk = (modelId: string): JsonObject => buildChatCompletionRequest({
    messages: [makeUserMessage('hi')],
    params: makeTextGenerationParams({ model: makeChatModel({ modelId }), temperature: 0.7, topP: 0.9 }),
    setting: makeProviderSettingOpenAI({}),
    stream: false,
    encodeImage: encoder,
  });
  const normal = mk('gpt-4o');
  assert.equal(normal['temperature'], 0.7);
  assert.equal(normal['top_p'], 0.9);
  const o1 = mk('o1');
  assert.equal(o1['temperature'], undefined);
  assert.equal(o1['top_p'], undefined);
  assert.equal(mk('gpt-5')['temperature'], undefined);
});

test('request: max_tokens vs MiMo max_completion_tokens', () => {
  const mk = (mimo: boolean): JsonObject => buildChatCompletionRequest({
    messages: [makeUserMessage('hi')],
    params: makeTextGenerationParams({ maxTokens: 100 }),
    setting: makeProviderSettingOpenAI(mimo ? { brand: 'mimo' } : {}),
    stream: false,
    encodeImage: encoder,
  });
  assert.equal(mk(false)['max_tokens'], 100);
  const mimoReq = mk(true);
  assert.equal(mimoReq['max_completion_tokens'], 100);
  assert.equal(mimoReq['max_tokens'], undefined);
});

test('request: reasoning 矩阵 — 默认/deepseek/dashscope/openrouter/mimo/siliconflow', () => {
  const reasoningParams = (level: import('../main/ets/chat/provider_model.ts').ReasoningLevel) =>
    makeTextGenerationParams({
      model: makeChatModel({ abilities: ['reasoning'] }),
      reasoningLevel: level,
    });
  const mk = (setting: ReturnType<typeof makeProviderSettingOpenAI>, level: 'off' | 'auto' | 'high') =>
    buildChatCompletionRequest({
      messages: [makeUserMessage('hi')],
      params: reasoningParams(level),
      setting,
      stream: false,
      encodeImage: encoder,
    });

  // 默认(OpenAI 官方):非 AUTO → reasoning_effort;OFF(effort none)→ low
  assert.equal(str(mk(makeProviderSettingOpenAI({}), 'high'), 'reasoning_effort'), 'high');
  assert.equal(str(mk(makeProviderSettingOpenAI({}), 'off'), 'reasoning_effort'), 'low');
  assert.equal(mk(makeProviderSettingOpenAI({}), 'auto')['reasoning_effort'], undefined);

  // deepseek: thinking.type + 非 AUTO 时 reasoning_effort
  const ds = mk(makeProviderSettingOpenAI({ baseUrl: 'https://api.deepseek.com/v1' }), 'high');
  assert.equal(str(ds['thinking'] as JsonObject, 'type'), 'enabled');
  assert.equal(str(ds, 'reasoning_effort'), 'high');
  const dsOff = mk(makeProviderSettingOpenAI({ baseUrl: 'https://api.deepseek.com/v1' }), 'off');
  assert.equal(str(dsOff['thinking'] as JsonObject, 'type'), 'disabled');
  assert.equal(dsOff['reasoning_effort'], undefined);

  // dashscope: enable_thinking + 非 AUTO thinking_budget
  const ali = mk(makeProviderSettingOpenAI({ baseUrl: 'https://dashscope.aliyuncs.com/v1' }), 'high');
  assert.equal(ali['enable_thinking'], true);
  assert.equal(ali['thinking_budget'], 8000);
  const aliAuto = mk(makeProviderSettingOpenAI({ baseUrl: 'https://dashscope.aliyuncs.com/v1' }), 'auto');
  assert.equal(aliAuto['enable_thinking'], true);
  assert.equal(aliAuto['thinking_budget'], undefined);

  // openrouter: reasoning effort/enabled
  const orHigh = mk(makeProviderSettingOpenAI({ baseUrl: 'https://openrouter.ai/api/v1' }), 'high');
  assert.equal(str(orHigh['reasoning'] as JsonObject, 'effort'), 'high');
  const orAuto = mk(makeProviderSettingOpenAI({ baseUrl: 'https://openrouter.ai/api/v1' }), 'auto');
  assert.equal((orAuto['reasoning'] as JsonObject)['enabled'], true);
  const orOff = mk(makeProviderSettingOpenAI({ baseUrl: 'https://openrouter.ai/api/v1' }), 'off');
  assert.equal(str(orOff['reasoning'] as JsonObject, 'effort'), 'none');

  // mimo: thinking.type enabled/disabled
  const mimo = mk(makeProviderSettingOpenAI({ brand: 'mimo' }), 'auto');
  assert.equal(str(mimo['thinking'] as JsonObject, 'type'), 'enabled');

  // siliconflow: 名单内 enable_thinking,名单外无
  const sf = mk(makeProviderSettingOpenAI({ baseUrl: 'https://api.siliconflow.cn/v1' }), 'auto');
  assert.equal(sf['enable_thinking'], undefined, 'test-model 不在名单');
  const sfListed = buildChatCompletionRequest({
    messages: [makeUserMessage('hi')],
    params: makeTextGenerationParams({
      model: makeChatModel({ modelId: 'deepseek-ai/DeepSeek-V3.2', abilities: ['reasoning'] }),
      reasoningLevel: 'off',
    }),
    setting: makeProviderSettingOpenAI({ baseUrl: 'https://api.siliconflow.cn/v1' }),
    stream: false,
    encodeImage: encoder,
  });
  assert.equal(sfListed['enable_thinking'], false);
});

test('request: tools 形状;无 TOOL 能力或空 tools 不输出', () => {
  const tool: ChatToolDefinition = {
    name: 'search', description: 'web search',
    parameters: { type: 'object', properties: { q: { type: 'string' } } },
  };
  const req = buildChatCompletionRequest({
    messages: [makeUserMessage('hi')],
    params: makeTextGenerationParams({
      model: makeChatModel({ abilities: ['tool'] }),
      tools: [tool],
    }),
    setting: makeProviderSettingOpenAI({}),
    stream: false,
    encodeImage: encoder,
  });
  const tools = req['tools'] as JsonValue[];
  assert.equal(tools.length, 1);
  const fn = at(tools, 0)['function'] as JsonObject;
  assert.equal(str(fn, 'name'), 'search');
  assert.deepEqual(fn['parameters'], tool.parameters);

  const noAbility = buildChatCompletionRequest({
    messages: [makeUserMessage('hi')],
    params: makeTextGenerationParams({ model: makeChatModel({ abilities: [] }), tools: [tool] }),
    setting: makeProviderSettingOpenAI({}),
    stream: false,
    encodeImage: encoder,
  });
  assert.equal(noAbility['tools'], undefined);
});

test('request: openrouter 图像输出 modalities;customBody 深合并', () => {
  const req = buildChatCompletionRequest({
    messages: [makeUserMessage('hi')],
    params: makeTextGenerationParams({
      model: makeChatModel({ outputModalities: ['text', 'image'] }),
      customBody: [
        { key: 'thinking', value: { extra: 1 } },
        { key: 'brand_new', value: 'x' },
        { key: '  ', value: 'skipped' },
      ],
    }),
    setting: makeProviderSettingOpenAI({ baseUrl: 'https://openrouter.ai/api/v1' }),
    stream: false,
    encodeImage: encoder,
  });
  assert.deepEqual(req['modalities'], ['image', 'text']);
  // customBody 与已有 thinking 键深合并(request 里 openrouter 无 thinking;用 mergeCustomBody 单测深合并语义)
  assert.equal(req['brand_new'], 'x');
  assert.ok(!('  ' in req));
});

test('mergeCustomBody: 同键 JsonObject 递归合并,否则替换', () => {
  const base: JsonObject = { a: { x: 1, y: 2 }, b: 1 };
  const out = mergeCustomBody(base, [
    { key: 'a', value: { y: 3, z: 4 } },
    { key: 'b', value: { nested: true } },
  ]);
  assert.deepEqual(out['a'], { x: 1, y: 3, z: 4 });
  assert.deepEqual(out['b'], { nested: true });
});

// R06/C04:customBody 不得覆写 stream(对齐 ChatCompletionsAPI.kt withForcedStream)
test('request R06: customBody stream 覆写被强制回写为调用模式', () => {
  const streaming = buildChatCompletionRequest({
    messages: [makeUserMessage('hi')],
    params: makeTextGenerationParams({
      model: makeChatModel({ modelId: 'gpt-4o' }),
      customBody: [{ key: 'stream', value: false }],
    }),
    setting: makeProviderSettingOpenAI({}),
    stream: true,
  });
  assert.equal(streaming['stream'], true, '调用 stream=true 时 customBody stream=false 无效');

  const nonStream = buildChatCompletionRequest({
    messages: [makeUserMessage('hi')],
    params: makeTextGenerationParams({
      model: makeChatModel({ modelId: 'gpt-4o' }),
      customBody: [{ key: 'stream', value: true }],
    }),
    setting: makeProviderSettingOpenAI({}),
    stream: false,
  });
  assert.equal(nonStream['stream'], false, '调用 stream=false 时 customBody stream=true 无效');
});
