import { test } from 'node:test';
import assert from 'node:assert/strict';
import modelCases from './fixtures/intelligent-ui/p0-model-cases.json';
import {
  SMART_UI_MAX_LINE_BYTES,
  SMART_UI_MAX_RECORDS,
  SMART_UI_MAX_TEXT_BINDINGS,
  SMART_UI_MAX_TOTAL_BYTES,
  parseSmartUi,
  validateSmartUiNode,
} from '../main/ets/chat/smart_ui/protocol.ts';
import type { SmartUiDiagnostic, SmartUiNodeType } from '../main/ets/chat/smart_ui/protocol.ts';

interface SmartUiModelCase {
  id: string;
  category: string;
  prompt: string;
  body: string;
  expected: {
    status: string;
    rootId: string;
    nodeTypes: string[];
    stateIds: string[];
    assertions: string[];
  };
}

const cases: SmartUiModelCase[] = modelCases as unknown as SmartUiModelCase[];
const diagnosticCodes = (diagnostics: SmartUiDiagnostic[]): string[] => diagnostics.map((item) => item.code);

test('presentation tokens are type-specific, composable, and survive streaming upserts', () => {
  const root = { op: 'node', id: 'panel', type: 'column', variant: 'group', layout: 'grid', tone: 'info' };
  const metric = { op: 'node', id: 'value', parent: 'panel', type: 'text', text: '24 °C', variant: 'metric', tone: 'accent' };
  const body = [root, metric, { ...metric, variant: 'badge', tone: 'success' }, { op: 'end' }]
    .map((record) => JSON.stringify(record)).join('\n');
  const document = parseSmartUi(body, 'styled', true);
  assert.equal(document.status, 'ready');
  assert.deepEqual(document.nodes[0], { id: 'panel', parent: null, type: 'column',
    variant: 'group', layout: 'grid', tone: 'info' });
  assert.equal(document.nodes[1].variant, 'badge');
  assert.equal(document.nodes[1].tone, 'success');
  for (const variant of ['body', 'heading', 'subheading', 'caption', 'metric', 'code', 'badge',
    'hero', 'lead', 'eyebrow', 'quote']) {
    assert.ok(validateSmartUiNode({ ...metric, variant }).node, variant);
  }
  for (const layout of ['stack', 'row', 'grid']) {
    assert.ok(validateSmartUiNode({ ...root, layout }).node, layout);
  }
  for (const tone of ['neutral', 'accent', 'success', 'warning', 'info']) {
    assert.ok(validateSmartUiNode({ ...metric, tone }).node, tone);
  }
  for (const invalid of [{ ...metric, variant: 'chips' }, { ...root, variant: 'heading' },
    { ...metric, layout: 'row' }, { ...root, layout: 'masonry' }, { ...metric, tone: '#ff0000' },
    { op: 'node', id: 'input', type: 'input', state: 'note', variant: 'primary' }]) {
    assert.equal(validateSmartUiNode(invalid).node, null);
  }
});

test('all 26 presentation variants validate, round-trip to snapshots, and reject mismatched types', async () => {
  const { collectConversationArtifacts, uiSnapshotArtifactExport } = await import('../main/ets/chat/conversation_artifacts.ts');
  const { makeConversation, makeMessageNode } = await import('../main/ets/chat/conversation.ts');
  const { makeUIMessage } = await import('../main/ets/chat/message.ts');
  const catalog = {
    column: ['plain', 'group', 'outlined', 'elevated', 'callout', 'section'],
    text: ['body', 'heading', 'subheading', 'caption', 'metric', 'code', 'badge', 'hero', 'lead', 'eyebrow', 'quote'],
    choice: ['list', 'chips', 'tiles', 'segmented'],
    button: ['primary', 'secondary', 'ghost', 'pill'],
    input: ['underline'],
  };
  assert.equal(Object.values(catalog).flat().length, 26);
  for (const [type, variants] of Object.entries(catalog)) {
    for (const variant of variants) {
      const record = { op: 'node', id: 'sample', parent: 'panel', type, variant,
        ...(type === 'text' ? { text: '示例' } : {}),
        ...(type === 'choice' ? { state: 'choice', options: [{ value: 'one', label: '一' }] } : {}),
        ...(type === 'input' ? { state: 'note' } : {}),
        ...(type === 'button' ? { label: '继续', action: { type: 'draft', text: '{{note}}' } } : {}),
      };
      const body = [
        { op: 'state', id: 'choice', initial: 'one' }, { op: 'state', id: 'note', initial: '' },
        { op: 'node', id: 'panel', type: 'column' }, record, { op: 'end' },
      ].map((item) => JSON.stringify(item)).join('\n');
      const document = parseSmartUi(body, variant, true);
      assert.equal(document.status, 'ready', variant);
      assert.equal(document.nodes[1].variant, variant);
      const message = makeUIMessage('assistant', [{ type: 'text', text: `\`\`\`amber-ui v=1 id=styled\n${body}\n\`\`\``, metadata: null }]);
      const conversation = makeConversation('styles', [makeMessageNode([message])]);
      const artifact = collectConversationArtifacts(conversation, false).uiSnapshots[0];
      assert.equal(JSON.parse(uiSnapshotArtifactExport(artifact).content).definition.nodes[1].variant, variant);
      assert.equal(validateSmartUiNode({ ...record, type: 'asset', ref: 'diagram' }).node, null);
    }
  }
});

