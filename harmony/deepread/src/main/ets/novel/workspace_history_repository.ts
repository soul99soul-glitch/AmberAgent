import { forkNovelBranchPlans } from './chapter_contract.ts';
import { invalidInput } from './error.ts';
import {
  mutateNovelBranchMetadata, previousWorkspaceCheckpoint, reachableWorkspaceHistory,
  selectWorkspaceCheckpoint,
} from './history_selection.ts';
import { initializeSharedMaterials, withBranchMaterialEdits } from './material_inheritance.ts';
import type { NovelBranch, NovelProject } from './models.ts';
import { novelId } from './models.ts';
import type { NovelProjectRepository, NovelWorkspaceCas, NovelWorkspaceSnapshot } from './repository_contract.ts';
import type {
  WorkspaceBranchSnapshot, WorkspaceCheckout, WorkspaceCommitSnapshot, WorkspaceLedgerCommit,
  WorkspaceProjectState,
} from './workspace_codec.ts';
import {
  assertCas, assertCommandId, assertNoActiveNovelJob, buildSnapshotFiles, commitSnapshotPath,
  latestBranchCommit, snapshotTreeDigest, validateProject, withAuthorPlot,
} from './workspace_codec.ts';
import type { WorkspaceCommit } from './workspace_history.ts';
import { historyMutationContext, readWorkspaceSnapshotLocked } from './workspace_project_repository.ts';
import type { WorkspaceScope, WorkspaceSession } from './workspace_transaction.ts';

type HistorySession = Pick<WorkspaceSession,
  'branch' | 'checkout' | 'commit' | 'commits' | 'jobs' | 'loadProject' | 'materializeCheckout' | 'projectFromState' | 'readText' | 'replay' | 'proposals'>;
type HistoryStorage = WorkspaceScope<HistorySession>;

const restoreHistoryLocked = async (
  tx: HistorySession, id: string, head: string | null, forkName: string | null, expected: NovelWorkspaceCas, commandId: string,
): Promise<NovelProject> => {
  const context = await historyMutationContext(tx, id, expected, commandId);
  if (context.replay !== null) return context.replay;
  const target: WorkspaceCommit | null = head === null
    ? previousWorkspaceCheckpoint(context.commits, context.checkout.head, context.checkout.branchId)
    : selectWorkspaceCheckpoint(context.commits, context.checkout.head, context.checkout.branchId, head);
  if (target === null) throw invalidInput('没有可撤销的提交');
  if (forkName === null && target.commitId === context.checkout.head) throw invalidInput('已经位于该检查点');
  const raw: string | null = await tx.readText(commitSnapshotPath(target.commitId));
  if (raw === null) throw invalidInput('检查点快照缺失');
  const snapshot: WorkspaceCommitSnapshot = JSON.parse(raw) as WorkspaceCommitSnapshot;
  const prior: NovelProject = validateProject(snapshot.project);
  if (prior.id !== id || snapshotTreeDigest(buildSnapshotFiles(prior, target.branchId, snapshot.plotContent).files) !== target.treeDigest) {
    throw invalidInput('检查点快照与台账不一致');
  }
  let branches: NovelBranch[] = context.current.branches;
  let branchId: string = context.checkout.branchId;
  if (forkName !== null) {
    const clean: string = forkName.trim();
    if (clean.length === 0) throw invalidInput('分支名不能为空');
    branchId = novelId();
    branches = branches.concat([{
      id: branchId, name: clean, lifecycle: 'active', isMain: false,
      forkFromChapterId: prior.chapters.length === 0 ? null : prior.chapters[prior.chapters.length - 1].id,
      createdAt: Date.now()
    }]);
  }
  let selected: NovelProject = prior;
  if (context.current.baseMaterials !== undefined) {
    selected = forkName !== null
      ? withBranchMaterialEdits({ ...prior, baseMaterials: context.current.baseMaterials }, prior.materials)
      : prior.baseMaterials === undefined ? initializeSharedMaterials(prior) : prior;
  }
  const restored: NovelProject = {
    ...selected, name: context.current.name, modelPolicy: context.current.modelPolicy,
    creationMode: context.current.creationMode, quickStartSeed: context.current.quickStartSeed,
    polishPreference: context.current.polishPreference, branches,
    branchSettings: forkName === null ? selected.branchSettings : forkNovelBranchPlans(selected.branchSettings, branchId),
    revision: context.current.revision + 1, updatedAt: Date.now()
  };
  return await tx.commit({
    project: restored,
    mutation: forkName === null ? 'undo' : 'history_fork',
    importedFiles: [],
    branchId: branchId,
    receipt: commandId,
    unresolvedFromOrdinal: snapshot.unresolvedFromOrdinal,
    plotStale: snapshot.plotStale,
    makeUndo: false,
    changedChapterOrdinal: null,
    preservePriorBranch: forkName !== null,
    clearUndo: true,
    plotSyncedOverride: // Fork paths produce a new digest. Synced plot status is relative to that new tree.
      forkName === null ? snapshot.plotSyncedTreeDigest : undefined,
    plotContentOverride: snapshot.plotContent,
    proposalStoreOverride: null,
    restoredFromHead: forkName === null ? target.commitId : undefined
  });
};

