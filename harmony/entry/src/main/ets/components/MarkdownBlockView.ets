// One observed block owns its UI dependencies. Frozen siblings never read the growing array.
import { tokenizeCode, orderedListLabel } from '@amber/chat-domain';
import type { MarkdownBlock, MarkdownBlockTable, MarkdownBlockHeading, MarkdownBlockList,
  MarkdownBlockCode, InlineToken, InlineMathToken, HighlightToken } from '@amber/chat-domain';
import { MOTION_OVERLAY, ACCENT, BODY, INK, LABEL, LINE, META, MONO_SMALL, SURFACE2,
  lineHeightForSize } from '../design/tokens.ets';
import { FONT_SANS, FONT_SANS_BOLD, FONT_SANS_MEDIUM, FONT_MONO, FONT_SERIF } from '../design/fonts.ets';
import { MermaidBlock } from './MermaidBlock.ets';
import { MathRichBlock } from './MathRichBlock.ets';
import { NativeMarkdownText } from './NativeMarkdownText.ets';

// API 12 V1: ForEach keeps this object; ObjectLink observes replacement of block.
@Observed
export class MarkdownBlockSlot {
  readonly key: string;
  readonly index: number;
  block: MarkdownBlock;
  tableColumnWidths: number[];

  constructor(index: number, block: MarkdownBlock, tableColumnWidths: number[]) {
    this.key = `md-${index}`;
    this.index = index;
    this.block = block;
    this.tableColumnWidths = tableColumnWidths;
  }
}

interface MarkdownInlineBinding {
  inlines: InlineToken[];
  size: number;
  weight: FontWeight;
  family: string;
  lineHeight: number;
  align: TextAlign;
  fillWidth: boolean;
}

interface MarkdownHeadingBinding {
  block: MarkdownBlockHeading;
  index: number;
}

interface MarkdownListBinding {
  block: MarkdownBlockList;
  index: number;
}

interface MarkdownCodeBlockBinding {
  block: MarkdownBlockCode;
  index: number;
}

interface MarkdownTableBinding {
  block: MarkdownBlockTable;
  index: number;
}

interface MarkdownBlockBinding {
  block: MarkdownBlock;
  index: number;
}

// 代码高亮色板(对齐 code_highlight.ts HIGHLIGHT_COLORS)
const CODE_KEYWORD: string = '#C678DD';
const CODE_STRING: string = '#98C379';
const CODE_COMMENT: string = '#7F848E';
const CODE_NUMBER: string = '#D19A66';
const CODE_OPERATOR: string = '#56B6C2';
const CODE_PLAIN: string = '#ABB2BF';

// 代码块背景
const CODE_BG: string = '#282C34';
const CODE_HEADER_BG: string = '#20242B';
const CODE_TEXT: string = '#ABB2BF';
// F16:超过该行数自动折叠(与 SettingDisplayPage 文案一致:超过 10 行)
const CODE_COLLAPSE_THRESHOLD: number = 10;
const CODE_LINE_NO: string = '#5C6370';

// 行内代码样式
const INLINE_CODE_FG: string = ACCENT;

@Component
export struct MarkdownBlockView {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @ObjectLink slot: MarkdownBlockSlot;
  @Prop fontSize: number = BODY;
  @Prop fontColor: string = INK;
  @Prop lineHeightScale: number = 0;
  @Prop readingMode: boolean = false;
  @Prop serifForReading: boolean = true;
  @Prop chatFontFamily: string = 'DEFAULT';
  @Prop enableLatexRendering: boolean = true;
  @Prop fillWidth: boolean = true;
  @Prop streaming: boolean = false;
  @Prop streamKey: string = '';
  @Prop codeBlockAutoCollapse: boolean = false;
  @Prop showLineNumbers: boolean = false;
  @State private codeExpanded: Record<string, boolean> = {};
  // Shared by the parent message, retaining the existing 16-entry highlight bound.
  highlightCache: Map<string, HighlightToken[]> = new Map<string, HighlightToken[]>();
  mermaidInstanceId: string = '';
  onCopyText: (text: string, message: string) => void = (_text: string, _message: string): void => {};
  onOpenLink: (url: string) => void = (_url: string): void => {};
  onOpenImage: (url: string) => void = (_url: string): void => {};
  onVisibleGrow: () => void = (): void => {};

