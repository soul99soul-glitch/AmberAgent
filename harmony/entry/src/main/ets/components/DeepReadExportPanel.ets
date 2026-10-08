// Export a single persisted article snapshot; generation UI drafts never enter this panel.
import { common } from '@kit.AbilityKit';
import { webview } from '@kit.ArkWeb';
import {
  DeepReadCacheEntry, deepReadToText, deepReadToMarkdown, isComplete, hasDisplayableDeepReadOutput,
} from '@amber/deepread-domain';
import { getRepository } from '../di/AppContainer.ets';
import { sha256HexUtf8 } from '../platform_impl/EntryLocalToolPorts.ets';
import { renderCapturedDeepReadTemplateHtml, resolveDeepReadArticleTemplate, DeepReadRenderOptions } from '../platform_impl/DeepReadTemplate.ets';
import {
  isDeepReadPdfSupported, deepReadMarkdownToText, shareDeepReadText, saveDeepReadTextFile, shareDeepReadTextFile, saveDeepReadMarkdown,
  shareDeepReadMarkdown, saveDeepReadPdf, shareDeepReadPdf,
} from '../platform_impl/DeepReadExportFiles.ets';
import { BG, SURFACE, INK, INK3, LINE, ACCENT, ERROR, GUTTER } from '../design/tokens.ets';
import { DeepReadDesign as D } from '../design/DeepReadDesign.ets';
import { getProductKind } from '../platform_impl/ProductIdentity.ets';
import { Icon } from './ui/Icon.ets';

const PDF_DOCUMENT_URL: string = 'https://deepread-export.local/article';
const PRINT_CSS: string = '<style>@page{size:A4}'
  + '@media print{body{padding:0}'
  + 'h1,h2,h3{break-after:avoid}figure,.diagram-node,.diagram-edge,.reading > a,.reading > div{break-inside:avoid}'
  + 'pre{white-space:pre-wrap;overflow-wrap:anywhere}table{min-width:0;width:100%}'
  + 'th,td{min-width:0}thead{display:table-header-group}.table-wrap{overflow:visible}}</style>';

@Component
export struct DeepReadExportPanel {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @Prop topicId: string = '';
  @Prop title: string = '';
  @Prop fontScale: number = 1;
  @Prop fontSerif: boolean = false;
  @StorageProp('reduceMotion') reduceMotion: boolean = false;
  @StorageProp('appBackgrounded') appBackgrounded: boolean = false;
  @StorageProp('darkMode') darkMode: boolean = false;
  @StorageProp('deepread.appearance.readerLayout') readerLayout: string = 'classic';
  @StorageProp('deepread.appearance.readerStyle') readerStyle: string = 'classic';
  @Prop customTemplateHtml: string = '';
  onClose: () => void = (): void => {};
  isActive: () => boolean = (): boolean => true;
  @State loading: boolean = true;
  @State ready: boolean = false;
  @State complete: boolean = false;
  @State articleTitle: string = '';
  @State busy: boolean = false;
  @State errorMessage: string = '';
  @State note: string = '';
  @State pdfPreview: boolean = false;
  @State pdfReady: boolean = false;
  private alive: boolean = false;
  private lifecycle: number = 0;
  private snapshot: DeepReadCacheEntry | null = null;
  private fileStem: string = '';
  private pdfHtml: string = '';
  private pdfLoadStarted: boolean = false;
  private pdfAttached: boolean = false;
  private pdfWebInitialized: boolean = false;
  private pdfPreviewId: number = 0;
  private pdfDocumentUrl: string = '';
  private controller: webview.WebviewController = new webview.WebviewController();

  aboutToAppear(): void {
    this.alive = true;
    this.lifecycle++;
    this.loading = true;
    this.ready = false;
    this.snapshot = null;
    this.busy = false;
    this.errorMessage = '';
    this.note = '';
    this.pdfPreview = false;
    this.pdfReady = false;
    this.articleTitle = this.title;
    this.loadSnapshot();
  }

  aboutToDisappear(): void {
    this.alive = false;
    this.lifecycle++;
    this.pdfLoadStarted = false;
    this.pdfAttached = false;
    this.pdfWebInitialized = false;
  }

