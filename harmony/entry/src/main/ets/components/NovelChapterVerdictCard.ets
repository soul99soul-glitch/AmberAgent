// NovelChapterVerdictCard — 章节裁决专用展示(E13-A 冻结合同)
//
// 只消费 durable job 里已校验的 GhostwriteReview/GhostwriteCandidate/FrozenPlan:
//   blocking → 阻断优先;非 blocking + rewriteRequired → 需重写;否则审稿通过。
// 「审稿通过」不声称已收录 — 收录只由 job stage/receipt 证明。
// 展开后全文可读(不再用 maxLines 冒充全文);不加强制通过/收录按钮或模型请求。

import {
  ACCENT, ACCENT_INK, ACCENT_LIGHT, ERROR, INK, INK2, INK3, LINE, RAISED, SURFACE,
} from '../design/tokens.ets';
import type {
  FrozenPlan, GhostwriteCandidate, GhostwriteFinding, GhostwriteReview,
} from '@amber/deepread-domain';

@Component
export struct NovelChapterVerdictCard {
  @Prop review: GhostwriteReview | null = null;
  @Prop candidate: GhostwriteCandidate | null = null;
  @Prop frozenPlan: FrozenPlan | null = null;
  @State private expanded: boolean = false;

  private verdictLabel(): string {
    if (this.review === null) return '';
    if (this.review.blocking) return '存在阻断问题';
    if (this.review.rewriteRequired) return '需重写';
    return '审稿通过';
  }

  private verdictColor(): string {
    if (this.review === null) return INK3;
    return this.review.blocking || this.review.rewriteRequired ? ERROR : ACCENT;
  }

  private findingLabel(finding: GhostwriteFinding): string {
    if (finding.kind === 'missing_required') return '缺失必写';
    if (finding.kind === 'forbidden_violation') return '违反禁写';
    if (finding.kind === 'hard_continuity') return '连续性硬伤';
    return '提示';
  }

  private shortDigest(digest: string): string {
    return digest.length <= 12 ? digest : `${digest.slice(0, 12)}…`;
  }

  build(): void {
    if (this.review !== null) {
      Column() {
        Row() {
          Text(this.verdictLabel())
            .fontSize(11)
            .fontColor(this.review.blocking || this.review.rewriteRequired ? ERROR : ACCENT_INK)
            .padding({ left: 8, right: 8, top: 3, bottom: 3 })
            .borderRadius(12)
            .backgroundColor(this.review.blocking || this.review.rewriteRequired ? SURFACE : ACCENT_LIGHT)
          if (this.candidate !== null) {
            Text(`第 ${this.candidate.chapterOrdinal} 章 · 第 ${this.candidate.attempt + 1} 稿`)
              .fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK).margin({ left: 10 })
          }
          Blank().layoutWeight(1)
          Text(this.expanded ? '收起' : '查看')
            .fontSize(12).fontColor(ACCENT).width(52).height(44)
            .textAlign(TextAlign.Center).padding({ top: 13, bottom: 13 })
            .accessibilityText(`${this.expanded ? '收起' : '查看'}章节裁决`)
            .onClick((): void => { this.expanded = !this.expanded; })
        }
        .width('100%').constraintSize({ minHeight: 44 }).alignItems(VerticalAlign.Center)

        Text(this.summaryLine())
          .fontSize(11).fontColor(INK3).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          .width('100%').margin({ top: 2 })

        if (this.expanded) {
          if (!this.review.blocking && !this.review.rewriteRequired) {
            Text('审稿通过不代表已收录；收录以提交记录为准。')
              .fontSize(11).fontColor(INK3).lineHeight(16).width('100%').margin({ top: 8 })
          }
          Text(`候选 ${this.review.candidateId} · ${this.shortDigest(this.review.candidateDigest)}`)
            .fontSize(11).fontColor(INK3).width('100%').margin({ top: 8 })
          Text(`计划 ${this.review.planId} · ${this.shortDigest(this.review.planDigest)}`)
            .fontSize(11).fontColor(INK3).width('100%').margin({ top: 3 })
          if (this.review.rewriteInstructions.trim().length > 0) {
            Text('重写说明').fontSize(12).fontWeight(FontWeight.Medium).fontColor(INK)
              .width('100%').margin({ top: 10 })
            Text(this.review.rewriteInstructions).fontSize(12).fontColor(INK2).lineHeight(18)
              .width('100%').margin({ top: 4 })
          }
          ForEach(this.review.findings, (finding: GhostwriteFinding, index: number): void => {
            Column() {
              Text(`${this.findingLabel(finding)} · ${finding.code}`)
                .fontSize(11).fontColor(INK3).width('100%')
              Text(finding.message).fontSize(12).fontColor(INK2).lineHeight(18)
                .width('100%').margin({ top: 2 })
              if (finding.location.trim().length > 0) {
                Text(`位置:${finding.location}`).fontSize(11).fontColor(INK3).lineHeight(16)
                  .width('100%').margin({ top: 2 })
              }
            }
            .width('100%').alignItems(HorizontalAlign.Start)
            .padding({ top: 7, bottom: 7 })
            .border({ width: { top: 0.5 }, color: { top: LINE } }).margin({ top: 8 })
          }, (finding: GhostwriteFinding, index: number): string => `${index}:${finding.kind}:${finding.code}`)
          if (this.review.nextPlan !== null && this.review.nextPlan.trim().length > 0) {
            Text('下一版计划').fontSize(12).fontWeight(FontWeight.Medium).fontColor(INK)
              .width('100%').margin({ top: 10 })
            Text(this.review.nextPlan).fontSize(12).fontColor(INK2).lineHeight(18)
              .width('100%').margin({ top: 4 })
          }
          if (this.review.stateDelta.chapterHighlight.trim().length > 0) {
            Text(`状态增量:${this.review.stateDelta.chapterHighlight}`).fontSize(12).fontColor(INK2)
              .lineHeight(18).width('100%').margin({ top: 8 })
          }
          if (this.review.stateDelta.plotState.trim().length > 0) {
            Text(`情节状态:${this.review.stateDelta.plotState}`).fontSize(12).fontColor(INK2)
              .lineHeight(18).width('100%').margin({ top: 6 })
          }
        }
      }
      .width('100%').padding({ top: 11, bottom: 3 })
      .border({ width: { top: 0.5 }, color: { top: LINE } }).margin({ top: 12 })
    }
  }

  private summaryLine(): string {
    if (this.review === null) return '';
    const base: string = `${this.review.findings.length} 项发现`;
    if (this.frozenPlan !== null) return `${base} · 冻结计划 ${this.frozenPlan.planId}`;
    return base;
  }
}
