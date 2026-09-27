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
import { HOME_GLASS_BOTTOM, HOME_GLASS_EDGE, HOME_GLASS_SHADOW, HOME_GLASS_TOP } from '../../design/tokens.ets';

const HOLD_DELAY_MS: number = 170;

const accentAlpha = (hex: string, alpha: number): string => {
  const value: string = hex.replace('#', '');
  if (value.length !== 6) return hex;
  const r: number = parseInt(value.substring(0, 2), 16);
  const g: number = parseInt(value.substring(2, 4), 16);
  const b: number = parseInt(value.substring(4, 6), 16);
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
};

// accent 向白提亮(f=0..1):按住时实底按钮的“高光”色
const lightenHex = (hex: string, f: number): string => {
  const value: string = hex.replace('#', '');
  if (value.length !== 6) return hex;
  const r: number = Math.round(parseInt(value.substring(0, 2), 16) + (255 - parseInt(value.substring(0, 2), 16)) * f);
  const g: number = Math.round(parseInt(value.substring(2, 4), 16) + (255 - parseInt(value.substring(2, 4), 16)) * f);
  const b: number = Math.round(parseInt(value.substring(4, 6), 16) + (255 - parseInt(value.substring(4, 6), 16)) * f);
  return `#${r.toString(16).padStart(2, '0')}${g.toString(16).padStart(2, '0')}${b.toString(16).padStart(2, '0')}`;
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
  onTap: () => void = (): void => {};
  @BuilderParam content: () => void = this.emptyContent;
  @State private pressScale: number = 1;
  @State private held: boolean = false;
  private holdTimer: number = -1;

  @Builder
  emptyContent(): void {
  }

  private isSolid(): boolean {
    return this.bgMode === 'solid';
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
      Row() {
        this.content()
      }
    }
    .constraintSize({ minWidth: this.btnWidth, minHeight: this.btnHeight })
    .borderRadius(this.radius)
    .linearGradient({
      direction: GradientDirection.Bottom,
      colors: this.held
        ? (this.isSolid()
          ? [[lightenHex(this.accentColor, 0.38), 0], [lightenHex(this.accentColor, 0.38), 1]]
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
    .shadow(this.held
      ? { radius: 26, color: this.haloColor, offsetY: 0 }
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
