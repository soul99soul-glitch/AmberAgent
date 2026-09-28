import type { JsonObject, JsonValue } from '../json.ts';
import type { AgentTool } from '../tool.ts';
import { toolInvocationPolicy, toolMutatesState, toolRiskProfile } from '../tool_policy.ts';
import { RECIPE_MAX_STEPS, RECIPE_MAX_TIMEOUT_SECONDS, RECIPE_SCHEMA } from './models.ts';
import type { RecipeBinding, RecipeEnvelope, RecipeInputType, RecipeIssue, RecipeManifest, RecipeStep } from './models.ts';

export class RecipeValidationError extends Error {
  readonly code: string;
  readonly issues: RecipeIssue[];
  constructor(code: string, issues: RecipeIssue[]) {
    super(issues.map(issue => `${issue.path}: ${issue.message}`).join('\n'));
    this.name = 'RecipeValidationError'; this.code = code; this.issues = issues;
  }
}

const issue = (code: string, path: string, message: string): RecipeIssue => ({ code, path, message });
const malformed = (path: string, expected: string): never => {
  throw new RecipeValidationError('invalid_manifest', [issue('invalidManifestJSON', path, `需要${expected}。`)]);
};
const objectAt = (value: JsonValue | undefined, path: string): JsonObject => {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return malformed(path, ' JSON 对象');
  return value;
};
const stringAt = (value: JsonValue | undefined, path: string): string => {
  if (typeof value !== 'string') return malformed(path, '字符串');
  return value;
};
const finiteJSON = (value: JsonValue, path: string): void => {
  if (typeof value === 'number' && !Number.isFinite(value)) malformed(path, '有限数值');
  if (Array.isArray(value)) value.forEach((entry, index) => finiteJSON(entry, `${path}[${index}]`));
  else if (value !== null && typeof value === 'object') {
    for (const key of Object.keys(value)) finiteJSON(value[key], `${path}.${key}`);
  }
};

export const decodeRecipe = (text: string): RecipeManifest => {
  let parsed: JsonValue;
  try { parsed = JSON.parse(text) as JsonValue; }
  catch { return malformed('', '合法的 recipe.json'); }
  const root: JsonObject = objectAt(parsed, '');
  const inputs: JsonObject = objectAt(root['inputs'], 'inputs');
  for (const key of Object.keys(inputs)) {
    if (inputs[key] !== 'string' && inputs[key] !== 'number' && inputs[key] !== 'boolean') {
      malformed(`inputs.${key}`, ' string、number 或 boolean 类型');
    }
  }
  const rawSteps: JsonValue | undefined = root['steps'];
  if (!Array.isArray(rawSteps)) return malformed('steps', '步骤数组');
  const steps: RecipeStep[] = rawSteps.map((raw, index): RecipeStep => {
    const path: string = `steps[${index}]`;
    const value: JsonObject = objectAt(raw, path);
    const args: JsonObject = objectAt(value['arguments'], `${path}.arguments`);
    finiteJSON(args, `${path}.arguments`);
    const step: RecipeStep = { id: stringAt(value['id'], `${path}.id`),
      tool: stringAt(value['tool'], `${path}.tool`), arguments: args };
    const timeout: JsonValue | undefined = value['timeoutSeconds'];
    if (timeout !== undefined && timeout !== null) {
      if (typeof timeout !== 'number' || !Number.isInteger(timeout)) malformed(`${path}.timeoutSeconds`, '整数秒');
      step.timeoutSeconds = timeout as number;
    }
    return step;
  });
  const outputs: JsonObject = objectAt(root['outputs'], 'outputs');
  finiteJSON(outputs, 'outputs');
  return { schema: stringAt(root['schema'], 'schema'), name: stringAt(root['name'], 'name'),
    version: stringAt(root['version'], 'version'), description: stringAt(root['description'], 'description'),
    inputs: inputs as Record<string, RecipeInputType>, steps, outputs };
};

const fullMatch = (pattern: RegExp, value: string): RegExpMatchArray | null => {
  const match: RegExpMatchArray | null = value.match(pattern);
  return match !== null && match[0] === value ? match : null;
};
const memberName = (name: string): boolean => fullMatch(/^[a-z][a-z0-9_]{0,31}$/, name) !== null;

export const parseRecipeBinding = (value: JsonValue): RecipeBinding | null => {
  if (typeof value !== 'string') return null;
  const input: RegExpMatchArray | null = fullMatch(/^\$\{input\.([a-z][a-z0-9_]{0,31})\}$/, value);
  if (input !== null) return { kind: 'input', name: input[1], field: null };
  const step: RegExpMatchArray | null = fullMatch(/^\$\{step\.([a-z][a-z0-9_]{0,31})\.output\.([A-Za-z_][A-Za-z0-9_]*)\}$/, value);
  return step === null ? null : { kind: 'step', name: step[1], field: step[2] };
};

