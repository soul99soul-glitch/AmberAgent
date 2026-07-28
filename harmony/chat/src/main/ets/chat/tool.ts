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
import type { UIMessage, UIMessagePart } from './message.ts';
import type { ChatModel, ChatToolDefinition } from './provider_model.ts';

// ===== InputSchema(Tool.kt:28-36) =====

export interface InputSchemaObj {
  type: 'object';
  properties: JsonObject;
  required: string[] | null;
}

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
  execute: (input: JsonValue) => Promise<UIMessagePart[]>;
}

export interface AgentToolOpts {
  name: string;
  description: string;
  parameters?: () => InputSchemaObj | null;
  systemPrompt?: (model: ChatModel, messages: UIMessage[]) => string;
  needsApproval?: boolean;
  allowsAutoApproval?: boolean;
  mandatoryApproval?: boolean;
  execute: (input: JsonValue) => Promise<UIMessagePart[]>;
}

export const makeAgentTool = (opts: AgentToolOpts): AgentTool => ({
  name: opts.name,
  description: opts.description,
  parameters: opts.parameters ?? ((): InputSchemaObj | null => null),
  systemPrompt: opts.systemPrompt ?? ((_: ChatModel, __: UIMessage[]): string => ''),
  needsApproval: opts.needsApproval ?? false,
  allowsAutoApproval: opts.allowsAutoApproval ?? true,
  mandatoryApproval: opts.mandatoryApproval ?? false,
  execute: opts.execute,
});

// ===== → 请求侧定义(serialName "object" → type:'object') =====

// Android 各 provider 请求构建层把 Tool.parameters 序列化为
//   {"type":"object","properties":{...},"required":[...]};required 为 null 时省略
export const toolParametersToJson = (schema: InputSchemaObj | null): JsonObject => {
  const out: JsonObject = { type: 'object', properties: schema !== null ? schema.properties : {} };
  if (schema !== null && schema.required !== null) {
    out['required'] = schema.required;
  }
  return out;
};

export const toChatToolDefinition = (tool: AgentTool): ChatToolDefinition => ({
  name: tool.name,
  description: tool.description,
  parameters: toolParametersToJson(tool.parameters()),
});
