import type { JsonObject, JsonValue } from '../json.ts';
import type { AgentTool } from '../tool.ts';
import type { RecipeInputType, RecipeManifest } from '../recipes/models.ts';
import { canonicalRecipeJSON, decodeRecipe, recipeEnvelope, validateRecipe } from '../recipes/validation.ts';
import { terminalUTF8Encode } from '../terminal/utf8.ts';
import { pluginNetworkURLAllowed, pluginPrimitiveSupported, pluginURLHost, supportedPluginWebMountActions } from './broker.ts';
import { canonicalPluginValue, jsonObject, legacyPluginInputSchema, pluginInputSchemaIssues,
  pluginOutputSchemaAllows, pluginScalarCompare, pluginSchemaIssues, pluginSchemaTypes, pluginSchemaValueIssues } from './json_schema.ts';
import { PLUGIN_MAX_FILES, PLUGIN_MAX_FILE_BYTES, PLUGIN_MAX_PACKAGE_BYTES, PLUGIN_PACKAGE_HASH_DOMAIN, PLUGIN_SCHEMA } from './models.ts';
import type { PluginCapabilities, PluginCommandManifest, PluginDescriptor, PluginDirectoryMetadata,
  PluginEnvelope, PluginFile, PluginImplementation, PluginIssue, PluginManifest, PluginOutputType,
  PluginPackage, PluginRemoteManifest, PluginResolvedTool, PluginToolManifest, PluginValidationResult } from './models.ts';
import type { PluginHashPort } from './ports.ts';

