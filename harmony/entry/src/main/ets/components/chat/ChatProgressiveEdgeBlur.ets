// ChatProgressiveEdgeBlur — 无深读依赖的顶部/底部窄带渐进模糊组件
// 复用已真机验证的 backgroundEffect + BlendMode.DST_IN 机制，切后台自动停帧。

import { BG } from '../../design/tokens.ets';

@Component
export struct ChatProgressiveEdgeBlur {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('darkMode') darkMode: boolean = false;
  @StorageProp('appBackgrounded') appBackgrounded: boolean = false;
  @Prop top: boolean = true;
  @Prop bandHeight: number = 98;
  @Prop active: boolean = true;

  private paperTint(): string {
    const clean: string = BG.startsWith('#') && BG.length === 7 ? BG.substring(1) : 'EFE7D6';
    return (this.darkMode ? '#99' : '#CC') + clean;
  }

  build(): void {
    Column() {
      Row()
        .width('100%')
        .height('100%')
        .linearGradient({
          direction: this.top ? GradientDirection.Top : GradientDirection.Bottom,
          colors: [[Color.Transparent, 0], ['#80FFFFFF', 0.45], [Color.White, 1]],
        })
        .blendMode(BlendMode.DST_IN, BlendApplyType.OFFSCREEN)
    }
    .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .height(this.bandHeight)
    .blendMode(BlendMode.SRC_OVER, BlendApplyType.OFFSCREEN)
    .backgroundEffect({
      radius: this.active && !this.appBackgrounded ? 12 : 0,
      saturation: 1.05,
      brightness: 1,
      color: this.paperTint(),
    })
    .hitTestBehavior(HitTestMode.None)
  }
}
