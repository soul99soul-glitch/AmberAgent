import type { SearchSettings, SearchServiceOptions, SearchServiceOptionsType } from '@amber/chat-domain';
import { makeSearchServiceOptions, getSearchService } from '@amber/chat-domain';
import { MOTION_OVERLAY } from '../../design/tokens.ets';
import { resolveSelectedSearchIndex } from './ChatSearchSettingsSession.ets';

export const SEARCH_TYPE_ORDER: SearchServiceOptionsType[] = [
  'bing_local', 'amber_agent', 'zhipu', 'tavily', 'exa', 'searxng', 'linkup',
  'brave', 'serper', 'serpapi', 'metaso', 'ollama', 'perplexity', 'firecrawl',
  'jina', 'bocha', 'grok',
];

const parseIntOrNull = (v: string): number | null => {
  const t: string = v.trim();
  if (!/^[+-]?\d+$/.test(t)) return null;
  const n: number = parseInt(t, 10);
  return Number.isNaN(n) ? null : n;
};

const distinct = (arr: string[]): string[] =>
  arr.filter((v: string, i: number): boolean => arr.indexOf(v) === i);

export interface ChatSearchServiceEditorPorts {
  applyPatch: (patch: (settings: SearchSettings) => void) => Promise<void>;
  serviceCount: () => number;
}

class SearchServiceEditConflict extends Error {}

// The page retains this draft/session across theme view remounts.
@Observed
export class ChatSearchServiceEditorSession {
  editorVisible: boolean = false;
  editorSaving: boolean = false;
  editorError: string = '';
  editIndex: number = -1;
  editTargetId: string = ''; // target.service.id(编辑时)
  typeMenuOpen: boolean = false;
  // Per-type draft fields stay first-level so ObjectLink observes each edit.
  dType: SearchServiceOptionsType = 'bing_local';
  dId: string = '';
  dApiKey: string = '';
  dDepth: string = '';
  dUrl: string = '';
  dEngines: string = '';
  dLanguage: string = '';
  dUsername: string = '';
  dPassword: string = '';
  dSearchUrl: string = '';
  dScrapeUrl: string = '';
  dModel: string = '';
  dCustomUrl: string = '';
  dSystemPrompt: string = '';
  dMaxTokens: string = '';
  dMaxTokensPerPage: string = '';
  dSummary: boolean = true;
  private pageAlive: boolean = false;
  private editorSession: number = 0;
  private ports: ChatSearchServiceEditorPorts | null = null;
  private get deps(): ChatSearchServiceEditorPorts { return this.ports as ChatSearchServiceEditorPorts; }

  start(ports: ChatSearchServiceEditorPorts): void { this.ports = ports; this.pageAlive = true; }
  dispose(): void { this.pageAlive = false; }

  closeEditor(): void {
    this.editorSession++;
    this.editorVisible = false;
    this.editorSaving = false;
  }

  openAdd(): void {
    this.editorSession++;
    this.editorSaving = false;
    this.editorError = '';
    // Android 新增 target:BingLocalOptions() 初始(:54-59)
    this.loadDraft(makeSearchServiceOptions('bing_local'));
    this.editIndex = -1;
    this.editTargetId = '';
    this.typeMenuOpen = false;
    this.editorVisible = true;
  }

  openEdit(service: SearchServiceOptions, index: number): void {
    this.editorSession++;
    this.editorSaving = false;
    this.editorError = '';
    this.loadDraft(service);
    this.editIndex = index;
    this.editTargetId = service.id;
    this.typeMenuOpen = false;
    this.editorVisible = true;
  }

