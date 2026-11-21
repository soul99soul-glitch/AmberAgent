// ImageCompress — 选图/拍照压缩与多格式兜底编码(D-079b)
// 兼容鸿蒙真机常见坑位：
//   1) 华为截屏（PNG > 2048px）/ HEIF / WebP 传 sampleSize > 1 易解码失败 → 优先 desiredSize，逐级降级
//   2) 仅用前 16 字节嗅探区分 GIF 动图，未知魔数不再提前阻断，交给系统 ImageKit 尝试解码
//   3) MediaLibrary / 云端 URI 对 fd 游标或 stat.size === 0 敏感 → 支持循环读 + ArrayBuffer 建 ImageSource 兜底
import { fileIo } from '@kit.CoreFileKit';
import { image } from '@kit.ImageKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { util } from '@kit.ArkTS';
import {
  COMPRESS_JPEG_QUALITY, COMPRESS_MAX_DIMENSION, MAX_PICKER_IMAGE_BYTES, assertPickerImageSize,
  calculateInSampleSize, guessImageMimeType, mapExifOrientationToTransform,
} from '@amber/chat-domain';
import type { ExifTransformType } from '@amber/chat-domain';

const HILOG_DOMAIN: number = 0x0001;
const HILOG_TAG: string = 'Chat.ImageCompress';
const FALLBACK_RAW_MAX_BYTES: number = 8 * 1024 * 1024;
const STREAM_CHUNK_BYTES: number = 64 * 1024;

const ORIENTATION_TEXT_TO_INT: Record<string, number> = {
  'Top-left': 1,
  'Top-right': 2,
  'Bottom-right': 3,
  'Bottom-left': 4,
  'Left-top': 5,
  'Right-top': 6,
  'Right-bottom': 7,
  'Left-bottom': 8,
};

const toBase64 = (bytes: Uint8Array): string => new util.Base64Helper().encodeToStringSync(bytes);

const readBytesFromFd = (fd: number, declaredSize: number, maxBytes: number, uri: string): Uint8Array => {
  if (declaredSize > maxBytes) {
    assertPickerImageSize(declaredSize, uri);
  }
  if (declaredSize > 0) {
    const buf: ArrayBuffer = new ArrayBuffer(declaredSize);
    const firstRead: number = fileIo.readSync(fd, buf, { offset: 0, length: declaredSize });
    if (firstRead >= declaredSize) return new Uint8Array(buf);
    if (firstRead <= 0) return new Uint8Array(0);
    const out: Uint8Array = new Uint8Array(declaredSize);
    out.set(new Uint8Array(buf, 0, firstRead), 0);
    let offset: number = firstRead;
    const chunkBuf: ArrayBuffer = new ArrayBuffer(STREAM_CHUNK_BYTES);
    while (offset < declaredSize) {
      const want: number = Math.min(STREAM_CHUNK_BYTES, declaredSize - offset);
      const readLen: number = fileIo.readSync(fd, chunkBuf, { offset, length: want });
      if (readLen <= 0) break;
      out.set(new Uint8Array(chunkBuf, 0, readLen), offset);
      offset += readLen;
    }
    return offset === declaredSize ? out : out.slice(0, offset);
  }
  // 虚拟/云端 URI 有时 stat.size === 0，按分块循环读至 EOF
  const chunks: Uint8Array[] = [];
  let total: number = 0;
  let offset: number = 0;
  while (true) {
    const chunkBuf: ArrayBuffer = new ArrayBuffer(STREAM_CHUNK_BYTES);
    const readLen: number = fileIo.readSync(fd, chunkBuf, { offset, length: STREAM_CHUNK_BYTES });
    if (readLen <= 0) break;
    total += readLen;
    if (total > maxBytes) {
      assertPickerImageSize(total, uri);
    }
    chunks.push(new Uint8Array(chunkBuf, 0, readLen));
    offset += readLen;
  }
  const merged: Uint8Array = new Uint8Array(total);
  let cursor: number = 0;
  for (const c of chunks) {
    merged.set(c, cursor);
    cursor += c.byteLength;
  }
  return merged;
};

const tryGuessMime = (header: Uint8Array): string => {
  if (header.byteLength < 12) return '';
  try {
    return guessImageMimeType(header);
  } catch (_e) {
    return '';
  }
};

