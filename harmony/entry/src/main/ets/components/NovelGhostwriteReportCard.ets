import { INK, INK3, ERROR, SURFACE2 } from '../design/tokens.ets';
import type { NovelGhostwriteReport, NovelGhostwriteReportChapter } from '@amber/deepread-domain';

@Component
export struct NovelGhostwriteReportCard {
  @Prop report: NovelGhostwriteReport | null = null;
  private reportTitle(): string {
    const stage: string = this.report?.stage ?? '';
    if (stage === 'completed') return '最近代笔已完成';
    if (stage === 'cancelled') return '最近代笔已取消';
    if (stage === 'failed') return '最近代笔执行失败';
    return '最近代笔记录';
  }
  build(): void {
    if (this.report !== null) {
      Column({ space: 8 }) {
        Text(this.reportTitle())
          .fontSize(15).fontWeight(FontWeight.Medium).fontColor(INK).width('100%')
        Text(`已收录 ${this.report.chapters.length} / ${this.report.targetChapterCount} 章；已保存的正文与版本保留。`)
          .fontSize(12).fontColor(INK3).lineHeight(18).width('100%')
        ForEach(this.report.chapters, (chapter: NovelGhostwriteReportChapter): void => {
          Text(`第 ${chapter.ordinal} 章 · 已收录`).fontSize(12).fontColor(INK3).width('100%')
        }, (chapter: NovelGhostwriteReportChapter): string => chapter.receipt)
        if (this.report.failure !== null) {
          Text(this.report.failure).fontSize(12).fontColor(ERROR).lineHeight(18).width('100%')
        }
      }.width('100%').padding(14).alignItems(HorizontalAlign.Start).backgroundColor(SURFACE2).borderRadius(12)
    }
  }
}
