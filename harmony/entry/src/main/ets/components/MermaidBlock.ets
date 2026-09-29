// MermaidBlock — Mermaid 图渲染(D7-3;本地 mermaid.min.js,ArkWeb 沙箱)
//
// host 页(rawfile/mermaid_host.html)+ 本地库,无网络依赖;strict 安全级。
// source 变化 400ms 去抖重渲(流式友好);blockId 供 componentSnapshot 导出。
import { webview } from '@kit.ArkWeb';
import { display, promptAction } from '@kit.ArkUI';
import { image } from '@kit.ImageKit';
import { imageExportPages } from '@amber/chat-domain';
import type { ImageExportPage } from '@amber/chat-domain';
import { INK3, SURFACE2 } from '../design/tokens.ets';
import { packPixelMapToCache, saveImageFilesToAlbum, removeCachedImageFiles } from '../platform_impl/ImageSaveSupport.ets';

interface MermaidRenderInfo {
  revision: number;
  height: number;
  ok: boolean;
}

@Component
export struct MermaidBlock {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop @Watch('onSourceChange') source: string = '';
  @StorageProp('darkMode') @Watch('onSourceChange') darkMode: boolean = false;
  @Prop blockId: string = '';
  @State renderedHeight: number = 300;
  @State renderReady: boolean = false;
  @State exporting: boolean = false;
  @State captureHeight: number = 300;
  @State displayHeight: number = 600;
  @State displayWidth: number = 360;
  @State exportProgress: string = '';
  private controller: webview.WebviewController = new webview.WebviewController();
  private attached: boolean = false;
  private alive: boolean = false;
  private renderRevision: number = 0;
  private screenHeightPx: number = 0;
  private renderTimer: number = -1;
  private frameTimer: number = -1;
  private finishFrame: (() => void) | null = null;

  aboutToAppear(): void {
    this.alive = true;
    const screen: display.Display = display.getDefaultDisplaySync();
    this.screenHeightPx = screen.height;
    this.displayHeight = px2vp(screen.height);
    this.displayWidth = px2vp(screen.width);
  }

  aboutToDisappear(): void {
    this.attached = false;
    this.alive = false;
    this.renderReady = false;
    if (this.renderTimer !== -1) {
      clearTimeout(this.renderTimer);
      this.renderTimer = -1;
    }
    if (this.frameTimer !== -1) clearTimeout(this.frameTimer);
    this.frameTimer = -1;
    if (this.finishFrame !== null) this.finishFrame();
    this.finishFrame = null;
  }

  private onSourceChange(): void {
    this.renderRevision++;
    this.renderReady = false;
    this.renderedHeight = 300;
    if (this.renderTimer !== -1) clearTimeout(this.renderTimer);
    this.renderTimer = setTimeout((): void => {
      this.renderTimer = -1;
      this.renderNow();
    }, 400);
  }

  private renderNow(): void {
    if (!this.attached || this.source.trim().length === 0) return;
    const src: string = JSON.stringify(this.source)
      .replace(/\u2028/g, '\\u2028')
      .replace(/\u2029/g, '\\u2029');
    this.controller.runJavaScript(
      `window.__amberRenderMermaid && window.__amberRenderMermaid(${src}, ${this.renderRevision}, ${JSON.stringify(this.darkMode ? 'dark' : 'default')})`)
      .catch((): void => {});
  }

  private onHostMessage(raw: string): void {
    if (!this.alive) return;
    const info: MermaidRenderInfo = JSON.parse(raw) as MermaidRenderInfo;
    if (info.revision !== this.renderRevision || !Number.isFinite(info.height) || info.height <= 0) return;
    this.renderedHeight = info.height;
    this.renderReady = info.ok;
  }

  private waitRenderFrame(): Promise<void> {
    return new Promise<void>((resolve: () => void): void => {
      this.finishFrame = resolve;
      this.frameTimer = setTimeout((): void => {
        this.frameTimer = -1;
        this.finishFrame = null;
        resolve();
      }, 32);
    });
  }

  private async exportImage(): Promise<void> {
    if (!this.renderReady || this.exporting || !this.attached) return;
    const revision: number = this.renderRevision;
    const files: string[] = [];
    this.exporting = true;
    try {
      const pages: ImageExportPage[] = imageExportPages(vp2px(this.renderedHeight), this.screenHeightPx);
      for (let index: number = 0; index < pages.length; index++) {
        if (!this.alive || revision !== this.renderRevision) return;
        const page: ImageExportPage = pages[index];
        this.exportProgress = pages.length === 1 ? '正在生成图片…' : `正在生成 ${index + 1}/${pages.length} 张…`;
        this.captureHeight = px2vp(page.height);
        await this.waitRenderFrame();
        if (!this.alive || revision !== this.renderRevision) return;
        const actualOffset: string = await this.controller.runJavaScript(
          `window.__amberSetExportOffset(${px2vp(page.top)})`);
        if (Math.abs(vp2px(Number(actualOffset)) - page.top) > 1) throw new Error('图表滚动尚未就绪');
        await this.waitRenderFrame();
        if (!this.alive || revision !== this.renderRevision) return;
        const pm: image.PixelMap = await this.getUIContext().getComponentSnapshot()
          .get(this.blockId, { scale: 1, waitUntilRenderFinished: true });
        try {
          files.push(await packPixelMapToCache(pm));
        } finally {
          await pm.release();
        }
      }
      if (!this.alive || revision !== this.renderRevision) return;
      this.exportProgress = files.length === 1 ? '请确认保存图片' : `请确认保存 ${files.length} 张图片`;
      await saveImageFilesToAlbum(files);
    } catch (error) {
      if (this.alive) {
        const message: string = error instanceof Error ? error.message : String(error);
        promptAction.showToast({ message: `导出失败:${message}`, duration: 2000 });
      }
    } finally {
      removeCachedImageFiles(files);
      if (this.alive) {
        this.exporting = false;
        if (this.attached) this.controller.runJavaScript('window.__amberEndExport()').catch((): void => {});
      }
    }
  }

  build(): void {
    Column() {
      Web({ src: $rawfile('mermaid_host.html'), controller: this.controller })
        .id(this.blockId)
        .width('100%')
        .height(this.exporting ? this.captureHeight : Math.min(this.renderedHeight, this.displayHeight))
        .constraintSize({ maxWidth: this.displayWidth })
        .backgroundColor(SURFACE2)
        .borderRadius(10)
        .javaScriptAccess(true)
        .domStorageAccess(true)
        .verticalScrollBarAccess(false)
        .horizontalScrollBarAccess(false)
        .javaScriptProxy({
          object: {
            postMessage: (raw: string): void => { this.onHostMessage(raw); },
          },
          name: 'AmberMermaid',
          methodList: ['postMessage'],
          controller: this.controller,
        })
        .onPageEnd((): void => {
          this.attached = true;
          this.renderNow();
        })
      Row() {
        Text(this.exporting ? this.exportProgress : this.renderedHeight > this.displayHeight
          ? `图内可滚动 · 导出分为 ${imageExportPages(vp2px(this.renderedHeight), this.screenHeightPx).length} 张`
          : '')
          .fontSize(11).fontColor(INK3).layoutWeight(1)
        Text(this.exporting ? '生成中…' : '导出图片')
          .fontSize(12).fontColor(INK3)
          .height(44).padding({ left: 12, right: 12 })
          .enabled(this.renderReady && !this.exporting)
          .opacity(this.renderReady && !this.exporting ? 1 : 0.5)
          .onClick((): void => { this.exportImage(); })
      }
      .width('100%')
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .backgroundColor(SURFACE2)
    .borderRadius(10)
  }
}
