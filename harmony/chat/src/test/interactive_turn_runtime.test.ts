import { test } from 'node:test';
import assert from 'node:assert/strict';
import { runInteractiveTurn } from '../main/ets/chat/interactive_turn_runtime.ts';
import type { InteractiveTurnSnapshot } from '../main/ets/chat/interactive_turn_runtime.ts';
import { createMemoryConversationStore } from '../main/ets/chat/chat_turn.ts';
import type { ChatTurnDeps } from '../main/ets/chat/chat_turn.ts';
import type { StreamOpts } from '../main/ets/chat/chat_turn.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import { makeConversation, toMessageNode } from '../main/ets/chat/conversation.ts';
import type { Conversation } from '../main/ets/chat/conversation.ts';
import { makeAssistantMessage } from '../main/ets/chat/message.ts';
import { makeUserMessage } from '../main/ets/chat/message.ts';
import type { MessageChunk, UIMessage } from '../main/ets/chat/message.ts';
import { runRegenerateAtWithTools } from '../main/ets/chat/tool_loop.ts';
import type { ChatToolDefinition } from '../main/ets/chat/provider_model.ts';

const baseDeps = (streamOutput: boolean, nowMs: () => number): ChatTurnDeps => ({
  assistant: makeAssistant({ streamOutput }),
  inputTransformers: [],
  outputTransformers: [],
  provider: {
    streamText: (
      _messages: UIMessage[], _onChunk: (chunk: MessageChunk) => void, opts?: StreamOpts,
    ): Promise<void> => {
      opts?.onDataEnd?.();
      return Promise.resolve();
    },
  },
  store: createMemoryConversationStore(),
  nowMs,
});

const withAssistant = (base: Conversation, text: string): Conversation => ({
  ...base,
  messageNodes: [...base.messageNodes, toMessageNode(makeAssistantMessage(text))],
});

const textChunk = (text: string): MessageChunk => ({
  id: 'chunk', model: 'model', usage: null,
  choices: [{
    index: 0,
    delta: makeAssistantMessage(text),
    message: null,
    finishReason: 'unknown',
  }],
});

test('interactive runtime: request 完成前可见增量判定 live，并保留宿主 onUpdate', async () => {
  let clock: number = 10;
  const initial: Conversation = makeConversation('interactive-live');
  const frames: InteractiveTurnSnapshot[] = [];
  const hostUpdates: UIMessage[][] = [];
  const deps: ChatTurnDeps = {
    ...baseDeps(true, (): number => clock),
    onUpdate: (messages: UIMessage[]): void => { hostUpdates.push(messages); },
  };
  const result = await runInteractiveTurn(initial, deps, async (runtimeDeps): Promise<Conversation> => {
    let endStream: () => void = (): void => {};
    const source = {
      streamText: (_messages: UIMessage[], _onChunk: (chunk: MessageChunk) => void,
        opts?: StreamOpts): Promise<void> => new Promise((resolve): void => {
        endStream = (): void => {
          opts?.onDataEnd?.();
          resolve();
        };
      }),
    };
    const observed = runtimeDeps.wrapStreamProvider?.(source) ?? source;
    const stream = observed.streamText([], (): void => {});
    const first: Conversation = withAssistant(initial, '增');
    runtimeDeps.onUpdate?.(first.messageNodes.map(node => node.messages[node.selectIndex]));
    clock = 15;
    const out: Conversation = withAssistant(initial, '增量');
    runtimeDeps.onUpdate?.(out.messageNodes.map(node => node.messages[node.selectIndex]));
    clock = 20;
    endStream();
    await stream;
    return out;
  }, { onSnapshot: (frame): void => { frames.push(frame); } });

  assert.equal(result.transport, 'live');
  assert.equal(hostUpdates.length, 2);
  assert.equal(frames[0].generationActive, true);
  assert.equal(frames[0].textDeltasLive, false, '首帧尚不能证明传输实时');
  assert.equal(frames.some(frame => frame.textDeltasLive), true);
  assert.equal(frames.at(-1)?.generationActive, false);
  assert.equal(frames.at(-1)?.transport, 'live');
});

