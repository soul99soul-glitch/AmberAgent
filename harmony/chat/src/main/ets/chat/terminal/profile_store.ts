import type { KeyValueStore } from '../kv_store.ts';
import { newId } from '../ids.ts';
import type { SSHCredentialStorePort } from './ports.ts';
import type { SSHCredential, SSHCredentialBinding, SSHProfile, SSHProfileDraft, SSHProfileSettings } from './models.ts';

export class TerminalError extends Error {
  constructor(public readonly code: string, message: string = code) { super(message); this.name = 'TerminalError'; }
}
export const normalizeDraft = (d: SSHProfileDraft): SSHProfileDraft => {
  if (!d.id.trim() || !d.host.trim() || !d.name.trim() || !d.username.trim() ||
    !Number.isInteger(d.port) || d.port < 1 || d.port > 65535 ||
    (d.authMethod !== 'password' && d.authMethod !== 'privateKey')) throw new TerminalError('invalid_arguments');
  return { id: d.id, name: d.name.trim(), host: d.host.trim(), port: d.port,
    username: d.username.trim(), authMethod: d.authMethod };
};
export const sameBinding = (a: SSHCredentialBinding, b: SSHCredentialBinding): boolean =>
  a.host === b.host && a.port === b.port && a.username === b.username && a.authMethod === b.authMethod;
export const draftDigest = (d: SSHProfileDraft): string =>
  JSON.stringify([d.id, d.name, d.host, d.port, d.username, d.authMethod]);
export const targetDigest = (p: SSHProfile): string => JSON.stringify([
  p.id, p.host, p.port, p.username, p.authMethod, p.knownHostSHA256,
  p.knownHostHost, p.knownHostPort, p.credentialRef,
]);
const copyProfile = (p: SSHProfile): SSHProfile => ({ id: p.id, name: p.name, host: p.host, port: p.port,
  username: p.username, authMethod: p.authMethod, revision: p.revision, knownHostSHA256: p.knownHostSHA256,
  knownHostHost: p.knownHostHost, knownHostPort: p.knownHostPort, credentialRef: p.credentialRef });
const copySettings = (s: SSHProfileSettings): SSHProfileSettings => ({
  profiles: s.profiles.map(copyProfile), defaultProfileId: s.defaultProfileId,
});
export interface SSHProfileStoreDeps { kv: KeyValueStore; credentials: SSHCredentialStorePort; }
const KEY: string = 'terminal_ssh_profiles';

export class SSHProfileStore {
  private settings: SSHProfileSettings = { profiles: [], defaultProfileId: null };
  private pending: Promise<void> = Promise.resolve();
  private listeners: Array<(settings: SSHProfileSettings) => void> = [];
  private constructor(private readonly deps: SSHProfileStoreDeps) {}
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
  snapshot(): SSHProfileSettings { return copySettings(this.settings); }
  get(id: string): SSHProfile | null {
    const p: SSHProfile | undefined = this.settings.profiles.find((v: SSHProfile): boolean => v.id === id);
    return p === undefined ? null : copyProfile(p);
  }
  defaultProfile(): SSHProfile | null {
    return this.settings.defaultProfileId === null ? null : this.get(this.settings.defaultProfileId);
  }
  subscribe(listener: (settings: SSHProfileSettings) => void): () => void {
    this.listeners.push(listener);
    listener(this.snapshot());
    return (): void => { this.listeners = this.listeners.filter((v): boolean => v !== listener); };
  }
  private serial<T>(operation: () => Promise<T>): Promise<T> {
    const run: Promise<T> = this.pending.then(operation);
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
      const hostMatches: boolean = old !== null && old.host === d.host && old.port === d.port;
      const bindingMatches: boolean = old !== null && sameBinding(old, d);
      const next: SSHProfile = { id: d.id, name: d.name, host: d.host, port: d.port, username: d.username,
        authMethod: d.authMethod, revision: (old?.revision ?? 0) + 1,
        knownHostSHA256: hostMatches ? old!.knownHostSHA256 : null,
        knownHostHost: hostMatches ? old!.knownHostHost : null,
        knownHostPort: hostMatches ? old!.knownHostPort : null,
        credentialRef: bindingMatches ? old!.credentialRef : null };
      await this.persist(this.nextSettings(next));
      if (old !== null && old.credentialRef !== next.credentialRef) await this.cleanup(old.credentialRef);
      return copyProfile(next);
    });
  }
  async commitVerified(draft: SSHProfileDraft, expectedRevision: number | null,
    fingerprintSHA256: string, credential: SSHCredential | null): Promise<SSHProfile> {
    const d: SSHProfileDraft = normalizeDraft(draft);
    if (!fingerprintSHA256.startsWith('SHA256:')) return Promise.reject(new TerminalError('invalid_arguments'));
    return this.serial(async (): Promise<SSHProfile> => {
      let stagedRef: string | null = null;
      try {
        if (credential !== null) {
          if (!credential.secret) throw new TerminalError('credential_missing');
          stagedRef = newId();
          await this.deps.credentials.save(stagedRef, d, credential);
        }
        const old: SSHProfile | null = this.checkRevision(d.id, expectedRevision);
        let ref: string | null = stagedRef;
        if (ref === null) {
          if (old === null || !sameBinding(old, d) || old.credentialRef === null ||
            !await this.deps.credentials.exists(old.credentialRef)) throw new TerminalError('credential_missing');
          ref = old.credentialRef;
        }
        const next: SSHProfile = { id: d.id, name: d.name, host: d.host, port: d.port, username: d.username,
          authMethod: d.authMethod, revision: (old?.revision ?? 0) + 1, knownHostSHA256: fingerprintSHA256,
          knownHostHost: d.host, knownHostPort: d.port, credentialRef: ref };
        await this.persist(this.nextSettings(next));
        stagedRef = null;
        if (old !== null && old.credentialRef !== ref) await this.cleanup(old.credentialRef);
        return copyProfile(next);
      } catch (error) { await this.cleanup(stagedRef); throw error; }
    });
  }
  deleteProfile(id: string, expectedRevision: number): Promise<void> {
    return this.serial(async (): Promise<void> => {
      const old: SSHProfile | null = this.checkRevision(id, expectedRevision);
      const next: SSHProfileSettings = this.snapshot();
      next.profiles = next.profiles.filter((p: SSHProfile): boolean => p.id !== id);
      if (next.defaultProfileId === id) next.defaultProfileId = null;
      await this.persist(next);
      await this.cleanup(old!.credentialRef);
    });
  }
}
