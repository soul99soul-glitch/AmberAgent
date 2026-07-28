// novel/mutations — 项目纯函数变更(移植自 Android NovelMutations.kt)
// 每个函数取 (project, …, now) 返回新 NovelProject(不可变 spread)。校验失败抛 NovelError。

import {
  MAX_PROJECT_NAME_CHARS, MAX_TITLE_CHARS, MAX_CHAPTER_CHARS, MAX_MATERIAL_CHARS,
  makeNovelProject, makeNovelChapter, makeNovelMaterial,
} from './models.ts';
import type {
  NovelProject, NovelMessage, NovelChapter, NovelMaterial, NovelMaterialSuggestion,
  NovelMaterialKind, NovelCollectionTarget,
} from './models.ts';
import { invalidInput, notFound, alreadyCollected } from './error.ts';

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

export const setProjectModel = (
  project: NovelProject, modelId: string | null, now: number,
): NovelProject => ({
  ...project,
  modelId: modelId,
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
    const chapter: NovelChapter = makeNovelChapter({ title: cleanT, content: body, now: now });
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
  const chapters: NovelChapter[] = project.chapters.filter(c => c.id !== chapterId);
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
  title: string, content: string, enabled: boolean, now: number,
): NovelMutation<NovelMaterial> => {
  const cleanT: string = cleanTitle(title);
  const body: string = content.slice(0, MAX_MATERIAL_CHARS);
  if (materialId === null) {
    const material: NovelMaterial =
      makeNovelMaterial({ kind: kind, title: cleanT, content: body, enabled: enabled, now: now });
    return {
      project: { ...project, materials: [...project.materials, material], updatedAt: now },
      value: material,
    };
  }
  const idx: number = project.materials.findIndex(m => m.id === materialId);
  if (idx < 0) throw notFound('material', materialId);
  const updated: NovelMaterial = {
    ...project.materials[idx], kind: kind, title: cleanT, content: body, enabled: enabled, updatedAt: now,
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

// 收录助手回复进章节
export const collect = (
  project: NovelProject, messageId: string, target: NovelCollectionTarget, now: number,
): NovelMutation<NovelChapter> => {
  const msgIdx: number = project.messages.findIndex(m => m.id === messageId);
  if (msgIdx < 0) throw notFound('message', messageId);
  const msg: NovelMessage = project.messages[msgIdx];
  if (msg.role !== 'assistant' || msg.mode !== 'write') throw invalidInput('只能收录助手的写作回复');
  if (msg.content.trim().length === 0) throw invalidInput('该回复没有正文');
  if (msg.collectedChapterId !== null) throw alreadyCollected(messageId);

  let chapters: NovelChapter[] = project.chapters.slice();
  let chapter: NovelChapter;
  if (target.kind === 'new_chapter') {
    chapter = makeNovelChapter({ title: cleanTitle(target.title), content: msg.content, now: now });
    chapters = [...chapters, chapter];
  } else {
    const idx: number = chapters.findIndex(c => c.id === target.chapterId);
    if (idx < 0) throw notFound('chapter', target.chapterId);
    const existing: NovelChapter = chapters[idx];
    const newContent: string = target.kind === 'append'
      ? (existing.content.length > 0 ? existing.content + '\n\n' + msg.content : msg.content)
      : msg.content;
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

// 采用/忽略建议;采用时按 kind+归一标题 upsert 素材
export const resolveSuggestion = (
  project: NovelProject, suggestionId: string, accept: boolean, now: number,
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
    const norm: string = normalizeTitle(sug.title);
    const matchIdx: number = project.materials.findIndex(
      m => m.kind === sug.kind && normalizeTitle(m.title) === norm);
    if (matchIdx >= 0) {
      material = { ...project.materials[matchIdx], content: sug.content, updatedAt: now };
      materials = project.materials.slice();
      materials[matchIdx] = material;
    } else {
      material = makeNovelMaterial({
        kind: sug.kind, title: sug.title, content: sug.content, enabled: true, now: now,
      });
      materials = [...project.materials, material];
    }
  }

  suggestions[idx] = {
    ...sug, status: accept ? 'accepted' : 'rejected', resolvedAt: now,
  };
  return {
    project: { ...project, materials: materials, materialSuggestions: suggestions, updatedAt: now },
    value: material,
  };
};
