// tool_exposure — ToolExposureState 懒暴露(D-062)
//
// Android 基准: ToolSearch.kt:270-311(ToolExposureState 类 + from)+
//   :373-380(UIMessagePart.Tool.expandedToolNames)
// 语义:
//   - 非发现工具数 > TOOL_SEARCH_AUTO_THRESHOLD(40)且目录含 tool_search →
//     懒模式:初始仅常驻工具可见(isResidentTool),tool_search 执行的
//     expanded_tools / 待恢复 pending 工具逐步暴露
//   - 否则全量可见(bypass)
// 接线点(GenerationHandler.kt):from(:159)/exposeToolNames(pending)(:167)/
//   toolsForStep(:183)/observeExecutedTools(:364)
// 偏差登记:无 — 纯内存状态机,全量忠实

import type { JsonObject, JsonValue } from './json.ts';
import type { UIMessagePart, UIMessagePartTool } from './message.ts';
import type { AgentTool } from './tool.ts';
import { createToolRegistry } from './tool_registry.ts';
import {
  isResidentTool, TOOL_SEARCH_TOOL_NAME, TOOL_SEARCH_AUTO_THRESHOLD,
} from './builtin_introspection_tools.ts';

export interface ToolExposureState {
  // Android enabled = lazyMode
  readonly enabled: boolean;
  toolsForStep: () => AgentTool[];
  exposeToolNames: (names: string[]) => void;
  observeExecutedTools: (executedTools: UIMessagePartTool[]) => void;
  replaceTools: (tools: AgentTool[]) => void;
}

// expandedToolNames(:373-380):tool_search 输出 Text 段 JSON →
//   expanded_tools 字符串数组;非法 JSON/缺键/非字符串项静默丢弃
//   (runCatching getOrDefault emptyList / mapNotNull contentOrNull)
const expandedToolNames = (tool: UIMessagePartTool): string[] => {
  const out: string[] = [];
  for (const part of tool.output) {
    if (part.type !== 'text') continue;
    const text: string = (part as { text: string }).text;
    let payload: JsonObject;
    try {
      const parsed: JsonValue = JSON.parse(text) as JsonValue;
      if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) continue;
      payload = parsed as JsonObject;
    } catch {
      continue;
    }
    const names: JsonValue | undefined = payload['expanded_tools'];
    if (!Array.isArray(names)) continue;
    for (const n of names) {
      if (typeof n === 'string') out.push(n);
    }
  }
  return out;
};

// from(:297-311)
export const createToolExposureState = (allTools: AgentTool[]): ToolExposureState => {
  const toolsByName: Map<string, AgentTool> = new Map<string, AgentTool>();
  for (const t of allTools) toolsByName.set(t.name, t);
  const toolCount: number = allTools.filter(
    (t: AgentTool): boolean =>
      t.name !== TOOL_SEARCH_TOOL_NAME
        && t.name !== 'tools_list'
        && t.name !== 'tool_policy_explain',
  ).length;
  const hasSearch: boolean = allTools.some(
    (t: AgentTool): boolean => t.name === TOOL_SEARCH_TOOL_NAME);
  const lazyMode: boolean = toolCount > TOOL_SEARCH_AUTO_THRESHOLD && hasSearch;

  const exposedNames: Set<string> = new Set<string>();
  if (!lazyMode) {
    for (const t of allTools) exposedNames.add(t.name);
  } else {
    // runCatching { ToolRegistry.from(tools) }.getOrNull()
    let categoryOf: (name: string) => string | null = (_name: string): null => null;
    try {
      const registry = createToolRegistry(allTools);
      categoryOf = (name: string): string | null =>
        registry.metadataFor(name)?.category ?? null;
    } catch {
      categoryOf = (_name: string): null => null;
    }
    for (const tool of allTools) {
      if (isResidentTool(tool.name, categoryOf(tool.name))) {
        exposedNames.add(tool.name);
      }
    }
  }

  const state: ToolExposureState = {
    enabled: lazyMode,
    replaceTools: (tools: AgentTool[]): void => {
      allTools = tools;
      toolsByName.clear();
      for (const tool of tools) toolsByName.set(tool.name, tool);
      for (const name of exposedNames) {
        if (!toolsByName.has(name)) exposedNames.delete(name);
      }
      if (!lazyMode) for (const tool of tools) exposedNames.add(tool.name);
    },
    // toolsForStep(:279-281):非懒 → 全量;懒 → 原序过滤(不重排)
    toolsForStep: (): AgentTool[] => {
      if (!lazyMode) return allTools;
      return allTools.filter((t: AgentTool): boolean => exposedNames.has(t.name));
    },
    // exposeToolNames(:283-289):懒模式守卫 + 仅已知名
    exposeToolNames: (names: string[]): void => {
      if (!lazyMode) return;
      for (const name of names) {
        if (toolsByName.has(name)) exposedNames.add(name);
      }
    },
    // observeExecutedTools(:291-301):仅 tool_search 的 expanded_tools
    observeExecutedTools: (executedTools: UIMessagePartTool[]): void => {
      if (!lazyMode) return;
      const expanded: string[] = [];
      for (const tool of executedTools) {
        if (tool.toolName !== TOOL_SEARCH_TOOL_NAME) continue;
        for (const name of expandedToolNames(tool)) expanded.push(name);
      }
      state.exposeToolNames(expanded);
    },
  };
  return state;
};
