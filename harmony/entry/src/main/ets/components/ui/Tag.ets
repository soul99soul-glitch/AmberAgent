// Tag — 状态标签(对齐 Android Tag.kt)
// TagType: DEFAULT / SUCCESS / ERROR / WARNING / INFO
import { INK2, SURFACE2, ERROR_BG, SUCCESS_BG, SUCCESS_FG, WARNING_BG, WARNING_FG, INFO_BG, INFO_FG, ERROR } from '../../design/tokens.ets';

export enum TagType {
  Default,
  Success,
  Error,
  Warning,
  Info,
}

@Component
export struct Tag {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop type: TagType = TagType.Default;
  @Prop text: string = '';
  onClick: () => void = (): void => {};

  private bgColor(): string {
    if (this.type === TagType.Success) return SUCCESS_BG;
    if (this.type === TagType.Error) return ERROR_BG;
    if (this.type === TagType.Warning) return WARNING_BG;
    if (this.type === TagType.Info) return INFO_BG;
    return SURFACE2;
  }
  private fgColor(): string {
    if (this.type === TagType.Success) return SUCCESS_FG;
    if (this.type === TagType.Error) return ERROR;
    if (this.type === TagType.Warning) return WARNING_FG;
    if (this.type === TagType.Info) return INFO_FG;
    return INK2;
  }

  build(): void {
    Text(this.text)
      
      .opacity(this.themeEpoch >= 0 ? 1 : 1).fontSize(11)
      .fontWeight(FontWeight.Medium)
      .fontColor(this.fgColor())
      .padding({ left: 6, right: 6, top: 3, bottom: 3 })
      .backgroundColor(this.bgColor())
      .borderRadius(50)
      .maxLines(1)
      .onClick((): void => { this.onClick(); })
  }
}
