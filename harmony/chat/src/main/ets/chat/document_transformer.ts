// document_transformer — 文档内联为 prompt(D-074)
// Android 基准: app/.../core/ai/transformers/DocumentAsPromptTransformer.kt(全文 144 行)
//   - transform(:21-44):document parts 逐个 readDocumentContent → prompt add(0)
//     (每个 doc 前插 → 最终逆序;恒量 copy 无短路)
//   - prompt 模板(:29-36):trimMargin() 无 '|' → **缩进原样保留**(Android quirk 钉住);
//     空行 → '',故首行 '\n' 起、末行 18 空格行 → ''
//   - readDocumentContent(:73-128):错误文案逐字/64MB 上限/mime 分发/文本截断
//     /二进制占位;getOrElse → '[ERROR, failed to read file: ...]'
//   - isLikelyTextFile(:130-140):mime text/* 或 41 扩展名集
// 偏差(登记):
//   - androidx toUri().toFile()/File IO → DocumentReaderDeps Port(statFile 非法
//     uri → null;readTextFile/parse* 注入);PDF/DOCX/PPTX/EPUB 解析(document
//     模块未移植)entry 不提供 parse* → 抛错 → '[ERROR, failed to read file]'
//     = P1(与 Android 解析失败同文案路径)
//   - OfficeNativeSwitch(native .so 桥)不移植
import type { UIMessage, UIMessagePart, UIMessagePartDocument } from './message.ts';
import type { MessageTransformer, TransformerContext } from './transformer_pipeline.ts';

export const MAX_INLINE_TEXT_CHARS: number = 512 * 1024; // :142
export const MAX_INLINE_FILE_BYTES: number = 64 * 1024 * 1024; // :143

// androidx File 形态(uri 合法但可能不存在/非普通文件)
export interface DocumentFileHandle {
  exists: boolean;
  isFile: boolean;
  sizeBytes: number;
  absolutePath: string;
}

export interface DocumentReaderDeps {
  // document.url.toUri().toFile() — 非法 uri(非 file scheme)→ null
  statFile: (document: UIMessagePartDocument) => Promise<DocumentFileHandle | null>;
  // bufferedReader 读 maxCharsPlusOne 字符上限(:90-97)
  readTextFile: (handle: DocumentFileHandle, maxCharsPlusOne: number) => Promise<string>;
  // :84-87 四 mime 解析(PdfParser/Docx/Pptx/Epub);未提供或抛错 → ERROR 文案
  parsePdf?: (handle: DocumentFileHandle) => Promise<string>;
  parseDocx?: (handle: DocumentFileHandle) => Promise<string>;
  parsePptx?: (handle: DocumentFileHandle) => Promise<string>;
  parseEpub?: (handle: DocumentFileHandle) => Promise<string>;
}

const SP: string = '                  '; // 18 空格(模板缩进逐字)

// :130-140 扩展名集(41 项,逐字)
const TEXT_FILE_EXTENSIONS: string[] = [
  'txt', 'md', 'markdown', 'mdx', 'csv', 'json', 'jsonl', 'xml', 'html', 'htm',
  'css', 'js', 'ts', 'tsx', 'jsx', 'py', 'java', 'kt', 'kts', 'swift', 'go',
  'rs', 'c', 'h', 'cpp', 'hpp', 'cs', 'sh', 'bash', 'zsh', 'fish', 'rb',
  'php', 'sql', 'yml', 'yaml', 'toml', 'ini', 'conf', 'gradle', 'properties',
  'log', 'svg',
];

// substringAfterLast('.', missingDelimiterValue = "") + lowercase in set
export const isLikelyTextFile = (document: UIMessagePartDocument): boolean => {
  if (document.mime.startsWith('text/')) return true;
  const idx: number = document.fileName.lastIndexOf('.');
  const ext: string = idx >= 0 ? document.fileName.substring(idx + 1) : '';
  return TEXT_FILE_EXTENSIONS.includes(ext.toLowerCase());
};

