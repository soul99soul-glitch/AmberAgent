// mcp_tools — MCP 工具桥接(管理工具三件 + createRunTools mcp__ 组)
//
// Android 基准:
//   app/core/ai/tools/McpManagementTools.kt(245 行)— mcp_list/mcp_call_tool/
//     mcp_test + mcp_import_from_skill(:131-167;D-123 skills 子系统落地后并入)
//   ChatService.kt:2254-2282 — createRunTools mcp 组:getAllAvailableTools 逐个
//     Tool('mcp__'+name, needsApproval=tool.needsApproval, execute 经
//     activityStore.startTool(title='调用 MCP 工具',runtime='MCP')→ callTool →
//     complete(toolOutputPreview())/fail 包装)
//   ChatService.kt:2547-2551 — toolOutputPreview:join('\n'){Text→text/else→
//     toString()}.takeLast(1600)
// 偏差登记:
//   - activityStore(ToolActivityStore 子系统)未移植 → activity 端口可选,缺省
//     不发事件(非空实现;接线随活动面子系统,P1 登记)
//   - toolOutputPreview 非 Text 分支 Kotlin data class toString → JSON.stringify
//     (活动面落地前不可达,登记)
//   - mcp_import_from_skill:settings.update 闭包内取 existingKeys 快照
//     (字段级 RMW 由 entry 保证,与 McpSettingsPort 既有注释同)

import type { JsonObject, JsonValue } from './json.ts';
import type { AbortSignalLike } from '@amber/deepread-domain';
import { makeAgentTool } from './tool.ts';
import type { AgentTool, InputSchemaObj } from './tool.ts';
import type { UIMessagePart } from './message.ts';
import { McpManager } from './mcp_manager.ts';
import type { McpMessagePart, McpSettingsPort } from './mcp_manager.ts';
import type { McpServerConfig, McpTool, McpStatus } from './mcp_config.ts';
import { parseMcpServersFromJson } from './mcp_import.ts';

// ===== helpers(McpManagementTools.kt:170-244) =====

const isObj = (v: JsonValue | undefined): v is JsonObject =>
  typeof v === 'object' && v !== null && !Array.isArray(v);

// contentOrNull 语义:primitive → 内容串(number/boolean → String),JsonNull → null
const contentOrNull = (v: JsonValue | undefined): string | null => {
  if (v === undefined || v === null) return null;
  if (typeof v === 'string') return v;
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  return null;
};

// :170-185 — arguments ?? args ?? {};primitive 串再解析;数组 → error
export const mcpArgumentsObject = (input: JsonValue): JsonObject => {
  const obj: JsonObject = isObj(input) ? input : {};
  const raw: JsonValue | undefined = obj['arguments'] ?? obj['args'];
  if (raw === undefined) return {};
  if (isObj(raw)) return raw;
  if (typeof raw === 'string' || typeof raw === 'number' || typeof raw === 'boolean') {
    const text: string = contentOrNull(raw)?.trim() ?? '';
    if (text.length === 0) return {};
    const parsed: JsonValue = JSON.parse(text) as JsonValue;
    // .jsonObject  getter:非 object 抛(kotlinx 同语义)
    if (!isObj(parsed)) throw new Error(`Element ${typeof parsed} is not a JsonObject`);
    return parsed;
  }
  throw new Error('arguments must be a JSON object');
};

// :187-193
const findMcpServer = (settings: McpSettingsPort, input: JsonValue): McpServerConfig => {
  const obj: JsonObject = isObj(input) ? input : {};
  const serverId: string | null = contentOrNull(obj['server_id']);
  const name: string | null = contentOrNull(obj['name']);
  const server: McpServerConfig | undefined = settings.getMcpServers()
    .find((s: McpServerConfig): boolean =>
      (serverId !== null && s.id === serverId) ||
      (name !== null && name.trim().length > 0 && s.commonOptions.name === name));
  if (server === undefined) throw new Error('MCP server not found');
  return server;
};

// :233-239
export const mcpStatusToString = (status: McpStatus): string => {
  if (status.kind === 'idle') return 'idle';
  if (status.kind === 'connecting') return 'connecting';
  if (status.kind === 'connected') return 'connected';
  if (status.kind === 'reconnecting') return `reconnecting:${status.attempt}/${status.maxAttempts}`;
  return `error:${status.message}`;
};

