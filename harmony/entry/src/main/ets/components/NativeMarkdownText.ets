// Text updates require measurement; fade frames only invalidate the drawing layer.
// API 14+ masks appended ranges. API 12/13 keeps immediate native text updates.
import { FrameCallback, LengthMetrics } from '@kit.ArkUI';
import { common2D, drawing, text } from '@kit.ArkGraphics2D';
import { deviceInfo } from '@kit.BasicServicesKit';
import type { InlineToken } from '@amber/chat-domain';
import { ACCENT, BODY, INK, lineHeightForSize } from '../design/tokens.ets';
import { FONT_SANS, FONT_SANS_BOLD, FONT_MONO } from '../design/fonts.ets';
import { NativeInlineRenderer } from './NativeMarkdownInline.ts';
import type { NativeInlineRun, NativeInlineSurface, NativeInlineFadeRange } from './NativeMarkdownInline.ts';

class NativeMarkdownFadeFrame extends FrameCallback {
  private callback: (timeMs: number) => void;
  constructor(callback: (timeMs: number) => void) { super(); this.callback = callback; }
  onFrame(frameTimeInNano: number): void { this.callback(frameTimeInNano / 1_000_000); }
}

interface NativeFadeGeometry {
  start: number;
  length: number;
  rects: common2D.Rect[];
}

function supportsNativeRangeFade(): boolean {
  return canIUse('SystemCapability.ArkUI.ArkUI.Full') &&
    canIUse('SystemCapability.Graphics.Drawing') && deviceInfo.sdkApiVersion >= 14;
}

class NativeMarkdownFadeModifier extends DrawModifier {
  private controller: TextController;
  private enabled: boolean;
  private ranges: NativeInlineFadeRange[] = [];
  private geometry: NativeFadeGeometry[] = [];
  private width: number = -1;
  private height: number = -1;
  private brush: drawing.Brush = new drawing.Brush();

  constructor(controller: TextController, enabled: boolean) {
    super();
    this.controller = controller;
    this.enabled = enabled;
    this.brush.setBlendMode(drawing.BlendMode.DST_OUT);
  }

  setRanges(ranges: NativeInlineFadeRange[]): void { this.ranges = ranges; }
  clearGeometry(): void { this.geometry = []; }

  drawFront(context: DrawContext): void {
    if (!this.enabled || this.ranges.length === 0) return;
    if (context.size.width !== this.width || context.size.height !== this.height) {
      this.width = context.size.width;
      this.height = context.size.height;
      this.clearGeometry();
    }
    for (const range of this.ranges) {
      let cached: NativeFadeGeometry | undefined = this.geometry.find((entry: NativeFadeGeometry): boolean => {
        return entry.start === range.start && entry.length === range.length;
      });
      if (cached === undefined) {
        // drawFront runs after this Text's layout and native content drawing.
        // Both LayoutManager rectangles and drawing.Canvas coordinates are px.
        const boxes = this.controller.getLayoutManager().getRectsForRange(
          { start: range.start, end: range.start + range.length }, text.RectWidthStyle.TIGHT, text.RectHeightStyle.MAX);
        const rects: common2D.Rect[] = [];
        for (const box of boxes) rects.push(box.rect);
        cached = { start: range.start, length: range.length, rects };
        this.geometry.push(cached);
      }
      this.brush.setColor({ alpha: Math.round(255 * (1 - range.opacity)), red: 0, green: 0, blue: 0 });
      context.canvas.attachBrush(this.brush);
      for (const rect of cached.rects) context.canvas.drawRect(rect);
      context.canvas.detachBrush();
    }
  }
}

@Component
export struct NativeMarkdownText {
  @Prop @Watch('onInlinesChanged') inlines: InlineToken[] = [];
  @Prop @Watch('onInlinesChanged') streaming: boolean = false;
  @Prop @Watch('onPresentationChanged') fontSize: number = BODY;
  @Prop @Watch('onPresentationChanged') fontColor: string = INK;
  @Prop @Watch('onPresentationChanged') fontFamily: string = FONT_SANS;
  @Prop @Watch('onPresentationChanged') boldFontFamily: string = FONT_SANS_BOLD;
  @Prop @Watch('onPresentationChanged') fontWeight: FontWeight = FontWeight.Normal;
  @Prop @Watch('onLayoutChanged') lineHeight: number = lineHeightForSize(BODY);
  @Prop @Watch('onLayoutChanged') textAlign: TextAlign = TextAlign.Start;
  @Prop @Watch('onLayoutChanged') fillWidth: boolean = true;
  onOpenLink: (url: string) => void = (_url: string): void => {};
  onCopyLink: (url: string) => void = (_url: string): void => {};

  private controller: TextController = new TextController();
  private fadeSupported: boolean = supportsNativeRangeFade();
  private fadeModifier: NativeMarkdownFadeModifier = new NativeMarkdownFadeModifier(this.controller, this.fadeSupported);
  @State private masking: boolean = false;
  private styledString: MutableStyledString = new MutableStyledString('');
  private renderer: NativeInlineRenderer | null = null;
  private attached: boolean = false;
  private framePending: boolean = false;
  private generation: number = 0;

