import { curves } from '@kit.ArkUI';
import { Icon } from '../ui/Icon.ets';
import { DeepReadDesign as D } from '../../design/DeepReadDesign.ets';

export function deepReadDeskStage(stage: string, label: string): number {
  if (label.includes('抓取')) return 1;
  if (label.includes('保存') || label.endsWith('扩展阅读')) return 3;
  if (stage === 'VERIFYING' || stage === 'COMPLETE') return 3;
  if (stage === 'WRITING' || label.startsWith('正在生成')) return 2;
  if (stage === 'PLANNING') return 1;
  return 0;
}

// Timers animate lead type, pulses and notes. The scheduler alone advances the stage rail.
@Component
export struct DeepReadNewsroomDesk {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('appBackgrounded') @Watch('syncTimer') appBackgrounded: boolean = false;
  @StorageProp('reduceMotion') @Watch('syncTimer') reduceMotion: boolean = false;
  @Prop title: string = '';
  @Prop @Watch('stageChanged') stage: string = 'COLLECTING';
  @Prop @Watch('stageChanged') detail: string = '';
  @Prop @Watch('syncTimer') active: boolean = true;
  @State private tick: number = 0;
  @State private availableHeight: number = 0;
  @State private pulseScale: number = 1;
  @State private pulseOpacity: number = 0;
  private alive: boolean = false;
  private timer: number = -1;
  private pulseTimer: number = -1;
  private pulseOwner: number = 0;
  private lastStage: number = -1;
  private readonly steps: string[] = ['采访', '整理', '撰稿', '付印'];
  private readonly icons: string[] = ['search', 'doc', 'edit', 'check'];
  private readonly headlines: string[] = ['记者正在多方采访', '编辑正在整理素材', '主笔正在撰写文章', '即将付印'];
  private readonly notes: string[] = ['好的报道，始于多问一句。', '事实是神圣的，评论是自由的。',
    '把来源摆上桌，再下结论。', '慢一点，才能读得深一点。', '区分事实与观点，是阅读的第一步。'];

