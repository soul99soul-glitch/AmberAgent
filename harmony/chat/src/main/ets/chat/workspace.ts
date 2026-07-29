// workspace — /workspace 子系统域层(WorkspaceManager.kt 读写照 + WorkspaceTools.kt 六件)
//
// Android 基准(逐字锚点):
//   feature/workspace/.../WorkspaceManager.kt(639 行读写照):
//     list(:45-59)/readText(:61-66)/writeText(:117-132)/editText(:155-170)/
//     move(:172-201)/search(:203-231);requireDocument/findDocument(:373-386)/
//     requireOrCreateFile/Parent(:388-416)/requireFilePath(:441-445)/
//     textMimeTypeForPath(:455-466);WorkspaceState/WorkspaceEntry/EditResult/
//     SearchResult(:590-619)
//   feature/workspace/.../WorkspacePaths.kt:normalize/join(全文)
//   feature/tools/impl/.../WorkspaceTools.kt(286 行全文):
//     file_list/file_read/file_write/file_edit/file_search/file_move 六件 +
//     trackWorkspaceTool(:194-215 activity 事件)+ obj/stringProp/booleanProp/
//     integerProp(:217-238)+ previewText(:240-246)+ activityInputPreview
//     (:248-262)+ FILE_READ_DEFAULT/HARD_MAX + normalizeFileReadMaxChars +
//     buildFileReadJson(:265-286)
//   feature/tools/access/.../ToolJson.kt(全文:string/requiredString/boolean/
//     int/long/textJson)
//   LocalTools.kt:122-126(workspace_files 门 → getTools();同门
//     WorkspaceArtifactTools/ExternalFileTools 两组 = P1 登记)
//
// 偏差适配登记:
//   - SAF DocumentFile/ContentResolver/持久授权层 HarmonyOS 无对应物 →
//     WorkspaceFsPort 注入(entry = fileIo,根 filesDir/amberagent/workspace-
//     mirror,与 WorkspaceManager.kt:23 mirrorDir 同路径语义);SAF↔mirror 双向
//     同步/withMirrorSync/copyUriToUploads/deleteWorkspaceFile 不移植(P1,
//     随文件 sheet/terminal 子系统)
//   - WorkspaceState.configured = treeUri != null → harmony 镜像恒可用 →
//     configured 恒 true(file_list payload workspace_configured 字段;
//     'No AmberAgent workspace selected' 路径不可达,登记)
//   - DocumentFile.type(SAF provider MIME)→ 扩展名推导(textMimeTypeForPath
//     同表;表外 → 'application/octet-stream';目录 → null)
//   - move 跨父复制:copyDocument 逐文件流拷 → 端口 copyFile;renameTo 同父
//     改名 → 端口 rename(POSIX 语义)
//   - 活动事件 runtime 标签 'SAF workspace' 逐字保留(UI 显示串;SAF 概念在
//     harmony 无对应,登记)

import type { JsonObject, JsonValue } from './json.ts';
import { makeAgentTool } from './tool.ts';
import type { AgentTool, InputSchemaObj } from './tool.ts';
import type { UIMessagePart } from './message.ts';
import type { AgentToolActivityStore } from './tool_activity.ts';

// ===== 模型(WorkspaceManager.kt:590-619) =====

export interface WorkspaceEntry {
  path: string;
  name: string;
  directory: boolean;
  sizeBytes: number | null;
  mimeType: string | null;
}

export interface WorkspaceEditResult {
  path: string;
  replaceCount: number;
}

export interface WorkspaceSearchResult {
  path: string;
  lineNumber: number;
  preview: string;
}

// ===== WorkspacePaths.kt(normalize/join 全文逐字) =====

