import type { NovelChapter, NovelProject } from './models.ts';
import type { NovelChapterPlotPointer } from './plot_projection.ts';
import { firstStaleChapterOrdinal, rebuildChapterPlots, updateChapterPlots } from './plot_projection.ts';
import { invalidateNovelStructuredState } from './structured_state.ts';
import type { WorkspaceBranchSnapshot } from './workspace_codec.ts';

export const chapterChangedAt = (before: NovelChapter[], after: NovelChapter[]): number | null => {
  const count: number = Math.max(before.length, after.length);
  for (let i: number = 0; i < count; i++) {
    const left: NovelChapter | undefined = before[i];
    const right: NovelChapter | undefined = after[i];
    if (left === undefined || right === undefined || left.id !== right.id || left.title !== right.title ||
      left.content !== right.content || left.discarded !== right.discarded) return i;
  }
  return null;
};

export const projectChapterPlots = (
  before: NovelProject, after: NovelProject, branch: WorkspaceBranchSnapshot,
): NovelProject => {
  const prior: NovelChapterPlotPointer[] = before.chapterPlots.length === 0 && branch.plotStale
    ? rebuildChapterPlots(before.chapters).map((pointer: NovelChapterPlotPointer, index: number): NovelChapterPlotPointer => ({
      ...pointer, stale: index + 1 >= (branch.unresolvedFromOrdinal ?? 1),
    })) : before.chapterPlots;
  return {
    ...after, chapterPlots: updateChapterPlots(before.chapters, after.chapters, prior),
    structuredState: after.structuredState === undefined ? undefined
      : invalidateNovelStructuredState(before.chapters, after.chapters, after.structuredState)
  };
};

export const projectedPlotStatus = (
  after: NovelProject,
): { unresolved: number | null; stale: boolean } => {
  const unresolved: number | null = firstStaleChapterOrdinal(after.chapters, after.chapterPlots);
  return { unresolved, stale: unresolved !== null };
};

