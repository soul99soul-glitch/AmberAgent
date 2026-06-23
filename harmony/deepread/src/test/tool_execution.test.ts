import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import {
  resolveDecision, executeToolsAndMerge,
  makeSuccessOutput, makeFailedOutput, makeDeniedOutput, parseToolOutputStatus,
  ToolDefinition, ApprovalMatrix,
} from '../main/ets/agent/tool_execution.ts';
import {
  UIMessage, UIMessagePart, makeUserMessage, makeAssistantMessage,
} from '../main/ets/agent/message.ts';

// ===== resolveDecision =====

test('resolveDecision: tool in autoApprovedToolNames → allow', () => {
  const def: ToolDefinition = {
    name: 'deep_read_write_overview', description: 'overview',
    allowsAutoApproval: true, isHighRisk: false,
    execute: async () => [makeSuccessOutput({ section: 'overview' })],
  };
  const matrix: ApprovalMatrix = {
    autoApproveTools: true, autoApproveHighRiskTools: false,
    autoApprovedToolNames: new Set(['deep_read_write_overview']),
  };
  const dec = resolveDecision(def, {
    kind: 'tool', toolCallId: 'c1', toolName: 'deep_read_write_overview',
    input: '{}', output: [], approvalState: 'auto',
  }, matrix);
  assert.equal(dec.action, 'allow');
});

test('resolveDecision: high-risk without flag → ask', () => {
  const def: ToolDefinition = {
    name: 'delete_file', description: 'x',
    allowsAutoApproval: false, isHighRisk: true,
    execute: async () => [],
  };
  const matrix: ApprovalMatrix = {
    autoApproveTools: true, autoApproveHighRiskTools: false,
    autoApprovedToolNames: new Set(),
  };
  const dec = resolveDecision(def, {
    kind: 'tool', toolCallId: 'c1', toolName: 'delete_file',
    input: '{}', output: [], approvalState: 'auto',
  }, matrix);
  assert.equal(dec.action, 'ask');
});

test('resolveDecision: autoApproveTools=false → ask', () => {
  const def: ToolDefinition = {
    name: 'search_web', description: 'search',
    allowsAutoApproval: true, isHighRisk: false,
    execute: async () => [],
  };
  const matrix: ApprovalMatrix = {
    autoApproveTools: false, autoApproveHighRiskTools: false,
    autoApprovedToolNames: new Set(),
  };
  const dec = resolveDecision(def, {
    kind: 'tool', toolCallId: 'c1', toolName: 'search_web',
    input: '{}', output: [], approvalState: 'auto',
  }, matrix);
  assert.equal(dec.action, 'ask');
});

test('resolveDecision: missing definition → ask', () => {
  const matrix: ApprovalMatrix = {
    autoApproveTools: true, autoApproveHighRiskTools: true,
    autoApprovedToolNames: new Set(),
  };
  const dec = resolveDecision(undefined, {
    kind: 'tool', toolCallId: 'c1', toolName: 'unknown_tool',
    input: '{}', output: [], approvalState: 'auto',
  }, matrix);
  assert.equal(dec.action, 'ask');
});

// ===== executeToolsAndMerge =====

test('executeToolsAndMerge: no tool parts = no-op', async () => {
  const msgs = [makeUserMessage('q'), makeAssistantMessage([{ kind: 'text', text: 'a' }])];
  const r = await executeToolsAndMerge(msgs, new Map(), {
    autoApproveTools: true, autoApproveHighRiskTools: false, autoApprovedToolNames: new Set(),
  });
  assert.equal(r.executedCount, 0);
  assert.equal(r.hasPendingApproval, false);
});

