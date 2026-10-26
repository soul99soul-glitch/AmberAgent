// 聊天图片、Markdown 图片与生成图共用的全屏预览。
import { common } from '@kit.AbilityKit';
import { newId } from '@amber/chat-domain';
import { saveImageToAlbum } from '../../platform_impl/ImageSaveSupport.ets';
import { shareImage } from '../../platform_impl/ImageShareSupport.ets';
import { cachedMediaUrl, cachedMediaUrlIfPresent } from '../../platform_impl/MediaCache.ets';
import { BG } from '../../design/tokens.ets';

interface ImagePreviewLoadEvent {
  width: number;
  height: number;
  componentWidth: number;
  componentHeight: number;
  loadingStatus: number;
  contentWidth: number;
  contentHeight: number;
  contentOffsetX: number;
  contentOffsetY: number;
}

@Component
export struct ImagePreviewDialog {
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @Prop @Watch('onImagesChanged') images: string[] = [];
  @Prop @Watch('onShowChanged') show: boolean = true;
  @Prop initialIndex: number = 0;
  @State currentIndex: number = 0;
  @State private sources: string[] = [];
  @State imageScale: number = 1;
  @State imageOffsetX: number = 0;
  @State imageOffsetY: number = 0;
  @State saving: boolean = false;
  @State sharing: boolean = false;
  private pinchStartScale: number = 1;
  private panLastOffsetX: number = 0;
  private panLastOffsetY: number = 0;
  private viewportWidth: number = 0;
  private viewportHeight: number = 0;
  private imageWidth: number = 0;
  private imageHeight: number = 0;
  private imageSizeUrl: string = '';
  private shareAnchorId: string = `image-preview-share-${newId()}`;
  private alive: boolean = true;
  private sourceEpoch: number = 0;
  onDismiss: () => void = (): void => {};

  aboutToAppear(): void {
    this.alive = true;
    this.currentIndex = Math.max(0, Math.min(this.images.length - 1, this.initialIndex));
    this.resetSources();
    this.resolveSources().catch((): void => {});
  }

  aboutToDisappear(): void { this.alive = false; }

  private onImagesChanged(): void {
    this.currentIndex = Math.max(0, Math.min(this.images.length - 1, this.currentIndex));
    this.resetSources();
    this.resolveSources().catch((): void => {});
  }

  private onShowChanged(): void {
    if (!this.show) return;
    this.currentIndex = Math.max(0, Math.min(this.images.length - 1, this.initialIndex));
    this.resetSources();
    this.resolveSources().catch((): void => {});
  }

  private resetSources(): void {
    this.sourceEpoch += 1;
    this.sources = this.images.slice();
    this.resetImageTransform();
  }

  private sourceAt(index: number): string {
    const value: string | undefined = this.sources[index];
    return value !== undefined && value.length > 0 ? value : (this.images[index] ?? '');
  }

  private setSource(index: number, url: string, epoch: number): void {
    if (!this.alive || epoch !== this.sourceEpoch) return;
    const current: string | undefined = this.sources[index];
    if (current === url) return;
    const next: string[] = this.sources.slice();
    next[index] = url;
    this.sources = next;
  }

