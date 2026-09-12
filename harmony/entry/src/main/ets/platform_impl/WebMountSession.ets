// WebMountSession — WebMount 隐藏浏览器会话(MVP,单会话)
//
// Android 基准: feature/webmount(WebMountManager 多 tab + OAuth + 站点注册,
//   30+ 工具);鸿蒙 MVP = 单会话 9 工具(wm_open/state/extract/click/type/
//   scroll/screenshot/eval),会话经 WebMountHost(移出可视区的真实尺寸 Web
//   组件,保证渲染与组件截图可用)承载于 ChatPage 根 Stack。
// 状态:pageReady 由 Web.onPageEnd 驱动;open 等待就绪(15s 超时)。

import { webview } from '@kit.ArkWeb';
import type { UIContext } from '@kit.ArkUI';
import { image } from '@kit.ImageKit';
import { util } from '@kit.ArkTS';
import { hilog } from '@kit.PerformanceAnalysisKit';

const DOMAIN: number = 0x0001;
const TAG: string = 'Chat.WebMount';
const OPEN_TIMEOUT_MS: number = 15000;
const WEB_HOST_ID: string = 'webmount-web';

// 宿主需要的最小状态同步(session → ChatPage @State,经回调)
export interface WebMountHostHooks {
  onMountNeeded: () => void;   // 首次 open 时要求宿主挂载 Web 组件
}

// 逻辑 tab(单 Web 组件:active tab 的 url 由 WebView 呈现;其余 tab 仅记状态)
export interface WebMountTab {
  id: string;
  title: string;
  url: string;
}

export class WebMountSession {
  private controller: webview.WebviewController = new webview.WebviewController();
  private currentUrl: string = '';
  private pageReady: boolean = false;
  private hostMounted: boolean = false;
  private pageEndWaiters: Array<() => void> = [];
  private hooks: WebMountHostHooks | null = null;
  private uiContext: UIContext | null = null;
  private tabs: WebMountTab[] = [{ id: 'main', title: '', url: '' }];
  private activeTabId: string = 'main';

  public bindHooks(hooks: WebMountHostHooks): void {
    this.hooks = hooks;
  }

  // 截图依赖 UI 上下文(API 12:uiContext.getComponentSnapshot());宿主挂载时注入
  public attachUiContext(ui: UIContext): void {
    this.uiContext = ui;
  }

  public getController(): webview.WebviewController {
    return this.controller;
  }

  public getUrl(): string {
    return this.currentUrl;
  }

  public getActiveTabId(): string {
    return this.activeTabId;
  }

  public listTabs(): WebMountTab[] {
    const out: WebMountTab[] = [];
    for (const t of this.tabs) {
      out.push({ id: t.id, title: t.title, url: t.url });
    }
    return out;
  }

  public openTab(tabId: string): WebMountTab {
    const existing: WebMountTab | undefined = this.tabs.find(
      (t: WebMountTab): boolean => t.id === tabId);
    if (existing !== undefined) return existing;
    const created: WebMountTab = { id: tabId, title: '', url: '' };
    this.tabs.push(created);
    return created;
  }

  public selectTab(tabId: string): WebMountTab {
    const hit: WebMountTab | undefined = this.tabs.find(
      (t: WebMountTab): boolean => t.id === tabId);
    if (hit === undefined) throw new Error(`tab not found: ${tabId}`);
    this.activeTabId = tabId;
    return hit;
  }

  public closeTab(tabId: string): boolean {
    if (tabId === 'main') throw new Error('cannot close main tab');
    const idx: number = this.tabs.findIndex((t: WebMountTab): boolean => t.id === tabId);
    if (idx < 0) return false;
    this.tabs.splice(idx, 1);
    if (this.activeTabId === tabId) {
      this.activeTabId = this.tabs.length > 0 ? this.tabs[0].id : 'main';
    }
    return true;
  }

  private touchActiveTab(url: string, title: string = ''): void {
    for (const t of this.tabs) {
      if (t.id === this.activeTabId) {
        t.url = url;
        if (title.length > 0) t.title = title;
      }
    }
  }

  public markHostMounted(): void {
    this.hostMounted = true;
  }

  // Web.onPageEnd 回调:解锁所有等待者
  public markPageEnd(url: string): void {
    this.pageReady = true;
    this.currentUrl = url;
    this.touchActiveTab(url);
    const waiters: Array<() => void> = this.pageEndWaiters;
    this.pageEndWaiters = [];
    for (const w of waiters) w();
  }

