// mcp_config — MCP 服务器配置模型(McpConfig.kt 全文 60 行 + McpStatus.kt 9 行)
//
// Android 基准:
//   app/core/ai/mcp/McpConfig.kt — McpCommonOptions(:9-14)/McpTool(:16-25,
//     needsApproval 默认 true = fail-closed 注释逐字)/McpServerConfig sealed
//     (:27-59,@SerialName 'sse'/'streamable_http',clone 默认参 = 自身字段)
//   app/core/ai/mcp/McpStatus.kt — 五态(Idle/Connecting/Connected/
//     Reconnecting(attempt,maxAttempts)/Error(message))
//   ai/core/Tool.kt:28-36 — InputSchema.Obj(properties, required?)
//
// Uuid → string(newId,ids.ts);kotlinx encodeDefaults/explicitNulls=false
// 线格式在 mcp_config_serialize.ts。

import type { InputSchemaObj } from './tool.ts';
import type { JsonObject } from './json.ts';
import { newId } from './ids.ts';

// ===== McpCommonOptions(McpConfig.kt:9-14) =====

export interface McpCommonOptions {
  enable: boolean;
  name: string;
  headers: Array<[string, string]>;
  tools: McpTool[];
}

export interface McpCommonOptionsOpts {
  enable?: boolean;
  name?: string;
  headers?: Array<[string, string]>;
  tools?: McpTool[];
}

export const makeMcpCommonOptions = (opts: McpCommonOptionsOpts): McpCommonOptions => ({
  enable: opts.enable ?? true,
  name: opts.name ?? '',
  headers: opts.headers ?? [],
  tools: opts.tools ?? [],
});

// ===== McpTool(McpConfig.kt:16-25) =====
// Fail-closed: MCP tools not explicitly marked safe ask before running.
// Global auto-approval settings still pass them without prompting.

export interface McpTool {
  // Present on runtime-discovered tools so direct calls retain the owning
  // server identity even when multiple servers expose the same tool name.
  serverId?: string;
  enable: boolean;
  name: string;
  description: string | null;
  inputSchema: InputSchemaObj | null;
  annotations?: JsonObject;
  needsApproval: boolean;
}

export interface McpToolOpts {
  serverId?: string;
  enable?: boolean;
  name?: string;
  description?: string | null;
  inputSchema?: InputSchemaObj | null;
  annotations?: JsonObject;
  needsApproval?: boolean;
}

export const makeMcpTool = (opts: McpToolOpts): McpTool => ({
  ...(opts.serverId === undefined ? {} : { serverId: opts.serverId }),
  enable: opts.enable ?? true,
  name: opts.name ?? '',
  description: opts.description ?? null,
  inputSchema: opts.inputSchema ?? null,
  ...(opts.annotations === undefined ? {} : { annotations: opts.annotations }),
  needsApproval: opts.needsApproval ?? true,
});

// ===== McpServerConfig(McpConfig.kt:27-59) =====

export type McpServerKind = 'sse' | 'streamable_http';

interface McpServerConfigBase {
  kind: McpServerKind;
  id: string;
  commonOptions: McpCommonOptions;
  url: string;
}

export interface McpSseServerConfig extends McpServerConfigBase {
  kind: 'sse';
}

export interface McpStreamableHttpServerConfig extends McpServerConfigBase {
  kind: 'streamable_http';
}

export type McpServerConfig = McpSseServerConfig | McpStreamableHttpServerConfig;

export interface McpServerConfigOpts {
  id?: string;
  commonOptions?: McpCommonOptions;
  url?: string;
}

export const makeMcpSseServer = (opts: McpServerConfigOpts): McpSseServerConfig => ({
  kind: 'sse',
  id: opts.id ?? newId(),
  commonOptions: opts.commonOptions ?? makeMcpCommonOptions({}),
  url: opts.url ?? '',
});

export const makeMcpStreamableHttpServer = (
  opts: McpServerConfigOpts,
): McpStreamableHttpServerConfig => ({
  kind: 'streamable_http',
  id: opts.id ?? newId(),
  commonOptions: opts.commonOptions ?? makeMcpCommonOptions({}),
  url: opts.url ?? '',
});

// clone(id = this.id, commonOptions = this.commonOptions) → copy(:38-41/:52-55)
export const cloneMcpServerConfig = (
  config: McpServerConfig, id: string = config.id,
  commonOptions: McpCommonOptions = config.commonOptions,
): McpServerConfig => {
  if (config.kind === 'sse') {
    return makeMcpSseServer({ id, commonOptions, url: config.url });
  }
  return makeMcpStreamableHttpServer({ id, commonOptions, url: config.url });
};

// ===== McpStatus(McpStatus.kt 全文) =====

export type McpStatus =
  | { kind: 'idle' }
  | { kind: 'connecting' }
  | { kind: 'connected' }
  | { kind: 'reconnecting'; attempt: number; maxAttempts: number }
  | { kind: 'error'; message: string };

export const MCP_STATUS_IDLE: McpStatus = { kind: 'idle' };
export const MCP_STATUS_CONNECTING: McpStatus = { kind: 'connecting' };
export const MCP_STATUS_CONNECTED: McpStatus = { kind: 'connected' };

export const mcpStatusReconnecting = (attempt: number, maxAttempts: number): McpStatus =>
  ({ kind: 'reconnecting', attempt, maxAttempts });

export const mcpStatusError = (message: string): McpStatus => ({ kind: 'error', message });
