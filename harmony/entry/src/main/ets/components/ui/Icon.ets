// Icon — 单色系统符号图标(对齐设计系统: stroke 单色图标, currentColor)
//
// 设计系统用单色细线 SVG 图标(currentColor)。emoji 渲染成彩色图片,破坏质感。
// ArkUI 用 SymbolGlyph 渲染系统符号,单色,可指定 fontColor — 正确方案。
//
// hi_ 前缀 = hugeicons 自绘路径(Android 端 me.rerere.hugeicons.stroke 同源,
// 路径数据从 hugeicons-compose AAR 字节码重建,24×24 视窗 stroke 1.5)。
// 系统符号字形与 hugeicons 差异大(如 模型议会: 系统原子 vs Android 气泡),
// 首页宫格等对形要求高的位置用 hi_ 系列。
//
// 用法: Icon({ name: 'search', iconSize: 20, color: INK2 })
//
// 注意: 属性名用 iconSize 而非 size —— CustomComponent 基类已占用 size
//   (size?: SizeOptions),@Prop size 会与之冲突(编译错误 10505001)。
//   symbol 名以本 SDK(toolchains/id_defined.json type=symbol)实存为准。

import { display } from '@kit.ArkUI';
import { INK2 } from '../../design/tokens.ets';

// Path.commands 坐标单位是 px(非 vp):按 density 把 24×24 视窗缩放到 iconSize
function scalePathData(d: string, f: number): string {
  const tokens: string[] = d.match(/[MLHVCSQTAZmlhvcstqz]|-?\d*\.?\d+(?:e[-+]?\d+)?/g) ?? [];
  let out: string = '';
  let cmd: string = '';
  let argIdx: number = 0;
  for (const tok of tokens) {
    const isLetter: boolean = tok.charCodeAt(0) < 48 || tok.charCodeAt(0) > 57;
    if (isLetter && tok.length === 1 && tok !== '-' && tok !== '.') {
      cmd = tok;
      argIdx = 0;
      out += ' ' + tok;
      continue;
    }
    const v: number = parseFloat(tok);
    // A 命令: rx ry rot laf sf x y —— rot/laf/sf 不缩放
    const upper: string = cmd.toUpperCase();
    const noScale: boolean = (upper === 'A' && (argIdx === 2 || argIdx === 3 || argIdx === 4));
    out += ' ' + (noScale ? fmtNum(v) : fmtNum(v * f));
    argIdx++;
  }
  return out;
}

function fmtNum(v: number): string {
  const s: string = v.toFixed(2);
  if (s.endsWith('.00')) return s.substring(0, s.length - 3) || '0';
  if (s.endsWith('0')) return s.substring(0, s.length - 1);
  return s;
}

// hugeicons 24×24 stroke 路径(与 Android 首页宫格同形):
//   BookOpen01 / Archive / PencilEdit01 / Global / Message02
const HI_BOOK_OPEN: string =
  'M5.333 3C7.794 2.997 10.168 3.887 12 5.5V21C10.168 19.387 7.794 18.497 5.333 18.5C3.771 18.5 2.99 18.5 2.645 18.279C2.438 18.147 2.353 18.062 2.221 17.855C2 17.51 2 16.894 2 15.663V6.403C2 4.975 2 4.262 2.549 3.683C3.097 3.104 3.659 3.074 4.783 3.015C4.965 3.005 5.149 3 5.333 3M18.667 3C16.206 2.997 13.832 3.887 12 5.5V21C13.832 19.387 16.206 18.497 18.667 18.5C20.229 18.5 21.01 18.5 21.355 18.279C21.562 18.147 21.646 18.062 21.779 17.855C22 17.51 22 16.894 22 15.663V6.403C22 4.975 22 4.262 21.451 3.683C20.903 3.104 20.341 3.074 19.217 3.015C19.035 3.005 18.851 3 18.667 3';
