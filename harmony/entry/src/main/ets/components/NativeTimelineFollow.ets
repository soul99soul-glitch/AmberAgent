// Native List animation owns intermediate positions. DisplaySync requests the
// motion rate and samples changing layout without computing each scroll step.
import { display, UIContext } from '@kit.ArkUI';
import { displaySync } from '@kit.ArkGraphics2D';
import { deviceInfo } from '@kit.BasicServicesKit';

export class NativeTimelineFollow {
  private context: UIContext;
  private scroller: Scroller;
  private mayFollow: () => boolean;
  private bottomTarget: () => number | null;
  private willScroll: () => void;
  private pending: boolean = false;
  private generation: number = 0;
  private animationTarget: number = -1;
  private clock: displaySync.DisplaySync = displaySync.create();
  private frameCallback: (info: displaySync.IntervalInfo) => void = (_info: displaySync.IntervalInfo): void => {};

  constructor(context: UIContext, scroller: Scroller, mayFollow: () => boolean,
    bottomTarget: () => number | null, willScroll: () => void) {
    this.context = context;
    this.scroller = scroller;
    this.mayFollow = mayFollow;
    this.bottomTarget = bottomTarget;
    this.willScroll = willScroll;
  }

  request(): void {
    if (this.pending || !this.mayFollow()) return;
    this.pending = true;
    const screen: display.Display = display.getDefaultDisplaySync();
    // Prefer the highest known mode up to120Hz. Older systems expose only
    // their current rate; API20+ can enumerate the supported modes.
    let rate: number = screen.refreshRate <= 120 ? screen.refreshRate : 60;
    if (canIUse('SystemCapability.Window.SessionManager') && deviceInfo.sdkApiVersion >= 20) {
      const supported: number[] | undefined = screen.supportedRefreshRates;
      if (supported !== undefined) {
        for (const supportedRate of supported) {
          if (supportedRate > rate && supportedRate <= 120) rate = supportedRate;
        }
      }
    }
    rate = Math.max(1, Math.min(120, Math.round(rate)));
    // Let LTPO choose a sustainable motion rate instead of locking the panel.
    // At rest cancel() releases the request entirely, allowing deeper idle rates.
    this.clock.setExpectedFrameRateRange({ min: Math.min(60, rate), max: rate, expected: rate });
    const generation: number = this.generation;
    this.frameCallback = (_info: displaySync.IntervalInfo): void => {
      this.tick(generation);
    };
    this.clock.on('frame', this.frameCallback);
    this.context.runScopedTask((): void => { this.clock.start(); });
  }

  isRunning(): boolean {
    return this.pending && this.animationTarget >= 0;
  }

  cancel(): void {
    const wasAnimating: boolean = this.animationTarget >= 0;
    this.generation++;
    if (this.pending) {
      this.clock.stop();
      this.clock.off('frame', this.frameCallback);
    }
    // Invalidate before stopping the List animation, whose callbacks may reenter.
    this.pending = false;
    this.animationTarget = -1;
    if (wasAnimating) {
      this.scroller.scrollTo({ xOffset: 0,
        yOffset: this.scroller.currentOffset().yOffset, animation: false });
    }
  }

  tick(generation: number): void {
    if (generation !== this.generation) return;
    if (!this.mayFollow()) { this.cancel(); return; }
    const target: number | null = this.bottomTarget();
    if (target === null) {
      // New structural row beyond the measured range: anchor it once, then measure.
      this.cancel();
      this.willScroll();
      this.scroller.scrollEdge(Edge.Bottom);
      return;
    }
    const current: number = this.scroller.currentOffset().yOffset;
    const distance: number = target - current;
    if (distance <= 0.5) { this.cancel(); return; }
    if (Math.abs(target - this.animationTarget) <= 0.5) return;
    this.animationTarget = target;
    this.willScroll();
    // As in the native thinking panel, retarget only on geometry changes.
    // Linear interpolation avoids restarting an ease-in on every text snapshot;
    // duration keeps large paragraph bursts within the existing720vp/s ceiling.
    this.scroller.scrollTo({ xOffset: 0, yOffset: target,
      animation: { duration: Math.max(160, distance / 720 * 1000),
        curve: Curve.Linear, canOverScroll: false } });
  }
}
