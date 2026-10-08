import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createOpenAIChatApi } from '../main/ets/chat/openai_chat_api.ts';
import { createOpenAIResponsesApi } from '../main/ets/chat/openai_responses_api.ts';
import { classifyGenerationFailure, makeGenerationRetrySetting } from '../main/ets/chat/generation_retry.ts';
import { runChatTurn, createMemoryConversationStore } from '../main/ets/chat/chat_turn.ts';
import type { ChatTurnDeps, ChatStreamProvider } from '../main/ets/chat/chat_turn.ts';
import { runRegenerateAt } from '../main/ets/chat/regenerate.ts';
import { runChatTurnWithTools } from '../main/ets/chat/tool_loop.ts';
import { makeAgentTool } from '../main/ets/chat/tool.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import { makeConversation, toMessageNode, currentMessages } from '../main/ets/chat/conversation.ts';
import type { Conversation } from '../main/ets/chat/conversation.ts';
import { makeUIMessage, makeUserMessage, makeAssistantMessage, toText } from '../main/ets/chat/message.ts';
import type { UIMessage, UIMessagePart, MessageChunk } from '../main/ets/chat/message.ts';
import { makeTextGenerationParams, makeProviderSettingOpenAI } from '../main/ets/chat/provider_model.ts';
import { createDocumentTransformer } from '../main/ets/chat/document_transformer.ts';
import type { MessageTransformer } from '../main/ets/chat/transformer_pipeline.ts';
import { estimateTokens, makeCompactPolicy } from '../main/ets/chat/context_compact.ts';
import { prepareContext, createMemoryCompactStore } from '../main/ets/chat/context_engine.ts';
import type { PrepareContextDeps } from '../main/ets/chat/context_engine.ts';

const textChunk = (text: string): MessageChunk => ({
  id: 'fixture', model: 'fixture', usage: null,
  choices: [{ index: 0, delta: makeAssistantMessage(text), message: null, finishReason: null }],
});

for (const protocol of ['chat_stream', 'responses_stream', 'responses_generate'] as const) {
  for (const [status, category, retryable] of [
    [503, 'SERVER', true], [429, 'RATE_LIMIT', true], [408, 'TIMEOUT', true],
    [401, 'AUTH', false], [400, 'BAD_REQUEST', false],
  ] as const) {
    test(`${protocol}: JSON detail preserves HTTP ${status} for the existing retry classifier`, async () => {
      const response = { status, headers: {}, body: '{"error":{"message":"Please try again later"}}' };
      const http = { fetch: async () => response, fetchStream: async () => response };
      const setting = makeProviderSettingOpenAI({});
      const params = makeTextGenerationParams({});
      const messages = [makeUserMessage('hi')];
      const api = protocol === 'chat_stream' ? createOpenAIChatApi({ http, setting })
        : createOpenAIResponsesApi({ http, setting });
      const call = protocol === 'responses_generate' ? api.generateText(messages, params)
        : api.streamText(messages, params, () => {});
      await assert.rejects(call, (error: Error): boolean => {
        assert.match(error.message, /Please try again later/);
        assert.match(error.message, new RegExp(String(status)));
        const failure = classifyGenerationFailure(error);
        assert.equal(failure.category, category);
        assert.equal(failure.retryable, retryable);
        return true;
      });
    });
  }
}

test('regenerate: abort releases a retry wait without another provider call or failed output', async () => {
  const conversation = {
    ...makeConversation('retry-release', []),
    messageNodes: [toMessageNode(makeUserMessage('question')), toMessageNode(makeAssistantMessage('old answer'))],
  };
  const controller = new AbortController();
  let releaseSleep: () => void = () => {};
  let startedSleep: () => void = () => {};
  const sleeping = new Promise<void>((resolve) => { startedSleep = resolve; });
  const store = createMemoryConversationStore();
  let calls = 0;
  let settled = false;
  const run = runRegenerateAt(conversation, conversation.messageNodes[1].id, {
    assistant: makeAssistant(), inputTransformers: [], outputTransformers: [], store,
    provider: { streamText: async (_messages, onChunk) => {
      calls++; onChunk(textChunk('failed attempt')); throw new Error('network interrupted');
    } },
    abortSignal: controller.signal,
    retrySetting: makeGenerationRetrySetting({ jitterRatio: 0 }),
    sleep: async () => { startedSleep(); await new Promise<void>((resolve) => { releaseSleep = resolve; }); },
  }).then((value) => { settled = true; return value; });
  await sleeping;
  controller.abort();
  try {
    await new Promise<void>((resolve) => { setTimeout(resolve, 30); });
    assert.equal(settled, true, 'A stopped retry must not retain the conversation until its delay expires');
    assert.equal(await run, conversation);
    assert.equal(calls, 1);
    assert.equal(store.saved.length, 0);
  } finally { releaseSleep(); await run; }
});

