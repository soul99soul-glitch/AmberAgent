import { curves } from '@kit.ArkUI';
import { ACCENT } from '../../design/tokens.ets';
import { FONT_SERIF } from '../../design/fonts.ets';

// A finite ink impression. Its owner decides which real event deserves a stamp.
@Component
export struct StudioInkStamp {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('appBackgrounded') @Watch('backgroundChanged') appBackgrounded: boolean = false;
  @Prop word: string = '开卷';
  @Prop @Watch('play') trigger: number = 0;
  @Prop @Watch('activeChanged') active: boolean = true;
  @Prop stampSize: number = 48;
  onVisibilityChange: (visible: boolean) => void = (_visible: boolean): void => {};
  @State private ink: number = 0;
  @State private stampScale: number = 1.5;
  private alive: boolean = false;
  private fadeTimer: number = -1;

  aboutToAppear(): void {
    this.alive = true;
    if (this.trigger > 0) this.play();
  }

  aboutToDisappear(): void {
    this.alive = false;
    this.clearTimer();
  }

  private clearTimer(): void {
    if (this.fadeTimer >= 0) clearTimeout(this.fadeTimer);
    this.fadeTimer = -1;
  }

  private activeChanged(): void {
    if (this.active) return;
    this.settle();
  }

  private backgroundChanged(): void {
    if (this.appBackgrounded) this.settle();
  }

  private settle(): void {
    this.clearTimer();
    this.ink = 0;
    this.stampScale = 1.5;
    this.onVisibilityChange(false);
  }

  private play(): void {
    if (!this.alive || !this.active || this.appBackgrounded || this.trigger <= 0) return;
    this.clearTimer();
    this.ink = 0;
    this.stampScale = 1.5;
    this.onVisibilityChange(true);
    this.getUIContext().animateTo({ duration: 360, curve: curves.springCurve(0, 1, 220, 24) }, (): void => {
      this.ink = 0.9;
      this.stampScale = 1;
    });
    this.fadeTimer = setTimeout((): void => {
      this.fadeTimer = -1;
      if (!this.alive || !this.active || this.appBackgrounded) return;
      this.getUIContext().animateTo({ duration: 360, curve: Curve.EaseOut }, (): void => {
        this.ink = 0;
      });
      this.onVisibilityChange(false);
    }, 2400);
  }

  build(): void {
    Stack() {
      Row().width('100%').height('100%').borderRadius(7).border({ width: 3, color: ACCENT })
      Row().width('78%').height('78%').borderRadius(4).border({ width: 0.8, color: ACCENT })
      Text(this.word).fontFamily(FONT_SERIF).fontSize(this.stampSize * 0.31).fontWeight(700).fontColor(ACCENT)
    }.width(this.stampSize).height(this.stampSize)
      .rotate({ angle: -10 }).scale({ x: this.stampScale, y: this.stampScale }).opacity(this.ink)
      .hitTestBehavior(HitTestMode.None).accessibilityText(this.ink > 0 ? this.word : '')
  }
}
