const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
const components = require('../../../chat/src/main/ets/chat/smart_ui/components.ts');
const { parseResponseContent } = require('../../../chat/src/main/ets/chat/response_content.ts');
const { createSmartUiController } = require('../../../chat/src/main/ets/chat/smart_ui/interaction.ts');

const fixturePath = path.join(__dirname, '../main/ets/platform_impl/ChatSmartUiComponentFixture.ets');
const source = fs.readFileSync(fixturePath, 'utf8');
const fixture = {};
const code = ts.transpileModule(source, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
}).outputText;
new Function('exports', 'require', code)(fixture, name => {
  assert.equal(name, '@amber/chat-domain');
  return components;
});

const readyBlocks = payload => {
  const blocks = parseResponseContent(payload, false).filter(segment => segment.type === 'ui');
  for (const block of blocks) {
    assert.equal(block.status, 'ready', `${block.id}: ${JSON.stringify(block.diagnostics)}`);
    assert.equal(block.complete, true, `${block.id} needs an ended document and a closed fence`);
    assert.equal(block.ui.status, 'ready');
    assert.ok(block.ui.nodes.length <= 64);
    assert.ok(block.ui.states.length <= 32);
  }
  return blocks;
};

test('gallery replays all 80 catalog kinds as eight parser-ready production UI blocks', () => {
  const blocks = readyBlocks(fixture.debugSmartUiComponentGalleryPayload());
  assert.equal(blocks.length, 8);
  const kinds = new Set(blocks.flatMap(block => block.ui.nodes.map(node => node.component?.kind).filter(Boolean)));
  assert.equal(components.SMART_UI_COMPONENT_KINDS.length, 80);
  assert.deepEqual([...kinds].sort(), [...components.SMART_UI_COMPONENT_KINDS].sort());
  const families = [...new Set(components.SMART_UI_COMPONENT_CATALOG.map(entry => entry.family))];
  assert.deepEqual(blocks.map(block => block.id), families.map(family => `component-${family}`));
  for (const block of blocks) {
    for (const node of block.ui.nodes) {
      if (!node.component) continue;
      if (components.componentKindRequiresState(node.component.kind)) {
        assert.ok(block.ui.states.some(state => state.id === node.state), `${node.id} must declare its actual state`);
      }
      if (components.componentKindHasOptions(node.component.kind)) assert.ok(node.options?.length > 0);
      if (['tabs', 'accordion'].includes(node.component.kind)) {
        const children = block.ui.nodes.filter(child => child.parent === node.id);
        assert.ok(children.length > 0, `${node.id} must expose real child content`);
        const initial = block.ui.states.find(state => state.id === node.state).initial;
        const selected = node.component.kind === 'tabs' ? [initial] : JSON.parse(initial);
        assert.ok(selected.every(id => children.some(child => child.id === id)));
      }
    }
  }
});

test('local decision fixture has declared form data, a required constraint, draft submission and local reset', () => {
  const blocks = readyBlocks(fixture.debugSmartUiTaskPayload());
  assert.equal(blocks.length, 1);
  const document = blocks[0].ui;
  assert.deepEqual(document.states.map(state => [state.id, state.initial]), [['plan', 'brief'], ['budget', 50], ['constraints', '']]);
  assert.deepEqual(document.nodes.filter(node => node.component).map(node => node.component.kind),
    ['goal', 'singleChoice', 'slider', 'textArea', 'progress', 'submitButton', 'resetButton']);
  const field = document.nodes.find(node => node.component?.kind === 'textArea');
  assert.equal(field.required, true);
  assert.equal(field.state, 'constraints');
  const budget = document.nodes.find(node => node.component?.kind === 'slider');
  assert.deepEqual([budget.component.min, budget.component.max, budget.component.step], [20, 100, 5]);
  const submit = document.nodes.find(node => node.component?.kind === 'submitButton');
  assert.equal(submit.action.type, 'draft');
  for (const state of document.states) assert.ok(submit.action.text.includes(`{{${state.id}}}`));
  assert.equal(document.nodes.find(node => node.component?.kind === 'resetButton').action, undefined);
});

