import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseSmartUi } from '../main/ets/chat/smart_ui/protocol.ts';
import type { SmartUiDocument } from '../main/ets/chat/smart_ui/protocol.ts';
import {
  SMART_UI_MAX_CONTINUATION_SNAPSHOT_CHARS,
  appendSmartUiContinuation, isExplicitSmartUiContinuation, isSmartUiTaskState,
  latestSmartUiContinuationSource, smartUiContinuationSnapshot, smartUiContinuationDocumentForSource,
} from '../main/ets/chat/smart_ui/continuation.ts';
import type { SmartUiContinuationCandidate } from '../main/ets/chat/smart_ui/continuation.ts';
import { makeAssistantMessage } from '../main/ets/chat/message.ts';
import { smartUiDefinitionFingerprint } from '../main/ets/chat/smart_ui/persistence.ts';
import { appendSmartUiDraft } from '../main/ets/chat/smart_ui/interaction.ts';

const plan = (): SmartUiDocument => parseSmartUi([
  { op: 'state', id: 'style', initial: 'slow' },
  { op: 'state', id: 'note', initial: '' },
  { op: 'state', id: 'unused', initial: 'do not send' },
  { op: 'node', id: 'root', type: 'column' },
  { op: 'node', id: 'title', parent: 'root', type: 'text', variant: 'heading', text: '旅行方案' },
  { op: 'node', id: 'pace', parent: 'root', type: 'choice', state: 'style', label: '旅行节奏',
    options: [{ value: 'slow', label: '轻松休闲' }, { value: 'fast', label: '紧凑充实' }] },
  { op: 'node', id: 'notes', parent: 'root', type: 'input', state: 'note', placeholder: '同行需求' },
  { op: 'end' },
].map((record) => JSON.stringify(record)).join('\n'), 'trip-plan', true);

test('continuation snapshot sends current display labels, input, target and block identity', () => {
  const document = plan();
  assert.equal(document.status, 'ready', JSON.stringify(document.diagnostics));
  const snapshot = smartUiContinuationSnapshot(document, [
    { id: 'style', value: 'fast' }, { id: 'note', value: '带孩子，避开夜间活动' },
  ]);
  assert.ok(snapshot?.includes('来源：trip-plan'));
  assert.ok(snapshot?.includes('旅行方案'));
  assert.ok(snapshot?.includes('旅行节奏'));
  assert.ok(snapshot?.includes('紧凑充实'));
  assert.ok(snapshot?.includes('同行需求'));
  assert.ok(snapshot?.includes('带孩子，避开夜间活动'));
  assert.ok(!snapshot?.includes('do not send'));
  assert.ok(!snapshot?.includes('"fast"'));
});

test('defaults complete untouched visible fields, stale values and partial documents are refused', () => {
  const document = plan();
  assert.ok(smartUiContinuationSnapshot(document, [{ id: 'note', value: '需要电梯' }])?.includes('轻松休闲'));
  assert.equal(smartUiContinuationSnapshot(document, [{ id: 'style', value: 'deleted-option' }]), null);
  assert.equal(smartUiContinuationSnapshot({ ...document, status: 'partial', ended: false }, []), null);
});

test('snapshot omits conditionally hidden controls, including a hidden ancestor', () => {
  const document = plan();
  document.nodes.splice(document.nodes.length - 1, 0, {
    id: 'hidden', type: 'column', parent: 'root', visibleWhen: { state: 'style', equals: 'slow' },
  }, { id: 'hiddenInput', type: 'input', parent: 'hidden', state: 'unused', placeholder: '隐藏字段' });
  const snapshot = smartUiContinuationSnapshot(document, [{ id: 'style', value: 'fast' }]);
  assert.ok(snapshot);
  assert.ok(!snapshot.includes('隐藏字段'));
  assert.ok(!snapshot.includes('do not send'));
});

