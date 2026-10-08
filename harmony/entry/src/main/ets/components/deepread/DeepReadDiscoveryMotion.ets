import { curves } from '@kit.ArkUI';
import { DeepReadDesign as D } from '../../design/DeepReadDesign.ets';

// A card has one entrance and can subsequently be tossed by the discovery owner.
@Component
export struct DeepReadDiscoveryMotion {
  @StorageProp('reduceMotion') @Watch('motionChanged') reduceMotion: boolean = false;
  @StorageProp('appBackgrounded') @Watch('motionChanged') appBackgrounded: boolean = false;
  @StorageProp('deepreadDiscoveryScattered') @Watch('scatterChanged') scattered: boolean = false;
  @StorageProp('deepreadDiscoveryScatterSeed') scatterSeed: number = 0;
  @Prop index: number = 0;
  @Prop playEntrance: boolean = true;
  @Prop scatterEnabled: boolean = true;
  @Prop @Watch('motionChanged') active: boolean = true;
  @BuilderParam content: () => void;
  @State private ink: number = 1;
  @State private entranceY: number = 0;
  @State private tossX: number = 0;
  @State private tossY: number = 0;
  @State private angle: number = 0;
  private alive: boolean = false;
  private entranceTimer: number = -1;

  aboutToAppear(): void {
    this.alive = true;
    if (!this.playEntrance || !this.active || this.appBackgrounded) return;
    this.ink = 0;
    this.entranceY = this.reduceMotion ? 0 : 26;
    // Render the initial transparent/offset frame before committing the target.
    this.entranceTimer = setTimeout((): void => {
      this.entranceTimer = -1;
      if (!this.alive || !this.active || this.appBackgrounded) return;
      this.getUIContext().animateTo({ duration: this.reduceMotion ? 250 : 550,
        delay: this.reduceMotion ? 0 : Math.min(this.index, 8) * 50,
        curve: this.reduceMotion ? Curve.EaseOut : curves.springCurve(0, 1, 180, 24) }, (): void => {
        this.ink = 1; this.entranceY = 0;
      });
    }, 16);
  }

  aboutToDisappear(): void { this.alive = false; this.settle(); }

  private settle(): void {
    if (this.entranceTimer >= 0) clearTimeout(this.entranceTimer);
    this.entranceTimer = -1;
    this.ink = 1; this.entranceY = 0; this.tossX = 0; this.tossY = 0; this.angle = 0;
  }

  private motionChanged(): void {
    if (!this.alive || !this.active || this.appBackgrounded) { this.settle(); return; }
    if (this.reduceMotion) { this.entranceY = 0; this.tossX = 0; this.tossY = 0; this.angle = 0; }
  }

  private unit(shift: number): number {
    const bits: number = ((this.index + 1) * 1664525 + (this.scatterSeed + 1) * 1013904223) >>> 0;
    return ((bits >>> shift) & 255) / 127.5 - 1;
  }

  private scatterChanged(): void {
    if (!this.scatterEnabled) return;
    if (!this.alive || !this.active || this.appBackgrounded) { this.settle(); return; }
    if (this.reduceMotion) { this.motionChanged(); return; }
    this.getUIContext().animateTo({ duration: this.scattered ? 280 : 620,
      curve: curves.springCurve(0, 1, this.scattered ? 230 : 120, 18) }, (): void => {
      this.tossX = this.scattered ? this.unit(0) * 70 : 0;
      this.tossY = this.scattered ? this.unit(8) * 36 - 18 : 0;
      this.angle = this.scattered ? this.unit(16) * 9 : 0;
    });
  }

  build(): void {
    Column() { this.content() }.width('100%').opacity(this.ink)
      .translate({ x: this.tossX, y: this.entranceY + this.tossY }).rotate({ angle: this.angle })
  }
}

@Component
export struct DeepReadDiscoveryPlaceholder {
  @StorageProp('reduceMotion') @Watch('syncPulse') reduceMotion: boolean = false;
  @StorageProp('appBackgrounded') @Watch('syncPulse') appBackgrounded: boolean = false;
  @Prop @Watch('syncPulse') active: boolean = true;
  @State private ink: number = 1;
  private alive: boolean = false;
  private pulseTimer: number = -1;

  aboutToAppear(): void { this.alive = true; this.syncPulse(); }
  aboutToDisappear(): void { this.alive = false; this.syncPulse(); }

  private syncPulse(): void {
    if (this.pulseTimer >= 0) clearInterval(this.pulseTimer);
    this.pulseTimer = -1;
    this.ink = 1;
    if (!this.alive || !this.active || this.appBackgrounded || this.reduceMotion) return;
    this.pulseTimer = setInterval((): void => {
      if (!this.alive || !this.active || this.appBackgrounded || this.reduceMotion) return;
      this.getUIContext().animateTo({ duration: 900, curve: Curve.EaseInOut }, (): void => {
        this.ink = this.ink > 0.5 ? 0.45 : 1;
      });
    }, 900);
  }

  build(): void {
    Column({ space: 8 }) {
      Row().height(20).width('85%').backgroundColor(D.rule).borderRadius(4)
      Row().height(12).width('60%').backgroundColor(D.rule).borderRadius(4)
    }.width('100%').padding(14).alignItems(HorizontalAlign.Start).backgroundColor(D.card)
      .borderRadius(D.radius).border({ width: 0.6, color: D.rule }).opacity(this.ink)
      .accessibilityText('正在获取热点')
  }
}

@Component
export struct DeepReadDiscoveryNotice {
  @StorageProp('reduceMotion') @Watch('settleMotion') reduceMotion: boolean = false;
  @StorageProp('appBackgrounded') @Watch('settleMotion') appBackgrounded: boolean = false;
  @Prop @Watch('settleMotion') active: boolean = true;
  @Prop message: string = '';
  @Prop danger: boolean = true;
  @State private ink: number = 0;
  @State private y: number = 0;
  private alive: boolean = false;
  private entranceTimer: number = -1;

  aboutToAppear(): void {
    this.alive = true;
    if (!this.active || this.appBackgrounded) { this.ink = 1; return; }
    this.y = this.reduceMotion ? 0 : -18;
    this.entranceTimer = setTimeout((): void => {
      this.entranceTimer = -1;
      if (!this.alive || !this.active || this.appBackgrounded) return;
      this.getUIContext().animateTo({ duration: this.reduceMotion ? 150 : 280, curve: Curve.EaseOut }, (): void => {
        this.ink = 1; this.y = 0;
      });
    }, 16);
  }

  aboutToDisappear(): void { this.alive = false; this.settleMotion(); }

  private settleMotion(): void {
    if (!this.alive || this.appBackgrounded || !this.active) {
      if (this.entranceTimer >= 0) clearTimeout(this.entranceTimer);
      this.entranceTimer = -1;
      this.ink = 1; this.y = 0;
    }
    if (this.reduceMotion) this.y = 0;
  }

  build(): void {
    Text(this.message).fontSize(13).lineHeight(19).fontColor(this.danger ? D.danger : D.muted).width('100%')
      .padding(14).backgroundColor(D.card).borderRadius(D.radius).border({ width: 0.6, color: D.rule })
      .opacity(this.ink).translate({ y: this.y })
      .transition(TransitionEffect.OPACITY.animation({ duration: this.reduceMotion ? 150 : 280 }))
  }
}
