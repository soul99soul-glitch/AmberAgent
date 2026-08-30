// NovelSettingProposalCard — 小说讨论产生的设定提案，确认后才写入项目设定。
import { ACCENT, ACCENT_INK, INK, INK2, INK3, LINE, SURFACE, SURFACE2 } from '../design/tokens.ets';

@Component
export struct NovelSettingProposalCard {
  @Prop title: string = '';
  @Prop content: string = '';
  @Prop kindLabel: string = '设定提案';
  onResolve: (accepted: boolean) => void = (_accepted: boolean): void => {};

  build(): void {
    Column() {
      Row() {
        Text(this.kindLabel).fontSize(11).fontColor(ACCENT)
          .padding({ left: 8, right: 8, top: 3, bottom: 3 }).borderRadius(8)
          .backgroundColor(SURFACE2)
        Text(this.title.length > 0 ? this.title : '待确认设定')
          .fontSize(14).fontColor(INK).fontWeight(FontWeight.Medium)
          .margin({ left: 8 }).layoutWeight(1)
      }
      .width('100%')
      if (this.content.length > 0) {
        Text(this.content).fontSize(13).fontColor(INK2).lineHeight(20)
          .width('100%').margin({ top: 9 })
      }
      Row() {
        Blank()
        Text('忽略').fontSize(13).fontColor(INK3)
          .constraintSize({ minWidth: 44 }).height(44).textAlign(TextAlign.Center)
          .padding({ left: 12, right: 12 })
          .onClick((): void => { this.onResolve(false); })
        Text('采用').fontSize(13).fontColor(ACCENT_INK)
          .constraintSize({ minWidth: 44 }).height(44).textAlign(TextAlign.Center)
          .padding({ left: 15, right: 15 })
          .backgroundColor(ACCENT).borderRadius(22).margin({ left: 8 })
          .onClick((): void => { this.onResolve(true); })
      }
      .width('100%').margin({ top: 12 })
    }
    .width('100%').padding(14).backgroundColor(SURFACE).borderRadius(12)
    .border({ width: 1, color: LINE }).margin({ bottom: 12 })
    .alignItems(HorizontalAlign.Start)
  }
}