const HI_APP_ARCHIVE: string =
  'M13 2H11C7.229 2 5.343 2 4.172 3.172C3 4.343 3 6.229 3 10V14C3 17.771 3 19.657 4.172 20.828C5.343 22 7.229 22 11 22H13C16.771 22 18.657 22 19.828 20.828C21 19.657 21 17.771 21 14V10C21 6.229 21 4.343 19.828 3.172C18.657 2 16.771 2 13 2M21 12H3M15 7H9M15 17H9';
const HI_PENCIL: string =
  'M15.214 5.982L16.616 4.581C17.39 3.806 18.645 3.806 19.419 4.581C20.194 5.355 20.194 6.61 19.419 7.384L18.018 8.786M15.214 5.982L6.98 14.216C5.935 15.262 5.412 15.784 5.056 16.421C4.7 17.058 4.342 18.562 4 20C5.438 19.658 6.942 19.299 7.579 18.944C8.216 18.588 8.738 18.065 9.784 17.02L18.018 8.786M15.214 5.982L18.018 8.786M11 20H17';
const HI_GLOBAL: string =
  'M22 12C22 6.477 17.523 2 12 2C6.477 2 2 6.477 2 12C2 17.523 6.477 22 12 22C17.523 22 22 17.523 22 12M20 5.699C19.065 5.766 17.868 6.128 17.038 7.203C15.538 9.144 14.039 9.306 13.039 8.659C11.54 7.688 12.8 6.116 11.04 5.262C9.893 4.705 9.733 3.19 10.372 2M2 11C2.763 11.662 3.83 12.268 5.089 12.268C7.688 12.268 8.208 12.765 8.208 14.752C8.208 16.739 8.208 16.739 8.728 18.229C9.067 19.198 9.185 20.167 8.511 21M22 13.452C21.113 12.941 20 12.731 18.873 13.54C16.718 15.09 15.231 13.806 14.562 15.089C13.576 16.978 17.096 17.571 14 22';
const HI_MESSAGE: string =
  'M14.171 20.89C18.354 20.612 21.686 17.233 21.96 12.991C22.013 12.161 22.013 11.301 21.96 10.471C21.686 6.228 18.354 2.849 14.171 2.571C12.743 2.476 11.254 2.476 9.829 2.571C5.646 2.849 2.314 6.228 2.04 10.471C1.987 11.301 1.987 12.161 2.04 12.991C2.14 14.536 2.823 15.967 3.628 17.175C4.095 18.02 3.787 19.076 3.3 19.998C2.949 20.663 2.774 20.995 2.915 21.235C3.056 21.475 3.37 21.483 3.999 21.498C5.244 21.528 6.083 21.176 6.749 20.685C7.126 20.406 7.315 20.267 7.445 20.251C7.576 20.235 7.832 20.34 8.344 20.551C8.804 20.741 9.339 20.858 9.829 20.89C11.254 20.985 12.743 20.985 14.171 20.89M11.995 12H12.004M15.991 12H16M8 12H8.009';

@Component
export struct Icon {
  @Prop name: string = 'search';
  @Prop iconSize: number = 20;
  @Prop color: string = INK2;

  // 自绘路径优先(24×24 视窗,按 iconSize 等比放大)
  private hiPath(): string {
    if (this.name === 'hi_book_open') return HI_BOOK_OPEN;
    if (this.name === 'hi_app_archive') return HI_APP_ARCHIVE;
    if (this.name === 'hi_pencil') return HI_PENCIL;
    if (this.name === 'hi_global') return HI_GLOBAL;
    if (this.name === 'hi_message') return HI_MESSAGE;
    return '';
  }

