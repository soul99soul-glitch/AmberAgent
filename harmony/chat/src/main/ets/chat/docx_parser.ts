// docx_parser — DocxParser.kt 全文移植(D-112)
// Android 基准:document/src/main/java/app/amber/document/DocxParser.kt(349 行全文)
//   - parse:zip 扫 'word/document.xml' → parseDocumentXml;未命中/异常文案逐字
//   - 段落:heading(pStyle HeadingN/headingN)→ '#'×N;列表(numPr)→ 缩进+'- '/'1. '
//     (**number 恒 1 = Android 原样,不修正**);普通 → 原文 + '\n\n'
//   - 行内:rPr b/i → **/*/*** 包裹;表格 → markdown(首行后 '---' 分隔,尾 '\n')
//   - 单元格多段落以 ' ' 连接;文档级 trim
// 端口化:ZipInputStream/XmlPullParser → ZipEntryTextProvider/XmlPullPort(xml_pull.ts)
import type { XmlPullEvent, XmlPullFactory, XmlPullPort, ZipEntryTextProvider } from './xml_pull.ts';

interface ListInfo {
  level: number;
  isNumbered: boolean;
  number: number;
}

interface ParagraphProperties {
  listInfo: ListInfo | null;
  headingLevel: number;
}

// DocxParser.kt:21-38 parse(File) — zip 扫描 + 两文案逐字
export const parseDocxFromZip = async (
  entryText: ZipEntryTextProvider, newParser: XmlPullFactory,
): Promise<string> => {
  try {
    const xml: string | null = await entryText('word/document.xml');
    if (xml === null) {
      return 'Unable to find document content in DOCX file';
    }
    return parseDocxDocumentXml(newParser, xml);
  } catch (e) {
    const msg: string = e instanceof Error ? e.message : String(e);
    return `Error parsing DOCX file: ${msg}`;
  }
};

// DocxParser.kt:40-70 parseDocumentXml(body 内 p/tbl 分发;结果 trim)
export const parseDocxDocumentXml = (newParser: XmlPullFactory, xmlText: string): string => {
  try {
    const parser: XmlPullPort = newParser(xmlText);
    let result: string = '';
    let inBody: boolean = false;
    while (parser.eventType !== 'end_document') {
      if (parser.eventType === 'start_tag') {
        if (parser.name === 'body') {
          inBody = true;
        } else if (parser.name === 'p') {
          if (inBody) processParagraph(parser, { append: (s: string): void => { result += s; } });
        } else if (parser.name === 'tbl') {
          if (inBody) processTable(parser, { append: (s: string): void => { result += s; } });
        }
      } else if (parser.eventType === 'end_tag') {
        if (parser.name === 'body') inBody = false;
      }
      parser.next();
    }
    return result.trim();
  } catch (e) {
    const msg: string = e instanceof Error ? e.message : String(e);
    return `Error parsing document XML: ${msg}`;
  }
};

// StringBuilder 等价(ArkTS 闭包改写限制 → 显式 sink)
interface StringSink { append: (s: string) => void; }

// next() 副作用后重读事件(TS 属性窄化不感知副作用 → 函数边界复位)
const pullEvent = (parser: XmlPullPort): XmlPullEvent => parser.eventType;
const pullName = (parser: XmlPullPort): string => parser.name;

// DocxParser.kt:72-115 processParagraph(列表/标题/普通三态输出)
const processParagraph = (parser: XmlPullPort, result: StringSink): void => {
  const paragraphStartDepth: number = parser.depth;
  let paragraphContent: string = '';
  let listInfo: ListInfo | null = null;
  let headingLevel: number = 0;
  const content: StringSink = { append: (s: string): void => { paragraphContent += s; } };

  while (parser.next() !== 'end_document') {
    if (parser.eventType === 'start_tag') {
      if (parser.name === 'r') {
        extractRunText(parser, content);
      } else if (parser.name === 'pPr') {
        const props: ParagraphProperties = extractParagraphProperties(parser);
        listInfo = props.listInfo;
        headingLevel = props.headingLevel;
      }
    } else if (parser.eventType === 'end_tag') {
      if (parser.name === 'p' && parser.depth === paragraphStartDepth) break;
    }
  }

  const paragraphText: string = paragraphContent.trim();
  if (paragraphText.length > 0) {
    if (listInfo !== null) {
      const indent: string = '  '.repeat(listInfo.level);
      const marker: string = listInfo.isNumbered ? `${listInfo.number}. ` : '- ';
      result.append(`${indent}${marker}${paragraphText}\n`);
    } else if (headingLevel > 0) {
      const headingPrefix: string = '#'.repeat(headingLevel);
      result.append(`${headingPrefix} ${paragraphText}\n\n`);
    } else {
      result.append(`${paragraphText}\n\n`);
    }
  }
};

