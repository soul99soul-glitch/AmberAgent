// H2.2 needle 基线:压缩后「具体事实」是否还能拿到。
//
// 评分口径(每类一分,共 5 类):
//   - 文本命中:压缩产物的 searchable 文本里能直接搜到该实体
//   - 可恢复:expandConversationContext(compact.id) 能取回原始消息,原文含该实体
// 文本命中是「有损但方便」,可恢复是「必须成立」的保证——两者分开计分,
// 避免把「摘要里没写」误判成「事实丢了」。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  compactConversation, createMemoryCompactStore, expandConversationContext,
  searchConversationContext,
} from '../main/ets/chat/context_engine.ts';
import {
  compactSearchableText, makeCompactPolicy, parseCompactSummary,
} from '../main/ets/chat/context_compact.ts';
import { toMessageNode } from '../main/ets/chat/conversation.ts';
import { makeConversation } from '../main/ets/chat/conversation.ts';
import { makeUIMessage, toText } from '../main/ets/chat/message.ts';
import type { ChatStreamProvider } from '../main/ets/chat/chat_turn.ts';
import type { MessageChunk } from '../main/ets/chat/message.ts';
import type { Conversation } from '../main/ets/chat/conversation.ts';

interface Needle {
  category: string;
  value: string;
}

const NEEDLES: Needle[] = [
  { category: 'path', value: '/Users/arquiel/Downloads/AI/amber-macos/macApp/AmberMac/DesktopTranscript.swift' },
  { category: 'stable-id', value: 'provider-7f3a2b1c-9d4e' },
  { category: 'status+number', value: 'HTTP 451 after 3 retries' },
  { category: 'command+exit', value: 'gradlew test exit 1' },
  { category: 'decision-quote', value: '不做静默兜底' },
];

const emit = (onChunk: (chunk: MessageChunk) => void, text: string): void => onChunk({
  id: 'chunk', model: 'model', usage: null, choices: [{ index: 0, finishReason: null,
    message: null, delta: makeUIMessage('assistant', [{ type: 'text', text, metadata: null }]) }],
});

// 每类实体单独一条消息,保证都能被 source_message_ids 覆盖到
const conversationWithNeedles = (id: string): Conversation => makeConversation(id,
  NEEDLES.map((needle: Needle, index: number) => toMessageNode({
    ...makeUIMessage(index % 2 === 0 ? 'user' : 'assistant',
      [{ type: 'text', text: `记录 ${needle.category}: ${needle.value}`, metadata: null }]),
    id: `${id}-m${index}`,
  })).concat([0, 1, 2].map((index: number) => toMessageNode({
    ...makeUIMessage('user', [{ type: 'text', text: `最近的追问 ${index}`, metadata: null }]),
    id: `${id}-recent${index}`,
  }))));

const highQualitySummary = (): string => JSON.stringify({
  schema_version: 2,
  timeline_summary: '目标已经确定。历史已经分析。方案已经选择。结果需要验证。',
  handoff_markdown: '## Goal\n继续已经确定的任务,保持既有约束不变。'.repeat(6),
});

const providerOf = (outputs: string[]): ChatStreamProvider & { prompts: string[] } => {
  const prompts: string[] = [];
  let call: number = 0;
  return {
    prompts,
    streamText: async (messages, onChunk): Promise<void> => {
      prompts.push(toText(messages[messages.length - 1]));
      const output: string = outputs[Math.min(call, outputs.length - 1)];
      call += 1;
      emit(onChunk, output);
    },
  };
};

const scoreOf = (searchable: string, expanded: string): boolean[] => NEEDLES.map((needle: Needle) =>
  searchable.includes(needle.value) || expanded.includes(needle.value));

