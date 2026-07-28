// service_registry — getSearchService 分派(D-068c)
// Android 基准: search/.../SearchService.kt:46-66(companion getService when 分派)
// 偏差: Android 置于 companion object;此处独立模块以避免 search_service ↔ provider 循环依赖
import type { SearchService, SearchServiceOptions } from './search_service.ts';
import { bingSearchService } from './bing_service.ts';
import { zhipuSearchService } from './zhipu_service.ts';
import { tavilySearchService } from './tavily_service.ts';
import { exaSearchService } from './exa_service.ts';
import { searXNGService } from './searxng_service.ts';
import { linkUpService } from './linkup_service.ts';
import { braveSearchService } from './brave_service.ts';
import { serperSearchService } from './serper_service.ts';
import { serpApiSearchService } from './serpapi_service.ts';
import { metasoSearchService } from './metaso_service.ts';
import { ollamaSearchService } from './ollama_service.ts';
import { perplexitySearchService } from './perplexity_service.ts';
import { firecrawlSearchService } from './firecrawl_service.ts';
import { jinaSearchService } from './jina_service.ts';
import { bochaSearchService } from './bocha_service.ts';
import { amberAgentSearchService } from './amberagent_service.ts';
import { grokSearchService } from './grok_service.ts';

export const getSearchService = (options: SearchServiceOptions): SearchService => {
  switch (options.type) {
    case 'bing_local': return bingSearchService;
    case 'zhipu': return zhipuSearchService;
    case 'tavily': return tavilySearchService;
    case 'exa': return exaSearchService;
    case 'searxng': return searXNGService;
    case 'linkup': return linkUpService;
    case 'brave': return braveSearchService;
    case 'serper': return serperSearchService;
    case 'serpapi': return serpApiSearchService;
    case 'metaso': return metasoSearchService;
    case 'ollama': return ollamaSearchService;
    case 'perplexity': return perplexitySearchService;
    case 'firecrawl': return firecrawlSearchService;
    case 'jina': return jinaSearchService;
    case 'bocha': return bochaSearchService;
    case 'amber_agent': return amberAgentSearchService;
    case 'grok': return grokSearchService;
  }
};
