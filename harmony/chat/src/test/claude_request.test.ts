// Claude 请求构建规格测试(D-044)
//
// Android 基准: ClaudeProvider.kt buildMessageRequest(:289-378) /
//   buildMessages(:380-393) / insertMessagesCacheControl(:395-434)
// 逐条锁定:默认 max_tokens/temperature 门控/system 块/thinking/tools/
//   cache_control 三处规则/customBody 合并/消息映射(含 tool_use+tool_result)

import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  buildClaudeMessageRequest, buildClaudeMessages, insertMessagesCacheControl,
  toClaudeContentBlock,
  SYSTEM_PROMPT_CACHE_CONTROL_METADATA, SYSTEM_PROMPT_CACHE_DISABLED,
  SYSTEM_PROMPT_CACHE_EPHEMERAL,
} from '../main/ets/chat/claude_request.ts';
import type { ClaudeEncodedImage } from '../main/ets/chat/claude_request.ts';
import { makeTextGenerationParams, makeChatModel } from '../main/ets/chat/provider_model.ts';
import type { TextGenerationParams } from '../main/ets/chat/provider_model.ts';
import { makeProviderSettingClaude } from '../main/ets/chat/provider_settings.ts';
import { makeUIMessage, makeUserMessage, makeAssistantMessage } from '../main/ets/chat/message.ts';
import type { UIMessage, UIMessagePartTool } from '../main/ets/chat/message.ts';
import type { JsonObject, JsonValue } from '../main/ets/chat/json.ts';

const encoder = (url: string): ClaudeEncodedImage => {
  assert.equal(url, 'file:///tmp/p.png', 'encoder 收到原始 url');
  return { mimeType: 'image/png', base64: 'QUJD' };
};

const baseSetting = () => makeProviderSettingClaude({});

const baseParams = (over: Partial<TextGenerationParams> = {}): TextGenerationParams =>
  makeTextGenerationParams({ model: makeChatModel({ modelId: 'claude-opus-4-7' }), ...over });

test('buildClaudeMessageRequest 骨架:model/messages/max_tokens 默认 64000/stream', () => {
  const req = buildClaudeMessageRequest({
    messages: [makeUserMessage('你好')],
    params: baseParams(),
    setting: baseSetting(),
    stream: false,
  });
  assert.equal(req['model'], 'claude-opus-4-7');
  assert.equal(req['max_tokens'], 64000, 'Android 默认 64_000');
  assert.equal(req['stream'], false);
  const msgs = req['messages'] as JsonObject[];
  assert.deepEqual(msgs, [{ role: 'user', content: [{ type: 'text', text: '你好' }] }]);
});

test('maxTokens 覆盖默认值;temperature 仅 reasoningLevel=off 时携带;top_p 无条件携带', () => {
  // off:temperature 携带
  const off = buildClaudeMessageRequest({
    messages: [makeUserMessage('x')],
    params: baseParams({ temperature: 0.5, topP: 0.9, maxTokens: 1024, reasoningLevel: 'off' }),
    setting: baseSetting(),
    stream: false,
  });
  assert.equal(off['max_tokens'], 1024);
  assert.equal(off['temperature'], 0.5);
  assert.equal(off['top_p'], 0.9);

  // reasoning 开启(low):temperature 不携带(Android :302-305),top_p 仍携带
  const low = buildClaudeMessageRequest({
    messages: [makeUserMessage('x')],
    params: baseParams({ temperature: 0.5, topP: 0.9, reasoningLevel: 'low' }),
    setting: baseSetting(),
    stream: false,
  });
  assert.equal('temperature' in low, false);
  assert.equal(low['top_p'], 0.9);
});

test('system 消息抽出为 system 块数组,不进 messages;多 text part 多块', () => {
  const sys: UIMessage = makeUIMessage('system', [
    { type: 'text', text: '块一', metadata: null },
    { type: 'text', text: '块二', metadata: null },
  ]);
  const req = buildClaudeMessageRequest({
    messages: [sys, makeUserMessage('你好')],
    params: baseParams(),
    setting: baseSetting(),
    stream: false,
  });
  assert.deepEqual(req['system'], [
    { type: 'text', text: '块一' },
    { type: 'text', text: '块二' },
  ]);
  const msgs = req['messages'] as JsonObject[];
  assert.equal(msgs.length, 1, 'system 不进 messages');
  assert.equal(msgs[0]['role'], 'user');
});

