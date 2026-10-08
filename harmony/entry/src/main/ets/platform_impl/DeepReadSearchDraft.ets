import { SEARCH_SERVICE_TYPES } from '@amber/chat-domain';
import type { SearchSettings, SearchServiceOptionsType } from '@amber/chat-domain';

export interface DeepReadSearchEditorDraft {
  settings: SearchSettings;
  selectedId: string;
  dirty: boolean;
}

const EDITOR_DRAFT_KEY: string = 'deepreadSearchEditorDraft';

export const DEEPREAD_SEARCH_TYPES: SearchServiceOptionsType[] = [
  'bing_local', 'zhipu', 'tavily', 'exa', 'searxng', 'linkup', 'brave',
  'serper', 'serpapi', 'metaso', 'ollama', 'perplexity', 'firecrawl',
  'jina', 'bocha', 'amber_agent', 'grok',
];

export function deepReadSearchTitle(type: SearchServiceOptionsType): string {
  return type === 'bing_local' ? '免费多引擎聚合' : SEARCH_SERVICE_TYPES[type];
}

// Credentials stay in process memory; neither router params nor PersistentStorage receive this snapshot.
export function writeDeepReadSearchEditorDraft(draft: DeepReadSearchEditorDraft): void {
  AppStorage.setOrCreate<DeepReadSearchEditorDraft>(EDITOR_DRAFT_KEY,
    JSON.parse(JSON.stringify(draft)) as DeepReadSearchEditorDraft);
}

export function readDeepReadSearchEditorDraft(): DeepReadSearchEditorDraft | null {
  const draft: DeepReadSearchEditorDraft | undefined = AppStorage.get<DeepReadSearchEditorDraft>(EDITOR_DRAFT_KEY);
  return draft === undefined ? null : JSON.parse(JSON.stringify(draft)) as DeepReadSearchEditorDraft;
}

export function consumeDeepReadSearchEditorDraft(): DeepReadSearchEditorDraft | null {
  const draft: DeepReadSearchEditorDraft | null = readDeepReadSearchEditorDraft();
  AppStorage.delete(EDITOR_DRAFT_KEY);
  return draft;
}
