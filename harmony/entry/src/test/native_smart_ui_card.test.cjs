const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const ts = require('../../../chat/node_modules/typescript');

const root = path.resolve(__dirname, '..', 'main', 'ets', 'components');
const card = fs.readFileSync(path.join(root, 'NativeSmartUiCard.ets'), 'utf8');
const node = fs.readFileSync(path.join(root, 'NativeSmartUiNode.ets'), 'utf8');

test('native smart UI keeps state and recovery at the card boundary', () => {
  assert.match(card, /@Prop @Watch\('onDocumentChanged'\) document/);
  assert.match(card, /@Prop @Watch\('onInitialValuesChanged'\) initialValues/);
  assert.match(card, /private onInitialValuesChanged\(\): void \{[\s\S]*this\.reconcile\(\[\]\)/);
  assert.match(card, /@Prop @Watch\('onSourceKeyChanged'\) sourceKey/);
  assert.match(card, /SmartUiStateAccessor/);
  assert.match(card, /this\.values = result\.values/);
  assert.match(card, /this\.onStateChange\(stateId, nextValue\)/);
  assert.doesNotMatch(card, /AppStorage\.setOrCreate|WebView|fetch\(|httpRequest/);
});

test('native smart UI renders a fixed typed directory with bounded depth and input', () => {
  assert.match(node, /@Builder\n\s+private RenderNode\(\): void/);
  assert.match(node, /this\.currentNode\(\)\.type === 'column'/);
  assert.match(node, /this\.currentNode\(\)\.type === 'asset'/);
  assert.match(node, /this\.depth < 6/);
  assert.match(node, /\.maxLength\(512\)/);
  assert.match(node, /\.constraintSize\(\{ minHeight: 44 \}\)/);
  assert.doesNotMatch(node, /WebView|HTML|innerHTML|eval\(/i);
});

test('retained child callbacks carry only stable IDs and reread current upserts', () => {
  assert.match(card, /RootNode\(nodeId: string\)/);
  assert.match(card, /NativeSmartUiNode\(\{ document: this\.document, nodeId/);
  assert.match(node, /@Prop nodeId: string/);
  assert.match(node, /private currentNode\(\): SmartUiNode[\s\S]*const nodes: SmartUiNode\[\] = this\.document\.nodes[\s\S]*node\.id === id/);
  assert.match(node, /ForEach\(this\.childNodeIds\(\), \(childId: string\)/);
  assert.match(node, /ForEach\(this\.optionKeys\(\), \(optionKey: string\)/);
  assert.doesNotMatch(node, /ForEach\(this\.childNodesFor|ForEach\(this\.node\.options/);
});

test('typed weather projections render actual values and explicit unavailable states', () => {
  assert.match(card, /@Prop @Watch\('onProjectionsChanged'\) projections/);
  assert.match(card, /projections: this\.projections/);
  assert.match(node, /resolveWeatherBinding\(projection, binding\.ref, binding\.path\)/);
  assert.match(node, /temperature_celsius/);
  assert.match(node, /未提供/);
  assert.match(node, /数据不可用/);
});

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

test('actual boundText refuses ambiguous duplicate projection refs', () => {
  const extracted = ts.transpileModule(
    `class NativeNode { ${method(node, 'currentNode')} ${method(node, 'projectionFor')} ${method(node, 'projectionAmbiguous')} ${method(node, 'boundText')} } return NativeNode;`,
    { compilerOptions: { target: ts.ScriptTarget.ES2022 } },
  ).outputText;
  const NativeNode = new Function('EMPTY_NODE', 'resolveWeatherBinding', extracted)(
    { id: 'empty', parent: null, type: 'column' },
    () => 42,
  );
  const instance = new NativeNode();
  Object.assign(instance, {
    nodeId: 'temperature',
    document: { nodes: [{ id: 'temperature', parent: 'root', type: 'text', text: '温度',
      bind: { ref: 'weather', path: 'temperature_celsius' } }] },
    projections: [
      { ref: 'weather', status: 'ready', fields: [] },
      { ref: 'weather', status: 'ready', fields: [] },
    ],
  });
  assert.equal(instance.boundText(), '温度：数据不可用');
});


test('completion reveal commits one deferred start frame and stays off for history and reduced motion', async () => {
  const extracted = ts.transpileModule(
    `class Model { ${method(card, 'onReadyChanged')} } return Model;`,
    { compilerOptions: { target: ts.ScriptTarget.ES2022 } },
  ).outputText;
  const Model = new Function('Curve', extracted)({ EaseOut: 0 });
  const calls = [];
  const make = overrides => Object.assign(new Model(), {
    previousReady: false, ready: true, reduceMotion: false,
    revealOpacity: 1, revealScale: 1, revealTimer: -1, alive: true,
    getUIContext: () => ({ animateTo: (options, callback) => { calls.push(options); callback(); } }),
    ...overrides,
  });
  const live = make();
  live.onReadyChanged();
  assert.equal(calls.length, 0, 'start frame must be committed before animateTo');
  assert.equal(live.revealOpacity, 0.88);
  await new Promise(resolve => setTimeout(resolve, 30));
  assert.equal(calls.length, 1);
  assert.equal(calls[0].duration, 240);
  const history = make({ previousReady: true });
  history.onReadyChanged();
  await new Promise(resolve => setTimeout(resolve, 30));
  assert.equal(calls.length, 1, 'already-ready history must not replay');
  const reduced = make({ reduceMotion: true });
  reduced.onReadyChanged();
  await new Promise(resolve => setTimeout(resolve, 30));
  assert.equal(calls.length, 1, 'reduced motion must not animate');
});
