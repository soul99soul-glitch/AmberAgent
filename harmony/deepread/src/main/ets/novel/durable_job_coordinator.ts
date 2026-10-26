import { selectedPolishTargets, frozenPolishContext } from './polish_context.ts';
import type { NovelPolishStartPreview } from './polish_context.ts';
import { projectPolishOutcomes } from './polish.ts';
import type { PolishChapterResult } from './polish.ts';
import { resolveNovelDefaultTarget } from './standalone_defaults.ts';
import type { NovelModelDefaults } from './standalone_defaults.ts';
import { novelId, novelChapterOrdinal } from './models.ts';
import type { NovelProject } from './models.ts';
import { invalidInput } from './error.ts';
import type { NovelModelRunning } from './model_running.ts';
import type { NovelProjectRepository, NovelWorkspaceStatus, NovelWorkspaceCas } from './repository.ts';
import type { DurableGhostwriteJob, GhostwriteClaimRef, GhostwriteStage } from './ghostwrite.ts';
import { createGhostwriteRunner } from './ghostwrite_runner.ts';
import type { GhostwriteRunner } from './ghostwrite_runner.ts';
import type { DurablePolishJob, PolishContextOptions, PolishStage } from './polish.ts';
import { createPolishRunner } from './polish_runner.ts';
import type { PolishRunner } from './polish_runner.ts';
import { makeGhostwriteStartPreview, assertGhostwriteStartPreview } from './ghostwrite_start_preview.ts';
import type { NovelGhostwriteStartPreview } from './ghostwrite_start_preview.ts';
import { ghostwriteJobReport } from './job_report.ts';
import type { NovelGhostwriteReport } from './job_report.ts';
import type { NovelPolishEffects } from './creation.ts';

interface BackgroundPolishOwner {
  token: string;
  stopRequested: boolean;
}

const GHOSTWRITE_LEASE_MS: number = 12 * 60 * 60 * 1000;
const POLISH_LEASE_MS: number = 12 * 60 * 60 * 1000;

interface NovelDurableJobDeps {
  repository: NovelProjectRepository;
  modelRunning: NovelModelRunning;
  loadModelDefaults: () => Promise<NovelModelDefaults>;
  loadPolishPreference?: () => Promise<string>;
  polishEffects?: NovelPolishEffects;
  nowMs: () => number;
}

export class NovelDurableJobCoordinator {
  private repository: NovelProjectRepository;
  private modelRunning: NovelModelRunning;
  private loadModelDefaults: () => Promise<NovelModelDefaults>;
  private ghostwriteRunner: GhostwriteRunner;
  private polishRunner: PolishRunner;
  private polishEffects: NovelPolishEffects | null;
  private nowMs: () => number;
  // 仅防止同一进程重复驱动；durable job/repository 始终是唯一事实源。
  private drivingGhostwriteJobs: Map<string, Promise<void>> = new Map();
  private drivingPolishJobs: Map<string, Promise<void>> = new Map();
  private backgroundPolishOwners: Map<string, BackgroundPolishOwner> = new Map();

  constructor(deps: NovelDurableJobDeps) {
    this.repository = deps.repository;
    this.modelRunning = deps.modelRunning;
    this.loadModelDefaults = deps.loadModelDefaults;
    this.ghostwriteRunner = createGhostwriteRunner({
      repository: deps.repository,
      modelRunning: deps.modelRunning,
      resolveModelTarget: async (policy, role) => resolveNovelDefaultTarget(policy, await this.loadModelDefaults(), role),
      nowMs: deps.nowMs,
      makeId: (prefix: string): string => `${prefix}:${novelId()}`,
    });
    this.polishRunner = createPolishRunner({
      loadPolishPreference: deps.loadPolishPreference,
      repository: deps.repository,
      modelRunning: deps.modelRunning,
      resolveModelTarget: async (policy, role) => resolveNovelDefaultTarget(policy, await this.loadModelDefaults(), role),
      shouldYieldToSystem: (projectId: string, jobId: string, ownerToken: string): boolean => {
        const owner: BackgroundPolishOwner | undefined = this.backgroundPolishOwners.get(`${projectId}:${jobId}`);
        return owner !== undefined && owner.token === ownerToken && owner.stopRequested;
      },
      nowMs: deps.nowMs,
      makeId: (prefix: string): string => `${prefix}:${novelId()}`,
    });
    this.polishEffects = deps.polishEffects ?? null;
    this.nowMs = deps.nowMs;
  }

