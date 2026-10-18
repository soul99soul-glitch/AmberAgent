import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  chooseStudyOption, emptyStudyProgress, gradedItemCount, hasGradedItems,
  nextStudy, parseStudyProgress, previousStudy, restartStudy, revealStudyAnswer,
  serializeStudyProgress, shouldAdoptStudyProgress, studyProgressFrom,
} from '../main/ets/chat/smart_ui/study.ts';
import type { SmartUiStudyItem } from '../main/ets/chat/smart_ui/components.ts';

const quiz = (id: string, correct: string): SmartUiStudyItem => ({
  id, prompt: `${id} prompt`, answer: `${id} answer`,
  options: [{ id: 'a', label: 'A' }, { id: 'b', label: 'B' }], correctOptionId: correct,
});
const items: SmartUiStudyItem[] = [quiz('q1', 'a'), quiz('q2', 'b'), { id: 'f1', prompt: 'flash', answer: 'answer' }];

test('replaying an already answered question cannot double count', () => {
  let progress = emptyStudyProgress(items.length);
  progress = chooseStudyOption(progress, items[0], 'a');
  assert.equal(progress.score, 1);
  progress = nextStudy(progress, items.length);
  assert.equal(progress.i, 1);
  progress = previousStudy(progress);
  assert.equal(progress.i, 0);
  assert.equal(progress.answered, true);
  const replay = chooseStudyOption(progress, items[0], 'a');
  assert.strictEqual(replay, progress, 'answered question is immutable');
  assert.equal(replay.score, 1);
  progress = nextStudy(replay, items.length);
  progress = chooseStudyOption(progress, items[1], 'a');
  assert.equal(progress.score, 1, 'wrong answer does not add a point');
  progress = nextStudy(progress, items.length);
  assert.equal(progress.i, 2);
  progress = nextStudy(progress, items.length);
  assert.equal(progress.finished, true);
  assert.equal(progress.score, 1);
  assert.equal(gradedItemCount(items), 2);
  assert.equal(hasGradedItems(items), true);
});

test('flashcard reveal and restart are explicit and progress round-trips', () => {
  let progress = emptyStudyProgress(items.length);
  progress = revealStudyAnswer(progress);
  assert.equal(progress.answered, true);
  progress = nextStudy(progress, items.length);
  assert.equal(progress.i, 1);
  const raw = serializeStudyProgress(progress);
  assert.deepEqual(parseStudyProgress(raw, items.length), progress);
  const restarted = restartStudy(items.length);
  assert.equal(restarted.i, 0);
  assert.equal(restarted.score, 0);
  assert.equal(restarted.r, '---');
  assert.equal(parseStudyProgress('', items.length), null);
  assert.equal(parseStudyProgress('{"i":9,"r":"---"}', items.length), null);
  assert.equal(parseStudyProgress('not json', items.length), null);
});

test('fresh empty state and explicit reset are adopted; matching snapshot is not re-read', () => {
  assert.equal(shouldAdoptStudyProgress('', '', 3, 0), true, 'fresh mount must size r even when raw is empty');
  assert.equal(shouldAdoptStudyProgress('', '', 0, 0), false);
  assert.equal(shouldAdoptStudyProgress('', '{"i":0}', 3, 3), true, 'reset to empty must be adopted');
  assert.equal(shouldAdoptStudyProgress('{"i":0}', '{"i":0}', 3, 3), false);
  assert.equal(studyProgressFrom('', 3).r, '---');
  assert.equal(studyProgressFrom('not json', 2).r, '--');
  assert.equal(parseStudyProgress('{"i":0,"r":"---","finished":true}', 3)?.finished, false,
    'finished is only valid on the terminal item');
});

test('restoring a revealed flashcard preserves its answer without awarding quiz points', () => {
  let progress = emptyStudyProgress(items.length);
  progress = nextStudy(nextStudy(progress, items.length), items.length);
  assert.equal(items[progress.i].options, undefined);
  progress = revealStudyAnswer(progress);
  const restored = studyProgressFrom(serializeStudyProgress(progress), items.length);
  assert.deepEqual(restored, progress);
  assert.equal(restored.answered, true);
  assert.equal(restored.score, 0);
  assert.equal(restored.r, '---');
});

test('restored graded questions remain answered even when the reveal flag is absent or false', () => {
  for (const raw of ['{"i":0,"r":"1"}', '{"i":0,"r":"0","answered":false}']) {
    assert.equal(studyProgressFrom(raw, 1).answered, true);
  }
});
