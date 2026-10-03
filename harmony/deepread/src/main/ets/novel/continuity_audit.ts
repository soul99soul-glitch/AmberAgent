// 全文分块审校：正文连续覆盖；只把可回查原文的证据列为问题。
import type { NovelProject, NovelChapter, NovelModelTarget } from './models.ts';
import { novelChapterOrdinal } from './models.ts';
import type { NovelModelRunning, NovelModelRequest, NovelModelStream } from './model_running.ts';
import { latestAssistantText } from '../agent/message.ts';
import { defaultGhostwriteDigest } from './ghostwrite.ts';
import { novelStructuredStateContext } from './context_builder.ts';
import { emptyNovelStructuredState, pruneNovelStructuredState } from './structured_state.ts';
import { effectiveNovelMaterials } from './material_inheritance.ts';

export const AUDIT_MAX_OUTPUT_TOKENS = 8_192;
export const AUDIT_TIMEOUT_MS = 120_000;
export const AUDIT_MAX_ISSUES = 40;
export type NovelAuditSeverity = 'blocker' | 'major' | 'minor';
export interface NovelAuditParsedReference {
  chapterId: string;
  sourceDigest: string;
  quote: string;
  start: number;
  end: number;
}
export interface NovelAuditReference extends NovelAuditParsedReference {
  chapterOrdinal: number;
  chapterTitle: string;
}
export interface NovelAuditParsedIssue {
  severity: NovelAuditSeverity;
  chapterRef: string;
  summary: string;
  suggestion: string;
  chapterId: string;
  sourceDigest: string;
  quote: string;
  canonicalReferences?: NovelAuditParsedReference[];
}
export interface NovelAuditIssue extends NovelAuditParsedIssue {
  start: number;
  end: number;
  canonicalReferences?: NovelAuditReference[];
}
export interface NovelAuditBlock {
  chapterId: string;
  sourceDigest: string;
  start: number;
  end: number;
  status: 'checked' | 'failed' | 'cancelled';
  error: string | null;
}
export interface NovelAuditProgress {
  completedBlocks: number;
  totalBlocks: number;
  checkedChars: number;
  totalChars: number;
  currentChapterId: string | null;
}
export interface NovelAuditReport {
  ok: boolean;
  issues: NovelAuditIssue[];
  raw: string;
  coverage: { totalChars: number; checkedChars: number; complete: boolean };
  blocks: NovelAuditBlock[];
  cancelled: boolean;
  invalidEvidenceCount: number;
}
// 临时调用控制，不持久化任务；离页立即终止当前模型并停止后续块。
export class NovelAuditController {
  private cancelled: boolean = false;
  private listeners: Array<() => void> = [];
  private readonly progress: (progress: NovelAuditProgress) => void;
  constructor(onProgress: (progress: NovelAuditProgress) => void = (): void => {}) {
    this.progress = onProgress;
  }
  isCancelled(): boolean { return this.cancelled; }
  cancel(): void {
    if (this.cancelled) return;
    this.cancelled = true;
    for (const listener of this.listeners.slice()) listener();
    this.listeners = [];
  }
  onCancel(listener: () => void): () => void {
    if (this.cancelled) { listener(); return (): void => {}; }
    this.listeners.push(listener);
    return (): void => { this.listeners = this.listeners.filter(item => item !== listener); };
  }
  onProgress(progress: NovelAuditProgress): void { this.progress(progress); }
}
export const CONTINUITY_AUDIT_SYSTEM_PROMPT =
  '你是长篇小说连续性审校。核对本块正文、邻接原文与作者事实，只输出严格 JSON：\n'
  + '{"issues":[{"severity":"blocker|major|minor","chapterId":"本块chapterId","sourceDigest":"本块sourceDigest",'
  + '"quote":"本块正文中逐字原文证据","summary":"问题","suggestion":"修复建议",'
  + '"canonicalReferences":[{"chapterId":"较早章ID","sourceDigest":"较早章完整正文摘要",'
  + '"quote":"较早章逐字原文","start":原文UTF16起点,"end":原文UTF16终点}]}]}\n'
  + '跨章矛盾必须同时给出较早章 canonicalReferences 和本块较晚章 quote，保护较早章事实，只修较晚章。'
  + 'canonicalReferences 只使用输入的前文来源，所有定位按其 sourceRange；单章措辞瑕疵可省略此字段。'
  + 'quote 必须逐字出现在本块正文内，不得编造、改写或取自邻接原文。计划只是后续方向，不把尚未发生的计划情节判为历史正文错误。无问题输出 {"issues":[]}。';