const malformedBindings = (value: JsonValue, path: string, issues: RecipeIssue[]): void => {
  if (typeof value === 'string' && value.startsWith('${') && parseRecipeBinding(value) === null) {
    issues.push(issue('invalidBindingSyntax', path, '绑定必须是完整的 ${input.name} 或 ${step.id.output.field} 字符串。'));
  } else if (Array.isArray(value)) value.forEach((entry, index) => malformedBindings(entry, `${path}[${index}]`, issues));
  else if (value !== null && typeof value === 'object') {
    for (const key of Object.keys(value).sort()) malformedBindings(value[key], `${path}.${key}`, issues);
  }
};
const managementTools: string[] = ['recipes_list', 'recipe_validate', 'recipe_import', 'recipe_enable', 'recipe_disable', 'recipe_delete'];

export const validateRecipe = (manifest: RecipeManifest, primitives: AgentTool[]): RecipeIssue[] => {
  const issues: RecipeIssue[] = [];
  if (manifest.schema !== RECIPE_SCHEMA) issues.push(issue('schemaMismatch', 'schema', `需要 ${RECIPE_SCHEMA}。`));
  if (fullMatch(/^[a-z][a-z0-9_]{1,31}$/, manifest.name) === null) {
    issues.push(issue('invalidName', 'name', 'name 必须是 2–32 位小写字母、数字或下划线，并以字母开头。'));
  }
  if (manifest.version.length === 0 || /\s/.test(manifest.version)) issues.push(issue('invalidVersion', 'version', 'version 不能为空或包含空白。'));
  if (manifest.description.trim().length === 0) issues.push(issue('emptyDescription', 'description', 'description 不能为空。'));
  const inputNames: string[] = Object.keys(manifest.inputs);
  for (const name of inputNames.slice().sort()) {
    if (!memberName(name)) issues.push(issue('invalidInputName', `inputs.${name}`, '输入名须为 1–32 位小写标识符。'));
    else if (name === 'display_title') issues.push(issue('reservedInputName', `inputs.${name}`, 'display_title 是宿主保留的显示标题，不能声明为 Recipe 输入。'));
  }
  if (manifest.steps.length === 0) issues.push(issue('noSteps', 'steps', 'Recipe 至少需要一个步骤。'));
  if (manifest.steps.length > RECIPE_MAX_STEPS) issues.push(issue('stepLimitExceeded', 'steps', `最多 ${RECIPE_MAX_STEPS} 个步骤。`));
  const available: Set<string> = new Set(primitives.map(tool => tool.name));
  const seenIds: Set<string> = new Set();
  manifest.steps.forEach((step, index) => {
    const path: string = `steps[${index}]`;
    if (!memberName(step.id)) issues.push(issue('invalidStepId', `${path}.id`, '步骤 id 须为 1–32 位小写标识符。'));
    else if (seenIds.has(step.id)) issues.push(issue('duplicateStepId', `${path}.id`, `步骤 id「${step.id}」重复。`));
    else seenIds.add(step.id);
    if (step.tool.length === 0 || /\s/.test(step.tool)) issues.push(issue('invalidToolName', `${path}.tool`, 'tool 不能为空或包含空白。'));
    else if (step.tool.startsWith('recipe__') || step.tool.startsWith('plugin__') || managementTools.includes(step.tool)) {
      issues.push(issue('recipeToolReference', `${path}.tool`, '步骤只能引用 primitive，不能引用 Recipe、插件或 Recipe 管理工具。'));
    } else if (!available.has(step.tool)) issues.push(issue('unknownTool', `${path}.tool`, `当前 primitive 目录没有「${step.tool}」。`));
    if (step.timeoutSeconds !== undefined && (!Number.isInteger(step.timeoutSeconds)
      || step.timeoutSeconds < 1 || step.timeoutSeconds > RECIPE_MAX_TIMEOUT_SECONDS)) {
      issues.push(issue('invalidTimeout', `${path}.timeoutSeconds`, 'timeoutSeconds 必须是 1–600 的整数。'));
    }
    for (const key of Object.keys(step.arguments).sort()) {
      const value: JsonValue = step.arguments[key];
      const argPath: string = `${path}.arguments.${key}`;
      const binding: RecipeBinding | null = parseRecipeBinding(value);
      if (binding === null) malformedBindings(value, argPath, issues);
      else if (binding.kind === 'input') {
        if (!inputNames.includes(binding.name)) issues.push(issue('unresolvedInputBinding', argPath, `未声明输入「${binding.name}」。`));
      } else {
        const sourceIndex: number = manifest.steps.findIndex(source => source.id === binding.name);
        if (sourceIndex < 0) issues.push(issue('unresolvedStepBinding', argPath, `没有步骤「${binding.name}」。`));
        else if (sourceIndex >= index) issues.push(issue('invalidStepReference', argPath, '步骤只能引用前序步骤的输出。'));
      }
    }
  });
  for (const name of Object.keys(manifest.outputs).sort()) {
    const path: string = `outputs.${name}`;
    if (!memberName(name)) issues.push(issue('invalidOutputName', path, '输出名须为 1–32 位小写标识符。'));
    const binding: RecipeBinding | null = parseRecipeBinding(manifest.outputs[name]);
    if (binding === null || binding.kind !== 'step') issues.push(issue('outputMustBeBinding', path, '输出必须绑定 ${step.id.output.field}。'));
    else if (!seenIds.has(binding.name)) issues.push(issue('unresolvedOutputStep', path, `没有步骤「${binding.name}」。`));
  }
  return issues;
};

