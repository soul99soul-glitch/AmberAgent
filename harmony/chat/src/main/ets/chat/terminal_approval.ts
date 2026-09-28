// Approval resumes rebuild tool factories; only the persisted invocation target
// identifies the endpoint and protocol the user approved.
import type { JsonObject, JsonValue } from './json.ts';
import type { UIMessagePartTool } from './message.ts';
import type { ToolInvocationHook, ToolInvocationRequest, ToolInvocationResult } from './tool_dispatcher.ts';
import type { SSHTargetSnapshot } from './terminal/models.ts';
import type { TerminalRuntime } from './terminal/runtime.ts';
import type { MoshRuntime } from './mosh/runtime.ts';
import type { SSHProfileStore } from './terminal/profile_store.ts';

export interface TerminalApprovalGuard {
  captureInvocationMetadata: (part: UIMessagePartTool) => JsonObject | null;
  hook: ToolInvocationHook;
}
const SSH_TARGET_TOOLS: Set<string> = new Set([
  'terminal_execute', 'terminal_job_start', 'terminal_session_start', 'terminal_session_exec',
]);
const MOSH_TARGET_TOOLS: Set<string> = new Set(['terminal_mosh_session_start', 'terminal_mosh_session_exec']);
type TerminalProtocol = 'remote_ssh' | 'remote_mosh';
const protocolFor = (name: string): TerminalProtocol | null => SSH_TARGET_TOOLS.has(name) ? 'remote_ssh'
  : MOSH_TARGET_TOOLS.has(name) ? 'remote_mosh' : null;
const sessionExec = (name: string): boolean => name === 'terminal_session_exec' || name === 'terminal_mosh_session_exec';
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
    message: 'Terminal target or protocol changed, or approval context is missing. Submit a new terminal call and approve its current target and protocol.',
  }) }],
  metadata: {},
});

export const createTerminalApprovalGuard = (
  runtime: Pick<TerminalRuntime, 'captureTarget' | 'readSession'>,
  profiles: Pick<SSHProfileStore, 'snapshot'>,
  moshRuntime?: Pick<MoshRuntime, 'captureTarget' | 'readSession'>,
): TerminalApprovalGuard => {
  const targets: Map<string, SSHTargetSnapshot> = new Map();
  const moshTargets: Map<string, SSHTargetSnapshot> = new Map();
  const labels: Map<string, string> = new Map();
  const settings = profiles.snapshot();
  for (const profile of settings.profiles) {
    targets.set(profile.id, runtime.captureTarget(profile.id));
    if (moshRuntime !== undefined) moshTargets.set(profile.id, moshRuntime.captureTarget(profile.id));
    labels.set(profile.id, `${profile.username}@${profile.host}:${profile.port}`);
  }
  const defaultTarget: SSHTargetSnapshot | null = settings.defaultProfileId === null
    ? null : runtime.captureTarget(null);
  const moshDefaultTarget: SSHTargetSnapshot | null = settings.defaultProfileId === null || moshRuntime === undefined
    ? null : moshRuntime.captureTarget(null);

  const frozenTarget = (part: UIMessagePartTool, args: JsonObject, protocol: TerminalProtocol): SSHTargetSnapshot | null => {
    const selectedRuntime = protocol === 'remote_mosh' ? moshRuntime : runtime;
    if (selectedRuntime === undefined) return null;
    const selectedTargets: Map<string, SSHTargetSnapshot> = protocol === 'remote_mosh' ? moshTargets : targets;
    if (sessionExec(part.toolName)) {
      const id: JsonValue | undefined = args['session_id'];
      if (typeof id !== 'string') return null;
      try { return selectedTargets.get(selectedRuntime.readSession(id).profileId) ?? null; }
      catch { return null; }
    }
    const id: JsonValue | undefined = args['profile_id'];
    return defaultProfileArg(id) ? protocol === 'remote_mosh' ? moshDefaultTarget : defaultTarget
      : typeof id === 'string' ? selectedTargets.get(id) ?? null : null;
  };
  const captureInvocationMetadata = (part: UIMessagePartTool): JsonObject | null => {
    const protocol: TerminalProtocol | null = protocolFor(part.toolName);
    if (protocol === null) return null;
    const args: JsonObject | null = parseInput(part);
    const target: SSHTargetSnapshot | null = args === null ? null : frozenTarget(part, args, protocol);
    return { terminal_protocol: protocol, terminal_target_label: target === null ? '' : labels.get(target.profileId) ?? '',
      terminal_target: target === null ? null : {
      profileId: target.profileId, digest: target.digest, usesDefault: target.usesDefault,
    } };
  };
  const hook: ToolInvocationHook = {
    before: async (request: ToolInvocationRequest): Promise<ToolInvocationResult | null> => {
      const protocol: TerminalProtocol | null = protocolFor(request.tool.toolName);
      if (protocol === null) return null;
      const savedProtocol: JsonValue | undefined = request.tool.metadata?.['terminal_protocol'];
      if (savedProtocol !== protocol && !(protocol === 'remote_ssh' && savedProtocol === undefined)) return targetChanged();
      const selectedRuntime = protocol === 'remote_mosh' ? moshRuntime : runtime;
      if (selectedRuntime === undefined) return targetChanged();
      const approved: SSHTargetSnapshot | null = savedTarget(request.tool);
      const args: JsonObject | null = objectValue(request.parsedArgs ?? undefined);
      if (approved === null || args === null) return targetChanged();
      try {
        if (sessionExec(request.tool.toolName)) {
          const id: JsonValue | undefined = args['session_id'];
          if (typeof id !== 'string' || approved.usesDefault ||
            selectedRuntime.readSession(id).profileId !== approved.profileId) return targetChanged();
        } else {
          const id: JsonValue | undefined = args['profile_id'];
          if (approved.usesDefault ? !defaultProfileArg(id) : id !== approved.profileId) {
            return targetChanged();
          }
        }
        const current: SSHTargetSnapshot = selectedRuntime.captureTarget(approved.usesDefault ? null : approved.profileId);
        if (current.profileId !== approved.profileId || current.digest !== approved.digest ||
          current.usesDefault !== approved.usesDefault) return targetChanged();
      } catch { return targetChanged(); }
      return null;
    },
  };
  return { captureInvocationMetadata, hook };
};