interface RawReference {
  chapterId?: unknown; sourceDigest?: unknown; quote?: unknown; start?: unknown; end?: unknown;
}
interface RawIssue {
  severity?: unknown; chapterRef?: unknown; summary?: unknown; suggestion?: unknown;
  chapterId?: unknown; sourceDigest?: unknown; quote?: unknown;
  canonicalReferences?: unknown;
}
interface RawEnvelope { issues?: RawIssue[]; }
const stringField = (value: unknown): string => typeof value === 'string' ? value : '';
const parseCanonicalReferences = (value: unknown): NovelAuditParsedReference[] | undefined => {
  if (value === undefined) return undefined;
  if (!Array.isArray(value) || value.length === 0) throw new Error('连续性检查失败：成对前文证据不能为空');
  return value.map((raw: unknown): NovelAuditParsedReference => {
    if (raw === null || typeof raw !== 'object' || Array.isArray(raw)) throw new Error('连续性检查失败：前文证据结构无效');
    const item = raw as RawReference;
    if (typeof item.chapterId !== 'string' || typeof item.sourceDigest !== 'string' || typeof item.quote !== 'string'
      || typeof item.start !== 'number' || typeof item.end !== 'number' || !Number.isInteger(item.start)
      || !Number.isInteger(item.end)) throw new Error('连续性检查失败：前文证据缺少实际来源或定位');
    return { chapterId: item.chapterId, sourceDigest: item.sourceDigest, quote: item.quote, start: item.start, end: item.end };
  });
};
export const verifyAuditCanonicalReferences = (chapters: NovelChapter[], targetChapterId: string,
  references: NovelAuditParsedReference[]): NovelAuditReference[] => {
  const targetIndex: number = chapters.findIndex(chapter => chapter.id === targetChapterId && !chapter.discarded);
  if (targetIndex < 0 || references.length === 0) throw new Error('连续性修复失败：缺少成对前文证据');
  const seen: Set<string> = new Set();
  return references.map((reference): NovelAuditReference => {
    const index: number = chapters.findIndex(chapter => chapter.id === reference.chapterId && !chapter.discarded);
    if (index < 0 || index >= targetIndex) throw new Error('连续性修复失败：必须保护较早章事实并只修复较晚章');
    const chapter: NovelChapter = chapters[index];
    if (reference.sourceDigest !== defaultGhostwriteDigest(chapter.content)
      || !Number.isInteger(reference.start) || !Number.isInteger(reference.end) || reference.start < 0
      || reference.end <= reference.start || reference.end > chapter.content.length || reference.quote.trim().length === 0
      || chapter.content.slice(reference.start, reference.end) !== reference.quote) {
      throw new Error('连续性修复失败：前文证据或来源正文已变化');
    }
    const key: string = `${chapter.id}:${reference.start}:${reference.end}`;
    if (seen.has(key)) throw new Error('连续性修复失败：前文成对证据重复');
    seen.add(key);
    return { chapterId: chapter.id, sourceDigest: reference.sourceDigest, quote: reference.quote,
      start: reference.start, end: reference.end, chapterOrdinal: novelChapterOrdinal(chapter, index + 1), chapterTitle: chapter.title };
  });
};
export const parseAuditReport = (text: string): NovelAuditParsedIssue[] => {
  const start: number = text.indexOf('{');
  const end: number = text.lastIndexOf('}');
  if (start < 0 || end <= start) throw new Error('连续性检查失败：模型未返回 JSON');
  let parsed: RawEnvelope | null;
  try { parsed = JSON.parse(text.slice(start, end + 1)) as RawEnvelope; }
  catch { throw new Error('连续性检查失败：JSON 格式无效'); }
  if (parsed === null || !Array.isArray(parsed.issues)) throw new Error('连续性检查失败：缺少 issues 数组');
  if (parsed.issues.length > AUDIT_MAX_ISSUES) throw new Error('连续性检查失败：本块问题数量超过输出限制，报告未完成');
  const out: NovelAuditParsedIssue[] = [];
  for (const item of parsed.issues.slice(0, AUDIT_MAX_ISSUES)) {
    if (item === null || typeof item !== 'object' || stringField(item.summary).trim().length === 0) {
      throw new Error('连续性检查失败：问题条目缺少 summary');
    }
    const severity: string = stringField(item.severity).toLowerCase().trim();
    out.push({
      severity: severity === 'blocker' || severity === 'critical' || severity === '高' ? 'blocker'
        : severity === 'major' || severity === '中' ? 'major' : 'minor',
      chapterRef: stringField(item.chapterRef).trim() || '?',
      summary: stringField(item.summary).trim().slice(0, 500),
      suggestion: stringField(item.suggestion).trim().slice(0, 500),
      chapterId: stringField(item.chapterId), sourceDigest: stringField(item.sourceDigest), quote: stringField(item.quote),
      canonicalReferences: parseCanonicalReferences(item.canonicalReferences),
    });
  }
  return out;
};
const authorFacts = (project: NovelProject): string => [
  `项目：${project.name}`,
  `作者剧情：\n${project.authorPlot ?? ''}`,
  `已取证事件与身份确认：\n${novelStructuredStateContext(project)}`,
  `本章计划：\n${project.branchSettings.thisChapterPlan}`,
  `后续计划：\n${project.branchSettings.futurePlan}`,
  `写作偏好与约束：\n${project.branchSettings.preferences}`,
  `确认决策：\n${project.branchSettings.confirmedDecisions.map(item => `${item.title}\n${item.content}`).join('\n\n')}`,
  `未回收伏笔：\n${project.branchSettings.foreshadows.filter(item => item.status === 'open')
    .map(item => `${item.title}\n${item.content}`).join('\n\n')}`,
  `启用资料与事实：\n${project.materials.filter(item => item.enabled)
    .map(item => `[${item.kind}] ${item.title}\n${item.content}`).join('\n\n')}`,
].join('\n\n');
// 用于预览/旧调用的全文，不再采用章尾或总量截断。实际调用经过分块规划。
export const buildAuditUserPrompt = (project: NovelProject): string => authorFacts(project) + '\n\n正文：\n'
  + project.chapters.filter(item => !item.discarded)
    .map(chapter => `## 第${novelChapterOrdinal(chapter, project.chapters.indexOf(chapter) + 1)}章 ${chapter.title}\n${chapter.content}`).join('\n\n');
