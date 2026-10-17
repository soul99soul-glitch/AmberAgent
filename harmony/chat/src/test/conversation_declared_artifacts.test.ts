import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  collectConversationArtifacts,
  sceneArtifactExport,
  svgArtifactExport,
  uiSnapshotArtifactExport,
} from '../main/ets/chat/conversation_artifacts.ts';
import type { ConversationSvgValidationProof } from '../main/ets/chat/conversation_artifacts.ts';
import { makeConversation, makeMessageNode } from '../main/ets/chat/conversation.ts';
import { makeUIMessage } from '../main/ets/chat/message.ts';
import { parseResponseContent } from '../main/ets/chat/response_content.ts';
import { cloneSmartUiComponent } from '../main/ets/chat/smart_ui/components.ts';
import type { SmartUiComponent } from '../main/ets/chat/smart_ui/components.ts';

const text = (value: string) => ({ type: 'text' as const, text: value, metadata: null });
const svgBody = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20"><rect width="20" height="20" fill="#3366ff"/></svg>';
const svgBodyWithoutNamespace = '<svg viewBox="0 0 20 20"><rect width="20" height="20" fill="#3366ff"/></svg>';
const sceneBody = JSON.stringify({ objects: [{ id: 'cube', type: 'box', color: '#3366ff', position: [0, 0, 0],
  rotation: [0, 0, 0], scale: [1, 1, 1], size: [2, 2, 2] }] });
const uiBody = [
  '{"op":"state","id":"selected","initial":"root"}',
  '{"op":"node","id":"root","type":"column","variant":"group","layout":"grid","tone":"info"}',
  '{"op":"node","id":"heading","parent":"root","type":"text","variant":"heading","tone":"accent","text":"企业 CA","bind":{"ref":"weather","path":"temperature_celsius"}}',
  '{"op":"node","id":"choice","parent":"root","type":"choice","state":"selected","options":[{"value":"root","label":"根证书"},{"value":"server","label":"服务器证书"}]}',
  '{"op":"node","id":"continue","parent":"root","type":"button","label":"继续解释","action":{"type":"draft","text":"继续解释 {{selected}}"}}',
  '{"op":"end"}',
].join('\n');
const declared = (language: string, id: string, body: string): string =>
  `\`\`\`${language} v=1 id=${id}\n${body}\n\`\`\`\n`;

test('rich native artifact snapshots retain semantic controls, row data, style and local reset', () => {
  const body = [
    { op: 'state', id: 'choices', initial: '[]' },
    { op: 'node', id: 'root', type: 'column' },
    { op: 'node', id: 'choicesNode', parent: 'root', type: 'component', state: 'choices', required: true, label: '选活动', options: [{ value: 'walk', label: '散步', detail: '附近公园', color: 'green' }], component: { kind: 'multiChoice', title: '活动偏好', style: { tone: 'accent' } } },
    { op: 'node', id: 'table', parent: 'root', type: 'component', component: { kind: 'table', title: '计划数据', rows: [{ label: '周六', value: 2, detail: '全天', cells: ['上午', '下午'] }] } },
    { op: 'node', id: 'reset', parent: 'root', type: 'component', component: { kind: 'button', title: '重置' }, action: { type: 'reset' } },
    { op: 'end' },
  ].map((record) => JSON.stringify(record)).join('\n');
  const message = makeUIMessage('assistant', [text(declared('amber-ui', 'rich-controls', body))]);
  const conversation = makeConversation('rich-native-artifacts', [makeMessageNode([message])]);
  const index = collectConversationArtifacts(conversation, false);
  assert.equal(index.uiSnapshots.length, 1);
  const snapshot = index.uiSnapshots[0];
  const choices = snapshot.definition.nodes.find((node) => node.id === 'choicesNode')!;
  assert.equal(choices.required, true);
  assert.equal(choices.component?.kind, 'multiChoice');
  assert.equal(choices.component?.style?.tone, 'accent');
  assert.deepEqual(choices.options, [{ value: 'walk', label: '散步', detail: '附近公园', color: 'green' }]);
  assert.deepEqual(snapshot.definition.nodes.find((node) => node.id === 'reset')?.action, { type: 'reset' });
  assert.match(snapshot.summary, /活动偏好/);
  assert.match(snapshot.summary, /计划数据/);
  const exported = uiSnapshotArtifactExport(snapshot);
  assert.ok(exported);
  const payload = JSON.parse(exported.content);
  assert.deepEqual(payload.definition.nodes.find((node: { id: string }) => node.id === 'table').component.rows, [{ label: '周六', value: 2, detail: '全天', cells: ['上午', '下午'] }]);
});

