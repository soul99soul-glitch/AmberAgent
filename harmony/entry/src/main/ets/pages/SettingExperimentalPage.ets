// SettingExperimentalPage — 实验性功能入口页(G7 重构)
// 对齐 Android: SettingExperimental → 子代理/Council/WebMount/OfficePro/TodayBoard
import { router } from '@kit.ArkUI';
import { BG, GUTTER } from '../design/tokens.ets';
import { PageTitleBar, SectionHeader, NavRow, PageSpacer } from '../components/PageTemplate.ets';

@Entry
@Component
struct SettingExperimentalPage {
  private go(page: string): void {
    router.pushUrl({ url: page });
  }

  build(): void {
    Column() {
      PageTitleBar({ title: '实验功能' })

      Scroll() {
        Column() {
          SectionHeader({ label: '智能体' })
          NavRow({ label: '子代理', valueText: 'Beta', onRowClick: (): void => { this.go('pages/SettingExperimentalSubAgentPageFull'); } })
          NavRow({ label: '模型议会', valueText: 'Beta', isLast: true, onRowClick: (): void => { this.go('pages/SettingExperimentalModelCouncilPageFull'); } })

          SectionHeader({ label: '内容' })
          NavRow({ label: '网页挂载', valueText: 'Alpha', onRowClick: (): void => { this.go('pages/SettingExperimentalWebMountPage'); } })
          NavRow({ label: 'Office Pro', valueText: 'Alpha', onRowClick: (): void => { this.go('pages/SettingExperimentalOfficeProPage'); } })
          NavRow({ label: 'DeepRead 模板', isLast: true, onRowClick: (): void => { this.go('pages/DeepReadTemplateSettingsPage'); } })

          SectionHeader({ label: '自动化' })
          NavRow({ label: '定时任务', valueText: 'Beta', isLast: true, onRowClick: (): void => { this.go('pages/SettingCronTasksPage'); } })

          SectionHeader({ label: '其他' })
          NavRow({ label: '小应用', valueText: 'Beta', onRowClick: (): void => { this.go('pages/MiniAppListPage'); } })
          NavRow({ label: '字体设置', onRowClick: (): void => { this.go('pages/SettingSlidesFontPage'); } })
          NavRow({ label: '系统权限', isLast: true, onRowClick: (): void => { this.go('pages/SettingSystemAccessPage'); } })

          PageSpacer()
        }
        .width('100%')
        .padding({ left: GUTTER, right: GUTTER })
      }
      .layoutWeight(1)
      .align(Alignment.Top)
      .backgroundColor(BG)
    }
    .width('100%').height('100%').backgroundColor(BG)
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }
}