  aboutToAppear(): void {
    this.styledString = new MutableStyledString('');
    const surface: NativeInlineSurface = {
      append: (runs: NativeInlineRun[], _opacity: number): void => {
        this.styledString.appendStyledString(this.makeStyledString(runs));
      },
      replace: (start: number, count: number, runs: NativeInlineRun[], _opacity: number): void => {
        this.styledString.replaceStyledString(start, count, this.makeStyledString(runs));
      },
      // Alpha belongs to the drawing mask, never the native FONT attribute.
      setOpacity: (_start: number, _runs: NativeInlineRun[], _opacity: number): void => {},
      publish: (contentChanged: boolean): void => { this.publish(contentChanged); },
    };
    this.renderer = new NativeInlineRenderer(surface);
    this.onInlinesChanged();
  }

  aboutToDisappear(): void {
    this.attached = false;
    this.generation++;
    this.framePending = false;
    if (this.renderer !== null) this.renderer.clear();
    this.fadeModifier.setRanges([]);
    this.fadeModifier.clearGeometry();
  }

  private inlineRuns(): NativeInlineRun[] {
    const runs: NativeInlineRun[] = [];
    for (const token of this.inlines) {
      // Images and math use the parent's existing rich path.
      if (token.type === 'image' || token.type === 'math') continue;
      if (token.text.length > 0) runs.push({ kind: token.type, text: token.text,
        url: token.type === 'link' ? token.url : '' });
    }
    return runs;
  }

  private onInlinesChanged(): void {
    if (this.renderer !== null && this.renderer.update(this.inlineRuns(), this.streaming && this.fadeSupported)) this.requestFrame();
  }

  private onPresentationChanged(): void {
    if (this.renderer === null || this.styledString.length === 0) return;
    this.styledString.replaceStyledString(0, this.styledString.length, this.makeStyledString(this.inlineRuns()));
    this.publish(true);
  }

  private onLayoutChanged(): void {
    this.fadeModifier.clearGeometry();
    if (this.attached && this.masking) this.fadeModifier.invalidate();
  }

  private fontStyle(run: NativeInlineRun): TextStyle {
    const bold: boolean = run.kind === 'bold';
    const code: boolean = run.kind === 'code';
    const color: string = run.kind === 'link' || (code && this.fontColor === INK) ? ACCENT : this.fontColor;
    return new TextStyle({
      fontColor: color,
      fontSize: LengthMetrics.fp(code ? this.fontSize - 1 : this.fontSize),
      fontFamily: code ? FONT_MONO : bold ? this.boldFontFamily : this.fontFamily,
      fontWeight: bold ? FontWeight.Bold : this.fontWeight,
      fontStyle: run.kind === 'italic' ? FontStyle.Italic : FontStyle.Normal,
    });
  }

  private makeStyledString(runs: NativeInlineRun[]): StyledString {
    let text: string = '';
    const styles: StyleOptions[] = [];
    for (const run of runs) {
      const start: number = text.length;
      text += run.text;
      styles.push({ start, length: run.text.length, styledKey: StyledStringKey.FONT,
        styledValue: this.fontStyle(run) });
      if (run.kind === 'link') {
        styles.push({ start, length: run.text.length, styledKey: StyledStringKey.DECORATION,
          styledValue: new DecorationStyle({ type: TextDecorationType.Underline,
            color: ACCENT }) });
        styles.push({ start, length: run.text.length, styledKey: StyledStringKey.GESTURE,
          styledValue: new GestureStyle({
            onClick: (): void => { this.onOpenLink(run.url); },
            onLongPress: (): void => { this.onCopyLink(run.url); },
          }) });
      }
    }
    return new StyledString(text, styles);
  }

  private publish(contentChanged: boolean): void {
    const ranges: NativeInlineFadeRange[] = this.renderer === null ? [] : this.renderer.activeFadeRanges();
    this.fadeModifier.setRanges(ranges);
    // Change isolation only at animation boundaries, including the terminal fade.
    this.masking = this.fadeSupported && ranges.length > 0;
    if (contentChanged) this.fadeModifier.clearGeometry();
    if (!this.attached) return;
    if (contentChanged) this.controller.setStyledString(this.styledString);
    this.fadeModifier.invalidate();
  }

  private onTextAppear(): void {
    this.attached = true;
    // The API12-targeted app must bind after attachment even on this API26
    // device; a runtime-version-gated prebind left historical paragraphs empty.
    this.publish(true);
    this.requestFrame();
  }

  private requestFrame(): void {
    if (!this.attached || this.framePending || this.renderer === null || !this.renderer.hasAnimation()) return;
    this.framePending = true;
    const generation: number = this.generation;
    this.getUIContext().postFrameCallback(new NativeMarkdownFadeFrame((timeMs: number): void => {
      if (generation !== this.generation || !this.attached) return;
      this.framePending = false;
      if (this.renderer !== null && this.renderer.frame(timeMs)) this.requestFrame();
    }));
  }

  build(): void {
    Text(undefined, { controller: this.controller })
      .width(this.fillWidth ? '100%' : 'auto')
      .fontSize(this.fontSize)
      .fontColor(this.fontColor)
      .fontFamily(this.fontFamily)
      .fontWeight(this.fontWeight)
      .lineHeight(this.lineHeight)
      .textAlign(this.textAlign)
      .copyOption(CopyOptions.LocalDevice)
      .drawModifier(this.fadeModifier)
      // Isolate DST_OUT to the text, preserving the parent's color or texture.
      .blendMode(this.masking ? BlendMode.SRC_OVER : BlendMode.NONE, BlendApplyType.OFFSCREEN)
      .onAppear((): void => {
        this.onTextAppear();
      })
  }
}