export class PluginValidationError extends Error {
  readonly code: string; readonly issues: PluginIssue[];
  constructor(code: string, issues: PluginIssue[]) {
    super(issues.map(issue => `${issue.path}: ${issue.message}`).join('\n'));
    this.name = 'PluginValidationError'; this.code = code; this.issues = issues;
  }
}
const issue = (code: string, path: string, message: string): PluginIssue => ({ code, path, message });
const malformed = (path: string, expected: string): never => {
  throw new PluginValidationError('invalid_manifest', [issue('invalidManifestJSON', path, `需要${expected}。`)]);
};
const objectAt = (value: JsonValue | undefined, path: string): JsonObject => jsonObject(value) ?? malformed(path, ' JSON 对象');
const stringAt = (value: JsonValue | undefined, path: string): string => typeof value === 'string' ? value : malformed(path, '字符串');
const optionalString = (value: JsonValue | undefined, path: string): string | undefined => value === undefined || value === null ? undefined : stringAt(value, path);
const stringsAt = (value: JsonValue | undefined, path: string, optional: boolean = false): string[] => {
  if (optional && (value === undefined || value === null)) return [];
  if (!Array.isArray(value) || value.some(entry => typeof entry !== 'string')) return malformed(path, '字符串数组');
  return value as string[];
};
const integerAt = (value: JsonValue | undefined, path: string, fallback: number): number => {
  if (value === undefined || value === null) return fallback;
  return typeof value === 'number' && Number.isInteger(value) ? value : malformed(path, '整数');
};
const schemaAt = (value: JsonValue | undefined, path: string): JsonObject | undefined => value === undefined || value === null ? undefined : objectAt(value, path);
const decodeInputs = (raw: JsonValue | undefined, path: string): Record<string, RecipeInputType> => {
  const object: JsonObject = raw === undefined || raw === null ? {} : objectAt(raw, path);
  for (const key of Object.keys(object)) if (object[key] !== 'string' && object[key] !== 'number' && object[key] !== 'boolean') malformed(`${path}.${key}`, ' string、number 或 boolean 类型');
  return object as Record<string, RecipeInputType>;
};
const decodeTool = (raw: JsonValue, index: number): PluginToolManifest => {
  const path: string = `tools[${index}]`; const object: JsonObject = objectAt(raw, path);
  const tool: PluginToolManifest = { name: stringAt(object['name'], `${path}.name`),
    host_tools: stringsAt(object['host_tools'], `${path}.host_tools`, true), inputs: decodeInputs(object['inputs'], `${path}.inputs`),
    output: (optionalString(object['output'], `${path}.output`) ?? 'json') as PluginOutputType,
    timeout_ms: integerAt(object['timeout_ms'], `${path}.timeout_ms`, 10000), max_output_chars: integerAt(object['max_output_chars'], `${path}.max_output_chars`, 10000) };
  tool.description = optionalString(object['description'], `${path}.description`);
  tool.recipe = optionalString(object['recipe'], `${path}.recipe`); tool.script = optionalString(object['script'], `${path}.script`);
  tool.input_schema = schemaAt(object['input_schema'], `${path}.input_schema`); tool.output_schema = schemaAt(object['output_schema'], `${path}.output_schema`);
  if (object['remote'] !== undefined && object['remote'] !== null) {
    const remote: JsonObject = objectAt(object['remote'], `${path}.remote`);
    tool.remote = { kind: stringAt(remote['kind'], `${path}.remote.kind`) as 'mcp' | 'openapi',
      server: optionalString(remote['server'], `${path}.remote.server`), tool: optionalString(remote['tool'], `${path}.remote.tool`),
      url: optionalString(remote['url'], `${path}.remote.url`), method: optionalString(remote['method'], `${path}.remote.method`) };
  }
  if (object['command'] !== undefined && object['command'] !== null) {
    const command: JsonObject = objectAt(object['command'], `${path}.command`);
    tool.command = { runtime: stringAt(command['runtime'], `${path}.command.runtime`), entry: stringAt(command['entry'], `${path}.command.entry`),
      stdin_input: optionalString(command['stdin_input'], `${path}.command.stdin_input`) };
  }
  return tool;
};
export const decodePlugin = (text: string): PluginManifest => {
  let parsed: JsonValue;
  try { parsed = JSON.parse(text) as JsonValue; } catch { return malformed('', '合法 plugin.json'); }
  const root: JsonObject = objectAt(parsed, ''); const capabilities: JsonObject = objectAt(root['capabilities'], 'capabilities');
  if (!Array.isArray(root['tools'])) return malformed('tools', '工具数组');
  if (typeof root['backgroundAllowed'] !== 'boolean') return malformed('backgroundAllowed', 'boolean');
  const manifest: PluginManifest = { schema: stringAt(root['schema'], 'schema'), id: stringAt(root['id'], 'id'),
    name: stringAt(root['name'], 'name'), version: stringAt(root['version'], 'version'), description: stringAt(root['description'], 'description'),
    tools: root['tools'].map(decodeTool), backgroundAllowed: root['backgroundAllowed'], capabilities: {
      workspaceReadPrefixes: stringsAt(capabilities['workspaceReadPrefixes'], 'capabilities.workspaceReadPrefixes'),
      workspaceWritePrefixes: stringsAt(capabilities['workspaceWritePrefixes'], 'capabilities.workspaceWritePrefixes'),
      networkDomains: stringsAt(capabilities['networkDomains'], 'capabilities.networkDomains'),
      webMountActions: stringsAt(capabilities['webMountActions'], 'capabilities.webMountActions'),
      localRuntimes: stringsAt(capabilities['localRuntimes'], 'capabilities.localRuntimes', true) } };
  if (root['directory'] !== undefined && root['directory'] !== null) {
    const directory: JsonObject = objectAt(root['directory'], 'directory');
    const metadata: PluginDirectoryMetadata = { publisher: stringAt(directory['publisher'], 'directory.publisher') };
    metadata.homepage_url = optionalString(directory['homepage_url'], 'directory.homepage_url');
    metadata.support_url = optionalString(directory['support_url'], 'directory.support_url'); metadata.privacy_url = optionalString(directory['privacy_url'], 'directory.privacy_url');
    if (directory['minimum_age'] !== undefined && directory['minimum_age'] !== null) metadata.minimum_age = integerAt(directory['minimum_age'], 'directory.minimum_age', 4);
    manifest.directory = metadata;
  }
  return manifest;
};

const validUnicodePath = (path: string): boolean => !Array.from(path).some(character => {
  const scalar: number = character.codePointAt(0)!; return scalar >= 0xd800 && scalar <= 0xdfff;
});
export const isCanonicalPluginPath = (path: string): boolean => path.length > 0 && !path.startsWith('/') && validUnicodePath(path)
  && !/[\\:\u0000-\u001f\u007f-\u009f]/.test(path) && path === path.normalize('NFC')
  && path.split('/').every(part => part.length > 0 && !part.startsWith('.'));
