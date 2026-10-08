import { Icon } from '../ui/Icon.ets';
import { DeepReadDesign as D } from '../../design/DeepReadDesign.ets';

// The standalone reader keeps its controls in the navigation bar, as the iOS detail view does.
@Component
export struct DeepReadArticleToolbar {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @Prop fontScale: number = 1;
  @Prop canvasColor: string = D.paper;
  @Prop hasSources: boolean = false;
  @Prop canShare: boolean = false;
  @Prop hasArticle: boolean = false;
  @Prop running: boolean = false;
  @Prop canStyle: boolean = true;
  onBack: () => void = (): void => {};
  onSources: () => void = (): void => {};
  onShare: () => void = (): void => {};
  onZoomIn: () => void = (): void => {};
  onZoomOut: () => void = (): void => {};
  onTemplates: () => void = (): void => {};
  onRegenerate: () => void = (): void => {};
  onSave: () => void = (): void => {};

  @Builder
  StyleMenu(): void {
    Menu() {
      MenuItem({ content: '版式与样式' }).onClick((): void => { this.onTemplates(); })
      MenuItem({ content: `字号 ${Math.round(this.fontScale * 100)}%` }).enabled(false)
      MenuItem({ content: '缩小字号' }).enabled(this.fontScale > 0.7)
        .onClick((): void => { this.onZoomOut(); })
      MenuItem({ content: '放大字号' }).enabled(this.fontScale < 1.8)
        .onClick((): void => { this.onZoomIn(); })
      MenuItem({ content: '保存到工作区' }).enabled(this.hasArticle && !this.running)
        .onClick((): void => { this.onSave(); })
      MenuItem({ content: '重新生成' }).enabled(this.hasArticle && !this.running)
        .onClick((): void => { this.onRegenerate(); })
    }
  }

  @Builder
  ArticleMenu(): void {
    Menu() {
      MenuItem({ content: '保存到工作区' }).enabled(this.hasArticle && !this.running)
        .onClick((): void => { this.onSave(); })
      MenuItem({ content: '重新生成' }).enabled(this.hasArticle && !this.running)
        .onClick((): void => { this.onRegenerate(); })
    }
  }

  private sharePath(): string {
    // Path coordinates are pixels; use this window's density for the 20vp glyph.
    const unit: number = this.getUIContext().vp2px(1);
    return `M ${10 * unit} ${13 * unit} V ${unit} M ${6 * unit} ${5 * unit} L ${10 * unit} ${unit} L ${14 * unit} ${5 * unit}`
      + ` M ${6 * unit} ${7 * unit} H ${3 * unit} Q ${2 * unit} ${7 * unit} ${2 * unit} ${8 * unit}`
      + ` V ${18 * unit} Q ${2 * unit} ${19 * unit} ${3 * unit} ${19 * unit} H ${17 * unit}`
      + ` Q ${18 * unit} ${19 * unit} ${18 * unit} ${18 * unit} V ${8 * unit}`
      + ` Q ${18 * unit} ${7 * unit} ${17 * unit} ${7 * unit} H ${14 * unit}`;
  }

  build(): void {
    Row() {
      Row({ space: 8 }) {
        Stack() {
          Row().width(40).height(40).borderRadius(20).backgroundColor(D.card)
            .shadow({ radius: 10, color: '#0A2A2320', offsetY: 3 })
          Icon({ name: 'back', iconSize: 20, color: D.ink })
        }.width(44).height(44).accessibilityText('返回').onClick((): void => { this.onBack(); })
        Text('深度阅读').fontSize(17).fontWeight(600).fontColor(D.ink)
          .fontFamily(D.sans).maxLines(1)
      }
      Blank()
      Stack() {
        Row().width('100%').height(40).borderRadius(20).backgroundColor(D.card)
          .shadow({ radius: 12, color: '#0A2A2320', offsetY: 4 })
        Row() {
          if (this.canStyle) {
            Text('Aa').fontFamily(D.serif).fontSize(19).fontColor(D.accent)
              .width(44).height(44).textAlign(TextAlign.Center)
              .accessibilityText('版式与样式').bindMenu(this.StyleMenu())
          } else if (this.hasArticle) {
            Row() { Icon({ name: 'more', iconSize: 19, color: D.accent }) }
              .width(44).height(44).justifyContent(FlexAlign.Center)
              .accessibilityText('文章操作').bindMenu(this.ArticleMenu())
          }
          Row() { Icon({ name: 'link', iconSize: 20, color: D.accent }) }
            .width(44).height(44).justifyContent(FlexAlign.Center)
            .accessibilityText('来源').onClick((): void => { this.onSources(); })
          Row() {
            Path().commands(this.sharePath()).width(20).height(20).fillOpacity(0)
              .stroke(D.accent).strokeWidth(1.5).strokeLineCap(LineCapStyle.Round).strokeLineJoin(LineJoinStyle.Round)
          }.width(44).height(44).justifyContent(FlexAlign.Center)
            .opacity(this.canShare ? 1 : 0.35).enabled(this.canShare)
            .accessibilityText('分享').onClick((): void => { this.onShare(); })
        }.padding({ left: 6, right: 6 })
      }.width(this.canStyle || this.hasArticle ? 144 : 100).height(44)
    }.width('100%').padding({ left: 14, right: 14, top: this.statusBarHeight + 2, bottom: 6 })
      .backgroundColor(this.canvasColor).opacity(this.themeEpoch >= 0 ? 1 : 1)
  }
}
