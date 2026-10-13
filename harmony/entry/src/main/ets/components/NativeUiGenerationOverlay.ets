import { ACCENT, SURFACE } from '../design/tokens.ets';

/** A rendered native node measured in window coordinates by onAreaChange. */
export interface NativeUiNodeFrame {
  id: string;
  type: string;
  x: number;
  y: number;
  width: number;
  height: number;
}

export type NativeUiNodeAreaCallback = (id: string, frame: NativeUiNodeFrame | null) => void;

export interface NativeUiFrameBounds {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

export const nativeUiFrameBounds = (frame: NativeUiNodeFrame, hostX: number, hostY: number,
  width: number, height: number): NativeUiFrameBounds | null => {
  const bounds: NativeUiFrameBounds = {
    left: Math.max(0, frame.x - hostX),
    top: Math.max(0, frame.y - hostY),
    right: Math.min(width, frame.x - hostX + frame.width),
    bottom: Math.min(height, frame.y - hostY + frame.height),
  };
  return bounds.right - bounds.left < 2 || bounds.bottom - bounds.top < 2 ? null : bounds;
};

const REVEAL_DURATION: number = 680;

const clampUnit = (value: number): number => Math.max(0, Math.min(1, value));

export class NativeUiRevealClock {
  private duration: number;
  private startedAt: number = -1;

  constructor(duration: number = REVEAL_DURATION) {
    this.duration = Math.max(1, duration);
  }

  start(nowMs: number): void {
    this.startedAt = nowMs;
  }

  reset(): void {
    this.startedAt = -1;
  }

  progress(nowMs: number): number {
    if (this.startedAt < 0) return 0;
    return clampUnit((nowMs - this.startedAt) / this.duration);
  }

  done(nowMs: number): boolean {
    return this.startedAt >= 0 && nowMs >= this.startedAt + this.duration;
  }
}

export const nativeUiColorWithAlpha = (color: string, alpha: number): string => {
  const normalized: string = color.startsWith('#') ? color.substring(1) : color;
  if (normalized.length !== 6 || !/^[0-9a-fA-F]{6}$/.test(normalized)) return color;
  const red: number = parseInt(normalized.substring(0, 2), 16);
  const green: number = parseInt(normalized.substring(2, 4), 16);
  const blue: number = parseInt(normalized.substring(4, 6), 16);
  return `rgba(${red}, ${green}, ${blue}, ${clampUnit(alpha)})`;
};

@Component
export struct NativeUiGenerationOverlay {
  @StorageProp('themeEpoch') @Watch('onThemeChanged') themeEpoch: number = 0;
  @StorageProp('reduceMotion') @Watch('onEnvironmentChanged') reduceMotion: boolean = false;
  @StorageProp('appBackgrounded') @Watch('onEnvironmentChanged') appBackgrounded: boolean = false;
  @StorageProp('accentColor') accentColor: string = ACCENT;
  @Prop @Watch('onFramesChanged') frames: NativeUiNodeFrame[] = [];
  @Prop @Watch('onActiveChanged') active: boolean = true;
  @Prop @Watch('onVisibleChanged') visible: boolean = true;
  onFinished: () => void = (): void => {};
  private settings: RenderingContextSettings = new RenderingContextSettings(true);
  private context: CanvasRenderingContext2D = new CanvasRenderingContext2D(this.settings);
  private hostX: number = 0;
  private hostY: number = 0;
  private hostReady: boolean = false;
  private started: boolean = false;
  private finished: boolean = false;
  private animationTimer: number = -1;
  private clock: NativeUiRevealClock = new NativeUiRevealClock();
  private progress: number = 0;

  aboutToAppear(): void {
    if (this.active) this.start();
  }

  aboutToDisappear(): void {
    this.clearAnimationTimer();
    this.finished = true;
  }

  private onThemeChanged(): void {
    this.draw();
  }

  private onFramesChanged(): void {
    this.draw();
  }

  private onActiveChanged(): void {
    if (this.active) this.start();
    else this.complete();
  }

  private onVisibleChanged(): void {
    if (!this.visible) this.complete();
  }

  private onEnvironmentChanged(): void {
    if (this.reduceMotion || this.appBackgrounded) this.complete();
  }

  private start(): void {
    if (this.started || this.finished) return;
    this.started = true;
    if (!this.visible || this.reduceMotion || this.appBackgrounded) {
      this.complete();
      return;
    }
    this.clock.start(Date.now());
    this.progress = 0;
    this.draw();
    this.animationTimer = setInterval((): void => { this.tick(); }, 16);
  }

  private clearAnimationTimer(): void {
    if (this.animationTimer >= 0) clearInterval(this.animationTimer);
    this.animationTimer = -1;
    this.clock.reset();
  }

