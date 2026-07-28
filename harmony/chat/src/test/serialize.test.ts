// UIMessage JSON 序列化规格测试 — kotlinx 线格式 1:1
//
// Android 基准:
//   ai/util/Json.kt: ignoreUnknownKeys / encodeDefaults=true / explicitNulls=false / isLenient
//   ai/ui/Message.kt: sealed part discriminator "type"(SerialName),字段声明序,
//     Reasoning.finishedAt 默认 Clock.System.now()(缺省即解析时刻 — quirk Q-1)
// 金色 blob:按 kotlinx 规则手工推导(鉴别器在前、声明序、默认值编码、null 省略)

import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  serializeUIMessage, parseUIMessage,
  serializeMessageList, parseMessageList,
} from '../main/ets/chat/serialize.ts';
import { makeUIMessage, makeUserMessage } from '../main/ets/chat/message.ts';
import type { UIMessage, UIMessagePart } from '../main/ets/chat/message.ts';
import type { JsonValue } from '../main/ets/chat/json.ts';

const FIXED_NOW = '2099-01-01T00:00:00.000Z';
const fixedNow = (): string => FIXED_NOW;

// ===== serialize:金色 blob =====

test('serialize: 基础 user text 消息 — 键序/null 省略/annotations 空数组编码', () => {
  const msg = makeUserMessage('你好');
  msg.id = 'm1';
  msg.createdAt = '2026-07-28T10:00:00.000Z';
  const json = JSON.stringify(serializeUIMessage(msg));
  assert.equal(
    json,
    '{"id":"m1","role":"user","parts":[{"type":"text","text":"你好"}],"annotations":[],"createdAt":"2026-07-28T10:00:00.000Z"}',
  );
});

test('serialize: 默认值编码(encodeDefaults=true) — tool output/approvalState、video mime', () => {
  const toolMsg = makeUIMessage('assistant', [{
    type: 'tool', toolCallId: 'c1', toolName: 'search', input: '{}',
    output: [], approvalState: { type: 'auto' }, metadata: null,
  }]);
  const toolJson = JSON.stringify((serializeUIMessage(toolMsg).parts as JsonValue[])[0]);
  assert.equal(
    toolJson,
    '{"type":"tool","toolCallId":"c1","toolName":"search","input":"{}","output":[],"approvalState":{"type":"auto"}}',
  );
  const videoMsg = makeUIMessage('user', [{
    type: 'video', url: 'file://v', mime: 'video/mp4', metadata: null,
  }]);
  assert.equal(
    JSON.stringify((serializeUIMessage(videoMsg).parts as JsonValue[])[0]),
    '{"type":"video","url":"file://v","mime":"video/mp4"}',
  );
});

test('serialize: approvalState 变体 — denied reason 默认 "" 也编码', () => {
  const denied = makeUIMessage('assistant', [{
    type: 'tool', toolCallId: 'c', toolName: 't', input: '',
    output: [], approvalState: { type: 'denied', reason: '' }, metadata: null,
  }]);
  const part = (serializeUIMessage(denied).parts as JsonValue[])[0] as { approvalState: unknown };
  assert.equal(JSON.stringify(part.approvalState), '{"type":"denied","reason":""}');
  const answered = makeUIMessage('assistant', [{
    type: 'tool', toolCallId: 'c', toolName: 't', input: '',
    output: [], approvalState: { type: 'answered', answer: 'yes' }, metadata: null,
  }]);
  const part2 = (serializeUIMessage(answered).parts as JsonValue[])[0] as { approvalState: unknown };
  assert.equal(JSON.stringify(part2.approvalState), '{"type":"answered","answer":"yes"}');
});

