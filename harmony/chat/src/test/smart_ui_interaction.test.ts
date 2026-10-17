import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  SMART_UI_MAX_DRAFT_PROMPT_CHARS,
  SMART_UI_MAX_INPUT_VALUE_CHARS,
  appendSmartUiDraft,
  createSmartUiController,
} from '../main/ets/chat/smart_ui/interaction.ts';
import type { SmartUiScalar } from '../main/ets/chat/smart_ui/protocol.ts';
import { parseSmartUi } from '../main/ets/chat/smart_ui/protocol.ts';

const document = parseSmartUi(
  '{"op":"state","id":"selected","initial":"root"}\n' +
  '{"op":"state","id":"note","initial":""}\n' +
  '{"op":"node","id":"panel","type":"column"}\n' +
  '{"op":"node","id":"choice","parent":"panel","type":"choice","state":"selected","options":[' +
  '{"value":"root","label":"根证书"},{"value":"server","label":"服务器证书"}]}\n' +
  '{"op":"node","id":"detail","parent":"panel","type":"text","text":"详情","visibleWhen":{"state":"selected","equals":"root"}}\n' +
  '{"op":"node","id":"input","parent":"panel","type":"input","state":"note","placeholder":"备注"}\n' +
  '{"op":"node","id":"draft","parent":"panel","type":"button","label":"继续解释",' +
  '"action":{"type":"draft","text":"请继续解释：{{selected}}；{{note}}"}}\n' +
  '{"op":"end"}',
  'ca-controls',
  true,
);

interface MemoryState {
  values: Map<string, SmartUiScalar>;
}

const controllerFor = (memory: MemoryState = { values: new Map<string, SmartUiScalar>() }) => createSmartUiController({
  get: (_sourceKey: string, stateId: string): SmartUiScalar | undefined => memory.values.get(stateId),
  set: (_sourceKey: string, stateId: string, value: SmartUiScalar): void => { memory.values.set(stateId, value); },
});

test('reconcile prefers current values, then page store, then recovery values and defaults', () => {
  const memory: MemoryState = { values: new Map<string, SmartUiScalar>([['selected', 'server']]) };
  const controller = controllerFor(memory);
  const result = controller.reconcile(document, 'conversation/message/ca-controls', [], [
    { id: 'selected', value: 'root' }, { id: 'note', value: '恢复内容' },
  ]);
  assert.deepEqual(result.values, [
    { id: 'selected', value: 'server' }, { id: 'note', value: '恢复内容' },
  ]);
  const current = controller.reconcile(document, 'conversation/message/ca-controls', [
    { id: 'selected', value: 'root' }, { id: 'note', value: '当前内容' },
  ], [{ id: 'note', value: '旧内容' }]);
  assert.deepEqual(current.values, [
    { id: 'selected', value: 'root' }, { id: 'note', value: '当前内容' },
  ]);
});

test('preserves invalid model state without replacing it and disables the derived draft', () => {
  const controller = controllerFor();
  const result = controller.reconcile(document, 'source', [], [{ id: 'selected', value: 'deleted-option' }]);
  assert.deepEqual(result.values[0], { id: 'selected', value: 'deleted-option' });
  assert.deepEqual(result.invalidStateIds, ['selected']);
  assert.equal(controller.draft(document, document.nodes[4]?.action, result.values), null);
});

test('setValue accepts only the declared type and choice options', () => {
  const memory: MemoryState = { values: new Map<string, SmartUiScalar>() };
  const controller = controllerFor(memory);
  const initial = controller.reconcile(document, 'source', [], []);
  const accepted = controller.setValue(document, 'source', initial.values, 'selected', 'server');
  assert.equal(accepted.accepted, true);
  assert.equal(memory.values.get('selected'), 'server');
  const rejected = controller.setValue(document, 'source', accepted.values, 'selected', 'missing');
  assert.equal(rejected.accepted, false);
  assert.equal(rejected.values.find((item) => item.id === 'selected')?.value, 'missing');
  assert.ok(rejected.invalidStateIds.includes('selected'));
});

test('visibleWhen compares literal scalar types and draft prefers option labels', () => {
  const controller = controllerFor();
  const values = [{ id: 'selected', value: 'root' as SmartUiScalar }, { id: 'note', value: '请看链路' as SmartUiScalar }];
  assert.equal(controller.visibleWhen({ state: 'selected', equals: 'root' }, values), true);
  assert.equal(controller.visibleWhen({ state: 'selected', equals: 'server' }, values), false);
  assert.equal(controller.visibleWhen({ state: 'selected', equals: 1 }, values), false);
  assert.equal(controller.draft(document, document.nodes[4]?.action, values), '请继续解释：根证书；请看链路');
});

test('input values are capped at the state boundary and prompt output has a final limit', () => {
  const controller = controllerFor();
  assert.equal(controller.isValueValid(document, 'note', 'x'.repeat(SMART_UI_MAX_INPUT_VALUE_CHARS)), true);
  assert.equal(controller.isValueValid(document, 'note', 'x'.repeat(SMART_UI_MAX_INPUT_VALUE_CHARS + 1)), false);
  const longAction = { type: 'draft' as const, text: `{{note}}${'y'.repeat(SMART_UI_MAX_DRAFT_PROMPT_CHARS)}` };
  const result = controller.draft(document, longAction, [{ id: 'note', value: 'x' }]);
  assert.equal(result?.length, SMART_UI_MAX_DRAFT_PROMPT_CHARS);
});

