// message_grouping.test.ts — 阶段 A:groupMessageParts 纯逻辑测试
// Android 基准: ChatMessageCot.kt 全文(319 行)
//   groupMessageParts / ThinkingStep / MessagePartBlock /
//   coalesceSubAgentSteps / coalesceCouncilSteps / mergeDuplicateRunBlocks
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import type {
  UIMessagePart, UIMessagePartText, UIMessagePartReasoning, UIMessagePartTool,
} from '../main/ets/chat/message.ts';
import type { JsonObject } from '../main/ets/chat/json.ts';
import { groupMessageParts } from '../main/ets/chat/message_grouping.ts';
import type {
  ThinkingStep, MessagePartBlock,
} from '../main/ets/chat/message_grouping.ts';

// ===== helpers =====

const text = (t: string): UIMessagePartText => ({ type: 'text', text: t, metadata: null });
const reasoning = (r: string): UIMessagePartReasoning => ({
  type: 'reasoning', reasoning: r, createdAt: '2026-01-01T00:00:00Z', finishedAt: null, metadata: null,
});
const tool = (
  name: string, input: JsonObject = {}, output: UIMessagePart[] = [],
): UIMessagePartTool => ({
  type: 'tool',
  toolCallId: `call-${name}`,
  toolName: name,
  input: JSON.stringify(input),
  output,
  approvalState: { type: 'auto' },
  metadata: null,
});
const toolOutputText = (text: string): UIMessagePart[] => [{ type: 'text', text, metadata: null }];

const blockKind = (block: MessagePartBlock): string => {
  if (block.kind === 'thinking') return 'thinking';
  if (block.kind === 'content') return 'content';
  if (block.kind === 'subagent') return 'subagent';
  if (block.kind === 'council') return 'council';
  return 'unknown';
};

// ===== tests =====

describe('groupMessageParts basic grouping', () => {
  it('groups consecutive reasoning + tool into a single ThinkingBlock', () => {
    const parts: UIMessagePart[] = [
      reasoning('thinking 1'),
      tool('file_read'),
      text('answer'),
    ];
    const blocks = groupMessageParts(parts);
    assert.equal(blocks.length, 2);
    assert.equal(blockKind(blocks[0]), 'thinking');
    assert.equal(blockKind(blocks[1]), 'content');
    const tb = blocks[0] as { kind: 'thinking'; steps: ThinkingStep[] };
    assert.equal(tb.steps.length, 2);
    assert.equal(tb.steps[0].kind, 'reasoning');
    assert.equal(tb.steps[1].kind, 'tool');
  });

  it('flushes thinking before text and text before non-text non-thinking', () => {
    const parts: UIMessagePart[] = [
      reasoning('think'),
      text('text1'),
      reasoning('think2'),
      text('text2'),
    ];
    const blocks = groupMessageParts(parts);
    // think | text1 | think2 | text2
    assert.equal(blocks.length, 4);
    assert.equal(blockKind(blocks[0]), 'thinking');
    assert.equal(blockKind(blocks[1]), 'content');
    assert.equal(blockKind(blocks[2]), 'thinking');
    assert.equal(blockKind(blocks[3]), 'content');
  });

  it('skips blank reasoning parts', () => {
    const parts: UIMessagePart[] = [
      reasoning('   '),
      reasoning('real'),
      text('answer'),
    ];
    const blocks = groupMessageParts(parts);
    assert.equal(blocks.length, 2);
    const tb = blocks[0] as { kind: 'thinking'; steps: ThinkingStep[] };
    assert.equal(tb.steps.length, 1);
    assert.equal(tb.steps[0].kind, 'reasoning');
  });

  it('non-text non-reasoning non-tool parts become standalone ContentBlocks', () => {
    const imagePart: UIMessagePart = { type: 'image', url: 'data:image/png;base64,abc', metadata: null };
    const parts: UIMessagePart[] = [
      text('before'),
      imagePart,
      text('after'),
    ];
    const blocks = groupMessageParts(parts);
    assert.equal(blocks.length, 3);
    assert.equal(blockKind(blocks[0]), 'content');
    assert.equal(blockKind(blocks[1]), 'content');
    assert.equal(blockKind(blocks[2]), 'content');
  });

  it('empty parts list returns empty blocks', () => {
    assert.deepEqual(groupMessageParts([]), []);
  });
});

