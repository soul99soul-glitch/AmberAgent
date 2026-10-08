// image_compress — 语义钉死(D-079a)
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  MAX_PICKER_IMAGE_BYTES, assertPickerImageSize, calculateInSampleSize, guessImageMimeType,
  mapExifOrientationToTransform,
} from '../main/ets/chat/image_compress.ts';

const bytes = (arr: number[]): Uint8Array => new Uint8Array(arr);

test('picker 图片大小上限在读取前阻断，GIF 同样受限', () => {
  assert.equal(MAX_PICKER_IMAGE_BYTES, 64 * 1024 * 1024);
  assert.doesNotThrow(() => assertPickerImageSize(MAX_PICKER_IMAGE_BYTES, 'file://ok.gif'));
  assert.throws(
    () => assertPickerImageSize(MAX_PICKER_IMAGE_BYTES + 1, 'file://large.gif'),
    /Image exceeds 67108864 bytes: file:\/\/large\.gif/,
  );
});

test('guessImageMimeType: 五族 magic 逐字', () => {
  assert.equal(guessImageMimeType(bytes([0xFF, 0xD8, 0xFF, 0xE0, 0, 0, 0, 0, 0, 0, 0, 0])),
    'image/jpeg');
  assert.equal(guessImageMimeType(
    bytes([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0])), 'image/png');
  // RIFF....WEBP
  assert.equal(guessImageMimeType(
    bytes([0x52, 0x49, 0x46, 0x46, 1, 2, 3, 4, 0x57, 0x45, 0x42, 0x50])), 'image/webp');
  assert.equal(guessImageMimeType(
    bytes([0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0, 0, 0, 0, 0, 0])), 'image/gif');
  assert.equal(guessImageMimeType(
    bytes([0x47, 0x49, 0x46, 0x38, 0x37, 0x61, 0, 0, 0, 0, 0, 0])), 'image/gif');
});

test('guessImageMimeType: ftyp box → heic/avif', () => {
  const mk = (brand: string): Uint8Array => {
    const b: number[] = [0, 0, 0, 0, 0x66, 0x74, 0x79, 0x70];
    for (const c of brand) b.push(c.charCodeAt(0));
    return bytes(b);
  };
  assert.equal(guessImageMimeType(mk('heic')), 'image/heic');
  assert.equal(guessImageMimeType(mk('mif1')), 'image/heic');
  assert.equal(guessImageMimeType(mk('avif')), 'image/avif');
  assert.equal(guessImageMimeType(mk('avis')), 'image/avif');
});

test('guessImageMimeType: 短文件/未知 → 错误文案逐字', () => {
  assert.throws((): string => guessImageMimeType(bytes([1, 2, 3])),
    /File too short to determine MIME type/);
  assert.throws(
    (): string => guessImageMimeType(bytes([0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0, 0, 0, 0, 0, 0])),
    (e: Error): boolean => e.message.startsWith('Failed to guess MIME type: ABCDEF, ')
      && e.message.includes('65,66,67'));
});

test('calculateInSampleSize: 2 倍步进 + 16MP 安全网(:236-250)', () => {
  assert.equal(calculateInSampleSize(1000, 1000), 1); // 已 ≤2048
  assert.equal(calculateInSampleSize(2048, 2048), 1); // 边界不缩
  assert.equal(calculateInSampleSize(2049, 100), 2);
  assert.equal(calculateInSampleSize(4096, 4096), 2); // 4096/2=2048 ≤2048
  assert.equal(calculateInSampleSize(4097, 100), 2); // trunc(4097/2)=2048 不超 → 停
  // 第一个循环先行:8000/2=4000 > 2048 → ×2=4;8000/4=2000 ≤2048 → 停 4(16MP 网未触及)
  assert.equal(calculateInSampleSize(8000, 8000), 4);
  // 16384x16384 → 4: 4096*4096=16.7M > → 8: 2048*2048=4.2M 停
  assert.equal(calculateInSampleSize(16384, 16384), 8);
});

test('mapExifOrientationToTransform: 8 值 + 未知 → NONE(:53-66)', () => {
  assert.equal(mapExifOrientationToTransform(1), 'NONE');
  assert.equal(mapExifOrientationToTransform(0), 'NONE');
  assert.equal(mapExifOrientationToTransform(2), 'FLIP_HORIZONTAL');
  assert.equal(mapExifOrientationToTransform(3), 'ROTATE_180');
  assert.equal(mapExifOrientationToTransform(4), 'FLIP_VERTICAL');
  assert.equal(mapExifOrientationToTransform(5), 'TRANSPOSE');
  assert.equal(mapExifOrientationToTransform(6), 'ROTATE_90');
  assert.equal(mapExifOrientationToTransform(7), 'TRANSVERSE');
  assert.equal(mapExifOrientationToTransform(8), 'ROTATE_270');
  assert.equal(mapExifOrientationToTransform(99), 'NONE');
});