interface AuditInputBlock { chapter: NovelChapter; ordinal: number; start: number; end: number; sourceDigest: string; userPrompt: string; }
const blockPrompt = (facts: string, chapter: NovelChapter, ordinal: number, start: number, end: number,
  sourceDigest: string, preceding: string, canonicalSource: string): string => facts
  + `\n\n前文事实来源（跨章矛盾需逐字引用并保留这些较早章事实）：\n${canonicalSource}`
  + `\n\n邻接原文（只供理解衔接，不作为问题定位证据）：\n${preceding}`
  + `\n\n本块：第${ordinal}章 ${chapter.title}\nchapterId=${chapter.id}\nsourceDigest=${sourceDigest}`
  + `\nUTF16范围=[${start},${end})\n本块正文：\n${chapter.content.slice(start, end)}`;
const planAuditBlocks = (project: NovelProject, budget: number,
  estimate: (system: string, user: string) => number): AuditInputBlock[] => {
  const chapters: NovelChapter[] = project.chapters.filter(item => !item.discarded);
  const facts: string = authorFacts(project);
  const verifiedState = pruneNovelStructuredState(project.structuredState ?? emptyNovelStructuredState(),
    project.chapters, effectiveNovelMaterials(project));
  const blocks: AuditInputBlock[] = [];
  for (let ordinal = 0; ordinal < chapters.length; ordinal += 1) {
    const chapter: NovelChapter = chapters[ordinal];
    const chapterOrdinal: number = novelChapterOrdinal(chapter, project.chapters.indexOf(chapter) + 1);
    const sourceDigest: string = defaultGhostwriteDigest(chapter.content);
    let start: number = 0;
    // 空章仍检查标题与作者约束，但不增加字符覆盖数量。
    do {
      const preceding: string = start > 0 ? chapter.content.slice(Math.max(0, start - 1000), start)
        : ordinal > 0 ? chapters[ordinal - 1].content.slice(-1000) : '';
      const earlier: NovelChapter | undefined = ordinal > 0 ? chapters[ordinal - 1] : undefined;
      const adjacentSource: string = earlier === undefined ? '无较早章来源' : JSON.stringify({
        chapterId: earlier.id, sourceDigest: defaultGhostwriteDigest(earlier.content),
        chapterOrdinal: novelChapterOrdinal(earlier, project.chapters.indexOf(earlier) + 1), chapterTitle: earlier.title,
        sourceRange: { start: Math.max(0, earlier.content.length - 1000), end: earlier.content.length }, content: earlier.content.slice(-1000),
      });
      const eventSources = verifiedState.events.filter(event => chapters.findIndex(item => item.id === event.chapterId) < ordinal)
        .map(event => {
          const sourceChapter = chapters.find(item => item.id === event.chapterId)!;
          const sourceStart: number = sourceChapter.content.indexOf(event.quote);
          return { chapterId: sourceChapter.id, sourceDigest: event.sourceDigest, quote: event.quote,
            chapterOrdinal: novelChapterOrdinal(sourceChapter, project.chapters.indexOf(sourceChapter) + 1), chapterTitle: sourceChapter.title,
            start: sourceStart, end: sourceStart + event.quote.length,
            sourceRange: { start: sourceStart, end: sourceStart + event.quote.length } };
        });
      const canonicalSource: string = adjacentSource + '\n已取证早章事件来源：\n' + JSON.stringify(eventSources);
      const prompt = (end: number): string => blockPrompt(facts, chapter, chapterOrdinal, start, end, sourceDigest, preceding, canonicalSource);
      if (estimate(CONTINUITY_AUDIT_SYSTEM_PROMPT, prompt(start)) >= budget) {
        throw new Error('作者必要事实已超出当前审校模型输入窗口，请调整启用资料或选择更大窗口模型；正文未被截断。');
      }
      let low: number = start;
      let high: number = chapter.content.length;
      while (low < high) {
        const mid: number = Math.ceil((low + high) / 2);
        if (estimate(CONTINUITY_AUDIT_SYSTEM_PROMPT, prompt(mid)) <= budget) low = mid;
        else high = mid - 1;
      }
      let end: number = low;
      // 不在 UTF16 代理对中间分块。
      if (end < chapter.content.length && end > start && chapter.content.charCodeAt(end - 1) >= 0xD800
        && chapter.content.charCodeAt(end - 1) <= 0xDBFF) end -= 1;
      if (end === start && chapter.content.length > start) throw new Error('模型输入窗口不足以容纳正文分块');
      blocks.push({ chapter, ordinal: chapterOrdinal, start, end, sourceDigest, userPrompt: prompt(end) });
      start = end;
    } while (start < chapter.content.length);
  }
  return blocks;
};
let auditSeq: number = 0;
const newAuditRunId = (): string => { auditSeq += 1; return `audit_${Date.now().toString(36)}_${auditSeq}`; };
const collectAuditText = (model: NovelModelRunning, request: NovelModelRequest,
  timeoutMs: number, control: NovelAuditController): Promise<string> => new Promise((resolve, reject) => {
  let done: boolean = false;
  let text: string = '';
  let unsubscribe: () => void = (): void => {};
  let removeCancel: () => void = (): void => {};
  const finish = (error: string | null): void => {
    if (done) return;
    done = true;
    clearTimeout(timer);
    unsubscribe();
    removeCancel();
    if (error === null) resolve(text); else reject(new Error(error));
  };
  const timer: ReturnType<typeof setTimeout> = setTimeout(() => {
    finish('审校分块超时'); model.cancel(request.runId);
  }, timeoutMs);
  removeCancel = control.onCancel((): void => { finish('审校已取消'); model.cancel(request.runId); });
  if (done) return;
  try {
    const stream: NovelModelStream = model.start(request);
    unsubscribe = stream.subscribe(event => {
      if (done) return;
      if (event.kind === 'snapshot') text = latestAssistantText(event.messages);
      else if (event.kind === 'completed') finish(null);
      else if (event.kind === 'failed') finish(event.message);
      else if (event.kind === 'waiting_user') { finish('审校任务不支持工具交互'); model.cancel(request.runId); }
    });
    if (done) unsubscribe();
  } catch (error) { finish(error instanceof Error ? error.message : String(error)); }
});
export const runContinuityAudit = async (model: NovelModelRunning, project: NovelProject,
  target: NovelModelTarget, timeoutMs: number = AUDIT_TIMEOUT_MS,
  control: NovelAuditController = new NovelAuditController()): Promise<NovelAuditReport> => {
  await model.validate(target, project.id);
  const budget: number = model.inputBudgetTokens !== undefined
    ? await model.inputBudgetTokens(target, project.id, AUDIT_MAX_OUTPUT_TOKENS) : 16_000;
  if (!Number.isFinite(budget) || budget <= 0) throw new Error('审校模型没有可用输入窗口');
  const estimate: (system: string, user: string) => number = model.estimateInputTokens !== undefined
    ? (system, user): number => model.estimateInputTokens!(system, user)
    : (system, user): number => system.length + user.length + 256;
  const inputs: AuditInputBlock[] = planAuditBlocks(project, budget, estimate);
  const totalChars: number = inputs.reduce((sum, item) => sum + item.end - item.start, 0);
  let checkedChars: number = 0;
  let invalidEvidenceCount: number = 0;
  const issues: NovelAuditIssue[] = [];
  const blocks: NovelAuditBlock[] = [];
  const raw: string[] = [];
  const progress = (chapterId: string | null): void => control.onProgress({
    completedBlocks: blocks.length, totalBlocks: inputs.length, checkedChars, totalChars, currentChapterId: chapterId,
  });
  progress(null);
  for (const input of inputs) {
    if (control.isCancelled()) break;
    progress(input.chapter.id);
    const block: NovelAuditBlock = { chapterId: input.chapter.id, sourceDigest: input.sourceDigest,
      start: input.start, end: input.end, status: 'failed', error: null };
    try {
      const text: string = await collectAuditText(model, {
        runId: newAuditRunId(), projectId: project.id, systemPrompt: CONTINUITY_AUDIT_SYSTEM_PROMPT,
        maxOutputTokens: AUDIT_MAX_OUTPUT_TOKENS, modelTarget: target, toolProfile: 'none', history: [],
        operation: { kind: 'turn', userPrompt: input.userPrompt }, checkpoint: async (): Promise<void> => {},
      }, timeoutMs, control);
      raw.push(text);
      const parsed: NovelAuditParsedIssue[] = parseAuditReport(text);
      for (const issue of parsed) {
        const quote: string = issue.quote ?? '';
        const offset: number = quote.length === 0 ? -1 : input.chapter.content.slice(input.start, input.end).indexOf(quote);
        if (issue.chapterId !== input.chapter.id || issue.sourceDigest !== input.sourceDigest || offset < 0) {
          invalidEvidenceCount += 1; continue;
        }
        let canonicalReferences: NovelAuditReference[] | undefined;
        if (issue.canonicalReferences !== undefined) {
          try { canonicalReferences = verifyAuditCanonicalReferences(project.chapters, input.chapter.id, issue.canonicalReferences); }
          catch { invalidEvidenceCount += 1; continue; }
        }
        issues.push({ ...issue, canonicalReferences, chapterRef: `第${input.ordinal}章 ${input.chapter.title}`,
          start: input.start + offset, end: input.start + offset + quote.length });
      }
      block.status = 'checked';
      checkedChars += input.end - input.start;
    } catch (error) {
      block.status = control.isCancelled() ? 'cancelled' : 'failed';
      block.error = error instanceof Error ? error.message : String(error);
    }
    blocks.push(block);
    progress(null);
  }
  if (control.isCancelled()) {
    for (const input of inputs.slice(blocks.length)) blocks.push({
      chapterId: input.chapter.id, sourceDigest: input.sourceDigest, start: input.start, end: input.end,
      status: 'cancelled', error: '审校已取消，该范围尚未检查',
    });
  }
  const complete: boolean = blocks.length === inputs.length && blocks.every(item => item.status === 'checked');
  return { ok: complete && issues.length === 0 && invalidEvidenceCount === 0 && !control.isCancelled(), issues,
    raw: raw.join('\n\n'), coverage: { totalChars, checkedChars, complete }, blocks,
    cancelled: control.isCancelled(), invalidEvidenceCount };
};
