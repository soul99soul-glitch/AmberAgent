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
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import { fileIo, fileUri } from '@kit.CoreFileKit';
import { http } from '@kit.NetworkKit';
import { Context } from '@kit.AbilityKit';

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

  // D0-2:保存到系统相册(showAssetsCreationDialog 弹窗授权,免 WRITE_IMAGEVIDEO;
  //   file:// 直存,http(s) 先落 cache;用户取消 = 返回空列表静默)
  private async saveToAlbum(url: string): Promise<void> {
    if (!canIUse('SystemCapability.FileManagement.PhotoAccessHelper.Core')) {
      promptAction.showToast({ message: '当前系统不支持保存到相册', duration: 2000 });
      return;
    }
    try {
      const ctx: Context = AppStorage.get<Context>('abilityContext') as Context;
      let srcPath: string = '';
      if (url.startsWith('file://')) {
        srcPath = url.substring('file://'.length);
      } else if (url.startsWith('http://') || url.startsWith('https://')) {
        const req: http.HttpRequest = http.createHttp();
        try {
          const resp: http.HttpResponse = await req.request(url, {
            method: http.RequestMethod.GET,
            expectDataType: http.HttpDataType.ARRAY_BUFFER,
            connectTimeout: 30000,
            readTimeout: 60000,
          });
          if (!(resp.result instanceof ArrayBuffer)) throw new Error('download failed');
          srcPath = `${ctx.cacheDir}/amber_save_${Date.now()}.png`;
          const tmp: fileIo.File = fileIo.openSync(
            srcPath, fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
          fileIo.writeSync(tmp.fd, resp.result as ArrayBuffer);
          fileIo.closeSync(tmp.fd);
        } finally {
          try { req.destroy(); } catch { /* 已销毁 */ }
        }
      } else {
        promptAction.showToast({ message: '该图片不支持保存', duration: 1500 });
        return;
      }
      const helper: photoAccessHelper.PhotoAccessHelper = photoAccessHelper.getPhotoAccessHelper(ctx);
      const srcUri: string = fileUri.getUriFromPath(srcPath);
      const configs: photoAccessHelper.PhotoCreationConfig[] = [{
        title: `amber_${Date.now()}`,
        fileNameExtension: 'png',
        photoType: photoAccessHelper.PhotoType.IMAGE,
        subtype: photoAccessHelper.PhotoSubtype.DEFAULT,
      }];
      const desUris: string[] = await helper.showAssetsCreationDialog([srcUri], configs);
      if (desUris.length === 0) return;
      const desFile: fileIo.File = fileIo.openSync(desUris[0], fileIo.OpenMode.WRITE_ONLY);
      const srcFile: fileIo.File = fileIo.openSync(srcUri, fileIo.OpenMode.READ_ONLY);
      fileIo.copyFileSync(srcFile.fd, desFile.fd);
      fileIo.closeSync(srcFile.fd);
      fileIo.closeSync(desFile.fd);
      promptAction.showToast({ message: '已保存到相册', duration: 1500 });
    } catch (e) {
      const msg: string = e instanceof Error ? e.message : String(e);
      promptAction.showToast({ message: `保存失败:${msg}`, duration: 2000 });
    }
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
            Text('保存').fontSize(META).fontColor(ACCENT)
              .padding({ left: 8, right: 8, top: 4, bottom: 4 })
              .onClick((): void => {
                if (this.currentIndex < this.imageUrls.length) {
                  this.saveToAlbum(this.imageUrls[this.currentIndex]);
                }
              })
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