const document: UIMessagePart = {
  type: 'document', url: 'file://history.txt', fileName: 'history.txt', mime: 'text/plain', metadata: null,
};
const documentTransformer = (): MessageTransformer => createDocumentTransformer({
  statFile: async () => ({ exists: true, isFile: true, sizeBytes: 200000, absolutePath: '/history.txt' }),
  readTextFile: async () => 'x'.repeat(200000),
});
const history = (): Conversation => ({
  ...makeConversation('materialized-budget', []),
  messageNodes: [toMessageNode(makeUIMessage('user', [document])), toMessageNode(makeAssistantMessage('file received'))],
});
const turnDeps = (provider: ChatStreamProvider): ChatTurnDeps => ({
  assistant: makeAssistant(), inputTransformers: [documentTransformer()], outputTransformers: [], provider,
  store: createMemoryConversationStore(), finalTokenBudget: 4000,
});

for (const mode of ['normal', 'regenerate', 'tool_loop'] as const) {
  test(`${mode}: fit uses expanded historical document and leaves stored source intact`, async () => {
    const initial = history();
    const captures: UIMessage[][] = [];
    const provider: ChatStreamProvider = { streamText: async (messages, onChunk) => {
      captures.push(messages); onChunk(textChunk('answer'));
    } };
    const deps = turnDeps(provider);
    const followup = makeUserMessage('continue with this latest instruction');
    let result: Conversation;
    if (mode === 'regenerate') {
      const target = toMessageNode(makeAssistantMessage('old final answer'));
      initial.messageNodes.push(toMessageNode(followup), target);
      result = await runRegenerateAt(initial, target.id, deps);
    } else if (mode === 'tool_loop') {
      const tool = makeAgentTool({ name: 'fixture', description: 'fixture', execute: async () => [] });
      result = await runChatTurnWithTools(initial, toText(followup), deps, {
        tools: [tool], makeProviderForStep: () => provider,
      });
    } else result = await runChatTurn(initial, toText(followup), deps);
    assert.equal(captures.length, 1);
    assert.ok(estimateTokens(captures[0]) <= 4000, 'Provider receives the fitted materialized input');
    assert.equal(toText(captures[0].at(-1)!), toText(followup));
    const persisted = currentMessages(result)[0].parts;
    assert.deepEqual(persisted, [document], 'History fitting must not replace the stored attachment');
    assert.deepEqual(currentMessages(initial)[0].parts, [document]);
  });
}

for (const mode of ['normal', 'regenerate', 'tool_loop'] as const) {
  test(`${mode}: vision fallback also fits expanded historical OCR`, async () => {
    const image: UIMessagePart = { type: 'image', url: 'file://history.png', metadata: null };
    const initial = { ...history(), messageNodes: [
      toMessageNode(makeUIMessage('user', [image])), toMessageNode(makeAssistantMessage('image received')),
    ] };
    const captures: UIMessage[][] = [];
    const provider: ChatStreamProvider = { streamText: async (messages, onChunk) => {
      captures.push(messages);
      if (captures.length === 1) throw new Error('image unsupported');
      onChunk(textChunk('answer'));
    } };
    const deps: ChatTurnDeps = {
      ...turnDeps(provider), modelSupportsImageInput: true,
      inputTransformers: [{ transform: (ctx, messages) => ctx.forceImageToText !== true ? messages
        : messages.map((message) => ({ ...message, parts: message.parts.map((part) =>
          part.type === 'image' ? { type: 'text', text: 'ocr'.repeat(60000), metadata: null } : part) })) }],
    };
    const followup = makeUserMessage('latest instruction');
    if (mode === 'regenerate') {
      const target = toMessageNode(makeAssistantMessage('old final'));
      initial.messageNodes.push(toMessageNode(followup), target);
      await runRegenerateAt(initial, target.id, deps);
    } else if (mode === 'tool_loop') await runChatTurnWithTools(initial, toText(followup), deps, {
      tools: [makeAgentTool({ name: 'fixture', description: 'fixture', execute: async () => [] })],
      makeProviderForStep: () => provider,
    });
    else await runChatTurn(initial, toText(followup), deps);
    assert.equal(captures.length, 2, 'The first real image request falls back once');
    assert.ok(estimateTokens(captures[1]) <= 4000);
    assert.equal(toText(captures[1].at(-1)!), toText(followup));
    assert.deepEqual(currentMessages(initial)[0].parts, [image]);
  });
}