const handlerPath = (path: string, directory: string, extension: string): boolean => isCanonicalPluginPath(path)
  && path.startsWith(`${directory}/`) && path.endsWith(extension) && path.split('/').length === 2;
export const isAllowedPluginFilePath = (path: string): boolean => isCanonicalPluginPath(path) && (path === 'plugin.json'
  || path === 'README.md' || handlerPath(path, 'recipes', '.json') || ['.js', '.py', '.sh'].some(extension => handlerPath(path, 'scripts', extension))
  || (path.startsWith('assets/') && path.length > 'assets/'.length));

// NFC paths match Swift String ordering by Unicode scalar, not JS UTF-16 code units.
export const pluginPathCompare = pluginScalarCompare;

/** Reject malformed UTF-8 rather than hashing a silently replaced script. */
export const pluginFileText = (file: PluginFile): string => {
  let result: string = '';
  for (let i: number = 0; i < file.data.length;) {
    const lead: number = file.data[i]; const count: number = lead < 0x80 ? 1 : lead >= 0xc2 && lead <= 0xdf ? 2 : lead >= 0xe0 && lead <= 0xef ? 3 : lead >= 0xf0 && lead <= 0xf4 ? 4 : 0;
    let cp: number = lead & (count === 1 ? 0x7f : count === 2 ? 0x1f : count === 3 ? 15 : 7);
    let valid: boolean = count > 0 && i + count <= file.data.length;
    for (let offset: number = 1; valid && offset < count; offset++) {
      const byte: number = file.data[i + offset]; if ((byte & 0xc0) !== 0x80) valid = false;
      else cp = (cp << 6) | (byte & 63);
    }
    if (!valid || (count === 2 && cp < 0x80) || (count === 3 && cp < 0x800) || (count === 4 && cp < 0x10000)
      || cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff)) throw new PluginValidationError('invalid_package', [issue('invalidUTF8', file.path, '文本文件必须是合法 UTF-8。')]);
    result += String.fromCodePoint(cp); i += count;
  }
  return result;
};
const packageFileIssues = (files: PluginFile[]): PluginIssue[] => {
  const issues: PluginIssue[] = []; const paths: Set<string> = new Set(); let total: number = 0;
  if (files.length === 0 || files.length > PLUGIN_MAX_FILES) issues.push(issue('fileBudget', '', `包必须含 1–${PLUGIN_MAX_FILES} 个文件。`));
  for (const file of files) {
    if (!isAllowedPluginFilePath(file.path)) issues.push(issue('invalidPath', file.path, '不允许的包文件路径。'));
    if (paths.has(file.path)) issues.push(issue('duplicatePath', file.path, '包文件路径重复。')); paths.add(file.path);
    if (file.data.length > PLUGIN_MAX_FILE_BYTES) issues.push(issue('fileBudget', file.path, '文件超过 256 KiB。'));
    if (file.data.some(byte => !Number.isInteger(byte) || byte < 0 || byte > 255)) issues.push(issue('invalidBytes', file.path, '文件字节必须为 0–255 整数。'));
    total += file.data.length;
  }
  if (!paths.has('plugin.json')) issues.push(issue('missingManifest', 'plugin.json', '缺少 plugin.json。'));
  if (total > PLUGIN_MAX_PACKAGE_BYTES) issues.push(issue('packageBudget', '', '包总大小超过 1 MiB。'));
  return issues;
};
const exactMatch = (pattern: RegExp, value: string): boolean => pattern.exec(value)?.[0] === value;
const memberName = (name: string): boolean => exactMatch(/^[a-z][a-z0-9_]{0,31}$/, name) && !name.includes('__');
const httpsURL = (value: string): boolean => pluginURLHost(value)?.scheme === 'https';
const capabilitiesIssues = (capabilities: PluginCapabilities): PluginIssue[] => {
  const issues: PluginIssue[] = [];
  for (const key of ['workspaceReadPrefixes', 'workspaceWritePrefixes'] as const) for (const prefix of capabilities[key]) {
    const relative: string = prefix.slice(11);
    if (prefix !== '/workspace' && !(prefix.startsWith('/workspace/') && relative.length > 0
      && validUnicodePath(relative) && !/[\\:\u0000-\u001f\u007f-\u009f]/.test(relative) && relative === relative.normalize('NFC')
      && relative.split('/').every(part => part.length > 0 && part !== '.' && part !== '..'))) issues.push(issue('invalidCapability', `capabilities.${key}`, 'Workspace 前缀必须是 /workspace 内的规范路径。'));
  }
  for (const domain of capabilities.networkDomains) if (domain !== domain.toLowerCase() || domain.length > 253
    || domain.split('.').length < 2 || !domain.split('.').every(label => exactMatch(/^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/, label))) issues.push(issue('invalidCapability', 'capabilities.networkDomains', '需要规范小写主机名。'));
  for (const action of capabilities.webMountActions) if (!supportedPluginWebMountActions.includes(action)) issues.push(issue('unsupportedCapability', 'capabilities.webMountActions', `当前平台不能完整执行 ${action}。`));
  for (const runtime of capabilities.localRuntimes) if (runtime !== 'embedded_python') issues.push(issue('unsupportedRuntime', 'capabilities.localRuntimes', `当前平台不支持 ${runtime}。`));
  return issues;
};
const emptyEnvelope = (): PluginEnvelope => ({ tools: [], mutates: false, needsApproval: false, risk: 'normal' });
const mergeEnvelope = (target: PluginEnvelope, source: PluginEnvelope): void => {
  for (const tool of source.tools) if (!target.tools.includes(tool)) target.tools.push(tool);
  target.mutates = target.mutates || source.mutates; target.needsApproval = target.needsApproval || source.needsApproval;
  if (source.risk === 'high' || (source.risk === 'sensitive' && target.risk === 'normal')) target.risk = source.risk;
};
const envelopeFor = (names: string[], primitives: AgentTool[], args: JsonObject = {}): PluginEnvelope => recipeEnvelope({
  schema: '', name: '', version: '', description: '', inputs: {}, outputs: {}, steps: names.map((name, index) => ({ id: `step${index}`, tool: name, arguments: args })) }, primitives);
