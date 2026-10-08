// 聊天 Markdown 文件保存/分享；内容由域层生成，用户取消保存时不另写文件。
import { common } from '@kit.AbilityKit';
import { fileIo, fileUri, picker } from '@kit.CoreFileKit';
import { systemShare } from '@kit.ShareKit';
import { uniformTypeDescriptor } from '@kit.ArkData';

const writeMarkdownFile = (path: string, content: string): void => {
  const file: fileIo.File = fileIo.openSync(path,
    fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
  try {
    fileIo.writeSync(file.fd, content);
  } finally {
    fileIo.closeSync(file.fd);
  }
};

export const saveMarkdownFile = async (
  context: common.UIAbilityContext, fileName: string, content: string,
): Promise<boolean> => {
  const uris: string[] = await new picker.DocumentViewPicker(context).save({ newFileNames: [fileName] });
  if (uris.length === 0) return false;
  writeMarkdownFile(uris[0], content);
  return true;
};

export const shareMarkdownFile = async (
  context: common.UIAbilityContext, fileName: string, content: string,
): Promise<void> => {
  const path: string = `${context.cacheDir}/${fileName}`;
  writeMarkdownFile(path, content);
  try {
    const data: systemShare.SharedData = new systemShare.SharedData({
      utd: uniformTypeDescriptor.UniformDataType.MARKDOWN,
      uri: fileUri.getUriFromPath(path),
      title: fileName,
    });
    const controller: systemShare.ShareController = new systemShare.ShareController(data);
    await controller.show(context, {});
    // 接收应用仍可能读取该文件；保留在系统可清理的 cacheDir 中。
  } catch (error) {
    fileIo.unlinkSync(path);
    throw error instanceof Error ? error : new Error(String(error));
  }
};