  isRunning(projectId: string): boolean {
    const prefix: string = `${projectId}:`;
    return Array.from(this.drivingGhostwriteJobs.keys()).some((key: string): boolean => key.startsWith(prefix)) ||
      Array.from(this.drivingPolishJobs.keys()).some((key: string): boolean => key.startsWith(prefix));
  }

  recover(projectId: string): void {
    void this.recoverGhostwriteDriver(projectId).catch((): void => {
      // Durable job remains visible as paused/failed; open 本身不因后台接管失败而隐藏项目。
    });
    void this.recoverPolishDriver(projectId).catch((): void => {
      // Durable polish remains visible; foreground recovery failure must not hide the project.
    });
  }

  async cancelAndDrain(projectId: string): Promise<void> {
    const ghostwriteJobs: DurableGhostwriteJob[] = await this.repository.listGhostwriteJobs(projectId);
    for (const job of ghostwriteJobs) {
      if (job.stage !== 'completed' && job.stage !== 'cancelled') await this.cancelGhostwrite(projectId, job.jobId);
    }
    const polishJobs: DurablePolishJob[] = await this.repository.listPolishJobs(projectId);
    for (const job of polishJobs) {
      if (job.stage !== 'completed' && job.stage !== 'cancelled') await this.cancelPolish(projectId, job.jobId);
    }
    const prefix: string = `${projectId}:`;
    const drivers: Promise<void>[] = [];
    this.drivingGhostwriteJobs.forEach((driver: Promise<void>, key: string): void => {
      if (key.startsWith(prefix)) drivers.push(driver);
    });
    this.drivingPolishJobs.forEach((driver: Promise<void>, key: string): void => {
      if (key.startsWith(prefix)) drivers.push(driver);
    });
    await Promise.all(drivers);
  }

  async ghostwriteJob(projectId: string): Promise<DurableGhostwriteJob | null> {
    const jobs: DurableGhostwriteJob[] = await this.repository.listGhostwriteJobs(projectId);
    jobs.sort((left: DurableGhostwriteJob, right: DurableGhostwriteJob): number =>
      right.updatedAt - left.updatedAt);
    const active: DurableGhostwriteJob | undefined = jobs.find(
      (job: DurableGhostwriteJob): boolean => job.stage !== 'completed' && job.stage !== 'cancelled');
    if (active === undefined) return null;
    const progress = await this.repository.ghostwriteProgress(projectId, active.jobId);
    return { ...active, progress };
  }

  async prepareGhostwriteStart(projectId: string, targetChapterCount: number): Promise<NovelGhostwriteStartPreview> {
    const snapshot = await this.repository.readWorkspaceSnapshot(projectId);
    this.assertWritingContextReady(snapshot.status);
    return makeGhostwriteStartPreview(snapshot, targetChapterCount);
  }

  async latestGhostwriteReport(projectId: string): Promise<NovelGhostwriteReport | null> {
    const jobs: DurableGhostwriteJob[] = (await this.repository.listGhostwriteJobs(projectId)).filter(
      job => job.stage === 'completed' || job.stage === 'cancelled');
    jobs.sort((left, right): number => right.updatedAt - left.updatedAt);
    if (jobs.length === 0) return null;
    const progress = await this.repository.ghostwriteProgress(projectId, jobs[0].jobId);
    return ghostwriteJobReport({ ...jobs[0], progress });
  }