test('P0 fixture contains exactly 20 local model request cases with the planned distribution', () => {
  assert.equal(cases.length, 20);
  assert.deepEqual(
    cases.reduce((counts: Record<string, number>, item: SmartUiModelCase): Record<string, number> => {
      counts[item.category] = (counts[item.category] ?? 0) + 1;
      return counts;
    }, {}),
    { choice: 8, input: 4, asset: 4, combination: 4 },
  );
  for (const item of cases) {
    assert.ok(item.prompt.length > 0);
    assert.ok(item.body.length > 0);
    assert.equal(item.expected.status, 'ready');
    assert.ok(item.expected.assertions.length > 0);
    if (item.category === 'choice') assert.ok(item.expected.assertions.includes('choice'));
    if (item.category === 'input') assert.ok(item.expected.assertions.includes('input'));
    if (item.category === 'asset') assert.ok(item.expected.assertions.includes('asset'));
    if (item.category === 'combination') {
      assert.ok(item.expected.assertions.includes('choice'));
      assert.ok(item.expected.assertions.includes('asset'));
      assert.ok(item.expected.assertions.includes('draft'));
    }
  }
});

test('all fixed fixture cases produce a ready typed document without SVG or 3D execution', () => {
  for (const item of cases) {
    const document = parseSmartUi(item.body, item.id, true);
    assert.equal(document.status, 'ready', item.id);
    assert.equal(document.ended, true, item.id);
    assert.equal(document.rootId, item.expected.rootId, item.id);
    assert.deepEqual(document.nodes.map((node) => node.type), item.expected.nodeTypes as SmartUiNodeType[], item.id);
    assert.deepEqual(document.states.map((state) => state.id), item.expected.stateIds, item.id);
    assert.deepEqual(diagnosticCodes(document.diagnostics), [], item.id);
    assert.doesNotMatch(item.body, /<svg|three\.js|<script|javascript:/i, item.id);
  }
});

test('streaming accepts complete lines and waits for an incomplete trailing record', () => {
  const body = '{"op":"node","id":"panel","type":"column"}\n' +
    '{"op":"node","id":"title","parent":"panel","type":"text","text":"已显示"}\n' +
    '{"op":"node","id":"pending","parent":"panel","type":"text","text":"未完';
  const document = parseSmartUi(body, 'streaming', false);
  assert.equal(document.status, 'partial');
  assert.equal(document.nodes.length, 2);
  assert.deepEqual(document.nodes.map((node) => node.id), ['panel', 'title']);
  assert.ok(diagnosticCodes(document.diagnostics).includes('incomplete-line'));
});

test('terminal mode parses a valid final line without newline and rejects an invalid final line', () => {
  const ready = parseSmartUi(
    '{"op":"node","id":"panel","type":"column"}\n{"op":"end"}',
    'terminal-ready',
    true,
  );
  assert.equal(ready.status, 'ready');
  assert.equal(ready.ended, true);

  const broken = parseSmartUi(
    '{"op":"node","id":"panel","type":"column"}\n{"op":"node","id":"broken"',
    'terminal-broken',
    true,
  );
  assert.equal(broken.status, 'error');
  assert.ok(diagnosticCodes(broken.diagnostics).includes('invalid-json'));
  assert.deepEqual(broken.nodes.map((node) => node.id), ['panel']);

  const pendingAfterEnd = parseSmartUi(
    '{"op":"node","id":"panel","type":"column"}\n{"op":"end"}\n{"op":"node","id":"late"',
    'pending-after-end',
    false,
  );
  assert.equal(pendingAfterEnd.status, 'error');
  assert.ok(diagnosticCodes(pendingAfterEnd.diagnostics).includes('after-end'));

  const empty = parseSmartUi('', 'empty-terminal', true);
  assert.equal(empty.status, 'error');
  assert.ok(diagnosticCodes(empty.diagnostics).includes('missing-end'));
  assert.ok(diagnosticCodes(empty.diagnostics).includes('missing-root'));
});

