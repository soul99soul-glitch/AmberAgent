// API 12 frame driver. Snapshot publication and layout run independently of scrolling.
import { FrameCallback, UIContext } from '@kit.ArkUI';
import { nativeTimelineSpringStep } from '@amber/chat-domain';
import type { NativeTimelineSpringSample } from '@amber/chat-domain';

class TimelineFollowFrame extends FrameCallback {
  private driver: NativeTimelineFollow;
  private generation: number;

  constructor(driver: NativeTimelineFollow, generation: number) {
    super();
    this.driver = driver;
    this.generation = generation;
  }

  onFrame(frameTimeInNano: number): void {
    // onIdle can be deferred indefinitely by expensive text layout. Read the last
    // committed geometry at vsync; size changes schedule the next frame as needed.
    this.driver.tick(this.generation, frameTimeInNano / 1_000_000);
  }
}

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
    this.context.postFrameCallback(new TimelineFollowFrame(this, this.generation));
  }

  cancel(): void {
    this.generation++;
    this.pending = false;
    this.lastFrameMs = 0;
    this.velocity = 0;
  }

  tick(generation: number, frameTimeMs: number): void {
    if (generation !== this.generation) return;
    this.pending = false;
    if (!this.mayFollow()) { this.cancel(); return; }
    const target: number | null = this.bottomTarget();
    if (target === null) {
      // New structural row beyond the measured range: anchor it once, then measure.
      this.willScroll();
      this.scroller.scrollEdge(Edge.Bottom);
      this.lastFrameMs = 0;
      this.velocity = 0;
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
      // No idle vsync loop. The next actual layout change requests a new frame.
      this.lastFrameMs = 0;
      this.velocity = 0;
    } else {
      this.request();
    }
  }
}
