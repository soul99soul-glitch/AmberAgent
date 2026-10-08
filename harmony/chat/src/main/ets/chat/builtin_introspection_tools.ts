// builtin_introspection_tools — 自省/发现工具组(D-060)
//
// Android 基准:
//   core/ai/tools/ToolsListTool.kt(全文)— tools_list
//   core/ai/tools/ToolPolicyExplainTool.kt(全文)— tool_policy_explain
//   feature/tools/api/.../ToolSearch.kt:1-380 — tool_search + ToolSearchIndex
//     (评分/别名/schemaFootprint)+ isResidentTool 四集合
//   LocalTools.kt:86-92 — registryIntrospectionTools 对
// 裁剪/偏差登记:
//   - AgentPermissionBroker 未移植 → capabilities 恒空:tools_list 条目
//     required_permissions=[]、risk 回落 metadata.risk.name(与 Android 零
//     capability 时逐字节一致)
//   - ToolProfileFilter D-082 已移植 → createToolSearchTool(registry, profile)
//     profile 非 null 时 trace 出 profile/profile_filtered 键(:115-118);
//     评分不受 profile 影响(Android 同 — profile 仅进 trace)
//   - ToolExposureState 懒暴露机制(toolsForStep/exposeToolNames/
//     observeExecutedTools/expandedToolNames)= P1(D-056 既登);
//     isResidentTool 为纯函数先行移植
//   - schema 字符串 = Kotlin data class toString 形态(Obj(properties=<json>,
//     required=[a, b]);kotlinx JsonObject.toString() 段为 JSON,List 段为
//     原始 join)— 非 JSON,忠实复刻

import type { JsonObject, JsonValue } from './json.ts';
import type { UIMessage, UIMessagePart } from './message.ts';
import type { ChatModel } from './provider_model.ts';
import type { MainAgentToolProfile } from './assistant.ts';
import type { AgentTool, InputSchemaObj } from './tool.ts';
import { makeAgentTool, makeInputSchemaObj } from './tool.ts';
import type { ToolInvocationPolicy, ToolRisk } from './tool_policy.ts';
import type { ToolMetadata, ToolRegistry } from './tool_registry.ts';

// ===== 常量(ToolSearch.kt:26-28) =====

export const TOOL_SEARCH_TOOL_NAME: string = 'tool_search';
export const TOOL_SEARCH_AUTO_THRESHOLD: number = 40;
export const TOOL_SEARCH_DEFAULT_LIMIT: number = 5;

// ===== isResidentTool 四集合(ToolSearch.kt:323-370 逐字) =====

const DISCOVERY_UTILITY_TOOLS: string[] = [
  TOOL_SEARCH_TOOL_NAME, 'tools_list', 'tool_policy_explain',
];

const RESIDENT_EXACT_TOOLS: string[] = [
  'ask_user', 'permissions_status', 'agent_runtime_status',
  'file_list', 'file_read', 'file_write', 'file_edit', 'file_search', 'file_move',
  'terminal_execute', 'terminal_job_start', 'terminal_job_read',
  'terminal_job_wait', 'terminal_job_stop',
  'terminal_session_start', 'terminal_session_exec', 'terminal_session_read',
  'terminal_session_stop',
  'mcp_list', 'mcp_call_tool',
  'generate_image',
];

const RESIDENT_CONTEXT_TOOLS: string[] = [
  'conversation_context_status', 'conversation_search', 'conversation_expand',
];

const RESIDENT_MODEL_COUNCIL_TOOLS: string[] = [
  'model_council_status', 'model_council_start', 'model_council_read',
  'model_council_wait', 'model_council_cancel',
];

// ToolSearch.kt:313-321(判定序逐字)
export const isResidentTool = (name: string, category: string | null): boolean => {
  if (DISCOVERY_UTILITY_TOOLS.includes(name)) return true;
  if (RESIDENT_EXACT_TOOLS.includes(name)) return true;
  if (name.startsWith('subagent_')) return true;
  if (RESIDENT_MODEL_COUNCIL_TOOLS.includes(name)) return true;
  if (name.startsWith('agent_task_')) {
    return name === 'agent_task_list' || name === 'agent_task_read';
  }
  if (category === 'context') return RESIDENT_CONTEXT_TOOLS.includes(name);
  return false;
};