  aboutToAppear(): void { this.alive = true; this.syncTimer(); }
  aboutToDisappear(): void { this.alive = false; this.stopTimer(); }
  private stopTimer(): void {
    if (this.timer >= 0) clearInterval(this.timer);
    if (this.pulseTimer >= 0) clearTimeout(this.pulseTimer);
    this.timer = -1;
    this.pulseTimer = -1;
    this.pulseOwner++;
    if (this.alive) {
      this.getUIContext().animateTo({ duration: 0 }, (): void => { this.pulseScale = 1; this.pulseOpacity = 0; });
    }
  }
  private syncTimer(): void {
    this.stopTimer();
    if (!this.alive || !this.active || this.appBackgrounded) return;
    this.lastStage = this.stageIndex();
    this.pulse();
    const reduced: boolean = this.reduceMotion;
    const timer: number = setInterval((): void => {
      if (this.timer !== timer || !this.alive || !this.active || this.appBackgrounded) return;
      this.tick += reduced ? 14 : 1;
    }, reduced ? 4200 : 300);
    this.timer = timer;
  }
  private motionEnabled(): boolean { return this.alive && this.active && !this.appBackgrounded && !this.reduceMotion; }
  private stageChanged(): void {
    if (this.stageIndex() === this.lastStage) return;
    this.lastStage = this.stageIndex();
    this.pulse();
  }
  private pulse(): void {
    if (!this.motionEnabled()) return;
    if (this.pulseTimer >= 0) clearTimeout(this.pulseTimer);
    const owner: number = ++this.pulseOwner;
    this.getUIContext().keyframeAnimateTo({ iterations: 1 }, [
      { duration: 10, curve: Curve.Linear, event: (): void => {
        if (owner !== this.pulseOwner || !this.motionEnabled()) return;
        this.pulseScale = 1; this.pulseOpacity = 0.5;
      } },
      { duration: 1200, curve: Curve.EaseOut, event: (): void => {
        if (owner !== this.pulseOwner || !this.motionEnabled()) return;
        this.pulseScale = 1.55; this.pulseOpacity = 0;
      } },
    ]);
    // Let the full fade reach zero before the next 10ms reset, like phaseAnimator.
    const pulseTimer: number = setTimeout((): void => {
      if (this.pulseTimer !== pulseTimer || owner !== this.pulseOwner || !this.motionEnabled()) return;
      this.pulseTimer = -1;
      this.pulse();
    }, 1210);
    this.pulseTimer = pulseTimer;
  }
  private stageIndex(): number { return deepReadDeskStage(this.stage, this.detail); }
  private glyphs(): string[] { return Array.from(this.title.replace(/[\s\p{P}]/gu, '')).slice(0, 10); }
  private placed(): number {
    return this.reduceMotion ? this.glyphs().length : Math.min(this.tick % (this.glyphs().length + 6), this.glyphs().length);
  }
  private noteIndex(): number { return Math.floor(this.tick / 14) % this.notes.length; }
  private glyphTransition(): TransitionEffect {
    if (!this.motionEnabled()) return TransitionEffect.OPACITY.animation({ duration: 0 });
    return TransitionEffect.asymmetric(
      TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: -28 }))
        .animation({ duration: 320, curve: curves.springCurve(0, 1, 386, 23) }),
      TransitionEffect.OPACITY.animation({ duration: 320, curve: Curve.EaseOut }));
  }
  private noteTransition(): TransitionEffect {
    const duration: number = this.active && !this.appBackgrounded ? 500 : 0;
    if (this.reduceMotion) return TransitionEffect.OPACITY.animation({ duration, curve: Curve.EaseInOut });
    return TransitionEffect.asymmetric(
      TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: 50 })),
      TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: -50 })))
      .animation({ duration, curve: Curve.EaseInOut });
  }
  private iconTransition(): TransitionEffect {
    if (!this.motionEnabled()) return TransitionEffect.OPACITY.animation({ duration: 200 });
    return TransitionEffect.asymmetric(
      TransitionEffect.OPACITY.combine(TransitionEffect.scale({ x: 0.8, y: 0.8 }))
        .animation({ duration: 500, curve: curves.springCurve(0, 1, 400, 30) }),
      TransitionEffect.OPACITY.animation({ duration: 200 }));
  }

  @Builder
  Rail(): void {
    Row() {
      ForEach(this.steps, (word: string, index: number): void => {
        if (index > 0) {
          Stack() {
            Row().height(2).width('100%').backgroundColor(D.rule)
            Row().height(2).width('100%').backgroundColor(D.accent)
              .scale({ x: index <= this.stageIndex() ? 1 : 0, y: 1, centerX: 0 })
              .animation({ duration: this.motionEnabled() ? 600 : 0, curve: curves.springCurve(0, 1, 110, 17) })
          }.height(2).layoutWeight(1).margin({ top: 16 })
        }
        Column({ space: 6 }) {
          Stack() {
            if (index === this.stageIndex() && this.motionEnabled()) {
              Circle({ width: 34, height: 34 }).fill(Color.Transparent).stroke(D.accent).strokeWidth(2)
                .scale({ x: this.pulseScale, y: this.pulseScale }).opacity(this.pulseOpacity)
            }
            Circle({ width: 34, height: 34 }).fill(index < this.stageIndex() ? D.accent : D.card)
              .stroke(index <= this.stageIndex() ? D.accent : D.rule).strokeWidth(1.5)
              .animation({ duration: this.motionEnabled() ? 600 : 0, curve: curves.springCurve(0, 1, 110, 17) })
            ForEach([index < this.stageIndex()], (done: boolean): void => {
              Icon({ name: done ? 'check' : this.icons[index], iconSize: 15,
                color: done ? D.paper : index === this.stageIndex() ? D.accent : D.muted })
                .transition(this.iconTransition())
            }, (done: boolean): string => `rail-icon-${index}-${done}`)
          }.width(34).height(34)
          Text(word).fontSize(11).fontWeight(index === this.stageIndex() ? FontWeight.Bold : FontWeight.Regular)
            .fontColor(index <= this.stageIndex() ? D.ink : D.muted)
        }.width(42)
      }, (word: string): string => word)
    }.width('100%').alignItems(VerticalAlign.Top)
  }

  @Builder
  TypeTray(): void {
    Row({ space: 5 }) {
      ForEach(this.glyphs(), (glyph: string, index: number): void => {
        Stack() {
          Row().width('100%').height(32).borderRadius(5)
            .border({ width: 1, color: D.rule, style: BorderStyle.Dashed })
          if (index < this.placed()) {
            Text(glyph).fontFamily(D.serif).fontSize(17).fontWeight(FontWeight.Bold).fontColor(D.paper)
              .textAlign(TextAlign.Center).width('100%').height(32).backgroundColor(D.ink).borderRadius(5)
              .shadow({ radius: 2, color: '#2E000000', offsetY: 2 }).transition(this.glyphTransition())
          }
        }.constraintSize({ maxWidth: 28 }).layoutWeight(1).height(32)
      }, (_glyph: string, index: number): string => `lead-${index}`)
    }.width('100%').justifyContent(FlexAlign.Center)
  }

  build(): void {
    Scroll() {
      Column({ space: 28 }) {
        this.Rail()
        Column({ space: 10 }) {
          Stack() {
            ForEach([this.stageIndex()], (index: number): void => {
              Icon({ name: this.icons[index], iconSize: 34, color: D.accent })
                .scale({ x: this.motionEnabled() ? 1 + Math.sin(this.tick * 0.3) * 0.05 : 1,
                  y: this.motionEnabled() ? 1 + Math.sin(this.tick * 0.3) * 0.05 : 1 })
                .animation({ duration: this.motionEnabled() ? 300 : 0, curve: Curve.EaseInOut })
                .transition(this.iconTransition())
            }, (index: number): string => `desk-icon-${index}`)
          }.width(34).height(34)
          Stack() {
            ForEach([this.stageIndex()], (index: number): void => {
              Text(this.headlines[index]).fontFamily(D.serif).fontSize(22)
                .fontWeight(FontWeight.Medium).fontColor(D.ink).textAlign(TextAlign.Center)
                .transition(TransitionEffect.OPACITY.animation({ duration: this.active && !this.appBackgrounded ? 500 : 0 }))
            }, (index: number): string => `desk-headline-${index}`)
          }.width('100%').constraintSize({ minHeight: 34 })
        }.width('100%').constraintSize({ minHeight: 78 })
        this.TypeTray()
        Stack() {
          ForEach([this.noteIndex()], (note: number): void => {
            Text(this.notes[note]).fontFamily(D.serif).fontSize(16).fontStyle(FontStyle.Italic)
              .fontColor(D.muted).lineHeight(25).maxLines(2).textAlign(TextAlign.Center).width('100%')
              .transition(this.noteTransition())
          }, (note: number): string => `desk-note-${note}`)
        }.height(50).width('100%').clip(true)
        Text('可以离开此页面，稍后在阅读库查看进度。').fontSize(13).fontColor(D.muted)
          .lineHeight(20).textAlign(TextAlign.Center).width('100%')
      }.width('100%').constraintSize({ maxWidth: 520, minHeight: this.availableHeight })
        .justifyContent(FlexAlign.Center).padding(24)
    }.width('100%').height('100%').scrollBar(BarState.Off)
      .onAreaChange((_old: Area, area: Area): void => { this.availableHeight = Number(area.height); })
      .accessibilityText(this.headlines[this.stageIndex()] + '。' + this.detail)
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
  }
}
