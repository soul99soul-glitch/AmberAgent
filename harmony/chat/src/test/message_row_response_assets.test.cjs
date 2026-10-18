const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

test('message row shares current XML validation across text groups and rejects stale snapshots', async () => {
  const { parseResponseContent, applySvgValidation, declaredResponseContentIds } = await import('../main/ets/chat/response_content.ts');
  const source = fs.readFileSync(path.resolve(__dirname,
    '../../../entry/src/main/ets/components/AgentMessageRow.ets'), 'utf8');
  const methods = ['responseAssets', 'validatedArtifact'].map(name =>
    source.match(new RegExp(`  private ${name}\\([^]*?\\n  \\}`))[0]).join('\n');
  const exports = {};
  vm.runInNewContext(ts.transpileModule(`class Probe {${methods}}; exports.Probe=Probe;`, {
    compilerOptions: { target: ts.ScriptTarget.ES2022 },
  }).outputText, { exports, parseResponseContent, applySvgValidation, declaredResponseContentIds });
  const svg = '```amber-svg v=1 id=diagram\n<svg xmlns="http://www.w3.org/2000/svg"><rect width="10" height="10"/></svg>\n```';
  let blocks = [{ kind: 'content', index: 2, part: { type: 'text', text: svg } }];
  const probe = new exports.Probe();
  probe.streaming = false;
  probe.svgValidationResults = [];
  probe.getBlocks = () => blocks;
  Object.defineProperty(probe, 'message', { get: () => ({ parts: blocks.map(block => block.part) }) });
  const candidate = probe.responseAssets()[0];
  assert.equal(candidate.status, 'partial');
  probe.validatedArtifact(2, candidate.sourceStart, candidate.id, candidate.snapshot, true, '');
  const registry = probe.responseAssets();
  assert.equal(registry[0].status, 'ready');
  const ui = '```amber-ui v=1 id=controls\n{"op":"node","id":"root","type":"column"}\n' +
    '{"op":"node","id":"view","parent":"root","type":"asset","ref":"diagram"}\n{"op":"end"}\n```';
  assert.equal(parseResponseContent(ui, false, registry)[0].assetDiagnostics.length, 0);
  blocks = [{ kind: 'content', index: 2, part: { type: 'text', text: svg.replace('width="10"', 'width="20"') } }];
  assert.equal(probe.responseAssets()[0].status, 'partial');
  blocks = [
    { kind: 'content', index: 0, part: { type: 'text', text: ui.replace('id=controls', 'id=diagram') } },
    { kind: 'content', index: 1, part: { type: 'image', url: 'https://example.com/image.png' } },
    { kind: 'content', index: 2, part: { type: 'text', text: svg } },
  ];
  assert.equal(probe.responseAssets()[0].status, 'error',
    'shared XML validation cannot promote a visual ID that collides with a UI in another text group');
});
