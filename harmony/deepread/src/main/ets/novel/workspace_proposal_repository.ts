import { reconcileNovelBranchPlans } from './chapter_contract.ts';
import { invalidInput, notFound } from './error.ts';
import { reachableWorkspaceHistory, selectWorkspaceCheckpoint } from './history_selection.ts';
import { materialSuggestionChapterDigest } from './material_adoption.ts';
import type { NovelChapter, NovelProject } from './models.ts';
import { saveChapterVersion } from './mutations.ts';
import type { NovelProjectRepository, NovelWorkspaceCas, NovelWorkspaceSnapshot } from './repository_contract.ts';
import type { NovelProjectOperationKind, NovelProjectToolInput } from './specialized_operations.ts';
import {
  applyNovelSpecializedOperation, makeNovelProjectOperation, makeNovelSpecializedReview,
  novelOperationSourceDigest,
} from './specialized_operations.ts';
import type {
  WorkspaceBranchSnapshot, WorkspaceChapterRecord, WorkspaceCheckout, WorkspaceCommitSnapshot,
  WorkspaceLedgerCommit, WorkspaceProposalDecision, WorkspaceProposalStore,
} from './workspace_codec.ts';
import {
  assertCas, assertCommandId, assertNoActiveNovelJob, branchSettingPaths, findReceipt,
} from './workspace_codec.ts';
import type {
  DurableWorkspaceProposal, WorkspaceCommit, WorkspaceProposalPatch, WorkspaceProposalReview,
} from './workspace_history.ts';
import { resolveDurableWorkspaceProposal, validateDurableWorkspaceProposal } from './workspace_history.ts';
import { assertChapterPlanEditable, readWorkspaceSnapshotLocked } from './workspace_project_repository.ts';
import { chapterChangedAt, projectChapterPlots, projectedPlotStatus } from './workspace_projection.ts';
import type { WorkspaceScope, WorkspaceSession } from './workspace_transaction.ts';

type ProposalSession = Pick<WorkspaceSession,
  'branch' | 'checkout' | 'commit' | 'commits' | 'historicalSnapshot' | 'jobs' | 'loadProject' | 'proposals' | 'saveProposals'>;
type ProposalStorage = WorkspaceScope<ProposalSession>;

const validateProposalTargets = (
  proposal: DurableWorkspaceProposal, branch: WorkspaceBranchSnapshot,
): void => {
  const plotPath: string = `branches/${branch.branchId}/plan/plot.md`;
  const planPath: string = branchSettingPaths(branch.branchId).thisChapterPlan;
  const chapterPaths: Set<string> = new Set<string>();
  for (let i: number = 0; i < branch.state.chapters.length; i++) {
    chapterPaths.add(branch.state.chapters[i].path);
  }
  for (let i: number = 0; i < proposal.patches.length; i++) {
    const path: string = proposal.patches[i].path;
    if (path !== plotPath && path !== planPath && !chapterPaths.has(path)) {
      throw invalidInput(`proposal 仅可修改已有章节、plot 或本章计划: ${path}`);
    }
  }
};

const stageWorkspaceProposalLocked = async (tx: ProposalSession, id: string, proposal: DurableWorkspaceProposal): Promise<DurableWorkspaceProposal> => {
  const store: WorkspaceProposalStore = await tx.proposals();
  const existing: DurableWorkspaceProposal | undefined = store.proposals.find(item => item.proposalId === proposal.proposalId);
  if (existing !== undefined) {
    if (JSON.stringify(existing) !== JSON.stringify(proposal)) throw invalidInput(`proposal id 已存在: ${proposal.proposalId}`);
    return existing;
  }
  await tx.saveProposals({
    version: 1,
    proposals: store.proposals.concat([proposal]), decisions: store.decisions
  });
  return proposal;
};

const assertProposalCheckout = (proposal: DurableWorkspaceProposal, checkout: WorkspaceCheckout, commits: WorkspaceCommit[]): void => {
  if (proposal.expectedCas.head === checkout.head) { assertCas(proposal.expectedCas, checkout); return; }
  if (proposal.expectedCas.branchId !== checkout.branchId || proposal.expectedCas.treeDigest !== checkout.treeDigest) {
    throw invalidInput('工作区已变化，拒绝覆盖');
  }
  let cursor: string | null = checkout.head;
  while (cursor !== proposal.expectedCas.head) {
    const commit = commits.find(item => item.commitId === cursor);
    if (commit === undefined || commit.branchId !== checkout.branchId || commit.mutation !== 'transcript_checkpoint' ||
      commit.treeDigest !== checkout.treeDigest || commit.parent === null) throw invalidInput('工作区已变化，拒绝覆盖');
    cursor = commit.parent;
  }
};