test('serialize: reasoning finishedAt=null → 省略;非空 → 保留', () => {
  const open = makeUIMessage('assistant', [{
    type: 'reasoning', reasoning: '想', createdAt: '2026-07-28T10:00:00.000Z',
    finishedAt: null, metadata: null,
  }]);
  assert.equal(
    JSON.stringify((serializeUIMessage(open).parts as JsonValue[])[0]),
    '{"type":"reasoning","reasoning":"想","createdAt":"2026-07-28T10:00:00.000Z"}',
  );
  const closed = makeUIMessage('assistant', [{
    type: 'reasoning', reasoning: '想', createdAt: '2026-07-28T10:00:00.000Z',
    finishedAt: '2026-07-28T10:00:01.000Z', metadata: null,
  }]);
  assert.equal(
    JSON.stringify((serializeUIMessage(closed).parts as JsonValue[])[0]),
    '{"type":"reasoning","reasoning":"想","createdAt":"2026-07-28T10:00:00.000Z","finishedAt":"2026-07-28T10:00:01.000Z"}',
  );
});

test('serialize: usage/annotation/mini_app 完整字段', () => {
  const msg = makeUIMessage('assistant', [{
    type: 'mini_app', appId: 'a1', title: 'T', description: 'D',
    iconEmoji: null, category: null, permissions: [], htmlHash: null,
    version: 1, metadata: null,
  }], {
    annotations: [{ type: 'url_citation', title: 'ti', url: 'u' }],
    usage: { promptTokens: 1, completionTokens: 2, cachedTokens: 0, totalTokens: 3 },
  });
  msg.id = 'x';
  msg.createdAt = '2026-07-28T00:00:00.000Z';
  assert.equal(
    JSON.stringify(serializeUIMessage(msg)),
    '{"id":"x","role":"assistant","parts":[{"type":"mini_app","appId":"a1","title":"T","description":"D","permissions":[],"version":1}],'
      + '"annotations":[{"type":"url_citation","title":"ti","url":"u"}],'
      + '"createdAt":"2026-07-28T00:00:00.000Z",'
      + '"usage":{"promptTokens":1,"completionTokens":2,"cachedTokens":0,"totalTokens":3}}',
  );
});

// ===== parse: kotlinx 反序列化语义 =====

test('parse: 未知字段忽略;缺失 annotations → []', () => {
  const msg = parseUIMessage({
    id: 'm1', role: 'user',
    parts: [{ type: 'text', text: 'a' }],
    createdAt: '2026-07-28T10:00:00.000Z',
    futureField: { nested: true },
  }, fixedNow);
  assert.deepEqual(msg.annotations, []);
  assert.equal(msg.finishedAt, null);
  assert.equal(msg.modelId, null);
  assert.equal(msg.usage, null);
  assert.equal(msg.translation, null);
});

test('parse: 缺 role/parts → 抛错(kotlinx MissingFieldException 对齐)', () => {
  assert.throws(() => parseUIMessage({
    id: 'm1', parts: [], createdAt: '2026-07-28T10:00:00.000Z',
  }, fixedNow));
  assert.throws(() => parseUIMessage({
    id: 'm1', role: 'user', createdAt: '2026-07-28T10:00:00.000Z',
  }, fixedNow));
  assert.throws(() => parseUIMessage({
    id: 'm1', role: 'alien', parts: [], createdAt: '2026-07-28T10:00:00.000Z',
  }, fixedNow));
});

test('parse Q-1: reasoning finishedAt 缺省 → 解析时刻(kotlinx 默认值 quirk)', () => {
  // Android: Reasoning.finishedAt: Instant? = Clock.System.now()
  // null 被 explicitNulls=false 省略后,解码时"复活"为解析时刻 — 忠实复刻
  const msg = parseUIMessage({
    id: 'm', role: 'assistant',
    parts: [{ type: 'reasoning', reasoning: 'r', createdAt: '2026-07-28T10:00:00.000Z' }],
    createdAt: '2026-07-28T10:00:00.000Z',
  }, fixedNow);
  const r = msg.parts[0];
  assert.equal(r.type, 'reasoning');
  if (r.type === 'reasoning') assert.equal(r.finishedAt, FIXED_NOW);
});