const fileSource = (files: PluginFile[], path: string): string => {
  const file: PluginFile | undefined = files.find(entry => entry.path === path);
  if (file === undefined) throw new PluginValidationError('invalid_package', [issue('missingHandler', path, '找不到 handler 文件。')]);
  const source: string = pluginFileText(file);
  if (source.trim().length === 0) throw new PluginValidationError('invalid_package', [issue('emptyHandler', path, 'handler 文件不能为空。')]);
  return source;
};
const remoteTools = (remote: PluginRemoteManifest, capabilities: PluginCapabilities, primitives: AgentTool[], path: string, issues: PluginIssue[]): string[] => {
  if (remote.kind === 'mcp') {
    if (!remote.server?.trim() || !remote.tool?.trim() || remote.url !== undefined || remote.method !== undefined) issues.push(issue('invalidRemote', path, 'MCP 必须且只能声明 server/tool。'));
    const tool: AgentTool | undefined = primitives.find(entry => entry.mcpTarget !== undefined && entry.mcpTarget.serverId === remote.server && entry.mcpTarget.toolName === remote.tool);
    if (tool === undefined) issues.push(issue('missingPrimitive', path, '当前助手没有许可的固定 MCP server/tool。'));
    return tool === undefined ? [] : [tool.name];
  }
  if (remote.kind !== 'openapi' || remote.server !== undefined || remote.tool !== undefined || remote.url === undefined
    || !httpsURL(remote.url) || !pluginNetworkURLAllowed(remote.url, capabilities.networkDomains)) issues.push(issue('invalidRemote', path, 'OpenAPI 必须绑定能力域内的固定 HTTPS URL。'));
  if (!['GET', 'HEAD', 'POST', 'PUT', 'PATCH', 'DELETE'].includes(remote.method ?? 'GET')) issues.push(issue('invalidRemote', `${path}.method`, '需要大写标准 HTTP 方法。'));
  if (!primitives.some(tool => tool.name === 'http_request')) issues.push(issue('missingPrimitive', path, '当前助手没有 http_request 工具。'));
  return ['http_request'];
};

