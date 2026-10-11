import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  applySvgValidation,
  parseResponseContent,
  stripDeclaredResponseBlocks,
  validateResponseContentReferences,
} from '../main/ets/chat/response_content.ts';
import type { ResponseContentSegment, VisualArtifact } from '../main/ets/chat/response_content.ts';

const segment = (content: string, streaming: boolean = false): ResponseContentSegment[] =>
  parseResponseContent(content, streaming);

const byType = (segments: ResponseContentSegment[], type: ResponseContentSegment['type']): ResponseContentSegment => {
  const found: ResponseContentSegment | undefined = segments.find((item: ResponseContentSegment): boolean => item.type === type);
  assert.ok(found, `missing ${type} segment`);
  return found as ResponseContentSegment;
};

const svg = (id: string = 'diagram'): string => `
\`\`\`amber-svg v=1 id=${id}
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><rect width="10" height="10" fill="#123456"/></svg>
\`\`\`
`;

const ui = (id: string = 'controls', ref: string = 'diagram'): string => `
\`\`\`amber-ui v=1 id=${id}
{"op":"node","id":"root","type":"column"}
{"op":"node","id":"asset","parent":"root","type":"asset","ref":"${ref}"}
{"op":"end"}
\`\`\`
`;

const three = (id: string = 'scene'): string => `
\`\`\`amber-three v=1 id=${id}
{"objects":[{"id":"cube","type":"box","color":"#ff6633","position":[0,1,0],"rotation":[0,0,0],"scale":[1,1,1],"size":[2,2,2]}]}
\`\`\`
`;

const codes = (item: ResponseContentSegment): string[] => item.diagnostics.map((diagnostic) => diagnostic.code);

test('single router preserves text and declared fence order while ordinary code remains text', () => {
  const source: string = '前文\n````text\n```amber-svg v=1 id=hidden\n<svg/>\n```\n````\n' + svg() + '后文';
  const segments: ResponseContentSegment[] = segment(source);
  assert.equal(segments.filter((item) => item.type === 'svg').length, 1);
  assert.equal(segments[0].type, 'text');
  assert.match(segments[0].content, /```text/);
  assert.match(segments[0].content, /hidden/);
  assert.equal(segments[1].type, 'svg');
  assert.equal(segments[1].sourceStart, source.indexOf(svg().trimStart()));
  assert.equal(segments[2].type, 'text');
  assert.match(segments[2].content, /后文/);
});

test('strict new headers report missing, unknown, duplicate and invalid arguments locally', () => {
  const missing: ResponseContentSegment = byType(segment('```amber-ui\n{"op":"end"}\n```'), 'ui');
  assert.equal(missing.status, 'error');
  assert.ok(codes(missing).includes('missing-version'));
  assert.ok(codes(missing).includes('missing-id'));

  const invalid: ResponseContentSegment = byType(
    segment('```amber-svg v=1 id=bad! unknown=ok v=1\n<svg/>\n```'), 'svg',
  );
  assert.equal(invalid.status, 'error');
  assert.ok(codes(invalid).includes('unknown-argument'));
  assert.ok(codes(invalid).includes('duplicate-argument'));
  assert.ok(codes(invalid).includes('invalid-id'));
});

test('UI completion is gated by the outer fence and draft stays disabled while it is open', () => {
  const body: string = '{"op":"node","id":"root","type":"column"}\n{"op":"end"}';
  const open: ResponseContentSegment = byType(segment(`\`\`\`amber-ui v=1 id=open\n${body}`, false), 'ui');
  assert.equal(open.closed, false);
  assert.equal(open.ui?.status, 'ready');
  assert.equal(open.complete, false);
  assert.equal(open.draftEnabled, false);

  const closed: ResponseContentSegment = byType(segment(`\`\`\`amber-ui v=1 id=closed\n${body}\n\`\`\``, false), 'ui');
  assert.equal(closed.closed, true);
  assert.equal(closed.complete, true);
  assert.equal(closed.draftEnabled, true);
});

