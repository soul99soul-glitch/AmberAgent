// generation_retry.test.ts — 生成重试策略(TDD 先行)
//
// Android 基准: core/ai/api/.../GenerationRetry.kt(全文 166 行)
//   GenerationRetrySetting / GenerationFailureClassifier.classify(:51-102)
//   decide(:104-124) / delayForAttempt(:126-136)
//   errorText(:139-146) / hasCause(:148-156) / hasNetworkCause(:158-162)
// 集成基准: GenerationHandler.kt runProviderCallWithRetry(:774-820)
//   onBeforeRetry: messages = baseMessages + onUpdateMessages(full)(:625-628)
//   状态文案: strings.xml:386 "Connection interrupted. Retrying in %1$d s (%2$d/%3$d): %4$s"

import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  makeGenerationRetrySetting, classifyGenerationFailure, decideGenerationRetry, delayForAttempt, RETRY_STATUS_TEMPLATE
} from '../main/ets/chat/generation_retry.ts';
import type { GenerationRetrySetting } from '../main/ets/chat/generation_retry.ts';

import { runChatTurn, createMemoryConversationStore } from '../main/ets/chat/chat_turn.ts';
import type { ChatStreamProvider } from '../main/ets/chat/chat_turn.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import type { Assistant } from '../main/ets/chat/assistant.ts';
import { makeConversation, currentMessages } from '../main/ets/chat/conversation.ts';
import type { Conversation } from '../main/ets/chat/conversation.ts';
import type { MessageChunk, UIMessage } from '../main/ets/chat/message.ts';
import { toText } from '../main/ets/chat/message.ts';
import type { AbortSignalLike } from '@amber/deepread-domain';

const err = (message: string, cause?: Error): Error => {
  const e = new Error(message);
  if (cause !== undefined) e.cause = cause;
  return e;
};

const deltaChunkOf = (text: string): MessageChunk => ({
  id: 'c', model: 'm',
  choices: [{
    index: 0,
    delta: {
      id: 'd', role: 'assistant',
      parts: [{ type: 'text', text, metadata: null }],
      annotations: [], createdAt: '2026-07-28T00:00:00Z', finishedAt: null,
      modelId: null, usage: null, translation: null,
    },
    message: null, finishReason: 'unknown',
  }],
  usage: null,
});

const failingProvider = (errors: Error[], thenText: string): { provider: ChatStreamProvider; calls: number } => {
  const state = { calls: 0 };
  const provider: ChatStreamProvider = {
    streamText: (_msgs: UIMessage[], onChunk: (c: MessageChunk) => void): Promise<void> => {
      state.calls++;
      const idx: number = state.calls - 1;
      if (idx < errors.length) return Promise.reject(errors[idx]);
      onChunk(deltaChunkOf(thenText));
      return Promise.resolve();
    },
  };
  return { provider, calls: state.calls };
};

const instantSleep = (): Promise<void> => Promise.resolve();

test('classify: 取消 → CANCELLED 不可重试(:52-58)', () => {
  // JS 映射:Error.name === 'AbortError'(ArkTS/浏览器 AbortController 约定)
  const e = new Error('aborted');
  e.name = 'AbortError';
  const c = classifyGenerationFailure(e);
  assert.equal(c.category, 'CANCELLED');
  assert.equal(c.retryable, false);
  assert.equal(c.reason, 'generation was cancelled');
});

test('classify: 关键字矩阵忠实(:62-101)', () => {
  // CONTEXT
  assert.equal(classifyGenerationFailure(err('context_length_exceeded')).category, 'CONTEXT');
  assert.equal(classifyGenerationFailure(err('maximum context reached')).category, 'CONTEXT');
  // QUOTA(优先级高于 AUTH:'401 insufficient_quota' → QUOTA)
  assert.equal(classifyGenerationFailure(err('401 insufficient_quota')).category, 'QUOTA');
  assert.equal(classifyGenerationFailure(err('余额不足')).category, 'QUOTA');
  // SAFETY
  assert.equal(classifyGenerationFailure(err('content_policy violation')).category, 'SAFETY');
  // AUTH 401 / 403 不同 reason
  const a401 = classifyGenerationFailure(err('HTTP 401 Unauthorized'));
  assert.equal(a401.category, 'AUTH');
  assert.equal(a401.retryable, false);
  const a403 = classifyGenerationFailure(err('403 forbidden'));
  assert.equal(a403.reason, 'permission was denied');
  // BAD_REQUEST
  assert.equal(classifyGenerationFailure(err('400 bad request: model not found')).category, 'BAD_REQUEST');
  // RATE_LIMIT
  assert.equal(classifyGenerationFailure(err('HTTP 429 rate limit')).category, 'RATE_LIMIT');
  assert.equal(classifyGenerationFailure(err('429 too many requests')).retryable, true);
  // TIMEOUT
  assert.equal(classifyGenerationFailure(err('408 request timeout')).category, 'TIMEOUT');
  // SERVER
  assert.equal(classifyGenerationFailure(err('HTTP 503 temporarily unavailable')).category, 'SERVER');
  assert.equal(classifyGenerationFailure(err('502 bad gateway')).retryable, true);
  // NETWORK 关键字
  assert.equal(classifyGenerationFailure(err('connection reset by peer')).category, 'NETWORK');
  assert.equal(classifyGenerationFailure(err('unexpected end of stream')).category, 'NETWORK');
  // UNKNOWN
  const u = classifyGenerationFailure(err('something weird'));
  assert.equal(u.category, 'UNKNOWN');
  assert.equal(u.retryable, false);
});

