// runAgentLoop 纯逻辑测试 — 组装 GenerationHandler 核心(§4.8)
// 多步循环:callModel → 检测 tool_calls → executeToolsAndMerge → 再调模型,直到无 tool 或达 maxSteps/budget FINAL

import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { runAgentLoop } from '../main/ets/agent/agent_loop.ts';
import type { AgentLoopDeps } from '../main/ets/agent/agent_loop.ts';
import type { UIMessage } from '../main/ets/agent/message.ts';
import { makeUserMessage, makeAssistantMessage, makeUIMessage } from '../main/ets/agent/message.ts';
import type { ToolDefinition, ApprovalMatrix } from '../main/ets/agent/tool_execution.ts';

// 构造一个 assistant message 含一个 tool call(未执行)
const assistantWithToolCall = (
  toolName: string,
  toolCallId: string,
  input: string,
): UIMessage => makeUIMessage('assistant', [
  { type: 'text', text: '正在调用工具。', metadata: null },
  {
    type: 'tool', toolCallId, toolName, input, approvalState: { type: 'approved' },
    output: [], metadata: null,
  },
]);

const assistantText = (text: string): UIMessage =>
  makeAssistantMessage(text);

const approval: ApprovalMatrix = {
  autoApproveTools: true,
  autoApproveHighRiskTools: false,
  autoApprovedToolNames: new Set(['writer_tool']),
};

// ===== 3. 多 tool 调用链:3 步循环 =====

test('runAgentLoop: chain of tool calls (3 steps)', async () => {
  let callCount = 0;
  const toolA: ToolDefinition = {
    name: 'tool_a', description: 'd', allowsAutoApproval: true, isHighRisk: false,
    execute: async () => [{ type: 'text', text: '{"status":"ok"}', metadata: null }],
  };
  const deps: AgentLoopDeps = {
    callModel: async (messages) => {
      callCount++;
      if (callCount === 1) return [...messages, assistantWithToolCall('tool_a', 'c1', '{}')];
      if (callCount === 2) return [...messages, assistantWithToolCall('tool_a', 'c2', '{}')];
      return [...messages, assistantText('done')];
    },
    tools: new Map([['tool_a', toolA]]),
    approval,
    maxSteps: 8,
  };
  const result = await runAgentLoop([makeUserMessage('q')], deps);
  assert.equal(result.steps, 3);
  assert.equal(result.stopReason, 'no_more_tools');
});

// ===== 4. maxSteps 耗尽 → stopReason max_steps =====

test('runAgentLoop: hits maxSteps → stopReason max_steps', async () => {
  // callModel 永远返回 tool call(不收敛)
  let callCount = 0;
  const tool: ToolDefinition = {
    name: 'tool_a', description: 'd', allowsAutoApproval: true, isHighRisk: false,
    execute: async () => [{ type: 'text', text: '{}', metadata: null }],
  };
  const deps: AgentLoopDeps = {
    callModel: async (messages) => {
      callCount++;
      return [...messages, assistantWithToolCall('tool_a', `c${callCount}`, '{}')];
    },
    tools: new Map([['tool_a', tool]]),
    approval,
    maxSteps: 3,
  };
  const result = await runAgentLoop([makeUserMessage('q')], deps);
  assert.equal(result.stopReason, 'max_steps');
  assert.ok(result.steps <= 3);
});

// ===== 5. ASK 决策(需用户审批)→ 暂停,stopReason pending_approval =====

test('runAgentLoop: tool requires approval (ASK) → pause, pending_approval', async () => {
  const highRiskTool: ToolDefinition = {
    name: 'high_risk_tool', description: 'd', allowsAutoApproval: false, isHighRisk: true,
    execute: async () => [{ type: 'text', text: '{}', metadata: null }],
  };
  const noAutoApprove: ApprovalMatrix = {
    autoApproveTools: false,
    autoApproveHighRiskTools: false,
    autoApprovedToolNames: new Set(),
  };
  const deps: AgentLoopDeps = {
    callModel: async (messages) => [...messages, assistantWithToolCall('high_risk_tool', 'tc1', '{}')],
    tools: new Map([['high_risk_tool', highRiskTool]]),
    approval: noAutoApprove,
    maxSteps: 4,
  };
  const result = await runAgentLoop([makeUserMessage('q')], deps);
  assert.equal(result.stopReason, 'pending_approval');
  assert.equal(result.pendingApproval, true);
});

