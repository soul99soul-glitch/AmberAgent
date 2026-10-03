import type { NovelModelPolicy, NovelModelTarget, NovelProject, NovelStorySeed } from './models.ts';
import { upsertMaterial } from './mutations.ts';
import { invalidInput } from './error.ts';
export type { NovelStorySeed } from './models.ts';

export type NovelModelRole = 'writing' | 'review' | 'stateSync';
export interface NovelModelDefaults {
  writing: NovelModelTarget;
  review: NovelModelTarget;
  stateSync: NovelModelTarget;
}
export const defaultNovelModelDefaults = (): NovelModelDefaults => ({
  writing: { kind: 'global' }, review: { kind: 'global' }, stateSync: { kind: 'global' },
});

export const resolveNovelDefaultTarget = (
  policy: NovelModelPolicy, defaults: NovelModelDefaults, role: NovelModelRole,
): NovelModelTarget => {
  const projectTarget: NovelModelTarget | null = policy[role];
  if (projectTarget?.kind === 'fixed') return projectTarget;
  const defaultTarget: NovelModelTarget = defaults[role];
  if (defaultTarget.kind === 'fixed') return defaultTarget;
  if (role !== 'writing' && projectTarget === null) {
    return resolveNovelDefaultTarget(policy, defaults, 'writing');
  }
  return { kind: 'global' };
};

export const novelStorySeedText = (seed: NovelStorySeed): string => [
  seed.genre.trim() ? `题材：${seed.genre.trim()}` : '',
  seed.coreIdea.trim() ? `核心想法：${seed.coreIdea.trim()}` : '',
  seed.world.trim() ? `世界背景：${seed.world.trim()}` : '',
  seed.characters.trim() ? `主要人物：${seed.characters.trim()}` : '',
  seed.direction.trim() ? `故事方向：${seed.direction.trim()}` : '',
].filter(value => value.length > 0).join('\n\n');

// Save author-provided facts before the first AI turn. Generated suggestions still require approval.
export const projectWithNovelStorySeed = (
  project: NovelProject, seed: NovelStorySeed, now: number,
): NovelProject => {
  let result: NovelProject = { ...project, creationMode: 'quickStart', quickStartSeed: { ...seed } };
  const requirement: string = [seed.genre.trim(), seed.coreIdea.trim()].filter(value => value.length > 0).join('\n');
  if (requirement.length > 0) result = upsertMaterial(result, null, 'requirement', '故事种子', requirement, true, now).project;
  if (seed.world.trim().length > 0) result = upsertMaterial(result, null, 'world', '初始世界背景', seed.world.trim(), true, now).project;
  if (seed.characters.trim().length > 0) result = upsertMaterial(result, null, 'character', '初始主要人物', seed.characters.trim(), true, now).project;
  if (seed.direction.trim().length > 0) result = upsertMaterial(result, null, 'outline', '初始故事方向', seed.direction.trim(), true, now).project;
  return result;
};

// Regeneration changes only this request; the author's original seed stays unchanged.
export const novelQuickStartRequestText = (
  project: NovelProject, guidance: string = '', coreIdeaOverride: string = '',
): string => {
  if (project.creationMode !== 'quickStart' || project.quickStartSeed === undefined || project.quickStartSeed === null) {
    throw invalidInput('项目没有原始故事种子，不能重新生成设定建议');
  }
  const original: NovelStorySeed = project.quickStartSeed;
  const current: NovelStorySeed = { ...original, coreIdea: coreIdeaOverride.trim() || original.coreIdea };
  return [
    '请生成一组可确认的世界观、人物、总剧情大纲和写作要求建议。',
    novelStorySeedText(current),
    guidance.trim().length > 0 ? `本轮调整方向：\n${guidance.trim()}` : '',
  ].filter(value => value.length > 0).join('\n\n');
};
