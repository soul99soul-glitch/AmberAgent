// A citation is evidence only after a successful run, from its new assistant text.
import type { UIMessage, UIMessagePart } from './message.ts';
import type { TailSafeOutputMessageTransformer } from './transformer_pipeline.ts';

const citationPattern = (): RegExp => /\[\[memory:(\d+)\]\]/g;

export const memoryCitationIds = (text: string, allowedIds: number[]): number[] => {
  const ids: number[] = [];
  const pattern = citationPattern();
  let match: RegExpExecArray | null = pattern.exec(text);
  while (match !== null) {
    const id: number = Number(match[1]);
    if (id > 0 && Number.isSafeInteger(id) && allowedIds.includes(id) && !ids.includes(id)) ids.push(id);
    match = pattern.exec(text);
  }
  return ids;
};

export const stripMemoryCitations = (text: string): string => text.replace(citationPattern(), '');

export const readMemoryCitationIds = (
  message: UIMessage, runId: string, allowedIds: number[],
): number[] => {
  if (message.role !== 'assistant') return [];
  const ids: number[] = [];
  for (const part of message.parts) {
    if (part.type !== 'text' || part.metadata?.['memoryCitationRunId'] !== runId) continue;
    const stored = part.metadata['memoryCitationIds'];
    if (!(stored instanceof Array)) continue;
    for (const value of stored) {
      const id: number = Number(value);
      if (value === id && Number.isSafeInteger(id) && id > 0 && allowedIds.includes(id) && !ids.includes(id)) ids.push(id);
    }
  }
  return ids;
};

export const createMemoryCitationTransformer = (
  runId: string, history: UIMessage[], allowedIds: () => number[],
): TailSafeOutputMessageTransformer => {
  const existing: Set<string> = new Set<string>(history.map((message: UIMessage): string => message.id));
  const finalized: Set<string> = new Set<string>();
  const transform = (message: UIMessage, final: boolean): UIMessage => {
    if (message.role !== 'assistant' || existing.has(message.id) || finalized.has(message.id)) return message;
    const parts: UIMessagePart[] = message.parts.map((part: UIMessagePart): UIMessagePart => {
      if (part.type !== 'text') return part;
      const text: string = stripMemoryCitations(part.text);
      if (!final) return text === part.text ? part : { ...part, text };
      return { ...part, text, metadata: { ...part.metadata,
        memoryCitationRunId: runId, memoryCitationIds: memoryCitationIds(part.text, allowedIds()) } };
    });
    if (final) finalized.add(message.id);
    return { ...message, parts };
  };
  return {
    visualTransform: (_ctx, messages) => messages.map((message: UIMessage): UIMessage => transform(message, false)),
    visualTransformTail: (_ctx, message) => transform(message, false),
    onGenerationFinish: (_ctx, messages) => messages.map((message: UIMessage): UIMessage => transform(message, true)),
  };
};