  private currentActive(token: number = this.lifecycle): boolean { return this.alive && token === this.lifecycle && this.isActive(); }

  private async loadSnapshot(): Promise<void> {
    const token: number = this.lifecycle;
    try {
      const entry: DeepReadCacheEntry | null = await getRepository().get(this.topicId);
      if (!this.currentActive(token)) return;
      if (entry === null) throw new Error('没有找到已保存的文章,请返回阅读页确认保存结果。');
      if (!hasDisplayableDeepReadOutput(entry.output)) throw new Error('文章尚无已保存正文。');
      this.complete = isComplete(entry.output);
      this.snapshot = entry;
      this.articleTitle = entry.title;
      this.fileStem = `deepread-${sha256HexUtf8(entry.topicId)}`;
      this.ready = true;
    } catch (error) {
      if (this.currentActive(token)) this.errorMessage = error instanceof Error ? error.message : String(error);
    } finally {
      if (this.currentActive(token)) this.loading = false;
    }
  }

  private context(): common.UIAbilityContext {
    const context: common.UIAbilityContext | undefined = AppStorage.get<common.UIAbilityContext>('abilityContext');
    if (context === undefined) throw new Error('页面上下文尚未就绪。');
    return context;
  }

  private async perform(action: string): Promise<void> {
    const token: number = this.lifecycle;
    if (!this.currentActive(token) || !this.ready || this.snapshot === null || this.busy) return;
    this.busy = true;
    this.errorMessage = '';
    this.note = '';
    try {
      const context: common.UIAbilityContext = this.context();
      const active: () => boolean = (): boolean => this.currentActive(token);
      if (action === 'text') {
        await shareDeepReadText(context, this.snapshot.title, deepReadToText(this.snapshot, deepReadMarkdownToText), active);
        if (this.currentActive(token)) this.note = '已打开文本分享面板';
      } else if (action === 'text-save') {
        const saved: boolean = await saveDeepReadTextFile(context,
          `${this.fileStem}${this.complete ? '' : '-partial'}.txt`, deepReadToText(this.snapshot, deepReadMarkdownToText), active);
        if (this.currentActive(token) && saved) this.note = 'TXT 文件已保存';
      } else if (action === 'text-share') {
        await shareDeepReadTextFile(context,
          `${this.fileStem}${this.complete ? '' : '-partial'}.txt`, deepReadToText(this.snapshot, deepReadMarkdownToText), active);
        if (this.currentActive(token)) this.note = '已打开 TXT 文件分享面板';
      } else if (action === 'markdown-save') {
        const saved: boolean = await saveDeepReadMarkdown(context, `${this.fileStem}.md`, deepReadToMarkdown(this.snapshot), active);
        if (this.currentActive(token) && saved) this.note = 'Markdown 文件已保存';
      } else if (action === 'markdown-share') {
        await shareDeepReadMarkdown(context, `${this.fileStem}.md`, deepReadToMarkdown(this.snapshot), active);
        if (this.currentActive(token)) this.note = '已打开 Markdown 分享面板';
      }
    } catch (error) {
      if (this.currentActive(token)) this.errorMessage = `导出失败:${error instanceof Error ? error.message : String(error)}`;
    } finally {
      if (this.currentActive(token)) this.busy = false;
    }
  }

