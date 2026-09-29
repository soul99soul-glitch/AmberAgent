// Switch — 自定义开关(对齐 Android Switch.kt)
// 非 M3 Switch, 完全自绘: 圆角轨道 + 滑动圆点;触达区 ≥44
import { SURFACE2, LINE, ACCENT, INK4 } from '../../design/tokens.ets';

export enum SwitchSize {
  Small,
  Medium,
  Large,
}

@Component
export struct AmberSwitch {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
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
    return this.checked ? this.trackWidth() - this.thumbSize() - 2 : 2;
  }
  private trackColor(): string {
    if (!this.fieldEnabled) return SURFACE2;
    return this.checked ? this.accentColorStore : LINE;
  }

  build(): void {
    Row() {
      Stack() {
        Text('')
          .width(this.thumbSize())
          .height(this.thumbSize())
          .borderRadius(this.thumbSize() / 2)
          .backgroundColor(this.fieldEnabled ? Color.White : INK4)
          .margin({ left: this.thumbOffset() })
          .animation({ duration: 150, curve: Curve.EaseOut })
      }
      .width(this.trackWidth())
      .height(this.trackHeight())
      .borderRadius(this.trackHeight() / 2)
      .backgroundColor(this.trackColor())
      .alignContent(Alignment.Start)
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .constraintSize({ minWidth: 44, minHeight: 44 })
    .justifyContent(FlexAlign.Center)
    .alignItems(VerticalAlign.Center)
    .onClick((): void => {
      if (this.fieldEnabled) this.onCheckedChange(!this.checked);
    })
    .accessibilityText(this.checked ? '已开启' : '已关闭')
  }
}
