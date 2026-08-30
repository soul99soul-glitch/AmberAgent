// NovelInteractiveAdapter — 把 NovelModelRunning 端口接到 Chat 的真实交互生成深模块。
// 平台 provider/model 解析由 Entry 注入；本层复用 runChatTurn、accumulator、retry、
// reasoning/output transforms 与 abort，不直接访问 Novel repository。

import type {
  AbortControllerLike, NovelModelEvent, NovelModelRequest, NovelModelRunning, NovelModelStream,
  StreamTransportState,
} from '@amber/deepread-domain';
import type { Assistant } from './assistant.ts';
import { patchAssistant } from './assistant.ts';
import type { ChatStreamProvider, ChatTurnDeps } from './chat_turn.ts';
import { createMemoryConversationStore, runChatTurn } from './chat_turn.ts';
import { makeConversation } from './conversation.ts';
import type { UIMessage } from './message.ts';
import { latestAssistantText } from '@amber/deepread-domain';
import { thinkTagTransformer, regexOutputTransformer } from './transformers.ts';
import { runInteractiveTurn } from './interactive_turn_runtime.ts';
import type { InteractiveTurnSnapshot } from './interactive_turn_runtime.ts';
import type { GenerationRetrySetting } from './generation_retry.ts';
import { makeGenerationRetrySetting } from './generation_retry.ts';

export interface NovelInteractiveRuntimeConfig {
  assistant: Assistant;
  provider: ChatStreamProvider;
  retrySetting?: GenerationRetrySetting;
}

export interface NovelInteractiveAdapterDeps {
  resolveRuntime: (modelId: string | null) => Promise<NovelInteractiveRuntimeConfig | null>;
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

const assistantMessages = (messages: UIMessage[]): UIMessage[] =>
  messages.filter((message: UIMessage): boolean => message.role === 'assistant');

class ChatBackedNovelModel implements NovelModelRunning {
  private readonly deps: NovelInteractiveAdapterDeps;
  private readonly runs: Map<string, NovelInteractiveRunHandle> = new Map();

  constructor(deps: NovelInteractiveAdapterDeps) {
    this.deps = deps;
  }

  async validate(modelId: string | null): Promise<void> {
    const runtime: NovelInteractiveRuntimeConfig | null = await this.deps.resolveRuntime(modelId);
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
    const messages: UIMessage[] = assistantMessages(snapshot.messages);
    this.emit(handle, {
      kind: 'snapshot',
      messages,
      generationActive: snapshot.generationActive,
      textDeltasLive: snapshot.textDeltasLive,
      transport: snapshot.transport,
    });
  }

  private async run(
    request: NovelModelRequest, handle: NovelInteractiveRunHandle,
  ): Promise<void> {
    try {
      const resolved: NovelInteractiveRuntimeConfig | null = await this.deps.resolveRuntime(request.modelId);
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
      const conversation = makeConversation(`novel:${request.runId}`, [], {
        assistantId: assistant.id,
        title: '',
      });
      const deps: ChatTurnDeps = {
        assistant,
        inputTransformers: [],
        outputTransformers: [thinkTagTransformer, regexOutputTransformer],
        provider: resolved.provider,
        store: createMemoryConversationStore(),
        abortSignal: handle.abortController.signal,
        retrySetting: resolved.retrySetting ?? makeGenerationRetrySetting({}),
        flushIntervalMs: 0,
        nowMs: this.deps.nowMs,
      };
      this.emit(handle, { kind: 'status', text: '正在连接模型并组织正文…' });
      const result = await runInteractiveTurn(
        conversation,
        deps,
        (runtimeDeps: ChatTurnDeps) => runChatTurn(conversation, request.userPrompt, runtimeDeps),
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
      if (latestAssistantText(assistantMessages(result.conversation.messageNodes
        .map(node => node.messages[node.selectIndex]))).trim().length === 0) {
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