export const normalizeRecipeInputs = (manifest: RecipeManifest, raw: JsonValue): JsonObject => {
  const issues: RecipeIssue[] = [];
  if (raw === null || typeof raw !== 'object' || Array.isArray(raw)) {
    throw new RecipeValidationError('input_invalid', [issue('inputInvalid', 'inputs', '调用参数必须是 JSON 对象。')]);
  }
  const actual: string[] = Object.keys(raw).filter(key => key !== 'display_title');
  const required: string[] = Object.keys(manifest.inputs);
  const normalized: JsonObject = {};
  for (const name of required) {
    if (!actual.includes(name)) issues.push(issue('inputInvalid', `inputs.${name}`, '缺少必需输入。'));
    else {
      const value: JsonValue = raw[name];
      const type: RecipeInputType = manifest.inputs[name];
      if (typeof value !== type || (type === 'number' && !Number.isFinite(value as number))) {
        issues.push(issue('inputInvalid', `inputs.${name}`, `需要 ${type} 类型${type === 'number' ? '的有限数值' : ''}。`));
      } else normalized[name] = value;
    }
  }
  for (const name of actual) if (!required.includes(name)) issues.push(issue('inputInvalid', `inputs.${name}`, '未声明的额外输入。'));
  if (issues.length > 0) throw new RecipeValidationError('input_invalid', issues);
  return normalized;
};

// Match iOS JSONEncoder.sortedKeys, including its default slash escaping.
const canonicalString = (value: string): string => JSON.stringify(value).replace(/\//g, '\\/');
// Serialize directly so JSON literal keys such as __proto__ remain own data keys.
const canonicalJSONValue = (value: JsonValue): string => {
  if (typeof value === 'string') return canonicalString(value);
  if (Array.isArray(value)) return `[${value.map(entry => canonicalJSONValue(entry)).join(',')}]`;
  if (value !== null && typeof value === 'object') {
    return `{${Object.keys(value).sort().map(key => `${canonicalString(key)}:${canonicalJSONValue(value[key])}`).join(',')}}`;
  }
  return JSON.stringify(value);
};
export const canonicalRecipeJSON = (manifest: RecipeManifest): string => {
  const steps: JsonObject[] = manifest.steps.map((step): JsonObject => {
    const fields: JsonObject = { id: step.id, tool: step.tool, arguments: step.arguments };
    if (step.timeoutSeconds !== undefined) fields['timeoutSeconds'] = step.timeoutSeconds;
    return fields;
  });
  const fields: JsonObject = { schema: manifest.schema, name: manifest.name, version: manifest.version,
    description: manifest.description, inputs: manifest.inputs, steps, outputs: manifest.outputs };
  finiteJSON(fields, '');
  return canonicalJSONValue(fields);
};

const riskRank = (risk: 'normal' | 'sensitive' | 'high'): number => risk === 'high' ? 2 : risk === 'sensitive' ? 1 : 0;
export const recipeEnvelope = (manifest: RecipeManifest, primitives: AgentTool[]): RecipeEnvelope => {
  const envelope: RecipeEnvelope = { tools: [], mutates: false, needsApproval: false, risk: 'normal' };
  for (const step of manifest.steps) {
    if (!envelope.tools.includes(step.tool)) envelope.tools.push(step.tool);
    const tool: AgentTool | undefined = primitives.find(entry => entry.name === step.tool);
    if (tool === undefined) { envelope.mutates = true; envelope.needsApproval = true; envelope.risk = 'high'; continue; }
    const policy = toolInvocationPolicy(tool, step.arguments);
    const baseRisk = toolRiskProfile(step.tool).risk;
    envelope.mutates = envelope.mutates || toolMutatesState(step.tool) || policy.mutates;
    envelope.needsApproval = envelope.needsApproval || tool.mandatoryApproval || tool.needsApproval
      || policy.needsApproval || policy.alwaysAsk || baseRisk === 'high';
    for (const risk of [baseRisk, policy.risk]) if (riskRank(risk) > riskRank(envelope.risk)) envelope.risk = risk;
  }
  return envelope;
};
