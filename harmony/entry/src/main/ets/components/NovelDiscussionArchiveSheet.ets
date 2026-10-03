// 讨论归档确认：来源范围由宿主冻结；这里只编辑摘要并确认作者选定的决定。
import {
  ACCENT, ACCENT_INK, ERROR, INK, INK2, INK3, INK4, LINE,
  MOTION_OVERLAY, OVERLAY, SURFACE, SURFACE2,
} from '../design/tokens.ets';

@Component
export struct NovelDiscussionArchiveSheet {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @Prop visible: boolean = false;
  @Prop @Watch('resetDraft') draftKey: string = '';
  @Prop sourceMessageCount: number = 0;
  @Prop sourceRangeLabel: string = '';
  @Prop initialSummary: string = '';
  @Prop decisions: string[] = [];
  @Prop busy: boolean = false;
  @Prop errorMessage: string = '';
  @Prop bottomPadding: number = 0;

  onConfirm: (summary: string, selectedDecisionIndexes: number[]) => void =
    (_summary: string, _selectedDecisionIndexes: number[]): void => {};
  onCancel: () => void = (): void => {};

  @State private summary: string = '';
  @State private selectedDecisionIndexes: number[] = [];
  @State private committingInput: boolean = false;
  private summaryController: TextAreaController = new TextAreaController();
  private alive: boolean = false;

  aboutToAppear(): void {
    this.alive = true;
    this.resetDraft();
  }

  aboutToDisappear(): void { this.alive = false; }

  private resetDraft(): void {
    this.summary = this.initialSummary;
    this.selectedDecisionIndexes = [];
    this.committingInput = false;
  }

  build(): void {
    if (this.themeEpoch >= 0 && this.visible) {
      Stack() {
        Row() {}
          .width('100%').height('100%').backgroundColor(OVERLAY)
          .onClick((): void => { this.cancel(); })
        Column() {
          this.header()
          Scroll() {
            Column() {
              this.sourceSection()
              this.summarySection()
              this.decisionsSection()
              if (this.errorMessage.length > 0) {
                Text(this.errorMessage).fontSize(12).fontColor(ERROR).lineHeight(18)
                  .width('100%').wordBreak(WordBreak.BREAK_ALL).margin({ top: 12 })
              }
            }
            .width('100%').alignItems(HorizontalAlign.Start)
          }
          .width('100%').layoutWeight(1).align(Alignment.TopStart)
          this.actions()
        }
        .width('100%').height('88%').alignItems(HorizontalAlign.Start)
        .backgroundColor(SURFACE).borderRadius({ topLeft: 20, topRight: 20 })
        .padding({ left: 20, right: 20, top: 18, bottom: 24 + this.bottomPadding })
        .transition(TransitionEffect.translate({ y: 160 })
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      }
      .width('100%').height('100%').alignContent(Alignment.Bottom)
      .transition(TransitionEffect.OPACITY
        .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
    }
  }

  @Builder
  private header(): void {
    Row() {
      Text('确认讨论归档').fontSize(18).fontWeight(FontWeight.Bold).fontColor(INK).layoutWeight(1)
      Text('取消').fontSize(13).fontColor(this.blocked() ? INK4 : INK3)
        .width(44).height(44).textAlign(TextAlign.Center)
        .onClick((): void => { this.cancel(); }).accessibilityText('取消讨论归档')
    }
    .width('100%').alignItems(VerticalAlign.Center).margin({ bottom: 10 })
  }

  @Builder
  private sourceSection(): void {
    Column() {
      Text(`本次归档 ${this.sourceMessageCount} 条讨论消息`)
        .fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK)
      if (this.sourceRangeLabel.length > 0) {
        Text(this.sourceRangeLabel).fontSize(12).fontColor(INK2).lineHeight(18).margin({ top: 4 })
      }
      Text('原文会保留在创作记录中。确认后，下一轮会使用归档摘要代替这段原文；只有勾选的决定才会成为创作约束。')
        .fontSize(12).fontColor(INK3).lineHeight(18).margin({ top: 6 })
    }
    .width('100%').alignItems(HorizontalAlign.Start).margin({ bottom: 16 })
  }

  @Builder
  private summarySection(): void {
    Column() {
      Row() {
        Text('归档摘要').fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK).layoutWeight(1)
        Text(`${this.summary.trim().length}/2000`).fontSize(11)
          .fontColor(this.summary.trim().length > 2000 ? ERROR : INK3).margin({ left: 8 })
      }
      .width('100%').margin({ bottom: 8 })
      TextArea({ text: this.summary, placeholder: '编辑讨论要点与后续创作需要保留的信息',
        controller: this.summaryController })
        .width('100%').height(180).fontSize(14).fontColor(INK).placeholderColor(INK4)
        .backgroundColor(SURFACE2).borderRadius(10).padding(12).enabled(!this.blocked())
        .onChange((value: string): void => { this.summary = value; })
        .accessibilityText('可编辑的归档摘要')
    }
    .width('100%').alignItems(HorizontalAlign.Start).margin({ bottom: 16 })
  }

