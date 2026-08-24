// MiniAppRunnerPage — 小应用运行页(Harmony 首个 WebView 使用点)
//
// 状态机 Loading / Missing / Ready / Error(对齐 MiniAppRunnerPage.kt)。
// ArkWeb 装配:
//   Web 组件 javaScriptProxy({ object: AmberNative, name, methodList: ['postMessage'] })
//   onControllerAttached → registerJavaScriptProxy + loadData(shellHtml)
//   onInterceptRequest → https 图片(externalImages 权限 + grant 过 → 代理字节流)
//   onPageEnd 后可 runJavaScript(回传 _handleNativeResponse)
// shell = HAR injectMiniAppShell(html, BRIDGE_JS, 每次运行随机 token)
// 顶部返回栏 + 菜单(查看源码[showSourceButton 门控]/导出)
//
// 图片代理实现说明:onInterceptRequest 为同步回调(component/web.d.ts
//   Callback<OnInterceptRequestEvent, WebResourceResponse>,不支持 Promise)。
//   采用「预取缓存 + refresh 重载」:onPageEnd 后异步预取 html 中全部 https
//   图片字节入缓存(externalImages 权限门),随后刷新页面,onInterceptRequest
//   命中缓存返回字节流;未授权/失败 → 空响应(Android blockedResponse 语义)。

import { router, promptAction } from '@kit.ArkUI';
import { webview } from '@kit.ArkWeb';
import { fileIo, picker } from '@kit.CoreFileKit';
import { http, connection } from '@kit.NetworkKit';
import { util } from '@kit.ArkTS';
import { hilog } from '@kit.PerformanceAnalysisKit';
import {
  BRIDGE_JS, injectMiniAppShell, MINI_APP_SHELL_BASE_URL, MiniAppUrlGuard,
  MINI_APP_MAX_IMAGE_BYTES, MINI_APP_IMAGE_ACCEPT_HEADER,
} from '@amber/chat-domain';
import type { MiniAppRecord } from '@amber/chat-domain';
import { ACCENT, BG, BODY, ERROR, INK, INK2, INK3, LINE, OVERLAY, SURFACE, TITLE } from '../design/tokens.ets';
import { getMiniAppPlatform } from '../platform_impl/MiniAppPlatform.ets';
import type { MiniAppPlatform } from '../platform_impl/MiniAppPlatform.ets';
import { MiniAppBridgeImpl } from '../platform_impl/MiniAppBridgeImpl.ets';
import type { MiniAppBridgeHost, MiniAppThemeJson } from '../platform_impl/MiniAppBridgeImpl.ets';

const HILOG_DOMAIN: number = 0x0001;
const HILOG_TAG: string = 'MiniApp.Runner';

type MiniAppRunnerStateKind = 'loading' | 'missing' | 'ready' | 'error';

interface MiniAppRunnerState {
  kind: MiniAppRunnerStateKind;
  message: string;
  app: MiniAppRecord | null;
}

const loadingState = (): MiniAppRunnerState => ({ kind: 'loading', message: '', app: null });

// DNS 解析优先 → 字面 IP 兜底(与桥接层同策略,绝不放宽私网判定)
const miniAppDnsResolve: (host: string) => Promise<string[]> = async (host: string): Promise<string[]> => {
  try {
    const addresses = await connection.getAddressesByName(host);
    const out: string[] = [];
    for (const a of addresses) {
      if (a.address !== null && a.address !== undefined && a.address.trim().length > 0) {
        out.push(a.address.trim());
      }
    }
    if (out.length === 0) throw new Error('empty dns result');
    return out;
  } catch {
    throw new Error('dns failed');
  }
};

// 从 html 提取 https 图片 src(src 属性;html 校验器已保证 img src 仅 data:/https:)
const extractHttpsImageUrls = (html: string): string[] => {
  const out: string[] = [];
  const pattern: RegExp = /<img\b[^>]*\bsrc\s*=\s*["'](https:\/\/[^"']+)["']/gi;
  let m: RegExpExecArray | null;
  while ((m = pattern.exec(html)) !== null) {
    if (m[1] !== undefined && out.indexOf(m[1]) < 0) out.push(m[1]);
  }
  return out;
};

