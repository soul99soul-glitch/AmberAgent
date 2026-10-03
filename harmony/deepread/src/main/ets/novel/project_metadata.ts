import type { NovelProject, NovelProjectCreationMode, NovelStorySeed } from './models.ts';
import { invalidInput } from './error.ts';

export interface NovelCreationMetadata {
  creationMode: NovelProjectCreationMode;
  quickStartSeed: NovelStorySeed | null;
  polishPreference: string;
}

export const normalizeNovelCreationMetadata = (project: NovelProject): NovelCreationMetadata => {
  const mode: NovelProjectCreationMode = project.creationMode ?? 'blank';
  if (mode !== 'blank' && mode !== 'quickStart') throw invalidInput('项目创建方式无效');
  const seed: NovelStorySeed | null = project.quickStartSeed ?? null;
  if (mode === 'quickStart' && (seed === null ||
    ![seed.genre, seed.coreIdea, seed.world, seed.characters, seed.direction].every(field => typeof field === 'string'))) {
    throw invalidInput('快速开始项目缺少完整原始种子');
  }
  if (mode === 'blank' && seed !== null) throw invalidInput('空白项目不能包含快速开始种子');
  if (project.polishPreference !== undefined && typeof project.polishPreference !== 'string') {
    throw invalidInput('项目润色偏好无效');
  }
  return { creationMode: mode, quickStartSeed: seed, polishPreference: project.polishPreference ?? '' };
};

export const assertNovelCreationMetadataImmutable = (before: NovelProject, after: NovelProject): void => {
  const original: NovelCreationMetadata = normalizeNovelCreationMetadata(before);
  const updated: NovelCreationMetadata = normalizeNovelCreationMetadata(after);
  if (original.creationMode !== updated.creationMode ||
    JSON.stringify(original.quickStartSeed) !== JSON.stringify(updated.quickStartSeed)) {
    throw invalidInput('创建方式与原始种子不可修改');
  }
};
