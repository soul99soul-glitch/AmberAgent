// OpenAI Chat Completions provider 纯逻辑 — 规格测试 + fixture 回放
//
// Android 基准:
//   ai/provider/providers/openai/ChatCompletionsAPI.kt
//     - normalizeOpenAIStreamDataLines (:72-98)
//     - streamText onEvent 组装 (:199-256)
//     - parseMessage (:739-811) / parseAnnotations (:813-829) / parseTokenUsage (:832-849)
//   ai/util/ErrorParser.kt parseErrorDetail
//   ai/src/test/.../ChatCompletionsAPIMessageTest.kt(单测口径)
//
// fixture 回放: harmony/tests/fixtures/streaming/*(G-B 审计产出,锚定 parity edge case)
// 回放链路: fixture raw(SSE 线格式) → SseAssembler → normalize → parse → MessageChunk
//          → MessageStreamAccumulator(累积断言)
//
// finishReason 口径:Android 把 wire 的 null/缺失一律归一为 "unknown"
//   (ChatCompletionsAPI.kt:224-226)。fixture expected.finishReason:null 表示 wire 值为 null,
//   回放断言按映射 null→"unknown" 比较,不改变 Android 语义。

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

import { SseAssembler } from '@amber/deepread-domain';
import type { SseEvent } from '@amber/deepread-domain';

import { normalizeOpenAIStreamDataLines } from '../main/ets/chat/openai_normalize.ts';
import {
  parseChatCompletionMessage,
  parseOpenAiTokenUsage,
  parseOpenAiErrorDetail,
  parseOpenAiStreamEventData,
  OpenAiStreamError,
} from '../main/ets/chat/openai_parse.ts';
import {
  makeUserMessage, getTools,
} from '../main/ets/chat/message.ts';
import type { UIMessagePart, UIMessagePartReasoning, UIMessagePartTool } from '../main/ets/chat/message.ts';
import { MessageStreamAccumulator } from '../main/ets/chat/stream_accumulator.ts';
import {
  streamToolIndex, hasExplicitReasoningContentField,
} from '../main/ets/chat/tool_merge.ts';
import type { JsonObject, JsonValue } from '../main/ets/chat/json.ts';

const here = dirname(fileURLToPath(import.meta.url));
const fixtureDir = join(here, '..', '..', '..', 'tests', 'fixtures', 'streaming');

interface FixtureLine {
  event?: string;
  raw?: string;
  expected?: Record<string, JsonValue>;
  expectedPayload?: string;
  note?: string;
}

const readJsonl = (name: string): FixtureLine[] =>
  readFileSync(join(fixtureDir, name), 'utf-8')
    .split('\n')
    .filter((l: string): boolean => l.trim().length > 0)
    .map((l: string): FixtureLine => JSON.parse(l) as FixtureLine);

const readJson = (name: string): JsonObject =>
  JSON.parse(readFileSync(join(fixtureDir, name), 'utf-8')) as JsonObject;

// raw SSE 线格式 → SseEvent 列表(经 SseAssembler 全链路)
const assemble = (raw: string): SseEvent[] => {
  const assembler = new SseAssembler();
  const bytes: ArrayBuffer = new TextEncoder().encode(raw + '\n\n').buffer as ArrayBuffer;
  const events: SseEvent[] = assembler.feed(bytes);
  events.push(...assembler.flush());
  return events;
};

// delta 各类型 part 的文本提取(回放断言用)
const textOf = (parts: UIMessagePart[]): string =>
  parts.filter((p: UIMessagePart): boolean => p.type === 'text')
    .map((p: UIMessagePart): string => (p as { text: string }).text).join('');
const reasoningOf = (parts: UIMessagePart[]): string =>
  parts.filter((p: UIMessagePart): p is UIMessagePartReasoning => p.type === 'reasoning')
    .map((p: UIMessagePartReasoning): string => p.reasoning).join('');

// ===== normalizeOpenAIStreamDataLines(ChatCompletionsAPI.kt:72-98) =====

test('normalize: 剥离嵌套 data: 前缀', () => {
  const payloads = normalizeOpenAIStreamDataLines('data:{"error":{"message":"unexpected end of data"}}');
  assert.deepEqual(payloads, ['{"error":{"message":"unexpected end of data"}}']);
});

test('normalize: 常规 payload 保留,[DONE] 行剔除', () => {
  const payloads = normalizeOpenAIStreamDataLines('{"choices":[]}\ndata:[DONE]');
  assert.deepEqual(payloads, ['{"choices":[]}']);
});

