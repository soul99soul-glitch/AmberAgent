// search_tools — 搜索工具组 search_web/search_sources_status/search_strategy_explain/scrape_web(D-070)
// Android 基准: app/.../core/ai/tools/SearchTools.kt(全文 256 行)
// 偏差:Tool → AgentTool(chat HAR 既有形状);LocalDate.now().toLocalString(true) → deps.today 注入
import type { JsonObject, JsonValue } from '../chat/json.ts';
import type { AbortSignalLike } from '@amber/deepread-domain';
import type { UIMessagePart } from '../chat/message.ts';
import type { AgentTool, InputSchemaObj } from '../chat/tool.ts';
import { makeAgentTool, makeInputSchemaObj } from '../chat/tool.ts';
import type { ScrapedResult, SearchServiceOptions } from './search_service.ts';
import {
  SEARCH_SERVICE_TYPES, makeSearchServiceOptions, scrapedResultToJson,
} from './search_service.ts';
import type { SearchSettings } from './search_aggregator.ts';
import { enabledServices } from './search_aggregator.ts';
import type { OrchestratorDeps } from './search_orchestrator.ts';
import {
  searchOrchestratorExplain, searchOrchestratorSearch, searchOrchestratorStatus,
} from './search_orchestrator.ts';
import { getSearchService } from './service_registry.ts';
import { stringContentOrNull } from './json_pick.ts';

const asInputObject = (input: JsonValue): JsonObject =>
  (typeof input === 'object' && input !== null && !Array.isArray(input))
    ? input as JsonObject
    : {};

// :149-216
const searchWebParameters = (): InputSchemaObj =>
  makeInputSchemaObj(
    {
      query: { type: 'string', description: 'search keyword' },
      topic: {
        type: 'string',
        description: 'search topic',
        enum: ['general', 'news', 'market', 'technical', 'finance'],
      },
      time_range: {
        type: 'string',
        description: 'recency window for current/news searches',
        enum: ['day', 'week', 'month', 'year', 'any'],
      },
      recency_days: { type: 'integer', description: 'optional exact recency window in days' },
      max_results: { type: 'integer', description: 'maximum merged results to return' },
      depth: {
        type: 'string',
        description: 'search depth: quick uses fewer variants, standard rewrites queries, deep adds more variants and WebView fallback hints',
        enum: ['quick', 'standard', 'deep'],
      },
      allow_webview: {
        type: 'boolean',
        description: 'whether to return WebView search fallback suggestions when ordinary sources are weak',
      },
      services: {
        type: 'array',
        description: 'optional enabled service names or ids to use',
        items: { type: 'string' },
      },
      preferred_sources: {
        type: 'array',
        description: 'optional source names or ids to prefer; alias of services',
        items: { type: 'string' },
      },
    },
    ['query'],
  );

// :218-232
const scrapeWebParameters = (): InputSchemaObj =>
  makeInputSchemaObj(
    {
      url: { type: 'string', description: 'url to scrape' },
      service: {
        type: 'string',
        description: 'optional enabled service name or id that supports scraping',
      },
    },
    ['url'],
  );

// :234-237
const scrapeEnabledServices = (settings: SearchSettings): SearchServiceOptions[] =>
  enabledServices(settings)
    .filter((o: SearchServiceOptions) => getSearchService(o).scrapingParameters !== null);

// :239-256
const resolveScrapeService = (settings: SearchSettings, input: JsonObject): SearchServiceOptions => {
  const requested: string | null = stringContentOrNull(input, 'service');
  const candidates: SearchServiceOptions[] = scrapeEnabledServices(settings);
  let selected: SearchServiceOptions | null = null;
  if (requested === null || requested.trim().length === 0) {
    const indexed: SearchServiceOptions | undefined =
      settings.searchServices[settings.searchServiceSelected];
    if (indexed !== undefined && candidates.some((c) => c.id === indexed.id)) {
      selected = indexed;
    } else if (candidates.length > 0) {
      selected = candidates[0];
    } else if (settings.searchBuiltinJinaEnabled) {
      selected = makeSearchServiceOptions('jina');
    }
  } else {
    const withScrape: SearchServiceOptions | undefined =
      enabledServices(settings, [requested])
        .find((o: SearchServiceOptions) => getSearchService(o).scrapingParameters !== null);
    if (withScrape !== undefined) {
      selected = withScrape;
    } else if (
      settings.searchBuiltinJinaEnabled &&
      ['jina', 'jina reader', 'jina_reader', 'jina builtin']
        .some((selector: string) => requested.toLowerCase().includes(selector))
    ) {
      selected = makeSearchServiceOptions('jina');
    }
  }
  if (selected === null) {
    throw new Error('No enabled search service supports scraping. Enable Jina Reader or another scraping-capable search service in settings.');
  }
  return selected;
};

