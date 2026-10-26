import type { JsonObject, JsonValue } from './json.ts';
import type { UIMessagePart } from './message.ts';
import type { AgentTool, InputSchemaObj } from './tool.ts';
import { makeAgentTool } from './tool.ts';
import { MAX_ARCHIVE_ENTRY_BYTES, mimeFromName, safeBaseName } from './workspace_artifact_files.ts';
import type { ArtifactToolTracker } from './workspace_artifact_tool.ts';
import {
  arrayProp, booleanProp, inputLimit, integerProp, jsonArrayStrings, stringProp, textJson,
} from './workspace_artifact_tool.ts';
import {
  PosixWorkspaceManager, normalizeWorkspacePath, toolInputBoolean, toolInputRequiredString,
  toolInputString,
} from './workspace.ts';
import type { InflateRawPort, ZipEntryRecord } from './zip_archive.ts';
import { extractZipEntryData, readZipEntries, utf8Decode } from './zip_archive.ts';
import type { DeflateRawPort, ZipWriteEntry } from './zip_writer.ts';
import { buildZipBytes, dosTimeDate } from './zip_writer.ts';

export const MAX_ARCHIVE_BYTES: number = 256 * 1024 * 1024;

// 归档总输出上限:单条目 64MB 上限挡不住「大量 64MB 条目」的解压炸弹;
// tar.gz 的 ISIZE 是 gzip trailer 声明(mod 2^32),同样不可信
const MAX_ARCHIVE_OUTPUT_BYTES: number = 256 * 1024 * 1024;


// ===== gzip/tar 底层 =====

// GZIPInputStream 头剥离(flag 感知:FEXTRA/FNAME/FCOMMENT/FHCRC)+ trailer
//   ISIZE → raw deflate 段 + 声明解压长度
export const gunzipSplit = (bytes: Uint8Array): { payload: Uint8Array; isize: number } => {
  if (bytes.length < 18 || bytes[0] !== 0x1f || bytes[1] !== 0x8b || bytes[2] !== 8) {
    throw new Error('Not in GZIP format');
  }
  const flags: number = bytes[3];
  let p: number = 10;
  if ((flags & 4) !== 0) { // FEXTRA
    const xlen: number = bytes[p] | (bytes[p + 1] << 8);
    p += 2 + xlen;
  }
  if ((flags & 8) !== 0) { // FNAME
    while (p < bytes.length && bytes[p] !== 0) p++;
    p++;
  }
  if ((flags & 16) !== 0) { // FCOMMENT
    while (p < bytes.length && bytes[p] !== 0) p++;
    p++;
  }
  if ((flags & 2) !== 0) p += 2; // FHCRC
  const u32le = (off: number): number =>
    (bytes[off] | (bytes[off + 1] << 8) | (bytes[off + 2] << 16) |
      (bytes[off + 3] << 24)) >>> 0;
  const isize: number = u32le(bytes.length - 4);
  return { payload: bytes.subarray(p, bytes.length - 8), isize };
};

interface TarHeader {
  name: string;
  size: number;
  directory: boolean;
}

// :672-683 — 512 头块;全零 → null(结束)
const readTarHeader = (bytes: Uint8Array, off: number): TarHeader | null => {
  if (off + 512 > bytes.length) return null;
  let allZero: boolean = true;
  for (let i: number = off; i < off + 512; i++) {
    if (bytes[i] !== 0) { allZero = false; break; }
  }
  if (allZero) return null;
  const field = (start: number, len: number): string => {
    let end: number = start;
    while (end < start + len && bytes[off + end] !== 0) end++;
    return utf8Decode(bytes.subarray(off + start, off + end)).trim();
  };
  const name: string = field(0, 100);
  const sizeText: string = field(124, 12);
  const size: number = sizeText.length > 0 ? parseInt(sizeText, 8) : 0;
  const type: number = bytes[off + 156];
  return {
    name,
    size: Number.isNaN(size) ? 0 : size,
    directory: type === 0x35 /* '5' */ || name.endsWith('/'),
  };
};

// :692
const alignTarSize = (size: number): number => Math.floor((size + 511) / 512) * 512;

export type ArchiveType = 'zip' | 'tar' | 'targz';

