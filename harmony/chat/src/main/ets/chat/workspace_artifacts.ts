// workspace_artifacts — WorkspaceArtifactTools.kt(862 行全文)域层移植(D-125)
//
// Android 基准(逐字锚点,app/src/main/java/app/amber/feature/tools/
//   WorkspaceArtifactTools.kt):
//   11 件:getTools(:48-60);http_request(:62-83)/download_file(:86-125)/
//   archive_list(:127-149)/archive_extract(:151-181)/archive_create(:183-215)/
//   pdf_read(:217-240)/pdf_render_page(:242-268)/office_read(:270-311)/
//   image_info(:313-338)/image_convert(:340-385)/ocr_image(:387-406);
//   trackArtifactTool(:408-430)/request(:432-458)/openConnection(:460-467)/
//   requireHttpUrl(:469-472)/listArchiveEntries(:474-494)/extractArchive
//   (:496-547)/createZip(:549-565)/addZipEntry(:567-572)/withTempWorkspaceFile
//   (:574-585)/parseXlsxText(:606-620)/stripXml(:622-629)/scaleBitmapIfNeeded
//   (:639-645)/encodeBitmap(:647-661)/entryJson(:663-667)/tar 读(:669-692)/
//   safeArchiveTarget(:694-700)/archiveType(:702-710)/mimeFromName(:712-715)/
//   fileNameFromUrl(:717-718)/safeBaseName(:720-721)/safeFileName(:723-724)/
//   流读取器(:726-790)/schema helpers(:792-824)/limit(:826-827)/
//   jsonArrayStrings(:829-830)/previewText(:832-838)/常量(:854-861)
//   LocalTools.kt:122-126(workspace_files 门内第二组)
//
// 偏差适配登记:
//   - HttpURLConnection → ArtifactHttpPort 注入(entry = @kit.NetworkKit http,
//     NetStack DNS;User-Agent 'AmberAgent/0.8' 在 entry 设置,:466 锚点);
//     headerFields Map<String,List<String>> joinToString(', ') → 端口侧拼好
//   - Bitmap/BitmapFactory/ExifInterface/PdfRenderer → ArtifactImagePort 注入
//     (entry = @kit.ImageKit ImageSource/ImagePacker);缩放公式
//     scaleBitmapIfNeeded 留在域层 artifactScaleDims(逐字,entry 复用)
//   - GZIPInputStream → 域层 gzip 头剥离(flag 感知)+ InflateRawPort
//     (trailer ISIZE = 声明解压长度)
//   - ZipOutputStream(DEFLATE + data descriptor 流式布局)→ 域层 zip 写器:
//     method 8 + 尺寸预计算(无 data descriptor)——字节布局不同,归档功能
//     等价(任意解压器可读),登记
//   - withTempWorkspaceFile(cacheDir 暂存):harmony 解析器直吃字节 → 省
//     暂存;64MB 上限(MAX_WORKSPACE_TEMP_FILE_BYTES)保留
//   - pdf_read/pdf_render_page:MuPDF native + PdfRenderer 平台阻断(既有
//     spike 登记)→ 工具保留在表内(协议等价),execute 显式抛错(非静默,
//     经 track fail 事件),不返回假成功
//   - OfficeNativeSwitch(native bridge 回退路由)→ 直连内置解析器
//     (parseDocxFromZip/parsePptxFromZip,D-112/114 既有;xlsx = 本文件
//     parseXlsxText 逐字)
//   - MimeTypeMap.getSingleton() 全表 → 常用扩展名子集表(表外 octet-stream,
//     登记)

import type { JsonObject, JsonValue } from './json.ts';
import { makeAgentTool } from './tool.ts';
import type { AgentTool, InputSchemaObj } from './tool.ts';
import type { UIMessagePart } from './message.ts';
import type { AgentToolActivityStore } from './tool_activity.ts';
import {
  PosixWorkspaceManager, toolInputString, toolInputRequiredString, toolInputBoolean,
  toolInputInt, normalizeWorkspacePath,
} from './workspace.ts';
import {
  readZipEntries, extractZipEntryData, readZipEntryText, utf8Decode,
} from './zip_archive.ts';
import type { ZipEntryRecord, InflateRawPort } from './zip_archive.ts';
import { parseDocxFromZip } from './docx_parser.ts';
import { parsePptxFromZip } from './pptx_parser.ts';
import type { XmlPullFactory, ZipEntryTextProvider } from './xml_pull.ts';

// ===== 常量(:854-861) =====

export const MAX_HTTP_BODY_BYTES: number = 512 * 1024;
export const MAX_DOWNLOAD_BYTES: number = 128 * 1024 * 1024;
export const MAX_ARCHIVE_BYTES: number = 256 * 1024 * 1024;
// 归档总输出上限:单条目 64MB 上限挡不住「大量 64MB 条目」的解压炸弹;
// tar.gz 的 ISIZE 是 gzip trailer 声明(mod 2^32),同样不可信
const MAX_ARCHIVE_OUTPUT_BYTES: number = 256 * 1024 * 1024;

export const MAX_ARCHIVE_ENTRY_BYTES: number = 64 * 1024 * 1024;
export const MAX_IMAGE_BYTES: number = 64 * 1024 * 1024;
export const MAX_WORKSPACE_TEMP_FILE_BYTES: number = 64 * 1024 * 1024;

// ===== 平台端口 =====

export interface ArtifactHttpRequest {
  method: string;
  url: string;
  headers: Record<string, string>;
  body: string | null;
  timeoutMs: number;
  // 响应体上限(字节):平台在响应头阶段按 Content-Length 预检,
  // 超限立即中断,避免整包收完才发现超限的内存峰值
  maxResponseBytes?: number;
}

export interface ArtifactHttpResponse {
  status: number;
  // connection.url(重定向后最终 URL,:450)
  url: string;
  // headerFields:null 键(状态行)已滤;values joinToString(', ')(:451-454)
  headers: Record<string, string>;
  body: Uint8Array;
}

export type ArtifactHttpPort = (req: ArtifactHttpRequest) => Promise<ArtifactHttpResponse>;