export const normalizeWorkspacePath = (path: string): string => {
  const trimmed: string = path.trim();
  let relative: string;
  if (trimmed.length === 0 || trimmed === '.' || trimmed === '/') {
    relative = '.';
  } else if (trimmed === '/workspace' || trimmed === 'workspace') {
    relative = '.';
  } else if (trimmed.startsWith('/workspace/')) {
    relative = trimmed.substring('/workspace/'.length);
  } else if (trimmed.startsWith('workspace/')) {
    relative = trimmed.substring('workspace/'.length);
  } else if (trimmed.startsWith('/')) {
    throw new Error(`Only /workspace paths are allowed: ${path}`);
  } else {
    relative = trimmed;
  }
  const parts: string[] = relative.split('/')
    .filter((seg: string): boolean => seg.trim().length > 0 && seg !== '.');
  parts.forEach((seg: string): void => {
    if (seg === '..') throw new Error(`Path traversal is not allowed: ${path}`);
  });
  const joined: string = parts.join('/');
  return joined.length === 0 ? '.' : joined;
};

export const joinWorkspacePath = (parent: string, child: string): string =>
  (parent === '.' || parent.length === 0) ? child : `${parent}/${child}`;

// ===== WorkspaceFsPort(POSIX 文件系统最小面;entry = fileIo) =====

export interface WorkspaceFsEntry {
  name: string;
  directory: boolean;
  sizeBytes: number | null; // 目录 → null
}

export interface WorkspaceFsPort {
  // 绝对路径(根内)操作;相对 → 绝对由 PosixWorkspaceManager 拼接
  exists(abs: string): boolean;
  isDirectory(abs: string): boolean;
  isFile(abs: string): boolean;
  // 目录列举(未排序;排序在域层);目录不存在 → 抛由调用侧前置检查
  listNames(abs: string): WorkspaceFsEntry[];
  readText(abs: string): string;
  // append=false → 截断写;父目录须已建(域层 requireOrCreateParent)
  writeText(abs: string, content: string, append: boolean): void;
  // D-125:二进制读写(WorkspaceManager.kt:88-115/:134-153 载体)
  readBytes(abs: string): Uint8Array;
  writeBytes(abs: string, bytes: Uint8Array, append: boolean): void;
  fileSize(abs: string): number;
  mkdirs(abs: string): void;
  // 同父改名/跨设备移动;目标不得已存在(域层前置检查)
  rename(fromAbs: string, toAbs: string): void;
  copyFile(fromAbs: string, toAbs: string): void;
  deleteRecursively(abs: string): boolean;
}

// ===== PosixWorkspaceManager(WorkspaceManager.kt 读写照逻辑;SAF → 端口) =====

export interface PosixWorkspaceManagerDeps {
  port: WorkspaceFsPort;
  // mirrorDir = filesDir/amberagent/workspace-mirror(:23)
  rootAbs: string;
}

export class PosixWorkspaceManager {
  private readonly deps: PosixWorkspaceManagerDeps;

  constructor(deps: PosixWorkspaceManagerDeps) {
    this.deps = deps;
  }

  // WorkspaceState.configured:harmony 镜像恒可用 → true(头注重4)
  configured(): boolean {
    return true;
  }

  // :45-59
  async list(relativePath: string = '.'): Promise<WorkspaceEntry[]> {
    const dir: string = this.requireDocument(relativePath);
    if (!this.deps.port.isDirectory(dir)) {
      throw new Error(`Not a directory: ${relativePath}`);
    }
    const normalized: string = normalizeWorkspacePath(relativePath);
    const entries: WorkspaceFsEntry[] = this.deps.port.listNames(dir);
    const mapped: WorkspaceEntry[] = entries.map((e: WorkspaceFsEntry): WorkspaceEntry => ({
      path: joinWorkspacePath(normalized, e.name),
      name: e.name,
      directory: e.directory,
      sizeBytes: e.directory ? null : e.sizeBytes,
      mimeType: e.directory ? null : textMimeTypeForPath(e.name),
    }));
    // compareBy { !it.isDirectory }.thenBy { name.lowercase() }(false<true → 目录优先)
    mapped.sort((a: WorkspaceEntry, b: WorkspaceEntry): number => {
      if (a.directory !== b.directory) return a.directory ? -1 : 1;
      const an: string = a.name.toLowerCase();
      const bn: string = b.name.toLowerCase();
      return an < bn ? -1 : an > bn ? 1 : 0;
    });
    return mapped;
  }

