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
import type {
  MessageChunk, UIMessage, UIMessagePart, UIMessagePartTool,
} from '../main/ets/chat/message.ts';
import { makeAssistantMessage, makeUserMessage } from '../main/ets/chat/message.ts';
import { makeAgentTool, makeInputSchemaObj } from '../main/ets/chat/tool.ts';
import type { AgentTool } from '../main/ets/chat/tool.ts';
import { createAskUserTool } from '../main/ets/chat/builtin_ask_user_tool.ts';
import type { ChatToolDefinition } from '../main/ets/chat/provider_model.ts';
import type { JsonValue } from '../main/ets/chat/json.ts';

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

const toolChunkOf = (callId: string, name: string, input: string): MessageChunk => ({
  id: 'chunk', model: 'model',
  choices: [{
    index: 0,
    delta: {
      id: 'assistant', role: 'assistant',
      parts: [{
        type: 'tool', toolCallId: callId, toolName: name, input,
        output: [], approvalState: { type: 'auto' }, metadata: null,
      }],
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

const request = (
  checkpoint: (messages: UIMessage[]) => Promise<void> = async (): Promise<void> => {},
  history: UIMessage[] = [],
): NovelModelRequest => ({
  runId: 'novel-run',
  projectId: 'project-1',
  systemPrompt: '只写正文',
  maxOutputTokens: null,
  modelTarget: { kind: 'global' },
  history,
  operation: { kind: 'turn', userPrompt: '开始' },
  checkpoint,
});

const collectToTerminal = (
  start: (callback: (event: NovelModelEvent) => void) => () => void,
): Promise<NovelModelEvent[]> => new Promise((resolve): void => {
  const events: NovelModelEvent[] = [];
  start((event: NovelModelEvent): void => {
    events.push(event);
    if (event.kind === 'completed' || event.kind === 'failed' || event.kind === 'waiting_user') {
      resolve(events);
    }
  });
});

test('novel adapter: validate 把 fixed provider/model target 原样交给 runtime resolver', async () => {
  const provider: ChatStreamProvider = {
    streamText(): Promise<void> { return Promise.resolve(); },
  };
  const seen: Array<NovelModelRequest['modelTarget']> = [];
  const adapter = createNovelInteractiveAdapter({
    resolveRuntime: async target => {
      seen.push(target);
      return { assistant: makeAssistant({}), provider };
    },
    createAbortController: (): AbortControllerLike => new TestAbortController(),
  });
  await adapter.validate(
    { kind: 'fixed', providerId: 'provider-a', modelId: 'model-b' }, 'project-1');
  assert.deepEqual(seen, [{ kind: 'fixed', providerId: 'provider-a', modelId: 'model-b' }]);
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
  const terminalEvent: NovelModelEvent | undefined = events.at(-1);
  assert.equal(terminalEvent?.kind, 'failed');
  assert.equal(terminalEvent?.kind === 'failed' ? terminalEvent.message : '', 'cancelled');
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
      if (event.kind === 'snapshot' && latestAssistantText(event.messages).length === 0) {
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
  const terminalEvent: NovelModelEvent | undefined = completedEvents.at(-1);
  assert.equal(terminalEvent?.kind, 'failed');
  assert.equal(terminalEvent?.kind === 'failed' ? terminalEvent.message : '', 'cancelled');
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

const findToolPartOrNull = (
  messages: UIMessage[], toolCallId: string,
): UIMessagePartTool | null => {
  for (const message of messages) {
    for (const part of message.parts) {
      if (part.type === 'tool' && part.toolCallId === toolCallId) return part;
    }
  }
  return null;
};

const findToolPart = (messages: UIMessage[], toolCallId: string): UIMessagePartTool => {
  const found: UIMessagePartTool | null = findToolPartOrNull(messages, toolCallId);
  if (found !== null) return found;
  throw new Error(`tool part not found:${toolCallId}`);
};

test('novel adapter: canonical history 原样进入 Chat，会话 snapshot/checkpoint 始终是完整 current messages', async () => {
  const oldUser: UIMessage = makeUserMessage('旧问题');
  const oldAssistant: UIMessage = makeAssistantMessage('旧回答');
  oldAssistant.parts.unshift({
    type: 'reasoning', reasoning: '旧思考', createdAt: '2026-08-30T00:00:00Z',
    finishedAt: '2026-08-30T00:00:01Z', metadata: null,
  });
  const history: UIMessage[] = [oldUser, oldAssistant];
  const checkpoints: UIMessage[][] = [];
  let providerMessages: UIMessage[] = [];
  const provider: ChatStreamProvider = {
    streamText(messages: UIMessage[], onChunk: (chunk: MessageChunk) => void): Promise<void> {
      providerMessages = messages;
      onChunk(chunkOf('新回答'));
      return Promise.resolve();
    },
  };
  const adapter = createNovelInteractiveAdapter({
    resolveRuntime: async target => {
      assert.deepEqual(target, { kind: 'global' });
      return { assistant: makeAssistant({}), provider };
    },
    createAbortController: (): AbortControllerLike => new TestAbortController(),
  });
  const stream = adapter.start(request(async (messages: UIMessage[]): Promise<void> => {
    checkpoints.push(messages);
  }, history));
  const events = await collectToTerminal(callback => stream.subscribe(callback));

  assert.ok(providerMessages.some((message: UIMessage): boolean => message.id === oldUser.id));
  assert.ok(providerMessages.some((message: UIMessage): boolean => message.id === oldAssistant.id));
  assert.equal(checkpoints.length, 2);
  assert.deepEqual(checkpoints[0].slice(0, 2).map((message: UIMessage): string => message.id),
    history.map((message: UIMessage): string => message.id));
  assert.equal(checkpoints[0].at(-1)?.role, 'user');
  assert.equal(checkpoints[1].at(-1)?.role, 'assistant');
  const finalSnapshot = events.filter(event => event.kind === 'snapshot').at(-1);
  assert.ok(finalSnapshot?.kind === 'snapshot');
  assert.equal(finalSnapshot.messages.length, 4);
  assert.equal(finalSnapshot.messages[0].id, oldUser.id);
  assert.equal(finalSnapshot.messages[1].id, oldAssistant.id);
  assert.equal(latestAssistantText(finalSnapshot.messages), '新回答');
});

test('novel adapter: checkpoint 必须完成后才调用 provider，并保持 user→终态顺序', async () => {
  let releaseUserCheckpoint: (() => void) | null = null;
  let providerCalled: boolean = false;
  const checkpointRoles: string[] = [];
  const provider: ChatStreamProvider = {
    streamText(_messages: UIMessage[], onChunk: (chunk: MessageChunk) => void): Promise<void> {
      providerCalled = true;
      onChunk(chunkOf('完成'));
      return Promise.resolve();
    },
  };
  const adapter = createNovelInteractiveAdapter({
    resolveRuntime: async () => ({ assistant: makeAssistant({}), provider }),
    createAbortController: (): AbortControllerLike => new TestAbortController(),
  });
  let checkpointCount: number = 0;
  const stream = adapter.start(request(async (messages: UIMessage[]): Promise<void> => {
    checkpointCount += 1;
    checkpointRoles.push(messages.at(-1)?.role ?? 'none');
    if (checkpointCount === 1) {
      await new Promise<void>((resolve): void => { releaseUserCheckpoint = resolve; });
    }
  }));
  const terminal = collectToTerminal(callback => stream.subscribe(callback));
  await new Promise(resolve => setTimeout(resolve, 5));
  assert.equal(providerCalled, false);
  assert.notEqual(releaseUserCheckpoint, null);
  (releaseUserCheckpoint as unknown as () => void)();
  const events = await terminal;
  assert.equal(events.at(-1)?.kind, 'completed');
  assert.equal(providerCalled, true);
  assert.deepEqual(checkpointRoles, ['user', 'assistant']);
});

test('novel adapter: ask_user pending→checkpoint→同 toolCallId answer continuation→工具输出→终态', async () => {
  const script: MessageChunk[][] = [
    [toolChunkOf('ask-1', 'ask_user', JSON.stringify({
      questions: [{ id: 'tone', question: '什么语气？' }],
    }))],
    [chunkOf('已按温暖语气继续。')],
  ];
  let providerCall: number = 0;
  const provider: ChatStreamProvider = {
    streamText(_messages: UIMessage[], onChunk: (chunk: MessageChunk) => void): Promise<void> {
      const chunks: MessageChunk[] = script[providerCall] ?? [chunkOf('unexpected')];
      providerCall += 1;
      for (const chunk of chunks) onChunk(chunk);
      return Promise.resolve();
    },
  };
  const checkpoints: UIMessage[][] = [];
  const adapter = createNovelInteractiveAdapter({
    resolveRuntime: async () => ({
      assistant: makeAssistant({}), provider,
      tools: [createAskUserTool()],
      makeProviderForStep: (_defs: ChatToolDefinition[]): ChatStreamProvider => provider,
    }),
    createAbortController: (): AbortControllerLike => new TestAbortController(),
  });
  const save = async (messages: UIMessage[]): Promise<void> => { checkpoints.push(messages); };
  const first = adapter.start(request(save));
  const waitingEvents = await collectToTerminal(callback => first.subscribe(callback));
  assert.equal(waitingEvents.at(-1)?.kind, 'waiting_user');
  const pendingHistory: UIMessage[] = checkpoints.at(-1) ?? [];
  const pendingTool: UIMessagePartTool = findToolPart(pendingHistory, 'ask-1');
  assert.equal(pendingTool.approvalState.type, 'pending');
  assert.equal(pendingTool.output.length, 0);

  const continuation: NovelModelRequest = {
    ...request(save, pendingHistory),
    runId: 'novel-run-answer',
    operation: {
      kind: 'tool_continuation', toolCallId: 'ask-1',
      verdict: { kind: 'answered', answer: '{"tone":"温暖"}' },
    },
  };
  const second = adapter.start(continuation);
  const completedEvents = await collectToTerminal(callback => second.subscribe(callback));
  assert.equal(completedEvents.at(-1)?.kind, 'completed');
  assert.equal(providerCall, 2);

  const answerCheckpointIndex: number = checkpoints.findIndex((messages: UIMessage[]): boolean => {
    const tool: UIMessagePartTool | null = findToolPartOrNull(messages, 'ask-1');
    return tool !== null && tool.approvalState.type === 'answered' && tool.output.length === 0;
  });
  assert.ok(answerCheckpointIndex >= 0);
  const outputCheckpointIndex: number = checkpoints.findIndex((messages: UIMessage[]): boolean => {
    const tool: UIMessagePartTool | null = findToolPartOrNull(messages, 'ask-1');
    return tool !== null && tool.output.length === 1;
  });
  assert.ok(outputCheckpointIndex > answerCheckpointIndex);
  const outputTool: UIMessagePartTool = findToolPart(checkpoints[outputCheckpointIndex], 'ask-1');
  assert.equal((outputTool.output[0] as { text: string }).text, '{"tone":"温暖"}');
  assert.equal(latestAssistantText(checkpoints.at(-1) ?? []), '已按温暖语气继续。');
});

const writeTool = (onExecute: () => void): AgentTool => makeAgentTool({
  name: 'novel_workspace_write',
  description: 'write proposal',
  parameters: () => makeInputSchemaObj({ proposal_id: { type: 'string' } }, ['proposal_id']),
  needsApproval: true,
  allowsAutoApproval: false,
  execute: (_input: JsonValue): Promise<UIMessagePart[]> => {
    onExecute();
    return Promise.resolve([{
      type: 'text', text: '{"status":"proposed","proposal_id":"p1"}', metadata: null,
    }]);
  },
});

const runWriteVerdict = async (
  verdict: { kind: 'approved' } | { kind: 'denied'; reason: string },
): Promise<{ executed: boolean; checkpoints: UIMessage[][]; events: NovelModelEvent[] }> => {
  let executed: boolean = false;
  const script: MessageChunk[][] = [
    [toolChunkOf('write-1', 'novel_workspace_write', '{"proposal_id":"p1"}')],
    [chunkOf('写入决策已处理。')],
  ];
  let callIndex: number = 0;
  const provider: ChatStreamProvider = {
    streamText(_messages: UIMessage[], onChunk: (chunk: MessageChunk) => void): Promise<void> {
      for (const chunk of script[callIndex] ?? [chunkOf('unexpected')]) onChunk(chunk);
      callIndex += 1;
      return Promise.resolve();
    },
  };
  const checkpoints: UIMessage[][] = [];
  const adapter = createNovelInteractiveAdapter({
    resolveRuntime: async () => ({
      assistant: makeAssistant({}), provider,
      tools: [writeTool((): void => { executed = true; })],
      makeProviderForStep: (): ChatStreamProvider => provider,
    }),
    createAbortController: (): AbortControllerLike => new TestAbortController(),
  });
  const save = async (messages: UIMessage[]): Promise<void> => { checkpoints.push(messages); };
  const first = adapter.start(request(save));
  const waiting = await collectToTerminal(callback => first.subscribe(callback));
  assert.equal(waiting.at(-1)?.kind, 'waiting_user');
  const secondRequest: NovelModelRequest = {
    ...request(save, checkpoints.at(-1) ?? []),
    runId: `novel-write-${verdict.kind}`,
    operation: { kind: 'tool_continuation', toolCallId: 'write-1', verdict },
  };
  const second = adapter.start(secondRequest);
  const events = await collectToTerminal(callback => second.subscribe(callback));
  return { executed, checkpoints, events };
};

test('novel adapter: write approved 执行真实工具并 checkpoint 输出', async () => {
  const result = await runWriteVerdict({ kind: 'approved' });
  assert.equal(result.executed, true);
  assert.equal(result.events.at(-1)?.kind, 'completed');
  const output = findToolPart(result.checkpoints.at(-1) ?? [], 'write-1').output;
  assert.equal((output[0] as { text: string }).text,
    '{"status":"proposed","proposal_id":"p1"}');
});

test('novel adapter: write denied 不执行工具，复用 dispatcher 写入 denied output 后续跑', async () => {
  const result = await runWriteVerdict({ kind: 'denied', reason: '不要改正文' });
  assert.equal(result.executed, false);
  assert.equal(result.events.at(-1)?.kind, 'completed');
  const output = findToolPart(result.checkpoints.at(-1) ?? [], 'write-1').output;
  assert.equal(output.length, 1);
  const payload = JSON.parse((output[0] as { text: string }).text);
  assert.equal(payload.status, 'denied');
  assert.ok(String(payload.message).includes('不要改正文'));
});
