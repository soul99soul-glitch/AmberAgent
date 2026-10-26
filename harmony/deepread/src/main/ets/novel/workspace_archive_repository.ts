import type { FileStore } from '../platform/files.ts';
import { createMemoryFileStore } from '../platform/files.ts';
import { invalidInput } from './error.ts';
import type { NovelBranch, NovelChapter, NovelProject } from './models.ts';
import { novelId } from './models.ts';
import { copyNovelNativeBackup } from './native_backup_copy.ts';
import type { NovelNativeBackupImport } from './native_backup.ts';
import type { NovelProjectRepository } from './repository_contract.ts';
import type { NovelBookChapterSnapshot, NovelBookExportInput } from './workspace_book_export.ts';
import type {
  SnapshotFile, WorkspaceBranchSnapshot, WorkspaceCheckout, WorkspaceCommitSnapshot, WorkspaceLedgerCommit,
  WorkspaceNovelJobStore,
} from './workspace_codec.ts';
import {
  CHECKOUT_FILE, GHOSTWRITE_JOBS_FILE, LEDGER_FILE, PUBLIC_ORIGINALS_PREFIX, assertBackupJobsIdle,
  branchSnapshotPath, buildSnapshotFiles, chapterOrdinal, commitSnapshotPath, decodeLegacy, encodeUtf8,
  parseNovelJobStore, snapshotTreeDigest,
} from './workspace_codec.ts';
import type { NovelWorkspaceManifest } from './workspace_contract.ts';
import { parseNovelWorkspaceManifest } from './workspace_contract.ts';
import type { NovelWorkspaceArchiveFile, NovelWorkspaceValidatedImport } from './workspace_exchange.ts';
import { decodeNovelWorkspaceUtf8 } from './workspace_exchange.ts';
import type { NovelWorkspaceBranchImport, NovelWorkspaceImportPlan } from './workspace_interop.ts';
import { buildNovelWorkspaceImportPlan } from './workspace_interop.ts';
import { commitLocked } from './workspace_project_repository.ts';
import type {
  NovelNativeBackupSnapshot, NovelNativeRestorePreview, NovelProjectFailure, NovelProjectInventory,
  NovelProjectRecoveryPreview,
} from './workspace_storage.ts';
import { workspaceStorageToken as stableDigest, writeWorkspaceStorageFiles } from './workspace_storage.ts';
import type { WorkspaceScope, WorkspaceSession, WorkspaceStorage } from './workspace_transaction.ts';
import { readCheckout, validateFullTree } from './workspace_tree.ts';

type ArchiveSession = Pick<WorkspaceSession,
  'branch' | 'checkout' | 'commit' | 'commits' | 'deleteProject' | 'exists' | 'files' | 'isLiveDirectory' | 'jobs' | 'loadProject' | 'migrateLegacy' | 'projectFromState' | 'readBytes' | 'readLegacyDeleted' | 'readText' | 'recover' | 'removeLegacyDeleted' | 'replaceTree' | 'replay' | 'restoreDeleted'>;
type ArchiveStorage = WorkspaceScope<ArchiveSession> & Pick<WorkspaceStorage, 'list' | 'deletedProject' | 'readLegacyDeleted' | 'readManifest'>;

const failedProjectName = async (storage: Pick<WorkspaceStorage, 'readManifest'>, id: string): Promise<string> => {
  try {
    const raw: string | null = await storage.readManifest(id);
    return raw === null ? id : parseNovelWorkspaceManifest(raw).title;
  } catch { return id; }
};

