// SettingCronTasksPage — 定时任务管理(G3-S4)
// Android 基准: SettingAgentExtensionsPage.kt:319-402 — cron 任务列表
// 后端: agent_cron.ts(域逻辑已移植);entry AgentCronStore.ets(持久化)
import { router } from '@kit.ArkUI';
import { INK, INK2, INK3, SURFACE, LINE, BG, BODY, META, LABEL, TITLE, ACCENT } from '../design/tokens.ets';

@Entry
@Component
struct SettingCronTasksPage {
  build(): void {
    Column() {
      Row() {
        Text('←').fontSize(18).fontColor(INK3).onClick((): void => { router.back(); })
        Text('定时任务').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
      }
      .width('100%').height(56).padding({ left: 24, right: 24 }).backgroundColor(BG)

      Scroll() {
        Column() {
          Column() {
            Text('Cron 定时任务').fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK2)
            Text('Agent 可以创建定时执行的任务（如每日摘要、定期检查等）。任务在应用启动时自动重新调度。')
              .fontSize(LABEL).fontColor(INK3).margin({ top: 6 })
          }
          .width('100%').padding({ left: 16, right: 16, top: 20, bottom: 16 })

          // 空态(cron 任务列表需 Worker 执行器落地后才能运行 = 设备验证项)
          Column() {
            Text('⏰').fontSize(32).margin({ bottom: 8 })
            Text('暂无定时任务').fontSize(BODY).fontColor(INK3)
            Text('在对话中让 Agent 创建 cron 任务后，会显示在这里')
              .fontSize(LABEL).fontColor(INK3).margin({ top: 4 })
              .textAlign(TextAlign.Center)
          }
          .width('100%')
          .padding({ top: 48 })
          .alignItems(HorizontalAlign.Center)

          Column() {}.height(40)
        }
        .width('100%')
      }
      .layoutWeight(1)
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
