import type { AssistantRegex } from './assistant.ts';
import type { UIMessagePart } from './message.ts';
import { replaceRegexes } from './transformers.ts';

// Apply once at history admission, never when enqueueing or draining an
// already accepted message. Attachments and text metadata retain their identity.
export const applyUserInputRegexes = (
  parts: UIMessagePart[], rules: AssistantRegex[],
): UIMessagePart[] => {
  if (rules.length === 0) return parts;
  return parts.map((part: UIMessagePart): UIMessagePart => {
    if (part.type !== 'text') return part;
    const text: string = replaceRegexes(part.text, rules, 'user', false);
    return text === part.text ? part : { type: 'text', text, metadata: part.metadata };
  });
};
