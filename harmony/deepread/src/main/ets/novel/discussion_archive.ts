// 归档草稿不改变事实；作者确认摘要和选定决定后，才推进持久范围游标。
import { novelId } from './models.ts';
import type { NovelProject, NovelMessage, NovelConfirmedDecision, NovelModelTarget } from './models.ts';
import type { NovelModelRunning, NovelModelRequest } from './model_running.ts';
import { collectModelText } from './suggestion_engine.ts';
import { defaultGhostwriteDigest } from './ghostwrite.ts';
import { invalidInput } from './error.ts';

export const MAX_ARCHIVE_SUMMARY_CHARS = 2_000;
const MAX_ARCHIVE_DECISIONS = 12;

export interface NovelDiscussionArchive {
  id: string;
  sourceMessageIds: string[];
  throughMessageId: string;
  summary: string;
  decisions: NovelConfirmedDecision[];
  createdAt: number;
}

export interface NovelDiscussionArchiveDraft {
  branchId: string;
  sourceMessageIds: string[];
  sourceDigest: string;
  previousArchiveId: string | null;
  summary: string;
  decisions: string[];
}

export interface NovelDiscussionArchiveConfirmation {
  project: NovelProject;
  archive: NovelDiscussionArchive;
}

interface RawArchiveDraft {
  summary?: unknown;
  decisions?: unknown;
}

const latestArchive = (project: NovelProject): NovelDiscussionArchive | null => {
  const archives = project.discussionArchives ?? [];
  return archives.length > 0 ? archives[archives.length - 1] : null;
};

const hasPendingTool = (project: NovelProject): boolean => project.messages.some(message =>
  message.uiMessage.parts.some(part => part.type === 'tool' && part.approvalState.type === 'pending'));

export const eligibleNovelDiscussion = (project: NovelProject): NovelMessage[] => {
  if (hasPendingTool(project)) return [];
  const covered = new Set<string>();
  (project.discussionArchives ?? []).forEach(archive => archive.sourceMessageIds.forEach(id => covered.add(id)));
  return project.messages.filter(message => message.mode === 'discuss' &&
    message.content.trim().length > 0 && !covered.has(message.id));
};

const selectedDiscussion = (project: NovelProject, selectedIds: string[]): NovelMessage[] => {
  if (hasPendingTool(project)) throw invalidInput('请先处理待确认的工具，再归档讨论');
  const ids = new Set(selectedIds);
  if (ids.size === 0 || ids.size !== selectedIds.length) throw invalidInput('归档范围为空或重复');
  const selected = eligibleNovelDiscussion(project).filter(message => ids.has(message.id));
  if (selected.length !== ids.size) throw invalidInput('归档范围已变化，部分消息不存在或已经归档');
  return selected;
};

const sourceDigest = (project: NovelProject, messages: NovelMessage[]): string => defaultGhostwriteDigest(JSON.stringify({
  previousArchive: latestArchive(project),
  sources: messages.map(message => ({ id: message.id, role: message.role, content: message.content })),
}));

const parseArchiveDraft = (text: string): { summary: string; decisions: string[] } => {
  let raw: RawArchiveDraft;
  try {
    // Accept a complete Markdown JSON wrapper, while keeping the payload validation strict.
    const fenced: RegExpMatchArray | null = text.trim().match(/^```(?:json)?\s*\n([\s\S]*?)\n```$/i);
    raw = JSON.parse(fenced === null ? text.trim() : fenced[1]) as RawArchiveDraft;
  } catch {
    throw invalidInput('模型返回的归档摘要格式不完整，请重新整理讨论');
  }
  if (raw === null || typeof raw.summary !== 'string' || raw.summary.trim().length === 0) {
    throw invalidInput('归档摘要为空或格式不完整');
  }
  if (raw.summary.trim().length > MAX_ARCHIVE_SUMMARY_CHARS) throw invalidInput('归档摘要过长，请重新生成');
  if (!Array.isArray(raw.decisions) || raw.decisions.length > MAX_ARCHIVE_DECISIONS) {
    throw invalidInput('归档决定列表格式不完整');
  }
  const decisions: string[] = [];
  raw.decisions.forEach((item: unknown): void => {
    if (typeof item !== 'string' || item.trim().length === 0 || item.length > 500) {
      throw invalidInput('归档决定必须是非空短句');
    }
    if (!decisions.includes(item.trim())) decisions.push(item.trim());
  });
  return { summary: raw.summary.trim(), decisions };
};

