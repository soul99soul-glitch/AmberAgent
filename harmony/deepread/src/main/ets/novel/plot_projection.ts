// 正文的确定性逐章指针；作者的 plot.md 仍由作者维护。
import type { NovelChapter } from './models.ts';

export interface NovelChapterPlotPointer {
  chapterId: string;
  sourceDigest: string;
  text: string;
  stale: boolean;
}

export const chapterPlotSourceDigest = (content: string): string => {
  let hash: number = 0x811c9dc5;
  for (let i: number = 0; i < content.length; i++) {
    hash = Math.imul(hash ^ content.charCodeAt(i), 0x01000193);
  }
  return (hash >>> 0).toString(16).padStart(8, '0');
};

const activeChapters = (chapters: NovelChapter[]): NovelChapter[] => chapters.filter(
  (chapter: NovelChapter): boolean => !chapter.discarded);

const makePointer = (chapter: NovelChapter, stale: boolean): NovelChapterPlotPointer => ({
  chapterId: chapter.id,
  sourceDigest: chapterPlotSourceDigest(chapter.content),
  text: `${chapter.title.trim()}\n${chapter.content.split('\n')
    .map((line: string): string => line.trim())
    .filter((line: string): boolean => line.length > 0).slice(0, 3).join('\n')}`.trim().slice(0, 160),
  stale,
});

export const rebuildChapterPlots = (chapters: NovelChapter[]): NovelChapterPlotPointer[] =>
  activeChapters(chapters).map((chapter: NovelChapter): NovelChapterPlotPointer => makePointer(chapter, false));

export const updateChapterPlots = (
  before: NovelChapter[], after: NovelChapter[], prior: NovelChapterPlotPointer[],
): NovelChapterPlotPointer[] => {
  const previous: NovelChapter[] = activeChapters(before);
  const current: NovelChapter[] = activeChapters(after);
  const previousById: Map<string, NovelChapter> = new Map(previous.map(
    (chapter: NovelChapter): [string, NovelChapter] => [chapter.id, chapter]));
  const pointersById: Map<string, NovelChapterPlotPointer> = new Map(prior.map(
    (pointer: NovelChapterPlotPointer): [string, NovelChapterPlotPointer] => [pointer.chapterId, pointer]));
  let affected: boolean = false;
  return current.map((chapter: NovelChapter, index: number): NovelChapterPlotPointer => {
    const oldChapter: NovelChapter | undefined = previousById.get(chapter.id);
    const oldPointer: NovelChapterPlotPointer | undefined = pointersById.get(chapter.id);
    const bodyChanged: boolean = oldChapter === undefined || oldChapter.content !== chapter.content;
    const sourceMismatch: boolean = oldPointer !== undefined && oldChapter !== undefined &&
      oldPointer.sourceDigest !== chapterPlotSourceDigest(oldChapter.content);
    if (previous[index]?.id !== chapter.id || bodyChanged || sourceMismatch) affected = true;
    // 本次重写/新增的正文已重新取证；未改的后章须确认前文变化的影响。
    return makePointer(chapter, bodyChanged ? false : affected || (oldPointer?.stale ?? false));
  });
};

export const firstStaleChapterOrdinal = (
  chapters: NovelChapter[], pointers: NovelChapterPlotPointer[],
): number | null => {
  const byId: Map<string, NovelChapterPlotPointer> = new Map(pointers.map(
    (pointer: NovelChapterPlotPointer): [string, NovelChapterPlotPointer] => [pointer.chapterId, pointer]));
  const current: NovelChapter[] = activeChapters(chapters);
  const index: number = current.findIndex((chapter: NovelChapter): boolean => byId.get(chapter.id)?.stale === true);
  return index < 0 ? null : index + 1;
};