test('requires parent declaration order and keeps one root', () => {
  const childFirst = parseSmartUi(
    '{"op":"node","id":"child","parent":"panel","type":"text","text":"先到"}\n' +
    '{"op":"node","id":"panel","type":"column"}\n{"op":"end"}',
    'order',
    true,
  );
  assert.equal(childFirst.status, 'error');
  assert.ok(diagnosticCodes(childFirst.diagnostics).includes('parent-before-child'));
  assert.deepEqual(childFirst.nodes.map((node) => node.id), ['panel']);

  const twoRoots = parseSmartUi(
    '{"op":"node","id":"first","type":"column"}\n' +
    '{"op":"node","id":"second","type":"column"}\n{"op":"end"}',
    'roots',
    true,
  );
  assert.equal(twoRoots.status, 'error');
  assert.ok(diagnosticCodes(twoRoots.diagnostics).includes('duplicate-root'));
  assert.equal(twoRoots.rootId, 'first');
  assert.deepEqual(twoRoots.nodes.map((node) => node.id), ['first']);

  const nonColumnParent = parseSmartUi(
    '{"op":"node","id":"root","type":"column"}\n' +
    '{"op":"node","id":"text","parent":"root","type":"text","text":"文本"}\n' +
    '{"op":"node","id":"child","parent":"text","type":"text","text":"不能挂载"}\n{"op":"end"}',
    'parent-kind',
    true,
  );
  assert.equal(nonColumnParent.status, 'error');
  assert.ok(diagnosticCodes(nonColumnParent.diagnostics).includes('parent-not-column'));
});

test('allows a compatible node upsert and rejects type or parent changes', () => {
  const document = parseSmartUi(
    '{"op":"node","id":"panel","type":"column"}\n' +
    '{"op":"node","id":"title","parent":"panel","type":"text","text":"第一版"}\n' +
    '{"op":"node","id":"title","parent":"panel","type":"text","text":"更新版"}\n' +
    '{"op":"node","id":"title","parent":"panel","type":"button","label":"危险","action":{"type":"draft","text":"x"}}\n' +
    '{"op":"node","id":"title","parent":"other","type":"text","text":"移动"}\n' +
    '{"op":"end"}',
    'upsert',
    true,
  );
  assert.equal(document.status, 'error');
  assert.equal(document.nodes.length, 2);
  assert.equal(document.nodes[1].text, '更新版');
  assert.equal(document.nodes[1].type, 'text');
  assert.ok(diagnosticCodes(document.diagnostics).includes('node-type-change'));
  assert.ok(diagnosticCodes(document.diagnostics).includes('node-parent-change'));

  const stateTypeChange = parseSmartUi(
    '{"op":"state","id":"text-state","initial":"文本"}\n' +
    '{"op":"state","id":"number-state","initial":1}\n' +
    '{"op":"node","id":"panel","type":"column"}\n' +
    '{"op":"node","id":"field","parent":"panel","type":"choice","state":"text-state","options":[{"value":"文本","label":"文本"}]}\n' +
    '{"op":"node","id":"field","parent":"panel","type":"choice","state":"number-state","options":[{"value":1,"label":"数字"}]}\n' +
    '{"op":"end"}',
    'upsert-state-type',
    true,
  );
  assert.ok(diagnosticCodes(stateTypeChange.diagnostics).includes('state-type-change'));

  const semanticUpsert = parseSmartUi(
    '{"op":"state","id":"selected","initial":"root"}\n' +
    '{"op":"node","id":"panel","type":"column"}\n' +
    '{"op":"node","id":"choice","parent":"panel","type":"choice","state":"selected","options":[{"value":"root","label":"根"}]}\n' +
    '{"op":"node","id":"choice","parent":"panel","type":"choice","state":"missing","options":[{"value":"root","label":"根"}]}\n' +
    '{"op":"end"}',
    'upsert-semantic',
    true,
  );
  assert.equal(semanticUpsert.nodes[1].state, 'selected');
  assert.ok(diagnosticCodes(semanticUpsert.diagnostics).includes('unknown-state'));
  assert.equal(semanticUpsert.diagnostics.find((item) => item.code === 'unknown-state')?.line, 4);
});