  @Builder
  private decisionsSection(): void {
    Column() {
      Text('确认的创作决定').fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK)
      Text('请逐项核对；默认不勾选。未勾选的内容只作为讨论背景保留。')
        .fontSize(12).fontColor(INK3).lineHeight(18).margin({ top: 4, bottom: 6 })
      if (this.decisions.length === 0) {
        Text('这段讨论没有提出需要确认的决定。').fontSize(12).fontColor(INK3).lineHeight(18)
          .width('100%').padding({ top: 8, bottom: 8 })
      } else {
        ForEach(this.decisions, (decision: string, index: number): void => {
          Row() {
            Checkbox().select(this.isSelected(index)).selectedColor(ACCENT)
              .width(24).height(24).margin({ left: 0, right: 12 }).enabled(!this.blocked())
              .onChange((checked: boolean): void => { this.selectDecision(index, checked); })
              .accessibilityText(`确认创作决定 ${index + 1}：${decision}`)
            Text(decision).fontSize(13).fontColor(INK2).lineHeight(20).layoutWeight(1)
              .constraintSize({ minHeight: 44 })
              .onClick((): void => { this.selectDecision(index, !this.isSelected(index)); })
          }
          .width('100%').alignItems(VerticalAlign.Center).padding({ top: 6, bottom: 6 })
          .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
        }, (_decision: string, index: number): string => `${this.draftKey}-${index}`)
      }
    }
    .width('100%').alignItems(HorizontalAlign.Start)
  }

  @Builder
  private actions(): void {
    Row({ space: 12 }) {
      Text('取消').fontSize(14).fontColor(this.blocked() ? INK4 : INK2)
        .layoutWeight(1).height(44).textAlign(TextAlign.Center)
        .backgroundColor(SURFACE2).borderRadius(12).border({ width: 1, color: LINE })
        .onClick((): void => { this.cancel(); })
      Text(this.busy ? '正在归档…' : '确认归档').fontSize(14).fontWeight(FontWeight.Medium)
        .fontColor(this.canConfirm() ? ACCENT_INK : INK4).layoutWeight(1).height(44)
        .textAlign(TextAlign.Center).backgroundColor(this.canConfirm() ? ACCENT : SURFACE2)
        .borderRadius(12).border({ width: 1, color: this.canConfirm() ? ACCENT : LINE })
        .onClick((): void => { this.confirm(); }).accessibilityText('确认摘要与选中的创作决定并归档')
    }
    .width('100%').margin({ top: 14 })
  }

  private blocked(): boolean { return this.busy || this.committingInput; }

  private canConfirm(): boolean {
    const length: number = this.summary.trim().length;
    return !this.blocked() && this.sourceMessageCount > 0 && length > 0 && length <= 2000;
  }

  private isSelected(index: number): boolean { return this.selectedDecisionIndexes.indexOf(index) >= 0; }

  private selectDecision(index: number, checked: boolean): void {
    if (this.blocked()) return;
    if (checked && !this.isSelected(index)) {
      this.selectedDecisionIndexes = this.selectedDecisionIndexes.concat(index);
    } else if (!checked) {
      this.selectedDecisionIndexes = this.selectedDecisionIndexes.filter((value: number): boolean => value !== index);
    }
  }

  private cancel(): void {
    if (this.blocked()) return;
    this.summaryController.stopEditing();
    this.onCancel();
  }

  private confirm(): void {
    if (this.blocked() || this.sourceMessageCount <= 0) return;
    this.committingInput = true;
    this.summaryController.stopEditing();
    setTimeout((): void => {
      this.committingInput = false;
      if (!this.alive || !this.visible || !this.canConfirm()) return;
      this.onConfirm(this.summary.trim(), this.selectedDecisionIndexes.slice());
    }, 0);
  }
}
