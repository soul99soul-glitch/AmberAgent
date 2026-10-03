import assert from 'node:assert/strict';
import test from 'node:test';
import fs from 'node:fs';
import ts from 'typescript';
import { defaultSearchPrefs, loadSearchPrefs, SEARCH_PREFS_KEYS } from '../main/ets/search/search_prefs.ts';
import { createSearchTools } from '../main/ets/search/search_tools.ts';
import { filterToolProfile } from '../main/ets/chat/tool_profile_filter.ts';
import type { AgentTool } from '../main/ets/chat/tool.ts';
import type { KeyValueStore } from '../main/ets/chat/kv_store.ts';

interface ResearchAPI {
  loadNovelResearchEnabled(store: KeyValueStore): Promise<boolean>;
  saveNovelResearchEnabled(store: KeyValueStore, enabled: boolean): Promise<void>;
  loadNovelResearchTools(store: KeyValueStore): Promise<AgentTool[]>;
}

const loadSupport = (initialize: () => void): ResearchAPI => {
  const source = fs.readFileSync(new URL('../../../entry/src/main/ets/platform_impl/NovelResearchSupport.ets', import.meta.url), 'utf8');
  const compiled = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const module = { exports: {} };
  new Function('require', 'exports', 'module', compiled)((id: string) => {
    if (id === '@amber/chat-domain') return { loadSearchPrefs, createSearchTools, SEARCH_PREFS_KEYS };
    if (id === './SearchSdkInit.ets') return { ensureSearchSdk: initialize };
    throw new Error(`unexpected import ${id}`);
  }, module.exports, module);
  return module.exports as ResearchAPI;
};

test('novel research respects the same search preference, defaults off, and enables real search tools', async () => {
  const values = new Map<string, string>();
  const store: KeyValueStore = {
    get: async key => values.get(key) ?? null,
    put: async (key, value) => { values.set(key, value); },
    delete: async key => { values.delete(key); },
  };
  let initialized = 0;
  const api = loadSupport(() => { initialized++; });
  assert.equal(await api.loadNovelResearchEnabled(store), false);
  assert.deepEqual(await api.loadNovelResearchTools(store), []);
  assert.equal(initialized, 0);
  await api.saveNovelResearchEnabled(store, true);
  assert.equal(values.get(SEARCH_PREFS_KEYS.enableWebSearch), 'true');
  assert.equal(await api.loadNovelResearchEnabled(store), true);
  const tools = await api.loadNovelResearchTools(store);
  assert.equal(initialized, 1);
  assert.deepEqual(tools.map(tool => tool.name), createSearchTools(defaultSearchPrefs()).map(tool => tool.name));
  // Shared profile definitions allow every research tool in web_read and full.
  assert.equal(filterToolProfile(tools, 'web_read').filteredCount, 0);
  const status = tools.find(tool => tool.name === 'search_sources_status');
  assert.ok(status);
  const output = await status.execute({});
  assert.ok(output[0]?.type === 'text' && JSON.parse(output[0].text));
  await api.saveNovelResearchEnabled(store, false);
  assert.deepEqual(await api.loadNovelResearchTools(store), []);
});

import { createNovelInteractiveAdapter } from '../main/ets/chat/novel_interactive_adapter.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import type { ChatStreamProvider } from '../main/ets/chat/chat_turn.ts';
import type { MessageChunk, UIMessage, UIMessagePart } from '../main/ets/chat/message.ts';
import type { NovelModelRequest, NovelModelEvent, HttpClient } from '@amber/deepread-domain';
import { initSearchSdk, makeSearchServiceOptions } from '../main/ets/search/search_service.ts';

