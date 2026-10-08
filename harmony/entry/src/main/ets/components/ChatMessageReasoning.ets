// 同一个思考壳承载标题与正文；收起时按内容宽度呈胶囊，展开时提供阅读区域。
import type { JsonObject, UIMessagePartReasoning } from '@amber/chat-domain';
import { toDisplayReasoningText, reasoningDurationMs, reasoningDisplayLimit } from '@amber/chat-domain';
import { MOTION_OVERLAY, INK2, ACCENT_LIGHT, LINE, lineHeightForSize } from '../design/tokens.ets';
import { FONT_SANS } from '../design/fonts.ets';

interface ReasoningLine {
  key: string;
  text: string;
}

@Observed
class ReasoningLineSlot {
  readonly key: string;
  text: string;

  constructor(line: ReasoningLine) {
    this.key = line.key;
    this.text = line.text;
  }
}

@Component
struct ReasoningLineText {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @ObjectLink slot: ReasoningLineSlot;

  build(): void {
    if (this.themeEpoch >= 0) {
      Text(this.slot.text.length > 0 ? this.slot.text : ' ')
        .fontSize(13).fontColor(INK2).fontFamily(FONT_SANS)
        .lineHeight(lineHeightForSize(13)).width('100%')
        .copyOption(CopyOptions.LocalDevice)
    }
  }
}

// 与 iOS ChatKoboyoMark.solidThoughtCloud 同源的扁平实心思考云。
const THOUGHT_CLOUD_PATH: string = 'M109 19.1C96.7 21.1 83.1 31 78.1 41.6L75.7 46.6L65.1 46.5C54.7 46.5 54.2 46.6 46.8 50.3C37.5 54.9 29.1 63.5 24.7 72.9C21.2 80.4 20 91.9 21.9 99.5C22.9 103.6 22.8 104.9 20.9 110.1C12.1 134.2 34.1 160.8 62 159.9L69 159.7L72.1 164.3C78 173.2 88.7 180.4 100.7 183.5C108.3 185.5 124.1 185.4 131.1 183.4C137.3 181.6 145.8 177 148.5 174C150.8 171.4 151.2 171.5 157.6 174.6C167.1 179.2 181.2 178.8 190 173.7C198.1 169 204.5 161 206.5 153.1C207.6 149.1 208.1 148.6 211.8 147.5C222 144.4 229.3 137.9 234.2 127.3C245.1 104.1 236.2 78.6 214.8 71L209.6 69.2L208.4 61.3C206.5 48.2 200.7 40 189.5 34.4C181.2 30.4 168 29.8 158.1 33L151.2 35.2L146.9 30.5C141.7 24.9 135.1 21.3 126.1 19.4C118.7 17.9 116.9 17.8 109 19.1ZM45.5 173.7C39.2 177 35 183.1 35 189C35 195.4 40.1 202.6 46.3 204.9C51.2 206.8 59.8 206.2 64.2 203.8C73.5 198.5 75.5 185.7 68.2 177.7C63.4 172.3 51.9 170.3 45.5 173.7ZM23.9 208.5C20.4 210.4 17.6 216.4 18.4 220.3C19.8 227.1 27.5 231.1 34.5 228.8C39.6 227.1 42.3 223.3 42.3 217.9C42.3 214.3 41.7 213.1 38.7 210.3C35.7 207.6 34.2 207 30.8 207C28.4 207.1 25.3 207.7 23.9 208.5Z';

@Component
export struct ChatMessageReasoning {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @Prop @Watch('updateReasoningSlots') part: UIMessagePartReasoning;
  @Prop state: string;
  @Prop @Watch('onLoadingChanged') loading: boolean;
  @Prop autoCloseThinking: boolean = true;
  @Prop allowAutoCollapse: boolean = true;
  onToggle: () => void = (): void => {};
  onAutoCollapse: () => void = (): void => {};

