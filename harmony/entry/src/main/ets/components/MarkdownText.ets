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
  orderedListLabel,
  composeLatex,
  StreamingDisplayState,
  MarkdownCache,
} from '@amber/chat-domain';
import type {
  MarkdownBlock,
  MarkdownBlockTable,
  InlineToken,
  InlineMathToken,
  HighlightToken,
  MarkdownCacheResult,
} from '@amber/chat-domain';
import { ACCENT, BODY, INK, INK3, LABEL, LINE, META, MONO_SMALL, SURFACE2, TEXT_STYLES, lineHeightForSize } from '../design/tokens.ets';
import { FONT_SANS, FONT_SANS_BOLD, FONT_SANS_MEDIUM, FONT_MONO, FONT_SERIF } from '../design/fonts.ets';
import { Icon } from './ui/Icon.ets';
import { promptAction } from '@kit.ArkUI';
import { pasteboard } from '@kit.BasicServicesKit';
import { common } from '@kit.AbilityKit';
import { image } from '@kit.ImageKit';
import { MermaidBlock } from './MermaidBlock.ets';
import { savePixelMapToAlbum } from '../platform_impl/ImageSaveSupport.ets';

// ===== P3:流式逐字 reveal + Markdown 增量解析(Phase 3 计划 §4) =====
// 仅对「活动流式尾消息的最后文本块」启用 pacing(streaming=true 且 streamKey 非空);
// 历史/非尾文本即时完整渲染(streaming=false → 直接 settle)。
// 语义对齐 Android rememberStreamingDisplayText(StreamingDisplay.kt):
//   - StreamingDisplayState.advance(注入 nowMs,定时器只负责喂时间 → 零时钟依赖);
//   - stream=false 终态 drain(FINAL 速率)而非丢弃可见文本;
//   - 状态按 messageId+partKey 存模块级注册表,跨 streamingTail → rows 迁移保活。

// pacing 定时器节拍(≈60fps;ArkUI 无 withFrameNanos,用 setInterval 等价物登记)
const PACING_TICK_MS: number = 16;
// 流式 Markdown 解析节流(≥200ms,对齐 deferStreamingMarkdownParse 语义)
const MARKDOWN_STREAM_PARSE_THROTTLE_MS: number = 200;
// 可见增长回调最小间隔:避免每 tick scrollEdge 风暴(仍受 ChatPage 既有 follow 守卫)
const VISIBLE_GROW_CALLBACK_MIN_INTERVAL_MS: number = 100;
// 模块级流式状态注册表上限(有界,LRU 淘汰兜底泄漏)
const STREAM_STATE_REGISTRY_MAX: number = 64;

// 逐字 reveal 状态注册表:跨组件重建(streamingTail → rows)保活同一消息的 pacing 状态。
const streamStateRegistry: Map<string, StreamingDisplayState> = new Map();

// 全局 Markdown 增量解析缓存(32 条目 / 200k 字符,精确 + 前缀增量,有界)。
const markdownCache: MarkdownCache = new MarkdownCache();

const getStreamState = (key: string): StreamingDisplayState => {
  let state: StreamingDisplayState | undefined = streamStateRegistry.get(key);
  if (state === undefined) {
    state = new StreamingDisplayState();
  } else {
    streamStateRegistry.delete(key); // LRU touch
  }
  streamStateRegistry.set(key, state);
  // 有界淘汰:超出上限移除最旧条目(Map 保持插入序)
  while (streamStateRegistry.size > STREAM_STATE_REGISTRY_MAX) {
    let eldest: string = '';
    let found: boolean = false;
    streamStateRegistry.forEach((_v: StreamingDisplayState, k: string): void => {
      if (!found) {
        eldest = k;
        found = true;
      }
    });
    if (!found) break;
    streamStateRegistry.delete(eldest);
  }
  return state;
};

const getStreamStateIfPresent = (key: string): StreamingDisplayState | undefined =>
  streamStateRegistry.get(key);

