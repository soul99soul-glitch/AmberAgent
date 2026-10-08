import { ACCENT, INK, INK2, INK3, LINE } from '../../design/tokens.ets';
import { FONT_MONO, FONT_SERIF, FONT_SANS } from '../../design/fonts.ets';
import { StudioInkStamp } from './StudioInkStamp.ets';

@Component
export struct StudioHero {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @Prop kind: string = 'novel';
  @Prop title: string = '';
  @Prop caption: string = '';
  @Prop detail: string = '';
  @Prop compact: boolean = false;
  @Prop @Watch('activeChanged') active: boolean = true;
  @State private secret: boolean = false;
  @State private stamp: number = 0;

  private activeChanged(): void {
    if (!this.active) {
      this.secret = false;
      this.stamp = 0;
    }
  }

  private secretCaption(): string {
    return this.kind === 'novel' ? '故事开卷，落笔成章。' : '慢一点，才能读得深一点。';
  }

  @Builder
  Copy(): void {
    Column({ space: 7 }) {
      Row() {
        Text(this.kind === 'novel' ? 'AMBER / STORY STUDIO' : 'AMBER / DEEP READ')
          .fontSize(9).fontFamily(FONT_MONO).letterSpacing(1.2).fontColor(INK3)
          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis }).layoutWeight(1)
        Text('长按刊头 · 开卷').fontSize(9).fontFamily(FONT_SANS).fontColor(INK3)
      }.width('100%')
      Row().height(2).width('100%').backgroundColor(INK).margin({ top: 3, bottom: 4 })
      Row() {
        Text(this.title).fontSize(this.compact ? 24 : 28).fontFamily(FONT_SERIF).fontWeight(FontWeight.Medium)
          .lineHeight(this.compact ? 33 : 39).fontColor(INK).layoutWeight(1)
          .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
        StudioInkStamp({ word: '开卷', trigger: this.stamp, active: this.active, stampSize: this.compact ? 34 : 42,
          onVisibilityChange: (visible: boolean): void => { this.secret = visible; } })
          .margin({ left: 8 })
      }.width('100%').alignItems(VerticalAlign.Center)
      Text(this.secret ? this.secretCaption() : this.compact && this.detail.length > 0 ? this.detail : this.caption)
        .fontSize(12).fontFamily(FONT_SANS)
        .lineHeight(19).fontColor(this.secret ? ACCENT : INK2).width('100%')
        .textAlign(TextAlign.Start)
        .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
      if (!this.compact && this.detail.length > 0) {
        Text(this.detail).fontSize(11).fontFamily(FONT_SANS).lineHeight(17).fontColor(INK3).width('100%')
          .textAlign(TextAlign.Start)
          .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
      }
      Row().height(0.5).width('100%').backgroundColor(LINE).margin({ top: 5 })
    }.width('100%').alignItems(HorizontalAlign.Start)
  }

  build(): void {
    Column() { this.Copy() }
      .width('100%').padding({ top: 10, bottom: 10 })
      .gesture(LongPressGesture().onAction((): void => {
        if (this.active) this.stamp += 1;
      }))
      .accessibilityText(this.title + '，长按刊头开卷')
  }
}
