import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { UIMessage, UIMessagePartTool, UIMessagePartReasoning } from '../main/ets/chat/message.ts';
import {
  projectAssistantTurns,
  consecutiveTurnToolFailures,
  splitCompletedParagraphs,
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

test('turn projection keeps pending approvals, media and failures without a later recovery visible', () => {
  const pendingSsh = toolPart('t1', 'terminal_execute', { pending: true });
  const askUser = toolPart('t2', 'ask_user', { ok: true });
  const routineRead = toolPart('t3', 'file_read', { ok: true });
  const failedOnce = toolPart('t4', 'file_read', { error: 'ENOENT' });
  const lastRead = toolPart('t5', 'file_read', { error: 'ENOENT' });
  const python = toolPart('t6', 'python_execute');
  python.output = [{ type: 'text', text: '{"exit_code":1}', metadata: null }];
  const photo = toolPart('t7', 'photos_pick');
  photo.output = [{ type: 'image', url: 'file:///test/photo', metadata: null }];
  const first = textMsg('m1', 'assistant', '', [pendingSsh, askUser, failedOnce]);
  const second = textMsg('m2', 'assistant', '', [routineRead, photo, lastRead, python]);

  const [head, member] = projectAssistantTurns([first, second]);
  assert.deepEqual(head.standaloneToolIds, ['t1', 't2']);
  assert.deepEqual(member.standaloneToolIds, ['t7', 't5', 't6']);
  assert.equal(head.turnProgress?.hasError, true);
  assert.equal(consecutiveTurnToolFailures([first, second]), 2);
});

test('single-line turn status stays alive in plain human Chinese until final reply completes', () => {
  const u1 = textMsg('u1', 'user', '帮我查高分游戏');

  // 1) 刚发送、尚未吐字时：保持正在思考，不出现空白
  const emptyLive: UIMessage = { ...textMsg('a1', 'assistant', ''), finishedAt: null };
  const [_, p0] = projectAssistantTurns([u1, emptyLive], new Set(['a1']));
  assert.equal(p0.turnProgress?.statusKind, 'thinking');
  assert.equal(p0.turnProgress?.headline, '正在思考');

  // 2) 调用 search_web / 未知工具时：只展示人话动作，不泄露原始工具名
  const searchPart: UIMessagePartTool = {
    type: 'tool',
    toolCallId: 's1',
    toolName: 'search_web',
    input: JSON.stringify({ query: 'mc高分游戏' }),
    output: [],
    approvalState: { type: 'auto' },
    metadata: null,
  };
  const searching: UIMessage = { ...textMsg('a1', 'assistant', '', [searchPart]), finishedAt: null };
  const [, p1] = projectAssistantTurns([u1, searching], new Set(['a1']));
  assert.equal(p1.turnProgress?.statusKind, 'tool');
  assert.equal(p1.turnProgress?.headline, '正在搜索「mc高分游戏」…');

  const unknownTool: UIMessagePartTool = {
    type: 'tool',
    toolCallId: 'u-tool',
    toolName: 'mcp_internal_fetch_v2',
    input: '{}',
    output: [],
    approvalState: { type: 'auto' },
    metadata: null,
  };
  const queryingUnknown: UIMessage = { ...textMsg('a1', 'assistant', '', [unknownTool]), finishedAt: null };
  const [, pUnknown] = projectAssistantTurns([u1, queryingUnknown], new Set(['a1']));
  assert.equal(pUnknown.turnProgress?.statusKind, 'tool');
  assert.equal(pUnknown.turnProgress?.headline, '正在查资料…');
  assert.equal(pUnknown.turnProgress?.headline.includes('mcp_internal'), false);

  // 3) 工具已完成但正文尚未吐出时：单行状态不得消失，保持「正在整理查到的内容」
  const doneSearch: UIMessagePartTool = {
    ...searchPart,
    output: [{ type: 'text', text: '{"ok":true}', metadata: null }],
  };
  const digesting: UIMessage = { ...textMsg('a1', 'assistant', '', [doneSearch]), finishedAt: null };
  const [, p2] = projectAssistantTurns([u1, digesting], new Set(['a1']));
  assert.equal(p2.turnProgress?.statusKind, 'thinking');
  assert.equal(p2.turnProgress?.headline, '正在整理查到的内容');

  // 4) 正在流式输出最终回复时：单行状态仍保持「正在回复」直到完成
  const writing: UIMessage = { ...textMsg('a1', 'assistant', '这几年高分游戏有…', [doneSearch]), finishedAt: null };
  const [, p3] = projectAssistantTurns([u1, writing], new Set(['a1']));
  assert.equal(p3.turnProgress?.statusKind, 'writing');
  assert.equal(p3.turnProgress?.headline, '正在回复');

  // 5) 最终回复完成后：状态收敛为 completed
  const finished: UIMessage = textMsg('a1', 'assistant', '这几年高分游戏有…', [doneSearch]);
  const [, p4] = projectAssistantTurns([u1, finished], new Set());
  assert.equal(p4.turnProgress?.statusKind, 'completed');
  assert.equal(p4.isTurnTail, true);
  assert.equal(p4.tailTurnProgress?.statusKind, 'completed');
});

test('splitCompletedParagraphs buffers incomplete trailing paragraphs while streaming and preserves code fences', () => {
  assert.deepEqual(splitCompletedParagraphs('第一段还没写完', true), []);
  assert.deepEqual(splitCompletedParagraphs('第一段还没写完', false), ['第一段还没写完']);
  assert.deepEqual(
    splitCompletedParagraphs('第一段写完了。\n\n第二段正在写', true),
    ['第一段写完了。'],
  );
  assert.deepEqual(
    splitCompletedParagraphs('第一段。\n\n```ts\nconst a = 1;\n\nconst b = 2;\n```', true),
    ['第一段。'],
  );
  assert.deepEqual(
    splitCompletedParagraphs('第一段。\n\n```ts\nconst a = 1;\n\nconst b = 2;\n```\n\n第三段进行中', true),
    ['第一段。', '```ts\nconst a = 1;\n\nconst b = 2;\n```'],
  );
  assert.deepEqual(
    splitCompletedParagraphs('第一段。\n\n```ts\nconst a = 1;\n\nconst b = 2;\n```\n\n第三段完成。', false),
    ['第一段。', '```ts\nconst a = 1;\n\nconst b = 2;\n```', '第三段完成。'],
  );
});

test('multi-message assistant turn exposes tailTurnProgress on the last message for bottom status anchoring', () => {
  const u1 = textMsg('u1', 'user', '查一下');
  const a1 = textMsg('a1', 'assistant', '先看看。', [toolPart('t1', 'search_web', { ok: true })]);
  const a2: UIMessage = { ...textMsg('a2', 'assistant', '正在写第二段'), finishedAt: null };
  const [, p1, p2] = projectAssistantTurns([u1, a1, a2], new Set(['a2']));
  assert.equal(p1.turnRole, 'head');
  assert.equal(p1.isTurnTail, false);
  assert.equal(p1.tailTurnProgress, null);
  assert.equal(p2.turnRole, 'member');
  assert.equal(p2.isTurnTail, true);
  assert.equal(p2.tailTurnProgress?.statusKind, 'writing');
  assert.equal(p2.tailTurnProgress?.headline, '正在回复');
});
