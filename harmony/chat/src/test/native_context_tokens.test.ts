import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  countNativeContextFootprint, nativeTokenizerForModel, estimateImageTokens,
} from '../main/ets/chat/native_context_tokens.ts';
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

test('image token estimate reads PNG/JPEG dimensions instead of a fixed per-image cost', () => {
  const png = (width: number, height: number): string => {
    const bytes = Buffer.alloc(24);
    bytes[0] = 0x89; bytes[1] = 0x50; bytes[2] = 0x4e; bytes[3] = 0x47;
    bytes.writeUInt32BE(width, 16); bytes.writeUInt32BE(height, 20);
    return `data:image/png;base64,${bytes.toString('base64')}`;
  };
  assert.equal(estimateImageTokens(png(1, 1)), 86);
  assert.equal(estimateImageTokens(png(100, 100)), 99);
  assert.equal(estimateImageTokens(png(1320, 2856)), Math.ceil(1320 * 2856 / 750) + 85);
  const jpeg = Buffer.alloc(20);
  jpeg[0] = 0xff; jpeg[1] = 0xd8; jpeg[2] = 0xff; jpeg[3] = 0xc0;
  jpeg[4] = 0; jpeg[5] = 11; jpeg[6] = 8; jpeg[7] = 0x02; jpeg[8] = 0x58;
  jpeg[9] = 0x05; jpeg[10] = 0x28;
  assert.equal(estimateImageTokens(`data:image/jpeg;base64,${jpeg.toString('base64')}`),
    Math.ceil(1320 * 600 / 750) + 85);
  assert.equal(estimateImageTokens('https://example.com/a.png'), 1600);
  assert.equal(estimateImageTokens('blob://missing'), 0);
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
  // Two remote images contribute 1600 tokens each; other fixed costs retain
  // their character estimate and the BPE batch above contributes 36 tokens.
  assert.equal(result, 5538);
});

test('a 1024px image contributes its token estimate without another character conversion', async () => {
  const png = Buffer.alloc(24);
  png[0] = 0x89; png[1] = 0x50; png[2] = 0x4e; png[3] = 0x47;
  png.writeUInt32BE(1024, 16); png.writeUInt32BE(1024, 20);
  const image: UIMessagePart = { type: 'image', url: `data:image/png;base64,${png.toString('base64')}`, metadata: null };
  assert.equal(await countNativeContextFootprint([message('image', [image])], [], 'o200k_base',
    async (_ids, texts) => { assert.deepEqual(texts, []); return []; }), 1485);
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