  build(): void {
    if (this.themeEpoch >= 0) {
      if (this.enableLatexRendering && this.blockHasMath(this.slot.block)) {
        this.RenderMathBlock({ block: this.slot.block, index: this.slot.index })
      } else if (this.slot.block.kind === 'heading') {
        this.RenderHeading({ block: this.slot.block as MarkdownBlockHeading, index: this.slot.index })
      } else if (this.slot.block.kind === 'paragraph') {
        this.RenderParagraph({ block: this.slot.block, index: this.slot.index })
      } else if (this.slot.block.kind === 'code_block') {
        this.RenderCodeBlock({ block: this.slot.block as MarkdownBlockCode, index: this.slot.index })
      } else if (this.slot.block.kind === 'list') {
        this.RenderList({ block: this.slot.block as MarkdownBlockList, index: this.slot.index })
      } else if (this.slot.block.kind === 'blockquote') {
        this.RenderBlockquote({ block: this.slot.block, index: this.slot.index })
      } else if (this.slot.block.kind === 'table') {
        this.RenderTable({ block: this.slot.block as MarkdownBlockTable, index: this.slot.index })
      } else {
        Divider().color(LINE).strokeWidth(0.5).margin({ top: 8, bottom: 8 })
      }
    }
  }

  private copyText(text: string, message: string): void { this.onCopyText(text, message); }
  private showLinkActions(url: string): void { this.onOpenLink(url); }
  private showImagePreview(url: string): void { this.onOpenImage(url); }

  @Builder
  RenderMathBlock(params: MarkdownBlockBinding): void {
    MathRichBlock({
      source: JSON.stringify(params.block),
      fontSize: this.fontSize,
      fontColor: this.fontColor,
      lineHeight: this.effLineHeight(this.fontSize),
      fontFamily: this.bodyFamily(),
      headingFamily: this.headFamily(),
      readingMode: this.readingMode,
      onOpenLink: (url: string): void => { this.showLinkActions(url); },
      onOpenImage: (url: string): void => { this.showImagePreview(url); },
      onVisibleGrow: (): void => { this.onVisibleGrow(); },
    })
  }

  private inlinesHaveMath(tokens: InlineToken[]): boolean {
    for (const token of tokens) if (token.type === 'math') return true;
    return false;
  }

  private blockHasMath(block: MarkdownBlock): boolean {
    if (block.kind === 'heading' || block.kind === 'paragraph' || block.kind === 'blockquote') {
      return this.inlinesHaveMath(block.inlines);
    }
    if (block.kind === 'list') {
      for (const item of block.items) if (this.inlinesHaveMath(item)) return true;
    } else if (block.kind === 'table') {
      for (const header of block.headers) if (this.inlinesHaveMath(header)) return true;
      for (const row of block.rows) {
        for (const cell of row) if (this.inlinesHaveMath(cell)) return true;
      }
    }
    return false;
  }

  private getCodeLines(block: MarkdownBlock): HighlightToken[][] {
    if (block.kind !== 'code_block') return [];
    if (block.lang.length === 0) return [];
    const key: string = JSON.stringify([block.lang, block.code]);
    const cached: HighlightToken[] | undefined = this.highlightCache.get(key);
    if (cached !== undefined) return this.splitTokensByLine(cached);
    const tokens: HighlightToken[] = tokenizeCode(block.code, block.lang);
    this.highlightCache.set(key, tokens);
    while (this.highlightCache.size > 16) {
      let eldest: string = '';
      let found: boolean = false;
      this.highlightCache.forEach((_v: HighlightToken[], cacheKey: string): void => {
        if (!found) {
          eldest = cacheKey;
          found = true;
        }
      });
      if (!found) break;
      this.highlightCache.delete(eldest);
    }
    return this.splitTokensByLine(tokens);
  }

  private headingSize(level: number): number {
    if (level <= 1) return 22;
    if (level === 2) return 20;
    if (level === 3) return 18;
    if (level === 4) return 16;
    if (level === 5) return 15;
    return 14;
  }

  private tokenColor(type: string): string {
    if (type === 'keyword') return CODE_KEYWORD;
    if (type === 'string') return CODE_STRING;
    if (type === 'comment') return CODE_COMMENT;
    if (type === 'number') return CODE_NUMBER;
    if (type === 'operator') return CODE_OPERATOR;
    return CODE_PLAIN;
  }

  private tableColumnWidth(_blockIndex: number, column: number): number {
    const widths: number[] = this.slot.tableColumnWidths;
    return column < widths.length ? widths[column] : 80;
  }