const releaseStreamState = (key: string): void => {
  streamStateRegistry.delete(key);
};

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
export struct MarkdownText {
  @Prop @Watch('onContentChanged') content: string = '';
  @Prop fontSize: number = BODY;
  @Prop fontColor: string = INK;
  // 阅读模式行高倍率(0 = 走契约档;长文场景传 1.7 左右)
  @Prop lineHeightScale: number = 0;
  // 阅读模式:衬线正文 + 两端对齐 + 段落间距(深读长文用;聊天消息保持默认)
  @Prop readingMode: boolean = false;
  // 聊天字体族(DisplaySetting.chatFontFamily):'DEFAULT' | 'SERIF' | 'MONO'
  @Prop chatFontFamily: string = 'DEFAULT';
  // LaTeX 渲染开关(DisplaySetting.enableLatexRendering);关闭时公式显示源码
  @Prop enableLatexRendering: boolean = true;
  // 收缩包裹场景(用户气泡 maxWidth 82%)需 'auto',否则 100% 使测宽失效不换行
  @Prop fillWidth: boolean = true;
  // P3:是否活动流式尾(仅最后文本块传 true)→ 启动逐字 reveal
  @Prop @Watch('onStreamingChanged') streaming: boolean = false;
  // P3:稳定键(messageId + partKey);非空 = 参与 pacing/增量缓存;
  //   空 = 历史/非流式内容,即时完整渲染(推理卡/其他组件不受影响)
  @Prop streamKey: string = '';
  // P3:可见文本增长回调(逐字/drain 推进时触发,供 ChatPage 复用既有
  //   followStreamingUpdate,不新建第二套滚动状态机;内部节流防风暴)
  onVisibleGrow: () => void = (): void => {};
  // F16:代码块显示设置(默认 false = 保持既有外观)
  @Prop codeBlockAutoCollapse: boolean = false;
  @Prop showLineNumbers: boolean = false;
  @State private codeExpanded: Record<string, boolean> = {};
  @State private displayText: string = '';
  @State private displayBlocks: MarkdownBlock[] = [];
  private highlightCache: Map<string, HighlightToken[]> = new Map<string, HighlightToken[]>();
  private pacingTimer: number = -1;
  private lastParseMs: number = 0;
  private lastParsedText: string = '';
  private lastGrowCallbackMs: number = 0;
  @State private previewUrl: string = '';
  @State private previewScale: number = 1;
  private pinchStartScale: number = 1;

  aboutToAppear(): void {
    this.updateDisplay();
  }

  // 不在此释放 streamStateRegistry:streamingTail → rows 迁移中,同 key 的
  // 新组件实例仍需状态完成终态 drain;注册表有界(LRU)兜底泄漏。
  aboutToDisappear(): void {
    this.stopPacingTimer();
  }

  private onContentChanged(): void {
    this.updateDisplay();
  }

  private onStreamingChanged(): void {
    this.updateDisplay();
  }

  // 统一显示入口:根据 streaming/streamKey 决定 settle 或 pacing/drain。
  private updateDisplay(): void {
    const key: string = this.streamKey;
    if (key.length === 0) {
      this.settleDisplay();
      return;
    }
    const state: StreamingDisplayState | undefined = getStreamStateIfPresent(key);
    if (this.content.length === 0) {
      // 工具续跑会同时保留旧消息行和同 id 的流式预览。旧行不能释放
      // 正在被预览使用的状态，否则 reveal 会从头重启。
      if (state === undefined || state.content.length === 0) releaseStreamState(key);
      this.settleDisplay();
      return;
    }
    if (this.streaming) {
      // 活动流式:确保定时器运行并立即推进一步(首帧即出字)
      this.ensurePacingTimer();
      this.tickPacing();
      return;
    }
    if (state !== undefined && state.content !== this.content) {
      // 同 key 的活动流式实例拥有不同的新内容时，当前旧行只做静态渲染，
      // 不推进或释放共享状态，避免两个定时器交替覆盖 target。
      this.settleDisplay();
      return;
    }
    if (state !== undefined && state.visibleLength < this.content.length) {
      // stream=false 但存在待清空 backlog → 继续终态 drain(不丢可见文本)
      this.ensurePacingTimer();
      this.tickPacing();
      return;
    }
    if (state !== undefined) releaseStreamState(key);
    this.settleDisplay();
  }

