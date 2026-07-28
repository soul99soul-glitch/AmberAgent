// xml_pull — XmlPullParser 端口类型(D-112)
// Android 基准:org.xmlpull.v1.XmlPullParser(document 模块 DocxParser/PptxParser/
//   EpubParser 共用子集:eventType/next/START_TAG/END_TAG/TEXT/END_DOCUMENT/
//   name/depth/text/getAttributeValue(null, name))
// 域层仅依赖本 Port;entry 由 @kit.ArkTS xml.XmlPullParser 适配,测试由 token 脚本驱动

export type XmlPullEvent = 'start_document' | 'start_tag' | 'end_tag' | 'text' | 'end_document';

export interface XmlPullPort {
  // 当前事件(parser.eventType;初始 start_document)
  readonly eventType: XmlPullEvent;
  // 当前标签名(非 tag 事件 → '';Kotlin parser.name 同语义)
  readonly name: string;
  // 当前嵌套深度(根元素 = 1;Kotlin parser.depth 同语义)
  readonly depth: number;
  // 当前文本(text 事件有效;Kotlin parser.text 可空 → string | null)
  readonly text: string | null;
  // getAttributeValue(null, name):未命中 → null;
  //   kxml2 语义:namespace=null = 任意命名空间通配(按本地名匹配)
  getAttributeValue(namespace: string | null, name: string): string | null;
  // parser.next():推进并返回新事件
  next(): XmlPullEvent;
}

// 由 XML 文本构造 pull parser(XmlPullParserFactory.newInstance + setInput UTF-8)
export type XmlPullFactory = (xmlText: string) => XmlPullPort;

// ZIP 条目文本供给(ZipInputStream 条目扫描;未命中 → null,异常 → throw)
export type ZipEntryTextProvider = (entryName: string) => Promise<string | null>;
