import { router } from '@kit.ArkUI';
import { BG, INK, INK3, ACCENT, ACCENT_INK, SURFACE, SURFACE2, ERROR, LINE } from '../design/tokens.ets';
import { FONT_SERIF } from '../design/fonts.ets';
import { PageTitleBar, PlainSection } from '../components/PageTemplate.ets';
import { Icon } from '../components/ui/Icon.ets';
import { DeepReadHaptics } from '../design/DeepReadHaptics.ets';
import { NOVEL_THEMES, NovelTheme } from '../design/novel_themes.ets';
import { ThemePack, ThemePalette, themePackStore, applyThemePack, resolveThemeDark } from '../design/theme_packs.ets';
import { getChatKvStore } from '../di/AppContainer.ets';
import { applyWindowTheme } from '../entryability/EntryAbility.ets';

@Entry
@Component
struct NovelAppearancePage {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('darkMode') darkMode: boolean = false;
  @StorageProp('systemDarkMode') systemDarkMode: boolean = false;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @StorageProp('reduceMotion') reduceMotion: boolean = false;
  @StorageProp('appBackgrounded') appBackgrounded: boolean = false;
  @State mode: string = 'system';
  @State selectedId: string = '';
  @State fixedMode: boolean = false;
  @State loading: boolean = true;
  @State saving: boolean = false;
  @State error: string = '';
  private alive: boolean = false;

  aboutToAppear(): void { this.alive = true; this.load(); }
  aboutToDisappear(): void { this.alive = false; }
  onBackPress(): boolean { return this.saving; }
  private back(): void { if (!this.saving) router.back(); }

  private async load(): Promise<void> {
    this.loading = true;
    this.error = '';
    try {
      await themePackStore.hydrate(getChatKvStore());
      const mode: string | null = await getChatKvStore().get('ui_color_mode');
      if (!this.alive) return;
      this.mode = mode === 'light' || mode === 'dark' ? mode : 'system';
      this.selectedId = themePackStore.current().id;
      this.fixedMode = themePackStore.current().fixedColorMode !== undefined;
    } catch (error) {
      if (this.alive) this.error = '读取外观失败：' + String(error);
    } finally {
      if (this.alive) this.loading = false;
    }
  }

  private apply(pack: ThemePack): void {
    const preferredDark: boolean = this.mode === 'dark' || (this.mode === 'system' && this.systemDarkMode);
    const dark: boolean = resolveThemeDark(pack, preferredDark);
    const update: () => void = (): void => {
      applyThemePack(pack, dark);
      this.selectedId = pack.id;
      this.fixedMode = pack.fixedColorMode !== undefined;
      AppStorage.setOrCreate<string>('themePackId', pack.id);
      AppStorage.setOrCreate<boolean>('homeDotGrid', pack.dotGrid);
      AppStorage.setOrCreate<string>('accentColor', pack.accent);
      AppStorage.setOrCreate<string>('uiColorMode', this.mode);
      AppStorage.setOrCreate<boolean>('darkMode', dark);
    };
    if (!this.alive || this.appBackgrounded) { update(); }
    else this.getUIContext().animateTo({ duration: this.reduceMotion || this.appBackgrounded ? 0 : 250,
      curve: Curve.EaseInOut }, update);
    applyWindowTheme(dark);
  }

  private async selectTheme(theme: NovelTheme): Promise<void> {
    if (this.loading || this.saving || this.isSelected(theme) || this.error.length > 0) return;
    this.saving = true;
    try {
      await themePackStore.select(getChatKvStore(), theme);
      // 即便离开页面，保存成功的全局主题仍要与磁盘保持一致。
      this.apply(theme);
      DeepReadHaptics.selection();
    } catch (error) {
      if (this.alive) this.error = '保存主题失败：' + String(error);
    } finally {
      if (this.alive) this.saving = false;
    }
  }

  private async selectMode(mode: string): Promise<void> {
    if (this.loading || this.saving || this.fixedMode || mode === this.mode || this.error.length > 0) return;
    this.saving = true;
    try {
      await getChatKvStore().put('ui_color_mode', mode);
      this.mode = mode;
      this.apply(themePackStore.current());
      DeepReadHaptics.selection();
    } catch (error) {
      if (this.alive) this.error = '保存外观失败：' + String(error);
    } finally {
      if (this.alive) this.saving = false;
    }
  }

  private isSelected(theme: NovelTheme): boolean {
    const current: ThemePack = themePackStore.current();
    // 导入配方也按完整视觉配方匹配；旧点阵包不会误选为小说平涂主题。
    return this.selectedId.length > 0 && ACCENT === theme.accent && ACCENT_INK === theme.accentInk
      && !current.dotGrid && (current.patterns === undefined || current.patterns.length === 0)
      && current.gradient === undefined && current.fixedColorMode === theme.fixedColorMode && JSON.stringify(current.light) === JSON.stringify(theme.light)
      && JSON.stringify(current.dark) === JSON.stringify(theme.dark);
  }

