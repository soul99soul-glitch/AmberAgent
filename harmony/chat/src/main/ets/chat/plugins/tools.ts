import type { JsonObject, JsonValue } from '../json.ts';
import type { UIMessagePart } from '../message.ts';
import type { AgentTool } from '../tool.ts';
import { makeAgentTool, makeInputSchemaObj } from '../tool.ts';
import { sanitizedToolFailureMessage } from '../tool_dispatcher.ts';
import type { InstalledPlugin, PluginDescriptor } from './models.ts';
import type { PluginStore } from './ports.ts';
import { PluginValidationError, pluginFileText } from './validation.ts';
import { pluginSourceFromInput } from './runner.ts';
import { supportedPluginWebMountActions } from './broker.ts';

export interface PluginToolDeps { store: PluginStore; installed: InstalledPlugin[]; primitives: AgentTool[]; }
const text = (value: JsonObject): UIMessagePart[] => [{ type: 'text', text: JSON.stringify(value), metadata: null }];
const objectInput = (value: JsonValue): JsonObject => {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new Error('Tool arguments must be a JSON object.');
  return value;
};
const stringInput = (input: JsonObject, key: string): string => {
  const value = input[key];
  if (typeof value !== 'string' || value.length === 0) throw new Error(key + ' must be a nonempty string.');
  return value;
};
const guarded = (run: (input: JsonObject) => Promise<UIMessagePart[]>): ((value: JsonValue) => Promise<UIMessagePart[]>) =>
  async (value) => { try { return await run(objectInput(value)); } catch (error) { return text({ status: 'failed',
    error_code: (error as PluginValidationError).code ?? 'invalid_arguments', message: sanitizedToolFailureMessage(error as Error),
    issues: error instanceof PluginValidationError ? JSON.parse(JSON.stringify(error.issues)) as JsonValue : [] }); } };
const adapterRequired = async (): Promise<UIMessagePart[]> => text({ status: 'failed', error_code: 'plugin_adapter_required',
  message: 'Plugin import, candidate tests and runs require their pinned PluginLoopAdapter and individual host approval.' });
const stringProperty: JsonObject = { type: 'string' };
const sourceProperties: JsonObject = { workspace_directory: stringProperty, archive_path: stringProperty };
const descriptorJSON = (descriptor: PluginDescriptor): JsonObject => ({ name: descriptor.name, tool: descriptor.toolId,
  version: descriptor.version, package_hash: descriptor.packageHash, input_schema: descriptor.inputSchema,
  output_schema: descriptor.outputSchema, output: descriptor.output, backend: descriptor.implementation.kind,
  primitive_tools: descriptor.primitiveTools, envelope: JSON.parse(JSON.stringify(descriptor.envelope)) as JsonValue });

export const getPluginSDK = (): JsonObject => ({ schema: 'amber.plugin.v1',
  package: { required: 'plugin.json', optional: ['README.md', 'recipes/*.json', 'scripts/*.js', 'scripts/*.py', 'assets/**'],
    max_files: 32, max_file_bytes: 256 * 1024, max_package_bytes: 1024 * 1024 },
  backends: { recipe: 'Private package recipe; existing steps and approvals, never installed as a global Recipe.',
    javascript: 'Synchronous function body with input and declared tools.<name>(args). Typed JSON return; Promise is rejected. Fresh VM, no DOM/network/files/module host.',
    command: 'runtime=embedded_python, fixed scripts/*.py source. stdin is input JSON or an exact declared string stdin_input; no source interpolation. Python limits: 256KiB source, 64KiB stdin, 60000ms.',
    remote: 'kind=mcp fixes current assistant-permitted server/tool; kind=openapi fixes HTTPS URL/method and checks every redirect against networkDomains.' },
  host_tools: { workspace: ['file_list', 'file_read', 'file_search', 'file_write', 'file_edit', 'file_move'],
    web: ['scrape_web'], web_mount: supportedPluginWebMountActions, introspection: ['tool_search', 'tools_list'], human: ['ask_user'],
    denied: ['terminal_*', 'python_execute', 'javascript_execute', 'http_request', 'plugin/recipe management', 'memory/private/subagent/exec', 'wm_eval', 'wm_signed_fetch', 'wm_site_*'] },
  capability_rules: 'Workspace prefixes compare complete path segments. Domains include their subdomains. Actual host permissions come from current primitives, never from a weaker manifest.',
  cancellation: 'JS API12 uses cooperative abandon: host gate closes and late results are discarded. An infinite script may retain CPU and the sole runtime slot until it ends; no hard-kill promise. Pending host calls preserve the VM stack; missing VM never re-evaluates source.',
  test: 'plugin_test requires exactly one source and expected_candidate_hash. expected_result absent differs from null; typed deep comparison. candidate_test=true, registered=false; live health unchanged.',
  install: 'plugin_import requires expected_candidate_hash and explicit human approval even with unattended settings; enable defaults false.',
  example: { 'plugin.json': { schema: 'amber.plugin.v1', id: 'normalize', name: 'Normalize JSON', version: '1', description: 'Return the input as JSON.',
    tools: [{ name: 'run', script: 'scripts/run.js', inputs: { value: 'string' }, host_tools: [], output: 'json' }],
    capabilities: { workspaceReadPrefixes: [], workspaceWritePrefixes: [], networkDomains: [], webMountActions: [] }, backgroundAllowed: false },
    'scripts/run.js': 'return { value: input.value };' },
});

