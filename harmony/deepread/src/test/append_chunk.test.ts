import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { appendChunk, mergeTool, findToolMergeTarget } from '../main/ets/agent/append_chunk.ts';
import type {
  UIMessagePart, UIMessagePartText, UIMessagePartTool,
} from '../main/ets/agent/message.ts';

const textPart = (text: string): UIMessagePartText => ({ type: 'text', text, metadata: null });

const toolPart = (
  toolCallId: string,
  toolName: string,
  input: string,
  output: UIMessagePart[] = [],
  metadata: UIMessagePartTool['metadata'] = null,
): UIMessagePartTool => ({
  type: 'tool', toolCallId, toolName, input, output,
  approvalState: { type: 'auto' }, metadata,
});

// ===== Text =====

test('text delta appends to last text part', () => {
  const parts: UIMessagePart[] = [textPart('hello')];
  const result = appendChunk(parts, { type: 'text', text: ' world' });
  assert.equal(result.length, 1);
  if (result[0].type === 'text') assert.equal(result[0].text, 'hello world');
});

test('text delta creates new part when last is not text', () => {
  const parts: UIMessagePart[] = [{ type: 'image', url: 'https://x.com/a.png', metadata: null }];
  const result = appendChunk(parts, { type: 'text', text: 'caption' });
  assert.equal(result.length, 2);
  assert.equal(result[1].type, 'text');
});

test('empty text delta is no-op', () => {
  const parts: UIMessagePart[] = [textPart('hello')];
  const result = appendChunk(parts, { type: 'text', text: '' });
  assert.equal(result, parts, 'same reference (no change)');
  assert.equal(result.length, 1);
});

test('text delta on empty parts creates single text', () => {
  const result = appendChunk([], { type: 'text', text: 'first' });
  assert.equal(result.length, 1);
  if (result[0].type === 'text') assert.equal(result[0].text, 'first');
});

// ===== Reasoning =====

test('reasoning delta appends to last reasoning part', () => {
  const createdAt = '1970-01-01T00:00:01.000Z';
  const parts: UIMessagePart[] = [{
    type: 'reasoning', reasoning: 'step 1. ', createdAt, finishedAt: null, metadata: null,
  }];
  const result = appendChunk(parts, { type: 'reasoning', reasoning: 'step 2.' });
  assert.equal(result.length, 1);
  if (result[0].type === 'reasoning') {
    assert.equal(result[0].reasoning, 'step 1. step 2.');
    assert.equal(result[0].createdAt, createdAt, 'createdAt preserved');
  }
});

test('reasoning delta with finishedAt sets finishedAt', () => {
  const finishedAt = '1970-01-01T00:00:02.000Z';
  const parts: UIMessagePart[] = [{
    type: 'reasoning', reasoning: 'thinking',
    createdAt: '1970-01-01T00:00:01.000Z', finishedAt: null, metadata: null,
  }];
  const result = appendChunk(parts, { type: 'reasoning', reasoning: '', finishedAt });
  assert.equal(result.length, 1);
  if (result[0].type === 'reasoning') assert.equal(result[0].finishedAt, finishedAt);
});

test('reasoning delta creates new part when last is text', () => {
  const parts: UIMessagePart[] = [textPart('a')];
  const result = appendChunk(parts, { type: 'reasoning', reasoning: 'think' });
  assert.equal(result.length, 2);
  assert.equal(result[1].type, 'reasoning');
});

test('empty reasoning delta without finishedAt is no-op', () => {
  const parts: UIMessagePart[] = [textPart('a')];
  const result = appendChunk(parts, { type: 'reasoning', reasoning: '' });
  assert.equal(result, parts);
});

// ===== Tool merge =====

test('tool delta with new id creates new tool part', () => {
  const parts: UIMessagePart[] = [];
  const result = appendChunk(parts, {
    type: 'tool', toolCallId: 'c1', toolName: 'search', input: '{"q":',
    approvalState: { type: 'auto' },
  });
  assert.equal(result.length, 1);
  if (result[0].type === 'tool') {
    assert.equal(result[0].toolCallId, 'c1');
    assert.equal(result[0].toolName, 'search');
    assert.equal(result[0].input, '{"q":');
  }
});

test('tool delta with same id merges into existing', () => {
  const parts: UIMessagePart[] = [toolPart('c1', 'search', '{"q":')];
  const result = appendChunk(parts, {
    type: 'tool', toolCallId: 'c1', toolName: '', input: '"x"}',
  });
  assert.equal(result.length, 1);
  if (result[0].type === 'tool') {
    assert.equal(result[0].input, '{"q":"x"}', 'input accumulated');
    assert.equal(result[0].toolName, 'search', 'toolName preserved');
  }
});

test('tool streamArgsReplace replaces input entirely', () => {
  const parts: UIMessagePart[] = [toolPart('c1', 'write', 'partial')];
  const result = appendChunk(parts, {
    type: 'tool', toolCallId: 'c1', input: '{"full":"json"}', streamArgsReplace: true,
  });
  assert.equal(result.length, 1);
  if (result[0].type === 'tool') {
    assert.equal(result[0].input, '{"full":"json"}', 'input replaced');
  }
});