  // 非 pacing 路径:完整内容即时渲染(历史/用户气泡/推理卡/已定型内容)
  private settleDisplay(): void {
    this.stopPacingTimer();
    this.displayText = this.content;
    this.parseDisplay(true);
  }

  private ensurePacingTimer(): void {
    if (this.pacingTimer !== -1) return;
    this.pacingTimer = setInterval((): void => {
      this.tickPacing();
    }, PACING_TICK_MS);
  }

  private stopPacingTimer(): void {
    if (this.pacingTimer !== -1) {
      clearInterval(this.pacingTimer);
      this.pacingTimer = -1;
    }
  }

  // 一帧 pacing:advance(注入 nowMs)→ 发射则更新显示文本并通知增长回调。
  private tickPacing(): void {
    const key: string = this.streamKey;
    if (key.length === 0) {
      this.stopPacingTimer();
      return;
    }
    const state: StreamingDisplayState = getStreamState(key);
    const now: number = Date.now();
    const emitted: boolean = state.advance(this.content, this.streaming, now);
    const slice: string = this.content.substring(0, state.visibleLength);
    if (slice !== this.displayText) {
      // 内容替换(前缀失配/缩短 → advance 已 settle)→ 立即强制重解析,避免
      // 节流窗口内短暂展示旧块;前缀增长 → 走常规节流
      const replaced: boolean = this.displayText.length > 0 && slice.length > 0 &&
        !slice.startsWith(this.displayText);
      this.displayText = slice;
      this.parseDisplay(replaced);
    }
    if (emitted && slice.length > 0) {
      this.notifyVisibleGrow(now);
    }
    // 状态机收尾:
    if (!this.streaming && state.visibleLength >= state.content.length) {
      // 终态 drain 完成 → 释放注册表条目并停表(最终内容已在末帧解析)
      releaseStreamState(key);
      this.stopPacingTimer();
    } else if (this.streaming && state.visibleLength >= state.content.length) {
      // 流式中 backlog 已空:停表,待下一次内容增长由 onContentChanged 重启
      this.stopPacingTimer();
    }
  }

  // 可见文本确实增长才回调(非每 tick),且受最小间隔节流 + ChatPage 既有 follow 守卫
  private notifyVisibleGrow(now: number): void {
    if (now - this.lastGrowCallbackMs < VISIBLE_GROW_CALLBACK_MIN_INTERVAL_MS) return;
    this.lastGrowCallbackMs = now;
    this.onVisibleGrow();
  }

