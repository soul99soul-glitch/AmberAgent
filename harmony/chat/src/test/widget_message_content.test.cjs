const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const source = fs.readFileSync(path.resolve(__dirname,
  '../../../entry/src/main/ets/components/WidgetMessageContent.ets'), 'utf8');
const methods = [...source.matchAll(/  private [A-Za-z][A-Za-z0-9_]*\([^]*?\n  \}/g)]
  .map(match => match[0]).join('\n');
const fields = (source.match(/^  private [A-Za-z][A-Za-z0-9_]*:[^\n]+/gm) ?? []).join('\n');
const build = source.slice(source.indexOf('  build(): void'));
const callback = build.match(/ForEach\(this\.segments,[^]*?=>\s*\{([^]*?)\n\s*\},/)[1];
function fixture(dependencies = {}) {
  const records = [];
  const exports = {};
  const chain = new Proxy({}, { get: () => () => chain });
  const script = 'class Probe {' + fields + '\n' + methods + '} exports.Probe=Probe;' +
    'exports.render=function(segment,index){' + callback + '};';
  vm.runInNewContext(ts.transpileModule(script, {
    compilerOptions: { target: ts.ScriptTarget.ES2022 },
  }).outputText, {
    exports, LABEL: 12, INK3: '#888', ...dependencies,
    MarkdownText: props => records.push({ kind: 'text', props }),
    GenerativeWidgetCard: props => records.push({ kind: 'widget', props }),
    NativeSmartUiCard: props => records.push({ kind: 'ui', props }),
    VisualArtifactCard: props => records.push({ kind: 'visual', props }),
    Text: text => { records.push({ kind: 'fallback', text }); return chain; },
  });
  const probe = new exports.Probe();
  Object.assign(probe, { segments: [], smartUiSourcePrefix: 'source', smartUiValuesFor: () => [],
    onVisibleGrow: () => {}, smartUiProjections: [], smartUiValueSnapshots: [],
    content: '', streaming: false, streamKey: '', assets: [], declaredContentIds: [], messageStreaming: false,
    smartUiNodeId: '', onSmartUiDocument: () => {}, onSmartUiSourceReplaced: () => {} });
  return { probe, render: exports.render, records };
}

test('equivalent V1 prop copies preserve parsed segments and the native 3D scene input', async () => {
  const domain = await import('../main/ets/chat/response_content.ts');
  let parseCalls = 0;
  const f = fixture({ ...domain, parseResponseContent: (...args) => { parseCalls++; return domain.parseResponseContent(...args); } });
  f.probe.content = '```amber-three v=1 id=scene\n' +
    '{"objects":[{"id":"cube","type":"box","color":"#f00","position":[0,0,0],"size":[1,1,1]}]}\n```';
  f.probe.assets = domain.parseResponseContent(f.probe.content, false).map(segment => segment.artifact);
  f.probe.declaredContentIds = ['scene'];
  f.probe.prepare();
  const segments = f.probe.segments;
  for (let index = 0; index < 4; index++) {
    f.probe.assets = structuredClone(f.probe.assets);
    f.probe.prepare();
    f.probe.declaredContentIds = [...f.probe.declaredContentIds];
    f.probe.prepare();
  }
  assert.equal(parseCalls, 1, 'watchers must skip parsing equivalent array copies');
  assert.equal(f.probe.segments, segments, 'unchanged child scene props retain cached native covers');
});

test('XML validation changes still update a retained content projection', async () => {
  const domain = await import('../main/ets/chat/response_content.ts');
  const f = fixture(domain);
  f.probe.content = '```amber-svg v=1 id=diagram\n<svg xmlns="http://www.w3.org/2000/svg"/>\n```';
  f.probe.declaredContentIds = ['diagram'];
  f.probe.assets = domain.parseResponseContent(f.probe.content, false).map(segment => segment.artifact);
  f.probe.prepare();
  assert.equal(f.probe.segments[0].artifact.status, 'partial');
  for (const status of ['ready', 'error']) {
    f.probe.assets = structuredClone(f.probe.assets);
    f.probe.assets[0].status = status;
    f.probe.prepare();
    assert.equal(f.probe.segments[0].artifact.status, status);
  }
});

test('stream completion and reused Smart UI source identities still publish their current definitions', async () => {
  const domain = await import('../main/ets/chat/response_content.ts');
  const f = fixture(domain);
  const published = [];
  const replaced = [];
  Object.assign(f.probe, {
    content: '```amber-ui v=1 id=panel\n{"op":"node","id":"root","type":"column"}\n{"op":"end"}\n```',
    streaming: true, messageStreaming: true, streamKey: 'stream-a', smartUiNodeId: 'node-a',
    declaredContentIds: ['panel'],
    onSmartUiDocument: (...args) => published.push({ args, nodeId: f.probe.smartUiNodeId }),
    onSmartUiSourceReplaced: prefix => replaced.push(prefix),
  });
  f.probe.prepare();
  assert.equal(published.at(-1).args[2], true);
  f.probe.streaming = false;
  f.probe.messageStreaming = false;
  f.probe.prepare();
  assert.equal(published.at(-1).args[2], false);
  f.probe.smartUiSourcePrefix = 'replacement-source';
  f.probe.prepare();
  assert.deepEqual(replaced, ['source']);
  assert.match(published.at(-1).args[0], /^replacement-source:/);
  f.probe.smartUiNodeId = 'node-b';
  f.probe.prepare();
  assert.equal(published.at(-1).nodeId, 'node-b');
});

test('retained callbacks read current UI and SVG through real builders', async () => {
  const { parseResponseContent } = await import('../main/ets/chat/response_content.ts');
  const f = fixture();
  const initial = parseResponseContent('```amber-ui v=1 id=controls\n', true)[0];
  const ui = '```amber-ui v=1 id=controls\n' +
    '{"op":"node","id":"root","type":"column"}\n' +
    '{"op":"node","id":"detail","parent":"root","type":"text","text":"ready"}\n' +
    '{"op":"end"}\n```';
  f.probe.segments = parseResponseContent(ui, false);
  f.render.call(f.probe, initial, 0);
  assert.equal(f.records[0].kind, 'ui');
  assert.equal(f.records[0].props.document.rootId, 'root');
  assert.equal(f.records[0].props.ready, true);
  const svg = '```amber-svg v=1 id=diagram\n' +
    '<svg xmlns="http://www.w3.org/2000/svg"><rect width="10" height="10"/></svg>\n```';
  f.probe.segments = parseResponseContent(svg, false);
  f.render.call(f.probe, initial, 0);
  assert.equal(f.records[1].kind, 'visual');
  assert.match(f.records[1].props.artifact.snapshot, /width="10"/);
});

test('a message-level ID change disables an earlier card without replacing its content or state', async () => {
  const domain = await import('../main/ets/chat/response_content.ts');
  const f = fixture(domain);
  const content = '```amber-ui v=1 id=panel\n{"op":"node","id":"root","type":"column"}\n' +
    '{"op":"end"}\n```';
  const published = [];
  const changes = [];
  Object.assign(f.probe, { content, assets: [], streaming: false, messageStreaming: false,
    smartUiNodeId: 'node', declaredContentIds: ['panel'],
    onSmartUiDocument: (...args) => published.push(args),
    onSmartUiSourceReplaced: (...args) => changes.push(args) });
  f.probe.prepare();
  assert.equal(f.probe.segmentReadyAt(0), true);
  assert.equal(published.at(-1).at(-1), true);
  f.probe.declaredContentIds = ['panel', 'panel'];
  f.probe.prepare();
  assert.equal(f.probe.segmentReadyAt(0), false);
  assert.match(f.probe.segmentDiagnosticAt(0), /ID 重复/);
  assert.equal(published.at(-1).at(-1), false);
  assert.equal(changes.length, 0);
  f.probe.declaredContentIds = ['panel', 'unique'];
  f.probe.prepare();
  assert.equal(f.probe.segmentReadyAt(0), true);
  assert.equal(published.at(-1).at(-1), true);
});

test('duplicate blocks retain their local view and show the ID ambiguity beside it', async () => {
  const { parseResponseContent } = await import('../main/ets/chat/response_content.ts');
  const blocks = [
    '```amber-ui v=1 id=shared\n{"op":"node","id":"root","type":"column"}\n{"op":"end"}\n```',
    '```amber-svg v=1 id=shared\n<svg/>\n```',
  ];
  for (const content of blocks) {
    const f = fixture();
    f.probe.segments = parseResponseContent(content, false, undefined, true, ['shared', 'shared']);
    f.probe.ResponseSegment(0);
    assert.ok(['ui', 'visual'].includes(f.records[0].kind), 'valid local content remains visible');
    assert.equal(f.records.at(-1).kind, 'fallback');
    assert.match(f.records.at(-1).text, /shared.*ID 重复/);
  }
});
test('legacy failures and replacement text do not use captured old snapshots', async () => {
  const { parseResponseContent } = await import('../main/ets/chat/response_content.ts');
  const f = fixture();
  const raw = '```show-widget\n' + JSON.stringify({ title: 'CA', renderer: 'svg',
    widget_code: '<svg viewBox="0 0 200 60"><text x="4" y="24">CA</text></svg>' });
  const initial = parseResponseContent(raw.slice(0, -8), true)[0];
  f.probe.segments = parseResponseContent('```show-widget\n{"title":', false);
  f.render.call(f.probe, initial, 0);
  assert.equal(f.records.at(-1).kind, 'fallback');
  f.probe.segments = parseResponseContent(raw + '\n```', false);
  f.render.call(f.probe, initial, 0);
  assert.equal(f.records.at(-1).props.widget.title, 'CA');
  f.probe.segments = parseResponseContent('cancelled', false);
  f.render.call(f.probe, initial, 0);
  assert.equal(f.records.at(-1).props.content, 'cancelled');
});

test('an external restored value refreshes props without replacing the retained card callback', async () => {
  const { parseResponseContent } = await import('../main/ets/chat/response_content.ts');
  const f = fixture();
  f.probe.segments = parseResponseContent('```amber-ui v=1 id=panel\n' +
    '{"op":"state","id":"selected","initial":"root"}\n' +
    '{"op":"node","id":"root","type":"column"}\n{"op":"end"}\n```', false);
  const initial = f.probe.segments[0];
  f.probe.syncSmartUiValues();
  f.render.call(f.probe, initial, 0);
  assert.equal(f.records.at(-1).props.initialValues.length, 0);
  f.probe.smartUiValuesFor = () => [{ id: 'selected', value: 'server' }];
  f.probe.syncSmartUiValues();
  f.render.call(f.probe, initial, 0);
  assert.equal(f.records.at(-1).props.initialValues[0].value, 'server');
});
