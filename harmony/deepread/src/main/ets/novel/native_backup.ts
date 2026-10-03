// 原生备份保存完整文件树；仓库负责验证私有状态并原子安装，ZIP 与 SDK 留在平台 adapter。
import { invalidInput } from './error.ts';
import { decodeNovelWorkspaceUtf8, DEFAULT_NOVEL_WORKSPACE_EXCHANGE_LIMITS } from './workspace_exchange.ts';
import type {
  NovelWorkspaceArchiveCodec, NovelWorkspaceArchiveEntry, NovelWorkspaceArchiveFile, NovelWorkspaceExchangeLimits,
} from './workspace_exchange.ts';
import type { WorkspaceCas } from './workspace_history.ts';

export const NOVEL_NATIVE_BACKUP_MANIFEST_PATH: string = 'native-backup.json';
export const NOVEL_NATIVE_BACKUP_FORMAT: string = 'amber.novel.native-backup';
export const NOVEL_NATIVE_BACKUP_VERSION: number = 1;

export interface NovelNativeBackupMetadata {
  projectId: string;
  title: string;
  schemaVersion: number;
  activeBranch: string;
  state: WorkspaceCas;
  createdAt: number;
}

export interface NovelNativeBackupEntry {
  path: string;
  byteLength: number;
  checksum: string;
}

export interface NovelNativeBackupManifest extends NovelNativeBackupMetadata {
  format: string;
  version: number;
  // 用于检测传输损坏，不是签名或真实性证明。
  checksumAlgorithm: string;
  entries: NovelNativeBackupEntry[];
}

export interface NovelNativeBackupImport {
  projectId: string;
  manifest: NovelNativeBackupManifest;
  files: NovelWorkspaceArchiveFile[];
}

const safeInteger = (value: number): boolean => Number.isSafeInteger(value) && value >= 0;
const requireText = (value: string, field: string): void => {
  if (typeof value !== 'string' || value.trim().length === 0) throw invalidInput(`原生备份 ${field} 无效`);
};

// 私有 .amber 与未知文件都保留；仍拒绝绝对路径、路径穿越、Windows 路径和 NUL。
const checkedPath = (path: string): string => {
  requireText(path, '文件路径');
  if (path !== path.trim() || path.startsWith('/') || path.indexOf('\\') >= 0) {
    throw invalidInput(`原生备份路径无效: ${path}`);
  }
  const segments: string[] = path.split('/');
  for (let i: number = 0; i < segments.length; i++) {
    const segment: string = segments[i];
    if (segment.length === 0 || segment === '.' || segment === '..' ||
      segment.indexOf(':') >= 0 || segment.indexOf('\0') >= 0) {
      throw invalidInput(`原生备份路径无效: ${path}`);
    }
  }
  return path;
};

const checkedId = (id: string, field: string): void => {
  checkedPath(id);
  if (id.indexOf('/') >= 0 || id.startsWith('.')) throw invalidInput(`原生备份 ${field} 无效`);
};

const checkMetadata = (metadata: NovelNativeBackupMetadata): void => {
  checkedId(metadata.projectId, 'projectId');
  requireText(metadata.title, 'title');
  checkedId(metadata.activeBranch, 'activeBranch');
  if (!safeInteger(metadata.schemaVersion) || metadata.schemaVersion === 0 || !safeInteger(metadata.createdAt)) {
    throw invalidInput('原生备份 schemaVersion 或 createdAt 无效');
  }
  if (metadata.state === null || typeof metadata.state !== 'object') throw invalidInput('原生备份 state 无效');
  if (metadata.state.branchId !== metadata.activeBranch) throw invalidInput('原生备份活动分支与 state 不一致');
  requireText(metadata.state.head, 'state.head');
  requireText(metadata.state.treeDigest, 'state.treeDigest');
};

const checksum = (bytes: Uint8Array): string => {
  let hash: number = 0x811c9dc5;
  for (let i: number = 0; i < bytes.length; i++) hash = Math.imul(hash ^ bytes[i], 0x01000193) >>> 0;
  return hash.toString(16).padStart(8, '0');
};

const encodeManifest = (manifest: NovelNativeBackupManifest): Uint8Array => {
  // JSON 的非 ASCII 字符用合法 Unicode escape，避免增加第二套平台 UTF-8 adapter。
  const json: string = JSON.stringify(manifest).replace(/[\u0080-\uffff]/g,
    (character: string): string => `\\u${character.charCodeAt(0).toString(16).padStart(4, '0')}`);
  const bytes: Uint8Array = new Uint8Array(json.length);
  for (let i: number = 0; i < json.length; i++) bytes[i] = json.charCodeAt(i);
  return bytes;
};