  private symbol(): Resource {
    if (this.name === 'back') return $r('sys.symbol.chevron_left');
    if (this.name === 'menu') return $r('sys.symbol.list_bullet');
    if (this.name === 'arrow_up') return $r('sys.symbol.arrow_up');
    if (this.name === 'search') return $r('sys.symbol.magnifyingglass');
    if (this.name === 'gear') return $r('sys.symbol.gearshape');
    if (this.name === 'star') return $r('sys.symbol.star');
    if (this.name === 'pin') return $r('sys.symbol.pin');
    if (this.name === 'pin_fill') return $r('sys.symbol.pin_fill');
    if (this.name === 'puzzle') return $r('sys.symbol.puzzle');
    if (this.name === 'edit') return $r('sys.symbol.square_and_pencil');
    if (this.name === 'trash') return $r('sys.symbol.trash');
    if (this.name === 'check') return $r('sys.symbol.checkmark');
    if (this.name === 'close') return $r('sys.symbol.xmark');
    if (this.name === 'plus') return $r('sys.symbol.plus');
    if (this.name === 'chevron_left') return $r('sys.symbol.chevron_left');
    if (this.name === 'chevron_right') return $r('sys.symbol.chevron_right');
    if (this.name === 'chevron_down') return $r('sys.symbol.chevron_down');
    if (this.name === 'bell') return $r('sys.symbol.bell');
    if (this.name === 'person') return $r('sys.symbol.person');
    if (this.name === 'globe') return $r('sys.symbol.worldclock');
    if (this.name === 'link') return $r('sys.symbol.link');
    if (this.name === 'info') return $r('sys.symbol.info_circle');
    if (this.name === 'shield') return $r('sys.symbol.checkmark_shield');
    if (this.name === 'eye') return $r('sys.symbol.eye');
    if (this.name === 'eye_off') return $r('sys.symbol.eye_slash');
    if (this.name === 'copy') return $r('sys.symbol.square_on_square_fill');
    if (this.name === 'retry') return $r('sys.symbol.arrow_clockwise');
    if (this.name === 'more') return $r('sys.symbol.more');
    if (this.name === 'clipboard') return $r('sys.symbol.text_clipboard');
    if (this.name === 'book') return $r('sys.symbol.book');
    if (this.name === 'cpu') return $r('sys.symbol.computer');
    if (this.name === 'brain') return $r('sys.symbol.AI_deep_thinking');
    if (this.name === 'spark') return $r('sys.symbol.wand_and_stars');
    if (this.name === 'image') return $r('sys.symbol.picture');
    if (this.name === 'clip') return $r('sys.symbol.paperclip');
    if (this.name === 'box') return $r('sys.symbol.storagebox');
    if (this.name === 'key') return $r('sys.symbol.key');
    if (this.name === 'history') return $r('sys.symbol.arrow_counterclockwise_clock');
    if (this.name === 'folder') return $r('sys.symbol.folder');
    if (this.name === 'share') return $r('sys.symbol.share');
    if (this.name === 'speaker') return $r('sys.symbol.speaker_wave_2');
    if (this.name === 'money') return $r('sys.symbol.moneybag');
    if (this.name === 'plug') return $r('sys.symbol.powercord');
    if (this.name === 'ban') return $r('sys.symbol.nosign');
    if (this.name === 'doc') return $r('sys.symbol.doc_text');
    if (this.name === 'upload') return $r('sys.symbol.upload');
    if (this.name === 'save') return $r('sys.symbol.save');
    if (this.name === 'palette') return $r('sys.symbol.paintpalette');
    if (this.name === 'clock') return $r('sys.symbol.clock');
    if (this.name === 'arrow_down_to_line') return $r('sys.symbol.arrow_down_to_line');
    return $r('sys.symbol.circle');
  }

  private scaleFactor(): number {
    // Path.commands 实测单位是 px(探针校验):按设备密度把 24 视窗缩放到 iconSize(vp)
    let density: number = 3;
    try {
      density = display.getDefaultDisplaySync().densityPixels;
    } catch (_e) {
      density = 3;
    }
    return this.iconSize * density / 24;
  }

  build(): void {
    if (this.hiPath() !== '') {
      Path()
        .commands(scalePathData(this.hiPath(), this.scaleFactor()))
        .width(this.iconSize)
        .height(this.iconSize)
        .fillOpacity(0)
        .stroke(this.color)
        .strokeWidth(1.5 * this.iconSize / 24)
        .strokeLineCap(LineCapStyle.Round)
        .strokeLineJoin(LineJoinStyle.Round)
    } else {
      SymbolGlyph(this.symbol())
        .fontSize(this.iconSize)
        .fontColor([this.color])
    }
  }
}