test('needle: a quality-gated summary keeps every category recoverable through the compact id', async () => {
  const store = createMemoryCompactStore();
  const policy = makeCompactPolicy({ keepRecentTurns: 1, precompactRatio: 0, forceRatio: 100 });
  const conversation = conversationWithNeedles('needle-good');
  const provider = providerOf([highQualitySummary()]);

  const result = await compactConversation(conversation, policy, null, 'test', '', false, {
    store, newId: () => 'compact-needle-good', provider,
  });
  assert.equal(result.status, 'completed', `compaction must complete: ${result.error ?? ''}`);
  const compacts = await store.getCompacts(conversation.id);
  const compact = compacts[0];
  const payload = parseCompactSummary(compact.summary);
  assert.notEqual(payload, null);

  // 所有被压缩消息都要能在 source_message_ids 里找到,否则原文就永久不可达
  assert.equal(payload?.sourceMessageIds.length, compact.sourceEndIndex - compact.sourceStartIndex + 1);

  const expanded = expandConversationContext(conversation, compacts, compact.id, 0)
    .map((message) => toText(message)).join('\n');
  for (const needle of NEEDLES) {
    assert.equal(expanded.includes(needle.value), true,
      `${needle.category} must stay reachable through the compact id`);
  }

  const searchable = compactSearchableText(compact.summary);
  const score = scoreOf(searchable, expanded);
  assert.equal(score.filter((hit: boolean) => hit).length, NEEDLES.length, 'recoverability is the guarantee');
  // 文本命中率是观测基线,不是保证:摘要按设计允许省略细节。
  // 当前基线 0/5(模型只改写了可读摘要)→ 一旦有人改动这条口径,这里会先红。
  const textHits = NEEDLES.filter((needle: Needle) => searchable.includes(needle.value)).length;
  assert.equal(textHits, 0, `text hits baseline changed: ${textHits}/${NEEDLES.length}`);
});

test('needle: the fallback path still keeps every category recoverable', async () => {
  const store = createMemoryCompactStore();
  const policy = makeCompactPolicy({ keepRecentTurns: 1, precompactRatio: 0, forceRatio: 100 });
  const conversation = conversationWithNeedles('needle-fallback');
  // 两次都返回不合格摘要(过短)→ 走 compactFallbackPayload
  const provider = providerOf(['太短了。', '还是太短。']);

  const result = await compactConversation(conversation, policy, null, 'test', '', false, {
    store, newId: () => 'compact-needle-fallback', provider,
  });
  assert.equal(result.status, 'completed', `fallback compaction must complete: ${result.error ?? ''}`);
  assert.equal(provider.prompts.length, 2, 'a low-quality summary is retried once before falling back');
  const compacts = await store.getCompacts(conversation.id);
  const compact = compacts[0];
  const payload = parseCompactSummary(compact.summary);
  assert.notEqual(payload, null, 'the fallback must still produce a v2 payload');
  if (payload === null) throw new Error('unreachable: payload asserted non-null');
  assert.ok(payload.handoffMarkdown.length >= 80, 'fallback handoff must pass the quality gate');

  const expanded = expandConversationContext(conversation, compacts, compact.id, 0)
    .map((message) => toText(message)).join('\n');
  for (const needle of NEEDLES) {
    assert.equal(expanded.includes(needle.value), true,
      `${needle.category} must survive the fallback path via the compact id`);
  }
  // 检索入口同样可用:按 compact id 展开后仍能按关键词命中原文
  const hits = searchConversationContext(conversation, compacts, 'HTTP 451', 5);
  assert.equal(hits.some((hit) => hit.source === 'message'), true,
    'the entity remains findable by conversation_search after compaction');

  // PD-012 登记的口径:fallback 优先采用模型文本(哪怕没有内容),源码内容只在模型输出
  // 为空/JSON 片段时才用。与 Android 逐字一致,因此这里锁定 0/5 而不是私自改顺序。
  const searchable = compactSearchableText(compact.summary);
  const textHits = NEEDLES.filter((needle: Needle) => searchable.includes(needle.value)).length;
  assert.equal(textHits, 0, `fallback text hits baseline changed: ${textHits}/${NEEDLES.length}`);
});
