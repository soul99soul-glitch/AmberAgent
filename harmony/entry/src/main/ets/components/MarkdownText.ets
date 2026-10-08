// MarkdownText — 原生 ArkUI Markdown 渲染组件(P0 修复)
//
// 替代 MarkdownRichText(RichText 不参与宿主布局测量 → 消息操作按钮不可见)。
// 方案: parseMarkdown(chat HAR) → block/inline AST → 原生 ArkUI 组件渲染。
//   - 段落/标题/引用: 原生 MutableStyledString 增量追加 + 新尾段淡入
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

import { newId, parseMarkdown, MarkdownCache } from '@amber/chat-domain';
import type { MarkdownBlock, MarkdownBlockTable, InlineToken, HighlightToken,
  MarkdownCacheResult } from '@amber/chat-domain';
import { BODY, INK, META, lineHeightForSize } from '../design/tokens.ets';
import { FONT_SANS } from '../design/fonts.ets';
import { promptAction } from '@kit.ArkUI';
import { pasteboard } from '@kit.BasicServicesKit';
import { common } from '@kit.AbilityKit';
import { ImagePreviewDialog } from './ui/ImagePreviewDialog.ets';
import { MarkdownBlockSlot, MarkdownBlockView } from './MarkdownBlockView.ets';

// 正文随上游 48ms 累计快照同步排版，不叠加 reveal/parse 计时门。
// 全局 Markdown 增量解析缓存(32 条目 / 200k 字符,精确 + 前缀增量,有界)。
const markdownCache: MarkdownCache = new MarkdownCache();

@Component
export struct MarkdownText {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop @Watch('onContentChanged') content: string = '';
  @Prop fontSize: number = BODY;
  @Prop fontColor: string = INK;
  // 阅读模式行高倍率(0 = 走契约档;长文场景传 1.7 左右)
  @Prop lineHeightScale: number = 0;
  // 阅读模式:两端对齐 + 段落间距(深读长文用;聊天消息保持默认)
  //   字体族:默认衬线(深读杂志版式);小说章节传 serifForReading=false 走默认无衬线
  @Prop readingMode: boolean = false;
  @Prop serifForReading: boolean = true;
  // 聊天字体族(DisplaySetting.chatFontFamily):'DEFAULT' | 'SERIF' | 'MONO'
  @Prop chatFontFamily: string = 'DEFAULT';
  // LaTeX 渲染开关(DisplaySetting.enableLatexRendering);关闭时公式显示源码
  @Prop enableLatexRendering: boolean = true;
  // 收缩包裹场景(用户气泡 maxWidth 82%)需 'auto',否则 100% 使测宽失效不换行
  @Prop fillWidth: boolean = true;
  // 活动流式尾标记；正文与历史使用同一原生渲染路径。
  @Prop @Watch('onStreamingChanged') streaming: boolean = false;
  // 稳定键(messageId + partKey);非空 = 参与增量缓存;
  // 空 = 直接解析。
  @Prop streamKey: string = '';
  // 原生布局尺寸变化后通知宿主，滚动帧驱动统一合并回调。
  onVisibleGrow: () => void = (): void => {};
  // F16:代码块显示设置(默认 false = 保持既有外观)
  @Prop codeBlockAutoCollapse: boolean = false;
  @Prop showLineNumbers: boolean = false;
  @State private hasDisplayContent: boolean = false;
  @State private displaySlots: MarkdownBlockSlot[] = [];
  private parsedBlocks: MarkdownBlock[] = [];
  private highlightCache: Map<string, HighlightToken[]> = new Map<string, HighlightToken[]>();
  private lastParsedText: string = '';
  @State private previewUrl: string = '';
  private mermaidInstanceId: string = newId().replace(/-/g, '_');

  aboutToAppear(): void {
    this.updateDisplay();
  }

  private onContentChanged(): void { this.updateDisplay(); }

  private onStreamingChanged(): void { this.updateDisplay(); }

  private updateDisplay(): void {
    if (this.content === this.lastParsedText) return;
    const blocks: MarkdownBlock[] = this.parseBlocks(this.content, this.lastParsedText.length);
    this.updateBlockSlots(blocks);
    this.lastParsedText = this.content;
    this.hasDisplayContent = this.content.trim().length > 0;
  }

  private updateBlockSlots(blocks: MarkdownBlock[]): void {
    const slots: MarkdownBlockSlot[] = this.displaySlots.slice();
    const structureChanged: boolean = slots.length !== blocks.length;
    for (let index: number = 0; index < blocks.length; index++) {
      const block: MarkdownBlock = blocks[index];
      if (index >= slots.length) {
        slots.push(new MarkdownBlockSlot(index, block,
          block.kind === 'table' ? this.resolveTableWidths(block) : []));
      } else if (this.parsedBlocks[index] !== block) {
        // Cache retains raw AST identity for frozen prefixes. Do not write those slots.
        slots[index].block = block;
        if (block.kind === 'table') {
          slots[index].tableColumnWidths = this.resolveTableWidths(block);
        } else if (this.parsedBlocks[index].kind === 'table') {
          slots[index].tableColumnWidths = [];
        }
      }
    }
    // Ordinary text append changes only the observed tail, never ForEach's array.
    if (structureChanged) this.displaySlots = slots.slice(0, blocks.length);
    this.parsedBlocks = blocks;
  }

