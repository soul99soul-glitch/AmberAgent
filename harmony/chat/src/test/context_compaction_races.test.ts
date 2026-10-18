import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  buildCompressionPrompt, compactConversation, createMemoryCompactStore,
  DEFAULT_COMPRESS_PROMPT, invalidateCompacts, prepareContext,
} from '../main/ets/chat/context_engine.ts';
import {
  makeCompactPolicy, planCompaction, prepareMessagesWithCompacts, selectCompactsForInjection,
} from '../main/ets/chat/context_compact.ts';
import { currentMessages, makeConversation, toMessageNode } from '../main/ets/chat/conversation.ts';
import { editMessage } from '../main/ets/chat/edit_message.ts';
import { selectMessageBranch } from '../main/ets/chat/regenerate.ts';
import { makeUIMessage, toText } from '../main/ets/chat/message.ts';
import type { ChatStreamProvider } from '../main/ets/chat/chat_turn.ts';
import type { CompactResult } from '../main/ets/chat/context_compact.ts';
import type { MessageChunk } from '../main/ets/chat/message.ts';

const conversationOf = (id: string, count: number, text: string) => makeConversation(id,
  Array.from({ length: count }, (_, index) => toMessageNode({
    ...makeUIMessage(index % 2 === 0 ? 'user' : 'assistant', [{ type: 'text', text, metadata: null }]),
    id: `${id}-m${index}`,
  })));

const summaryOutput = (fact: string = ''): string => JSON.stringify({
  schema_version: 2, timeline_summary: '目标已经确定。历史已经分析。方案已经选择。结果需要验证。',
  handoff_markdown: '## Goal\n' + fact + '继续执行已经确定的任务。'.repeat(20),
});

const emit = (onChunk: (chunk: MessageChunk) => void, text: string): void => onChunk({
  id: 'chunk', model: 'model', usage: null, choices: [{ index: 0, finishReason: null,
    message: null, delta: makeUIMessage('assistant', [{ type: 'text', text, metadata: null }]) }],
});

const deferred = () => {
  let resolve!: () => void;
  const promise = new Promise<void>(done => { resolve = done; });
  return { promise, resolve };
};

test('branch invalidation rejects a late incremental compact carrying only a previous branch fact', async () => {
  const store = createMemoryCompactStore();
  const policy = makeCompactPolicy({ keepRecentTurns: 1, precompactRatio: 0, forceRatio: 100 });
  const originalFact = 'ORIGINAL SECRET = old-value';
  const first = conversationOf('branch-race', 8, 'neutral history');
  first.messageNodes[0].messages[0].parts = [{ type: 'text', text: originalFact, metadata: null }];
  assert.equal((await compactConversation(first, policy, null, 'test', '', false, {
    store, newId: () => 'prior', provider: { streamText: async (_messages, onChunk) => {
      emit(onChunk, summaryOutput(originalFact));
    } },
  })).status, 'completed');

  const base = conversationOf(first.id, 12, 'neutral history');
  base.messageNodes[0].messages[0] = first.messageNodes[0].messages[0];
  const alternative = editMessage(base, `${first.id}-m0`, [
    { type: 'text', text: 'REVISED SECRET = new-value', metadata: null },
  ]);
  const originalBranch = selectMessageBranch(alternative, alternative.messageNodes[0].id, 0);
  const entered = deferred();
  const release = deferred();
  let prompt = '';
  const pending = compactConversation(originalBranch, policy, null, 'test', '', false, {
    store, newId: () => 'late', provider: { streamText: async (messages, onChunk) => {
      prompt = toText(messages[0]);
      entered.resolve();
      await release.promise;
      emit(onChunk, summaryOutput(originalFact));
    } },
  });
  await entered.promise;
  const source = prompt.split('<conversation>\n')[1].split('\n</conversation>')[0];
  assert.ok(prompt.includes(originalFact), 'old fact is carried from the previous handoff');
  assert.ok(!source.includes('old-value') && !source.includes('new-value'),
    'the incremental source has no branch fact of its own');
  const edited = selectMessageBranch(originalBranch, originalBranch.messageNodes[0].id, 1);
  await invalidateCompacts(store, edited.id, 'message_branch_changed');
  release.resolve();
  assert.equal((await pending).status, 'skipped');
  const saved = await store.getCompacts(edited.id);
  assert.deepEqual(saved, []);
  const prepared = prepareMessagesWithCompacts(currentMessages(edited), saved, policy, 0);
  assert.ok(!prepared.map(toText).join('\n').includes('old-value'));
  assert.ok(prepared.map(toText).join('\n').includes('new-value'));
});

test('an invalid compact rejected by injection also cannot block fresh planning', async () => {
  const store = createMemoryCompactStore();
  const conversation = conversationOf('invalid-source', 8, 'neutral');
  const policy = makeCompactPolicy({ keepRecentTurns: 1, precompactRatio: 0 });
  await compactConversation(conversation, policy, null, 'test', '', false, {
    store, provider: { streamText: async (_messages, onChunk) => emit(onChunk, summaryOutput()) },
  });
  const edited = editMessage(conversation, 'invalid-source-m0', [
    { type: 'text', text: 'revised', metadata: null },
  ]);
  const saved = await store.getCompacts(conversation.id);
  assert.equal(selectCompactsForInjection(saved, new Set(currentMessages(edited).map(message => message.id))).length, 0);
  const plan = planCompaction(edited.messageNodes, saved, policy, null);
  assert.equal(plan.shouldCompact, true);
  assert.equal(plan.sourceStartIndex, 0);
});

