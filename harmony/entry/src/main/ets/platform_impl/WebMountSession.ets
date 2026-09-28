// WebMountSession — WebMount 隐藏浏览器会话(MVP,单会话)
//
// Android 基准: feature/webmount(WebMountManager 多 tab + OAuth + 站点注册,
//   30+ 工具);鸿蒙 MVP = 单会话 9 工具(wm_open/state/extract/click/type/
//   scroll/screenshot/eval),会话经 WebMountHost(移出可视区的真实尺寸 Web
//   组件,保证渲染与组件截图可用)承载于 ChatPage 根 Stack。
// 状态:hostMounted 由 Web.onControllerAttached 真实置位(R07 修复);pageReady 由
//   Web.onPageEnd 驱动;open 等待本次导航就绪(15s 超时正确 reject)。
//
// R07 修复要点:
//   - 就绪信号来自 controller 真正 attached(onControllerAttached),而不是外层
//     组件 aboutToAppear/初始 pageEnd;
//   - 卸载(onDetach)对称复位 hostMounted/pageReady/UI context 并终止等待者;
//   - 等待者超时/取消时正确 reject 并清理(旧实现超时 resolve、waiter 残留);
//   - 可用 AbortSignalLike 贯穿 open/reload/wait(最小取消);
//   - 提供同步 URL gate,onLoadIntercept 逐导航(含重定向)核验授权(L02)。

import { webview } from '@kit.ArkWeb';
import type { UIContext } from '@kit.ArkUI';
import { image } from '@kit.ImageKit';
import { util } from '@kit.ArkTS';
import { hilog } from '@kit.PerformanceAnalysisKit';
import type { AbortSignalLike } from '@amber/deepread-domain';
import { makeSiteGate, siteAllowsUrl, setWebMountSitesListener } from './WebMountSites.ets';
import type { UserSiteEntry } from './WebMountSites.ets';

const DOMAIN: number = 0x0001;
const TAG: string = 'Chat.WebMount';
const OPEN_TIMEOUT_MS: number = 15000;
const HOST_MOUNT_TIMEOUT_MS: number = 10000;
const WEB_HOST_ID: string = 'webmount-web';
const INTERNAL_URL_PREFIX: string = 'about:';

// 宿主需要的最小状态同步(session → ChatPage @State,经回调)
export interface WebMountHostHooks {
  onMountNeeded: () => void;   // 首次 open 时要求宿主挂载 Web 组件
}

// bindHooks 返回的按 owner 解绑句柄:旧页面 unbind 不会解掉新页面的 hooks
export interface WebMountHookBinding {
  unbind: () => void;
}

// 逻辑 tab(单 Web 组件:active tab 的 url 由 WebView 呈现;其余 tab 仅记状态)
export interface WebMountTab {
  id: string;
  title: string;
  url: string;
}

// 等待者:settle/fail 幂等,内部清理定时器与 abort 监听
interface SettleWaiter {
  id: number;
  settle: () => void;
  fail: (e: Error) => void;
}

export class WebMountSession {
  private controller: webview.WebviewController = new webview.WebviewController();
  private currentUrl: string = '';
  private pageReady: boolean = false;
  private hostMounted: boolean = false;
  private pageEndWaiters: SettleWaiter[] = [];
  private mountWaiters: SettleWaiter[] = [];
  private nextWaiterId: number = 1;
  private hooks: WebMountHostHooks | null = null;
  private uiContext: UIContext | null = null;
  private urlGate: ((url: string) => boolean) | null = null;
  private lastBlockedUrl: string = '';
  private pendingExternalNav: boolean = false;
  // 全局开关:关闭时同步阻止一切会话操作(P2-1);由设置页 toggle 通知
  private enabled: boolean = true;
  private tabs: WebMountTab[] = [{ id: 'main', title: '', url: '' }];
  private activeTabId: string = 'main';
  // bot/hand 租约(iOS/Android 接管模式):hand = 用户手动控制,Agent 写操作暂停
  private lease: string = 'bot';

  // 租约切换(发布 AppStorage,UI 订阅 'webmountLease')
  public setLease(value: string): void {
    this.lease = value;
    AppStorage.setOrCreate<string>('webmountLease', value);
  }

  public getLease(): string {
    return this.lease;
  }

