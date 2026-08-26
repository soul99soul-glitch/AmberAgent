// SettingAboutPage — 关于页(真实版本值,卡容器对齐设置体系)
// Android 基准: SettingAboutPage.kt — 版本/构建/开源说明。
//   版本号经 bundleManager 取真值(不写死);开源仓库无外链目标 → 纯说明行(无箭头)。
import { bundleManager } from '@kit.AbilityKit';
import { INK, INK2, INK3, SURFACE, LINE, BG, BODY, META, LABEL } from '../design/tokens.ets';
import { PageTitleBar } from '../components/PageTemplate.ets';

@Entry
@Component
struct SettingAboutPage {
  @State versionLabel: string = '';

  aboutToAppear(): void {
    try {
      const info = bundleManager.getBundleInfoForSelfSync(bundleManager.BundleFlag.GET_BUNDLE_INFO_DEFAULT);
      this.versionLabel = `${info.versionName} (${info.versionCode})`;
    } catch (e) {
      this.versionLabel = '';
    }
  }

  build(): void {
    Column() {
      PageTitleBar({ title: '关于' })

      Scroll() {
        Column() {
          // 应用信息
          Column() {
            Text('AmberAgent').fontSize(22).fontWeight(FontWeight.Bold).fontColor(INK)
            Text('鸿蒙原生版').fontSize(BODY).fontColor(INK3).margin({ top: 4 })
          }
          .width('100%')
          .padding({ top: 40, bottom: 24 })
          .alignItems(HorizontalAlign.Center)

          Column() {
            Row() {
              Text('版本').fontSize(BODY).fontColor(INK2).layoutWeight(1)
              Text(this.versionLabel).fontSize(META).fontColor(INK3).fontFamily('JetBrainsMono')
                .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis }).textAlign(TextAlign.End)
            }
            .width('100%')
            .padding({ left: 16, right: 16, top: 14, bottom: 14 })

            Row() {
              Text('构建').fontSize(BODY).fontColor(INK2).layoutWeight(1)
              Text('HarmonyOS NEXT / ArkTS').fontSize(META).fontColor(INK3).fontFamily('JetBrainsMono')
                .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis }).textAlign(TextAlign.End)
            }
            .width('100%')
            .padding({ left: 16, right: 16, top: 14, bottom: 14 })
            .border({ width: { top: 0.5 }, color: { top: LINE } })

            Row() {
              Text('开源社区项目 · 不收集任何用户数据').fontSize(LABEL).fontColor(INK3)
            }
            .width('100%')
            .padding({ left: 16, right: 16, top: 14, bottom: 14 })
            .border({ width: { top: 0.5 }, color: { top: LINE } })
          }
          .backgroundColor(SURFACE)
          .border({ width: 1, color: LINE }).borderRadius(14).clip(true)
          .margin({ left: 18, right: 18 })

          Text('Powered by multiple AI providers.')
            .fontSize(LABEL).fontColor(INK3).fontFamily('JetBrainsMono')
            .margin({ top: 24 })
            .textAlign(TextAlign.Center).width('100%')

          Column() {}.height(40)
        }
        .width('100%')
      }
        .align(Alignment.TopStart)
      .layoutWeight(1)
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
