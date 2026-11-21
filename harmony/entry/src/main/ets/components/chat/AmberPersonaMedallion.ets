// AmberPersonaMedallion — Muse 风格四态动画形象圆章（76vp 顶栏 / 64-72vp Hero / 48vp 抽屉）
// 支持 Built-in Canvas（灵狐 / 琥珀晶 4 态 × 4 关键帧）、自定义 4×4 Sprite Sheet 切片与静态头像；
// 切后台、减弱动态效果或上层覆盖面板打开时冻结在第 0 帧并释放定时器。

import { image } from '@kit.ImageKit';
import { fileIo } from '@kit.CoreFileKit';
import { http } from '@kit.NetworkKit';
import type { AmberPersonaCanvasPreset, AmberPersonaMood, SpriteSheetLayoutSpec, SpriteSheetRegion } from '@amber/chat-domain';
import {
  PERSONA_FRAME_INTERVAL_MS,
  PERSONA_KEYFRAME_COUNT,
  computeMedallionFrameIndex,
  detectPersonaSpriteSheetLayout,
  shouldFreezeMedallionAnimation,
  spriteSheetRegionForMoodFrame,
} from '@amber/chat-domain';
import {
  ACCENT, ACCENT_INK, BG_GLASS, ERROR, HOME_GLASS_EDGE, HOME_GLASS_SHADOW,
  INK, INK2, SURFACE, SURFACE2, accentEdge, accentSoftBg,
} from '../../design/tokens.ets';
import { Icon } from '../ui/Icon.ets';

export const APP_STORAGE_SOUL_AVATAR_URI: string = 'agentSoulAvatarUri';
export const APP_STORAGE_SOUL_AVATAR_PRESET: string = 'agentSoulAvatarPreset';

const SPRITE_MODES: AmberPersonaMood[] = ['idle', 'running', 'needs_input', 'blocked'];

@Component
export struct AmberPersonaMedallion {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('accentColor') @Watch('redrawCanvas') accentColorStore: string = ACCENT;
  @StorageProp('appBackgrounded') @Watch('onAnimationGateChanged') appBackgrounded: boolean = false;
  @StorageProp('reduceMotion') @Watch('onAnimationGateChanged') reduceMotion: boolean = false;
  @StorageProp(APP_STORAGE_SOUL_AVATAR_URI) @Watch('onAvatarUriChanged') avatarUriStore: string = '';
  @StorageProp(APP_STORAGE_SOUL_AVATAR_PRESET) @Watch('redrawCanvas') avatarPresetStore: string = 'muse';
  @Prop medallionSize: number = 76;
  @Prop displayName: string = 'Amber';
  @Prop isDefault: boolean = true;
  @Prop @Watch('onAnimationGateChanged') sending: boolean = false;
  @Prop @Watch('onMoodChanged') mood: AmberPersonaMood = 'idle';
  @Prop @Watch('onAnimationGateChanged') sheetOpen: boolean = false;
  @Prop @Watch('onAvatarUriChanged') customAvatarUri: string = '';
  @Prop @Watch('redrawCanvas') previewPreset: string = '';

  @State private pulseScale: number = 1.0;
  @State private pulseOpacity: number = 0.35;
  @State private frameIndex: number = 0;
  @State private spriteReady: boolean = false;

  private frameStep: number = 0;
  private frameTimer: number = -1;
  private pulsePhase: boolean = false;
  private loadedSpriteUri: string = '';
  private spriteGeneration: number = 0;
  // row-major 4x4: index = row * 4 + col
  private spritePixelMaps: image.PixelMap[] = [];
  private canvasSettings: RenderingContextSettings = new RenderingContextSettings(true);
  private canvasCtx: CanvasRenderingContext2D = new CanvasRenderingContext2D(this.canvasSettings);
  private canvasReady: boolean = false;

  private effectiveMood(): AmberPersonaMood {
    if (this.mood !== 'idle') return this.mood;
    return this.sending ? 'running' : 'idle';
  }

  private effectivePreset(): string {
    const p: string = (this.previewPreset || this.avatarPresetStore).trim();
    return p.length > 0 ? p : 'muse';
  }

  private usesBuiltInCanvas(): boolean {
    if (this.activeAvatarUri().length > 0) return false;
    const p: string = this.effectivePreset();
    return p === 'fox' || p === 'crystal' || (p === 'muse' && this.isDefault);
  }

