// 将该次请求档位随新思考内容保存；旧消息缺失档位时保持未知。
import type { UIMessage, UIMessagePart } from './message.ts';
import type { ReasoningLevel } from './provider_model.ts';
import type { TailSafeOutputMessageTransformer } from './transformer_pipeline.ts';

export const createReasoningLevelMetadataTransformer = (
  level: ReasoningLevel, history: UIMessage[],
): TailSafeOutputMessageTransformer => {
  const existing = new Set<string>();
  for (const message of history) {
    existing.add(`${message.id}/${message.createdAt}`);
    for (const part of message.parts) {
      if (part.type === 'reasoning') existing.add(`${message.id}/${part.createdAt}`);
    }
  }
  const annotate = (message: UIMessage): UIMessage => {
    if (message.role !== 'assistant') return message;
    let changed = false;
    const parts = message.parts.map((part: UIMessagePart): UIMessagePart => {
      if (part.type !== 'reasoning' || part.metadata?.['reasoningLevel'] !== undefined ||
        existing.has(`${message.id}/${part.createdAt}`)) return part;
      changed = true;
      return { ...part, metadata: { ...part.metadata, reasoningLevel: level } };
    });
    return changed ? { ...message, parts } : message;
  };
  return {
    visualTransform: (_ctx, messages) => messages.map(annotate),
    visualTransformTail: (_ctx, message) => annotate(message),
    onGenerationFinish: (_ctx, messages) => messages.map(annotate),
  };
};