test('parse: tool 缺省 output/approvalState → []/{auto};part 未知类型抛错', () => {
  const msg = parseUIMessage({
    id: 'm', role: 'assistant',
    parts: [{ type: 'tool', toolCallId: 'c', toolName: 't', input: 'x' }],
    createdAt: '2026-07-28T10:00:00.000Z',
  }, fixedNow);
  const tool = msg.parts[0];
  assert.equal(tool.type, 'tool');
  if (tool.type === 'tool') {
    assert.deepEqual(tool.output, []);
    assert.deepEqual(tool.approvalState, { type: 'auto' });
  }
  assert.throws(() => parseUIMessage({
    id: 'm', role: 'assistant',
    parts: [{ type: 'hologram', url: 'x' }],
    createdAt: '2026-07-28T10:00:00.000Z',
  }, fixedNow));
});

test('parse: part 默认值 — video/audio/document/mini_app', () => {
  const msg = parseUIMessage({
    id: 'm', role: 'user',
    parts: [
      { type: 'video', url: 'v' },
      { type: 'audio', url: 'a' },
      { type: 'document', url: 'd', fileName: 'f.txt' },
      { type: 'mini_app', appId: 'x', title: 't', description: 'd' },
    ],
    createdAt: '2026-07-28T10:00:00.000Z',
  }, fixedNow);
  const [v, a, d, mini] = msg.parts;
  assert.equal(v.type === 'video' ? v.mime : '', 'video/mp4');
  if (a.type === 'audio') {
    assert.equal(a.fileName, '');
    assert.equal(a.mime, 'audio/mpeg');
  }
  assert.equal(d.type === 'document' ? d.mime : '', 'text/*');
  if (mini.type === 'mini_app') {
    assert.deepEqual(mini.permissions, []);
    assert.equal(mini.version, 1);
    assert.equal(mini.iconEmoji, null);
    assert.equal(mini.htmlHash, null);
  }
});

// ===== round-trip =====

test('round-trip: 全 part 类型序列化→解析 deepEqual(reasoning finishedAt 非空)', () => {
  const parts: UIMessagePart[] = [
    { type: 'text', text: 't', metadata: { k: 'v' } },
    { type: 'image', url: 'u', metadata: null },
    { type: 'video', url: 'v', mime: 'video/webm', metadata: null },
    { type: 'audio', url: 'a', fileName: 'f', mime: 'audio/ogg', metadata: null },
    { type: 'document', url: 'd', fileName: 'doc.pdf', mime: 'application/pdf', metadata: null },
    {
      type: 'mini_app', appId: 'app', title: 'T', description: 'D',
      iconEmoji: '🐝', category: 'tool', permissions: ['net'], htmlHash: 'h',
      version: 2, metadata: null,
    },
    {
      type: 'reasoning', reasoning: 'r',
      createdAt: '2026-07-28T10:00:00.000Z', finishedAt: '2026-07-28T10:00:01.000Z', metadata: null,
    },
    {
      type: 'tool', toolCallId: 'c', toolName: 'search', input: '{"q":1}',
      output: [{ type: 'text', text: 'out', metadata: null }],
      approvalState: { type: 'approved' }, metadata: null,
    },
  ];
  const msg = makeUIMessage('assistant', parts, {
    annotations: [
      { type: 'url_citation', title: 'T', url: 'U' },
      { type: 'generation_interrupted', reason: 'process_death' },
    ],
    usage: { promptTokens: 3, completionTokens: 4, cachedTokens: 1, totalTokens: 7 },
    finishedAt: '2026-07-28T10:05:00.000Z',
    modelId: 'model-uuid',
    translation: '翻译',
  });
  msg.id = 'rt';
  msg.createdAt = '2026-07-28T09:00:00.000Z';
  const parsed = parseUIMessage(serializeUIMessage(msg), fixedNow);
  assert.deepEqual(parsed, msg);
});

test('message list blob: serialize/parse 数组(message_node.messages 列格式)', () => {
  const a = makeUserMessage('q');
  a.id = 'a';
  a.createdAt = '2026-07-28T10:00:00.000Z';
  const b = makeUIMessage('assistant', [{ type: 'text', text: 'r', metadata: null }]);
  b.id = 'b';
  b.createdAt = '2026-07-28T10:01:00.000Z';
  const blob: string = serializeMessageList([a, b]);
  assert.equal(blob[0], '[');
  const parsed = parseMessageList(blob, fixedNow);
  assert.deepEqual(parsed, [a, b]);
});
