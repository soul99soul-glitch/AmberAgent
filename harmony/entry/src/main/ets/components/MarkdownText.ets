// MarkdownText — 原生 ArkUI Markdown 渲染组件(P0 修复)
//
// 替代 MarkdownRichText(RichText 不参与宿主布局测量 → 消息操作按钮不可见)。
// 方案: parseMarkdown(chat HAR) → block/inline AST → 原生 ArkUI 组件渲染。
//   - 段落/标题/引用: Text + Span(粗体/斜体/代码/链接)
//   - 代码块: Column 内 Text 行(每行一个 Text,Span 着色)
//   - 列表: Row(marker Text + content Text)
//   - 表格: Column 内 Row 行
//   - 水平线: Divider
//
// 所有组件原生参与布局测量 → Column 高度正确 → ActionFooter 可见。
//
// ArkUI @Builder 约束: 不能在 @Builder 体内声明 const/let 后再写 UI;
//   所有计算逻辑放普通 private 方法,@Builder 只含 UI 组件语法。
//   ForEach 回调内 if/else 必须包裹 UI 组件(不能裸 const)。

import {
  parseMarkdown,
  tokenizeCode,
} from '@amber/chat-domain';
import type {
  MarkdownBlock,
  InlineToken,
  HighlightToken,
} from '@amber/chat-domain';
import { INK, INK2, INK3, INK4, SURFACE2, LINE, ACCENT, BODY, META } from '../design/tokens.ets';

// 代码高亮色板(对齐 code_highlight.ts HIGHLIGHT_COLORS)
const CODE_KEYWORD: string = '#C678DD';
const CODE_STRING: string = '#98C379';
const CODE_COMMENT: string = '#7F848E';
const CODE_NUMBER: string = '#D19A66';
const CODE_OPERATOR: string = '#56B6C2';
const CODE_PLAIN: string = '#ABB2BF';

// 代码块背景
const CODE_BG: string = '#282C34';
const CODE_TEXT: string = '#ABB2BF';

// 行内代码样式
const INLINE_CODE_FG: string = '#B8623A';

@Component
export struct MarkdownText {
  @Prop content: string = '';
  @Prop fontSize: number = BODY;
  @Prop fontColor: string = INK;

