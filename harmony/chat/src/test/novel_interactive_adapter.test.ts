import { test } from 'node:test';
import assert from 'node:assert/strict';
import type {
  AbortControllerLike, AbortSignalLike, NovelModelEvent, NovelModelRequest,
  NovelChatMode, NovelGenerationGranularity, NovelRunKind,
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
import { createNovelProjectOperationTools } from '../main/ets/chat/novel_project_operations.ts';
import type { ChatToolDefinition } from '../main/ets/chat/provider_model.ts';
import type { JsonValue } from '../main/ets/chat/json.ts';
import { estimateTokens } from '../main/ets/chat/context_compact.ts';
import {
  createNovelCreation, createFileNovelRepository, createMemoryFileStore, makeNovelProject, makeNovelMaterial,
} from '@amber/deepread-domain';

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

test('novel context preview uses the actual configured model/output budget and identical send selection', async () => {
  let sent: UIMessage[] = [];
  let providerCalls: number = 0;
  const provider: ChatStreamProvider = { async streamText(messages, onChunk): Promise<void> {
    providerCalls++; sent = messages; onChunk(chunkOf('完成'));
  } };
  const adapter = createNovelInteractiveAdapter({
    resolveRuntime: async () => ({ assistant: makeAssistant({}), provider, contextWindowTokens: 1200, maxOutputTokens: 200 }),
    createAbortController: (): AbortControllerLike => new TestAbortController(),
  });
  const history = [makeUserMessage('旧问题'.repeat(300)), makeAssistantMessage('旧回答'.repeat(300))];
  const modelRequest: NovelModelRequest = { ...request(undefined, history), context: {
    excludedHistoryMessageIds: [], sections: [
      { key: 'instruction', text: '必要作者约束', required: true },
      { key: 'material:a', text: '智能资料 A'.repeat(100), required: false },
      { key: 'material:b', text: '智能资料 B'.repeat(1000), required: false },
    ], materialDecisions: ['a', 'b'].map(id => ({ materialId: id, title: id, text: `智能资料 ${id}`,
      protected: false, included: true, reason: 'smartMatch', relevanceScore: 100 })),
  } };
  const preview = await adapter.previewContext!(modelRequest);
  assert.equal(providerCalls, 0, 'preview resolves configuration without invoking the model');
  assert.equal(preview.maxOutputTokens, 200);
  assert.equal(preview.tokenBudget, 1000);
  assert.equal(preview.materialDecisions.find(item => item.materialId === 'a')?.included, true);
  assert.equal(preview.materialDecisions.find(item => item.materialId === 'b')?.reason, 'budgetTrimmed');
  const events = await collectToTerminal(callback => adapter.start(modelRequest).subscribe(callback));
  assert.equal(events.at(-1)?.kind, 'completed');
  const text = (messages: UIMessage[]) => messages.map(message => ({ role: message.role,
    parts: message.parts.filter(part => part.type === 'text').map(part => part.text) }));
  assert.deepEqual(text(sent), text(preview.preparedMessages));
  assert.equal(estimateTokens(sent), preview.estimatedInputTokens);
  assert.equal(history.length, 2, 'canonical history stays intact');
});

test('novel context preview preserves protected material above 48k characters or reports real window failure', async () => {
  let windowTokens: number = 50_000;
  let sent: UIMessage[] = [];
  const provider: ChatStreamProvider = { async streamText(messages, onChunk): Promise<void> {
    sent = messages; onChunk(chunkOf('完成'));
  } };
  const adapter = createNovelInteractiveAdapter({
    resolveRuntime: async () => ({ assistant: makeAssistant({}), provider, contextWindowTokens: windowTokens, maxOutputTokens: 100 }),
    createAbortController: (): AbortControllerLike => new TestAbortController(),
  });
  const fullMaterial: string = 'abcdefghij'.repeat(6000);
  const modelRequest: NovelModelRequest = { ...request(), context: { excludedHistoryMessageIds: [],
    sections: [{ key: 'material:forced', text: fullMaterial, required: true }] } };
  const preview = await adapter.previewContext!(modelRequest);
  assert.ok(preview.preparedMessages.some(message => message.parts.some(part => part.type === 'text' && part.text === fullMaterial)));
  windowTokens = 500;
  await assert.rejects(adapter.previewContext!(modelRequest), /上下文.*超出/);
  const events = await collectToTerminal(callback => adapter.start(modelRequest).subscribe(callback));
  assert.equal(events.at(-1)?.kind, 'failed');
  assert.equal(sent.length, 0, 'send re-resolves changed model budget instead of using an old preview');
});

test('novel creation preview and actual Workspace send kinds share exact system/input/material selection in all three modes', async t => {
  const modes: Array<{ mode: NovelChatMode; granularity: NovelGenerationGranularity | null; runKind: NovelRunKind }> = [
    { mode: 'discuss', granularity: null, runKind: 'discussion' },
    { mode: 'write', granularity: 'continuation', runKind: 'prose_continuation' },
    { mode: 'write', granularity: 'whole_chapter', runKind: 'prose_whole_chapter' },
  ];
  for (const mode of modes) {
  await t.test(mode.runKind, async () => {
  let sent: UIMessage[] = [];
  const provider: ChatStreamProvider = { async streamText(messages, onChunk): Promise<void> {
    sent = messages; onChunk(chunkOf('完成正文'));
  } };
  const adapter = createNovelInteractiveAdapter({
    resolveRuntime: async () => ({ assistant: makeAssistant({}), provider, contextWindowTokens: 4000, maxOutputTokens: 300,
      runtimeSnapshot: { providerId: 'test-provider', modelId: 'test-model', configurationJson: '{}' },
      responsesResumeSupported: false }),
    createAbortController: (): AbortControllerLike => new TestAbortController(),
  });
  const repository = createFileNovelRepository(createMemoryFileStore());
  const smart = { ...makeNovelMaterial({ kind: 'character', title: 'harbor', content: '港口人物', injectionMode: 'smart', now: 1 }), id: 'smart' };
  const off = { ...makeNovelMaterial({ kind: 'character', title: 'off-setting', content: '临时包含禁用世界', injectionMode: 'off',
    aliases: ['temporary-alias'], tags: ['z-tag', 'a-tag'], now: 1 }), id: 'off' };
  const initial = await repository.createProject({ ...makeNovelProject({ name: '预览', now: 1 }),
    materials: [smart, off], baseMaterials: [smart, off], materialOverrides: [], hiddenMaterialIds: [] });
  const creation = createNovelCreation({ repository, modelRunning: adapter });
  const before = await repository.readWorkspaceSnapshot(initial.id);
  const overrides = { branchId: before.status.activeBranchId, forceIncludeMaterialIds: ['off'], forceExcludeMaterialIds: ['smart'] };
  const preview = await creation.previewContext(initial.id, ' harbor ', mode.mode, mode.granularity, overrides);
  assert.equal(preview.branchId, before.status.activeBranchId);
  assert.equal(preview.receipt.materialDecisions.find(item => item.materialId === 'off')?.reason, 'forceIncluded');
  assert.equal(preview.receipt.materialDecisions.find(item => item.materialId === 'smart')?.reason, 'forceExcluded');
  assert.deepEqual((await repository.readWorkspaceSnapshot(initial.id)).status.cas, before.status.cas, 'preview is read-only');
  await new Promise<void>((resolve, reject) => {
    creation.generate(initial.id, ' harbor ', mode.mode, mode.granularity, mode.runKind, null, overrides).subscribe(event => {
      if (event.kind === 'completed') resolve();
      if (event.kind === 'failed') reject(new Error(event.message));
    });
  });
  const text = (messages: UIMessage[]) => messages.flatMap(message => message.parts.filter(part => part.type === 'text').map(part => part.text));
  assert.deepEqual(text(sent), text(preview.receipt.preparedMessages));
  assert.match(text(sent).join('\n'), /临时包含禁用世界/);
  assert.match(text(sent).join('\n'), /Aliases: temporary-alias/);
  assert.match(text(sent).join('\n'), /Tags: a-tag, z-tag/);
  assert.equal(text(sent).join('\n').includes('港口人物'), false);
  await assert.rejects(creation.previewContext(initial.id, '继续', 'write', null, { ...overrides, branchId: 'another-branch' }), /分支已切换/);
  });
  }
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
  const eventKinds: string[] = events.map(event => String(event.kind));
  assert.equal(eventKinds.includes('delta') || eventKinds.includes('replace'), false);
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

test('novel adapter: C6 read_only profile 复用同一 tool loop 但排除 workspace write', async () => {
  const provider: ChatStreamProvider = {
    streamText(_messages: UIMessage[], onChunk: (chunk: MessageChunk) => void): Promise<void> {
      onChunk(chunkOf('只读完成'));
      return Promise.resolve();
    },
  };
  const execute = async (_input: JsonValue): Promise<UIMessagePart[]> => [];
  const tools: AgentTool[] = [
    makeAgentTool({ name: 'novel_workspace_read', description: 'read', execute }),
    makeAgentTool({ name: 'novel_workspace_status', description: 'status', execute }),
    makeAgentTool({ name: 'novel_list_chapters', description: 'chapters', execute }),
    makeAgentTool({ name: 'novel_read_chapter', description: 'chapter', execute }),
    makeAgentTool({ name: 'novel_list_setting_proposals', description: 'settings', execute }),
    makeAgentTool({ name: 'novel_revise_chapter', description: 'revise', execute }),
    makeAgentTool({ name: 'ask_user', description: 'ask', execute }),
    makeAgentTool({
      name: 'novel_workspace_write', description: 'write', needsApproval: true, execute,
    }),
  ];
  let stepToolNames: string[] = [];
  const adapter = createNovelInteractiveAdapter({
    resolveRuntime: async () => ({
      assistant: makeAssistant({}),
      provider,
      tools,
      makeProviderForStep: (definitions: ChatToolDefinition[]): ChatStreamProvider => {
        stepToolNames = definitions.map((definition: ChatToolDefinition): string => definition.name);
        return provider;
      },
    }),
    createAbortController: (): AbortControllerLike => new TestAbortController(),
  });
  const readOnlyRequest: NovelModelRequest = { ...request(), toolProfile: 'read_only' };
  const events = await collectToTerminal(
    callback => adapter.start(readOnlyRequest).subscribe(callback));
  assert.equal(events.at(-1)?.kind, 'completed');
  assert.deepEqual(stepToolNames, ['novel_workspace_read', 'novel_workspace_status',
    'novel_list_chapters', 'novel_read_chapter', 'novel_list_setting_proposals']);
});

test('novel project staging executes in the real tool loop and returns the author proposal without dispatcher approval', async () => {
  let calls = 0;
  let staged = 0;
  let continuation: UIMessage[] = [];
  const provider: ChatStreamProvider = {
    async streamText(messages, onChunk): Promise<void> {
      calls++;
      if (calls === 1) onChunk(toolChunkOf('project-proposal', 'novel_rename_project', '{"title":"New name"}'));
      else { continuation = messages; onChunk(chunkOf('已生成待作者批准的改名提案。')); }
    },
  };
  const tools = createNovelProjectOperationTools(async (name, input) => {
    assert.equal(name, 'novel_rename_project');
    assert.equal(input.title, 'New name');
    staged++;
    return { requires_author_approval: true, proposal_id: 'proposal-a' };
  });
  const adapter = createNovelInteractiveAdapter({
    resolveRuntime: async () => ({ assistant: makeAssistant({}), provider, tools,
      makeProviderForStep: () => provider }),
    createAbortController: () => new TestAbortController(),
  });
  const events = await collectToTerminal(callback => adapter.start(request()).subscribe(callback));
  assert.equal(events.at(-1)?.kind, 'completed');
  assert.equal(staged, 1);
  assert.equal(calls, 2);
  const tool = findToolPart(continuation, 'project-proposal');
  assert.equal(tool.output[0].type, 'text');
  if (tool.output[0].type === 'text') assert.deepEqual(JSON.parse(tool.output[0].text),
    { requires_author_approval: true, proposal_id: 'proposal-a' });
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

test('novel context: 模型窗口只裁发送历史的完整轮次，checkpoint 与 snapshot 保留全历史', async () => {
  const history: UIMessage[] = [];
  for (let index = 0; index < 10; index += 1) {
    history.push(makeUserMessage(`question-${index}: ${'u'.repeat(800)}`));
    history.push(makeAssistantMessage(`answer-${index}: ${'a'.repeat(800)}`));
  }
  const originalIds: string[] = history.map(message => message.id);
  const checkpoints: UIMessage[][] = [];
  let sent: UIMessage[] = [];
  const provider: ChatStreamProvider = {
    async streamText(messages, onChunk): Promise<void> {
      sent = messages;
      onChunk(chunkOf('完成'));
    },
  };
  const adapter = createNovelInteractiveAdapter({
    resolveRuntime: async () => ({ assistant: makeAssistant({}), provider, contextWindowTokens: 1000 }),
    createAbortController: (): AbortControllerLike => new TestAbortController(),
  });
  const modelRequest: NovelModelRequest = {
    ...request(async messages => { checkpoints.push(messages); }, history), maxOutputTokens: 100,
  };
  const events = await collectToTerminal(callback => adapter.start(modelRequest).subscribe(callback));
  assert.equal(events.at(-1)?.kind, 'completed');
  const sentHistory = sent.filter(message => originalIds.includes(message.id));
  assert.ok(sentHistory.length > 0 && sentHistory.length < history.length);
  assert.equal(sentHistory[0].role, 'user');
  assert.equal(sentHistory.length % 2, 0);
  assert.deepEqual(sentHistory.map(message => message.id), originalIds.slice(-sentHistory.length));
  assert.ok(estimateTokens(sent) <= 900);
  assert.deepEqual(checkpoints.at(-1)?.slice(0, history.length).map(message => message.id), originalIds);
  const snapshot = events.filter(event => event.kind === 'snapshot').at(-1);
  assert.ok(snapshot?.kind === 'snapshot');
  assert.equal(snapshot.messages.length, history.length + 2);
  assert.deepEqual(history.map(message => message.id), originalIds);
});

test('novel context: 归档仅排除发送副本，有context时不重复原systemPrompt', async () => {
  const history = [makeUserMessage('旧讨论'), makeAssistantMessage('旧回答')];
  let sent: UIMessage[] = [];
  let saved: UIMessage[] = [];
  const provider: ChatStreamProvider = {
    async streamText(messages, onChunk): Promise<void> { sent = messages; onChunk(chunkOf('完成')); },
  };
  const adapter = createNovelInteractiveAdapter({
    resolveRuntime: async () => ({ assistant: makeAssistant({}), provider, contextWindowTokens: 1000 }),
    createAbortController: (): AbortControllerLike => new TestAbortController(),
  });
  const modelRequest: NovelModelRequest = {
    ...request(async messages => { saved = messages; }, history),
    systemPrompt: '旧system不要重复', maxOutputTokens: 100,
    context: {
      sections: [
        { key: 'instruction', text: '按作者决定创作', required: true },
        { key: 'archive', text: '累计摘要：主角抵达北城。', required: true },
      ],
      excludedHistoryMessageIds: history.map(message => message.id),
    },
  };
  const events = await collectToTerminal(callback => adapter.start(modelRequest).subscribe(callback));
  assert.equal(events.at(-1)?.kind, 'completed');
  assert.equal(sent.some(message => history.some(old => old.id === message.id)), false);
  assert.deepEqual(saved.slice(0, 2).map(message => message.id), history.map(message => message.id));
  const systemText = sent.filter(message => message.role === 'system')
    .flatMap(message => message.parts.filter(part => part.type === 'text').map(part => part.text)).join('\n');
  assert.match(systemText, /按作者决定创作/);
  assert.match(systemText, /累计摘要：主角抵达北城/);
  assert.equal(systemText.includes('旧system不要重复'), false);
});

test('novel context: 作者必需sections或完整来源输入超窗，明确失败且不调用provider', async () => {
  for (const oversizedSection of [true, false]) {
    let called: boolean = false;
    const provider: ChatStreamProvider = {
      async streamText(_messages, onChunk): Promise<void> { called = true; onChunk(chunkOf('意外')); },
    };
    const adapter = createNovelInteractiveAdapter({
      resolveRuntime: async () => ({ assistant: makeAssistant({}), provider, contextWindowTokens: 500 }),
      createAbortController: (): AbortControllerLike => new TestAbortController(),
    });
    const modelRequest: NovelModelRequest = {
      ...request(), maxOutputTokens: 100,
      operation: { kind: 'turn', userPrompt: oversizedSection ? '开始' : '完整原章'.repeat(1000) },
      context: {
        sections: [{
          key: 'instruction', text: oversizedSection ? '作者必需决定'.repeat(1000) : '保留原章事实',
          required: true,
        }],
        excludedHistoryMessageIds: [],
      },
    };
    const events = await collectToTerminal(callback => adapter.start(modelRequest).subscribe(callback));
    const terminal = events.at(-1);
    assert.equal(terminal?.kind, 'failed');
    assert.match(terminal?.kind === 'failed' ? terminal.message : '', /上下文.*超出/);
    assert.equal(called, false);
  }
});

test('novel context: 无sections的helper也检查完整systemPrompt预算，未知窗口使用既有默认', async () => {
  for (const configuredWindow of [500, null]) {
    let called: boolean = false;
    const provider: ChatStreamProvider = {
      async streamText(_messages, onChunk): Promise<void> { called = true; onChunk(chunkOf('完成')); },
    };
    const adapter = createNovelInteractiveAdapter({
      resolveRuntime: async () => ({
        assistant: makeAssistant({}), provider, contextWindowTokens: configuredWindow,
      }),
      createAbortController: (): AbortControllerLike => new TestAbortController(),
    });
    const modelRequest: NovelModelRequest = {
      ...request(), maxOutputTokens: 100, systemPrompt: '完整系统指令'.repeat(1000),
    };
    const events = await collectToTerminal(callback => adapter.start(modelRequest).subscribe(callback));
    assert.equal(events.at(-1)?.kind, configuredWindow === null ? 'completed' : 'failed');
    assert.equal(called, configuredWindow === null);
  }
});

test('novel context: 工具续接保留待答所在完整轮次及本轮工具输出，旧历史仍完整checkpoint', async () => {
  const oldHistory: UIMessage[] = [];
  for (let index = 0; index < 8; index += 1) {
    oldHistory.push(makeUserMessage(`old-${index} ${'u'.repeat(1200)}`));
    oldHistory.push(makeAssistantMessage(`answer-${index} ${'a'.repeat(1200)}`));
  }
  const sends: UIMessage[][] = [];
  let saved: UIMessage[] = [];
  const provider: ChatStreamProvider = {
    async streamText(messages, onChunk): Promise<void> {
      sends.push(messages);
      onChunk(sends.length === 1 ? toolChunkOf('context-ask', 'ask_user', JSON.stringify({
        questions: [{ id: 'tone', question: '语气？' }],
      })) : chunkOf('按回答继续。'));
    },
  };
  const adapter = createNovelInteractiveAdapter({
    resolveRuntime: async () => ({
      assistant: makeAssistant({}), provider, contextWindowTokens: 1800,
      tools: [createAskUserTool()], makeProviderForStep: () => provider,
    }),
    createAbortController: (): AbortControllerLike => new TestAbortController(),
  });
  const save = async (messages: UIMessage[]): Promise<void> => { saved = messages; };
  const first = await collectToTerminal(callback => adapter.start({
    ...request(save, oldHistory), maxOutputTokens: 100,
  }).subscribe(callback));
  assert.equal(first.at(-1)?.kind, 'waiting_user');
  const pendingHistory = saved;
  const pendingUser = pendingHistory.at(-2);
  assert.equal(pendingUser?.role, 'user');
  const second = await collectToTerminal(callback => adapter.start({
    ...request(save, pendingHistory), runId: 'context-continue', maxOutputTokens: 100,
    context: {
      sections: [{ key: 'instruction', text: '按作者回答继续', required: true }],
      // 即使范围包含待答轮次，未决工具的来源和结果也不能被归档过滤误删。
      excludedHistoryMessageIds: pendingHistory.map(message => message.id),
    },
    operation: { kind: 'tool_continuation', toolCallId: 'context-ask',
      verdict: { kind: 'answered', answer: '{"tone":"温暖"}' } },
  }).subscribe(callback));
  assert.equal(second.at(-1)?.kind, 'completed');
  assert.equal(sends.length, 2);
  assert.ok(sends[1].some(message => message.id === pendingUser?.id));
  assert.equal(findToolPart(sends[1], 'context-ask').output.length, 1);
  assert.deepEqual(saved.slice(0, oldHistory.length).map(message => message.id),
    oldHistory.map(message => message.id));
  assert.equal(latestAssistantText(saved), '按回答继续。');
});

test('novel context: 本轮工具输出超窗时停止后续发送，完整结果仍保存在checkpoint', async () => {
  let calls: number = 0;
  let saved: UIMessage[] = [];
  const fullResult = '完整工具读取结果'.repeat(1000);
  const provider: ChatStreamProvider = {
    async streamText(_messages, onChunk): Promise<void> {
      calls += 1;
      onChunk(toolChunkOf('context-read', 'novel_workspace_read', '{}'));
    },
  };
  const adapter = createNovelInteractiveAdapter({
    resolveRuntime: async () => ({
      assistant: makeAssistant({}), provider, contextWindowTokens: 1600,
      tools: [makeAgentTool({
        name: 'novel_workspace_read', description: 'read',
        execute: async (): Promise<UIMessagePart[]> => [
          { type: 'text', text: fullResult, metadata: null },
        ],
      })],
      makeProviderForStep: () => provider,
    }),
    createAbortController: (): AbortControllerLike => new TestAbortController(),
  });
  const events = await collectToTerminal(callback => adapter.start({
    ...request(async messages => { saved = messages; }), maxOutputTokens: 100,
  }).subscribe(callback));
  const terminal = events.at(-1);
  assert.equal(terminal?.kind, 'failed');
  assert.match(terminal?.kind === 'failed' ? terminal.message : '', /上下文.*超出/);
  assert.equal(calls, 1);
  const tool = findToolPart(saved, 'context-read');
  assert.equal(tool.output[0].type === 'text' ? tool.output[0].text : '', fullResult);
});

test('novel request: 单次输出上限送到实际main和tool factory，null继承runtime实际上限', async () => {
  for (const withTools of [false, true]) {
    const mainCaps: Array<number | null> = [];
    const stepCaps: Array<number | null | undefined> = [];
    const provider: ChatStreamProvider = {
      async streamText(_messages, onChunk): Promise<void> { onChunk(chunkOf('完成')); },
    };
    const adapter = createNovelInteractiveAdapter({
      resolveRuntime: async () => ({
        assistant: makeAssistant({ maxTokens: 777 }), provider, contextWindowTokens: 1000,
        maxOutputTokens: 200,
        makeProviderForOutputTokens: (cap: number | null): ChatStreamProvider => {
          mainCaps.push(cap);
          return provider;
        },
        tools: withTools ? [createAskUserTool()] : [],
        makeProviderForStep: (_definitions, cap): ChatStreamProvider => {
          stepCaps.push(cap);
          return provider;
        },
      }),
      createAbortController: (): AbortControllerLike => new TestAbortController(),
    });
    const modelRequest: NovelModelRequest = { ...request(), maxOutputTokens: withTools ? null : 100 };
    const events = await collectToTerminal(callback => adapter.start(modelRequest).subscribe(callback));
    assert.equal(events.at(-1)?.kind, 'completed');
    assert.deepEqual(mainCaps, [withTools ? 200 : 100]);
    assert.deepEqual(stepCaps, withTools ? [200] : []);
    assert.equal(modelRequest.maxOutputTokens, withTools ? null : 100);
  }
});

test('novel audit budget metadata reuses the production window, output cap and message estimator', async () => {
  const { novelInputTokenBudget, estimateNovelInputTokens } = await import('../main/ets/chat/novel_context_policy.ts');
  const seen: Array<{target: NovelModelRequest['modelTarget'];projectId:string}> = [];
  const provider: ChatStreamProvider = {streamText:async()=>{}};
  const adapter = createNovelInteractiveAdapter({
    resolveRuntime:async(target,projectId)=>{
      seen.push({target,projectId});
      return {assistant:makeAssistant({}),provider,contextWindowTokens:16000,maxOutputTokens:1234};
    },
    createAbortController:()=>new TestAbortController(),
  });
  const target: NovelModelRequest['modelTarget']={kind:'fixed',providerId:'p',modelId:'m'};
  assert.equal(await adapter.inputBudgetTokens!(target,'audit-book',8192),novelInputTokenBudget(8192,16000));
  assert.deepEqual(seen,[{target,projectId:'audit-book'}]);
  assert.equal(adapter.estimateInputTokens!('作者约束','本块正文'),estimateNovelInputTokens('作者约束','本块正文'));
  assert.equal(adapter.estimateInputTokens!('作者约束','本块正文'),estimateTokens([
    { ...makeUserMessage('作者约束'),role:'system' },makeUserMessage('本块正文'),
  ]));
});
