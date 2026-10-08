import type { DeepReadOutput } from '@amber/deepread-domain';
import { isComplete } from '@amber/deepread-domain';
import { INK, INK2, INK3, ACCENT, ERROR, SURFACE2, META, BODY2 } from '../../design/tokens.ets';

@Component
export struct DeepReadArticleHostRecovery {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @Prop output: DeepReadOutput | null = null;
  @Prop running: boolean = false;
  @Prop cancelled: boolean = false;
  @Prop awaitingContinue: boolean = false;
  @Prop workspaceError: string = '';
  @Prop workspaceSaving: boolean = false;
  @Prop webError: string = '';
  @Prop continuationNotice: string = '';
  @Prop error: string = '';
  @Prop failedSection: boolean = false;
  @Prop draftNotice: string = '';
  @Prop currentStage: string = '';
  @Prop historyExpired: boolean = false;
  @Prop progressLabel: string = '';
  onSave: () => void = (): void => {};
  onNative: () => void = (): void => {};
  onCancel: () => void = (): void => {};
  onContinue: () => void = (): void => {};
  onRetry: () => void = (): void => {};
  onRegenerate: () => void = (): void => {};

  build(): void {
    Column() {
      if (this.workspaceError.length > 0 && !this.running) {
        Row() {
          Text(this.workspaceError).fontSize(META).fontColor(ERROR).layoutWeight(1)
            .maxLines(3).textOverflow({ overflow: TextOverflow.Ellipsis })
          Text(this.workspaceSaving ? '保存中' : '重试保存').fontSize(META).fontColor(INK)
            .constraintSize({ minHeight: 44 }).padding({ left: 12, right: 12 }).onClick((): void => { this.onSave(); })
        }.width('100%').alignItems(VerticalAlign.Center)
      }
      if (this.webError.length > 0) {
        Row() {
          Text(this.webError).fontSize(META).fontColor(ERROR).layoutWeight(1)
            .maxLines(3).textOverflow({ overflow: TextOverflow.Ellipsis })
          Text('原生阅读').fontSize(META).fontColor(INK).constraintSize({ minHeight: 44 })
            .padding({ left: 12, right: 12 }).onClick((): void => { this.onNative(); })
        }.width('100%').alignItems(VerticalAlign.Center)
      }
      if (this.running) {
        Row() {
          LoadingProgress().width(24).height(24).color(ACCENT).margin({ right: 10 })
          Text(this.output !== null && isComplete(this.output) ? '重新生成中，原文章已保留'
            : this.output?.generationPhase === 'IDLE' ? '准备中' : this.progressLabel)
            .fontSize(META).fontColor(INK2).layoutWeight(1).maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
          Text('取消生成').fontSize(META).fontColor(ERROR).constraintSize({ minHeight: 44 })
            .padding({ left: 12, right: 12 }).onClick((): void => { this.onCancel(); })
        }.width('100%').alignItems(VerticalAlign.Center)
      } else {
        if (this.awaitingContinue || (this.cancelled && this.output !== null && !isComplete(this.output))) {
          Text(this.continuationNotice).fontSize(META).fontColor(INK3).width('100%').margin({ bottom: 4 })
          Row() {
            Text('继续生成').fontSize(BODY2).fontColor(INK).constraintSize({ minHeight: 44 })
              .padding({ left: 12, right: 12 }).onClick((): void => { this.onContinue(); })
            Blank()
          }.width('100%')
        }
        if (this.error.length > 0 && !this.cancelled) {
          Row() {
            Text(this.error).fontSize(META).fontColor(ERROR).layoutWeight(1)
              .maxLines(4).textOverflow({ overflow: TextOverflow.Ellipsis })
            Text(this.failedSection ? '重试此节' : '重试').fontSize(META).fontColor(INK)
              .constraintSize({ minHeight: 44 }).padding({ left: 12, right: 12 }).onClick((): void => { this.onRetry(); })
          }.width('100%').alignItems(VerticalAlign.Center)
        } else if (this.draftNotice.length > 0) {
          Row() {
            Text(this.draftNotice).fontSize(META).fontColor(INK3).layoutWeight(1)
            Text('继续生成').fontSize(META).fontColor(INK).constraintSize({ minHeight: 44 })
              .padding({ left: 12, right: 12 }).onClick((): void => { this.onContinue(); })
          }.width('100%')
        }
        if (this.output !== null) {
          Row() {
            Text(this.historyExpired ? '内容已过 24 小时' : this.currentStage).fontSize(META).fontColor(INK3).layoutWeight(1)
            if (isComplete(this.output) && this.workspaceError.length === 0) {
              Text(this.workspaceSaving ? '保存中' : '保存到工作区').fontSize(META).fontColor(INK)
                .constraintSize({ minHeight: 44 }).padding({ left: 10, right: 10 }).onClick((): void => { this.onSave(); })
            }
            Text('重新生成').fontSize(META).fontColor(INK).constraintSize({ minHeight: 44 })
              .padding({ left: 12, right: 12 }).onClick((): void => { this.onRegenerate(); })
          }.width('100%').alignItems(VerticalAlign.Center)
        }
      }
    }.width('100%').alignItems(HorizontalAlign.Start)
      .padding({ left: 20, right: 12, top: 4, bottom: 4 }).backgroundColor(SURFACE2)
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
  }
}
