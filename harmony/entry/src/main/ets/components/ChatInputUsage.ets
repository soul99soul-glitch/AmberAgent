// ChatInputUsage — 上下文用量指示器 + reasoning 等级 chip + 用量 sheet
// 对齐 Android ChatInputUsage.kt(663 行)
// 必要: ContextRing(圆环+token数) + ReasoningLevelChip(等级选择)
// 降级(跳过): Canvas drawArc(用 Text 替代) / DropdownMenu(用 Select 替代) / ComposerUsageSheet(已有独立页面)
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, ACCENT, ACCENT_LIGHT, SIGNAL, ERROR, BODY, META, LABEL, STATUS_BLUE, STATUS_AMBER, STATUS_BLUE_LIGHT } from '../design/tokens.ets';

// 格式化 token 数(对齐 Android formatContextTokens)
const formatTokens = (tokens: number): string => {
  const value: number = Math.trunc(tokens);
  if (value <= 0) return '0';
  if (value < 1000) return String(value);
  if (value < 1000000) {
    const kilo: number = value / 1000;
    return Number.isInteger(kilo) ? `${kilo}K` : `${kilo.toFixed(1)}K`;
  }
  if (value < 1000000000) {
    const mega: number = value / 1000000;
    return Number.isInteger(mega) ? `${mega}M` : `${mega.toFixed(1)}M`;
  }
  const billion: number = value / 1000000000;
  return Number.isInteger(billion) ? `${billion}B` : `${billion.toFixed(1)}B`;
};

// 上下文用量颜色(对齐 Android ringColor)
const contextColor = (ratio: number, compactActive: boolean): string => {
  if (compactActive) return STATUS_BLUE;  // compact active
  if (ratio >= 0.9) return ERROR;         // error
  if (ratio >= 0.7) return STATUS_AMBER;  // amber
  return INK3;                            // normal
};

@Component
struct ContextRing {
  @Prop @Watch('onInputChanged') ratio: number = 0;
  @Prop @Watch('onInputChanged') color: string = INK3;
  @Prop @Watch('onInputChanged') lineColor: string = LINE;
  private settings: RenderingContextSettings = new RenderingContextSettings(true);
  private context: CanvasRenderingContext2D = new CanvasRenderingContext2D(this.settings);

  private onInputChanged(): void {
    this.draw();
  }

  private draw(): void {
    const width: number = this.context.width;
    const height: number = this.context.height;
    const size: number = Math.min(width, height);
    if (size <= 0) return;
    this.context.clearRect(0, 0, width, height);
    const stroke: number = 1.5;
    const center: number = size / 2;
    const radius: number = (size - stroke) / 2;
    this.context.beginPath();
    this.context.strokeStyle = this.lineColor;
    this.context.lineWidth = stroke;
    this.context.arc(center, center, radius, 0, Math.PI * 2);
    this.context.stroke();
    if (this.ratio <= 0) return;
    this.context.beginPath();
    this.context.strokeStyle = this.color;
    this.context.lineWidth = stroke;
    this.context.arc(center, center, radius, -Math.PI / 2,
      -Math.PI / 2 + Math.PI * 2 * Math.min(this.ratio, 1));
    this.context.stroke();
  }

  build(): void {
    Canvas(this.context)
      .width(14)
      .height(14)
      .onReady((): void => { this.draw(); });
  }
}

// ContextUsageIndicator — 上下文环 + token 文本
@Component
export struct ContextUsageIndicator {
  @Prop usedTokens: number = 0;
  @Prop contextWindow: number = 0;
  @Prop compactActive: boolean = false;
  @Prop lineColor: string = LINE;

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
      ContextRing({ ratio: this.ratio(), color: this.ringColor(), lineColor: this.lineColor })
        .margin({ right: 4 })

      Text(this.labelText())
        .fontSize(LABEL)
        .fontColor(INK3)
        .constraintSize({ maxWidth: 180 })
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
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
  onSelect: () => void = (): void => {};
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
      .onClick((): void => { this.onSelect(); })
  }
}
