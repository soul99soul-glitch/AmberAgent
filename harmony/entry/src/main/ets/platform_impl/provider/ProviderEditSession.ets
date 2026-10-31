import type {
  ProviderSetting, ProviderSettingGoogle, ProviderSettingClaude, ProviderSettingOpenAIVariant, ProviderModel, GoogleCatalogModel, KeyValueStore, OpenAIAuthMode
} from '@amber/chat-domain';
import {
  loadProviders, saveProviders, makeProviderSettingGoogle, makeProviderSettingClaude, makeProviderSettingOpenAIVariant, makeProviderModel, openAIAuthModeFixedBaseUrl
} from '@amber/chat-domain';
import type { CodexTokens } from '../CodexOAuthClient.ets';
import type { GrokTokens } from '../GrokOAuthClient.ets';

export interface ProviderEditPorts {
  kvStore: KeyValueStore;
  loadCodexTokens: (providerId: string) => Promise<CodexTokens | null>;
  loadGrokTokens: (providerId: string) => Promise<GrokTokens | null>;
  clearCodexTokens?: (providerId: string) => Promise<void>;
  clearGrokTokens?: (providerId: string) => Promise<void>;
  catalogModel: (modelId: string) => GoogleCatalogModel | undefined;
  onProviderApplied: (provider: ProviderSetting) => void;
  cleanupDeletedGoogleProvider: (provider: ProviderSettingGoogle) => Promise<void>;
  cleanupWarning: (message: string) => void;
}

@Observed
export class ProviderEditSession {
  provider: ProviderSetting | null = null;
  loadError: string = '';
  loading: boolean = true;
  providerEnabled: boolean = true;
  name: string = '';
  baseUrl: string = '';
  apiKey: string = '';
  modelsText: string = '';
  chatPath: string = '/chat/completions';
  useResponseApi: boolean = false;
  vertexAI: boolean = false;
  useServiceAccount: boolean = false;
  serviceAccountEmail: string = '';
  privateKey: string = '';
  location: string = 'us-central1';
  projectId: string = '';
  promptCaching: boolean = false;
  savedMsg: string = '';
  unsupportedAuthMsg: string = '';
  authMode: string = 'api_key';
  ioBusy: boolean = false;
  private isNew: boolean = false;
  private ports: ProviderEditPorts | null = null;
  private get deps(): ProviderEditPorts { return this.ports as ProviderEditPorts; }

  start(ports: ProviderEditPorts): void { this.ports = ports; }

  load(id: string, isNew: boolean, draft: ProviderSetting | null): void {
    this.isNew = isNew;
    loadProviders(this.deps.kvStore).then((list: ProviderSetting[] | null): void => {
      let target: ProviderSetting | null = null;
      if (id.length > 0) {
        const hit = (list ?? []).find((p: ProviderSetting): boolean => p.id === id);
        if (hit !== undefined) target = hit;
      }
      if (target === null && draft !== null) {
        target = draft;
      }
      if (target !== null) this.applyProvider(target);
      else this.loadError = '模型服务不存在，请返回重新选择。';
      this.loading = false;
    }).catch((): void => { this.loadError = '无法读取模型服务，请返回后重试。'; this.loading = false; });
  }

  reloadReference(): void {
    const raw: ProviderSetting | null = this.provider;
    if (raw === null) return;
    loadProviders(this.deps.kvStore).then((list: ProviderSetting[] | null): void => {
      const hit = (list ?? []).find((p: ProviderSetting): boolean => p.id === raw.id);
      if (hit !== undefined && this.provider !== null && this.provider.id === hit.id) {
        this.provider = hit;
        if (hit.type === 'google') {
          const current: ProviderSettingGoogle = hit as ProviderSettingGoogle;
          this.authMode = current.authMode;
          this.useServiceAccount = current.useServiceAccount;
          if (raw.type === 'google') {
            const prior: ProviderSettingGoogle = raw as ProviderSettingGoogle;
            if (prior.authMode !== current.authMode || prior.useServiceAccount !== current.useServiceAccount
              || prior.privateKeyRef !== current.privateKeyRef || prior.oauthClientId !== current.oauthClientId
              || prior.projectId !== current.projectId || prior.location !== current.location) {
              this.apiKey = current.apiKey;
              this.vertexAI = current.vertexAI;
              this.projectId = current.projectId;
              this.location = current.location;
            }
          }
        }
      }
    }).catch((): void => {});
  }

