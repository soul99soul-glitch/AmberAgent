// SettingAppearancePage — 外观(iOS AppearanceSettingsView 对位,iOS 二级页骨架)
// 颜色模式(浅色/深色/跟随系统)+ 强调色五选一。自设置主页迁入。
import {
  BG, INK, ACCENT, ACCENT_OPTIONS, setAccentColor, applyTheme, ERROR, TEXT_STYLES,
} from '../design/tokens.ets';
import { CenteredTitleBar, PlainSection, PageSpacer, Seg } from '../components/PageTemplate.ets';
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
  // R26:主题模式落盘失败可见回滚
  @State themeError: string = '';
  // R26:最近一次成功落盘的模式(回滚目标);竞态 guard 序号只让最新操作回滚/报错
  private persistedThemeMode: string = 'system';
  private themeSeq: number = 0;

  aboutToAppear(): void {
    loadDisplaySetting(getChatKvStore()).then((s: DisplaySetting): void => {
      if (s.accentColor.length > 0) {
        this.accentColor = s.accentColor;
        setAccentColor(s.accentColor);
        AppStorage.setOrCreate<string>('accentColor', s.accentColor);
      }
    }).catch((): void => {});
    getChatKvStore().get(UI_COLOR_MODE_KEY).then((raw: string | null): void => {
      if (raw === 'dark' || raw === 'light' || raw === 'system') {
        this.themeMode = raw;
        this.persistedThemeMode = raw;
      }
    }).catch((): void => {});
  }

  // R26:应用模式到主题 + 窗口 + AppStorage(单一入口,回滚复用)
  private applyThemeMode(mode: string): void {
    const resolvedDark: boolean = mode === 'dark' || (mode === 'system' && this.systemDarkMode);
    applyTheme(resolvedDark);
    applyWindowTheme(resolvedDark);
    AppStorage.setOrCreate<string>('uiColorMode', mode);
    AppStorage.setOrCreate<boolean>('darkMode', resolvedDark);
  }

  // R26:主题模式保存。乐观应用后 await 落盘;失败则可见回滚到最近成功值。
  //   竞态 guard:仅当本次仍是最新操作(seq 未被后操作超越)才回滚/报错,
  //   避免较早失败覆盖用户之后的选择。
  private async setTheme(mode: string): Promise<void> {
    const seq: number = ++this.themeSeq;
    this.themeMode = mode;
    this.themeError = '';
    this.applyThemeMode(mode);
    try {
      await getChatKvStore().put(UI_COLOR_MODE_KEY, mode);
      this.persistedThemeMode = mode;
    } catch (_e) {
      if (seq !== this.themeSeq) return;
      this.themeMode = this.persistedThemeMode;
      this.applyThemeMode(this.persistedThemeMode);
      this.themeError = '主题设置保存失败,已恢复原设置';
    }
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
      CenteredTitleBar({ title: '外观' })
      Scroll() {
        Column() {
          PlainSection({ label: '外观模式' })
          Column() {
            Seg({
              options: THEME_MODE_OPTIONS,
              selected: this.themeModeIndex(),
              onSelect: (idx: number): void => {
                this.setTheme(idx === 0 ? 'light' : idx === 1 ? 'dark' : 'system')
                  .catch((): void => {});
              },
            })
          }
          .width('100%')
          .padding({ left: 16, right: 16 })
          if (this.themeError.length > 0) {
            // R26:失败可见反馈(与设置页错误条同款式)
            Text(this.themeError)
              .fontSize(TEXT_STYLES.secondary.size)
              .fontWeight(TEXT_STYLES.secondary.weight)
              .lineHeight(TEXT_STYLES.secondary.lineHeight)
              .fontFamily(TEXT_STYLES.secondary.family)
              .fontColor(ERROR)
              .width('100%').maxLines(2)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
              .padding({ left: 16, right: 16 })
              .margin({ top: 8 })
          }
          PlainSection({ label: '强调色' })
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
          .padding({ left: 16, right: 16, top: 10, bottom: 4 })
          PageSpacer()
        }
        .width('100%')
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