  private canvasPreset(): AmberPersonaCanvasPreset {
    return this.effectivePreset() === 'crystal' ? 'crystal' : 'fox';
  }

  private activeAvatarUri(): string {
    const propUri: string = this.customAvatarUri.trim();
    if (this.previewPreset.length > 0 || propUri.length > 0) return propUri;
    return this.avatarUriStore.trim();
  }

  private presetIconName(): string {
    const p: string = this.effectivePreset();
    if (p === 'star' || p === 'crystal') return 'ph_star';
    if (p === 'rocket') return 'ph_rocket';
    if (p === 'yinyang') return 'ph_yinYang';
    if (p === 'alien') return 'ph_alien';
    if (p === 'ghost') return 'ph_ghost';
    return 'ph_maskHappy';
  }

  private isFrozen(): boolean {
    return shouldFreezeMedallionAnimation(this.appBackgrounded, this.reduceMotion, this.sheetOpen);
  }

  private needsFrameLoop(): boolean {
    if (this.isFrozen()) return false;
    if (this.usesBuiltInCanvas()) return true;
    if (this.spriteReady && this.spritePixelMaps.length >= 16) return true;
    const m: AmberPersonaMood = this.effectiveMood();
    return m === 'running' || m === 'needs_input';
  }

  private haloTint(): string {
    return this.effectiveMood() === 'blocked' ? ERROR : this.accentColorStore;
  }

  private ringStrokeColor(): string {
    const m: AmberPersonaMood = this.effectiveMood();
    if (m === 'blocked') return ERROR;
    if (m === 'running' || m === 'needs_input') return this.accentColorStore;
    return HOME_GLASS_EDGE;
  }

  private stopTimer(): void {
    if (this.frameTimer !== -1) {
      clearInterval(this.frameTimer);
      this.frameTimer = -1;
    }
  }

  private syncAnimationTimer(): void {
    if (!this.needsFrameLoop()) {
      this.stopTimer();
      this.frameStep = 0;
      this.frameIndex = 0;
      this.pulseScale = 1.0;
      this.pulseOpacity = 0.25;
      this.redrawCanvas();
      return;
    }
    if (this.frameTimer !== -1) return;
    this.frameTimer = setInterval((): void => {
      if (!this.needsFrameLoop()) {
        this.syncAnimationTimer();
        return;
      }
      this.frameStep += 1;
      const nextFrame: number = computeMedallionFrameIndex(this.frameStep, PERSONA_KEYFRAME_COUNT, false);
      if (nextFrame !== this.frameIndex) {
        this.frameIndex = nextFrame;
        this.redrawCanvas();
      }
      const m: AmberPersonaMood = this.effectiveMood();
      if (m === 'running' || m === 'needs_input') {
        this.pulsePhase = !this.pulsePhase;
        this.pulseScale = this.pulsePhase ? 1.07 : 0.99;
        this.pulseOpacity = this.pulsePhase ? 0.62 : 0.28;
      } else {
        this.pulseScale = 1.0;
        this.pulseOpacity = 0.25;
      }
    }, PERSONA_FRAME_INTERVAL_MS);
  }

  onAnimationGateChanged(): void {
    this.syncAnimationTimer();
  }

  onMoodChanged(): void {
    this.redrawCanvas();
    this.syncAnimationTimer();
  }

  onAvatarUriChanged(): void {
    this.reloadSpriteSheetAsync();
    this.syncAnimationTimer();
  }

  aboutToAppear(): void {
    this.reloadSpriteSheetAsync();
    this.syncAnimationTimer();
  }

  aboutToDisappear(): void {
    this.stopTimer();
    this.spriteGeneration += 1;
    this.releaseSpriteFrames();
  }

  private releaseSpriteFrames(): void {
    for (const pm of this.spritePixelMaps) {
      try { pm.release(); } catch (_e) {}
    }
    this.spritePixelMaps = [];
    this.spriteReady = false;
    this.loadedSpriteUri = '';
  }

