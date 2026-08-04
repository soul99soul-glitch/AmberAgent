// SettingPage — 设置入口索引页(M3.1)
// Android 基准: feature/ui/pages/setting/SettingPage.kt(363 行)— 4 分组索引
//   鸿蒙:收口 ChatListPage 散落 10 链接为统一索引页;路由 + 既有页面复用。
//
// 分组(对齐 Android):
//   general:助手
//   agentRuntime:记忆 / 权限 / 运行 / MCP / 子代理 / Council
//   modelServices:Provider / 任务模型 / 搜索
//   data:删除全部(DANGER 色)
import { router } from '@kit.ArkUI';
import {
  BG, SURFACE, INK, INK2, INK3, INK4, LINE, ACCENT, ERROR, BODY, META,
} from '../design/tokens.ets';

interface SettingEntry {
  label: string;
  page: string;
  danger: boolean;
}

@Entry
@Component
struct SettingPage {
  private go(page: string): void {
    router.pushUrl({ url: page });
  }

  @Builder
  SectionHeader(title: string): void {
    Text(title)
      .fontSize(META).fontColor(INK3)
      .width('100%')
      .padding({ left: 16, top: 20, bottom: 6 })
  }

  @Builder
  EntryRow(entry: SettingEntry): void {
    Row() {
      Text(entry.label).fontSize(BODY).fontColor(entry.danger ? ERROR : INK).layoutWeight(1)
      Text('›').fontSize(BODY).fontColor(INK4)
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 14, bottom: 14 })
    .backgroundColor(SURFACE)
    .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
    .onClick((): void => { this.go(entry.page); })
  }

  @Builder
  Section(entries: SettingEntry[]): void {
    Column() {
      ForEach(entries, (e: SettingEntry): void => {
        this.EntryRow(e)
      }, (e: SettingEntry): string => e.page)
    }
    .width('100%')
    .backgroundColor(SURFACE)
    .border({ width: 1, color: LINE })
    .borderRadius(0)
  }

  build(): void {
    Column() {
      // 顶栏
      Row() {
        Text('←').fontSize(18).fontColor(INK3)
          .onClick((): void => { router.back(); })
        Text('设置').fontSize(18).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
      }
      .width('100%').height(56).padding({ left: 24, right: 24 })
      .backgroundColor(BG)

      Scroll() {
        Column() {
          // general
          this.SectionHeader('通用')
          this.Section([
            { label: '助手', page: 'pages/ChatAssistantsPage', danger: false },
            { label: '助手列表', page: 'pages/AssistantListPage', danger: false },
            { label: '显示设置', page: 'pages/SettingDisplayPage', danger: false },
            { label: '主题', page: 'pages/SettingDisplayPage', danger: false },
            { label: '关于', page: 'pages/SettingAboutPage', danger: false },
          ])

          // agentRuntime
          this.SectionHeader('Agent 运行')
          this.Section([
            { label: '记忆', page: 'pages/MemoryPage', danger: false },
            { label: '记忆设置', page: 'pages/SettingAgentMemoryPageFull', danger: false },
            { label: '权限策略', page: 'pages/ChatAgentPermissionsPage', danger: false },
            { label: '权限设置', page: 'pages/SettingAgentPermissionsPageFull', danger: false },
            { label: '执行参数', page: 'pages/ChatAgentExecutionPage', danger: false },
            { label: '执行设置', page: 'pages/SettingAgentExecutionPageFull', danger: false },
            { label: '扩展管理', page: 'pages/SettingAgentExtensionsPageFull', danger: false },
            { label: 'MCP', page: 'pages/ChatMcpSettingsPage', danger: false },
            { label: 'MCP 设置', page: 'pages/SettingMcpPageFull', danger: false },
            { label: '子代理(实验)', page: 'pages/SettingSubAgentPage', danger: false },
            { label: '子代理设置', page: 'pages/SettingExperimentalSubAgentPageFull', danger: false },
            { label: 'Council(实验)', page: 'pages/SettingModelCouncilPage', danger: false },
            { label: 'Council 设置', page: 'pages/SettingExperimentalModelCouncilPageFull', danger: false },
          ])

          // modelServices
          this.SectionHeader('模型与服务')
          this.Section([
            { label: 'Provider', page: 'pages/ChatProviderSettingsPage', danger: false },
            { label: 'Provider 列表', page: 'pages/SettingProviderListPage', danger: false },
            { label: 'Provider 详情', page: 'pages/SettingProviderDetailPageFull', danger: false },
            { label: '模型管理', page: 'pages/SettingModelPage', danger: false },
            { label: '任务模型', page: 'pages/ChatTaskModelsPage', danger: false },
            { label: '搜索', page: 'pages/ChatSearchSettingsPage', danger: false },
            { label: '搜索源列表', page: 'pages/SettingSearchListCards', danger: false },
            { label: 'TTS 设置', page: 'pages/SettingTTSPage', danger: false },
            { label: 'TTS Provider', page: 'pages/SettingTTSProviderPage', danger: false },
          ])

          // experimental
          this.SectionHeader('实验功能')
          this.Section([
            { label: '实验功能入口', page: 'pages/SettingExperimentalPage', danger: false },
            { label: 'Web 挂载', page: 'pages/SettingExperimentalWebMountPage', danger: false },
            { label: 'Office Pro', page: 'pages/SettingExperimentalOfficeProPage', danger: false },
            { label: '字体设置', page: 'pages/SettingSlidesFontPage', danger: false },
          ])

          // deepread
          this.SectionHeader('深度阅读')
          this.Section([
            { label: '模板设置', page: 'pages/DeepReadTemplateSettingsPage', danger: false },
            { label: '模板工作台', page: 'pages/DeepReadTemplateWorkbenchPage', danger: false },
            { label: '热榜源设置', page: 'pages/HotListSourceSettingsPage', danger: false },
          ])

          // extensions
          this.SectionHeader('扩展')
          this.Section([
            { label: '技能', page: 'pages/SkillsPage', danger: false },
            { label: '提示词模板', page: 'pages/PromptTemplatePage', danger: false },
            { label: '快捷消息', page: 'pages/QuickMessagesPage', danger: false },
            { label: '系统权限', page: 'pages/SettingSystemAccessPage', danger: false },
          ])

          // data
          this.SectionHeader('数据')
          this.Section([
            { label: '沙盒设置', page: 'pages/SettingSandboxPage', danger: false },
            { label: '文件管理', page: 'pages/SettingFilesPage', danger: false },
            { label: '定时任务', page: 'pages/SettingCronTasksPage', danger: false },
            { label: '删除全部会话', page: 'pages/HistoryPage', danger: true },
          ])

          Column() {}.height(40)
        }
        .width('100%')
      }
      .layoutWeight(1)
      .backgroundColor(BG)
    }
    .width('100%').height('100%').backgroundColor(BG)
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }
}
