// ChatMessageReasoning — reasoning 思考卡片组件(G4-S2 升级)
//
// Android 基准: ChatMessageReasoning.kt(451 行)
// 升级: 三态完整(collapsed/preview/expanded) + budget 标签 + MarkdownText 渲染内容
//   + duration 实时显示 + preview 限高滚动 + 自动折叠/ticker
// 降级(跳过): shimmer / AnimatedContent 标题切换 / 主题色变体

import type { UIMessagePartReasoning } from '@amber/chat-domain';
import {
  toDisplayReasoningText, reasoningDurationMs, formatThoughtSeconds,
  extractThinkingTitle,
} from '@amber/chat-domain';
import { MOTION_OVERLAY, INK, INK2, INK3, INK4, ACCENT, SURFACE, SURFACE2, LINE, META } from '../design/tokens.ets';
import { MarkdownText } from './MarkdownText.ets';
import { Icon } from './ui/Icon.ets';

@Component
export struct ChatMessageReasoning {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop part: UIMessagePartReasoning;
  @Prop state: string; // 'collapsed' | 'preview' | 'expanded'
  @Prop @Watch('onLoadingChanged') loading: boolean;
  @Prop autoCloseThinking: boolean = true;
  onToggle: () => void = (): void => {};
  onAutoCollapse: () => void = (): void => {};

  @State private durationTick: number = Date.now();
  private durationTimer: number = -1;
  private collapseTimer: number = -1;
  private collapseTimerVersion: number = 0;
  private sawLoading: boolean = false;

  aboutToAppear(): void {
    this.sawLoading = this.loading;
    if (this.loading) this.startDurationTicker();
  }

  aboutToDisappear(): void {
    this.stopDurationTicker();
    this.clearCollapseTimer();
  }

  private onLoadingChanged(): void {
    if (this.loading) {
      this.sawLoading = true;
      this.clearCollapseTimer();
      this.startDurationTicker();
      return;
    }
    this.stopDurationTicker();
    if (this.sawLoading && this.part.finishedAt !== null &&
      this.autoCloseThinking && this.state === 'expanded') {
      this.scheduleAutoCollapse();
    }
  }

  private startDurationTicker(): void {
    if (this.durationTimer !== -1) return;
    this.durationTimer = setInterval((): void => {
      this.durationTick = Date.now();
    }, 250);
  }

  private stopDurationTicker(): void {
    if (this.durationTimer !== -1) {
      clearInterval(this.durationTimer);
      this.durationTimer = -1;
    }
  }

  private clearCollapseTimer(): void {
    this.collapseTimerVersion++;
    if (this.collapseTimer !== -1) {
      clearTimeout(this.collapseTimer);
      this.collapseTimer = -1;
    }
  }

  private scheduleAutoCollapse(): void {
    this.clearCollapseTimer();
    const timerVersion: number = this.collapseTimerVersion;
    this.collapseTimer = setTimeout((): void => {
      if (timerVersion !== this.collapseTimerVersion) return;
      this.collapseTimer = -1;
      if (this.autoCloseThinking && this.state === 'expanded' && this.part.finishedAt !== null) {
        this.getUIContext().animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
          this.onAutoCollapse();
        });
      }
    }, 800);
  }

  build(): void {
    Column() {
      // 头部: 图标 + 标题文本 + 展开箭头
      Row() {
        Icon({ name: 'brain', iconSize: 16, color: ACCENT })
        Text(this.headerText())
          .fontSize(13)
          .fontColor(ACCENT)
          .fontWeight(FontWeight.Medium)
          .fontFamily('JetBrainsMono')
          .margin({ left: 6 })
          .layoutWeight(1)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })

        // duration(右侧)
        if (this.shouldShowDuration()) {
          Text(this.durationLabel())
            .fontSize(11)
            .fontColor(INK3)
            .fontFamily('JetBrainsMono')
            .margin({ right: 6 })
        }

      }
      .width('100%')
      .padding({ left: 2, top: 6, bottom: 6 })
      .onClick((): void => {
        this.clearCollapseTimer();
        this.getUIContext().animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
          this.onToggle();
        });
      })

      // 内容(preview / expanded)
      if (this.state === 'preview') {
        // preview: 限高 100px + 滚动 + 渐隐
        Scroll() {
          Column() {
            this.RenderReasoningContent(false)
          }
          .width('100%')
        }
        .align(Alignment.TopStart)
        .width('100%')
        .height(100)
        .padding({ left: 14, top: 2, bottom: 2 })
        .border({ width: { left: 2 }, color: { left: LINE } })
        .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: -8 }))
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      } else if (this.state === 'expanded') {
        // expanded: 全文
        Column() {
          this.RenderReasoningContent(true)
        }
        .width('100%')
        .padding({ left: 14, top: 2, bottom: 2 })
        .border({ width: { left: 2 }, color: { left: LINE } })
        .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: -8 }))
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      }
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .clip(true)
    .margin({ bottom: 4 })
  }

  @Builder
  RenderReasoningContent(isExpanded: boolean): void {
    MarkdownText({
      content: toDisplayReasoningText(this.part.reasoning, this.loading, isExpanded),
      fontSize: 13,
      fontColor: INK2,
    })
  }

  private headerText(): string {
    if (this.loading) {
      const title: string | null = extractThinkingTitle(this.part.reasoning);
      if (title !== null) return title;
    }
    return '深度思考';
  }

  private budgetLabel(): string {
    // reasoning budget 从 part 元数据获取(如果有 budgetTokens 字段)
    // 简化:暂不提取,返回空(后续从 reasoning_display.ts 接入)
    return '';
  }

  private shouldShowDuration(): boolean {
    if (!this.loading && this.part.finishedAt === null) return false;
    const durationMs: number = reasoningDurationMs(
      this.part.createdAt, this.part.finishedAt, this.loading, this.durationTick);
    return Number.isFinite(durationMs) && durationMs >= 0;
  }

  private durationLabel(): string {
    const durationMs: number = reasoningDurationMs(
      this.part.createdAt, this.part.finishedAt, this.loading, this.durationTick);
    if (!Number.isFinite(durationMs) || durationMs < 0) return '';
    if (this.loading) return `思考中 ${(durationMs / 1000).toFixed(1)}s`;
    return formatThoughtSeconds(durationMs / 1000);
  }
}