const branchMetadataLocked = async (
  tx: HistorySession, id: string, branchId: string, operation: 'rename' | 'main' | 'delete', name: string,
  expected: NovelWorkspaceCas, commandId: string,
): Promise<NovelProject> => {
  const context = await historyMutationContext(tx, id, expected, commandId);
  if (context.replay !== null) return context.replay;
  if (operation === 'delete') assertNoActiveNovelJob(await tx.jobs(), branchId);
  const branches: NovelBranch[] = mutateNovelBranchMetadata(context.current.branches,
    context.checkout.branchId, branchId, operation, name);
  const state: WorkspaceBranchSnapshot = await tx.branch(context.checkout.branchId);
  return await tx.commit({
    project: {
      ...context.current, branches, revision: context.current.revision + 1,
      updatedAt: Date.now()
    },
    mutation: `branch_${operation}`,
    importedFiles: [],
    branchId: context.checkout.branchId,
    receipt: commandId,
    unresolvedFromOrdinal: state.unresolvedFromOrdinal,
    plotStale: state.plotStale,
    makeUndo: false,
    changedChapterOrdinal: null,
    preservePriorBranch: false,
    clearUndo: false,
    plotSyncedOverride: state.plotSyncedTreeDigest,
    plotContentOverride: state.plotContent
  });
};