  applyProvider(p: ProviderSetting): void {
    this.provider = p;
    this.providerEnabled = p.enabled;
    this.name = p.name;
    this.baseUrl = p.baseUrl;
    this.modelsText = p.models.map((m: ProviderModel): string => m.modelId).join(', ');
    this.unsupportedAuthMsg = '';
    this.apiKey = p.apiKey;
    if (p.type === 'openai') {
      const v: ProviderSettingOpenAIVariant = p as ProviderSettingOpenAIVariant;
      this.chatPath = v.chatCompletionsPath;
      this.useResponseApi = v.useResponseApi;
      this.authMode = v.authMode;
    } else if (p.type === 'google') {
      const v: ProviderSettingGoogle = p as ProviderSettingGoogle;
      this.vertexAI = v.vertexAI;
      this.useServiceAccount = v.useServiceAccount;
      this.authMode = v.authMode;
      this.serviceAccountEmail = v.serviceAccountEmail;
      this.privateKey = '';
      this.location = v.location;
      this.projectId = v.projectId;
    } else {
      const c: ProviderSettingClaude = p as ProviderSettingClaude;
      this.promptCaching = c.promptCaching;
    }
    this.deps.onProviderApplied(p);
  }

  isGoogleApiKey(): boolean {
    return this.provider !== null && this.provider.type === 'google'
      && this.authMode === 'api_key' && !this.useServiceAccount;
  }

  parseModels(existing: ProviderModel[]): ProviderModel[] {
    return this.modelsText
      .split(/[,\n]/)
      .map((s: string): string => s.trim())
      .filter((s: string, index: number, ids: string[]): boolean => s.length > 0 && ids.indexOf(s) === index)
      .map((id: string): ProviderModel => {
        const hit: ProviderModel | undefined = existing.find(
          (m: ProviderModel): boolean => m.modelId === id);
        const catalog: GoogleCatalogModel | undefined = this.deps.catalogModel(id);
        return hit !== undefined ? hit : makeProviderModel({ modelId: id, displayName: catalog !== undefined ? catalog.displayName : id });
      });
  }

  async save(): Promise<boolean> {
    const raw: ProviderSetting | null = this.provider;
    if (raw === null || this.ioBusy) return false;
    this.ioBusy = true;
    // api_key/plan 模式必须填 Key;codex/grok 的凭据由登录态承载(apiKey 字段同步)
    if (raw.type === 'openai' && this.authMode !== 'codex_oauth' && this.authMode !== 'grok_oauth'
      && this.apiKey.trim().length === 0) {
      this.savedMsg = '请先填写 API Key';
      setTimeout((): void => { this.savedMsg = ''; }, 2000);
      this.ioBusy = false;
      return false;
    }
    if (raw.type !== 'openai' && this.unsupportedAuthMsg.length > 0 && this.apiKey.trim().length === 0) {
      this.savedMsg = '请先填写 API Key';
      setTimeout((): void => { this.savedMsg = ''; }, 2000);
      this.ioBusy = false;
      return false;
    }
    const apiKeySnapshot: string = this.apiKey;
    const models: ProviderModel[] = this.parseModels(raw.models);
    const trimmedName: string = this.name.trim();
    let updated: ProviderSetting;
    if (raw.type === 'google') {
      const g: ProviderSettingGoogle = raw as ProviderSettingGoogle;
      updated = makeProviderSettingGoogle({
        id: raw.id,
        enabled: this.providerEnabled,
        name: trimmedName.length > 0 ? trimmedName : 'Google',
        baseUrl: this.baseUrl.trim(),
        apiKey: this.isGoogleApiKey() ? this.apiKey.trim() : g.apiKey,
        models,
        vertexAI: this.isGoogleApiKey() ? this.vertexAI : g.vertexAI,
        useServiceAccount: g.useServiceAccount,
        serviceAccountEmail: g.serviceAccountEmail,
        privateKey: g.privateKey,
        privateKeyRef: g.privateKeyRef,
        location: this.isGoogleApiKey() ? this.location.trim() : g.location,
        projectId: this.isGoogleApiKey() ? this.projectId.trim() : g.projectId,
        authMode: g.authMode,
        oauthClientId: g.oauthClientId,
        oauthRedirectUri: g.oauthRedirectUri,
        oauthClientSecretRef: g.oauthClientSecretRef,
        balanceOption: g.balanceOption,
      });
    } else if (raw.type === 'claude') {
      const c: ProviderSettingClaude = raw as ProviderSettingClaude;
      updated = makeProviderSettingClaude({
        id: raw.id,
        enabled: this.providerEnabled,
        name: trimmedName.length > 0 ? trimmedName : 'Claude',
        baseUrl: this.baseUrl.trim(),
        apiKey: this.apiKey.trim(),
        models,
        promptCaching: this.promptCaching,
        balanceOption: c.balanceOption,
      });
    } else {
      const o: ProviderSettingOpenAIVariant = raw as ProviderSettingOpenAIVariant;
      const fixed: string | null = openAIAuthModeFixedBaseUrl(this.authMode as OpenAIAuthMode);
      updated = makeProviderSettingOpenAIVariant({
        id: raw.id,
        enabled: this.providerEnabled,
        name: trimmedName.length > 0 ? trimmedName : 'OpenAI',
        // plan/codex/grok 模式钉官方地址;api_key 模式用表单值
        baseUrl: fixed !== null ? fixed : this.baseUrl.trim(),
        // codex/grok 的 access token 由登录/刷新流程维护,不读表单
        apiKey: (this.authMode === 'codex_oauth' || this.authMode === 'grok_oauth')
          ? o.apiKey : this.apiKey.trim(),
        chatCompletionsPath: this.chatPath.trim(),
        useResponseApi: this.authMode === 'codex_oauth' ? true : this.useResponseApi,
        models,
        authMode: this.authMode as OpenAIAuthMode,
        brand: o.brand,
        balanceOption: o.balanceOption,
      });
    }
    return await loadProviders(this.deps.kvStore).then(async (list: ProviderSetting[] | null): Promise<boolean> => {
      const all: ProviderSetting[] = list ?? [];
      if (updated.type === 'openai') {
        const openAi: ProviderSettingOpenAIVariant = updated as ProviderSettingOpenAIVariant;
        if (openAi.authMode === 'codex_oauth' || openAi.authMode === 'grok_oauth') {
          const tokens = openAi.authMode === 'codex_oauth' ? await this.deps.loadCodexTokens(updated.id) : await this.deps.loadGrokTokens(updated.id);
          const latest: ProviderSetting | undefined = all.find((item: ProviderSetting): boolean => item.id === updated.id);
          updated.apiKey = tokens !== null ? tokens.accessToken : latest !== undefined ? latest.apiKey : apiKeySnapshot;
        }
      }
      const idx: number = all.findIndex((p: ProviderSetting): boolean => p.id === updated.id);
      if (idx < 0 && !this.isNew) throw new Error('模型服务已被删除，请返回重新添加。');
      if (idx >= 0) {
        const latestModels: ProviderModel[] = all[idx].models;
        // Only the catalog IDs are edited here; retain parameters saved by the model editor.
        updated.models = updated.models.map((model: ProviderModel): ProviderModel =>
          latestModels.find((latest: ProviderModel): boolean => latest.modelId === model.modelId) ?? model);
      }
      if (idx >= 0) {
        all[idx] = updated;
      } else {
        all.unshift(updated);
      }
      return saveProviders(this.deps.kvStore, all).then((): boolean => {
        this.provider = updated;
        this.isNew = false;
        this.unsupportedAuthMsg = '';
        this.savedMsg = '已保存';
        setTimeout((): void => { this.savedMsg = ''; }, 1500);
        return true;
      });
    }).catch((e: Error): boolean => {
      this.savedMsg = `保存失败：${e.message}`;
      return false;
    }).finally((): void => {
      this.ioBusy = false;
    });
  }

