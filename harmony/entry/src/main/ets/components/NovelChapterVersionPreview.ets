import { ACCENT, ACCENT_INK, ERROR, INK, INK3, INK4, MOTION_OVERLAY, OVERLAY, SURFACE, SURFACE2 } from '../design/tokens.ets';
import { MarkdownText } from './MarkdownText.ets';

@Component
export struct NovelChapterVersionPreview {
  @StorageProp('reduceMotion') private reduceMotion: boolean = false;
  @StorageProp('appBackgrounded') private appBackgrounded: boolean = false;
  @Prop title: string = '';
  @Prop content: string = '';
  @Prop metadata: string = '';
  @Prop busy: boolean = false;
  @Prop errorMessage: string = '';
  @Prop bottomPadding: number = 24;
  @Prop forkName: string = '';
  @Prop forkAvailable: boolean = false;
  @Prop forkDetail: string = '';
  @Prop busyLabel: string = '恢复中…';
  onBack: () => void = (): void => {};
  onClose: () => void = (): void => {};
  onRestore: () => void = (): void => {};
  onForkName: (name: string) => void = (_name: string): void => {};
  onFork: () => void = (): void => {};

  build(): void {
    Stack({ alignContent: Alignment.Bottom }) {
      Row() {}.width('100%').height('100%').backgroundColor(OVERLAY)
        .onClick((): void => { if (!this.busy) this.onClose(); })
      Column({ space: 12 }) {
        Row() {
          Text('历史版本预览').fontSize(17).fontWeight(FontWeight.Bold).fontColor(INK).layoutWeight(1)
          Text('关闭').fontSize(13).fontColor(this.busy ? INK4 : INK3)
            .width(44).height(44).textAlign(TextAlign.Center).enabled(!this.busy)
            .onClick((): void => { this.onClose(); })
        }.width('100%')
        Text(this.metadata).fontSize(12).fontColor(INK3).lineHeight(18).width('100%')
        Scroll() {
          Column({ space: 16 }) {
            Text(this.title.length > 0 ? this.title : '未命名章节')
              .fontSize(19).fontWeight(FontWeight.Bold).fontColor(INK).width('100%')
            if (this.content.length > 0) {
              MarkdownText({ content: this.content, fontColor: INK, readingMode: true,
                serifForReading: false, enableLatexRendering: false, codeBlockAutoCollapse: true })
            } else {
              Text('该版本没有正文。').fontSize(14).fontColor(INK3).width('100%')
            }
            Text(this.forkDetail).fontSize(12).fontColor(INK3).lineHeight(18).width('100%')
            if (this.forkAvailable) {
              TextInput({ text: this.forkName, placeholder: '从历史版本创建的新分支名称' })
                .width('100%').height(44).fontSize(14).fontColor(INK).placeholderColor(INK3)
                .backgroundColor(SURFACE2).enabled(!this.busy)
                .onChange((name: string): void => { this.onForkName(name); })
              Text('从历史版本创建分支').fontSize(14).fontColor(ACCENT).width('100%').height(44)
                .enabled(!this.busy && this.forkName.trim().length > 0)
                .opacity(!this.busy && this.forkName.trim().length > 0 ? 1 : 0.45)
                .onClick((): void => { this.onFork(); })
            }
          }.width('100%').alignItems(HorizontalAlign.Start).padding(12)
        }.width('100%').layoutWeight(1).align(Alignment.TopStart)
          .scrollBar(BarState.Auto).backgroundColor(SURFACE2).borderRadius(10)
        if (this.errorMessage.length > 0) {
          Scroll() {
            Text(this.errorMessage).fontSize(12).fontColor(ERROR).lineHeight(18)
              .width('100%').wordBreak(WordBreak.BREAK_ALL)
          }.width('100%').constraintSize({ maxHeight: 72 }).align(Alignment.TopStart)
        }
        Row({ space: 12 }) {
          Text('返回历史').fontSize(14).fontColor(this.busy ? INK4 : INK3)
            .layoutWeight(1).height(44).textAlign(TextAlign.Center)
            .backgroundColor(SURFACE2).borderRadius(10).enabled(!this.busy)
            .onClick((): void => { this.onBack(); })
          Text(this.busy ? this.busyLabel : '恢复此版本').fontSize(14).fontColor(this.busy ? INK4 : ACCENT_INK)
            .layoutWeight(1).height(44).textAlign(TextAlign.Center)
            .backgroundColor(ACCENT).borderRadius(10).enabled(!this.busy)
            .onClick((): void => { this.onRestore(); })
        }.width('100%')
      }.width('100%').height('85%').backgroundColor(SURFACE)
        .borderRadius({ topLeft: 20, topRight: 20 })
        .padding({ left: 18, right: 18, top: 12, bottom: 14 + this.bottomPadding })
        .onClick((): void => {})
        .transition(TransitionEffect.translate({ y: this.reduceMotion || this.appBackgrounded ? 0 : 160 })
          .animation({ duration: this.appBackgrounded ? 0 : MOTION_OVERLAY, curve: Curve.EaseOut }))
    }.width('100%').height('100%')
      .transition(TransitionEffect.OPACITY
        .animation({ duration: this.appBackgrounded ? 0 : MOTION_OVERLAY, curve: Curve.EaseOut }))
  }
}
