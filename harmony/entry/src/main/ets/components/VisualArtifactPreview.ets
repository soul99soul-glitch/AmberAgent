// Trusted local renderer; generated HTML remains in an iframe without script permission.
import { webview } from '@kit.ArkWeb';
import { util } from '@kit.ArkTS';
import { sanitizeWidgetHtml, widgetDocument } from '@amber/chat-domain';
import type { ThreeSceneSpec } from '@amber/chat-domain';
import { ACCENT, INK3, LABEL, SURFACE2 } from '../design/tokens.ets';
import { DotGround } from './ui/DotMatrix.ets';
import { DotLoading } from './ui/DotLoading.ets';
import { NativeThreeCover } from './NativeThreeCover.ets';
import { activateArtifactInteraction, clearArtifactInteraction } from '../platform_impl/ArtifactInteraction.ets';
import { UIGenerationMotion } from '../platform_impl/UIGenerationMotion.ets';
import { threePreviewKey, readThreePreview, writeThreePreview } from '../platform_impl/ThreePreviewCache.ets';

const MAX_VALIDATION_SVG_CHARS: number = 120000;

interface VisualRenderInfo {
  kind?: string;
  revision: number;
  height: number;
  ok: boolean;
  content: boolean;
  error: string;
  validationRevision: number;
  validationOk: boolean;
  validationError: string;
  validationSvg: string;
}

interface VisualRenderPayload {
  document: string;
  rawSVG: string;
  renderer: string;
  scene: ThreeSceneSpec | null;
  dark: boolean;
  complete: boolean;
  strictSvg: boolean;
  revision: number;
  view: string;
}

interface ThreePreviewFrame {
  snapshot: string;
  view: string;
}

@Component
export struct VisualArtifactPreview {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('darkMode') @Watch('sourceChanged') darkMode: boolean = false;
  @StorageProp('accentColor') accent: string = ACCENT;
  @StorageProp('appBackgrounded') @Watch('updateActivity') backgrounded: boolean = false;
  @StorageProp('reduceMotion') @Watch('motionPreferenceChanged') reduceMotion: boolean = false;
  @StorageLink('activeArtifactInteraction') @Watch('syncInteraction') activeInteraction: string = '';
  @StorageLink('threePreviewOwner') @Watch('syncPreview') previewOwner: string = '';
  @StorageProp('threePreviewScrolling') @Watch('syncPreview') previewScrolling: boolean = false;
  @Prop @Watch('sourceChanged') html: string = '';
  @Prop @Watch('sourceChanged') renderer: string = 'html';
  @Prop @Watch('sourceChanged') scene: ThreeSceneSpec | null = null;
  @Prop @Watch('sourceChanged') complete: boolean = false;
  @Prop @Watch('sourceChanged') strictSvg: boolean = false;
  @Prop @Watch('requestReveal') revealKey: string = '';
  @Prop @Watch('requestReveal') revealEligible: boolean = false;
  onContentGrow: () => void = (): void => {};
  onValidation: (ok: boolean, error: string, canonicalSvg: string) => void =
    (_ok: boolean, _error: string, _canonicalSvg: string): void => {};
  @State renderedHeight: number = 180;
  @State hasContent: boolean = false;
  @State renderError: string = '';
  @State interactiveReady: boolean = false;
  @State expanded: boolean = false;
  @State threeSnapshot: string = '';
  private controller: webview.WebviewController = new webview.WebviewController();
  private motion: UIGenerationMotion = new UIGenerationMotion(this.controller);
  private renderedRevision: number = -1;
  private attached: boolean = false;
  private alive: boolean = false;
  private visible: boolean = true;
  private revision: number = 0;
  private timer: number = -1;
  private interactionOwner: string = util.generateRandomUUID(false);
  private canvasArea: Area | null = null;
  private mountedHost: string = '';
  private requestedHost: string = '';
  private snapshotRevision: number = -1;
  private coverKey: string = '';
  private coverSource: string = '';
  private coverLoaded: boolean = false;
  private coverLoading: Promise<void> | null = null;
  private coverAttempted: boolean = false;
  private coverTimer: number = -1;
  private cameraView: string = '';