  private async decodeImageSource(uri: string): Promise<image.ImageSource | null> {
    if (uri.startsWith('file://')) {
      return image.createImageSource(uri.substring('file://'.length));
    }
    if (uri.startsWith('/')) {
      return image.createImageSource(uri);
    }
    if (uri.startsWith('http://') || uri.startsWith('https://')) {
      const req: http.HttpRequest = http.createHttp();
      try {
        const res: http.HttpResponse = await req.request(uri, {
          method: http.RequestMethod.GET,
          expectDataType: http.HttpDataType.ARRAY_BUFFER,
          connectTimeout: 8000,
          readTimeout: 12000,
          maxLimit: 10 * 1024 * 1024,
        });
        if (res.responseCode >= 200 && res.responseCode < 300 && res.result instanceof ArrayBuffer) {
          return image.createImageSource(res.result as ArrayBuffer);
        }
      } finally {
        req.destroy();
      }
    }
    try {
      if (fileIo.accessSync(uri)) return image.createImageSource(uri);
    } catch (_e) {}
    return null;
  }

  private reloadSpriteSheetAsync(): void {
    const uri: string = this.activeAvatarUri();
    if (uri.length === 0) {
      if (this.spritePixelMaps.length > 0) this.releaseSpriteFrames();
      return;
    }
    if (uri === this.loadedSpriteUri && this.spriteReady) return;
    const gen: number = ++this.spriteGeneration;
    this.releaseSpriteFrames();
    this.loadedSpriteUri = uri;
    this.loadSpriteFramesForUri(uri, gen).catch((): void => {});
  }

  private async loadSpriteFramesForUri(uri: string, gen: number): Promise<void> {
    const source: image.ImageSource | null = await this.decodeImageSource(uri);
    if (source === null) return;
    const frames: image.PixelMap[] = [];
    try {
      const info: image.ImageInfo = await source.getImageInfo();
      const spec: SpriteSheetLayoutSpec = detectPersonaSpriteSheetLayout(info.size.width, info.size.height);
      if (spec.kind !== 'quad4x4' || gen !== this.spriteGeneration) return;
      for (const moodKey of SPRITE_MODES) {
        for (let f: number = 0; f < 4; f++) {
          if (gen !== this.spriteGeneration) {
            for (const pm of frames) { try { pm.release(); } catch (_e) {} }
            return;
          }
          const region: SpriteSheetRegion = spriteSheetRegionForMoodFrame(spec, moodKey, f);
          const pm: image.PixelMap = await source.createPixelMap({
            desiredRegion: {
              size: { width: region.width, height: region.height },
              x: region.x,
              y: region.y,
            },
          });
          frames.push(pm);
        }
      }
      if (gen !== this.spriteGeneration) {
        for (const pm of frames) { try { pm.release(); } catch (_e) {} }
        return;
      }
      this.spritePixelMaps = frames;
      this.spriteReady = true;
      this.syncAnimationTimer();
    } finally {
      try { await source.release(); } catch (_e) {}
    }
  }

  private currentSpritePixelMap(): image.PixelMap | null {
    if (!this.spriteReady || this.spritePixelMaps.length < 16) return null;
    const m: AmberPersonaMood = this.effectiveMood();
    const row: number = m === 'running' ? 1 : m === 'needs_input' ? 2 : m === 'blocked' ? 3 : 0;
    const col: number = this.isFrozen() ? 0 : (this.frameIndex % 4);
    return this.spritePixelMaps[row * 4 + col] ?? null;
  }

