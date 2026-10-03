import { normalizeNovelMaterialFields } from './material_fields.ts';
import type { NovelMaterialFields } from './models.ts';
// novel/mutations — 项目纯函数变更(移植自 Android NovelMutations.kt)
// 每个函数取 (project, …, now) 返回新 NovelProject(不可变 spread)。校验失败抛 NovelError。

import {
  MAX_PROJECT_NAME_CHARS, MAX_TITLE_CHARS, MAX_CHAPTER_CHARS, MAX_MATERIAL_CHARS,
  makeNovelProject, makeNovelChapter, makeNovelMaterial,
  makeNovelChapterVersion, makeNovelBranch, nextNovelChapterOrdinal, novelChapterOrdinal,
} from './models.ts';
import type {
  NovelProject, NovelMessage, NovelChapter, NovelMaterial, NovelMaterialSuggestion,
  NovelMaterialKind, NovelCollectionTarget, NovelChapterVersionKind, NovelBranch,
  NovelChapterVersion, NovelSettingProposal, NovelModelPolicy, NovelBranchSettings,
} from './models.ts';
import { invalidInput, notFound, alreadyCollected } from './error.ts';
import { assertNovelCandidateCurrent } from './candidate_provenance.ts';
import { applyMaterialAdoption, buildSuggestionAdoption, buildSettingProposalAdoption } from './material_adoption.ts';
import type { NovelMaterialAdoption } from './material_adoption.ts';

// ===== N1:分支 / 版本 / 撤销 / 废弃(Android Branch/Version/Checkpoint 语义)=====

// 章节版本快照:保存/覆盖章节前先存一版(kind 标来源)
export const saveChapterVersion = (
  project: NovelProject, chapterId: string, kind: NovelChapterVersionKind, now: number,
): NovelMutation<NovelChapterVersion> => {
  const idx: number = project.chapters.findIndex(c => c.id === chapterId);
  if (idx < 0) throw notFound('chapter', chapterId);
  const c: NovelChapter = project.chapters[idx];
  const version: NovelChapterVersionLike = makeNovelChapterVersion(chapterId, kind, c.title, c.content, now);
  return {
    project: { ...project, chapterVersions: [...project.chapterVersions, version], updatedAt: now },
    value: version,
  };
};

// 章节版本恢复:恢复即再存一版当前内容(不丢历史)
export const restoreChapterVersion = (
  project: NovelProject, versionId: string, now: number,
): NovelMutation<NovelChapter> => {
  const v = project.chapterVersions.find(x => x.id === versionId);
  if (v === undefined) throw notFound('chapter_version', versionId);
  const idx: number = project.chapters.findIndex(c => c.id === v.chapterId);
  if (idx < 0) throw notFound('chapter', v.chapterId);
  const current: NovelChapter = project.chapters[idx];
  const backup: NovelChapterVersionLike = makeNovelChapterVersion(v.chapterId, 'manual', current.title, current.content, now);
  const restored: NovelChapter = { ...current, title: v.title, content: v.content, updatedAt: now };
  const chapters: NovelChapter[] = project.chapters.slice();
  chapters[idx] = restored;
  return {
    project: { ...project, chapters: chapters, chapterVersions: [...project.chapterVersions, backup], updatedAt: now },
    value: restored,
  };
};

// 章节废弃/恢复标记
export const setChapterDiscarded = (
  project: NovelProject, chapterId: string, discarded: boolean, now: number,
): NovelMutation<NovelChapter> => {
  const idx: number = project.chapters.findIndex(c => c.id === chapterId);
  if (idx < 0) throw notFound('chapter', chapterId);
  const updated: NovelChapter = { ...project.chapters[idx], discarded: discarded, updatedAt: now };
  const chapters: NovelChapter[] = project.chapters.slice();
  chapters[idx] = updated;
  return { project: { ...project, chapters: chapters, updatedAt: now }, value: updated };
};

