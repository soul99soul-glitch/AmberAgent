import type { AutoApprovalReview, UIMessage, JevSettings, JsonObject, JevQuestion, JevEvaluateResult } from '@amber/chat-domain';
import { createJevAutoApprovalReview, loadJevSettings } from '@amber/chat-domain';
import type { AbortSignalLike } from '@amber/deepread-domain';
import { getChatKvStore } from '../di/AppContainer.ets';
import { jevEvaluatePurpose } from './JevSupport.ets';

// A run/seat/generation receives its own immutable task snapshot and dispatcher cache.
// Outputs, reasoning and previous tools never enter this purpose's external request.
const userTextsFromMessages = (messages: UIMessage[]): string[] => {
  const userTexts: string[] = [];
  for (const message of messages) {
    if (message.role !== 'user') continue;
    let text: string = '';
    for (const part of message.parts) if (part.type === 'text') text += part.text + '\n';
    userTexts.push(text.trim());
  }
  return userTexts;
};
export const createEntryJevAutoApprovalReview = (
  messages: UIMessage[], currentMessagesProvider?: () => UIMessage[],
): AutoApprovalReview => {
  const reviewFor = (selected: UIMessage[]): AutoApprovalReview => createJevAutoApprovalReview({
    recentUserTexts: userTextsFromMessages(selected),
    loadSettings: (): Promise<JevSettings> => loadJevSettings(getChatKvStore()),
    evaluate: (state: JsonObject, questions: Record<string, JevQuestion>, signal?: AbortSignalLike, expectedSettings?: JevSettings): Promise<JevEvaluateResult> =>
      jevEvaluatePurpose('auto_approval', state, questions, signal, expectedSettings),
  });
  if (currentMessagesProvider === undefined) return reviewFor(messages);
  // The existing save barrier updates this run's context before first review.
  // This includes the accepted send/steer or regenerated prefix, excluding future branches.
  return (part, decision, signal): Promise<string[]> => reviewFor(currentMessagesProvider())(part, decision, signal);
};
