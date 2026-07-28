// context_engine.test.ts — 上下文压缩引擎(TDD)
//
// Android 基准:
//   ConversationContextEngine.kt(:112-286/:346-605/:674-806)/
//   ConversationContextRepository.kt(:38-88)/PreparedContextEditor.kt(205 行)/
//   CompressPrompt.kt(逐字)/StringUtils.kt:37-44
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  DEFAULT_COMPRESS_PROMPT, applyPlaceholders, buildCompressionPrompt,
  COMPACT_RETRY_SUFFIX, editPreparedContext,
  createMemoryCompactStore, invalidateCompacts, copyValidCompactsToConversation,
  compactConversation, ContextCompactionFailedError, prepareContext,
  withEffectiveMessages, effectiveContextNextAction,
} from '../main/ets/chat/context_engine.ts';
import type { CompactEngineDeps, PrepareContextDeps } from '../main/ets/chat/context_engine.ts';
import {
  makeCompactPolicy,
} from '../main/ets/chat/context_compact.ts';
import type { ConversationCompact } from '../main/ets/chat/context_compact.ts';
import { makeUIMessage } from '../main/ets/chat/message.ts';
import type { UIMessage, UIMessagePart, MessageChunk } from '../main/ets/chat/message.ts';
import { makeConversation } from '../main/ets/chat/conversation.ts';
import type { Conversation, MessageNode } from '../main/ets/chat/conversation.ts';
import type { ChatStreamProvider } from '../main/ets/chat/chat_turn.ts';

// ===== 构造助手 =====

const textMsg = (id: string, role: 'system' | 'user' | 'assistant', text: string): UIMessage => ({
  ...makeUIMessage(role, [{ type: 'text', text, metadata: null }]),
  id,
});

const nodeOf = (m: UIMessage): MessageNode => ({ id: `n-${m.id}`, messages: [m], selectIndex: 0 });

const convOfTexts = (id: string, texts: string[]): Conversation => ({
  ...makeConversation(id, []),
  messageNodes: texts.map((t: string, i: number): MessageNode =>
    nodeOf(textMsg(`${id}-m${i}`, i % 2 === 0 ? 'user' : 'assistant', t))),
});

const chunkOf = (text: string): MessageChunk => ({
  id: 'c', model: 'm',
  choices: [{
    index: 0, finishReason: null,
    delta: makeUIMessage('assistant', [{ type: 'text', text, metadata: null }]),
    message: null,
  }],
  usage: null,
});

const providerOf = (responder: (prompt: string) => string): ChatStreamProvider => ({
  streamText(messages: UIMessage[], onChunk: (chunk: MessageChunk) => void): Promise<void> {
    const first = messages[0].parts[0];
    const prompt: string = first.type === 'text' ? (first as { text: string }).text : '';
    onChunk(chunkOf(responder(prompt)));
    return Promise.resolve();
  },
});

const v2Output = (timeline: string, handoff: string): string => JSON.stringify({
  schema_version: 2, timeline_summary: timeline, handoff_markdown: handoff,
});

// 高质量 payload(handoff≥80 且 timeline≥4 句)
const GOOD_OUTPUT: string = v2Output(
  '第一句。第二句。第三句。第四句。',
  '## Goal\n' + 'x'.repeat(100));

const depsOf = (provider: ChatStreamProvider, store = createMemoryCompactStore())
  : CompactEngineDeps => ({
  provider,
  store,
  now: () => 1000,
  newId: () => 'compact-new-id',
  locale: 'Chinese',
});

// ===== applyPlaceholders / DEFAULT_COMPRESS_PROMPT =====

describe('applyPlaceholders(StringUtils.kt:37-44)', () => {
  it('{key} 全部替换', () => {
    assert.equal(applyPlaceholders('a {x} b {x} {y}', [['x', '1'], ['y', '2']]), 'a 1 b 1 2');
  });

  it('压缩模板含全部占位符且可填充', () => {
    const out: string = applyPlaceholders(DEFAULT_COMPRESS_PROMPT, [
      ['target_tokens', '2000'], ['locale', 'Chinese'],
      ['additional_context', 'CTX'], ['content', 'BODY'],
    ]);
    assert.equal(out.includes('{'), false);
    assert.equal(out.includes('Target approximately 2000 tokens'), true);
    assert.equal(out.includes('<conversation>\nBODY\n</conversation>'), true);
  });
});

