// NovelInteractiveAdapter — 把 NovelModelRunning 端口接到 Chat 的真实交互生成深模块。
// 平台 provider/model 解析由 Entry 注入；本层复用 runChatTurn、accumulator、retry、
// reasoning/output transforms 与 abort，不直接访问 Novel repository。

import type {
  AbortControllerLike, NovelModelEvent, NovelModelRequest, NovelModelRunning, NovelModelStream,
  StreamTransportState,
  NovelContextPreviewReceipt,
  NovelStructuredTaskOptions,
  NovelRuntimeSnapshot, NovelResponsesResumeCursor, NovelPreparedRuntime,
} from '@amber/deepread-domain';
import type { Assistant } from './assistant.ts';
import { patchAssistant } from './assistant.ts';
import type { ChatStreamProvider, ChatTurnDeps, ConversationStore, StreamOpts } from './chat_turn.ts';
import { runChatTurn } from './chat_turn.ts';
import { currentMessages, makeConversation } from './conversation.ts';
import type { MessageChunk, UIMessage } from './message.ts';
import { makeSystemMessage, makeUserMessage } from './message.ts';
import { latestAssistantText } from '@amber/deepread-domain';
import { thinkTagTransformer, regexOutputTransformer } from './transformers.ts';
import { runInteractiveTurn } from './interactive_turn_runtime.ts';
import type { InteractiveTurnSnapshot } from './interactive_turn_runtime.ts';
import type { GenerationRetrySetting } from './generation_retry.ts';
import { makeGenerationRetrySetting } from './generation_retry.ts';
import { conversationWithMessagesAsNodes } from './context_engine.ts';
import type { AgentTool } from './tool.ts';
import { toChatToolDefinition } from './tool.ts';
import type { ChatModel, ChatToolDefinition } from './provider_model.ts';
import type { ToolLoopOptions } from './tool_loop.ts';
import { runChatTurnWithTools, runToolLoopContinuation } from './tool_loop.ts';
import {
  applyToolApprovalToConversation, conversationHasPendingTools, findToolNameInConversation,
} from './tool_approval.ts';
import type { Conversation } from './conversation.ts';
import { buildToolSystemPrompt } from './context_assembly.ts';
import { buildAgentLoopBudgetPrompt } from './agent_loop_budget.ts';
import { createNovelContextPolicy, mergeNovelSystemMessages, novelInputTokenBudget, estimateNovelInputTokens } from './novel_context_policy.ts';
import type { NovelContextPolicy } from './novel_context_policy.ts';
import { resolveNovelMaxOutputTokens } from './novel_request_config.ts';
import { runResumedNovelResponse, clearResumedNovelInterruption } from './novel_response_resume.ts';

type NovelModelTarget = NovelModelRequest['modelTarget'];

export interface NovelResponsesStreamOptions {
  runId: string;
  resumeFrom: NovelResponsesResumeCursor | null;
  partialMessages: UIMessage[];
  onCheckpoint: (cursor: NovelResponsesResumeCursor) => Promise<void>;
}
export interface NovelInteractiveRuntimeConfig {
  assistant: Assistant;
  provider: ChatStreamProvider;
  makeProviderForOutputTokens?: (maxOutputTokens: number | null, taskOptions?: NovelStructuredTaskOptions,
    responses?: NovelResponsesStreamOptions) => ChatStreamProvider;
  runtimeSnapshot?: NovelRuntimeSnapshot;
  responsesProviderId?: string;
  responsesResumeSupported?: boolean;
  maxOutputTokens?: number | null;
  tools?: AgentTool[];
  makeProviderForStep?: (
    stepTools: ChatToolDefinition[], maxOutputTokens?: number | null, responses?: NovelResponsesStreamOptions,
  ) => ChatStreamProvider;
  toolPromptModel?: ChatModel;
  toolLoopOptions?: Omit<ToolLoopOptions,
    'tools' | 'makeProviderForStep' | 'toolPromptModel'>;
  retrySetting?: GenerationRetrySetting;
  contextWindowTokens?: number | null;
}