@Entry
@Component
struct MiniAppRunnerPage {
  @State state: MiniAppRunnerState = loadingState();
  @State shellHtml: string = '';
  @State sourceDialogOpen: boolean = false;
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @StorageProp('darkMode') darkMode: boolean = false;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  private appId: string = '';
  private platform: MiniAppPlatform | null = null;
  private bridge: MiniAppBridgeImpl | null = null;
  private controller: webview.WebviewController = new webview.WebviewController();
  private sessionToken: string = '';
  private sourceTarget: string = '';
  // 图片代理缓存(url → WebResourceResponse;同步 onInterceptRequest 消费)
  private imageCache: Map<string, WebResourceResponse> = new Map<string, WebResourceResponse>();
  private imageGuard: MiniAppUrlGuard = new MiniAppUrlGuard(miniAppDnsResolve);
  private prefetchStarted: boolean = false;
  // 离页守卫:预取完成等异步回调不再触碰已销毁的 controller/@State
  private alive: boolean = false;

  aboutToAppear(): void {
    this.alive = true;
    const params = router.getParams() as Record<string, string>;
    this.appId = params ? (params['appId'] ?? '') : '';
    getMiniAppPlatform().then((platform: MiniAppPlatform): void => {
      this.platform = platform;
      // Android MiniAppRunnerPage.kt:261 setWebContentsDebuggingEnabled 等价
      //   (全局静态开关,由设置页 WebView 调试项控制)
      webview.WebviewController.setWebDebuggingAccess(platform.settingSnapshot().webViewDebugEnabled);
      this.loadApp();
    }).catch((e: Error): void => {
      this.state = { kind: 'error', message: e.message, app: null };
    });
  }

  aboutToDisappear(): void {
    this.alive = false;
    if (this.bridge !== null) {
      this.bridge.close();
      this.bridge = null;
    }
    this.imageCache.clear();
  }

