import { hilog } from '@kit.PerformanceAnalysisKit';
import { conversationHasPendingOrUnexecutedTools, currentMessages, makeTextGenerationParams,
  makeUserMessage, memoryWorkerModelReferences, patchConversation, recapEligible, runAutoTitle, runMemoryExtraction,
  runSuggestion, toChatModel, toText, readMemoryCitationIds } from '@amber/chat-domain';
import type { AutoTitleDeps, Conversation, ConversationStore, MemoryExtractionDeps,
  MemoryWorkerModelResolution, MessageChunk, OpenAIChatApi, ReasoningLevel, SuggestionDeps, UIMessage } from '@amber/chat-domain';
import { prepareProviderApi, readTaskModelReference, TASK_TITLE_MODEL_KEY, TASK_SUGGESTION_MODEL_KEY,
  TASK_COMPRESS_MODEL_KEY, loadTaskPrompts, resolveTaskModelReference,
  resolveTaskModelReferenceChain, taskModelReferenceForChoice } from '../di/AppContainer.ets';
import type { ChatProviderChoice, TaskPrompts } from '../di/AppContainer.ets';
import type { DetachedConversationContext } from './ConversationRunService.ets';
import { systemLanguage } from './AgentRuntimePrefs.ets';
import { beginConversationRun } from './ConversationRunGuard.ets';
import { getMemoryExtractionStoreOps, getMemoryReadRepository } from './MemoryStore.ets';
import { canCommitMemoryExtraction } from './MemoryExtractionCommitGuard.ets';
import { notifyGenerationDone } from './NotificationNotifier.ets';
import { getConversationRecapService } from './ConversationRecapSupport.ets';

const HILOG_DOMAIN: number = 0x0000;
const HILOG_TAG: string = 'AmberChat';
interface TitlePatch { title: string; }
interface SuggestionsPatch { chatSuggestions: string[]; }
interface ErrorPatch { errorMsg: string; }

// The domain returns a candidate. Only the session mutation persists auxiliary fields.
const auxiliaryCandidateStore: ConversationStore = {
  save: (_candidate: Conversation): Promise<void> => Promise.resolve(),
};
const sameConversationTail = (left: Conversation, right: Conversation): boolean =>
  JSON.stringify(currentMessages(left)) === JSON.stringify(currentMessages(right));
const chunkText = (chunk: MessageChunk): string => {
  const message = chunk.choices.length > 0 ? chunk.choices[0].message : null;
  return message === null ? '' : toText(message);
};
const generateAuxiliaryText = async (
  choice: ChatProviderChoice, prompt: string, reasoningLevel: ReasoningLevel = 'auto',
): Promise<string> => {
  const headers: Record<string, string> = {};
  for (const header of choice.model.customHeaders) {
    const name: string = header.name.trim();
    if (name.length > 0) headers[name] = header.value.trim();
  }
  const api: OpenAIChatApi = await prepareProviderApi(choice.provider, headers);
  return chunkText(await api.generateText([makeUserMessage(prompt)], makeTextGenerationParams({
    model: toChatModel(choice.model), reasoningLevel, customBody: choice.model.customBodies,
  })));
};

const maybeAutoTitle = async (ctx: DetachedConversationContext, snapshot: Conversation): Promise<void> => {
  if (snapshot.title.trim().length > 0) return;
  let releaseRun: () => void;
  try { releaseRun = beginConversationRun(snapshot.id); } catch { return; }
  try {
    const prompts: TaskPrompts = await loadTaskPrompts();
    const deps: AutoTitleDeps = {
      generateText: async (prompt: string): Promise<string> => {
        const reference = await readTaskModelReference(TASK_TITLE_MODEL_KEY);
        const choice: ChatProviderChoice | null = await resolveTaskModelReference(
          reference, ctx.seed.runtime.resolvedChoice);
        if (choice === null) throw new Error('no title model');
        return generateAuxiliaryText(choice, prompt, 'off');
      },
      store: auxiliaryCandidateStore, locale: '简体中文', template: prompts.titlePrompt,
    };
    const titled: Conversation = await runAutoTitle(snapshot, deps);
    await ctx.mutateConversation((latest: Conversation): Conversation | null => {
      if (latest.title !== snapshot.title || !sameConversationTail(snapshot, latest)) return null;
      const patch: TitlePatch = { title: titled.title };
      return patchConversation(latest, patch);
    });
  } catch (error) {
    const patch: ErrorPatch = { errorMsg: `自动命名失败:${(error as Error).message}` };
    ctx.publish(patch);
  } finally { releaseRun(); }
};

