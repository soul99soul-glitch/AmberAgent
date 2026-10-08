import { Icon } from '../ui/Icon.ets';
import { FONT_SERIF } from '../../design/fonts.ets';
import { BG, INK, INK3, SURFACE2, META } from '../../design/tokens.ets';

// Preserve the host navigation while the independent reader follows the iOS product.
@Component
export struct DeepReadArticleHostToolbar {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @Prop fontScale: number = 1;
  @Prop hasSources: boolean = false;
  @Prop canShare: boolean = false;
  @Prop hasArticle: boolean = false;
  @Prop templateName: string = '';
  @Prop templateMessage: string = '';
  @Prop nativeReadingOverride: boolean = false;
  onBack: () => void = (): void => {};
  onSources: () => void = (): void => {};
  onShare: () => void = (): void => {};
  onZoomIn: () => void = (): void => {};
  onZoomOut: () => void = (): void => {};

  build(): void {
    Column() {
      Row() {
        Row() { Icon({ name: 'back', iconSize: 20, color: INK3 }) }
          .width(44).height(44).justifyContent(FlexAlign.Center).onClick((): void => { this.onBack(); })
        Text('深度阅读').fontSize(18).fontWeight(FontWeight.Bold).fontColor(INK)
          .fontFamily(FONT_SERIF).margin({ left: 4 }).layoutWeight(1)
        if (this.hasSources) {
          Text('资料').fontSize(META).fontColor(INK).width(44).height(44).textAlign(TextAlign.Center)
            .onClick((): void => { this.onSources(); })
        }
        if (this.canShare) {
          Text('导出').fontSize(META).fontColor(INK).width(44).height(44).textAlign(TextAlign.Center)
            .onClick((): void => { this.onShare(); })
        }
        Row() {
          Text('A-').fontSize(13).fontColor(this.fontScale <= 0.7 ? INK3 : INK)
            .width(44).height(44).textAlign(TextAlign.Center).onClick((): void => { this.onZoomOut(); })
          Text('A+').fontSize(15).fontColor(this.fontScale >= 1.8 ? INK3 : INK)
            .width(44).height(44).textAlign(TextAlign.Center).onClick((): void => { this.onZoomIn(); })
        }.borderRadius(8).backgroundColor(SURFACE2)
      }.width('100%').padding({ left: 8, right: 12, top: this.statusBarHeight + 2, bottom: 8 }).backgroundColor(BG)
      if (this.hasArticle) {
        Text('本篇版式：' + this.templateName + (this.nativeReadingOverride ? ' · 当前使用原生阅读' : ''))
          .fontSize(META).fontColor(INK3).width('100%').padding({ left: 20, right: 20, bottom: 8 })
        if (this.templateMessage.length > 0) {
          Text(this.templateMessage).fontSize(META).fontColor(INK3).width('100%')
            .padding({ left: 20, right: 20, bottom: 8 })
        }
      }
    }.width('100%').opacity(this.themeEpoch >= 0 ? 1 : 1)
  }
}
