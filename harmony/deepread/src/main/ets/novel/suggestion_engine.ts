import { parseNovelMaterialFields } from './material_fields.ts';
import type { NovelMaterialFieldsPayload } from './material_fields.ts';
// novel/suggestion_engine — 素材建议引擎(移植自 Android NovelMaterialSuggestionEngine.kt)
// 收录章节后,让模型读章节 + 已有资料,产出严格 JSON 的"活资料"建议(候选,需用户采用)。

import { MATERIAL_KIND_LABELS, makeNovelSuggestion } from './models.ts';
import type {
  NovelProject, NovelChapter, NovelMaterialKind, NovelMaterialSuggestion, NovelModelTarget, NovelMaterialFields,
} from './models.ts';
import type { NovelModelRunning, NovelModelRequest, NovelModelEvent, NovelModelStream } from './model_running.ts';
import { latestAssistantText } from '../agent/message.ts';
import { invalidModelOutput, providerError, isNovelError } from './error.ts';
import { materialSuggestionChapterDigest } from './material_adoption.ts';

export const MAX_SUGGESTIONS = 12;
const SUGGESTION_MAX_OUTPUT_TOKENS = 4_096;
const SUGGESTION_TIMEOUT_MS = 90_000;
const MATERIALS_BLOCK_CHARS = 12_000;
const CHAPTER_TAIL_CHARS = 24_000;
const TITLE_CHARS = 300;
const CONTENT_CHARS = 20_000;

export const SUGGESTION_SYSTEM_PROMPT =
  '你是小说资料整理助手。阅读刚收录的章节，结合已有资料，产出需要新建或更新的"活资料"。\n'
  + '只输出一个完整 JSON 对象，不要代码围栏或额外文字。字符串中的换行必须写成 \\n，不要直接换行。格式示例：\n'
  + '{"suggestions":[{"kind":"world","title":"资料标题","content":"合并新事实后的完整最新资料","aliases":[],"tags":[],"customKind":"","injectionMode":"smart"}]}\n'
  + 'kind 每次只选一个：world、character、relationship、outline、requirement、other。最多 12 条建议，只整理本章已发生的事实。\n'
  + '若无需要更新，输出 {"suggestions":[]}。';

const tail = (s: string, max: number): string =>
  s.length <= max ? s : s.slice(s.length - max);

export const buildSuggestionUserPrompt = (project: NovelProject, chapter: NovelChapter): string => {
  const materialsBlock: string = project.materials
    .filter(m => m.enabled)
    .map(m => `[${MATERIAL_KIND_LABELS[m.kind]}] ${m.title}: ${m.content}`)
    .join('\n')
    .slice(0, MATERIALS_BLOCK_CHARS);
  return `项目：${project.name}\n已有资料：\n${materialsBlock}\n\n刚收录的章节《${chapter.title}》：\n${tail(chapter.content, CHAPTER_TAIL_CHARS)}`;
};

// kind 中英别名归一
export const mapSuggestionKind = (raw: unknown): NovelMaterialKind => {
  const s: string = String(raw ?? '').toLowerCase().trim();
  switch (s) {
    case 'world': case '世界': case '世界观': return 'world';
    case 'character': case '角色': case '人物': return 'character';
    case 'relationship': case '关系': case '人物关系': return 'relationship';
    case 'outline': case '剧情': case '大纲': return 'outline';
    case 'requirement': case '写作要求': case '要求': return 'requirement';
    default: return 'other';
  }
};

interface RawSuggestion extends NovelMaterialFieldsPayload {
  kind?: unknown;
  title?: unknown;
  content?: unknown;
}

interface RawEnvelope {
  suggestions?: unknown;
}

const validSuggestionKind = (raw: unknown): boolean => {
  if (typeof raw !== 'string') return false;
  const value: string = raw.toLowerCase().trim();
  return ['world', '世界', '世界观', 'character', '角色', '人物', 'outline', '剧情', '大纲',
    'requirement', '写作要求', '要求', 'relationship', '关系', '人物关系', 'other', 'custom', '其他', '其它'].includes(value);
};

const suggestionMaterialFields = (item: RawSuggestion): NovelMaterialFields => {
  try { return parseNovelMaterialFields(item); }
  catch (error) {
    throw invalidModelOutput(`资料建议解析失败：${error instanceof Error ? error.message : String(error)}`);
  }
};