// :702-710
export const archiveTypeOf = (path: string): ArchiveType => {
  const lower: string = path.toLowerCase();
  if (lower.endsWith('.zip')) return 'zip';
  if (lower.endsWith('.tar.gz') || lower.endsWith('.tgz')) return 'targz';
  if (lower.endsWith('.tar')) return 'tar';
  throw new Error(`Unsupported archive type: ${path}`);
};

// :694-700 — Zip Slip/逃逸阻断
export const safeArchiveTarget = (destination: string, entryName: string): string => {
  const cleanName: string = entryName.trim().replace(/\\/g, '/');
  if (cleanName.trim().length === 0 || cleanName.startsWith('/') || cleanName.indexOf('../') >= 0) {
    throw new Error(`Unsafe archive entry: ${entryName}`);
  }
  return normalizeWorkspacePath(`${normalizeWorkspacePath(destination)}/${cleanName}`);
};

// :663-667
const archiveEntryJson = (name: string, directory: boolean, size: number): JsonObject => {
  const obj: JsonObject = { path: name, directory };
  if (size >= 0) obj['size_bytes'] = size;
  return obj;
};

export interface WorkspaceArchiveToolsDeps {
  workspaceManager: PosixWorkspaceManager;
  inflateRaw: InflateRawPort;
  deflateRaw: DeflateRawPort;
}