  @State private durationTick: number = Date.now();
  @State private preserveStreamLines: boolean = false;
  @State private reasoningSlots: ReasoningLineSlot[] = [];
  private durationTimer: number = -1;
  private collapseTimer: number = -1;
  private sawLoading: boolean = false;
  private thinkingScroller: Scroller = new Scroller();
  private followThinking: boolean = true;
  private thinkingReady: boolean = false;
  private thinkingTouchActive: boolean = false;
  private thinkingContentHeight: number = 0;
  private thinkingViewportHeight: number = 0;
  private thinkingAnimationTarget: number = -1;

  aboutToAppear(): void {
    this.sawLoading = this.loading;
    this.preserveStreamLines = this.loading;
    this.updateReasoningSlots();
    if (this.loading) this.startDurationTicker();
  }

  aboutToDisappear(): void {
    this.stopThinkingFollow();
    this.thinkingReady = false;
    this.stopDurationTicker();
    this.clearCollapseTimer();
  }

  private onLoadingChanged(): void {
    if (this.loading) {
      this.sawLoading = true;
      this.preserveStreamLines = true;
      this.updateReasoningSlots();
      this.clearCollapseTimer();
      this.startDurationTicker();
    } else {
      this.stopDurationTicker();
      if (this.sawLoading && this.part.finishedAt !== null && this.autoCloseThinking &&
        this.allowAutoCollapse && this.state === 'expanded') {
        this.clearCollapseTimer();
        this.collapseTimer = setTimeout((): void => {
          this.collapseTimer = -1;
          if (!this.loading && this.allowAutoCollapse && this.autoCloseThinking && this.state === 'expanded') {
            this.getUIContext().animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
              this.onAutoCollapse();
            });
          }
        }, 300);
      }
    }
  }

  private startDurationTicker(): void {
    if (this.durationTimer !== -1) return;
    this.durationTick = Date.now();
    this.durationTimer = setInterval((): void => { this.durationTick = Date.now(); }, 1000);
  }

  private stopDurationTicker(): void {
    if (this.durationTimer !== -1) {
      clearInterval(this.durationTimer);
      this.durationTimer = -1;
    }
  }

  private clearCollapseTimer(): void {
    if (this.collapseTimer !== -1) {
      clearTimeout(this.collapseTimer);
      this.collapseTimer = -1;
    }
  }

  private levelLabel(): string {
    const metadata: JsonObject | null = this.part.metadata;
    if (metadata === null) return '';
    const level = metadata['reasoningLevel'];
    if (level === 'low') return '低';
    if (level === 'medium') return '中';
    if (level === 'high') return '高';
    if (level === 'xhigh' || level === 'max') return '最高';
    if (level === 'auto') return '自动';
    return '';
  }

  private headerText(): string {
    const durationMs: number = reasoningDurationMs(
      this.part.createdAt, this.part.finishedAt, this.loading, this.durationTick);
    const validDuration: boolean = Number.isFinite(durationMs) && durationMs >= 0;
    const seconds: number = this.loading ? Math.floor(durationMs / 1000) : Math.max(1, Math.round(durationMs / 1000));
    const title: string = this.loading
      ? (validDuration ? `思考中 ${seconds} 秒` : '思考中')
      : (this.part.finishedAt !== null && validDuration ? `思考了 ${seconds} 秒` : '思考过程');
    const level: string = this.levelLabel();
    return level.length > 0 ? `${title} · ${level}` : title;
  }

  private displayedReasoningText(): string {
    return toDisplayReasoningText(this.part.reasoning, this.loading || this.preserveStreamLines, true);
  }

  private reasoningLines(): ReasoningLine[] {
    const source: string = this.part.reasoning;
    const sourceStart: number = Math.max(0,
      source.length - reasoningDisplayLimit(this.loading || this.preserveStreamLines, true));
    const blocks: ReasoningLine[] = [];
    if (sourceStart > 0) {
      const rendered: string = this.displayedReasoningText();
      // One separator is supplied by the next Text; retain the other blank line.
      const notice: string = rendered.slice(0, rendered.length - (source.length - sourceStart) - 1);
      blocks.push({ key: 'notice', text: notice });
    }
    const lines: string[] = source.split('\n');
    let offset: number = 0;
    let start: number = 0;
    let content: string = '';
    for (let index: number = 0; index < lines.length; index++) {
      const line: string = lines[index];
      if (index > 0 && content.length + 1 + line.length > 512) {
        // Bound the number of native Text nodes without inserting new line breaks.
        // Partition from the original source so the moving tail window keeps IDs.
        if (start + content.length >= sourceStart) {
          const visibleStart: number = Math.max(start, sourceStart);
          const visible: string = content.slice(visibleStart - start);
          blocks.push({ key: `source-${start}`, text: visible });
        }
        start = offset;
        content = line;
      } else {
        content += (index > 0 ? '\n' : '') + line;
      }
      offset += line.length + 1;
    }
    if (start + content.length >= sourceStart) {
      const visibleStart: number = Math.max(start, sourceStart);
      const visible: string = content.slice(visibleStart - start);
      blocks.push({ key: `source-${start}`, text: visible });
    }
    return blocks;
  }

  private updateReasoningSlots(): void {
    if (!this.loading && !this.preserveStreamLines) return;
    const previous: ReasoningLineSlot[] = this.reasoningSlots;
    const byKey: Map<string, ReasoningLineSlot> = new Map<string, ReasoningLineSlot>();
    for (const slot of previous) byKey.set(slot.key, slot);
    const lines: ReasoningLine[] = this.reasoningLines();
    const next: ReasoningLineSlot[] = [];
    let structureChanged: boolean = previous.length !== lines.length;
    for (let index: number = 0; index < lines.length; index++) {
      const line: ReasoningLine = lines[index];
      const existing: ReasoningLineSlot | undefined = byKey.get(line.key);
      const slot: ReasoningLineSlot = existing ?? new ReasoningLineSlot(line);
      // Only the growing tail and the clipped window edge publish text changes.
      if (slot.text !== line.text) slot.text = line.text;
      next.push(slot);
      if (previous[index] !== slot) structureChanged = true;
    }
    // Retained ObjectLink children read their own text, not a per-delta array.
    if (structureChanged) this.reasoningSlots = next;
  }

  private updateThinkingContentHeight(height: number): void {
    this.thinkingContentHeight = height;
    this.requestThinkingFollow();
  }

  private updateThinkingViewportHeight(height: number): void {
    this.thinkingViewportHeight = height;
    this.requestThinkingFollow();
  }

  private requestThinkingFollow(): void {
    if (!this.thinkingReady || !this.loading || !this.followThinking ||
      this.thinkingTouchActive || this.thinkingViewportHeight <= 0) return;
    // Content is measured before its growing viewport. As on iOS, wait until
    // the card reaches its streaming height cap before treating growth as overflow.
    if (this.thinkingViewportHeight + 0.5 < 180) return;
    const target: number = Math.max(0, this.thinkingContentHeight - this.thinkingViewportHeight);
    const current: number = this.thinkingScroller.currentOffset().yOffset;
    const distance: number = target - current;
    if (distance <= 0.5) {
      this.stopThinkingFollow();
      return;
    }
    if (Math.abs(target - this.thinkingAnimationTarget) <= 0.5) return;
    this.thinkingAnimationTarget = target;
    // Match the iOS thinking panel's linear glide: at least 280ms, at most 540vp/s.
    // Native Scroll owns the intermediate frames; text snapshots only retarget it.
    this.thinkingScroller.scrollTo({ xOffset: 0, yOffset: target,
      animation: { duration: Math.max(280, distance / 540 * 1000), curve: Curve.Linear,
        canOverScroll: false } });
  }

  private stopThinkingFollow(): void {
    if (!this.thinkingReady || this.thinkingAnimationTarget < 0) return;
    this.thinkingAnimationTarget = -1;
    this.thinkingScroller.scrollTo({ xOffset: 0,
      yOffset: this.thinkingScroller.currentOffset().yOffset, animation: false });
  }

  private beginThinkingTouch(): void {
    this.thinkingTouchActive = true;
    this.stopThinkingFollow();
  }

  private endThinkingTouch(): void {
    this.thinkingTouchActive = false;
    this.requestThinkingFollow();
  }

  private pauseThinkingFollow(): void {
    this.followThinking = false;
    this.stopThinkingFollow();
  }

  private resumeThinkingFollow(): void {
    if (!this.thinkingScroller.isAtEnd()) return;
    this.followThinking = true;
    this.requestThinkingFollow();
  }

  build(): void {
    Column() {
      Row({ space: 7 }) {
        Shape() {
          Path().commands(THOUGHT_CLOUD_PATH).fill(INK2).strokeWidth(0)
        }
        .width(14).height(14)
        .viewPort({ x: 0, y: 0, width: '257px', height: '252px' })
        Text(this.headerText())
          .fontSize(13).fontColor(INK2).fontWeight(FontWeight.Medium)
          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
      }
      .width(this.state === 'collapsed' ? 'auto' : '100%')
      .padding({ left: 12, right: 12, top: 7, bottom: 7 })
      .onClick((): void => {
        this.clearCollapseTimer();
        // Keep the live text subtree during the handoff to the first body token.
        // Reopen a finished card as one selectable Text only on explicit demand.
        if (!this.loading && this.state === 'collapsed') this.preserveStreamLines = false;
        this.getUIContext().animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
          this.onToggle();
        });
      })
      if (this.state !== 'collapsed') {
        Scroll(this.thinkingScroller) {
          Column() {
            if (this.loading || this.preserveStreamLines) {
              // ArkUI remeasures a Text's whole paragraph on content changes.
              // Freeze completed text blocks while the final block grows.
              ForEach(this.reasoningSlots, (slot: ReasoningLineSlot): void => {
                ReasoningLineText({ slot: slot })
              }, (slot: ReasoningLineSlot): string => slot.key)
            } else {
              // Reopened completed cards retain continuous cross-line selection/copy.
              Text(this.displayedReasoningText())
                .fontSize(13).fontColor(INK2).fontFamily(FONT_SANS)
                .lineHeight(lineHeightForSize(13)).width('100%')
                .copyOption(CopyOptions.LocalDevice)
            }
          }
          .width('100%').alignItems(HorizontalAlign.Start)
          // Padding belongs to the measured content, so both heights use the same bounds.
          .padding({ left: 12, right: 12, top: 3, bottom: 10 })
          .onSizeChange((_oldSize: SizeOptions, size: SizeOptions): void => {
            this.updateThinkingContentHeight(Number(size.height));
          })
        }
        .align(Alignment.TopStart).width('100%')
        .constraintSize({ maxHeight: this.loading ? 180 : 260 })
        .scrollBar(BarState.Off)
        .onAppear((): void => {
          this.thinkingReady = true;
          this.requestThinkingFollow();
        })
        .onDisAppear((): void => {
          this.stopThinkingFollow();
          this.thinkingReady = false;
          this.thinkingTouchActive = false;
          this.thinkingContentHeight = 0;
          this.thinkingViewportHeight = 0;
        })
        .onSizeChange((_oldSize: SizeOptions, size: SizeOptions): void => {
          this.updateThinkingViewportHeight(Number(size.height));
        })
        .onTouch((event: TouchEvent): void => {
          if (event.type === TouchType.Down) this.beginThinkingTouch();
          if (event.type === TouchType.Up || event.type === TouchType.Cancel) this.endThinkingTouch();
        })
        .onWillScroll((_x: number, y: number, _state: ScrollState, source: ScrollSource): void => {
          if (y < 0 && (source === ScrollSource.DRAG || source === ScrollSource.FLING)) {
            this.pauseThinkingFollow();
          }
        })
        .onDidScroll((_x: number, y: number, state: ScrollState): void => {
          if (y > 0 && state !== ScrollState.Idle) this.resumeThinkingFollow();
        })
        .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: 8 }))
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      }
    }
    .enabled(this.themeEpoch >= 0)
    .alignItems(HorizontalAlign.Start)
    .width(this.state === 'collapsed' ? 'auto' : '100%')
    .constraintSize({ maxWidth: '100%' })
    .backgroundColor(ACCENT_LIGHT)
    .border({ width: 0.7, color: LINE })
    .borderRadius(this.state === 'collapsed' ? 17 : 18)
    .clip(true)
    .margin({ bottom: 6 })
  }
}