  private loadApp(): void {
    const platform: MiniAppPlatform | null = this.platform;
    if (platform === null) return;
    this.state = loadingState();
    platform.repository.getById(this.appId).then((record: MiniAppRecord | null): void => {
      if (!this.alive) return;
      if (record === null) {
        this.state = { kind: 'missing', message: '小应用不存在', app: null };
        return;
      }
      // 先就绪 shell 再置 ready:Web 构建读 shellHtml,顺序无竞态
      this.sessionToken = util.generateRandomUUID(false);
      this.shellHtml = injectMiniAppShell(record.htmlContent, BRIDGE_JS, this.sessionToken);
      this.state = { kind: 'ready', message: '', app: record };
      // markRun(Android RunnerPage:90)
      platform.repository.markRun(record.id).catch((e: Error): void => {
        hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'markRun failed: %{public}s', e.message);
      });
      this.imageCache.clear();
      this.prefetchStarted = false;
    }).catch((e: Error): void => {
      if (!this.alive) return;
      this.state = { kind: 'error', message: e.message, app: null };
    });
  }

  // ===== 桥接装配 =====

  private attachBridge(app: MiniAppRecord): void {
    const platform: MiniAppPlatform | null = this.platform;
    if (platform === null) return;
    if (this.bridge !== null) {
      this.bridge.close();
    }
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
        }).then((res: promptAction.ShowDialogSuccessResponse): boolean => res.index === 0)
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
    this.bridge = new MiniAppBridgeImpl(platform, {
      appId: app.id,
      sessionToken: this.sessionToken,
      host: host,
      app: (): MiniAppRecord | null => {
        const cur: MiniAppRunnerState = this.state;
        return cur.app;
      },
      runJs: (script: string): void => {
        this.controller.runJavaScript(script).catch((e: Error): void => {
          hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'runJavaScript failed: %{public}s', e.message);
        });
      },
    });
  }

  private onBridgeMessage(raw: string): void {
    if (this.bridge !== null) {
      this.bridge.postMessage(raw);
    }
  }

  // ===== 图片代理(预取缓存 + 同步拦截) =====

  private prefetchImagesAndReload(): void {
    const app: MiniAppRecord | null = this.state.app;
    const platform: MiniAppPlatform | null = this.platform;
    if (app === null || platform === null) return;
    if (this.prefetchStarted) return;
    this.prefetchStarted = true;
    const urls: string[] = extractHttpsImageUrls(app.htmlContent);
    if (urls.length === 0) return;
    // 并发预取全部 https 图片入缓存;全部完成后才 refresh(此前 fetchedCount
    //   必为 0,onInterceptRequest 只会返回空响应 — 并发链不被丢弃)
    const tasks: Promise<boolean>[] = [];
    for (const url of urls) {
      tasks.push(this.fetchAndCacheImage(url));
    }
    Promise.all(tasks).then((oks: boolean[]): void => {
      if (!this.alive) return; // 离页后不再刷新已销毁的 controller
      let fetchedCount: number = 0;
      for (const ok of oks) {
        if (ok) fetchedCount += 1;
      }
      if (fetchedCount > 0) {
        // 刷新触发 onInterceptRequest 重放,命中缓存返回字节流
        this.controller.refresh();
      }
    }).catch((e: Error): void => {
      hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'image prefetch failed: %{public}s', e.message);
    });
  }

  private async fetchAndCacheImage(url: string): Promise<boolean> {
    const app: MiniAppRecord | null = this.state.app;
    const platform: MiniAppPlatform | null = this.platform;
    if (app === null || platform === null) return false;
    // 权限门:externalImages 全局开关 + 声明 + grant 记忆(Android RunnerPage:283-295)
    try {
      const sandbox = platform.sandboxFor(app.id, app.permissions);
      sandbox.require('externalImages');
    } catch (_e) {
      this.imageCache.set(url, this.blockedResponse());
      return false;
    }
    try {
      const bytes: Uint8Array = await this.fetchImageBytes(url);
      const contentType: string = this.sniffImageContentType(bytes);
      const response: WebResourceResponse = new WebResourceResponse();
      const buffer: ArrayBuffer = bytes.buffer.slice(
        bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
      response.setResponseData(buffer);
      response.setResponseEncoding('utf-8');
      response.setResponseMimeType(contentType);
      response.setResponseCode(200);
      response.setReasonMessage('OK');
      this.imageCache.set(url, response);
      return true;
    } catch (e) {
      hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'image proxy failed: %{public}s',
        e instanceof Error ? e.message : String(e));
      this.imageCache.set(url, this.blockedResponse());
      return false;
    }
  }

  private async fetchImageBytes(url: string): Promise<Uint8Array> {
    // URL guard:https-only + DNS 私网判定(绝不放宽)
    await this.imageGuard.check(url);
    const httpRequest: http.HttpRequest = http.createHttp();
    try {
      const options: http.HttpRequestOptions = {
        method: http.RequestMethod.GET,
        header: {
          accept: MINI_APP_IMAGE_ACCEPT_HEADER,
          'user-agent': 'AmberAgent-MiniApp/2',
        },
        expectDataType: http.HttpDataType.ARRAY_BUFFER,
        connectTimeout: 10000,
        readTimeout: 15000,
        usingCache: false,
      };
      const resp: http.HttpResponse = await httpRequest.request(url, options);
      if (resp.result instanceof ArrayBuffer) {
        const bytes: Uint8Array = new Uint8Array(resp.result);
        if (bytes.byteLength > MINI_APP_MAX_IMAGE_BYTES) {
          throw new Error('Image is too large');
        }
        return bytes;
      }
      throw new Error('Image fetch returned non-binary response');
    } finally {
      httpRequest.destroy();
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

  // ===== 菜单动作 =====

  private openMenu(): void {
    const app: MiniAppRecord | null = this.state.app;
    if (app === null) return;
    let showSource: boolean = true;
    if (this.platform !== null) {
      showSource = this.platform.settingSnapshot().showSourceButton;
    }
    const buttons: promptAction.Button[] = [];
    if (showSource) {
      buttons.push({ text: '查看源码', color: INK });
    }
    buttons.push({ text: '导出 HTML', color: INK });
    buttons.push({ text: '取消', color: INK3 });
    const menuButtons: [promptAction.Button, promptAction.Button?,
      promptAction.Button?, promptAction.Button?, promptAction.Button?, promptAction.Button?] =
      [buttons[0], buttons.length > 1 ? buttons[1] : undefined,
        buttons.length > 2 ? buttons[2] : undefined, undefined, undefined, undefined];
    promptAction.showActionMenu({
      title: app.title,
      buttons: menuButtons,
    }).then((res: promptAction.ActionMenuSuccessResponse): void => {
      const index: number = res.index;
      if (showSource && index === 0) {
        this.sourceTarget = app.htmlContent;
        this.sourceDialogOpen = true;
      } else if ((showSource && index === 1) || (!showSource && index === 0)) {
        this.exportHtml(app);
      }
    }).catch((): void => {});
  }

  private exportHtml(app: MiniAppRecord): void {
    const ctx: Context = getContext(this) as Context;
    const safeName: string = app.title
      .replace(/[\\/:*?"<>|]+/g, '_')
      .slice(0, 48);
    const fileName: string = `${safeName.length > 0 ? safeName : 'miniapp'}.html`;
    const docPicker = new picker.DocumentViewPicker(ctx);
    docPicker.save({ newFileNames: [fileName] }).then((uris: string[]): void => {
      if (uris.length > 0) {
        this.writeToUri(uris[0], app.htmlContent);
      } else {
        this.exportToDownloadDir(app.htmlContent);
      }
    }).catch((): void => {
      this.exportToDownloadDir(app.htmlContent);
    });
  }

  private writeToUri(uri: string, content: string): void {
    try {
      const file: fileIo.File = fileIo.openSync(
        uri, fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
      fileIo.writeSync(file.fd, util.TextEncoder.create('utf-8').encodeInto(content));
      fileIo.closeSync(file.fd);
      promptAction.showToast({ message: '已导出 HTML', duration: 1600 });
    } catch (e) {
      hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'picker write failed: %{public}s',
        e instanceof Error ? e.message : String(e));
      this.exportToDownloadDir(content);
    }
  }

  // 导出降级:写 filesDir/Download/miniapp-<title>.html 并 toast 路径
  private exportToDownloadDir(content: string): void {
    const ctx: Context = getContext(this) as Context;
    const dir: string = `${ctx.filesDir}/Download`;
    const safe: string = (this.state.app !== null ? this.state.app.title : 'miniapp')
      .replace(/[\\/:*?"<>|]+/g, '_').slice(0, 48);
    const name: string = `${safe.length > 0 ? safe : 'miniapp'}.html`;
    try {
      fileIo.mkdirSync(dir);
    } catch (_e) {
      // 已存在
    }
    const path: string = `${dir}/${name}`;
    try {
      const file: fileIo.File = fileIo.openSync(
        path, fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
      fileIo.writeSync(file.fd, util.TextEncoder.create('utf-8').encodeInto(content));
      fileIo.closeSync(file.fd);
      promptAction.showToast({ message: `已导出到 ${path}`, duration: 2200 });
    } catch (e) {
      hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'download export failed: %{public}s',
        e instanceof Error ? e.message : String(e));
      promptAction.showToast({ message: '导出失败', duration: 1600 });
    }
  }

  // ===== UI =====

  @Builder
  topBar(app: MiniAppRecord): void {
    Row() {
      Row() {
        Text('←').fontSize(24).fontColor(INK)
      }
      .width(44).height(44)
      .justifyContent(FlexAlign.Center)
      .alignItems(VerticalAlign.Center)
      .accessibilityText('返回')
      .onClick((): void => { router.back(); })

      Text(app.title)
        .fontSize(TITLE)
        .fontWeight(FontWeight.Bold)
        .fontColor(INK)
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .layoutWeight(1)
        .margin({ left: 8 })

      Row() {
        Text('⋯').fontSize(20).fontColor(INK2)
      }
      .width(44).height(44)
      .justifyContent(FlexAlign.Center)
      .alignItems(VerticalAlign.Center)
      .accessibilityText('更多操作')
      .onClick((): void => { this.openMenu(); })
    }
    .width('100%')
    .padding({ left: 14, right: 6, top: this.statusBarHeight + 2, bottom: 8 })
    .border({ width: { bottom: 1 }, color: { bottom: LINE } })
    .backgroundColor(BG)
  }

  @Builder
  centerHint(text: string, isError: boolean): void {
    Column() {
      Text(text)
        .fontSize(BODY)
        .fontColor(isError ? ERROR : INK3)
        .textAlign(TextAlign.Center)
      if (this.state.kind === 'error') {
        Text('重试')
          .fontSize(BODY)
          .fontColor(this.accentColorStore)
          .margin({ top: 14 })
          .padding({ left: 16, right: 16, top: 8, bottom: 8 })
          .onClick((): void => { this.loadApp(); })
      }
    }
    .width('100%').height('100%')
    .justifyContent(FlexAlign.Center)
    .alignItems(HorizontalAlign.Center)
  }

  @Builder
  miniAppWebView(app: MiniAppRecord): void {
    Web({ src: '', controller: this.controller })
      .javaScriptAccess(true)
      .domStorageAccess(false)
      .fileAccess(false)
      .onlineImageAccess(false)
      .mixedMode(MixedMode.None)
      .cacheMode(CacheMode.None)
      .zoomAccess(false)
      .textAutosizing(false)
      .javaScriptProxy({
        object: {
          postMessage: (raw: string): void => {
            this.onBridgeMessage(raw);
          },
        },
        name: 'AmberNative',
        methodList: ['postMessage'],
        controller: this.controller,
      })
      .onControllerAttached((): void => {
        this.attachBridge(app);
        if (this.shellHtml.length > 0) {
          this.controller.loadData(this.shellHtml, 'text/html', 'utf-8', MINI_APP_SHELL_BASE_URL);
        }
      })
      .onPageEnd((): void => {
        // 页面加载完成;此后 runJavaScript 才安全(emitEvent 由桥接实例驱动)
        this.prefetchImagesAndReload();
      })
      .onErrorReceive((event: OnErrorReceiveEvent): void => {
        const message: string = event.error !== null && event.error !== undefined
          ? String(event.error) : '小应用加载失败';
        this.state = { kind: 'error', message: message, app: this.state.app };
      })
      .onInterceptRequest((event: OnInterceptRequestEvent): WebResourceResponse | null => {
        const url: string = event.request.getRequestUrl();
        if (!url.startsWith('https://')) {
          // 非 https 子资源一律空响应(Android blockedResponse 语义)
          return this.blockedResponse();
        }
        const cached: WebResourceResponse | undefined = this.imageCache.get(url);
        if (cached !== undefined) return cached;
        // 未预取(未授权/失败/首轮加载)→ 空响应;refresh 后命中缓存
        return this.blockedResponse();
      })
      .width('100%')
      .layoutWeight(1)
  }

  @Builder
  sourceDialog(): void {
    if (this.sourceDialogOpen) {
      Stack() {
        Column() {
          Row() {
            Text('源码').fontSize(15).fontWeight(FontWeight.Medium).fontColor(INK).layoutWeight(1)
            Text('关闭')
              .fontSize(14).fontColor(this.accentColorStore)
              .padding({ left: 16, right: 16, top: 8, bottom: 8 })
              .onClick((): void => { this.sourceDialogOpen = false; })
          }
          .width('100%')
          Scroll() {
            Text(this.sourceTarget)
              .fontSize(11)
              .fontColor(INK2)
              .fontFamily('JetBrainsMono')
              .width('100%')
          }
          .layoutWeight(1)
          .align(Alignment.Top)
        }
        .width('88%').height('70%')
        .padding(16)
        .backgroundColor(SURFACE)
        .borderRadius(14)
        .border({ width: 1, color: LINE })
      }
      .width('100%').height('100%')
      .backgroundColor(OVERLAY)
      .onClick((): void => { this.sourceDialogOpen = false; })
    }
  }

  build(): void {
    Stack() {
      Column() {
        if (this.state.kind === 'loading') {
          this.centerHint('加载中…', false)
        } else if (this.state.kind === 'missing') {
          this.centerHint(this.state.message, false)
        } else if (this.state.kind === 'error') {
          this.centerHint(this.state.message, true)
        } else if (this.state.kind === 'ready' && this.state.app !== null) {
          Column() {
            this.topBar(this.state.app)
            this.miniAppWebView(this.state.app)
          }
          .width('100%').height('100%')
        }
      }
      .width('100%').height('100%')
      .backgroundColor(BG)

      this.sourceDialog()
    }
    .width('100%').height('100%')
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }
}
