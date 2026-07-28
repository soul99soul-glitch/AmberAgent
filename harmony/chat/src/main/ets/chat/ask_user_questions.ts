// ask_user_questions — ask_user 问题解析/应答载荷(D-105)
//
// Android 基准: feature/ui/components/message/ChatMessageAskUserStep.kt
//   :87-99   questions 解析(runCatching 全损 → [];缺省 id/question 空串、
//            options []、selection_type 'text';元素/options 类型错 → 全损)
//   :331-345 提交载荷 {"answers":{id: string|[...]}},键序 = questions 序;
//            multi → 选中数组(缺省 []),其余 → 文本(缺省 "")
//   :74-84   answeredAnswers = answer JSON 的 'answers' 对象;失败/非对象 → null
//   :246-253 已应答展示:数组 → 原始项 join ' · ';原始 → content;
//            非原始/缺失 → 整段 answer
//   :348-353 提交使能:multi → 该题选中集非空;其余 → 文本非空白
//   :295-296 anyAnswered:任一文本非空白 或 任一多选集非空

export interface AskUserQuestion {
  id: string;
  question: string;
  options: string[];
  selectionType: string; // 'text' | 'single' | 'multi'(缺省 'text')
}

// :87-99 — runCatching 语义:任何结构错误 → 整体 [](不做部分恢复)
export const parseAskUserQuestions = (input: string): AskUserQuestion[] => {
  try {
    const root: unknown = JSON.parse(input);
    if (typeof root !== 'object' || root === null || Array.isArray(root)) return [];
    const rawQs: unknown = (root as Record<string, unknown>)['questions'];
    if (!Array.isArray(rawQs)) return [];
    const out: AskUserQuestion[] = [];
    for (const item of rawQs) {
      if (typeof item !== 'object' || item === null || Array.isArray(item)) {
        return []; // q.jsonObject 抛 → getOrElse 全损
      }
      const obj: Record<string, unknown> = item as Record<string, unknown>;
      let options: string[] = [];
      const rawOpts: unknown = obj['options'];
      if (rawOpts !== undefined && rawOpts !== null) {
        if (!Array.isArray(rawOpts)) return []; // .jsonArray 抛 → 全损
        const acc: string[] = [];
        for (const o of rawOpts) {
          // mapNotNull { it.jsonPrimitive.contentOrNull }:非原始抛 → 全损
          if (typeof o !== 'string' && typeof o !== 'number' && typeof o !== 'boolean') {
            return [];
          }
          acc.push(String(o));
        }
        options = acc;
      }
      const id: unknown = obj['id'];
      const question: unknown = obj['question'];
      const sel: unknown = obj['selection_type'];
      out.push({
        id: typeof id === 'string' ? id : '',
        question: typeof question === 'string' ? question : '',
        options,
        selectionType: typeof sel === 'string' ? sel : 'text',
      });
    }
    return out;
  } catch (_e) {
    return [];
  }
};

// :331-345 — 提交载荷;键序 = questions 序(JSON.stringify 紧凑无空格,同 kotlinx)
export const buildAskUserAnswerPayload = (
  questions: AskUserQuestion[],
  answers: Record<string, string>,
  multiAnswers: Record<string, string[]>,
): string => {
  const answersObj: Record<string, string | string[]> = {};
  for (const q of questions) {
    if (q.selectionType === 'multi') {
      const sel: string[] | undefined = multiAnswers[q.id];
      answersObj[q.id] = sel !== undefined ? sel.slice() : [];
    } else {
      const a: string | undefined = answers[q.id];
      answersObj[q.id] = a !== undefined ? a : '';
    }
  }
  return JSON.stringify({ answers: answersObj });
};

export type AskedAnswers = Record<string, string | string[]>;

// :74-84 — JsonInstant.parseToJsonElement(state.answer).jsonObject["answers"]?.jsonObject
//   失败/非对象/answers 非对象 → null
export const parseAskedAnswers = (answer: string): AskedAnswers | null => {
  try {
    const root: unknown = JSON.parse(answer);
    if (typeof root !== 'object' || root === null || Array.isArray(root)) return null;
    const raw: unknown = (root as Record<string, unknown>)['answers'];
    if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) return null;
    return raw as AskedAnswers;
  } catch (_e) {
    return null;
  }
};

// :246-253 — 展示文本:数组 → 原始项 join ' · ';原始 → String(content);
//   非原始(对象)/键缺失/answeredAnswers null → 整段 answer
export const askAnswerDisplayText = (
  answeredAnswers: AskedAnswers | null,
  questionId: string,
  fallbackAnswer: string,
): string => {
  if (answeredAnswers === null) return fallbackAnswer;
  const v: string | string[] | undefined = answeredAnswers[questionId];
  if (Array.isArray(v)) {
    // mapNotNull { jsonPrimitiveOrNull?.contentOrNull }:非原始丢弃
    return v.filter((x: string): boolean => typeof x === 'string').join(' · ');
  }
  if (v !== undefined && v !== null) {
    return typeof v === 'string' ? v : fallbackAnswer;
  }
  return fallbackAnswer;
};

// :348-353 — 全部可提交:multi → 非空集;其余 → 非空白(isNullOrBlank)
export const isAskUserSubmittable = (
  questions: AskUserQuestion[],
  answers: Record<string, string>,
  multiAnswers: Record<string, string[]>,
): boolean => {
  for (const q of questions) {
    if (q.selectionType === 'multi') {
      const sel: string[] | undefined = multiAnswers[q.id];
      if (sel === undefined || sel.length === 0) return false;
    } else {
      const a: string | undefined = answers[q.id];
      if (a === undefined || a.trim().length === 0) return false;
    }
  }
  return true;
};

// :295-296 — anyAnswered:任一文本非空白 或 任一多选集非空
export const hasAnyAskAnswer = (
  answers: Record<string, string>,
  multiAnswers: Record<string, string[]>,
): boolean => {
  const keys: string[] = Object.keys(answers);
  for (const k of keys) {
    if (answers[k].trim().length > 0) return true;
  }
  const mKeys: string[] = Object.keys(multiAnswers);
  for (const k of mKeys) {
    if (multiAnswers[k].length > 0) return true;
  }
  return false;
};