  async startGhostwrite(
    projectId: string, targetChapterCount: number, preview?: NovelGhostwriteStartPreview,
  ): Promise<DurableGhostwriteJob> {
    const snapshot = await this.repository.readWorkspaceSnapshot(projectId);
    this.assertWritingContextReady(snapshot.status);
    const frozen: NovelGhostwriteStartPreview = preview ?? makeGhostwriteStartPreview(snapshot, targetChapterCount);
    assertGhostwriteStartPreview(snapshot, targetChapterCount, frozen);
    await this.validateGhostwriteModels(projectId, snapshot.project);
    const jobId: string = novelId();
    const job: DurableGhostwriteJob = await this.repository.startGhostwriteJob(
      projectId, frozen.expectedCas, jobId, `${jobId}:plan:${snapshot.project.chapters.length + 1}`,
      targetChapterCount, this.nowMs());
    this.launchGhostwriteDriver(projectId, job.jobId);
    return job;
  }

  async pauseGhostwrite(projectId: string, jobId: string): Promise<DurableGhostwriteJob> {
    const job: DurableGhostwriteJob = await this.repository.loadGhostwriteJob(projectId, jobId);
    const claim: GhostwriteClaimRef = await this.ensureGhostwriteClaim(projectId, job);
    const paused: DurableGhostwriteJob =
      await this.repository.pauseGhostwriteJob(projectId, jobId, claim, this.nowMs());
    this.ghostwriteRunner.cancel(projectId, jobId);
    return paused;
  }

  async resumeGhostwrite(projectId: string, jobId: string): Promise<DurableGhostwriteJob> {
    await this.drivingGhostwriteJobs.get(`${projectId}:${jobId}`);
    const resumed: DurableGhostwriteJob = await this.repository.resumeGhostwriteJob(
      projectId, jobId, this.nowMs());
    this.launchGhostwriteDriver(projectId, jobId);
    return resumed;
  }

  async reviseGhostwriteWithBrief(
    projectId: string, jobId: string, brief: string, expectedCas?: NovelWorkspaceCas,
    expectedCandidateDigest?: string,
  ): Promise<DurableGhostwriteJob> {
    await this.drivingGhostwriteJobs.get(`${projectId}:${jobId}`);
    const snapshot = await this.repository.readWorkspaceSnapshot(projectId);
    await this.validateGhostwriteModels(projectId, snapshot.project);
    const revised = await this.repository.reviseGhostwriteWithBrief(
      projectId, jobId, brief, expectedCas ?? snapshot.status.cas, this.nowMs(), expectedCandidateDigest);
    return revised.stage === 'paused'
      ? this.resumeGhostwrite(projectId, jobId) : this.retryGhostwrite(projectId, jobId);
  }

  async retryGhostwrite(projectId: string, jobId: string): Promise<DurableGhostwriteJob> {
    await this.drivingGhostwriteJobs.get(`${projectId}:${jobId}`);
    const retried: DurableGhostwriteJob = await this.repository.retryGhostwriteJob(
      projectId, jobId, this.nowMs());
    this.launchGhostwriteDriver(projectId, jobId);
    return retried;
  }

  async cancelGhostwrite(projectId: string, jobId: string): Promise<DurableGhostwriteJob> {
    const job: DurableGhostwriteJob = await this.repository.loadGhostwriteJob(projectId, jobId);
    const now: number = this.nowMs();
    const claim: GhostwriteClaimRef | null = job.claim !== null && now < job.claim.leaseUntil
      ? { token: job.claim.token, epoch: job.claim.epoch } : null;
    const cancelled: DurableGhostwriteJob =
      await this.repository.cancelGhostwriteJob(projectId, jobId, claim, this.nowMs());
    this.ghostwriteRunner.cancel(projectId, jobId);
    return cancelled;
  }

  async polishJob(projectId: string, requestedJobId: string = ''): Promise<DurablePolishJob | null> {
    if (requestedJobId.length > 0) {
      const requested: DurablePolishJob = await this.repository.loadPolishJob(projectId, requestedJobId);
      const progress = await this.repository.polishProgress(projectId, requested.jobId);
      return { ...requested, progress };
    }
    const jobs: DurablePolishJob[] = await this.repository.listPolishJobs(projectId);
    jobs.sort((left: DurablePolishJob, right: DurablePolishJob): number =>
      right.updatedAt - left.updatedAt);
    const active: DurablePolishJob | undefined = jobs.find(
      (job: DurablePolishJob): boolean => job.stage !== 'completed' && job.stage !== 'cancelled');
    const selected: DurablePolishJob | undefined = active ?? jobs[0];
    if (selected === undefined) return null;
    const progress = await this.repository.polishProgress(projectId, selected.jobId);
    return { ...selected, progress };
  }