  private tableTextAlign(block: MarkdownBlockTable, column: number): TextAlign {
    const alignment = block.alignments[column] ?? 'left';
    if (alignment === 'center') return TextAlign.Center;
    if (alignment === 'right') return TextAlign.End;
    return TextAlign.Start;
  }

  // ===== F16:代码块行号 / 自动折叠(默认关闭,仅在设置开启时生效) =====

  private codeKey(block: MarkdownBlock, bi: number): string {
    if (block.kind !== 'code_block') return '';
    return `${bi}:${block.lang}:${block.code.length}`;
  }

  private codeSourceLines(block: MarkdownBlock): string[] {
    if (block.kind !== 'code_block') return [];
    return block.code.split('\n');
  }

  private codeLineCount(block: MarkdownBlock): number {
    return this.codeSourceLines(block).length;
  }

  private codeIsCollapsed(block: MarkdownBlock, bi: number): boolean {
    if (!this.codeBlockAutoCollapse) return false;
    if (this.codeLineCount(block) <= CODE_COLLAPSE_THRESHOLD) return false;
    return this.codeExpanded[this.codeKey(block, bi)] !== true;
  }

  private toggleCodeExpanded(block: MarkdownBlock, bi: number): void {
    const key: string = this.codeKey(block, bi);
    const copy: Record<string, boolean> = {};
    const keys: string[] = Object.keys(this.codeExpanded);
    for (const k of keys) copy[k] = this.codeExpanded[k];
    copy[key] = !(this.codeExpanded[key] === true);
    this.getUIContext().animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
      this.codeExpanded = copy;
    });
  }

  private visibleCodeLineCap(block: MarkdownBlock, bi: number): number {
    const total: number = this.codeLineCount(block);
    return this.codeIsCollapsed(block, bi) ? Math.min(CODE_COLLAPSE_THRESHOLD, total) : total;
  }

  private visibleHighlightLines(block: MarkdownBlock, bi: number): HighlightToken[][] {
    return this.getCodeLines(block).slice(0, this.visibleCodeLineCap(block, bi));
  }

  private visiblePlainLines(block: MarkdownBlock, bi: number): string[] {
    return this.codeSourceLines(block).slice(0, this.visibleCodeLineCap(block, bi));
  }

  private splitTokensByLine(tokens: HighlightToken[]): HighlightToken[][] {
    const lines: HighlightToken[][] = [];
    let current: HighlightToken[] = [];
    for (const tok of tokens) {
      const text: string = tok.text;
      if (text.includes('\n')) {
        const parts: string[] = text.split('\n');
        for (let pi: number = 0; pi < parts.length; pi++) {
          if (pi > 0) {
            lines.push(current);
            current = [];
          }
          if (parts[pi].length > 0) {
            current.push({ text: parts[pi], type: tok.type } as HighlightToken);
          }
        }
      } else {
        current.push(tok);
      }
    }
    lines.push(current);
    return lines;
  }

  // ===== @Builder 渲染方法 =====

  private supportsNativeInlines(inlines: InlineToken[]): boolean {
    return inlines.every((token: InlineToken): boolean => token.type !== 'image' && token.type !== 'math');
  }

  private blockInlines(block: MarkdownBlock): InlineToken[] {
    if (block.kind === 'heading' || block.kind === 'paragraph' || block.kind === 'blockquote') return block.inlines;
    return [];
  }

  @Builder
  RenderTextInlines(params: MarkdownInlineBinding): void {
    if (this.supportsNativeInlines(params.inlines)) {
      NativeMarkdownText({
        inlines: params.inlines, streaming: this.streaming,
        fontSize: params.size, fontColor: this.fontColor, fontWeight: params.weight,
        fontFamily: params.family, boldFontFamily: FONT_SANS_BOLD,
        lineHeight: params.lineHeight, textAlign: params.align, fillWidth: params.fillWidth,
        onOpenLink: (url: string): void => { this.showLinkActions(url); },
        onCopyLink: (url: string): void => { this.copyText(url, '已复制链接'); },
      })
    } else {
      // 图片与禁用排版的公式保留已有附件/源码渲染行为。
      Text() {
        ForEach(params.inlines, (tok: InlineToken): void => {
          this.RenderInlineToken(tok, params.size, this.fontColor, params.weight)
        }, (tok: InlineToken, idx: number): string => `inline-${idx}-${JSON.stringify(tok)}`)
      }
      .width(params.fillWidth ? '100%' : 'auto')
      .fontSize(params.size).fontColor(this.fontColor).fontWeight(params.weight)
      .fontFamily(params.family).lineHeight(params.lineHeight).textAlign(params.align)
      .copyOption(CopyOptions.LocalDevice)
    }
  }

  @Builder
  RenderHeading(params: MarkdownHeadingBinding): void {
    Column() {
      this.RenderTextInlines({ inlines: this.blockInlines(params.block),
        size: this.headingSize(params.block.level), weight: FontWeight.Bold, family: this.headFamily(),
        lineHeight: lineHeightForSize(this.headingSize(params.block.level)), align: TextAlign.Start, fillWidth: true })
    }
    .width('100%').alignItems(HorizontalAlign.Start)
    .margin({ top: this.readingMode ? (params.block.level <= 2 ? 22 : 14) : (params.block.level <= 2 ? 12 : 8),
      bottom: this.readingMode ? 8 : 4 })
  }

  @Builder
  RenderParagraph(params: MarkdownBlockBinding): void {
    Column() {
      this.RenderTextInlines({ inlines: this.blockInlines(params.block),
        size: this.fontSize, weight: FontWeight.Normal, family: this.bodyFamily(),
        lineHeight: this.effLineHeight(this.fontSize),
        align: this.isDisplayMathParagraph(params.block) ? TextAlign.Center : (this.readingMode ? TextAlign.JUSTIFY : TextAlign.Start),
        fillWidth: this.fillWidth })
    }
    .width(this.fillWidth ? '100%' : 'auto').alignItems(HorizontalAlign.Start)
    .margin({ top: this.readingMode ? 0 : 2, bottom: this.readingMode ? 12 : 4 })
  }

  @Builder
  RenderBlockquote(params: MarkdownBlockBinding): void {
    Column() {
      this.RenderTextInlines({ inlines: this.blockInlines(params.block),
        size: this.fontSize, weight: FontWeight.Normal, family: this.bodyFamily(),
        lineHeight: this.effLineHeight(this.fontSize), align: TextAlign.Start, fillWidth: true })
    }
    .width('100%').alignItems(HorizontalAlign.Start)
    .border({ width: { left: 3 }, color: LINE }).padding({ left: 10 })
    .margin({ top: 4, bottom: 4 })
  }

  private bodyFamily(): string {
    if (this.readingMode && this.serifForReading) return FONT_SERIF;
    if (this.chatFontFamily === 'SERIF') return FONT_SERIF;
    if (this.chatFontFamily === 'MONO') return FONT_MONO;
    return FONT_SANS;
  }

  private headFamily(): string {
    if (this.readingMode && this.serifForReading) return FONT_SERIF;
    if (this.chatFontFamily === 'SERIF') return FONT_SERIF;
    if (this.chatFontFamily === 'MONO') return FONT_MONO;
    return FONT_SANS_BOLD;
  }

  // 有效行高:lineHeightScale > 0 时按倍率,否则契约档
  private effLineHeight(size: number): number {
    if (this.lineHeightScale > 0) return Math.round(size * this.lineHeightScale * 10) / 10;
    return lineHeightForSize(size);
  }

  // 整段仅为一个 $$..$$ 展示公式 → 段落居中
  private isDisplayMathParagraph(block: MarkdownBlock): boolean {
    if (block.kind !== 'paragraph') return false;
    return block.inlines.length === 1
      && block.inlines[0].type === 'math'
      && (block.inlines[0] as InlineMathToken).display;
  }

  // 列表项嵌套深度(parser depths;每级缩进 12)
  private listDepthAt(block: MarkdownBlock, idx: number): number {
    if (block.kind !== 'list' || block.depths === undefined) return 0;
    if (idx < 0 || idx >= block.depths.length) return 0;
    return block.depths[idx];
  }

  // 有序序号:顶层延续、嵌套层各自从 1 重启(CommonMark 语义)
  private orderedLabel(block: MarkdownBlock, idx: number): string {
    if (block.kind !== 'list') return '';
    if (block.depths === undefined) {
      const flat: number[] = [];
      return orderedListLabel(block.start, flat, idx);
    }
    return orderedListLabel(block.start, block.depths, idx);
  }

  @Builder
  RenderList(params: MarkdownListBinding): void {
    Column() {
      if (params.block.kind === 'list') {
        ForEach(
          params.block.items,
          (itemInlines: InlineToken[], idx: number): void => {
            Row() {
              // 标记与正文同 lineHeight 且顶对齐:多行条目圆点不再垂直漂移
              Text(params.block.ordered ? this.orderedLabel(params.block, idx) : '•')
                .fontSize(this.fontSize)
                .fontColor(this.fontColor)
                .fontFamily(this.bodyFamily())
                .lineHeight(this.effLineHeight(this.fontSize))
                .width(params.block.ordered ? 32 : 16)
              Column() {
                this.RenderTextInlines({ inlines: params.block.items[idx],
                  size: this.fontSize, weight: FontWeight.Normal, family: this.bodyFamily(),
                  lineHeight: this.effLineHeight(this.fontSize), align: TextAlign.Start, fillWidth: true })
              }.layoutWeight(1).alignItems(HorizontalAlign.Start)
            }
            .width('100%')
            .alignItems(VerticalAlign.Top)
            .padding({ left: this.listDepthAt(params.block, idx) * 12 })
            .margin({ bottom: 6 })
          },
          (itemInlines: InlineToken[], idx: number): string => `list-${idx}`,
        )
      }
    }
    .width('100%')
    .margin({ top: 2, bottom: 4 })
  }

  @Builder
  CodeLineText(idx: number, lineTokens: HighlightToken[], plainText: string): void {
    Row() {
      // F16:行号(默认关;与代码行同基线同字号)
      if (this.showLineNumbers) {
        Text(`${idx + 1}`)
          .fontSize(12)
          .fontFamily(FONT_MONO)
          .fontColor(CODE_LINE_NO)
          .width(28)
          .textAlign(TextAlign.End)
          .lineHeight(18)
          .margin({ right: 10 })
      }
      if (lineTokens.length > 0) {
        Text() {
          ForEach(
            lineTokens,
            (tok: HighlightToken, ti: number): void => {
              Span(tok.text)
                .fontSize(12)
                .fontFamily(FONT_MONO)
                .fontColor(this.tokenColor(tok.type))
            },
            (tok: HighlightToken, ti: number): string => `tok-${idx}-${ti}`,
          )
        }
        .copyOption(CopyOptions.LocalDevice)
        .fontSize(12)
        .fontFamily(FONT_MONO)
        .lineHeight(18)
      } else if (plainText.length > 0) {
        Text(plainText)
          .copyOption(CopyOptions.LocalDevice)
          .fontSize(12)
          .fontColor(CODE_TEXT)
          .fontFamily(FONT_MONO)
          .lineHeight(18)
      } else {
        Text(' ').fontSize(12).fontFamily(FONT_MONO).lineHeight(18)
      }
    }
    .alignItems(VerticalAlign.Top)
    // 只跟随用户展开/折叠的 animateTo，不为流式追加的每一行启动动画。
    .transition(TransitionEffect.OPACITY)
  }

  // D7-3:mermaid 判定/实例唯一块 id;导出由 MermaidBlock 掌握实际图高。
  private isMermaid(block: MarkdownBlock): boolean {
    return block.kind === 'code_block' && block.lang.trim().toLowerCase() === 'mermaid';
  }

  private mermaidBlockId(block: MarkdownBlock, bi: number): string {
    const key: string = this.streamKey.replace(/[^A-Za-z0-9_]/g, '_');
    return `mmd_${this.mermaidInstanceId}_${key}_${bi}`;
  }

  @Builder
  RenderCodeBlock(params: MarkdownCodeBlockBinding): void {
    Column() {
      if (params.block.kind === 'code_block') {
        Row() {
          Text(params.block.lang.length > 0 ? params.block.lang : 'code')
            .fontSize(MONO_SMALL)
            .fontColor('#9AA3B2')
            .fontFamily(FONT_MONO)
            .layoutWeight(1)
          if (this.codeBlockAutoCollapse && this.codeLineCount(params.block) > CODE_COLLAPSE_THRESHOLD) {
            Text(this.codeIsCollapsed(params.block, params.index) ? '展开' : '收起')
              .fontSize(META)
              .lineHeight(lineHeightForSize(META))
              .fontFamily(FONT_SANS)
              .fontColor(CODE_TEXT)
              .width(48)
              .height(44)
              .textAlign(TextAlign.Center)
              .onClick((): void => {
                this.toggleCodeExpanded(params.block, params.index);
              })
              .accessibilityText('展开或收起代码块')
          }
          Text('复制')
            .fontSize(META)
            .lineHeight(lineHeightForSize(META))
            .fontFamily(FONT_SANS)
            .fontColor(CODE_TEXT)
            .width(48)
            .height(44)
            .textAlign(TextAlign.Center)
            .onClick((): void => { this.copyText(params.block.code, '已复制代码'); })
        }
        .width('100%')
        .height(44)
        .padding({ right: 4 })
        .alignItems(VerticalAlign.Center)
        .backgroundColor(CODE_HEADER_BG)
        .border({ width: { bottom: 0.5 }, color: '#3A404B' })

        // D7-3:mermaid 图渲染(本地 mermaid.min.js 沙箱;失败降级源码照常展示)
        if (this.isMermaid(params.block)) {
          MermaidBlock({
            source: params.block.code,
            blockId: this.mermaidBlockId(params.block, params.index),
          })
            .margin({ bottom: 8 })
        }

        // 横向滚动:长行不折行,行 Text 不限宽(内容自适应),短行靠 Column 左对齐
        Scroll() {
          Column() {
            if (params.block.lang.length > 0) {
              ForEach(
                this.visibleHighlightLines(params.block, params.index),
                (lineTokens: HighlightToken[], idx: number): void => {
                  this.CodeLineText(idx, lineTokens, '')
                },
                (lineTokens: HighlightToken[], idx: number): string => `codeline-${idx}-${JSON.stringify(lineTokens)}`,
              )
            } else {
              if (this.showLineNumbers || this.codeBlockAutoCollapse) {
                ForEach(
                  this.visiblePlainLines(params.block, params.index),
                  (line: string, idx: number): void => {
                    this.CodeLineText(idx, [], line)
                  },
                  (line: string, idx: number): string => `codetext-${idx}-${line}`,
                )
              } else {
                // 默认路径:与既有渲染一致(整段单个 Text,不逐行)
                Text(params.block.code)
                  .copyOption(CopyOptions.LocalDevice)
                  .fontSize(12)
                  .fontColor(CODE_TEXT)
                  .fontFamily(FONT_MONO)
                  .lineHeight(18)
              }
            }
          }
          .alignItems(HorizontalAlign.Start)
        }
        .scrollable(ScrollDirection.Horizontal)
        .scrollBar(BarState.Off)
        .width('100%')
        .align(Alignment.TopStart)
        if (this.codeIsCollapsed(params.block, params.index)) {
          Text('已折叠，点击展开')
            .fontSize(LABEL)
            .fontColor(CODE_LINE_NO)
            .fontFamily(FONT_MONO)
            .width('100%')
            .padding({ top: 6 })
            .onClick((): void => {
              this.toggleCodeExpanded(params.block, params.index);
            })
            .transition(TransitionEffect.OPACITY)
        }
      }
    }
    .width('100%')
    .backgroundColor(CODE_BG)
    .borderRadius(8)
    .clip(true)
    .padding({ left: 12, right: 12, top: 10, bottom: 10 })
    .margin({ top: 4, bottom: 6 })
  }

  @Builder
  RenderTable(params: MarkdownTableBinding): void {
    Scroll() {
      Column() {
        if (params.block.kind === 'table') {
          // 表头
          Row() {
            ForEach(
              params.block.headers,
              (cellInlines: InlineToken[], ci: number): void => {
                Text() {
                  ForEach(
                    cellInlines,
                    (tok: InlineToken, ti: number): void => {
                      this.RenderInlineToken(tok, META, this.fontColor, FontWeight.Medium)
                    },
                    (tok: InlineToken, ti: number): string => `th-${ci}-${ti}-${tok.type}`,
                  )
                }
                .fontSize(META)
                .fontColor(this.fontColor)
                .lineHeight(lineHeightForSize(META))
                .fontFamily(FONT_SANS_MEDIUM)
                .textAlign(this.tableTextAlign(params.block, ci))
                .width(this.tableColumnWidth(params.index, ci))
                .copyOption(CopyOptions.LocalDevice)
                .padding({ left: 6, right: 6, top: 4, bottom: 4 })
              },
              (cellInlines: InlineToken[], ci: number): string => `th-col-${ci}-${JSON.stringify(cellInlines)}`,
            )
          }
          .backgroundColor(SURFACE2)

          // 表体
          ForEach(
            params.block.rows,
            (rowCells: InlineToken[][], ri: number): void => {
              Row() {
                ForEach(
                  rowCells,
                  (cellInlines: InlineToken[], ci: number): void => {
                    Text() {
                      ForEach(
                        cellInlines,
                        (tok: InlineToken, ti: number): void => {
                          this.RenderInlineToken(tok, META, this.fontColor, FontWeight.Normal)
                        },
                        (tok: InlineToken, ti: number): string => `td-${ri}-${ci}-${ti}-${tok.type}`,
                      )
                    }
                    .fontSize(META)
                    .fontColor(this.fontColor)
                    .lineHeight(lineHeightForSize(META))
                    .fontFamily(FONT_SANS)
                    .textAlign(this.tableTextAlign(params.block, ci))
                    .width(this.tableColumnWidth(params.index, ci))
                    .copyOption(CopyOptions.LocalDevice)
                    .padding({ left: 6, right: 6, top: 4, bottom: 4 })
                  },
                  (cellInlines: InlineToken[], ci: number): string => `td-${ri}-${ci}`,
                )
              }
              .border({ width: { top: 0.5 }, color: LINE })
            },
            (rowCells: InlineToken[][], ri: number): string => `tr-${ri}-${JSON.stringify(rowCells)}`,
          )
        }
      }
      .alignItems(HorizontalAlign.Start)
      .border({ width: 0.5, color: LINE })
      .borderRadius(4)
    }
    .scrollable(ScrollDirection.Horizontal)
    .scrollBar(BarState.Auto)
    .width('100%')
    .align(Alignment.TopStart)
    .margin({ top: 4, bottom: 6 })
  }

  // ===== 行内 token 渲染 =====

  // 权重→家族名(字重由注册的静态档承载;仅设 fontWeight 会落到合成加粗)
  private spanFamily(weight: FontWeight): string {
    if (this.readingMode && this.serifForReading) return FONT_SERIF;
    if (weight === FontWeight.Bold) return FONT_SANS_BOLD;
    if (weight === FontWeight.Medium) return FONT_SANS_MEDIUM;
    return FONT_SANS;
  }

  @Builder
  RenderInlineToken(tok: InlineToken, size: number, color: string, weight: FontWeight): void {
    if (tok.type === 'text') {
      Span(tok.text)
        .fontSize(size)
        .fontColor(color)
        .fontWeight(weight)
        .fontFamily(this.spanFamily(weight))
    } else if (tok.type === 'bold') {
      Span(tok.text)
        .fontSize(size)
        .fontColor(color)
        .fontWeight(FontWeight.Bold)
        .fontFamily(FONT_SANS_BOLD)
    } else if (tok.type === 'italic') {
      Span(tok.text)
        .fontSize(size)
        .fontColor(color)
        .fontWeight(weight)
        .fontStyle(FontStyle.Italic)
        .fontFamily(this.spanFamily(weight))
    } else if (tok.type === 'code') {
      Span(tok.text)
        .fontSize(size - 1)
        .fontColor(this.fontColor === INK ? INLINE_CODE_FG : color)
        .fontFamily(FONT_MONO)
    } else if (tok.type === 'link') {
      Span(tok.text)
        .fontSize(size)
        .fontColor(ACCENT)
        .decoration({ type: TextDecorationType.Underline, color: ACCENT })
        .gesture(
          GestureGroup(
            GestureMode.Exclusive,
            LongPressGesture().onAction((): void => { this.copyText(tok.url, '已复制链接'); }),
            TapGesture().onAction((): void => { this.showLinkActions(tok.url); }),
          )
        )
    } else if (tok.type === 'image') {
      if (tok.url.trim().length > 0) {
        ImageSpan(tok.url)
          .width(220)
          .height(150)
          .objectFit(ImageFit.Contain)
          .borderRadius(8)
          .onClick((): void => { this.showImagePreview(tok.url); })
      } else {
        Span(`[图: ${tok.alt}]`)
          .fontSize(size)
          .fontColor(color)
      }
    } else if (tok.type === 'math') {
      // 关闭数学排版时保留公式源码；启用时整块由 MathRichBlock 渲染。
      Span(tok.display ? `$$${tok.latex}$$` : `$${tok.latex}$`)
        .fontSize(size)
        .fontColor(color)
        .fontFamily(FONT_MONO)
    }
  }
}
