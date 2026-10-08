import assert from 'node:assert/strict';
import { test } from 'node:test';
import { generateAssistantOnce, createMemoryConversationStore } from '../main/ets/chat/chat_turn.ts';
import { runRegenerateAt } from '../main/ets/chat/regenerate.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import { makeGenerationRetrySetting } from '../main/ets/chat/generation_retry.ts';
import { makeConversation, toMessageNode, currentMessages } from '../main/ets/chat/conversation.ts';
import { makeUserMessage, makeAssistantMessage, toText } from '../main/ets/chat/message.ts';
import type { UIMessage, MessageChunk } from '../main/ets/chat/message.ts';

const chunkOf = (text: string): MessageChunk => ({
  id: 'c', model: 'm', usage: null,
  choices: [{ index: 0, finishReason: 'unknown', message: null,
    delta: { ...makeAssistantMessage(text), finishedAt: null } }],
});
const tail = (messages: UIMessage[]) => toText(messages.at(-1)!);

for (const route of ['generate', 'regenerate'] as const) {
  test(`${route}: 48ms publishes the latest tail without waiting for another provider chunk`, async (t) => {
    t.mock.timers.enable({ apis: ['setTimeout', 'Date'], now: 0 });
    let send: (chunk: MessageChunk) => void = () => {};
    let finish: () => void = () => {};
    let ready: () => void = () => {};
    const started = new Promise<void>((resolve) => { ready = resolve; });
    const visible: UIMessage[][] = [];
    const raw: UIMessage[][] = [];
    const store = createMemoryConversationStore();
    const deps = {
      assistant: makeAssistant({}), inputTransformers: [], outputTransformers: [], store,
      onUpdate: (messages: UIMessage[]) => { visible.push(messages); },
      onRawFlushSnapshot: (messages: UIMessage[]) => { raw.push(messages); },
      provider: { streamText: (_messages: UIMessage[], onChunk: (chunk: MessageChunk) => void) => {
        send = onChunk;
        ready();
        return new Promise<void>((resolve) => { finish = resolve; });
      } },
    };
    const user = makeUserMessage('q');
    const conv = makeConversation('stream', [toMessageNode(user), toMessageNode(makeAssistantMessage('old'))]);
    const running = route === 'generate'
      ? generateAssistantOnce([user], [user], deps)
      : runRegenerateAt(conv, conv.messageNodes[1].id, deps).then(currentMessages);
    await started;
    send(chunkOf('a'));
    assert.deepEqual(visible.map(tail), ['a'], 'first chunk is immediate even when clock starts at zero');
    t.mock.timers.tick(8);
    send(chunkOf('b'));
    t.mock.timers.tick(8);
    send(chunkOf('c'));
    assert.deepEqual(visible.map(tail), ['a'], 'window coalesces provider chunks');
    t.mock.timers.tick(31);
    assert.deepEqual(visible.map(tail), ['a']);
    t.mock.timers.tick(1);
    assert.deepEqual(visible.map(tail), ['a', 'abc'], 'timer publishes the latest accumulator at 48ms');
    assert.deepEqual(raw.map(tail), ['a', 'abc']);
    send(chunkOf('d'));
    finish();
    const messages = await running;
    assert.equal(tail(messages), 'abcd', 'authoritative final messages keep every chunk');
    assert.equal(tail(visible.at(-1)!), 'abcd', 'completion flushes a pending UI tail');
    assert.equal(tail(raw.at(-1)!), 'abcd', 'completion raw checkpoint keeps every chunk');
    const publications = visible.length;
    t.mock.timers.tick(1000);
    assert.equal(visible.length, publications, 'cancelled timer cannot overwrite the final snapshot');
  });

  test(`${route}: a failed attempt timer cannot cover the retry's new stream`, async (t) => {
    t.mock.timers.enable({ apis: ['setTimeout', 'Date'], now: 100 });
    let attempt = 0;
    let finish: () => void = () => {};
    let ready: () => void = () => {};
    const secondStarted = new Promise<void>((resolve) => { ready = resolve; });
    const visible: UIMessage[][] = [];
    const deps = {
      assistant: makeAssistant({}), inputTransformers: [], outputTransformers: [],
      store: createMemoryConversationStore(), sleep: async () => {},
      nowMs: () => Date.now(),
      retrySetting: makeGenerationRetrySetting({ maxRetries: 1 }),
      onUpdate: (messages: UIMessage[]) => { visible.push(messages); },
      provider: { streamText: async (_messages: UIMessage[], send: (chunk: MessageChunk) => void) => {
        attempt++;
        if (attempt === 1) {
          send(chunkOf('old'));
          t.mock.timers.tick(8);
          send(chunkOf('-tail'));
          throw new Error('network connection interrupted');
        }
        send(chunkOf('new'));
        ready();
        await new Promise<void>((resolve) => { finish = resolve; });
      } },
    };
    const user = makeUserMessage('q');
    const conv = makeConversation('retry', [toMessageNode(user), toMessageNode(makeAssistantMessage('original'))]);
    const running = route === 'generate'
      ? generateAssistantOnce([user], [user], deps)
      : runRegenerateAt(conv, conv.messageNodes[1].id, deps).then(currentMessages);
    await secondStarted;
    assert.equal(tail(visible.at(-1)!), 'new');
    const publications = visible.length;
    t.mock.timers.tick(1000);
    assert.equal(visible.length, publications, 'old pending timer was cancelled before resetting to the seed');
    finish();
    assert.equal(tail(await running), 'new', 'failed attempt content is discarded');
  });

  for (const terminal of ['abort', 'failure'] as const) {
    test(`${route}: ${terminal} flushes the pending tail and cancels its timer`, async (t) => {
      t.mock.timers.enable({ apis: ['setTimeout', 'Date'], now: 100 });
      const visible: UIMessage[][] = [];
      const raw: UIMessage[][] = [];
      const signal = { aborted: false };
      const deps = {
        assistant: makeAssistant({}), inputTransformers: [], outputTransformers: [],
        store: createMemoryConversationStore(), abortSignal: signal,
        retrySetting: makeGenerationRetrySetting({ enabled: false }),
        onUpdate: (messages: UIMessage[]) => { visible.push(messages); },
        onRawFlushSnapshot: (messages: UIMessage[]) => { raw.push(messages); },
        provider: { streamText: async (_messages: UIMessage[], send: (chunk: MessageChunk) => void) => {
          send(chunkOf('a'));
          t.mock.timers.tick(8);
          send(chunkOf('b'));
          if (terminal === 'abort') signal.aborted = true;
          throw new Error('failed');
        } },
      };
      const user = makeUserMessage('q');
      const conv = makeConversation('stream', [toMessageNode(user), toMessageNode(makeAssistantMessage('old'))]);
      const run = () => route === 'generate'
        ? generateAssistantOnce([user], [user], deps)
        : runRegenerateAt(conv, conv.messageNodes[1].id, deps).then(currentMessages);
      if (terminal === 'failure') await assert.rejects(run, /failed/);
      else assert.equal(tail(await run()), 'ab');
      assert.deepEqual(visible.map(tail), ['a', 'ab']);
      assert.equal(tail(raw.at(-1)!), 'ab');
      t.mock.timers.tick(1000);
      assert.deepEqual(visible.map(tail), ['a', 'ab']);
    });
  }
}
