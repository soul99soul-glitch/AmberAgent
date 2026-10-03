import type { Conversation, JevSettings, JevCompletionFacts, JevCompletionBatch, JevEvaluateResult } from '@amber/chat-domain';
import {
  currentMessages, loadJevSettings, resolveJevPurposeMode, jevPurposeConsentUnchanged,
  jevUnverifiedChanges, buildJevCompletionBatch, jevCompletionNeedsVerification,
} from '@amber/chat-domain';
import type { AbortSignalLike } from '@amber/deepread-domain';
import { getChatKvStore } from '../di/AppContainer.ets';
import { jevEvaluatePurpose } from './JevSupport.ets';

// Returns a volatile notice candidate; never writes conversation history.
export const evaluateJevCompletion = async (
  conversation: Conversation, signal: AbortSignalLike,
): Promise<JevCompletionFacts | null> => {
  if (signal.aborted) return null;
  const settings: JevSettings = await loadJevSettings(getChatKvStore());
  if (signal.aborted || resolveJevPurposeMode(settings, 'completion_check') === 'off') return null;
  const messages = currentMessages(conversation);
  const facts: JevCompletionFacts | null = jevUnverifiedChanges(messages);
  if (facts === null) return null;
  let task: string = '';
  for (let index: number = messages.length - 1; index >= 0; index--) {
    if (messages[index].role === 'user') {
      task = messages[index].parts.map((part): string => part.type === 'text' ? part.text : '').join('\n');
      break;
    }
  }
  const batch: JevCompletionBatch | null = buildJevCompletionBatch(settings, facts, task);
  if (batch === null) return null;
  const result: JevEvaluateResult = await jevEvaluatePurpose('completion_check', batch.state, batch.questions, signal, settings);
  if (signal.aborted || !result.ok || result.shadow || result.evaluation === null) return null;
  const latest: JevSettings = await loadJevSettings(getChatKvStore());
  if (signal.aborted || !jevPurposeConsentUnchanged(settings, latest, 'completion_check')) return null;
  return jevCompletionNeedsVerification(result.evaluation) ? facts : null;
};