  // :61-66
  async readText(relativePath: string): Promise<string> {
    const file: string = this.requireDocument(relativePath);
    if (!this.deps.port.isFile(file)) {
      throw new Error(`Not a file: ${relativePath}`);
    }
    return this.deps.port.readText(file);
  }

  // :88-93
  async readBytes(relativePath: string): Promise<Uint8Array> {
    const file: string = this.requireDocument(relativePath);
    if (!this.deps.port.isFile(file)) {
      throw new Error(`Not a file: ${relativePath}`);
    }
    return this.deps.port.readBytes(file);
  }

  // :95-115 — 预检 size + 读后再核(双闸;'File exceeds X byte limit: Y' 逐字)
  async readBytesCapped(relativePath: string, maxBytes: number): Promise<Uint8Array> {
    if (maxBytes <= 0) throw new Error('maxBytes must be positive');
    const file: string = this.requireDocument(relativePath);
    if (!this.deps.port.isFile(file)) {
      throw new Error(`Not a file: ${relativePath}`);
    }
    if (this.deps.port.fileSize(file) > maxBytes) {
      throw new Error(`File exceeds ${maxBytes} byte limit: ${relativePath}`);
    }
    const bytes: Uint8Array = this.deps.port.readBytes(file);
    if (bytes.length > maxBytes) {
      throw new Error(`File exceeds ${maxBytes} byte limit: ${relativePath}`);
    }
    return bytes;
  }

  // :117-132
  async writeText(relativePath: string, content: string, append: boolean = false): Promise<WorkspaceEntry> {
    const normalized: string = this.requireFilePath(relativePath);
    const file: string = this.requireOrCreateFile(normalized);
    this.deps.port.writeText(file, content, append);
    const name: string = normalized.substring(normalized.lastIndexOf('/') + 1);
    return {
      path: normalized,
      name,
      directory: false,
      sizeBytes: this.deps.port.fileSize(file),
      mimeType: textMimeTypeForPath(normalized),
    };
  }

  // :134-153 — mimeType = 入参直返(SAF file.type → 镜像无 mime 源,登记);
  //   sizeBytes = 写后文件长度
  async writeBytes(
    relativePath: string, bytes: Uint8Array, mimeType: string = 'application/octet-stream',
    append: boolean = false,
  ): Promise<WorkspaceEntry> {
    const normalized: string = this.requireFilePath(relativePath);
    const file: string = this.requireOrCreateFile(normalized);
    this.deps.port.writeBytes(file, bytes, append);
    const name: string = normalized.substring(normalized.lastIndexOf('/') + 1);
    return {
      path: normalized,
      name,
      directory: false,
      sizeBytes: this.deps.port.fileSize(file),
      mimeType,
    };
  }

  // :155-170
  async editText(
    relativePath: string, oldText: string, newText: string, replaceAll: boolean,
  ): Promise<WorkspaceEditResult> {
    if (oldText.length === 0) throw new Error('old_text must not be empty');
    const current: string = await this.readText(relativePath);
    let count: number;
    if (replaceAll) {
      count = current.split(oldText).length - 1;
    } else {
      count = current.indexOf(oldText) >= 0 ? 1 : 0;
    }
    if (count <= 0) throw new Error(`Text not found in ${relativePath}`);
    // replaceFirst 字面替换(JS replace 串模式虽首命中,但 replacement 中 $ 序列
    //   有特殊语义 → 切片拼接保字面;replaceAll → split/join 全量)
    const firstIdx: number = current.indexOf(oldText);
    const updated: string = replaceAll
      ? current.split(oldText).join(newText)
      : current.slice(0, firstIdx) + newText + current.slice(firstIdx + oldText.length);
    await this.writeText(relativePath, updated);
    return {
      path: normalizeWorkspacePath(relativePath),
      replaceCount: count,
    };
  }