test('executeToolsAndMerge: executes tool and writes output back to same part', async () => {
  const def: ToolDefinition = {
    name: 'deep_read_write_overview', description: 'overview',
    allowsAutoApproval: true, isHighRisk: false,
    execute: async (input) => [makeSuccessOutput({ section: 'overview', accepted: true })],
  };
  const msgs: UIMessage[] = [
    makeUserMessage('write overview'),
    makeAssistantMessage([{
      kind: 'tool', toolCallId: 'c1', toolName: 'deep_read_write_overview',
      input: '{"summary":"x"}', output: [], approvalState: 'approved',
    }]),
  ];
  const r = await executeToolsAndMerge(msgs, new Map([['deep_read_write_overview', def]]), {
    autoApproveTools: true, autoApproveHighRiskTools: false,
    autoApprovedToolNames: new Set(['deep_read_write_overview']),
  });
  assert.equal(r.executedCount, 1);
  assert.equal(r.hasPendingApproval, false);
  // 验证 tool result 写回了同一条 message 的 Tool part(P0-12 核心)
  const lastMsg = r.messages[r.messages.length - 1];
  assert.equal(lastMsg.role, 'assistant', 'no new role=tool message created');
  const tool = lastMsg.parts.find(p => p.kind === 'tool');
  if (tool && tool.kind === 'tool') {
    assert.equal(tool.toolCallId, 'c1');
    assert.equal(tool.output.length, 1);
    assert.equal(tool.output[0].kind, 'text');
    const parsed = JSON.parse(tool.output[0].text);
    assert.equal(parsed.status, 'ok');
    assert.equal(parsed.section, 'overview');
  }
});

test('executeToolsAndMerge: ASK marks approvalState pending, no execution', async () => {
  const def: ToolDefinition = {
    name: 'delete_file', description: 'x',
    allowsAutoApproval: false, isHighRisk: true,
    execute: async () => [makeSuccessOutput({})],
  };
  const msgs: UIMessage[] = [
    makeUserMessage('rm'),
    makeAssistantMessage([{
      kind: 'tool', toolCallId: 'c1', toolName: 'delete_file',
      input: '{}', output: [], approvalState: 'approved',
    }]),
  ];
  const r = await executeToolsAndMerge(msgs, new Map([['delete_file', def]]), {
    autoApproveTools: true, autoApproveHighRiskTools: false,
    autoApprovedToolNames: new Set(),
  });
  assert.equal(r.hasPendingApproval, true);
  assert.equal(r.executedCount, 0);
  // approval state 写为 pending
  const tool = r.messages[1].parts[0];
  if (tool.kind === 'tool') assert.equal(tool.approvalState, 'pending');
});

test('executeToolsAndMerge: tool execute throws → failed output', async () => {
  const def: ToolDefinition = {
    name: 'broken_tool', description: 'x',
    allowsAutoApproval: true, isHighRisk: false,
    execute: async () => { throw new Error('boom'); },
  };
  const msgs: UIMessage[] = [
    makeUserMessage('go'),
    makeAssistantMessage([{
      kind: 'tool', toolCallId: 'c1', toolName: 'broken_tool',
      input: '{}', output: [], approvalState: 'approved',
    }]),
  ];
  const r = await executeToolsAndMerge(msgs, new Map([['broken_tool', def]]), {
    autoApproveTools: true, autoApproveHighRiskTools: true,
    autoApprovedToolNames: new Set(['broken_tool']),
  });
  assert.equal(r.executedCount, 1);
  const tool = r.messages[1].parts[0];
  if (tool && tool.kind === 'tool') {
    const parsed = JSON.parse(tool.output[0].text);
    assert.equal(parsed.status, 'failed');
    assert.equal(parsed.message, 'boom');
  }
});

test('executeToolsAndMerge: skips already-executed tools', async () => {
  let execCount = 0;
  const def: ToolDefinition = {
    name: 't', description: 'x',
    allowsAutoApproval: true, isHighRisk: false,
    execute: async () => { execCount++; return [makeSuccessOutput({})]; },
  };
  const msgs: UIMessage[] = [
    makeAssistantMessage([{
      kind: 'tool', toolCallId: 'c1', toolName: 't',
      input: '{}',
      output: [{ kind: 'text', text: '{"status":"ok"}' }],  // already executed
      approvalState: 'auto',
    }]),
  ];
  const r = await executeToolsAndMerge(msgs, new Map([['t', def]]), {
    autoApproveTools: true, autoApproveHighRiskTools: true,
    autoApprovedToolNames: new Set(['t']),
  });
  assert.equal(execCount, 0);
  assert.equal(r.executedCount, 0);
});

