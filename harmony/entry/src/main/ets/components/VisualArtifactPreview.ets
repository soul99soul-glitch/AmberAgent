// Trusted local renderer; generated HTML remains in an iframe without script permission.
import { webview } from '@kit.ArkWeb';
import { util } from '@kit.ArkTS';
import { sanitizeWidgetHtml, widgetDocument } from '@amber/chat-domain';
import type { ThreeSceneSpec } from '@amber/chat-domain';
import { ACCENT, INK3, LABEL, SURFACE2 } from '../design/tokens.ets';
import { DotGround } from './ui/DotMatrix.ets';
import { DotLoading } from './ui/DotLoading.ets';
import { activateArtifactInteraction, clearArtifactInteraction } from '../platform_impl/ArtifactInteraction.ets';
import { UIGenerationMotion } from '../platform_impl/UIGenerationMotion.ets';

const MAX_VALIDATION_SVG_CHARS: number = 120000;

interface VisualRenderInfo {
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
}

@Component
export struct VisualArtifactPreview {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('darkMode') @Watch('requestRender') darkMode: boolean = false;
  @StorageProp('accentColor') accent: string = ACCENT;
  @StorageProp('appBackgrounded') @Watch('updateActivity') backgrounded: boolean = false;
  @StorageProp('reduceMotion') @Watch('motionPreferenceChanged') reduceMotion: boolean = false;
  @StorageLink('activeArtifactInteraction') @Watch('syncInteraction') activeInteraction: string = '';
  @Prop @Watch('requestRender') html: string = '';
  @Prop @Watch('requestRender') renderer: string = 'html';
  @Prop @Watch('requestRender') scene: ThreeSceneSpec | null = null;
  @Prop @Watch('requestRender') complete: boolean = false;
  @Prop @Watch('requestRender') strictSvg: boolean = false;
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

  private isPassive(): boolean {
    return this.renderer !== 'threejs' || !this.interactiveReady;
  }

  private isInteracting(): boolean { return this.activeInteraction === this.interactionOwner; }

  private hostKind(): string { return this.renderer === 'threejs' ? 'three' : 'svg'; }

  private webSource(): Resource {
    return this.renderer === 'threejs' ? $rawfile('visual/host.html') : $rawfile('visual/svg-host.html');
  }

  private detachHost(releaseProxy: boolean = false): void {
    this.motion.cancel();
    this.renderedRevision = -1;
    if (this.attached) {
      this.controller.runJavaScript('window.AmberVisual.dispose()').catch((): void => {});
      if (releaseProxy) this.controller.deleteJavaScriptRegister('AmberVisualHost');
    }
    this.attached = false;
    this.mountedHost = '';
  }

  private activateInteraction(): void {
    if (this.interactiveReady && this.hasContent && this.canvasArea !== null) {
      activateArtifactInteraction(this.interactionOwner, this.canvasArea);
    }
  }

  private syncInteraction(): void {
    if (!this.alive || !this.attached) return;
    this.controller.runJavaScript(`window.AmberVisual.setInteracting(${this.isInteracting() && this.interactiveReady})`)
      .catch((): void => {});
  }

  aboutToAppear(): void { this.alive = true; }

  aboutToDisappear(): void {
    clearArtifactInteraction(this.interactionOwner);
    this.alive = false;
    this.interactiveReady = false;
    if (this.timer !== -1) clearTimeout(this.timer);
    this.timer = -1;
    this.detachHost(true);
  }

  private requestRender(): void {
    this.motion.cancel();
    const nextHost: string = this.hostKind();
    if (this.mountedHost.length > 0 && this.mountedHost !== nextHost) {
      this.detachHost();
      this.hasContent = false;
      this.renderError = '';
      this.interactiveReady = false;
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

  private renderNow(): void {
    if (!this.alive || !this.attached) return;
    const revision: number = this.revision;
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
      };
      const json: string = JSON.stringify(payload).replace(/\u2028/g, '\\u2028').replace(/\u2029/g, '\\u2029');
      this.controller.runJavaScript(`window.AmberVisual.update(${json});` +
        `window.AmberVisual.setInteracting(${this.isInteracting() && this.interactiveReady});`).catch((error: Error): void => {
        if (this.alive && revision === this.revision) {
          this.renderError = error.message;
          this.interactiveReady = false;
          clearArtifactInteraction(this.interactionOwner);
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
    if (!this.alive || !this.attached) return;
    this.controller.runJavaScript(`window.AmberVisual.setActive(${this.visible && !this.backgrounded})`)
      .catch((): void => {});
    if (this.visible && !this.backgrounded) this.requestRender();
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
      this.renderError = info.ok ? '' : info.error;
      this.renderedRevision = info.revision;
      this.hasContent = info.content;
      this.interactiveReady = this.renderer === 'threejs' && info.ok;
      if (!this.interactiveReady && this.isInteracting()) clearArtifactInteraction(this.interactionOwner);
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
      Scroll() {
        Stack() {
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
              object: { postMessage: (raw: string): void => { this.hostMessage(raw); } },
              name: 'AmberVisualHost', methodList: ['postMessage'], controller: this.controller,
            })
            .onPageEnd((): void => {
              this.attached = true;
              this.mountedHost = this.hostKind();
              this.interactiveReady = false;
              this.renderNow();
              this.updateActivity();
            })
            .onErrorReceive((event: OnErrorReceiveEvent): void => {
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
              if (this.strictSvg) this.onValidation(false, message, '');
            })
            .onOverrideUrlLoading((request: WebResourceRequest): boolean => {
              const url: string = request.getRequestUrl();
              return request.isRequestGesture() || !(url.startsWith('resource://rawfile/visual/') ||
                url === 'about:srcdoc' || url === 'about:blank');
            })
          if (this.interactiveReady && !this.isInteracting()) {
            Column() {}
              .width('100%').height(this.renderedHeight)
              .onClick((): void => { this.activateInteraction(); })
              .accessibilityText('点按开始操控三维画布，上下滑动浏览聊天')
          }
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
      if (this.interactiveReady) {
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
