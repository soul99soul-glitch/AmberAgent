import type { AbortSignalLike } from '@amber/deepread-domain';
import type { SSHConnectionOptions, SSHCredential, SSHProfile, SSHTargetSnapshot } from './models.ts';
import type { SSHCredentialStorePort } from './ports.ts';
import { SSHProfileStore, TerminalError, targetDigest } from './profile_store.ts';
import { checkAbort } from './control.ts';

export interface SSHTargetResolverDeps { profiles: SSHProfileStore; credentials: SSHCredentialStorePort; }

// One trust/credential boundary for ordinary SSH and the low-level Mosh bootstrap.
export class SSHTargetResolver {
  constructor(private readonly deps: SSHTargetResolverDeps) {}
  captureTarget(profileId: string | null): SSHTargetSnapshot {
    const profile: SSHProfile | null = profileId === null ? this.deps.profiles.defaultProfile() : this.deps.profiles.get(profileId);
    if (profile === null) throw new TerminalError('profile_missing');
    return { profileId: profile.id, digest: targetDigest(profile), usesDefault: profileId === null };
  }
  targetProfile(target: SSHTargetSnapshot): SSHProfile {
    const current: SSHProfile | null = this.deps.profiles.get(target.profileId);
    if (current === null || targetDigest(current) !== target.digest ||
      (target.usesDefault && this.deps.profiles.snapshot().defaultProfileId !== target.profileId)) {
      throw new TerminalError('target_changed');
    }
    if (current.knownHostSHA256 === null || current.knownHostHost !== current.host || current.knownHostPort !== current.port) {
      throw new TerminalError('host_trust_required');
    }
    return current;
  }
  async prepare(target: SSHTargetSnapshot, signal?: AbortSignalLike): Promise<SSHConnectionOptions> {
    checkAbort(signal);
    const p: SSHProfile = this.targetProfile(target);
    if (p.credentialRef === null) throw new TerminalError('credential_missing');
    const auth: SSHCredential | null = await this.deps.credentials.load(p.credentialRef, p);
    checkAbort(signal); this.targetProfile(target);
    if (auth === null || !auth.secret) throw new TerminalError('credential_missing');
    return { host: p.host, port: p.port, username: p.username, authMethod: p.authMethod,
      secret: auth.secret, passphrase: auth.passphrase, expectedFingerprintSHA256: p.knownHostSHA256!, connectTimeoutMs: 10000 };
  }
}