const maybeSuggestions = async (ctx: DetachedConversationContext, snapshot: Conversation): Promise<void> => {
  let releaseRun: () => void;
  try { releaseRun = beginConversationRun(snapshot.id); } catch { return; }
  try {
    const prompts: TaskPrompts = await loadTaskPrompts();
    const deps: SuggestionDeps = {
      generateText: async (prompt: string): Promise<string> => {
        const reference = await readTaskModelReference(TASK_SUGGESTION_MODEL_KEY);
        const choice: ChatProviderChoice | null = await resolveTaskModelReference(
          reference, ctx.seed.runtime.resolvedChoice);
        if (choice === null) throw new Error('no suggestion model');
        return generateAuxiliaryText(choice, prompt, 'off');
      },
      store: auxiliaryCandidateStore, locale: '简体中文', template: prompts.suggestionPrompt,
    };
    const suggested: Conversation = await runSuggestion(snapshot, deps);
    await ctx.mutateConversation((latest: Conversation): Conversation | null => {
      if (!sameConversationTail(snapshot, latest)) return null;
      const patch: SuggestionsPatch = { chatSuggestions: suggested.chatSuggestions };
      return patchConversation(latest, patch);
    });
  } catch (error) {
    hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'suggestion failed: %{public}s', (error as Error).message);
  } finally { releaseRun(); }
};

const maybeMemoryExtraction = (ctx: DetachedConversationContext, finalized: Conversation): void => {
  if (conversationHasPendingOrUnexecutedTools(finalized)) return;
  const ops = getMemoryExtractionStoreOps();
  let choicePromise: Promise<ChatProviderChoice | null> | null = null;
  const resolveExtractionChoice = (): Promise<ChatProviderChoice | null> => {
    if (choicePromise === null) {
      choicePromise = (async (): Promise<ChatProviderChoice | null> => {
        const compressReference = await readTaskModelReference(TASK_COMPRESS_MODEL_KEY);
        const fallback: ChatProviderChoice = ctx.seed.runtime.resolvedChoice;
        return resolveTaskModelReferenceChain(memoryWorkerModelReferences(
          ctx.seed.agentRuntime.memoryWorker, compressReference, taskModelReferenceForChoice(fallback)), fallback);
      })();
    }
    return choicePromise;
  };
  const deps: MemoryExtractionDeps = {
    worker: ctx.seed.agentRuntime.memoryWorker, locale: systemLanguage(),
    resolveWorkerModel: async (): Promise<MemoryWorkerModelResolution> => {
      const choice: ChatProviderChoice | null = await resolveExtractionChoice();
      return choice === null ? { kind: 'no_model' } : { kind: 'ok', modelId: choice.model.modelId };
    },
    generateText: async (prompt: string): Promise<string> => {
      const choice: ChatProviderChoice | null = await resolveExtractionChoice();
      if (choice === null) throw new Error('no model');
      return generateAuxiliaryText(choice, prompt);
    },
    getAllActiveRecords: ops.getAllActiveRecords, addCandidates: ops.addCandidates,
    addCandidate: ops.addCandidate, addMemory: ops.addMemory, addEvent: ops.addEvent,
    countEventsSince: ops.countEventsSince,
    canApply: (): Promise<boolean> => canCommitMemoryExtraction(finalized),
    applyActions: (actions, now) => ops.applyActions(actions, now, async (): Promise<void> => {
      if (!await canCommitMemoryExtraction(finalized)) throw new Error('提炼来源或自动记忆设置已改变，结果未写入');
    }),
  };
  runMemoryExtraction(finalized, deps).catch((error: Error): void => {
    hilog.error(HILOG_DOMAIN, HILOG_TAG, 'memory extraction failed: %{public}s', error.message);
  });
};

export const completeConversationAuxiliary = (
  ctx: DetachedConversationContext, finalized: Conversation, manualToolCallId: string,
): void => {
  const reinforce = getMemoryReadRepository().reinforceMemories;
  if (reinforce !== undefined && ctx.recalledMemoryIds.length > 0) {
    for (const message of currentMessages(finalized)) {
      const ids: number[] = readMemoryCitationIds(message, ctx.runId, ctx.recalledMemoryIds);
      if (ids.length === 0) continue;
      reinforce(ids, message.id).catch((error: Error): void => {
        hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'memory reinforcement failed: %{public}s', error.message);
      });
    }
  }
  if (manualToolCallId.length === 0) {
    maybeAutoTitle(ctx, finalized);
    maybeSuggestions(ctx, finalized);
    maybeMemoryExtraction(ctx, finalized);
  }
  if (recapEligible(finalized) && !conversationHasPendingOrUnexecutedTools(finalized)) {
    const recapService = getConversationRecapService();
    recapService.request(finalized.id).then((): void => {
      const error: string = recapService.state(finalized.id).error;
      if (error.length > 0) hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'conversation recap failed: %{public}s', error);
    }).catch((error: Error): void => {
      hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'conversation recap failed: %{public}s', error.message);
    });
  }
  if (!ctx.seed.enableGenerationNotification || !(AppStorage.get<boolean>('appBackgrounded') ?? false)) return;
  const messages: UIMessage[] = currentMessages(finalized);
  const last: UIMessage | null = messages.length > 0 ? messages[messages.length - 1] : null;
  const text: string = last === null ? '' : toText(last).slice(0, 50).trim();
  notifyGenerationDone(ctx.seed.runtime.assistant.name, text);
};
