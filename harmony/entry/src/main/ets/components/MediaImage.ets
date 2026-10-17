import { cachedMediaUrl, cachedMediaUrlIfPresent } from '../platform_impl/MediaCache.ets';
import { INK3, SURFACE2 } from '../design/tokens.ets';

/** Image with a persistent local-file cache; starts from the original URL and swaps to file:// when ready. */
@Component
export struct MediaImage {
  @Prop @Watch('onUrlChanged') url: string = '';
  @Prop frameWidth: Length = 160;
  // 0 = keep the image's natural aspect ratio under frameWidth; >0 = fixed thumbnail box.
  @Prop frameHeight: Length = 0;
  @Prop radius: number = 8;
  @Prop fit: ImageFit = ImageFit.Cover;
  onOpen: (url: string) => void = (_url: string): void => {};
  @State private displayUrl: string = '';
  @State private failed: boolean = false;
  private prepared: boolean = false;
  private alive: boolean = true;
  private epoch: number = 0;

  aboutToAppear(): void { this.alive = true; this.reset(); }
  aboutToDisappear(): void { this.alive = false; }

  private reset(): void {
    this.epoch += 1;
    this.displayUrl = this.url;
    this.failed = false;
    this.prepared = false;
  }

  private onUrlChanged(): void { this.reset(); }

  private async prepare(): Promise<void> {
    if (this.prepared || this.url.length === 0) return;
    this.prepared = true;
    if (!/^https?:\/\//i.test(this.url)) return;
    const epoch: number = this.epoch;
    const known: string | null = await cachedMediaUrlIfPresent(this.url, 'image');
    if (!this.alive || epoch !== this.epoch) return;
    if (known !== null && known !== this.url && known.length > 0) {
      this.failed = false;
      this.displayUrl = known;
      return;
    }
    const local: string = await cachedMediaUrl(this.url, 'image');
    if (!this.alive || epoch !== this.epoch || local === this.url || local.length === 0) return;
    this.failed = false;
    this.displayUrl = local;
  }

  private onImageError(): void {
    if (this.displayUrl !== this.url) this.displayUrl = this.url;
    else this.failed = true;
  }

  @Builder
  private MediaBody(fixed: boolean): void {
    if (this.failed || this.displayUrl.length === 0) {
      Row() {
        Text(this.failed ? '图片无法显示' : '正在加载图片')
          .fontSize(11).fontColor(INK3).textAlign(TextAlign.Center);
      }.width('100%').height(fixed ? '100%' : 96).justifyContent(FlexAlign.Center)
        .backgroundColor(SURFACE2).borderRadius(this.radius);
    } else if (fixed) {
      Image(this.displayUrl).width('100%').height('100%').objectFit(this.fit)
        .borderRadius(this.radius).onError((): void => { this.onImageError(); });
    } else {
      Image(this.displayUrl).width('100%').objectFit(this.fit)
        .borderRadius(this.radius).onError((): void => { this.onImageError(); });
    }
  }

  build(): void {
    Column() {
      if (this.frameHeight === 0) {
        this.MediaBody(false)
      } else {
        Stack() { this.MediaBody(true) }.width('100%').height(this.frameHeight)
      }
    }
    .width(this.frameWidth).borderRadius(this.radius)
    .onVisibleAreaChange([0.0, 0.01], (isVisible: boolean): void => {
      if (isVisible) this.prepare().catch((): void => {});
    })
    .onClick((): void => { if (!this.failed) this.onOpen(this.url); });
  }
}
