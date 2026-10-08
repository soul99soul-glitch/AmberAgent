// One local ArkWeb host per math-containing Markdown block; normal blocks remain native.
import { webview } from '@kit.ArkWeb';
import { BODY, INK, ACCENT, INK3, LINE, SURFACE2 } from '../design/tokens.ets';
import { FONT_SANS } from '../design/fonts.ets';

interface MathRenderInfo {
  kind: string;
  revision: number;
  height: number;
  url: string;
}

interface MathBlockStyle {
  size: number;
  lineHeight: number;
  color: string;
  accent: string;
  muted: string;
  line: string;
  surface: string;
  family: string;
  headingFamily: string;
  reading: boolean;
}

@Component
export struct MathRichBlock {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @Prop @Watch('renderChanged') source: string = '';
  @Prop @Watch('renderChanged') fontSize: number = BODY;
  @Prop @Watch('renderChanged') lineHeight: number = 26;
  @Prop @Watch('renderChanged') fontColor: string = INK;
  @Prop @Watch('renderChanged') fontFamily: string = FONT_SANS;
  @Prop @Watch('renderChanged') headingFamily: string = FONT_SANS;
  @Prop @Watch('renderChanged') readingMode: boolean = false;
  onOpenLink: (url: string) => void = (_url: string): void => {};
  onOpenImage: (url: string) => void = (_url: string): void => {};
  onVisibleGrow: () => void = (): void => {};
  @State private renderedHeight: number = 40;
  private controller: webview.WebviewController = new webview.WebviewController();
  private attached: boolean = false;
  private alive: boolean = false;
  private revision: number = 0;

  aboutToAppear(): void { this.alive = true; }

  aboutToDisappear(): void {
    this.alive = false;
    if (this.attached) {
      this.controller.deleteJavaScriptRegister('AmberMath');
    }
    this.attached = false;
  }

  private renderChanged(): void {
    this.revision++;
    this.renderNow();
  }

  private renderNow(): void {
    if (!this.attached || !this.alive || this.source.length === 0) return;
    const style: MathBlockStyle = {
      size: this.fontSize, lineHeight: this.lineHeight, color: this.fontColor,
      accent: ACCENT, muted: INK3, line: LINE, surface: SURFACE2,
      family: this.fontFamily, headingFamily: this.headingFamily, reading: this.readingMode,
    };
    const source: string = this.source.replace(/\u2028/g, '\\u2028').replace(/\u2029/g, '\\u2029');
    this.controller.runJavaScript(
      `window.renderAmberMathBlock(${source}, ${JSON.stringify(style)}, ${this.revision})`)
      .catch((error: Error): void => { console.warn(`[MathRichBlock] ${error.message}`); });
  }

  private hostMessage(raw: string): void {
    if (!this.alive) return;
    const info: MathRenderInfo = JSON.parse(raw) as MathRenderInfo;
    if (info.revision !== this.revision) return;
    if (info.kind === 'link') this.onOpenLink(info.url);
    else if (info.kind === 'image') this.onOpenImage(info.url);
    else if (info.kind === 'measure' && Number.isFinite(info.height) && info.height > 0 &&
      Math.abs(this.renderedHeight - info.height) > 0.5) {
      this.renderedHeight = info.height;
      this.onVisibleGrow();
    }
  }

  build(): void {
    Web({ src: $rawfile('math/host.html'), controller: this.controller })
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
      .width('100%')
      .height(this.renderedHeight)
      .backgroundColor(Color.Transparent)
      .javaScriptAccess(true)
      .domStorageAccess(false)
      .verticalScrollBarAccess(false)
      .horizontalScrollBarAccess(false)
      .javaScriptProxy({
        object: { postMessage: (raw: string): void => { this.hostMessage(raw); } },
        name: 'AmberMath', methodList: ['postMessage'], controller: this.controller,
      })
      .onPageEnd((): void => {
        this.attached = true;
        this.renderNow();
      })
  }
}
