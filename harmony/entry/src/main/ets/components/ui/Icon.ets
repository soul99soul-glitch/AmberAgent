// Icon — 单色系统符号图标(对齐设计系统: stroke 单色图标, currentColor)
//
// 设计系统用单色细线 SVG 图标(currentColor)。emoji 渲染成彩色图片,破坏质感。
// ArkUI 用 SymbolGlyph 渲染系统符号,单色,可指定 fontColor — 正确方案。
//
// 用法: Icon({ name: 'search', iconSize: 20, color: INK2 })
//
// 注意: 属性名用 iconSize 而非 size —— CustomComponent 基类已占用 size
//   (size?: SizeOptions),@Prop size 会与之冲突(编译错误 10505001)。
//   symbol 名以本 SDK(toolchains/id_defined.json type=symbol)实存为准。

import { INK2 } from '../../design/tokens.ets';

@Component
export struct Icon {
  @Prop name: string = 'search';
  @Prop iconSize: number = 20;
  @Prop color: string = INK2;

  private symbol(): Resource {
    if (this.name === 'search') return $r('sys.symbol.magnifyingglass');
    if (this.name === 'gear') return $r('sys.symbol.gearshape');
    if (this.name === 'star') return $r('sys.symbol.star');
    if (this.name === 'pin') return $r('sys.symbol.pin');
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
    if (this.name === 'arrow_up') return $r('sys.symbol.arrow_up');
    return $r('sys.symbol.circle');
  }

  build(): void {
    SymbolGlyph(this.symbol())
      .fontSize(this.iconSize)
      .fontColor([this.color])
  }
}
