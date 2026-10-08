// DisplaySync votes for actual display frames while the native List is moving.
// postFrameCallback alone can inherit the system's 30fps animation vote.
import { display, UIContext } from '@kit.ArkUI';
import { displaySync } from '@kit.ArkGraphics2D';
import { deviceInfo } from '@kit.BasicServicesKit';
import { nativeTimelineSpringStep } from '@amber/chat-domain';
import type { NativeTimelineSpringSample } from '@amber/chat-domain';

export class NativeTimelineFollow {
  private context: UIContext;
  private scroller: Scroller;
  private mayFollow: () => boolean;
  private bottomTarget: () => number | null;
  private willScroll: () => void;
  private pending: boolean = false;
  private generation: number = 0;
  private lastFrameMs: number = 0;
  private velocity: number = 0;
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
    let rate: number = screen.refreshRate;
    if (canIUse('SystemCapability.Window.SessionManager') && deviceInfo.sdkApiVersion >= 20) {
      const supported: number[] | undefined = screen.supportedRefreshRates;
      if (supported !== undefined && supported.length > 0) rate = supported[supported.length - 1];
    }
    // Older systems expose the current rate only. Never request beyond a known
    // device capability; cap the preferred motion clock at 120Hz.
    rate = Math.max(1, Math.min(120, Math.round(rate)));
    this.clock.setExpectedFrameRateRange({ min: rate, max: rate, expected: rate });
    const generation: number = this.generation;
    this.frameCallback = (info: displaySync.IntervalInfo): void => {
      this.tick(generation, info.timestamp / 1_000_000);
    };
    this.clock.on('frame', this.frameCallback);
    this.context.runScopedTask((): void => { this.clock.start(); });
  }

  cancel(): void {
    this.generation++;
    if (this.pending) {
      this.clock.stop();
      this.clock.off('frame', this.frameCallback);
    }
    this.pending = false;
    this.lastFrameMs = 0;
    this.velocity = 0;
  }

  tick(generation: number, frameTimeMs: number): void {
    if (generation !== this.generation) return;
    if (!this.mayFollow()) { this.cancel(); return; }
    const target: number | null = this.bottomTarget();
    if (target === null) {
      // New structural row beyond the measured range: anchor it once, then measure.
      this.willScroll();
      this.scroller.scrollEdge(Edge.Bottom);
      this.cancel();
      return;
    }
    const current: number = this.scroller.currentOffset().yOffset;
    const dt: number = this.lastFrameMs > 0 ? (frameTimeMs - this.lastFrameMs) / 1000 : 1 / 60;
    this.lastFrameMs = frameTimeMs;
    const sample: NativeTimelineSpringSample = nativeTimelineSpringStep(current, this.velocity, target, dt);
    const arrived: boolean = Math.abs(target - sample.position) <= 0.5;
    const delta: number = (arrived ? target : sample.position) - current;
    this.velocity = sample.velocity;
    if (Math.abs(delta) > 0.01) {
      this.willScroll();
      this.scroller.scrollBy(0, delta);
    }
    if (arrived) {
      // Release the high-rate vote at rest, on touch, and when the page detaches.
      this.cancel();
    }
  }
}
