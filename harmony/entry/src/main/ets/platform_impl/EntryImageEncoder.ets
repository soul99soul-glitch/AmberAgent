// EntryImageEncoder — chat ImageEncoder Port 的 entry 实现
// Android 基准:ai/util/FileEncoder.kt:68-103
//   - data: 直通(提取 mime)
//   - http(s): 直通
//   - file:// → 读文件 + mime 猜测 + base64(带 data: 前缀)
// 裁剪(PARITY_DEBT 登记):Android 对 file:// 统一压缩(compressAndEncode);
//   鸿蒙 MVP 不压缩,直接原样编码。
// 失败语义:读不到/编码失败即抛错(对齐 "image encoding failure should fail",
//   D-014 missingEncoder 同口径)。

import { fileIo } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';

const mimeFromPath = (path: string): string => {
  const lower: string = path.toLowerCase();
  if (lower.endsWith('.png')) return 'image/png';
  if (lower.endsWith('.jpg') || lower.endsWith('.jpeg')) return 'image/jpeg';
  if (lower.endsWith('.gif')) return 'image/gif';
  if (lower.endsWith('.webp')) return 'image/webp';
  return 'image/png'; // Android guessMimeType 失败即抛;picker 来源统一 png 兜底
};

export const encodeImageForChat = (url: string): string => {
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
};

// 读 picker 返回的 content/file URI → data: URL(选图即刻物化,避免临时授权过期;
//   偏差登记:Android 复制到应用私有目录存 file://,鸿蒙 MVP 直接存 data: URL)
export const readImageAsDataUrl = (uri: string): string => {
  const file = fileIo.openSync(uri, fileIo.OpenMode.READ_ONLY);
  try {
    const stat = fileIo.statSync(file.fd);
    const buf = new ArrayBuffer(stat.size);
    fileIo.readSync(file.fd, buf);
    const base64 = new util.Base64Helper().encodeToStringSync(new Uint8Array(buf));
    return `data:${mimeFromPath(uri)};base64,${base64}`;
  } finally {
    fileIo.closeSync(file);
  }
};
