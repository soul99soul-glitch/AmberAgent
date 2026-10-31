import type { SSHKeyStore } from './key_store.ts';
import type { KeyValueStore } from '../kv_store.ts';
import type { JsonObject } from '../json.ts';
import { newId } from '../ids.ts';
import type { SSHCredentialStorePort } from './ports.ts';
import type { SSHCredential, SSHCredentialBinding, SSHProfile, SSHProfileDraft, SSHProfileSettings } from './models.ts';
import { dependentJumpProfileIds, resolveSSHRoute, validateProfileRouteCandidate } from './route_resolver.ts';

export class TerminalError extends Error {
  public readonly details: JsonObject | null;
  constructor(public readonly code: string, message: string = code, details: JsonObject | null = null) {
    super(message);
    this.name = 'TerminalError';
    this.details = details;
  }
}

const normalizeOptionalId = (value: string | null | undefined): string | null => {
  if (value === undefined || value === null) return null;
  const trimmed: string = value.trim();
  return trimmed.length > 0 ? trimmed : null;
};

export const normalizeDraft = (d: SSHProfileDraft): SSHProfileDraft => {
  if (!d.id.trim() || !d.host.trim() || !d.name.trim() || !d.username.trim() ||
    !Number.isInteger(d.port) || d.port < 1 || d.port > 65535 ||
    (d.authMethod !== 'password' && d.authMethod !== 'privateKey')) throw new TerminalError('invalid_arguments');
  const keyId: string | null = normalizeOptionalId(d.keyId);
  const jumpProfileId: string | null = normalizeOptionalId(d.jumpProfileId);
  if (keyId !== null && d.authMethod !== 'privateKey') {
    throw new TerminalError('invalid_arguments');
  }
  const normalized: SSHProfileDraft = {
    id: d.id,
    name: d.name.trim(),
    host: d.host.trim(),
    port: d.port,
    username: d.username.trim(),
    authMethod: d.authMethod,
  };
  if (keyId !== null) normalized.keyId = keyId;
  else if (d.keyId !== undefined) normalized.keyId = null;
  if (jumpProfileId !== null) normalized.jumpProfileId = jumpProfileId;
  else if (d.jumpProfileId !== undefined) normalized.jumpProfileId = null;
  return normalized;
};

export const sameBinding = (a: SSHCredentialBinding & { keyId?: string | null },
  b: SSHCredentialBinding & { keyId?: string | null }): boolean =>
  a.host === b.host && a.port === b.port && a.username === b.username &&
  a.authMethod === b.authMethod && (a.keyId ?? null) === (b.keyId ?? null);

export const sameHostAndRoute = (old: SSHProfile, d: SSHProfileDraft): boolean =>
  old.host === d.host &&
  old.port === d.port &&
  (old.jumpProfileId ?? null) === (d.jumpProfileId ?? null) &&
  (old.knownHostViaJump ?? null) === (d.jumpProfileId ?? null);

export const draftDigest = (d: SSHProfileDraft): string => {
  if ((d.jumpProfileId ?? null) === null && (d.keyId ?? null) === null) {
    return JSON.stringify([d.id, d.name, d.host, d.port, d.username, d.authMethod]);
  }
  return JSON.stringify([
    d.id, d.name, d.host, d.port, d.username, d.authMethod,
    d.keyId ?? null, d.jumpProfileId ?? null,
  ]);
};

export const targetDigest = (p: SSHProfile): string => {
  if ((p.jumpProfileId ?? null) === null && (p.keyId ?? null) === null) {
    return JSON.stringify([
      p.id, p.host, p.port, p.username, p.authMethod, p.knownHostSHA256,
      p.knownHostHost, p.knownHostPort, p.credentialRef, p.revision, p.name,
    ]);
  }
  return JSON.stringify([
    p.id, p.host, p.port, p.username, p.authMethod, p.knownHostSHA256,
    p.knownHostHost, p.knownHostPort, p.knownHostViaJump ?? null,
    p.credentialRef, p.keyId ?? null, p.jumpProfileId ?? null, p.revision, p.name,
  ]);
};

const copyProfile = (p: SSHProfile): SSHProfile => {
  const out: SSHProfile = {
    id: p.id,
    name: p.name,
    host: p.host,
    port: p.port,
    username: p.username,
    authMethod: p.authMethod,
    revision: p.revision,
    knownHostSHA256: p.knownHostSHA256,
    knownHostHost: p.knownHostHost,
    knownHostPort: p.knownHostPort,
    credentialRef: p.credentialRef,
  };
  if (p.keyId !== undefined && p.keyId !== null) out.keyId = p.keyId;
  else if (p.keyId === null) out.keyId = null;
  if (p.jumpProfileId !== undefined && p.jumpProfileId !== null) out.jumpProfileId = p.jumpProfileId;
  else if (p.jumpProfileId === null) out.jumpProfileId = null;
  if (p.knownHostViaJump !== undefined && p.knownHostViaJump !== null) out.knownHostViaJump = p.knownHostViaJump;
  else if (p.knownHostViaJump === null) out.knownHostViaJump = null;
  return out;
};

