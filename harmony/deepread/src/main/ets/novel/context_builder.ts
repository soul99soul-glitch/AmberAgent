import { confirmedChapterPlanText } from './chapter_contract.ts';
// Novel 上下文只包含领域事实；canonical 对话由发送端单独裁剪。
import { classifyNovelMaterials } from './material_injection.ts';
import type { NovelInjectionOverrides } from './material_injection.ts';
import { novelPromptTemplate } from './prompt_catalog.ts';
import type { NovelRunKind } from './prompt_catalog.ts';
import type { NovelProject, NovelChatMode, NovelGenerationGranularity } from './models.ts';
import type { NovelContextSection, NovelRequestContext } from './model_running.ts';
import { chapterPlotSourceDigest } from './plot_projection.ts';
import { emptyNovelStructuredState, pruneNovelStructuredState } from './structured_state.ts';
import { effectiveNovelMaterials } from './material_inheritance.ts';

export const MAX_USER_CHARS = 8_000;
export const MAX_OUTPUT_CHARS = 256_000;
export const MAX_SYSTEM_CHARS = 48_000;

const CONTINUATION_INSTRUCTION =
  '紧接现有正文续写一个自然片段，保持人物、语气与情节连贯，不要重复已有内容，不要输出章节标题。';
const WHOLE_CHAPTER_INSTRUCTION =
  '创作完整的新一章，包含场景、对话与情节推进，结构完整、节奏自然。';
const DISCUSS_INSTRUCTION =
  '你是小说策划伙伴，和我讨论设定、人物与情节。给出建议与选项，但不把建议冒充既定事实，不直接写正文。\n'
  + '需要作者补充关键信息时用 ask_user；具体工作区变更用 novel_workspace_write 提交待确认提案，作者批准后才写入。';

export const novelComposerRunKind = (
  mode: NovelChatMode, granularity: NovelGenerationGranularity | null,
): NovelRunKind => mode === 'discuss' ? 'discussion'
  : granularity === 'continuation' ? 'prose_continuation' : 'prose_whole_chapter';

export const novelStructuredStateContext = (project: NovelProject): string => {
  const state = pruneNovelStructuredState(project.structuredState ?? emptyNovelStructuredState(),
    project.chapters, effectiveNovelMaterials(project));
  return [
    ...state.events.map(event => `事件：${event.summary}\n来源章 ${event.chapterId} / ${event.sourceDigest}\n原文：${event.quote}\n人物ID：${event.entityRefs.join(', ')}`),
    ...state.identityClarifications.map(item => `作者身份确认：${item.mention} / ${item.action} / ${item.materialId ?? '不作为人物身份'}`),
  ].join('\n\n');
};

export const buildNovelContext = (
  project: NovelProject, mode: NovelChatMode,
  granularity: NovelGenerationGranularity | null = null, runKind: NovelRunKind | null = null,
  userText: string = '', overrides?: NovelInjectionOverrides,
): NovelRequestContext => {
  const gran = granularity ?? project.lastGenerationGranularity;
  const instruction = runKind !== null ? novelPromptTemplate(runKind).systemText
    : mode === 'discuss' ? DISCUSS_INSTRUCTION
      : gran === 'continuation' ? CONTINUATION_INSTRUCTION : WHOLE_CHAPTER_INSTRUCTION;
  const sections: NovelContextSection[] = [{ key: 'instruction', text: instruction, required: true }];
  const add = (key: string, title: string, text: string, required: boolean): void => {
    if (text.trim().length > 0) sections.push({ key, text: `# ${title}\n${text}`, required });
  };
  const settings = project.branchSettings;
  add('chapter_plan', '作者本章计划', confirmedChapterPlanText(settings), true);
  add('preferences', '作者写作偏好', settings.preferences, true);
  add('author_plot', '作者剧情资料', project.authorPlot ?? '', true);
  add('structured_state', '已取证事件与作者身份确认', novelStructuredStateContext(project), true);
  add('confirmed_decisions', '作者已确认决定', settings.confirmedDecisions
    .map(decision => `${decision.title}：${decision.content}`).join('\n'), true);
  add('foreshadows', '未解伏笔', settings.foreshadows.filter(item => item.status === 'open')
    .map(item => `${item.title}：${item.content}`).join('\n'), true);
  if (mode === 'write' && gran === 'whole_chapter') {
    add('future_plan', '未来方向（不替代本章计划）', settings.futurePlan, true);
  }
  const archives = project.discussionArchives ?? [];
  if (archives.length > 0) {
    add('discussion_archive', '讨论归档',
      '下文为讨论背景；未决想法不是正式设定，正式资料和作者已确认决定优先。\n' + archives[archives.length - 1].summary, true);
  }
  const materialDecisions = classifyNovelMaterials(project, userText, overrides);
  materialDecisions.filter(material => material.included).forEach(material => {
    sections.push({ key: `material:${material.materialId}`, text: material.text, required: material.protected });
  });
  const activeChapters = project.chapters.filter(chapter => !chapter.discarded);
  activeChapters.slice(-3).reverse().forEach(chapter =>
    add(`chapter:${chapter.id}`, '章节正文', `## ${chapter.title}\n${chapter.content}`, false));
  (project.chapterPlots ?? []).slice().reverse().forEach(pointer => {
    const chapter = activeChapters.find(item => item.id === pointer.chapterId);
    if (chapter !== undefined && !pointer.stale && pointer.sourceDigest === chapterPlotSourceDigest(chapter.content)) {
      add(`plot:${pointer.chapterId}`, '正文剧情指针', pointer.text, false);
    }
  });
  const archivedIds = new Set<string>();
  archives.forEach(archive => archive.sourceMessageIds.forEach(id => archivedIds.add(id)));
  return {
    sections,
    materialDecisions,
    excludedHistoryMessageIds: project.messages.filter(message => archivedIds.has(message.id))
      .map(message => message.uiMessage.id),
  };
};

// 兼容只消费字符串的调用方。作者约束完整保留，软性历史块只整块选择，绝不切断指令。
export const systemPrompt = (
  project: NovelProject, mode: NovelChatMode,
  granularity: NovelGenerationGranularity | null = null, runKind: NovelRunKind | null = null,
  userText: string = '', overrides?: NovelInjectionOverrides,
): string => {
  const sections = buildNovelContext(project, mode, granularity, runKind, userText, overrides).sections;
  const selected = sections.filter(section => section.required);
  let size = selected.reduce((total, section) => total + section.text.length + 2, 0);
  sections.filter(section => !section.required).forEach(section => {
    if (size + section.text.length + 2 <= MAX_SYSTEM_CHARS) {
      selected.push(section);
      size += section.text.length + 2;
    }
  });
  return selected.map(section => section.text).join('\n\n');
};
