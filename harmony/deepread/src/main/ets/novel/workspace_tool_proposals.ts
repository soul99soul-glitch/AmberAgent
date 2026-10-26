import type { NovelProjectToolInput } from './specialized_operations.ts';
import { novelId } from './models.ts';
import type { NovelProject, NovelMaterial } from './models.ts';
import { invalidInput } from './error.ts';
import type { NovelToolContinuationVerdict } from './model_running.ts';
import type {
  NovelProjectRepository, NovelWorkspaceStatus, NovelWorkspaceCas, NovelWorkspaceSnapshot,
} from './repository.ts';
import type { UIMessagePart, UIMessagePartTool } from '../agent/message.ts';
import type { DurableWorkspaceProposal, WorkspaceProposalPatch, WorkspaceProposalReview } from './workspace_history.ts';
import { novelMessageUi } from './transcript.ts';

// Workspace write 提案的准备、批准和拒绝恢复共用这一入口。
interface RawWorkspaceWritePatch {
  operation?: unknown;
  path?: unknown;
  content?: unknown;
}

interface RawWorkspaceWriteInput {
  proposal_id?: unknown;
  patches?: RawWorkspaceWritePatch[];
}

export class NovelWorkspaceToolProposals {
  private repository: NovelProjectRepository;
  private nowMs: () => number;

  constructor(repository: NovelProjectRepository, nowMs: () => number) {
    this.repository = repository;
    this.nowMs = nowMs;
  }

  async createWorkspaceProposal(
    projectId: string, patches: WorkspaceProposalPatch[], proposalId: string = novelId(),
    review?: WorkspaceProposalReview, expectedCas?: NovelWorkspaceCas,
  ): Promise<DurableWorkspaceProposal> {
    const status: NovelWorkspaceStatus = await this.repository.workspaceStatus(projectId);
    return await this.repository.createProposal(
      projectId, expectedCas ?? status.cas, proposalId, patches, this.nowMs(), review);
  }

  resolveWorkspaceProposal(
    projectId: string, proposalId: string, accept: boolean, commandId: string = novelId(),
  ): Promise<DurableWorkspaceProposal> {
    return this.repository.resolveProposal(projectId, proposalId, accept, commandId, this.nowMs());
  }

  async stageWorkspaceWriteProposal(
    projectId: string, proposalId: string, rawPatches: RawWorkspaceWritePatch[],
  ): Promise<DurableWorkspaceProposal> {
    const patches: WorkspaceProposalPatch[] = await this.normalizeWorkspaceProposalPatches(projectId, rawPatches);
    const snapshot: NovelWorkspaceSnapshot = await this.repository.readWorkspaceSnapshot(projectId);
    const existing: DurableWorkspaceProposal | undefined = (await this.repository.workspaceProposals(projectId))
      .find(item => item.proposalId === proposalId);
    const branch: string = snapshot.status.activeBranchId;
    const patch: WorkspaceProposalPatch = patches[0];
    const material: NovelMaterial | undefined = patches.length === 1 && patch.operation === 'write'
      ? snapshot.project.materials.find(item => patch.path === `branches/${branch}/setting/${item.kind}/${item.id}.md`) : undefined;
    if (material !== undefined) {
      const args: NovelProjectToolInput = { material_id: material.id, kind: material.kind,
        title: material.title, content: patch.content ?? '', scope: 'branch' };
      if (existing !== undefined) {
        const prior: NovelProjectToolInput | undefined = existing.operation?.args;
        if (existing.operation?.kind !== 'revise_material' || prior === undefined || prior.material_id !== args.material_id
          || prior.kind !== args.kind || prior.content !== args.content || prior.scope !== 'branch') {
          throw invalidInput(`proposal id 已用于其他资料内容: ${proposalId}`);
        }
        return existing;
      }
      return this.repository.proposeProjectOperation(projectId, 'revise_material', args,
        snapshot.status.cas, proposalId, this.nowMs());
    }
    if (existing !== undefined) {
      if (existing.operation !== undefined || JSON.stringify(existing.patches) !== JSON.stringify(patches)) {
        throw invalidInput(`proposal id 已用于其他 patches: ${proposalId}`);
      }
      return existing;
    }
    return this.createWorkspaceProposal(projectId, patches, proposalId, undefined, snapshot.status.cas);
  }