test('normalize: 多行单 JSON payload 不被拆碎(SSE 合法跨行)', () => {
  const raw = '{"id":"x","choices":[{"delta":\n{"content":"hello"},"finish_reason":null}]}';
  const payloads = normalizeOpenAIStreamDataLines(raw);
  assert.equal(payloads.length, 1);
  const parsed = JSON.parse(payloads[0]) as JsonObject;
  assert.equal(parsed['id'], 'x');
});

test('normalize: 独立 JSON 行仍按行拆分', () => {
  const payloads = normalizeOpenAIStreamDataLines('{"a":1}\n{"b":2}');
  assert.deepEqual(payloads, ['{"a":1}', '{"b":2}']);
});

test('normalize: 逐行剥离嵌套 data: 前缀后再尝试整体 join', () => {
  const raw = 'data: {"id":"x","choices":[{"delta":\ndata: {"content":"hello"},"finish_reason":null}]}';
  const payloads = normalizeOpenAIStreamDataLines(raw);
  assert.equal(payloads.length, 1);
  const parsed = JSON.parse(payloads[0]) as JsonObject;
  const choices = parsed['choices'] as JsonValue[];
  const delta = (choices[0] as JsonObject)['delta'] as JsonObject;
  assert.equal(delta['content'], 'hello');
});

// ===== parseMessage(ChatCompletionsAPI.kt:739-811) =====

test('parseMessage: 基本 assistant content', () => {
  const msg = parseChatCompletionMessage({ role: 'assistant', content: '你好' });
  assert.equal(msg.role, 'assistant');
  assert.equal(textOf(msg.parts), '你好');
});

test('parseMessage: role 缺失默认 ASSISTANT;未知 role 抛错(对齐 valueOf)', () => {
  const def = parseChatCompletionMessage({ content: 'x' });
  assert.equal(def.role, 'assistant');
  assert.throws(() => parseChatCompletionMessage({ role: 'weird', content: 'x' }));
});

test('parseMessage: 空 reasoning_content 也产出 reasoning part 且带 explicit 标记', () => {
  // ChatCompletionsAPIMessageTest: empty reasoning_content should round trip without placeholder
  const msg = parseChatCompletionMessage({ role: 'assistant', reasoning_content: '', content: 'ok' });
  const reasoning = msg.parts.filter(
    (p: UIMessagePart): p is UIMessagePartReasoning => p.type === 'reasoning');
  assert.equal(reasoning.length, 1);
  assert.equal(reasoning[0].reasoning, '');
  assert.ok(hasExplicitReasoningContentField(reasoning[0].metadata));
  assert.equal(textOf(msg.parts), 'ok');
});

test('parseMessage: reasoning 回退链 reasoning_content > reasoning > Mistral thinking', () => {
  const a = parseChatCompletionMessage({ reasoning_content: 'rc', reasoning: 'r' });
  assert.equal(reasoningOf(a.parts), 'rc');
  const b = parseChatCompletionMessage({ reasoning: 'r' });
  const bParts = b.parts.filter((p: UIMessagePart): p is UIMessagePartReasoning => p.type === 'reasoning');
  assert.equal(bParts[0].reasoning, 'r');
  // reasoning key 不带 explicit 标记(只有 reasoning_content key 才带)
  assert.ok(!hasExplicitReasoningContentField(bParts[0].metadata));
  // Mistral: content 为数组 [{thinking:[{text}]}]
  const c = parseChatCompletionMessage({
    content: [{ type: 'thinking', thinking: [{ type: 'text', text: '好的' }] }],
  });
  assert.equal(reasoningOf(c.parts), '好的');
});

test('parseMessage: tool_calls 带 index → stream_tool_index metadata;非 function 类型抛错', () => {
  const msg = parseChatCompletionMessage({
    role: 'assistant',
    tool_calls: [
      { index: 1, id: 'call_1', type: 'function', function: { name: 'search', arguments: '{"q":1}' } },
    ],
  });
  const tools = getTools(msg);
  assert.equal(tools.length, 1);
  assert.equal(tools[0].toolCallId, 'call_1');
  assert.equal(tools[0].toolName, 'search');
  assert.equal(tools[0].input, '{"q":1}');
  assert.equal(streamToolIndex(tools[0]), 1);
  assert.deepEqual(tools[0].approvalState, { type: 'auto' });
  assert.throws(() => parseChatCompletionMessage({
    tool_calls: [{ type: 'custom', function: { name: 'x', arguments: '' } }],
  }), /tool call type not supported/);
});