  private isPassive(): boolean {
    return this.renderer !== 'threejs' || !this.interactiveReady;
  }

  private isInteracting(): boolean { return this.activeInteraction === this.interactionOwner; }

  private mountsThree(): boolean {
    return this.isInteracting() || this.previewOwner === this.interactionOwner;
  }

  private releasePreview(): void {
    if (this.previewOwner === this.interactionOwner) {
      if (!this.isInteracting()) {
        this.detachHost(true);
        this.interactiveReady = false;
      }
      AppStorage.setOrCreate<string>('threePreviewOwner', '');
    }
  }

  private async loadCover(): Promise<void> {
    if (this.coverLoaded || this.scene === null) return;
    if (this.coverLoading !== null) return this.coverLoading;
    const source: string = this.coverSource;
    const scene: ThreeSceneSpec = this.scene;
    const dark: boolean = this.darkMode;
    const loading: Promise<void> = (async (): Promise<void> => {
      try {
        const key: string = await threePreviewKey(scene, dark);
        const cached = await readThreePreview(key);
        if (!this.alive || source !== this.coverSource) return;
        this.coverKey = key;
        this.coverLoaded = true;
        if (cached !== null) {
          this.threeSnapshot = cached.snapshot;
          this.cameraView = cached.view;
        }
      } catch (error) {
        console.warn(`[ThreePreview] Cache read failed: ${(error as Error).message}`);
      } finally {
        if (source === this.coverSource) this.coverLoading = null;
      }
    })();
    this.coverLoading = loading;
    return loading;
  }

  private syncPreview(): void {
    if (this.coverTimer !== -1) clearTimeout(this.coverTimer);
    this.coverTimer = -1;
    const canPrepare: boolean = this.alive && this.renderer === 'threejs' && this.visible &&
      !this.backgrounded && !this.previewScrolling && this.activeInteraction.length === 0;
    if (!canPrepare) {
      if (this.threeSnapshot.length === 0) this.coverAttempted = false;
      this.releasePreview();
    }
    if (this.renderer === 'threejs' && !this.mountsThree() && this.attached) {
      this.detachHost(true);
      this.interactiveReady = false;
    }
    if (!canPrepare || this.threeSnapshot.length > 0 || this.coverAttempted ||
      this.scene === null || this.scene.objects.length === 0 || this.previewOwner.length > 0) return;
    const source: string = this.coverSource;
    this.coverTimer = setTimeout((): void => {
      this.coverTimer = -1;
      this.loadCover().then((): void => {
        if (!this.alive || source !== this.coverSource || this.renderer !== 'threejs' ||
          this.scene === null || this.scene.objects.length === 0 || !this.visible || this.backgrounded || this.previewScrolling ||
          this.activeInteraction.length > 0 || this.previewOwner.length > 0 ||
          this.threeSnapshot.length > 0 || this.coverAttempted) return;
        this.coverAttempted = true;
        this.interactionOwner = util.generateRandomUUID(false);
        AppStorage.setOrCreate<string>('threePreviewOwner', this.interactionOwner);
      });
    }, 200);
  }

  private hostKind(): string { return this.renderer === 'threejs' ? 'three' : 'svg'; }

  private webSource(): Resource {
    return this.renderer === 'threejs' ? $rawfile('visual/host.html') : $rawfile('visual/svg-host.html');
  }

  private detachHost(releaseProxy: boolean = false): void {
    this.motion.cancel();
    this.revision++;
    if (this.timer !== -1) clearTimeout(this.timer);
    this.timer = -1;
    this.renderedRevision = -1;
    if (this.attached) {
      this.controller.runJavaScript('window.AmberVisual.dispose()').catch((): void => {});
      if (releaseProxy) this.controller.deleteJavaScriptRegister('AmberVisualHost');
    }
    this.attached = false;
    this.mountedHost = '';
  }

