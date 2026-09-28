// conversation_export tests

import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  exportConversationMarkdown, exportConversationJson, exportConversationJsonString,
  exportFileNameFor, maskSensitiveJsonObject,
  messageImageExportText,
} from '../main/ets/chat/conversation_export.ts';
import { makeConversation, toMessageNode } from '../main/ets/chat/conversation.ts';
import { makeUserMessage, makeAssistantMessage, makeUIMessage } from '../main/ets/chat/message.ts';

const sample = () => makeConversation('c1', [
  toMessageNode(makeUserMessage('你好世界')),
  toMessageNode(makeAssistantMessage('你好！有什么可以帮你？')),
], { title: '测试会话' });

test('export markdown: title + roles + bodies', () => {
  const md = exportConversationMarkdown(sample());
  assert.ok(md.indexOf('# 测试会话') >= 0);
  assert.ok(md.indexOf('## 用户') >= 0);
  assert.ok(md.indexOf('## 助手') >= 0);
  assert.ok(md.indexOf('你好世界') >= 0);
  assert.ok(md.indexOf('你好！有什么可以帮你？') >= 0);
});

test('export markdown: selectedNodeIds filters', () => {
  const conv = sample();
  const onlyFirst = exportConversationMarkdown(conv, { selectedNodeIds: [conv.messageNodes[0].id] });
  assert.ok(onlyFirst.indexOf('你好世界') >= 0);
  assert.ok(onlyFirst.indexOf('你好！有什么可以帮你？') < 0);
});

test('export markdown: fallback title', () => {
  const conv = makeConversation('c2', [toMessageNode(makeUserMessage('hi'))], { title: '  ' });
  const md = exportConversationMarkdown(conv, { fallbackTitle: '未命名对话' });
  assert.ok(md.indexOf('# 未命名对话') >= 0);
});

test('export json: meta + messages', () => {
  const json = exportConversationJson(sample());
  assert.equal(json.title, '测试会话');
  assert.equal(json.messages.length, 2);
  assert.equal(json.messages[0].role, 'user');
});

test('export json string parseable + filename', () => {
  const conv = sample();
  const raw = exportConversationJsonString(conv);
  const parsed = JSON.parse(raw) as { messages: unknown[] };
  assert.equal(parsed.messages.length, 2);
  const name = exportFileNameFor(conv, 'md');
  assert.ok(name.endsWith('.md'));
  assert.ok(name.indexOf('测试会话') >= 0 || name.indexOf('conversation') >= 0);
});

test('maskSensitiveJsonObject', () => {
  const masked = maskSensitiveJsonObject({
    api_key: 'sk-secret',
    Authorization: 'Bearer x',
    nested: { token: 't', ok: 'v' },
  });
  assert.equal(masked['api_key'], '***');
  assert.equal(masked['Authorization'], '***');
  const nested = masked['nested'] as Record<string, unknown>;
  assert.equal(nested['token'], '***');
  assert.equal(nested['ok'], 'v');
});

test('image export includes reasoning only when requested', () => {
  const message = makeUIMessage('assistant', [
    { type: 'reasoning', reasoning: '比较两种方案', createdAt: '', finishedAt: null, metadata: null },
    { type: 'text', text: '选用第二种', metadata: null },
  ]);
  assert.equal(messageImageExportText(message, false), '选用第二种');
  assert.equal(messageImageExportText(message, true), '思考过程：\n比较两种方案\n\n选用第二种');
  assert.equal(messageImageExportText(makeUIMessage('user', []), false), '[非文本内容]');
});