  // :172-201
  async move(sourcePath: string, targetPath: string): Promise<WorkspaceEntry> {
    const sourceNormalized: string = normalizeWorkspacePath(sourcePath);
    const targetNormalized: string = normalizeWorkspacePath(targetPath);
    if (sourceNormalized === targetNormalized) {
      throw new Error(`Source and target are the same path: ${sourceNormalized}`);
    }
    if (targetNormalized.startsWith(`${sourceNormalized}/`)) {
      throw new Error(
        `Moving a path into itself is not allowed: ${sourceNormalized} -> ${targetNormalized}`);
    }
    if (this.findDocument(targetNormalized) !== null) {
      throw new Error(`Target path already exists: ${targetNormalized}`);
    }
    const source: string = this.requireDocument(sourcePath);
    this.requireOrCreateParent(targetPath);
    const targetName: string = targetNormalized.substring(targetNormalized.lastIndexOf('/') + 1);
    const sourceParent: string = substringBeforeLast(sourceNormalized, '/', '.');
    const targetParentPath: string = substringBeforeLast(targetNormalized, '/', '.');
    const targetAbs: string = this.abs(targetNormalized);
    const sourceIsDir: boolean = this.deps.port.isDirectory(source);
    if (sourceParent === targetParentPath) {
      // :185-188 — 同父 renameTo
      this.deps.port.rename(source, targetAbs);
    } else {
      // :190-191 — 跨父 copyDocument + 删原
      this.copyDocument(source, targetAbs, sourceIsDir);
      if (!this.deps.port.deleteRecursively(source)) {
        throw new Error(`Copied but failed to delete original path: ${sourcePath}`);
      }
    }
    return {
      path: targetNormalized,
      name: targetName,
      directory: sourceIsDir,
      sizeBytes: sourceIsDir ? null : this.deps.port.fileSize(targetAbs),
      mimeType: sourceIsDir ? null : textMimeTypeForPath(targetName),
    };
  }

  // :203-231
  async search(query: string, relativePath: string = '.', maxResults: number = 50): Promise<WorkspaceSearchResult[]> {
    if (query.trim().length === 0) throw new Error('query is required');
    const start: string = this.requireDocument(relativePath);
    const results: WorkspaceSearchResult[] = [];
    const visit = (abs: string, path: string): void => {
      if (results.length >= maxResults) return;
      if (this.deps.port.isDirectory(abs)) {
        this.deps.port.listNames(abs).forEach((child: WorkspaceFsEntry): void => {
          visit(`${abs}/${child.name}`, joinWorkspacePath(path, child.name));
        });
        return;
      }
      let text: string | null = null;
      try {
        text = this.deps.port.readText(abs);
      } catch (_e) {
        return; // runCatching → 不可读跳过(:216-218)
      }
      const lines: string[] = text.split(/\r\n|\r|\n/);
      const lowerQuery: string = query.toLowerCase();
      for (let i: number = 0; i < lines.length; i++) {
        if (lines[i].toLowerCase().indexOf(lowerQuery) >= 0) {
          results.push({
            path,
            lineNumber: i + 1,
            preview: lines[i].slice(0, 240),
          });
          return; // 每文件首命中行(:219 firstOrNull)
        }
      }
    };
    visit(start, normalizeWorkspacePath(relativePath));
    return results;
  }

  // ===== 内部(WorkspaceManager.kt:368-445;DocumentFile → abs 路径串) =====

  private abs(normalized: string): string {
    return normalized === '.' ? this.deps.rootAbs : `${this.deps.rootAbs}/${normalized}`;
  }

  // :368-371 — requireRoot:镜像根缺省自建(ensureMirrorRoot 语义;:245-249 同)
  private requireRoot(): string {
    if (!this.deps.port.exists(this.deps.rootAbs)) {
      this.deps.port.mkdirs(this.deps.rootAbs);
    }
    return this.deps.rootAbs;
  }

