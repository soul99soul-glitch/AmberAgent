import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { JsonObject } from '../main/ets/chat/json.ts';
import { makeAgentTool } from '../main/ets/chat/tool.ts';
import type { RecipeManifest } from '../main/ets/chat/recipes/models.ts';
import { canonicalRecipeJSON, decodeRecipe, normalizeRecipeInputs, parseRecipeBinding,
  recipeEnvelope, RecipeValidationError, validateRecipe } from '../main/ets/chat/recipes/validation.ts';

const primitives = ['file_read', 'python_execute', 'file_write', 'http_request'].map(name => makeAgentTool({
  name, description: name, needsApproval: name === 'python_execute', execute: async () => [],
}));
const fixture = (): RecipeManifest => ({ schema: 'amber.recipe.v1', name: 'normalize_json', version: '1.0.0',
  description: '排序 JSON 文字。', inputs: { input_path: 'string', output_path: 'string', pretty: 'boolean', count: 'number' },
  steps: [
    { id: 'read', tool: 'file_read', arguments: { path: '${input.input_path}' } },
    { id: 'compute', tool: 'python_execute', arguments: { code: 'import json;print(json.loads(stdin))',
      stdin: '${step.read.output.content}', literal: { z: '${input.not_declared}', a: [2, 1, '中😀'] } } },
    { id: 'save', tool: 'file_write', arguments: { path: '${input.output_path}', content: '${step.compute.output.stdout}' }, timeoutSeconds: 600 },
  ], outputs: { saved_path: '${step.save.output.path}' } });
const issueCodes = (manifest: RecipeManifest): string[] => validateRecipe(manifest, primitives).map(issue => issue.code);

test('one valid Recipe fixture preserves literal data, canonical schema and strict top-level bindings', () => {
  const raw = { ...fixture(), runtime: 'discarded', steps: fixture().steps.map(step => ({ ...step, unknown: 'discarded' })) };
  const decoded = decodeRecipe(JSON.stringify(raw));
  assert.deepEqual(validateRecipe(decoded, primitives), []);
  const canonical = canonicalRecipeJSON(decoded);
  assert.ok(!canonical.includes('discarded'));
  assert.equal(canonicalRecipeJSON(decodeRecipe(canonical)), canonical);
  assert.deepEqual(decoded.steps[1].arguments['literal'], { z: '${input.not_declared}', a: [2, 1, '中😀'] });
  assert.deepEqual(parseRecipeBinding('${input.input_path}'), { kind: 'input', name: 'input_path', field: null });
  assert.deepEqual(parseRecipeBinding('${step.read.output.Content_1}'), { kind: 'step', name: 'read', field: 'Content_1' });
  assert.equal(parseRecipeBinding('prefix ${input.input_path}'), null);
  assert.equal(parseRecipeBinding('${input.input_path}\n'), null);
  const special = decodeRecipe(JSON.stringify(fixture()).replace('"literal":{', '"literal":{"__proto__":{"kept":true},'));
  assert.ok(canonicalRecipeJSON(special).includes('"__proto__":{"kept":true}'));
  assert.equal(Object.hasOwn((JSON.parse(canonicalRecipeJSON(special)).steps[1].arguments.literal), '__proto__'), true);
});

test('decode catches shape/type failures and optional null timeout follows iOS omission', () => {
  for (const raw of [null, { ...fixture(), inputs: null }, { ...fixture(), inputs: { value: 'array' } },
    { ...fixture(), steps: [{ id: 'a', tool: 'file_read', arguments: [], timeoutSeconds: 1 }] },
    { ...fixture(), steps: [{ id: 'a', tool: 'file_read', arguments: {}, timeoutSeconds: 1.5 }] }]) {
    assert.throws(() => decodeRecipe(JSON.stringify(raw)), RecipeValidationError);
  }
  const missing: JsonObject = JSON.parse(JSON.stringify(fixture())); delete missing['outputs'];
  assert.throws(() => decodeRecipe(JSON.stringify(missing)), (error: unknown) => error instanceof RecipeValidationError && error.issues[0].path === 'outputs');
  assert.throws(() => decodeRecipe('{bad'), RecipeValidationError);
  assert.throws(() => decodeRecipe(JSON.stringify(fixture()).replace('"path":"${input.input_path}"', '"path":1e400')), RecipeValidationError);
  const nullable = fixture();
  const text = JSON.stringify(nullable).replace('"timeoutSeconds":600', '"timeoutSeconds":null');
  assert.equal(decodeRecipe(text).steps[2].timeoutSeconds, undefined);
  assert.ok(!canonicalRecipeJSON(decodeRecipe(text)).includes('timeoutSeconds'));
});