// 分支:fork 当前主分支(继承章节视图;鸿蒙轻量实现 = 分支记录本身)
export const forkBranch = (
  project: NovelProject, name: string, now: number,
): NovelMutation<NovelBranch> => {
  const clean: string = name.trim();
  if (clean.length === 0) throw invalidInput('分支名称不能为空');
  const main: NovelBranch | undefined = project.branches.find(b => b.isMain && b.lifecycle === 'active');
  const branch: NovelBranch = makeNovelBranch(clean, false,
    main !== undefined ? main.forkFromChapterId : null, now);
  return { project: { ...project, branches: [...project.branches, branch], updatedAt: now }, value: branch };
};

// 切换活跃分支标记(轻量实现:isMain 移动;完整分支隔离待后续)
export const selectBranch = (
  project: NovelProject, branchId: string, now: number,
): NovelMutation<NovelBranch> => {
  const b: NovelBranch | undefined = project.branches.find(x => x.id === branchId && x.lifecycle === 'active');
  if (b === undefined) throw notFound('branch', branchId);
  const branches: NovelBranch[] = project.branches.map(
    (x: NovelBranch): NovelBranch => ({ ...x, isMain: x.id === branchId }));
  return { project: { ...project, branches: branches, updatedAt: now }, value: b };
};

// 重命名分支
export const renameBranch = (
  project: NovelProject, branchId: string, name: string, now: number,
): NovelMutation<NovelBranch> => {
  const clean: string = name.trim();
  if (clean.length === 0) throw invalidInput('分支名称不能为空');
  const idx: number = project.branches.findIndex(x => x.id === branchId);
  if (idx < 0) throw notFound('branch', branchId);
  const renamed: NovelBranch = { ...project.branches[idx], name: clean.slice(0, MAX_PROJECT_NAME_CHARS) };
  const branches: NovelBranch[] = project.branches.slice();
  branches[idx] = renamed;
  return { project: { ...project, branches: branches, updatedAt: now }, value: renamed };
};

// makeNovelChapterVersion 返回类型别名(ArkTS 不用 structural typing)
type NovelChapterVersionLike = ReturnType<typeof makeNovelChapterVersion>;

export interface NovelMutation<T> {
  project: NovelProject;
  value: T;
}

const cleanName = (name: string): string => {
  const t: string = name.trim();
  if (t.length === 0) throw invalidInput('项目名称不能为空');
  return t.slice(0, MAX_PROJECT_NAME_CHARS);
};

const cleanTitle = (title: string): string => {
  const t: string = title.trim();
  if (t.length === 0) throw invalidInput('标题不能为空');
  return t.slice(0, MAX_TITLE_CHARS);
};

// 归一标题:小写 + 去空白(用于素材按 kind+标题 匹配)
export const normalizeTitle = (title: string): string =>
  title.toLowerCase().replace(/\s+/g, '');

export const createProject = (name: string, now: number): NovelProject =>
  makeNovelProject({ name: cleanName(name), now: now });

export const renameProject = (project: NovelProject, name: string, now: number): NovelProject => ({
  ...project,
  name: cleanName(name),
  updatedAt: now,
});

export const setProjectModelPolicy = (
  project: NovelProject, modelPolicy: NovelModelPolicy, now: number,
): NovelProject => ({
  ...project,
  modelPolicy: modelPolicy,
  updatedAt: now,
});

export const setProjectBranchSettings = (
  project: NovelProject, branchSettings: NovelBranchSettings, now: number,
): NovelProject => ({
  ...project,
  branchSettings: branchSettings,
  updatedAt: now,
});

export const appendMessage = (
  project: NovelProject, message: NovelMessage, now: number,
): NovelProject => ({
  ...project,
  messages: [...project.messages, message],
  updatedAt: now,
});

export const saveChapter = (
  project: NovelProject, chapterId: string | null, title: string, content: string, now: number,
): NovelMutation<NovelChapter> => {
  const cleanT: string = cleanTitle(title);
  const body: string = content.slice(0, MAX_CHAPTER_CHARS);
  if (chapterId === null) {
    const chapter: NovelChapter = makeNovelChapter({ ordinal: nextNovelChapterOrdinal(project.chapters), title: cleanT, content: body, now: now });
    return {
      project: { ...project, chapters: [...project.chapters, chapter], updatedAt: now },
      value: chapter,
    };
  }
  const idx: number = project.chapters.findIndex(c => c.id === chapterId);
  if (idx < 0) throw notFound('chapter', chapterId);
  const updated: NovelChapter = {
    ...project.chapters[idx], title: cleanT, content: body, updatedAt: now,
  };
  const chapters: NovelChapter[] = project.chapters.slice();
  chapters[idx] = updated;
  return { project: { ...project, chapters: chapters, updatedAt: now }, value: updated };
};

