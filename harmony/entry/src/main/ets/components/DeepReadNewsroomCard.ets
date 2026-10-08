import type { DeepReadActiveRun, DeepReadGenerationPhase } from '@amber/deepread-domain';
import { BG, SURFACE, SURFACE2, INK, INK2, INK3, LINE, ACCENT, BODY, META } from '../design/tokens.ets';
import { FONT_SERIF } from '../design/fonts.ets';
import { DeepReadDesign as D } from '../design/DeepReadDesign.ets';
import { getProductKind } from '../platform_impl/ProductIdentity.ets';

export const deepReadNewsroomStageIndex = (stage?: DeepReadGenerationPhase): number => {
  if (stage === 'COLLECTING') return 0;
  if (stage === 'PLANNING') return 1;
  if (stage === 'WRITING') return 2;
  if (stage === 'VERIFYING' || stage === 'COMPLETE') return 3;
  return -1;
};

export const deepReadNewsroomLabel = (run: DeepReadActiveRun): string => {
  if ((run.label ?? '').trim().length > 0) return run.label as string;
  if (run.stage === 'COLLECTING') return '正在收集资料';
  if (run.stage === 'PLANNING') return '正在规划文章结构';
  if (run.stage === 'WRITING') return '正在撰写文章';
  if (run.stage === 'VERIFYING') return '正在核对文章';
  if (run.stage === 'COMPLETE') return '文章已保存，正在结束任务';
  return '任务已开始，等待阶段更新';
};

@Component
export struct DeepReadNewsroomCard {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @Prop run: DeepReadActiveRun | null = null;
  onOpen: () => void = (): void => {};
  private readonly stageNames: string[] = ['采访', '整理', '撰稿', '付印'];

  build(): void {
    if (this.run !== null && getProductKind() === 'deepread') {
      Column({ space: 10 }) {
        Row({ space: 6 }) {
          Circle({ width: 7, height: 7 }).fill(D.warn)
          Text('生成中').fontSize(12).fontWeight(FontWeight.Medium).fontColor(D.warn)
        }.padding({ left: 9, right: 9, top: 4, bottom: 4 }).borderRadius(999)
          .backgroundColor(D.card)
        Text(this.run.title).fontSize(20).fontFamily(D.serif).fontWeight(FontWeight.Medium)
          .fontColor(D.ink).lineHeight(28).width('100%')
        Text(deepReadNewsroomLabel(this.run)).fontSize(12).fontColor(D.muted).lineHeight(19).width('100%')
        Text('可以离开此页面，稍后在阅读库查看进度。').fontSize(13).fontColor(D.muted).lineHeight(20).width('100%')
      }.width('100%').padding(16).alignItems(HorizontalAlign.Start)
        .backgroundColor(D.card).borderRadius(18).border({ width: 0.6, color: D.rule })
        .constraintSize({ minHeight: 44 }).onClick((): void => { this.onOpen(); })
        .accessibilityText('生成中。' + this.run.title + '。' + deepReadNewsroomLabel(this.run))
        .opacity(this.themeEpoch >= 0 ? 1 : 1)
    } else if (this.run !== null) {
      Column({ space: 10 }) {
        Text('编辑室 · 正在制作').fontSize(META).fontColor(INK3).width('100%')
        Text(this.run.title).fontSize(BODY).fontFamily(FONT_SERIF).fontWeight(FontWeight.Medium)
          .fontColor(INK).width('100%')
        Row({ space: 6 }) {
          ForEach(this.stageNames, (name: string, index: number): void => {
            Text(name).fontSize(META)
              .fontColor(deepReadNewsroomStageIndex(this.run?.stage) === index ? ACCENT : INK3)
              .backgroundColor(deepReadNewsroomStageIndex(this.run?.stage) === index ? SURFACE2 : BG)
              .borderRadius(8).padding({ left: 4, right: 4, top: 6, bottom: 6 })
              .textAlign(TextAlign.Center).layoutWeight(1)
          }, (name: string): string => `${name}:${this.run?.stage ?? 'IDLE'}`)
        }.width('100%')
        Text(deepReadNewsroomLabel(this.run)).fontSize(BODY).fontColor(INK2).width('100%')
        Text('可以离开此页面，稍后在阅读库查看进度。').fontSize(META).fontColor(INK3).width('100%')
        Text('查看进度').fontSize(BODY).fontColor(ACCENT).textAlign(TextAlign.Center)
          .width('100%').constraintSize({ minHeight: 44 }).backgroundColor(SURFACE2).borderRadius(10)
          .onClick((): void => { this.onOpen(); })
      }.width('100%').padding(16).alignItems(HorizontalAlign.Start)
        .backgroundColor(SURFACE).borderRadius(16).border({ width: 0.5, color: LINE })
        .opacity(this.themeEpoch >= 0 ? 1 : 1)
    }
  }
}