const recoveryCandidate = async (tx: ArchiveSession, id: string): Promise<{
  preview: NovelProjectRecoveryPreview; files: NovelWorkspaceArchiveFile[];
} | null> => {
  const liveFiles: NovelWorkspaceArchiveFile[] = await tx.files('live');
  const backupFiles: NovelWorkspaceArchiveFile[] = await tx.files('trash');
  if (backupFiles.length > 0) {
    try {
      const project: NovelProject = await validateFullTree(backupFiles);
      if (project.id !== id) throw invalidInput('恢复项目 id 不一致');
      return {
        preview: {
          projectId: id, title: project.name, source: 'trash',
          sourceToken: stableDigest(liveFiles) + ':' + stableDigest(backupFiles)
        }, files: backupFiles
      };
    } catch { /* Invalid trash is preserved, then inspect the canonical head snapshot. */ }
  }
  try {
    const checkout: WorkspaceCheckout | null = await tx.checkout();
    if (checkout === null) return null;
    const raw: string | null = await tx.readText(commitSnapshotPath(checkout.head));
    if (raw === null) return null;
    const snapshot: WorkspaceCommitSnapshot = JSON.parse(raw) as WorkspaceCommitSnapshot;
    const built = buildSnapshotFiles(snapshot.project, checkout.branchId, snapshot.plotContent);
    const branch: WorkspaceBranchSnapshot = {
      branchId: checkout.branchId, state: built.state,
      unresolvedFromOrdinal: snapshot.unresolvedFromOrdinal, plotStale: snapshot.plotStale,
      plotSourceTreeDigest: snapshot.plotSourceTreeDigest,
      plotSyncedTreeDigest: snapshot.plotSyncedTreeDigest, plotContent: snapshot.plotContent
    };
    const hasProjectDocument: boolean = liveFiles.some(file => file.path === 'project.md');
    const replacements: SnapshotFile[] = built.files.filter(file =>
      file.path !== 'project.md' || !hasProjectDocument).concat([
        { path: branchSnapshotPath(checkout.branchId), content: JSON.stringify(branch) },
      ]);
    const replacementPaths: Set<string> = new Set(replacements.map((file: SnapshotFile): string => file.path));
    const files: NovelWorkspaceArchiveFile[] = liveFiles.filter(
      (file: NovelWorkspaceArchiveFile): boolean => !replacementPaths.has(file.path)).concat(
        replacements.map((file: SnapshotFile): NovelWorkspaceArchiveFile => ({ path: file.path, bytes: encodeUtf8(file.content) })));
    const project: NovelProject = await validateFullTree(files);
    if (project.id !== id) return null;
    return {
      preview: {
        projectId: id, title: project.name, source: 'head_snapshot',
        sourceToken: stableDigest(liveFiles) + ':' + stableDigest(backupFiles)
      }, files
    };
  } catch { return null; }
};

const nativeRestorePreview = async (tx: ArchiveSession, input: NovelNativeBackupImport): Promise<NovelNativeRestorePreview> => {
  const project: NovelProject = await validateFullTree(input.files, true);
  const memory: FileStore = createMemoryFileStore();
  await writeWorkspaceStorageFiles(memory, 'candidate', input.files);
  const checkout: WorkspaceCheckout | null = await readCheckout(memory, 'candidate');
  if (project.id !== input.projectId || project.id !== input.manifest.projectId || project.name !== input.manifest.title ||
    project.schemaVersion !== input.manifest.schemaVersion || checkout === null ||
    checkout.branchId !== input.manifest.activeBranch || checkout.head !== input.manifest.state.head ||
    checkout.treeDigest !== input.manifest.state.treeDigest || checkout.branchId !== input.manifest.state.branchId) {
    throw invalidInput('原生备份 manifest 与工作区状态不一致');
  }
  const current: NovelWorkspaceArchiveFile[] = await tx.files('live');
  const trash: NovelWorkspaceArchiveFile[] = await tx.files('trash');
  const deleted: NovelWorkspaceArchiveFile[] = await tx.files('deleted');
  return {
    projectId: project.id, title: project.name, replaceExisting: current.length > 0 || trash.length > 0 || deleted.length > 0,
    sourceToken: stableDigest(input.files) + ':' + stableDigest(current) + ':' + stableDigest(trash) + ':' + stableDigest(deleted)
  };
};

const workspaceRestorePreview = async (tx: ArchiveSession, plan: NovelWorkspaceImportPlan): Promise<NovelNativeRestorePreview> => {
  const id: string = plan.manifest.projectId;
  const current: NovelWorkspaceArchiveFile[] = await tx.files('live');
  const trash: NovelWorkspaceArchiveFile[] = await tx.files('trash');
  const deleted: NovelWorkspaceArchiveFile[] = await tx.files('deleted');
  return {
    projectId: id, title: plan.manifest.title,
    replaceExisting: current.length > 0 || trash.length > 0 || deleted.length > 0,
    sourceToken: stableDigest([{ path: 'plan.json', bytes: encodeUtf8(JSON.stringify(plan)) }]) + ':' +
      stableDigest(current) + ':' + stableDigest(trash) + ':' + stableDigest(deleted)
  };
};

