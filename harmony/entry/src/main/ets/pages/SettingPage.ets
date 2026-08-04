// SettingPage — 设置入口索引页(G7 重构)
//
// 对齐 Android SettingPage.kt 4 分组:
//   1. 通用: 助手 / 显示设置 / 关于
//   2. Agent 运行: 记忆 / 扩展 / 执行 / 权限 / MCP / 实验功能
//   3. 模型与服务: Provider / 模型 / 搜索 / TTS
//   4. 数据: 沙盒 / 文件 / 定时任务 / 删除全部
//
// 删除重复入口: 不再有 "权限策略"+"权限设置" 并列
// 实验功能归入 Agent 运行分组下,不与常规设置平铺

import { router } from '@kit.ArkUI';
import { BG, SURFACE, INK, INK2, INK3, INK4, LINE, ACCENT, ERROR, BODY, META } from '../design/tokens.ets';
import { PageTitleBar, SectionHeader, NavRow, PageSpacer } from '../components/PageTemplate.ets';

@Entry
@Component
struct SettingPage {
  private go(page: string): void {
    router.pushUrl({ url: page });
  }

  build(): void {
    Column() {
      PageTitleBar({ title: '设置' })

      Scroll() {
        Column() {
          // ── 1. 通用 ──
          SectionHeader({ label: '通用' })
          NavRow({ label: '助手', onRowClick: (): void => { this.go('pages/ChatAssistantsPage'); } })
          NavRow({ label: '显示设置', onRowClick: (): void => { this.go('pages/SettingDisplayPage'); } })
          NavRow({ label: '关于', onRowClick: (): void => { this.go('pages/SettingAboutPage'); } })

          // ── 2. Agent 运行 ──
          SectionHeader({ label: 'Agent 运行' })
          NavRow({ label: '记忆', onRowClick: (): void => { this.go('pages/SettingAgentMemoryPageFull'); } })
          NavRow({ label: '扩展', onRowClick: (): void => { this.go('pages/SettingAgentExtensionsPageFull'); } })
          NavRow({ label: '执行参数', onRowClick: (): void => { this.go('pages/SettingAgentExecutionPageFull'); } })
          NavRow({ label: '权限策略', onRowClick: (): void => { this.go('pages/SettingAgentPermissionsPageFull'); } })
          NavRow({ label: 'MCP 服务器', onRowClick: (): void => { this.go('pages/SettingMcpPageFull'); } })
          NavRow({ label: '实验功能', onRowClick: (): void => { this.go('pages/SettingExperimentalPage'); } })

          // ── 3. 模型与服务 ──
          SectionHeader({ label: '模型与服务' })
          NavRow({ label: 'Provider', onRowClick: (): void => { this.go('pages/SettingProviderListPage'); } })
          NavRow({ label: '模型管理', onRowClick: (): void => { this.go('pages/SettingModelPage'); } })
          NavRow({ label: '任务模型', onRowClick: (): void => { this.go('pages/ChatTaskModelsPage'); } })
          NavRow({ label: '搜索', onRowClick: (): void => { this.go('pages/SettingSearchListCards'); } })
          NavRow({ label: 'TTS', onRowClick: (): void => { this.go('pages/SettingTTSProviderPage'); } })

          // ── 4. 数据 ──
          SectionHeader({ label: '数据' })
          NavRow({ label: '沙盒设置', onRowClick: (): void => { this.go('pages/SettingSandboxPage'); } })
          NavRow({ label: '文件管理', onRowClick: (): void => { this.go('pages/SettingFilesPage'); } })
          NavRow({ label: '定时任务', onRowClick: (): void => { this.go('pages/SettingCronTasksPage'); } })
          NavRow({ label: '删除全部会话', danger: true, onRowClick: (): void => { this.go('pages/HistoryPage'); } })

          PageSpacer()
        }
        .width('100%')
      }
      .layoutWeight(1)
      .backgroundColor(BG)
    }
    .width('100%')
    .height('100%')
    .backgroundColor(BG)
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }
}
