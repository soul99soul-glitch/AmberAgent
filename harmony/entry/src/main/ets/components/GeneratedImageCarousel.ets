// GeneratedImageCarousel — 生成图轮播、全屏预览及图片操作。
import { INK3, ACCENT, SURFACE2, META } from '../design/tokens.ets';
import { pasteboard } from '@kit.BasicServicesKit';
import { promptAction } from '@kit.ArkUI';
import { saveImageToAlbum } from '../platform_impl/ImageSaveSupport.ets';
import { shareImage } from '../platform_impl/ImageShareSupport.ets';
import { common } from '@kit.AbilityKit';
import { newId } from '@amber/chat-domain';
import { ImagePreviewDialog } from './ui/ImagePreviewDialog.ets';

@Component
export struct GeneratedImageCarousel {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop imageUrls: string[];
  @Prop showActions: boolean = true;
  // 修改入口(generate_image edit 流程;仅 generate_image 工具结果开启)
  @Prop showModify: boolean = false;
  onModify: (url: string) => void = (_url: string): void => {};
  @State currentIndex: number = 0;
  @State previewOpen: boolean = false;
  @State sharing: boolean = false;
  private shareAnchorId: string = `generated-image-share-${newId()}`;

  @Builder
  private ImagePreview(): void {
    ImagePreviewDialog({ images: this.imageUrls, initialIndex: this.currentIndex,
      onDismiss: (): void => { this.previewOpen = false; } })
  }

  private async shareCurrentImage(): Promise<void> {
    if (this.sharing || this.currentIndex >= this.imageUrls.length) return;
    this.sharing = true;
    try {
      const context: common.UIAbilityContext = this.getUIContext().getHostContext() as common.UIAbilityContext;
      await shareImage(this.imageUrls[this.currentIndex], context, this.shareAnchorId);
    } finally { this.sharing = false; }
  }

  private copyUrl(url: string): void {
    const board: pasteboard.SystemPasteboard = pasteboard.getSystemPasteboard();
    board.setDataSync(pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, url));
    promptAction.showToast({ message: '已复制链接', duration: 1500 });
  }

  build(): void {
    if (this.themeEpoch >= 0 && this.imageUrls.length === 0) {
      Blank().height(0)
    } else {
      Column() {
        // 图片轮播
        Swiper() {
          ForEach(this.imageUrls, (url: string, i: number) => {
            Image(url)
              .width('100%')
              .height(300)
              .objectFit(ImageFit.Contain)
              .backgroundColor(SURFACE2)
              .borderRadius(12)
              .onClick((): void => {
                this.currentIndex = i;
                this.previewOpen = true;
              })
          }, (url: string, i: number): string => `img-${url}`)
        }
        .indicator(true)
        .onChange((index: number): void => {
          this.currentIndex = index;
        })
        .height(320)

        // 操作行
        if (this.showActions) {
          Row() {
            Text(`${this.currentIndex + 1}/${this.imageUrls.length}`)
              .fontSize(META).fontColor(INK3).layoutWeight(1)
            if (this.showModify) {
              Text('修改').fontSize(META).fontColor(ACCENT)
                .height(44).padding({ left: 8, right: 8 }).textAlign(TextAlign.Center)
                .onClick((): void => {
                  if (this.currentIndex < this.imageUrls.length) {
                    this.onModify(this.imageUrls[this.currentIndex]);
                  }
                })
            }
            Text(this.sharing ? '准备中' : '分享').fontSize(META).fontColor(ACCENT).height(44)
              .padding({ left: 8, right: 8 }).textAlign(TextAlign.Center).id(this.shareAnchorId)
              .enabled(!this.sharing)
              .onClick((): void => { this.shareCurrentImage(); })
            Text('保存').fontSize(META).fontColor(ACCENT)
              .height(44).padding({ left: 8, right: 8 }).textAlign(TextAlign.Center)
              .onClick((): void => {
                if (this.currentIndex < this.imageUrls.length) {
                  saveImageToAlbum(this.imageUrls[this.currentIndex]).catch((): void => {});
                }
              })
            Text('复制链接').fontSize(META).fontColor(ACCENT)
              .height(44).padding({ left: 8, right: 8 }).textAlign(TextAlign.Center)
              .onClick((): void => {
                if (this.currentIndex < this.imageUrls.length) {
                  this.copyUrl(this.imageUrls[this.currentIndex]);
                }
              })
          }
          .width('100%')
          .padding({ left: 12, right: 12, top: 6, bottom: 6 })
        }
      }
      .width('100%')
      .margin({ bottom: 4 })
      .bindContentCover(this.previewOpen, this.ImagePreview(), {
        onWillDismiss: (): void => { this.previewOpen = false; },
      })
    }
  }
}
