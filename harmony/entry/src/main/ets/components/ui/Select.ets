// Select — 下拉选择器(对齐 Android Select.kt)
// pill 形 anchor + 下拉菜单
import { MOTION_OVERLAY, INK, INK2, INK3, SURFACE, SURFACE2, LINE, ACCENT, ACCENT_LIGHT, BODY, META } from '../../design/tokens.ets';

@Component
export struct PillSelect {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop options: string[] = [];
  @Prop selectedIndex: number = 0;
  @Prop triggerColor: string = '';
  @Prop triggerMinHeight: number = 0;
  @State expanded: boolean = false;
  onSelect: (index: number) => void = (_i: number): void => {};

  build(): void {
    Column() {
      // anchor pill
      Row() {
        Text(this.selectedIndex < this.options.length ? this.options[this.selectedIndex] : '')
          .fontSize(META)
          .fontColor(this.triggerColor.length > 0 ? this.triggerColor : ACCENT)
          .maxLines(1)
          .margin({ right: 4 })
        Text('▼')
          .fontSize(10)
          .fontColor(this.triggerColor.length > 0 ? this.triggerColor : ACCENT)
          .rotate({ angle: this.expanded ? 180 : 0 })
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut })
      }
      .padding({ left: 14, right: 14, top: 7, bottom: 7 })
      .constraintSize({ minHeight: this.triggerMinHeight })
      .backgroundColor(ACCENT_LIGHT)
      .borderRadius(50)
      .border({ width: 1, color: ACCENT })
      .onClick((): void => {
        this.getUIContext().animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
          this.expanded = !this.expanded;
        });
      })

      // 下拉菜单
      if (this.expanded) {
        Column() {
          ForEach(this.options, (opt: string, idx: number): void => {
            Row() {
              Text(opt)
                .fontSize(BODY)
                .fontColor(idx === this.selectedIndex
                  ? (this.triggerColor.length > 0 ? this.triggerColor : ACCENT) : INK2)
                .layoutWeight(1)
            }
            .width('100%')
            .padding({ left: 14, right: 14, top: 8, bottom: 8 })
            .onClick((): void => {
              this.onSelect(idx);
              this.getUIContext().animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
                this.expanded = false;
              });
            })
          }, (opt: string, idx: number): string => `opt-${idx}`)
        }
        .width('100%')
        .backgroundColor(SURFACE)
        .borderRadius(8)
        .border({ width: 1, color: LINE })
        .margin({ top: 2 })
        .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: -8 }))
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      }
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .clip(true)
  }
}