  // 返回按 owner 的 unbind:仅当 hooks 仍是本次绑定的对象时才解绑,
  // 多页/退出重开时旧页面不会解掉新页面刚绑定的 hooks。
  public bindHooks(hooks: WebMountHostHooks): WebMountHookBinding {
    this.hooks = hooks;
    const bound: WebMountHostHooks = hooks;
    const binding: WebMountHookBinding = {
      unbind: (): void => {
        if (this.hooks === bound) this.hooks = null;
      },
    };
    return binding;
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

  public getLastBlockedUrl(): string {
    return this.lastBlockedUrl;
  }

  // D6-1:导航日志(network_inspect;环形 ≤50,'blocked|ok|atMs|url')
  private navLog: string[] = [];

  public getNavLog(): string[] {
    return [...this.navLog];
  }

  private recordNav(kind: string, url: string): void {
    this.navLog.push(`${kind}|${Date.now()}|${url}`);
    if (this.navLog.length > 50) this.navLog = this.navLog.slice(this.navLog.length - 50);
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

  private isInternalUrl(url: string): boolean {
    return url.startsWith(INTERNAL_URL_PREFIX);
  }

  // 授权门:工具在每次 open/页面操作前刷新(见 WebMountTools)。null = fail closed。
  public setUrlGate(gate: ((url: string) => boolean) | null): void {
    this.urlGate = gate;
  }

  public isEnabled(): boolean {
    return this.enabled;
  }

  // 全局/助手开关的有效值(OR 语义,见 WebMountTools.syncEnabled):
  //   关闭只终止当前会话页面;下次合法建 tools 时会重新按 OR 求值,
  //   助手单独勾选 webmount 时仍可继续(全局开关非总禁用)。
  public setEnabled(value: boolean): void {
    this.enabled = value;
    if (!value) this.resetToBlank();
  }

  // 站点表变更收口(P2-1):换 gate,并核验当前 URL;失权立即复位到 about:blank。
  // 由 saveWebMountSites 成功后的单槽回调驱动,无需等下一次工具调用。
  public applySitesSnapshot(sites: UserSiteEntry[]): void {
    this.urlGate = makeSiteGate(sites);
    const current: string = this.currentUrl;
    if (current.length > 0 && !siteAllowsUrl(sites, current)) {
      hilog.warn(DOMAIN, TAG, 'current page no longer allowed after sites change: %{public}s', current);
      this.resetToBlank();
    }
  }

  // onLoadIntercept 主框架判定:true = 阻止加载。仅 http(s) 且命中当次站点快照才放行,
  //   about: 内部占位页放行;gate 未设置时 fail closed(拒绝一切外部导航)。
  private shouldBlockUrlInner(url: string): boolean {
    if (this.isInternalUrl(url)) return false;
    if (!this.enabled) {
      this.lastBlockedUrl = url;
      return true;
    }
    if (this.isAllowedNav(url)) return false;
    this.lastBlockedUrl = url;
    hilog.warn(DOMAIN, TAG, 'navigation blocked by site gate: %{public}s', url);
    return true;
  }

  public shouldBlockUrl(url: string): boolean {
    const blocked: boolean = this.shouldBlockUrlInner(url);
    if (blocked) this.recordNav('blocked', url);
    return blocked;
  }

  // 单次导航授权判定(内部页放行;gate 缺失 fail closed)
  private isAllowedNav(url: string): boolean {
    if (this.isInternalUrl(url)) return true;
    if (this.urlGate === null) return false;
    return this.urlGate(url);
  }

  // Web.onControllerAttached:controller 真正绑定成功 → 宿主可用
  public markControllerAttached(): void {
    this.hostMounted = true;
    const waiters: SettleWaiter[] = this.mountWaiters;
    this.mountWaiters = [];
    for (const w of waiters) w.settle();
  }

  // Web.onDetach(或页面销毁):对称复位,终止未完成等待
  public markControllerDetached(): void {
    this.hostMounted = false;
    this.pageReady = false;
    this.uiContext = null;
    this.pendingExternalNav = false;
    const err: Error = new Error('WebMount host detached');
    const mounts: SettleWaiter[] = this.mountWaiters;
    this.mountWaiters = [];
    for (const w of mounts) w.fail(err);
    const pages: SettleWaiter[] = this.pageEndWaiters;
    this.pageEndWaiters = [];
    for (const w of pages) w.fail(err);
  }

  // Web.onPageEnd:用事件真实 url(F-02 残余:跟随链接后不再沿用旧地址)
  public markPageEnd(url: string): void {
    this.recordNav('ok', url);
    const finalUrl: string = url.length > 0 ? url : this.currentUrl;
    // 宿主初始 about:blank 的 pageEnd 不算本次外部导航完成
    if (this.pendingExternalNav && this.isInternalUrl(finalUrl)) return;
    this.pendingExternalNav = false;
    this.pageReady = true;
    if (!this.isAllowedNav(finalUrl)) {
      // 重定向/跳转落到未授权主机:保持 not-ready,open 会失败
      this.pageReady = false;
      this.lastBlockedUrl = finalUrl;
      hilog.warn(DOMAIN, TAG, 'page end on non-allowed url: %{public}s', finalUrl);
    }
    this.currentUrl = finalUrl;
    this.touchActiveTab(finalUrl);
    // 页面状态发布(ChatPage 接管条订阅 'webmountUrl'/'webmountReady')
    AppStorage.setOrCreate<string>('webmountUrl', this.currentUrl);
    AppStorage.setOrCreate<boolean>('webmountReady', this.pageReady);
    const waiters: SettleWaiter[] = this.pageEndWaiters;
    this.pageEndWaiters = [];
    for (const w of waiters) w.settle();
  }

  private removePageWaiter(id: number): void {
    const idx: number = this.pageEndWaiters.findIndex(
      (w: SettleWaiter): boolean => w.id === id);
    if (idx >= 0) this.pageEndWaiters.splice(idx, 1);
  }

  private removeMountWaiter(id: number): void {
    const idx: number = this.mountWaiters.findIndex(
      (w: SettleWaiter): boolean => w.id === id);
    if (idx >= 0) this.mountWaiters.splice(idx, 1);
  }

  // 等待 controller attached;超时/取消正确 reject
  private waitForMount(signal?: AbortSignalLike): Promise<void> {
    return new Promise<void>((resolve: () => void, reject: (e: Error) => void): void => {
      let done: boolean = false;
      let timer: number = -1;
      let onAbort: () => void = (): void => {};
      const id: number = this.nextWaiterId;
      this.nextWaiterId = id + 1;
      const waiter: SettleWaiter = {
        id: id,
        settle: (): void => {
          if (done) return;
          done = true;
          if (timer >= 0) clearTimeout(timer);
          if (signal !== undefined) signal.removeEventListener?.('abort', onAbort);
          resolve();
        },
        fail: (e: Error): void => {
          if (done) return;
          done = true;
          if (timer >= 0) clearTimeout(timer);
          if (signal !== undefined) signal.removeEventListener?.('abort', onAbort);
          reject(e);
        },
      };
      timer = setTimeout((): void => {
        this.removeMountWaiter(id);
        waiter.fail(new Error('WebMount host mount timeout'));
      }, HOST_MOUNT_TIMEOUT_MS);
      onAbort = (): void => {
        this.removeMountWaiter(id);
        waiter.fail(new Error('WebMount cancelled'));
      };
      this.mountWaiters.push(waiter);
      if (signal !== undefined) {
        if (signal.aborted) {
          this.removeMountWaiter(id);
          waiter.fail(new Error('WebMount cancelled'));
          return;
        }
        signal.addEventListener?.('abort', onAbort);
      }
    });
  }

  // 等待本次导航 onPageEnd;超时/取消正确 reject 并从等待列表移除
  private waitForPageEnd(signal?: AbortSignalLike): Promise<void> {
    return new Promise<void>((resolve: () => void, reject: (e: Error) => void): void => {
      let done: boolean = false;
      let timer: number = -1;
      let onAbort: () => void = (): void => {};
      const id: number = this.nextWaiterId;
      this.nextWaiterId = id + 1;
      const waiter: SettleWaiter = {
        id: id,
        settle: (): void => {
          if (done) return;
          done = true;
          if (timer >= 0) clearTimeout(timer);
          if (signal !== undefined) signal.removeEventListener?.('abort', onAbort);
          resolve();
        },
        fail: (e: Error): void => {
          if (done) return;
          done = true;
          if (timer >= 0) clearTimeout(timer);
          if (signal !== undefined) signal.removeEventListener?.('abort', onAbort);
          reject(e);
        },
      };
      timer = setTimeout((): void => {
        this.removePageWaiter(id);
        waiter.fail(new Error(`WebMount page load timeout: ${this.currentUrl}`));
      }, OPEN_TIMEOUT_MS);
      onAbort = (): void => {
        this.removePageWaiter(id);
        try {
          this.controller.stop();
        } catch (_e) {
          // controller 可能已解绑;忽略
        }
        waiter.fail(new Error('WebMount cancelled'));
      };
      this.pageEndWaiters.push(waiter);
      if (signal !== undefined) {
        if (signal.aborted) {
          this.removePageWaiter(id);
          waiter.fail(new Error('WebMount cancelled'));
          return;
        }
        signal.addEventListener?.('abort', onAbort);
      }
    });
  }

  private async ensureHost(signal?: AbortSignalLike): Promise<void> {
    if (this.hostMounted) return;
    if (this.hooks !== null) this.hooks.onMountNeeded();
    await this.waitForMount(signal);
    if (!this.hostMounted) throw new Error('WebMount host not mounted');
  }

  // 打开 URL(调用方已完成标准解析 + 授权校验):挂载宿主 → loadUrl → 等本次 onPageEnd
  public async open(url: string, signal?: AbortSignalLike): Promise<void> {
    await this.ensureHost(signal);
    this.pageReady = false;
    this.pendingExternalNav = !this.isInternalUrl(url);
    this.currentUrl = url;
    this.touchActiveTab(url);
    try {
      this.controller.loadUrl(url);
    } catch (e) {
      this.pendingExternalNav = false;
      throw new Error(`WebMount loadUrl failed: ${e instanceof Error ? e.message : String(e)}`);
    }
    await this.waitForPageEnd(signal);
    if (!this.pageReady) throw new Error(`WebMount page load failed: ${url}`);
  }

  // 复位到 about:blank(revoke 后丢弃当前页面内容);内部页不受 gate 阻断
  public resetToBlank(): void {
    this.pageReady = false;
    this.pendingExternalNav = false;
    this.currentUrl = '';
    this.touchActiveTab('');
    try {
      this.controller.loadUrl('about:blank');
    } catch (_e) {
      // 宿主未挂载/已解绑;状态已复位
    }
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

  // 重载并等待本次 onPageEnd;超时/取消 reject(不再静默回报 reloaded)
  public async reload(signal?: AbortSignalLike): Promise<void> {
    if (!this.hostMounted) throw new Error('WebMount host not mounted');
    this.pageReady = false;
    this.pendingExternalNav = true;
    this.controller.refresh();
    await this.waitForPageEnd(signal);
    if (!this.pageReady) throw new Error(`WebMount reload failed: ${this.currentUrl}`);
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
    // P2-1:任何 saveWebMountSites 成功后立即刷新本会话授权(revoke 无需等下次工具)
    setWebMountSitesListener((sites: UserSiteEntry[]): void => {
      if (globalSession !== null) globalSession.applySitesSnapshot(sites);
    });
    hilog.info(DOMAIN, TAG, 'session created');
  }
  return globalSession;
};

// 设置页全局开关**显式关闭**回调:立即终止当前会话(复位页面、阻止后续操作),
//   但不改变 OR 语义——下一次合法建 tools 时由 wm_open/guardPage 重新按
//   (global || assistantEnabled) 求值;助手单独勾选 webmount 时仍可继续。
export const setWebMountGlobalEnabled = (value: boolean): void => {
  if (globalSession !== null) globalSession.setEnabled(value);
};

// WebMountSurface — 会话可视面(同一 controller;offscreen host 与接管面板共用)
@Component
export struct WebMountSurface {
  private session: WebMountSession = getWebMountSession();

  build(): void {
    Web({ src: 'about:blank', controller: this.session.getController() })
      .id(WEB_HOST_ID)
      .onControllerAttached((): void => {
        this.session.markControllerAttached();
      })
      // L02:每次主框架导航(含重定向)同步核验授权,未授权一律阻止
      .onLoadIntercept((event: OnLoadInterceptEvent): boolean => {
        const request: WebResourceRequest = event.data;
        if (!request.isMainFrame()) return false;
        return this.session.shouldBlockUrl(request.getRequestUrl());
      })
      .onPageEnd((event: OnPageEndEvent): void => {
        this.session.markPageEnd(event.url);
      })
      // 卸载对称复位:hostMounted/UI context/未完成等待者
      .onDetach((): void => {
        this.session.markControllerDetached();
      })
  }
}

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
      WebMountSurface()
        .width(360).height(720)
        .position({ x: -2000, y: 0 })
    }
  }
}