  private async activateInteraction(): Promise<void> {
    if (this.isInteracting()) return;
    if (this.renderer === 'threejs' && this.scene !== null && this.scene.objects.length > 0 &&
      this.canvasArea !== null) {
      const source: string = this.coverSource;
      await this.loadCover();
      if (!this.alive || source !== this.coverSource || this.renderer !== 'threejs' ||
        this.scene === null || this.scene.objects.length === 0 || !this.visible || this.backgrounded || this.canvasArea === null) return;
      this.renderError = '';
      if (!this.mountsThree()) this.interactionOwner = util.generateRandomUUID(false);
      activateArtifactInteraction(this.interactionOwner, this.canvasArea);
    }
  }

  private syncInteraction(): void {
    if (!this.alive) return;
    this.syncPreview();
    if (this.renderer === 'threejs' && !this.mountsThree()) {
      this.detachHost(true);
      this.interactiveReady = false;
      return;
    }
    if (!this.attached) return;
    this.controller.runJavaScript(`window.AmberVisual.setInteracting(${this.isInteracting() && this.interactiveReady})`)
      .catch((): void => {});
  }

  aboutToAppear(): void {
    this.alive = true;
    if (this.renderer === 'threejs') {
      this.visible = false;
      this.sourceChanged();
    }
    this.requestedHost = this.hostKind();
  }

  aboutToDisappear(): void {
    this.alive = false;
    clearArtifactInteraction(this.interactionOwner);
    this.releasePreview();
    if (this.coverTimer !== -1) clearTimeout(this.coverTimer);
    this.coverTimer = -1;
    this.interactiveReady = false;
    if (this.timer !== -1) clearTimeout(this.timer);
    this.timer = -1;
    this.detachHost(true);
  }

  private sourceChanged(): void {
    const source: string = this.renderer === 'threejs' ? `${this.darkMode}:${JSON.stringify(this.scene)}` : '';
    if (source.length > 0 && source === this.coverSource) return;
    this.coverSource = source;
    this.coverKey = '';
    this.coverLoaded = false;
    this.coverLoading = null;
    this.coverAttempted = false;
    this.cameraView = '';
    this.threeSnapshot = '';
    this.snapshotRevision = -1;
    if (this.renderer === 'threejs' && !this.isInteracting()) {
      this.releasePreview();
      this.detachHost(true);
      this.interactiveReady = false;
      this.renderedHeight = 280;
    }
    this.requestRender();
    this.syncPreview();
  }

  private captureThreeSnapshot(revision: number, refresh: boolean = false): void {
    if (!refresh && this.snapshotRevision === revision) return;
    this.snapshotRevision = revision;
    const key: string = this.coverKey;
    this.controller.runJavaScript('JSON.stringify(window.AmberThree.captureFrame())').then((result: string): void => {
      if (!this.alive || revision !== this.revision) return;
      const json: string = result.startsWith('"') ? JSON.parse(result) as string : result;
      const frame: ThreePreviewFrame = JSON.parse(json) as ThreePreviewFrame;
      const snapshot: string = frame.snapshot;
      if (snapshot.startsWith('data:image/png;base64,') && snapshot.length <= 2 * 1024 * 1024) {
        this.threeSnapshot = snapshot;
        this.cameraView = frame.view;
        this.interactiveReady = true;
        this.syncInteraction();
        if (key.length > 0) writeThreePreview(key, snapshot, frame.view).catch((error: Error): void => {
          console.warn(`[ThreePreview] Cache write failed: ${error.message}`);
        });
      }
      if (!this.isInteracting()) this.releasePreview();
    }).catch((error: Error): void => {
      console.warn(`[ThreePreview] Capture failed: ${error.message}`);
      if (revision === this.revision) this.releasePreview();
    });
  }

