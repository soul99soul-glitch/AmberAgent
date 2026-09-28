import { test } from 'node:test';
import assert from 'node:assert/strict';
import { countNativeContextFootprint, nativeTokenizerForModel } from '../main/ets/chat/native_context_tokens.ts';
import type { UIMessage, UIMessagePart } from '../main/ets/chat/message.ts';
import { makeUIMessage } from '../main/ets/chat/message.ts';
import type { ConversationCompact } from '../main/ets/chat/context_compact.ts';
import { compactInjectionText } from '../main/ets/chat/context_compact.ts';

const message = (id: string, parts: UIMessagePart[] = []): UIMessage => ({ ...makeUIMessage('user', parts), id });
const text = (value: string): UIMessagePart => ({ type: 'text', text: value, metadata: null });
const compact = (sourceIds: string[], status: string = 'completed'): ConversationCompact => ({
  id: 'c1', conversationId: 'conv', summary: 'compact summary', level: 1,
  sourceStartIndex: 0, sourceEndIndex: 0, sourceMessageIds: sourceIds,
  tokenEstimate: 10, createdAt: 1, updatedAt: 1, status,
});

test('official OpenAI o200k model families include versioned, decimal and fine-tuned names', () => {
  for (const modelId of ['gpt-5', 'gpt-5.1', 'gpt-5-mini', 'gpt-4.1', 'gpt-4.1-2025-04-14',
    'gpt-4o', 'gpt-4o-mini', 'gpt-4.5-preview', 'chatgpt-4o-latest', 'o1', 'o1-mini', 'o3',
    'o3-pro', 'o4-mini', 'o4-mini-2025-04-16', 'ft:gpt-4o-mini:team:custom:abc']) {
    assert.equal(nativeTokenizerForModel(modelId), 'o200k_base', modelId);
  }
});

test('official cl100k model families remain separate from 4o/4.1', () => {
  for (const modelId of ['gpt-4', 'gpt-4-32k', 'gpt-3.5', 'gpt-3.5-turbo', 'gpt-3.5-turbo-0125',
    'gpt-35-turbo', 'gpt-35-turbo-0613', 'ft:gpt-4:team:custom:abc', 'ft:gpt-3.5-turbo:team:abc']) {
    assert.equal(nativeTokenizerForModel(modelId), 'cl100k_base', modelId);
  }
});

test('unknown deployment aliases, other providers and gpt-oss retain the existing estimate', () => {
  for (const modelId of ['', 'default', 'my-gpt-4o-deployment', 'claude-sonnet-4', 'gemini-2.5-pro',
    'gpt-oss-20b', 'gpt-oss-120b', 'gpt-4.2', 'o2', 'gpt-image-2', 'GPT-4o']) {
    assert.equal(nativeTokenizerForModel(modelId), null, modelId);
  }
});

test('empty conversation sends one empty native batch and displays zero', async () => {
  let calls = 0;
  assert.equal(await countNativeContextFootprint([], [], 'o200k_base', async (ids, texts) => {
    calls++;
    assert.deepEqual(ids, []);
    assert.deepEqual(texts, []);
    return [];
  }), 0);
  assert.equal(calls, 1);
});

test('BPE collects reasoning, tool input/output, filenames and mini-app text while retaining media/role costs', async () => {
  const parts: UIMessagePart[] = [text('reply'), { type: 'reasoning', reasoning: 'thought',
    createdAt: '2026-09-28T00:00:00Z', finishedAt: null, metadata: null },
    { type: 'tool', toolCallId: 'tool1', toolName: 'search', input: 'args',
      output: [text('tool result'), { type: 'image', url: 'tool-image', metadata: null }],
      approvalState: { type: 'auto' }, metadata: null },
    { type: 'image', url: 'image', metadata: null },
    { type: 'video', url: 'video', mime: 'video/mp4', metadata: null },
    { type: 'audio', url: 'audio', fileName: 'audio.mp3', mime: 'audio/mp3', metadata: null },
    { type: 'document', url: 'doc', fileName: '报告.pdf', mime: 'application/pdf', metadata: null },
    { type: 'mini_app', appId: 'app1', title: 'app', description: 'desc', iconEmoji: null,
      category: null, permissions: [], htmlHash: null, version: 1, metadata: null }];
  const msg = makeUIMessage('assistant', parts);
  const result = await countNativeContextFootprint([msg], [], 'o200k_base', async (ids, texts) => {
    assert.deepEqual(texts, ['reply', 'thought', 'args', 'tool result', '报告.pdf', 'app', 'desc']);
    assert.deepEqual(ids, texts.map(() => 'o200k_base'));
    return [3, 7, 2, 9, 4, 5, 6];
  });
  assert.equal(result, 36 + Math.floor((4 * 4500 + 80 + 120 + 'assistant'.length) / 4));
});

test('unexecuted tool contributes only its input and each message keeps a four-token floor', async () => {
  const tool: UIMessagePart = { type: 'tool', toolCallId: 't', toolName: 'search', input: 'input',
    output: [], approvalState: { type: 'pending' }, metadata: null };
  assert.equal(await countNativeContextFootprint([message('a', [tool]), message('b')], [], 'cl100k_base',
    async (_ids, texts) => { assert.deepEqual(texts, ['input']); return [1]; }), 8);
});

test('completed compact replaces covered source text and summary/recent retain independent floors', async () => {
  const c = compact(['old']);
  const result = await countNativeContextFootprint([
    message('old', [text('covered source')]), message('recent', [text('remaining text')]),
  ], [c], 'o200k_base', async (_ids, texts) => {
    assert.deepEqual(texts, [compactInjectionText(c), 'remaining text']);
    return [0, 100];
  });
  assert.equal(result, 4 + 101);
});

test('incomplete and branch-invalid compacts cannot hide original messages', async () => {
  const messages = [message('current', [text('current branch')])];
  for (const c of [compact(['current'], 'running'), compact(['other-branch'])]) {
    assert.equal(await countNativeContextFootprint(messages, [c], 'o200k_base', async (_ids, texts) => {
      assert.deepEqual(texts, ['current branch']);
      return [20];
    }), 21);
  }
});

test('batch input and fixed costs are captured before the async native result', async () => {
  const parts = [text('original')];
  const messages = [message('a', parts)];
  let complete: ((counts: number[]) => void) | null = null;
  const pending = countNativeContextFootprint(messages, [], 'o200k_base', async (_ids, texts) => {
    assert.deepEqual(texts, ['original']);
    return new Promise<number[]>((resolve) => { complete = resolve; });
  });
  messages.push(message('later'));
  parts.push({ type: 'image', url: 'later-image', metadata: null });
  (complete as unknown as (counts: number[]) => void)([20]);
  assert.equal(await pending, 21);
});

test('native failure propagates so the page keeps its existing estimate', async () => {
  const failure = new Error('native fixture failed');
  await assert.rejects(countNativeContextFootprint([message('a', [text('hello')])], [], 'o200k_base',
    async () => { throw failure; }), failure);
});
