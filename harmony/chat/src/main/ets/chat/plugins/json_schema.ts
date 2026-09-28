import type { JsonObject, JsonValue } from '../json.ts';
import type { RecipeInputType } from '../recipes/models.ts';
import type { PluginIssue, PluginOutputType } from './models.ts';

const keywords: string[] = ['type', 'properties', 'required', 'items', 'additionalProperties', 'enum', 'description'];
const types: string[] = ['string', 'number', 'integer', 'boolean', 'object', 'array', 'null'];
const problem = (path: string, message: string): PluginIssue => ({ code: 'invalidSchema', path, message });
export const jsonObject = (value: JsonValue | undefined): JsonObject | null =>
  value !== null && value !== undefined && typeof value === 'object' && !Array.isArray(value) ? value : null;
const own = (value: JsonObject, key: string): boolean => Object.prototype.hasOwnProperty.call(value, key);
export const pluginSchemaTypes = (schema: JsonObject): string[] | null => {
  const raw: JsonValue | undefined = schema['type'];
  return typeof raw === 'string' ? [raw] : Array.isArray(raw) ? raw.filter(value => typeof value === 'string') as string[] : null;
};

export const pluginSchemaIssues = (value: JsonValue, path: string = '$'): PluginIssue[] => {
  const node: JsonObject | null = jsonObject(value);
  if (node === null) return [problem(path, 'Schema 节点必须是 JSON object。')];
  const issues: PluginIssue[] = [];
  for (const key of Object.keys(node)) if (!keywords.includes(key)) issues.push(problem(`${path}.${key}`, '不支持的 JSON Schema 关键字。'));
  const rawType: JsonValue | undefined = node['type'];
  if (rawType !== undefined) {
    const parsed: string[] | null = pluginSchemaTypes(node);
    if (parsed === null || parsed.length === 0 || (Array.isArray(rawType) && parsed.length !== rawType.length)
      || new Set(parsed).size !== parsed.length || parsed.some(type => !types.includes(type))) {
      issues.push(problem(`${path}.type`, 'type 必须是有效类型或非空且无重复的类型数组。'));
    }
  }
  const properties: JsonValue | undefined = node['properties'];
  if (properties !== undefined) {
    const fields: JsonObject | null = jsonObject(properties);
    if (fields === null) issues.push(problem(`${path}.properties`, 'properties 必须是 JSON object。'));
    else for (const key of Object.keys(fields).sort()) issues.push(...pluginSchemaIssues(fields[key], `${path}.properties.${key}`));
    const allowed: string[] | null = pluginSchemaTypes(node);
    if (allowed !== null && !allowed.includes('object')) issues.push(problem(`${path}.properties`, '带 properties 的节点必须允许 object。'));
  }
  const required: JsonValue | undefined = node['required'];
  if (required !== undefined && (!Array.isArray(required) || required.some(value => typeof value !== 'string')
    || new Set(required).size !== required.length)) issues.push(problem(`${path}.required`, 'required 必须是无重复的字符串数组。'));
  const items: JsonValue | undefined = node['items'];
  if (items !== undefined) issues.push(...pluginSchemaIssues(items, `${path}.items`));
  if (node['additionalProperties'] !== undefined && typeof node['additionalProperties'] !== 'boolean') {
    issues.push(problem(`${path}.additionalProperties`, 'additionalProperties 只支持 boolean。'));
  }
  if (node['enum'] !== undefined) {
    if (!Array.isArray(node['enum']) || node['enum'].length === 0) issues.push(problem(`${path}.enum`, 'enum 必须是非空数组。'));
    else issues.push(...pluginSchemaValueIssues({}, node['enum'], `${path}.enum`));
  }
  if (node['description'] !== undefined && typeof node['description'] !== 'string') issues.push(problem(`${path}.description`, 'description 必须是字符串。'));
  return issues;
};
export const pluginInputSchemaIssues = (schema: JsonObject, path: string = '$'): PluginIssue[] => {
  const issues: PluginIssue[] = pluginSchemaIssues(schema, path);
  const allowed: string[] | null = pluginSchemaTypes(schema);
  const properties: JsonObject | null = jsonObject(schema['properties']);
  if ((allowed === null && properties === null) || (allowed !== null && (allowed.length !== 1 || allowed[0] !== 'object'))) {
    issues.push(problem(`${path}.type`, 'input_schema 根节点只能允许 object。'));
  }
  if (properties === null) issues.push(problem(`${path}.properties`, 'input_schema 必须明确声明 properties。'));
  else {
    for (const name of Object.keys(properties)) if (/^[a-z][a-z0-9_]{0,31}$/.exec(name)?.[0] !== name || name.includes('__') || name === 'display_title') {
      issues.push(problem(`${path}.properties.${name}`, '输入名无效或保留。'));
    }
    const required: JsonValue | undefined = schema['required'];
    if (Array.isArray(required)) for (const name of required) if (typeof name === 'string' && !own(properties, name)) {
      issues.push(problem(`${path}.required`, `required 字段「${name}」未在 properties 中声明。`));
    }
  }
  return issues;
};
export const legacyPluginInputSchema = (inputs: Record<string, RecipeInputType>): JsonObject => {
  const properties: JsonObject = {};
  for (const name of Object.keys(inputs).sort()) properties[name] = { type: inputs[name] };
  return { type: 'object', properties, required: Object.keys(inputs).sort(), additionalProperties: false };
};
export const pluginOutputSchemaAllows = (schema: JsonObject, output: PluginOutputType): boolean => {
  const allowed: string[] | null = pluginSchemaTypes(schema);
  return output === 'json' || allowed === null || allowed.includes(output) || (output === 'number' && allowed.includes('integer'));
};
export const pluginJSONEqual = (left: JsonValue, right: JsonValue): boolean => {
  if (left === right) return true;
  if (Array.isArray(left) || Array.isArray(right)) return Array.isArray(left) && Array.isArray(right)
    && left.length === right.length && left.every((value, index) => pluginJSONEqual(value, right[index]));
  const a: JsonObject | null = jsonObject(left); const b: JsonObject | null = jsonObject(right);
  return a !== null && b !== null && Object.keys(a).length === Object.keys(b).length
    && Object.keys(a).every(key => own(b, key) && pluginJSONEqual(a[key], b[key]));
};
const matches = (value: JsonValue, type: string): boolean => {
  if (type === 'null') return value === null;
  if (type === 'array') return Array.isArray(value);
  if (type === 'object') return jsonObject(value) !== null;
  if (type === 'number' || type === 'integer') return typeof value === 'number' && Number.isFinite(value)
    && (type !== 'integer' || Number.isInteger(value));
  return typeof value === type;
};
export const pluginSchemaValueIssues = (schema: JsonObject, value: JsonValue, path: string = '$'): PluginIssue[] => {
  const issues: PluginIssue[] = [];
  if (typeof value === 'number' && !Number.isFinite(value)) return [problem(path, '值必须是有限数值。')];
  const enumeration: JsonValue | undefined = schema['enum'];
  if (Array.isArray(enumeration) && !enumeration.some(allowed => pluginJSONEqual(allowed, value))) issues.push(problem(path, '值不在 enum 允许范围内。'));
  const allowed: string[] | null = pluginSchemaTypes(schema);
  if (allowed !== null && !allowed.some(type => matches(value, type))) {
    issues.push(problem(path, `值类型不符合 ${allowed.join('、')}。`)); return issues;
  }
  const object: JsonObject | null = jsonObject(value);
  if (object !== null) {
    const required: JsonValue | undefined = schema['required'];
    if (Array.isArray(required)) for (const key of required) if (typeof key === 'string' && !own(object, key)) issues.push(problem(`${path}.${key}`, '缺少必填字段。'));
    const properties: JsonObject = jsonObject(schema['properties']) ?? {};
    for (const key of Object.keys(object).sort()) {
      const child: JsonObject | null = own(properties, key) ? jsonObject(properties[key]) : null;
      if (child !== null) issues.push(...pluginSchemaValueIssues(child, object[key], `${path}.${key}`));
      else if (schema['additionalProperties'] === false) issues.push(problem(`${path}.${key}`, '不允许额外字段。'));
      else issues.push(...pluginSchemaValueIssues({}, object[key], `${path}.${key}`));
    }
  }
  if (Array.isArray(value)) {
    const itemSchema: JsonObject = jsonObject(schema['items']) ?? {};
    value.forEach((entry, index) => issues.push(...pluginSchemaValueIssues(itemSchema, entry, `${path}[${index}]`)));
  }
  return issues;
};