test('force preparation waits for background compaction and replans from its committed range', async () => {
  const store = createMemoryCompactStore();
  const conversation = conversationOf('force-race', 28, '你'.repeat(300));
  const extended = conversationOf(conversation.id, 34, '你'.repeat(300));
  const policy = makeCompactPolicy();
  const entered = deferred();
  const release = deferred();
  const prompts: string[] = [];
  let ids = 0;
  let background: Promise<CompactResult> | undefined;
  const provider: ChatStreamProvider = { streamText: async (messages, onChunk) => {
    prompts.push(toText(messages[0]));
    if (prompts.length === 1) {
      entered.resolve();
      await release.promise;
    }
    emit(onChunk, summaryOutput());
  } };
  const deps = { store, provider, newId: () => `force-${++ids}`,
    launchPrecompact: (run: () => Promise<CompactResult>): void => { background = run(); } };
  await prepareContext(conversation, policy, 11500, currentMessages(conversation), 0, deps);
  await entered.promise;
  // Another ordinary send stays independent of the provider's pending summary.
  const ordinary = await prepareContext(conversation, policy, 11500, currentMessages(conversation), 0, deps);
  assert.equal(ordinary.compressionApplied, false);
  const force = prepareContext(extended, policy, 11500, currentMessages(extended), 0, deps);
  let callsBeforeRelease: number;
  try {
    await new Promise<void>(done => setImmediate(done));
    callsBeforeRelease = prompts.length;
  } finally {
    release.resolve();
  }
  const prepared = await force;
  await background;
  assert.equal(callsBeforeRelease, 1, 'force must await the pending same-conversation compact');
  assert.equal(prompts.length, 2);
  const saved = await store.getCompacts(conversation.id);
  assert.equal(saved.length, 2);
  assert.deepEqual(saved[0].sourceMessageIds, currentMessages(extended).slice(0, 4).map(message => message.id));
  assert.deepEqual(saved[1].sourceMessageIds, currentMessages(extended).slice(4, 10).map(message => message.id));
  assert.ok(prompts[1].includes('"covered_compact_ids": ["force-1"]'));
  const injected = selectCompactsForInjection(saved, new Set(currentMessages(extended).map(message => message.id)));
  assert.deepEqual(injected.map(compact => compact.id), ['force-2']);
  assert.equal(prepared.messages.filter(message => toText(message).startsWith('[Conversation compact handoff:')).length, 1);
});

test('compression template substitution preserves literal placeholders in inserted conversation content', () => {
  const content = 'literal: {locale}; {target_tokens}; {additional_context}; {content}; $&';
  const prompt = buildCompressionPrompt({ basePrompt: DEFAULT_COMPRESS_PROMPT, content,
    targetTokens: 2000, additionalPrompt: '', sourceMessageIds: ['m'], previousCompacts: [],
    coveredCompactIds: [], payloadCreatedAt: 1, handoffPrompt: 'Keep {content} literally.', locale: 'Chinese' });
  assert.equal(prompt.split('<conversation>\n')[1].split('\n</conversation>')[0], content);
  assert.ok(prompt.includes('Target approximately 2000 tokens'));
  assert.ok(prompt.includes('Use Chinese language'));
  assert.ok(prompt.includes('Keep {content} literally.'));
});

test('pending compaction does not serialize other conversations or independent store instances', async () => {
  const store = createMemoryCompactStore();
  const conversation = conversationOf('pending-owner', 8, 'neutral history');
  const policy = makeCompactPolicy({ keepRecentTurns: 1 });
  const entered = deferred();
  const release = deferred();
  const first = compactConversation(conversation, policy, 128000, 'test', '', true, {
    store, provider: { streamText: async (_messages, onChunk) => {
      entered.resolve();
      await release.promise;
      emit(onChunk, summaryOutput());
    } },
  });
  await entered.promise;
  let independentProviderCalls = 0;
  const provider: ChatStreamProvider = { streamText: async (_messages, onChunk) => {
    independentProviderCalls++;
    emit(onChunk, summaryOutput());
  } };
  const otherConversation = compactConversation(conversationOf('independent', 8, 'neutral history'),
    policy, 128000, 'test', '', true, { store, provider });
  const otherStore = compactConversation(conversation, policy, 128000, 'test', '', true, {
    store: createMemoryCompactStore(), provider,
  });
  let callsBeforeRelease: number;
  try {
    await new Promise<void>(done => setImmediate(done));
    callsBeforeRelease = independentProviderCalls;
  } finally {
    release.resolve();
  }
  assert.deepEqual((await Promise.all([first, otherConversation, otherStore])).map(result => result.status),
    ['completed', 'completed', 'completed']);
  assert.equal(callsBeforeRelease, 2);
});