describe('groupMessageParts subagent coalescing', () => {
  it('coalesces subagent_start/wait/read sharing the same run_id into one SubAgentBlock', () => {
    const runId = 'run-1';
    const parts: UIMessagePart[] = [
      tool('subagent_start', {}, toolOutputText(JSON.stringify({ run_id: runId }))),
      tool('subagent_wait', { run_id: runId }),
      tool('subagent_read', { run_id: runId }),
      text('summary'),
    ];
    const blocks = groupMessageParts(parts);
    // SubAgentBlock | ContentBlock
    assert.equal(blocks.length, 2);
    assert.equal(blockKind(blocks[0]), 'subagent');
    assert.equal(blockKind(blocks[1]), 'content');
    const sb = blocks[0] as { kind: 'subagent'; step: { kind: 'subagent_task'; runId: string; tools: UIMessagePartTool[]; anchor: UIMessagePartTool } };
    assert.equal(sb.step.runId, runId);
    assert.equal(sb.step.tools.length, 3);
    // anchor = subagent_start
    assert.equal(sb.step.anchor.toolName, 'subagent_start');
  });

  it('keeps subagent_start without result as plain ToolStep (no run_id yet)', () => {
    const parts: UIMessagePart[] = [
      tool('subagent_start', {}, []), // no output → no run_id
      text('waiting'),
    ];
    const blocks = groupMessageParts(parts);
    // ThinkingBlock(tool) | ContentBlock
    assert.equal(blocks.length, 2);
    assert.equal(blockKind(blocks[0]), 'thinking');
    const tb = blocks[0] as { kind: 'thinking'; steps: ThinkingStep[] };
    assert.equal(tb.steps[0].kind, 'tool');
  });

  it('separates two subagent runs with different run_ids', () => {
    const parts: UIMessagePart[] = [
      tool('subagent_start', {}, toolOutputText(JSON.stringify({ run_id: 'run-a' }))),
      tool('subagent_start', {}, toolOutputText(JSON.stringify({ run_id: 'run-b' }))),
      tool('subagent_wait', { run_id: 'run-a' }),
      tool('subagent_wait', { run_id: 'run-b' }),
    ];
    const blocks = groupMessageParts(parts);
    assert.equal(blocks.length, 2);
    assert.equal(blockKind(blocks[0]), 'subagent');
    assert.equal(blockKind(blocks[1]), 'subagent');
  });

  it('lifts SubAgentBlock out of surrounding ThinkingBlock', () => {
    const runId = 'run-1';
    const parts: UIMessagePart[] = [
      reasoning('before'),
      tool('subagent_start', {}, toolOutputText(JSON.stringify({ run_id: runId }))),
      reasoning('after'),
    ];
    const blocks = groupMessageParts(parts);
    // ThinkingBlock(before) | SubAgentBlock | ThinkingBlock(after)
    assert.equal(blocks.length, 3);
    assert.equal(blockKind(blocks[0]), 'thinking');
    assert.equal(blockKind(blocks[1]), 'subagent');
    assert.equal(blockKind(blocks[2]), 'thinking');
  });
});

describe('groupMessageParts council coalescing', () => {
  it('coalesces model_council_start/wait/read sharing run_id into one CouncilBlock', () => {
    const runId = 'council-1';
    const parts: UIMessagePart[] = [
      tool('model_council_start', {}, toolOutputText(JSON.stringify({ run_id: runId }))),
      tool('model_council_wait', { run_id: runId }),
      text('verdict'),
    ];
    const blocks = groupMessageParts(parts);
    assert.equal(blocks.length, 2);
    assert.equal(blockKind(blocks[0]), 'council');
    assert.equal(blockKind(blocks[1]), 'content');
    const cb = blocks[0] as { kind: 'council'; step: { kind: 'council_task'; runId: string; tools: UIMessagePartTool[]; anchor: UIMessagePartTool } };
    assert.equal(cb.step.runId, runId);
    assert.equal(cb.step.tools.length, 2);
    assert.equal(cb.step.anchor.toolName, 'model_council_start');
  });

  it('model_council_make_report is coalesced into the same council run', () => {
    const runId = 'council-1';
    const parts: UIMessagePart[] = [
      tool('model_council_start', {}, toolOutputText(JSON.stringify({ run_id: runId }))),
      tool('model_council_make_report', { run_id: runId }),
    ];
    const blocks = groupMessageParts(parts);
    assert.equal(blocks.length, 1);
    assert.equal(blockKind(blocks[0]), 'council');
    const cb = blocks[0] as { kind: 'council'; step: { tools: UIMessagePartTool[] } };
    assert.equal(cb.step.tools.length, 2);
  });
});