export const createWorkspaceHistoryRepository = (storage: HistoryStorage): Pick<NovelProjectRepository,
  'createBranch' | 'switchBranch' | 'workspaceHistory' | 'chapterVersionCheckpoint' | 'undo'
  | 'undoToCheckpoint' | 'forkFromHistory' | 'renameBranch' | 'setMainBranch' | 'deleteBranch'> => {
  const repository = {
    createBranch: (
      id: string, name: string, expected: NovelWorkspaceCas, commandId: string,
    ): Promise<NovelProject> => storage.run(id, async (tx): Promise<NovelProject> => {
      assertCommandId(commandId);
      const commits: WorkspaceLedgerCommit[] = await tx.commits();
      const replay: NovelProject | null = await tx.replay(commits, commandId);
      if (replay !== null) return replay;
      const current: NovelProject = await tx.loadProject();
      const checkout: WorkspaceCheckout | null = await tx.checkout();
      if (checkout === null) throw invalidInput('checkout 无效');
      assertCas(expected, checkout);
      assertNoActiveNovelJob(await tx.jobs(), checkout.branchId);
      const clean: string = name.trim();
      if (clean.length === 0) throw invalidInput('分支名不能为空');
      const currentState: WorkspaceBranchSnapshot = await tx.branch(checkout.branchId);
      const branch: NovelBranch = {
        id: novelId(), name: clean, lifecycle: 'active', isMain: false,
        forkFromChapterId: current.chapters.length === 0
          ? null : current.chapters[current.chapters.length - 1].id,
        createdAt: Date.now(),
      };
      const fork: NovelProject = {
        ...current,
        branches: current.branches.concat([branch]),
        branchSettings: forkNovelBranchPlans(current.branchSettings, branch.id),
        revision: current.revision + 1,
        updatedAt: Date.now(),
      };
      return await tx.commit({
        project: fork,
        mutation: 'create_branch',
        importedFiles: [],
        branchId: branch.id,
        receipt: commandId,
        unresolvedFromOrdinal: currentState.unresolvedFromOrdinal,
        plotStale: currentState.plotStale,
        makeUndo: false,
        changedChapterOrdinal: null,
        preservePriorBranch: true
      });
    }),

    switchBranch: (
      id: string, targetBranchId: string, expected: NovelWorkspaceCas,
    ): Promise<NovelProject> => storage.run(id, async (tx): Promise<NovelProject> => {
      const current: NovelProject = await tx.loadProject();
      const checkout: WorkspaceCheckout | null = await tx.checkout();
      if (checkout === null) throw invalidInput('checkout 无效');
      assertCas(expected, checkout);
      if (targetBranchId === checkout.branchId) return current;
      assertNoActiveNovelJob(await tx.jobs(), checkout.branchId);
      const targetBranch: NovelBranch | undefined = current.branches.find(
        (branch: NovelBranch): boolean => branch.id === targetBranchId && branch.lifecycle === 'active');
      if (targetBranch === undefined) throw invalidInput(`分支不存在: ${targetBranchId}`);
      const targetSnapshot: WorkspaceBranchSnapshot = await tx.branch(targetBranchId);
      const targetStored: NovelProject = await tx.projectFromState(targetSnapshot.state);
      const target: NovelProject = {
        ...targetStored,
        name: current.name,
        modelPolicy: current.modelPolicy,
        creationMode: current.creationMode,
        quickStartSeed: current.quickStartSeed,
        polishPreference: current.polishPreference,
        baseMaterials: current.baseMaterials ?? targetStored.baseMaterials,
        lastGenerationGranularity: current.lastGenerationGranularity,
        branches: current.branches,
        updatedAt: current.updatedAt,
      };
      const commits: WorkspaceLedgerCommit[] = await tx.commits();
      const targetHead: WorkspaceLedgerCommit | null = latestBranchCommit(commits, targetBranchId);
      if (targetHead === null || snapshotTreeDigest(
        buildSnapshotFiles(target, targetBranchId, targetSnapshot.plotContent).files) !==
        targetHead.treeDigest) {
        throw invalidInput('目标分支 head/tree 不一致');
      }
      const targetCheckout: WorkspaceCheckout = {
        branchId: targetBranchId,
        head: targetHead.commitId,
        treeDigest: targetHead.treeDigest,
      };
      const currentState: WorkspaceProjectState = buildSnapshotFiles(current, checkout.branchId).state;
      await tx.materializeCheckout(currentState, target, targetSnapshot, targetCheckout);
      return withAuthorPlot(target, targetSnapshot.plotContent);
    }),

    workspaceHistory: (id: string): Promise<WorkspaceCommit[]> => storage.run(id, async (tx) => {
      const snapshot: NovelWorkspaceSnapshot = await readWorkspaceSnapshotLocked(tx, id);
      return reachableWorkspaceHistory(await tx.commits(), snapshot.status.cas.head, snapshot.status.activeBranchId);
    }),

    chapterVersionCheckpoint: (
      id: string, versionId: string, expected?: NovelWorkspaceCas,
    ): Promise<WorkspaceCommit | null> => storage.run(id, async (tx): Promise<WorkspaceCommit | null> => {
      const current: NovelWorkspaceSnapshot = await readWorkspaceSnapshotLocked(tx, id);
      if (expected !== undefined) assertCas(expected, current.status.cas);
      const version = current.project.chapterVersions.find(item => item.id === versionId);
      if (version === undefined) return null;
      const history: WorkspaceCommit[] = reachableWorkspaceHistory(await tx.commits(),
        current.status.cas.head, current.status.activeBranchId);
      for (const commit of history) {
        const raw: string | null = await tx.readText(commitSnapshotPath(commit.commitId));
        if (raw === null) throw invalidInput('检查点快照缺失');
        const snapshot: WorkspaceCommitSnapshot = JSON.parse(raw) as WorkspaceCommitSnapshot;
        const project: NovelProject = validateProject(snapshot.project);
        if (project.id !== id || snapshotTreeDigest(buildSnapshotFiles(project, commit.branchId, snapshot.plotContent).files) !== commit.treeDigest) {
          throw invalidInput('检查点快照与台账不一致');
        }
        if (project.chapters.some(chapter => chapter.id === version.chapterId &&
          chapter.title === version.title && chapter.content === version.content)) return commit;
      }
      return null;
    }),

    undo: (id: string, expected: NovelWorkspaceCas, commandId: string): Promise<NovelProject> =>
      storage.run(id, async (tx) => await restoreHistoryLocked(tx, id, null, null, expected, commandId)),

    undoToCheckpoint: (id: string, head: string, expected: NovelWorkspaceCas, commandId: string): Promise<NovelProject> =>
      storage.run(id, async (tx) => await restoreHistoryLocked(tx, id, head, null, expected, commandId)),

    forkFromHistory: (id: string, head: string, name: string, expected: NovelWorkspaceCas, commandId: string): Promise<NovelProject> =>
      storage.run(id, async (tx) => await restoreHistoryLocked(tx, id, head, name, expected, commandId)),

    renameBranch: (id: string, branchId: string, name: string, expected: NovelWorkspaceCas, commandId: string): Promise<NovelProject> =>
      storage.run(id, async (tx) => await branchMetadataLocked(tx, id, branchId, 'rename', name, expected, commandId)),

    setMainBranch: (id: string, branchId: string, expected: NovelWorkspaceCas, commandId: string): Promise<NovelProject> =>
      storage.run(id, async (tx) => await branchMetadataLocked(tx, id, branchId, 'main', '', expected, commandId)),

    deleteBranch: (id: string, branchId: string, expected: NovelWorkspaceCas, commandId: string): Promise<NovelProject> =>
      storage.run(id, async (tx) => await branchMetadataLocked(tx, id, branchId, 'delete', '', expected, commandId))
  };
  return repository;
};
