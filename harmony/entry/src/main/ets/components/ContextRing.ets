// ContextRing — 上下文用量 5 段条 + 百分比(G4-S4)
// Android 基准: ContextRing.kt(439 行)
// 必要: 5 段条(threshold 颜色) + 百分比文本
// 降级(跳过): ContextUsagePopup(260dp 弹出详情) / Canvas drawArc / 配额行
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, ACCENT, ERROR, LABEL, STATUS_AMBER } from '../design/tokens.ets';

const formatTokens = (n: number): string => {
  if (n <= 0) return '0';
  if (n < 1000) return `${n}`;
  if (n < 10000) return `${(n / 1000).toFixed(1)}K`;
  if (n < 1000000) return `${Math.round(n / 1000)}K`;
  return `${(n / 1000000).toFixed(1)}M`;
};

@Component
export struct ContextRing {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop used: number = 0;
  @Prop total: number = 0;
  @State expanded: boolean = false;
  onClick: () => void = (): void => {};

  private ratio(): number {
    if (this.total <= 0) return 0;
    return Math.min(this.used / this.total, 1);
  }

  private filledBars(): number {
    return Math.round(this.ratio() * 5);
  }

  private barColor(filled: boolean): string {
    if (!filled) return LINE;
    const r: number = this.ratio();
    if (r >= 0.75) return ERROR;
    if (r >= 0.5) return STATUS_AMBER;
    return ACCENT;
  }

  private percentText(): string {
    const pct: number = Math.round(this.ratio() * 100);
    return `${pct}%`;
  }

  build(): void {
    Row() {
      // 5 段条
      Row() {
        ForEach([0, 1, 2, 3, 4], (i: number): void => {
          Text('')
            .width(3)
            .height(8)
            .borderRadius(1)
            .backgroundColor(this.barColor(i < this.filledBars()))
            .margin({ right: 2 })
        }, (i: number): string => `bar-${i}`)
      }
      .margin({ right: 3 })

      Text(this.percentText())
        .fontSize(LABEL)
        .fontColor(INK3)
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .alignItems(VerticalAlign.Center)
    .padding({ left: 6, right: 6, top: 6, bottom: 6 })
    .borderRadius(8)
    .onClick((): void => { this.onClick(); })
  }
}
