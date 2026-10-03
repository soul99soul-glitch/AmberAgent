// Author-confirmed constraints share the existing branch settings and frozen-plan chain.
import type { NovelProject, NovelBranchSettings } from './models.ts';
import { novelId } from './models.ts';
import { defaultGhostwriteDigest } from './ghostwrite.ts';
import { invalidInput, invalidModelOutput } from './error.ts';
import type { NovelModelRequest, NovelModelRunning } from './model_running.ts';
import type { NovelAuditController } from './continuity_audit.ts';
import { latestAssistantText } from '../agent/message.ts';

export type NovelChapterContractStatus = 'draft' | 'confirmed';
export interface NovelChapterContractInput {
  outlinePlacement: string;
  goalAndConflict: string;
  mustHappen: string[];
  mustNotHappen: string[];
  endingHook: string;
  visibleFacts: string[];
}
export interface NovelChapterContract extends NovelChapterContractInput {
  id: string;
  branchId: string;
  status: NovelChapterContractStatus;
  contentDigest: string;
  updatedAt: number;
  confirmedAt: number | null;
}
export interface NovelUpcomingArc {
  beats: string[];
  updatedAt: number;
}
const normalizedLines = (items: string[]): string[] => items.flatMap(item => item.split(/\r?\n/))
  .map(item => item.trim()).filter(Boolean);

export const makeNovelChapterContract = (
  input: NovelChapterContractInput, status: NovelChapterContractStatus,
  branchId: string, now: number, id: string = novelId(),
): NovelChapterContract => {
  if (status !== 'draft' && status !== 'confirmed') throw invalidInput('本章合同状态无效');
  const clean: NovelChapterContractInput = {
    outlinePlacement: input.outlinePlacement.trim(),
    goalAndConflict: input.goalAndConflict.trim(),
    mustHappen: normalizedLines(input.mustHappen),
    mustNotHappen: normalizedLines(input.mustNotHappen),
    endingHook: input.endingHook.trim(),
    visibleFacts: normalizedLines(input.visibleFacts),
  };
  if (clean.goalAndConflict.length === 0) throw invalidInput('请填写本章目标与冲突');
  const payload: string = [clean.outlinePlacement, clean.goalAndConflict, clean.mustHappen.join('\n'),
    clean.mustNotHappen.join('\n'), clean.endingHook, clean.visibleFacts.join('\n')].join('\n---\n');
  // Internal source guard uses the workspace's FNV digest. Public iOS plans omit their SHA digest.
  return { ...clean, id, branchId, status, contentDigest: defaultGhostwriteDigest(payload),
    updatedAt: now, confirmedAt: status === 'confirmed' ? now : null };
};

const bulletText = (items: string[]): string => items.map(line => '- ' + line).join('\n');
export const chapterContractMarkdown = (contract: NovelChapterContractInput): string => [
  `## 位置\n\n${contract.outlinePlacement}`,
  `## 目标与冲突\n\n${contract.goalAndConflict}`,
  `## 必须发生\n\n${bulletText(contract.mustHappen)}`,
  `## 不可发生\n\n${bulletText(contract.mustNotHappen)}`,
  `## 收束\n\n${contract.endingHook}`,
  `## 可见事实\n\n${bulletText(contract.visibleFacts)}`,
].join('\n\n');

export const confirmedChapterPlanText = (settings: NovelBranchSettings): string => {
  const contract: NovelChapterContract | undefined = settings.chapterContract;
  if (contract === undefined) return settings.thisChapterPlan;
  return contract.status === 'confirmed'
    ? `Status: confirmed\nDigest: ${contract.contentDigest}\n\n${chapterContractMarkdown(contract)}` : '';
};

export const withNovelChapterContract = (
  project: NovelProject, input: NovelChapterContractInput, status: NovelChapterContractStatus,
  branchId: string, now: number,
): NovelProject => {
  const contract: NovelChapterContract = makeNovelChapterContract(
    input, status, branchId, now, project.branchSettings.chapterContract?.id);
  const settings: NovelBranchSettings = { ...project.branchSettings, chapterContract: contract, suggestedChapterCount: undefined };
  return { ...project, branchSettings: { ...settings, thisChapterPlan: confirmedChapterPlanText(settings) },
    updatedAt: Math.max(project.updatedAt, now), revision: project.revision + 1 };
};

