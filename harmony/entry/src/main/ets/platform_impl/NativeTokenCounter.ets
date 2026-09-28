import nativeTokens from 'libamber_native.so';
import type { UIMessage, ConversationCompact } from '@amber/chat-domain';
import { countNativeContextFootprint } from '@amber/chat-domain';

export const countNativeContextTokens = (
  messages: UIMessage[], compacts: ConversationCompact[], tokenizerId: string,
): Promise<number> => countNativeContextFootprint(messages, compacts, tokenizerId, nativeTokens.countBatch);
