import { invalidInput, notFound } from './error.ts';
import type { GhostwriteClaimRef } from './ghostwrite.ts';
import { defaultGhostwriteDigest } from './ghostwrite.ts';
import type { NovelChapter, NovelProject } from './models.ts';
import { makeNovelChapterVersion, withNovelProjectCompatibility } from './models.ts';
import { frozenPolishContext } from './polish_context.ts';
import type {
  DurablePolishJob, PolishCandidate, PolishChapterTarget, PolishContextOptions, PolishContextSnapshotItem,
  PolishProgress, PolishReview, PolishStage, PolishWarning,
} from './polish.ts';
import {
  applyPolishReview, assertPolishClaim, cancelPolishJob as cancelPolishJobDomain,
  claimPolishJob as claimPolishJobDomain, commitPolishChapter as commitPolishChapterDomain,
  currentPolishTarget, failPolishJob as failPolishJobDomain, makePolishCandidate, makePolishChapterTarget,
  makePolishJob, pausePolishJob as pausePolishJobDomain, polishReceipt, projectPolishProgress,
  recordPolishChapterResult, resumePolishJob as resumePolishJobDomain,
  retryPolishJob as retryPolishJobDomain, transitionPolishJob, validateDurablePolishJob,
  validatePolishReview, withPolishCandidate, yieldPolishJobToSystem,
} from './polish.ts';
import type { NovelProjectRepository, NovelWorkspaceCas } from './repository_contract.ts';
import type {
  DurableNovelJob, WorkspaceBranchSnapshot, WorkspaceCheckout, WorkspaceLedgerCommit,
  WorkspaceNovelJobStore,
} from './workspace_codec.ts';
import {
  ancestryCommitIds, assertCas, assertCommandId, assertNoActiveNovelJob, chapterOrdinal, isPolishJob,
} from './workspace_codec.ts';
import { projectChapterPlots, projectedPlotStatus } from './workspace_projection.ts';
import type { WorkspaceScope, WorkspaceSession } from './workspace_transaction.ts';

type PolishSession = Pick<WorkspaceSession,
  'branch' | 'checkout' | 'commitJob' | 'commits' | 'jobs' | 'loadProject' | 'saveJobs'>;
type PolishStorage = WorkspaceScope<PolishSession>;

interface LoadedPolishJob {
  store: WorkspaceNovelJobStore;
  index: number;
  job: DurablePolishJob;
  checkout: WorkspaceCheckout;
}

const replacePolishJob = (
  store: WorkspaceNovelJobStore, index: number, job: DurablePolishJob,
): WorkspaceNovelJobStore => {
  const jobs: DurableNovelJob[] = store.jobs.slice();
  jobs[index] = validateDurablePolishJob(job);
  return { version: 2, jobs };
};


const assertPolishWorkspaceBinding = (
  id: string, job: DurablePolishJob, checkout: WorkspaceCheckout,
): void => {
  if (job.projectId !== id || job.branchId !== checkout.branchId) {
    throw invalidInput('polish job 与当前 project/branch 不匹配');
  }
  assertCas(job.expectedCas, checkout);
};


const polishJobIndex = (store: WorkspaceNovelJobStore, jobId: string): number => {
  const index: number = store.jobs.findIndex(
    (job: DurableNovelJob): boolean => isPolishJob(job) && job.jobId === jobId);
  if (index < 0) throw notFound('polish_job', jobId);
  return index;
};

const loadBoundPolishJob = async (tx: PolishSession, id: string, jobId: string, requireCas: boolean = true): Promise<LoadedPolishJob> => {
  await tx.loadProject();
  const checkout: WorkspaceCheckout | null = await tx.checkout();
  if (checkout === null) throw invalidInput('checkout 无效');
  const store: WorkspaceNovelJobStore = await tx.jobs();
  const index: number = polishJobIndex(store, jobId);
  const stored: DurableNovelJob = store.jobs[index];
  if (!isPolishJob(stored)) throw invalidInput('polish job 类型无效');
  if (requireCas) assertPolishWorkspaceBinding(id, stored, checkout);
  else if (stored.projectId !== id || stored.branchId !== checkout.branchId) {
    throw invalidInput('polish job 与当前 project/branch 不匹配');
  }
  return { store, index, job: stored, checkout };
};