  workspaceToolPath(path: string, activeBranchId: string, directory: boolean): string {
    const clean: string = path.trim().replace(/\\/g, '/').replace(/^\.\//, '');
    if (clean.startsWith('/') || clean.split('/').some((part: string): boolean => part === '..')) {
      throw invalidInput('工作区路径无效');
    }
    const branchRoot: string = `branches/${activeBranchId}/`;
    if (clean.length === 0) return branchRoot;
    const checked: string = clean.startsWith('branches/') ? clean : branchRoot + clean;
    if (!checked.startsWith(branchRoot) || checked.indexOf('/.amber/') >= 0) {
      throw invalidInput('工具只能访问当前分支可见文件');
    }
    if (!directory && checked.endsWith('/')) throw invalidInput('读取路径必须指向文件');
    return checked;
  }

  async prepareWorkspaceWriteTool(
    projectId: string, tool: UIMessagePartTool,
    verdict: NovelToolContinuationVerdict,
  ): Promise<void> {
    if (verdict.kind === 'answered') throw invalidInput('write 工具不接受回答');
    let raw: RawWorkspaceWriteInput;
    try {
      raw = JSON.parse(tool.input) as typeof raw;
    } catch {
      throw invalidInput('write 工具参数不是有效 JSON');
    }
    const proposalId: string = typeof raw.proposal_id === 'string' ? raw.proposal_id.trim() : '';
    if (proposalId.length === 0 || !Array.isArray(raw.patches) || raw.patches.length === 0) {
      throw invalidInput('write 工具缺少 proposal_id 或 patches');
    }
    const existing: DurableWorkspaceProposal = await this.stageWorkspaceWriteProposal(projectId, proposalId, raw.patches);
    if ((existing.status === 'accepted' && verdict.kind === 'denied') ||
      (existing.status === 'rejected' && verdict.kind === 'approved')) {
      throw invalidInput(`proposal 已按相反决定结案: ${proposalId}`);
    }
  }

  private async normalizeWorkspaceProposalPatches(
    projectId: string, rawPatches: RawWorkspaceWritePatch[],
  ): Promise<WorkspaceProposalPatch[]> {
    if (!Array.isArray(rawPatches) || rawPatches.length === 0) {
      throw invalidInput('write 工具缺少 patches');
    }
    const status: NovelWorkspaceStatus = await this.repository.workspaceStatus(projectId);
    return rawPatches.map((patch: RawWorkspaceWritePatch): WorkspaceProposalPatch => {
      if (patch.operation !== 'write' && patch.operation !== 'delete') {
        throw invalidInput('write patch operation 必须是 write 或 delete');
      }
      if (typeof patch.path !== 'string' || patch.path.trim().length === 0) {
        throw invalidInput('write patch path 必须是非空字符串');
      }
      if (patch.operation === 'write' && typeof patch.content !== 'string') {
        throw invalidInput('write patch content 必须是字符串');
      }
      if (patch.operation === 'delete' && patch.content !== null) {
        throw invalidInput('delete patch content 必须是 null');
      }
      return {
        operation: patch.operation,
        path: this.workspaceToolPath(patch.path, status.activeBranchId, false),
        content: patch.operation === 'delete' ? null : patch.content as string,
      };
    });
  }

  private proposalIdFromTool(tool: UIMessagePartTool): string | null {
    try {
      const raw = JSON.parse(tool.input) as { proposal_id?: unknown };
      return typeof raw.proposal_id === 'string' && raw.proposal_id.trim().length > 0
        ? raw.proposal_id.trim() : null;
    } catch {
      return null;
    }
  }

  async resolvePreparedWorkspaceWrite(
    projectId: string, tool: UIMessagePartTool, accept: boolean,
  ): Promise<void> {
    let raw: RawWorkspaceWriteInput;
    try {
      raw = JSON.parse(tool.input) as RawWorkspaceWriteInput;
    } catch {
      throw invalidInput('write 工具参数不是有效 JSON');
    }
    const proposalId: string = typeof raw.proposal_id === 'string' ? raw.proposal_id.trim() : '';
    if (proposalId.length === 0 || !Array.isArray(raw.patches)) {
      throw invalidInput('write 工具缺少 proposal_id 或 patches');
    }
    await this.stageWorkspaceWriteProposal(projectId, proposalId, raw.patches);
    await this.resolveWorkspaceProposal(
      projectId, proposalId, accept,
      `novel:${projectId}:proposal:${proposalId}:${accept ? 'approve' : 'deny'}`);
  }

  async reconcileDeniedWorkspaceTools(
    projectId: string, project: NovelProject,
  ): Promise<boolean> {
    const proposals: DurableWorkspaceProposal[] = await this.repository.workspaceProposals(projectId);
    const existing: Map<string, DurableWorkspaceProposal> = new Map(proposals.map(
      (proposal: DurableWorkspaceProposal): [string, DurableWorkspaceProposal] =>
        [proposal.proposalId, proposal]));
    let changed: boolean = false;
    for (let i: number = 0; i < project.messages.length; i++) {
      const parts: UIMessagePart[] = novelMessageUi(project.messages[i]).parts;
      for (let p: number = 0; p < parts.length; p++) {
        const part: UIMessagePart = parts[p];
        if (part.type !== 'tool' || part.toolName !== 'novel_workspace_write' ||
          part.approvalState.type !== 'denied') continue;
        const proposalId: string | null = this.proposalIdFromTool(part);
        if (proposalId === null) continue;
        const proposal: DurableWorkspaceProposal | undefined = existing.get(proposalId);
        if (proposal?.status === 'rejected') continue;
        if (proposal?.status === 'accepted') {
          throw invalidInput(`proposal 已按相反决定结案: ${proposalId}`);
        }
        await this.resolvePreparedWorkspaceWrite(projectId, part, false);
        changed = true;
      }
    }
    return changed;
  }
}
