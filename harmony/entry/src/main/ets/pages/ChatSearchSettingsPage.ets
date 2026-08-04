// ChatSearchSettingsPage — 搜索设置面(D-099)
// Android 锚点:
//   SettingSearchPage.kt(全文)— hero(master switch;enabledCount = 服务启用数 +
//     六内置 true 数;serviceCount = services.size + 6)、move/delete/edit/新增
//     语义逐字(:68-119,:215-251)
//   SettingSearchListCards.kt — hero(:96-176)、内置六行 zh 串逐字(:267-326)、
//     已配置行 rank+开关(:352-372)、通用选项(:551-590)、stepper clamp 1..50
//     (:592-620)、推荐组合(:726-778)、resolveSelectedSearchIndex(:804-811)
//   SettingSearchServiceEditorSheet.kt — 结构/按钮(:46-152)、per-type 字段
//     (:155-933)、类型切换 = primaryConstructor.callBy 全新默认(:87)
// 偏差登记:
//   - ReorderableColumn 长按拖拽 → 上移/下移 按钮(ArkUI 无现成拖拽排序;
//     move 语义逐字保留);trailing '拖动调整优先级' → '调整优先级'
//   - ModalBottomSheet → 全屏 overlay(同 ChatTaskModelsPage overlay 先例)
//   - StepperNumberField 直输 → 仅 −/＋ 按钮(clamp 1..50 语义保留)
//   - 每 provider Description() composable 未移植(P1 既有登记)
import { ACCENT,BG,INK,INK3,LINE,SURFACE,TITLE } from '../design/tokens.ets';
import { router } from '@kit.ArkUI';
import {
  defaultSearchPrefs, loadSearchPrefs, updateSearchPrefs,
  makeSearchServiceOptions, searchServiceOptionsToJson, searchServiceOptionsFromJson,
  getSearchService, SEARCH_SERVICE_TYPES,
} from '@amber/chat-domain';
import type {
  SearchSettings, SearchServiceOptions, SearchServiceOptionsType,
} from '@amber/chat-domain';
import { getChatKvStore } from '../di/AppContainer.ets';

const DANGER: string = '#B5473A';


// TYPES LinkedHashMap 插入序(SEARCH_SERVICE_TYPES 声明序逐字)
const SEARCH_TYPE_ORDER: SearchServiceOptionsType[] = [
  'bing_local', 'amber_agent', 'zhipu', 'tavily', 'exa', 'searxng', 'linkup',
  'brave', 'serper', 'serpapi', 'metaso', 'ollama', 'perplexity', 'firecrawl',
  'jina', 'bocha', 'grok',
];

