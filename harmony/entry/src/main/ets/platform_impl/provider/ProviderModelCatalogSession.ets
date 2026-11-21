import type {
  ProviderSetting, ProviderSettingGoogle, GoogleCatalogModel, GoogleModelCatalog, OpenAIAuthMode
} from '@amber/chat-domain';
import { openAIAuthHeaders } from '@amber/chat-domain';
import type { HttpClient, AbortSignalLike } from '@amber/deepread-domain';
import { EntryAbortController } from '../EntryAbortController.ets';

export interface ProviderCatalogDraft {
  provider: ProviderSetting | null;
  authMode: string;
  apiKey: string;
  baseUrl: string;
  modelsText: string;
  vertexAI: boolean;
}
export interface ProviderModelCatalogPorts {
  currentDraft: () => ProviderCatalogDraft;
  setModelsText: (text: string) => void;
  saveModels?: () => void;
  httpClient: HttpClient;
  loadGoogleModelCatalog: (provider: ProviderSettingGoogle, signal: AbortSignalLike) => Promise<GoogleModelCatalog>;
}

@Observed
export class ProviderModelCatalogSession {
  fetchBusy: boolean = false;
  fetchMsg: string = '';
  fetchedIds: string[] = [];
  private allFetchedIds: string[] = [];
  private googleCatalogController: EntryAbortController | null = null;
  private googleFetchedModels: GoogleCatalogModel[] = [];
  private ports: ProviderModelCatalogPorts | null = null;
  private get deps(): ProviderModelCatalogPorts { return this.ports as ProviderModelCatalogPorts; }
  private get provider(): ProviderSetting | null { return this.deps.currentDraft().provider; }
  private get authMode(): string { return this.deps.currentDraft().authMode; }
  private get apiKey(): string { return this.deps.currentDraft().apiKey; }
  private get baseUrl(): string { return this.deps.currentDraft().baseUrl; }
  private get vertexAI(): boolean { return this.deps.currentDraft().vertexAI; }
  private get modelsText(): string { return this.deps.currentDraft().modelsText; }
  private set modelsText(value: string) { this.deps.setModelsText(value); }
  start(ports: ProviderModelCatalogPorts): void { this.ports = ports; }
  dispose(): void {
    this.googleCatalogController?.abort();
    this.googleCatalogController = null;
  }
  catalogModel(id: string): GoogleCatalogModel | undefined {
    return this.googleFetchedModels.find((model: GoogleCatalogModel): boolean => model.modelId === id);
  }

  fetchModels(): void {
    const raw: ProviderSetting | null = this.provider;
    if (raw === null || this.fetchBusy) return;
    if (raw.type === 'google' && raw.authMode === 'api_key' && !raw.useServiceAccount && this.vertexAI) {
      this.fetchMsg = 'Vertex API Key 模式请手动填写模型 ID。';
      return;
    }
    if (raw.type === 'openai' && (this.authMode === 'codex_oauth' || this.authMode === 'grok_oauth')) {
      this.fetchMsg = '账号登录时会添加可用模型，也可以手动填写模型 ID。';
      return;
    }
    this.fetchBusy = true;
    this.fetchMsg = '正在拉取…';
    if (raw.type === 'google' && (raw.authMode !== 'api_key' || raw.useServiceAccount)) {
      const controller: EntryAbortController = new EntryAbortController();
      this.googleCatalogController = controller;
      this.deps.loadGoogleModelCatalog(raw as ProviderSettingGoogle, controller.signal).then((catalog: GoogleModelCatalog): void => {
        if (controller.signal.aborted) return;
        this.googleFetchedModels = catalog.models;
        this.allFetchedIds = catalog.models.map((model): string => model.modelId);
        this.syncFetchedIds();
        this.fetchMsg = !catalog.supported ? catalog.message
          : (this.fetchedIds.length > 0 ? `发现 ${catalog.models.length} 个模型，${this.fetchedIds.length} 个可添加`
            : `发现 ${catalog.models.length} 个模型，全部已在列表中`);
      }).catch((error: Error): void => {
        if (!controller.signal.aborted) this.fetchMsg = error instanceof Error ? `拉取失败：${error.message}` : '拉取失败';
      }).finally((): void => {
        if (this.googleCatalogController === controller) this.googleCatalogController = null;
        this.fetchBusy = false;
      });
      return;
    }
    let headers: Record<string, string> = {};
    if (raw.type === 'claude') {
      headers['x-api-key'] = this.apiKey.trim();
      headers['anthropic-version'] = '2023-06-01';
    } else if (raw.type === 'google') {
      headers['x-goog-api-key'] = this.apiKey.trim();
    } else {
      headers = openAIAuthHeaders(this.authMode as OpenAIAuthMode, this.baseUrl.trim(), this.apiKey.trim());
    }
    this.deps.httpClient.fetch({
      url: `${this.baseUrl.trim()}/models`,
      method: 'GET',
      headers,
    }, { signal: undefined }).then((resp): void => {
      this.fetchBusy = false;
      if (resp.status < 200 || resp.status >= 300) {
        this.fetchMsg = `拉取失败(${resp.status}): ${resp.body.substring(0, 120)}`;
        return;
      }
      const ids: string[] = this.parseModelIds(resp.body, raw.type);
      if (ids.length === 0) {
        this.fetchMsg = '返回为空或无法解析';
        return;
      }
      this.allFetchedIds = ids;
      this.syncFetchedIds();
      this.fetchMsg = this.fetchedIds.length > 0
        ? `发现 ${ids.length} 个模型，${this.fetchedIds.length} 个可添加`
        : `发现 ${ids.length} 个模型，全部已在列表中`;
    }).catch((e: Error): void => {
      this.fetchBusy = false;
      this.fetchMsg = `拉取失败:${e.message}`;
    });
  }

