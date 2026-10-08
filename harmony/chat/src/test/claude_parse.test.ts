// Claude 解析规格测试(D-044)
//
// Android 基准: ClaudeProvider.kt parseMessage(:526-595) /
//   parseTokenUsage(:602-620) / generateText 响应提取(:129-151) /
//   streamText onEvent(:192-254)

import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  parseClaudeMessage, parseClaudeTokenUsage, parseClaudeResponseBody,
  parseClaudeStreamEvent,
} from '../main/ets/chat/claude_parse.ts';
import type { UIMessagePartReasoning, UIMessagePartTool } from '../main/ets/chat/message.ts';
import { toText } from '../main/ets/chat/message.ts';
import { STREAM_TOOL_INDEX_METADATA_KEY } from '../main/ets/chat/tool_merge.ts';
import type { JsonObject } from '../main/ets/chat/json.ts';

test('parseClaudeMessage:text 块;空 text 跳过', () => {
  const msg = parseClaudeMessage([
    { type: 'text', text: '你好' },
    { type: 'text', text: '' },
    { type: 'text', text: '世界' },
  ]);
  assert.equal(msg.role, 'assistant');
  assert.equal(msg.parts.length, 2);
  assert.equal(toText(msg), '你好\n世界', '两独立 text part(Android 同),toText 换行连接');
});

test('parseClaudeMessage:thinking signature 和 redacted 原块均保留且带原块索引', () => {
  const msg = parseClaudeMessage([
    { type: 'thinking', thinking: '推演', signature: 'sig-9' },
    { type: 'redacted_thinking', data: 'ENCRYPTED' },
  ]);
  assert.equal(msg.parts.length, 2, 'redacted_thinking 入历史以便工具续轮回放');
  const r = msg.parts[0] as UIMessagePartReasoning;
  assert.equal(r.type, 'reasoning');
  assert.equal(r.reasoning, '推演');
  assert.deepEqual(r.metadata, { signature: 'sig-9', claude_thinking_block_index: 0 });
  assert.deepEqual(msg.parts[1].metadata, {
    claude_redacted_thinking: { type: 'redacted_thinking', data: 'ENCRYPTED' },
    claude_thinking_block_index: 1,
  });
  assert.equal(r.finishedAt, null);
});

test('parseClaudeMessage:tool_use → tool part(input 序列化;空 input → 空串)', () => {
  const msg = parseClaudeMessage([
    { type: 'tool_use', id: 'c1', name: 'search', input: { q: 'x' } },
    { type: 'tool_use', id: 'c2', name: 'ping', input: {} },
  ]);
  const t1 = msg.parts[0] as UIMessagePartTool;
  assert.equal(t1.toolCallId, 'c1');
  assert.equal(t1.toolName, 'search');
  assert.equal(t1.input, '{"q":"x"}');
  const t2 = msg.parts[1] as UIMessagePartTool;
  assert.equal(t2.input, '', '空 input → 空串(Android :571)');
});

test('parseClaudeMessage:input_json_delta → 空 id/name tool part,input=partial_json', () => {
  const msg = parseClaudeMessage([{ type: 'input_json_delta', partial_json: '{"q":' }]);
  const t = msg.parts[0] as UIMessagePartTool;
  assert.equal(t.toolCallId, '');
  assert.equal(t.toolName, '');
  assert.equal(t.input, '{"q":');
});

test('parseClaudeTokenUsage:input+cache_read+cache_creation 合计为 prompt;message.usage 回退;null 处理', () => {
  const u = parseClaudeTokenUsage({
    usage: { input_tokens: 100, cache_read_input_tokens: 20, cache_creation_input_tokens: 5, output_tokens: 7 },
  });
  assert.deepEqual(u, { promptTokens: 125, completionTokens: 7, cachedTokens: 20, totalTokens: 132 });

  const nested = parseClaudeTokenUsage({
    message: { usage: { input_tokens: 10, output_tokens: 3 } },
  });
  assert.deepEqual(nested, { promptTokens: 10, completionTokens: 3, cachedTokens: 0, totalTokens: 13 });

  assert.equal(parseClaudeTokenUsage({}), null, '无 usage → null');
  assert.equal(parseClaudeTokenUsage(null), null);
});

test('parseClaudeResponseBody:id/model/content/stop_reason/usage → MessageChunk(message 选择)', () => {
  const chunk = parseClaudeResponseBody({
    id: 'msg_1',
    model: 'claude-opus-4-7',
    content: [{ type: 'text', text: '完整回答' }],
    stop_reason: 'end_turn',
    usage: { input_tokens: 10, output_tokens: 4 },
  });
  assert.equal(chunk.id, 'msg_1');
  assert.equal(chunk.model, 'claude-opus-4-7');
  assert.equal(chunk.choices.length, 1);
  assert.equal(chunk.choices[0].delta, null);
  assert.equal(toText(chunk.choices[0].message!), '完整回答');
  assert.equal(chunk.choices[0].finishReason, 'end_turn');
  assert.equal(chunk.usage!.promptTokens, 10);

  // stop_reason 缺失 → 'unknown';content 缺失 → 空 parts
  const bare = parseClaudeResponseBody({});
  assert.equal(bare.choices[0].finishReason, 'unknown');
  assert.equal(bare.choices[0].message!.parts.length, 0);
});

