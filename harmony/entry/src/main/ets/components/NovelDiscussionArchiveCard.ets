import { ACCENT, INK, INK3, SURFACE2 } from '../design/tokens.ets';
import type { NovelDiscussionArchive, NovelConfirmedDecision } from '@amber/deepread-domain';

@Component
export struct NovelDiscussionArchiveCard {
  @Prop archive: NovelDiscussionArchive;
  @Prop expanded: boolean = false;
  onToggle: (archiveId: string) => void = (_archiveId: string): void => {};

  build(): void {
    Column({ space: 8 }) {
      Text(`讨论归档 · ${this.archive.sourceMessageIds.length} 条原文`).fontSize(14).fontColor(INK).width('100%')
      Text(this.archive.summary).fontSize(13).fontColor(INK3).lineHeight(20).width('100%')
      ForEach(this.archive.decisions, (decision: NovelConfirmedDecision): void => {
        Text(`已确认决定：${decision.content}`).fontSize(12).fontColor(INK3).lineHeight(18).width('100%')
      }, (decision: NovelConfirmedDecision): string => decision.id)
      Text(this.expanded ? '收起归档原文' : '展开归档原文').fontSize(14).fontColor(ACCENT)
        .constraintSize({ minHeight: 44 }).onClick((): void => { this.onToggle(this.archive.id); })
    }.width('100%').alignItems(HorizontalAlign.Start).padding(14).backgroundColor(SURFACE2).borderRadius(14)
  }
}
