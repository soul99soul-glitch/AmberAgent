import { test } from 'node:test';
import assert from 'node:assert/strict';
import { collectConversationArtifacts, widgetArtifactExport, artifactSourceNodeIndex } from '../main/ets/chat/conversation_artifacts.ts';
import { makeConversation, makeMessageNode } from '../main/ets/chat/conversation.ts';
import { makeUIMessage } from '../main/ets/chat/message.ts';
import { makeDisplaySetting, loadDisplaySetting, saveDisplaySetting } from '../main/ets/chat/display_setting.ts';
import { createMemoryKeyValueStore } from '../main/ets/chat/kv_store.ts';
import { groupMessageParts } from '../main/ets/chat/message_grouping.ts';
import { parseGenerativeWidgets } from '../main/ets/chat/generative_widget.ts';
const fence = (payload: object): string => '```show-widget\n' + JSON.stringify(payload) + '\n```\n';
const html = { title: 'Plan', widget_code: '<section><p>content</p></section>' };
const text = (body: string) => ({ type: 'text' as const, text: body, metadata: null });
const sample = () => {
  const message = makeUIMessage('assistant', [text(fence(html))]);
  const node = makeMessageNode([message]);
  const conversation = makeConversation('artifacts-test', [node]);
  return { message, node, conversation };
};

test('Shelf indexes completed HTML, chart and slides from selected assistant body with stable exact sources', () => {
  const { message, node, conversation } = sample();
  message.parts.push(text(fence({ title: 'Chart', renderer: 'vchart', spec: { type: 'bar', data: [{ values: [{ x: 'A', y: 2 }] }], xField: 'x', yField: 'y' } })),
    text(fence({ title: 'Slides', renderer: 'slides', spec: { slides: [{ title: 'One', content: ['Point'] }] } })));
  const first = collectConversationArtifacts(conversation, true);
  assert.equal(first.widgets.length, 3);
  assert.deepEqual(first.widgets.map((item) => item.widget.renderer), ['html', 'vchart', 'slides']);
  assert.deepEqual(first.widgets[0].source, { conversationId: conversation.id, nodeId: node.id, messageId: message.id });
  assert.deepEqual(first.widgets.map((item) => item.id), collectConversationArtifacts(conversation, true).widgets.map((item) => item.id));
  assert.equal(artifactSourceNodeIndex(conversation, first.widgets[0].source), 0);
  node.messages.push(makeUIMessage('assistant', [text('another branch')]));
  node.selectIndex = 1;
  assert.equal(collectConversationArtifacts(conversation, true).widgets.length, 0);
  assert.equal(artifactSourceNodeIndex(conversation, first.widgets[0].source), -1);
});

test('Shelf excludes incomplete/invalid/plain/user/reasoning/tool-output widgets and obeys display switch', () => {
  const { conversation, message } = sample();
  message.parts = [text('ordinary HTML <section>not a widget</section>'),
    text('```show-widget\n' + JSON.stringify(html)), text('```show-widget\n{bad}\n```'),
    { type: 'reasoning', reasoning: fence(html), metadata: null, createdAt: '', finishedAt: null },
    { type: 'tool', toolCallId: 't1', toolName: 'x', input: '{}', output: [text(fence(html))], approvalState: { type: 'auto' }, metadata: null }];
  conversation.messageNodes.push(makeMessageNode([makeUIMessage('user', [text(fence(html))])]));
  assert.equal(collectConversationArtifacts(conversation, true).widgets.length, 0);
  const other = sample();
  assert.equal(collectConversationArtifacts(other.conversation, false).widgets.length, 0);
});

test('Shelf discovers the same widget as the body when its fence spans adjacent text parts', () => {
  const { conversation, message } = sample();
  const body = fence(html);
  const split = body.indexOf('content');
  message.parts = [text(body.slice(0, split)), text(body.slice(split))];
  const displayed = groupMessageParts(message.parts).filter((block) => block.kind === 'content' && block.part.type === 'text');
  assert.equal(displayed.length, 1);
  if (displayed[0].kind !== 'content' || displayed[0].part.type !== 'text') assert.fail('missing merged body');
  assert.equal(parseGenerativeWidgets(displayed[0].part.text, false).filter((segment) => segment.kind === 'widget').length, 1);
  const artifacts = collectConversationArtifacts(conversation, true);
  assert.equal(artifacts.widgets.length, 1);
  assert.equal(artifacts.widgets[0].widget.title, 'Plan');
  assert.equal(artifacts.widgets[0].id, `${message.id}:0:0`);
});

test('Shelf preserves image/file/miniapp discovery and document versions while deduping image and miniapp identity', () => {
  const { conversation, message } = sample();
  message.parts = [
    { type: 'image', url: 'file:///image.png', metadata: null },
    { type: 'image', url: 'file:///image.png', metadata: null },
    { type: 'document', url: 'file:///v1.md', fileName: 'Draft.md', mime: 'text/markdown', metadata: null },
    { type: 'document', url: 'file:///v2.md', fileName: 'Draft.md', mime: 'text/markdown', metadata: null },
    { type: 'mini_app', appId: 'app-one', title: 'First', description: '', iconEmoji: null, category: null, permissions: [], htmlHash: null, version: 1, metadata: null },
    { type: 'mini_app', appId: 'app-one', title: 'Latest', description: '', iconEmoji: null, category: null, permissions: [], htmlHash: null, version: 2, metadata: null },
  ];
  const result = collectConversationArtifacts(conversation, false);
  assert.equal(result.images.length, 1);
  assert.equal(result.docs.length, 2);
  assert.deepEqual(result.duplicateDocumentNames, ['Draft.md']);
  assert.equal(result.miniApps.length, 1);
  assert.equal(result.miniApps[0].title, 'Latest');
});

test('Widget export uses the same safe standalone document and a bounded path-free HTML filename', () => {
  const { conversation, message } = sample();
  message.parts = [text(fence({ title: '../../very/long\n<export>', widget_code: '<section onclick="bad()">content<script>bad()</script><img src="https://unsafe.example/x"></section>' }))];
  const artifact = collectConversationArtifacts(conversation, true).widgets[0];
  const output = widgetArtifactExport(artifact, true);
  assert.match(output.fileName, /\.html$/);
  assert.doesNotMatch(output.fileName, /[\/\\\n<>]/);
  assert.match(output.content, /Content-Security-Policy/);
  assert.match(output.content, /#ECE8DF/);
  assert.match(output.content, /html,body\{background:#14110E\}/, 'standalone dark HTML must provide its own readable backdrop');
  assert.doesNotMatch(output.content, /<script|onclick|https:\/\/unsafe/);
  assert.match(output.content, /content/);
});

test('Generative display setting defaults on for old settings and off persists round-trip', async () => {
  const store = createMemoryKeyValueStore();
  assert.equal(makeDisplaySetting().enableGenerativeWidgets, true);
  await store.put('display_setting', '{"fontSizeRatio":1.2}');
  assert.equal((await loadDisplaySetting(store)).enableGenerativeWidgets, true);
  await saveDisplaySetting(store, makeDisplaySetting({ enableGenerativeWidgets: false }));
  assert.equal((await loadDisplaySetting(store)).enableGenerativeWidgets, false);
});
