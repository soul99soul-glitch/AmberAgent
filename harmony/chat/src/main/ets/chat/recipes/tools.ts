import type { JsonObject, JsonValue } from '../json.ts';
import type { UIMessagePart } from '../message.ts';
import type { AgentTool } from '../tool.ts';
import { makeAgentTool, makeInputSchemaObj } from '../tool.ts';
import { sanitizedToolFailureMessage } from '../tool_dispatcher.ts';
import type { InstalledRecipe, RecipeDescriptor } from './models.ts';
import type { RecipeStore } from './ports.ts';
import { recipeEnvelope, RecipeValidationError, validateRecipe } from './validation.ts';

export interface RecipeToolDeps { store: RecipeStore; installed: InstalledRecipe[]; primitives: AgentTool[]; }
const text = (value: JsonObject): UIMessagePart[] => [{ type: 'text', text: JSON.stringify(value), metadata: null }];
const objectInput = (value: JsonValue): JsonObject => {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new Error('Tool arguments must be a JSON object.');
  return value;
};
const stringInput = (value: JsonObject, key: string): string => {
  const field = value[key];
  if (typeof field !== 'string' || field.trim().length === 0) throw new Error(key + ' must be a nonempty string.');
  return field;
};
const errorResult = (error: Error): UIMessagePart[] => text({ status: 'failed',
  error_code: (error as RecipeValidationError).code ?? 'invalid_arguments', message: sanitizedToolFailureMessage(error),
  issues: error instanceof RecipeValidationError ? JSON.parse(JSON.stringify(error.issues)) as JsonValue : [] });
const descriptorJSON = (descriptor: RecipeDescriptor, primitives: AgentTool[]): JsonObject => ({
  name: descriptor.manifest.name, version: descriptor.manifest.version, description: descriptor.manifest.description,
  hash: descriptor.hash, inputs: descriptor.manifest.inputs, steps: JSON.parse(JSON.stringify(descriptor.manifest.steps)) as JsonValue,
  issues: JSON.parse(JSON.stringify(validateRecipe(descriptor.manifest, primitives))) as JsonValue,
  envelope: JSON.parse(JSON.stringify(recipeEnvelope(descriptor.manifest, primitives))) as JsonValue,
});
const adapterRequired = async (): Promise<UIMessagePart[]> => text({ status: 'failed', error_code: 'recipe_adapter_required',
  message: 'Recipe execution requires its pinned preview and individual primitive approval through RecipeLoopAdapter.' });
const guarded = (run: (input: JsonObject) => Promise<UIMessagePart[]>): ((value: JsonValue) => Promise<UIMessagePart[]>) =>
  async (value) => { try { return await run(objectInput(value)); } catch (error) { return errorResult(error as Error); } };
const stringProperty: JsonObject = { type: 'string' };

export const createRecipeTools = (deps: RecipeToolDeps): AgentTool[] => {
  const primitives = deps.primitives.slice();
  const tools: AgentTool[] = [
    makeAgentTool({ name: 'recipes_list', description: 'List installed Recipes, hashes, enabled state and current primitive availability.',
      allowsAutoApproval: false, parameters: () => makeInputSchemaObj({}), execute: guarded(async () => {
        const recipes = await deps.store.listInstalled();
        return text({ recipes: recipes.map((entry) => ({ ...descriptorJSON(entry.descriptor, primitives), enabled: entry.enabled })) });
      }) }),
    makeAgentTool({ name: 'recipe_validate', description: 'Validate exactly one installed name or Workspace recipe.json path without installing it.',
      allowsAutoApproval: false, parameters: () => makeInputSchemaObj({ name: stringProperty, workspace_path: stringProperty }),
      execute: guarded(async (input) => {
        const hasName = input['name'] !== undefined, hasPath = input['workspace_path'] !== undefined;
        if (hasName === hasPath) throw new Error('Specify exactly one of name or workspace_path.');
        if (hasPath) {
          const preview = await deps.store.prepareImport(stringInput(input, 'workspace_path'), primitives);
          return text({ ...descriptorJSON(preview.candidate, primitives), base_hash: preview.baseHash, workspace_path: preview.workspacePath });
        }
        const name = stringInput(input, 'name');
        const installed = (await deps.store.listInstalled()).find((entry) => entry.descriptor.manifest.name === name);
        if (!installed) return text({ status: 'failed', error_code: 'missing_recipe', message: 'Recipe is not installed.' });
        return text({ ...descriptorJSON(installed.descriptor, primitives), enabled: installed.enabled });
      }) }),
    makeAgentTool({ name: 'recipe_import', description: 'Preview and install a Workspace recipe.json. Approval binds its candidate and installed base hashes.',
      needsApproval: true, allowsAutoApproval: false, parameters: () => makeInputSchemaObj({ workspace_path: stringProperty }, ['workspace_path']),
      execute: adapterRequired }),
  ];
  for (const name of ['recipe_enable', 'recipe_disable', 'recipe_delete']) {
    tools.push(makeAgentTool({ name, description: name === 'recipe_delete' ? 'Delete the installed Recipe only if expected_hash still matches.' :
      'Change installed Recipe enabled state only if expected_hash still matches.', needsApproval: true, allowsAutoApproval: false,
      parameters: () => makeInputSchemaObj({ name: stringProperty, expected_hash: stringProperty }, ['name', 'expected_hash']),
      execute: guarded(async (input) => {
        const recipe = stringInput(input, 'name'), hash = stringInput(input, 'expected_hash');
        if (name === 'recipe_delete') await deps.store.remove(recipe, hash);
        else await deps.store.setEnabled(recipe, hash, name === 'recipe_enable');
        return text({ status: 'succeeded', name: recipe, hash, operation: name });
      }) }));
  }
  for (const entry of deps.installed) {
    if (!entry.enabled || validateRecipe(entry.descriptor.manifest, primitives).length > 0) continue;
    const descriptor: RecipeDescriptor = JSON.parse(JSON.stringify(entry.descriptor)) as RecipeDescriptor;
    const properties: JsonObject = {};
    for (const name of Object.keys(descriptor.manifest.inputs)) properties[name] = { type: descriptor.manifest.inputs[name]! };
    tools.push(makeAgentTool({ name: 'recipe__' + descriptor.manifest.name,
      description: descriptor.manifest.description + ' (version ' + descriptor.manifest.version + ', hash ' + descriptor.hash +
        '). Runs up to eight declared primitives; each step uses its own current approval policy.',
      recipeEnvelope: recipeEnvelope(descriptor.manifest, primitives), needsApproval: false, allowsAutoApproval: false,
      parameters: () => makeInputSchemaObj(properties, Object.keys(descriptor.manifest.inputs)), execute: adapterRequired }));
  }
  return tools;
};
