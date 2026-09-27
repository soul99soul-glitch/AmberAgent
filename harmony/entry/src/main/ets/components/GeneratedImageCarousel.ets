// GeneratedImageCarousel — AI 生成图片轮播(第三梯队)
// Android 基准: GeneratedImageCarousel.kt(297 行)— LazyRow + AsyncImage + 复制/下载/分享
//   依赖 Coil AsyncImage(鸿蒙用 ArkUI Image)
//
// 功能(MVP):
//   ✅ 水平滚动图片轮播(Swiper)
//   ✅ 复制图片 URL
//   ❌ 下载到本地(后续迭代,需平台文件 API)
//   ❌ 分享(后续迭代,需系统 share sheet)

import { INK3, ACCENT, SURFACE2, META } from '../design/tokens.ets';
import { pasteboard } from '@kit.BasicServicesKit';
import { promptAction } from '@kit.ArkUI';

@Component
export struct GeneratedImageCarousel {
  @Prop imageUrls: string[];
  @Prop showActions: boolean = true;
  // 修改入口(generate_image edit 流程;仅 generate_image 工具结果开启)
  @Prop showModify: boolean = false;
  onModify: (url: string) => void = (_url: string): void => {};
  @State currentIndex: number = 0;

  private copyUrl(url: string): void {
    const board: pasteboard.SystemPasteboard = pasteboard.getSystemPasteboard();
    board.setDataSync(pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, url));
    promptAction.showToast({ message: '已复制链接', duration: 1500 });
  }

  build(): void {
    if (this.imageUrls.length === 0) {
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
            Text(`图片 ${this.currentIndex + 1}/${this.imageUrls.length}`)
              .fontSize(META).fontColor(INK3)
              .layoutWeight(1)
            if (this.showModify) {
              Text('修改').fontSize(META).fontColor(ACCENT)
                .padding({ left: 8, right: 8, top: 4, bottom: 4 })
                .onClick((): void => {
                  if (this.currentIndex < this.imageUrls.length) {
                    this.onModify(this.imageUrls[this.currentIndex]);
                  }
                })
            }
            Text('复制链接').fontSize(META).fontColor(ACCENT)
              .padding({ left: 8, right: 8, top: 4, bottom: 4 })
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
    }
  }
}
