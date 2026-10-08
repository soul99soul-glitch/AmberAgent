// InteractiveTurnRuntime — Chat/Novel 共用的单次交互运行 façade。
// provider、retry、transform、MessageStreamAccumulator 与 tool loop 仍由既有 Chat
// 深模块执行；本层只统一不可变 UI snapshot、generation/text-live 分离和传输资格判定。

import type { Conversation } from './conversation.ts';
import { currentMessages } from './conversation.ts';
import type { ChatStreamProvider, ChatTurnDeps, StreamOpts } from './chat_turn.ts';
import type { MessageChunk, StreamTransportState, UIMessage, UIMessagePart } from './message.ts';

export interface InteractiveTurnSnapshot {
  messages: UIMessage[];
  generationActive: boolean;
  textDeltasLive: boolean;
  transport: StreamTransportState;
  firstParsedDeltaAt: number | null;
  dataEndAt: number | null;
}

export interface InteractiveTurnHooks {
  onSnapshot?: (snapshot: InteractiveTurnSnapshot) => void;
}

export interface InteractiveTurnRunResult {
  conversation: Conversation;
  transport: StreamTransportState;
  firstParsedDeltaAt: number | null;
  dataEndAt: number | null;
}

export type InteractiveTurnOperation = (deps: ChatTurnDeps) => Promise<Conversation>;

const partContentLength = (part: UIMessagePart): number => {
  if (part.type === 'text') return part.text.length;
  if (part.type === 'reasoning') return part.reasoning.length;
  if (part.type === 'tool') {
    let length: number = part.toolName.length + part.input.length;
    for (const output of part.output) length += partContentLength(output);
    return length;
  }
  return 0;
};

interface AssistantContentMeasurement {
  message: UIMessage;
  length: number;
}

const assistantContentLength = (
  messages: UIMessage[], cache: Map<string, AssistantContentMeasurement>,
): number => {
  let length: number = 0;
  for (const message of messages) {
    if (message.role !== 'assistant') continue;
    const previous: AssistantContentMeasurement | undefined = cache.get(message.id);
    if (previous !== undefined && previous.message === message) {
      length += previous.length;
    } else {
      let measured: number = 0;
      for (const part of message.parts) measured += partContentLength(part);
      cache.set(message.id, { message, length: measured });
      length += measured;
    }
  }
  return length;
};

const copySnapshotMessages = (messages: UIMessage[]): UIMessage[] => messages.slice();

export const runInteractiveTurn = async (
  initialConversation: Conversation,
  deps: ChatTurnDeps,
  operation: InteractiveTurnOperation,
  hooks: InteractiveTurnHooks = {},
): Promise<InteractiveTurnRunResult> => {
  const nowMs: () => number = deps.nowMs ?? ((): number => Date.now());
  // UIMessage 不可变；同一 run 内复用历史计量，同 ID 的新对象仍重新计量。
  const contentMeasurements: Map<string, AssistantContentMeasurement> = new Map();
  const initialLength: number = assistantContentLength(currentMessages(initialConversation), contentMeasurements);
  const externalOnUpdate: ((messages: UIMessage[]) => void) | undefined = deps.onUpdate;
  let firstParsedDeltaAt: number | null = null;
  let dataEndAt: number | null = null;
  let requestBaselineLength: number = initialLength;
  let lastAssistantContentLength: number = initialLength;
  let liveQualified: boolean = false;
  let latestMessages: UIMessage[] = currentMessages(initialConversation);

  const emit = (
    messages: UIMessage[], generationActive: boolean,
    transport: StreamTransportState, dataEndAt: number | null,
  ): void => {
    if (hooks.onSnapshot === undefined) return;
    hooks.onSnapshot({
      messages: copySnapshotMessages(messages),
      generationActive,
      textDeltasLive: generationActive && transport === 'live' && dataEndAt === null,
      transport,
      firstParsedDeltaAt,
      dataEndAt,
    });
  };

  const classifyTransport = (): StreamTransportState => {
    if (deps.assistant.streamOutput === false || firstParsedDeltaAt === null || dataEndAt === null) {
      return 'unavailable';
    }
    return firstParsedDeltaAt < dataEndAt ? 'live' : 'buffered';
  };

  const recordDataEnd = (): void => {
    if (dataEndAt !== null) return;
    dataEndAt = nowMs();
    emit(latestMessages, true, classifyTransport(), dataEndAt);
  };

  const resetTransport = (): void => {
    firstParsedDeltaAt = null;
    dataEndAt = null;
    liveQualified = false;
  };

  const instrumentProvider = (source: ChatStreamProvider): ChatStreamProvider => {
    const provider: ChatStreamProvider = {
      streamText: (
        messages: UIMessage[], onChunk: (chunk: MessageChunk) => void, opts?: StreamOpts,
      ): Promise<void> => {
        resetTransport();
        requestBaselineLength = assistantContentLength(opts?.baselineMessages ?? latestMessages, contentMeasurements);
        lastAssistantContentLength = requestBaselineLength;
        return source.streamText(messages, onChunk, {
          signal: opts?.signal,
          onDataEnd: (): void => {
            opts?.onDataEnd?.();
            recordDataEnd();
          },
        });
      },
    };
    const generateText = source.generateText;
    if (generateText !== undefined) {
      provider.generateText = (messages: UIMessage[], opts?: StreamOpts) => generateText(messages, opts);
    }
    return provider;
  };
  const externalWrap = deps.wrapStreamProvider;

  const runtimeDeps: ChatTurnDeps = {
    ...deps,
    wrapStreamProvider: (source: ChatStreamProvider): ChatStreamProvider =>
      instrumentProvider(externalWrap !== undefined ? externalWrap(source) : source),
    onUpdate: (messages: UIMessage[]): void => {
      if (externalOnUpdate !== undefined) externalOnUpdate(messages);
      latestMessages = messages;
      const contentLength: number = assistantContentLength(messages, contentMeasurements);
      if (contentLength <= requestBaselineLength
        && (lastAssistantContentLength > requestBaselineLength
          || firstParsedDeltaAt !== null || dataEndAt !== null)) {
        resetTransport();
      } else if (contentLength > lastAssistantContentLength) {
        const updateAt: number = nowMs();
        if (firstParsedDeltaAt === null) {
          firstParsedDeltaAt = updateAt;
        } else if (dataEndAt === null && updateAt > firstParsedDeltaAt) {
          liveQualified = true;
        }
      }
      lastAssistantContentLength = contentLength;
      const transport: StreamTransportState = dataEndAt !== null
        ? classifyTransport()
        : (deps.assistant.streamOutput !== false && liveQualified ? 'live' : 'unavailable');
      emit(messages, true, transport, dataEndAt);
    },
  };

  try {
    const conversation: Conversation = await operation(runtimeDeps);
    const transport: StreamTransportState = classifyTransport();
    const finalMessages: UIMessage[] = currentMessages(conversation);
    latestMessages = finalMessages;
    emit(finalMessages, false, transport, dataEndAt);
    return { conversation, transport, firstParsedDeltaAt, dataEndAt };
  } catch (error) {
    const transport: StreamTransportState = classifyTransport();
    emit(latestMessages, false, transport, dataEndAt);
    throw error;
  }
};
