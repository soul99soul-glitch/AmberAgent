// ArtifactPorts — WorkspaceArtifactTools entry 平台端口(D-125)
// Android 锚点(WorkspaceArtifactTools.kt):
//   openConnection(:460-467)— HttpURLConnection + User-Agent 'AmberAgent/0.8' +
//     instanceFollowRedirects=true → @kit.NetworkKit http(NetStack DNS,与
//     RcpHttpClient 同选型;重定向由 NetStack 内部跟随)
//   request(:432-458)— headerFields null 键过滤 + values joinToString(', ')
//   BitmapFactory.decodeByteArray(bounds)/Bitmap.scale/compress →
//     @kit.ImageKit ImageSource/PixelMap/ImagePacker
//   ExifInterface.TAG_ORIENTATION(0-8 int)→ PropertyKey.ORIENTATION 字符串映射
//   ZipOutputStream DEFLATE → zlib deflateInit2(-15) raw 流
// 偏差登记:
//   - connection.url(重定向后最终 URL):http kit 不回传最终 URL → 原样回
//     请求 url(登记)
//   - BitmapFactory.Options.outMimeType → ImageInfo 无 mime 字段 → 魔数嗅探
//     (PNG/JPEG/GIF/WEBP/BMP;不识 → '')
//   - Bitmap.createScaledBitmap(filter=true)→ PixelMap.scale 双线性
//   - encodeBitmap WEBP_LOSSY(API 30+)→ PackingOption 'image/webp'

import { http } from '@kit.NetworkKit';
import { image } from '@kit.ImageKit';
import { util } from '@kit.ArkTS';
import { zlib } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import type {
  ArtifactHttpPort, ArtifactHttpRequest, ArtifactHttpResponse,
  ArtifactImagePort, ArtifactImageInfo, DeflateRawPort,
} from '@amber/chat-domain';

const TAG: string = 'ArtifactPorts';
const DOMAIN: number = 0xA125;

// ===== ArtifactHttpPort =====

const mapMethod = (method: string): http.RequestMethod => {
  switch (method) {
    case 'POST': return http.RequestMethod.POST;
    case 'PUT': return http.RequestMethod.PUT;
    case 'PATCH': return http.RequestMethod.PATCH;
    case 'DELETE': return http.RequestMethod.DELETE;
    case 'HEAD': return http.RequestMethod.HEAD;
    default: return http.RequestMethod.GET;
  }
};