const applyExifTransform = async (pixelMap: image.PixelMap, transform: ExifTransformType): Promise<void> => {
  try {
    switch (transform) {
      case 'FLIP_HORIZONTAL':
        await pixelMap.flip(true, false);
        break;
      case 'ROTATE_180':
        await pixelMap.rotate(180);
        break;
      case 'FLIP_VERTICAL':
        await pixelMap.flip(false, true);
        break;
      case 'TRANSPOSE':
        await pixelMap.rotate(90);
        await pixelMap.flip(true, false);
        break;
      case 'ROTATE_90':
        await pixelMap.rotate(90);
        break;
      case 'TRANSVERSE':
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
    // 变换失败保留原图
  }
};

const computeTargetSize = (width: number, height: number): image.Size => {
  const longest: number = Math.max(width, height);
  if (longest <= COMPRESS_MAX_DIMENSION) {
    return { width, height };
  }
  const ratio: number = COMPRESS_MAX_DIMENSION / longest;
  return {
    width: Math.max(1, Math.round(width * ratio)),
    height: Math.max(1, Math.round(height * ratio)),
  };
};

const decodePixelMapResilient = async (
  source: image.ImageSource, width: number, height: number, uri: string,
): Promise<image.PixelMap> => {
  const target: image.Size = computeTargetSize(width, height);
  const needsResize: boolean = target.width < width || target.height < height;

  // 层级 1：desiredSize 硬件等比缩放（全格式兼容，避免 PNG/HEIF 截屏 sampleSize>1 报错）
  try {
    const opts: image.DecodingOptions = needsResize ? { desiredSize: target } : {};
    return await source.createPixelMap(opts);
  } catch (e1) {
    hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'createPixelMap(desiredSize) failed: %{public}s',
      e1 instanceof Error ? e1.message : String(e1));
  }

  // 层级 2：sampleSize 采样解码
  if (needsResize) {
    try {
      const sampleSize: number = calculateInSampleSize(
        width, height, COMPRESS_MAX_DIMENSION, COMPRESS_MAX_DIMENSION);
      return await source.createPixelMap({ sampleSize });
    } catch (e2) {
      hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'createPixelMap(sampleSize) failed: %{public}s',
        e2 instanceof Error ? e2.message : String(e2));
    }
  }

  // 层级 3：无参默认解码后原地 scale
  try {
    const fullMap: image.PixelMap = await source.createPixelMap();
    if (needsResize) {
      try {
        const scaleX: number = target.width / width;
        const scaleY: number = target.height / height;
        await fullMap.scale(scaleX, scaleY);
      } catch (_scaleErr) {
        // 缩放失败保留原分辨率
      }
    }
    return fullMap;
  } catch (e3) {
    hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'createPixelMap(default) failed: %{public}s',
      e3 instanceof Error ? e3.message : String(e3));
    throw new Error(`Failed to decode image: ${uri}`);
  }
};

const encodeSourceToDataUrl = async (source: image.ImageSource, uri: string): Promise<string> => {
  let width: number = 0;
  let height: number = 0;
  try {
    const info: image.ImageInfo = await source.getImageInfo();
    width = info.size.width;
    height = info.size.height;
  } catch (err) {
    hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'getImageInfo failed: %{public}s',
      err instanceof Error ? err.message : String(err));
    throw new Error(`Failed to decode image dimensions: ${uri}`);
  }
  if (width <= 0 || height <= 0) {
    throw new Error(`Failed to decode image dimensions: ${uri}`);
  }

  const pixelMap: image.PixelMap = await decodePixelMapResilient(source, width, height, uri);
  try {
    let orientation: number = 1;
    try {
      const text: string = await source.getImageProperty(image.PropertyKey.ORIENTATION);
      const mapped: number | undefined = ORIENTATION_TEXT_TO_INT[text];
      orientation = mapped !== undefined ? mapped : 1;
    } catch {
      orientation = 1;
    }
    await applyExifTransform(pixelMap, mapExifOrientationToTransform(orientation));

    const packer: image.ImagePacker = image.createImagePacker();
    try {
      try {
        const packed: ArrayBuffer = await packer.packToData(
          pixelMap, { format: 'image/jpeg', quality: COMPRESS_JPEG_QUALITY });
        return `data:image/jpeg;base64,${toBase64(new Uint8Array(packed))}`;
      } catch (jpegErr) {
        hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'packToData(jpeg) failed, retry png: %{public}s',
          jpegErr instanceof Error ? jpegErr.message : String(jpegErr));
        const packedPng: ArrayBuffer = await packer.packToData(
          pixelMap, { format: 'image/png', quality: 90 });
        return `data:image/png;base64,${toBase64(new Uint8Array(packedPng))}`;
      }
    } finally {
      await packer.release();
    }
  } finally {
    await pixelMap.release();
  }
};