  private async resolveSources(): Promise<void> {
    const epoch: number = this.sourceEpoch;
    const snapshot: string[] = this.images.slice();
    for (let index: number = 0; index < snapshot.length; index++) {
      const url: string = snapshot[index];
      if (!/^https?:\/\//i.test(url)) continue;
      const known: string | null = await cachedMediaUrlIfPresent(url, 'image');
      if (!this.alive || epoch !== this.sourceEpoch) return;
      if (known !== null && known.length > 0) this.setSource(index, known, epoch);
    }
    this.resolveCurrent().catch((): void => {});
  }

  private async resolveCurrent(): Promise<void> {
    const epoch: number = this.sourceEpoch;
    const index: number = this.currentIndex;
    const url: string = this.images[index] ?? '';
    if (!/^https?:\/\//i.test(url)) return;
    const known: string | null = await cachedMediaUrlIfPresent(url, 'image');
    if (!this.alive || epoch !== this.sourceEpoch) return;
    if (known !== null && known.length > 0) {
      this.setSource(index, known, epoch);
      return;
    }
    const local: string = await cachedMediaUrl(url, 'image');
    if (!this.alive || epoch !== this.sourceEpoch) return;
    if (local.length > 0) this.setSource(index, local, epoch);
  }

  private changeImage(delta: number): void {
    this.currentIndex = Math.max(0, Math.min(this.images.length - 1, this.currentIndex + delta));
    this.resetImageTransform();
    this.resolveCurrent().catch((): void => {});
  }

  private resetImageTransform(): void {
    this.imageScale = 1;
    this.pinchStartScale = 1;
    this.imageOffsetX = 0;
    this.imageOffsetY = 0;
    this.panLastOffsetX = 0;
    this.panLastOffsetY = 0;
    if (this.imageSizeUrl !== (this.images[this.currentIndex] ?? '')) {
      this.imageWidth = 0;
      this.imageHeight = 0;
      this.imageSizeUrl = '';
    }
  }

  private clampImageOffset(x: number, y: number): void {
    let maxX: number = 0;
    let maxY: number = 0;
    if (this.imageWidth > 0 && this.imageHeight > 0 && this.viewportWidth > 0 && this.viewportHeight > 0) {
      const fit: number = Math.min(this.viewportWidth / this.imageWidth, this.viewportHeight / this.imageHeight);
      maxX = Math.max(0, (this.imageWidth * fit * this.imageScale - this.viewportWidth) / 2);
      maxY = Math.max(0, (this.imageHeight * fit * this.imageScale - this.viewportHeight) / 2);
    }
    this.imageOffsetX = maxX > 0 ? Math.max(-maxX, Math.min(maxX, x)) : 0;
    this.imageOffsetY = maxY > 0 ? Math.max(-maxY, Math.min(maxY, y)) : 0;
  }

  private setViewportSize(width: number, height: number): void {
    this.viewportWidth = width;
    this.viewportHeight = height;
    this.clampImageOffset(this.imageOffsetX, this.imageOffsetY);
  }

  private onImageLoaded(imageUrl: string, width: number, height: number): void {
    if (imageUrl !== (this.images[this.currentIndex] ?? '')) return;
    this.imageSizeUrl = imageUrl;
    this.imageWidth = width;
    this.imageHeight = height;
    this.clampImageOffset(this.imageOffsetX, this.imageOffsetY);
  }

  private startPinch(): void { this.pinchStartScale = this.imageScale; }

  private updatePinch(scale: number): void {
    this.imageScale = Math.max(1, Math.min(4, this.pinchStartScale * scale));
    if (this.imageScale === 1) {
      this.clampImageOffset(0, 0);
    } else {
      this.clampImageOffset(this.imageOffsetX, this.imageOffsetY);
    }
  }

  private startPan(): void {
    this.panLastOffsetX = 0;
    this.panLastOffsetY = 0;
  }

  private updatePan(offsetX: number, offsetY: number): void {
    // 第二指加入时 Pan 的累计位移不重置；1 倍下忽略的移动也要消费。
    const deltaX: number = offsetX - this.panLastOffsetX;
    const deltaY: number = offsetY - this.panLastOffsetY;
    this.panLastOffsetX = offsetX;
    this.panLastOffsetY = offsetY;
    this.clampImageOffset(this.imageScale > 1 ? this.imageOffsetX + deltaX : 0,
      this.imageScale > 1 ? this.imageOffsetY + deltaY : 0);
  }

  private async saveCurrentImage(): Promise<void> {
    if (this.saving) return;
    this.saving = true;
    try { await saveImageToAlbum(this.sourceAt(this.currentIndex)); }
    finally { this.saving = false; }
  }

  private async shareCurrentImage(): Promise<void> {
    if (this.sharing) return;
    this.sharing = true;
    try {
      const context: common.UIAbilityContext = this.getUIContext().getHostContext() as common.UIAbilityContext;
      await shareImage(this.sourceAt(this.currentIndex), context, this.shareAnchorId);
    } finally { this.sharing = false; }
  }

  private imageLoadHandler(imageUrl: string): (event?: ImagePreviewLoadEvent) => void {
    return (event?: ImagePreviewLoadEvent): void => {
      if (event !== undefined && event.loadingStatus === 1) this.onImageLoaded(imageUrl, event.width, event.height);
    };
  }

  build(): void {
    if (this.show && this.images.length > 0) {
      Column() {
        Column() {
          Row({ space: 8 }) {
            Blank().layoutWeight(1)
            Text(this.sharing ? '准备中' : '分享').fontSize(14).fontColor('#FFFFFF')
              .width(64).height(44).textAlign(TextAlign.Center).id(this.shareAnchorId)
              .enabled(!this.sharing)
              .onClick((): void => { this.shareCurrentImage(); })
            Text(this.saving ? '保存中' : '保存').fontSize(14).fontColor('#FFFFFF')
              .width(64).height(44).textAlign(TextAlign.Center).enabled(!this.saving)
              .onClick((): void => { this.saveCurrentImage(); })
            Row() {
              SymbolGlyph($r('sys.symbol.xmark')).fontSize(24).fontColor(['#FFFFFF'])
            }
              .width(44).height(44)
              .justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
              .accessibilityText('关闭图片预览')
              .onClick((): void => { this.onDismiss(); })
          }
          .width('100%').padding({ left: 12, right: 12, top: 8, bottom: 8 })
          Stack({ alignContent: Alignment.Center }) {
            Image(this.sourceAt(this.currentIndex)).width('100%').height('100%')
              .draggable(false)
              .objectFit(ImageFit.Contain)
              .scale({ x: this.imageScale, y: this.imageScale })
              .translate({ x: this.imageOffsetX, y: this.imageOffsetY })
              .onComplete(this.imageLoadHandler(this.images[this.currentIndex]))
              .onError((): void => { this.setSource(this.currentIndex, this.images[this.currentIndex], this.sourceEpoch); })
          }
          .width('100%').layoutWeight(1).clip(true)
          .onAreaChange((_oldArea: Area, area: Area): void => {
            this.setViewportSize(Number(area.width), Number(area.height));
          })
          .gesture(GestureGroup(GestureMode.Parallel,
            PinchGesture()
              .onActionStart((): void => { this.startPinch(); })
              .onActionUpdate((event: GestureEvent): void => { this.updatePinch(event.scale); }),
            PanGesture({ fingers: 1, direction: PanDirection.All })
              .onActionStart((): void => { this.startPan(); })
              .onActionUpdate((event: GestureEvent): void => { this.updatePan(event.offsetX, event.offsetY); }),
          ))
          if (this.images.length > 1) {
            Row() {
              Text('‹').fontSize(28).fontColor('#FFFFFF').width(48).height(44)
                .textAlign(TextAlign.Center).enabled(this.currentIndex > 0)
                .opacity(this.currentIndex > 0 ? 1 : 0.3)
                .onClick((): void => { this.changeImage(-1); })
              Text(`${this.currentIndex + 1} / ${this.images.length}`).fontSize(14)
                .fontColor('#FFFFFF').layoutWeight(1).textAlign(TextAlign.Center)
              Text('›').fontSize(28).fontColor('#FFFFFF').width(48).height(44)
                .textAlign(TextAlign.Center).enabled(this.currentIndex < this.images.length - 1)
                .opacity(this.currentIndex < this.images.length - 1 ? 1 : 0.3)
                .onClick((): void => { this.changeImage(1); })
            }
            .width('100%').padding({ left: 16, right: 16, top: 8, bottom: 12 })
          }
        }
        .width('100%').height('100%').backgroundColor('#000000')
      }
      .width('100%').height('100%')
      .padding({ top: this.statusBarHeight, bottom: this.navBarHeight })
      .backgroundColor(BG)
    }
  }
}