  async previewPolishSelected(
    projectId: string, chapterIds: string[], contextOptions: PolishContextOptions,
  ): Promise<NovelPolishStartPreview> {
    const snapshot = await this.repository.readWorkspaceSnapshot(projectId);
    this.assertWritingContextReady(snapshot.status);
    const targets = selectedPolishTargets(snapshot.project, chapterIds);
    const preference = (snapshot.project.polishPreference ?? '').trim();
    return { cas: snapshot.status.cas, chapterIds: targets.map(target => target.id), targets,
      polishPreference: preference, preferenceSource: preference ? 'project' : 'none', contextOptions,
      contextSnapshot: frozenPolishContext(snapshot.project, snapshot.status.activeBranchId, contextOptions,
        targets, snapshot.project.authorPlot ?? null) };
  }

  async startPolishSelected(
    projectId: string, chapterIds: string[], contextOptions: PolishContextOptions, expectedCas?: NovelWorkspaceCas,
  ): Promise<DurablePolishJob> {
    const snapshot = await this.repository.readWorkspaceSnapshot(projectId);
    const targets = selectedPolishTargets(snapshot.project, chapterIds);
    await this.validateGhostwriteModels(projectId, snapshot.project);
    this.assertWritingContextReady(snapshot.status);
    const job = await this.repository.startPolishJob(projectId, expectedCas ?? snapshot.status.cas, novelId(),
      targets[0].ordinal, targets[targets.length - 1].ordinal, contextOptions, this.nowMs(), targets.map(target => target.ordinal));
    await this.syncPolishEffects(job, true);
    this.launchPolishDriver(projectId, job.jobId, null);
    return job;
  }

  async polishOutcomes(projectId: string, jobId: string): Promise<PolishChapterResult[]> {
    return projectPolishOutcomes(await this.repository.loadPolishJob(projectId, jobId));
  }

  async startPolish(
    projectId: string, fromOrdinal: number, toOrdinal: number, contextOptions: PolishContextOptions,
  ): Promise<DurablePolishJob> {
    const project: NovelProject = await this.repository.loadProject(projectId);
    await this.validateGhostwriteModels(projectId, project);
    const status: NovelWorkspaceStatus = await this.repository.workspaceStatus(projectId);
    this.assertWritingContextReady(status);
    const job: DurablePolishJob = await this.repository.startPolishJob(
      projectId, status.cas, novelId(), fromOrdinal, toOrdinal, contextOptions, this.nowMs());
    await this.syncPolishEffects(job, true);
    this.launchPolishDriver(projectId, job.jobId, null);
    return job;
  }

  async startSelectedPolish(
    projectId: string, ordinals: number[], contextOptions: PolishContextOptions,
  ): Promise<DurablePolishJob> {
    const snapshot = await this.repository.readWorkspaceSnapshot(projectId);
    await this.validateGhostwriteModels(projectId, snapshot.project);
    this.assertWritingContextReady(snapshot.status);
    const selected: number[] = ordinals.slice().sort((left: number, right: number): number => left - right);
    if (selected.length === 0) throw invalidInput('至少选择一个润色章节');
    const job = await this.repository.startPolishJob(projectId, snapshot.status.cas, novelId(),
      selected[0], selected[selected.length - 1], contextOptions, this.nowMs(), selected);
    await this.syncPolishEffects(job, true);
    this.launchPolishDriver(projectId, job.jobId, null);
    return job;
  }