// DocxParser.kt:117-156 extractRunText(rPr b/i → markdown 包裹;单个 t 文本节点)
const extractRunText = (parser: XmlPullPort, result: StringSink): void => {
  const runStartDepth: number = parser.depth;
  let isBold: boolean = false;
  let isItalic: boolean = false;

  while (parser.next() !== 'end_document') {
    if (parser.eventType === 'start_tag') {
      if (parser.name === 'rPr') {
        const formatting: boolean[] = extractFormatting(parser);
        isBold = formatting[0];
        isItalic = formatting[1];
      } else if (parser.name === 't') {
        parser.next();
        if (pullEvent(parser) === 'text') {
          const raw: string = parser.text ?? '';
          let text: string = raw;
          if (isBold && isItalic) {
            text = `***${raw}***`;
          } else if (isBold) {
            text = `**${raw}**`;
          } else if (isItalic) {
            text = `*${raw}*`;
          }
          result.append(text);
        }
      }
    } else if (parser.eventType === 'end_tag') {
      if (parser.name === 'r' && parser.depth === runStartDepth) break;
    }
  }
};

// DocxParser.kt:158-180 extractFormatting(rPr 内 b/i 存在性)
const extractFormatting = (parser: XmlPullPort): boolean[] => {
  const rPrStartDepth: number = parser.depth;
  let isBold: boolean = false;
  let isItalic: boolean = false;

  while (parser.next() !== 'end_document') {
    if (parser.eventType === 'start_tag') {
      if (parser.name === 'b') {
        isBold = true;
      } else if (parser.name === 'i') {
        isItalic = true;
      }
    } else if (parser.eventType === 'end_tag') {
      if (parser.name === 'rPr' && parser.depth === rPrStartDepth) break;
    }
  }
  return [isBold, isItalic];
};

// DocxParser.kt:182-228 processTable(markdown;maxCols 补空;首行后分隔;尾 '\n')
const processTable = (parser: XmlPullPort, result: StringSink): void => {
  const tableStartDepth: number = parser.depth;
  const rows: string[][] = [];

  while (parser.next() !== 'end_document') {
    if (parser.eventType === 'start_tag') {
      if (parser.name === 'tr') {
        const cells: string[] = extractTableRow(parser);
        if (cells.length > 0) rows.push(cells);
      }
    } else if (parser.eventType === 'end_tag') {
      if (parser.name === 'tbl' && parser.depth === tableStartDepth) break;
    }
  }

  if (rows.length > 0) {
    let maxCols: number = 0;
    for (const row of rows) {
      if (row.length > maxCols) maxCols = row.length;
    }
    for (let index = 0; index < rows.length; index++) {
      const row: string[] = rows[index];
      result.append('| ');
      for (let colIndex = 0; colIndex < maxCols; colIndex++) {
        const cellContent: string = colIndex < row.length ? row[colIndex] : '';
        result.append(`${cellContent} | `);
      }
      result.append('\n');
      if (index === 0) {
        result.append('| ');
        for (let i = 0; i < maxCols; i++) {
          result.append('--- | ');
        }
        result.append('\n');
      }
    }
  }
  result.append('\n');
};

// DocxParser.kt:230-251 extractTableRow(tc 收集)
const extractTableRow = (parser: XmlPullPort): string[] => {
  const rowStartDepth: number = parser.depth;
  const cells: string[] = [];

  while (parser.next() !== 'end_document') {
    if (parser.eventType === 'start_tag') {
      if (parser.name === 'tc') {
        cells.push(extractCellText(parser));
      }
    } else if (parser.eventType === 'end_tag') {
      if (parser.name === 'tr' && parser.depth === rowStartDepth) break;
    }
  }
  return cells;
};

