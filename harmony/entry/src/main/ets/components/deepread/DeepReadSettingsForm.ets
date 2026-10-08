import { router, curves } from '@kit.ArkUI';
import { makeSearchServiceOptions, loadDisplaySetting, saveDisplaySetting } from '@amber/chat-domain';
import type { SearchSettings, SearchServiceOptions, SearchServiceOptionsType, DisplaySetting } from '@amber/chat-domain';
import { DEEPREAD_SEARCH_TYPES, deepReadSearchTitle } from '../../platform_impl/DeepReadSearchDraft.ets';
import { readerLayoutName, readerStyleName } from '../../platform_impl/DeepReadAppearance.ets';
import { getChatKvStore } from '../../di/AppContainer.ets';
import type { DeepReadModelOption } from '../../di/AppContainer.ets';
import type { DeepReadTemplateDef } from '../../platform_impl/DeepReadTemplate.ets';
import { DeepReadDesign as D } from '../../design/DeepReadDesign.ets';
import { DeepReadHaptics } from '../../design/DeepReadHaptics.ets';
import { setAccentColor } from '../../design/tokens.ets';
import { Icon } from '../ui/Icon.ets';
import { DeepReadSettingsSection, DeepReadSettingsNote, DeepReadSettingsGroup,
  DeepReadSettingsRow, DeepReadSettingsToggle, DeepReadSettingsPicker, DeepReadSettingsButton } from './DeepReadSettingsVisual.ets';

interface DeepReadAccentChoice { name: string; light: string; dark: string; }
const ACCENTS: DeepReadAccentChoice[] = [
  { name: '朱砂', light: '#C8402F', dark: '#E67261' },
  { name: '黛蓝', light: '#2F5D8A', dark: '#7FA8D6' },
  { name: '松绿', light: '#2F6B57', dark: '#7DBFA4' },
  { name: '藤紫', light: '#6B4C9A', dark: '#B39DDB' },
  { name: '墨黑', light: '#2A2320', dark: '#F1E9DF' },
];

// Presentation for the independent product; existing model/template owners stay in the page.
@Component
export struct DeepReadSettingsForm {
  @StorageProp('darkMode') darkMode: boolean = false;
  @StorageProp('accentColor') accentColor: string = '#B8623A';
  @StorageProp('deepread.appearance.readerLayout') readerLayout: string = 'classic';
  @StorageProp('deepread.appearance.readerStyle') readerStyle: string = 'classic';
  @Prop modelLabel: string = '';
  @Prop modelDetail: string = '';
  @Prop modelOptions: DeepReadModelOption[] = [];
  @Prop modelError: string = '';
  @Prop templates: DeepReadTemplateDef[] = [];
  @StorageProp('reduceMotion') reduceMotion: boolean = false;
  @Link fontModeIndex: number;
  @Link fontScalePercent: number;
  @Link templateId: string;
  @Prop notice: string = '';
  @Link search: SearchSettings;
  @Link searchLoaded: boolean;
  @Link selectedSearchId: string;
  @Link searchError: string;
  @State saving: boolean = false;
  @State error: string = '';
  private alive: boolean = false;

  onModel: () => void = (): void => {};
  onSearchEdit: (serviceId: string) => void = (_serviceId: string): void => {};
  onSearchRetry: () => void = (): void => {};
  onEdited: () => void = (): void => {};
  onSave: () => Promise<void> = async (): Promise<void> => {};

  aboutToAppear(): void { this.alive = true; }
  aboutToDisappear(): void { this.alive = false; }

  private accentSelected(choice: DeepReadAccentChoice): boolean {
    return this.accentColor === choice.light || (choice.light === '#C8402F' && this.accentColor === '#B8623A');
  }

  private openTemplates(): void {
    router.pushUrl({ url: 'pages/DeepReadTemplateSettingsPage', params: {
      readerFontMode: this.fontModeIndex === 1 ? 'serif' : 'default', readerFontScale: this.fontScalePercent,
    } });
  }

  private async chooseAccent(choice: DeepReadAccentChoice): Promise<void> {
    try {
      const setting: DisplaySetting = await loadDisplaySetting(getChatKvStore());
      setting.accentColor = choice.light;
      await saveDisplaySetting(getChatKvStore(), setting);
      DeepReadHaptics.selection();
      this.getUIContext().animateTo({ duration: this.reduceMotion ? 0 : 400,
        curve: this.reduceMotion ? Curve.Linear : curves.springMotion(0.4, 0.7) }, (): void => {
        AppStorage.setOrCreate<string>('accentColor', choice.light);
        setAccentColor(choice.light);
      });
    } catch (error) { if (this.alive) this.error = '强调色保存失败：' + String(error); }
  }