// :241-244
export const mcpImportKey = (config: McpServerConfig): string =>
  `${config.kind}|${config.commonOptions.name}|${config.url}`;

// InputSchema.Obj data class toString 逐字:Obj(properties=<json>, required=<list|null>)
const schemaToKotlinString = (schema: InputSchemaObj): string => {
  if (schema.jsonSchema !== undefined) return JSON.stringify(schema.jsonSchema);
  const required: string = schema.required === null
    ? 'null' : `[${schema.required.join(', ')}]`;
  return `Obj(properties=${JSON.stringify(schema.properties)}, required=${required})`;
};

// :195-231(键序 = buildJsonObject 插入序)
const serverToJson = (server: McpServerConfig, status: McpStatus | undefined,
  includeTools: boolean, includeSchema: boolean): JsonObject => {
  const tools: McpTool[] = server.commonOptions.tools;
  const out: JsonObject = {
    id: server.id,
    name: server.commonOptions.name,
    enabled: server.commonOptions.enable,
    status: mcpStatusToString(status ?? { kind: 'idle' }),
    tool_count: tools.length,
    enabled_tool_count: tools.filter((t: McpTool): boolean => t.enable).length,
    type: server.kind,
    url: server.url,
  };
  if (includeTools) {
    const arr: JsonObject[] = tools.map((tool: McpTool): JsonObject => {
      const t: JsonObject = {
        name: tool.name,
        description: (tool.description ?? '').slice(0, 240),
        enabled: tool.enable,
        needs_approval: tool.needsApproval,
      };
      if (includeSchema) {
        t['schema'] = tool.inputSchema === null ? '' : schemaToKotlinString(tool.inputSchema);
      }
      if (tool.annotations !== undefined) t['annotations'] = tool.annotations;
      return t;
    });
    out['tools'] = arr;
  }
  return out;
};

// toBooleanStrictOrNull:'true'/'false' 严格,否则 null → 默认
const strictBool = (v: JsonValue | undefined): boolean | null => {
  if (typeof v === 'boolean') return v;
  if (v === 'true') return true;
  if (v === 'false') return false;
  return null;
};

// ===== createMcpManagementTools(:23-168,去 skill 件) =====

export interface McpManagementToolsDeps {
  settings: McpSettingsPort;
  manager: McpManager;
  // D-123:skills 子系统落地后由 entry 恒传;缺省 → 不出 mcp_import_from_skill
  skills?: McpSkillManagerPort;
  assistantIds?: string[];
}

// SkillManager 读面(McpManagementTools.kt:26/143-147 用例最小集)
export interface McpSkillManagerPort {
  getSkillDir(skillName: string): string | null;
  fileExists(path: string): boolean;
  readFileText(path: string): string;
}

