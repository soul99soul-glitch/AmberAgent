// SettingAppearancePage — 外观(iOS AppearanceSettingsView 对位)
// 颜色模式(浅色/深色/跟随系统)+ 强调色五选一。自设置主页迁入。
import {
  BG, INK, ACCENT, ACCENT_OPTIONS, setAccentColor, applyTheme,
} from '../design/tokens.ets';
import { PageTitleBar, SectionHeader, PageSpacer, Seg } from '../components/PageTemplate.ets';
import { getChatKvStore } from '../di/AppContainer.ets';
import { applyWindowTheme } from '../entryability/EntryAbility.ets';
import type { DisplaySetting } from '@amber/chat-domain';
import { loadDisplaySetting, saveDisplaySetting } from '@amber/chat-domain';

const UI_COLOR_MODE_KEY: string = 'ui_color_mode';
const THEME_MODE_OPTIONS: string[] = ['浅色', '深色', '跟随系统'];

@Entry
@Component
struct SettingAppearancePage {
  @StorageProp('darkMode') darkMode: boolean = false;
  @StorageProp('systemDarkMode') systemDarkMode: boolean = false;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @State themeMode: string = 'system';
  @State accentColor: string = ACCENT;

  aboutToAppear(): void {
    loadDisplaySetting(getChatKvStore()).then((s: DisplaySetting): void => {
      if (s.accentColor.length > 0) {
        this.accentColor = s.accentColor;
        setAccentColor(s.accentColor);
        AppStorage.setOrCreate<string>('accentColor', s.accentColor);
      }
    }).catch((): void => {});
    getChatKvStore().get(UI_COLOR_MODE_KEY).then((raw: string | null): void => {
      if (raw === 'dark' || raw === 'light' || raw === 'system') this.themeMode = raw;
    }).catch((): void => {});
  }

  private setTheme(mode: string): void {
    this.themeMode = mode;
    getChatKvStore().put(UI_COLOR_MODE_KEY, mode);
    const resolvedDark: boolean = mode === 'dark' || (mode === 'system' && this.systemDarkMode);
    applyTheme(resolvedDark);
    applyWindowTheme(resolvedDark);
    AppStorage.setOrCreate<string>('uiColorMode', mode);
    AppStorage.setOrCreate<boolean>('darkMode', resolvedDark);
  }

  private setAccent(color: string): void {
    this.accentColor = color;
    setAccentColor(color);
    AppStorage.setOrCreate<string>('accentColor', color);
    loadDisplaySetting(getChatKvStore()).then((s: DisplaySetting): void => {
      const next: DisplaySetting = s;
      next.accentColor = color;
      saveDisplaySetting(getChatKvStore(), next);
    }).catch((): void => {});
  }

  private themeModeIndex(): number {
    if (this.themeMode === 'light') return 0;
    if (this.themeMode === 'dark') return 1;
    return 2;
  }

  build(): void {
    Column() {
      PageTitleBar({ title: '外观' })
      Scroll() {
        Column() {
          SectionHeader({ label: '颜色模式' })
          Seg({
            options: THEME_MODE_OPTIONS,
            selected: this.themeModeIndex(),
            onSelect: (idx: number): void => {
              this.setTheme(idx === 0 ? 'light' : idx === 1 ? 'dark' : 'system');
            },
          })
          SectionHeader({ label: '强调色' })
          Row() {
            ForEach(ACCENT_OPTIONS, (color: string, idx: number): void => {
              Stack({ alignContent: Alignment.Center }) {
                Column()
                  .width(this.accentColor === color ? 44 : 36)
                  .height(this.accentColor === color ? 44 : 36)
                  .borderRadius(this.accentColor === color ? 22 : 18)
                  .backgroundColor(this.accentColor === color ? BG : color)
                  .border({ width: this.accentColor === color ? 2 : 0, color: INK })
                if (this.accentColor === color) {
                  Column()
                    .width(36).height(36).borderRadius(18)
                    .backgroundColor(color)
                    .border({ width: 3, color: BG })
                }
              }
              .width(44).height(44)
              .margin({ right: idx < ACCENT_OPTIONS.length - 1 ? 12 : 0 })
              .onClick((): void => { this.setAccent(color); })
            }, (color: string, idx: number): string => `swatch-${idx}-${color}`)
          }
          .width('100%')
          .padding({ left: 0, top: 10, bottom: 4 })
          PageSpacer()
        }
        .width('100%')
        .padding({ left: 18, right: 18 })
      }
      .align(Alignment.TopStart)
      .layoutWeight(1)
      .backgroundColor(BG)
    }
    .width('100%').height('100%').backgroundColor(BG)
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
    .opacity((this.accentColorStore.length >= 0 && (this.darkMode || !this.darkMode)) ? 1 : 1)
  }
}
