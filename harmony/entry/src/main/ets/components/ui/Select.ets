// Select — 下拉选择器(对齐 Android Select.kt)
// pill 形 anchor + 下拉菜单
import { INK, INK2, INK3, SURFACE, SURFACE2, LINE, ACCENT, ACCENT_LIGHT, BODY, META } from '../../design/tokens.ets';

@Component
export struct Select {
  @Prop options: string[] = [];
  @Prop selectedIndex: number = 0;
  @State expanded: boolean = false;
  onSelect: (index: number) => void = (_i: number): void => {};

  build(): void {
    Column() {
      // anchor pill
      Row() {
        Text(this.selectedIndex < this.options.length ? this.options[this.selectedIndex] : '')
          .fontSize(META)
          .fontColor(ACCENT)
          .maxLines(1)
          .margin({ right: 4 })
        Text(this.expanded ? '▲' : '▼')
          .fontSize(10)
          .fontColor(ACCENT)
      }
      .padding({ left: 14, right: 14, top: 7, bottom: 7 })
      .backgroundColor(ACCENT_LIGHT)
      .borderRadius(50)
      .border({ width: 1, color: ACCENT })
      .onClick((): void => { this.expanded = !this.expanded; })

      // 下拉菜单
      if (this.expanded) {
        Column() {
          ForEach(this.options, (opt: string, idx: number): void => {
            Row() {
              Text(opt)
                .fontSize(BODY)
                .fontColor(idx === this.selectedIndex ? ACCENT : INK2)
                .layoutWeight(1)
            }
            .width('100%')
            .padding({ left: 14, right: 14, top: 8, bottom: 8 })
            .onClick((): void => {
              this.onSelect(idx);
              this.expanded = false;
            })
          }, (opt: string, idx: number): string => `opt-${idx}`)
        }
        .width('100%')
        .backgroundColor(SURFACE)
        .borderRadius(8)
        .border({ width: 1, color: LINE })
        .margin({ top: 2 })
      }
    }
  }
}
