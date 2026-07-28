// EntryDocumentParser — 文档解析 entry 适配(D-112)
// Android 基准:document 模块 DocxParser.kt 平台子集
//   - java.util.zip.ZipInputStream 条目流扫描 → zlib.decompressFile 整包解压到
//     临时目录后按条目名读文本(**偏差登记**:整包解压 vs 流式扫描,输出等价)
//   - org.xmlpull.v1.XmlPullParser(kxml2,namespace-aware,本地名)→ @kit.ArkTS
//     xml.XmlPullParser(ParseOptions 回调;两遍解析:token 流 + 属性三元组,
//     按 ParseInfo.getAttributeCount 顺序对齐,消除配对歧义)
//   - getAttributeValue(null, name)= kxml2 任意命名空间通配 → 本地名/前缀名匹配
// 清理:临时目录用后递归删除(平台缓存,非业务数据;失败静默对齐 Android use{} 关闭)
import { fileIo } from '@kit.CoreFileKit';
import { util, xml } from '@kit.ArkTS';
import { zlib } from '@kit.BasicServicesKit';
import type {
  DocumentFileHandle, XmlPullEvent, XmlPullPort, ZipEntryTextProvider,
} from '@amber/chat-domain';
import { newId, parseDocxFromZip } from '@amber/chat-domain';

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

  constructor(xmlText: string) {
    const tokens: XmlToken[] = [
      { event: 'start_document', name: '', depth: 0, text: null, attrCount: 0, attrs: new Map() },
    ];
    const buf: Uint8Array = util.TextEncoder.create('utf-8').encodeInto(xmlText);
    // 第一遍:事件 token 流(ParseInfo 自带 depth/attrCount)
    const tokenOptions: xml.ParseOptions = {
      supportDoctype: false,
      ignoreNameSpace: false,
      tokenValueCallbackFunction: (eventType: xml.EventType, value: xml.ParseInfo): boolean => {
        if (eventType === xml.EventType.START_TAG) {
          tokens.push({
            event: 'start_tag', name: localName(value.getName()), depth: value.getDepth(),
            text: null, attrCount: value.getAttributeCount(), attrs: new Map(),
          });
        } else if (eventType === xml.EventType.END_TAG) {
          tokens.push({
            event: 'end_tag', name: localName(value.getName()), depth: value.getDepth(),
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

export const createEntryXmlPull = (xmlText: string): XmlPullPort => new EntryXmlPull(xmlText);

// ===== ZIP 条目供给(整包解压 → 条目名读文本) =====

const readFileText = (path: string): string => {
  const file = fileIo.openSync(path, fileIo.OpenMode.READ_ONLY);
  try {
    const st = fileIo.statSync(file.fd);
    const buf = new ArrayBuffer(st.size);
    fileIo.readSync(file.fd, buf);
    return util.TextDecoder.create('utf-8').decodeToString(new Uint8Array(buf));
  } finally {
    fileIo.closeSync(file);
  }
};

// 递归删除临时目录(平台缓存;失败静默 = Android 临时资源清理同档)
const removeDirRecursive = (dir: string): void => {
  try {
    const names: string[] = fileIo.listFileSync(dir);
    for (const n of names) {
      const p: string = `${dir}/${n}`;
      try {
        const st = fileIo.statSync(p);
        if (st.isDirectory()) {
          removeDirRecursive(p);
        } else {
          fileIo.unlinkSync(p);
        }
      } catch { /* 单点失败不阻断其余清理 */ }
    }
    fileIo.rmdirSync(dir);
  } catch { /* 目录不存在/已清理 */ }
};

// DocxParser.kt:21-38 parse(File) 平台段:zip 开流(失败 → 抛 → 域层
//   'Error parsing DOCX file: <msg>')+ 条目扫描(未命中 → null → 域层原文案)
export const parseDocxEntry = async (handle: DocumentFileHandle): Promise<string> => {
  const ctx: Context = AppStorage.get<Context>('abilityContext') as Context;
  const dir: string = `${ctx.cacheDir}/docparse_${newId()}`;
  fileIo.mkdirSync(dir, true);
  try {
    await zlib.decompressFile(handle.absolutePath, dir);
    const entryText: ZipEntryTextProvider = (entryName: string): Promise<string | null> => {
      const p: string = `${dir}/${entryName}`;
      if (!fileIo.accessSync(p)) return Promise.resolve(null);
      return Promise.resolve(readFileText(p));
    };
    return await parseDocxFromZip(entryText, createEntryXmlPull);
  } finally {
    removeDirRecursive(dir);
  }
};
