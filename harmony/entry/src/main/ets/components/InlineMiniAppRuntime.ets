// InlineMiniAppRuntime — 完成后的小应用内嵌运行时。
//
// 生成中的内容仍走 VisualArtifactPreview 的无脚本静态预览；只有已保存的
// MiniAppRecord 才进入这里，复用 Runner 的 shell、bridge、sandbox 和图片代理。

import { router, promptAction } from '@kit.ArkUI';
import { webview } from '@kit.ArkWeb';
import { http, connection } from '@kit.NetworkKit';
import { util } from '@kit.ArkTS';
import { hilog } from '@kit.PerformanceAnalysisKit';
import {
  BRIDGE_JS, injectMiniAppShell, MINI_APP_SHELL_BASE_URL, MiniAppUrlGuard,
  MINI_APP_MAX_IMAGE_BYTES, MINI_APP_IMAGE_ACCEPT_HEADER,
} from '@amber/chat-domain';
import type { MiniAppRecord, MiniAppHostResolver } from '@amber/chat-domain';
import { ACCENT, BG, ERROR, INK3, LINE } from '../design/tokens.ets';
import { getMiniAppPlatform } from '../platform_impl/MiniAppPlatform.ets';
import type { MiniAppPlatform } from '../platform_impl/MiniAppPlatform.ets';
import { MiniAppBridgeImpl } from '../platform_impl/MiniAppBridgeImpl.ets';
import type { MiniAppBridgeHost, MiniAppThemeJson } from '../platform_impl/MiniAppBridgeImpl.ets';
import { activateArtifactInteraction, clearArtifactInteraction } from '../platform_impl/ArtifactInteraction.ets';
import { UIGenerationMotion } from '../platform_impl/UIGenerationMotion.ets';

const HILOG_DOMAIN: number = 0x0001;
const HILOG_TAG: string = 'MiniApp.Inline';
const INLINE_COMPACT_HEIGHT: number = 280;
const INLINE_EXPANDED_HEIGHT: number = 600;
const MAX_PREFETCH_IMAGE_URLS: number = 32;
const PREFETCH_IMAGE_CONCURRENCY: number = 4;
const PREFETCH_IMAGE_TOTAL_BYTES: number = 8 * 1024 * 1024;

const inlineShell = (html: string, bridgeScript: string, token: string): string => {
  const shell: string = injectMiniAppShell(html, bridgeScript, token, true);
  // Generated controls may declare touch-action:none; keep vertical chat scrolling available.
  const policy: string = '<style id="amber-inline-touch-policy">' +
    'html,body,body *{touch-action:pan-y!important;}' +
    'html.amber-canvas-active canvas{touch-action:none!important;}</style>';
  return /<\/head>/i.test(shell) ? shell.replace(/<\/head>/i, `${policy}</head>`) : shell + policy;
};

interface CanvasInteractionMessage {
  type: string;
  focused: boolean;
  sessionToken: string;
}

interface PrefetchBudget { remainingBytes: number; }
interface UsedBytes { bytes: number; }

const dnsResolveHost: MiniAppHostResolver = async (host: string): Promise<string[]> => {
  try {
    const addresses = await connection.getAddressesByName(host);
    const result: string[] = [];
    for (const address of addresses) {
      if (address.address !== undefined && address.address !== null && address.address.trim().length > 0) {
        result.push(address.address.trim());
      }
    }
    if (result.length === 0) throw new Error('empty dns result');
    return result;
  } catch {
    throw new Error('dns failed');
  }
};

const extractHttpsImageUrls = (html: string): string[] => {
  const result: string[] = [];
  const pattern: RegExp = /<(?:img|source)\b[^>]*\bsrc\s*=\s*["'](https:\/\/[^"']+)["']/gi;
  let match: RegExpExecArray | null;
  while ((match = pattern.exec(html)) !== null) {
    if (match[1] !== undefined && result.indexOf(match[1]) < 0) result.push(match[1]);
    if (result.length >= MAX_PREFETCH_IMAGE_URLS) break;
  }
  return result;
};