  private openPdfPreview(): void {
    if (!this.currentActive() || !this.ready || !this.complete || this.snapshot === null || this.busy) return;
    if (!isDeepReadPdfSupported()) { this.errorMessage = '当前系统不支持 PDF 导出,需要 API 14 或更高版本。'; return; }
    this.errorMessage = '';
    this.note = '';
    this.pdfReady = false;
    this.pdfLoadStarted = false;
    this.pdfAttached = false;
    this.pdfWebInitialized = false;
    this.pdfDocumentUrl = `${PDF_DOCUMENT_URL}?preview=${++this.pdfPreviewId}`;
    const options: DeepReadRenderOptions = {
      fontScale: this.fontScale, fontSerif: this.fontSerif,
      readerLayout: getProductKind() === 'deepread' ? this.readerLayout : 'classic',
      readerStyle: getProductKind() === 'deepread' ? this.readerStyle : 'classic', dark: this.darkMode,
      background: getProductKind() === 'deepread' ? D.paper : BG,
      foreground: getProductKind() === 'deepread' ? D.ink : INK,
      muted: getProductKind() === 'deepread' ? D.muted : INK3,
      surface: getProductKind() === 'deepread' ? D.card : SURFACE,
      border: getProductKind() === 'deepread' ? D.rule : LINE,
      accent: getProductKind() === 'deepread' ? D.accent : this.accentColorStore,
    };
    try {
      const html: string = renderCapturedDeepReadTemplateHtml(this.snapshot.title, this.snapshot.output, options);
      this.pdfHtml = html.replace('</head>', PRINT_CSS
        + (getProductKind() === 'deepread' ? '<style>img,figure{display:none!important;}</style>' : '') + '</head>');
      this.note = resolveDeepReadArticleTemplate(this.snapshot.output).message;
    } catch (error) {
      this.errorMessage = `PDF 版式加载失败:${error instanceof Error ? error.message : String(error)}`;
      return;
    }
    this.pdfPreview = true;
  }

  private loadPdfHtml(): void {
    if (!this.currentActive() || !this.pdfPreview || !this.pdfAttached || !this.pdfWebInitialized || this.pdfHtml.length === 0) return;
    this.pdfReady = false;
    this.pdfLoadStarted = true;
    try {
      this.controller.loadData(this.pdfHtml,
        'text/html', 'UTF-8', this.pdfDocumentUrl, this.pdfDocumentUrl);
    } catch (error) {
      this.pdfLoadStarted = false;
      this.errorMessage = `PDF 预览加载失败:${error instanceof Error ? error.message : String(error)}`;
    }
  }

  private onPdfAttached(previewId: number, epoch: number): void {
    if (!this.currentActive() || !this.pdfPreview || previewId !== this.pdfPreviewId || epoch !== this.themeEpoch) return;
    this.pdfAttached = true;
    this.pdfWebInitialized = false;
    this.pdfLoadStarted = false;
    this.pdfReady = false;
  }

  private onPdfPageEnd(url: string, previewId: number = this.pdfPreviewId, epoch: number = this.themeEpoch): void {
    if (!this.currentActive() || !this.pdfPreview || !this.pdfAttached ||
      previewId !== this.pdfPreviewId || epoch !== this.themeEpoch) return;
    if (!this.pdfWebInitialized && url === 'about:blank') {
      this.pdfWebInitialized = true;
      this.loadPdfHtml();
      return;
    }
    if (this.pdfLoadStarted &&
      (url === this.pdfDocumentUrl || url.startsWith(this.pdfDocumentUrl + '#'))) this.pdfReady = true;
    if (!this.pdfLoadStarted || !url.startsWith('data:')) return;
    try {
      // ArkWeb ends the data document but reports its history URL through getUrl.
      if (this.controller.getUrl() === this.pdfDocumentUrl) this.pdfReady = true;
    } catch (_) {
      this.onPdfError(this.pdfDocumentUrl, true, '无法确认预览文档，请返回菜单后重试。', previewId, epoch);
    }
  }

  private onPdfError(url: string, mainFrame: boolean, message: string,
    previewId: number = this.pdfPreviewId, epoch: number = this.themeEpoch): void {
    if (!this.currentActive() || !this.pdfPreview || !this.pdfAttached || !mainFrame ||
      previewId !== this.pdfPreviewId || epoch !== this.themeEpoch) return;
    if (!(this.pdfLoadStarted && url === this.pdfDocumentUrl) &&
      !(url === 'about:blank' && !this.pdfWebInitialized)) return;
    this.pdfAttached = false;
    this.pdfLoadStarted = false;
    this.pdfWebInitialized = false;
    this.pdfReady = false;
    this.errorMessage = `PDF 预览加载失败:${message.length > 0 ? message : '正文加载错误。请返回菜单后重试。'}`;
  }

