// DotLoading — 脉冲加载点(对齐 Android DotLoading.kt)
// 单圆点 alpha 0.3↔1 往复循环(iterations -1 + Alternate)
import { ACCENT } from '../../design/tokens.ets';

@Component
export struct DotLoading {
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @Prop color: string = '';
  @Prop dotSize: number = 16;
  @State alpha: number = 0.3;

  aboutToAppear(): void {
    animateTo({ duration: 600, curve: Curve.EaseInOut, iterations: -1, playMode: PlayMode.Alternate }, (): void => {
      this.alpha = 1;
    });
  }

  build(): void {
    Text('')
      .width(this.dotSize)
      .height(this.dotSize)
      .borderRadius(this.dotSize / 2)
      .backgroundColor(this.color.length > 0 ? this.color : this.accentColorStore)
      .opacity(this.alpha)
  }
}
