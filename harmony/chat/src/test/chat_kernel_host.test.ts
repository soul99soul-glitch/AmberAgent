// Kernel host phase sequence tests

import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  createChatKernelHost,
  isKernelTerminalPhase,
  type KernelPhase,
} from '../main/ets/chat/chat_kernel_host.ts';
import { makeConversation } from '../main/ets/chat/conversation.ts';
import type { Conversation } from '../main/ets/chat/conversation.ts';
import { makeAssistantMessage } from '../main/ets/chat/message.ts';
import { createMemoryConversationStore } from '../main/ets/chat/chat_turn.ts';
import type { ChatTurnDeps } from '../main/ets/chat/chat_turn.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';

const baseDeps = (): ChatTurnDeps => ({
  assistant: makeAssistant({ name: 'a' }),
  inputTransformers: [],
  outputTransformers: [],
  provider: {
    streamText: async (_messages, onChunk): Promise<void> => {
      onChunk({
        id: 'c1', model: 'm', usage: null,
        choices: [{
          index: 0,
          delta: makeAssistantMessage('hi'),
          message: null,
          finishReason: null,
        }],
      });
    },
  },
  store: createMemoryConversationStore(),
});

test('kernel: preparing → streaming → completed', async () => {
  const phases: KernelPhase[] = [];
  const host = createChatKernelHost({
    nowMs: (() => { let t = 0; return (): number => { t += 1; return t; }; })(),
    onPhase: (phase): void => { phases.push(phase); },
  });
  const conv = makeConversation('kernel');
  const op = async (deps: ChatTurnDeps): Promise<Conversation> => {
    await deps.store.save(conv);
    return conv;
  };
  await host.run(conv, baseDeps(), op);
  assert.deepEqual(phases, ['preparing', 'streaming', 'completed']);
  assert.equal(host.phase(), 'completed');
  assert.equal(host.context().finishedAtMs !== null, true);
  assert.ok(isKernelTerminalPhase(host.phase()));
});

test('kernel: cancel during run → cancelled(user)', async () => {
  const phases: KernelPhase[] = [];
  const host = createChatKernelHost({
    onPhase: (phase): void => { phases.push(phase); },
  });
  const conv = makeConversation('kernel');
  const op = async (): Promise<Conversation> => {
    host.cancel('user');
    return conv;
  };
  await host.run(conv, baseDeps(), op);
  assert.ok(phases.indexOf('cancelled') >= 0);
  assert.equal(host.phase(), 'cancelled');
  assert.equal(host.context().cause, 'user');
});

test('kernel: operation throws → failed(error)', async () => {
  const phases: KernelPhase[] = [];
  const host = createChatKernelHost({
    onPhase: (phase): void => { phases.push(phase); },
  });
  const conv = makeConversation('kernel');
  const op = async (): Promise<Conversation> => {
    throw new Error('provider down');
  };
  await assert.rejects(() => host.run(conv, baseDeps(), op), /provider down/);
  assert.equal(host.phase(), 'failed');
  assert.equal(host.context().cause, 'error');
  assert.ok(host.context().error !== null);
});
