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
// ph_ 前缀 = phosphor-icons fill 路径(iOS HomePhosphor 同源,256×256 视窗实心;
// 路径表 phosphor_paths.ets 由 harmony/scripts/gen-phosphor-icons.mjs 生成)。
// 首页头部/快捷入口/会话行图标与 iOS 对齐时用 ph_ 系列。
//
// 用法: Icon({ name: 'search', iconSize: 20, color: INK2 })
//
// 注意: 属性名用 iconSize 而非 size —— CustomComponent 基类已占用 size
//   (size?: SizeOptions),@Prop size 会与之冲突(编译错误 10505001)。
//   symbol 名以本 SDK(toolchains/id_defined.json type=symbol)实存为准。

import { display } from '@kit.ArkUI';
import { INK2 } from '../../design/tokens.ets';
import { phosphorPath } from './phosphor_paths.ets';

// Path.commands 坐标单位是 px(非 vp):按 density 把视窗缩放到 iconSize
function scalePathData(d: string, f: number): string {
  const tokens: string[] = d.match(/[MLHVCSQTAZmlhvcstqza]|-?\d*\.?\d+(?:e[-+]?\d+)?/g) ?? [];
  // 每类命令的固定参数个数;隐式重复段按组循环(phosphor 圆角弧 A 命令重灾区:
  // 一段 7 参,后续重复段不重复字母,rot/laf/sf 仍需按段内位置豁免缩放)
  const groupSize = (cmd: string): number => {
    const u: string = cmd.toUpperCase();
    if (u === 'M' || u === 'L' || u === 'T') return 2;
    if (u === 'H' || u === 'V') return 1;
    if (u === 'C') return 6;
    if (u === 'S' || u === 'Q') return 4;
    if (u === 'A') return 7;
    return 0;
  };
  let out: string = '';
  let cmd: string = '';
  let group: number = 0;
  let argIdx: number = 0;
  for (const tok of tokens) {
    const code: number = tok.charCodeAt(0);
    const isLetter: boolean = (code >= 65 && code <= 90) || (code >= 97 && code <= 122);
    if (isLetter) {
      cmd = tok;
      group = groupSize(cmd);
      argIdx = 0;
      out += ' ' + tok;
      continue;
    }
    const v: number = parseFloat(tok);
    // A 命令: rx ry rot laf sf x y —— 段内 rot/laf/sf(第 3/4/5 参)不缩放
    const within: number = group > 0 ? argIdx % group : 0;
    const noScale: boolean = cmd.toUpperCase() === 'A' && (within === 2 || within === 3 || within === 4);
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
const HI_CAMERA: string = 'M12.697 3.5H11.303C10.588 3.5 10.231 3.5 9.911 3.612C9.715 3.68 9.531 3.779 9.366 3.904C9.095 4.108 8.897 4.405 8.5 5L8.5 5C8.297 5.305 7.998 5.753 7.879 5.879C7.583 6.189 7.196 6.397 6.773 6.47C6.603 6.5 6.42 6.5 6.054 6.5C5.074 6.5 4.584 6.5 4.183 6.613C3.181 6.897 2.397 7.681 2.114 8.683C2 9.084 2 9.574 2 10.554V14.5C2 17.328 2 18.743 2.879 19.621C3.758 20.5 5.172 20.5 8 20.5H16C18.829 20.5 20.243 20.5 21.122 19.621C22 18.743 22 17.328 22 14.5V10.554C22 9.574 22 9.084 21.887 8.683C21.603 7.681 20.82 6.897 19.817 6.613C19.417 6.5 18.927 6.5 17.947 6.5C17.58 6.5 17.397 6.5 17.227 6.47C16.805 6.397 16.417 6.189 16.122 5.879C16.002 5.753 15.703 5.305 15.5 5C15.104 4.405 14.906 4.108 14.635 3.904C14.469 3.779 14.285 3.68 14.09 3.612C13.769 3.5 13.412 3.5 12.697 3.5M16 13C16 15.209 14.209 17 12 17C9.791 17 8 15.209 8 13C8 10.791 9.791 9 12 9C14.209 9 16 10.791 16 13M19 9.5V9.51';
const HI_PHOTOS: string = 'M3 16L7.47 11.53C7.809 11.191 8.27 11 8.75 11C9.23 11 9.691 11.191 10.03 11.53L14 15.5M15.5 17L14 15.5M21 16L18.53 13.53C18.191 13.191 17.73 13 17.25 13C16.77 13 16.309 13.191 15.97 13.53L14 15.5M15.5 8C15.776 8 16 7.776 16 7.5C16 7.224 15.776 7 15.5 7M15.5 8C15.224 8 15 7.776 15 7.5C15 7.224 15.224 7 15.5 7M15.5 8V7M3.698 19.747C2.5 18.345 2.5 16.23 2.5 12C2.5 7.77 2.5 5.655 3.698 4.253C3.868 4.054 4.054 3.868 4.253 3.698C5.655 2.5 7.77 2.5 12 2.5C16.23 2.5 18.345 2.5 19.747 3.698C19.946 3.868 20.132 4.054 20.302 4.253C21.5 5.655 21.5 7.77 21.5 12C21.5 16.23 21.5 18.345 20.302 19.747C20.132 19.946 19.946 20.132 19.747 20.302C18.345 21.5 16.23 21.5 12 21.5C7.77 21.5 5.655 21.5 4.253 20.302C4.054 20.132 3.868 19.946 3.698 19.747';
const HI_FILES: string = 'M14.5 5L12.5 5C9.672 5 8.257 5 7.379 5.879C6.5 6.757 6.5 8.172 6.5 11L6.5 16C6.5 18.828 6.5 20.243 7.379 21.121C8.257 22 9.672 22 12.5 22H13.843C14.661 22 15.069 22 15.437 21.848C15.804 21.695 16.094 21.406 16.672 20.828L19.328 18.172C19.906 17.594 20.195 17.305 20.348 16.937C20.5 16.569 20.5 16.161 20.5 15.343V11C20.5 8.172 20.5 6.757 19.621 5.879C18.743 5 17.328 5 14.5 5M15 21.5L15 20.5C15 18.614 15 17.672 15.586 17.086C16.172 16.5 17.114 16.5 19 16.5L20 16.5M6.5 19C4.843 19 3.5 17.657 3.5 16L3.5 8C3.5 5.172 3.5 3.757 4.379 2.879C5.257 2 6.672 2 9.5 2L14.5 2C16.157 2 17.5 3.343 17.5 5M10.001 13L14.001 13M10.001 9L17.001 9';
const HI_MESSAGE: string =
  'M14.171 20.89C18.354 20.612 21.686 17.233 21.96 12.991C22.013 12.161 22.013 11.301 21.96 10.471C21.686 6.228 18.354 2.849 14.171 2.571C12.743 2.476 11.254 2.476 9.829 2.571C5.646 2.849 2.314 6.228 2.04 10.471C1.987 11.301 1.987 12.161 2.04 12.991C2.14 14.536 2.823 15.967 3.628 17.175C4.095 18.02 3.787 19.076 3.3 19.998C2.949 20.663 2.774 20.995 2.915 21.235C3.056 21.475 3.37 21.483 3.999 21.498C5.244 21.528 6.083 21.176 6.749 20.685C7.126 20.406 7.315 20.267 7.445 20.251C7.576 20.235 7.832 20.34 8.344 20.551C8.804 20.741 9.339 20.858 9.829 20.89C11.254 20.985 12.743 20.985 14.171 20.89M11.995 12H12.004M15.991 12H16M8 12H8.009';

@Component
export struct Icon {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
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
    if (this.name === 'hi_camera') return HI_CAMERA;
    if (this.name === 'hi_photos') return HI_PHOTOS;
    if (this.name === 'hi_files') return HI_FILES;
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
    if (this.name === 'check_circle') return $r('sys.symbol.checkmark_circle');
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

  private phScaleFactor(): number {
    // phosphor 视窗 256×256,同上按密度缩放
    let density: number = 3;
    try {
      density = display.getDefaultDisplaySync().densityPixels;
    } catch (_e) {
      density = 3;
    }
    return this.iconSize * density / 256;
  }

  build(): void {
    if (this.themeEpoch >= 0 && this.hiPath() !== '') {
      Path()
        .commands(scalePathData(this.hiPath(), this.scaleFactor()))
        .width(this.iconSize)
        .height(this.iconSize)
        .fillOpacity(0)
        .stroke(this.color)
        .strokeWidth(1.5 * this.iconSize / 24)
        .strokeLineCap(LineCapStyle.Round)
        .strokeLineJoin(LineJoinStyle.Round)
    } else if (this.name.startsWith('ph_') && phosphorPath(this.name) !== '') {
      Path()
        .commands(scalePathData(phosphorPath(this.name), this.phScaleFactor()))
        .width(this.iconSize)
        .height(this.iconSize)
        .fill(this.color)
        .strokeWidth(0)
    } else {
      SymbolGlyph(this.symbol())
        .fontSize(this.iconSize)
        .fontColor([this.color])
    }
  }
}
