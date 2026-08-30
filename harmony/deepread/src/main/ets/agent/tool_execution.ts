// executeToolsAndMerge — P0-12 核心:tool-result 写回路径
// 对应 Android AgentToolDispatcher.executeBatch + GenerationHandler tool loop
//
// 关键设计:tool result 不新建 role='tool' message,
// 而是写回原 assistant message 的 Tool part.output(按 toolCallId 匹配)
//
// 整个流程:
// 1. 找最后一条 assistant message 的未执行 tool parts
// 2. 对每个 tool,resolveDecision 决定是否执行(approval matrix)
// 3. 执行 tool,得到 output: UIMessagePartText[]
// 4. 把 output 写回 Tool part(同 toolCallId),返回新 message 数组

import {
  UIMessage, UIMessagePart, UIMessagePartTool, UIMessagePartText,
  getTools, isToolAwaitingExecution, isToolExecuted,
} from './message.ts';

// ===== 类型定义 =====

// 一个 tool 的执行结果(output 总是 [Text(jsonString)] 形态,Android 同样)
export interface ToolExecutionResult {
  toolCallId: string;
  output: UIMessagePartText[];   // 失败时也用 [Text({status:'failed',...})] 形态
}

// 决策:ASK / ALLOW / DENY
export type PermissionAction = 'ask' | 'allow' | 'deny';

export interface PermissionDecision {
  action: PermissionAction;
  reason: string;       // deny 时填
  trace: string[];      // 审批路径(用于 metadata)
}

// Tool definition(对应 Android Tool interface)
export interface ToolDefinition {
  name: string;
  description: string;
  schema?: object;
  allowsAutoApproval: boolean;       // 是否允许自动批准
  isHighRisk: boolean;
  // 真实 execute 由调用方提供(plan 3 由 SectionWriterTools 实现)
  execute: (input: string) => Promise<UIMessagePartText[]>;
}

export interface ApprovalMatrix {
  autoApproveTools: boolean;
  autoApproveHighRiskTools: boolean;
  autoApprovedToolNames: Set<string>;
}

// ===== 决策逻辑 =====

// resolveDecision — 对应 Android permissionDecisionResolver.resolve
export const resolveDecision = (
  toolDef: ToolDefinition | undefined,
  tool: UIMessagePartTool,
  matrix: ApprovalMatrix,
): PermissionDecision => {
  // 未找到定义 → ASK(防意外工具)
  if (!toolDef) {
    return {
      action: 'ask',
      reason: '',
      trace: ['tool definition not found'],
    };
  }
  // 显式列入 autoApprovedToolNames → ALLOW(DeepRead writer tool 走这里)
  if (matrix.autoApprovedToolNames.has(toolDef.name)) {
    return {
      action: 'allow',
      reason: '',
      trace: [`tool '${toolDef.name}' in autoApprovedToolNames`],
    };
  }
  // 高风险 + 未允许高风险 → ASK
  if (toolDef.isHighRisk && !matrix.autoApproveHighRiskTools) {
    return {
      action: 'ask',
      reason: `high-risk tool '${toolDef.name}' requires approval`,
      trace: [`high-risk tool '${toolDef.name}', autoApproveHighRiskTools=false`],
    };
  }
  // 普通风险 + autoApproveTools 关闭 → ASK
  if (!matrix.autoApproveTools) {
    return {
      action: 'ask',
      reason: `auto-approval disabled for '${toolDef.name}'`,
      trace: [`autoApproveTools=false`],
    };
  }
  // 默认允许
  return {
    action: 'allow',
    reason: '',
    trace: [`auto-approval allowed for '${toolDef.name}'`],
  };
};

// ===== 主执行函数 =====

export interface ExecuteToolsResult {
  messages: UIMessage[];        // 更新后的消息列表
  hasPendingApproval: boolean;  // 是否有 ASK 决策(等用户审批)
  executedCount: number;        // 本次执行的 tool 数
  deniedCount: number;          // 被拒的 tool 数
}