test('classify: cause 链遍历 + 类名入文本(:139-146)', () => {
  class SocketTimeoutException extends Error {}
  const e = err('outer', new SocketTimeoutException('timed out'));
  assert.equal(classifyGenerationFailure(e).category, 'TIMEOUT');
  // 网络 cause 类名(UnknownHostException/ConnectException/SocketException/SSLException)
  class UnknownHostException extends Error {}
  const e2 = err('http failed', new UnknownHostException('no such host'));
  assert.equal(classifyGenerationFailure(e2).category, 'NETWORK');
  // 循环 cause 不死循环
  const a = err('a');
  const b = err('b', a);
  a.cause = b;
  const c = classifyGenerationFailure(a);
  assert.equal(c.category, 'UNKNOWN');
});

test('decide: 禁用/超次数/不可重试 → false(:104-124)', () => {
  const s = makeGenerationRetrySetting({});
  const e503 = err('503 overloaded');
  assert.equal(decideGenerationRetry(e503, 1, makeGenerationRetrySetting({ enabled: false })).retryable, false);
  assert.equal(decideGenerationRetry(e503, 6, s).retryable, false); // attempt > maxRetries
  assert.equal(decideGenerationRetry(e503, 5, s).retryable, true);
  assert.equal(decideGenerationRetry(err('400 bad request'), 1, s).retryable, false);
});

test('delayForAttempt: 指数 + 上限 + jitter(:126-136)', () => {
  const s: GenerationRetrySetting = makeGenerationRetrySetting({ jitterRatio: 0 });
  assert.equal(delayForAttempt(1, s, () => 0.5), 1000);
  assert.equal(delayForAttempt(2, s, () => 0.5), 2000);
  assert.equal(delayForAttempt(3, s, () => 0.5), 4000);
  assert.equal(delayForAttempt(4, s, () => 0.5), 8000);
  assert.equal(delayForAttempt(5, s, () => 0.5), 16000); // cap
  assert.equal(delayForAttempt(9, s, () => 0.5), 16000); // 仍 cap
  // jitter:rand=0 → base-jitter;rand≈1 → base+jitter;均 ∈ [850,1150]
  const j = makeGenerationRetrySetting({ jitterRatio: 0.15 });
  assert.equal(delayForAttempt(1, j, () => 0), 850);
  assert.equal(delayForAttempt(1, j, () => 0.999999), 1150);
  // coerceAtLeast(0)
  const zero = makeGenerationRetrySetting({ initialDelayMs: 0, maxDelayMs: 0 });
  assert.equal(delayForAttempt(1, zero, () => 0), 0);
});

test('runChatTurn: 503 一次后成功 → 重试成功,状态回调,部分输出丢弃', async () => {
  const f = failingProvider([err('HTTP 503 overloaded')], '重试后的答案');
  const statusLog: Array<string | null> = [];
  const updateLog: string[] = [];
  const conv: Conversation = makeConversation('c1', []);
  const store = createMemoryConversationStore();
  const out = await runChatTurn(conv, '你好', {
    assistant: makeAssistant({}) as Assistant,
    inputTransformers: [],
    outputTransformers: [],
    provider: f.provider,
    store,
    retrySetting: makeGenerationRetrySetting({}),
    sleep: instantSleep,
    onRetryStatus: (s: string | null): void => { statusLog.push(s); },
    onUpdate: (msgs: UIMessage[]): void => { updateLog.push(toText(msgs[msgs.length - 1])); },
  });
  const texts = currentMessages(out).map(toText);
  assert.deepEqual(texts, ['你好', '重试后的答案']);
  // 状态:先非空重试文案(模板逐字 strings.xml:386),成功清零
  assert.equal(statusLog.length >= 2, true);
  const expected = RETRY_STATUS_TEMPLATE
    .replace('%1$d', '1').replace('%2$d', '1').replace('%3$d', '5')
    .replace('%4$s', 'provider is temporarily unavailable');
  assert.equal(statusLog[0], expected);
  assert.equal(statusLog[statusLog.length - 1], null);
  // onBeforeRetry:onUpdate 收到 base 快照(末条 = user 消息,部分输出清空)
  assert.ok(updateLog.some((t: string): boolean => t === '你好'));
});