test('system cache_control:EPHEMERAL 标记的最后一个 part 打 cache;DISABLED 全禁;promptCaching 关则不打', () => {
  const marked = (text: string, marker: string | null): UIMessage => makeUIMessage('system', [
    { type: 'text', text, metadata: marker !== null
      ? { [SYSTEM_PROMPT_CACHE_CONTROL_METADATA]: marker } : null },
  ]);
  const user = makeUserMessage('x');

  // EPHEMERAL 标记 + caching 开 → 该块打 cache_control
  const on = buildClaudeMessageRequest({
    messages: [marked('s', SYSTEM_PROMPT_CACHE_EPHEMERAL), user],
    params: baseParams(),
    setting: makeProviderSettingClaude({ promptCaching: true }),
    stream: false,
  });
  assert.deepEqual((on['system'] as JsonObject[])[0],
    { type: 'text', text: 's', cache_control: { type: 'ephemeral' } });

  // DISABLED 标记 → 不打
  const disabled = buildClaudeMessageRequest({
    messages: [marked('s', SYSTEM_PROMPT_CACHE_DISABLED), user],
    params: baseParams(),
    setting: makeProviderSettingClaude({ promptCaching: true }),
    stream: false,
  });
  assert.deepEqual((disabled['system'] as JsonObject[])[0], { type: 'text', text: 's' });

  // caching 关 → 不打
  const off = buildClaudeMessageRequest({
    messages: [marked('s', SYSTEM_PROMPT_CACHE_EPHEMERAL), user],
    params: baseParams(),
    setting: makeProviderSettingClaude({ promptCaching: false }),
    stream: false,
  });
  assert.deepEqual((off['system'] as JsonObject[])[0], { type: 'text', text: 's' });

  // 无标记 + caching 开 → 不打(Android:cacheIndex 仅取显式标记)
  const unmarked = buildClaudeMessageRequest({
    messages: [marked('s', null), user],
    params: baseParams(),
    setting: makeProviderSettingClaude({ promptCaching: true }),
    stream: false,
  });
  assert.deepEqual((unmarked['system'] as JsonObject[])[0], { type: 'text', text: 's' });
});

test('thinking:OFF→disabled;AUTO→adaptive+summarized;显式档→output_config.effort;无 reasoning 能力→无 thinking 键', () => {
  const reasoningModel = makeChatModel({ modelId: 'claude-opus-4-7', abilities: ['reasoning'] });

  const off = buildClaudeMessageRequest({
    messages: [makeUserMessage('x')],
    params: baseParams({ model: reasoningModel, reasoningLevel: 'off' }),
    setting: baseSetting(), stream: false,
  });
  assert.deepEqual(off['thinking'], { type: 'disabled' });

  const auto = buildClaudeMessageRequest({
    messages: [makeUserMessage('x')],
    params: baseParams({ model: reasoningModel, reasoningLevel: 'auto' }),
    setting: baseSetting(), stream: false,
  });
  assert.deepEqual(auto['thinking'], { type: 'adaptive', display: 'summarized' });
  assert.equal('output_config' in auto, false);

  const high = buildClaudeMessageRequest({
    messages: [makeUserMessage('x')],
    params: baseParams({ model: reasoningModel, reasoningLevel: 'high' }),
    setting: baseSetting(), stream: false,
  });
  assert.deepEqual(high['thinking'], { type: 'adaptive', display: 'summarized' });
  assert.deepEqual(high['output_config'], { effort: 'high' });

  const plain = buildClaudeMessageRequest({
    messages: [makeUserMessage('x')],
    params: baseParams({ reasoningLevel: 'high' }), // model 无 abilities
    setting: baseSetting(), stream: false,
  });
  assert.equal('thinking' in plain, false);
});

test('tools:input_schema 透传;promptCaching → 末位 tool 打 cache_control;customBody 合并', () => {
  const toolModel = makeChatModel({ modelId: 'claude-opus-4-7', abilities: ['tool'] });
  const req = buildClaudeMessageRequest({
    messages: [makeUserMessage('x')],
    params: baseParams({
      model: toolModel,
      tools: [
        { name: 't1', description: 'd1', parameters: { type: 'object' } },
        { name: 't2', description: 'd2', parameters: { type: 'object' } },
      ],
      customBody: [{ key: 'metadata', value: { user_id: 'u1' } }],
    }),
    setting: makeProviderSettingClaude({ promptCaching: true }),
    stream: false,
  });
  const tools = req['tools'] as JsonObject[];
  assert.equal(tools.length, 2);
  assert.deepEqual(tools[0], { name: 't1', description: 'd1', input_schema: { type: 'object' } });
  assert.deepEqual(tools[1]['cache_control'], { type: 'ephemeral' }, '末位 tool 打 cache');
  assert.equal('cache_control' in tools[0], false);
  assert.deepEqual(req['metadata'], { user_id: 'u1' }, 'customBody 合并');
});