describe('buildCompressionPrompt(engine:731-773)', () => {
  it('schema 指令含 covered/source ids/created_at;previous 空 → None.', () => {
    const out: string = buildCompressionPrompt({
      basePrompt: DEFAULT_COMPRESS_PROMPT,
      content: 'CONV', targetTokens: 2000, additionalPrompt: '',
      sourceMessageIds: ['m1', 'm2'], previousCompacts: [],
      coveredCompactIds: ['c1'], payloadCreatedAt: 42,
      handoffPrompt: 'HP', locale: 'Chinese',
    });
    assert.equal(out.includes('"covered_compact_ids": ["c1"]'), true);
    assert.equal(out.includes('"source_message_ids": ["m1", "m2"]'), true);
    assert.equal(out.includes('"created_at": 42'), true);
    assert.equal(out.includes('Previous compact handoffs to carry forward:\nNone.'), true);
    assert.equal(out.includes('Agent-editable handoff instructions:\nHP'), true);
  });
});

// ===== PreparedContextEditor =====

describe('editPreparedContext(PreparedContextEditor.kt 全文)', () => {
  const toolMsg = (id: string, toolName: string, outputLen: number): UIMessage =>
    textMsg(id, 'assistant', '调用工具') && ({
      ...makeUIMessage('assistant', [{
        type: 'tool', toolCallId: `tc-${id}`, toolName, input: 'q',
        output: [{ type: 'text', text: 'r'.repeat(outputLen), metadata: null }],
        approvalState: { type: 'approved' }, metadata: null,
      }]),
      id,
    });

  it('长输出(>16000)→ trimmed_tool_result JSON;保留 tool_name/original chars', () => {
    const msgs: UIMessage[] = [];
    for (let i = 0; i < 30; i++) msgs.push(textMsg(`t${i}`, 'user', '短'));
    msgs.unshift(toolMsg('old', 'some_tool', 20000));
    const out = editPreparedContext(msgs, 4);
    const tool = out.messages[0].parts[0];
    assert.equal(tool.type, 'tool');
    const outText = (tool as { output: UIMessagePart[] }).output[0];
    const parsed = JSON.parse((outText as { text: string }).text) as Record<string, unknown>;
    assert.equal(parsed['status'], 'trimmed_tool_result');
    assert.equal(parsed['tool_name'], 'some_tool');
    assert.equal(parsed['original_output_chars'], 20000);
    assert.equal(out.trace.steps.length, 2);
  });

  it('可清理工具(2000<输出≤16000)→ cleared_tool_result;近期消息不动', () => {
    const old = toolMsg('old', 'file_read', 5000);
    const recent = toolMsg('recent', 'file_read', 5000);
    // count=6, keepRecent=4 → 仅 index 0/1 可编辑(editor:73)
    const pads: UIMessage[] = [textMsg('p1', 'user', 'x'), textMsg('p2', 'user', 'x'),
      textMsg('p3', 'user', 'x'), textMsg('p4', 'user', 'x')];
    const out = editPreparedContext([old, ...pads, recent], 4);
    const oldOut = (out.messages[0].parts[0] as { output: UIMessagePart[] }).output[0];
    assert.equal(JSON.parse((oldOut as { text: string }).text)['status'], 'cleared_tool_result');
    // 近期(index >= count-keepRecent)原样
    const recentOut = (out.messages[5].parts[0] as { output: UIMessagePart[] }).output[0];
    assert.equal(recentOut.type, 'text');
    assert.equal((recentOut as { text: string }).text, 'r'.repeat(5000));
  });

  it('未执行/含多模态/失败输出 → 不动', () => {
    const failed = {
      ...makeUIMessage('assistant', [{
        type: 'tool' as const, toolCallId: 'x', toolName: 'file_read', input: 'q',
        output: [{ type: 'text' as const, text: '"error": "boom"', metadata: null }],
        approvalState: { type: 'approved' as const }, metadata: null,
      }]),
      id: 'f',
    };
    const out = editPreparedContext([failed, textMsg('u', 'user', 'x')], 0);
    assert.equal(out.messages[0], failed);
  });
});