export const prepareNovelDiscussionArchive = async (
  model: NovelModelRunning, target: NovelModelTarget, project: NovelProject,
  selectedIds: string[], branchId: string, timeoutMs: number,
): Promise<NovelDiscussionArchiveDraft> => {
  let messages = selectedDiscussion(project, selectedIds);
  const previous = latestArchive(project);
  await model.validate(target, project.id);
  const systemPrompt = '整理小说讨论，输出严格 JSON：{"summary":"累计讨论摘要","decisions":["可供作者逐项确认的决定"]}。'
    + '累计摘要必须保留已有归档背景，区分作者已经确认的内容、建议和未决问题，不能把助手建议冒充作者决定。'
    + `摘要最多 ${MAX_ARCHIVE_SUMMARY_CHARS} 字，决定最多 ${MAX_ARCHIVE_DECISIONS} 条。`;
  const maxOutputTokens = 3_072;
  const budget = model.inputBudgetTokens !== undefined && model.estimateInputTokens !== undefined
    ? await model.inputBudgetTokens(target, project.id, maxOutputTokens) : null;
  let userPrompt = `已有归档摘要：\n${previous?.summary ?? '无'}\n\n本次选择的讨论：\n`;
  let sourceCount = 0;
  for (const message of messages) {
    const next = userPrompt + (sourceCount > 0 ? '\n\n' : '')
      + `${message.role === 'user' ? '作者' : '助手'}：${message.content}`;
    if (budget !== null && model.estimateInputTokens !== undefined
      && model.estimateInputTokens(systemPrompt, next) > budget) break;
    userPrompt = next;
    sourceCount += 1;
  }
  if (sourceCount === 0) {
    throw invalidInput('首条讨论的完整内容超出当前模型窗口预算，请选择更大上下文模型后归档');
  }
  // Only confirmed complete sources advance the archive cursor; the rest remain eligible.
  messages = messages.slice(0, sourceCount);
  const request: NovelModelRequest = {
    runId: `archive:${novelId()}`, projectId: project.id,
    systemPrompt, maxOutputTokens, modelTarget: target, toolProfile: 'none', history: [],
    operation: { kind: 'turn', userPrompt },
    checkpoint: async (): Promise<void> => {},
  };
  const parsed = parseArchiveDraft(await collectModelText(model, request, timeoutMs));
  return {
    branchId, sourceMessageIds: messages.map(message => message.id), sourceDigest: sourceDigest(project, messages),
    previousArchiveId: previous?.id ?? null, summary: parsed.summary, decisions: parsed.decisions,
  };
};

export const confirmNovelDiscussionArchive = (
  project: NovelProject, draft: NovelDiscussionArchiveDraft, editedSummary: string,
  selectedDecisionIndexes: number[], branchId: string, now: number,
): NovelDiscussionArchiveConfirmation => {
  if (draft.branchId !== branchId) throw invalidInput('归档草稿来自另一分支，请切回原分支确认');
  if ((latestArchive(project)?.id ?? null) !== draft.previousArchiveId) {
    throw invalidInput('讨论归档范围已经推进，请重新整理新增讨论');
  }
  const messages = selectedDiscussion(project, draft.sourceMessageIds);
  if (sourceDigest(project, messages) !== draft.sourceDigest) throw invalidInput('归档来源讨论已变更，请重新整理');
  const summary = editedSummary.trim();
  if (summary.length === 0 || summary.length > MAX_ARCHIVE_SUMMARY_CHARS) {
    throw invalidInput(`归档摘要必须为 1–${MAX_ARCHIVE_SUMMARY_CHARS} 字`);
  }
  const indexes = new Set<number>();
  const decisions: NovelConfirmedDecision[] = [];
  selectedDecisionIndexes.forEach(index => {
    if (!Number.isInteger(index) || index < 0 || index >= draft.decisions.length || indexes.has(index)) {
      throw invalidInput('请确认要采用的决定，不要重复选择');
    }
    indexes.add(index);
    const content = draft.decisions[index];
    if (project.branchSettings.confirmedDecisions.some(decision => decision.content === content)) return;
    decisions.push({ id: novelId(), title: '讨论确认决定', content, confirmedAt: now });
  });
  const archive: NovelDiscussionArchive = {
    id: novelId(), sourceMessageIds: messages.map(message => message.id),
    throughMessageId: messages[messages.length - 1].id, summary, decisions, createdAt: now,
  };
  return {
    archive,
    project: {
      ...project, discussionArchives: [...(project.discussionArchives ?? []), archive], updatedAt: now,
      branchSettings: { ...project.branchSettings,
        confirmedDecisions: [...project.branchSettings.confirmedDecisions, ...decisions] },
    },
  };
};
