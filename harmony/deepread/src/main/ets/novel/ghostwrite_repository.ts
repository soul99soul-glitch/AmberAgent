import { confirmedChapterPlanText } from './chapter_contract.ts';
import { invalidInput, notFound } from './error.ts';
import type {
  DurableGhostwriteJob, GhostwriteCandidate, GhostwriteClaimRef, GhostwriteProgress, GhostwriteReview,
  GhostwriteStage,
} from './ghostwrite.ts';
import {
  applyGhostwriteReview, assertGhostwriteClaim, claimGhostwriteJob as claimGhostwriteJobDomain,
  freezeGhostwritePlan, ghostwriteReceipt, makeGhostwriteJob,
  pauseGhostwriteJob as pauseGhostwriteJobDomain, projectGhostwriteProgress,
  resumeGhostwriteJob as resumeGhostwriteJobDomain, transitionGhostwriteJob, validateDurableGhostwriteJob,
  validateGhostwriteReview, withGhostwriteCandidate,
} from './ghostwrite.ts';
import type { NovelChapter, NovelProject } from './models.ts';
import { nextNovelChapterOrdinal, withNovelProjectCompatibility } from './models.ts';
import type { NovelProjectRepository, NovelWorkspaceCas } from './repository_contract.ts';
import type {
  DurableNovelJob, WorkspaceBranchSnapshot, WorkspaceCheckout, WorkspaceLedgerCommit,
  WorkspaceNovelJobStore,
} from './workspace_codec.ts';
import {
  ancestryCommitIds, assertCas, assertCommandId, assertNoActiveNovelJob, chapterOrdinal, isGhostwriteJob,
  novelJobIsTerminal,
} from './workspace_codec.ts';
import { projectChapterPlots, projectedPlotStatus } from './workspace_projection.ts';
import type { WorkspaceScope, WorkspaceSession } from './workspace_transaction.ts';

type GhostwriteSession = Pick<WorkspaceSession,
  'branch' | 'checkout' | 'commitJob' | 'commits' | 'jobs' | 'loadProject' | 'saveJobs'>;
type GhostwriteStorage = WorkspaceScope<GhostwriteSession>;

interface LoadedGhostwriteJob {
  store: WorkspaceNovelJobStore;
  index: number;
  job: DurableGhostwriteJob;
  checkout: WorkspaceCheckout;
}

const replaceGhostwriteJob = (
  store: WorkspaceNovelJobStore, index: number, job: DurableGhostwriteJob,
): WorkspaceNovelJobStore => {
  const jobs: DurableNovelJob[] = store.jobs.slice();
  jobs[index] = validateDurableGhostwriteJob(job);
  return { version: 2, jobs };
};


const assertGhostwriteWorkspaceBinding = (
  id: string, job: DurableGhostwriteJob, checkout: WorkspaceCheckout,
): void => {
  if (job.projectId !== id || job.branchId !== checkout.branchId) {
    throw invalidInput('ghostwrite job 与当前 project/branch 不匹配');
  }
  assertCas(job.expectedCas, checkout);
};


const ghostwriteJobIndex = (store: WorkspaceNovelJobStore, jobId: string): number => {
  const index: number = store.jobs.findIndex(
    (job: DurableNovelJob): boolean => isGhostwriteJob(job) && job.jobId === jobId);
  if (index < 0) throw notFound('ghostwrite_job', jobId);
  return index;
};

const loadBoundGhostwriteJob = async (tx: GhostwriteSession, id: string, jobId: string, requireCas: boolean = true): Promise<LoadedGhostwriteJob> => {
  await tx.loadProject();
  const checkout: WorkspaceCheckout | null = await tx.checkout();
  if (checkout === null) throw invalidInput('checkout 无效');
  const store: WorkspaceNovelJobStore = await tx.jobs();
  const index: number = ghostwriteJobIndex(store, jobId);
  const stored: DurableNovelJob = store.jobs[index];
  if (!isGhostwriteJob(stored)) throw invalidInput('ghostwrite job 类型无效');
  const job: DurableGhostwriteJob = stored;
  if (requireCas) assertGhostwriteWorkspaceBinding(id, job, checkout);
  else if (job.projectId !== id || job.branchId !== checkout.branchId) {
    throw invalidInput('ghostwrite job 与当前 project/branch 不匹配');
  }
  return { store, index, job, checkout };
};

