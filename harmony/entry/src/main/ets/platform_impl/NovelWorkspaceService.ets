// 小说工作区交换/书籍导出应用服务。repository 仍是唯一数据源；
// 本层只把纯域校验、共享 ZIP codec 与文件选择 UI 之间接起来。

import type {
  NovelBookExport, NovelProject, NovelProjectRepository, NovelCreation,
  NovelNativeBackupImport, NovelNativeRestorePreview, NovelWorkspaceImportPlan,
  NovelWorkspaceArchiveCodec, NovelWorkspaceArchiveEntry, NovelWorkspaceValidatedImport,
  NovelNativeBackupSnapshot, NovelWorkspaceCas, DurableWorkspaceProposal, NovelProjectToolInput,
} from '@amber/deepread-domain';
import {
  buildNovelBookExport, encodeNovelBookEpub, exportNovelWorkspaceArchive,
  importNovelWorkspaceArchive, buildNovelWorkspaceImportPlan, buildNovelWorkspacePublicFiles,
  exportNovelNativeBackup, importNovelNativeBackup, NOVEL_NATIVE_BACKUP_MANIFEST_PATH,
} from '@amber/deepread-domain';
import { copyNovelWorkspaceImportPlan } from '@amber/deepread-domain';
import type { NovelImportResolution } from '@amber/deepread-domain';
import { novelId } from '@amber/deepread-domain';
import {
  createNovelBookZipEncoder, createNovelWorkspaceArchiveCodec,
} from './NovelWorkspaceArchiveCodec.ets';

export interface NovelBookExportBundle {
  text: string;
  markdown: string;
  epub: Uint8Array;
}

export interface NovelWorkspaceImportPreview {
  kind: 'native' | 'public';
  collision: boolean;
  projectId: string;
  description: string;
  native: NovelNativeBackupImport | null;
  nativeRestore: NovelNativeRestorePreview | null;
  workspace: NovelWorkspaceImportPlan | null;
}

export type NovelManuscriptOperationKind = 'revise_chapter' | 'delete_chapters' | 'revert_recent_chapters';
export interface NovelManuscriptOperationArgs extends NovelProjectToolInput {}

export interface NovelWorkspaceService {
  proposeManuscriptOperation(projectId: string, kind: NovelManuscriptOperationKind, args: NovelManuscriptOperationArgs, expectedCas: NovelWorkspaceCas): Promise<DurableWorkspaceProposal>;
  inspectImport(bytes: Uint8Array): Promise<NovelWorkspaceImportPreview>;
  confirmImport(preview: NovelWorkspaceImportPreview, resolution?: NovelImportResolution): Promise<NovelProject>;
  exportNativeBackupZip(projectId: string): Promise<Uint8Array>;
  exportWorkspaceZip(projectId: string): Promise<Uint8Array>;
  exportBook(projectId: string): Promise<NovelBookExportBundle>;
}

class EntryNovelWorkspaceService implements NovelWorkspaceService {
  private readonly repository: NovelProjectRepository;
  private readonly creation: NovelCreation;

  constructor(repository: NovelProjectRepository, creation: NovelCreation) {
    this.repository = repository;
    this.creation = creation;
  }

  proposeManuscriptOperation(projectId: string, kind: NovelManuscriptOperationKind,
    args: NovelManuscriptOperationArgs, expectedCas: NovelWorkspaceCas): Promise<DurableWorkspaceProposal> {
    if (this.creation.activeRun(projectId) !== null) return Promise.reject(new Error('创作正在进行，请完成或停止后再生成正文修改建议'));
    return this.creation.proposeProjectOperation(projectId, kind, args, expectedCas);
  }

