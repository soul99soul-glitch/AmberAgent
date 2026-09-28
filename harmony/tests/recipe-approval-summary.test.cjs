const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('../chat/node_modules/typescript');

test('Recipe approval displays the pinned primitive and ask_user keeps the parent answer identity', () => {
  const source = fs.readFileSync(path.resolve(__dirname, '../entry/src/main/ets/components/RecipeApprovalSummary.ets'), 'utf8');
  const output = ts.transpileModule(source, { compilerOptions: {
    module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022,
  } }).outputText;
  const exports = {};
  vm.runInNewContext(output, { exports }, { filename: 'RecipeApprovalSummary.ets' });
  const primitive = { type: 'tool', toolCallId: 'child-step', toolName: 'terminal_execute',
    input: '{"profile_id":"p","command":"echo resolved"}', output: [], approvalState: { type: 'pending' },
    metadata: { terminal_protocol: 'remote_ssh', terminal_target_label: 'user@frozen-host:22' } };
  const descriptor = { hash: 'canonical-hash', canonicalJSON: '{"name":"check"}', manifest: {
    name: 'check', version: '1', steps: [{ id: 'ssh', tool: 'terminal_execute' }],
  } };
  const parent = { type: 'tool', toolCallId: 'parent-call', toolName: 'recipe__check', input: '{"host":"model-input"}',
    output: [], approvalState: { type: 'pending' }, metadata: { recipe_v1: {
      kind: 'run', descriptor, nextIndex: 0, phase: 'awaiting_approval', pendingStep: primitive,
    } } };
  const view = exports.recipeApprovalView(parent);
  assert.equal(view.title, 'Recipe check · 1');
  assert.equal(view.stepLabel, '步骤 1/1 · ssh · terminal_execute');
  assert.equal(view.pendingStep.input, primitive.input);
  assert.equal(view.pendingStep.metadata.terminal_target_label, 'user@frozen-host:22');
  assert.equal(exports.recipeAskUserPart(parent), null);
  primitive.toolName = 'ask_user';
  descriptor.manifest.steps[0] = { id: 'ask', tool: 'ask_user' };
  primitive.input = '{"questions":[{"id":"decision","question":"继续？"}]}';
  const ask = exports.recipeAskUserPart(parent);
  assert.equal(ask.toolCallId, 'parent-call');
  assert.equal(ask.input, primitive.input);
  assert.equal(ask.approvalState.type, 'pending');
  parent.approvalState = { type: 'answered', answer: '{"answers":{"decision":"继续"}}' };
  assert.equal(exports.recipeAskUserPart(parent).approvalState.answer, parent.approvalState.answer);
  assert.equal(primitive.toolCallId, 'child-step');
  assert.equal(primitive.approvalState.type, 'pending');
  const installed = exports.recipeApprovalView({ ...parent, toolName: 'recipe_import', metadata: {
    recipe_v1: { kind: 'import', preview: { workspacePath: 'recipe.json', candidate: descriptor, baseHash: 'installed-hash' } },
  } });
  assert.equal(installed.hash, 'canonical-hash');
  assert.equal(installed.baseHash, 'installed-hash');
  assert.equal(installed.canonicalJSON, descriptor.canonicalJSON);
});