  readingModelIds(): string[] {
    const ids: string[] = [];
    for (const part of this.modelsText.split(/[,\n]/)) {
      const id: string = part.trim();
      if (id.length > 0 && ids.indexOf(id) < 0) ids.push(id);
    }
    return ids;
  }

  readingModelName(id: string): string {
    const model: ProviderModel | undefined = this.provider?.models.find((item: ProviderModel): boolean => item.modelId === id);
    return model !== undefined && model.displayName.length > 0 ? model.displayName : id;
  }

  // The page owns confirmation and routing; this owner commits the latest list,
  // then clears Google and Codex/Grok OAuth credentials. Cleanup failure does not undo deletion.
  async deleteProvider(providerId: string): Promise<boolean> {
    let deletedGoogle: ProviderSettingGoogle | null = null;
    try {
      const list: ProviderSetting[] = (await loadProviders(this.deps.kvStore)) ?? [];
      const current: ProviderSetting | undefined = list.find((item: ProviderSetting): boolean => item.id === providerId);
      if (current !== undefined && current.type === 'google') deletedGoogle = current as ProviderSettingGoogle;
      await saveProviders(this.deps.kvStore, list.filter((item: ProviderSetting): boolean => item.id !== providerId));
      if (deletedGoogle !== null) {
        try { await this.deps.cleanupDeletedGoogleProvider(deletedGoogle); }
        catch (error) {
          this.deps.cleanupWarning(error instanceof Error ? error.message : '服务商已删除，但 Google 凭据清理失败。');
        }
      }
      const clearCodex = this.deps.clearCodexTokens
        ?? (typeof this.deps.kvStore?.delete === 'function'
          ? (id: string): Promise<void> => this.deps.kvStore.delete(`oauth_tokens/codex/${id}`)
          : null);
      const clearGrok = this.deps.clearGrokTokens
        ?? (typeof this.deps.kvStore?.delete === 'function'
          ? (id: string): Promise<void> => this.deps.kvStore.delete(`oauth_tokens/grok/${id}`)
          : null);
      try {
        if (clearCodex !== null) await clearCodex(providerId);
        if (clearGrok !== null) await clearGrok(providerId);
      } catch (error) {
        this.deps.cleanupWarning(error instanceof Error ? error.message : '服务商已删除，但 OAuth 凭据清理失败。');
      }
      return true;
    } catch (error) {
      this.savedMsg = `删除失败：${(error as Error).message}`;
      return false;
    } finally { this.ioBusy = false; }
  }
}