// ===== Kotlin data class toString 形态(schema 字段/footprint 共用) =====

// InputSchema.Obj(properties=JsonObject, required=List<String>?) 的
//   data class toString:Obj(properties={"k":...}, required=[a, b]);
//   JsonObject 段 = JSON.stringify(kotlinx toString 同形),List 段 = join(', '),
//   null → 'null'
const inputSchemaToKotlinString = (schema: InputSchemaObj | null): string => {
  if (schema === null) return '';
  if (schema.jsonSchema !== undefined) return JSON.stringify(schema.jsonSchema);
  const req: string = schema.required === null
    ? 'null'
    : `[${schema.required.join(', ')}]`;
  return `Obj(properties=${JSON.stringify(schema.properties)}, required=${req})`;
};

// ToolSearch.kt:379-380
const schemaFootprintChars = (tool: AgentTool): number =>
  tool.name.length + tool.description.length
    + inputSchemaToKotlinString(tool.parameters()).length;

// ===== 输入解析(jsonPrimitive contentOrNull/intOrNull/booleanOrNull 语义) =====

const asObject = (input: JsonValue): JsonObject => {
  if (typeof input !== 'object' || input === null || Array.isArray(input)) return {};
  return input as JsonObject;
};

const inputString = (input: JsonValue, key: string): string => {
  const v: JsonValue | undefined = asObject(input)[key];
  return typeof v === 'string' ? v : '';
};

// contentOrNull(缺省/非字符串 → null)
const inputStringOrNull = (input: JsonValue, key: string): string | null => {
  const v: JsonValue | undefined = asObject(input)[key];
  return typeof v === 'string' ? v : null;
};

const inputIntOrNull = (input: JsonValue, key: string): number | null => {
  const v: JsonValue | undefined = asObject(input)[key];
  return typeof v === 'number' ? v : null;
};

// toBooleanStrictOrNull ?: default:仅 JSON boolean 生效
const inputBoolOr = (input: JsonValue, key: string, fallback: boolean): boolean => {
  const v: JsonValue | undefined = asObject(input)[key];
  return typeof v === 'boolean' ? v : fallback;
};

// ToolRisk Kotlin 枚举名(tools_list 用 .name 原文,无 lowercase)
const riskKotlinName = (risk: ToolRisk): string =>
  risk === 'normal' ? 'Normal' : risk === 'sensitive' ? 'Sensitive' : 'High';

// ===== tools_list(ToolsListTool.kt 全文) =====

