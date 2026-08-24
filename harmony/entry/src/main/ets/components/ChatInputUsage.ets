// ChatInputUsage — 上下文用量指示器 + reasoning 等级 chip + 用量 sheet
// 对齐 Android ChatInputUsage.kt(663 行)
// 必要: ContextRing(圆环+token数) + ReasoningLevelChip(等级选择)
// 降级(跳过): Canvas drawArc(用 Text 替代) / DropdownMenu(用 Select 替代) / ComposerUsageSheet(已有独立页面)
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, ACCENT, ACCENT_LIGHT, SIGNAL, ERROR, BODY, META, LABEL, STATUS_BLUE, STATUS_AMBER, STATUS_BLUE_LIGHT } from '../design/tokens.ets';

// 格式化 token 数(对齐 Android formatContextTokens)
const formatTokens = (tokens: number): string => {
  if (tokens <= 0) return '0';
  if (tokens < 1000) return '<1K';
  if (tokens < 10000) return `${(tokens / 1000).toFixed(1)}K`;
  return `${Math.round(tokens / 1000)}K`;
};

// 上下文用量颜色(对齐 Android ringColor)
const contextColor = (ratio: number, compactActive: boolean): string => {
  if (compactActive) return STATUS_BLUE;  // blue
  if (ratio >= 0.7) return ERROR;       // red
  if (ratio >= 0.5) return STATUS_AMBER;   // amber
  return INK3;                           // muted
};

// ContextUsageIndicator — 上下文环 + token 文本
@Component
export struct ContextUsageIndicator {
  @Prop usedTokens: number = 0;
  @Prop contextWindow: number = 0;
  @Prop compactActive: boolean = false;

  private ratio(): number {
    if (this.contextWindow <= 0) return 0;
    return Math.min(this.usedTokens / this.contextWindow, 1);
  }

  private ringColor(): string {
    return contextColor(this.ratio(), this.compactActive);
  }

  private labelText(): string {
    if (this.compactActive) return '自动压缩中';
    const used: string = formatTokens(this.usedTokens);
    const window: string = this.contextWindow > 0 ? formatTokens(this.contextWindow) : '--';
    return `Context ${used} / ${window}`;
  }

  build(): void {
    Row() {
      // 圆环(用 Text 字符模拟,ArkUI Canvas 复杂度高)
      Text('●')
        .fontSize(10)
        .fontColor(this.ringColor())
        .opacity(this.ratio() > 0 ? 0.8 : 0.3)
        .margin({ right: 4 })

      Text(this.labelText())
        .fontSize(LABEL)
        .fontColor(INK3)
        .maxLines(1)
    }
    .alignItems(VerticalAlign.Center)
  }
}

// ReasoningLevelChip — reasoning 等级选择 chip
@Component
export struct ReasoningLevelChip {
  @Prop label: string = '';
  @Prop accent: boolean = false;
  onSelect: () => void = (): void => {};
  build(): void {
    Text(this.label)
      .fontSize(LABEL)
      .fontColor(this.accent ? STATUS_BLUE : INK2)
      .padding({ left: 10, right: 10, top: 5, bottom: 5 })
      .backgroundColor(this.accent ? STATUS_BLUE_LIGHT : SURFACE)
      .borderRadius(8)
      .border({ width: 1, color: this.accent ? STATUS_BLUE : LINE })
      .maxLines(1)
      .onClick((): void => { this.onSelect(); })
  }
}

// ComposerStatusChip — 状态 chip(对齐 Android ComposerStatusChip)
@Component
export struct ComposerStatusChip {
  @Prop text: string = '';
  @Prop accent: boolean = false;
  onClick: () => void = (): void => {};
  build(): void {
    Text(this.text)
      .fontSize(LABEL)
      .fontColor(this.accent ? STATUS_BLUE : INK2)
      .padding({ left: 10, right: 10, top: 5, bottom: 5 })
      .backgroundColor(this.accent ? STATUS_BLUE_LIGHT : SURFACE)
      .borderRadius(8)
      .border({ width: 1, color: this.accent ? STATUS_BLUE : LINE })
      .maxLines(1)
      .textOverflow({ overflow: TextOverflow.Ellipsis })
      .onClick((): void => { this.onClick(); })
  }
}
