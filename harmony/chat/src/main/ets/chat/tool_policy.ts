// tool_policy — 工具调用策略(D-056)
//
// Android 基准: feature/tools/api ToolRegistry.kt(策略部分全文)
//   - ToolInvocationPolicy(:43-59)/ToolRisk(:156-160)
//   - Tool.invocationPolicy(input)(:167/:204-422 含逐名覆盖表)
//   - category(:424-457)/mutatesState(:459-483)/hasMutatingNameHint(:487-499)
//   - riskProfile(:501-528)/alwaysAsk(:530-534)
//   - requiresFailClosedAutoApproval + FAIL_CLOSED 类目(:536-550)
//   - concurrencySafe(:552-559)/sensitiveRead(:560-566)
//   - foregroundPackageRequirement(:568-577)/speculativeBlockReason(:579-600)
//   - outputBudgetChars(:602-640)/isPrivateNetworkTarget(:175-201)
//   - enforceOutputBudget + truncatedEnvelope(:672-707)
// 偏差登记:Registry 的注册/去重/包装层(ToolRegistry.from/tools())随内置工具
//   落地(D-058)接入;本模块为策略纯逻辑全文。常量组合值:
//   MODEL_COUNCIL = 200000*8*5 + 512000(ModelCouncilModels.kt:11-20)
//   SUB_AGENT = 200000 + 64000(SubAgentModels.kt:13)

import type { JsonObject, JsonValue } from './json.ts';
import type { UIMessagePart, UIMessagePartText } from './message.ts';
import type { AgentTool, InputSchemaObj } from './tool.ts';

// ===== ToolRisk(:156-160) =====

export type ToolRisk = 'normal' | 'sensitive' | 'high';

// ===== ToolInvocationPolicy(:43-59) =====

export interface ToolInvocationPolicy {
  name: string;
  category: string;
  mutates: boolean;
  needsApproval: boolean;
  autoApprovable: boolean;
  concurrencySafe: boolean;
  outputBudgetChars: number;
  risk: ToolRisk;
  parallelGroup: string | null;
  requiresForegroundAppPackage: string | null;
  speculativeEligible: boolean;
  speculativeBlockReason: string | null;
  reason: string | null;
  mandatoryApproval: boolean;
  alwaysAsk: boolean;
}

interface RiskProfile {
  risk: ToolRisk;
  explicit: boolean;
}

// ===== 常量 =====

export const DEFAULT_TOOL_OUTPUT_BUDGET_CHARS: number = 80000;
// ModelCouncilModels.kt:11-20 组合(:627-629)
export const MODEL_COUNCIL_TOOL_OUTPUT_BUDGET_CHARS: number = 200000 * 8 * 5 + 512000;
// SubAgentModels.kt:13 组合(:631-633)
export const SUB_AGENT_TOOL_OUTPUT_BUDGET_CHARS: number = 200000 + 64000;

export const EXTERNAL_CLI_COUNCIL_RUNNER_TYPES: string[] = [
  'external_cli', 'cli', 'gemini_cli', 'antigravity_cli', 'codex_cli', 'claude_code', 'kimi_cli',
];

const FAIL_CLOSED_AUTO_APPROVAL_CATEGORIES: string[] = [
  'cloud', 'system', 'external_file', 'terminal', 'screen', 'office',
];

// ===== JsonElement 取值 helper(:659-670) =====

const asObject = (v: JsonValue | null | undefined): JsonObject | null =>
  v !== null && v !== undefined && typeof v === 'object' && !Array.isArray(v)
    ? v as JsonObject
    : null;

export const inputStringValue = (
  input: JsonValue | null | undefined, name: string,
): string | null => {
  const obj: JsonObject | null = asObject(input);
  if (obj === null) return null;
  const v: JsonValue | undefined = obj[name];
  return typeof v === 'string' ? v : null;
};

export const inputBooleanValue = (
  input: JsonValue | null | undefined, name: string,
): boolean => {
  const s: string | null = inputStringValue(input, name);
  return s !== null && s.toLowerCase() === 'true';
};

// ===== 外部 CLI council 判定(:662-697) =====

