// ChatTurnProgressRow — 对齐 iOS ChatTaskInlineCardView 的轻量临时单行状态条
// 无背景框、无描边、无步数角标：
// - 进行中（thinking / tool / writing）：3 颗深浅渐变错相上下起伏波浪圆点 + 单行轻文案
// - 已完成（completed）：作为临时态自动隐藏，不残留灰卡
// - 未恢复错误（error）：仅保留安静的单行红点提示，点按可查看详情

import { ACCENT, ERROR, INK, INK2, INK3 } from '../../design/tokens.ets';
import { FONT_SANS } from '../../design/fonts.ets';
import { Icon } from '../ui/Icon.ets';

const DOT_SHADE_TIERS: number[] = [1.0, 0.72, 0.46];
const WAVE_FRAME_MS: number = 33;

@Component
export struct ChatTurnProgressRow {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @StorageProp('reduceMotion') @Watch('syncWaveTimer') reduceMotion: boolean = false;
  @StorageProp('appBackgrounded') @Watch('syncWaveTimer') appBackgrounded: boolean = false;
  @Prop @Watch('syncWaveTimer') statusKind: string = 'completed';
  @Prop headline: string = '';
  @Prop stepCount: number = 0;
  @Prop hasError: boolean = false;
  @Prop streaming: boolean = false;
  onOpenDetails: () => void = (): void => {};

  @State private waveTick: number = 0;
  private waveTimer: number = -1;
  private mounted: boolean = false;

  private isLive(): boolean {
    return this.statusKind === 'thinking' || this.statusKind === 'tool' || this.statusKind === 'writing';
  }

  private isErrorState(): boolean {
    return !this.isLive() && (this.hasError || this.statusKind === 'error');
  }

  private shouldDisplay(): boolean {
    if (this.themeEpoch < 0 || this.headline.length === 0) return false;
    // 临时态：仅在思考/调用工具/组织回复过程中，或存在未恢复错误时展示
    return this.isLive() || this.isErrorState();
  }

  private stopWaveTimer(): void {
    if (this.waveTimer !== -1) {
      clearInterval(this.waveTimer);
      this.waveTimer = -1;
    }
  }

  syncWaveTimer(): void {
    const needAnim: boolean = this.mounted && this.isLive() && !this.reduceMotion && !this.appBackgrounded;
    if (!needAnim) {
      this.stopWaveTimer();
      return;
    }
    if (this.waveTimer !== -1) return;
    this.waveTimer = setInterval((): void => {
      if (!this.mounted || !this.isLive() || this.reduceMotion || this.appBackgrounded) {
        this.stopWaveTimer();
        return;
      }
      this.waveTick += 1;
    }, WAVE_FRAME_MS);
  }

  aboutToAppear(): void {
    this.mounted = true;
    this.syncWaveTimer();
  }

  aboutToDisappear(): void {
    this.mounted = false;
    this.stopWaveTimer();
  }

  private waveOffsetY(dotIndex: number): number {
    if (!this.isLive() || this.reduceMotion || this.appBackgrounded) return 0;
    const t: number = this.waveTick * (WAVE_FRAME_MS / 1000.0);
    const phase: number = t * 4.6 - dotIndex * 0.92;
    return -2.3 * Math.sin(phase);
  }

  private waveOpacity(dotIndex: number): number {
    const tier: number = DOT_SHADE_TIERS[dotIndex] ?? 0.6;
    if (!this.isLive() || this.reduceMotion || this.appBackgrounded) return tier;
    const t: number = this.waveTick * (WAVE_FRAME_MS / 1000.0);
    const wave: number = Math.sin(t * 4.6 - dotIndex * 0.92);
    const normalized: number = (wave + 1.0) * 0.5;
    return Math.min(1.0, Math.max(0.28, (0.38 + 0.62 * normalized) * tier));
  }

  private waveScale(dotIndex: number): number {
    if (!this.isLive() || this.reduceMotion || this.appBackgrounded) return 1.0;
    const t: number = this.waveTick * (WAVE_FRAME_MS / 1000.0);
    const wave: number = Math.sin(t * 4.6 - dotIndex * 0.92);
    const normalized: number = (wave + 1.0) * 0.5;
    return 0.90 + 0.16 * normalized;
  }

  build(): void {
    if (this.shouldDisplay()) {
      Row({ space: 8 }) {
        if (this.isLive()) {
          // 对齐 iOS ChatTaskProgressDot：3 颗深浅渐变圆点错相上下起伏波浪
          Row({ space: 3.5 }) {
            ForEach([0, 1, 2], (dotIdx: number): void => {
              Column()
                .width(5.2)
                .height(5.2)
                .borderRadius(999)
                .backgroundColor(this.accentColorStore)
                .opacity(this.waveOpacity(dotIdx))
                .scale({ x: this.waveScale(dotIdx), y: this.waveScale(dotIdx) })
                .translate({ y: this.waveOffsetY(dotIdx) })
            }, (dotIdx: number): string => `dot-${dotIdx}`)
          }
          .height(16)
          .alignItems(VerticalAlign.Center)
        } else {
          Column()
            .width(6)
            .height(6)
            .borderRadius(999)
            .backgroundColor(ERROR)
        }

        Text(this.headline)
          .fontSize(13.5)
          .fontFamily(FONT_SANS)
          .fontWeight(this.isLive() ? FontWeight.Medium : FontWeight.Normal)
          .fontColor(this.isErrorState() ? ERROR : (this.isLive() ? INK : INK2))
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .constraintSize({ maxWidth: '86%' })

        if (this.isErrorState()) {
          Icon({ name: 'chevron_right', iconSize: 11, color: INK3 })
        }
      }
      .width('100%')
      .constraintSize({ minHeight: 26 })
      .padding({ top: 2, bottom: 4 })
      .margin({ bottom: 4 })
      .alignItems(VerticalAlign.Center)
      .onClick((): void => {
        this.onOpenDetails();
      })
      .accessibilityText(this.headline)
    }
  }
}
