// base64_image_transformer — data:image base64 → 本地 file://(D-076)
// Android 基准:
//   Base64ImageToLocalFileTransformer.kt(全文 17 行)
//   FilesManager.kt:230-265 convertBase64ImagePartToLocalFile + :183-206
//     createChatFilesByByteArrays + :469-480 buildUuidFileName
// 语义钉住:
//   - 只处理 UIMessagePart.Image 且 url startsWith('data:image')
//   - base64 段 = substringAfter('base64,')(未含分隔符 → 整串,与 Kotlin 同)
//   - BitmapFactory.decodeByteArray → null(不可解码)→ **保留原 data url part**
//   - 可解码 → PNG 重编码(quality 100)→ 写 upload/{uuid}.png → part.copy(url)
//   - 非 data image / 非 image part → 原引用
// 偏差:
//   - FilesRepository(Room)跟踪 → entry 侧登记偏差(DB 跟踪 P1;文件落盘忠实)
//   - Koin 全局获取 → deps 注入(reencodeToPng/saveUploadImage)
//   - kotlin Base64.decode 失败抛错 → 同样向外传播(不吞)
import type { UIMessage, UIMessagePart, UIMessagePartImage } from './message.ts';
import type { OutputMessageTransformer, TransformerContext } from './transformer_pipeline.ts';

// kotlin.io.encoding.Base64.decode 语义:非法符号/长度 → 抛错(向外传播不吞);
// 标准 alphabet(+ /), '=' 仅末尾补齐
const B64_ALPHABET: string = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

export const decodeBase64 = (input: string): Uint8Array => {
  let clean: string = '';
  for (const ch of input) {
    if (B64_ALPHABET.includes(ch) || ch === '=') clean += ch;
    else throw new Error(`The symbol '${ch}'(${ch.charCodeAt(0)}) is prohibited`);
  }
  if (clean.length % 4 !== 0) {
    throw new Error('The input array has a wrong length');
  }
  const padEnd: number = clean.endsWith('==') ? 2 : clean.endsWith('=') ? 1 : 0;
  if (clean.indexOf('=') >= 0 && clean.indexOf('=') < clean.length - padEnd) {
    throw new Error('The pad symbol has a wrong position');
  }
  const outLen: number = Math.floor(clean.length / 4) * 3 - padEnd;
  const out: Uint8Array = new Uint8Array(outLen);
  let o: number = 0;
  for (let i: number = 0; i < clean.length; i += 4) {
    const v = (c: string): number => c === '=' ? 0 : B64_ALPHABET.indexOf(c);
    const n: number = (v(clean[i]) << 18) | (v(clean[i + 1]) << 12)
      | (v(clean[i + 2]) << 6) | v(clean[i + 3]);
    if (o < outLen) out[o++] = (n >> 16) & 0xFF;
    if (o < outLen) out[o++] = (n >> 8) & 0xFF;
    if (o < outLen) out[o++] = n & 0xFF;
  }
  return out;
};

// FileFolders.UPLOAD(FilesManager.kt:650 逐字)
export const FILE_FOLDER_UPLOAD: string = 'upload';
// MAX_CHAT_ATTACHMENT_BYTES(FilesManager.kt:45)
export const MAX_CHAT_ATTACHMENT_BYTES: number = 128 * 1024 * 1024;

export interface Base64ImageDeps {
  // BitmapFactory.decodeByteArray + compressToPng 等价:
  //   不可解码 → null(保留 data url);可解码 → PNG 字节(quality 100)
  reencodeToPng: (bytes: Uint8Array) => Promise<Uint8Array | null>;
  // createChatFilesByByteArrays 单文件:128MB require + 写 upload/{uuid}.png
  //   → file:// uri 字符串
  saveUploadImage: (pngBytes: Uint8Array) => Promise<string>;
}

// substringAfter('base64,'):含分隔符 → 其后;不含 → 整串(Kotlin 语义)
export const extractBase64Payload = (url: string): string => {
  const idx: number = url.indexOf('base64,');
  return idx >= 0 ? url.substring(idx + 'base64,'.length) : url;
};

export const isDataImageUrl = (url: string): boolean => url.startsWith('data:image');

// FilesManager.kt:230-265 单消息忠实(顺序 await,Kotlin map 顺序语义)
export const convertBase64ImagePartToLocalFile = async (
  message: UIMessage, deps: Base64ImageDeps,
): Promise<UIMessage> => {
  const parts: UIMessagePart[] = [];
  for (const part of message.parts) {
    if (part.type !== 'image' || !isDataImageUrl(part.url)) {
      parts.push(part);
      continue;
    }
    const imagePart: UIMessagePartImage = part;
    const sourceBytes: Uint8Array = decodeBase64(extractBase64Payload(imagePart.url));
    const pngBytes: Uint8Array | null = await deps.reencodeToPng(sourceBytes);
    if (pngBytes === null) {
      // undecodable → keeping data url part(FilesManager.kt:240-246)
      parts.push(part);
      continue;
    }
    const localUrl: string = await deps.saveUploadImage(pngBytes);
    parts.push({ ...imagePart, url: localUrl });
  }
  return { ...message, parts };
};

export const createBase64ImageToLocalFileTransformer = (
  deps: Base64ImageDeps,
): OutputMessageTransformer => ({
  onGenerationFinish: async (
    _ctx: TransformerContext, messages: UIMessage[],
  ): Promise<UIMessage[]> => {
    const out: UIMessage[] = [];
    for (const message of messages) {
      out.push(await convertBase64ImagePartToLocalFile(message, deps));
    }
    return out;
  },
});