  private requestRender(): void {
    this.motion.cancel();
    const nextHost: string = this.hostKind();
    if (this.mountedHost.length > 0 && this.mountedHost !== nextHost) {
      this.detachHost();
      this.requestedHost = nextHost;
      this.hasContent = false;
      this.renderError = '';
      this.interactiveReady = false;
      try {
        this.controller.loadUrl(this.webSource());
      } catch (_) {}
    }
    if (this.renderer !== 'threejs') clearArtifactInteraction(this.interactionOwner);
    this.revision++;
    if (!this.alive || !this.attached || this.timer !== -1) return;
    // A throttle consumes the latest snapshot even when model deltas never pause.
    this.timer = setTimeout((): void => {
      this.timer = -1;
      this.renderNow();
    }, 180);
  }

  private async renderNow(): Promise<void> {
    if (!this.alive || !this.attached || (this.renderer === 'threejs' && !this.mountsThree())) return;
    const revision: number = this.revision;
    if (this.renderer === 'threejs') {
      await this.loadCover();
      if (!this.alive || !this.attached || revision !== this.revision || !this.mountsThree()) return;
    }
    try {
      const document: string = widgetDocument(this.html, this.darkMode, this.renderer === 'miniapp');
      const payload: VisualRenderPayload = {
        document,
        rawSVG: this.strictSvg && this.renderer !== 'threejs' ? sanitizeWidgetHtml(this.html) : '',
        renderer: this.renderer,
        scene: this.scene,
        dark: this.darkMode,
        complete: this.complete,
        strictSvg: this.strictSvg,
        revision: revision,
        view: this.renderedRevision === -1 ? this.cameraView : '',
      };
      const json: string = JSON.stringify(payload).replace(/\u2028/g, '\\u2028').replace(/\u2029/g, '\\u2029');
      this.controller.runJavaScript(`window.AmberVisual.update(${json});` +
        `window.AmberVisual.setInteracting(${this.isInteracting() && this.interactiveReady});`).catch((error: Error): void => {
        if (this.alive && revision === this.revision) {
          this.renderError = error.message;
          this.interactiveReady = false;
          clearArtifactInteraction(this.interactionOwner);
          this.releasePreview();
          if (this.strictSvg) this.onValidation(false, error.message, '');
        }
      });

    } catch (error) {
      this.renderError = (error as Error).message;
      if (this.strictSvg) this.onValidation(false, this.renderError, '');
    }
  }

  private updateActivity(): void {
    if (!this.visible || this.backgrounded) this.motion.cancel();
    if (!this.visible || this.backgrounded) clearArtifactInteraction(this.interactionOwner);
    this.syncPreview();
    if (!this.alive || !this.attached) return;
    this.controller.runJavaScript(`window.AmberVisual.setActive(${this.visible && !this.backgrounded})`)
      .catch((): void => {});
    if (this.visible && !this.backgrounded && this.renderedRevision !== this.revision) {
      if (this.timer !== -1) clearTimeout(this.timer);
      this.timer = -1;
      this.renderNow();
    }
    this.requestReveal();
  }

  private motionPreferenceChanged(): void {
    if (this.reduceMotion) this.motion.cancel(true);
    this.requestReveal();
  }

  private requestReveal(): void {
    if (!this.alive || !this.attached || !this.visible || this.backgrounded || !this.hasContent ||
      this.renderError.length > 0 || this.renderedRevision !== this.revision ||
      !this.complete || this.renderer === 'threejs') return;
    // The compact preview clips at 280vp; do not draw a full-document overlay behind that clip.
    this.motion.play(this.revealKey, this.revealEligible && this.revealKey.length > 0,
      this.reduceMotion || this.renderedHeight > 280, true);
  }