const checkedManifest = (bytes: Uint8Array): NovelNativeBackupManifest => {
  let manifest: NovelNativeBackupManifest;
  try {
    manifest = JSON.parse(decodeNovelWorkspaceUtf8(bytes)) as NovelNativeBackupManifest;
  } catch (_error) {
    throw invalidInput('原生备份 manifest JSON 无效');
  }
  if (manifest === null || typeof manifest !== 'object' || manifest.format !== NOVEL_NATIVE_BACKUP_FORMAT ||
    manifest.version !== NOVEL_NATIVE_BACKUP_VERSION) throw invalidInput('原生备份版本不受支持');
  if (manifest.checksumAlgorithm !== 'fnv1a32') throw invalidInput('原生备份校验算法不受支持');
  checkMetadata(manifest);
  if (!Array.isArray(manifest.entries) || manifest.entries.length === 0) throw invalidInput('原生备份文件清单无效');
  const paths: Set<string> = new Set<string>();
  for (let i: number = 0; i < manifest.entries.length; i++) {
    const entry: NovelNativeBackupEntry = manifest.entries[i];
    if (entry === null || typeof entry !== 'object') throw invalidInput('原生备份文件清单无效');
    checkedPath(entry.path);
    if (entry.path === NOVEL_NATIVE_BACKUP_MANIFEST_PATH || paths.has(entry.path)) {
      throw invalidInput(`原生备份清单包含重复或保留路径: ${entry.path}`);
    }
    if (!safeInteger(entry.byteLength) || typeof entry.checksum !== 'string' || !/^[0-9a-f]{8}$/.test(entry.checksum)) {
      throw invalidInput(`原生备份清单大小或校验值无效: ${entry.path}`);
    }
    paths.add(entry.path);
  }
  return manifest;
};

const checkedEntries = (entries: NovelWorkspaceArchiveEntry[],
  limits: NovelWorkspaceExchangeLimits): NovelWorkspaceArchiveEntry[] => {
  const limitValues: number[] = [limits.maxEntries, limits.maxCompressedBytes, limits.maxExpandedBytes,
    limits.maxEntryCompressedBytes, limits.maxEntryExpandedBytes];
  for (let i: number = 0; i < limitValues.length; i++) {
    if (!safeInteger(limitValues[i]) || limitValues[i] === 0) throw invalidInput('原生备份大小限制无效');
  }
  if (entries.length === 0 || entries.length > limits.maxEntries) throw invalidInput('原生备份文件数量超过限制');
  const kinds: Map<string, boolean> = new Map<string, boolean>();
  const normalized: NovelWorkspaceArchiveEntry[] = [];
  let compressedTotal: number = 0;
  let expandedTotal: number = 0;
  for (let i: number = 0; i < entries.length; i++) {
    const entry: NovelWorkspaceArchiveEntry = entries[i];
    const path: string = checkedPath(entry.isDirectory && entry.path.endsWith('/')
      ? entry.path.slice(0, -1) : entry.path);
    if (kinds.has(path)) throw invalidInput(`原生备份包含重复路径: ${path}`);
    if (!safeInteger(entry.compressedSize) || !safeInteger(entry.uncompressedSize) ||
      entry.compressedSize > limits.maxEntryCompressedBytes || entry.uncompressedSize > limits.maxEntryExpandedBytes ||
      entry.compressedSize > limits.maxCompressedBytes - compressedTotal ||
      entry.uncompressedSize > limits.maxExpandedBytes - expandedTotal) throw invalidInput('原生备份文件大小超过限制');
    if (entry.isDirectory && entry.uncompressedSize !== 0) throw invalidInput('原生备份目录大小无效');
    compressedTotal += entry.compressedSize;
    expandedTotal += entry.uncompressedSize;
    kinds.set(path, entry.isDirectory);
    normalized.push({ path, isDirectory: entry.isDirectory,
      compressedSize: entry.compressedSize, uncompressedSize: entry.uncompressedSize });
  }
  for (let i: number = 0; i < normalized.length; i++) {
    const segments: string[] = normalized[i].path.split('/');
    let prefix: string = '';
    for (let j: number = 0; j < segments.length - 1; j++) {
      prefix = prefix.length === 0 ? segments[j] : `${prefix}/${segments[j]}`;
      if (kinds.get(prefix) === false) throw invalidInput(`原生备份文件与目录冲突: ${prefix}`);
    }
  }
  return normalized;
};

// Rebuild the archive manifest after a deliberate project copy. Repository still
// validates the private history/state graph before this import can be installed.
export const makeNovelNativeBackupImport = (metadata: NovelNativeBackupMetadata,
  sourceFiles: NovelWorkspaceArchiveFile[]): NovelNativeBackupImport => {
  checkMetadata(metadata);
  const files: NovelWorkspaceArchiveFile[] = sourceFiles.map(file => ({ path: file.path, bytes: new Uint8Array(file.bytes) }));
  const manifest: NovelNativeBackupManifest = {
    ...metadata, state: { ...metadata.state }, format: NOVEL_NATIVE_BACKUP_FORMAT,
    version: NOVEL_NATIVE_BACKUP_VERSION, checksumAlgorithm: 'fnv1a32',
    entries: files.map(file => ({ path: file.path, byteLength: file.bytes.length, checksum: checksum(file.bytes) })),
  };
  checkedManifest(encodeManifest(manifest));
  return { projectId: metadata.projectId, manifest, files };
};