// ===== 6. tool 执行抛错 → 失败 output 写回,循环继续(不中断) =====

test('runAgentLoop: tool throws → failed output written back, loop continues', async () => {
  let callCount = 0;
  const throwingTool: ToolDefinition = {
    name: 'tool_a', description: 'd', allowsAutoApproval: true, isHighRisk: false,
    execute: async () => { throw new Error('boom'); },
  };
  const deps: AgentLoopDeps = {
    callModel: async (messages) => {
      callCount++;
      if (callCount === 1) return [...messages, assistantWithToolCall('tool_a', 'c1', '{}')];
      return [...messages, assistantText('恢复后回答')];
    },
    tools: new Map([['tool_a', throwingTool]]),
    approval,
    maxSteps: 4,
  };
  const result = await runAgentLoop([makeUserMessage('q')], deps);
  assert.equal(result.stopReason, 'no_more_tools');
  // tool part 的 output 应是 failed JSON
  const toolMsg = result.messages[result.messages.length - 2];
  const toolPart = toolMsg.parts.find(p => p.type === 'tool');
  assert.ok(toolPart !== undefined && toolPart.type === 'tool');
  const output = toolPart.output[0];
  assert.equal(output.type, 'text');
  const outJson = JSON.parse(output.type === 'text' ? output.text : '{}');
  assert.equal(outJson.status, 'failed');
  assert.equal(outJson.message, 'boom');
});

// ===== 7. budget FINAL(无 resumable tool)→ 注入 budget 提醒 + 不再给 tool =====

test('runAgentLoop: budget FINAL (small loop) → passes budget prompt to callModel', async () => {
  let callCount = 0;
  const receivedBudgets: string[] = [];
  const deps: AgentLoopDeps = {
    callModel: async (messages, _exposed, budgetPrompt) => {
      if (budgetPrompt.length > 0) receivedBudgets.push(budgetPrompt);
      callCount++;
      if (callCount <= 2) return [...messages, assistantWithToolCall('writer_tool', `c${callCount}`, '{}')];
      return [...messages, assistantText('答案')];
    },
    tools: new Map([['writer_tool', {
      name: 'writer_tool', description: 'd', allowsAutoApproval: true, isHighRisk: false,
      execute: async () => [{ type: 'text', text: '{"status":"ok"}', metadata: null }],
    }]]),
    approval,
    maxSteps: 3,  // small loop:step 1 → TIGHT, step 2 → FINAL
  };
  const result = await runAgentLoop([makeUserMessage('q')], deps);
  assert.ok(receivedBudgets.length > 0, 'budget prompt passed to callModel');
  assert.ok(receivedBudgets.some(b => b.includes('最后机会') || b.includes('紧急')), 'FINAL or TIGHT injected');
  assert.ok(result.steps <= 3);
});

// ===== 9. onMessagesUpdate 回调(模拟流式 33ms flush 快照) =====

test('runAgentLoop: onMessagesUpdate called after each step', async () => {
  let updateCount = 0;
  let callCount = 0;
  const tool: ToolDefinition = {
    name: 'tool_a', description: 'd', allowsAutoApproval: true, isHighRisk: false,
    execute: async () => [{ type: 'text', text: '{}', metadata: null }],
  };
  const deps: AgentLoopDeps = {
    callModel: async (messages) => {
      callCount++;
      if (callCount === 1) return [...messages, assistantWithToolCall('tool_a', 'c1', '{}')];
      return [...messages, assistantText('done')];
    },
    tools: new Map([['tool_a', tool]]),
    approval,
    maxSteps: 4,
    onMessagesUpdate: (msgs) => { updateCount++; assert.ok(msgs.length > 0); },
  };
  await runAgentLoop([makeUserMessage('q')], deps);
  assert.ok(updateCount >= 2, `onMessagesUpdate called per step, got ${updateCount}`);
});

// ===== 10. AbortSignal 取消 =====

test('runAgentLoop: aborted signal before start → returns stopReason aborted (no throw)', async () => {
  const controller = new AbortController();
  controller.abort();
  let callCount = 0;
  const deps: AgentLoopDeps = {
    callModel: async (messages) => { callCount++; return [...messages, assistantText('x')]; },
    tools: new Map(),
    approval,
    maxSteps: 4,
    signal: controller.signal,
  };
  const result = await runAgentLoop([makeUserMessage('q')], deps);
  assert.equal(result.stopReason, 'aborted');
  assert.equal(callCount, 0, 'callModel not invoked when already aborted');
});
