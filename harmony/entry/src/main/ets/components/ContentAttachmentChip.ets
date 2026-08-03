// ContentAttachmentChip — 消息内附件 pill(M2.2 part 诚实化)
// Android 基准: ChatMessageMessagePartsBlock.kt:345-515(video/audio/document chip)
//   鸿蒙 MVP:统一 pill(图标 + 文件名/mime);不依赖 picker(渲染侧,非输入侧)
//
// 用途:assistant 回复的 video/audio/document part 从占位文本 [视频]/[音频]/[文档]
//   升级为真实可读附件 chip。文件名优先 part.fileName(audio/document),
//   无则从 url basename 提取;再无则用 mime 通用名。
import { INK2, INK3, INK4, SURFACE2, BODY, META, LABEL } from '../design/tokens.ets';

@Component
export struct ContentAttachmentChip {
  @Prop icon: string = '📄';
  @Prop label: string = '';
  @Prop sublabel: string = '';
  build(): void {
    Row() {
      Text(this.icon).fontSize(BODY).margin({ right: 8 })
      Column() {
        Text(this.label.length > 0 ? this.label : '附件')
          .fontSize(META).fontColor(INK2)
          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        if (this.sublabel.length > 0) {
          Text(this.sublabel).fontSize(LABEL).fontColor(INK4).margin({ top: 1 })
        }
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1)
    }
    .width('100%')
    .padding({ left: 12, right: 12, top: 8, bottom: 8 })
    .backgroundColor(SURFACE2)
    .borderRadius(8)
    .margin({ bottom: 4 })
  }
}

// 从 url 提取 basename(data: URL / 空 → '')
export const urlBasename = (url: string): string => {
  if (url.length === 0) return '';
  if (url.startsWith('data:')) return '';
  const qIdx: number = url.indexOf('?');
  const path: string = qIdx >= 0 ? url.substring(0, qIdx) : url;
  const slash: number = path.lastIndexOf('/');
  return slash >= 0 ? path.substring(slash + 1) : path;
};
