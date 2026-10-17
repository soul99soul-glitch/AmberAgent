import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  SMART_UI_COMPONENT_CATALOG, SMART_UI_COMPONENT_KINDS, componentKindHasOptions,
  componentKindIsMultiSelection, componentKindIsNumeric, componentKindRequiresState,
  decodeSmartUiSelection, smartUiComponentValueValid,
} from '../main/ets/chat/smart_ui/components.ts';
import type { SmartUiComponentKind } from '../main/ets/chat/smart_ui/components.ts';
import { parseSmartUi, validateSmartUiNode } from '../main/ets/chat/smart_ui/protocol.ts';
import type { JsonObject } from '../main/ets/chat/json.ts';

const recordsFor = (kind: SmartUiComponentKind): JsonObject[] => {
  const records: JsonObject[] = [{ op: 'node', id: 'root', type: 'column' }];
  const component: JsonObject = { kind, title: '原生任务组件' };
  const node: JsonObject = { op: 'node', id: 'component', parent: 'root', type: 'component', component };
  if (componentKindRequiresState(kind)) {
    const initial = componentKindIsNumeric(kind) ? 2 : ['toggle', 'disclosure'].includes(kind) ? false :
      componentKindIsMultiSelection(kind) ? '[]' : kind === 'tabs' ? 'child' :
      componentKindHasOptions(kind) ? 'a' : kind === 'datePicker' ? '2024-02-29' : kind === 'timePicker' ? '08:30' : '';
    records.unshift({ op: 'state', id: 'field', initial });
    node['state'] = 'field';
  }
  if (componentKindHasOptions(kind)) node['options'] = [{ value: 'a', label: '选择甲', color: 'blue' }, { value: 'b', label: '选择乙', detail: '说明' }];
  if (kind === 'button' || kind === 'submitButton') node['action'] = { type: 'draft', text: '基于任务继续' };
  if (kind === 'barChart' || kind === 'lineChart') component['rows'] = [{ label: '甲', value: 2 }, { label: '乙', value: 5 }];
  if (kind === 'study') component['study'] = [
    { id: 'q1', prompt: '问题一', answer: '答案一', options: [{ id: 'a', label: '甲' }, { id: 'b', label: '乙' }], correctOptionId: 'a' },
  ];
  records.push(node);
  if (kind === 'tabs' || kind === 'accordion') records.push({ op: 'node', id: 'child', parent: 'component', type: 'component', component: { kind: 'body', title: '第一页', text: '具体说明' } });
  records.push({ op: 'end' });
  return records;
};

const parseRecords = (records: JsonObject[]) => parseSmartUi(records.map((record) => JSON.stringify(record)).join('\n'), 'native-catalog', true);

test('the 81 named native kinds each survive production parsing with their distinct kind', () => {
  assert.equal(SMART_UI_COMPONENT_KINDS.length, 81);
  assert.equal(new Set(SMART_UI_COMPONENT_KINDS).size, 81);
  assert.equal(SMART_UI_COMPONENT_CATALOG.length, 81);
  for (const kind of SMART_UI_COMPONENT_KINDS) {
    const document = parseRecords(recordsFor(kind));
    assert.equal(document.status, 'ready', `${kind}: ${JSON.stringify(document.diagnostics)}`);
    assert.equal(document.nodes.find((node) => node.id === 'component')?.component?.kind, kind);
  }
});

test('component containers preserve flat order, bounded depth, immutable kind, and the legacy root', () => {
  const body = recordsFor('tabs');
  assert.equal(parseRecords(body).status, 'ready');
  const changed = body.slice(0, -1);
  changed.push({ op: 'node', id: 'component', parent: 'root', type: 'component', component: { kind: 'body', text: '替换' } });
  changed.push({ op: 'end' });
  assert.ok(parseRecords(changed).diagnostics.some((item) => item.code === 'component-kind-change'));
  assert.ok(parseRecords([{ op: 'node', id: 'root', type: 'component', component: { kind: 'column' } }, { op: 'end' }]).diagnostics.some((item) => item.code === 'root-not-column'));
  assert.ok(parseRecords([{ op: 'node', id: 'root', type: 'column' }, { op: 'node', id: 'body', parent: 'root', type: 'component', component: { kind: 'body', text: '内容' } }, { op: 'node', id: 'nested', parent: 'body', type: 'text', text: '非法子节点' }, { op: 'end' }]).diagnostics.some((item) => item.code === 'parent-not-column'));
});

