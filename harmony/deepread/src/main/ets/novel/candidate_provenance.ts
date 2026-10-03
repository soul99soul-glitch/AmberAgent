// 候选来源只绑定作者确认的书稿/设定，不绑定频繁变动的对话 revision。
import type { NovelProject, NovelCandidateProvenance } from './models.ts';
import { defaultGhostwriteDigest } from './ghostwrite.ts';
import { invalidInput, notFound } from './error.ts';
import { normalizeNovelMaterialFields } from './material_fields.ts';

export const novelManuscriptDigest = (project: NovelProject): string => defaultGhostwriteDigest(
  JSON.stringify(project.chapters.map(chapter => ({
    id: chapter.id, title: chapter.title, content: chapter.content, discarded: chapter.discarded,
  }))),
);

export const novelPlanDigest = (project: NovelProject): string => defaultGhostwriteDigest(JSON.stringify({
  plan: project.branchSettings.thisChapterPlan,
  future: project.branchSettings.futurePlan,
  preferences: project.branchSettings.preferences,
  authorPlot: project.authorPlot ?? '',
  foreshadows: project.branchSettings.foreshadows.map(item => ({
    id: item.id, title: item.title, content: item.content, status: item.status,
  })),
  decisions: project.branchSettings.confirmedDecisions.map(item => ({
    id: item.id, title: item.title, content: item.content,
  })),
  materials: project.materials.filter(item => item.enabled).map(item => {
    const fields = normalizeNovelMaterialFields(item, item.enabled);
    // Empty/default metadata keeps the established digest for existing candidates.
    return { id: item.id, kind: item.kind, title: item.title, content: item.content,
      ...(fields.aliases.length > 0 ? { aliases: fields.aliases } : {}),
      ...(fields.tags.length > 0 ? { tags: fields.tags.slice().sort() } : {}),
      ...(fields.customKind.length > 0 ? { customKind: fields.customKind } : {}),
      ...(fields.injectionMode !== 'always' ? { injectionMode: fields.injectionMode } : {}),
    };
  }),
}));

export const makeNovelCandidateProvenance = (
  project: NovelProject, branchId: string, kind: NovelCandidateProvenance['kind'],
  sourceChapterId: string | null,
): NovelCandidateProvenance => {
  const source = sourceChapterId === null ? undefined : project.chapters.find(item => item.id === sourceChapterId);
  if (sourceChapterId !== null && source === undefined) throw notFound('chapter', sourceChapterId);
  return {
    kind, branchId, sourceChapterId,
    sourceDigest: source === undefined ? null : defaultGhostwriteDigest(source.content),
    baseManuscriptDigest: novelManuscriptDigest(project),
    basePlanDigest: novelPlanDigest(project),
    complete: false,
  };
};

export const assertNovelCandidateCurrent = (
  project: NovelProject, candidate: NovelCandidateProvenance, branchId: string | null = null,
): void => {
  if (branchId !== null && candidate.branchId !== branchId) {
    throw invalidInput('该候选来自另一分支，请切回生成时的分支后收录');
  }
  if (candidate.baseManuscriptDigest !== novelManuscriptDigest(project)) {
    throw invalidInput('候选生成后书稿已变更，请依据当前书稿重新生成');
  }
  if (candidate.basePlanDigest !== novelPlanDigest(project)) {
    throw invalidInput('候选生成后计划或设定已变更，请依据当前设定重新生成');
  }
  if (candidate.sourceChapterId !== null) {
    const source = project.chapters.find(chapter => chapter.id === candidate.sourceChapterId);
    if (source === undefined || defaultGhostwriteDigest(source.content) !== candidate.sourceDigest) {
      throw invalidInput('候选的来源章节已变更，请重新生成');
    }
  }
  if (candidate.kind === 'polish' && !candidate.complete) {
    throw invalidInput('润色输出尚未完整结束，不能覆盖原章');
  }
};
