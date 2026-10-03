// tool — 工具定义模型(D-056)
//
// Android 基准: ai/core/Tool.kt(全文 37 行)
//   - Tool data class(:11-26):name/description/parameters/systemPrompt/
//     needsApproval/allowsAutoApproval/mandatoryApproval/execute
//   - InputSchema sealed(:28-36):Obj(properties, required?),serialName "object"
// 偏差登记:
//   - Kotlin 函数类型默认值 → makeAgentTool opts 可选字段(默认同 Android)
//   - systemPrompt 的 Model 参 → ChatModel(provider_model.ts 既有抽象)

import type { JsonObject, JsonValue } from './json.ts';
import type { AbortSignalLike } from '@amber/deepread-domain';
import type { UIMessage, UIMessagePart } from './message.ts';
import type { ChatModel, ChatToolDefinition } from './provider_model.ts';
import type { RecipeEnvelope } from './recipes/models.ts';
import type { PluginEnvelope } from './plugins/models.ts';

// ===== InputSchema(Tool.kt:28-36) =====

export interface InputSchemaObj {
  type: 'object';
  properties: JsonObject;
  required: string[] | null;
  // MCP may include root composition/definitions beyond the local shorthand.
  jsonSchema?: JsonObject;
}

export interface McpToolIdentity { serverId: string; toolName: string; }

export const makeInputSchemaObj = (
  properties: JsonObject, required: string[] | null = null,
): InputSchemaObj => ({ type: 'object', properties, required });

// ===== AgentTool(Tool.kt:11-26) =====

export interface AgentTool {
  name: string;
  description: string;
  parameters: () => InputSchemaObj | null;
  systemPrompt: (model: ChatModel, messages: UIMessage[]) => string;
  needsApproval: boolean;
  allowsAutoApproval: boolean;
  // Tool.kt:19-24 注释语义:绕过普通 auto-approval 与 run 内信任,仅
  //   "auto approve high-risk tools" 设置可无人值守执行(如 wm_eval)
  mandatoryApproval: boolean;
  recipeEnvelope?: RecipeEnvelope;
  pluginEnvelope?: PluginEnvelope;
  mcpTarget?: McpToolIdentity;
  // signal:调用方(工具循环)的取消信号 — 网络/长任务工具(MCP、搜索)应消费;
  //   其余工具忽略即可(可选参数,全部既有实现兼容)
  execute: (input: JsonValue, signal?: AbortSignalLike) => Promise<UIMessagePart[]>;
}

export interface AgentToolOpts {
  name: string;
  description: string;
  parameters?: () => InputSchemaObj | null;
  systemPrompt?: (model: ChatModel, messages: UIMessage[]) => string;
  needsApproval?: boolean;
  allowsAutoApproval?: boolean;
  mandatoryApproval?: boolean;
  recipeEnvelope?: RecipeEnvelope;
  pluginEnvelope?: PluginEnvelope;
  mcpTarget?: McpToolIdentity;
  execute: (input: JsonValue, signal?: AbortSignalLike) => Promise<UIMessagePart[]>;
}

export const makeAgentTool = (opts: AgentToolOpts): AgentTool => ({
  name: opts.name,
  description: opts.description,
  parameters: opts.parameters ?? ((): InputSchemaObj | null => null),
  systemPrompt: opts.systemPrompt ?? ((_: ChatModel, __: UIMessage[]): string => ''),
  needsApproval: opts.needsApproval ?? false,
  allowsAutoApproval: opts.allowsAutoApproval ?? true,
  mandatoryApproval: opts.mandatoryApproval ?? false,
  recipeEnvelope: opts.recipeEnvelope,
  pluginEnvelope: opts.pluginEnvelope,
  mcpTarget: opts.mcpTarget,
  execute: opts.execute,
});

// ===== → 请求侧定义(serialName "object" → type:'object') =====

// Android 各 provider 请求构建层把 Tool.parameters 序列化为
//   {"type":"object","properties":{...},"required":[...]};required 为 null 时省略
export const toolParametersToJson = (schema: InputSchemaObj | null): JsonObject => {
  if (schema?.jsonSchema !== undefined) return schema.jsonSchema;
  const out: JsonObject = { type: 'object', properties: schema !== null ? schema.properties : {} };
  if (schema !== null && schema.required !== null) {
    out['required'] = schema.required;
  }
  return out;
};

export const toChatToolDefinition = (tool: AgentTool): ChatToolDefinition => {
  const schema: InputSchemaObj | null = tool.parameters();
  const definition: ChatToolDefinition = {
    name: tool.name,
    description: tool.description,
    parameters: toolParametersToJson(schema),
  };
  if (schema?.jsonSchema !== undefined) definition.parametersJsonSchema = schema.jsonSchema;
  return definition;
};