  private closePdfPreview(): void {
    if (!this.currentActive()) return;
    this.pdfLoadStarted = false;
    this.pdfAttached = false;
    this.pdfWebInitialized = false;
    this.pdfReady = false;
    this.pdfPreview = false;
    this.errorMessage = '';
    this.note = '';
  }

  private handleNavigation(request: WebResourceRequest): boolean {
    if (!this.currentActive()) return true;
    const url: string = request.getRequestUrl();
    if (url === 'about:blank' || url.startsWith('data:')) return false;
    if (url === this.pdfDocumentUrl && !request.isRequestGesture()) return false;
    if (url.startsWith(this.pdfDocumentUrl + '#')) return false;
    return true;
  }

  private async exportPdf(share: boolean): Promise<void> {
    const token: number = this.lifecycle;
    if (!this.currentActive(token) || !this.pdfPreview || !this.pdfReady || this.busy) return;
    if (!isDeepReadPdfSupported()) { this.errorMessage = '当前系统不支持 PDF 导出,需要 API 14 或更高版本。'; return; }
    this.busy = true;
    this.errorMessage = '';
    this.note = '';
    try {
      const configuration: webview.PdfConfiguration = {
        width: 8.27, height: 11.69, marginTop: 0.5, marginBottom: 0.5,
        marginLeft: 0.5, marginRight: 0.5, scale: 1, shouldPrintBackground: true,
      };
      const pdf: webview.PdfData = await this.controller.createPdf(configuration);
      if (!this.currentActive(token) || !this.pdfPreview) return;
      const bytes: Uint8Array = pdf.pdfArrayBuffer();
      if (bytes.byteLength === 0) throw new Error('没有生成有效的 PDF 文件。');
      const context: common.UIAbilityContext = this.context();
      const active: () => boolean = (): boolean => this.currentActive(token) && this.pdfPreview;
      if (share) {
        await shareDeepReadPdf(context, `${this.fileStem}.pdf`, bytes, active);
        if (active()) this.note = '已打开 PDF 分享面板';
      } else {
        const saved: boolean = await saveDeepReadPdf(context, `${this.fileStem}.pdf`, bytes, active);
        if (active() && saved) this.note = 'PDF 文件已保存';
      }
    } catch (error) {
      if (this.currentActive(token) && this.pdfPreview) this.errorMessage = `PDF 导出失败:${error instanceof Error ? error.message : String(error)}`;
    } finally {
      if (this.currentActive(token)) this.busy = false;
    }
  }

