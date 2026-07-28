// epub_parser — EpubParser.kt 全文移植(D-114)
// Android 基准:document/src/main/java/app/amber/document/EpubParser.kt(231 行全文)
//   - container.xml → rootfile full-path;opfDir = substringBeforeLast('/', '')
//   - spine 序遍历:manifest 未命中/非 html/条目缺失 → continue;内容非空 + '\n\n'
//   - parseXhtml:**namespaceAware=false**(标签保留前缀,小写比较)+
//     DOCDECL 不处理;parser.next() try/catch → break(畸形容忍);
//     文本 \n→' ' \r→' ' \s+→' ';结果 \n{3,} → '\n\n' + trim;异常 → ''
//   - li:父栈顶 ol → 共享计数 'N. '否则 '- '(ul 不重置计数,Android 原样)
// 端口化:ZipFile → zip_archive(CD 序);XmlPull ns-aware/no-ns 双工厂注入
//   (entry:createEntryXmlPull / createEntryXmlPullNoNs)
import type { XmlPullFactory, XmlPullPort } from './xml_pull.ts';
import type { InflateRawPort, ZipEntryRecord } from './zip_archive.ts';
import { readZipEntries, readZipEntryText } from './zip_archive.ts';

interface ManifestItem {
  id: string;
  href: string;
  mediaType: string;
}

// EpubParser.kt:16-46 parse(File)
export const parseEpubFromZip = async (
  zipBytes: Uint8Array,
  inflateRaw: InflateRawPort,
  newParser: XmlPullFactory,
  newParserNoNs: XmlPullFactory,
): Promise<string> => {
  try {
    const entries: ZipEntryRecord[] = readZipEntries(zipBytes);
    const find = (name: string): ZipEntryRecord | undefined =>
      entries.find((e: ZipEntryRecord): boolean => e.name === name);
    // findOpfPath:container 缺失/rootfile 缺失 → 'Unable to find OPF file in EPUB'
    const container: ZipEntryRecord | undefined = find('META-INF/container.xml');
    if (container === undefined) {
      return 'Unable to find OPF file in EPUB';
    }
    const containerXml: string = await readZipEntryText(zipBytes, container, inflateRaw);
    const opfPath: string | null = parseEpubContainerXml(newParser, containerXml);
    if (opfPath === null) {
      return 'Unable to find OPF file in EPUB';
    }
    const slash: number = opfPath.lastIndexOf('/');
    const opfDir: string = slash >= 0 ? opfPath.substring(0, slash) : '';
    const opfEntry: ZipEntryRecord | undefined = find(opfPath);
    if (opfEntry === undefined) {
      return 'Unable to read OPF file in EPUB';
    }
    const opfXml: string = await readZipEntryText(zipBytes, opfEntry, inflateRaw);
    const opf: OpfContents = parseEpubOpfXml(newParser, opfXml);

    let result: string = '';
    for (const itemId of opf.spine) {
      const item: ManifestItem | undefined = opf.manifest.get(itemId);
      if (item === undefined) continue;
      if (!item.mediaType.includes('html')) continue;
      const itemPath: string = opfDir.length === 0 ? item.href : `${opfDir}/${item.href}`;
      const entry: ZipEntryRecord | undefined = find(itemPath);
      if (entry === undefined) continue;
      const xhtml: string = await readZipEntryText(zipBytes, entry, inflateRaw);
      const content: string = parseEpubXhtml(newParserNoNs, xhtml);
      if (content.trim().length > 0) {
        result += content;
        result += '\n\n';
      }
    }
    const trimmed: string = result.trim();
    return trimmed.length > 0 ? trimmed : 'No readable content found in EPUB file';
  } catch (e) {
    const msg: string = e instanceof Error ? e.message : String(e);
    return `Error parsing EPUB file: ${msg}`;
  }
};

// EpubParser.kt:48-64 findOpfPath(首个 rootfile full-path;未命中 → null)
export const parseEpubContainerXml = (newParser: XmlPullFactory, xmlText: string): string | null => {
  const parser: XmlPullPort = newParser(xmlText);
  while (parser.eventType !== 'end_document') {
    if (parser.eventType === 'start_tag' && parser.name === 'rootfile') {
      return parser.getAttributeValue(null, 'full-path');
    }
    parser.next();
  }
  return null;
};

interface OpfContents {
  manifest: Map<string, ManifestItem>;
  spine: string[];
}

