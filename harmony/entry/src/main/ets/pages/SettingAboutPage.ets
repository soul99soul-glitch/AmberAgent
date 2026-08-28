// SettingAboutPage — 关于页(真实版本值,卡容器对齐设置体系)
// Android 基准: SettingAboutPage.kt — 版本/构建/开源说明。
//   版本号经 bundleManager 取真值(不写死);开源仓库无外链目标 → 纯说明行(无箭头)。
import { bundleManager } from '@kit.AbilityKit';
import { INK, INK3, BG, BODY, TITLE, LABEL, TEXT_STYLES, lineHeightForSize } from '../design/tokens.ets';
import { FONT_MONO, FONT_SANS_MEDIUM, FONT_SANS_BOLD } from '../design/fonts.ets';
import { PageTitleBar, SectionHeader, NavRow, PageSpacer } from '../components/PageTemplate.ets';

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
            Text('AmberAgent')
              .fontSize(TITLE).fontWeight(FontWeight.Bold).lineHeight(TEXT_STYLES.screenTitle.lineHeight).fontFamily(FONT_SANS_BOLD).fontColor(INK)
            Text('鸿蒙原生版')
              .fontSize(BODY).fontWeight(FontWeight.Normal).lineHeight(TEXT_STYLES.body.lineHeight)
              .fontFamily(FONT_SANS_MEDIUM).fontColor(INK3).margin({ top: 4 })
          }
          .width('100%')
          .padding({ top: 40, bottom: 24 })
          .alignItems(HorizontalAlign.Center)

          SectionHeader({ label: '应用信息' })
          NavRow({ label: '版本', valueText: this.versionLabel, showChevron: false })
          NavRow({ label: '构建', valueText: 'HarmonyOS NEXT / ArkTS', showChevron: false })
          NavRow({ label: '开源社区项目', desc: '不收集任何用户数据', isLast: true, showChevron: false })

          Text('Powered by multiple AI providers.')
            .fontSize(LABEL).fontWeight(FontWeight.Normal).lineHeight(TEXT_STYLES.meta.lineHeight)
            .fontFamily(FONT_MONO).fontColor(INK3)
            .margin({ top: 24 })
            .textAlign(TextAlign.Center).width('100%')

          PageSpacer()
        }
        .width('100%')
      }
        .align(Alignment.TopStart)
      .layoutWeight(1)
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