test('component task values use labels while disclosure, tabs and accordion state stay local', () => {
  const document = parseSmartUi([
    { op: 'state', id: 'features', initial: '["a"]' },
    { op: 'state', id: 'online', initial: false },
    { op: 'state', id: 'open', initial: true },
    { op: 'state', id: 'tab', initial: 'tabBody' },
    { op: 'state', id: 'expanded', initial: '["accordionBody"]' },
    { op: 'node', id: 'root', type: 'column' },
    { op: 'node', id: 'featurePicker', parent: 'root', type: 'component', state: 'features',
      label: '功能', options: [{ value: 'a', label: '自动分类' }, { value: 'b', label: '离线检索' }],
      component: { kind: 'multiChoice' } },
    { op: 'node', id: 'network', parent: 'root', type: 'component', state: 'online', label: '网络访问',
      component: { kind: 'toggle' } },
    { op: 'node', id: 'details', parent: 'root', type: 'component', state: 'open', component: { kind: 'disclosure', title: '查看详情' } },
    { op: 'node', id: 'tabs', parent: 'root', type: 'component', state: 'tab', component: { kind: 'tabs', title: '页签' } },
    { op: 'node', id: 'tabBody', parent: 'tabs', type: 'component', component: { kind: 'group', title: '第一部分' } },
    { op: 'node', id: 'accordion', parent: 'root', type: 'component', state: 'expanded', component: { kind: 'accordion', title: '折叠部分' } },
    { op: 'node', id: 'accordionBody', parent: 'accordion', type: 'component', component: { kind: 'group', title: '第二部分' } },
    { op: 'end' },
  ].map((record) => JSON.stringify(record)).join('\n'), 'work-plan', true);
  assert.equal(document.status, 'ready', JSON.stringify(document.diagnostics));
  assert.equal(isSmartUiTaskState(document, 'features'), true);
  assert.equal(isSmartUiTaskState(document, 'open'), false);
  assert.equal(isSmartUiTaskState(document, 'tab'), false);
  const snapshot = smartUiContinuationSnapshot(document, [{ id: 'features', value: '["b","a"]' }, { id: 'online', value: true }]);
  assert.ok(snapshot?.includes('离线检索、自动分类'));
  assert.ok(snapshot?.includes('开启'));
  for (const presentation of ['查看详情', '页签', '折叠部分', 'tabBody', 'accordionBody']) {
    assert.ok(!snapshot?.includes(presentation));
  }
});

test('required blank fields fail and oversized snapshots never silently lose a selected field', () => {
  const document = plan();
  const note = document.nodes.find((node) => node.state === 'note');
  assert.ok(note);
  note.required = true;
  assert.equal(smartUiContinuationSnapshot(document, []), null);
  note.required = false;
  const fields = Array.from({ length: 10 }, (_, index) => ({ id: `detail${index}`, initial: 'x'.repeat(512), valueType: 'string' as const }));
  document.states.push(...fields);
  document.nodes.push(...fields.map((state) => ({ id: `${state.id}Input`, type: 'input' as const, parent: 'root', state: state.id })));
  assert.equal(smartUiContinuationSnapshot(document, []), null);
  const small = smartUiContinuationSnapshot(plan(), []);
  assert.ok(small && small.length <= SMART_UI_MAX_CONTINUATION_SNAPSHOT_CHARS);
});

test('explicit continuation uses a narrow whole-message intent and leaves ordinary questions alone', () => {
  for (const text of ['按这个继续', '按当前选择继续。', '根据当前选择继续！', 'continue with my selections']) {
    assert.equal(isExplicitSmartUiContinuation(text), true, text);
  }
  for (const text of ['继续', '这个是什么意思', '“按这个继续”是怎么用的？', '按这个继续，但先说它的缺点', 'continue?']) {
    assert.equal(isExplicitSmartUiContinuation(text), false, text);
  }
});

const candidate = (sourceKey: string, messageId: string, lastInteraction: number,
  conversationId: string = 'conversation'): SmartUiContinuationCandidate => ({
  sourceKey, messageId, lastInteraction, conversationId, ready: true, streaming: false,
});

test('latest source requires actual interaction in the current conversation and selected branch', () => {
  const candidates = [candidate('default', 'm1', 0), candidate('selected', 'm2', 1),
    candidate('other-branch', 'm3', 8), candidate('other-conversation', 'm2', 9, 'other')];
  assert.equal(latestSmartUiContinuationSource(candidates, 'conversation', ['m1', 'm2']), 'selected');
  assert.equal(latestSmartUiContinuationSource(candidates, 'conversation', ['m1', 'm3']), 'other-branch');
  assert.equal(latestSmartUiContinuationSource(candidates, 'conversation', ['m1']), null);
  assert.equal(latestSmartUiContinuationSource([{ ...candidate('stream', 'm1', 10), streaming: true }], 'conversation', ['m1']), 'stream');
  assert.equal(latestSmartUiContinuationSource([{ ...candidate('partial', 'm1', 10), ready: false }], 'conversation', ['m1']), null);
  assert.equal(latestSmartUiContinuationSource([candidate('a', 'm1', 1), candidate('b', 'm1', 1)], 'conversation', ['m1']), null);
});