test('parseMessage: images 仅支持 data uri,剥离 base64 前缀', () => {
  const msg = parseChatCompletionMessage({
    images: [{ type: 'image_url', image_url: { url: 'data:image/png;base64,QUJD' } }],
  });
  const img = msg.parts.find((p: UIMessagePart): boolean => p.type === 'image');
  assert.ok(img !== undefined);
  assert.equal((img as { url: string }).url, 'QUJD');
  assert.throws(() => parseChatCompletionMessage({
    images: [{ type: 'image_url', image_url: { url: 'https://x/y.png' } }],
  }), /Only data uri/);
});

test('parseMessage: annotations url_citation;未知类型抛错', () => {
  const msg = parseChatCompletionMessage({
    annotations: [{ type: 'url_citation', url_citation: { title: 'T', url: 'https://x' } }],
  });
  assert.deepEqual(msg.annotations, [{ type: 'url_citation', title: 'T', url: 'https://x' }]);
  assert.throws(() => parseChatCompletionMessage({
    annotations: [{ type: 'weird' }],
  }), /unknown annotation type/);
});

// ===== parseTokenUsage(ChatCompletionsAPI.kt:832-849) =====

test('parseTokenUsage: 标准字段 + cached 回退链', () => {
  const u = parseOpenAiTokenUsage({
    prompt_tokens: 10, completion_tokens: 5, total_tokens: 15,
    prompt_tokens_details: { cached_tokens: 3 },
  });
  assert.ok(u !== null);
  assert.equal(u.promptTokens, 10);
  assert.equal(u.completionTokens, 5);
  assert.equal(u.totalTokens, 15);
  assert.equal(u.cachedTokens, 3);

  // prompt_cache_hit/miss 求和回退 + cached 用 hit
  const v = parseOpenAiTokenUsage({ prompt_cache_hit_tokens: 4, prompt_cache_miss_tokens: 6 });
  assert.ok(v !== null);
  assert.equal(v.promptTokens, 10);
  assert.equal(v.cachedTokens, 4);

  assert.equal(parseOpenAiTokenUsage(null), null);
});

// ===== parseErrorDetail(ErrorParser.kt) =====

test('parseErrorDetail: 字段递归 error>detail>message>description,空数组/裸对象兜底', () => {
  const e1 = parseOpenAiErrorDetail({ error: { message: 'quota exceeded', type: 'insufficient_quota' } });
  assert.equal(e1.message, 'quota exceeded');
  const e2 = parseOpenAiErrorDetail({ message: 'm' });
  assert.equal(e2.message, 'm');
  const e3 = parseOpenAiErrorDetail([]);
  assert.equal(e3.message, 'Unknown error: Empty JSON array');
  const e4 = parseOpenAiErrorDetail([{ detail: 'd' }]);
  assert.equal(e4.message, 'd');
  const e5 = parseOpenAiErrorDetail({ code: 42 });
  assert.equal(e5.message, JSON.stringify({ code: 42 }));
  const e6 = parseOpenAiErrorDetail('raw');
  assert.equal(e6.message, 'raw');
});

// ===== parseOpenAiStreamEventData(streamText onEvent,ChatCompletionsAPI.kt:199-256) =====

test('streamEventData: error payload 抛 OpenAiStreamError 并带原始 payload', () => {
  assert.throws(
    () => parseOpenAiStreamEventData('{"error":{"message":"quota exceeded","type":"insufficient_quota"}}'),
    (err: unknown): boolean => {
      assert.ok(err instanceof OpenAiStreamError);
      assert.equal((err as Error).message, 'quota exceeded');
      const payload = (err as OpenAiStreamError).payload as JsonObject;
      assert.equal(payload['type'], 'insufficient_quota');
      return true;
    },
  );
});

test('streamEventData: choices 空(usage-only chunk)产出空 choices + usage', () => {
  const { chunks, done } = parseOpenAiStreamEventData(
    '{"id":"x","model":"m","choices":[],"usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}}');
  assert.equal(done, false);
  assert.equal(chunks.length, 1);
  assert.equal(chunks[0].choices.length, 0);
  assert.equal(chunks[0].usage?.totalTokens, 3);
});

test('streamEventData: finish_reason null → "unknown"(Android 归一)', () => {
  const { chunks } = parseOpenAiStreamEventData(
    '{"choices":[{"index":0,"delta":{"content":"a"},"finish_reason":null}]}');
  assert.equal(chunks[0].choices[0].finishReason, 'unknown');
  const { chunks: c2 } = parseOpenAiStreamEventData(
    '{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}');
  assert.equal(c2[0].choices[0].finishReason, 'stop');
});

