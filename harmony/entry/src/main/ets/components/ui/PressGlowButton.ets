// PressGlowButton — 胶囊/圆形按钮的按压反馈容器(首页头部控制簇/FAB 同款)
//
// 两段反馈:
//   按下(down): 整体放大 1.06, springCurve 回弹(“点按放大”)
//   按住(≥170ms 未抬起): HDR 高光 — uiEffect hdrBrightnessRatio 真提亮
//     (需 ohos.permission.HDR_BRIGHTNESS;设备无 HDR 管线时静默无效,自然降级),
//     叠白炽芯(白 overlay 0.22)与 accent 强光晕(shadow 加大加亮)
//   抬起/取消: 全部回落
//
// 用法: PressGlowButton({ radius: 19, solid: false, onTap: ... }) { Icon(...) }
//   尺寸由调用点修饰符给定(.width(78).height(38));内容经 @BuilderParam 注入。

import { curves } from '@kit.ArkUI';
import { uiEffect } from '@kit.ArkGraphics2D';
import { HOME_GLASS_BOTTOM, HOME_GLASS_EDGE, HOME_GLASS_SHADOW, HOME_GLASS_TOP } from '../../design/tokens.ets';

// 按住提亮倍数(设备上限 = 峰值亮度/SDR 白,旗舰 OLED 通常 ≥2;1.8 克制不刺眼)
const HDR_HOLD_RATIO: number = 1.8;
const HOLD_DELAY_MS: number = 170;

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
  // 底模式: 'glass' 玻璃渐变+细边(默认) | 'solid' accent 实底(FAB) | 'plain' 无底(内容自带底,如头像)
  @Prop bgMode: string = 'glass';
  // 显式尺寸(0 = 由内容决定,如 FAB);别在调用点挂 .width/.height — 内部 100% 层会随之错配
  @Prop btnWidth: number = 0;
  @Prop btnHeight: number = 0;
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
    // 单 Stack:尺寸由 btnWidth/btnHeight 或内容决定;底/边/影/提亮全挂在 Stack 自身,
    // 内部禁止 100% 子层(包内容尺寸下 100% 会把父级撑到满屏)。
    // 白炽芯由 HDR 提亮本身承担(亮度超过 SDR 白即为“高光核”)。
    Stack() {
      Row() {
        this.content()
      }
    }
    .constraintSize({ minWidth: this.btnWidth, minHeight: this.btnHeight })
    .borderRadius(this.radius)
    .linearGradient({
      direction: GradientDirection.Bottom,
      colors: this.bgMode === 'solid'
        ? [[this.accentColor, 0], [this.accentColor, 1]]
        : (this.bgMode === 'glass'
          ? [[HOME_GLASS_TOP, 0], [HOME_GLASS_BOTTOM, 1]]
          : [['rgba(0, 0, 0, 0)', 0], ['rgba(0, 0, 0, 0)', 1]]),
    })
    .border(this.bgMode === 'glass' ? { width: 0.5, color: HOME_GLASS_EDGE } : { width: 0, color: Color.Transparent })
    .shadow(this.held
      ? { radius: 26, color: accentAlpha(this.accentColor, 0.8), offsetY: 0 }
      : (this.isSolid()
        ? { radius: 14, color: accentAlpha(this.accentColor, 0.38), offsetY: 5 }
        : { radius: 10, color: HOME_GLASS_SHADOW, offsetY: 4 }))
    // HDR 提亮:ratio 1.0 = 无处理;按住时 1.8 触发 HDR 渲染管线
    .compositingFilter(uiEffect.createFilter().hdrBrightnessRatio(this.held ? HDR_HOLD_RATIO : 1))
    .scale({ x: this.pressScale, y: this.pressScale })
    .onTouch((event: TouchEvent): void => {
      if (event.type === TouchType.Down) this.setPressed(true);
      else if (event.type === TouchType.Up || event.type === TouchType.Cancel) this.setPressed(false);
    })
    .onClick((): void => { this.onTap(); })
  }
}
