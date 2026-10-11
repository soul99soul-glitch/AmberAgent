// ToolApprovalSummaryCard — 通用待授权摘要(非 recipe/plugin/SSH 工具)
//
// 数据由 chat-domain buildToolApprovalSummary 纯函数产出(标签行 + 破坏性 + 后果提示),
// 本组件只负责排版。iOS 基准: ChatToolSupport.swift appleArgumentsPreview /
// appleConsequenceWarning / isDestructiveAppleAction。
import type { ToolApprovalSummary, ToolApprovalLine } from '@amber/chat-domain';
import {
  INK, INK2, INK3, SURFACE2, LINE, ERROR, ERROR_BG, WARNING_BG, WARNING_FG,
  META, LABEL, PAD_ITEM, RADIUS,
} from '../design/tokens.ets';
import { Icon } from './ui/Icon.ets';

// 标签列最小宽度:对齐「事件 ID」「最多选择」这类 4 字标签
const LABEL_MIN_WIDTH: number = 52;
const ROW_GAP: number = 6;

@Component
export struct ToolApprovalSummaryCard {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop summary: ToolApprovalSummary;
  // 卡片标题(调用方传静态显示标题;空 → summary.title)
  @Prop heading: string = '';
  @Prop iconName: string = 'gear';
  @Prop canOpen: boolean = true;
  onOpen: () => void = (): void => {};

  private headingText(): string {
    return this.heading.length > 0 ? this.heading : this.summary.title;
  }

  private warningBg(): string {
    return this.summary.warningLevel === 'danger' ? ERROR_BG : WARNING_BG;
  }

  private warningFg(): string {
    return this.summary.warningLevel === 'danger' ? ERROR : WARNING_FG;
  }

  private a11yText(): string {
    const parts: string[] = [`待授权：${this.headingText()}`];
    if (this.summary.destructive) parts.push('破坏性操作');
    for (const line of this.summary.lines) parts.push(`${line.label} ${line.value}`);
    if (this.summary.note.length > 0) parts.push(this.summary.note);
    if (this.summary.warning.length > 0) parts.push(this.summary.warning);
    return parts.join('，');
  }

  build(): void {
    Column({ space: ROW_GAP }) {
      Row({ space: ROW_GAP }) {
        Icon({ name: this.iconName, iconSize: 14, color: this.summary.destructive ? ERROR : INK2 })
          .flexShrink(0)
        Text(this.headingText())
          .fontSize(META).fontWeight(FontWeight.Medium)
          .fontColor(this.summary.destructive ? ERROR : INK)
          .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
          .layoutWeight(1)
      }
      .width('100%').alignItems(VerticalAlign.Center)

      ForEach(this.summary.lines, (line: ToolApprovalLine): void => {
        Row({ space: ROW_GAP }) {
          Text(line.label)
            .fontSize(LABEL).fontColor(INK3)
            .constraintSize({ minWidth: LABEL_MIN_WIDTH })
            .flexShrink(0)
          Text(line.value)
            .fontSize(LABEL).fontColor(INK2)
            .wordBreak(WordBreak.BREAK_ALL)
            .maxLines(3).textOverflow({ overflow: TextOverflow.Ellipsis })
            .layoutWeight(1)
        }
        .width('100%').alignItems(VerticalAlign.Top)
      }, (line: ToolApprovalLine, idx: number): string => `${idx}-${line.label}-${line.value}`)

      if (this.summary.note.length > 0) {
        Text(this.summary.note).fontSize(LABEL).fontColor(INK3).width('100%')
      }

      if (this.summary.warning.length > 0) {
        Row({ space: ROW_GAP }) {
          Icon({ name: 'info', iconSize: 12, color: this.warningFg() }).flexShrink(0)
          Text(this.summary.warning)
            .fontSize(LABEL).fontColor(this.warningFg())
            .fontWeight(this.summary.warningLevel === 'danger' ? FontWeight.Medium : FontWeight.Normal)
            .layoutWeight(1)
        }
        .width('100%')
        .alignItems(VerticalAlign.Top)
        .padding({ left: ROW_GAP + 2, right: ROW_GAP + 2, top: ROW_GAP, bottom: ROW_GAP })
        .backgroundColor(this.warningBg())
        .borderRadius(RADIUS / 2)
      }
    }
    .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .alignItems(HorizontalAlign.Start)
    .padding(PAD_ITEM)
    .backgroundColor(SURFACE2)
    .borderRadius(RADIUS)
    .border({ width: this.summary.destructive ? 1 : 0.5, color: this.summary.destructive ? ERROR : LINE })
    .margin({ bottom: ROW_GAP })
    .onClick((): void => { if (this.canOpen) this.onOpen(); })
    .accessibilityGroup(true)
    .accessibilityText(this.a11yText())
  }
}