test('streamEventData: delta 缺失时取 message;两者都缺跳过该 choice(usage 保留)', () => {
  const { chunks } = parseOpenAiStreamEventData(
    '{"choices":[{"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}]}');
  assert.equal(textOf(chunks[0].choices[0].delta?.parts ?? []), 'hi');
  // 网关 usage-only/keep-alive 尾块:choices:[{}] 不得让整条流报废
  const r = parseOpenAiStreamEventData(
    '{"choices":[{"index":0,"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":1,"total_tokens":6}}');
  assert.equal(r.chunks.length, 1);
  assert.equal(r.chunks[0].choices.length, 0);
  assert.equal(r.chunks[0].usage?.promptTokens, 5);
});

test('streamEventData: 纯 [DONE] 数据 → done 信号,无 chunk', () => {
  const { chunks, done } = parseOpenAiStreamEventData('[DONE]');
  assert.equal(done, true);
  assert.equal(chunks.length, 0);
});

test('streamEventData: 单 event 多 payload(独立 JSON 行)逐一出 chunk', () => {
  const raw = '{"choices":[{"delta":{"content":"a"},"finish_reason":null}]}\n{"choices":[{"delta":{"content":"b"},"finish_reason":null}]}';
  const { chunks } = parseOpenAiStreamEventData(raw);
  assert.equal(chunks.length, 2);
  assert.equal(textOf(chunks[0].choices[0].delta?.parts ?? []), 'a');
  assert.equal(textOf(chunks[1].choices[0].delta?.parts ?? []), 'b');
});

// ===== fixture 回放(harmony/tests/fixtures/streaming) =====

test('replay: openai_chat_completion_chunks — 文本累积 + finish + [DONE]', () => {
  const acc = new MessageStreamAccumulator([makeUserMessage('hi')]);
  let sawDone = false;
  for (const line of readJsonl('openai_chat_completion_chunks.jsonl')) {
    const expected = (line.expected ?? {}) as Record<string, JsonValue>;
    for (const evt of assemble(line.raw ?? '')) {
      if (evt.done) {
        sawDone = true;
        assert.equal(expected['done'], true);
        continue;
      }
      const { chunks } = parseOpenAiStreamEventData(evt.data);
      for (const chunk of chunks) {
        acc.append(chunk);
        const delta = chunk.choices[0]?.delta;
        if (expected['role'] !== undefined) {
          assert.equal(delta?.role, expected['role']);
        }
        if (expected['textDelta'] !== undefined) {
          assert.equal(textOf(delta?.parts ?? []), expected['textDelta']);
        }
        if (expected['finishReason'] !== undefined) {
          const fr = chunk.choices[0]?.finishReason;
          assert.equal(fr, expected['finishReason'] === null ? 'unknown' : expected['finishReason']);
        }
      }
    }
    if (expected['accumulatedText'] !== undefined) {
      const last = acc.snapshot()[acc.snapshot().length - 1];
      assert.equal(textOf(last.parts), expected['accumulatedText']);
    }
  }
  assert.ok(sawDone, '应见到 [DONE]');
});

test('replay: openai_done_and_error — error 抛错 + done', () => {
  const lines = readJsonl('openai_done_and_error.jsonl');
  // error 行
  const errEvents = assemble(lines[0].raw ?? '');
  assert.equal(errEvents.length, 1);
  assert.throws(
    () => parseOpenAiStreamEventData(errEvents[0].data),
    (err: unknown): boolean => {
      const expected = (lines[0].expected ?? {}) as Record<string, JsonValue>;
      assert.equal((err as Error).message, expected['errorMessage']);
      const payload = (err as OpenAiStreamError).payload as JsonObject;
      assert.equal(payload['type'], expected['errorType']);
      return true;
    },
  );
  // [DONE] 行
  const doneEvents = assemble(lines[1].raw ?? '');
  assert.equal(doneEvents.length, 1);
  assert.equal(doneEvents[0].done, true);
});

test('replay: openai_malformed_nested_data_prefix — assembler+normalize 联合归一', () => {
  for (const line of readJsonl('openai_malformed_nested_data_prefix.jsonl')) {
    const events = assemble(line.raw ?? '');
    assert.equal(events.length, 1);
    const payloads = normalizeOpenAIStreamDataLines(events[0].data);
    assert.deepEqual(payloads, [line.expectedPayload]);
  }
});