test('runChatTurn: 重试耗尽 → 原错误传播,调用次数 = maxRetries+1', async () => {
  const errors = [err('503'), err('503'), err('503'), err('503')];
  let calls = 0;
  const provider: ChatStreamProvider = {
    streamText: (): Promise<void> => {
      calls++;
      return Promise.reject(errors[calls - 1] ?? err('503 final'));
    },
  };
  const conv: Conversation = makeConversation('c1', []);
  await assert.rejects(
    runChatTurn(conv, '你好', {
      assistant: makeAssistant({}) as Assistant,
      inputTransformers: [],
      outputTransformers: [],
      provider,
      store: createMemoryConversationStore(),
      retrySetting: makeGenerationRetrySetting({ maxRetries: 2 }),
      sleep: instantSleep,
    }),
    /503/,
  );
  assert.equal(calls, 3); // 1 + 2 次重试
});

test('runChatTurn: 400 不重试(单次调用即抛)', async () => {
  let calls = 0;
  const provider: ChatStreamProvider = {
    streamText: (): Promise<void> => {
      calls++;
      return Promise.reject(err('400 bad request'));
    },
  };
  await assert.rejects(
    runChatTurn(makeConversation('c1', []), '你好', {
      assistant: makeAssistant({}) as Assistant,
      inputTransformers: [],
      outputTransformers: [],
      provider,
      store: createMemoryConversationStore(),
      sleep: instantSleep,
    }),
    /400/,
  );
  assert.equal(calls, 1);
});

test('runChatTurn: 重试等待中 abort → 跳出循环走部分快照语义', async () => {
  let calls = 0;
  const state = { aborted: false };
  const signal: AbortSignalLike = {
    get aborted(): boolean { return state.aborted; },
  };
  const provider: ChatStreamProvider = {
    streamText: (): Promise<void> => {
      calls++;
      return Promise.reject(err('connection reset'));
    },
  };
  const out = await runChatTurn(makeConversation('c1', []), '你好', {
    assistant: makeAssistant({}) as Assistant,
    inputTransformers: [],
    outputTransformers: [],
    provider,
    store: createMemoryConversationStore(),
    retrySetting: makeGenerationRetrySetting({}),
    sleep: (): Promise<void> => {
      state.aborted = true; // sleep 期间用户点停止
      return Promise.resolve();
    },
    abortSignal: signal,
  });
  // abort → 正常 resolve,仅 user 快照(无 assistant 内容)
  assert.deepEqual(currentMessages(out).map(toText), ['你好']);
  assert.equal(calls, 1);
});

test('runChatTurn: 部分流式输出在重试时丢弃,不混入成功结果', async () => {
  let calls = 0;
  const provider: ChatStreamProvider = {
    streamText: (_m: UIMessage[], onChunk: (c: MessageChunk) => void): Promise<void> => {
      calls++;
      if (calls === 1) {
        onChunk(deltaChunkOf('失败尝试的部分内容'));
        return Promise.reject(err('stream was reset'));
      }
      onChunk(deltaChunkOf('干净答案'));
      return Promise.resolve();
    },
  };
  const out = await runChatTurn(makeConversation('c1', []), '你好', {
    assistant: makeAssistant({}) as Assistant,
    inputTransformers: [],
    outputTransformers: [],
    provider,
    store: createMemoryConversationStore(),
    sleep: instantSleep,
  });
  assert.deepEqual(currentMessages(out).map(toText), ['你好', '干净答案']);
});

// Phase 4 回归:位移溢出修复(attempt≥32 时 << 进符号位变负)

test('delayForAttempt: attempt 32+ 不再溢出为负(算术指数)', () => {
  const setting = makeGenerationRetrySetting({ enabled: true, initialDelayMs: 1000, maxDelayMs: 30_000 });
  for (let attempt = 1; attempt <= 40; attempt++) {
    const d = delayForAttempt(attempt, setting, (): number => 0.5);
    assert.ok(d >= 0, `attempt=${attempt} delay=${d}`);
    assert.ok(d <= setting.maxDelayMs, `attempt=${attempt} delay=${d} exceeds max`);
  }
  // 无 jitter 下精确验证钳制(位移语义下 attempt 32: 1000<<31 → 负)
  const exact = makeGenerationRetrySetting({
    enabled: true, initialDelayMs: 1000, maxDelayMs: 30_000, jitterRatio: 0,
  });
  assert.equal(delayForAttempt(32, exact, (): number => 0), 30_000);
  assert.equal(delayForAttempt(40, exact, (): number => 0), 30_000);
});
