// SettingAboutPage — 关于页(G3-S4)
// Android 基准: SettingAboutPage.kt(181 行)— 版本/构建/开源链接
import { INK, INK2, INK3, SURFACE, LINE, BG, BODY, META, LABEL, ACCENT } from '../design/tokens.ets';
import { PageTitleBar } from '../components/PageTemplate.ets';

@Entry
@Component
struct SettingAboutPage {
  build(): void {
    Column() {
      PageTitleBar({ title: '关于' })

      Scroll() {
        Column() {
          // 应用信息
          Column() {
            Text('AmberAgent').fontSize(24).fontWeight(FontWeight.Bold).fontColor(INK)
            Text('鸿蒙原生版').fontSize(BODY).fontColor(INK3).margin({ top: 4 })
          }
          .width('100%')
          .padding({ top: 40, bottom: 24 })
          .alignItems(HorizontalAlign.Center)

          // 版本
          Row() {
            Text('版本').fontSize(BODY).fontColor(INK2).layoutWeight(1)
            Text('2.6.8 (harmony-preview)').fontSize(META).fontColor(INK3)
          }
          .width('100%')
          .padding({ left: 16, right: 16, top: 14, bottom: 14 })
          .backgroundColor(SURFACE)
          .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })

          // 构建
          Row() {
            Text('构建').fontSize(BODY).fontColor(INK2).layoutWeight(1)
            Text('HarmonyOS NEXT / ArkTS').fontSize(META).fontColor(INK3)
          }
          .width('100%')
          .padding({ left: 16, right: 16, top: 14, bottom: 14 })
          .backgroundColor(SURFACE)
          .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })

          // 开源
          Row() {
            Text('开源仓库').fontSize(BODY).fontColor(ACCENT).layoutWeight(1)
            Text('›').fontSize(BODY).fontColor(INK3)
          }
          .width('100%')
          .padding({ left: 16, right: 16, top: 14, bottom: 14 })
          .backgroundColor(SURFACE)
          .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })

          Text('Powered by multiple AI providers. 本应用为开源社区项目，不收集任何用户数据。')
            .fontSize(LABEL).fontColor(INK3)
            .margin({ top: 24, left: 16, right: 16 })
            .textAlign(TextAlign.Center)

          Column() {}.height(40)
        }
        .width('100%')
      }
      .layoutWeight(1)
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