  private tick(): void {
    if (this.finished) return;
    const nowMs: number = Date.now();
    this.progress = this.clock.progress(nowMs);
    this.draw();
    if (this.clock.done(nowMs)) this.complete();
  }

  private complete(): void {
    if (this.finished) return;
    this.clearAnimationTimer();
    this.progress = 1;
    this.draw();
    this.finished = true;
    this.onFinished();
  }

  private frameBounds(frame: NativeUiNodeFrame, width: number, height: number): NativeUiFrameBounds | null {
    return nativeUiFrameBounds(frame, this.hostX, this.hostY, width, height);
  }

  private eraseFrame(bounds: NativeUiFrameBounds, amount: number): void {
    if (amount <= 0) return;
    this.context.save();
    this.context.globalCompositeOperation = 'destination-out';
    this.context.globalAlpha = clampUnit(amount);
    this.context.fillStyle = '#FFFFFF';
    this.context.fillRect(bounds.left, bounds.top, bounds.right - bounds.left, bounds.bottom - bounds.top);
    this.context.restore();
  }

  private drawFrame(frame: NativeUiNodeFrame, alpha: number, width: number, height: number): void {
    const bounds: NativeUiFrameBounds | null = this.frameBounds(frame, width, height);
    if (bounds === null) return;
    const color: string = nativeUiColorWithAlpha(this.accentColor, alpha);
    this.context.strokeStyle = color;
    this.context.lineWidth = 0.8;
    this.context.beginPath();
    this.context.moveTo(bounds.left, bounds.top);
    this.context.lineTo(bounds.right, bounds.top);
    this.context.lineTo(bounds.right, bounds.bottom);
    this.context.lineTo(bounds.left, bounds.bottom);
    this.context.lineTo(bounds.left, bounds.top);
    this.context.stroke();

    const dot: number = 1.5;
    this.context.fillStyle = color;
    this.context.beginPath();
    this.context.arc(bounds.left, bounds.top, dot, 0, Math.PI * 2);
    this.context.arc(bounds.right, bounds.top, dot, 0, Math.PI * 2);
    this.context.arc(bounds.left, bounds.bottom, dot, 0, Math.PI * 2);
    this.context.arc(bounds.right, bounds.bottom, dot, 0, Math.PI * 2);
    this.context.fill();
  }

  private draw(): void {
    const width: number = this.context.width;
    const height: number = this.context.height;
    this.context.clearRect(0, 0, width, height);
    if (!this.active || !this.hostReady || width <= 0 || height <= 0) return;

    const maskFade: number = 1 - clampUnit((this.progress - 0.76) / 0.24);
    if (maskFade > 0) {
      this.context.fillStyle = nativeUiColorWithAlpha(SURFACE, 0.96 * maskFade);
      this.context.fillRect(0, 0, width, height);
    }
    const ordered: NativeUiNodeFrame[] = this.frames.slice().sort((left: NativeUiNodeFrame,
      right: NativeUiNodeFrame): number => left.y === right.y ? left.x - right.x : left.y - right.y);
    const count: number = Math.max(1, ordered.length);
    for (let index = 0; index < ordered.length; index++) {
      const bounds: NativeUiFrameBounds | null = this.frameBounds(ordered[index], width, height);
      if (bounds === null) continue;
      const threshold: number = 0.16 + index / count * 0.48;
      const revealed: number = clampUnit((this.progress - threshold) / 0.28);
      this.eraseFrame(bounds, revealed);
      const alpha: number = (1 - revealed) * maskFade * 0.55;
      if (alpha > 0) this.drawFrame(ordered[index], alpha, width, height);
    }
    if (this.progress < 1) {
      const scanY: number = height * clampUnit(this.progress);
      this.context.strokeStyle = nativeUiColorWithAlpha(this.accentColor, 0.75);
      this.context.lineWidth = 1;
      this.context.beginPath();
      this.context.moveTo(0, scanY);
      this.context.lineTo(width, scanY);
      this.context.stroke();
    }
  }

  build(): void {
    Canvas(this.context)
      .opacity(this.themeEpoch >= 0 && this.active && this.visible && !this.reduceMotion && !this.appBackgrounded ? 1 : 0)
      .width('100%')
      .height('100%')
      .position({ x: 0, y: 0 })
      .hitTestBehavior(HitTestMode.None)
      .onReady((): void => {
        this.hostReady = true;
        this.draw();
      })
      .onAreaChange((_old: Area, area: Area): void => {
        this.hostX = Number(area.globalPosition.x);
        this.hostY = Number(area.globalPosition.y);
        this.hostReady = Number(area.width) > 0 && Number(area.height) > 0;
        this.draw();
      });
  }
}