export const validatePlugin = (manifest: PluginManifest, files: PluginFile[], primitives: AgentTool[], existingNames: string[] = []): PluginValidationResult => {
  const issues: PluginIssue[] = packageFileIssues(files); const tools: PluginResolvedTool[] = []; const names: Set<string> = new Set();
  if (manifest.schema !== PLUGIN_SCHEMA) issues.push(issue('schemaMismatch', 'schema', `需要 ${PLUGIN_SCHEMA}。`));
  if (!exactMatch(/^[a-z][a-z0-9_]{1,31}$/, manifest.id) || manifest.id.includes('__')) issues.push(issue('invalidId', 'id', 'id 需要 2–32 位小写字母/数字/下划线且不含 __。'));
  if (!manifest.name.trim() || Array.from(manifest.name).length > 80) issues.push(issue('invalidName', 'name', 'name 不能为空或超过 80 字符。'));
  if (!manifest.version || /\s/.test(manifest.version)) issues.push(issue('invalidVersion', 'version', 'version 不能为空或包含空白。'));
  if (!manifest.description.trim()) issues.push(issue('invalidDescription', 'description', 'description 不能为空。'));
  if (manifest.tools.length === 0) issues.push(issue('missingTools', 'tools', '至少声明一个工具。'));
  issues.push(...capabilitiesIssues(manifest.capabilities));
  if (manifest.directory !== undefined) {
    const directory = manifest.directory;
    if (!directory.publisher.trim()) issues.push(issue('invalidDirectory', 'directory.publisher', 'publisher 不能为空。'));
    for (const field of ['homepage_url', 'support_url', 'privacy_url'] as const) if (directory[field] !== undefined && !httpsURL(directory[field])) issues.push(issue('invalidDirectory', `directory.${field}`, '需要固定 HTTPS URL。'));
    if (directory.minimum_age !== undefined && ![4, 9, 12, 17].includes(directory.minimum_age)) issues.push(issue('invalidDirectory', 'directory.minimum_age', '只支持 4/9/12/17。'));
  }
  for (let index: number = 0; index < manifest.tools.length; index++) {
    const member: PluginToolManifest = manifest.tools[index]; const path: string = `tools[${index}]`; const before: number = issues.length;
    if (!memberName(member.name) || names.has(member.name)) issues.push(issue('invalidToolName', `${path}.name`, '成员名无效或重复。')); names.add(member.name);
    const toolId: string = `plugin__${manifest.id}__${member.name}`;
    if (existingNames.includes(toolId) || primitives.some(tool => tool.name === toolId)) issues.push(issue('toolConflict', `${path}.name`, '注册名与现有工具冲突。'));
    if (![member.recipe, member.script, member.remote, member.command].filter(value => value !== undefined).length
      || [member.recipe, member.script, member.remote, member.command].filter(value => value !== undefined).length !== 1) issues.push(issue('handlerCount', path, '必须且只能声明一个 handler。'));
    if (!['json', 'object', 'array', 'string', 'number', 'boolean'].includes(member.output)) issues.push(issue('invalidOutput', `${path}.output`, '无效的 output 类型。'));
    const maxTimeout: number = member.command !== undefined ? 60000 : 30000;
    if (!Number.isInteger(member.timeout_ms) || member.timeout_ms < 1000 || member.timeout_ms > maxTimeout) issues.push(issue('invalidTimeout', `${path}.timeout_ms`, `timeout_ms 范围为 1000–${maxTimeout}。`));
    if (!Number.isInteger(member.max_output_chars) || member.max_output_chars < 1000 || member.max_output_chars > 32000) issues.push(issue('invalidOutputBudget', `${path}.max_output_chars`, 'max_output_chars 范围为 1000–32000。'));
    for (const name of Object.keys(member.inputs)) if (!memberName(name) || name === 'display_title') issues.push(issue('invalidInput', `${path}.inputs.${name}`, '输入名无效或保留。'));
    if (member.input_schema !== undefined) {
      issues.push(...pluginInputSchemaIssues(member.input_schema, `${path}.input_schema`));
      if (Object.keys(member.inputs).length > 0) issues.push(issue('mixedInputs', path, 'input_schema 不与非空 inputs 混用。'));
    }
    if (member.output_schema !== undefined) {
      issues.push(...pluginSchemaIssues(member.output_schema, `${path}.output_schema`));
      if (!pluginOutputSchemaAllows(member.output_schema, member.output)) issues.push(issue('incompatibleOutput', `${path}.output_schema`, 'output_schema 与 output 类型不兼容。'));
    }
    let implementation: PluginImplementation | null = null; let primitiveTools: string[] = []; let inputSchema: JsonObject = member.input_schema ?? legacyPluginInputSchema(member.inputs);
    let envelope: PluginEnvelope = emptyEnvelope();
    try {
      if (member.recipe !== undefined) {
        if (!handlerPath(member.recipe, 'recipes', '.json')) issues.push(issue('invalidHandlerPath', `${path}.recipe`, 'Recipe 路径需要 recipes/<name>.json。'));
        if (member.host_tools.length > 0 || Object.keys(member.inputs).length > 0 || member.input_schema !== undefined || member.output_schema !== undefined) issues.push(issue('recipeOwnsContract', path, 'Recipe 成员的输入/工具/schema 由私有 Recipe 派生。'));
        const recipe: RecipeManifest = decodeRecipe(fileSource(files, member.recipe)); issues.push(...validateRecipe(recipe, primitives).map(entry => issue(entry.code, `${path}.recipe.${entry.path}`, entry.message)));
        primitiveTools = Array.from(new Set(recipe.steps.map(step => step.tool)));
        implementation = { kind: 'recipe', manifest: recipe }; inputSchema = legacyPluginInputSchema(recipe.inputs); envelope = recipeEnvelope(recipe, primitives);
      } else if (member.script !== undefined) {
        if (!handlerPath(member.script, 'scripts', '.js')) issues.push(issue('invalidHandlerPath', `${path}.script`, 'JS 路径需要 scripts/<name>.js。'));
        if (new Set(member.host_tools).size !== member.host_tools.length) issues.push(issue('duplicateHostTools', `${path}.host_tools`, 'host_tools 不能重复。'));
        primitiveTools = Array.from(new Set(member.host_tools)); implementation = { kind: 'javascript', source: fileSource(files, member.script), hostTools: member.host_tools.slice() };
        envelope = envelopeFor(primitiveTools, primitives);
      } else if (member.command !== undefined) {
        const command: PluginCommandManifest = member.command;
        if (command.runtime !== 'embedded_python' || !manifest.capabilities.localRuntimes.includes('embedded_python')) issues.push(issue('unsupportedRuntime', `${path}.command.runtime`, 'Python 需明确 embedded_python runtime 能力。'));
        if (!handlerPath(command.entry, 'scripts', '.py')) issues.push(issue('invalidHandlerPath', `${path}.command.entry`, 'Python 路径需要 scripts/<name>.py。'));
        if (member.host_tools.length > 0) issues.push(issue('unexpectedHostTools', `${path}.host_tools`, 'host_tools 只属于 JS 成员。'));
        if (command.stdin_input !== undefined) {
          const property: JsonObject | null = jsonObject(jsonObject(inputSchema['properties'])?.[command.stdin_input]);
          const propertyTypes: string[] | null = property === null ? null : pluginSchemaTypes(property);
          if (propertyTypes === null || propertyTypes.length !== 1 || propertyTypes[0] !== 'string') issues.push(issue('invalidStdinInput', `${path}.command.stdin_input`, 'stdin_input 必须引用声明的 string 输入。'));
        }
        primitiveTools = ['python_execute']; implementation = { kind: 'command', runtime: 'embedded_python', entry: command.entry, source: fileSource(files, command.entry), stdinInput: command.stdin_input ?? null };
        envelope = envelopeFor(primitiveTools, primitives); envelope.mutates = true; envelope.needsApproval = true; envelope.risk = 'high';
        if (!primitives.some(tool => tool.name === 'python_execute')) issues.push(issue('missingPrimitive', path, '当前助手没有本机 python_execute 工具。'));
      } else if (member.remote !== undefined) {
        if (member.host_tools.length > 0) issues.push(issue('unexpectedHostTools', `${path}.host_tools`, 'host_tools 只属于 JS 成员。'));
        primitiveTools = remoteTools(member.remote, manifest.capabilities, primitives, `${path}.remote`, issues); implementation = { kind: 'remote', remote: member.remote };
        envelope = envelopeFor(primitiveTools, primitives, { url: member.remote.url ?? '', method: member.remote.method ?? 'GET' });
      }
    } catch (error) {
      if (error instanceof PluginValidationError) issues.push(...error.issues);
      else if (error instanceof Error) issues.push(issue('invalidHandler', path, error.message)); else throw error;
    }
    if (member.recipe !== undefined || member.script !== undefined) for (const name of primitiveTools) {
      if (!pluginPrimitiveSupported(name)) issues.push(issue('unsupportedPrimitive', path, `不允许宿主工具 ${name}。`));
      else if (!primitives.some(tool => tool.name === name)) issues.push(issue('missingPrimitive', path, `当前助手没有 ${name}。`));
      if (name.startsWith('wm_') && (!manifest.capabilities.webMountActions.includes(name) || manifest.capabilities.networkDomains.length === 0)) issues.push(issue('missingCapability', path, `${name} 需要动作和网络域声明。`));
      if (name === 'scrape_web' && manifest.capabilities.networkDomains.length === 0) issues.push(issue('missingCapability', path, 'scrape_web 需要 networkDomains。'));
      if (['file_list', 'file_read', 'file_search', 'file_edit', 'file_move'].includes(name) && manifest.capabilities.workspaceReadPrefixes.length === 0) issues.push(issue('missingCapability', path, `${name} 需要 Workspace 读权限。`));
      if (['file_write', 'file_edit', 'file_move'].includes(name) && manifest.capabilities.workspaceWritePrefixes.length === 0) issues.push(issue('missingCapability', path, `${name} 需要 Workspace 写权限。`));
    }
    if (implementation !== null && issues.length === before) tools.push({ name: member.name, description: member.description?.trim() || manifest.description,
      inputSchema, outputSchema: member.output_schema ?? null, output: member.output, timeoutMs: member.timeout_ms,
      maxOutputChars: member.max_output_chars, implementation, primitiveTools, envelope });
  }
  const envelope: PluginEnvelope = emptyEnvelope(); for (const tool of tools) mergeEnvelope(envelope, tool.envelope);
  return { issues, tools, primitiveTools: envelope.tools.slice(), envelope: issues.length === 0 ? envelope : null };
};

