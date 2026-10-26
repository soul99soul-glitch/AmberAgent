import { reconcileNovelBranchPlans } from './chapter_contract.ts';
import { invalidInput, notFound } from './error.ts';
import type { DurableGhostwriteJob } from './ghostwrite.ts';
import { previousWorkspaceCheckpoint } from './history_selection.ts';
import { normalizeNovelMaterialFields } from './material_fields.ts';
import {
  initializeSharedMaterials, restoreNovelMaterialInheritance, withBranchMaterialEdits,
  withUpdatedSharedMaterials,
} from './material_inheritance.ts';
import type { NovelBranch, NovelMaterial, NovelProject, NovelSettingProposal } from './models.ts';
import { novelId } from './models.ts';
import { rebuildChapterPlots } from './plot_projection.ts';
import { assertNovelCreationMetadataImmutable } from './project_metadata.ts';
import type {
  NovelProjectRepository, NovelWorkspaceCas, NovelWorkspaceMutationKind, NovelWorkspaceSnapshot,
  NovelWorkspaceStatus,
} from './repository_contract.ts';
import type { NovelStateOperation } from './state_rebuild.ts';
import { assertNovelStateSources } from './state_rebuild.ts';
import type {
  DurableNovelJob, WorkspaceBranchSnapshot, WorkspaceCheckout, WorkspaceLedgerCommit,
  WorkspaceNovelJobStore, WorkspaceProposalStore,
} from './workspace_codec.ts';
import {
  assertCas, assertCommandId, assertNoActiveNovelJob, isGhostwriteJob, novelJobIsTerminal,
} from './workspace_codec.ts';
import type { DurableWorkspaceProposal } from './workspace_history.ts';
import { chapterChangedAt, projectChapterPlots, projectedPlotStatus } from './workspace_projection.ts';
import type { WorkspaceScope, WorkspaceSession } from './workspace_transaction.ts';

type ProjectSession = Pick<WorkspaceSession,
  'branch' | 'checkout' | 'commit' | 'commits' | 'exists' | 'jobs' | 'loadProject' | 'proposals' | 'recover' | 'replay'>;
type ProjectStorage = WorkspaceScope<ProjectSession>;

export const assertChapterPlanEditable = async (tx: Pick<WorkspaceSession, 'jobs'>, id: string, branchId: string): Promise<void> => {
  const jobStore: WorkspaceNovelJobStore = await tx.jobs();
  const bound: DurableGhostwriteJob | undefined = jobStore.jobs.find(
    (job: DurableNovelJob): job is DurableGhostwriteJob => isGhostwriteJob(job) &&
      job.branchId === branchId && !novelJobIsTerminal(job));
  if (bound !== undefined) throw invalidInput('运行中的 ghostwrite job 已冻结本章计划');
};

export const commitLocked = async (
  tx: Pick<WorkspaceSession, 'recover' | 'commits' | 'replay' | 'loadProject' | 'checkout' | 'branch' | 'jobs' | 'commit'>, id: string, expected: NovelWorkspaceCas, commandId: string, kind: NovelWorkspaceMutationKind,
  transform: (p: NovelProject) => NovelProject,
): Promise<NovelProject> => {
  assertCommandId(commandId);
  await tx.recover();
  const commits: WorkspaceLedgerCommit[] = await tx.commits();
  const replay: NovelProject | null = await tx.replay(commits, commandId);
  if (replay !== null) return replay;
  const current: NovelProject = await tx.loadProject();
  const checkout: WorkspaceCheckout | null = await tx.checkout();
  if (checkout === null) throw invalidInput('checkout 无效');
  assertCas(expected, checkout);
  const branch: WorkspaceBranchSnapshot = await tx.branch(checkout.branchId);
  const candidate: NovelProject = transform(current);
  const transformed: NovelProject = withBranchMaterialEdits({
    ...candidate,
    branchSettings: reconcileNovelBranchPlans(current.branchSettings, candidate.branchSettings),
  }, candidate.materials);
  if (transformed.id !== id) throw invalidInput('项目 id 不可变更');
  assertNovelCreationMetadataImmutable(current, transformed);
  if (transformed.branchSettings.thisChapterPlan !== current.branchSettings.thisChapterPlan) {
    await assertChapterPlanEditable(tx, id, checkout.branchId);
  }
  const next: NovelProject = projectChapterPlots(current, { ...transformed, revision: current.revision + 1 }, branch);
  const changedIndex: number | null = chapterChangedAt(current.chapters, next.chapters);
  const { unresolved, stale } = projectedPlotStatus(next);
  return await tx.commit({
    project: next,
    mutation: kind,
    importedFiles: [],
    branchId: checkout.branchId,
    receipt: commandId,
    unresolvedFromOrdinal: unresolved,
    plotStale: stale,
    makeUndo: kind !== 'transcript_checkpoint' && kind !== 'state_operation',
    changedChapterOrdinal: changedIndex === null ? null : changedIndex + 1
  });
};

