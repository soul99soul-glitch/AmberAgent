// A show-widget display surface: no JavaScript bridge, network or native tool access.
import { webview } from '@kit.ArkWeb';
import type { GenerativeWidget, GenerativeWidgetAction } from '@amber/chat-domain';
import { widgetDocument, widgetActionPrompt } from '@amber/chat-domain';
import { INK, INK3, LINE, META, LABEL, SURFACE2, ACCENT, accentSoftBg } from '../design/tokens.ets';
import { FONT_SANS } from '../design/fonts.ets';

@Component
export struct GenerativeWidgetCard {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('darkMode') @Watch('requestRender') darkMode: boolean = false;
  @StorageProp('accentColor') accent: string = ACCENT;
  @Prop @Watch('requestRender') widget: GenerativeWidget;
  onAction: (prompt: string) => void = (_prompt: string): void => {};
  onContentGrow: () => void = (): void => {};
  @State renderError: string = '';
  private controller: webview.WebviewController = new webview.WebviewController();
  private attached: boolean = false;
  private alive: boolean = false;
  private renderTimer: number = -1;
  private initialDocumentUrl: string = '';

  aboutToAppear(): void {
    this.alive = true;
    // The initial Web navigation is the actual document, rather than a competing blank page.
    this.initialDocumentUrl = 'data:text/html;charset=utf-8,' +
      encodeURIComponent(widgetDocument(this.widget.html, this.darkMode));
  }
  aboutToDisappear(): void {
    this.alive = false;
    this.attached = false;
    if (this.renderTimer !== -1) clearTimeout(this.renderTimer);
    this.renderTimer = -1;
  }

  private requestRender(): void {
    if (!this.alive || !this.attached || this.renderTimer !== -1) return;
    // Throttle reads the latest HTML, allowing progressive SVG even during continuous deltas.
    this.renderTimer = setTimeout((): void => {
      this.renderTimer = -1;
      this.renderNow();
    }, 220);
  }

  private renderNow(): void {
    if (!this.alive || !this.attached) return;
    try {
      const html: string = widgetDocument(this.widget.html, this.darkMode);
      // URL-encoded data uses the data protocol; do not combine it with a nonempty base URL.
      this.controller.loadData(encodeURIComponent(html), 'text/html', 'UTF-8');
      this.renderError = '';
    } catch (error) { this.renderError = (error as Error).message; }
  }

  build(): void {
    Column({ space: 6 }) {
      Row() {
        Text(this.widget.title).fontSize(META).fontFamily(FONT_SANS).fontColor(INK)
          .layoutWeight(1).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        if (!this.widget.complete) {
          Text('生成中…').fontSize(LABEL).fontColor(INK3).margin({ left: 8 })
        }
      }
      .width('100%').padding({ left: 12, right: 12, top: 10 })
      Scroll() {
        Web({ src: this.initialDocumentUrl, controller: this.controller, renderMode: RenderMode.SYNC_RENDER })
          .width('100%')
          .layoutMode(WebLayoutMode.FIT_CONTENT)
          .overScrollMode(OverScrollMode.NEVER)
          .backgroundColor(Color.Transparent)
          .javaScriptAccess(false)
          .domStorageAccess(false)
          .multiWindowAccess(false)
          .fileAccess(false)
          .onlineImageAccess(false)
          .darkMode(this.darkMode ? WebDarkMode.On : WebDarkMode.Off)
          .forceDarkAccess(false)
          .onControllerAttached((): void => { this.attached = true; this.requestRender(); })
          .onOverrideUrlLoading((request: WebResourceRequest): boolean => {
            const url: string = request.getRequestUrl();
            // Permit only our local document loading and fragment anchors; user links stay blocked.
            if (url === 'about:blank' || url.startsWith('about:blank#')) return false;
            if (url === this.initialDocumentUrl || url.startsWith(this.initialDocumentUrl + '#')) return false;
            return request.isRequestGesture() || !url.startsWith('data:text/html');
          })
          .onAreaChange((oldArea: Area, newArea: Area): void => {
            // Scrolling changes position too; only document size changes require follow updates.
            if (oldArea.width !== newArea.width || oldArea.height !== newArea.height) {
              this.onContentGrow();
            }
          })
      }
      .width('100%').constraintSize({ minHeight: 80, maxHeight: 720 })
      .scrollBar(BarState.Auto)
      if (this.renderError.length > 0 || this.widget.notice.length > 0) {
        Text(this.renderError.length > 0 ? this.renderError : this.widget.notice)
          .fontSize(LABEL).fontColor(INK3).width('100%')
          .padding({ left: 12, right: 12, bottom: 6 })
      }
      if (this.widget.complete && this.widget.actions.length > 0) {
        Flex({ wrap: FlexWrap.Wrap }) {
          ForEach(this.widget.actions, (action: GenerativeWidgetAction, index: number): void => {
            Text(action.label).fontSize(META).fontFamily(FONT_SANS).fontColor(this.accent)
              .constraintSize({ minHeight: 44, minWidth: 44 })
              .padding({ left: 12, right: 12 })
              .borderRadius(22).backgroundColor(accentSoftBg(this.accent))
              .margin({ right: 6, bottom: 6 })
              .onClick((): void => { this.onAction(widgetActionPrompt(this.widget.title, action.instruction)); })
              .accessibilityText(`${action.label}，填入输入框`)
          }, (action: GenerativeWidgetAction, index: number): string => `${action.id}-${index}`)
        }
        .width('100%').padding({ left: 12, right: 12, bottom: 4 })
      }
    }
    .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').backgroundColor(SURFACE2).borderRadius(14)
    .border({ width: 1, color: LINE }).clip(true).margin({ bottom: 8 })
  }
}