  // 解析节流:流式中 ≥200ms 一次;settle/drain(streaming=false)立即解析(精确缓存兜底)
  private parseDisplay(force: boolean): void {
    const text: string = this.displayText;
    if (text === this.lastParsedText) return;
    if (!force && this.streaming && this.streamKey.length > 0 &&
      Date.now() - this.lastParseMs < MARKDOWN_STREAM_PARSE_THROTTLE_MS) {
      return;
    }
    const prevLen: number = this.lastParsedText.length;
    this.lastParseMs = Date.now();
    this.lastParsedText = text;
    this.displayBlocks = this.parseBlocks(text, prevLen);
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
      if (this.displayText.trim().length > 0) {
        if (this.displayBlocks.length === 0) {
          // 安全兜底:解析未产出块(理论不会,parseMarkdown 有异常回退)→ 纯文本段落
          Text(this.displayText)
            .width(this.fillWidth ? '100%' : 'auto')
            .fontSize(this.fontSize)
            .fontColor(this.fontColor)
            .lineHeight(lineHeightForSize(this.fontSize))
            .fontFamily(FONT_SANS)
        } else {
          ForEach(
            this.displayBlocks,
            (block: MarkdownBlock, idx: number): void => {
              if (block.kind === 'heading') {
                this.RenderHeading(block)
              } else if (block.kind === 'paragraph') {
                this.RenderParagraph(block)
              } else if (block.kind === 'code_block') {
                this.RenderCodeBlock(block, idx)
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
          if (this.displayText.length > this.lastParsedText.length) {
            Text(this.displayText.substring(this.lastParsedText.length))
              .width(this.fillWidth ? '100%' : 'auto')
              .fontSize(this.fontSize)
              .fontColor(this.fontColor)
              .lineHeight(lineHeightForSize(this.fontSize))
              .fontFamily(FONT_SANS)
              .margin({ bottom: 4 })
          }
        }
      }
    }
    .width(this.fillWidth ? '100%' : 'auto')
    .alignItems(HorizontalAlign.Start)
    .bindContentCover(this.previewUrl.length > 0, this.ImagePreviewOverlay(), {
      onWillDismiss: (): void => { this.closeImagePreview(); },
    })
  }

  // ===== 预计算方法(非 @Builder) =====

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

  private tableTextAlign(block: MarkdownBlockTable, column: number): TextAlign {
    const alignment = block.alignments[column] ?? 'left';
    if (alignment === 'center') return TextAlign.Center;
    if (alignment === 'right') return TextAlign.End;
    return TextAlign.Start;
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
    this.previewScale = 1;
    this.pinchStartScale = 1;
  }

  private clampPreviewScale(value: number): number {
    return Math.max(1, Math.min(3, value));
  }

  @Builder
  ImagePreviewOverlay(): void {
    if (this.previewUrl.length > 0) {
      Stack({ alignContent: Alignment.Center }) {
        Image(this.previewUrl)
          .width('100%')
          .height('100%')
          .objectFit(ImageFit.Contain)
          .scale({ x: this.previewScale, y: this.previewScale })
          .onClick((): void => { this.closeImagePreview(); })
          .gesture(
            PinchGesture()
              .onActionStart((): void => { this.pinchStartScale = this.previewScale; })
              .onActionUpdate((event: GestureEvent): void => {
                this.previewScale = this.clampPreviewScale(this.pinchStartScale * event.scale);
              })
          )
        Icon({ name: 'close', iconSize: 24, color: '#FFFFFF' })
          .width(44)
          .height(44)
          .position({ top: 24, right: 16 })
          .onClick((): void => { this.closeImagePreview(); })
      }
      .width('100%')
      .height('100%')
      .backgroundColor('#000000')
    }
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
    this.codeExpanded = copy;
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

  @Builder
  RenderHeading(block: MarkdownBlock): void {
    Text() {
      if (block.kind === 'heading') {
        ForEach(
          block.inlines,
          (tok: InlineToken, idx: number): void => {
            this.RenderInlineToken(tok, this.headingSize(block.level), this.fontColor, FontWeight.Bold)
          },
          (tok: InlineToken, idx: number): string => `h-${idx}-${tok.type}`,
        )
      }
    }
    .width('100%')
    .fontSize(this.headingSize(block.kind === 'heading' ? block.level : 1))
    .fontColor(this.fontColor)
    .lineHeight(lineHeightForSize(this.headingSize(block.kind === 'heading' ? block.level : 1)))
    .fontWeight(FontWeight.Bold)
    .fontFamily(this.headFamily())
    .margin({ top: this.readingMode
      ? (block.kind === 'heading' && block.level <= 2 ? 22 : 14)
      : (block.kind === 'heading' && block.level <= 2 ? 12 : 8),
      bottom: this.readingMode ? 8 : 4 })
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
    .width(this.fillWidth ? '100%' : 'auto')
    .fontSize(this.fontSize)
    .fontColor(this.fontColor)
    .lineHeight(this.effLineHeight(this.fontSize))
    .fontFamily(this.bodyFamily())
    .textAlign(this.isDisplayMathParagraph(block)
      ? TextAlign.Center
      : (this.readingMode ? TextAlign.JUSTIFY : TextAlign.Start))
    .margin({ top: this.readingMode ? 0 : 2, bottom: this.readingMode ? 12 : 4 })
  }

  @Builder
  RenderBlockquote(block: MarkdownBlock): void {
    Text() {
      if (block.kind === 'blockquote') {
        ForEach(
          block.inlines,
          (tok: InlineToken, idx: number): void => {
              this.RenderInlineToken(tok, this.fontSize, this.fontColor, FontWeight.Normal)

          },
          (tok: InlineToken, idx: number): string => `bq-${idx}-${tok.type}`,
        )
      }
    }
    .width('100%')
    .fontSize(this.fontSize)
    .fontColor(this.fontColor)
    .lineHeight(this.effLineHeight(this.fontSize))
    .fontFamily(this.bodyFamily())
    .border({ width: { left: 3 }, color: LINE })
    .padding({ left: 10 })
    .margin({ top: 4, bottom: 4 })
  }

  // 阅读模式字体族:正文/标题衬线(粗体经 fontWeight 合成)
  // 聊天字体族(DisplaySetting.chatFontFamily):DEFAULT/SERIF/MONO 三选
  private bodyFamily(): string {
    if (this.readingMode) return FONT_SERIF;
    if (this.chatFontFamily === 'SERIF') return FONT_SERIF;
    if (this.chatFontFamily === 'MONO') return FONT_MONO;
    return FONT_SANS;
  }

  private headFamily(): string {
    if (this.readingMode) return FONT_SERIF;
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
  RenderList(block: MarkdownBlock): void {
    Column() {
      if (block.kind === 'list') {
        ForEach(
          block.items,
          (itemInlines: InlineToken[], idx: number): void => {
            Row() {
              // 标记与正文同 lineHeight 且顶对齐:多行条目圆点不再垂直漂移
              Text(block.ordered ? this.orderedLabel(block, idx) : '•')
                .fontSize(this.fontSize)
                .fontColor(this.fontColor)
                .fontFamily(this.bodyFamily())
                .lineHeight(this.effLineHeight(this.fontSize))
                .width(block.ordered ? 32 : 16)
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
              .lineHeight(this.effLineHeight(this.fontSize))
              .fontFamily(this.bodyFamily())
              .layoutWeight(1)
            }
            .width('100%')
            .alignItems(VerticalAlign.Top)
            .padding({ left: this.listDepthAt(block, idx) * 12 })
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
        .fontSize(12)
        .fontFamily(FONT_MONO)
        .lineHeight(18)
      } else if (plainText.length > 0) {
        Text(plainText)
          .fontSize(12)
          .fontColor(CODE_TEXT)
          .fontFamily(FONT_MONO)
          .lineHeight(18)
      } else {
        Text(' ').fontSize(12).fontFamily(FONT_MONO).lineHeight(18)
      }
    }
    .alignItems(VerticalAlign.Top)
  }

  // D7-3:mermaid 判定/块 id/导出(componentSnapshot → ImageSaveSupport)
  private isMermaid(block: MarkdownBlock): boolean {
    return block.kind === 'code_block' && block.lang.trim().toLowerCase() === 'mermaid';
  }

  private mermaidBlockId(block: MarkdownBlock, bi: number): string {
    const key: string = this.streamKey.replace(/[^A-Za-z0-9_]/g, '_');
    return `mmd_${key}_${bi}`;
  }

  private exportMermaid(block: MarkdownBlock, bi: number): void {
    const id: string = this.mermaidBlockId(block, bi);
    this.getUIContext().getComponentSnapshot().get(id)
      .then((pm: image.PixelMap): void => {
        savePixelMapToAlbum(pm).catch((): void => {});
      })
      .catch((): void => {
        promptAction.showToast({ message: '导出失败:渲染尚未就绪', duration: 1500 });
      });
  }

  @Builder
  RenderCodeBlock(block: MarkdownBlock, bi: number): void {
    Column() {
      if (block.kind === 'code_block') {
        Row() {
          Text(block.lang.length > 0 ? block.lang : 'code')
            .fontSize(MONO_SMALL)
            .fontColor('#9AA3B2')
            .fontFamily(FONT_MONO)
            .layoutWeight(1)
          // D7-3:mermaid 导出图片(componentSnapshot → 相册)
          if (this.isMermaid(block)) {
            Text('导出')
              .fontSize(META)
              .lineHeight(lineHeightForSize(META))
              .fontFamily(FONT_SANS)
              .fontColor(CODE_TEXT)
              .width(48)
              .height(44)
              .textAlign(TextAlign.Center)
              .onClick((): void => { this.exportMermaid(block, bi); })
              .accessibilityText('导出图片到相册')
          }
          if (this.codeBlockAutoCollapse && this.codeLineCount(block) > CODE_COLLAPSE_THRESHOLD) {
            Text(this.codeIsCollapsed(block, bi) ? '展开' : '收起')
              .fontSize(META)
              .lineHeight(lineHeightForSize(META))
              .fontFamily(FONT_SANS)
              .fontColor(CODE_TEXT)
              .width(48)
              .height(44)
              .textAlign(TextAlign.Center)
              .onClick((): void => {
                this.toggleCodeExpanded(block, bi);
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
            .onClick((): void => { this.copyText(block.code, '已复制代码'); })
        }
        .width('100%')
        .height(44)
        .padding({ right: 4 })
        .alignItems(VerticalAlign.Center)
        .backgroundColor(CODE_HEADER_BG)
        .border({ width: { bottom: 0.5 }, color: '#3A404B' })

        // D7-3:mermaid 图渲染(本地 mermaid.min.js 沙箱;失败降级源码照常展示)
        if (this.isMermaid(block)) {
          MermaidBlock({
            source: block.code,
            blockId: this.mermaidBlockId(block, bi),
          })
            .margin({ bottom: 8 })
        }

        // 横向滚动:长行不折行,行 Text 不限宽(内容自适应),短行靠 Column 左对齐
        Scroll() {
          Column() {
            if (block.lang.length > 0) {
              ForEach(
                this.visibleHighlightLines(block, bi),
                (lineTokens: HighlightToken[], idx: number): void => {
                  this.CodeLineText(idx, lineTokens, '')
                },
                (lineTokens: HighlightToken[], idx: number): string => `codeline-${idx}`,
              )
            } else {
              if (this.showLineNumbers || this.codeBlockAutoCollapse) {
                ForEach(
                  this.visiblePlainLines(block, bi),
                  (line: string, idx: number): void => {
                    this.CodeLineText(idx, [], line)
                  },
                  (line: string, idx: number): string => `codetext-${idx}`,
                )
              } else {
                // 默认路径:与既有渲染一致(整段单个 Text,不逐行)
                Text(block.code)
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
        if (this.codeIsCollapsed(block, bi)) {
          Text('已折叠，点击展开')
            .fontSize(LABEL)
            .fontColor(CODE_LINE_NO)
            .fontFamily(FONT_MONO)
            .width('100%')
            .padding({ top: 6 })
            .onClick((): void => {
              this.toggleCodeExpanded(block, bi);
            })
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
                    this.RenderInlineToken(tok, META, this.fontColor, FontWeight.Medium)
                  },
                  (tok: InlineToken, ti: number): string => `th-${ci}-${ti}-${tok.type}`,
                )
              }
              .fontSize(META)
              .fontColor(this.fontColor)
              .lineHeight(lineHeightForSize(META))
              .fontFamily(FONT_SANS_MEDIUM)
              .textAlign(this.tableTextAlign(block, ci))
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
                        this.RenderInlineToken(tok, META, this.fontColor, FontWeight.Normal)
                      },
                      (tok: InlineToken, ti: number): string => `td-${ri}-${ci}-${ti}-${tok.type}`,
                    )
                  }
                  .fontSize(META)
                  .fontColor(this.fontColor)
                  .lineHeight(lineHeightForSize(META))
                  .fontFamily(FONT_SANS)
                  .textAlign(this.tableTextAlign(block, ci))
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

  // 权重→家族名(字重由注册的静态档承载;仅设 fontWeight 会落到合成加粗)
  private spanFamily(weight: FontWeight): string {
    if (this.readingMode) return FONT_SERIF;
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
      if (this.enableLatexRendering) {
        // LaTeX 子集 → Unicode 合成(math_compose 纯逻辑);展示公式放大 2pt
        Span(composeLatex(tok.latex))
          .fontSize(tok.display ? size + 2 : size)
          .fontColor(color)
          .fontWeight(weight)
          .fontStyle(FontStyle.Italic)
          .fontFamily(FONT_SERIF)
      } else {
        // 设置关闭时原样展示 $..$ 源码(不静默吞掉公式内容)
        Span(tok.display ? `$$${tok.latex}$$` : `$${tok.latex}$`)
          .fontSize(size)
          .fontColor(color)
          .fontFamily(FONT_MONO)
      }
    }
  }
}
