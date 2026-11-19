// AmberPersonaMedallion — Muse 风格通用形象圆章（76vp 顶栏 / 48vp 抽屉与面板）
// 仅用原生几何环 + 属性呼吸 + 可选静态头像图；切后台或减弱动态效果时强制静帧。

import {
  ACCENT, ACCENT_INK, BG_GLASS, HOME_GLASS_EDGE, HOME_GLASS_SHADOW,
  INK, INK2, SURFACE, SURFACE2, accentEdge, accentSoftBg,
} from '../../design/tokens.ets';
import { Icon } from '../ui/Icon.ets';

export const APP_STORAGE_SOUL_AVATAR_URI: string = 'agentSoulAvatarUri';
export const APP_STORAGE_SOUL_AVATAR_PRESET: string = 'agentSoulAvatarPreset';

@Component
export struct AmberPersonaMedallion {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @StorageProp('appBackgrounded') appBackgrounded: boolean = false;
  @StorageProp('deepreadReduceMotion') reduceMotion: boolean = false;
  @StorageProp(APP_STORAGE_SOUL_AVATAR_URI) avatarUriStore: string = '';
  @StorageProp(APP_STORAGE_SOUL_AVATAR_PRESET) avatarPresetStore: string = 'muse';
  @Prop medallionSize: number = 76;
  @Prop displayName: string = 'Amber';
  @Prop isDefault: boolean = true;
  @Prop @Watch('onSendingChange') sending: boolean = false;
  @Prop customAvatarUri: string = '';
  @State private pulseScale: number = 1.0;
  @State private pulseOpacity: number = 0.35;
  private pulseTimer: number = -1;

  private activeAvatarUri(): string {
    const propUri: string = this.customAvatarUri.trim();
    if (propUri.length > 0) return propUri;
    return this.avatarUriStore.trim();
  }

  private presetIconName(): string {
    const p: string = this.avatarPresetStore.trim();
    if (p === 'cat') return 'ph_cat';
    if (p === 'moon') return 'ph_moon';
    if (p === 'flower') return 'ph_flower';
    if (p === 'robot') return 'ph_robot';
    if (p === 'ghost') return 'ph_ghost';
    return 'ph_maskHappy';
  }

  private canAnimate(): boolean {
    return this.sending && !this.appBackgrounded && !this.reduceMotion;
  }

  private syncPulseTimer(): void {
    if (!this.canAnimate()) {
      if (this.pulseTimer !== -1) {
        clearInterval(this.pulseTimer);
        this.pulseTimer = -1;
      }
      this.pulseScale = 1.0;
      this.pulseOpacity = 0.25;
      return;
    }
    if (this.pulseTimer !== -1) return;
    let phase: boolean = false;
    this.pulseTimer = setInterval((): void => {
      if (!this.canAnimate()) {
        this.syncPulseTimer();
        return;
      }
      phase = !phase;
      animateTo({ duration: 680, curve: Curve.EaseInOut }, (): void => {
        this.pulseScale = phase ? 1.08 : 0.98;
        this.pulseOpacity = phase ? 0.65 : 0.28;
      });
    }, 720);
  }

  onSendingChange(): void {
    this.syncPulseTimer();
  }

  aboutToAppear(): void {
    this.syncPulseTimer();
  }

  aboutToDisappear(): void {
    if (this.pulseTimer !== -1) {
      clearInterval(this.pulseTimer);
      this.pulseTimer = -1;
    }
  }

  private coreInitial(): string {
    const trimmed: string = this.displayName.trim();
    if (trimmed.length === 0) return 'A';
    return Array.from(trimmed)[0].toUpperCase();
  }

  build(): void {
    Stack({ alignContent: Alignment.Center }) {
      // 运行态外环柔和呼吸光晕（切后台/减弱动态时静止）
      if (this.sending) {
        Column()
          .width(this.medallionSize + 8)
          .height(this.medallionSize + 8)
          .borderRadius(999)
          .border({ width: 1.5, color: accentEdge(this.accentColorStore) })
          .backgroundColor(accentSoftBg(this.accentColorStore))
          .scale({ x: this.pulseScale, y: this.pulseScale })
          .opacity(this.pulseOpacity)
      }

      // 主圆章玻璃基座
      Stack({ alignContent: Alignment.Center }) {
        if (this.activeAvatarUri().length > 0) {
          Image(this.activeAvatarUri())
            .width(this.medallionSize - 6)
            .height(this.medallionSize - 6)
            .borderRadius(999)
            .objectFit(ImageFit.Cover)
        } else {
          // 几何内核：暖纸 + Accent 叠层
          Column()
            .width(this.medallionSize - 8)
            .height(this.medallionSize - 8)
            .borderRadius(999)
            .linearGradient({
              angle: 135,
              colors: this.isDefault
                ? [[SURFACE, 0], [SURFACE2, 0.55], [accentSoftBg(this.accentColorStore), 1]]
                : [[accentSoftBg(this.accentColorStore), 0], [this.accentColorStore, 1]],
            })

          if (this.isDefault && this.avatarPresetStore === 'muse') {
            Column()
              .width(this.medallionSize * 0.44)
              .height(this.medallionSize * 0.44)
              .borderRadius(999)
              .border({ width: 1.5, color: this.accentColorStore })
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
        color: this.sending ? this.accentColorStore : HOME_GLASS_EDGE,
      })
      .shadow({ radius: 14, color: HOME_GLASS_SHADOW, offsetY: 4 })
    }
    .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width(this.medallionSize + 8)
    .height(this.medallionSize + 8)
  }
}
