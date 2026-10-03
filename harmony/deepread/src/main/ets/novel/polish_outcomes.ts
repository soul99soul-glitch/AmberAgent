import type { DurablePolishJob, PolishChapterResult, PolishChapterOutcome, PolishProgress } from './polish.ts';
import type { WorkspaceCas } from './workspace_history.ts';
import { invalidInput } from './error.ts';

export const projectPolishOutcomes = (job: DurablePolishJob): PolishChapterResult[] => job.outcomes?.map(item => ({ ...item }))
  ?? job.targets.map((target, index) => ({ chapterId: target.id, chapterOrdinal: target.ordinal,
    status: job.progress.some(item => item.chapterId === target.id) ? 'success' : job.stage === 'failed' && index === job.cursor ? 'failed' : 'unprocessed',
    message: job.stage === 'failed' && index === job.cursor ? job.failure : null, updatedAt: job.updatedAt }));

export const advancePolishOutcome = (
  job: DurablePolishJob, status: Exclude<PolishChapterOutcome, 'unprocessed'>,
  message: string | null, now: number, nextCas: WorkspaceCas = job.expectedCas,
  progress: PolishProgress[] = job.progress,
): DurablePolishJob => {
  const target = job.targets[job.cursor];
  if (target === undefined) throw invalidInput('润色当前章节不存在');
  const outcomes = projectPolishOutcomes(job).map(item => item.chapterId === target.id
    ? { ...item, status, message, updatedAt: now } : item);
  const next = outcomes.findIndex(item => item.status === 'unprocessed'
    && (job.retryChapterIds === undefined || job.retryChapterIds.includes(item.chapterId)));
  return { ...job, outcomes, progress, cursor: next < 0 ? job.targets.length : next,
    stage: next < 0 ? 'completed' : 'queued', resumeStage: null, expectedCas: nextCas,
    claim: next < 0 ? null : job.claim, candidate: null, review: null, rewriteCount: 0,
    failure: null, updatedAt: now };
};

export const retryPolishOutcomes = (job: DurablePolishJob, now: number, chapterIds?: string[]): DurablePolishJob => {
  if (!['completed', 'cancelled', 'failed'].includes(job.stage)) throw invalidInput('润色仍在运行或暂停，请先完成或取消当前批次');
  const outcomes = projectPolishOutcomes(job);
  const eligible = outcomes.filter(item => item.status === 'failed' || item.status === 'unprocessed').map(item => item.chapterId);
  const selected = chapterIds ?? eligible;
  if (selected.length === 0 || new Set(selected).size !== selected.length || selected.some(id => !eligible.includes(id))) {
    throw invalidInput('只能重试失败或未处理的润色章节，且必须明确有效选择');
  }
  const reset = outcomes.map(item => selected.includes(item.chapterId) ? { ...item, status: 'unprocessed' as const, message: null, updatedAt: now } : item);
  const cursor = reset.findIndex(item => selected.includes(item.chapterId));
  return { ...job, outcomes: reset, retryChapterIds: selected.slice(), cursor, stage: 'queued', resumeStage: null,
    candidate: null, review: null, rewriteCount: 0, claim: null, failure: null, updatedAt: now };
};
