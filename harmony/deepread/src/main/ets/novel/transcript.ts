// Novel transcript compatibility boundary.
// The canonical payload is UIMessage; text is a display/collection projection only.

import type { UIMessage } from '../agent/message.ts';
import type { UIMessagePartText } from '../agent/message.ts';
import type { NovelMessage } from './models.ts';

export const novelMessageUi = (message: NovelMessage): UIMessage => message.uiMessage;

export const novelMessageText = (message: NovelMessage): string => novelMessageUi(message).parts
  .filter((part): part is UIMessagePartText => part.type === 'text')
  .map((part: UIMessagePartText): string => part.text)
  .join('\n');
