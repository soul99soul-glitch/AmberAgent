// DotLoading — 三点脉冲加载(对齐 Android DotLoading.kt)
// 三个小圆点共享 alpha 0.3↔1 往复循环(iterations -1 + Alternate)
import { INK3 } from '../../design/tokens.ets';

@Component
export struct DotLoading {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop color: string = INK3;
  @Prop dotSize: number = 6;
  @State alpha: number = 0.3;

  private startPulse(): void {
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
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .height(this.dotSize)
    .onAppear((): void => { this.startPulse(); })
  }
}
