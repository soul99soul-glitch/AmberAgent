// DocumentSupport — DocumentReaderDeps entry 实现(D-074)
// Android 基准: DocumentAsPromptTransformer.kt:73-128
//   - document.url.toUri().toFile():非 file scheme → 抛 IllegalStateException
//     → runCatching null → '[ERROR, invalid file uri]'(entry:null 返回)
//   - File.exists()/isFile()/length()/absolutePath → fileIo.statSync 映射
//   - bufferedReader 字符读取 → 整读 + UTF-8 decode + 截断(语义等价;
//     64MB 上限已由上层判定)
// 偏差(登记):
//   - PDF 解析未移植(MuPDF native 平台阻断,spike 登记)→ parsePdf 不提供,
//     HAR 侧抛错走 '[ERROR, failed to read file]'(与 Android 解析失败同文案);
//     DOCX/PPTX/EPUB 已落地(D-112/D-113/D-114)
//   - OfficeNativeSwitch(native 桥)不移植
import { fileIo } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';
import type { UIMessagePartDocument } from '@amber/chat-domain';
import type {
  DocumentFileHandle, DocumentReaderDeps, ZipEntryRecord, ZipEntryTextProvider,
} from '@amber/chat-domain';
import {
  isLikelyTextFile, parseDocxFromZip, parseEpubFromZip, parsePptxFromZip,
  readDocumentContent, readZipEntries, readZipEntryText,
} from '@amber/chat-domain';
import {
  createEntryXmlPull, createEntryXmlPullNoNs, inflateRawEntry, parseDocxEntry, parseEpubEntry,
  parsePptxEntry,
} from './EntryDocumentParser.ets';

const S_IFMT: number = 0o170000;
const S_IFREG: number = 0o100000;

const statOne = (document: UIMessagePartDocument): Promise<DocumentFileHandle | null> => {
  // toUri().toFile():非 file scheme → 非法 uri(null)
  if (!document.url.startsWith('file://')) return Promise.resolve(null);
  const path: string = document.url.substring('file://'.length);
  try {
    const st = fileIo.statSync(path);
    const handle: DocumentFileHandle = {
      exists: true,
      isFile: (st.mode & S_IFMT) === S_IFREG,
      sizeBytes: st.size,
      absolutePath: path,
    };
    return Promise.resolve(handle);
  } catch {
    // File("") / 不存在 → exists=false(走 '[ERROR, file not found]')
    const missing: DocumentFileHandle = {
      exists: false,
      isFile: false,
      sizeBytes: 0,
      absolutePath: path,
    };
    return Promise.resolve(missing);
  }
};

const readText = (handle: DocumentFileHandle, maxCharsPlusOne: number): Promise<string> => {
  const file = fileIo.openSync(handle.absolutePath, fileIo.OpenMode.READ_ONLY);
  try {
    const st = fileIo.statSync(file.fd);
    const buf = new ArrayBuffer(st.size);
    fileIo.readSync(file.fd, buf);
    const decoder = util.TextDecoder.create('utf-8');
    const text: string = decoder.decodeToString(new Uint8Array(buf));
    return Promise.resolve(text.substring(0, maxCharsPlusOne));
  } finally {
    fileIo.closeSync(file);
  }
};

// DOCX/PPTX/EPUB 已接线(D-112/D-113/D-114);PDF 不提供(MuPDF native 平台阻断,
//   HAR 侧抛错走 '[ERROR, failed to read file]' 同 Android 解析失败文案路径)
export const createEntryDocumentReader = (): DocumentReaderDeps => ({
  statFile: statOne,
  readTextFile: readText,
  parseDocx: parseDocxEntry,
  parsePptx: parsePptxEntry,
  parseEpub: parseEpubEntry,
});

