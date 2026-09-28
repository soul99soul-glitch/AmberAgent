import { common } from '@kit.AbilityKit';
import { fileIo, ListFileOptions } from '@kit.CoreFileKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { util } from '@kit.ArkTS';
import { normalizeWorkspacePath, PluginValidationError, preparePluginPackage } from '@amber/chat-domain';
import type { AgentTool, InstalledPlugin, PluginArchivePort, PluginFailureKind, PluginFile, PluginHealth,
  PluginImportPreview, PluginPackage, PluginReadResult, PluginReceipt, PluginSource, PluginStore,
  PluginTrust, PluginTrustedKey } from '@amber/chat-domain';
import { sharedKvMutex, writeTextFileAtomic } from './AtomicJsonFile';
import { workspaceRootAbs } from './WorkspaceStore';
import { createEntryPluginArchive, isPluginMemberPath, pluginHashPort, pluginPublicKeyFingerprint,
  pluginText, PluginArchiveError, verifyPluginSignature } from './PluginArchive';

export class PluginStoreError extends Error {
  public readonly code: string;
  constructor(code: string) { super(code); this.code = code; }
}
interface PreviousPlugin { hash: string; enabled: boolean; trust: PluginTrust; }
interface PluginPointer extends PreviousPlugin { previous: PreviousPlugin | null; }

const requireId = (id: string): void => {
  const match: RegExpMatchArray | null = id.match(/^[a-z][a-z0-9_]{1,31}$/);
  if (match === null || match[0] !== id || id.includes('__')) throw new PluginStoreError('invalid_plugin_id');
};
const validHash = (hash: string): boolean => /^[0-9a-f]{64}$/.test(hash) && hash.length === 64;
const unsignedTrust = (): PluginTrust => ({ tier: 'local_unsigned', publisherTrusted: false,
  keyId: null, fingerprint: null, signature: null });
const trustStamp = (trust: PluginTrust): string => JSON.stringify([trust.tier, trust.publisherTrusted,
  trust.keyId, trust.fingerprint, trust.signature?.algorithm ?? null, trust.signature?.keyId ?? null,
  trust.signature?.publicKeyBase64 ?? null, trust.signature?.signatureBase64 ?? null]);
const healthy = (id: string, hash: string): PluginHealth => ({ pluginId: id, packageHash: hash,
  consecutiveFailures: 0, quarantinedAt: null, quarantineReason: null, diagnostics: [] });
