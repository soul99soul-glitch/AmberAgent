// tool_permission — 权限决策解析器(D-056)
//
// Android 基准: app/.../feature/runtime/PermissionDecisionResolver.kt(全文 237 行)
//   - PermissionDecisionAction(:17-21)/PermissionDecision(:23-28)
//   - PermissionDecisionTrace + toJson(:30-73,键序逐字)
//   - resolve 优先级链(:76-196)/shouldPauseForApproval(:198-210)
//   - requiresSubAgentApproval(:212-213)/hasSessionGrant(:215-222)
//   - HISTORY_READ_TOOLS_AUTO_APPROVED_FOR_SUBAGENT(:231-234)/ASK_USER_TOOL_NAME(:236)
// 偏差登记:traceId 由调用方注入(默认随机 UUID 等价物);approvalState 串 =
//   Kotlin class simpleName('Auto'/'Pending'/...)映射自判别字段 type。

import type { JsonObject, JsonValue } from './json.ts';
import type { UIMessagePartTool, ToolApprovalState } from './message.ts';
import { newId as defaultNewId } from './ids.ts';
import type { AgentTool } from './tool.ts';
import type { ToolInvocationPolicy } from './tool_policy.ts';
import { toolInvocationPolicyFromText } from './tool_policy.ts';

// ===== ToolInvocationContext(ToolInvocationContext.kt 全文 9 行) =====

export type ToolInvocationContext = 'normal' | 'subagent' | 'cron' | 'model_council';

// ===== PermissionDecisionAction(:17-21) =====

export type PermissionDecisionAction = 'allow' | 'ask' | 'deny';

export interface PermissionDecision {
  action: PermissionDecisionAction;
  reason: string;
  source: string;
  trace: PermissionDecisionTrace;
}

// ===== PermissionDecisionTrace(:30-73) =====

export interface PermissionDecisionTrace {
  traceId: string;
  toolName: string;
  invocationContext: ToolInvocationContext;
  policy: ToolInvocationPolicy | null;
  autoApproveTools: boolean;
  autoApproveHighRiskTools: boolean;
  autoApprovedByRun: boolean;
  approvalState: string;
  action: PermissionDecisionAction;
  source: string;
  reason: string;
}

// toJson(:43-72,键序逐字;policy 可空,非空字段可选省略同 buildJsonObject 条件 put)
export const permissionDecisionTraceToJson = (t: PermissionDecisionTrace): JsonObject => {
  const out: JsonObject = {
    trace_id: t.traceId,
    tool_name: t.toolName,
    invocation_context: t.invocationContext,
    approval_state: t.approvalState,
    action: t.action,
    source: t.source,
    reason: t.reason,
    auto_approve_tools: t.autoApproveTools,
    auto_approve_high_risk_tools: t.autoApproveHighRiskTools,
    auto_approved_by_run: t.autoApprovedByRun,
  };
  if (t.policy !== null) {
    const p: ToolInvocationPolicy = t.policy;
    const policyJson: JsonObject = {
      category: p.category,
      risk: p.risk,
      mutates: p.mutates,
      needs_approval: p.needsApproval,
      auto_approvable: p.autoApprovable,
      concurrency_safe: p.concurrencySafe,
    };
    if (p.parallelGroup !== null) policyJson['parallel_group'] = p.parallelGroup;
    if (p.requiresForegroundAppPackage !== null) {
      policyJson['requires_foreground_app_package'] = p.requiresForegroundAppPackage;
    }
    policyJson['speculative_eligible'] = p.speculativeEligible;
    if (p.speculativeBlockReason !== null) {
      policyJson['speculative_block_reason'] = p.speculativeBlockReason;
    }
    policyJson['output_budget_chars'] = p.outputBudgetChars;
    policyJson['mandatory_approval'] = p.mandatoryApproval;
    policyJson['always_ask'] = p.alwaysAsk;
    if (p.reason !== null) policyJson['reason'] = p.reason;
    out['policy'] = policyJson;
  }
  return out;
};