export interface NovelInteractiveAdapterDeps {
  resolveRuntime: (
    modelTarget: NovelModelTarget, projectId: string, runtimeSnapshot?: NovelRuntimeSnapshot,
  ) => Promise<NovelInteractiveRuntimeConfig | null>;
  createAbortController: () => AbortControllerLike;
  nowMs?: () => number;
  onTransportQualified?: (
    runId: string, transport: StreamTransportState,
    firstParsedDeltaAt: number | null, dataEndAt: number | null,
  ) => void;
  // E13:可选 raw 观察者 — 经 ChatTurnDeps.onRawFlushSnapshot 转发(视觉变换前);
  //   只观察,不改变生成/持久化语义
  onRawSnapshot?: (request: NovelModelRequest, messages: UIMessage[]) => void;
}

interface NovelInteractiveRunHandle {
  subscribers: Set<(event: NovelModelEvent) => void>;
  abortController: AbortControllerLike;
}

const novelRequestTools = (request: NovelModelRequest, runtime: NovelInteractiveRuntimeConfig): AgentTool[] => {
  const tools: AgentTool[] = runtime.tools ?? [];
  const selected: AgentTool[] = request.toolProfile === 'none' ? []
    : request.toolProfile === 'read_only' ? tools.filter(tool =>
      tool.name === 'novel_workspace_list' || tool.name === 'novel_workspace_read'
      || tool.name === 'novel_workspace_grep' || tool.name === 'novel_workspace_status'
      || tool.name === 'novel_list_chapters' || tool.name === 'novel_read_chapter'
      || tool.name === 'novel_list_setting_proposals') : tools;
  if (selected.length > 0 && runtime.makeProviderForStep === undefined) {
    throw new Error('Novel 工具已启用，但未配置逐步 Provider');
  }
  return selected;
};

const novelContextOverhead = (
  tools: AgentTool[], runtime: NovelInteractiveRuntimeConfig, messages: UIMessage[],
): string => {
  const definitions: ChatToolDefinition[] = tools.map(toChatToolDefinition);
  const toolPrompt: string = runtime.toolPromptModel !== undefined
    ? buildToolSystemPrompt(tools, runtime.toolPromptModel, messages) : '';
  const budgetPromptReserve: string = tools.length === 0 ? '' : [
    buildAgentLoopBudgetPrompt(4, 16), buildAgentLoopBudgetPrompt(10, 16),
    buildAgentLoopBudgetPrompt(14, 16),
  ].sort((left: string, right: string): number => right.length - left.length)[0];
  return [definitions.length > 0 ? JSON.stringify(definitions) : '', toolPrompt, budgetPromptReserve]
    .filter((text: string): boolean => text.length > 0).join('\n\n');
};

class ChatBackedNovelModel implements NovelModelRunning {
  private readonly deps: NovelInteractiveAdapterDeps;
  private readonly runs: Map<string, NovelInteractiveRunHandle> = new Map();

  constructor(deps: NovelInteractiveAdapterDeps) {
    this.deps = deps;
  }

  async validate(modelTarget: NovelModelTarget, projectId: string): Promise<void> {
    const runtime: NovelInteractiveRuntimeConfig | null =
      await this.deps.resolveRuntime(modelTarget, projectId);
    if (runtime === null) throw new Error('尚未配置可用的 Chat Provider/模型');
  }

  async inputBudgetTokens(modelTarget: NovelModelTarget, projectId: string, maxOutputTokens: number): Promise<number> {
    const runtime: NovelInteractiveRuntimeConfig | null = await this.deps.resolveRuntime(modelTarget, projectId);
    if (runtime === null) throw new Error('尚未配置可用的 Chat Provider/模型');
    return novelInputTokenBudget(resolveNovelMaxOutputTokens(
      maxOutputTokens, runtime.maxOutputTokens, runtime.contextWindowTokens ?? null), runtime.contextWindowTokens ?? null);
  }

  estimateInputTokens(systemPrompt: string, userPrompt: string): number {
    return estimateNovelInputTokens(systemPrompt, userPrompt);
  }

  async prepareOrdinaryRequest(request: NovelModelRequest): Promise<NovelPreparedRuntime> {
    const runtime = await this.deps.resolveRuntime(request.modelTarget, request.projectId, request.runtimeSnapshot);
    if (runtime?.runtimeSnapshot === undefined) throw new Error('当前模型未提供可冻结的小说运行配置');
    const catalog: string = JSON.stringify(novelRequestTools(request, runtime).map(toChatToolDefinition));
    return { runtimeSnapshot: { ...runtime.runtimeSnapshot, frozenToolCatalogJson: catalog,
      responsesProviderId: runtime.responsesProviderId ?? runtime.runtimeSnapshot.responsesProviderId },
      responsesResumeSupported: runtime.responsesResumeSupported === true };
  }