const containsExternalCliSeat = (v: JsonValue | null | undefined): boolean => {
  if (!Array.isArray(v)) return false;
  return v.some((item: JsonValue): boolean => {
    const seat: JsonObject | null = asObject(item);
    if (seat === null) return false;
    const runner: string = (inputStringValue(seat, 'runner_type') ?? '').toLowerCase();
    const externalTool: string = inputStringValue(seat, 'external_tool') ?? '';
    return EXTERNAL_CLI_COUNCIL_RUNNER_TYPES.includes(runner) || externalTool.trim().length > 0;
  });
};

export const containsExternalCliCouncilSeat = (input: JsonValue | null | undefined): boolean => {
  const root: JsonObject | null = asObject(input);
  if (root === null) return false;
  const task: JsonObject = asObject(root['task']) ?? root;
  return containsExternalCliSeat(task['planned_seats']) || containsExternalCliSeat(task['seats']);
};

export const allowsExternalCliCouncil = (input: JsonValue | null | undefined): boolean => {
  const root: JsonObject | null = asObject(input);
  if (root === null) return false;
  const task: JsonObject | null = asObject(root['task']);
  return inputBooleanValue(root, 'allow_external_cli') || inputBooleanValue(task, 'allow_external_cli');
};

// ===== isPrivateNetworkTarget(:175-201) =====
// 静态 loopback/私网分类(纯主机名,无 DNS 解析)

