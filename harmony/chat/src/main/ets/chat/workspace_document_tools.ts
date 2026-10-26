import { parseDocxFromZip } from './docx_parser.ts';
import type { JsonValue } from './json.ts';
import type { UIMessagePart } from './message.ts';
import { parsePptxFromZip } from './pptx_parser.ts';
import type { AgentTool, InputSchemaObj } from './tool.ts';
import { makeAgentTool } from './tool.ts';
import { MAX_ARCHIVE_ENTRY_BYTES } from './workspace_artifact_files.ts';
import type { ArtifactToolTracker } from './workspace_artifact_tool.ts';
import { inputLimit, integerProp, stringProp, textJson } from './workspace_artifact_tool.ts';
import { PosixWorkspaceManager, toolInputRequiredString } from './workspace.ts';
import type { XmlPullFactory, ZipEntryTextProvider } from './xml_pull.ts';
import type { InflateRawPort, ZipEntryRecord } from './zip_archive.ts';
import { readZipEntries, readZipEntryText } from './zip_archive.ts';

export const MAX_WORKSPACE_TEMP_FILE_BYTES: number = 64 * 1024 * 1024;

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

// ===== office_read 路由(:283-300) =====

export const OFFICE_UNSUPPORTED = (path: string): string => `Unsupported Office extension: ${path}`;

const officeText = async (
  path: string, bytes: Uint8Array, deps: WorkspaceDocumentToolsDeps,
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

export interface WorkspaceDocumentToolsDeps {
  workspaceManager: PosixWorkspaceManager;
  inflateRaw: InflateRawPort;
  newXmlParser: XmlPullFactory;
}

export const createWorkspaceDocumentTools = (deps: WorkspaceDocumentToolsDeps, track: ArtifactToolTracker): AgentTool[] => {
  const mgr: PosixWorkspaceManager = deps.workspaceManager;

  return [
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
    })
  ];
};
