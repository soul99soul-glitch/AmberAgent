// pptx_parser — PptxParser.kt 全文移植(D-113)
// Android 基准:document/src/main/java/app/amber/document/PptxParser.kt(453 行全文)
//   - ZipFile 枚举序 = 中央目录序(zip_archive.readZipEntries 忠实)
//   - **排序 quirk 逐字保留**:sortKey = name.substringAfter('slide')
//     .substringBefore('.xml').toIntOrNull() ?: 0 — 'slide' 首现于 'slides'
//     段 → 恒非数字 → 键恒 0 → 稳定排序 = 保持 CD 枚举序
//   - slideNumber = 排序后 index+1;notesSlide<N>.xml 同用该 index(Android 原样)
//   - '## Slide N' / '### Speaker Notes' 输出形态;slide XML 异常 →
//     'Error parsing slide XML: <msg>\n' 内联;notes 异常 → '' 静默
import type { XmlPullEvent, XmlPullFactory, XmlPullPort } from './xml_pull.ts';
import type { InflateRawPort, ZipEntryRecord } from './zip_archive.ts';
import { readZipEntries, readZipEntryText } from './zip_archive.ts';

interface SlideContent {
  slideNumber: number;
  content: string;
  notes: string;
}

// 排序键(PptxParser.kt:26 逐字;quirk 注释见文件头)
const slideSortKey = (name: string): number => {
  const after: string = name.substring(name.indexOf('slide') + 'slide'.length);
  const dot: number = after.indexOf('.xml');
  const before: string = dot >= 0 ? after.substring(0, dot) : after;
  return /^[+-]?\d+$/.test(before) ? parseInt(before, 10) : 0;
};

// PptxParser.kt:18-57 parse(File)
export const parsePptxFromZip = async (
  zipBytes: Uint8Array, inflateRaw: InflateRawPort, newParser: XmlPullFactory,
): Promise<string> => {
  try {
    const entries: ZipEntryRecord[] = readZipEntries(zipBytes);
    const slideEntries: ZipEntryRecord[] = entries
      .filter((e: ZipEntryRecord): boolean => /^ppt\/slides\/slide\d+\.xml$/.test(e.name));
    // 稳定排序(JS Array.sort 稳定 = Kotlin sortedBy 稳定)
    const sorted: ZipEntryRecord[] = [...slideEntries].sort(
      (a: ZipEntryRecord, b: ZipEntryRecord): number => slideSortKey(a.name) - slideSortKey(b.name));
    if (sorted.length === 0) {
      return 'No slides found in PPTX file';
    }
    const slides: SlideContent[] = [];
    for (let index: number = 0; index < sorted.length; index++) {
      const slideNumber: number = index + 1;
      const slideXml: string = await readZipEntryText(zipBytes, sorted[index], inflateRaw);
      const slideContent: string = parsePptxSlideXml(newParser, slideXml);
      // notesSlide<slideNumber>.xml(index 序,Android 原样);未命中 → ''
      const notesName: string = `ppt/notesSlides/notesSlide${slideNumber}.xml`;
      const notesRec: ZipEntryRecord | undefined =
        entries.find((e: ZipEntryRecord): boolean => e.name === notesName);
      let notes: string = '';
      if (notesRec !== undefined) {
        const notesXml: string = await readZipEntryText(zipBytes, notesRec, inflateRaw);
        notes = parsePptxNotesXml(newParser, notesXml);
      }
      slides.push({ slideNumber, content: slideContent, notes });
    }
    return formatPptxOutput(slides);
  } catch (e) {
    const msg: string = e instanceof Error ? e.message : String(e);
    return `Error parsing PPTX file: ${msg}`;
  }
};

// PptxParser.kt:59-75 formatOutput(结果 trim)
const formatPptxOutput = (slides: SlideContent[]): string => {
  let result: string = '';
  for (const slide of slides) {
    result += `## Slide ${slide.slideNumber}\n\n`;
    result += slide.content;
    if (slide.notes.trim().length > 0) {
      result += '\n### Speaker Notes\n\n';
      result += slide.notes;
    }
    result += '\n';
  }
  return result.trim();
};

interface StringSink { append: (s: string) => void; }

const pullEvent = (parser: XmlPullPort): XmlPullEvent => parser.eventType;