  private redrawCanvas(): void {
    if (!this.canvasReady || !this.usesBuiltInCanvas()) return;
    const size: number = Math.max(16, this.medallionSize - 8);
    const ctx: CanvasRenderingContext2D = this.canvasCtx;
    ctx.clearRect(0, 0, size, size);

    const m: AmberPersonaMood = this.effectiveMood();
    const kf: number = this.isFrozen() ? 0 : (this.frameIndex % PERSONA_KEYFRAME_COUNT);
    const tint: string = m === 'blocked' ? ERROR : this.accentColorStore;
    const cx: number = size * 0.5;
    const cy: number = size * 0.54;
    const bob: number = m === 'running'
      ? (kf === 1 ? -1.8 : kf === 2 ? -2.4 : kf === 3 ? -1.0 : 0)
      : (kf === 1 ? -0.6 : kf === 2 ? -0.9 : kf === 3 ? -0.4 : 0);
    const shakeX: number = m === 'blocked' ? (kf === 1 ? -1.6 : kf === 3 ? 1.6 : 0) : 0;
    const blink: boolean = m === 'idle' && kf === 2;

    ctx.save();
    ctx.translate(cx + shakeX, cy + bob);

    if (this.canvasPreset() === 'crystal') {
      const r: number = size * 0.30;
      const rot: number = m === 'running' ? (kf * 0.14 - 0.18) : (kf === 1 ? 0.05 : kf === 3 ? -0.05 : 0);
      ctx.rotate(rot);
      ctx.fillStyle = tint;
      ctx.beginPath();
      ctx.moveTo(0, -r * 1.15);
      ctx.lineTo(r * 0.78, -r * 0.2);
      ctx.lineTo(r * 0.52, r * 0.92);
      ctx.lineTo(-r * 0.52, r * 0.92);
      ctx.lineTo(-r * 0.78, -r * 0.2);
      ctx.closePath();
      ctx.fill();

      ctx.strokeStyle = 'rgba(255,255,255,0.65)';
      ctx.lineWidth = 1.3;
      ctx.beginPath();
      ctx.moveTo(0, -r * 1.15);
      ctx.lineTo(0, r * 0.92);
      ctx.moveTo(-r * 0.78, -r * 0.2);
      ctx.lineTo(r * 0.78, -r * 0.2);
      ctx.stroke();

      if (m === 'needs_input' || m === 'running') {
        ctx.fillStyle = '#FFFFFF';
        const orbY: number = -r * 0.48;
        ctx.beginPath();
        ctx.arc(0, orbY, kf % 2 === 0 ? 2.4 : 3.2, 0, Math.PI * 2);
        ctx.fill();
      }
      ctx.restore();
      return;
    }

    // 灵狐轮廓（4 态 × 4 关键帧）
    const r: number = size * 0.27;
    const tilt: number = m === 'needs_input' ? (kf === 1 || kf === 2 ? -0.09 : 0.03) : 0;
    ctx.rotate(tilt);

    const earLift: number = kf === 1 ? -1.4 : kf === 2 ? -2.0 : 0;
    ctx.fillStyle = tint;
    ctx.beginPath();
    ctx.moveTo(-r * 0.85, -r * 0.1);
    ctx.lineTo(-r * 1.05, -r * 1.15 + earLift);
    ctx.lineTo(-r * 0.2, -r * 0.65);
    ctx.closePath();
    ctx.fill();

    ctx.beginPath();
    ctx.moveTo(r * 0.85, -r * 0.1);
    ctx.lineTo(r * 1.05, -r * 1.15 + (m === 'needs_input' ? -earLift : earLift));
    ctx.lineTo(r * 0.2, -r * 0.65);
    ctx.closePath();
    ctx.fill();

    ctx.beginPath();
    ctx.moveTo(0, -r * 0.8);
    ctx.quadraticCurveTo(r * 1.15, -r * 0.3, r * 0.78, r * 0.55);
    ctx.lineTo(0, r * 0.96);
    ctx.lineTo(-r * 0.78, r * 0.55);
    ctx.quadraticCurveTo(-r * 1.15, -r * 0.3, 0, -r * 0.8);
    ctx.fill();

    ctx.fillStyle = 'rgba(255,255,255,0.82)';
    ctx.beginPath();
    ctx.moveTo(-r * 0.55, r * 0.25);
    ctx.lineTo(0, r * 0.94);
    ctx.lineTo(r * 0.55, r * 0.25);
    ctx.quadraticCurveTo(0, r * 0.45, -r * 0.55, r * 0.25);
    ctx.fill();

    ctx.strokeStyle = INK;
    ctx.fillStyle = INK;
    ctx.lineWidth = 1.6;
    const eyeY: number = -r * 0.05;
    const eyeX: number = r * 0.36;
    if (m === 'blocked') {
      ctx.beginPath();
      ctx.moveTo(-eyeX - 2.2, eyeY - 2.2); ctx.lineTo(-eyeX + 2.2, eyeY + 2.2);
      ctx.moveTo(-eyeX + 2.2, eyeY - 2.2); ctx.lineTo(-eyeX - 2.2, eyeY + 2.2);
      ctx.moveTo(eyeX - 2.2, eyeY - 2.2); ctx.lineTo(eyeX + 2.2, eyeY + 2.2);
      ctx.moveTo(eyeX + 2.2, eyeY - 2.2); ctx.lineTo(eyeX - 2.2, eyeY + 2.2);
      ctx.stroke();
    } else if (blink) {
      ctx.beginPath();
      ctx.moveTo(-eyeX - 2.5, eyeY); ctx.lineTo(-eyeX + 2.5, eyeY);
      ctx.moveTo(eyeX - 2.5, eyeY); ctx.lineTo(eyeX + 2.5, eyeY);
      ctx.stroke();
    } else {
      ctx.beginPath();
      ctx.arc(-eyeX, eyeY, m === 'running' ? 2.4 : 2.1, 0, Math.PI * 2);
      ctx.arc(eyeX, eyeY, m === 'running' ? 2.4 : 2.1, 0, Math.PI * 2);
      ctx.fill();
    }

    ctx.fillStyle = INK;
    ctx.beginPath();
    ctx.arc(0, r * 0.42, 1.8, 0, Math.PI * 2);
    ctx.fill();

    ctx.restore();
  }

