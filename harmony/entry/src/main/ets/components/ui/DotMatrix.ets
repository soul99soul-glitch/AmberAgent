// DotMatrix — ASCII 点阵显示层(session-home-ascii.html 逐值移植)
//
// 源文件规则:
//   - 5×7 LED 字形(DOTFONT)/ 5×5 图标(DFEATURE)位图逐值复制,'1'=亮点
//   - 每个像素是「圆点」(borderRadius 50%),不是方块
//   - DotGlyph gap 缺省 = max(1, dotScale*0.5);DotText 字距 = dotScale*1.5,空格宽 dotScale*3
//   - 辉光(drop-shadow 2.5px currentColor)仅暗色主题且仅 glow 面(词标/功能图标/FAB);
//     浅色主题全哑光,小号点阵(日期数字/置顶钉)任何主题都哑光
// 注:prop 名用 dotScale/dotGap — scale 是 CustomComponent 保留名

import { ACCENT, INK } from '../../design/tokens.ets';

// 5×7 LED font — letters, digits, symbols. '1' = lit dot.(逐值复制)
export const DOTFONT: Record<string, string[]> = {};
DOTFONT['A'] = ['01110', '10001', '10001', '11111', '10001', '10001', '10001'];
DOTFONT['B'] = ['11110', '10001', '10001', '11110', '10001', '10001', '11110'];
DOTFONT['E'] = ['11111', '10000', '10000', '11110', '10000', '10000', '11111'];
DOTFONT['M'] = ['10001', '11011', '10101', '10101', '10001', '10001', '10001'];
DOTFONT['R'] = ['11110', '10001', '10001', '11110', '10100', '10010', '10001'];
DOTFONT['0'] = ['01110', '10001', '10011', '10101', '11001', '10001', '01110'];
DOTFONT['1'] = ['00100', '01100', '00100', '00100', '00100', '00100', '01110'];
DOTFONT['2'] = ['01110', '10001', '00001', '00010', '00100', '01000', '11111'];
DOTFONT['3'] = ['11111', '00010', '00100', '00010', '00001', '10001', '01110'];
DOTFONT['4'] = ['00010', '00110', '01010', '10010', '11111', '00010', '00010'];
DOTFONT['5'] = ['11111', '10000', '11110', '00001', '00001', '10001', '01110'];
DOTFONT['6'] = ['00110', '01000', '10000', '11110', '10001', '10001', '01110'];
DOTFONT['7'] = ['11111', '00001', '00010', '00100', '01000', '01000', '01000'];
DOTFONT['8'] = ['01110', '10001', '10001', '01110', '10001', '10001', '01110'];
DOTFONT['9'] = ['01110', '10001', '10001', '01111', '00001', '00010', '01100'];
DOTFONT[':'] = ['00000', '00110', '00110', '00000', '00110', '00110', '00000'];
DOTFONT['.'] = ['00000', '00000', '00000', '00000', '00000', '00110', '00110'];
DOTFONT['-'] = ['00000', '00000', '00000', '01110', '00000', '00000', '00000'];
DOTFONT['·'] = ['00000', '00000', '00100', '00100', '00000', '00000', '00000'];
DOTFONT['/'] = ['00001', '00010', '00010', '00100', '01000', '01000', '10000'];

// 5×5 dot bitmaps for the feature rail + FAB + pin(逐值复制)
export const DFEATURE: Record<string, string[]> = {};
DFEATURE['reader'] = ['11011', '10101', '10101', '11111', '01010'];
DFEATURE['apps'] = ['11011', '11011', '00000', '11011', '11011'];
DFEATURE['novel'] = ['00011', '00110', '00110', '01100', '11000'];
DFEATURE['webmount'] = ['01110', '10001', '10101', '10001', '01110'];
DFEATURE['council'] = ['11100', '11100', '00000', '00111', '00111'];
DFEATURE['plus'] = ['00100', '00100', '01110', '00100', '00100'];
// 45° pushpin — hollow 3×3 head, diagonal shaft
DFEATURE['pin'] = ['00111', '00101', '00111', '01000', '10000'];

