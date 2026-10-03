// 小说工作区 ZIP 交换的纯域校验层。
// 解压、建包与 staging/rename 由调用方通过 port 提供；本模块只返回完整验证过的文件集合。

import { parseNovelWorkspaceManifest, validateNovelWorkspacePath } from './workspace_contract.ts';
import type { NovelWorkspaceManifest } from './workspace_contract.ts';
import { invalidInput } from './error.ts';

export const NOVEL_WORKSPACE_MANIFEST_PATH: string = 'manifest.yaml';

export interface NovelWorkspaceArchiveEntry {
  path: string;
  compressedSize: number;
  uncompressedSize: number;
  isDirectory: boolean;
}

export interface NovelWorkspaceArchiveFile {
  path: string;
  bytes: Uint8Array;
}

// ZIP 实现是平台 adapter 的职责，域层不解析 central directory 或实现 deflate。
export interface NovelWorkspaceArchiveCodec {
  list(archive: Uint8Array): Promise<NovelWorkspaceArchiveEntry[]>;
  extract(archive: Uint8Array, path: string): Promise<Uint8Array>;
  create(files: NovelWorkspaceArchiveFile[]): Promise<Uint8Array>;
}

export interface NovelWorkspaceExchangeLimits {
  maxEntries: number;
  maxCompressedBytes: number;
  maxExpandedBytes: number;
  maxEntryCompressedBytes: number;
  maxEntryExpandedBytes: number;
}

export const DEFAULT_NOVEL_WORKSPACE_EXCHANGE_LIMITS: NovelWorkspaceExchangeLimits = {
  maxEntries: 1024,
  maxCompressedBytes: 16 * 1024 * 1024,
  maxExpandedBytes: 64 * 1024 * 1024,
  maxEntryCompressedBytes: 8 * 1024 * 1024,
  maxEntryExpandedBytes: 16 * 1024 * 1024,
};

export interface NovelWorkspaceValidatedImport {
  manifest: NovelWorkspaceManifest;
  files: NovelWorkspaceArchiveFile[];
}

type ArchivePathKind = 'file' | 'directory';

const isHostPrivatePath = (path: string): boolean =>
  path === '.amber' || path.startsWith('.amber/');

const normalizeArchivePath = (path: string, isDirectory: boolean): string => {
  if (!isDirectory && path.endsWith('/')) throw invalidInput(`工作区路径无效: ${path}`);
  const candidate: string = isDirectory && path.endsWith('/') ? path.slice(0, -1) : path;
  if (isHostPrivatePath(candidate)) throw invalidInput('导入包不能包含宿主私有 .amber');
  return validateNovelWorkspacePath(candidate);
};

const isSafeSize = (size: number): boolean => Number.isSafeInteger(size) && size >= 0;

const checkedAdd = (total: number, value: number, limit: number, label: string): number => {
  if (value > limit - total) throw invalidInput(`ZIP ${label}超过限制`);
  return total + value;
};

const validateEntrySizes = (entry: NovelWorkspaceArchiveEntry,
  compressedTotal: number, expandedTotal: number, limits: NovelWorkspaceExchangeLimits): [number, number] => {
  if (!isSafeSize(entry.compressedSize) || !isSafeSize(entry.uncompressedSize)) {
    throw invalidInput('ZIP entry 大小无效');
  }
  if (entry.compressedSize > limits.maxEntryCompressedBytes) {
    throw invalidInput('ZIP 单项压缩大小超过限制');
  }
  if (entry.uncompressedSize > limits.maxEntryExpandedBytes) {
    throw invalidInput('ZIP 单项展开大小超过限制');
  }
  const nextCompressed: number = checkedAdd(compressedTotal, entry.compressedSize,
    limits.maxCompressedBytes, '累计压缩大小');
  const nextExpanded: number = checkedAdd(expandedTotal, entry.uncompressedSize,
    limits.maxExpandedBytes, '累计展开大小');
  return [nextCompressed, nextExpanded];
};

const validateLimitValues = (limits: NovelWorkspaceExchangeLimits): void => {
  const values: number[] = [limits.maxEntries, limits.maxCompressedBytes, limits.maxExpandedBytes,
    limits.maxEntryCompressedBytes, limits.maxEntryExpandedBytes];
  for (let i: number = 0; i < values.length; i++) {
    if (!isSafeSize(values[i]) || values[i] === 0) throw invalidInput('ZIP 大小限制无效');
  }
};

const validateMetadata = (entries: NovelWorkspaceArchiveEntry[],
  limits: NovelWorkspaceExchangeLimits): NovelWorkspaceArchiveEntry[] => {
  validateLimitValues(limits);
  if (entries.length === 0 || entries.length > limits.maxEntries) {
    throw invalidInput('ZIP entry 数量无效');
  }

  let compressedTotal: number = 0;
  let expandedTotal: number = 0;
  const kinds: Map<string, ArchivePathKind> = new Map<string, ArchivePathKind>();
  const normalized: NovelWorkspaceArchiveEntry[] = [];
  for (let i: number = 0; i < entries.length; i++) {
    const source: NovelWorkspaceArchiveEntry = entries[i];
    const path: string = normalizeArchivePath(source.path, source.isDirectory);
    if (kinds.has(path)) throw invalidInput(`ZIP 包含重复路径: ${path}`);
    const totals: [number, number] = validateEntrySizes(source, compressedTotal, expandedTotal, limits);
    compressedTotal = totals[0];
    expandedTotal = totals[1];
    const kind: ArchivePathKind = source.isDirectory ? 'directory' : 'file';
    kinds.set(path, kind);
    normalized.push({
      path,
      compressedSize: source.compressedSize,
      uncompressedSize: source.uncompressedSize,
      isDirectory: source.isDirectory,
    });
  }

  for (let i: number = 0; i < normalized.length; i++) {
    const current: NovelWorkspaceArchiveEntry = normalized[i];
    if (current.isDirectory) continue;
    const segments: string[] = current.path.split('/');
    let prefix: string = '';
    for (let j: number = 0; j < segments.length - 1; j++) {
      prefix = prefix.length === 0 ? segments[j] : `${prefix}/${segments[j]}`;
      if (kinds.get(prefix) === 'file') {
        throw invalidInput(`ZIP 文件与目录冲突: ${prefix}`);
      }
    }
  }
  return normalized;
};