// resolveSelectedSearchIndex(SettingSearchListCards.kt:804-811 逐字)
const resolveSelectedSearchIndex = (
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

// updateSearchPrefs JSON 相等短路 → transform 不得原地改同一对象;
// arkts-no-spread → 显式逐字段深克隆(services 经 toJson/fromJson 往返)
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

// Android toIntOrNull:整串合法整数才解析,否则 null
const parseIntOrNull = (v: string): number | null => {
  const t: string = v.trim();
  if (!/^[+-]?\d+$/.test(t)) return null;
  const n: number = parseInt(t, 10);
  return Number.isNaN(n) ? null : n;
};

const distinct = (arr: string[]): string[] =>
  arr.filter((v: string, i: number): boolean => arr.indexOf(v) === i);

const capitalize = (s: string): string =>
  s.length === 0 ? s : s.charAt(0).toUpperCase() + s.slice(1);

@Entry
@Component
struct ChatSearchSettingsPage {
  @State loaded: boolean = false;
  @State s: SearchSettings = defaultSearchPrefs();
  // ===== 编辑器 overlay(editIndex < 0 = 新增,Android target.index == null) =====
  @State editorVisible: boolean = false;
  @State editIndex: number = -1;
  @State editTargetId: string = ''; // target.service.id(编辑时)
  @State typeMenuOpen: boolean = false;
  // per-type 草稿字段(editor `var options by remember` → 平铺 @State)
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

  aboutToAppear(): void {
    this.refresh();
  }

  private async refresh(): Promise<void> {
    const s: SearchSettings = await loadSearchPrefs(getChatKvStore());
    this.s = s;
    this.loaded = true;
  }

  // vm.updateSettings(settings.copy(...)) → updateSearchPrefs + 深克隆 transform
  private async applyPatch(patch: (n: SearchSettings) => void): Promise<void> {
    await updateSearchPrefs(getChatKvStore(), (c: SearchSettings): SearchSettings => {
      const n: SearchSettings = cloneSearchSettings(c);
      patch(n);
      return n;
    });
    await this.refresh();
  }

  // hero enabledCount:服务启用数 + 六内置 true 数(SettingSearchPage.kt:126-137)
  private enabledCount(): number {
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

  // ===== 主开关/内置开关/通用选项 =====

  private setMaster(enabled: boolean): void {
    this.applyPatch((n: SearchSettings): void => { n.enableWebSearch = enabled; });
  }

  private setBuiltin(field: string, enabled: boolean): void {
    this.applyPatch((n: SearchSettings): void => {
      if (field === 'jina') n.searchBuiltinJinaEnabled = enabled;
      else if (field === 'duckduckgo') n.searchBuiltinDuckDuckGoEnabled = enabled;
      else if (field === 'bing') n.searchBuiltinBingEnabled = enabled;
      else if (field === 'wikipedia') n.searchBuiltinWikipediaEnabled = enabled;
      else if (field === 'hackernews') n.searchBuiltinHackerNewsEnabled = enabled;
      else n.searchGoogleWebViewFallbackEnabled = enabled;
    });
  }

  private builtinValue(field: string): boolean {
    if (field === 'jina') return this.s.searchBuiltinJinaEnabled;
    if (field === 'duckduckgo') return this.s.searchBuiltinDuckDuckGoEnabled;
    if (field === 'bing') return this.s.searchBuiltinBingEnabled;
    if (field === 'wikipedia') return this.s.searchBuiltinWikipediaEnabled;
    if (field === 'hackernews') return this.s.searchBuiltinHackerNewsEnabled;
    return this.s.searchGoogleWebViewFallbackEnabled;
  }

  private setResultSize(value: number): void {
    const clamped: number = Math.min(50, Math.max(1, value)); // coerceIn(1, 50)
    this.applyPatch((n: SearchSettings): void => {
      n.searchCommonOptions = { resultSize: clamped };
    });
  }

  // ===== 已配置服务:move/enable/edit/delete(语义逐字) =====

  // moveSearchService(SettingSearchPage.kt:68-81)
  private moveService(fromIndex: number, toIndex: number): void {
    const cur: SearchSettings = this.s;
    if (fromIndex < 0 || fromIndex >= cur.searchServices.length) return;
    if (toIndex < 0 || toIndex >= cur.searchServices.length) return;
    this.applyPatch((n: SearchSettings): void => {
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

  // onServiceEnabledChange(:155-162)
  private toggleService(service: SearchServiceOptions, enabled: boolean): void {
    this.applyPatch((n: SearchSettings): void => {
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

  // ===== 编辑器 =====

  private openAdd(): void {
    // Android 新增 target:BingLocalOptions() 初始(:54-59)
    this.loadDraft(makeSearchServiceOptions('bing_local'));
    this.editIndex = -1;
    this.editTargetId = '';
    this.typeMenuOpen = false;
    this.editorVisible = true;
  }

  private openEdit(service: SearchServiceOptions, index: number): void {
    this.loadDraft(service);
    this.editIndex = index;
    this.editTargetId = service.id;
    this.typeMenuOpen = false;
    this.editorVisible = true;
  }

  // 类型切换 = primaryConstructor.callBy(mapOf())(:87)— 全新默认实例(新 id)
  private onTypeChange(t: SearchServiceOptionsType): void {
    this.loadDraft(makeSearchServiceOptions(t));
    this.typeMenuOpen = false;
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

  // SearchAbilityTagLine(:228-253):抓取 tag = scrapingParameters != null
  private scrapeCapable(): boolean {
    return getSearchService(this.draftOptions()).scrapingParameters !== null;
  }

  private hasApiKey(): boolean {
    return this.dType !== 'bing_local' && this.dType !== 'searxng';
  }

  private hasDepth(): boolean {
    return this.dType === 'tavily' || this.dType === 'linkup' || this.dType === 'amber_agent';
  }

  private depthOptions(): string[] {
    return this.dType === 'tavily' ? ['basic', 'advanced'] : ['standard', 'deep'];
  }

  // onConfirm 新增(:216-226):prepend + 启用(distinct)+ selected = 0
  private confirmAdd(): void {
    const updated: SearchServiceOptions = this.draftOptions();
    this.applyPatch((n: SearchSettings): void => {
      const ids: string[] = [updated.id];
      for (const oid of n.searchEnabledServiceIds) {
        if (ids.indexOf(oid) < 0) ids.push(oid);
      }
      n.searchServices = [updated].concat(n.searchServices);
      n.searchEnabledServiceIds = ids;
      n.searchServiceSelected = 0;
    });
    this.editorVisible = false;
  }

  // onConfirm 编辑(:227-247):id 定位(失败回 target.index);曾启用 → enabledIds 换 id
  private confirmEdit(): void {
    const updated: SearchServiceOptions = this.draftOptions();
    const targetId: string = this.editTargetId;
    const fallbackIndex: number = this.editIndex;
    this.applyPatch((n: SearchSettings): void => {
      const found: number = n.searchServices.findIndex(
        (x: SearchServiceOptions): boolean => x.id === targetId);
      const currentIndex: number = found >= 0 ? found : fallbackIndex;
      if (currentIndex < 0 || currentIndex >= n.searchServices.length) return;
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
    this.editorVisible = false;
  }

  // deleteSearchService(:83-103):guard index!=null && size>1;selectedId takeUnless 删除项
  private confirmDelete(): void {
    if (this.editIndex < 0 || this.s.searchServices.length <= 1) return;
    const targetId: string = this.editTargetId;
    const fallbackIndex: number = this.editIndex;
    this.applyPatch((n: SearchSettings): void => {
      if (n.searchServices.length <= 1) return;
      const found: number = n.searchServices.findIndex(
        (x: SearchServiceOptions): boolean => x.id === targetId);
      const currentIndex: number = found >= 0 ? found : fallbackIndex;
      if (currentIndex < 0 || currentIndex >= n.searchServices.length) return;
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
    this.editorVisible = false;
  }

  // ===== UI =====

  @Builder
  heroCard() {
    Column() {
      Row() {
        Column() {
          Text('Agent 网络搜索')
            .fontSize(16).fontWeight(FontWeight.Medium).fontColor(INK)
          Text('允许聊天调用 search_web 和 scrape_web，使用已启用服务。')
            .fontSize(12).fontColor(INK3).margin({ top: 4 })
        }
        .alignItems(HorizontalAlign.Start).layoutWeight(1)
        Toggle({ type: ToggleType.Switch, isOn: this.s.enableWebSearch })
          .selectedColor(ACCENT)
          .onChange((v: boolean): void => { this.setMaster(v); })
      }
      .width('100%').padding(18)

      Row()
        .width('100%').height(1).backgroundColor(LINE)

      Row() {
        Row()
          .width(7).height(7).borderRadius(4).backgroundColor(ACCENT)
          .margin({ right: 8 })
        Text(`${this.enabledCount()} / ${this.s.searchServices.length + 6} 个服务已启用 · 多源召回`)
          .fontSize(12).fontColor(INK3)
      }
      .width('100%').padding({ left: 18, right: 18, top: 12, bottom: 12 })
    }
    .width('100%').backgroundColor(SURFACE).borderRadius(14)
    .border({ width: 1, color: LINE })
  }

  @Builder
  subGroupLabel(text: string, trailing: string) {
    Row() {
      Text(text)
        .fontSize(11).fontWeight(FontWeight.Medium).fontColor(INK3)
        .layoutWeight(1)
      if (trailing.length > 0) {
        Text(trailing).fontSize(11).fontColor(INK3)
      }
    }
    .width('100%').padding({ left: 16, right: 16, top: 14, bottom: 6 })
  }

  @Builder
  builtinRow(title: string, desc: string, field: string, showDivider: boolean) {
    Column() {
      Row() {
        Column() {
          Text(title).fontSize(14).fontWeight(FontWeight.Medium).fontColor(INK)
          Text(desc).fontSize(11).fontColor(INK3).margin({ top: 2 })
        }
        .alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ right: 10 })
        Toggle({ type: ToggleType.Switch, isOn: this.builtinValue(field) })
          .selectedColor(ACCENT)
          .onChange((v: boolean): void => { this.setBuiltin(field, v); })
      }
      .width('100%').padding({ left: 16, right: 16, top: 10, bottom: 10 })

      if (showDivider) {
        Row().width('100%').height(1).backgroundColor(LINE).margin({ left: 16 })
      }
    }
    .width('100%')
  }

  @Builder
  serviceRow(svc: SearchServiceOptions, index: number) {
    Column() {
      Row() {
        Text(`${index + 1}`)
          .fontSize(11).fontColor(INK3)
          .width(20).textAlign(TextAlign.Center)
        Text(SEARCH_SERVICE_TYPES[svc.type])
          .fontSize(14).fontWeight(FontWeight.Medium).fontColor(INK)
          .layoutWeight(1).margin({ left: 8 })
          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        Text('上移')
          .fontSize(12).fontColor(index > 0 ? ACCENT : LINE)
          .padding({ left: 8, right: 8, top: 4, bottom: 4 })
          .onClick((): void => {
            if (index > 0) this.moveService(index, index - 1);
          })
        Text('下移')
          .fontSize(12)
          .fontColor(index < this.s.searchServices.length - 1 ? ACCENT : LINE)
          .padding({ left: 8, right: 8, top: 4, bottom: 4 })
          .onClick((): void => {
            if (index < this.s.searchServices.length - 1) this.moveService(index, index + 1);
          })
        Toggle({
          type: ToggleType.Switch,
          isOn: this.s.searchEnabledServiceIds.indexOf(svc.id) >= 0,
        })
          .selectedColor(ACCENT)
          .onChange((v: boolean): void => { this.toggleService(svc, v); })
        Text('›').fontSize(16).fontColor(INK3).margin({ left: 8 })
      }
      .width('100%').padding({ left: 16, right: 16, top: 10, bottom: 10 })
      .onClick((): void => { this.openEdit(svc, index); })

      if (index < this.s.searchServices.length - 1) {
        Row().width('100%').height(1).backgroundColor(LINE).margin({ left: 16 })
      }
    }
    .width('100%')
  }

  @Builder
  commonOptionsCard() {
    Column() {
      this.subGroupLabel('通用选项', '')
      Row() {
        Column() {
          Text('结果数量').fontSize(14).fontWeight(FontWeight.Medium).fontColor(INK)
          Text('每次搜索最多返回的结果条数').fontSize(11).fontColor(INK3).margin({ top: 2 })
        }
        .alignItems(HorizontalAlign.Start).layoutWeight(1)
        Row() {
          Text('−')
            .fontSize(16).fontColor(this.s.searchCommonOptions.resultSize > 1 ? INK : LINE)
            .padding({ left: 12, right: 12, top: 4, bottom: 4 })
            .onClick((): void => {
              if (this.s.searchCommonOptions.resultSize > 1) {
                this.setResultSize(this.s.searchCommonOptions.resultSize - 1);
              }
            })
          Text(`${this.s.searchCommonOptions.resultSize}`)
            .fontSize(14).fontColor(INK).width(36).textAlign(TextAlign.Center)
          Text('＋')
            .fontSize(16).fontColor(this.s.searchCommonOptions.resultSize < 50 ? INK : LINE)
            .padding({ left: 12, right: 12, top: 4, bottom: 4 })
            .onClick((): void => {
              if (this.s.searchCommonOptions.resultSize < 50) {
                this.setResultSize(this.s.searchCommonOptions.resultSize + 1);
              }
            })
        }
        .backgroundColor(BG).borderRadius(10).border({ width: 1, color: LINE })
      }
      .width('100%').padding({ left: 16, right: 16, top: 8, bottom: 18 })
    }
    .width('100%').backgroundColor(SURFACE).borderRadius(14)
    .border({ width: 1, color: LINE })
  }

  @Builder
  recommendRow(title: string, detail: string) {
    Row() {
      Text(title).fontSize(13).fontWeight(FontWeight.Medium).fontColor(ACCENT)
      Text(detail).fontSize(12).fontColor(INK3).layoutWeight(1).textAlign(TextAlign.End)
    }
    .width('100%').padding({ left: 14, right: 14, top: 10, bottom: 10 })
    .border({ width: 1, color: LINE }).borderRadius(12)
    .margin({ bottom: 8 })
  }

  @Builder
  formTextField(label: string, value: string, placeholder: string,
    onChange: (v: string) => void) {
    Column() {
      Text(label).fontSize(12).fontColor(INK3).margin({ bottom: 4 })
      TextInput({ text: value, placeholder })
        .width('100%').fontSize(13).fontColor(INK)
        .backgroundColor(BG).borderRadius(8)
        .onChange(onChange)
    }
    .width('100%').alignItems(HorizontalAlign.Start).margin({ bottom: 12 })
  }

  @Builder
  editorOverlay() {
    Column() {
      Blank().onClick((): void => { this.editorVisible = false; })
      Column() {
        Text(this.editIndex < 0 ? '添加提供商' : '编辑')
          .fontSize(16).fontWeight(FontWeight.Medium).fontColor(INK)
          .margin({ bottom: 12 })

        Scroll() {
          Column() {
            // 类型选择(Select :82-102 → 展开列表)
            Column() {
              Row() {
                Text('类型').fontSize(12).fontColor(INK3)
                Blank()
                Text(SEARCH_SERVICE_TYPES[this.dType])
                  .fontSize(13).fontColor(INK)
                Text(this.typeMenuOpen ? '▲' : '▼')
                  .fontSize(11).fontColor(INK3).margin({ left: 6 })
              }
              .width('100%').padding(10)
              .backgroundColor(BG).borderRadius(8)
              .onClick((): void => { this.typeMenuOpen = !this.typeMenuOpen; })

              if (this.typeMenuOpen) {
                Column() {
                  ForEach(SEARCH_TYPE_ORDER, (t: SearchServiceOptionsType) => {
                    Row() {
                      Text(SEARCH_SERVICE_TYPES[t])
                        .fontSize(13)
                        .fontColor(t === this.dType ? ACCENT : INK)
                        .layoutWeight(1)
                      if (t === this.dType) {
                        Text('✓').fontSize(12).fontColor(ACCENT)
                      }
                    }
                    .width('100%').padding({ left: 10, right: 10, top: 8, bottom: 8 })
                    .onClick((): void => { this.onTypeChange(t); })
                  }, (t: SearchServiceOptionsType): string => t)
                }
                .width('100%').height(220).margin({ top: 4 })
                .backgroundColor(BG).borderRadius(8)
              }
            }
            .width('100%').margin({ bottom: 12 })

            // 能力 tag(SearchAbilityTagLine :228-253)
            Row() {
              Text('搜索')
                .fontSize(11).fontColor(INK3)
                .padding({ left: 8, right: 8, top: 2, bottom: 2 })
                .border({ width: 1, color: LINE }).borderRadius(6)
              if (this.scrapeCapable()) {
                Text('抓取')
                  .fontSize(11).fontColor(INK3)
                  .padding({ left: 8, right: 8, top: 2, bottom: 2 })
                  .border({ width: 1, color: LINE }).borderRadius(6)
                  .margin({ left: 8 })
              }
            }
            .width('100%').margin({ bottom: 12 })

            // per-type 字段(SearchServiceOptionsEditor :155-225 分派)
            if (this.hasApiKey()) {
              this.formTextField('API Key', this.dApiKey, '',
                (v: string): void => { this.dApiKey = v; })
            }
            if (this.hasDepth()) {
              Column() {
                Text('Depth').fontSize(12).fontColor(INK3).margin({ bottom: 4 })
                Row() {
                  ForEach(this.depthOptions(), (d: string) => {
                    Text(capitalize(d))
                      .fontSize(13)
                      .fontColor(d === this.dDepth ? Color.White : INK)
                      .textAlign(TextAlign.Center)
                      .padding({ top: 6, bottom: 6 })
                      .layoutWeight(1)
                      .backgroundColor(d === this.dDepth ? ACCENT : BG)
                      .onClick((): void => { this.dDepth = d; })
                  }, (d: string): string => d)
                }
                .width('100%').borderRadius(8)
                .border({ width: 1, color: LINE })
              }
              .width('100%').alignItems(HorizontalAlign.Start).margin({ bottom: 12 })
            }
            if (this.dType === 'searxng') {
              this.formTextField('API URL', this.dUrl, '',
                (v: string): void => { this.dUrl = v; })
              this.formTextField('Engines', this.dEngines, '',
                (v: string): void => { this.dEngines = v; })
              this.formTextField('Language', this.dLanguage, '',
                (v: string): void => { this.dLanguage = v; })
              this.formTextField('Username', this.dUsername, '',
                (v: string): void => { this.dUsername = v; })
              this.formTextField('Password', this.dPassword, '',
                (v: string): void => { this.dPassword = v; })
            }
            if (this.dType === 'perplexity') {
              this.formTextField('Max Tokens', this.dMaxTokens, '',
                (v: string): void => { this.dMaxTokens = v; })
              this.formTextField('Max Tokens / Page', this.dMaxTokensPerPage, '',
                (v: string): void => { this.dMaxTokensPerPage = v; })
            }
            if (this.dType === 'jina') {
              this.formTextField('Search URL', this.dSearchUrl, 'https://s.jina.ai/',
                (v: string): void => { this.dSearchUrl = v; })
              this.formTextField('Scrape URL', this.dScrapeUrl, 'https://r.jina.ai/',
                (v: string): void => { this.dScrapeUrl = v; })
            }
            if (this.dType === 'bocha') {
              Row() {
                Column() {
                  Text('Summary').fontSize(13).fontColor(INK)
                  Text('Enable summary generation').fontSize(11).fontColor(INK3)
                    .margin({ top: 2 })
                }
                .alignItems(HorizontalAlign.Start).layoutWeight(1)
                Toggle({ type: ToggleType.Switch, isOn: this.dSummary })
                  .selectedColor(ACCENT)
                  .onChange((v: boolean): void => { this.dSummary = v; })
              }
              .width('100%').margin({ bottom: 12 })
            }
            if (this.dType === 'grok') {
              this.formTextField('Model', this.dModel, '',
                (v: string): void => { this.dModel = v; })
              this.formTextField('Custom URL', this.dCustomUrl, '',
                (v: string): void => { this.dCustomUrl = v; })
              Column() {
                Text('System Prompt').fontSize(12).fontColor(INK3).margin({ bottom: 4 })
                TextArea({ text: this.dSystemPrompt })
                  .width('100%').height(88).fontSize(13).fontColor(INK)
                  .backgroundColor(BG).borderRadius(8)
                  .onChange((v: string): void => { this.dSystemPrompt = v; })
              }
              .width('100%').alignItems(HorizontalAlign.Start).margin({ bottom: 12 })
            }
          }
          .width('100%').alignItems(HorizontalAlign.Start)
        }
        .width('100%').layoutWeight(1)

        Row() {
          if (this.editIndex >= 0 && this.s.searchServices.length > 1) {
            Text('删除提供商')
              .fontSize(14).fontColor(DANGER)
              .padding({ left: 12, right: 12, top: 8, bottom: 8 })
              .onClick((): void => { this.confirmDelete(); })
          }
          Blank()
          Text('取消')
            .fontSize(14).fontColor(INK3)
            .padding({ left: 20, right: 20, top: 8, bottom: 8 })
            .onClick((): void => { this.editorVisible = false; })
          Text(this.editIndex < 0 ? '添加提供商' : '保存')
            .fontSize(14).fontColor(ACCENT)
            .padding({ left: 20, right: 20, top: 8, bottom: 8 })
            .onClick((): void => {
              if (this.editIndex < 0) this.confirmAdd();
              else this.confirmEdit();
            })
        }
        .width('100%').margin({ top: 8 })
      }
      .width('100%').height('86%').padding(16)
      .backgroundColor(SURFACE)
      .borderRadius({ topLeft: 16, topRight: 16 })
    }
    .width('100%').height('100%').backgroundColor('#66000000')
  }

  build() {
    Stack() {
      Column() {
        Row() {
          Text('搜索服务')
            .fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          Blank()
          Text('＋')
            .fontSize(20).fontColor(ACCENT)
            .padding({ left: 10, right: 10, top: 2, bottom: 2 })
            .onClick((): void => { this.openAdd(); })
        }
        .width('100%').padding({ left: 16, right: 16, top: 16, bottom: 4 })

        if (!this.loaded) {
          Text('加载中…').fontSize(13).fontColor(INK3).margin({ top: 32 })
        } else {
          Scroll() {
            Column() {
              this.heroCard()

              Column() {
                this.subGroupLabel('内置免费源', '')
                this.builtinRow('Jina Search / Reader',
                  '可无 Key 使用，尽量以 Markdown 搜索和读取网页。', 'jina', true)
                this.builtinRow('DuckDuckGo Lite',
                  '无需 API Key 的免费公共召回源。', 'duckduckgo', true)
                this.builtinRow('Bing HTML',
                  '免费公共兜底，遇到验证页会明确报错。', 'bing', true)
                this.builtinRow('Wikipedia',
                  '实体和背景知识源，适合百科类问题。', 'wikipedia', true)
                this.builtinRow('Hacker News',
                  '技术、开源和 AI 工具生态讨论源。', 'hackernews', true)
                this.builtinRow('Google WebView 兜底',
                  '普通搜索弱或深度搜索时，打开可见搜索页兜底。', 'webview', false)

                this.subGroupLabel('已配置', '调整优先级')
                ForEach(this.s.searchServices, (svc: SearchServiceOptions, index: number) => {
                  this.serviceRow(svc, index)
                }, (svc: SearchServiceOptions): string => svc.id)
              }
              .width('100%').backgroundColor(SURFACE).borderRadius(14)
              .border({ width: 1, color: LINE })
              .margin({ top: 22 })

              Column() {
                this.commonOptionsCard()
              }
              .width('100%').margin({ top: 22 })

              Column() {
                Row() {
                  Text('推荐组合')
                    .fontSize(11).fontWeight(FontWeight.Medium).fontColor(INK3)
                }
                .width('100%').padding({ top: 14, bottom: 6 })
                this.recommendRow('零成本', 'Jina + DuckDuckGo + Bing')
                this.recommendRow('Google 结果', 'Serper / SerpAPI')
                this.recommendRow('高质量多源', 'Tavily 或 Brave')
              }
              .width('100%').margin({ top: 8 })
            }
            .width('100%').padding({ left: 16, right: 16, bottom: 24 })
          }
          .width('100%').layoutWeight(1)
        }
      }
      .width('100%').height('100%').backgroundColor(BG)

      if (this.editorVisible) {
        this.editorOverlay()
      }
    }
    .width('100%').height('100%')
  }
}
