// novel/suggestion_engine — 素材建议引擎(移植自 Android NovelMaterialSuggestionEngine.kt)
// 收录章节后,让模型读章节 + 已有资料,产出严格 JSON 的"活资料"建议(候选,需用户采用)。

import { MATERIAL_KIND_LABELS, makeNovelSuggestion } from './models.ts';
import type {
  NovelProject, NovelChapter, NovelMaterialKind, NovelMaterialSuggestion, NovelModelTarget,
} from './models.ts';
import type { NovelModelRunning, NovelModelRequest, NovelModelEvent } from './model_running.ts';
import { latestAssistantText } from '../agent/message.ts';

export const MAX_SUGGESTIONS = 12;
const SUGGESTION_MAX_OUTPUT_TOKENS = 4_096;
const SUGGESTION_TIMEOUT_MS = 90_000;
const MATERIALS_BLOCK_CHARS = 12_000;
const CHAPTER_TAIL_CHARS = 24_000;
const TITLE_CHARS = 300;
const CONTENT_CHARS = 20_000;

export const SUGGESTION_SYSTEM_PROMPT =
  '你是小说资料整理助手。阅读刚收录的章节，结合已有资料，产出需要新建或更新的"活资料"。\n'
  + '只输出严格 JSON，不要任何额外文字。格式：\n'
  + '{"suggestions":[{"kind":"world|character|outline|requirement|other","title":"资料标题","content":"合并新事实后的完整最新资料"}]}\n'
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
    case 'outline': case '剧情': case '大纲': return 'outline';
    case 'requirement': case '写作要求': case '要求': return 'requirement';
    default: return 'other';
  }
};

interface RawSuggestion {
  kind?: unknown;
  title?: unknown;
  content?: unknown;
}

interface RawEnvelope {
  suggestions?: RawSuggestion[];
}

// 从模型全文里解析建议(取首个 { 到末个 },容错)
export const parseSuggestions = (
  text: string, sourceChapterId: string, now: number,
): NovelMaterialSuggestion[] => {
  const start: number = text.indexOf('{');
  const end: number = text.lastIndexOf('}');
  if (start < 0 || end <= start) return [];
  const json: string = text.slice(start, end + 1);
  let parsed: RawEnvelope;
  try {
    parsed = JSON.parse(json) as RawEnvelope;
  } catch {
    return [];
  }
  const items: RawSuggestion[] | undefined = parsed.suggestions;
  if (items === undefined || !Array.isArray(items)) return [];
  const out: NovelMaterialSuggestion[] = [];
  for (let i = 0; i < items.length && out.length < MAX_SUGGESTIONS; i++) {
    const it: RawSuggestion = items[i];
    const title: string = String(it.title ?? '').trim().slice(0, TITLE_CHARS);
    const content: string = String(it.content ?? '').slice(0, CONTENT_CHARS);
    if (title.length === 0 || content.length === 0) continue;
    out.push(makeNovelSuggestion({
      sourceChapterId: sourceChapterId,
      kind: mapSuggestionKind(it.kind),
      title: title,
      content: content,
      now: now,
    }));
  }
  return out;
};

// 跑一次模型调用,累积 delta/replace 成全文;completed 解决,failed/超时 拒绝。
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
      reject(new Error('timeout'));
    }, timeoutMs);
    const stream = modelRunning.start(request);
    unsub = stream.subscribe((evt: NovelModelEvent): void => {
      if (settled) return;
      if (evt.kind === 'delta') {
        buffer += evt.text;
      } else if (evt.kind === 'replace') {
        buffer = evt.text;
      } else if (evt.kind === 'snapshot') {
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
        reject(new Error(evt.message));
      } else if (evt.kind === 'waiting_user') {
        settled = true;
        clearTimeout(timer);
        unsub();
        reject(new Error('state-sync 模型请求了用户交互'));
      }
      // activity / status:忽略
    });
  });
};

let sugSeq = 0;
const newSuggestionRunId = (): string => {
  sugSeq += 1;
  return `sug_${Date.now().toString(36)}_${sugSeq}`;
};

// 分析某章 → 建议列表;模型失败/超时返回空数组(由调用方附 warning,不阻断收录)
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
    history: [],
    operation: { kind: 'turn', userPrompt: buildSuggestionUserPrompt(project, chapter) },
    checkpoint: async (): Promise<void> => {},
  };
  try {
    const fullText: string = await collectModelText(modelRunning, request, timeoutMs);
    return parseSuggestions(fullText, chapter.id, now);
  } catch {
    return [];
  }
};
