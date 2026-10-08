import { sensor } from '@kit.SensorServiceKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { curves } from '@kit.ArkUI';
import { DeepReadDesign as D } from '../../design/DeepReadDesign.ets';
import { DeepReadHaptics as H } from '../../design/DeepReadHaptics.ets';
import { Icon } from '../ui/Icon.ets';
import type { HotItem } from '../../platform_impl/NewsNowProvider.ets';
import type { DeepReadInputSource } from '@amber/deepread-domain';

export interface DeepReadDiscoveryPick extends HotItem {
  seedUrls: string[];
  inputSources: DeepReadInputSource[];
}

// Two strong samples distinguish a deliberate shake from placing the phone down.
export class DeepReadShakeDetector {
  private peaks: number = 0;
  private lastPeak: number = 0;
  private lastShake: number = -1200;

  accept(x: number, y: number, z: number, now: number): boolean {
    if (x * x + y * y + z * z < 18 * 18 || now - this.lastShake < 1200) return false;
    if (now - this.lastPeak > 350) this.peaks = 0;
    this.lastPeak = now;
    this.peaks++;
    if (this.peaks < 2) return false;
    this.peaks = 0;
    this.lastShake = now;
    return true;
  }
}

@Component
export struct DeepReadDiscoveryShake {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('reduceMotion') @Watch('motionReduced') reduceMotion: boolean = false;
  @StorageProp('appBackgrounded') @Watch('syncActivity') appBackgrounded: boolean = false;
  @Prop @Watch('syncActivity') active: boolean = true;
  @Prop @Watch('triggerChanged') trigger: number = 0;
  getPicks: () => DeepReadDiscoveryPick[] = (): DeepReadDiscoveryPick[] => [];
  onRead: (pick: DeepReadDiscoveryPick) => void = (_pick: DeepReadDiscoveryPick): void => {};
  onHeightChanged: (height: number) => void = (_height: number): void => {};
  @State private lucky: DeepReadDiscoveryPick | null = null;
  @State private dieY: number = 0;
  @State private dieScale: number = 1;
  private pending: DeepReadDiscoveryPick | null = null;
  private alive: boolean = false;
  private subscribed: boolean = false;
  private scattered: boolean = false;
  private lastTrigger: number = 0;
  private scatterTimer: number = -1;
  private expiryTimer: number = -1;
  private expiryCycle: number = 0;
  private bounceTimer: number = -1;
  private readonly dieXs: number[] = [3.5, 15.5, 9.5, 3.5, 15.5];
  private readonly dieYs: number[] = [3.5, 3.5, 9.5, 15.5, 15.5];
  private detector: DeepReadShakeDetector = new DeepReadShakeDetector();
  private acceleration: (data: sensor.AccelerometerResponse) => void = (data: sensor.AccelerometerResponse): void => {
    if (this.alive && this.active && !this.appBackgrounded && this.detector.accept(data.x, data.y, data.z, Date.now())) {
      this.shuffle();
    }
  };

  aboutToAppear(): void { this.alive = true; this.lastTrigger = this.trigger; this.syncActivity(); }
  aboutToDisappear(): void { this.alive = false; this.syncActivity(); }

  private syncActivity(): void {
    if (!this.alive || !this.active || this.appBackgrounded) {
      if (this.subscribed) {
        this.subscribed = false;
        try { sensor.off(sensor.SensorId.ACCELEROMETER, this.acceleration); }
        catch (error) { hilog.warn(0x0001, 'DeepReadShake', 'off failed: %{public}s', String(error)); }
      }
      this.clearVisuals();
      this.detector = new DeepReadShakeDetector();
      return;
    }
    if (this.subscribed || !canIUse('SystemCapability.Sensors.Sensor')) return;
    try {
      // API 9, official offline sensor-guidelines. Interval is in nanoseconds.
      sensor.on(sensor.SensorId.ACCELEROMETER, this.acceleration, { interval: 50000000 });
      this.subscribed = true;
    } catch (error) {
      // Unsupported simulators keep the explicit random-pick control available.
      hilog.warn(0x0001, 'DeepReadShake', 'on failed: %{public}s', String(error));
    }
  }

  private triggerChanged(): void {
    if (!this.alive || this.trigger <= this.lastTrigger) return;
    this.lastTrigger = this.trigger;
    this.shuffle();
  }

  private clearVisuals(): void {
    this.settleDie();
    if (this.scatterTimer >= 0) clearTimeout(this.scatterTimer);
    if (this.expiryTimer >= 0) clearTimeout(this.expiryTimer);
    this.scatterTimer = -1; this.expiryTimer = -1;
    this.expiryCycle++;
    this.pending = null; this.lucky = null; this.scattered = false;
    this.onHeightChanged(0);
    AppStorage.setOrCreate<boolean>('deepreadDiscoveryScattered', false);
  }

  private motionReduced(): void {
    if (!this.reduceMotion) return;
    this.settleDie();
    if (this.pending !== null) this.finishShuffle();
  }

  private settleDie(): void {
    if (this.bounceTimer >= 0) clearTimeout(this.bounceTimer);
    this.bounceTimer = -1; this.dieY = 0; this.dieScale = 1;
  }

