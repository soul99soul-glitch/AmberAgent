import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { UIMessage, UIMessagePartTool, UIMessagePartReasoning } from '../main/ets/chat/message.ts';
import {
  projectAssistantTurns,
  isExemptStandaloneToolPart,
  buildLaterSucceededToolSet,
} from '../main/ets/chat/turn_tool_grouping.ts';

const textMsg = (
  id: string,
  role: 'user' | 'assistant',
  text: string,
  tools: UIMessagePartTool[] = [],
  reasonings: UIMessagePartReasoning[] = [],
): UIMessage => ({
  id,
  role,
  parts: [
    ...reasonings,
    ...tools,
    ...(text.length > 0 ? [{ type: 'text' as const, text, metadata: null }] : []),
  ],
  createdAt: '2026-11-19T12:00:00Z',
  finishedAt: '2026-11-19T12:00:01Z',
  modelId: 'gpt-test',
  usage: null,
  annotations: [],
});

const toolPart = (
  toolCallId: string,
  toolName: string,
  opts: { pending?: boolean; denied?: boolean; error?: string; ok?: boolean; displayTitle?: string } = {},
): UIMessagePartTool => ({
  type: 'tool',
  toolCallId,
  toolName,
  input: JSON.stringify(opts.displayTitle ? { display_title: opts.displayTitle } : {}),
  output: opts.error
    ? [{ type: 'text', text: JSON.stringify({ ok: false, error: opts.error }), metadata: null }]
    : (opts.ok ? [{ type: 'text', text: JSON.stringify({ ok: true }), metadata: null }] : []),
  approvalState: opts.pending
    ? { type: 'pending', reason: '需要确认' }
    : (opts.denied ? { type: 'denied', reason: '用户拒绝' } : { type: 'auto' }),
  metadata: null,
});

test('projectAssistantTurns deduplicates continuation headers and summarizes single-line turn progress', () => {
  const u1 = textMsg('u1', 'user', '帮我查一下配置');
  const a1 = textMsg('a1', 'assistant', '我先翻一下配置文件。', [
    toolPart('t1', 'file_read', { ok: true, displayTitle: '读取配置文件' }),
  ]);
  const a2 = textMsg('a2', 'assistant', '', [
    toolPart('t2', 'file_search', { displayTitle: '搜索关联引用' }),
  ]);
  const u2 = textMsg('u2', 'user', '好的，继续');
  const a3 = textMsg('a3', 'assistant', '收到');

  const projections = projectAssistantTurns([u1, a1, a2, u2, a3], new Set(['a2']));
  assert.equal(projections[0].turnRole, 'none');
  assert.equal(projections[1].turnRole, 'head');
  assert.equal(projections[1].isAssistantContinuation, false);
  assert.equal(projections[1].turnProgress?.showSingleLineStatus, true);
  assert.equal(projections[1].turnProgress?.statusKind, 'tool');
  assert.equal(projections[1].turnProgress?.headline, '搜索关联引用…');
  assert.equal(projections[1].turnProgress?.allTools.length, 2);

  assert.equal(projections[2].turnRole, 'member');
  assert.equal(projections[2].isAssistantContinuation, true);
  assert.equal(projections[2].turnProgress, null);

  // 新一轮用户消息后重置 turn head
  assert.equal(projections[4].turnRole, 'head');
  assert.equal(projections[4].isAssistantContinuation, false);
});

test('isExemptStandaloneToolPart keeps pending approvals, ask_user, and unrecovered failures visible', () => {
  const pendingSsh = toolPart('t1', 'terminal_execute', { pending: true });
  const askUser = toolPart('t2', 'ask_user', { ok: true });
  const routineRead = toolPart('t3', 'file_read', { ok: true });
  const failedOnce = toolPart('t4', 'file_read', { error: 'ENOENT' });
  const failedUnrecovered = toolPart('t5', 'http_request', { error: '502' });

  const laterSucceeded = buildLaterSucceededToolSet([
    textMsg('m1', 'assistant', '', [failedOnce, routineRead, failedUnrecovered]),
  ]);

  assert.equal(isExemptStandaloneToolPart(pendingSsh, laterSucceeded), true);
  assert.equal(isExemptStandaloneToolPart(askUser, laterSucceeded), true);
  // routine read is collapsed
  assert.equal(isExemptStandaloneToolPart(routineRead, laterSucceeded), false);
  // failedOnce was later recovered by a successful file_read
  assert.equal(isExemptStandaloneToolPart(failedOnce, laterSucceeded), false);
  // failedUnrecovered was never recovered -> exempt and stays visible
  assert.equal(isExemptStandaloneToolPart(failedUnrecovered, laterSucceeded), true);
});
