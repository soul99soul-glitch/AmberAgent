// SettingAppearancePage — 外观(iOS AppearanceSettingsView 对位)
// 颜色模式(浅色/深色/跟随系统)+ 强调色五选一。自设置主页迁入,
// 主页一行「外观 value=当前模式」进入。
import { bundleManager } from '@kit.AbilityKit';
import {
  BG, SURFACE, SURFACE2, RAISED, INK, INK3, LINE,
  ACCENT, ACCENT_INK, BODY2, applyTheme, setAccentColor,
} from '../design/tokens.ets';
import { PageTitleBar, SectionHeader, PageSpacer } from '../components/PageTemplate.ets';
import { getChatKvStore } from '../di/AppContainer.ets';
import { applyWindowTheme } from '../entryability/EntryAbility.ets';
import type { DisplaySetting } from '@amber/chat-domain';
import { loadDisplaySetting, saveDisplaySetting } from '@amber/chat-domain';

const UI_COLOR_MODE_KEY: string = 'ui_color_mode';

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
    });
    getChatKvStore().get(UI_COLOR_MODE_KEY).then((raw: string | null): void => {
      if (raw === 'dark' || raw === 'light' || raw === 'system') this.themeMode = raw;
    });
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
    });
  }

  build() {
    Column() {
      PageTitleBar({ title: '外观' })

      Scroll() {
        Column() {
          // ── 颜色模式 ──
          SectionHeader({ label: '颜色模式' })

          Row() {
            Row() {
              Text('浅色')
                .fontSize(BODY2)
                .fontWeight(this.themeMode === 'light' ? FontWeight.Bold : FontWeight.Normal)
                .fontColor(this.themeMode === 'light' ? INK : INK3)
            }
            .layoutWeight(1).justifyContent(FlexAlign.Center)
            .padding({ top: 9, bottom: 9 }).borderRadius(10)
            .backgroundColor(this.themeMode === 'light' ? RAISED : 'transparent')
            .onClick((): void => { this.setTheme('light'); })

            Row() {
              Text('深色')
                .fontSize(BODY2)
                .fontWeight(this.themeMode === 'dark' ? FontWeight.Bold : FontWeight.Normal)
                .fontColor(this.themeMode === 'dark' ? INK : INK3)
            }
            .layoutWeight(1).justifyContent(FlexAlign.Center)
            .padding({ top: 9, bottom: 9 }).borderRadius(10)
            .backgroundColor(this.themeMode === 'dark' ? RAISED : 'transparent')
            .onClick((): void => { this.setTheme('dark'); })

            Row() {
              Text('跟随系统')
                .fontSize(BODY2)
                .fontWeight(this.themeMode === 'system' ? FontWeight.Bold : FontWeight.Normal)
                .fontColor(this.themeMode === 'system' ? INK : INK3)
            }
            .layoutWeight(1).justifyContent(FlexAlign.Center)
            .padding({ top: 9, bottom: 9 }).borderRadius(10)
            .backgroundColor(this.themeMode === 'system' ? RAISED : 'transparent')
            .onClick((): void => { this.setTheme('system'); })
          }
          .width('100%').padding(4)
          .backgroundColor(SURFACE2).borderRadius(13)
          .margin({ top: 4 })

          // ── 强调色 ──
          SectionHeader({ label: '强调色' })
          Row() {
            ForEach(['#B8623A', '#5E9C6E', '#4F86D6', '#9277C4', '#C2607A'],
              (color: string, idx: number): void => {
                Text(this.accentColor === color ? '✓' : '')
                  .fontSize(18).fontColor(ACCENT_INK)
                  .width(38).height(38).borderRadius(11)
                  .backgroundColor(color)
                  .border({ width: this.accentColor === color ? 2 : 0, color: INK })
                  .textAlign(TextAlign.Center)
                  .margin({ right: idx < 4 ? 12 : 0 })
                  .onClick((): void => { this.setAccent(color); })
              }, (color: string, idx: number): string => `swatch-${idx}`)
          }
          .width('100%')
          .padding({ left: 6, top: 10, bottom: 4 })

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
    .opacity(this.accentColorStore.length >= 0 ? 1 : 1)
  }
}
