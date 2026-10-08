// NovelPlanProposalProjection — 「本章计划提案」的窄 UI 投影(E13-A 冻结合同)
//
// 只有完整有效的 novel_workspace_write input 才投影为专用卡:
//   - proposal_id 非空字符串;
//   - patches 非空数组,且每个 patch 都是 write(string content)或 delete(null content);
//   - 全部 patch 指向同一个 plan/this-chapter.md,或规范 branches/<单分支>/plan/this-chapter.md。
// 保序保留**每一个** patch(含重复写/删),不隐藏任何已批准副作用。
// 混合路径/未知操作/坏 JSON 返回 null — 原通用工具 UI 与原始 input 保留,
// 不构造新的可批准伪目标;授权判定仍归 workspaceToolPath/固定 patch/CAS。

import type { JsonObject, JsonValue, UIMessagePartTool } from '@amber/chat-domain';
import { indicatesFailure, toolOutputJson, toolOutputText } from '@amber/chat-domain';

export interface NovelChapterPlanPatchView {
  operation: 'write' | 'delete';
  path: string;
  content: string | null;
}

export type NovelChapterPlanProposalStatus =
  'pending' | 'approved' | 'running' | 'accepted' | 'rejected' | 'failed' | 'unknown';

export interface NovelChapterPlanProposalView {
  proposalId: string;
  patches: NovelChapterPlanPatchView[];
  status: NovelChapterPlanProposalStatus;
  resultText: string;
}

const PLAN_PATH: string = 'plan/this-chapter.md';
const BRANCH_PLAN_PATTERN: RegExp = /^branches\/[^/]+\/plan\/this-chapter\.md$/;

const objectOf = (value: JsonValue | undefined): JsonObject | null =>
  value !== null && typeof value === 'object' && !Array.isArray(value) ? value as JsonObject : null;

const isPlanPath = (path: string): boolean =>
  path === PLAN_PATH || BRANCH_PLAN_PATTERN.test(path);

export const parseNovelChapterPlanProposal = (
  part: UIMessagePartTool,
): NovelChapterPlanProposalView | null => {
  if (part.toolName !== 'novel_workspace_write') return null;
  let input: JsonValue;
  try {
    input = JSON.parse(part.input) as JsonValue;
  } catch (_error) {
    return null;
  }
  const args: JsonObject | null = objectOf(input);
  if (args === null) return null;
  const proposalId: JsonValue | undefined = args['proposal_id'];
  if (typeof proposalId !== 'string' || proposalId.trim().length === 0) return null;
  const rawPatches: JsonValue | undefined = args['patches'];
  if (!Array.isArray(rawPatches) || rawPatches.length === 0) return null;
  const patches: NovelChapterPlanPatchView[] = [];
  let targetPath: string | null = null;
  for (const raw of rawPatches) {
    const patch: JsonObject | null = objectOf(raw);
    if (patch === null) return null;
    const operation: JsonValue | undefined = patch['operation'];
    const path: JsonValue | undefined = patch['path'];
    const content: JsonValue | undefined = patch['content'];
    if (typeof path !== 'string' || !isPlanPath(path)) return null;
    if (targetPath === null) {
      targetPath = path;
    } else if (path !== targetPath) {
      // 混合路径(跨分支/跨文件)保留通用工具 UI,不投影
      return null;
    }
    if (operation === 'write') {
      if (typeof content !== 'string') return null;
      patches.push({ operation: 'write', path: path, content: content });
    } else if (operation === 'delete') {
      if (content !== null && content !== undefined) return null;
      patches.push({ operation: 'delete', path: path, content: null });
    } else {
      return null;
    }
  }
  if (targetPath === null) return null;
  const output: JsonObject = toolOutputJson(part);
  let status: NovelChapterPlanProposalStatus = 'unknown';
  let resultText: string = '';
  if (part.approvalState.type === 'denied') {
    status = 'rejected';
    resultText = part.approvalState.reason ?? '';
  } else if (part.output.length > 0) {
    resultText = toolOutputText(part);
    if (indicatesFailure(output) && (output['proposal_id'] === undefined || output['proposal_id'] === proposalId)) {
      status = 'failed';
      const message: JsonValue | undefined = output['message'] ?? output['error'];
      resultText = typeof message === 'string' ? message : '本章计划未能应用，请查看提案内容后重试。';
    } else if (output['proposal_id'] === proposalId && output['status'] === 'accepted') {
      status = 'accepted';
      resultText = '';
    } else if (output['proposal_id'] === proposalId && output['status'] === 'rejected') {
      status = 'rejected';
      resultText = '';
    }
  } else if (part.approvalState.type === 'pending') {
    status = 'pending';
  } else if (part.approvalState.type === 'approved') {
    status = 'approved';
    resultText = '已批准，等待执行后写入本章计划。';
  } else {
    status = 'running';
  }
  return { proposalId: proposalId, patches: patches, status: status, resultText: resultText };
};