// ===== F44 文档附件预览(只读) =====
// 聊天文档 chip 打开路径最小实现:把 data:*;base64 / file:// 文本与
//   DOCX/PPTX/EPUB 解析为预览文本。约束:
//   - 不写临时真实文件:data: 直接在内存字节上走占位/解析;file:// 只读原文件
//   - 不外网读取任意 URL:仅接受 data: 与 file://,其余(含 http/https)直接拒绝
//   - PDF 明确不支持(未移植 MuPDF native):返回显式提示,不静默走二进制占位
//   - 输入上限 10MiB(与 ChatPage picker readUriBytes 同口径,更保守);
//     data: 先按 base64 长度估算再解码,file:// 先 stat 校验大小再 open/read
//   - 输出截断 100k chars(一屏预览足够,避免把 10MiB 文本灌进 UI)
// 复用域层:isLikelyTextFile(分类)/readDocumentContent(mime 分发 + 错误文案)/
//   parseDocxFromZip·parsePptxFromZip·parseEpubFromZip(inflateRawEntry +
//   createEntryXmlPull/createEntryXmlPullNoNs)。
// 范围说明:既有 createEntryDocumentReader.statFile 只支持 file://,故「发送」
//   data: 文档仍走 '[ERROR, invalid file uri]' 缺陷;本函数只服务预览,不改动
//   该 Port 的现有语义(待后续单独修)。

export const MAX_PREVIEW_INPUT_BYTES: number = 10 * 1024 * 1024;
export const MAX_PREVIEW_CHARS: number = 100 * 1024;

const MIME_PDF: string = 'application/pdf';
const MIME_DOCX: string = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
const MIME_PPTX: string = 'application/vnd.openxmlformats-officedocument.presentationml.presentation';
const MIME_EPUB: string = 'application/epub+zip';

const hasExtension = (fileName: string, ext: string): boolean => {
  const dot: number = fileName.lastIndexOf('.');
  return dot >= 0 && fileName.substring(dot + 1).toLowerCase() === ext;
};

// picker 的 mimeByName 未收录 epub 等 → 按扩展名兜底,保证 office 解析可达
const effectiveMime = (document: UIMessagePartDocument): string => {
  if (document.mime === MIME_PDF || hasExtension(document.fileName, 'pdf')) return MIME_PDF;
  if (document.mime === MIME_DOCX || hasExtension(document.fileName, 'docx')) return MIME_DOCX;
  if (document.mime === MIME_PPTX || hasExtension(document.fileName, 'pptx')) return MIME_PPTX;
  if (document.mime === MIME_EPUB || hasExtension(document.fileName, 'epub')) return MIME_EPUB;
  return document.mime;
};

// data:*;base64:先估算解码长度(每 4 字符至多 3 字节)再解码,超限不分配
const decodeBase64DataUrl = (url: string): Uint8Array | null => {
  const comma: number = url.indexOf(',');
  if (comma < 0) return null;
  const meta: string = url.substring('data:'.length, comma).toLowerCase();
  if (!meta.endsWith(';base64')) return null;
  let payload: string = url.substring(comma + 1);
  const estimated: number = Math.floor(payload.length / 4) * 3;
  if (estimated > MAX_PREVIEW_INPUT_BYTES) {
    throw new Error(`data url exceeds preview limit (${MAX_PREVIEW_INPUT_BYTES} bytes)`);
  }
  if (payload.indexOf('%') >= 0) payload = decodeURIComponent(payload);
  const bytes: Uint8Array = new util.Base64Helper().decodeSync(payload);
  if (bytes.length > MAX_PREVIEW_INPUT_BYTES) {
    throw new Error(`data url exceeds preview limit (${MAX_PREVIEW_INPUT_BYTES} bytes)`);
  }
  return bytes;
};

// file://:先 stat 校验普通文件与大小,再 open/read(不读超限文件)
const readLocalPreviewBytes = (url: string): Uint8Array => {
  const path: string = url.substring('file://'.length);
  const st: fileIo.Stat = fileIo.statSync(path);
  if ((st.mode & S_IFMT) !== S_IFREG) throw new Error('not a regular file');
  if (st.size > MAX_PREVIEW_INPUT_BYTES) {
    throw new Error(`file exceeds preview limit (${st.size} bytes)`);
  }
  if (st.size === 0) return new Uint8Array(0);
  const file: fileIo.File = fileIo.openSync(path, fileIo.OpenMode.READ_ONLY);
  try {
    const buf: ArrayBuffer = new ArrayBuffer(st.size);
    fileIo.readSync(file.fd, buf);
    return new Uint8Array(buf);
  } finally {
    fileIo.closeSync(file);
  }
};

