// Dedicated project actions use the existing durable workspace proposal transaction.
import { invalidInput, notFound } from './error.ts';
import { defaultGhostwriteDigest } from './ghostwrite.ts';
import { materialSuggestionChapterDigest } from './material_adoption.ts';
import { normalizeNovelMaterialFields } from './material_fields.ts';
import { withBranchMaterialEdits, withUpdatedSharedMaterials, initializeSharedMaterials } from './material_inheritance.ts';
import { withNovelChapterContract, withNovelUpcomingArc, chapterContractMarkdown } from './chapter_contract.ts';
import { makeNovelMaterial, novelChapterOrdinal, novelId, MAX_CHAPTER_CHARS, MAX_MATERIAL_CHARS, MAX_TITLE_CHARS } from './models.ts';
import type { NovelChapter, NovelMaterialKind, NovelMaterialInjectionMode, NovelProject } from './models.ts';
import { deleteChapter, saveChapter, saveChapterVersion } from './mutations.ts';
import type { WorkspaceProposalReview } from './workspace_history.ts';

export type NovelProjectOperationKind = 'rename_project' | 'set_polish_preference' | 'upsert_upcoming_arc'
  | 'clear_upcoming_arc' | 'revise_material' | 'propose_chapter_plan' | 'prepare_ghostwrite'
  | 'set_chapter_title' | 'list_chapters' | 'read_chapter' | 'revise_chapter'
  | 'revert_recent_chapters' | 'delete_chapters' | 'list_setting_proposals' | 'reject_setting_proposals';
export interface NovelProjectToolInput {
  title?: string; preference?: string; beats?: string[]; kind?: string; material_id?: string;
  content?: string; aliases?: string[]; tags?: string[]; custom_name?: string;
  injection_mode?: NovelMaterialInjectionMode; scope?: 'shared' | 'branch';
  outline_placement?: string; goal_and_conflict?: string; must_happen?: string[]; must_not_happen?: string[];
  ending_hook?: string; visible_facts?: string[]; upcoming_arc?: string[]; suggested_chapter_count?: number;
  chapter_id?: string; chapter_ordinal?: number; start_paragraph?: number; end_paragraph?: number;
  new_text?: string; expected_text?: string; source_digest?: string; chapter_count?: number;
  chapter_ids?: string[]; chapter_ordinals?: number[]; proposal_ids?: string[]; reason?: string;
}
export interface NovelSpecializedOperation {
  kind: NovelProjectOperationKind;
  args: NovelProjectToolInput;
  restoreHead?: string;
}
export interface NovelChapterParagraph { index: number; text: string; start: number; end: number; }
export interface NovelProjectToolResult {
  status: string; proposal_id?: string; requires_author_approval?: boolean; summary?: string;
  content?: string; source_digest?: string; truncated?: boolean;
  chapters?: { id: string; ordinal: number; title: string; characters: number; paragraph_count: number }[];
  paragraphs?: NovelChapterParagraph[];
  setting_proposals?: { id: string; title: string; content: string }[];
}
const KINDS: NovelProjectOperationKind[] = ['rename_project', 'set_polish_preference', 'upsert_upcoming_arc',
  'clear_upcoming_arc', 'revise_material', 'propose_chapter_plan', 'prepare_ghostwrite', 'set_chapter_title',
  'list_chapters', 'read_chapter', 'revise_chapter', 'revert_recent_chapters', 'delete_chapters',
  'list_setting_proposals', 'reject_setting_proposals'];