  async startChapterPolish(
    projectId: string, chapterId: string, contextOptions: PolishContextOptions,
  ): Promise<DurablePolishJob> {
    const snapshot = await this.repository.readWorkspaceSnapshot(projectId);
    const index: number = snapshot.project.chapters.findIndex(chapter => chapter.id === chapterId);
    if (index < 0 || snapshot.project.chapters[index].discarded) throw invalidInput('润色章节不存在或已废弃');
    const ordinal: number = novelChapterOrdinal(snapshot.project.chapters[index], index + 1);
    await this.validateGhostwriteModels(projectId, snapshot.project);
    this.assertWritingContextReady(snapshot.status);
    const job = await this.repository.startPolishJob(projectId, snapshot.status.cas, novelId(),
      ordinal, ordinal, contextOptions, this.nowMs());
    await this.syncPolishEffects(job, true);
    this.launchPolishDriver(projectId, job.jobId, null);
    return job;
  }

  async pausePolish(projectId: string, jobId: string): Promise<DurablePolishJob> {
    const job: DurablePolishJob = await this.repository.loadPolishJob(projectId, jobId);
    const claim: GhostwriteClaimRef = await this.ensurePolishClaim(projectId, job);
    const paused: DurablePolishJob = await this.repository.pausePolishJob(
      projectId, jobId, claim, this.nowMs());
    this.polishRunner.cancel(projectId, jobId);
    await this.syncPolishEffects(paused, false);
    return paused;
  }

  async resumePolish(projectId: string, jobId: string): Promise<DurablePolishJob> {
    await this.drivingPolishJobs.get(`${projectId}:${jobId}`);
    const resumed: DurablePolishJob = await this.repository.resumePolishJob(
      projectId, jobId, this.nowMs());
    await this.syncPolishEffects(resumed, true);
    this.launchPolishDriver(projectId, jobId, null, true);
    return resumed;
  }

  async retryPolish(projectId: string, jobId: string, chapterIds?: string[]): Promise<DurablePolishJob> {
    await this.drivingPolishJobs.get(`${projectId}:${jobId}`);
    const retried: DurablePolishJob = await this.repository.retryPolishJob(
      projectId, jobId, this.nowMs(), chapterIds);
    await this.syncPolishEffects(retried, true);
    this.launchPolishDriver(projectId, jobId, null, true);
    return retried;
  }

  async cancelPolish(projectId: string, jobId: string): Promise<DurablePolishJob> {
    const cancelled: DurablePolishJob = await this.repository.cancelPolishJob(
      projectId, jobId, this.nowMs());
    this.polishRunner.cancel(projectId, jobId);
    await this.syncPolishEffects(cancelled, false);
    return cancelled;
  }

  async drivePolishInBackground(
    projectId: string, branchId: string, jobId: string, maxChapters: number = 1,
  ): Promise<DurablePolishJob> {
    const key: string = `${projectId}:${jobId}`;
    const existing: BackgroundPolishOwner | undefined = this.backgroundPolishOwners.get(key);
    const owner: BackgroundPolishOwner = existing ?? { token: novelId(), stopRequested: false };
    // onWorkStop may arrive while the first repository read or file-lock open is pending.
    if (existing === undefined) this.backgroundPolishOwners.set(key, owner);
    try {
      const job: DurablePolishJob = await this.repository.loadPolishJob(projectId, jobId);
      if (job.branchId !== branchId) throw invalidInput('polish 后台任务分支不匹配');
      if (existing !== undefined) return job;
      return await this.runPolishDriver(projectId, jobId, maxChapters, owner);
    } finally {
      if (existing === undefined) this.backgroundPolishOwners.delete(key);
    }
  }

  async yieldPolishForSystem(projectId: string, jobId: string): Promise<DurablePolishJob> {
    const owner: BackgroundPolishOwner | undefined = this.backgroundPolishOwners.get(`${projectId}:${jobId}`);
    if (owner !== undefined) owner.stopRequested = true;
    const job: DurablePolishJob = await this.repository.loadPolishJob(projectId, jobId);
    // A scheduler callback that lost the driver lock must not stop the foreground owner.
    if (!this.canDrivePolishStage(job.stage) || owner === undefined || job.claim?.token !== owner.token) return job;
    const claim: GhostwriteClaimRef = { token: owner.token, epoch: job.claim.epoch };
    const waiting: DurablePolishJob = await this.repository.yieldPolishJob(
      projectId, jobId, claim, this.nowMs());
    this.polishRunner.cancel(projectId, jobId);
    await this.syncPolishEffects(waiting, true);
    return waiting;
  }

