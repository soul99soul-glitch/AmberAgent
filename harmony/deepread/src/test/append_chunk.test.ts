import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { appendChunk, mergeTool, findToolMergeTarget } from '../main/ets/agent/append_chunk.ts';
import { UIMessagePart, UIMessagePartTool } from '../main/ets/agent/message.ts';

// ===== Text =====

test('text delta appends to last text part', () => {
  const parts: UIMessagePart[] = [{ kind: 'text', text: 'hello' }];
  const result = appendChunk(parts, { kind: 'text', text: ' world' });
  assert.equal(result.length, 1);
  if (result[0].kind === 'text') assert.equal(result[0].text, 'hello world');
});

test('text delta creates new part when last is not text', () => {
  const parts: UIMessagePart[] = [{ kind: 'image', url: 'https://x.com/a.png' }];
  const result = appendChunk(parts, { kind: 'text', text: 'caption' });
  assert.equal(result.length, 2);
  assert.equal(result[1].kind, 'text');
});

test('empty text delta is no-op', () => {
  const parts: UIMessagePart[] = [{ kind: 'text', text: 'hello' }];
  const result = appendChunk(parts, { kind: 'text', text: '' });
  assert.equal(result, parts, 'same reference (no change)');
  assert.equal(result.length, 1);
});

test('text delta on empty parts creates single text', () => {
  const result = appendChunk([], { kind: 'text', text: 'first' });
  assert.equal(result.length, 1);
  if (result[0].kind === 'text') assert.equal(result[0].text, 'first');
});

// ===== Reasoning =====

test('reasoning delta appends to last reasoning part', () => {
  const parts: UIMessagePart[] = [{
    kind: 'reasoning', reasoning: 'step 1. ', createdAt: 1000, finishedAt: null,
  }];
  const result = appendChunk(parts, { kind: 'reasoning', reasoning: 'step 2.' });
  assert.equal(result.length, 1);
  if (result[0].kind === 'reasoning') {
    assert.equal(result[0].reasoning, 'step 1. step 2.');
    assert.equal(result[0].createdAt, 1000, 'createdAt preserved');
  }
});

test('reasoning delta with finishedAt sets finishedAt', () => {
  const parts: UIMessagePart[] = [{
    kind: 'reasoning', reasoning: 'thinking', createdAt: 1000, finishedAt: null,
  }];
  const result = appendChunk(parts, { kind: 'reasoning', reasoning: '', finishedAt: 2000 });
  assert.equal(result.length, 1);
  if (result[0].kind === 'reasoning') assert.equal(result[0].finishedAt, 2000);
});

test('reasoning delta creates new part when last is text', () => {
  const parts: UIMessagePart[] = [{ kind: 'text', text: 'a' }];
  const result = appendChunk(parts, { kind: 'reasoning', reasoning: 'think' });
  assert.equal(result.length, 2);
  assert.equal(result[1].kind, 'reasoning');
});

test('empty reasoning delta without finishedAt is no-op', () => {
  const parts: UIMessagePart[] = [{ kind: 'text', text: 'a' }];
  const result = appendChunk(parts, { kind: 'reasoning', reasoning: '' });
  assert.equal(result, parts);
});

// ===== Tool merge =====

test('tool delta with new id creates new tool part', () => {
  const parts: UIMessagePart[] = [];
  const result = appendChunk(parts, {
    kind: 'tool', toolCallId: 'c1', toolName: 'search', input: '{"q":', approvalState: 'auto',
  });
  assert.equal(result.length, 1);
  if (result[0].kind === 'tool') {
    assert.equal(result[0].toolCallId, 'c1');
    assert.equal(result[0].toolName, 'search');
    assert.equal(result[0].input, '{"q":');
  }
});

test('tool delta with same id merges into existing', () => {
  const parts: UIMessagePart[] = [{
    kind: 'tool', toolCallId: 'c1', toolName: 'search', input: '{"q":', output: [], approvalState: 'auto',
  }];
  const result = appendChunk(parts, {
    kind: 'tool', toolCallId: 'c1', toolName: '', input: '"x"}',
  });
  assert.equal(result.length, 1);
  if (result[0].kind === 'tool') {
    assert.equal(result[0].input, '{"q":"x"}', 'input accumulated');
    assert.equal(result[0].toolName, 'search', 'toolName preserved');
  }
});

test('tool streamArgsReplace replaces input entirely', () => {
  const parts: UIMessagePart[] = [{
    kind: 'tool', toolCallId: 'c1', toolName: 'write', input: 'partial', output: [], approvalState: 'auto',
  }];
  const result = appendChunk(parts, {
    kind: 'tool', toolCallId: 'c1', input: '{"full":"json"}', streamArgsReplace: true,
  });
  assert.equal(result.length, 1);
  if (result[0].kind === 'tool') {
    assert.equal(result[0].input, '{"full":"json"}', 'input replaced');
  }
});

test('tool delta cannot merge into executed tool', () => {
  const parts: UIMessagePart[] = [{
    kind: 'tool', toolCallId: 'c1', toolName: 'x', input: '{}',
    output: [{ kind: 'text', text: 'done' }],
    approvalState: 'auto',
  }];
  // executed 后,新 delta 不应合并(应新建)
  const result = appendChunk(parts, {
    kind: 'tool', toolCallId: 'c1', toolName: 'x', input: '{}',
  });
  assert.equal(result.length, 2, 'new tool part created instead of merging');
});

