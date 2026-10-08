import { ACCENT, INK, INK2, INK3, LINE } from '../../design/tokens.ets';
import { FONT_MONO, FONT_SERIF } from '../../design/fonts.ets';

// Four editorial milestones driven by the scheduler, never by a progress timer.
@Component
export struct StudioNewsroom {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @Prop title: string = '';
  @Prop stage: string = 'COLLECTING';
  @Prop detail: string = '收集来源';
  @Prop compact: boolean = false;
  @Prop active: boolean = true;
  @State private trackWidth: number = 0;
  private readonly steps: string[] = ['采访', '整理', '撰稿', '付印'];

  private stepIndex(): number {
    if (this.stage === 'PLANNING') return 1;
    if (this.stage === 'WRITING') return 2;
    if (this.stage === 'VERIFYING' || this.stage === 'COMPLETE') return 3;
    return 0;
  }

  build(): void {
    Column({ space: this.compact ? 8 : 14 }) {
      if (!this.compact) {
        Text('AMBER / NEWSROOM').fontFamily(FONT_MONO).fontSize(10).fontColor(INK3)
        Row().width('100%').height(2).backgroundColor(INK)
        Text(this.title).fontFamily(FONT_SERIF).fontSize(24).fontWeight(FontWeight.Medium)
          .lineHeight(34).fontColor(INK).maxLines(3).textOverflow({ overflow: TextOverflow.Ellipsis })
          .width('100%')
      }
      Column({ space: 8 }) {
        Row() {
          ForEach(this.steps, (word: string, index: number): void => {
            Text(`${index + 1}  ${word}`).fontFamily(FONT_MONO).fontSize(this.compact ? 11 : 12)
              .fontColor(index === this.stepIndex() ? ACCENT : index < this.stepIndex() ? INK2 : INK3)
              .fontWeight(index === this.stepIndex() ? FontWeight.Medium : FontWeight.Regular)
              .layoutWeight(1).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          }, (word: string): string => word)
        }.width('100%')
        Stack({ alignContent: Alignment.TopStart }) {
          Row().width('100%').height(1).backgroundColor(LINE)
          Row().width(this.trackWidth / 4).height(2).backgroundColor(ACCENT)
            .translate({ x: this.stepIndex() * this.trackWidth / 4 })
            .animation({ duration: this.active ? 260 : 0, curve: Curve.EaseOut })
        }.width('100%').height(2)
          .onAreaChange((_oldArea: Area, area: Area): void => { this.trackWidth = Math.max(0, Number(area.width)); })
      }.width('100%')
      ForEach([this.detail], (label: string): void => {
        Text(label).fontSize(this.compact ? 11 : 13).fontColor(INK2).lineHeight(this.compact ? 17 : 20)
          .width('100%').maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
          .transition(TransitionEffect.asymmetric(
            TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: 4 }))
              .animation({ duration: this.active ? 220 : 0, curve: Curve.EaseOut }),
            TransitionEffect.OPACITY.animation({ duration: 0 })))
      }, (label: string): string => label)
    }.width('100%').alignItems(HorizontalAlign.Start)
      .accessibilityText('编辑进度：' + this.steps[this.stepIndex()] + '，' + this.detail)
  }
}