export const createArtifactHttpPort = (): ArtifactHttpPort =>
  async (req: ArtifactHttpRequest): Promise<ArtifactHttpResponse> => {
    const httpRequest: http.HttpRequest = http.createHttp();
    // Content-Length 预检:响应头阶段发现超限立即 destroy 中断接收,
    // 不等整包收完才截断(内存峰值防护)
    let oversizeMessage: string | null = null;
    const maxBytes: number | undefined = req.maxResponseBytes;
    if (maxBytes !== undefined && maxBytes > 0) {
      httpRequest.on('headersReceive', (headerFields: Object): void => {
        // header 名大小写不敏感;chunked 无长度时本预检退化为收后截断(平台
        // requestInStream 流式收体属后续接线,登记)
        const rec: Record<string, string> = headerFields as Record<string, string>;
        let lenRaw: string | undefined;
        Object.keys(rec).forEach((name: string): void => {
          if (name.toLowerCase() === 'content-length') lenRaw = String(rec[name]);
        });
        if (lenRaw === undefined) return;
        const len: number = Number.parseInt(lenRaw, 10);
        if (Number.isFinite(len) && len > maxBytes) {
          oversizeMessage =
            `Response Content-Length ${len} exceeds limit ${maxBytes} bytes`;
          try { httpRequest.destroy(); } catch { /* 已销毁 */ }
        }
      });
    }
    try {
      const header: Record<string, string> = {};
      Object.keys(req.headers).forEach((name: string): void => {
        header[name] = req.headers[name];
      });
      // :466 — setRequestProperty('User-Agent', 'AmberAgent/0.8') 逐字
      header['User-Agent'] = 'AmberAgent/0.8';
      const options: http.HttpRequestOptions = {
        method: mapMethod(req.method),
        header,
        expectDataType: http.HttpDataType.ARRAY_BUFFER,
        connectTimeout: req.timeoutMs,
        readTimeout: req.timeoutMs,
        usingCache: false,
      };
      if (req.body !== null) {
        options.extraData = req.body;
      }
      const resp: http.HttpResponse = await httpRequest.request(req.url, options);
      // destroy 后 SDK 可能 resolve(而非 reject)空/部分响应 → 必须复查
      if (oversizeMessage !== null) {
        throw new Error(oversizeMessage);
      }
      const headers: Record<string, string> = {};
      if (resp.header !== undefined && resp.header !== null) {
        const rec: Record<string, string> = resp.header as Record<string, string>;
        Object.keys(rec).forEach((name: string): void => {
          // headerFields values joinToString(', ') — kit 侧已拼为单串
          headers[name] = String(rec[name]);
        });
      }
      let body: Uint8Array = new Uint8Array(0);
      if (resp.result instanceof ArrayBuffer) {
        body = new Uint8Array(resp.result);
      } else if (typeof resp.result === 'string') {
        body = new Uint8Array(util.TextEncoder.create('utf-8').encodeInto(resp.result));
      }
      return {
        status: resp.responseCode,
        // 登记:http kit 不回传重定向后最终 URL → 原请求 URL
        url: req.url,
        headers,
        body,
      };
    } catch (e) {
      if (oversizeMessage !== null) {
        throw new Error(oversizeMessage);
      }
      throw e instanceof Error ? e : new Error(String(e));
    } finally {
      try { httpRequest.destroy(); } catch { /* 已销毁(oversize 路径) */ }
    }
  };

// ===== ArtifactImagePort =====

// outMimeType 魔数嗅探(BitmapFactory 边界解码 → ImageInfo 无 mime,登记)
const sniffMime = (bytes: Uint8Array): string => {
  const startsWith = (sig: number[]): boolean => {
    if (bytes.length < sig.length) return false;
    for (let i: number = 0; i < sig.length; i++) {
      if (bytes[i] !== sig[i]) return false;
    }
    return true;
  };
  if (startsWith([0x89, 0x50, 0x4e, 0x47])) return 'image/png';
  if (startsWith([0xff, 0xd8, 0xff])) return 'image/jpeg';
  if (startsWith([0x47, 0x49, 0x46, 0x38])) return 'image/gif';
  if (startsWith([0x42, 0x4d])) return 'image/bmp';
  if (bytes.length >= 12 && bytes[0] === 0x52 && bytes[1] === 0x49 &&
    bytes[2] === 0x46 && bytes[3] === 0x46 && bytes[8] === 0x57 &&
    bytes[9] === 0x45 && bytes[10] === 0x42 && bytes[11] === 0x50) {
    return 'image/webp';
  }
  return '';
};

// ExifInterface ORIENTATION int(0-8)→ HarmonyOS ORIENTATION 属性字符串
const EXIF_STRING_TO_INT: Record<string, number> = {
  'Top-left': 1, 'Top-right': 2, 'Bottom-right': 3, 'Bottom-left': 4,
  'Left-top': 5, 'Right-top': 6, 'Right-bottom': 7, 'Left-bottom': 8,
};