export const readWorkspaceSnapshotLocked = async (tx: Pick<WorkspaceSession, 'loadProject' | 'checkout' | 'branch' | 'proposals' | 'commits'>, id: string): Promise<NovelWorkspaceSnapshot> => {
  const project: NovelProject = await tx.loadProject();
  const checkout: WorkspaceCheckout | null = await tx.checkout();
  if (checkout === null) throw invalidInput('checkout 无效');
  const branchState: WorkspaceBranchSnapshot = await tx.branch(checkout.branchId);
  const active: NovelBranch | undefined = project.branches.find(
    (branch: NovelBranch): boolean => branch.id === checkout.branchId && branch.lifecycle === 'active');
  if (active === undefined) throw invalidInput('active branch 不存在');
  const proposalStore: WorkspaceProposalStore = await tx.proposals();
  const status: NovelWorkspaceStatus = {
    cas: checkout,
    activeBranchId: active.id,
    activeBranchName: active.name,
    branches: project.branches.filter(
      (branch: NovelBranch): boolean => branch.lifecycle === 'active'),
    canUndo: previousWorkspaceCheckpoint(await tx.commits(), checkout.head, active.id) !== null,
    unresolvedFromOrdinal: branchState.unresolvedFromOrdinal,
    plotStale: branchState.plotSourceTreeDigest !== branchState.plotSyncedTreeDigest,
    pendingProposalCount: project.settingProposals.filter(
      (proposal: NovelSettingProposal): boolean => proposal.status === 'pending').length +
      proposalStore.proposals.filter(
        (proposal: DurableWorkspaceProposal): boolean => proposal.status === 'pending' && proposal.expectedCas.branchId === active.id).length,
  };
  return { project, status };
};

export const historyMutationContext = async (tx: Pick<WorkspaceSession, 'commits' | 'replay' | 'loadProject' | 'checkout' | 'jobs'>, id: string, expected: NovelWorkspaceCas, commandId: string) => {
  assertCommandId(commandId);
  const commits: WorkspaceLedgerCommit[] = await tx.commits();
  const replay: NovelProject | null = await tx.replay(commits, commandId);
  if (replay !== null) return { commits, replay, current: replay, checkout: expected };
  const current: NovelProject = await tx.loadProject();
  const checkout: WorkspaceCheckout | null = await tx.checkout();
  if (checkout === null) throw invalidInput('checkout 无效');
  assertCas(expected, checkout);
  assertNoActiveNovelJob(await tx.jobs(), checkout.branchId);
  return { commits, replay, current, checkout };
};