  @Builder
  private ModeChoice(label: string, mode: string): void {
    Text(label).fontSize(13).fontWeight(this.mode === mode ? 600 : 400).fontColor(INK)
      .textAlign(TextAlign.Center).layoutWeight(1).height(40).borderRadius(8).margin(2)
      .backgroundColor(this.mode === mode ? SURFACE : Color.Transparent)
      .onClick((): void => { this.selectMode(mode); })
  }

  private previewPalette(theme: NovelTheme): ThemePalette {
    return this.darkMode ? theme.dark : theme.light;
  }

  @Builder
  private Preview(theme: NovelTheme): void {
    Column({ space: 9 }) {
      Row().width(44).height(5).borderRadius(3).backgroundColor(this.previewPalette(theme).ink)
      Column({ space: 4 }) {
        Row().width('85%').height(3).borderRadius(2).backgroundColor(this.previewPalette(theme).ink).opacity(0.35)
        Row().width('85%').height(3).borderRadius(2).backgroundColor(this.previewPalette(theme).ink).opacity(0.35)
        Row().width(40).height(3).borderRadius(2).backgroundColor(this.previewPalette(theme).ink).opacity(0.35)
      }.width('100%').alignItems(HorizontalAlign.Start).padding(9).borderRadius(7)
        .backgroundColor(this.previewPalette(theme).surface)
      Row() {
        Row().width(36).height(12).borderRadius(6).backgroundColor(theme.accent)
        Blank()
        Row().width(11).height(11).borderRadius(2).backgroundColor('#C8402F').rotate({ angle: -6 })
      }.width('100%')
    }.width('100%').height(96).alignItems(HorizontalAlign.Start).justifyContent(FlexAlign.SpaceBetween)
      .padding(11).backgroundColor(this.previewPalette(theme).bg).borderRadius(14)
      .border({ width: this.isSelected(theme) ? 2 : 0.75, color: this.isSelected(theme) ? theme.accent : LINE })
  }

  @Builder
  private ThemeCard(theme: NovelTheme): void {
    Column({ space: 8 }) {
      this.Preview(theme)
      Row({ space: 6 }) {
        Column({ space: 3 }) {
          Text(theme.displayName).fontFamily(FONT_SERIF).fontSize(15).fontWeight(600).fontColor(INK)
          Text(theme.note).fontSize(10).fontColor(INK3).maxLines(1).minFontSize(8).maxFontSize(10)
        }.alignItems(HorizontalAlign.Start).layoutWeight(1)
        if (this.isSelected(theme)) { Icon({ name: 'check_circle', iconSize: 17, color: theme.accent }) }
      }.width('100%')
    }.layoutWeight(1).alignItems(HorizontalAlign.Start)
      .enabled(!this.loading && !this.saving && this.error.length === 0)
      .accessibilityText(theme.displayName + '，' + theme.note + (this.isSelected(theme) ? '，已选中' : ''))
      .onClick((): void => { this.selectTheme(theme); })
  }

  build(): void {
    Column() {
      ForEach([this.themeEpoch], (_epoch: number): void => {
        Column() {
          PageTitleBar({ title: '外观与主题', onBack: (): void => { this.back(); } })
          Scroll() {
            Column() {
              PlainSection({ label: '外观模式' })
              Row() {
                this.ModeChoice('浅色', 'light')
                this.ModeChoice('深色', 'dark')
                this.ModeChoice('跟随系统', 'system')
              }.width('100%').backgroundColor(SURFACE2).borderRadius(10)
                .enabled(!this.fixedMode && !this.loading && !this.saving && this.error.length === 0)
                .opacity(this.fixedMode ? 0.45 : 1)
              if (this.fixedMode) {
                Text('当前主题自带画布颜色，明暗随主题固定。').fontSize(12).fontColor(INK3)
                  .lineHeight(18).width('100%').margin({ top: 10 })
              }
              PlainSection({ label: '小说主题' })
              Column({ space: 16 }) {
                ForEach([0, 2, 4, 6], (index: number): void => {
                  Row({ space: 14 }) {
                    this.ThemeCard(NOVEL_THEMES[index])
                    this.ThemeCard(NOVEL_THEMES[index + 1])
                  }.width('100%').alignItems(VerticalAlign.Top)
                }, (index: number): string => String(index))
              }.width('100%')
              if (this.loading) { Text('加载中…').fontSize(13).fontColor(INK3).padding(16) }
              if (this.error.length > 0) {
                Text(this.error).fontSize(13).fontColor(ERROR).width('100%').padding({ top: 16 })
                Text('重试').fontSize(14).fontColor(INK).padding(12).onClick((): void => { this.load(); })
              }
            }.width('100%').padding({ left: 20, right: 20, bottom: this.navBarHeight + 24 })
          }.layoutWeight(1).width('100%')
        }.width('100%').height('100%').backgroundColor(BG)
      }, (epoch: number): string => String(epoch))
    }.width('100%').height('100%')
  }
}
