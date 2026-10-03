import { normalizeNovelMaterialFields, novelMaterialAdoptionFields, optionalNovelMaterialFields } from './material_fields.ts';
// 资料采用只操作作者明确选择的目标；预览基线不依赖对话 checkpoint/revision。
import {
  MAX_TITLE_CHARS, MAX_MATERIAL_CHARS, makeNovelMaterial,
} from './models.ts';
import type {
  NovelProject, NovelChapter, NovelMaterial, NovelMaterialKind,
  NovelMaterialSuggestion, NovelSettingProposal, NovelMaterialFields,
} from './models.ts';
import { defaultGhostwriteDigest } from './ghostwrite.ts';
import { invalidInput, notFound } from './error.ts';

export interface NovelMaterialAdoption extends NovelMaterialFields {
  // 编辑预览绑定打开时的真实工作区分支；旧无编辑调用默认新建。
  branchId: string | null;
  kind: NovelMaterialKind;
  title: string;
  content: string;
  // null 明确表示新建；更新必须提交预览时目标的 digest。
  targetMaterialId: string | null;
  targetDigest: string | null;
  sourceDigest: string;
}

export interface NovelMaterialAdoptionResult {
  materials: NovelMaterial[];
  material: NovelMaterial;
}

export const materialAdoptionTargetDigest = (material: NovelMaterial): string =>
  defaultGhostwriteDigest(JSON.stringify({
    id: material.id, kind: material.kind, title: material.title,
    content: material.content, enabled: material.enabled, ...normalizeNovelMaterialFields(material, material.enabled),
  }));

export const materialSuggestionChapterDigest = (chapter: NovelChapter): string =>
  defaultGhostwriteDigest(JSON.stringify({
    id: chapter.id, title: chapter.title, content: chapter.content, discarded: chapter.discarded,
  }));

const suggestionSourceDigest = (project: NovelProject, suggestion: NovelMaterialSuggestion): string => {
  const chapter: NovelChapter | undefined = project.chapters.find(item => item.id === suggestion.sourceChapterId);
  const chapterDigest: string | null = chapter === undefined ? null : materialSuggestionChapterDigest(chapter);
  if (suggestion.sourceDigest !== undefined && suggestion.sourceDigest !== chapterDigest) {
    throw invalidInput('资料建议的来源章节已变更，请重新分析后采用');
  }
  return defaultGhostwriteDigest(JSON.stringify({
    id: suggestion.id, sourceChapterId: suggestion.sourceChapterId,
    kind: suggestion.kind, title: suggestion.title, content: suggestion.content, chapterDigest,
    ...optionalNovelMaterialFields(suggestion),
  }));
};

const settingProposalSourceDigest = (project: NovelProject, proposal: NovelSettingProposal): string => {
  const source = project.messages.find(message => message.id === proposal.sourceMessageId);
  return defaultGhostwriteDigest(JSON.stringify({
    id: proposal.id, sourceMessageId: proposal.sourceMessageId,
    kind: proposal.kind, title: proposal.title, content: proposal.content,
    ...optionalNovelMaterialFields(proposal),
    sourceContent: source === undefined ? null : source.content,
  }));
};

const targetDigest = (project: NovelProject, targetMaterialId: string | null): string | null => {
  if (targetMaterialId === null) return null;
  const material: NovelMaterial | undefined = project.materials.find(item => item.id === targetMaterialId);
  if (material === undefined) throw notFound('material', targetMaterialId);
  return materialAdoptionTargetDigest(material);
};

export const buildSuggestionAdoption = (
  project: NovelProject, suggestion: NovelMaterialSuggestion, targetMaterialId: string | null = null,
  branchId: string | null = null,
): NovelMaterialAdoption => ({
  branchId,
  ...novelMaterialAdoptionFields(suggestion, project.materials.find(item => item.id === targetMaterialId),
    project.materials.find(item => item.id === targetMaterialId)?.enabled ?? true),
  kind: suggestion.kind, title: suggestion.title, content: suggestion.content,
  targetMaterialId, targetDigest: targetDigest(project, targetMaterialId),
  sourceDigest: suggestionSourceDigest(project, suggestion),
});

export const buildSettingProposalAdoption = (
  project: NovelProject, proposal: NovelSettingProposal, targetMaterialId: string | null = null,
  branchId: string | null = null,
): NovelMaterialAdoption => ({
  branchId,
  ...novelMaterialAdoptionFields(proposal, project.materials.find(item => item.id === targetMaterialId),
    project.materials.find(item => item.id === targetMaterialId)?.enabled ?? true),
  kind: proposal.kind, title: proposal.title, content: proposal.content,
  targetMaterialId, targetDigest: targetDigest(project, targetMaterialId),
  sourceDigest: settingProposalSourceDigest(project, proposal),
});

export const assertMaterialAdoptionBranch = (edit: NovelMaterialAdoption, branchId: string): void => {
  if (edit.branchId !== branchId) {
    throw invalidInput('资料预览不属于当前分支，请重新打开预览后采用');
  }
};

export const applyMaterialAdoption = (
  project: NovelProject, edit: NovelMaterialAdoption, currentSourceDigest: string, now: number,
): NovelMaterialAdoptionResult => {
  if (edit.sourceDigest !== currentSourceDigest) {
    throw invalidInput('资料建议或来源已变更，请重新打开预览后采用');
  }
  const title: string = edit.title.trim();
  if (title.length === 0) throw invalidInput('资料标题不能为空');
  if (edit.content.trim().length === 0) throw invalidInput('资料正文不能为空');
  if (title.length > MAX_TITLE_CHARS) throw invalidInput(`资料标题不能超过 ${MAX_TITLE_CHARS} 字`);
  if (edit.content.length > MAX_MATERIAL_CHARS) throw invalidInput(`资料正文不能超过 ${MAX_MATERIAL_CHARS} 字`);
  if (edit.targetMaterialId === null) {
    const material: NovelMaterial = makeNovelMaterial({
      kind: edit.kind, title, content: edit.content, enabled: true, now, ...normalizeNovelMaterialFields(edit),
    });
    return { materials: [...project.materials, material], material };
  }
  const index: number = project.materials.findIndex(item => item.id === edit.targetMaterialId);
  if (index < 0) throw notFound('material', edit.targetMaterialId);
  const current: NovelMaterial = project.materials[index];
  if (edit.targetDigest !== materialAdoptionTargetDigest(current)) {
    throw invalidInput('目标资料已变更，请重新查看目标内容后采用');
  }
  const fields = novelMaterialAdoptionFields(edit, current, current.enabled);
  const material: NovelMaterial = {
    ...current, ...fields, enabled: fields.injectionMode !== 'off',
    kind: edit.kind, title, content: edit.content, updatedAt: now,
  };
  const materials: NovelMaterial[] = project.materials.slice();
  materials[index] = material;
  return { materials, material };
};
