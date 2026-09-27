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
const HDR_HOLD_RATIO: number = 1.8;

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
  // 触点(组件坐标系 vp):solid 模式光斑跟随指尖
  @State private spotX: number = -1;
  @State private spotY: number = -1;
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

  private setPressed(down: boolean, touchX: number = -1, touchY: number = -1): void {
    if (this.holdTimer >= 0) {
      clearTimeout(this.holdTimer);
      this.holdTimer = -1;
    }
    if (down) {
      if (touchX >= 0 && touchY >= 0) {
        this.spotX = touchX;
        this.spotY = touchY;
      }
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

  // 本色光斑资源(accent 五色一一对应;核心 = 提亮 30% 的 accent 本色,不是白)
  private spotImage(): Resource {
    if (this.accentColor === '#5E9C6E') return $r('app.media.radial_spot_5e9c6e');
    if (this.accentColor === '#4F86D6') return $r('app.media.radial_spot_4f86d6');
    if (this.accentColor === '#9277C4') return $r('app.media.radial_spot_9277c4');
    if (this.accentColor === '#C2607A') return $r('app.media.radial_spot_c2607a');
    return $r('app.media.radial_spot_b8623a');
  }

  // solid 光斑尺寸:按钮高的 1.6 倍,只覆盖触点周围;
  // Area 异常值(包内容 Stack 可能上报父约束)一律回退 btnHeight,防满屏
  private spotSize(): number {
    const h: number = (this.glowH > 0 && this.glowH <= 200) ? this.glowH
      : (this.btnHeight > 0 ? this.btnHeight : 42);
    return Math.min(h * 1.6, 96);
  }

  private spotPosX(): number {
    const s: number = this.spotSize();
    const cx: number = this.spotX >= 0 ? this.spotX : (this.glowW > 0 ? this.glowW / 2 : s);
    return cx - s / 2;
  }

  private spotPosY(): number {
    const s: number = this.spotSize();
    const cy: number = this.spotY >= 0 ? this.spotY : (this.glowH > 0 ? this.glowH / 2 : s);
    return cy - s / 2;
  }

  build(): void {
    // 单 Stack(尺寸 = btnWidth/btnHeight 或内容):底/边/影全挂容器自身。
    // 内部禁止任何 100% 子层——包内容尺寸下 100% 会把父级撑到满屏(两次实测);
    // “白炽芯”不做覆盖层,直接换底:实底→accent 向白提亮,玻璃/无底→更白的渐变。
    Stack() {
      if (this.held) {
        if (this.isSolid()) {
          // solid(新对话):本色提亮 — 同色径向光斑(更亮的 accent 本色,不含白),
          // 媒体通道 HDR 加成;跟随触点、clip 裁进按钮剪影,灯只在按钮里亮
          Image(this.spotImage())
            .width(this.spotSize())
            .height(this.spotSize())
            .position({ x: this.spotPosX(), y: this.spotPosY() })
            .borderRadius(this.spotSize() / 2)
            .foregroundFilter(this.heldFilter())
            .hitTestBehavior(HitTestMode.None)
        } else {
          // glass/plain(搜索/齿轮/头像):整面均匀亮起;
          // 光场满幅白,形状交 borderRadius 裁切 = 按钮本体形状
          Image($r('app.media.glow_disc'))
            .width(this.glowW > 0 ? this.glowW : 1)
            .height(this.glowH > 0 ? this.glowH : 1)
            .borderRadius(this.radius)
            .foregroundFilter(this.heldFilter())
            .hitTestBehavior(HitTestMode.None)
        }
      }
      Row() {
        this.content()
      }
    }
    .constraintSize({
      minWidth: this.btnWidth,
      minHeight: this.btnHeight,
      // position 子层(指尖光斑)参与固有尺寸会把按钮撑大;封顶后光斑盒外
      // 自然溢出(默认不裁切),不再反向 inflate;prop 有精确尺寸先吃 prop
      maxWidth: this.btnWidth > 0 ? this.btnWidth : (this.glowW > 0 ? this.glowW : 300),
      maxHeight: this.btnHeight > 0 ? this.btnHeight : (this.glowH > 0 ? this.glowH : 300),
    })
    .onAreaChange((_old: Area, value: Area): void => {
      // 包内容 Stack 可能上报父约束区(曾把 FAB 光斑撑到满屏):超界值回退 prop 尺寸
      const w: number = Number(value.width);
      const h: number = Number(value.height);
      this.glowW = (w > 0 && w < 400) ? w : this.btnWidth;
      this.glowH = (h > 0 && h < 400) ? h : this.btnHeight;
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
    // 光斑/内容裁进按钮轮廓:灯只在按钮里亮,外部不出“圆圈”;
    // clip 只管子层,shadow 光晕在边界外渲染不受影响
    .clip(true)
    // 真 HDR 提亮(API 24+):媒体光场主通道,容器滤镜作叠底
    .backgroundFilter(this.held ? this.heldFilter() : uiEffect.createFilter().blur(0))
    .shadow(this.held
      ? { radius: this.haloRadius, color: this.haloColor, offsetY: 0 }
      : (this.isSolid()
        ? { radius: 14, color: accentAlpha(this.accentColor, 0.38), offsetY: 5 }
        : { radius: 10, color: HOME_GLASS_SHADOW, offsetY: 4 }))
    .scale({ x: this.pressScale, y: this.pressScale })
    .onTouch((event: TouchEvent): void => {
      if (event.type === TouchType.Down) {
        const tx: number = event.touches.length > 0 ? event.touches[0].x : -1;
        const ty: number = event.touches.length > 0 ? event.touches[0].y : -1;
        this.setPressed(true, tx, ty);
      } else if (event.type === TouchType.Up || event.type === TouchType.Cancel) {
        this.setPressed(false);
      }
    })
    .onClick((): void => { this.onTap(); })
  }
}