test('allows one control per state and releases the old state on control upsert', () => {
  const duplicate = parseSmartUi(
    '{"op":"state","id":"selected","initial":"a"}\n' +
    '{"op":"node","id":"panel","type":"column"}\n' +
    '{"op":"node","id":"first","parent":"panel","type":"choice","state":"selected","options":[{"value":"a","label":"A"}]}\n' +
    '{"op":"node","id":"second","parent":"panel","type":"choice","state":"selected","options":[{"value":"a","label":"A"}]}\n' +
    '{"op":"end"}',
    'duplicate-control-state',
    true,
  );
  assert.equal(duplicate.status, 'error');
  assert.deepEqual(duplicate.nodes.map((node) => node.id), ['panel', 'first']);
  assert.ok(diagnosticCodes(duplicate.diagnostics).includes('state-control-duplicate'));

  const released = parseSmartUi(
    '{"op":"state","id":"first-state","initial":"a"}\n' +
    '{"op":"state","id":"second-state","initial":"b"}\n' +
    '{"op":"node","id":"panel","type":"column"}\n' +
    '{"op":"node","id":"first","parent":"panel","type":"choice","state":"first-state","options":[{"value":"a","label":"A"}]}\n' +
    '{"op":"node","id":"first","parent":"panel","type":"choice","state":"second-state","options":[{"value":"b","label":"B"}]}\n' +
    '{"op":"node","id":"second","parent":"panel","type":"input","state":"first-state"}\n' +
    '{"op":"end"}',
    'released-control-state',
    true,
  );
  assert.equal(released.status, 'ready');
  assert.equal(released.nodes.find((node) => node.id === 'first')?.state, 'second-state');
  assert.equal(released.nodes.find((node) => node.id === 'second')?.state, 'first-state');
});

test('validates all six node kinds without accepting script-like fields', () => {
  const valid: Array<Record<string, unknown>> = [
    { op: 'node', id: 'root', type: 'column' },
    { op: 'node', id: 'text', parent: 'root', type: 'text', text: '内容' },
    { op: 'node', id: 'choice', parent: 'root', type: 'choice', state: 'selected', options: [{ value: 'a', label: 'A' }] },
    { op: 'node', id: 'input', parent: 'root', type: 'input', state: 'selected', placeholder: '输入' },
    { op: 'node', id: 'button', parent: 'root', type: 'button', label: '继续', action: { type: 'draft', text: '继续 {{selected}}' } },
    { op: 'node', id: 'asset', parent: 'root', type: 'asset', ref: 'diagram' },
  ];
  for (const record of valid) {
    const result = validateSmartUiNode(record as never);
    assert.ok(result.node, JSON.stringify(record));
    assert.deepEqual(result.diagnostics, [], JSON.stringify(record));
  }
  const script = validateSmartUiNode({
    op: 'node', id: 'bad', parent: 'root', type: 'button', label: '执行',
    action: { type: 'draft', text: 'x' }, script: 'alert(1)',
  } as never);
  assert.equal(script.node, null);
  assert.ok(diagnosticCodes(script.diagnostics).includes('unsupported-field'));
  const html = validateSmartUiNode({
    op: 'node', id: 'bad', parent: 'root', type: 'asset', ref: '<svg/>',
  } as never);
  assert.equal(html.node, null);
});