const assertProposalSources = (proposal: DurableWorkspaceProposal, current: NovelProject): void => {
  if (proposal.operation !== undefined && proposal.review?.sourceDigest !== novelOperationSourceDigest(current)) {
    throw invalidInput('操作来源已变化，请重新预览');
  }
  for (const preview of proposal.review?.previews ?? []) {
    if (preview.chapterId === undefined || preview.sourceDigest === undefined) continue;
    const chapter: NovelChapter | undefined = current.chapters.find(item => item.id === preview.chapterId);
    if (chapter === undefined || preview.sourceDigest !== materialSuggestionChapterDigest(chapter)) throw invalidInput('正文来源已变化');
    if (preview.start !== undefined && chapter.content.slice(preview.start, preview.end) !== preview.oldText) throw invalidInput('预览原文范围已变化');
  }
};

export const createWorkspaceProposalRepository = (storage: ProposalStorage): Pick<NovelProjectRepository,
  'workspaceProposals' | 'proposeProjectOperation' | 'createProposal' | 'resolveProposal'> => {
  const repository = {
    workspaceProposals: (id: string): Promise<DurableWorkspaceProposal[]> =>
      storage.run(id, async (tx): Promise<DurableWorkspaceProposal[]> => {
        const snapshot: NovelWorkspaceSnapshot = await readWorkspaceSnapshotLocked(tx, id);
        return (await tx.proposals()).proposals.filter(proposal => proposal.expectedCas.branchId === snapshot.status.activeBranchId);
      }),

    proposeProjectOperation: (
      id: string, kind: NovelProjectOperationKind, args: NovelProjectToolInput, expected: NovelWorkspaceCas,
      proposalId: string, createdAt: number,
    ): Promise<DurableWorkspaceProposal> => storage.run(id, async (tx) => {
      const current: NovelProject = await tx.loadProject();
      const checkout: WorkspaceCheckout | null = await tx.checkout();
      if (checkout === null) throw invalidInput('checkout 无效');
      assertCas(expected, checkout);
      const operation = makeNovelProjectOperation(kind, args);
      let historical: NovelProject | undefined;
      if (kind === 'revert_recent_chapters') {
        const count = args.chapter_count;
        const working: NovelChapter[] = current.chapters.filter(chapter => !chapter.discarded);
        if (!Number.isInteger(count) || count! < 1 || count! > Math.min(64, working.length)) throw invalidInput('回退章数超出正文范围');
        const kept: string[] = working.slice(0, working.length - count!).map(chapter => chapter.id);
        const history: WorkspaceCommit[] = reachableWorkspaceHistory(await tx.commits(), checkout.head, checkout.branchId);
        for (const commit of history) {
          const snapshot: WorkspaceCommitSnapshot = await tx.historicalSnapshot(commit);
          const ids = snapshot.project.chapters.filter(chapter => !chapter.discarded).map(chapter => chapter.id);
          if (JSON.stringify(ids) === JSON.stringify(kept)) { operation.restoreHead = commit.commitId; historical = snapshot.project; break; }
        }
        if (historical === undefined) throw invalidInput('没有对应的真实历史检查点，未回退或删除正文');
      }
      const next: NovelProject = applyNovelSpecializedOperation(current, operation, checkout.branchId, createdAt, historical);
      const proposal: DurableWorkspaceProposal = validateDurableWorkspaceProposal({
        proposalId, status: 'pending',
        expectedCas: expected, patches: [], operation, review: makeNovelSpecializedReview(current, next, operation), createdAt, resolvedAt: null
      });
      return await stageWorkspaceProposalLocked(tx, id, proposal);
    }),

    createProposal: (
      id: string, expected: NovelWorkspaceCas, proposalId: string,
      patches: WorkspaceProposalPatch[], createdAt: number, review?: WorkspaceProposalReview,
    ): Promise<DurableWorkspaceProposal> => storage.run(id, async (tx): Promise<DurableWorkspaceProposal> => {
      const current: NovelProject = await tx.loadProject();
      const checkout: WorkspaceCheckout | null = await tx.checkout();
      if (checkout === null) throw invalidInput('checkout 无效');
      assertCas(expected, checkout);
      const proposal: DurableWorkspaceProposal = validateDurableWorkspaceProposal({
        proposalId,
        status: 'pending',
        expectedCas: {
          branchId: expected.branchId,
          head: expected.head,
          treeDigest: expected.treeDigest,
        },
        patches,
        ...(review === undefined ? {} : { review }),
        createdAt,
        resolvedAt: null,
      });
      const branch: WorkspaceBranchSnapshot = await tx.branch(checkout.branchId);
      validateProposalTargets(proposal, branch);
      return await stageWorkspaceProposalLocked(tx, current.id, proposal);
    }),

    resolveProposal: (
      id: string, proposalId: string, accept: boolean, commandId: string, resolvedAt: number,
    ): Promise<DurableWorkspaceProposal> => storage.run(id, async (tx): Promise<DurableWorkspaceProposal> => {
      assertCommandId(commandId);
      const store: WorkspaceProposalStore = await tx.proposals();
      const commandDecision: WorkspaceProposalDecision | undefined = store.decisions.find(
        (decision: WorkspaceProposalDecision): boolean => decision.commandId === commandId);
      if (commandDecision !== undefined) {
        if (commandDecision.proposalId !== proposalId || commandDecision.accept !== accept) {
          throw invalidInput('proposal decision command 已用于其他决定');
        }
        const replay: DurableWorkspaceProposal | undefined = store.proposals.find(
          (proposal: DurableWorkspaceProposal): boolean => proposal.proposalId === proposalId);
        if (replay === undefined) throw invalidInput('proposal decision 缺少 proposal');
        return replay;
      }
      const proposalIndex: number = store.proposals.findIndex(
        (proposal: DurableWorkspaceProposal): boolean => proposal.proposalId === proposalId);
      if (proposalIndex < 0) throw notFound('proposal', proposalId);
      const proposal: DurableWorkspaceProposal = store.proposals[proposalIndex];
      if (proposal.status !== 'pending') return proposal;
      const current: NovelProject = await tx.loadProject();
      const checkout: WorkspaceCheckout | null = await tx.checkout();
      if (checkout === null) throw invalidInput('checkout 无效');
      const proposalCommits: WorkspaceCommit[] = await tx.commits();
      if (accept) { assertProposalCheckout(proposal, checkout, proposalCommits); assertProposalSources(proposal, current); }
      else if (proposal.expectedCas.branchId !== checkout.branchId) throw invalidInput('不能操作其他分支的提案');
      const branch: WorkspaceBranchSnapshot = await tx.branch(checkout.branchId);
      if (accept) validateProposalTargets(proposal, branch);
      const resolved: DurableWorkspaceProposal = resolveDurableWorkspaceProposal(
        proposal, accept, resolvedAt).proposal;
      const proposals: DurableWorkspaceProposal[] = store.proposals.slice();
      proposals[proposalIndex] = resolved;
      const decision: WorkspaceProposalDecision = { commandId, proposalId, accept };
      const nextStore: WorkspaceProposalStore = {
        version: 1,
        proposals,
        decisions: store.decisions.concat([decision]),
      };
      if (!accept) {
        await tx.saveProposals(nextStore);
        return resolved;
      }
      const commits: WorkspaceLedgerCommit[] = await tx.commits();
      if (findReceipt(commits, commandId) !== null) {
        throw invalidInput('proposal decision command 与既有 receipt 冲突');
      }
      if (proposal.operation !== undefined) {
        assertNoActiveNovelJob(await tx.jobs(), checkout.branchId);
        let restored: WorkspaceCommitSnapshot | undefined;
        if (proposal.operation.restoreHead !== undefined) {
          const target = selectWorkspaceCheckpoint(commits, checkout.head, checkout.branchId, proposal.operation.restoreHead);
          restored = await tx.historicalSnapshot(target);
        }
        let applied: NovelProject = applyNovelSpecializedOperation(current, proposal.operation, checkout.branchId, resolvedAt, restored?.project);
        applied = { ...applied, revision: current.revision + 1 };
        const changed = chapterChangedAt(current.chapters, applied.chapters);
        const next: NovelProject = restored === undefined ? projectChapterPlots(current, applied, branch) : applied;
        const status = restored === undefined ? projectedPlotStatus(next)
          : { unresolved: restored.unresolvedFromOrdinal, stale: restored.plotStale };
        if (next.branchSettings.thisChapterPlan !== current.branchSettings.thisChapterPlan) await assertChapterPlanEditable(tx, id, checkout.branchId);
        await tx.commit({
          project: next,
          mutation: restored === undefined ? 'proposal_resolve' : 'undo',
          importedFiles: [],
          branchId: checkout.branchId,
          receipt: commandId,
          unresolvedFromOrdinal: status.unresolved,
          plotStale: status.stale,
          makeUndo: true,
          changedChapterOrdinal: changed === null ? null : changed + 1,
          preservePriorBranch: false,
          clearUndo: false,
          plotSyncedOverride: restored?.plotSyncedTreeDigest,
          plotContentOverride: restored === undefined ? branch.plotContent : restored.plotContent,
          proposalStoreOverride: nextStore,
          restoredFromHead: proposal.operation.restoreHead
        });
        return resolved;
      }
      let archived: NovelProject = current;
      const archivedIds: Set<string> = new Set();
      let chapters: NovelChapter[] = current.chapters.slice();
      let plotContent: string | null = branch.plotContent;
      let thisChapterPlan: string = current.branchSettings.thisChapterPlan;
      let changedIndex: number | null = null;
      for (let i: number = 0; i < proposal.patches.length; i++) {
        const patch: WorkspaceProposalPatch = proposal.patches[i];
        const plotPath: string = `branches/${checkout.branchId}/plan/plot.md`;
        if (patch.path === plotPath) {
          plotContent = patch.operation === 'write' ? patch.content : null;
          continue;
        }
        if (patch.path === branchSettingPaths(checkout.branchId).thisChapterPlan) {
          thisChapterPlan = patch.operation === 'write' ? patch.content ?? '' : '';
          continue;
        }
        const recordIndex: number = branch.state.chapters.findIndex(
          (record: WorkspaceChapterRecord): boolean => record.path === patch.path);
        if (recordIndex < 0) throw invalidInput(`proposal 章节路径不存在: ${patch.path}`);
        const chapterId: string = branch.state.chapters[recordIndex].id;
        const projectIndex: number = chapters.findIndex(
          (chapter: NovelChapter): boolean => chapter.id === chapterId);
        if (projectIndex < 0) throw invalidInput(`proposal 章节不存在: ${chapterId}`);
        changedIndex = changedIndex === null ? projectIndex : Math.min(changedIndex, projectIndex);
        if (patch.operation === 'delete') {
          chapters.splice(projectIndex, 1);
        } else {
          if (chapters[projectIndex].content !== patch.content && !archivedIds.has(chapterId)) {
            archived = saveChapterVersion(archived, chapterId, 'manual', resolvedAt).project;
            archivedIds.add(chapterId);
          }
          chapters[projectIndex] = {
            ...chapters[projectIndex],
            content: patch.content ?? '',
            updatedAt: resolvedAt,
          };
        }
      }
      if (thisChapterPlan !== current.branchSettings.thisChapterPlan) {
        await assertChapterPlanEditable(tx, id, checkout.branchId);
      }
      const next: NovelProject = projectChapterPlots(current, {
        ...current,
        chapterVersions: archived.chapterVersions,
        chapters,
        branchSettings: reconcileNovelBranchPlans(current.branchSettings, { ...current.branchSettings, thisChapterPlan }),
        revision: current.revision + 1,
        updatedAt: Math.max(current.updatedAt, resolvedAt),
      }, branch);
      const { unresolved, stale } = projectedPlotStatus(next);
      await tx.commit({
        project: next,
        mutation: 'proposal_resolve',
        importedFiles: [],
        branchId: checkout.branchId,
        receipt: commandId,
        unresolvedFromOrdinal: unresolved,
        plotStale: stale,
        makeUndo: true,
        changedChapterOrdinal: changedIndex === null ? null : changedIndex + 1,
        preservePriorBranch: false,
        clearUndo: false,
        plotContentOverride: plotContent,
        proposalStoreOverride: nextStore
      });
      return resolved;
    })
  };
  return repository;
};
