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
  makeUserMessage, makeUIMessage, toText, summaryAsText, getTools, isValidToUpload, hasBase64Part,
  isEmptyInputMessage, isEmptyUIMessage,
} from '../main/ets/chat/message.ts';
import type { UIMessagePartTool, UIMessagePartReasoning, UIMessagePartImage } from '../main/ets/chat/message.ts';

// ===== 工厂 =====

// ===== JSON 线格式 =====

// ===== 默认值(对齐 Android 构造默认值) =====

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
