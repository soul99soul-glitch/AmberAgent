import type { KeyValueStore, SearchSettings, SearchServiceOptions } from '@amber/chat-domain';
import { defaultSearchPrefs, loadSearchPrefs, updateSearchPrefs,
  searchServiceOptionsToJson, searchServiceOptionsFromJson, MAX_SEARCH_RESULTS } from '@amber/chat-domain';

export const resolveSelectedSearchIndex = (
  services: SearchServiceOptions[], selectedServiceId: string | null,
): number => {
  if (services.length === 0) return 0;
  if (selectedServiceId !== null) {
    const idx: number = services.findIndex(
      (x: SearchServiceOptions): boolean => x.id === selectedServiceId);
    if (idx >= 0) return idx;
  }
  return 0;
};

// updateSearchPrefs compares current/next JSON, so the transform must not mutate current.
const cloneSearchSettings = (s: SearchSettings): SearchSettings => ({
  enableWebSearch: s.enableWebSearch,
  searchCommonOptions: { resultSize: s.searchCommonOptions.resultSize },
  searchServices: s.searchServices.map(
    (o: SearchServiceOptions): SearchServiceOptions =>
      searchServiceOptionsFromJson(searchServiceOptionsToJson(o))),
  searchServiceSelected: s.searchServiceSelected,
  searchEnabledServiceIds: s.searchEnabledServiceIds.slice(),
  searchBuiltinDuckDuckGoEnabled: s.searchBuiltinDuckDuckGoEnabled,
  searchBuiltinBingEnabled: s.searchBuiltinBingEnabled,
  searchBuiltinJinaEnabled: s.searchBuiltinJinaEnabled,
  searchBuiltinWikipediaEnabled: s.searchBuiltinWikipediaEnabled,
  searchBuiltinHackerNewsEnabled: s.searchBuiltinHackerNewsEnabled,
  searchGoogleWebViewFallbackEnabled: s.searchGoogleWebViewFallbackEnabled,
});

// This owner publishes complete preference snapshots; the domain prefs queue owns writes.
@Observed
export class ChatSearchSettingsSession {
  loaded: boolean = false;
  error: string = '';
  s: SearchSettings = defaultSearchPrefs();
  private kv: KeyValueStore | null = null;
  private pageAlive: boolean = false;

  start(kv: KeyValueStore): void { this.kv = kv; this.pageAlive = true; }
  dispose(): void { this.pageAlive = false; }

  async refresh(): Promise<void> {
    const s: SearchSettings = await loadSearchPrefs(this.kv as KeyValueStore);
    if (!this.pageAlive) return;
    this.s = s;
    this.loaded = true;
  }

  async applyPatch(patch: (n: SearchSettings) => void): Promise<void> {
    await updateSearchPrefs(this.kv as KeyValueStore, (c: SearchSettings): SearchSettings => {
      const n: SearchSettings = cloneSearchSettings(c);
      patch(n);
      return n;
    });
    await this.refresh();
  }

  private savePreference(patch: (n: SearchSettings) => void): void {
    this.applyPatch(patch).then((): void => {
      if (this.pageAlive) this.error = '';
    }).catch((): void => {
      if (this.pageAlive) this.error = '保存搜索设置失败，请重试。';
    });
  }

  enabledCount(): number {
    let count: number = 0;
    for (const svc of this.s.searchServices) {
      if (this.s.searchEnabledServiceIds.indexOf(svc.id) >= 0) count++;
    }
    if (this.s.searchBuiltinJinaEnabled) count++;
    if (this.s.searchBuiltinDuckDuckGoEnabled) count++;
    if (this.s.searchBuiltinBingEnabled) count++;
    if (this.s.searchBuiltinWikipediaEnabled) count++;
    if (this.s.searchBuiltinHackerNewsEnabled) count++;
    if (this.s.searchGoogleWebViewFallbackEnabled) count++;
    return count;
  }

  setMaster(enabled: boolean): void {
    this.savePreference((n: SearchSettings): void => { n.enableWebSearch = enabled; });
  }

  setBuiltin(field: string, enabled: boolean): void {
    this.savePreference((n: SearchSettings): void => {
      if (field === 'jina') n.searchBuiltinJinaEnabled = enabled;
      else if (field === 'duckduckgo') n.searchBuiltinDuckDuckGoEnabled = enabled;
      else if (field === 'bing') n.searchBuiltinBingEnabled = enabled;
      else if (field === 'wikipedia') n.searchBuiltinWikipediaEnabled = enabled;
      else if (field === 'hackernews') n.searchBuiltinHackerNewsEnabled = enabled;
      else n.searchGoogleWebViewFallbackEnabled = enabled;
    });
  }

  builtinValue(field: string): boolean {
    if (field === 'jina') return this.s.searchBuiltinJinaEnabled;
    if (field === 'duckduckgo') return this.s.searchBuiltinDuckDuckGoEnabled;
    if (field === 'bing') return this.s.searchBuiltinBingEnabled;
    if (field === 'wikipedia') return this.s.searchBuiltinWikipediaEnabled;
    if (field === 'hackernews') return this.s.searchBuiltinHackerNewsEnabled;
    return this.s.searchGoogleWebViewFallbackEnabled;
  }

  setResultSize(value: number): void {
    const clamped: number = Math.min(MAX_SEARCH_RESULTS, Math.max(1, value));
    this.savePreference((n: SearchSettings): void => {
      n.searchCommonOptions = { resultSize: clamped };
    });
  }

  adjustResultSize(delta: number): void {
    this.savePreference((n: SearchSettings): void => {
      n.searchCommonOptions = {
        resultSize: Math.min(MAX_SEARCH_RESULTS, Math.max(1, n.searchCommonOptions.resultSize + delta)),
      };
    });
  }

  moveService(serviceId: string, delta: number): void {
    this.savePreference((n: SearchSettings): void => {
      const fromIndex: number = n.searchServices.findIndex(
        (service: SearchServiceOptions): boolean => service.id === serviceId);
      const toIndex: number = fromIndex + delta;
      if (fromIndex < 0 || toIndex < 0 || toIndex >= n.searchServices.length) return;
      const selectedServiceId: string | null =
        n.searchServiceSelected >= 0 && n.searchServiceSelected < n.searchServices.length
          ? n.searchServices[n.searchServiceSelected].id : null;
      const newServices: SearchServiceOptions[] = n.searchServices.slice();
      const item: SearchServiceOptions = newServices.splice(fromIndex, 1)[0];
      newServices.splice(toIndex, 0, item);
      n.searchServices = newServices;
      n.searchServiceSelected = resolveSelectedSearchIndex(newServices, selectedServiceId);
    });
  }

  toggleService(service: SearchServiceOptions, enabled: boolean): void {
    this.savePreference((n: SearchSettings): void => {
      if (enabled) {
        const ids: string[] = n.searchEnabledServiceIds.slice();
        if (ids.indexOf(service.id) < 0) ids.push(service.id);
        n.searchEnabledServiceIds = ids;
      } else {
        n.searchEnabledServiceIds = n.searchEnabledServiceIds.filter(
          (id: string): boolean => id !== service.id);
      }
    });
  }
}
