import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import {
  resolveDecision, executeToolsAndMerge,
  makeSuccessOutput, makeFailedOutput, makeDeniedOutput, parseToolOutputStatus,
} from '../main/ets/agent/tool_execution.ts';
import type { ToolDefinition, ApprovalMatrix } from '../main/ets/agent/tool_execution.ts';
import {
  makeUserMessage, makeAssistantMessage, makeUIMessage,
} from '../main/ets/agent/message.ts';
import type {
  UIMessage, UIMessagePart, UIMessagePartText, UIMessagePartTool, ToolApprovalState,
} from '../main/ets/agent/message.ts';

const textPart = (text: string): UIMessagePartText => ({ type: 'text', text, metadata: null });

const toolPart = (
  toolName: string,
  toolCallId: string = 'c1',
  approvalState: ToolApprovalState = { type: 'auto' },
  output: UIMessagePart[] = [],
  metadata: UIMessagePartTool['metadata'] = null,
  input: string = '{}',
): UIMessagePartTool => ({
  type: 'tool', toolCallId, toolName, input, output, approvalState, metadata,
});

const matrix = (
  autoApproveTools: boolean = true,
  autoApproveHighRiskTools: boolean = false,
  autoApprovedToolNames: string[] = [],
): ApprovalMatrix => ({
  autoApproveTools,
  autoApproveHighRiskTools,
  autoApprovedToolNames: new Set(autoApprovedToolNames),
});

// ===== resolveDecision =====

test('resolveDecision: tool in autoApprovedToolNames → allow', () => {
  const def: ToolDefinition = {
    name: 'deep_read_write_overview', description: 'overview',
    allowsAutoApproval: true, isHighRisk: false,
    execute: async () => [makeSuccessOutput({ section: 'overview' })],
  };
  const dec = resolveDecision(
    def,
    toolPart('deep_read_write_overview'),
    matrix(true, false, ['deep_read_write_overview']),
  );
  assert.equal(dec.action, 'allow');
});

test('resolveDecision: high-risk without flag → ask', () => {
  const def: ToolDefinition = {
    name: 'delete_file', description: 'x',
    allowsAutoApproval: false, isHighRisk: true,
    execute: async () => [],
  };
  const dec = resolveDecision(def, toolPart('delete_file'), matrix());
  assert.equal(dec.action, 'ask');
});

test('resolveDecision: autoApproveTools=false → ask', () => {
  const def: ToolDefinition = {
    name: 'search_web', description: 'search',
    allowsAutoApproval: true, isHighRisk: false,
    execute: async () => [],
  };
  const dec = resolveDecision(def, toolPart('search_web'), matrix(false));
  assert.equal(dec.action, 'ask');
});

test('resolveDecision: missing definition → ask', () => {
  const dec = resolveDecision(undefined, toolPart('unknown_tool'), matrix(true, true));
  assert.equal(dec.action, 'ask');
});

// ===== executeToolsAndMerge =====

test('executeToolsAndMerge: no tool parts = no-op', async () => {
  const msgs = [makeUserMessage('q'), makeAssistantMessage('a')];
  const r = await executeToolsAndMerge(msgs, new Map(), matrix());
  assert.equal(r.executedCount, 0);
  assert.equal(r.hasPendingApproval, false);
});

