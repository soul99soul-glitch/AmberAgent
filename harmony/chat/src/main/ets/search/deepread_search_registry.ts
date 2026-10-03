// DeepRead uses its selected enabled service, or the ordinary free sources plus native Google.
import type { AbortSignalLike, SearchHit, SearchProvider } from '@amber/deepread-domain';
import type { SearchSettings } from './search_aggregator.ts';
import { enabledServices } from './search_aggregator.ts';
import { getSearchService } from './service_registry.ts';
import { SEARCH_SERVICE_TYPES } from './search_service.ts';
import type { SearchServiceOptions } from './search_service.ts';

export type DeepReadSearchGoogle = (query: string, resultSize: number, signal?: AbortSignalLike) => Promise<SearchHit[]>;
export interface DeepReadSearchProviderDependencies {
  freeProviders: SearchProvider[];
  ensureSdk: () => void;
  searchGoogle?: DeepReadSearchGoogle;
}
const aborted = (signal?: AbortSignalLike): boolean => signal?.aborted === true;
const usable = (options: SearchServiceOptions): boolean => options.type === 'bing_local'
  || (options.type === 'searxng' ? options.url.trim().length > 0 : options.apiKey.trim().length > 0);
const mergeHits = (buckets: SearchHit[][]): SearchHit[] => {
  const result: SearchHit[] = []; const seen = new Set<string>();
  const length = Math.max(0, ...buckets.map(bucket => bucket.length));
  for (let i = 0; i < length; i++) for (const bucket of buckets) {
    const hit = bucket[i];
    if (hit !== undefined && !seen.has(hit.url)) { seen.add(hit.url); result.push(hit); }
  }
  return result;
};
const withinBudget = <T>(operation: Promise<T>, remainingMs: number): Promise<T> => new Promise((resolve, reject) => {
  const timer = setTimeout(() => reject(new Error('provider budget exceeded')), remainingMs);
  operation.then(value => { clearTimeout(timer); resolve(value); }, error => { clearTimeout(timer); reject(error); });
});

export const createDeepReadSearchProviders = (
  settings: SearchSettings, deps: DeepReadSearchProviderDependencies, preferredSearchId?: string | null,
): SearchProvider[] => {
  const services = enabledServices(settings).filter(usable);
  const preferred = preferredSearchId === undefined ? settings.searchServices[settings.searchServiceSelected]?.id : preferredSearchId;
  const selected = services.find(options => options.id === preferred) ?? services[0];
  if (selected !== undefined && selected.type !== 'bing_local') return [{ name: SEARCH_SERVICE_TYPES[selected.type],
    search: async (queries, signal, onFailure): Promise<SearchHit[]> => {
      if (aborted(signal)) return [];
      deps.ensureSdk(); const hits: SearchHit[] = []; const started = Date.now();
      for (const query of queries.filter(query => !/^https?:\/\//i.test(query))) {
        const remaining = 9000 - (Date.now() - started);
        if (aborted(signal) || remaining <= 0) break;
        try {
          const result = await withinBudget(getSearchService(selected).search({ query }, settings.searchCommonOptions, selected, { signal }), remaining);
          if (aborted(signal)) break;
          hits.push(...result.items.map(item => ({ title: item.title, url: item.url, snippet: item.text,
            source: SEARCH_SERVICE_TYPES[selected.type], publishedAt: item.publishedAt })));
        } catch (error) {
          if (aborted(signal)) break;
          onFailure?.(query, `${SEARCH_SERVICE_TYPES[selected.type]}: ${String(error)}`);
        }
      }
      return hits;
    },
  }];
  return [{ name: 'free_aggregate', search: async (queries, signal, onFailure): Promise<SearchHit[]> => {
    if (aborted(signal)) return [];
    const started = Date.now();
    const buckets = await Promise.all(deps.freeProviders.map(provider => provider.search(queries, signal, onFailure).catch(error => {
      if (!aborted(signal)) onFailure?.(queries[0] ?? '', `${provider.name}: ${String(error)}`);
      return [] as SearchHit[];
    })));
    let hits = mergeHits(buckets);
    if (hits.length < 3 && settings.searchGoogleWebViewFallbackEnabled && deps.searchGoogle !== undefined) {
      for (const query of queries.filter(query => !/^https?:\/\//i.test(query))) {
        const remaining = 9000 - (Date.now() - started);
        if (aborted(signal) || remaining <= 0) break;
        try {
          const google = await withinBudget(deps.searchGoogle(query, settings.searchCommonOptions.resultSize, signal), remaining);
          if (aborted(signal)) break;
          hits = mergeHits([hits, google]);
        } catch (error) {
          if (aborted(signal)) break;
          onFailure?.(query, `Google WebView: ${String(error)}`);
        }
      }
    }
    return hits;
  } }];
};