test('replay: openai_parallel_tool_deltas — 并行 tool 按 stream index 合并', () => {
  const acc = new MessageStreamAccumulator([makeUserMessage('use both')]);
  for (const line of readJsonl('openai_parallel_tool_deltas.jsonl')) {
    for (const evt of assemble(line.raw ?? '')) {
      assert.ok(!evt.done);
      for (const chunk of parseOpenAiStreamEventData(evt.data).chunks) {
        acc.append(chunk);
      }
    }
    const expected = ((line.expected ?? {})['tools'] ?? []) as Array<Record<string, JsonValue>>;
    const last = acc.snapshot()[acc.snapshot().length - 1];
    const tools = getTools(last);
    for (const exp of expected) {
      const tool = tools.find(
        (t: UIMessagePartTool): boolean => streamToolIndex(t) === exp['streamIndex']);
      assert.ok(tool !== undefined, `缺 streamIndex=${String(exp['streamIndex'])} 的 tool`);
      assert.equal(tool.toolCallId, exp['id']);
      assert.equal(tool.toolName, exp['name']);
      assert.equal(tool.input, exp['input']);
    }
  }
  // 终态:两个 tool 完整参数(对齐 Android streamed parallel tool argument deltas 测试)
  const last = acc.snapshot()[acc.snapshot().length - 1];
  const byId = new Map(getTools(last).map((t: UIMessagePartTool): [string, UIMessagePartTool] => [t.toolCallId, t]));
  assert.equal(byId.get('tool_a')?.input, '{"query":"amber');
  assert.equal(byId.get('tool_b')?.input, '{"path":"README.md"}');
});

test('replay: openai_reasoning_content_chunks — reasoning 累积 + 关闭规则', () => {
  const acc = new MessageStreamAccumulator([makeUserMessage('q')]);
  for (const line of readJsonl('openai_reasoning_content_chunks.jsonl')) {
    const expected = (line.expected ?? {}) as Record<string, JsonValue>;
    for (const evt of assemble(line.raw ?? '')) {
      assert.ok(!evt.done);
      for (const chunk of parseOpenAiStreamEventData(evt.data).chunks) {
        acc.append(chunk);
        const delta = chunk.choices[0]?.delta;
        if (expected['reasoningDelta'] !== undefined) {
          assert.equal(reasoningOf(delta?.parts ?? []), expected['reasoningDelta']);
        }
        if (expected['hasExplicitReasoningContentField'] === true) {
          const r = (delta?.parts ?? []).find(
            (p: UIMessagePart): p is UIMessagePartReasoning => p.type === 'reasoning');
          assert.ok(r !== undefined && hasExplicitReasoningContentField(r.metadata));
        }
        if (expected['textDelta'] !== undefined) {
          assert.equal(textOf(delta?.parts ?? []), expected['textDelta']);
        }
      }
    }
    const last = acc.snapshot()[acc.snapshot().length - 1];
    if (expected['accumulatedReasoning'] !== undefined) {
      assert.equal(reasoningOf(last.parts), expected['accumulatedReasoning']);
    }
    if (expected['reasoningShouldBeMarkedFinished'] === true) {
      const r = last.parts.find(
        (p: UIMessagePart): p is UIMessagePartReasoning => p.type === 'reasoning');
      assert.ok(r !== undefined && r.finishedAt !== null, 'reasoning 应被正文 delta 关闭');
    }
  }
});

test('replay: normalize-only fixtures(independent/multi-line/data-prefixed)', () => {
  const independent = readJson('openai_independent_json_lines.json');
  const p1 = normalizeOpenAIStreamDataLines(independent['rawData'] as string);
  assert.deepEqual(p1.map((s: string): JsonValue => JSON.parse(s) as JsonValue),
    independent['expectedPayloads'] as JsonValue[]);

  const multiLine = readJson('openai_multi_line_payload.json');
  const p2 = normalizeOpenAIStreamDataLines(multiLine['rawData'] as string);
  assert.equal(p2.length, multiLine['expectedPayloadCount'] as number);
  assert.deepEqual(JSON.parse(p2[0]) as JsonValue, multiLine['expectedJson'] as JsonValue);

  const prefixed = readJson('openai_multiline_data_prefixed_payload.json');
  const p3 = normalizeOpenAIStreamDataLines(prefixed['rawData'] as string);
  assert.equal(p3.length, prefixed['expectedPayloadCount'] as number);
  assert.deepEqual(JSON.parse(p3[0]) as JsonValue, prefixed['expectedJson'] as JsonValue);
});
