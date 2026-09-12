// novel/continuity_audit — 全文一致性审计(LLM;iOS NovelContinuityAudit 对齐)
//
// 结构检查仍在 NovelCreation.checkConsistency;本模块跑一次 stateSync 模型,
// 产出章节级 issue 列表。解析失败显式抛错,不静默空报告。

import type { NovelProject, NovelChapter, NovelModelTarget } from './models.ts';
import type { NovelModelRunning, NovelModelRequest } from './model_running.ts';
import { collectModelText } from './suggestion_engine.ts';

export const AUDIT_MAX_OUTPUT_TOKENS = 8_192;
export const AUDIT_TIMEOUT_MS = 120_000;
export const AUDIT_MAX_ISSUES = 40;
const CHAPTER_TAIL_CHARS = 18_000;
const MATERIALS_CHARS = 8_000;

export type NovelAuditSeverity = 'blocker' | 'major' | 'minor';

export interface NovelAuditIssue {
  severity: NovelAuditSeverity;
  chapterRef: string;
  summary: string;
  suggestion: string;
}

export interface NovelAuditReport {
  ok: boolean;
  issues: NovelAuditIssue[];
  raw: string;
}

export const CONTINUITY_AUDIT_SYSTEM_PROMPT =
  '你是长篇小说连续性审校。只输出严格 JSON，不要任何额外文字。格式：\n'
  + '{"issues":[{"severity":"blocker|major|minor","chapterRef":"章号或标题","summary":"问题","suggestion":"修复建议"}]}\n'
  + '若无问题，输出 {"issues":[]}。不要复述正文。';

const mapSeverity = (raw: unknown): NovelAuditSeverity => {
  const s = String(raw ?? '').toLowerCase().trim();
  if (s === 'blocker' || s === '高' || s === 'critical') return 'blocker';
  if (s === 'major' || s === '中') return 'major';
  return 'minor';
};

interface RawIssue {
  severity?: unknown;
  chapterRef?: unknown;
  summary?: unknown;
  suggestion?: unknown;
}

interface RawEnvelope {
  issues?: RawIssue[];
}

export const parseAuditReport = (text: string): NovelAuditIssue[] => {
  const start: number = text.indexOf('{');
  const end: number = text.lastIndexOf('}');
  if (start < 0 || end <= start) {
    throw new Error('continuity audit: model did not return JSON');
  }
  let parsed: RawEnvelope;
  try {
    parsed = JSON.parse(text.slice(start, end + 1)) as RawEnvelope;
  } catch {
    throw new Error('continuity audit: invalid JSON');
  }
  const items: RawIssue[] | undefined = parsed.issues;
  if (items === undefined || !Array.isArray(items)) {
    throw new Error('continuity audit: missing issues array');
  }
  const out: NovelAuditIssue[] = [];
  for (let i = 0; i < items.length && out.length < AUDIT_MAX_ISSUES; i++) {
    const it: RawIssue = items[i];
    const summary: string = String(it.summary ?? '').trim();
    const chapterRef: string = String(it.chapterRef ?? '').trim();
    if (summary.length === 0) continue;
    out.push({
      severity: mapSeverity(it.severity),
      chapterRef: chapterRef.length > 0 ? chapterRef : '?',
      summary: summary.slice(0, 500),
      suggestion: String(it.suggestion ?? '').trim().slice(0, 500),
    });
  }
  return out;
};

export const buildAuditUserPrompt = (project: NovelProject): string => {
  const materials: string = project.materials
    .filter((m): boolean => m.enabled)
    .map((m): string => `[${m.kind}] ${m.title}`)
    .join('\n')
    .slice(0, MATERIALS_CHARS);
  const chapters: string = project.chapters
    .filter((c): boolean => !c.discarded)
    .map((c: NovelChapter, idx: number): string => {
      const body: string = c.content.length > CHAPTER_TAIL_CHARS
        ? c.content.slice(c.content.length - CHAPTER_TAIL_CHARS)
        : c.content;
      return `## 第${idx + 1}章 ${c.title}\n${body}`;
    })
    .join('\n\n')
    .slice(0, 120_000);
  return `项目：${project.name}\n资料目录：\n${materials || '（无）'}\n\n正文：\n${chapters || '（无章节）'}`;
};

let auditSeq = 0;
const newAuditRunId = (): string => {
  auditSeq += 1;
  return `audit_${Date.now().toString(36)}_${auditSeq}`;
};

export const runContinuityAudit = async (
  modelRunning: NovelModelRunning,
  project: NovelProject,
  modelTarget: NovelModelTarget,
  timeoutMs: number = AUDIT_TIMEOUT_MS,
): Promise<NovelAuditReport> => {
  const request: NovelModelRequest = {
    runId: newAuditRunId(),
    projectId: project.id,
    systemPrompt: CONTINUITY_AUDIT_SYSTEM_PROMPT,
    maxOutputTokens: AUDIT_MAX_OUTPUT_TOKENS,
    modelTarget,
    toolProfile: 'none',
    history: [],
    operation: { kind: 'turn', userPrompt: buildAuditUserPrompt(project) },
    checkpoint: async (): Promise<void> => {},
  };
  const fullText: string = await collectModelText(modelRunning, request, timeoutMs);
  const issues: NovelAuditIssue[] = parseAuditReport(fullText);
  return { ok: issues.length === 0, issues, raw: fullText };
};