  async validateGhostwriteModels(projectId: string, loadedProject: NovelProject | null = null): Promise<void> {
    const project: NovelProject = loadedProject ?? await this.repository.loadProject(projectId);
    const defaults: NovelModelDefaults = await this.loadModelDefaults();
    const writingTarget = resolveNovelDefaultTarget(project.modelPolicy, defaults, 'writing');
    const reviewTarget = resolveNovelDefaultTarget(project.modelPolicy, defaults, 'review');
    const stateTarget = resolveNovelDefaultTarget(project.modelPolicy, defaults, 'stateSync');
    await this.modelRunning.validate(writingTarget, projectId);
    if (JSON.stringify(reviewTarget) !== JSON.stringify(writingTarget)) {
      await this.modelRunning.validate(reviewTarget, projectId);
    }
    if (JSON.stringify(stateTarget) !== JSON.stringify(reviewTarget) &&
      JSON.stringify(stateTarget) !== JSON.stringify(writingTarget)) {
      await this.modelRunning.validate(stateTarget, projectId);
    }
  }

  private async ensureGhostwriteClaim(
    projectId: string, job: DurableGhostwriteJob,
  ): Promise<GhostwriteClaimRef> {
    const now: number = this.nowMs();
    if (job.claim !== null && now < job.claim.leaseUntil) {
      return { token: job.claim.token, epoch: job.claim.epoch };
    }
    const claimed: DurableGhostwriteJob = await this.repository.claimGhostwriteJob(
      projectId, job.jobId, novelId(), now, GHOSTWRITE_LEASE_MS);
    if (claimed.claim === null) throw invalidInput('ghostwrite claim 未建立');
    return { token: claimed.claim.token, epoch: claimed.claim.epoch };
  }

  private canDriveGhostwriteStage(stage: GhostwriteStage): boolean {
    return stage !== 'paused' && stage !== 'waiting_user' && stage !== 'failed' &&
      stage !== 'cancelled' && stage !== 'completed';
  }

  private launchGhostwriteDriver(projectId: string, jobId: string): void {
    const key: string = `${projectId}:${jobId}`;
    if (this.drivingGhostwriteJobs.has(key)) return;
    const ownerToken: string = novelId();
    const driving: Promise<void> = this.ghostwriteRunner.run(projectId, jobId, ownerToken, GHOSTWRITE_LEASE_MS)
      .catch((): void => {
        // runner 已将可归属错误写回 durable failed；owner 被暂停/取消抢占时保留新 durable 状态。
      })
      .then((): void => {})
      .finally((): void => { this.drivingGhostwriteJobs.delete(key); });
    this.drivingGhostwriteJobs.set(key, driving);
  }

  private async recoverGhostwriteDriver(projectId: string): Promise<void> {
    const job: DurableGhostwriteJob | null = await this.ghostwriteJob(projectId);
    if (job === null || !this.canDriveGhostwriteStage(job.stage)) return;
    const key: string = `${projectId}:${job.jobId}`;
    if (this.drivingGhostwriteJobs.has(key)) return;
    const now: number = this.nowMs();
    if (job.claim !== null && now < job.claim.leaseUntil) {
      const staleClaim: GhostwriteClaimRef = { token: job.claim.token, epoch: job.claim.epoch };
      await this.repository.pauseGhostwriteJob(projectId, job.jobId, staleClaim, now);
      await this.repository.resumeGhostwriteJob(projectId, job.jobId, now);
    }
    this.launchGhostwriteDriver(projectId, job.jobId);
  }

  private async ensurePolishClaim(
    projectId: string, job: DurablePolishJob,
  ): Promise<GhostwriteClaimRef> {
    const now: number = this.nowMs();
    if (job.claim !== null && now < job.claim.leaseUntil) {
      return { token: job.claim.token, epoch: job.claim.epoch };
    }
    const claimed: DurablePolishJob = await this.repository.claimPolishJob(
      projectId, job.jobId, novelId(), now, POLISH_LEASE_MS);
    if (claimed.claim === null) throw invalidInput('polish claim 未建立');
    return { token: claimed.claim.token, epoch: claimed.claim.epoch };
  }

