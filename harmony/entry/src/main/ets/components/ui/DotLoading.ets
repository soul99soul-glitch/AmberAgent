// DotLoading — 脉冲加载点(对齐 Android DotLoading.kt)
// 单圆点 alpha 0.3↔1 循环
import { ACCENT } from '../../design/tokens.ets';

@Component
export struct DotLoading {
  @Prop color: string = ACCENT;
  @Prop size: number = 16;
  @State alpha: number = 0.3;

  build(): void {
    Text('')
      .width(this.size)
      .height(this.size)
      .borderRadius(this.size / 2)
      .backgroundColor(this.color)
      .opacity(this.alpha)
  }
}
