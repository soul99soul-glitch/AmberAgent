// Tag — 状态标签(对齐 Android Tag.kt)
// TagType: DEFAULT / SUCCESS / ERROR / WARNING / INFO
import { INK, INK2, INK3, SURFACE2, ACCENT } from '../../design/tokens.ets';

export enum TagType {
  Default,
  Success,
  Error,
  Warning,
  Info,
}

@Component
export struct Tag {
  @Prop type: TagType = TagType.Default;
  @Prop text: string = '';
  onClick: () => void = (): void => {};

  private bgColor(): string {
    if (this.type === TagType.Success) return '#AFF0B5';
    if (this.type === TagType.Error) return '#FDCDC5';
    if (this.type === TagType.Warning) return '#FFE4BA';
    if (this.type === TagType.Info) return '#C3E7FE';
    return SURFACE2;
  }
  private fgColor(): string {
    if (this.type === TagType.Success) return '#4E5969';
    if (this.type === TagType.Error) return '#A1151E';
    if (this.type === TagType.Warning) return '#A64500';
    if (this.type === TagType.Info) return '#114BA3';
    return INK2;
  }

  build(): void {
    Text(this.text)
      .fontSize(11)
      .fontWeight(FontWeight.Medium)
      .fontColor(this.fgColor())
      .padding({ left: 6, right: 6, top: 1, bottom: 1 })
      .backgroundColor(this.bgColor())
      .borderRadius(50)
      .maxLines(1)
      .onClick((): void => { this.onClick(); })
  }
}
