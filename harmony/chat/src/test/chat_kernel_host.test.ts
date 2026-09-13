// Kernel host phase sequence tests

import { test } from 'node:test';
import assert from 'node:assert/strict';

import { createChatKernelHost, isKernelTerminalPhase } from '../main/ets/chat/chat_kernel_host.ts';
import type { KernelPhase } from '../main/ets/chat/chat_kernel_host.ts';
import { makeConversation, makeMessageNode } from '../main/ets/chat/conversation.ts';
import type { Conversation } from '../main/ets/chat/conversation.ts';
import { makeAssistantMessage, makeUIMessage } from '../main/ets/chat/message.ts';
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

// ===== R05:取消判据只认显式 cancel / 真实 abort signal =====

const convWithUser = (): Conversation =>
  makeConversation('kernel', [
    makeMessageNode(
      [makeUIMessage('user', [{ type: 'text', text: 'hello', metadata: null }])], 0, 'n1'),
  ]);

test('kernel R05:含 "abort" 的错误不误判取消;真实 abort signal → cancelled 且返回已落库(含 user)会话', async () => {
  // 1) 非取消错误(message 含 abort / 普通 AbortError name 但 signal 未 abort)→ failed 且抛错
  for (const err of [
    Object.assign(new Error('Request was aborted by upstream gateway'), { name: 'OpenAiStreamError' }),
    Object.assign(new Error('stream abort by transport'), { name: 'Error' }),
    Object.assign(new Error('nope'), { name: 'AbortError' }),
  ]) {
    const host = createChatKernelHost({ onPhase: (): void => {} });
    const persisted = convWithUser();
    const op = async (deps: ChatTurnDeps): Promise<Conversation> => {
      await deps.store.save(persisted); // 引擎已先持久化新 user 消息
      throw err;
    };
    await assert.rejects(() => host.run(makeConversation('kernel'), baseDeps(), op));
    assert.equal(host.phase(), 'failed', `${err.name}:${err.message} 不应被当取消`);
    assert.equal(host.context().cause, 'error');
  }

  // 2) 真实 abort signal → cancelled,且返回引擎最后落库(含 user)的会话而非入参旧会话
  const host = createChatKernelHost({ onPhase: (): void => {} });
  const controller = new AbortController();
  const deps: ChatTurnDeps = { ...baseDeps(), abortSignal: controller.signal };
  const persisted = convWithUser();
  const op = async (d: ChatTurnDeps): Promise<Conversation> => {
    await d.store.save(persisted);
    controller.abort();
    throw Object.assign(new Error('stream aborted'), { name: 'AbortError' });
  };
  const out = await host.run(makeConversation('kernel'), deps, op);
  assert.equal(host.phase(), 'cancelled');
  assert.equal(host.context().cause, 'user');
  assert.equal(out.result, null);
  assert.equal(out.conversation.messageNodes.length, 1,
    '取消收口必须返回引擎最后落库的会话,不能回退到无 user 的入参');
  assert.equal(out.conversation.messageNodes[0].messages[0].role, 'user');
});