  // :373-376
  private requireDocument(relativePath: string): string {
    const normalized: string = normalizeWorkspacePath(relativePath);
    const found: string | null = this.findDocument(normalized);
    if (found === null) throw new Error(`Path not found: ${normalized}`);
    return found;
  }

  // :378-386 — 逐段 findFile(根须存在)
  private findDocument(relativePath: string): string | null {
    const normalized: string = normalizeWorkspacePath(relativePath);
    const root: string = this.requireRoot();
    if (normalized === '.') return root;
    const segments: string[] = normalized.split('/')
      .filter((seg: string): boolean => seg.trim().length > 0);
    let current: string = root;
    for (const seg of segments) {
      const next: string = `${current}/${seg}`;
      if (!this.deps.port.exists(next)) return null;
      current = next;
    }
    return current;
  }

  // :388-404 — 存在即取;不存在 createFile(harmony 无重名改名问题,一次到位)
  private requireOrCreateFile(normalized: string): string {
    this.requireOrCreateParent(normalized);
    const abs: string = this.abs(normalized);
    if (this.deps.port.exists(abs)) return abs;
    this.deps.port.writeText(abs, '', false);
    return abs;
  }

  // :406-416 — 逐段 findFile ?: createDirectory
  private requireOrCreateParent(relativePath: string): string {
    const normalized: string = normalizeWorkspacePath(relativePath);
    const parentPath: string = substringBeforeLast(normalized, '/', '.');
    if (parentPath === '.' || parentPath === normalized) return this.requireRoot();
    const root: string = this.requireRoot();
    let current: string = root;
    const segments: string[] = parentPath.split('/')
      .filter((seg: string): boolean => seg.trim().length > 0);
    for (const seg of segments) {
      const next: string = `${current}/${seg}`;
      if (!this.deps.port.exists(next)) {
        this.deps.port.mkdirs(next);
      }
      current = next;
    }
    return current;
  }

  // :418-439 — 目录递归拷/文件 copyFile
  private copyDocument(sourceAbs: string, targetAbs: string, sourceIsDir: boolean): void {
    if (sourceIsDir) {
      this.deps.port.mkdirs(targetAbs);
      this.deps.port.listNames(sourceAbs).forEach((child: WorkspaceFsEntry): void => {
        this.copyDocument(`${sourceAbs}/${child.name}`, `${targetAbs}/${child.name}`,
          child.directory);
      });
      return;
    }
    this.deps.port.copyFile(sourceAbs, targetAbs);
  }

  // :441-445
  private requireFilePath(path: string): string {
    const normalized: string = normalizeWorkspacePath(path);
    if (normalized === '.') {
      throw new Error(`A file path under /workspace is required: ${path}`);
    }
    return normalized;
  }
}

// substringBeforeLast(delimiter, missingDelimiterValue)(Kotlin 逐字)
const substringBeforeLast = (s: string, delimiter: string, missing: string): string => {
  const idx: number = s.lastIndexOf(delimiter);
  return idx < 0 ? missing : s.substring(0, idx);
};

// :455-466 逐字(表外 → text/plain 仅 writeText;list 推导见头注重3)
export const textMimeTypeForPath = (path: string): string => {
  const idx: number = path.lastIndexOf('.');
  const ext: string = idx < 0 ? '' : path.substring(idx + 1).toLowerCase();
  switch (ext) {
    case 'md':
    case 'markdown':
      return 'text/markdown';
    case 'json':
      return 'application/json';
    case 'yaml':
    case 'yml':
      return 'application/yaml';
    case 'html':
    case 'htm':
      return 'text/html';
    case 'css':
      return 'text/css';
    case 'csv':
      return 'text/csv';
    case 'js':
    case 'mjs':
    case 'cjs':
      return 'text/javascript';
    case 'sh':
      return 'application/x-sh';
    default:
      return 'text/plain';
  }
};

// ===== ToolJson.kt(全文逐字;kotlix 语义 helper) =====

const isObj = (v: JsonValue | undefined): v is JsonObject =>
  typeof v === 'object' && v !== null && !Array.isArray(v);

