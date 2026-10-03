import type { DurableGhostwriteJob, GhostwriteStage } from './ghostwrite.ts';

export interface NovelGhostwriteReportChapter {
  ordinal: number;
  receipt: string;
  commitId: string;
}
export interface NovelGhostwriteReport {
  jobId: string;
  branchId: string;
  stage: GhostwriteStage;
  targetChapterCount: number;
  startOrdinal: number;
  endOrdinal: number;
  chapters: NovelGhostwriteReportChapter[];
  failure: string | null;
  updatedAt: number;
}
export const ghostwriteJobReport = (job: DurableGhostwriteJob): NovelGhostwriteReport => ({
  jobId: job.jobId, branchId: job.branchId, stage: job.stage, targetChapterCount: job.targetChapterCount,
  startOrdinal: job.startChapterOrdinal, endOrdinal: job.endChapterOrdinal,
  chapters: job.progress.map(progress => ({ ordinal: progress.chapterOrdinal, receipt: progress.receipt,
    commitId: progress.commitId })), failure: job.failure, updatedAt: job.updatedAt,
});