// ===== 常量(:231-236) =====

// Historian subagent 必须能静默跑的只读历史工具(正常上下文为 Sensitive)
export const HISTORY_READ_TOOLS_AUTO_APPROVED_FOR_SUBAGENT: string[] = [
  'session_read', 'session_expand',
];

export const ASK_USER_TOOL_NAME: string = 'ask_user';

// Kotlin approvalState.javaClass.simpleName 等价
export const approvalStateSimpleName = (s: ToolApprovalState): string => {
  switch (s.type) {
    case 'auto': return 'Auto';
    case 'pending': return 'Pending';
    case 'approved': return 'Approved';
    case 'denied': return 'Denied';
    case 'answered': return 'Answered';
  }
};

// hasSessionGrant(:215-222):input JSON 的 grant_id 非空白
export const toolHasSessionGrant = (tool: UIMessagePartTool): boolean => {
  try {
    const parsed: JsonValue = JSON.parse(tool.input.trim().length === 0 ? '{}' : tool.input) as JsonValue;
    if (parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)) {
      const grant: JsonValue | undefined = (parsed as JsonObject)['grant_id'];
      return typeof grant === 'string' && grant.trim().length > 0;
    }
    return false;
  } catch {
    return false;
  }
};

// requiresSubAgentApproval(:212-213)
const SUBAGENT_APPROVAL_CATEGORIES: string[] = [
  'screen', 'terminal', 'system', 'external_file', 'office', 'cloud',
];

export const policyRequiresSubAgentApproval = (p: ToolInvocationPolicy): boolean =>
  p.mutates || p.risk !== 'normal' || SUBAGENT_APPROVAL_CATEGORIES.includes(p.category);

// ===== PermissionDecisionResolver(:75-223) =====

export interface PermissionResolverOpts {
  newId?: () => string;
}

export class PermissionDecisionResolver {
  private readonly newId: () => string;

  constructor(opts: PermissionResolverOpts = {}) {
    this.newId = opts.newId ?? defaultNewId;
  }