test('button draft freezes its snapshot, preserves composer text, and does not duplicate the same tail', () => {
  const original = '我的原文\n';
  const snapshot = smartUiContinuationSnapshot(plan(), [{ id: 'style', value: 'fast' }]);
  const draft = appendSmartUiContinuation('请继续细化这份方案', snapshot);
  const composer = appendSmartUiDraft(original, draft);
  assert.ok(composer.startsWith(original));
  assert.ok(composer.includes('紧凑充实'));
  assert.equal(appendSmartUiContinuation(draft, snapshot), draft);
  assert.equal(appendSmartUiDraft(composer, draft), composer);
  const changed = smartUiContinuationSnapshot(plan(), [{ id: 'style', value: 'slow' }]);
  assert.notEqual(changed, snapshot);
  assert.ok(composer.includes('紧凑充实'));
  assert.ok(!composer.includes('轻松休闲'));
});

test('source verification re-reads the current grouped message and detects edits, missing blocks and different message owners', () => {
  const body = [
    { op: 'state', id: 'selection', initial: 'a' },
    { op: 'node', id: 'root', type: 'column' },
    { op: 'node', id: 'choose', parent: 'root', type: 'choice', state: 'selection', options: [{ value: 'a', label: '方案 A' }] },
    { op: 'end' },
  ].map((record) => JSON.stringify(record)).join('\n');
  const text = '```amber-ui v=1 id=plan\n' + body + '\n```';
  const message = makeAssistantMessage(text);
  const source = { conversationId: 'c', nodeId: 'n', messageId: message.id, blockId: 'plan', blockIndex: 0, sourceStart: 0 };
  const original = smartUiContinuationDocumentForSource(message, source);
  assert.ok(original);
  const fingerprint = smartUiDefinitionFingerprint(original);
  // Rendering groups consecutive text parts; the source offset is relative to that group.
  message.parts = [{ type: 'text', text: text.slice(0, 90), metadata: null },
    { type: 'text', text: text.slice(90), metadata: null }];
  const combined = smartUiContinuationDocumentForSource(message, source);
  assert.ok(combined);
  assert.equal(smartUiDefinitionFingerprint(combined), fingerprint);
  message.parts = [{ type: 'text', text: text.replace('方案 A', '新方案'), metadata: null }];
  const edited = smartUiContinuationDocumentForSource(message, source);
  assert.ok(edited);
  assert.notEqual(smartUiDefinitionFingerprint(edited), fingerprint);
  assert.equal(smartUiContinuationDocumentForSource(message, { ...source, messageId: 'other' }), null);
  assert.equal(smartUiContinuationDocumentForSource(message, { ...source, blockIndex: 2 }), null);
  assert.equal(smartUiContinuationDocumentForSource(message, { ...source, sourceStart: 8 }), null);
  message.parts = [{ type: 'text', text: '已移除卡片', metadata: null }];
  assert.equal(smartUiContinuationDocumentForSource(message, source), null);
});


test('a complete UI block stays usable while later content in the same message is still streaming', () => {
  const text = '```amber-ui v=1 id=finished\n' +
    '{"op":"node","id":"root","type":"column"}\n{"op":"end"}\n```\n' +
    '后续正文仍在输出\n```amber-ui v=1 id=loading\n{"op":"node","id":"root","type":"column"}\n';
  const message = makeAssistantMessage(text);
  const source = { conversationId: 'c', nodeId: '', messageId: message.id, blockId: 'finished', blockIndex: 0, sourceStart: 0 };
  const complete = smartUiContinuationDocumentForSource(message, source);
  assert.ok(complete);
  assert.equal(complete.status, 'ready');
  assert.ok(smartUiContinuationSnapshot(complete, []));
  assert.equal(latestSmartUiContinuationSource([{ sourceKey: 'live-card', conversationId: 'c', messageId: message.id,
    ready: true, streaming: true, lastInteraction: 1 }], 'c', [message.id]), 'live-card');
});

test('continuation rejects a message-level duplicate ID across tool-separated text groups and types', () => {
  const ui = '```amber-ui v=1 id=shared\n{"op":"node","id":"root","type":"column"}\n{"op":"end"}\n```';
  const other = [ui, '```amber-svg v=1 id=shared\n<svg/>\n```',
    '```amber-three v=1 id=shared\n{"objects":[]}\n```'];
  for (const duplicate of other) {
    const message = makeAssistantMessage(ui);
    message.parts.push({ type: 'tool', toolCallId: 't', toolName: 'search', input: '{}', output: [],
      approvalState: { type: 'auto' }, metadata: null }, { type: 'text', text: duplicate, metadata: null });
    const source = { conversationId: 'c', nodeId: 'n', messageId: message.id,
      blockId: 'shared', blockIndex: 0, sourceStart: 0 };
    assert.equal(smartUiContinuationDocumentForSource(message, source), null);
    message.parts[2] = { type: 'text', text: duplicate.replace('id=shared', 'id=unique'), metadata: null };
    assert.equal(smartUiContinuationDocumentForSource(message, source)?.status, 'ready');
  }
});
