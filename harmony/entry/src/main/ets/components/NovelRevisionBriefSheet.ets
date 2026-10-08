import { ACCENT, ERROR, INK, INK3, LINE, MOTION_OVERLAY, OVERLAY, SURFACE, SURFACE2 } from '../design/tokens.ets';

// Reader 修订／重生成、暂停代笔修改要求共用输入；写入范围由调用方冻结并展示。
@Component
export struct NovelRevisionBriefSheet {
  @StorageProp('reduceMotion') private reduceMotion: boolean = false;
  @StorageProp('appBackgrounded') private appBackgrounded: boolean = false;
  @Prop @Watch('resetDraft') draftKey: string = '';
  @Prop @Watch('cancel') closeRequest: number = 0;
  @Prop initialText: string = '';
  @Prop sheetTitle: string = '修改要求';
  @Prop detail: string = '';
  @Prop placeholder: string = '说明你希望调整的内容';
  @Prop confirmLabel: string = '确认';
  @Prop allowEmpty: boolean = false;
  @Prop busy: boolean = false;
  @Prop errorMessage: string = '';
  @Prop bottomPadding: number = 0;
  @State private text: string = '';
  @State private committing: boolean = false;
  private alive: boolean = false;
  private inputToken: number = 0;
  private original: string = '';
  private controller: TextAreaController = new TextAreaController();
  onConfirm: (text: string) => void = (_text: string): void => {};
  onCancel: () => void = (): void => {};

  aboutToAppear(): void { this.alive = true; this.resetDraft(); }
  aboutToDisappear(): void { this.alive = false; this.inputToken++; }

  private resetDraft(): void {
    this.text = this.initialText;
    this.original = this.initialText;
    this.committing = false;
    this.inputToken++;
  }

  private cancel(): void {
    if (!this.alive || this.busy || this.committing) return;
    this.controller.stopEditing();
    const token: number = ++this.inputToken;
    setTimeout((): void => {
      if (!this.alive || token !== this.inputToken || this.busy || this.committing) return;
      if (this.text === this.original) { this.onCancel(); return; }
      AlertDialog.show({ title: '放弃修改要求？', message: '尚未提交的要求将丢失。',
        primaryButton: { value: '继续编辑', action: (): void => {} },
        secondaryButton: { value: '放弃', fontColor: ERROR, action: (): void => {
          if (this.alive && token === this.inputToken && !this.busy && !this.committing) this.onCancel();
        } } });
    }, 0);
  }

  private confirm(): void {
    if (!this.alive || this.busy || this.committing) return;
    this.committing = true;
    this.controller.stopEditing();
    const token: number = ++this.inputToken;
    setTimeout((): void => {
      if (!this.alive || token !== this.inputToken) return;
      this.committing = false;
      if (this.busy || (!this.allowEmpty && this.text.trim().length === 0)) return;
      this.onConfirm(this.text.trim());
    }, 0);
  }

  build(): void {
    Stack({ alignContent: Alignment.Bottom }) {
      Row() {}.width('100%').height('100%').backgroundColor(OVERLAY).onClick((): void => { this.cancel(); })
      Column({ space: 12 }) {
        Text(this.sheetTitle).fontSize(18).fontWeight(FontWeight.Medium).fontColor(INK).width('100%')
        Scroll() {
          Column({ space: 12 }) {
            Text(this.detail).fontSize(13).fontColor(INK3).lineHeight(20).width('100%')
            TextArea({ text: this.text, placeholder: this.placeholder, controller: this.controller })
              .width('100%').height(180).fontSize(14).fontColor(INK).placeholderColor(INK3)
              .backgroundColor(SURFACE2).borderRadius(12).padding(12).enabled(!this.busy && !this.committing)
              .onChange((value: string): void => { this.text = value; })
            if (this.errorMessage.length > 0) {
              Text(this.errorMessage).fontSize(13).fontColor(ERROR).lineHeight(18).width('100%')
            }
          }.width('100%').alignItems(HorizontalAlign.Start)
        }.width('100%').layoutWeight(1).align(Alignment.TopStart).scrollBar(BarState.Auto)
        Row({ space: 12 }) {
          Text('取消').fontSize(14).fontColor(INK3).height(44).layoutWeight(1).textAlign(TextAlign.Center)
            .enabled(!this.busy && !this.committing).onClick((): void => { this.cancel(); })
          Text(this.busy ? '提交中…' : this.confirmLabel).fontSize(14).fontColor(ACCENT).height(44)
            .layoutWeight(1).textAlign(TextAlign.Center)
            .enabled(!this.busy && !this.committing && (this.allowEmpty || this.text.trim().length > 0))
            .opacity(this.allowEmpty || this.text.trim().length > 0 ? 1 : 0.45)
            .onClick((): void => { this.confirm(); })
        }.width('100%').border({ width: { top: 1 }, color: { top: LINE } })
      }.width('100%').height('80%').alignItems(HorizontalAlign.Start).backgroundColor(SURFACE)
        .borderRadius({ topLeft: 20, topRight: 20 }).padding({ left: 20, right: 20, top: 18, bottom: this.bottomPadding + 16 })
        .transition(TransitionEffect.translate({ y: this.reduceMotion || this.appBackgrounded ? 0 : 160 })
          .animation({ duration: this.appBackgrounded ? 0 : MOTION_OVERLAY, curve: Curve.EaseOut }))
    }.width('100%').height('100%')
      .transition(TransitionEffect.OPACITY
        .animation({ duration: this.appBackgrounded ? 0 : MOTION_OVERLAY, curve: Curve.EaseOut }))
  }
}