const loadClaimedGhostwriteJob = async (
  tx: GhostwriteSession, id: string, jobId: string, claim: GhostwriteClaimRef, now: number,
): Promise<LoadedGhostwriteJob> => {
  const loaded: LoadedGhostwriteJob = await loadBoundGhostwriteJob(tx, id, jobId);
  assertGhostwriteClaim(loaded.job, claim, now);
  return loaded;
};

export const createGhostwriteRepository = (storage: GhostwriteStorage): Pick<NovelProjectRepository,
  'listGhostwriteJobs' | 'loadGhostwriteJob' | 'startGhostwriteJob' | 'claimGhostwriteJob'
  | 'checkpointGhostwriteStage' | 'checkpointGhostwriteCandidate' | 'checkpointGhostwriteReview'
  | 'failGhostwriteJob' | 'pauseGhostwriteJob' | 'resumeGhostwriteJob' | 'reviseGhostwriteWithBrief'
  | 'retryGhostwriteJob' | 'cancelGhostwriteJob' | 'commitGhostwriteChapter' | 'ghostwriteProgress'> => {
  const repository = {
    listGhostwriteJobs: (id: string): Promise<DurableGhostwriteJob[]> =>
      storage.run(id, async (tx): Promise<DurableGhostwriteJob[]> => {
        await tx.loadProject();
        const checkout: WorkspaceCheckout | null = await tx.checkout();
        if (checkout === null) throw invalidInput('checkout 无效');
        return (await tx.jobs()).jobs.filter(
          (job: DurableNovelJob): job is DurableGhostwriteJob =>
            isGhostwriteJob(job) && job.branchId === checkout.branchId).slice();
      }),

    loadGhostwriteJob: (id: string, jobId: string): Promise<DurableGhostwriteJob> =>
      storage.run(id, async (tx): Promise<DurableGhostwriteJob> => {
        await tx.loadProject();
        const checkout: WorkspaceCheckout | null = await tx.checkout();
        if (checkout === null) throw invalidInput('checkout 无效');
        const store: WorkspaceNovelJobStore = await tx.jobs();
        const stored: DurableNovelJob = store.jobs[ghostwriteJobIndex(store, jobId)];
        if (!isGhostwriteJob(stored)) throw invalidInput('ghostwrite job 类型无效');
        const job: DurableGhostwriteJob = stored;
        if (job.projectId !== id || job.branchId !== checkout.branchId) {
          throw invalidInput('ghostwrite job 不属于当前 project/branch');
        }
        return job;
      }),

    startGhostwriteJob: (
      id: string, expected: NovelWorkspaceCas, jobId: string, planId: string,
      targetChapterCount: number, now: number,
    ): Promise<DurableGhostwriteJob> => storage.run(id, async (tx): Promise<DurableGhostwriteJob> => {
      const project: NovelProject = await tx.loadProject();
      const checkout: WorkspaceCheckout | null = await tx.checkout();
      if (checkout === null) throw invalidInput('checkout 无效');
      assertCas(expected, checkout);
      const branchState: WorkspaceBranchSnapshot = await tx.branch(checkout.branchId);
      if (branchState.unresolvedFromOrdinal !== null || branchState.plotStale) {
        throw invalidInput('当前分支存在 unresolved 或 plot stale，不能启动代笔');
      }
      const store: WorkspaceNovelJobStore = await tx.jobs();
      if (store.jobs.some((job: DurableNovelJob): boolean => job.jobId === jobId)) {
        throw invalidInput(`ghostwrite job 已存在: ${jobId}`);
      }
      assertNoActiveNovelJob(store, checkout.branchId);
      const startChapterOrdinal: number = nextNovelChapterOrdinal(project.chapters);
      const frozenPlan = freezeGhostwritePlan({
        planId,
        content: confirmedChapterPlanText(project.branchSettings),
        upcomingArc: project.branchSettings.upcomingArc?.beats,
        expectedCas: {
          branchId: expected.branchId,
          head: expected.head,
          treeDigest: expected.treeDigest,
        },
      });
      const job: DurableGhostwriteJob = makeGhostwriteJob({
        jobId,
        projectId: id,
        branchId: checkout.branchId,
        now,
        frozenPlan,
        targetChapterCount,
        startChapterOrdinal,
        modelPolicyAtStart: project.modelPolicy,
      });
      const nextStore: WorkspaceNovelJobStore = {
        version: 2,
        jobs: store.jobs.concat([job]),
      };
      await tx.saveJobs(nextStore);
      return job;
    }),

    claimGhostwriteJob: (
      id: string, jobId: string, token: string, now: number, leaseMs: number,
    ): Promise<DurableGhostwriteJob> => storage.run(id, async (tx): Promise<DurableGhostwriteJob> => {
      const loaded: LoadedGhostwriteJob = await loadBoundGhostwriteJob(tx, id, jobId);
      const claimed: DurableGhostwriteJob = claimGhostwriteJobDomain(
        loaded.job, token, now, leaseMs);
      await tx.saveJobs(replaceGhostwriteJob(loaded.store, loaded.index, claimed));
      return claimed;
    }),

    checkpointGhostwriteStage: (
      id: string, jobId: string, claim: GhostwriteClaimRef, nextStage: GhostwriteStage, now: number,
    ): Promise<DurableGhostwriteJob> => storage.run(id, async (tx): Promise<DurableGhostwriteJob> => {
      const loaded: LoadedGhostwriteJob = await loadClaimedGhostwriteJob(tx, id, jobId, claim, now);
      const next: DurableGhostwriteJob = transitionGhostwriteJob(loaded.job, nextStage, now);
      await tx.saveJobs(replaceGhostwriteJob(loaded.store, loaded.index, next));
      return next;
    }),

    checkpointGhostwriteCandidate: (
      id: string, jobId: string, claim: GhostwriteClaimRef,
      candidate: GhostwriteCandidate, now: number,
    ): Promise<DurableGhostwriteJob> => storage.run(id, async (tx): Promise<DurableGhostwriteJob> => {
      const loaded: LoadedGhostwriteJob = await loadClaimedGhostwriteJob(tx, id, jobId, claim, now);
      const next: DurableGhostwriteJob = withGhostwriteCandidate(loaded.job, candidate, now);
      await tx.saveJobs(replaceGhostwriteJob(loaded.store, loaded.index, next));
      return next;
    }),

    checkpointGhostwriteReview: (
      id: string, jobId: string, claim: GhostwriteClaimRef,
      review: GhostwriteReview, now: number,
    ): Promise<DurableGhostwriteJob> => storage.run(id, async (tx): Promise<DurableGhostwriteJob> => {
      const loaded: LoadedGhostwriteJob = await loadClaimedGhostwriteJob(tx, id, jobId, claim, now);
      let next: DurableGhostwriteJob;
      if (loaded.job.stage === 'planning' && loaded.job.candidate !== null &&
        loaded.job.frozenPlan !== null && loaded.job.review !== null) {
        const checked: GhostwriteReview = validateGhostwriteReview(
          review, loaded.job.candidate, loaded.job.frozenPlan);
        if (checked.nextPlan === null) throw invalidInput('planner checkpoint 缺少下一章计划');
        next = { ...loaded.job, stage: 'committing', review: checked, updatedAt: now };
      } else {
        next = applyGhostwriteReview(loaded.job, review, now);
      }
      await tx.saveJobs(replaceGhostwriteJob(loaded.store, loaded.index, next));
      return next;
    }),

    failGhostwriteJob: (
      id: string, jobId: string, claim: GhostwriteClaimRef, reason: string, now: number,
    ): Promise<DurableGhostwriteJob> => storage.run(id, async (tx): Promise<DurableGhostwriteJob> => {
      const loaded: LoadedGhostwriteJob = await loadBoundGhostwriteJob(tx, id, jobId, false);
      assertGhostwriteClaim(loaded.job, claim, now);
      const clean: string = reason.trim();
      if (clean.length === 0) throw invalidInput('ghostwrite 失败原因为空');
      const next: DurableGhostwriteJob = {
        ...loaded.job,
        stage: 'failed',
        resumeStage: loaded.job.stage,
        claim: null,
        failure: clean,
        updatedAt: now,
      };
      await tx.saveJobs(replaceGhostwriteJob(loaded.store, loaded.index, next));
      return next;
    }),

    pauseGhostwriteJob: (
      id: string, jobId: string, claim: GhostwriteClaimRef, now: number,
    ): Promise<DurableGhostwriteJob> => storage.run(id, async (tx): Promise<DurableGhostwriteJob> => {
      const loaded: LoadedGhostwriteJob = await loadClaimedGhostwriteJob(tx, id, jobId, claim, now);
      const next: DurableGhostwriteJob = pauseGhostwriteJobDomain(loaded.job, claim, now);
      await tx.saveJobs(replaceGhostwriteJob(loaded.store, loaded.index, next));
      return next;
    }),

    resumeGhostwriteJob: (
      id: string, jobId: string, now: number,
    ): Promise<DurableGhostwriteJob> => storage.run(id, async (tx): Promise<DurableGhostwriteJob> => {
      const loaded: LoadedGhostwriteJob = await loadBoundGhostwriteJob(tx, id, jobId);
      const next: DurableGhostwriteJob = resumeGhostwriteJobDomain(loaded.job, now);
      await tx.saveJobs(replaceGhostwriteJob(loaded.store, loaded.index, next));
      return next;
    }),

    reviseGhostwriteWithBrief: (
      id: string, jobId: string, brief: string, expected: NovelWorkspaceCas, now: number,
      expectedCandidateDigest?: string,
    ): Promise<DurableGhostwriteJob> => storage.run(id, async (tx): Promise<DurableGhostwriteJob> => {
      const loaded: LoadedGhostwriteJob = await loadBoundGhostwriteJob(tx, id, jobId);
      assertCas(expected, loaded.checkout);
      if (expectedCandidateDigest !== undefined &&
        expectedCandidateDigest !== (loaded.job.candidate?.digest ?? '')) {
        throw invalidInput('代写候选已变化，请重新查看后提交修订要求');
      }
      if (loaded.job.stage !== 'paused' && loaded.job.stage !== 'failed') {
        throw invalidInput('仅暂停或失败的代写任务可提交作者修订要求');
      }
      const clean: string = brief.trim();
      if (clean.length === 0) throw invalidInput('作者修订要求不能为空');
      if (clean.length > 2400) throw invalidInput('作者修订要求不能超过 2400 字');
      const next: DurableGhostwriteJob = validateDurableGhostwriteJob({
        ...loaded.job,
        authorBrief: clean,
        resumeStage: 'writing',
        rewriteCount: loaded.job.candidate?.attempt ?? 0,
        claim: null,
        failure: null,
        updatedAt: now,
      });
      await tx.saveJobs(replaceGhostwriteJob(loaded.store, loaded.index, next));
      return next;
    }),

    retryGhostwriteJob: (
      id: string, jobId: string, now: number,
    ): Promise<DurableGhostwriteJob> => storage.run(id, async (tx): Promise<DurableGhostwriteJob> => {
      const loaded: LoadedGhostwriteJob = await loadBoundGhostwriteJob(tx, id, jobId);
      if (loaded.job.stage !== 'failed') throw invalidInput('仅失败的 ghostwrite job 可重试');
      const retryStage: GhostwriteStage = loaded.job.resumeStage === null
        ? loaded.job.candidate === null ? 'writing' : 'reviewing'
        : loaded.job.resumeStage;
      if (retryStage === 'failed' || retryStage === 'cancelled' || retryStage === 'completed' ||
        retryStage === 'paused' || retryStage === 'waiting_user') {
        throw invalidInput('ghostwrite retry stage 无效');
      }
      const next: DurableGhostwriteJob = {
        ...loaded.job,
        stage: retryStage,
        resumeStage: null,
        claim: null,
        failure: null,
        updatedAt: now,
      };
      await tx.saveJobs(replaceGhostwriteJob(loaded.store, loaded.index, next));
      return next;
    }),

    cancelGhostwriteJob: (
      id: string, jobId: string, claim: GhostwriteClaimRef | null, now: number,
    ): Promise<DurableGhostwriteJob> => storage.run(id, async (tx): Promise<DurableGhostwriteJob> => {
      const loaded: LoadedGhostwriteJob = await loadBoundGhostwriteJob(tx, id, jobId, false);
      if (novelJobIsTerminal(loaded.job)) throw invalidInput('当前 ghostwrite job 已是终态');
      if (claim !== null) assertGhostwriteClaim(loaded.job, claim, now);
      else if (loaded.job.claim !== null && now < loaded.job.claim.leaseUntil) {
        throw invalidInput('ghostwrite owner lease 仍有效，取消须提供当前 claim');
      }
      const next: DurableGhostwriteJob = {
        ...loaded.job,
        stage: 'cancelled',
        resumeStage: null,
        claim: null,
        updatedAt: now,
      };
      await tx.saveJobs(replaceGhostwriteJob(loaded.store, loaded.index, next));
      return next;
    }),

    commitGhostwriteChapter: (
      id: string, jobId: string, claim: GhostwriteClaimRef, commandId: string, now: number,
    ): Promise<DurableGhostwriteJob> => storage.run(id, async (tx): Promise<DurableGhostwriteJob> => {
      assertCommandId(commandId);
      const store: WorkspaceNovelJobStore = await tx.jobs();
      const index: number = ghostwriteJobIndex(store, jobId);
      const stored: DurableNovelJob = store.jobs[index];
      if (!isGhostwriteJob(stored)) throw invalidInput('ghostwrite job 类型无效');
      const storedJob: DurableGhostwriteJob = stored;
      if (storedJob.progress.some(
        (entry: GhostwriteProgress): boolean => entry.receipt === commandId)) return storedJob;
      const project: NovelProject = await tx.loadProject();
      const checkout: WorkspaceCheckout | null = await tx.checkout();
      if (checkout === null) throw invalidInput('checkout 无效');
      assertGhostwriteWorkspaceBinding(id, storedJob, checkout);
      assertGhostwriteClaim(storedJob, claim, now);
      if (storedJob.stage !== 'committing' || storedJob.candidate === null ||
        storedJob.review === null || storedJob.frozenPlan === null) {
        throw invalidInput('ghostwrite job 尚未到可提交阶段');
      }
      const candidate: GhostwriteCandidate = storedJob.candidate;
      const review: GhostwriteReview = validateGhostwriteReview(
        storedJob.review, candidate, storedJob.frozenPlan);
      if (review.blocking || review.rewriteRequired) throw invalidInput('ghostwrite review 尚未通过');
      const receipt: string = ghostwriteReceipt(
        storedJob.jobId, candidate.chapterOrdinal, candidate.planId,
        candidate.planDigest, candidate.candidateId,
      );
      if (commandId !== receipt) throw invalidInput('ghostwrite commit receipt 与候选不匹配');
      if (candidate.chapterOrdinal !== storedJob.currentChapterOrdinal) {
        throw invalidInput('ghostwrite candidate ordinal 与 job cursor 不匹配');
      }
      if (confirmedChapterPlanText(project.branchSettings).trim() !== storedJob.frozenPlan.content) {
        throw invalidInput('ghostwrite 冻结计划已变化');
      }
      for (let i: number = 0; i < project.chapters.length; i++) {
        if (!project.chapters[i].discarded &&
          chapterOrdinal(project.chapters[i], i + 1) === candidate.chapterOrdinal) {
          throw invalidInput(`ghostwrite 目标章节已存在: ${candidate.chapterOrdinal}`);
        }
      }
      const finalChapter: boolean = storedJob.currentChapterOrdinal === storedJob.endChapterOrdinal;
      if (!finalChapter && review.nextPlan === null) {
        throw invalidInput('非最终 ghostwrite 章节缺少下一章计划');
      }
      const chapter: NovelChapter = {
        id: `chapter-${candidate.chapterOrdinal.toString().padStart(3, '0')}-ghostwrite-${jobId}`,
        title: candidate.title,
        content: candidate.content,
        createdAt: now,
        updatedAt: now,
        discarded: false,
      };
      const nextPlanContent: string = finalChapter ? '' : (review.nextPlan ?? '').trim();
      const branch: WorkspaceBranchSnapshot = await tx.branch(checkout.branchId);
      const nextProject: NovelProject = projectChapterPlots(project, withNovelProjectCompatibility({
        ...project,
        chapters: project.chapters.concat([chapter]),
        branchSettings: {
          ...project.branchSettings,
          thisChapterPlan: nextPlanContent,
          chapterContract: undefined,
          suggestedChapterCount: undefined,
        },
        revision: project.revision + 1,
        updatedAt: Math.max(project.updatedAt, now),
      }), branch);
      const { unresolved, stale } = projectedPlotStatus(nextProject);
      const plotContent: string | null = branch.plotContent;
      const committedStore: WorkspaceNovelJobStore = await tx.commitJob({
        project: nextProject,
        mutation: 'generation_commit',
        branchId: checkout.branchId,
        receipt,
        unresolvedFromOrdinal: unresolved,
        plotStale: stale,
        makeUndo: true,
        changedChapterOrdinal: candidate.chapterOrdinal,
        plotContentOverride: plotContent
      }, (nextCas): WorkspaceNovelJobStore => {
        const progress: GhostwriteProgress = {
          chapterOrdinal: candidate.chapterOrdinal,
          planId: candidate.planId,
          planDigest: candidate.planDigest,
          candidateId: candidate.candidateId,
          receipt,
          commitId: nextCas.head,
          branchId: checkout.branchId,
        };
        const nextFrozenPlan = finalChapter ? null : freezeGhostwritePlan({
          planId: `${storedJob.jobId}:plan:${storedJob.currentChapterOrdinal + 1}`,
          content: nextPlanContent,
          upcomingArc: nextProject.branchSettings.upcomingArc?.beats,
          expectedCas: nextCas,
        });
        const nextJob: DurableGhostwriteJob = validateDurableGhostwriteJob({
          ...storedJob,
          stage: finalChapter ? 'completed' : 'planning',
          resumeStage: null,
          expectedCas: nextCas,
          frozenPlan: nextFrozenPlan,
          candidate: null,
          review: null,
          rewriteCount: 0,
          currentChapterOrdinal: finalChapter
            ? storedJob.currentChapterOrdinal : storedJob.currentChapterOrdinal + 1,
          claim: finalChapter ? null : storedJob.claim,
          progress: storedJob.progress.concat([progress]),
          failure: null,
          updatedAt: now,
        });
        return replaceGhostwriteJob(store, index, nextJob);
      });
      return committedStore.jobs[index] as DurableGhostwriteJob;
    }),

    ghostwriteProgress: (
      id: string, jobId: string,
    ): Promise<GhostwriteProgress[]> => storage.run(id, async (tx): Promise<GhostwriteProgress[]> => {
      await tx.loadProject();
      const checkout: WorkspaceCheckout | null = await tx.checkout();
      if (checkout === null) throw invalidInput('checkout 无效');
      const store: WorkspaceNovelJobStore = await tx.jobs();
      const stored: DurableNovelJob = store.jobs[ghostwriteJobIndex(store, jobId)];
      if (!isGhostwriteJob(stored)) throw invalidInput('ghostwrite job 类型无效');
      const job: DurableGhostwriteJob = stored;
      if (job.projectId !== id || job.branchId !== checkout.branchId) return [];
      const commits: WorkspaceLedgerCommit[] = await tx.commits();
      return projectGhostwriteProgress(job, ancestryCommitIds(commits, checkout.head));
    })
  };
  return repository;
};