const jsonContentOrNull = (v: JsonValue | undefined): string | null => {
  if (v === undefined || v === null) return null;
  if (typeof v === 'string') return v;
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  return null;
};

// string(name):jsonObject[name]?.jsonPrimitive?.contentOrNull
export const toolInputString = (input: JsonValue, name: string): string | null => {
  const obj: JsonObject = isObj(input) ? input : {};
  return jsonContentOrNull(obj[name]);
};

// requiredString(name)?: error("$name is required")
export const toolInputRequiredString = (input: JsonValue, name: string): string => {
  const v: string | null = toolInputString(input, name);
  if (v === null) throw new Error(`${name} is required`);
  return v;
};

// boolean(name):contentOrNull?.toBooleanStrictOrNull()
export const toolInputBoolean = (input: JsonValue, name: string): boolean | null => {
  const v: string | null = toolInputString(input, name);
  if (v === 'true') return true;
  if (v === 'false') return false;
  return null;
};

// int(name):contentOrNull?.toIntOrNull()(严格整数)
export const toolInputInt = (input: JsonValue, name: string): number | null => {
  const v: string | null = toolInputString(input, name);
  if (v === null) return null;
  if (!/^[+-]?\d+$/.test(v)) return null;
  const n: number = Number(v);
  return Number.isSafeInteger(n) ? n : null;
};

// ===== WorkspaceTools.kt:265-286(常量 + 纯函数) =====

export const FILE_READ_DEFAULT_MAX_CHARS: number = 65536;
export const FILE_READ_HARD_MAX_CHARS: number = 262144;

export const normalizeFileReadMaxChars = (requested: number | null): number => {
  if (requested === null) return FILE_READ_DEFAULT_MAX_CHARS;
  if (requested <= 0) throw new Error('max_chars must be greater than 0');
  return Math.min(requested, FILE_READ_HARD_MAX_CHARS);
};

export const buildFileReadJson = (
  path: string, content: string, requestedMaxChars: number | null,
): JsonObject => {
  const maxChars: number = normalizeFileReadMaxChars(requestedMaxChars);
  const truncated: boolean = content.length > maxChars;
  return {
    path,
    content: truncated ? content.slice(0, maxChars) : content,
    total_size_chars: content.length,
    truncated,
    max_chars: maxChars,
  };
};

// :240-246 — previewText(join('\n') + takeLast(1600);非 text → JSON.stringify 登记)
const previewText = (parts: UIMessagePart[]): string => {
  const joined: string = parts.map((part: UIMessagePart): string => {
    if (part.type === 'text') return part.text;
    return JSON.stringify(part);
  }).join('\n');
  return joined.slice(Math.max(0, joined.length - 1600));
};

// :248-262 — activityInputPreview(file_write/file_edit 摘要,else 原样)
export const workspaceActivityInputPreview = (input: JsonValue, toolName: string): string => {
  if (toolName === 'file_write') {
    const payload: JsonObject = {
      path: toolInputString(input, 'path') ?? '',
      append: toolInputBoolean(input, 'append') ?? false,
      content_chars: toolInputString(input, 'content')?.length ?? 0,
    };
    return JSON.stringify(payload);
  }
  if (toolName === 'file_edit') {
    const payload: JsonObject = {
      path: toolInputString(input, 'path') ?? '',
      old_text_chars: toolInputString(input, 'old_text')?.length ?? 0,
      new_text_chars: toolInputString(input, 'new_text')?.length ?? 0,
      replace_all: toolInputBoolean(input, 'replace_all') ?? false,
    };
    return JSON.stringify(payload);
  }
  return JSON.stringify(input);
};

// ===== createWorkspaceTools(WorkspaceTools.kt:18-192) =====

const stringProp = (description: string): JsonObject => ({ type: 'string', description });
const booleanProp = (description: string): JsonObject => ({ type: 'boolean', description });
const integerProp = (description: string): JsonObject => ({ type: 'integer', description });