export const createToolsListTool = (registry: ToolRegistry): AgentTool => makeAgentTool({
  name: 'tools_list',
  description: "Debug/catalog view of AmberAgent's full tool catalog. In lazy mode, hidden tools listed here are not callable until exposed by tool_search.",
  parameters: () => makeInputSchemaObj({
    category: {
      type: 'string',
      description: 'Optional category filter: workspace, external_file, cloud, office, terminal, web, webview, screen, system, memory, context, cron, task, subagent, model_council, skill, mcp, utility.',
    },
    query: {
      type: 'string',
      description: 'Optional name or description filter',
    },
    include_disabled: {
      type: 'boolean',
      description: 'Stage 1 lists enabled tools only; when true, the response explains this limitation.',
    },
    include_schema: {
      type: 'boolean',
      description: 'Include tool input schema. Defaults to false.',
    },
  }),
  execute: (input: JsonValue): Promise<UIMessagePart[]> => {
    const categoryFilter: string | null = inputStringOrNull(input, 'category');
    const query: string = inputString(input, 'query');
    const includeSchema: boolean = inputBoolOr(input, 'include_schema', false);
    const includeDisabled: boolean = inputBoolOr(input, 'include_disabled', false);
    const toolDefinitions: Map<string, AgentTool> = new Map<string, AgentTool>();
    for (const t of registry.tools()) toolDefinitions.set(t.name, t);
    const tools: ToolMetadata[] = registry.metadata
      .filter((m: ToolMetadata): boolean =>
        categoryFilter === null || m.category === categoryFilter)
      .filter((m: ToolMetadata): boolean => {
        const tool: AgentTool | undefined = toolDefinitions.get(m.name);
        return query.trim().length === 0
          || m.name.toLowerCase().includes(query.toLowerCase())
          || (tool?.description ?? '').toLowerCase().includes(query.toLowerCase());
      });
    const toolItems: JsonObject[] = tools.map((metadata: ToolMetadata): JsonObject => {
      const tool: AgentTool | undefined = toolDefinitions.get(metadata.name);
      const item: JsonObject = {
        name: metadata.name,
        category: metadata.category,
        description: (tool?.description ?? '').slice(0, 240),
        enabled: true,
        resident: isResidentTool(metadata.name, metadata.category),
        mutates: metadata.mutates,
        sensitive_read: metadata.sensitiveRead,
        needs_approval: metadata.needsApproval,
        allows_auto_approval: metadata.autoApprovable,
        output_budget_chars: metadata.outputBudgetChars,
        dynamic_policy_supported: true,
      };
      const invocationPolicy: ToolInvocationPolicy | null =
        registry.evaluateInvocation(metadata.name);
      item['concurrency_safe'] = invocationPolicy?.concurrencySafe ?? true;
      if (invocationPolicy?.parallelGroup) {
        item['parallel_group'] = invocationPolicy.parallelGroup;
      }
      if (invocationPolicy?.requiresForegroundAppPackage) {
        item['requires_foreground_app_package'] =
          invocationPolicy.requiresForegroundAppPackage;
      }
      item['speculative_eligible'] = invocationPolicy?.speculativeEligible ?? false;
      if (invocationPolicy?.speculativeBlockReason) {
        item['speculative_block_reason'] = invocationPolicy.speculativeBlockReason;
      }
      // capabilities 恒空(broker 未移植)→ 回落 metadata.risk.name,空权限表
      item['risk'] = riskKotlinName(metadata.risk);
      item['required_permissions'] = [];
      if (includeSchema && tool !== undefined) {
        item['schema'] = inputSchemaToKotlinString(tool.parameters());
      }
      return item;
    });
    const payload: JsonObject = {
      enabled_count: tools.length,
      include_disabled_supported: false,
      catalog_mode: 'debug',
      callability_note: 'This is a full catalog/debug view. In lazy tool mode, hidden tools listed here are not callable until tool_search exposes their schemas.',
      next_action: 'To call a non-resident tool from this list, call tool_search with query set to the exact tool name, then call it on the next model step.',
    };
    if (includeDisabled) {
      payload['note'] = 'Disabled tool enumeration is not available in stage1 because tools are generated from the current agent configuration.';
    }
    payload['tools'] = toolItems;
    return Promise.resolve([
      { type: 'text', text: JSON.stringify(payload), metadata: null },
    ]);
  },
});

// ===== tool_policy_explain(ToolPolicyExplainTool.kt 全文) =====