describe('groupMessageParts mergeDuplicateRunBlocks', () => {
  it('merges SubAgentBlocks split by intervening text into one block', () => {
    const runId = 'run-1';
    const parts: UIMessagePart[] = [
      tool('subagent_start', {}, toolOutputText(JSON.stringify({ run_id: runId }))),
      text('intermediate text'),
      tool('subagent_wait', { run_id: runId }),
    ];
    const blocks = groupMessageParts(parts);
    // Without merge: SubAgentBlock | ContentBlock | SubAgentBlock (same runId → crash)
    // With merge: SubAgentBlock(2 tools) | ContentBlock
    assert.equal(blocks.length, 2);
    assert.equal(blockKind(blocks[0]), 'subagent');
    assert.equal(blockKind(blocks[1]), 'content');
    const sb = blocks[0] as { kind: 'subagent'; step: { tools: UIMessagePartTool[] } };
    assert.equal(sb.step.tools.length, 2);
  });

  it('merges CouncilBlocks split by intervening text', () => {
    const runId = 'council-1';
    const parts: UIMessagePart[] = [
      tool('model_council_start', {}, toolOutputText(JSON.stringify({ run_id: runId }))),
      text('middle'),
      tool('model_council_read', { run_id: runId }),
    ];
    const blocks = groupMessageParts(parts);
    assert.equal(blocks.length, 2);
    assert.equal(blockKind(blocks[0]), 'council');
    const cb = blocks[0] as { kind: 'council'; step: { tools: UIMessagePartTool[] } };
    assert.equal(cb.step.tools.length, 2);
  });
});

describe('groupMessageParts edge cases (review coverage)', () => {
  it('subagent and council in the same message do not interfere', () => {
    const subRun = 'sub-1';
    const councilRun = 'council-1';
    const parts: UIMessagePart[] = [
      tool('subagent_start', {}, toolOutputText(JSON.stringify({ run_id: subRun }))),
      tool('model_council_start', {}, toolOutputText(JSON.stringify({ run_id: councilRun }))),
      tool('subagent_wait', { run_id: subRun }),
      tool('model_council_wait', { run_id: councilRun }),
    ];
    const blocks = groupMessageParts(parts);
    // Two separate blocks, each with 2 tools
    assert.equal(blocks.length, 2);
    assert.equal(blockKind(blocks[0]), 'subagent');
    assert.equal(blockKind(blocks[1]), 'council');
    const sb = blocks[0] as { kind: 'subagent'; step: { tools: UIMessagePartTool[]; runId: string } };
    const cb = blocks[1] as { kind: 'council'; step: { tools: UIMessagePartTool[]; runId: string } };
    assert.equal(sb.step.runId, subRun);
    assert.equal(sb.step.tools.length, 2);
    assert.equal(cb.step.runId, councilRun);
    assert.equal(cb.step.tools.length, 2);
  });

  it('merges 3+ consecutive text parts with metadata override chain', () => {
    const parts: UIMessagePart[] = [
      { type: 'text', text: 'a', metadata: { k: '1' } as JsonObject },
      { type: 'text', text: 'b', metadata: null },
      { type: 'text', text: 'c', metadata: { k: '3' } as JsonObject },
    ];
    const blocks = groupMessageParts(parts);
    assert.equal(blocks.length, 1);
    const cb = blocks[0] as { kind: 'content'; part: UIMessagePartText };
    assert.equal(cb.part.text, 'abc');
    // second null → keep first; third non-null → override
    assert.deepEqual(cb.part.metadata, { k: '3' });
  });

  it('subagent_cancel extracts run_id from input', () => {
    const runId = 'run-cancel';
    const parts: UIMessagePart[] = [
      tool('subagent_start', {}, toolOutputText(JSON.stringify({ run_id: runId }))),
      tool('subagent_cancel', { run_id: runId }),
    ];
    const blocks = groupMessageParts(parts);
    assert.equal(blocks.length, 1);
    assert.equal(blockKind(blocks[0]), 'subagent');
    const sb = blocks[0] as { kind: 'subagent'; step: { tools: UIMessagePartTool[] } };
    assert.equal(sb.step.tools.length, 2);
    assert.equal(sb.step.tools[1].toolName, 'subagent_cancel');
  });
});