export const decodeNovelWorkspaceUtf8 = (bytes: Uint8Array): string => {
  let result: string = '';
  for (let i: number = 0; i < bytes.length;) {
    const first: number = bytes[i];
    if (first < 0x80) {
      result += String.fromCharCode(first);
      i++;
      continue;
    }
    let needed: number = 0;
    let codePoint: number = 0;
    if (first >= 0xC2 && first <= 0xDF) {
      needed = 1;
      codePoint = first & 0x1F;
    } else if (first >= 0xE0 && first <= 0xEF) {
      needed = 2;
      codePoint = first & 0x0F;
    } else if (first >= 0xF0 && first <= 0xF4) {
      needed = 3;
      codePoint = first & 0x07;
    } else {
      throw invalidInput('manifest 不是 UTF-8 文本');
    }
    if (i + needed >= bytes.length) throw invalidInput('manifest 不是 UTF-8 文本');
    for (let j: number = 1; j <= needed; j++) {
      const next: number = bytes[i + j];
      if ((next & 0xC0) !== 0x80) throw invalidInput('manifest 不是 UTF-8 文本');
      codePoint = (codePoint << 6) | (next & 0x3F);
    }
    const minimum: number = needed === 1 ? 0x80 : needed === 2 ? 0x800 : 0x10000;
    if (codePoint < minimum || codePoint > 0x10FFFF || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
      throw invalidInput('manifest 不是 UTF-8 文本');
    }
    if (codePoint <= 0xFFFF) {
      result += String.fromCharCode(codePoint);
    } else {
      const scalar: number = codePoint - 0x10000;
      result += String.fromCharCode(0xD800 + (scalar >> 10), 0xDC00 + (scalar & 0x3FF));
    }
    i += needed + 1;
  }
  return result;
};

const parseManifestFromFiles = (files: NovelWorkspaceArchiveFile[]): NovelWorkspaceManifest => {
  let manifestBytes: Uint8Array | undefined;
  let projectDocument: string | null = null;
  for (let i: number = 0; i < files.length; i++) {
    if (files[i].path === NOVEL_WORKSPACE_MANIFEST_PATH) manifestBytes = files[i].bytes;
    if (files[i].path === 'project.md') projectDocument = decodeNovelWorkspaceUtf8(files[i].bytes);
  }
  if (manifestBytes === undefined) throw invalidInput('ZIP 缺少 manifest.yaml');
  if (projectDocument === null) throw invalidInput('ZIP 缺少 project.md');
  return parseNovelWorkspaceManifest(decodeNovelWorkspaceUtf8(manifestBytes), projectDocument);
};

const validateExportFiles = (files: NovelWorkspaceArchiveFile[],
  limits: NovelWorkspaceExchangeLimits): NovelWorkspaceManifest => {
  const entries: NovelWorkspaceArchiveEntry[] = [];
  for (let i: number = 0; i < files.length; i++) {
    entries.push({
      path: files[i].path,
      // export 尚未压缩，不能把原始文件大小伪装成 ZIP compressedSize。
      compressedSize: 0,
      uncompressedSize: files[i].bytes.length,
      isDirectory: false,
    });
  }
  validateMetadata(entries, limits);
  return parseManifestFromFiles(files);
};

export const importNovelWorkspaceArchive = async (codec: NovelWorkspaceArchiveCodec, archive: Uint8Array,
  limits: NovelWorkspaceExchangeLimits = DEFAULT_NOVEL_WORKSPACE_EXCHANGE_LIMITS): Promise<NovelWorkspaceValidatedImport> => {
  const entries: NovelWorkspaceArchiveEntry[] = validateMetadata(await codec.list(archive), limits);
  const files: NovelWorkspaceArchiveFile[] = [];
  for (let i: number = 0; i < entries.length; i++) {
    const entry: NovelWorkspaceArchiveEntry = entries[i];
    if (entry.isDirectory) continue;
    const bytes: Uint8Array = await codec.extract(archive, entry.path);
    if (bytes.length !== entry.uncompressedSize) {
      throw invalidInput(`ZIP 解压大小不匹配: ${entry.path}`);
    }
    files.push({ path: entry.path, bytes: new Uint8Array(bytes) });
  }
  const manifest: NovelWorkspaceManifest = parseManifestFromFiles(files);
  return { manifest, files };
};

export const exportNovelWorkspaceArchive = async (codec: NovelWorkspaceArchiveCodec,
  sourceFiles: NovelWorkspaceArchiveFile[],
  limits: NovelWorkspaceExchangeLimits = DEFAULT_NOVEL_WORKSPACE_EXCHANGE_LIMITS): Promise<Uint8Array> => {
  const files: NovelWorkspaceArchiveFile[] = [];
  for (let i: number = 0; i < sourceFiles.length; i++) {
    const source: NovelWorkspaceArchiveFile = sourceFiles[i];
    if (isHostPrivatePath(source.path)) continue;
    files.push({ path: source.path, bytes: new Uint8Array(source.bytes) });
  }
  validateExportFiles(files, limits);
  return codec.create(files);
};
