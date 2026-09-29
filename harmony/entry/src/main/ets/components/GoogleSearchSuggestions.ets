import { webview } from '@kit.ArkWeb';
import { INK3, LABEL } from '../design/tokens.ets';

// Provider HTML/CSS stays intact; FIT_CONTENT follows its actual document height.
@Component
export struct GoogleSearchSuggestions {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop @Watch('loadHtml') html: string = '';
  onOpenUrl: (url: string) => void = (_url: string): void => {};
  @StorageProp('darkMode') @Watch('refreshColors') darkMode: boolean = false;
  @StorageProp('themePackId') @Watch('refreshColors') themePackId: string = '';
  @State mutedColor: string = INK3;
  @State loadError: string = '';
  private controller: webview.WebviewController = new webview.WebviewController();
  private attached: boolean = false;
  private alive: boolean = false;

  aboutToAppear(): void { this.alive = true; }
  aboutToDisappear(): void { this.alive = false; this.attached = false; }

  private refreshColors(): void { this.mutedColor = INK3; }

  private loadHtml(): void {
    if (!this.alive || !this.attached) return;
    const document: string = '<!DOCTYPE html><html><head><meta charset="utf-8">'
      + '<meta name="viewport" content="width=device-width, initial-scale=1.0">'
      + '<meta name="color-scheme" content="light dark"></head><body>'
      + this.html + '</body></html>';
    this.loadError = '';
    try {
      // Same controller-attached loadData encoding as DeepReadArticlePage; avoids CSS # URL parsing.
      this.controller.loadData(encodeURIComponent(document).replace(/#/g, '%23'), 'text/html', 'UTF-8', ' ', ' ');
    } catch (_error) { this.loadError = 'Google 搜索建议无法显示。'; }
  }

  private handleNavigation(request: WebResourceRequest): boolean {
    const url: string = request.getRequestUrl();
    if (url === 'about:blank' || url.startsWith('data:')) return false;
    // One navigation hook owns external links; no second interception/open path.
    if (this.alive && request.isMainFrame() && request.isRequestGesture() && /^https?:\/\//i.test(url)) {
      this.onOpenUrl(url);
    }
    return true;
  }

  build(): void {
    Column() {
      Web({ src: 'about:blank', controller: this.controller, renderMode: RenderMode.SYNC_RENDER })
        .width('100%')
        .layoutMode(WebLayoutMode.FIT_CONTENT)
        .overScrollMode(OverScrollMode.NEVER)
        .backgroundColor(Color.Transparent)
        .javaScriptAccess(false)
        .domStorageAccess(false)
        .multiWindowAccess(false)
        .darkMode(this.darkMode ? WebDarkMode.On : WebDarkMode.Off)
        .forceDarkAccess(false)
        .onControllerAttached((): void => { this.attached = true; this.loadHtml(); })
        .onOverrideUrlLoading((request: WebResourceRequest): boolean => this.handleNavigation(request))
      if (this.loadError.length > 0) {
        Text(this.loadError).fontSize(LABEL).fontColor(this.mutedColor).width('100%')
      }
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1).width('100%')
  }
}