@Component
export struct DotGlyph {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop rows: string[] = [];
  @Prop dotScale: number = 2;
  @Prop dotGap: number = -1; // -1 = 缺省 max(1, dotScale*0.5)
  @Prop color: string = INK;
  @Prop glow: boolean = false;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @StorageProp('darkMode') darkMode: boolean = false;

  private g(): number {
    return this.dotGap >= 0 ? this.dotGap : Math.max(1, this.dotScale * 0.5);
  }

  private dotShadow(lit: boolean): ShadowOptions {
    const on: ShadowOptions = { radius: 2.5, color: this.color, offsetX: 0, offsetY: 0 };
    const off: ShadowOptions = { radius: 0, color: '#00000000', offsetX: 0, offsetY: 0 };
    // 辉光单层:仅暗色主题 + glow 面的亮点(源 .amb[data-theme=dark] .dt-led);
    //   ArkUI shadow 按组件盒 → 落在圆点上等效 CSS drop-shadow 逐点辉光
    return lit && this.glow && this.darkMode ? on : off;
  }

  build(): void {
    Column() {
      ForEach(this.rows, (row: string): void => {
        Row() {
          ForEach(row.split(''), (c: string, i: number): void => {
            Column()
              .width(this.dotScale)
              .height(this.dotScale)
              .borderRadius(this.dotScale / 2)
              .backgroundColor(c === '1' ? this.color : Color.Transparent)
              .shadow(this.dotShadow(c === '1'))
              .margin({ right: this.g(), bottom: this.g() })
          }, (c: string, i: number): string => `${c}-${i}`)
        }
      }, (row: string, ri: number): string => `r${ri}`)
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
  }
}

// 点阵底纹:19px 网格圆点,浅 4% 墨 / 深 6.5% 墨(LED 屏熄灭像素,淡到几乎不可见)
@Component
export struct DotGround {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  private settings: RenderingContextSettings = new RenderingContextSettings(true);
  private ctx: CanvasRenderingContext2D = new CanvasRenderingContext2D(this.settings);
  @StorageProp('darkMode') @Watch('onDarkChange') darkMode: boolean = false;

  private onDarkChange(): void {
    this.draw();
  }

  private draw(): void {
    const w: number = this.ctx.width;
    const h: number = this.ctx.height;
    this.ctx.clearRect(0, 0, w, h);
    this.ctx.fillStyle = this.darkMode ? 'rgba(236,232,223,0.065)' : 'rgba(27,26,23,0.04)';
    for (let x: number = 9; x < w; x += 19) {
      for (let y: number = 9; y < h; y += 19) {
        this.ctx.beginPath();
        this.ctx.arc(x, y, 1, 0, Math.PI * 2);
        this.ctx.fill();
      }
    }
  }

  build(): void {
    Canvas(this.ctx)
      
      .opacity(this.themeEpoch >= 0 ? 1 : 1).width('100%')
      .height('100%')
      .hitTestBehavior(HitTestMode.None)
      .onReady((): void => { this.draw(); })
  }
}

@Component
export struct DotText {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop text: string = '';
  @Prop dotScale: number = 2;
  @Prop dotGap: number = -1;
  @Prop color: string = INK;
  @Prop glow: boolean = false;

  build(): void {
    Row() {
      ForEach(this.text.split(''), (ch: string, i: number): void => {
        if (ch === ' ') {
          Column().width(this.dotScale * 3)
        } else {
          DotGlyph({
            rows: DOTFONT[ch] !== undefined ? DOTFONT[ch] : DOTFONT[ch.toUpperCase()],
            dotScale: this.dotScale,
            dotGap: this.dotGap,
            color: this.color,
            glow: this.glow,
          })
            .margin({ left: i > 0 ? this.dotScale * 1.5 : 0 })
        }
      }, (ch: string, i: number): string => `${ch}-${i}`)
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .alignItems(VerticalAlign.Center)
  }
}