// Swift sorted keys and NFC path ordering compare Unicode scalars, not UTF-16 units.
export const pluginScalarCompare = (left: string, right: string): number => {
  let a: number = 0; let b: number = 0;
  while (a < left.length && b < right.length) {
    const first: number = left.codePointAt(a)!; const second: number = right.codePointAt(b)!;
    if (first !== second) return first < second ? -1 : 1;
    a += first > 0xffff ? 2 : 1; b += second > 0xffff ? 2 : 1;
  }
  return a < left.length ? 1 : b < right.length ? -1 : 0;
};
/** Same sorted-key/slash escaping as Swift JSONEncoder; preserves literal own keys. */
export const canonicalPluginValue = (value: JsonValue): string => {
  if (typeof value === 'string') return JSON.stringify(value).replace(/\//g, '\\/');
  if (Array.isArray(value)) return `[${value.map(entry => canonicalPluginValue(entry)).join(',')}]`;
  if (value !== null && typeof value === 'object') return `{${Object.keys(value).sort(pluginScalarCompare)
    .map(key => `${canonicalPluginValue(key)}:${canonicalPluginValue(value[key])}`).join(',')}}`;
  if (typeof value === 'number' && !Number.isFinite(value)) throw new Error('JSON 不能包含非有限数值。');
  return JSON.stringify(value);
};
