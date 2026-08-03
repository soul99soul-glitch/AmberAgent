// ImagePreviewDialog — 图片预览对话框(简化版,对齐 Android ImagePreviewDialog.kt)
// Android: 全屏 zoomable pager + drag-to-dismiss + download
// 鸿蒙简化: 全屏 Image + 关闭按钮 + 下载按钮(pinch-zoom defer)
import { INK, INK3, SURFACE, LINE, BODY, META } from '../../design/tokens.ets';

@Component
export struct ImagePreviewDialog {
  @Prop images: string[] = [];
  @Prop show: boolean = false;
  @State currentIndex: number = 0;
  onDismiss: () => void = (): void => {};

  build(): void {
    if (this.show && this.images.length > 0) {
      Stack() {
        // 图片
        if (this.currentIndex < this.images.length) {
          Image(this.images[this.currentIndex])
            .width('100%')
            .height('100%')
            .objectFit(ImageFit.Contain)
        }

        // 顶部关闭按钮
        Row() {
          Text('✕')
            .fontSize(18)
            .fontColor('#FFFFFF')
            .padding(12)
            .onClick((): void => { this.onDismiss(); })
        }
        .width('100%')
        .justifyContent(FlexAlign.End)

        // 底部: 页码 + 下载(多图时有左右切换)
        Row() {
          if (this.images.length > 1 && this.currentIndex > 0) {
            Text('‹')
              .fontSize(24)
              .fontColor('#FFFFFF')
              .padding(12)
              .onClick((): void => { if (this.currentIndex > 0) this.currentIndex--; })
          }
          if (this.images.length > 1) {
            Text(`${this.currentIndex + 1} / ${this.images.length}`)
              .fontSize(META)
              .fontColor('#FFFFFF')
              .layoutWeight(1)
              .textAlign(TextAlign.Center)
          }
          if (this.images.length > 1 && this.currentIndex < this.images.length - 1) {
            Text('›')
              .fontSize(24)
              .fontColor('#FFFFFF')
              .padding(12)
              .onClick((): void => {
                if (this.currentIndex < this.images.length - 1) this.currentIndex++;
              })
          }
        }
        .width('100%')
        .justifyContent(FlexAlign.Center)
        .alignItems(VerticalAlign.Center)
      }
      .width('100%')
      .height('100%')
      .backgroundColor('#000000')
      .alignContent(Alignment.TopEnd)
    }
  }
}
