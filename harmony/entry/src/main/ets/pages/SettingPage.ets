// SettingPage — 设置入口(G8 对齐 SettingsScreen 设计稿)
//
// 结构梳理(对齐 Android 层次):外观 / 模型与服务 / Agent Runtime / 数据 / 关于。
//   无 profile 卡、无订阅占位、无死占位行(退出登录/通知)。

import { router } from '@kit.ArkUI';
import { bundleManager } from '@kit.AbilityKit';
import {
  BG, SURFACE, SURFACE2, RAISED, INK, INK3, LINE,
  ACCENT, ACCENT_INK, BODY2, LABEL, applyTheme, setAccentColor,
} from '../design/tokens.ets';
import { PageTitleBar, SectionHeader, NavRow, PageSpacer } from '../components/PageTemplate.ets';
import { describeChatModel, getChatKvStore } from '../di/AppContainer.ets';
import type { DisplaySetting } from '@amber/chat-domain';
import { loadDisplaySetting, saveDisplaySetting } from '@amber/chat-domain';

// 主题选择持久化键(全局调色板应用登记 P1:tokens 为静态常量)
const UI_COLOR_MODE_KEY: string = 'ui_color_mode';

@Entry
@Component
struct SettingPage {
  // theme swap triggers full-page rebuild (build re-reads mutable tokens)
  @StorageProp('darkMode') darkMode: boolean = false;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @State themeMode: string = 'light';
  @State accentColor: string = ACCENT;
  // 真实值装配:默认模型标签 + 版本号(不写死占位)
  @State modelLabel: string = '';
  @State versionLabel: string = '';

  aboutToAppear(): void {
    describeChatModel().then((label: string): void => { this.modelLabel = label; });
    try {
      const info = bundleManager.getBundleInfoForSelfSync(bundleManager.BundleFlag.GET_BUNDLE_INFO_DEFAULT);
      this.versionLabel = `${info.versionName} (${info.versionCode})`;
    } catch (e) {
      this.versionLabel = '';
    }
    // 真实持久化回读:强调色(DisplaySetting)+ 主题(ui_color_mode)
    loadDisplaySetting(getChatKvStore()).then((s: DisplaySetting): void => {
      if (s.accentColor.length > 0) {
        this.accentColor = s.accentColor;
        setAccentColor(s.accentColor);
        AppStorage.setOrCreate<string>('accentColor', s.accentColor);
      }
    });
    getChatKvStore().get(UI_COLOR_MODE_KEY).then((raw: string | null): void => {
      if (raw === 'dark' || raw === 'light') this.themeMode = raw;
    });
  }