  private changeSearchFlag(index: number, enabled: boolean): void {
    DeepReadHaptics.selection();
    this.onEdited();
    const next: SearchSettings = JSON.parse(JSON.stringify(this.search)) as SearchSettings;
    if (index === 0) next.searchBuiltinDuckDuckGoEnabled = enabled;
    else if (index === 1) next.searchBuiltinBingEnabled = enabled;
    else if (index === 2) next.searchBuiltinJinaEnabled = enabled;
    else if (index === 3) next.searchBuiltinWikipediaEnabled = enabled;
    else if (index === 4) next.searchBuiltinHackerNewsEnabled = enabled;
    else next.searchGoogleWebViewFallbackEnabled = enabled;
    this.search = next;
  }

  private selectSearch(index: number): void {
    DeepReadHaptics.selection();
    this.onEdited();
    const next: SearchSettings = JSON.parse(JSON.stringify(this.search)) as SearchSettings;
    next.searchServiceSelected = index;
    this.selectedSearchId = next.searchServices[index]?.id ?? '';
    this.searchError = '';
    this.search = next;
  }

  private changeResultSize(delta: number): void {
    DeepReadHaptics.selection();
    this.onEdited();
    const next: SearchSettings = JSON.parse(JSON.stringify(this.search)) as SearchSettings;
    next.searchCommonOptions.resultSize = Math.max(1, Math.min(50, next.searchCommonOptions.resultSize + delta));
    this.search = next;
  }

  private addSearch(type: SearchServiceOptionsType): void {
    const service: SearchServiceOptions = makeSearchServiceOptions(type);
    const next: SearchSettings = JSON.parse(JSON.stringify(this.search)) as SearchSettings;
    next.searchServices = next.searchServices.concat([service]);
    next.searchEnabledServiceIds = next.searchEnabledServiceIds.concat([service.id]);
    next.searchServiceSelected = next.searchServices.length - 1;
    this.search = next;
    this.selectedSearchId = service.id;
    this.searchError = '';
    this.onEdited();
    DeepReadHaptics.selection();
  }

  private deleteSearch(serviceId: string): void {
    const next: SearchSettings = JSON.parse(JSON.stringify(this.search)) as SearchSettings;
    next.searchServices = next.searchServices.filter((service: SearchServiceOptions): boolean => service.id !== serviceId);
    next.searchEnabledServiceIds = next.searchEnabledServiceIds.filter((id: string): boolean => id !== serviceId);
    if (this.selectedSearchId === serviceId) this.selectedSearchId = '';
    next.searchServiceSelected = next.searchServices.findIndex((service: SearchServiceOptions): boolean => service.id === this.selectedSearchId);
    this.search = next;
    this.searchError = '';
    this.onEdited();
  }

  @Builder
  AddSearchMenu(): void {
    Menu() {
      ForEach(DEEPREAD_SEARCH_TYPES, (type: SearchServiceOptionsType): void => {
        MenuItem({ content: deepReadSearchTitle(type) }).onClick((): void => { this.addSearch(type); })
      }, (type: SearchServiceOptionsType): string => type)
    }
  }

  @Builder
  ServiceActions(service: SearchServiceOptions): void {
    Menu() {
      MenuItem({ content: '编辑' }).onClick((): void => { this.onSearchEdit(service.id); })
      MenuItem({ content: '删除服务' }).onClick((): void => { this.deleteSearch(service.id); })
    }
  }

  private async saveSettings(): Promise<void> {
    if (!this.searchLoaded || this.saving) return;
    this.saving = true;
    this.error = '';
    try {
      await this.onSave();
    } catch (error) { if (this.alive) this.error = '保存设置失败：' + String(error); }
    finally { if (this.alive) this.saving = false; }
  }

