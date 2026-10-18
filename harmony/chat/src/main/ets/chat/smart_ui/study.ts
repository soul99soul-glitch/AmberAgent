// study — 学习卡的纯状态机(翻卡/测验/重练/进度)。
//
// 结果按题目位置记录在 r 中：'-' 未答、'1' 答对、'0' 答错。
// 分数由 r 派生，重复进入同一题不会重复计分；进度 JSON 通过 study 节点的
// 字符串状态持久化，非法/缺失进度从第一题重新开始。

import type { SmartUiStudyItem } from './components.ts';

export interface StudyProgress {
  i: number;
  score: number;
  selected: string;
  answered: boolean;
  finished: boolean;
  r: string;
}

export const emptyStudyProgress = (count: number): StudyProgress => ({
  i: 0, score: 0, selected: '', answered: false, finished: false,
  r: '-'.repeat(Math.max(0, count)),
});

const scoreOf = (results: string): number => {
  let score: number = 0;
  for (const char of results) if (char === '1') score++;
  return score;
};

const answeredAt = (results: string, index: number): boolean =>
  index >= 0 && index < results.length && results.charAt(index) !== '-';

const withResult = (results: string, index: number, correct: boolean): string => {
  if (index < 0 || index >= results.length) return results;
  const before: string = results.substring(0, index);
  const after: string = results.substring(index + 1);
  return `${before}${correct ? '1' : '0'}${after}`;
};

export const gradedItemCount = (items: SmartUiStudyItem[]): number =>
  items.filter((item: SmartUiStudyItem): boolean => (item.options?.length ?? 0) > 0).length;

export const hasGradedItems = (items: SmartUiStudyItem[]): boolean => gradedItemCount(items) > 0;

export const chooseStudyOption = (
  progress: StudyProgress, item: SmartUiStudyItem | null, optionId: string,
): StudyProgress => {
  if (item === null || (item.options?.length ?? 0) === 0) return progress;
  if (progress.r.charAt(progress.i) !== '-') return progress;
  const correct: boolean = optionId === item.correctOptionId;
  const results: string = withResult(progress.r, progress.i, correct);
  return {
    i: progress.i, score: scoreOf(results), selected: optionId,
    answered: true, finished: progress.finished, r: results,
  };
};

export const revealStudyAnswer = (progress: StudyProgress): StudyProgress =>
  progress.answered ? progress : {
    i: progress.i, score: progress.score, selected: progress.selected,
    answered: true, finished: progress.finished, r: progress.r,
  };

export const nextStudy = (progress: StudyProgress, count: number): StudyProgress => {
  if (progress.i >= count - 1) {
    return {
      i: progress.i, score: progress.score, selected: progress.selected,
      answered: progress.answered, finished: true, r: progress.r,
    };
  }
  const index: number = progress.i + 1;
  return {
    i: index, score: progress.score, selected: '', answered: answeredAt(progress.r, index),
    finished: false, r: progress.r,
  };
};

export const previousStudy = (progress: StudyProgress): StudyProgress => {
  if (progress.i <= 0) return progress;
  const index: number = progress.i - 1;
  return {
    i: index, score: progress.score, selected: '', answered: answeredAt(progress.r, index),
    finished: false, r: progress.r,
  };
};

export const restartStudy = (count: number): StudyProgress => emptyStudyProgress(count);

export const parseStudyProgress = (raw: string, count: number): StudyProgress | null => {
  if (raw.length === 0) return null;
  try {
    const parsed = JSON.parse(raw) as Partial<StudyProgress>;
    if (!Number.isInteger(parsed.i) || typeof parsed.r !== 'string' || parsed.r.length !== count) return null;
    const index: number = parsed.i as number;
    if (index < 0 || index >= Math.max(count, 1)) return null;
    let results: string = '';
    for (let i: number = 0; i < count; i++) {
      const char: string = parsed.r.charAt(i);
      results += char === '1' || char === '0' ? char : '-';
    }
    return {
      i: index, score: scoreOf(results),
      selected: typeof parsed.selected === 'string' ? parsed.selected : '',
      // Flashcard reveals have no graded result in r, but still survive a remount.
      answered: parsed.answered === true || answeredAt(results, index),
      finished: parsed.finished === true && index === count - 1,
      r: results,
    };
  } catch {
    return null;
  }
};

export const serializeStudyProgress = (progress: StudyProgress): string => JSON.stringify(progress);

// Component adoption contract: a fresh empty state (lastPersisted='') must still size r;
// a genuine reset (raw='' after a non-empty write) must also be adopted.
export const shouldAdoptStudyProgress = (
  raw: string, lastPersisted: string, count: number, currentResultsLength: number,
): boolean => raw !== lastPersisted || currentResultsLength !== count;

export const studyProgressFrom = (raw: string, count: number): StudyProgress =>
  parseStudyProgress(raw, count) ?? emptyStudyProgress(count);
