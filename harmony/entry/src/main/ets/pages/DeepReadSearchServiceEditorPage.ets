import { DeepReadSubpageDock, DeepReadDockLayout } from '../components/deepread/DeepReadSubpageDock.ets';
import { router, KeyboardAvoidMode } from '@kit.ArkUI';
import type { SearchSettings, SearchServiceOptions, SearchServiceOptionsType } from '@amber/chat-domain';
import { DeepReadDesign as D } from '../design/DeepReadDesign.ets';
import { DeepReadPaperBackground } from '../components/deepread/DeepReadPaperBackground.ets';
import { DeepReadSettingsHeader, DeepReadSettingsSection, DeepReadSettingsGroup,
  DeepReadSettingsNote, DeepReadSettingsRow, DeepReadSettingsToggle, DeepReadSettingsPicker } from '../components/deepread/DeepReadSettingsVisual.ets';
import { deepReadSearchTitle, readDeepReadSearchEditorDraft, writeDeepReadSearchEditorDraft } from '../platform_impl/DeepReadSearchDraft.ets';
import type { DeepReadSearchEditorDraft } from '../platform_impl/DeepReadSearchDraft.ets';

interface DeepReadSearchEditorParams { serviceId?: string; }

@Entry
@Component
struct DeepReadSearchServiceEditorPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @State serviceEnabled: boolean = true;
  @State loaded: boolean = false;
  @State error: string = '';
  @State dType: SearchServiceOptionsType = 'bing_local';
  @State dId: string = '';
  @State dApiKey: string = '';
  @State dDepth: string = '';
  @State dUrl: string = '';
  @State dEngines: string = '';
  @State dLanguage: string = '';
  @State dUsername: string = '';
  @State dPassword: string = '';
  @State dSearchUrl: string = '';
  @State dScrapeUrl: string = '';
  @State dModel: string = '';
  @State dCustomUrl: string = '';
  @State dSystemPrompt: string = '';
  @State dMaxTokens: string = '';
  @State dMaxTokensPerPage: string = '';
  @State dSummary: boolean = true;
  private draft: DeepReadSearchEditorDraft | null = null;
  private changed: boolean = false;
  private previousKeyboardAvoidMode: KeyboardAvoidMode = KeyboardAvoidMode.OFFSET;
  private keyboardAvoidanceActive: boolean = false;

  aboutToAppear(): void {
    this.draft = readDeepReadSearchEditorDraft();
    const params: DeepReadSearchEditorParams | undefined = router.getParams() as DeepReadSearchEditorParams | undefined;
    const service: SearchServiceOptions | undefined = this.draft?.settings.searchServices.find(
      (item: SearchServiceOptions): boolean => item.id === params?.serviceId);
    if (service === undefined || this.draft === null) {
      this.error = '搜索服务草稿已失效，请返回设置页重新打开。';
      return;
    }
    this.loadDraft(service);
    this.serviceEnabled = this.draft.settings.searchEnabledServiceIds.includes(service.id);
    this.loaded = true;
  }

  onPageShow(): void {
    if (this.keyboardAvoidanceActive) return;
    this.previousKeyboardAvoidMode = this.getUIContext().getKeyboardAvoidMode();
    this.getUIContext().setKeyboardAvoidMode(KeyboardAvoidMode.RESIZE);
    this.keyboardAvoidanceActive = true;
  }

  onPageHide(): void {
    if (!this.keyboardAvoidanceActive) return;
    this.getUIContext().setKeyboardAvoidMode(this.previousKeyboardAvoidMode);
    this.keyboardAvoidanceActive = false;
  }

  aboutToDisappear(): void {
    this.onPageHide();
    this.draft = null;
    this.dApiKey = '';
    this.dPassword = '';
  }

  onBackPress(): boolean {
    this.finish();
    return true;
  }

  private edited(): void { this.changed = true; this.error = ''; }

  private optionalTokenLimit(value: string): number | null {
    if (value.trim().length === 0) return null;
    if (!/^\d+$/.test(value.trim()) || Number(value) < 1 || !Number.isSafeInteger(Number(value))) {
      throw new Error('Token 限制请输入正整数，或留空不限制。');
    }
    return Number(value);
  }

  private finish(): void {
    if (this.preserveDraft()) router.back();
  }

  private preserveDraft(): boolean {
    if (!this.loaded || this.draft === null) return true;
    if (!this.changed) {
      writeDeepReadSearchEditorDraft(this.draft);
      return true;
    }
    try {
      const next: SearchSettings = JSON.parse(JSON.stringify(this.draft.settings)) as SearchSettings;
      const service: SearchServiceOptions = this.draftOptions();
      next.searchServices = next.searchServices.map((item: SearchServiceOptions): SearchServiceOptions => item.id === service.id ? service : item);
      next.searchEnabledServiceIds = next.searchEnabledServiceIds.filter((id: string): boolean => id !== service.id);
      if (this.serviceEnabled) next.searchEnabledServiceIds = next.searchEnabledServiceIds.concat([service.id]);
      writeDeepReadSearchEditorDraft({ settings: next, selectedId: this.draft.selectedId, dirty: this.changed || this.draft.dirty });
      return true;
    } catch (error) { this.error = String(error); return false; }
  }

  private deleteService(): void {
    if (!this.loaded || this.draft === null) return;
    const next: SearchSettings = JSON.parse(JSON.stringify(this.draft.settings)) as SearchSettings;
    next.searchServices = next.searchServices.filter((service: SearchServiceOptions): boolean => service.id !== this.dId);
    next.searchEnabledServiceIds = next.searchEnabledServiceIds.filter((id: string): boolean => id !== this.dId);
    const selectedId: string = this.draft.selectedId === this.dId ? '' : this.draft.selectedId;
    next.searchServiceSelected = next.searchServices.findIndex((service: SearchServiceOptions): boolean => service.id === selectedId);
    writeDeepReadSearchEditorDraft({ settings: next, selectedId, dirty: true });
    router.back();
  }

  private loadDraft(o: SearchServiceOptions): void {
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

  private draftOptions(): SearchServiceOptions {
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
          maxTokens: this.optionalTokenLimit(this.dMaxTokens),
          maxTokensPerPage: this.optionalTokenLimit(this.dMaxTokensPerPage),
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

  @Builder
  Field(label: string, value: string, onChange: (value: string) => void, secure: boolean = false): void {
    Column() {
      Text(label).fontSize(13).fontColor(D.muted).width('100%').padding({ top: 10, left: 16, right: 16 })
      TextInput({ text: value, placeholder: label }).type(secure ? InputType.Password : InputType.Normal)
        .fontSize(16).fontColor(D.ink).backgroundColor(Color.Transparent).height(46)
        .padding({ left: 16, right: 16 }).onChange((text: string): void => { onChange(text); this.edited(); })
    }.width('100%')
  }

  @Builder
  Service(): void {
    DeepReadSettingsRow({ label: deepReadSearchTitle(this.dType), chevron: false })
    DeepReadSettingsToggle({ label: '启用', checked: this.serviceEnabled, last: this.dType === 'bing_local' || this.dType === 'searxng',
      onChange: (enabled: boolean): void => { this.serviceEnabled = enabled; this.edited(); } })
    if (this.dType !== 'bing_local' && this.dType !== 'searxng') {
      this.Field(this.dType === 'jina' ? 'API Key（可选）' : 'API Key', this.dApiKey,
        (value: string): void => { this.dApiKey = value; }, true)
    }
  }

  @Builder
  SearXNG(): void {
    this.Field('地址', this.dUrl, (value: string): void => { this.dUrl = value; })
    this.Field('搜索引擎', this.dEngines, (value: string): void => { this.dEngines = value; })
    this.Field('语言', this.dLanguage, (value: string): void => { this.dLanguage = value; })
    this.Field('用户名', this.dUsername, (value: string): void => { this.dUsername = value; })
    this.Field('密码', this.dPassword, (value: string): void => { this.dPassword = value; }, true)
  }

  @Builder
  Depth(): void {
    DeepReadSettingsPicker({ label: '深度', options: ['标准', '深入'], last: true,
      selected: this.dDepth === 'advanced' || this.dDepth === 'deep' ? 1 : 0,
      onSelect: (index: number): void => {
        this.dDepth = this.dType === 'tavily' ? (index === 1 ? 'advanced' : 'basic') : (index === 1 ? 'deep' : 'standard');
        this.edited();
      } })
  }

  @Builder
  TokenLimits(): void {
    this.Field('最大 Token 数', this.dMaxTokens, (value: string): void => { this.dMaxTokens = value; })
    this.Field('每页最大 Token 数', this.dMaxTokensPerPage, (value: string): void => { this.dMaxTokensPerPage = value; })
  }

  @Builder
  Jina(): void {
    this.Field('搜索地址', this.dSearchUrl, (value: string): void => { this.dSearchUrl = value; })
    this.Field('正文读取地址', this.dScrapeUrl, (value: string): void => { this.dScrapeUrl = value; })
  }

  @Builder
  Bocha(): void {
    DeepReadSettingsToggle({ label: '包含摘要', checked: this.dSummary, last: true,
      onChange: (summary: boolean): void => { this.dSummary = summary; this.edited(); } })
  }

  @Builder
  Grok(): void {
    this.Field('模型', this.dModel, (value: string): void => { this.dModel = value; })
    this.Field('地址', this.dCustomUrl, (value: string): void => { this.dCustomUrl = value; })
    Text('系统提示词').fontSize(13).fontColor(D.muted).width('100%').padding({ top: 10, left: 16, right: 16 })
    TextArea({ text: this.dSystemPrompt, placeholder: '系统提示词' }).height(120).fontSize(16)
      .fontColor(D.ink).backgroundColor(Color.Transparent).padding(16)
      .onChange((value: string): void => { this.dSystemPrompt = value; this.edited(); })
  }

  @Builder
  Options(): void {
    if (this.dType === 'searxng') {
      DeepReadSettingsSection({ title: 'SearXNG' })
      DeepReadSettingsGroup({ content: (): void => { this.SearXNG(); } })
    } else if (this.dType === 'tavily' || this.dType === 'linkup' || this.dType === 'amber_agent') {
      DeepReadSettingsSection({ title: '搜索深度' })
      DeepReadSettingsGroup({ content: (): void => { this.Depth(); } })
    } else if (this.dType === 'perplexity') {
      DeepReadSettingsSection({ title: '可选限制' })
      DeepReadSettingsGroup({ content: (): void => { this.TokenLimits(); } })
    } else if (this.dType === 'jina') {
      DeepReadSettingsSection({ title: 'Jina 地址' })
      DeepReadSettingsGroup({ content: (): void => { this.Jina(); } })
    } else if (this.dType === 'bocha') {
      DeepReadSettingsSection({ title: '结果' })
      DeepReadSettingsGroup({ content: (): void => { this.Bocha(); } })
    } else if (this.dType === 'grok') {
      DeepReadSettingsSection({ title: 'Grok 搜索' })
      DeepReadSettingsGroup({ content: (): void => { this.Grok(); } })
    }
  }

  @Builder
  Delete(): void {
    DeepReadSettingsRow({ label: '删除搜索服务', last: true, chevron: false, destructive: true,
      onTap: (): void => { this.deleteService(); } })
  }

  build(): void {
    Column() {
      ForEach([this.themeEpoch], (_epoch: number): void => {
        Stack() {
          DeepReadPaperBackground()
          Column() {
            DeepReadSettingsHeader({ title: deepReadSearchTitle(this.dType), onBack: (): void => { this.finish(); } })
            Scroll() {
              Column() {
                if (this.loaded) {
                  DeepReadSettingsSection({ title: '服务' })
                  DeepReadSettingsGroup({ content: (): void => { this.Service(); } })
                  if (this.dType === 'jina') {
                    DeepReadSettingsNote({ text: 'API Key 留空可用于正文读取；Jina 搜索需要凭据，未填写时会使用其他可用搜索来源。' })
                  }
                  this.Options()
                  DeepReadSettingsNote({ text: '返回设置页后，点击“保存并应用”。' })
                  DeepReadSettingsGroup({ content: (): void => { this.Delete(); } }).margin({ top: 24 })
                }
                if (this.error.length > 0) {
                  Text(this.error).fontSize(13).fontColor(D.danger).width('100%').padding({ top: 12, left: 16, right: 16 })
                }
              }.width('100%').padding({ left: D.gutter, right: D.gutter, bottom: DeepReadDockLayout.bottomInset(this.navBarHeight) })
            }.width('100%').layoutWeight(1).scrollBar(BarState.Off).align(Alignment.TopStart)
          }.width('100%').height('100%')
          DeepReadSubpageDock({ onBeforeLeave: (): boolean => { return this.preserveDraft(); } }).align(Alignment.Bottom).hitTestBehavior(HitTestMode.Transparent)
        }.width('100%').height('100%').backgroundColor(D.paper)
          .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
      }, (epoch: number): string => `theme-${epoch}`)
    }.width('100%').height('100%').backgroundColor(D.paper)
  }
}
