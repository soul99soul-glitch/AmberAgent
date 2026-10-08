// ImageSaveSupport — 图片保存到系统相册(D7-4 提取;原 GeneratedImageCarousel 私有实现)
//
// showAssetsCreationDialog 弹窗授权(免 WRITE_IMAGEVIDEO);file:// 直存,
// http(s)/data 图片先落 cache;用户取消 = 返回空列表静默;canIUse 包裹。
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import { fileIo, fileUri } from '@kit.CoreFileKit';
import { promptAction } from '@kit.ArkUI';
import { Context } from '@kit.AbilityKit';
import { image } from '@kit.ImageKit';
import { newId } from '@amber/chat-domain';
import { prepareImageSource, releaseImageSource, PreparedImageSource } from './ImageSourceSupport.ets';

// 一批临时图片只申请一次用户授权，文件顺序就是分页顺序。
export const saveImageFilesToAlbum = async (paths: string[], extension: string = 'png'): Promise<boolean> => {
  if (!canIUse('SystemCapability.FileManagement.PhotoAccessHelper.Core')) {
    promptAction.showToast({ message: '当前系统不支持保存到相册', duration: 2000 });
    return false;
  }
  if (paths.length === 0) return false;
  const ctx: Context = AppStorage.get<Context>('abilityContext') as Context;
  const srcUris: string[] = paths.map((path: string): string => fileUri.getUriFromPath(path));
  const title: string = `amber_${Date.now()}`;
  const configs: photoAccessHelper.PhotoCreationConfig[] = paths.map(
    (_path: string, index: number): photoAccessHelper.PhotoCreationConfig => ({
      title: `${title}_${index + 1}`,
      fileNameExtension: extension,
      photoType: photoAccessHelper.PhotoType.IMAGE,
      subtype: photoAccessHelper.PhotoSubtype.DEFAULT,
    }));
  const helper: photoAccessHelper.PhotoAccessHelper = photoAccessHelper.getPhotoAccessHelper(ctx);
  try {
    const desUris: string[] = await helper.showAssetsCreationDialog(srcUris, configs);
    if (desUris.length === 0) return false;
    for (let index: number = 0; index < desUris.length; index++) {
      const desFile: fileIo.File = fileIo.openSync(desUris[index], fileIo.OpenMode.WRITE_ONLY);
      try {
        const srcFile: fileIo.File = fileIo.openSync(srcUris[index], fileIo.OpenMode.READ_ONLY);
        try {
          fileIo.copyFileSync(srcFile.fd, desFile.fd);
        } finally {
          fileIo.closeSync(srcFile.fd);
        }
      } finally {
        fileIo.closeSync(desFile.fd);
      }
    }
    promptAction.showToast({ message: desUris.length === 1 ? '已保存到相册'
      : `已保存 ${desUris.length} 张图片到相册`, duration: 1500 });
    return true;
  } finally {
    try { await helper.release(); } catch { /* 释放失败不覆盖保存结果或原始错误 */ }
  }
};

export const removeCachedImageFiles = (paths: string[]): void => {
  for (const path of paths) {
    try { fileIo.unlinkSync(path); } catch { /* 临时文件已清理 */ }
  }
};

export const saveImageToAlbum = async (url: string, extension: string = 'png'): Promise<void> => {
  if (!canIUse('SystemCapability.FileManagement.PhotoAccessHelper.Core')) {
    promptAction.showToast({ message: '当前系统不支持保存到相册', duration: 2000 });
    return;
  }
  let source: PreparedImageSource | null = null;
  try {
    source = await prepareImageSource(url, extension);
    await saveImageFilesToAlbum([source.path], source.extension);
  } catch (error) {
    const message: string = error instanceof Error ? error.message : String(error);
    promptAction.showToast({ message: `保存失败:${message}`, duration: 2000 });
  } finally {
    if (source !== null) releaseImageSource(source);
  }
};

// 每片先落 PNG，调用方立即 release PixelMap，不累计整批截图内存。
export const packPixelMapToCache = async (pm: image.PixelMap): Promise<string> => {
  const ctx: Context = AppStorage.get<Context>('abilityContext') as Context;
  const path: string = `${ctx.cacheDir}/amber_shot_${newId()}.png`;
  const packer: image.ImagePacker = image.createImagePacker();
  let file: fileIo.File | null = null;
  try {
    file = fileIo.openSync(path, fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
    const option: image.PackingOption = { format: 'image/png', quality: 95 };
    await packer.packToFile(pm, file.fd, option);
  } catch (error) {
    removeCachedImageFiles([path]);
    throw error instanceof Error ? error : new Error(String(error));
  } finally {
    if (file !== null) fileIo.closeSync(file.fd);
    await packer.release();
  }
  return path;
};

// 单张导出仍复用相同打包/相册链路，PixelMap 所有权保持在调用方。
export const savePixelMapToAlbum = async (pm: image.PixelMap): Promise<void> => {
  const path: string = await packPixelMapToCache(pm);
  try {
    await saveImageFilesToAlbum([path]);
  } finally {
    removeCachedImageFiles([path]);
  }
};