// java.net.URI(url).host 等价(ArkTS 无全局 URL):scheme://authority → 去
//   userinfo/path → IPv6 保留方括号(与 Android removePrefix/Suffix 对齐)
const parseUrlHost = (raw: string): string | null => {
  const schemeIdx: number = raw.indexOf('://');
  if (schemeIdx < 0) return null;
  let rest: string = raw.slice(schemeIdx + 3);
  const pathIdx: number = rest.search(/[/?#]/);
  if (pathIdx >= 0) rest = rest.slice(0, pathIdx);
  const atIdx: number = rest.lastIndexOf('@');
  if (atIdx >= 0) rest = rest.slice(atIdx + 1);
  if (rest.length === 0) return null;
  if (rest.startsWith('[')) {
    const closeIdx: number = rest.indexOf(']');
    if (closeIdx < 0) return null;
    return rest.slice(0, closeIdx + 1);
  }
  const colonIdx: number = rest.indexOf(':');
  return colonIdx >= 0 ? rest.slice(0, colonIdx) : rest;
};

export const isPrivateNetworkTarget = (url: string | null): boolean => {
  if (url === null || url.trim().length === 0) return false;
  const host: string | null = ((): string | null => {
    const h: string | null = parseUrlHost(url.trim());
    return h !== null ? h.toLowerCase() : null;
  })();
  if (host === null || host.length === 0) return false;
  const bare: string = host.startsWith('[') && host.endsWith(']')
    ? host.slice(1, -1)
    : host;
  if (bare === 'localhost' || bare.endsWith('.localhost') || bare.endsWith('.local')
    || bare.endsWith('.internal') || bare.endsWith('.lan')) {
    return true;
  }
  if (bare.includes(':')) {
    return bare === '::' || bare === '::1' || bare.startsWith('fe80:')
      || bare.startsWith('fc') || bare.startsWith('fd');
  }
  const parts: string[] = bare.split('.');
  const octets: number[] = [];
  for (const p of parts) {
    const n: number = Number(p);
    if (!Number.isInteger(n)) return false;
    octets.push(n);
  }
  if (parts.length === 4 && octets.every((n: number): boolean => n >= 0 && n <= 255)) {
    const a: number = octets[0];
    const b: number = octets[1];
    return a === 0 || a === 127 || a === 10
      || (a === 192 && b === 168)
      || (a === 172 && b >= 16 && b <= 31)
      || (a === 169 && b === 254)
      || (a === 100 && b >= 64 && b <= 127);
  }
  return false;
};

// ===== category(:424-457) =====

export const toolCategory = (name: string): string => {
  if (['provider_list', 'provider_configure', 'provider_models'].includes(name)) return 'provider';
  if (name.startsWith('plugin_') || name === 'plugins_list') return 'plugin';
  if (name.startsWith('recipe_') || name === 'recipes_list') return 'recipe';
  if (name.startsWith('external_file_')) return 'external_file';
  if (name.startsWith('file_') || name.startsWith('archive_')
    || ['download_file', 'pdf_read', 'pdf_render_page', 'office_read', 'image_info',
      'image_convert', 'ocr_image'].includes(name)) return 'workspace';
  if (name.startsWith('icloud_')) return 'cloud';
  if (name.startsWith('officepro_')) return 'office';
  if (name.startsWith('terminal_')) return 'terminal';
  if (name === 'python_execute') return 'python';
  if (['search_web', 'scrape_web', 'search_sources_status', 'search_strategy_explain',
    'http_request'].includes(name)) return 'web';
  if (name.startsWith('webview_')) return 'webview';
  if (name.startsWith('wm_')) return 'webmount';
  if (name.startsWith('hn_')) return 'webmount_hackernews';
  if (name.startsWith('reddit_')) return 'webmount_reddit';
  if (name.startsWith('juejin_')) return 'webmount_juejin';
  if (name.startsWith('feishu_docs_')) return 'webmount_feishu_docs';
  if (name.startsWith('github_')) return 'webmount_github';
  if (name.startsWith('bilibili_')) return 'webmount_bilibili';
  if (name.startsWith('zhihu_')) return 'webmount_zhihu';
  if (name.startsWith('screen_') || name === 'vlm_task') return 'screen';
  if (name.startsWith('sms_') || name.startsWith('contacts_') || name.startsWith('calendar_')
    || name.startsWith('call_') || name.startsWith('apps_') || name.startsWith('app_')
    || ['device_phone_state', 'media_search', 'location_current', 'audio_record_once',
      'notification_list', 'usage_stats_list', 'battery_status', 'network_status',
      'wifi_status', 'device_info', 'settings_open', 'intent_open', 'share_text',
      'share_file', 'notification_post'].includes(name)) return 'system';
  if (name.startsWith('memory_')) return 'memory';
  if (name === 'agent_prompt_config') return 'prompt_config';
  if (name.startsWith('conversation_') || name.startsWith('session_')) return 'context';
  if (name.startsWith('deep_read_')) return 'deep_read';
  if (name.startsWith('cron_task_')) return 'cron';
  if (name.startsWith('agent_task_') || name === 'agent_runtime_status') return 'task';
  if (['tool_policy_explain', 'tool_search', 'tools_list'].includes(name)) return 'utility';
  if (name.startsWith('subagent_')) return 'subagent';
  if (name.startsWith('model_council_')) return 'model_council';
  if (name.startsWith('skill') || name === 'use_skill') return 'skill';
  if (name.startsWith('mcp_') || name.startsWith('mcp__')) return 'mcp';
  return 'utility';
};

// ===== mutatesState(:459-483) + hasMutatingNameHint(:487-499) =====

// String.hasToken(:485-486)
const hasToken = (name: string, token: string): boolean =>
  name === token || name.startsWith(`${token}_`) || name.endsWith(`_${token}`)
  || name.includes(`_${token}_`);

const hasMutatingNameHint = (name: string): boolean => {
  const readOnlyName: boolean = hasToken(name, 'read') || hasToken(name, 'list')
    || hasToken(name, 'search') || hasToken(name, 'status');
  return name.includes('_write')
    || name.includes('_edit')
    || name.includes('_move')
    || name.includes('_delete')
    || hasToken(name, 'create')
    || hasToken(name, 'append')
    || (hasToken(name, 'comment') && !readOnlyName)
    || (hasToken(name, 'post') && !readOnlyName)
    || hasToken(name, 'publish')
    || hasToken(name, 'send')
    || hasToken(name, 'update');
};

export const toolMutatesState = (name: string): boolean => {
  if (name === 'provider_configure') return true;
  if (name === 'memory_tool') return true;
  if (name === 'deep_read_open') return true;
  if (name === 'run_plan_update') return false;
  return hasMutatingNameHint(name)
    || name.includes('_install')
    || name.includes('_stop')
    || name === 'pdf_render_page'
    || name === 'mcp_call_tool'
    || name === 'officepro_make_report'
    || name === 'officepro_project_update'
    || name === 'agent_prompt_config'
    || name === 'model_council_make_report'
    || ['cron_task_create', 'cron_task_update', 'cron_task_delete'].includes(name)
    || ['agent_task_cancel', 'agent_task_retry', 'agent_task_cleanup'].includes(name)
    || (name.startsWith('memory_') && name !== 'memory_list')
    || name === 'conversation_compact'
    || name === 'deep_read_finish'
    || ['subagent_start', 'subagent_cancel'].includes(name)
    || ['wm_click', 'wm_tap', 'wm_type', 'wm_keys', 'wm_select'].includes(name)
    || name.startsWith('skill_enable')
    || name.startsWith('skill_disable');
};

// ===== riskProfile(:501-528) =====

const OFFICEPRO_HIGH_RISK: string[] = [
  'officepro_read_screen', 'officepro_capture_context', 'officepro_context_digest',
  'officepro_daily_radar', 'officepro_project_briefing', 'officepro_document_warroom',
  'officepro_open_items_radar', 'officepro_meeting_closure', 'officepro_create_task_draft',
  'officepro_create_base_record_draft', 'officepro_reply_draft', 'officepro_project_context',
];

export const toolRiskProfile = (name: string): RiskProfile => {
  if (['provider_configure', 'provider_models'].includes(name)) return { risk: 'sensitive', explicit: true };
  if (name === 'provider_list') return { risk: 'normal', explicit: true };
  if (name === 'http_request') return { risk: 'high', explicit: true };
  if (name === 'memory_tool') return { risk: 'high', explicit: true };
  if (name === 'mcp_call_tool') return { risk: 'sensitive', explicit: true };
  if (name === 'wm_eval') return { risk: 'high', explicit: true };
  if (['wm_click', 'wm_tap', 'wm_type', 'wm_keys', 'wm_select'].includes(name)) {
    return { risk: 'sensitive', explicit: true };
  }
  if (['session_read', 'session_expand'].includes(name)) return { risk: 'sensitive', explicit: true };
  if (name === 'pdf_render_page') return { risk: 'high', explicit: true };
  if (['agent_task_cancel', 'agent_task_retry', 'agent_task_cleanup'].includes(name)) {
    return { risk: 'sensitive', explicit: true };
  }
  if (name === 'subagent_start') return { risk: 'normal', explicit: true };
  if (OFFICEPRO_HIGH_RISK.includes(name)) return { risk: 'high', explicit: true };
  if (name.startsWith('external_file_')
    && (name.includes('_write') || name.includes('_delete'))) {
    return { risk: 'high', explicit: true };
  }
  if (name.startsWith('sms_') || name.startsWith('call_') || name.startsWith('contacts_write')) {
    return { risk: 'high', explicit: true };
  }
  if (name.startsWith('screen_') || name === 'vlm_task') return { risk: 'sensitive', explicit: true };
  if (name.startsWith('terminal_')) return { risk: 'sensitive', explicit: true };
  if (name === 'python_execute') return { risk: 'sensitive', explicit: true };
  return { risk: 'normal', explicit: false };
};

// ===== alwaysAsk(:530-534) =====

export const toolAlwaysAsk = (name: string, input: JsonValue | null): boolean => {
  if (name === 'sms_send' || name === 'contacts_write') return true;
  if (name === 'call_phone') return inputBooleanValue(input, 'direct_call');
  return false;
};

// ===== requiresFailClosedAutoApproval(:536-541) =====

export const requiresFailClosedAutoApproval = (
  mutates: boolean, category: string, riskExplicit: boolean,
): boolean =>
  !riskExplicit && (mutates || FAIL_CLOSED_AUTO_APPROVAL_CATEGORIES.includes(category));

// ===== concurrencySafe(:552-559) =====

export const toolConcurrencySafe = (name: string): boolean => {
  if (name === 'python_execute') return false;
  if (['terminal_install_packages', 'terminal_job_stop', 'terminal_execute',
    'terminal_job_start'].includes(name)) return false;
  if (name.startsWith('cron_task_') && name !== 'cron_task_list') return false;
  if (name.startsWith('agent_task_')
    || ['agent_runtime_status', 'tool_policy_explain', 'tool_search', 'tools_list'].includes(name)) {
    return true;
  }
  if (name.startsWith('subagent_') || name.startsWith('model_council_')) return false;
  return !toolMutatesState(name);
};

// ===== sensitiveRead(:560-566) =====

export const toolSensitiveRead = (name: string): boolean =>
  ['session_read', 'session_expand'].includes(name)
  || name.startsWith('screen_')
  || ['officepro_read_screen', 'officepro_capture_context', 'officepro_context_digest'].includes(name);

// ===== foregroundPackageRequirement(:568-577) =====

export const toolForegroundPackageRequirement = (name: string): string | null => {
  if (['officepro_read_screen', 'officepro_capture_context', 'officepro_context_digest',
    'officepro_daily_radar', 'officepro_project_briefing', 'officepro_document_warroom',
    'officepro_open_items_radar', 'officepro_meeting_closure'].includes(name)) {
    return 'configured_officepro_target';
  }
  return null;
};

// ===== speculativeBlockReason(:579-600) =====

export const toolSpeculativeBlockReason = (
  name: string, category: string, mutates: boolean, needsApproval: boolean,
  concurrencySafe: boolean, risk: ToolRisk, parallelGroup: string | null,
  requiresForegroundAppPackage: string | null,
): string | null => {
  if (risk !== 'normal') return 'risk_not_normal';
  if (mutates) return 'mutates_state';
  if (needsApproval) return 'needs_approval';
  if (!concurrencySafe) return 'not_concurrency_safe';
  if (parallelGroup === null) return 'no_parallel_group';
  if (requiresForegroundAppPackage !== null) return 'requires_foreground_app';
  if (['terminal', 'screen', 'office', 'external_file', 'subagent', 'model_council'].includes(category)) {
    return 'category_blocked';
  }
  if (category === 'cron' && name !== 'cron_task_list') return 'cron_mutation_blocked';
  if (category === 'memory' && mutates) return 'memory_write_blocked';
  return null;
};

// ===== outputBudgetChars(:602-640) =====

export const toolOutputBudgetChars = (name: string): number => {
  // A complete JSON archive is already capped at 2 MiB by its decoder.
  if (name === 'plugin_export') return 2 * 1024 * 1024 + 8192;
  // Native bounds the combined UTF-8 output to 128 KiB; JSON may escape each byte.
  if (name === 'python_execute') return 128 * 1024 * 6 + 2048;
  // 262144 = FILE_READ_HARD_MAX_CHARS(WorkspaceTools.kt 内部常量,+2048 信封余量)
  if (name === 'file_read') return 262144 + 2048;
  if (name === 'wm_screenshot') return 1200000;
  if (name === 'wm_observe') return 180000;
  if (name === 'wm_fetch_replay') return 220000;
  if (name === 'wm_signed_fetch') return 1100000;
  if (name === 'feishu_docs_read') return 220000;
  if (name === 'feishu_docs_blocks') return 180000;
  if (name === 'feishu_docs_snapshot') return 180000;
  if (name === 'feishu_docs_markdown_pack') return 220000;
  if (name === 'github_file_read') return 220000;
  if (name === 'zhihu_answer_read') return 90000;
  if (name === 'zhihu_question_read') return 100000;
  if (['model_council_status', 'model_council_start', 'model_council_wait',
    'model_council_cancel', 'model_council_read'].includes(name)) {
    return MODEL_COUNCIL_TOOL_OUTPUT_BUDGET_CHARS;
  }
  if (['subagent_start', 'subagent_wait', 'subagent_cancel', 'subagent_read'].includes(name)) {
    return SUB_AGENT_TOOL_OUTPUT_BUDGET_CHARS;
  }
  return DEFAULT_TOOL_OUTPUT_BUDGET_CHARS;
};

// ===== invocationPolicy(:204-422 全文,含逐名覆盖表) =====

export const toolInvocationPolicy = (tool: AgentTool, input: JsonValue | null): ToolInvocationPolicy => {
  if (['plugin_import', 'plugin_enable', 'plugin_disable', 'plugin_delete', 'plugin_rollback', 'plugin_restore'].includes(tool.name)) {
    return { name: tool.name, category: 'plugin', mutates: true, needsApproval: true,
      autoApprovable: false, concurrencySafe: false, outputBudgetChars: toolOutputBudgetChars(tool.name),
      risk: 'sensitive', parallelGroup: null, requiresForegroundAppPackage: null,
      speculativeEligible: false, speculativeBlockReason: 'plugin_installation_changes',
      reason: null, mandatoryApproval: tool.mandatoryApproval, alwaysAsk: tool.name === 'plugin_import' };
  }
  if (tool.pluginEnvelope !== undefined) {
    const envelope = tool.pluginEnvelope;
    return { name: tool.name, category: 'plugin', mutates: envelope.mutates,
      needsApproval: envelope.needsApproval, autoApprovable: false, concurrencySafe: false,
      outputBudgetChars: toolOutputBudgetChars(tool.name), risk: envelope.risk, parallelGroup: null,
      requiresForegroundAppPackage: null, speculativeEligible: false,
      speculativeBlockReason: 'plugin_primitives_require_dispatch',
      reason: 'Plugin primitives resolve their own permissions', mandatoryApproval: tool.mandatoryApproval, alwaysAsk: false };
  }
  if (['recipe_import', 'recipe_enable', 'recipe_disable', 'recipe_delete'].includes(tool.name)) {
    return { name: tool.name, category: 'recipe', mutates: true, needsApproval: true,
      autoApprovable: false, concurrencySafe: false, outputBudgetChars: toolOutputBudgetChars(tool.name),
      risk: 'sensitive', parallelGroup: null, requiresForegroundAppPackage: null,
      speculativeEligible: false, speculativeBlockReason: 'recipe_installation_changes',
      reason: null, mandatoryApproval: false, alwaysAsk: false };
  }
  if (tool.recipeEnvelope !== undefined) {
    const envelope = tool.recipeEnvelope;
    return { name: tool.name, category: 'recipe', mutates: envelope.mutates,
      needsApproval: envelope.needsApproval, autoApprovable: false,
      concurrencySafe: false, outputBudgetChars: toolOutputBudgetChars(tool.name),
      risk: envelope.risk, parallelGroup: null, requiresForegroundAppPackage: null,
      speculativeEligible: false, speculativeBlockReason: 'recipe_steps_require_dispatch',
      reason: 'Recipe steps resolve their own permissions', mandatoryApproval: false, alwaysAsk: false };
  }
  const name: string = tool.name;
  const baseMutates: boolean = toolMutatesState(name);
  const baseRiskProfile: RiskProfile = toolRiskProfile(name);
  const baseRisk: ToolRisk = baseRiskProfile.risk;
  let mutates: boolean = baseMutates;
  let risk: ToolRisk = baseRisk;
  const riskExplicitBase: boolean = baseRiskProfile.explicit;
  let riskExplicit: boolean = riskExplicitBase;
  let mandatoryApprovalEffective: boolean = tool.mandatoryApproval;
  let needsApproval: boolean = mandatoryApprovalEffective || tool.needsApproval
    || baseMutates || baseRisk === 'high';
  let autoApprovable: boolean = !mandatoryApprovalEffective && tool.allowsAutoApproval
    && baseRisk !== 'high';
  let concurrencySafe: boolean = toolConcurrencySafe(name);

  if (name === 'http_request') {
    const method: string = (inputStringValue(input, 'method') ?? 'GET').toUpperCase();
    const readMethod: boolean = method === 'GET' || method === 'HEAD';
    // GET/HEAD 访问 loopback/私网主机可探测本机与内网服务,失去只读快车道,
    //   升为 High 风险 — 仅显式高风险开关可自动批准(:216-221 注释)
    const privateTarget: boolean = isPrivateNetworkTarget(inputStringValue(input, 'url'));
    const safe: boolean = readMethod && !privateTarget;
    mutates = !readMethod;
    risk = safe ? 'normal' : 'high';
    riskExplicit = true;
    needsApproval = !safe;
    autoApprovable = safe || (tool.allowsAutoApproval && risk !== 'high');
    concurrencySafe = safe;
  } else if (name === 'memory_tool') {
    const op: string = inputStringValue(input, 'action')
      ?? inputStringValue(input, 'operation')
      ?? inputStringValue(input, 'op')
      ?? inputStringValue(input, 'type')
      ?? 'read';
    const readOnly: boolean = ['read', 'get', 'list', 'search', 'status', 'query']
      .includes(op.toLowerCase());
    mutates = !readOnly;
    risk = readOnly ? 'normal' : 'high';
    riskExplicit = true;
    needsApproval = !readOnly;
    autoApprovable = readOnly || (tool.allowsAutoApproval && risk !== 'high');
    concurrencySafe = readOnly;
  } else if (['cron_task_list', 'agent_task_list', 'agent_task_read', 'agent_runtime_status',
    'tool_policy_explain', 'tool_search', 'tools_list'].includes(name)) {
    mutates = false;
    risk = 'normal';
    riskExplicit = true;
    needsApproval = false;
    autoApprovable = true;
    concurrencySafe = true;
  } else if (name === 'agent_prompt_config') {
    const action: string = inputStringValue(input, 'action') ?? 'get';
    const readOnly: boolean = action === 'get';
    mutates = !readOnly;
    risk = readOnly ? 'normal' : 'sensitive';
    riskExplicit = true;
    needsApproval = !readOnly;
    autoApprovable = readOnly || tool.allowsAutoApproval;
    concurrencySafe = readOnly;
  } else if (name === 'mcp_call_tool') {
    mutates = true;
    risk = 'sensitive';
    riskExplicit = true;
    needsApproval = true;
    autoApprovable = tool.allowsAutoApproval;
    concurrencySafe = false;
  } else if (name === 'wm_eval') {
    // 用户登录态 WebView 中任意 JS — 高风险变更,恒需显式批准
    mutates = true;
    risk = 'high';
    riskExplicit = true;
    needsApproval = true;
    autoApprovable = false;
    concurrencySafe = false;
  } else if (['wm_click', 'wm_tap', 'wm_type', 'wm_keys', 'wm_select'].includes(name)) {
    mutates = true;
    risk = 'sensitive';
    riskExplicit = true;
    needsApproval = true;
    autoApprovable = tool.allowsAutoApproval;
    concurrencySafe = false;
  } else if (name === 'wm_tab_close') {
    // 销毁 agent 自己打开的会话;变状态但不变用户数据 — Normal + 可自动批准
    mutates = true;
    risk = 'normal';
    riskExplicit = true;
    needsApproval = false;
    autoApprovable = tool.allowsAutoApproval;
    concurrencySafe = false;
  } else if (name === 'wm_site_add') {
    mutates = true;
    risk = 'normal';
    riskExplicit = true;
    needsApproval = false;
    autoApprovable = true;
    concurrencySafe = false;
  } else if (name === 'wm_site_remove') {
    // 清除站点 cookies/OAuth 凭据/token — 恒需逐次显式批准
    mutates = true;
    risk = 'sensitive';
    riskExplicit = true;
    needsApproval = true;
    autoApprovable = tool.allowsAutoApproval;
    concurrencySafe = false;
  } else if (name === 'wm_profile_synthesize') {
    mutates = true;
    risk = 'normal';
    riskExplicit = true;
    needsApproval = false;
    autoApprovable = true;
    concurrencySafe = false;
  } else if (name === 'wm_signed_fetch') {
    const method: string = (inputStringValue(input, 'method') ?? 'GET').toUpperCase();
    const safe: boolean = method === 'GET' || method === 'HEAD';
    mutates = !safe;
    risk = safe ? 'normal' : 'high';
    riskExplicit = true;
    needsApproval = !safe;
    autoApprovable = safe || (tool.allowsAutoApproval && risk !== 'high');
    concurrencySafe = safe;
  } else if (name === 'model_council_start') {
    if (containsExternalCliCouncilSeat(input) || allowsExternalCliCouncil(input)) {
      mutates = true;
      risk = 'sensitive';
      riskExplicit = true;
      needsApproval = true;
      autoApprovable = false;
      concurrencySafe = false;
      mandatoryApprovalEffective = true;
    }
  }

  const category: string = toolCategory(name);
  if (requiresFailClosedAutoApproval(mutates, category, riskExplicit)) {
    autoApprovable = false;
  }
  const alwaysAsk: boolean = toolAlwaysAsk(name, input);
  const foregroundRequirement: string | null = toolForegroundPackageRequirement(name);
  // wm_*:跨 session 并发、session 内严格串行;parallel group 由 session_id 派生
  const baseParallelGroup: string | null = concurrencySafe && !mutates && risk === 'normal'
    ? category
    : null;
  const parallelGroup: string | null = name.startsWith('wm_')
    ? ((): string => {
      const sessionId: string | null = inputStringValue(input, 'session_id');
      return sessionId !== null ? `webmount:${sessionId}` : 'webmount:unbound';
    })()
    : baseParallelGroup;
  const speculativeBlockReason: string | null = toolSpeculativeBlockReason(
    name, category, mutates, needsApproval, concurrencySafe, risk,
    parallelGroup, foregroundRequirement);
  return {
    name,
    category,
    mutates,
    needsApproval,
    autoApprovable,
    concurrencySafe,
    outputBudgetChars: toolOutputBudgetChars(name),
    risk,
    parallelGroup,
    requiresForegroundAppPackage: foregroundRequirement,
    speculativeEligible: speculativeBlockReason === null,
    speculativeBlockReason,
    reason: null,
    mandatoryApproval: mandatoryApprovalEffective,
    alwaysAsk,
  };
};

export const toolInvocationPolicyFromText = (tool: AgentTool, inputText: string): ToolInvocationPolicy => {
  let input: JsonValue | null = null;
  try {
    input = JSON.parse(inputText.trim().length === 0 ? '{}' : inputText) as JsonValue;
  } catch {
    input = null;
  }
  return toolInvocationPolicy(tool, input);
};

// ===== 输出预算执行(:672-707) =====

export const truncatedEnvelope = (text: string, maxChars: number): string => {
  const tailChars: number = Math.max(maxChars - 512, 1024);
  const envelope: JsonObject = {
    status: 'truncated',
    truncated: true,
    total_chars: text.length,
    max_chars: maxChars,
    content_tail: text.slice(Math.max(0, text.length - tailChars)),
    note: 'Tool output exceeded the registry output budget. The original tool result remains in the local transcript.',
  };
  return JSON.stringify(envelope);
};

export const enforceOutputBudget = (
  parts: UIMessagePart[], maxChars: number,
): UIMessagePart[] => {
  let remaining: number = maxChars;
  let truncated: boolean = false;
  const bounded: UIMessagePart[] = [];
  for (const part of parts) {
    if (part.type !== 'text') {
      bounded.push(part);
      continue;
    }
    if (remaining <= 0) {
      truncated = true;
      break;
    }
    if (part.text.length <= remaining) {
      bounded.push(part);
      remaining -= part.text.length;
    } else {
      const truncatedPart: UIMessagePartText = {
        type: 'text',
        text: truncatedEnvelope(part.text, maxChars),
        metadata: null,
      };
      bounded.push(truncatedPart);
      truncated = true;
      break;
    }
  }
  return truncated ? bounded : parts;
};

// ===== display_title 提示注入(ToolRegistry.kt:131-154) =====

export const withDisplayTitleHint = (
  schema: InputSchemaObj | null,
  toolName: string,
): InputSchemaObj | null => {
  if (schema === null) return null;
  if (toolName === 'subagent_start') return schema;
  const properties: JsonObject = { ...schema.properties };
  properties['display_title'] = {
    type: 'string',
    description: 'Optional short user-facing action title in Chinese, 4-14 chars, describing this specific step (e.g. 写入第一卷, 合并最终文件).',
  };
  if (schema.jsonSchema !== undefined) {
    return { type: 'object', properties, required: schema.required,
      jsonSchema: { ...schema.jsonSchema, properties } };
  }
  return { type: 'object', properties, required: schema.required };
};