  private waitForPageEnd(): Promise<void> {
    return new Promise<void>((resolve: () => void): void => {
      const timer: number = setTimeout((): void => {
        const i: number = this.pageEndWaiters.indexOf(resolve);
        if (i >= 0) this.pageEndWaiters.splice(i, 1);
        resolve();
      }, OPEN_TIMEOUT_MS);
      const wrapped: () => void = (): void => {
        clearTimeout(timer);
        resolve();
      };
      this.pageEndWaiters.push(wrapped);
    });
  }

  // 打开 URL:确保宿主挂载 → loadUrl → 等 onPageEnd
  public async open(url: string): Promise<void> {
    if (!this.hostMounted && this.hooks !== null) {
      this.hooks.onMountNeeded();
      // 挂载由下一帧 build 完成;轮询等待(宿主挂载是 UI 帧级)
      for (let i = 0; i < 50 && !this.hostMounted; i++) {
        await new Promise<void>((r: () => void): void => { setTimeout(r, 100); });
      }
      if (!this.hostMounted) throw new Error('WebMount host not mounted');
    }
    this.pageReady = false;
    this.currentUrl = url;
    this.touchActiveTab(url);
    this.controller.loadUrl(url);
    await this.waitForPageEnd();
    if (!this.pageReady) throw new Error(`page load timeout: ${url}`);
  }

  public back(): boolean {
    if (!this.controller.accessBackward()) return false;
    this.controller.backward();
    this.pageReady = true;
    return true;
  }

  public forward(): boolean {
    if (!this.controller.accessForward()) return false;
    this.controller.forward();
    this.pageReady = true;
    return true;
  }

  public async reload(): Promise<void> {
    this.pageReady = false;
    this.controller.refresh();
    await this.waitForPageEnd();
  }

  public isReady(): boolean {
    return this.pageReady;
  }

  // 在会话页面执行同步表达式,结果 JSON 字符串;异常转 {wm_error}
  public async evalJson(expr: string): Promise<string> {
    const script: string = `(function(){ try { const v = ${expr};`
      + ` return JSON.stringify({ ok: true, value: v === undefined ? null : v }); }`
      + ` catch (e) { return JSON.stringify({ ok: false, error: String(e) }); } })()`;
    const raw: string = await this.controller.runJavaScript(script);
    return raw;
  }

  // 截图 → base64 png data url(经组件截图,宿主需真实尺寸)
  public async screenshotDataUrl(): Promise<string> {
    if (this.uiContext === null) throw new Error('WebMount host not mounted (no UI context)');
    const pixelMap: image.PixelMap =
      await this.uiContext.getComponentSnapshot().get(WEB_HOST_ID);
    const packer: image.ImagePacker = image.createImagePacker();
    try {
      const opt: image.PackingOption = { format: 'image/png', quality: 100 };
      const buf: ArrayBuffer = await packer.packing(pixelMap, opt);
      const b64: string = new util.Base64Helper().encodeToStringSync(new Uint8Array(buf));
      return `data:image/png;base64,${b64}`;
    } finally {
      packer.release();
      pixelMap.release();
    }
  }
}

// 全局单会话(MVP;多 tab 会话为后续梯队)
let globalSession: WebMountSession | null = null;

export const getWebMountSession = (): WebMountSession => {
  if (globalSession === null) {
    globalSession = new WebMountSession();
    hilog.info(DOMAIN, TAG, 'session created');
  }
  return globalSession;
};

// WebMountHost — 会话宿主组件:真实尺寸渲染但 position 移出可视区
//   (Visibility.None/尺寸 0 会导致 Web 不渲染、截图黑,移出屏保持渲染树真实)
@Component
export struct WebMountHost {
  @Prop visible: boolean = false;
  private session: WebMountSession = getWebMountSession();

  aboutToAppear(): void {
    this.session.attachUiContext(this.getUIContext());
  }

  build(): void {
    if (this.visible) {
      Web({ src: 'about:blank', controller: this.session.getController() })
        .id(WEB_HOST_ID)
        .width(360).height(720)
        .position({ x: -2000, y: 0 })
        .onPageEnd((): void => {
          this.session.markPageEnd(this.session.getUrl());
        })
    }
  }
}
