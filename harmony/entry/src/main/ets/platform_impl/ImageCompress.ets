// ImageCompress — FileEncoder.kt:138-224 compressAndEncode 的 entry 实现(D-079b)
// Android 基准:
//   :143-146 gif 原样直通(动图不压缩,保持原 mime)
//   :148-155 decode bounds 失败 → 'Failed to decode image dimensions: X'(逐字)
//   :157-171 强制 calculateInSampleSize 采样压缩;decode 失败 → 'Failed to decode image: X'(逐字)
//     (Android 的 heic/avif API 等级变体在鸿蒙无对应物,统一走 :169 else 分支)
//   :189-198 EXIF 归一化;读取失败 runCatching → 按 NORMAL 处理(:193-195)
//   :200-224 8 值变换;变换失败 runCatching → 保留原图
//   :174-186 强制 JPEG q85 输出("很多提供商不支持 webp")
// 偏差(登记 D-079):Android 在每次发送 encode 时压缩(file:// 路径);鸿蒙在选图物化时
//   压缩一次并直接存 data: URL —— 提供商载荷等价(JPEG q85,长边 ≤2048,EXIF 已归一)。
//   变换手段:Android Matrix 合成新 bitmap;鸿蒙 PixelMap.rotate/flip 原地顺序执行,
//   TRANSPOSE=rotate90+flipH / TRANSVERSE=rotate270+flipH,与 postScale 合成语义一致。

import { fileIo } from '@kit.CoreFileKit';
import { image } from '@kit.ImageKit';
import { util } from '@kit.ArkTS';
import {
  COMPRESS_JPEG_QUALITY, COMPRESS_MAX_DIMENSION, assertPickerImageSize,
  calculateInSampleSize, guessImageMimeType, mapExifOrientationToTransform,
} from '@amber/chat-domain';
import type { ExifTransformType } from '@amber/chat-domain';

// ImageKit ORIENTATION 属性是 EXIF 文本值;映射回 Android ExifInterface 整型常量
//   以便复用 HAR 的 mapExifOrientationToTransform(:53-66 逐字移植)
const ORIENTATION_TEXT_TO_INT: Record<string, number> = {
  'Top-left': 1, // ORIENTATION_NORMAL
  'Top-right': 2, // ORIENTATION_FLIP_HORIZONTAL
  'Bottom-right': 3, // ORIENTATION_ROTATE_180
  'Bottom-left': 4, // ORIENTATION_FLIP_VERTICAL
  'Left-top': 5, // ORIENTATION_TRANSPOSE
  'Right-top': 6, // ORIENTATION_ROTATE_90
  'Right-bottom': 7, // ORIENTATION_TRANSVERSE
  'Left-bottom': 8, // ORIENTATION_ROTATE_270
};

const toBase64 = (bytes: Uint8Array): string => new util.Base64Helper().encodeToStringSync(bytes);

// 显式 offset=0 定位读(fd 读取会推进游标,头嗅探后须回到起点)
const readAtStart = (fd: number, size: number): Uint8Array => {
  const buf: ArrayBuffer = new ArrayBuffer(size);
  fileIo.readSync(fd, buf, { offset: 0, length: size });
  return new Uint8Array(buf);
};

// applyExifTransform(:200-224):变换失败保留原图(PixelMap 原地修改,catch 即保留)
const applyExifTransform = async (pixelMap: image.PixelMap, transform: ExifTransformType): Promise<void> => {
  try {
    switch (transform) {
      case 'FLIP_HORIZONTAL': // matrix.setScale(-1f, 1f)
        await pixelMap.flip(true, false);
        break;
      case 'ROTATE_180': // matrix.setRotate(180f)
        await pixelMap.rotate(180);
        break;
      case 'FLIP_VERTICAL': // matrix.setScale(1f, -1f)
        await pixelMap.flip(false, true);
        break;
      case 'TRANSPOSE': // rotate 90 + postScale(-1f, 1f)
        await pixelMap.rotate(90);
        await pixelMap.flip(true, false);
        break;
      case 'ROTATE_90':
        await pixelMap.rotate(90);
        break;
      case 'TRANSVERSE': // rotate 270 + postScale(-1f, 1f)
        await pixelMap.rotate(270);
        await pixelMap.flip(true, false);
        break;
      case 'ROTATE_270':
        await pixelMap.rotate(270);
        break;
      case 'NONE':
        break;
    }
  } catch {
    // runCatching.getOrElse { bitmap }(:221-223):变换失败保留原图
  }
};