  private coreInitial(): string {
    const trimmed: string = this.displayName.trim();
    if (trimmed.length === 0) return 'A';
    return Array.from(trimmed)[0].toUpperCase();
  }

  build(): void {
    Stack({ alignContent: Alignment.Center }) {
      if (this.effectiveMood() === 'running' || this.effectiveMood() === 'needs_input' || this.effectiveMood() === 'blocked') {
        Column()
          .width(this.medallionSize + 8)
          .height(this.medallionSize + 8)
          .borderRadius(999)
          .border({ width: 1.5, color: accentEdge(this.haloTint()) })
          .backgroundColor(accentSoftBg(this.haloTint()))
          .scale({ x: this.pulseScale, y: this.pulseScale })
          .opacity(this.pulseOpacity)
      }

      Stack({ alignContent: Alignment.Center }) {
        if (this.activeAvatarUri().length > 0 && this.currentSpritePixelMap() !== null) {
          Image(this.currentSpritePixelMap()!)
            .width(this.medallionSize - 6)
            .height(this.medallionSize - 6)
            .borderRadius(999)
            .objectFit(ImageFit.Cover)
        } else if (this.activeAvatarUri().length > 0) {
          Image(this.activeAvatarUri())
            .width(this.medallionSize - 6)
            .height(this.medallionSize - 6)
            .borderRadius(999)
            .objectFit(ImageFit.Cover)
        } else {
          Column()
            .width(this.medallionSize - 8)
            .height(this.medallionSize - 8)
            .borderRadius(999)
            .linearGradient({
              angle: 135,
              colors: this.isDefault
                ? [[SURFACE, 0], [SURFACE2, 0.55], [accentSoftBg(this.haloTint()), 1]]
                : [[accentSoftBg(this.haloTint()), 0], [this.haloTint(), 1]],
            })

          if (this.usesBuiltInCanvas()) {
            Canvas(this.canvasCtx)
              .width(this.medallionSize - 8)
              .height(this.medallionSize - 8)
              .onReady((): void => {
                this.canvasReady = true;
                this.redrawCanvas();
              })
          } else if (this.isDefault && this.effectivePreset() === 'muse') {
            Column()
              .width(this.medallionSize * 0.44)
              .height(this.medallionSize * 0.44)
              .borderRadius(999)
              .border({ width: 1.5, color: this.haloTint() })
              .opacity(0.6)
            Text(this.coreInitial())
              .fontSize(Math.round(this.medallionSize * 0.34))
              .fontWeight(FontWeight.Bold)
              .fontColor(INK)
          } else {
            Icon({
              name: this.presetIconName(),
              iconSize: Math.round(this.medallionSize * 0.42),
              color: this.isDefault ? INK2 : ACCENT_INK,
            })
          }
        }
      }
      .width(this.medallionSize)
      .height(this.medallionSize)
      .borderRadius(999)
      .backgroundColor(this.appBackgrounded ? BG_GLASS : SURFACE)
      .backgroundBlurStyle(
        this.appBackgrounded ? BlurStyle.NONE : BlurStyle.Thin,
        { colorMode: ThemeColorMode.SYSTEM, adaptiveColor: AdaptiveColor.DEFAULT, scale: 0.6 },
      )
      .border({
        width: 1.5,
        color: this.ringStrokeColor(),
      })
      .shadow({ radius: 14, color: HOME_GLASS_SHADOW, offsetY: 4 })
    }
    .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width(this.medallionSize + 8)
    .height(this.medallionSize + 8)
  }
}