export const deleteChapter = (
  project: NovelProject, chapterId: string, now: number,
): NovelProject => {
  if (!project.chapters.some(c => c.id === chapterId)) throw notFound('chapter', chapterId);
  const chapters: NovelChapter[] = project.chapters.map((chapter: NovelChapter, index: number): NovelChapter =>
    chapter.ordinal !== undefined ? chapter : { ...chapter, ordinal: novelChapterOrdinal(chapter, index + 1) })
    .filter(c => c.id !== chapterId);
  // 清空指向该章的 collectedChapterId
  const messages: NovelMessage[] = project.messages.map(m =>
    m.collectedChapterId === chapterId ? { ...m, collectedChapterId: null } : m);
  // 丢弃该章产生的建议
  const materialSuggestions: NovelMaterialSuggestion[] =
    project.materialSuggestions.filter(s => s.sourceChapterId !== chapterId);
  return { ...project, chapters: chapters, messages: messages, materialSuggestions: materialSuggestions, updatedAt: now };
};

export const upsertMaterial = (
  project: NovelProject, materialId: string | null, kind: NovelMaterialKind,
  title: string, content: string, enabled: boolean, now: number, fields?: NovelMaterialFields,
): NovelMutation<NovelMaterial> => {
  const cleanT: string = cleanTitle(title);
  const body: string = content.slice(0, MAX_MATERIAL_CHARS);
  if (materialId === null) {
    const material: NovelMaterial =
      makeNovelMaterial({ kind: kind, title: cleanT, content: body, enabled: enabled, now: now, ...fields });
    return {
      project: { ...project, materials: [...project.materials, material], updatedAt: now },
      value: material,
    };
  }
  const idx: number = project.materials.findIndex(m => m.id === materialId);
  if (idx < 0) throw notFound('material', materialId);
  const current: NovelMaterial = project.materials[idx];
  const metadata = normalizeNovelMaterialFields({ ...current, ...fields, injectionMode: fields?.injectionMode ??
    (enabled === current.enabled ? current.injectionMode : enabled ? 'always' : 'off') }, enabled);
  const updated: NovelMaterial = {
    ...project.materials[idx], ...metadata, kind, title: cleanT, content: body,
    enabled: metadata.injectionMode !== 'off', updatedAt: now,
  };
  const materials: NovelMaterial[] = project.materials.slice();
  materials[idx] = updated;
  return { project: { ...project, materials: materials, updatedAt: now }, value: updated };
};

export const deleteMaterial = (
  project: NovelProject, materialId: string, now: number,
): NovelProject => {
  if (!project.materials.some(m => m.id === materialId)) throw notFound('material', materialId);
  return { ...project, materials: project.materials.filter(m => m.id !== materialId), updatedAt: now };
};

// 收录助手回复进章节;editedContent = 收录前编辑稿(四段式收录表单),null 取消息原文
export const collect = (
  project: NovelProject, messageId: string, target: NovelCollectionTarget, now: number,
  editedContent: string | null = null,
  branchId: string | null = null,
): NovelMutation<NovelChapter> => {
  const msgIdx: number = project.messages.findIndex(m => m.id === messageId);
  if (msgIdx < 0) throw notFound('message', messageId);
  const msg: NovelMessage = project.messages[msgIdx];
  if (msg.role !== 'assistant' || msg.mode !== 'write') throw invalidInput('只能收录助手的写作回复');
  const content: string = editedContent !== null ? editedContent : msg.content;
  if (content.trim().length === 0) throw invalidInput('该回复没有正文');
  if (msg.collectedChapterId !== null) throw alreadyCollected(messageId);
  if (msg.candidate !== undefined) assertNovelCandidateCurrent(project, msg.candidate, branchId);

  let chapters: NovelChapter[] = project.chapters.slice();
  let chapter: NovelChapter;
  if (target.kind === 'new_chapter') {
    chapter = makeNovelChapter({ ordinal: nextNovelChapterOrdinal(project.chapters), title: cleanTitle(target.title), content: content, now: now });
    chapters = [...chapters, chapter];
  } else {
    const idx: number = chapters.findIndex(c => c.id === target.chapterId);
    if (idx < 0) throw notFound('chapter', target.chapterId);
    const existing: NovelChapter = chapters[idx];
    const newContent: string = target.kind === 'append'
      ? (existing.content.length > 0 ? existing.content + '\n\n' + content : content)
      : content;
    chapter = { ...existing, content: newContent, updatedAt: now };
    chapters[idx] = chapter;
  }

  const messages: NovelMessage[] = project.messages.slice();
  messages[msgIdx] = { ...msg, collectedChapterId: chapter.id };
  return {
    project: { ...project, chapters: chapters, messages: messages, updatedAt: now },
    value: chapter,
  };
};

