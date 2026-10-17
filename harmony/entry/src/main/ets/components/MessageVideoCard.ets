import { ACCENT, INK, INK3, LINE, META, SURFACE2 } from '../design/tokens.ets';
import { FONT_SANS } from '../design/fonts.ets';
import { ContentAttachmentChip, urlBasename } from './ContentAttachmentChip.ets';

/** Lazy inline video: a bounded tap-to-play card instead of an eager player per history row. */
@Component
export struct MessageVideoCard {
  @Prop url: string = '';
  @Prop mime: string = 'video/mp4';
  @State private playing: boolean = false;
  @State private failed: boolean = false;

  private label(): string {
    const name: string = urlBasename(this.url);
    return name.length > 0 ? name : '视频';
  }

  build(): void {
    if (this.url.length === 0 || this.failed) {
      ContentAttachmentChip({ icon: '🎬', label: this.label(), sublabel: this.mime });
    } else if (!this.playing) {
      Row({ space: 10 }) {
        Text('▶').fontSize(16).fontColor(ACCENT).width(36).height(36).textAlign(TextAlign.Center)
          .backgroundColor(Color.Transparent).borderRadius(18).border({ width: 1, color: ACCENT });
        Column({ space: 2 }) {
          Text(this.label()).fontSize(META).fontColor(INK).width('100%')
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis }).fontFamily(FONT_SANS);
          if (this.mime.length > 0) {
            Text(this.mime).fontSize(META).fontColor(INK3).width('100%').maxLines(1);
          }
        }.layoutWeight(1).alignItems(HorizontalAlign.Start);
        Text('播放').fontSize(META).fontColor(ACCENT).constraintSize({ minWidth: 44, minHeight: 44 })
          .textAlign(TextAlign.Center);
      }
      .width('100%').constraintSize({ minHeight: 60 }).padding({ left: 10, right: 6, top: 6, bottom: 6 })
      .backgroundColor(SURFACE2).borderRadius(10).border({ width: 1, color: LINE })
      .accessibilityGroup(true).accessibilityText(`${this.label()}，视频，点击播放`)
      .onClick((): void => { this.playing = true; });
    } else {
      Column({ space: 4 }) {
        Video({ src: this.url }).controls(true).autoPlay(true).loop(false)
          .width('100%').height(220).objectFit(ImageFit.Contain)
          .backgroundColor(Color.Black).borderRadius(10)
          .onError((): void => { this.failed = true; });
        if (this.mime.length > 0) {
          Text(this.mime).fontSize(META).fontColor(INK3).width('100%').maxLines(1);
        }
      }.width('100%').alignItems(HorizontalAlign.Start).margin({ bottom: 4 });
    }
  }
}
