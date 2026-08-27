// SettingPage — 设置入口(G8 对齐 SettingsScreen 设计稿)
//
// 结构梳理(对齐 Android 层次):外观 / 模型与服务 / Agent Runtime / 数据 / 关于。
//   无 profile 卡、无订阅占位、无死占位行(退出登录/通知)。

import { router } from '@kit.ArkUI';
import { bundleManager } from '@kit.AbilityKit';
import { BG, SURFACE, LINE, BODY2, LABEL, ACCENT, INK3 } from '../design/tokens.ets';
import { PageTitleBar, SectionHeader, NavRow, PageSpacer } from '../components/PageTemplate.ets';
import { describeChatModel, getChatKvStore } from '../di/AppContainer.ets';
import type { DisplaySetting } from '@amber/chat-domain';
import { loadDisplaySetting, saveDisplaySetting } from '@amber/chat-domain';

// 主题选择持久化键(外观页写入;根页只读回显 value)
const UI_COLOR_MODE_KEY: string = 'ui_color_mode';

@Entry
@Component
struct SettingPage {
  // theme swap triggers full-page rebuild (build re-reads mutable tokens)
  @StorageProp('darkMode') darkMode: boolean = false;
  @StorageProp('systemDarkMode') systemDarkMode: boolean = false;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @State appearanceValue: string = '跟随系统';
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
    // 外观行 value 只读回显(写入在 AppearancePage)
    getChatKvStore().get(UI_COLOR_MODE_KEY).then((raw: string | null): void => {
      if (raw === 'light') this.appearanceValue = '浅色';
      else if (raw === 'dark') this.appearanceValue = '深色';
      else this.appearanceValue = '跟随系统';
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
          // ── 通用设置(iOS generalEntries)──
          SectionHeader({ label: '通用设置' })
          Column() {
            NavRow({ label: '外观', valueText: this.appearanceValue, iconName: 'palette',
              onRowClick: (): void => { this.go('pages/SettingAppearancePage'); } })
            NavRow({ label: '显示与字体', iconName: 'eye', isLast: true,
              onRowClick: (): void => { this.go('pages/SettingDisplayPage'); } })
          }
          .width('100%').backgroundColor(SURFACE)
          .border({ width: 1, color: LINE }).borderRadius(18).clip(true)

          // ── Agent 设置(iOS agentEntries)──
          SectionHeader({ label: 'AGENT 设置' })
          Column() {
            NavRow({ label: '灵魂与记忆', iconName: 'brain',
              onRowClick: (): void => { this.go('pages/SettingAgentMemoryPageFull'); } })
            NavRow({ label: '运行环境', iconName: 'clock',
              onRowClick: (): void => { this.go('pages/ChatAgentExecutionPage'); } })
            NavRow({ label: '技能', iconName: 'puzzle',
              onRowClick: (): void => { this.go('pages/SkillsPage'); } })
            NavRow({ label: '权限与批准', iconName: 'shield',
              onRowClick: (): void => { this.go('pages/ChatAgentPermissionsPage'); } })
            NavRow({ label: 'MCP 服务器', iconName: 'plug', isLast: true,
              onRowClick: (): void => { this.go('pages/ChatMcpSettingsPage'); } })
          }
          .width('100%').backgroundColor(SURFACE)
          .border({ width: 1, color: LINE }).borderRadius(18).clip(true)

          // ── 模型与服务(iOS modelServiceEntries)──
          SectionHeader({ label: '模型与服务' })
          Column() {
            NavRow({ label: '服务商', iconName: 'key',
              onRowClick: (): void => { this.go('pages/ChatProviderSettingsPage'); } })
            NavRow({ label: '模型与提示词', valueText: this.modelLabel, iconName: 'cpu',
              onRowClick: (): void => { this.go('pages/ChatModelPickerPage'); } })
            NavRow({ label: '任务模型', iconName: 'spark',
              onRowClick: (): void => { this.go('pages/ChatTaskModelsPage'); } })
            NavRow({ label: '提示词', iconName: 'doc',
              onRowClick: (): void => { this.go('pages/ChatPromptsPage'); } })
            NavRow({ label: '搜索服务', iconName: 'search',
              onRowClick: (): void => { this.go('pages/ChatSearchSettingsPage'); } })
            NavRow({ label: '语音服务', iconName: 'speaker', isLast: true,
              onRowClick: (): void => { this.go('pages/SettingTTSProviderPage'); } })
          }
          .width('100%').backgroundColor(SURFACE)
          .border({ width: 1, color: LINE }).borderRadius(18).clip(true)

          // ── 高级功能(iOS advancedFeatureEntries,自实验性功能上提)──
          SectionHeader({ label: '高级功能' })
          Column() {
            NavRow({ label: 'WebMount', valueText: 'Alpha', iconName: 'globe',
              onRowClick: (): void => { this.go('pages/SettingExperimentalWebMountPage'); } })
            NavRow({ label: '子代理', valueText: 'Beta', iconName: 'person',
              onRowClick: (): void => { this.go('pages/SettingSubAgentPage'); } })
            NavRow({ label: '模型议会', valueText: 'Beta', iconName: 'spark',
              onRowClick: (): void => { this.go('pages/SettingModelCouncilPage'); } })
            NavRow({ label: '小应用', valueText: 'Beta', iconName: 'box',
              onRowClick: (): void => { this.go('pages/MiniAppSettingsPage'); } })
            NavRow({ label: '小说创作', iconName: 'doc',
              onRowClick: (): void => { this.go('pages/NovelProjectsPage'); } })
            NavRow({ label: '深度阅读', iconName: 'book', isLast: true,
              onRowClick: (): void => { this.go('pages/SettingDeepReadPage'); } })
          }
          .width('100%').backgroundColor(SURFACE)
          .border({ width: 1, color: LINE }).borderRadius(18).clip(true)

          // ── 数据设置(iOS dataEntries)──
          SectionHeader({ label: '数据设置' })
          Column() {
            NavRow({ label: '文件管理', iconName: 'folder',
              onRowClick: (): void => { this.go('pages/SettingFilesPage'); } })
            NavRow({ label: '历史记录', iconName: 'history',
              onRowClick: (): void => { this.go('pages/HistoryPage'); } })
            NavRow({ label: '收藏', iconName: 'star',
              onRowClick: (): void => { this.go('pages/FavoritePage'); } })
            NavRow({ label: '定时任务', iconName: 'clock', isLast: true,
              onRowClick: (): void => { this.go('pages/SettingCronTasksPage'); } })
          }
          .width('100%').backgroundColor(SURFACE)
          .border({ width: 1, color: LINE }).borderRadius(18).clip(true)

          // ── 关于 ──
          SectionHeader({ label: '关于' })
          Column() {
            NavRow({ label: '版本', valueText: this.versionLabel, showChevron: false })
            NavRow({ label: '关于', isLast: true, onRowClick: (): void => { this.go('pages/SettingAboutPage'); } })
          }
          .width('100%').backgroundColor(SURFACE)
          .border({ width: 1, color: LINE }).borderRadius(18).clip(true)

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
        .align(Alignment.TopStart)
      .layoutWeight(1).backgroundColor(BG)
    }
    .width('100%').height('100%').backgroundColor(BG)
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
    .opacity(this.accentColorStore.length >= 0 ? 1 : 1)
  }
}