  private canDrivePolishStage(stage: PolishStage): boolean {
    return stage !== 'waiting_system' && stage !== 'paused' && stage !== 'failed' &&
      stage !== 'cancelled' && stage !== 'completed';
  }

  private launchPolishDriver(
    projectId: string, jobId: string, maxChapters: number | null, waitForOwner: boolean = false,
  ): void {
    const key: string = `${projectId}:${jobId}`;
    if (this.drivingPolishJobs.has(key)) return;
    const driving: Promise<void> = this.runPolishDriver(projectId, jobId, maxChapters, null, waitForOwner)
      .then((): void => {})
      .catch((): void => {
        // runner owns durable failure; a stale foreground owner must not overwrite a newer state.
      })
      .finally((): void => { this.drivingPolishJobs.delete(key); });
    this.drivingPolishJobs.set(key, driving);
  }

  private async recoverPolishDriver(projectId: string): Promise<void> {
    const job: DurablePolishJob | null = await this.polishJob(projectId);
    if (job === null) return;
    if (job.stage === 'waiting_system') {
      await this.syncPolishEffects(job, true);
      return;
    }
    if (!this.canDrivePolishStage(job.stage)) {
      await this.syncPolishEffects(job, false);
      return;
    }
    const key: string = `${projectId}:${job.jobId}`;
    if (this.drivingPolishJobs.has(key)) return;
    this.launchPolishDriver(projectId, job.jobId, null);
  }

  private async runPolishDriver(
    projectId: string, jobId: string, maxChapters: number | null,
    background: BackgroundPolishOwner | null, waitForOwner: boolean = false,
  ): Promise<DurablePolishJob> {
    const drive = async (): Promise<DurablePolishJob> => {
      let job: DurablePolishJob = await this.repository.loadPolishJob(projectId, jobId);
      if (background !== null && job.stage === 'waiting_system' && !background.stopRequested) {
        job = await this.repository.resumePolishJob(projectId, jobId, this.nowMs());
      }
      if (!this.canDrivePolishStage(job.stage)) {
        await this.syncPolishEffects(job, job.stage === 'waiting_system');
        return job;
      }
      // The process-owned file lock, not a context-local Map, proves the old driver is gone.
      // A dead process releases this lock even while its durable 12-hour lease is still valid.
      const now: number = this.nowMs();
      if (job.claim !== null && now < job.claim.leaseUntil) {
        const staleClaim: GhostwriteClaimRef = { token: job.claim.token, epoch: job.claim.epoch };
        await this.repository.pausePolishJob(projectId, jobId, staleClaim, now);
        job = await this.repository.resumePolishJob(projectId, jobId, now);
      }
      const owner: string = background?.token ?? novelId();
      const completed = await this.polishRunner.run(projectId, jobId, owner, POLISH_LEASE_MS, maxChapters);
      await this.syncPolishEffects(completed, completed.stage === 'waiting_system');
      return completed;
    };
    // Explicit resume/retry must replace the invalidated owner after it exits; passive opens
    // and scheduler callbacks only observe a busy owner and never queue duplicate model work.
    const result: DurablePolishJob | null = waitForOwner
      ? await this.repository.withPolishDriverLock(projectId, jobId, drive)
      : await this.repository.tryWithPolishDriverLock(projectId, jobId, drive);
    // A live owner is already doing the work; do not queue a second model request.
    return result ?? await this.repository.loadPolishJob(projectId, jobId);
  }

  private async syncPolishEffects(job: DurablePolishJob, schedule: boolean): Promise<void> {
    if (this.polishEffects === null) return;
    await this.polishEffects.notify(job);
    if (schedule) await this.polishEffects.schedule(job);
    else await this.polishEffects.cancel(job);
  }

  private assertWritingContextReady(status: NovelWorkspaceStatus): void {
    if (status.plotStale || status.unresolvedFromOrdinal !== null) {
      throw invalidInput('正文已变更，请先同步剧情并确认后续章节影响，再开始写作');
    }
  }
}
