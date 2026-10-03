// Novel transcript compatibility boundary.
// The canonical payload is UIMessage; text is a display/collection projection only.

import type { UIMessage } from '../agent/message.ts';
import type { UIMessagePartText } from '../agent/message.ts';
import type { NovelMessage } from './models.ts';
import { makeNovelMessage } from './models.ts';

export const novelMessageUi = (message: NovelMessage): UIMessage => message.uiMessage;

export const novelMessageText = (message: NovelMessage): string => novelMessageUi(message).parts
  .filter((part): part is UIMessagePartText => part.type === 'text')
  .map((part: UIMessagePartText): string => part.text)
  .join('\n');

// 协议标记从 canonical 文本中剥离，保留 reasoning/tool/usage 等原有消息事实。
export const withNovelMessageText = (message: NovelMessage, content: string): NovelMessage => {
  let textWritten: boolean = false;
  const parts = message.uiMessage.parts.filter(part => {
    if (part.type !== 'text') return true;
    if (textWritten) return false;
    textWritten = true;
    return true;
  }).map(part => part.type === 'text' ? { ...part, text: content } : part);
  return makeNovelMessage({
    id: message.id, role: message.role, mode: message.mode,
    uiMessage: { ...message.uiMessage, parts }, createdAt: message.createdAt,
    granularity: message.granularity, collectedChapterId: message.collectedChapterId,
    interrupted: message.interrupted, candidate: message.candidate, runKind: message.runKind,
  });
};
