const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const ts = require('../../../chat/node_modules/typescript');

const root = path.resolve(__dirname, '..', 'main', 'ets', 'components');
const read = name => fs.readFileSync(path.join(root, name), 'utf8');
const transpile = source => ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText;
const domainModule = { exports: {} };
new Function('exports', transpile(fs.readFileSync(path.resolve(__dirname, '../../../chat/src/main/ets/chat/smart_ui/components.ts'), 'utf8')))(domainModule.exports);
const helperSource = read('NativeSmartUiValues.ets').replace(/^import[^\n]*\n/gm, '').replace(/^export /gm, '');
const helper = new Function('decodeSmartUiSelection', transpile(helperSource) + '\nreturn { smartUiValue, smartUiString, smartUiSelection, smartUiNumericValue, smartUiDateString, smartUiSelectedDate };')(domainModule.exports.decodeSmartUiSelection);

function method(source, name) {
  const match = new RegExp(`^  private ${name}\\(`, 'm').exec(source);
  assert.ok(match, name);
  let end = source.indexOf('{', match.index) + 1;
  let depth = 1;
  for (; depth && end < source.length; end++) {
    if (source[end] === '{') depth++;
    if (source[end] === '}') depth--;
  }
  return source.slice(match.index, end);
}

function model(file, methods, deps = {}) {
  const source = read(file);
  const body = transpile(`class Model { ${methods.map(name => method(source, name)).join('\n')} } return Model;`);
  return new Function(...Object.keys(deps), body)(...Object.values(deps));
}

