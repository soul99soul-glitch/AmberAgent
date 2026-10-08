// 图片操作共用的真实文件来源；缓存只用于本次保存/分享，不进入聊天附件注册表。
import { Context } from '@kit.AbilityKit';
import { fileIo } from '@kit.CoreFileKit';
import { http } from '@kit.NetworkKit';
import { util } from '@kit.ArkTS';
import { newId } from '@amber/chat-domain';

export interface PreparedImageSource {
  path: string;
  extension: string;
  temporary: boolean;
}

const imageExtension = (bytes: Uint8Array, fallback: string): string => {
  if (bytes.length >= 8 && bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47) return 'png';
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return 'jpg';
  if (bytes.length >= 6 && bytes[0] === 0x47 && bytes[1] === 0x49 && bytes[2] === 0x46) return 'gif';
  if (bytes.length >= 12 && bytes[0] === 0x52 && bytes[1] === 0x49 && bytes[2] === 0x46 &&
    bytes[8] === 0x57 && bytes[9] === 0x45 && bytes[10] === 0x42 && bytes[11] === 0x50) return 'webp';
  if (bytes.length >= 2 && bytes[0] === 0x42 && bytes[1] === 0x4d) return 'bmp';
  return fallback;
};

const sourceExtension = (url: string, fallback: string): string => {
  const match: RegExpMatchArray | null = url.split(/[?#]/)[0].match(/\.(png|jpe?g|gif|webp|bmp|heic)$/i);
  if (match === null) return fallback;
  return match[1].toLowerCase() === 'jpeg' ? 'jpg' : match[1].toLowerCase();
};

export const releaseImageSource = (source: PreparedImageSource): void => {
  if (!source.temporary) return;
  try { fileIo.unlinkSync(source.path); } catch { /* 已清理的临时文件 */ }
};

export const prepareImageSource = async (url: string, fallbackExtension: string = 'png'): Promise<PreparedImageSource> => {
  if (url.startsWith('file://')) {
    const path: string = url.substring('file://'.length);
    const header: Uint8Array = new Uint8Array(12);
    const file: fileIo.File = fileIo.openSync(path, fileIo.OpenMode.READ_ONLY);
    try { fileIo.readSync(file.fd, header.buffer as ArrayBuffer); }
    finally { fileIo.closeSync(file.fd); }
    return { path, extension: imageExtension(header, sourceExtension(url, fallbackExtension)), temporary: false };
  }
  let bytes: Uint8Array;
  let extension: string = sourceExtension(url, fallbackExtension);
  if (url.startsWith('data:')) {
    const comma: number = url.indexOf(',');
    const header: string = url.substring(0, comma);
    const mime: RegExpMatchArray | null = header.match(/^data:image\/(png|jpe?g|gif|webp|bmp|heic);base64$/i);
    if (comma < 0 || mime === null) throw new Error('该图片不是受支持的 base64 图片');
    extension = mime[1].toLowerCase() === 'jpeg' ? 'jpg' : mime[1].toLowerCase();
    bytes = new util.Base64Helper().decodeSync(url.substring(comma + 1));
  } else if (url.startsWith('http://') || url.startsWith('https://')) {
    const request: http.HttpRequest = http.createHttp();
    try {
      const response: http.HttpResponse = await request.request(url, {
        method: http.RequestMethod.GET, expectDataType: http.HttpDataType.ARRAY_BUFFER,
        connectTimeout: 30000, readTimeout: 60000,
      });
      if (response.responseCode < 200 || response.responseCode >= 300 || !(response.result instanceof ArrayBuffer)) {
        throw new Error('图片下载失败');
      }
      bytes = new Uint8Array(response.result as ArrayBuffer);
    } finally {
      request.destroy();
    }
  } else {
    throw new Error('该图片来源不支持保存或分享');
  }
  if (bytes.length === 0) throw new Error('图片内容为空');
  extension = imageExtension(bytes, extension);
  const context: Context = AppStorage.get<Context>('abilityContext') as Context;
  const source: PreparedImageSource = {
    path: `${context.cacheDir}/amber_image_${newId()}.${extension}`, extension, temporary: true,
  };
  let file: fileIo.File | null = null;
  try {
    file = fileIo.openSync(source.path, fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
    fileIo.writeSync(file.fd, bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer);
  } catch (error) {
    releaseImageSource(source);
    throw error instanceof Error ? error : new Error(String(error));
  } finally {
    if (file !== null) fileIo.closeSync(file.fd);
  }
  return source;
};
