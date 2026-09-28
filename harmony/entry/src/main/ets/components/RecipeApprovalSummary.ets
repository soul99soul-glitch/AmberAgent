import type { UIMessagePartTool, JsonValue, RecipeRunCheckpoint, RecipeImportCheckpoint } from '@amber/chat-domain';

export interface RecipeApprovalView {
  kind: 'run' | 'import';
  title: string;
  stepLabel: string;
  hash: string;
  baseHash: string | null;
  workspacePath: string;
  canonicalJSON: string;
  pendingStep: UIMessagePartTool | null;
}

// Read the checkpoint already pinned by the runner; never look up a live package
// or rebuild step arguments from the parent's model inputs.
export const recipeApprovalView = (parent: UIMessagePartTool): RecipeApprovalView | null => {
  if (parent.toolName !== 'recipe_import' && !parent.toolName.startsWith('recipe__')) return null;
  const raw: JsonValue | undefined = parent.metadata?.['recipe_v1'];
  if (raw === null || raw === undefined || typeof raw !== 'object' || Array.isArray(raw)) return null;
  if (raw['kind'] === 'import' && parent.toolName === 'recipe_import') {
    const checkpoint: RecipeImportCheckpoint = raw as Object as RecipeImportCheckpoint;
    const preview = checkpoint.preview;
    return { kind: 'import', title: `安装 Recipe ${preview.candidate.manifest.name} · ${preview.candidate.manifest.version}`,
      stepLabel: '固定导入内容', hash: preview.candidate.hash, baseHash: preview.baseHash,
      workspacePath: preview.workspacePath, canonicalJSON: preview.candidate.canonicalJSON, pendingStep: null };
  }
  if (raw['kind'] !== 'run') return null;
  const checkpoint: RecipeRunCheckpoint = raw as Object as RecipeRunCheckpoint;
  const manifest = checkpoint.descriptor.manifest;
  const step = manifest.steps[checkpoint.nextIndex];
  return { kind: 'run', title: `Recipe ${manifest.name} · ${manifest.version}`,
    stepLabel: step === undefined ? `已完成 ${checkpoint.completedSteps.length}/${manifest.steps.length} 步`
      : `步骤 ${checkpoint.nextIndex + 1}/${manifest.steps.length} · ${step.id} · ${checkpoint.pendingStep?.toolName ?? step.tool}`,
    hash: checkpoint.descriptor.hash, baseHash: null, workspacePath: '', canonicalJSON: '', pendingStep: checkpoint.pendingStep };
};

// Only the display part is projected. Submission still locates the parent's
// message/part, and the runner transfers its answered state to the saved step.
export const recipeAskUserPart = (parent: UIMessagePartTool): UIMessagePartTool | null => {
  const view: RecipeApprovalView | null = recipeApprovalView(parent);
  const step: UIMessagePartTool | null = view?.pendingStep ?? null;
  if (step === null || step.toolName !== 'ask_user') return null;
  return { type: 'tool', toolCallId: parent.toolCallId, toolName: step.toolName, input: step.input,
    output: step.output, approvalState: parent.approvalState, metadata: step.metadata };
};
