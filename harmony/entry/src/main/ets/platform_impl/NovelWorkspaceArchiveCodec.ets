// 小说 ZIP/EPUB 平台适配：复用 Chat 的中央目录解析和 ZIP writer，
// 只注入 Harmony zlib raw inflate/deflate，不在 Novel 复制归档轮子。

import type {
  NovelBookZipEncoder, NovelBookZipEntry, NovelWorkspaceArchiveCodec,
  NovelWorkspaceArchiveEntry, NovelWorkspaceArchiveFile,
} from '@amber/deepread-domain';
import {
  buildZipBytes, dosTimeDate, readZipEntries, readZipEntryBytes,
} from '@amber/chat-domain';
import type { ZipEntryRecord, ZipWriteEntry } from '@amber/chat-domain';
import { createDeflateRawPort } from './ArtifactPorts.ets';
import { inflateRawEntry } from './EntryDocumentParser.ets';
import { util } from '@kit.ArkTS';

const ZIP_EPOCH: Date = new Date(1980, 0, 1, 0, 0, 0);

class EntryNovelWorkspaceArchiveCodec implements NovelWorkspaceArchiveCodec {
  async list(archive: Uint8Array): Promise<NovelWorkspaceArchiveEntry[]> {
    return readZipEntries(archive).map((record: ZipEntryRecord): NovelWorkspaceArchiveEntry => ({
      path: record.name,
      compressedSize: record.compressedSize,
      uncompressedSize: record.uncompressedSize,
      isDirectory: record.name.endsWith('/'),
    }));
  }

  async extract(archive: Uint8Array, path: string): Promise<Uint8Array> {
    const record: ZipEntryRecord | undefined = readZipEntries(archive).find(
      (entry: ZipEntryRecord): boolean => entry.name === path,
    );
    if (record === undefined) throw new Error(`ZIP entry missing: ${path}`);
    return await readZipEntryBytes(archive, record, inflateRawEntry);
  }

  async create(files: NovelWorkspaceArchiveFile[]): Promise<Uint8Array> {
    const entries: ZipWriteEntry[] = files.map((file: NovelWorkspaceArchiveFile): ZipWriteEntry => ({
      name: file.path,
      data: file.bytes,
      compression: 'deflate',
    }));
    const stamp = dosTimeDate(ZIP_EPOCH);
    return await buildZipBytes(entries, createDeflateRawPort(), stamp.time, stamp.date);
  }
}

class EntryNovelBookZipEncoder implements NovelBookZipEncoder {
  async encode(entries: NovelBookZipEntry[]): Promise<Uint8Array> {
    const encoder = util.TextEncoder.create('utf-8');
    const zipEntries: ZipWriteEntry[] = entries.map((entry: NovelBookZipEntry): ZipWriteEntry => ({
      name: entry.name,
      data: new Uint8Array(encoder.encodeInto(entry.text)),
      compression: entry.compression,
    }));
    const stamp = dosTimeDate(ZIP_EPOCH);
    return await buildZipBytes(zipEntries, createDeflateRawPort(), stamp.time, stamp.date);
  }
}

export const createNovelWorkspaceArchiveCodec = (): NovelWorkspaceArchiveCodec =>
  new EntryNovelWorkspaceArchiveCodec();

export const createNovelBookZipEncoder = (): NovelBookZipEncoder =>
  new EntryNovelBookZipEncoder();
