// grok_service — Grok(xAI Responses API)搜索(D-068c)
// Android 基准: search/.../GrokSearchService.kt(全文 178 行)
// 偏差: Log.i(query) 省略(隐私:用户查询不入日志,章程禁止)
import type { SearchRequestContext } from './search_service.ts';
import type { JsonObject, JsonValue } from '../chat/json.ts';
import { makeInputSchemaObj } from '../chat/tool.ts';
import type {
  GrokOptions, ScrapedResult, SearchCommonOptions, SearchResult,
  SearchResultItem, SearchService, SearchServiceOptions,
} from './search_service.ts';
import { searchSdkRuntime } from './search_service.ts';
import { asArray, asRecord, requireQuery, strOrNull } from './json_pick.ts';

const asOptions = (o: SearchServiceOptions): GrokOptions => {
  if (o.type !== 'grok') throw new Error('GrokSearchService requires GrokOptions');
  return o;
};

export const grokSearchService: SearchService = {
  name: 'Grok',

  parameters: makeInputSchemaObj(
    { query: { type: 'string', description: 'The question to ask, can be a natural language question' } },
    ['query'],
  ),

  scrapingParameters: null,

  search: async (
    params: JsonObject, commonOptions: SearchCommonOptions, serviceOptions: SearchServiceOptions,
    context?: SearchRequestContext,
  ): Promise<SearchResult> => {
    const opts: GrokOptions = asOptions(serviceOptions);
    if (opts.apiKey.trim().length === 0) throw new Error('Grok API key is required');
    const query: string = requireQuery(params);

    const body: JsonObject = {
      model: opts.model,
      input: [
        { role: 'system', content: opts.systemPrompt },
        { role: 'user', content: query },
      ],
      tools: [
        { type: 'web_search' },
        { type: 'x_search' },
      ],
      store: false,
    };

    const resp = await searchSdkRuntime().http.fetch({
      url: opts.customUrl,
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${opts.apiKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(body),
    }, context);
    if (resp.status < 200 || resp.status >= 300) {
      throw new Error(`response failed #${resp.status}: ${resp.body}`);
    }
    const payload: JsonObject = asRecord(JSON.parse(resp.body) as JsonObject);
    let answer: string | null = null;
    let textContent: JsonObject | null = null;
    for (const v of asArray(payload['output'])) {
      const o: JsonObject = asRecord(v);
      if (strOrNull(o['type']) === 'message' && strOrNull(o['role']) === 'assistant') {
        for (const c of asArray(o['content'])) {
          const co: JsonObject = asRecord(c);
          if (strOrNull(co['type']) === 'output_text') {
            textContent = co;
            break;
          }
        }
        break;
      }
    }
    if (textContent !== null) answer = strOrNull(textContent['text']);
    const items: SearchResultItem[] = [];
    const seen: string[] = [];
    if (textContent !== null) {
      for (const a of asArray(textContent['annotations'])) {
        const ao: JsonObject = asRecord(a);
        if (strOrNull(ao['type']) !== 'url_citation') continue;
        const u: string | null = strOrNull(ao['url']);
        if (u === null || u.trim().length === 0) continue;
        if (seen.includes(u)) continue;
        seen.push(u);
        if (items.length >= commonOptions.resultSize) break;
        items.push({ title: u, url: u, text: '', publishedAt: null, images: [] });
      }
    }
    return { answer, items };
  },

  scrape: (
    _params: JsonObject, _commonOptions: SearchCommonOptions, serviceOptions: SearchServiceOptions,
  ): Promise<ScrapedResult> => {
    asOptions(serviceOptions);
    return Promise.reject(new Error('Scraping is not supported for Grok'));
  },
};
