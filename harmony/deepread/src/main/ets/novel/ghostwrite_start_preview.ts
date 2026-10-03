import { confirmedChapterPlanText } from './chapter_contract.ts';
import { invalidInput } from './error.ts';
import { freezeGhostwritePlan, MAX_GHOSTWRITE_CHAPTER_COUNT } from './ghostwrite.ts';
import type { NovelWorkspaceSnapshot, NovelWorkspaceCas } from './repository.ts';

export interface NovelGhostwriteStartPreview {
  projectId: string;
  branchId: string;
  branchName: string;
  planContent: string;
  targetChapterCount: number;
  expectedCas: NovelWorkspaceCas;
}
export const makeGhostwriteStartPreview = (
  snapshot: NovelWorkspaceSnapshot, targetChapterCount: number,
): NovelGhostwriteStartPreview => {
  if (!Number.isInteger(targetChapterCount) || targetChapterCount < 1 || targetChapterCount > MAX_GHOSTWRITE_CHAPTER_COUNT) {
    throw invalidInput(`代笔章节数必须在 1-${MAX_GHOSTWRITE_CHAPTER_COUNT}`);
  }
  // Reuse the durable job's plan validity; the preview retains the full author Markdown.
  freezeGhostwritePlan({ planId: 'preview', content: confirmedChapterPlanText(snapshot.project.branchSettings),
    expectedCas: snapshot.status.cas });
  return { projectId: snapshot.project.id, branchId: snapshot.status.activeBranchId,
    branchName: snapshot.status.activeBranchName, planContent: confirmedChapterPlanText(snapshot.project.branchSettings),
    targetChapterCount, expectedCas: { ...snapshot.status.cas } };
};
export const assertGhostwriteStartPreview = (
  snapshot: NovelWorkspaceSnapshot, count: number, preview: NovelGhostwriteStartPreview,
): void => {
  const cas: NovelWorkspaceCas = snapshot.status.cas;
  if (preview.projectId !== snapshot.project.id || preview.branchId !== cas.branchId ||
    preview.expectedCas.branchId !== cas.branchId || preview.expectedCas.head !== cas.head ||
    preview.expectedCas.treeDigest !== cas.treeDigest || preview.targetChapterCount !== count ||
    preview.planContent !== confirmedChapterPlanText(snapshot.project.branchSettings)) {
    throw invalidInput('代笔预览后计划、正文或分支已变更，请重新预览');
  }
};
