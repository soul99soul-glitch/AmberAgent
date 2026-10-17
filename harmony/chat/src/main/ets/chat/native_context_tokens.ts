// Native BPE is used only for the existing Context display. Protocol and media
// overhead keep the same estimates as context_compact.ts.
import type {
  UIMessage, UIMessagePart, UIMessagePartText, UIMessagePartReasoning,
  UIMessagePartTool, UIMessagePartDocument, UIMessagePartMiniApp, UIMessagePartImage,
} from './message.ts';
import { CHAT_BLOB_MISSING_URL, CHAT_BLOB_MISSING_DATA_URL } from './conversation_blobs.ts';
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

const B64_ALPHABET: string = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

const decodeBase64Prefix = (payload: string, maxBytes: number): Uint8Array => {
  const out: Uint8Array = new Uint8Array(maxBytes);
  let buffer: number = 0;
  let bits: number = 0;
  let index: number = 0;
  for (let i: number = 0; i < payload.length && index < maxBytes; i++) {
    const value: number = B64_ALPHABET.indexOf(payload.charAt(i));
    if (value < 0) continue;
    buffer = (buffer << 6) | value;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out[index] = (buffer >> bits) & 0xff;
      index++;
    }
  }
  return out.slice(0, index);
};

const dimensionsOf = (url: string): { width: number; height: number } | null => {
  const comma: number = url.indexOf(',');
  if (comma < 0) return null;
  const bytes: Uint8Array = decodeBase64Prefix(url.substring(comma + 1), 512);
  if (bytes.length < 10) return null;
  // PNG: signature + IHDR width/height at 16..23.
  if (bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47 && bytes.length >= 24) {
    return {
      width: (bytes[16] << 24) | (bytes[17] << 16) | (bytes[18] << 8) | bytes[19],
      height: (bytes[20] << 24) | (bytes[21] << 16) | (bytes[22] << 8) | bytes[23],
    };
  }
  // GIF: little-endian logical screen width/height.
  if (bytes[0] === 0x47 && bytes[1] === 0x49 && bytes[2] === 0x46) {
    return { width: bytes[6] | (bytes[7] << 8), height: bytes[8] | (bytes[9] << 8) };
  }
  // JPEG: scan SOF0/1/2 for height/width.
  if (bytes[0] === 0xff && bytes[1] === 0xd8) {
    let i: number = 2;
    while (i + 9 < bytes.length) {
      if (bytes[i] !== 0xff) { i++; continue; }
      const marker: number = bytes[i + 1];
      if (marker === 0xd8 || marker === 0xd9 || marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
        i += 2;
        continue;
      }
      const length: number = (bytes[i + 2] << 8) | bytes[i + 3];
      if (marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc) {
        return { width: (bytes[i + 7] << 8) | bytes[i + 8], height: (bytes[i + 5] << 8) | bytes[i + 6] };
      }
      i += 2 + length;
    }
  }
  return null;
};

export const estimateImageTokens = (url: string): number => {
  if (url === CHAT_BLOB_MISSING_URL || url === CHAT_BLOB_MISSING_DATA_URL) return 0;
  if (url.startsWith('data:')) {
    const dimensions = dimensionsOf(url);
    if (dimensions !== null && dimensions.width > 0 && dimensions.height > 0) {
      return Math.ceil(dimensions.width * dimensions.height / 750) + 85;
    }
    const comma: number = url.indexOf(',');
    const payloadLength: number = comma >= 0 ? url.length - comma - 1 : url.length;
    return Math.max(85, Math.ceil(payloadLength * 3 / 4 / 8));
  }
  return 1600;
};

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
    case 'image': return estimateImageTokens((part as UIMessagePartImage).url);
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