  private setTheme(mode: string): void {
    this.themeMode = mode;
    getChatKvStore().put(UI_COLOR_MODE_KEY, mode);
    applyTheme(mode === 'dark');
    AppStorage.setOrCreate<boolean>('darkMode', mode === 'dark');
    // tokens 于 build 读取 → 重建当前页即时生效(栈内他页回栈时重建)
    // @StorageProp('darkMode') 驱动各根页重建读新令牌
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

  private go(page: string): void {
    router.pushUrl({ url: page });
  }

  build(): void {
    Column() {
      PageTitleBar({ title: '设置' })

      Scroll() {
        Column() {
          // ── 外观 ──
          SectionHeader({ label: 'APPEARANCE' })

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
          }
          .width('100%').padding(4)
          .backgroundColor(SURFACE2).borderRadius(13)
          .margin({ top: 4 })

          Row() {
            ForEach(['#B8623A', '#5E9C6E', '#4F86D6', '#9277C4', '#C2607A'], (color: string, idx: number): void => {
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

          Column() {
            NavRow({ label: '显示设置', isLast: true, onRowClick: (): void => { this.go('pages/SettingDisplayPage'); } })
          }
          .width('100%').backgroundColor(SURFACE)
          .border({ width: 1, color: LINE }).borderRadius(14).clip(true)

          // ── 模型与服务 ──
          SectionHeader({ label: 'MODELS & SERVICES' })
          Column() {
            NavRow({ label: '默认模型', valueText: this.modelLabel, onRowClick: (): void => { this.go('pages/ChatModelPickerPage'); } })
            NavRow({ label: '任务模型', onRowClick: (): void => { this.go('pages/ChatTaskModelsPage'); } })
            NavRow({ label: '服务商与密钥', onRowClick: (): void => { this.go('pages/ChatProviderSettingsPage'); } })
            NavRow({ label: '联网搜索', onRowClick: (): void => { this.go('pages/ChatSearchSettingsPage'); } })
            NavRow({ label: 'TTS', onRowClick: (): void => { this.go('pages/SettingTTSProviderPage'); } })
            NavRow({ label: '深度阅读设置', isLast: true, onRowClick: (): void => { this.go('pages/SettingDeepReadPage'); } })
          }
          .width('100%').backgroundColor(SURFACE)
          .border({ width: 1, color: LINE }).borderRadius(14).clip(true)

          // ── Agent Runtime ──
          SectionHeader({ label: 'AGENT RUNTIME' })
          Column() {
            NavRow({ label: '技能', onRowClick: (): void => { this.go('pages/SkillsPage'); } })
            NavRow({ label: '核心记忆', onRowClick: (): void => { this.go('pages/SettingAgentMemoryPageFull'); } })
            NavRow({ label: '提示词', onRowClick: (): void => { this.go('pages/ChatPromptsPage'); } })
            NavRow({ label: 'MCP 服务器', onRowClick: (): void => { this.go('pages/ChatMcpSettingsPage'); } })
            NavRow({ label: '执行参数', onRowClick: (): void => { this.go('pages/ChatAgentExecutionPage'); } })
            NavRow({ label: '权限与批准', onRowClick: (): void => { this.go('pages/ChatAgentPermissionsPage'); } })
            NavRow({ label: '沙盒设置', onRowClick: (): void => { this.go('pages/SettingSandboxPage'); } })
            NavRow({ label: '定时任务', onRowClick: (): void => { this.go('pages/SettingCronTasksPage'); } })
            NavRow({ label: '实验功能', isLast: true, onRowClick: (): void => { this.go('pages/SettingExperimentalPage'); } })
          }
          .width('100%').backgroundColor(SURFACE)
          .border({ width: 1, color: LINE }).borderRadius(14).clip(true)

          // ── 数据 ──
          SectionHeader({ label: 'DATA' })
          Column() {
            NavRow({ label: '历史记录', onRowClick: (): void => { this.go('pages/HistoryPage'); } })
            NavRow({ label: '收藏', onRowClick: (): void => { this.go('pages/FavoritePage'); } })
            NavRow({ label: '今日看板', onRowClick: (): void => { this.go('pages/BoardPage'); } })
            NavRow({ label: '文件管理', isLast: true, onRowClick: (): void => { this.go('pages/SettingFilesPage'); } })
          }
          .width('100%').backgroundColor(SURFACE)
          .border({ width: 1, color: LINE }).borderRadius(14).clip(true)

          // ── 关于 ──
          SectionHeader({ label: 'ABOUT' })
          Column() {
            NavRow({ label: '版本', valueText: this.versionLabel, showChevron: false })
            NavRow({ label: '关于', isLast: true, onRowClick: (): void => { this.go('pages/SettingAboutPage'); } })
          }
          .width('100%').backgroundColor(SURFACE)
          .border({ width: 1, color: LINE }).borderRadius(14).clip(true)

          // 1:1 原型版本行:$ 提示符陶土色
          Row() {
            Text('$').fontColor(ACCENT).fontSize(LABEL).fontFamily('JetBrainsMono')
            Text(` amber v${this.versionLabel}`).fontColor(INK3).fontSize(LABEL).fontFamily('JetBrainsMono')
          }
          .width('100%').justifyContent(FlexAlign.Center).margin({ top: 22 })

          PageSpacer()
        }
        .width('100%').padding({ left: 18, right: 18 })
      }
      .layoutWeight(1).backgroundColor(BG)
    }
    .width('100%').height('100%').backgroundColor(BG)
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
    .opacity(this.accentColorStore.length >= 0 ? 1 : 1)
  }
}