test('gallery filled card and grid keep legal padding tokens and ordinary caption children', () => {
  const document = readyBlocks(fixture.debugSmartUiComponentGalleryPayload()).find(block => block.id === 'component-layout').ui;
  for (const [kind, tone] of [['card', 'danger'], ['grid', 'accent']]) {
    const container = document.nodes.find(node => node.id === `sample-${kind}`);
    assert.deepEqual(container.component.style, { tone, emphasis: 'filled', padding: 'small' });
    const child = document.nodes.find(node => node.parent === container.id);
    assert.equal(child.component.kind, 'caption');
    assert.equal(child.component.style, undefined, 'child must exercise inherited container text tone');
  }
});

test('streaming the task fixture through the response router never enables draft before fence completion', () => {
  const payload = fixture.debugSmartUiTaskPayload();
  for (let frame = 1; frame < 24; frame++) {
    const content = payload.slice(0, Math.ceil(payload.length * frame / 24));
    const blocks = parseResponseContent(content, true).filter(segment => segment.type === 'ui');
    assert.ok(blocks.every(block => !block.complete && !block.draftEnabled));
  }
  assert.equal(readyBlocks(payload)[0].draftEnabled, true);
});

test('task submission uses readable option labels, respects required input and resets the actual local state', () => {
  const document = readyBlocks(fixture.debugSmartUiTaskPayload())[0].ui;
  const memory = new Map();
  const controller = createSmartUiController({
    get: (_source, id) => memory.get(id),
    set: (_source, id, value) => memory.set(id, value),
  });
  const submit = document.nodes.find(node => node.component?.kind === 'submitButton');
  const initial = controller.reconcile(document, 'fixture', [], []).values;
  assert.equal(controller.draft(document, submit.action, initial), null);
  const constraints = controller.setValue(document, 'fixture', initial, 'constraints', '只有午休时间');
  assert.equal(constraints.accepted, true);
  const selected = controller.setValue(document, 'fixture', constraints.values, 'plan', 'deep');
  assert.equal(selected.accepted, true);
  const draft = controller.draft(document, submit.action, selected.values);
  assert.match(draft, /选择的本地方案：深入方案/);
  assert.match(draft, /时间预算：50 分钟/);
  assert.match(draft, /约束与偏好：只有午休时间/);
  assert.doesNotMatch(draft, /\bdeep\b/);
  const validBudget = controller.setValue(document, 'fixture', selected.values, 'budget', 60);
  assert.equal(validBudget.accepted, true);
  const rejectedBudget = controller.setValue(document, 'fixture', validBudget.values, 'budget', 57);
  assert.equal(rejectedBudget.accepted, false);
  assert.equal(rejectedBudget.values.find(state => state.id === 'budget').value, 57);
  assert.equal(memory.get('budget'), 60);
  assert.ok(rejectedBudget.invalidStateIds.includes('budget'));
  assert.equal(controller.draft(document, submit.action, rejectedBudget.values), null);
  const reset = controller.reset(document, 'fixture').values;
  assert.deepEqual(reset, initial);
  assert.equal(memory.get('constraints'), '');
  assert.equal(controller.draft(document, submit.action, reset), null);
});

test('component fixtures remain connected to the existing debug replay with explicit persistence opt-in', () => {
  const replay = fs.readFileSync(path.join(__dirname, '../main/ets/platform_impl/ChatVisualFixture.ets'), 'utf8');
  assert.match(replay, /kind === 'component-gallery' \? debugSmartUiComponentGalleryPayload\(\)/);
  assert.match(replay, /kind === 'task-ui' \? debugSmartUiTaskPayload\(\)/);
  assert.match(replay, /if \(!BuildProfile\.DEBUG\) return/);
  assert.match(replay, /replayDebugVisual\(/);
  assert.match(replay, /if \(persist\) await getChatRepository\(\)\.save\(data\.finalConversation\)/);
});