// DocxParser.kt:253-279 extractCellText(多段落 ' ' 连接;trim)
const extractCellText = (parser: XmlPullPort): string => {
  const cellStartDepth: number = parser.depth;
  let result: string = '';

  while (parser.next() !== 'end_document') {
    if (parser.eventType === 'start_tag') {
      if (parser.name === 'p') {
        const paragraphText: string = extractCellParagraphText(parser);
        if (paragraphText.trim().length > 0) {
          if (result.length > 0) result += ' ';
          result += paragraphText;
        }
      }
    } else if (parser.eventType === 'end_tag') {
      if (parser.name === 'tc' && parser.depth === cellStartDepth) break;
    }
  }
  return result.trim();
};

// DocxParser.kt:281-301 extractCellParagraphText(行内 r 复用 markdown 包裹)
const extractCellParagraphText = (parser: XmlPullPort): string => {
  const paragraphStartDepth: number = parser.depth;
  let result: string = '';
  const sink: StringSink = { append: (s: string): void => { result += s; } };

  while (parser.next() !== 'end_document') {
    if (parser.eventType === 'start_tag') {
      if (parser.name === 'r') {
        extractRunText(parser, sink);
      }
    } else if (parser.eventType === 'end_tag') {
      if (parser.name === 'p' && parser.depth === paragraphStartDepth) break;
    }
  }
  return result.trim();
};

// Kotlin String.toIntOrNull(严格全串,可带符号;JS parseInt 前缀解析不忠实)
const toIntOrNull = (v: string): number | null => {
  if (!/^[+-]?\d+$/.test(v)) return null;
  const n: number = parseInt(v, 10);
  return isNaN(n) ? null : n;
};

// DocxParser.kt:303-348 extractParagraphProperties
//   (pStyle Heading*/heading* → 末位数字(digitToIntOrNull ?: 1);
//    numPr:ilvl val toIntOrNull ?: 0,numId val 存在 → isNumbered;
//    listInfo = (listLevel>0 || isNumbered) → ListInfo(number 恒 1,Android 原样))
const extractParagraphProperties = (parser: XmlPullPort): ParagraphProperties => {
  const pPrStartDepth: number = parser.depth;
  let listLevel: number = 0;
  let isNumbered: boolean = false;
  let headingLevel: number = 0;

  while (parser.next() !== 'end_document') {
    if (parser.eventType === 'start_tag') {
      if (parser.name === 'pStyle') {
        const styleVal: string | null = parser.getAttributeValue(null, 'val');
        if (styleVal !== null && (styleVal.startsWith('Heading') || styleVal.startsWith('heading'))) {
          const last: string = styleVal.charAt(styleVal.length - 1);
          const digit: number = parseInt(last, 10);
          headingLevel = (!isNaN(digit) && last >= '0' && last <= '9') ? digit : 1;
        }
      } else if (parser.name === 'numPr') {
        const numPrStartDepth: number = parser.depth;
        while (parser.next() !== 'end_document') {
          // next() 后重读(函数边界复位 TS 窄化)
          const ev: XmlPullEvent = pullEvent(parser);
          const nm: string = pullName(parser);
          if (ev === 'start_tag') {
            if (nm === 'ilvl') {
              const v: string | null = parser.getAttributeValue(null, 'val');
              listLevel = v !== null ? (toIntOrNull(v) ?? 0) : 0;
            } else if (nm === 'numId') {
              isNumbered = parser.getAttributeValue(null, 'val') !== null;
            }
          } else if (ev === 'end_tag') {
            if (nm === 'numPr' && parser.depth === numPrStartDepth) break;
          }
        }
      }
    } else if (parser.eventType === 'end_tag') {
      if (parser.name === 'pPr' && parser.depth === pPrStartDepth) break;
    }
  }

  const listInfo: ListInfo | null = (listLevel > 0 || isNumbered)
    ? { level: listLevel, isNumbered: isNumbered, number: 1 }
    : null;
  return { listInfo, headingLevel };
};
