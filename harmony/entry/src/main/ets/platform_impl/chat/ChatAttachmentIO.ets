import { fileIo } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';
import type { EditSourceImage } from '../ImageGenTool.ets';

const MIME_BY_EXT: Record<string, string> = {};
MIME_BY_EXT.pdf = 'application/pdf';
MIME_BY_EXT.txt = 'text/plain';
MIME_BY_EXT.md = 'text/markdown';
MIME_BY_EXT.csv = 'text/csv';
MIME_BY_EXT.json = 'application/json';
MIME_BY_EXT.doc = 'application/msword';
MIME_BY_EXT.png = 'image/png';
MIME_BY_EXT.jpg = 'image/jpeg';
MIME_BY_EXT.jpeg = 'image/jpeg';
MIME_BY_EXT.gif = 'image/gif';
MIME_BY_EXT.webp = 'image/webp';
MIME_BY_EXT.zip = 'application/zip';
MIME_BY_EXT.docx = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
MIME_BY_EXT.xlsx = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
MIME_BY_EXT.pptx = 'application/vnd.openxmlformats-officedocument.presentationml.presentation';

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
    const st: fileIo.Stat = fileIo.statSync(file.fd);
    if (st.size > MAX) {
      throw new Error(`文件超过 10MB: ${st.size} bytes`);
    }
    const buf: ArrayBuffer = new ArrayBuffer(st.size);
    fileIo.readSync(file.fd, buf, { offset: 0, length: st.size });
    return new Uint8Array(buf);
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