test('interactive runtime: 同一收流边界才出现首帧判定 buffered', async () => {
  const initial: Conversation = makeConversation('interactive-buffered');
  const frames: InteractiveTurnSnapshot[] = [];
  const result = await runInteractiveTurn(
    initial, baseDeps(true, (): number => 30),
    async (runtimeDeps): Promise<Conversation> => {
      const source = {
        streamText: (_messages: UIMessage[], _onChunk: (chunk: MessageChunk) => void,
          opts?: StreamOpts): Promise<void> => {
          opts?.onDataEnd?.();
          return Promise.resolve();
        },
      };
      const observed = runtimeDeps.wrapStreamProvider?.(source) ?? source;
      await observed.streamText([], (): void => {});
      const out: Conversation = withAssistant(initial, '整包');
      runtimeDeps.onUpdate?.(out.messageNodes.map(node => node.messages[node.selectIndex]));
      return out;
    },
    { onSnapshot: (frame): void => { frames.push(frame); } },
  );
  assert.equal(result.transport, 'buffered');
  assert.equal(result.firstParsedDeltaAt, result.dataEndAt);
  assert.equal(frames.some(frame => frame.generationActive && frame.transport === 'buffered'), true);
  assert.equal(frames.some(frame => frame.textDeltasLive), false);
});

test('interactive runtime: 非流式 completion 不冒充 live', async () => {
  let clock: number = 40;
  const initial: Conversation = makeConversation('interactive-nonstream');
  const frames: InteractiveTurnSnapshot[] = [];
  const result = await runInteractiveTurn(
    initial, baseDeps(false, (): number => clock),
    async (runtimeDeps): Promise<Conversation> => {
      const out: Conversation = withAssistant(initial, '一次性结果');
      runtimeDeps.onUpdate?.(out.messageNodes.map(node => node.messages[node.selectIndex]));
      clock = 50;
      return out;
    },
    { onSnapshot: (frame): void => { frames.push(frame); } },
  );
  assert.equal(result.transport, 'unavailable');
  assert.equal(frames.some(frame => frame.textDeltasLive), false);
});

test('interactive runtime: regenerate 用截断 seed 作基线，新答案短于旧答案仍判 live', async () => {
  let clock: number = 10;
  const initial: Conversation = makeConversation('interactive-regenerate', [
    toMessageNode(makeUserMessage('问题')),
    toMessageNode(makeAssistantMessage('旧'.repeat(100))),
  ]);
  const targetNodeId: string = initial.messageNodes[1].id;
  const provider = {
    streamText: (
      _messages: UIMessage[], onChunk: (chunk: MessageChunk) => void, opts?: StreamOpts,
    ): Promise<void> => {
      onChunk(textChunk('新'.repeat(40)));
      clock = 15;
      onChunk(textChunk('文'.repeat(40)));
      clock = 20;
      opts?.onDataEnd?.();
      return Promise.resolve();
    },
  };
  const result = await runInteractiveTurn(
    initial,
    { ...baseDeps(true, (): number => clock), provider, flushIntervalMs: 0 },
    (runtimeDeps): Promise<Conversation> => runRegenerateAtWithTools(
      initial,
      targetNodeId,
      runtimeDeps,
      {
        tools: [],
        makeProviderForStep: (_definitions: ChatToolDefinition[]) => provider,
      },
    ),
  );
  assert.equal(result.transport, 'live');
  assert.equal(result.firstParsedDeltaAt, 10);
  assert.equal(result.dataEndAt, 20);
});

test('interactive runtime: unchanged historical message parts are measured once, replacements still update transport', async () => {
  let historyReads = 0;
  const history = makeAssistantMessage('历史');
  const parts = history.parts;
  Object.defineProperty(history, 'parts', { get: () => { historyReads++; return parts; } });
  const initial = makeConversation('cached-runtime', [toMessageNode(history)]);
  let clock = 100;
  const frames: InteractiveTurnSnapshot[] = [];
  const result = await runInteractiveTurn(initial, baseDeps(true, () => clock), async (runtime) => {
    let finish = (): void => {};
    const source = { streamText: (_messages: UIMessage[], _chunk: (chunk: MessageChunk) => void, opts?: StreamOpts) =>
      new Promise<void>((resolve) => { finish = () => { opts?.onDataEnd?.(); resolve(); }; }) };
    const provider = runtime.wrapStreamProvider!(source);
    const pending = provider.streamText([history], () => {});
    const tail = makeAssistantMessage('增');
    runtime.onUpdate!([history, tail]);
    clock = 150;
    const grown = { ...tail, parts: makeAssistantMessage('增长').parts };
    runtime.onUpdate!([history, grown]);
    clock = 160;
    finish(); await pending;
    return makeConversation(initial.id, [toMessageNode(history), toMessageNode(grown)]);
  }, { onSnapshot: (snapshot) => { frames.push(snapshot); } });
  assert.equal(historyReads, 1, 'unchanged history must not rescan parts at baseline and every flush');
  assert.equal(result.transport, 'live');
  assert.equal(result.firstParsedDeltaAt, 100);
  assert.equal(result.dataEndAt, 160);
  assert.equal(frames.at(-1)?.messages[1].parts[0].type === 'text'
    ? (frames.at(-1)!.messages[1].parts[0] as { text: string }).text : '', '增长');
});
