import { test } from 'node:test';
import assert from 'node:assert/strict';
import type {
  AbortControllerLike, AbortSignalLike, NovelModelEvent, NovelModelRequest,
} from '@amber/deepread-domain';
import { latestAssistantText } from '@amber/deepread-domain';
import { createNovelInteractiveAdapter } from '../main/ets/chat/novel_interactive_adapter.ts';
import type { ChatStreamProvider, StreamOpts } from '../main/ets/chat/chat_turn.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import { makeGenerationRetrySetting } from '../main/ets/chat/generation_retry.ts';
import type { MessageChunk, UIMessage } from '../main/ets/chat/message.ts';

const chunkOf = (text: string): MessageChunk => ({
  id: 'chunk', model: 'model',
  choices: [{
    index: 0,
    delta: {
      id: 'assistant', role: 'assistant',
      parts: [{ type: 'text', text, metadata: null }],
      annotations: [], createdAt: '2026-08-30T00:00:00Z', finishedAt: null,
      modelId: 'model', usage: null, translation: null,
    },
    message: null,
    finishReason: 'unknown',
  }],
  usage: null,
});

class TestAbortSignal implements AbortSignalLike {
  aborted: boolean = false;
  private listeners: Array<() => void> = [];
  addEventListener(_type: string, listener: () => void): void { this.listeners.push(listener); }
  removeEventListener(_type: string, listener: () => void): void {
    const index: number = this.listeners.indexOf(listener);
    if (index >= 0) this.listeners.splice(index, 1);
  }
  fire(): void {
    this.aborted = true;
    for (const listener of this.listeners.slice()) listener();
  }
}

class TestAbortController implements AbortControllerLike {
  readonly signal: TestAbortSignal = new TestAbortSignal();
  abort(): void { this.signal.fire(); }
}

const request = (): NovelModelRequest => ({
  runId: 'novel-run',
  systemPrompt: '只写正文',
  userPrompt: '开始',
  maxOutputTokens: null,
  modelId: null,
});

const collectToTerminal = (
  start: (callback: (event: NovelModelEvent) => void) => () => void,
): Promise<NovelModelEvent[]> => new Promise((resolve): void => {
  const events: NovelModelEvent[] = [];
  start((event: NovelModelEvent): void => {
    events.push(event);
    if (event.kind === 'completed' || event.kind === 'failed') resolve(events);
  });
});

test('novel adapter: Chat 累加快照 A→AB，实时上报 canonical snapshot 且不重复字符串 delta', async () => {
  let clock: number = 10;
  const provider: ChatStreamProvider = {
    async streamText(
      _messages: UIMessage[], onChunk: (chunk: MessageChunk) => void, opts?: StreamOpts,
    ): Promise<void> {
      onChunk(chunkOf('A'));
      clock = 15;
      onChunk(chunkOf('B'));
      clock = 20;
      opts?.onDataEnd?.();
    },
  };
  const adapter = createNovelInteractiveAdapter({
    resolveRuntime: async () => ({ assistant: makeAssistant({}), provider }),
    createAbortController: (): AbortControllerLike => new TestAbortController(),
    nowMs: (): number => clock,
  });
  const stream = adapter.start(request());
  const events = await collectToTerminal(callback => stream.subscribe(callback));
  const snapshots = events.filter(event => event.kind === 'snapshot');
  assert.ok(snapshots.length >= 2);
  const finalSnapshot = snapshots.at(-1);
  assert.ok(finalSnapshot?.kind === 'snapshot');
  assert.equal(latestAssistantText(finalSnapshot.messages), 'AB');
  assert.equal(finalSnapshot.transport, 'live');
  assert.equal(events.some(event => event.kind === 'delta' || event.kind === 'replace'), false);
  assert.equal(events.at(-1)?.kind, 'completed');
});

test('novel adapter: cancel 保留已见 snapshot，终态只发 failed(cancelled)', async () => {
  let release: (() => void) | null = null;
  const provider: ChatStreamProvider = {
    streamText(
      _messages: UIMessage[], onChunk: (chunk: MessageChunk) => void, opts?: StreamOpts,
    ): Promise<void> {
      onChunk(chunkOf('部分'));
      return new Promise<void>((resolve, reject): void => {
        release = resolve;
        opts?.signal?.addEventListener?.('abort', (): void => {
          const error: Error = new Error('aborted');
          error.name = 'AbortError';
          reject(error);
        });
      });
    },
  };
  const adapter = createNovelInteractiveAdapter({
    resolveRuntime: async () => ({ assistant: makeAssistant({}), provider }),
    createAbortController: (): AbortControllerLike => new TestAbortController(),
  });
  const stream = adapter.start(request());
  const terminal = collectToTerminal(callback => stream.subscribe(callback));
  await new Promise(resolve => setTimeout(resolve, 5));
  adapter.cancel('novel-run');
  const events = await terminal;
  if (release !== null) (release as () => void)();
  const snapshot = events.find(event => event.kind === 'snapshot');
  assert.ok(snapshot?.kind === 'snapshot');
  assert.equal(latestAssistantText(snapshot.messages), '部分');
  assert.equal(events.at(-1)?.kind, 'failed');
  assert.equal(events.at(-1)?.kind === 'failed' ? events.at(-1)?.message : '', 'cancelled');
  assert.equal(events.some(event => event.kind === 'completed'), false);
});