  async previewContext(request: NovelModelRequest): Promise<NovelContextPreviewReceipt> {
    const runtime: NovelInteractiveRuntimeConfig | null = await this.deps.resolveRuntime(request.modelTarget, request.projectId);
    if (runtime === null) throw new Error('尚未配置可用的 Chat Provider/模型');
    if (request.operation.kind !== 'turn') throw new Error('工具续接需要先完成本轮工具结果，再计算发送上下文');
    const maxOutputTokens: number = resolveNovelMaxOutputTokens(
      request.maxOutputTokens, runtime.maxOutputTokens, runtime.contextWindowTokens ?? null);
    const tools: AgentTool[] = novelRequestTools(request, runtime);
    const messages: UIMessage[] = [...request.history, makeUserMessage(request.operation.userPrompt)];
    const receipt: NovelContextPreviewReceipt = createNovelContextPolicy(
      { ...request, maxOutputTokens }, runtime.contextWindowTokens ?? null)
      .preview(messages, novelContextOverhead(tools, runtime, messages));
    return { ...receipt, modelLabel: runtime.toolPromptModel?.displayName || runtime.toolPromptModel?.modelId
      || (request.modelTarget.kind === 'fixed' ? request.modelTarget.modelId : '应用当前模型') };
  }

  start(request: NovelModelRequest): NovelModelStream {
    const subscribers = new Set<(event: NovelModelEvent) => void>();
    const abortController: AbortControllerLike = this.deps.createAbortController();
    const handle: NovelInteractiveRunHandle = { subscribers, abortController };
    this.runs.set(request.runId, handle);
    void this.run(request, handle);
    return {
      subscribe: (callback: (event: NovelModelEvent) => void): (() => void) => {
        subscribers.add(callback);
        return (): void => { subscribers.delete(callback); };
      },
    };
  }

  cancel(runId: string): void {
    const handle: NovelInteractiveRunHandle | undefined = this.runs.get(runId);
    if (handle !== undefined) handle.abortController.abort();
  }

  private emit(handle: NovelInteractiveRunHandle, event: NovelModelEvent): void {
    handle.subscribers.forEach((callback: (value: NovelModelEvent) => void): void => {
      callback(event);
    });
  }

  private emitSnapshot(handle: NovelInteractiveRunHandle, snapshot: InteractiveTurnSnapshot): void {
    this.emit(handle, {
      kind: 'snapshot',
      messages: snapshot.messages,
      generationActive: snapshot.generationActive,
      textDeltasLive: snapshot.textDeltasLive,
      transport: snapshot.transport,
    });
  }

