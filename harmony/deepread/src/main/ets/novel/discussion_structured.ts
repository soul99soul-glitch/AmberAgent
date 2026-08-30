// novel/discussion_structured — 讨论回复里的结构化 fenced JSON。
// ask_user 语义对齐 legacy Android NovelDiscussionAskParser；setting_proposals
// 是鸿蒙域的轻量提案协议，解析失败时不影响普通讨论正文落库。

import { MAX_MATERIAL_CHARS, MAX_TITLE_CHARS } from './models.ts';
import type { NovelMaterialKind } from './models.ts';

export type NovelAskSelectionType = 'single' | 'multi' | 'text';

export interface NovelDiscussionQuestion {
  id: string;
  question: string;
  options: string[];
  selectionType: NovelAskSelectionType;
}

export type NovelDiscussionAsk = NovelDiscussionQuestion;

export interface NovelDiscussionAskParsed {
  displayContent: string;
  questions: NovelDiscussionQuestion[];
  rawFence: string;
  toolInput: string;
}

export interface NovelSettingProposalDraft {
  kind: NovelMaterialKind;
  title: string;
  content: string;
}

export interface NovelSettingProposalsParsed {
  displayContent: string;
  proposals: NovelSettingProposalDraft[];
  rawFence: string;
}

export const MAX_SETTING_PROPOSALS = 20;
const MAX_PROPOSAL_CONTENT_CHARS: number = Math.min(MAX_MATERIAL_CHARS, 20_000);
const ASK_COMPLETE_FENCE = /```ask_user\s*([\s\S]*?)```/i;
const ASK_OPEN_FENCE = /```ask_user\b/i;
const PROPOSALS_COMPLETE_FENCE = /```setting_proposals\s*([\s\S]*?)```/i;
const PROPOSALS_OPEN_FENCE = /```setting_proposals\b/i;

interface JsonObject {
  [key: string]: unknown;
}

const isJsonObject = (value: unknown): value is JsonObject =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

const stringValue = (value: unknown): string =>
  typeof value === 'string' ? value : '';

const normalizeSelectionType = (value: unknown): NovelAskSelectionType => {
  const raw: string = stringValue(value).trim().toLowerCase();
  if (raw === 'multi' || raw === 'multiple') return 'multi';
  if (raw === 'text' || raw === 'free' || raw === 'freetext') return 'text';
  return 'single';
};

const parseJsonObject = (body: string): JsonObject | null => {
  try {
    const parsed: unknown = JSON.parse(body);
    return isJsonObject(parsed) ? parsed : null;
  } catch {
    return null;
  }
};

const stripFence = (content: string, complete: RegExp, open: RegExp): string => {
  const match: RegExpMatchArray | null = content.match(complete);
  if (match !== null && match.index !== undefined) {
    const display: string = (content.slice(0, match.index) + content.slice(match.index + match[0].length)).trim();
    return display.length > 0 ? display : content;
  }
  const openMatch: RegExpMatchArray | null = content.match(open);
  if (openMatch === null || openMatch.index === undefined) return content;
  return content.slice(0, openMatch.index).trim();
};

export const encodeDiscussionToolInput = (questions: NovelDiscussionQuestion[]): string => {
  const encodedQuestions: JsonObject[] = questions.map((q: NovelDiscussionQuestion): JsonObject => ({
    id: q.id,
    question: q.question,
    options: q.options,
    selection_type: q.selectionType,
  }));
  return JSON.stringify({ questions: encodedQuestions });
};

