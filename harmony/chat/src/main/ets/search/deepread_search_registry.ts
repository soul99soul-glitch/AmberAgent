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
  const searchFree = async (
    queries: string[], started: number, signal?: AbortSignalLike,
    onFailure?: (query: string, error: string) => void,
  ): Promise<SearchHit[]> => {
    if (aborted(signal)) return [];
    const buckets = await Promise.all(deps.freeProviders.map(async (provider: SearchProvider): Promise<SearchHit[]> => {
      const remaining = 9000 - (Date.now() - started);
      if (aborted(signal) || remaining <= 0) return [];
      try {
        return await withinBudget(provider.search(queries, signal, onFailure), remaining);
      } catch (error) {
        if (!aborted(signal)) onFailure?.(queries[0] ?? '', `${provider.name}: ${String(error)}`);
        return [];
      }
    }));
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
  };
  if (selected === undefined || selected.type === 'bing_local') {
    return [{ name: 'free_aggregate', search: (queries, signal, onFailure): Promise<SearchHit[]> =>
      searchFree(queries, Date.now(), signal, onFailure) }];
  }
  const candidates: SearchServiceOptions[] = [selected];
  // Exhaust configured APIs before the Bing entry delegates to the free aggregate.
  for (const options of services) {
    if (options.id !== selected.id && options.type !== 'bing_local') candidates.push(options);
  }
  for (const options of services) {
    if (options.type === 'bing_local') candidates.push(options);
  }
  return [{ name: SEARCH_SERVICE_TYPES[selected.type],
    search: async (queries, signal, onFailure): Promise<SearchHit[]> => {
      if (aborted(signal)) return [];
      deps.ensureSdk();
      const hits: SearchHit[] = []; const started = Date.now();
      const picked = queries.filter(query => !/^https?:\/\//i.test(query));
      let candidateIndex = 0;
      for (let queryIndex = 0; queryIndex < picked.length; queryIndex++) {
        const query = picked[queryIndex];
        while (candidateIndex < candidates.length) {
          const remaining = 9000 - (Date.now() - started);
          if (aborted(signal) || remaining <= 0) return hits;
          const options = candidates[candidateIndex];
          if (options.type === 'bing_local') {
            return hits.concat(await searchFree(picked.slice(queryIndex), started, signal, onFailure));
          }
          try {
            const result = await withinBudget(getSearchService(options).search(
              { query }, settings.searchCommonOptions, options, { signal }), remaining);
            if (aborted(signal)) return hits;
            hits.push(...result.items.map(item => ({ title: item.title, url: item.url, snippet: item.text,
              source: SEARCH_SERVICE_TYPES[options.type], publishedAt: item.publishedAt })));
            break;
          } catch (error) {
            if (aborted(signal)) return hits;
            onFailure?.(query, `${SEARCH_SERVICE_TYPES[options.type]}: ${String(error)}`);
            // A failed configuration is not retried for later research angles in this run.
            candidateIndex++;
          }
        }
        if (candidateIndex >= candidates.length) break;
      }
      return hits;
    },
  }];
};
