// Novel discussion shares the application's search settings and cancellation-aware tools.
import type { AgentTool, KeyValueStore, SearchSettings } from '@amber/chat-domain';
import { createSearchTools, loadSearchPrefs, SEARCH_PREFS_KEYS } from '@amber/chat-domain';
import { ensureSearchSdk } from './SearchSdkInit.ets';

export const loadNovelResearchEnabled = async (store: KeyValueStore): Promise<boolean> =>
  await store.get(SEARCH_PREFS_KEYS.enableWebSearch) === 'true';

export const saveNovelResearchEnabled = async (store: KeyValueStore, enabled: boolean): Promise<void> => {
  await store.put(SEARCH_PREFS_KEYS.enableWebSearch, enabled ? 'true' : 'false');
};

export const loadNovelResearchTools = async (store: KeyValueStore): Promise<AgentTool[]> => {
  const settings: SearchSettings = await loadSearchPrefs(store);
  if (!settings.enableWebSearch) return [];
  ensureSearchSdk();
  return createSearchTools(settings);
};
