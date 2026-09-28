// tool_registry — ToolRegistry 注册/包装层(D-058)
//
// Android 基准: feature/tools/api ToolRegistry.kt:31-161
//   - ToolMetadata 9 字段(:31-41)+ toMetadata 派生(:99-124)
//   - from(tools):重名 require 失败,文案逐字(:84-97)
//   - tools():包装副本 — needsApproval/allowsAutoApproval 按 metadata 覆盖、
//     display_title 注入(subagent_start 豁免)、execute 包 output 预算(:66-75)
//   - metadataFor/evaluateInvocation(:77-81)
// 策略派生全部复用 tool_policy.ts(D-056);本文件仅为注册/包装胶水。
// 偏差登记:scopedToConversation(activityStore 包装,ChatService:2332-2343)
//   与 ToolProfileFilter(助手 toolProfile 字段鸿蒙未建)= P1。

import type { JsonObject, JsonValue } from './json.ts';
import type { AbortSignalLike } from '@amber/deepread-domain';
import type { AgentTool } from './tool.ts';
import type { ToolRisk, ToolInvocationPolicy } from './tool_policy.ts';
import {
  toolCategory, toolMutatesState, toolRiskProfile, toolSensitiveRead,
  toolOutputBudgetChars, requiresFailClosedAutoApproval,
  enforceOutputBudget, withDisplayTitleHint, toolInvocationPolicy,
} from './tool_policy.ts';
import type { InputSchemaObj } from './tool.ts';
import type { UIMessagePart } from './message.ts';

// ===== ToolMetadata(:31-41) =====

export interface ToolMetadata {
  name: string;
  category: string;
  mutates: boolean;
  sensitiveRead: boolean;
  needsApproval: boolean;
  autoApprovable: boolean;
  outputBudgetChars: number;
  risk: ToolRisk;
  mandatoryApproval: boolean;
}

// toMetadata(:99-124 派生链逐字)
export const toToolMetadata = (tool: AgentTool): ToolMetadata => {
  const envelope = tool.pluginEnvelope ?? tool.recipeEnvelope;
  if (envelope !== undefined) {
    return { name: tool.name, category: tool.pluginEnvelope === undefined ? 'recipe' : 'plugin', mutates: envelope.mutates,
      sensitiveRead: envelope.risk !== 'normal', needsApproval: envelope.needsApproval,
      autoApprovable: false, outputBudgetChars: toolOutputBudgetChars(tool.name),
      risk: envelope.risk, mandatoryApproval: false };
  }
  const mutates: boolean = toolMutatesState(tool.name);
  const category: string = toolCategory(tool.name);
  const riskProfile: { risk: ToolRisk; explicit: boolean } = toolRiskProfile(tool.name);
  const risk: ToolRisk = riskProfile.risk;
  const effectiveNeedsApproval: boolean = tool.mandatoryApproval
    || tool.needsApproval || mutates || risk === 'high';
  const effectiveAutoApproval: boolean = !tool.mandatoryApproval
    && tool.allowsAutoApproval
    && risk !== 'high'
    && !requiresFailClosedAutoApproval(mutates, category, riskProfile.explicit);
  return {
    name: tool.name,
    category,
    mutates,
    sensitiveRead: toolSensitiveRead(tool.name),
    needsApproval: effectiveNeedsApproval,
    autoApprovable: effectiveAutoApproval,
    outputBudgetChars: toolOutputBudgetChars(tool.name),
    risk,
    mandatoryApproval: tool.mandatoryApproval,
  };
};

// ===== ToolRegistry(:61-97) =====

export interface ToolRegistry {
  metadata: ToolMetadata[];
  tools: () => AgentTool[];
  metadataFor: (name: string) => ToolMetadata | null;
  evaluateInvocation: (toolName: string, input?: JsonValue | null) => ToolInvocationPolicy | null;
}

interface RegistryEntry {
  tool: AgentTool;
  metadata: ToolMetadata;
}

export const createToolRegistry = (tools: AgentTool[]): ToolRegistry => {
  // from(:84-97):重名 require 失败(文案逐字,排序逗号连接)
  const counts = new Map<string, number>();
  for (const t of tools) {
    counts.set(t.name, (counts.get(t.name) ?? 0) + 1);
  }
  const duplicates: string[] = [];
  for (const [name, count] of counts) {
    if (count > 1) duplicates.push(name);
  }
  if (duplicates.length > 0) {
    duplicates.sort();
    throw new Error(`Duplicate tool names registered: ${duplicates.join(', ')}`);
  }
  const entries: RegistryEntry[] = tools.map((tool: AgentTool): RegistryEntry => ({
    tool,
    metadata: toToolMetadata(tool),
  }));
  return {
    metadata: entries.map((e: RegistryEntry): ToolMetadata => e.metadata),
    // tools()(:66-75):每次调用生成新包装副本(Android 同 — copy 逐次新建)
    tools: (): AgentTool[] => entries.map((e: RegistryEntry): AgentTool => ({
      name: e.tool.name,
      description: e.tool.description,
      systemPrompt: e.tool.systemPrompt,
      needsApproval: e.metadata.needsApproval,
      allowsAutoApproval: e.metadata.autoApprovable,
      mandatoryApproval: e.tool.mandatoryApproval,
      recipeEnvelope: e.tool.recipeEnvelope,
      pluginEnvelope: e.tool.pluginEnvelope,
      mcpTarget: e.tool.mcpTarget,
      parameters: (): InputSchemaObj | null =>
        withDisplayTitleHint(e.tool.parameters(), e.tool.name),
      execute: (input: JsonValue, signal?: AbortSignalLike): Promise<UIMessagePart[]> =>
        e.tool.execute(input, signal).then(
          (parts: UIMessagePart[]): UIMessagePart[] =>
            enforceOutputBudget(parts, e.metadata.outputBudgetChars)),
    })),
    metadataFor: (name: string): ToolMetadata | null =>
      entries.find((e: RegistryEntry): boolean => e.metadata.name === name)?.metadata ?? null,
    evaluateInvocation: (toolName: string, input: JsonValue | null = null): ToolInvocationPolicy | null => {
      const hit: RegistryEntry | undefined = entries.find(
        (e: RegistryEntry): boolean => e.tool.name === toolName);
      return hit !== undefined ? toolInvocationPolicy(hit.tool, input) : null;
    },
  };
};