test('tool delta cannot merge into executed tool', () => {
  const parts: UIMessagePart[] = [
    toolPart('c1', 'x', '{}', [textPart('done')]),
  ];
  // executed 后,新 delta 不应合并(应新建)
  const result = appendChunk(parts, {
    type: 'tool', toolCallId: 'c1', toolName: 'x', input: '{}',
  });
  assert.equal(result.length, 2, 'new tool part created instead of merging');
});

test('mergeTool accumulates output', () => {
  const existing = toolPart('c1', 'x', '{}', [textPart('first')]);
  const merged = mergeTool(existing, {
    type: 'tool', output: [textPart(' second')],
  });
  assert.equal(merged.output.length, 2);
});

test('mergeTool late-arriving id wins', () => {
  const existing = toolPart('', 'search', '');
  const merged = mergeTool(existing, {
    type: 'tool', toolCallId: 'real-id-123',
  });
  assert.equal(merged.toolCallId, 'real-id-123');
});

// ===== findToolMergeTarget =====

test('findToolMergeTarget by toolCallId', () => {
  const parts: UIMessagePart[] = [
    textPart('a'),
    toolPart('c1', 'x', '{}'),
    toolPart('c2', 'y', '{}'),
  ];
  const idx = findToolMergeTarget(parts, { type: 'tool', toolCallId: 'c2' });
  assert.equal(idx, 2);
});

test('findToolMergeTarget skips executed tools', () => {
  const parts: UIMessagePart[] = [
    toolPart('c1', 'x', '{}', [textPart('done')]),
    toolPart('c2', 'y', '{}'),
  ];
  // c1 已 executed,即使 id 匹配也不该合并到它
  const idx = findToolMergeTarget(parts, { type: 'tool', toolCallId: 'c1' });
  assert.equal(idx, -1, 'executed tool is sealed');
});

// ===== End-to-end streaming simulation =====

test('simulate OpenAI streaming: tool_call fragments assemble correctly', () => {
  let parts: UIMessagePart[] = [];
  // 第一个 chunk:reasoning start
  parts = appendChunk(parts, { type: 'reasoning', reasoning: 'Let me search' });
  // 第二个 chunk:text
  parts = appendChunk(parts, { type: 'text', text: 'I will ' });
  parts = appendChunk(parts, { type: 'text', text: 'search for that.' });
  // tool call fragments(无 id 先)
  const streamMetadata = { stream_tool_index: 0 };
  parts = appendChunk(parts, {
    type: 'tool', metadata: streamMetadata, toolName: 'search_web', input: '{"q":"',
  });
  parts = appendChunk(parts, { type: 'tool', metadata: streamMetadata, input: 'latest news"}' });
  // 后来 id 到达
  parts = appendChunk(parts, { type: 'tool', metadata: streamMetadata, toolCallId: 'call_abc' });
  // 验证
  assert.equal(parts.length, 3);
  assert.equal(parts[0].type, 'reasoning');
  if (parts[1].type === 'text') assert.equal(parts[1].text, 'I will search for that.');
  if (parts[2].type === 'tool') {
    assert.equal(parts[2].toolCallId, 'call_abc');
    assert.equal(parts[2].toolName, 'search_web');
    assert.equal(parts[2].input, '{"q":"latest news"}');
  }
});

// ===== 多工具无 id 流式回归(#2:streamToolIndex 归位) =====

test('parallel tool streams: fragments land in their own part by streamToolIndex', () => {
  let parts: UIMessagePart[] = [];
  const stream0 = { stream_tool_index: 0 };
  const stream1 = { stream_tool_index: 1 };
  // 两个工具的首个分片都无 id,只有 index
  parts = appendChunk(parts, { type: 'tool', metadata: stream0, toolName: 'search_web', input: '{"q":"' });
  parts = appendChunk(parts, { type: 'tool', metadata: stream1, toolName: 'write_file', input: '{"path":"a' });
  // 后续分片交错到达
  parts = appendChunk(parts, { type: 'tool', metadata: stream1, input: '.txt","c":"' });
  parts = appendChunk(parts, { type: 'tool', metadata: stream0, input: 'news"}' });
  parts = appendChunk(parts, { type: 'tool', metadata: stream1, input: 'hi"}' });
  // id 迟到
  parts = appendChunk(parts, { type: 'tool', metadata: stream0, toolCallId: 'call_0' });
  parts = appendChunk(parts, { type: 'tool', metadata: stream1, toolCallId: 'call_1' });

  const tools = parts.filter((part): part is UIMessagePartTool => part.type === 'tool');
  assert.equal(tools.length, 2, 'two distinct tool parts, no duplicates');
  const t0 = tools[0];
  const t1 = tools[1];
  assert.equal(t0.toolCallId, 'call_0');
  assert.equal(t0.toolName, 'search_web');
  assert.equal(t0.input, '{"q":"news"}');
  assert.equal(t1.toolCallId, 'call_1');
  assert.equal(t1.toolName, 'write_file');
  assert.equal(t1.input, '{"path":"a.txt","c":"hi"}');
});
