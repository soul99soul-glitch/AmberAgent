import type { NovelMaterial, NovelProject } from './models.ts';
import { MATERIAL_KIND_LABELS } from './models.ts';
import { invalidInput } from './error.ts';
import { effectiveNovelMaterials, materialOrigin } from './material_inheritance.ts';

export interface NovelInjectionOverrides {
  branchId?: string;
  forceIncludeMaterialIds: string[];
  forceExcludeMaterialIds: string[];
}
export type NovelMaterialInjectionReason = 'branchOverride' | 'always' | 'forceIncluded'
  | 'smartMatch' | 'forceExcluded' | 'disabled' | 'noSmartMatch' | 'budgetTrimmed';
export interface NovelMaterialInjectionDecision {
  materialId: string;
  title: string;
  text: string;
  reason: NovelMaterialInjectionReason;
  relevanceScore: number;
  protected: boolean;
  included: boolean;
  estimatedTokens?: number;
}

const normalized = (text: string): string => text.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().trim();
const kindOrder: string[] = ['world', 'character', 'relationship', 'outline', 'requirement', 'other'];
const protectedOrder: string[] = ['branchOverride', 'always', 'forceIncluded'];

export const novelMaterialRelevanceScore = (material: NovelMaterial, query: string): number => {
  const normalizedQuery: string = normalized(query);
  if (normalizedQuery.length === 0) return 0;
  const names: string[] = [material.title, ...(material.aliases ?? [])].map(normalized)
    .filter((name: string): boolean => name.length >= 2);
  let score: number = names.some((name: string): boolean => normalizedQuery.includes(name)) ? 100 : 0;
  const tags: string[] = [...new Set((material.tags ?? []).map(normalized)
    .filter((tag: string): boolean => tag.length >= 2))];
  score += tags.filter((tag: string): boolean => normalizedQuery.includes(tag)).length * 20;
  const content: string = normalized(material.content);
  const terms: string[] = [...new Set(normalizedQuery.split(/[^\p{L}\p{N}]+/u)
    .filter((term: string): boolean => term.length >= 3))];
  return score + Math.min(20, terms.filter((term: string): boolean => content.includes(term)).length);
};

export const classifyNovelMaterials = (
  project: NovelProject, userText: string = '', overrides?: NovelInjectionOverrides,
): NovelMaterialInjectionDecision[] => {
  const materials: NovelMaterial[] = effectiveNovelMaterials(project);
  const ids: Set<string> = new Set(materials.map((material: NovelMaterial): string => material.id));
  const includes: Set<string> = new Set(overrides?.forceIncludeMaterialIds ?? []);
  const excludes: Set<string> = new Set(overrides?.forceExcludeMaterialIds ?? []);
  if ([...includes, ...excludes].some((id: string): boolean => !ids.has(id))) {
    throw invalidInput('本次资料选择已过期，请重新预览当前分支资料');
  }
  const chapterTail: string = project.chapters.filter(chapter => !chapter.discarded).slice(-1)
    .map(chapter => chapter.content).join('\n');
  const query: string = [userText, project.authorPlot ?? '', project.branchSettings.thisChapterPlan,
    project.branchSettings.futurePlan, chapterTail].join('\n');
  const decisions: NovelMaterialInjectionDecision[] = materials.map((material: NovelMaterial): NovelMaterialInjectionDecision => {
    const mode = material.injectionMode ?? (material.enabled ? 'always' : 'off');
    let reason: NovelMaterialInjectionReason;
    let score: number = 0;
    if (materialOrigin(project, material.id) === 'override') reason = 'branchOverride';
    else if (excludes.has(material.id)) reason = 'forceExcluded';
    else if (mode === 'always') reason = 'always';
    else if (includes.has(material.id)) reason = 'forceIncluded';
    else if (mode === 'off') reason = 'disabled';
    else {
      score = novelMaterialRelevanceScore(material, query);
      reason = score > 0 ? 'smartMatch' : 'noSmartMatch';
    }
    const protectedMaterial: boolean = protectedOrder.includes(reason);
    const kindLabel: string = material.customKind?.trim() || MATERIAL_KIND_LABELS[material.kind];
    const aliases: string = material.kind === 'character' && (material.aliases?.length ?? 0) > 0
      ? material.aliases!.join(', ') : '(none)';
    const tags: string = (material.tags ?? []).map(tag => tag.trim()).filter(tag => tag.length > 0).sort().join(', ');
    return { materialId: material.id, title: material.title,
      text: `# 活资料\n[${kindLabel}] ${material.title}\nAliases: ${aliases}\nTags: ${tags}\n\n${material.content}`,
      reason, relevanceScore: score, protected: protectedMaterial,
      included: protectedMaterial || reason === 'smartMatch' };
  });
  const materialById: Map<string, NovelMaterial> = new Map(materials.map(material => [material.id, material]));
  return decisions.sort((left, right): number => {
    if (left.protected !== right.protected) return left.protected ? -1 : 1;
    if (left.protected && left.reason !== right.reason) {
      return protectedOrder.indexOf(left.reason) - protectedOrder.indexOf(right.reason);
    }
    if (!left.protected && left.relevanceScore !== right.relevanceScore) return right.relevanceScore - left.relevanceScore;
    const leftMaterial = materialById.get(left.materialId)!;
    const rightMaterial = materialById.get(right.materialId)!;
    const kind = kindOrder.indexOf(leftMaterial.kind) - kindOrder.indexOf(rightMaterial.kind);
    if (kind !== 0) return kind;
    const titleLeft: string = normalized(left.title);
    const titleRight: string = normalized(right.title);
    if (titleLeft !== titleRight) return titleLeft < titleRight ? -1 : 1;
    return left.materialId < right.materialId ? -1 : left.materialId > right.materialId ? 1 : 0;
  });
};
