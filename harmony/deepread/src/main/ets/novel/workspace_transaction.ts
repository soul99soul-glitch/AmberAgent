import type { FileStore } from '../platform/files.ts';
import { joinPath } from '../platform/files.ts';
import { invalidInput, notFound } from './error.ts';
import { withUpdatedSharedMaterials } from './material_inheritance.ts';
import type { NovelMaterial, NovelProject } from './models.ts';
import { novelId } from './models.ts';
import type { NovelWorkspaceCas } from './repository_contract.ts';
import type {
  SnapshotFile, WorkspaceBranchSnapshot, WorkspaceCheckout, WorkspaceCommitSnapshot, WorkspaceLedgerCommit,
  WorkspaceMaterialRecord, WorkspaceNovelJobStore, WorkspaceProjectState, WorkspaceProposalStore,
  WorkspaceUndoRecord,
} from './workspace_codec.ts';
import {
  CHECKOUT_FILE, GHOSTWRITE_JOBS_FILE, LEDGER_FILE, PROPOSALS_FILE, STATE_FILE, assertNoActiveNovelJob,
  branchSnapshotPath, buildSnapshotFiles, commitSnapshotPath, decodeLegacy, findReceipt,
  latestBranchCommit, mainBranch, normalizeProject, parseLedger, snapshotTreeDigest, undoPath,
  validateProject, withAuthorPlot,
} from './workspace_codec.ts';
import {
  NOVEL_WORKSPACE_FORMAT, NOVEL_WORKSPACE_VERSION, serializeNovelWorkspaceManifest,
} from './workspace_contract.ts';
import type { NovelWorkspaceArchiveFile } from './workspace_exchange.ts';
import type { WorkspaceCommit } from './workspace_history.ts';
import { projectChapterPlots } from './workspace_projection.ts';
import { swapWorkspaceStorageStage, writeWorkspaceStorageFiles } from './workspace_storage.ts';
import {
  loadAt, passthroughFilesAt, projectFromStoredState, readActiveBranchSnapshot, readCheckout, readCommits,
  readHistoricalSnapshot, readNovelJobStore, readProposalStore, validateFullTree, walkWorkspaceFiles,
  workspaceNeedsSchemaMigration,
} from './workspace_tree.ts';

const LEGACY_PROJECTS_DIR: string = 'amberagent/novel-creation/projects';
const LEGACY_DELETED_DIR: string = 'novel_deleted';
const LEGACY_SUFFIX: string = '.novel.json';
const ROOT: string = 'amberagent/novel-workspace';
const STAGING_ROOT: string = joinPath(ROOT, '.staging');
const TRASH_ROOT: string = joinPath(ROOT, '.trash');
const DELETED_ROOT: string = joinPath(ROOT, '.deleted');
const LOCK_ROOT: string = joinPath(ROOT, '.locks');
const projectRoot = (id: string): string => joinPath(ROOT, id);
const stagingRoot = (id: string): string => joinPath(STAGING_ROOT, id);
const trashRoot = (id: string): string => joinPath(TRASH_ROOT, id);
const deletedRoot = (id: string): string => joinPath(DELETED_ROOT, id);
const legacyPath = (id: string): string => joinPath(LEGACY_PROJECTS_DIR, id + LEGACY_SUFFIX);

