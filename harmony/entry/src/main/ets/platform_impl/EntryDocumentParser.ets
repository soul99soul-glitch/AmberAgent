// EntryDocumentParser — 文档解析 entry 适配(D-112/D-113/D-114)
// Android 基准:document 模块 DocxParser.kt/PptxParser.kt 平台子集
//   - java.util.zip.ZipFile/ZipInputStream → zip_archive.ts(EOCD+中央目录序,
//     D-113 起 CD 枚举序忠实;deflate = zlib.inflateInit2 raw 流注入)
//   - org.xmlpull.v1.XmlPullParser(kxml2,namespace-aware,本地名)→ @kit.ArkTS
//     xml.XmlPullParser(ParseOptions 回调;两遍解析:token 流 + 属性三元组,
//     按 ParseInfo.getAttributeCount 顺序对齐,消除配对歧义)
//   - getAttributeValue(null, name)= kxml2 任意命名空间通配 → 本地名/前缀名匹配
import { fileIo } from '@kit.CoreFileKit';
import { util, xml } from '@kit.ArkTS';
import { zlib } from '@kit.BasicServicesKit';
import type {
  DocumentFileHandle, XmlPullEvent, XmlPullPort, ZipEntryRecord, ZipEntryTextProvider,
} from '@amber/chat-domain';
import {
  parseDocxFromZip, parseEpubFromZip, parsePptxFromZip, readZipEntries, readZipEntryText,
} from '@amber/chat-domain';

// ===== XML pull 适配(ParseOptions 回调 → token 流) =====

interface XmlToken {
  event: XmlPullEvent;
  name: string;
  depth: number;
  text: string | null;
  attrCount: number;
  attrs: Map<string, string>;
}

// kxml2 namespace-aware → parser.name = 本地名(去 'w:' 前缀)
const localName = (name: string): string => {
  const i: number = name.lastIndexOf(':');
  return i >= 0 ? name.substring(i + 1) : name;
};

class EntryXmlPull implements XmlPullPort {
  private idx: number = 0; // 初始 START_DOCUMENT(kxml2 同)
  private tokens: XmlToken[] = [];

  // nsAware:true = kxml2 namespace-aware(本地名);false = 保留限定名
  //   (EpubParser.kt:104 parseXhtml namespaceAware=false 语义)
  constructor(xmlText: string, nsAware: boolean = true) {
    const tokens: XmlToken[] = [
      { event: 'start_document', name: '', depth: 0, text: null, attrCount: 0, attrs: new Map() },
    ];
    const tagName = (name: string): string => nsAware ? localName(name) : name;
    const buf: Uint8Array = util.TextEncoder.create('utf-8').encodeInto(xmlText);
    // 第一遍:事件 token 流(ParseInfo 自带 depth/attrCount)
    const tokenOptions: xml.ParseOptions = {
      supportDoctype: false,
      ignoreNameSpace: false,
      tokenValueCallbackFunction: (eventType: xml.EventType, value: xml.ParseInfo): boolean => {
        if (eventType === xml.EventType.START_TAG) {
          tokens.push({
            event: 'start_tag', name: tagName(value.getName()), depth: value.getDepth(),
            text: null, attrCount: value.getAttributeCount(), attrs: new Map(),
          });
        } else if (eventType === xml.EventType.END_TAG) {
          tokens.push({
            event: 'end_tag', name: tagName(value.getName()), depth: value.getDepth(),
            text: null, attrCount: 0, attrs: new Map(),
          });
        } else if (eventType === xml.EventType.TEXT || eventType === xml.EventType.CDSECT) {
          // kxml2:CDATA 段同 TEXT 事件
          tokens.push({
            event: 'text', name: '', depth: value.getDepth(),
            text: value.getText(), attrCount: 0, attrs: new Map(),
          });
        } else if (eventType === xml.EventType.END_DOCUMENT) {
          tokens.push({ event: 'end_document', name: '', depth: 0, text: null, attrCount: 0, attrs: new Map() });
        }
        return true;
      },
    };
    new xml.XmlPullParser(buf.buffer as ArrayBuffer, 'utf-8').parse(tokenOptions);
    // 第二遍:属性三元组(文档序)→ 按 attrCount 逐个弹入对应 start_tag token
    const triples: string[][] = [];
    const attrOptions: xml.ParseOptions = {
      supportDoctype: false,
      ignoreNameSpace: false,
      attributeWithTagCallbackFunction: (tagName: string, key: string, value: string): boolean => {
        triples.push([tagName, key, value]);
        return true;
      },
    };
    new xml.XmlPullParser(buf.buffer as ArrayBuffer, 'utf-8').parse(attrOptions);
    let cursor: number = 0;
    for (const t of tokens) {
      if (t.event !== 'start_tag') continue;
      for (let i = 0; i < t.attrCount && cursor < triples.length; i++, cursor++) {
        t.attrs.set(triples[cursor][1], triples[cursor][2]);
      }
    }
    this.tokens = tokens;
  }

  private get cur(): XmlToken {
    return this.tokens[this.idx];
  }

  get eventType(): XmlPullEvent {
    return this.cur.event;
  }

  get name(): string {
    return this.cur.name;
  }

  get depth(): number {
    return this.cur.depth;
  }

  get text(): string | null {
    return this.cur.text;
  }