const buildProfileRecord = (
  d: SSHProfileDraft,
  revision: number,
  knownHostSHA256: string | null,
  knownHostHost: string | null,
  knownHostPort: number | null,
  knownHostViaJump: string | null | undefined,
  credentialRef: string | null,
): SSHProfile => {
  const record: SSHProfile = {
    id: d.id,
    name: d.name,
    host: d.host,
    port: d.port,
    username: d.username,
    authMethod: d.authMethod,
    revision,
    knownHostSHA256,
    knownHostHost,
    knownHostPort,
    credentialRef,
  };
  if (d.keyId !== undefined) record.keyId = d.keyId;
  if (d.jumpProfileId !== undefined) record.jumpProfileId = d.jumpProfileId;
  if (knownHostViaJump !== undefined) record.knownHostViaJump = knownHostViaJump;
  return record;
};

const copySettings = (s: SSHProfileSettings): SSHProfileSettings => ({
  profiles: s.profiles.map(copyProfile), defaultProfileId: s.defaultProfileId,
});
export interface SSHProfileStoreDeps { kv: KeyValueStore; credentials: SSHCredentialStorePort; keys?: SSHKeyStore; }
const KEY: string = 'terminal_ssh_profiles';

export class SSHProfileStore {
  private settings: SSHProfileSettings = { profiles: [], defaultProfileId: null };
  private pending: Promise<void> = Promise.resolve();
  private listeners: Array<(settings: SSHProfileSettings) => void> = [];
  private constructor(private readonly deps: SSHProfileStoreDeps) {
    deps.keys?.setProfileUsageResolver((id: string): string[] => this.profilesUsingKey(id));
  }
  static async create(deps: SSHProfileStoreDeps): Promise<SSHProfileStore> {
    const store: SSHProfileStore = new SSHProfileStore(deps);
    const raw: string | null = await deps.kv.get(KEY);
    if (raw !== null) {
      const parsed: SSHProfileSettings = JSON.parse(raw) as SSHProfileSettings;
      if (!Array.isArray(parsed.profiles)) throw new TerminalError('profile_data_invalid');
      for (const profile of parsed.profiles) normalizeDraft(profile);
      store.settings = copySettings(parsed);
    }
    return store;
  }
  keyStore(): SSHKeyStore | undefined { return this.deps.keys; }
  snapshot(): SSHProfileSettings { return copySettings(this.settings); }
  credentialExists(ref: string | null): Promise<boolean> {
    if (ref === null) return Promise.resolve(false);
    return this.deps.credentials.exists(ref).catch((): boolean => false);
  }
  get(id: string): SSHProfile | null {
    const p: SSHProfile | undefined = this.settings.profiles.find((v: SSHProfile): boolean => v.id === id);
    return p === undefined ? null : copyProfile(p);
  }
  defaultProfile(): SSHProfile | null {
    return this.settings.defaultProfileId === null ? null : this.get(this.settings.defaultProfileId);
  }
  profilesUsingKey(keyId: string): string[] {
    return this.settings.profiles
      .filter((p: SSHProfile): boolean => (p.keyId ?? null) === keyId)
      .map((p: SSHProfile): string => p.id);
  }
  subscribe(listener: (settings: SSHProfileSettings) => void): () => void {
    this.listeners.push(listener);
    listener(this.snapshot());
    return (): void => { this.listeners = this.listeners.filter((v): boolean => v !== listener); };
  }
  private serial<T>(operation: () => Promise<T>): Promise<T> {
    const run: Promise<T> = this.pending.then((): Promise<T> => this.deps.keys === undefined
      ? operation() : this.deps.keys.withProfileWrite(operation));
    this.pending = run.then((): void => undefined, (): void => undefined);
    return run;
  }
  private checkRevision(id: string, revision: number | null): SSHProfile | null {
    const old: SSHProfile | null = this.get(id);
    if ((old === null && revision !== null) || (old !== null && old.revision !== revision)) {
      throw new TerminalError('profile_conflict');
    }
    return old;
  }
  private async persist(next: SSHProfileSettings): Promise<void> {
    await this.deps.kv.put(KEY, JSON.stringify(next));
    this.settings = next;
    for (const listener of this.listeners.slice()) {
      try { listener(this.snapshot()); } catch { /* observers cannot undo committed metadata */ }
    }
  }
  private async cleanup(ref: string | null): Promise<void> {
    if (ref === null) return;
    try { await this.deps.credentials.delete(ref); }
    catch { console.error('terminal credential cleanup failed'); }
  }
  private nextSettings(profile: SSHProfile): SSHProfileSettings {
    const next: SSHProfileSettings = this.snapshot();
    next.profiles = next.profiles.filter((p: SSHProfile): boolean => p.id !== profile.id);
    next.profiles.push(profile);
    return next;
  }
  setDefault(id: string | null): Promise<void> {
    return this.serial(async (): Promise<void> => {
      if (id !== null && this.get(id) === null) throw new TerminalError('profile_missing');
      const next: SSHProfileSettings = this.snapshot();
      next.defaultProfileId = id;
      await this.persist(next);
    });
  }
  async saveMetadata(draft: SSHProfileDraft, expectedRevision: number | null): Promise<SSHProfile> {
    const d: SSHProfileDraft = normalizeDraft(draft);
    return this.serial(async (): Promise<SSHProfile> => {
      const old: SSHProfile | null = this.checkRevision(d.id, expectedRevision);
      const mergedDraft: SSHProfileDraft = {
        ...d,
        keyId: d.keyId !== undefined ? d.keyId : old?.keyId,
        jumpProfileId: d.jumpProfileId !== undefined ? d.jumpProfileId : old?.jumpProfileId,
      };
      const hostMatches: boolean = old !== null && sameHostAndRoute(old, mergedDraft);
      const bindingMatches: boolean = old !== null && sameBinding(old, mergedDraft);
      const viaJump: string | null | undefined = (mergedDraft.jumpProfileId !== undefined || old?.knownHostViaJump !== undefined)
        ? (hostMatches ? (old?.knownHostViaJump ?? null) : null)
        : undefined;
      const next: SSHProfile = buildProfileRecord(
        mergedDraft,
        (old?.revision ?? 0) + 1,
        hostMatches ? old!.knownHostSHA256 : null,
        hostMatches ? old!.knownHostHost : null,
        hostMatches ? old!.knownHostPort : null,
        viaJump,
        bindingMatches ? old!.credentialRef : null,
      );
      if (next.keyId && (this.deps.keys === undefined || this.deps.keys.get(next.keyId) === null)) throw new TerminalError('key_missing');
      validateProfileRouteCandidate(next, this.settings.profiles);
      await this.persist(this.nextSettings(next));
      if (old !== null && old.credentialRef !== next.credentialRef) await this.cleanup(old.credentialRef);
      return copyProfile(next);
    });
  }
  async commitVerified(draft: SSHProfileDraft, expectedRevision: number | null,
    fingerprintSHA256: string, credential: SSHCredential | null, expectedRouteDigest?: string): Promise<SSHProfile> {
    const d: SSHProfileDraft = normalizeDraft(draft);
    if (!fingerprintSHA256.startsWith('SHA256:')) return Promise.reject(new TerminalError('invalid_arguments'));
    return this.serial(async (): Promise<SSHProfile> => {
      let stagedRef: string | null = null;
      try {
        const old: SSHProfile | null = this.checkRevision(d.id, expectedRevision);
        if (expectedRouteDigest !== undefined) {
          // Recheck inside the serialized write: a jump edit may have queued after the probe.
          const route: SSHProfile[] = resolveSSHRoute(d.id, this.settings.profiles);
          const digest: string = route.length === 1 ? targetDigest(route[0]) : JSON.stringify(route.map(targetDigest));
          if (digest !== expectedRouteDigest) throw new TerminalError('profile_conflict');
        }
        const mergedDraft: SSHProfileDraft = {
          ...d,
          keyId: d.keyId !== undefined ? d.keyId : old?.keyId,
          jumpProfileId: d.jumpProfileId !== undefined ? d.jumpProfileId : old?.jumpProfileId,
        };
        const usesKeyId: boolean = mergedDraft.authMethod === 'privateKey' &&
          mergedDraft.keyId !== undefined && mergedDraft.keyId !== null;
        if (usesKeyId && credential !== null) throw new TerminalError('invalid_arguments');
        if (credential !== null) {
          if (!credential.secret) throw new TerminalError('credential_missing');
          stagedRef = newId();
          await this.deps.credentials.save(stagedRef, mergedDraft, credential);
        }
        let ref: string | null = stagedRef;
        if (ref === null && !usesKeyId) {
          if (old === null || !sameBinding(old, mergedDraft) || old.credentialRef === null ||
            !await this.deps.credentials.exists(old.credentialRef)) throw new TerminalError('credential_missing');
          ref = old.credentialRef;
        }
        const viaJump: string | null | undefined = (mergedDraft.jumpProfileId !== undefined || old?.knownHostViaJump !== undefined)
          ? (mergedDraft.jumpProfileId ?? null)
          : undefined;
        const next: SSHProfile = buildProfileRecord(
          mergedDraft,
          (old?.revision ?? 0) + 1,
          fingerprintSHA256,
          mergedDraft.host,
          mergedDraft.port,
          viaJump,
          usesKeyId ? null : ref,
        );
        if (next.keyId && (this.deps.keys === undefined || this.deps.keys.get(next.keyId) === null)) throw new TerminalError('key_missing');
      validateProfileRouteCandidate(next, this.settings.profiles);
        await this.persist(this.nextSettings(next));
        stagedRef = null;
        if (old !== null && old.credentialRef !== next.credentialRef) await this.cleanup(old.credentialRef);
        return copyProfile(next);
      } catch (error) { await this.cleanup(stagedRef); throw error; }
    });
  }
  async saveWithCredential(draft: SSHProfileDraft, expectedRevision: number | null,
    credential: SSHCredential | null, clearCredential: boolean = false): Promise<SSHProfile> {
    const d: SSHProfileDraft = normalizeDraft(draft);
    if (credential !== null && clearCredential) return Promise.reject(new TerminalError('invalid_arguments'));
    if (credential !== null && !credential.secret) return Promise.reject(new TerminalError('credential_missing'));
    return this.serial(async (): Promise<SSHProfile> => {
      let stagedRef: string | null = null;
      try {
        const old: SSHProfile | null = this.checkRevision(d.id, expectedRevision);
        const mergedDraft: SSHProfileDraft = {
          ...d,
          keyId: d.keyId !== undefined ? d.keyId : (clearCredential ? null : old?.keyId),
          jumpProfileId: d.jumpProfileId !== undefined ? d.jumpProfileId : old?.jumpProfileId,
        };
        const usesKeyId: boolean = !clearCredential && mergedDraft.authMethod === 'privateKey' &&
          mergedDraft.keyId !== undefined && mergedDraft.keyId !== null;
        if (usesKeyId && credential !== null) throw new TerminalError('invalid_arguments');
        if (credential !== null) {
          if (credential.secret.indexOf('\0') >= 0) throw new TerminalError('invalid_arguments');
          stagedRef = newId();
          await this.deps.credentials.save(stagedRef, mergedDraft, credential);
        }
        const sameTarget: boolean = old !== null && sameHostAndRoute(old, mergedDraft);
        let ref: string | null = stagedRef;
        if (ref === null && !clearCredential && !usesKeyId) {
          if (old === null || !sameBinding(old, mergedDraft) || old.credentialRef === null ||
            !await this.deps.credentials.exists(old.credentialRef)) throw new TerminalError('credential_missing');
          ref = old.credentialRef;
        }
        const viaJump: string | null | undefined = (mergedDraft.jumpProfileId !== undefined || old?.knownHostViaJump !== undefined)
          ? (sameTarget ? (old?.knownHostViaJump ?? null) : null)
          : undefined;
        const next: SSHProfile = buildProfileRecord(
          mergedDraft,
          (old?.revision ?? 0) + 1,
          sameTarget ? old!.knownHostSHA256 : null,
          sameTarget ? old!.knownHostHost : null,
          sameTarget ? old!.knownHostPort : null,
          viaJump,
          usesKeyId ? null : ref,
        );
        if (next.keyId && (this.deps.keys === undefined || this.deps.keys.get(next.keyId) === null)) throw new TerminalError('key_missing');
      validateProfileRouteCandidate(next, this.settings.profiles);
        await this.persist(this.nextSettings(next));
        stagedRef = null;
        if (old !== null && old.credentialRef !== next.credentialRef) await this.cleanup(old.credentialRef);
        return copyProfile(next);
      } catch (error) { await this.cleanup(stagedRef); throw error; }
    });
  }
  deleteProfile(id: string, expectedRevision: number): Promise<void> {
    return this.serial(async (): Promise<void> => {
      const old: SSHProfile | null = this.checkRevision(id, expectedRevision);
      const dependentIds: string[] = dependentJumpProfileIds(id, this.settings.profiles);
      if (dependentIds.length > 0) {
        throw new TerminalError(
          'in_use_as_jump',
          `SSH profile '${old!.name}' (${id}) is used as a jump host by other profiles and cannot be deleted.`,
          { dependent_profile_ids: dependentIds },
        );
      }
      const next: SSHProfileSettings = this.snapshot();
      next.profiles = next.profiles.filter((p: SSHProfile): boolean => p.id !== id);
      if (next.defaultProfileId === id) next.defaultProfileId = null;
      await this.persist(next);
      await this.cleanup(old!.credentialRef);
    });
  }
}