  private shuffle(): void {
    if (!this.alive || !this.active || this.appBackgrounded || this.scattered) return;
    const picks: DeepReadDiscoveryPick[] = this.getPicks();
    if (picks.length === 0) return;
    if (this.expiryTimer >= 0) clearTimeout(this.expiryTimer);
    this.expiryTimer = -1;
    this.expiryCycle++;
    this.settleDie();
    this.pending = picks[Math.floor(Math.random() * picks.length)];
    if (this.reduceMotion) { this.finishShuffle(); return; }
    this.scattered = true;
    AppStorage.setOrCreate<number>('deepreadDiscoveryScatterSeed', (AppStorage.get<number>('deepreadDiscoveryScatterSeed') ?? 0) + 1);
    AppStorage.setOrCreate<boolean>('deepreadDiscoveryScattered', true);
    this.scatterTimer = setTimeout((): void => { this.finishShuffle(); }, 360);
  }

  private finishShuffle(): void {
    if (!this.alive || !this.active || this.appBackgrounded || this.pending === null) return;
    if (this.scatterTimer >= 0) clearTimeout(this.scatterTimer);
    this.scatterTimer = -1;
    this.scattered = false;
    AppStorage.setOrCreate<boolean>('deepreadDiscoveryScattered', false);
    const pick: DeepReadDiscoveryPick = this.pending;
    this.pending = null;
    this.getUIContext().animateTo({ duration: this.reduceMotion ? 150 : 280, curve: Curve.EaseOut }, (): void => {
      this.lucky = pick;
    });
    H.success();
    const cycle: number = this.expiryCycle;
    if (!this.reduceMotion) {
      this.dieY = -5; this.dieScale = 1.15;
      this.bounceTimer = setTimeout((): void => {
        this.bounceTimer = -1;
        if (!this.alive || !this.active || this.appBackgrounded || this.reduceMotion || cycle !== this.expiryCycle) return;
        this.getUIContext().animateTo({ duration: 420, curve: curves.springCurve(0, 1, 180, 16) }, (): void => {
          this.dieY = 0; this.dieScale = 1;
        });
      }, 16);
    }
    this.expiryTimer = setTimeout((): void => {
      if (this.alive && this.active && !this.appBackgrounded && cycle === this.expiryCycle) this.dismissLucky();
    }, 8000);
  }

  private dismissLucky(): void {
    this.settleDie();
    if (this.expiryTimer >= 0) clearTimeout(this.expiryTimer);
    this.expiryTimer = -1;
    this.expiryCycle++;
    this.getUIContext().animateTo({ duration: this.reduceMotion ? 150 : 280, curve: Curve.EaseOut }, (): void => {
      this.lucky = null;
    });
  }

  private detail(pick: DeepReadDiscoveryPick): string {
    if (pick.inputSources.length > 1) return `${pick.inputSources.length} 个来源`;
    return (pick.inputSources[0]?.researchSource?.source ?? '当前热点') + ` 第 ${pick.rank}`;
  }

  private reportHeight(area: Area): void {
    this.onHeightChanged(this.alive && this.active && !this.appBackgrounded ? Number(area.height) : 0);
  }

  @Builder
  DiceIcon(): void {
    Stack({ alignContent: Alignment.TopStart }) {
      Row().width(22).height(22).borderRadius(4).border({ width: 1.6, color: D.accent })
      ForEach([0, 1, 2, 3, 4], (index: number): void => {
        Circle().width(3).height(3).fill(D.accent).position({ x: this.dieXs[index], y: this.dieYs[index] })
      }, (index: number): string => 'dot-' + index)
    }.width(22).height(22).translate({ y: this.dieY }).scale({ x: this.dieScale, y: this.dieScale })
      .accessibilityText('随机推荐')
  }

  @Builder
  LuckyBanner(pick: DeepReadDiscoveryPick): void {
    Row({ space: 10 }) {
      this.DiceIcon()
      Column({ space: 3 }) {
        Text('摇到一篇 · ' + this.detail(pick)).fontSize(11).fontFamily(D.sans).fontColor(D.muted).width('100%')
        Text(pick.displayTitle ?? pick.title).fontSize(14).fontWeight(600).fontFamily(D.serif).fontColor(D.ink)
          .maxLines(2).width('100%')
      }.layoutWeight(1).alignItems(HorizontalAlign.Start)
      Text('读这篇').fontSize(13).fontColor(D.card).padding({ left: 12, right: 12 }).height(44)
        .textAlign(TextAlign.Center).borderRadius(22).backgroundColor(D.accent)
        .onClick((): void => { this.dismissLucky(); this.onRead(pick); })
      Row() { Icon({ name: 'close', iconSize: 14, color: D.muted }) }.width(44).height(44)
        .justifyContent(FlexAlign.Center).accessibilityText('关闭随机推荐')
        .onClick((): void => { this.dismissLucky(); })
    }.width('100%').padding(14).backgroundColor(D.card).borderRadius(22).border({ width: 0.6, color: D.rule })
      .shadow({ radius: 12, color: '#142A2320', offsetY: 4 })
      .transition((this.reduceMotion ? TransitionEffect.OPACITY
        : TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: 28 })))
        .animation({ duration: this.reduceMotion ? 150 : 280, curve: Curve.EaseOut }))
  }

  build(): void {
    Column() {
      ForEach([this.themeEpoch], (_epoch: number): void => {
        if (this.lucky !== null) this.LuckyBanner(this.lucky)
      }, (epoch: number): string => 'lucky-theme-' + epoch)
    }.width('100%').padding({ left: D.gutter, right: D.gutter })
      .onAreaChange((_previous: Area, area: Area): void => { this.reportHeight(area); })
  }
}
