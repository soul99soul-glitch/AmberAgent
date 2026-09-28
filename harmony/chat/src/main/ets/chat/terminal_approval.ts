// Approval resumes rebuild tool factories; only the persisted invocation target
// identifies the SSH endpoint the user approved.
import type { JsonObject, JsonValue } from './json.ts';
import type { UIMessagePartTool } from './message.ts';
import type { ToolInvocationHook, ToolInvocationRequest, ToolInvocationResult } from './tool_dispatcher.ts';
import type { SSHTargetSnapshot } from './terminal/models.ts';
import type { TerminalRuntime } from './terminal/runtime.ts';
import type { SSHProfileStore } from './terminal/profile_store.ts';

export interface TerminalApprovalGuard {
  captureInvocationMetadata: (part: UIMessagePartTool) => JsonObject | null;
  hook: ToolInvocationHook;
}
const TARGET_TOOLS: Set<string> = new Set([
  'terminal_execute', 'terminal_job_start', 'terminal_session_start', 'terminal_session_exec',
]);
const objectValue = (value: JsonValue | undefined): JsonObject | null =>
  value !== null && value !== undefined && typeof value === 'object' && !Array.isArray(value)
    ? value as JsonObject : null;
const defaultProfileArg = (value: JsonValue | undefined): boolean =>
  value === undefined || value === null || value === '';
const parseInput = (part: UIMessagePartTool): JsonObject | null => {
  try { return objectValue(JSON.parse(part.input || '{}') as JsonValue); }
  catch { return null; }
};
const savedTarget = (part: UIMessagePartTool): SSHTargetSnapshot | null => {
  const value: JsonObject | null = objectValue(part.metadata?.['terminal_target']);
  if (value === null || typeof value['profileId'] !== 'string' || value['profileId'].length === 0 ||
    typeof value['digest'] !== 'string' || value['digest'].length === 0 ||
    typeof value['usesDefault'] !== 'boolean') return null;
  return { profileId: value['profileId'], digest: value['digest'], usesDefault: value['usesDefault'] };
};
const targetChanged = (): ToolInvocationResult => ({
  output: [{ type: 'text', metadata: null, text: JSON.stringify({
    status: 'failed', error_code: 'target_changed', recoverable: false,
    message: 'SSH target changed or approval context is missing. Submit a new terminal call and approve its current target.',
  }) }],
  metadata: {},
});

export const createTerminalApprovalGuard = (
  runtime: Pick<TerminalRuntime, 'captureTarget' | 'readSession'>,
  profiles: Pick<SSHProfileStore, 'snapshot'>,
): TerminalApprovalGuard => {
  const targets: Map<string, SSHTargetSnapshot> = new Map();
  const labels: Map<string, string> = new Map();
  const settings = profiles.snapshot();
  for (const profile of settings.profiles) {
    targets.set(profile.id, runtime.captureTarget(profile.id));
    labels.set(profile.id, `${profile.username}@${profile.host}:${profile.port}`);
  }
  const defaultTarget: SSHTargetSnapshot | null = settings.defaultProfileId === null
    ? null : runtime.captureTarget(null);

  const frozenTarget = (part: UIMessagePartTool, args: JsonObject): SSHTargetSnapshot | null => {
    if (part.toolName === 'terminal_session_exec') {
      const id: JsonValue | undefined = args['session_id'];
      if (typeof id !== 'string') return null;
      try { return targets.get(runtime.readSession(id).profileId) ?? null; }
      catch { return null; }
    }
    const id: JsonValue | undefined = args['profile_id'];
    return defaultProfileArg(id) ? defaultTarget
      : typeof id === 'string' ? targets.get(id) ?? null : null;
  };
  const captureInvocationMetadata = (part: UIMessagePartTool): JsonObject | null => {
    if (!TARGET_TOOLS.has(part.toolName)) return null;
    const args: JsonObject | null = parseInput(part);
    const target: SSHTargetSnapshot | null = args === null ? null : frozenTarget(part, args);
    return { terminal_target_label: target === null ? '' : labels.get(target.profileId) ?? '',
      terminal_target: target === null ? null : {
      profileId: target.profileId, digest: target.digest, usesDefault: target.usesDefault,
    } };
  };
  const hook: ToolInvocationHook = {
    before: async (request: ToolInvocationRequest): Promise<ToolInvocationResult | null> => {
      if (!TARGET_TOOLS.has(request.tool.toolName)) return null;
      const approved: SSHTargetSnapshot | null = savedTarget(request.tool);
      const args: JsonObject | null = objectValue(request.parsedArgs ?? undefined);
      if (approved === null || args === null) return targetChanged();
      try {
        if (request.tool.toolName === 'terminal_session_exec') {
          const id: JsonValue | undefined = args['session_id'];
          if (typeof id !== 'string' || approved.usesDefault ||
            runtime.readSession(id).profileId !== approved.profileId) return targetChanged();
        } else {
          const id: JsonValue | undefined = args['profile_id'];
          if (approved.usesDefault ? !defaultProfileArg(id) : id !== approved.profileId) {
            return targetChanged();
          }
        }
        const current: SSHTargetSnapshot = runtime.captureTarget(approved.usesDefault ? null : approved.profileId);
        if (current.profileId !== approved.profileId || current.digest !== approved.digest ||
          current.usesDefault !== approved.usesDefault) return targetChanged();
      } catch { return targetChanged(); }
      return null;
    },
  };
  return { captureInvocationMetadata, hook };
};