export interface ArtifactImageInfo {
  width: number;
  height: number;
  mimeType: string; // BitmapFactory.Options.outMimeType;解码失败端口抛错
  // ExifInterface TAG_ORIENTATION;读不到 → ORIENTATION_UNDEFINED = 0
  exifOrientation: number;
}

export interface ArtifactImagePort {
  info(bytes: Uint8Array): Promise<ArtifactImageInfo>;
  // decode → (targetWidth/Height 非 null 时缩放)→ encode(format, quality);
  // 解码失败 → 抛(域层 image_info 前置 info 调用转成 'Unable to decode' 文案)
  convert(
    bytes: Uint8Array, targetWidth: number | null, targetHeight: number | null,
    format: string, quality: number,
  ): Promise<Uint8Array>;
}

// raw deflate 压缩 Port(entry = zlib deflateInit2 负 windowBits)
export type DeflateRawPort = (data: Uint8Array) => Promise<Uint8Array>;

export interface WorkspaceArtifactToolsDeps {
  workspaceManager: PosixWorkspaceManager;
  activityStore: AgentToolActivityStore;
  http: ArtifactHttpPort;
  image: ArtifactImagePort;
  inflateRaw: InflateRawPort;
  deflateRaw: DeflateRawPort;
  newXmlParser: XmlPullFactory;
}

// ===== schema/helpers(:792-830) =====

const stringProp = (description: string): JsonObject => ({ type: 'string', description });
const booleanProp = (description: string): JsonObject => ({ type: 'boolean', description });
const integerProp = (description: string): JsonObject => ({ type: 'integer', description });
const objectProp = (description: string): JsonObject => ({ type: 'object', description });
const arrayProp = (description: string): JsonObject => ({
  type: 'array', description, items: { type: 'string' },
});

const textJson = (payload: JsonObject): UIMessagePart[] =>
  [{ type: 'text', text: JSON.stringify(payload), metadata: null }];

// :826-827
const inputLimit = (input: JsonValue, name: string, def: number, max: number): number => {
  const v: number = toolInputInt(input, name) ?? def;
  return Math.min(Math.max(v, 1), max);
};

// :829-830 — jsonPrimitive.contentOrNull 非 blank 才收
const jsonArrayStrings = (value: JsonValue | undefined): string[] => {
  if (!Array.isArray(value)) return [];
  const out: string[] = [];
  value.forEach((v: JsonValue): void => {
    if (typeof v === 'string' && v.length > 0 && v.trim().length > 0) out.push(v);
    else if (typeof v === 'number' || typeof v === 'boolean') {
      const s: string = String(v);
      if (s.trim().length > 0) out.push(s);
    }
  });
  return out;
};

// :832-838
const previewText = (parts: UIMessagePart[]): string =>
  parts.map((p: UIMessagePart): string => p.type === 'text' ? p.text : JSON.stringify(p))
    .join('\n')
    .slice(-1600);

// ===== URL/文件名(:469-472/:712-724) =====

// java.net.URI.getScheme 逐字:首个 ':' 前段;'http'/'https' 精确小写比较
//   ('HTTP://x' → scheme 'HTTP' → 拒绝,与 Kotlin == 比较同语义)
export const requireHttpUrl = (url: string): void => {
  const idx: number = url.indexOf(':');
  const scheme: string = idx >= 0 ? url.substring(0, idx) : '';
  if (scheme !== 'http' && scheme !== 'https') {
    throw new Error('Only http and https URLs are allowed');
  }
};