// ===== invalidate / copyValid =====

describe('invalidateCompacts + copyValidCompactsToConversation(repository:38-88)', () => {
  const compactOf = (id: string, convId: string, end: number, ids: string[]): ConversationCompact => ({
    id, conversationId: convId, summary: JSON.stringify({
      schema_version: 2, timeline_summary: 'T', handoff_markdown: 'H',
      covered_compact_ids: [],
    }), level: 1, sourceStartIndex: 0, sourceEndIndex: end,
    sourceMessageIds: ids, tokenEstimate: 1, createdAt: 1, updatedAt: 1, status: 'completed',
  });

  it('invalidate:清空 + 事件', async () => {
    const store = createMemoryCompactStore();
    await store.insertCompact(compactOf('c1', 'conv', 0, ['a']));
    await invalidateCompacts(store, 'conv', 'message_deleted');
    assert.deepEqual(await store.getCompacts('conv'), []);
    assert.equal(store.events.some((e: string): boolean =>
      e.includes('compact_invalidated') && e.includes('message_deleted')), true);
  });

  it('copyValid:合格复制 + id 重映射 + 事件;不合格跳过', async () => {
    const store = createMemoryCompactStore();
    await store.insertCompact(compactOf('c1', 'src', 1, ['t-m0', 't-m1']));
    await store.insertCompact(compactOf('c2', 'src', 5, ['ghost']));
    const target = convOfTexts('t', ['a', 'b', 'c']);
    const n: number = await copyValidCompactsToConversation(
      store, 'src', target, () => 'new-id-1', () => 99);
    assert.equal(n, 1);
    const copied: ConversationCompact[] = await store.getCompacts('t');
    assert.equal(copied.length, 1);
    assert.equal(copied[0].id, 'new-id-1');
    assert.equal(copied[0].conversationId, 't');
    assert.equal(copied[0].updatedAt, 99);
  });
});

// ===== compactConversation =====

