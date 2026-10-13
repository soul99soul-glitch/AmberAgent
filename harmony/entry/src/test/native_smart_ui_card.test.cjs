const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const ts = require('../../../chat/node_modules/typescript');

const root = path.resolve(__dirname, '..', 'main', 'ets', 'components');
const card = fs.readFileSync(path.join(root, 'NativeSmartUiCard.ets'), 'utf8');
const node = fs.readFileSync(path.join(root, 'NativeSmartUiNode.ets'), 'utf8');
const overlay = fs.readFileSync(path.join(root, 'NativeUiGenerationOverlay.ets'), 'utf8');

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

test('native smart UI reveal is gated, measured, and input-transparent', () => {
  assert.match(card, /@Prop @Watch\('onRevealEligibleChanged'\) revealEligible/);
  assert.match(card, /CONSUMED_NATIVE_UI_REVEALS/);
  assert.match(card, /scheduleRevealStart[\s\S]*this\.measuredFrames\.size === 0/);
  assert.match(card, /reduceMotion/);
  assert.match(card, /appBackgrounded/);
  assert.match(card, /onVisibleAreaChange\(\[0, 1\]/);
  assert.match(card, /event\.type === TouchType\.Down/);
  assert.match(node, /area\.globalPosition\.x/);
  assert.match(node, /area\.globalPosition\.y/);
  assert.match(node, /\.onAreaChange\(\(_old: Area, area: Area\)/);
  assert.match(overlay, /HitTestMode\.None/);
  assert.match(overlay, /Canvas\(this\.context\)/);
  assert.match(overlay, /rgba\(/);
  assert.doesNotMatch(overlay, /#\$\{channel/);
  assert.match(overlay, /\.position\(\{ x: 0, y: 0 \}\)/);
  assert.match(card, /NativeUiGenerationOverlay\([\s\S]*?\.position\(\{ x: 0, y: 0 \}\)/);
  assert.match(overlay, /setInterval\(\(\): void => \{ this\.tick\(\); \}, 16\)/);
  assert.match(overlay, /clearInterval\(this\.animationTimer\)/);
  assert.doesNotMatch(overlay, /animateTo/);
  assert.doesNotMatch(card, /opacity\(this\.revealActive/);
  assert.match(overlay, /onFinished/);
});

function evaluateExportedClass(source, name, prefix = '') {
  const start = source.indexOf(`export class ${name} {`);
  assert.ok(start >= 0, name);
  let end = source.indexOf('{', start);
  let depth = 0;
  for (; end < source.length; end++) {
    if (source[end] === '{') depth++;
    if (source[end] === '}') {
      depth--;
      if (depth === 0) break;
    }
  }
  const classSource = source.slice(start, end + 1);
  const output = ts.transpileModule(`${prefix}${classSource.replace('export ', '')}`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022 },
  }).outputText;
  return new Function(`${output}; return ${name};`)();
}

test('reveal clock is a bounded elapsed-time animation and ledger consumes once', () => {
  const Clock = evaluateExportedClass(overlay, 'NativeUiRevealClock',
    'const clampUnit = (value) => Math.max(0, Math.min(1, value));\n');
  const clock = new Clock(680);
  clock.start(1000);
  assert.equal(clock.progress(1000), 0);
  assert.ok(clock.progress(1340) > 0 && clock.progress(1340) < 1);
  assert.equal(clock.progress(1680), 1);
  assert.equal(clock.done(1679), false);
  assert.equal(clock.done(1680), true);

  const Ledger = evaluateExportedClass(card, 'NativeUiRevealLedger');
  const ledger = new Ledger();
  assert.equal(ledger.has('source-a'), false);
  ledger.consume('source-a');
  assert.equal(ledger.has('source-a'), true);
  assert.equal(ledger.has('source-b'), false);
});

test('measured frame coordinates are translated to overlay coordinates and clipped', () => {
  const match = overlay.match(/export interface NativeUiFrameBounds[\s\S]*?export const nativeUiFrameBounds[\s\S]*?\n\};/);
  assert.ok(match);
  const output = ts.transpileModule(match[0].replace('export ', '').replace('export ', ''), {
    compilerOptions: { target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const nativeUiFrameBounds = new Function(`${output}; return nativeUiFrameBounds;`)();
  assert.deepEqual(nativeUiFrameBounds({ id: 'a', type: 'button', x: 120, y: 240, width: 80, height: 44 }, 100, 200, 200, 200),
    { left: 20, top: 40, right: 100, bottom: 84 });
  assert.equal(nativeUiFrameBounds({ id: 'tiny', type: 'text', x: 0, y: 0, width: 1, height: 1 }, 0, 0, 200, 200), null);
});

test('column container frames do not become reveal holes and accent channels stay intact', () => {
  const frameStart = card.indexOf('export const nativeUiRevealFrames');
  const frameEnd = card.indexOf(';', frameStart) + 1;
  assert.ok(frameStart >= 0 && frameEnd > frameStart);
  const frameOutput = ts.transpileModule(card.slice(frameStart, frameEnd).replace('export ', ''), {
    compilerOptions: { target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const nativeUiRevealFrames = new Function(`${frameOutput}; return nativeUiRevealFrames;`)();
  const root = { id: 'root', type: 'column', x: 0, y: 0, width: 200, height: 120 };
  const leaf = { id: 'button', type: 'button', x: 12, y: 16, width: 176, height: 44 };
  assert.deepEqual(nativeUiRevealFrames([root, leaf]), [leaf]);

  const colorMatch = overlay.match(/export const nativeUiColorWithAlpha[\s\S]*?\n\};/);
  assert.ok(colorMatch);
  const colorOutput = ts.transpileModule(colorMatch[0].replace('export ', ''), {
    compilerOptions: { target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const nativeUiColorWithAlpha = new Function(
    `const clampUnit = (value) => Math.max(0, Math.min(1, value));\n${colorOutput}; return nativeUiColorWithAlpha;`,
  )();
  assert.equal(nativeUiColorWithAlpha('#B8623A', 0.5), 'rgba(184, 98, 58, 0.5)');
});

test('reveal VM cancels on state regression and consumes root-only snapshots', () => {
  const methodSources = ['tryStartReveal', 'onReadyChanged', 'onStreamingChanged', 'onRevealEligibleChanged']
    .map((name) => method(card, name)).join('\n');
  const output = ts.transpileModule(`class RevealVm { ${methodSources} }`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const RevealVm = new Function(`${output}; return RevealVm;`)();

  const vm = new RevealVm();
  let consumed = 0;
  let finished = 0;
  Object.assign(vm, {
    pendingReveal: true, ready: true, revealEligible: true, reduceMotion: false,
    appBackgrounded: false, cardVisible: true, frames: [],
    consumed: () => false,
    snapshotFrames: () => [],
    consume: () => { consumed++; },
    finishReveal: () => { finished++; },
  });
  vm.tryStartReveal();
  assert.equal(consumed, 1, 'root-only measurement is consumed without starting an empty overlay');
  assert.equal(finished, 1);

  const guard = new RevealVm();
  let cancelled = 0;
  Object.assign(guard, {
    ready: false, revealEligible: true, streaming: false,
    cancelReveal: () => { cancelled++; },
  });
  guard.onReadyChanged();
  assert.equal(cancelled, 1, 'ready regression cancels an active overlay');
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

