import type { AbortSignalLike } from '@amber/deepread-domain';
import type {
  SSHConnectionOptions, SSHCredential, SSHHopConnectionOptions, SSHProfile, SSHTargetSnapshot,
} from './models.ts';
import type { SSHCredentialStorePort } from './ports.ts';
import type { SSHKeyStore } from './key_store.ts';
import { SSHProfileStore, TerminalError, targetDigest } from './profile_store.ts';
import { isProfileHostTrusted, resolveSSHRoute } from './route_resolver.ts';
import { checkAbort } from './control.ts';

export interface SSHTargetResolverDeps {
  profiles: SSHProfileStore;
  credentials: SSHCredentialStorePort;
  keys?: SSHKeyStore;
}

// One trust/credential boundary for ordinary SSH (single-hop and ProxyJump) and the low-level Mosh bootstrap.
export class SSHTargetResolver {
  constructor(private readonly deps: SSHTargetResolverDeps) {}

  resolveRoute(profileId: string): SSHProfile[] {
    return resolveSSHRoute(profileId, this.deps.profiles.snapshot().profiles);
  }

  private computeRouteDigest(route: SSHProfile[]): string {
    if (route.length === 1) {
      return targetDigest(route[0]);
    }
    return JSON.stringify(route.map(targetDigest));
  }

  captureTarget(profileId: string | null): SSHTargetSnapshot {
    const profile: SSHProfile | null = profileId === null
      ? this.deps.profiles.defaultProfile()
      : this.deps.profiles.get(profileId);
    if (profile === null) throw new TerminalError('profile_missing');
    const route: SSHProfile[] = this.resolveRoute(profile.id);
    return {
      profileId: profile.id,
      digest: this.computeRouteDigest(route),
      usesDefault: profileId === null,
    };
  }

  targetRoute(target: SSHTargetSnapshot): SSHProfile[] {
    const current: SSHProfile | null = this.deps.profiles.get(target.profileId);
    if (current === null || (target.usesDefault && this.deps.profiles.snapshot().defaultProfileId !== target.profileId)) {
      throw new TerminalError('target_changed');
    }
    let route: SSHProfile[];
    try {
      route = this.resolveRoute(target.profileId);
    } catch {
      throw new TerminalError('target_changed');
    }
    if (this.computeRouteDigest(route) !== target.digest) {
      throw new TerminalError('target_changed');
    }
    for (let i = 0; i < route.length; i++) {
      const hop: SSHProfile = route[i];
      if (!isProfileHostTrusted(hop)) {
        throw new TerminalError(
          'host_key_untrusted',
          'Remote SSH: host_key_untrusted (host_trust_required)',
          {
            hop_index: i + 1,
            hop_count: route.length,
            profile_id: hop.id,
            endpoint: `${hop.username}@${hop.host}:${hop.port}`,
          },
        );
      }
    }
    return route;
  }

  targetProfile(target: SSHTargetSnapshot): SSHProfile {
    const route: SSHProfile[] = this.targetRoute(target);
    return route[route.length - 1];
  }

  async loadHopCredential(p: SSHProfile): Promise<SSHCredential> {
    if (p.authMethod === 'privateKey' && p.keyId !== undefined && p.keyId !== null) {
      if (this.deps.keys === undefined) throw new TerminalError('credential_missing');
      const keyAuth: SSHCredential | null = await this.deps.keys.loadCredential(p.keyId);
      if (keyAuth === null || !keyAuth.secret) throw new TerminalError('credential_missing');
      return keyAuth;
    }
    if (p.credentialRef === null) throw new TerminalError('credential_missing');
    const auth: SSHCredential | null = await this.deps.credentials.load(p.credentialRef, p);
    if (auth === null || !auth.secret) throw new TerminalError('credential_missing');
    return auth;
  }

  async prepareJumpHopsForDraft(
    jumpProfileId: string | null | undefined,
    signal?: AbortSignalLike,
  ): Promise<SSHHopConnectionOptions[]> {
    if (jumpProfileId === undefined || jumpProfileId === null || jumpProfileId.trim().length === 0) {
      return [];
    }
    const jumpChain: SSHProfile[] = this.resolveRoute(jumpProfileId.trim());
    if (jumpChain.length > 3) {
      throw new TerminalError('jump_chain_too_long');
    }
    const hops: SSHHopConnectionOptions[] = [];
    for (let i = 0; i < jumpChain.length; i++) {
      checkAbort(signal);
      const hop: SSHProfile = jumpChain[i];
      if (!isProfileHostTrusted(hop)) {
        throw new TerminalError(
          'host_key_untrusted',
          'Remote SSH: host_key_untrusted (host_trust_required)',
          {
            hop_index: i + 1,
            hop_count: jumpChain.length + 1,
            profile_id: hop.id,
            endpoint: `${hop.username}@${hop.host}:${hop.port}`,
          },
        );
      }
      const cred: SSHCredential = await this.loadHopCredential(hop);
      hops.push({
        profileId: hop.id,
        host: hop.host,
        port: hop.port,
        username: hop.username,
        expectedFingerprintSHA256: hop.knownHostSHA256!,
        authMethod: hop.authMethod,
        secret: cred.secret,
        passphrase: cred.passphrase,
      });
    }
    return hops;
  }

  async prepare(target: SSHTargetSnapshot, signal?: AbortSignalLike): Promise<SSHConnectionOptions> {
    checkAbort(signal);
    const route: SSHProfile[] = this.targetRoute(target);
    const jumpHops: SSHHopConnectionOptions[] = [];
    for (let i = 0; i < route.length - 1; i++) {
      const hop: SSHProfile = route[i];
      const hopAuth: SSHCredential = await this.loadHopCredential(hop);
      checkAbort(signal);
      this.targetRoute(target);
      jumpHops.push({
        profileId: hop.id,
        host: hop.host,
        port: hop.port,
        username: hop.username,
        expectedFingerprintSHA256: hop.knownHostSHA256!,
        authMethod: hop.authMethod,
        secret: hopAuth.secret,
        passphrase: hopAuth.passphrase,
      });
    }
    const p: SSHProfile = route[route.length - 1];
    const auth: SSHCredential = await this.loadHopCredential(p);
    checkAbort(signal);
    this.targetRoute(target);
    const options: SSHConnectionOptions = {
      host: p.host,
      port: p.port,
      username: p.username,
      authMethod: p.authMethod,
      secret: auth.secret,
      passphrase: auth.passphrase,
      expectedFingerprintSHA256: p.knownHostSHA256!,
      connectTimeoutMs: 10000,
    };
    if (jumpHops.length > 0) {
      options.profileId = p.id;
      options.jumpHops = jumpHops;
    }
    return options;
  }
}
