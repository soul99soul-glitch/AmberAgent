// 聊天图片、Markdown 图片与生成图共用的全屏预览。
import { common } from '@kit.AbilityKit';
import { newId } from '@amber/chat-domain';
import { saveImageToAlbum } from '../../platform_impl/ImageSaveSupport.ets';
import { shareImage } from '../../platform_impl/ImageShareSupport.ets';
import { Icon } from './Icon.ets';

@Component
export struct ImagePreviewDialog {
  @Prop images: string[] = [];
  @Prop show: boolean = true;
  @Prop initialIndex: number = 0;
  @State currentIndex: number = 0;
  @State imageScale: number = 1;
  @State saving: boolean = false;
  @State sharing: boolean = false;
  private pinchStartScale: number = 1;
  private shareAnchorId: string = `image-preview-share-${newId()}`;
  onDismiss: () => void = (): void => {};

  aboutToAppear(): void {
    this.currentIndex = Math.max(0, Math.min(this.images.length - 1, this.initialIndex));
  }

  private changeImage(delta: number): void {
    this.currentIndex = Math.max(0, Math.min(this.images.length - 1, this.currentIndex + delta));
    this.imageScale = 1;
    this.pinchStartScale = 1;
  }

  private async saveCurrentImage(): Promise<void> {
    if (this.saving) return;
    this.saving = true;
    try { await saveImageToAlbum(this.images[this.currentIndex]); }
    finally { this.saving = false; }
  }

  private async shareCurrentImage(): Promise<void> {
    if (this.sharing) return;
    this.sharing = true;
    try {
      const context: common.UIAbilityContext = this.getUIContext().getHostContext() as common.UIAbilityContext;
      await shareImage(this.images[this.currentIndex], context, this.shareAnchorId);
    } finally { this.sharing = false; }
  }

  build(): void {
    if (this.show && this.images.length > 0) {
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
          Icon({ name: 'close', iconSize: 24, color: '#FFFFFF' }).width(44).height(44)
            .onClick((): void => { this.onDismiss(); })
        }
        .width('100%').padding({ left: 12, right: 12, top: 8, bottom: 8 })
        Stack({ alignContent: Alignment.Center }) {
          Image(this.images[this.currentIndex]).width('100%').height('100%')
            .objectFit(ImageFit.Contain).scale({ x: this.imageScale, y: this.imageScale })
            .gesture(PinchGesture()
              .onActionStart((): void => { this.pinchStartScale = this.imageScale; })
              .onActionUpdate((event: GestureEvent): void => {
                this.imageScale = Math.max(1, Math.min(4, this.pinchStartScale * event.scale));
              }))
        }
        .width('100%').layoutWeight(1).clip(true)
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
  }
}
