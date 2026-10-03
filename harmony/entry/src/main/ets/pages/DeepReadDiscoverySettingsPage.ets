import { getAppContainer } from '../di/AppContainer.ets';
import { DEEPREAD_DISCOVERY_CATEGORIES, DEEPREAD_DISCOVERY_SOURCES } from '@amber/deepread-domain';
import type { Storage, DeepReadDiscoveryCategory } from '@amber/deepread-domain';
import { BG, INK, INK3, INK4, SURFACE2, ERROR, GUTTER, META } from '../design/tokens.ets';
import { FONT_SANS_MEDIUM } from '../design/fonts.ets';
import { CenteredTitleBar, PlainSection, CardGroup, CardToggleRow, FooterNote, BtnFill } from '../components/PageTemplate.ets';
import { PillSelect } from '../components/ui/Select.ets';

interface DiscoverySourceToggle {
  id: string;
  displayName: string;
  category: DeepReadDiscoveryCategory;
  enabled: boolean;
}

@Entry
@Component
struct DeepReadDiscoverySettingsPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @State sources: DiscoverySourceToggle[] = [];
  @State focusKeywords: string = '';
  @State focusModeIndex: number = 0;
  @State refreshMinutes: number = 60;
  @State wifiOnly: boolean = false;
  @State translateZh: boolean = false;
  @State loading: boolean = true;
  @State settingsLoaded: boolean = false;
  @State saving: boolean = false;
  @State notice: string = '';
  @State error: string = '';
  private alive: boolean = false;
  private loadToken: number = 0;

  aboutToAppear(): void {
    this.alive = true;
    const token: number = ++this.loadToken;
    this.loadSettings(token);
  }

  aboutToDisappear(): void {
    this.alive = false;
    this.loadToken++;
  }

  private async loadSettings(token: number): Promise<void> {
    this.loading = true;
    try {
      const storage: Storage = getAppContainer().storage;
      const toggles: DiscoverySourceToggle[] = [];
      for (const source of DEEPREAD_DISCOVERY_SOURCES) {
        const enabled: string = await storage.get<string>('source_' + source.id,
          source.defaultEnabled === false ? 'false' : 'true');
        toggles.push({ id: source.id, displayName: source.displayName, category: source.category,
          enabled: enabled === 'true' });
      }
      const keywords: string = await storage.get<string>('deepread_focus_keywords', '');
      const mode: string = await storage.get<string>('deepread_focus_mode', 'all');
      const minutes: number = await storage.get<number>('deepread_hotlist_refresh_minutes', 60);
      const wifi: string = await storage.get<string>('deepread_hotlist_wifi_only', 'false');
      const translate: string = await storage.get<string>('deepread_hotlist_translate_zh', 'false');
      if (!this.alive || token !== this.loadToken) return;
      this.sources = toggles;
      this.focusKeywords = keywords;
      this.focusModeIndex = mode === 'focus_first' ? 1 : mode === 'focus_only' ? 2 : 0;
      this.refreshMinutes = [15, 30, 60, 120].includes(minutes) ? minutes : 60;
      this.wifiOnly = wifi === 'true';
      this.translateZh = translate === 'true';
      this.settingsLoaded = true;
      this.error = '';
    } catch (error) {
      if (this.alive && token === this.loadToken) this.error = `读取发现设置失败：${String(error)}`;
    } finally {
      if (this.alive && token === this.loadToken) this.loading = false;
    }
  }

  private changeSource(id: string): void {
    this.sources = this.sources.map((source: DiscoverySourceToggle): DiscoverySourceToggle => ({
      id: source.id, displayName: source.displayName, category: source.category,
      enabled: source.id === id ? !source.enabled : source.enabled,
    }));
    this.notice = '';
  }

  private sourceGroup(category: DeepReadDiscoveryCategory): DiscoverySourceToggle[] {
    return this.sources.filter((source: DiscoverySourceToggle): boolean => source.category === category);
  }

  private async saveSettings(): Promise<void> {
    if (!this.settingsLoaded || this.loading || this.saving) return;
    this.saving = true;
    this.notice = '';
    this.error = '';
    const token: number = this.loadToken;
    const toggles: DiscoverySourceToggle[] = this.sources.slice();
    const keywords: string = this.focusKeywords.trim();
    const mode: string = this.focusModeIndex === 1 ? 'focus_first' : this.focusModeIndex === 2 ? 'focus_only' : 'all';
    const minutes: number = this.refreshMinutes;
    const wifi: boolean = this.wifiOnly;
    const translate: boolean = this.translateZh;
    try {
      const storage: Storage = getAppContainer().storage;
      for (const source of toggles) await storage.set<string>('source_' + source.id, source.enabled ? 'true' : 'false');
      await storage.set<string>('deepread_focus_keywords', keywords);
      await storage.set<string>('deepread_focus_mode', mode);
      await storage.set<number>('deepread_hotlist_refresh_minutes', minutes);
      await storage.set<string>('deepread_hotlist_wifi_only', wifi ? 'true' : 'false');
      await storage.set<string>('deepread_hotlist_translate_zh', translate ? 'true' : 'false');
      if (this.alive && token === this.loadToken) this.notice = '已保存，返回发现页后应用';
    } catch (error) {
      if (this.alive && token === this.loadToken) this.error = `保存发现设置失败：${String(error)}`;
    } finally {
      if (this.alive && token === this.loadToken) this.saving = false;
    }
  }

  @Builder
  SourceGroup(category: DeepReadDiscoveryCategory): void {
    Column() {
      ForEach(this.sourceGroup(category), (source: DiscoverySourceToggle, index: number): void => {
        CardToggleRow({ label: source.displayName, checked: source.enabled,
          isLast: index === this.sourceGroup(category).length - 1,
          onToggle: (): void => { this.changeSource(source.id); } })
      }, (source: DiscoverySourceToggle): string => source.id)
    }.width('100%')
  }

  @Builder
  FocusCard(): void {
    Column({ space: 10 }) {
      TextInput({ text: this.focusKeywords, placeholder: 'AI、大模型、机器人、数码…' })
        .height(44).fontSize(15).fontColor(INK).placeholderColor(INK4)
        .backgroundColor(SURFACE2).borderRadius(8).padding({ left: 12, right: 12 })
        .onChange((value: string): void => { this.focusKeywords = value; this.notice = ''; })
      Row() {
        Text('筛选模式').fontSize(16).fontFamily(FONT_SANS_MEDIUM).fontColor(INK).layoutWeight(1)
        PillSelect({ options: ['全部', '关注优先', '仅关注'], selectedIndex: this.focusModeIndex,
          triggerColor: INK, triggerMinHeight: 44,
          onSelect: (index: number): void => { this.focusModeIndex = index; this.notice = ''; } })
          .width(112)
      }.width('100%').alignItems(VerticalAlign.Center).constraintSize({ minHeight: 44 })
    }.width('100%').padding({ left: 14, right: 14, top: 12, bottom: 12 })
  }

  @Builder
  RefreshCard(): void {
    Column() {
      Row() {
        Text('缓存刷新间隔').fontSize(16).fontFamily(FONT_SANS_MEDIUM).fontColor(INK).layoutWeight(1)
        PillSelect({ options: ['15 分钟', '30 分钟', '1 小时', '2 小时'],
          selectedIndex: [15, 30, 60, 120].indexOf(this.refreshMinutes), triggerColor: INK, triggerMinHeight: 44,
          onSelect: (index: number): void => { this.refreshMinutes = [15, 30, 60, 120][index]; this.notice = ''; } })
          .width(112)
      }.width('100%').alignItems(VerticalAlign.Center).constraintSize({ minHeight: 52 })
        .padding({ left: 14, right: 14, top: 8, bottom: 8 })
      CardToggleRow({ label: '仅在 Wi-Fi 下刷新', desc: '移动网络下保留缓存与当前榜单', checked: this.wifiOnly,
        onToggle: (): void => { this.wifiOnly = !this.wifiOnly; this.notice = ''; } })
      CardToggleRow({ label: '显示中文标题', desc: '翻译英文标题，保留原始来源资料', checked: this.translateZh, isLast: true,
        onToggle: (): void => { this.translateZh = !this.translateZh; this.notice = ''; } })
    }.width('100%')
  }

  build(): void {
    Column() {
      ForEach([this.themeEpoch], (_epoch: number): void => {
        Column() {
          CenteredTitleBar({ title: '发现设置' })
          Scroll() {
            Column() {
              if (this.loading) {
                Text('正在读取设置…').fontSize(META).fontColor(INK3).padding(20)
              } else if (this.settingsLoaded) {
                PlainSection({ label: '关注内容' })
                CardGroup({ radius: 18, stroke: true, content: (): void => { this.FocusCard(); } })
                  .margin({ left: GUTTER, right: GUTTER })
                FooterNote({ text: '多个关键词用逗号或换行分隔；关注优先调整顺序，仅关注过滤未匹配条目。' })
                ForEach(DEEPREAD_DISCOVERY_CATEGORIES, (category: DeepReadDiscoveryCategory): void => {
                  PlainSection({ label: category })
                  CardGroup({ radius: 18, stroke: true, content: (): void => { this.SourceGroup(category); } })
                    .margin({ left: GUTTER, right: GUTTER })
                }, (category: DeepReadDiscoveryCategory): string => category)
                PlainSection({ label: '刷新与显示' })
                CardGroup({ radius: 18, stroke: true, content: (): void => { this.RefreshCard(); } })
                  .margin({ left: GUTTER, right: GUTTER })
                FooterNote({ text: '来源刷新失败时保留已缓存结果。发现条目的标题、来源和排名会随深度阅读一起保存。' })
                Row() {
                  BtnFill({ label: this.saving ? '保存中…' : '保存发现设置', onTap: (): void => { this.saveSettings(); } })
                    .width('100%')
                }.width('100%').margin({ top: 20 }).padding({ left: GUTTER, right: GUTTER })
                if (this.notice.length > 0) {
                  Text(this.notice).fontSize(META).fontColor(INK3).width('100%').margin({ top: 12 })
                    .padding({ left: GUTTER, right: GUTTER })
                }
              }
              if (this.error.length > 0) {
                Text(this.error).fontSize(META).fontColor(ERROR).width('100%').margin({ top: 12 })
                  .padding({ left: GUTTER, right: GUTTER })
                if (!this.settingsLoaded) {
                  Row() {
                    BtnFill({ label: '重试读取', onTap: (): void => { this.loadSettings(++this.loadToken); } })
                      .width('100%')
                  }.width('100%').margin({ top: 16 }).padding({ left: GUTTER, right: GUTTER })
                }
              }
            }.width('100%').alignItems(HorizontalAlign.Start).enabled(!this.saving)
              .padding({ bottom: this.navBarHeight + 28 })
          }.width('100%').layoutWeight(1).align(Alignment.TopStart)
        }.width('100%').height('100%').backgroundColor(BG)
          .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
      }, (epoch: number): string => `theme-${epoch}`)
    }.width('100%').height('100%').backgroundColor(BG)
  }
}