export const fileNameFromUrl = (url: string): string => {
  // URI(url).path:去 scheme://authority,截 ?/#
  let rest: string = url;
  const schemeIdx: number = rest.indexOf('://');
  if (schemeIdx >= 0) rest = rest.substring(schemeIdx + 3);
  const pathStart: number = rest.indexOf('/');
  let path: string = pathStart >= 0 ? rest.substring(pathStart) : '';
  const q: number = path.search(/[?#]/);
  if (q >= 0) path = path.substring(0, q);
  const name: string = path.substring(path.lastIndexOf('/') + 1);
  return name.length > 0 ? name : 'download.bin';
};

export const safeFileName = (name: string): string => {
  const cleaned: string = name.replace(/[^A-Za-z0-9._-]/g, '_');
  return cleaned.length > 0 ? cleaned : 'file.bin';
};

export const safeBaseName = (path: string): string => {
  const base: string = path.substring(path.lastIndexOf('/') + 1);
  const dot: number = base.lastIndexOf('.');
  const stem: string = dot > 0 ? base.substring(0, dot) : base;
  return safeFileName(stem.length > 0 ? stem : 'artifact');
};

// MimeTypeMap 常用子集(表外 application/octet-stream,:712-715)
const MIME_TABLE: Record<string, string> = {
  txt: 'text/plain', md: 'text/markdown', json: 'application/json',
  csv: 'text/csv', xml: 'text/xml', html: 'text/html', htm: 'text/html',
  css: 'text/css', js: 'application/javascript', mjs: 'text/javascript',
  png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif',
  webp: 'image/webp', bmp: 'image/bmp', svg: 'image/svg+xml',
  pdf: 'application/pdf', zip: 'application/zip', tar: 'application/x-tar',
  gz: 'application/gzip', mp3: 'audio/mpeg', mp4: 'video/mp4',
  wav: 'audio/wav', doc: 'application/msword',
  docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  pptx: 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
};

export const mimeFromName = (name: string): string => {
  const dot: number = name.lastIndexOf('.');
  const ext: string = dot >= 0 ? name.substring(dot + 1).toLowerCase() : '';
  const hit: string | undefined = MIME_TABLE[ext];
  return hit !== undefined ? hit : 'application/octet-stream';
};

// ===== gzip/tar 底层 =====

// GZIPInputStream 头剥离(flag 感知:FEXTRA/FNAME/FCOMMENT/FHCRC)+ trailer
//   ISIZE → raw deflate 段 + 声明解压长度
export const gunzipSplit = (bytes: Uint8Array): { payload: Uint8Array; isize: number } => {
  if (bytes.length < 18 || bytes[0] !== 0x1f || bytes[1] !== 0x8b || bytes[2] !== 8) {
    throw new Error('Not in GZIP format');
  }
  const flags: number = bytes[3];
  let p: number = 10;
  if ((flags & 4) !== 0) { // FEXTRA
    const xlen: number = bytes[p] | (bytes[p + 1] << 8);
    p += 2 + xlen;
  }
  if ((flags & 8) !== 0) { // FNAME
    while (p < bytes.length && bytes[p] !== 0) p++;
    p++;
  }
  if ((flags & 16) !== 0) { // FCOMMENT
    while (p < bytes.length && bytes[p] !== 0) p++;
    p++;
  }
  if ((flags & 2) !== 0) p += 2; // FHCRC
  const u32le = (off: number): number =>
    (bytes[off] | (bytes[off + 1] << 8) | (bytes[off + 2] << 16) |
      (bytes[off + 3] << 24)) >>> 0;
  const isize: number = u32le(bytes.length - 4);
  return { payload: bytes.subarray(p, bytes.length - 8), isize };
};

interface TarHeader {
  name: string;
  size: number;
  directory: boolean;
}

// :672-683 — 512 头块;全零 → null(结束)
const readTarHeader = (bytes: Uint8Array, off: number): TarHeader | null => {
  if (off + 512 > bytes.length) return null;
  let allZero: boolean = true;
  for (let i: number = off; i < off + 512; i++) {
    if (bytes[i] !== 0) { allZero = false; break; }
  }
  if (allZero) return null;
  const field = (start: number, len: number): string => {
    let end: number = start;
    while (end < start + len && bytes[off + end] !== 0) end++;
    return utf8Decode(bytes.subarray(off + start, off + end)).trim();
  };
  const name: string = field(0, 100);
  const sizeText: string = field(124, 12);
  const size: number = sizeText.length > 0 ? parseInt(sizeText, 8) : 0;
  const type: number = bytes[off + 156];
  return {
    name,
    size: Number.isNaN(size) ? 0 : size,
    directory: type === 0x35 /* '5' */ || name.endsWith('/'),
  };
};

// :692
const alignTarSize = (size: number): number => Math.floor((size + 511) / 512) * 512;

export type ArchiveType = 'zip' | 'tar' | 'targz';

// :702-710
export const archiveTypeOf = (path: string): ArchiveType => {
  const lower: string = path.toLowerCase();
  if (lower.endsWith('.zip')) return 'zip';
  if (lower.endsWith('.tar.gz') || lower.endsWith('.tgz')) return 'targz';
  if (lower.endsWith('.tar')) return 'tar';
  throw new Error(`Unsupported archive type: ${path}`);
};

// :694-700 — Zip Slip/逃逸阻断
export const safeArchiveTarget = (destination: string, entryName: string): string => {
  const cleanName: string = entryName.trim().replace(/\\/g, '/');
  if (cleanName.trim().length === 0 || cleanName.startsWith('/') || cleanName.indexOf('../') >= 0) {
    throw new Error(`Unsafe archive entry: ${entryName}`);
  }
  return normalizeWorkspacePath(`${normalizeWorkspacePath(destination)}/${cleanName}`);
};

// :663-667
const archiveEntryJson = (name: string, directory: boolean, size: number): JsonObject => {
  const obj: JsonObject = { path: name, directory };
  if (size >= 0) obj['size_bytes'] = size;
  return obj;
};

// ===== CRC32(域层纯实现;zip 写器用) =====

const CRC_TABLE: Uint32Array = ((): Uint32Array => {
  const table: Uint32Array = new Uint32Array(256);
  for (let n: number = 0; n < 256; n++) {
    let c: number = n;
    for (let k: number = 0; k < 8; k++) {
      c = (c & 1) !== 0 ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
    }
    table[n] = c >>> 0;
  }
  return table;
})();

export const crc32 = (data: Uint8Array): number => {
  let crc: number = 0xffffffff;
  for (let i: number = 0; i < data.length; i++) {
    crc = CRC_TABLE[(crc ^ data[i]) & 0xff] ^ (crc >>> 8);
  }
  return (crc ^ 0xffffffff) >>> 0;
};

// ===== zip 写器(method 8;尺寸预计算,无 data descriptor — 布局偏差登记) =====

const utf8Encode = (text: string): Uint8Array => {
  // 手写 UTF-8 编码(域层无 TextEncoder 依赖,与 zip_archive utf8Decode 对称)
  const out: number[] = [];
  for (let i: number = 0; i < text.length; i++) {
    let cp: number = text.charCodeAt(i);
    if (cp >= 0xd800 && cp <= 0xdbff && i + 1 < text.length) {
      const lo: number = text.charCodeAt(i + 1);
      if (lo >= 0xdc00 && lo <= 0xdfff) {
        cp = 0x10000 + ((cp - 0xd800) << 10) + (lo - 0xdc00);
        i++;
      }
    }
    if (cp < 0x80) {
      out.push(cp);
    } else if (cp < 0x800) {
      out.push(0xc0 | (cp >> 6), 0x80 | (cp & 0x3f));
    } else if (cp < 0x10000) {
      out.push(0xe0 | (cp >> 12), 0x80 | ((cp >> 6) & 0x3f), 0x80 | (cp & 0x3f));
    } else {
      out.push(0xf0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3f),
        0x80 | ((cp >> 6) & 0x3f), 0x80 | (cp & 0x3f));
    }
  }
  return new Uint8Array(out);
};

export interface ZipWriteEntry {
  name: string;
  data: Uint8Array;
  compression?: 'stored' | 'deflate';
}

export const buildZipBytes = async (
  entries: ZipWriteEntry[], deflateRaw: DeflateRawPort, dosTime: number, dosDate: number,
): Promise<Uint8Array> => {
  const chunks: Uint8Array[] = [];
  const central: Uint8Array[] = [];
  let offset: number = 0;
  const u16le = (v: number): number[] => [v & 0xff, (v >> 8) & 0xff];
  const u32le = (v: number): number[] =>
    [v & 0xff, (v >> 8) & 0xff, (v >> 16) & 0xff, ((v >> 24) & 0xff)];

  for (const entry of entries) {
    const nameBytes: Uint8Array = utf8Encode(entry.name);
    const crc: number = crc32(entry.data);
    const method: number = entry.compression === 'stored' ? 0 : 8;
    const compressed: Uint8Array = method === 0 ? entry.data : await deflateRaw(entry.data);
    const local: number[] = [
      ...u32le(0x04034b50), ...u16le(20), ...u16le(0x0800), ...u16le(method),
      ...u16le(dosTime), ...u16le(dosDate), ...u32le(crc),
      ...u32le(compressed.length), ...u32le(entry.data.length),
      ...u16le(nameBytes.length), ...u16le(0),
    ];
    const localArr: Uint8Array = new Uint8Array(local);
    chunks.push(localArr, nameBytes, compressed);
    const cd: number[] = [
      ...u32le(0x02014b50), ...u16le(20), ...u16le(20), ...u16le(0x0800),
      ...u16le(method), ...u16le(dosTime), ...u16le(dosDate), ...u32le(crc),
      ...u32le(compressed.length), ...u32le(entry.data.length),
      ...u16le(nameBytes.length), ...u16le(0), ...u16le(0), ...u16le(0),
      ...u16le(0), ...u32le(0), ...u32le(offset),
    ];
    central.push(new Uint8Array(cd), nameBytes);
    offset += localArr.length + nameBytes.length + compressed.length;
  }
  let cdSize: number = 0;
  central.forEach((c: Uint8Array): void => { cdSize += c.length; });
  const eocd: number[] = [
    ...u32le(0x06054b50), ...u16le(0), ...u16le(0),
    ...u16le(entries.length), ...u16le(entries.length),
    ...u32le(cdSize), ...u32le(offset), ...u16le(0),
  ];
  const all: Uint8Array[] = [...chunks, ...central, new Uint8Array(eocd)];
  let total: number = 0;
  all.forEach((c: Uint8Array): void => { total += c.length; });
  const out: Uint8Array = new Uint8Array(total);
  let pos: number = 0;
  all.forEach((c: Uint8Array): void => {
    out.set(c, pos);
    pos += c.length;
  });
  return out;
};

// DOS 时间/日期(Date → zip 字段;工具调用侧注入当前时刻)
export const dosTimeDate = (d: Date): { time: number; date: number } => ({
  time: (d.getHours() << 11) | (d.getMinutes() << 5) | Math.floor(d.getSeconds() / 2),
  date: (((d.getFullYear() - 1980) << 9) | ((d.getMonth() + 1) << 5) | d.getDate()),
});

// ===== xlsx(:606-629) =====

export const stripXml = (xmlText: string): string =>
  xmlText.replace(/<[^>]+>/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/\s+/g, ' ')
    .trim();

// :606-620 — ZipFile.entries() = CD 序(zip_archive readZipEntries 同序)
export const parseXlsxText = async (
  zipBytes: Uint8Array, inflateRaw: InflateRawPort,
): Promise<string> => {
  let text: string = '';
  const entries: ZipEntryRecord[] = readZipEntries(zipBytes);
  const targets: ZipEntryRecord[] = entries.filter((e: ZipEntryRecord): boolean =>
    e.name === 'xl/sharedStrings.xml' || e.name.startsWith('xl/worksheets/sheet'));
  for (const entry of targets) {
    text += `## ${entry.name}\n`;
    const xmlText: string = await readZipEntryText(zipBytes, entry, inflateRaw);
    if (xmlText.length > MAX_ARCHIVE_ENTRY_BYTES) {
      throw new Error(`${entry.name} exceeds the ${MAX_ARCHIVE_ENTRY_BYTES} byte limit`);
    }
    text += `${stripXml(xmlText)}\n`;
  }
  return text;
};

// ===== 图片缩放(:639-645 逐字) =====

export interface ArtifactScaleResult {
  width: number;
  height: number;
  scaled: boolean; // false → 原尺寸(Android 返回原 bitmap)
}

export const artifactScaleDims = (
  width: number, height: number, maxWidth: number | null, maxHeight: number | null,
): ArtifactScaleResult => {
  const widthLimit: number = maxWidth !== null && maxWidth > 0 ? maxWidth : width;
  const heightLimit: number = maxHeight !== null && maxHeight > 0 ? maxHeight : height;
  const scale: number = Math.min(widthLimit / width, heightLimit / height, 1);
  if (scale >= 1) return { width, height, scaled: false };
  return {
    width: Math.floor(width * scale),
    height: Math.floor(height * scale),
    scaled: true,
  };
};

// ===== office_read 路由(:283-300) =====

export const OFFICE_UNSUPPORTED = (path: string): string => `Unsupported Office extension: ${path}`;

const officeText = async (
  path: string, bytes: Uint8Array, deps: WorkspaceArtifactToolsDeps,
): Promise<string> => {
  const dot: number = path.lastIndexOf('.');
  const ext: string = dot >= 0 ? path.substring(dot + 1).toLowerCase() : '';
  if (ext === 'docx') {
    let cached: ZipEntryRecord[] | null = null;
    const entryText: ZipEntryTextProvider = async (name: string): Promise<string | null> => {
      if (cached === null) cached = readZipEntries(bytes);
      const rec: ZipEntryRecord | undefined =
        cached.find((e: ZipEntryRecord): boolean => e.name === name);
      if (rec === undefined) return null;
      return await readZipEntryText(bytes, rec, deps.inflateRaw);
    };
    return await parseDocxFromZip(entryText, deps.newXmlParser);
  }
  if (ext === 'pptx') {
    return await parsePptxFromZip(bytes, deps.inflateRaw, deps.newXmlParser);
  }
  if (ext === 'xlsx') {
    return await parseXlsxText(bytes, deps.inflateRaw);
  }
  throw new Error(OFFICE_UNSUPPORTED(path));
};

// ===== PDF 平台阻断(MuPDF/PdfRenderer spike 登记;显式抛错非静默) =====

export const PDF_BLOCKED_MESSAGE: string =
  'PDF support is not available in this build: the bundled MuPDF parser and ' +
  'PdfRenderer have not been ported to HarmonyOS yet (registered platform blocker)';

// ===== createWorkspaceArtifactTools(11 件;:48-60 序) =====

export const createWorkspaceArtifactTools = (
  deps: WorkspaceArtifactToolsDeps,
): AgentTool[] => {
  const mgr: PosixWorkspaceManager = deps.workspaceManager;

  // :408-430
  const track = async (
    toolName: string, title: string, input: JsonValue, runtime: string,
    block: () => Promise<UIMessagePart[]>,
  ): Promise<UIMessagePart[]> => {
    const toolCallId: string = deps.activityStore.startTool(
      toolName, title, JSON.stringify(input).slice(0, 1200), runtime, '/workspace');
    try {
      const result: UIMessagePart[] = await block();
      deps.activityStore.complete(toolCallId, previewText(result));
      return result;
    } catch (e) {
      deps.activityStore.fail(toolCallId, e instanceof Error ? e : new Error(String(e)));
      throw e;
    }
  };

  // :432-458 — request 体(status/headers/body/截断标志)
  const request = async (input: JsonValue): Promise<JsonObject> => {
    const url: string = toolInputRequiredString(input, 'url');
    const rawMethod: string = toolInputString(input, 'method') ?? 'GET';
    const method: string = rawMethod.toUpperCase();
    if (['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD'].indexOf(method) < 0) {
      throw new Error(`Unsupported HTTP method: ${method}`);
    }
    requireHttpUrl(url);
    const timeout: number = inputLimit(input, 'timeout_ms', 20000, 120000);
    const headers: Record<string, string> = {};
    const rawHeaders: JsonValue | undefined =
      (input as JsonObject | null)?.['headers'] as JsonValue | undefined;
    if (rawHeaders !== null && rawHeaders !== undefined &&
      typeof rawHeaders === 'object' && !Array.isArray(rawHeaders)) {
      const rec: JsonObject = rawHeaders as JsonObject;
      Object.keys(rec).forEach((name: string): void => {
        // value.toString().trim('"') 逐字:字符串 → JSON 引号串 → 去首尾 '"'
        const v: JsonValue = rec[name] as JsonValue;
        const s: string = typeof v === 'string' ? JSON.stringify(v) : String(v);
        headers[name] = s.replace(/^"+|"+$/g, '');
      });
    }
    const body: string | null = toolInputString(input, 'body');
    const resp: ArtifactHttpResponse =
      await deps.http({
        method, url, headers, body, timeoutMs: timeout,
        maxResponseBytes: MAX_HTTP_BODY_BYTES,
      });
    const capped: Uint8Array = resp.body.subarray(0, MAX_HTTP_BODY_BYTES);
    const text: string = utf8Decode(capped);
    return {
      status_code: resp.status,
      url: resp.url,
      headers: resp.headers as unknown as JsonValue,
      body: text,
      truncated: resp.body.length >= MAX_HTTP_BODY_BYTES,
    };
  };

  // :474-494
  const listArchiveEntries = async (
    path: string, bytes: Uint8Array, limit: number,
  ): Promise<JsonValue[]> => {
    const out: JsonValue[] = [];
    const type: ArchiveType = archiveTypeOf(path);
    if (type === 'zip') {
      const entries: ZipEntryRecord[] = readZipEntries(bytes);
      for (const e of entries) {
        if (out.length >= limit) break;
        out.push(archiveEntryJson(e.name, e.name.endsWith('/'), e.uncompressedSize));
      }
      return out;
    }
    let tarBytes: Uint8Array = bytes;
    if (type === 'targz') {
      const gz: { payload: Uint8Array; isize: number } = gunzipSplit(bytes);
      // ISIZE 是 trailer 声明(mod 2^32),先按总输出上限拒绝再分配
      if (gz.isize > MAX_ARCHIVE_OUTPUT_BYTES) {
        throw new Error('Archive total uncompressed size exceeds the allowed limit');
      }
      tarBytes = await deps.inflateRaw(gz.payload, gz.isize);
      if (tarBytes.length > MAX_ARCHIVE_OUTPUT_BYTES) {
        throw new Error('Archive total uncompressed size exceeds the allowed limit');
      }
      if (tarBytes.length !== gz.isize) {
        throw new Error('Corrupted gzip archive: inflated size does not match trailer');
      }
    }
    let off: number = 0;
    while (out.length < limit) {
      const header: TarHeader | null = readTarHeader(tarBytes, off);
      if (header === null) break;
      out.push(archiveEntryJson(header.name, header.directory, header.size));
      off += 512 + alignTarSize(header.size);
    }
    return out;
  };

  // :496-547
  const extractArchive = async (
    path: string, bytes: Uint8Array, destination: string, overwrite: boolean,
  ): Promise<{ files: number; directories: number; bytes: number }> => {
    const stats = { files: 0, directories: 0, bytes: 0 };
    const writeOne = async (target: string, data: Uint8Array): Promise<void> => {
      if (stats.bytes + data.length > MAX_ARCHIVE_OUTPUT_BYTES) {
        throw new Error('Archive total uncompressed size exceeds the allowed limit');
      }
      if (!overwrite) {
        let exists: boolean = false;
        try {
          await mgr.readBytes(target);
          exists = true;
        } catch (_e) {
          exists = false;
        }
        if (exists) throw new Error(`Target already exists: ${target}`);
      }
      await mgr.writeBytes(target, data, mimeFromName(target));
      stats.files++;
      stats.bytes += data.length;
    };
    const type: ArchiveType = archiveTypeOf(path);
    if (type === 'zip') {
      const entries: ZipEntryRecord[] = readZipEntries(bytes);
      // 预累加声明解压总量:条目级上限挡不住多大量级,先拒绝再动手
      let declaredTotal: number = 0;
      for (const e of entries) {
        declaredTotal += e.uncompressedSize;
        if (declaredTotal > MAX_ARCHIVE_OUTPUT_BYTES) {
          throw new Error('Archive total uncompressed size exceeds the allowed limit');
        }
      }
      for (const e of entries) {
        const target: string = safeArchiveTarget(destination, e.name);
        if (e.name.endsWith('/')) {
          stats.directories++;
          continue;
        }
        const raw: Uint8Array = extractZipEntryData(bytes, e);
        let data: Uint8Array;
        if (e.method === 0) {
          data = raw;
        } else if (e.method === 8) {
          if (e.uncompressedSize > MAX_ARCHIVE_ENTRY_BYTES) {
            throw new Error(
              `Archive entry ${e.name} exceeds the ${MAX_ARCHIVE_ENTRY_BYTES} byte limit`);
          }
          data = await deps.inflateRaw(raw, e.uncompressedSize);
          // 实际输出与声明不符 = 损坏/截断,不得把不完整文件当成功写入
          if (data.length !== e.uncompressedSize) {
            throw new Error(`Archive entry ${e.name} inflate size mismatch`);
          }
        } else {
          throw new Error(`Unsupported zip compression method ${e.method}: ${e.name}`);
        }
        if (data.length > MAX_ARCHIVE_ENTRY_BYTES) {
          throw new Error(
            `Archive entry ${e.name} exceeds the ${MAX_ARCHIVE_ENTRY_BYTES} byte limit`);
        }
        await writeOne(target, data);
      }
      return stats;
    }
    let tarBytes: Uint8Array = bytes;
    if (type === 'targz') {
      const gz: { payload: Uint8Array; isize: number } = gunzipSplit(bytes);
      // ISIZE 是 trailer 声明(mod 2^32),先按总输出上限拒绝再分配
      if (gz.isize > MAX_ARCHIVE_OUTPUT_BYTES) {
        throw new Error('Archive total uncompressed size exceeds the allowed limit');
      }
      tarBytes = await deps.inflateRaw(gz.payload, gz.isize);
      if (tarBytes.length > MAX_ARCHIVE_OUTPUT_BYTES) {
        throw new Error('Archive total uncompressed size exceeds the allowed limit');
      }
      if (tarBytes.length !== gz.isize) {
        throw new Error('Corrupted gzip archive: inflated size does not match trailer');
      }
    }
    let off: number = 0;
    while (true) {
      const header: TarHeader | null = readTarHeader(tarBytes, off);
      if (header === null) break;
      const target: string = safeArchiveTarget(destination, header.name);
      if (header.directory) {
        stats.directories++;
      } else {
        if (header.size > MAX_ARCHIVE_ENTRY_BYTES) {
          throw new Error(
            `Archive entry is too large: ${header.name} (${header.size} bytes)`);
        }
        const data: Uint8Array = tarBytes.subarray(off + 512, off + 512 + header.size);
        if (data.length < header.size) throw new Error('Unexpected end of stream');
        await writeOne(target, data);
      }
      off += 512 + alignTarSize(header.size);
    }
    return stats;
  };

  // :549-572 — list 成功 → 目录(直子非目录项,不递归);失败 → 单文件
  const createZip = async (sources: string[]): Promise<Uint8Array> => {
    const entries: ZipWriteEntry[] = [];
    for (const source of sources) {
      let listed: Awaited<ReturnType<PosixWorkspaceManager['list']>> | null = null;
      try {
        listed = await mgr.list(source);
      } catch (_e) {
        listed = null;
      }
      if (listed === null) {
        const normalized: string = normalizeWorkspacePath(source);
        entries.push({ name: normalized, data: await mgr.readBytes(normalized) });
      } else {
        for (const e of listed) {
          if (!e.directory) entries.push({ name: e.path, data: await mgr.readBytes(e.path) });
        }
      }
    }
    const now: { time: number; date: number } = dosTimeDate(new Date());
    // addZipEntry:safe = normalize(path).removePrefix("./") — normalize 永
    //   不产出 './' 前缀,removePrefix 恒 no-op(忠实)
    return await buildZipBytes(entries, deps.deflateRaw, now.time, now.date);
  };

  return [
    // :62-83 — http_request
    makeAgentTool({
      name: 'http_request',
      description: 'Make a bounded HTTP/HTTPS request. Does not send app cookies or ' +
        'private device data. Response body is truncated at 512KB.',
      parameters: (): InputSchemaObj => ({
        type: 'object',
        properties: {
          method: stringProp('HTTP method. Defaults to GET.'),
          url: stringProp('HTTP or HTTPS URL.'),
          headers: objectProp('Optional string headers.'),
          body: stringProp('Optional request body.'),
          timeout_ms: integerProp('Timeout in milliseconds. Defaults to 20000.'),
        },
        required: ['url'],
      }),
      execute: async (input: JsonValue): Promise<UIMessagePart[]> =>
        track('http_request', 'HTTP 请求', input, 'HttpURLConnection',
          async (): Promise<UIMessagePart[]> => textJson(await request(input))),
    }),
    // :86-125 — download_file
    makeAgentTool({
      name: 'download_file',
      description: 'Download an HTTP/HTTPS URL into the user-authorized /workspace. ' +
        'Defaults to /workspace/downloads/<filename>.',
      parameters: (): InputSchemaObj => ({
        type: 'object',
        properties: {
          url: stringProp('HTTP or HTTPS URL to download.'),
          workspace_path: stringProp('Optional destination path under /workspace.'),
        },
        required: ['url'],
      }),
      needsApproval: true,
      allowsAutoApproval: false,
      execute: async (input: JsonValue): Promise<UIMessagePart[]> =>
        track('download_file', '下载文件', input, 'HTTP -> SAF workspace',
          async (): Promise<UIMessagePart[]> => {
            const url: string = toolInputRequiredString(input, 'url');
            requireHttpUrl(url);
            const resp: ArtifactHttpResponse = await deps.http(
              {
                method: 'GET', url, headers: {}, body: null, timeoutMs: 30000,
                maxResponseBytes: MAX_DOWNLOAD_BYTES,
              });
            if (resp.status < 200 || resp.status > 299) {
              throw new Error(`Download failed with HTTP ${resp.status}`);
            }
            if (resp.body.length > MAX_DOWNLOAD_BYTES) {
              throw new Error(`Download exceeds the ${MAX_DOWNLOAD_BYTES} byte limit`);
            }
            const rawDest: string | null = toolInputString(input, 'workspace_path');
            const destination: string =
              rawDest !== null && rawDest.trim().length > 0
                ? rawDest
                : `downloads/${safeFileName(fileNameFromUrl(url))}`;
            const contentType: string | undefined = resp.headers['content-type'] ??
              resp.headers['Content-Type'];
            const mime: string = contentType !== undefined
              ? contentType.split(';')[0]
              : 'application/octet-stream';
            const entry = await mgr.writeBytes(destination, resp.body, mime);
            return textJson({
              status: 'saved',
              path: entry.path,
              size_bytes: resp.body.length,
              mime_type: entry.mimeType ?? '',
            });
          }),
    }),
    // :127-149 — archive_list
    makeAgentTool({
      name: 'archive_list',
      description: 'List entries in a /workspace archive. Supports zip, tar, tar.gz, and tgz.',
      parameters: (): InputSchemaObj => ({
        type: 'object',
        properties: {
          path: stringProp('Archive path under /workspace.'),
          limit: integerProp('Maximum entries. Defaults to 200.'),
        },
        required: ['path'],
      }),
      execute: async (input: JsonValue): Promise<UIMessagePart[]> =>
        track('archive_list', '查看压缩包', input, 'Workspace artifact',
          async (): Promise<UIMessagePart[]> => {
            const path: string = toolInputRequiredString(input, 'path');
            const limit: number = inputLimit(input, 'limit', 200, 1000);
            const bytes: Uint8Array = await mgr.readBytesCapped(path, MAX_ARCHIVE_BYTES);
            const entries: JsonValue[] = await listArchiveEntries(path, bytes, limit);
            return textJson({ path, entries: entries as unknown as JsonValue });
          }),
    }),
    // :151-181 — archive_extract
    makeAgentTool({
      name: 'archive_extract',
      description: 'Extract a zip/tar/tar.gz/tgz archive inside /workspace. ' +
        'Blocks Zip Slip and path traversal.',
      parameters: (): InputSchemaObj => ({
        type: 'object',
        properties: {
          path: stringProp('Archive path under /workspace.'),
          destination_path: stringProp('Destination directory under /workspace. ' +
            'Defaults to /workspace/extracted/<archive-name>.'),
          overwrite: booleanProp('Overwrite existing files. Defaults to false.'),
        },
        required: ['path'],
      }),
      needsApproval: true,
      allowsAutoApproval: false,
      execute: async (input: JsonValue): Promise<UIMessagePart[]> =>
        track('archive_extract', '解压压缩包', input, 'Workspace artifact',
          async (): Promise<UIMessagePart[]> => {
            const path: string = toolInputRequiredString(input, 'path');
            const rawDest: string | null = toolInputString(input, 'destination_path');
            const destination: string = rawDest !== null && rawDest.trim().length > 0
              ? rawDest
              : `extracted/${safeBaseName(path)}`;
            const overwrite: boolean = toolInputBoolean(input, 'overwrite') ?? false;
            const bytes: Uint8Array = await mgr.readBytesCapped(path, MAX_ARCHIVE_BYTES);
            const result = await extractArchive(path, bytes, destination, overwrite);
            return textJson({
              path,
              destination_path: destination,
              files_written: result.files,
              directories_seen: result.directories,
              bytes_written: result.bytes,
            });
          }),
    }),
    // :183-215 — archive_create
    makeAgentTool({
      name: 'archive_create',
      description: 'Create a zip archive from /workspace files or folders. ' +
        'Stage 1 supports format=zip.',
      parameters: (): InputSchemaObj => ({
        type: 'object',
        properties: {
          source_paths: arrayProp('Workspace-relative files/folders to include.'),
          destination_path: stringProp('Destination archive path under /workspace.'),
          format: stringProp('Archive format. Stage 1 supports zip.'),
        },
        required: ['source_paths', 'destination_path'],
      }),
      needsApproval: true,
      allowsAutoApproval: false,
      execute: async (input: JsonValue): Promise<UIMessagePart[]> =>
        track('archive_create', '创建压缩包', input, 'Workspace artifact',
          async (): Promise<UIMessagePart[]> => {
            const format: string = toolInputString(input, 'format') ?? 'zip';
            if (format !== 'zip') {
              throw new Error('archive_create stage1 supports format=zip only');
            }
            const rawSources: JsonValue | undefined =
              (input as JsonObject | null)?.['source_paths'] as JsonValue | undefined;
            const sources: string[] = jsonArrayStrings(rawSources);
            if (sources.length === 0) throw new Error('source_paths must not be empty');
            const bytes: Uint8Array = await createZip(sources);
            const entry = await mgr.writeBytes(
              toolInputRequiredString(input, 'destination_path'), bytes, 'application/zip');
            return textJson({
              path: entry.path,
              format: 'zip',
              size_bytes: bytes.length,
            });
          }),
    }),
    // :217-240 — pdf_read(平台阻断:显式抛错,工具保留协议面)
    makeAgentTool({
      name: 'pdf_read',
      description: 'Extract text from a PDF file in /workspace using the bundled MuPDF parser.',
      parameters: (): InputSchemaObj => ({
        type: 'object',
        properties: {
          path: stringProp('PDF path under /workspace.'),
          max_chars: integerProp('Maximum text characters. Defaults to 20000.'),
        },
        required: ['path'],
      }),
      execute: async (input: JsonValue): Promise<UIMessagePart[]> =>
        track('pdf_read', '读取 PDF', input, 'MuPDF',
          async (): Promise<UIMessagePart[]> => {
            throw new Error(PDF_BLOCKED_MESSAGE);
          }),
    }),
    // :242-268 — pdf_render_page(平台阻断:同上)
    makeAgentTool({
      name: 'pdf_render_page',
      description: 'Render one PDF page from /workspace to a PNG artifact in /workspace/previews.',
      parameters: (): InputSchemaObj => ({
        type: 'object',
        properties: {
          path: stringProp('PDF path under /workspace.'),
          page: integerProp('1-based page number. Defaults to 1.'),
          destination_path: stringProp('Optional PNG output path under /workspace.'),
        },
        required: ['path'],
      }),
      execute: async (input: JsonValue): Promise<UIMessagePart[]> =>
        track('pdf_render_page', '渲染 PDF 页面', input, 'PdfRenderer',
          async (): Promise<UIMessagePart[]> => {
            throw new Error(PDF_BLOCKED_MESSAGE);
          }),
    }),
    // :270-311 — office_read
    makeAgentTool({
      name: 'office_read',
      description: 'Read basic text from docx, xlsx, or pptx files in /workspace.',
      parameters: (): InputSchemaObj => ({
        type: 'object',
        properties: {
          path: stringProp('Office document path under /workspace.'),
          max_chars: integerProp('Maximum text characters. Defaults to 20000.'),
        },
        required: ['path'],
      }),
      execute: async (input: JsonValue): Promise<UIMessagePart[]> =>
        track('office_read', '读取 Office 文档', input, 'Workspace artifact',
          async (): Promise<UIMessagePart[]> => {
            const path: string = toolInputRequiredString(input, 'path');
            const bytes: Uint8Array =
              await mgr.readBytesCapped(path, MAX_WORKSPACE_TEMP_FILE_BYTES);
            const text: string = await officeText(path, bytes, deps);
            const maxChars: number = inputLimit(input, 'max_chars', 20000, 80000);
            return textJson({
              path,
              text: text.length > maxChars ? text.substring(0, maxChars) : text,
              text_chars: text.length,
              truncated: text.length > maxChars,
            });
          }),
    }),
    // :313-338 — image_info
    makeAgentTool({
      name: 'image_info',
      description: 'Read basic image metadata from a /workspace image.',
      parameters: (): InputSchemaObj => ({
        type: 'object',
        properties: {
          path: stringProp('Image path under /workspace.'),
        },
        required: ['path'],
      }),
      execute: async (input: JsonValue): Promise<UIMessagePart[]> =>
        track('image_info', '读取图片信息', input, 'Workspace artifact',
          async (): Promise<UIMessagePart[]> => {
            const path: string = toolInputRequiredString(input, 'path');
            const bytes: Uint8Array = await mgr.readBytesCapped(path, MAX_IMAGE_BYTES);
            const info: ArtifactImageInfo = await deps.image.info(bytes);
            return textJson({
              path,
              width: info.width,
              height: info.height,
              mime_type: info.mimeType,
              size_bytes: bytes.length,
              exif_orientation: info.exifOrientation,
            });
          }),
    }),
    // :340-385 — image_convert
    makeAgentTool({
      name: 'image_convert',
      description: 'Convert or resize a /workspace image to png, jpg, or webp.',
      parameters: (): InputSchemaObj => ({
        type: 'object',
        properties: {
          path: stringProp('Input image path under /workspace.'),
          destination_path: stringProp('Output path under /workspace.'),
          format: stringProp('png, jpg, or webp. Defaults from destination extension or png.'),
          max_width: integerProp('Optional maximum width.'),
          max_height: integerProp('Optional maximum height.'),
          quality: integerProp('Output quality 1-100 for jpg/webp. Defaults to 90.'),
        },
        required: ['path', 'destination_path'],
      }),
      needsApproval: true,
      execute: async (input: JsonValue): Promise<UIMessagePart[]> =>
        track('image_convert', '转换图片', input, 'Workspace artifact',
          async (): Promise<UIMessagePart[]> => {
            const source: string = toolInputRequiredString(input, 'path');
            const destination: string = toolInputRequiredString(input, 'destination_path');
            const sourceBytes: Uint8Array = await mgr.readBytes(source);
            // decode 失败 → 'Unable to decode image: X'(:361);info 前置拿尺寸
            let info: ArtifactImageInfo;
            try {
              info = await deps.image.info(sourceBytes);
            } catch (_e) {
              throw new Error(`Unable to decode image: ${source}`);
            }
            const scale: ArtifactScaleResult = artifactScaleDims(
              info.width, info.height,
              toolInputInt(input, 'max_width'), toolInputInt(input, 'max_height'));
            const rawFormat: string | null = toolInputString(input, 'format');
            const format: string = (rawFormat !== null
              ? rawFormat
              : destination.indexOf('.') >= 0
                ? destination.substring(destination.lastIndexOf('.') + 1)
                : 'png').toLowerCase();
            const quality: number = inputLimit(input, 'quality', 90, 100);
            const bytes: Uint8Array = await deps.image.convert(
              sourceBytes, scale.scaled ? scale.width : null,
              scale.scaled ? scale.height : null, format, quality);
            const mime: string = format === 'jpg' || format === 'jpeg'
              ? 'image/jpeg'
              : format === 'webp' ? 'image/webp' : 'image/png';
            const entry = await mgr.writeBytes(destination, bytes, mime);
            return textJson({
              path: entry.path,
              mime_type: mime,
              size_bytes: bytes.length,
            });
          }),
    }),
    // :387-406 — ocr_image(stage1 桩逐字)
    makeAgentTool({
      name: 'ocr_image',
      description: 'OCR a /workspace image. Stage 1 reports runtime availability; ' +
        'use a VLM model if OCR runtime is unavailable.',
      parameters: (): InputSchemaObj => ({
        type: 'object',
        properties: {
          path: stringProp('Image path under /workspace.'),
        },
        required: ['path'],
      }),
      execute: async (input: JsonValue): Promise<UIMessagePart[]> =>
        track('ocr_image', '图片 OCR', input, 'Workspace artifact',
          async (): Promise<UIMessagePart[]> => textJson({
            path: toolInputRequiredString(input, 'path'),
            status: 'unavailable',
            runtime: 'stage1-no-local-ocr',
            message: 'Local OCR is not bundled yet. Use image_info plus a configured ' +
              'remote multimodal/VLM model for OCR in this build.',
          })),
    }),
  ];
};
