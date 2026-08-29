// SettingPage — 设置入口(youhua 纸面列表优化版)
//
// 结构(amber-youhua.html 四组制):通用设置 / AGENT 设置 / 模型与服务 / 数据设置 / 关于。
//   纸面列表:无卡片分组,行间内缩发丝线,圆形图标座 + 标题/描述两段行。
//   原「高级功能」组收编进实验功能页;MCP/定时任务收编进技能与扩展页。

import { router } from '@kit.ArkUI';
import { bundleManager } from '@kit.AbilityKit';
import { BG, ACCENT, INK3, LABEL, TEXT_STYLES } from '../design/tokens.ets';
import { PageTitleBar, SectionHeader, NavRow, PageSpacer } from '../components/PageTemplate.ets';
import { FONT_MONO } from '../design/fonts.ets';
import { describeChatModel, getChatKvStore } from '../di/AppContainer.ets';

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
    this.refreshAppearanceValue();
  }

  // 从外观页返回时回栈不重跑 aboutToAppear — onPageShow 重读回显值,
  // 并借 darkMode 读取触发根页按新主题令牌重建
  onPageShow(): void {
    this.refreshAppearanceValue();
    describeChatModel().then((label: string): void => {
      this.modelLabel = label;
    }).catch((): void => {});
  }

  private refreshAppearanceValue(): void {
    getChatKvStore().get(UI_COLOR_MODE_KEY).then((raw: string | null): void => {
      if (raw === 'light') this.appearanceValue = '浅色';
      else if (raw === 'dark') this.appearanceValue = '深色';
      else this.appearanceValue = '跟随系统';
    }).catch((): void => {});
  }

  private go(page: string): void {
    router.pushUrl({ url: page });
  }

  build(): void {
    Column() {
      PageTitleBar({ title: '设置' })

      Scroll() {
        Column() {
          // ── 通用设置(youhua 通用设置)──
          SectionHeader({ label: '通用设置' })
          NavRow({ label: '颜色模式', desc: this.appearanceValue, chipText: this.appearanceValue, iconName: 'palette',
            onRowClick: (): void => { this.go('pages/SettingAppearancePage'); } })
          NavRow({ label: '显示设置', desc: '管理显示设置', iconName: 'gear', isLast: true,
            onRowClick: (): void => { this.go('pages/SettingDisplayPage'); } })

          // ── AGENT 设置(youhua AGENT 设置)──
          SectionHeader({ label: 'AGENT 设置' })
          NavRow({ label: '核心记忆', desc: '跨会话保留的 AmberAgent 持久知识', iconName: 'brain',
            onRowClick: (): void => { this.go('pages/SettingAgentMemoryPageFull'); } })
          NavRow({ label: '技能与扩展', desc: 'Skill、提示词和外部工具服务', iconName: 'puzzle',
            onRowClick: (): void => { this.go('pages/SkillsPage'); } })
          NavRow({ label: '运行与显示', desc: '工具循环、操作预览和实时状态', iconName: 'eye',
            onRowClick: (): void => { this.go('pages/ChatAgentExecutionPage'); } })
          NavRow({ label: '权限与批准', desc: '系统权限和工具批准策略', iconName: 'shield',
            onRowClick: (): void => { this.go('pages/ChatAgentPermissionsPage'); } })
          NavRow({ label: 'Agent 运行环境', desc: 'Workspace、Alpine/proot 和命令运行状态', iconName: 'box',
            onRowClick: (): void => { this.go('pages/SettingSandboxPage'); } })
          NavRow({ label: '实验性功能', desc: '管理仍需真机验证的 AmberAgent 预览能力', iconName: 'spark', isLast: true,
            onRowClick: (): void => { this.go('pages/SettingExperimentalPage'); } })

          // ── 模型与服务(youhua 模型与服务)──
          SectionHeader({ label: '模型与服务' })
          NavRow({ label: '提供商', desc: '配置 AI 提供商', iconName: 'key',
            onRowClick: (): void => { this.go('pages/ChatProviderSettingsPage'); } })
          NavRow({ label: '模型与提示词', valueText: this.modelLabel, desc: '默认模型、辅助任务模型和提示词', iconName: 'cpu',
            onRowClick: (): void => { this.go('pages/ChatModelPickerPage'); } })
          NavRow({ label: '任务模型', desc: '标题、生图、视觉与压缩等辅助模型', iconName: 'spark',
            onRowClick: (): void => { this.go('pages/ChatTaskModelsPage'); } })
          NavRow({ label: '提示词', desc: '快捷消息与提示词模板', iconName: 'doc',
            onRowClick: (): void => { this.go('pages/ChatPromptsPage'); } })
          NavRow({ label: '搜索服务', desc: '设置搜索服务', iconName: 'search', isLast: true,
            onRowClick: (): void => { this.go('pages/ChatSearchSettingsPage'); } })

          // ── 数据设置(youhua 数据设置)──
          SectionHeader({ label: '数据设置' })
          NavRow({ label: '文件管理', desc: '聊天文件与附件存储', iconName: 'folder',
            onRowClick: (): void => { this.go('pages/SettingFilesPage'); } })
          NavRow({ label: '历史记录', desc: '浏览全部会话历史', iconName: 'history',
            onRowClick: (): void => { this.go('pages/HistoryPage'); } })
          NavRow({ label: '收藏', desc: '收藏的消息与内容', iconName: 'star',
            onRowClick: (): void => { this.go('pages/FavoritePage'); } })
          NavRow({ label: '深度阅读', desc: '热点榜单与深度文章', iconName: 'book', isLast: true,
            onRowClick: (): void => { this.go('pages/BoardPage'); } })

          // ── 关于 ──
          SectionHeader({ label: '关于' })
          NavRow({ label: '版本', valueText: this.versionLabel, showChevron: false })
          NavRow({ label: '关于', isLast: true, onRowClick: (): void => { this.go('pages/SettingAboutPage'); } })

          // youhua 版本行:$ 提示符 accent 色
          Row() {
            Text('$')
              .fontSize(LABEL).fontWeight(FontWeight.Medium)
              .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO)
              .fontColor(this.accentColorStore)
            Text(` amber v${this.versionLabel}`)
              .fontSize(LABEL).fontWeight(FontWeight.Medium)
              .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO)
              .fontColor(INK3)
          }
          .width('100%').justifyContent(FlexAlign.Center).margin({ top: 22 })

          PageSpacer()
        }
        .width('100%')
      }
        .align(Alignment.TopStart)
      .layoutWeight(1).backgroundColor(BG)
    }
    .width('100%').height('100%').backgroundColor(BG)
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
    .opacity((this.accentColorStore.length >= 0 && (this.darkMode || !this.darkMode)) ? 1 : 1)
  }
}