const isDirectPassthroughMime = (mime: string): boolean =>
  mime === 'image/jpeg' || mime === 'image/png' || mime === 'image/webp' || mime === 'image/gif';

export const compressImageAsDataUrl = async (uri: string): Promise<string> => {
  const file: fileIo.File = fileIo.openSync(uri, fileIo.OpenMode.READ_ONLY);
  try {
    let declaredSize: number = 0;
    try {
      const stat: fileIo.Stat = fileIo.statSync(file.fd);
      declaredSize = stat.size;
    } catch (_statErr) {
      declaredSize = 0;
    }
    if (declaredSize > 0) {
      assertPickerImageSize(declaredSize, uri);
    }

    // 读取头部 16 字节用于检测 GIF 动图直通（不阻断未知魔数）
    const headLen: number = declaredSize > 0 ? Math.min(declaredSize, 16) : 16;
    const headBuf: ArrayBuffer = new ArrayBuffer(headLen);
    const headRead: number = fileIo.readSync(file.fd, headBuf, { offset: 0, length: headLen });
    const header: Uint8Array = headRead > 0 ? new Uint8Array(headBuf, 0, headRead) : new Uint8Array(0);
    const guessedMime: string = tryGuessMime(header);

    if (guessedMime === 'image/gif') {
      const rawGif: Uint8Array = readBytesFromFd(file.fd, declaredSize, MAX_PICKER_IMAGE_BYTES, uri);
      if (rawGif.byteLength === 0) throw new Error('图片文件为空');
      return `data:image/gif;base64,${toBase64(rawGif)}`;
    }

    // 路径 1：直接用 fd 建 ImageSource（零拷贝快路径）
    let fdError: Error | null = null;
    try {
      const fdSource: image.ImageSource = image.createImageSource(file.fd);
      try {
        return await encodeSourceToDataUrl(fdSource, uri);
      } finally {
        await fdSource.release();
      }
    } catch (e) {
      fdError = e instanceof Error ? e : new Error(String(e));
      hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'fd image decode failed, fallback to buffer: %{public}s', fdError.message);
    }

    // 路径 2：读入 ArrayBuffer 重建 ImageSource（规避 MediaLibrary / 云端 fd 偏移与 seek 异常）
    const rawBytes: Uint8Array = readBytesFromFd(file.fd, declaredSize, MAX_PICKER_IMAGE_BYTES, uri);
    if (rawBytes.byteLength === 0) {
      throw fdError ?? new Error('图片文件内容为空（可能尚未从云端下载完成）');
    }
    const rawMime: string = guessedMime.length > 0 ? guessedMime : tryGuessMime(rawBytes.slice(0, 16));
    if (rawMime === 'image/gif') {
      return `data:image/gif;base64,${toBase64(rawBytes)}`;
    }

    try {
      const arrayBuf: ArrayBuffer = rawBytes.buffer.slice(
        rawBytes.byteOffset, rawBytes.byteOffset + rawBytes.byteLength) as ArrayBuffer;
      const bufSource: image.ImageSource = image.createImageSource(arrayBuf);
      try {
        return await encodeSourceToDataUrl(bufSource, uri);
      } finally {
        await bufSource.release();
      }
    } catch (bufErr) {
      hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'buffer image decode failed: %{public}s',
        bufErr instanceof Error ? bufErr.message : String(bufErr));
      // 路径 3：若已是标准 Web 图片且体积可控，直接原图 base64 兜底，避免白白失败
      if (isDirectPassthroughMime(rawMime) && rawBytes.byteLength <= FALLBACK_RAW_MAX_BYTES) {
        return `data:${rawMime};base64,${toBase64(rawBytes)}`;
      }
      throw bufErr instanceof Error ? bufErr : (fdError ?? new Error(String(bufErr)));
    }
  } finally {
    fileIo.closeSync(file);
  }
};