// PptxParser.kt:77-102 parseSlideXml(sp/graphicFrame 分发;**不 trim**;
//   异常 → 'Error parsing slide XML: <msg>\n' 内联进结果)
export const parsePptxSlideXml = (newParser: XmlPullFactory, xmlText: string): string => {
  try {
    const parser: XmlPullPort = newParser(xmlText);
    let result: string = '';
    const sink: StringSink = { append: (s: string): void => { result += s; } };
    while (parser.eventType !== 'end_document') {
      if (parser.eventType === 'start_tag') {
        if (parser.name === 'sp') {
          processShape(parser, sink);
        } else if (parser.name === 'graphicFrame') {
          processGraphicFrame(parser, sink);
        }
      }
      parser.next();
    }
    return result;
  } catch (e) {
    const msg: string = e instanceof Error ? e.message : String(e);
    return `Error parsing slide XML: ${msg}\n`;
  }
};

// PptxParser.kt:104-138 processShape(非空文本 + '\n\n';
//   hasBullet/bulletLevel/isNumbered 形状级变量赋值后未用 — Android 原样保留语义)
const processShape = (parser: XmlPullPort, result: StringSink): void => {
  const shapeStartDepth: number = parser.depth;
  let textContent: string = '';
  const content: StringSink = { append: (s: string): void => { textContent += s; } };

  while (parser.next() !== 'end_document') {
    if (parser.eventType === 'start_tag') {
      if (parser.name === 'p') {
        processParagraph(parser, content);
      }
    } else if (parser.eventType === 'end_tag') {
      if (parser.name === 'sp' && parser.depth === shapeStartDepth) break;
    }
  }

  const text: string = textContent.trim();
  if (text.length > 0) {
    result.append(text);
    result.append('\n\n');
  }
};

// PptxParser.kt:140-186 processParagraph(bullet → 缩进+'1. '/'- ';否则原文+'\n')
const processParagraph = (parser: XmlPullPort, result: StringSink): void => {
  const paragraphStartDepth: number = parser.depth;
  let paragraphText: string = '';
  const sink: StringSink = { append: (s: string): void => { paragraphText += s; } };
  let hasBullet: boolean = false;
  let bulletLevel: number = 0;
  let isNumbered: boolean = false;

  while (parser.next() !== 'end_document') {
    if (parser.eventType === 'start_tag') {
      if (parser.name === 'pPr') {
        const info: BulletInfo = extractBulletInfo(parser);
        hasBullet = info.hasBullet;
        bulletLevel = info.level;
        isNumbered = info.isNumbered;
      } else if (parser.name === 'r') {
        extractTextRun(parser, sink);
      }
    } else if (parser.eventType === 'end_tag') {
      if (parser.name === 'p' && parser.depth === paragraphStartDepth) break;
    }
  }

  const text: string = paragraphText.trim();
  if (text.length > 0) {
    if (hasBullet) {
      const indent: string = '  '.repeat(bulletLevel);
      const marker: string = isNumbered ? '1. ' : '- ';
      result.append(`${indent}${marker}${text}\n`);
    } else {
      result.append(`${text}\n`);
    }
  }
};

// Triple<Boolean, Int, Boolean> 等价
interface BulletInfo {
  hasBullet: boolean;
  level: number;
  isNumbered: boolean;
}

// PptxParser.kt:188-225 extractBulletInfo(buChar/buAutoNum/lvl val)
const extractBulletInfo = (parser: XmlPullPort): BulletInfo => {
  const pPrStartDepth: number = parser.depth;
  let hasBullet: boolean = false;
  let level: number = 0;
  let isNumbered: boolean = false;

  while (parser.next() !== 'end_document') {
    if (parser.eventType === 'start_tag') {
      if (parser.name === 'buChar') {
        hasBullet = true;
        isNumbered = false;
      } else if (parser.name === 'buAutoNum') {
        hasBullet = true;
        isNumbered = true;
      } else if (parser.name === 'lvl') {
        const v: string | null = parser.getAttributeValue(null, 'val');
        if (v !== null) {
          level = /^[+-]?\d+$/.test(v) ? parseInt(v, 10) : 0;
        }
      }
    } else if (parser.eventType === 'end_tag') {
      if (parser.name === 'pPr' && parser.depth === pPrStartDepth) break;
    }
  }
  return { hasBullet, level, isNumbered };
};

// PptxParser.kt:227-248 extractTextRun(t → 单 TEXT 追加,无 markdown)
const extractTextRun = (parser: XmlPullPort, result: StringSink): void => {
  const runStartDepth: number = parser.depth;

  while (parser.next() !== 'end_document') {
    if (parser.eventType === 'start_tag') {
      if (parser.name === 't') {
        parser.next();
        if (pullEvent(parser) === 'text') {
          result.append(parser.text ?? '');
        }
      }
    } else if (parser.eventType === 'end_tag') {
      if (parser.name === 'r' && parser.depth === runStartDepth) break;
    }
  }
};