  private hostMessage(raw: string): void {
    if (!this.alive || raw.length > (this.strictSvg ? MAX_VALIDATION_SVG_CHARS * 2 + 2048 : 2048)) return;
    try {
      const info: VisualRenderInfo = JSON.parse(raw) as VisualRenderInfo;
      if (info.revision !== this.revision) return;
      if (info.kind === 'three-frame') {
        this.captureThreeSnapshot(info.revision, true);
        return;
      }
      this.renderError = info.ok ? '' : info.error;
      this.renderedRevision = info.revision;
      this.hasContent = info.content;
      const threeReady: boolean = this.renderer === 'threejs' && info.ok && info.content;
      if (threeReady) {
        this.captureThreeSnapshot(info.revision);
      }
      if (!info.ok) {
        this.interactiveReady = false;
        clearArtifactInteraction(this.interactionOwner);
        this.releasePreview();
      }
      if (this.strictSvg && info.validationRevision === info.revision && info.validationRevision === this.revision) {
        let validationOk: boolean = info.validationOk;
        let validationError: string = info.validationError || info.error;
        let canonicalSvg: string = '';
        if (validationOk) {
          if (typeof info.validationSvg !== 'string' || info.validationSvg.length === 0 ||
            info.validationSvg.length > MAX_VALIDATION_SVG_CHARS) {
            validationOk = false;
            validationError = 'SVG 规范化结果不可导出';
          } else canonicalSvg = info.validationSvg;
        }
        this.onValidation(validationOk, validationError, canonicalSvg);
      }
      if (Number.isFinite(info.height) && info.height > 0) {
        const height: number = Math.min(10000, Math.max(120, Math.ceil(info.height)));
        if (height !== this.renderedHeight) {
          this.renderedHeight = height;
          this.onContentGrow();
        }
      }
      this.requestReveal();
    } catch (_) { this.renderError = '预览状态无法读取'; }
  }

