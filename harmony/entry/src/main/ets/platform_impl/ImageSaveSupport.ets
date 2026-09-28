// ImageSaveSupport — 图片保存到系统相册(D7-4 提取;原 GeneratedImageCarousel 私有实现)
//
// showAssetsCreationDialog 弹窗授权(免 WRITE_IMAGEVIDEO);file:// 直存,
// http(s) 先落 cache;用户取消 = 返回空列表静默;canIUse 包裹。
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import { fileIo, fileUri } from '@kit.CoreFileKit';
import { http } from '@kit.NetworkKit';
import { promptAction } from '@kit.ArkUI';
import { Context } from '@kit.AbilityKit';
import { image } from '@kit.ImageKit';

export const saveImageToAlbum = async (url: string, extension: string = 'png'): Promise<void> => {
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
        srcPath = `${ctx.cacheDir}/amber_save_${Date.now()}.${extension}`;
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
      fileNameExtension: extension,
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
};

// PixelMap → 临时 PNG 文件 → 相册(长图/Mermaid 导出共用)
export const savePixelMapToAlbum = async (pm: image.PixelMap): Promise<void> => {
  const ctx: Context = AppStorage.get<Context>('abilityContext') as Context;
  const path: string = `${ctx.cacheDir}/amber_shot_${Date.now()}.png`;
  const packer: image.ImagePacker = image.createImagePacker();
  const file: fileIo.File = fileIo.openSync(
    path, fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
  try {
    const option: image.PackingOption = { format: 'image/png', quality: 95 };
    await packer.packToFile(pm, file.fd, option);
  } finally {
    fileIo.closeSync(file.fd);
    packer.release();
  }
  await saveImageToAlbum(`file://${path}`, 'png');
};