  @Builder
  Appearance(): void {
    Row() {
      ForEach(ACCENTS, (choice: DeepReadAccentChoice): void => {
        Column({ space: 6 }) {
          Stack() {
            if (this.accentSelected(choice)) {
              Circle().width(42).height(42).fill(Color.Transparent)
                .stroke(this.darkMode ? choice.dark : choice.light).strokeWidth(2)
                .geometryTransition(this.reduceMotion ? '' : 'deepread-accent-ring')
                .transition(TransitionEffect.OPACITY.animation({ duration: this.reduceMotion ? 0 : 400 }))
            }
            Stack() {
              Circle().width(32).height(32).fill(this.darkMode ? choice.dark : choice.light)
              if (this.accentSelected(choice)) {
                Icon({ name: 'check', iconSize: 13, color: D.paper })
                  .transition(TransitionEffect.OPACITY.combine(TransitionEffect.scale({ x: 0.5, y: 0.5 }))
                    .animation({ duration: this.reduceMotion ? 0 : 400, curve: this.reduceMotion ? Curve.Linear : curves.springMotion(0.4, 0.7) }))
              }
            }.width(32).height(32).scale({ x: this.accentSelected(choice) ? 1 : 0.9, y: this.accentSelected(choice) ? 1 : 0.9 })
              .animation({ duration: this.reduceMotion ? 0 : 400, curve: this.reduceMotion ? Curve.Linear : curves.springMotion(0.4, 0.7) })
          }.width(44).height(44)
          Text(choice.name).fontSize(11).fontWeight(this.accentSelected(choice) ? 600 : 400)
            .fontColor(this.accentSelected(choice) ? D.ink : D.muted)
        }.width('20%').padding({ top: 4, bottom: 4 }).accessibilityText(choice.name)
          .onClick((): void => { this.chooseAccent(choice); })
      }, (choice: DeepReadAccentChoice): string => choice.light)
    }.width('100%').padding({ left: 8, right: 8 })
    DeepReadSettingsRow({ label: '版式与样式', last: true,
      value: readerLayoutName(this.readerLayout) + ' · ' + readerStyleName(this.readerStyle),
      onTap: (): void => { router.pushUrl({ url: 'pages/DeepReadAppearancePage' }); } })
  }

  @Builder
  Generation(): void {
    DeepReadSettingsRow({ label: '生成模型', value: this.modelLabel, valueAccent: true, picker: true,
      onTap: (): void => { this.onModel(); } })
    DeepReadSettingsRow({ label: '模型服务', detail: '管理服务地址、凭据与模型', last: true,
      onTap: (): void => { router.pushUrl({ url: 'pages/ChatProviderSettingsPage' }); } })
  }

  @Builder
  Search(): void {
    DeepReadSettingsPicker({ label: '首选搜索服务', selected: this.search.searchServices.findIndex(
      (service: SearchServiceOptions): boolean => service.id === this.selectedSearchId) + 1,
      options: ['请选择服务'].concat(this.search.searchServices.map((service: SearchServiceOptions): string => deepReadSearchTitle(service.type))),
      onSelect: (index: number): void => { this.selectSearch(index - 1); } })
    ForEach(this.search.searchServices, (service: SearchServiceOptions): void => {
      DeepReadSettingsRow({ label: deepReadSearchTitle(service.type),
        value: this.search.searchEnabledServiceIds.includes(service.id) ? '启用' : '停用',
        onTap: (): void => { this.onSearchEdit(service.id); } })
        .bindContextMenu(this.ServiceActions(service), ResponseType.LongPress)
    }, (service: SearchServiceOptions): string => service.id)
    DeepReadSettingsRow({ label: '添加搜索服务', action: true, chevron: false })
      .bindMenu(this.AddSearchMenu())
    Row({ space: 4 }) {
      Text(`每次搜索最多 ${this.search.searchCommonOptions.resultSize} 条`).fontSize(17).fontColor(D.ink).layoutWeight(1)
      Button('−').width(44).height(44).fontSize(22).fontColor(D.ink).backgroundColor(D.paper)
        .onClick((): void => { this.changeResultSize(-1); })
      Button('+').width(44).height(44).fontSize(22).fontColor(D.ink).backgroundColor(D.paper)
        .onClick((): void => { this.changeResultSize(1); })
    }.width('100%').height(50).padding({ left: 16, right: 16 })
  }

  @Builder
  FreeSources(): void {
    DeepReadSettingsToggle({ label: 'DuckDuckGo', checked: this.search.searchBuiltinDuckDuckGoEnabled,
      onChange: (value: boolean): void => { this.changeSearchFlag(0, value); } })
    DeepReadSettingsToggle({ label: 'Bing', checked: this.search.searchBuiltinBingEnabled,
      onChange: (value: boolean): void => { this.changeSearchFlag(1, value); } })
    DeepReadSettingsToggle({ label: 'Jina 搜索与正文补充读取', checked: this.search.searchBuiltinJinaEnabled,
      onChange: (value: boolean): void => { this.changeSearchFlag(2, value); } })
    DeepReadSettingsToggle({ label: 'Wikipedia', checked: this.search.searchBuiltinWikipediaEnabled,
      onChange: (value: boolean): void => { this.changeSearchFlag(3, value); } })
    DeepReadSettingsToggle({ label: 'Hacker News', checked: this.search.searchBuiltinHackerNewsEnabled,
      onChange: (value: boolean): void => { this.changeSearchFlag(4, value); } })
    DeepReadSettingsToggle({ label: 'Google 网页补充搜索', checked: this.search.searchGoogleWebViewFallbackEnabled, last: true,
      onChange: (value: boolean): void => { this.changeSearchFlag(5, value); } })
  }