  private async run(
    request: NovelModelRequest, handle: NovelInteractiveRunHandle,
  ): Promise<void> {
    let checkpointFailurePartial: (() => Promise<void>) | undefined;
    try {
      const resolved: NovelInteractiveRuntimeConfig | null =
        await this.deps.resolveRuntime(request.modelTarget, request.projectId, request.runtimeSnapshot);
      if (resolved === null) {
        this.emit(handle, { kind: 'failed', message: '尚未配置可用的 Chat Provider/模型' });
        return;
      }
      const maxOutputTokens: number = resolveNovelMaxOutputTokens(
        request.maxOutputTokens, resolved.maxOutputTokens, resolved.contextWindowTokens ?? null);
      const tools: AgentTool[] = novelRequestTools(request, resolved);
      if (request.runtimeSnapshot?.frozenToolCatalogJson !== undefined
        && request.runtimeSnapshot.frozenToolCatalogJson !== JSON.stringify(tools.map(toChatToolDefinition))) {
        throw new Error('原请求工具配置已变化，无法恢复该小说请求');
      }
      const isResume: boolean = request.operation.kind === 'resume_response';
      const completedResume: boolean = request.operation.kind === 'resume_response'
        && request.operation.cursor.terminalStatus === 'completed';
      const responseEnabled: boolean = request.responsesResumeEnabled === true;
      if (isResume && !responseEnabled) throw new Error('原请求未启用 Responses 恢复');
      if (responseEnabled && (resolved.responsesResumeSupported !== true || resolved.makeProviderForOutputTokens === undefined)) {
        throw new Error('原 Provider 不支持 Responses 恢复');
      }
      if (isResume && request.operation.kind === 'resume_response'
        && (request.runtimeSnapshot === undefined || request.operation.cursor.providerId !==
          (request.runtimeSnapshot.responsesProviderId ?? request.runtimeSnapshot.providerId))) {
        throw new Error('Responses 游标不属于原请求 Provider');
      }
      const rawState: { messages: UIMessage[]; cursor: NovelResponsesResumeCursor | undefined } = {
        messages: request.history, cursor: request.operation.kind === 'resume_response' ? request.operation.cursor : undefined,
      };
      let savedMessagesJson: string = JSON.stringify(request.history);
      let checkpointRejected: boolean = false;
      const saveCanonical = async (messages: UIMessage[], cursor?: NovelResponsesResumeCursor): Promise<void> => {
        const messagesJson: string = JSON.stringify(messages);
        try { await request.checkpoint(messages, cursor); }
        catch (error) { checkpointRejected = true; throw error; }
        savedMessagesJson = messagesJson;
      };
      // Responses publishes each event together with its cursor. Its owner already
      // controls failure durability; ordinary tool streams need this final raw save.
      if (!responseEnabled) checkpointFailurePartial = async (): Promise<void> => {
        if (!checkpointRejected && JSON.stringify(rawState.messages) !== savedMessagesJson) {
          await saveCanonical(rawState.messages, rawState.cursor);
        }
      };
      const responses: NovelResponsesStreamOptions | undefined = responseEnabled ? {
        runId: request.runId,
        resumeFrom: request.operation.kind === 'resume_response' ? request.operation.cursor : null,
        partialMessages: request.history,
        onCheckpoint: async (cursor: NovelResponsesResumeCursor): Promise<void> => {
          if (handle.abortController.signal.aborted) throw new Error('cancelled');
          await request.checkpoint(rawState.messages, cursor);
          rawState.cursor = cursor;
        },
      } : undefined;
      const requestProvider: ChatStreamProvider = resolved.makeProviderForOutputTokens !== undefined
        ? resolved.makeProviderForOutputTokens(maxOutputTokens, request.taskOptions, responses) : resolved.provider;
      const resumeProvider: ChatStreamProvider = completedResume
        ? { streamText: async (): Promise<void> => {} } : requestProvider;
      const assistant: Assistant = patchAssistant(resolved.assistant, {
        systemPrompt: request.context === undefined ? request.systemPrompt : '',
        streamOutput: true,
        presetMessages: [],
        localTools: [],
        mcpServers: [],
        enabledSkills: [],
        enableMemory: false,
        useGlobalMemory: false,
        enableRecentChatsReference: false,
        maxTokens: maxOutputTokens,
      });
      const emptyConversation: Conversation = makeConversation(`novel:${request.projectId}`, [], {
        assistantId: assistant.id,
        title: '',
      });
      const conversation: Conversation = conversationWithMessagesAsNodes(
        emptyConversation, request.history);
      const store: ConversationStore = {
        save: async (value: Conversation): Promise<void> => {
          rawState.messages = currentMessages(value);
          await saveCanonical(rawState.messages, rawState.cursor);
        },
      };
      const makeProviderForStep = resolved.makeProviderForStep;
      const contextPolicy: NovelContextPolicy = createNovelContextPolicy(
        { ...request, maxOutputTokens }, resolved.contextWindowTokens ?? null);
      const guardProvider = (
        provider: ChatStreamProvider, definitions: ChatToolDefinition[] = [], resumeGet: boolean = false,
      ): ChatStreamProvider => ({
        streamText: (
          messages: UIMessage[], onChunk: (chunk: MessageChunk) => void, opts?: StreamOpts,
        ): Promise<void> => {
          // Tool definitions 也占 provider 输入。这里只核验实际发送总量；
          // 不启用普通 Chat 的逐消息 final fit，以免拆开完整轮次或来源章节。
          const sending: UIMessage[] = mergeNovelSystemMessages(messages);
          if (resumeGet) return provider.streamText(sending, onChunk, opts);
          contextPolicy.assertBudget(definitions.length > 0
            ? [...sending, makeSystemMessage(JSON.stringify(definitions))] : sending);
          return provider.streamText(sending, onChunk, opts);
        },
      });
      const deps: ChatTurnDeps = {
        assistant,
        inputTransformers: [],
        outputTransformers: [thinkTagTransformer, regexOutputTransformer],
        provider: guardProvider(isResume ? resumeProvider : requestProvider, [], isResume),
        store,
        abortSignal: handle.abortController.signal,
        retrySetting: responseEnabled ? makeGenerationRetrySetting({ enabled: false })
          : resolved.retrySetting ?? makeGenerationRetrySetting({}),
        flushIntervalMs: 0,
        nowMs: this.deps.nowMs,
        prepareContextMessages: async (messages: UIMessage[]): Promise<UIMessage[]> => {
          if (isResume && !resumeProviderConsumed) return messages;
          return contextPolicy.prepare(messages, novelContextOverhead(tools, resolved, messages));
        },
        onRawFlushSnapshot: (messages: UIMessage[]): void => {
          rawState.messages = messages;
          this.deps.onRawSnapshot?.(request, messages);
        },
      };
      let resumeProviderConsumed: boolean = false;
      const loop: ToolLoopOptions = {
        ...(resolved.toolLoopOptions ?? {}),
        tools,
        makeProviderForStep: (stepTools: ChatToolDefinition[]): ChatStreamProvider => {
          if (isResume && !resumeProviderConsumed) {
            resumeProviderConsumed = true;
            return guardProvider(resumeProvider, stepTools, true);
          }
          const nextResponses = isResume && responses !== undefined
            ? { ...responses, resumeFrom: null, partialMessages: [] } : responses;
          return guardProvider(makeProviderForStep !== undefined
            ? makeProviderForStep(stepTools, maxOutputTokens, nextResponses) : requestProvider, stepTools);
        },
        toolPromptModel: resolved.toolPromptModel,
      };
      this.emit(handle, { kind: 'status', text: '正在连接模型并组织正文…' });
      const result = await runInteractiveTurn(
        conversation,
        deps,
        async (runtimeDeps: ChatTurnDeps): Promise<Conversation> => {
          if (request.operation.kind === 'resume_response') {
            if (tools.length === 0) return runResumedNovelResponse(conversation, runtimeDeps);
            const resumed = await runToolLoopContinuation(conversation, runtimeDeps, loop);
            if (handle.abortController.signal.aborted || conversationHasPendingTools(resumed)) return resumed;
            const cleared = clearResumedNovelInterruption(resumed, request.history[request.history.length - 1].id);
            await runtimeDeps.store.save(cleared);
            return cleared;
          }
          if (request.operation.kind === 'turn') {
            return tools.length === 0
              ? runChatTurn(conversation, request.operation.userPrompt, runtimeDeps)
              : runChatTurnWithTools(
                conversation, request.operation.userPrompt, runtimeDeps, loop);
          }
          if (findToolNameInConversation(conversation, request.operation.toolCallId) === null) {
            throw new Error(`未找到待续跑工具:${request.operation.toolCallId}`);
          }
          const approved: Conversation = applyToolApprovalToConversation(
            conversation, request.operation.toolCallId, request.operation.verdict);
          await runtimeDeps.store.save(approved);
          if (conversationHasPendingTools(approved)) return approved;
          return runToolLoopContinuation(approved, runtimeDeps, loop);
        },
        { onSnapshot: (snapshot: InteractiveTurnSnapshot): void => { this.emitSnapshot(handle, snapshot); } },
      );
      if (this.deps.onTransportQualified !== undefined) {
        this.deps.onTransportQualified(
          request.runId, result.transport, result.firstParsedDeltaAt, result.dataEndAt);
      }
      if (handle.abortController.signal.aborted) {
        this.emit(handle, { kind: 'failed', message: 'cancelled' });
        return;
      }
      if (conversationHasPendingTools(result.conversation)) {
        this.emit(handle, { kind: 'waiting_user' });
        return;
      }
      const resultMessages: UIMessage[] = currentMessages(result.conversation);
      if (tools.length === 0 && request.operation.kind === 'turn'
        && latestAssistantText(resultMessages.slice(request.history.length)).trim().length === 0) {
        this.emit(handle, { kind: 'failed', message: '模型返回空内容' });
        return;
      }
      this.emit(handle, { kind: 'completed' });
    } catch (error) {
      let failure: unknown = error;
      if (checkpointFailurePartial !== undefined) {
        try { await checkpointFailurePartial(); }
        catch (saveError) { failure = saveError; }
      }
      this.emit(handle, {
        kind: 'failed',
        message: handle.abortController.signal.aborted ? 'cancelled' : String(failure),
      });
    } finally {
      this.runs.delete(request.runId);
    }
  }
}

export const createNovelInteractiveAdapter = (
  deps: NovelInteractiveAdapterDeps,
): NovelModelRunning => new ChatBackedNovelModel(deps);
