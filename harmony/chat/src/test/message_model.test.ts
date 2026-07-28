// UIMessage / UIMessagePart 模型与 JSON 线格式规格测试
// 基准: ai/src/main/java/app/amber/ai/ui/Message.kt + DATA_SCHEMA_MATRIX J03
// 关键线格式约束:
//   - sealed part 鉴别器字段名必须是 "type"(kotlinx 默认 classDiscriminator)
//   - SerialName 必须小写: text/image/video/audio/document/mini_app/reasoning/tool
//   - role 序列化为小写: system/user/assistant/tool
//   - approvalState 也是 sealed: {type:"auto"|...}; denied 带 reason, answered 带 answer
//   - annotation: url_citation(title,url) / generation_interrupted(reason)

import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import {
  makeSystemMessage, makeUserMessage, makeAssistantMessage, makeUIMessage,
  toText, summaryAsText, getTools, isValidToUpload, hasBase64Part,
  isEmptyInputMessage, isEmptyUIMessage,
} from '../main/ets/chat/message.ts';
import type {
  UIMessage, UIMessagePart, UIMessagePartText, UIMessagePartTool,
  UIMessagePartReasoning, UIMessagePartImage, UIMessagePartMiniApp,
  UIMessagePartVideo, UIMessagePartAudio, UIMessagePartDocument,
} from '../main/ets/chat/message.ts';

// ===== 工厂 =====

test('makeUserMessage: role=user + 单个 text part', () => {
  const m = makeUserMessage('你好');
  assert.equal(m.role, 'user');
  assert.equal(m.parts.length, 1);
  assert.equal(m.parts[0].type, 'text');
  assert.equal((m.parts[0] as UIMessagePartText).text, '你好');
  assert.ok(m.id.length > 0);
  assert.ok(m.createdAt.length > 0);
});

test('makeSystemMessage / makeAssistantMessage 角色正确', () => {
  assert.equal(makeSystemMessage('s').role, 'system');
  assert.equal(makeAssistantMessage('a').role, 'assistant');
});

// ===== JSON 线格式 =====

test('JSON round-trip: text part 鉴别器字段是 type 且值小写', () => {
  const m = makeUserMessage('hi');
  const json = JSON.parse(JSON.stringify(m)) as UIMessage;
  assert.equal(json.role, 'user');
  assert.equal(json.parts[0].type, 'text');
  assert.deepEqual(json, m);
});

test('JSON round-trip: 全部 part 类型 SerialName 小写且字段名一致', () => {
  const parts: UIMessagePart[] = [
    { type: 'text', text: 't', metadata: null },
    { type: 'image', url: 'u', metadata: null },
    { type: 'video', url: 'u', mime: 'video/mp4', metadata: null },
    { type: 'audio', url: 'u', fileName: 'a.mp3', mime: 'audio/mpeg', metadata: null },
    { type: 'document', url: 'u', fileName: 'd.txt', mime: 'text/*', metadata: null },
    {
      type: 'mini_app', appId: 'x', title: 't', description: 'd',
      iconEmoji: null, category: null, permissions: [], htmlHash: null, version: 1,
      metadata: null,
    },
    { type: 'reasoning', reasoning: 'r', createdAt: '2026-07-28T00:00:00Z', finishedAt: null, metadata: null },
    {
      type: 'tool', toolCallId: 'c1', toolName: 'n', input: '{}',
      output: [], approvalState: { type: 'auto' }, metadata: null,
    },
  ];
  const m = makeUIMessage('assistant', parts);
  const json = JSON.parse(JSON.stringify(m)) as UIMessage;
  const names = json.parts.map(p => p.type);
  assert.deepEqual(names, ['text', 'image', 'video', 'audio', 'document', 'mini_app', 'reasoning', 'tool']);
  assert.deepEqual(json, m);
});

test('JSON: approvalState sealed 形状 — denied 带 reason, answered 带 answer', () => {
  const denied: UIMessagePartTool = {
    type: 'tool', toolCallId: 'c', toolName: 'n', input: '',
    output: [], approvalState: { type: 'denied', reason: 'r' }, metadata: null,
  };
  const json = JSON.parse(JSON.stringify(denied)) as UIMessagePartTool;
  assert.equal(json.approvalState.type, 'denied');
  assert.equal((json.approvalState as { type: 'denied'; reason: string }).reason, 'r');
  const answered: UIMessagePartTool = { ...denied, approvalState: { type: 'answered', answer: '42' } };
  const json2 = JSON.parse(JSON.stringify(answered)) as UIMessagePartTool;
  assert.equal((json2.approvalState as { type: 'answered'; answer: string }).answer, '42');
});