test('SVG is a candidate snapshot until trusted XML validation and has no GenerativeWidget', () => {
  const item: ResponseContentSegment = byType(segment('前\n' + svg() + '后'), 'svg');
  assert.equal(item.artifact?.type, 'svg');
  assert.equal(item.widget, null);
  assert.equal(item.status, 'partial');
  assert.equal(item.complete, false);
  assert.ok(item.artifact?.snapshot?.startsWith('<svg'));
  assert.doesNotMatch(item.artifact?.content ?? '', /<script|onload=/i);
});

test('SVG and Three.js remain separate artifacts and an incomplete fence is local', () => {
  const partialSvg: ResponseContentSegment = byType(segment('```amber-svg v=1 id=partial\n<svg><rect>', true), 'svg');
  assert.equal(partialSvg.closed, false);
  assert.equal(partialSvg.status, 'partial');
  assert.equal(partialSvg.artifact?.snapshot, null);

  const threeSegment: ResponseContentSegment = byType(segment(three()), 'three');
  assert.equal(threeSegment.status, 'ready');
  assert.equal(threeSegment.artifact?.type, 'three');
  assert.equal(threeSegment.artifact?.scene?.objects.length, 1);
  assert.equal(threeSegment.widget, null);
});

test('SVG projection accepts legal prefixes and trailing comments or processing instructions', () => {
  const source: string = '```amber-svg v=1 id=decorated\n' +
    '<?xml version="1.0"?>\n<!-- prefix -->\n<svg xmlns="http://www.w3.org/2000/svg"><rect/></svg><!-- tail --><?tail?>\n```';
  const item: ResponseContentSegment = byType(segment(source), 'svg');
  assert.equal(item.status, 'partial');
  assert.equal(item.artifact?.snapshot !== null, true);
  assert.deepEqual(item.diagnostics, []);

  const prefixed: ResponseContentSegment = byType(segment(
    '```amber-svg v=1 id=prefixed\n' +
    '<svg:svg xmlns:svg="http://www.w3.org/2000/svg"><svg:rect/></svg:svg>\n```',
  ), 'svg');
  assert.equal(prefixed.status, 'partial');
  assert.equal(prefixed.artifact?.snapshot !== null, true);
});

test('malformed closed SVG is only a candidate until validation, and validation is snapshot-bound', () => {
  const source: string = '```amber-svg v=1 id=proof\n<svg><rect>\n```';
  const initial: ResponseContentSegment[] = segment(source);
  const item: ResponseContentSegment = byType(initial, 'svg');
  assert.equal(item.status, 'partial');
  assert.equal(item.artifact?.snapshot !== null, true);
  const snapshot: string = item.artifact?.snapshot as string;

  const stale: ResponseContentSegment[] = applySvgValidation(initial, item.sourceStart, 'proof', snapshot + 'stale', true);
  assert.equal(stale[0].status, 'partial');
  assert.equal(initial[0].status, 'partial');

  const failed: ResponseContentSegment[] = applySvgValidation(initial, item.sourceStart, 'proof', snapshot, false, 'DOM parser rejected SVG');
  assert.equal(failed[0].status, 'error');
  assert.equal(failed[0].complete, false);
  assert.equal(failed[0].artifact?.status, 'error');
  assert.equal(failed[0].diagnostics.at(-1)?.code, 'svg-validation-failed');
});

test('trusted validation promotes only the current closed SVG and leaves other content unchanged', () => {
  const source: string = '说明\n' + svg('valid') + ui('controls', 'valid');
  const initial: ResponseContentSegment[] = segment(source);
  const visual: ResponseContentSegment = byType(initial, 'svg');
  const snapshot: string = visual.artifact?.snapshot as string;
  const validated: ResponseContentSegment[] = applySvgValidation(
    initial, visual.sourceStart, 'valid', snapshot, true,
  );
  const result: ResponseContentSegment = byType(validated, 'svg');
  const uiResult: ResponseContentSegment = byType(validated, 'ui');
  assert.equal(result.status, 'ready');
  assert.equal(result.complete, true);
  assert.equal(result.artifact?.status, 'ready');
  assert.equal(uiResult.status, 'ready');
  assert.equal(uiResult.draftEnabled, true);
});

