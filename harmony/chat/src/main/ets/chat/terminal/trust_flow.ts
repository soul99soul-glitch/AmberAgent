import type { AbortSignalLike } from '@amber/deepread-domain';
import { newId } from '../ids.ts';
import type { SSHConnectionOptions, SSHCredential, SSHProfile, SSHProfileDraft, SSHTrustProbe } from './models.ts';
import type { SSHTransportPort } from './ports.ts';
import { SSHProfileStore, TerminalError, draftDigest, normalizeDraft, sameBinding, sameHostAndRoute, targetDigest } from './profile_store.ts';
import { SSHTargetResolver } from './target_resolver.ts';
import { checkAbort } from './control.ts';

// Settings can replace a pin; the agent can only approve an initially untrusted host.
// Probe authenticates preceding jumps, never the host whose key is being inspected.
export class SSHTrustFlow {
  constructor(private readonly profiles: SSHProfileStore, private readonly resolver: SSHTargetResolver,
    private readonly transport: SSHTransportPort) {}

  private routeDigest(draft: SSHProfileDraft): string {
    return draft.jumpProfileId ? JSON.stringify(this.resolver.resolveRoute(draft.jumpProfileId).map(targetDigest)) : '';
  }
  async probe(draft: SSHProfileDraft, signal?: AbortSignalLike): Promise<SSHTrustProbe> {
    const d: SSHProfileDraft = normalizeDraft(draft);
    const revision: number | null = this.profiles.get(d.id)?.revision ?? null;
    const routeDigest: string = this.routeDigest(d);
    const hops = await this.resolver.prepareJumpHopsForDraft(d.jumpProfileId, signal);
    checkAbort(signal);
    const result = await this.transport.probe(newId(), {
      host: d.host, port: d.port, timeoutMs: 10000, profileId: d.id, jumpHops: hops,
    }, signal);
    checkAbort(signal);
    if ((this.profiles.get(d.id)?.revision ?? null) !== revision || this.routeDigest(d) !== routeDigest) {
      throw new TerminalError('profile_conflict');
    }
    const current: SSHProfile | null = this.profiles.get(d.id);
    const expected: string | null = current !== null && sameHostAndRoute(current, d) ? current.knownHostSHA256 : null;
    return { draftDigest: draftDigest(d), profileRevision: revision, fingerprintSHA256: result.fingerprintSHA256,
      hostKeyType: result.hostKeyType, expectedFingerprintSHA256: expected, routeDigest,
      hopIndex: hops.length + 1, hopCount: hops.length + 1,
      trustState: expected === null ? 'untrusted' : expected === result.fingerprintSHA256 ? 'trusted' : 'mismatch' };
  }
  async verifyAndSave(draft: SSHProfileDraft, probe: SSHTrustProbe, credential: SSHCredential | null,
    acceptFingerprint: boolean, signal?: AbortSignalLike): Promise<SSHProfile> {
    const d: SSHProfileDraft = normalizeDraft(draft);
    const validate = (): void => {
      checkAbort(signal);
      if (draftDigest(d) !== probe.draftDigest || (this.profiles.get(d.id)?.revision ?? null) !== probe.profileRevision
        || this.routeDigest(d) !== (probe.routeDigest ?? '')) throw new TerminalError('profile_conflict');
    };
    validate();
    if (!probe.fingerprintSHA256.startsWith('SHA256:')) throw new TerminalError('invalid_arguments');
    if (probe.trustState !== 'trusted' && !acceptFingerprint) throw new TerminalError('host_trust_required');
    const old: SSHProfile | null = this.profiles.get(d.id);
    let auth: SSHCredential | null = credential;
    if (auth === null) {
      if (d.keyId) auth = await this.resolver.loadHopCredential({ ...d, revision: 0, credentialRef: null,
        knownHostSHA256: null, knownHostHost: null, knownHostPort: null });
      else if (old !== null && sameBinding(old, d)) auth = await this.resolver.loadHopCredential(old);
    }
    if (auth === null || !auth.secret) throw new TerminalError('credential_missing');
    const jumpHops = await this.resolver.prepareJumpHopsForDraft(d.jumpProfileId, signal);
    validate();
    const connection: SSHConnectionOptions = { host: d.host, port: d.port, profileId: d.id,
      username: d.username, authMethod: d.authMethod, secret: auth.secret, passphrase: auth.passphrase,
      expectedFingerprintSHA256: probe.fingerprintSHA256, connectTimeoutMs: 10000, jumpHops };
    // Authentication only: bastions may explicitly forbid shell/session channels.
    if (this.transport.verify === undefined) throw new TerminalError('authentication_check_unavailable');
    await this.transport.verify(newId(), connection, signal);
    validate();
    return this.profiles.commitVerified(d, probe.profileRevision, probe.fingerprintSHA256, credential);
  }
}