test('executeToolsAndMerge: executes tool and writes output back to same part', async () => {
  const def: ToolDefinition = {
    name: 'deep_read_write_overview', description: 'overview',
    allowsAutoApproval: true, isHighRisk: false,
    execute: async () => [makeSuccessOutput({ section: 'overview', accepted: true })],
  };
  const msgs: UIMessage[] = [
    makeUserMessage('write overview'),
    makeUIMessage('assistant', [
      toolPart(
        'deep_read_write_overview', 'c1', { type: 'approved' }, [], null,
        '{"summary":"x"}',
      ),
    ]),
  ];
  const r = await executeToolsAndMerge(
    msgs,
    new Map([['deep_read_write_overview', def]]),
    matrix(true, false, ['deep_read_write_overview']),
  );
  assert.equal(r.executedCount, 1);
  assert.equal(r.hasPendingApproval, false);
  // 验证 tool result 写回了同一条 message 的 Tool part(P0-12 核心)
  const lastMsg = r.messages[r.messages.length - 1];
  assert.equal(lastMsg.role, 'assistant', 'no new role=tool message created');
  const tool = lastMsg.parts.find(
    (part): part is UIMessagePartTool => part.type === 'tool',
  );
  if (tool) {
    assert.equal(tool.toolCallId, 'c1');
    assert.equal(tool.output.length, 1);
    assert.equal(tool.output[0].type, 'text');
    const parsed = JSON.parse((tool.output[0] as UIMessagePartText).text);
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
    makeUIMessage('assistant', [toolPart('delete_file', 'c1', { type: 'approved' })]),
  ];
  const r = await executeToolsAndMerge(
    msgs, new Map([['delete_file', def]]), matrix(),
  );
  assert.equal(r.hasPendingApproval, true);
  assert.equal(r.executedCount, 0);
  // approval state 写为 pending
  const tool = r.messages[1].parts[0];
  if (tool.type === 'tool') assert.deepEqual(tool.approvalState, { type: 'pending' });
});

test('executeToolsAndMerge: tool execute throws → failed output', async () => {
  const def: ToolDefinition = {
    name: 'broken_tool', description: 'x',
    allowsAutoApproval: true, isHighRisk: false,
    execute: async () => { throw new Error('boom'); },
  };
  const msgs: UIMessage[] = [
    makeUserMessage('go'),
    makeUIMessage('assistant', [toolPart('broken_tool', 'c1', { type: 'approved' })]),
  ];
  const r = await executeToolsAndMerge(
    msgs,
    new Map([['broken_tool', def]]),
    matrix(true, true, ['broken_tool']),
  );
  assert.equal(r.executedCount, 1);
  const tool = r.messages[1].parts[0];
  if (tool.type === 'tool') {
    const parsed = JSON.parse((tool.output[0] as UIMessagePartText).text);
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
    makeUIMessage('assistant', [
      toolPart('t', 'c1', { type: 'auto' }, [textPart('{"status":"ok"}')]),
    ]),
  ];
  const r = await executeToolsAndMerge(
    msgs, new Map([['t', def]]), matrix(true, true, ['t']),
  );
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
    makeUIMessage('assistant', [
      toolPart('t1', 'c1', { type: 'approved' }),
      toolPart('t2', 'c2', { type: 'approved' }),
    ]),
  ];
  const r = await executeToolsAndMerge(
    msgs,
    new Map([['t1', def1], ['t2', def2]]),
    matrix(true, true, ['t1', 't2']),
  );
  assert.equal(r.executedCount, 2);
  const parts = r.messages[0].parts;
  assert.equal(parts.length, 2);
});

// ===== Output factories =====

test('makeSuccessOutput produces status:ok JSON', () => {
  const out = makeSuccessOutput({ section: 'overview' });
  assert.equal(out.type, 'text');
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
  assert.equal(parseToolOutputStatus([textPart('not json')]), 'unparseable');
});

// ===== Integration: agent loop one round =====