test('mergeTool accumulates output', () => {
  const existing: UIMessagePartTool = {
    kind: 'tool', toolCallId: 'c1', toolName: 'x', input: '{}',
    output: [{ kind: 'text', text: 'first' }],
    approvalState: 'auto',
  };
  const merged = mergeTool(existing, {
    kind: 'tool', output: [{ kind: 'text', text: ' second' }],
  });
  assert.equal(merged.output.length, 2);
});

test('mergeTool late-arriving id wins', () => {
  const existing: UIMessagePartTool = {
    kind: 'tool', toolCallId: '', toolName: 'search', input: '', output: [], approvalState: 'auto',
  };
  const merged = mergeTool(existing, {
    kind: 'tool', toolCallId: 'real-id-123',
  });
  assert.equal(merged.toolCallId, 'real-id-123');
});

// ===== findToolMergeTarget =====

test('findToolMergeTarget by toolCallId', () => {
  const parts: UIMessagePart[] = [
    { kind: 'text', text: 'a' },
    { kind: 'tool', toolCallId: 'c1', toolName: 'x', input: '{}', output: [], approvalState: 'auto' },
    { kind: 'tool', toolCallId: 'c2', toolName: 'y', input: '{}', output: [], approvalState: 'auto' },
  ];
  const idx = findToolMergeTarget(parts, { kind: 'tool', toolCallId: 'c2' });
  assert.equal(idx, 2);
});

test('findToolMergeTarget skips executed tools', () => {
  const parts: UIMessagePart[] = [
    {
      kind: 'tool', toolCallId: 'c1', toolName: 'x', input: '{}',
      output: [{ kind: 'text', text: 'done' }], approvalState: 'auto',
    },
    { kind: 'tool', toolCallId: 'c2', toolName: 'y', input: '{}', output: [], approvalState: 'auto' },
  ];
  // c1 已 executed,即使 id 匹配也不该合并到它
  const idx = findToolMergeTarget(parts, { kind: 'tool', toolCallId: 'c1' });
  assert.equal(idx, -1, 'executed tool is sealed');
});

// ===== End-to-end streaming simulation =====

test('simulate OpenAI streaming: tool_call fragments assemble correctly', () => {
  let parts: UIMessagePart[] = [];
  // 第一个 chunk:reasoning start
  parts = appendChunk(parts, { kind: 'reasoning', reasoning: 'Let me search' });
  // 第二个 chunk:text
  parts = appendChunk(parts, { kind: 'text', text: 'I will ' });
  parts = appendChunk(parts, { kind: 'text', text: 'search for that.' });
  // tool call fragments(无 id 先)
  parts = appendChunk(parts, { kind: 'tool', streamToolIndex: 0, toolName: 'search_web', input: '{"q":"' });
  parts = appendChunk(parts, { kind: 'tool', streamToolIndex: 0, input: 'latest news"}' });
  // 后来 id 到达
  parts = appendChunk(parts, { kind: 'tool', streamToolIndex: 0, toolCallId: 'call_abc' });
  // 验证
  assert.equal(parts.length, 3);
  assert.equal(parts[0].kind, 'reasoning');
  if (parts[1].kind === 'text') assert.equal(parts[1].text, 'I will search for that.');
  if (parts[2].kind === 'tool') {
    assert.equal(parts[2].toolCallId, 'call_abc');
    assert.equal(parts[2].toolName, 'search_web');
    assert.equal(parts[2].input, '{"q":"latest news"}');
  }
});

// ===== 多工具无 id 流式回归(#2:streamToolIndex 归位) =====

test('parallel tool streams: fragments land in their own part by streamToolIndex', () => {
  let parts: UIMessagePart[] = [];
  // 两个工具的首个分片都无 id,只有 index
  parts = appendChunk(parts, { kind: 'tool', streamToolIndex: 0, toolName: 'search_web', input: '{"q":"' });
  parts = appendChunk(parts, { kind: 'tool', streamToolIndex: 1, toolName: 'write_file', input: '{"path":"a' });
  // 后续分片交错到达
  parts = appendChunk(parts, { kind: 'tool', streamToolIndex: 1, input: '.txt","c":"' });
  parts = appendChunk(parts, { kind: 'tool', streamToolIndex: 0, input: 'news"}' });
  parts = appendChunk(parts, { kind: 'tool', streamToolIndex: 1, input: 'hi"}' });
  // id 迟到
  parts = appendChunk(parts, { kind: 'tool', streamToolIndex: 0, toolCallId: 'call_0' });
  parts = appendChunk(parts, { kind: 'tool', streamToolIndex: 1, toolCallId: 'call_1' });

  const tools = parts.filter(p => p.kind === 'tool');
  assert.equal(tools.length, 2, 'two distinct tool parts, no duplicates');
  const t0 = tools[0];
  const t1 = tools[1];
  if (t0.kind === 'tool' && t1.kind === 'tool') {
    assert.equal(t0.toolCallId, 'call_0');
    assert.equal(t0.toolName, 'search_web');
    assert.equal(t0.input, '{"q":"news"}');
    assert.equal(t1.toolCallId, 'call_1');
    assert.equal(t1.toolName, 'write_file');
    assert.equal(t1.input, '{"path":"a.txt","c":"hi"}');
  }
});