  private parseBlocks(text: string, prevLen: number): MarkdownBlock[] {
    if (text.length === 0) return [];
    if (this.streamKey.length > 0) {
      // 流式/已定型内容:全局增量缓存(精确命中 + 前缀增量;
      //   代码围栏/表格边界不安全 → 自动全量回退 = 不完整 Markdown 安全兜底)
      const result: MarkdownCacheResult = markdownCache.getOrParse(this.streamKey, prevLen, text);
      return result.blocks;
    }
    // 非流式内容:直接解析(parseMarkdown 自带异常安全回退 → 纯文本段落)
    return parseMarkdown(text);
  }

  build(): void {
    Column() {
      if (this.hasDisplayContent) {
        if (this.displaySlots.length === 0) {
          Text(this.content)
            .width(this.fillWidth ? '100%' : 'auto')
            .fontSize(this.fontSize).fontColor(this.fontColor)
            .copyOption(CopyOptions.LocalDevice)
            .lineHeight(lineHeightForSize(this.fontSize)).fontFamily(FONT_SANS)
        } else {
          ForEach(this.displaySlots, (slot: MarkdownBlockSlot): void => {
            MarkdownBlockView({
              slot: slot, fontSize: this.fontSize, fontColor: this.fontColor,
              lineHeightScale: this.lineHeightScale, readingMode: this.readingMode,
              serifForReading: this.serifForReading, chatFontFamily: this.chatFontFamily,
              enableLatexRendering: this.enableLatexRendering, fillWidth: this.fillWidth,
              streaming: this.streaming, streamKey: this.streamKey,
              codeBlockAutoCollapse: this.codeBlockAutoCollapse, showLineNumbers: this.showLineNumbers,
              highlightCache: this.highlightCache, mermaidInstanceId: this.mermaidInstanceId,
              onCopyText: (text: string, message: string): void => { this.copyText(text, message); },
              onOpenLink: (url: string): void => { this.showLinkActions(url); },
              onOpenImage: (url: string): void => { this.showImagePreview(url); },
              onVisibleGrow: (): void => { this.onVisibleGrow(); },
            })
          }, (slot: MarkdownBlockSlot): string => slot.key)
        }
      }
    }
    .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width(this.fillWidth ? '100%' : 'auto')
    .alignItems(HorizontalAlign.Start)
    .onSizeChange((oldSize: SizeOptions, newSize: SizeOptions): void => {
      if (Number(oldSize.height) !== Number(newSize.height)) this.onVisibleGrow();
    })
    .bindContentCover(this.previewUrl.length > 0, this.ImagePreviewOverlay(), {
      onWillDismiss: (): void => { this.closeImagePreview(); },
    })
  }

  // 与 Android 的 80–200 列宽范围一致；整列共用宽度，宽表由 Scroll 横滚。
  private resolveTableWidths(block: MarkdownBlockTable): number[] {
    const widths: number[] = [];
    for (let column: number = 0; column < block.headers.length; column++) {
      let width: number = this.tableCellWidth(block.headers[column]);
      for (const row of block.rows) {
        if (column < row.length) width = Math.max(width, this.tableCellWidth(row[column]));
      }
      widths.push(Math.max(80, Math.min(200, width)));
    }
    return widths;
  }

  private tableCellWidth(tokens: InlineToken[]): number {
    let text: string = '';
    for (const token of tokens) {
      if (token.type === 'image') return 200;
      text += token.type === 'math' ? token.latex : token.text;
    }
    let widest: number = 0;
    for (const line of text.split('\n')) {
      let width: number = 12;
      for (let i: number = 0; i < line.length; i++) {
        width += line.charCodeAt(i) > 255 ? META : META * 0.6;
      }
      widest = Math.max(widest, width);
    }
    return Math.ceil(widest);
  }

  private copyText(text: string, message: string): void {
    try {
      const board: pasteboard.SystemPasteboard = pasteboard.getSystemPasteboard();
      board.setDataSync(pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, text));
      promptAction.showToast({ message: message, duration: 1400 });
    } catch (_e) {
      promptAction.showToast({ message: '复制失败', duration: 1400 });
    }
  }

  private openUrl(url: string): void {
    const normalizedUrl: string = url.trim();
    if (normalizedUrl.length === 0) return;
    const schemeEnd: number = normalizedUrl.indexOf(':');
    const scheme: string = schemeEnd > 0 ? normalizedUrl.substring(0, schemeEnd).toLowerCase() : '';
    if (scheme !== 'http' && scheme !== 'https' && scheme !== 'mailto' && scheme !== 'tel') {
      this.copyText(url, '无法打开浏览器，已复制链接');
      return;
    }
    try {
      const ctx: common.UIAbilityContext = getContext(this) as common.UIAbilityContext;
      ctx.startAbility({
        action: 'ohos.want.action.viewData',
        entities: ['entity.system.browsable'],
        uri: normalizedUrl,
      }).catch((_e: Error): void => {
        this.copyText(url, '无法打开浏览器，已复制链接');
      });
    } catch (_e) {
      this.copyText(url, '无法打开浏览器，已复制链接');
    }
  }

  private showLinkActions(url: string): void {
    AlertDialog.show({
      title: '链接',
      message: url,
      primaryButton: {
        value: '复制链接',
        action: (): void => { this.copyText(url, '已复制链接'); },
      },
      secondaryButton: {
        value: '浏览器打开',
        action: (): void => { this.openUrl(url); },
      },
    });
  }

  private showImagePreview(url: string): void {
    this.previewUrl = url;
  }

  private closeImagePreview(): void {
    this.previewUrl = '';
  }

  @Builder
  ImagePreviewOverlay(): void {
    if (this.previewUrl.length > 0) {
      ImagePreviewDialog({ images: [this.previewUrl],
        onDismiss: (): void => { this.closeImagePreview(); } })
    }
  }

}
