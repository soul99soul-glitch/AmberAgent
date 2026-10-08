import { DeepReadDesign as D } from '../../design/DeepReadDesign.ets';

// Keep the material in a narrow edge band. The inner alpha mask follows the
// official backgroundEffect/blendMode pattern without blurring the whole page.
@Component
export struct DeepReadEdgeFade {
  @StorageProp('darkMode') darkMode: boolean = false;
  @StorageProp('appBackgrounded') appBackgrounded: boolean = false;
  @Prop top: boolean = true;
  @Prop bandHeight: number = 96;
  @Prop active: boolean = true;

  private paperTint(): string { return (this.darkMode ? '#99' : '#CC') + D.paper.substring(1); }

  build(): void {
    Column() {
      Row().width('100%').height('100%')
        .linearGradient({ direction: this.top ? GradientDirection.Top : GradientDirection.Bottom,
          colors: [[Color.Transparent, 0], ['#80FFFFFF', 0.45], [Color.White, 1]] })
        .blendMode(BlendMode.DST_IN, BlendApplyType.OFFSCREEN)
    }.width('100%').height(this.bandHeight)
      .blendMode(BlendMode.SRC_OVER, BlendApplyType.OFFSCREEN)
      .backgroundEffect({ radius: this.active && !this.appBackgrounded ? 12 : 0,
        saturation: 1.05, brightness: 1, color: this.paperTint() })
      .hitTestBehavior(HitTestMode.None)
  }
}
