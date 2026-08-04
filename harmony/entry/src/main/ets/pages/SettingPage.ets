// SettingPage — 设置入口(G8 对齐 SettingsScreen 设计稿)

import { router } from '@kit.ArkUI';
import {
  BG, SURFACE, SURFACE2, RAISED, INK, INK2, INK3, INK4, LINE, LINE2,
  ACCENT, ACCENT_INK, ACCENT_LIGHT, ERROR,
  TITLE, BODY, BODY2, META, MONO_SMALL, LABEL, TINY,
} from '../design/tokens.ets';
import { PageTitleBar, SectionHeader, NavRow, PageSpacer } from '../components/PageTemplate.ets';

@Entry
@Component
struct SettingPage {
  @State themeMode: string = 'light';
  @State accentColor: string = '#B8623A';

  private go(page: string): void {
    router.pushUrl({ url: page });
  }

  build(): void {
    Column() {
      PageTitleBar({ title: '设置' })

      Scroll() {
        Column() {
          // ── 账户卡 ──
          Row() {
            Text('光')
              .fontSize(17)
              .fontWeight(FontWeight.Bold)
              .fontColor(BG)
              .fontFamily('monospace')
              .width(46).height(46).borderRadius(14)
              .backgroundColor(INK)
              .textAlign(TextAlign.Center)
            Column() {
              Text('光').fontSize(BODY).fontWeight(FontWeight.Bold).fontColor(INK)
              Text('Amber Plus · 续费于 6/30')
                .fontSize(MONO_SMALL).fontColor(INK3).fontFamily('monospace').margin({ top: 3 })
            }
            .alignItems(HorizontalAlign.Start).margin({ left: 14 }).layoutWeight(1)
            Text('›').fontSize(BODY).fontColor(INK4)
          }
          .width('100%').padding(16)
          .backgroundColor(SURFACE)
          .border({ width: 1, color: LINE }).borderRadius(14)
          .margin({ top: 8 })
          .onClick((): void => { this.go('pages/ChatAssistantsPage'); })

          // ── APPEARANCE ──
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
            .onClick((): void => { this.themeMode = 'light'; })

            Row() {
              Text('深色')
                .fontSize(BODY2)
                .fontWeight(this.themeMode === 'dark' ? FontWeight.Bold : FontWeight.Normal)
                .fontColor(this.themeMode === 'dark' ? INK : INK3)
            }
            .layoutWeight(1).justifyContent(FlexAlign.Center)
            .padding({ top: 9, bottom: 9 }).borderRadius(10)
            .backgroundColor(this.themeMode === 'dark' ? RAISED : 'transparent')
            .onClick((): void => { this.themeMode = 'dark'; })
          }
          .width('100%').padding(4)
          .backgroundColor(SURFACE2).borderRadius(13)
          .margin({ top: 4 })

          SectionHeader({ label: 'ACCENT' })

          Row() {
            ForEach(['#B8623A', '#5E9C6E', '#4F86D6', '#9277C4', '#C2607A'], (color: string, idx: number): void => {
              Text(this.accentColor === color ? '✓' : '')
                .fontSize(18).fontColor(ACCENT_INK)
                .width(38).height(38).borderRadius(11)
                .backgroundColor(color)
                .border({ width: this.accentColor === color ? 2 : 0, color: INK })
                .textAlign(TextAlign.Center)
                .margin({ right: idx < 4 ? 12 : 0 })
                .onClick((): void => { this.accentColor = color; })
            }, (color: string, idx: number): string => `swatch-${idx}`)
          }
          .width('100%')
          .padding({ left: 6, bottom: 4 })

          // ── AGENT ──
          SectionHeader({ label: 'AGENT' })
          Column() {
            NavRow({ label: '默认模型', valueText: 'Claude Sonnet 4.5', onRowClick: (): void => { this.go('pages/ChatModelPickerPage'); } })
            NavRow({ label: '核心记忆', valueText: '14 条', onRowClick: (): void => { this.go('pages/SettingAgentMemoryPageFull'); } })
            NavRow({ label: '技能', valueText: '6 个已启用', onRowClick: (): void => { this.go('pages/SkillsPage'); } })
          }
          .width('100%').backgroundColor(SURFACE)
          .border({ width: 1, color: LINE }).borderRadius(14).clip(true)

          // ── CONNECTIVITY ──
          SectionHeader({ label: 'CONNECTIVITY' })
          Column() {
            NavRow({ label: '联网搜索', valueText: '自动', onRowClick: (): void => { this.go('pages/SettingSearchListCards'); } })
            NavRow({ label: '服务商与密钥', valueText: '3 个', onRowClick: (): void => { this.go('pages/SettingProviderListPage'); } })
            NavRow({ label: '隐私与数据', onRowClick: (): void => { this.go('pages/SettingAgentPermissionsPageFull'); } })
          }
          .width('100%').backgroundColor(SURFACE)
          .border({ width: 1, color: LINE }).borderRadius(14).clip(true)

          // ── ABOUT ──
          SectionHeader({ label: 'ABOUT' })
          Column() {
            NavRow({ label: '版本', valueText: '2.4.0 (128)' })
            NavRow({ label: '通知' })
          }
          .width('100%').backgroundColor(SURFACE)
          .border({ width: 1, color: LINE }).borderRadius(14).clip(true)

          Column() {}.height(16)

          // ── 退出 ──
          Column() {
            NavRow({ label: '退出登录', danger: true })
          }
          .width('100%').backgroundColor(SURFACE)
          .border({ width: 1, color: LINE }).borderRadius(14).clip(true)

          Text('amber · 光的设备')
            .fontSize(LABEL).fontColor(INK4).fontFamily('monospace')
            .textAlign(TextAlign.Center).width('100%').margin({ top: 22 })

          // ── 更多入口 ──
          SectionHeader({ label: '更多' })
          Column() {
            NavRow({ label: '显示设置', onRowClick: (): void => { this.go('pages/SettingDisplayPage'); } })
            NavRow({ label: '扩展管理', onRowClick: (): void => { this.go('pages/SettingAgentExtensionsPageFull'); } })
            NavRow({ label: '执行参数', onRowClick: (): void => { this.go('pages/SettingAgentExecutionPageFull'); } })
            NavRow({ label: 'MCP 服务器', onRowClick: (): void => { this.go('pages/SettingMcpPageFull'); } })
            NavRow({ label: '实验功能', onRowClick: (): void => { this.go('pages/SettingExperimentalPage'); } })
            NavRow({ label: '模型管理', onRowClick: (): void => { this.go('pages/SettingModelPage'); } })
            NavRow({ label: '任务模型', onRowClick: (): void => { this.go('pages/ChatTaskModelsPage'); } })
            NavRow({ label: 'TTS', onRowClick: (): void => { this.go('pages/SettingTTSProviderPage'); } })
            NavRow({ label: '沙盒设置', onRowClick: (): void => { this.go('pages/SettingSandboxPage'); } })
            NavRow({ label: '文件管理', onRowClick: (): void => { this.go('pages/SettingFilesPage'); } })
            NavRow({ label: '定时任务', onRowClick: (): void => { this.go('pages/SettingCronTasksPage'); } })
            NavRow({ label: '历史记录', onRowClick: (): void => { this.go('pages/HistoryPage'); } })
            NavRow({ label: '今日看板', onRowClick: (): void => { this.go('pages/BoardPage'); } })
            NavRow({ label: '收藏', onRowClick: (): void => { this.go('pages/FavoritePage'); } })
            NavRow({ label: '关于', onRowClick: (): void => { this.go('pages/SettingAboutPage'); } })
          }
          .width('100%').backgroundColor(SURFACE)
          .border({ width: 1, color: LINE }).borderRadius(14).clip(true)

          PageSpacer()
        }
        .width('100%').padding({ left: 18, right: 18 })
      }
      .layoutWeight(1).backgroundColor(BG)
    }
    .width('100%').height('100%').backgroundColor(BG)
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }
}
