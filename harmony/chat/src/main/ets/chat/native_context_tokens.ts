// Native BPE is used only for the existing Context display. Protocol and media
// overhead keep the same estimates as context_compact.ts.
import type {
  UIMessage, UIMessagePart, UIMessagePartText, UIMessagePartReasoning,
  UIMessagePartTool, UIMessagePartDocument, UIMessagePartMiniApp,
} from './message.ts';
import { makeSystemMessage } from './message.ts';
import type { ConversationCompact } from './context_compact.ts';
import {
  compactInjectionText, validCompletedCompacts, selectCompactsForInjection,
} from './context_compact.ts';

// OpenAI's tiktoken model table. Unknown deployment aliases and o200k_harmony
// models deliberately retain the existing weighted-character estimate.
export const nativeTokenizerForModel = (modelId: string): string | null => {
  if (modelId.startsWith('gpt-5') || /^(gpt-4\.1|gpt-4o|o1|o3|o4-mini)(-|$)/.test(modelId)
    || /^(gpt-4\.5-|chatgpt-4o-|ft:gpt-4o)/.test(modelId)) return 'o200k_base';
  if (modelId === 'gpt-3.5' || /^(gpt-4|gpt-3\.5-turbo|gpt-35-turbo)(-|$)/.test(modelId)
    || /^(ft:gpt-4|ft:gpt-3\.5-turbo)/.test(modelId)) return 'cl100k_base';
  return null;
};

export type ContextTokenBatchCounter = (ids: string[], texts: string[]) => Promise<number[]>;

interface FootprintGroup {
  textStart: number;
  textEnd: number;
  fixedChars: number;
  messageCount: number;
}

const collectPart = (part: UIMessagePart, texts: string[]): number => {
  switch (part.type) {
    case 'text': texts.push((part as UIMessagePartText).text); return 0;
    case 'reasoning': texts.push((part as UIMessagePartReasoning).reasoning); return 0;
    case 'tool': {
      const tool: UIMessagePartTool = part as UIMessagePartTool;
      texts.push(tool.input);
      let fixedChars: number = 0;
      for (const output of tool.output) fixedChars += collectPart(output, texts);
      return fixedChars;
    }
    case 'image':
    case 'video':
    case 'audio': return 4500;
    case 'document': texts.push((part as UIMessagePartDocument).fileName); return 80;
    case 'mini_app': {
      const app: UIMessagePartMiniApp = part as UIMessagePartMiniApp;
      texts.push(app.title, app.description);
      return 120;
    }
    default: return 0;
  }
};

const collectGroup = (messages: UIMessage[], texts: string[]): FootprintGroup => {
  const start: number = texts.length;
  let fixedChars: number = 0;
  for (const message of messages) {
    fixedChars += message.role.length;
    for (const part of message.parts) fixedChars += collectPart(part, texts);
  }
  return { textStart: start, textEnd: texts.length, fixedChars, messageCount: messages.length };
};

export const countNativeContextFootprint = async (
  messages: UIMessage[], activeCompacts: ConversationCompact[], tokenizerId: string,
  countBatch: ContextTokenBatchCounter,
): Promise<number> => {
  const texts: string[] = [];
  const groups: FootprintGroup[] = [];
  const existingIds: Set<string> = new Set<string>(messages.map((message: UIMessage): string => message.id));
  const completed: ConversationCompact[] = validCompletedCompacts(activeCompacts, existingIds);
  if (completed.length === 0) {
    groups.push(collectGroup(messages, texts));
  } else {
    const coveredIds: Set<string> = new Set<string>();
    for (const compact of completed) {
      for (const id of compact.sourceMessageIds) coveredIds.add(id);
    }
    const summaries: UIMessage[] = selectCompactsForInjection(activeCompacts, existingIds)
      .map((compact: ConversationCompact): UIMessage => makeSystemMessage(compactInjectionText(compact)));
    groups.push(collectGroup(summaries, texts));
    groups.push(collectGroup(messages.filter((message: UIMessage): boolean => !coveredIds.has(message.id)), texts));
  }
  const counts: number[] = await countBatch(texts.map((): string => tokenizerId), texts);
  let total: number = 0;
  for (const group of groups) {
    let textTokens: number = 0;
    for (let i: number = group.textStart; i < group.textEnd; i++) textTokens += counts[i];
    total += Math.max(textTokens + Math.floor(group.fixedChars / 4), group.messageCount * 4);
  }
  return total;
};
