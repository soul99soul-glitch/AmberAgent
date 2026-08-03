// SettingFilesPage — 文件管理设置(G3-S4)
// Android 基准: SettingFilesPage.kt(274 行)— 工作区文件管理
import { router } from '@kit.ArkUI';
import { INK, INK2, INK3, SURFACE, LINE, BG, BODY, META, LABEL, TITLE, ACCENT } from '../design/tokens.ets';

@Entry
@Component
struct SettingFilesPage {
  build(): void {
    Column() {
      Row() {
        Text('←').fontSize(18).fontColor(INK3).onClick((): void => { router.back(); })
        Text('文件管理').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
      }
      .width('100%').height(56).padding({ left: 24, right: 24 }).backgroundColor(BG)

      Scroll() {
        Column() {
          Column() {
            Text('工作区文件').fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK2)
            Text('管理 Agent 创建的文件、图片和文档附件。会话删除时关联文件会自动清理。')
              .fontSize(LABEL).fontColor(INK3).margin({ top: 6 })
          }
          .width('100%').padding({ left: 16, right: 16, top: 20, bottom: 16 })

          Row() {
            Text('存储位置').fontSize(BODY).fontColor(INK2).layoutWeight(1)
            Text('应用文件目录').fontSize(META).fontColor(INK3)
          }
          .width('100%').padding({ left: 16, right: 16, top: 14, bottom: 14 })
          .backgroundColor(SURFACE).border({ width: { bottom: 0.5 }, color: { bottom: LINE } })

          Row() {
            Text('图片压缩').fontSize(BODY).fontColor(INK2).layoutWeight(1)
            Text('已启用').fontSize(META).fontColor(ACCENT)
          }
          .width('100%').padding({ left: 16, right: 16, top: 14, bottom: 14 })
          .backgroundColor(SURFACE).border({ width: { bottom: 0.5 }, color: { bottom: LINE } })

          Row() {
            Text('清理孤立文件').fontSize(BODY).fontColor(ACCENT).layoutWeight(1)
            Text('›').fontSize(BODY).fontColor(INK3)
          }
          .width('100%').padding({ left: 16, right: 16, top: 14, bottom: 14 })
          .backgroundColor(SURFACE).border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
          .onClick((): void => {})

          Column() {}.height(40)
        }
        .width('100%')
      }
      .layoutWeight(1)
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