export const createToolPolicyExplainTool = (registry: ToolRegistry): AgentTool =>
  makeAgentTool({
    name: 'tool_policy_explain',
    description: 'Explain how AmberAgent would evaluate one tool invocation without executing it.',
    parameters: () => makeInputSchemaObj(
      {
        tool_name: { type: 'string', description: 'Tool name to evaluate.' },
        input: {
          type: 'string',
          description: 'Optional JSON string input for dynamic policy evaluation.',
        },
      },
      ['tool_name'],
    ),
    execute: (input: JsonValue): Promise<UIMessagePart[]> => {
      const toolName: string = inputString(input, 'tool_name');
      const rawInput: string = inputString(input, 'input');
      // runCatching { parse(rawInput.ifBlank { "{}" }) }.getOrNull()
      let toolInput: JsonValue | null = null;
      try {
        toolInput = JSON.parse(rawInput.trim().length === 0 ? '{}' : rawInput) as JsonValue;
      } catch {
        toolInput = null;
      }
      const policy: ToolInvocationPolicy | null =
        registry.evaluateInvocation(toolName, toolInput);
      const payload: JsonObject = {
        status: policy === null ? 'not_found' : 'ok',
        tool_name: toolName,
      };
      if (policy !== null) {
        payload['category'] = policy.category;
        payload['risk'] = policy.risk; // .name.lowercase() — 鸿蒙枚举值即小写
        payload['mutates'] = policy.mutates;
        payload['needs_approval'] = policy.needsApproval;
        payload['allows_auto_approval'] = policy.autoApprovable;
        payload['concurrency_safe'] = policy.concurrencySafe;
        if (policy.parallelGroup !== null) payload['parallel_group'] = policy.parallelGroup;
        if (policy.requiresForegroundAppPackage !== null) {
          payload['requires_foreground_app_package'] = policy.requiresForegroundAppPackage;
        }
        payload['speculative_eligible'] = policy.speculativeEligible;
        if (policy.speculativeBlockReason !== null) {
          payload['speculative_block_reason'] = policy.speculativeBlockReason;
        }
        payload['output_budget_chars'] = policy.outputBudgetChars;
        payload['always_ask'] = policy.alwaysAsk;
        if (policy.reason !== null) payload['reason'] = policy.reason;
      }
      return Promise.resolve([
        { type: 'text', text: JSON.stringify(payload), metadata: null },
      ]);
    },
  });

// ===== tool_search(ToolSearch.kt:30-268) =====

export interface ScoredTool {
  metadata: ToolMetadata;
  tool: AgentTool;
  score: number;
}

// searchAliases(ToolSearch.kt:220-258 逐字,含中文别名表)
const searchAliases = (metadata: ToolMetadata): string[] => {
  const out: string[] = [];
  const name: string = metadata.name;
  const category: string = metadata.category;
  if (name === 'screen_screenshot') {
    out.push('截图', '截屏', '屏幕截图', '看屏幕', 'screenshot');
  } else if (name === 'screen_read_ui') {
    out.push('读屏幕', '读取屏幕', 'ui 树', 'UI 树', '当前页面', '看页面');
  } else if (name.startsWith('screen_click') || name.startsWith('screen_tap')) {
    out.push('点击', '点一下', '点击屏幕', 'tap');
  } else if (name.startsWith('screen_')) {
    out.push('屏幕', '手机屏幕', '滑动', '输入');
  }
  if (name.startsWith('wm_') || category.includes('webmount')) {
    out.push('网页', '浏览器', 'webview', 'WebView', 'webmount', '打开网页', '点击网页', '读取网页');
  }
  if (name === 'wm_observe') {
    out.push('观察网页', '页面状态', '网页摘要', '网页节点');
  } else if (name === 'wm_visual_snapshot' || name === 'wm_visual_read') {
    out.push('网页截图', '读图', '视觉读取', '图片识别', '看网页图片');
  } else if (name === 'wm_network_inspect' || name === 'wm_fetch_replay') {
    out.push('网络请求', '接口', 'XHR', 'fetch', '重放请求');
  }
  if (name.startsWith('feishu_docs_') || category.includes('feishu')) {
    out.push('飞书', '云文档', '飞书云文档', 'wiki', '知识库', '文档', '表格', '会议纪要');
  }
  if (name.startsWith('subagent_')) {
    out.push('子代理', 'subagent', '副 agent', '副代理');
  }
  if (name.startsWith('model_council_')) {
    out.push('议会', '多模型', 'council', '模型会议');
  }
  if (name.startsWith('file_')) {
    out.push('文件', '工作区', '搜索文件', '读文件', '写文件');
  }
  if (name.startsWith('terminal_')) {
    out.push('终端', '命令', '脚本', '运行命令', 'terminal');
  }
  if (name.startsWith('mcp_') || category === 'mcp') {
    out.push('mcp', 'MCP', '外部工具');
  }
  if (category === 'provider') {
    out.push('服务商', '提供商', '接口配置', 'API key', '密钥', 'provider', '模型配置');
  }
  return out;
};

// 语义重排钩子(Jev;entry 注入,失败回退词面序)
export type ToolSearchReranker = (query: string, matches: ScoredTool[]) => Promise<ScoredTool[]>;

