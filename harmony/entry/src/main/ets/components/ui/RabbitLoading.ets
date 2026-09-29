// RabbitLoading — 加载动画(ui/RabbitLoading.kt:35 行)
// Android 基准: AnimatedVectorDrawable(R.drawable.pig)+ primary 色 tint
// ArkUI: 无 AVD 等价 → 复用统一 DotLoading 动画
//   登记:需 Lottie/AVPlayer 资源移植后才还原本动画
import { DotLoading } from './DotLoading.ets';
import { ACCENT } from '../../design/tokens.ets';

@Component
export struct RabbitLoading {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop iconSize: number = 32;
  build(): void {
    Column() {
      DotLoading({ color: ACCENT, dotSize: this.iconSize / 3 })
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width(this.iconSize).height(this.iconSize)
    .justifyContent(FlexAlign.Center)
  }
}
