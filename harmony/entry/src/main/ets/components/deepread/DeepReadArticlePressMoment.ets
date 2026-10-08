import { curves } from '@kit.ArkUI';
import { DeepReadDesign as D } from '../../design/DeepReadDesign.ets';
import { DeepReadHaptics } from '../../design/DeepReadHaptics.ets';

@Component
export struct DeepReadArticlePressMoment {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('appBackgrounded') @Watch('hide') appBackgrounded: boolean = false;
  @StorageProp('reduceMotion') @Watch('reduceMotionChanged') reduceMotion: boolean = false;
  @StorageProp('darkMode') darkMode: boolean = false;
  @Prop @Watch('play') trigger: number = 0;
  @Prop inscription: string = '付印';
  @Prop caption: string = '文章已排版完成';
  @Prop @Watch('activityChanged') active: boolean = true;
  @State private visible: boolean = false;
  @State private stampScale: number = 2.6;
  @State private stampAngle: number = 8;
  @State private ink: number = 0;
  @State private captionShown: boolean = false;
  private alive: boolean = false;
  private timer: number = -1;
  private enterTimer: number = -1;
  private captionTimer: number = -1;
  private motionOwner: number = 0;

  aboutToAppear(): void { this.alive = true; this.play(); }
  aboutToDisappear(): void { this.alive = false; this.hide(); }
  private activityChanged(): void { if (!this.active) this.hide(); }
  private hide(): void {
    if (this.timer >= 0) clearTimeout(this.timer);
    if (this.enterTimer >= 0) clearTimeout(this.enterTimer);
    if (this.captionTimer >= 0) clearTimeout(this.captionTimer);
    this.timer = -1;
    this.enterTimer = -1;
    this.captionTimer = -1;
    this.motionOwner++;
    this.visible = false;
  }
  private current(owner: number): boolean {
    return owner === this.motionOwner && this.alive && this.visible && this.active && !this.appBackgrounded;
  }
  private overlayTransition(): TransitionEffect {
    const animated: boolean = this.active && !this.appBackgrounded;
    return TransitionEffect.asymmetric(
      TransitionEffect.OPACITY.animation({ duration: animated ? 200 : 0, curve: Curve.EaseIn }),
      TransitionEffect.OPACITY.animation({ duration: animated ? 400 : 0, curve: Curve.EaseOut }));
  }
  private reduceMotionChanged(): void {
    if (!this.reduceMotion || !this.alive || !this.visible) return;
    this.motionOwner++;
    if (this.enterTimer >= 0) clearTimeout(this.enterTimer);
    if (this.captionTimer >= 0) clearTimeout(this.captionTimer);
    this.enterTimer = -1;
    this.captionTimer = -1;
    this.getUIContext().animateTo({ duration: 0 }, (): void => {
      this.stampScale = 1; this.stampAngle = -10; this.ink = 0.88; this.captionShown = true;
    });
  }
  private startStamp(owner: number): void {
    if (!this.current(owner)) return;
    if (!this.reduceMotion) {
      this.getUIContext().keyframeAnimateTo({ iterations: 1 }, [
        { duration: 10, curve: Curve.Linear, event: (): void => {
          if (!this.current(owner)) return;
          this.stampScale = 2.6; this.stampAngle = 8;
        } },
        { duration: 180, curve: Curve.EaseOut, event: (): void => {
          if (!this.current(owner)) return;
          this.stampScale = 0.92; this.stampAngle = -10;
        } },
        { duration: 350, curve: curves.springCurve(0, 1, 220, 16), event: (): void => {
          if (this.current(owner)) this.stampScale = 1;
        } },
      ]);
    }
    this.getUIContext().animateTo({ delay: 10, duration: this.reduceMotion ? 250 : 140, curve: Curve.EaseOut }, (): void => {
      if (this.current(owner)) this.ink = 0.88;
    });
  }
  private play(): void {
    this.hide();
    if (!this.alive || !this.active || this.appBackgrounded || this.trigger <= 0) return;
    this.visible = true;
    this.ink = 0;
    this.captionShown = false;
    this.stampScale = this.reduceMotion ? 1 : 2.6;
    this.stampAngle = this.reduceMotion ? -10 : 8;
    const owner: number = this.motionOwner;
    DeepReadHaptics.impact();
    // Wait for the newly visible subtree to mount before starting property animation.
    const enterTimer: number = setTimeout((): void => {
      if (this.enterTimer !== enterTimer) return;
      this.enterTimer = -1;
      this.startStamp(owner);
    }, 16);
    this.enterTimer = enterTimer;
    const captionTimer: number = setTimeout((): void => {
      if (this.captionTimer !== captionTimer) return;
      this.captionTimer = -1;
      if (!this.current(owner)) return;
      this.getUIContext().animateTo({ duration: 400, curve: Curve.EaseOut }, (): void => {
        if (this.current(owner)) this.captionShown = true;
      });
    }, 300);
    this.captionTimer = captionTimer;
    const dismissTimer: number = setTimeout((): void => {
      if (this.timer === dismissTimer) this.hide();
    }, this.inscription === '付印' ? 1400 : 2600);
    this.timer = dismissTimer;
  }

  build(): void {
    if (this.visible && this.themeEpoch >= 0) {
      Column({ space: 18 }) {
        Stack() {
          Row().width(128).height(128).borderRadius(18).border({ width: 9, color: D.accent })
          Row().width(102).height(102).borderRadius(10).border({ width: 2.5, color: D.accent })
          Text(Array.from(this.inscription).join('\n')).fontFamily(D.serif).fontSize(38).fontWeight(FontWeight.Bold)
            .fontColor(D.accent).textAlign(TextAlign.Center).lineHeight(43)
        }.width(128).height(128).rotate({ angle: this.stampAngle }).opacity(this.ink)
          .scale({ x: this.stampScale, y: this.stampScale })
          .blendMode(this.darkMode ? BlendMode.NONE : BlendMode.MULTIPLY)
        Text(this.caption).fontFamily(D.serif).fontSize(17).fontWeight(FontWeight.Medium).fontColor(D.ink)
          .opacity(this.captionShown ? 1 : 0).translate({ y: this.captionShown || this.reduceMotion ? 0 : 8 })
      }.width('100%').height('100%').justifyContent(FlexAlign.Center)
        .transition(this.overlayTransition())
        .backgroundColor('#80' + D.paper.substring(1)).backgroundBlurStyle(BlurStyle.Thin)
        .onClick((): void => { this.hide(); })
        .accessibilityText(this.inscription + '。' + this.caption + '。点按关闭。')
    }
  }
}