@Component
export struct InlineMiniAppRuntime {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('darkMode') darkMode: boolean = false;
  @StorageProp('reduceMotion') @Watch('motionPolicyChanged') reduceMotion: boolean = false;
  @StorageProp('appBackgrounded') @Watch('updateActivity') backgrounded: boolean = false;
  @StorageLink('activeArtifactInteraction') @Watch('onArtifactInteractionChanged')
  activeArtifactInteraction: string = '';
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @Prop @Watch('onAppChanged') app: MiniAppRecord | null = null;
  @Prop @Watch('onRevealEligibilityChanged') revealEligible: boolean = false;
  @Prop complete: boolean = false;
  onContentGrow: () => void = (): void => {};
  @State expanded: boolean = false;
  @State errorMessage: string = '';
  @State ready: boolean = false;
  private alive: boolean = false;
  private attached: boolean = false;
  private platform: MiniAppPlatform | null = null;
  private bridge: MiniAppBridgeImpl | null = null;
  private controller: webview.WebviewController = new webview.WebviewController();
  private motion: UIGenerationMotion = new UIGenerationMotion(this.controller);
  private sessionToken: string = '';
  private imageCache: Map<string, WebResourceResponse> = new Map<string, WebResourceResponse>();
  private imageGuard: MiniAppUrlGuard = new MiniAppUrlGuard(dnsResolveHost);
  private prefetchStarted: boolean = false;
  private mountedAppId: string = '';
  private mountedAppVersion: number = 0;
  private runtimeRevision: number = 0;
  private activeRevision: number = 0;
  private activePageRevision: number = 0;
  private visible: boolean = true;
  private lastVisibility: boolean | null = null;
  private prefetchFinished: boolean = false;
  private prefetchRefreshPending: boolean = false;
  private interactionOwner: string = util.generateRandomUUID(false);
  private interactionArea: Area | null = null;

  aboutToAppear(): void {
    this.alive = true;
    this.prepareRuntime();
  }

  aboutToDisappear(): void {
    this.clearOwnInteraction();
    this.motion.cancel(false);
    this.emitVisibility(false);
    this.alive = false;
    this.runtimeRevision++;
    this.closeBridge();
    this.imageCache.clear();
    this.attached = false;
  }

  private onAppChanged(): void {
    if (!this.alive) return;
    const app: MiniAppRecord | null = this.app;
    if (app === null) {
      this.clearOwnInteraction();
      this.motion.cancel(false);
      this.runtimeRevision++;
      this.closeBridge();
      this.imageCache.clear();
      return;
    }
    if (app.id === this.mountedAppId && app.version === this.mountedAppVersion) return;
    this.prepareRuntime();
  }

  private prepareRuntime(): void {
    const app: MiniAppRecord | null = this.app;
    if (app === null || !this.complete) return;
    const revision: number = ++this.runtimeRevision;
    this.activeRevision = revision;
    this.clearOwnInteraction();
    this.motion.cancel(false);
    this.closeBridge();
    this.sessionToken = '';
    this.errorMessage = '';
    this.ready = false;
    this.mountedAppId = app.id;
    this.mountedAppVersion = app.version;
    this.imageCache.clear();
    this.prefetchStarted = false;
    this.prefetchFinished = false;
    this.prefetchRefreshPending = false;
    getMiniAppPlatform().then((platform: MiniAppPlatform): void => {
      if (!this.isCurrent(revision, app)) return;
      this.platform = platform;
      if (this.attached && this.isActive()) this.loadApp(app, revision);
    }).catch((e: Error): void => {
      if (this.isCurrent(revision, app)) this.errorMessage = e.message;
    });
  }

  private isCurrent(revision: number, app: MiniAppRecord): boolean {
    return this.alive && revision === this.runtimeRevision &&
      this.app !== null && this.app.id === app.id && this.app.version === app.version;
  }

  private isActive(): boolean {
    return this.alive && this.visible && !this.backgrounded;
  }

  private closeBridge(): void {
    if (this.bridge !== null) {
      this.bridge.close();
      this.bridge = null;
    }
  }

  private isCanvasInteractionActive(): boolean {
    return this.activeArtifactInteraction === this.interactionOwner;
  }

  private clearOwnInteraction(): void {
    if (this.isCanvasInteractionActive()) clearArtifactInteraction(this.interactionOwner);
    this.interactionArea = null;
  }

