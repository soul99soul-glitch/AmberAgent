// ViewText — 文本查看器(对齐 Android ViewText.kt)
// Android 用 AndroidView bridge 渲染 Spannable;鸿蒙直接用 Text + Span
// 支持纯文本渲染 + 可选等宽字体
import { INK, INK2, INK3, BODY } from '../../design/tokens.ets';

@Component
export struct ViewText {
  @Prop text: string = '';
  @Prop fontSize: number = BODY;
  @Prop fontColor: string = INK2;
  @Prop mono: boolean = false;
  @Prop maxLines: number = 0;  // 0 = 不限
  build(): void {
    Text(this.text)
      .fontSize(this.fontSize)
      .fontColor(this.fontColor)
      .fontFamily(this.mono ? 'monospace' : 'sans-serif')
      .lineHeight(this.fontSize * 1.5)
      .maxLines(this.maxLines > 0 ? this.maxLines : 999)
      .textOverflow({ overflow: this.maxLines > 0 ? TextOverflow.Ellipsis : TextOverflow.None })
      .width('100%')
  }
}
