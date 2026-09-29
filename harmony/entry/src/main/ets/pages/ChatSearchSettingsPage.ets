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
// 视觉:2026-09-27 二级页 iOS 对齐(基准截图 IMG_1991)——居中标题「搜索服务」+
//   accent 圆添加、PlainSection 组标、r18 发丝描边卡组、CardToggleRow、
//   hero 状态行(绿点 + 文字)、已配置行序号胶囊 + 行内上移/下移 + 开关;
//   逻辑(加载/启停/排序/key 编辑/删除)零改动,编辑器 overlay 原样保留。
import {
  ACCENT, ACCENT_INK, BG, INK, INK3, INK4, LINE, LINE2, OVERLAY, RAISED, SURFACE, SURFACE2,
  SUCCESS_FG, ERROR, TEXT_STYLES, MOTION_OVERLAY, lineHeightForSize, accentSoftBg,
} from '../design/tokens.ets';
import {
  CenteredTitleBar, PlainSection, CardGroup, CardToggleRow, FooterNote, BtnFill, BtnGhost,
} from '../components/PageTemplate.ets';
import {
  FONT_MONO, FONT_MONO_MEDIUM, FONT_SANS_MEDIUM, FONT_SANS_SEMIBOLD,
} from '../design/fonts.ets';
import { Icon } from '../components/ui/Icon.ets';
import { KeyboardAvoidMode, router } from '@kit.ArkUI';
import {
  defaultSearchPrefs, loadSearchPrefs, updateSearchPrefs,
  makeSearchServiceOptions, searchServiceOptionsToJson, searchServiceOptionsFromJson,
  getSearchService, SEARCH_SERVICE_TYPES,
} from '@amber/chat-domain';
import type {
  SearchSettings, SearchServiceOptions, SearchServiceOptionsType,
} from '@amber/chat-domain';
import { getChatKvStore } from '../di/AppContainer.ets';

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
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @State loaded: boolean = false;
  @State s: SearchSettings = defaultSearchPrefs();
  // ===== 编辑器 overlay(editIndex < 0 = 新增,Android target.index == null) =====
  @State editorVisible: boolean = false;
  @State editorSaving: boolean = false;
  @State editorError: string = '';
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
  private previousKeyboardAvoidMode: KeyboardAvoidMode = KeyboardAvoidMode.OFFSET;
  private keyboardAvoidanceActive: boolean = false;
  private pageAlive: boolean = false;
  private editorSession: number = 0;

  aboutToAppear(): void {
    this.pageAlive = true;
    this.enableKeyboardAvoidance();
    this.refresh();
  }

  onPageShow(): void { this.enableKeyboardAvoidance(); }
  onPageHide(): void { this.restoreKeyboardAvoidance(); }
  aboutToDisappear(): void {
    this.pageAlive = false;
    this.restoreKeyboardAvoidance();
  }
  onBackPress(): boolean {
    if (!this.editorVisible) return false;
    if (this.typeMenuOpen) this.typeMenuOpen = false;
    else this.closeEditor();
    return true;
  }
  private enableKeyboardAvoidance(): void {
    if (this.keyboardAvoidanceActive) return;
    this.previousKeyboardAvoidMode = this.getUIContext().getKeyboardAvoidMode();
    this.getUIContext().setKeyboardAvoidMode(KeyboardAvoidMode.RESIZE);
    this.keyboardAvoidanceActive = true;
  }
  private restoreKeyboardAvoidance(): void {
    if (!this.keyboardAvoidanceActive) return;
    this.getUIContext().setKeyboardAvoidMode(this.previousKeyboardAvoidMode);
    this.keyboardAvoidanceActive = false;
  }

  private async refresh(): Promise<void> {
    const s: SearchSettings = await loadSearchPrefs(getChatKvStore());
    if (!this.pageAlive) return;
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

  private closeEditor(): void {
    this.editorSession++;
    this.editorVisible = false;
    this.editorSaving = false;
  }

  private openAdd(): void {
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

  private openEdit(service: SearchServiceOptions, index: number): void {
    this.editorSession++;
    this.editorSaving = false;
    this.editorError = '';
    this.loadDraft(service);
    this.editIndex = index;
    this.editTargetId = service.id;
    this.typeMenuOpen = false;
    this.editorVisible = true;
  }

  // 类型切换 = primaryConstructor.callBy(mapOf())(:87)— 全新默认实例(新 id)
  private onTypeChange(t: SearchServiceOptionsType): void {
    const draft: SearchServiceOptions = makeSearchServiceOptions(t);
    animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
      this.loadDraft(draft);
      this.typeMenuOpen = false;
    });
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

  private async saveEditorPatch(patch: (n: SearchSettings) => void): Promise<void> {
    if (this.editorSaving) return;
    const session: number = this.editorSession;
    this.editorSaving = true;
    this.editorError = '';
    try {
      await this.applyPatch(patch);
      if (this.pageAlive && session === this.editorSession) this.closeEditor();
    } catch (_error) {
      if (this.pageAlive && session === this.editorSession) {
        this.editorError = '保存失败，请重试。草稿已保留。';
      }
    } finally {
      if (this.pageAlive && session === this.editorSession) this.editorSaving = false;
    }
  }

  // onConfirm 新增(:216-226):prepend + 启用(distinct)+ selected = 0
  private confirmAdd(): void {
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

  // onConfirm 编辑(:227-247):id 定位(失败回 target.index);曾启用 → enabledIds 换 id
  private confirmEdit(): void {
    const updated: SearchServiceOptions = this.draftOptions();
    const targetId: string = this.editTargetId;
    const fallbackIndex: number = this.editIndex;
    this.saveEditorPatch((n: SearchSettings): void => {
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
  }

  // deleteSearchService(:83-103):guard index!=null && size>1;selectedId takeUnless 删除项
  private confirmDelete(): void {
    if (this.editIndex < 0 || this.s.searchServices.length <= 1) return;
    const targetId: string = this.editTargetId;
    const fallbackIndex: number = this.editIndex;
    this.saveEditorPatch((n: SearchSettings): void => {
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
  }

  // ===== UI =====

  // iOS hero 状态行(IMG_1991):绿点 + 「N / M 个服务已启用 · 多源召回」
  @Builder
  heroStatusRow() {
    Row() {
      Column()
        .width(8).height(8).borderRadius(4)
        .backgroundColor(SUCCESS_FG)
      Text(`${this.enabledCount()} / ${this.s.searchServices.length + 6} 个服务已启用 · 多源召回`)
        .fontSize(13)
        .lineHeight(lineHeightForSize(13))
        .fontFamily(FONT_SANS_MEDIUM)
        .fontColor(INK3)
        .margin({ left: 8 })
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
    }
    .width('100%')
    .alignItems(VerticalAlign.Center)
    .constraintSize({ minHeight: 44 })
    .padding({ left: 14, right: 14 })
  }

  // hero 卡内容:总开关 + 状态行(同卡,iOS 基准)
  @Builder
  heroCardContent(): void {
    CardToggleRow({
      label: 'Agent 网络搜索',
      desc: '允许聊天调用 search_web 和 scrape_web，使用已启用服务。',
      checked: this.s.enableWebSearch,
      isLast: false,
      onToggle: (): void => { this.setMaster(!this.s.enableWebSearch); },
    })
    this.heroStatusRow()
  }

  @Builder
  inlineToggle(checked: boolean, onToggle: () => void) {
    Stack({ alignContent: Alignment.Start }) {
      Column().width(42).height(24).borderRadius(99)
        .backgroundColor(checked ? this.accentColorStore : LINE2)
      Column().width(20).height(20).borderRadius(10).backgroundColor(RAISED)
        .margin({ left: checked ? 22 : 2 })
        .animation({ duration: 150, curve: Curve.EaseOut })
    }
    .clip(true).margin({ left: 12 }).onClick(onToggle)
    .accessibilityText(checked ? '已开启' : '已关闭')
  }

  @Builder
  builtinRow(title: string, desc: string, field: string, isLast: boolean) {
    CardToggleRow({
      label: title, desc: desc, checked: this.builtinValue(field), isLast: isLast,
      onToggle: (): void => { this.setBuiltin(field, !this.builtinValue(field)); },
    })
  }

  // 内置免费源卡内容:六行开关(iOS 基准顺序)
  @Builder
  builtinCardContent(): void {
    this.builtinRow('Jina Reader',
      '无需 Key 读取网页全文，供 scrape_web 工具使用。', 'jina', false)
    this.builtinRow('免费网页聚合',
      'DuckDuckGo、Brave、360、夸克并发查询，被拦截的引擎自动跳过。', 'duckduckgo', false)
    this.builtinRow('Bing HTML',
      '免费公共兜底，遇到验证页会自动跳过。', 'bing', false)
    this.builtinRow('Wikipedia',
      '实体和背景知识源，普通话题默认参与。', 'wikipedia', false)
    this.builtinRow('Hacker News',
      '技术、开源和 AI 工具生态讨论源。', 'hackernews', false)
    this.builtinRow('Google WebView 兜底',
      '普通搜索弱或深度搜索时，打开可见搜索页兜底。', 'webview', true)
  }

  // 已配置卡内容(iOS:拖柄 + 序号胶囊 + 名称 + id + 开关;鸿蒙无拖拽 → 行内上移/下移)
  @Builder
  configuredCardContent(): void {
    ForEach(this.s.searchServices, (svc: SearchServiceOptions, index: number) => {
      this.serviceRow(svc, index)
    }, (svc: SearchServiceOptions): string => svc.id)
  }

  @Builder
  serviceRow(svc: SearchServiceOptions, index: number) {
    Column() {
      Row() {
        // 序号胶囊(iOS:软底 accent 圆)
        Text(`${index + 1}`)
          .fontSize(TEXT_STYLES.meta.size).fontWeight(FontWeight.Medium)
          .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
          .fontColor(this.accentColorStore)
          .width(24).height(24).textAlign(TextAlign.Center)
          .borderRadius(12)
          .backgroundColor(accentSoftBg(this.accentColorStore))
        Column() {
          Text(SEARCH_SERVICE_TYPES[svc.type])
            .fontSize(TEXT_STYLES.body.size).fontWeight(600)
            .lineHeight(TEXT_STYLES.body.lineHeight).fontFamily(FONT_SANS_SEMIBOLD).fontColor(INK)
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          Text(svc.id).fontSize(TEXT_STYLES.meta.size).fontWeight(FontWeight.Medium)
            .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO).fontColor(INK3)
            .margin({ top: 3 }).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        .alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 10 })
        Text('上移').fontSize(TEXT_STYLES.meta.size).fontWeight(FontWeight.Medium)
          .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
          .fontColor(index > 0 ? this.accentColorStore : INK4).padding({ left: 6, right: 6 })
          .onClick((): void => { if (index > 0) this.moveService(index, index - 1); })
        Text('下移').fontSize(TEXT_STYLES.meta.size).fontWeight(FontWeight.Medium)
          .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
          .fontColor(index < this.s.searchServices.length - 1 ? this.accentColorStore : INK4)
          .padding({ left: 6, right: 6 })
          .onClick((): void => {
            if (index < this.s.searchServices.length - 1) this.moveService(index, index + 1);
          })
        this.inlineToggle(this.s.searchEnabledServiceIds.indexOf(svc.id) >= 0,
          (): void => {
            this.toggleService(svc, this.s.searchEnabledServiceIds.indexOf(svc.id) < 0);
          })
        Icon({ name: 'chevron_right', iconSize: 16, color: INK4 }).margin({ left: 4 })
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
      .constraintSize({ minHeight: 56 })
      .padding({ left: 14, right: 10, top: 8, bottom: 8 })
      .onClick((): void => { this.openEdit(svc, index); })
      if (index < this.s.searchServices.length - 1) {
        // 分隔线内缩对齐文字起点(行 padding 14 + 胶囊 24 + 间距 10)
        Row() {
          Row()
            .width('100%')
            .height(1)
            .backgroundColor(LINE)
        }
        .width('100%')
        .padding({ left: 48, right: 14 })
      }
    }
    .width('100%')
  }

  @Builder
  commonOptionsRow() {
    Row() {
      Column() {
        Text('结果数量').fontSize(TEXT_STYLES.body.size).fontWeight(600)
          .lineHeight(TEXT_STYLES.body.lineHeight).fontFamily(FONT_SANS_SEMIBOLD).fontColor(INK)
        Text('每次搜索最多返回的结果条数').fontSize(TEXT_STYLES.secondary.size)
          .lineHeight(TEXT_STYLES.secondary.lineHeight).fontFamily(TEXT_STYLES.secondary.family)
          .fontColor(INK3).margin({ top: 4 }).maxLines(2)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1)
      Row() {
        Text('−').fontSize(TEXT_STYLES.meta.size).fontWeight(FontWeight.Medium)
          .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
          .fontColor(this.s.searchCommonOptions.resultSize > 1 ? INK : INK4)
          .width(32).height(32).textAlign(TextAlign.Center)
          .onClick((): void => {
            if (this.s.searchCommonOptions.resultSize > 1) {
              this.setResultSize(this.s.searchCommonOptions.resultSize - 1);
            }
          })
        Text(`${this.s.searchCommonOptions.resultSize}`)
          .fontSize(TEXT_STYLES.meta.size).fontWeight(FontWeight.Medium)
          .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
          .fontColor(INK).width(36).textAlign(TextAlign.Center)
        Text('＋').fontSize(TEXT_STYLES.meta.size).fontWeight(FontWeight.Medium)
          .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
          .fontColor(this.s.searchCommonOptions.resultSize < 50 ? INK : INK4)
          .width(32).height(32).textAlign(TextAlign.Center)
          .onClick((): void => {
            if (this.s.searchCommonOptions.resultSize < 50) {
              this.setResultSize(this.s.searchCommonOptions.resultSize + 1);
            }
          })
      }
      .backgroundColor(SURFACE2).borderRadius(8).border({ width: 1, color: LINE })
    }
    .width('100%')
    .alignItems(VerticalAlign.Center)
    .constraintSize({ minHeight: 56 })
    .padding({ left: 14, right: 14, top: 10, bottom: 10 })
  }

  @Builder
  recommendRow(title: string, detail: string, isLast: boolean) {
    Column() {
      Row() {
        Text(title).fontSize(TEXT_STYLES.body.size).fontWeight(600)
          .lineHeight(TEXT_STYLES.body.lineHeight).fontFamily(FONT_SANS_SEMIBOLD)
          .fontColor(this.accentColorStore)
        Text(detail).fontSize(TEXT_STYLES.meta.size).fontWeight(FontWeight.Medium)
          .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO_MEDIUM)
          .fontColor(INK3).layoutWeight(1).textAlign(TextAlign.End)
          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
      .constraintSize({ minHeight: 52 })
      .padding({ left: 14, right: 14, top: 10, bottom: 10 })
      if (!isLast) {
        Row() {
          Row()
            .width('100%')
            .height(1)
            .backgroundColor(LINE)
        }
        .width('100%')
        .padding({ left: 14, right: 14 })
      }
    }
    .width('100%')
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
      Blank().onClick((): void => { this.closeEditor(); })
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
              .onClick((): void => {
                animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
                  this.typeMenuOpen = !this.typeMenuOpen;
                });
              })

              if (this.typeMenuOpen) {
                Scroll() {
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
                .width('100%')
                }
                .align(Alignment.TopStart)
                .height(220).margin({ top: 4 })
                .backgroundColor(BG).borderRadius(8)
                .transition(TransitionEffect.OPACITY.animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
              }
            }
            .width('100%').margin({ bottom: 12 }).clip(true)

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
                      .fontColor(d === this.dDepth ? ACCENT_INK : INK)
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
                this.inlineToggle(this.dSummary, (): void => { this.dSummary = !this.dSummary; })
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
        .align(Alignment.TopStart)
        .width('100%').layoutWeight(1)
        .enabled(!this.editorSaving)

        if (this.editorError.length > 0) {
          Text(this.editorError).fontSize(12).fontColor(ERROR)
            .width('100%').margin({ top: 8 })
        }

        Row() {
          if (this.editIndex >= 0 && this.s.searchServices.length > 1) {
            BtnGhost({ label: '删除提供商', onTap: (): void => { this.confirmDelete(); } })
              .enabled(!this.editorSaving)
          }
          Blank()
          BtnGhost({ label: '取消', onTap: (): void => { this.closeEditor(); } })
          BtnFill({
            label: this.editorSaving ? '保存中…' : (this.editIndex < 0 ? '添加提供商' : '保存'),
            onTap: (): void => {
              if (this.editIndex < 0) this.confirmAdd();
              else this.confirmEdit();
            },
          })
            .enabled(!this.editorSaving)
        }
        .width('100%').margin({ top: 8 })
      }
      .width('100%').height('86%').padding(16)
      .backgroundColor(SURFACE)
      .borderRadius({ topLeft: 16, topRight: 16 })
      .transition(TransitionEffect.translate({ y: 160 })
        .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
    }
    .width('100%').height('100%').backgroundColor(OVERLAY)
    .transition(TransitionEffect.OPACITY.animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
  }

  build() {
    Stack() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      Column() {
        CenteredTitleBar({
          title: '搜索服务',
          actionIcon: 'plus',
          onAction: (): void => { this.openAdd(); },
          onBack: (): void => { if (!this.onBackPress()) router.back(); },
        })

        if (!this.loaded) {
          Text('加载中…').fontSize(13).fontColor(INK3).margin({ top: 32 })
        } else {
          Scroll() {
            Column() {
              // ── AGENT 网络搜索(IMG_1991:总开关 + 状态行同卡)──
              PlainSection({ label: 'Agent 网络搜索' })
              Column() {
                CardGroup({ radius: 18, stroke: true, content: (): void => { this.heroCardContent(); } })
              }
              .width('100%').padding({ left: 16, right: 16 })

              // ── 内置免费源(六行开关)──
              PlainSection({ label: '内置免费源' })
              Column() {
                CardGroup({ radius: 18, stroke: true, content: (): void => { this.builtinCardContent(); } })
              }
              .width('100%').padding({ left: 16, right: 16 })

              // ── 已配置(序号胶囊 + 行内上移/下移 + 开关;鸿蒙无拖拽,保留既有排序交互)──
              PlainSection({ label: '已配置' })
              Column() {
                CardGroup({ radius: 18, stroke: true, content: (): void => { this.configuredCardContent(); } })
              }
              .width('100%').padding({ left: 16, right: 16 })
              FooterNote({ text: '使用行内「上移 / 下移」调整召回优先级，点按行编辑服务。' })

              // ── 通用选项(鸿蒙保留,iOS 无此组,按规范排布)──
              PlainSection({ label: '通用选项' })
              Column() {
                CardGroup({ radius: 18, stroke: true, content: (): void => { this.commonOptionsRow(); } })
              }
              .width('100%').padding({ left: 16, right: 16 })

              // ── 推荐组合(鸿蒙保留,iOS 无此组,按规范排布)──
              PlainSection({ label: '推荐组合' })
              Column() {
                CardGroup({ radius: 18, stroke: true, content: (): void => {
                  this.recommendRow('零成本', 'Jina + DuckDuckGo + Bing', false)
                  this.recommendRow('Google 结果', 'Serper / SerpAPI', false)
                  this.recommendRow('高质量多源', 'Tavily 或 Brave', true)
                } })
              }
              .width('100%').padding({ left: 16, right: 16 })

              Column().height(24)
            }
            .width('100%')
          }
          .align(Alignment.TopStart)
          .width('100%').layoutWeight(1)
        }
      }
      .width('100%').height('100%').backgroundColor(BG)

      if (this.editorVisible) {
        this.editorOverlay()
      }
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%')
  }
}