const optionalFields = (target: JsonObject, source: object, keys: string[]): void => {
  const object: JsonObject = source as JsonObject;
  for (const key of keys) if (object[key] !== undefined) target[key] = object[key];
};
export const canonicalPluginJSON = (manifest: PluginManifest): string => {
  const tools: JsonObject[] = manifest.tools.map(member => {
    const fields: JsonObject = { name: member.name, host_tools: member.host_tools, inputs: member.inputs, output: member.output,
      timeout_ms: member.timeout_ms, max_output_chars: member.max_output_chars };
    optionalFields(fields, member, ['description', 'recipe', 'script', 'input_schema', 'output_schema']);
    if (member.remote !== undefined) { const remote: JsonObject = { kind: member.remote.kind }; optionalFields(remote, member.remote, ['server', 'tool', 'url', 'method']); fields['remote'] = remote; }
    if (member.command !== undefined) { const command: JsonObject = { runtime: member.command.runtime, entry: member.command.entry }; optionalFields(command, member.command, ['stdin_input']); fields['command'] = command; }
    return fields;
  });
  const capabilities: JsonObject = { workspaceReadPrefixes: manifest.capabilities.workspaceReadPrefixes, workspaceWritePrefixes: manifest.capabilities.workspaceWritePrefixes,
    networkDomains: manifest.capabilities.networkDomains, webMountActions: manifest.capabilities.webMountActions };
  if (manifest.capabilities.localRuntimes.length > 0) capabilities['localRuntimes'] = manifest.capabilities.localRuntimes;
  const fields: JsonObject = { schema: manifest.schema, id: manifest.id, name: manifest.name, version: manifest.version,
    description: manifest.description, tools, capabilities, backgroundAllowed: manifest.backgroundAllowed };
  if (manifest.directory !== undefined) { const directory: JsonObject = { publisher: manifest.directory.publisher }; optionalFields(directory, manifest.directory, ['homepage_url', 'support_url', 'privacy_url', 'minimum_age']); fields['directory'] = directory; }
  return canonicalPluginValue(fields);
};
export const pluginPackageHashBytes = (files: PluginFile[]): Uint8Array => {
  const bytes: number[] = Array.from(terminalUTF8Encode(PLUGIN_PACKAGE_HASH_DOMAIN));
  const lengthBytes = (length: number): void => {
    for (let shift: number = 7; shift >= 0; shift--) bytes.push(Math.floor(length / Math.pow(256, shift)) % 256);
  };
  for (const file of files.slice().sort((a, b) => pluginPathCompare(a.path, b.path))) {
    const path: Uint8Array = terminalUTF8Encode(file.path); lengthBytes(path.length);
    for (const byte of path) bytes.push(byte); lengthBytes(file.data.length); for (const byte of file.data) bytes.push(byte);
  }
  return new Uint8Array(bytes);
};
export const preparePluginPackage = async (files: PluginFile[], primitives: AgentTool[], hashPort: PluginHashPort, existingNames: string[] = []): Promise<PluginPackage> => {
  const fileIssues: PluginIssue[] = packageFileIssues(files);
  if (fileIssues.length > 0) throw new PluginValidationError('invalid_package', fileIssues);
  const manifest: PluginManifest = decodePlugin(pluginFileText(files.find(file => file.path === 'plugin.json')!));
  const validation: PluginValidationResult = validatePlugin(manifest, files, primitives, existingNames);
  if (validation.issues.length > 0 || validation.envelope === null) throw new PluginValidationError('invalid_package', validation.issues);
  let canonicalFiles: PluginFile[];
  try {
    canonicalFiles = files.map(file => {
      let text: string | null = null;
      if (file.path === 'plugin.json') text = canonicalPluginJSON(manifest);
      else if (handlerPath(file.path, 'recipes', '.json')) text = canonicalPluginValue(JSON.parse(canonicalRecipeJSON(decodeRecipe(pluginFileText(file)))) as JsonValue);
      return { path: file.path, data: text === null ? file.data.slice() : Array.from(terminalUTF8Encode(text)) };
    }).sort((a, b) => pluginPathCompare(a.path, b.path));
  } catch (error) {
    if (error instanceof PluginValidationError) throw error;
    if (error instanceof Error) throw new PluginValidationError('invalid_package', [issue('invalidCanonicalContent', '', error.message)]);
    throw error;
  }
  const canonicalIssues: PluginIssue[] = packageFileIssues(canonicalFiles);
  if (canonicalIssues.length > 0) throw new PluginValidationError('invalid_package', canonicalIssues);
  const hash: string = await hashPort.sha256(pluginPackageHashBytes(canonicalFiles)); const fileHashes: Record<string, string> = {};
  for (const file of canonicalFiles) fileHashes[file.path] = await hashPort.sha256(new Uint8Array(file.data));
  const tools: PluginDescriptor[] = validation.tools.map(tool => ({ ...tool, pluginId: manifest.id, toolId: `plugin__${manifest.id}__${tool.name}`,
    version: manifest.version, packageHash: hash, capabilities: manifest.capabilities, backgroundAllowed: manifest.backgroundAllowed }));
  return { manifest, hash, files: canonicalFiles, fileHashes, tools, envelope: validation.envelope };
};
export const normalizePluginInputs = (descriptor: PluginDescriptor, raw: JsonValue): JsonObject => {
  const object: JsonObject | null = jsonObject(raw);
  if (object === null) throw new PluginValidationError('invalid_inputs', [issue('invalidInput', '$', '工具输入必须是 JSON object。')]);
  const sanitized: JsonObject = {};
  for (const key of Object.keys(object)) if (key !== 'display_title') Object.defineProperty(sanitized, key, { value: object[key], enumerable: true, configurable: true, writable: true });
  const issues: PluginIssue[] = pluginSchemaValueIssues(descriptor.inputSchema, sanitized);
  if (issues.length > 0) throw new PluginValidationError('invalid_inputs', issues);
  return sanitized;
};
export const validatePluginOutput = (descriptor: PluginDescriptor, value: JsonValue): PluginIssue[] => {
  const coarse: JsonObject = descriptor.output === 'json' ? {} : { type: descriptor.output };
  const issues: PluginIssue[] = pluginSchemaValueIssues(coarse, value);
  if (descriptor.outputSchema !== null) issues.push(...pluginSchemaValueIssues(descriptor.outputSchema, value));
  return issues;
};
