// RabbitLoading — 加载动画(ui/RabbitLoading.kt:35 行)
// Android 基准: AnimatedVectorDrawable(R.drawable.pig)+ primary 色 tint
// ArkUI: 无 AVD 等价 → 降级用既有 DotLoading 三点动画(design/components.ets)
//   登记:需 Lottie/AVPlayer 资源移植后才还原本动画
import { DotLoading } from '../../design/components.ets';
import { ACCENT } from '../../design/tokens.ets';

@Component
export struct RabbitLoading {
  @Prop iconSize: number = 32;
  build(): void {
    Column() {
      DotLoading({ color: ACCENT, dotSize: this.iconSize / 3 })
    }
    .width(this.iconSize).height(this.iconSize)
    .justifyContent(FlexAlign.Center)
  }
}