// EpubParser.kt:66-99 parseOpf(item id/href/media-type + itemref idref 序)
export const parseEpubOpfXml = (newParser: XmlPullFactory, xmlText: string): OpfContents => {
  const parser: XmlPullPort = newParser(xmlText);
  const manifest: Map<string, ManifestItem> = new Map();
  const spine: string[] = [];

  while (parser.eventType !== 'end_document') {
    if (parser.eventType === 'start_tag') {
      if (parser.name === 'item') {
        const id: string = parser.getAttributeValue(null, 'id') ?? '';
        const href: string = parser.getAttributeValue(null, 'href') ?? '';
        const mediaType: string = parser.getAttributeValue(null, 'media-type') ?? '';
        if (id.length > 0) {
          manifest.set(id, { id, href, mediaType });
        }
      } else if (parser.name === 'itemref') {
        const idref: string = parser.getAttributeValue(null, 'idref') ?? '';
        if (idref.length > 0) {
          spine.push(idref);
        }
      }
    }
    parser.next();
  }
  return { manifest, spine };
};

// EpubParser.kt:101-230 parseXhtml(ns-unaware;next() 异常 → break;异常 → '')
export const parseEpubXhtml = (newParser: XmlPullFactory, xmlText: string): string => {
  try {
    const parser: XmlPullPort = newParser(xmlText);
    let result: string = '';
    const tagStack: string[] = [];
    let inBody: boolean = false;
    let listCounter: number = 0;

    while (parser.eventType !== 'end_document') {
      if (parser.eventType === 'start_tag') {
        const tag: string = parser.name.toLowerCase();
        tagStack.push(tag);
        if (tag === 'body') {
          inBody = true;
        } else if (tag === 'ol') {
          listCounter = 0;
        } else if (tag === 'li') {
          const parentTag: string | undefined =
            tagStack.length >= 2 ? tagStack[tagStack.length - 2] : undefined;
          if (parentTag === 'ol') {
            listCounter++;
            result += `${listCounter}. `;
          } else {
            result += '- ';
          }
        } else if (tag === 'br') {
          result += '\n';
        } else if (tag === 'img') {
          if (inBody) {
            const alt: string | null = parser.getAttributeValue(null, 'alt');
            if (alt !== null && alt.trim().length > 0) {
              result += `[image: ${alt}]`;
            }
          }
        } else if (/^h[1-6]$/.test(tag)) {
          if (inBody) {
            const level: number = parseInt(tag.charAt(1), 10);
            result += `${'#'.repeat(level)} `;
          }
        } else if (tag === 'strong' || tag === 'b') {
          if (inBody) result += '**';
        } else if (tag === 'em' || tag === 'i') {
          if (inBody) result += '*';
        } else if (tag === 'hr') {
          if (inBody) result += '\n---\n';
        } else if (tag === 'blockquote') {
          if (inBody) result += '> ';
        }
      } else if (parser.eventType === 'text') {
        if (inBody) {
          const raw: string | null = parser.text;
          const text: string | null = raw === null
            ? null
            : raw.replace(/\n/g, ' ').replace(/\r/g, ' ').replace(/\s+/g, ' ');
          if (text !== null && text.trim().length > 0) {
            result += text;
          }
        }
      } else if (parser.eventType === 'end_tag') {
        const tag: string = parser.name.toLowerCase();
        if (tagStack.length > 0) tagStack.pop();
        if (tag === 'body') {
          inBody = false;
        } else if (tag === 'p' || tag === 'div') {
          if (inBody) result += '\n\n';
        } else if (/^h[1-6]$/.test(tag)) {
          if (inBody) result += '\n\n';
        } else if (tag === 'li') {
          if (inBody) result += '\n';
        } else if (tag === 'ul' || tag === 'ol') {
          if (inBody) result += '\n';
        } else if (tag === 'br') {
          // Android when 分支空体
        } else if (tag === 'strong' || tag === 'b') {
          if (inBody) result += '**';
        } else if (tag === 'em' || tag === 'i') {
          if (inBody) result += '*';
        } else if (tag === 'blockquote') {
          if (inBody) result += '\n';
        }
      }
      try {
        parser.next();
      } catch {
        break; // EpubParser.kt:217-221 畸形容忍
      }
    }
    return result.replace(/\n{3,}/g, '\n\n').trim();
  } catch {
    return ''; // EpubParser.kt:227-229 静默
  }
};
