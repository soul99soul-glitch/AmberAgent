const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('../chat/node_modules/typescript');

test('Plugin approval keeps the pinned review, expected-result presence and parent ask_user identity', () => {
  const load = (name, dependencies = {}) => {
    const source = fs.readFileSync(path.resolve(__dirname, '../entry/src/main/ets/components', name), 'utf8');
    const output = ts.transpileModule(source, { compilerOptions: {
      module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022,
    } }).outputText;
    const exports = {};
    vm.runInNewContext(output, { exports, require: (name) => {
      assert.ok(Object.hasOwn(dependencies, name), `Unexpected dependency: ${name}`);
      return dependencies[name];
    } }, { filename: name });
    return exports;
  };
  const page = load('PluginPageProjection.ets');
  const projection = load('PluginApprovalSummary.ets', { './PluginPageProjection.ets': page });
  const primitive = { type: 'tool', toolCallId: 'saved-child', toolName: 'python_execute',
    input: '{"code":"print(stdin)","stdin":"fixed"}', output: [], approvalState: { type: 'pending' },
    metadata: { fixed: 'exact-child-metadata' } };
  const capabilities = { workspaceReadPrefixes: [], workspaceWritePrefixes: ['output'],
    networkDomains: [], webMountActions: [], localRuntimes: ['embedded_python'] };
  const descriptor = { pluginId: 'fixture', toolId: 'plugin__fixture__run', name: 'run', version: '2.1.0',
    packageHash: 'pinned-package-hash', capabilities, backgroundAllowed: false,
    implementation: { kind: 'command', runtime: 'embedded_python', entry: 'scripts/run.py',
      source: 'print(stdin)', stdinInput: null } };
  const checkpoint = { kind: 'run', executionId: 'fixed-execution', descriptor, inputs: { value: 'pinned-input' },
    phase: 'awaiting_approval', pendingStep: primitive, pendingCallId: null, recipeState: null, test: null };
  const parent = { type: 'tool', toolCallId: 'parent-call', toolName: descriptor.toolId,
    input: '{"value":"changed-model-input"}', output: [], approvalState: { type: 'pending' },
    metadata: { plugin_v1: checkpoint } };
  const before = JSON.stringify(parent);
  const view = projection.pluginApprovalView(parent);
  assert.equal(view.kind, 'run');
  assert.ok(view.title.includes('2.1.0') && view.title.includes(descriptor.toolId) && view.title.includes('embedded_python'));
  assert.equal(view.hash, descriptor.packageHash);
  assert.equal(view.pendingStep, primitive);
  assert.equal(view.pendingStep.input, primitive.input);
  assert.equal(JSON.parse(view.canonicalJSON).inputs.value, 'pinned-input');
  assert.equal(projection.pluginAskUserPart(parent), null);
  assert.equal(JSON.stringify(parent), before);

  const testParent = { ...parent, toolName: 'plugin_test' };
  for (const [expectedProvided, expectedResult, label] of [[false, null, '未提供'], [true, null, 'null'], [true, { ok: true }, '已提供']]) {
    testParent.metadata = { plugin_v1: { ...checkpoint, test: {
      kind: 'candidate_test', candidateHash: descriptor.packageHash, expectedProvided, expectedResult,
    } } };
    const trial = projection.pluginApprovalView(testParent);
    assert.ok(trial.title.includes('候选试跑'));
    assert.ok(trial.stepLabel.includes(label));
    const preview = JSON.parse(trial.canonicalJSON);
    assert.equal(preview.test.expectedProvided, expectedProvided);
    assert.deepEqual(preview.test.expectedResult, expectedResult);
  }

  const askChild = { ...primitive, toolName: 'ask_user',
    input: '{"questions":[{"id":"decision","question":"继续？"}]}' };
  const askParent = { ...parent, metadata: { plugin_v1: { ...checkpoint, pendingStep: askChild } } };
  const ask = projection.pluginAskUserPart(askParent);
  assert.equal(ask.toolCallId, 'parent-call');
  assert.equal(ask.input, askChild.input);
  assert.equal(ask.metadata, askChild.metadata);
  assert.equal(ask.approvalState.type, 'pending');
  askParent.approvalState = { type: 'answered', answer: '{"answers":{"decision":"继续"}}' };
  assert.equal(projection.pluginAskUserPart(askParent).approvalState.answer, askParent.approvalState.answer);
  askParent.approvalState = { type: 'approved' };
  assert.equal(projection.pluginAskUserPart(askParent).approvalState.type, 'approved');
  assert.equal(askChild.toolCallId, 'saved-child');
  assert.equal(askChild.approvalState.type, 'pending');

  const fileHashes = { 'plugin.json': 'manifest-hash', 'scripts/run.py': 'python-hash', 'assets/input.csv': 'asset-hash' };
  const trust = { tier: 'signed', publisherTrusted: false, keyId: 'short-key-id', fingerprint: 'full-key-fingerprint', signature: null };
  const imported = projection.pluginApprovalView({ ...parent, toolName: 'plugin_import', metadata: { plugin_v1: {
    kind: 'import', preview: { source: { kind: 'archive', workspacePath: 'packages/fixed.amberplugin' },
      candidate: { hash: 'candidate-package-hash', manifest: { name: 'Fixed Plugin', version: '3.0', capabilities },
        files: Object.keys(fileHashes).map((path) => ({ path, data: [1] })), fileHashes, envelope: { effect: 'write' } },
      trust, baseHash: 'installed-hash', permissionExpanded: true, permissionDiff: ['Workspace write: output'], enable: true },
  } } });
  assert.ok(imported.title.includes('安装并启用') && imported.title.includes('3.0'));
  assert.ok(imported.stepLabel.includes('发布者未信任') && imported.stepLabel.includes('权限扩大'));
  assert.equal(imported.hash, 'candidate-package-hash');
  assert.equal(imported.baseHash, 'installed-hash');
  assert.equal(imported.workspacePath, 'packages/fixed.amberplugin');
  const review = JSON.parse(imported.canonicalJSON);
  assert.deepEqual(review.file_hashes, fileHashes);
  assert.deepEqual(review.trust, trust);
  assert.deepEqual(review.permission_diff, ['Workspace write: output']);
  assert.equal(review.enable, true);
  assert.equal(review.base_hash, 'installed-hash');
  assert.equal(review.source.kind, 'archive');
  assert.equal(projection.pluginApprovalView({ ...parent, toolName: 'recipe__fixture' }), null);
  assert.equal(projection.pluginApprovalView({ ...parent, metadata: { plugin_v1: { kind: 'run' } } }), null);
});