// :23-147
const abortError = (): Error => {
  const err: Error = new Error('search tool aborted');
  err.name = 'AbortError';
  return err;
};

const raceAbort = <T>(run: Promise<T>, signal: AbortSignalLike): Promise<T> =>
  new Promise<T>((resolve, reject): void => {
    let listener: (() => void) | null = null;
    const settle = (fn: () => void): void => {
      if (listener !== null && signal.removeEventListener !== undefined) {
        signal.removeEventListener('abort', listener);
      }
      fn();
    };
    if (signal.addEventListener !== undefined) {
      listener = (): void => { settle((): void => { reject(abortError()); }); };
      signal.addEventListener('abort', listener);
      if (signal.aborted) listener();
    }
    run.then(
      (value: T): void => { settle((): void => { resolve(value); }); },
      (e: Error): void => { settle((): void => { reject(e); }); },
    );
  });

export const createSearchTools = (settings: SearchSettings, deps: OrchestratorDeps = {}): AgentTool[] => {
  const today: string = (deps.today ?? ((): string => new Date().toISOString().substring(0, 10)))();
  const enabled: SearchServiceOptions[] = enabledServices(settings);
  const enabledServiceNames: string =
    enabled.map((o: SearchServiceOptions) => SEARCH_SERVICE_TYPES[o.type] ?? 'Search').join(', ');
  const builtinList: string[] = [];
  if (settings.searchBuiltinJinaEnabled) builtinList.push('Jina Reader (scrape only)');
  if (settings.searchBuiltinDuckDuckGoEnabled) builtinList.push('Free web (DuckDuckGo, Brave, 360, Quark)');
  if (settings.searchBuiltinBingEnabled) builtinList.push('Bing');
  if (settings.searchBuiltinWikipediaEnabled) builtinList.push('Wikipedia');
  if (settings.searchBuiltinHackerNewsEnabled) builtinList.push('Hacker News');
  if (settings.searchGoogleWebViewFallbackEnabled) builtinList.push('Google WebView fallback');
  const builtinStatus: string = builtinList.join(', ');
  const namesPart: string = enabledServiceNames.trim().length > 0 ? enabledServiceNames : 'none';
  const builtinPart: string = builtinStatus.trim().length > 0 ? builtinStatus : 'none';

  // :38-71 trimIndent 逐字
  const searchWebDescription: string = [
    'Search the web through AmberAgent Search Orchestrator.',
    'It uses enabled API services first, then built-in free public/vertical sources such as DuckDuckGo, Brave, Bing, 360, Quark, Wikipedia, and Hacker News as fallback/cross-check. Blocked public engines are skipped automatically.',
    'Use this when the user asks for the latest news, current facts, or needs verification.',
    `Enabled configured services: ${namesPart}.`,
    `Built-in sources: ${builtinPart}.`,
    'For news/current events, set `topic=news` and choose `time_range` (`day` for today/latest, `week` for recent).',
    'For market/sales/share questions, set `topic=market`; the orchestrator will generate English market-data variants.',
    'Generate focused keywords and run multiple searches when the topic is broad or likely to have gaps.',
    'If snippets are not enough, call scrape_web on the most relevant source pages before answering.',
    'If ordinary sources are blocked or weak, set `allow_webview=true` or call webview_search_open using webview_fallback suggestions.',
    `Today is ${today}.`,
    '',
    'Response format:',
    '- items[].id (short id), title, url, text, source_service, source_services, duplicate_count',
    '- items[].images[] (optional): relevant image URLs from the search results',
    '- sources[].service, status, result_count, error',
    '',
    'Citations:',
    '- Prefer natural Markdown source links, e.g. `[Reuters](https://www.reuters.com/...)`, after the sentence.',
    '- Multiple source links are allowed.',
    '- Legacy `[citation,domain](id)` citations are still accepted for compatibility.',
    '- If no results are cited, omit source links.',
    '',
    'IMPORTANT — Images:',
    'Do not embed images with Markdown image syntax like `![](url)`.',
    'Do not write internal image-rendering fences or code blocks.',
    'If items include images, AmberAgent handles the visual rendering separately.',
    'Your job is to write the answer text and attach source links for the sources you used.',
    '',
    'Example:',
    'The capital of France is Paris. [example.com](https://example.com/paris)',
    'The population is about 2.1 million. [example.com](https://example.com/paris) [example2.com](https://example2.com/france)',
  ].join('\n');

  const tools: AgentTool[] = [
    makeAgentTool({
      name: 'search_web',
      description: searchWebDescription,
      parameters: searchWebParameters,
      execute: async (input: JsonValue, signal?: AbortSignalLike): Promise<UIMessagePart[]> => {
        // Each run owns its signal; stopping also cancels the actual provider HTTP.
        if (signal !== undefined && signal.aborted) {
          throw abortError();
        }
        const run: Promise<JsonObject> =
          searchOrchestratorSearch(settings, asInputObject(input), undefined, deps, { signal });
        if (signal === undefined) {
          const results: JsonObject = await run;
          return [{ type: 'text', text: JSON.stringify(results), metadata: null }];
        }
        const results: JsonObject = await raceAbort(run, signal);
        return [{ type: 'text', text: JSON.stringify(results), metadata: null }];
      },
    }),
    makeAgentTool({
      name: 'search_sources_status',
      description: 'Return enabled Search Orchestrator sources, including configured API sources, built-in free public sources, and WebView fallback status.',
      parameters: (): InputSchemaObj => makeInputSchemaObj({}),
      execute: (): Promise<UIMessagePart[]> =>
        Promise.resolve([{
          type: 'text', text: JSON.stringify(searchOrchestratorStatus(settings)), metadata: null,
        }]),
    }),
    makeAgentTool({
      name: 'search_strategy_explain',
      description: 'Explain how search_web would rewrite this query, choose sources, and decide whether WebView fallback is available. It does not perform a search.',
      parameters: searchWebParameters,
      execute: (input: JsonValue): Promise<UIMessagePart[]> =>
        Promise.resolve([{
          type: 'text',
          text: JSON.stringify(searchOrchestratorExplain(settings, asInputObject(input), deps)),
          metadata: null,
        }]),
    }),
  ];

  // :108-145 scrape_web 条件加入
  if (scrapeEnabledServices(settings).length > 0 || settings.searchBuiltinJinaEnabled) {
    tools.push(makeAgentTool({
      name: 'scrape_web',
      description: [
        'Scrape a URL for detailed page content.',
        'Built-in Jina Reader is available without an API key and is preferred when no configured scraping service is selected.',
        'Use this when the user requests content from a specific page or when search snippets are insufficient.',
        'Avoid using it for common questions unless the user asks.',
      ].join('\n'),
      parameters: scrapeWebParameters,
      execute: async (input: JsonValue, signal?: AbortSignalLike): Promise<UIMessagePart[]> => {
        if (signal !== undefined && signal.aborted) throw abortError();
        const obj: JsonObject = asInputObject(input);
        const options: SearchServiceOptions = resolveScrapeService(settings, obj);
        const run: Promise<ScrapedResult> = getSearchService(options).scrape(
          obj, settings.searchCommonOptions, options, { signal },
        );
        const result: ScrapedResult = signal === undefined ? await run : await raceAbort(run, signal);
        const payload: JsonObject = scrapedResultToJson(result);
        return [{ type: 'text', text: JSON.stringify(payload), metadata: null }];
      },
    }));
  }
  return tools;
};
