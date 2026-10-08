import { PathShape } from '@kit.ArkUI';
import { USER_BG } from '../design/tokens.ets';

// Two cubic segments per corner keep curvature continuous with the straight edges.
// PathShape SVG coordinates are px; layout and padding remain vp.
@Component
export struct ChatUserBubble {
  @BuilderParam content: () => void;
  @State bubbleWidth: number = 0;
  @State bubbleHeight: number = 0;

  private bubblePath(): string {
    const w: number = this.getUIContext().vp2px(this.bubbleWidth);
    const h: number = this.getUIContext().vp2px(this.bubbleHeight);
    const r: number = Math.min(this.getUIContext().vp2px(26), w / 2, h / 2);
    const t: number = Math.min(this.getUIContext().vp2px(6), r);
    const a: number = 0.1591;
    const b: number = 0.3182;
    const c: number = 0.55;
    return `M ${r} 0 H ${w - r} `
      + `C ${w - c * r} 0 ${w - b * r} 0 ${w - a * r} ${a * r} `
      + `C ${w} ${b * r} ${w} ${c * r} ${w} ${r} V ${h - t} `
      + `C ${w} ${h - c * t} ${w} ${h - b * t} ${w - a * t} ${h - a * t} `
      + `C ${w - b * t} ${h} ${w - c * t} ${h} ${w - t} ${h} H ${r} `
      + `C ${c * r} ${h} ${b * r} ${h} ${a * r} ${h - a * r} `
      + `C 0 ${h - b * r} 0 ${h - c * r} 0 ${h - r} V ${r} `
      + `C 0 ${c * r} 0 ${b * r} ${a * r} ${a * r} `
      + `C ${b * r} 0 ${c * r} 0 ${r} 0 Z`;
  }

  build(): void {
    Column() {
      this.content()
    }
    .alignItems(HorizontalAlign.Start)
    .padding({ left: 15, right: 15, top: 11, bottom: 11 })
    .backgroundColor(USER_BG)
    .clipShape(new PathShape({ commands: this.bubblePath() }))
    .onAreaChange((_old: Area, area: Area): void => {
      this.bubbleWidth = Number(area.width);
      this.bubbleHeight = Number(area.height);
    })
  }
}
