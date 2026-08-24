// Switch — 自定义开关(对齐 Android Switch.kt)
// 非 M3 Switch, 完全自绘: 圆角轨道 + 滑动圆点
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, ACCENT } from '../../design/tokens.ets';

export enum SwitchSize {
  Small,
  Medium,
  Large,
}

@Component
export struct AmberSwitch {
  @Prop checked: boolean = false;
  @Prop switchSize: SwitchSize = SwitchSize.Small;
  @Prop fieldEnabled: boolean = true;
  onCheckedChange: (checked: boolean) => void = (_c: boolean): void => {};

  private trackWidth(): number {
    if (this.switchSize === SwitchSize.Small) return 34;
    if (this.switchSize === SwitchSize.Medium) return 38;
    return 42;
  }
  private trackHeight(): number {
    if (this.switchSize === SwitchSize.Small) return 18;
    if (this.switchSize === SwitchSize.Medium) return 20;
    return 22;
  }
  private thumbSize(): number {
    if (this.switchSize === SwitchSize.Small) return 14;
    if (this.switchSize === SwitchSize.Medium) return 16;
    return 18;
  }
  private thumbOffset(): number {
    // 左右留白对称(均 2):选中态与未选中态的 thumb 内边距一致
    return this.checked ? this.trackWidth() - this.thumbSize() - 2 : 2;
  }
  private trackColor(): string {
    if (!this.fieldEnabled) return SURFACE2;
    return this.checked ? ACCENT : LINE;
  }
  private thumbColor(): string {
    if (!this.fieldEnabled) return INK4;
    return '#FFFFFF';
  }

  build(): void {
    Stack() {
      // 滑动圆点
      Text('')
        .width(this.thumbSize())
        .height(this.thumbSize())
        .borderRadius(this.thumbSize() / 2)
        .backgroundColor(this.thumbColor())
        .margin({ left: this.thumbOffset() })
    }
    .width(this.trackWidth())
    .height(this.trackHeight())
    .borderRadius(this.trackHeight() / 2)
    .backgroundColor(this.trackColor())
    .alignContent(Alignment.Start)
    .onClick((): void => {
      if (this.fieldEnabled) this.onCheckedChange(!this.checked);
    })
  }
}