const LEGACY_SUFFIX: string = '.novel.json';

export const createWorkspaceArchiveRepository = (storage: ArchiveStorage): Pick<NovelProjectRepository,
  'listProjects' | 'listProjectInventory' | 'inspectProjectRecovery' | 'readProjectRecovery'
  | 'restoreProjectRecovery' | 'nativeBackupSnapshot' | 'inspectNativeRestore' | 'copyNativeBackup'
  | 'inspectWorkspaceRestore' | 'installNativeBackup' | 'publicExportPlan' | 'installWorkspacePlan'
  | 'deleteProject' | 'restorePrevious' | 'installWorkspace' | 'workspaceFiles' | 'bookExportInput'> => {
  const repository = {
    async listProjects(): Promise<NovelProject[]> {
      return (await repository.listProjectInventory()).projects;
    },

    async listProjectInventory(): Promise<NovelProjectInventory> {
      const failures: NovelProjectFailure[] = [];
      const projects: NovelProject[] = [];
      const seen: Set<string> = new Set<string>();
      const names: string[] = await storage.list('live');
      const recoveryIds: string[] = names.filter((id: string): boolean => !id.startsWith('.'));
      const backups: string[] = await storage.list('trash');
      const stages: string[] = await storage.list('stage');
      for (let i: number = 0; i < backups.length; i++) {
        if (recoveryIds.indexOf(backups[i]) < 0) recoveryIds.push(backups[i]);
      }
      for (let i: number = 0; i < stages.length; i++) {
        if (recoveryIds.indexOf(stages[i]) < 0) recoveryIds.push(stages[i]);
      }
      for (let i: number = 0; i < recoveryIds.length; i++) {
        const id: string = recoveryIds[i];
        try {
          const project: NovelProject | null = await storage.run(id, async (tx): Promise<NovelProject | null> => {
            await tx.recover();
            if (!await tx.isLiveDirectory()) return null;
            return await tx.loadProject();
          });
          if (project !== null) {
            projects.push(project);
            seen.add(id);
          }
        } catch (error) {
          failures.push({ id, name: await failedProjectName(storage, id), error: error instanceof Error ? error.message : String(error) });
          seen.add(id);
        }
      }
      const legacyNames: string[] = await storage.list('legacy');
      for (let i: number = 0; i < legacyNames.length; i++) {
        const name: string = legacyNames[i];
        if (!name.endsWith(LEGACY_SUFFIX)) continue;
        const id: string = name.slice(0, -LEGACY_SUFFIX.length);
        if (seen.has(id)) continue;
        try {
          const migrated: NovelProject | null = await storage.run(id, async (tx): Promise<NovelProject | null> => await tx.migrateLegacy());
          if (migrated !== null) projects.push(migrated);
        } catch (error) {
          failures.push({ id, name: await failedProjectName(storage, id), error: error instanceof Error ? error.message : String(error) });
        }
      }
      projects.sort((left: NovelProject, right: NovelProject): number => {
        if (right.updatedAt !== left.updatedAt) return right.updatedAt - left.updatedAt;
        return left.name.toLowerCase().localeCompare(right.name.toLowerCase());
      });
      return { projects, failures };
    },

    inspectProjectRecovery: (id: string): Promise<NovelProjectRecoveryPreview | null> =>
      storage.run(id, async (tx): Promise<NovelProjectRecoveryPreview | null> => {
        const candidate = await recoveryCandidate(tx, id);
        return candidate === null ? null : candidate.preview;
      }),

    readProjectRecovery: (preview: NovelProjectRecoveryPreview): Promise<NovelProject> =>
      storage.run(preview.projectId, async (tx): Promise<NovelProject> => {
        const candidate = await recoveryCandidate(tx, preview.projectId);
        if (candidate === null || candidate.preview.sourceToken !== preview.sourceToken ||
          candidate.preview.source !== preview.source) throw invalidInput('恢复来源已变化，请重新预览');
        return await validateFullTree(candidate.files);
      }),

    restoreProjectRecovery: (preview: NovelProjectRecoveryPreview): Promise<NovelProject> =>
      storage.run(preview.projectId, async (tx): Promise<NovelProject> => {
        const candidate = await recoveryCandidate(tx, preview.projectId);
        if (candidate === null || candidate.preview.sourceToken !== preview.sourceToken ||
          candidate.preview.source !== preview.source) throw invalidInput('恢复来源已变化，请重新预览');
        return await tx.replaceTree(candidate.files, { validateStaged: true, retainOtherRoots: false });
      }),

    nativeBackupSnapshot: (id: string): Promise<NovelNativeBackupSnapshot> =>
      storage.run(id, async (tx): Promise<NovelNativeBackupSnapshot> => {
        const project: NovelProject = await tx.loadProject();
        assertBackupJobsIdle(await tx.jobs());
        const checkout: WorkspaceCheckout | null = await tx.checkout();
        if (checkout === null) throw invalidInput('checkout 无效');
        const files: NovelWorkspaceArchiveFile[] = await tx.files('live');
        await validateFullTree(files);
        return {
          metadata: {
            projectId: id, title: project.name, schemaVersion: project.schemaVersion,
            activeBranch: checkout.branchId, state: checkout, createdAt: Date.now()
          }, files
        };
      }),

    inspectNativeRestore: (input: NovelNativeBackupImport): Promise<NovelNativeRestorePreview> =>
      storage.run(input.projectId, async (tx): Promise<NovelNativeRestorePreview> => await nativeRestorePreview(tx, input)),

    copyNativeBackup: async (input: NovelNativeBackupImport, projectId: string): Promise<NovelNativeBackupImport> => {
      await validateFullTree(input.files, true);
      const copied: NovelNativeBackupImport = copyNovelNativeBackup(input, projectId);
      await validateFullTree(copied.files, true);
      return copied;
    },

    inspectWorkspaceRestore: (plan: NovelWorkspaceImportPlan): Promise<NovelNativeRestorePreview> =>
      storage.run(plan.manifest.projectId, async (tx): Promise<NovelNativeRestorePreview> => await workspaceRestorePreview(tx, plan)),

    installNativeBackup: (input: NovelNativeBackupImport, preview: NovelNativeRestorePreview): Promise<NovelProject> =>
      storage.run(input.projectId, async (tx): Promise<NovelProject> => {
        const current: NovelNativeRestorePreview = await nativeRestorePreview(tx, input);
        if (current.projectId !== preview.projectId || current.sourceToken !== preview.sourceToken ||
          current.replaceExisting !== preview.replaceExisting) throw invalidInput('恢复目标或备份已变化，请重新预览');
        const jobsBytes: Uint8Array | null = await tx.readBytes(GHOSTWRITE_JOBS_FILE);
        if (jobsBytes !== null) {
          let jobs: WorkspaceNovelJobStore | null = null;
          try { jobs = parseNovelJobStore(decodeNovelWorkspaceUtf8(jobsBytes)); }
          catch { /* Explicit replacement retains unreadable old records with the old tree. */ }
          if (jobs !== null) assertBackupJobsIdle(jobs);
        }
        return await tx.replaceTree(input.files, { validateStaged: true, retainOtherRoots: true });
      }),

    publicExportPlan: (id: string): Promise<NovelWorkspaceImportPlan> =>
      storage.run(id, async (tx): Promise<NovelWorkspaceImportPlan> => {
        const project: NovelProject = await tx.loadProject();
        const checkout: WorkspaceCheckout | null = await tx.checkout();
        if (checkout === null) throw invalidInput('checkout 无效');
        const allFiles: NovelWorkspaceArchiveFile[] = await tx.files('live');
        const originals: NovelWorkspaceArchiveFile[] = allFiles.filter(
          (file: NovelWorkspaceArchiveFile): boolean => file.path.startsWith(PUBLIC_ORIGINALS_PREFIX)).map(
            (file: NovelWorkspaceArchiveFile): NovelWorkspaceArchiveFile => ({
              path: file.path.slice(PUBLIC_ORIGINALS_PREFIX.length), bytes: file.bytes,
            }));
        let originalPlan: NovelWorkspaceImportPlan | null = null;
        const originalManifest: NovelWorkspaceArchiveFile | undefined = originals.find(
          (file: NovelWorkspaceArchiveFile): boolean => file.path === 'manifest.yaml');
        if (originalManifest !== undefined) {
          const projectDocument: NovelWorkspaceArchiveFile | undefined = originals.find(
            (file: NovelWorkspaceArchiveFile): boolean => file.path === 'project.md');
          originalPlan = buildNovelWorkspaceImportPlan(parseNovelWorkspaceManifest(
            decodeNovelWorkspaceUtf8(originalManifest.bytes), projectDocument === undefined ? undefined :
            decodeNovelWorkspaceUtf8(projectDocument.bytes)), originals);
        }
        const branches: NovelWorkspaceBranchImport[] = [];
        const managedPaths: Set<string> = new Set(['manifest.yaml', 'project.md']);
        for (let i: number = 0; i < project.branches.length; i++) {
          const branch: NovelBranch = project.branches[i];
          if (branch.lifecycle !== 'active') continue;
          const snapshot: WorkspaceBranchSnapshot = await tx.branch(branch.id);
          const branchProject: NovelProject = await tx.projectFromState(snapshot.state);
          buildSnapshotFiles(branchProject, branch.id, snapshot.plotContent).files.forEach(
            (file: SnapshotFile): void => { managedPaths.add(file.path); });
          const originalBranch: NovelWorkspaceBranchImport | undefined = originalPlan?.branches.find(
            (item: NovelWorkspaceBranchImport): boolean => item.id === branch.id);
          branches.push({
            id: branch.id, pathName: originalBranch?.pathName ?? branch.id, name: branch.name,
            project: { ...branchProject, name: project.name, polishPreference: project.polishPreference, branches: project.branches }, plotContent: snapshot.plotContent,
            plotStale: snapshot.plotStale, unresolvedFromChapterOrdinal: snapshot.unresolvedFromOrdinal
          });
        }
        const originalPaths: Set<string> = new Set(originals.map((file: NovelWorkspaceArchiveFile): string => file.path));
        const unsupported: NovelWorkspaceArchiveFile[] = allFiles.filter((file: NovelWorkspaceArchiveFile): boolean =>
          !file.path.startsWith('.amber/') && !managedPaths.has(file.path) && !originalPaths.has(file.path));
        const files: NovelWorkspaceArchiveFile[] = originals.length > 0 ? originals.concat(unsupported) :
          allFiles.filter((file: NovelWorkspaceArchiveFile): boolean => !file.path.startsWith('.amber/'));
        const manifest: NovelWorkspaceManifest = parseNovelWorkspaceManifest(
          await tx.readText('manifest.yaml') ?? '');

        return {
          manifest, activeBranchId: checkout.branchId, branches, files,
          unsupportedPaths: unsupported.map((file: NovelWorkspaceArchiveFile): string => file.path)
        };
      }),

    installWorkspacePlan: (plan: NovelWorkspaceImportPlan, preview?: NovelNativeRestorePreview): Promise<NovelProject> =>
      storage.run(plan.manifest.projectId, async (tx): Promise<NovelProject> => {
        const id: string = plan.manifest.projectId;
        const current: NovelNativeRestorePreview = await workspaceRestorePreview(tx, plan);
        if (preview === undefined && current.replaceExisting) throw invalidInput('导入项目 id 已存在');
        if (preview !== undefined && (preview.projectId !== id || preview.sourceToken !== current.sourceToken ||
          preview.replaceExisting !== current.replaceExisting)) throw invalidInput('导入目标或工作区已变化，请重新预览');
        const jobsRaw: string | null = await tx.readText(GHOSTWRITE_JOBS_FILE);
        if (jobsRaw !== null) {
          let jobs: WorkspaceNovelJobStore | null = null;
          try { jobs = parseNovelJobStore(jobsRaw); } catch { /* Retain unreadable original evidence during explicit replacement. */ }
          if (jobs !== null) assertBackupJobsIdle(jobs);
        }
        const active: NovelWorkspaceBranchImport | undefined = plan.branches.find(
          (branch: NovelWorkspaceBranchImport): boolean => branch.id === plan.activeBranchId);
        if (active === undefined) throw invalidInput('导入活动分支不存在');
        const contents: Map<string, NovelWorkspaceArchiveFile> = new Map();
        const unsupportedPaths: Set<string> = new Set(plan.unsupportedPaths);
        plan.files.forEach((file: NovelWorkspaceArchiveFile): void => {
          if (unsupportedPaths.has(file.path)) contents.set(file.path, file);
          else {
            const path: string = PUBLIC_ORIGINALS_PREFIX + file.path;
            contents.set(path, { path, bytes: file.bytes });
          }
          if (file.path === 'project.md') contents.set(file.path, file);
        });
        const commits: WorkspaceLedgerCommit[] = [];
        const order: NovelWorkspaceBranchImport[] = plan.branches.filter(
          (branch: NovelWorkspaceBranchImport): boolean => branch.id !== active.id).concat([active]);
        let activeCheckout: WorkspaceCheckout | null = null;
        for (let i: number = 0; i < order.length; i++) {
          const branch: NovelWorkspaceBranchImport = order[i];
          if (branch.project.id !== id) throw invalidInput('导入分支项目 id 不一致');
          const built = buildSnapshotFiles(branch.project, branch.id, branch.plotContent);
          const digest: string = snapshotTreeDigest(built.files);
          const head: string = `import-${i}-${digest}`;
          const plotSynced: string | null = branch.plotStale ? null : digest;
          const snapshot: WorkspaceBranchSnapshot = {
            branchId: branch.id, state: built.state,
            unresolvedFromOrdinal: branch.unresolvedFromChapterOrdinal, plotStale: branch.plotStale,
            plotSourceTreeDigest: digest, plotSyncedTreeDigest: plotSynced, plotContent: branch.plotContent
          };
          const commitSnapshot: WorkspaceCommitSnapshot = {
            project: built.project,
            unresolvedFromOrdinal: branch.unresolvedFromChapterOrdinal, plotStale: branch.plotStale,
            plotSourceTreeDigest: digest, plotSyncedTreeDigest: plotSynced, plotContent: branch.plotContent
          };
          const files: SnapshotFile[] = built.files.filter((file: SnapshotFile): boolean => file.path !== 'project.md').concat([
            { path: branchSnapshotPath(branch.id), content: JSON.stringify(snapshot) },
            { path: commitSnapshotPath(head), content: JSON.stringify(commitSnapshot) },
          ]);
          files.forEach((file: SnapshotFile): void => { contents.set(file.path, { path: file.path, bytes: encodeUtf8(file.content) }); });
          commits.push({
            version: 1, commitId: head, parent: commits.length === 0 ? null : commits[commits.length - 1].commitId, branchId: branch.id, treeDigest: digest,
            mutation: 'workspace_import', receipt: `workspace_import:${id}:${branch.id}`,
            changedPaths: [`branches/${branch.id}/plan/state.md`], changedChapterOrdinal: null, createdAt: branch.project.updatedAt
          });
          if (branch.id === active.id) activeCheckout = { branchId: branch.id, head, treeDigest: digest };
        }
        contents.set(LEDGER_FILE, {
          path: LEDGER_FILE, bytes: encodeUtf8(commits.map(
            (commit: WorkspaceLedgerCommit): string => JSON.stringify(commit)).join('\n') + '\n')
        });
        contents.set(CHECKOUT_FILE, { path: CHECKOUT_FILE, bytes: encodeUtf8(JSON.stringify(activeCheckout)) });
        const files: NovelWorkspaceArchiveFile[] = Array.from(contents.values());
        await validateFullTree(files);
        return await tx.replaceTree(files, { validateStaged: false, retainOtherRoots: true });
      }),

    deleteProject: (id: string): Promise<void> => storage.run(id, async (tx) => tx.deleteProject()),

    async restorePrevious(): Promise<NovelProject | null> {
      const names: string[] = await storage.list('deleted');
      let best: NovelProject | null = null;
      let bestId: string = '';
      let bestLegacyFile: string = '';
      let bestDeletedAt: number = -1;
      for (let i: number = 0; i < names.length; i++) {
        try {
          const { project, deletedAt } = await storage.deletedProject(names[i]);
          if (best === null || deletedAt > bestDeletedAt) {
            best = project;
            bestId = names[i];
            bestLegacyFile = '';
            bestDeletedAt = deletedAt;
          }
        } catch {
          // 跳过损坏的回收项，不删除证据。
        }
      }
      const legacyNames: string[] = await storage.list('legacyDeleted');
      for (let i: number = 0; i < legacyNames.length; i++) {
        if (!legacyNames[i].endsWith(LEGACY_SUFFIX)) continue;
        const path: string = legacyNames[i];
        try {
          const raw: string | null = await storage.readLegacyDeleted(path);
          if (raw === null) continue;
          const project: NovelProject = decodeLegacy(raw);
          if (best === null) {
            best = project;
            bestId = '';
            bestLegacyFile = path;
          }
        } catch {
          // 旧版损坏 JSON 必须保留，便于人工取证或修复。
        }
      }
      if (best === null) return null;
      if (bestLegacyFile.length > 0) {
        const selectedId: string = best.id;
        const selectedFile: string = bestLegacyFile;
        return await storage.run(selectedId, async (tx): Promise<NovelProject> => {
          const raw: string | null = await tx.readLegacyDeleted(selectedFile);
          if (raw === null) throw invalidInput('待恢复项目已不存在');
          const selected: NovelProject = decodeLegacy(raw);
          if (selected.id !== selectedId) throw invalidInput('待恢复项目已变更');
          await tx.recover();
          if (await tx.exists('live')) throw invalidInput('同 id 项目已存在');
          const restored: NovelProject = await tx.commit({ project: selected, mutation: 'legacy_restore' });
          await tx.removeLegacyDeleted(selectedFile);
          return restored;
        });
      }
      return await storage.run(best.id, async (tx): Promise<NovelProject> => {
        await tx.restoreDeleted(bestId);
        await tx.loadProject();
        const checkout: WorkspaceCheckout | null = await tx.checkout();
        if (checkout === null) throw invalidInput('checkout 无效');
        return await commitLocked(tx, best.id, checkout, novelId(), 'compat_update', (project: NovelProject): NovelProject => project);
      });
    },

    async installWorkspace(input: NovelWorkspaceValidatedImport): Promise<NovelProject> {
      const requestedId: string = input.manifest.projectId;
      const requestedAvailable: boolean = await storage.run(requestedId, async (tx): Promise<boolean> => {
        await tx.recover();
        return !await tx.exists('live') && !await tx.exists('deleted');
      });
      const id: string = requestedAvailable ? requestedId : novelId();
      const plan: NovelWorkspaceImportPlan = buildNovelWorkspaceImportPlan(input.manifest, input.files);
      if (id !== requestedId) {
        plan.manifest = { ...plan.manifest, projectId: id };
        plan.branches = plan.branches.map((branch: NovelWorkspaceBranchImport): NovelWorkspaceBranchImport => ({
          ...branch, project: { ...branch.project, id },
        }));
      }
      return await repository.installWorkspacePlan(plan);
    },

    async workspaceFiles(id: string): Promise<NovelWorkspaceArchiveFile[]> {
      return await storage.run(id, async (tx): Promise<NovelWorkspaceArchiveFile[]> => {
        await tx.loadProject();
        const files: NovelWorkspaceArchiveFile[] = await tx.files('live');
        return files.filter((file: NovelWorkspaceArchiveFile): boolean =>
          !file.path.startsWith('.amber/'));
      });
    },

    async bookExportInput(id: string): Promise<NovelBookExportInput> {
      return await storage.run(id, async (tx): Promise<NovelBookExportInput> => {
        const project: NovelProject = await tx.loadProject();
        const visible: NovelChapter[] = project.chapters.filter(
          (chapter: NovelChapter): boolean => !chapter.discarded,
        );
        const chapters: NovelBookChapterSnapshot[] = visible.map(
          (chapter: NovelChapter, index: number): NovelBookChapterSnapshot => ({
            ordinal: chapterOrdinal(chapter, index + 1),
            title: chapter.title,
            content: chapter.content,
          }),
        );
        return { bookTitle: project.name, chapters };
      });
    }
  };
  return repository;
};