  private syncCanvasInteraction(): void {
    if (!this.attached || !this.ready) return;
    this.controller.runJavaScript(`window.__amberSetCanvasInteraction(${this.isCanvasInteractionActive()})`)
      .catch((): void => {});
  }

  private onArtifactInteractionChanged(): void {
    this.syncCanvasInteraction();
  }

  private onRevealEligibilityChanged(): void {
    if (this.revealEligible) this.playRevealIfReady();
  }

  private motionPolicyChanged(): void {
    if (this.reduceMotion) {
      this.motion.cancel(true);
    } else {
      this.playRevealIfReady();
    }
    this.updateActivity();
  }

  private onInteractionMessage(raw: string): void {
    if (!this.alive || !this.ready || !this.isActive() || raw.length === 0 || raw.length > 1024) return;
    try {
      const message: CanvasInteractionMessage = JSON.parse(raw) as CanvasInteractionMessage;
      if (message.type !== 'canvasFocus' || message.sessionToken !== this.sessionToken) return;
      if (message.focused === true) {
        if (this.interactionArea !== null) {
          activateArtifactInteraction(this.interactionOwner, this.interactionArea);
        }
      } else {
        this.clearOwnInteraction();
      }
    } catch (_) {}
  }

  private onWebAreaChange(_oldArea: Area, area: Area): void {
    this.interactionArea = area;
    if (this.isCanvasInteractionActive()) {
      activateArtifactInteraction(this.interactionOwner, area);
    }
  }

  private onWebTouch(event: TouchEvent): void {
    if (event.type === TouchType.Down) this.motion.cancel(true);
    // A cancelled native stream means the parent chat has taken the gesture.
    if (event.type === TouchType.Cancel && this.isCanvasInteractionActive()) {
      this.clearOwnInteraction();
    }
  }