  @Builder
  Reading(): void {
    DeepReadSettingsPicker({ label: '字体', options: ['系统字体', '衬线字体'], selected: this.fontModeIndex,
      onSelect: (index: number): void => { DeepReadHaptics.selection(); this.fontModeIndex = index; this.onEdited(); } })
    Row() {
      Text('字号').fontSize(17).fontColor(D.ink).layoutWeight(1)
      Text(`${Math.round(this.fontScalePercent)}%`).fontSize(17).fontColor(D.muted)
    }.width('100%').height(48).padding({ left: 16, right: 16 })
    Slider({ value: this.fontScalePercent, min: 70, max: 180, step: 5 }).selectedColor(D.accent)
      .width('100%').padding({ left: 16, right: 16, bottom: 8 })
      .onChange((value: number): void => { this.fontScalePercent = Math.round(value); this.onEdited(); })
    DeepReadSettingsPicker({ label: '生成模板', options: this.templates.map((template: DeepReadTemplateDef): string => template.name),
      selected: Math.max(0, this.templates.findIndex((template: DeepReadTemplateDef): boolean => template.id === this.templateId)),
      onSelect: (index: number): void => { DeepReadHaptics.selection(); this.templateId = this.templates[index].id; this.onEdited(); } })
    DeepReadSettingsRow({ label: '管理与创建模板', last: true,
      onTap: (): void => { this.openTemplates(); } })
  }

  build(): void {
    Column() {
      DeepReadSettingsSection({ title: '外观' })
      DeepReadSettingsGroup({ content: (): void => { this.Appearance(); } })
      DeepReadSettingsNote({ text: '即时生效，文章里的强调色也会同步更换。' })
      DeepReadSettingsSection({ title: '文章生成' })
      DeepReadSettingsGroup({ content: (): void => { this.Generation(); } })
      DeepReadSettingsNote({ text: '使用你自己的 API 凭据。生成模型与模板 AI 共用已配置的服务商。' })
      if (this.modelError.length > 0) { DeepReadSettingsNote({ text: this.modelError }) }
      DeepReadSettingsSection({ title: '多来源搜索' })
      DeepReadSettingsGroup({ content: (): void => { this.Search(); } }).enabled(this.searchLoaded && !this.saving)
      DeepReadSettingsNote({ text: '优先使用选中的搜索服务；不可用时，选择其他已启用的可用服务。' })
      if (this.searchError.length > 0) {
        Text(this.searchError).fontSize(13).fontColor(D.danger).width('100%')
          .padding({ left: 16, right: 16, top: 8 })
        Button('重新读取搜索配置').fontSize(14).fontColor(D.accent).backgroundColor(Color.Transparent)
          .height(44).onClick((): void => { this.onSearchRetry(); })
      }
      DeepReadSettingsSection({ title: '免费聚合与正文读取' })
      DeepReadSettingsGroup({ content: (): void => { this.FreeSources(); } }).enabled(this.searchLoaded && !this.saving)
      DeepReadSettingsSection({ title: '阅读与排版' })
      DeepReadSettingsGroup({ content: (): void => { this.Reading(); } })
      DeepReadSettingsButton({ label: this.saving ? '保存中…' : '保存并应用', icon: 'check_circle', success: this.notice.startsWith('已保存'), disabled: !this.searchLoaded || this.saving,
        onTap: (): void => { this.saveSettings(); } }).margin({ top: 28 })
      if (this.notice.length > 0) {
        DeepReadSettingsNote({ text: this.notice })
          .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: this.reduceMotion ? 0 : -10 }))
            .animation({ duration: this.reduceMotion ? 0 : 300, curve: Curve.EaseOut }))
      }
      if (this.error.length > 0) {
        Text(this.error).fontSize(13).fontColor(D.danger).width('100%').padding({ top: 10, left: 16, right: 16 })
      }
    }.width('100%').enabled(!this.saving)
  }
}