  build(): void {
    Column() {
      Stack() {
      if (this.renderer !== 'threejs' || this.mountsThree()) {
        Scroll() {
          Stack() {
            ForEach([this.interactionOwner], (hostOwner: string): void => {
              Web({ src: this.webSource(), controller: this.controller })
                .width('100%').height(this.renderedHeight)
                .backgroundColor(Color.Transparent)
                .javaScriptAccess(true)
                .domStorageAccess(false)
                .multiWindowAccess(false)
                .fileAccess(false)
                .onlineImageAccess(false)
                .verticalScrollBarAccess(false)
                .horizontalScrollBarAccess(false)
                .hitTestBehavior(this.isPassive() || (this.renderer === 'threejs' && !this.isInteracting())
                  ? HitTestMode.None : HitTestMode.Default)
                .nestedScroll({ scrollForward: this.isInteracting() ? NestedScrollMode.SELF_ONLY : NestedScrollMode.PARENT_FIRST,
                  scrollBackward: this.isInteracting() ? NestedScrollMode.SELF_ONLY : NestedScrollMode.PARENT_FIRST })
                .overScrollMode(OverScrollMode.NEVER)
                .darkMode(this.darkMode ? WebDarkMode.On : WebDarkMode.Off)
                .forceDarkAccess(false)
                .javaScriptProxy({
                  object: { postMessage: (raw: string): void => {
                    if (hostOwner === this.interactionOwner) this.hostMessage(raw);
                  } },
                  name: 'AmberVisualHost', methodList: ['postMessage'], controller: this.controller,
                })
                .onPageEnd((): void => {
                  if (hostOwner !== this.interactionOwner || !this.alive ||
                    (this.renderer === 'threejs' && !this.mountsThree())) return;
                  const targetHost: string = this.hostKind();
                  if (this.requestedHost.length > 0 && this.requestedHost !== targetHost) {
                    this.requestedHost = targetHost;
                    try {
                      this.controller.loadUrl(this.webSource());
                    } catch (_) {}
                    return;
                  }
                  this.requestedHost = targetHost;
                  this.attached = true;
                  this.mountedHost = targetHost;
                  this.interactiveReady = false;
                  this.updateActivity();
                  if (!this.visible || this.backgrounded) this.renderNow();
                })
                .onErrorReceive((event: OnErrorReceiveEvent): void => {
                  if (hostOwner !== this.interactionOwner || !this.alive) return;
                  this.motion.cancel();
                  this.renderedRevision = -1;
                  const message: string = event.error !== null && event.error !== undefined
                    ? String(event.error) : '预览宿主加载失败';
                  this.attached = false;
                  this.mountedHost = '';
                  this.renderError = message;
                  this.hasContent = false;
                  this.interactiveReady = false;
                  clearArtifactInteraction(this.interactionOwner);
                  this.releasePreview();
                  if (this.strictSvg) this.onValidation(false, message, '');
                })
                .onOverrideUrlLoading((request: WebResourceRequest): boolean => {
                  const url: string = request.getRequestUrl();
                  return request.isRequestGesture() || !(url.startsWith('resource://rawfile/visual/') ||
                    url === 'about:srcdoc' || url === 'about:blank');
                })
            }, (hostOwner: string): string => hostOwner)
            if (!this.hasContent && this.renderError.length === 0 && !this.complete) {
              Stack() {
                DotGround()
                DotLoading({ color: this.accent, dotSize: 5 })
              }
              .width('100%').height(180).backgroundColor(SURFACE2)
            }
          }
          .width('100%')
          .border({ width: 1, color: this.isInteracting() ? this.accent : Color.Transparent })
        }
        .width('100%').height(Math.min(this.expanded ? (this.isPassive() ? 10000 : 600) : 280, this.renderedHeight))
        .hitTestBehavior(this.isPassive() ? HitTestMode.None : HitTestMode.Default)
        .scrollBar(this.isPassive() ? BarState.Off : BarState.Auto)
        .nestedScroll({ scrollForward: this.isInteracting() ? NestedScrollMode.SELF_ONLY : NestedScrollMode.PARENT_FIRST,
          scrollBackward: this.isInteracting() ? NestedScrollMode.SELF_ONLY : NestedScrollMode.PARENT_FIRST })
      }
      if (this.renderer === 'threejs') {
        NativeThreeCover({ scene: this.scene, dark: this.darkMode, snapshot: this.threeSnapshot })
          .visibility(this.isInteracting() && this.interactiveReady ? Visibility.Hidden : Visibility.Visible)
          .onClick((): void => { this.activateInteraction(); })
          .accessibilityText('点按开始操控三维画布，上下滑动浏览聊天')
      }
      }
      .width('100%')
      if (this.renderer === 'threejs' && this.scene !== null && this.scene.objects.length > 0) {
        Text(this.isInteracting() ? '操控中 · 退出操控' : '点按画布开始操控').fontSize(LABEL).fontColor(this.accent)
          .height(44).width('100%').textAlign(TextAlign.Center)
          .onClick((): void => {
            if (this.isInteracting()) clearArtifactInteraction(this.interactionOwner);
            else this.activateInteraction();
          })
      }
      if (this.renderedHeight > 280) {
        Text(this.expanded ? '收起预览' : '展开预览').fontSize(LABEL).fontColor(this.accent)
          .height(44).width('100%').textAlign(TextAlign.Center)
          .onClick((): void => { this.expanded = !this.expanded; })
          .accessibilityText(this.expanded ? '收起生成内容预览' : '展开生成内容预览')
      }
      if (this.renderError.length > 0) {
        Text(this.renderError).fontSize(LABEL).fontColor(INK3).width('100%').padding(10)
      }
    }
    .opacity(this.themeEpoch >= 0 ? 1 : 1).width('100%').clip(true)
    .onTouch((event: TouchEvent): void => {
      if (event.type === TouchType.Down) this.motion.cancel(true);
    })
    .onAreaChange((_old: Area, area: Area): void => {
      // Keep the exit strip inside the focused area so outside-touch reset cannot re-arm its toggle.
      this.canvasArea = area;
      if (this.isInteracting()) activateArtifactInteraction(this.interactionOwner, area);
    })
    .onVisibleAreaChange([0, 1], (_expanding: boolean, ratio: number): void => {
      this.visible = ratio > 0;
      this.updateActivity();
    })
  }
}
