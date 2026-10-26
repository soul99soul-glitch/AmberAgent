import { resolveNovelDefaultTarget } from './standalone_defaults.ts';
import type { NovelModelDefaults } from './standalone_defaults.ts';
import { novelId, novelChapterOrdinal } from './models.ts';
import { invalidInput, notFound, projectBusy } from './error.ts';

import type { NovelModelRunning } from './model_running.ts';
import {
  prepareContinuityRepair, parseContinuityRepair, mergeContinuityRepairs, collectContinuityRepairText,
  CONTINUITY_REPAIR_SYSTEM_PROMPT, REPAIR_MAX_OUTPUT_TOKENS,
} from './continuity_repair.ts';
import type { AppliedContinuityRepair } from './continuity_repair.ts';
import type { NovelAuditIssue } from './continuity_audit.ts';
import { chapterFileName } from './workspace_contract.ts';
import type { NovelProjectRepository } from './repository.ts';
import type { DurableWorkspaceProposal, WorkspaceProposalPatch, WorkspaceProposalReview } from './workspace_history.ts';
import { materialSuggestionChapterDigest } from './material_adoption.ts';
import type { NovelWorkspaceToolProposals } from './workspace_tool_proposals.ts';

// 每项目修复只由此 owner 持有取消信号与结束等待，不与普通生成共享 live 状态。
interface LiveContinuityRepair {
  cancelled: boolean;
  cancelText: (() => void) | null;
  done: Promise<void>;
  finish: () => void;
}

interface NovelContinuityRepairDeps {
  repository: NovelProjectRepository;
  modelRunning: NovelModelRunning;
  loadModelDefaults: () => Promise<NovelModelDefaults>;
  workspaceTools: NovelWorkspaceToolProposals;
  projectRunning: (projectId: string) => boolean;
}

export class NovelContinuityRepairService {
  private repository: NovelProjectRepository;
  private modelRunning: NovelModelRunning;
  private loadModelDefaults: () => Promise<NovelModelDefaults>;
  private workspaceTools: NovelWorkspaceToolProposals;
  private projectRunning: (projectId: string) => boolean;
  private repairRuns: Map<string, LiveContinuityRepair> = new Map();

  constructor(deps: NovelContinuityRepairDeps) {
    this.repository = deps.repository;
    this.modelRunning = deps.modelRunning;
    this.loadModelDefaults = deps.loadModelDefaults;
    this.workspaceTools = deps.workspaceTools;
    this.projectRunning = deps.projectRunning;
  }

  isRunning(projectId: string): boolean { return this.repairRuns.has(projectId); }

  async cancelContinuityRepair(projectId: string): Promise<void> {
    const run = this.repairRuns.get(projectId);
    if (run === undefined) return;
    run.cancelled = true; run.cancelText?.();
    await run.done;
  }

  async repairContinuityIssues(projectId: string, issues: NovelAuditIssue[]): Promise<DurableWorkspaceProposal> {
    if (this.projectRunning(projectId)) throw projectBusy(projectId);
    if (issues.length === 0) throw invalidInput('没有可修复的审校证据');
    let finish: () => void = (): void => {};
    const run: LiveContinuityRepair = { cancelled: false, cancelText: null,
      done: new Promise<void>(resolve => { finish = resolve; }), finish: (): void => finish() };
    this.repairRuns.set(projectId, run);
    try {
      const snapshot = await this.repository.readWorkspaceSnapshot(projectId);
      const prepared = issues.map(issue => {
        const chapter = snapshot.project.chapters.find(item => item.id === issue.chapterId && !item.discarded);
        if (chapter === undefined) throw notFound('chapter', issue.chapterId);
        const frozen = { ...chapter }; const evidence = { ...issue };
        return { frozen, evidence, plan: prepareContinuityRepair(frozen, evidence, snapshot.project.chapters) };
      });
      const sorted = prepared.slice().sort((a, b) => a.frozen.id.localeCompare(b.frozen.id) || a.evidence.start - b.evidence.start);
      for (let index: number = 1; index < sorted.length; index++) {
        if (sorted[index - 1].frozen.id === sorted[index].frozen.id && sorted[index - 1].evidence.end > sorted[index].evidence.start) {
          throw invalidInput('审校修复范围重叠，请逐条选择或重新审校');
        }
      }
      const target = resolveNovelDefaultTarget(snapshot.project.modelPolicy, await this.loadModelDefaults(), 'review');
      await this.modelRunning.validate(target, projectId);
      const repairs: AppliedContinuityRepair[] = [];
      const previews: WorkspaceProposalReview['previews'] = [];
      for (const item of prepared) {
        if (run.cancelled) throw invalidInput('连续性修复已取消');
        const collecting = collectContinuityRepairText(this.modelRunning, {
          runId: `repair:${novelId()}`, projectId, systemPrompt: CONTINUITY_REPAIR_SYSTEM_PROMPT,
          maxOutputTokens: REPAIR_MAX_OUTPUT_TOKENS, modelTarget: target, history: [], toolProfile: 'none',
          operation: { kind: 'turn', userPrompt: item.plan.userPrompt }, checkpoint: async (): Promise<void> => {},
        });
        run.cancelText = collecting.cancel;
        const text = await collecting.result; run.cancelText = null;
        if (run.cancelled) throw invalidInput('连续性修复已取消');
        const repair = parseContinuityRepair(text, item.frozen, item.evidence);
        repairs.push(repair);
        const ordinal = novelChapterOrdinal(item.frozen, snapshot.project.chapters.findIndex(chapter => chapter.id === item.frozen.id) + 1);
        previews.push({ label: `第 ${ordinal} 章 ${item.frozen.title}`, chapterId: item.frozen.id,
          oldText: item.evidence.quote, newText: repair.replacement, sourceDigest: materialSuggestionChapterDigest(item.frozen),
          start: item.evidence.start, end: item.evidence.end });
      }
      const patches: WorkspaceProposalPatch[] = [];
      for (const chapter of snapshot.project.chapters.filter(item => repairs.some(repair => repair.chapterId === item.id))) {
        const ordinal = novelChapterOrdinal(chapter, snapshot.project.chapters.indexOf(chapter) + 1);
        patches.push({ operation: 'write',
          path: `branches/${snapshot.status.activeBranchId}/chapters/${chapterFileName(ordinal, chapter.title)}`,
          content: mergeContinuityRepairs(chapter, repairs.filter(repair => repair.chapterId === chapter.id)) });
      }
      if (run.cancelled) throw invalidInput('连续性修复已取消');
      return await this.workspaceTools.createWorkspaceProposal(projectId, patches, novelId(), {
        summary: `连续性修复：${issues.length} 条已取证问题`,
        sourceDigest: prepared.map(item => `${item.frozen.id}:${item.evidence.sourceDigest}`).join('|'), previews,
      }, snapshot.status.cas);
    } finally { this.repairRuns.delete(projectId); run.finish(); }
  }
}
