import type { AbortSignalLike } from '@amber/deepread-domain';
import type { AgentTool } from '../tool.ts';
import { makeAgentTool, makeInputSchemaObj } from '../tool.ts';
import type { JsonObject, JsonValue } from '../json.ts';
import type { UIMessagePart } from '../message.ts';
import { SSHProfileStore, TerminalError } from './profile_store.ts';
import type { TerminalRuntime } from './runtime.ts';
import { terminalFailure } from './control.ts';
import { consumeSSHHostTrustConfirmation } from './host_trust_approval.ts';

const result = (value: JsonObject): UIMessagePart[] => [{ type: 'text', text: JSON.stringify(value), metadata: null }];

export const createSSHHostTrustTool = (runtime: TerminalRuntime, profiles: SSHProfileStore): AgentTool => makeAgentTool({
  name: 'ssh_host_trust',
  description: 'First-use SSH host trust only. Call with profile_id to probe its host key (preceding jumps must be trusted). '
    + 'Show the returned hop number and SHA256 fingerprint to the user for independent verification. Then call again '
    + 'with that fingerprint for foreground approval. Existing host keys can NEVER be replaced by this tool; changes '
    + 'must be reviewed manually in Settings. No target credentials are sent during probing.',
  needsApproval: true, mandatoryApproval: true, allowsAutoApproval: false,
  parameters: () => makeInputSchemaObj({
    profile_id: { type: 'string', description: 'Saved profile whose host key is being confirmed.' },
    fingerprint: { type: 'string', description: 'Omit to probe; supply the exact returned SHA256 fingerprint to approve initial trust.' },
  }, ['profile_id']),
  execute: async (input: JsonValue, signal?: AbortSignalLike): Promise<UIMessagePart[]> => {
    try {
      if (input === null || typeof input !== 'object' || Array.isArray(input)
        || typeof input['profile_id'] !== 'string') throw new TerminalError('invalid_arguments');
      const args: JsonObject = input as JsonObject;
      const id: string = args['profile_id'] as string;
      const profile = profiles.get(id);
      if (profile === null) throw new TerminalError('profile_missing');
      const fingerprint: JsonValue | undefined = args['fingerprint'];
      if (fingerprint !== undefined && typeof fingerprint !== 'string') throw new TerminalError('invalid_arguments');
      if (profile.knownHostSHA256 !== null) {
        return result({ ok: false, status: 'settings_required', profile_id: id,
          message: '已保存的主机指纹只能在设置页人工核对和替换。' });
      }
      const probe = await runtime.probe(profile, signal);
      if (!fingerprint) return result({ ok: true, status: 'awaiting_host_trust', profile_id: id,
        hop_index: probe.hopIndex ?? 1, hop_count: probe.hopCount ?? 1,
        fingerprint: probe.fingerprintSHA256, host_key_type: probe.hostKeyType,
        message: '请通过可信渠道核对指纹，再携带 fingerprint 发起首次信任审批。' });
      if (fingerprint !== probe.fingerprintSHA256) return result({ ok: false, status: 'host_key_changed',
        hop_index: probe.hopIndex ?? 1, hop_count: probe.hopCount ?? 1, profile_id: id,
        expected_fingerprint: fingerprint, presented_fingerprint: probe.fingerprintSHA256,
        message: `第 ${probe.hopIndex ?? 1} 跳主机指纹变化，已拦截。请在设置页处理。` });
      // commitVerified checks revision, so concurrent settings edits cannot be overwritten.
      const target = runtime.captureTarget(id);
      if (!consumeSSHHostTrustConfirmation(args, target)) {
        throw new TerminalError('host_trust_confirmation_required');
      }
      await profiles.commitVerified(profile, probe.profileRevision, probe.fingerprintSHA256, null, target.digest);
      return result({ ok: true, status: 'trusted', profile_id: id, fingerprint });
    } catch (error) {
      const failure = terminalFailure(error as Error);
      return result({ ok: false, status: 'failed', error_code: failure.code,
        hop_index: failure.hopIndex ?? null, hop_count: failure.hopCount ?? null,
        profile_id: failure.profileId ?? null, message: failure.message });
    }
  },
});