export const createWorkspaceProjectRepository = (storage: ProjectStorage): Pick<NovelProjectRepository,
  'loadProject' | 'createProject' | 'updateProject' | 'workspaceStatus' | 'readWorkspaceSnapshot'
  | 'commitProject' | 'saveSharedMaterial' | 'deleteSharedMaterial' | 'restoreMaterialInheritance'
  | 'completeStateOperation' | 'syncPlot' | 'resolveUnresolved'> => {
  const repository = {
    async loadProject(id: string): Promise<NovelProject> {
      return await storage.run(id, async (tx): Promise<NovelProject> => await tx.loadProject());
    },

    async createProject(project: NovelProject): Promise<NovelProject> {
      return await storage.run(project.id, async (tx): Promise<NovelProject> => {
        await tx.recover();
        if (await tx.exists('live')) throw invalidInput('项目已存在');
        return await tx.commit({
          project: initializeSharedMaterials({ ...project, chapterPlots: rebuildChapterPlots(project.chapters) }),
          mutation: 'create'
        });
      });
    },

    updateProject: (id: string, transform: (p: NovelProject) => NovelProject): Promise<NovelProject> =>
      storage.run(id, async (tx): Promise<NovelProject> => {
        await tx.loadProject();
        const checkout: WorkspaceCheckout | null = await tx.checkout();
        if (checkout === null) throw invalidInput('checkout 无效');
        return await commitLocked(tx, id, checkout, novelId(), 'compat_update', transform);
      }),

    workspaceStatus: (id: string): Promise<NovelWorkspaceStatus> =>
      storage.run(id, async (tx): Promise<NovelWorkspaceStatus> => (await readWorkspaceSnapshotLocked(tx, id)).status),

    readWorkspaceSnapshot: (id: string): Promise<NovelWorkspaceSnapshot> =>
      storage.run(id, async (tx): Promise<NovelWorkspaceSnapshot> => await readWorkspaceSnapshotLocked(tx, id)),

    commitProject: (
      id: string, expected: NovelWorkspaceCas, commandId: string, kind: NovelWorkspaceMutationKind,
      transform: (p: NovelProject) => NovelProject,
    ): Promise<NovelProject> => storage.run(id, async (tx): Promise<NovelProject> => await commitLocked(tx, id, expected, commandId, kind, transform)),

    saveSharedMaterial: (
      id: string, material: NovelMaterial, expected: NovelWorkspaceCas, commandId: string,
    ): Promise<NovelProject> => storage.run(id, async (tx) => {
      const context = await historyMutationContext(tx, id, expected, commandId);
      if (context.replay !== null) return context.replay;
      if (material.id.trim().length === 0 || material.title.trim().length === 0) throw invalidInput('资料名称或 id 无效');
      const fields = normalizeNovelMaterialFields(material, material.enabled);
      const saved: NovelMaterial = { ...material, ...fields, enabled: fields.injectionMode !== 'off' };
      const current: NovelProject = initializeSharedMaterials(context.current);
      const base: NovelMaterial[] = (current.baseMaterials ?? []).filter(item => item.id !== saved.id).concat([saved]);
      const next: NovelProject = withUpdatedSharedMaterials(current, current.baseMaterials ?? [], base);
      const state: WorkspaceBranchSnapshot = await tx.branch(context.checkout.branchId);
      return await tx.commit({
        project: { ...next, revision: context.current.revision + 1, updatedAt: Date.now() },
        mutation: 'material_base_change',
        importedFiles: [],
        branchId: context.checkout.branchId,
        receipt: commandId,
        unresolvedFromOrdinal: state.unresolvedFromOrdinal,
        plotStale: state.plotStale,
        makeUndo: true,
        changedChapterOrdinal: null,
        preservePriorBranch: false,
        clearUndo: false,
        plotSyncedOverride: state.plotStale ? state.plotSyncedTreeDigest : undefined,
        plotContentOverride: state.plotContent
      });
    }),

    deleteSharedMaterial: (
      id: string, materialId: string, expected: NovelWorkspaceCas, commandId: string,
    ): Promise<NovelProject> => storage.run(id, async (tx) => {
      const context = await historyMutationContext(tx, id, expected, commandId);
      if (context.replay !== null) return context.replay;
      const current: NovelProject = initializeSharedMaterials(context.current);
      if (!(current.baseMaterials ?? []).some(item => item.id === materialId)) throw notFound('material', materialId);
      const next: NovelProject = withUpdatedSharedMaterials(current, current.baseMaterials ?? [],
        (current.baseMaterials ?? []).filter(item => item.id !== materialId));
      const state: WorkspaceBranchSnapshot = await tx.branch(context.checkout.branchId);
      return await tx.commit({
        project: { ...next, revision: context.current.revision + 1, updatedAt: Date.now() },
        mutation: 'material_base_change',
        importedFiles: [],
        branchId: context.checkout.branchId,
        receipt: commandId,
        unresolvedFromOrdinal: state.unresolvedFromOrdinal,
        plotStale: state.plotStale,
        makeUndo: true,
        changedChapterOrdinal: null,
        preservePriorBranch: false,
        clearUndo: false,
        plotSyncedOverride: state.plotStale ? state.plotSyncedTreeDigest : undefined,
        plotContentOverride: state.plotContent
      });
    }),

    restoreMaterialInheritance: (
      id: string, materialId: string, expected: NovelWorkspaceCas, commandId: string,
    ): Promise<NovelProject> => storage.run(id, async (tx) => {
      const context = await historyMutationContext(tx, id, expected, commandId);
      if (context.replay !== null) return context.replay;
      const next: NovelProject = restoreNovelMaterialInheritance(context.current, materialId);
      const state: WorkspaceBranchSnapshot = await tx.branch(context.checkout.branchId);
      return await tx.commit({
        project: { ...next, revision: context.current.revision + 1, updatedAt: Date.now() },
        mutation: 'material_inheritance_restore',
        importedFiles: [],
        branchId: context.checkout.branchId,
        receipt: commandId,
        unresolvedFromOrdinal: state.unresolvedFromOrdinal,
        plotStale: state.plotStale,
        makeUndo: true,
        changedChapterOrdinal: null,
        preservePriorBranch: false,
        clearUndo: false,
        plotSyncedOverride: state.plotStale ? state.plotSyncedTreeDigest : undefined,
        plotContentOverride: state.plotContent
      });
    }),

    completeStateOperation: (id: string, expected: NovelWorkspaceCas, operation: NovelStateOperation): Promise<NovelProject> =>
      storage.run(id, async (tx): Promise<NovelProject> => {
        const current = await tx.loadProject();
        const checkout = await tx.checkout();
        if (checkout === null) throw invalidInput('checkout 无效');
        assertCas(expected, checkout);
        assertNovelStateSources(operation, current, checkout.branchId);
        if (current.stateOperation?.id !== operation.id || operation.status !== 'completed'
          || operation.cursor !== operation.targets.length || operation.targets.length === 0) {
          throw invalidInput('状态任务尚未完成，未提交重建结果');
        }
        const branch = await tx.branch(checkout.branchId);
        const rebuilt: boolean = operation.kind === 'stateRebuild';
        const next: NovelProject = {
          ...current, stateOperation: operation, structuredState: operation.draft,
          chapterPlots: rebuilt ? rebuildChapterPlots(current.chapters) : current.chapterPlots,
          revision: current.revision + 1, updatedAt: Date.now()
        };
        return tx.commit({
          project: next,
          mutation: 'state_operation',
          importedFiles: [],
          branchId: checkout.branchId,
          receipt: `${operation.id}:completed`,
          unresolvedFromOrdinal: rebuilt ? null : branch.unresolvedFromOrdinal,
          plotStale: rebuilt ? false : branch.plotStale,
          makeUndo: false,
          changedChapterOrdinal: null,
          preservePriorBranch: false,
          clearUndo: false,
          plotSyncedOverride: rebuilt ? undefined : branch.plotSyncedTreeDigest
        });
      }),

    syncPlot: (
      id: string, expected: NovelWorkspaceCas, commandId: string,
    ): Promise<NovelProject> => storage.run(id, async (tx): Promise<NovelProject> => {
      assertCommandId(commandId);
      const commits: WorkspaceLedgerCommit[] = await tx.commits();
      const replay: NovelProject | null = await tx.replay(commits, commandId);
      if (replay !== null) return replay;
      const current: NovelProject = await tx.loadProject();
      const checkout: WorkspaceCheckout | null = await tx.checkout();
      if (checkout === null) throw invalidInput('checkout 无效');
      assertCas(expected, checkout);
      const next: NovelProject = {
        ...current, chapterPlots: rebuildChapterPlots(current.chapters),
        revision: current.revision + 1, updatedAt: Date.now(),
      };
      return await tx.commit({
        project: next,
        mutation: 'plot_sync',
        importedFiles: [],
        branchId: checkout.branchId,
        receipt: commandId,
        unresolvedFromOrdinal: null,
        plotStale: false,
        makeUndo: true
      });
    }),

    resolveUnresolved: (
      id: string, expected: NovelWorkspaceCas, commandId: string,
    ): Promise<NovelProject> => storage.run(id, async (tx): Promise<NovelProject> => {
      assertCommandId(commandId);
      const commits: WorkspaceLedgerCommit[] = await tx.commits();
      const replay: NovelProject | null = await tx.replay(commits, commandId);
      if (replay !== null) return replay;
      const current: NovelProject = await tx.loadProject();
      const checkout: WorkspaceCheckout | null = await tx.checkout();
      if (checkout === null) throw invalidInput('checkout 无效');
      assertCas(expected, checkout);
      const next: NovelProject = {
        ...current, chapterPlots: rebuildChapterPlots(current.chapters),
        revision: current.revision + 1, updatedAt: Date.now(),
      };
      return await tx.commit({
        project: next,
        mutation: 'unresolved_resolve',
        importedFiles: [],
        branchId: checkout.branchId,
        receipt: commandId,
        unresolvedFromOrdinal: null,
        plotStale: false,
        makeUndo: true
      });
    })
  };
  return repository;
};
