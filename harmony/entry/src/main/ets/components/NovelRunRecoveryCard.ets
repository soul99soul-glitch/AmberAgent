import { ACCENT, ERROR, INK, INK3, LINE, SURFACE, SURFACE2 } from '../design/tokens.ets';

// 普通生成的持久恢复记录：读取与精确重试由 Workspace／creation 负责。
@Component
export struct NovelRunRecoveryCard {
  @Prop @Watch('resetRecord') recordKey: string = '';
  @Prop heading: string = '上次生成已中断';
  @Prop detail: string = '';
  @Prop userText: string = '';
  @Prop partialText: string = '';
  @Prop busy: boolean = false;
  @Prop retryAllowed: boolean = false;
  @Prop resumeAllowed: boolean = false;
  @Prop blockedReason: string = '';
  @Prop errorMessage: string = '';
  @Prop retryLabel: string = '按原请求重试';
  @State private expanded: boolean = false;
  private alive: boolean = false;
  private actionToken: number = 0;
  onRetry: (recordKey: string) => void = (_key: string): void => {};
  onResume: (recordKey: string) => void = (_key: string): void => {};
  onCopyPartial: (recordKey: string, text: string) => void = (_key: string, _text: string): void => {};

  aboutToAppear(): void { this.alive = true; this.resetRecord(); }
  aboutToDisappear(): void { this.alive = false; this.actionToken++; }

  private resetRecord(): void { this.expanded = false; this.actionToken++; }

  private copyPartial(): void {
    if (!this.alive || this.partialText.trim().length === 0) return;
    this.onCopyPartial(this.recordKey, this.partialText);
  }

  private confirmRetry(): void { this.confirmRecovery('retry'); }
  private confirmResume(): void { this.confirmRecovery('resume'); }

  private confirmRecovery(kind: string): void {
    const resume: boolean = kind === 'resume';
    if (!this.alive || this.busy || !(resume ? this.resumeAllowed : this.retryAllowed) || this.recordKey.length === 0) return;
    const token: number = ++this.actionToken;
    const key: string = this.recordKey;
    AlertDialog.show({ title: resume ? '继续已保存的响应？' : '按原请求重试？',
      message: `${this.detail}\n` + (resume
        ? '使用上次保存的响应继续生成，不重新发送全新请求。'
        : '将再次调用模型，按保存的要求和目标生成。') +
        '部分内容不会直接写入正文。',
      primaryButton: { value: '取消', action: (): void => {} },
      secondaryButton: { value: resume ? '继续响应' : '重试', action: (): void => {
        if (!this.alive || token !== this.actionToken || key !== this.recordKey || this.busy) return;
        if (resume) { if (this.resumeAllowed) this.onResume(key); }
        else if (this.retryAllowed) this.onRetry(key);
      } } });
  }

  build(): void {
    Column({ space: 10 }) {
      Text(this.heading).fontSize(15).fontWeight(FontWeight.Medium).fontColor(INK).width('100%')
      Text(this.detail).fontSize(12).fontColor(INK3).lineHeight(18).width('100%').wordBreak(WordBreak.BREAK_ALL)
      if (this.userText.length > 0) {
        Text('原请求').fontSize(12).fontColor(INK3).width('100%')
        Scroll() {
          Text(this.userText).fontSize(13).fontColor(INK).lineHeight(20).width('100%').wordBreak(WordBreak.BREAK_ALL)
        }.width('100%').constraintSize({ maxHeight: 100 }).align(Alignment.TopStart).scrollBar(BarState.Auto)
      }
      if (this.partialText.length > 0) {
        Text('已保存的部分内容').fontSize(12).fontColor(INK3).width('100%')
        if (this.expanded) {
          Scroll() {
            Text(this.partialText).fontSize(13).fontColor(INK).lineHeight(20).width('100%').wordBreak(WordBreak.BREAK_ALL)
          }.width('100%').height(180).align(Alignment.TopStart).scrollBar(BarState.Auto)
            .backgroundColor(SURFACE2).borderRadius(10).padding(10)
        } else {
          Text(this.partialText).fontSize(13).fontColor(INK).lineHeight(20).width('100%')
            .maxLines(4).textOverflow({ overflow: TextOverflow.Ellipsis }).wordBreak(WordBreak.BREAK_ALL)
        }
        Row({ space: 12 }) {
          Text(this.expanded ? '收起内容' : '查看完整内容').fontSize(13).fontColor(ACCENT).height(44)
            .layoutWeight(1).textAlign(TextAlign.Center).onClick((): void => { this.expanded = !this.expanded; })
          Text('复制部分内容').fontSize(13).fontColor(ACCENT).height(44).layoutWeight(1).textAlign(TextAlign.Center)
            .onClick((): void => { this.copyPartial(); })
        }.width('100%')
      } else {
        Text('本次尚无已保存的生成正文。').fontSize(12).fontColor(INK3).lineHeight(18).width('100%')
      }
      if (this.blockedReason.length > 0) {
        Text(this.blockedReason).fontSize(12).fontColor(INK3).lineHeight(18).width('100%')
      }
      if (this.errorMessage.length > 0) {
        Text(this.errorMessage).fontSize(12).fontColor(ERROR).lineHeight(18).width('100%').wordBreak(WordBreak.BREAK_ALL)
      }
      Row({ space: 12 }) {
        if (this.resumeAllowed) {
          Text(this.busy ? '处理中…' : '继续已保存的响应').fontSize(13).fontColor(ACCENT).height(44).layoutWeight(1)
            .textAlign(TextAlign.Center).enabled(!this.busy && this.resumeAllowed && this.recordKey.length > 0)
            .onClick((): void => { this.confirmResume(); })
        }
        Text(this.busy ? '处理中…' : this.retryLabel).fontSize(13).fontColor(ACCENT).height(44).layoutWeight(1)
          .textAlign(TextAlign.Center).enabled(!this.busy && this.retryAllowed && this.recordKey.length > 0)
          .opacity(this.retryAllowed ? 1 : 0.45).onClick((): void => { this.confirmRetry(); })
      }.width('100%')
    }.width('100%').alignItems(HorizontalAlign.Start).padding(16).backgroundColor(SURFACE)
      .border({ width: 1, color: LINE }).borderRadius(14)
  }
}
