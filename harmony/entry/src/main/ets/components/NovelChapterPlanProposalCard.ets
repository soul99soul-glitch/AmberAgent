// NovelChapterPlanProposalCard — 「本章计划提案」专用摘要(E13-A 冻结合同)
//
// 只展示 parseNovelChapterPlanProposal 投影出的完整提案:proposalId 与按序
//   全部 patch(写入=完整计划内容可展开读全;delete=「清空本章计划」)。
// 批准/拒绝不走本卡 — 原胶囊按钮仍调 ChatMessageTools 的 onApprove/onDeny
//   (同一 toolCallId/locator/continueTool 路由);本卡不含任何新写回路径。

import { ACCENT, ERROR, INK, INK2, INK3, LINE, SURFACE } from '../design/tokens.ets';
import type { NovelChapterPlanPatchView, NovelChapterPlanProposalView } from '../platform_impl/NovelPlanProposalProjection.ets';

@Component
export struct NovelChapterPlanProposalCard {
  @Prop view: NovelChapterPlanProposalView | null = null;
  @State private expanded: boolean = false;

  private writeCount(): number {
    if (this.view === null) return 0;
    return this.view.patches.filter((patch: NovelChapterPlanPatchView): boolean => patch.operation === 'write').length;
  }

  private deleteCount(): number {
    if (this.view === null) return 0;
    return this.view.patches.filter((patch: NovelChapterPlanPatchView): boolean => patch.operation === 'delete').length;
  }

  build(): void {
    if (this.view !== null) {
      Column() {
        Row() {
          Column() {
            Text('本章计划提案').fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK)
            Text(`提案 ${this.view.proposalId} · ${this.writeCount()} 次写入 · ${this.deleteCount()} 次清空`)
              .fontSize(11).fontColor(INK3).maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
              .width('100%').margin({ top: 2 })
          }
          .layoutWeight(1).alignItems(HorizontalAlign.Start)
          Text(this.expanded ? '收起' : '查看')
            .fontSize(12).fontColor(ACCENT).width(52).height(44)
            .textAlign(TextAlign.Center).padding({ top: 13, bottom: 13 })
            .accessibilityText(`${this.expanded ? '收起' : '查看'}本章计划提案`)
            .onClick((): void => { this.expanded = !this.expanded; })
        }
        .width('100%').constraintSize({ minHeight: 44 }).alignItems(VerticalAlign.Center)

        if (this.expanded) {
          ForEach(this.view.patches, (patch: NovelChapterPlanPatchView, index: number): void => {
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
      }
      .width('100%')
      .padding({ left: 12, right: 12, top: 10, bottom: 10 })
      .backgroundColor(SURFACE)
      .borderRadius(8)
      .margin({ bottom: 6 })
    }
  }
}
