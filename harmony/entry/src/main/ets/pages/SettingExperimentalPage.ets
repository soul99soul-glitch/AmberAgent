// SettingExperimentalPage — 实验性功能入口页(G7 重构)
// 对齐 Android: SettingExperimental → 子代理/Council/WebMount/TodayBoard
// (Office Pro 随语音/办公集成下线移除,2026-08-29)
import { router } from '@kit.ArkUI';
import { BG } from '../design/tokens.ets';
import { PageTitleBar, SectionHeader, NavRow, PageSpacer } from '../components/PageTemplate.ets';

@Entry
@Component
struct SettingExperimentalPage {
  private go(page: string): void {
    router.pushUrl({ url: page });
  }

  build(): void {
    Column() {
      PageTitleBar({ title: '实验性功能' })

      Scroll() {
        Column() {
          SectionHeader({ label: '预览功能' })
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

          SectionHeader({ label: '内容' })
          NavRow({ label: 'DeepRead 模板', iconName: 'book', isLast: true,
            onRowClick: (): void => { this.go('pages/DeepReadTemplateSettingsPage'); } })

          SectionHeader({ label: '其他' })
          NavRow({ label: '系统权限', iconName: 'shield', isLast: true,
            onRowClick: (): void => { this.go('pages/SettingSystemAccessPage'); } })

          PageSpacer()
        }
        .width('100%')
      }
      .layoutWeight(1)
      .align(Alignment.TopStart)
      .backgroundColor(BG)
    }
    .width('100%').height('100%').backgroundColor(BG)
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }
}