test('legacy widget fences delegate to the old parser', () => {
  const source: string = '说明\n```show-widget\n' + JSON.stringify({
    title: '旧卡片', renderer: 'svg', widget_code: '<svg><rect/></svg>',
  }) + '\n```\n尾部';
  const segments: ResponseContentSegment[] = segment(source);
  const legacy: ResponseContentSegment = byType(segments, 'widget');
  assert.equal(legacy.widget?.title, '旧卡片');
  assert.equal(legacy.artifact, null);
  assert.equal(legacy.ui, null);
});

test('UI asset references resolve against the complete message registry, including late artifacts', () => {
  const segments: ResponseContentSegment[] = segment(ui() + svg());
  const uiSegment: ResponseContentSegment = byType(segments, 'ui');
  assert.equal(uiSegment.status, 'ready');
  assert.equal(uiSegment.draftEnabled, true);

  const onlyUi: ResponseContentSegment[] = segment(ui());
  const onlyUiSegment: ResponseContentSegment = byType(onlyUi, 'ui');
  const diagnostics = validateResponseContentReferences(
    onlyUiSegment.ui as NonNullable<ResponseContentSegment['ui']>, [], false,
  );
  assert.ok(diagnostics.some((item) => item.code === 'asset-pending'));

  const uiOnly: ResponseContentSegment = byType(segment(ui()), 'ui');
  const artifact: VisualArtifact = byType(segment(svg()), 'svg').artifact as VisualArtifact;
  const routedWithFullRegistry: ResponseContentSegment = byType(
    parseResponseContent(ui(), false, [artifact], true), 'ui',
  );
  assert.equal(routedWithFullRegistry.status, 'ready');
  assert.equal(routedWithFullRegistry.draftEnabled, true);
  assert.equal(uiOnly.status, 'ready');
  assert.equal(uiOnly.draftEnabled, true);
  assert.equal(uiOnly.assetDiagnostics[0]?.nodeId, 'asset');
});

test('duplicate artifact IDs and missing terminal references are local errors', () => {
  const segments: ResponseContentSegment[] = segment(svg('same') + three('same'));
  const artifacts: VisualArtifact[] = segments
    .filter((item): item is ResponseContentSegment & { artifact: VisualArtifact } => item.artifact !== null)
    .map((item) => item.artifact);
  assert.equal(artifacts.length, 2);
  assert.ok(segments.every((item) => item.type === 'svg' || item.type === 'three' ? item.status === 'error' : true));

  const unresolved: ResponseContentSegment = byType(segment(ui('controls', 'missing')), 'ui');
  assert.equal(unresolved.status, 'ready');
  assert.equal(unresolved.draftEnabled, true);
  assert.ok(unresolved.assetDiagnostics.some((item) => item.code === 'asset-not-found'));

  const duplicateUi: ResponseContentSegment[] = segment(ui('same-ui') + ui('same-ui'));
  assert.equal(duplicateUi.filter((item) => item.type === 'ui').every((item) => item.status === 'error'), true);
  assert.ok(duplicateUi.filter((item) => item.type === 'ui').every((item) => codes(item).includes('duplicate-id')));
});

test('stripDeclaredResponseBlocks only strips new protocol blocks and keeps source immutable', () => {
  const source: string = '前\n' + ui() + svg() + '\n```show-widget\n{}\n```\n后';
  const stripped: string = stripDeclaredResponseBlocks(source);
  assert.equal(source.includes('amber-ui'), true);
  assert.doesNotMatch(stripped, /amber-ui|amber-svg/);
  assert.match(stripped, /```show-widget/);
  assert.match(stripped, /前/);
  assert.match(stripped, /后/);
});