test('JSON: annotations url_citation / generation_interrupted 形状', () => {
  const m = makeUIMessage('assistant', [{ type: 'text', text: 't', metadata: null }], {
    annotations: [
      { type: 'url_citation', title: 'T', url: 'https://x' },
      { type: 'generation_interrupted', reason: 'process_death' },
    ],
  });
  const json = JSON.parse(JSON.stringify(m)) as UIMessage;
  assert.equal(json.annotations[0].type, 'url_citation');
  assert.equal(json.annotations[1].type, 'generation_interrupted');
  assert.deepEqual(json, m);
});

test('JSON: metadata JsonObject 透传', () => {
  const p: UIMessagePartText = { type: 'text', text: 't', metadata: { stream_tool_index: 2 } };
  const json = JSON.parse(JSON.stringify(p)) as UIMessagePartText;
  assert.equal((json.metadata as Record<string, number>).stream_tool_index, 2);
});

// ===== 默认值(对齐 Android 构造默认值) =====

test('part 工厂默认值: video mime=video/mp4, document mime=text/*, mini_app version=1', () => {
  const v: UIMessagePartVideo = { type: 'video', url: 'u', mime: 'video/mp4', metadata: null };
  const d: UIMessagePartDocument = { type: 'document', url: 'u', fileName: 'f', mime: 'text/*', metadata: null };
  const ma: UIMessagePartMiniApp = {
    type: 'mini_app', appId: 'a', title: 't', description: 'd',
    iconEmoji: null, category: null, permissions: [], htmlHash: null, version: 1, metadata: null,
  };
  assert.equal(v.mime, 'video/mp4');
  assert.equal(d.mime, 'text/*');
  assert.equal(ma.version, 1);
});

// ===== 帮助函数 =====

test('toText: 只拼接 text parts,\\n 分隔', () => {
  const m = makeUIMessage('assistant', [
    { type: 'text', text: 'a', metadata: null },
    { type: 'image', url: 'u', metadata: null },
    { type: 'text', text: 'b', metadata: null },
  ]);
  assert.equal(toText(m), 'a\n\nb');
});

test('summaryAsText: [ROLE]: 前缀大写枚举名', () => {
  const m = makeUserMessage('hi');
  assert.equal(summaryAsText(m), '[USER]: hi');
});

test('getTools: 过滤 tool parts', () => {
  const tool: UIMessagePartTool = {
    type: 'tool', toolCallId: 'c', toolName: 'n', input: '',
    output: [], approvalState: { type: 'auto' }, metadata: null,
  };
  const m = makeUIMessage('assistant', [{ type: 'text', text: 't', metadata: null }, tool]);
  assert.equal(getTools(m).length, 1);
  assert.equal(getTools(m)[0].toolCallId, 'c');
});

test('isValidToUpload: 全 blank text → false;有 image url → true;reasoning blank → false', () => {
  assert.equal(isValidToUpload(makeUIMessage('user', [{ type: 'text', text: '  ', metadata: null }])), false);
  assert.equal(isValidToUpload(makeUIMessage('user', [{ type: 'image', url: 'http://x', metadata: null }])), true);
  assert.equal(isValidToUpload(makeUIMessage('assistant', [
    { type: 'reasoning', reasoning: '', createdAt: 't', finishedAt: null, metadata: null },
  ])), false);
});

test('hasBase64Part: image url 以 data: 开头', () => {
  const img: UIMessagePartImage = { type: 'image', url: 'data:image/png;base64,xx', metadata: null };
  assert.equal(hasBase64Part(makeUIMessage('user', [img])), true);
  assert.equal(hasBase64Part(makeUIMessage('user', [{ ...img, url: 'http://x' }])), false);
});

test('isEmptyInputMessage: 空列表 true;reasoning 不算用户输入(按空处理)', () => {
  assert.equal(isEmptyInputMessage([]), true);
  assert.equal(isEmptyInputMessage([{ type: 'text', text: '  ', metadata: null }]), true);
  assert.equal(isEmptyInputMessage([{ type: 'text', text: 'x', metadata: null }]), false);
  const r: UIMessagePartReasoning = { type: 'reasoning', reasoning: 'r', createdAt: 't', finishedAt: null, metadata: null };
  assert.equal(isEmptyInputMessage([r]), true);
});

test('isEmptyUIMessage: reasoning 按内容判空;tool 按空处理(else 分支)', () => {
  assert.equal(isEmptyUIMessage([]), true);
  const rBlank: UIMessagePartReasoning = { type: 'reasoning', reasoning: '', createdAt: 't', finishedAt: null, metadata: null };
  const rFull: UIMessagePartReasoning = { ...rBlank, reasoning: 'think' };
  assert.equal(isEmptyUIMessage([rBlank]), true);
  assert.equal(isEmptyUIMessage([rFull]), false);
  const tool: UIMessagePartTool = {
    type: 'tool', toolCallId: 'c', toolName: 'n', input: '',
    output: [], approvalState: { type: 'auto' }, metadata: null,
  };
  assert.equal(isEmptyUIMessage([tool]), true);
});