const statOrMissing = (path: string): fileIo.Stat | null => {
  try { return fileIo.lstatSync(path); }
  catch (error) {
    // Official File IO: only 13900002 denotes missing; permission / IO errors remain errors.
    if ((error as BusinessError).code === 13900002) return null;
    throw error as Error;
  }
};
const requireDirectory = (path: string): void => {
  const stat: fileIo.Stat = fileIo.lstatSync(path);
  if (stat.isSymbolicLink() || !stat.isDirectory()) throw new PluginStoreError('invalid_path');
};
const readBytes = (path: string, limit: number): Uint8Array => {
  const stat: fileIo.Stat = fileIo.lstatSync(path);
  if (stat.isSymbolicLink() || !stat.isFile()) throw new PluginStoreError('invalid_path');
  if (stat.size > limit) throw new PluginStoreError('file_budget_exceeded');
  const file: fileIo.File = fileIo.openSync(path, fileIo.OpenMode.READ_ONLY);
  try {
    const buffer: ArrayBuffer = new ArrayBuffer(limit + 1);
    let count: number = 0;
    while (count < buffer.byteLength) {
      const chunk: ArrayBuffer = new ArrayBuffer(buffer.byteLength - count);
      const read: number = fileIo.readSync(file.fd, chunk);
      if (read === 0) break;
      if (read < 0 || read > chunk.byteLength) throw new PluginStoreError('file_read_failed');
      new Uint8Array(buffer).set(new Uint8Array(chunk, 0, read), count); count += read;
    }
    if (count > limit) throw new PluginStoreError('file_budget_exceeded');
    return new Uint8Array(buffer.slice(0, count));
  } finally { fileIo.closeSync(file); }
};
const walkPackage = (root: string): PluginFile[] => {
  requireDirectory(root);
  const files: PluginFile[] = [];
  let total: number = 0; let visited: number = 0;
  const visit = (directory: string, prefix: string, depth: number): void => {
    if (depth > 32) throw new PluginStoreError('file_budget_exceeded');
    const options: ListFileOptions = { recursion: false, listNum: 257 - visited };
    for (const name of fileIo.listFileSync(directory, options).sort()) {
      visited++;
      if (visited > 256 || name.includes('/') || name.includes('\\') || name.startsWith('.')) {
        throw new PluginStoreError('file_budget_exceeded');
      }
      const relative: string = prefix.length === 0 ? name : `${prefix}/${name}`;
      const path: string = `${directory}/${name}`;
      const stat: fileIo.Stat = fileIo.lstatSync(path);
      if (stat.isSymbolicLink()) throw new PluginStoreError('invalid_path');
      if (stat.isDirectory()) { visit(path, relative, depth + 1); continue; }
      if (!stat.isFile() || !isPluginMemberPath(relative)) throw new PluginStoreError('invalid_path');
      if (files.length === 32) throw new PluginStoreError('file_budget_exceeded');
      const bytes: Uint8Array = readBytes(path, Math.min(256 * 1024, 1024 * 1024 - total));
      total += bytes.byteLength; files.push({ path: relative, data: Array.from(bytes) });
    }
  };
  visit(root, '', 0); return files;
};
const writePackage = (root: string, candidate: PluginPackage): void => {
  fileIo.mkdirSync(root, true);
  for (const member of candidate.files) {
    if (!isPluginMemberPath(member.path)) throw new PluginStoreError('invalid_path');
    const path: string = `${root}/${member.path}`;
    fileIo.mkdirSync(path.slice(0, path.lastIndexOf('/')), true);
    const file: fileIo.File = fileIo.openSync(path,
      fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
    try {
      const bytes: Uint8Array = new Uint8Array(member.data); let consumed: number = 0;
      while (consumed < bytes.byteLength) {
        const chunk: ArrayBuffer = (bytes.buffer as ArrayBuffer).slice(consumed);
        const written: number = fileIo.writeSync(file.fd, chunk);
        if (written <= 0 || written > chunk.byteLength) throw new PluginStoreError('file_write_failed');
        consumed += written;
      }
    } finally { fileIo.closeSync(file); }
  }
};
const permissionDiff = (base: PluginPackage | null, candidate: PluginPackage,
  oldTrust: PluginTrust | null, trust: PluginTrust): string[] => {
  const differences: string[] = [];
  const added = (name: string, oldValues: string[], newValues: string[]): void => {
    for (const value of newValues) if (!oldValues.includes(value)) differences.push(`${name}:${value}`);
  };
  added('tools', base?.envelope.tools ?? [], candidate.envelope.tools);
  const old = base?.manifest.capabilities; const next = candidate.manifest.capabilities;
  added('workspaceReadPrefixes', old?.workspaceReadPrefixes ?? [], next.workspaceReadPrefixes);
  added('workspaceWritePrefixes', old?.workspaceWritePrefixes ?? [], next.workspaceWritePrefixes);
  added('networkDomains', old?.networkDomains ?? [], next.networkDomains);
  added('webMountActions', old?.webMountActions ?? [], next.webMountActions);
  added('localRuntimes', old?.localRuntimes ?? [], next.localRuntimes);
  if (oldTrust !== null && ((oldTrust.tier === 'signed' && trust.tier !== 'signed') ||
    (oldTrust.tier === 'signed' && oldTrust.keyId !== trust.keyId))) differences.push('publisher_identity_changed');
  return differences.sort();
};

class EntryPluginStore implements PluginStore {
  private readonly root: string;
  private readonly primitives: () => Promise<AgentTool[]>;
  private readonly archive: PluginArchivePort = createEntryPluginArchive();
  private readonly healthCache: Map<string, PluginHealth> = new Map<string, PluginHealth>();
  constructor(root: string, primitives: () => Promise<AgentTool[]>) { this.root = root; this.primitives = primitives; }
  private directory(id: string): string { requireId(id); return `${this.root}/${id}`; }
  private packagePath(id: string, hash: string): string {
    if (!validHash(hash)) throw new PluginStoreError('plugin_store_corrupt');
    return `${this.directory(id)}/packages/${hash}`;
  }
  private pointer(id: string): PluginPointer | null {
    const directory: string = this.directory(id);
    if (statOrMissing(this.root) === null) return null;
    requireDirectory(this.root);
    const dirStat: fileIo.Stat | null = statOrMissing(directory);
    if (dirStat === null) return null;
    requireDirectory(directory);
    const path: string = `${directory}/current.json`;
    if (statOrMissing(path) === null) return null;
    try {
      const pointer: PluginPointer = JSON.parse(pluginText(readBytes(path, 16 * 1024))) as PluginPointer;
      if (pointer === null || !validHash(pointer.hash) || (pointer.enabled !== true && pointer.enabled !== false) ||
        pointer.trust === null || pointer.trust === undefined ||
        (pointer.previous !== null && (!validHash(pointer.previous.hash) ||
          (pointer.previous.enabled !== true && pointer.previous.enabled !== false)))) throw new PluginStoreError('plugin_store_corrupt');
      return pointer;
    } catch (error) {
      if (error instanceof SyntaxError || error instanceof TypeError) throw new PluginStoreError('plugin_store_corrupt');
      throw error as Error;
    }
  }
  private async trustFor(trust: PluginTrust, hash: string): Promise<PluginTrust> {
    if (trust === null || trust === undefined) throw new PluginStoreError('plugin_store_corrupt');
    if (trust.tier === 'local_unsigned' && trust.keyId === null && trust.fingerprint === null && trust.signature === null) {
      return unsignedTrust();
    }
    if (trust.tier !== 'signed' || trust.signature === null || trust.signature === undefined) {
      throw new PluginArchiveError('signature_invalid');
    }
    const fingerprint: string = await verifyPluginSignature(trust.signature, hash);
    if (trust.keyId !== fingerprint.slice(0, 16) || trust.fingerprint !== fingerprint) {
      throw new PluginArchiveError('signature_invalid');
    }
    const keys: PluginTrustedKey[] = await this.listTrustedKeys();
    return { tier: 'signed', keyId: trust.keyId, fingerprint, signature: trust.signature,
      publisherTrusted: keys.some((key: PluginTrustedKey): boolean => key.fingerprint === fingerprint) };
  }
  private async packageFor(id: string, pointer: PreviousPlugin, primitives: AgentTool[]): Promise<PluginReadResult> {
    const path: string = this.packagePath(id, pointer.hash);
    try { requireDirectory(`${this.directory(id)}/packages`); requireDirectory(path); }
    catch (error) {
      if ((error as BusinessError).code === 13900002) throw new PluginStoreError('missing_package');
      throw error as Error;
    }
    const candidate: PluginPackage = await preparePluginPackage(walkPackage(path),
      primitives, pluginHashPort);
    if (candidate.manifest.id !== id || candidate.hash !== pointer.hash) throw new PluginStoreError('package_hash_mismatch');
    return { candidate, trust: await this.trustFor(pointer.trust, candidate.hash) };
  }
  private health(id: string, hash: string): PluginHealth {
    const key: string = `${id}/${hash}`;
    const cached: PluginHealth | undefined = this.healthCache.get(key);
    if (cached !== undefined) return cached;
    const path: string = `${this.directory(id)}/health-${hash}.json`;
    if (statOrMissing(path) === null) return healthy(id, hash);
    try {
      const value: PluginHealth = JSON.parse(pluginText(readBytes(path, 256 * 1024))) as PluginHealth;
      if (value.pluginId !== id || value.packageHash !== hash || !Number.isSafeInteger(value.consecutiveFailures) ||
        value.consecutiveFailures < 0 || !Array.isArray(value.diagnostics) || value.diagnostics.length > 50 ||
        (value.quarantinedAt !== null && !Number.isFinite(value.quarantinedAt))) throw new PluginStoreError('plugin_store_corrupt');
      return value;
    } catch (error) {
      if (error instanceof SyntaxError || error instanceof TypeError) throw new PluginStoreError('plugin_store_corrupt');
      throw error as Error;
    }
  }
  private async saveHealth(value: PluginHealth): Promise<PluginHealth> {
    this.healthCache.set(`${value.pluginId}/${value.packageHash}`, value);
    await writeTextFileAtomic(`${this.directory(value.pluginId)}/health-${value.packageHash}.json`, JSON.stringify(value));
    return value;
  }
  private async writePointer(id: string, pointer: PluginPointer): Promise<void> {
    await writeTextFileAtomic(`${this.directory(id)}/current.json`, JSON.stringify(pointer));
  }
  private requireBase(id: string, hash: string): PluginPointer {
    const pointer: PluginPointer | null = this.pointer(id);
    if (pointer === null || pointer.hash !== hash) throw new PluginStoreError('stale_base');
    return pointer;
  }
  async listInstalled(oracle?: AgentTool[]): Promise<InstalledPlugin[]> {
    if (statOrMissing(this.root) === null) return [];
    requireDirectory(this.root);
    const installed: InstalledPlugin[] = []; const primitives: AgentTool[] = oracle ?? await this.primitives();
    for (const id of fileIo.listFileSync(this.root).sort()) {
      if (id.startsWith('.')) continue;
      requireId(id);
      let pointer: PluginPointer | null = null;
      try {
        pointer = this.pointer(id); if (pointer === null) continue;
        const read: PluginReadResult = await this.packageFor(id, pointer, primitives);
        const health: PluginHealth = this.health(id, pointer.hash);
        installed.push({ id, currentHash: pointer.hash, package: read.candidate, configuredEnabled: pointer.enabled,
          enabled: pointer.enabled && health.quarantinedAt === null, trust: read.trust, health,
          errorCode: null, errorMessage: null });
      } catch (error) {
        if (!(error instanceof PluginStoreError) && !(error instanceof PluginArchiveError) && !(error instanceof PluginValidationError)) {
          throw error as Error;
        }
        installed.push({ id, currentHash: pointer?.hash ?? null, package: null, configuredEnabled: pointer?.enabled ?? false, enabled: false,
          trust: unsignedTrust(), health: null, errorCode: error.code, errorMessage: error.message });
      }
    }
    return installed;
  }
  async readPackage(source: PluginSource, primitives: AgentTool[]): Promise<PluginReadResult> {
    if (source.kind !== 'archive' && source.kind !== 'directory') throw new PluginStoreError('invalid_source');
    const relative: string = normalizeWorkspacePath(source.workspacePath);
    const root: string = workspaceRootAbs(); requireDirectory(root);
    let path: string = root;
    const segments: string[] = relative === '.' ? [] : relative.split('/');
    for (let index: number = 0; index < segments.length; index++) {
      path += `/${segments[index]}`;
      if (index < segments.length - 1) requireDirectory(path);
    }
    const read: PluginReadResult = source.kind === 'archive'
      ? await this.archive.decode(readBytes(path, 2 * 1024 * 1024), primitives)
      : { candidate: await preparePluginPackage(walkPackage(path), primitives, pluginHashPort), trust: unsignedTrust() };
    read.trust = await this.trustFor(read.trust, read.candidate.hash); return read;
  }
  private prunePackages(id: string, pointer: PluginPointer): void {
    // Only the current and one rollback slot remain reachable; cleanup never changes the published pointer.
    try {
      const directory: string = `${this.directory(id)}/packages`;
      for (const hash of fileIo.listFileSync(directory)) {
        if (validHash(hash) && hash !== pointer.hash && hash !== pointer.previous?.hash) {
          requireDirectory(`${directory}/${hash}`); fileIo.rmdirSync(`${directory}/${hash}`);
        }
      }
    } catch { /* Retain an unreachable hash directory if cleanup fails. */ }
  }
  async prepareImport(source: PluginSource, primitives: AgentTool[], enable: boolean): Promise<PluginImportPreview> {
    const pinned: PluginSource = { kind: source.kind, workspacePath: normalizeWorkspacePath(source.workspacePath) };
    const read: PluginReadResult = await this.readPackage(pinned, primitives);
    return sharedKvMutex.withLock(this.directory(read.candidate.manifest.id), async (): Promise<PluginImportPreview> => {
      const base: PluginPointer | null = this.pointer(read.candidate.manifest.id);
      const old: PluginReadResult | null = base === null ? null : await this.packageFor(read.candidate.manifest.id, base, primitives);
      const diff: string[] = permissionDiff(old?.candidate ?? null, read.candidate, old?.trust ?? null, read.trust);
      return { source: pinned, candidate: read.candidate, trust: read.trust, baseHash: base?.hash ?? null,
        permissionDiff: diff, permissionExpanded: base === null || diff.length > 0, enable };
    });
  }
  applyImport(preview: PluginImportPreview, primitives: AgentTool[]): Promise<PluginReceipt> {
    const id: string = preview.candidate.manifest.id; const hash: string = preview.candidate.hash;
    const baseHash: string | null = preview.baseHash; const enable: boolean = preview.enable;
    const source: PluginSource = { kind: preview.source.kind, workspacePath: preview.source.workspacePath };
    const expectedTrust: string = trustStamp(preview.trust); const expectedDiff: string = JSON.stringify(preview.permissionDiff);
    return sharedKvMutex.withLock(this.directory(id), async (): Promise<PluginReceipt> => {
      const read: PluginReadResult = await this.readPackage(source, primitives);
      if (read.candidate.hash !== hash || read.candidate.manifest.id !== id || trustStamp(read.trust) !== expectedTrust) {
        throw new PluginStoreError('stale_candidate');
      }
      const base: PluginPointer | null = this.pointer(id);
      if ((base?.hash ?? null) !== baseHash) throw new PluginStoreError('stale_base');
      const old: PluginReadResult | null = base === null ? null : await this.packageFor(id, base, primitives);
      const diff: string[] = permissionDiff(old?.candidate ?? null, read.candidate, old?.trust ?? null, read.trust);
      if (JSON.stringify(diff) !== expectedDiff) throw new PluginStoreError('stale_candidate');
      const destination: string = this.packagePath(id, hash);
      if (statOrMissing(destination) === null) {
        const stage: string = `${this.directory(id)}/.stage-${util.generateRandomUUID()}`;
        try {
          const packages: string = `${this.directory(id)}/packages`;
          if (statOrMissing(packages) === null) fileIo.mkdirSync(packages, true);
          requireDirectory(packages); writePackage(stage, read.candidate); fileIo.renameSync(stage, destination);
        }
        catch (error) {
          try { if (statOrMissing(stage) !== null) fileIo.rmdirSync(stage); } catch { /* Preserve the actual stage failure. */ }
          throw error as Error;
        }
      } else await this.packageFor(id, { hash, enabled: false, trust: read.trust }, primitives);
      const pointer: PluginPointer = { hash, enabled: enable, trust: read.trust,
        previous: base === null || base.hash === hash ? base?.previous ?? null
          : { hash: base.hash, enabled: base.enabled, trust: base.trust } };
      const changed: boolean = base === null || JSON.stringify(base) !== JSON.stringify(pointer);
      if (changed) { await this.writePointer(id, pointer); this.prunePackages(id, pointer); }
      return { id, hash, changed, enabled: enable && this.health(id, hash).quarantinedAt === null,
        permissionExpanded: base === null || diff.length > 0, trust: read.trust };
    });
  }
  setEnabled(id: string, expectedHash: string, enabled: boolean): Promise<void> {
    return sharedKvMutex.withLock(this.directory(id), async (): Promise<void> => {
      const pointer: PluginPointer = this.requireBase(id, expectedHash);
      await this.packageFor(id, pointer, await this.primitives()); pointer.enabled = enabled;
      await this.writePointer(id, pointer);
    });
  }
  remove(id: string, expectedHash: string): Promise<void> {
    return sharedKvMutex.withLock(this.directory(id), async (): Promise<void> => {
      this.requireBase(id, expectedHash); fileIo.rmdirSync(this.directory(id));
      for (const key of this.healthCache.keys()) if (key.startsWith(`${id}/`)) this.healthCache.delete(key);
    });
  }
  rollback(id: string, expectedCurrentHash: string): Promise<PluginReceipt> {
    return sharedKvMutex.withLock(this.directory(id), async (): Promise<PluginReceipt> => {
      const current: PluginPointer = this.requireBase(id, expectedCurrentHash);
      if (current.previous === null) throw new PluginStoreError('no_previous');
      const primitives: AgentTool[] = await this.primitives();
      const old: PluginReadResult = await this.packageFor(id, current.previous, primitives);
      let live: PluginReadResult | null = null;
      try { live = await this.packageFor(id, current, primitives); }
      catch (error) {
        if (!(error instanceof PluginStoreError) && !(error instanceof PluginArchiveError) && !(error instanceof PluginValidationError)) {
          throw error as Error;
        }
      }
      const diff: string[] = permissionDiff(live?.candidate ?? null, old.candidate, live?.trust ?? null, old.trust);
      const expanded: boolean = live === null || diff.length > 0;
      const enabled: boolean = current.previous.enabled && !expanded;
      const pointer: PluginPointer = { hash: old.candidate.hash, enabled, trust: old.trust, previous: null };
      await this.writePointer(id, pointer); this.prunePackages(id, pointer);
      return { id, hash: old.candidate.hash, changed: true,
        enabled: enabled && this.health(id, old.candidate.hash).quarantinedAt === null,
        permissionExpanded: expanded, trust: old.trust };
    });
  }
  restore(id: string, expectedHash: string): Promise<void> {
    return sharedKvMutex.withLock(this.directory(id), async (): Promise<void> => {
      const pointer: PluginPointer = this.requireBase(id, expectedHash);
      await this.packageFor(id, pointer, await this.primitives());
      const health: PluginHealth = this.health(id, expectedHash);
      health.consecutiveFailures = 0; health.quarantinedAt = null; health.quarantineReason = null;
      await this.saveHealth(health);
    });
  }
  exportArchive(id: string, expectedHash: string): Promise<Uint8Array> {
    return sharedKvMutex.withLock(this.directory(id), async (): Promise<Uint8Array> => {
      const read: PluginReadResult = await this.packageFor(id, this.requireBase(id, expectedHash), await this.primitives());
      return this.archive.encode(read.candidate, read.trust);
    });
  }
  recordSuccess(id: string, hash: string): Promise<PluginHealth> {
    return sharedKvMutex.withLock(this.directory(id), async (): Promise<PluginHealth> => {
      if (this.pointer(id)?.hash !== hash) return this.healthCache.get(`${id}/${hash}`) ?? healthy(id, hash);
      const value: PluginHealth = this.health(id, hash);
      value.consecutiveFailures = 0; return this.saveHealth(value);
    });
  }
  recordFailure(id: string, hash: string, toolId: string, kind: PluginFailureKind, detail: string): Promise<PluginHealth> {
    return sharedKvMutex.withLock(this.directory(id), async (): Promise<PluginHealth> => {
      if (this.pointer(id)?.hash !== hash) return this.healthCache.get(`${id}/${hash}`) ?? healthy(id, hash);
      const value: PluginHealth = this.health(id, hash); value.consecutiveFailures++;
      value.diagnostics.push({ id: util.generateRandomUUID(), timeMs: Date.now(), toolId: toolId.slice(0, 160),
        kind, detail: Array.from(detail).slice(0, 500).join('') });
      value.diagnostics = value.diagnostics.slice(-50);
      if (value.consecutiveFailures >= 3 && value.quarantinedAt === null) {
        value.quarantinedAt = Date.now(); value.quarantineReason = kind;
      }
      return this.saveHealth(value);
    });
  }
  async listTrustedKeys(): Promise<PluginTrustedKey[]> {
    const path: string = `${this.root}/.trusted-keys.json`;
    if (statOrMissing(this.root) === null) return [];
    requireDirectory(this.root);
    if (statOrMissing(path) === null) return [];
    try {
      const keys: PluginTrustedKey[] = JSON.parse(pluginText(readBytes(path, 64 * 1024))) as PluginTrustedKey[];
      if (!Array.isArray(keys)) throw new PluginStoreError('plugin_store_corrupt');
      const fingerprints: Set<string> = new Set<string>();
      for (const key of keys) {
        if (key.fingerprint !== pluginPublicKeyFingerprint(key.publicKeyBase64) || fingerprints.has(key.fingerprint)) {
          throw new PluginStoreError('plugin_store_corrupt');
        }
        if (Array.from(key.label).join('') !== key.label) throw new PluginStoreError('plugin_store_corrupt');
        fingerprints.add(key.fingerprint);
      }
      return keys;
    } catch (error) {
      if (error instanceof SyntaxError || error instanceof TypeError) throw new PluginStoreError('plugin_store_corrupt');
      throw error as Error;
    }
  }
  addTrustedKey(publicKeyBase64: string, label: string): Promise<PluginTrustedKey> {
    const key: PluginTrustedKey = { fingerprint: pluginPublicKeyFingerprint(publicKeyBase64), publicKeyBase64,
      label: Array.from(label).slice(0, 80).join('') };
    return sharedKvMutex.withLock(`${this.root}/.trusted-keys.json`, async (): Promise<PluginTrustedKey> => {
      const keys: PluginTrustedKey[] = await this.listTrustedKeys();
      const kept: PluginTrustedKey[] = keys.filter((old: PluginTrustedKey): boolean => old.fingerprint !== key.fingerprint);
      kept.push(key);
      const text: string = JSON.stringify(kept);
      if (util.TextEncoder.create('utf-8').encodeInto(text).byteLength > 64 * 1024) {
        throw new PluginStoreError('file_budget_exceeded');
      }
      await writeTextFileAtomic(`${this.root}/.trusted-keys.json`, text); return key;
    });
  }
  removeTrustedKey(fingerprint: string): Promise<void> {
    return sharedKvMutex.withLock(`${this.root}/.trusted-keys.json`, async (): Promise<void> => {
      const keys: PluginTrustedKey[] = await this.listTrustedKeys();
      await writeTextFileAtomic(`${this.root}/.trusted-keys.json`, JSON.stringify(
        keys.filter((key: PluginTrustedKey): boolean => key.fingerprint !== fingerprint)));
    });
  }
}

export const createEntryPluginStore = async (
  context: common.UIAbilityContext, primitives: () => Promise<AgentTool[]>,
): Promise<PluginStore> => new EntryPluginStore(`${context.filesDir}/plugins`, primitives);