const truncatePreview = (content: string): string =>
  content.length > MAX_PREVIEW_CHARS
    ? `${content.substring(0, MAX_PREVIEW_CHARS)}\n` +
      `[TRUNCATED: preview exceeds ${MAX_PREVIEW_CHARS} characters]`
    : content;

export const readDocumentPreview = async (
  document: UIMessagePartDocument,
): Promise<string> => {
  if (effectiveMime(document) === MIME_PDF) {
    return `[ERROR, pdf preview not supported: ${document.fileName}]`;
  }
  const office: boolean = effectiveMime(document) === MIME_DOCX
    || effectiveMime(document) === MIME_PPTX || effectiveMime(document) === MIME_EPUB;
  if (!office && !isLikelyTextFile(document)) {
    return `[ERROR, preview unsupported for this file type: ${document.fileName}]`;
  }
  let bytes: Uint8Array;
  try {
    if (document.url.startsWith('data:')) {
      const decoded: Uint8Array | null = decodeBase64DataUrl(document.url);
      if (decoded === null) return `[ERROR, unsupported data url: ${document.fileName}]`;
      bytes = decoded;
    } else if (document.url.startsWith('file://')) {
      bytes = readLocalPreviewBytes(document.url);
    } else {
      // 非 data:/file:// 一律拒绝,绝不外网读取
      return `[ERROR, unsupported file uri: ${document.fileName}]`;
    }
  } catch (e) {
    const msg: string = e instanceof Error ? e.message : String(e);
    return `[ERROR, failed to read file: ${document.fileName} (${msg})]`;
  }
  // 复用 readDocumentContent 的 mime 分发/错误文案:注入内存字节端口;
  //   DOCX = ZipEntryTextProvider(按需解压 word/document.xml)+ parseDocxFromZip;
  //   PPTX/EPUB = zipBytes + inflateRawEntry;文本 = UTF-8 decode。
  let cached: ZipEntryRecord[] | null = null;
  const entryText: ZipEntryTextProvider = async (entryName: string): Promise<string | null> => {
    if (cached === null) cached = readZipEntries(bytes);
    const rec: ZipEntryRecord | undefined =
      cached.find((e: ZipEntryRecord): boolean => e.name === entryName);
    if (rec === undefined) return null;
    return await readZipEntryText(bytes, rec, inflateRawEntry);
  };
  const deps: DocumentReaderDeps = {
    statFile: (): Promise<DocumentFileHandle | null> => {
      const handle: DocumentFileHandle = {
        exists: true, isFile: true, sizeBytes: bytes.length, absolutePath: document.fileName,
      };
      return Promise.resolve(handle);
    },
    readTextFile: (_handle: DocumentFileHandle, maxCharsPlusOne: number): Promise<string> =>
      Promise.resolve(
        util.TextDecoder.create('utf-8').decodeToString(bytes).substring(0, maxCharsPlusOne)),
    parseDocx: (): Promise<string> => parseDocxFromZip(entryText, createEntryXmlPull),
    parsePptx: (): Promise<string> =>
      parsePptxFromZip(bytes, inflateRawEntry, createEntryXmlPull),
    parseEpub: (): Promise<string> =>
      parseEpubFromZip(bytes, inflateRawEntry, createEntryXmlPull, createEntryXmlPullNoNs),
  };
  // mime 按扩展名归一(epub 等 picker mime 缺失时仍能命中 parse*)
  const dispatch: UIMessagePartDocument = {
    type: 'document', url: document.url, fileName: document.fileName,
    mime: effectiveMime(document), metadata: document.metadata,
  };
  return truncatePreview(await readDocumentContent(dispatch, deps));
};
