// novel/context_builder — 生成上下文装配(移植自 Android NovelContextBuilder.kt)
// 单一 systemPrompt:指令变体 + 活资料(20k 头截) + 章节正文(末3章,16k 尾截)
//   + 最近对话(末16条,10k 尾截),整体 cap 48k。用户提示词即原始 userText。

import { MATERIAL_KIND_LABELS } from './models.ts';
import { novelPromptTemplate } from './prompt_catalog.ts';
import type { NovelRunKind } from './prompt_catalog.ts';
import type {
  NovelProject, NovelChatMode, NovelGenerationGranularity, NovelMaterial, NovelMessage, NovelChapter } from './models.ts';

// 预算常量(照搬 Android)
export const MAX_USER_CHARS = 8_000;
export const MAX_OUTPUT_CHARS = 256_000;
export const MAX_SYSTEM_CHARS = 48_000;
const MATERIAL_CHARS = 20_000;
const CHAPTER_CHARS = 16_000;
const MESSAGE_CHARS = 10_000;

const CONTINUATION_INSTRUCTION =
  '紧接现有正文续写一个自然片段，保持人物、语气与情节连贯，不要重复已有内容，不要输出章节标题。';
const WHOLE_CHAPTER_INSTRUCTION =
  '创作完整的新一章，包含场景、对话与情节推进，结构完整、节奏自然。';
const DISCUSS_INSTRUCTION =
  '你是小说策划伙伴，和我讨论设定、人物与情节。给出建议与选项，但不把建议冒充既定事实，不直接写正文。';

// 尾部保留截断(章节/对话用,保住最新内容)
const tail = (s: string, max: number): string =>
  s.length <= max ? s : s.slice(s.length - max);

export const systemPrompt = (
  project: NovelProject,
  mode: NovelChatMode,
  granularity: NovelGenerationGranularity | null = null,
  runKind: NovelRunKind | null = null,
): string => {
  const gran: NovelGenerationGranularity = granularity ?? project.lastGenerationGranularity;
  // N1:显式 runKind → 用目录模板(6 种 Run);否则保持旧指令变体行为
  if (runKind !== null) {
    const tpl: string = novelPromptTemplate(runKind).systemText;
    const sectionsKinded: string[] = [tpl];
    const matsK: NovelMaterial[] = project.materials
      .filter(m => m.enabled)
      .sort((a, b) => b.updatedAt - a.updatedAt);
    if (matsK.length > 0) {
      const bodyK: string = matsK
        .map(m => `[${MATERIAL_KIND_LABELS[m.kind]}] ${m.title}\n${m.content}`)
        .join('\n\n');
      if (bodyK.length > 0) sectionsKinded.push('# 活资料\n' + bodyK.slice(0, MATERIAL_CHARS));
    }
    const chaptersK: NovelChapter[] = project.chapters
      .filter(c => !c.discarded)
      .slice(-3);
    if (chaptersK.length > 0) {
      const bodyC: string = chaptersK
        .map(c => `# ${c.title}\n${c.content}`)
        .join('\n\n');
      sectionsKinded.push('# 章节正文\n' + tail(bodyC, CHAPTER_CHARS));
    }
    const msgsK: NovelMessage[] = project.messages.slice(-16);
    if (msgsK.length > 0) {
      const bodyM: string = msgsK
        .map(m => `${m.role === 'user' ? '作者' : '助手'}: ${m.content}`)
        .join('\n\n');
      sectionsKinded.push('# 最近对话\n' + tail(bodyM, MESSAGE_CHARS));
    }
    return sectionsKinded.join('\n\n').slice(0, MAX_SYSTEM_CHARS);
  }
  const granLegacy: NovelGenerationGranularity = granularity ?? project.lastGenerationGranularity;
  let instruction: string;
  if (mode === 'discuss') instruction = DISCUSS_INSTRUCTION;
  else if (granLegacy === 'continuation') instruction = CONTINUATION_INSTRUCTION;
  else instruction = WHOLE_CHAPTER_INSTRUCTION;

  const sections: string[] = [instruction];

  // 活资料:启用素材,按 updatedAt 倒序
  const mats: NovelMaterial[] = project.materials
    .filter(m => m.enabled)
    .sort((a, b) => b.updatedAt - a.updatedAt);
  if (mats.length > 0) {
    const body: string = mats
      .map(m => `[${MATERIAL_KIND_LABELS[m.kind]}] ${m.title}\n${m.content}`)
      .join('\n\n');
    if (body.length > 0) sections.push('# 活资料\n' + body.slice(0, MATERIAL_CHARS));
  }

  // 章节正文:末 3 章,尾截
  const lastChapters = project.chapters.slice(-3);
  if (lastChapters.length > 0) {
    const body: string = lastChapters.map(c => `## ${c.title}\n${c.content}`).join('\n\n');
    if (body.length > 0) sections.push('# 章节正文\n' + tail(body, CHAPTER_CHARS));
  }

  // 最近对话:末 16 条,尾截
  const lastMsgs: NovelMessage[] = project.messages.slice(-16);
  if (lastMsgs.length > 0) {
    const body: string = lastMsgs
      .map(m => `${m.role === 'user' ? '用户' : '助手'}：${m.content}`)
      .join('\n');
    if (body.length > 0) sections.push('# 最近对话\n' + tail(body, MESSAGE_CHARS));
  }

  return sections.join('\n\n').slice(0, MAX_SYSTEM_CHARS);
};