export const createPluginTools = (deps: PluginToolDeps): AgentTool[] => {
  const primitives = deps.primitives.slice();
  const tools: AgentTool[] = [
    makeAgentTool({ name: 'plugins_list', description: 'List installed plugins with hashes, enabled/quarantined/invalid state, trust and diagnostics.',
      allowsAutoApproval: false, parameters: () => makeInputSchemaObj({}), execute: guarded(async () => {
        const installed = await deps.store.listInstalled(primitives);
        return text({ plugins: installed.map((entry) => ({ id: entry.id, current_hash: entry.currentHash, configured_enabled: entry.configuredEnabled,
          enabled: entry.enabled, error_code: entry.errorCode, error_message: entry.errorMessage,
          trust: JSON.parse(JSON.stringify(entry.trust)) as JsonValue, health: JSON.parse(JSON.stringify(entry.health)) as JsonValue,
          name: entry.package?.manifest.name ?? entry.id, version: entry.package?.manifest.version ?? null,
          tools: entry.package?.tools.map(descriptorJSON) ?? [] })) });
      }) }),
    makeAgentTool({ name: 'plugin_sdk', description: 'Return the actual plugin schema, supported backends, host capability limits and example package.',
      allowsAutoApproval: false, parameters: () => makeInputSchemaObj({}), execute: async () => text(getPluginSDK()) }),
    makeAgentTool({ name: 'plugin_validate', description: 'Validate one Workspace directory or .amberplugin JSON archive without registering or running it.',
      allowsAutoApproval: false, parameters: () => makeInputSchemaObj(sourceProperties), execute: guarded(async (input) => {
        const read = await deps.store.readPackage(pluginSourceFromInput(input), primitives);
        return text({ status: 'valid', candidate_hash: read.candidate.hash, registered: false,
          plugin_id: read.candidate.manifest.id, tools: read.candidate.tools.map(descriptorJSON),
          trust: JSON.parse(JSON.stringify(read.trust)) as JsonValue, envelope: JSON.parse(JSON.stringify(read.candidate.envelope)) as JsonValue });
      }) }),
    makeAgentTool({ name: 'plugin_test', description: 'Run the fixed candidate tool with individual host approvals. Test does not register or alter live health; exact expected_result is optional.',
      allowsAutoApproval: false, parameters: () => makeInputSchemaObj({ ...sourceProperties, expected_candidate_hash: stringProperty,
        tool: stringProperty, inputs: { type: 'object' }, expected_result: { description: 'Optional exact JSON value; explicit null is an expectation.' } }, ['expected_candidate_hash', 'tool', 'inputs']), execute: adapterRequired }),
    makeAgentTool({ name: 'plugin_import', description: 'Import a fixed candidate after explicit human approval and candidate/base CAS. enable=true explicitly installs and enables; default false.',
      needsApproval: true, mandatoryApproval: true, allowsAutoApproval: false, parameters: () => makeInputSchemaObj({ ...sourceProperties,
        expected_candidate_hash: stringProperty, expected_base_hash: { type: ['string', 'null'], description: 'Optional visible installed hash; null pins first install.' },
        enable: { type: 'boolean', default: false } }, ['expected_candidate_hash']), execute: adapterRequired }),
  ];
  for (const name of ['plugin_enable', 'plugin_disable', 'plugin_delete', 'plugin_rollback', 'plugin_restore', 'plugin_export']) {
    tools.push(makeAgentTool({ name, description: 'Apply ' + name + ' only to the fixed installed id and expected_hash.',
      needsApproval: true, allowsAutoApproval: false, parameters: () => makeInputSchemaObj({ id: stringProperty, expected_hash: stringProperty }, ['id', 'expected_hash']),
      execute: guarded(async (input) => {
        const id = stringInput(input, 'id'), hash = stringInput(input, 'expected_hash');
        if (name === 'plugin_rollback') return text(JSON.parse(JSON.stringify(await deps.store.rollback(id, hash))) as JsonObject);
        if (name === 'plugin_export') {
          const bytes = await deps.store.exportArchive(id, hash);
          const archive = JSON.parse(pluginFileText({ path: id + '.amberplugin', data: Array.from(bytes) })) as JsonValue;
          return text({ filename: id + '.amberplugin', archive });
        }
        if (name === 'plugin_delete') await deps.store.remove(id, hash);
        else if (name === 'plugin_restore') await deps.store.restore(id, hash);
        else await deps.store.setEnabled(id, hash, name === 'plugin_enable');
        return text({ status: 'succeeded', id, hash, operation: name });
      }) }));
  }
  for (const entry of deps.installed) {
    if (!entry.enabled || entry.package === null) continue;
    for (const raw of entry.package.tools) {
      const descriptor = JSON.parse(JSON.stringify(raw)) as PluginDescriptor;
      tools.push(makeAgentTool({ name: descriptor.toolId, description: descriptor.description +
        ' (plugin ' + descriptor.pluginId + ', version ' + descriptor.version + ', hash ' + descriptor.packageHash + '). Each host call uses its current approval.',
        pluginEnvelope: descriptor.envelope, allowsAutoApproval: false,
        parameters: () => makeInputSchemaObj((descriptor.inputSchema['properties'] ?? {}) as JsonObject,
          Array.isArray(descriptor.inputSchema['required']) ? descriptor.inputSchema['required'] as string[] : null), execute: adapterRequired }));
    }
  }
  return tools;
};
