// SettingAboutPage — 关于页(真实版本值,卡容器对齐设置体系)
// Android 基准: SettingAboutPage.kt — 版本/构建/开源说明。
//   版本号经 bundleManager 取真值(不写死);开源仓库无外链目标 → 纯说明行(无箭头)。
// 2026-09-27 二级页 iOS 对齐:仅换视觉骨架(CenteredTitleBar/PlainSection/
//   CardGroup r18+描边/CardRow/FooterNote),版本信息与展示逻辑零改动。
import { bundleManager } from '@kit.AbilityKit';
import { INK, INK3, BG, BODY, TITLE, TEXT_STYLES } from '../design/tokens.ets';
import { FONT_SANS_MEDIUM, FONT_SANS_BOLD } from '../design/fonts.ets';
import {
  CenteredTitleBar, PlainSection, CardGroup, CardRow, FooterNote, PageSpacer,
} from '../components/PageTemplate.ets';

@Entry
@Component
struct SettingAboutPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
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
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      CenteredTitleBar({ title: '关于' })

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
          .padding({ top: 24, bottom: 0 })
          .alignItems(HorizontalAlign.Center)

          PlainSection({ label: '应用信息' })
          CardGroup({ radius: 18, stroke: true, content: (): void => { this.InfoCard(); } })
            .margin({ left: 16, right: 16 })

          FooterNote({ text: 'Powered by multiple AI providers.' })

          PageSpacer()
        }
        .width('100%')
      }
        .align(Alignment.TopStart)
      .layoutWeight(1)
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%').backgroundColor(BG)
  }

  // ── 卡片内容(@Builder:组件构造必须在 builder 上下文,铁律 4b)──

  @Builder
  InfoCard(): void {
    Column() {
      CardRow({ label: '版本', valueText: this.versionLabel, showChevron: false })
      CardRow({ label: '构建', valueText: 'HarmonyOS NEXT / ArkTS', showChevron: false })
      CardRow({ label: '开源社区项目', desc: '不收集任何用户数据', isLast: true, showChevron: false })
    }
    .width('100%')
  }
}