// scoreTool(ToolSearch.kt:178-218 逐字)
const scoreTool = (
  metadata: ToolMetadata, tool: AgentTool,
  query: string, tokens: string[], category: string | null,
): number => {
  let score: number = 0;
  if (category !== null && metadata.category.toLowerCase() === category) score += 25;
  if (tokens.length === 0) return category !== null ? score + 1 : 0;
  const name: string = metadata.name.toLowerCase();
  const description: string = tool.description.toLowerCase();
  const categoryText: string = metadata.category.toLowerCase();
  if (query === name) score += 240;
  if (tokens.some((t: string): boolean => t === name)) score += 180;
  for (const token of tokens) {
    if (name === token) score += 120;
    else if (name.startsWith(token)) score += 80;
    else if (name.includes(token)) score += 55;
    else if (categoryText === token) score += 35;
    else if (categoryText.includes(token)) score += 24;
    else if (description.includes(token)) score += 16;
  }
  for (const alias of searchAliases(metadata)) {
    const normalizedAlias: string = alias.toLowerCase();
    if (query === normalizedAlias) score += 100;
    else if (query.includes(normalizedAlias)) score += 55;
    else if (normalizedAlias.includes(query) && query.length >= 2) score += 32;
    else if (tokens.some((t: string): boolean => t === normalizedAlias)) score += 80;
    else if (tokens.some(
      (t: string): boolean => normalizedAlias.includes(t) && t.length >= 2)) score += 24;
  }
  if (tokens.some((t: string): boolean => name.split('_').includes(t))) score += 30;
  if (score > 0 && !metadata.mutates && metadata.risk === 'normal') score += 2;
  return score;
};

// search(ToolSearch.kt:151-176):过滤自身/类目 → 评分 → 降序+名次 → take
const searchIndex = (
  registry: ToolRegistry, query: string,
  category: string | null, limit: number,
): ScoredTool[] => {
  const toolsByName: Map<string, AgentTool> = new Map<string, AgentTool>();
  for (const t of registry.tools()) toolsByName.set(t.name, t);
  const normalizedQuery: string = query.trim().toLowerCase();
  const tokens: string[] = normalizedQuery
    .split(/\s+/)
    .map((t: string): string => t.trim())
    .filter((t: string): boolean => t.length > 0);
  const scored: ScoredTool[] = [];
  for (const metadata of registry.metadata) {
    if (metadata.name === TOOL_SEARCH_TOOL_NAME) continue;
    if (category !== null && metadata.category.toLowerCase() !== category) continue;
    const tool: AgentTool | undefined = toolsByName.get(metadata.name);
    if (tool === undefined) continue;
    const score: number = scoreTool(metadata, tool, normalizedQuery, tokens, category);
    if (score > 0) scored.push({ metadata, tool, score });
  }
  scored.sort((a: ScoredTool, b: ScoredTool): number => {
    if (b.score !== a.score) return b.score - a.score;
    return a.metadata.name < b.metadata.name ? -1 : a.metadata.name > b.metadata.name ? 1 : 0;
  });
  return scored.slice(0, limit);
};

// categoryCounts(ToolSearch.kt:90-91):groupingBy eachCount
const categoryCounts = (registry: ToolRegistry): Map<string, number> => {
  const counts: Map<string, number> = new Map<string, number>();
  for (const m of registry.metadata) {
    counts.set(m.category, (counts.get(m.category) ?? 0) + 1);
  }
  return counts;
};

// sortedWith(compareByDescending{value}.thenBy{key})
const sortedCategoryEntries = (counts: Map<string, number>): [string, number][] => {
  const entries: [string, number][] = [];
  counts.forEach((v: number, k: string): void => {
    entries.push([k, v]);
  });
  entries.sort((a: [string, number], b: [string, number]): number => {
    if (b[1] !== a[1]) return b[1] - a[1];
    return a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0;
  });
  return entries;
};