  public resolve(
    toolDef: AgentTool | null,
    tool: UIMessagePartTool,
    autoApproveTools: boolean,
    autoApproveHighRiskTools: boolean,
    autoApprovedToolNames: string[] = [],
    invocationContext: ToolInvocationContext = 'normal',
  ): PermissionDecision {
    const decision = (
      action: PermissionDecisionAction, reason: string, source: string,
      policy: ToolInvocationPolicy | null,
    ): PermissionDecision => {
      const trace: PermissionDecisionTrace = {
        traceId: this.newId(),
        toolName: tool.toolName,
        invocationContext,
        policy,
        autoApproveTools,
        autoApproveHighRiskTools,
        autoApprovedByRun: autoApprovedToolNames.includes(tool.toolName),
        approvalState: approvalStateSimpleName(tool.approvalState),
        action,
        source,
        reason,
      };
      return { action, reason, source, trace };
    };
    if (toolDef === null) {
      return decision(
        'deny',
        `Tool not found or not exposed. If this tool came from tools_list, call tool_search with query="${tool.toolName}" first, then retry.`,
        'tool_lookup',
        null,
      );
    }
    const policy: ToolInvocationPolicy = toolInvocationPolicyFromText(toolDef, tool.input);
    if (tool.approvalState.type !== 'auto') {
      return decision('allow', 'User already decided.', 'approval_state', policy);
    }
    if (tool.toolName === ASK_USER_TOOL_NAME && policy.needsApproval) {
      return decision('ask', 'ask_user always needs a human answer.', 'hitl', policy);
    }
    // E12 窄准入(冻结合同):wm_tap 合成页面交互、wm_fetch_replay 携带页面凭据
    //   访问网络 — 任何设置组合(含双 auto)都必须显式人工批准;approved/denied
    //   仍由上面的 approval_state 分支保持权威。与 ask_user 特例同构,不改其他工具语义。
    if ((tool.toolName === 'wm_tap' || tool.toolName === 'wm_fetch_replay') && policy.needsApproval) {
      return decision(
        'ask',
        'WebMount touch/replay always requires explicit human approval.',
        'webmount_explicit_approval',
        policy,
      );
    }
    if (autoApproveTools && autoApproveHighRiskTools) {
      return decision(
        'allow',
        'Both auto-approval toggles allow unattended tool execution.',
        'settings_unattended',
        policy,
      );
    }
    if (policy.alwaysAsk) {
      return decision('ask', 'Tool always requires explicit human approval.', 'always_ask', policy);
    }
    // Mandatory approval 门(:130-150):严于普通 auto-approval 与 run 内信任,
    //   但尊重显式 "auto approve high-risk tools" 设置
    if (policy.mandatoryApproval) {
      if (autoApproveTools && autoApproveHighRiskTools) {
        return decision(
          'allow',
          'Mandatory approval was bypassed by explicit high-risk auto-approval settings.',
          'settings_high_risk_mandatory',
          policy,
        );
      }
      return decision(
        'ask',
        'Tool requires explicit human approval unless high-risk auto-approval is enabled.',
        'mandatory_approval',
        policy,
      );
    }
    if (invocationContext === 'subagent') {
      if (HISTORY_READ_TOOLS_AUTO_APPROVED_FOR_SUBAGENT.includes(tool.toolName)
        && toolHasSessionGrant(tool)) {
        // Historian subagent 专职读历史且无回问通道;仅当 manager 已铸造
        //   有界 SessionAccessGrant 时预批准(:152-162 注释)
        return decision(
          'allow',
          'Historian subagent pre-approved for read-only history tool.',
          'subagent_history',
          policy,
        );
      }
      if (policyRequiresSubAgentApproval(policy)) {
        if (autoApproveTools && autoApproveHighRiskTools
          && tool.toolName !== ASK_USER_TOOL_NAME
          && (policy.risk === 'high' || policy.mandatoryApproval)) {
          return decision(
            'allow',
            'Sub Agent approval was bypassed by explicit high-risk auto-approval settings.',
            'settings_high_risk_subagent',
            policy,
          );
        }
        return decision('ask', 'Sub Agent context cannot silently run this tool.', 'subagent', policy);
      }
    }
    if (!policy.needsApproval) {
      return decision('allow', 'Tool is read-only for this invocation.', 'policy', policy);
    }
    if (policy.risk === 'high' && !autoApproveHighRiskTools) {
      return decision('ask', 'High-risk invocation requires explicit approval.', 'risk', policy);
    }
    if (autoApprovedToolNames.includes(tool.toolName) && tool.toolName !== ASK_USER_TOOL_NAME
      && policy.risk !== 'high') {
      return decision('allow', 'Tool was approved earlier in this run.', 'run_trust', policy);
    }
    if (autoApproveTools && autoApproveHighRiskTools && policy.risk === 'high') {
      return decision('allow', 'High-risk auto-approval allowed this invocation.', 'settings_high_risk', policy);
    }
    if (autoApproveTools && policy.autoApprovable) {
      return decision('allow', 'Global auto-approval allowed this invocation.', 'settings', policy);
    }
    return decision('ask', 'Tool requires approval.', 'ui', policy);
  }

  public shouldPauseForApproval(
    toolDef: AgentTool | null,
    tool: UIMessagePartTool,
    autoApproveTools: boolean,
    autoApproveHighRiskTools: boolean = false,
    autoApprovedToolNames: string[] = [],
  ): boolean {
    return this.resolve(
      toolDef, tool, autoApproveTools, autoApproveHighRiskTools, autoApprovedToolNames,
    ).action === 'ask';
  }
}