  parseModelIds(body: string, providerType: string): string[] {
    try {
      const parsed = JSON.parse(body) as Record<string, Object>;
      if (providerType === 'google') {
        const models = parsed['models'] as Object[];
        if (!Array.isArray(models)) return [];
        const out: string[] = [];
        for (const item of models) {
          const obj = item as Record<string, Object>;
          const name: string = String(obj['name'] ?? '');
          if (name.length > 0) out.push(name.startsWith('models/') ? name.substring(7) : name);
        }
        return out;
      }
      const data = parsed['data'] as Object[];
      if (!Array.isArray(data)) return [];
      const out: string[] = [];
      for (const item of data) {
        const obj = item as Record<string, Object>;
        const id: string = String(obj['id'] ?? '');
        if (id.length > 0) out.push(id);
      }
      return out;
    } catch (_e) {
      return [];
    }
  }

  private currentIds(): string[] {
    const out: string[] = [];
    for (const part of this.modelsText.split(/[,\n]/)) {
      const id: string = part.trim();
      if (id.length > 0 && out.indexOf(id) < 0) out.push(id);
    }
    return out;
  }

  private syncFetchedIds(): void {
    const existing: string[] = this.currentIds();
    this.fetchedIds = this.allFetchedIds.filter((id: string): boolean => existing.indexOf(id) < 0);
  }

  private commitIds(ids: string[]): void {
    this.modelsText = ids.join(', ');
    this.syncFetchedIds();
    this.deps.saveModels?.();
  }

  addFetchedId(id: string): void {
    const parts: string[] = this.currentIds();
    if (parts.indexOf(id) >= 0) return;
    parts.push(id);
    this.commitIds(parts);
  }

  addAllFetched(): void {
    const parts: string[] = this.currentIds();
    let changed: boolean = false;
    for (const id of this.fetchedIds) {
      if (parts.indexOf(id) < 0) {
        parts.push(id);
        changed = true;
      }
    }
    if (!changed) return;
    this.commitIds(parts);
  }

  addManualIds(rawInput: string): boolean {
    const parts: string[] = this.currentIds();
    let added: boolean = false;
    for (const raw of rawInput.split(/[,\n]/)) {
      const id: string = raw.trim();
      if (id.length > 0 && parts.indexOf(id) < 0) {
        parts.push(id);
        added = true;
      }
    }
    if (!added) return false;
    this.commitIds(parts);
    return true;
  }

  removeModelId(modelId: string): void {
    const parts: string[] = this.currentIds().filter((id: string): boolean => id !== modelId);
    this.commitIds(parts);
  }
}