describe('compactConversation(engine:346-605)', () => {
  const bigConv = (id: string): Conversation =>
    convOfTexts(id, Array.from({ length: 28 }, (): string => '你'.repeat(400000)));

  it('skip:below_threshold → skipped + 事件', async () => {
    const store = createMemoryCompactStore();
    const result = await compactConversation(
      convOfTexts('c', ['短', '短']), makeCompactPolicy(), null,
      'manual_compact', '', false, depsOf(providerOf((): string => GOOD_OUTPUT), store));
    assert.equal(result.status, 'skipped');
    assert.equal(result.error, 'below_threshold');
    assert.equal(store.events.some((e: string): boolean => e.includes('Skipped: below_threshold')), true);
  });

  it('completed:流式摘要 → 规范化 → compact 落库(tokenEstimate/字段)', async () => {
    const store = createMemoryCompactStore();
    const result = await compactConversation(
      bigConv('c'), makeCompactPolicy(), null,
      'manual_compact', '', false, depsOf(providerOf((): string => GOOD_OUTPUT), store));
    assert.equal(result.status, 'completed');
    assert.equal(result.summaryId, 'compact-new-id');
    const saved: ConversationCompact[] = await store.getCompacts('c');
    assert.equal(saved.length, 1);
    assert.equal(saved[0].status, 'completed');
    assert.equal(saved[0].level, 1);
    assert.equal(saved[0].createdAt, 1000);
    assert.equal(saved[0].sourceMessageIds.length > 0, true);
    const parsed = JSON.parse(saved[0].summary) as Record<string, unknown>;
    assert.equal(parsed['schema_version'], 2);
  });

  it('质量不达标 → 重试一次(提示含 RETRY_SUFFIX)→ 仍差 → fallback payload', async () => {
    const store = createMemoryCompactStore();
    const prompts: string[] = [];
    const provider: ChatStreamProvider = {
      streamText(messages: UIMessage[], onChunk: (chunk: MessageChunk) => void): Promise<void> {
        const first = messages[0].parts[0];
        prompts.push(first.type === 'text' ? (first as { text: string }).text : '');
        onChunk(chunkOf('垃圾输出'));
        return Promise.resolve();
      },
    };
    const result = await compactConversation(
      bigConv('c'), makeCompactPolicy(), null,
      'manual_compact', '', false, depsOf(provider, store));
    assert.equal(result.status, 'completed');
    assert.equal(prompts.length, 2);
    assert.equal(prompts[1].includes(COMPACT_RETRY_SUFFIX), true);
    const saved: ConversationCompact[] = await store.getCompacts('c');
    const parsed = JSON.parse(saved[0].summary) as Record<string, unknown>;
    // fallback:纯文本 → cleanHumanText 为 timeline
    assert.equal((parsed['timeline_summary'] as string).includes('垃圾输出'), true);
  });

  it('provider 抛错 → failed(记录事件);AbortError → 传播', async () => {
    const store = createMemoryCompactStore();
    const failing: ChatStreamProvider = {
      streamText(): Promise<void> { return Promise.reject(new Error('HTTP 500')); },
    };
    const result = await compactConversation(
      bigConv('c'), makeCompactPolicy(), null,
      'manual_compact', '', false, depsOf(failing, store));
    assert.equal(result.status, 'failed');
    assert.equal(result.error, 'HTTP 500');
    assert.equal(store.events.some((e: string): boolean => e.includes('HTTP 500')), true);

    const aborting: ChatStreamProvider = {
      streamText(): Promise<void> {
        const e = new Error('aborted');
        e.name = 'AbortError';
        return Promise.reject(e);
      },
    };
    await assert.rejects(
      () => compactConversation(bigConv('c'), makeCompactPolicy(), null,
        'manual_compact', '', false, depsOf(aborting, createMemoryCompactStore())),
      /aborted/);
  });
});

// ===== prepareContext =====