test('native component snapshot rows, items and style do not share mutable references', () => {
  const original: SmartUiComponent = { kind: 'table', items: ['列一'], rows: [{ label: '甲', cells: ['原始值'] }], style: { tone: 'accent' } };
  const copy = cloneSmartUiComponent(original);
  copy.items![0] = '被修改';
  copy.rows![0].cells![0] = '新值';
  copy.style!.tone = 'warning';
  assert.deepEqual(original.items, ['列一']);
  assert.deepEqual(original.rows![0].cells, ['原始值']);
  assert.equal(original.style!.tone, 'accent');
});

test('declared artifacts use selected branches, preserve source, and index mixed content by type', () => {
  const body = `说明\n${declared('amber-ui', 'controls', uiBody)}\n${declared('amber-svg', 'diagram', svgBody)}\n${declared('amber-three', 'scene', sceneBody)}`;
  const message = makeUIMessage('assistant', [text(body)]);
  const node = makeMessageNode([message]);
  const conversation = makeConversation('declared-artifacts', [node]);
  const original = JSON.stringify(conversation);
  const parsed = parseResponseContent(body, false);
  const svgSegment = parsed.find((segment) => segment.type === 'svg');
  assert.ok(svgSegment?.artifact?.snapshot !== null);
  const proof: ConversationSvgValidationProof = {
    source: {
      conversationId: conversation.id,
      nodeId: node.id,
      messageId: message.id,
      blockIndex: 0,
      sourceStart: svgSegment?.sourceStart ?? -1,
      blockId: 'diagram',
      protocol: 'amber-svg',
      version: 1,
    },
    snapshot: svgSegment?.artifact?.snapshot ?? '',
    validator: 'domparser',
    ok: true,
  };
  const index = collectConversationArtifacts(conversation, false, [proof]);
  assert.equal(index.widgets.length, 0, 'new protocols do not become legacy widgets');
  assert.equal(index.svg.length, 1);
  assert.equal(index.svg[0].source.blockIndex, 0);
  assert.equal(index.svg[0].source.blockId, 'diagram');
  assert.equal(index.svg[0].source.protocol, 'amber-svg');
  assert.equal(index.scenes.length, 1);
  assert.equal(index.scenes[0].scene.objects[0].id, 'cube');
  assert.equal(index.uiSnapshots.length, 1);
  assert.equal(index.uiSnapshots[0].interactive, false);
  assert.match(index.uiSnapshots[0].summary, /企业 CA/);
  assert.match(index.uiSnapshots[0].summary, /只读引用：weather\/temperature_celsius/);
  assert.deepEqual(index.uiSnapshots[0].definition.nodes[1].bind,
    { ref: 'weather', path: 'temperature_celsius' });
  assert.equal(index.uiSnapshots[0].definition.nodes[0].variant, 'group');
  assert.equal(index.uiSnapshots[0].definition.nodes[0].layout, 'grid');
  assert.equal(index.uiSnapshots[0].definition.nodes[0].tone, 'info');
  const exported = uiSnapshotArtifactExport(index.uiSnapshots[0]);
  assert.ok(exported);
  const definition = JSON.parse(exported.content).definition;
  assert.equal(definition.nodes[1].variant, 'heading');
  assert.equal(definition.nodes[1].tone, 'accent');
  assert.equal(JSON.stringify(conversation), original, 'collector never rewrites persisted source');

  node.messages.push(makeUIMessage('assistant', [text('unselected\n' + declared('amber-svg', 'other', svgBody))]));
  node.selectIndex = 1;
  const selected = collectConversationArtifacts(conversation, false, [proof]);
  assert.equal(selected.svg.length, 1, 'the shelf follows the selected message branch');
  assert.equal(selected.svg[0].id, 'other');
  assert.equal(selected.scenes.length, 0);
  assert.equal(selected.uiSnapshots.length, 0);
});