// ScoredTool.toJson(ToolSearch.kt:260-272 键序逐字)
const scoredToolToJson = (s: ScoredTool): JsonObject => ({
  name: s.metadata.name,
  category: s.metadata.category,
  description: s.tool.description.slice(0, 360),
  score: s.score,
  mutates: s.metadata.mutates,
  sensitive_read: s.metadata.sensitiveRead,
  needs_approval: s.metadata.needsApproval,
  allows_auto_approval: s.metadata.autoApprovable,
  risk: s.metadata.risk, // .name.lowercase(ROOT) — 鸿蒙枚举值即小写
  output_budget_chars: s.metadata.outputBudgetChars,
  schema: inputSchemaToKotlinString(s.tool.parameters()),
});

const residentToolCount = (registry: ToolRegistry): number => {
  let count: number = 0;
  for (const tool of registry.tools()) {
    const metadata: ToolMetadata | null = registry.metadataFor(tool.name);
    if (isResidentTool(tool.name, metadata !== null ? metadata.category : null)) count += 1;
  }
  return count;
};

// searchPayload(ToolSearch.kt:92-149 键序逐字;profile 非 null → trace 出
//   profile/profile_filtered 键(:115-118,位置在 query 之后 hit_tools 之前))
const searchPayload = async (
  registry: ToolRegistry, query: string,
  category: string | null, limit: number,
  profile: MainAgentToolProfile | null = null,
  reranker?: ToolSearchReranker,
): Promise<JsonObject> => {
  const trimmed: string | null = category !== null ? category.trim().toLowerCase() : null;
  const normalizedCategory: string | null =
    trimmed !== null && trimmed.length > 0 ? trimmed : null;
  const boundedLimit: number = Math.min(Math.max(limit, 1), 20);
  let matches: ScoredTool[] =
    searchIndex(registry, query, normalizedCategory, boundedLimit);
  // Jev 语义重排(仅多候选时;失败/影子/不可用一律回退词面序)
  if (reranker !== undefined && matches.length > 1) {
    try {
      matches = await reranker(query, matches);
    } catch (_e) {
      // 回退词面序
    }
  }
  const expandedTools: string[] =
    matches.map((s: ScoredTool): string => s.metadata.name);
  const fullSchemaChars: number = registry.tools()
    .reduce((acc: number, t: AgentTool): number => acc + schemaFootprintChars(t), 0);
  const residentSchemaChars: number = registry.tools()
    .filter((t: AgentTool): boolean => {
      const m: ToolMetadata | null = registry.metadataFor(t.name);
      return isResidentTool(t.name, m !== null ? m.category : null);
    })
    .reduce((acc: number, t: AgentTool): number => acc + schemaFootprintChars(t), 0);
  const expandedSchemaChars: number = matches
    .reduce((acc: number, s: ScoredTool): number => acc + schemaFootprintChars(s.tool), 0);
  const payload: JsonObject = {
    status: 'ok',
    query,
  };
  if (normalizedCategory !== null) payload['category'] = normalizedCategory;
  payload['limit'] = boundedLimit;
  payload['total_tools'] = registry.metadata.length;
  payload['resident_tools'] = residentToolCount(registry);
  payload['matches_count'] = matches.length;
  payload['expanded_tools'] = expandedTools;
  payload['callability_note'] = 'Only tools in expanded_tools are newly callable on the next model step. tools_list is catalog/debug only and does not expose hidden schemas.';
  const trace: JsonObject = {
    mode: registry.metadata.length > TOOL_SEARCH_AUTO_THRESHOLD ? 'lazy' : 'bypass',
    query,
  };
  // :115-118 — profile?.let { profile(name.lowercase) + profile_filtered(!= FULL) }
  if (profile !== null) {
    trace['profile'] = profile; // 鸿蒙联合值即 SerialName 小写
    trace['profile_filtered'] = profile !== 'full';
  }
  trace['hit_tools'] = expandedTools;
  trace['expanded_tools'] = expandedTools;
  trace['estimated_full_schema_chars'] = fullSchemaChars;
  trace['estimated_resident_schema_chars'] = residentSchemaChars;
  trace['estimated_expanded_schema_chars'] = expandedSchemaChars;
  trace['estimated_schema_savings_chars'] =
    Math.max(fullSchemaChars - residentSchemaChars - expandedSchemaChars, 0);
  payload['trace'] = trace;
  payload['tools'] = matches.map(scoredToolToJson);
  if (matches.length === 0) {
    const candidates: JsonObject[] = sortedCategoryEntries(categoryCounts(registry))
      .slice(0, 16)
      .map((e: [string, number]): JsonObject => ({ category: e[0], count: e[1] }));
    payload['category_candidates'] = candidates;
    payload['debug_hint'] = 'No matching tool was expanded. Try a more concrete Chinese/English query, or use tools_list only to identify an exact tool name and then call tool_search(query="<exact_tool_name>").';
  } else {
    payload['next_step'] = 'On the next model step, call one of expanded_tools exactly. Do not call tools only seen in tools_list unless you first expose them with tool_search(query="<exact_tool_name>"). Permissions still apply.';
  }
  return payload;
};