describe('prepareContext(engine:112-286)', () => {
  it('conversation=null → limitContext 路径', async () => {
    const msgs: UIMessage[] = [textMsg('a', 'user', '1'), textMsg('b', 'user', '2'),
      textMsg('c', 'user', '3')];
    const out = await prepareContext(null, makeCompactPolicy(), null, msgs, 2,
      depsOf(providerOf((): string => GOOD_OUTPUT)) as PrepareContextDeps);
    assert.deepEqual(out.messages.map((m: UIMessage): string => m.id), ['b', 'c']);
    assert.equal(out.compressionApplied, false);
  });

  it('force 档 → 同步压缩后注入摘要;超窗触发二次 fit 压缩(engine:220-258 忠实)', async () => {
    const conv = convOfTexts('c', Array.from({ length: 28 }, (): string => '你'.repeat(400000)));
    const store = createMemoryCompactStore();
    const msgs: UIMessage[] = conv.messageNodes.map((n: MessageNode): UIMessage => n.messages[0]);
    const out = await prepareContext(conv, makeCompactPolicy(), null, msgs, 0,
      { ...depsOf(providerOf((): string => GOOD_OUTPUT), store) } as PrepareContextDeps);
    assert.equal(out.compressionApplied, true);
    // 首次 force 压缩仅覆盖 m0..m3(keepRecent 24);余 24 条 × 400K 字符仍超
    //   窗口 forceRatio → auto_fit_model_window 二次压缩(force=true,
    //   keepRecentTurns/2)→ 共 2 个 completed compact
    assert.equal(out.summaryIds.length, 2);
    assert.equal(out.messages[0].role, 'system');
    const first = out.messages[0].parts[0];
    assert.equal(first.type === 'text'
      && (first as { text: string }).text.includes('[Conversation compact handoff:'), true);
    // 覆盖消息剔除 + fit 修剪后体积大减
    assert.equal(out.messages.length < msgs.length, true);
  });

  it('precompact 档 → launchPrecompact 触发(本轮不阻塞,无摘要注入)', async () => {
    const conv = convOfTexts('c', Array.from({ length: 28 }, (): string => '你'.repeat(300)));
    const store = createMemoryCompactStore();
    const msgs: UIMessage[] = conv.messageNodes.map((n: MessageNode): UIMessage => n.messages[0]);
    let launched: number = 0;
    const deps: PrepareContextDeps = {
      ...depsOf(providerOf((): string => GOOD_OUTPUT), store),
      launchPrecompact: (run: () => Promise<unknown>): void => {
        launched++;
        run().catch((): void => {});
      },
    };
    // 窗口调到 ratio ∈ [0.70,0.85) → precompact 档
    const out = await prepareContext(conv, makeCompactPolicy(), 11500, msgs, 0, deps);
    assert.equal(launched, 1);
    assert.equal(out.compressionApplied, false); // 本轮尚无 completed compact
    // 等 floating run 完成后 compact 已落库(下轮生效)
    await new Promise((r): void => { setTimeout(r, 20); });
    assert.equal((await store.getCompacts('c')).length, 1);
  });

  it('notifyOnly → 不触发压缩', async () => {
    const conv = convOfTexts('c', Array.from({ length: 28 }, (): string => '你'.repeat(400000)));
    const store = createMemoryCompactStore();
    const msgs: UIMessage[] = conv.messageNodes.map((n: MessageNode): UIMessage => n.messages[0]);
    const out = await prepareContext(conv, makeCompactPolicy({ notifyOnly: true }), null, msgs, 0,
      depsOf(providerOf((): string => GOOD_OUTPUT), store) as PrepareContextDeps);
    assert.equal(out.compressionApplied, false);
    assert.equal((await store.getCompacts('c')).length, 0);
  });

  it('force 压缩失败 → ContextCompactionFailedError(phase=auto_force)', async () => {
    const conv = convOfTexts('c', Array.from({ length: 28 }, (): string => '你'.repeat(400000)));
    const failing: ChatStreamProvider = {
      streamText(): Promise<void> { return Promise.reject(new Error('boom')); },
    };
    const msgs: UIMessage[] = conv.messageNodes.map((n: MessageNode): UIMessage => n.messages[0]);
    await assert.rejects(
      () => prepareContext(conv, makeCompactPolicy(), null, msgs, 0,
        depsOf(failing, createMemoryCompactStore()) as PrepareContextDeps),
      (e: Error): boolean => {
        assert.equal(e instanceof ContextCompactionFailedError, true);
        assert.equal((e as ContextCompactionFailedError).phase, 'auto_force');
        return true;
      });
  });
});

describe('withEffectiveMessages + effectiveContextNextAction(engine:775-806)', () => {
  it('edited 消息按 id 替换节点当前消息', () => {
    const conv = convOfTexts('c', ['a', 'b']);
    const edited: UIMessage = { ...conv.messageNodes[0].messages[0], parts: [
      { type: 'text', text: '改写', metadata: null },
    ] };
    const out = withEffectiveMessages(conv, [edited]);
    const first = out.messageNodes[0].messages[0].parts[0];
    assert.equal(first.type === 'text' && (first as { text: string }).text, '改写');
    // 未涉及的节点引用不变
    assert.equal(out.messageNodes[1], conv.messageNodes[1]);
  });

  it('nextAction 三档 + disabled', () => {
    const p = makeCompactPolicy();
    assert.equal(effectiveContextNextAction(p, 900, 1000), 'force_threshold');
    assert.equal(effectiveContextNextAction(p, 750, 1000), 'precompact_threshold');
    assert.equal(effectiveContextNextAction(p, 100, 1000), 'below_threshold');
    assert.equal(effectiveContextNextAction(makeCompactPolicy({ enabled: false }), 999, 1000), 'disabled');
  });
});
