// NovelChapterVerdictCard — 章节裁决专用展示(E13-A 冻结合同)
//
// 只消费 durable job 里已校验的 GhostwriteReview/GhostwriteCandidate/FrozenPlan:
//   blocking → 阻断优先;非 blocking + rewriteRequired → 需重写;否则审稿通过。
// 「审稿通过」不声称已收录 — 收录只由 job stage/receipt 证明。
// 展开后全文可读(不再用 maxLines 冒充全文);不加强制通过/收录按钮或模型请求。
// 容器走 NovelApprovalShell(iOS 审批卡规格),裁决态 = 图标+色字 Label。

import { MOTION_OVERLAY,
  ACCENT, ERROR, INK, INK2, INK3, LINE,
} from '../design/tokens.ets';
import { FONT_SANS_MEDIUM } from '../design/fonts.ets';
import { NovelApprovalShell } from './NovelApprovalShell.ets';
import type {
  FrozenPlan, GhostwriteCandidate, GhostwriteFinding, GhostwriteReview,
} from '@amber/deepread-domain';

@Component
export struct NovelChapterVerdictCard {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop review: GhostwriteReview | null = null;
  @Prop candidate: GhostwriteCandidate | null = null;
  @Prop frozenPlan: FrozenPlan | null = null;
  @State private expanded: boolean = false;

  private verdictLabel(review: GhostwriteReview): string {
    if (review.blocking) return '存在阻断问题';
    if (review.rewriteRequired) return '需重写';
    return '审稿通过';
  }

  private verdictIcon(review: GhostwriteReview): string {
    if (review.blocking) return 'ban';
    if (review.rewriteRequired) return 'retry';
    return 'check';
  }

  private verdictTone(review: GhostwriteReview): string {
    return review.blocking || review.rewriteRequired ? 'error' : 'success';
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

  @Builder
  private reviewDetails(review: GhostwriteReview): void {
    Column() {
      if (!review.blocking && !review.rewriteRequired) {
        Text('审稿通过不代表已收录；收录以提交记录为准。')
          .fontSize(11).fontColor(INK3).lineHeight(16).width('100%').margin({ top: 8 })
      }
      Text(`候选 ${review.candidateId} · ${this.shortDigest(review.candidateDigest)}`)
        .fontSize(11).fontColor(INK3).width('100%').margin({ top: 8 })
      Text(`计划 ${review.planId} · ${this.shortDigest(review.planDigest)}`)
        .fontSize(11).fontColor(INK3).width('100%').margin({ top: 3 })
      if (review.rewriteInstructions.trim().length > 0) {
        Text('重写说明').fontSize(12).fontWeight(FontWeight.Medium).fontColor(INK)
          .width('100%').margin({ top: 10 })
        Text(review.rewriteInstructions).fontSize(12).fontColor(INK2).lineHeight(18)
          .width('100%').margin({ top: 4 })
      }
      ForEach(review.findings, (finding: GhostwriteFinding, index: number): void => {
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
      if (review.nextPlan !== null && review.nextPlan.trim().length > 0) {
        Text('下一版计划').fontSize(12).fontWeight(FontWeight.Medium).fontColor(INK)
          .width('100%').margin({ top: 10 })
        Text(review.nextPlan).fontSize(12).fontColor(INK2).lineHeight(18)
          .width('100%').margin({ top: 4 })
      }
      if (review.stateDelta.chapterHighlight.trim().length > 0) {
        Text(`状态增量:${review.stateDelta.chapterHighlight}`).fontSize(12).fontColor(INK2)
          .lineHeight(18).width('100%').margin({ top: 8 })
      }
      if (review.stateDelta.plotState.trim().length > 0) {
        Text(`情节状态:${review.stateDelta.plotState}`).fontSize(12).fontColor(INK2)
          .lineHeight(18).width('100%').margin({ top: 6 })
      }
    }
    .width('100%')
    .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: -8 }))
      .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
  }

  @Builder
  private reviewCard(review: GhostwriteReview, candidate: GhostwriteCandidate | null, frozenPlan: FrozenPlan | null): void {
    Column() {
      NovelApprovalShell({
        iconName: 'ph_gavel',
        title: candidate !== null ? `第 ${candidate.chapterOrdinal} 章 · 第 ${candidate.attempt + 1} 稿` : '章节裁决',
        subtitle: this.summaryLine(review, frozenPlan),
        statusLabel: this.verdictLabel(review),
        statusIcon: this.verdictIcon(review),
        statusTone: this.verdictTone(review),
        content: (): void => { this.Body(review); },
      })
    }
    .width('100%')
    // 挂在代笔卡内的分隔(壳自带描边,顶部分发丝线取消)
    .margin({ top: 12 })
  }

  @Builder
  private Body(review: GhostwriteReview): void {
    Column() {
      Row() {
        Blank()
        Text(this.expanded ? '收起' : '查看').fontSize(12).fontFamily(FONT_SANS_MEDIUM).fontColor(ACCENT)
          .constraintSize({ minHeight: 44 }).padding({ left: 12 })
          .accessibilityText(`${this.expanded ? '收起' : '查看'}章节裁决`)
          .onClick((): void => {
            this.getUIContext().animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
              this.expanded = !this.expanded;
            });
          })
      }
      .width('100%')
      if (this.expanded) {
        this.reviewDetails(review)
      }
    }
    .width('100%')
  }

  build(): void {
    if (this.themeEpoch >= 0 && this.review !== null) {
      this.reviewCard(this.review, this.candidate, this.frozenPlan)
    }
  }

  private summaryLine(review: GhostwriteReview, frozenPlan: FrozenPlan | null): string {
    const base: string = `${review.findings.length} 项发现`;
    if (frozenPlan !== null) return `${base} · 冻结计划 ${frozenPlan.planId}`;
    return base;
  }
}