  private attachBridge(app: MiniAppRecord): void {
    const platform: MiniAppPlatform | null = this.platform;
    if (platform === null) return;
    this.closeBridge();
    const host: MiniAppBridgeHost = {
      toast: (message: string): void => {
        promptAction.showToast({ message: message, duration: 1600 });
      },
      confirm: (title: string, message: string): Promise<boolean> =>
        promptAction.showDialog({
          title: title,
          message: message,
          buttons: [
            { text: '允许', color: this.accentColorStore },
            { text: '拒绝', color: INK3 },
          ],
        }).then((result: promptAction.ShowDialogSuccessResponse): boolean => result.index === 0)
          .catch((): boolean => false),
      theme: (): MiniAppThemeJson => ({
        dark: this.darkMode,
        background: this.darkMode ? '#1C1B17' : '#FAF9F5',
        foreground: this.darkMode ? '#ECE8DF' : '#1B1A17',
        primary: this.accentColorStore,
      }),
      launch: (targetAppId: string): void => {
        router.pushUrl({ url: 'pages/MiniAppRunnerPage', params: { appId: targetAppId } });
      },
    };
    if (this.sessionToken.length === 0) this.sessionToken = util.generateRandomUUID(false);
    this.bridge = new MiniAppBridgeImpl(platform, {
      appId: app.id,
      sessionToken: this.sessionToken,
      host: host,
      app: (): MiniAppRecord | null => this.app,
      runJs: (script: string): void => {
        this.controller.runJavaScript(script).catch((e: Error): void => {
          hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'bridge response failed: %{public}s', e.message);
        });
      },
    });
  }

  private onBridgeMessage(raw: string): void {
    if (this.bridge !== null) this.bridge.postMessage(raw);
  }

  private loadApp(app: MiniAppRecord, revision: number): void {
    if (!this.attached || this.platform === null || !this.complete || !this.isCurrent(revision, app)) return;
    this.activePageRevision = revision;
    this.sessionToken = util.generateRandomUUID(false);
    this.attachBridge(app);
    this.controller.loadData(
      inlineShell(app.htmlContent, BRIDGE_JS, this.sessionToken),
      'text/html', 'utf-8', MINI_APP_SHELL_BASE_URL);
  }

  private prefetchImagesAndReload(): boolean {
    const app: MiniAppRecord | null = this.app;
    const platform: MiniAppPlatform | null = this.platform;
    const revision: number = this.activePageRevision;
    if (app === null || platform === null || revision !== this.activeRevision) return false;
    if (this.prefetchStarted) return !this.prefetchFinished;
    if (!this.isActive()) return true;
    this.prefetchStarted = true;
    const urls: string[] = extractHttpsImageUrls(app.htmlContent);
    if (urls.length === 0) {
      this.prefetchFinished = true;
      return false;
    }
    const budget: PrefetchBudget = { remainingBytes: PREFETCH_IMAGE_TOTAL_BYTES };
    const queue: string[] = [...urls];
    let fetched: number = 0;
    const worker = async (): Promise<void> => {
      for (;;) {
        const next: string | undefined = queue.shift();
        if (next === undefined) return;
        if (budget.remainingBytes < MINI_APP_MAX_IMAGE_BYTES) {
          if (this.isActive() && this.isCurrent(revision, app)) {
            this.imageCache.set(next, this.blockedResponse());
          }
          continue;
        }
        budget.remainingBytes -= MINI_APP_MAX_IMAGE_BYTES;
        const used: UsedBytes = { bytes: 0 };
        if (await this.fetchAndCacheImage(next, used, revision, app)) fetched++;
        budget.remainingBytes += MINI_APP_MAX_IMAGE_BYTES - used.bytes;
      }
    };
    const tasks: Promise<void>[] = [];
    for (let index: number = 0; index < PREFETCH_IMAGE_CONCURRENCY; index++) tasks.push(worker());
    Promise.all(tasks).then((): void => {
      if (!this.isCurrent(revision, app)) return;
      this.prefetchFinished = true;
      if (fetched > 0) {
        if (this.isActive()) {
          this.ready = false;
          this.motion.cancel(false);
          this.controller.refresh();
        }
        else this.prefetchRefreshPending = true;
      } else {
        this.playRevealIfReady();
      }
    }).catch((e: Error): void => {
      hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'inline image prefetch failed: %{public}s', e.message);
      if (this.isCurrent(revision, app)) {
        this.prefetchFinished = true;
        this.playRevealIfReady();
      }
    });
    return true;
  }

  private playRevealIfReady(): void {
    const app: MiniAppRecord | null = this.app;
    if (app === null || !this.complete || !this.ready || !this.prefetchFinished ||
      this.prefetchRefreshPending || !this.isActive() || this.activePageRevision !== this.activeRevision) {
      return;
    }
    this.motion.play(`miniapp:${app.id}:${app.version}`, this.revealEligible, this.reduceMotion, false);
  }

  private async fetchAndCacheImage(
    url: string, used: UsedBytes, revision: number, app: MiniAppRecord): Promise<boolean> {
    const platform: MiniAppPlatform | null = this.platform;
    if (platform === null || !this.isCurrent(revision, app)) return false;
    try {
      platform.sandboxFor(app.id, app.permissions).require('externalImages');
      const bytes: Uint8Array = await this.fetchImageBytes(url);
      if (!this.isCurrent(revision, app)) return false;
      used.bytes = bytes.byteLength;
      const response: WebResourceResponse = new WebResourceResponse();
      const buffer: ArrayBuffer = bytes.buffer.slice(
        bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
      response.setResponseData(buffer);
      response.setResponseEncoding('utf-8');
      response.setResponseMimeType(this.sniffImageContentType(bytes));
      response.setResponseCode(200);
      response.setReasonMessage('OK');
      if (!this.isCurrent(revision, app)) return false;
      this.imageCache.set(url, response);
      return true;
    } catch (e) {
      hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'inline image blocked: %{public}s',
        e instanceof Error ? e.message : String(e));
      if (this.isCurrent(revision, app)) {
        this.imageCache.set(url, this.blockedResponse());
      }
      return false;
    }
  }

  private async fetchImageBytes(url: string): Promise<Uint8Array> {
    await this.imageGuard.check(url);
    const request: http.HttpRequest = http.createHttp();
    try {
      const response: http.HttpResponse = await request.request(url, {
        method: http.RequestMethod.GET,
        header: { accept: MINI_APP_IMAGE_ACCEPT_HEADER, 'user-agent': 'AmberAgent-MiniApp/2' },
        expectDataType: http.HttpDataType.ARRAY_BUFFER,
        connectTimeout: 10000,
        readTimeout: 15000,
        usingCache: false,
      });
      if (!(response.result instanceof ArrayBuffer)) throw new Error('Image fetch returned non-binary response');
      const bytes: Uint8Array = new Uint8Array(response.result);
      if (bytes.byteLength > MINI_APP_MAX_IMAGE_BYTES) throw new Error('Image is too large');
      return bytes;
    } finally {
      request.destroy();
    }
  }

  private sniffImageContentType(bytes: Uint8Array): string {
    if (bytes.length >= 8 && bytes[0] === 0x89 && bytes[1] === 0x50 &&
      bytes[2] === 0x4e && bytes[3] === 0x47) return 'image/png';
    if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) {
      return 'image/jpeg';
    }
    if (bytes.length >= 6 && bytes[0] === 0x47 && bytes[1] === 0x49 &&
      bytes[2] === 0x46 && bytes[3] === 0x38) return 'image/gif';
    if (bytes.length >= 12 && bytes[0] === 0x52 && bytes[1] === 0x49 &&
      bytes[2] === 0x46 && bytes[3] === 0x46 && bytes[8] === 0x57 &&
      bytes[9] === 0x45 && bytes[10] === 0x42 && bytes[11] === 0x50) return 'image/webp';
    return 'image/png';
  }

  private blockedResponse(): WebResourceResponse {
    const response: WebResourceResponse = new WebResourceResponse();
    response.setResponseData('');
    response.setResponseEncoding('utf-8');
    response.setResponseMimeType('text/plain');
    response.setResponseCode(403);
    response.setReasonMessage('Forbidden');
    return response;
  }

  private toggleExpanded(): void {
    this.clearOwnInteraction();
    this.expanded = !this.expanded;
    this.onContentGrow();
  }

  @Builder
  private webView(): void {
    Web({ src: '', controller: this.controller })
      .javaScriptAccess(true)
      .domStorageAccess(false)
      .fileAccess(false)
      .onlineImageAccess(false)
      .mixedMode(MixedMode.None)
      .cacheMode(CacheMode.None)
      .zoomAccess(false)
      .textAutosizing(false)
      .overScrollMode(OverScrollMode.NEVER)
      .nestedScroll({
        scrollForward: this.isCanvasInteractionActive()
          ? NestedScrollMode.SELF_ONLY : NestedScrollMode.PARENT_FIRST,
        scrollBackward: this.isCanvasInteractionActive()
          ? NestedScrollMode.SELF_ONLY : NestedScrollMode.PARENT_FIRST,
      })
      .javaScriptProxy({
        object: {
          postMessage: (raw: string): void => this.onBridgeMessage(raw),
          postInteraction: (raw: string): void => this.onInteractionMessage(raw),
        },
        name: 'AmberNative',
        methodList: ['postMessage', 'postInteraction'],
        controller: this.controller,
      })
      .onControllerAttached((): void => {
        this.attached = true;
        const current: MiniAppRecord | null = this.app;
        if (current !== null && this.isActive()) this.loadApp(current, this.activeRevision);
      })
      .onPageEnd((): void => {
        if (!this.alive || this.activePageRevision !== this.activeRevision) return;
        this.ready = true;
        const waiting: boolean = this.prefetchImagesAndReload();
        if (!waiting && this.prefetchRefreshPending) {
          this.prefetchRefreshPending = false;
          this.ready = false;
          this.motion.cancel(false);
          this.controller.refresh();
        } else if (!waiting) {
          this.playRevealIfReady();
        }
        this.updateActivity();
        this.syncCanvasInteraction();
      })
      .onErrorReceive((event: OnErrorReceiveEvent): void => {
        if (!this.alive || this.activePageRevision !== this.activeRevision) return;
        this.motion.cancel(false);
        this.ready = false;
        this.prefetchRefreshPending = false;
        this.errorMessage = event.error !== null && event.error !== undefined
          ? String(event.error) : '小应用加载失败';
      })
      .onOverrideUrlLoading((request: WebResourceRequest): boolean => {
        const url: string = request.getRequestUrl();
        return request.isRequestGesture() || !url.startsWith(MINI_APP_SHELL_BASE_URL);
      })
      .onInterceptRequest((event: OnInterceptRequestEvent): WebResourceResponse | null => {
        const url: string = event.request.getRequestUrl();
        if (url.startsWith('data:')) return null;
        if (!url.startsWith('https://')) return this.blockedResponse();
        const cached: WebResourceResponse | undefined = this.imageCache.get(url);
        return cached === undefined ? this.blockedResponse() : cached;
      })
      .onTouch((event: TouchEvent): void => { this.onWebTouch(event); })
      .width('100%')
      .height(this.expanded ? INLINE_EXPANDED_HEIGHT : INLINE_COMPACT_HEIGHT)
      .backgroundColor(Color.Transparent)
      .clip(true)
  }

  private emitVisibility(active: boolean): void {
    if (!this.attached || !this.ready || this.lastVisibility === active) return;
    this.lastVisibility = active;
    try {
      if (active) this.controller.onActive();
      else this.controller.onInactive();
    } catch (_) {}
    const state: string = active ? 'visible' : 'hidden';
    this.controller.runJavaScript(
      `document.dispatchEvent(new Event('visibilitychange'));` +
      `window.dispatchEvent(new Event('${state === 'visible' ? 'pageshow' : 'pagehide'}'));`)
      .catch((): void => {});
  }

  private updateActivity(): void {
    const app: MiniAppRecord | null = this.app;
    if (app === null || !this.alive) return;
    if (!this.isActive()) {
      this.clearOwnInteraction();
      this.motion.cancel(false);
      this.emitVisibility(false);
      this.closeBridge();
      return;
    }
    if (this.attached && (!this.ready || this.activePageRevision !== this.activeRevision)) {
      this.loadApp(app, this.activeRevision);
    } else if (this.attached && this.ready && this.bridge === null &&
      this.activePageRevision === this.activeRevision) {
      this.attachBridge(app);
    }
    if (this.bridge !== null && this.ready) this.emitVisibility(true);
    if (this.ready) {
      const waiting: boolean = this.prefetchImagesAndReload();
      if (!waiting && this.prefetchRefreshPending) {
        this.prefetchRefreshPending = false;
        this.ready = false;
        this.motion.cancel(false);
        this.controller.refresh();
      } else if (!waiting) {
        this.playRevealIfReady();
      }
    }
  }

  build(): void {
    Column() {
      if (this.app !== null && this.complete) {
        this.webView()
        Row() {
          if (this.isCanvasInteractionActive()) {
            Text('退出操控')
              .fontSize(12)
              .fontColor(this.accentColorStore)
              .padding({ top: 10, bottom: 10, left: 12, right: 12 })
              .onClick((): void => this.clearOwnInteraction())
              .accessibilityText('退出画布操控')
          }
          Text(this.expanded ? '收起预览' : '展开预览')
            .fontSize(12)
            .fontColor(this.accentColorStore)
            .padding({ top: 10, bottom: 10, left: 12, right: 12 })
            .onClick((): void => this.toggleExpanded())
        }
        .width('100%').height(44)
        .justifyContent(FlexAlign.End)
        .alignItems(VerticalAlign.Center)
      }
      if (this.errorMessage.length > 0) {
        Text(this.errorMessage).fontSize(12).fontColor(ERROR).width('100%').padding(8)
      } else if (!this.ready && this.app !== null && this.complete) {
        Text('正在加载小应用…').fontSize(12).fontColor(INK3).width('100%').padding(8)
      }
    }
    .width('100%')
    .backgroundColor(BG)
    .border({
      width: 1,
      color: this.isCanvasInteractionActive() ? ACCENT : LINE,
    })
    .borderRadius(12)
    .clip(true)
    .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .onAreaChange((oldArea: Area, area: Area): void => { this.onWebAreaChange(oldArea, area); })
    .onVisibleAreaChange([0, 1], (_expanding: boolean, ratio: number): void => {
      const nextVisible: boolean = ratio > 0;
      if (nextVisible === this.visible) return;
      this.visible = nextVisible;
      this.updateActivity();
    })
    .onSizeChange((): void => this.onContentGrow())
  }
}