export const parseDiscussionAsk = (content: string): NovelDiscussionAskParsed | null => {
  const match: RegExpMatchArray | null = content.match(ASK_COMPLETE_FENCE);
  if (match === null || match.index === undefined) return null;
  const body: string = (match[1] ?? '').trim();
  if (body.length === 0) return null;
  const envelope: JsonObject | null = parseJsonObject(body);
  if (envelope === null || !Array.isArray(envelope.questions)) return null;

  const questions: NovelDiscussionQuestion[] = [];
  for (let i = 0; i < envelope.questions.length; i++) {
    const item: unknown = envelope.questions[i];
    if (!isJsonObject(item)) continue;
    const question: string = stringValue(item.question).trim();
    if (question.length === 0) continue;
    const options: string[] = Array.isArray(item.options)
      ? item.options
        .map((option: unknown): string => stringValue(option).trim())
        .filter((option: string): boolean => option.length > 0)
      : [];
    const rawId: string = stringValue(item.id).trim();
    questions.push({
      id: rawId.length > 0 ? rawId : `q${i + 1}`,
      question: question,
      options: options,
      selectionType: normalizeSelectionType(item.selection_type ?? item.selectionType),
    });
  }
  if (questions.length === 0) return null;

  const display: string = (content.slice(0, match.index) + content.slice(match.index + match[0].length)).trim();
  return {
    displayContent: display.length > 0 ? display : content.trim(),
    questions: questions,
    rawFence: match[0],
    toolInput: encodeDiscussionToolInput(questions),
  };
};

export const stripDiscussionAskFence = (content: string): string =>
  stripFence(content, ASK_COMPLETE_FENCE, ASK_OPEN_FENCE);

export const formatDiscussionAnswers = (
  questions: NovelDiscussionQuestion[], answers: Readonly<Record<string, string>>,
): string => {
  if (questions.length === 0) return '';
  const lines: string[] = ['【我的选择】'];
  for (const question of questions) {
    const answer: string = (answers[question.id] ?? '').trim();
    if (answer.length > 0) lines.push(`- ${question.question} → ${answer}`);
  }
  return lines.length > 1 ? lines.join('\n') : '';
};

export const formatDiscussionAnswersFromToolPayload = (
  answerJson: string, questions: NovelDiscussionQuestion[],
): string => {
  const fallback: string = answerJson.trim();
  let envelope: JsonObject | null = null;
  try {
    const parsed: unknown = JSON.parse(answerJson);
    envelope = isJsonObject(parsed) ? parsed : null;
  } catch {
    return fallback;
  }
  if (envelope === null || !isJsonObject(envelope.answers)) return fallback;
  const answers: Record<string, string> = {};
  for (const question of questions) {
    const value: unknown = envelope.answers[question.id];
    let text: string = '';
    if (Array.isArray(value)) {
      text = value
        .map((item: unknown): string => stringValue(item).trim())
        .filter((item: string): boolean => item.length > 0)
        .join('、');
    } else if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
      text = String(value).trim();
    } else if (isJsonObject(value)) {
      text = JSON.stringify(value);
    }
    if (text.length > 0) answers[question.id] = text;
  }
  const formatted: string = formatDiscussionAnswers(questions, answers);
  return formatted.length > 0 ? formatted : fallback;
};

const isMaterialKind = (value: string): value is NovelMaterialKind =>
  value === 'world' || value === 'character' || value === 'outline'
  || value === 'requirement' || value === 'other';

export const parseSettingProposals = (content: string): NovelSettingProposalsParsed | null => {
  const match: RegExpMatchArray | null = content.match(PROPOSALS_COMPLETE_FENCE);
  if (match === null || match.index === undefined) return null;
  const envelope: JsonObject | null = parseJsonObject((match[1] ?? '').trim());
  if (envelope === null || !Array.isArray(envelope.proposals)) return null;

  const proposals: NovelSettingProposalDraft[] = [];
  for (let i = 0; i < envelope.proposals.length && proposals.length < MAX_SETTING_PROPOSALS; i++) {
    const item: unknown = envelope.proposals[i];
    if (!isJsonObject(item)) continue;
    const kind: string = stringValue(item.kind).trim().toLowerCase();
    const title: string = stringValue(item.title).trim().slice(0, MAX_TITLE_CHARS);
    const proposalContent: string = stringValue(item.content).trim().slice(0, MAX_PROPOSAL_CONTENT_CHARS);
    if (!isMaterialKind(kind) || title.length === 0 || proposalContent.length === 0) continue;
    proposals.push({ kind: kind, title: title, content: proposalContent });
  }

  const display: string = (content.slice(0, match.index) + content.slice(match.index + match[0].length)).trim();
  return {
    displayContent: display.length > 0 ? display : content.trim(),
    proposals: proposals,
    rawFence: match[0],
  };
};

export const stripSettingProposalsFence = (content: string): string =>
  stripFence(content, PROPOSALS_COMPLETE_FENCE, PROPOSALS_OPEN_FENCE);