export const novelProjectOperationKind = (name: string): NovelProjectOperationKind => {
  const clean = name.startsWith('novel_') ? name.slice(6) : name;
  if (!KINDS.includes(clean as NovelProjectOperationKind)) throw invalidInput('未知小说专属操作');
  return clean as NovelProjectOperationKind;
};
const text = (value: string | undefined, label: string, max: number, allowEmpty = false): string => {
  if (typeof value !== 'string') throw invalidInput(`${label} 必须为文本`);
  const clean = value.trim();
  if ((!allowEmpty && clean.length === 0) || clean.length > max) throw invalidInput(`${label} 为空或过长`);
  return clean;
};
const integer = (value: number | undefined, label: string, min: number, max: number): number => {
  if (!Number.isInteger(value) || value! < min || value! > max) throw invalidInput(`${label} 超出范围`);
  return value!;
};
const strings = (value: string[] | undefined, label: string, maxCount = 32, maxChars = 4000): string[] => {
  if (!Array.isArray(value) || value.length > maxCount) throw invalidInput(`${label} 条目无效`);
  return value.map(item => text(item, label, maxChars));
};
export const novelChapterParagraphs = (content: string): NovelChapterParagraph[] => {
  const paragraphs: NovelChapterParagraph[] = [];
  // Boundaries follow blank lines; offsets point into the original UTF-16 text,
  // preserving untouched whitespace and CRLF when a range is replaced.
  const lines = /([^\r\n]*)(\r\n|\n|\r|$)/g;
  let first: number | null = null;
  let last = 0;
  const flush = (): void => {
    if (first !== null) paragraphs.push({ index: paragraphs.length + 1, text: content.slice(first, last), start: first, end: last });
    first = null;
  };
  let match: RegExpExecArray | null;
  while ((match = lines.exec(content)) !== null && match[0].length > 0) {
    if (match[1].trim().length === 0) flush();
    else { if (first === null) first = match.index; last = match.index + match[1].length; }
  }
  flush();
  return paragraphs;
};
export const novelOperationSourceDigest = (project: NovelProject): string => defaultGhostwriteDigest(JSON.stringify({
  name: project.name, polishPreference: project.polishPreference, branchSettings: project.branchSettings,
  chapters: project.chapters, materials: project.materials, baseMaterials: project.baseMaterials,
  materialOverrides: project.materialOverrides, hiddenMaterialIds: project.hiddenMaterialIds,
  settingProposals: project.settingProposals,
}));
const working = (project: NovelProject): NovelChapter[] => project.chapters.filter(chapter => !chapter.discarded);
const resolveChapter = (project: NovelProject, args: NovelProjectToolInput): NovelChapter => {
  const chapters = working(project);
  const byId = args.chapter_id === undefined ? undefined : chapters.find(chapter => chapter.id === args.chapter_id);
  const byOrdinal = args.chapter_ordinal === undefined ? undefined : project.chapters.find((chapter, index) =>
    !chapter.discarded && novelChapterOrdinal(chapter, index + 1) === args.chapter_ordinal);
  if ((args.chapter_id !== undefined && byId === undefined) || (args.chapter_ordinal !== undefined && byOrdinal === undefined)) throw notFound('chapter', args.chapter_id ?? String(args.chapter_ordinal));
  if (byId !== undefined && byOrdinal !== undefined && byId.id !== byOrdinal.id) throw invalidInput('章节 id 与序号不一致');
  const chapter = byId ?? byOrdinal ?? chapters[chapters.length - 1];
  if (chapter === undefined) throw invalidInput('当前分支没有正文');
  return chapter;
};
const selectedChapters = (project: NovelProject, args: NovelProjectToolInput): NovelChapter[] => {
  if (!args.chapter_ids?.length && !args.chapter_ordinals?.length) throw invalidInput('必须明确选择章节');
  const selected: NovelChapter[] = [];
  for (const id of args.chapter_ids ?? []) selected.push(resolveChapter(project, { chapter_id: id }));
  for (const ordinal of args.chapter_ordinals ?? []) selected.push(resolveChapter(project, { chapter_ordinal: ordinal }));
  return Array.from(new Map(selected.map(chapter => [chapter.id, chapter])).values());
};
export const readNovelProjectOperation = (project: NovelProject, kind: NovelProjectOperationKind,
  args: NovelProjectToolInput): NovelProjectToolResult | null => {
  if (kind === 'list_chapters') return { status: 'read', chapters: working(project).map(chapter => ({
    id: chapter.id, ordinal: novelChapterOrdinal(chapter, project.chapters.indexOf(chapter) + 1), title: chapter.title,
    characters: chapter.content.length, paragraph_count: novelChapterParagraphs(chapter.content).length,
  })) };
  if (kind === 'list_setting_proposals') return { status: 'read', setting_proposals: project.settingProposals
    .filter(proposal => proposal.status === 'pending').map(proposal => ({ id: proposal.id, title: proposal.title, content: proposal.content })) };
  if (kind !== 'read_chapter') return null;
  const chapter = resolveChapter(project, args);
  const paragraphs = novelChapterParagraphs(chapter.content);
  const start = args.start_paragraph === undefined ? 1 : integer(args.start_paragraph, '起始段', 1, paragraphs.length);
  const end = args.end_paragraph === undefined ? paragraphs.length : integer(args.end_paragraph, '结束段', start, paragraphs.length);
  const selected = paragraphs.slice(start - 1, end);
  const full = selected.map(paragraph => `[${paragraph.index}] ${paragraph.text}`).join('\n\n');
  let includedLength = 0;
  const included: NovelChapterParagraph[] = [];
  for (const paragraph of selected) {
    includedLength += (included.length === 0 ? 0 : 2) + `[${paragraph.index}] `.length + paragraph.text.length;
    if (includedLength > 24000) break;
    included.push(paragraph);
  }
  return { status: 'read', content: full.slice(0, 24000), truncated: full.length > 24000,
    source_digest: materialSuggestionChapterDigest(chapter), paragraphs: included };
};
export const applyNovelSpecializedOperation = (
  project: NovelProject, operation: NovelSpecializedOperation, branchId: string, now: number,
  historical?: NovelProject,
): NovelProject => {
  const args = operation.args;
  switch (operation.kind) {
    case 'rename_project': return { ...project, name: text(args.title, '项目名', MAX_TITLE_CHARS), updatedAt: now };
    case 'set_polish_preference': return { ...project, polishPreference: text(args.preference, '润色偏好', 8000, true), updatedAt: now };
    case 'upsert_upcoming_arc': return withNovelUpcomingArc(project, strings(args.beats, '后续节拍', 8, 160), now);
    case 'clear_upcoming_arc': return withNovelUpcomingArc(project, [], now);
    case 'propose_chapter_plan':
    case 'prepare_ghostwrite': {
      const preparing = operation.kind === 'prepare_ghostwrite';
      if (!preparing && project.branchSettings.chapterContract?.status === 'confirmed') throw invalidInput('已确认合同不能由讨论提案降级为草稿');
      let next = withNovelChapterContract(project, {
        outlinePlacement: args.outline_placement ?? '', goalAndConflict: text(args.goal_and_conflict, '目标与冲突', 8000),
        mustHappen: strings(args.must_happen ?? [], '必须发生'), mustNotHappen: strings(args.must_not_happen ?? [], '禁止发生'),
        endingHook: args.ending_hook ?? '', visibleFacts: strings(args.visible_facts ?? [], '可见事实'),
      }, preparing ? 'confirmed' : 'draft', branchId, now);
      if (preparing) {
        next = { ...next, branchSettings: { ...next.branchSettings, suggestedChapterCount: integer(args.suggested_chapter_count, '代笔章数', 1, 10) } };
        if (!(args.must_happen?.length)) throw invalidInput('代笔合同至少需要一条必须发生');
        next = withNovelUpcomingArc(next, strings(args.upcoming_arc ?? [], '后续节拍', 8, 160), now);
      }
      return next;
    }
    case 'revise_material': {
      if (args.scope !== undefined && args.scope !== 'shared' && args.scope !== 'branch') throw invalidInput('资料作用范围无效');
      const existing = args.scope === 'branch' ? project.materials.find(item => item.id === args.material_id)
        : project.baseMaterials?.find(item => item.id === args.material_id) ?? project.materials.find(item => item.id === args.material_id);
      if (args.material_id !== undefined && existing === undefined && !args.material_id.startsWith('novel-operation-new-')) throw notFound('material', args.material_id);
      const kind = args.kind === 'custom' ? 'other' : args.kind === 'masterOutline' ? 'outline' : args.kind === 'writingRequirements' ? 'requirement' : args.kind;
      if (!['world', 'character', 'relationship', 'outline', 'requirement', 'other'].includes(kind ?? '')) throw invalidInput('资料类型无效');
      if (existing !== undefined && existing.kind !== kind) throw invalidInput('已有资料不能更改类型');
      if (args.injection_mode !== undefined && !['always', 'smart', 'off'].includes(args.injection_mode)) throw invalidInput('资料注入模式无效');
      const fields = normalizeNovelMaterialFields({ aliases: args.aliases === undefined ? existing?.aliases : strings(args.aliases, '别名'),
        tags: args.tags === undefined ? existing?.tags : strings(args.tags, '标签'), customKind: args.custom_name ?? existing?.customKind,
        injectionMode: args.injection_mode ?? existing?.injectionMode ?? 'smart' }, existing?.enabled ?? true);
      const material = { ...makeNovelMaterial({ id: args.material_id ?? `novel-operation-new-${novelId()}`, kind: kind as NovelMaterialKind,
        title: text(args.title, '资料名称', MAX_TITLE_CHARS), content: text(args.content, '资料正文', MAX_MATERIAL_CHARS), now,
        enabled: fields.injectionMode !== 'off' }), ...fields, createdAt: existing?.createdAt ?? now };
      if (args.scope === 'branch') return withBranchMaterialEdits(project,
        project.materials.filter(item => item.id !== material.id).concat([material]));
      const shared = initializeSharedMaterials(project);
      return { ...withUpdatedSharedMaterials(shared, shared.baseMaterials ?? [],
        (shared.baseMaterials ?? []).filter(item => item.id !== material.id).concat([material])), updatedAt: now };
    }
    case 'set_chapter_title':
    case 'revise_chapter': {
      const chapter = resolveChapter(project, args);
      let title = chapter.title;
      let content = chapter.content;
      if (operation.kind === 'set_chapter_title') title = text(args.title, '章节标题', MAX_TITLE_CHARS);
      else {
        const digest = materialSuggestionChapterDigest(chapter);
        if (args.source_digest !== undefined && args.source_digest !== digest) throw invalidInput('正文来源已变化');
        const paragraphs = novelChapterParagraphs(content);
        const start = integer(args.start_paragraph, '起始段', 1, paragraphs.length);
        const end = integer(args.end_paragraph, '结束段', start, paragraphs.length);
        const oldText = content.slice(paragraphs[start - 1].start, paragraphs[end - 1].end);
        if (args.expected_text !== undefined && args.expected_text !== oldText) throw invalidInput('段落原文已变化');
        const replacement = text(args.new_text, '替换正文', 32000);
        content = content.slice(0, paragraphs[start - 1].start) + replacement + content.slice(paragraphs[end - 1].end);
      }
      if (content.length > MAX_CHAPTER_CHARS) throw invalidInput('替换后的章节过长，未截断或写入');
      const archived = saveChapterVersion(project, chapter.id, 'manual', now).project;
      return saveChapter(archived, chapter.id, title, content, now).project;
    }
    case 'delete_chapters': {
      let next = project;
      for (const chapter of selectedChapters(project, args)) next = deleteChapter(next, chapter.id, now);
      return next;
    }
    case 'reject_setting_proposals': {
      const pending = project.settingProposals.filter(proposal => proposal.status === 'pending');
      const ids = args.proposal_ids?.length ? strings(args.proposal_ids, '设定提案 id', 128) : pending.map(proposal => proposal.id);
      if (ids.some(id => !pending.some(proposal => proposal.id === id))) throw invalidInput('找不到待确认设定提案');
      return { ...project, settingProposals: project.settingProposals.map(proposal => ids.includes(proposal.id)
        ? { ...proposal, status: 'rejected', resolvedAt: now } : proposal), updatedAt: now };
    }
    case 'revert_recent_chapters': {
      integer(args.chapter_count, '回退章数', 1, 64);
      if (historical === undefined || operation.restoreHead === undefined) throw invalidInput('缺少真实历史检查点');
      const next = project.baseMaterials === undefined ? historical
        : withBranchMaterialEdits({ ...historical, baseMaterials: project.baseMaterials }, historical.materials);
      return { ...next, name: project.name, modelPolicy: project.modelPolicy, branches: project.branches,
        creationMode: project.creationMode, quickStartSeed: project.quickStartSeed, polishPreference: project.polishPreference, updatedAt: now };
    }
    default: throw invalidInput('读取操作不能作为写入提案');
  }
};
const operationLabel = (kind: NovelProjectOperationKind): string => ({ rename_project: '修改项目名称', set_polish_preference: '修改润色偏好', upsert_upcoming_arc: '设置后续章节节拍', clear_upcoming_arc: '清除后续章节节拍', revise_material: '修改创作资料', propose_chapter_plan: '提出本章计划', prepare_ghostwrite: '确认代笔合同', set_chapter_title: '修改章节标题', list_chapters: '查看章节', read_chapter: '阅读章节', revise_chapter: '替换指定段落', revert_recent_chapters: '回退最近章节', delete_chapters: '删除所选章节', list_setting_proposals: '查看设定提案', reject_setting_proposals: '拒绝设定提案' })[kind];
export const makeNovelSpecializedReview = (project: NovelProject, next: NovelProject,
  operation: NovelSpecializedOperation): WorkspaceProposalReview => {
  const args = operation.args;
  const previews: WorkspaceProposalReview['previews'] = [];
  if (operation.kind === 'revise_chapter' || operation.kind === 'set_chapter_title') {
    const chapter = resolveChapter(project, args);
    const after = next.chapters.find(item => item.id === chapter.id)!;
    const paragraphs = novelChapterParagraphs(chapter.content);
    const start = args.start_paragraph === undefined ? undefined : paragraphs[args.start_paragraph - 1].start;
    const end = args.end_paragraph === undefined ? undefined : paragraphs[args.end_paragraph - 1].end;
    previews.push({ label: chapter.title, chapterId: chapter.id,
      oldText: operation.kind === 'set_chapter_title' ? chapter.title : chapter.content.slice(start, end),
      newText: operation.kind === 'set_chapter_title' ? after.title : args.new_text!.trim(),
      startParagraph: args.start_paragraph, endParagraph: args.end_paragraph, start, end,
      sourceDigest: materialSuggestionChapterDigest(chapter) });
  } else if (operation.kind === 'delete_chapters' || operation.kind === 'revert_recent_chapters') {
    for (const chapter of project.chapters.filter(item => !next.chapters.some(after => after.id === item.id))) {
      previews.push({ label: chapter.title, chapterId: chapter.id, oldText: chapter.content, newText: '', sourceDigest: materialSuggestionChapterDigest(chapter) });
    }
  } else if (operation.kind === 'revise_material') {
    const before = args.scope === 'branch' ? project.materials.find(item => item.id === args.material_id)
      : project.baseMaterials?.find(item => item.id === args.material_id) ?? project.materials.find(item => item.id === args.material_id);
    const after = args.scope === 'branch' ? next.materials.find(item => item.id === args.material_id)
      : next.baseMaterials?.find(item => item.id === args.material_id) ?? next.materials.find(item => item.id === args.material_id);
    previews.push({ label: `${after?.title ?? '资料'}（${args.scope === 'branch' ? '本分支' : '项目共享'}）`, oldText: before?.content ?? '', newText: after?.content ?? '' });
  } else if (operation.kind === 'reject_setting_proposals') {
    for (const proposal of project.settingProposals.filter(item => item.status === 'pending' && next.settingProposals.find(after => after.id === item.id)?.status === 'rejected'))
      previews.push({ label: proposal.title, oldText: proposal.content, newText: '拒绝采用此设定提案' });
  } else previews.push({ label: operationLabel(operation.kind),
    oldText: operation.kind === 'rename_project' ? project.name : operation.kind === 'set_polish_preference' ? project.polishPreference ?? '' : operation.kind.includes('arc') ? project.branchSettings.futurePlan : project.branchSettings.chapterContract === undefined ? project.branchSettings.thisChapterPlan : chapterContractMarkdown(project.branchSettings.chapterContract),
    newText: operation.kind === 'rename_project' ? next.name : operation.kind === 'set_polish_preference' ? next.polishPreference ?? '' : operation.kind.includes('arc') ? next.branchSettings.futurePlan : next.branchSettings.chapterContract === undefined ? next.branchSettings.thisChapterPlan : chapterContractMarkdown(next.branchSettings.chapterContract) });
  if (operation.kind === 'prepare_ghostwrite') previews.push({ label: '代笔章数与后续方向',
    oldText: `建议章数：${project.branchSettings.suggestedChapterCount ?? 1}\n${project.branchSettings.futurePlan}`,
    newText: `建议章数：${args.suggested_chapter_count}\n${next.branchSettings.futurePlan}` });
  return { summary: `${operation.kind === 'revise_material' ? args.scope === 'branch' ? '修改本分支创作资料' : '修改项目共享创作资料' : operationLabel(operation.kind)}${args.reason?.trim() ? `：${args.reason.trim()}` : ''}`,
    sourceDigest: novelOperationSourceDigest(project), previews };
};
export const validateNovelSpecializedOperation = (operation: NovelSpecializedOperation): NovelSpecializedOperation => {
  const kind = novelProjectOperationKind(operation.kind);
  if (!(operation.args instanceof Object) || Array.isArray(operation.args)) throw invalidInput('专属操作参数无效');
  if (operation.restoreHead !== undefined && (typeof operation.restoreHead !== 'string' || operation.restoreHead.length === 0)) throw invalidInput('检查点无效');
  return { kind, args: operation.args, ...(operation.restoreHead === undefined ? {} : { restoreHead: operation.restoreHead }) };
};
export const makeNovelProjectOperation = (kind: NovelProjectOperationKind, args: NovelProjectToolInput): NovelSpecializedOperation => {
  const frozen = JSON.parse(JSON.stringify(args)) as NovelProjectToolInput;
  if (kind === 'revise_material' && frozen.material_id === undefined) frozen.material_id = `novel-operation-new-${novelId()}`;
  return { kind, args: frozen };
};
