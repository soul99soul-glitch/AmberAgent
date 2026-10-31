import type { JsonObject, JsonValue } from '../json.ts';
import type { UIMessagePartTool } from '../message.ts';
import type { SSHTargetSnapshot } from './models.ts';
import { TerminalError } from './profile_store.ts';

interface ForegroundConfirmation {
  input: string;
  target: SSHTargetSnapshot;
}
interface AuthorizedInput {
  target: SSHTargetSnapshot;
  fingerprint: string;
}

// Only the platform's unlocked foreground verdict records a confirmation. Neither
// persisted approvalState nor model-controlled metadata can recreate it on resume.
const confirmations: Map<string, ForegroundConfirmation> = new Map();
const authorizedInputs: WeakMap<JsonObject, AuthorizedInput> = new WeakMap();
const objectValue = (value: JsonValue | undefined): JsonObject | null =>
  value !== undefined && value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as JsonObject : null;
const parseInput = (text: string): JsonObject | null => {
  try { return objectValue(JSON.parse(text || '{}') as JsonValue); } catch { return null; }
};
const sameTarget = (a: SSHTargetSnapshot, b: SSHTargetSnapshot): boolean =>
  a.profileId === b.profileId && a.digest === b.digest && a.usesDefault === b.usesDefault;

export const requiresSSHHostTrustConfirmation = (part: UIMessagePartTool): boolean => {
  if (part.toolName !== 'ssh_host_trust') return false;
  const input: JsonObject | null = parseInput(part.input);
  return input === null || (typeof input['fingerprint'] === 'string' && input['fingerprint'].length > 0);
};

export const clearSSHHostTrustConfirmation = (toolCallId: string): void => { confirmations.delete(toolCallId); };

/** Call only after the platform has checked both foreground and screen-unlocked state. */
export const recordSSHHostTrustConfirmation = (part: UIMessagePartTool): void => {
  clearSSHHostTrustConfirmation(part.toolCallId);
  if (!requiresSSHHostTrustConfirmation(part)) return;
  const input: JsonObject | null = parseInput(part.input);
  const target: JsonObject | null = objectValue(part.metadata?.['terminal_target']);
  if (part.toolCallId.length === 0 || input === null || target === null
    || typeof input['fingerprint'] !== 'string' || input['fingerprint'].length === 0
    || typeof target['profileId'] !== 'string' || target['profileId'] !== input['profile_id']
    || typeof target['digest'] !== 'string' || target['digest'].length === 0 || target['usesDefault'] !== false) {
    throw new TerminalError('host_trust_confirmation_required');
  }
  confirmations.set(part.toolCallId, { input: part.input, target: {
    profileId: target['profileId'], digest: target['digest'], usesDefault: false,
  } });
};

/** Bind the one-use foreground verdict to this dispatcher's parsed argument object. */
export const authorizeSSHHostTrustInput = (
  part: UIMessagePartTool, input: JsonObject, current: SSHTargetSnapshot,
): boolean => {
  const confirmation: ForegroundConfirmation | undefined = confirmations.get(part.toolCallId);
  clearSSHHostTrustConfirmation(part.toolCallId);
  if (part.approvalState.type !== 'approved' || confirmation === undefined
    || confirmation.input !== part.input || !sameTarget(confirmation.target, current)
    || input['profile_id'] !== current.profileId || typeof input['fingerprint'] !== 'string') return false;
  authorizedInputs.set(input, { target: current, fingerprint: input['fingerprint'] });
  return true;
};

/** Recheck the live route after probing, immediately before the durable pin write. */
export const consumeSSHHostTrustConfirmation = (input: JsonObject, current: SSHTargetSnapshot): boolean => {
  const authorized: AuthorizedInput | undefined = authorizedInputs.get(input);
  authorizedInputs.delete(input);
  return authorized !== undefined && sameTarget(authorized.target, current)
    && input['profile_id'] === current.profileId && input['fingerprint'] === authorized.fingerprint;
};
