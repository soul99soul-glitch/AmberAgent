// mcp_describe_tool — 单个已发现 MCP 工具的完整描述 + 输入 JSON Schema
//
// iOS 基准:IOSSkillMcpTools.mcpDescribeToolJSON / Tool.kt createMcpDescribeToolDeclaration。
// 只读目录查询:读 settings 里持久化的 server.commonOptions.tools,不出网、不改配置
// (与 mcp_list 同级,无需审批)。mcp_list 的 schema 默认不返回,模型在 mcp_call_tool
// 之前用本工具取精确参数名/类型。描述与 schema 来自外部 MCP server,标记 untrusted。
// 失败以 {ok:false,error,valid_*} JSON 返回(不抛),便于模型自纠。

import type { JsonObject, JsonValue } from './json.ts';
import { makeAgentTool, toolParametersToJson } from './tool.ts';
import type { AgentTool, InputSchemaObj } from './tool.ts';
import type { UIMessagePart } from './message.ts';
import type { McpSettingsPort } from './mcp_manager.ts';
import type { McpServerConfig, McpTool } from './mcp_config.ts';

export interface McpDescribeToolDeps {
  settings: McpSettingsPort;
  // 当前助手挂载的 server id;与 mcp_call_tool 同一可调用判定(未传 = 不限制)
  assistantIds?: string[];
}

const isObj = (v: JsonValue | undefined): v is JsonObject =>
  typeof v === 'object' && v !== null && !Array.isArray(v);

const firstString = (obj: JsonObject, keys: string[]): string => {
  for (const key of keys) {
    const v: JsonValue | undefined = obj[key];
    if (typeof v === 'string' && v.trim().length > 0) return v.trim();
  }
  return '';
};

const json = (value: JsonObject): UIMessagePart[] =>
  [{ type: 'text', text: JSON.stringify(value), metadata: null }];

const sortedNames = (names: string[]): string[] => names.slice().sort();

export const createMcpDescribeTool = (deps: McpDescribeToolDeps): AgentTool => makeAgentTool({
  name: 'mcp_describe_tool',
  description: 'Return the full description and input JSON schema of one discovered MCP tool. ' +
    'Call this before mcp_call_tool when you need the exact argument names and types for ' +
    'a tool\'s `arguments`. Read-only; does not contact the server.',
  parameters: (): InputSchemaObj => ({
    type: 'object',
    properties: {
      server: { type: 'string', description: 'the configured MCP server name (or id)' },
      tool: { type: 'string', description: 'the discovered tool name on that server' },
    },
    required: ['server', 'tool'],
  }),
  execute: (input: JsonValue): Promise<UIMessagePart[]> => {
    const obj: JsonObject = isObj(input) ? input : {};
    // server_id / name / tool_name:与 mcp_call_tool 参数名互通,降低模型混用时的失败率
    const serverRef: string = firstString(obj, ['server', 'server_id', 'name']);
    const toolName: string = firstString(obj, ['tool', 'tool_name']);
    const servers: McpServerConfig[] = deps.settings.getMcpServers();
    const validServers: string[] = sortedNames(servers.map((s: McpServerConfig): string => s.commonOptions.name));
    const byId: McpServerConfig[] = servers.filter((s: McpServerConfig): boolean => s.id === serverRef);
    const matches: McpServerConfig[] = byId.length > 0 ? byId
      : servers.filter((s: McpServerConfig): boolean => s.commonOptions.name === serverRef);
    if (matches.length === 0) {
      return Promise.resolve(json({ ok: false, error: `MCP server not found: ${serverRef}`, valid_servers: validServers }));
    }
    if (matches.length > 1) {
      return Promise.resolve(json({ ok: false, error: `MCP server name is ambiguous: ${serverRef}; use the server id.`,
        server_ids: matches.map((s: McpServerConfig): string => s.id) }));
    }
    const server: McpServerConfig = matches[0];
    const serverName: string = server.commonOptions.name;
    const tools: McpTool[] = server.commonOptions.tools;
    const tool: McpTool | undefined = tools.find((t: McpTool): boolean => t.name === toolName);
    if (tool === undefined) {
      return Promise.resolve(json({ ok: false, error: `MCP tool not found on server '${serverName}': ${toolName}`,
        valid_tools: sortedNames(tools.map((t: McpTool): string => t.name)) }));
    }
    const result: JsonObject = {
      ok: true,
      server: serverName,
      server_id: server.id,
      tool: tool.name,
      enabled: tool.enable,
      server_enabled: server.commonOptions.enable,
      callable_in_this_conversation: server.commonOptions.enable && tool.enable
        && (deps.assistantIds === undefined || deps.assistantIds.includes(server.id)),
      call_with: { server_id: server.id, tool_name: tool.name },
      needs_approval: tool.needsApproval,
      description: tool.description ?? '',
      input_schema: tool.inputSchema === null ? null : toolParametersToJson(tool.inputSchema),
      untrusted: true,
    };
    if (tool.annotations !== undefined) result['annotations'] = tool.annotations;
    return Promise.resolve(json(result));
  },
});
