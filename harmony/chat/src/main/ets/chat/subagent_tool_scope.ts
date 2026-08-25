// subagent_tool_scope — child-only discovery and executable tool scope(D-132a Task 4)
// Android baseline: feature/subagent/.../SubAgentToolScope.kt(complete file).
// The manager supplies the validated allowlist ∩ parent tools in parent order. This final
// boundary also removes subagent_* tools so public supervisor tools never enter a child run.

import type { JsonObject, JsonValue } from './json.ts';
import type { AbortSignalLike } from '@amber/deepread-domain';
import type { UIMessagePart, UIMessagePartText } from './message.ts';
import type { AgentTool } from './tool.ts';
import { makeAgentTool, makeInputSchemaObj } from './tool.ts';
import type { ToolRegistry } from './tool_registry.ts';
import { createToolRegistry } from './tool_registry.ts';
import {
  createToolPolicyExplainTool,
  createToolSearchTool,
  createToolsListTool,
  TOOL_SEARCH_TOOL_NAME,
} from './builtin_introspection_tools.ts';

const TOOLS_LIST_TOOL_NAME: string = 'tools_list';
const TOOL_POLICY_EXPLAIN_TOOL_NAME: string = 'tool_policy_explain';

const isDiscoveryTool = (name: string): boolean =>
  name === TOOL_SEARCH_TOOL_NAME ||
  name === TOOLS_LIST_TOOL_NAME ||
  name === TOOL_POLICY_EXPLAIN_TOOL_NAME;

const executeJsonTool = async (
  tool: AgentTool, input: JsonValue, signal?: AbortSignalLike,
): Promise<JsonObject> => {
  const parts: UIMessagePart[] = await tool.execute(input, signal);
  const textPart: UIMessagePartText = parts[0] as UIMessagePartText;
  return JSON.parse(textPart.text) as JsonObject;
};

const textResult = (payload: JsonObject): UIMessagePart[] => [{
  type: 'text',
  text: JSON.stringify(payload),
  metadata: null,
}];

const scopedListItem = (source: JsonObject): JsonObject => {
  const item: JsonObject = {
    name: source['name'],
    category: source['category'],
    description: source['description'],
    enabled: true,
    mutates: source['mutates'],
    sensitive_read: source['sensitive_read'],
    needs_approval: source['needs_approval'],
    allows_auto_approval: source['allows_auto_approval'],
    output_budget_chars: source['output_budget_chars'],
    risk: source['risk'],
    concurrency_safe: source['concurrency_safe'],
    speculative_eligible: source['speculative_eligible'],
  };
  if ('speculative_block_reason' in source) {
    item['speculative_block_reason'] = source['speculative_block_reason'];
  }
  if ('schema' in source) item['schema'] = source['schema'];
  return item;
};

const createScopedToolsListTool = (registry: ToolRegistry): AgentTool => {
  const baseTool: AgentTool = createToolsListTool(registry);
  return makeAgentTool({
    name: TOOLS_LIST_TOOL_NAME,
    description: 'List tools available inside this subagent scope. This catalog is scoped and cannot reveal parent-only tools.',
    parameters: () => makeInputSchemaObj({
      category: {
        type: 'string',
        description: 'Optional scoped tool category filter.',
      },
      query: {
        type: 'string',
        description: 'Optional scoped tool name or description filter.',
      },
      include_schema: {
        type: 'boolean',
        description: 'Include scoped tool input schema. Defaults to false.',
      },
    }),
    execute: async (input: JsonValue, signal?: AbortSignalLike): Promise<UIMessagePart[]> => {
      const basePayload: JsonObject = await executeJsonTool(baseTool, input, signal);
      const baseTools: JsonValue = basePayload['tools'];
      const tools: JsonValue[] = Array.isArray(baseTools)
        ? baseTools.map((value: JsonValue): JsonObject => scopedListItem(value as JsonObject))
        : [];
      return textResult({
        scoped: true,
        enabled_count: tools.length,
        tools,
      });
    },
  });
};

const createScopedToolPolicyExplainTool = (registry: ToolRegistry): AgentTool => {
  const baseTool: AgentTool = createToolPolicyExplainTool(registry);
  return makeAgentTool({
    name: TOOL_POLICY_EXPLAIN_TOOL_NAME,
    description: 'Explain how this subagent scope would evaluate one allowed tool invocation without executing it.',
    parameters: () => makeInputSchemaObj(
      {
        tool_name: {
          type: 'string',
          description: 'Tool name to evaluate within this subagent scope.',
        },
        input: {
          type: 'string',
          description: 'Optional JSON string input for dynamic policy evaluation.',
        },
      },
      ['tool_name'],
    ),
    execute: async (input: JsonValue, signal?: AbortSignalLike): Promise<UIMessagePart[]> => {
      const source: JsonObject = await executeJsonTool(baseTool, input, signal);
      const payload: JsonObject = {
        scoped: true,
        status: source['status'],
        tool_name: source['tool_name'],
      };
      if (source['status'] === 'ok') {
        payload['category'] = source['category'];
        payload['risk'] = source['risk'];
        payload['mutates'] = source['mutates'];
        payload['needs_approval'] = source['needs_approval'];
        payload['allows_auto_approval'] = source['allows_auto_approval'];
        payload['concurrency_safe'] = source['concurrency_safe'];
        payload['speculative_eligible'] = source['speculative_eligible'];
        if ('speculative_block_reason' in source) {
          payload['speculative_block_reason'] = source['speculative_block_reason'];
        }
        payload['output_budget_chars'] = source['output_budget_chars'];
        payload['always_ask'] = source['always_ask'];
        if ('reason' in source) payload['reason'] = source['reason'];
      }
      return textResult(payload);
    },
  });
};

export const scopedSubAgentTools = (allowedTools: AgentTool[]): AgentTool[] => {
  if (allowedTools.length === 0) return [];

  const availableTools: AgentTool[] = allowedTools.filter(
    (tool: AgentTool): boolean => !tool.name.startsWith('subagent_'));
  if (availableTools.length === 0) return [];

  const requestedTools: Set<string> = new Set<string>();
  availableTools.forEach((tool: AgentTool): void => {
    requestedTools.add(tool.name);
  });
  const executableTools: AgentTool[] = availableTools.filter(
    (tool: AgentTool): boolean => !isDiscoveryTool(tool.name));
  const scopedRegistry: ToolRegistry = createToolRegistry(executableTools);
  const scopedTools: AgentTool[] = executableTools.slice();
  scopedTools.push(createToolSearchTool(scopedRegistry));
  if (requestedTools.has(TOOLS_LIST_TOOL_NAME)) {
    scopedTools.push(createScopedToolsListTool(scopedRegistry));
  }
  if (requestedTools.has(TOOL_POLICY_EXPLAIN_TOOL_NAME)) {
    scopedTools.push(createScopedToolPolicyExplainTool(scopedRegistry));
  }
  return scopedTools;
};