const truncateIfNeeded = (content: string): string =>
  content.length > MAX_INLINE_TEXT_CHARS
    ? `${content.substring(0, MAX_INLINE_TEXT_CHARS)}\n[TRUNCATED: document text exceeds ${MAX_INLINE_TEXT_CHARS} characters]`
    : content;

const binaryPlaceholder = (document: UIMessagePartDocument, handle: DocumentFileHandle): string =>
  `[BINARY_OR_ARCHIVE_FILE]\nname: ${document.fileName}\nmime: ${document.mime}\n` +
  `size_bytes: ${handle.sizeBytes}\nlocal_path: ${handle.absolutePath}\n` +
  'The file is attached but was not inlined as text. Use available tools, such as terminal_execute, to inspect, extract, or process it.\n';

// :73-128 全文忠实
export const readDocumentContent = async (
  document: UIMessagePartDocument, deps: DocumentReaderDeps,
): Promise<string> => {
  const handle: DocumentFileHandle | null = await deps.statFile(document);
  if (handle === null) return `[ERROR, invalid file uri: ${document.fileName}]`;
  if (!handle.exists || !handle.isFile) {
    return `[ERROR, file not found: ${document.fileName}]`;
  }
  if (handle.sizeBytes > MAX_INLINE_FILE_BYTES) {
    return `[ERROR, file too large to inline: ${document.fileName} (${handle.sizeBytes} bytes)]`;
  }
  try {
    let content: string;
    if (document.mime === 'application/pdf') {
      if (deps.parsePdf === undefined) throw new Error('pdf parser unavailable');
      content = await deps.parsePdf(handle);
    } else if (document.mime === 'application/vnd.openxmlformats-officedocument.wordprocessingml.document') {
      if (deps.parseDocx === undefined) throw new Error('docx parser unavailable');
      content = await deps.parseDocx(handle);
    } else if (document.mime === 'application/vnd.openxmlformats-officedocument.presentationml.presentation') {
      if (deps.parsePptx === undefined) throw new Error('pptx parser unavailable');
      content = await deps.parsePptx(handle);
    } else if (document.mime === 'application/epub+zip') {
      if (deps.parseEpub === undefined) throw new Error('epub parser unavailable');
      content = await deps.parseEpub(handle);
    } else if (isLikelyTextFile(document)) {
      content = await deps.readTextFile(handle, MAX_INLINE_TEXT_CHARS + 1);
    } else {
      content = binaryPlaceholder(document, handle);
    }
    return truncateIfNeeded(content);
  } catch {
    return `[ERROR, failed to read file: ${document.fileName}]`;
  }
};

// prompt 模板(:29-36;trimMargin() 无 '|' → 缩进保留,空行 → '')
export const buildDocumentPrompt = (fileName: string, content: string): string =>
  `\n${SP}## user sent a file: ${fileName}\n${SP}<content>\n${SP}\`\`\`\n` +
  `${SP}${content}\n${SP}\`\`\`\n${SP}</content>\n`;

// transform(:21-44):每个 document 的 prompt add(0)(逆序效果忠实);
//   恒量 copy(Android message.copy + toMutableList 无短路)
export const createDocumentTransformer = (deps: DocumentReaderDeps): MessageTransformer => ({
  transform: async (_ctx: TransformerContext, messages: UIMessage[]): Promise<UIMessage[]> => {
    const out: UIMessage[] = [];
    for (const message of messages) {
      const documents = message.parts.filter(
        (p: UIMessagePart): boolean => p.type === 'document') as UIMessagePartDocument[];
      const parts: UIMessagePart[] = [...message.parts];
      for (const document of documents) {
        const content: string = await readDocumentContent(document, deps);
        parts.unshift({ type: 'text', text: buildDocumentPrompt(document.fileName, content), metadata: null });
      }
      out.push({ ...message, parts });
    }
    return out;
  },
});
