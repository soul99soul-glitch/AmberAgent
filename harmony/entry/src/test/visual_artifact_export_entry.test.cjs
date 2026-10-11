const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const entryRoot = path.resolve(__dirname, '..');
const shelf = fs.readFileSync(path.join(entryRoot, 'main/ets/components/ChatArtifactShelf.ets'), 'utf8');
const exporter = fs.readFileSync(path.join(entryRoot, 'main/ets/platform_impl/VisualArtifactExport.ets'), 'utf8');

test('artifact shelf keeps declared content in independent SVG, scene, and static UI sections', () => {
  assert.match(shelf, /artifacts\.svg/);
  assert.match(shelf, /artifacts\.scenes/);
  assert.match(shelf, /artifacts\.uiSnapshots/);
  assert.match(shelf, /private SvgPreview\(\)/);
  assert.match(shelf, /private ScenePreview\(\)/);
  assert.match(shelf, /private UiSnapshotPreview\(\)/);
  assert.match(shelf, /renderer: 'svg'/);
  assert.match(shelf, /renderer: 'threejs'/);
  assert.match(shelf, /svgSelectionEpoch/);
  assert.match(shelf, /svgValidationSnapshot/);
  assert.match(shelf, /svgValidationCanonical/);
  assert.match(shelf, /canonicalSvg/);
  assert.match(shelf, /svgValidationCanonical\.length > 120000/);
  assert.match(shelf, /source\.blockIndex/);
  assert.match(shelf, /source\.sourceStart/);
  assert.match(shelf, /source\.blockId/);
  assert.match(shelf, /ForEach\(\[this\.svgSelectionEpoch\]/);
  assert.doesNotMatch(shelf, /NativeSmartUiCard/);
});

test('visual export requires the SVG DOM proof and labels scene/UI output separately', () => {
  assert.match(exporter, /svgArtifactExport\(artifact, proof\)/);
  assert.match(exporter, /ConversationSvgValidationProof/);
  assert.match(exporter, /sceneArtifactExport\(artifact\)/);
  assert.match(exporter, /uiSnapshotArtifactExport\(artifact\)/);
  assert.match(exporter, /saveMarkdownFile/);
  assert.match(exporter, /coverFileName/);
  assert.match(exporter, /uiSnapshotArtifactExport/);
});

