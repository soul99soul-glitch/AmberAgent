import type { NovelMaterial, NovelProject } from './models.ts';
import { normalizeNovelMaterialFields } from './material_fields.ts';
import { invalidInput } from './error.ts';

export type NovelMaterialOrigin = 'shared' | 'override' | 'branch';

const materialIdentity = (material: NovelMaterial): string => JSON.stringify({
  id: material.id, kind: material.kind, title: material.title, content: material.content,
  updatedAt: material.updatedAt,
  ...normalizeNovelMaterialFields(material, material.enabled),
});

export const effectiveNovelMaterials = (project: NovelProject): NovelMaterial[] => {
  if (project.baseMaterials === undefined) return project.materials;
  const overrides: Map<string, NovelMaterial> = new Map((project.materialOverrides ?? []).map(item => [item.id, item]));
  const hidden: Set<string> = new Set(project.hiddenMaterialIds ?? []);
  const result: NovelMaterial[] = [];
  const seen: Set<string> = new Set();
  for (const base of project.baseMaterials) {
    seen.add(base.id);
    if (!hidden.has(base.id)) result.push(overrides.get(base.id) ?? base);
  }
  for (const item of project.materialOverrides ?? []) if (!seen.has(item.id)) result.push(item);
  return result;
};

export const materialOrigin = (project: NovelProject, materialId: string): NovelMaterialOrigin => {
  if (project.baseMaterials === undefined) return 'branch';
  return (project.materialOverrides ?? []).some(item => item.id === materialId) ? 'override' : 'shared';
};

export const withBranchMaterialEdits = (project: NovelProject, materials: NovelMaterial[]): NovelProject => {
  if (project.baseMaterials === undefined) return { ...project, materials };
  const base: Map<string, NovelMaterial> = new Map(project.baseMaterials.map(item => [item.id, item]));
  const effectiveIds: Set<string> = new Set(materials.map(item => item.id));
  const explicitIds: Set<string> = new Set((project.materialOverrides ?? []).map(item => item.id));
  const overrides: NovelMaterial[] = materials.filter(item => {
    const inherited: NovelMaterial | undefined = base.get(item.id);
    return inherited === undefined || explicitIds.has(item.id) || materialIdentity(inherited) !== materialIdentity(item);
  });
  const result: NovelProject = { ...project, materialOverrides: overrides,
    hiddenMaterialIds: project.baseMaterials.filter(item => !effectiveIds.has(item.id)).map(item => item.id) };
  return { ...result, materials: effectiveNovelMaterials(result) };
};

export const initializeSharedMaterials = (project: NovelProject): NovelProject => {
  if (project.baseMaterials !== undefined) return { ...project, materials: effectiveNovelMaterials(project) };
  return { ...project, baseMaterials: project.materials, materialOverrides: [], hiddenMaterialIds: [] };
};

// Legacy branch files can contain whole copied material sets. Capture their differences
// against the old shared baseline before applying a new baseline, preserving user edits.
export const withUpdatedSharedMaterials = (
  project: NovelProject, originalBase: NovelMaterial[], nextBase: NovelMaterial[],
): NovelProject => {
  const inherited: NovelProject = project.baseMaterials === undefined
    ? withBranchMaterialEdits({ ...project, baseMaterials: originalBase, materialOverrides: [], hiddenMaterialIds: [] }, project.materials)
    : project;
  const result: NovelProject = { ...inherited, baseMaterials: nextBase };
  return { ...result, materials: effectiveNovelMaterials(result) };
};

export const restoreNovelMaterialInheritance = (project: NovelProject, materialId: string): NovelProject => {
  if (project.baseMaterials === undefined || !project.baseMaterials.some(item => item.id === materialId)) {
    throw invalidInput('资料没有可恢复的共享版本');
  }
  const next: NovelProject = { ...project,
    materialOverrides: (project.materialOverrides ?? []).filter(item => item.id !== materialId),
    hiddenMaterialIds: (project.hiddenMaterialIds ?? []).filter(id => id !== materialId) };
  return { ...next, materials: effectiveNovelMaterials(next) };
};