test('numeric, collection, date/time defaults and native metadata reject malformed inputs', () => {
  const invalidComponents: JsonObject[] = [
    { kind: 'slider', min: 10, max: 5 }, { kind: 'stepper', step: 0 }, { kind: 'rating', max: 11 },
    { kind: 'grid', columns: 1.5 }, { kind: 'lineChart', rows: [{ label: '甲' }] },
    { kind: 'body', style: { tone: 'javascript' } }, { kind: 'body', onclick: 'run()' },
    { kind: 'image', url: 'https://user:pass@example.com/image.png' }, { kind: 'linkCard', url: 'http://example.com' },
  ];
  for (const mutation of invalidComponents) {
    const result = validateSmartUiNode({ op: 'node', id: 'native', parent: 'root', type: 'component', component: mutation });
    assert.equal(result.node, null, JSON.stringify(mutation));
  }
  const slider = recordsFor('slider');
  slider[0]['initial'] = 2.5;
  assert.ok(parseRecords(slider).diagnostics.some((item) => item.code === 'component-initial-invalid'));
  const date = recordsFor('datePicker');
  date[0]['initial'] = '2023-02-29';
  assert.ok(parseRecords(date).diagnostics.some((item) => item.code === 'component-initial-invalid'));
  const time = recordsFor('timePicker');
  time[0]['initial'] = '24:01';
  assert.ok(parseRecords(time).diagnostics.some((item) => item.code === 'component-initial-invalid'));
  const multi = recordsFor('multiChoice');
  multi[0]['initial'] = '["unknown"]';
  assert.ok(parseRecords(multi).diagnostics.some((item) => item.code === 'component-initial-invalid'));
});

test('only valid unique bounded selection strings become collections', () => {
  assert.deepEqual(decodeSmartUiSelection('["a","b"]'), ['a', 'b']);
  for (const value of ['not json', 'null', '{}', '[1]', '["a","a"]', '[""]']) assert.equal(decodeSmartUiSelection(value), null);
  const document = parseRecords(recordsFor('checklist'));
  const node = document.nodes.find((item) => item.id === 'component')!;
  assert.equal(smartUiComponentValueValid(node, '["a"]', []), true);
  assert.equal(smartUiComponentValueValid(node, '["c"]', []), false);
  node.required = true;
  assert.equal(smartUiComponentValueValid(node, '[]', []), false);
});

test('display containers require real labeled children and selections from those children', () => {
  const tabs = recordsFor('tabs');
  tabs[0]['initial'] = 'missing';
  assert.ok(parseRecords(tabs).diagnostics.some((item) => item.code === 'component-initial-invalid'));
  const accordion = recordsFor('accordion');
  accordion[0]['initial'] = '["missing"]';
  assert.ok(parseRecords(accordion).diagnostics.some((item) => item.code === 'component-initial-invalid'));
  const missingLabel = recordsFor('tabs');
  const child = missingLabel[missingLabel.length - 2]['component'] as JsonObject;
  delete child['title'];
  assert.ok(parseRecords(missingLabel).diagnostics.some((item) => item.code === 'component-child-label'));
});

test('multi-selection option IDs match the bounded collection codec', () => {
  for (const value of ['', 'a'.repeat(121), 2, false]) {
    const records = recordsFor('multiChoice');
    const component = records.find((record) => record['type'] === 'component')!;
    component['options'] = [{ value, label: '不可选项' }];
    assert.ok(parseRecords(records).diagnostics.some((item) => item.code === 'multi-option-not-string'));
  }
});

test('legacy and native buttons cannot leak presentation-only state into task drafts', () => {
  for (const kind of ['disclosure', 'tabs', 'accordion'] as SmartUiComponentKind[]) {
    for (const legacy of [true, false]) {
      const records = recordsFor(kind).slice(0, -1);
      const button: JsonObject = { op: 'node', id: 'continue', parent: 'root', type: legacy ? 'button' : 'component', label: '继续', action: { type: 'draft', text: '{{field}}' } };
      if (!legacy) button['component'] = { kind: 'submitButton' };
      records.push(button, { op: 'end' });
      assert.ok(parseRecords(records).diagnostics.some((item) => item.code === 'presentation-state-in-action'), `${kind}/${legacy}`);
    }
  }
});

