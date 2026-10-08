// conversation_export tests

import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  exportConversationMarkdown, exportConversationJson, exportConversationJsonString,
  exportFileNameFor, maskSensitiveJsonObject, maskSensitiveJsonText,
  messageImageExportText,
  messageArchiveMarkdown, messageArchiveSource,
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

test('mask nested array JSON without changing the source or ordinary text', () => {
  const source = { entries: [{ api_key: 'array-secret', nested: [{ password: 'nested-secret', ok: 'visible' }] }],
    headers: { 'x-api-key': 'header-secret', 'X-Goog-Api-Key': 'google-header-secret', Accept: 'application/json' },
    token_count: '42', session_id: 'terminal-session', accessToken: 'access-secret',
    text: 'token is an ordinary word', empty_token: '' };
  const original = JSON.stringify(source);
  const masked = maskSensitiveJsonObject(source);
  assert.deepEqual(masked, { entries: [{ api_key: '***', nested: [{ password: '***', ok: 'visible' }] }],
    headers: { 'x-api-key': '***', 'X-Goog-Api-Key': '***', Accept: 'application/json' },
    token_count: '42', session_id: 'terminal-session', accessToken: '***',
    text: 'token is an ordinary word', empty_token: '' });
  assert.equal(JSON.stringify(source), original);
  assert.deepEqual(JSON.parse(maskSensitiveJsonText('[{"authorization":"Bearer secret"}]')),
    [{ authorization: '***' }]);
  assert.equal(maskSensitiveJsonText('ordinary output: token'), 'ordinary output: token');
  assert.equal(maskSensitiveJsonText('{"api_key":'), '{"api_key":');
});

test('Markdown and JSON tool exports mask the same data while keeping runnable messages intact', () => {
  const message = makeUIMessage('assistant', [{ type: 'tool', toolName: 'provider_config', toolCallId: 't1',
    input: '{"providers":[{"apiKey":"input-secret","name":"visible"}]}',
    output: [{ type: 'text', text: '{"entries":[{"secret":"output-secret","ok":true}]}', metadata: null },
      { type: 'text', text: 'completed normally', metadata: null }],
    approvalState: { type: 'approved' }, metadata: null }]);
  const conv = makeConversation('tools', [toMessageNode(message)]);
  const original = JSON.stringify(conv);
  const markdown = exportConversationMarkdown(conv);
  const json = exportConversationJsonString(conv);
  for (const exported of [markdown, json]) {
    assert.ok(!exported.includes('input-secret'));
    assert.ok(!exported.includes('output-secret'));
    assert.ok(exported.includes('visible'));
    assert.ok(exported.includes('completed normally'));
    assert.ok(exported.includes('***'));
  }
  assert.equal(JSON.stringify(conv), original);
  const exportedTool = exportConversationJson(conv).messages[0].parts[0];
  assert.equal(exportedTool.type, 'tool');
  if (exportedTool.type === 'tool') {
    assert.deepEqual(JSON.parse(exportedTool.input), { providers: [{ apiKey: '***', name: 'visible' }] });
    assert.deepEqual(JSON.parse(exportedTool.output[0].type === 'text' ? exportedTool.output[0].text : ''),
      { entries: [{ secret: '***', ok: true }] });
  }
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

test('Workspace message Markdown preserves source, one selected message and original conversation', () => {
  const conversation = sample();
  const node = conversation.messageNodes[1];
  const message = node.messages[0];
  const original = JSON.stringify(conversation);
  const archived = messageArchiveMarkdown(conversation, message.id);
  assert.deepEqual(messageArchiveSource(archived), {
    conversationId: conversation.id, messageId: message.id, nodeId: node.id,
  });
  assert.ok(archived.includes('你好！有什么可以帮你？'));
  assert.ok(!archived.includes('你好世界'));
  assert.equal(JSON.stringify(conversation), original);
  assert.equal(messageArchiveSource('# 普通 Workspace 文件'), null);
  assert.equal(messageArchiveSource('<!-- amber-chat-source {broken} -->'), null);
  assert.throws(() => messageArchiveMarkdown(conversation, 'missing-message'), /当前对话分支/);
});