// 合法空数组表示没有建议；缺 JSON、坏结构和坏条目都应明确报错。
export const parseSuggestions = (
  text: string, sourceChapterId: string, now: number,
): NovelMaterialSuggestion[] => {
  const start: number = text.indexOf('{');
  const end: number = text.lastIndexOf('}');
  if (start < 0 || end <= start) throw invalidModelOutput('资料建议解析失败：模型未返回完整 JSON');
  const json: string = text.slice(start, end + 1);
  let parsed: RawEnvelope | null;
  try {
    parsed = JSON.parse(json) as RawEnvelope;
  } catch {
    throw invalidModelOutput('资料建议解析失败：JSON 格式无效');
  }
  if (parsed === null || typeof parsed !== 'object' || !Array.isArray(parsed.suggestions)) {
    throw invalidModelOutput('资料建议解析失败：缺少 suggestions 数组');
  }
  const items: RawSuggestion[] = parsed.suggestions as RawSuggestion[];
  const out: NovelMaterialSuggestion[] = [];
  for (let i = 0; i < items.length && out.length < MAX_SUGGESTIONS; i++) {
    const it: RawSuggestion = items[i];
    if (it === null || typeof it !== 'object' || !validSuggestionKind(it.kind)
      || typeof it.title !== 'string' || typeof it.content !== 'string'
      || it.title.trim().length === 0 || it.content.trim().length === 0) {
      throw invalidModelOutput(`资料建议解析失败：第 ${i + 1} 条建议需要有效类别、标题和正文`);
    }
    const title: string = it.title.trim().slice(0, TITLE_CHARS);
    const content: string = it.content.slice(0, CONTENT_CHARS);
    out.push(makeNovelSuggestion({
      sourceChapterId: sourceChapterId,
      kind: mapSuggestionKind(it.kind),
      ...suggestionMaterialFields(it),
      title: title,
      content: content,
      now: now,
    }));
  }
  return out;
};

// 跑一次模型调用,从 canonical snapshot 取最新 assistant 全文;
// completed 解决,failed/超时 拒绝。
export const collectModelText = (
  modelRunning: NovelModelRunning,
  request: NovelModelRequest,
  timeoutMs: number,
): Promise<string> => {
  return new Promise<string>((resolve, reject) => {
    let buffer: string = '';
    let settled: boolean = false;
    let unsub: () => void = (): void => {};
    const timer: ReturnType<typeof setTimeout> = setTimeout(() => {
      if (settled) return;
      settled = true;
      unsub();
      clearTimeout(timer);
      modelRunning.cancel(request.runId);
      reject(providerError('辅助任务超时，请重试'));
    }, timeoutMs);
    let stream: NovelModelStream;
    try {
      stream = modelRunning.start(request);
    } catch (error) {
      settled = true;
      clearTimeout(timer);
      reject(isNovelError(error) ? error : providerError(error instanceof Error ? error.message : String(error)));
      return;
    }
    unsub = stream.subscribe((evt: NovelModelEvent): void => {
      if (settled) return;
      if (evt.kind === 'snapshot') {
        buffer = latestAssistantText(evt.messages);
      } else if (evt.kind === 'completed') {
        settled = true;
        clearTimeout(timer);
        unsub();
        resolve(buffer);
      } else if (evt.kind === 'failed') {
        settled = true;
        clearTimeout(timer);
        unsub();
        reject(providerError(evt.message));
      } else if (evt.kind === 'waiting_user') {
        settled = true;
        clearTimeout(timer);
        unsub();
        reject(providerError('辅助模型请求了用户交互，此任务不支持交互'));
      }
      // status:忽略
    });
  });
};

let sugSeq = 0;
const newSuggestionRunId = (): string => {
  sugSeq += 1;
  return `sug_${Date.now().toString(36)}_${sugSeq}`;
};

// 分析失败抛出明确错误；正文收录是否继续由调用方决定，不能用空建议掩盖失败。
export const analyzeChapterSuggestions = async (
  modelRunning: NovelModelRunning,
  project: NovelProject,
  chapter: NovelChapter,
  modelTarget: NovelModelTarget,
  now: number,
  timeoutMs: number = SUGGESTION_TIMEOUT_MS,
): Promise<NovelMaterialSuggestion[]> => {
  const request: NovelModelRequest = {
    runId: newSuggestionRunId(),
    projectId: project.id,
    systemPrompt: SUGGESTION_SYSTEM_PROMPT,
    maxOutputTokens: SUGGESTION_MAX_OUTPUT_TOKENS,
    modelTarget,
    toolProfile: 'none',
    history: [],
    operation: { kind: 'turn', userPrompt: buildSuggestionUserPrompt(project, chapter) },
    checkpoint: async (): Promise<void> => {},
  };
  try {
    await modelRunning.validate(modelTarget, project.id);
    const deadline: number = Date.now() + timeoutMs;
    const fullText: string = await collectModelText(modelRunning, request, timeoutMs);
    let suggestions: NovelMaterialSuggestion[];
    try {
      suggestions = parseSuggestions(fullText, chapter.id, now);
    } catch (error) {
      // Only a completed but invalid result gets one correction; transport failures never retry.
      if (!isNovelError(error) || error.code !== 'invalid_output') throw error;
      const remaining: number = deadline - Date.now();
      if (remaining <= 0) throw error;
      const correction: NovelModelRequest = { ...request, runId: newSuggestionRunId(),
        operation: { kind: 'turn', userPrompt: buildSuggestionUserPrompt(project, chapter)
          + `\n\n上次资料建议格式不正确：${error.message}。请根据原章节修正，保持建议内容，不新增事实。`
          + `\n只返回符合上述格式的完整 JSON 对象。上次输出：\n${fullText}` } };
      suggestions = parseSuggestions(await collectModelText(modelRunning, correction, remaining), chapter.id, now);
    }
    const sourceDigest: string = materialSuggestionChapterDigest(chapter);
    return suggestions.map(suggestion => ({ ...suggestion, sourceDigest }));
  } catch (error) {
    if (isNovelError(error)) throw error;
    throw providerError(error instanceof Error ? error.message : String(error));
  }
};