export const createWorkspaceArchiveTools = (deps: WorkspaceArchiveToolsDeps, track: ArtifactToolTracker): AgentTool[] => {
  const mgr: PosixWorkspaceManager = deps.workspaceManager;
  // :474-494
  const listArchiveEntries = async (
    path: string, bytes: Uint8Array, limit: number,
  ): Promise<JsonValue[]> => {
    const out: JsonValue[] = [];
    const type: ArchiveType = archiveTypeOf(path);
    if (type === 'zip') {
      const entries: ZipEntryRecord[] = readZipEntries(bytes);
      for (const e of entries) {
        if (out.length >= limit) break;
        out.push(archiveEntryJson(e.name, e.name.endsWith('/'), e.uncompressedSize));
      }
      return out;
    }
    let tarBytes: Uint8Array = bytes;
    if (type === 'targz') {
      const gz: { payload: Uint8Array; isize: number } = gunzipSplit(bytes);
      // ISIZE 是 trailer 声明(mod 2^32),先按总输出上限拒绝再分配
      if (gz.isize > MAX_ARCHIVE_OUTPUT_BYTES) {
        throw new Error('Archive total uncompressed size exceeds the allowed limit');
      }
      tarBytes = await deps.inflateRaw(gz.payload, gz.isize);
      if (tarBytes.length > MAX_ARCHIVE_OUTPUT_BYTES) {
        throw new Error('Archive total uncompressed size exceeds the allowed limit');
      }
      if (tarBytes.length !== gz.isize) {
        throw new Error('Corrupted gzip archive: inflated size does not match trailer');
      }
    }
    let off: number = 0;
    while (out.length < limit) {
      const header: TarHeader | null = readTarHeader(tarBytes, off);
      if (header === null) break;
      out.push(archiveEntryJson(header.name, header.directory, header.size));
      off += 512 + alignTarSize(header.size);
    }
    return out;
  };

  // :496-547
  const extractArchive = async (
    path: string, bytes: Uint8Array, destination: string, overwrite: boolean,
  ): Promise<{ files: number; directories: number; bytes: number }> => {
    const stats = { files: 0, directories: 0, bytes: 0 };
    const writeOne = async (target: string, data: Uint8Array): Promise<void> => {
      if (stats.bytes + data.length > MAX_ARCHIVE_OUTPUT_BYTES) {
        throw new Error('Archive total uncompressed size exceeds the allowed limit');
      }
      if (!overwrite) {
        let exists: boolean = false;
        try {
          await mgr.readBytes(target);
          exists = true;
        } catch (_e) {
          exists = false;
        }
        if (exists) throw new Error(`Target already exists: ${target}`);
      }
      await mgr.writeBytes(target, data, mimeFromName(target));
      stats.files++;
      stats.bytes += data.length;
    };
    const type: ArchiveType = archiveTypeOf(path);
    if (type === 'zip') {
      const entries: ZipEntryRecord[] = readZipEntries(bytes);
      // 预累加声明解压总量:条目级上限挡不住多大量级,先拒绝再动手
      let declaredTotal: number = 0;
      for (const e of entries) {
        declaredTotal += e.uncompressedSize;
        if (declaredTotal > MAX_ARCHIVE_OUTPUT_BYTES) {
          throw new Error('Archive total uncompressed size exceeds the allowed limit');
        }
      }
      for (const e of entries) {
        const target: string = safeArchiveTarget(destination, e.name);
        if (e.name.endsWith('/')) {
          stats.directories++;
          continue;
        }
        const raw: Uint8Array = extractZipEntryData(bytes, e);
        let data: Uint8Array;
        if (e.method === 0) {
          data = raw;
        } else if (e.method === 8) {
          if (e.uncompressedSize > MAX_ARCHIVE_ENTRY_BYTES) {
            throw new Error(
              `Archive entry ${e.name} exceeds the ${MAX_ARCHIVE_ENTRY_BYTES} byte limit`);
          }
          data = await deps.inflateRaw(raw, e.uncompressedSize);
          // 实际输出与声明不符 = 损坏/截断,不得把不完整文件当成功写入
          if (data.length !== e.uncompressedSize) {
            throw new Error(`Archive entry ${e.name} inflate size mismatch`);
          }
        } else {
          throw new Error(`Unsupported zip compression method ${e.method}: ${e.name}`);
        }
        if (data.length > MAX_ARCHIVE_ENTRY_BYTES) {
          throw new Error(
            `Archive entry ${e.name} exceeds the ${MAX_ARCHIVE_ENTRY_BYTES} byte limit`);
        }
        await writeOne(target, data);
      }
      return stats;
    }
    let tarBytes: Uint8Array = bytes;
    if (type === 'targz') {
      const gz: { payload: Uint8Array; isize: number } = gunzipSplit(bytes);
      // ISIZE 是 trailer 声明(mod 2^32),先按总输出上限拒绝再分配
      if (gz.isize > MAX_ARCHIVE_OUTPUT_BYTES) {
        throw new Error('Archive total uncompressed size exceeds the allowed limit');
      }
      tarBytes = await deps.inflateRaw(gz.payload, gz.isize);
      if (tarBytes.length > MAX_ARCHIVE_OUTPUT_BYTES) {
        throw new Error('Archive total uncompressed size exceeds the allowed limit');
      }
      if (tarBytes.length !== gz.isize) {
        throw new Error('Corrupted gzip archive: inflated size does not match trailer');
      }
    }
    let off: number = 0;
    while (true) {
      const header: TarHeader | null = readTarHeader(tarBytes, off);
      if (header === null) break;
      const target: string = safeArchiveTarget(destination, header.name);
      if (header.directory) {
        stats.directories++;
      } else {
        if (header.size > MAX_ARCHIVE_ENTRY_BYTES) {
          throw new Error(
            `Archive entry is too large: ${header.name} (${header.size} bytes)`);
        }
        const data: Uint8Array = tarBytes.subarray(off + 512, off + 512 + header.size);
        if (data.length < header.size) throw new Error('Unexpected end of stream');
        await writeOne(target, data);
      }
      off += 512 + alignTarSize(header.size);
    }
    return stats;
  };

  // :549-572 — list 成功 → 目录(直子非目录项,不递归);失败 → 单文件
  const createZip = async (sources: string[]): Promise<Uint8Array> => {
    const entries: ZipWriteEntry[] = [];
    for (const source of sources) {
      let listed: Awaited<ReturnType<PosixWorkspaceManager['list']>> | null = null;
      try {
        listed = await mgr.list(source);
      } catch (_e) {
        listed = null;
      }
      if (listed === null) {
        const normalized: string = normalizeWorkspacePath(source);
        entries.push({ name: normalized, data: await mgr.readBytes(normalized) });
      } else {
        for (const e of listed) {
          if (!e.directory) entries.push({ name: e.path, data: await mgr.readBytes(e.path) });
        }
      }
    }
    const now: { time: number; date: number } = dosTimeDate(new Date());
    // addZipEntry:safe = normalize(path).removePrefix("./") — normalize 永
    //   不产出 './' 前缀,removePrefix 恒 no-op(忠实)
    return await buildZipBytes(entries, deps.deflateRaw, now.time, now.date);
  };
  return [
    // :127-149 — archive_list
    makeAgentTool({
      name: 'archive_list',
      description: 'List entries in a /workspace archive. Supports zip, tar, tar.gz, and tgz.',
      parameters: (): InputSchemaObj => ({
        type: 'object',
        properties: {
          path: stringProp('Archive path under /workspace.'),
          limit: integerProp('Maximum entries. Defaults to 200.'),
        },
        required: ['path'],
      }),
      execute: async (input: JsonValue): Promise<UIMessagePart[]> =>
        track('archive_list', '查看压缩包', input, 'Workspace artifact',
          async (): Promise<UIMessagePart[]> => {
            const path: string = toolInputRequiredString(input, 'path');
            const limit: number = inputLimit(input, 'limit', 200, 1000);
            const bytes: Uint8Array = await mgr.readBytesCapped(path, MAX_ARCHIVE_BYTES);
            const entries: JsonValue[] = await listArchiveEntries(path, bytes, limit);
            return textJson({ path, entries: entries as unknown as JsonValue });
          }),
    }),
    // :151-181 — archive_extract
    makeAgentTool({
      name: 'archive_extract',
      description: 'Extract a zip/tar/tar.gz/tgz archive inside /workspace. ' +
        'Blocks Zip Slip and path traversal.',
      parameters: (): InputSchemaObj => ({
        type: 'object',
        properties: {
          path: stringProp('Archive path under /workspace.'),
          destination_path: stringProp('Destination directory under /workspace. ' +
            'Defaults to /workspace/extracted/<archive-name>.'),
          overwrite: booleanProp('Overwrite existing files. Defaults to false.'),
        },
        required: ['path'],
      }),
      needsApproval: true,
      allowsAutoApproval: false,
      execute: async (input: JsonValue): Promise<UIMessagePart[]> =>
        track('archive_extract', '解压压缩包', input, 'Workspace artifact',
          async (): Promise<UIMessagePart[]> => {
            const path: string = toolInputRequiredString(input, 'path');
            const rawDest: string | null = toolInputString(input, 'destination_path');
            const destination: string = rawDest !== null && rawDest.trim().length > 0
              ? rawDest
              : `extracted/${safeBaseName(path)}`;
            const overwrite: boolean = toolInputBoolean(input, 'overwrite') ?? false;
            const bytes: Uint8Array = await mgr.readBytesCapped(path, MAX_ARCHIVE_BYTES);
            const result = await extractArchive(path, bytes, destination, overwrite);
            return textJson({
              path,
              destination_path: destination,
              files_written: result.files,
              directories_seen: result.directories,
              bytes_written: result.bytes,
            });
          }),
    }),
    // :183-215 — archive_create
    makeAgentTool({
      name: 'archive_create',
      description: 'Create a zip archive from /workspace files or folders. ' +
        'Stage 1 supports format=zip.',
      parameters: (): InputSchemaObj => ({
        type: 'object',
        properties: {
          source_paths: arrayProp('Workspace-relative files/folders to include.'),
          destination_path: stringProp('Destination archive path under /workspace.'),
          format: stringProp('Archive format. Stage 1 supports zip.'),
        },
        required: ['source_paths', 'destination_path'],
      }),
      needsApproval: true,
      allowsAutoApproval: false,
      execute: async (input: JsonValue): Promise<UIMessagePart[]> =>
        track('archive_create', '创建压缩包', input, 'Workspace artifact',
          async (): Promise<UIMessagePart[]> => {
            const format: string = toolInputString(input, 'format') ?? 'zip';
            if (format !== 'zip') {
              throw new Error('archive_create stage1 supports format=zip only');
            }
            const rawSources: JsonValue | undefined =
              (input as JsonObject | null)?.['source_paths'] as JsonValue | undefined;
            const sources: string[] = jsonArrayStrings(rawSources);
            if (sources.length === 0) throw new Error('source_paths must not be empty');
            const bytes: Uint8Array = await createZip(sources);
            const entry = await mgr.writeBytes(
              toolInputRequiredString(input, 'destination_path'), bytes, 'application/zip');
            return textJson({
              path: entry.path,
              format: 'zip',
              size_bytes: bytes.length,
            });
          }),
    })
  ];
};
