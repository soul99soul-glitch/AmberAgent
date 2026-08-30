// 当前分支书籍导出:只把已解析的章节快照渲染为文本与 EPUB 归档计划。
// 文件系统、分支解析与 ZIP 压缩均由调用方负责，避免领域层耦合 SDK。

import { invalidInput } from './error.ts';

export interface NovelBookChapterSnapshot {
  ordinal: number;
  title: string;
  content: string;
}

export interface NovelBookExportInput {
  bookTitle: string;
  chapters: NovelBookChapterSnapshot[];
}

export type NovelBookZipCompression = 'stored' | 'deflate';

export interface NovelBookZipEntry {
  name: string;
  text: string;
  compression: NovelBookZipCompression;
}

export interface NovelBookEpubMetadata {
  mimetype: 'application/epub+zip';
  rootfilePath: 'OEBPS/package.opf';
  title: string;
  language: 'zh-CN';
  identifier: 'urn:amber:novel:export';
}

export interface NovelBookEpub {
  metadata: NovelBookEpubMetadata;
  entries: NovelBookZipEntry[];
}

export interface NovelBookExport {
  txt: string;
  markdown: string;
  epub: NovelBookEpub;
}

// Entry/Chat 层负责将 entry.text UTF-8 编码，并按 compression 写入 ZIP。
// EPUB 规范要求首条 mimetype 无压缩且位于第一个 local file header。
export interface NovelBookZipEncoder {
  encode(entries: NovelBookZipEntry[]): Promise<Uint8Array>;
}

const EPUB_MIMETYPE: 'application/epub+zip' = 'application/epub+zip';
const EPUB_ROOTFILE: 'OEBPS/package.opf' = 'OEBPS/package.opf';
const EPUB_LANGUAGE: 'zh-CN' = 'zh-CN';
const EPUB_IDENTIFIER: 'urn:amber:novel:export' = 'urn:amber:novel:export';

const normalizedNewlines = (value: string): string => value.replace(/\r\n?/g, '\n');

const requiredTitle = (value: string, label: string): string => {
  const title: string = normalizedNewlines(value).trim();
  if (title.length === 0) throw invalidInput(`${label}不能为空`);
  return title;
};

const chapterDigits = (ordinal: number): string => {
  if (!Number.isInteger(ordinal) || ordinal < 1 || ordinal > 999) {
    throw invalidInput('章节序号必须在 1-999 之间');
  }
  return ordinal.toString().padStart(3, '0');
};

const orderedChapters = (chapters: NovelBookChapterSnapshot[]): NovelBookChapterSnapshot[] => {
  const copies: NovelBookChapterSnapshot[] = [];
  for (let i: number = 0; i < chapters.length; i++) {
    const chapter: NovelBookChapterSnapshot = chapters[i];
    copies.push({
      ordinal: chapter.ordinal,
      title: requiredTitle(chapter.title, '章节标题'),
      content: normalizedNewlines(chapter.content),
    });
  }
  copies.sort((left: NovelBookChapterSnapshot, right: NovelBookChapterSnapshot): number =>
    left.ordinal - right.ordinal);
  let previousOrdinal: number = 0;
  for (let i: number = 0; i < copies.length; i++) {
    const ordinal: number = copies[i].ordinal;
    chapterDigits(ordinal);
    if (ordinal === previousOrdinal) throw invalidInput(`章节序号重复: ${ordinal}`);
    previousOrdinal = ordinal;
  }
  return copies;
};