// PptxParser.kt:250-268 processGraphicFrame(tbl 分发)
const processGraphicFrame = (parser: XmlPullPort, result: StringSink): void => {
  const frameStartDepth: number = parser.depth;

  while (parser.next() !== 'end_document') {
    if (parser.eventType === 'start_tag') {
      if (parser.name === 'tbl') {
        processTable(parser, result);
      }
    } else if (parser.eventType === 'end_tag') {
      if (parser.name === 'graphicFrame' && parser.depth === frameStartDepth) break;
    }
  }
};

// PptxParser.kt:270-316 processTable(markdown;尾 '\n' **在 if 内**(与 docx 不同))
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
    for (let index: number = 0; index < rows.length; index++) {
      const row: string[] = rows[index];
      result.append('| ');
      for (let colIndex: number = 0; colIndex < maxCols; colIndex++) {
        const cellContent: string = colIndex < row.length ? row[colIndex] : '';
        result.append(`${cellContent} | `);
      }
      result.append('\n');
      if (index === 0) {
        result.append('| ');
        for (let i: number = 0; i < maxCols; i++) {
          result.append('--- | ');
        }
        result.append('\n');
      }
    }
    result.append('\n');
  }
};

// PptxParser.kt:318-340 extractTableRow
const extractTableRow = (parser: XmlPullPort): string[] => {
  const rowStartDepth: number = parser.depth;
  const cells: string[] = [];

  while (parser.next() !== 'end_document') {
    if (parser.eventType === 'start_tag') {
      if (parser.name === 'tc') {
        cells.push(extractTableCell(parser));
      }
    } else if (parser.eventType === 'end_tag') {
      if (parser.name === 'tr' && parser.depth === rowStartDepth) break;
    }
  }
  return cells;
};

// PptxParser.kt:342-369 extractTableCell(多 t 文本 ' ' 连接;trim)
const extractTableCell = (parser: XmlPullPort): string => {
  const cellStartDepth: number = parser.depth;
  let result: string = '';

  while (parser.next() !== 'end_document') {
    if (parser.eventType === 'start_tag') {
      if (parser.name === 't') {
        parser.next();
        if (pullEvent(parser) === 'text') {
          if (result.length > 0) result += ' ';
          result += parser.text ?? '';
        }
      }
    } else if (parser.eventType === 'end_tag') {
      if (parser.name === 'tc' && parser.depth === cellStartDepth) break;
    }
  }
  return result.trim();
};

// PptxParser.kt:371-402 parseNotesXml(sp → isNotesTextShape → extractShapeText;
//   结果 trim;**异常 → '' 静默**)
export const parsePptxNotesXml = (newParser: XmlPullFactory, xmlText: string): string => {
  try {
    const parser: XmlPullPort = newParser(xmlText);
    let result: string = '';
    const sink: StringSink = { append: (s: string): void => { result += s; } };
    while (parser.eventType !== 'end_document') {
      if (parser.eventType === 'start_tag') {
        if (parser.name === 'sp') {
          const inNotesShape: boolean = isNotesTextShape(parser);
          if (inNotesShape) {
            extractShapeText(parser, sink);
          }
        }
      }
      parser.next();
    }
    return result.trim();
  } catch {
    return '';
  }
};

// PptxParser.kt:404-426 isNotesTextShape(**消费事件流**:
//   前瞻 ph → type=='body';END_TAG depth<=currentDepth → false;
//   originalPosition 死变量不移植)
const isNotesTextShape = (parser: XmlPullPort): boolean => {
  const currentDepth: number = parser.depth;

  while (parser.next() !== 'end_document') {
    if (parser.eventType === 'start_tag') {
      if (parser.name === 'ph') {
        const type: string | null = parser.getAttributeValue(null, 'type');
        return type === 'body';
      }
    } else if (parser.eventType === 'end_tag') {
      if (parser.depth <= currentDepth) return false;
    }
  }
  return false;
};

// PptxParser.kt:428-452 extractShapeText(t 追加;p END_TAG → '\n';sp 同深度 break)
const extractShapeText = (parser: XmlPullPort, result: StringSink): void => {
  const shapeStartDepth: number = parser.depth;

  while (parser.next() !== 'end_document') {
    if (parser.eventType === 'start_tag') {
      if (parser.name === 't') {
        parser.next();
        if (pullEvent(parser) === 'text') {
          result.append(parser.text ?? '');
        }
      }
    } else if (parser.eventType === 'end_tag') {
      if (parser.name === 'sp' && parser.depth === shapeStartDepth) break;
      if (parser.name === 'p') {
        result.append('\n');
      }
    }
  }
};