// 执行最后一条 message 的未执行 tool parts,并把结果写回 Tool part
export const executeToolsAndMerge = async (
  messages: UIMessage[],
  toolDefinitions: Map<string, ToolDefinition>,
  matrix: ApprovalMatrix,
): Promise<ExecuteToolsResult> => {
  if (messages.length === 0) {
    return { messages, hasPendingApproval: false, executedCount: 0, deniedCount: 0 };
  }
  const lastMessage = messages[messages.length - 1];
  // 只处理 assistant message 的 tool parts
  if (lastMessage.role !== 'assistant') {
    return { messages, hasPendingApproval: false, executedCount: 0, deniedCount: 0 };
  }
  // 找待执行 tool parts(auto/approved/denied/answered 都进入决策;
  // 'pending' 已在等用户,跳过)
  const pendingTools = getTools(lastMessage).filter(t => isToolAwaitingExecution(t));
  if (pendingTools.length === 0) {
    // 仅剩已置 pending(等用户)的工具 → 报告 pending,让 agent loop 以
    // pending_approval 退出而非空转重调模型
    const parked: boolean = getTools(lastMessage).some(
      t => !isToolExecuted(t) && t.approvalState.type === 'pending');
    return { messages, hasPendingApproval: parked, executedCount: 0, deniedCount: 0 };
  }

  // 先做决策:任何一个 ASK → 整体暂停等用户
  let hasPendingApproval = false;
  const decisions = pendingTools.map(t => {
    const def = toolDefinitions.get(t.toolName);
    const dec = resolveDecision(def, t, matrix);
    if (dec.action === 'ask') hasPendingApproval = true;
    return { tool: t, def, decision: dec };
  });

  if (hasPendingApproval) {
    // 有 ASK → 把 approvalState 标 pending,不执行,等用户
    const updatedParts = markPendingApprovals(lastMessage.parts, decisions);
    const newLast = { ...lastMessage, parts: updatedParts };
    return {
      messages: [...messages.slice(0, -1), newLast],
      hasPendingApproval: true,
      executedCount: 0,
      deniedCount: 0,
    };
  }

  // 并行执行所有 allow/deny 的 tool。
  // 结果携带原 tool part 引用:并行无 id 工具流 toolCallId 同为 '',
  // 按 id 关联会互相覆写;part 按引用流动,恒等匹配无歧义
  const results: Array<{ part: UIMessagePartTool; toolCallId: string; output: UIMessagePartText[] }> = [];
  let deniedCount = 0;
  await Promise.all(decisions.map(async ({ tool, def, decision }) => {
    if (decision.action === 'deny') {
      results.push({
        part: tool,
        toolCallId: tool.toolCallId,
        output: [makeDeniedOutput(decision.reason, decision.trace)],
      });
      deniedCount++;
      return;
    }
    // action === 'allow'
    if (!def) {
      // 不该到这里(allow 必有 def),防御
      results.push({
        part: tool,
        toolCallId: tool.toolCallId,
        output: [makeFailedOutput('tool definition missing', decision.trace)],
      });
      return;
    }
    try {
      const output = await def.execute(tool.input);
      results.push({ part: tool, toolCallId: tool.toolCallId, output });
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      results.push({
        part: tool,
        toolCallId: tool.toolCallId,
        output: [makeFailedOutput(msg, decision.trace)],
      });
    }
  }));

  // 把结果写回 Tool part(按 toolCallId 匹配)
  const updatedParts = mergeResultsIntoParts(lastMessage.parts, results);
  const newLast: UIMessage = { ...lastMessage, parts: updatedParts };

  return {
    messages: [...messages.slice(0, -1), newLast],
    hasPendingApproval: false,
    executedCount: results.length,
    deniedCount,
  };
};

// ===== 内部辅助 =====

const markPendingApprovals = (
  parts: UIMessagePart[],
  decisions: Array<{ tool: UIMessagePartTool; decision: PermissionDecision }>,
): UIMessagePart[] => {
  return parts.map(p => {
    if (p.type !== 'tool') return p;
    // 恒等匹配:并行无 id 工具的 toolCallId 同为 '',按 id 会误标全部
    const dec = decisions.find(d => d.tool === p);
    if (dec && dec.decision.action === 'ask') {
      return { ...p, approvalState: { type: 'pending' } };
    }
    return p;
  });
};

const mergeResultsIntoParts = (
  parts: UIMessagePart[],
  results: Array<{ part: UIMessagePartTool; toolCallId: string; output: UIMessagePartText[] }>,
): UIMessagePart[] => {
  return parts.map(p => {
    if (p.type !== 'tool') return p;
    const r = results.find(x => x.part === p);
    if (!r) return p;
    return { ...p, output: r.output };
  });
};

// ===== Tool output 工厂(对应 Android 各种 status JSON) =====

export const makeSuccessOutput = (payload: Record<string, unknown>): UIMessagePartText => ({
  type: 'text',
  text: JSON.stringify({ status: 'ok', ...payload }),
  metadata: null,
});

export const makeFailedOutput = (
  message: string,
  trace: string[] = [],
  recoverable = false,
): UIMessagePartText => ({
  type: 'text',
  text: JSON.stringify({ status: 'failed', message, recoverable, permission_trace: trace }),
  metadata: null,
});

export const makeDeniedOutput = (
  reason: string,
  trace: string[] = [],
): UIMessagePartText => ({
  type: 'text',
  text: JSON.stringify({ status: 'denied', message: `Tool execution denied. Reason: ${reason}`, permission_trace: trace }),
  metadata: null,
});

// 解析 tool output,提取 status(给 supervisor loop 用)
export const parseToolOutputStatus = (output: UIMessagePartText[]): string => {
  if (output.length === 0) return 'empty';
  try {
    const parsed = JSON.parse(output[0].text) as { status?: string };
    return parsed.status ?? 'unknown';
  } catch {
    return 'unparseable';
  }
};
