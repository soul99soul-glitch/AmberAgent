import { fileIo } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';
import type { EditSourceImage } from '../ImageGenTool.ets';

const MIME_BY_EXT: Record<string, string> = {};
MIME_BY_EXT.pdf = 'application/pdf';
MIME_BY_EXT.txt = 'text/plain';
MIME_BY_EXT.md = 'text/markdown';
MIME_BY_EXT.csv = 'text/csv';
MIME_BY_EXT.json = 'application/json';
MIME_BY_EXT.xml = 'application/xml';
MIME_BY_EXT.html = 'text/html';
MIME_BY_EXT.htm = 'text/html';
MIME_BY_EXT.yaml = 'text/yaml';
MIME_BY_EXT.yml = 'text/yaml';
MIME_BY_EXT.log = 'text/plain';
MIME_BY_EXT.doc = 'application/msword';
MIME_BY_EXT.epub = 'application/epub+zip';
MIME_BY_EXT.png = 'image/png';
MIME_BY_EXT.jpg = 'image/jpeg';
MIME_BY_EXT.jpeg = 'image/jpeg';
MIME_BY_EXT.gif = 'image/gif';
MIME_BY_EXT.webp = 'image/webp';
MIME_BY_EXT.heic = 'image/heic';
MIME_BY_EXT.heif = 'image/heif';
MIME_BY_EXT.bmp = 'image/bmp';
MIME_BY_EXT.svg = 'image/svg+xml';
MIME_BY_EXT.zip = 'application/zip';
MIME_BY_EXT.docx = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
MIME_BY_EXT.xlsx = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
MIME_BY_EXT.pptx = 'application/vnd.openxmlformats-officedocument.presentationml.presentation';

const STREAM_CHUNK_BYTES: number = 64 * 1024;

export const mimeByName = (name: string): string => {
  const dot: number = name.lastIndexOf('.');
  if (dot < 0) return 'application/octet-stream';
  const ext: string = name.substring(dot + 1).toLowerCase();
  const mime: string | undefined = MIME_BY_EXT[ext];
  return mime !== undefined ? mime : 'application/octet-stream';
};

// generate_image edit 源格式白名单(gpt-image 编辑上传口径)
const isAllowedEditMime = (mime: string): boolean =>
  mime === 'image/png' || mime === 'image/jpeg' || mime === 'image/webp';

const editMimeExt = (mime: string): string =>
  mime === 'image/jpeg' ? 'jpg' : (mime === 'image/webp' ? 'webp' : 'png');

export function readUriBytes(uri: string): Uint8Array {
  const MAX: number = 10 * 1024 * 1024;
  const file: fileIo.File = fileIo.openSync(uri, fileIo.OpenMode.READ_ONLY);
  try {
    let declaredSize: number = 0;
    try {
      const st: fileIo.Stat = fileIo.statSync(file.fd);
      declaredSize = st.size;
    } catch (_statErr) {
      declaredSize = 0;
    }
    if (declaredSize > MAX) {
      throw new Error(`文件超过 10MB: ${declaredSize} bytes`);
    }
    if (declaredSize > 0) {
      const buf: ArrayBuffer = new ArrayBuffer(declaredSize);
      const firstRead: number | undefined = fileIo.readSync(file.fd, buf, {
        offset: 0,
        length: declaredSize,
      }) as number | undefined;
      // 兼容测试 mock 未返回读字节数，或一次读满的快路径
      if (typeof firstRead !== 'number' || firstRead >= declaredSize) {
        return new Uint8Array(buf);
      }
      if (firstRead <= 0) return new Uint8Array(0);
      const out: Uint8Array = new Uint8Array(declaredSize);
      out.set(new Uint8Array(buf, 0, firstRead), 0);
      let offset: number = firstRead;
      const chunkBuf: ArrayBuffer = new ArrayBuffer(STREAM_CHUNK_BYTES);
      while (offset < declaredSize) {
        const want: number = Math.min(STREAM_CHUNK_BYTES, declaredSize - offset);
        const readLen: number | undefined = fileIo.readSync(file.fd, chunkBuf, {
          offset,
          length: want,
        }) as number | undefined;
        if (typeof readLen !== 'number' || readLen <= 0) break;
        out.set(new Uint8Array(chunkBuf, 0, readLen), offset);
        offset += readLen;
      }
      return offset === declaredSize ? out : out.slice(0, offset);
    }
    // 虚拟/云盘 URI 有时 stat.size === 0，按分块循环读至 EOF
    const chunks: Uint8Array[] = [];
    let total: number = 0;
    let offset: number = 0;
    while (true) {
      const chunkBuf: ArrayBuffer = new ArrayBuffer(STREAM_CHUNK_BYTES);
      const readLen: number | undefined = fileIo.readSync(file.fd, chunkBuf, {
        offset,
        length: STREAM_CHUNK_BYTES,
      }) as number | undefined;
      if (typeof readLen !== 'number' || readLen <= 0) break;
      total += readLen;
      if (total > MAX) {
        throw new Error(`文件超过 10MB: ${total} bytes`);
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
  } finally {
    fileIo.closeSync(file);
  }
}

export async function loadEditSourceBytes(url: string): Promise<EditSourceImage | null> {
  try {
    if (url.startsWith('data:')) {
      const commaIdx: number = url.indexOf(',');
      if (commaIdx < 0) return null;
      const mime: string = url.substring(5, commaIdx).split(';')[0];
      if (!isAllowedEditMime(mime)) return null;
      const bytes: Uint8Array = new util.Base64Helper().decodeSync(url.substring(commaIdx + 1));
      return { bytes, mime, name: `source.${editMimeExt(mime)}` };
    }
    if (url.startsWith('file://')) {
      const mime: string = mimeByName(url);
      if (!isAllowedEditMime(mime)) return null;
      const bytes: Uint8Array = readUriBytes(url);
      const name: string = url.substring(url.lastIndexOf('/') + 1);
      return { bytes, mime, name: name.length > 0 ? name : `source.${editMimeExt(mime)}` };
    }
    return null;
  } catch (_e) {
    return null;
  }
}

export async function loadAttachDataUrl(url: string): Promise<string | null> {
  try {
    if (url.startsWith('data:')) return url;
    if (url.startsWith('file://')) {
      const mime: string = mimeByName(url);
      const bytes: Uint8Array = readUriBytes(url);
      const b64: string = new util.Base64Helper().encodeToStringSync(bytes);
      return `data:${mime};base64,${b64}`;
    }
    return null;
  } catch (_e) {
    return null;
  }
}
