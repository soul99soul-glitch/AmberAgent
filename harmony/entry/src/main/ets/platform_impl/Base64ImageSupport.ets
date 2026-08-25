// Base64ImageSupport — Base64ImageToLocalFileTransformer 的 entry 实现(D-076)
// Android 基准:
//   FilesManager.kt:183-206 createChatFilesByByteArrays(128MB require + 写文件)
//   FilesManager.kt:239-247 BitmapFactory.decodeByteArray null → 保留 data url
//   FilesManager.kt:643-646 Bitmap.compressToPng(PNG, 100)
//   FilesManager.kt:469-480 buildUuidFileName('image.png','image/png') → uuid.png
// 偏差(登记):
//   - Bitmap.compress PNG → @kit.ImageKit packToData(image/png, quality 100)
//   - trackUploadFile 失败:Android appScope.launch+runCatching 静默吞错;
//     本实现 hilog.warn 记录(可观测性增强,不改变成功路径语义)
import { fileIo } from '@kit.CoreFileKit';
import { image } from '@kit.ImageKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import {
  FILE_FOLDER_UPLOAD, MAX_CHAT_ATTACHMENT_BYTES,
  createBase64ImageToLocalFileTransformer, newId, trackUploadFile,
} from '@amber/chat-domain';
import type { Base64ImageDeps, OutputMessageTransformer } from '@amber/chat-domain';
import { getFilesRepository } from './ManagedFileStore.ets';

// BitmapFactory.decodeByteArray + compressToPng:不可解码 → null
const reencodeToPng = async (bytes: Uint8Array): Promise<Uint8Array | null> => {
  const buffer: ArrayBuffer = bytes.buffer.slice(
    bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
  const source: image.ImageSource = image.createImageSource(buffer);
  try {
    const pixelMap: image.PixelMap = await source.createPixelMap();
    try {
      const packer: image.ImagePacker = image.createImagePacker();
      try {
        const packed: ArrayBuffer = await packer.packToData(
          pixelMap, { format: 'image/png', quality: 100 });
        return new Uint8Array(packed);
      } finally {
        await packer.release(); // ImagePacker 也是 native 资源(同 ImageCompress 模式)
      }
    } finally {
      pixelMap.release();
    }
  } finally {
    await source.release(); // ImageSource 泄漏会累积 native 解码资源
  }
};

// 包装:不可解码 → null(FilesManager.kt:240-246 keeping data url part 语义)
const reencodeToPngSafe = async (bytes: Uint8Array): Promise<Uint8Array | null> => {
  try {
    return await reencodeToPng(bytes);
  } catch {
    return null;
  }
};

const filesDir = (): string => {
  const ctx: Context = AppStorage.get<Context>('abilityContext') as Context;
  return ctx.filesDir;
};

// createChatFilesByByteArrays 单文件:require ≤128MB → upload/{uuid}.png → file:// uri
const saveUploadImage = async (pngBytes: Uint8Array): Promise<string> => {
  if (pngBytes.byteLength > MAX_CHAT_ATTACHMENT_BYTES) {
    throw new Error(`Chat attachment exceeds ${MAX_CHAT_ATTACHMENT_BYTES} byte limit`);
  }
  const dir: string = `${filesDir()}/${FILE_FOLDER_UPLOAD}`;
  try {
    await fileIo.mkdir(dir, true);
  } catch {
    // 已存在
  }
  // buildUuidFileName('image.png','image/png') → uuid.png(FilesManager.kt:469-480)
  const fileName: string = `${newId()}.png`;
  const path: string = `${dir}/${fileName}`;
  const mode: number = fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC;
  const file: fileIo.File = await fileIo.open(path, mode);
  try {
    const buffer: ArrayBuffer = pngBytes.buffer.slice(
      pngBytes.byteOffset, pngBytes.byteOffset + pngBytes.byteLength) as ArrayBuffer;
    await fileIo.write(file.fd, buffer);
  } finally {
    await fileIo.close(file);
  }
  // D-086:trackUploadFile(FilesManager.kt:200/:482-505)— fire-and-forget;
  //   Android runCatching 静默,此处 hilog.warn(登记偏差)
  trackUploadFile(getFilesRepository(), {
    relativePath: `${FILE_FOLDER_UPLOAD}/${fileName}`,
    displayName: 'image.png',
    mimeType: 'image/png',
    sizeBytes: pngBytes.byteLength,
  }).catch((error: Error): void => {
    hilog.warn(0x0001, 'Chat.Base64Image', '[trackUploadFile] %{public}s', error.message);
  });
  return `file://${path}`;
};

const ENTRY_BASE64_IMAGE_DEPS: Base64ImageDeps = {
  reencodeToPng: reencodeToPngSafe,
  saveUploadImage,
};

export const createEntryBase64ImageTransformer = (): OutputMessageTransformer =>
  createBase64ImageToLocalFileTransformer(ENTRY_BASE64_IMAGE_DEPS);
