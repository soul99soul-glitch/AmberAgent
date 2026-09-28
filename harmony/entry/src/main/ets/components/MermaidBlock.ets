// MermaidBlock — Mermaid 图渲染(D7-3;本地 mermaid.min.js,ArkWeb 沙箱)
//
// host 页(rawfile/mermaid_host.html)+ 本地库,无网络依赖;strict 安全级。
// source 变化 400ms 去抖重渲(流式友好);blockId 供 componentSnapshot 导出。
import { webview } from '@kit.ArkWeb';
import { ERROR, SURFACE2 } from '../design/tokens.ets';

@Component
export struct MermaidBlock {
  @Prop @Watch('onSourceChange') source: string = '';
  @Prop blockId: string = '';
  @State renderTick: number = 0;
  private controller: webview.WebviewController = new webview.WebviewController();
  private attached: boolean = false;
  private renderTimer: number = -1;

  aboutToDisappear(): void {
    if (this.renderTimer !== -1) {
      clearTimeout(this.renderTimer);
      this.renderTimer = -1;
    }
  }

  private onSourceChange(): void {
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
      `window.__amberRenderMermaid && window.__amberRenderMermaid(${src})`)
      .catch((): void => {});
  }

  build(): void {
    Web({ src: $rawfile('mermaid_host.html'), controller: this.controller })
      .id(this.blockId)
      .width('100%')
      .height(300)
      .backgroundColor(SURFACE2)
      .borderRadius(10)
      .javaScriptAccess(true)
      .domStorageAccess(true)
      .onControllerAttached((): void => {
        this.attached = true;
        this.renderNow();
      })
  }
}
