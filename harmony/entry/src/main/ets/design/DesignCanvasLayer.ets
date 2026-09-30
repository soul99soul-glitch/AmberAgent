// design/DesignCanvasLayer.ets — 跨平台 design 画布纹理层(patterns 优先,dotGrid 回退)
//
// 用法(页面统一两行集成):
//   1) 根容器背景由 .backgroundColor(BG) 改为
//      .linearGradient({ angle: currentCanvasGradientAngle(), colors: canvasGradientColors() })
//   2) 在 epoch ForEach 内容首子节点放 DesignCanvasLayer({ surface: 'home' | 'shell' | 'app' })
//      (随页面 epoch 重挂载,自动重读当前包;无纹理时零绘制)
// canvasScope 作用域(amber.theme.pack v1):homeOnly → 仅 home 面;
//   shell → home + shell(设置树);appWide → 全部已集成面。
// 绘制几何:iOS AmberThemeDesignPatternRenderer 逐值(见 ChatListPage 初版移植);
//   opacity 折进 #AARRGGBB(不依赖 globalAlpha)。
import { HOME_DOT } from './tokens.ets';
import { currentCanvasPatterns, currentCanvasScope } from './theme_packs.ets';
import type { ThemePackPattern } from './theme_packs.ets';

@Component
export struct DesignCanvasLayer {
  // 调用面:home(会话首页)/ shell(设置树)/ app(其余集成面);默认 app
  surface: string = 'app';
  private canvasSettings: RenderingContextSettings = new RenderingContextSettings(true);
  private ctx: CanvasRenderingContext2D = new CanvasRenderingContext2D(this.canvasSettings);

  private scopeVisible(): boolean {
    const scope: string = currentCanvasScope();
    if (scope === 'appWide') return true;
    if (scope === 'shell') return this.surface === 'home' || this.surface === 'shell';
    return this.surface === 'home';
  }

  private redraw(width: number, height: number): void {
    const ctx: CanvasRenderingContext2D = this.ctx;
    ctx.clearRect(0, 0, width, height);
    if (!this.scopeVisible()) return;
    const patterns: ThemePackPattern[] = currentCanvasPatterns();
    if (patterns.length > 0) {
      for (const p of patterns) {
        this.drawPatternLayer(ctx, p, width, height);
      }
      return;
    }
    // dotGrid 回退(仅 home 面;iOS AmberDotGridOverlay:间距 18,点径 1.3)
    const dots: boolean = AppStorage.get<boolean>('homeDotGrid') ?? false;
    if (!dots || this.surface !== 'home') return;
    ctx.fillStyle = HOME_DOT;
    for (let y = 9; y < height; y += 18) {
      for (let x = 9; x < width; x += 18) {
        ctx.beginPath();
        ctx.arc(x, y, 0.65, 0, Math.PI * 2);
        ctx.fill();
      }
    }
  }

  private drawPatternLayer(ctx: CanvasRenderingContext2D, p: ThemePackPattern,
    width: number, height: number): void {
    if (width <= 0 || height <= 0) return;
    const spacing: number = p.spacing;
    const markSize: number = p.size;
    const lineWidth: number = Math.max(0.5, markSize * 0.32);
    const columns: number = Math.max(0, Math.ceil(width / spacing));
    const rows: number = Math.max(0, Math.ceil(height / spacing));
    const alpha: number = Math.round(Math.min(Math.max(p.opacity, 0), 0.3) * 255);
    const color: string = `#${alpha.toString(16).padStart(2, '0').toUpperCase()}${p.color.substring(1)}`;

    if (p.kind === 'dots') {
      ctx.fillStyle = color;
      for (let row = 0; row <= rows; row++) {
        for (let column = 0; column <= columns; column++) {
          ctx.beginPath();
          ctx.arc(column * spacing, row * spacing, markSize, 0, Math.PI * 2);
          ctx.fill();
        }
      }
      return;
    }
    ctx.strokeStyle = color;
    ctx.lineWidth = lineWidth;
    if (p.kind === 'grid') {
      ctx.beginPath();
      for (let column = 0; column <= columns; column++) {
        ctx.moveTo(column * spacing, 0);
        ctx.lineTo(column * spacing, height);
      }
      for (let row = 0; row <= rows; row++) {
        ctx.moveTo(0, row * spacing);
        ctx.lineTo(width, row * spacing);
      }
      ctx.stroke();
      return;
    }
    if (p.kind === 'diagonal') {
      const count: number = Math.max(0, Math.ceil((width + height) / spacing)) + 1;
      ctx.beginPath();
      for (let index = 0; index <= count; index++) {
        const x: number = -height + index * spacing;
        ctx.moveTo(x, height);
        ctx.lineTo(x + height, 0);
      }
      ctx.stroke();
      return;
    }
    if (p.kind === 'crosses') {
      ctx.beginPath();
      for (let row = 0; row <= rows; row++) {
        for (let column = 0; column <= columns; column++) {
          const x: number = column * spacing;
          const y: number = row * spacing;
          ctx.moveTo(x - markSize, y);
          ctx.lineTo(x + markSize, y);
          ctx.moveTo(x, y - markSize);
          ctx.lineTo(x, y + markSize);
        }
      }
      ctx.stroke();
      return;
    }
    if (p.kind === 'waves') {
      const wavelength: number = Math.max(spacing * 2, 1);
      const amplitude: number = Math.max(markSize * 1.5, 0.75);
      const sampleWidth: number = Math.max(wavelength / 6, 1);
      const sampleCount: number = Math.max(1, Math.min(512, Math.ceil(width / sampleWidth)));
      for (let row = 0; row <= rows; row++) {
        const baseline: number = row * spacing;
        ctx.beginPath();
        for (let sample = 0; sample <= sampleCount; sample++) {
          const x: number = sample / sampleCount * width;
          const y: number = baseline + Math.sin(x / wavelength * 2 * Math.PI) * amplitude;
          if (sample === 0) {
            ctx.moveTo(x, y);
          } else {
            ctx.lineTo(x, y);
          }
        }
        ctx.stroke();
      }
      return;
    }
    if (p.kind === 'rings') {
      const radius: number = Math.max(markSize * 2, 0.5);
      for (let row = 0; row <= rows; row++) {
        for (let column = 0; column <= columns; column++) {
          ctx.beginPath();
          ctx.arc(column * spacing, row * spacing, radius, 0, Math.PI * 2);
          ctx.stroke();
        }
      }
      return;
    }
  }

  build(): void {
    Canvas(this.ctx)
      .width('100%')
      .height('100%')
      .position({ x: 0, y: 0 })
      .hitTestBehavior(HitTestMode.None)
      .onReady((): void => {
        this.redraw(this.ctx.width, this.ctx.height);
      })
      .onAreaChange((_old: Area, value: Area): void => {
        this.redraw(Number(value.width), Number(value.height));
      })
  }
}