test('executeToolsAndMerge: multiple tools execute in parallel', async () => {
  const def1: ToolDefinition = {
    name: 't1', description: '', allowsAutoApproval: true, isHighRisk: false,
    execute: async () => [makeSuccessOutput({ from: 't1' })],
  };
  const def2: ToolDefinition = {
    name: 't2', description: '', allowsAutoApproval: true, isHighRisk: false,
    execute: async () => [makeSuccessOutput({ from: 't2' })],
  };
  const msgs: UIMessage[] = [
    makeAssistantMessage([
      { kind: 'tool', toolCallId: 'c1', toolName: 't1', input: '{}', output: [], approvalState: 'approved' },
      { kind: 'tool', toolCallId: 'c2', toolName: 't2', input: '{}', output: [], approvalState: 'approved' },
    ]),
  ];
  const r = await executeToolsAndMerge(msgs, new Map([['t1', def1], ['t2', def2]]), {
    autoApproveTools: true, autoApproveHighRiskTools: true,
    autoApprovedToolNames: new Set(['t1', 't2']),
  });
  assert.equal(r.executedCount, 2);
  const parts = r.messages[0].parts;
  assert.equal(parts.length, 2);
});

// ===== Output factories =====

test('makeSuccessOutput produces status:ok JSON', () => {
  const out = makeSuccessOutput({ section: 'overview' });
  assert.equal(out.kind, 'text');
  const parsed = JSON.parse(out.text);
  assert.equal(parsed.status, 'ok');
  assert.equal(parsed.section, 'overview');
});

test('makeFailedOutput produces status:failed with message + recoverable', () => {
  const out = makeFailedOutput('timeout', ['trace1'], false);
  const parsed = JSON.parse(out.text);
  assert.equal(parsed.status, 'failed');
  assert.equal(parsed.message, 'timeout');
  assert.equal(parsed.recoverable, false);
});

test('makeDeniedOutput produces status:denied with reason', () => {
  const out = makeDeniedOutput('user rejected', ['trace1']);
  const parsed = JSON.parse(out.text);
  assert.equal(parsed.status, 'denied');
  assert.match(parsed.message, /user rejected/);
});

// ===== parseToolOutputStatus =====

test('parseToolOutputStatus extracts status from JSON', () => {
  assert.equal(parseToolOutputStatus([makeSuccessOutput({})]), 'ok');
  assert.equal(parseToolOutputStatus([makeFailedOutput('x')]), 'failed');
  assert.equal(parseToolOutputStatus([makeDeniedOutput('y')]), 'denied');
});

test('parseToolOutputStatus empty when no output', () => {
  assert.equal(parseToolOutputStatus([]), 'empty');
});

test('parseToolOutputStatus unparseable for invalid JSON', () => {
  assert.equal(parseToolOutputStatus([{ kind: 'text', text: 'not json' }]), 'unparseable');
});

// ===== Integration: agent loop one round =====

test('integration: agent loop simulates one tool execution round', async () => {
  // 初始:LLM 流式输出了一条 assistant message 含 tool call
  let messages: UIMessage[] = [
    makeUserMessage('write overview section'),
    makeAssistantMessage([
      { kind: 'text', text: 'I will write overview now.' },
      {
        kind: 'tool', toolCallId: 'c1', toolName: 'deep_read_write_overview',
        input: '{"summary":"这是一段足够长的摘要内容满足最小长度要求再加几个字"}',
        output: [], approvalState: 'approved',
      },
    ]),
  ];

  // tool 定义(对应 SectionWriterTools)
  const defs = new Map<string, ToolDefinition>([
    ['deep_read_write_overview', {
      name: 'deep_read_write_overview',
      description: 'write overview section',
      allowsAutoApproval: true, isHighRisk: false,
      execute: async (input) => {
        const args = JSON.parse(input) as { summary: string };
        if (args.summary.length < 24) {
          return [makeFailedOutput('summary too short')];
        }
        return [makeSuccessOutput({ section: 'overview', accepted: true })];
      },
    }],
  ]);

  // 执行一轮
  const result = await executeToolsAndMerge(messages, defs, {
    autoApproveTools: true, autoApproveHighRiskTools: false,
    autoApprovedToolNames: new Set(['deep_read_write_overview']),
  });

  // 验证:result 写回了原 message,没新建 role=tool
  assert.equal(result.messages.length, 2, 'no new message added');
  assert.equal(result.executedCount, 1);
  const assistantMsg = result.messages[1];
  assert.equal(assistantMsg.role, 'assistant');
  const tool = assistantMsg.parts.find(p => p.kind === 'tool');
  assert.ok(tool && tool.kind === 'tool' && tool.output.length > 0);
  assert.equal(parseToolOutputStatus(tool.output), 'ok');
});
