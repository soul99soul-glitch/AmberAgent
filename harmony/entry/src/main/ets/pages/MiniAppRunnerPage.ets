// MiniAppRunnerPage — 小应用运行页(Harmony 首个 WebView 使用点)
//
// 状态机 Loading / Missing / Ready / Error(对齐 MiniAppRunnerPage.kt)。
// ArkWeb 装配:
//   Web 组件 javaScriptProxy({ object: AmberNative, name, methodList: ['postMessage'] })
//   onControllerAttached → registerJavaScriptProxy + loadData(shellHtml)
//   onInterceptRequest → https 图片(externalImages 权限 + grant 过 → 代理字节流)
//   onPageEnd 后可 runJavaScript(回传 _handleNativeResponse)
// shell = HAR injectMiniAppShell(html, BRIDGE_JS, 每次运行随机 token)
// 顶部返回栏 + 菜单(源码编辑[showSourceButton 门控]/导出)
//
// 源码编辑器(D2-2):编辑(mono TextArea + 行数指示)/ 预览(Web 直渲,无桥
//   即无原生能力)/ 保存(saveRevision 走既有 validateMiniAppHtml 校验管线,
//   成功后 loadApp 重新装配运行)。
// 偏差登记:行号/语法高亮未做 — TextArea 无滚动同步 API(官方 FAQ 确认),
//   富文本高亮编辑亦不可行,降级为行数指示 + mono 字体(检查面板语义由
//   保存时校验错误提示承担)。
//
// 图片代理实现说明:onInterceptRequest 为同步回调(component/web.d.ts
//   Callback<OnInterceptRequestEvent, WebResourceResponse>,不支持 Promise)。
//   采用「预取缓存 + refresh 重载」:onPageEnd 后异步预取 html 中全部 https
//   图片字节入缓存(externalImages 权限门),随后刷新页面,onInterceptRequest
//   命中缓存返回字节流;未授权/失败 → 空响应(Android blockedResponse 语义)。

import { router, promptAction, KeyboardAvoidMode } from '@kit.ArkUI';
import { Icon } from '../components/ui/Icon.ets';
import { webview } from '@kit.ArkWeb';
import { fileIo, picker } from '@kit.CoreFileKit';
import { http, connection } from '@kit.NetworkKit';
import { util } from '@kit.ArkTS';
import { hilog } from '@kit.PerformanceAnalysisKit';
import {
  BRIDGE_JS, injectMiniAppShell, MINI_APP_SHELL_BASE_URL, MiniAppUrlGuard,
  MINI_APP_MAX_IMAGE_BYTES, MINI_APP_IMAGE_ACCEPT_HEADER,
} from '@amber/chat-domain';
import type { MiniAppRecord, MiniAppGeneratedOutput } from '@amber/chat-domain';
import { ACCENT, BG, BODY, ERROR, INK, INK2, INK3, LINE, MOTION_OVERLAY, OVERLAY, SURFACE, TITLE } from '../design/tokens.ets';
import { FONT_MONO } from '../design/fonts.ets';
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

// 从 html 提取 https 图片 src(img 与 source 的 src 属性 — 校验器两者都放行,
// 只提 img 会让 <source src> 被拦截器阻断);数量上限防海量外链
const MAX_PREFETCH_IMAGE_URLS: number = 32;
// 并发上限 + 总字节预算:全量 Promise.all 会瞬时打满连接与内存
const PREFETCH_IMAGE_CONCURRENCY: number = 4;
const PREFETCH_IMAGE_TOTAL_BYTES: number = 8 * 1024 * 1024;

// holder:预取总字节预算 / 成功计数(闭包内可变)
interface PrefetchBudgetHolder {
  remainingBytes: number;
}

interface FetchedCountHolder {
  count: number;
}

interface BytesUsedHolder {
  bytes: number;
}

const extractHttpsImageUrls = (html: string): string[] => {
  const out: string[] = [];
  const pattern: RegExp = /<(?:img|source)\b[^>]*\bsrc\s*=\s*["'](https:\/\/[^"']+)["']/gi;
  let m: RegExpExecArray | null;
  while ((m = pattern.exec(html)) !== null) {
    if (m[1] !== undefined && out.indexOf(m[1]) < 0) out.push(m[1]);
    if (out.length >= MAX_PREFETCH_IMAGE_URLS) break;
  }
  return out;
};