// ToolSearch.kt:28-31 — profile 透传 ToolSearchIndex(trace 两键)
export const createToolSearchTool = (
  registry: ToolRegistry, profile: MainAgentToolProfile | null = null,
  reranker?: ToolSearchReranker): AgentTool => makeAgentTool({
  name: TOOL_SEARCH_TOOL_NAME,
  description: "Search AmberAgent's full tool catalog by intent/category and expose the best matching tool schemas for the next step.",
  parameters: () => makeInputSchemaObj(
    {
      query: {
        type: 'string',
        description: 'Required. What capability you need, e.g. "read PDF", "call Feishu MCP", "webview click", "截图", or an exact tool name from tools_list.',
      },
      category: {
        type: 'string',
        description: 'Optional category filter, e.g. workspace, terminal, web, webview, webmount, screen, system, memory, context, subagent, model_council, mcp, office, skill.',
      },
      limit: {
        type: 'integer',
        description: 'Maximum tools to expose. Defaults to 5; capped at 20.',
      },
      display_title: {
        type: 'string',
        description: 'Optional short user-facing action title in Chinese, e.g. 查找写作工具.',
      },
    },
    ['query'],
  ),
  needsApproval: false,
  allowsAutoApproval: true,
  // systemPrompt(ToolSearch.kt:55-73):每次调用按当前 registry 重建计数
  systemPrompt: (_model: ChatModel, _messages: UIMessage[]): string => {
    const categories: string = sortedCategoryEntries(categoryCounts(registry))
      .map((e: [string, number]): string => `${e[0]}:${e[1]}`)
      .join(', ');
    const residentCount: number = residentToolCount(registry);
    return 'Tool discovery:\n'
      + `- This run has ${registry.metadata.length} generated tools across categories: ${categories}.\n`
      + `- If the needed tool is not currently visible, call \`${TOOL_SEARCH_TOOL_NAME}\` with a concrete query. It exposes the best matching schemas for the next generation step.\n`
      + `- \`tools_list\` is only a debug/catalog view. A hidden tool listed by \`tools_list\` is not callable until \`${TOOL_SEARCH_TOOL_NAME}\` exposes it.\n`
      + `- If you used \`tools_list\` to identify a tool name, call \`${TOOL_SEARCH_TOOL_NAME}\` again with that exact tool name, then execute a name from \`expanded_tools\` on the next step.\n`
      + `- Resident tools currently stay visible without search: ${residentCount} core tools plus discovered tools.`;
  },
  execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
    const query: string = inputString(input, 'query');
    const rawCategory: string = inputString(input, 'category');
    // contentOrNull?.ifBlank { null }
    const category: string | null = rawCategory.length === 0 ? null : rawCategory;
    const limit: number = inputIntOrNull(input, 'limit') ?? TOOL_SEARCH_DEFAULT_LIMIT;
    const payload: JsonObject = await searchPayload(registry, query, category, limit, profile, reranker);
    return [
      { type: 'text', text: JSON.stringify(payload), metadata: null },
    ];
  },
});

// ===== registryIntrospectionTools 对(LocalTools.kt:86-92) =====
// permissionBroker 未移植 → 对内 tools_list 恒零 capability(见文件头偏差)

export const createRegistryIntrospectionTools = (registry: ToolRegistry): AgentTool[] => [
  createToolsListTool(registry),
  createToolPolicyExplainTool(registry),
];