const researchChunk = (parts: UIMessagePart[]): MessageChunk => ({
  id: 'chunk', model: 'model', choices: [{ index: 0, delta: {
    id: 'assistant', role: 'assistant', parts, annotations: [], createdAt: '2026-10-03T00:00:00Z',
    finishedAt: null, modelId: 'model', usage: null, translation: null,
  }, message: null, finishReason: 'unknown' }], usage: null,
});
const waitForTerminal = (adapter: ReturnType<typeof createNovelInteractiveAdapter>, request: NovelModelRequest): Promise<NovelModelEvent> =>
  new Promise(resolve => adapter.start(request).subscribe(event => {
    if (event.kind === 'completed' || event.kind === 'failed' || event.kind === 'waiting_user') resolve(event);
  }));
const researchRequest = (toolProfile: NovelModelRequest['toolProfile']): NovelModelRequest => ({
  runId: `research-${toolProfile}`, projectId: 'project', systemPrompt: '研究历史背景并给出来源链接。',
  maxOutputTokens: 2000, modelTarget: { kind: 'global' }, history: [],
  operation: { kind: 'turn', userPrompt: '查询古城建筑' }, toolProfile, checkpoint: async () => {},
});

test('novel discussion actually executes search and returns sources to the provider; writing none never exposes research', async () => {
  let searches = 0;
  const http: HttpClient = {
    fetch: async request => {
      assert.ok(request.url.startsWith('https://api.tavily.com/'));
      searches++;
      return { status: 200, headers: {}, body: JSON.stringify({ results: [
        { title: '古城', url: 'https://example.com/history', content: '城墙建于明代。' },
      ] }) };
    },
    fetchStream: async () => { throw new Error('not used'); },
  };
  initSearchSdk(http);
  const service = makeSearchServiceOptions('tavily');
  assert.equal(service.type, 'tavily');
  if (service.type === 'tavily') service.apiKey = 'test';
  const settings = { ...defaultSearchPrefs(), enableWebSearch: true, searchServices: [service],
    searchEnabledServiceIds: [service.id], searchBuiltinDuckDuckGoEnabled: false,
    searchBuiltinBingEnabled: false, searchBuiltinWikipediaEnabled: false,
    searchBuiltinHackerNewsEnabled: false, searchGoogleWebViewFallbackEnabled: false };
  const tools = createSearchTools(settings);
  let steps = 0;
  let providerSawSource = false;
  let exposed: string[] = [];
  const provider: ChatStreamProvider = {
    streamText: async (messages, onChunk) => {
      steps++;
      if (steps === 1) onChunk(researchChunk([{ type: 'tool', toolCallId: 'search-1', toolName: 'search_web',
        input: JSON.stringify({ query: '古城建筑', depth: 'quick', max_results: 1 }), output: [],
        approvalState: { type: 'auto' }, metadata: null }]));
      else {
        providerSawSource = JSON.stringify(messages).includes('https://example.com/history');
        onChunk(researchChunk([{ type: 'text', text: '城墙建于明代。[来源](https://example.com/history)', metadata: null }]));
      }
    },
  };
  const adapter = createNovelInteractiveAdapter({
    resolveRuntime: async () => ({ assistant: makeAssistant({}), provider, tools,
      makeProviderForStep: definitions => { exposed = definitions.map(tool => tool.name); return provider; } }),
    createAbortController: () => new AbortController(),
  });
  assert.equal((await waitForTerminal(adapter, researchRequest('all'))).kind, 'completed');
  assert.ok(exposed.includes('search_web'));
  assert.ok(searches > 0);
  assert.equal(providerSawSource, true);
  const before = searches;
  let noneSteps = 0;
  const noneProvider: ChatStreamProvider = { streamText: async (_messages, onChunk) => {
    noneSteps++;
    onChunk(researchChunk([{ type: 'text', text: '正文', metadata: null }]));
  } };
  const writer = createNovelInteractiveAdapter({
    resolveRuntime: async () => ({ assistant: makeAssistant({}), provider: noneProvider, tools,
      makeProviderForStep: () => { assert.fail('writing must not expose any tools'); } }),
    createAbortController: () => new AbortController(),
  });
  assert.equal((await waitForTerminal(writer, researchRequest('none'))).kind, 'completed');
  assert.equal(noneSteps, 1);
  assert.equal(searches, before);
});
