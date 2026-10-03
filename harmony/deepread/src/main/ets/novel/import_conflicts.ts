// Copy only project identity through the public format's known serializer.
// Branch/entity IDs are scoped to the new workspace; opaque files stay byte-for-byte.
import { invalidInput } from './error.ts';
import { validateNovelWorkspacePath, parseNovelWorkspaceManifest } from './workspace_contract.ts';
import { decodeNovelWorkspaceUtf8 } from './workspace_exchange.ts';
import { buildNovelWorkspacePublicFiles, buildNovelWorkspaceImportPlan } from './workspace_interop.ts';
import type { NovelWorkspaceImportPlan } from './workspace_interop.ts';

export type NovelImportResolution = 'reject' | 'replace' | 'keepBoth';

export const copyNovelWorkspaceImportPlan = (plan: NovelWorkspaceImportPlan,
  projectId: string): NovelWorkspaceImportPlan => {
  validateNovelWorkspacePath(projectId);
  if (projectId.includes('/') || projectId.startsWith('.') || projectId === plan.manifest.projectId) {
    throw invalidInput('副本项目标识无效');
  }
  const title: string = plan.manifest.title + '（副本）';
  const copied: NovelWorkspaceImportPlan = {
    ...plan, manifest: { ...plan.manifest, projectId, title },
    branches: plan.branches.map(branch => ({ ...branch,
      project: { ...branch.project, id: projectId, name: title } })),
  };
  const files = buildNovelWorkspacePublicFiles(copied);
  const manifestFile = files.find(file => file.path === 'manifest.yaml')!;
  const projectFile = files.find(file => file.path === 'project.md')!;
  return buildNovelWorkspaceImportPlan(parseNovelWorkspaceManifest(
    decodeNovelWorkspaceUtf8(manifestFile.bytes), decodeNovelWorkspaceUtf8(projectFile.bytes)), files);
};