export const createMcpManagementTools = (deps: McpManagementToolsDeps): AgentTool[] => [
  makeAgentTool({
    name: 'mcp_list',
    description: 'List configured MCP servers, enabled state, connection status, and known ' +
      'tool counts. Pass include_tools=true to see callable MCP tool names.',
    parameters: (): InputSchemaObj => ({
      type: 'object',
      properties: {
        include_tools: {
          type: 'boolean',
          description: 'Include enabled/disabled tool names for each server. Defaults to true.',
        },
        include_schema: {
          type: 'boolean',
          description: 'Include MCP input schemas. Defaults to false.',
        },
      },
      required: null,
    }),
    execute: (input: JsonValue): Promise<UIMessagePart[]> => {
      const obj: JsonObject = isObj(input) ? input : {};
      const includeTools: boolean = strictBool(obj['include_tools']) ?? true;
      const includeSchema: boolean = strictBool(obj['include_schema']) ?? false;
      const statusMap: ReadonlyMap<string, McpStatus> = deps.manager.getStatusMap();
      const servers: JsonObject[] = deps.settings.getMcpServers()
        .map((server: McpServerConfig): JsonObject =>
          serverToJson(server, statusMap.get(server.id), includeTools, includeSchema));
      const payload: JsonObject = {
        servers: servers,
        call_tool: 'Use mcp_call_tool with server_id or name, tool_name, and arguments ' +
          'to call one of these tools directly.',
      };
      return Promise.resolve([{ type: 'text', text: JSON.stringify(payload), metadata: null }]);
    },
  }),
  makeAgentTool({
    name: 'mcp_call_tool',
    description: 'Call one tool exposed by a configured MCP server. Use mcp_list ' +
      'include_tools=true first to discover server_id/name, tool_name, and input schema.',
    parameters: (): InputSchemaObj => ({
      type: 'object',
      properties: {
        server_id: {
          type: 'string',
          description: 'Optional MCP server id. Recommended when multiple servers expose ' +
            'the same tool name.',
        },
        name: {
          type: 'string',
          description: 'Optional MCP server name.',
        },
        tool_name: {
          type: 'string',
          description: 'MCP tool name to call, for example search_doc.',
        },
        arguments: {
          type: 'object',
          description: 'JSON object arguments for the MCP tool. A JSON string is also ' +
            'accepted for compatibility.',
        },
      },
      required: ['tool_name'],
    }),
    needsApproval: true,
    allowsAutoApproval: true,
    execute: async (input: JsonValue, signal?: AbortSignalLike): Promise<UIMessagePart[]> => {
      const obj: JsonObject = isObj(input) ? input : {};
      const serverId: string | null = contentOrNull(obj['server_id']);
      const serverName: string | null = contentOrNull(obj['name']);
      const toolName: string | null = contentOrNull(obj['tool_name']);
      if (toolName === null) throw new Error('tool_name is required');
      return deps.manager.callConfiguredTool(serverId, serverName, toolName,
        mcpArgumentsObject(input), signal, deps.assistantIds);
    },
  }),
  makeAgentTool({
    name: 'mcp_test',
    description: 'Test one configured MCP server by id or name and refresh its tool list.',
    parameters: (): InputSchemaObj => ({
      type: 'object',
      properties: {
        server_id: {
          type: 'string',
          description: 'MCP server id.',
        },
        name: {
          type: 'string',
          description: 'MCP server name.',
        },
      },
      required: null,
    }),
    needsApproval: true,
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const server: McpServerConfig = findMcpServer(deps.settings, input);
      await deps.manager.addClient(server);
      const status: McpStatus = deps.manager.getStatusMap().get(server.id) ?? { kind: 'idle' };
      const payload: JsonObject = {
        server: serverToJson(server, status, false, false),
        status: mcpStatusToString(status),
      };
      return [{ type: 'text', text: JSON.stringify(payload), metadata: null }];
    },
  }),
  // :131-167 — mcp_import_from_skill(D-123 落地;skills 子系统已移植)
  ...(deps.skills !== undefined ? [makeAgentTool({
    name: 'mcp_import_from_skill',
    description: 'Import standard mcp.json from an installed Skill into the global ' +
      'AmberAgent MCP settings.',
    parameters: (): InputSchemaObj => ({
      type: 'object',
      properties: {
        skill_name: { type: 'string', description: 'Installed skill name.' },
      },
      required: ['skill_name'],
    }),
    needsApproval: true,
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const skills: McpSkillManagerPort = deps.skills as McpSkillManagerPort;
      const obj: JsonObject = isObj(input) ? input : {};
      const skillName: string | null = contentOrNull(obj['skill_name']);
      if (skillName === null) throw new Error('skill_name is required');
      // :143 — getSkillDir(name)?.resolve("mcp.json") ?: error
      const dir: string | null = skills.getSkillDir(skillName);
      if (dir === null) throw new Error(`Skill not found: ${skillName}`);
      const mcpFile: string = `${dir}/mcp.json`;
      if (!skills.fileExists(mcpFile)) {
        throw new Error(`Skill '${skillName}' does not contain mcp.json`);
      }
      const configs: McpServerConfig[] = parseMcpServersFromJson(skills.readFileText(mcpFile));
      if (configs.length === 0) {
        throw new Error('mcp.json does not contain valid MCP servers');
      }
      // :152-158 — existingKeys 在 update 闭包内取快照(mcpImportKey 去重,:82 既有)
      let importedCount: number = 0;
      deps.settings.updateMcpServers((old: McpServerConfig[]): McpServerConfig[] => {
        const existingKeys: string[] = old.map(mcpImportKey);
        const newConfigs: McpServerConfig[] = configs.filter(
          (c: McpServerConfig): boolean => existingKeys.indexOf(mcpImportKey(c)) < 0);
        importedCount = newConfigs.length;
        return old.concat(newConfigs);
      });
      const payload: JsonObject = {
        success: true,
        skill_name: skillName,
        imported_count: importedCount,
        already_exists_count: configs.length - importedCount,
      };
      return Promise.resolve([{ type: 'text', text: JSON.stringify(payload), metadata: null }]);
    },
  })] : []),
];