test('supports optional text bindings with a required fallback and no state coupling', () => {
  const valid = validateSmartUiNode({
    op: 'node', id: 'weather-text', parent: 'panel', type: 'text', text: '天气未知',
    bind: { ref: 'weather', path: 'temperature_celsius' },
  } as never);
  assert.ok(valid.node !== null);
  assert.deepEqual(valid.node?.bind, { ref: 'weather', path: 'temperature_celsius' });

  const withoutState = parseSmartUi(
    '{"op":"node","id":"panel","type":"column"}\n' +
    '{"op":"node","id":"weather-text","parent":"panel","type":"text","text":"天气未知","bind":{"ref":"weather","path":"condition"}}\n' +
    '{"op":"end"}',
    'text-binding',
    true,
  );
  assert.equal(withoutState.status, 'ready');
  assert.deepEqual(withoutState.nodes[1].bind, { ref: 'weather', path: 'condition' });

  const missingFallback = validateSmartUiNode({
    op: 'node', id: 'weather-text', parent: 'panel', type: 'text',
    bind: { ref: 'weather', path: 'condition' },
  } as never);
  assert.equal(missingFallback.node, null);
  assert.ok(diagnosticCodes(missingFallback.diagnostics).includes('invalid-field'));

  const unknownPath = validateSmartUiNode({
    op: 'node', id: 'weather-text', parent: 'panel', type: 'text', text: '天气未知',
    bind: { ref: 'weather', path: 'temperature' },
  } as never);
  assert.equal(unknownPath.node, null);
  assert.ok(diagnosticCodes(unknownPath.diagnostics).includes('invalid-bind-path'));

  const extraBindingKey = validateSmartUiNode({
    op: 'node', id: 'weather-text', parent: 'panel', type: 'text', text: '天气未知',
    bind: { ref: 'weather', path: 'condition', expression: 'state.x' },
  } as never);
  assert.equal(extraBindingKey.node, null);
  assert.ok(diagnosticCodes(extraBindingKey.diagnostics).includes('unsupported-field'));
});

test('accepts a bounded user-facing asset label while keeping ref stable', () => {
  const valid = validateSmartUiNode({
    op: 'node', id: 'diagram', parent: 'panel', type: 'asset', ref: 'ca-chain', label: '证书链图示',
  } as never);
  assert.ok(valid.node !== null);
  assert.equal(valid.node?.ref, 'ca-chain');
  assert.equal(valid.node?.label, '证书链图示');

  const tooLong = validateSmartUiNode({
    op: 'node', id: 'diagram', parent: 'panel', type: 'asset', ref: 'ca-chain', label: 'x'.repeat(121),
  } as never);
  assert.equal(tooLong.node, null);
  assert.ok(diagnosticCodes(tooLong.diagnostics).includes('invalid-field'));
});

test('keeps valid nodes after a malformed record and resolves state references at end', () => {
  const document = parseSmartUi(
    '{"op":"state","id":"selected","initial":"root"}\n' +
    '{"op":"node","id":"panel","type":"column"}\n' +
    '{"op":"node","id":"broken","parent":"panel","type":"text","text":}\n' +
    '{"op":"node","id":"choice","parent":"panel","type":"choice","state":"selected","options":[{"value":"root","label":"根"}]}\n' +
    '{"op":"end"}',
    'isolation',
    true,
  );
  assert.equal(document.status, 'error');
  assert.deepEqual(document.nodes.map((node) => node.id), ['panel', 'choice']);
  assert.ok(diagnosticCodes(document.diagnostics).includes('invalid-json'));

  const unresolved = parseSmartUi(
    '{"op":"node","id":"panel","type":"column"}\n' +
    '{"op":"node","id":"choice","parent":"panel","type":"choice","state":"missing","options":[{"value":"a","label":"A"}]}\n' +
    '{"op":"end"}',
    'unresolved',
    true,
  );
  assert.equal(unresolved.status, 'error');
  assert.ok(diagnosticCodes(unresolved.diagnostics).includes('state-after-dependent-node'));

  const stateAfterNode = parseSmartUi(
    '{"op":"node","id":"panel","type":"column"}\n' +
    '{"op":"node","id":"choice","parent":"panel","type":"choice","state":"later","options":[{"value":"a","label":"A"}]}\n' +
    '{"op":"state","id":"later","initial":"a"}\n{"op":"end"}',
    'state-after-node',
    true,
  );
  assert.equal(stateAfterNode.status, 'error');
  assert.deepEqual(stateAfterNode.nodes.map((node) => node.id), ['panel']);
  assert.ok(diagnosticCodes(stateAfterNode.diagnostics).includes('state-after-dependent-node'));
  assert.equal(stateAfterNode.diagnostics.find((item) => item.code === 'state-after-dependent-node')?.line, 2);

  const numericInput = parseSmartUi(
    '{"op":"state","id":"amount","initial":10}\n' +
    '{"op":"node","id":"panel","type":"column"}\n' +
    '{"op":"node","id":"amount-input","parent":"panel","type":"input","state":"amount"}\n{"op":"end"}',
    'input-type',
    true,
  );
  assert.equal(numericInput.status, 'error');
  assert.ok(diagnosticCodes(numericInput.diagnostics).includes('input-state-not-string'));

  const invalidChoiceInitial = parseSmartUi(
    '{"op":"state","id":"selected","initial":"missing"}\n' +
    '{"op":"node","id":"panel","type":"column"}\n' +
    '{"op":"node","id":"choice","parent":"panel","type":"choice","state":"selected","options":[{"value":"present","label":"存在"}]}\n{"op":"end"}',
    'choice-initial',
    true,
  );
  assert.equal(invalidChoiceInitial.status, 'error');
  assert.ok(diagnosticCodes(invalidChoiceInitial.diagnostics).includes('choice-initial-not-option'));
});

