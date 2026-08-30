// NovelInteractiveAdapter — 把 NovelModelRunning 端口接到 Chat 的真实交互生成深模块。
// 平台 provider/model 解析由 Entry 注入；本层复用 runChatTurn、accumulator、retry、
// reasoning/output transforms 与 abort，不直接访问 Novel repository。

import type {
  AbortControllerLike, NovelModelEvent, NovelModelRequest, NovelModelRunning, NovelModelStream,
  StreamTransportState,
} from '@amber/deepread-domain';
import type { Assistant } from './assistant.ts';
import { patchAssistant } from './assistant.ts';
import type { ChatStreamProvider, ChatTurnDeps, ConversationStore } from './chat_turn.ts';
import { runChatTurn } from './chat_turn.ts';
import { currentMessages, makeConversation } from './conversation.ts';
import type { UIMessage } from './message.ts';
import { latestAssistantText } from '@amber/deepread-domain';
import { thinkTagTransformer, regexOutputTransformer } from './transformers.ts';
import { runInteractiveTurn } from './interactive_turn_runtime.ts';
import type { InteractiveTurnSnapshot } from './interactive_turn_runtime.ts';
import type { GenerationRetrySetting } from './generation_retry.ts';
import { makeGenerationRetrySetting } from './generation_retry.ts';
import { conversationWithMessagesAsNodes } from './context_engine.ts';
import type { AgentTool } from './tool.ts';
import type { ChatModel, ChatToolDefinition } from './provider_model.ts';
import type { ToolLoopOptions } from './tool_loop.ts';
import { runChatTurnWithTools, runToolLoopContinuation } from './tool_loop.ts';
import {
  applyToolApprovalToConversation, conversationHasPendingTools, findToolNameInConversation,
} from './tool_approval.ts';
import type { Conversation } from './conversation.ts';

type NovelModelTarget = NovelModelRequest['modelTarget'];

export interface NovelInteractiveRuntimeConfig {
  assistant: Assistant;
  provider: ChatStreamProvider;
  tools?: AgentTool[];
  makeProviderForStep?: (stepTools: ChatToolDefinition[]) => ChatStreamProvider;
  toolPromptModel?: ChatModel;
  toolLoopOptions?: Omit<ToolLoopOptions,
    'tools' | 'makeProviderForStep' | 'toolPromptModel'>;
  retrySetting?: GenerationRetrySetting;
}

export interface NovelInteractiveAdapterDeps {
  resolveRuntime: (
    modelTarget: NovelModelTarget, projectId: string,
  ) => Promise<NovelInteractiveRuntimeConfig | null>;
  createAbortController: () => AbortControllerLike;
  nowMs?: () => number;
  onTransportQualified?: (
    runId: string, transport: StreamTransportState,
    firstParsedDeltaAt: number | null, dataEndAt: number | null,
  ) => void;
}

interface NovelInteractiveRunHandle {
  subscribers: Set<(event: NovelModelEvent) => void>;
  abortController: AbortControllerLike;
}

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
    try {
      const resolved: NovelInteractiveRuntimeConfig | null =
        await this.deps.resolveRuntime(request.modelTarget, request.projectId);
      if (resolved === null) {
        this.emit(handle, { kind: 'failed', message: '尚未配置可用的 Chat Provider/模型' });
        return;
      }
      const assistant: Assistant = patchAssistant(resolved.assistant, {
        systemPrompt: request.systemPrompt,
        streamOutput: true,
        presetMessages: [],
        localTools: [],
        mcpServers: [],
        enabledSkills: [],
        enableMemory: false,
        useGlobalMemory: false,
        enableRecentChatsReference: false,
        maxTokens: request.maxOutputTokens,
      });
      const emptyConversation: Conversation = makeConversation(`novel:${request.projectId}`, [], {
        assistantId: assistant.id,
        title: '',
      });
      const conversation: Conversation = conversationWithMessagesAsNodes(
        emptyConversation, request.history);
      const store: ConversationStore = {
        save: async (value: Conversation): Promise<void> => {
          await request.checkpoint(currentMessages(value));
        },
      };
      const deps: ChatTurnDeps = {
        assistant,
        inputTransformers: [],
        outputTransformers: [thinkTagTransformer, regexOutputTransformer],
        provider: resolved.provider,
        store,
        abortSignal: handle.abortController.signal,
        retrySetting: resolved.retrySetting ?? makeGenerationRetrySetting({}),
        flushIntervalMs: 0,
        nowMs: this.deps.nowMs,
      };
      const configuredTools: AgentTool[] = resolved.tools ?? [];
      const tools: AgentTool[] = request.toolProfile === 'none'
        ? []
        : request.toolProfile === 'read_only'
          ? configuredTools.filter((tool: AgentTool): boolean =>
            tool.name === 'novel_workspace_list' || tool.name === 'novel_workspace_read' ||
            tool.name === 'novel_workspace_grep' || tool.name === 'novel_workspace_status')
          : configuredTools;
      const makeProviderForStep = resolved.makeProviderForStep;
      if (tools.length > 0 && makeProviderForStep === undefined) {
        throw new Error('Novel 工具已启用，但未配置逐步 Provider');
      }
      const loop: ToolLoopOptions = {
        ...(resolved.toolLoopOptions ?? {}),
        tools,
        makeProviderForStep: makeProviderForStep
          ?? ((_stepTools: ChatToolDefinition[]): ChatStreamProvider => resolved.provider),
        toolPromptModel: resolved.toolPromptModel,
      };
      this.emit(handle, { kind: 'status', text: '正在连接模型并组织正文…' });
      const result = await runInteractiveTurn(
        conversation,
        deps,
        async (runtimeDeps: ChatTurnDeps): Promise<Conversation> => {
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
      this.emit(handle, {
        kind: 'failed',
        message: handle.abortController.signal.aborted ? 'cancelled' : String(error),
      });
    } finally {
      this.runs.delete(request.runId);
    }
  }
}

export const createNovelInteractiveAdapter = (
  deps: NovelInteractiveAdapterDeps,
): NovelModelRunning => new ChatBackedNovelModel(deps);
