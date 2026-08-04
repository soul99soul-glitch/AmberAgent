// SettingSandboxPage — 沙盒执行设置(G3-S4)
// Android 基准: SettingSandboxPage.kt(384 行)— 沙盒/代码执行设置
// MVP: 沙盒活动时间线状态显示 + 工作区根目录
import { router } from '@kit.ArkUI';
import { INK, INK2, INK3, SURFACE, LINE, BG, BODY, META, LABEL, TITLE, ACCENT } from '../design/tokens.ets';

@Entry
@Component
struct SettingSandboxPage {
  build(): void {
    Column() {
      Row() {
        Text('←').fontSize(24).fontColor(INK).onClick((): void => { router.back(); })
        Text('沙盒设置').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
      }
      .width('100%').padding({ left: 14, right: 14, top: 2, bottom: 11 }).border({ width: { bottom: 1 }, color: { bottom: LINE } }).backgroundColor(BG)

      Scroll() {
        Column() {
          Column() {
            Text('沙盒执行').fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK2)
            Text('Agent 可以在隔离的沙盒环境中执行代码和操作。沙盒活动会记录在时间线中，可在聊天页查看。')
              .fontSize(LABEL).fontColor(INK3).margin({ top: 6 })
          }
          .width('100%')
          .padding({ left: 16, right: 16, top: 20, bottom: 16 })

          Row() {
            Text('沙盒状态').fontSize(BODY).fontColor(INK2).layoutWeight(1)
            Text('就绪').fontSize(META).fontColor(ACCENT)
          }
          .width('100%')
          .padding({ left: 16, right: 16, top: 14, bottom: 14 })
          .backgroundColor(SURFACE)
          .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })

          Row() {
            Text('工作区根目录').fontSize(BODY).fontColor(INK2).layoutWeight(1)
            Text('应用沙箱目录').fontSize(META).fontColor(INK3)
          }
          .width('100%')
          .padding({ left: 16, right: 16, top: 14, bottom: 14 })
          .backgroundColor(SURFACE)
          .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })

          Column() {}.height(40)
        }
        .width('100%')
      }
      .layoutWeight(1)
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