test('study cards validate items, options and the correct option id', () => {
  const valid = parseRecords([
    { op: 'node', id: 'root', type: 'column' },
    { op: 'node', id: 'study', parent: 'root', type: 'component', component: {
      kind: 'study', study: [
        { id: 'q1', prompt: '问题', answer: '答案', options: [{ id: 'a', label: '甲' }, { id: 'b', label: '乙' }], correctOptionId: 'a' },
        { id: 'q2', prompt: '翻卡问题', answer: '翻卡答案' },
      ],
    } },
    { op: 'end' },
  ]);
  assert.equal(valid.status, 'ready', JSON.stringify(valid.diagnostics));
  const missing = parseRecords([
    { op: 'node', id: 'root', type: 'column' },
    { op: 'node', id: 'study', parent: 'root', type: 'component', component: { kind: 'study' } },
    { op: 'end' },
  ]);
  assert.ok(missing.diagnostics.some((item) => item.message.includes('study')));
  const badCorrect = parseRecords([
    { op: 'node', id: 'root', type: 'column' },
    { op: 'node', id: 'study', parent: 'root', type: 'component', component: {
      kind: 'study', study: [
        { id: 'q1', prompt: '问题', answer: '答案', options: [{ id: 'a', label: '甲' }, { id: 'b', label: '乙' }], correctOptionId: 'x' },
      ],
    } },
    { op: 'end' },
  ]);
  assert.ok(badCorrect.diagnostics.some((item) => item.message.includes('correctOptionId')));
});

test('valueFieldId links chart rows and metric/progress to declared number states only', () => {
  const linked = parseRecords([
    { op: 'state', id: 'budget', initial: 50 },
    { op: 'state', id: 'label', initial: 'x' },
    { op: 'node', id: 'root', type: 'column' },
    { op: 'node', id: 'chart', parent: 'root', type: 'component', component: {
      kind: 'barChart', rows: [{ label: '预算', valueFieldId: 'budget' }],
    } },
    { op: 'node', id: 'metric', parent: 'root', type: 'component', component: {
      kind: 'metric', valueFieldId: 'budget', text: '分钟',
    } },
    { op: 'end' },
  ]);
  assert.equal(linked.status, 'ready', JSON.stringify(linked.diagnostics));
  const wrongType = parseRecords([
    { op: 'state', id: 'label', initial: 'x' },
    { op: 'node', id: 'root', type: 'column' },
    { op: 'node', id: 'chart', parent: 'root', type: 'component', component: {
      kind: 'barChart', rows: [{ label: '预算', valueFieldId: 'label' }],
    } },
    { op: 'end' },
  ]);
  assert.ok(wrongType.diagnostics.some((item) => item.code === 'state-type-mismatch'));
  const unknown = parseRecords([
    { op: 'node', id: 'root', type: 'column' },
    { op: 'node', id: 'metric', parent: 'root', type: 'component', component: {
      kind: 'metric', valueFieldId: 'missing',
    } },
    { op: 'end' },
  ]);
  assert.ok(unknown.diagnostics.some((item) => item.code === 'unknown-state' || item.code === 'state-after-dependent-node'));
});

test('study progress must bind a string state, and required-state components still reject missing state', () => {
  const wrongStudyState = parseRecords([
    { op: 'state', id: 'progress', initial: 1 },
    { op: 'node', id: 'root', type: 'column' },
    { op: 'node', id: 'study', parent: 'root', type: 'component', state: 'progress', component: {
      kind: 'study', study: [{ id: 'q1', prompt: '问题', answer: '答案' }],
    } },
    { op: 'end' },
  ]);
  assert.ok(wrongStudyState.diagnostics.some((item) => item.code === 'component-state-type'));
  const missingSliderState = parseRecords([
    { op: 'node', id: 'root', type: 'column' },
    { op: 'node', id: 'slider', parent: 'root', type: 'component', component: { kind: 'slider' } },
    { op: 'end' },
  ]);
  assert.ok(missingSliderState.diagnostics.some((item) => item.message.includes('component.state')));
});

test('valueFieldId is rejected on components that do not render a linked value', () => {
  const document = parseRecords([
    { op: 'state', id: 'budget', initial: 50 },
    { op: 'node', id: 'root', type: 'column' },
    { op: 'node', id: 'body', parent: 'root', type: 'component', component: {
      kind: 'body', text: '正文', valueFieldId: 'budget',
    } },
    { op: 'end' },
  ]);
  assert.ok(document.diagnostics.some((item) => item.message.includes('valueFieldId')));
});
