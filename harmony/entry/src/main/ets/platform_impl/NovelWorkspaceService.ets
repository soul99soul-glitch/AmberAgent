// 小说工作区交换/书籍导出应用服务。repository 仍是唯一数据源；
// 本层只把纯域校验、共享 ZIP codec 与文件选择 UI 之间接起来。

import type {
  NovelBookExport, NovelProject, NovelProjectRepository,
} from '@amber/deepread-domain';
import {
  buildNovelBookExport, encodeNovelBookEpub, exportNovelWorkspaceArchive,
  importNovelWorkspaceArchive,
} from '@amber/deepread-domain';
import {
  createNovelBookZipEncoder, createNovelWorkspaceArchiveCodec,
} from './NovelWorkspaceArchiveCodec.ets';

export interface NovelBookExportBundle {
  text: string;
  markdown: string;
  epub: Uint8Array;
}

export interface NovelWorkspaceService {
  importZip(bytes: Uint8Array): Promise<NovelProject>;
  exportWorkspaceZip(projectId: string): Promise<Uint8Array>;
  exportBook(projectId: string): Promise<NovelBookExportBundle>;
}

class EntryNovelWorkspaceService implements NovelWorkspaceService {
  private readonly repository: NovelProjectRepository;

  constructor(repository: NovelProjectRepository) {
    this.repository = repository;
  }

  async importZip(bytes: Uint8Array): Promise<NovelProject> {
    const codec = createNovelWorkspaceArchiveCodec();
    const validated = await importNovelWorkspaceArchive(codec, bytes);
    return await this.repository.installWorkspace(validated);
  }

  async exportWorkspaceZip(projectId: string): Promise<Uint8Array> {
    const codec = createNovelWorkspaceArchiveCodec();
    return await exportNovelWorkspaceArchive(
      codec, await this.repository.workspaceFiles(projectId));
  }

  async exportBook(projectId: string): Promise<NovelBookExportBundle> {
    const exported: NovelBookExport = buildNovelBookExport(
      await this.repository.bookExportInput(projectId));
    const epub: Uint8Array = await encodeNovelBookEpub(
      exported.epub, createNovelBookZipEncoder());
    const bundle: NovelBookExportBundle = {
      text: exported.txt,
      markdown: exported.markdown,
      epub,
    };
    return bundle;
  }
}

export const createNovelWorkspaceService = (
  repository: NovelProjectRepository,
): NovelWorkspaceService => new EntryNovelWorkspaceService(repository);
