// Continue the original canonical assistant with a stored Responses GET, without adding a user or retrying a POST.
import type { Conversation } from './conversation.ts';
import { currentMessages } from './conversation.ts';
import { conversationWithMessagesAsNodes } from './context_engine.ts';
import type { ChatTurnDeps } from './chat_turn.ts';
import type { MessageChunk, UIMessage } from './message.ts';
import { finishAssistantMessage } from './message.ts';
import { MessageStreamAccumulator } from './stream_accumulator.ts';
import { applyOnGenerationFinish, applyVisualTransformersStreamingTail } from './transformer_pipeline.ts';

export const clearResumedNovelInterruption = (conversation: Conversation, assistantId: string): Conversation =>
  conversationWithMessagesAsNodes(conversation, currentMessages(conversation).map((message: UIMessage): UIMessage =>
    message.id === assistantId ? { ...message,
      annotations: message.annotations.filter(annotation => annotation.type !== 'generation_interrupted') } : message));

export const runResumedNovelResponse = async (conversation: Conversation, deps: ChatTurnDeps): Promise<Conversation> => {
  const messages: UIMessage[] = currentMessages(conversation);
  if (messages.length === 0) throw new Error('原 Responses 请求没有可恢复的 canonical 消息');
  const accumulator = new MessageStreamAccumulator(messages);
  const provider = deps.wrapStreamProvider !== undefined ? deps.wrapStreamProvider(deps.provider) : deps.provider;
  const context = { assistant: deps.assistant };
  const publish = (): void => {
    const raw: UIMessage[] = accumulator.snapshot();
    deps.onRawFlushSnapshot?.(raw);
    deps.onUpdate?.(applyVisualTransformersStreamingTail(raw, deps.outputTransformers, context));
  };
  try {
    await provider.streamText(messages, (chunk: MessageChunk): void => {
      if (deps.abortSignal?.aborted === true) return;
      accumulator.append(chunk);
      publish();
    }, { signal: deps.abortSignal, baselineMessages: messages });
  } finally { publish(); }
  if (deps.abortSignal?.aborted === true) throw new Error('cancelled');
  const finished: UIMessage[] = await applyOnGenerationFinish(accumulator.snapshot(), deps.outputTransformers, context);
  if (finished.length > 0 && finished[finished.length - 1].role === 'assistant') {
    finished[finished.length - 1] = finishAssistantMessage(finished[finished.length - 1]);
  }
  const result: Conversation = clearResumedNovelInterruption(
    conversationWithMessagesAsNodes(conversation, finished), messages[messages.length - 1].id);
  await deps.store.save(result);
  return result;
};