const loadClaimedPolishJob = async (
  tx: PolishSession, id: string, jobId: string, claim: GhostwriteClaimRef, now: number,
): Promise<LoadedPolishJob> => {
  const loaded: LoadedPolishJob = await loadBoundPolishJob(tx, id, jobId);
  assertPolishClaim(loaded.job, claim, now);
  return loaded;
};

export const createPolishRepository = (storage: PolishStorage): Pick<NovelProjectRepository,
  'listPolishJobs' | 'loadPolishJob' | 'startPolishJob' | 'claimPolishJob' | 'checkpointPolishStage'
  | 'checkpointPolishCandidate' | 'checkpointPolishReview' | 'failPolishJob' | 'pausePolishJob'
  | 'yieldPolishJob' | 'resumePolishJob' | 'recordPolishChapterResult' | 'retryPolishJob'
  | 'cancelPolishJob' | 'commitPolishChapter' | 'polishProgress'> => {
  const repository = {
    listPolishJobs: (id: string): Promise<DurablePolishJob[]> =>
      storage.run(id, async (tx): Promise<DurablePolishJob[]> => {
        await tx.loadProject();
        const checkout: WorkspaceCheckout | null = await tx.checkout();
        if (checkout === null) throw invalidInput('checkout 无效');
        return (await tx.jobs()).jobs.filter(
          (job: DurableNovelJob): job is DurablePolishJob =>
            isPolishJob(job) && job.branchId === checkout.branchId).slice();
      }),

    loadPolishJob: (id: string, jobId: string): Promise<DurablePolishJob> =>
      storage.run(id, async (tx): Promise<DurablePolishJob> => {
        await tx.loadProject();
        const checkout: WorkspaceCheckout | null = await tx.checkout();
        if (checkout === null) throw invalidInput('checkout 无效');
        const store: WorkspaceNovelJobStore = await tx.jobs();
        const stored: DurableNovelJob = store.jobs[polishJobIndex(store, jobId)];
        if (!isPolishJob(stored) || stored.projectId !== id || stored.branchId !== checkout.branchId) {
          throw invalidInput('polish job 不属于当前 project/branch');
        }
        return stored;
      }),

    startPolishJob: (
      id: string, expected: NovelWorkspaceCas, jobId: string,
      fromOrdinal: number, toOrdinal: number, contextOptions: PolishContextOptions, now: number,
      selectedOrdinals?: number[],
    ): Promise<DurablePolishJob> => storage.run(id, async (tx): Promise<DurablePolishJob> => {
      if (!Number.isInteger(fromOrdinal) || !Number.isInteger(toOrdinal) ||
        fromOrdinal < 1 || toOrdinal < fromOrdinal) throw invalidInput('润色范围无效');
      const ordinals: number[] = selectedOrdinals === undefined ? [] : selectedOrdinals.slice();
      if (selectedOrdinals === undefined) {
        for (let ordinal: number = fromOrdinal; ordinal <= toOrdinal; ordinal++) ordinals.push(ordinal);
      } else {
        if (ordinals.length === 0 || ordinals.some((ordinal: number): boolean =>
          !Number.isInteger(ordinal) || ordinal < 1) || new Set<number>(ordinals).size !== ordinals.length) {
          throw invalidInput('润色选章必须是非空且不重复的正整数');
        }
        ordinals.sort((left: number, right: number): number => left - right);
      }
      const project: NovelProject = await tx.loadProject();
      const checkout: WorkspaceCheckout | null = await tx.checkout();
      if (checkout === null) throw invalidInput('checkout 无效');
      assertCas(expected, checkout);
      const store: WorkspaceNovelJobStore = await tx.jobs();
      if (store.jobs.some((job: DurableNovelJob): boolean => job.jobId === jobId)) {
        throw invalidInput(`novel job 已存在: ${jobId}`);
      }
      assertNoActiveNovelJob(store, checkout.branchId);

      const targets: PolishChapterTarget[] = [];
      for (const ordinal of ordinals) {
        let found: NovelChapter | null = null;
        for (let index: number = 0; index < project.chapters.length; index++) {
          const chapter: NovelChapter = project.chapters[index];
          if (!chapter.discarded && chapterOrdinal(chapter, index + 1) === ordinal) {
            if (found !== null) throw invalidInput(`第 ${ordinal} 章序号重复`);
            found = chapter;
          }
        }
        if (found === null) throw invalidInput(`第 ${ordinal} 章不存在或已废弃`);
        targets.push(makePolishChapterTarget({
          id: found.id, ordinal, title: found.title, sourceContent: found.content,
        }));
      }

      const branch: WorkspaceBranchSnapshot = await tx.branch(checkout.branchId);
      if (branch.plotStale || branch.unresolvedFromOrdinal !== null) {
        throw invalidInput('润色前必须同步剧情并确认后续章节影响');
      }
      const contextSnapshot: PolishContextSnapshotItem[] = frozenPolishContext(project, checkout.branchId, contextOptions, targets, branch.plotContent);
      const warnings: PolishWarning[] = [];
      const job: DurablePolishJob = makePolishJob({
        jobId, projectId: id, branchId: checkout.branchId, targets, contextOptions,
        contextSnapshot, warnings, expectedCas: checkout,
        modelPolicyAtStart: project.modelPolicy, polishPreferenceAtStart: (project.polishPreference ?? '').trim(), now,
      });
      const nextStore: WorkspaceNovelJobStore = { version: 2, jobs: store.jobs.concat([job]) };
      await tx.saveJobs(nextStore);
      return job;
    }),

    claimPolishJob: (
      id: string, jobId: string, token: string, now: number, leaseMs: number,
    ): Promise<DurablePolishJob> => storage.run(id, async (tx): Promise<DurablePolishJob> => {
      const loaded: LoadedPolishJob = await loadBoundPolishJob(tx, id, jobId);
      const claimed: DurablePolishJob = claimPolishJobDomain(loaded.job, token, now, leaseMs);
      await tx.saveJobs(replacePolishJob(loaded.store, loaded.index, claimed));
      return claimed;
    }),

    checkpointPolishStage: (
      id: string, jobId: string, claim: GhostwriteClaimRef, nextStage: PolishStage, now: number,
    ): Promise<DurablePolishJob> => storage.run(id, async (tx): Promise<DurablePolishJob> => {
      const loaded: LoadedPolishJob = await loadClaimedPolishJob(tx, id, jobId, claim, now);
      const next: DurablePolishJob = transitionPolishJob(loaded.job, nextStage, now);
      await tx.saveJobs(replacePolishJob(loaded.store, loaded.index, next));
      return next;
    }),

    checkpointPolishCandidate: (
      id: string, jobId: string, claim: GhostwriteClaimRef,
      candidate: PolishCandidate, now: number,
    ): Promise<DurablePolishJob> => storage.run(id, async (tx): Promise<DurablePolishJob> => {
      const loaded: LoadedPolishJob = await loadClaimedPolishJob(tx, id, jobId, claim, now);
      const next: DurablePolishJob = withPolishCandidate(loaded.job, candidate, now);
      await tx.saveJobs(replacePolishJob(loaded.store, loaded.index, next));
      return next;
    }),

    checkpointPolishReview: (
      id: string, jobId: string, claim: GhostwriteClaimRef,
      review: PolishReview, now: number,
    ): Promise<DurablePolishJob> => storage.run(id, async (tx): Promise<DurablePolishJob> => {
      const loaded: LoadedPolishJob = await loadClaimedPolishJob(tx, id, jobId, claim, now);
      const assessed: DurablePolishJob = applyPolishReview(loaded.job, review, now);
      const next: DurablePolishJob = assessed.stage === 'failed'
        ? recordPolishChapterResult(loaded.job, claim, review.blocking ? 'driftSkipped' : 'failed', assessed.failure!, now) : assessed;
      await tx.saveJobs(replacePolishJob(loaded.store, loaded.index, next));
      return next;
    }),

    failPolishJob: (
      id: string, jobId: string, claim: GhostwriteClaimRef, reason: string, now: number,
    ): Promise<DurablePolishJob> => storage.run(id, async (tx): Promise<DurablePolishJob> => {
      const loaded: LoadedPolishJob = await loadBoundPolishJob(tx, id, jobId, false);
      assertPolishClaim(loaded.job, claim, now);
      const next: DurablePolishJob = failPolishJobDomain(loaded.job, claim, reason, now);
      await tx.saveJobs(replacePolishJob(loaded.store, loaded.index, next));
      return next;
    }),

    pausePolishJob: (
      id: string, jobId: string, claim: GhostwriteClaimRef, now: number,
    ): Promise<DurablePolishJob> => storage.run(id, async (tx): Promise<DurablePolishJob> => {
      const loaded: LoadedPolishJob = await loadClaimedPolishJob(tx, id, jobId, claim, now);
      const next: DurablePolishJob = pausePolishJobDomain(loaded.job, claim, now);
      await tx.saveJobs(replacePolishJob(loaded.store, loaded.index, next));
      return next;
    }),

    yieldPolishJob: (
      id: string, jobId: string, claim: GhostwriteClaimRef, now: number,
    ): Promise<DurablePolishJob> => storage.run(id, async (tx): Promise<DurablePolishJob> => {
      const loaded: LoadedPolishJob = await loadClaimedPolishJob(tx, id, jobId, claim, now);
      const next: DurablePolishJob = yieldPolishJobToSystem(loaded.job, claim, now);
      await tx.saveJobs(replacePolishJob(loaded.store, loaded.index, next));
      return next;
    }),

    resumePolishJob: (
      id: string, jobId: string, now: number,
    ): Promise<DurablePolishJob> => storage.run(id, async (tx): Promise<DurablePolishJob> => {
      const loaded: LoadedPolishJob = await loadBoundPolishJob(tx, id, jobId);
      const next: DurablePolishJob = resumePolishJobDomain(loaded.job, now);
      await tx.saveJobs(replacePolishJob(loaded.store, loaded.index, next));
      return next;
    }),

    recordPolishChapterResult: (
      id: string, jobId: string, claim: GhostwriteClaimRef, status: 'failed' | 'driftSkipped', reason: string, now: number,
    ): Promise<DurablePolishJob> => storage.run(id, async (tx) => {
      const loaded = await loadClaimedPolishJob(tx, id, jobId, claim, now);
      const next = recordPolishChapterResult(loaded.job, claim, status, reason, now);
      await tx.saveJobs(replacePolishJob(loaded.store, loaded.index, next));
      return next;
    }),

    retryPolishJob: (
      id: string, jobId: string, now: number, chapterIds?: string[],
    ): Promise<DurablePolishJob> => storage.run(id, async (tx): Promise<DurablePolishJob> => {
      const loaded: LoadedPolishJob = await loadBoundPolishJob(tx, id, jobId);
      assertNoActiveNovelJob({ ...loaded.store, jobs: loaded.store.jobs.filter(job => job.jobId !== jobId) }, loaded.job.branchId);
      const next: DurablePolishJob = retryPolishJobDomain(loaded.job, now, chapterIds);
      await tx.saveJobs(replacePolishJob(loaded.store, loaded.index, next));
      return next;
    }),

    cancelPolishJob: (
      id: string, jobId: string, now: number,
    ): Promise<DurablePolishJob> => storage.run(id, async (tx): Promise<DurablePolishJob> => {
      const loaded: LoadedPolishJob = await loadBoundPolishJob(tx, id, jobId, false);
      const next: DurablePolishJob = cancelPolishJobDomain(loaded.job, now);
      await tx.saveJobs(replacePolishJob(loaded.store, loaded.index, next));
      return next;
    }),

    commitPolishChapter: (
      id: string, jobId: string, claim: GhostwriteClaimRef, commandId: string, now: number,
    ): Promise<DurablePolishJob> => storage.run(id, async (tx): Promise<DurablePolishJob> => {
      assertCommandId(commandId);
      const store: WorkspaceNovelJobStore = await tx.jobs();
      const index: number = polishJobIndex(store, jobId);
      const stored: DurableNovelJob = store.jobs[index];
      if (!isPolishJob(stored)) throw invalidInput('polish job 类型无效');
      if (stored.progress.some((entry: PolishProgress): boolean => entry.receipt === commandId)) return stored;
      const project: NovelProject = await tx.loadProject();
      const checkout: WorkspaceCheckout | null = await tx.checkout();
      if (checkout === null) throw invalidInput('checkout 无效');
      assertPolishWorkspaceBinding(id, stored, checkout);
      assertPolishClaim(stored, claim, now);
      if (stored.stage !== 'committing' || stored.candidate === null || stored.review === null) {
        throw invalidInput('polish job 尚未到可提交阶段');
      }
      const candidate: PolishCandidate = makePolishCandidate({
        jobId: stored.jobId,
        candidateId: stored.candidate.candidateId,
        chapterId: stored.candidate.chapterId,
        chapterOrdinal: stored.candidate.chapterOrdinal,
        sourceDigest: stored.candidate.sourceDigest,
        content: stored.candidate.content,
        attempt: stored.candidate.attempt,
      });
      validatePolishReview(stored.review, candidate);
      const target: PolishChapterTarget | null = currentPolishTarget(stored);
      if (target === null || target.id !== candidate.chapterId) throw invalidInput('polish target 与 candidate 不匹配');
      const receipt: string = polishReceipt(
        stored.jobId, candidate.chapterId, candidate.chapterOrdinal, candidate.sourceDigest,
        candidate.candidateId, candidate.digest,
      );
      if (commandId !== receipt) throw invalidInput('polish commit receipt 与候选不匹配');
      const chapterIndex: number = project.chapters.findIndex(
        (chapter: NovelChapter): boolean => chapter.id === target.id);
      if (chapterIndex < 0 || project.chapters[chapterIndex].discarded) {
        throw invalidInput('polish 目标章节不存在或已废弃');
      }
      const currentChapter: NovelChapter = project.chapters[chapterIndex];
      if (defaultGhostwriteDigest(currentChapter.content) !== target.sourceDigest) {
        throw invalidInput('polish 目标章节已在任务启动后变更');
      }
      const chapters: NovelChapter[] = project.chapters.slice();
      chapters[chapterIndex] = { ...currentChapter, content: candidate.content, updatedAt: now };
      const backup = makeNovelChapterVersion(
        currentChapter.id, 'polish', currentChapter.title, currentChapter.content, now);
      const branch: WorkspaceBranchSnapshot = await tx.branch(checkout.branchId);
      const nextProject: NovelProject = projectChapterPlots(project, withNovelProjectCompatibility({
        ...project,
        chapters,
        chapterVersions: project.chapterVersions.concat([backup]),
        revision: project.revision + 1,
        updatedAt: Math.max(project.updatedAt, now),
      }), branch);
      const { unresolved, stale } = projectedPlotStatus(nextProject);
      const committedStore: WorkspaceNovelJobStore = await tx.commitJob({
        project: nextProject,
        mutation: 'batch_polish_commit',
        branchId: checkout.branchId,
        receipt,
        unresolvedFromOrdinal: unresolved,
        plotStale: stale,
        makeUndo: true,
        changedChapterOrdinal: target.ordinal,
        plotContentOverride: branch.plotContent
      }, (nextCas): WorkspaceNovelJobStore => {
        const nextJob: DurablePolishJob = commitPolishChapterDomain(
          stored, claim, { branchId: checkout.branchId, commitId: nextCas.head, receipt, nextCas }, now);
        return replacePolishJob(store, index, nextJob);
      });
      return committedStore.jobs[index] as DurablePolishJob;
    }),

    polishProgress: (
      id: string, jobId: string,
    ): Promise<PolishProgress[]> => storage.run(id, async (tx): Promise<PolishProgress[]> => {
      await tx.loadProject();
      const checkout: WorkspaceCheckout | null = await tx.checkout();
      if (checkout === null) throw invalidInput('checkout 无效');
      const store: WorkspaceNovelJobStore = await tx.jobs();
      const stored: DurableNovelJob = store.jobs[polishJobIndex(store, jobId)];
      if (!isPolishJob(stored) || stored.projectId !== id || stored.branchId !== checkout.branchId) return [];
      const commits: WorkspaceLedgerCommit[] = await tx.commits();
      return projectPolishProgress(stored, ancestryCommitIds(commits, checkout.head));
    })
  };
  return repository;
};
