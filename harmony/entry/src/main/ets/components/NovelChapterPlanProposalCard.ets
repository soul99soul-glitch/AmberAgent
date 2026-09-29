// NovelChapterPlanProposalCard — 「本章计划提案」专用摘要(E13-A 冻结合同)
//
// 只展示 parseNovelChapterPlanProposal 投影出的完整提案:proposalId 与按序
//   全部 patch(写入=完整计划内容可展开读全;delete=「清空本章计划」)。
// 批准/拒绝不走本卡 — 原胶囊按钮仍调 ChatMessageTools 的 onApprove/onDeny
//   (同一 toolCallId/locator/continueTool 路由);本卡不含任何新写回路径。
// 容器走 NovelApprovalShell(iOS 审批卡规格)。

import { MOTION_OVERLAY, ACCENT, ERROR, INK, INK2, INK3, LINE } from '../design/tokens.ets';
import { FONT_SANS_MEDIUM, FONT_SANS_SEMIBOLD } from '../design/fonts.ets';
import { NovelApprovalShell } from './NovelApprovalShell.ets';
import type { NovelChapterPlanPatchView, NovelChapterPlanProposalView } from '../platform_impl/NovelPlanProposalProjection.ets';

@Component
export struct NovelChapterPlanProposalCard {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop view: NovelChapterPlanProposalView | null = null;
  @State private expanded: boolean = false;

  private writeCount(view: NovelChapterPlanProposalView): number {
    return view.patches.filter((patch: NovelChapterPlanPatchView): boolean => patch.operation === 'write').length;
  }

  private deleteCount(view: NovelChapterPlanProposalView): number {
    return view.patches.filter((patch: NovelChapterPlanPatchView): boolean => patch.operation === 'delete').length;
  }

  @Builder
  private proposalDetails(view: NovelChapterPlanProposalView): void {
    Column() {
      ForEach(view.patches, (patch: NovelChapterPlanPatchView, index: number): void => {
        Column() {
          Text(patch.operation === 'delete' ? `第 ${index + 1} 步 · 清空本章计划` : `第 ${index + 1} 步 · 写入计划`)
            .fontSize(11)
            .fontColor(patch.operation === 'delete' ? ERROR : INK3)
            .width('100%')
          if (patch.operation === 'write') {
            Text(patch.content ?? '').fontSize(12).fontColor(INK2).lineHeight(18)
              .width('100%').margin({ top: 4 })
          }
          Text(patch.path).fontSize(10).fontColor(INK3).width('100%').margin({ top: 4 })
        }
        .width('100%').alignItems(HorizontalAlign.Start)
        .padding({ top: 7, bottom: 7 })
        .border({ width: { top: 0.5 }, color: { top: LINE } }).margin({ top: 6 })
      }, (patch: NovelChapterPlanPatchView, index: number): string => `${index}:${patch.operation}`)
    }
    .width('100%')
    .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: -8 }))
      .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
  }

  build(): void {
    if (this.themeEpoch >= 0 && this.view !== null) {
      this.ShellFor(this.view)
    }
  }

  @Builder
  private ShellFor(view: NovelChapterPlanProposalView): void {
    Column() {
      NovelApprovalShell({
        iconName: 'ph_notebook',
        title: '本章计划提案',
        subtitle: `提案 ${view.proposalId} · ${this.writeCount(view)} 次写入 · ${this.deleteCount(view)} 次清空`,
        content: (): void => { this.Body(); },
      })
    }
    .width('100%').margin({ bottom: 6 })
  }

  @Builder
  private Body(): void {
    Column() {
      Row() {
        Blank()
        Text(this.expanded ? '收起' : '查看').fontSize(12).fontFamily(FONT_SANS_MEDIUM).fontColor(ACCENT)
          .constraintSize({ minHeight: 44 }).padding({ left: 12 })
          .accessibilityText(`${this.expanded ? '收起' : '查看'}本章计划提案`)
          .onClick((): void => {
            this.getUIContext().animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
              this.expanded = !this.expanded;
            });
          })
      }
      .width('100%')
      if (this.expanded && this.view !== null) {
        this.proposalDetails(this.view)
      }
    }
    .width('100%')
  }
}