export const withNovelUpcomingArc = (project: NovelProject, raw: string[], now: number): NovelProject => {
  const seen: Set<string> = new Set();
  const beats: string[] = [];
  for (const item of raw) {
    const beat: string = item.trim().slice(0, 160);
    if (!beat || seen.has(beat.toLowerCase())) continue;
    seen.add(beat.toLowerCase());
    beats.push(beat);
    if (beats.length === 8) break;
  }
  return { ...project,
    branchSettings: { ...project.branchSettings, upcomingArc: beats.length ? { beats, updatedAt: now } : undefined,
      futurePlan: bulletText(beats) },
    updatedAt: Math.max(project.updatedAt, now), revision: project.revision + 1 };
};

export const parseChapterContractProposal = (text: string): NovelChapterContractInput => {
  let raw: Partial<NovelChapterContractInput>;
  try { raw = JSON.parse(text.slice(text.indexOf('{'), text.lastIndexOf('}') + 1)); }
  catch { throw invalidModelOutput('本章合同提案不是有效 JSON'); }
  if (raw === null || typeof raw !== 'object' || typeof raw.outlinePlacement !== 'string'
    || typeof raw.goalAndConflict !== 'string' || typeof raw.endingHook !== 'string'
    || ![raw.mustHappen, raw.mustNotHappen, raw.visibleFacts].every(
      items => Array.isArray(items) && items.every(item => typeof item === 'string'))) {
    throw invalidModelOutput('本章合同提案缺少完整字段');
  }
  return raw as NovelChapterContractInput;
};

// Existing request/controller ports; planning neither writes a checkpoint nor creates a durable job.
export const collectChapterContractProposal = (
  model: NovelModelRunning, request: NovelModelRequest, control?: NovelAuditController,
): Promise<string> => new Promise((resolve, reject) => {
  let settled: boolean = false;
  let text: string = '';
  let unsubscribe: () => void = (): void => {};
  let removeCancel: () => void = (): void => {};
  const finish = (error?: Error): void => {
    if (settled) return;
    settled = true;
    clearTimeout(timer);
    unsubscribe();
    removeCancel();
    if (error) reject(error); else resolve(text);
  };
  const timer: ReturnType<typeof setTimeout> = setTimeout((): void => {
    model.cancel(request.runId);
    finish(invalidInput('本章合同提案超时'));
  }, 90_000);
  if (control?.isCancelled()) { finish(invalidInput('本章合同提案已取消')); return; }
  try {
    const stream = model.start(request);
    unsubscribe = stream.subscribe(event => {
      if (event.kind === 'snapshot') text = latestAssistantText(event.messages);
      else if (event.kind === 'completed') finish();
      else if (event.kind === 'failed') finish(invalidInput(event.message));
      else if (event.kind === 'waiting_user') finish(invalidInput('合同提案不支持交互'));
    });
    if (settled) unsubscribe();
    else removeCancel = control?.onCancel((): void => {
      model.cancel(request.runId);
      finish(invalidInput('本章合同提案已取消'));
    }) ?? ((): void => {});
  } catch (error) { finish(error instanceof Error ? error : new Error(String(error))); }
});

// A legacy text edit supersedes the corresponding structured value; unrelated edits retain it.
export const reconcileNovelBranchPlans = (
  previous: NovelBranchSettings, next: NovelBranchSettings,
): NovelBranchSettings => ({
  ...next,
  chapterContract: previous.thisChapterPlan !== next.thisChapterPlan &&
    JSON.stringify(previous.chapterContract) === JSON.stringify(next.chapterContract) ? undefined : next.chapterContract,
  suggestedChapterCount: previous.thisChapterPlan !== next.thisChapterPlan &&
    JSON.stringify(previous.chapterContract) === JSON.stringify(next.chapterContract) ? undefined : next.suggestedChapterCount,
  upcomingArc: previous.futurePlan !== next.futurePlan &&
    JSON.stringify(previous.upcomingArc) === JSON.stringify(next.upcomingArc) ? undefined : next.upcomingArc,
});

export const forkNovelBranchPlans = (settings: NovelBranchSettings, branchId: string): NovelBranchSettings => ({
  ...settings,
  chapterContract: settings.chapterContract === undefined ? undefined
    : { ...settings.chapterContract, id: novelId(), branchId },
});
