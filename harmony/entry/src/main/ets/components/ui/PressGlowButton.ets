// PressGlowButton — 胶囊/圆形按钮的按压反馈容器(首页头部控制簇/FAB 同款)
//
// 两段反馈:
//   按下(down): 整体放大 1.06, springCurve 回弹(“点按放大”)
//   按住(≥170ms 未抬起): “高光” — 底向白提亮(实底 accent→浅,玻璃→更白)
//     + 中性白光晕;不引入强调色杂边。注:系统 HDR 提亮(uiEffect
//     hdrBrightnessRatio)实测被系统对非媒体场景静默拒绝,此为拟态方案
//   抬起/取消: 全部回落
//
// 用法: PressGlowButton({ radius: 19, onTap: ... , content: ... })
//   尺寸: btnWidth/btnHeight prop(0 = 包内容,如 FAB);别在调用点挂 .width/.height
//   底: bgMode 'glass' 玻璃渐变+细边(默认) | 'solid' accent 实底 | 'plain' 无底(内容自带)

import { curves } from '@kit.ArkUI';
import { uiEffect } from '@kit.ArkGraphics2D';
import { HOME_GLASS_BOTTOM, HOME_GLASS_EDGE, HOME_GLASS_SHADOW, HOME_GLASS_TOP } from '../../design/tokens.ets';

const HOLD_DELAY_MS: number = 170;
// 按住 HDR 提亮倍数(hdrBrightnessRatio24+,设备上限=峰值亮度/SDR 白,超出自动截断)
const HDR_HOLD_RATIO: number = 2.5;

const accentAlpha = (hex: string, alpha: number): string => {
  const value: string = hex.replace('#', '');
  if (value.length !== 6) return hex;
  const r: number = parseInt(value.substring(0, 2), 16);
  const g: number = parseInt(value.substring(2, 4), 16);
  const b: number = parseInt(value.substring(4, 6), 16);
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
};

@Component
export struct PressGlowButton {
  @Prop accentColor: string = '#B8623A';
  @Prop radius: number = 19;
  @Prop bgMode: string = 'glass';
  @Prop btnWidth: number = 0;
  @Prop btnHeight: number = 0;
  // 按住时的光晕颜色(中性白系;浅色 0.9 / 深色由调用点传 0.3,勿用 accent)
  @Prop haloColor: string = 'rgba(255, 255, 255, 0.9)';
  // 光晕半径(FAB 需更大辐射范围时由调用点加大)
  @Prop haloRadius: number = 26;
  onTap: () => void = (): void => {};
  @BuilderParam content: () => void = this.emptyContent;
  @State private pressScale: number = 1;
  @State private held: boolean = false;
  @State private glowW: number = 0;
  @State private glowH: number = 0;
  private holdTimer: number = -1;

  @Builder
  emptyContent(): void {
  }

  private isSolid(): boolean {
    return this.bgMode === 'solid';
  }

  // HDR 提亮滤镜(held 时):hdrBrightnessRatio 起始于 API 24,低版本静默/异常;
  // try/catch 兜底退回 blur(0) 空滤镜,保持属性常量挂载、按 held 换内容
  private heldFilter(): uiEffect.Filter {
    try {
      return uiEffect.createFilter().hdrBrightnessRatio(HDR_HOLD_RATIO);
    } catch (_e) {
      return uiEffect.createFilter().blur(0);
    }
  }

  private setPressed(down: boolean): void {
    if (this.holdTimer >= 0) {
      clearTimeout(this.holdTimer);
      this.holdTimer = -1;
    }
    if (down) {
      animateTo({ duration: 300, curve: curves.springCurve(0, 1, 200, 22) }, (): void => {
        this.pressScale = 1.06;
      });
      this.holdTimer = setTimeout((): void => {
        animateTo({ duration: 180, curve: Curve.EaseOut }, (): void => {
          this.held = true;
        });
      }, HOLD_DELAY_MS);
    } else {
      animateTo({ duration: 320, curve: curves.springCurve(0, 1, 180, 24) }, (): void => {
        this.pressScale = 1;
      });
      animateTo({ duration: 200, curve: Curve.EaseIn }, (): void => {
        this.held = false;
      });
    }
  }

  build(): void {
    // 单 Stack(尺寸 = btnWidth/btnHeight 或内容):底/边/影全挂容器自身。
    // 内部禁止任何 100% 子层——包内容尺寸下 100% 会把父级撑到满屏(两次实测);
    // “白炽芯”不做覆盖层,直接换底:实底→accent 向白提亮,玻璃/无底→更白的渐变。
    Stack() {
      // HDR 辉光层(按住):平坦光场 Image 走媒体通道 + foregroundFilter HDR,
      // 整面均匀亮起;尺寸 onAreaChange 实测贴合按钮
      if (this.held) {
        Image($r('app.media.glow_disc'))
          .width(this.glowW > 0 ? this.glowW : 1)
          .height(this.glowH > 0 ? this.glowH : 1)
          .borderRadius(this.radius)
          .foregroundFilter(this.heldFilter())
          .hitTestBehavior(HitTestMode.None)
        // solid 专属:accent 透纱罩在光场上 — 白热核透过 accent 呈高亮通透,
        // 而非发白;同色 veil 尺寸与光场一致(避开 100% 子层撑满屏的坑)
        if (this.isSolid()) {
          Row()
            .width(this.glowW > 0 ? this.glowW : 1)
            .height(this.glowH > 0 ? this.glowH : 1)
            .borderRadius(this.radius)
            .backgroundColor(accentAlpha(this.accentColor, 0.55))
            .hitTestBehavior(HitTestMode.None)
        }
      }
      Row() {
        this.content()
      }
    }
    .constraintSize({ minWidth: this.btnWidth, minHeight: this.btnHeight })
    .onAreaChange((_old: Area, value: Area): void => {
      this.glowW = Number(value.width);
      this.glowH = Number(value.height);
    })
    .borderRadius(this.radius)
    .linearGradient({
      direction: GradientDirection.Bottom,
      colors: this.held
        ? (this.isSolid()
          ? [[this.accentColor, 0], [this.accentColor, 1]]
          : [['rgba(255, 255, 255, 0.95)', 0], ['rgba(255, 255, 255, 0.85)', 1]])
        : (this.bgMode === 'solid'
          ? [[this.accentColor, 0], [this.accentColor, 1]]
          : (this.bgMode === 'glass'
            ? [[HOME_GLASS_TOP, 0], [HOME_GLASS_BOTTOM, 1]]
            : [['rgba(0, 0, 0, 0)', 0], ['rgba(0, 0, 0, 0)', 1]])),
    })
    .border(this.bgMode === 'glass' && !this.held
      ? { width: 0.5, color: HOME_GLASS_EDGE }
      : { width: 0, color: Color.Transparent })
    // 真 HDR 提亮(API 24+):媒体光场主通道,容器滤镜作叠底
    .backgroundFilter(this.held ? this.heldFilter() : uiEffect.createFilter().blur(0))
    .shadow(this.held
      ? { radius: this.haloRadius, color: this.haloColor, offsetY: 0 }
      : (this.isSolid()
        ? { radius: 14, color: accentAlpha(this.accentColor, 0.38), offsetY: 5 }
        : { radius: 10, color: HOME_GLASS_SHADOW, offsetY: 4 }))
    .scale({ x: this.pressScale, y: this.pressScale })
    .onTouch((event: TouchEvent): void => {
      if (event.type === TouchType.Down) this.setPressed(true);
      else if (event.type === TouchType.Up || event.type === TouchType.Cancel) this.setPressed(false);
    })
    .onClick((): void => { this.onTap(); })
  }
}