test('semantic fixture covers actual name, step/tool/budget and binding failure branches', () => {
  const variations: Array<{ manifest: RecipeManifest; code: string }> = [
    { manifest: { ...fixture(), schema: 'v2' }, code: 'schemaMismatch' },
    { manifest: { ...fixture(), name: 'a\n' }, code: 'invalidName' },
    { manifest: { ...fixture(), version: '1 0' }, code: 'invalidVersion' },
    { manifest: { ...fixture(), description: ' \n' }, code: 'emptyDescription' },
    { manifest: { ...fixture(), inputs: { 'bad-name': 'string' } }, code: 'invalidInputName' },
    { manifest: { ...fixture(), steps: [] }, code: 'noSteps' },
    { manifest: { ...fixture(), steps: Array.from({ length: 9 }, (_, index) => ({ id: 'a' + index, tool: 'file_read', arguments: {} })) }, code: 'stepLimitExceeded' },
    { manifest: { ...fixture(), steps: [{ id: 'a', tool: 'file_read', arguments: {} }, { id: 'a', tool: 'file_read', arguments: {} }] }, code: 'duplicateStepId' },
    { manifest: { ...fixture(), steps: [{ id: 'a', tool: 'recipe__nested', arguments: {} }] }, code: 'recipeToolReference' },
    { manifest: { ...fixture(), steps: [{ id: 'a', tool: 'recipe_import', arguments: {} }] }, code: 'recipeToolReference' },
    { manifest: { ...fixture(), steps: [{ id: 'a', tool: 'missing', arguments: {} }] }, code: 'unknownTool' },
    { manifest: { ...fixture(), steps: [{ id: 'a', tool: 'file_read', arguments: {}, timeoutSeconds: 0 }] }, code: 'invalidTimeout' },
    { manifest: { ...fixture(), steps: [{ id: 'a', tool: 'file_read', arguments: { path: '${input.missing}' } }] }, code: 'unresolvedInputBinding' },
    { manifest: { ...fixture(), steps: [{ id: 'a', tool: 'file_read', arguments: { path: '${step.a.output.path}' } }] }, code: 'invalidStepReference' },
    { manifest: { ...fixture(), steps: [{ id: 'a', tool: 'file_read', arguments: { path: { deep: ['${step.a.output.x.y}'] } } }] }, code: 'invalidBindingSyntax' },
    { manifest: { ...fixture(), outputs: { value: '${input.input_path}' } }, code: 'outputMustBeBinding' },
    { manifest: { ...fixture(), outputs: { value: '${step.missing.output.path}' } }, code: 'unresolvedOutputStep' },
  ];
  for (const variation of variations) assert.ok(issueCodes(variation.manifest).includes(variation.code), variation.code);
  const shortMember = { ...fixture(), inputs: { a: 'string' as const }, steps: [{ id: 'b', tool: 'file_read', arguments: { path: '${input.a}' } }],
    outputs: { c: '${step.b.output.path}' } };
  assert.deepEqual(validateRecipe(shortMember, primitives), []);
});

test('strict call input fixture strips host title, rejects missing/extra/mismatched/nonfinite values', () => {
  const manifest = fixture();
  const inputs = { input_path: '/workspace/input.json', output_path: '/workspace/output.json', pretty: true, count: 1 };
  assert.deepEqual(normalizeRecipeInputs(manifest, { ...inputs, display_title: '宿主标题' }), inputs);
  for (const invalid of [null, [], { ...inputs, extra: true }, { ...inputs, pretty: 'true' },
    { ...inputs, count: Infinity }, { input_path: 'a', output_path: 'b', count: 1 }]) {
    assert.throws(() => normalizeRecipeInputs(manifest, invalid), (error: unknown) => error instanceof RecipeValidationError && error.code === 'input_invalid');
  }
  assert.deepEqual(normalizeRecipeInputs({ ...manifest, inputs: {} }, { display_title: '仅元数据' }), {});
});

test('manifest rejects host-reserved display_title input before installation', () => {
  const manifest: RecipeManifest = { ...fixture(), inputs: { ...fixture().inputs, display_title: 'string' } };
  assert.ok(validateRecipe(manifest, primitives).some(issue =>
    issue.code === 'reservedInputName' && issue.path === 'inputs.display_title'));
});

test('permission envelope uses actual primitives and conservatively preserves policy risk', () => {
  assert.deepEqual(recipeEnvelope(fixture(), primitives), {
    tools: ['file_read', 'python_execute', 'file_write'], mutates: true, needsApproval: true, risk: 'sensitive',
  });
  const readOnly = { ...fixture(), steps: [{ id: 'a', tool: 'file_read', arguments: { path: '/workspace/x' } }] };
  assert.deepEqual(recipeEnvelope(readOnly, primitives), { tools: ['file_read'], mutates: false, needsApproval: false, risk: 'normal' });
  const http = { ...fixture(), steps: [{ id: 'a', tool: 'http_request', arguments: { method: 'GET', url: '${input.input_path}' } }] };
  assert.equal(recipeEnvelope(http, primitives).risk, 'high');
  assert.equal(recipeEnvelope(http, primitives).needsApproval, true);
});