const xmlEscape = (value: string): string => value
  .replace(/&/g, '&amp;')
  .replace(/</g, '&lt;')
  .replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;')
  .replace(/'/g, '&#39;');

const chapterLabel = (chapter: NovelBookChapterSnapshot): string =>
  `${chapterDigits(chapter.ordinal)} ${chapter.title}`;

const chapterHref = (chapter: NovelBookChapterSnapshot): string =>
  `text/chapter-${chapterDigits(chapter.ordinal)}.xhtml`;

const chapterXhtml = (chapter: NovelBookChapterSnapshot): string => {
  const lines: string[] = chapter.content.split('\n');
  const document: string[] = [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<!DOCTYPE html>',
    '<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="zh-CN" lang="zh-CN">',
    '  <head>',
    `    <title>${xmlEscape(chapter.title)}</title>`,
    '  </head>',
    '  <body>',
    `    <h1>${xmlEscape(chapterLabel(chapter))}</h1>`,
  ];
  for (let i: number = 0; i < lines.length; i++) {
    document.push(`    <p>${xmlEscape(lines[i])}</p>`);
  }
  document.push('  </body>', '</html>', '');
  return document.join('\n');
};

const containerXml = (): string => [
  '<?xml version="1.0" encoding="UTF-8"?>',
  '<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">',
  '  <rootfiles>',
  `    <rootfile full-path="${EPUB_ROOTFILE}" media-type="application/oebps-package+xml"/>`,
  '  </rootfiles>',
  '</container>',
  '',
].join('\n');

const packageOpf = (bookTitle: string, chapters: NovelBookChapterSnapshot[]): string => {
  const manifest: string[] = [
    '    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>',
  ];
  const spine: string[] = [];
  for (let i: number = 0; i < chapters.length; i++) {
    const chapter: NovelBookChapterSnapshot = chapters[i];
    const id: string = `chapter-${chapterDigits(chapter.ordinal)}`;
    manifest.push(`    <item id="${id}" href="${chapterHref(chapter)}" media-type="application/xhtml+xml"/>`);
    spine.push(`    <itemref idref="${id}"/>`);
  }
  const document: string[] = [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id" xml:lang="zh-CN">',
    '  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">',
    `    <dc:identifier id="book-id">${EPUB_IDENTIFIER}</dc:identifier>`,
    `    <dc:title>${xmlEscape(bookTitle)}</dc:title>`,
    `    <dc:language>${EPUB_LANGUAGE}</dc:language>`,
    '  </metadata>',
    '  <manifest>',
  ];
  for (let i: number = 0; i < manifest.length; i++) document.push(manifest[i]);
  document.push('  </manifest>', '  <spine>');
  for (let i: number = 0; i < spine.length; i++) document.push(spine[i]);
  document.push('  </spine>', '</package>', '');
  return document.join('\n');
};

const navigationXhtml = (bookTitle: string, chapters: NovelBookChapterSnapshot[]): string => {
  const items: string[] = [];
  for (let i: number = 0; i < chapters.length; i++) {
    const chapter: NovelBookChapterSnapshot = chapters[i];
    items.push(`        <li><a href="${chapterHref(chapter)}">${xmlEscape(chapterLabel(chapter))}</a></li>`);
  }
  const document: string[] = [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<!DOCTYPE html>',
    '<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="zh-CN" lang="zh-CN">',
    '  <head>',
    `    <title>${xmlEscape(bookTitle)}</title>`,
    '  </head>',
    '  <body>',
    '    <nav epub:type="toc" id="toc">',
    `      <h1>${xmlEscape(bookTitle)}</h1>`,
    '      <ol>',
  ];
  for (let i: number = 0; i < items.length; i++) document.push(items[i]);
  document.push('      </ol>', '    </nav>', '  </body>', '</html>', '');
  return document.join('\n');
};

const epubPlan = (bookTitle: string, chapters: NovelBookChapterSnapshot[]): NovelBookEpub => {
  const entries: NovelBookZipEntry[] = [
    { name: 'mimetype', text: EPUB_MIMETYPE, compression: 'stored' },
    { name: 'META-INF/container.xml', text: containerXml(), compression: 'deflate' },
    { name: EPUB_ROOTFILE, text: packageOpf(bookTitle, chapters), compression: 'deflate' },
    { name: 'OEBPS/nav.xhtml', text: navigationXhtml(bookTitle, chapters), compression: 'deflate' },
  ];
  for (let i: number = 0; i < chapters.length; i++) {
    const chapter: NovelBookChapterSnapshot = chapters[i];
    entries.push({
      name: `OEBPS/${chapterHref(chapter)}`,
      text: chapterXhtml(chapter),
      compression: 'deflate',
    });
  }
  return {
    metadata: {
      mimetype: EPUB_MIMETYPE,
      rootfilePath: EPUB_ROOTFILE,
      title: bookTitle,
      language: EPUB_LANGUAGE,
      identifier: EPUB_IDENTIFIER,
    },
    entries: entries,
  };
};

export const buildNovelBookExport = (input: NovelBookExportInput): NovelBookExport => {
  const bookTitle: string = requiredTitle(input.bookTitle, '书名');
  const chapters: NovelBookChapterSnapshot[] = orderedChapters(input.chapters);
  const txtSections: string[] = [bookTitle];
  const markdownSections: string[] = [`# ${bookTitle}`];
  for (let i: number = 0; i < chapters.length; i++) {
    const chapter: NovelBookChapterSnapshot = chapters[i];
    txtSections.push(`${chapterLabel(chapter)}\n\n${chapter.content}`);
    markdownSections.push(`## ${chapterLabel(chapter)}\n\n${chapter.content}`);
  }
  return {
    txt: `${txtSections.join('\n\n')}\n`,
    markdown: `${markdownSections.join('\n\n')}\n`,
    epub: epubPlan(bookTitle, chapters),
  };
};

export const encodeNovelBookEpub = async (
  epub: NovelBookEpub, zipEncoder: NovelBookZipEncoder,
): Promise<Uint8Array> => await zipEncoder.encode(epub.entries);