@Entry
@Component
struct MiniAppRunnerPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @State state: MiniAppRunnerState = loadingState();
  @State shellHtml: string = '';
  @State sourceDialogOpen: boolean = false;
  // D2-2:源码编辑器状态
  @State sourceEdit: string = '';
  @State sourceTabPreview: boolean = false;
  @State sourceError: string = '';
  @State sourceSaving: boolean = false;
  @State sourcePreviewKey: number = 0;
  private sourcePreviewController: webview.WebviewController = new webview.WebviewController();
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @StorageProp('darkMode') darkMode: boolean = false;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  private appId: string = '';
  private platform: MiniAppPlatform | null = null;
  private bridge: MiniAppBridgeImpl | null = null;
  private controller: webview.WebviewController = new webview.WebviewController();
  private sessionToken: string = '';
  // 图片代理缓存(url → WebResourceResponse;同步 onInterceptRequest 消费)
  private imageCache: Map<string, WebResourceResponse> = new Map<string, WebResourceResponse>();
  private imageGuard: MiniAppUrlGuard = new MiniAppUrlGuard(miniAppDnsResolve);
  private prefetchStarted: boolean = false;
  // 离页守卫:预取完成等异步回调不再触碰已销毁的 controller/@State
  private alive: boolean = false;
  private previousKeyboardAvoidMode: KeyboardAvoidMode = KeyboardAvoidMode.OFFSET;
  private keyboardAvoidanceActive: boolean = false;

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

  onPageShow(): void {
    if (this.sourceDialogOpen) this.enableKeyboardAvoidance();
  }

  onPageHide(): void {
    this.restoreKeyboardAvoidance();
  }

  aboutToDisappear(): void {
    this.restoreKeyboardAvoidance();
    this.alive = false;
    if (this.bridge !== null) {
      this.bridge.close();
      this.bridge = null;
    }
    this.imageCache.clear();
  }

  private enableKeyboardAvoidance(): void {
    if (this.keyboardAvoidanceActive) return;
    this.previousKeyboardAvoidMode = this.getUIContext().getKeyboardAvoidMode();
    this.getUIContext().setKeyboardAvoidMode(KeyboardAvoidMode.RESIZE);
    this.keyboardAvoidanceActive = true;
  }

  private restoreKeyboardAvoidance(): void {
    if (!this.keyboardAvoidanceActive) return;
    this.getUIContext().setKeyboardAvoidMode(this.previousKeyboardAvoidMode);
    this.keyboardAvoidanceActive = false;
  }

  onBackPress(): boolean {
    if (!this.sourceDialogOpen) return false;
    this.dismissSourceDialog();
    return true;
  }

  private handleBack(): void {
    if (!this.onBackPress()) router.back();
  }

  private dismissSourceDialog(): void {
    if (this.sourceSaving) return;
    this.sourceDialogOpen = false;
    this.restoreKeyboardAvoidance();
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
    // 有界并发预取 + 总字节预算(剩余预算耗尽后未开始的 URL 直接置阻断响应);
    // 全部完成后才 refresh(此前 fetchedCount 必为 0,onInterceptRequest 只会
    // 返回空响应 — 并发链不被丢弃)
    const budget: PrefetchBudgetHolder = { remainingBytes: PREFETCH_IMAGE_TOTAL_BYTES };
    const fetched: FetchedCountHolder = { count: 0 };
    const tasks: Promise<boolean>[] = [];
    const queue: string[] = [...urls];
    const runWorker = async (): Promise<void> => {
      for (;;) {
        const next: string | undefined = queue.shift();
        if (next === undefined) return;
        // 预留单图上限再发请求(完成后按实际字节退还);预算不足覆盖单次
        // 最大响应时不再请求,直接置阻断响应 — 严格总预算上限
        if (budget.remainingBytes < MINI_APP_MAX_IMAGE_BYTES) {
          this.imageCache.set(next, this.blockedResponse());
          continue;
        }
        budget.remainingBytes -= MINI_APP_MAX_IMAGE_BYTES;
        const used: BytesUsedHolder = { bytes: 0 };
        const ok: boolean = await this.fetchAndCacheImage(next, used);
        if (ok) fetched.count++;
        // 按实际字节退还预留(失败请求全额退还;单图上限已限单次峰值)
        budget.remainingBytes += MINI_APP_MAX_IMAGE_BYTES - used.bytes;
      }
    };
    for (let i: number = 0; i < PREFETCH_IMAGE_CONCURRENCY; i++) {
      tasks.push(runWorker().then((): boolean => true));
    }
    Promise.all(tasks).then((): void => {
      if (!this.alive) return; // 离页后不再刷新已销毁的 controller
      if (fetched.count > 0) {
        // 刷新触发 onInterceptRequest 重放,命中缓存返回字节流;
        // 全部失败/阻断时不刷新(避免整页重载丢状态且无可用缓存)
        this.controller.refresh();
      }
    }).catch((e: Error): void => {
      hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'image prefetch failed: %{public}s', e.message);
    });
  }

  private async fetchAndCacheImage(url: string, used: BytesUsedHolder): Promise<boolean> {
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
      used.bytes = bytes.byteLength;
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
      showSource ? [buttons[0], buttons[1], buttons[2]] : [buttons[0], buttons[1]];
    promptAction.showActionMenu({
      title: app.title,
      buttons: menuButtons,
    }).then((res: promptAction.ActionMenuSuccessResponse): void => {
      const index: number = res.index;
      if (showSource && index === 0) {
        this.sourceEdit = app.htmlContent;
        this.sourceError = '';
        this.sourceTabPreview = false;
        this.enableKeyboardAvoidance();
        this.sourceDialogOpen = true;
      } else if ((showSource && index === 1) || (!showSource && index === 0)) {
        this.exportHtml(app);
      }
    }).catch((): void => {});
  }

  // D2-2:保存源码编辑 — 走 saveRevision 既有校验管线(validateMiniAppHtml
  //   在仓储内执行,失败抛错入 sourceError),成功后 loadApp 重新装配运行
  private async saveSourceEdit(): Promise<void> {
    const app: MiniAppRecord | null = this.state.app;
    if (app === null || this.sourceSaving) return;
    const platform: MiniAppPlatform | null = this.platform;
    if (platform === null) {
      this.sourceError = '平台未就绪';
      return;
    }
    this.sourceSaving = true;
    this.sourceError = '';
    try {
      const output: MiniAppGeneratedOutput = {
        title: app.title,
        description: app.description,
        icon: app.iconEmoji,
        category: app.category ?? '',
        permissions: app.permissions,
        html: this.sourceEdit,
      };
      const updated: MiniAppRecord | null =
        await platform.repository.saveRevision(app.id, output, null, null, '手动编辑源码');
      if (updated === null) throw new Error('保存失败(记录不存在)');
      this.sourceDialogOpen = false;
      this.restoreKeyboardAvoidance();
      this.loadApp();
      promptAction.showToast({ message: '已保存并重新运行', duration: 1500 });
    } catch (e) {
      const msg: string = e instanceof Error ? e.message : String(e);
      this.sourceError = msg;
    } finally {
      this.sourceSaving = false;
    }
  }

  private sourceLineCount(): number {
    if (this.sourceEdit.length === 0) return 0;
    return this.sourceEdit.split('\n').length;
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
        Icon({ name: 'back', iconSize: 20, color: INK })
      }
      .width(44).height(44)
      .justifyContent(FlexAlign.Center)
      .alignItems(VerticalAlign.Center)
      .accessibilityText('返回')
      .onClick((): void => { this.handleBack(); })

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
            Text('源码编辑').fontSize(15).fontWeight(FontWeight.Medium).fontColor(INK).layoutWeight(1)
            Text(this.sourceTabPreview ? '编辑' : '预览')
              .fontSize(14).fontColor(this.accentColorStore)
              .padding({ left: 12, right: 12, top: 8, bottom: 8 })
              .onClick((): void => {
                this.sourceTabPreview = !this.sourceTabPreview;
                if (this.sourceTabPreview) this.sourcePreviewKey++;
              })
            Text('保存')
              .fontSize(14).fontColor(this.sourceSaving ? INK3 : this.accentColorStore)
              .padding({ left: 12, right: 12, top: 8, bottom: 8 })
              .onClick((): void => { this.saveSourceEdit().catch((): void => {}); })
            Text('关闭')
              .fontSize(14).fontColor(this.sourceSaving ? INK3 : this.accentColorStore)
              .padding({ left: 12, right: 4, top: 8, bottom: 8 })
              .onClick((): void => { this.dismissSourceDialog(); })
          }
          .width('100%')
          if (this.sourceError.length > 0) {
            Text(this.sourceError)
              .fontSize(11).fontColor(ERROR)
              .width('100%')
              .maxLines(3).textOverflow({ overflow: TextOverflow.Ellipsis })
              .padding({ top: 4, bottom: 4 })
          }
          if (this.sourceTabPreview) {
            // 预览:无桥 Web 直渲(无原生能力;JS 放行 — 与运行同级信任,
            //   保存时仍经 validateMiniAppHtml 校验)
            Web({ src: 'about:blank', controller: this.sourcePreviewController })
              .key(`source-preview-${this.sourcePreviewKey}`)
              .width('100%').layoutWeight(1)
              .backgroundColor(SURFACE)
              .javaScriptAccess(true)
              .onControllerAttached((): void => {
                this.sourcePreviewController.loadData(
                  this.sourceEdit, 'text/html', 'utf-8', MINI_APP_SHELL_BASE_URL);
              })
          } else {
            Text(`共 ${this.sourceLineCount()} 行`)
              .fontSize(11).fontColor(INK3)
              .width('100%')
              .padding({ top: 2, bottom: 4 })
            TextArea({ text: this.sourceEdit, placeholder: '' })
              .enabled(!this.sourceSaving)
              .fontSize(11)
              .fontColor(INK)
              .fontFamily(FONT_MONO)
              .backgroundColor(Color.Transparent)
              .width('100%')
              .layoutWeight(1)
              .onChange((v: string): void => { this.sourceEdit = v; })
          }
        }
        .width('94%').height('86%')
        .padding(16)
        .backgroundColor(SURFACE)
        .borderRadius(14)
        .border({ width: 1, color: LINE })
        .onClick((): void => {})
        .transition(TransitionEffect.scale({ x: 0.96, y: 0.96 })
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      }
      .width('100%').height('100%')
      .backgroundColor(OVERLAY)
      .onClick((): void => { this.dismissSourceDialog(); })
      .transition(TransitionEffect.OPACITY
        .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
    }
  }

  build(): void {
    Stack() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
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
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%')
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }
}
