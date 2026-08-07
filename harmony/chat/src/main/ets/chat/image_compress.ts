// image_compress — 图片压缩纯逻辑(D-079a,PD-007)
// Android 基准:ai/util/FileEncoder.kt
//   supportedTypes(:14-21) / HEIF/AVIF brands(:24-30)
//   compressAndEncode(:138-187) / calculateInSampleSize(:236-250)
//   guessMimeType(:252-303) / mapExifOrientationToTransform(:53-66)
// 纯函数子集:mime 嗅探/采样率/EXIF 映射;PixelMap 管线在 entry(D-079b)

// FileEncoder.kt:140-141
export const COMPRESS_MAX_DIMENSION: number = 2048;
export const COMPRESS_JPEG_QUALITY: number = 85;
export const MAX_PICKER_IMAGE_BYTES: number = 64 * 1024 * 1024;

export const assertPickerImageSize = (sizeBytes: number, uri: string): void => {
  if (sizeBytes > MAX_PICKER_IMAGE_BYTES) {
    throw new Error(`Image exceeds ${MAX_PICKER_IMAGE_BYTES} bytes: ${uri}`);
  }
};
export const SUPPORTED_IMAGE_TYPES: readonly string[] = Object.freeze([
  'image/jpeg', 'image/png', 'image/gif', 'image/webp', 'image/heic', 'image/avif',
]);

// ISO BMFF ftyp brands(:24-30)
export const HEIF_BRANDS: readonly string[] = Object.freeze([
  'heic', 'heix', 'hevc', 'hevx', 'heim', 'heis', 'hevm', 'hevs', 'mif1', 'msf1',
]);
export const AVIF_BRANDS: readonly string[] = Object.freeze(['avif', 'avis']);

// ===== guessMimeType(:252-303 全文忠实) =====

const asciiOf = (bytes: Uint8Array, from: number, to: number): string => {
  let s: string = '';
  for (let i: number = from; i < to; i++) s += String.fromCharCode(bytes[i] & 0x7F);
  return s;
};

const PNG_MAGIC: readonly number[] = Object.freeze([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]);

// 输入 ≥16 字节头;失败抛错(错误文案逐字)
export const guessImageMimeType = (bytes: Uint8Array): string => {
  if (bytes.length < 12) {
    throw new Error('File too short to determine MIME type');
  }
  // HEIC/AVIF:ISO BMFF ftyp box at offset 4
  const ftypTag: string = asciiOf(bytes, 4, 8);
  if (ftypTag === 'ftyp') {
    const brand: string = asciiOf(bytes, 8, 12);
    if (HEIF_BRANDS.includes(brand)) return 'image/heic';
    if (AVIF_BRANDS.includes(brand)) return 'image/avif';
  }
  // JPEG:0xFF 0xD8
  if (bytes[0] === 0xFF && bytes[1] === 0xD8) return 'image/jpeg';
  // PNG
  let isPng: boolean = bytes.length >= 8;
  if (isPng) {
    for (let i: number = 0; i < 8; i++) {
      if (bytes[i] !== PNG_MAGIC[i]) {
        isPng = false;
        break;
      }
    }
  }
  if (isPng) return 'image/png';
  // WebP:RIFF....WEBP
  if (asciiOf(bytes, 0, 4) === 'RIFF' && asciiOf(bytes, 8, 12) === 'WEBP') return 'image/webp';
  // GIF:GIF89a / GIF87a
  const header: string = asciiOf(bytes, 0, 6);
  if (header === 'GIF89a' || header === 'GIF87a') return 'image/gif';
  const byteList: string = [...bytes].map((b: number): string => String(b)).join(',');
  throw new Error(`Failed to guess MIME type: ${header}, ${byteList}`);
};

// ===== calculateInSampleSize(:236-250 逐行忠实) =====
// Kotlin Int 除法向零截断;width/height 均为正时与 Math.floor 一致
export const calculateInSampleSize = (
  width: number, height: number,
  reqWidth: number = COMPRESS_MAX_DIMENSION, reqHeight: number = COMPRESS_MAX_DIMENSION,
): number => {
  let inSampleSize: number = 1;
  while (Math.trunc(height / inSampleSize) > reqHeight
    || Math.trunc(width / inSampleSize) > reqWidth) {
    inSampleSize *= 2;
  }
  // Safety net:解码像素 ≤ ~16MP(:243-246)
  const maxPixels: number = 16000000;
  while (Math.trunc(width / inSampleSize) * Math.trunc(height / inSampleSize) > maxPixels) {
    inSampleSize *= 2;
  }
  return inSampleSize;
};

// ===== EXIF(FileEncoder.kt:42-66;ExifInterface 常量值) =====

export const EXIF_ORIENTATION_UNDEFINED: number = 0;
export const EXIF_ORIENTATION_NORMAL: number = 1;
export const EXIF_ORIENTATION_FLIP_HORIZONTAL: number = 2;
export const EXIF_ORIENTATION_ROTATE_180: number = 3;
export const EXIF_ORIENTATION_FLIP_VERTICAL: number = 4;
export const EXIF_ORIENTATION_TRANSPOSE: number = 5;
export const EXIF_ORIENTATION_ROTATE_90: number = 6;
export const EXIF_ORIENTATION_TRANSVERSE: number = 7;
export const EXIF_ORIENTATION_ROTATE_270: number = 8;

export type ExifTransformType =
  | 'NONE' | 'FLIP_HORIZONTAL' | 'ROTATE_180' | 'FLIP_VERTICAL'
  | 'TRANSPOSE' | 'ROTATE_90' | 'TRANSVERSE' | 'ROTATE_270';

export const mapExifOrientationToTransform = (orientation: number): ExifTransformType => {
  switch (orientation) {
    case EXIF_ORIENTATION_FLIP_HORIZONTAL: return 'FLIP_HORIZONTAL';
    case EXIF_ORIENTATION_ROTATE_180: return 'ROTATE_180';
    case EXIF_ORIENTATION_FLIP_VERTICAL: return 'FLIP_VERTICAL';
    case EXIF_ORIENTATION_TRANSPOSE: return 'TRANSPOSE';
    case EXIF_ORIENTATION_ROTATE_90: return 'ROTATE_90';
    case EXIF_ORIENTATION_TRANSVERSE: return 'TRANSVERSE';
    case EXIF_ORIENTATION_ROTATE_270: return 'ROTATE_270';
    case EXIF_ORIENTATION_NORMAL:
    case EXIF_ORIENTATION_UNDEFINED:
      return 'NONE';
    default:
      return 'NONE';
  }
};
