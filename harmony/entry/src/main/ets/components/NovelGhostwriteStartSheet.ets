import { ACCENT, ACCENT_INK, INK, INK3, INK4, LINE, MOTION_OVERLAY, OVERLAY, SURFACE, SURFACE2, ERROR } from '../design/tokens.ets';
import { MarkdownText } from './MarkdownText.ets';

@Component
export struct NovelGhostwriteStartSheet {
  @StorageProp('reduceMotion') private reduceMotion: boolean = false;
  @StorageProp('appBackgrounded') private appBackgrounded: boolean = false;
  @Prop planContent: string = '';
  @Prop chapterCount: number = 1;
  @Prop branchName: string = '';
  @Prop busy: boolean = false;
  @Prop errorMessage: string = '';
  @Prop bottomPadding: number = 0;
  onConfirm: () => void = (): void => {};
  onClose: () => void = (): void => {};

  build(): void {
    Stack({ alignContent: Alignment.Bottom }) {
      Row() {}.width('100%').height('100%').backgroundColor(OVERLAY)
        .onClick((): void => { if (!this.busy) this.onClose(); })
      Column({ space: 12 }) {
        Row() {
          Text('确认代笔计划').fontSize(18).fontWeight(FontWeight.Bold).fontColor(INK).layoutWeight(1)
          Text('关闭').fontSize(13).fontColor(this.busy ? INK4 : INK3).width(44).height(44)
            .textAlign(TextAlign.Center).onClick((): void => { if (!this.busy) this.onClose(); })
        }.width('100%')
        Text(`${this.branchName} · 新增 ${this.chapterCount} 章`).fontSize(13).fontColor(INK3).width('100%')
        Text('下面是本次完整作者计划。确认后，任务使用这份计划与当前正文。')
          .fontSize(12).fontColor(INK3).lineHeight(18).width('100%')
        Scroll() {
          Column() {
            MarkdownText({ content: this.planContent, fontColor: INK, enableLatexRendering: false })
          }.width('100%').padding(12).alignItems(HorizontalAlign.Start)
        }.width('100%').layoutWeight(1).backgroundColor(SURFACE2).borderRadius(10).align(Alignment.TopStart)
        if (this.errorMessage.length > 0) {
          Text(this.errorMessage).fontSize(12).fontColor(ERROR).lineHeight(18).width('100%')
        }
        Row({ space: 12 }) {
          Text('返回修改').fontSize(14).fontColor(INK3).layoutWeight(1).height(44)
            .textAlign(TextAlign.Center).backgroundColor(SURFACE2).borderRadius(10)
            .onClick((): void => { if (!this.busy) this.onClose(); })
          Text(this.busy ? '正在创建…' : '确认并开始').fontSize(14).fontColor(this.busy ? INK4 : ACCENT_INK)
            .layoutWeight(1).height(44).textAlign(TextAlign.Center).backgroundColor(ACCENT).borderRadius(10)
            .enabled(!this.busy && this.errorMessage.length === 0).onClick((): void => { this.onConfirm(); })
        }.width('100%')
      }.width('100%').height('85%').backgroundColor(SURFACE).borderRadius({ topLeft: 20, topRight: 20 })
        .padding({ left: 20, right: 20, top: 12, bottom: 24 + this.bottomPadding })
        .transition(TransitionEffect.translate({ y: this.reduceMotion || this.appBackgrounded ? 0 : 160 })
          .animation({ duration: this.appBackgrounded ? 0 : MOTION_OVERLAY, curve: Curve.EaseOut }))
    }.width('100%').height('100%')
      .transition(TransitionEffect.OPACITY
        .animation({ duration: this.appBackgrounded ? 0 : MOTION_OVERLAY, curve: Curve.EaseOut }))
  }
}
