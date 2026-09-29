// AmberWordmark — 点阵 AMBER 品牌字(iOS AmberPixelWordmark 逐值移植)
//
// 5×7 像素大写字母,字间 1 空列 → 29 列 × 7 行;cell 4vp 时显示 116×28。
// 每格 12% 内缩,读作柔和点阵而非实条(iOS inset 0.12 同款)。
// 渲染: Canvas fillRect;颜色随主题令牌变化时重绘。

@Component
export struct AmberWordmark {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop @Watch('drawAll') color: string = '#1B1813';
  @Prop cell: number = 4;
  private settings: RenderingContextSettings = new RenderingContextSettings(true);
  private context: CanvasRenderingContext2D = new CanvasRenderingContext2D(this.settings);

  // 字母 7 行 × 5 列位图(MSB = 左列)
  private static readonly A: number[] = [0b01110, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001];
  private static readonly M: number[] = [0b10001, 0b11011, 0b10101, 0b10001, 0b10001, 0b10001, 0b10001];
  private static readonly B: number[] = [0b11110, 0b10001, 0b10001, 0b11110, 0b10001, 0b10001, 0b11110];
  private static readonly E: number[] = [0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b11111];
  private static readonly R: number[] = [0b11110, 0b10001, 0b10001, 0b11110, 0b10100, 0b10010, 0b10001];
  private static readonly COLS: number = 29; // 5 字母 × 5 列 + 4 空列
  private static readonly ROWS: number = 7;

  private drawAll(): void {
    const letters: number[][] = [
      AmberWordmark.A, AmberWordmark.M, AmberWordmark.B, AmberWordmark.E, AmberWordmark.R,
    ];
    const cell: number = this.cell;
    const inset: number = cell * 0.12;
    const dot: number = cell - inset * 2;
    this.context.clearRect(0, 0, AmberWordmark.COLS * cell, AmberWordmark.ROWS * cell);
    this.context.fillStyle = this.color;
    for (let li = 0; li < letters.length; li++) {
      const colOffset: number = li * 6; // 5 列 + 1 空列
      for (let r = 0; r < AmberWordmark.ROWS; r++) {
        const bits: number = letters[li][r];
        for (let c = 0; c < 5; c++) {
          if ((bits & (1 << (4 - c))) !== 0) {
            this.context.fillRect((colOffset + c) * cell + inset, r * cell + inset, dot, dot);
          }
        }
      }
    }
  }

  build(): void {
    Canvas(this.context)
      
      .opacity(this.themeEpoch >= 0 ? 1 : 1).width(AmberWordmark.COLS * this.cell)
      .height(AmberWordmark.ROWS * this.cell)
      .onReady((): void => { this.drawAll(); })
      .onAreaChange((): void => { this.drawAll(); })
      .accessibilityText('Amber')
  }
}