test('the component directory has a native family path for every protocol kind', () => {
  const renderer = read('NativeSmartUiComponent.ets');
  const inputs = read('NativeSmartUiInputs.ets');
  const data = read('NativeSmartUiData.ets');
  for (const kind of domainModule.exports.SMART_UI_COMPONENT_KINDS) {
    assert.ok((renderer + inputs + data).includes(`'${kind}'`), kind);
  }
  assert.match(renderer, /child: \(childId: string\) => void/);
  assert.match(inputs, /DatePicker\(/);
  assert.match(inputs, /TimePicker\(/);
  assert.match(inputs, /Slider\(/);
  assert.match(data, /Polyline\(/);
  assert.match(data, /scrollable\(ScrollDirection\.Horizontal\)/);
  assert.doesNotMatch(renderer + inputs + data, /WebView|eval\(|fetch\(/);
});

test('filled containers give independently themed children a normal surface in every child path', () => {
  const source = read('NativeSmartUiComponent.ets');
  const childContent = method(source, 'ChildContent');
  assert.match(childContent, /if \(this\.filled\(\)\)/);
  assert.match(childContent, /backgroundColor\(SURFACE\)/);
  assert.match(childContent, /else \{\s+this\.child\(id\)/);
  assert.match(method(source, 'Children'), /this\.ChildContent\(id\)/);
  assert.match(method(source, 'Layout'), /this\.ChildContent\(id\)/);
  assert.match(method(source, 'Actions'), /this\.ChildContent\(this\.activeTab\(\)\)/);
  assert.match(method(source, 'Actions'), /if \(this\.expanded\(id\)\) this\.ChildContent\(id\)/);
  assert.doesNotMatch(method(source, 'Layout') + method(source, 'Actions'), /this\.child\(/);
});

test('native input placeholders and slider tracks use theme-aware colors', () => {
  const source = read('NativeSmartUiInputs.ets');
  const inputs = method(source, 'TextInputControl');
  assert.equal((inputs.match(/\.placeholderColor\(this\.secondaryInk\)/g) ?? []).length, 2);
  assert.match(method(source, 'NumericInput'), /\.trackColor\(LINE\)/);
});

test('collection edits mutate the shared snapshot as JSON arrays and retain option labels', () => {
  const Model = model('NativeSmartUiInputs.ets', ['kind', 'options', 'option', 'multiple', 'selected', 'select', 'change', 'value', 'choiceLabel'], {
    smartUiOptions: node => node.options,
    smartUiSelection: helper.smartUiSelection,
    smartUiValue: helper.smartUiValue,
    encodeSmartUiSelection: domainModule.exports.encodeSmartUiSelection,
  });
  const instance = new Model();
  instance.node = { id: 'multi', state: 'choices', component: { kind: 'multiChoice' }, options: [{ label: '经济型', value: 'budget' }, { label: '舒适型', value: 'comfort' }] };
  instance.values = [{ id: 'choices', value: '["budget"]' }];
  const changes = [];
  instance.onStateChange = (id, value) => { changes.push([id, value]); instance.values = [{ id, value }]; };
  instance.select(1);
  assert.deepEqual(changes[0], ['choices', '["budget","comfort"]']);
  assert.equal(instance.selected(1), true);
  instance.select(0);
  assert.equal(instance.selected(0), false);
  assert.deepEqual(changes[1], ['choices', '["comfort"]']);
  instance.node.component.kind = 'picker';
  instance.values = [{ id: 'choices', value: 'comfort' }];
  assert.equal(instance.choiceLabel(), '舒适型');
});

test('numeric input preserves empty and invalid edits; stepped changes clamp and avoid float artifacts', () => {
  const Model = model('NativeSmartUiInputs.ets', ['kind', 'minimum', 'maximum', 'step', 'lastStepIndex', 'stepValue', 'stepMaximum', 'numeric', 'increment', 'changeSlider', 'changeNumber', 'change', 'value'], { smartUiNumericValue: helper.smartUiNumericValue, smartUiValue: helper.smartUiValue });
  const instance = new Model();
  instance.node = { state: 'price', component: { kind: 'stepper', min: 0, max: 1, step: 0.1, value: 0.2 } };
  instance.values = [{ id: 'price', value: 0.2 }];
  const changes = [];
  instance.onStateChange = (id, value) => changes.push([id, value]);
  instance.increment(1);
  assert.deepEqual(changes.pop(), ['price', 0.3]);
  instance.increment(-10);
  assert.deepEqual(changes.pop(), ['price', 0]);
  instance.changeNumber('');
  assert.deepEqual(changes.pop(), ['price', '']);
  instance.changeNumber('-');
  assert.deepEqual(changes.pop(), ['price', '-']);
  instance.changeNumber('-2.5');
  assert.deepEqual(changes.pop(), ['price', -2.5]);
  instance.node.component = { kind: 'stepper', min: 0, max: 10, step: 3 };
  instance.values = [{ id: 'price', value: 9 }];
  instance.increment(1);
  assert.deepEqual(changes.pop(), ['price', 9]);
  assert.equal(instance.stepMaximum(), 9);
  instance.changeSlider(10);
  assert.deepEqual(changes.pop(), ['price', 9]);
  instance.node.component = { kind: 'slider', min: 0, max: 1e-6, step: 1e-7 };
  instance.values = [{ id: 'price', value: 1e-7 }];
  instance.increment(1);
  assert.deepEqual(changes.pop(), ['price', 2e-7]);
  instance.changeSlider(3e-7);
  assert.deepEqual(changes.pop(), ['price', 3e-7]);
});

test('native text edits update their local prop snapshot before the owner callback and accept external resets', () => {
  const Model = model('NativeSmartUiInputs.ets', ['change', 'value', 'raw'], { smartUiValue: helper.smartUiValue, smartUiString: helper.smartUiString });
  const instance = new Model();
  instance.node = { state: 'note', component: { kind: 'textArea' } };
  const incoming = [{ id: 'note', value: '' }, { id: 'other', value: '保留' }];
  instance.values = incoming;
  const ownerEdits = [];
  instance.onStateChange = (id, value) => {
    assert.equal(instance.raw(), value, 'the next native render already sees this keystroke');
    ownerEdits.push([id, value]);
  };
  for (const value of ['o', 'of', 'off', 'offl', 'offli', 'offlin', 'offline']) instance.change(value);
  assert.equal(instance.raw(), 'offline');
  assert.deepEqual(incoming, [{ id: 'note', value: '' }, { id: 'other', value: '保留' }], 'local editing does not mutate the retained incoming snapshot');
  assert.deepEqual(instance.values[1], { id: 'other', value: '保留' });
  assert.deepEqual(ownerEdits.at(-1), ['note', 'offline']);
  instance.values = [{ id: 'note', value: '' }, { id: 'other', value: '保留' }];
  assert.equal(instance.raw(), '', 'a reset still replaces the local prop while the field is focused');
});

test('native date formatting uses local calendar fields without UTC day shifts', () => {
  const date = new Date(2026, 9, 12, 0, 5);
  assert.equal(helper.smartUiDateString(date, false), '2026-10-12');
  assert.equal(helper.smartUiDateString(date, true), '00:05');
  const selected = helper.smartUiSelectedDate('2026-10-12', false);
  assert.equal(selected.getFullYear(), 2026);
  assert.equal(selected.getMonth(), 9);
  assert.equal(selected.getDate(), 12);
  assert.equal(helper.smartUiDateString(helper.smartUiSelectedDate('23:59', true), true), '23:59');
});

test('chart geometry handles negative values, zero series and single-point lines', () => {
  const Model = model('NativeSmartUiData.ets', ['kind', 'rows', 'row', 'chartMin', 'chartMax', 'ratio', 'points', 'barWidth', 'barLeft']);
  const instance = new Model();
  instance.chartWidth = 240;
  instance.node = { component: { rows: [{ label: 'A', value: -10 }, { label: 'B', value: 10 }] } };
  assert.equal(instance.barWidth(0), '50%');
  assert.equal(instance.barLeft(0), '0%');
  assert.equal(instance.barLeft(1), '50%');
  assert.deepEqual(instance.points(), [[8, 140], [232, 12]]);
  instance.node.component.rows = [{ label: 'zero', value: 0 }];
  assert.deepEqual(instance.points(), [[120, 76]]);
  assert.equal(instance.barWidth(0), '0%');
  instance.node.component = { kind: 'list', items: ['第一项', '第二项'] };
  assert.deepEqual(instance.rows(), [{ label: '第一项' }, { label: '第二项' }]);
  instance.node.component.rows = [{ label: '显式数据', detail: '优先展示' }];
  assert.deepEqual(instance.rows(), [{ label: '显式数据', detail: '优先展示' }]);
});

test('invalid and empty user edits reach the page owner without relying on accepted persistence', () => {
  const Model = model('NativeSmartUiCard.ets', ['sameValues', 'sameStrings', 'applyReconciliation', 'updateState'], { SMART_UI_MAX_INPUT_VALUE_CHARS: 512 });
  const instance = new Model();
  instance.document = { nodes: [] };
  instance.values = [{ id: 'number', value: 10 }];
  instance.invalidStateIds = [];
  instance.stateDiagnostics = [];
  instance.controller = { setValue: (_document, _source, _current, stateId, value) => ({ accepted: false, values: [{ id: stateId, value }], invalidStateIds: [stateId], diagnostics: [] }) };
  const changes = [];
  instance.onStateChange = (id, value) => changes.push([id, value]);
  instance.onContentGrow = () => {};
  instance.updateState('number', '-');
  assert.deepEqual(changes.pop(), ['number', '-']);
  assert.deepEqual(instance.values, [{ id: 'number', value: '-' }]);
  instance.updateState('number', '');
  assert.deepEqual(changes.pop(), ['number', '']);
});

test('card ignores repeated native echoes and keeps equal owner snapshots stable', () => {
  const Model = model('NativeSmartUiCard.ets', ['sameValues', 'sameStrings', 'applyReconciliation', 'reconcile', 'onInitialValuesChanged', 'updateState'], { SMART_UI_MAX_INPUT_VALUE_CHARS: 512 });
  const instance = new Model();
  instance.document = { nodes: [] };
  instance.initialValues = [{ id: 'text', value: '' }];
  instance.values = [{ id: 'text', value: '' }];
  instance.invalidStateIds = [];
  instance.stateDiagnostics = [];
  let setterCalls = 0;
  let ownerChanges = 0;
  let growthCalls = 0;
  let valuesAssignments = 0;
  let values = instance.values;
  Object.defineProperty(instance, 'values', { get: () => values, set: next => { valuesAssignments++; values = next; } });
  instance.controller = {
    reconcile: (_document, _sourceKey, current, initial) => ({ values: (current.length ? current : initial).map(item => ({ ...item })), invalidStateIds: [], diagnostics: [] }),
    setValue: (_document, _sourceKey, _current, id, value) => { setterCalls++; return { accepted: true, values: [{ id, value }], invalidStateIds: [], diagnostics: [] }; },
  };
  instance.onStateChange = (id, value) => {
    ownerChanges++;
    instance.initialValues = [{ id, value }];
    instance.onInitialValuesChanged();
  };
  instance.onContentGrow = () => growthCalls++;
  const originalValues = instance.values;
  for (let count = 0; count < 100; count++) {
    instance.updateState('text', '');
    instance.initialValues = [{ id: 'text', value: '' }];
    instance.onInitialValuesChanged();
  }
  assert.strictEqual(instance.values, originalValues);
  assert.equal(valuesAssignments, 0);
  assert.equal(setterCalls, 0);
  assert.equal(ownerChanges, 0);
  assert.equal(growthCalls, 0);
  instance.updateState('text', '新的约束');
  assert.deepEqual(instance.values, [{ id: 'text', value: '新的约束' }]);
  assert.equal(valuesAssignments, 1);
  assert.equal(setterCalls, 1);
  assert.equal(ownerChanges, 1);
  assert.equal(growthCalls, 1);
  instance.updateState('text', '新的约束');
  assert.equal(setterCalls, 1);
  assert.equal(ownerChanges, 1);
  instance.updateState('text', '');
  assert.equal(setterCalls, 2);
  assert.equal(ownerChanges, 2);
  assert.deepEqual(instance.values, [{ id: 'text', value: '' }]);
});

test('equal card values still receive changed validation diagnostics from new definitions', () => {
  const Model = model('NativeSmartUiCard.ets', ['sameValues', 'sameStrings', 'applyReconciliation']);
  const instance = new Model();
  instance.values = [{ id: 'choice', value: 'old-option' }];
  instance.invalidStateIds = [];
  instance.stateDiagnostics = [];
  const originalValues = instance.values;
  instance.applyReconciliation({ values: [{ id: 'choice', value: 'old-option' }], invalidStateIds: ['choice'], diagnostics: [{ message: '选项已更新' }] });
  assert.strictEqual(instance.values, originalValues);
  assert.deepEqual(instance.invalidStateIds, ['choice']);
  assert.deepEqual(instance.stateDiagnostics, ['选项已更新']);
});

test('disclosure, tabs and accordion bind through the same owner and reset keeps persistence callbacks', () => {
  const source = read('NativeSmartUiComponent.ets');
  const Model = model('NativeSmartUiComponent.ets', ['node', 'component', 'kind', 'childIds', 'raw', 'change', 'activeTab', 'expanded', 'toggleChild'], {
    EMPTY_NODE: { id: 'empty' }, EMPTY_COMPONENT: { kind: 'group' },
    componentKindAllowsChildren: domainModule.exports.componentKindAllowsChildren,
    smartUiString: helper.smartUiString, smartUiSelection: helper.smartUiSelection,
    encodeSmartUiSelection: domainModule.exports.encodeSmartUiSelection,
  });
  const instance = new Model();
  instance.nodeId = 'tabs';
  instance.document = { nodes: [{ id: 'tabs', state: 'section', component: { kind: 'tabs' } }, { id: 'one', parent: 'tabs' }, { id: 'two', parent: 'tabs' }] };
  instance.values = [{ id: 'section', value: 'two' }];
  assert.equal(instance.activeTab(), 'two');
  instance.document.nodes[0].component.kind = 'accordion';
  instance.values = [{ id: 'section', value: '["one"]' }];
  instance.onStateChange = (id, value) => { instance.values = [{ id, value }]; };
  instance.toggleChild('two');
  assert.equal(instance.expanded('one'), true);
  assert.equal(instance.expanded('two'), true);
  assert.match(source, /this\.resetAction\(\).*this\.onReset\(\)/);
  assert.match(read('NativeSmartUiCard.ets'), /this\.controller\.reset\(this\.document, this\.sourceKey\)/);
  assert.match(read('NativeSmartUiCard.ets'), /for \(const item of result\.values\) this\.onStateChange\(item\.id, item\.value\)/);
});