test('消息映射:user 图片 → base64 source 块(ClaudeImageEncoder,无前缀)', () => {
  const msg: UIMessage = makeUIMessage('user', [
    { type: 'text', text: '看图', metadata: null },
    { type: 'image', url: 'file:///tmp/p.png', metadata: null },
  ]);
  const msgs = buildClaudeMessages([msg], false, encoder);
  assert.deepEqual(msgs, [{
    role: 'user',
    content: [
      { type: 'text', text: '看图' },
      { type: 'image', source: { type: 'base64', media_type: 'image/png', data: 'QUJD' } },
    ],
  }]);
});

test('消息映射:assistant reasoning → thinking 块 + metadata 并入(signature)', () => {
  const msg: UIMessage = makeUIMessage('assistant', [
    { type: 'reasoning', reasoning: '思考', createdAt: '2026-07-28T00:00:00Z',
      finishedAt: null, metadata: { signature: 'sig-1' } },
    { type: 'text', text: '回答', metadata: null },
  ]);
  const msgs = buildClaudeMessages([msg], false);
  assert.deepEqual(msgs, [{
    role: 'assistant',
    content: [
      { type: 'thinking', thinking: '思考', signature: 'sig-1' },
      { type: 'text', text: '回答' },
    ],
  }]);
});

test('消息映射:assistant 已执行 tool → assistant tool_use + user tool_result 紧跟', () => {
  const tool: UIMessagePartTool = {
    type: 'tool',
    toolCallId: 'call-1',
    toolName: 'search',
    input: '{"q":"x"}',
    output: [{ type: 'text', text: '结果', metadata: null }],
    approvalState: { type: 'auto' },
    metadata: null,
  };
  const msg: UIMessage = makeUIMessage('assistant', [
    { type: 'text', text: '调用工具', metadata: null },
    tool,
    { type: 'text', text: '后续说明', metadata: null },
  ]);
  const msgs = buildClaudeMessages([msg], false);
  assert.equal(msgs.length, 3, 'assistant(text+tool_use) / user(tool_result) / assistant(后续)');
  assert.deepEqual(msgs[0], {
    role: 'assistant',
    content: [
      { type: 'text', text: '调用工具' },
      { type: 'tool_use', id: 'call-1', name: 'search', input: { q: 'x' } },
    ],
  });
  assert.deepEqual(msgs[1], {
    role: 'user',
    content: [{ type: 'tool_result', tool_use_id: 'call-1', content: [{ type: 'text', text: '结果' }] }],
  });
  assert.deepEqual(msgs[2], { role: 'assistant', content: [{ type: 'text', text: '后续说明' }] });
});

test('insertMessagesCacheControl:倒数第二条真实 user 消息末位 content 打 cache;tool_result user 不算', () => {
  const u = (text: string): JsonObject => ({ role: 'user', content: [{ type: 'text', text }] });
  const toolResultUser: JsonObject = {
    role: 'user',
    content: [{ type: 'tool_result', tool_use_id: 'c', content: [] }],
  };
  const a: JsonObject = { role: 'assistant', content: [{ type: 'text', text: 'a' }] };

  // user / assistant / toolResultUser / assistant / user → 目标是第一条 user
  // (真实 user = [0, 4],取倒数第二 = 0)
  const out = insertMessagesCacheControl([u('一'), a, toolResultUser, a, u('二')]);
  assert.deepEqual((out[0]['content'] as JsonObject[])[0],
    { type: 'text', text: '一', cache_control: { type: 'ephemeral' } });
  assert.deepEqual((out[4]['content'] as JsonObject[])[0], { type: 'text', text: '二' }, '末条 user 不打');
  assert.deepEqual(out[2], toolResultUser, 'tool_result user 不动');

  // 仅一条真实 user → 不变
  const single = insertMessagesCacheControl([u('一'), a]);
  assert.deepEqual((single[0]['content'] as JsonObject[])[0], { type: 'text', text: '一' });
});

test('buildClaudeMessages 过滤:无效消息与 system 角色剔除(isValidToUpload)', () => {
  const empty = makeAssistantMessage('');
  const sys = makeUIMessage('system', [{ type: 'text', text: 's', metadata: null }]);
  const msgs = buildClaudeMessages([sys, empty, makeUserMessage('保留')], false);
  assert.equal(msgs.length, 1);
  assert.equal(msgs[0]['role'], 'user');
});

test('toClaudeContentBlock:未支持 part 类型返回 null(document/video 等按 Android else 丢弃)', () => {
  const doc = { type: 'document', url: 'file:///x.pdf', fileName: 'x.pdf', mime: 'application/pdf', metadata: null };
  assert.equal(toClaudeContentBlock(doc as never, encoder), null);
});
