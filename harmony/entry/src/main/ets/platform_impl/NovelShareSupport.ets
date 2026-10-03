// Reuses the same ShareKit file handoff as article exports. Successful files remain
// in cache because show() resolves before recipients finish reading them.
import { common } from '@kit.AbilityKit';
import { fileIo, fileUri } from '@kit.CoreFileKit';
import { systemShare } from '@kit.ShareKit';
import { uniformTypeDescriptor } from '@kit.ArkData';
import { util } from '@kit.ArkTS';

export type NovelShareFormat = 'txt' | 'markdown' | 'epub' | 'zip';

const shareType = (format: NovelShareFormat): string => {
  if (format === 'txt') return uniformTypeDescriptor.UniformDataType.PLAIN_TEXT;
  if (format === 'markdown') return uniformTypeDescriptor.UniformDataType.MARKDOWN;
  if (format === 'epub') return uniformTypeDescriptor.UniformDataType.EPUB;
  return uniformTypeDescriptor.UniformDataType.ZIP_ARCHIVE;
};

const removeFile = (path: string, directory: string): void => {
  try { fileIo.unlinkSync(path); } catch { /* Keep the original handoff error. */ }
  try { fileIo.rmdirSync(directory); } catch { /* Only this invocation's cache directory. */ }
};

export const shareNovelFile = async (
  context: common.UIAbilityContext, fileName: string, bytes: Uint8Array, format: NovelShareFormat,
  isActive: () => boolean = (): boolean => true,
): Promise<void> => {
  if (!isActive()) return;
  if (!canIUse('SystemCapability.Collaboration.SystemShare')) throw new Error('当前系统不支持文件分享');
  const directory: string = `${context.cacheDir}/novel-share-${util.generateRandomUUID(false)}`;
  const path: string = `${directory}/${fileName}`;
  try {
    fileIo.mkdirSync(directory);
    const file: fileIo.File = fileIo.openSync(path,
      fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
    try {
      let consumed: number = 0;
      while (consumed < bytes.byteLength) {
        const remaining: ArrayBuffer = (bytes.buffer as ArrayBuffer)
          .slice(bytes.byteOffset + consumed, bytes.byteOffset + bytes.byteLength);
        const written: number = fileIo.writeSync(file.fd, remaining);
        if (written <= 0 || written > remaining.byteLength) throw new Error('分享文件写入未完成');
        consumed += written;
      }
    } finally {
      fileIo.closeSync(file.fd);
    }
    if (!isActive()) { removeFile(path, directory); return; }
    const data: systemShare.SharedData = new systemShare.SharedData({
      utd: shareType(format), uri: fileUri.getUriFromPath(path), title: fileName,
    });
    const controller: systemShare.ShareController = new systemShare.ShareController(data);
    await controller.show(context, {});
  } catch (error) {
    removeFile(path, directory);
    throw error instanceof Error ? error : new Error(String(error));
  }
};
