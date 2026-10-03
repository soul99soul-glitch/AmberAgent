import {
  ConversationRecapService, currentMessages, makeTextGenerationParams, makeUserMessage, toText, decodeStoredRecap,
  recapCustomBody, makeChatModel,
} from '@amber/chat-domain';
import type {
  Conversation, ConversationCompact, ConversationRecap, MessageChunk, RecapServiceDeps,
} from '@amber/chat-domain';
import {
  getChatKvStore, getChatRepository, getCompactStore, getConversationRunService,
  readTaskModelReference, resolveChatRuntime, resolveTaskModelReference, TASK_TITLE_MODEL_KEY,
} from '../di/AppContainer.ets';
import type { ChatProviderChoice, ChatRuntime } from '../di/AppContainer.ets';
import { beginConversationRun } from './ConversationRunGuard.ets';

const recapKey = (id: string): string => `conversation_recaps/${id}`;

const generateRecap = async (conversation: Conversation, prompt: string): Promise<string> => {
  const current: ChatRuntime | null = await resolveChatRuntime(conversation.assistantId);
  const choice: ChatProviderChoice | null = await resolveTaskModelReference(
    await readTaskModelReference(TASK_TITLE_MODEL_KEY), current === null ? undefined : current.resolvedChoice);
  if (choice === null) throw new Error('未配置可用的回顾模型');
  const runtime: ChatRuntime | null = current !== null && choice.model.id === current.resolvedChoice.model.id
    && choice.providerContainerId === current.resolvedChoice.providerContainerId
    ? current
    : await resolveChatRuntime(conversation.assistantId, choice.model.id, choice.providerContainerId);
  if (runtime === null) throw new Error('回顾模型已不可用');
  const chunk: MessageChunk = await runtime.api.generateText([makeUserMessage(prompt)], makeTextGenerationParams({
    model: makeChatModel({
      modelId: runtime.params.model.modelId, displayName: runtime.params.model.displayName,
      abilities: runtime.params.model.abilities, inputModalities: runtime.params.model.inputModalities,
      outputModalities: runtime.params.model.outputModalities, tools: [],
    }),
    temperature: runtime.params.temperature, topP: runtime.params.topP,
    customBody: recapCustomBody(runtime.params.customBody), tools: [], reasoningLevel: 'off',
  }));
  return chunk.choices.length === 0 ? '' : toText(chunk.choices[0].message);
};

const compactSummary = async (conversation: Conversation): Promise<string> => {
  const messageIds: Set<string> = new Set(currentMessages(conversation).map((message): string => message.id));
  const compacts: ConversationCompact[] = await getCompactStore().getCompacts(conversation.id);
  let latest: ConversationCompact | null = null;
  for (const compact of compacts) {
    if (compact.status !== 'completed' || compact.sourceMessageIds.length === 0
      || !compact.sourceMessageIds.every((id: string): boolean => messageIds.has(id))) continue;
    if (latest === null || compact.updatedAt > latest.updatedAt) latest = compact;
  }
  return latest === null ? '' : latest.summary;
};

let service: ConversationRecapService | null = null;
export const getConversationRecapService = (): ConversationRecapService => {
  if (service === null) {
    const deps: RecapServiceDeps = {
      loadConversation: (id: string): Promise<Conversation | null> => getChatRepository().getById(id),
      read: async (id: string): Promise<ConversationRecap | null> => {
        const raw: string | null = await getChatKvStore().get(recapKey(id));
        if (raw === null) return null;
        return decodeStoredRecap(raw, id);
      },
      write: async (recap: ConversationRecap, isCurrent: () => boolean): Promise<boolean> => {
        let written: boolean = false;
        await getConversationRunService().mutateStoredConversation(recap.conversationId,
          async (_latest: Conversation): Promise<Conversation | null> => {
            if (!isCurrent()) return null;
            await getChatKvStore().put(recapKey(recap.conversationId), JSON.stringify(recap));
            if (!isCurrent()) {
              await getChatKvStore().delete(recapKey(recap.conversationId));
              return null;
            }
            written = true;
            return null;
          });
        return written;
      },
      remove: (id: string): Promise<void> => getChatKvStore().delete(recapKey(id)),
      generate: generateRecap, compactSummary, begin: beginConversationRun,
    };
    service = new ConversationRecapService(deps);
  }
  return service;
};