// 用新生成的建议替换某章的 pending 建议
export const replacePendingSuggestions = (
  project: NovelProject, chapterId: string, suggestions: NovelMaterialSuggestion[], now: number,
): NovelProject => {
  const kept: NovelMaterialSuggestion[] = project.materialSuggestions.filter(
    s => !(s.sourceChapterId === chapterId && s.status === 'pending'));
  return {
    ...project,
    materialSuggestions: [...kept, ...suggestions],
    updatedAt: now,
  };
};

// 采用/忽略建议；旧调用默认新建，只有作者明确选择的资料可更新。
export const resolveSuggestion = (
  project: NovelProject, suggestionId: string, accept: boolean, now: number,
  edit?: NovelMaterialAdoption,
): NovelMutation<NovelMaterial | null> => {
  const idx: number = project.materialSuggestions.findIndex(s => s.id === suggestionId);
  if (idx < 0) throw notFound('suggestion', suggestionId);
  const sug: NovelMaterialSuggestion = project.materialSuggestions[idx];
  if (sug.status !== 'pending') {
    return { project: project, value: null };
  }

  const suggestions: NovelMaterialSuggestion[] = project.materialSuggestions.slice();
  let materials: NovelMaterial[] = project.materials;
  let material: NovelMaterial | null = null;

  if (accept) {
    const baseline: NovelMaterialAdoption = buildSuggestionAdoption(project, sug);
    const adopted = applyMaterialAdoption(project, edit ?? baseline, baseline.sourceDigest, now);
    material = adopted.material;
    materials = adopted.materials;
  }

  suggestions[idx] = {
    ...sug, status: accept ? 'accepted' : 'rejected', resolvedAt: now,
  };
  return {
    project: { ...project, materials: materials, materialSuggestions: suggestions, updatedAt: now },
    value: material,
  };
};

// 设定提案使用与章节建议一致的显式采用目标；拒绝只结案。
export const resolveSettingProposal = (
  project: NovelProject, proposalId: string, accept: boolean, now: number,
  edit?: NovelMaterialAdoption,
): NovelMutation<NovelMaterial | null> => {
  const idx: number = project.settingProposals.findIndex(
    (proposal: NovelSettingProposal): boolean => proposal.id === proposalId);
  if (idx < 0) throw notFound('setting_proposal', proposalId);
  const proposal: NovelSettingProposal = project.settingProposals[idx];
  if (proposal.status !== 'pending') return { project: project, value: null };

  let materials: NovelMaterial[] = project.materials;
  let material: NovelMaterial | null = null;
  if (accept) {
    const baseline: NovelMaterialAdoption = buildSettingProposalAdoption(project, proposal);
    const adopted = applyMaterialAdoption(project, edit ?? baseline, baseline.sourceDigest, now);
    material = adopted.material;
    materials = adopted.materials;
  }

  const proposals: NovelSettingProposal[] = project.settingProposals.slice();
  proposals[idx] = {
    ...proposal,
    status: accept ? 'accepted' : 'rejected',
    resolvedAt: now,
  };
  return {
    project: { ...project, materials: materials, settingProposals: proposals, updatedAt: now },
    value: material,
  };
};