test('SVG requires an exact successful DOM proof and rejects stale or failed proofs', () => {
  const body = declared('amber-svg', 'diagram', svgBodyWithoutNamespace);
  const message = makeUIMessage('assistant', [text(body)]);
  const node = makeMessageNode([message]);
  const conversation = makeConversation('svg-proof', [node]);
  const segment = parseResponseContent(body, false).find((item) => item.type === 'svg');
  assert.ok(segment?.artifact?.snapshot !== null);
  const source = {
    conversationId: conversation.id,
    nodeId: node.id,
    messageId: message.id,
    blockIndex: 0,
    sourceStart: segment?.sourceStart ?? -1,
    blockId: 'diagram',
    protocol: 'amber-svg' as const,
    version: 1 as const,
  };
  const noProof = collectConversationArtifacts(conversation, false);
  assert.equal(noProof.svg.length, 1);
  assert.equal(noProof.svg[0].status, 'partial');
  const failed: ConversationSvgValidationProof = {
    source, snapshot: segment?.artifact?.snapshot ?? '', validator: 'domparser', ok: false,
  };
  const failedIndex = collectConversationArtifacts(conversation, false, [failed]);
  assert.equal(failedIndex.svg.length, 1);
  assert.equal(failedIndex.svg[0].status, 'error');
  assert.throws(() => svgArtifactExport(failedIndex.svg[0], failed), /DOM/);
  const stale: ConversationSvgValidationProof = {
    source, snapshot: (segment?.artifact?.snapshot ?? '') + ' ', validator: 'domparser', ok: true,
  };
  assert.equal(collectConversationArtifacts(conversation, false, [stale]).svg[0].status, 'partial');
  const valid: ConversationSvgValidationProof = {
    source, snapshot: segment?.artifact?.snapshot ?? '', validator: 'domparser', ok: true, canonicalSvg: svgBody,
  };
  const indexed = collectConversationArtifacts(conversation, false, [valid]);
  assert.equal(indexed.svg.length, 1);
  assert.equal(indexed.svg[0].status, 'ready');
  assert.equal(indexed.svg[0].svg, svgBodyWithoutNamespace, 'raw candidate identity remains unchanged');
  assert.equal(svgArtifactExport(indexed.svg[0], valid).content, svgBody, 'export uses host canonical XML');
  const missingCanonical: ConversationSvgValidationProof = { ...valid, canonicalSvg: undefined };
  assert.throws(() => svgArtifactExport(indexed.svg[0], missingCanonical), /规范化/);
  const oversizedCanonical: ConversationSvgValidationProof = { ...valid, canonicalSvg: 'x'.repeat(120_001) };
  assert.throws(() => svgArtifactExport(indexed.svg[0], oversizedCanonical), /过大/);
});

test('scene and UI exports carry explicit static formats', () => {
  const body = declared('amber-three', 'scene', sceneBody) + declared('amber-ui', 'controls', uiBody);
  const message = makeUIMessage('assistant', [text(body)]);
  const node = makeMessageNode([message]);
  const conversation = makeConversation('exports', [node]);
  const index = collectConversationArtifacts(conversation, false);
  const scene = sceneArtifactExport(index.scenes[0], true);
  assert.match(scene.jsonFileName, /\.json$/);
  assert.match(scene.json, /"objects"/);
  assert.match(scene.coverFileName, /-cover\.svg$/);
  assert.match(scene.cover, /<svg/);
  const ui = uiSnapshotArtifactExport(index.uiSnapshots[0]);
  assert.match(ui.fileName, /\.json$/);
  assert.match(ui.content, /"interactive": false/);
  assert.match(ui.content, /"summary"/);
  assert.match(ui.content, /"definition"/);
});

test('unknown protocol versions and invalid declared blocks remain out of the typed index', () => {
  const body = '```amber-svg v=2 id=unknown\n' + svgBody + '\n```\n' +
    '```amber-ui v=1 id=broken\n{"op":"node","id":"root","type":"column"}\n```\n';
  const message = makeUIMessage('assistant', [text(body)]);
  const conversation = makeConversation('unknown-version', [makeMessageNode([message])]);
  const index = collectConversationArtifacts(conversation, false);
  assert.equal(index.svg.length, 0);
  assert.equal(index.scenes.length, 0);
  assert.equal(index.uiSnapshots.length, 0);
});