test('appendSmartUiDraft preserves input and does not duplicate an identical tail', () => {
  assert.equal(appendSmartUiDraft('', '继续解释'), '继续解释');
  assert.equal(appendSmartUiDraft('原输入', '继续解释'), '原输入\n\n继续解释');
  assert.equal(appendSmartUiDraft('原输入\n', '继续解释'), '原输入\n\n继续解释');
  assert.equal(appendSmartUiDraft('原输入\n\n继续解释', '继续解释'), '原输入\n\n继续解释');
  assert.equal(appendSmartUiDraft('原输入\n\n继续解释  ', '继续解释'), '原输入\n\n继续解释  ');
  assert.equal(appendSmartUiDraft('已有背景继续解释', '继续解释'), '已有背景继续解释\n\n继续解释');
});

const richTask = () => parseSmartUi([
  { op: 'state', id: 'budget', initial: 10 },
  { op: 'state', id: 'enabled', initial: false },
  { op: 'state', id: 'selected', initial: '[]' },
  { op: 'state', id: 'note', initial: '' },
  { op: 'state', id: 'expanded', initial: false },
  { op: 'node', id: 'root', type: 'column' },
  { op: 'node', id: 'budgetNode', parent: 'root', type: 'component', state: 'budget', component: { kind: 'slider', title: '预算', min: 0, max: 20, step: 2 } },
  { op: 'node', id: 'switchNode', parent: 'root', type: 'component', state: 'enabled', component: { kind: 'toggle', title: '附说明' } },
  { op: 'node', id: 'selectionNode', parent: 'root', type: 'component', state: 'selected', required: true, options: [{ value: 'a', label: '旅行' }, { value: 'b', label: '阅读' }], component: { kind: 'multiChoice', title: '偏好' } },
  { op: 'node', id: 'noteNode', parent: 'root', type: 'component', state: 'note', required: true, component: { kind: 'textArea', title: '补充需求' } },
  { op: 'node', id: 'disclosureNode', parent: 'root', type: 'component', state: 'expanded', component: { kind: 'disclosure', title: '展开说明' } },
  { op: 'node', id: 'submit', parent: 'root', type: 'component', component: { kind: 'submitButton', title: '继续' }, action: { type: 'draft', text: '偏好{{selected}}，预算{{budget}}，附说明{{enabled}}。' } },
  { op: 'node', id: 'reset', parent: 'root', type: 'component', component: { kind: 'resetButton', title: '重置' } },
  { op: 'end' },
].map((record) => JSON.stringify(record)).join('\n'), 'trip-task', true);

test('native changes preserve exact number, boolean and collection values with readable drafts', () => {
  const doc = richTask();
  assert.equal(doc.status, 'ready', JSON.stringify(doc.diagnostics));
  const memory: MemoryState = { values: new Map() };
  const controller = controllerFor(memory);
  let values = controller.reconcile(doc, 'source', [], []).values;
  const action = doc.nodes.find((node) => node.id === 'submit')?.action;
  assert.equal(controller.draft(doc, action, values), null, 'unanswered required inputs block submission');
  for (const [id, value] of [['budget', 14], ['enabled', true], ['selected', '["a","b"]'], ['note', '步行优先']] as Array<[string, SmartUiScalar]>) {
    const changed = controller.setValue(doc, 'source', values, id, value);
    assert.equal(changed.accepted, true, id);
    values = changed.values;
  }
  assert.equal(memory.values.get('budget'), 14);
  assert.equal(memory.values.get('enabled'), true);
  assert.equal(memory.values.get('selected'), '["a","b"]');
  assert.equal(controller.draft(doc, action, values), '偏好旅行、阅读，预算14，附说明开启。');
  for (const [id, value] of [['budget', 13], ['budget', Infinity], ['enabled', 'true'], ['selected', '["missing"]'], ['selected', '["a","a"]']] as Array<[string, SmartUiScalar]>) {
    assert.equal(controller.setValue(doc, 'source', values, id, value).accepted, false, `${id}: ${value}`);
  }
});

test('clearing required fields replaces the accessor value and blocks only submission', () => {
  const doc = richTask();
  const memory: MemoryState = { values: new Map([['selected', '["a"]'], ['note', '已填写']]) };
  const controller = controllerFor(memory);
  const initial = controller.reconcile(doc, 'source', [], []).values;
  const cleared = controller.setValue(doc, 'source', initial, 'note', '');
  assert.equal(cleared.accepted, true);
  assert.equal(memory.values.get('note'), '');
  assert.deepEqual(cleared.invalidStateIds, []);
  assert.equal(controller.draft(doc, doc.nodes.find((node) => node.id === 'submit')?.action, cleared.values), null);
  const selection = controller.setValue(doc, 'source', cleared.values, 'selected', '[]');
  assert.equal(selection.accepted, true);
  assert.equal(memory.values.get('selected'), '[]');
  assert.equal(controller.reconcile(doc, 'source', [], []).values.find((value) => value.id === 'note')?.value, '');
  assert.equal(controller.reconcile(doc, 'source', [], []).values.find((value) => value.id === 'selected')?.value, '[]');
});

test('local reset restores all initial values and keeps presentation state out of drafts', () => {
  const doc = richTask();
  const memory: MemoryState = { values: new Map<string, SmartUiScalar>([['budget', 18], ['note', '填写过'], ['expanded', true]]) };
  const controller = controllerFor(memory);
  const reset = controller.reset(doc, 'source');
  assert.deepEqual(reset.values, doc.states.map((state) => ({ id: state.id, value: state.initial })));
  assert.equal(memory.values.get('note'), '');
  assert.equal(memory.values.get('expanded'), false);
  assert.equal(controller.draft(doc, { type: 'reset' }, reset.values), null);
  assert.equal(controller.draft(doc, { type: 'draft', text: '{{expanded}}' }, reset.values), null);
});