export const createArtifactImagePort = (): ArtifactImagePort => ({
  info: async (bytes: Uint8Array): Promise<ArtifactImageInfo> => {
    const buf: ArrayBuffer = bytes.buffer.slice(
      bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
    const source: image.ImageSource = image.createImageSource(buf);
    try {
      const imgInfo: image.ImageInfo = await source.getImageInfo();
      let exif: number = 0;
      try {
        const raw: string = await source.getImageProperty(image.PropertyKey.ORIENTATION);
        const hit: number | undefined = EXIF_STRING_TO_INT[raw];
        if (hit !== undefined) exif = hit;
      } catch (_e) {
        exif = 0; // ExifInterface 读取失败 → ORIENTATION_UNDEFINED(:631-637)
      }
      return {
        width: imgInfo.size.width,
        height: imgInfo.size.height,
        mimeType: sniffMime(bytes),
        exifOrientation: exif,
      };
    } finally {
      await source.release();
    }
  },
  convert: async (
    bytes: Uint8Array, targetWidth: number | null, targetHeight: number | null,
    format: string, quality: number,
  ): Promise<Uint8Array> => {
    const buf: ArrayBuffer = bytes.buffer.slice(
      bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
    const source: image.ImageSource = image.createImageSource(buf);
    try {
      const pixelMap: image.PixelMap = await source.createPixelMap();
      try {
        if (targetWidth !== null && targetHeight !== null) {
          const info: image.ImageInfo = await pixelMap.getImageInfo();
          // scaleBitmapIfNeeded 目标尺寸由域层 artifactScaleDims 计算(逐字)
          await pixelMap.scale(
            targetWidth / info.size.width, targetHeight / info.size.height,
            image.AntiAliasingLevel.HIGH);
        }
        const packFormat: string = format === 'jpg' || format === 'jpeg'
          ? 'image/jpeg'
          : format === 'webp' ? 'image/webp' : 'image/png';
        const packer: image.ImagePacker = image.createImagePacker();
        try {
          const packed: ArrayBuffer = await packer.packToData(
            pixelMap, { format: packFormat, quality });
          return new Uint8Array(packed);
        } finally {
          await packer.release();
        }
      } finally {
        await pixelMap.release();
      }
    } finally {
      await source.release();
    }
  },
});

// ===== DeflateRawPort(archive_create;deflateInit2 负 windowBits raw 流) =====

export const createDeflateRawPort = (): DeflateRawPort =>
  async (data: Uint8Array): Promise<Uint8Array> => {
    const inCopy: Uint8Array = new Uint8Array(data);
    // zlib compressBound = sourceLen + (sourceLen>>12) + (sourceLen>>14) +
    //   (sourceLen>>25) + 13
    const cap: number = Math.max(
      data.length + (data.length >> 12) + (data.length >> 14) +
      (data.length >> 25) + 64, 256);
    const outBuffer: ArrayBuffer = new ArrayBuffer(cap);
    const strm: zlib.ZStream = {
      nextIn: inCopy.buffer as ArrayBuffer,
      availableIn: inCopy.length,
      nextOut: outBuffer,
      availableOut: cap,
    };
    const zip = zlib.createZipSync();
    const init: zlib.ReturnStatus = await zip.deflateInit2(
      strm, zlib.CompressLevel.COMPRESS_LEVEL_DEFAULT_COMPRESSION,
      zlib.CompressMethod.DEFLATED,
      -15, zlib.MemLevel.MEM_LEVEL_DEFAULT,
      zlib.CompressStrategy.COMPRESS_STRATEGY_DEFAULT_STRATEGY);
    if (init !== zlib.ReturnStatus.OK) {
      throw new Error(`deflateInit2 failed: ${init}`);
    }
    try {
      const status: zlib.ReturnStatus =
        await zip.deflate(strm, zlib.CompressFlushMode.FINISH);
      const current: zlib.ZStream = await zip.getZStream();
      if (status !== zlib.ReturnStatus.STREAM_END && status !== zlib.ReturnStatus.OK) {
        throw new Error(`deflate failed: ${status}`);
      }
      if (status === zlib.ReturnStatus.OK && (current.availableOut ?? 0) === 0) {
        throw new Error('deflate output overflow (compressBound too small)');
      }
      // API 12 的 ZStream 入参不会回写执行后的状态，必须重新 getZStream()。
      // 官方 FAQ 明确指出直接读取 strm.totalOut 会一直为 0；输出字节仍从
      // 调用方持有的缓冲区读取，因为设备实现不保证 getZStream() 回传 nextOut。
      const total: number = current.totalOut ?? 0;
      return new Uint8Array(outBuffer.slice(0, total));
    } finally {
      await zip.deflateEnd(strm);
    }
  };

// 启动自检日志占位(端口装配排障用;不含敏感数据)
export const logArtifactPortsReady = (): void => {
  hilog.info(DOMAIN, TAG, 'artifact ports ready');
};