// File.compressAndEncode(:138-187)+ guessMimeType(:252-303)+ normalizeByExif(:189-198)
// 返回 data: URL(gif 保持 image/gif;其余强制 image/jpeg)
export const compressImageAsDataUrl = async (uri: string): Promise<string> => {
  const file: fileIo.File = fileIo.openSync(uri, fileIo.OpenMode.READ_ONLY);
  try {
    const stat: fileIo.Stat = fileIo.statSync(file.fd);
    assertPickerImageSize(stat.size, uri);
    // guessMimeType:前 16 字节魔数嗅探(<12 即抛,逐字)
    const header: Uint8Array = readAtStart(file.fd, Math.min(stat.size, 16));
    const mimeType: string = guessImageMimeType(header);

    // :143-146 — GIF 原样直通(可能是动图)
    if (mimeType === 'image/gif') {
      const raw: Uint8Array = readAtStart(file.fd, stat.size);
      return `data:image/gif;base64,${toBase64(raw)}`;
    }

    // :148-155 — decode bounds(ImageSource.getImageInfo)
    // 原生资源(source/packer/pixelMap)必须逐层 release:泄漏累积会耗尽
    // ImageKit 原生内存(ArtifactPorts.ets 同款三层 finally 模式)
    const source: image.ImageSource = image.createImageSource(file.fd);
    try {
      let width: number = 0;
      let height: number = 0;
      try {
        const info: image.ImageInfo = await source.getImageInfo();
        width = info.size.width;
        height = info.size.height;
      } catch {
        throw new Error(`Failed to decode image dimensions: ${uri}`);
      }
      if (width <= 0 || height <= 0) {
        throw new Error(`Failed to decode image dimensions: ${uri}`);
      }

      // :157-171 — 强制采样压缩;decode 失败逐字
      const sampleSize: number = calculateInSampleSize(
        width, height, COMPRESS_MAX_DIMENSION, COMPRESS_MAX_DIMENSION);
      let pixelMap: image.PixelMap;
      try {
        const options: image.DecodingOptions = { sampleSize };
        pixelMap = await source.createPixelMap(options);
      } catch {
        throw new Error(`Failed to decode image: ${uri}`);
      }
      try {
        // normalizeByExif(:189-198):读 EXIF 失败按 NORMAL;变换失败保留原图
        let orientation: number = 1;
        try {
          const text: string = await source.getImageProperty(image.PropertyKey.ORIENTATION);
          const mapped: number | undefined = ORIENTATION_TEXT_TO_INT[text];
          orientation = mapped !== undefined ? mapped : 1;
        } catch {
          orientation = 1;
        }
        await applyExifTransform(pixelMap, mapExifOrientationToTransform(orientation));

        // :174-186 — 强制 JPEG q85
        const packer: image.ImagePacker = image.createImagePacker();
        try {
          const packed: ArrayBuffer = await packer.packToData(
            pixelMap, { format: 'image/jpeg', quality: COMPRESS_JPEG_QUALITY });
          return `data:image/jpeg;base64,${toBase64(new Uint8Array(packed))}`;
        } finally {
          await packer.release();
        }
      } finally {
        await pixelMap.release();
      }
    } finally {
      await source.release();
    }
  } finally {
    // 外层 finally 统一关文件句柄(statSync/断言早抛时也能关闭)
    fileIo.closeSync(file);
  }
};
