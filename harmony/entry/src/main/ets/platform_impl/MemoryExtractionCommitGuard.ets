// Re-read mutable settings and the committed branch after worker model work.
import type { Conversation, UIMessage } from '@amber/chat-domain';
import { currentMessages, extractionMessagesContaminated } from '@amber/chat-domain';
import { getChatKvStore, getChatRepository } from '../di/AppContainer.ets';
import { loadAgentRuntimeSnapshot } from './AgentRuntimePrefs.ets';

const lastUser = (conversation: Conversation): UIMessage | null => {
  const messages: UIMessage[] = currentMessages(conversation);
  for (let index: number = messages.length - 1; index >= 0; index--) {
    if (messages[index].role === 'user') return messages[index];
  }
  return null;
};
export const canCommitMemoryExtraction = async (snapshot: Conversation): Promise<boolean> => {
  const worker = (await loadAgentRuntimeSnapshot(getChatKvStore())).memoryWorker;
  if (!worker.enabled || !worker.extractionEnabled) return false;
  const latest: Conversation | null = await getChatRepository().getById(snapshot.id);
  if (latest === null || extractionMessagesContaminated(currentMessages(latest))) return false;
  const original: UIMessage | null = lastUser(snapshot);
  const live: UIMessage | null = lastUser(latest);
  return original !== null && live !== null && original.id === live.id
    && original.createdAt === live.createdAt && JSON.stringify(original.parts) === JSON.stringify(live.parts);
};