test('novel adapter: 重试前清空失败部分，等待中停止可立即结束且不持久化旧正文', async () => {
  let attempts: number = 0;
  const controller = new TestAbortController();
  const provider: ChatStreamProvider = {
    streamText(
      _messages: UIMessage[], onChunk: (chunk: MessageChunk) => void, opts?: StreamOpts,
    ): Promise<void> {
      attempts += 1;
      onChunk(chunkOf('失败尝试旧正文'));
      opts?.onDataEnd?.();
      return Promise.reject(new Error('HTTP 503'));
    },
  };
  const adapter = createNovelInteractiveAdapter({
    resolveRuntime: async () => ({
      assistant: makeAssistant({}),
      provider,
      retrySetting: makeGenerationRetrySetting({
        enabled: true, maxRetries: 2, initialDelayMs: 10000, maxDelayMs: 10000, jitterRatio: 0,
      }),
    }),
    createAbortController: (): AbortControllerLike => controller,
  });
  const events: NovelModelEvent[] = [];
  const terminal = new Promise<NovelModelEvent[]>((resolve): void => {
    const stream = adapter.start(request());
    stream.subscribe((event: NovelModelEvent): void => {
      events.push(event);
      if (event.kind === 'snapshot' && event.messages.length === 0) {
        adapter.cancel('novel-run');
      }
      if (event.kind === 'completed' || event.kind === 'failed') resolve(events);
    });
  });
  const completedEvents: NovelModelEvent[] = await terminal;
  assert.equal(attempts, 1);
  const snapshots = completedEvents.filter(event => event.kind === 'snapshot');
  assert.ok(snapshots.some(event => event.kind === 'snapshot'
    && latestAssistantText(event.messages) === '失败尝试旧正文'));
  assert.equal(snapshots.at(-1)?.kind === 'snapshot'
    ? latestAssistantText(snapshots.at(-1)?.messages ?? []) : 'unexpected', '');
  assert.equal(completedEvents.at(-1)?.kind, 'failed');
  assert.equal(completedEvents.at(-1)?.kind === 'failed'
    ? completedEvents.at(-1)?.message : '', 'cancelled');
});

test('novel adapter: 503 dataEnd 无 partial 后重试成功，按新请求重新判定 live', async () => {
  let attempts: number = 0;
  let clock: number = 10;
  const provider: ChatStreamProvider = {
    streamText(
      _messages: UIMessage[], onChunk: (chunk: MessageChunk) => void, opts?: StreamOpts,
    ): Promise<void> {
      attempts += 1;
      if (attempts === 1) {
        opts?.onDataEnd?.();
        return Promise.reject(new Error('HTTP 503'));
      }
      onChunk(chunkOf('新'));
      clock = 15;
      onChunk(chunkOf('正文'));
      clock = 20;
      opts?.onDataEnd?.();
      return Promise.resolve();
    },
  };
  const adapter = createNovelInteractiveAdapter({
    resolveRuntime: async () => ({
      assistant: makeAssistant({}),
      provider,
      retrySetting: makeGenerationRetrySetting({
        enabled: true, maxRetries: 1, initialDelayMs: 0, maxDelayMs: 0, jitterRatio: 0,
      }),
    }),
    createAbortController: (): AbortControllerLike => new TestAbortController(),
    nowMs: (): number => clock,
  });
  const stream = adapter.start(request());
  const events = await collectToTerminal(callback => stream.subscribe(callback));
  assert.equal(attempts, 2);
  const snapshots = events.filter(event => event.kind === 'snapshot');
  const finalSnapshot = snapshots.at(-1);
  assert.ok(finalSnapshot?.kind === 'snapshot');
  assert.equal(latestAssistantText(finalSnapshot.messages), '新正文');
  assert.equal(finalSnapshot.transport, 'live');
  assert.equal(events.at(-1)?.kind, 'completed');
});