export type WorkspaceLocation = 'live' | 'trash' | 'deleted';
export type WorkspaceDirectory = WorkspaceLocation | 'stage' | 'legacy' | 'legacyDeleted';
export interface WorkspaceCommitRequest {
  project: NovelProject;
  mutation: string;
  importedFiles?: NovelWorkspaceArchiveFile[];
  branchId?: string | null;
  receipt?: string | null;
  unresolvedFromOrdinal?: number | null;
  plotStale?: boolean;
  makeUndo?: boolean;
  changedChapterOrdinal?: number | null;
  preservePriorBranch?: boolean;
  clearUndo?: boolean;
  plotSyncedOverride?: string | null;
  plotContentOverride?: string | null;
  proposalStoreOverride?: WorkspaceProposalStore | null;
  restoredFromHead?: string;
}
interface WorkspaceInstallRequest extends WorkspaceCommitRequest {
  jobsForCheckout?: (checkout: NovelWorkspaceCas) => WorkspaceNovelJobStore;
}
interface WorkspaceInstallResult {
  project: NovelProject;
  checkout: WorkspaceCheckout;
  jobs: WorkspaceNovelJobStore | null;
}
export interface WorkspaceReplacementPolicy {
  validateStaged: boolean;
  retainOtherRoots: boolean;
}
export interface WorkspaceSession {
  recover: () => Promise<void>;
  loadProject: () => Promise<NovelProject>;
  checkout: () => Promise<WorkspaceCheckout | null>;
  branch: (branchId: string) => Promise<WorkspaceBranchSnapshot>;
  historicalSnapshot: (commit: WorkspaceCommit) => Promise<WorkspaceCommitSnapshot>;
  projectFromState: (state: WorkspaceProjectState) => Promise<NovelProject>;
  commits: () => Promise<WorkspaceLedgerCommit[]>;
  jobs: () => Promise<WorkspaceNovelJobStore>;
  proposals: () => Promise<WorkspaceProposalStore>;
  replay: (commits: WorkspaceLedgerCommit[], commandId: string) => Promise<NovelProject | null>;
  commit: (request: WorkspaceCommitRequest) => Promise<NovelProject>;
  commitJob: (request: WorkspaceCommitRequest, jobsForCheckout: (checkout: NovelWorkspaceCas) => WorkspaceNovelJobStore) => Promise<WorkspaceNovelJobStore>;
  saveJobs: (jobs: WorkspaceNovelJobStore) => Promise<void>;
  saveProposals: (proposals: WorkspaceProposalStore) => Promise<void>;
  materializeCheckout: (current: WorkspaceProjectState, target: NovelProject, snapshot: WorkspaceBranchSnapshot, checkout: WorkspaceCheckout) => Promise<void>;
  files: (location: WorkspaceLocation) => Promise<NovelWorkspaceArchiveFile[]>;
  exists: (location: WorkspaceLocation) => Promise<boolean>;
  isLiveDirectory: () => Promise<boolean>;
  readText: (path: string) => Promise<string | null>;
  readBytes: (path: string) => Promise<Uint8Array | null>;
  replaceTree: (files: NovelWorkspaceArchiveFile[], policy: WorkspaceReplacementPolicy) => Promise<NovelProject>;
  deleteProject: () => Promise<void>;
  restoreDeleted: (deletedId: string) => Promise<void>;
  readLegacyDeleted: (name: string) => Promise<string | null>;
  removeLegacyDeleted: (name: string) => Promise<void>;
  migrateLegacy: () => Promise<NovelProject | null>;
}
export interface WorkspaceScope<TSession> {
  run: <T>(id: string, op: (session: TSession) => Promise<T>) => Promise<T>;
}
export interface WorkspaceStorage extends WorkspaceScope<WorkspaceSession> {
  list: (directory: WorkspaceDirectory) => Promise<string[]>;
  deletedProject: (id: string) => Promise<{ project: NovelProject; deletedAt: number }>;
  readLegacyDeleted: (name: string) => Promise<string | null>;
  readManifest: (id: string) => Promise<string | null>;
  tryWithPolishDriverLock: <T>(id: string, jobId: string, op: () => Promise<T>) => Promise<T | null>;
  withPolishDriverLock: <T>(id: string, jobId: string, op: () => Promise<T>) => Promise<T>;
}

