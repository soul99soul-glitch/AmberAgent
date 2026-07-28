// EntryImageEncoder — chat ImageEncoder Port 的 entry 实现
// Android 基准:ai/util/FileEncoder.kt:68-103
//   - data: 直通(提取 mime)
//   - http(s): 直通
//   - file:// → 读文件 + mime 猜测 + base64(带 data: 前缀)
//   - :97-99 — 任意失败(非 ImageEncodingException)统一包 ImageEncodingException(url, cause)
// 裁剪(PARITY_DEBT 登记):Android 对 file:// 统一压缩(compressAndEncode);
//   鸿蒙 MVP 不压缩,直接原样编码。
// 失败语义:读不到/编码失败即抛 ImageEncodingError(对齐 "image encoding failure
//   should fail",D-014 missingEncoder 同口径);vision fallback 分类器(D-071a)
//   凭此类型直 true。

import { fileIo } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';
import { ImageEncodingError } from '@amber/chat-domain';

const mimeFromPath = (path: string): string => {
  const lower: string = path.toLowerCase();
  if (lower.endsWith('.png')) return 'image/png';
  if (lower.endsWith('.jpg') || lower.endsWith('.jpeg')) return 'image/jpeg';
  if (lower.endsWith('.gif')) return 'image/gif';
  if (lower.endsWith('.webp')) return 'image/webp';
  return 'image/png'; // Android guessMimeType 失败即抛;picker 来源统一 png 兜底
};

// FileEncoder.kt:97-99 recoverCatching — 已是 ImageEncodingError 原样,否则包装
//   (inline 实现:arkts-no-any-unknown 不允许 unknown 参数的 helper)
export const encodeImageForChat = (url: string): string => {
  try {
    if (url.startsWith('data:') || url.startsWith('http://') || url.startsWith('https://')) {
      return url;
    }
    if (url.startsWith('file://')) {
      const file = fileIo.openSync(url, fileIo.OpenMode.READ_ONLY);
      try {
        const stat = fileIo.statSync(file.fd);
        const buf = new ArrayBuffer(stat.size);
        fileIo.readSync(file.fd, buf);
        const base64 = new util.Base64Helper().encodeToStringSync(new Uint8Array(buf));
        return `data:${mimeFromPath(url)};base64,${base64}`;
      } finally {
        fileIo.closeSync(file);
      }
    }
    throw new Error(`unsupported image url scheme: ${url}`);
  } catch (e) {
    if (e instanceof ImageEncodingError) throw e;
    const cause: Error = e instanceof Error ? e : new Error(String(e));
    throw new ImageEncodingError(url, cause);
  }
};

// Claude/Google 请求体需要的详细形态(无前缀 base64 + mime)。
// Android 基准:FileEncoder.kt:68-102 encodeBase64(withPrefix=false)
//   - data: → 剥头(头里提取 mime)
//   - file:// → 读文件 + mime 猜测 + 无前缀 base64
// 偏差(登记 D-046):Android 对 http(s) 会把 URL 原样当 base64 返回(mime 兜底 image/png)
//   —— 对 Claude inline base64 source / Google inlineData 是垃圾数据,属 Android 已知缺陷,
//   鸿蒙不照搬:命中 http(s) 直接抛错(非静默)。聊天选图链路(picker → data: URL 物化)
//   不会产出 http(s) 图片 part。
export interface EntryEncodedImage {
  mimeType: string;
  base64: string;
}

export const encodeImageDetailed = (url: string): EntryEncodedImage => {
  try {
    if (url.startsWith('data:')) {
      const mimeType: string = url.substring('data:'.length, url.indexOf(';'));
      const base64: string = url.substring(url.indexOf('base64,') + 'base64,'.length);
      return { mimeType, base64 };
    }
    if (url.startsWith('file://')) {
      const file = fileIo.openSync(url, fileIo.OpenMode.READ_ONLY);
      try {
        const stat = fileIo.statSync(file.fd);
        const buf = new ArrayBuffer(stat.size);
        fileIo.readSync(file.fd, buf);
        const base64 = new util.Base64Helper().encodeToStringSync(new Uint8Array(buf));
        return { mimeType: mimeFromPath(url), base64 };
      } finally {
        fileIo.closeSync(file);
      }
    }
    throw new Error(`unsupported image url scheme for inline encoding: ${url}`);
  } catch (e) {
    if (e instanceof ImageEncodingError) throw e;
    const cause: Error = e instanceof Error ? e : new Error(String(e));
    throw new ImageEncodingError(url, cause);
  }
};

// 选图物化链路(D-038)已迁至 ImageCompress.ets(D-079b,物化即压缩);
//   本文件仅保留 encode 期 Port 实现。PD-007 残余登记:file:// 私有目录存储、
//   file:// encode 期压缩(鸿蒙压缩在物化点完成,encode 期 file:// 原样)。
