import { JevContextRuntime, loadJevSettings, resolveJevPurposeMode } from '@amber/chat-domain';
import type { JevContextPolicy, JevContextRun, JevSettings } from '@amber/chat-domain';
import type { AbortSignalLike } from '@amber/deepread-domain';
import { getChatKvStore } from '../di/AppContainer.ets';
import { EntryAbortController } from './EntryAbortController.ets';
import { jevEvaluatePurpose } from './JevSupport.ets';

const loadPolicy = async (): Promise<JevContextPolicy> => {
  const settings: JevSettings = await loadJevSettings(getChatKvStore());
  return {
    settingsSnapshot: settings,
    selectionMode: resolveJevPurposeMode(settings, 'tool_context_selection'),
    retentionMode: resolveJevPurposeMode(settings, 'context_retention'),
    selectionAllowed: settings.toolContextSelection.allowToolOutput,
    retentionAllowed: settings.contextRetention.allowToolOutput,
    selectionTaskText: settings.toolContextSelection.allowTaskText,
    selectionToolMetadata: settings.toolContextSelection.allowToolMetadata,
    retentionTaskText: settings.contextRetention.allowTaskText,
    retentionToolMetadata: settings.contextRetention.allowToolMetadata,
    selectionConsentKey: JSON.stringify({ mode: settings.mode, apiMode: settings.apiMode, model: settings.model,
      apiKey: settings.apiKey, baseUrl: settings.baseUrl, purpose: settings.toolContextSelection }),
    retentionConsentKey: JSON.stringify({ mode: settings.mode, apiMode: settings.apiMode, model: settings.model,
      apiKey: settings.apiKey, baseUrl: settings.baseUrl, purpose: settings.contextRetention }),
  };
};

// Page/service sessions may be released; completed decisions follow the conversation.
const runtime: JevContextRuntime = new JevContextRuntime({
  loadPolicy,
  evaluate: jevEvaluatePurpose,
  createController: (): EntryAbortController => new EntryAbortController(),
});

export const beginJevContextRun = (
  conversationId: string, runId: string, signal?: AbortSignalLike,
): JevContextRun => runtime.beginRun(conversationId, runId, signal);