  build(): void {
    Column() {
      if (this.content.trim().length > 0) {
        ForEach(
          this.getBlocks(),
          (block: MarkdownBlock, idx: number): void => {
            if (block.kind === 'heading') {
              this.RenderHeading(block)
            } else if (block.kind === 'paragraph') {
              this.RenderParagraph(block)
            } else if (block.kind === 'code_block') {
              this.RenderCodeBlock(block)
            } else if (block.kind === 'list') {
              this.RenderList(block)
            } else if (block.kind === 'blockquote') {
              this.RenderBlockquote(block)
            } else if (block.kind === 'table') {
              this.RenderTable(block)
            } else {
              Divider().color(LINE).strokeWidth(0.5).margin({ top: 8, bottom: 8 })
            }
          },
          (block: MarkdownBlock, idx: number): string => `md-${idx}-${block.kind}`,
        )
      }
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
  }

  // ===== 预计算方法(非 @Builder) =====

  private getBlocks(): MarkdownBlock[] {
    return parseMarkdown(this.content);
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

  private getCodeLines(block: MarkdownBlock): HighlightToken[][] {
    if (block.kind !== 'code_block') return [];
    if (block.lang.length === 0) return [];
    const tokens: HighlightToken[] = tokenizeCode(block.code, block.lang);
    return this.splitTokensByLine(tokens);
  }

  // ===== @Builder 渲染方法 =====

  @Builder
  RenderHeading(block: MarkdownBlock): void {
    Text() {
      if (block.kind === 'heading') {
        ForEach(
          block.inlines,
          (tok: InlineToken, idx: number): void => {
            this.RenderInlineToken(tok, this.headingSize(block.level), INK, FontWeight.Bold)
          },
          (tok: InlineToken, idx: number): string => `h-${idx}-${tok.type}`,
        )
      }
    }
    .width('100%')
    .fontSize(this.headingSize(block.kind === 'heading' ? block.level : 1))
    .fontColor(INK)
    .margin({ top: block.kind === 'heading' && block.level <= 2 ? 12 : 8, bottom: 4 })
  }

  @Builder
  RenderParagraph(block: MarkdownBlock): void {
    Text() {
      if (block.kind === 'paragraph') {
        ForEach(
          block.inlines,
          (tok: InlineToken, idx: number): void => {
            this.RenderInlineToken(tok, this.fontSize, this.fontColor, FontWeight.Normal)
          },
          (tok: InlineToken, idx: number): string => `p-${idx}-${tok.type}`,
        )
      }
    }
    .width('100%')
    .fontSize(this.fontSize)
    .fontColor(this.fontColor)
    .lineHeight(this.fontSize * 1.6)
    .margin({ top: 2, bottom: 4 })
  }

  @Builder
  RenderBlockquote(block: MarkdownBlock): void {
    Text() {
      if (block.kind === 'blockquote') {
        ForEach(
          block.inlines,
          (tok: InlineToken, idx: number): void => {
            this.RenderInlineToken(tok, this.fontSize, INK3, FontWeight.Normal)
          },
          (tok: InlineToken, idx: number): string => `bq-${idx}-${tok.type}`,
        )
      }
    }
    .width('100%')
    .fontSize(this.fontSize)
    .fontColor(INK3)
    .lineHeight(this.fontSize * 1.5)
    .border({ width: { left: 3 }, color: LINE })
    .padding({ left: 10 })
    .margin({ top: 4, bottom: 4 })
  }

  @Builder
  RenderList(block: MarkdownBlock): void {
    Column() {
      if (block.kind === 'list') {
        ForEach(
          block.items,
          (itemInlines: InlineToken[], idx: number): void => {
            Row() {
              Text(block.ordered ? `${idx + 1}.` : '•')
                .fontSize(this.fontSize)
                .fontColor(INK3)
                .width(block.ordered ? 24 : 16)
                .margin({ top: 2 })
              Text() {
                ForEach(
                  itemInlines,
                  (tok: InlineToken, ti: number): void => {
                    this.RenderInlineToken(tok, this.fontSize, this.fontColor, FontWeight.Normal)
                  },
                  (tok: InlineToken, ti: number): string => `li-${idx}-${ti}-${tok.type}`,
                )
              }
              .fontSize(this.fontSize)
              .fontColor(this.fontColor)
              .lineHeight(this.fontSize * 1.5)
              .layoutWeight(1)
            }
            .width('100%')
            .margin({ bottom: 2 })
          },
          (itemInlines: InlineToken[], idx: number): string => `list-${idx}`,
        )
      }
    }
    .width('100%')
    .margin({ top: 2, bottom: 4 })
  }

  @Builder
  RenderCodeBlock(block: MarkdownBlock): void {
    Column() {
      if (block.kind === 'code_block') {
        if (block.lang.length > 0) {
          ForEach(
            this.getCodeLines(block),
            (lineTokens: HighlightToken[], idx: number): void => {
              if (lineTokens.length === 0) {
                Text(' ').fontSize(12).fontFamily('monospace').lineHeight(18).width('100%')
              } else {
                Text() {
                  ForEach(
                    lineTokens,
                    (tok: HighlightToken, ti: number): void => {
                      Span(tok.text)
                        .fontSize(12)
                        .fontFamily('monospace')
                        .fontColor(this.tokenColor(tok.type))
                    },
                    (tok: HighlightToken, ti: number): string => `tok-${idx}-${ti}`,
                  )
                }
                .fontSize(12)
                .fontFamily('monospace')
                .lineHeight(18)
                .width('100%')
              }
            },
            (lineTokens: HighlightToken[], idx: number): string => `codeline-${idx}`,
          )
        } else {
          Text(block.code)
            .fontSize(12)
            .fontColor(CODE_TEXT)
            .fontFamily('monospace')
            .lineHeight(18)
            .width('100%')
        }
      }
    }
    .width('100%')
    .backgroundColor(CODE_BG)
    .borderRadius(8)
    .padding({ left: 12, right: 12, top: 10, bottom: 10 })
    .margin({ top: 4, bottom: 6 })
  }

  @Builder
  RenderTable(block: MarkdownBlock): void {
    Column() {
      if (block.kind === 'table') {
        // 表头
        Row() {
          ForEach(
            block.headers,
            (cellInlines: InlineToken[], ci: number): void => {
              Text() {
                ForEach(
                  cellInlines,
                  (tok: InlineToken, ti: number): void => {
                    this.RenderInlineToken(tok, META, INK2, FontWeight.Medium)
                  },
                  (tok: InlineToken, ti: number): string => `th-${ci}-${ti}-${tok.type}`,
                )
              }
              .fontSize(META)
              .fontColor(INK2)
              .layoutWeight(1)
              .padding({ left: 6, right: 6, top: 4, bottom: 4 })
            },
            (cellInlines: InlineToken[], ci: number): string => `th-col-${ci}`,
          )
        }
        .width('100%')
        .backgroundColor(SURFACE2)

        // 表体
        ForEach(
          block.rows,
          (rowCells: InlineToken[][], ri: number): void => {
            Row() {
              ForEach(
                rowCells,
                (cellInlines: InlineToken[], ci: number): void => {
                  Text() {
                    ForEach(
                      cellInlines,
                      (tok: InlineToken, ti: number): void => {
                        this.RenderInlineToken(tok, META, INK2, FontWeight.Normal)
                      },
                      (tok: InlineToken, ti: number): string => `td-${ri}-${ci}-${ti}-${tok.type}`,
                    )
                  }
                  .fontSize(META)
                  .fontColor(INK2)
                  .layoutWeight(1)
                  .padding({ left: 6, right: 6, top: 4, bottom: 4 })
                },
                (cellInlines: InlineToken[], ci: number): string => `td-${ri}-${ci}`,
              )
            }
            .width('100%')
            .border({ width: { top: 0.5 }, color: LINE })
          },
          (rowCells: InlineToken[][], ri: number): string => `tr-${ri}`,
        )
      }
    }
    .width('100%')
    .border({ width: 0.5, color: LINE })
    .borderRadius(4)
    .margin({ top: 4, bottom: 6 })
  }

  // ===== 行内 token 渲染 =====

  @Builder
  RenderInlineToken(tok: InlineToken, size: number, color: string, weight: FontWeight): void {
    if (tok.type === 'text') {
      Span(tok.text)
        .fontSize(size)
        .fontColor(color)
        .fontWeight(weight)
    } else if (tok.type === 'bold') {
      Span(tok.text)
        .fontSize(size)
        .fontColor(color)
        .fontWeight(FontWeight.Bold)
    } else if (tok.type === 'italic') {
      Span(tok.text)
        .fontSize(size)
        .fontColor(color)
        .fontStyle(FontStyle.Italic)
    } else if (tok.type === 'code') {
      Span(tok.text)
        .fontSize(size - 1)
        .fontColor(INLINE_CODE_FG)
        .fontFamily('monospace')
    } else if (tok.type === 'link') {
      Span(tok.text)
        .fontSize(size)
        .fontColor(ACCENT)
        .decoration({ type: TextDecorationType.Underline })
    } else {
      Span(`[图: ${tok.alt}]`)
        .fontSize(size)
        .fontColor(INK3)
    }
  }
}