const compactOutput = JSON.stringify({ schema_version: 2,
  timeline_summary: 'First sentence. Second sentence. Third sentence. Fourth sentence.',
  handoff_markdown: '## Goal\n' + 'important history '.repeat(20),
});
const compactHistory = (id: string, characters: number): Conversation => ({
  ...makeConversation(id, []), messageNodes: Array.from({ length: 28 }, (_, i) =>
    toMessageNode(i % 2 === 0 ? makeUserMessage('你'.repeat(characters))
      : makeAssistantMessage('你'.repeat(characters)))),
});

test('foreground force compaction receives run cancellation and does not retry or write', async () => {
  const conversation = compactHistory('foreground-signal', 3000);
  const controller = new AbortController();
  const store = createMemoryCompactStore();
  let calls = 0;
  const deps: PrepareContextDeps & { abortSignal: AbortSignal } = {
    store, abortSignal: controller.signal,
    provider: { streamText: async (_messages, _onChunk, opts) => {
      calls++;
      assert.equal(opts?.signal, controller.signal, 'The foreground request shares its run signal');
      controller.abort();
      const error = new Error('aborted'); error.name = 'AbortError'; throw error;
    } },
  };
  await assert.rejects(prepareContext(conversation, makeCompactPolicy(), 15000,
    currentMessages(conversation), 0, deps), (error: Error) => error.name === 'AbortError');
  assert.equal(calls, 1);
  assert.equal((await store.getCompacts(conversation.id)).length, 0);
});

test('detached precompaction does not inherit an ended foreground run signal', async () => {
  const conversation = compactHistory('background-signal', 300);
  const controller = new AbortController();
  const store = createMemoryCompactStore();
  let background: (() => Promise<unknown>) | undefined;
  const deps: PrepareContextDeps & { abortSignal: AbortSignal } = {
    store, abortSignal: controller.signal,
    launchPrecompact: (run) => { background = run; },
    provider: { streamText: async (_messages, onChunk, opts) => {
      assert.equal(opts?.signal, undefined); onChunk(textChunk(compactOutput));
    } },
  };
  await prepareContext(conversation, makeCompactPolicy(), 11500, currentMessages(conversation), 0, deps);
  assert.ok(background !== undefined);
  controller.abort();
  await background();
  assert.equal((await store.getCompacts(conversation.id)).length, 1);
});

test('foreground model-window fit keeps cancellation after the initial forced summary', async () => {
  const conversation = compactHistory('foreground-fit-signal', 3000);
  const controller = new AbortController();
  const store = createMemoryCompactStore();
  let calls = 0;
  const deps: PrepareContextDeps = {
    store, abortSignal: controller.signal,
    provider: { streamText: async (_messages, onChunk, opts) => {
      calls++;
      assert.equal(opts?.signal, controller.signal);
      if (calls === 1) { onChunk(textChunk(compactOutput)); return; }
      controller.abort();
      // A provider may resolve with its last buffered chunk concurrently with stop.
      onChunk(textChunk('incomplete summary'));
    } },
  };
  await assert.rejects(prepareContext(conversation, makeCompactPolicy(), 15000,
    currentMessages(conversation), 0, deps), (error: Error) => error.name === 'AbortError');
  assert.equal(calls, 2, 'Cancellation must prevent the quality retry for the fit summary');
  assert.equal((await store.getCompacts(conversation.id)).length, 1,
    'The completed first summary stays intact; the cancelled fit summary is not written');
});