const textJson = (payload: JsonObject): UIMessagePart[] =>
  [{ type: 'text', text: JSON.stringify(payload), metadata: null }];

export const createWorkspaceTools = (
  manager: PosixWorkspaceManager,
  activityStore: AgentToolActivityStore,
): AgentTool[] => {
  // :194-215 — trackWorkspaceTool(activity 三段事件包装)
  const track = async (
    toolName: string, title: string, input: JsonValue,
    block: () => Promise<UIMessagePart[]>,
  ): Promise<UIMessagePart[]> => {
    const toolCallId: string = activityStore.startTool(
      toolName, title, workspaceActivityInputPreview(input, toolName),
      'SAF workspace', '/workspace');
    try {
      const result: UIMessagePart[] = await block();
      activityStore.complete(toolCallId, previewText(result));
      return result;
    } catch (e) {
      activityStore.fail(toolCallId, e instanceof Error ? e : new Error(String(e)));
      throw e;
    }
  };

  return [
    // :27-56 — file_list
    makeAgentTool({
      name: 'file_list',
      description: 'List files and folders under the user-authorized /workspace directory.',
      parameters: (): InputSchemaObj => ({
        type: 'object',
        properties: {
          path: stringProp('Workspace-relative path. Defaults to /workspace.'),
        },
        required: null,
      }),
      execute: async (input: JsonValue): Promise<UIMessagePart[]> =>
        track('file_list', '列出 workspace', input, async (): Promise<UIMessagePart[]> => {
          const path: string = toolInputString(input, 'path') ?? '.';
          const entries: WorkspaceEntry[] = await manager.list(path);
          const entryJson: JsonObject[] = entries.map((entry: WorkspaceEntry): JsonObject => {
            const obj: JsonObject = {
              path: entry.path,
              name: entry.name,
              directory: entry.directory,
            };
            if (entry.sizeBytes !== null) obj['size_bytes'] = entry.sizeBytes;
            if (entry.mimeType !== null) obj['mime_type'] = entry.mimeType;
            return obj;
          });
          return textJson({
            workspace_configured: manager.configured(),
            path,
            entries: entryJson as unknown as JsonValue,
          });
        }),
    }),
    // :58-75 — file_read
    makeAgentTool({
      name: 'file_read',
      description: 'Read a UTF-8 text file from /workspace. Common locations: notes/, ' +
        'reports/, ppt/, scripts/, data/.',
      parameters: (): InputSchemaObj => ({
        type: 'object',
        properties: {
          path: stringProp('Workspace-relative file path to read.'),
          max_chars: integerProp(
            'Maximum characters to return. Defaults to 65536; hard limit is 262144.'),
        },
        required: ['path'],
      }),
      execute: async (input: JsonValue): Promise<UIMessagePart[]> =>
        track('file_read', '读取文件', input, async (): Promise<UIMessagePart[]> => {
          const path: string = toolInputRequiredString(input, 'path');
          const content: string = await manager.readText(path);
          return textJson(buildFileReadJson(path, content, toolInputInt(input, 'max_chars')));
        }),
    }),
    // :77-103 — file_write(needsApproval,allowsAutoApproval=false)
    makeAgentTool({
      name: 'file_write',
      description: 'Write UTF-8 text to a file in /workspace. Creates parent folders. ' +
        'Put files in the matching subdirectory:\n' +
        '- notes/ for .md, .txt, Markdown notes and documentation\n' +
        '- reports/ for analysis reports, briefings, summaries\n' +
        '- ppt/ or slides/ for presentation slides, slide specs\n' +
        '- scripts/ for code, scripts, config files (.py, .sh, .json, .kt)\n' +
        '- data/ for datasets, CSV, JSON data files\n' +
        '- officepro/ for Feishu office documents and drafts\n' +
        'Only put files at the workspace root when none of the above apply.',
      parameters: (): InputSchemaObj => ({
        type: 'object',
        properties: {
          path: stringProp('Workspace-relative file path to write.'),
          content: stringProp('UTF-8 text content to write.'),
          append: booleanProp('Append instead of replacing the file content. Defaults to false.'),
        },
        required: ['path', 'content'],
      }),
      needsApproval: true,
      allowsAutoApproval: false,
      execute: async (input: JsonValue): Promise<UIMessagePart[]> =>
        track('file_write', '写入文件', input, async (): Promise<UIMessagePart[]> => {
          const entry: WorkspaceEntry = await manager.writeText(
            toolInputRequiredString(input, 'path'),
            toolInputRequiredString(input, 'content'),
            toolInputBoolean(input, 'append') ?? false);
          return textJson({
            path: entry.path,
            size_bytes: entry.sizeBytes ?? 0,
          });
        }),
    }),
    // :105-133 — file_edit(needsApproval,allowsAutoApproval=false)
    makeAgentTool({
      name: 'file_edit',
      description: 'Edit a text file in /workspace by replacing exact old_text with new_text.',
      parameters: (): InputSchemaObj => ({
        type: 'object',
        properties: {
          path: stringProp('Workspace-relative file path to edit.'),
          old_text: stringProp('Exact text to replace.'),
          new_text: stringProp('Replacement text.'),
          replace_all: booleanProp(
            'Replace every match instead of the first match. Defaults to false.'),
        },
        required: ['path', 'old_text', 'new_text'],
      }),
      needsApproval: true,
      allowsAutoApproval: false,
      execute: async (input: JsonValue): Promise<UIMessagePart[]> =>
        track('file_edit', '编辑文件', input, async (): Promise<UIMessagePart[]> => {
          const result: WorkspaceEditResult = await manager.editText(
            toolInputRequiredString(input, 'path'),
            toolInputRequiredString(input, 'old_text'),
            toolInputRequiredString(input, 'new_text'),
            toolInputBoolean(input, 'replace_all') ?? false);
          return textJson({
            path: result.path,
            replace_count: result.replaceCount,
          });
        }),
    }),
    // :135-166 — file_search
    makeAgentTool({
      name: 'file_search',
      description: 'Search UTF-8 text files in /workspace for a query string.',
      parameters: (): InputSchemaObj => ({
        type: 'object',
        properties: {
          query: stringProp('Text query to search for.'),
          path: stringProp('Workspace-relative directory or file. Defaults to /workspace.'),
          max_results: integerProp('Maximum results to return. Defaults to 50.'),
        },
        required: ['query'],
      }),
      execute: async (input: JsonValue): Promise<UIMessagePart[]> =>
        track('file_search', '搜索 workspace', input, async (): Promise<UIMessagePart[]> => {
          const results: WorkspaceSearchResult[] = await manager.search(
            toolInputRequiredString(input, 'query'),
            toolInputString(input, 'path') ?? '.',
            toolInputInt(input, 'max_results') ?? 50);
          const resultJson: JsonObject[] = results.map((r: WorkspaceSearchResult): JsonObject => ({
            path: r.path,
            line_number: r.lineNumber,
            preview: r.preview,
          }));
          return textJson({ results: resultJson as unknown as JsonValue });
        }),
    }),
    // :168-192 — file_move(needsApproval,allowsAutoApproval=false)
    makeAgentTool({
      name: 'file_move',
      description: 'Move or rename a file/folder inside /workspace.',
      parameters: (): InputSchemaObj => ({
        type: 'object',
        properties: {
          source_path: stringProp('Existing workspace-relative path.'),
          target_path: stringProp('New workspace-relative path.'),
        },
        required: ['source_path', 'target_path'],
      }),
      needsApproval: true,
      allowsAutoApproval: false,
      execute: async (input: JsonValue): Promise<UIMessagePart[]> =>
        track('file_move', '移动文件', input, async (): Promise<UIMessagePart[]> => {
          const entry: WorkspaceEntry = await manager.move(
            toolInputRequiredString(input, 'source_path'),
            toolInputRequiredString(input, 'target_path'));
          return textJson({
            path: entry.path,
            directory: entry.directory,
          });
        }),
    }),
  ];
};