  onTypeChange(t: SearchServiceOptionsType): void {
    const draft: SearchServiceOptions = makeSearchServiceOptions(t);
    animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
      this.loadDraft(draft);
      this.typeMenuOpen = false;
    });
  }

  loadDraft(o: SearchServiceOptions): void {
    this.dType = o.type;
    this.dId = o.id;
    this.dApiKey = '';
    this.dDepth = '';
    this.dUrl = '';
    this.dEngines = '';
    this.dLanguage = '';
    this.dUsername = '';
    this.dPassword = '';
    this.dSearchUrl = '';
    this.dScrapeUrl = '';
    this.dModel = '';
    this.dCustomUrl = '';
    this.dSystemPrompt = '';
    this.dMaxTokens = '';
    this.dMaxTokensPerPage = '';
    this.dSummary = true;
    switch (o.type) {
      case 'zhipu': this.dApiKey = o.apiKey; break;
      case 'tavily': this.dApiKey = o.apiKey; this.dDepth = o.depth; break;
      case 'exa': this.dApiKey = o.apiKey; break;
      case 'searxng':
        this.dUrl = o.url; this.dEngines = o.engines; this.dLanguage = o.language;
        this.dUsername = o.username; this.dPassword = o.password;
        break;
      case 'linkup': this.dApiKey = o.apiKey; this.dDepth = o.depth; break;
      case 'brave': this.dApiKey = o.apiKey; break;
      case 'serper': this.dApiKey = o.apiKey; break;
      case 'serpapi': this.dApiKey = o.apiKey; break;
      case 'metaso': this.dApiKey = o.apiKey; break;
      case 'ollama': this.dApiKey = o.apiKey; break;
      case 'perplexity':
        this.dApiKey = o.apiKey;
        // 显示:`maxTokens?.takeIf { it > 0 }?.toString() ?: ""`
        this.dMaxTokens = o.maxTokens !== null && o.maxTokens > 0 ? String(o.maxTokens) : '';
        this.dMaxTokensPerPage =
          o.maxTokensPerPage !== null && o.maxTokensPerPage > 0 ? String(o.maxTokensPerPage) : '';
        break;
      case 'firecrawl': this.dApiKey = o.apiKey; break;
      case 'jina':
        this.dApiKey = o.apiKey; this.dSearchUrl = o.searchUrl; this.dScrapeUrl = o.scrapeUrl;
        break;
      case 'bocha': this.dApiKey = o.apiKey; this.dSummary = o.summary; break;
      case 'amber_agent': this.dApiKey = o.apiKey; this.dDepth = o.depth; break;
      case 'grok':
        this.dApiKey = o.apiKey; this.dModel = o.model;
        this.dCustomUrl = o.customUrl; this.dSystemPrompt = o.systemPrompt;
        break;
      default: break; // bing_local 无字段
    }
  }

  draftOptions(): SearchServiceOptions {
    const id: string = this.dId;
    switch (this.dType) {
      case 'bing_local': return { type: 'bing_local', id };
      case 'zhipu': return { type: 'zhipu', id, apiKey: this.dApiKey };
      case 'tavily': return { type: 'tavily', id, apiKey: this.dApiKey, depth: this.dDepth };
      case 'exa': return { type: 'exa', id, apiKey: this.dApiKey };
      case 'searxng':
        return {
          type: 'searxng', id, url: this.dUrl, engines: this.dEngines,
          language: this.dLanguage, username: this.dUsername, password: this.dPassword,
        };
      case 'linkup': return { type: 'linkup', id, apiKey: this.dApiKey, depth: this.dDepth };
      case 'brave': return { type: 'brave', id, apiKey: this.dApiKey };
      case 'serper': return { type: 'serper', id, apiKey: this.dApiKey };
      case 'serpapi': return { type: 'serpapi', id, apiKey: this.dApiKey };
      case 'metaso': return { type: 'metaso', id, apiKey: this.dApiKey };
      case 'ollama': return { type: 'ollama', id, apiKey: this.dApiKey };
      case 'perplexity':
        return {
          type: 'perplexity', id, apiKey: this.dApiKey,
          maxTokens: parseIntOrNull(this.dMaxTokens),
          maxTokensPerPage: parseIntOrNull(this.dMaxTokensPerPage),
        };
      case 'firecrawl': return { type: 'firecrawl', id, apiKey: this.dApiKey };
      case 'jina':
        return {
          type: 'jina', id, apiKey: this.dApiKey,
          searchUrl: this.dSearchUrl.trim(), scrapeUrl: this.dScrapeUrl.trim(),
        };
      case 'bocha': return { type: 'bocha', id, apiKey: this.dApiKey, summary: this.dSummary };
      case 'amber_agent':
        return { type: 'amber_agent', id, apiKey: this.dApiKey, depth: this.dDepth };
      case 'grok':
        return {
          type: 'grok', id, apiKey: this.dApiKey, model: this.dModel,
          customUrl: this.dCustomUrl, systemPrompt: this.dSystemPrompt,
        };
    }
  }

  scrapeCapable(): boolean {
    return getSearchService(this.draftOptions()).scrapingParameters !== null;
  }

  hasApiKey(): boolean {
    return this.dType !== 'bing_local' && this.dType !== 'searxng';
  }

  hasDepth(): boolean {
    return this.dType === 'tavily' || this.dType === 'linkup' || this.dType === 'amber_agent';
  }

  depthOptions(): string[] {
    return this.dType === 'tavily' ? ['basic', 'advanced'] : ['standard', 'deep'];
  }

  async saveEditorPatch(patch: (n: SearchSettings) => void): Promise<void> {
    if (this.editorSaving) return;
    const session: number = this.editorSession;
    this.editorSaving = true;
    this.editorError = '';
    try {
      await this.deps.applyPatch(patch);
      if (this.pageAlive && session === this.editorSession) this.closeEditor();
    } catch (error) {
      if (this.pageAlive && session === this.editorSession) {
        this.editorError = error instanceof SearchServiceEditConflict
          ? error.message : '保存失败，请重试。草稿已保留。';
      }
    } finally {
      if (this.pageAlive && session === this.editorSession) this.editorSaving = false;
    }
  }

  confirmAdd(): void {
    const updated: SearchServiceOptions = this.draftOptions();
    this.saveEditorPatch((n: SearchSettings): void => {
      const ids: string[] = [updated.id];
      for (const oid of n.searchEnabledServiceIds) {
        if (ids.indexOf(oid) < 0) ids.push(oid);
      }
      n.searchServices = [updated].concat(n.searchServices);
      n.searchEnabledServiceIds = ids;
      n.searchServiceSelected = 0;
    });
  }

  confirmEdit(): void {
    const updated: SearchServiceOptions = this.draftOptions();
    const targetId: string = this.editTargetId;
    this.saveEditorPatch((n: SearchSettings): void => {
      const currentIndex: number = n.searchServices.findIndex(
        (x: SearchServiceOptions): boolean => x.id === targetId);
      if (currentIndex < 0) throw new SearchServiceEditConflict('该搜索服务已删除，请关闭编辑器后重新选择。草稿已保留。');
      const oldService: SearchServiceOptions = n.searchServices[currentIndex];
      const wasEnabled: boolean = n.searchEnabledServiceIds.indexOf(oldService.id) >= 0;
      const newServices: SearchServiceOptions[] = n.searchServices.slice();
      newServices[currentIndex] = updated;
      n.searchServices = newServices;
      if (wasEnabled) {
        const swapped: string[] = n.searchEnabledServiceIds
          .filter((id: string): boolean => id !== oldService.id);
        swapped.push(updated.id);
        n.searchEnabledServiceIds = distinct(swapped);
      }
    });
  }

  confirmDelete(): void {
    if (this.editIndex < 0) return;
    const targetId: string = this.editTargetId;
    this.saveEditorPatch((n: SearchSettings): void => {
      const currentIndex: number = n.searchServices.findIndex(
        (x: SearchServiceOptions): boolean => x.id === targetId);
      if (currentIndex < 0) throw new SearchServiceEditConflict('该搜索服务已删除，请关闭编辑器后重新选择。草稿已保留。');
      if (n.searchServices.length <= 1) throw new SearchServiceEditConflict('至少保留一个搜索服务。');
      const selectedServiceId: string | null =
        n.searchServiceSelected >= 0 && n.searchServiceSelected < n.searchServices.length
          ? n.searchServices[n.searchServiceSelected].id : null;
      const newServices: SearchServiceOptions[] = n.searchServices.slice();
      const deleted: SearchServiceOptions = newServices.splice(currentIndex, 1)[0];
      n.searchServices = newServices;
      n.searchServiceSelected = resolveSelectedSearchIndex(
        newServices, selectedServiceId === deleted.id ? null : selectedServiceId);
      n.searchEnabledServiceIds = n.searchEnabledServiceIds.filter(
        (id: string): boolean => id !== deleted.id);
    });
  }
}
