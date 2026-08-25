// DotLoading — 三点脉冲加载(对齐 Android DotLoading.kt)
// 三个小圆点共享 alpha 0.3↔1 往复循环(iterations -1 + Alternate)
import { INK3 } from '../../design/tokens.ets';

@Component
export struct DotLoading {
  @Prop color: string = INK3;
  @Prop dotSize: number = 6;
  @State alpha: number = 0.3;

  aboutToAppear(): void {
    animateTo({ duration: 600, curve: Curve.EaseInOut, iterations: -1, playMode: PlayMode.Alternate }, (): void => {
      this.alpha = 1;
    });
  }

  build(): void {
    Row() {
      Circle({ width: this.dotSize, height: this.dotSize })
        .fill(this.color)
        .opacity(this.alpha)
      Circle({ width: this.dotSize, height: this.dotSize })
        .fill(this.color)
        .opacity(this.alpha)
        .margin({ left: 4 })
      Circle({ width: this.dotSize, height: this.dotSize })
        .fill(this.color)
        .opacity(this.alpha)
        .margin({ left: 4 })
    }
    .height(this.dotSize)
  }
}
