// NotionListRow — Notion 风格列表行(ui/NotionListRow.kt:124 行)
// Android 基准: 56dp min 高 + leading/title/subtitle/trailing + selected 态(背景色变化)
// ArkUI 差异: Surface → Row with 条件 backgroundColor
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, ACCENT, ACCENT_LIGHT, BODY, META, LABEL } from '../../design/tokens.ets';

@Component
export struct NotionListRow {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop title: string = '';
  @Prop subtitle: string = '';
  @Prop selected: boolean = false;
  @Prop enabled: boolean = true;
  onClick: () => void = (): void => {};

  build(): void {
    Row() {
      Column() {
        Text(this.title)
          .fontSize(BODY)
          .fontColor(this.selected ? ACCENT : INK)
          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        if (this.subtitle.length > 0) {
          Text(this.subtitle)
            .fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        }
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1)
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .constraintSize({ minHeight: 56 })
    .padding({ left: 16, right: 16 })
    .backgroundColor(this.selected ? ACCENT_LIGHT : SURFACE)
    .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
    .onClick((): void => { if (this.enabled) this.onClick(); })
  }
}
