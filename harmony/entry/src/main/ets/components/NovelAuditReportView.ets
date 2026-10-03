import { ACCENT, ERROR, INK, INK3, SURFACE, SURFACE2 } from '../design/tokens.ets';
import { novelChapterOrdinal } from '@amber/deepread-domain';
import type { NovelChapter, NovelAuditReport, NovelAuditIssue, NovelAuditBlock, NovelAuditReference } from '@amber/deepread-domain';

@Component
export struct NovelAuditReportView {
  @Prop report: NovelAuditReport | null = null;
  @Prop chapters: NovelChapter[] = [];
  @Prop stale: boolean = false;
  @Prop repairBusy: boolean = false;
  onRepair: (issue: NovelAuditIssue) => void = (_issue: NovelAuditIssue): void => {};
  onCanonicalEvidence: (issue: NovelAuditIssue, reference: NovelAuditReference) => void = (_issue: NovelAuditIssue, _reference: NovelAuditReference): void => {};
  onEvidence: (issue: NovelAuditIssue) => void = (_issue: NovelAuditIssue): void => {};
  build(): void {
    if (this.report !== null) {
      Column({ space: 8 }) {
        if (this.stale) {
          Text('分支或正文已变化，下列报告来自旧稿。请重新审校。').fontSize(12).fontColor(ERROR).lineHeight(18).width('100%')
        }
        Text(this.coverageLabel()).fontSize(13).fontColor(INK).lineHeight(19).width('100%')
        Text('审校覆盖本块、邻接正文与作者事实；覆盖完整也不代表已穷尽远距离跨章矛盾。')
          .fontSize(11).fontColor(INK3).lineHeight(17).width('100%')
        if (this.report.invalidEvidenceCount > 0) {
          Text(`${this.report.invalidEvidenceCount} 条无法回查原文的模型发现未列为问题。`)
            .fontSize(12).fontColor(INK3).lineHeight(18).width('100%')
        }
        ForEach(this.report.blocks, (block: NovelAuditBlock): void => {
          if (block.status !== 'checked') {
            Text(`${block.status === 'failed' ? '检查失败' : '尚未检查'} · ${this.blockChapterLabel(block)} · 正文位置 ${block.start + 1}–${block.end}${block.error === null ? '' : `：${block.error}`}`)
              .fontSize(12).fontColor(ERROR).lineHeight(18).width('100%')
          }
        }, (block: NovelAuditBlock): string => `${block.chapterId}:${block.start}`)
        ForEach(this.report.issues, (issue: NovelAuditIssue): void => {
          Column({ space: 6 }) {
            Text(`${this.severityLabel(issue.severity)} · ${issue.chapterRef}`).fontSize(12)
              .fontColor(issue.severity === 'blocker' ? ERROR : INK3).width('100%')
            Text(issue.summary).fontSize(13).fontColor(INK).lineHeight(19).width('100%')
            if (issue.quote.length > 0) {
              Text(`原文：${issue.quote}`).fontSize(12).fontColor(INK3).lineHeight(18).width('100%')
                .padding(10).backgroundColor(SURFACE2).borderRadius(8)
            }
            this.CanonicalEvidence(issue)
            if (issue.suggestion.length > 0) {
              Text(`建议：${issue.suggestion}`).fontSize(12).fontColor(INK3).lineHeight(18).width('100%')
            }
            Text(this.repairBusy ? '正在生成修复建议…' : '生成修复建议').fontSize(13).fontColor(ACCENT).constraintSize({ minHeight: 44 })
              .enabled(!this.stale && !this.repairBusy).opacity(this.stale || this.repairBusy ? 0.45 : 1)
              .onClick((): void => { this.onRepair(issue); })
            Text('查看原文').fontSize(13).fontColor(ACCENT).constraintSize({ minHeight: 44 })
                .onClick((): void => { this.onEvidence(issue); })
          }.width('100%').alignItems(HorizontalAlign.Start).padding(12).backgroundColor(SURFACE).borderRadius(10)
        }, (issue: NovelAuditIssue): string => `${issue.chapterId}:${issue.start}:${issue.summary}`)
      }.width('100%').alignItems(HorizontalAlign.Start)
    }
  }
  @Builder
  private CanonicalEvidence(issue: NovelAuditIssue): void {
    ForEach(issue.canonicalReferences ?? [], (reference: NovelAuditReference): void => {
      Text(`应保留的先文事实 · 第 ${reference.chapterOrdinal} 章 · ${reference.chapterTitle}`)
        .fontSize(12).fontColor(INK).lineHeight(18).width('100%')
      Text(reference.quote).fontSize(12).fontColor(INK3).lineHeight(18).width('100%')
        .padding(10).backgroundColor(SURFACE2).borderRadius(8)
      Text('查看先文事实').fontSize(13).fontColor(ACCENT).constraintSize({ minHeight: 44 })
        .onClick((): void => { this.onCanonicalEvidence(issue, reference); })
    }, (reference: NovelAuditReference): string => `${reference.chapterId}:${reference.start}:${reference.end}`)
  }
  private blockChapterLabel(block: NovelAuditBlock): string {
    const chapter: NovelChapter | undefined = this.chapters.find((item: NovelChapter): boolean => item.id === block.chapterId);
    return chapter === undefined ? '已变更章节' : `第 ${novelChapterOrdinal(chapter, this.chapters.indexOf(chapter) + 1)} 章 · ${chapter.title}`;
  }
  private severityLabel(severity: string): string {
    return severity === 'blocker' ? '严重' : severity === 'major' ? '主要' : '提示';
  }
  private coverageLabel(): string {
    if (this.report === null) return '';
    const total: number = this.report.coverage.totalChars;
    const checked: number = this.report.coverage.checkedChars;
    const complete: boolean = this.report.coverage.complete;
    return `${this.report.cancelled ? '已取消 · ' : ''}${complete ? '全部正文已检查' : '部分正文已检查'} ${checked} / ${total} 字符 · ${this.report.issues.length} 条问题`;
  }
}