// ===== createRunTools mcp__ 组(ChatService.kt:2254-2282) =====

// ToolActivityStore 端口(子系统未移植,可选 — P1 登记)
export interface McpToolActivityPort {
  startTool(toolName: string, title: string, inputPreview: string, runtime: string): string;
  complete(toolCallId: string, outputPreview: string): void;
  fail(toolCallId: string, error: Error): void;
}

// toolOutputPreview(ChatService.kt:2547-2551;else 分支偏差见头注)
export const toolOutputPreview = (parts: UIMessagePart[]): string => {
  const joined: string = parts.map((part: UIMessagePart): string => {
    if (part.type === 'text') return part.text;
    return JSON.stringify(part);
  }).join('\n');
  return joined.slice(Math.max(0, joined.length - 1600));
};

export interface McpServerToolsDeps {
  manager: McpManager;
  activity?: McpToolActivityPort;
  // 本轮 assistant 的 MCP server 快照(防全局 provider 被并发页面改写)
  assistantIds?: string[];
}

export const createMcpServerTools = (deps: McpServerToolsDeps): AgentTool[] => {
  const tools: McpTool[] = deps.manager.getAllAvailableTools(deps.assistantIds);
  const counts: Map<string, number> = new Map();
  for (const tool of tools) counts.set(tool.name, (counts.get(tool.name) ?? 0) + 1);
  const usedNames: Set<string> = new Set<string>();
  return tools.map((tool: McpTool): AgentTool => {
    const duplicate: boolean = (counts.get(tool.name) ?? 0) > 1;
    const serverSuffix: string = (tool.serverId ?? 'unknown').replace(/[^A-Za-z0-9_]/g, '_');
    // 全局唯一分配:同 server 重名工具/不同 server 清洗后 suffix 碰撞都会让
    // createToolRegistry 抛 Duplicate 并阻断整轮工具构建
    const baseName: string = duplicate
      ? `mcp__${tool.name}__${serverSuffix}` : `mcp__${tool.name}`;
    let exposedName: string = baseName;
    if (usedNames.has(baseName)) {
      let seq: number = 2;
      while (usedNames.has(`${baseName}__${seq}`)) seq++;
      exposedName = `${baseName}__${seq}`;
    }
    usedNames.add(exposedName);
    return makeAgentTool({
      name: exposedName,
      description: tool.description ?? '',
      parameters: (): InputSchemaObj | null => tool.inputSchema,
      needsApproval: tool.needsApproval,
      mcpTarget: tool.serverId === undefined ? undefined : { serverId: tool.serverId, toolName: tool.name },
      execute: async (input: JsonValue, signal?: AbortSignalLike): Promise<UIMessagePart[]> => {
        const toolCallId: string | null = deps.activity !== undefined
          ? deps.activity.startTool(exposedName, '调用 MCP 工具', JSON.stringify(input), 'MCP')
          : null;
        try {
          if (!isObj(input)) throw new Error(`Element ${typeof input} is not a JsonObject`);
          const result: McpMessagePart[] = await deps.manager.callTool(
            tool.name, input, tool.serverId, signal, deps.assistantIds);
          if (deps.activity !== undefined && toolCallId !== null) {
            deps.activity.complete(toolCallId, toolOutputPreview(result));
          }
          return result;
        } catch (e) {
          if (deps.activity !== undefined && toolCallId !== null) {
            deps.activity.fail(toolCallId, e instanceof Error ? e : new Error(String(e)));
          }
          throw e;
        }
      },
    });
  });
};