  // kxml2:namespace=null 通配 → 本地名精确或 '前缀:本地名' 命中
  getAttributeValue(_namespace: string | null, name: string): string | null {
    const attrs: Map<string, string> = this.cur.attrs;
    const direct: string | undefined = attrs.get(name);
    if (direct !== undefined) return direct;
    const suffix: string = `:${name}`;
    let found: string | null = null;
    attrs.forEach((v: string, k: string): void => {
      if (found === null && k.endsWith(suffix)) found = v;
    });
    return found;
  }

  next(): XmlPullEvent {
    if (this.idx < this.tokens.length - 1) this.idx++;
    return this.eventType;
  }
}

export const createEntryXmlPull = (xmlText: string): XmlPullPort => new EntryXmlPull(xmlText, true);

// EpubParser.kt:104 — parseXhtml namespaceAware=false(保留限定名)
export const createEntryXmlPullNoNs = (xmlText: string): XmlPullPort => new EntryXmlPull(xmlText, false);

// ===== ZIP 读取(D-113 升级:中央目录序 + raw inflate,取代 D-112 整包解压) =====
// Java ZipFile 语义:entries() = CD 枚举序;getInputStream(entry) = 单条目解压;
//   deflate = zlib.inflateInit2(-15) raw 流(平台能力,InflateRawPort 注入域层)

const readFileBytes = (path: string): Uint8Array => {
  const file = fileIo.openSync(path, fileIo.OpenMode.READ_ONLY);
  try {
    const st = fileIo.statSync(file.fd);
    const buf = new ArrayBuffer(st.size);
    fileIo.readSync(file.fd, buf);
    return new Uint8Array(buf);
  } finally {
    fileIo.closeSync(file);
  }
};

// InflateRawPort entry 实现(raw deflate,负 windowBits;输出容量 = 头声明
//   uncompressedSize,不足即抛 — 非静默)
// D-123:导出供 SkillStore(zip 技能包导入)复用
export const inflateRawEntry = async (data: Uint8Array, expectedSize: number): Promise<Uint8Array> => {
  // 声明值是归档内不可信输入:分配前拒绝超限(压缩炸弹防护,与域层 64MB 上限同口径)
  const MAX_INFLATE_OUTPUT_BYTES: number = 64 * 1024 * 1024;
  if (expectedSize > MAX_INFLATE_OUTPUT_BYTES) {
    throw new Error(`inflate output exceeds limit: ${expectedSize}`);
  }
  const inCopy: Uint8Array = new Uint8Array(data); // subarray .buffer 偏移剥离
  const outCap: number = Math.max(expectedSize, 1);
  const outBuffer: ArrayBuffer = new ArrayBuffer(outCap);
  const strm: zlib.ZStream = {
    nextIn: inCopy.buffer as ArrayBuffer,
    availableIn: inCopy.length,
    nextOut: outBuffer,
    availableOut: outCap,
  };
  const zip = zlib.createZipSync();
  const init: zlib.ReturnStatus = await zip.inflateInit2(strm, -15);
  if (init !== zlib.ReturnStatus.OK) {
    throw new Error(`inflateInit2 failed: ${init}`);
  }
  try {
    const status: zlib.ReturnStatus = await zip.inflate(strm, zlib.CompressFlushMode.FINISH);
    const current: zlib.ZStream = await zip.getZStream();
    if (status !== zlib.ReturnStatus.STREAM_END && status !== zlib.ReturnStatus.OK) {
      throw new Error(`inflate failed: ${status}`);
    }
    if (status === zlib.ReturnStatus.OK && (current.availableOut ?? 0) === 0) {
      throw new Error('inflate output overflow (declared size too small)');
    }
    // API 12 的 ZStream 入参不会回写执行后的状态，必须重新 getZStream()。
    const total: number = current.totalOut ?? 0;
    return new Uint8Array(outBuffer.slice(0, total));
  } finally {
    await zip.inflateEnd(strm);
  }
};

// DocxParser.kt:21-38 parse(File) 平台段:CD 解析失败(EOCD)经 provider 抛入
//   域层 catch → 'Error parsing DOCX file: <msg>';条目未命中 → null → 原文案
export const parseDocxEntry = async (handle: DocumentFileHandle): Promise<string> => {
  const bytes: Uint8Array = readFileBytes(handle.absolutePath);
  let cached: ZipEntryRecord[] | null = null;
  const entryText: ZipEntryTextProvider = async (entryName: string): Promise<string | null> => {
    if (cached === null) cached = readZipEntries(bytes);
    const rec: ZipEntryRecord | undefined =
      cached.find((e: ZipEntryRecord): boolean => e.name === entryName);
    if (rec === undefined) return null;
    return await readZipEntryText(bytes, rec, inflateRawEntry);
  };
  return await parseDocxFromZip(entryText, createEntryXmlPull);
};

// PptxParser.kt:18-57 parse(File) 平台段:CD 序枚举在域层(zip_archive);
//   EOCD/解压失败 → 域层 'Error parsing PPTX file: <msg>'
export const parsePptxEntry = async (handle: DocumentFileHandle): Promise<string> => {
  const bytes: Uint8Array = readFileBytes(handle.absolutePath);
  return await parsePptxFromZip(bytes, inflateRawEntry, createEntryXmlPull);
};

// EpubParser.kt:16-46 parse(File) 平台段:container/opf/xhtml 三解析在域层;
//   xhtml 用 no-ns 工厂(namespaceAware=false 逐字)
export const parseEpubEntry = async (handle: DocumentFileHandle): Promise<string> => {
  const bytes: Uint8Array = readFileBytes(handle.absolutePath);
  return await parseEpubFromZip(bytes, inflateRawEntry, createEntryXmlPull, createEntryXmlPullNoNs);
};
