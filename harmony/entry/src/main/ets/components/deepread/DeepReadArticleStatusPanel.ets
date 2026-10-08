import { DeepReadDesign as D } from '../../design/DeepReadDesign.ets';

@Component
export struct DeepReadArticleStatusPanel {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('reduceMotion') reduceMotion: boolean = false;
  @StorageProp('appBackgrounded') appBackgrounded: boolean = false;
  @Prop active: boolean = true;
  @Prop running: boolean = false;
  @Prop cancelled: boolean = false;
  @Prop awaitingContinue: boolean = false;
  @Prop statusLabel: string = '';
  @Prop error: string = '';
  @Prop workspaceError: string = '';
  @Prop webError: string = '';
  @Prop notice: string = '';
  @Prop templateMessage: string = '';
  @Prop saving: boolean = false;
  @Prop complete: boolean = false;
  @Prop readable: boolean = false;
  @Prop sourceFailureCount: number = 0;
  onCancel: () => void = (): void => {};
  onContinue: () => void = (): void => {};
  onRetry: () => void = (): void => {};
  onSave: () => void = (): void => {};
  onNative: () => void = (): void => {};

  private visible(): boolean {
    // Completed articles keep source diagnostics on the Sources page, without reserving reader space.
    return this.running || this.error.length > 0 || this.workspaceError.length > 0 || this.webError.length > 0
      || this.templateMessage.length > 0 || (!this.complete && (this.cancelled || this.awaitingContinue
        || this.notice.length > 0 || this.sourceFailureCount > 0));
  }

  private statusTransition(): TransitionEffect {
    const duration: number = this.active && !this.appBackgrounded ? 300 : 0;
    if (this.reduceMotion) return TransitionEffect.OPACITY.animation({ duration });
    return TransitionEffect.asymmetric(
      TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: 4 })),
      TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: -4 })))
      .animation({ duration, curve: Curve.EaseOut });
  }

  build(): void {
    if (this.visible() && this.themeEpoch >= 0) {
      Column() {
      Column({ space: 4 }) {
        Row({ space: 8 }) {
          if (this.running) { LoadingProgress().width(18).height(18).color(D.accent) }
          Stack() {
            ForEach([this.statusLabel], (label: string): void => {
              Text(label).fontSize(15).fontWeight(FontWeight.Medium).fontColor(D.ink)
                .lineHeight(22).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                .width('100%').transition(this.statusTransition())
            }, (label: string): string => label)
          }.layoutWeight(1).constraintSize({ minHeight: 22 }).clip(true)
          if (this.running) {
            Button('取消').fontSize(15).fontColor(D.accent).backgroundColor(Color.Transparent)
              .constraintSize({ minWidth: 44, minHeight: 44 }).padding({ left: 8, right: 8 })
              .onClick((): void => { this.onCancel(); })
          } else if (this.error.length > 0 || (!this.complete && this.sourceFailureCount > 0)) {
            Button(this.readable && !this.complete ? '补全章节' : '重试').fontSize(15).fontColor(D.accent)
              .backgroundColor(Color.Transparent).constraintSize({ minWidth: 44, minHeight: 44 })
              .padding({ left: 8, right: 8 }).onClick((): void => { this.onRetry(); })
          } else if (!this.complete && (this.awaitingContinue || this.cancelled || this.notice.length > 0)) {
            Button(this.readable ? '补全章节' : '继续生成').fontSize(15).fontColor(D.accent)
              .backgroundColor(Color.Transparent).constraintSize({ minHeight: 44 })
              .padding({ left: 8, right: 8 }).onClick((): void => { this.onContinue(); })
          }
        }.width('100%').constraintSize({ minHeight: 44 }).alignItems(VerticalAlign.Center)
        if (this.error.length > 0) {
          Text(this.error).fontSize(13).fontColor(D.danger).lineHeight(20).width('100%').copyOption(CopyOptions.InApp)
        }
        if (!this.complete && this.notice.length > 0) {
          Text(this.notice).fontSize(13).fontColor(D.muted).lineHeight(20).width('100%')
        }
        if (!this.running && !this.complete && this.sourceFailureCount > 0) {
          Text(`${this.sourceFailureCount} 个来源未取得资料。可在“来源”中查看原因。`)
            .fontSize(13).fontColor(D.muted).lineHeight(20).width('100%')
        }
        if (this.templateMessage.length > 0) {
          Text(this.templateMessage).fontSize(13).fontColor(D.muted).lineHeight(20).width('100%')
        }
        if (this.workspaceError.length > 0) {
          Row({ space: 8 }) {
            Text(this.workspaceError).fontSize(13).fontColor(D.danger).lineHeight(20).layoutWeight(1)
            Button(this.saving ? '保存中' : '重试保存').fontSize(13).fontColor(D.accent)
              .backgroundColor(Color.Transparent).constraintSize({ minHeight: 44 }).enabled(!this.saving && !this.running)
              .onClick((): void => { this.onSave(); })
          }.width('100%')
        }
        if (this.webError.length > 0) {
          Row({ space: 8 }) {
            Text(this.webError).fontSize(13).fontColor(D.danger).lineHeight(20).layoutWeight(1)
            Button('原生阅读').fontSize(13).fontColor(D.accent).backgroundColor(Color.Transparent)
              .constraintSize({ minHeight: 44 }).onClick((): void => { this.onNative(); })
          }.width('100%')
        }
      }.width('100%').constraintSize({ maxWidth: 760 }).alignItems(HorizontalAlign.Start)
        .padding({ left: 14, right: 6, bottom: this.running ? 0 : 8 })
        .backgroundColor(D.card).borderRadius(14).border({ width: 0.6, color: D.rule })
      }.width('100%').padding({ left: 16, right: 16, top: 4, bottom: 4 })
        .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: this.reduceMotion ? 0 : -16 }))
          .animation({ duration: this.active && !this.appBackgrounded ? 300 : 0, curve: Curve.EaseOut }))
    }
  }
}
