import { common } from '@kit.AbilityKit';
import { fileUri } from '@kit.CoreFileKit';
import { systemShare } from '@kit.ShareKit';
import { uniformTypeDescriptor as utd } from '@kit.ArkData';
import { promptAction } from '@kit.ArkUI';
import { prepareImageSource, releaseImageSource, PreparedImageSource } from './ImageSourceSupport.ets';

// 分享真实图片文件；show 只表示面板已显示，临时来源必须等 dismiss 才能清理。
export const shareImage = async (url: string, context: common.UIAbilityContext, anchorId: string = ''): Promise<void> => {
  if (!canIUse('SystemCapability.Collaboration.SystemShare')) {
    promptAction.showToast({ message: '当前系统不支持图片分享', duration: 2000 });
    return;
  }
  let source: PreparedImageSource | null = null;
  try {
    source = await prepareImageSource(url);
    const sharedSource: PreparedImageSource = source;
    const data: systemShare.SharedData = new systemShare.SharedData({
      utd: utd.UniformDataType.IMAGE, uri: fileUri.getUriFromPath(source.path),
    });
    const controller: systemShare.ShareController = new systemShare.ShareController(data);
    controller.on('dismiss', (): void => { releaseImageSource(sharedSource); });
    const options: systemShare.ShareControllerOptions = {};
    if (anchorId.length > 0) options.anchor = anchorId;
    await controller.show(context, options);
  } catch (error) {
    if (source !== null) releaseImageSource(source);
    const message: string = error instanceof Error ? error.message : String(error);
    promptAction.showToast({ message: `分享失败:${message}`, duration: 2000 });
  }
};