  async inspectImport(bytes: Uint8Array): Promise<NovelWorkspaceImportPreview> {
    const codec: NovelWorkspaceArchiveCodec = createNovelWorkspaceArchiveCodec();
    const entries: NovelWorkspaceArchiveEntry[] = await codec.list(bytes);
    const native: boolean = entries.some((entry: NovelWorkspaceArchiveEntry): boolean =>
      entry.path === NOVEL_NATIVE_BACKUP_MANIFEST_PATH);
    if (native) {
      const imported: NovelNativeBackupImport = await importNovelNativeBackup(codec, bytes);
      const restore: NovelNativeRestorePreview = await this.creation.inspectNativeRestore(imported);
      const replacement: string = restore.replaceExisting
        ? '\n本机已有相同项目，请选择保留两份或明确替换；原目录会完整保留。'
        : '\n将作为新项目恢复。';
      return {
        kind: 'native', collision: restore.replaceExisting, projectId: restore.projectId,
        description: `项目：${restore.title}\n完整恢复正文、资料、所有分支、创作记录、版本和任务状态。${replacement}`,
        native: imported, nativeRestore: restore, workspace: null,
      };
    }
    const validated: NovelWorkspaceValidatedImport = await importNovelWorkspaceArchive(codec, bytes);
    const plan: NovelWorkspaceImportPlan = buildNovelWorkspaceImportPlan(validated.manifest, validated.files);
    const restore: NovelNativeRestorePreview = await this.repository.inspectWorkspaceRestore(plan);
    const title: string = plan.branches[0].project.name;
    const unsupported: string = plan.unsupportedPaths.length > 0
      ? `\n另有 ${plan.unsupportedPaths.length} 个扩展文件按原样保留，不参与创作上下文。` : '';
    return {
      kind: 'public', collision: restore.replaceExisting, projectId: restore.projectId,
      description: `项目：${title}\n导入 ${plan.branches.length} 个分支的正文、资料、计划和剧情。\n公开工作区不包含私有创作记录、历史版本和任务状态；${restore.replaceExisting ? '本机已有相同项目，请选择保留两份或明确替换；替换时原目录完整保留。' : '将作为新项目导入。'}${unsupported}`,
      native: null, nativeRestore: restore, workspace: plan,
    };
  }

  async confirmImport(preview: NovelWorkspaceImportPreview,
    resolution: NovelImportResolution = 'reject'): Promise<NovelProject> {
    if (preview.collision && resolution === 'reject') throw new Error('本机已有同名项目标识，请选择保留两份或替换');
    if (preview.kind === 'native' && preview.native !== null && preview.nativeRestore !== null) {
      if (resolution === 'keepBoth') {
        const copied: NovelNativeBackupImport = await this.repository.copyNativeBackup(preview.native, novelId());
        return await this.creation.installNativeBackup(copied, await this.creation.inspectNativeRestore(copied));
      }
      return await this.creation.installNativeBackup(preview.native, preview.nativeRestore);
    }
    if (preview.kind === 'public' && preview.workspace !== null && preview.nativeRestore !== null) {
      if (resolution === 'keepBoth') {
        return await this.creation.installWorkspacePlan(copyNovelWorkspaceImportPlan(preview.workspace, novelId()));
      }
      return await this.creation.installWorkspacePlan(preview.workspace, preview.nativeRestore);
    }
    throw new Error('导入预览无效，请重新选择文件');
  }

  async exportWorkspaceZip(projectId: string): Promise<Uint8Array> {
    const codec: NovelWorkspaceArchiveCodec = createNovelWorkspaceArchiveCodec();
    const plan: NovelWorkspaceImportPlan = await this.repository.publicExportPlan(projectId);
    return await exportNovelWorkspaceArchive(codec, buildNovelWorkspacePublicFiles(plan));
  }

  async exportNativeBackupZip(projectId: string): Promise<Uint8Array> {
    const snapshot: NovelNativeBackupSnapshot = await this.creation.nativeBackupSnapshot(projectId);
    return await exportNovelNativeBackup(createNovelWorkspaceArchiveCodec(), snapshot.metadata, snapshot.files);
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
  repository: NovelProjectRepository, creation: NovelCreation,
): NovelWorkspaceService => new EntryNovelWorkspaceService(repository, creation);