test('rejects records after end and enforces the parser budgets', () => {
  const afterEnd = parseSmartUi(
    '{"op":"node","id":"panel","type":"column"}\n{"op":"end"}\n' +
    '{"op":"node","id":"late","type":"column"}',
    'after-end',
    true,
  );
  assert.equal(afterEnd.status, 'error');
  assert.ok(diagnosticCodes(afterEnd.diagnostics).includes('after-end'));

  const longText = 'x'.repeat(SMART_UI_MAX_LINE_BYTES + 1);
  const lineLimit = parseSmartUi(longText, 'line-limit', true);
  assert.ok(diagnosticCodes(lineLimit.diagnostics).includes('line-limit'));
  const multibyteLine = parseSmartUi('中'.repeat(6000), 'multibyte-line-limit', true);
  assert.ok(diagnosticCodes(multibyteLine.diagnostics).includes('line-limit'));

  const stateValueLimit = parseSmartUi(
    `{"op":"state","id":"long","initial":"${'x'.repeat(513)}"}\n{"op":"node","id":"panel","type":"column"}\n{"op":"end"}`,
    'state-value-limit',
    true,
  );
  assert.ok(diagnosticCodes(stateValueLimit.diagnostics).includes('state-value-limit'));

  const records = Array.from({ length: SMART_UI_MAX_RECORDS + 1 }, (_value, index) =>
    JSON.stringify({ op: 'state', id: `s${index}`, initial: index }));
  const recordLimit = parseSmartUi(records.join('\n'), 'record-limit', true);
  assert.ok(diagnosticCodes(recordLimit.diagnostics).includes('record-limit'));

  const totalLimit = parseSmartUi('x'.repeat(SMART_UI_MAX_TOTAL_BYTES + 1), 'total-limit', true);
  assert.ok(diagnosticCodes(totalLimit.diagnostics).includes('total-limit'));

  const bindingLines: string[] = ['{"op":"node","id":"panel","type":"column"}'];
  for (let index: number = 0; index < SMART_UI_MAX_TEXT_BINDINGS + 1; index++) {
    bindingLines.push(JSON.stringify({
      op: 'node', id: `weather-${index}`, parent: 'panel', type: 'text', text: '天气未知',
      bind: { ref: 'weather', path: 'condition' },
    }));
  }
  bindingLines.push('{"op":"end"}');
  const bindingLimit = parseSmartUi(bindingLines.join('\n'), 'binding-limit', true);
  assert.ok(diagnosticCodes(bindingLimit.diagnostics).includes('binding-limit'));
  assert.equal(bindingLimit.nodes.filter((node) => node.bind !== undefined).length, SMART_UI_MAX_TEXT_BINDINGS);
});

test('does not mix SVG or Three.js payloads into the smart UI protocol', () => {
  const svg = parseSmartUi(
    '{"op":"node","id":"panel","type":"column"}\n' +
    '{"op":"node","id":"raw","parent":"panel","type":"asset","ref":"<svg viewBox=\\\"0 0 10 10\\\"/>"}\n{"op":"end"}',
    'no-visual-embed',
    true,
  );
  assert.equal(svg.status, 'error');
  assert.ok(diagnosticCodes(svg.diagnostics).includes('invalid-ref'));
  const three = parseSmartUi(
    '{"op":"node","id":"panel","type":"column"}\n' +
    '{"op":"node","id":"raw","parent":"panel","type":"asset","ref":"three.js:scene"}\n{"op":"end"}',
    'no-three-embed',
    true,
  );
  assert.equal(three.status, 'error');
  assert.ok(diagnosticCodes(three.diagnostics).includes('invalid-ref'));
});
