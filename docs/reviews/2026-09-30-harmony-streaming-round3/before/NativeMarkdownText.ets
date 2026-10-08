// API 12 native attributed text. A stable controller receives only changed tails;
// frame callbacks alter appended-range colors without changing or parsing text.
import { FrameCallback, LengthMetrics } from '@kit.ArkUI';
import type { InlineToken } from '@amber/chat-domain';
import { ACCENT, BODY, INK, lineHeightForSize } from '../design/tokens.ets';
import { FONT_SANS, FONT_SANS_BOLD, FONT_MONO } from '../design/fonts.ets';
import { NativeInlineRenderer } from './NativeMarkdownInline.ts';
import type { NativeInlineRun, NativeInlineSurface } from './NativeMarkdownInline.ts';

class NativeMarkdownFadeFrame extends FrameCallback {
  private callback: (timeMs: number) => void;
  constructor(callback: (timeMs: number) => void) { super(); this.callback = callback; }
  onFrame(frameTimeInNano: number): void { this.callback(frameTimeInNano / 1_000_000); }
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
  @Prop lineHeight: number = lineHeightForSize(BODY);
  @Prop textAlign: TextAlign = TextAlign.Start;
  @Prop fillWidth: boolean = true;
  onOpenLink: (url: string) => void = (_url: string): void => {};
  onCopyLink: (url: string) => void = (_url: string): void => {};

  private controller: TextController = new TextController();
  private styledString: MutableStyledString = new MutableStyledString('');
  private renderer: NativeInlineRenderer | null = null;
  private attached: boolean = false;
  private framePending: boolean = false;
  private generation: number = 0;

  aboutToAppear(): void {
    this.styledString = new MutableStyledString('');
    const surface: NativeInlineSurface = {
      append: (runs: NativeInlineRun[], opacity: number): void => {
        this.styledString.appendStyledString(this.makeStyledString(runs, opacity));
      },
      replace: (start: number, count: number, runs: NativeInlineRun[], opacity: number): void => {
        this.styledString.replaceStyledString(start, count, this.makeStyledString(runs, opacity));
      },
      setOpacity: (start: number, runs: NativeInlineRun[], opacity: number): void => {
        this.applyFontStyles(start, runs, opacity);
      },
      publish: (): void => { this.publish(); },
    };
    this.renderer = new NativeInlineRenderer(surface);
    this.onInlinesChanged();
  }

  aboutToDisappear(): void {
    this.attached = false;
    this.generation++;
    this.framePending = false;
    if (this.renderer !== null) this.renderer.clear();
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
    if (this.renderer !== null && this.renderer.update(this.inlineRuns(), this.streaming)) this.requestFrame();
  }

  private onPresentationChanged(): void {
    if (this.renderer !== null) this.renderer.restyle();
  }

  private fadedColor(color: string, opacity: number): string {
    const raw: string = color.substring(1);
    const alpha: number = raw.length === 8 ? parseInt(raw.substring(0, 2), 16) : 255;
    const rgb: string = raw.length === 8 ? raw.substring(2) : raw;
    const byte: string = Math.round(alpha * opacity).toString(16).padStart(2, '0');
    return `#${byte}${rgb}`;
  }

  private fontStyle(run: NativeInlineRun, opacity: number): TextStyle {
    const bold: boolean = run.kind === 'bold';
    const code: boolean = run.kind === 'code';
    const color: string = run.kind === 'link' || (code && this.fontColor === INK) ? ACCENT : this.fontColor;
    // FONT is replaced as a whole by setStyle; every frame keeps all typography.
    return new TextStyle({
      fontColor: this.fadedColor(color, opacity),
      fontSize: LengthMetrics.fp(code ? this.fontSize - 1 : this.fontSize),
      fontFamily: code ? FONT_MONO : bold ? this.boldFontFamily : this.fontFamily,
      fontWeight: bold ? FontWeight.Bold : this.fontWeight,
      fontStyle: run.kind === 'italic' ? FontStyle.Italic : FontStyle.Normal,
    });
  }

  private makeStyledString(runs: NativeInlineRun[], opacity: number): StyledString {
    let text: string = '';
    const styles: StyleOptions[] = [];
    for (const run of runs) {
      const start: number = text.length;
      text += run.text;
      styles.push({ start, length: run.text.length, styledKey: StyledStringKey.FONT,
        styledValue: this.fontStyle(run, opacity) });
      if (run.kind === 'link') {
        styles.push({ start, length: run.text.length, styledKey: StyledStringKey.DECORATION,
          styledValue: new DecorationStyle({ type: TextDecorationType.Underline,
            color: this.fadedColor(ACCENT, opacity) }) });
        styles.push({ start, length: run.text.length, styledKey: StyledStringKey.GESTURE,
          styledValue: new GestureStyle({
            onClick: (): void => { this.onOpenLink(run.url); },
            onLongPress: (): void => { this.onCopyLink(run.url); },
          }) });
      }
    }
    return new StyledString(text, styles);
  }

  private applyFontStyles(start: number, runs: NativeInlineRun[], opacity: number): void {
    let offset: number = start;
    for (const run of runs) {
      this.styledString.setStyle({ start: offset, length: run.text.length, styledKey: StyledStringKey.FONT,
        styledValue: this.fontStyle(run, opacity) });
      if (run.kind === 'link') {
        this.styledString.setStyle({ start: offset, length: run.text.length,
          styledKey: StyledStringKey.DECORATION,
          styledValue: new DecorationStyle({ type: TextDecorationType.Underline,
            color: this.fadedColor(ACCENT, opacity) }) });
      }
      offset += run.text.length;
    }
  }

  private publish(): void {
    if (this.attached) this.controller.setStyledString(this.styledString);
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
      .onAppear((): void => {
        this.attached = true;
        this.publish();
        this.requestFrame();
      })
  }
}