export const exportNovelNativeBackup = async (codec: NovelWorkspaceArchiveCodec,
  metadata: NovelNativeBackupMetadata, sourceFiles: NovelWorkspaceArchiveFile[],
  limits: NovelWorkspaceExchangeLimits = DEFAULT_NOVEL_WORKSPACE_EXCHANGE_LIMITS): Promise<Uint8Array> => {
  checkMetadata(metadata);
  if (sourceFiles.length === 0) throw invalidInput('原生备份文件树为空');
  const files: NovelWorkspaceArchiveFile[] = [];
  const entries: NovelNativeBackupEntry[] = [];
  for (let i: number = 0; i < sourceFiles.length; i++) {
    const source: NovelWorkspaceArchiveFile = sourceFiles[i];
    if (source.path === NOVEL_NATIVE_BACKUP_MANIFEST_PATH) throw invalidInput('项目文件与原生备份 manifest 路径冲突');
    const bytes: Uint8Array = new Uint8Array(source.bytes);
    files.push({ path: source.path, bytes });
    entries.push({ path: source.path, byteLength: bytes.length, checksum: checksum(bytes) });
  }
  const manifest: NovelNativeBackupManifest = {
    format: NOVEL_NATIVE_BACKUP_FORMAT, version: NOVEL_NATIVE_BACKUP_VERSION,
    checksumAlgorithm: 'fnv1a32', entries, projectId: metadata.projectId, title: metadata.title,
    schemaVersion: metadata.schemaVersion, activeBranch: metadata.activeBranch,
    state: { branchId: metadata.state.branchId, head: metadata.state.head, treeDigest: metadata.state.treeDigest },
    createdAt: metadata.createdAt,
  };
  files.push({ path: NOVEL_NATIVE_BACKUP_MANIFEST_PATH, bytes: encodeManifest(manifest) });
  checkedEntries(files.map((file: NovelWorkspaceArchiveFile): NovelWorkspaceArchiveEntry => ({
    path: file.path, compressedSize: 0, uncompressedSize: file.bytes.length, isDirectory: false,
  })), limits);
  const archive: Uint8Array = await codec.create(files);
  if (archive.length > limits.maxCompressedBytes) throw invalidInput('原生备份压缩大小超过限制');
  // 压缩前无法预测每项压缩大小；用生成包的真实 ZIP metadata 完成同一套限制检查。
  checkedEntries(await codec.list(archive), limits);
  return archive;
};

export const importNovelNativeBackup = async (codec: NovelWorkspaceArchiveCodec, archive: Uint8Array,
  limits: NovelWorkspaceExchangeLimits = DEFAULT_NOVEL_WORKSPACE_EXCHANGE_LIMITS): Promise<NovelNativeBackupImport> => {
  if (archive.length > limits.maxCompressedBytes) throw invalidInput('原生备份压缩大小超过限制');
  const entries: NovelWorkspaceArchiveEntry[] = checkedEntries(await codec.list(archive), limits);
  const manifestEntry: NovelWorkspaceArchiveEntry | undefined = entries.find(
    (entry: NovelWorkspaceArchiveEntry): boolean => entry.path === NOVEL_NATIVE_BACKUP_MANIFEST_PATH && !entry.isDirectory);
  if (manifestEntry === undefined) throw invalidInput('原生备份缺少 native-backup.json');
  const manifestBytes: Uint8Array = await codec.extract(archive, manifestEntry.path);
  if (manifestBytes.length !== manifestEntry.uncompressedSize) throw invalidInput('原生备份 manifest 大小不匹配');
  const manifest: NovelNativeBackupManifest = checkedManifest(manifestBytes);
  const fileEntries: NovelWorkspaceArchiveEntry[] = entries.filter(
    (entry: NovelWorkspaceArchiveEntry): boolean => !entry.isDirectory && entry.path !== NOVEL_NATIVE_BACKUP_MANIFEST_PATH);
  if (fileEntries.length !== manifest.entries.length) throw invalidInput('原生备份文件清单与 ZIP 不一致');
  const declared: Map<string, NovelNativeBackupEntry> = new Map<string, NovelNativeBackupEntry>();
  for (let i: number = 0; i < manifest.entries.length; i++) declared.set(manifest.entries[i].path, manifest.entries[i]);
  const files: NovelWorkspaceArchiveFile[] = [];
  for (let i: number = 0; i < fileEntries.length; i++) {
    const entry: NovelWorkspaceArchiveEntry = fileEntries[i];
    const expected: NovelNativeBackupEntry | undefined = declared.get(entry.path);
    if (expected === undefined || expected.byteLength !== entry.uncompressedSize) {
      throw invalidInput(`原生备份文件清单与 ZIP 不一致: ${entry.path}`);
    }
    const bytes: Uint8Array = await codec.extract(archive, entry.path);
    if (bytes.length !== expected.byteLength || checksum(bytes) !== expected.checksum) {
      throw invalidInput(`原生备份文件校验失败: ${entry.path}`);
    }
    files.push({ path: entry.path, bytes: new Uint8Array(bytes) });
  }
  return { projectId: manifest.projectId, manifest, files };
};