test('integration: agent loop simulates one tool execution round', async () => {
  // 初始:LLM 流式输出了一条 assistant message 含 tool call
  const messages: UIMessage[] = [
    makeUserMessage('write overview section'),
    makeUIMessage('assistant', [
      textPart('I will write overview now.'),
      toolPart(
        'deep_read_write_overview', 'c1', { type: 'approved' }, [], null,
        '{"summary":"这是一段足够长的摘要内容满足最小长度要求再加几个字"}',
      ),
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
  const result = await executeToolsAndMerge(
    messages, defs, matrix(true, false, ['deep_read_write_overview']),
  );

  // 验证:result 写回了原 message,没新建 role=tool
  assert.equal(result.messages.length, 2, 'no new message added');
  assert.equal(result.executedCount, 1);
  const assistantMsg = result.messages[1];
  assert.equal(assistantMsg.role, 'assistant');
  const tool = assistantMsg.parts.find(
    (part): part is UIMessagePartTool => part.type === 'tool',
  );
  assert.ok(tool && tool.output.length > 0);
  assert.equal(parseToolOutputStatus(tool.output as UIMessagePartText[]), 'ok');
});

// ===== 流式 auto 工具回归(#1:审批态 'auto' 必须可执行) =====

test('executeToolsAndMerge: streamed approvalState=auto tool enters execution', async () => {
  const def: ToolDefinition = {
    name: 'search_web',
    description: 'search',
    allowsAutoApproval: true, isHighRisk: false,
    execute: async () => [makeSuccessOutput({ results: [] })],
  };
  const msgs: UIMessage[] = [
    makeUserMessage('search'),
    makeUIMessage('assistant', [
      // appendChunk 流式新建工具的缺省审批态 = { type: 'auto' }
      toolPart('search_web'),
    ]),
  ];
  const r = await executeToolsAndMerge(
    msgs, new Map([['search_web', def]]), matrix(true, false, ['search_web']),
  );
  assert.equal(r.executedCount, 1, 'auto tool must execute when matrix allows');
  assert.equal(r.hasPendingApproval, false);
  const tool = r.messages[1].parts.find(
    (part): part is UIMessagePartTool => part.type === 'tool',
  );
  assert.ok(tool && tool.output.length > 0);
});

test('executeToolsAndMerge: auto tool with autoApproveTools=false → ask (pending), not skipped', async () => {
  const def: ToolDefinition = {
    name: 'search_web',
    description: 'search',
    allowsAutoApproval: false, isHighRisk: false,
    execute: async () => [makeSuccessOutput({})],
  };
  const msgs: UIMessage[] = [
    makeUserMessage('search'),
    makeUIMessage('assistant', [toolPart('search_web')]),
  ];
  const r = await executeToolsAndMerge(
    msgs, new Map([['search_web', def]]), matrix(false),
  );
  assert.equal(r.executedCount, 0);
  assert.equal(r.hasPendingApproval, true, 'auto tool must surface as pending approval, not vanish');
  const tool = r.messages[1].parts.find(
    (part): part is UIMessagePartTool => part.type === 'tool',
  );
  assert.ok(tool && tool.approvalState.type === 'pending');
});

test('executeToolsAndMerge: pending tool is not re-entered', async () => {
  const def: ToolDefinition = {
    name: 'search_web',
    description: 'search',
    allowsAutoApproval: true, isHighRisk: false,
    execute: async () => [makeSuccessOutput({})],
  };
  const msgs: UIMessage[] = [
    makeUserMessage('search'),
    makeUIMessage('assistant', [toolPart('search_web', 'c1', { type: 'pending' })]),
  ];
  const r = await executeToolsAndMerge(
    msgs, new Map([['search_web', def]]), matrix(),
  );
  assert.equal(r.executedCount, 0);
  assert.equal(r.hasPendingApproval, true,
    'parked pending tools must report hasPendingApproval so agent loop exits with pending_approval');
});

// ===== 1b 回归:并行无 id 工具的结果按 part 恒等写回 =====

test('executeToolsAndMerge: parallel blank-id tools get distinct results (identity merge)', async () => {
  let calls = 0;
  const def: ToolDefinition = {
    name: 'search_web',
    description: 'search',
    allowsAutoApproval: true, isHighRisk: false,
    execute: async () => {
      calls++;
      return [makeSuccessOutput({ n: calls })];
    },
  };
  const msgs: UIMessage[] = [
    makeUserMessage('search twice'),
    makeUIMessage('assistant', [
      toolPart('search_web', '', { type: 'auto' }, [], { stream_tool_index: 0 }, '{"n":1}'),
      toolPart('search_web', '', { type: 'auto' }, [], { stream_tool_index: 1 }, '{"n":2}'),
    ]),
  ];
  const r = await executeToolsAndMerge(
    msgs, new Map([['search_web', def]]), matrix(true, false, ['search_web']),
  );
  assert.equal(r.executedCount, 2);
  const tools = r.messages[1].parts.filter(
    (part): part is UIMessagePartTool => part.type === 'tool',
  );
  assert.equal(tools.length, 2);
  assert.notEqual(tools[0].output, tools[1].output);
  const out0 = JSON.parse((tools[0].output[0] as UIMessagePartText).text) as { n: number };
  const out1 = JSON.parse((tools[1].output[0] as UIMessagePartText).text) as { n: number };
  assert.equal(out0.n, 1, 'first blank-id tool keeps its own result');
  assert.equal(out1.n, 2, 'second blank-id tool keeps its own result');
});