export const createWorkspaceStorage = (fileStore: FileStore): WorkspaceStorage => {
  const updateTails: Map<string, Promise<void>> = new Map<string, Promise<void>>();

  const withProjectLock = <T>(id: string, op: () => Promise<T>): Promise<T> => {
    const previous: Promise<void> = updateTails.get(id) ?? Promise.resolve();
    const run: Promise<T> = previous.catch((): void => { }).then(
      async (): Promise<T> => await fileStore.withExclusiveLock(joinPath(LOCK_ROOT, `${id}.lock`), op));
    const tail: Promise<void> = run.then((): void => { }, (): void => { });
    updateTails.set(id, tail);
    return run.finally((): void => {
      if (updateTails.get(id) === tail) updateTails.delete(id);
    });
  };

  const recoverProject = async (id: string): Promise<void> => {
    const live: string = projectRoot(id);
    const stage: string = stagingRoot(id);
    const backup: string = trashRoot(id);
    const liveExists: boolean = await fileStore.exists(live);
    const backupExists: boolean = await fileStore.exists(backup);
    if (!liveExists && backupExists) {
      try {
        if (await workspaceNeedsSchemaMigration(fileStore, backup)) await loadAt(fileStore, backup);
        else await validateFullTree(await walkWorkspaceFiles(fileStore, backup));
      } catch (error) {
        // An explicit repair may have moved a damaged live tree to backup. Its validated
        // replacement is still staged if the process stopped between the two renames.
        if (!await fileStore.exists(stage)) throw error;
        await validateFullTree(await walkWorkspaceFiles(fileStore, stage));
        await fileStore.rename(stage, live);
        await fileStore.rename(backup, joinPath(ROOT, '.retained', id, novelId()));
        return;
      }
      await fileStore.rename(backup, live);
    }
    else if (liveExists && backupExists) {
      // A failed live tree must never erase the only valid pre-swap copy.
      if (!await workspaceNeedsSchemaMigration(fileStore, live)) {
        await validateFullTree(await walkWorkspaceFiles(fileStore, live));
        await fileStore.rename(backup, joinPath(ROOT, '.retained', id, novelId()));
      }
    }
    await fileStore.deleteTree(stage);
  };

  const installPrivateStoreOnly = async (
    id: string, targetPath: string, content: string,
  ): Promise<void> => {
    const live: string = projectRoot(id);
    const stage: string = stagingRoot(id);
    const backup: string = trashRoot(id);
    const files: NovelWorkspaceArchiveFile[] = await walkWorkspaceFiles(fileStore, live);
    await fileStore.deleteTree(stage);
    for (let i: number = 0; i < files.length; i++) {
      const path: string = files[i].path;
      if (path === targetPath) continue;
      await fileStore.writeBytes(joinPath(stage, path), files[i].bytes);
    }
    await fileStore.writeText(joinPath(stage, targetPath), content);
    if (await fileStore.exists(backup)) await fileStore.rename(backup, joinPath(ROOT, '.retained', id, novelId()));
    await fileStore.rename(live, backup);
    try {
      await fileStore.rename(stage, live);
    } catch (error) {
      if (!await fileStore.exists(live) && await fileStore.exists(backup)) {
        await fileStore.rename(backup, live);
      }
      throw error;
    }
    await fileStore.deleteTree(backup);
  };

  const materializeCheckout = async (
    id: string, currentState: WorkspaceProjectState, target: NovelProject,
    targetSnapshot: WorkspaceBranchSnapshot, checkout: WorkspaceCheckout,
  ): Promise<void> => {
    const live: string = projectRoot(id);
    const stage: string = stagingRoot(id);
    const backup: string = trashRoot(id);
    const allFiles: NovelWorkspaceArchiveFile[] = await walkWorkspaceFiles(fileStore, live);
    await fileStore.deleteTree(stage);
    for (let i: number = 0; i < allFiles.length; i++) {
      const path: string = allFiles[i].path;
      await fileStore.writeBytes(joinPath(stage, path), allFiles[i].bytes);
    }
    for (let i: number = 0; i < currentState.materials.length; i++) {
      const material: WorkspaceMaterialRecord = currentState.materials[i];
      await fileStore.delete(joinPath(stage, `setting/${material.kind}/${material.id}.md`));
    }
    const built = buildSnapshotFiles(target, checkout.branchId, targetSnapshot.plotContent);
    if (snapshotTreeDigest(built.files) !== checkout.treeDigest) {
      throw invalidInput('目标分支 head/tree 不一致');
    }
    const targetState: WorkspaceProjectState = built.state;
    const durableTarget: WorkspaceBranchSnapshot = {
      ...targetSnapshot,
      state: targetState,
      plotSourceTreeDigest: checkout.treeDigest,
    };
    const writes: SnapshotFile[] = built.files.filter(
      (file: SnapshotFile): boolean => file.path !== 'project.md').concat([
        {
          path: 'manifest.yaml', content: serializeNovelWorkspaceManifest({
            format: NOVEL_WORKSPACE_FORMAT,
            version: NOVEL_WORKSPACE_VERSION,
            projectId: target.id,
            title: target.name,
            activeBranch: checkout.branchId,
            createdAt: target.createdAt,
            updatedAt: target.updatedAt,
          })
        },
        { path: STATE_FILE, content: JSON.stringify(targetState) },
        { path: CHECKOUT_FILE, content: JSON.stringify(checkout) },
        { path: branchSnapshotPath(checkout.branchId), content: JSON.stringify(durableTarget) },
      ]);
    for (let i: number = 0; i < writes.length; i++) {
      await fileStore.writeText(joinPath(stage, writes[i].path), writes[i].content);
    }
    if (await fileStore.exists(backup)) await fileStore.rename(backup, joinPath(ROOT, '.retained', id, novelId()));
    await fileStore.rename(live, backup);
    try {
      await fileStore.rename(stage, live);
    } catch (error) {
      if (!await fileStore.exists(live) && await fileStore.exists(backup)) {
        await fileStore.rename(backup, live);
      }
      throw error;
    }
    await fileStore.deleteTree(backup);
  };

  const installSnapshotResult = async (request: WorkspaceInstallRequest): Promise<WorkspaceInstallResult> => {
    const { project: input, mutation, importedFiles = [], branchId = null, receipt = null,
      unresolvedFromOrdinal = null, plotStale = false, makeUndo = false, changedChapterOrdinal = null,
      preservePriorBranch = false, clearUndo = false, plotSyncedOverride, plotContentOverride,
      proposalStoreOverride = null, restoredFromHead } = request;
    let novelJobStoreOverride: WorkspaceNovelJobStore | null = null;
    const normalized: NovelProject = normalizeProject(validateProject(input));
    const activeBranchId: string = branchId ?? mainBranch(normalized).id;
    let built = buildSnapshotFiles(normalized, activeBranchId);
    const project: NovelProject = built.project;
    const live: string = projectRoot(project.id);
    const stage: string = stagingRoot(project.id);
    const backup: string = trashRoot(project.id);
    await recoverProject(project.id);
    const liveExists: boolean = await fileStore.exists(live);
    const priorLedgerRaw: string = liveExists
      ? (await fileStore.readText(joinPath(live, LEDGER_FILE)) ?? '') : '';
    const priorCommits: WorkspaceLedgerCommit[] = liveExists ? parseLedger(priorLedgerRaw) : [];
    const priorCheckout: WorkspaceCheckout | null = liveExists
      ? await readCheckout(fileStore, live)
      : null;
    let priorActiveSnapshot: WorkspaceBranchSnapshot | null = null;
    if (liveExists && priorCheckout !== null) {
      const priorBranchRaw: string | null = await fileStore.readText(
        joinPath(live, branchSnapshotPath(priorCheckout.branchId)));
      if (priorBranchRaw !== null) {
        priorActiveSnapshot = JSON.parse(priorBranchRaw) as WorkspaceBranchSnapshot;
      }
    }
    const plotContent: string | null = plotContentOverride !== undefined
      ? plotContentOverride
      : priorActiveSnapshot === null ? null : priorActiveSnapshot.plotContent;
    built = buildSnapshotFiles(normalized, activeBranchId, plotContent);
    let preservedPublic: NovelWorkspaceArchiveFile[] = liveExists
      ? await passthroughFilesAt(fileStore, live)
      : importedFiles;
    if (plotContentOverride !== undefined) {
      const plotPath: string = `branches/${activeBranchId}/plan/plot.md`;
      preservedPublic = preservedPublic.filter(
        (file: NovelWorkspaceArchiveFile): boolean => file.path !== plotPath);
    }
    const allPrior: NovelWorkspaceArchiveFile[] = liveExists
      ? await walkWorkspaceFiles(fileStore, live) : [];
    const sharedWrites: SnapshotFile[] = [];
    const replacedSharedPaths: Set<string> = new Set();
    const baseChanged: boolean = priorActiveSnapshot !== null && normalized.baseMaterials !== undefined &&
      JSON.stringify(priorActiveSnapshot.state.project.baseMaterials) !== JSON.stringify(normalized.baseMaterials);
    if (baseChanged && priorCheckout !== null) {
      // Every affected branch will receive a new head. Validate the existing complete
      // canonical tree first, so an unrelated damaged branch cannot become a new commit.
      await validateFullTree(allPrior);
      const jobs: WorkspaceNovelJobStore = await readNovelJobStore(fileStore, projectRoot(project.id));
      const original: NovelProject = await projectFromStoredState(fileStore, live, priorActiveSnapshot!.state);
      const originalBase: NovelMaterial[] = original.baseMaterials ?? original.materials;
      for (const branch of normalized.branches.filter(item => item.lifecycle === 'active')) {
        assertNoActiveNovelJob(jobs, branch.id);
        if (branch.id === activeBranchId) continue;
        const previous: WorkspaceBranchSnapshot = await readActiveBranchSnapshot(fileStore, projectRoot(project.id), branch.id);
        const branchProject: NovelProject = await projectFromStoredState(fileStore, live, previous.state);
        const previousHead: WorkspaceLedgerCommit | null = latestBranchCommit(priorCommits, branch.id);
        if (previousHead === null) throw invalidInput('分支 head 缺失');
        const next: NovelProject = withUpdatedSharedMaterials(branchProject, originalBase, normalized.baseMaterials!);
        const branchBuild = buildSnapshotFiles({
          ...next, branches: normalized.branches,
          name: normalized.name, revision: branchProject.revision + 1, updatedAt: normalized.updatedAt
        }, branch.id, previous.plotContent);
        const digest: string = snapshotTreeDigest(branchBuild.files);
        const branchHead: string = `c${priorCommits.length}-${digest}`;
        const synced: string | null = previous.plotStale ? previous.plotSyncedTreeDigest : digest;
        const branchState: WorkspaceBranchSnapshot = {
          ...previous, state: branchBuild.state,
          plotSourceTreeDigest: digest, plotSyncedTreeDigest: synced, plotStale: synced !== digest
        };
        const commitSnapshot: WorkspaceCommitSnapshot = {
          project: branchBuild.project,
          unresolvedFromOrdinal: previous.unresolvedFromOrdinal, plotStale: branchState.plotStale,
          plotSourceTreeDigest: digest, plotSyncedTreeDigest: synced, plotContent: previous.plotContent
        };
        priorCommits.push({
          version: 1, commitId: branchHead, parent: previousHead.commitId,
          branchId: branch.id, treeDigest: digest, mutation: 'material_base_change',
          receipt: `shared:${receipt ?? mutation}:${branch.id}:${priorCommits.length}`,
          changedPaths: [`branches/${branch.id}/plan/state.md`], changedChapterOrdinal: null, createdAt: normalized.updatedAt
        });
        buildSnapshotFiles(branchProject, branch.id, previous.plotContent).files.forEach(file => {
          if (file.path.startsWith(`branches/${branch.id}/`) || file.path.startsWith('setting/')) replacedSharedPaths.add(file.path);
        });
        const writes: SnapshotFile[] = branchBuild.files.filter(file => file.path.startsWith(`branches/${branch.id}/`))
          .concat([{ path: branchSnapshotPath(branch.id), content: JSON.stringify(branchState) },
          { path: commitSnapshotPath(branchHead), content: JSON.stringify(commitSnapshot) }]);
        writes.forEach(file => { replacedSharedPaths.add(file.path); sharedWrites.push(file); });
      }
    }
    if (preservePriorBranch && priorCheckout !== null) {
      const prefix: string = `branches/${priorCheckout.branchId}/`;
      const priorBranchFiles: NovelWorkspaceArchiveFile[] = allPrior.filter(
        (file: NovelWorkspaceArchiveFile): boolean => file.path.startsWith(prefix),
      );
      preservedPublic = preservedPublic.concat(priorBranchFiles);
    }
    const preservedPrivate: NovelWorkspaceArchiveFile[] = allPrior.filter(
      (file: NovelWorkspaceArchiveFile): boolean => file.path.startsWith('.amber/') &&
        file.path !== STATE_FILE && file.path !== LEDGER_FILE && file.path !== CHECKOUT_FILE &&
        file.path !== branchSnapshotPath(activeBranchId) &&
        !(proposalStoreOverride !== null && file.path === PROPOSALS_FILE) &&
        !((novelJobStoreOverride !== null || request.jobsForCheckout !== undefined) && file.path === GHOSTWRITE_JOBS_FILE) &&
        !((clearUndo || makeUndo) && file.path === undoPath(activeBranchId)),
    );
    const preserved: NovelWorkspaceArchiveFile[] = preservedPublic.concat(preservedPrivate)
      .filter(file => !replacedSharedPaths.has(file.path));
    await fileStore.deleteTree(stage);
    let preservedProjectMd: NovelWorkspaceArchiveFile | null = null;
    for (let i: number = 0; i < preserved.length; i++) {
      if (preserved[i].path === 'project.md') preservedProjectMd = preserved[i];
    }
    // project.md 属于用户可维护的公开工作区文件。仅新建项目时生成标题占位；
    // 导入及后续业务状态更新都必须逐字节保留现有内容。
    const generatedFiles: SnapshotFile[] = preservedProjectMd === null
      ? built.files
      : built.files.filter((file: SnapshotFile): boolean => file.path !== 'project.md');
    const generatedPaths: Set<string> = new Set<string>();
    generatedFiles.forEach((file: SnapshotFile): void => { generatedPaths.add(file.path); });
    const passthrough: NovelWorkspaceArchiveFile[] = preserved.filter(
      (file: NovelWorkspaceArchiveFile): boolean => !generatedPaths.has(file.path),
    );
    const treeDigest: string = snapshotTreeDigest(generatedFiles);
    const head: string = `c${priorCommits.length}-${treeDigest}`;
    const commandReceipt: string = receipt ?? `${mutation}:${project.id}:${priorCommits.length}`;
    const commit: WorkspaceLedgerCommit = {
      version: 1,
      commitId: head,
      parent: priorCheckout === null ? null : priorCheckout.head,
      branchId: activeBranchId,
      treeDigest,
      mutation,
      receipt: commandReceipt,
      changedPaths: generatedFiles.filter((file: SnapshotFile): boolean =>
        !file.path.startsWith('.amber/') && file.path !== 'manifest.yaml' && file.path !== 'project.md')
        .map((file: SnapshotFile): string => file.path),
      changedChapterOrdinal,
      createdAt: project.updatedAt,
    };
    if (restoredFromHead !== undefined) commit.restoredFromHead = restoredFromHead;
    if (commit.changedPaths.length === 0) {
      commit.changedPaths = [`branches/${activeBranchId}/plan/state.md`];
    }
    const commits: WorkspaceLedgerCommit[] = priorCommits.concat([commit]);
    const checkout: WorkspaceCheckout = {
      branchId: activeBranchId,
      head,
      treeDigest,
    };
    // Job progress/frozen plans must reference this exact commit, including shared-branch ledger growth.
    // The synchronous attachment is installed with body, versions, ledger and checkout in the same swap.
    if (request.jobsForCheckout !== undefined) novelJobStoreOverride = request.jobsForCheckout(checkout);
    const plotSyncedTreeDigest: string | null = plotSyncedOverride !== undefined
      ? plotSyncedOverride
      : plotStale
        ? priorActiveSnapshot?.plotSyncedTreeDigest === treeDigest
          ? null : priorActiveSnapshot?.plotSyncedTreeDigest ?? null
        : treeDigest;
    const branchSnapshot: WorkspaceBranchSnapshot = {
      branchId: activeBranchId,
      state: built.state,
      unresolvedFromOrdinal,
      plotStale: plotSyncedTreeDigest !== treeDigest,
      plotSourceTreeDigest: treeDigest,
      plotSyncedTreeDigest,
      plotContent,
    };
    const commitSnapshot: WorkspaceCommitSnapshot = {
      project,
      unresolvedFromOrdinal,
      plotStale: branchSnapshot.plotStale,
      plotSourceTreeDigest: treeDigest,
      plotSyncedTreeDigest: branchSnapshot.plotSyncedTreeDigest,
      plotContent,
    };
    const files: SnapshotFile[] = generatedFiles.concat(sharedWrites).concat([
      {
        path: LEDGER_FILE, content: commits.map(
          (item: WorkspaceLedgerCommit): string => JSON.stringify(item)).join('\n') + '\n'
      },
      { path: CHECKOUT_FILE, content: JSON.stringify(checkout) },
      { path: branchSnapshotPath(activeBranchId), content: JSON.stringify(branchSnapshot) },
      { path: commitSnapshotPath(head), content: JSON.stringify(commitSnapshot) },
    ]);
    if (makeUndo && liveExists && priorCheckout !== null) {
      const priorProject: NovelProject = await loadAt(fileStore, live);
      if (priorActiveSnapshot === null) throw invalidInput('active branch state 缺失');
      const priorBranch: WorkspaceBranchSnapshot = priorActiveSnapshot;
      const undo: WorkspaceUndoRecord = {
        branchId: priorCheckout.branchId,
        snapshot: {
          project: priorProject,
          unresolvedFromOrdinal: priorBranch.unresolvedFromOrdinal,
          plotStale: priorBranch.plotStale,
          plotSourceTreeDigest: priorBranch.plotSourceTreeDigest,
          plotSyncedTreeDigest: priorBranch.plotSyncedTreeDigest,
          plotContent: priorBranch.plotContent,
        },
      };
      files.push({ path: undoPath(activeBranchId), content: JSON.stringify(undo) });
    }
    if (proposalStoreOverride !== null) {
      files.push({ path: PROPOSALS_FILE, content: JSON.stringify(proposalStoreOverride) });
    }
    if (novelJobStoreOverride !== null) {
      files.push({ path: GHOSTWRITE_JOBS_FILE, content: JSON.stringify(novelJobStoreOverride) });
    }
    for (let i: number = 0; i < files.length; i++) {
      await fileStore.writeText(joinPath(stage, files[i].path), files[i].content);
    }
    for (let i: number = 0; i < passthrough.length; i++) {
      await fileStore.writeBytes(joinPath(stage, passthrough[i].path), passthrough[i].bytes);
    }
    if (!liveExists) {
      await fileStore.rename(stage, live);
      return { project: withAuthorPlot(project, plotContent), checkout, jobs: novelJobStoreOverride };
    }
    if (await fileStore.exists(backup)) await fileStore.rename(backup, joinPath(ROOT, '.retained', project.id, novelId()));
    await fileStore.rename(live, backup);
    try {
      await fileStore.rename(stage, live);
    } catch (error) {
      if (!await fileStore.exists(live) && await fileStore.exists(backup)) {
        await fileStore.rename(backup, live);
      }
      throw error;
    }
    await fileStore.deleteTree(backup);
    if (request.jobsForCheckout !== undefined) {
      const installed: WorkspaceCheckout | null = await readCheckout(fileStore, live);
      if (installed === null || installed.head !== checkout.head || installed.treeDigest !== checkout.treeDigest) {
        throw invalidInput('小说作业原子提交 checkout 校验失败');
      }
    }
    return { project: withAuthorPlot(project, plotContent), checkout, jobs: novelJobStoreOverride };
  };

  const installSnapshot = async (request: WorkspaceCommitRequest): Promise<NovelProject> =>
    (await installSnapshotResult(request)).project;

  const migrateLegacyIfPresent = async (id: string): Promise<NovelProject | null> => {
    const raw: string | null = await fileStore.readText(legacyPath(id));
    if (raw === null) return null;
    const migrated: NovelProject = await installSnapshot({ project: decodeLegacy(raw), mutation: 'legacy_migration' });
    await fileStore.delete(legacyPath(id));
    return migrated;
  };

  const loadProjectInternal = async (id: string): Promise<NovelProject> => {
    await recoverProject(id);
    if (await fileStore.exists(projectRoot(id))) {
      const root: string = projectRoot(id);
      const project: NovelProject = await loadAt(fileStore, root);
      if (!await workspaceNeedsSchemaMigration(fileStore, root)) return project;
      const checkout: WorkspaceCheckout | null = await readCheckout(fileStore, root);
      if (checkout === null) throw invalidInput('迁移前 checkout 无效');
      const branch: WorkspaceBranchSnapshot = await readActiveBranchSnapshot(fileStore, projectRoot(id), checkout.branchId);
      const migrated: NovelProject = projectChapterPlots(project, project, branch);
      return await installSnapshot({ project: migrated, mutation: 'schema_migration', importedFiles: [], branchId: checkout.branchId, receipt: null, unresolvedFromOrdinal: branch.unresolvedFromOrdinal, plotStale: branch.plotStale, makeUndo: false, changedChapterOrdinal: null, preservePriorBranch: false, clearUndo: false, plotSyncedOverride: branch.plotSyncedTreeDigest, plotContentOverride: branch.plotContent });
    }
    const migrated: NovelProject | null = await migrateLegacyIfPresent(id);
    if (migrated !== null) return migrated;
    throw notFound('project', id);
  };

  const replayProject = async (
    id: string, commits: WorkspaceLedgerCommit[], commandId: string,
  ): Promise<NovelProject | null> => {
    const replay: WorkspaceLedgerCommit | null = findReceipt(commits, commandId);
    if (replay === null) return null;
    const raw: string | null = await fileStore.readText(
      joinPath(projectRoot(id), commitSnapshotPath(replay.commitId)));
    if (raw === null) throw invalidInput('receipt snapshot 缺失');
    try {
      const snapshot: WorkspaceCommitSnapshot = JSON.parse(raw) as WorkspaceCommitSnapshot;
      return withAuthorPlot(validateProject(snapshot.project), snapshot.plotContent);
    } catch {
      throw invalidInput('receipt snapshot 无效');
    }
  };

  const locationRoot = (id: string, location: WorkspaceLocation): string =>
    location === 'live' ? projectRoot(id) : location === 'trash' ? trashRoot(id) : deletedRoot(id);

  const session = (id: string): WorkspaceSession => ({
    recover: () => recoverProject(id),
    loadProject: () => loadProjectInternal(id),
    checkout: () => readCheckout(fileStore, projectRoot(id)),
    branch: (branchId: string) => readActiveBranchSnapshot(fileStore, projectRoot(id), branchId),
    historicalSnapshot: (commit: WorkspaceCommit) => readHistoricalSnapshot(fileStore, projectRoot(id), id, commit),
    projectFromState: (state: WorkspaceProjectState) => projectFromStoredState(fileStore, projectRoot(id), state),
    commits: () => readCommits(fileStore, projectRoot(id), id),
    jobs: () => readNovelJobStore(fileStore, projectRoot(id)),
    proposals: () => readProposalStore(fileStore, projectRoot(id)),
    replay: (commits: WorkspaceLedgerCommit[], commandId: string) => replayProject(id, commits, commandId),
    commit: (request: WorkspaceCommitRequest) => installSnapshot(request),
    commitJob: async (request: WorkspaceCommitRequest, jobsForCheckout: (checkout: NovelWorkspaceCas) => WorkspaceNovelJobStore) => {
      const result: WorkspaceInstallResult = await installSnapshotResult({ ...request, jobsForCheckout });
      if (result.jobs === null) throw invalidInput('小说作业原子提交缺少 jobs');
      return result.jobs;
    },
    saveJobs: (jobs: WorkspaceNovelJobStore) => installPrivateStoreOnly(id, GHOSTWRITE_JOBS_FILE, JSON.stringify(jobs)),
    saveProposals: (proposals: WorkspaceProposalStore) => installPrivateStoreOnly(id, PROPOSALS_FILE, JSON.stringify(proposals)),
    materializeCheckout: (current: WorkspaceProjectState, target: NovelProject, snapshot: WorkspaceBranchSnapshot, checkout: WorkspaceCheckout) =>
      materializeCheckout(id, current, target, snapshot, checkout),
    files: (location: WorkspaceLocation) => walkWorkspaceFiles(fileStore, locationRoot(id, location)),
    exists: (location: WorkspaceLocation) => fileStore.exists(locationRoot(id, location)),
    isLiveDirectory: () => fileStore.isDirectory(projectRoot(id)),
    readText: (path: string) => fileStore.readText(joinPath(projectRoot(id), path)),
    readBytes: (path: string) => fileStore.readBytes(joinPath(projectRoot(id), path)),
    replaceTree: async (files: NovelWorkspaceArchiveFile[], policy: WorkspaceReplacementPolicy) => {
      const stage: string = stagingRoot(id);
      await writeWorkspaceStorageFiles(fileStore, stage, files);
      if (policy.validateStaged) await loadAt(fileStore, stage);
      await swapWorkspaceStorageStage(fileStore, stage, projectRoot(id), trashRoot(id), joinPath(ROOT, '.retained', id, novelId()));
      if (policy.retainOtherRoots) {
        for (const root of [trashRoot(id), deletedRoot(id)]) {
          if (await fileStore.exists(root)) await fileStore.rename(root, joinPath(ROOT, '.retained', id, novelId()));
        }
      }
      return await loadAt(fileStore, projectRoot(id));
    },
    deleteProject: async () => {
      await recoverProject(id);
      const live: string = projectRoot(id);
      if (!await fileStore.exists(live)) return;
      if (await fileStore.exists(deletedRoot(id))) await fileStore.rename(deletedRoot(id), joinPath(ROOT, '.retained', id, novelId()));
      await fileStore.writeText(joinPath(live, '.amber/deleted-at.json'), JSON.stringify({ deletedAt: Date.now() }));
      await fileStore.rename(live, deletedRoot(id));
    },
    restoreDeleted: async (deletedId: string) => {
      if (await fileStore.exists(projectRoot(id))) throw invalidInput('同 id 项目已存在');
      if (!await fileStore.exists(deletedRoot(deletedId))) throw invalidInput('待恢复项目已不存在');
      await fileStore.rename(deletedRoot(deletedId), projectRoot(id));
      await fileStore.delete(joinPath(projectRoot(id), '.amber/deleted-at.json'));
    },
    readLegacyDeleted: (name: string) => fileStore.readText(joinPath(LEGACY_DELETED_DIR, name)),
    removeLegacyDeleted: (name: string) => fileStore.delete(joinPath(LEGACY_DELETED_DIR, name)),
    migrateLegacy: () => migrateLegacyIfPresent(id),
  });
  return {
    run: <T>(id: string, op: (view: WorkspaceSession) => Promise<T>): Promise<T> => withProjectLock(id, () => op(session(id))),
    list: (directory: WorkspaceDirectory): Promise<string[]> => fileStore.list(directory === 'live' ? ROOT : directory === 'trash' ? TRASH_ROOT :
      directory === 'stage' ? STAGING_ROOT : directory === 'deleted' ? DELETED_ROOT : directory === 'legacy' ? LEGACY_PROJECTS_DIR : LEGACY_DELETED_DIR),
    deletedProject: async (id: string) => {
      const project: NovelProject = await loadAt(fileStore, deletedRoot(id));
      const raw: string | null = await fileStore.readText(joinPath(deletedRoot(id), '.amber/deleted-at.json'));
      const deletion: { deletedAt: number } | null = raw === null ? null : JSON.parse(raw) as { deletedAt: number };
      return { project, deletedAt: deletion === null || !Number.isSafeInteger(deletion.deletedAt) ? 0 : deletion.deletedAt };
    },
    readLegacyDeleted: (name: string) => fileStore.readText(joinPath(LEGACY_DELETED_DIR, name)),
    readManifest: (id: string) => fileStore.readText(joinPath(projectRoot(id), 'manifest.yaml')),
    tryWithPolishDriverLock: <T>(id: string, jobId: string, op: () => Promise<T>): Promise<T | null> =>
      fileStore.tryWithExclusiveLock(joinPath(LOCK_ROOT, `${id}.polish.${jobId}.lock`), op),
    withPolishDriverLock: <T>(id: string, jobId: string, op: () => Promise<T>): Promise<T> =>
      fileStore.withExclusiveLock(joinPath(LOCK_ROOT, `${id}.polish.${jobId}.lock`), op),
  };

};