  private contentTransition(): TransitionEffect {
    if (getProductKind() !== 'deepread' || this.appBackgrounded) return TransitionEffect.IDENTITY;
    return TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: this.reduceMotion ? 0 : 12 }))
      .animation({ duration: this.reduceMotion ? 120 : 180, curve: Curve.EaseOut });
  }

  @Builder
  Action(label: string, action: string): void {
    Button(label).fontSize(15).fontColor(getProductKind() === 'deepread' ? D.ink : INK).backgroundColor(getProductKind() === 'deepread' ? D.card : SURFACE)
      .width('100%').height(48).borderRadius(12)
      .enabled(this.ready && !this.busy)
      .onClick((): void => { this.perform(action); })
  }

  @Builder
  HostMenu(): void {
    Scroll() {
      Column({ space: 12 }) {
        Text(this.complete ? '导出内容取自打开面板时已保存的文章。' : '当前为部分稿，文本和 Markdown 包含已经保存的章节；补全文章后可导出 PDF。')
          .fontSize(13).fontColor(getProductKind() === 'deepread' ? D.muted : INK3).width('100%')
        this.Action('保存 TXT 文件', 'text-save')
        this.Action('分享 TXT 文件', 'text-share')
        this.Action('保存 Markdown 文件', 'markdown-save')
        this.Action('分享 Markdown 文件', 'markdown-share')
        Button('PDF 预览与导出').fontSize(15).fontColor(getProductKind() === 'deepread' ? D.ink : INK).backgroundColor(getProductKind() === 'deepread' ? D.card : SURFACE)
          .width('100%').height(48).borderRadius(12)
          .enabled(this.ready && this.complete && !this.busy && isDeepReadPdfSupported())
          .onClick((): void => { this.openPdfPreview(); })
        if (!isDeepReadPdfSupported()) {
          Text('当前系统不支持 PDF 导出(API 14+),可使用文本或 Markdown。')
            .fontSize(13).fontColor(getProductKind() === 'deepread' ? D.muted : INK3).width('100%')
        }
      }
      .width('100%').alignItems(HorizontalAlign.Start).justifyContent(FlexAlign.Start)
      .padding({ left: GUTTER, right: GUTTER, top: 12, bottom: 24 })
    }.align(Alignment.TopStart).width('100%').layoutWeight(1)
  }

  @Builder
  ExportRow(label: string, icon: string, action: string, separator: boolean = true): void {
    Row({ space: 12 }) {
      Icon({ name: icon, iconSize: 19, color: D.accent })
      Text(label).fontSize(16).fontColor(D.ink).layoutWeight(1)
      Icon({ name: 'chevron_right', iconSize: 13, color: D.muted })
    }.width('100%').constraintSize({ minHeight: 50 }).padding({ left: 16, right: 16, top: 10, bottom: 10 })
      .border({ width: { bottom: separator ? 0.5 : 0 }, color: D.rule })
      .enabled(this.ready && !this.busy && (action !== 'pdf' || (this.complete && isDeepReadPdfSupported())))
      .opacity(action === 'pdf' && (!this.complete || !isDeepReadPdfSupported()) ? 0.4 : 1)
      .onClick((): void => { if (action === 'pdf') this.openPdfPreview(); else this.perform(action); })
  }

  @Builder
  Menu(): void {
    if (getProductKind() !== 'deepread') {
      this.HostMenu()
    } else {
      Scroll() {
        Column({ space: 18 }) {
          Column() {
            this.ExportRow('文本', 'doc', 'text-share')
            this.ExportRow('Markdown', 'edit', 'markdown-share')
            this.ExportRow('PDF', 'doc', 'pdf', false)
          }.width('100%').backgroundColor(D.card).borderRadius(18).border({ width: 0.6, color: D.rule })
          Text('保存到文件').fontSize(13).fontColor(D.muted).width('100%')
          Column() {
            this.ExportRow('保存 TXT 文件', 'save', 'text-save')
            this.ExportRow('保存 Markdown 文件', 'save', 'markdown-save', false)
          }.width('100%').backgroundColor(D.card).borderRadius(18).border({ width: 0.6, color: D.rule })
          Text(this.complete ? '导出已保存的文章；PDF 可预览后保存或分享。'
            : '部分稿可导出文本与 Markdown。补全章节后可导出 PDF。')
            .fontSize(13).fontColor(D.muted).lineHeight(20).width('100%')
          if (!isDeepReadPdfSupported()) {
            Text('当前系统不支持 PDF 导出，可使用文本或 Markdown。').fontSize(13).fontColor(D.muted).lineHeight(20).width('100%')
          }
        }.width('100%').constraintSize({ maxWidth: 760 }).alignItems(HorizontalAlign.Start)
          .padding({ left: 18, right: 18, top: 18, bottom: 28 })
      }.align(Alignment.TopStart).width('100%').layoutWeight(1).transition(this.contentTransition())
    }
  }

  @Builder
  PdfPreview(previewId: number, epoch: number): void {
    Column() {
      Row() {
        Text('A4 导出预览').fontSize(14).fontColor(getProductKind() === 'deepread' ? D.ink : INK).layoutWeight(1)
        Button('返回菜单').fontSize(13).fontColor(getProductKind() === 'deepread' ? D.ink : INK)
          .backgroundColor(Color.Transparent).constraintSize({ minWidth: 80, minHeight: 44 })
          .enabled(!this.busy).onClick((): void => { this.closePdfPreview(); })
      }.width('100%').padding({ left: GUTTER, right: GUTTER })
      Web({ src: 'about:blank', controller: this.controller })
        .width('100%').layoutWeight(1).backgroundColor(getProductKind() === 'deepread' ? D.paper : BG)
        .javaScriptAccess(false).fileAccess(false).domStorageAccess(false).multiWindowAccess(false)
        .onControllerAttached((): void => {
          this.onPdfAttached(previewId, epoch);
        })
        .onPageEnd((event: OnPageEndEvent): void => { this.onPdfPageEnd(event.url, previewId, epoch); })
        .onErrorReceive((event: OnErrorReceiveEvent): void => {
          this.onPdfError(event.request.getRequestUrl(), event.request.isMainFrame(), event.error.getErrorInfo(), previewId, epoch);
        })
        .onOverrideUrlLoading((request: WebResourceRequest): boolean => this.handleNavigation(request))
      if (!this.pdfReady && this.errorMessage.length === 0) {
        Text('正在加载完整文章预览…').fontSize(12).fontColor(getProductKind() === 'deepread' ? D.muted : INK3).padding(8)
      }
      Row({ space: 12 }) {
        Button('保存 PDF').fontSize(15).fontColor(getProductKind() === 'deepread' ? D.ink : INK).backgroundColor(getProductKind() === 'deepread' ? D.card : SURFACE)
          .height(48).layoutWeight(1).enabled(this.pdfReady && !this.busy)
          .onClick((): void => { this.exportPdf(false); })
        Button('分享 PDF').fontSize(15).fontColor(getProductKind() === 'deepread' ? D.ink : INK).backgroundColor(getProductKind() === 'deepread' ? D.card : SURFACE)
          .height(48).layoutWeight(1).enabled(this.pdfReady && !this.busy)
          .onClick((): void => { this.exportPdf(true); })
      }.width('100%').padding({ left: GUTTER, right: GUTTER, top: 12, bottom: 12 })
    }.width('100%').layoutWeight(1).transition(this.contentTransition())
  }

  build(): void {
    Column() {
      ForEach([this.themeEpoch], (_epoch: number): void => {
        Row({ space: 12 }) {
          Column({ space: 4 }) {
            Text(getProductKind() === 'deepread' ? '分享文章' : '导出深度阅读').fontSize(20).fontWeight(FontWeight.Bold).fontColor(getProductKind() === 'deepread' ? D.ink : INK)
            Text(this.articleTitle).fontSize(13).fontColor(getProductKind() === 'deepread' ? D.muted : INK3).maxLines(3)
              .textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%')
          }.layoutWeight(1).alignItems(HorizontalAlign.Start)
          Button('关闭').fontSize(14).fontColor(getProductKind() === 'deepread' ? D.ink : INK)
            .backgroundColor(Color.Transparent).constraintSize({ minWidth: 64, minHeight: 44 })
            .onClick((): void => { this.alive = false; this.onClose(); })
        }.width('100%').padding({ left: GUTTER, right: GUTTER, top: this.statusBarHeight + 8, bottom: 12 })
        Row().width('100%').height(0.5).backgroundColor(getProductKind() === 'deepread' ? D.rule : LINE)
        if (this.loading) {
          Text('正在读取已保存文章…').fontSize(14).fontColor(getProductKind() === 'deepread' ? D.muted : INK3).padding(24)
          Blank().layoutWeight(1)
        } else if (this.pdfPreview) {
          this.PdfPreview(this.pdfPreviewId, _epoch)
        } else {
          this.Menu()
        }
        if (this.busy) {
          Text('正在准备导出…').fontSize(13).fontColor(getProductKind() === 'deepread' ? D.muted : INK3).padding(8)
        }
        if (this.errorMessage.length > 0) {
          Text(this.errorMessage).fontSize(13).fontColor(getProductKind() === 'deepread' ? D.danger : ERROR).width('100%')
            .padding({ left: GUTTER, right: GUTTER, top: 8, bottom: 12 })
        }
        if (this.note.length > 0) {
          Text(this.note).fontSize(13).fontColor(getProductKind() === 'deepread' ? D.muted : INK3).width('100%')
            .padding({ left: GUTTER, right: GUTTER, top: 8, bottom: 12 })
        }
      }, (epoch: number): string => `deepread-export-${epoch}`)
    }.width('100%').height('100%').backgroundColor(getProductKind() === 'deepread' ? D.paper : BG).padding({ bottom: this.navBarHeight })
  }
}