test('parseClaudeStreamEvent:content_block_start text → delta chunk;[DONE] 跳过', () => {
  const start = parseClaudeStreamEvent('content_block_start', 'evt-1',
    JSON.stringify({ index: 0, content_block: { type: 'text', text: '' } }));
  assert.equal(start.chunk, null, '空 text 且无 usage → 跳过(Android :237)');

  const delta = parseClaudeStreamEvent('content_block_delta', 'evt-2',
    JSON.stringify({ index: 0, delta: { type: 'text_delta', text: '你' } }));
  assert.equal(delta.done, false);
  assert.equal(delta.chunk!.id, 'evt-2');
  assert.equal(toText(delta.chunk!.choices[0].delta!), '你');
  assert.equal(delta.chunk!.choices[0].finishReason, null);

  const done = parseClaudeStreamEvent(undefined, undefined, '[DONE]');
  assert.equal(done.chunk, null);
  assert.equal(done.done, false, '[DONE] 仅跳过,非 message_stop');
});

test('parseClaudeStreamEvent:thinking_delta/signature_delta → reasoning part;message_stop → done', () => {
  const thinking = parseClaudeStreamEvent('content_block_delta', undefined,
    JSON.stringify({ index: 0, delta: { type: 'thinking_delta', thinking: '推' } }));
  const r = thinking.chunk!.choices[0].delta!.parts[0] as UIMessagePartReasoning;
  assert.equal(r.type, 'reasoning');
  assert.equal(r.reasoning, '推');

  const sig = parseClaudeStreamEvent('content_block_delta', undefined,
    JSON.stringify({ index: 0, delta: { type: 'signature_delta', signature: 's-1' } }));
  const r2 = sig.chunk!.choices[0].delta!.parts[0] as UIMessagePartReasoning;
  assert.equal(r2.reasoning, '');
  assert.deepEqual(r2.metadata, { signature: 's-1', claude_thinking_block_index: 0 });

  const stop = parseClaudeStreamEvent('message_stop', undefined, '{}');
  assert.equal(stop.done, true);
  assert.equal(stop.chunk, null);
});

test('parseClaudeStreamEvent:tool_use/input_json_delta 注入 stream index(metadata)', () => {
  const toolStart = parseClaudeStreamEvent('content_block_start', undefined,
    JSON.stringify({ index: 2, content_block: { type: 'tool_use', id: 'c1', name: 'search', input: {} } }));
  const t = toolStart.chunk!.choices[0].delta!.parts[0] as UIMessagePartTool;
  assert.equal(t.toolCallId, 'c1');
  assert.equal((t.metadata as JsonObject)[STREAM_TOOL_INDEX_METADATA_KEY], 2,
    'index 注入 tool metadata(并行 tool 关联键)');

  const jsonDelta = parseClaudeStreamEvent('content_block_delta', undefined,
    JSON.stringify({ index: 2, delta: { type: 'input_json_delta', partial_json: '{"q":' } }));
  const t2 = jsonDelta.chunk!.choices[0].delta!.parts[0] as UIMessagePartTool;
  assert.equal((t2.metadata as JsonObject)[STREAM_TOOL_INDEX_METADATA_KEY], 2);

  // 无 index → 不注入
  const noIndex = parseClaudeStreamEvent('content_block_start', undefined,
    JSON.stringify({ content_block: { type: 'tool_use', id: 'c1', name: 's', input: {} } }));
  const t3 = noIndex.chunk!.choices[0].delta!.parts[0] as UIMessagePartTool;
  assert.equal(t3.metadata, null);
});

test('parseClaudeStreamEvent:message_delta usage-only → usage chunk(parts 空也发)', () => {
  const res = parseClaudeStreamEvent('message_delta', undefined,
    JSON.stringify({ delta: { stop_reason: 'end_turn' }, usage: { output_tokens: 9 } }));
  assert.notEqual(res.chunk, null, 'usage 非空 → 发 chunk(Android :237)');
  assert.equal(res.chunk!.choices[0].delta!.parts.length, 0);
  assert.equal(res.chunk!.usage!.completionTokens, 9);
});

test('parseClaudeStreamEvent:error 事件 → 抛 parseErrorDetail(message 提取)', () => {
  assert.throws(
    () => parseClaudeStreamEvent('error', undefined,
      JSON.stringify({ type: 'error', error: { type: 'overloaded_error', message: 'Overloaded' } })),
    (e: Error) => e.message === 'Overloaded',
  );
});
