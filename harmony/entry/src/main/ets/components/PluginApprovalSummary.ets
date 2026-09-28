import type {
  UIMessagePartTool, JsonValue, JsonObject, PluginRunCheckpoint, PluginImportCheckpoint,
  PluginDescriptor, PluginTestContext, PluginManifest, PluginTrust, PluginSource, PluginEnvelope,
} from '@amber/chat-domain';
import type { RecipeApprovalView } from './RecipeApprovalSummary.ets';
import { pluginRuntimeLabel, pluginTrustLabel } from './PluginPageProjection.ets';

export type PluginApprovalView = RecipeApprovalView;

interface PluginRunReview { descriptor: PluginDescriptor; inputs: JsonObject; test: PluginTestContext | null; }
interface PluginImportReview {
  package_hash: string;
  manifest: PluginManifest;
  file_hashes: Record<string, string>;
  trust: PluginTrust;
  permissions: PluginEnvelope;
  permission_diff: string[];
  permission_expanded: boolean;
  enable: boolean;
  base_hash: string | null;
  source: PluginSource;
}

// Project only the checkpoint pinned before approval, never the live plugin store or model input.
export const pluginApprovalView = (parent: UIMessagePartTool): RecipeApprovalView | null => {
  if (parent.toolName !== 'plugin_import' && parent.toolName !== 'plugin_test' && !parent.toolName.startsWith('plugin__')) return null;
  const raw: JsonValue | undefined = parent.metadata?.['plugin_v1'];
  if (raw === null || raw === undefined || typeof raw !== 'object' || Array.isArray(raw)) return null;
  try {
    if (raw['kind'] === 'import' && parent.toolName === 'plugin_import') {
      const checkpoint: PluginImportCheckpoint = raw as Object as PluginImportCheckpoint;
      const preview = checkpoint.preview;
      const candidate = preview.candidate;
      const manifest = candidate.manifest;
      if (typeof candidate.hash !== 'string' || !candidate.hash || typeof manifest.name !== 'string' || !manifest.name ||
        typeof manifest.version !== 'string' || !manifest.version ||
        typeof preview.source.workspacePath !== 'string' || !preview.source.workspacePath ||
        (preview.source.kind !== 'directory' && preview.source.kind !== 'archive') ||
        candidate.fileHashes === null || typeof candidate.fileHashes !== 'object' || Array.isArray(candidate.fileHashes) ||
        !Array.isArray(candidate.files) || !candidate.files.every((file): boolean => typeof candidate.fileHashes[file.path] === 'string') ||
        typeof preview.enable !== 'boolean' || typeof preview.permissionExpanded !== 'boolean' ||
        !Array.isArray(preview.permissionDiff) || typeof preview.trust.publisherTrusted !== 'boolean' ||
        !['built_in', 'signed', 'local_unsigned'].includes(preview.trust.tier) ||
        (preview.baseHash !== null && typeof preview.baseHash !== 'string') ||
        candidate.envelope === null || typeof candidate.envelope !== 'object') return null;
      const review: PluginImportReview = { package_hash: candidate.hash, manifest: manifest,
        file_hashes: candidate.fileHashes, trust: preview.trust, permissions: candidate.envelope,
        permission_diff: preview.permissionDiff, permission_expanded: preview.permissionExpanded,
        enable: preview.enable, base_hash: preview.baseHash, source: preview.source };
      return { kind: 'import', title: `${preview.enable ? '安装并启用' : '安装'}插件 ${manifest.name} · ${manifest.version}`,
        stepLabel: `${pluginTrustLabel(preview.trust)} · 权限差异 ${preview.permissionDiff.length} 项 · ${preview.permissionExpanded ? '权限扩大' : '权限未扩大'}`,
        hash: candidate.hash, baseHash: preview.baseHash, workspacePath: preview.source.workspacePath,
        canonicalJSON: JSON.stringify(review), pendingStep: null };
    }
    if (raw['kind'] !== 'run' || parent.toolName === 'plugin_import') return null;
    const checkpoint: PluginRunCheckpoint = raw as Object as PluginRunCheckpoint;
    const descriptor = checkpoint.descriptor;
    if (typeof checkpoint.executionId !== 'string' || !checkpoint.executionId ||
      typeof descriptor.packageHash !== 'string' || !descriptor.packageHash ||
      typeof descriptor.version !== 'string' || !descriptor.version || typeof descriptor.pluginId !== 'string' ||
      typeof descriptor.toolId !== 'string' || !['recipe', 'javascript', 'command', 'remote'].includes(descriptor.implementation.kind) ||
      (descriptor.implementation.kind === 'command' && descriptor.implementation.runtime !== 'embedded_python') ||
      (descriptor.implementation.kind === 'remote' && !['mcp', 'openapi'].includes(descriptor.implementation.remote.kind)) ||
      (parent.toolName !== 'plugin_test' && parent.toolName !== descriptor.toolId) ||
      !['ready', 'running', 'awaiting_approval', 'started', 'finished'].includes(checkpoint.phase) ||
      checkpoint.inputs === null || typeof checkpoint.inputs !== 'object' || Array.isArray(checkpoint.inputs) ||
      (parent.toolName === 'plugin_test') !== (checkpoint.test !== null)) return null;
    const test: PluginTestContext | null = checkpoint.test;
    if (test !== null && (test.kind !== 'candidate_test' || test.candidateHash !== descriptor.packageHash ||
      typeof test.expectedProvided !== 'boolean' || test.expectedResult === undefined)) return null;
    const step: UIMessagePartTool | null = checkpoint.pendingStep;
    if ((checkpoint.phase === 'awaiting_approval' && step === null) ||
      (step !== null && (step.type !== 'tool' || !step.toolCallId || !step.toolName ||
        typeof step.input !== 'string' || !Array.isArray(step.output)))) return null;
    const expected: string = test === null ? '' : test.expectedProvided ?
      test.expectedResult === null ? ' · 期望结果：null' : ' · 期望结果：已提供（查看详情）' : ' · 期望结果：未提供';
    const review: PluginRunReview = { descriptor: descriptor, inputs: checkpoint.inputs, test: test };
    return { kind: 'run', title: `${test === null ? '插件' : '候选试跑'} ${descriptor.pluginId} · ${descriptor.version} · ${descriptor.toolId} · ${pluginRuntimeLabel(descriptor)}`,
      stepLabel: `${step === null ? `阶段：${checkpoint.phase}` : `当前宿主调用：${step.toolName}`}${expected}`,
      hash: descriptor.packageHash, baseHash: null, workspacePath: '', canonicalJSON: JSON.stringify(review), pendingStep: step };
  } catch (_error) { return null; }
};

// Display the exact saved ask_user child; submission still locates and answers the parent.
export const pluginAskUserPart = (parent: UIMessagePartTool): UIMessagePartTool | null => {
  const step: UIMessagePartTool | null = pluginApprovalView(parent)?.pendingStep ?? null;
  if (step === null || step.toolName !== 'ask_user') return null;
  return { type: 'tool', toolCallId: parent.toolCallId, toolName: step.toolName, input: step.input,
    output: step.output, approvalState: parent.approvalState, metadata: step.metadata };
};
